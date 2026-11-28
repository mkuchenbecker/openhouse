package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.housetables.client.model.ToggleStatus;
import com.linkedin.openhouse.javaclient.CommitRaceCatalog;
import com.linkedin.openhouse.spark.grants.GrantStoringAuthorization;
import com.linkedin.openhouse.spark.smoke.LinkedInMainComparison;
import com.linkedin.openhouse.spark.smoke.LinkedInMainRecording;
import com.linkedin.openhouse.spark.smoke.SparkSqlSmokeScenarios;
import com.linkedin.openhouse.tables.exception.ViewExceptionHandler;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.toggle.model.TableToggleStatus;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.util.Collections;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Records LinkedIn main's outcome of every smoke statement on main's only catalog path, the
 * OpenHouse catalog of Spark 3.5 over the native tables API: the same sessions, service and second
 * catalog the suite's legacy path uses (views gate open for the views database, an authorization
 * backend that stores grants), built from main's own fixtures. Test-side code only, copied into a
 * checkout of main by the recording script; it never runs on the branch.
 */
public class LinkedInMainSmokeRecorder extends SparkSqlSmokeScenarios {

  private static final String PATH =
      "the OpenHouse catalog of Spark 3.5 over the native tables API";

  private static final class Fixture extends OpenHouseSparkITest {
    SparkSession session() throws Exception {
      return getSparkSession();
    }
  }

  private static final Fixture FIXTURE = new Fixture();
  private static SparkSession session;
  private static ConfigurableApplicationContext server;

  private final LinkedInMainComparison recorder;

  public LinkedInMainSmokeRecorder() throws Exception {
    recorder = new LinkedInMainComparison("smoke-scenarios", Collections.emptyList());
  }

  @Override
  protected void outcome(String scenario, String key, Outcome actual) {
    recorder.observe(scenario, key, actual.toString());
  }

  @Override
  protected synchronized SparkSession sparkSession() throws Exception {
    SparkSession base = FIXTURE.session();
    if (session == null || session.sparkContext() != base.sparkContext()) {
      SparkSession spark = base.newSession();
      spark.conf().set("spark.sql.catalog.openhouse.uri", serverUri());
      String prefix = "spark.sql.catalog." + SECOND_CATALOG;
      spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
      spark.conf().set(prefix + ".catalog-impl", CommitRaceCatalog.class.getName());
      spark.conf().set(prefix + ".uri", serverUri());
      spark.conf().set(prefix + ".cluster", "local-cluster");
      spark.conf().set(prefix + ".auth-token", token(base));
      spark.conf().set(prefix + ".cache-enabled", "false");
      session = spark;
    }
    return session;
  }

  @Override
  protected SparkSession viewsSparkSession() throws Exception {
    SparkSession base = FIXTURE.session();
    SparkSession spark = base.newSession();
    String prefix = "spark.sql.catalog." + VIEWS_CATALOG;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".catalog-impl", "com.linkedin.openhouse.spark.OpenHouseCatalog");
    spark.conf().set(prefix + ".uri", serverUri());
    spark.conf().set(prefix + ".cluster", "local-cluster");
    spark.conf().set(prefix + ".auth-token", token(base));
    spark.conf().set(prefix + ".iceberg-views-enabled", "true");
    spark.conf().set(prefix + ".cache-enabled", "false");
    spark.conf().set("spark.sql.iceberg.rewriteViewCommands.enabled", "true");
    return spark;
  }

  private static String token(SparkSession base) {
    return base.conf().get("spark.sql.catalog.openhouse.auth-token");
  }

  private static synchronized String serverUri() {
    if (server == null) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
      server =
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
    }
    return "http://localhost:" + ((WebServerApplicationContext) server).getWebServer().getPort();
  }

  @AfterAll
  void writeRecording() throws Exception {
    recorder.endSuite(LinkedInMainRecording.header(PATH), null);
    session = null;
    if (server != null) {
      server.close();
      server = null;
    }
  }
}
