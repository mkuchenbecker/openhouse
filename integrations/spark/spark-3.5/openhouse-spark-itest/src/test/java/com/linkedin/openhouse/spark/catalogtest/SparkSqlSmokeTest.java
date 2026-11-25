package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.housetables.client.model.ToggleStatus;
import com.linkedin.openhouse.javaclient.CommitRaceCatalog;
import com.linkedin.openhouse.javaclient.CommitRaceRestShimCatalog;
import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.spark.grants.GrantStoringAuthorization;
import com.linkedin.openhouse.spark.smoke.CommitRaceRestCatalog;
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

  /**
   * Iceberg's own {@code SparkCatalog} with {@code type=rest}, over HTTP to the tables service's
   * Iceberg REST routes, with no OpenHouse catalog class on the catalog: what a platform configures
   * to reach OpenHouse through plain Iceberg REST (work item N1-c). The OpenHouse SQL extensions
   * are in the session as on the other paths (the shared session loads them through {@code
   * spark.sql.extensions}). On both Iceberg lines: see {@code RestHttpPathSparkSqlSmokeTest}.
   */
  protected static final String REST_HTTP = "rest-http";

  private static final String LEGACY_CATALOG_IMPL = "com.linkedin.openhouse.spark.OpenHouseCatalog";
  private static final String REST_SHIM_CATALOG_IMPL =
      "com.linkedin.openhouse.spark.OpenHouseRestCatalog";

  private static final String LEGACY_SECOND_CATALOG_IMPL = CommitRaceCatalog.class.getName();
  private static final String REST_SHIM_SECOND_CATALOG_IMPL =
      CommitRaceRestShimCatalog.class.getName();

  private static final String UNSUPPORTED = "java.lang.UnsupportedOperationException";

  /**
   * The shared session of the other catalog tests, whose Spark context the suite's sessions use.
   */
  private static final class Fixture extends OpenHouseSparkITest {
    SparkSession session() throws Exception {
      return getSparkSession();
    }
  }

  private static final Fixture FIXTURE = new Fixture();

  private static SparkSession legacySession;
  private static SparkSession restShimSession;
  private static SparkSession restHttpSession;
  private static ConfigurableApplicationContext smokeServer;

  /** The catalog path under test. */
  protected abstract String catalogPath();

  /** The one place where the catalog path selects session and catalog configuration. */
  @Override
  protected SparkSession sparkSession() throws Exception {
    switch (catalogPath()) {
      case LEGACY:
        return legacySession();
      case REST_SHIM:
        return restShimSession();
      case REST_HTTP:
        return restHttpSession();
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  /**
   * A session over the shared Spark context whose {@code openhouse} catalog is the shared session's
   * OpenHouse catalog, pointed at the suite's service ({@link #smokeServerUri()}).
   */
  private SparkSession legacySession() throws Exception {
    SparkSession base = FIXTURE.session();
    synchronized (SparkSqlSmokeTest.class) {
      if (legacySession == null || legacySession.sparkContext() != base.sparkContext()) {
        SparkSession spark = base.newSession();
        spark.conf().set("spark.sql.catalog.openhouse.uri", smokeServerUri());
        openHouseSecondCatalog(spark, LEGACY_SECOND_CATALOG_IMPL, base);
        legacySession = spark;
      }
      return legacySession;
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
        spark.conf().set("spark.sql.catalog.openhouse.uri", smokeServerUri());
        spark.conf().set("spark.sql.catalog.openhouse.catalog-impl", REST_SHIM_CATALOG_IMPL);
        openHouseSecondCatalog(spark, REST_SHIM_SECOND_CATALOG_IMPL, base);
        restShimSession = spark;
      }
      return restShimSession;
    }
  }

  /**
   * A session over the shared Spark context whose {@code openhouse} catalog is Iceberg's {@code
   * SparkCatalog} over Iceberg's {@code RESTCatalog}: the OpenHouse catalog's settings are removed
   * and the catalog is configured as a user of plain Iceberg REST would, with the same principal's
   * token. Everything else matches the legacy session, the SQL extensions included.
   */
  private SparkSession restHttpSession() throws Exception {
    SparkSession base = FIXTURE.session();
    synchronized (SparkSqlSmokeTest.class) {
      if (restHttpSession == null || restHttpSession.sparkContext() != base.sparkContext()) {
        SparkSession spark = base.newSession();
        restCatalog(spark, "openhouse", smokeServerUri(), base);
        restCatalog(spark, SECOND_CATALOG, smokeServerUri(), base);
        spark.conf().unset("spark.sql.catalog." + SECOND_CATALOG + ".type");
        spark
            .conf()
            .set(
                "spark.sql.catalog." + SECOND_CATALOG + ".catalog-impl",
                CommitRaceRestCatalog.class.getName());
        spark.conf().set("spark.sql.catalog." + SECOND_CATALOG + ".cache-enabled", "false");
        restHttpSession = spark;
      }
      return restHttpSession;
    }
  }

  /**
   * The suite's second catalog ({@link #SECOND_CATALOG}) on an OpenHouse catalog path: the class
   * {@code catalogImpl} (the path's catalog class, with the commit hook), the suite's service, the
   * same principal, and no table caching.
   */
  private static void openHouseSecondCatalog(
      SparkSession spark, String catalogImpl, SparkSession base) {
    String prefix = "spark.sql.catalog." + SECOND_CATALOG;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".catalog-impl", catalogImpl);
    spark.conf().set(prefix + ".uri", smokeServerUri());
    spark.conf().set(prefix + ".cluster", "local-cluster");
    spark
        .conf()
        .set(prefix + ".auth-token", base.conf().get("spark.sql.catalog.openhouse.auth-token"));
    spark.conf().set(prefix + ".cache-enabled", "false");
  }

  /** Makes {@code catalog} of {@code spark} Iceberg's REST catalog at {@code uri}'s IRC routes. */
  static void restCatalog(SparkSession spark, String catalog, String uri, SparkSession base) {
    String prefix = "spark.sql.catalog." + catalog;
    for (String key : new String[] {"catalog-impl", "cluster", "auth-token"}) {
      spark.conf().unset(prefix + "." + key);
    }
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", uri + "/iceberg");
    spark.conf().set(prefix + ".token", base.conf().get("spark.sql.catalog.openhouse.auth-token"));
  }

  /**
   * Session for scenarios that need views: the suite's service has the views feature gate open for
   * {@value #VIEWS_DB}, and the catalog has {@code iceberg-views-enabled} (the setup used by
   * OpenHouseViewEnabledTestSpark3_5). The catalog path still selects the configuration here.
   */
  @Override
  protected SparkSession viewsSparkSession() throws Exception {
    switch (catalogPath()) {
      case LEGACY:
        return viewsSession(LEGACY_CATALOG_IMPL);
      case REST_SHIM:
        return viewsSession(REST_SHIM_CATALOG_IMPL);
      case REST_HTTP:
        return restHttpViewsSession();
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  /**
   * The suite's own embedded tables service, one per test class, for every catalog path of the
   * class: the shared fixture's service, with two things added. Views are enabled for {@value
   * #VIEWS_DB} (the views feature gate; every other database keeps views off, as s28 shows), and
   * the authorization backend stores grants ({@link GrantStoringAuthorization}, work item T6), so
   * SHOW GRANTS lists what GRANT stored and REVOKE removed. Before T6 the main statements ran on
   * the shared fixture, which drops grants, and the views statements on a second service like this
   * one.
   */
  protected static synchronized String smokeServerUri() {
    if (smokeServer == null) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
      ConfigurableApplicationContext server =
          new SpringApplication(
                  SpringH2TestApplication.class,
                  ViewExceptionHandler.class,
                  GrantStoringAuthorization.Config.class)
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
      smokeServer = server;
    }
    return "http://localhost:"
        + ((WebServerApplicationContext) smokeServer).getWebServer().getPort();
  }

  private SparkSession viewsSession(String catalogImpl) throws Exception {
    SparkSession base = FIXTURE.session();
    String uri = smokeServerUri();
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

  /** The views catalog over the views service's IRC routes; views need no catalog flag there. */
  private SparkSession restHttpViewsSession() throws Exception {
    SparkSession base = FIXTURE.session();
    String uri = smokeServerUri();
    SparkSession spark = base.newSession();
    restCatalog(spark, VIEWS_CATALOG, uri, base);
    spark.conf().set("spark.sql.catalog." + VIEWS_CATALOG + ".cache-enabled", "false");
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
    if (IcebergLine.current() == IcebergLine.V3 && !REST_HTTP.equals(catalogPath())) {
      differences.addAll(namespaceExistsCheckOnIceberg112());
    }
    if (REST_SHIM.equals(catalogPath())) {
      differences.addAll(
          refusedReplaceLeavesItsDataFile(
              REFUSED_AT_COMMIT,
              "Work item C3: Iceberg's RESTCatalog stages a replace on the client (it loads the"
                  + " table, and no request carries the replace intent before Spark writes), so the"
                  + " gate runs when the adapter commits the replace, after the data file is"
                  + " written. The legacy catalog sends a native stage-replace first. Sending one"
                  + " here too would refuse before the write, but every allowed replace would then"
                  + " leave the native stage's metadata file as well (the legacy path's allowed"
                  + " replace adds 2 metadata files, this path's 1): not done, put to a decision."
                  + " The file is left for the orphan files job."));
    }
    if (REST_HTTP.equals(catalogPath())) {
      differences.addAll(RestHttpPathSparkSqlSmokeTest.restHttpDifferences());
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

  /** The category of the files a replace leaves when the gate refuses only its commit (C3). */
  protected static final String REFUSED_AT_COMMIT = "replace refused at commit, data file left";

  /** The category of a difference that the Iceberg 1.12 line makes on every Spark 3.5 path. */
  protected static final String ICEBERG_112 = "(c) Iceberg 1.12 line";

  /**
   * Existing catalogtest classes close the shared session when they finish, so the next class
   * builds its own. Do the same, and stop the suite's service, so this class leaves no session
   * behind for classes (such as OpenHouseViewEnabledTestSpark3_5) that expect to create their own.
   */
  @AfterAll
  static void releaseSessionAndViewsServer() {
    scala.Option<SparkSession> active = SparkSession.getDefaultSession();
    if (active.isDefined()) {
      active.get().close();
    }
    synchronized (SparkSqlSmokeTest.class) {
      legacySession = null;
      restShimSession = null;
      restHttpSession = null;
      if (smokeServer != null) {
        smokeServer.close();
        smokeServer = null;
      }
    }
  }
}
