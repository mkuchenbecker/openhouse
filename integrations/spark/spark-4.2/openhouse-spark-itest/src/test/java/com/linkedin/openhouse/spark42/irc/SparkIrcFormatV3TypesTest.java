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
 * cluster switch on and variant columns allowed ({@code cluster.iceberg.format-v3.variant.enabled},
 * catalog {@code ircv3t}; SparkIrcFormatV3Test has the default, variant off). Spark 4.2 can spell
 * three of them: {@code variant}, {@code void} (Iceberg's {@code unknown}) and, behind Spark's
 * {@code spark.sql.geospatial.enabled}, {@code geometry} and {@code geography}; it has no
 * nanosecond timestamp type.
 */
public class SparkIrcFormatV3TypesTest {
  private static final String DB = "irc42";
  private static final List<String> TABLES =
      Arrays.asList("x5_void_create", "x5_void_ctas", "x5_void_add", "x5_geo", "x5_nanos");

  @BeforeAll
  static void addTheCatalog() throws Exception {
    IrcReadTestServiceProcess service = IrcReadTestServiceProcess.getWithFormatV3AndVariant();
    SparkSession spark = IrcSpark.session();
    String prefix = "spark.sql.catalog.ircv3t";
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", service.uri() + "/iceberg");
    spark.conf().set(prefix + ".token", service.token(IrcSpark.READER));
    ServerPlanning.notApplied(prefix, ServerPlanning.FORMAT_V3);
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

  private static String v3(String format) {
    return " USING iceberg TBLPROPERTIES ('format-version'='3'"
        + (format == null ? "" : ", 'write.format.default'='" + format + "'")
        + ")";
  }

  /**
   * A {@code void} column (Iceberg's {@code unknown}) on a v3 table: CREATE TABLE, CTAS and ADD
   * COLUMN take it, whatever the file format (ORC, the cluster's default, Parquet, Avro); rows read
   * it as null and filter on it.
   */
  @Test
  void aVoidColumnOnAV3TableIsWrittenAndReadInEveryFormat() throws Exception {
    for (String format : new String[] {null, "parquet", "avro"}) {
      String table = "x5_void_" + (format == null ? "default" : format);
      rows("CREATE TABLE " + t(table) + " (id bigint, c void)" + v3(format));
      rows("INSERT INTO " + t(table) + " VALUES (1, NULL), (2, NULL)");
      Assertions.assertEquals(
          Arrays.asList(row(1L, null), row(2L, null)),
          rows("SELECT * FROM " + t(table) + " ORDER BY id"),
          table);
      Assertions.assertEquals(
          Arrays.asList(row(1L), row(2L)),
          rows("SELECT id FROM " + t(table) + " WHERE c IS NULL ORDER BY id"),
          table);
      Assertions.assertEquals(
          Collections.emptyList(),
          rows("SELECT id FROM " + t(table) + " WHERE c IS NOT NULL"),
          table);
      Assertions.assertEquals(
          Arrays.asList(row("id", "bigint", null), row("c", "void", null)),
          rows("DESCRIBE " + t(table)),
          table);
    }
    rows("CREATE TABLE " + t("x5_void_ctas") + v3(null) + " AS SELECT 1L AS id, NULL AS c");
    Assertions.assertEquals(
        Collections.singletonList(row(1L, null)), rows("SELECT * FROM " + t("x5_void_ctas")));
    rows("CREATE TABLE " + t("x5_void_add") + " (id bigint)" + v3(null));
    rows("INSERT INTO " + t("x5_void_add") + " VALUES (1)");
    rows("ALTER TABLE " + t("x5_void_add") + " ADD COLUMN c void");
    rows("INSERT INTO " + t("x5_void_add") + " VALUES (2, NULL)");
    Assertions.assertEquals(
        Arrays.asList(row(1L, null), row(2L, null)),
        rows("SELECT * FROM " + t("x5_void_add") + " ORDER BY id"));
    // Not a partition column: Iceberg's Spark integration refuses it before any request.
    String partitioned =
        chain(
            failure(
                "CREATE TABLE "
                    + t("x5_void_part")
                    + " (id bigint, c void) USING iceberg PARTITIONED BY (c)"
                    + " TBLPROPERTIES ('format-version'='3')"));
    Assertions.assertFalse(exists("x5_void_part"), partitioned);
  }

  /**
   * A variant column on a v3 table that writes Parquet: CREATE TABLE, CTAS and ADD COLUMN take it;
   * rows written with {@code parse_json} read back, filter by {@code variant_get}, and survive a
   * DELETE. On a v3 table that writes ORC (the cluster's default) the service refuses the column,
   * by CREATE TABLE, ADD COLUMN and a later switch to ORC, saying what to set.
   */
  @Test
  void aVariantColumnOnAV3TableThatWritesParquet() throws Exception {
    String table = "x5_variant";
    rows("CREATE TABLE " + t(table) + " (id bigint, v variant)" + v3("parquet"));
    rows(
        "INSERT INTO "
            + t(table)
            + " SELECT 1L, parse_json('{\"a\":1,\"b\":\"x\"}')"
            + " UNION ALL SELECT 2L, parse_json('[1,2]')"
            + " UNION ALL SELECT 3L, CAST(NULL AS VARIANT)");
    Assertions.assertEquals(
        Arrays.asList(row(1L, "{\"a\":1,\"b\":\"x\"}"), row(2L, "[1,2]"), row(3L, null)),
        rows("SELECT id, to_json(v) FROM " + t(table) + " ORDER BY id"));
    Assertions.assertEquals(
        Collections.singletonList(row(1L)),
        rows("SELECT id FROM " + t(table) + " WHERE variant_get(v, '$.a', 'int') = 1"));
    Assertions.assertEquals(
        Collections.singletonList(row(3L)),
        rows("SELECT id FROM " + t(table) + " WHERE v IS NULL"));
    rows("ALTER TABLE " + t(table) + " ADD COLUMN w variant");
    rows("INSERT INTO " + t(table) + " SELECT 4L, parse_json('1'), parse_json('\"w\"')");
    Assertions.assertEquals(
        Arrays.asList(row(1L, null), row(4L, "\"w\"")),
        rows("SELECT id, to_json(w) FROM " + t(table) + " WHERE id IN (1, 4) ORDER BY id"));
    rows("DELETE FROM " + t(table) + " WHERE id = 2");
    Assertions.assertEquals(
        Arrays.asList(row(1L), row(3L), row(4L)),
        rows("SELECT id FROM " + t(table) + " ORDER BY id"));
    rows("CREATE TABLE " + t("x5_variant_ctas") + v3("parquet") + " AS SELECT * FROM " + t(table));
    Assertions.assertEquals(
        rows("SELECT id, to_json(v), to_json(w) FROM " + t(table) + " ORDER BY id"),
        rows("SELECT id, to_json(v), to_json(w) FROM " + t("x5_variant_ctas") + " ORDER BY id"));

    // ORC: refused by the service, naming the column and what to set.
    String onOrc =
        chain(
            failure("CREATE TABLE " + t("x5_variant_orc") + " (id bigint, v variant)" + v3(null)));
    refusedOnOrc(onOrc, "v");
    Assertions.assertFalse(exists("x5_variant_orc"));
    rows("CREATE TABLE " + t("x5_variant_orc") + " (id bigint)" + v3(null));
    refusedOnOrc(
        chain(failure("ALTER TABLE " + t("x5_variant_orc") + " ADD COLUMN v variant")), "v");
    refusedOnOrc(
        chain(
            failure(
                "ALTER TABLE " + t(table) + " SET TBLPROPERTIES ('write.format.default'='orc')")),
        "v");
    Assertions.assertEquals(
        Arrays.asList(row(1L), row(3L), row(4L)),
        rows("SELECT id FROM " + t(table) + " ORDER BY id"));
    // Not a partition column: Iceberg's Spark integration refuses it before any request.
    String partitioned =
        chain(
            failure(
                "CREATE TABLE "
                    + t("x5_variant_part")
                    + " (id bigint, v variant) USING iceberg PARTITIONED BY (v)"
                    + " TBLPROPERTIES ('format-version'='3', 'write.format.default'='parquet')"));
    Assertions.assertFalse(exists("x5_variant_part"), partitioned);
  }

  /** A variant column on a v3 table that writes Avro. */
  @Test
  void aVariantColumnOnAV3TableThatWritesAvro() throws Exception {
    String table = "x5_variant_avro";
    rows("CREATE TABLE " + t(table) + " (id bigint, v variant)" + v3("avro"));
    rows(
        "INSERT INTO "
            + t(table)
            + " SELECT 1L, parse_json('{\"a\":1}') UNION ALL SELECT 2L, CAST(NULL AS VARIANT)");
    Assertions.assertEquals(
        Arrays.asList(row(1L, "{\"a\":1}"), row(2L, null)),
        rows("SELECT id, to_json(v) FROM " + t(table) + " ORDER BY id"));
  }

  /**
   * A table that wrote ORC files and then switched to Parquet is refused a variant column while it
   * still has ORC data files: Iceberg's Spark ORC reader cannot read the column from them. Once its
   * data is rewritten (here by INSERT OVERWRITE) to Parquet, it takes the column.
   */
  @Test
  void aVariantColumnIsRefusedWhileTheTableHasOrcDataFiles() throws Exception {
    String table = "x5_variant_after_orc";
    rows("CREATE TABLE " + t(table) + " (id bigint)" + v3(null));
    rows("INSERT INTO " + t(table) + " VALUES (1)");
    rows("ALTER TABLE " + t(table) + " SET TBLPROPERTIES ('write.format.default'='parquet')");
    String refused = chain(failure("ALTER TABLE " + t(table) + " ADD COLUMN v variant"));
    Assertions.assertTrue(
        refused.contains("FORMAT_V3_TYPE_NOT_SUPPORTED: column v (variant)")
            && refused.contains("the table has ORC data files"),
        refused);
    Assertions.assertEquals(
        Collections.singletonList(row("id", "bigint", null)), rows("DESCRIBE " + t(table)));
    rows("INSERT OVERWRITE " + t(table) + " SELECT * FROM " + t(table));
    rows("ALTER TABLE " + t(table) + " ADD COLUMN v variant");
    rows("INSERT INTO " + t(table) + " SELECT 2L, parse_json('{\"a\":2}')");
    Assertions.assertEquals(
        Arrays.asList(row(1L, null), row(2L, "{\"a\":2}")),
        rows("SELECT id, to_json(v) FROM " + t(table) + " ORDER BY id"));
  }

  private static void refusedOnOrc(String message, String column) {
    Assertions.assertTrue(
        message.contains("FORMAT_V3_TYPE_NOT_SUPPORTED: column " + column + " (variant)")
            && message.contains("cannot write a variant column to ORC"),
        message);
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
