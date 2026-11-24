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
      List<Row> lineage = read(spark, "SELECT _row_id FROM " + t + " WHERE _row_id IS NULL");
      Assertions.assertTrue(lineage.isEmpty(), "every row has a row id");
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
    SparkSession spark = session(formatVersion);
    String t = "openhouse." + fqtn;
    spark.sql("REFRESH TABLE " + t);
    List<String> live = rows(spark, "SELECT id, data, ts FROM " + t);
    Map<Long, List<String>> perSnapshot = new LinkedHashMap<>();
    for (long id : snapshotIds(ops(formatVersion).getTable(fqtn))) {
      perSnapshot.put(id, rows(spark, "SELECT id, data, ts FROM " + t + " VERSION AS OF " + id));
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
    assertNoLiveDataLost(formatVersion, fqtn, before, before.perSnapshot);
  }

  // ---------------------------------------------------------------------------- snapshot expiry

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void snapshotExpiryWithFileDeletionKeepsWhatRetainedSnapshotsNeed(int formatVersion)
      throws Exception {
    String fqtn = createTable(formatVersion, "expire", "orc");
    Reading before = reading(formatVersion, fqtn);
    Table table = ops(formatVersion).getTable(fqtn);
    List<Long> ids = snapshotIds(table);
    Set<String> filesBefore = referencedFiles(table);
    Map<Long, List<String>> retained = new LinkedHashMap<>();
    for (long id : ids.subList(ids.size() - 2, ids.size())) {
      retained.put(id, before.perSnapshot.get(id));
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
    Assertions.assertFalse(expiredOnly.isEmpty());
    Assertions.assertTrue(
        expiredOnly.stream().noneMatch(f -> finalTable.io().newInputFile(f).exists()),
        "files only expired snapshots referenced were deleted");
  }

  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void metadataOnlyExpiryThenOrphanRemovalKeepsWhatRetainedSnapshotsNeed(int formatVersion)
      throws Exception {
    String fqtn = createTable(formatVersion, "expire_meta", "orc");
    Reading before = reading(formatVersion, fqtn);
    List<Long> ids = snapshotIds(ops(formatVersion).getTable(fqtn));
    Map<Long, List<String>> retained = new LinkedHashMap<>();
    for (long id : ids.subList(ids.size() - 3, ids.size())) {
      retained.put(id, before.perSnapshot.get(id));
    }

    Operations ops = ops(formatVersion);
    SnapshotsExpirationSparkApp expiry =
        new SnapshotsExpirationSparkApp(
            "x6-expiry-meta", null, fqtn, 3, "days", 3, false, ".backup", otelEmitter);
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
    DeleteOrphanFiles.Result result =
        ops.deleteOrphanFiles(table, System.currentTimeMillis(), ".backup", 1, false, 1000);
    List<String> orphans = new ArrayList<>();
    result.orphanFileLocations().forEach(orphans::add);
    List<String> liveListed =
        orphans.stream()
            .filter(
                o -> referenced.stream().anyMatch(r -> r.endsWith(new Path(o).toUri().getPath())))
            .collect(Collectors.toList());
    Assertions.assertEquals(Collections.emptyList(), liveListed);
    assertEveryReferencedFileExists(ops.getTable(fqtn));
  }
}
