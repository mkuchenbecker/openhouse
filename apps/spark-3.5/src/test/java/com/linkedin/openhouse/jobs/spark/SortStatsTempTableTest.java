package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Collections;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.Table;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The temporary copy {@link SortStatsCollectionSparkApp} makes takes the source table's format
 * version: a format v2 source gives the statement the app always ran (a v2 copy, the service's
 * defaults); a v3 source gives a v3 copy with the source's write format. Run through the tables
 * service on both lines (the statement executed as the app executes it).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SortStatsTempTableTest extends OpenHouseSparkITest {
  private static final String DB = "j5sort";
  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
  private FormatV3Tables tables;

  @BeforeAll
  void start() throws Exception {
    tables = new FormatV3Tables(getSparkSession(), DB, otelEmitter);
  }

  @AfterAll
  void stop() {
    if (tables != null) {
      tables.close();
    }
  }

  @Test
  void aFormatV2SourceGivesTheStatementTheAppAlwaysRan() {
    Assertions.assertEquals(
        "create table openhouse.db.t_sample as select * from spark_catalog.db.t"
            + " where datepartition = '2026-11-23-00' limit 10",
        SortStatsCollectionSparkApp.createTempTableStatement(
            "db.t_sample", "db.t", "2026-11-23-00", 10, 2, "orc"));
    Assertions.assertEquals(
        "create table openhouse.db.t_sample as select * from spark_catalog.db.t"
            + " where datepartition = '2026-11-23-00' limit 10",
        SortStatsCollectionSparkApp.createTempTableStatement(
            "db.t_sample", "db.t", "2026-11-23-00", 10, 2, null));
  }

  @Test
  void aFormatV3SourceGivesAV3CopyInItsWriteFormat() {
    Assertions.assertEquals(
        "create table openhouse.db.t_sample tblproperties ('format-version'='3',"
            + " 'write.format.default'='parquet') as select * from spark_catalog.db.t"
            + " where datepartition = '2026-11-23-00' limit 10",
        SortStatsCollectionSparkApp.createTempTableStatement(
            "db.t_sample", "db.t", "2026-11-23-00", 10, 3, "parquet"));
    Assertions.assertEquals(
        "create table openhouse.db.t_sample tblproperties ('format-version'='3') as select *"
            + " from spark_catalog.db.t where datepartition = '2026-11-23-00' limit 10",
        SortStatsCollectionSparkApp.createTempTableStatement(
            "db.t_sample", "db.t", "2026-11-23-00", 10, 3, null));
  }

  @Test
  void theCopyHasTheSourcesFormatVersionThroughTheService() {
    for (int formatVersion : new int[] {2, 3}) {
      if (formatVersion == 3 && !FormatV3Tables.icebergCanWriteV3()) {
        continue;
      }
      SparkSession spark = tables.session(formatVersion);
      // The app reads the source through spark_catalog, which these sessions point at openhouse.
      SparkSession session = FormatV3Tables.copyCatalogs(spark, spark.newSession());
      spark
          .conf()
          .getAll()
          .foreach(
              entry -> {
                if (entry._1().startsWith(FormatV3Tables.CATALOG_PREFIX + ".")) {
                  session
                      .conf()
                      .set(
                          "spark.sql.catalog.spark_catalog"
                              + entry._1().substring(FormatV3Tables.CATALOG_PREFIX.length()),
                          entry._2());
                }
                return null;
              });
      session
          .conf()
          .set("spark.sql.catalog.spark_catalog", spark.conf().get(FormatV3Tables.CATALOG_PREFIX));
      String source = DB + ".src_v" + formatVersion;
      String copy = source + "_sample";
      session.sql("DROP TABLE IF EXISTS openhouse." + source);
      session.sql("DROP TABLE IF EXISTS openhouse." + copy);
      session.sql(
          "CREATE TABLE openhouse."
              + source
              + " (id bigint, datepartition string) TBLPROPERTIES ('write.format.default'='parquet'"
              + (formatVersion == 3 ? ", 'format-version'='3'" : "")
              + ")");
      session.sql("INSERT INTO openhouse." + source + " VALUES (1, 'p'), (2, 'p')");
      Operations ops = Operations.withCatalog(session, otelEmitter);
      Table table = ops.getTable(source);
      Assertions.assertEquals(formatVersion, FormatV3Tables.formatVersion(table));
      // The app reads the version and write format from the plan of its data_files query.
      BaseTable read =
          SortStatsCollectionSparkApp.sourceTableOf(
              session.sql(
                  "select sum(file_size_in_bytes) from openhouse." + source + ".data_files"));
      Assertions.assertNotNull(read, "the query's plan names the source table");
      Assertions.assertEquals(table.uuid(), read.uuid());
      Assertions.assertEquals(formatVersion, read.operations().current().formatVersion());
      Assertions.assertEquals("parquet", read.properties().get("write.format.default"));
      Assertions.assertNull(SortStatsCollectionSparkApp.sourceTableOf(session.sql("select 1")));
      session.sql(
          SortStatsCollectionSparkApp.createTempTableStatement(
              copy,
              source,
              "p",
              10,
              read.operations().current().formatVersion(),
              read.properties().get("write.format.default")));
      Table created = ops.getTable(copy);
      Assertions.assertEquals(formatVersion, FormatV3Tables.formatVersion(created), "copy version");
      Assertions.assertEquals(
          2L, session.sql("SELECT * FROM openhouse." + copy).count(), "the rows were copied");
      session.sql("DROP TABLE openhouse." + copy);
      session.sql("DROP TABLE openhouse." + source);
    }
  }
}
