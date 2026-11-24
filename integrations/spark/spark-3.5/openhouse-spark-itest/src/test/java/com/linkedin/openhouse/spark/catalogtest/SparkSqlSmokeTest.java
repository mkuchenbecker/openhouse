package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.housetables.client.model.ToggleStatus;
import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.spark.smoke.SparkSqlSmokeScenarios;
import com.linkedin.openhouse.tables.exception.ViewExceptionHandler;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.toggle.model.TableToggleStatus;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The Spark SQL smoke suite (work item T1) on the Spark 3.5 catalog paths. The scenarios, their
 * statements and their baseline outcomes are {@link SparkSqlSmokeScenarios}, the one list the Spark
 * 4.2 itest runs too (work item N7). This class supplies only the sessions of each path and the
 * statements on which a path differs from the baseline.
 *
 * <p>The catalog path is a parameter: a concrete subclass names its path in {@link #catalogPath()}
 * and {@link #sparkSession()} is the single place that turns the path into a session.
 */
public abstract class SparkSqlSmokeTest extends SparkSqlSmokeScenarios {

  /** The OpenHouse catalog class, the baseline. */
  protected static final String LEGACY = "legacy";

  /**
   * Iceberg's {@code RESTCatalog} over an in-process REST-shaped client that dispatches to the
   * OpenHouse catalog.
   */
  protected static final String REST_SHIM = "rest-shim";

  private static final String LEGACY_CATALOG_IMPL = "com.linkedin.openhouse.spark.OpenHouseCatalog";
  private static final String REST_SHIM_CATALOG_IMPL =
      "com.linkedin.openhouse.spark.OpenHouseRestCatalog";

  private static final String UNSUPPORTED = "java.lang.UnsupportedOperationException";

  /** The embedded tables service and the shared session of the other catalog tests. */
  private static final class Fixture extends OpenHouseSparkITest {
    SparkSession session() throws Exception {
      return getSparkSession();
    }
  }

  private static final Fixture FIXTURE = new Fixture();

  private static SparkSession restShimSession;
  private static ConfigurableApplicationContext viewsServer;

  /** The catalog path under test. */
  protected abstract String catalogPath();

  /** The one place where the catalog path selects session and catalog configuration. */
  @Override
  protected SparkSession sparkSession() throws Exception {
    switch (catalogPath()) {
      case LEGACY:
        return FIXTURE.session();
      case REST_SHIM:
        return restShimSession();
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  /**
   * A session over the shared Spark context whose {@code openhouse} catalog is built by the
   * REST-shaped catalog class; everything else matches the legacy session.
   */
  private SparkSession restShimSession() throws Exception {
    SparkSession base = FIXTURE.session();
    synchronized (SparkSqlSmokeTest.class) {
      if (restShimSession == null || restShimSession.sparkContext() != base.sparkContext()) {
        SparkSession spark = base.newSession();
        spark.conf().set("spark.sql.catalog.openhouse.catalog-impl", REST_SHIM_CATALOG_IMPL);
        restShimSession = spark;
      }
      return restShimSession;
    }
  }

  /**
   * Session for scenarios that need views. Views are off in the default fixture, so these scenarios
   * run against a second embedded tables service with the views feature gate open and a catalog
   * with {@code iceberg-views-enabled} (the setup used by OpenHouseViewEnabledTestSpark3_5). The
   * catalog path still selects the configuration here.
   */
  @Override
  protected SparkSession viewsSparkSession() throws Exception {
    switch (catalogPath()) {
      case LEGACY:
        return viewsSession(LEGACY_CATALOG_IMPL);
      case REST_SHIM:
        return viewsSession(REST_SHIM_CATALOG_IMPL);
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  /** The port of the second embedded tables service, where views are enabled for the views db. */
  protected static synchronized String viewsServerUri() {
    if (viewsServer == null) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
      ConfigurableApplicationContext server =
          new SpringApplication(SpringH2TestApplication.class, ViewExceptionHandler.class)
              .run("--server.port=0");
      server
          .getBean(ToggleStatusesRepository.class)
          .save(
              TableToggleStatus.builder()
                  .databaseId(VIEWS_DB)
                  .tableId(ViewsFeatureGate.GATE_PROBE_TABLE_ID)
                  .featureId("views")
                  .toggleStatusEnum(ToggleStatus.StatusEnum.ACTIVE)
                  .build());
      viewsServer = server;
    }
    return "http://localhost:"
        + ((WebServerApplicationContext) viewsServer).getWebServer().getPort();
  }

  private SparkSession viewsSession(String catalogImpl) throws Exception {
    SparkSession base = FIXTURE.session();
    String uri = viewsServerUri();
    SparkSession spark = base.newSession();
    String prefix = "spark.sql.catalog." + VIEWS_CATALOG;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".catalog-impl", catalogImpl);
    spark.conf().set(prefix + ".uri", uri);
    spark.conf().set(prefix + ".cluster", "local-cluster");
    spark
        .conf()
        .set(prefix + ".auth-token", base.conf().get("spark.sql.catalog.openhouse.auth-token"));
    spark.conf().set(prefix + ".iceberg-views-enabled", "true");
    spark.conf().set(prefix + ".cache-enabled", "false");
    spark.conf().set("spark.sql.iceberg.rewriteViewCommands.enabled", "true");
    return spark;
  }

  /** One outcomes file per path, in the directory the build names. */
  @Override
  protected String outcomesFile() {
    String dir = System.getProperty("smoke.outcomes.dir");
    return dir == null ? null : dir + "/spark-3.5-" + catalogPath() + "-outcomes.tsv";
  }

  /**
   * The baseline is Spark 3.5 with the OpenHouse catalog on Iceberg 1.5.2. On the Iceberg 1.12
   * line, every path differs from it on the message of two refused namespace statements; nothing
   * else differs on the legacy and rest-shim paths.
   */
  @Override
  protected List<Difference> differences() {
    List<Difference> differences = new ArrayList<>();
    if (IcebergLine.current() == IcebergLine.V3) {
      differences.addAll(namespaceExistsCheckOnIceberg112());
    }
    return differences;
  }

  /**
   * Iceberg 1.12's {@code SparkCatalog} checks a namespace's existence with {@code
   * catalog.namespaceExists} (apache/iceberg#14507), where 1.5.2 left Spark's default, which
   * describes the namespace. Still refused, as an {@code UnsupportedOperationException}; the
   * wording is the OpenHouse catalog's answer to the other call (decision D14b).
   */
  protected static List<Difference> namespaceExistsCheckOnIceberg112() {
    String why =
        "Decision D14b: Iceberg 1.12's SparkCatalog checks existence with"
            + " catalog.namespaceExists (apache/iceberg#14507), which the OpenHouse catalog"
            + " refuses with its own wording. Still refused, same class.";
    List<Difference> differences = new ArrayList<>();
    differences.add(
        Difference.on("s19", "CREATE NAMESPACE openhouse.smoke_t1_new_ns")
            .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
            .here(fails(UNSUPPORTED, "Checking if database exists is not supported"))
            .because(ICEBERG_112, why));
    differences.add(
        Difference.on("s24", "DROP NAMESPACE " + CAT_DB)
            .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
            .here(fails(UNSUPPORTED, "Checking if database exists is not supported"))
            .because(ICEBERG_112, why));
    return Collections.unmodifiableList(differences);
  }

  /** The category of a difference that the Iceberg 1.12 line makes on every Spark 3.5 path. */
  protected static final String ICEBERG_112 = "(c) Iceberg 1.12 line";

  /**
   * Existing catalogtest classes close the shared session when they finish, so the next class
   * builds its own. Do the same, and stop the views server, so this class leaves no session behind
   * for classes (such as OpenHouseViewEnabledTestSpark3_5) that expect to create their own.
   */
  @AfterAll
  static void releaseSessionAndViewsServer() {
    scala.Option<SparkSession> active = SparkSession.getDefaultSession();
    if (active.isDefined()) {
      active.get().close();
    }
    synchronized (SparkSqlSmokeTest.class) {
      restShimSession = null;
      if (viewsServer != null) {
        viewsServer.close();
        viewsServer = null;
      }
    }
  }
}
