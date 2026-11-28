package com.linkedin.openhouse.spark42.irc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.Table;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Native Iceberg column defaults (format v3 {@code initial-default} and {@code write-default})
 * through Spark 4.2 SQL on Iceberg 1.12 over the Iceberg REST routes only, with no OpenHouse class
 * in the engine, against a tables service with the format v3 switch on.
 *
 * <p>The same scenarios as the Spark 3.5 test {@code NativeColumnDefaultsSparkTest}: defaulted
 * columns are added through the Iceberg API after rows were written (Spark SQL's {@code ADD COLUMN
 * ... DEFAULT} is refused by Iceberg's Spark 4.2 integration), and old rows must read each column's
 * {@code initial-default} in projections, pushed-down filters, aggregates, and after a later schema
 * change or a row-level DELETE or UPDATE on the old files; Parquet and ORC, row and vectorized.
 * Unlike Spark 3.5, Spark 4.2 writes the {@code write-default} for an omitted column and for the
 * DEFAULT keyword.
 */
public class SparkIrcNativeDefaultsTest {
  private static final String DB = "irc42";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final List<String> TABLES = new ArrayList<>();

  /** 2024-01-02T03:04:05Z, the timestamp column's default, in microseconds. */
  private static final long TS_DEFAULT_MICROS = 1704164645000000L;

  private static final long TS_EXPLICIT_MICROS =
      Instant.parse("2025-05-06T00:00:00Z").toEpochMilli() * 1000L;

  private static final String SELECT_ALL =
      "SELECT id, c_int, c_long, c_str, c_bool, CAST(c_dec AS STRING), CAST(c_date AS STRING),"
          + " unix_micros(c_ts), s.a, s.b FROM %s ORDER BY id";

  private static final String OLD_ROW_DEFAULTS =
      "7,7000000000,US,true,12.34,2024-01-02," + TS_DEFAULT_MICROS;

  private static SparkSession spark;

  @BeforeAll
  static void addTheCatalog() throws Exception {
    IrcReadTestServiceProcess service = IrcReadTestServiceProcess.getWithFormatV3();
    spark = IrcSpark.session();
    String prefix = "spark.sql.catalog.ircv3";
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", service.uri() + "/iceberg");
    spark.conf().set(prefix + ".token", service.token(IrcSpark.READER));
    ServerPlanning.notApplied(prefix, ServerPlanning.FORMAT_V3);
  }

  @AfterAll
  static void dropTheTables() {
    for (String table : TABLES) {
      spark.sql("DROP TABLE IF EXISTS " + t(table));
    }
  }

  // ---------------------------------------------------------------- helpers

  private static String t(String table) {
    return "ircv3." + DB + ".nd_" + table;
  }

  private static List<String> rows(String sql) {
    return spark.sql(sql).collectAsList().stream().map(Row::toString).collect(Collectors.toList());
  }

  private static List<String> rows(String format, String table) {
    return rows(String.format(format, t(table)));
  }

  private static void assertIds(String table, String predicate, long... ids) {
    List<String> expected =
        Arrays.stream(ids).mapToObj(id -> "[" + id + "]").collect(Collectors.toList());
    Assertions.assertEquals(
        expected,
        rows("SELECT id FROM " + t(table) + " WHERE " + predicate + " ORDER BY id"),
        "filter: " + predicate);
  }

  /** The predicate reached Iceberg's scan (so file and row-group pruning saw it). */
  private static void assertPushedDown(String table, String predicate, String asPushed) {
    String plan =
        spark
            .sql("SELECT id FROM " + t(table) + " WHERE " + predicate)
            .queryExecution()
            .executedPlan()
            .toString();
    Assertions.assertTrue(plan.contains(asPushed), plan);
  }

  /**
   * A Parquet scan of primitive columns, defaulted ones included, runs on the vectorized reader
   * when the table asks for it, and on the row reader otherwise (Spark's plan reads columnar
   * batches only from a vectorized scan).
   */
  private static void assertParquetReadIsColumnarWhenVectorized(
      String table, String format, boolean vectorized) {
    if (!"parquet".equals(format)) {
      return;
    }
    String plan =
        spark
            .sql("SELECT id, c_int, c_str FROM " + t(table) + " WHERE c_str = 'US'")
            .queryExecution()
            .executedPlan()
            .toString();
    Assertions.assertEquals(vectorized, plan.contains("ColumnarToRow"), plan);
  }

  private static Throwable thrown(Executable executable) {
    try {
      executable.execute();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected a failure");
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  private static Table table(String table) throws Exception {
    spark.sql("REFRESH TABLE " + t(table));
    return Spark3Util.loadIcebergTable(spark, t(table));
  }

  /** The field of that name in the current schema of the table's current metadata file. */
  private static JsonNode storedField(String table, String name) throws Exception {
    List<String> latest =
        rows(
            "SELECT file FROM "
                + t(table)
                + ".metadata_log_entries ORDER BY timestamp DESC LIMIT 1");
    String file = latest.get(0).substring(1, latest.get(0).length() - 1);
    JsonNode stored =
        MAPPER.readTree(
            Files.readAllBytes(
                Paths.get(file.startsWith("file:") ? java.net.URI.create(file).getPath() : file)));
    int current = stored.get("current-schema-id").asInt();
    for (JsonNode schema : stored.get("schemas")) {
      if (schema.get("schema-id").asInt() == current) {
        for (JsonNode field : schema.get("fields")) {
          if (name.equals(field.get("name").asText())) {
            return field;
          }
        }
      }
    }
    throw new AssertionError("no field " + name + " in " + stored);
  }

  /**
   * A v3 table in {@code format}, with two rows written, then seven primitive columns and a struct
   * field added with defaults through the Iceberg API, then a row with explicit values and a row
   * with explicit NULLs.
   */
  private static void createDefaultedTable(
      String table, String format, boolean vectorized, String more) throws Exception {
    TABLES.add(table);
    spark.sql("DROP TABLE IF EXISTS " + t(table));
    spark.sql(
        String.format(
            "CREATE TABLE %s (id bigint, name string, s struct<a:int>) USING iceberg"
                + " TBLPROPERTIES ('format-version'='3', 'write.format.default'='%s',"
                + " 'read.parquet.vectorization.enabled'='%b',"
                + " 'read.orc.vectorization.enabled'='%b'%s)",
            t(table), format, vectorized, vectorized, more));
    spark.sql(
        "INSERT INTO "
            + t(table)
            + " VALUES (1, 'a', named_struct('a', 1)), (2, 'b', named_struct('a', 2))");

    table(table)
        .updateSchema()
        .addColumn("c_int", Types.IntegerType.get(), Literal.of(7))
        .addColumn("c_long", Types.LongType.get(), Literal.of(7000000000L))
        .addColumn("c_str", Types.StringType.get(), Literal.of("US"))
        .addColumn("c_bool", Types.BooleanType.get(), Literal.of(true))
        .addColumn("c_dec", Types.DecimalType.of(10, 2), Literal.of(new BigDecimal("12.34")))
        .addColumn(
            "c_date", Types.DateType.get(), Literal.of("2024-01-02").to(Types.DateType.get()))
        .addColumn(
            "c_ts",
            Types.TimestampType.withZone(),
            Literal.of("2024-01-02T03:04:05+00:00").to(Types.TimestampType.withZone()))
        .addColumn("s", "b", Types.IntegerType.get(), Literal.of(5))
        .commit();
    spark.sql("REFRESH TABLE " + t(table));

    spark.sql(
        "INSERT INTO "
            + t(table)
            + " (id, name, s, c_int, c_long, c_str, c_bool, c_dec, c_date, c_ts) VALUES"
            + " (3, 'c', named_struct('a', 3, 'b', 30), 8, 8, 'CA', false, 1.50,"
            + " DATE '2025-05-06', TIMESTAMP '2025-05-06 00:00:00'),"
            + " (4, 'd', named_struct('a', 4, 'b', CAST(NULL AS int)), CAST(NULL AS int),"
            + " CAST(NULL AS bigint), CAST(NULL AS string), CAST(NULL AS boolean),"
            + " CAST(NULL AS decimal(10,2)), CAST(NULL AS date), CAST(NULL AS timestamp))");
  }

  private static List<String> allRowsAsWritten() {
    return Arrays.asList(
        "[1," + OLD_ROW_DEFAULTS + ",1,5]",
        "[2," + OLD_ROW_DEFAULTS + ",2,5]",
        "[3,8,8,CA,false,1.50,2025-05-06," + TS_EXPLICIT_MICROS + ",3,30]",
        "[4,null,null,null,null,null,null,null,4,null]");
  }

  // ---------------------------------------------------------------- reads

  /**
   * Old rows read each column's initial-default, in projections, filters (pushed down to the scan),
   * aggregates and a query that selects only the defaulted column; explicit values and explicit
   * NULLs written later read as written.
   */
  @ParameterizedTest(name = "{0}, vectorized={1}")
  @CsvSource({"parquet,false", "parquet,true", "orc,false", "orc,true"})
  void oldRowsReadEachColumnsInitialDefault(String format, boolean vectorized) throws Exception {
    String table = "reads_" + format + "_" + vectorized;
    createDefaultedTable(table, format, vectorized, "");

    JsonNode cStr = storedField(table, "c_str");
    Assertions.assertEquals("US", cStr.get("initial-default").asText(), cStr.toString());
    Assertions.assertEquals("US", cStr.get("write-default").asText(), cStr.toString());

    Assertions.assertEquals(allRowsAsWritten(), rows(SELECT_ALL, table));

    assertIds(table, "c_str = 'US'", 1, 2);
    assertIds(table, "c_str IN ('US', 'MX')", 1, 2);
    assertIds(table, "c_str <> 'US'", 3);
    assertIds(table, "upper(c_str) = 'US'", 1, 2);
    assertIds(table, "c_str IS NULL", 4);
    assertIds(table, "c_str IS NOT NULL", 1, 2, 3);
    assertIds(table, "c_int = 7", 1, 2);
    assertIds(table, "c_int < 8", 1, 2);
    assertIds(table, "c_int > 7", 3);
    assertIds(table, "c_long = 7000000000", 1, 2);
    assertIds(table, "c_bool = true", 1, 2);
    assertIds(table, "c_bool = false", 3);
    assertIds(table, "c_dec = 12.34", 1, 2);
    assertIds(table, "c_date = DATE '2024-01-02'", 1, 2);
    assertIds(table, "c_ts = TIMESTAMP '2024-01-02 03:04:05'", 1, 2);
    assertIds(table, "s.b = 5", 1, 2);
    assertIds(table, "s.b IS NULL", 4);
    assertPushedDown(table, "c_str = 'US'", "c_str = 'US'");
    assertPushedDown(table, "c_int = 7", "c_int = 7");

    assertParquetReadIsColumnarWhenVectorized(table, format, vectorized);
    Assertions.assertEquals(
        Arrays.asList("[CA]", "[US]", "[US]", "[null]"),
        rows("SELECT c_str FROM %s ORDER BY c_str NULLS LAST", table));
    Assertions.assertEquals(
        Arrays.asList("[US]", "[US]"), rows("SELECT c_str FROM %s WHERE c_str = 'US'", table));
    Assertions.assertEquals(
        Arrays.asList("[5]", "[5]"), rows("SELECT s.b FROM %s WHERE s.b = 5", table));

    Assertions.assertEquals(
        Collections.singletonList("[3,22,7,8,4]"),
        rows("SELECT count(c_int), sum(c_int), min(c_int), max(c_int), count(*) FROM %s", table));
    Assertions.assertEquals(
        Collections.singletonList("[CA,US,2024-01-02,2025-05-06]"),
        rows(
            "SELECT min(c_str), max(c_str), CAST(min(c_date) AS STRING),"
                + " CAST(max(c_date) AS STRING) FROM %s",
            table));
    Assertions.assertEquals(
        Arrays.asList("[CA,1]", "[US,2]", "[null,1]"),
        rows("SELECT c_str, count(*) FROM %s GROUP BY c_str ORDER BY c_str NULLS LAST", table));
    Assertions.assertEquals(
        Collections.singletonList("[2]"),
        rows("SELECT count(*) FROM %s WHERE c_str = 'US'", table));
  }

  /**
   * After a type widening, a comment change and new columns (one without a default, one with), old
   * rows still read the defaults, and a defaulted column added later fills every row written before
   * it. Renaming or dropping a column is refused by OpenHouse's schema-evolution rule on any table,
   * as before, and the refusal leaves the defaults as they were.
   */
  @ParameterizedTest(name = "{0}, vectorized={1}")
  @CsvSource({"parquet,false", "parquet,true", "orc,false", "orc,true"})
  void theDefaultsSurviveALaterSchemaChange(String format, boolean vectorized) throws Exception {
    String table = "evolve_" + format + "_" + vectorized;
    createDefaultedTable(table, format, vectorized, "");

    spark.sql("ALTER TABLE " + t(table) + " ALTER COLUMN c_int TYPE bigint");
    spark.sql("ALTER TABLE " + t(table) + " ALTER COLUMN c_str COMMENT 'country code'");
    spark.sql("ALTER TABLE " + t(table) + " ADD COLUMN later string");
    table(table)
        .updateSchema()
        .addColumn("c_late", Types.IntegerType.get(), Literal.of(9))
        .commit();
    spark.sql("REFRESH TABLE " + t(table));
    for (String refused : Arrays.asList("RENAME COLUMN c_str TO country", "DROP COLUMN c_bool")) {
      Throwable thrown = thrown(() -> spark.sql("ALTER TABLE " + t(table) + " " + refused));
      Assertions.assertTrue(
          chain(thrown).contains("invalid schema evolution"), refused + ": " + chain(thrown));
    }
    spark.sql("REFRESH TABLE " + t(table));

    Assertions.assertEquals(
        Arrays.asList(
            "[1,7,US,true,null,9,5]",
            "[2,7,US,true,null,9,5]",
            "[3,8,CA,false,null,9,30]",
            "[4,null,null,null,null,9,null]"),
        rows("SELECT id, c_int, c_str, c_bool, later, c_late, s.b FROM %s ORDER BY id", table));
    assertIds(table, "c_str = 'US'", 1, 2);
    assertIds(table, "c_int = 7", 1, 2);
    assertIds(table, "c_late = 9", 1, 2, 3, 4);
    Assertions.assertEquals(
        Collections.singletonList("[3,22,7,8]"),
        rows("SELECT count(c_int), sum(c_int), min(c_int), max(c_int) FROM %s", table));
    JsonNode cInt = storedField(table, "c_int");
    Assertions.assertEquals("long", cInt.get("type").asText(), cInt.toString());
    Assertions.assertEquals(7L, cInt.get("initial-default").asLong(), cInt.toString());
    JsonNode cStr = storedField(table, "c_str");
    Assertions.assertEquals("country code", cStr.get("doc").asText(), cStr.toString());
    Assertions.assertEquals("US", cStr.get("initial-default").asText(), cStr.toString());
  }

  /**
   * A row-level DELETE and UPDATE that touch the old files, in copy-on-write and merge-on-read: the
   * rows left still read the defaults, and the DELETE's own predicate on the defaulted column sees
   * the default.
   */
  @ParameterizedTest(name = "{0}, {1}")
  @CsvSource({
    "parquet,copy-on-write",
    "parquet,merge-on-read",
    "orc,copy-on-write",
    "orc,merge-on-read"
  })
  void theDefaultsSurviveRowLevelDeleteAndUpdateOnOldFiles(String format, String mode)
      throws Exception {
    String table = "rowlevel_" + format + "_" + mode.replace('-', '_');
    createDefaultedTable(
        table,
        format,
        false,
        String.format(
            ", 'write.delete.mode'='%s', 'write.update.mode'='%s', 'write.merge.mode'='%s'",
            mode, mode, mode));

    spark.sql("DELETE FROM " + t(table) + " WHERE c_str = 'US' AND id = 1");
    spark.sql("UPDATE " + t(table) + " SET name = 'bb' WHERE c_int = 7");

    Assertions.assertEquals(allRowsAsWritten().subList(1, 4), rows(SELECT_ALL, table));
    Assertions.assertEquals(
        Collections.singletonList("[2,bb]"),
        rows("SELECT id, name FROM %s WHERE c_str = 'US'", table));
    assertIds(table, "c_int = 7", 2);
    assertIds(table, "s.b = 5", 2);
    if ("merge-on-read".equals(mode)) {
      Assertions.assertFalse(rows("SELECT * FROM %s.delete_files", table).isEmpty());
    }
  }

  // ---------------------------------------------------------------- writes

  /**
   * Spark 4.2 writes the write-default: for a column an INSERT's column list omits and for the
   * DEFAULT keyword; after the write-default is changed, new rows get the new value while old rows
   * keep the initial-default; an explicit NULL stays NULL.
   */
  @Test
  void insertsGetTheWriteDefault() throws Exception {
    String table = "writes";
    createDefaultedTable(table, "parquet", true, "");

    spark.sql("INSERT INTO " + t(table) + " (id, name) VALUES (5, 'e')");
    spark.sql(
        "INSERT INTO "
            + t(table)
            + " VALUES (6, 'f', named_struct('a', 6, 'b', 60), DEFAULT, 1, DEFAULT, true,"
            + " 1.00, DATE '2025-01-01', TIMESTAMP '2025-01-01 00:00:00')");
    table(table).updateSchema().updateColumnDefault("c_str", Literal.of("XX")).commit();
    spark.sql("REFRESH TABLE " + t(table));
    spark.sql("INSERT INTO " + t(table) + " (id, name) VALUES (7, 'g')");
    spark.sql(
        "INSERT INTO "
            + t(table)
            + " (id, name, c_str) VALUES (8, 'h', DEFAULT), (9, 'i', CAST(NULL AS string))");

    Assertions.assertEquals(
        Arrays.asList(
            "[1,7,US,true]",
            "[2,7,US,true]",
            "[3,8,CA,false]",
            "[4,null,null,null]",
            "[5,7,US,true]",
            "[6,7,US,true]",
            "[7,7,XX,true]",
            "[8,7,XX,true]",
            "[9,7,null,true]"),
        rows("SELECT id, c_int, c_str, c_bool FROM %s ORDER BY id", table));
    assertIds(table, "c_str = 'US'", 1, 2, 5, 6);
    assertIds(table, "c_str = 'XX'", 7, 8);
    JsonNode cStr = storedField(table, "c_str");
    Assertions.assertEquals("US", cStr.get("initial-default").asText(), cStr.toString());
    Assertions.assertEquals("XX", cStr.get("write-default").asText(), cStr.toString());
  }

  // ---------------------------------------------------------------- refusals

  /**
   * Spark SQL cannot set a default: the DEFAULT clause of ADD COLUMN is refused by Iceberg's Spark
   * 4.2 integration before any request, on a v3 table as on a v2 one.
   */
  @Test
  void sqlDefaultClausesAreRefused() throws Exception {
    TABLES.addAll(Arrays.asList("sql_v3", "sql_v2"));
    spark.sql("DROP TABLE IF EXISTS " + t("sql_v3"));
    spark.sql("DROP TABLE IF EXISTS " + t("sql_v2"));
    spark.sql(
        "CREATE TABLE "
            + t("sql_v3")
            + " (id bigint) USING iceberg TBLPROPERTIES ('format-version'='3')");
    spark.sql("CREATE TABLE " + t("sql_v2") + " (id bigint) USING iceberg");
    for (String table : Arrays.asList("sql_v3", "sql_v2")) {
      Throwable refused =
          thrown(() -> spark.sql("ALTER TABLE " + t(table) + " ADD COLUMN c int DEFAULT 7"));
      Assertions.assertTrue(
          chain(refused).contains("setting default values in Spark is currently unsupported"),
          chain(refused));
      Assertions.assertNull(table(table).schema().findField("c"));
    }
  }

  /**
   * On a v2 table native defaults stay refused as before, over IRC too: Iceberg refuses a non-null
   * initial-default below v3 in the client, and the service refuses a write-default with {@code
   * COLUMN_DEFAULT_REQUIRES_V3}. A column added without a default reads NULL for old rows.
   */
  @Test
  void onAV2TableNativeDefaultsAreRefusedAsBefore() throws Exception {
    String table = "v2_table";
    TABLES.add(table);
    spark.sql("DROP TABLE IF EXISTS " + t(table));
    spark.sql("CREATE TABLE " + t(table) + " (id bigint, name string) USING iceberg");
    spark.sql("INSERT INTO " + t(table) + " VALUES (1, 'a')");

    Throwable initial =
        thrown(
            () ->
                table(table)
                    .updateSchema()
                    .addColumn("c_str", Types.StringType.get(), Literal.of("US"))
                    .commit());
    Assertions.assertTrue(
        chain(initial).contains("non-null default (US) is not supported until v3"), chain(initial));

    spark.sql("ALTER TABLE " + t(table) + " ADD COLUMN c_str string");
    Throwable write =
        thrown(
            () ->
                table(table)
                    .updateSchema()
                    .updateColumnDefault("c_str", Literal.of("US"))
                    .commit());
    Assertions.assertTrue(chain(write).contains("COLUMN_DEFAULT_REQUIRES_V3"), chain(write));

    spark.sql("REFRESH TABLE " + t(table));
    Assertions.assertEquals(
        Collections.singletonList("[1,a,null]"), rows("SELECT * FROM %s ORDER BY id", table));
    Assertions.assertFalse(storedField(table, "c_str").has("initial-default"));
    Assertions.assertFalse(storedField(table, "c_str").has("write-default"));
  }
}
