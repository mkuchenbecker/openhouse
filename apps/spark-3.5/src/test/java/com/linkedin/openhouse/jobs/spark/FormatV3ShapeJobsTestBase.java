package com.linkedin.openhouse.jobs.spark;

import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.assertEveryReferencedFileExists;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.dataFilesWithDeletes;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.liveDataFiles;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.liveDeleteFiles;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.referencedFiles;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.rows;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.snapshotIds;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.common.stats.model.IcebergTableStats;
import com.linkedin.openhouse.datalayout.config.DataCompactionConfig;
import com.linkedin.openhouse.jobs.spark.FormatV3Tables.Reading;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.Table;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The maintenance jobs' real app code on one table shape at a time (work item X10: branches and
 * tags, an evolved partition spec, equality deletes), on format v3 and, for comparison, on format
 * v2. A subclass builds the shape ({@link #createShape}) holding data, deletes and several
 * snapshots, and names the ids that must not be live at each ref; every job test here runs on it.
 *
 * <p>Each job runs through {@code runInner} and its post-job validation, as {@link
 * FormatV3MaintenanceTest} does, once with the strict settings (compaction rewriting every file
 * with a delete, expiry keeping 2 snapshots and deleting files, orphan removal with no age margin)
 * and once with the arguments the scheduler passes ({@code createApp} with the task's {@code
 * getArgs}). After each: every ref (main, each branch and tag) reads the same rows with full
 * contents (main as the job is meant to leave it), deleted rows stay deleted on every ref, row
 * lineage ({@code _row_id}, {@code _last_updated_sequence_number}) is unchanged on every ref (v3),
 * every retained snapshot reads as before, every ref keeps its head, and every file any retained
 * snapshot references exists; and the job did real work, or the test says why it does none.
 *
 * <p>v2 tables live on the apps tests' service (switch off), v3 tables on {@link
 * FormatV3TablesServer}; v3 cases skip on the reference line (Iceberg 1.5.2).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FormatV3ShapeJobsTestBase extends OpenHouseSparkITest {
  static final String MAIN = SnapshotRef.MAIN_BRANCH;
  private static final String STORAGE_URL = "http://localhost:1";

  final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));

  /** The v2 and v3 sessions, the base fixture and the checks of the other format v3 job tests. */
  FormatV3Tables tables;

  private final Map<Integer, RESTCatalog> restCatalogs = new HashMap<>();

  /** A table of the shape and the ids that must not be live at each of its refs. */
  static final class Shape {
    final String fqtn;
    final Map<String, Set<Long>> deletedByRef;

    Shape(String fqtn, Map<String, Set<Long>> deletedByRef) {
      this.fqtn = fqtn;
      this.deletedByRef = deletedByRef;
    }
  }

  /** The database the class's tables live in. */
  abstract String db();

  /** Builds the shape's table {@code <db>.<name>_v<formatVersion>}, holding data and deletes. */
  abstract Shape createShape(int formatVersion, String name) throws Exception;

  @BeforeAll
  void start() throws Exception {
    tables = new FormatV3Tables(getSparkSession(), db(), otelEmitter);
  }

  @AfterAll
  void stop() throws Exception {
    for (RESTCatalog rest : restCatalogs.values()) {
      rest.close();
    }
    if (tables != null) {
      tables.close();
    }
  }

  // ------------------------------------------------------------------------------------ helpers

  SparkSession session(int formatVersion) {
    return tables.session(formatVersion);
  }

  Operations ops(int formatVersion) {
    return tables.ops(formatVersion);
  }

  String ts(int daysAgo, int minute) {
    return tables.ts(daysAgo, minute);
  }

  /** An Iceberg RESTCatalog on the {@code /iceberg} routes of the service of this format. */
  RESTCatalog rest(int formatVersion) {
    // The service's Iceberg REST routes: the session catalog's own uri on the Spark 4.2 line (a
    // RESTCatalog already), its /iceberg routes on the Spark 3.5 line (the OpenHouse catalog).
    SparkSession spark = session(formatVersion);
    String sessionUri = spark.conf().get(FormatV3Tables.CATALOG_PREFIX + ".uri");
    String uri = JobsLine.restCatalog(spark) ? sessionUri : sessionUri + "/iceberg";
    RESTCatalog existing = restCatalogs.get(formatVersion);
    if (existing != null) {
      return existing;
    }
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", uri);
    try {
      properties.put("token", new DummyTokenInterceptor.DummySecurityJWT("x10b").buildNoopJWT());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    RESTCatalog rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(),
                "x10b_rest_v" + formatVersion,
                properties,
                session(formatVersion).sparkContext().hadoopConfiguration());
    restCatalogs.put(formatVersion, rest);
    return rest;
  }

  static long idOf(String row) {
    return Long.parseLong(row.substring(1, row.indexOf(',')));
  }

  static Set<Long> ids(long... ids) {
    Set<Long> set = new TreeSet<>();
    for (long id : ids) {
      set.add(id);
    }
    return set;
  }

  static Set<Long> union(Set<Long> a, long... more) {
    Set<Long> set = new TreeSet<>(a);
    set.addAll(ids(more));
    return set;
  }

  /** What the table reads at each ref, besides what {@link Reading} records. */
  static final class RefsReading {
    final Reading table;
    final Map<String, Long> heads;
    final Map<String, List<String>> rows;
    final Map<String, List<String>> lineage;

    RefsReading(
        Reading table,
        Map<String, Long> heads,
        Map<String, List<String>> rows,
        Map<String, List<String>> lineage) {
      this.table = table;
      this.heads = heads;
      this.rows = rows;
      this.lineage = lineage;
    }
  }

  RefsReading read(int formatVersion, String fqtn) {
    SparkSession spark = session(formatVersion);
    Reading reading = tables.readingIn(spark, formatVersion, fqtn);
    Table table = ops(formatVersion).getTable(fqtn);
    String t = "openhouse." + fqtn;
    Map<String, Long> heads = new TreeMap<>();
    Map<String, List<String>> refRows = new TreeMap<>();
    Map<String, List<String>> refLineage = new TreeMap<>();
    for (Map.Entry<String, SnapshotRef> ref : table.refs().entrySet()) {
      String name = ref.getKey();
      heads.put(name, ref.getValue().snapshotId());
      String at = MAIN.equals(name) ? "" : " VERSION AS OF '" + name + "'";
      refRows.put(name, rows(spark, "SELECT * FROM " + t + at));
      if (formatVersion == 3) {
        refLineage.put(
            name, rows(spark, "SELECT id, _row_id, _last_updated_sequence_number FROM " + t + at));
      }
    }
    Assertions.assertEquals(reading.live, refRows.get(MAIN), "main reads as the table does");
    return new RefsReading(reading, heads, refRows, refLineage);
  }

  /**
   * The checks every job must pass: every ref other than main reads exactly as before (same head,
   * same rows, same lineage); main reads {@code mainRows} and {@code mainLineage}; the shape's
   * deleted ids stay deleted on every ref; every snapshot still in the table reads as before; and
   * every file a retained snapshot references exists.
   */
  void assertNoLiveDataLost(
      int formatVersion,
      Shape shape,
      RefsReading before,
      List<String> mainRows,
      List<String> mainLineage) {
    RefsReading after = read(formatVersion, shape.fqtn);
    Assertions.assertEquals(before.heads.keySet(), after.heads.keySet(), "refs kept");
    for (String ref : before.heads.keySet()) {
      if (MAIN.equals(ref)) {
        Assertions.assertEquals(mainRows, after.rows.get(ref), "main, full contents");
        if (formatVersion == 3) {
          Assertions.assertEquals(mainLineage, after.lineage.get(ref), "main, row lineage");
        }
      } else {
        Assertions.assertEquals(before.heads.get(ref), after.heads.get(ref), "head of " + ref);
        Assertions.assertEquals(before.rows.get(ref), after.rows.get(ref), ref + ", full contents");
        if (formatVersion == 3) {
          Assertions.assertEquals(
              before.lineage.get(ref), after.lineage.get(ref), ref + ", row lineage");
        }
      }
    }
    for (Map.Entry<String, Set<Long>> deleted : shape.deletedByRef.entrySet()) {
      Set<Long> live =
          after.rows.get(deleted.getKey()).stream()
              .map(FormatV3ShapeJobsTestBase::idOf)
              .collect(Collectors.toSet());
      for (long id : deleted.getValue()) {
        Assertions.assertFalse(
            live.contains(id), "row " + id + " stays deleted on " + deleted.getKey());
      }
    }
    Table table = ops(formatVersion).getTable(shape.fqtn);
    for (long id : snapshotIds(table)) {
      if (before.table.perSnapshot.containsKey(id)) {
        Assertions.assertEquals(
            before.table.perSnapshot.get(id),
            after.table.perSnapshot.get(id),
            "time travel to snapshot " + id);
      }
    }
    for (Map.Entry<String, Long> head : after.heads.entrySet()) {
      Assertions.assertNotNull(table.snapshot(head.getValue()), "head of " + head.getKey());
    }
    assertEveryReferencedFileExists(table);
  }

  void assertNoLiveDataLost(int formatVersion, Shape shape, RefsReading before) {
    assertNoLiveDataLost(
        formatVersion, shape, before, before.rows.get(MAIN), before.lineage.get(MAIN));
  }

  static boolean isDataOrDeleteFile(String path) {
    return path.endsWith(".orc") || path.endsWith(".parquet") || path.endsWith(".puffin");
  }

  static Path plant(FileSystem fs, Path path, long ageMillis) throws IOException {
    fs.create(path, true).close();
    long time = System.currentTimeMillis() - ageMillis;
    fs.setTimes(path, time, time);
    return path;
  }

  /**
   * Before an expiry test: the oldest partition leaves main by a metadata-only DELETE and one more
   * INSERT follows, so files referenced only by snapshots the expiry removes exist (unless a branch
   * or tag still references them, which the expiry must then keep).
   */
  void prepareExpiry(int formatVersion, Shape shape) {
    SparkSession spark = session(formatVersion);
    spark.sql("DELETE FROM openhouse." + shape.fqtn + " WHERE ts < " + ts(7, 0));
    spark.sql("INSERT INTO openhouse." + shape.fqtn + " VALUES " + extraRow(200));
  }

  /** A row the shape's table accepts, in today's partition, for the given id. */
  String extraRow(long id) {
    return "(" + id + ", 'extra-" + id + "', " + ts(0, 59) + ")";
  }

  /** Checks a shape adds after a job ran; nothing by default. */
  void afterJob(String job, int formatVersion, Shape shape, RefsReading before) throws Exception {}

  // --------------------------------------------------------------------------------- compaction

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void compactionRewritesEveryFileWithDeletesAndKeepsEveryRef(int formatVersion) throws Exception {
    Shape shape = createShape(formatVersion, "compact");
    RefsReading before = read(formatVersion, shape.fqtn);
    Table table = ops(formatVersion).getTable(shape.fqtn);
    Set<String> withDeletes = dataFilesWithDeletes(table);
    Assertions.assertFalse(withDeletes.isEmpty(), "main has data files with deletes");

    DataCompactionSparkApp app =
        new DataCompactionSparkApp(
            "x10b-compaction",
            null,
            shape.fqtn,
            DataCompactionConfig.builder().minInputFiles(2).deleteFileThreshold(1).build(),
            otelEmitter);
    Operations ops = ops(formatVersion);
    app.runInner(ops);
    app.runValidations(ops);

    table = ops(formatVersion).getTable(shape.fqtn);
    Assertions.assertEquals("replace", table.currentSnapshot().operation(), "compaction committed");
    Set<String> liveAfter =
        liveDataFiles(table, table.currentSnapshot()).stream()
            .map(d -> d.path().toString())
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        Collections.disjoint(withDeletes, liveAfter),
        "every data file of main that carried deletes was rewritten");
    assertNoLiveDataLost(formatVersion, shape, before);
    afterJob("compaction", formatVersion, shape, before);
  }

  /**
   * DATA_COMPACTION as the scheduler launches it: {@code --tableName} only, so every default
   * applies (TableDataCompactionTask.getArgs). Whether it plans a rewrite depends on the shape's
   * file counts; the test prints what it did and requires only that nothing is lost.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void compactionWithProductionArgumentsKeepsEveryRef(int formatVersion) throws Exception {
    Shape shape = createShape(formatVersion, "compact_prod");
    RefsReading before = read(formatVersion, shape.fqtn);
    Operations ops = ops(formatVersion);
    long current = ops.getTable(shape.fqtn).currentSnapshot().snapshotId();
    DataCompactionSparkApp app =
        DataCompactionSparkApp.createApp(
            new String[] {"--storageURL", STORAGE_URL, "--tableName", shape.fqtn}, otelEmitter);
    app.runInner(ops);
    app.runValidations(ops);
    Table table = ops(formatVersion).getTable(shape.fqtn);
    System.out.println(
        "X10B-COMPACTION-PROD "
            + getClass().getSimpleName()
            + " v"
            + formatVersion
            + " committed="
            + (table.currentSnapshot().snapshotId() != current));
    assertNoLiveDataLost(formatVersion, shape, before);
  }

  // ------------------------------------------------------------------- expiry and orphan removal

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void expiryWithFileDeletionThenOrphanRemovalKeepEveryRef(int formatVersion) throws Exception {
    Shape shape = createShape(formatVersion, "expire");
    prepareExpiry(formatVersion, shape);
    RefsReading before = read(formatVersion, shape.fqtn);
    Table table = ops(formatVersion).getTable(shape.fqtn);
    List<Long> idsBefore = snapshotIds(table);
    Set<String> filesBefore = referencedFiles(table);
    long mainHead = table.currentSnapshot().snapshotId();
    long mainParent = table.currentSnapshot().parentId();

    Operations ops = ops(formatVersion);
    SnapshotsExpirationSparkApp expiry =
        new SnapshotsExpirationSparkApp(
            "x10b-expiry", null, shape.fqtn, 3, "days", 2, true, ".backup", otelEmitter);
    expiry.runInner(ops);
    expiry.runValidations(ops);
    OrphanFilesDeletionSparkApp orphans =
        new OrphanFilesDeletionSparkApp(
            "x10b-ofd-after-expiry", null, shape.fqtn, 0, otelEmitter, ".backup", 2, false, 1000);
    orphans.runInner(ops);
    orphans.runValidations(ops);

    table = ops(formatVersion).getTable(shape.fqtn);
    List<Long> idsAfter = snapshotIds(table);
    Assertions.assertTrue(idsAfter.contains(mainHead) && idsAfter.contains(mainParent));
    Assertions.assertTrue(idsAfter.size() < idsBefore.size(), "expiry removed snapshots");
    assertNoLiveDataLost(formatVersion, shape, before);
    Set<String> filesAfter = referencedFiles(table);
    Table finalTable = table;
    List<String> removed =
        filesBefore.stream()
            .filter(f -> !filesAfter.contains(f) && !f.endsWith("metadata.json"))
            .collect(Collectors.toList());
    Assertions.assertTrue(
        removed.stream().anyMatch(FormatV3ShapeJobsTestBase::isDataOrDeleteFile),
        "expiry removed data or delete files, not only manifests: " + removed);
    Assertions.assertTrue(
        removed.stream().noneMatch(f -> finalTable.io().newInputFile(f).exists()),
        "files only expired snapshots referenced are gone");
    afterJob("expiry", formatVersion, shape, before);
  }

  /**
   * SNAPSHOTS_EXPIRATION and ORPHAN_FILES_DELETION as the scheduler launches them: expiry with
   * {@code --tableName --versions 2} (no {@code --deleteFiles}: metadata only), orphan removal with
   * {@code --tableName} (7-day age threshold). Files the expired snapshots alone referenced are
   * younger than 7 days, so orphan removal must leave them; a planted 8-day-old orphan is its work.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void expiryAndOrphanRemovalWithProductionArgumentsKeepEveryRef(int formatVersion)
      throws Exception {
    Shape shape = createShape(formatVersion, "expire_prod");
    prepareExpiry(formatVersion, shape);
    RefsReading before = read(formatVersion, shape.fqtn);
    Operations ops = ops(formatVersion);
    Table table = ops.getTable(shape.fqtn);
    int snapshotsBefore = snapshotIds(table).size();
    Set<String> filesBefore = referencedFiles(table);
    long eightDays = 8 * 86_400_000L;
    String liveDelete = liveDeleteFiles(table, table.currentSnapshot()).get(0).path().toString();
    List<Path> planted =
        Arrays.asList(
            plant(
                ops.fs(),
                new Path(new Path(liveDelete).getParent(), "x10b-old.parquet"),
                eightDays),
            plant(ops.fs(), new Path(table.location(), "metadata/x10b-old.avro"), eightDays));

    SnapshotsExpirationSparkApp expiry =
        SnapshotsExpirationSparkApp.createApp(
            new String[] {
              "--storageURL", STORAGE_URL, "--tableName", shape.fqtn, "--versions", "2"
            },
            otelEmitter);
    expiry.runInner(ops);
    expiry.runValidations(ops);
    OrphanFilesDeletionSparkApp orphans =
        OrphanFilesDeletionSparkApp.createApp(
            new String[] {"--storageURL", STORAGE_URL, "--tableName", shape.fqtn}, otelEmitter);
    orphans.runInner(ops);
    orphans.runValidations(ops);

    table = ops(formatVersion).getTable(shape.fqtn);
    Assertions.assertTrue(snapshotIds(table).size() < snapshotsBefore, "expiry removed snapshots");
    for (Path orphan : planted) {
      Assertions.assertFalse(ops.fs().exists(orphan), "planted old orphan removed: " + orphan);
    }
    for (String file : filesBefore) {
      Assertions.assertTrue(
          table.io().newInputFile(file).exists(), "a file younger than 7 days is kept: " + file);
    }
    assertNoLiveDataLost(formatVersion, shape, before);
  }

  @ParameterizedTest(name = "format v{0}, batched={1}")
  @CsvSource({"2, false", "3, false", "2, true", "3, true"})
  void orphanRemovalRemovesOnlyPlantedOrphans(int formatVersion, boolean batched) throws Exception {
    Shape shape = createShape(formatVersion, "ofd" + (batched ? "_batch" : ""));
    RefsReading before = read(formatVersion, shape.fqtn);
    Operations ops = ops(formatVersion);
    Table table = ops.getTable(shape.fqtn);
    FileSystem fs = ops.fs();
    long hour = 3_600_000L;
    Path liveDir =
        new Path(liveDeleteFiles(table, table.currentSnapshot()).get(0).path().toString())
            .getParent();
    List<Path> planted =
        Arrays.asList(
            plant(fs, new Path(liveDir, "x10b-orphan-deletes.puffin"), hour),
            plant(fs, new Path(liveDir, "x10b-orphan-data.parquet"), hour),
            plant(fs, new Path(table.location(), "metadata/x10b-orphan-manifest.avro"), hour));
    if (batched) {
      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "x10b-ofd-batch",
              null,
              otelEmitter,
              Collections.singletonList(
                  BatchedOrphanFilesDeletionSparkApp.BatchEntry.builder()
                      .fqtn(shape.fqtn)
                      .databaseName(db())
                      .tableName(shape.fqtn.substring(db().length() + 1))
                      .build()),
              null,
              1,
              0,
              ".backup",
              2,
              true,
              1000);
      app.runInner(ops);
    } else {
      OrphanFilesDeletionSparkApp app =
          new OrphanFilesDeletionSparkApp(
              "x10b-ofd", null, shape.fqtn, 0, otelEmitter, ".backup", 2, false, 1000);
      app.runInner(ops);
      app.runValidations(ops);
    }
    for (Path orphan : planted) {
      Assertions.assertFalse(fs.exists(orphan), "planted orphan removed: " + orphan);
    }
    assertNoLiveDataLost(formatVersion, shape, before);
  }

  // ---------------------------------------------------------------------------------- retention

  /**
   * RETENTION as the scheduler launches it (TableRetentionTask.getArgs): 3 days on {@code ts}. The
   * partitions 10 and 5 days old (ids 1 to 16 in every shape's layout) leave main; every other ref
   * is untouched.
   */
  @ParameterizedTest(name = "format v{0}, backup={1}")
  @CsvSource({"2, false", "3, false", "2, true", "3, true"})
  void retentionDeletesOnlyExpiredPartitionsOfMain(int formatVersion, boolean backup)
      throws Exception {
    Shape shape = createShape(formatVersion, "retention" + (backup ? "_backup" : ""));
    if (backup) {
      session(formatVersion)
          .sql(
              "ALTER TABLE openhouse."
                  + shape.fqtn
                  + " SET TBLPROPERTIES ('"
                  + AppConstants.BACKUP_ENABLED_KEY
                  + "'='true')");
    }
    RefsReading before = read(formatVersion, shape.fqtn);
    Assertions.assertTrue(
        before.rows.get(MAIN).stream().anyMatch(r -> idOf(r) < 17), "main has expired rows");
    RetentionSparkApp app =
        RetentionSparkApp.createApp(
            new String[] {
              "--storageURL",
              STORAGE_URL,
              "--tableName",
              shape.fqtn,
              "--columnName",
              "ts",
              "--granularity",
              "day",
              "--count",
              "3"
            },
            otelEmitter);
    Operations ops = ops(formatVersion);
    app.runInner(ops);
    app.runValidations(ops);

    List<String> expectedMain =
        before.rows.get(MAIN).stream().filter(r -> idOf(r) >= 17).collect(Collectors.toList());
    List<String> expectedLineage =
        formatVersion == 3
            ? before.lineage.get(MAIN).stream()
                .filter(r -> idOf(r) >= 17)
                .collect(Collectors.toList())
            : null;
    assertNoLiveDataLost(formatVersion, shape, before, expectedMain, expectedLineage);
    afterJob("retention", formatVersion, shape, before);
  }

  // ------------------------------------------------------------------------------- stats jobs

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void tableStatsCollectionReadsTheShapeAndChangesNothing(int formatVersion) throws Exception {
    Shape shape = createShape(formatVersion, "stats");
    RefsReading before = read(formatVersion, shape.fqtn);
    Operations ops = ops(formatVersion);
    Table table = ops.getTable(shape.fqtn);
    long current = table.currentSnapshot().snapshotId();
    IcebergTableStats stats = ops.collectTableStats(shape.fqtn);
    Assertions.assertNotNull(stats, "stats collection failed (it returns null on an exception)");
    Assertions.assertEquals((long) snapshotIds(table).size(), (long) stats.getNumSnapshots());
    Assertions.assertFalse(ops.collectCommitEventTable(shape.fqtn).isEmpty());
    Assertions.assertFalse(ops.collectCommitEventTablePartitions(shape.fqtn).isEmpty());
    Assertions.assertFalse(ops.collectCommitEventTablePartitionStats(shape.fqtn).isEmpty());
    TableStatsCollectionSparkApp app =
        new TableStatsCollectionSparkApp("x10b-stats", null, shape.fqtn, otelEmitter);
    app.runInner(ops);
    app.runValidations(ops);
    Assertions.assertEquals(current, ops.getTable(shape.fqtn).currentSnapshot().snapshotId());
    assertNoLiveDataLost(formatVersion, shape, before);
    afterJob("stats", formatVersion, shape, before);
  }

  /** Helper for subclasses: an ordered map of ref name to the ids deleted there. */
  static Map<String, Set<Long>> deleted(Object... refAndIds) {
    Map<String, Set<Long>> map = new LinkedHashMap<>();
    for (int i = 0; i < refAndIds.length; i += 2) {
      @SuppressWarnings("unchecked")
      Set<Long> ids = (Set<Long>) refAndIds[i + 1];
      map.put((String) refAndIds[i], ids);
    }
    return map;
  }

  static List<String> sorted(List<String> rows) {
    List<String> copy = new ArrayList<>(rows);
    Collections.sort(copy);
    return copy;
  }
}
