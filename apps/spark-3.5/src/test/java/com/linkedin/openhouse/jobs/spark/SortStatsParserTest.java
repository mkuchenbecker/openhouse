package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.iceberg.spark.ExtendedParser;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.parser.ParserInterface;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Work item X11: what sort stats collection's {@code rewrite_data_files} call needs from the
 * session's SQL parser, on the session the apps tests use, compared with the session {@code
 * jobs.yaml} gives a production job.
 *
 * <p>{@code rewrite_data_files} with {@code sort_order} parses the order through Iceberg's {@code
 * ExtendedParser.parseSortOrder(spark, order)} (RewriteDataFilesProcedure.checkAndApplyStrategy).
 * Spark chains injected parsers in the order the extensions are listed, so with Iceberg's
 * extensions first and OpenHouse's second (jobs.yaml, and every test session) the session's parser
 * is OpenHouse's, which delegates to Iceberg's. Iceberg 1.12 walks the delegates to find its
 * parser; LinkedIn's Iceberg 1.5.2 accepts only the outermost one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SortStatsParserTest extends OpenHouseSparkITest {
  private static final String DB = "x11sort";
  /** The order SortStatsCollectionSparkApp passes to rewrite_data_files. */
  static final String SORT_ORDER = "header.memberId asc nulls last, header.time asc nulls last";
  /** The deployment configuration of the jobs in this repository (the docker-compose recipe). */
  private static final Path JOBS_YAML =
      Paths.get("../../infra/recipes/docker-compose/oh-hadoop-spark/jobs.yaml");

  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
  private Operations ops;

  @BeforeAll
  void start() throws Exception {
    ops = Operations.withCatalog(getSparkSession(), otelEmitter);
  }

  @AfterAll
  void stop() throws Exception {
    if (ops != null) {
      ops.close();
    }
  }

  /** The Spark properties every job gets from jobs.yaml ({@code defaults.spark-properties}). */
  private static Map<String, String> jobsYamlSparkDefaults() throws Exception {
    String yaml = new String(Files.readAllBytes(JOBS_YAML), StandardCharsets.UTF_8);
    int start = yaml.indexOf("spark-properties: &spark-defaults {");
    Assertions.assertTrue(start >= 0, "jobs.yaml has the job defaults");
    String block = yaml.substring(start, yaml.indexOf('}', start));
    Map<String, String> properties = new LinkedHashMap<>();
    Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(block);
    while (m.find()) {
      properties.put(m.group(1), m.group(2));
    }
    return properties;
  }

  /**
   * The apps tests' session lists the SQL extensions exactly as jobs.yaml does (same classes, same
   * order), and its {@code openhouse} catalog is the OpenHouse catalog as there (on the Spark 3.5
   * line; on the Spark 4.2 line it is Iceberg's RESTCatalog, and jobs.yaml has no Spark 4.2 jobs).
   * Neither configures {@code spark_catalog}, the catalog the app copies its sample from.
   */
  @Test
  void theTestSessionHasTheExtensionsAndCatalogsJobsYamlGivesAJob() throws Exception {
    SparkSession spark = getSparkSession();
    Map<String, String> yaml = jobsYamlSparkDefaults();
    Assertions.assertEquals(
        yaml.get("spark.sql.extensions"), spark.conf().get("spark.sql.extensions"));
    Assertions.assertEquals(
        "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
            + "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions",
        yaml.get("spark.sql.extensions"),
        "Iceberg's extensions first, OpenHouse's second");
    Assertions.assertEquals(
        yaml.get("spark.sql.catalog.openhouse"), spark.conf().get("spark.sql.catalog.openhouse"));
    if (!JobsLine.restCatalog(spark)) {
      Assertions.assertEquals(
          yaml.get("spark.sql.catalog.openhouse.catalog-impl"),
          spark.conf().get("spark.sql.catalog.openhouse.catalog-impl"));
    }
    Assertions.assertTrue(
        yaml.keySet().stream().noneMatch(k -> k.startsWith("spark.sql.catalog.spark_catalog")),
        "jobs.yaml does not configure spark_catalog: " + yaml.keySet());
    Assertions.assertNull(spark.conf().get("spark.sql.catalog.spark_catalog", null));
  }

  /** The parsers of {@code spark}, outermost first, following each one's delegate field. */
  static List<ParserInterface> parserChain(SparkSession spark) throws Exception {
    List<ParserInterface> chain = new ArrayList<>();
    ParserInterface current = spark.sessionState().sqlParser();
    while (current != null) {
      chain.add(current);
      ParserInterface next = null;
      for (Class<?> c = current.getClass(); c != null && next == null; c = c.getSuperclass()) {
        for (Field field : c.getDeclaredFields()) {
          field.setAccessible(true);
          Object value = field.get(current);
          if (value instanceof ParserInterface && value != current) {
            next = (ParserInterface) value;
            break;
          }
        }
      }
      current = next;
    }
    return chain;
  }

  /** On that session the outermost parser is OpenHouse's; Iceberg's sits directly below it. */
  @Test
  void theSessionsParserIsOpenHousesWrappingIcebergs() throws Exception {
    List<ParserInterface> chain = parserChain(getSparkSession());
    Assertions.assertTrue(chain.size() >= 2, chain.toString());
    Assertions.assertEquals(
        "com.linkedin.openhouse.spark.sql.catalyst.parser.extensions."
            + "OpenhouseSparkSqlExtensionsParser",
        chain.get(0).getClass().getName());
    Assertions.assertFalse(chain.get(0) instanceof ExtendedParser);
    Assertions.assertTrue(chain.get(1) instanceof ExtendedParser, chain.toString());
  }

  /**
   * The exact call rewrite_data_files makes for {@code sort_order}: Iceberg 1.12 finds its parser
   * below OpenHouse's and parses the order; Iceberg 1.5.2 checks the outermost parser only and
   * refuses. That is the whole difference between the lines.
   */
  @Test
  void icebergsSortOrderParseOnThatSession() throws Exception {
    SparkSession spark = getSparkSession();
    if (FormatV3Tables.icebergCanWriteV3()) {
      List<ExtendedParser.RawOrderField> fields = ExtendedParser.parseSortOrder(spark, SORT_ORDER);
      Assertions.assertEquals(2, fields.size());
    } else {
      IllegalStateException e =
          Assertions.assertThrows(
              IllegalStateException.class, () -> ExtendedParser.parseSortOrder(spark, SORT_ORDER));
      Assertions.assertEquals(
          "Cannot parse order: parser is not an Iceberg ExtendedParser", e.getMessage());
    }
  }

  /**
   * On a session configured exactly as jobs.yaml configures the job (no {@code spark_catalog}
   * pointing at OpenHouse), the job does not reach the rewrite on either line: its copy reads the
   * table through {@code spark_catalog}, Spark's built-in session catalog, which has no such table.
   * The X6 and X11 job tests point {@code spark_catalog} at OpenHouse to get past it, as a
   * deployment that runs this job must.
   */
  @Test
  void onTheJobsYamlSessionTheJobStopsAtItsCopyFromSparkCatalog() throws Exception {
    SparkSession spark = getSparkSession();
    String fqtn = DB + ".jobs_yaml_session";
    String t = "openhouse." + fqtn;
    spark.sql("DROP TABLE IF EXISTS " + t);
    spark.sql(
        "CREATE TABLE "
            + t
            + " (id bigint, header struct<memberId: bigint, time: bigint>, datepartition string)");
    String datepartition =
        LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"));
    spark.sql(
        "INSERT INTO "
            + t
            + " VALUES (1, named_struct('memberId', 1L, 'time', 2L), '"
            + datepartition
            + "')");
    SortStatsCollectionSparkApp app =
        new SortStatsCollectionSparkApp("x11-jobs-yaml", null, fqtn, otelEmitter);
    Exception e = Assertions.assertThrows(Exception.class, () -> app.runInner(ops));
    Assertions.assertTrue(
        String.valueOf(e.getMessage()).contains("TABLE_OR_VIEW_NOT_FOUND")
            && String.valueOf(e.getMessage()).contains("spark_catalog"),
        String.valueOf(e));
    Assertions.assertNull(ops.getTable(fqtn).properties().get("sort-compression-rate"));
    spark.sql("DROP TABLE IF EXISTS " + t);
  }

  /**
   * Which rewrite the app runs on each line: where Iceberg's own parse works (1.12) the app's
   * rewrite_data_files call, unchanged; on 1.5.2 the rewrite action with the order parsed by the
   * Iceberg parser the app finds below OpenHouse's. Either way the order is the one Iceberg parses
   * from {@code SORT_ORDER}: two fields, ascending, nulls last.
   */
  @Test
  void theAppParsesItsSortOrderWithIcebergsParserOnEitherLine() throws Exception {
    SparkSession spark = getSparkSession();
    Assertions.assertEquals(SORT_ORDER, SortStatsCollectionSparkApp.SORT_ORDER);
    Assertions.assertEquals(
        FormatV3Tables.icebergCanWriteV3(),
        SortStatsCollectionSparkApp.sessionParsesSortOrder(spark),
        "the session's parse works on Iceberg 1.12 only");
    ExtendedParser below =
        SortStatsCollectionSparkApp.icebergParserBelow(spark.sessionState().sqlParser());
    Assertions.assertNotNull(below);
    Assertions.assertSame(parserChain(spark).get(1), below);
    org.apache.iceberg.Schema schema =
        new org.apache.iceberg.Schema(
            org.apache.iceberg.types.Types.NestedField.optional(
                1,
                "header",
                org.apache.iceberg.types.Types.StructType.of(
                    org.apache.iceberg.types.Types.NestedField.optional(
                        2, "memberId", org.apache.iceberg.types.Types.LongType.get()),
                    org.apache.iceberg.types.Types.NestedField.optional(
                        3, "time", org.apache.iceberg.types.Types.LongType.get()))));
    org.apache.iceberg.SortOrder expected =
        org.apache.iceberg.SortOrder.builderFor(schema)
            .asc("header.memberId", org.apache.iceberg.NullOrder.NULLS_LAST)
            .asc("header.time", org.apache.iceberg.NullOrder.NULLS_LAST)
            .build();
    Assertions.assertEquals(expected, SortStatsCollectionSparkApp.sortOrder(below, schema));
    // A parser chain with no Iceberg parser in it gives none (the app then makes its old call).
    Assertions.assertNull(
        SortStatsCollectionSparkApp.icebergParserBelow(
            parserChain(spark).get(parserChain(spark).size() - 1)));
  }

  /**
   * The app's rewrite on the line's own path, on a table of three data files: one sorted data file
   * replaces them in one {@code replace} snapshot, and the rows are unchanged.
   */
  @Test
  void theAppsRewriteSortsTheTableOnEitherLine() throws Exception {
    SparkSession spark = getSparkSession();
    String fqtn = DB + ".rewrite_sorted";
    String t = "openhouse." + fqtn;
    spark.sql("DROP TABLE IF EXISTS " + t);
    spark.sql(
        "CREATE TABLE "
            + t
            + " (id bigint, header struct<memberId: bigint, time: bigint>, datepartition string)");
    for (int k = 0; k < 3; k++) {
      List<String> values = new ArrayList<>();
      for (int r = 1; r <= 4; r++) {
        long id = k * 4L + r;
        values.add(
            "("
                + id
                + ", named_struct('memberId', "
                + (id % 3)
                + "L, 'time', "
                + (100 - id)
                + "L), 'p')");
      }
      spark.sql("INSERT INTO " + t + " VALUES " + String.join(", ", values));
    }
    List<String> before =
        spark.sql("SELECT * FROM " + t + " ORDER BY id").collectAsList().stream()
            .map(Object::toString)
            .collect(java.util.stream.Collectors.toList());
    Assertions.assertEquals(3L, spark.sql("SELECT * FROM " + t + ".data_files").count());

    SortStatsCollectionSparkApp.rewriteSorted(spark, fqtn);

    org.apache.iceberg.Table table = ops.getTable(fqtn);
    Assertions.assertEquals("replace", table.currentSnapshot().operation());
    Assertions.assertEquals(1L, spark.sql("SELECT * FROM " + t + ".data_files").count());
    List<String> after =
        spark.sql("SELECT * FROM " + t + " ORDER BY id").collectAsList().stream()
            .map(Object::toString)
            .collect(java.util.stream.Collectors.toList());
    Assertions.assertEquals(before, after);
    List<String> inFileOrder =
        spark.sql("SELECT header.memberId, header.time FROM " + t).collectAsList().stream()
            .map(Object::toString)
            .collect(java.util.stream.Collectors.toList());
    List<String> sorted =
        spark
            .sql(
                "SELECT header.memberId, header.time FROM "
                    + t
                    + " ORDER BY header.memberId ASC NULLS LAST, header.time ASC NULLS LAST")
            .collectAsList().stream()
            .map(Object::toString)
            .collect(java.util.stream.Collectors.toList());
    Assertions.assertEquals(sorted, inFileOrder, "the one data file is in the sort order");
    spark.sql("DROP TABLE IF EXISTS " + t);
  }
}
