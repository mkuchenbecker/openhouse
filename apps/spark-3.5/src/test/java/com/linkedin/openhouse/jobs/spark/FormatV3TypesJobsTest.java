package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.common.stats.model.IcebergTableStats;
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
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The maintenance jobs on format v3 tables with the two column types the tables service takes there
 * (work item X5), on a tables service with the cluster switch on: stats collection (the one job
 * with type-specific code), data compaction and retention on a table with a {@code void} column
 * (Iceberg's {@code unknown}); and what the Spark 3.5 jobs do with a table that has a {@code
 * variant} column, which Spark 3.5 cannot read. Format v3 needs the Iceberg 1.12 line, so these
 * tests are skipped on the reference line.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FormatV3TypesJobsTest extends OpenHouseSparkITest {
  private static final String DB = "x5jobs";
  private static final String CATALOG_PREFIX = FormatV3Tables.CATALOG_PREFIX;

  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));

  /** The v3 tables service and its session, shared with the other format v3 job tests. */
  private FormatV3Tables tables;

  private SparkSession spark;
  private Catalog catalog;

  private final LocalDateTime today =
      LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);

  @BeforeAll
  void start() throws Exception {
    tables = new FormatV3Tables(getSparkSession(), DB, otelEmitter);
    if (!FormatV3Tables.icebergCanWriteV3()) {
      return;
    }
    spark = tables.session(3);
    Map<String, String> all = new HashMap<>();
    spark.conf().getAll().foreach(entry -> all.put(entry._1(), entry._2()));
    Map<String, String> properties = new HashMap<>();
    for (String key : Arrays.asList("uri", "cluster", "auth-token")) {
      if (all.containsKey(CATALOG_PREFIX + "." + key)) {
        properties.put(key, all.get(CATALOG_PREFIX + "." + key));
      }
    }
    catalog =
        CatalogUtil.loadCatalog(
            all.get(CATALOG_PREFIX + ".catalog-impl"),
            "x5jobs",
            properties,
            spark.sparkContext().hadoopConfiguration());
  }

  @AfterAll
  void stop() {
    if (tables != null) {
      tables.close();
    }
  }

  /** Row lineage's metadata columns exist only on an Iceberg that writes format v3 (1.12 line). */
  private static void requireFormatV3() {
    boolean v3;
    try {
      org.apache.iceberg.MetadataColumns.class.getField("ROW_ID");
      v3 = true;
    } catch (NoSuchFieldException e) {
      v3 = false;
    }
    Assumptions.assumeTrue(v3, "format v3 needs the Iceberg 1.12 line; this is the 1.5.2 line");
  }

  private Operations ops() {
    return tables.ops(3);
  }

  private String ts(int daysAgo) {
    return "TIMESTAMP '" + today.minusDays(daysAgo).plusHours(1).toString().replace('T', ' ') + "'";
  }

  private List<String> rows(String sql) {
    return spark.sql(sql).collectAsList().stream().map(Row::toString).collect(Collectors.toList());
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  @Test
  void statsCompactionAndRetentionOnAV3TableWithAVoidColumn() throws Exception {
    requireFormatV3();
    String fqtn = DB + ".with_void";
    String t = "openhouse." + fqtn;
    spark.sql("DROP TABLE IF EXISTS " + t);
    spark.sql(
        "CREATE TABLE "
            + t
            + " (id bigint, c void, ts timestamp) PARTITIONED BY (days(ts))"
            + " TBLPROPERTIES ('format-version'='3')");
    long id = 0;
    for (int daysAgo : new int[] {10, 10, 0, 0}) {
      id++;
      spark.sql("INSERT INTO " + t + " VALUES (" + id + ", NULL, " + ts(daysAgo) + ")");
    }
    Operations ops = ops();

    IcebergTableStats stats = ops.collectTableStats(fqtn);
    Assertions.assertNotNull(stats, "stats collection failed (it returns null on an exception)");
    Assertions.assertEquals(4L, (long) stats.getNumSnapshots());
    Assertions.assertFalse(ops.collectCommitEventTablePartitionStats(fqtn).isEmpty());
    TableStatsCollectionSparkApp app =
        new TableStatsCollectionSparkApp("x5-stats", null, fqtn, otelEmitter);
    app.runInner(ops);
    app.runValidations(ops);

    DataCompactionSparkApp compaction =
        new DataCompactionSparkApp(
            "x5-compaction",
            null,
            fqtn,
            DataCompactionConfig.builder().minInputFiles(2).build(),
            otelEmitter);
    compaction.runInner(ops);
    compaction.runValidations(ops);
    Assertions.assertEquals(
        "replace", ops.getTable(fqtn).currentSnapshot().operation(), "compaction committed");
    Assertions.assertEquals(
        Arrays.asList("[1,null]", "[2,null]", "[3,null]", "[4,null]"),
        rows("SELECT id, c FROM " + t + " ORDER BY id"));

    RetentionSparkApp retention =
        new RetentionSparkApp("x5-retention", null, fqtn, "ts", "", "day", 3, otelEmitter, ".bk");
    retention.runInner(ops);
    retention.runValidations(ops);
    Assertions.assertEquals(
        Arrays.asList("[3,null]", "[4,null]"), rows("SELECT id, c FROM " + t + " ORDER BY id"));
  }

  /**
   * A table with a variant column (made through the Iceberg API, as Spark 4 makes one over the
   * Iceberg REST routes; Spark 3.5 can neither declare nor write one): the jobs that read only
   * metadata run (stats collection, snapshot expiry, and compaction of an empty table), on both
   * lines. Retention cannot load the table into Spark 3.5, which has no variant type; that is why
   * the tables service refuses a retention policy on such a table. On Spark 4.2 retention runs.
   */
  @Test
  void theSparkThreeFiveJobsOnAV3TableWithAVariantColumn() throws Exception {
    requireFormatV3();
    String fqtn = DB + ".with_variant";
    Map<String, String> properties = new HashMap<>();
    properties.put("format-version", "3");
    properties.put("write.format.default", "parquet");
    Schema schema =
        SchemaParser.fromJson(
            "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"name\":\"id\",\"required\":false,"
                + "\"type\":\"long\"},{\"id\":2,\"name\":\"v\",\"required\":false,"
                + "\"type\":\"variant\"},{\"id\":3,\"name\":\"ts\",\"required\":false,"
                + "\"type\":\"timestamptz\"}]}");
    catalog.createTable(
        TableIdentifier.of(DB, "with_variant"),
        schema,
        PartitionSpec.builderFor(schema).day("ts").build(),
        properties);
    Operations ops = ops();
    Assertions.assertNotNull(ops.collectTableStats(fqtn), "stats collection reads metadata only");
    Assertions.assertEquals(
        Collections.emptyList(), ops.collectCommitEventTablePartitionStats(fqtn));
    DataCompactionSparkApp compaction =
        new DataCompactionSparkApp(
            "x5-compaction-v",
            null,
            fqtn,
            DataCompactionConfig.builder().minInputFiles(2).build(),
            otelEmitter);
    compaction.runInner(ops);
    ops.expireSnapshots(fqtn, 1, "days", 1);
    RetentionSparkApp retention =
        new RetentionSparkApp("x5-retention-v", null, fqtn, "ts", "", "day", 3, otelEmitter, ".bk");
    if (JobsLine.sparkReadsVariant()) {
      retention.runInner(ops);
      retention.runValidations(ops);
      return;
    }
    Throwable failed = Assertions.assertThrows(Throwable.class, () -> retention.runInner(ops));
    Assertions.assertTrue(chain(failed).contains("Unsupported type: variant"), chain(failed));
  }
}
