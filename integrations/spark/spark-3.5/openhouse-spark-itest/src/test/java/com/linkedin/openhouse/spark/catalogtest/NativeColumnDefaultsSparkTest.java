package com.linkedin.openhouse.spark.catalogtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Native Iceberg column defaults (format v3 {@code initial-default} and {@code write-default})
 * through Spark 3.5 SQL with the OpenHouse catalog (the native API), against a tables service with
 * the format v3 switch on (work items W7 and X4).
 *
 * <p>The defaulted columns are added through the Iceberg API, after rows were written: Spark SQL's
 * {@code ADD COLUMN ... DEFAULT} is refused by Iceberg's Spark 3.5 integration ({@link
 * #sqlDefaultClausesAreRefused}). Rows written before a column existed must read its {@code
 * initial-default}, in every way a query can touch them: projection, filter (pushed down to the
 * scan), aggregate, and after a later schema change or a row-level DELETE or UPDATE on the old
 * files. Parquet and ORC, each with the row and the vectorized reader.
 *
 * <p>The Iceberg API with a default value ({@code addColumn(..., Literal)}, {@code
 * updateColumnDefault}) does not exist on LinkedIn's Iceberg 1.5.2 reference line, which also has
 * no format v3, so this class calls it by reflection and skips every v3 test there.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NativeColumnDefaultsSparkTest extends OpenHouseSparkITest {
  private static final String DB = "native_defaults";

  /** The OpenHouse catalog on the service with the switch on; not shared with other classes. */
  private static final String V3 = "v3defaults";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 2024-01-02T03:04:05Z, the timestamp column's default, in microseconds. */
  private static final long TS_DEFAULT_MICROS = 1704164645000000L;

  private static final long TS_EXPLICIT_MICROS =
      Instant.parse("2025-05-06T00:00:00Z").toEpochMilli() * 1000L;

  /** The defaulted columns, as one query selects them. */
  private static final String SELECT_ALL =
      "SELECT id, c_int, c_long, c_str, c_bool, CAST(c_dec AS STRING), CAST(c_date AS STRING),"
          + " unix_micros(c_ts), s.a, s.b FROM %s ORDER BY id";

  private static final String OLD_ROW_DEFAULTS =
      "7,7000000000,US,true,12.34,2024-01-02," + TS_DEFAULT_MICROS;

  private SparkSession spark;
  private FormatV3Server server;
  private Catalog onServer;

  @BeforeAll
  void start() throws Exception {
    spark = getSparkSession();
    if (IcebergLine.current() != IcebergLine.V3) {
      return;
    }
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

  private void requireFormatV3() {
    Assumptions.assumeTrue(IcebergLine.current() == IcebergLine.V3);
  }

  private List<String> rows(String sql) {
    return spark.sql(sql).collectAsList().stream().map(Row::toString).collect(Collectors.toList());
  }

  private List<String> rows(String format, String table) {
    return rows(String.format(format, t(table)));
  }

  private void assertIds(String table, String predicate, long... ids) {
    List<String> expected =
        Arrays.stream(ids).mapToObj(id -> "[" + id + "]").collect(Collectors.toList());
    Assertions.assertEquals(
        expected,
        rows("SELECT id FROM " + t(table) + " WHERE " + predicate + " ORDER BY id"),
        "filter: " + predicate);
  }

  /** The predicate reached Iceberg's scan (so file and row-group pruning saw it). */
  private void assertPushedDown(String table, String predicate, String asPushed) {
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
  private void assertParquetReadIsColumnarWhenVectorized(
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

  private Table table(String table) throws Exception {
    spark.sql("REFRESH TABLE " + t(table));
    return Spark3Util.loadIcebergTable(spark, t(table));
  }

  /** The stored metadata file's field of that name in the current schema, as JSON. */
  private JsonNode storedField(String table, String name) throws Exception {
    String location =
        ((HasTableOperations) onServer.loadTable(TableIdentifier.of(DB, table)))
            .operations()
            .current()
            .metadataFileLocation();
    JsonNode stored =
        MAPPER.readTree(Files.readAllBytes(Paths.get(java.net.URI.create(location).getPath())));
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
   * {@code UpdateSchema.addColumn(parent, name, type, doc, default)}: the Iceberg 1.12 API that
   * sets both defaults of a new column. By reflection: the reference line has no such method.
   */
  private static UpdateSchema addColumn(
      UpdateSchema update, String parent, String name, Type type, Literal<?> value)
      throws Exception {
    Method method =
        UpdateSchema.class.getMethod(
            "addColumn", String.class, String.class, Type.class, String.class, Literal.class);
    return (UpdateSchema) invoke(method, update, parent, name, type, null, value);
  }

  /** {@code UpdateSchema.updateColumnDefault(name, default)}: changes the write-default only. */
  private static UpdateSchema updateColumnDefault(
      UpdateSchema update, String name, Literal<?> value) throws Exception {
    Method method =
        UpdateSchema.class.getMethod("updateColumnDefault", String.class, Literal.class);
    return (UpdateSchema) invoke(method, update, name, value);
  }

  private static Object invoke(Method method, Object target, Object... args) throws Exception {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }

  /**
   * A v3 table in {@code format}, with two rows written, then seven primitive columns and a struct
   * field added with defaults through the Iceberg API, then a row with explicit values and a row
   * with explicit NULLs.
   */
  private void createDefaultedTable(String table, String format, boolean vectorized, String more)
      throws Exception {
    spark.sql(
        String.format(
            "CREATE TABLE %s (id bigint, name string, s struct<a:int>) TBLPROPERTIES ("
                + "'format-version'='3', 'write.format.default'='%s',"
                + " 'read.parquet.vectorization.enabled'='%b',"
                + " 'read.orc.vectorization.enabled'='%b'%s)",
            t(table), format, vectorized, vectorized, more));
    spark.sql(
        "INSERT INTO "
            + t(table)
            + " VALUES (1, 'a', named_struct('a', 1)), (2, 'b', named_struct('a', 2))");

    UpdateSchema update = table(table).updateSchema();
    addColumn(update, null, "c_int", Types.IntegerType.get(), Literal.of(7));
    addColumn(update, null, "c_long", Types.LongType.get(), Literal.of(7000000000L));
    addColumn(update, null, "c_str", Types.StringType.get(), Literal.of("US"));
    addColumn(update, null, "c_bool", Types.BooleanType.get(), Literal.of(true));
    addColumn(
        update, null, "c_dec", Types.DecimalType.of(10, 2), Literal.of(new BigDecimal("12.34")));
    addColumn(
        update,
        null,
        "c_date",
        Types.DateType.get(),
        Literal.of("2024-01-02").to(Types.DateType.get()));
    addColumn(
        update,
        null,
        "c_ts",
        Types.TimestampType.withZone(),
        Literal.of("2024-01-02T03:04:05+00:00").to(Types.TimestampType.withZone()));
    addColumn(update, "s", "b", Types.IntegerType.get(), Literal.of(5));
    update.commit();
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
    requireFormatV3();
    String table = "reads_" + format + "_" + vectorized;
    createDefaultedTable(table, format, vectorized, "");

    // The service stored both defaults as committed.
    JsonNode cStr = storedField(table, "c_str");
    Assertions.assertEquals("US", cStr.get("initial-default").asText(), cStr.toString());
    Assertions.assertEquals("US", cStr.get("write-default").asText(), cStr.toString());

    Assertions.assertEquals(allRowsAsWritten(), rows(SELECT_ALL, table));

    // Filters, each on a column the old file does not have.
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
    // Only the defaulted column.
    Assertions.assertEquals(
        Arrays.asList("[CA]", "[US]", "[US]", "[null]"),
        rows("SELECT c_str FROM %s ORDER BY c_str NULLS LAST", table));
    Assertions.assertEquals(
        Arrays.asList("[US]", "[US]"), rows("SELECT c_str FROM %s WHERE c_str = 'US'", table));
    Assertions.assertEquals(
        Arrays.asList("[5]", "[5]"), rows("SELECT s.b FROM %s WHERE s.b = 5", table));

    // Aggregates, including those Iceberg can answer from file metrics.
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
    requireFormatV3();
    String table = "evolve_" + format + "_" + vectorized;
    createDefaultedTable(table, format, vectorized, "");

    spark.sql("ALTER TABLE " + t(table) + " ALTER COLUMN c_int TYPE bigint");
    spark.sql("ALTER TABLE " + t(table) + " ALTER COLUMN c_str COMMENT 'country code'");
    spark.sql("ALTER TABLE " + t(table) + " ADD COLUMN later string");
    UpdateSchema update = table(table).updateSchema();
    addColumn(update, null, "c_late", Types.IntegerType.get(), Literal.of(9));
    update.commit();
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
   * A row-level DELETE and UPDATE that touch the old files, in copy-on-write (the old file is
   * rewritten with the values read, defaults included) and merge-on-read (deletion vectors on the
   * old file): the rows left still read the defaults, and the DELETE's own predicate on the
   * defaulted column sees the default.
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
    requireFormatV3();
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
   * Spark 3.5 does not see Iceberg write-defaults (its Iceberg integration gives Spark no column
   * default): an INSERT whose column list omits a defaulted column is refused by Spark, and the
   * DEFAULT keyword writes NULL, even after the write-default is changed. Old rows keep the
   * initial-default either way. Spark 4.2 differs (SparkIrcNativeDefaultsTest).
   */
  @Test
  void spark35WritesNoWriteDefault() throws Exception {
    requireFormatV3();
    String table = "writes";
    createDefaultedTable(table, "parquet", true, "");

    Throwable omitted =
        thrown(
            () ->
                spark.sql(
                    "INSERT INTO "
                        + t(table)
                        + " (id, name, s, c_long, c_str, c_bool, c_dec, c_date, c_ts) VALUES"
                        + " (5, 'e', named_struct('a', 5, 'b', 50), 1, 'MX', true, 1.00,"
                        + " DATE '2025-01-01', TIMESTAMP '2025-01-01 00:00:00')"));
    Assertions.assertTrue(
        chain(omitted).contains("Cannot find data for the output column `c_int`"), chain(omitted));

    spark.sql(
        "INSERT INTO "
            + t(table)
            + " VALUES (5, 'e', named_struct('a', 5, 'b', 50), DEFAULT, 1, DEFAULT, true,"
            + " 1.00, DATE '2025-01-01', TIMESTAMP '2025-01-01 00:00:00')");
    UpdateSchema update = table(table).updateSchema();
    updateColumnDefault(update, "c_str", Literal.of("XX"));
    update.commit();
    spark.sql("REFRESH TABLE " + t(table));
    spark.sql(
        "INSERT INTO "
            + t(table)
            + " VALUES (6, 'f', named_struct('a', 6, 'b', 60), 6, 1, DEFAULT, true,"
            + " 1.00, DATE '2025-01-01', TIMESTAMP '2025-01-01 00:00:00')");

    Assertions.assertEquals(
        Arrays.asList(
            "[1,7,US]", "[2,7,US]", "[3,8,CA]", "[4,null,null]", "[5,null,null]", "[6,6,null]"),
        rows("SELECT id, c_int, c_str FROM %s ORDER BY id", table));
    JsonNode cStr = storedField(table, "c_str");
    Assertions.assertEquals("US", cStr.get("initial-default").asText(), cStr.toString());
    Assertions.assertEquals("XX", cStr.get("write-default").asText(), cStr.toString());
  }

  // ---------------------------------------------------------------- refusals

  /**
   * Spark SQL cannot set a default on this line: the DEFAULT clause of ADD COLUMN is refused by
   * Iceberg's Spark 3.5 integration before any request, on a v3 table as on a v2 one.
   */
  @Test
  void sqlDefaultClausesAreRefused() throws Exception {
    requireFormatV3();
    spark.sql("CREATE TABLE " + t("sql_v3") + " (id bigint) TBLPROPERTIES ('format-version'='3')");
    spark.sql("CREATE TABLE " + t("sql_v2") + " (id bigint)");
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
   * On a v2 table native defaults stay refused as before: Iceberg itself refuses a non-null
   * initial-default below v3 in the client, and the service refuses a write-default with {@code
   * COLUMN_DEFAULT_REQUIRES_V3}. A column added without a default reads NULL for old rows.
   */
  @Test
  void onAV2TableNativeDefaultsAreRefusedAsBefore() throws Exception {
    requireFormatV3();
    String table = "v2_table";
    spark.sql("CREATE TABLE " + t(table) + " (id bigint, name string)");
    spark.sql("INSERT INTO " + t(table) + " VALUES (1, 'a')");

    Throwable initial =
        thrown(
            () -> {
              UpdateSchema update = table(table).updateSchema();
              addColumn(update, null, "c_str", Types.StringType.get(), Literal.of("US"));
              update.commit();
            });
    Assertions.assertTrue(
        chain(initial).contains("non-null default (US) is not supported until v3"), chain(initial));

    spark.sql("ALTER TABLE " + t(table) + " ADD COLUMN c_str string");
    Throwable write =
        thrown(
            () -> {
              UpdateSchema update = table(table).updateSchema();
              updateColumnDefault(update, "c_str", Literal.of("US"));
              update.commit();
            });
    Assertions.assertTrue(chain(write).contains("COLUMN_DEFAULT_REQUIRES_V3"), chain(write));

    Assertions.assertEquals(
        Collections.singletonList("[1,a,null]"), rows("SELECT * FROM %s ORDER BY id", table));
    Assertions.assertFalse(storedField(table, "c_str").has("initial-default"));
    Assertions.assertFalse(storedField(table, "c_str").has("write-default"));
  }
}
