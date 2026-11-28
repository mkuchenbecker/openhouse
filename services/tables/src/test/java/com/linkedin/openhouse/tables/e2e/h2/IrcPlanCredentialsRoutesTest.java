package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.delete;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.get;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.newTable;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.post;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.putSnapshots;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.preparePutSnapshotsWithAppendRequest;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The IRC scan planning route ({@code POST .../tables/{table}/plan}) and the credentials route
 * ({@code GET .../tables/{table}/credentials}) at the HTTP boundary, on a cluster that turns scan
 * planning on ({@code cluster.tables.irc.scan-planning.enabled}; off, the routes are not served:
 * IrcConfigEndpointsTest), and the access-delegation header ({@code X-Iceberg-Access-Delegation})
 * on the load and create routes, which vends nothing.
 */
@SpringBootTest(properties = IrcPlanCredentialsRoutesTest.PLANNING_ON)
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcPlanCredentialsRoutesTest {

  static final String DB = "irc_plan_routes";

  /** The cluster property that serves the two routes, which are off by default. */
  static final String PLANNING_ON =
      com.linkedin.openhouse.cluster.configs.ClusterProperties.IRC_SCAN_PLANNING_ENABLED + "=true";

  /** The header a client sends to ask for vended credentials or remote signing. */
  static final String ACCESS_DELEGATION = "X-Iceberg-Access-Delegation";

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()));

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;

  @Autowired
  com.linkedin.openhouse.common.audit.AuditHandler<
          com.linkedin.openhouse.tables.audit.model.TableAuditEvent>
      tableAuditHandler;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String table : created) {
      delete(mvc, DB, table);
    }
    created.clear();
  }

  private void createNative(String table) throws Exception {
    created.add(table);
    post(mvc, newTable(DB, table)).andExpect(status().isCreated());
  }

  /** Appends a (dummy) data file to the table through the native snapshots route. */
  private void append(String table, String file) throws Exception {
    MvcResult current = get(mvc, DB, table).andReturn();
    GetTableResponseBody body = buildGetTableResponseBody(current);
    putSnapshots(
            mvc,
            DB,
            table,
            preparePutSnapshotsWithAppendRequest(
                current,
                body,
                body.getTableLocation(),
                catalog,
                Collections.singletonList(
                    createDummyDataFile("/tmp/" + file + ".orc", getPartitionSpec(body)))))
        .andExpect(status().isOk());
  }

  static String plan(String db, String table) {
    return "/v1/namespaces/" + db + "/tables/" + table + "/plan";
  }

  static String credentials(String db, String table) {
    return "/v1/namespaces/" + db + "/tables/" + table + "/credentials";
  }

  private MvcResult postPlan(String path, String json) throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(HttpMethod.POST, path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  @SuppressWarnings("unchecked")
  private List<String> endpoints() throws Exception {
    return (List<String>) JsonPath.read(body(IrcHttp.get(mvc, "/v1/config")), "$.endpoints");
  }

  // ---------------------------------------------------------------- the routes

  static final String PLAN_ENDPOINT =
      "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan";

  static final String CREDENTIALS_ENDPOINT =
      "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials";

  /**
   * Whether this server's Iceberg line serves the two routes when the cluster turns scan planning
   * on: the Iceberg 1.12 line (the default build) does; LinkedIn's Iceberg 1.5.2 reference line has
   * no scan planning classes, and there they answer 406 as before.
   */
  static boolean served() {
    try {
      Class.forName("org.apache.iceberg.rest.requests.PlanTableScanRequest");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /**
   * Served on Iceberg 1.12: {@code completed}, a plan id, every task inline (none for a table
   * without data), no {@code plan-tasks} and no {@code storage-credentials}; listed. On the
   * reference line: the spec's 406, not listed.
   */
  @Test
  void planningIsServedSynchronouslyOnIceberg112() throws Exception {
    createNative("t1");
    MvcResult result = postPlan(plan(DB, "t1"), "{}");
    if (!served()) {
      assertIrcError(result, 406, "UnsupportedOperationException");
      assertFalse(endpoints().contains(PLAN_ENDPOINT));
      return;
    }
    assertEquals(200, result.getResponse().getStatus(), body(result));
    Map<String, Object> response = JsonPath.read(body(result), "$");
    assertEquals("completed", response.get("status"), body(result));
    assertFalse(String.valueOf(response.get("plan-id")).isEmpty(), body(result));
    assertEquals(Collections.emptyList(), response.getOrDefault("file-scan-tasks", List.of()));
    assertFalse(response.containsKey("plan-tasks"), body(result));
    assertFalse(response.containsKey("storage-credentials"), body(result));
    assertFalse(response.containsKey("error"), body(result));
    assertTrue(endpoints().contains(PLAN_ENDPOINT));
    // A new id each time: nothing is kept that the id could name.
    assertNotEquals(
        response.get("plan-id"),
        JsonPath.read(body(postPlan(plan(DB, "t1"), "{}")), "$.plan-id"),
        "a fresh plan id");
  }

  /** The table audit events of {@code table} sent since the last clear, without timestamps. */
  private List<String> tableEvents(String table) {
    org.mockito.ArgumentCaptor<com.linkedin.openhouse.tables.audit.model.TableAuditEvent> events =
        org.mockito.ArgumentCaptor.forClass(
            com.linkedin.openhouse.tables.audit.model.TableAuditEvent.class);
    org.mockito.Mockito.verify(tableAuditHandler, org.mockito.Mockito.atLeast(0))
        .audit(events.capture());
    List<String> json = new ArrayList<>();
    for (com.linkedin.openhouse.tables.audit.model.TableAuditEvent event : events.getAllValues()) {
      if (DB.equals(event.getDatabaseName()) && table.equals(event.getTableName())) {
        json.add(event.toBuilder().eventTimestamp(null).build().toJson());
      }
    }
    return json;
  }

  /**
   * A plan and a credentials call read the table, and each is audited as the IRC load of it is: one
   * READ event, the load's, a SUCCESS with the metadata location read, or a FAILED for a table the
   * caller cannot load.
   */
  @Test
  void aPlanAndACredentialsCallAreAuditedAsTheLoadIs() throws Exception {
    assumeTrue(served());
    createNative("t1");
    for (String table : new String[] {"t1", "no_such_table"}) {
      org.mockito.Mockito.clearInvocations(tableAuditHandler);
      IrcHttp.get(mvc, "/v1/namespaces/" + DB + "/tables/" + table);
      List<String> load = tableEvents(table);
      assertEquals(1, load.size(), String.valueOf(load));
      org.mockito.Mockito.clearInvocations(tableAuditHandler);
      postPlan(plan(DB, table), "{}");
      assertEquals(load, tableEvents(table), "plan of " + table);
      org.mockito.Mockito.clearInvocations(tableAuditHandler);
      IrcHttp.get(mvc, credentials(DB, table));
      assertEquals(load, tableEvents(table), "credentials of " + table);
    }
  }

  /** On Iceberg 1.12: an empty list, whatever plan id is named. On the reference line: 406. */
  @Test
  void credentialsAreAnEmptyListOnIceberg112() throws Exception {
    createNative("t1");
    for (String path :
        new String[] {credentials(DB, "t1"), credentials(DB, "t1") + "?planId=any-plan-id"}) {
      MvcResult result = IrcHttp.get(mvc, path);
      if (!served()) {
        assertIrcError(result, 406, "UnsupportedOperationException");
        continue;
      }
      assertEquals(200, result.getResponse().getStatus(), body(result));
      assertEquals("{\"storage-credentials\":[]}", body(result));
      assertTrue(result.getResponse().getContentType().startsWith("application/json"));
    }
    assertEquals(served(), endpoints().contains(CREDENTIALS_ENDPOINT));
  }

  /**
   * The routes of an asynchronous or paged plan are not served on either line: no plan is stored,
   * so there is nothing for them to read, cancel or page. A client never calls them here: it does
   * so only for a {@code submitted} plan, for {@code plan-tasks}, or when cancel is listed.
   */
  @Test
  void theStoredPlanRoutesAreNotServed() throws Exception {
    createNative("t1");
    String plan = plan(DB, "t1");
    for (MvcResult result :
        new MvcResult[] {
          IrcHttp.get(mvc, plan + "/p1"),
          IrcHttp.perform(mvc, IrcHttp.request(HttpMethod.DELETE, plan + "/p1")),
          postPlan("/v1/namespaces/" + DB + "/tables/t1/tasks", "{\"plan-task\":\"p1\"}")
        }) {
      assertIrcError(result, 406, "UnsupportedOperationException");
    }
    for (String endpoint : endpoints()) {
      assertFalse(endpoint.contains("/plan/") || endpoint.endsWith("/tasks"), endpoint);
    }
  }

  /**
   * A missing table, a namespace of several levels and an empty level answer as a load does: the
   * same status, type and message.
   */
  @Test
  void aMissingTableIsAnsweredAsALoad() throws Exception {
    assumeTrue(served());
    for (String[] key :
        new String[][] {{DB, "no_such_table"}, {"irc_plan_no_db", "t"}, {DB + "%1Fx", "t"}}) {
      MvcResult load = IrcHttp.getEncoded(mvc, "/v1/namespaces/" + key[0] + "/tables/" + key[1]);
      int status = load.getResponse().getStatus();
      assertTrue(status == 404, body(load));
      assertSameError(
          load,
          IrcHttp.perform(
              mvc,
              MockMvcRequestBuilders.request(
                      HttpMethod.POST, URI.create(IrcHttp.BASE + plan(key[0], key[1])))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}")));
      assertSameError(load, IrcHttp.getEncoded(mvc, credentials(key[0], key[1])));
    }
  }

  private static void assertSameError(MvcResult expected, MvcResult actual) throws Exception {
    assertEquals(
        expected.getResponse().getStatus(), actual.getResponse().getStatus(), body(actual));
    assertEquals(body(expected), body(actual));
  }

  /**
   * A request the planner cannot carry out is a 400 in the spec's error shape, not a 500: a body
   * that is not a plan request, a filter that does not parse or names no column, a snapshot the
   * table does not have, an incremental scan without its end, and a negative row hint.
   */
  @Test
  void aRequestThatCannotBePlannedIs400() throws Exception {
    assumeTrue(served());
    createNative("t1");
    // A table with a snapshot, so that the scan's columns and filter are resolved: a table
    // without one plans no file and resolves nothing, on the client as here.
    append("t1", "irc_plan_400");
    String path = plan(DB, "t1");
    MvcResult planned = postPlan(path, "{}");
    assertEquals(200, planned.getResponse().getStatus(), body(planned));
    assertEquals(
        Collections.singletonList("/tmp/irc_plan_400.orc"),
        JsonPath.read(body(planned), "$.file-scan-tasks[*].data-file.file-path"));
    assertIrcError(IrcHttp.perform(mvc, IrcHttp.request(HttpMethod.POST, path)), 400, BAD);
    for (String json :
        new String[] {
          "not json",
          "{\"filter\":{\"type\":\"no-such-operation\"}}",
          "{\"filter\":{\"type\":\"eq\",\"term\":\"no_such_column\",\"value\":1}}",
          "{\"snapshot-id\":1234567890}",
          "{\"start-snapshot-id\":1}",
          "{\"min-rows-requested\":-1}"
        }) {
      MvcResult result = postPlan(path, json);
      assertEquals(400, result.getResponse().getStatus(), json + " -> " + body(result));
      assertIrcError(result, 400, BAD);
    }
  }

  private static final String BAD = "BadRequestException";

  /**
   * A selected name the table does not have is ignored, as {@code Schema.select} ignores it when a
   * client plans (Iceberg's client sends only names of its scan's schema).
   */
  @Test
  void anUnknownSelectedNameIsIgnoredAsOnTheClient() throws Exception {
    assumeTrue(served());
    createNative("t1");
    append("t1", "irc_plan_select");
    String path = plan(DB, "t1");
    MvcResult unknown = postPlan(path, "{\"select\":[\"no_such_column\"]}");
    assertEquals(200, unknown.getResponse().getStatus(), body(unknown));
    assertEquals(
        JsonPath.read(body(postPlan(path, "{}")), "$.file-scan-tasks").toString(),
        JsonPath.read(body(unknown), "$.file-scan-tasks").toString());
  }

  /**
   * The server never asks a client to plan on the server: neither the config nor a load response
   * carries {@code scan-planning-mode}, so a client plans on the server only if configured to.
   */
  @Test
  void theServerNeverSendsAScanPlanningMode() throws Exception {
    createNative("t1");
    String config = body(IrcHttp.get(mvc, "/v1/config"));
    assertFalse(config.contains("scan-planning-mode"), config);
    String load = body(IrcHttp.get(mvc, "/v1/namespaces/" + DB + "/tables/t1"));
    assertFalse(load.contains("scan-planning-mode"), load);
    assertFalse(load.contains("storage-credentials"), load);
  }

  // ---------------------------------------------------------------- the header

  private MockHttpServletRequestBuilder delegating(MockHttpServletRequestBuilder request) {
    return request.header(ACCESS_DELEGATION, "vended-credentials,remote-signing");
  }

  /** A load asking for vended credentials or remote signing is answered as one that does not. */
  @Test
  void theAccessDelegationHeaderLeavesALoadByteForByteUnchanged() throws Exception {
    createNative("t1");
    String path = "/v1/namespaces/" + DB + "/tables/t1";
    MvcResult plain = IrcHttp.get(mvc, path);
    assertEquals(200, plain.getResponse().getStatus(), body(plain));
    for (String value :
        new String[] {
          "vended-credentials", "remote-signing", "vended-credentials,remote-signing"
        }) {
      MvcResult asked =
          IrcHttp.perform(
              mvc, IrcHttp.request(HttpMethod.GET, path).header(ACCESS_DELEGATION, value));
      assertEquals(200, asked.getResponse().getStatus(), body(asked));
      assertEquals(body(plain), body(asked), value);
      assertEquals(plain.getResponse().getContentType(), asked.getResponse().getContentType());
    }
  }

  /**
   * A create asking for vended credentials or remote signing is answered as one that does not: the
   * two responses differ only in what differs between any two tables (name, UUID, location,
   * timestamps).
   */
  @Test
  void theAccessDelegationHeaderLeavesACreateUnchanged() throws Exception {
    created.add("plain");
    MvcResult plain = IrcWrite.create(mvc, DB, createRequest("plain"));
    assertEquals(200, plain.getResponse().getStatus(), body(plain));
    created.add("asked");
    MvcResult asked =
        IrcHttp.perform(
            mvc, delegating(IrcWrite.post(IrcWrite.tables(DB), createRequest("asked"))));
    assertEquals(200, asked.getResponse().getStatus(), body(asked));
    assertEquals(
        FormatV3.withoutUuids(body(plain)).replace("plain", "TABLE"),
        FormatV3.withoutUuids(body(asked)).replace("asked", "TABLE"));
    assertFalse(body(asked).contains("storage-credentials"), body(asked));
  }

  private static CreateTableRequest createRequest(String table) {
    return CreateTableRequest.builder()
        .withName(table)
        .withSchema(SCHEMA)
        .setProperties(newTable(DB, table).getTableProperties())
        .build();
  }
}
