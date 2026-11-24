package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.JobState;
import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tables.client.api.TableApi;
import com.linkedin.openhouse.tables.client.invoker.ApiClient;
import com.linkedin.openhouse.tables.client.model.CreateUpdateLockRequestBody;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Collections;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Spark 4.2 only, REST-only platform: a job that declares SYSTEM ({@link
 * SnapshotsExpirationSparkApp}) sets {@code spark.sql.catalog.openhouse.action-type}, which only
 * the OpenHouse catalog reads, so over Iceberg's RESTCatalog it is not declared and the service
 * refuses the job on a SYSTEM_ONLY-locked table (AppsTest's case fails on Spark 4.2). This test
 * checks the way RESTCatalog can declare it: the catalog property {@code
 * header.X-OpenHouse-Action-Type=SYSTEM}, which RESTCatalog sends as an HTTP header on every
 * request. If it passes, the jobs need only set that property as well (a change to BaseSparkApp).
 */
public class Spark42SystemActionHeaderTest extends OpenHouseSparkITest {
  private static final String HEADER = "spark.sql.catalog.openhouse.header.X-OpenHouse-Action-Type";

  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));

  @Test
  void snapshotExpiryWithTheSystemHeaderMaintainsASystemOnlyLockedTable() throws Exception {
    String database = "db";
    String tableName = "j42_se_system_header";
    String fqtn = database + "." + tableName;
    SparkSession spark = getSparkSession();
    spark.sql(String.format("DROP TABLE IF EXISTS openhouse.%s", fqtn));
    spark.sql(String.format("CREATE TABLE openhouse.%s (id int)", fqtn));
    ApiClient client = new ApiClient();
    client.setBasePath(getOpenHouseLocalServerURI().toString());
    client.addDefaultHeader("Authorization", "Bearer " + DUMMY_TOKEN);
    TableApi controls = new TableApi(client);
    try {
      spark.sql(String.format("INSERT INTO openhouse.%s VALUES (1)", fqtn));
      spark.sql(String.format("INSERT INTO openhouse.%s VALUES (2)", fqtn));
      long current =
          getOpenHouseCatalog(spark)
              .loadTable(TableIdentifier.parse(fqtn))
              .currentSnapshot()
              .snapshotId();
      controls
          .createLockV1(
              database,
              tableName,
              new CreateUpdateLockRequestBody()
                  .locked(true)
                  .reason(CreateUpdateLockRequestBody.ReasonEnum.SYSTEM_ONLY))
          .block();
      SparkSession session = spark.newSession();
      session.conf().set(HEADER, "SYSTEM");
      SparkSession.setActiveSession(session);
      StateManager stateManager = Mockito.mock(StateManager.class);
      new SnapshotsExpirationSparkApp(
              "job-" + tableName, stateManager, fqtn, 0, "", 1, false, ".backup", otelEmitter)
          .run();
      Mockito.verify(stateManager).updateState("job-" + tableName, JobState.SUCCEEDED);
      SparkSession reader = getSparkSession().newSession();
      reader.conf().set(HEADER, "SYSTEM");
      Table table = getOpenHouseCatalog(reader).loadTable(TableIdentifier.parse(fqtn));
      Assertions.assertEquals(current, table.currentSnapshot().snapshotId());
      Assertions.assertEquals(1, Iterables.size(table.snapshots()));
    } finally {
      SparkSession.clearActiveSession();
      controls.deleteLockByReasonV1(database, tableName, "SYSTEM_ONLY").block();
      getSparkSession().sql(String.format("DROP TABLE openhouse.%s", fqtn));
    }
  }
}
