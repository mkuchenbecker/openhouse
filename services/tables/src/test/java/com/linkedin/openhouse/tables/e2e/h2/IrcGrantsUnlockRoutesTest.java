package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.newTable;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.post;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.tableUrl;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.ValidationUtilities.CURRENT_MAJOR_VERSION_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.audit.model.TableAuditEvent;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The OpenHouse grants and unlock routes under the IRC base path (work item V5-3), against the
 * native routes they adapt. Every case runs the native request on one table (or database) and the
 * IRC request on an identical twin, and compares:
 *
 * <ul>
 *   <li>the outcome: the success status, the success body byte for byte, or the error status and
 *       message (the IRC error in the spec's shape, with the Iceberg type of the existing IRC
 *       routes);
 *   <li>the stored state: the ACLs the service handed the authorization handler ({@link
 *       RecordingAcls}), and the table's lock state and version;
 *   <li>the access decisions the service asked for;
 *   <li>the table audit events.
 * </ul>
 *
 * <p>The routes, as the native ones they adapt (the native update is a PATCH; Iceberg's REST client
 * can send only GET, HEAD, POST and DELETE, so the IRC update is a POST with the same body):
 *
 * <pre>
 * GET    /iceberg/v1/namespaces/{ns}/tables/{t}/openhouse/acl-policies  GET   /v1/databases/{ns}/tables/{t}/aclPolicies
 * POST   /iceberg/v1/namespaces/{ns}/tables/{t}/openhouse/acl-policies  PATCH /v1/databases/{ns}/tables/{t}/aclPolicies
 * GET    /iceberg/v1/namespaces/{ns}/openhouse/acl-policies             GET   /v1/databases/{ns}/aclPolicies
 * POST   /iceberg/v1/namespaces/{ns}/openhouse/acl-policies             PATCH /v1/databases/{ns}/aclPolicies
 * DELETE /iceberg/v1/namespaces/{ns}/tables/{t}/openhouse/lock          DELETE /v1/databases/{ns}/tables/{t}/lock
 * DELETE /iceberg/v1/namespaces/{ns}/tables/{t}/openhouse/lock/{reason} DELETE /v1/databases/{ns}/tables/{t}/lock/{reason}
 * </pre>
 *
 * Same Spring context as {@link IrcReadRoutesAuthTest} (token interceptor and authorization on).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcGrantsUnlockRoutesTest {

  private static final String V1 = CURRENT_MAJOR_VERSION_PREFIX;

  /** The body the OpenHouse Java client sends for {@code GRANT SELECT ON TABLE x TO u1}. */
  static final String GRANT_U1 =
      "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\",\"operation\":\"GRANT\"}";

  static final String REVOKE_U1 =
      "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\",\"operation\":\"REVOKE\"}";

  /** Every field of the native body. */
  static final String GRANT_U2_FULL =
      "{\"role\":\"ACL_EDITOR\",\"principal\":\"u2\",\"operation\":\"GRANT\","
          + "\"expirationEpochTimeSeconds\":1707791165,\"properties\":{\"k\":\"v\"}}";

  @Autowired MockMvc mvc;
  @Autowired AuditHandler<TableAuditEvent> tableAuditHandler;
  @Autowired ToggleStatusesRepository toggles;
  @MockBean AuthorizationHandler authorizationHandler;

  private final RecordingAcls acls = new RecordingAcls();
  private final List<String[]> tables = new ArrayList<>();
  private final List<String[]> views = new ArrayList<>();

  @BeforeEach
  void install() {
    acls.install(authorizationHandler);
  }

  @AfterEach
  void cleanUp() throws Exception {
    acls.allowAll();
    for (String[] key : views) {
      NativeViewHttp.delete(mvc, key[0], key[1]);
    }
    for (String[] key : tables) {
      mvc.perform(MockMvcRequestBuilders.delete(tableUrl(key[0], key[1]) + "/lock/SYSTEM_ONLY"));
      mvc.perform(MockMvcRequestBuilders.delete(tableUrl(key[0], key[1]) + "/lock"));
      CommitPathHttp.delete(mvc, key[0], key[1]);
    }
    views.clear();
    tables.clear();
  }

  // ---------------------------------------------------------------- fixtures

  static String nativeDb(String name) {
    return "irc_gu_n_" + name;
  }

  static String ircDb(String name) {
    return "irc_gu_i_" + name;
  }

  private void table(String db, boolean shared) throws Exception {
    tables.add(new String[] {db, "t"});
    post(
            mvc,
            newTable(db, "t")
                .toBuilder()
                .policies(Policies.builder().sharingEnabled(shared).build())
                .build())
        .andExpect(status().isCreated());
  }

  /** Identical tables {@code t} in the native and the IRC database of case {@code name}. */
  private void twins(String name, boolean shared) throws Exception {
    table(nativeDb(name), shared);
    table(ircDb(name), shared);
  }

  private void lock(String db, String reason) throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post(tableUrl(db, "t") + "/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    reason == null
                        ? "{\"locked\":true,\"message\":\"m\"}"
                        : "{\"locked\":true,\"message\":\"m\",\"reason\":\"" + reason + "\"}"))
        .andExpect(status().isCreated());
  }

  private void lockTwins(String name, String reason) throws Exception {
    lock(nativeDb(name), reason);
    lock(ircDb(name), reason);
  }

  // ---------------------------------------------------------------- requests

  static String nativeTableAcl(String db, String table) {
    return V1 + "/databases/" + db + "/tables/" + table + "/aclPolicies";
  }

  static String nativeDatabaseAcl(String db) {
    return V1 + "/databases/" + db + "/aclPolicies";
  }

  static String ircTableAcl(String namespace, String table) {
    return "/v1/namespaces/" + namespace + "/tables/" + table + "/openhouse/acl-policies";
  }

  static String ircDatabaseAcl(String namespace) {
    return "/v1/namespaces/" + namespace + "/openhouse/acl-policies";
  }

  static String ircLock(String namespace, String table) {
    return "/v1/namespaces/" + namespace + "/tables/" + table + "/openhouse/lock";
  }

  private static MockHttpServletRequestBuilder withBody(
      MockHttpServletRequestBuilder request, String body) {
    return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  /** A native request; {@code path} is sent as given (already encoded). */
  private MockHttpServletRequestBuilder nativeRequest(HttpMethod method, String path, String body) {
    return withBody(MockMvcRequestBuilders.request(method, URI.create(path)), body);
  }

  /** An IRC request; {@code path} is relative to the IRC base and sent as given. */
  private MockHttpServletRequestBuilder ircRequest(HttpMethod method, String path, String body) {
    return withBody(MockMvcRequestBuilders.request(method, URI.create(IrcHttp.BASE + path)), body);
  }

  // ---------------------------------------------------------------- outcomes

  /** What one request did: its answer, the audit events it caused and the decisions it asked. */
  static final class Outcome {
    final int status;
    final String body;
    final List<String> audit;
    final List<String> decisions;
    final MvcResult result;

    Outcome(MvcResult result, List<String> audit, List<String> decisions) throws Exception {
      this.status = result.getResponse().getStatus();
      this.body = result.getResponse().getContentAsString();
      this.audit = audit;
      this.decisions = decisions;
      this.result = result;
    }

    @Override
    public String toString() {
      return status + " " + body + " audit=" + audit + " decisions=" + decisions;
    }
  }

  private Outcome perform(MockHttpServletRequestBuilder request, String db, String resource)
      throws Exception {
    acls.takeDecisions(resource);
    clearInvocations(tableAuditHandler);
    MvcResult result = mvc.perform(request).andReturn();
    List<String> audit = new ArrayList<>();
    for (Invocation invocation : mockingDetails(tableAuditHandler).getInvocations()) {
      if ("audit".equals(invocation.getMethod().getName())) {
        audit.add(normalized((TableAuditEvent) invocation.getArgument(0), db));
      }
    }
    return new Outcome(result, audit, acls.takeDecisions(resource));
  }

  private static String normalized(TableAuditEvent event, String db) {
    return String.join(
        " ",
        String.valueOf(event.getOperationType()),
        String.valueOf(event.getOperationStatus()),
        "db=" + (db.equals(event.getDatabaseName()) ? "DB" : event.getDatabaseName()),
        "table=" + event.getTableName(),
        "grantor=" + event.getGrantor(),
        "grantee=" + event.getGrantee(),
        "role=" + event.getRole(),
        "user=" + event.getUser(),
        "action=" + event.getActionType(),
        "cluster=" + event.getClusterName(),
        "root=" + event.getCurrentTableRoot());
  }

  /** The IRC error type of the existing IRC routes for a native status on these routes. */
  private static String ircType(int status) {
    switch (status) {
      case 400:
        return "BadRequestException";
      case 403:
        return "ForbiddenException";
      case 404:
        return "NoSuchTableException";
      case 409:
        return "LockConflictException";
      case 415:
        return "HttpMediaTypeNotSupportedException";
      case 500:
        return "ServiceFailureException";
      default:
        throw new AssertionError("no IRC type expected for " + status);
    }
  }

  private static String nativeMessage(String body) {
    try {
      return JsonPath.read(body, "$.message");
    } catch (Exception e) {
      return body;
    }
  }

  /**
   * A message with its bean-validation violations sorted: the validator reports them in no fixed
   * order (two native requests with the same body can list them differently).
   */
  private static String violations(String message) {
    // A missing body: Spring's message names the controller method that was to receive it, the
    // native controller's natively and the IRC controller's here (pinned in the body tests).
    message =
        message.replaceAll(
            "Required request body is missing: .*$", "Required request body is missing: <method>");
    String[] parts = message.split("; ");
    if (parts.length < 2 || !message.contains("UpdateAclPoliciesRequestBody.")) {
      return message;
    }
    java.util.Arrays.sort(parts);
    return String.join("; ", parts);
  }

  /**
   * Asserts the native and the IRC outcome are the same once database names are aligned: success
   * status and body, or error status and message (the IRC one in the spec's error shape); the same
   * audit events and access decisions. Returns the status.
   */
  private int assertSame(String name, Outcome nativeOutcome, Outcome ircOutcome) throws Exception {
    String n = nativeDb(name);
    String i = ircDb(name);
    assertEquals(nativeOutcome.status, ircOutcome.status, nativeOutcome + " / " + ircOutcome);
    if (nativeOutcome.status < 400) {
      assertEquals(
          nativeOutcome.body.replace(n, "DB"), ircOutcome.body.replace(i, "DB"), "success body");
    } else {
      String ircMessage =
          assertIrcError(ircOutcome.result, ircOutcome.status, ircType(ircOutcome.status))
              .message();
      assertEquals(
          violations(nativeMessage(nativeOutcome.body).replace(n, "DB")),
          violations(ircMessage.replace(i, "DB")),
          "error message");
    }
    assertEquals(nativeOutcome.audit, ircOutcome.audit, "table audit");
    assertEquals(nativeOutcome.decisions, ircOutcome.decisions, "access decisions");
    return nativeOutcome.status;
  }

  /** Runs a table ACL request natively (PATCH or GET) and over IRC (POST or GET), compares. */
  private int tableAcl(String name, HttpMethod ircMethod, String body) throws Exception {
    HttpMethod nativeMethod = ircMethod == HttpMethod.POST ? HttpMethod.PATCH : ircMethod;
    Outcome nativeOutcome =
        perform(
            nativeRequest(nativeMethod, nativeTableAcl(nativeDb(name), "t"), body),
            nativeDb(name),
            nativeDb(name) + ".t");
    Outcome ircOutcome =
        perform(
            ircRequest(ircMethod, ircTableAcl(ircDb(name), "t"), body),
            ircDb(name),
            ircDb(name) + ".t");
    int status = assertSame(name, nativeOutcome, ircOutcome);
    assertEquals(acls.stored(nativeDb(name) + ".t"), acls.stored(ircDb(name) + ".t"), "ACLs");
    return status;
  }

  private int databaseAcl(String name, HttpMethod ircMethod, String body) throws Exception {
    HttpMethod nativeMethod = ircMethod == HttpMethod.POST ? HttpMethod.PATCH : ircMethod;
    Outcome nativeOutcome =
        perform(
            nativeRequest(nativeMethod, nativeDatabaseAcl(nativeDb(name)), body),
            nativeDb(name),
            nativeDb(name));
    Outcome ircOutcome =
        perform(ircRequest(ircMethod, ircDatabaseAcl(ircDb(name)), body), ircDb(name), ircDb(name));
    int status = assertSame(name, nativeOutcome, ircOutcome);
    assertEquals(acls.stored(nativeDb(name)), acls.stored(ircDb(name)), "ACLs");
    return status;
  }

  /**
   * Unlocks natively and over IRC ({@code reason} null for the reasonless route) and compares the
   * outcomes and the table's lock state and version (whether it wrote) afterwards.
   */
  private int unlock(String name, String reason) throws Exception {
    String suffix = reason == null ? "" : "/" + reason;
    String[] before = {state(nativeDb(name)), state(ircDb(name))};
    Outcome nativeOutcome =
        perform(
            nativeRequest(
                HttpMethod.DELETE, tableUrl(nativeDb(name), "t") + "/lock" + suffix, null),
            nativeDb(name),
            nativeDb(name) + ".t");
    Outcome ircOutcome =
        perform(
            ircRequest(HttpMethod.DELETE, ircLock(ircDb(name), "t") + suffix, null),
            ircDb(name),
            ircDb(name) + ".t");
    String[] after = {state(nativeDb(name)), state(ircDb(name))};
    int status = assertSame(name, nativeOutcome, ircOutcome);
    assertEquals(after[0], after[1], "lock state after");
    assertEquals(
        version(nativeDb(name)) + "", version(ircDb(name)) + "", "version count after (same)");
    assertEquals(
        before[0].equals(after[0]), before[1].equals(after[1]), "both changed state or neither");
    return status;
  }

  private final Map<String, List<String>> versions = new HashMap<>();

  /** The table's lock state, and records its version, read as the system reads a locked table. */
  private String state(String db) throws Exception {
    MvcResult result =
        mvc.perform(
                MockMvcRequestBuilders.get(tableUrl(db, "t"))
                    .header(TablesMvcConstants.HTTP_HEADER_ACTION_TYPE, "SYSTEM"))
            .andReturn();
    if (result.getResponse().getStatus() != 200) {
      return "status " + result.getResponse().getStatus();
    }
    String json = result.getResponse().getContentAsString();
    List<String> seen = versions.computeIfAbsent(db, d -> new ArrayList<>());
    String location = JsonPath.read(json, "$.tableLocation");
    if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(location)) {
      seen.add(location);
    }
    try {
      Map<String, Object> lock = JsonPath.read(json, "$.policies.lockState");
      if (lock == null) {
        return "unlocked";
      }
      return "locked=" + lock.get("locked") + " reason=" + lock.get("reason");
    } catch (PathNotFoundException e) {
      return "unlocked";
    }
  }

  /** How many table versions {@link #state} has seen for {@code db}. */
  private int version(String db) {
    return versions.getOrDefault(db, Collections.emptyList()).size();
  }

  // ---------------------------------------------------------------- table grants

  /**
   * GRANT, SHOW GRANTS and REVOKE on a shared table: each IRC request stores, answers and audits
   * what the native request does. The update is 204 with no body, the listing 200 with the native
   * body byte for byte.
   */
  @Test
  void grantShowAndRevokeOnATableAreTheNativeOnes() throws Exception {
    twins("grant", true);
    assertEquals(204, tableAcl("grant", HttpMethod.POST, GRANT_U1));
    assertEquals(
        Collections.singletonList("u1=TABLE_VIEWER exp=null props=null"),
        acls.stored(ircDb("grant") + ".t"));
    assertEquals(204, tableAcl("grant", HttpMethod.POST, GRANT_U2_FULL));
    assertEquals(
        "u2=ACL_EDITOR exp=1707791165 props={k=v}", acls.stored(ircDb("grant") + ".t").get(1));
    assertEquals(200, tableAcl("grant", HttpMethod.GET, null));
    MvcResult shown =
        mvc.perform(ircRequest(HttpMethod.GET, ircTableAcl(ircDb("grant"), "t"), null)).andReturn();
    assertEquals(
        "u1", JsonPath.read(shown.getResponse().getContentAsString(), "$.results[0].principal"));
    assertEquals(
        "application/json", shown.getResponse().getContentType().replace(";charset=UTF-8", ""));
    assertEquals(204, tableAcl("grant", HttpMethod.POST, REVOKE_U1));
    assertEquals(1, acls.stored(ircDb("grant") + ".t").size());
    assertEquals(200, tableAcl("grant", HttpMethod.GET, null));
  }

  @Test
  void aGrantOnAnUnsharedTableIs400AsNatively() throws Exception {
    twins("unshared", false);
    assertEquals(400, tableAcl("unshared", HttpMethod.POST, GRANT_U1));
    assertEquals(Collections.emptyList(), acls.stored(ircDb("unshared") + ".t"));
    // A revoke needs no sharing.
    assertEquals(204, tableAcl("unshared", HttpMethod.POST, REVOKE_U1));
  }

  @Test
  void aGrantOnALockedTableIs400AndARevokeIsAcceptedAsNatively() throws Exception {
    twins("locked", true);
    lockTwins("locked", null);
    assertEquals(400, tableAcl("locked", HttpMethod.POST, GRANT_U1));
    assertEquals(204, tableAcl("locked", HttpMethod.POST, REVOKE_U1));
    assertEquals(200, tableAcl("locked", HttpMethod.GET, null));
    twins("syslocked", true);
    lockTwins("syslocked", "SYSTEM_ONLY");
    assertEquals(400, tableAcl("syslocked", HttpMethod.POST, GRANT_U1));
    assertEquals(200, tableAcl("syslocked", HttpMethod.GET, null));
  }

  /**
   * A principal without UPDATE_ACL is refused 403 on both routes and nothing is stored. Listing
   * makes no access decision natively, so it is answered over IRC for a principal denied
   * everything.
   */
  @Test
  void aGrantWithoutUpdateAclIs403AndShowingNeedsNoPrivilegeAsNatively() throws Exception {
    twins("denied", true);
    acls.deny(Privileges.UPDATE_ACL);
    assertEquals(403, tableAcl("denied", HttpMethod.POST, GRANT_U1));
    assertEquals(403, tableAcl("denied", HttpMethod.POST, REVOKE_U1));
    assertEquals(Collections.emptyList(), acls.stored(ircDb("denied") + ".t"));
    acls.deny(Privileges.values());
    assertEquals(200, tableAcl("denied", HttpMethod.GET, null));
  }

  /**
   * A missing table, and a name that is a view, are 404 on both, as the native routes read tables
   * only.
   */
  @Test
  void aclsOfAMissingTableOrOfAViewAre404AsNatively() throws Exception {
    assertEquals(404, tableAcl("missing", HttpMethod.POST, GRANT_U1));
    assertEquals(404, tableAcl("missing", HttpMethod.GET, null));
    for (String db : new String[] {nativeDb("view"), ircDb("view")}) {
      NativeViewHttp.enableViews(toggles, db);
      table(db, true);
      views.add(new String[] {db, "v"});
      MvcResult created =
          NativeViewHttp.post(
              mvc,
              NativeViewHttp.createBody(
                  db,
                  "v",
                  NativeViewHttp.SCHEMA_ID_NAME,
                  "SELECT id, name FROM " + db + ".t",
                  Collections.emptyMap()));
      assertEquals(201, NativeViewHttp.code(created), NativeViewHttp.body(created));
    }
    Outcome nativeOutcome =
        perform(
            nativeRequest(HttpMethod.GET, nativeTableAcl(nativeDb("view"), "v"), null),
            nativeDb("view"),
            nativeDb("view") + ".v");
    Outcome ircOutcome =
        perform(
            ircRequest(HttpMethod.GET, ircTableAcl(ircDb("view"), "v"), null),
            ircDb("view"),
            ircDb("view") + ".v");
    assertEquals(404, assertSame("view", nativeOutcome, ircOutcome));
  }

  /**
   * Every body the native route refuses is refused over IRC with the same status and message, and
   * nothing is stored. Bean validation runs in the handler (audited FAILED on both); a body that
   * does not parse never reaches it (no audit on both).
   */
  @Test
  void aTableAclBodyTheNativeRouteRefusesIsRefusedTheSameWay() throws Exception {
    twins("bodies", true);
    String[] refused = {
      "{}",
      "{\"role\":\"\",\"principal\":\"u1\",\"operation\":\"GRANT\"}",
      "{\"role\":\"TABLE_VIEWER\",\"principal\":\"\",\"operation\":\"GRANT\"}",
      "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\"}",
      "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\",\"operation\":\"FOO\"}",
      "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\",\"operation\":\"grant\"}",
      "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\",\"operation\":\"GRANT\","
          + "\"expirationEpochTimeSeconds\":\"soon\"}",
      "{\"role\":",
      "[]",
      "",
    };
    for (String body : refused) {
      assertEquals(400, tableAcl("bodies", HttpMethod.POST, body), body);
    }
    assertEquals(
        "Unacceptable JSON Required request body is missing: public"
            + " org.springframework.http.ResponseEntity<java.lang.Void>"
            + " com.linkedin.openhouse.tables.api.irc.IcebergRestOpenHouseController"
            + ".updateTableAclPolicies(java.lang.String,java.lang.String,"
            + "com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody)",
        assertIrcError(
                mvc.perform(ircRequest(HttpMethod.POST, ircTableAcl(ircDb("bodies"), "t"), ""))
                    .andReturn(),
                400,
                "BadRequestException")
            .message());
    assertEquals(Collections.emptyList(), acls.stored(ircDb("bodies") + ".t"));
    // An unknown field is ignored on both.
    assertEquals(
        204,
        tableAcl(
            "bodies",
            HttpMethod.POST,
            "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\",\"operation\":\"GRANT\",\"x\":1}"));
    // No body at all, and a body that is not JSON.
    Outcome nativeNoBody =
        perform(
            MockMvcRequestBuilders.patch(nativeTableAcl(nativeDb("bodies"), "t")),
            nativeDb("bodies"),
            nativeDb("bodies") + ".t");
    Outcome ircNoBody =
        perform(
            MockMvcRequestBuilders.post(IrcHttp.BASE + ircTableAcl(ircDb("bodies"), "t")),
            ircDb("bodies"),
            ircDb("bodies") + ".t");
    assertEquals(400, assertSame("bodies", nativeNoBody, ircNoBody));
    Outcome nativeText =
        perform(
            MockMvcRequestBuilders.patch(nativeTableAcl(nativeDb("bodies"), "t"))
                .contentType(MediaType.TEXT_PLAIN)
                .content(GRANT_U1),
            nativeDb("bodies"),
            nativeDb("bodies") + ".t");
    Outcome ircText =
        perform(
            MockMvcRequestBuilders.post(IrcHttp.BASE + ircTableAcl(ircDb("bodies"), "t"))
                .contentType(MediaType.TEXT_PLAIN)
                .content(GRANT_U1),
            ircDb("bodies"),
            ircDb("bodies") + ".t");
    // The native 415 has an empty body; the IRC one carries Spring's message.
    assertEquals(415, nativeText.status, nativeText.toString());
    assertEquals("", nativeText.body);
    assertIrcError(ircText.result, 415, "HttpMediaTypeNotSupportedException");
    assertEquals(nativeText.audit, ircText.audit);
  }

  @Test
  void anInvalidIdentifierIs400AsNatively() throws Exception {
    assertEquals(400, tableAcl("bad-name", HttpMethod.POST, GRANT_U1));
    assertEquals(400, tableAcl("bad-name", HttpMethod.GET, null));
    assertEquals(400, databaseAcl("bad-name", HttpMethod.POST, GRANT_U1));
    assertEquals(400, databaseAcl("bad-name", HttpMethod.GET, null));
  }

  /**
   * A namespace of more than one level names no table, as on the other IRC table routes: 404 {@code
   * NoSuchTableException}; it is a malformed database, as on the other IRC namespace routes: 400.
   * Neither reaches the service. (The OpenHouse Java client refuses both before sending.)
   */
  @Test
  void aMultiLevelNamespaceIsAnsweredAsOnTheOtherIrcRoutes() throws Exception {
    String namespace = "irc_gu_ml%1Fx";
    for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.POST}) {
      String body = method == HttpMethod.POST ? GRANT_U1 : null;
      assertIrcError(
          mvc.perform(ircRequest(method, ircTableAcl(namespace, "t"), body)).andReturn(),
          404,
          "NoSuchTableException");
      assertIrcError(
          mvc.perform(ircRequest(method, ircDatabaseAcl(namespace), body)).andReturn(),
          400,
          "BadRequestException");
    }
    assertIrcError(
        mvc.perform(ircRequest(HttpMethod.DELETE, ircLock(namespace, "t"), null)).andReturn(),
        404,
        "NoSuchTableException");
    assertIrcError(
        mvc.perform(ircRequest(HttpMethod.DELETE, ircLock(namespace, "t") + "/LEGACY", null))
            .andReturn(),
        404,
        "NoSuchTableException");
    assertEquals(Collections.emptyList(), acls.takeDecisions("irc_gu_ml"));
  }

  // ---------------------------------------------------------------- database grants

  /**
   * GRANT, SHOW GRANTS and REVOKE on a database, including one that holds no table: the native
   * database ACL routes do not require the database to exist (so that CREATE TABLE can be granted
   * before the first table), and neither do these.
   */
  @Test
  void grantShowAndRevokeOnADatabaseAreTheNativeOnes() throws Exception {
    twins("dbgrant", false);
    String grantCreate =
        "{\"role\":\"TABLE_CREATOR\",\"principal\":\"u1\",\"operation\":\"GRANT\"}";
    assertEquals(204, databaseAcl("dbgrant", HttpMethod.POST, grantCreate));
    assertEquals(
        Collections.singletonList("u1=TABLE_CREATOR exp=null props=null"),
        acls.stored(ircDb("dbgrant")));
    assertEquals(204, databaseAcl("dbgrant", HttpMethod.POST, GRANT_U2_FULL));
    assertEquals(200, databaseAcl("dbgrant", HttpMethod.GET, null));
    assertEquals(
        204, databaseAcl("dbgrant", HttpMethod.POST, grantCreate.replace("GRANT", "REVOKE")));
    assertEquals(200, databaseAcl("dbgrant", HttpMethod.GET, null));
    // A database that holds nothing.
    assertEquals(204, databaseAcl("dbempty", HttpMethod.POST, grantCreate));
    assertEquals(200, databaseAcl("dbempty", HttpMethod.GET, null));
    assertEquals(1, acls.stored(ircDb("dbempty")).size());
  }

  @Test
  void aDatabaseGrantWithoutUpdateAclIs403AndShowingNeedsNoPrivilegeAsNatively() throws Exception {
    acls.deny(Privileges.UPDATE_ACL);
    assertEquals(403, databaseAcl("dbdenied", HttpMethod.POST, GRANT_U1));
    assertEquals(Collections.emptyList(), acls.stored(ircDb("dbdenied")));
    acls.deny(Privileges.values());
    assertEquals(200, databaseAcl("dbdenied", HttpMethod.GET, null));
  }

  @Test
  void aDatabaseAclBodyTheNativeRouteRefusesIsRefusedTheSameWay() throws Exception {
    for (String body :
        new String[] {
          "{}",
          "{\"role\":\"\",\"principal\":\"u1\",\"operation\":\"GRANT\"}",
          "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\",\"operation\":\"FOO\"}",
          "{\"role\":",
          ""
        }) {
      assertEquals(400, databaseAcl("dbbodies", HttpMethod.POST, body), body);
    }
    assertEquals(Collections.emptyList(), acls.stored(ircDb("dbbodies")));
  }

  // ---------------------------------------------------------------- unlock

  /** UNLOCK and UNLOCK REASON LEGACY remove a LEGACY lock, with one write, as natively. */
  @Test
  void unlockingALegacyLockIsTheNativeUnlock() throws Exception {
    twins("unlock", false);
    lockTwins("unlock", null);
    assertEquals("locked=true reason=LEGACY", state(ircDb("unlock")));
    assertEquals(204, unlock("unlock", null));
    assertEquals("unlocked", state(ircDb("unlock")));
    lockTwins("unlock", null);
    assertEquals(204, unlock("unlock", "LEGACY"));
    assertEquals("unlocked", state(ircDb("unlock")));
  }

  /**
   * A SYSTEM_ONLY lock is removed only with its reason; the other forms are the native 409 with the
   * native message, and leave the lock.
   */
  @Test
  void aSystemOnlyLockIsRemovedOnlyWithItsReasonAsNatively() throws Exception {
    twins("sysonly", false);
    lockTwins("sysonly", "SYSTEM_ONLY");
    assertEquals(409, unlock("sysonly", null));
    assertEquals(409, unlock("sysonly", "LEGACY"));
    assertEquals("locked=true reason=SYSTEM_ONLY", state(ircDb("sysonly")));
    assertEquals(204, unlock("sysonly", "SYSTEM_ONLY"));
    assertEquals("unlocked", state(ircDb("sysonly")));
  }

  @Test
  void unlockingAnUnlockedTableWritesNothingAsNatively() throws Exception {
    twins("nolock", false);
    state(nativeDb("nolock"));
    state(ircDb("nolock"));
    assertEquals(204, unlock("nolock", null));
    assertEquals(204, unlock("nolock", "LEGACY"));
    assertEquals(204, unlock("nolock", "SYSTEM_ONLY"));
    assertEquals(1, version(nativeDb("nolock")));
    assertEquals(1, version(ircDb("nolock")));
  }

  @Test
  void unlockingAMissingTableIs404AndWithoutLockAdminIs403AsNatively() throws Exception {
    assertEquals(404, unlock("nounlock", null));
    assertEquals(404, unlock("nounlock", "SYSTEM_ONLY"));
    twins("lockadmin", false);
    lockTwins("lockadmin", null);
    acls.deny(Privileges.LOCK_ADMIN);
    assertEquals(403, unlock("lockadmin", null));
    assertEquals(403, unlock("lockadmin", "LEGACY"));
    acls.allowAll();
    assertEquals("locked=true reason=LEGACY", state(ircDb("lockadmin")));
  }

  /**
   * A reason that is not a lock reason (the SQL accepts only LEGACY and SYSTEM_ONLY, in any case,
   * and sends it upper-cased) is 400 on both, from the same path-variable conversion. The native
   * answer has an empty body; the IRC error carries a message, which the spec requires.
   */
  @Test
  void anUnknownLockReasonIs400OnBoth() throws Exception {
    twins("reason", false);
    lockTwins("reason", null);
    for (String reason : new String[] {"UNKNOWN", "system_only", "legacy"}) {
      MvcResult nativeResult =
          mvc.perform(
                  MockMvcRequestBuilders.delete(
                      tableUrl(nativeDb("reason"), "t") + "/lock/" + reason))
              .andReturn();
      assertEquals(400, nativeResult.getResponse().getStatus());
      assertEquals("", nativeResult.getResponse().getContentAsString());
      String message =
          assertIrcError(
                  mvc.perform(
                          ircRequest(
                              HttpMethod.DELETE,
                              ircLock(ircDb("reason"), "t") + "/" + reason,
                              null))
                      .andReturn(),
                  400,
                  "BadRequestException")
              .message();
      assertEquals(
          "Invalid reason: " + reason + "; expected one of [LEGACY, SYSTEM_ONLY]", message);
    }
    assertEquals("locked=true reason=LEGACY", state(ircDb("reason")));
  }

  /**
   * Unlocking a replica table is the native 500 (the service refuses it with an unmapped
   * exception). The IRC message is the native message without the exception class name the native
   * 500 body prefixes, as on every IRC route.
   */
  @Test
  void unlockingAReplicaTableIsThe500OnBoth() throws Exception {
    for (String db : new String[] {nativeDb("replica"), ircDb("replica")}) {
      tables.add(new String[] {db, "t"});
      String uuid = UUID.randomUUID().toString();
      Map<String, String> props = new HashMap<>(newTable(db, "t").getTableProperties());
      props.put("openhouse.tableUUID", uuid);
      props.put("openhouse.databaseId", db);
      props.put("openhouse.tableId", "t");
      props.put("openhouse.tableType", "REPLICA_TABLE");
      props.put("openhouse.tableLocation", "/tmp/unused/" + db + "/t-" + uuid + "/metadata.json");
      post(
              mvc,
              newTable(db, "t")
                  .toBuilder()
                  .tableType(TableType.REPLICA_TABLE)
                  .tableProperties(props)
                  .build())
          .andExpect(status().isCreated());
    }
    MvcResult nativeResult =
        mvc.perform(MockMvcRequestBuilders.delete(tableUrl(nativeDb("replica"), "t") + "/lock"))
            .andReturn();
    assertEquals(500, nativeResult.getResponse().getStatus());
    String ircMessage =
        assertIrcError(
                mvc.perform(ircRequest(HttpMethod.DELETE, ircLock(ircDb("replica"), "t"), null))
                    .andReturn(),
                500,
                "ServiceFailureException")
            .message();
    assertEquals(
        nativeMessage(nativeResult.getResponse().getContentAsString())
            .replace(nativeDb("replica"), "DB"),
        "java.lang.UnsupportedOperationException: " + ircMessage.replace(ircDb("replica"), "DB"));
  }

  // ---------------------------------------------------------------- nothing else changed

  /** The native routes answer as before: the IRC routes are additions. */
  @Test
  void theNativeRoutesAreUnchanged() throws Exception {
    twins("nativeok", true);
    mvc.perform(
            MockMvcRequestBuilders.patch(nativeTableAcl(nativeDb("nativeok"), "t"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(GRANT_U1))
        .andExpect(status().isNoContent());
    mvc.perform(MockMvcRequestBuilders.get(nativeTableAcl(nativeDb("nativeok"), "t")))
        .andExpect(status().isOk());
    // The IRC paths do not exist natively, and the native method is not served over IRC.
    assertNotEquals(
        204,
        mvc.perform(
                MockMvcRequestBuilders.patch(IrcHttp.BASE + ircTableAcl(ircDb("nativeok"), "t"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(GRANT_U1))
            .andReturn()
            .getResponse()
            .getStatus());
    assertIrcError(
        mvc.perform(
                MockMvcRequestBuilders.patch(IrcHttp.BASE + ircTableAcl(ircDb("nativeok"), "t"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(GRANT_U1))
            .andReturn(),
        400,
        "BadRequestException");
    assertTrue(
        Objects.equals(
            Collections.emptyList(),
            acls.stored(ircDb("nativeok") + ".t").stream().collect(Collectors.toList())));
  }
}
