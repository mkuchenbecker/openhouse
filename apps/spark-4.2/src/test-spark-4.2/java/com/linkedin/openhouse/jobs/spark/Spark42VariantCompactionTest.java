package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.datalayout.config.DataCompactionConfig;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Spark 4.2 only (the measurement the variant cluster property waits for): data compaction and the
 * post-job validation on {@link FormatV3VariantJobsTest}'s fixture (format v3, Parquet, a variant
 * column holding object, array, string and null values, deletion vectors in two Puffin files, on a
 * service with {@code cluster.iceberg.format-v3.variant.enabled} on). On Spark 3.5 every job fails
 * on this table ({@link FormatV3VariantJobsTest} pins that); here compaction must rewrite the files
 * and keep every live row, its variant value, its row id and its last-updated sequence number, and
 * the post-job validation must pass. Also reads the table through Spark SQL, which Spark 3.5
 * cannot.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Spark42VariantCompactionTest extends OpenHouseSparkITest {
  private static final String DB = "j42variant";
  private static final List<Long> DELETED = Arrays.asList(2L, 6L, 10L, 14L, 19L, 23L);
  private static final String SCHEMA =
      "{\"type\":\"struct\",\"fields\":["
          + "{\"id\":1,\"name\":\"id\",\"required\":false,\"type\":\"long\"},"
          + "{\"id\":2,\"name\":\"v\",\"required\":false,\"type\":\"variant\"},"
          + "{\"id\":3,\"name\":\"ts\",\"required\":false,\"type\":\"timestamptz\"}]}";

  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
  private final LocalDateTime today =
      LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);

  private FormatV3Tables tables;
  private SparkSession spark;
  private Catalog catalog;

  @BeforeAll
  void start() throws Exception {
    tables = new FormatV3Tables(getSparkSession(), DB, otelEmitter);
    spark = tables.session(3);
    Map<String, String> conf = new HashMap<>();
    spark.conf().getAll().foreach(entry -> conf.put(entry._1(), entry._2()));
    Map<String, String> properties = new HashMap<>();
    String prefix = FormatV3Tables.CATALOG_PREFIX + ".";
    conf.forEach(
        (k, v) -> {
          if (k.startsWith(prefix)) {
            properties.put(k.substring(prefix.length()), v);
          }
        });
    catalog =
        CatalogUtil.buildIcebergCatalog(
            "j42variant", properties, spark.sparkContext().hadoopConfiguration());
  }

  @AfterAll
  void stop() {
    if (tables != null) {
      tables.close();
    }
  }

  private Operations ops() {
    return tables.ops(3);
  }

  private long micros(int daysAgo, int hour) {
    return today.minusDays(daysAgo).plusHours(hour).toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
  }

  /**
   * As {@link FormatV3VariantJobsTest}'s fixture: 8 data files, 6 deletion vectors, 10 snapshots.
   */
  private String createTable(String name) {
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
    for (int p = 0; p < FormatV3Tables.PARTITION_DAYS_AGO.length; p++) {
      for (int k = 0; k < 2; k++) {
        long[] ids = {next, next + 1, next + 2, next + 3};
        next += 4;
        VariantData.append(table, ids, micros(FormatV3Tables.PARTITION_DAYS_AGO[p], 1 + k), null);
      }
    }
    VariantData.delete(table, Arrays.asList(2L, 6L, 10L, 14L));
    table.refresh();
    VariantData.delete(table, Arrays.asList(19L, 23L));
    table.refresh();
    Assertions.assertEquals(3, FormatV3Tables.formatVersion(table));
    Assertions.assertEquals(10, FormatV3Tables.snapshotIds(table).size());
    return DB + "." + name;
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  private static Throwable run(org.junit.jupiter.api.function.Executable job) {
    try {
      job.execute();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  /**
   * {@link VariantData#read}'s rows cut to {@code id | v | ts | _row_id |
   * _last_updated_sequence_number}: a record read from a data file that has deletes also carries
   * the row's position as a sixth value, which a rewrite (no deletes left) drops.
   */
  private static List<String> rows(Table table) {
    return VariantData.read(table, null).stream()
        .map(
            r -> {
              String[] values = r.split(" \\| ");
              return String.join(" | ", Arrays.copyOf(values, Math.min(5, values.length)));
            })
        .collect(Collectors.toList());
  }

  private static List<String> dataFiles(Table table) {
    return FormatV3Tables.liveDataFiles(table, table.currentSnapshot()).stream()
        .map(f -> f.path().toString())
        .sorted()
        .collect(Collectors.toList());
  }

  /** Spark 4.2 reads the variant column through Spark SQL (Spark 3.5 cannot). */
  @Test
  void sparkSqlReadsTheVariantTable() {
    String fqtn = createTable("read_sql");
    List<String> rows =
        FormatV3Tables.rows(
            spark,
            "SELECT id, to_json(v), _row_id FROM openhouse." + fqtn + " WHERE id IN (1, 4, 32)");
    Assertions.assertEquals(3, rows.size(), rows.toString());
    Assertions.assertTrue(rows.stream().anyMatch(r -> r.contains("row-4")), rows.toString());
  }

  /**
   * Strict compaction (every file group due): the files are rewritten into fewer files, the
   * deletion vectors are applied, every live row reads exactly as before (variant value, row id,
   * last-updated sequence number), and the post-job validation passes.
   */
  @Test
  void strictCompactionRewritesAndKeepsVariantValuesAndRowLineage() throws Exception {
    String fqtn = createTable("compact_strict");
    Table before = ops().getTable(fqtn);
    Snapshot beforeSnapshot = before.currentSnapshot();
    List<String> beforeRows = rows(before);
    List<String> beforeFiles = dataFiles(before);
    Assertions.assertEquals(8, beforeFiles.size());
    Assertions.assertFalse(
        FormatV3Tables.liveDeleteFiles(before, beforeSnapshot).isEmpty(), "deletion vectors");

    DataCompactionSparkApp app =
        new DataCompactionSparkApp(
            "j42-compaction",
            null,
            fqtn,
            DataCompactionConfig.builder().minInputFiles(2).deleteFileThreshold(1).build(),
            otelEmitter);
    Throwable work = run(() -> app.runInner(ops()));
    Assertions.assertNull(work, () -> "the job's own work: " + chain(work));
    Throwable validation = run(() -> app.runValidations(ops()));
    Assertions.assertNull(validation, () -> "the post-job validation: " + chain(validation));

    Table after = ops().getTable(fqtn);
    Snapshot afterSnapshot = after.currentSnapshot();
    Assertions.assertNotEquals(
        beforeSnapshot.snapshotId(), afterSnapshot.snapshotId(), "compaction committed");
    Assertions.assertEquals("replace", afterSnapshot.operation());
    List<String> afterFiles = dataFiles(after);
    Assertions.assertTrue(
        Collections.disjoint(beforeFiles, afterFiles), "every data file was rewritten");
    Assertions.assertTrue(afterFiles.size() < beforeFiles.size(), afterFiles.toString());
    Assertions.assertEquals(
        Collections.emptyList(),
        FormatV3Tables.liveDeleteFiles(after, afterSnapshot),
        "deletion vectors applied");
    List<String> afterRows = rows(after);
    Assertions.assertEquals(beforeRows, afterRows, "live rows, variant values and row lineage");
    for (long id : DELETED) {
      Assertions.assertTrue(
          afterRows.stream().noneMatch(r -> r.startsWith(id + " | ")), "row " + id + " deleted");
    }
    Assertions.assertTrue(afterRows.stream().noneMatch(r -> r.endsWith("null")), "lineage kept");
    Assertions.assertEquals(26, afterRows.size());
    Assertions.assertTrue(
        afterRows.stream().allMatch(r -> r.split(" \\| ").length == 5), afterRows.toString());
  }

  /**
   * Compaction with the scheduler's production arguments (table name only): records what it does
   * (on Spark 3.5 it plans nothing here); the post-job validation must pass and no live row change.
   */
  @Test
  void compactionWithProductionArgumentsKeepsRowsAndValidates() throws Exception {
    String fqtn = createTable("compact_table_only");
    List<String> beforeRows = rows(ops().getTable(fqtn));
    long beforeSnapshot = ops().getTable(fqtn).currentSnapshot().snapshotId();
    DataCompactionSparkApp app =
        DataCompactionSparkApp.createApp(
            new String[] {"--storageURL", "http://localhost:1", "--tableName", fqtn}, otelEmitter);
    Throwable work = run(() -> app.runInner(ops()));
    Assertions.assertNull(work, () -> "the job's own work: " + chain(work));
    Throwable validation = run(() -> app.runValidations(ops()));
    Assertions.assertNull(validation, () -> "the post-job validation: " + chain(validation));
    Table after = ops().getTable(fqtn);
    System.out.println(
        "[j42] production-args compaction: snapshot "
            + (after.currentSnapshot().snapshotId() == beforeSnapshot ? "unchanged" : "replaced")
            + ", data files "
            + dataFiles(after).size());
    Assertions.assertEquals(beforeRows, rows(after));
  }
}
