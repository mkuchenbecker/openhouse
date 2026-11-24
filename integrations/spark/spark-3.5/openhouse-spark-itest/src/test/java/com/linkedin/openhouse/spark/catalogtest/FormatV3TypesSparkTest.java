package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

/**
 * The column types format v3 adds (work item X5), through Spark 3.5 SQL with the OpenHouse catalog
 * (the native API), against a tables service with the cluster switch on. Of those types Spark 3.5
 * can spell only one, {@code void}, which Iceberg's Spark integration maps to Iceberg's {@code
 * unknown}; it has no type for variant, geometry, geography or nanosecond timestamps.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FormatV3TypesSparkTest extends OpenHouseSparkITest {
  private static final String DB = "fv3_types";

  /** The OpenHouse catalog on the service with the switch on. */
  private static final String V3 = "v3types";

  private SparkSession spark;
  private FormatV3Server server;
  private Catalog onServer;

  @BeforeAll
  void start() throws Exception {
    spark = getSparkSession();
    server = new FormatV3Server();
    Map<String, String> properties = new HashMap<>();
    String defaults = "spark.sql.catalog.openhouse.";
    properties.put("uri", server.uri());
    properties.put("cluster", spark.conf().get(defaults + "cluster"));
    properties.put("auth-token", spark.conf().get(defaults + "auth-token"));
    String prefix = "spark.sql.catalog." + V3;
    spark.conf().set(prefix, spark.conf().get("spark.sql.catalog.openhouse"));
    spark.conf().set(prefix + ".catalog-impl", spark.conf().get(defaults + "catalog-impl"));
    properties.forEach((key, value) -> spark.conf().set(prefix + "." + key, value));
    onServer =
        CatalogUtil.loadCatalog(
            spark.conf().get(defaults + "catalog-impl"),
            V3,
            properties,
            spark.sparkContext().hadoopConfiguration());
  }

  @AfterAll
  void stop() {
    if (server != null) {
      for (TableIdentifier table : onServer.listTables(Namespace.of(DB))) {
        onServer.dropTable(table);
      }
      server.close();
    }
  }

  // ---------------------------------------------------------------- helpers

  private static String t(String table) {
    return V3 + "." + DB + "." + table;
  }

  private List<String> rows(String sql) {
    return spark.sql(sql).collectAsList().stream().map(Row::toString).collect(Collectors.toList());
  }

  private static Throwable thrown(Executable executable) {
    try {
      executable.execute();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected a failure");
  }

  private String failure(String sql) {
    return chain(thrown(() -> spark.sql(sql).collectAsList()));
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  private boolean exists(String table) {
    return onServer.tableExists(TableIdentifier.of(DB, table));
  }

  private void requireFormatV3() {
    Assumptions.assumeTrue(IcebergLine.current() == IcebergLine.V3);
  }

  // ---------------------------------------------------------------- tests

  /**
   * A {@code void} column (Iceberg's {@code unknown}) on a v3 table is refused by the service, by
   * CREATE TABLE, CTAS and ADD COLUMN, naming work item X5, and leaves nothing behind.
   */
  @Test
  void aVoidColumnOnAV3TableIsRefusedNamingX5() throws Exception {
    requireFormatV3();
    String create =
        failure(
            "CREATE TABLE "
                + t("void_create")
                + " (id bigint, c void) TBLPROPERTIES ('format-version'='3')");
    String ctas =
        failure(
            "CREATE TABLE "
                + t("void_ctas")
                + " TBLPROPERTIES ('format-version'='3') AS SELECT 1L AS id, NULL AS c");
    spark.sql(
        "CREATE TABLE " + t("void_add") + " (id bigint) TBLPROPERTIES ('format-version'='3')");
    String add = failure("ALTER TABLE " + t("void_add") + " ADD COLUMN c void");
    for (String message : new String[] {create, ctas, add}) {
      Assertions.assertTrue(
          message.contains("400")
              && message.contains("FORMAT_V3_TYPE_NOT_SUPPORTED: column c (unknown)")
              && message.contains("work item X5"),
          message);
    }
    Assertions.assertFalse(exists("void_create"));
    Assertions.assertFalse(exists("void_ctas"));
    Assertions.assertEquals(
        java.util.Collections.singletonList("[id,bigint,null]"), rows("DESCRIBE " + t("void_add")));
  }

  /**
   * On a v2 table a {@code void} column is refused by the client's own Iceberg before any request,
   * as it always was; on the reference line too.
   */
  @Test
  void aVoidColumnOnAV2TableIsRefusedByTheClientsIceberg() throws Exception {
    spark.sql("CREATE TABLE " + t("void_v2") + " (id bigint)");
    String add = failure("ALTER TABLE " + t("void_v2") + " ADD COLUMN c void");
    String create = failure("CREATE TABLE " + t("void_v2c") + " (id bigint, c void)");
    for (String message : new String[] {add, create}) {
      Assertions.assertTrue(
          message.startsWith("java.lang.IllegalStateException: Invalid schema for v2:")
              || message.contains("Unsupported type")
              || message.contains("void"),
          message);
    }
    Assertions.assertFalse(exists("void_v2c"));
    Assertions.assertEquals(
        java.util.Collections.singletonList("[id,bigint,null]"), rows("DESCRIBE " + t("void_v2")));
  }

  /** Spark 3.5 has no variant type: a statement that names one does not parse. */
  @Test
  void sparkThreeFiveCannotDeclareAVariantColumn() {
    String variant =
        failure(
            "CREATE TABLE "
                + t("variant")
                + " (id bigint, v variant) TBLPROPERTIES ('format-version'='3')");
    Assertions.assertTrue(
        variant.startsWith("org.apache.spark.sql.catalyst.parser.ParseException"), variant);
  }
}
