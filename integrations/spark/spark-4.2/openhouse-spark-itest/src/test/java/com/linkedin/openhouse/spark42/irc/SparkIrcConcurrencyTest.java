package com.linkedin.openhouse.spark42.irc;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.iceberg.spark.actions.SparkActions;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The first three races of work item X10 through the Iceberg REST routes, from Spark 4.2 with
 * Iceberg's own catalog and client and no OpenHouse class in the engine, on a format v3 table and
 * on a v2 merge-on-read table for comparison. The Spark 3.5 suite ({@code FormatV3ConcurrencyTest}
 * in the apps project) runs them, and the others, through the native OpenHouse catalog.
 *
 * <p>Interleaved by construction: the writer that is to lose runs on a catalog whose REST client is
 * {@link HookedRestCatalog}; when it sends its commit (built and validated against base N), the
 * winner runs to completion first, on the ordinary catalog, on its own thread, and commits N+1;
 * then the loser's request goes on with its requirements on N. The service checks them against the
 * stored table and refuses (409), and Iceberg's commit loop refreshes and runs its conflict checks
 * against N+1.
 *
 * <p>Acceptable: the loser fails with a conflict its caller sees and the table is exactly as the
 * winner left it, or it succeeds and the table reads what a serial order gives. After each case no
 * deleted row is back, row ids are unchanged and unique (v3), every file any snapshot references
 * exists, and on v3 no data file has more than one deletion vector or a dangling one.
 */
public class SparkIrcConcurrencyTest {
  private static final String DB = "irc42";
  private static final List<String> TABLES = new ArrayList<>();
  private static ThreadPoolExecutor winnerThread;

  @BeforeAll
  static void addTheCatalogs() throws Exception {
    // Started before any statement of this class, so it carries no Spark SQL execution.
    winnerThread =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    winnerThread.prestartAllCoreThreads();
    SparkSession spark = IrcSpark.session();
    IrcReadTestServiceProcess v2 = IrcReadTestServiceProcess.get();
    IrcReadTestServiceProcess v3 = IrcReadTestServiceProcess.getWithFormatV3();
    catalog(spark, "x10_v2", v2, false);
    catalog(spark, "x10_v2_loser", v2, true);
    catalog(spark, "x10_v3", v3, false);
    catalog(spark, "x10_v3_loser", v3, true);
    // In the server-planning run, the v3 catalogs keep client planning (declared in catalog()).
  }

  private static void catalog(
      SparkSession spark, String name, IrcReadTestServiceProcess service, boolean hooked)
      throws Exception {
    String prefix = "spark.sql.catalog." + name;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    if (hooked) {
      spark.conf().set(prefix + ".catalog-impl", HookedRestCatalog.class.getName());
    } else {
      spark.conf().set(prefix + ".type", "rest");
    }
    spark.conf().set(prefix + ".uri", service.uri() + "/iceberg");
    spark.conf().set(prefix + ".token", service.token(IrcSpark.READER));
    spark.conf().set(prefix + ".cache-enabled", "false");
    if (service == IrcReadTestServiceProcess.getWithFormatV3()) {
      ServerPlanning.notApplied(prefix, ServerPlanning.FORMAT_V3);
    } else {
      ServerPlanning.apply(prefix, spark.conf()::set);
    }
  }

  @AfterAll
  static void dropTheTables() throws Exception {
    for (String table : TABLES) {
      IrcSpark.rows("DROP TABLE IF EXISTS " + table);
    }
    if (winnerThread != null) {
      winnerThread.shutdownNow();
    }
  }

  private static String winner(int formatVersion, String name) {
    return "x10_v" + formatVersion + "." + DB + "." + name;
  }

  private static String loser(int formatVersion, String name) {
    return "x10_v" + formatVersion + "_loser." + DB + "." + name;
  }

  private static SparkSession spark() throws Exception {
    return IrcSpark.session();
  }

  private static List<String> rows(String sql) throws Exception {
    return spark().sql(sql).collectAsList().stream()
        .map(Row::toString)
        .sorted()
        .collect(Collectors.toList());
  }

  /**
   * A merge-on-read table partitioned by {@code p}: 2 partitions, two INSERTs of 4 rows each per
   * partition (4 data files; ids {@code 4k + 1} to {@code 4k + 4} share a file), then a DELETE of
   * one row in every file, so each file has a delete (a deletion vector on v3).
   */
  private static String createTable(int formatVersion, String name) throws Exception {
    String table = "x10_" + name + "_v" + formatVersion;
    String t = winner(formatVersion, table);
    spark().sql("DROP TABLE IF EXISTS " + t);
    spark()
        .sql(
            "CREATE TABLE "
                + t
                + " (id bigint, data string, p int) USING iceberg PARTITIONED BY (p) TBLPROPERTIES"
                + " ('write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read',"
                + " 'write.merge.mode'='merge-on-read'"
                + (formatVersion == 3 ? ", 'format-version'='3'" : "")
                + ")");
    TABLES.add(t);
    for (int k = 0; k < 4; k++) {
      List<String> values = new ArrayList<>();
      for (int r = 1; r <= 4; r++) {
        long id = 4L * k + r;
        values.add("(" + id + ", 'row-" + id + "', " + (k / 2) + ")");
      }
      spark().sql("INSERT INTO " + t + " VALUES " + String.join(", ", values));
    }
    spark().sql("DELETE FROM " + t + " WHERE id IN (2, 6, 10, 14)");
    List<String> formats = rows("SELECT DISTINCT file_format FROM " + t + ".delete_files");
    Assertions.assertEquals(1, formats.size(), formats.toString());
    Assertions.assertEquals(formatVersion == 3, formats.get(0).equals("[PUFFIN]"), formats.get(0));
    return table;
  }

  private static Table icebergTable(String name) throws Exception {
    return Spark3Util.loadIcebergTable(spark(), name);
  }

  private interface Step {
    void run() throws Exception;
  }

  private static final class State {
    final String metadataLocation;
    final long snapshotId;
    final List<String> live;
    final Set<Long> ids;
    final Map<Long, Long> rowIds;
    final List<Long> allRowIds;

    State(
        String metadataLocation,
        long snapshotId,
        List<String> live,
        Set<Long> ids,
        Map<Long, Long> rowIds,
        List<Long> allRowIds) {
      this.metadataLocation = metadataLocation;
      this.snapshotId = snapshotId;
      this.live = live;
      this.ids = ids;
      this.rowIds = rowIds;
      this.allRowIds = allRowIds;
    }
  }

  private static State state(int formatVersion, String table) throws Exception {
    String t = winner(formatVersion, table);
    spark().sql("REFRESH TABLE " + t);
    Table iceberg = icebergTable(t);
    Set<Long> ids = new TreeSet<>();
    Map<Long, Long> rowIds = new HashMap<>();
    List<Long> allRowIds = new ArrayList<>();
    if (formatVersion == 3) {
      for (Row row : spark().sql("SELECT id, _row_id FROM " + t).collectAsList()) {
        ids.add(row.getLong(0));
        Long rowId = row.isNullAt(1) ? null : row.getLong(1);
        rowIds.put(row.getLong(0), rowId);
        allRowIds.add(rowId);
      }
    } else {
      spark().sql("SELECT id FROM " + t).collectAsList().forEach(r -> ids.add(r.getLong(0)));
    }
    return new State(
        ((HasTableOperations) iceberg).operations().current().metadataFileLocation(),
        iceberg.currentSnapshot().snapshotId(),
        rows("SELECT * FROM " + t),
        ids,
        rowIds,
        allRowIds);
  }

  private enum Outcome {
    CONFLICT,
    SERIAL
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  private static boolean isConflict(Throwable thrown) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t instanceof ValidationException || t instanceof CommitFailedException) {
        return true;
      }
    }
    return false;
  }

  /**
   * Runs {@code loser}, whose commit request on {@code table} first runs {@code winner}; then
   * checks the outcome and pins it.
   *
   * @param serialSql a query over the table at N ({@code %s}) reading what a serial order gives
   * @param inserted ids the steps insert
   */
  private static void race(
      String name,
      int formatVersion,
      String table,
      Step loser,
      Step winner,
      String serialSql,
      Outcome expected,
      Set<Long> inserted)
      throws Exception {
    String key = DB + "." + table;
    State base = state(formatVersion, table);
    List<String> serial =
        rows(
            String.format(
                serialSql, winner(formatVersion, table) + " VERSION AS OF " + base.snapshotId));
    AtomicReference<State> winnerState = new AtomicReference<>();
    AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
    HookedRestCatalog.arm(
        key,
        () -> {
          try {
            winnerThread
                .submit(
                    () -> {
                      // Iceberg's Spark actions read the session as the thread's active one.
                      SparkSession.setActiveSession(spark());
                      winner.run();
                      winnerState.set(state(formatVersion, table));
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
      stillArmed = HookedRestCatalog.armed(key);
      HookedRestCatalog.disarm(key);
    }
    if (winnerFailure.get() != null) {
      throw new AssertionError("the winner failed", winnerFailure.get());
    }
    Assertions.assertFalse(stillArmed, "the loser never sent a commit: " + chain(loserFailure));
    State after = state(formatVersion, table);
    State won = winnerState.get();
    String raced = HookedRestCatalog.racedAttempt(key);
    Outcome actual = loserFailure == null ? Outcome.SERIAL : Outcome.CONFLICT;
    System.out.println(
        "X10-RACE rest v"
            + formatVersion
            + " "
            + name
            + " outcome="
            + actual
            + " raced="
            + (raced == null || raced.length() < 160 ? raced : raced.substring(0, 160) + "...")
            + " attempts="
            + HookedRestCatalog.attempts(key).size()
            + " failure="
            + (loserFailure == null ? "-" : chain(loserFailure)));
    if (actual == Outcome.CONFLICT) {
      Assertions.assertTrue(isConflict(loserFailure), "a conflict: " + chain(loserFailure));
      Assertions.assertEquals(
          won.metadataLocation, after.metadataLocation, "the table as the winner left it");
      Assertions.assertEquals(won.live, after.live);
      Assertions.assertEquals(won.rowIds, after.rowIds);
    } else {
      Assertions.assertEquals(serial, after.live, "rows of a serial order");
    }
    Assertions.assertEquals(expected, actual, "pinned outcome: " + chain(loserFailure));

    for (long id = 1; id <= 16; id++) {
      if (!base.ids.contains(id) && !inserted.contains(id)) {
        Assertions.assertFalse(after.ids.contains(id), "row " + id + " was deleted at N");
      }
      if (base.ids.contains(id) && !won.ids.contains(id)) {
        Assertions.assertFalse(after.ids.contains(id), "the winner deleted row " + id);
      }
    }
    if (formatVersion == 3) {
      Assertions.assertFalse(after.allRowIds.contains(null), "every row has a row id");
      Assertions.assertEquals(
          after.allRowIds.size(), new HashSet<>(after.allRowIds).size(), "unique row ids");
      for (Map.Entry<Long, Long> row : base.rowIds.entrySet()) {
        if (after.rowIds.containsKey(row.getKey())) {
          Assertions.assertEquals(row.getValue(), after.rowIds.get(row.getKey()), "row id kept");
        }
      }
    }
    assertEveryReferencedFileExists(formatVersion, table);
    if (formatVersion == 3) {
      String t = winner(formatVersion, table);
      Assertions.assertEquals(
          Collections.emptyList(),
          rows(
              "SELECT referenced_data_file, count(*) FROM "
                  + t
                  + ".delete_files GROUP BY referenced_data_file HAVING count(*) > 1"),
          "at most one deletion vector per data file");
      Assertions.assertEquals(
          Collections.emptyList(),
          rows(
              "SELECT referenced_data_file FROM "
                  + t
                  + ".delete_files WHERE referenced_data_file NOT IN (SELECT file_path FROM "
                  + t
                  + ".data_files)"),
          "no dangling deletion vector");
    }
    Assertions.assertNotNull(raced, "the raced request ended");
    if (!won.metadataLocation.equals(base.metadataLocation)) {
      Assertions.assertTrue(
          raced.startsWith("CommitFailedException"),
          "the service refused the commit built on N: " + raced);
    }
  }

  /** Every file a snapshot references, read from the metadata tables, exists. */
  private static void assertEveryReferencedFileExists(int formatVersion, String table)
      throws Exception {
    String t = winner(formatVersion, table);
    List<String> files = new ArrayList<>();
    for (String sql :
        new String[] {
          "SELECT file_path FROM " + t + ".all_files",
          "SELECT path FROM " + t + ".all_manifests",
          "SELECT manifest_list FROM " + t + ".snapshots"
        }) {
      spark().sql(sql).collectAsList().forEach(r -> files.add(r.getString(0)));
    }
    files.add(((HasTableOperations) icebergTable(t)).operations().current().metadataFileLocation());
    List<String> missing =
        files.stream()
            .filter(
                f ->
                    !Files.exists(
                        f.startsWith("file:") ? Paths.get(URI.create(f).getPath()) : Paths.get(f)))
            .collect(Collectors.toList());
    Assertions.assertFalse(files.isEmpty());
    Assertions.assertEquals(Collections.emptyList(), missing, "referenced files exist");
  }

  /** Compaction as the OpenHouse job runs it, in one commit: every file with deletes rewritten. */
  private static void compaction(String name) throws Exception {
    SparkActions.get(spark())
        .rewriteDataFiles(icebergTable(name))
        .binPack()
        .option("min-input-files", "2")
        .option("delete-file-threshold", "1")
        .option("partial-progress.enabled", "false")
        .execute();
  }

  // ------------------------------------------------------------------------------------ cases

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void compactionPreparedThenADeleteAddsDeletesToTheFilesItRewrites(int formatVersion)
      throws Exception {
    String table = createTable(formatVersion, "compact_vs_delete");
    race(
        "compaction-then-delete",
        formatVersion,
        table,
        () -> compaction(loser(formatVersion, table)),
        () ->
            spark()
                .sql("DELETE FROM " + winner(formatVersion, table) + " WHERE id IN (1, 9)")
                .collectAsList(),
        "SELECT * FROM %s WHERE id NOT IN (1, 9)",
        Outcome.CONFLICT,
        Collections.emptySet());
  }

  @ParameterizedTest(name = "format v{0}, {1}")
  @CsvSource({"2, delete", "3, delete", "2, update", "3, update", "2, merge", "3, merge"})
  void rowLevelWritePreparedThenCompactionReplacesItsFiles(int formatVersion, String op)
      throws Exception {
    String table = createTable(formatVersion, "rlw_vs_compact_" + op);
    String t = loser(formatVersion, table);
    String sql;
    String serial;
    switch (op) {
      case "delete":
        sql = "DELETE FROM " + t + " WHERE id IN (5, 13)";
        serial = "SELECT * FROM %s WHERE id NOT IN (5, 13)";
        break;
      case "update":
        sql = "UPDATE " + t + " SET data = 'x10-update' WHERE id IN (5, 13)";
        serial =
            "SELECT id, CASE WHEN id IN (5, 13) THEN 'x10-update' ELSE data END AS data, p FROM %s";
        break;
      default:
        sql =
            "MERGE INTO "
                + t
                + " target USING (SELECT * FROM VALUES (5L, 'u'), (13L, 'd'), (300L, 'i') AS"
                + " s(id, op)) source ON target.id = source.id"
                + " WHEN MATCHED AND source.op = 'd' THEN DELETE"
                + " WHEN MATCHED THEN UPDATE SET target.data = 'x10-merge'"
                + " WHEN NOT MATCHED THEN INSERT (id, data, p) VALUES (source.id, 'x10-insert', 1)";
        serial =
            "SELECT id, CASE WHEN id = 5 THEN 'x10-merge' ELSE data END AS data, p FROM %s"
                + " WHERE id <> 13 UNION ALL SELECT 300L, 'x10-insert', 1";
    }
    race(
        op + "-then-compaction",
        formatVersion,
        table,
        () -> spark().sql(sql).collectAsList(),
        () -> compaction(winner(formatVersion, table)),
        serial,
        Outcome.CONFLICT,
        "merge".equals(op) ? Collections.singleton(300L) : Collections.emptySet());
  }

  @ParameterizedTest(name = "format v{0}, loser {1}")
  @CsvSource({
    "2, delete, SERIAL",
    "3, delete, CONFLICT",
    "2, update, CONFLICT",
    "3, update, CONFLICT"
  })
  void twoRowLevelWritesOnOneDataFile(int formatVersion, String op, Outcome expected)
      throws Exception {
    String table = createTable(formatVersion, "same_file_" + op);
    String t = loser(formatVersion, table);
    String sql =
        "delete".equals(op)
            ? "DELETE FROM " + t + " WHERE id = 5"
            : "UPDATE " + t + " SET data = 'x10-update' WHERE id = 5";
    String serial =
        "delete".equals(op)
            ? "SELECT * FROM %s WHERE id NOT IN (5, 8)"
            : "SELECT id, CASE WHEN id = 5 THEN 'x10-update' ELSE data END AS data, p FROM %s"
                + " WHERE id <> 8";
    race(
        op + "-then-delete-same-file",
        formatVersion,
        table,
        () -> spark().sql(sql).collectAsList(),
        () ->
            spark()
                .sql("DELETE FROM " + winner(formatVersion, table) + " WHERE id = 8")
                .collectAsList(),
        serial,
        expected,
        Collections.emptySet());
  }
}
