package com.linkedin.openhouse.jobs.spark;

import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.CATALOG_PREFIX;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.DELETED_IDS;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.assertEveryReferencedFileExists;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.assertNoDanglingDeletionVectors;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.liveDeleteFiles;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.read;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.referencedDataFile;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.rows;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.snapshotIds;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.datalayout.config.DataCompactionConfig;
import com.linkedin.openhouse.javaclient.CommitHook;
import com.linkedin.openhouse.javaclient.CommitHookCatalog;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Row-level writes (DELETE, UPDATE, MERGE) racing the maintenance jobs on Iceberg format v3 tables,
 * through OpenHouse's own commit path, each case run the same way on a v2 merge-on-read table for
 * comparison.
 *
 * <p>Every case is interleaved by construction, with no sleeps and no reliance on thread timing.
 * The writer that is to lose the race runs on a session whose OpenHouse catalog is {@link
 * CommitHookCatalog}: it plans, writes its files and builds and validates its commit against base N
 * as in production; at the start of its first commit attempt (before the client sends the snapshots
 * PUT to the tables service), the winner runs to completion on the ordinary catalog and commits
 * N+1; then the loser's attempt goes on with base N. The tables service refuses it (409), and
 * Iceberg's commit loop refreshes to N+1 and runs its conflict checks against it, or the loser's
 * step had nothing to commit and is unaffected.
 *
 * <p>The acceptable outcomes are exactly two: the loser fails with an error its caller sees, and
 * the table is exactly as the winner left it (same metadata file, rows and row ids); or the loser
 * succeeds, and the table reads what a serial order of the two gives. After every case: no deleted
 * row is back, the {@code _row_id} of every surviving row is unchanged and every row id is unique
 * (v3), every file any snapshot references exists, and on v3 no data file has more than one
 * deletion vector and none has a dangling one. Each case also pins which of the two outcomes it
 * gets, and the outcome of each commit attempt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FormatV3ConcurrencyTest extends OpenHouseSparkITest {
  private static final String DB = "x10conc";
  /** Ids the fixture ever writes, beyond which the cases insert 200 and 300. */
  private static final List<Long> FIXTURE_IDS = fixtureIds();

  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));

  private FormatV3Tables tables;
  /** Per format version, a session whose {@code openhouse} catalog is {@link CommitHookCatalog}. */
  private final Map<Integer, SparkSession> hooked = new HashMap<>();

  private final Map<Integer, Operations> loserOpsByFormat = new ConcurrentHashMap<>();
  /**
   * The thread the winner runs on, started before any Spark statement, so that it carries no Spark
   * SQL execution of the loser's thread (Spark's local properties are inherited by threads a thread
   * creates). The loser waits for the winner to finish.
   */
  private ThreadPoolExecutor winnerThread;

  @BeforeAll
  void start() throws Exception {
    tables = new FormatV3Tables(getSparkSession(), DB, otelEmitter);
    winnerThread =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    winnerThread.prestartAllCoreThreads();
  }

  @AfterAll
  void stop() {
    if (winnerThread != null) {
      winnerThread.shutdownNow();
    }
    for (Operations ops : loserOpsByFormat.values()) {
      try {
        ops.close();
      } catch (Exception e) {
        // best effort
      }
    }
    loserOpsByFormat.clear();
    if (tables != null) {
      tables.close();
    }
  }

  private static List<Long> fixtureIds() {
    List<Long> ids = new ArrayList<>();
    for (long id = 1; id <= 32; id++) {
      ids.add(id);
    }
    ids.add(100L);
    ids.add(200L);
    ids.add(300L);
    return Collections.unmodifiableList(ids);
  }

  // ------------------------------------------------------------------------------- the harness

  /** The session of the writer that loses the race: the same service, the hooked catalog. */
  private SparkSession loserSession(int formatVersion) {
    SparkSession base = tables.session(formatVersion);
    return hooked.computeIfAbsent(
        formatVersion,
        v -> {
          SparkSession session = FormatV3Tables.copyCatalogs(base, base.newSession());
          session.conf().set(CATALOG_PREFIX + ".catalog-impl", CommitHookCatalog.class.getName());
          session.conf().set(CATALOG_PREFIX + ".cache-enabled", "false");
          return session;
        });
  }

  /**
   * The loser's {@link Operations}: one per format version, independent of the winner's ({@code
   * tables.ops}: another object, another catalog), closed in {@link #stop()}. A new one per call,
   * never closed, left one undisposed connection pool each.
   */
  private Operations loserOps(int formatVersion) {
    SparkSession session = loserSession(formatVersion);
    return loserOpsByFormat.computeIfAbsent(
        formatVersion, v -> Operations.withCatalog(session, otelEmitter));
  }

  /** A statement on the winner's (ordinary) session, on the table as it is now. */
  private void winnerSql(int formatVersion, String fqtn, String sql) {
    SparkSession spark = tables.session(formatVersion);
    spark.sql("REFRESH TABLE openhouse." + fqtn);
    spark.sql(sql);
  }

  private interface Step {
    void run() throws Exception;
  }

  /** What a table holds at one point: its metadata file, rows, row ids and snapshots. */
  private static final class State {
    final String metadataLocation;
    final long snapshotId;
    final List<Long> snapshots;
    final List<String> live;
    final Set<Long> ids;
    /** id to {@code _row_id}, v3 only. */
    final Map<Long, Long> rowIds;

    final List<Long> allRowIds;

    State(
        String metadataLocation,
        long snapshotId,
        List<Long> snapshots,
        List<String> live,
        Set<Long> ids,
        Map<Long, Long> rowIds,
        List<Long> allRowIds) {
      this.metadataLocation = metadataLocation;
      this.snapshotId = snapshotId;
      this.snapshots = snapshots;
      this.live = live;
      this.ids = ids;
      this.rowIds = rowIds;
      this.allRowIds = allRowIds;
    }
  }

  private State state(int formatVersion, String fqtn) {
    SparkSession spark = tables.session(formatVersion);
    String t = "openhouse." + fqtn;
    spark.sql("REFRESH TABLE " + t);
    Table table = tables.ops(formatVersion).getTable(fqtn);
    List<String> live = rows(spark, "SELECT * FROM " + t);
    Set<Long> ids = new TreeSet<>();
    Map<Long, Long> rowIds = new HashMap<>();
    List<Long> allRowIds = new ArrayList<>();
    if (formatVersion == 3) {
      for (Row row : read(spark, "SELECT id, _row_id FROM " + t)) {
        ids.add(row.getLong(0));
        Long rowId = row.isNullAt(1) ? null : row.getLong(1);
        rowIds.put(row.getLong(0), rowId);
        allRowIds.add(rowId);
      }
    } else {
      read(spark, "SELECT id FROM " + t).forEach(r -> ids.add(r.getLong(0)));
    }
    return new State(
        ((HasTableOperations) table).operations().current().metadataFileLocation(),
        table.currentSnapshot().snapshotId(),
        snapshotIds(table),
        live,
        ids,
        rowIds,
        allRowIds);
  }

  /** One race: the table before, as the winner left it, and after the loser; the loser's error. */
  private static final class Race {
    final State base;
    final State winner;
    final State after;
    final Throwable loserFailure;
    final List<String> attempts;
    /** The outcome of the attempt the winner ran in (built on N, sent after N+1 landed). */
    String racedAttempt;
    /** What a serial order of the two gives, read before the race: {@code null} if not asked. */
    final List<String> serial;

    Race(
        State base,
        State winner,
        State after,
        Throwable loserFailure,
        List<String> attempts,
        List<String> serial) {
      this.base = base;
      this.winner = winner;
      this.after = after;
      this.loserFailure = loserFailure;
      this.attempts = attempts;
      this.serial = serial;
    }
  }

  /**
   * Runs {@code loser}, whose first commit attempt on {@code fqtn} runs {@code winner} to
   * completion first.
   *
   * @param serialSql a query over the table at base N ({@code %s}) that reads what a serial order
   *     of the two steps gives; both orders give the same rows in every case here
   */
  private Race race(int formatVersion, String fqtn, Step loser, Step winner, String serialSql) {
    State base = state(formatVersion, fqtn);
    List<String> serial =
        serialSql == null
            ? null
            : rows(
                tables.session(formatVersion),
                String.format(
                    serialSql, "openhouse." + fqtn + " VERSION AS OF " + base.snapshotId));
    AtomicReference<State> winnerState = new AtomicReference<>();
    AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
    CommitHook.arm(
        fqtn,
        () -> {
          try {
            winnerThread
                .submit(
                    () -> {
                      winner.run();
                      winnerState.set(state(formatVersion, fqtn));
                      return null;
                    })
                .get();
          } catch (ExecutionException e) {
            winnerFailure.set(e.getCause());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            winnerFailure.set(e);
          }
        });
    Throwable loserFailure = null;
    boolean stillArmed;
    try {
      loser.run();
    } catch (Throwable t) {
      loserFailure = t;
    } finally {
      stillArmed = CommitHook.armed(fqtn);
      CommitHook.disarm(fqtn);
    }
    if (winnerFailure.get() != null) {
      throw new AssertionError("the winner failed", winnerFailure.get());
    }
    Assertions.assertFalse(
        stillArmed,
        "the loser never reached a commit attempt, so nothing raced: " + chain(loserFailure));
    Race race =
        new Race(
            base,
            winnerState.get(),
            state(formatVersion, fqtn),
            loserFailure,
            CommitHook.attempts(fqtn),
            serial);
    race.racedAttempt = CommitHook.racedAttempt(fqtn);
    return race;
  }

  private enum Outcome {
    /** The loser failed with an error its caller sees; the table is as the winner left it. */
    CONFLICT,
    /** The loser committed; the table reads what a serial order gives. */
    SERIAL
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  private static String abbreviate(String s) {
    return s == null || s.length() <= 120 ? s : s.substring(0, 120) + "...";
  }

  private static boolean isConflict(Throwable thrown) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t instanceof ValidationException
          || t instanceof CommitFailedException
          || (t instanceof IllegalArgumentException
              && String.valueOf(t.getMessage()).contains("is not an ancestor of"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The checks every race must pass, and the outcome it is pinned to.
   *
   * @param inserted ids the two steps insert
   */
  private void assertSafe(
      String name,
      int formatVersion,
      String fqtn,
      Race race,
      Outcome expected,
      Set<Long> inserted) {
    assertSafe(name, formatVersion, fqtn, race, expected, inserted, false, Raced.REFUSED);
  }

  private void assertSafe(
      String name,
      int formatVersion,
      String fqtn,
      Race race,
      Outcome expected,
      Set<Long> inserted,
      boolean danglingDeletionVectorsPinned) {
    assertSafe(
        name,
        formatVersion,
        fqtn,
        race,
        expected,
        inserted,
        danglingDeletionVectorsPinned,
        Raced.REFUSED);
  }

  /**
   * What the tables service does with the loser's attempt built on N, sent after the winner
   * committed N+1.
   */
  private enum Raced {
    /** Refused as a conflict; the loser's commit loop refreshes and retries, or fails. */
    REFUSED,
    /**
     * Accepted as sent, and the result is one of the serial orders. Over the Iceberg REST routes
     * only (Spark 4.2 line): a REST commit sends its changes and its requirements (the branch's
     * snapshot id), not whole metadata built on N; a winner that does not move the branch (snapshot
     * expiry) leaves the requirements true, so the service applies the changes to the current
     * metadata. The OpenHouse catalog sends metadata built on N, which the service refuses.
     */
    ACCEPTED_SERIAL_EQUIVALENT
  }

  private void assertSafe(
      String name,
      int formatVersion,
      String fqtn,
      Race race,
      Outcome expected,
      Set<Long> inserted,
      boolean danglingDeletionVectorsPinned,
      Raced raced) {
    Outcome actual = race.loserFailure == null ? Outcome.SERIAL : Outcome.CONFLICT;
    System.out.println(
        "RACE native v"
            + formatVersion
            + " "
            + name
            + " outcome="
            + actual
            + " raced="
            + abbreviate(race.racedAttempt)
            + " attempts="
            + race.attempts.size()
            + " failure="
            + (race.loserFailure == null ? "-" : chain(race.loserFailure)));
    if (actual == Outcome.CONFLICT) {
      Assertions.assertTrue(
          isConflict(race.loserFailure),
          "the loser failed with a conflict: " + chain(race.loserFailure));
      Assertions.assertEquals(
          race.winner.metadataLocation,
          race.after.metadataLocation,
          "a failed commit left the table exactly as the winner left it");
      Assertions.assertEquals(race.winner.live, race.after.live, "rows as the winner left them");
      Assertions.assertEquals(race.winner.rowIds, race.after.rowIds, "row ids as the winner left");
    } else {
      Assertions.assertNotNull(race.serial, "a serial outcome to compare with");
      Assertions.assertEquals(race.serial, race.after.live, "rows of a serial order");
    }
    Assertions.assertEquals(expected, actual, "pinned outcome: " + chain(race.loserFailure));

    // No deleted row is back: rows absent at N, and rows the winner deleted, stay absent.
    for (long id : FIXTURE_IDS) {
      if (!race.base.ids.contains(id) && !inserted.contains(id)) {
        Assertions.assertFalse(race.after.ids.contains(id), "row " + id + " was deleted at N");
      }
      if (race.base.ids.contains(id) && !race.winner.ids.contains(id)) {
        Assertions.assertFalse(race.after.ids.contains(id), "the winner deleted row " + id);
      }
    }
    for (long id : DELETED_IDS) {
      Assertions.assertFalse(race.after.ids.contains(id), "fixture-deleted row " + id);
    }
    if (formatVersion == 3) {
      Assertions.assertFalse(race.after.allRowIds.contains(null), "every row has a row id");
      Assertions.assertEquals(
          race.after.allRowIds.size(),
          new HashSet<>(race.after.allRowIds).size(),
          "row ids are unique: " + race.after.rowIds);
      for (Map.Entry<Long, Long> row : race.base.rowIds.entrySet()) {
        if (race.after.rowIds.containsKey(row.getKey())) {
          Assertions.assertEquals(
              row.getValue(),
              race.after.rowIds.get(row.getKey()),
              "row " + row.getKey() + " keeps its _row_id");
        }
      }
    }
    Table table = tables.ops(formatVersion).getTable(fqtn);
    assertEveryReferencedFileExists(table);
    if (formatVersion == 3) {
      assertAtMostOneDeletionVectorPerDataFile(table);
      if (!danglingDeletionVectorsPinned) {
        assertNoDanglingDeletionVectors(formatVersion, table);
      }
    }
    // The race went through the service: when the winner committed, the loser's attempt built on
    // N was refused by the tables service as a conflict, or (declared per case) accepted;
    // otherwise it committed.
    Assertions.assertNotNull(race.racedAttempt, "the raced attempt ended: " + race.attempts);
    if (race.winner.metadataLocation.equals(race.base.metadataLocation)) {
      Assertions.assertEquals("committed", race.racedAttempt);
    } else if (raced == Raced.ACCEPTED_SERIAL_EQUIVALENT) {
      Assertions.assertEquals(
          "committed", race.racedAttempt, "the service accepted the attempt built on N");
    } else {
      Assertions.assertTrue(
          isRefusal(race.racedAttempt),
          "the service refused the attempt built on N: " + race.racedAttempt);
    }
  }

  /**
   * How the tables service's refusal of a commit built on a stale base reads to the loser. The
   * OpenHouse catalog (Spark 3.5 line) gets the native API's 409 "Conflict detected". Over the
   * Iceberg REST routes (Spark 4.2 line) the service checks the commit's requirements and answers
   * the specification's conflict, which Iceberg's RESTCatalog reports as "Commit failed:
   * Requirement failed: ...": the same refusal, in that route's wording.
   */
  private boolean isRefusal(String attempt) {
    if (JobsLine.restCatalog(loserSession(2))) {
      return attempt.startsWith("CommitFailedException: Commit failed: Requirement failed: ");
    }
    return attempt.startsWith("CommitFailedException: 409")
        && attempt.contains("Conflict detected");
  }

  /**
   * {@link Raced#ACCEPTED_SERIAL_EQUIVALENT} over the Iceberg REST routes, {@link Raced#REFUSED}
   * with the OpenHouse catalog, for a loser racing a snapshot expiry; see {@link Raced}.
   */
  private Raced racedWithExpiry(int formatVersion) {
    return JobsLine.restCatalog(loserSession(formatVersion))
        ? Raced.ACCEPTED_SERIAL_EQUIVALENT
        : Raced.REFUSED;
  }

  /**
   * The data, delete and manifest files (and manifest lists) {@code table} references, without the
   * metadata files, which expiry keeps.
   */
  private static Set<String> contentFiles(Table table) {
    Set<String> files = new TreeSet<>(FormatV3Tables.referencedFiles(table));
    files.removeIf(f -> f.endsWith(".metadata.json"));
    return files;
  }

  /**
   * The expiry deleted what only the expired snapshots referenced, and nothing brought it back:
   * those files are gone from storage and no snapshot of the table references them.
   */
  private void assertExpiredFilesStayDeleted(
      int formatVersion, String fqtn, Set<String> before, Set<String> keptByExpiry) {
    Table table = tables.ops(formatVersion).getTable(fqtn);
    Set<String> expired = new TreeSet<>(before);
    expired.removeAll(keptByExpiry);
    Assertions.assertFalse(expired.isEmpty(), "the expiry had files to delete");
    Set<String> referenced = contentFiles(table);
    for (String file : expired) {
      Assertions.assertFalse(referenced.contains(file), "an expired file is referenced: " + file);
      Assertions.assertFalse(table.io().newInputFile(file).exists(), "expired file back: " + file);
    }
  }

  /** The format v3 rule: at most one deletion vector per data file in a snapshot. */
  private static void assertAtMostOneDeletionVectorPerDataFile(Table table) {
    Map<String, List<String>> byDataFile = new HashMap<>();
    for (DeleteFile d : liveDeleteFiles(table, table.currentSnapshot())) {
      byDataFile
          .computeIfAbsent(referencedDataFile(d), k -> new ArrayList<>())
          .add(d.path().toString());
    }
    for (Map.Entry<String, List<String>> e : byDataFile.entrySet()) {
      Assertions.assertEquals(1, e.getValue().size(), "deletion vectors of " + e.getKey());
    }
  }

  private static Set<Long> ids(Long... ids) {
    return new HashSet<>(Arrays.asList(ids));
  }

  // ----------------------------------------------------------------------------- the steps

  /**
   * Compaction as the maintenance job runs it on v3 (every data file that carries deletes is
   * rewritten), with partial progress off so that it commits once: with partial progress on, its
   * file groups commit from more than one thread, so the race would not be one by construction
   * (each group's commit is a rewrite with the same conflict checks).
   */
  private void compaction(Operations ops, String fqtn) {
    DataCompactionSparkApp app =
        new DataCompactionSparkApp(
            "x10-compaction",
            null,
            fqtn,
            DataCompactionConfig.builder()
                .minInputFiles(2)
                .deleteFileThreshold(1)
                .partialProgressEnabled(false)
                .build(),
            otelEmitter);
    app.runInner(ops);
    app.runValidations(ops);
  }

  /** Snapshot expiry with file deletion, keeping the current snapshot only. */
  private void expiry(Operations ops, String fqtn) {
    SnapshotsExpirationSparkApp app =
        new SnapshotsExpirationSparkApp(
            "x10-expiry", null, fqtn, 3, "days", 1, true, ".backup", otelEmitter);
    app.runInner(ops);
    app.runValidations(ops);
  }

  /** Retention of 3 days: the partitions 10 and 5 days old (ids 1 to 16) go. */
  private void retention(Operations ops, String fqtn) {
    RetentionSparkApp app =
        new RetentionSparkApp(
            "x10-retention", null, fqtn, "ts", "", "day", 3, otelEmitter, ".backup");
    app.runInner(ops);
    app.runValidations(ops);
  }

  /**
   * The row-level writes, on id 5 (the data file of ids 5 to 8, 10 days old) and id 21 (1 day old);
   * the MERGE also inserts id 300.
   */
  private static String rowLevelWrite(String op, String t) {
    switch (op) {
      case "delete":
        return "DELETE FROM " + t + " WHERE id IN (5, 21)";
      case "update":
        return "UPDATE " + t + " SET data = 'x10-update' WHERE id IN (5, 21)";
      case "merge":
        return "MERGE INTO "
            + t
            + " target USING (SELECT * FROM VALUES (5L, 'u'), (21L, 'd'), (300L, 'i') AS s(id, op))"
            + " source ON target.id = source.id"
            + " WHEN MATCHED AND source.op = 'd' THEN DELETE"
            + " WHEN MATCHED THEN UPDATE SET target.data = 'x10-merge'"
            + " WHEN NOT MATCHED THEN INSERT (id, data, ts) VALUES (source.id, 'x10-insert',"
            + " TIMESTAMP '2000-01-01 00:00:00')";
      default:
        throw new IllegalArgumentException(op);
    }
  }

  /** What {@link #rowLevelWrite} leaves, as a query over the table at N ({@code %s}). */
  private static String afterRowLevelWrite(String op) {
    switch (op) {
      case "delete":
        return "SELECT * FROM %s WHERE id NOT IN (5, 21)";
      case "update":
        return "SELECT id, CASE WHEN id IN (5, 21) THEN 'x10-update' ELSE data END AS data, ts"
            + " FROM %s";
      case "merge":
        return "SELECT id, CASE WHEN id = 5 THEN 'x10-merge' ELSE data END AS data, ts FROM %s"
            + " WHERE id <> 21 UNION ALL SELECT 300L, 'x10-insert', TIMESTAMP '2000-01-01 00:00:00'";
      default:
        throw new IllegalArgumentException(op);
    }
  }

  // ------------------------------------------------------------------------------ compaction

  /**
   * Compaction plans and rewrites at N; a DELETE then adds deletes (deletion vectors on v3) to data
   * files the compaction rewrites; then the compaction commits. Committing the rewrite would drop
   * the new deletes with the files they apply to and bring the deleted rows back.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void compactionPreparedThenADeleteAddsDeletesToTheFilesItRewrites(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "compact_vs_delete", "orc");
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> compaction(loserOps(formatVersion), fqtn),
            () ->
                winnerSql(
                    formatVersion,
                    fqtn,
                    "DELETE FROM openhouse." + fqtn + " WHERE id IN (1, 17, 25)"),
            "SELECT * FROM %s WHERE id NOT IN (1, 17, 25)");
    assertSafe("compaction-then-delete", formatVersion, fqtn, race, Outcome.CONFLICT, ids());
  }

  /**
   * Compaction prepared at N; an INSERT commits N+1 (new rows, new row ids on v3); the compaction
   * then commits on its retry. Row ids of the rewritten rows and of the inserted rows must stay
   * distinct.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void compactionPreparedThenAnInsertCommits(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "compact_vs_insert", "orc");
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> compaction(loserOps(formatVersion), fqtn),
            () ->
                winnerSql(
                    formatVersion,
                    fqtn,
                    "INSERT INTO openhouse."
                        + fqtn
                        + " VALUES (200, 'x10-insert', "
                        + tables.ts(0, 50)
                        + ")"),
            "SELECT * FROM %s UNION ALL SELECT 200L, 'x10-insert', " + tables.ts(0, 50));
    assertSafe("compaction-then-insert", formatVersion, fqtn, race, Outcome.SERIAL, ids(200L));
  }

  /**
   * A DELETE, UPDATE or MERGE prepared at N (its deletes and new rows written); compaction then
   * replaces the files it targets; then the row-level write commits. Committing would attach its
   * deletes to files that are gone, and keep the compacted copies of the rows it deleted.
   */
  @ParameterizedTest(name = "format v{0}, {1}")
  @CsvSource({"2, delete", "3, delete", "2, update", "3, update", "2, merge", "3, merge"})
  void rowLevelWritePreparedThenCompactionReplacesItsFiles(int formatVersion, String op) {
    String fqtn = tables.createTable(formatVersion, "rlw_vs_compact_" + op, "orc");
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> loserSession(formatVersion).sql(rowLevelWrite(op, "openhouse." + fqtn)),
            () -> compaction(tables.ops(formatVersion), fqtn),
            afterRowLevelWrite(op));
    assertSafe(
        op + "-then-compaction",
        formatVersion,
        fqtn,
        race,
        Outcome.CONFLICT,
        "merge".equals(op) ? ids(300L) : ids());
  }

  // ---------------------------------------------------------- two writes on one data file

  /**
   * Two row-level writes on one data file (ids 5 to 8, which already has a deletion vector for 6
   * and 7 on v3): the loser's write of row 5 is prepared at N, the winner's DELETE of row 8 commits
   * N+1, then the loser commits. On v3 a data file has at most one deletion vector per snapshot:
   * the second must merge with or fail against the first, never sit next to it or replace it.
   */
  @ParameterizedTest(name = "format v{0}, loser {1}")
  @CsvSource({
    "2, delete, SERIAL",
    "3, delete, CONFLICT",
    "2, update, CONFLICT",
    "3, update, CONFLICT"
  })
  void twoRowLevelWritesOnOneDataFile(int formatVersion, String op, Outcome expected) {
    String fqtn = tables.createTable(formatVersion, "same_file_" + op, "orc");
    String t = "openhouse." + fqtn;
    String loser =
        "delete".equals(op)
            ? "DELETE FROM " + t + " WHERE id = 5"
            : "UPDATE " + t + " SET data = 'x10-update' WHERE id = 5";
    String serial =
        "delete".equals(op)
            ? "SELECT * FROM %s WHERE id NOT IN (5, 8)"
            : "SELECT id, CASE WHEN id = 5 THEN 'x10-update' ELSE data END AS data, ts FROM %s"
                + " WHERE id <> 8";
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> loserSession(formatVersion).sql(loser),
            () -> winnerSql(formatVersion, fqtn, "DELETE FROM " + t + " WHERE id = 8"),
            serial);
    assertSafe(op + "-then-delete-same-file", formatVersion, fqtn, race, expected, ids());
  }

  // ------------------------------------------------------------------------- snapshot expiry

  /**
   * A row-level write prepared at N; snapshot expiry (with file deletion, keeping only N) runs;
   * then the write commits. The write's commit must not bring back the expired snapshots, whose
   * files are gone.
   */
  @ParameterizedTest(name = "format v{0}, {1}")
  @CsvSource({"2, delete", "3, delete", "2, update", "3, update"})
  void rowLevelWritePreparedThenSnapshotExpiryRuns(int formatVersion, String op) {
    String fqtn = tables.createTable(formatVersion, "rlw_vs_expiry_" + op, "orc");
    Set<String> filesAtN = contentFiles(tables.ops(formatVersion).getTable(fqtn));
    Set<String> filesOfN = new TreeSet<>();
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> loserSession(formatVersion).sql(rowLevelWrite(op, "openhouse." + fqtn)),
            () -> {
              expiry(tables.ops(formatVersion), fqtn);
              filesOfN.addAll(contentFiles(tables.ops(formatVersion).getTable(fqtn)));
            },
            afterRowLevelWrite(op));
    assertSafe(
        op + "-then-expiry",
        formatVersion,
        fqtn,
        race,
        Outcome.SERIAL,
        ids(),
        false,
        racedWithExpiry(formatVersion));
    Assertions.assertEquals(
        Collections.singletonList(race.base.snapshotId), race.winner.snapshots, "expiry kept N");
    // No expired snapshot comes back: N and the write only, on either line.
    Assertions.assertEquals(
        2, race.after.snapshots.size(), "N and the write: " + race.after.snapshots);
    Assertions.assertEquals(race.base.snapshotId, (long) race.after.snapshots.get(0));
    assertExpiredFilesStayDeleted(formatVersion, fqtn, filesAtN, filesOfN);
  }

  /**
   * Snapshot expiry (keeping one snapshot, deleting files) prepared at N; a DELETE commits N+1;
   * then the expiry commits on its retry, and deletes the files only expired snapshots referenced.
   * It must not delete a file N+1 references.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void snapshotExpiryPreparedThenADeleteCommits(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "expiry_vs_delete", "orc");
    Set<String> filesAtN = contentFiles(tables.ops(formatVersion).getTable(fqtn));
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> expiry(loserOps(formatVersion), fqtn),
            () ->
                winnerSql(
                    formatVersion, fqtn, "DELETE FROM openhouse." + fqtn + " WHERE id IN (5, 21)"),
            "SELECT * FROM %s WHERE id NOT IN (5, 21)");
    Raced raced = racedWithExpiry(formatVersion);
    assertSafe(
        "expiry-then-delete", formatVersion, fqtn, race, Outcome.SERIAL, ids(), false, raced);
    // Both lines end in the serial order DELETE-then-expiry: only the DELETE's snapshot is kept.
    // With the OpenHouse catalog the expiry's attempt built on N is refused and it retries on N+1;
    // over the REST routes that attempt (the job's age pass) is accepted (see Raced), and the job's
    // second pass (keep the last version) then runs on the table with the DELETE and expires N.
    Assertions.assertEquals(
        Collections.singletonList(race.winner.snapshotId), race.after.snapshots, "kept the DELETE");
    // What N's snapshot references is kept on both lines (N, or the DELETE built on it).
    Table table = tables.ops(formatVersion).getTable(fqtn);
    Set<String> kept = new TreeSet<>();
    for (String file : filesAtN) {
      if (table.io().newInputFile(file).exists()) {
        kept.add(file);
      }
    }
    assertExpiredFilesStayDeleted(formatVersion, fqtn, filesAtN, kept);
  }

  /**
   * A row-level write scans N; an INSERT commits N+1 and expiry keeping one snapshot removes N;
   * then the write commits. Its conflict checks start from N, which no longer exists.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void rowLevelWriteWhoseScanSnapshotIsExpiredBeforeItCommits(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "rlw_scan_expired", "orc");
    String t = "openhouse." + fqtn;
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> loserSession(formatVersion).sql(rowLevelWrite("delete", t)),
            () -> {
              winnerSql(
                  formatVersion,
                  fqtn,
                  "INSERT INTO " + t + " VALUES (200, 'x10-insert', " + tables.ts(0, 50) + ")");
              expiry(tables.ops(formatVersion), fqtn);
            },
            null);
    assertSafe(
        "delete-then-insert-and-expiry", formatVersion, fqtn, race, Outcome.CONFLICT, ids(200L));
  }

  // ------------------------------------------------------------------------------- retention

  /**
   * A DELETE prepared at N targets a row in a partition past retention (id 1, 10 days old) and one
   * inside it (id 25); retention drops the old partitions; then the DELETE commits on its retry
   * (its delete of row 1 is moot: the row's file is gone), and the table reads as retention then
   * the DELETE.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void rowLevelWritePreparedThenRetentionDropsAPartitionItTargets(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "rlw_vs_retention", "orc");
    String t = "openhouse." + fqtn;
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> loserSession(formatVersion).sql("DELETE FROM " + t + " WHERE id IN (1, 25)"),
            () -> retention(tables.ops(formatVersion), fqtn),
            "SELECT * FROM %s WHERE id >= 17 AND id <> 25");
    assertSafe("delete-then-retention", formatVersion, fqtn, race, Outcome.SERIAL, ids(), true);
    // Iceberg's check that a row-level write's data files still exist skips files a DELETE
    // snapshot removed, for a DELETE statement (its rows are gone either way), so the DELETE
    // commits, and its delete for row 1 now applies to a data file the table no longer has. Rows
    // read as in a serial order. Pinned: inside Iceberg (MergingSnapshotProducer
    // validateDataFilesExist with skipDeletes), not in OpenHouse's commit path.
    if (formatVersion == 3) {
      Table table = tables.ops(formatVersion).getTable(fqtn);
      Set<String> live =
          FormatV3Tables.liveDataFiles(table, table.currentSnapshot()).stream()
              .map(d -> d.path().toString())
              .collect(Collectors.toSet());
      List<String> dangling =
          liveDeleteFiles(table, table.currentSnapshot()).stream()
              .map(FormatV3Tables::referencedDataFile)
              .filter(f -> !live.contains(f))
              .collect(Collectors.toList());
      Assertions.assertEquals(1, dangling.size(), "one dangling deletion vector: " + dangling);
      Assertions.assertTrue(
          dangling.get(0).contains("ts_day=" + tables.today.minusDays(10).toLocalDate()),
          "for the data file of row 1, which retention removed: " + dangling);
    }
  }

  /** Same rows, full contents, and on v3 the same {@code _row_id} per row. */
  private static void assertSameRows(State expected, State actual, String step) {
    Assertions.assertEquals(expected.live, actual.live, "rows after " + step);
    Assertions.assertEquals(expected.rowIds, actual.rowIds, "row ids after " + step);
  }

  /** On v3: the live deletion vectors whose data file is not live, as their data files' paths. */
  private static List<String> danglingDeletionVectors(Table table) {
    Set<String> live =
        FormatV3Tables.liveDataFiles(table, table.currentSnapshot()).stream()
            .map(d -> d.path().toString())
            .collect(Collectors.toSet());
    return liveDeleteFiles(table, table.currentSnapshot()).stream()
        .map(FormatV3Tables::referencedDataFile)
        .filter(f -> f != null && !live.contains(f))
        .collect(Collectors.toList());
  }

  /**
   * What happens after the race above leaves a dangling deletion vector (v3), as the jobs that own
   * the table run next. Compaction (every data file with deletes rewritten) and snapshot expiry
   * keep it: no rewrite group holds a data file that is gone, and the vector is live in the current
   * snapshot. Nothing misbehaves meanwhile: rows read the same, every file a snapshot references
   * exists (the Puffin file is live), and table stats collect. The next retention run drops it:
   * retention's DELETE removes every delete file whose partition the row filter covers (Iceberg's
   * MergingSnapshotProducer.deleteByRowFilter), and the vector's partition is the one retention
   * dropped. Rows stay as they were. The same steps on v2.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void aDanglingDeletionVectorFromTheRaceIsDroppedByTheNextRetentionRun(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "rlw_vs_retention_next", "orc");
    String t = "openhouse." + fqtn;
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> loserSession(formatVersion).sql("DELETE FROM " + t + " WHERE id IN (1, 25)"),
            () -> retention(tables.ops(formatVersion), fqtn),
            "SELECT * FROM %s WHERE id >= 17 AND id <> 25");
    assertSafe("delete-then-retention", formatVersion, fqtn, race, Outcome.SERIAL, ids(), true);
    Operations ops = tables.ops(formatVersion);
    State afterRace = state(formatVersion, fqtn);
    Assertions.assertEquals(race.after.live, afterRace.live);
    if (formatVersion == 3) {
      Assertions.assertEquals(1, danglingDeletionVectors(ops.getTable(fqtn)).size());
    }

    compaction(ops, fqtn);
    Table table = ops.getTable(fqtn);
    System.out.println(
        "AFTER-RACE v"
            + formatVersion
            + " after compaction: "
            + table.currentSnapshot().operation());
    assertSameRows(afterRace, state(formatVersion, fqtn), "compaction");
    assertEveryReferencedFileExists(table);
    if (formatVersion == 3) {
      Assertions.assertEquals(
          1, danglingDeletionVectors(table).size(), "compaction keeps the dangling vector");
    }

    expiry(ops, fqtn);
    table = ops.getTable(fqtn);
    Assertions.assertEquals(1, snapshotIds(table).size(), "expiry kept the current snapshot");
    assertSameRows(afterRace, state(formatVersion, fqtn), "expiry");
    assertEveryReferencedFileExists(table);
    if (formatVersion == 3) {
      Assertions.assertEquals(
          1, danglingDeletionVectors(table).size(), "expiry keeps the dangling vector");
    }
    Assertions.assertNotNull(ops.collectTableStats(fqtn), "table stats collect");

    long beforeRetention = table.currentSnapshot().snapshotId();
    retention(ops, fqtn);
    table = ops.getTable(fqtn);
    System.out.println(
        "AFTER-RACE v"
            + formatVersion
            + " second retention: "
            + (table.currentSnapshot().snapshotId() == beforeRetention
                ? "no commit"
                : table.currentSnapshot().operation() + " " + table.currentSnapshot().summary()));
    assertSameRows(afterRace, state(formatVersion, fqtn), "retention again");
    assertEveryReferencedFileExists(table);
    assertNoDanglingDeletionVectors(formatVersion, table);
  }

  /** Retention prepared at N; the same DELETE commits N+1; then retention commits on its retry. */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void retentionPreparedThenADeleteInBothPartitionsCommits(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "retention_vs_rlw", "orc");
    String t = "openhouse." + fqtn;
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> retention(loserOps(formatVersion), fqtn),
            () -> winnerSql(formatVersion, fqtn, "DELETE FROM " + t + " WHERE id IN (1, 25)"),
            "SELECT * FROM %s WHERE id >= 17 AND id <> 25");
    assertSafe("retention-then-delete", formatVersion, fqtn, race, Outcome.SERIAL, ids());
  }

  // ------------------------------------------------------------------- orphan file removal

  private static Set<String> filesUnder(FileSystem fs, Path dir) throws IOException {
    Set<String> files = new TreeSet<>();
    RemoteIterator<LocatedFileStatus> it = fs.listFiles(dir, true);
    while (it.hasNext()) {
      files.add(it.next().getPath().toUri().getPath());
    }
    return files;
  }

  /**
   * Orphan-file removal as production launches it ({@code createApp}: {@code --ttl} defaults to 7
   * days and is raised to at least 1 day, so {@code --ttl 0} runs with 1 day) runs between an
   * UPDATE's file writes and its commit. The UPDATE's data files, deletes (Puffin on v3), manifests
   * and manifest list are referenced by no snapshot yet and seconds old: they must stay, and the
   * UPDATE then commits.
   */
  @ParameterizedTest(name = "format v{0}, ttl argument {1}")
  @CsvSource({"2, none", "3, none", "2, 0", "3, 0"})
  void orphanRemovalAtTheProductionThresholdKeepsAWritersUncommittedFiles(
      int formatVersion, String ttl) throws Exception {
    String fqtn = tables.createTable(formatVersion, "ofd_prod_" + ttl, "orc");
    Operations ops = tables.ops(formatVersion);
    FileSystem fs = ops.fs();
    Path location = new Path(ops.getTable(fqtn).location());
    Set<String> before = filesUnder(fs, location);
    AtomicReference<Set<String>> inFlight = new AtomicReference<>();
    AtomicReference<Long> appTtlSeconds = new AtomicReference<>();
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> loserSession(formatVersion).sql(rowLevelWrite("update", "openhouse." + fqtn)),
            () -> {
              Set<String> written = filesUnder(fs, location);
              written.removeAll(before);
              inFlight.set(written);
              List<String> args =
                  new ArrayList<>(
                      Arrays.asList("--storageURL", "http://localhost:1", "--tableName", fqtn));
              if (!"none".equals(ttl)) {
                args.addAll(Arrays.asList("--ttl", ttl));
              }
              OrphanFilesDeletionSparkApp app =
                  OrphanFilesDeletionSparkApp.createApp(args.toArray(new String[0]), otelEmitter);
              app.runInner(tables.ops(formatVersion));
              appTtlSeconds.set(app.getTtlSeconds());
              app.runValidations(tables.ops(formatVersion));
            },
            afterRowLevelWrite("update"));
    assertInFlightFilesOfAnUpdate(formatVersion, inFlight.get());
    Assertions.assertEquals(
        "none".equals(ttl) ? TimeUnit.DAYS.toSeconds(7) : TimeUnit.DAYS.toSeconds(1),
        (long) appTtlSeconds.get(),
        "the production threshold");
    List<String> removed =
        inFlight.get().stream().filter(f -> !fileExists(fs, f)).collect(Collectors.toList());
    Assertions.assertEquals(Collections.emptyList(), removed, "uncommitted files kept");
    assertSafe("update-then-orphans-ttl-" + ttl, formatVersion, fqtn, race, Outcome.SERIAL, ids());
    Assertions.assertEquals(Collections.singletonList("committed"), race.attempts);
  }

  /**
   * The same with no age margin (a TTL of 0, which only the app's constructor accepts; the command
   * line raises it to 1 day): pinned to show that the age threshold is the only thing that keeps a
   * writer's uncommitted files, so it must exceed the longest time between a writer's first file
   * write and its commit.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void orphanRemovalWithNoAgeMarginRemovesAWritersUncommittedFiles(int formatVersion)
      throws Exception {
    String fqtn = tables.createTable(formatVersion, "ofd_no_margin", "orc");
    Operations ops = tables.ops(formatVersion);
    FileSystem fs = ops.fs();
    Path location = new Path(ops.getTable(fqtn).location());
    Set<String> before = filesUnder(fs, location);
    AtomicReference<Set<String>> inFlight = new AtomicReference<>();
    State base = state(formatVersion, fqtn);
    CommitHook.arm(
        fqtn,
        () -> {
          try {
            winnerThread
                .submit(
                    () -> {
                      Set<String> written = filesUnder(fs, location);
                      written.removeAll(before);
                      inFlight.set(written);
                      new OrphanFilesDeletionSparkApp(
                              "x10-ofd-0", null, fqtn, 0, otelEmitter, ".backup", 2, false, 1000)
                          .runInner(tables.ops(formatVersion));
                      return null;
                    })
                .get();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    Throwable failure = null;
    try {
      loserSession(formatVersion).sql(rowLevelWrite("update", "openhouse." + fqtn));
    } catch (Throwable t) {
      failure = t;
    } finally {
      CommitHook.disarm(fqtn);
    }
    assertInFlightFilesOfAnUpdate(formatVersion, inFlight.get());
    List<String> removed =
        inFlight.get().stream().filter(f -> !fileExists(fs, f)).collect(Collectors.toList());
    Table table = tables.ops(formatVersion).getTable(fqtn);
    List<String> missing = new ArrayList<>();
    boolean committed = table.currentSnapshot().snapshotId() != base.snapshotId;
    if (committed) {
      String manifestList = table.currentSnapshot().manifestListLocation();
      if (!table.io().newInputFile(manifestList).exists()) {
        missing.add(manifestList);
      }
    }
    System.out.println(
        "RACE native v"
            + formatVersion
            + " update-then-orphans-ttl-0-constructor removed="
            + removed.size()
            + "/"
            + inFlight.get().size()
            + " committed="
            + committed
            + " missingAfterCommit="
            + missing
            + " attempts="
            + CommitHook.attempts(fqtn)
            + " failure="
            + (failure == null ? "-" : chain(failure)));
    Assertions.assertEquals(inFlight.get(), new TreeSet<>(removed), "every uncommitted file went");
    Assertions.assertNull(failure, "the UPDATE commits all the same: " + chain(failure));
    Assertions.assertTrue(committed, "the UPDATE committed");
    Assertions.assertFalse(missing.isEmpty(), "and its snapshot references a removed file");
  }

  private static boolean fileExists(FileSystem fs, String path) {
    try {
      return fs.exists(new Path(path));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** What an UPDATE has written when it starts to commit: data, deletes, manifests, a list. */
  private static void assertInFlightFilesOfAnUpdate(int formatVersion, Set<String> inFlight) {
    Assertions.assertNotNull(inFlight, "the orphan removal ran inside the UPDATE's commit");
    Assertions.assertTrue(
        inFlight.stream().anyMatch(f -> f.endsWith(".orc") && !f.contains("-deletes")),
        "a new data file: " + inFlight);
    Assertions.assertTrue(
        inFlight.stream()
            .anyMatch(f -> formatVersion == 3 ? f.endsWith(".puffin") : f.contains("-deletes")),
        "a new delete file: " + inFlight);
    Assertions.assertTrue(
        inFlight.stream().anyMatch(f -> new Path(f).getName().startsWith("snap-")),
        "a manifest list: " + inFlight);
  }

  // ----------------------------------------------------------------- row lineage on a retry

  /**
   * An UPDATE prepared at N (its new data file carries the updated rows' row ids on v3); an INSERT
   * commits N+1 with new rows; the UPDATE commits on its retry, with first-row-ids assigned again.
   * The updated rows keep their row ids, and no row id is shared.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void updatePreparedThenAnInsertCommits(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "update_vs_insert", "orc");
    String t = "openhouse." + fqtn;
    Race race =
        race(
            formatVersion,
            fqtn,
            () -> loserSession(formatVersion).sql(rowLevelWrite("update", t)),
            () ->
                winnerSql(
                    formatVersion,
                    fqtn,
                    "INSERT INTO " + t + " VALUES (200, 'x10-insert', " + tables.ts(0, 50) + ")"),
            afterRowLevelWrite("update")
                + " UNION ALL SELECT 200L, 'x10-insert', "
                + tables.ts(0, 50));
    assertSafe("update-then-insert", formatVersion, fqtn, race, Outcome.SERIAL, ids(200L));
  }
}
