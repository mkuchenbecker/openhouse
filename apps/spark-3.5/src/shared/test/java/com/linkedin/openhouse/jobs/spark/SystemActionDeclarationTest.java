package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * What a job adds to its session to declare SYSTEM ({@link
 * BaseSparkApp#declareSystemActionOverRest}): with the OpenHouse catalog (Spark 3.5 line) nothing,
 * so the catalog sends exactly what it sent before; with Iceberg's RESTCatalog (Spark 4.2 line) the
 * {@code header.X-OpenHouse-Action-Type} property only, and only for an app that declares SYSTEM.
 */
public class SystemActionDeclarationTest extends OpenHouseSparkITest {
  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));

  private static Map<String, String> conf(SparkSession spark) {
    Map<String, String> all = new HashMap<>();
    spark.conf().getAll().foreach(entry -> all.put(entry._1(), entry._2()));
    return all;
  }

  @Test
  void aSystemAppAddsTheHeaderPropertyOnlyForTheRestCatalog() throws Exception {
    SparkSession session = getSparkSession().newSession();
    Map<String, String> before = conf(session);
    new SnapshotsExpirationSparkApp("j4", null, "db.t", 3, "days", 1, false, ".backup", otelEmitter)
        .declareSystemActionOverRest(session);
    Map<String, String> expected = new HashMap<>(before);
    if (JobsLine.restCatalog(session)) {
      expected.put("spark.sql.catalog.openhouse.header.X-OpenHouse-Action-Type", "SYSTEM");
    }
    Assertions.assertEquals(expected, conf(session));
  }

  @Test
  void anAppThatDoesNotDeclareSystemAddsNothing() throws Exception {
    SparkSession session = getSparkSession().newSession();
    Map<String, String> before = conf(session);
    new TableStatsCollectionSparkApp("j4", null, "db.t", otelEmitter)
        .declareSystemActionOverRest(session);
    Assertions.assertEquals(before, conf(session));
  }
}
