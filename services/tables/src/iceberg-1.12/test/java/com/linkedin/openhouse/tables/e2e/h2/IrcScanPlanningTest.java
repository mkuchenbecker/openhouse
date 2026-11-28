package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.dataFile;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.equalityDeletes;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.plan;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.planRequests;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.positionDeletes;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.record;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.requests.PlanTableScanRequestParser;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.apache.iceberg.rest.responses.PlanTableScanResponseParser;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * Scan planning on the server equals scan planning on the client. For the same table, snapshot,
 * filter and projection, the tasks an unmodified Iceberg 1.12 {@link RESTCatalog} configured with
 * {@code scan-planning-mode=server} gets from the plan route are the tasks a default-configured one
 * plans itself, and the rows read through either are the same. Each comparison also checks that the
 * server-planning client did send a plan request and the client-planning one did not.
 *
 * <p>Format v2 tables here, with position and equality deletes; deletion vectors on format v3 are
 * in {@link IrcScanPlanningFormatV3Test}.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ScanPlanning.ENABLED)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import({IrcReadRoutesTest.StubDefaults.class, ScanPlanning.RecordRequests.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcScanPlanningTest {
  private static final String DB = "irc_scan_plan";

  static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()),
          Types.NestedField.optional(3, "p", Types.StringType.get()));

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

  private Table create(String name, PartitionSpec spec) {
    TableIdentifier identifier = TableIdentifier.of(DB, name);
    created.add(identifier);
    return client.createTable(identifier, SCHEMA, spec);
  }

  private static PartitionSpec byP() {
    return PartitionSpec.builderFor(SCHEMA).identity("p").build();
  }

  /** A table of three partitions, two files each, ids 0-59 (file k holds 10k to 10k+9). */
  private Table partitioned(String name) {
    Table table = create(name, byP());
    AppendFiles append = table.newAppend();
    for (int file = 0; file < 6; file++) {
      String p = "p" + (file % 3);
      Record[] records = new Record[10];
      for (int row = 0; row < 10; row++) {
        long id = file * 10L + row;
        records[row] = record(SCHEMA, id, "n" + id, p);
      }
      append.appendFile(dataFile(table, records));
    }
    append.commit();
    return table;
  }

  /** An unpartitioned table of four files with disjoint id ranges, one append each. */
  private Table ranges(String name) {
    Table table = create(name, PartitionSpec.unpartitioned());
    for (int file = 0; file < 4; file++) {
      Record[] records = new Record[5];
      for (int row = 0; row < 5; row++) {
        long id = file * 100L + row;
        records[row] = record(SCHEMA, id, "n" + id, "x");
      }
      table.newAppend().appendFile(dataFile(table, records)).commit();
    }
    return table;
  }

  /**
   * Plans {@code identifier} both ways with the same narrowing, asserts the plans are equal and
   * that exactly one plan request was sent (by the server-planning client), and returns the plan.
   */
  private List<String> assertSamePlan(TableIdentifier identifier, UnaryOperator<TableScan> narrow) {
    int before = planRequests().size();
    List<String> onClient = plan(narrow.apply(client.loadTable(identifier).newScan()));
    assertEquals(before, planRequests().size(), "client planning sends no plan request");
    List<String> onServer = plan(narrow.apply(server.loadTable(identifier).newScan()));
    assertEquals(
        Collections.singletonList(
            "POST /iceberg/v1/namespaces/" + DB + "/tables/" + identifier.name() + "/plan"),
        planRequests().subList(before, planRequests().size()),
        "server planning sends one plan request");
    assertEquals(onClient, onServer);
    return onServer;
  }

  /** The rows a client reads from {@code identifier} are the same whichever plans. */
  private List<String> assertSameRows(
      TableIdentifier identifier, Expression filter, String... columns) {
    List<String> onClient = rows(client.loadTable(identifier), filter, columns);
    int before = planRequests().size();
    List<String> onServer = rows(server.loadTable(identifier), filter, columns);
    assertTrue(planRequests().size() > before, "the server planned the read");
    assertEquals(onClient, onServer);
    return onServer;
  }

  @Test
  void anUnfilteredScanPlansTheSameTasks() {
    Table table = partitioned("unfiltered");
    TableIdentifier id = TableIdentifier.of(DB, "unfiltered");
    assertEquals(6, assertSamePlan(id, ScanPlanning.asIs()).size());
    assertEquals(60, assertSameRows(id, Expressions.alwaysTrue()).size());
  }

  /**
   * A default-configured client (the catalog property unset, as every existing client) never sends
   * a plan request: not to load, plan, read, or write. Nothing the server sends turns it on.
   */
  @Test
  void aDefaultClientSendsNoPlanRequest() {
    int before = planRequests().size();
    Table table = partitioned("default_client");
    TableIdentifier id = TableIdentifier.of(DB, "default_client");
    plan(client.loadTable(id).newScan().filter(Expressions.equal("p", "p0")));
    rows(client.loadTable(id), Expressions.alwaysTrue());
    table
        .newRowDelta()
        .addDeletes(
            positionDeletes(
                table, client.loadTable(id).newScan().planFiles().iterator().next().file(), 0L))
        .commit();
    rows(client.loadTable(id), Expressions.lessThan("id", 30L), "id");
    assertEquals(before, planRequests().size(), "plan requests: " + planRequests());
    assertTrue(ScanPlanning.REQUESTS.size() > 0);
  }

  @Test
  void aPartitionFilterPrunesTheSameFiles() {
    partitioned("by_partition");
    TableIdentifier id = TableIdentifier.of(DB, "by_partition");
    Expression filter = Expressions.equal("p", "p1");
    assertEquals(2, assertSamePlan(id, scan -> scan.filter(filter)).size());
    assertEquals(20, assertSameRows(id, filter).size());
  }

  /** Files whose column stats exclude the filter are pruned; the residual is kept on the rest. */
  @Test
  void aFilterOnColumnStatsPrunesTheSameFilesWithTheSameResiduals() {
    ranges("by_stats");
    TableIdentifier id = TableIdentifier.of(DB, "by_stats");
    Expression filter =
        Expressions.and(Expressions.greaterThan("id", 150L), Expressions.lessThan("id", 303L));
    List<String> planned = assertSamePlan(id, scan -> scan.filter(filter));
    assertEquals(2, planned.size(), planned.toString());
    assertTrue(planned.get(0).contains("residual={\"type\":\"and\""), planned.get(0));
    assertEquals(8, assertSameRows(id, filter).size());
  }

  /** A case-insensitive filter, and a select, plan the same files with the same residuals. */
  @Test
  void projectionAndCaseInsensitivityPlanTheSame() {
    partitioned("projected");
    TableIdentifier id = TableIdentifier.of(DB, "projected");
    assertSamePlan(id, scan -> scan.select("id"));
    assertSamePlan(
        id, scan -> scan.caseSensitive(false).select("ID").filter(Expressions.equal("P", "p2")));
    assertSamePlan(id, scan -> scan.filter(Expressions.lessThan("id", 15L)).select("name"));
    assertEquals(
        rows(client.loadTable(id), Expressions.lessThan("id", 15L), "id"),
        assertSameRows(id, Expressions.lessThan("id", 15L), "id"));
  }

  /** Column stats are kept for the columns asked for, and only those, either way. */
  @Test
  void statsFieldsKeepTheSameColumnStats() {
    partitioned("stats_fields");
    TableIdentifier id = TableIdentifier.of(DB, "stats_fields");
    List<String> planned =
        assertSamePlan(id, scan -> scan.includeColumnStats(Collections.singletonList("id")));
    assertTrue(planned.get(0).contains("lower=[1]"), planned.get(0));
    assertTrue(assertSamePlan(id, ScanPlanning.asIs()).get(0).contains("lower=null"));
  }

  /** {@code useSnapshot} (time travel, with the snapshot's schema) plans that snapshot. */
  @Test
  void aSnapshotIdPlansThatSnapshot() {
    Table table = ranges("time_travel");
    TableIdentifier id = TableIdentifier.of(DB, "time_travel");
    long first = table.history().get(0).snapshotId();
    long second = table.history().get(1).snapshotId();
    assertEquals(1, assertSamePlan(id, scan -> scan.useSnapshot(first)).size());
    assertEquals(2, assertSamePlan(id, scan -> scan.useSnapshot(second)).size());
    assertEquals(4, assertSamePlan(id, ScanPlanning.asIs()).size());
  }

  /**
   * A branch is read with the table's schema: a column added after the branch was made can be
   * selected and filtered on, as when the client plans (the client sends the branch's head with
   * {@code use-snapshot-schema=false}).
   */
  @Test
  void aBranchIsPlannedWithTheTablesSchema() {
    Table table = ranges("branch");
    TableIdentifier id = TableIdentifier.of(DB, "branch");
    long head = table.currentSnapshot().snapshotId();
    table.manageSnapshots().createBranch("b", table.history().get(1).snapshotId()).commit();
    table.updateSchema().addColumn("extra", Types.IntegerType.get()).commit();
    assertEquals(
        2,
        assertSamePlan(
                id,
                scan -> scan.useRef("b").select("id", "extra").filter(Expressions.isNull("extra")))
            .size());
    assertEquals(4, assertSamePlan(id, scan -> scan.useRef("main")).size());
    assertEquals(head, client.loadTable(id).currentSnapshot().snapshotId());
  }

  @Test
  void anEmptyTablePlansNoTasks() {
    create("empty", byP());
    TableIdentifier id = TableIdentifier.of(DB, "empty");
    assertEquals(Collections.emptyList(), assertSamePlan(id, ScanPlanning.asIs()));
    assertEquals(Collections.emptyList(), assertSameRows(id, Expressions.alwaysTrue()));
  }

  /** Position deletes (format v2) come with the data file they apply to, either way. */
  @Test
  void positionDeletesArePlannedTheSame() {
    Table table = create("pos_deletes", byP());
    DataFile a = dataFile(table, record(SCHEMA, 1L, "a", "p0"), record(SCHEMA, 2L, "b", "p0"));
    DataFile b = dataFile(table, record(SCHEMA, 3L, "c", "p1"), record(SCHEMA, 4L, "d", "p1"));
    table.newAppend().appendFile(a).appendFile(b).commit();
    table.newRowDelta().addDeletes(positionDeletes(table, a, 0L)).commit();
    TableIdentifier id = TableIdentifier.of(DB, "pos_deletes");
    List<String> planned = assertSamePlan(id, ScanPlanning.asIs());
    assertEquals(
        1,
        planned.stream().filter(t -> t.contains("POSITION_DELETES")).count(),
        planned.toString());
    assertEquals(3, assertSameRows(id, Expressions.alwaysTrue()).size());
    assertSamePlan(id, scan -> scan.filter(Expressions.equal("p", "p0")));
    assertEquals(1, assertSameRows(id, Expressions.equal("p", "p0")).size());
  }

  /** Equality deletes (format v2) come with every data file they may apply to, either way. */
  @Test
  void equalityDeletesArePlannedTheSame() {
    Table table = create("eq_deletes", byP());
    table
        .newAppend()
        .appendFile(dataFile(table, record(SCHEMA, 1L, "a", "p0"), record(SCHEMA, 2L, "b", "p0")))
        .appendFile(dataFile(table, record(SCHEMA, 3L, "c", "p1")))
        .commit();
    table
        .newRowDelta()
        .addDeletes(
            equalityDeletes(
                table, ScanPlanning.partitionOf(table, record(SCHEMA, 0L, "", "p0")), "id", 2L))
        .commit();
    TableIdentifier id = TableIdentifier.of(DB, "eq_deletes");
    List<String> planned = assertSamePlan(id, ScanPlanning.asIs());
    assertEquals(
        1,
        planned.stream().filter(t -> t.contains("EQUALITY_DELETES")).count(),
        planned.toString());
    assertEquals(2, assertSameRows(id, Expressions.alwaysTrue()).size());
    assertEquals(1, assertSameRows(id, Expressions.lessThan("id", 3L)).size());
  }

  /** Spark fills {@code min-rows-requested} from a LIMIT; the server returns every task anyway. */
  @Test
  void minRowsRequestedIsIgnored() {
    partitioned("min_rows");
    TableIdentifier id = TableIdentifier.of(DB, "min_rows");
    assertEquals(6, assertSamePlan(id, scan -> scan.minRowsRequested(1L)).size());
  }

  /** A few hundred files are planned into one {@code completed} response with no plan tasks. */
  @Test
  void manyFilesComeInOneResponse() throws Exception {
    Table table = create("many", PartitionSpec.unpartitioned());
    AppendFiles append = table.newAppend();
    for (int i = 0; i < 300; i++) {
      append.appendFile(
          DataFiles.builder(PartitionSpec.unpartitioned())
              .withPath(table.location() + "/data/file-" + i + ".parquet")
              .withFormat(FileFormat.PARQUET)
              .withFileSizeInBytes(1000 + i)
              .withRecordCount(10)
              .build());
    }
    append.commit();
    TableIdentifier id = TableIdentifier.of(DB, "many");
    assertEquals(300, assertSamePlan(id, ScanPlanning.asIs()).size());

    JsonNode response = postPlan("many", "{}");
    assertEquals("completed", response.get("status").asText(), response.toString());
    assertEquals(300, response.get("file-scan-tasks").size());
    assertFalse(response.has("plan-tasks"), "no plan tasks");
    assertFalse(response.has("storage-credentials"), "no credentials");
    // The same with a min-rows-requested hint of one row.
    assertEquals(300, postPlan("many", "{\"min-rows-requested\":1}").get("file-scan-tasks").size());
  }

  /**
   * An incremental append scan (start exclusive, end inclusive, as the specification says) plans
   * what {@code newIncrementalAppendScan} plans. No 1.12 client sends one ({@code appendsBetween}
   * plans on the client), so the request is sent as JSON and the response read with Iceberg's
   * parser.
   */
  @Test
  void anIncrementalScanPlansTheAppendsBetweenTwoSnapshots() throws Exception {
    Table table = ranges("incremental");
    long first = table.history().get(0).snapshotId();
    long third = table.history().get(2).snapshotId();
    PlanTableScanRequest request =
        PlanTableScanRequest.builder().withStartSnapshotId(first).withEndSnapshotId(third).build();
    PlanTableScanResponse response =
        PlanTableScanResponseParser.fromJson(
            postPlan("incremental", PlanTableScanRequestParser.toJson(request)),
            table.specs(),
            true);
    List<String> onServer = new ArrayList<>();
    response.fileScanTasks().forEach(task -> onServer.add(ScanPlanning.describe(task)));
    Collections.sort(onServer);
    List<String> onClient = new ArrayList<>();
    try (CloseableIterable<FileScanTask> tasks =
        client
            .loadTable(TableIdentifier.of(DB, "incremental"))
            .newIncrementalAppendScan()
            .fromSnapshotExclusive(first)
            .toSnapshot(third)
            .planFiles()) {
      tasks.forEach(task -> onClient.add(ScanPlanning.describe(task)));
    }
    Collections.sort(onClient);
    assertEquals(2, onClient.size());
    assertEquals(onClient, onServer);
  }

  /** The residual the server sends for a filter on a partitioned table is what the client keeps. */
  @Test
  void theResidualOfAPartitionAndDataFilterIsTheSame() {
    partitioned("residual");
    TableIdentifier id = TableIdentifier.of(DB, "residual");
    Expression filter =
        Expressions.and(Expressions.equal("p", "p0"), Expressions.greaterThanOrEqual("id", 5L));
    List<String> planned = assertSamePlan(id, scan -> scan.filter(filter));
    assertEquals(2, planned.size());
    // The partition predicate is decided by the partition; the data predicate remains.
    for (String task : planned) {
      assertTrue(task.contains("residual={\"type\":\"gt-eq\",\"term\":\"id\""), task);
    }
    assertEquals(15, assertSameRows(id, filter).size());
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
}
