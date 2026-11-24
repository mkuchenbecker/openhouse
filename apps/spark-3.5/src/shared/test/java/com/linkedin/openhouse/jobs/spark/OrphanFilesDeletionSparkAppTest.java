package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tables.client.api.TableApi;
import com.linkedin.openhouse.tables.client.invoker.ApiClient;
import com.linkedin.openhouse.tables.client.model.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.client.model.TimePartitionSpec;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
public class OrphanFilesDeletionSparkAppTest extends OpenHouseSparkITest {
  private static final String BACKUP_DIR = ".backup";
  private static final String ORPHAN_FILE_NAME = "data/test_orphan_file.orc";
  private static final long DEFAULT_TTL_SECONDS = TimeUnit.DAYS.toSeconds(7);
  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Arrays.asList(DefaultOtelConfig.getOpenTelemetry()));

  @Test
  public void testTtlSecondsPrimaryTableOneDayTtl() throws Exception {
    final String tableName = "db.test_ofd1";

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      // create table
      prepareTable(ops, tableName);
      // create and test ofd spark job
      OrphanFilesDeletionSparkApp app =
          new OrphanFilesDeletionSparkApp(
              "test-ofd-job1", null, tableName, 86400, otelEmitter, ".backup", 1, false, 100);
      Assertions.assertEquals(86400, app.getTtlSeconds());
    }
  }

  @Test
  public void testTtlSecondsPrimaryTableThreeDayTtl() throws Exception {
    final String tableName = "db.test_ofd2";

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      // create table
      prepareTable(ops, tableName);
      // create and test ofd spark job
      OrphanFilesDeletionSparkApp app =
          new OrphanFilesDeletionSparkApp(
              "test-ofd-job2", null, tableName, 259200, otelEmitter, ".backup", 1, false, 100);
      Assertions.assertEquals(259200, app.getTtlSeconds());
    }
  }

  @Test
  public void testTtlSecondsReplicaTableOneDayTtl() throws Exception {
    final String tableName = "db.test_ofd3";

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      // create table
      prepareReplicaTable(ops, tableName);
      // create and test ofd spark job
      OrphanFilesDeletionSparkApp app =
          new OrphanFilesDeletionSparkApp(
              "test-ofd-job3", null, tableName, 86400, otelEmitter, ".backup", 1, false, 100);
      app.runInner(ops);
      Assertions.assertEquals(259200, app.getTtlSeconds());
    }
  }

  @Test
  public void testTtlSecondsReplicaTableThreeDayTtl() throws Exception {
    final String tableName = "db.test_ofd4";

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      // create table
      prepareReplicaTable(ops, tableName);
      // create and test ofd spark job
      OrphanFilesDeletionSparkApp app =
          new OrphanFilesDeletionSparkApp(
              "test-ofd-job4", null, tableName, 259200, otelEmitter, ".backup", 1, false, 100);
      app.runInner(ops);
      Assertions.assertEquals(259200, app.getTtlSeconds());
    }
  }

  @Test
  public void testOneDayTtlEnabled() throws Exception {
    final String tableName = "db.test_ofd_one_day_ttl_enabled";
    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      Path orphanFilePath = setupTableWithOrphanFile(ops, tableName, 2);
      ops.spark()
          .sql(
              String.format(
                  "ALTER TABLE %s SET TBLPROPERTIES ('%s' = 'true')",
                  tableName, AppConstants.OFD_ONE_DAY_TTL_ENABLED_KEY));

      newApp(tableName).runInner(ops);

      Assertions.assertFalse(
          ops.fs().exists(orphanFilePath),
          "Orphan file older than 1 day should be deleted when ofd.one_day_ttl.enabled=true");
    }
  }

  @Test
  public void testDefaultTtl() throws Exception {
    final String tableName = "db.test_ofd_default_ttl";
    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      Path orphanFilePath = setupTableWithOrphanFile(ops, tableName, 2);

      newApp(tableName).runInner(ops);

      Assertions.assertTrue(
          ops.fs().exists(orphanFilePath),
          "Orphan file younger than the default 7d TTL should be preserved when the property is absent");
    }
  }

  private static void prepareTable(Operations ops, String tableName) {
    ops.spark().sql(String.format("DROP TABLE IF EXISTS %s", tableName)).show();
    ops.spark()
        .sql(
            String.format(
                "CREATE TABLE %s (data string, ts timestamp) partitioned by (days(ts))", tableName))
        .show();
    ops.spark().sql(String.format("SHOW TBLPROPERTIES %s", tableName)).show(false);
  }

  private void prepareReplicaTable(Operations ops, String tableName) {
    ops.spark().sql(String.format("DROP TABLE IF EXISTS %s", tableName)).show();
    if (JobsLine.restCatalog(ops.spark())) {
      // The Iceberg REST routes (Spark 4.2 line) refuse to set the table type: a replica table is
      // created by the replication flow through the native API, so the setup does that here; the
      // job then runs on it over the REST routes like on any other table.
      createReplicaTableThroughNativeApi(ops, tableName);
    } else {
      ops.spark()
          .sql(
              String.format(
                  "CREATE TABLE %s (data string, ts timestamp) partitioned by (days(ts)) "
                      + "TBLPROPERTIES ('openhouse.tableType' = 'REPLICA_TABLE')",
                  tableName))
          .show();
    }
    ops.spark().sql(String.format("SHOW TBLPROPERTIES %s", tableName)).show(false);
    Assertions.assertEquals(
        AppConstants.TABLE_TYPE_REPLICA,
        ops.getTable(tableName).properties().get(AppConstants.OPENHOUSE_TABLE_TYPE_KEY),
        "a replica table");
  }

  private void createReplicaTableThroughNativeApi(Operations ops, String tableName) {
    TableIdentifier id = TableIdentifier.parse(tableName);
    Schema schema =
        new Schema(
            Types.NestedField.optional(1, "data", Types.StringType.get()),
            Types.NestedField.optional(2, "ts", Types.TimestampType.withZone()));
    ApiClient client = new ApiClient();
    client.setBasePath(getOpenHouseLocalServerURI().toString());
    client.addDefaultHeader(
        "Authorization",
        "Bearer " + ops.spark().conf().get("spark.sql.catalog.openhouse.auth-token"));
    try {
      createReplicaTableThroughNativeApi(client, id, schema, ops);
    } catch (WebClientResponseException e) {
      throw new IllegalStateException(
          "native create of " + tableName + ": " + e.getResponseBodyAsString(), e);
    }
  }

  private static void createReplicaTableThroughNativeApi(
      ApiClient client, TableIdentifier id, Schema schema, Operations ops) {
    new TableApi(client)
        .createTableV1(
            id.namespace().toString(),
            new CreateUpdateTableRequestBody()
                .tableId(id.name())
                .databaseId(id.namespace().toString())
                .clusterId(
                    ops.spark().conf().get("spark.sql.catalog.openhouse.cluster", "local-cluster"))
                .schema(SchemaParser.toJson(schema, false))
                .timePartitioning(
                    new TimePartitionSpec()
                        .columnName("ts")
                        .granularity(TimePartitionSpec.GranularityEnum.DAY))
                .baseTableVersion("INITIAL_VERSION")
                .tableType(CreateUpdateTableRequestBody.TableTypeEnum.REPLICA_TABLE)
                // A replica table names its UUID (the primary's) and its identity, as the
                // replication flow does.
                .tableProperties(replicaProperties(id)))
        .block();
  }

  private static Map<String, String> replicaProperties(TableIdentifier id) {
    Map<String, String> properties = new HashMap<>();
    properties.put("openhouse.tableUUID", UUID.randomUUID().toString());
    properties.put("openhouse.databaseId", id.namespace().toString());
    properties.put("openhouse.tableId", id.name());
    return properties;
  }

  private OrphanFilesDeletionSparkApp newApp(String tableName) {
    return new OrphanFilesDeletionSparkApp(
        "test-job-id",
        null,
        tableName,
        DEFAULT_TTL_SECONDS,
        otelEmitter,
        BACKUP_DIR,
        5,
        false,
        20000);
  }

  private Path setupTableWithOrphanFile(Operations ops, String tableName, int orphanAgeDays)
      throws Exception {
    ops.spark().sql(String.format("DROP TABLE IF EXISTS %s", tableName)).show();
    ops.spark().sql(String.format("CREATE TABLE %s (data string, ts timestamp)", tableName)).show();
    for (int row = 0; row < 3; ++row) {
      ops.spark()
          .sql(String.format("INSERT INTO %s VALUES ('v%d', current_timestamp())", tableName, row))
          .show();
    }
    Table table = ops.getTable(tableName);
    Path orphanFilePath = new Path(table.location(), ORPHAN_FILE_NAME);
    FileSystem fs = ops.fs();
    fs.createNewFile(orphanFilePath);
    long mtimeMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(orphanAgeDays);
    fs.setTimes(orphanFilePath, mtimeMs, -1);
    return orphanFilePath;
  }
}
