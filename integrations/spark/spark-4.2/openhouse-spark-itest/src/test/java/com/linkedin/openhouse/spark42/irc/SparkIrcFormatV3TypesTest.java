package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.failure;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.row;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The column types format v3 adds (work item X5), through Spark 4.2 SQL on Iceberg 1.12 over the
 * Iceberg REST routes, with no OpenHouse class in the engine, against a tables service with the
 * cluster switch on (catalog {@code ircv3t}). Spark 4.2 can spell three of them: {@code variant},
 * {@code void} (Iceberg's {@code unknown}) and, behind Spark's {@code
 * spark.sql.geospatial.enabled}, {@code geometry} and {@code geography}; it has no nanosecond
 * timestamp type.
 */
public class SparkIrcFormatV3TypesTest {
  private static final String DB = "irc42";
  private static final List<String> TABLES =
      Arrays.asList("x5_void_create", "x5_void_ctas", "x5_void_add", "x5_geo", "x5_nanos");

  @BeforeAll
  static void addTheCatalog() throws Exception {
    IrcReadTestServiceProcess service = IrcReadTestServiceProcess.getWithFormatV3();
    SparkSession spark = IrcSpark.session();
    String prefix = "spark.sql.catalog.ircv3t";
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", service.uri() + "/iceberg");
    spark.conf().set(prefix + ".token", service.token(IrcSpark.READER));
  }

  @AfterAll
  static void dropTheTables() throws Exception {
    for (String table : TABLES) {
      rows("DROP TABLE IF EXISTS " + t(table));
    }
  }

  private static String t(String table) {
    return "ircv3t." + DB + "." + table;
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  private static boolean exists(String table) throws Exception {
    return rows("SHOW TABLES IN ircv3t." + DB).stream().anyMatch(r -> table.equals(r.get(1)));
  }

  private static void refusedNamingX5(String message, String column) {
    Assertions.assertTrue(
        message.contains("FORMAT_V3_TYPE_NOT_SUPPORTED: column " + column)
            && message.contains("work item X5"),
        message);
  }

  @Test
  void aVoidColumnOnAV3TableIsRefusedNamingX5() throws Exception {
    String create =
        chain(
            failure(
                "CREATE TABLE "
                    + t("x5_void_create")
                    + " (id bigint, c void) USING iceberg TBLPROPERTIES ('format-version'='3')"));
    String ctas =
        chain(
            failure(
                "CREATE TABLE "
                    + t("x5_void_ctas")
                    + " USING iceberg TBLPROPERTIES ('format-version'='3')"
                    + " AS SELECT 1L AS id, NULL AS c"));
    rows(
        "CREATE TABLE "
            + t("x5_void_add")
            + " (id bigint) USING iceberg TBLPROPERTIES ('format-version'='3')");
    String add = chain(failure("ALTER TABLE " + t("x5_void_add") + " ADD COLUMN c void"));
    for (String message : new String[] {create, ctas, add}) {
      refusedNamingX5(message, "c (unknown)");
    }
    Assertions.assertFalse(exists("x5_void_create"));
    Assertions.assertFalse(exists("x5_void_ctas"));
    Assertions.assertEquals(
        Collections.singletonList(row("id", "bigint", null)), rows("DESCRIBE " + t("x5_void_add")));
  }

  @Test
  void geometryAndGeographyColumnsOnAV3TableAreRefusedNamingX5() throws Exception {
    SparkSession spark = IrcSpark.session();
    spark.conf().set("spark.sql.geospatial.enabled", "true");
    try {
      String geometry =
          chain(
              failure(
                  "CREATE TABLE "
                      + t("x5_geo")
                      + " (id bigint, g GEOMETRY(4326)) USING iceberg"
                      + " TBLPROPERTIES ('format-version'='3')"));
      refusedNamingX5(geometry, "g (geometry");
      String geography =
          chain(
              failure(
                  "CREATE TABLE "
                      + t("x5_geo")
                      + " (id bigint, g GEOGRAPHY(4326)) USING iceberg"
                      + " TBLPROPERTIES ('format-version'='3')"));
      refusedNamingX5(geography, "g (geography");
      Assertions.assertFalse(exists("x5_geo"));
    } finally {
      spark.conf().unset("spark.sql.geospatial.enabled");
    }
  }

  /**
   * Spark has no nanosecond timestamp: TIMESTAMP_NTZ is Iceberg's microsecond {@code timestamp}.
   */
  @Test
  void sparkHasNoNanosecondTimestamp() throws Exception {
    rows(
        "CREATE TABLE "
            + t("x5_nanos")
            + " (id bigint, ts timestamp_ntz, tz timestamp) USING iceberg"
            + " TBLPROPERTIES ('format-version'='3')");
    Assertions.assertEquals(
        Arrays.asList(
            row("id", "bigint", null),
            row("ts", "timestamp_ntz", null),
            row("tz", "timestamp", null)),
        rows("DESCRIBE " + t("x5_nanos")));
  }
}
