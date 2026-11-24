package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.datalayout.config.DataCompactionConfig;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expressions;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

/**
 * Every maintenance job's real app code on a format v3 table with a variant column that holds data
 * (work item X5), on a tables service with the cluster switch and the variant property on. The data
 * is written and read with Iceberg's own generic writers and readers ({@link VariantData}), since
 * Spark 3.5 has no variant type: Parquet data files in four daily partitions, two per partition,
 * with object, array, string and null variant values, and deletion vectors in two Puffin files on
 * six of the eight data files; every commit goes through the tables service.
 *
 * <p>The outcome differs by line ({@link JobsLine#sparkReadsVariant()}), and each test states both:
 * on Spark 3.5, which cannot read a variant column, the jobs that read the table through Spark fail
 * and every closing validation fails; on Spark 4.2 every job completes and is correct, and every
 * closing validation passes.
 *
 * <p>Each job has exactly one acceptable outcome, asserted here: it completes and the table is
 * correct (every live row, variant values and row lineage included, equal before and after; deleted
 * rows stay deleted; every referenced file exists), or it fails with a visible error and the table
 * is exactly as it was (the same current snapshot, nothing removed from storage, and any file the
 * job wrote unreferenced). Format v3 needs the Iceberg 1.12 line; these tests skip on the reference
 * line.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FormatV3VariantJobsTest extends OpenHouseSparkITest {
  private static final String DB = "x5variant";
  private static final String CATALOG_PREFIX = FormatV3Tables.CATALOG_PREFIX;
  private static final String DEFAULT_ICEBERG_PREFIX = FormatV3Tables.DEFAULT_ICEBERG_PREFIX;
  private static final List<Long> DELETED = Arrays.asList(2L, 6L, 10L, 14L, 19L, 23L);
  private static final int[] PARTITION_DAYS_AGO = FormatV3Tables.PARTITION_DAYS_AGO;
  private static final String SCHEMA =
      "{\"type\":\"struct\",\"fields\":["
          + "{\"id\":1,\"name\":\"id\",\"required\":false,\"type\":\"long\"},"
          + "{\"id\":2,\"name\":\"v\",\"required\":false,\"type\":\"variant\"},"
          + "{\"id\":3,\"name\":\"ts\",\"required\":false,\"type\":\"timestamptz\"}]}";

  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));

  /** The v3 tables service and its session, shared with the other format v3 job tests. */
  private FormatV3Tables tables;

  private SparkSession spark;
  private Catalog catalog;
  private Map<String, String> conf;

  private final LocalDateTime today =
      LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);

  @BeforeAll
  void start() throws Exception {
    tables = new FormatV3Tables(getSparkSession(), DB, otelEmitter);
    if (!VariantData.supported()) {
      return;
    }
    spark = tables.session(3);
    conf = new HashMap<>();
    spark.conf().getAll().foreach(entry -> conf.put(entry._1(), entry._2()));
    Map<String, String> properties = new HashMap<>();
    // auth-token for the OpenHouse catalog (Spark 3.5 line), token for RESTCatalog (Spark 4.2).
    for (String key : Arrays.asList("uri", "cluster", "auth-token", "token")) {
      if (conf.containsKey(CATALOG_PREFIX + "." + key)) {
        properties.put(key, conf.get(CATALOG_PREFIX + "." + key));
      }
    }
    catalog =
        CatalogUtil.loadCatalog(
            conf.get(CATALOG_PREFIX + ".catalog-impl"),
            "x5variant",
            properties,
            spark.sparkContext().hadoopConfiguration());
  }

  @AfterAll
  void stop() {
    if (tables != null) {
      tables.close();
    }
  }

  // ------------------------------------------------------------------------------------ fixture

  private static void requireFormatV3() {
    Assumptions.assumeTrue(
        VariantData.supported(), "format v3 needs the Iceberg 1.12 line; this is the 1.5.2 line");
  }

  private Operations ops() {
    return tables.ops(3);
  }

  private long micros(int daysAgo, int hour) {
    return today.minusDays(daysAgo).plusHours(hour).toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
  }

  /**
   * {@code x5variant.<name>}: v3, Parquet, merge-on-read, partitioned by days(ts); four partitions
   * of two data files of four rows each (ids 1 to 32, written in that order), then deletion vectors
   * for ids 2, 6, 10, 14 (one Puffin file) and 19, 23 (another). 10 snapshots.
   */
  private String createTable(String name) {
    String fqtn = DB + "." + name;
    TableIdentifier id = TableIdentifier.of(DB, name);
    if (catalog.tableExists(id)) {
      catalog.dropTable(id);
    }
    Schema schema = SchemaParser.fromJson(SCHEMA);
    Map<String, String> properties = new HashMap<>();
    properties.put("format-version", "3");
    properties.put("write.format.default", "parquet");
    properties.put("write.delete.mode", "merge-on-read");
    Table table =
        catalog.createTable(
            id, schema, PartitionSpec.builderFor(schema).day("ts").build(), properties);
    long next = 1;
    for (int p = 0; p < PARTITION_DAYS_AGO.length; p++) {
      for (int k = 0; k < 2; k++) {
        long[] ids = {next, next + 1, next + 2, next + 3};
        next += 4;
        VariantData.append(table, ids, micros(PARTITION_DAYS_AGO[p], 1 + k), null);
      }
    }
    VariantData.delete(table, Arrays.asList(2L, 6L, 10L, 14L));
    table.refresh();
    VariantData.delete(table, Arrays.asList(19L, 23L));
    table.refresh();
    Assertions.assertEquals(3, FormatV3Tables.formatVersion(table));
    Assertions.assertEquals(10, FormatV3Tables.snapshotIds(table).size());
    List<String> rows = VariantData.read(table, null);
    Assertions.assertEquals(32 - DELETED.size(), rows.size());
    // Not vacuous: the variant values and the row lineage are in what the checks compare.
    String row4 = rows.stream().filter(r -> r.startsWith("4 | ")).findFirst().get();
    Assertions.assertTrue(row4.contains("row-4") && row4.contains("40"), row4);
    Assertions.assertTrue(rows.stream().noneMatch(r -> r.endsWith("null")), rows.toString());
    return fqtn;
  }

  /** What a job may not change, or may change only as its outcome allows. */
  private final class State {
    final Long current;
    final Set<String> storage;
    final Set<String> referenced;
    final List<String> rows;
    final Map<Long, List<String>> perSnapshot = new LinkedHashMap<>();

    State(String fqtn) throws IOException {
      Table table = ops().getTable(fqtn);
      current = table.currentSnapshot() == null ? null : table.currentSnapshot().snapshotId();
      storage = listFiles(ops().fs(), new Path(table.location()));
      referenced = referencedFiles(table);
      rows = VariantData.read(table, null);
      for (long id : FormatV3Tables.snapshotIds(table)) {
        perSnapshot.put(id, VariantData.read(table, id));
      }
    }
  }

  private static String normalized(String path) {
    return new Path(path).toUri().getPath();
  }

  private static Set<String> listFiles(FileSystem fs, Path root) throws IOException {
    Set<String> files = new TreeSet<>();
    RemoteIterator<LocatedFileStatus> it = fs.listFiles(root, true);
    while (it.hasNext()) {
      String path = normalized(it.next().getPath().toString());
      if (!path.endsWith(".crc")) {
        files.add(path);
      }
    }
    return files;
  }

  /** {@link FormatV3Tables#referencedFiles}, as storage paths. */
  private static Set<String> referencedFiles(Table table) {
    return FormatV3Tables.referencedFiles(table).stream()
        .map(FormatV3VariantJobsTest::normalized)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Throwable run(Executable job) {
    try {
      job.execute();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  /**
   * Outcome (a): the job completed and the table is correct. {@code retained} are the snapshots the
   * job keeps (all of them unless it expires some), each reading as before.
   */
  private void assertCompletedCorrectly(
      Throwable failure, String fqtn, State before, List<Long> retained) throws IOException {
    Assertions.assertNull(failure, () -> "the job failed: " + chain(failure));
    State after = new State(fqtn);
    Assertions.assertEquals(before.rows, after.rows, "live rows, variant values and row lineage");
    for (long id : DELETED) {
      Assertions.assertTrue(
          after.rows.stream().noneMatch(r -> r.startsWith(id + " | ")), "row " + id + " deleted");
    }
    for (long id : retained) {
      Assertions.assertEquals(
          before.perSnapshot.get(id),
          after.perSnapshot.get(id),
          "snapshot " + id + " reads as before");
    }
    Table table = ops().getTable(fqtn);
    List<String> missing =
        after.referenced.stream()
            .filter(f -> !table.io().newInputFile(f).exists())
            .collect(Collectors.toList());
    Assertions.assertEquals(Collections.emptyList(), missing, "every referenced file exists");
  }

  /**
   * Outcome (b): the job failed with a visible error, and the table is exactly as it was: the same
   * current snapshot and snapshots, nothing removed from storage, every file the job added
   * unreferenced, and every row (variant values and lineage) as before.
   *
   * @return the error, for the test to name
   */
  private String assertFailedCleanly(Throwable failure, String fqtn, State before)
      throws IOException {
    Assertions.assertNotNull(failure, "the job completed");
    State after = new State(fqtn);
    Assertions.assertEquals(before.current, after.current, "current snapshot");
    Assertions.assertEquals(before.perSnapshot.keySet(), after.perSnapshot.keySet(), "snapshots");
    Assertions.assertEquals(before.referenced, after.referenced, "referenced files");
    Set<String> removed = new TreeSet<>(before.storage);
    removed.removeAll(after.storage);
    Assertions.assertEquals(Collections.emptySet(), removed, "files removed from storage");
    Set<String> added = new TreeSet<>(after.storage);
    added.removeAll(before.storage);
    added.retainAll(after.referenced);
    Assertions.assertEquals(Collections.emptySet(), added, "files the job wrote and referenced");
    Assertions.assertEquals(before.rows, after.rows, "live rows");
    return chain(failure);
  }

  private static Path plant(FileSystem fs, Path path, long ageMillis) throws IOException {
    fs.create(path, true).close();
    long time = System.currentTimeMillis() - ageMillis;
    fs.setTimes(path, time, time);
    return path;
  }

  private static final String SPARK_HAS_NO_VARIANT = "Unsupported type: variant";

  // ----------------------------------------------------------------------------------- the jobs

  /** The post-job validation every table job runs ({@code TableStateValidator}) reads the table. */
  private static void assertPostJobValidationFails(Throwable failure) {
    Assertions.assertNotNull(failure, "the post-job validation passed");
    Assertions.assertTrue(
        failure instanceof com.linkedin.openhouse.jobs.exception.TableValidationException,
        chain(failure));
    Assertions.assertTrue(
        failure
            .getMessage()
            .contains(
                "Table state validation failed with exception: "
                    + "java.lang.UnsupportedOperationException: "
                    + SPARK_HAS_NO_VARIANT),
        chain(failure));
  }

  /**
   * The closing validation reads the table through Spark: on Spark 4.2 it passes; on Spark 3.5,
   * which has no variant type, it fails visibly.
   */
  private static void assertClosingValidation(Throwable validation) {
    if (JobsLine.sparkReadsVariant()) {
      Assertions.assertNull(validation, () -> "the post-job validation: " + chain(validation));
    } else {
      assertPostJobValidationFails(validation);
    }
  }

  /**
   * Data compaction with the scheduler's production arguments (table name only): the job plans no
   * file group on this table and commits nothing, on both lines. Its post-job validation then fails
   * on Spark 3.5, which cannot read the table, and passes on Spark 4.2. The table is exactly as it
   * was.
   */
  @Test
  void compactionWithProductionArguments() throws Exception {
    requireFormatV3();
    String fqtn = createTable("compact_table_only");
    State before = new State(fqtn);
    DataCompactionSparkApp app =
        DataCompactionSparkApp.createApp(
            new String[] {"--storageURL", "http://localhost:1", "--tableName", fqtn}, otelEmitter);
    Assertions.assertNull(run(() -> app.runInner(ops())), "the job's own work");
    Throwable validation = run(() -> app.runValidations(ops()));
    assertClosingValidation(validation);
    if (JobsLine.sparkReadsVariant()) {
      assertCompletedCorrectly(null, fqtn, before, new ArrayList<>(before.perSnapshot.keySet()));
      Assertions.assertEquals(before.current, new State(fqtn).current, "nothing planned");
    } else {
      assertFailedCleanly(validation, fqtn, before);
    }
  }

  /**
   * Strict data compaction (minInputFiles 2, deleteFileThreshold 1, every file group due).
   *
   * <p>Spark 3.5: every file group fails to read and the rewrite action commits nothing (it logs
   * each group's failure and reports 0 files rewritten, without throwing); the post-job validation
   * then fails. The table is exactly as it was.
   *
   * <p>Spark 4.2: the rewrite commits a {@code replace} snapshot; every data file is rewritten into
   * fewer files and the deletion vectors are applied; every live row reads as before, variant
   * value, row id and last-updated sequence number included; deleted rows stay deleted; every
   * earlier snapshot reads as before; every referenced file exists; the post-job validation passes.
   */
  @Test
  void strictCompaction() throws Exception {
    requireFormatV3();
    String fqtn = createTable("compact_strict");
    State before = new State(fqtn);
    DataCompactionSparkApp app =
        new DataCompactionSparkApp(
            "x5-compaction",
            null,
            fqtn,
            DataCompactionConfig.builder().minInputFiles(2).deleteFileThreshold(1).build(),
            otelEmitter);
    Table beforeTable = ops().getTable(fqtn);
    List<String> dataFilesBefore = dataFiles(beforeTable);
    Assertions.assertEquals(8, dataFilesBefore.size());
    Assertions.assertFalse(
        FormatV3Tables.liveDeleteFiles(beforeTable, beforeTable.currentSnapshot()).isEmpty());
    Assertions.assertNull(run(() -> app.runInner(ops())), "the job's own work");
    Throwable validation = run(() -> app.runValidations(ops()));
    assertClosingValidation(validation);
    if (!JobsLine.sparkReadsVariant()) {
      assertFailedCleanly(validation, fqtn, before);
      return;
    }
    assertCompletedCorrectly(null, fqtn, before, new ArrayList<>(before.perSnapshot.keySet()));
    Table table = ops().getTable(fqtn);
    Assertions.assertNotEquals(before.current, table.currentSnapshot().snapshotId(), "committed");
    Assertions.assertEquals("replace", table.currentSnapshot().operation());
    List<String> dataFilesAfter = dataFiles(table);
    Assertions.assertTrue(
        Collections.disjoint(dataFilesBefore, dataFilesAfter), "every data file rewritten");
    Assertions.assertTrue(
        dataFilesAfter.size() < dataFilesBefore.size(), "fewer files: " + dataFilesAfter);
    Assertions.assertEquals(
        Collections.emptyList(),
        FormatV3Tables.liveDeleteFiles(table, table.currentSnapshot()),
        "deletion vectors applied");
    Assertions.assertTrue(
        new State(fqtn).rows.stream().noneMatch(r -> r.endsWith("null")), "row lineage kept");
  }

  private static Set<String> withoutMetadataFiles(Set<String> files) {
    return files.stream()
        .filter(f -> !f.endsWith(".metadata.json"))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static List<String> dataFiles(Table table) {
    return FormatV3Tables.liveDataFiles(table, table.currentSnapshot()).stream()
        .map(f -> f.path().toString())
        .sorted()
        .collect(Collectors.toList());
  }

  /**
   * Snapshot expiry with file deletion (the oldest partition deleted from the table first, so its
   * files belong to expired snapshots only): the expiry is done and correct (the last 2 snapshots
   * remain and read as before, the expired-only files are gone, every referenced file exists), on
   * both lines. The post-job validation then fails visibly on Spark 3.5, which cannot read the
   * table, and passes on Spark 4.2.
   */
  @Test
  void snapshotExpiryWithFileDeletion() throws Exception {
    requireFormatV3();
    String fqtn = createTable("expire");
    ops()
        .getTable(fqtn)
        .newDelete()
        .deleteFromRowFilter(Expressions.lessThan("ts", micros(7, 0)))
        .commit();
    VariantData.append(ops().getTable(fqtn), new long[] {200, 201}, micros(0, 5), null);
    State before = new State(fqtn);
    List<Long> ids = new ArrayList<>(before.perSnapshot.keySet());
    List<Long> retained = ids.subList(ids.size() - 2, ids.size());
    SnapshotsExpirationSparkApp app =
        new SnapshotsExpirationSparkApp(
            "x5-expiry", null, fqtn, 3, "days", 2, true, ".backup", otelEmitter);
    Throwable work = run(() -> app.runInner(ops()));
    assertCompletedCorrectly(work, fqtn, before, retained);
    Table table = ops().getTable(fqtn);
    Assertions.assertEquals(retained, FormatV3Tables.snapshotIds(table));
    Set<String> after = referencedFiles(table);
    List<String> expiredOnly =
        before.referenced.stream()
            .filter(f -> !after.contains(f) && f.endsWith(".parquet"))
            .collect(Collectors.toList());
    Assertions.assertFalse(expiredOnly.isEmpty(), "data files only expired snapshots referenced");
    Assertions.assertTrue(
        expiredOnly.stream().noneMatch(f -> table.io().newInputFile(f).exists()),
        "expired-only data files were deleted");
    assertClosingValidation(run(() -> app.runValidations(ops())));
  }

  /**
   * Orphan file deletion, single and batched, with a planted orphan next to a live Puffin file: the
   * orphan is removed and the table is correct, on both lines. The post-job validation then fails
   * visibly on Spark 3.5 (the batched app runs it inside and reports the batch failed) and passes
   * on Spark 4.2 (the batch succeeds).
   */
  @Test
  void orphanFileDeletion() throws Exception {
    requireFormatV3();
    for (boolean batched : new boolean[] {false, true}) {
      String fqtn = createTable("ofd" + (batched ? "_batch" : ""));
      Table table = ops().getTable(fqtn);
      String livePuffin =
          table.currentSnapshot().addedDeleteFiles(table.io()).iterator().next().path().toString();
      Path orphan =
          plant(
              ops().fs(),
              new Path(new Path(livePuffin).getParent(), "x5-orphan.parquet"),
              3_600_000L);
      State before = new State(fqtn);
      before.storage.remove(normalized(orphan.toString()));
      Throwable failure;
      if (batched) {
        failure =
            run(
                () ->
                    new BatchedOrphanFilesDeletionSparkApp(
                            "x5-ofd-batch",
                            null,
                            otelEmitter,
                            Collections.singletonList(
                                BatchedOrphanFilesDeletionSparkApp.BatchEntry.builder()
                                    .fqtn(fqtn)
                                    .databaseName(DB)
                                    .tableName(fqtn.substring(DB.length() + 1))
                                    .build()),
                            null,
                            1,
                            0,
                            ".backup",
                            2,
                            true,
                            1000)
                        .runInner(ops()));
        if (JobsLine.sparkReadsVariant()) {
          Assertions.assertNull(failure, () -> "the batch: " + chain(failure));
        } else {
          Assertions.assertNotNull(failure, "the batch reports its table failed");
          Assertions.assertTrue(
              chain(failure).contains("All 1 operations in batch failed"), chain(failure));
        }
      } else {
        OrphanFilesDeletionSparkApp app =
            new OrphanFilesDeletionSparkApp(
                "x5-ofd", null, fqtn, 0, otelEmitter, ".backup", 2, false, 1000);
        Assertions.assertNull(run(() -> app.runInner(ops())), "the job's own work");
        failure = run(() -> app.runValidations(ops()));
        assertClosingValidation(failure);
      }
      Assertions.assertFalse(ops().fs().exists(orphan), "planted orphan removed");
      assertCompletedCorrectly(null, fqtn, before, new ArrayList<>(before.perSnapshot.keySet()));
      Assertions.assertEquals(
          before.storage.stream().filter(f -> !f.contains("/.backup/")).collect(Collectors.toSet()),
          new State(fqtn)
              .storage.stream().filter(f -> !f.contains("/.backup/")).collect(Collectors.toSet()),
          "only the orphan left storage");
    }
  }

  /**
   * Staged files deletion: the two-day-old file in {@code .trash} is removed and the table is
   * correct, on both lines; then the post-job validation fails visibly on Spark 3.5 and passes on
   * Spark 4.2.
   */
  @Test
  void stagedFilesDeletion() throws Exception {
    requireFormatV3();
    String fqtn = createTable("staged");
    Path staged =
        plant(
            ops().fs(),
            new Path(ops().getTable(fqtn).location(), ".trash/data/x5-staged.parquet"),
            2 * 86_400_000L);
    State before = new State(fqtn);
    StagedFilesDeletionSparkApp app =
        new StagedFilesDeletionSparkApp("x5-staged", null, fqtn, ".trash", 1, true, otelEmitter);
    Assertions.assertNull(run(() -> app.runInner(ops())), "the job's own work");
    Assertions.assertFalse(ops().fs().exists(staged), "staged file removed");
    assertCompletedCorrectly(null, fqtn, before, new ArrayList<>(before.perSnapshot.keySet()));
    assertClosingValidation(run(() -> app.runValidations(ops())));
  }

  /**
   * Table stats collection: the stats are collected from metadata (the variant column has no
   * metrics) and nothing changes, on both lines; then the post-job validation fails visibly on
   * Spark 3.5 and passes on Spark 4.2.
   */
  @Test
  void tableStatsCollectionChangesNothing() throws Exception {
    requireFormatV3();
    String fqtn = createTable("stats");
    State before = new State(fqtn);
    Assertions.assertNotNull(ops().collectTableStats(fqtn), "stats collected");
    Assertions.assertEquals(10L, (long) ops().collectTableStats(fqtn).getNumSnapshots());
    TableStatsCollectionSparkApp app =
        new TableStatsCollectionSparkApp("x5-stats", null, fqtn, otelEmitter);
    Assertions.assertNull(run(() -> app.runInner(ops())), "the job's own work");
    Throwable validation = run(() -> app.runValidations(ops()));
    assertClosingValidation(validation);
    if (JobsLine.sparkReadsVariant()) {
      assertCompletedCorrectly(null, fqtn, before, new ArrayList<>(before.perSnapshot.keySet()));
      Assertions.assertEquals(before.current, new State(fqtn).current, "nothing committed");
    } else {
      assertFailedCleanly(validation, fqtn, before);
    }
  }

  /**
   * Data layout strategy generation. Spark 3.5: it fails in its own work (it reads the table
   * through Spark), before it writes its strategy property; the table is exactly as it was. Spark
   * 4.2: it completes and writes its strategy property; no snapshot, row or file changes.
   */
  @Test
  void layoutStrategyGeneration() throws Exception {
    requireFormatV3();
    String fqtn = createTable("dlo");
    spark.sql("DROP TABLE IF EXISTS openhouse." + DB + ".dlo_out");
    spark.sql("DROP TABLE IF EXISTS openhouse." + DB + ".dlo_out_p");
    State before = new State(fqtn);
    DataLayoutStrategyGeneratorSparkApp app =
        new DataLayoutStrategyGeneratorSparkApp(
            "x5-dlo", null, fqtn, DB + ".dlo_out", DB + ".dlo_out_p", otelEmitter);
    Throwable failure = run(() -> app.runInner(ops()));
    if (JobsLine.sparkReadsVariant()) {
      assertCompletedCorrectly(failure, fqtn, before, new ArrayList<>(before.perSnapshot.keySet()));
      Assertions.assertEquals(before.current, new State(fqtn).current, "no snapshot committed");
      Assertions.assertNotNull(
          ops().getTable(fqtn).properties().get("write.data-layout.strategies"), "a strategy");
      return;
    }
    Assertions.assertTrue(chain(failure).contains(SPARK_HAS_NO_VARIANT), chain(failure));
    assertFailedCleanly(failure, fqtn, before);
    Assertions.assertNull(
        ops().getTable(fqtn).properties().get("write.data-layout.strategies"), "no strategy");
  }

  /**
   * Sort stats collection, through {@code spark_catalog} as X6 runs it, on a v3 table with the
   * columns the app sorts by and a variant column. Spark 3.5: it fails in its own work (it copies a
   * partition through Spark) and the table is exactly as it was. Spark 4.2: it completes, sets its
   * compression rate property and changes no snapshot or row; its temporary copy is dropped.
   */
  @Test
  void sortStatsCollection() throws Exception {
    requireFormatV3();
    SparkSession sortSpark = FormatV3Tables.copyCatalogs(spark, spark.newSession());
    for (Map.Entry<String, String> entry : conf.entrySet()) {
      if (entry.getKey().startsWith(CATALOG_PREFIX + ".")) {
        String key = entry.getKey().substring(CATALOG_PREFIX.length());
        sortSpark.conf().set("spark.sql.catalog.spark_catalog" + key, entry.getValue());
      }
    }
    sortSpark.conf().set("spark.sql.catalog.spark_catalog", conf.get(CATALOG_PREFIX));
    String name = "sortstats";
    TableIdentifier id = TableIdentifier.of(DB, name);
    if (catalog.tableExists(id)) {
      catalog.dropTable(id);
    }
    Map<String, String> properties = new HashMap<>();
    properties.put("format-version", "3");
    properties.put("write.format.default", "parquet");
    Table table =
        catalog.createTable(
            id,
            SchemaParser.fromJson(
                "{\"type\":\"struct\",\"fields\":["
                    + "{\"id\":1,\"name\":\"id\",\"required\":false,\"type\":\"long\"},"
                    + "{\"id\":2,\"name\":\"data\",\"required\":false,\"type\":\"string\"},"
                    + "{\"id\":3,\"name\":\"header\",\"required\":false,\"type\":{\"type\":"
                    + "\"struct\",\"fields\":[{\"id\":6,\"name\":\"memberId\",\"required\":false,"
                    + "\"type\":\"long\"},{\"id\":7,\"name\":\"time\",\"required\":false,"
                    + "\"type\":\"long\"}]}},"
                    + "{\"id\":4,\"name\":\"datepartition\",\"required\":false,\"type\":\"string\"},"
                    + "{\"id\":5,\"name\":\"v\",\"required\":false,\"type\":\"variant\"}]}"),
            PartitionSpec.unpartitioned(),
            properties);
    String datepartition =
        LocalDateTime.now()
            .minusDays(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"));
    for (int k = 0; k < 3; k++) {
      VariantData.append(
          table,
          new long[] {k * 5L + 1, k * 5L + 2, k * 5L + 3, k * 5L + 4, k * 5L + 5},
          0L,
          datepartition);
      table.refresh();
    }
    String fqtn = DB + "." + name;
    State before = new State(fqtn);
    Operations sortOps = tables.newOps(sortSpark, otelEmitter);
    SortStatsCollectionSparkApp app =
        new SortStatsCollectionSparkApp("x5-sort-stats", null, fqtn, otelEmitter);
    Throwable failure = run(() -> app.runInner(sortOps));
    if (JobsLine.sparkReadsVariant()) {
      Assertions.assertNull(failure, () -> "the job's own work: " + chain(failure));
      State after = new State(fqtn);
      Assertions.assertEquals(before.current, after.current, "current snapshot");
      Assertions.assertEquals(before.rows, after.rows, "live rows");
      // The property commit writes a new metadata file; every other referenced file is the same.
      Assertions.assertEquals(
          withoutMetadataFiles(before.referenced),
          withoutMetadataFiles(after.referenced),
          "referenced files");
      Assertions.assertNotNull(
          ops().getTable(fqtn).properties().get("sort-compression-rate"), "property set");
      Assertions.assertEquals(
          Collections.singletonList(name),
          catalog.listTables(org.apache.iceberg.catalog.Namespace.of(DB)).stream()
              .map(TableIdentifier::name)
              .filter(n -> n.startsWith(name))
              .collect(Collectors.toList()),
          "the temporary copy is dropped");
      return;
    }
    Assertions.assertTrue(chain(failure).contains(SPARK_HAS_NO_VARIANT), chain(failure));
    State after = new State(fqtn);
    Assertions.assertEquals(before.current, after.current, "current snapshot");
    Assertions.assertEquals(before.rows, after.rows, "live rows");
    Assertions.assertNull(
        ops().getTable(fqtn).properties().get("sort-compression-rate"), "no property set");
    assertFailedCleanly(failure, fqtn, before);
  }
}
