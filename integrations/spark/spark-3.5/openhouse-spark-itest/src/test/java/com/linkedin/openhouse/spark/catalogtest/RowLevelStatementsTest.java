package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.spark.smoke.SparkSqlRowLevelStatements;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;

/**
 * The row-level statements on format v3 tables ({@link SparkSqlRowLevelStatements}, work items X2
 * and X3) on the Spark 3.5 catalog paths, against a tables service of their own with the cluster
 * switch on ({@link FormatV3Server}), one per test class. A concrete subclass names its path in
 * {@link #catalogPath()}; the session has a catalog for each of the three paths on that service,
 * and the statements' rows are also read through the two paths not under test ({@link
 * #otherReaders()}). What the service stored is loaded through the OpenHouse Java client.
 */
public abstract class RowLevelStatementsTest extends SparkSqlRowLevelStatements {

  static final String LEGACY = "legacy";
  static final String REST_SHIM = "rest-shim";
  static final String REST_HTTP = "rest-http";

  private static final Map<String, String> READERS = new HashMap<>();

  static {
    READERS.put(LEGACY, "rowlevel_legacy");
    READERS.put(REST_SHIM, "rowlevel_shim");
    READERS.put(REST_HTTP, "rowlevel_http");
  }

  /** The shared session of the catalog tests, whose Spark context this suite's session uses. */
  private static final class Fixture extends OpenHouseSparkITest {
    SparkSession session() throws Exception {
      return getSparkSession();
    }
  }

  private static final Fixture FIXTURE = new Fixture();

  private FormatV3Server server;
  private SparkSession spark;
  private Catalog javaClient;

  /** The catalog path under test. */
  protected abstract String catalogPath();

  @Override
  protected synchronized SparkSession sparkSession() throws Exception {
    SparkSession base = FIXTURE.session();
    if (spark == null || spark.sparkContext() != base.sparkContext()) {
      if (server == null) {
        server = new FormatV3Server();
      }
      String token = base.conf().get("spark.sql.catalog.openhouse.auth-token");
      SparkSession session = base.newSession();
      configure(session, base, CATALOG, catalogPath(), token, false);
      for (Map.Entry<String, String> reader : READERS.entrySet()) {
        configure(session, base, reader.getValue(), reader.getKey(), token, true);
      }
      Map<String, String> client = new HashMap<>();
      client.put("uri", server.uri());
      client.put("cluster", "local-cluster");
      client.put("auth-token", token);
      javaClient =
          CatalogUtil.loadCatalog(
              "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
              "rowlevel_client",
              client,
              base.sparkContext().hadoopConfiguration());
      spark = session;
    }
    return spark;
  }

  /** Makes {@code catalog} of {@code session} the catalog {@code path} on the v3 service. */
  private void configure(
      SparkSession session,
      SparkSession base,
      String catalog,
      String path,
      String token,
      boolean noCache) {
    String prefix = "spark.sql.catalog." + catalog;
    session.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    switch (path) {
      case LEGACY:
        session
            .conf()
            .set(prefix + ".catalog-impl", "com.linkedin.openhouse.spark.OpenHouseCatalog");
        break;
      case REST_SHIM:
        session
            .conf()
            .set(prefix + ".catalog-impl", "com.linkedin.openhouse.spark.OpenHouseRestCatalog");
        break;
      case REST_HTTP:
        SparkSqlSmokeTest.restCatalog(session, catalog, server.uri(), base);
        if (noCache) {
          session.conf().set(prefix + ".cache-enabled", "false");
        }
        return;
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + path);
    }
    session.conf().set(prefix + ".uri", server.uri());
    session.conf().set(prefix + ".cluster", "local-cluster");
    session.conf().set(prefix + ".auth-token", token);
    if (noCache) {
      session.conf().set(prefix + ".cache-enabled", "false");
    }
  }

  @Override
  protected Table plainTable(String table) throws Exception {
    sparkSession();
    return javaClient.loadTable(TableIdentifier.of(DB, table));
  }

  @Override
  protected List<String> otherReaders() {
    List<String> others = new ArrayList<>();
    for (String path : Arrays.asList(LEGACY, REST_SHIM, REST_HTTP)) {
      if (!path.equals(catalogPath())) {
        others.add(READERS.get(path));
      }
    }
    return others;
  }

  /** A difference of this itest's JVM, not of a catalog path: the same on all three. */
  static final String TEST_JVM = "the Spark 3.5 itest's JVM";

  /** Iceberg 1.12's Avro writer in a JVM that also has the embedded service's thin jars. */
  static final String AVRO_CLASS_CAST =
      "java.lang.ClassCastException: class org.apache.avro.Schema$RecordSchema cannot be cast to"
          + " class org.apache.iceberg.shaded.org.apache.avro.Schema"
          + " (org.apache.avro.Schema$RecordSchema and"
          + " org.apache.iceberg.shaded.org.apache.avro.Schema are in unnamed module of loader"
          + " 'app')";

  /**
   * The checks on which the Spark 3.5 paths differ from the baseline. On the Iceberg 1.12 line,
   * Spark cannot write an Avro data file in this test JVM: the embedded tables service needs the
   * fork's thin iceberg-core (with unshaded Avro) on the classpath, next to Iceberg's Spark
   * runtime, which carries the same Iceberg Avro classes against its shaded Avro, and the writer
   * gets classes from both. A Spark 3.5 engine has only the runtime jar; the Spark 4.2 run of the
   * same list, whose JVM has only the runtime, writes and reads Avro tables. The classpath is the
   * reason the build already excludes the thin iceberg-data, iceberg-orc and iceberg-parquet here.
   */
  @Override
  protected List<Difference> differences() {
    List<Difference> differences = new ArrayList<>();
    if (IcebergLine.current() == IcebergLine.V3) {
      for (String test :
          Arrays.asList("v3MergeOnReadAvro", "v2MergeOnReadAvro", "v3CopyOnWriteAvro")) {
        differences.add(
            Difference.on(test, "INSERT: the statement")
                .baseline("succeeds")
                .here(AVRO_CLASS_CAST)
                .because(
                    TEST_JVM,
                    "Iceberg 1.12's Avro writer meets the unshaded Avro of the embedded service's"
                        + " thin iceberg-core next to the shaded runtime (both jars hold"
                        + " org.apache.iceberg.avro.AvroFormatModel). Not a catalog path or an"
                        + " engine difference; Avro is proven by the Spark 4.2 run."));
      }
    }
    return differences;
  }

  @Override
  protected String outcomesFile() {
    String dir = System.getProperty("smoke.outcomes.dir");
    return dir == null ? null : dir + "/spark-3.5-" + catalogPath() + "-row-level-outcomes.tsv";
  }

  /** Drops the tables, stops the service and, as the other catalog tests, closes the session. */
  @AfterAll
  void stop() throws Exception {
    if (server != null) {
      for (TableIdentifier table :
          javaClient.listTables(org.apache.iceberg.catalog.Namespace.of(DB))) {
        javaClient.dropTable(table);
      }
      server.close();
      server = null;
    }
    spark = null;
    scala.Option<SparkSession> active = SparkSession.getDefaultSession();
    if (active.isDefined()) {
      active.get().close();
    }
  }
}
