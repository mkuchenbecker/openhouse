package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.OtelEmitter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
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
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * The format v2 and v3 tables the maintenance tests run on, and the checks they make: two sessions
 * (v2 tables on the tables service every apps test uses, with the switch off as in production; v3
 * tables on a second service with {@code cluster.iceberg.format-v3.enabled} on), the fixture table,
 * what a table reads, and every file its snapshots reference. Used by {@link
 * FormatV3MaintenanceTest} and {@link FormatV3ConcurrencyTest}.
 */
final class FormatV3Tables implements AutoCloseable {
  static final String CATALOG_PREFIX = "spark.sql.catalog.openhouse";
  static final String DEFAULT_ICEBERG_PREFIX = "spark.sql.catalog.default_iceberg";

  static final int[] PARTITION_DAYS_AGO = {10, 5, 1, 0};
  /** Rows {@link #createTable} deletes, through DELETE (twice) and MERGE. */
  static final List<Long> DELETED_IDS =
      Collections.unmodifiableList(Arrays.asList(2L, 6L, 10L, 14L, 18L, 22L, 26L, 30L, 3L, 15L));

  private final String db;
  private final OtelEmitter otelEmitter;
  private final FormatV3TablesServer server;
  /** The session on the default service (format v2 tables). */
  private final SparkSession v2Spark;
  /** A session whose {@code openhouse} catalog is the service with the switch on (v3 tables). */
  private final SparkSession v3Spark;

  /**
   * One catalog per format version for the tests' own table loads. Operations.getTable builds a new
   * catalog on every call, and each OpenHouse client it builds keeps a connection pool (reactor
   * netty, evicting in the background, never disposed: client WebClientFactory) that stays
   * reachable for the life of the JVM; loading through Operations in a loop grows the test JVM's
   * heap by one pool per load. The jobs' own code still builds its catalogs as in production.
   */
  private final Map<Integer, org.apache.iceberg.catalog.Catalog> catalogs = new HashMap<>();

  private final Map<Integer, Operations> opsByFormat = new ConcurrentHashMap<>();
  private final List<Operations> ownOps = new CopyOnWriteArrayList<>();

  /** Midnight today, UTC: rows are written in partitions this many days before it. */
  final LocalDateTime today =
      LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);

  /**
   * @param base the apps tests' session, on the default service
   * @param db the database the tables are created in
   */
  FormatV3Tables(SparkSession base, String db, OtelEmitter otelEmitter) {
    this.db = db;
    this.otelEmitter = otelEmitter;
    this.v2Spark = base;
    this.server = new FormatV3TablesServer();
    this.v3Spark = copyCatalogs(base, base.newSession());
    v3Spark.conf().set(CATALOG_PREFIX + ".uri", server.uri());
    v3Spark.conf().set(DEFAULT_ICEBERG_PREFIX + ".uri", server.uri());
  }

  /** Copies the {@code openhouse} and {@code default_iceberg} catalogs of {@code from}. */
  static SparkSession copyCatalogs(SparkSession from, SparkSession to) {
    Map<String, String> all = new HashMap<>();
    from.conf().getAll().foreach(entry -> all.put(entry._1(), entry._2()));
    for (Map.Entry<String, String> entry : all.entrySet()) {
      if (entry.getKey().startsWith(CATALOG_PREFIX)
          || entry.getKey().startsWith(DEFAULT_ICEBERG_PREFIX)) {
        to.conf().set(entry.getKey(), entry.getValue());
      }
    }
    return to;
  }

  /** The table {@code fqtn}, loaded fresh through this format version's one catalog. */
  Table table(int formatVersion, String fqtn) {
    SparkSession spark = session(formatVersion);
    org.apache.iceberg.catalog.Catalog catalog =
        catalogs.computeIfAbsent(
            formatVersion,
            v -> {
              Map<String, String> properties = new HashMap<>();
              String prefix = CATALOG_PREFIX + ".";
              spark
                  .conf()
                  .getAll()
                  .foreach(
                      entry -> {
                        if (entry._1().startsWith(prefix)) {
                          properties.put(entry._1().substring(prefix.length()), entry._2());
                        }
                        return null;
                      });
              return org.apache.iceberg.CatalogUtil.buildIcebergCatalog(
                  "openhouse_tests_v" + v, properties, spark.sparkContext().hadoopConfiguration());
            });
    return catalog.loadTable(org.apache.iceberg.catalog.TableIdentifier.parse(fqtn));
  }

  @Override
  public void close() {
    List<Operations> opened = new ArrayList<>(opsByFormat.values());
    opened.addAll(ownOps);
    for (Operations ops : opened) {
      try {
        ops.close();
      } catch (Exception e) {
        // best effort
      }
    }
    opsByFormat.clear();
    ownOps.clear();
    for (org.apache.iceberg.catalog.Catalog catalog : catalogs.values()) {
      if (catalog instanceof AutoCloseable) {
        try {
          ((AutoCloseable) catalog).close();
        } catch (Exception e) {
          // best effort
        }
      }
    }
    server.close();
  }

  String db() {
    return db;
  }

  /** Row lineage's metadata columns exist only on an Iceberg that writes format v3 (1.12 line). */
  static boolean icebergCanWriteV3() {
    try {
      org.apache.iceberg.MetadataColumns.class.getField("ROW_ID");
      return true;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  /** The session for tables of this format version; a v3 case skips itself on the 1.5.2 line. */
  SparkSession session(int formatVersion) {
    if (formatVersion == 3) {
      Assumptions.assumeTrue(
          icebergCanWriteV3(), "format v3 needs the Iceberg 1.12 line; this is the 1.5.2 line");
      return v3Spark;
    }
    return v2Spark;
  }

  /**
   * The jobs' {@link Operations} on this format version's session: one per format version, built on
   * first use and closed with this object. {@code Operations} holds one catalog whose client keeps
   * a connection pool until it is closed, so a new one per call, never closed, grew the test JVM's
   * heap by one pool per call.
   */
  Operations ops(int formatVersion) {
    SparkSession session = session(formatVersion);
    return opsByFormat.computeIfAbsent(
        formatVersion, v -> Operations.withCatalog(session, otelEmitter));
  }

  /**
   * An {@link Operations} with its own session or emitter (a spy, say), not the shared one of
   * {@link #ops}: closed with this object.
   */
  Operations newOps(SparkSession session, OtelEmitter emitter) {
    Operations ops = Operations.withCatalog(session, emitter);
    ownOps.add(ops);
    return ops;
  }

  String ts(int daysAgo, int minute) {
    return "TIMESTAMP '"
        + today.minusDays(daysAgo).plusMinutes(minute).toString().replace('T', ' ')
        + "'";
  }

  /**
   * The first part of {@link #createTable}: creates the table (merge-on-read, partitioned by
   * days(ts)) and runs its 8 INSERTs (8 snapshots), without the row-level statements.
   */
  String createAndInsert(int formatVersion, String name, String fileFormat) {
    SparkSession spark = session(formatVersion);
    String fqtn = db + "." + name + "_v" + formatVersion + "_" + fileFormat;
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
    return fqtn;
  }

  /**
   * Creates {@code <db>.<name>_v<formatVersion>_<fileFormat>} and fills it: 4 daily partitions, two
   * INSERTs of 4 rows each per partition (8 data files; ids {@code p * 8 + k * 4 + 1} to {@code +
   * 4} share one file), a DELETE touching every data file, a second DELETE touching one data file
   * only (on v3 it replaces one deletion vector, so the first Puffin file stays live for the
   * others), an UPDATE, and a MERGE that updates, deletes and inserts. 12 snapshots.
   */
  String createTable(int formatVersion, String name, String fileFormat) {
    SparkSession spark = session(formatVersion);
    String fqtn = createAndInsert(formatVersion, name, fileFormat);
    String t = "openhouse." + fqtn;
    rowLevelWrites(spark, t);
    Table table = table(formatVersion, fqtn);
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

  /**
   * The four row-level statements of {@link #createTable} on table {@code t} (catalog-qualified): a
   * DELETE touching every data file of the fixture's layout, a DELETE of one row, an UPDATE, and a
   * MERGE that updates, deletes and inserts; the deleted ids are {@link #DELETED_IDS}. Shared with
   * the fixtures of other table shapes built on the same id layout.
   */
  void rowLevelWrites(SparkSession spark, String t) {
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
  }

  static int formatVersion(Table table) {
    return ((HasTableOperations) table).operations().current().formatVersion();
  }

  static List<Long> snapshotIds(Table table) {
    List<Long> ids = new ArrayList<>();
    table.snapshots().forEach(s -> ids.add(s.snapshotId()));
    return ids;
  }

  static List<Row> read(SparkSession spark, String sql) {
    return spark.sql(sql).collectAsList();
  }

  static List<String> rows(SparkSession spark, String sql) {
    return read(spark, sql).stream().map(Row::toString).sorted().collect(Collectors.toList());
  }

  /** What a table reads, recorded before a job runs and compared after it. */
  static final class Reading {
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

  Reading reading(int formatVersion, String fqtn) {
    return readingIn(session(formatVersion), formatVersion, fqtn);
  }

  Reading readingIn(SparkSession spark, int formatVersion, String fqtn) {
    String t = "openhouse." + fqtn;
    spark.sql("REFRESH TABLE " + t);
    List<String> live = rows(spark, "SELECT * FROM " + t);
    Map<Long, List<String>> perSnapshot = new LinkedHashMap<>();
    for (long id : snapshotIds(table(formatVersion, fqtn))) {
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

  /** Every file each snapshot of the table references, and the statistics files. */
  static Set<String> referencedFiles(Table table) {
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

  static List<ContentFile<?>> manifestEntries(Table table, ManifestFile manifest) {
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

  static List<DataFile> liveDataFiles(Table table, Snapshot snapshot) {
    List<DataFile> files = new ArrayList<>();
    for (ManifestFile manifest : snapshot.dataManifests(table.io())) {
      manifestEntries(table, manifest).forEach(f -> files.add((DataFile) f));
    }
    return files;
  }

  static List<DeleteFile> liveDeleteFiles(Table table, Snapshot snapshot) {
    List<DeleteFile> files = new ArrayList<>();
    for (ManifestFile manifest : snapshot.deleteManifests(table.io())) {
      manifestEntries(table, manifest).forEach(f -> files.add((DeleteFile) f));
    }
    return files;
  }

  static void assertEveryReferencedFileExists(Table table) {
    List<String> missing =
        referencedFiles(table).stream()
            .filter(f -> !table.io().newInputFile(f).exists())
            .collect(Collectors.toList());
    Assertions.assertEquals(Collections.emptyList(), missing, "files a retained snapshot needs");
  }

  /** Data files of the current snapshot that have delete files applied to them. */
  static Set<String> dataFilesWithDeletes(Table table) throws IOException {
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
  static String referencedDataFile(DeleteFile deleteFile) {
    try {
      Object referenced = DeleteFile.class.getMethod("referencedDataFile").invoke(deleteFile);
      return referenced == null ? null : referenced.toString();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** On v3: the Puffin file holding each data file's deletion vector in the snapshot. */
  static Map<String, String> deletionVectorFiles(Table table, Snapshot snapshot) {
    Map<String, String> byDataFile = new HashMap<>();
    for (DeleteFile d : liveDeleteFiles(table, snapshot)) {
      byDataFile.put(referencedDataFile(d), d.path().toString());
    }
    return byDataFile;
  }

  /** On v3, every live deletion vector applies to a live data file. */
  static void assertNoDanglingDeletionVectors(int formatVersion, Table table) {
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
}
