package com.linkedin.openhouse.capabilities.rest;

import com.linkedin.openhouse.capabilities.AclPolicy;
import com.linkedin.openhouse.capabilities.UnlockReason;
import com.linkedin.openhouse.capabilities.UnlockTableTarget;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The REST capability client against a stub of the tables service's IRC base path: for each
 * statement, the method, path, headers and body it sends, what it reads back, and how each error
 * status reaches the caller. The same tests run on the Iceberg line the build compiles against
 * (1.5.2 by default, 1.12 under {@code -PicebergFork}), so they also show the two transports behave
 * alike.
 */
class RestCapabilityClientTest {
  private static final String TABLE_ACL =
      "/iceberg/v1/namespaces/db/tables/t/openhouse/acl-policies";
  private static final String DATABASE_ACL = "/iceberg/v1/namespaces/db/openhouse/acl-policies";
  private static final String LOCK = "/iceberg/v1/namespaces/db/tables/t/openhouse/lock";
  private static final TableIdentifier T = TableIdentifier.of("db", "t");

  private StubIrcServer server;

  @BeforeEach
  void start() throws Exception {
    server = new StubIrcServer();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  /** The catalog properties a user sets for {@code SparkCatalog type=rest}, plus a header. */
  private Map<String, String> properties() {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", server.uri());
    properties.put("token", "the-token");
    properties.put("header.X-Client-Name", "spark-sql");
    return properties;
  }

  private RestCapabilityClient client() {
    return new RestCapabilityClient("irc", properties());
  }

  private StubIrcServer.Request only() {
    List<StubIrcServer.Request> requests = server.requests();
    Assertions.assertEquals(1, requests.size(), requests.toString());
    return requests.get(0);
  }

  private static void assertCatalogHeaders(StubIrcServer.Request request) {
    Assertions.assertEquals(
        "Bearer the-token", request.header("Authorization"), request.headers.toString());
    Assertions.assertEquals(
        "spark-sql", request.header("X-Client-Name"), request.headers.toString());
  }

  // ------------------------------------------------------------------ requests

  @Test
  void grantOnATableIsAPostOfTheNativeBody() {
    client().updateTableAclPolicies(T, true, "SELECT", "u1");
    StubIrcServer.Request request = only();
    Assertions.assertEquals("POST " + TABLE_ACL, request.toString());
    Assertions.assertEquals(
        "{\"role\":\"TABLE_VIEWER\",\"principal\":\"u1\",\"operation\":\"GRANT\"}", request.body);
    Assertions.assertTrue(
        request.header("Content-Type").startsWith("application/json"), request.headers.toString());
    assertCatalogHeaders(request);
  }

  @Test
  void revokeOnADatabaseIsAPostOfTheNativeBody() {
    client().updateDatabaseAclPolicies(Namespace.of("db"), false, "CREATE TABLE", "*");
    StubIrcServer.Request request = only();
    Assertions.assertEquals("POST " + DATABASE_ACL, request.toString());
    Assertions.assertEquals(
        "{\"role\":\"TABLE_CREATOR\",\"principal\":\"*\",\"operation\":\"REVOKE\"}", request.body);
    assertCatalogHeaders(request);
  }

  @Test
  void everyPrivilegeMapsToTheRoleTheJavaClientSends() {
    RestCapabilityClient client = client();
    for (String privilege :
        Arrays.asList("SELECT", "DESCRIBE", "MANAGE GRANTS", "ALTER", "CREATE TABLE")) {
      client.updateTableAclPolicies(T, true, privilege, "u");
    }
    Assertions.assertEquals(
        Arrays.asList("TABLE_VIEWER", "TABLE_VIEWER", "ACL_EDITOR", "TABLE_ADMIN", "TABLE_CREATOR"),
        server.requests().stream()
            .map(r -> r.body.replaceAll(".*\"role\":\"([A-Z_]+)\".*", "$1"))
            .collect(Collectors.toList()));
  }

  @Test
  void showGrantsReadsTheNativeResponseAndNamesEachRolesPrivilege() {
    String body =
        "{\"results\":[{\"principal\":\"*\",\"role\":\"TABLE_VIEWER\","
            + "\"expirationEpochTimeSeconds\":1707791165,\"properties\":{\"k\":\"v\"}},"
            + "{\"principal\":\"u2\",\"role\":\"ACL_EDITOR\"},"
            + "{\"principal\":\"u3\",\"role\":\"TABLE_ADMIN\"}]}";
    server.answer("GET", TABLE_ACL, 200, body);
    server.answer(
        "GET",
        DATABASE_ACL,
        200,
        "{\"results\":[{\"principal\":\"u4\",\"role\":\"TABLE_CREATOR\"}]}");
    RestCapabilityClient client = client();
    Assertions.assertEquals(
        Arrays.asList(
            new AclPolicy("SELECT", "*"),
            new AclPolicy("MANAGE GRANTS", "u2"),
            new AclPolicy("ALTER", "u3")),
        client.getTableAclPolicies(T));
    Assertions.assertEquals(
        Collections.singletonList(new AclPolicy("CREATE TABLE", "u4")),
        client.getDatabaseAclPolicies(Namespace.of("db")));
    List<StubIrcServer.Request> requests = server.requests();
    Assertions.assertEquals(
        "[GET " + TABLE_ACL + ", GET " + DATABASE_ACL + "]", requests.toString());
    requests.forEach(RestCapabilityClientTest::assertCatalogHeaders);
  }

  @Test
  void anEmptyOrMissingResultListIsNoGrants() {
    server.answer("GET", TABLE_ACL, 200, "{\"results\":[]}");
    server.answer("GET", DATABASE_ACL, 200, "{}");
    Assertions.assertEquals(Collections.emptyList(), client().getTableAclPolicies(T));
    Assertions.assertEquals(
        Collections.emptyList(), client().getDatabaseAclPolicies(Namespace.of("db")));
  }

  @Test
  void unlockWithoutAReasonDeletesTheLegacyLockAndWithAReasonNamesIt() throws Exception {
    RestCapabilityClient client = client();
    UnlockTableTarget target = UnlockTableTarget.of(new String[] {"db"}, "t");
    client.unlockTable(target, UnlockReason.LEGACY);
    client.unlockTable(target, UnlockReason.SYSTEM_ONLY);
    List<StubIrcServer.Request> requests = server.requests();
    Assertions.assertEquals(
        "[DELETE " + LOCK + ", DELETE " + LOCK + "/SYSTEM_ONLY]", requests.toString());
    requests.forEach(RestCapabilityClientTest::assertCatalogHeaders);
    requests.forEach(r -> Assertions.assertEquals("", r.body));
  }

  @Test
  void theCatalogsPrefixIsPartOfEveryPath() throws Exception {
    Map<String, String> properties = properties();
    properties.put("prefix", "warehouse-1");
    RestCapabilityClient client = new RestCapabilityClient("irc", properties);
    client.updateTableAclPolicies(T, true, "SELECT", "u");
    client.updateDatabaseAclPolicies(Namespace.of("db"), true, "CREATE TABLE", "u");
    client.unlockTable(UnlockTableTarget.of(new String[] {"db"}, "t"), UnlockReason.LEGACY);
    Assertions.assertEquals(
        Arrays.asList(
            "POST /iceberg/v1/warehouse-1/namespaces/db/tables/t/openhouse/acl-policies",
            "POST /iceberg/v1/warehouse-1/namespaces/db/openhouse/acl-policies",
            "DELETE /iceberg/v1/warehouse-1/namespaces/db/tables/t/openhouse/lock"),
        server.requests().stream().map(Object::toString).collect(Collectors.toList()));
  }

  @Test
  void withoutATokenOrCredentialNoAuthorizationIsSent() {
    Map<String, String> properties = properties();
    properties.remove("token");
    new RestCapabilityClient("irc", properties).updateTableAclPolicies(T, true, "SELECT", "u");
    Assertions.assertNull(only().header("Authorization"));
  }

  @Test
  void anOAuthCredentialIsExchangedForATokenAsTheCatalogDoes() {
    server.answer(
        "POST",
        "/iceberg/v1/oauth/tokens",
        200,
        "{\"access_token\":\"fetched-token\",\"token_type\":\"bearer\","
            + "\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\","
            + "\"expires_in\":3600}");
    Map<String, String> properties = properties();
    properties.remove("token");
    properties.put("credential", "client:secret");
    new RestCapabilityClient("irc", properties).updateTableAclPolicies(T, true, "SELECT", "u");
    List<StubIrcServer.Request> requests = server.requests();
    Assertions.assertEquals(
        "[POST /iceberg/v1/oauth/tokens, POST " + TABLE_ACL + "]", requests.toString());
    Assertions.assertTrue(
        requests.get(0).body.contains("grant_type=client_credentials"), requests.get(0).body);
    Assertions.assertEquals("Bearer fetched-token", requests.get(1).header("Authorization"));
    Assertions.assertEquals("spark-sql", requests.get(1).header("X-Client-Name"));
  }

  // ------------------------------------------------------------------ checks before any request

  @Test
  void aMultiLevelNamespaceIsRefusedAsTheJavaClientRefusesIt() {
    RestCapabilityClient client = client();
    TableIdentifier deep = TableIdentifier.of("a", "b", "t");
    for (Runnable call :
        Arrays.<Runnable>asList(
            () -> client.updateTableAclPolicies(deep, true, "SELECT", "u"),
            () -> client.getTableAclPolicies(deep),
            () -> client.updateDatabaseAclPolicies(Namespace.of("a", "b"), true, "SELECT", "u"),
            () -> client.getDatabaseAclPolicies(Namespace.of("a", "b")))) {
      ValidationException refused = Assertions.assertThrows(ValidationException.class, call::run);
      Assertions.assertEquals("Input namespace has more than one levels a.b", refused.getMessage());
    }
    Assertions.assertEquals(Collections.emptyList(), server.requests());
  }

  @Test
  void anUnknownPrivilegeIsRefusedAsTheJavaClientRefusesIt() {
    Assertions.assertThrows(
        NoSuchElementException.class,
        () -> client().updateTableAclPolicies(T, true, "SELECT (col)", "u"));
    Assertions.assertEquals(Collections.emptyList(), server.requests());
  }

  // ------------------------------------------------------------------ errors

  @Test
  void aBadRequestIsIllegalArgumentExceptionWithTheNativeMessage() {
    server.error("POST", TABLE_ACL, 400, "BadRequestException", "db.t is not a shared table");
    IllegalArgumentException refused =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> client().updateTableAclPolicies(T, true, "SELECT", "u"));
    Assertions.assertEquals("db.t is not a shared table", refused.getMessage());
  }

  @Test
  void aMissingTokenIsNotAuthorized() {
    server.error("GET", TABLE_ACL, 401, "NotAuthorizedException", "No token");
    NotAuthorizedException refused =
        Assertions.assertThrows(
            NotAuthorizedException.class, () -> client().getTableAclPolicies(T));
    Assertions.assertEquals("Not authorized: No token", refused.getMessage());
  }

  @Test
  void aDeniedPrincipalIsForbiddenWithTheNativeMessage() {
    String message = "Operation on table db.t failed as user u is unauthorized";
    server.error("POST", TABLE_ACL, 403, "AccessDeniedException", message);
    server.error("POST", DATABASE_ACL, 403, "AccessDeniedException", message);
    server.error("DELETE", LOCK, 403, "AccessDeniedException", message);
    RestCapabilityClient client = client();
    for (Runnable call :
        Arrays.<Runnable>asList(
            () -> client.updateTableAclPolicies(T, true, "SELECT", "u"),
            () -> client.updateDatabaseAclPolicies(Namespace.of("db"), true, "SELECT", "u"),
            () -> unlock(client, UnlockReason.LEGACY))) {
      Assertions.assertEquals(
          "Forbidden: " + message,
          Assertions.assertThrows(ForbiddenException.class, call::run).getMessage());
    }
  }

  @Test
  void aMissingTableIsNoSuchTableExceptionWithTheNativeMessage() {
    String message = "User table db.t cannot be found";
    server.error("POST", TABLE_ACL, 404, "NoSuchUserTableException", message);
    server.error("GET", TABLE_ACL, 404, "NoSuchUserTableException", message);
    server.error("DELETE", LOCK, 404, "NoSuchUserTableException", message);
    RestCapabilityClient client = client();
    for (Runnable call :
        Arrays.<Runnable>asList(
            () -> client.updateTableAclPolicies(T, true, "SELECT", "u"),
            () -> client.getTableAclPolicies(T),
            () -> unlock(client, UnlockReason.LEGACY))) {
      Assertions.assertEquals(
          message, Assertions.assertThrows(NoSuchTableException.class, call::run).getMessage());
    }
  }

  @Test
  void aLockConflictIsIllegalStateExceptionWithTheNativeMessage() {
    // Not Iceberg's table handler, which would read every 409 as "already exists".
    String message = "Table db.t has a SYSTEM_ONLY lock. Remove it with reason SYSTEM_ONLY.";
    server.error("DELETE", LOCK, 409, "LockConflictException", message);
    IllegalStateException conflict =
        Assertions.assertThrows(
            IllegalStateException.class, () -> unlock(client(), UnlockReason.LEGACY));
    Assertions.assertEquals(message, conflict.getMessage());
  }

  @Test
  void aServerFailureIsServiceFailureException() {
    server.error("DELETE", LOCK + "/SYSTEM_ONLY", 500, "UnsupportedOperationException", "replica");
    ServiceFailureException failure =
        Assertions.assertThrows(
            ServiceFailureException.class, () -> unlock(client(), UnlockReason.SYSTEM_ONLY));
    Assertions.assertEquals(
        "Server error: UnsupportedOperationException: replica", failure.getMessage());
  }

  private static void unlock(RestCapabilityClient client, UnlockReason reason) {
    try {
      client.unlockTable(UnlockTableTarget.of(new String[] {"db"}, "t"), reason);
    } catch (com.linkedin.openhouse.capabilities.UnlockValidationException e) {
      throw new AssertionError(e);
    }
  }
}
