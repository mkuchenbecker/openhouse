package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.dataFile;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.plan;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.planRequests;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.record;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
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
 * One plan is bounded (work item V11c): with the cluster property {@value #BOUND} set to 5, a scan
 * that plans more than five file scan tasks is answered {@code failed}, with an error that names
 * the bound and says to plan on the client, and no task; a scan that plans five or fewer is
 * answered as before, the tasks client planning gets. An unmodified Iceberg 1.12 {@link
 * RESTCatalog} configured to plan on the server fails the scan visibly ({@code
 * IllegalStateException} from its scan), never reads fewer rows; one that plans on the client is
 * not affected. The bound counts the tasks the scan plans, after pruning by the filter.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {ScanPlanning.ENABLED, IrcScanPlanningBoundTest.BOUND + "=5"})
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import({IrcReadRoutesTest.StubDefaults.class, ScanPlanning.RecordRequests.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcScanPlanningBoundTest {

  static final String BOUND = "cluster.tables.irc.scan-planning.max-file-scan-tasks";
  private static final String DB = "irc_scan_plan_bound";

  @LocalServerPort private int port;

  private RESTCatalog client;
  private RESTCatalog server;
  private final List<TableIdentifier> created = new ArrayList<>();

  @BeforeEach
  void start() {
    client = ScanPlanning.clientPlanning(port);
    server = ScanPlanning.serverPlanning(port);
  }

  @AfterEach
  void stop() throws Exception {
    try {
      for (TableIdentifier identifier : created) {
        client.dropTable(identifier);
      }
      created.clear();
    } finally {
      client.close();
      server.close();
    }
  }

  /** A table of {@code files} data files of two rows each, partitioned by p (p0 or p1). */
  private TableIdentifier table(String name, int files) {
    TableIdentifier identifier = TableIdentifier.of(DB, name);
    created.add(identifier);
    Table table =
        client.createTable(
            identifier,
            IrcScanPlanningTest.SCHEMA,
            PartitionSpec.builderFor(IrcScanPlanningTest.SCHEMA).identity("p").build());
    AppendFiles append = table.newAppend();
    for (int file = 0; file < files; file++) {
      String p = file < 3 ? "p0" : "p1";
      Record[] records = {
        record(IrcScanPlanningTest.SCHEMA, file * 10L, "n", p),
        record(IrcScanPlanningTest.SCHEMA, file * 10L + 1, "n", p)
      };
      append.appendFile(dataFile(table, records));
    }
    append.commit();
    return identifier;
  }

  private JsonNode postPlan(String table, String json) throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/iceberg/v1/namespaces/"
                                + DB
                                + "/tables/"
                                + table
                                + "/plan"))
                    .header("Authorization", "Bearer " + ScanPlanning.token("irc_planner"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return new ObjectMapper().readTree(response.body());
  }

  @Test
  void atTheBoundEveryTaskIsPlannedAsBefore() throws Exception {
    TableIdentifier id = table("at_bound", 5);
    List<String> onServer = plan(server.loadTable(id).newScan());
    assertEquals(plan(client.loadTable(id).newScan()), onServer);
    assertEquals(5, onServer.size());
    JsonNode answer = postPlan("at_bound", "{}");
    assertEquals("completed", answer.get("status").asText());
    assertEquals(5, answer.get("file-scan-tasks").size());
  }

  @Test
  void aboveTheBoundThePlanIsAnsweredFailedWithNoTask() throws Exception {
    table("above_bound", 6);
    JsonNode answer = postPlan("above_bound", "{}");
    assertEquals("failed", answer.get("status").asText(), answer.toString());
    JsonNode error = answer.get("error");
    assertEquals(422, error.get("code").asInt(), answer.toString());
    assertEquals("ScanPlanTooLargeException", error.get("type").asText());
    String message = error.get("message").asText();
    assertTrue(message.contains("more than 5 file scan tasks"), message);
    assertTrue(message.contains(BOUND), message);
    assertTrue(message.contains("scan-planning-mode=client"), message);
    assertFalse(answer.has("plan-id"), answer.toString());
    assertFalse(answer.has("file-scan-tasks"), answer.toString());
    assertFalse(answer.has("delete-files"), answer.toString());
    assertFalse(answer.has("plan-tasks"), answer.toString());
  }

  @Test
  void aServerPlanningClientFailsTheScanVisiblyAndAClientPlanningOneReadsEveryRow() {
    TableIdentifier id = table("client_sees", 6);
    int before = planRequests().size();
    Table onServer = server.loadTable(id);
    IllegalStateException planning =
        assertThrows(IllegalStateException.class, () -> plan(onServer.newScan()));
    assertTrue(
        planning.getMessage().contains("Remote scan planning failed")
            && planning.getMessage().contains("ScanPlanTooLargeException (code=422)")
            && planning.getMessage().contains("more than 5 file scan tasks"),
        planning.getMessage());
    // Reading rows fails the same way: no partial result.
    assertThrows(IllegalStateException.class, () -> rows(onServer, Expressions.alwaysTrue()));
    assertEquals(before + 2, planRequests().size());
    // A client that plans itself is not bounded.
    assertEquals(12, rows(client.loadTable(id), Expressions.alwaysTrue()).size());
    assertEquals(before + 2, planRequests().size());
  }

  /** The bound counts the tasks the scan plans: a filter that prunes to five or fewer plans. */
  @Test
  void aFilterThatPrunesToTheBoundPlans() {
    TableIdentifier id = table("pruned", 9);
    List<String> onServer =
        plan(server.loadTable(id).newScan().filter(Expressions.equal("p", "p0")));
    assertEquals(
        plan(client.loadTable(id).newScan().filter(Expressions.equal("p", "p0"))), onServer);
    assertEquals(3, onServer.size());
    assertEquals(
        rows(client.loadTable(id), Expressions.equal("p", "p0")),
        rows(server.loadTable(id), Expressions.equal("p", "p0")));
    assertThrows(
        IllegalStateException.class,
        () -> plan(server.loadTable(id).newScan().filter(Expressions.equal("p", "p1"))));
  }
}
