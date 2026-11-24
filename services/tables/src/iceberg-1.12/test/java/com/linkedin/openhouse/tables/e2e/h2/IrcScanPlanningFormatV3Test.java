package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.SWITCH_ON;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.dataFile;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.plan;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.planRequests;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.record;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.util.List;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * The service refuses to plan a scan of a format v3 table (work item V11e; the cluster switch
 * {@code cluster.iceberg.format-v3.enabled} on). A scan task in the specification carries no
 * sequence number, so a server-planned read of a v3 table would return a null inherited {@code
 * _last_updated_sequence_number}, and a server-planned copy-on-write rewrite would store wrong row
 * lineage. The plan is answered {@code failed}, and an Iceberg 1.12 client fails the scan visibly;
 * client planning reads the table as before. Until V11e this class compared a v3 plan with deletion
 * vectors to client planning; that subject no longer exists.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = SWITCH_ON)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import({IrcReadRoutesTest.StubDefaults.class, ScanPlanning.RecordRequests.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcScanPlanningFormatV3Test {
  private static final String DB = "irc_scan_plan_v3";

  @LocalServerPort private int port;

  private RESTCatalog client;
  private RESTCatalog server;

  @BeforeEach
  void start() {
    client = ScanPlanning.clientPlanning(port);
    server = ScanPlanning.serverPlanning(port);
  }

  @AfterEach
  void stop() throws Exception {
    try {
      client.dropTable(TableIdentifier.of(DB, "dv"));
    } finally {
      client.close();
      server.close();
    }
  }

  @Test
  void aFormatV3TableWithDeletionVectorsIsRefused() {
    TableIdentifier id = TableIdentifier.of(DB, "dv");
    Table table =
        client
            .buildTable(id, IrcScanPlanningTest.SCHEMA)
            .withPartitionSpec(
                PartitionSpec.builderFor(IrcScanPlanningTest.SCHEMA).identity("p").build())
            .withProperty("format-version", "3")
            .create();
    TableMetadata metadata = ((HasTableOperations) table).operations().current();
    assertEquals(3, metadata.formatVersion());
    DataFile a =
        dataFile(
            table,
            record(IrcScanPlanningTest.SCHEMA, 1L, "a", "p0"),
            record(IrcScanPlanningTest.SCHEMA, 2L, "b", "p0"),
            record(IrcScanPlanningTest.SCHEMA, 3L, "c", "p0"));
    DataFile b =
        dataFile(
            table,
            record(IrcScanPlanningTest.SCHEMA, 4L, "d", "p1"),
            record(IrcScanPlanningTest.SCHEMA, 5L, "e", "p1"));
    DataFile c = dataFile(table, record(IrcScanPlanningTest.SCHEMA, 6L, "f", "p2"));
    table.newAppend().appendFile(a).appendFile(b).appendFile(c).commit();
    RowDelta delta = table.newRowDelta();
    ScanPlanning.deletionVector(table, a, 0L, 2L).forEach(delta::addDeletes);
    List<DeleteFile> onB = ScanPlanning.deletionVector(table, b, 1L);
    onB.forEach(delta::addDeletes);
    delta.commit();
    assertEquals(FileFormat.PUFFIN, onB.get(0).format());

    // Client planning reads the deletion vectors and the row lineage.
    int before = planRequests().size();
    List<String> onClient = plan(client.loadTable(id).newScan());
    assertEquals(before, planRequests().size());
    assertEquals(3, onClient.size());
    List<String> read = rows(client.loadTable(id), Expressions.alwaysTrue());
    assertEquals(3, read.size(), read.toString());

    // The service refuses to plan it: every server-planned scan fails, and none returns rows.
    Table onServer = server.loadTable(id);
    for (Runnable scan :
        new Runnable[] {
          () -> plan(onServer.newScan()),
          () -> plan(onServer.newScan().filter(Expressions.equal("p", "p1"))),
          () -> rows(onServer, Expressions.alwaysTrue()),
          () -> rows(onServer, Expressions.greaterThan("id", 1L), "id")
        }) {
      IllegalStateException refused = assertThrows(IllegalStateException.class, scan::run);
      assertTrue(
          refused.getMessage().contains("Remote scan planning failed")
              && refused.getMessage().contains("ScanPlanFormatVersionException (code=422)")
              && refused.getMessage().contains("format version 3")
              && refused.getMessage().contains("scan-planning-mode=client"),
          refused.getMessage());
    }
    assertEquals(before + 4, planRequests().size());
    // Nothing changed: the table reads as before through client planning.
    assertEquals(read, rows(client.loadTable(id), Expressions.alwaysTrue()));
  }

  /** The answer on the wire: failed, the error, and no plan id and no task. */
  @Test
  void theAnswerIsFailedWithNoTask() throws Exception {
    TableIdentifier id = TableIdentifier.of(DB, "dv");
    Table table =
        client
            .buildTable(id, IrcScanPlanningTest.SCHEMA)
            .withProperty("format-version", "3")
            .create();
    table
        .newAppend()
        .appendFile(dataFile(table, record(IrcScanPlanningTest.SCHEMA, 1L, "a", "p0")))
        .commit();
    java.net.http.HttpResponse<String> response =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(
                            "http://localhost:"
                                + port
                                + "/iceberg/v1/namespaces/"
                                + DB
                                + "/tables/dv/plan"))
                    .header("Authorization", "Bearer " + ScanPlanning.token("irc_planner"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    com.fasterxml.jackson.databind.JsonNode answer =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
    assertEquals("failed", answer.get("status").asText(), response.body());
    assertEquals(422, answer.get("error").get("code").asInt());
    assertEquals("ScanPlanFormatVersionException", answer.get("error").get("type").asText());
    assertTrue(!answer.has("plan-id") && !answer.has("file-scan-tasks"), response.body());
    // The load's checks come first: a table that does not exist is still the load's 404.
    assertEquals(
        404,
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(
                            "http://localhost:"
                                + port
                                + "/iceberg/v1/namespaces/"
                                + DB
                                + "/tables/missing/plan"))
                    .header("Authorization", "Bearer " + ScanPlanning.token("irc_planner"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }
}
