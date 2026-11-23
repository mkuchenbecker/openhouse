package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.newTable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.rest.ErrorHandlers;
import org.apache.iceberg.rest.HTTPClient;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTRequest;
import org.apache.iceberg.rest.RESTResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * The grants and unlock routes through Iceberg's own {@link HTTPClient}, the client the planned
 * capability client is built on (it reuses the {@code RESTCatalog}'s URI and bearer token), over a
 * real port: what the client can send, what it parses from the native bodies, and how each refusal
 * surfaces as an exception with each of Iceberg's error handlers. Iceberg 1.5.2, the service's
 * line; the Spark 4.2 module repeats the client half on Iceberg 1.12.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcGrantsUnlockHttpClientTest {
  private static final String DB = "irc_gu_http";

  @LocalServerPort private int port;
  @MockBean AuthorizationHandler authorizationHandler;

  private final RecordingAcls acls = new RecordingAcls();
  private String token;
  private HTTPClient client;

  /** The native request body, written by the client's mapper (kebab-case field names). */
  static class AclUpdate implements RESTRequest {
    private String role;
    private String principal;
    private String operation;

    AclUpdate() {}

    AclUpdate(String role, String principal, String operation) {
      this.role = role;
      this.principal = principal;
      this.operation = operation;
    }

    @Override
    public void validate() {}
  }

  /** The native response body, read by the client's mapper. */
  static class AclPolicies implements RESTResponse {
    private List<AclEntry> results;

    @Override
    public void validate() {}
  }

  static class AclEntry {
    private String principal;
    private String role;

    /** Read under the kebab-case name the client's mapper derives: absent from the native body. */
    private Long expirationEpochTimeSeconds;

    /** The same field read under its native name. */
    @JsonProperty("expirationEpochTimeSeconds")
    private Long expiration;
  }

  @BeforeEach
  void start() throws Exception {
    acls.install(authorizationHandler);
    token = new DummySecurityJWT("irc_granter").buildNoopJWT();
    client = client(token);
    nativeCall("POST", "/v1/databases/" + DB + "/tables/", sharedTable("t"), 201);
    nativeCall("POST", "/v1/databases/" + DB + "/tables/", sharedTable("u"), 201);
  }

  @AfterEach
  void stop() throws Exception {
    acls.allowAll();
    for (String t : new String[] {"t", "u"}) {
      nativeCall("DELETE", "/v1/databases/" + DB + "/tables/" + t + "/lock/SYSTEM_ONLY", null, -1);
      nativeCall("DELETE", "/v1/databases/" + DB + "/tables/" + t, null, -1);
    }
    client.close();
  }

  private HTTPClient client(String bearer) {
    HTTPClient.Builder builder =
        HTTPClient.builder(Collections.emptyMap()).uri("http://localhost:" + port + "/iceberg");
    if (bearer != null) {
      builder.withHeader("Authorization", "Bearer " + bearer);
    }
    return builder.build();
  }

  private static String sharedTable(String table) {
    return newTable(DB, table)
        .toBuilder()
        .policies(Policies.builder().sharingEnabled(true).build())
        .build()
        .toJson();
  }

  private void nativeCall(String method, String path, String body, int expected) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token);
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (expected > 0) {
      assertEquals(expected, response.statusCode(), response.body());
    }
  }

  private static String tableAcl(String table) {
    return "v1/namespaces/" + DB + "/tables/" + table + "/openhouse/acl-policies";
  }

  private static String lock(String table) {
    return "v1/namespaces/" + DB + "/tables/" + table + "/openhouse/lock";
  }

  private static final Map<String, String> NO_HEADERS = Collections.emptyMap();

  private static final Consumer<ErrorResponse> DEFAULT = ErrorHandlers.defaultErrorHandler();
  private static final Consumer<ErrorResponse> TABLE = ErrorHandlers.tableErrorHandler();
  private static final Consumer<ErrorResponse> NAMESPACE = ErrorHandlers.namespaceErrorHandler();

  /**
   * GRANT, SHOW GRANTS, REVOKE (table and database) and UNLOCK (both reasons) with the client's own
   * methods: a POST with a JSON body, a GET, a DELETE. A 204 is a null response.
   */
  @Test
  void everyOperationIsReachableWithIcebergsHttpClient() throws Exception {
    assertNull(
        client.post(
            tableAcl("t"),
            new AclUpdate("TABLE_VIEWER", "u1", "GRANT"),
            AclPolicies.class,
            NO_HEADERS,
            TABLE));
    assertEquals(
        Collections.singletonList("u1=TABLE_VIEWER exp=null props=null"), acls.stored(DB + ".t"));
    AclPolicies shown = client.get(tableAcl("t"), AclPolicies.class, NO_HEADERS, TABLE);
    assertEquals(1, shown.results.size());
    assertEquals("u1", shown.results.get(0).principal);
    assertEquals("TABLE_VIEWER", shown.results.get(0).role);
    client.post(
        tableAcl("t"), new AclUpdate("TABLE_VIEWER", "u1", "REVOKE"), null, NO_HEADERS, TABLE);
    assertEquals(Collections.emptyList(), acls.stored(DB + ".t"));

    String databaseAcl = "v1/namespaces/" + DB + "/openhouse/acl-policies";
    client.post(
        databaseAcl, new AclUpdate("TABLE_CREATOR", "u1", "GRANT"), null, NO_HEADERS, NAMESPACE);
    assertEquals(
        "u1",
        client.get(databaseAcl, AclPolicies.class, NO_HEADERS, NAMESPACE).results.get(0).principal);

    nativeCall(
        "POST",
        "/v1/databases/" + DB + "/tables/t/lock",
        "{\"locked\":true,\"message\":\"m\"}",
        201);
    assertNull(client.delete(lock("t"), AclPolicies.class, NO_HEADERS, TABLE));
    nativeCall(
        "POST",
        "/v1/databases/" + DB + "/tables/t/lock",
        "{\"locked\":true,\"reason\":\"SYSTEM_ONLY\"}",
        201);
    assertNull(client.delete(lock("t") + "/SYSTEM_ONLY", AclPolicies.class, NO_HEADERS, TABLE));
  }

  /**
   * The native body's multi-word field is camel-case; the client's mapper reads kebab-case names,
   * so a plain field misses it and a field annotated with the native name reads it. (Single-word
   * fields, all that GRANT and SHOW GRANTS use, read either way.)
   */
  @Test
  void theClientsMapperReadsCamelCaseFieldsOnlyUnderTheirNativeName() throws Exception {
    String full =
        "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u2\",\"operation\":\"GRANT\","
            + "\"expirationEpochTimeSeconds\":1707791165}";
    nativeCall("PATCH", "/v1/databases/" + DB + "/tables/t/aclPolicies", full, 204);
    AclEntry entry = client.get(tableAcl("t"), AclPolicies.class, NO_HEADERS, TABLE).results.get(0);
    assertNull(entry.expirationEpochTimeSeconds);
    assertEquals(1707791165L, entry.expiration);
  }

  /** How each refusal surfaces, by status and error handler. */
  @Test
  void refusalsSurfaceAsIcebergExceptionsWithTheNativeMessage() throws Exception {
    nativeCall(
        "PATCH",
        "/v1/databases/" + DB + "/tables/u/aclPolicies",
        "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\",\"operation\":\"GRANT\"}",
        204);
    // 400 (a grant on a locked table): BadRequestException with every handler.
    nativeCall(
        "POST",
        "/v1/databases/" + DB + "/tables/u/lock",
        "{\"locked\":true,\"message\":\"m\"}",
        201);
    BadRequestException locked =
        assertThrows(
            BadRequestException.class,
            () ->
                client.post(
                    tableAcl("u"),
                    new AclUpdate("TABLE_VIEWER", "u3", "GRANT"),
                    null,
                    NO_HEADERS,
                    TABLE));
    assertEquals(
        "Malformed request: " + DB + ".u is in locked state and grants are not allowed for sharing",
        locked.getMessage());
    nativeCall("DELETE", "/v1/databases/" + DB + "/tables/u/lock", null, 204);

    // 400 (an operation the body does not allow), with the native message.
    assertEquals(
        "Malformed request: Invalid enum value: 'FOO' for the field: 'operation'. "
            + "The value must be one of: [GRANT, REVOKE].",
        assertThrows(
                BadRequestException.class,
                () ->
                    client.post(
                        tableAcl("u"),
                        new AclUpdate("TABLE_VIEWER", "u3", "FOO"),
                        null,
                        NO_HEADERS,
                        DEFAULT))
            .getMessage());

    // 403
    acls.deny(Privileges.UPDATE_ACL);
    ForbiddenException denied =
        assertThrows(
            ForbiddenException.class,
            () ->
                client.post(
                    tableAcl("u"),
                    new AclUpdate("TABLE_VIEWER", "u3", "GRANT"),
                    null,
                    NO_HEADERS,
                    TABLE));
    org.junit.jupiter.api.Assertions.assertTrue(
        denied.getMessage().startsWith("Forbidden: ")
            && denied.getMessage().contains("irc_granter")
            && denied.getMessage().contains("unauthorized"),
        denied.getMessage());
    acls.allowAll();

    // 404: the table handler names the table; the default handler has no 404 case.
    NoSuchTableException missing =
        assertThrows(
            NoSuchTableException.class,
            () -> client.get(tableAcl("nope"), AclPolicies.class, NO_HEADERS, TABLE));
    assertEquals("User table " + DB + ".nope cannot be found", missing.getMessage());
    assertEquals(
        "Unable to process: User table " + DB + ".nope cannot be found",
        assertThrows(
                RESTException.class,
                () -> client.get(tableAcl("nope"), AclPolicies.class, NO_HEADERS, DEFAULT))
            .getMessage());

    // 409 (an unlock that names the wrong reason): the table handler reads every 409 as "already
    // exists", so a capability client should not use it for unlock.
    nativeCall(
        "POST",
        "/v1/databases/" + DB + "/tables/u/lock",
        "{\"locked\":true,\"reason\":\"SYSTEM_ONLY\"}",
        201);
    String conflict =
        "Table " + DB + ".u has a SYSTEM_ONLY lock. Remove it with reason SYSTEM_ONLY.";
    assertEquals(
        conflict,
        assertThrows(
                AlreadyExistsException.class,
                () -> client.delete(lock("u"), AclPolicies.class, NO_HEADERS, TABLE))
            .getMessage());
    assertEquals(
        "Unable to process: " + conflict,
        assertThrows(
                RESTException.class,
                () -> client.delete(lock("u"), AclPolicies.class, NO_HEADERS, DEFAULT))
            .getMessage());

    // 400 (not a lock reason)
    assertEquals(
        "Malformed request: Invalid reason: NONE; expected one of [LEGACY, SYSTEM_ONLY]",
        assertThrows(
                BadRequestException.class,
                () -> client.delete(lock("u") + "/NONE", AclPolicies.class, NO_HEADERS, TABLE))
            .getMessage());

    // 401 without the bearer token
    try (HTTPClient anonymous = client(null)) {
      assertThrows(
          NotAuthorizedException.class,
          () -> anonymous.get(tableAcl("u"), AclPolicies.class, NO_HEADERS, TABLE));
    }
  }

  /** A 500 (unlocking a replica table) is ServiceFailureException with the type and message. */
  @Test
  void aServerFailureIsServiceFailureException() {
    // The replica case is pinned in IrcGrantsUnlockRoutesTest; here the handler's own failure.
    org.mockito.Mockito.doThrow(new IllegalStateException("store down"))
        .when(authorizationHandler)
        .listAclPolicies(
            org.mockito.ArgumentMatchers.any(com.linkedin.openhouse.tables.model.TableDto.class));
    assertEquals(
        "Server error: ServiceFailureException: store down",
        assertThrows(
                ServiceFailureException.class,
                () -> client.get(tableAcl("t"), AclPolicies.class, NO_HEADERS, TABLE))
            .getMessage());
  }

  /**
   * The client's public request methods: no PATCH and no PUT, which is why the native ACL update (a
   * PATCH) gets an IRC POST.
   */
  @Test
  void icebergsRestClientHasNoPatchOrPut() {
    TreeSet<String> methods =
        Arrays.stream(RESTClient.class.getMethods())
            .filter(m -> !Modifier.isStatic(m.getModifiers()))
            .map(Method::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(
        new TreeSet<>(Arrays.asList("close", "delete", "get", "head", "post", "postForm")),
        methods);
  }
}
