package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.SchemaParser;
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
 * The column types format v3 adds, through Spark 3.5 SQL with the OpenHouse catalog (the native
 * API), against a tables service with the cluster switch on. Of those types Spark 3.5 can spell
 * only one, {@code void}, which Iceberg's Spark integration maps to Iceberg's {@code unknown} and
 * the service takes; it has no type for variant, geometry, geography or nanosecond timestamps, and
 * cannot read a table with a variant column (which the service takes from Spark 4).
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

  private static String v3(String format) {
    return " TBLPROPERTIES ('format-version'='3'"
        + (format == null ? "" : ", 'write.format.default'='" + format + "'")
        + ")";
  }

  /**
   * A {@code void} column (Iceberg's {@code unknown}) on a v3 table: CREATE TABLE, CTAS and ADD
   * COLUMN take it, in ORC (the cluster's default) and Parquet; rows read it as null and filter on
   * it; it takes a column tag.
   */
  @Test
  void aVoidColumnOnAV3TableIsWrittenAndReadInEveryFormat() throws Exception {
    requireFormatV3();
    // Not Avro: this test JVM cannot write an Avro data file for any table (its Iceberg's shaded
    // Avro meets the unshaded one), which has nothing to do with the column's type.
    for (String format : new String[] {null, "parquet"}) {
      String table = "void_" + (format == null ? "default" : format);
      spark.sql("CREATE TABLE " + t(table) + " (id bigint, c void)" + v3(format));
      spark.sql("INSERT INTO " + t(table) + " VALUES (1, NULL), (2, NULL)");
      Assertions.assertEquals(
          java.util.Arrays.asList("[1,null]", "[2,null]"),
          rows("SELECT * FROM " + t(table) + " ORDER BY id"),
          table);
      Assertions.assertEquals(
          java.util.Arrays.asList("[1]", "[2]"),
          rows("SELECT id FROM " + t(table) + " WHERE c IS NULL ORDER BY id"),
          table);
      Assertions.assertEquals(
          java.util.Arrays.asList("[id,bigint,null]", "[c,void,null]"),
          rows("DESCRIBE " + t(table)),
          table);
    }
    spark.sql("CREATE TABLE " + t("void_ctas") + v3(null) + " AS SELECT 1L AS id, NULL AS c");
    Assertions.assertEquals(
        java.util.Collections.singletonList("[1,null]"), rows("SELECT * FROM " + t("void_ctas")));
    spark.sql("CREATE TABLE " + t("void_add") + " (id bigint)" + v3(null));
    spark.sql("INSERT INTO " + t("void_add") + " VALUES (1)");
    spark.sql("ALTER TABLE " + t("void_add") + " ADD COLUMN c void");
    spark.sql("INSERT INTO " + t("void_add") + " VALUES (2, NULL)");
    Assertions.assertEquals(
        java.util.Arrays.asList("[1,null]", "[2,null]"),
        rows("SELECT * FROM " + t("void_add") + " ORDER BY id"));
    spark.sql("ALTER TABLE " + t("void_add") + " MODIFY COLUMN c SET TAG = (PII)");
    String policies =
        onServer.loadTable(TableIdentifier.of(DB, "void_add")).properties().get("policies");
    Assertions.assertTrue(policies.contains("\"c\"") && policies.contains("PII"), policies);
  }

  /**
   * A table with a variant column, as Spark 4 creates one over the Iceberg REST routes (here
   * through the Iceberg API): Spark 3.5 cannot read it. Iceberg's Spark 3.5 integration has no
   * variant type, so the table fails to load into Spark. A Spark 3.5 limitation, not a refusal.
   */
  @Test
  void sparkThreeFiveCannotReadATableWithAVariantColumn() throws Exception {
    requireFormatV3();
    Map<String, String> properties = new HashMap<>();
    properties.put("format-version", "3");
    properties.put("write.format.default", "parquet");
    onServer.createTable(
        TableIdentifier.of(DB, "variant_table"),
        SchemaParser.fromJson(
            "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"name\":\"id\",\"required\":false,"
                + "\"type\":\"long\"},{\"id\":2,\"name\":\"v\",\"required\":false,"
                + "\"type\":\"variant\"}]}"),
        PartitionSpec.unpartitioned(),
        properties);
    Assertions.assertTrue(exists("variant_table"));
    for (String sql :
        new String[] {
          "SELECT * FROM " + t("variant_table"),
          "SELECT id FROM " + t("variant_table"),
          "DESCRIBE " + t("variant_table")
        }) {
      String failure = failure(sql);
      // Iceberg's TypeUtil.SchemaVisitor.variant default, reached from SparkTable.schema().
      Assertions.assertTrue(
          failure.startsWith("java.lang.UnsupportedOperationException: Unsupported type: variant"),
          failure);
    }
    // Listing does not load the table: SHOW TABLES still names it.
    Assertions.assertTrue(
        rows("SHOW TABLES IN " + V3 + "." + DB).stream().anyMatch(r -> r.contains("variant_table")),
        rows("SHOW TABLES IN " + V3 + "." + DB).toString());
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
