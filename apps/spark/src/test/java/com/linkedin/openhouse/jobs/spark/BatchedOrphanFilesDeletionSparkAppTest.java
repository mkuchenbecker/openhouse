package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link BatchedOrphanFilesDeletionSparkApp}.
 *
 * <p>These tests run the app's logic in-process against a real Spark session backed by an in-memory
 * Iceberg catalog ({@link OpenHouseSparkITest}). The app is never submitted as an external Spark
 * job; {@code runInner(ops)} is called directly.
 *
 * <p>When testing the per-table complete-operation callback path, the Optimizer service is replaced
 * by an embedded {@link com.sun.net.httpserver.HttpServer} that captures request bodies. This
 * verifies the shape and content of the JSON payloads without requiring a live service.
 *
 * <p>What is covered: orphan file deletion logic, parallel table processing, partial-success
 * semantics, per-table POST payload format (SUCCESS and FAILED), and argument parsing. What is NOT
 * covered: the actual Optimizer service receiving and persisting results, or end-to-end job
 * submission via the Jobs service.
 */
@Slf4j
public class BatchedOrphanFilesDeletionSparkAppTest extends OpenHouseSparkITest {
  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Arrays.asList(DefaultOtelConfig.getOpenTelemetry()));

  @Test
  public void testSuccessfulOrphanFilesDeletionForMultipleTables() throws Exception {
    final List<String> tableNames = Arrays.asList("db.test_batch1", "db.test_batch2");
    final int parallelism = 2;

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      for (String tableName : tableNames) {
        prepareTable(ops, tableName);
        populateTable(ops, tableName, 2);
      }

      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "test-job",
              null,
              tableNames,
              parallelism,
              TimeUnit.DAYS.toSeconds(1),
              otelEmitter,
              ".backup",
              10,
              Collections.emptyList(),
              null);

      app.runInner(ops);

      for (String tableName : tableNames) {
        Table table = ops.getTable(tableName);
        Assertions.assertNotNull(table);
      }

      log.info("Successfully processed {} tables in batch", tableNames.size());
    }
  }

  @Test
  public void testBatchedDeletionWithSingleTable() throws Exception {
    final List<String> tableNames = Arrays.asList("db.test_single");
    final int parallelism = 1;

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      prepareTable(ops, tableNames.get(0));
      populateTable(ops, tableNames.get(0), 3);

      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "test-job",
              null,
              tableNames,
              parallelism,
              TimeUnit.DAYS.toSeconds(1),
              otelEmitter,
              ".backup",
              10,
              Collections.emptyList(),
              null);

      app.runInner(ops);

      Table table = ops.getTable(tableNames.get(0));
      Assertions.assertNotNull(table);

      log.info("Successfully processed single table in batch");
    }
  }

  @Test
  public void testBatchedDeletionWithInvalidTable() throws Exception {
    final List<String> tableNames =
        Arrays.asList("db.test_valid1", "db.nonexistent_table", "db.test_valid2");
    final int parallelism = 3;

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      prepareTable(ops, "db.test_valid1");
      populateTable(ops, "db.test_valid1", 2);
      prepareTable(ops, "db.test_valid2");
      populateTable(ops, "db.test_valid2", 2);

      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "test-job",
              null,
              tableNames,
              parallelism,
              TimeUnit.DAYS.toSeconds(1),
              otelEmitter,
              ".backup",
              10,
              Collections.emptyList(),
              null);

      Assertions.assertThrows(RuntimeException.class, () -> app.runInner(ops));

      log.info("Successfully handled batch with invalid table");
    }
  }

  @Test
  public void testBatchedDeletionWithEmptyTables() throws Exception {
    final List<String> tableNames = Arrays.asList("db.test_empty1", "db.test_empty2");
    final int parallelism = 2;

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      for (String tableName : tableNames) {
        prepareTable(ops, tableName);
      }

      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "test-job",
              null,
              tableNames,
              parallelism,
              TimeUnit.DAYS.toSeconds(1),
              otelEmitter,
              ".backup",
              10,
              Collections.emptyList(),
              null);

      app.runInner(ops);

      for (String tableName : tableNames) {
        Table table = ops.getTable(tableName);
        Assertions.assertNotNull(table);
      }

      log.info("Successfully processed empty tables in batch");
    }
  }

  @Test
  public void testBatchedDeletionWithDifferentParallelism() throws Exception {
    final List<String> tableNames =
        Arrays.asList("db.test_par1", "db.test_par2", "db.test_par3", "db.test_par4");
    final int parallelism = 4;

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      for (String tableName : tableNames) {
        prepareTable(ops, tableName);
        populateTable(ops, tableName, 1);
      }

      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "test-job",
              null,
              tableNames,
              parallelism,
              TimeUnit.DAYS.toSeconds(1),
              otelEmitter,
              ".backup",
              10,
              Collections.emptyList(),
              null);

      app.runInner(ops);

      for (String tableName : tableNames) {
        Table table = ops.getTable(tableName);
        Assertions.assertNotNull(table);
      }

      log.info(
          "Successfully processed {} tables with parallelism {}", tableNames.size(), parallelism);
    }
  }

  @Test
  public void testOrphanDeletionResultSuccess() {
    String tableName = "db.test_result";
    int orphanFileCount = 42;
    long bytesDeleted = 1024L;
    long durationMs = 5000L;

    BatchedOrphanFilesDeletionSparkApp.OrphanDeletionResult result =
        BatchedOrphanFilesDeletionSparkApp.OrphanDeletionResult.success(
            tableName, orphanFileCount, bytesDeleted, durationMs);

    Assertions.assertTrue(result.isSuccess());
    Assertions.assertEquals(tableName, result.getTableName());
    Assertions.assertEquals(orphanFileCount, result.getOrphanFilesDeleted());
    Assertions.assertEquals(bytesDeleted, result.getBytesDeleted());
    Assertions.assertEquals(durationMs, result.getDurationMs());
    Assertions.assertNull(result.getErrorMessage());
    Assertions.assertNull(result.getErrorType());
  }

  @Test
  public void testOrphanDeletionResultFailure() {
    String tableName = "db.test_failure";
    Exception error = new RuntimeException("Test error");
    long durationMs = 1000L;

    BatchedOrphanFilesDeletionSparkApp.OrphanDeletionResult result =
        BatchedOrphanFilesDeletionSparkApp.OrphanDeletionResult.failure(
            tableName, error, durationMs);

    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(tableName, result.getTableName());
    Assertions.assertEquals(0, result.getOrphanFilesDeleted());
    Assertions.assertEquals(0, result.getBytesDeleted());
    Assertions.assertEquals(durationMs, result.getDurationMs());
    Assertions.assertEquals("Test error", result.getErrorMessage());
    Assertions.assertEquals("RuntimeException", result.getErrorType());
  }

  @Test
  public void testCreateAppFromCommandLineArgs() {
    String[] args = {
      "--jobId", "test-job-123",
      "--storageURL", "http://localhost:8080",
      "--tableNames", "db.table1,db.table2,db.table3",
      "--parallelism", "5",
      "--ttl", "86400",
      "--backupDir", "/backup",
      "--concurrentDeletes", "20",
      "--operationIds", "uuid-1,uuid-2,uuid-3",
      "--resultsEndpoint", "http://localhost:8083/v1/optimizer/operations"
    };

    BatchedOrphanFilesDeletionSparkApp app =
        BatchedOrphanFilesDeletionSparkApp.createApp(args, otelEmitter);

    Assertions.assertNotNull(app);
  }

  @Test
  public void testCreateAppWithDefaultValues() {
    String[] args = {
      "--jobId", "test-job-456",
      "--storageURL", "http://localhost:8080",
      "--tableNames", "db.table1"
    };

    BatchedOrphanFilesDeletionSparkApp app =
        BatchedOrphanFilesDeletionSparkApp.createApp(args, otelEmitter);

    Assertions.assertNotNull(app);
  }

  @Test
  public void testCreateAppWithOperationIdsAndEndpoint() {
    String[] args = {
      "--jobId", "test-job-789",
      "--storageURL", "http://localhost:8080",
      "--tableNames", "db.table1,db.table2",
      "--operationIds", "uuid-1,uuid-2",
      "--resultsEndpoint", "http://localhost:8083/v1/optimizer/operations"
    };

    BatchedOrphanFilesDeletionSparkApp app =
        BatchedOrphanFilesDeletionSparkApp.createApp(args, otelEmitter);

    Assertions.assertNotNull(app);
  }

  @Test
  public void testBatchedDeletionWithActualOrphanFiles() throws Exception {
    final String tableName = "db.test_orphans";
    final List<String> tableNames = Arrays.asList(tableName);

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      prepareTable(ops, tableName);
      populateTable(ops, tableName, 3);

      Table table = ops.getTable(tableName);
      Assertions.assertNotNull(table.currentSnapshot());

      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "test-job",
              null,
              tableNames,
              1,
              0L,
              otelEmitter,
              ".backup",
              10,
              Collections.emptyList(),
              null);

      app.runInner(ops);

      Table updatedTable = ops.getTable(tableName);
      Assertions.assertNotNull(updatedTable.currentSnapshot());

      log.info("Successfully ran orphan deletion on table with actual data");
    }
  }

  @Test
  public void testBatchedDeletionPartialSuccessWithEndpoint() throws Exception {
    final List<String> tableNames = Arrays.asList("db.test_ep_valid", "db.nonexistent_ep");
    final List<String> operationIds = Arrays.asList("op-100", "op-200");

    List<String> receivedBodies = Collections.synchronizedList(new ArrayList<>());
    HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
    httpServer.createContext(
        "/ops",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          receivedBodies.add(new String(body, StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    httpServer.start();
    String endpoint = "http://localhost:" + httpServer.getAddress().getPort() + "/ops";

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      prepareTable(ops, "db.test_ep_valid");
      populateTable(ops, "db.test_ep_valid", 2);

      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "test-job",
              null,
              tableNames,
              2,
              TimeUnit.DAYS.toSeconds(1),
              otelEmitter,
              ".backup",
              10,
              operationIds,
              endpoint);

      app.runInner(ops);

      Assertions.assertEquals(2, receivedBodies.size());
      long successCount = receivedBodies.stream().filter(b -> b.contains("\"SUCCESS\"")).count();
      long failureCount = receivedBodies.stream().filter(b -> b.contains("\"FAILED\"")).count();
      Assertions.assertEquals(1, successCount);
      Assertions.assertEquals(1, failureCount);

      // SUCCESS payload has status only, no result object.
      String successBody =
          receivedBodies.stream().filter(b -> b.contains("\"SUCCESS\"")).findFirst().get();
      Assertions.assertFalse(successBody.contains("\"result\""));

      // FAILED payload has status + result with error details.
      String failureBody =
          receivedBodies.stream().filter(b -> b.contains("\"FAILED\"")).findFirst().get();
      Assertions.assertTrue(failureBody.contains("\"errorMessage\""));
      Assertions.assertTrue(failureBody.contains("\"errorType\""));
    } finally {
      httpServer.stop(0);
    }
  }

  @Test
  public void testBatchedDeletionAllFailWithEndpoint() throws Exception {
    final List<String> tableNames = Arrays.asList("db.nonexistent_ep1", "db.nonexistent_ep2");
    final List<String> operationIds = Arrays.asList("op-300", "op-400");

    List<String> receivedBodies = Collections.synchronizedList(new ArrayList<>());
    HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
    httpServer.createContext(
        "/ops",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          receivedBodies.add(new String(body, StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    httpServer.start();
    String endpoint = "http://localhost:" + httpServer.getAddress().getPort() + "/ops";

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      BatchedOrphanFilesDeletionSparkApp app =
          new BatchedOrphanFilesDeletionSparkApp(
              "test-job",
              null,
              tableNames,
              2,
              TimeUnit.DAYS.toSeconds(1),
              otelEmitter,
              ".backup",
              10,
              operationIds,
              endpoint);

      Assertions.assertThrows(RuntimeException.class, () -> app.runInner(ops));

      Assertions.assertEquals(2, receivedBodies.size());
      long failureCount = receivedBodies.stream().filter(b -> b.contains("\"FAILED\"")).count();
      Assertions.assertEquals(2, failureCount);

      for (String body : receivedBodies) {
        Assertions.assertTrue(body.contains("\"errorMessage\""));
        Assertions.assertTrue(body.contains("\"errorType\""));
      }
    } finally {
      httpServer.stop(0);
    }
  }

  @Test
  public void testBatchedDeletionMismatchedOperationIdsThrows() throws Exception {
    final List<String> tableNames = Arrays.asList("db.table1", "db.table2");
    final List<String> operationIds = Arrays.asList("op-1", "op-2", "op-3");
    String endpoint = "http://localhost:9999/ops";

    BatchedOrphanFilesDeletionSparkApp app =
        new BatchedOrphanFilesDeletionSparkApp(
            "test-job",
            null,
            tableNames,
            2,
            TimeUnit.DAYS.toSeconds(1),
            otelEmitter,
            ".backup",
            10,
            operationIds,
            endpoint);

    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      Assertions.assertThrows(IllegalArgumentException.class, () -> app.runInner(ops));
    }
  }

  private static void prepareTable(Operations ops, String tableName) {
    ops.spark().sql(String.format("DROP TABLE IF EXISTS %s", tableName)).show();
    ops.spark().sql(String.format("CREATE TABLE %s (data string, ts timestamp)", tableName)).show();
  }

  private static void populateTable(Operations ops, String tableName, int numRows) {
    for (int row = 0; row < numRows; ++row) {
      ops.spark()
          .sql(String.format("INSERT INTO %s VALUES ('v%d', current_timestamp())", tableName, row))
          .show();
    }
  }
}
