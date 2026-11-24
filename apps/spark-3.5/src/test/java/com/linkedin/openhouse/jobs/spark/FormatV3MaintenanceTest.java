package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.common.stats.model.IcebergTableStats;
import com.linkedin.openhouse.datalayout.config.DataCompactionConfig;
import com.linkedin.openhouse.jobs.util.AppConstants;
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
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.ManifestContent;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.actions.DeleteOrphanFiles;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Black-box tests of the maintenance jobs on Iceberg format v3 tables (work item X6), each one run
 * the same way on a v2 table so that a v3 fix cannot change what a job does on v2.
 *
 * <p>Every test builds a time-partitioned, merge-on-read table with several data files per
 * partition, deletes from DELETE, UPDATE and MERGE (deletion vectors in Puffin files on v3,
 * position delete files on v2), one Puffin file that still serves live data files after a later
 * DELETE replaced another of its deletion vectors, row lineage on v3, and one snapshot per
 * statement. It records what the table reads before the job (live rows with full contents, every
 * snapshot's rows, {@code _row_id} and {@code _last_updated_sequence_number} of every row), runs
 * the job's own code ({@code runInner} and the post-job validation of the app the scheduler
 * launches), and checks afterwards that live rows and retained snapshots read the same, deleted
 * rows stay deleted, row lineage is unchanged, and every file a retained snapshot references
 * (manifest list, manifests, data files, delete files, statistics files) still exists.
 *
 * <p>v2 tables live on the tables service every apps test uses (switch off, as in production); v3
 * tables on a second one with {@code cluster.iceberg.format-v3.enabled} on. Format v3 needs the
 * Iceberg 1.12 line, so the v3 cases are skipped on LinkedIn's Iceberg 1.5.2 reference line, where
 * the v2 cases still run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FormatV3MaintenanceTest extends OpenHouseSparkITest {
  private static final String DB = "x6maint";
  private static final String CATALOG_PREFIX = "spark.sql.catalog.openhouse";
  private static final String DEFAULT_ICEBERG_PREFIX = "spark.sql.catalog.default_iceberg";

  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));

  private FormatV3TablesServer server;
  /** The session on the default service (format v2 tables). */
  private SparkSession v2Spark;
  /** A session whose {@code openhouse} catalog is the service with the switch on (v3 tables). */
  private SparkSession v3Spark;

  /** Midnight today, UTC: rows are written in partitions this many days before it. */
  private final LocalDateTime today =
      LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);

  private static final int[] PARTITION_DAYS_AGO = {10, 5, 1, 0};
  /** Rows the setup deletes, through DELETE (twice) and MERGE. */
  private static final List<Long> DELETED_IDS =
      Arrays.asList(2L, 6L, 10L, 14L, 18L, 22L, 26L, 30L, 3L, 15L);

  @BeforeAll
  void start() throws Exception {
    v2Spark = getSparkSession();
    server = new FormatV3TablesServer();
    v3Spark = v2Spark.newSession();
    Map<String, String> all = new HashMap<>();
    v2Spark.conf().getAll().foreach(entry -> all.put(entry._1(), entry._2()));
    for (Map.Entry<String, String> entry : all.entrySet()) {
      if (entry.getKey().startsWith(CATALOG_PREFIX)
          || entry.getKey().startsWith(DEFAULT_ICEBERG_PREFIX)) {
        v3Spark.conf().set(entry.getKey(), entry.getValue());
      }
    }
    v3Spark.conf().set(CATALOG_PREFIX + ".uri", server.uri());
    v3Spark.conf().set(DEFAULT_ICEBERG_PREFIX + ".uri", server.uri());
  }

  @AfterAll
  void stop() {
    if (server != null) {
      server.close();
    }
  }

  /**
   * The v3 cases skip themselves by an assumption on the reference line. On the default line
   * (Iceberg 1.12) that assumption must hold, or every v3 case would be silently skipped: the build
   * tells the test which line it is ({@code openhouse.iceberg.line}, apps build.gradle).
   */
  @org.junit.jupiter.api.Test
  void v3CasesAreNotSkippedOnTheDefaultLine() {
    String line = System.getProperty("openhouse.iceberg.line");
    Assertions.assertNotNull(line, "the build sets openhouse.iceberg.line for this test task");
    if ("default".equals(line)) {
      Assertions.assertTrue(
          icebergCanWriteV3(), "the default line runs Iceberg 1.12, which writes format v3");
      Table table = ops(3).getTable(createTable(3, "guard", "orc"));
      Assertions.assertEquals(3, formatVersion(table));
    } else {
      Assertions.assertEquals("linkedin", line);
      Assertions.assertFalse(icebergCanWriteV3(), "the reference line cannot write format v3");
    }
  }

  // ------------------------------------------------------------------------------------ fixture

  /** Row lineage's metadata columns exist only on an Iceberg that writes format v3 (1.12 line). */
  private boolean icebergCanWriteV3() {
    try {
      org.apache.iceberg.MetadataColumns.class.getField("ROW_ID");
      return true;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  private SparkSession session(int formatVersion) {
    if (formatVersion == 3) {
      Assumptions.assumeTrue(
          icebergCanWriteV3(), "format v3 needs the Iceberg 1.12 line; this is the 1.5.2 line");
      return v3Spark;
    }
    return v2Spark;
  }

  private Operations ops(int formatVersion) {
    return Operations.withCatalog(session(formatVersion), otelEmitter);
  }

  private String ts(int daysAgo, int minute) {
    return "TIMESTAMP '"
        + today.minusDays(daysAgo).plusMinutes(minute).toString().replace('T', ' ')
        + "'";
  }

  /**
   * Creates {@code x6maint.<name>} at the given format version and fills it: 4 daily partitions,
   * two INSERTs of 4 rows each per partition (8 data files), a DELETE touching every data file, a
   * second DELETE touching one data file only (on v3 it replaces one deletion vector, so the first
   * Puffin file stays live for the others), an UPDATE, and a MERGE that updates, deletes and
   * inserts. 13 snapshots.
   */
  private String createTable(int formatVersion, String name, String fileFormat) {
    SparkSession spark = session(formatVersion);
    String fqtn = DB + "." + name + "_v" + formatVersion + "_" + fileFormat;
    String t = "openhouse." + fqtn;
    spark.sql("DROP TABLE IF EXISTS " + t);
    String props =
        "'write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read', "
            + "'write.merge.mode'='merge-on-read', 'write.format.default'='"
            + fileFormat
            + "'"
            + (formatVersion == 3 ? ", 'format-version'='3'" : "");
    spark.sql(
        "CREATE TABLE "
            + t
            + " (id bigint, data string, ts timestamp) PARTITIONED BY (days(ts)) TBLPROPERTIES ("
            + props
            + ")");
    for (int p = 0; p < PARTITION_DAYS_AGO.length; p++) {
      for (int k = 0; k < 2; k++) {
        List<String> values = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
          long id = p * 8L + k * 4L + r + 1;
          values.add("(" + id + ", 'row-" + id + "', " + ts(PARTITION_DAYS_AGO[p], (int) id) + ")");
        }
        spark.sql("INSERT INTO " + t + " VALUES " + String.join(", ", values));
      }
    }
    spark.sql("DELETE FROM " + t + " WHERE id IN (2, 6, 10, 14, 18, 22, 26, 30)");
    spark.sql("DELETE FROM " + t + " WHERE id = 3");
    spark.sql("UPDATE " + t + " SET data = concat(data, '-updated') WHERE id IN (7, 23)");
    spark.sql(
        "MERGE INTO "
            + t
            + " target USING (SELECT * FROM VALUES (11L, 'u'), (15L, 'd'), (100L, 'i') AS s(id, op))"
            + " source ON target.id = source.id"
            + " WHEN MATCHED AND source.op = 'd' THEN DELETE"
            + " WHEN MATCHED THEN UPDATE SET target.data = 'merged'"
            + " WHEN NOT MATCHED THEN INSERT (id, data, ts) VALUES (source.id, 'inserted', "
            + ts(1, 59)
            + ")");
    Table table = ops(formatVersion).getTable(fqtn);
    Assertions.assertEquals(formatVersion, formatVersion(table));
    Assertions.assertEquals(12, snapshotIds(table).size(), "one snapshot per statement");
    Snapshot current = table.currentSnapshot();
    List<DeleteFile> deletes = liveDeleteFiles(table, current);
    Assertions.assertFalse(deletes.isEmpty(), "merge-on-read left delete files");
    if (formatVersion == 3) {
      Assertions.assertTrue(
          deletes.stream().allMatch(d -> d.format().name().equals("PUFFIN")),
          "v3 deletes are deletion vectors in Puffin files");
      // The first DELETE's Puffin files are still live through the data files the second DELETE
      // did not touch.
      Assertions.assertTrue(
          deletes.stream().map(d -> d.path().toString()).distinct().count() > 1,
          "deletion vectors live in more than one Puffin file");
      List<Row> lineage =
          read(
              spark,
              "SELECT _row_id FROM "
                  + t
                  + " WHERE _row_id IS NULL OR _last_updated_sequence_number IS NULL");
      Assertions.assertTrue(
          lineage.isEmpty(), "every row has a row id and a last-updated sequence number");
    } else {
      Assertions.assertTrue(deletes.stream().noneMatch(d -> d.format().name().equals("PUFFIN")));
    }
    Assertions.assertTrue(liveDataFiles(table, current).size() >= 8);
    return fqtn;
  }

  private static int formatVersion(Table table) {
    return ((HasTableOperations) table).operations().current().formatVersion();
  }

  private static List<Long> snapshotIds(Table table) {
    List<Long> ids = new ArrayList<>();
    table.snapshots().forEach(s -> ids.add(s.snapshotId()));
    return ids;
  }

  private static List<Row> read(SparkSession spark, String sql) {
    return spark.sql(sql).collectAsList();
  }

  private static List<String> rows(SparkSession spark, String sql) {
    return read(spark, sql).stream().map(Row::toString).sorted().collect(Collectors.toList());
  }

  /** What a table reads, recorded before a job runs and compared after it. */
  private static final class Reading {
    final List<String> live;
    final Map<Long, List<String>> perSnapshot;
    final List<String> lineage;
    final Set<Long> liveIds;

    Reading(
        List<String> live,
        Map<Long, List<String>> perSnapshot,
        List<String> lineage,
        Set<Long> ids) {
      this.live = live;
      this.perSnapshot = perSnapshot;
      this.lineage = lineage;
      this.liveIds = ids;
    }
  }

  private Reading reading(int formatVersion, String fqtn) {
    return readingIn(session(formatVersion), formatVersion, fqtn);
  }

  private Reading readingIn(SparkSession spark, int formatVersion, String fqtn) {
    String t = "openhouse." + fqtn;
    spark.sql("REFRESH TABLE " + t);
    List<String> live = rows(spark, "SELECT * FROM " + t);
    Map<Long, List<String>> perSnapshot = new LinkedHashMap<>();
    for (long id : snapshotIds(ops(formatVersion).getTable(fqtn))) {
      perSnapshot.put(id, rows(spark, "SELECT * FROM " + t + " VERSION AS OF " + id));
    }
    List<String> lineage =
        formatVersion == 3
            ? rows(spark, "SELECT id, _row_id, _last_updated_sequence_number FROM " + t)
            : Collections.emptyList();
    Set<Long> ids = new TreeSet<>();
    read(spark, "SELECT id FROM " + t).forEach(r -> ids.add(r.getLong(0)));
    return new Reading(live, perSnapshot, lineage, ids);
  }

  /**
   * The checks every job must pass: live rows read the same with full contents, deleted rows stay
   * deleted, row lineage is unchanged (v3), every retained snapshot reads as before, and every file
   * a retained snapshot references exists.
   */
  private void assertNoLiveDataLost(
      int formatVersion, String fqtn, Reading before, Map<Long, List<String>> expectedRetained) {
    Reading after = reading(formatVersion, fqtn);
    Assertions.assertEquals(before.live, after.live, "live rows, full contents");
    for (long id : DELETED_IDS) {
      Assertions.assertFalse(after.liveIds.contains(id), "deleted row " + id + " stays deleted");
    }
    if (formatVersion == 3) {
      Assertions.assertEquals(before.lineage, after.lineage, "_row_id and sequence numbers");
    }
    Table table = ops(formatVersion).getTable(fqtn);
    for (Map.Entry<Long, List<String>> retained : expectedRetained.entrySet()) {
      Assertions.assertNotNull(
          table.snapshot(retained.getKey()), "snapshot " + retained.getKey() + " retained");
      Assertions.assertEquals(
          retained.getValue(),
          after.perSnapshot.get(retained.getKey()),
          "time travel to snapshot " + retained.getKey());
    }
    assertEveryReferencedFileExists(table);
  }

  /** Every file each snapshot of the table references, and the statistics files. */
  private static Set<String> referencedFiles(Table table) {
    Set<String> files = new TreeSet<>();
    files.add(((HasTableOperations) table).operations().current().metadataFileLocation());
    for (Snapshot snapshot : table.snapshots()) {
      files.add(snapshot.manifestListLocation());
      for (ManifestFile manifest : snapshot.allManifests(table.io())) {
        files.add(manifest.path());
        for (ContentFile<?> file : manifestEntries(table, manifest)) {
          files.add(file.path().toString());
        }
      }
    }
    for (StatisticsFile statistics : table.statisticsFiles()) {
      files.add(statistics.path());
    }
    return files;
  }

  private static List<ContentFile<?>> manifestEntries(Table table, ManifestFile manifest) {
    List<ContentFile<?>> files = new ArrayList<>();
    try {
      if (manifest.content() == ManifestContent.DATA) {
        try (ManifestReader<DataFile> reader =
            ManifestFiles.read(manifest, table.io(), table.specs())) {
          reader.forEach(f -> files.add(f.copy()));
        }
      } else {
        try (ManifestReader<DeleteFile> reader =
            ManifestFiles.readDeleteManifest(manifest, table.io(), table.specs())) {
          reader.forEach(f -> files.add(f.copy()));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return files;
  }

  private static List<DataFile> liveDataFiles(Table table, Snapshot snapshot) {
    List<DataFile> files = new ArrayList<>();
    for (ManifestFile manifest : snapshot.dataManifests(table.io())) {
      manifestEntries(table, manifest).forEach(f -> files.add((DataFile) f));
    }
    return files;
  }

  private static List<DeleteFile> liveDeleteFiles(Table table, Snapshot snapshot) {
    List<DeleteFile> files = new ArrayList<>();
    for (ManifestFile manifest : snapshot.deleteManifests(table.io())) {
      manifestEntries(table, manifest).forEach(f -> files.add((DeleteFile) f));
    }
    return files;
  }

  private static void assertEveryReferencedFileExists(Table table) {
    List<String> missing =
        referencedFiles(table).stream()
            .filter(f -> !table.io().newInputFile(f).exists())
            .collect(Collectors.toList());
    Assertions.assertEquals(Collections.emptyList(), missing, "files a retained snapshot needs");
  }

  /** Data files of the current snapshot that have delete files applied to them. */
  private static Set<String> dataFilesWithDeletes(Table table) throws IOException {
    Set<String> files = new TreeSet<>();
    try (org.apache.iceberg.io.CloseableIterable<org.apache.iceberg.FileScanTask> tasks =
        table.newScan().planFiles()) {
      for (org.apache.iceberg.FileScanTask task : tasks) {
        if (!task.deletes().isEmpty()) {
          files.add(task.file().path().toString());
        }
      }
    }
    return files;
  }

  /**
   * The data file a deletion vector applies to ({@code DeleteFile.referencedDataFile}, Iceberg 1.12
   * only, so called reflectively; the reference line never builds a v3 table).
   */
  private static String referencedDataFile(DeleteFile deleteFile) {
    try {
      Object referenced = DeleteFile.class.getMethod("referencedDataFile").invoke(deleteFile);
      return referenced == null ? null : referenced.toString();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** On v3: the Puffin file holding each data file's deletion vector in the snapshot. */
  private static Map<String, String> deletionVectorFiles(Table table, Snapshot snapshot) {
    Map<String, String> byDataFile = new HashMap<>();
    for (DeleteFile d : liveDeleteFiles(table, snapshot)) {
      byDataFile.put(referencedDataFile(d), d.path().toString());
    }
    return byDataFile;
  }

  /**
   * On v3: Puffin files referenced by a retained snapshot that also held a deletion vector which an
   * expired snapshot used and a retained snapshot no longer takes from that file (partially live
   * Puffin files: the case an expiry that worked per file content would get wrong).
   */
  private static Set<String> partiallyLivePuffinFiles(
      Table table, List<Long> expired, List<Long> retained) {
    Set<String> retainedPuffins = new TreeSet<>();
    Set<String> retainedPairs = new TreeSet<>();
    for (long id : retained) {
      deletionVectorFiles(table, table.snapshot(id))
          .forEach(
              (dataFile, puffin) -> {
                retainedPuffins.add(puffin);
                retainedPairs.add(puffin + "|" + dataFile);
              });
    }
    Set<String> partial = new TreeSet<>();
    for (long id : expired) {
      deletionVectorFiles(table, table.snapshot(id))
          .forEach(
              (dataFile, puffin) -> {
                if (retainedPuffins.contains(puffin)
                    && !retainedPairs.contains(puffin + "|" + dataFile)) {
                  partial.add(puffin);
                }
              });
    }
    return partial;
  }

  /** Removes the oldest partition by a metadata-only DELETE: its data files leave the table. */
  private void dropOldestPartition(int formatVersion, String fqtn) {
    session(formatVersion).sql("DELETE FROM openhouse." + fqtn + " WHERE ts < " + ts(7, 0));
  }

  private static boolean isDataOrDeleteFile(String path) {
    return path.endsWith(".orc") || path.endsWith(".parquet") || path.endsWith(".puffin");
  }

  private static Path plant(FileSystem fs, Path path, long ageMillis) throws IOException {
    fs.create(path, true).close();
    long time = System.currentTimeMillis() - ageMillis;
    fs.setTimes(path, time, time);
    return path;
  }

  // --------------------------------------------------------------------------------- compaction

  @ParameterizedTest(name = "format v{0}, {1}")
  @CsvSource({"2, orc", "3, orc", "2, parquet", "3, parquet"})
  void compactionKeepsLiveRowsDeletesAndRowIds(int formatVersion, String fileFormat)
      throws Exception {
    String fqtn = createTable(formatVersion, "compact", fileFormat);
    Reading before = reading(formatVersion, fqtn);
    Table table = ops(formatVersion).getTable(fqtn);
    int dataFilesBefore = liveDataFiles(table, table.currentSnapshot()).size();
    Set<String> withDeletes = dataFilesWithDeletes(table);
    Assertions.assertFalse(withDeletes.isEmpty());

    DataCompactionSparkApp app =
        new DataCompactionSparkApp(
            "x6-compaction",
            null,
            fqtn,
            DataCompactionConfig.builder().minInputFiles(2).deleteFileThreshold(1).build(),
            otelEmitter);
    Operations ops = ops(formatVersion);
    app.runInner(ops);
    app.runValidations(ops);

    table = ops(formatVersion).getTable(fqtn);
    Assertions.assertEquals("replace", table.currentSnapshot().operation(), "compaction committed");
    Assertions.assertTrue(
        liveDataFiles(table, table.currentSnapshot()).size() < dataFilesBefore,
        "compaction reduced the data files");
    Set<String> liveAfter =
        liveDataFiles(table, table.currentSnapshot()).stream()
            .map(d -> d.path().toString())
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        Collections.disjoint(withDeletes, liveAfter),
        "every data file that carried deletes was rewritten");
    if (formatVersion == 3) {
      Assertions.assertEquals(
          Collections.emptyList(),
          liveDeleteFiles(table, table.currentSnapshot()),
          "the rewrite dropped the deletion vectors of the files it replaced");
    }
    assertNoDanglingDeletionVectors(formatVersion, table);
    assertNoLiveDataLost(formatVersion, fqtn, before, before.perSnapshot);
  }

  /** On v3, every live deletion vector applies to a live data file. */
  private static void assertNoDanglingDeletionVectors(int formatVersion, Table table) {
    if (formatVersion != 3) {
      return;
    }
    Set<String> live =
        liveDataFiles(table, table.currentSnapshot()).stream()
            .map(d -> d.path().toString())
            .collect(Collectors.toSet());
    for (DeleteFile d : liveDeleteFiles(table, table.currentSnapshot())) {
      Assertions.assertTrue(
          live.contains(referencedDataFile(d)), "deletion vector of a live data file: " + d.path());
    }
  }

  /**
   * Compaction as production launches it. {@code table-only}: DATA_COMPACTION, whose task passes
   * only {@code --tableName} (TableDataCompactionTask.getArgs), so every default applies
   * (minInputFiles 5, deleteFileThreshold Integer.MAX_VALUE, no partial progress). {@code
   * layout-args}: DATA_LAYOUT_STRATEGY_EXECUTION, whose task passes the generated strategy's sizes,
   * minInputFiles, concurrency and partial progress, never deleteFileThreshold
   * (TableDataLayoutStrategyExecutionTask.getArgs). {@code strategy-json}: the app's {@code
   * --strategy} entry with the strategy the generator stored on the table.
   */
  @ParameterizedTest(name = "format v{0}, {1}")
  @CsvSource({
    "2, table-only",
    "3, table-only",
    "2, layout-args",
    "3, layout-args",
    "2, strategy-json",
    "3, strategy-json"
  })
  void compactionWithProductionArgumentsKeepsLiveRowsDeletesAndRowIds(
      int formatVersion, String launch) throws Exception {
    String fqtn = createTable(formatVersion, "compact_" + launch.replace('-', '_'), "orc");
    Operations ops = ops(formatVersion);
    List<String> args = new ArrayList<>(Arrays.asList("--tableName", fqtn));
    if (!"table-only".equals(launch)) {
      String out = DB + ".dlo_" + launch.replace('-', '_') + "_v" + formatVersion;
      session(formatVersion).sql("DROP TABLE IF EXISTS openhouse." + out);
      session(formatVersion).sql("DROP TABLE IF EXISTS openhouse." + out + "_p");
      new DataLayoutStrategyGeneratorSparkApp(
              "x6-dlo-gen", null, fqtn, out, out + "_p", otelEmitter)
          .runInner(ops);
      List<com.linkedin.openhouse.datalayout.strategy.DataLayoutStrategy> strategies =
          com.linkedin.openhouse.datalayout.persistence.StrategiesDaoTableProps.deserializeList(
              ops.getTable(fqtn).properties().get("write.data-layout.strategies"));
      Assertions.assertFalse(strategies.isEmpty());
      com.linkedin.openhouse.datalayout.strategy.DataLayoutStrategy strategy = strategies.get(0);
      DataCompactionConfig config = strategy.getConfig();
      if ("layout-args".equals(launch)) {
        args.addAll(
            Arrays.asList(
                "--targetByteSize",
                String.valueOf(config.getTargetByteSize()),
                "--minByteSizeRatio",
                String.valueOf(config.getMinByteSizeRatio()),
                "--maxByteSizeRatio",
                String.valueOf(config.getMaxByteSizeRatio()),
                "--minInputFiles",
                String.valueOf(config.getMinInputFiles()),
                "--maxConcurrentFileGroupRewrites",
                String.valueOf(config.getMaxConcurrentFileGroupRewrites())));
        if (config.isPartialProgressEnabled()) {
          args.addAll(
              Arrays.asList(
                  "--partialProgressEnabled",
                  "--partialProgressMaxCommits",
                  String.valueOf(config.getPartialProgressMaxCommits())));
        }
      } else {
        args.addAll(
            Arrays.asList(
                "--strategy",
                com.linkedin.openhouse.datalayout.persistence.StrategiesDaoTableProps.serialize(
                    strategy)));
      }
    }
    Reading before = reading(formatVersion, fqtn);
    Table table = ops.getTable(fqtn);
    long snapshotBefore = table.currentSnapshot().snapshotId();
    Set<String> withDeletes = dataFilesWithDeletes(table);
    Map<String, String> dvsBefore =
        formatVersion == 3 ? deletionVectorFiles(table, table.currentSnapshot()) : null;

    List<String> argv = new ArrayList<>(Arrays.asList("--storageURL", "http://localhost:1"));
    argv.addAll(args);
    DataCompactionSparkApp app =
        DataCompactionSparkApp.createApp(argv.toArray(new String[0]), otelEmitter);
    app.runInner(ops);
    app.runValidations(ops);

    table = ops(formatVersion).getTable(fqtn);
    Set<String> liveAfter =
        liveDataFiles(table, table.currentSnapshot()).stream()
            .map(d -> d.path().toString())
            .collect(Collectors.toSet());
    List<String> rewrittenWithDeletes =
        withDeletes.stream().filter(f -> !liveAfter.contains(f)).collect(Collectors.toList());
    System.out.println(
        "X6-COMPACTION v"
            + formatVersion
            + " "
            + launch
            + " args="
            + args.subList(2, args.size()).stream()
                .map(a -> a.length() > 60 ? a.substring(0, 60) + "..." : a)
                .collect(Collectors.toList())
            + " committed="
            + (table.currentSnapshot().snapshotId() != snapshotBefore)
            + " rewroteFilesWithDeletes="
            + rewrittenWithDeletes.size()
            + "/"
            + withDeletes.size());
    if (icebergCanWriteV3()) {
      Assertions.assertNotEquals(
          snapshotBefore, table.currentSnapshot().snapshotId(), "compaction committed");
      Assertions.assertFalse(
          rewrittenWithDeletes.isEmpty(), "compaction rewrote data files that carried deletes");
    } else {
      // LinkedIn's Iceberg 1.5.2 plans nothing for this fixture with production arguments (no
      // partition reaches 5 files, and its bin-pack has no delete-ratio rule): pinned.
      Assertions.assertEquals(
          snapshotBefore, table.currentSnapshot().snapshotId(), "1.5.2 commits no rewrite here");
    }
    if (formatVersion == 3) {
      // The deletion vector of every data file the rewrite replaced is gone with it; the others
      // are untouched.
      Map<String, String> dvsAfter = deletionVectorFiles(table, table.currentSnapshot());
      for (String rewritten : rewrittenWithDeletes) {
        Assertions.assertFalse(dvsAfter.containsKey(rewritten), "DV of a rewritten file dropped");
      }
      for (Map.Entry<String, String> dv : dvsBefore.entrySet()) {
        if (liveAfter.contains(dv.getKey())) {
          Assertions.assertEquals(dv.getValue(), dvsAfter.get(dv.getKey()), "DV kept: " + dv);
        }
      }
    }
    assertNoDanglingDeletionVectors(formatVersion, table);
    assertNoLiveDataLost(formatVersion, fqtn, before, before.perSnapshot);
  }

  // ---------------------------------------------------------------------------- snapshot expiry

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void snapshotExpiryWithFileDeletionKeepsWhatRetainedSnapshotsNeed(int formatVersion)
      throws Exception {
    String fqtn = createTable(formatVersion, "expire", "orc");
    // The oldest partition leaves the table and one more INSERT follows, so its data and delete
    // files are referenced only by snapshots this expiry (keeping the last 2) removes.
    dropOldestPartition(formatVersion, fqtn);
    session(formatVersion)
        .sql("INSERT INTO openhouse." + fqtn + " VALUES (200, 'after-drop', " + ts(0, 59) + ")");
    Reading before = reading(formatVersion, fqtn);
    Table table = ops(formatVersion).getTable(fqtn);
    List<Long> ids = snapshotIds(table);
    Set<String> filesBefore = referencedFiles(table);
    Map<Long, List<String>> retained = new LinkedHashMap<>();
    for (long id : ids.subList(ids.size() - 2, ids.size())) {
      retained.put(id, before.perSnapshot.get(id));
    }
    if (formatVersion == 3) {
      Assertions.assertFalse(
          partiallyLivePuffinFiles(
                  table, ids.subList(0, ids.size() - 2), new ArrayList<>(retained.keySet()))
              .isEmpty(),
          "a Puffin file a retained snapshot needs also holds a vector an expired one replaced");
    }

    SnapshotsExpirationSparkApp app =
        new SnapshotsExpirationSparkApp(
            "x6-expiry", null, fqtn, 3, "days", 2, true, ".backup", otelEmitter);
    Operations ops = ops(formatVersion);
    app.runInner(ops);
    app.runValidations(ops);

    table = ops(formatVersion).getTable(fqtn);
    Assertions.assertEquals(new ArrayList<>(retained.keySet()), snapshotIds(table));
    assertNoLiveDataLost(formatVersion, fqtn, before, retained);
    // Not vacuous: files only the expired snapshots referenced are gone.
    Set<String> filesAfter = referencedFiles(table);
    Table finalTable = table;
    List<String> expiredOnly =
        filesBefore.stream()
            .filter(f -> !filesAfter.contains(f) && !f.endsWith("metadata.json"))
            .collect(Collectors.toList());
    Assertions.assertTrue(
        expiredOnly.stream().anyMatch(FormatV3MaintenanceTest::isDataOrDeleteFile),
        "expiry removes data or delete files, not only manifests: " + expiredOnly);
    Assertions.assertTrue(
        expiredOnly.stream().noneMatch(f -> finalTable.io().newInputFile(f).exists()),
        "files only expired snapshots referenced were deleted");
  }

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void metadataOnlyExpiryThenOrphanRemovalKeepsWhatRetainedSnapshotsNeed(int formatVersion)
      throws Exception {
    String fqtn = createTable(formatVersion, "expire_meta", "orc");
    dropOldestPartition(formatVersion, fqtn);
    Reading before = reading(formatVersion, fqtn);
    Set<String> filesBefore = referencedFiles(ops(formatVersion).getTable(fqtn));
    List<Long> ids = snapshotIds(ops(formatVersion).getTable(fqtn));
    Map<Long, List<String>> retained = new LinkedHashMap<>();
    for (long id : ids.subList(ids.size() - 1, ids.size())) {
      retained.put(id, before.perSnapshot.get(id));
    }

    Operations ops = ops(formatVersion);
    SnapshotsExpirationSparkApp expiry =
        new SnapshotsExpirationSparkApp(
            "x6-expiry-meta", null, fqtn, 3, "days", 1, false, ".backup", otelEmitter);
    expiry.runInner(ops);
    expiry.runValidations(ops);
    Assertions.assertEquals(
        new ArrayList<>(retained.keySet()), snapshotIds(ops(formatVersion).getTable(fqtn)));
    // Files only the expired snapshots referenced are now orphans; orphan removal with no age
    // margin must remove them and nothing a retained snapshot needs.
    OrphanFilesDeletionSparkApp orphans =
        new OrphanFilesDeletionSparkApp(
            "x6-ofd-after-expiry", null, fqtn, 0, otelEmitter, ".backup", 2, false, 1000);
    orphans.runInner(ops);
    orphans.runValidations(ops);
    assertNoLiveDataLost(formatVersion, fqtn, before, retained);
    Table table = ops(formatVersion).getTable(fqtn);
    Set<String> filesAfter = referencedFiles(table);
    List<String> removed =
        filesBefore.stream()
            .filter(f -> !filesAfter.contains(f) && !f.endsWith("metadata.json"))
            .collect(Collectors.toList());
    Assertions.assertTrue(
        removed.stream().anyMatch(FormatV3MaintenanceTest::isDataOrDeleteFile),
        "orphan removal had data or delete files to remove: " + removed);
    Assertions.assertTrue(
        removed.stream().noneMatch(f -> table.io().newInputFile(f).exists()),
        "orphan removal removed every file only expired snapshots referenced");
  }

  // ---------------------------------------------------------------------- orphan file removal

  @ParameterizedTest(name = "format v{0}, batched={1}")
  @CsvSource({"2, false", "3, false", "2, true", "3, true"})
  void orphanFileRemovalRemovesOnlyPlantedOrphans(int formatVersion, boolean batched)
      throws Exception {
    String fqtn = createTable(formatVersion, "ofd" + (batched ? "_batch" : ""), "orc");
    Reading before = reading(formatVersion, fqtn);
    Operations ops = ops(formatVersion);
    Table table = ops.getTable(fqtn);
    FileSystem fs = ops.fs();
    long hour = 3_600_000L;
    // Next to live Puffin and data files, and in the metadata directory.
    String livePuffinOrData =
        liveDeleteFiles(table, table.currentSnapshot()).get(0).path().toString();
    Path liveDir = new Path(livePuffinOrData).getParent();
    List<Path> planted =
        Arrays.asList(
            plant(fs, new Path(liveDir, "x6-orphan-deletes.puffin"), hour),
            plant(fs, new Path(liveDir, "x6-orphan-data." + "orc"), hour),
            plant(fs, new Path(table.location(), "metadata/x6-orphan-manifest.avro"), hour));

    if (batched) {
      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "x6-ofd-batch",
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
              1000);
      app.runInner(ops);
    } else {
      OrphanFilesDeletionSparkApp app =
          new OrphanFilesDeletionSparkApp(
              "x6-ofd", null, fqtn, 0, otelEmitter, ".backup", 2, false, 1000);
      app.runInner(ops);
      app.runValidations(ops);
    }

    for (Path orphan : planted) {
      Assertions.assertFalse(fs.exists(orphan), "planted orphan removed: " + orphan);
    }
    assertNoLiveDataLost(formatVersion, fqtn, before, before.perSnapshot);
  }

  // ------------------------------------------------- retention with backup and delete files

  /** Partition path of every live delete file of the snapshot, by the delete file's path. */
  private static Map<String, Set<String>> deleteFilePartitions(Table table, Snapshot snapshot) {
    Map<String, Set<String>> partitions = new java.util.TreeMap<>();
    for (DeleteFile d : liveDeleteFiles(table, snapshot)) {
      partitions
          .computeIfAbsent(d.path().toString(), k -> new TreeSet<>())
          .add(table.specs().get(d.specId()).partitionToPath(d.partition()));
    }
    return partitions;
  }

  private static List<Path> listRecursively(FileSystem fs, Path dir) throws IOException {
    List<Path> files = new ArrayList<>();
    if (!fs.exists(dir)) {
      return files;
    }
    org.apache.hadoop.fs.RemoteIterator<org.apache.hadoop.fs.LocatedFileStatus> it =
        fs.listFiles(dir, true);
    while (it.hasNext()) {
      files.add(it.next().getPath());
    }
    Collections.sort(files);
    return files;
  }

  /**
   * Retention with backup enabled on a merge-on-read table whose expired partitions carry delete
   * files, followed by what physically removes the expired files in production: snapshot expiry
   * with file deletion, or metadata-only expiry and then orphan removal. Both use the same
   * backup-or-delete handler. Establishes what lands in the backup directory, and that the
   * surviving partitions keep their delete files (a Puffin file shared with an expired partition
   * included) and their deleted rows stay deleted.
   */
  @ParameterizedTest(name = "format v{0}, files removed by {1}")
  @CsvSource({"2, expiry", "3, expiry", "2, orphans", "3, orphans"})
  void retentionBackupOfPartitionsWithDeleteFiles(int formatVersion, String remover)
      throws Exception {
    String fqtn = createTable(formatVersion, "ret_backup_" + remover, "orc");
    SparkSession spark = session(formatVersion);
    spark.sql(
        "ALTER TABLE openhouse."
            + fqtn
            + " SET TBLPROPERTIES ('"
            + AppConstants.BACKUP_ENABLED_KEY
            + "'='true')");
    Operations ops = ops(formatVersion);
    Table table = ops.getTable(fqtn);
    Snapshot beforeRetention = table.currentSnapshot();
    Map<String, Set<String>> deletesByPartition = deleteFilePartitions(table, beforeRetention);
    Set<String> survivingPartitions = new TreeSet<>();
    Set<String> expiredPartitions = new TreeSet<>();
    Map<String, String> dataFilePartition = new HashMap<>();
    for (DataFile d : liveDataFiles(table, beforeRetention)) {
      String partition = table.spec().partitionToPath(d.partition());
      dataFilePartition.put(d.path().toString(), partition);
    }
    for (int days : PARTITION_DAYS_AGO) {
      String partition =
          table
              .spec()
              .partitionToPath(
                  liveDataFiles(table, beforeRetention).stream()
                      .filter(
                          d ->
                              dataFilePartition
                                  .get(d.path().toString())
                                  .endsWith(today.minusDays(days).toLocalDate().toString()))
                      .findFirst()
                      .get()
                      .partition());
      (days >= 5 ? expiredPartitions : survivingPartitions).add(partition);
    }
    List<String> sharedAcrossKeptAndExpired =
        deletesByPartition.entrySet().stream()
            .filter(
                e ->
                    e.getValue().stream().anyMatch(expiredPartitions::contains)
                        && e.getValue().stream().anyMatch(survivingPartitions::contains))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    Set<String> survivingDeleteFiles =
        deletesByPartition.entrySet().stream()
            .filter(e -> e.getValue().stream().anyMatch(survivingPartitions::contains))
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(TreeSet::new));
    System.out.println(
        "X6-BACKUP v"
            + formatVersion
            + " "
            + remover
            + " delete files by partition: "
            + deletesByPartition);
    System.out.println(
        "X6-BACKUP v"
            + formatVersion
            + " "
            + remover
            + " shared across kept and expired: "
            + sharedAcrossKeptAndExpired);
    Reading before = reading(formatVersion, fqtn);

    RetentionSparkApp retention =
        new RetentionSparkApp(
            "x6-retention-backup", null, fqtn, "ts", "", "day", 3, otelEmitter, ".backup");
    retention.runInner(ops);
    retention.runValidations(ops);
    if ("expiry".equals(remover)) {
      SnapshotsExpirationSparkApp expiry =
          new SnapshotsExpirationSparkApp(
              "x6-expiry-backup", null, fqtn, 3, "days", 1, true, ".backup", otelEmitter);
      expiry.runInner(ops);
      expiry.runValidations(ops);
    } else {
      SnapshotsExpirationSparkApp expiry =
          new SnapshotsExpirationSparkApp(
              "x6-expiry-meta-backup", null, fqtn, 3, "days", 1, false, ".backup", otelEmitter);
      expiry.runInner(ops);
      OrphanFilesDeletionSparkApp orphans =
          new OrphanFilesDeletionSparkApp(
              "x6-ofd-backup", null, fqtn, 0, otelEmitter, ".backup", 2, false, 1000);
      orphans.runInner(ops);
      orphans.runValidations(ops);
    }

    // The surviving partitions: rows, deletes, row ids and their delete files are intact.
    Reading after = reading(formatVersion, fqtn);
    List<String> expectedLive =
        before.live.stream()
            .filter(r -> Long.parseLong(r.substring(1, r.indexOf(','))) >= 17)
            .collect(Collectors.toList());
    Assertions.assertEquals(expectedLive, after.live, "live rows inside retention");
    for (long id : DELETED_IDS) {
      Assertions.assertFalse(after.liveIds.contains(id), "deleted row " + id + " stays deleted");
    }
    if (formatVersion == 3) {
      List<String> expectedLineage =
          before.lineage.stream()
              .filter(r -> Long.parseLong(r.substring(1, r.indexOf(','))) >= 17)
              .collect(Collectors.toList());
      Assertions.assertEquals(expectedLineage, after.lineage, "_row_id of surviving rows");
    }
    table = ops(formatVersion).getTable(fqtn);
    Table finalTable = table;
    for (String deleteFile : survivingDeleteFiles) {
      Assertions.assertTrue(
          finalTable.io().newInputFile(deleteFile).exists(),
          "delete file of a surviving partition exists: " + deleteFile);
    }
    assertEveryReferencedFileExists(table);

    // What is in the backup directory, by kind, and which rows the backed-up data files hold.
    FileSystem fs = ops.fs();
    Path backupRoot = new Path(table.location(), ".backup");
    List<String> backedUpData = new ArrayList<>();
    Set<String> backedUpDataNames = new TreeSet<>();
    Set<String> backedUpDeleteNames = new TreeSet<>();
    Set<String> manifestListedNames = new TreeSet<>();
    for (Path p : listRecursively(fs, backupRoot)) {
      if (p.getName().startsWith("data_manifest")) {
        try (java.io.InputStream in = fs.open(p)) {
          String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          System.out.println("X6-BACKUP v" + formatVersion + " " + remover + " manifest: " + json);
          com.google.gson.JsonObject manifest =
              com.google.gson.JsonParser.parseString(json).getAsJsonObject();
          manifest
              .getAsJsonArray("files")
              .forEach(e -> manifestListedNames.add(new Path(e.getAsString()).getName()));
          Assertions.assertEquals(
              java.util.Set.of("file_count", "files"),
              manifest.keySet(),
              "the backup manifest records data files only, and nothing about deletes");
        }
      } else if (p.getName().contains("-deletes.")) {
        backedUpDeleteNames.add(p.getName());
      } else {
        backedUpData.add(p.toString());
        backedUpDataNames.add(p.getName());
      }
    }
    Set<String> expiredDataNames =
        dataFilePartition.entrySet().stream()
            .filter(e -> expiredPartitions.contains(e.getValue()))
            .map(e -> new Path(e.getKey()).getName())
            .collect(Collectors.toCollection(TreeSet::new));
    Assertions.assertEquals(
        expiredDataNames, backedUpDataNames, "every expired data file backed up");
    Assertions.assertEquals(
        expiredDataNames, manifestListedNames, "the manifests list exactly those data files");

    // The backed-up data files still hold the rows that were deleted before retention ran, and
    // nothing the manifest lists records that they were deleted: a restore driven by the
    // manifests brings them back. Pinned (work item X6, finding 1a), not changed here.
    Set<Long> idsInBackedUpData = new TreeSet<>();
    // Iceberg's ORC reader: Spark's own cannot parse Iceberg's ORC timestamp type attribute.
    org.apache.iceberg.Schema idOnly = table.schema().select("id");
    for (String file : backedUpData) {
      try (org.apache.iceberg.io.CloseableIterable<org.apache.iceberg.data.Record> records =
          org.apache
              .iceberg
              .orc
              .ORC
              .read(table.io().newInputFile(file))
              .project(idOnly)
              .createReaderFunc(
                  fileSchema ->
                      org.apache.iceberg.data.orc.GenericOrcReader.buildReader(idOnly, fileSchema))
              .build()) {
        records.forEach(r -> idsInBackedUpData.add((Long) r.getField("id")));
      }
    }
    Set<Long> allExpiredIds = new TreeSet<>();
    for (long id = 1; id <= 16; id++) {
      allExpiredIds.add(id);
    }
    Assertions.assertEquals(
        allExpiredIds, idsInBackedUpData, "deleted rows 2, 3, 6, 10, 14, 15 are in the backup");

    if (formatVersion == 3) {
      // Deletion vectors live in Puffin files at the data root, not in partition directories, and
      // one Puffin file serves partitions on both sides of the cut-off.
      Assertions.assertFalse(
          sharedAcrossKeptAndExpired.isEmpty(),
          "a Puffin file holds deletion vectors for an expired and a kept partition");
      Assertions.assertEquals(
          Collections.emptySet(), backedUpDeleteNames, "no deletion vector reaches the backup");
      for (Map.Entry<String, Set<String>> puffin : deletesByPartition.entrySet()) {
        boolean onlyExpired = expiredPartitions.containsAll(puffin.getValue());
        Assertions.assertEquals(
            !onlyExpired,
            finalTable.io().newInputFile(puffin.getKey()).exists(),
            "a Puffin file serving only expired partitions is deleted, any other is kept: "
                + puffin);
      }
    } else {
      // v2 position delete files live in their partition's directory: one partition each, and
      // they are moved into the backup with the data files, though no manifest lists them.
      Assertions.assertEquals(Collections.emptyList(), sharedAcrossKeptAndExpired);
      Assertions.assertFalse(backedUpDeleteNames.isEmpty(), "v2 delete files are in the backup");
      Assertions.assertTrue(
          Collections.disjoint(backedUpDeleteNames, manifestListedNames),
          "no manifest lists a delete file");
    }
  }

  // ---------------------------------------------------------------------------------- retention

  @ParameterizedTest(name = "format v{0}, backup={1}")
  @CsvSource({"2, false", "3, false", "2, true", "3, true"})
  void retentionDeletesOnlyExpiredPartitionsAndKeepsRowIds(int formatVersion, boolean backup)
      throws Exception {
    String fqtn = createTable(formatVersion, "retention" + (backup ? "_backup" : ""), "orc");
    SparkSession spark = session(formatVersion);
    if (backup) {
      spark.sql(
          "ALTER TABLE openhouse."
              + fqtn
              + " SET TBLPROPERTIES ('"
              + AppConstants.BACKUP_ENABLED_KEY
              + "'='true')");
    }
    Reading before = reading(formatVersion, fqtn);

    RetentionSparkApp app =
        new RetentionSparkApp(
            "x6-retention", null, fqtn, "ts", "", "day", 3, otelEmitter, ".backup");
    Operations ops = ops(formatVersion);
    app.runInner(ops);
    app.runValidations(ops);

    // The partitions 10 and 5 days old (ids 1 to 16) are past the 3-day retention; the rest stays.
    Reading after = reading(formatVersion, fqtn);
    List<String> expectedLive =
        before.live.stream()
            .filter(r -> Long.parseLong(r.substring(1, r.indexOf(','))) >= 17)
            .collect(Collectors.toList());
    Assertions.assertEquals(expectedLive, after.live, "live rows inside retention, full contents");
    Assertions.assertTrue(after.liveIds.stream().allMatch(id -> id >= 17));
    for (long id : DELETED_IDS) {
      Assertions.assertFalse(after.liveIds.contains(id), "deleted row " + id + " stays deleted");
    }
    if (formatVersion == 3) {
      List<String> expectedLineage =
          before.lineage.stream()
              .filter(r -> Long.parseLong(r.substring(1, r.indexOf(','))) >= 17)
              .collect(Collectors.toList());
      Assertions.assertEquals(expectedLineage, after.lineage, "_row_id of surviving rows");
    }
    Table table = ops(formatVersion).getTable(fqtn);
    for (Map.Entry<Long, List<String>> snapshot : before.perSnapshot.entrySet()) {
      Assertions.assertEquals(
          snapshot.getValue(),
          after.perSnapshot.get(snapshot.getKey()),
          "time travel to snapshot " + snapshot.getKey());
    }
    assertEveryReferencedFileExists(table);
  }

  // ------------------------------------------------------------------------ staged file removal

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void stagedFileRemovalTouchesOnlyTheTrashDirectory(int formatVersion) throws Exception {
    String fqtn = createTable(formatVersion, "staged", "orc");
    Reading before = reading(formatVersion, fqtn);
    Operations ops = ops(formatVersion);
    Table table = ops.getTable(fqtn);
    Path staged =
        plant(
            ops.fs(), new Path(table.location(), ".trash/data/x6-staged.puffin"), 2 * 86_400_000L);

    StagedFilesDeletionSparkApp app =
        new StagedFilesDeletionSparkApp("x6-staged", null, fqtn, ".trash", 1, true, otelEmitter);
    app.runInner(ops);
    app.runValidations(ops);

    Assertions.assertFalse(ops.fs().exists(staged));
    assertNoLiveDataLost(formatVersion, fqtn, before, before.perSnapshot);
  }

  // ------------------------------------------------------------- jobs that only read the table

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void tableStatsCollectionReadsAV3TableAndChangesNothing(int formatVersion) throws Exception {
    String fqtn = createTable(formatVersion, "stats", "orc");
    Reading before = reading(formatVersion, fqtn);
    Operations ops = ops(formatVersion);
    long current = ops.getTable(fqtn).currentSnapshot().snapshotId();

    IcebergTableStats stats = ops.collectTableStats(fqtn);
    Assertions.assertNotNull(stats, "stats collection failed (it returns null on an exception)");
    Assertions.assertEquals(12L, (long) stats.getNumSnapshots());
    Assertions.assertFalse(ops.collectCommitEventTable(fqtn).isEmpty());
    Assertions.assertFalse(ops.collectCommitEventTablePartitions(fqtn).isEmpty());
    Assertions.assertFalse(ops.collectCommitEventTablePartitionStats(fqtn).isEmpty());
    TableStatsCollectionSparkApp app =
        new TableStatsCollectionSparkApp("x6-stats", null, fqtn, otelEmitter);
    app.runInner(ops);
    app.runValidations(ops);

    Assertions.assertEquals(current, ops.getTable(fqtn).currentSnapshot().snapshotId());
    assertNoLiveDataLost(formatVersion, fqtn, before, before.perSnapshot);
  }

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void dataLayoutStrategyGenerationReadsAV3TableAndChangesNoData(int formatVersion)
      throws Exception {
    String fqtn = createTable(formatVersion, "dlo", "orc");
    Reading before = reading(formatVersion, fqtn);
    Operations ops = ops(formatVersion);
    String output = DB + ".dlo_out_v" + formatVersion;
    String partitionOutput = DB + ".dlo_partition_out_v" + formatVersion;
    session(formatVersion).sql("DROP TABLE IF EXISTS openhouse." + output);
    session(formatVersion).sql("DROP TABLE IF EXISTS openhouse." + partitionOutput);

    DataLayoutStrategyGeneratorSparkApp app =
        new DataLayoutStrategyGeneratorSparkApp(
            "x6-dlo", null, fqtn, output, partitionOutput, otelEmitter);
    app.runInner(ops);
    app.runValidations(ops);

    Assertions.assertNotNull(ops.getTable(fqtn).properties().get("write.data-layout.strategies"));
    assertNoLiveDataLost(formatVersion, fqtn, before, before.perSnapshot);
  }

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void batchedTableStatsCollectionPublishesAV3TableAndChangesNothing(int formatVersion)
      throws Exception {
    String fqtn = createTable(formatVersion, "stats_batch", "orc");
    Reading before = reading(formatVersion, fqtn);
    Operations ops = ops(formatVersion);
    long current = ops.getTable(fqtn).currentSnapshot().snapshotId();
    List<String> published = Collections.synchronizedList(new ArrayList<>());
    StatsCollectionSink sink =
        new StatsCollectionSink() {
          @Override
          public void publishStats(String table, IcebergTableStats stats) {
            published.add("stats:" + table + ":" + stats.getNumSnapshots());
          }

          @Override
          public void publishCommitEvents(
              String table,
              List<com.linkedin.openhouse.common.stats.model.CommitEventTable> events) {
            published.add("commits:" + table);
          }

          @Override
          public void publishPartitionEvents(
              String table,
              List<com.linkedin.openhouse.common.stats.model.CommitEventTablePartitions> events) {
            published.add("partitionEvents:" + table);
          }

          @Override
          public void publishPartitionStats(
              String table,
              List<com.linkedin.openhouse.common.stats.model.CommitEventTablePartitionStats>
                  stats) {
            published.add("partitionStats:" + table);
          }
        };

    BatchedTableStatsCollectionSparkApp app =
        new BatchedTableStatsCollectionSparkApp(
            "x6-stats-batch",
            null,
            otelEmitter,
            Collections.singletonList(
                BatchedTableStatsCollectionSparkApp.BatchEntry.builder()
                    .fqtn(fqtn)
                    .databaseName(DB)
                    .tableName(fqtn.substring(DB.length() + 1))
                    .build()),
            null,
            1,
            sink);
    app.runInner(ops);

    Assertions.assertTrue(published.contains("stats:" + fqtn + ":12"), published.toString());
    Assertions.assertTrue(published.contains("commits:" + fqtn), published.toString());
    Assertions.assertTrue(published.contains("partitionEvents:" + fqtn), published.toString());
    Assertions.assertTrue(published.contains("partitionStats:" + fqtn), published.toString());
    Assertions.assertEquals(current, ops.getTable(fqtn).currentSnapshot().snapshotId());
    assertNoLiveDataLost(formatVersion, fqtn, before, before.perSnapshot);
  }

  /**
   * Sort stats collection reads the table through {@code spark_catalog} (as a deployment that makes
   * the OpenHouse catalog the session catalog does), copies yesterday's {@code datepartition} into
   * a temporary table it compacts and drops, and sets one property on the table. The table's own
   * files and rows must not change.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void sortStatsCollectionReadsAV3TableAndChangesNoData(int formatVersion) throws Exception {
    SparkSession base = session(formatVersion);
    SparkSession spark = base.newSession();
    Map<String, String> all = new HashMap<>();
    base.conf().getAll().foreach(entry -> all.put(entry._1(), entry._2()));
    for (Map.Entry<String, String> entry : all.entrySet()) {
      if (entry.getKey().startsWith(CATALOG_PREFIX)
          || entry.getKey().startsWith(DEFAULT_ICEBERG_PREFIX)) {
        spark.conf().set(entry.getKey(), entry.getValue());
      }
      if (entry.getKey().startsWith(CATALOG_PREFIX + ".")) {
        String key = entry.getKey().substring(CATALOG_PREFIX.length());
        spark.conf().set("spark.sql.catalog.spark_catalog" + key, entry.getValue());
      }
    }
    spark.conf().set("spark.sql.catalog.spark_catalog", all.get(CATALOG_PREFIX));
    String fqtn = DB + ".sortstats_v" + formatVersion;
    String t = "openhouse." + fqtn;
    spark.sql("DROP TABLE IF EXISTS " + t);
    spark.sql("DROP TABLE IF EXISTS " + t + "_sample");
    String datepartition =
        LocalDateTime.now()
            .minusDays(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"));
    spark.sql(
        "CREATE TABLE "
            + t
            + " (id bigint, data string, header struct<memberId: bigint, time: bigint>,"
            + " datepartition string) TBLPROPERTIES ("
            + "'write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read'"
            + (formatVersion == 3 ? ", 'format-version'='3'" : "")
            + ")");
    for (int k = 0; k < 3; k++) {
      List<String> values = new ArrayList<>();
      for (int r = 1; r <= 5; r++) {
        long id = k * 5L + r;
        values.add(
            "("
                + id
                + ", 'row-"
                + id
                + "', named_struct('memberId', "
                + (id % 3)
                + "L, 'time', "
                + (100 - id)
                + "L), '"
                + datepartition
                + "')");
      }
      spark.sql("INSERT INTO " + t + " VALUES " + String.join(", ", values));
    }
    spark.sql("DELETE FROM " + t + " WHERE id IN (2, 6, 10, 14, 3)");
    spark.sql("UPDATE " + t + " SET data = 'updated' WHERE id = 7");
    Operations ops = Operations.withCatalog(spark, otelEmitter);
    Assertions.assertEquals(formatVersion, formatVersion(ops.getTable(fqtn)));
    Reading before = readingIn(spark, formatVersion, fqtn);

    SortStatsCollectionSparkApp app =
        new SortStatsCollectionSparkApp("x6-sort-stats", null, fqtn, otelEmitter);
    if (icebergCanWriteV3()) {
      app.runInner(ops);
      app.runValidations(ops);
      Assertions.assertNotNull(ops.getTable(fqtn).properties().get("sort-compression-rate"));
    } else {
      // On LinkedIn's Iceberg 1.5.2 the sort strategy of rewrite_data_files needs the session's
      // outermost parser to be Iceberg's, and the OpenHouse extensions wrap it (the order every
      // deployment uses, jobs.yaml), so the job fails before it rewrites anything. This exists
      // before format v3 and is pinned, not changed, here; the table must still be untouched.
      Throwable failure = Assertions.assertThrows(Exception.class, () -> app.runInner(ops));
      Assertions.assertTrue(
          String.valueOf(failure.getMessage()).contains("parser is not an Iceberg ExtendedParser"),
          String.valueOf(failure));
      Assertions.assertNull(ops.getTable(fqtn).properties().get("sort-compression-rate"));
    }
    Reading after = readingIn(spark, formatVersion, fqtn);
    Assertions.assertEquals(before.live, after.live, "live rows, full contents");
    Assertions.assertEquals(before.lineage, after.lineage, "_row_id and sequence numbers");
    for (long id : Arrays.asList(2L, 6L, 10L, 14L, 3L)) {
      Assertions.assertFalse(after.liveIds.contains(id), "deleted row " + id + " stays deleted");
    }
    Assertions.assertEquals(before.perSnapshot.keySet(), after.perSnapshot.keySet());
    for (Map.Entry<Long, List<String>> snapshot : before.perSnapshot.entrySet()) {
      Assertions.assertEquals(snapshot.getValue(), after.perSnapshot.get(snapshot.getKey()));
    }
    assertEveryReferencedFileExists(ops.getTable(fqtn));
  }

  // ------------------------------------------------------------------- orphan files, by hand

  /**
   * The Iceberg action's own result on a v3 table: no live Puffin file is ever in the orphan set,
   * even with no age margin. Pinned separately from the app so a failure names the action.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void deleteOrphanFilesNeverListsALiveFile(int formatVersion) throws Exception {
    String fqtn = createTable(formatVersion, "ofd_action", "parquet");
    Operations ops = ops(formatVersion);
    Table table = ops.getTable(fqtn);
    Set<String> referenced = referencedFiles(table);
    Path planted = plant(ops.fs(), new Path(table.location(), "data/x6-orphan.puffin"), 3_600_000L);
    DeleteOrphanFiles.Result result =
        ops.deleteOrphanFiles(table, System.currentTimeMillis(), ".backup", 1, false, 1000);
    List<String> orphans = new ArrayList<>();
    result.orphanFileLocations().forEach(orphans::add);
    Assertions.assertTrue(
        orphans.stream().anyMatch(o -> o.endsWith(planted.toUri().getPath())),
        "the planted orphan is listed: " + orphans);
    List<String> liveListed =
        orphans.stream()
            .filter(
                o -> referenced.stream().anyMatch(r -> r.endsWith(new Path(o).toUri().getPath())))
            .collect(Collectors.toList());
    Assertions.assertEquals(Collections.emptyList(), liveListed);
    assertEveryReferencedFileExists(ops.getTable(fqtn));
  }
}
