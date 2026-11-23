package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.DB;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.READER;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.STRANGER;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.failure;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.row;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.rest.ErrorHandlers;
import org.apache.iceberg.rest.HTTPClient;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTRequest;
import org.apache.iceberg.rest.RESTResponse;
import org.apache.iceberg.rest.auth.AuthManager;
import org.apache.iceberg.rest.auth.AuthManagers;
import org.apache.iceberg.rest.auth.AuthSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * What an Iceberg 1.12 client does now that the service's config lists the spec routes it serves
 * ({@code endpoints}, V10a) and names the OpenHouse routes in {@code openhouse.capabilities}
 * (V5-3). The service's access log ({@code IrcReadTestService}) shows which requests reached it.
 *
 * <p>Before the list, a 1.12 client assumed its default set (read: {@code RESTSessionCatalog}
 * {@code DEFAULT_ENDPOINTS}): existence checks loaded the table or namespace, views were refused
 * locally, and namespace writes, register and transactions were sent and answered 406. Register and
 * metrics reports are served and listed since V6 and V7.
 */
public class SparkIrcEndpointsTest {

  private static final String VIEWS_DB = "irc42_views";

  /**
   * An unmodified Iceberg 1.12 {@link RESTCatalog}, configured as the Spark catalog {@code irc}.
   */
  private static RESTCatalog rest(String principal) throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", IrcSpark.service().uri() + "/iceberg");
    properties.put("token", IrcSpark.service().token(principal));
    RESTCatalog catalog = new RESTCatalog();
    catalog.initialize("probe", properties);
    return catalog;
  }

  /** Every request the service has logged so far, as {@code METHOD path status}. */
  private static List<String> requests() throws IOException, Exception {
    Path access = IrcSpark.service().work().resolve("access");
    List<String> lines = new ArrayList<>();
    try (Stream<Path> files = Files.list(access)) {
      for (Path file : files.collect(Collectors.toList())) {
        lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
      }
    }
    return lines;
  }

  /**
   * Waits for {@code line} to be logged: the access log valve writes a request's line after its
   * response is sent, so a client can read the log before its last request's line is there.
   */
  private static void awaitRequest(String line) throws Exception {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (!requests().contains(line)) {
      if (System.nanoTime() > deadline) {
        Assertions.fail("not logged: " + line + " in " + requests());
      }
      Thread.sleep(50);
    }
  }

  private static List<String> requestsFor(String fragment) throws Exception {
    return requests().stream().filter(l -> l.contains(fragment)).collect(Collectors.toList());
  }

  /** The root cause of {@code failure}, which Spark may wrap. */
  private static Throwable root(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static void assertRefusedLocally(Throwable failure, String endpoint) {
    Throwable root = root(failure);
    Assertions.assertEquals(
        UnsupportedOperationException.class, root.getClass(), String.valueOf(failure));
    Assertions.assertEquals("Server does not support endpoint: " + endpoint, root.getMessage());
  }

  // ---------------------------------------------------------------- existence

  /** {@code tableExists} and {@code namespaceExists} are a HEAD each, not a load. */
  @Test
  void existenceChecksAreHeadRequests() throws Exception {
    try (RESTCatalog catalog = rest(READER)) {
      Assertions.assertTrue(catalog.tableExists(TableIdentifier.of(DB, "flat")));
      Assertions.assertFalse(catalog.tableExists(TableIdentifier.of(DB, "head_probe_missing")));
      Assertions.assertTrue(catalog.namespaceExists(Namespace.of(DB)));
      Assertions.assertFalse(catalog.namespaceExists(Namespace.of("irc42_head_probe")));
    }
    Assertions.assertEquals(
        Collections.singletonList(
            "HEAD /iceberg/v1/namespaces/irc42/tables/head_probe_missing 404"),
        requestsFor("/head_probe_missing"));
    Assertions.assertEquals(
        Collections.singletonList("HEAD /iceberg/v1/namespaces/irc42_head_probe 404"),
        requestsFor("/irc42_head_probe"));
    Assertions.assertTrue(
        requests().contains("HEAD /iceberg/v1/namespaces/irc42/tables/flat 204"), "HEAD flat");
    Assertions.assertTrue(requests().contains("HEAD /iceberg/v1/namespaces/irc42 204"), "HEAD ns");
  }

  /**
   * Spark's {@code DROP TABLE IF EXISTS} on a missing table: Spark resolves the name by loading the
   * table (a GET), then asks whether a view has the name, which is a HEAD.
   */
  @Test
  void sparksDropIfExistsLoadsTheTableThenProbesTheViewWithHead() throws Exception {
    rows("DROP TABLE IF EXISTS irc." + DB + ".head_probe_sql");
    awaitRequest("HEAD /iceberg/v1/namespaces/irc42/views/head_probe_sql 404");
    Assertions.assertEquals(
        Arrays.asList(
            "GET /iceberg/v1/namespaces/irc42/tables/head_probe_sql 404",
            "HEAD /iceberg/v1/namespaces/irc42/views/head_probe_sql 404"),
        requestsFor("/head_probe_sql"));
  }

  // ---------------------------------------------------------------- views

  /**
   * CREATE VIEW, SELECT from it, SHOW VIEWS and DROP VIEW over IRC, in a database whose views gate
   * is open: the view routes are listed, so the 1.12 client uses them.
   */
  @Test
  void aViewCanBeCreatedSelectedAndDropped() throws Exception {
    String t = "irc." + VIEWS_DB + ".t";
    String v = "irc." + VIEWS_DB + ".v";
    try {
      rows("CREATE TABLE " + t + " (id bigint) USING iceberg");
      rows("INSERT INTO " + t + " VALUES (1), (2)");
      rows("CREATE VIEW " + v + " AS SELECT id FROM " + t);
      Assertions.assertEquals(
          Arrays.asList(row(1L), row(2L)), rows("SELECT * FROM " + v + " ORDER BY id"));
      Assertions.assertTrue(
          rows("SHOW VIEWS IN irc." + VIEWS_DB).stream().anyMatch(r -> "v".equals(r.get(1))),
          String.valueOf(rows("SHOW VIEWS IN irc." + VIEWS_DB)));
      // Renaming is not served (D27): refused on the client, no request reaches the service.
      assertRefusedLocally(
          failure("ALTER VIEW " + v + " RENAME TO irc." + VIEWS_DB + ".w"),
          "POST /v1/{prefix}/views/rename");
      Assertions.assertEquals(Collections.emptyList(), requestsFor("/views/rename"));
      rows("DROP VIEW " + v);
      Throwable gone = failure("SELECT * FROM " + v);
      Assertions.assertTrue(
          String.valueOf(gone.getMessage()).contains("TABLE_OR_VIEW_NOT_FOUND"),
          String.valueOf(gone));
    } finally {
      rows("DROP VIEW IF EXISTS " + v);
      rows("DROP TABLE IF EXISTS " + t);
    }
    awaitRequest("DELETE /iceberg/v1/namespaces/irc42_views/tables/t 204");
    List<String> viewRequests = requestsFor("/iceberg/v1/namespaces/" + VIEWS_DB + "/views");
    Assertions.assertTrue(
        viewRequests.contains("POST /iceberg/v1/namespaces/irc42_views/views 200"),
        viewRequests.toString());
    Assertions.assertTrue(
        viewRequests.contains("GET /iceberg/v1/namespaces/irc42_views/views/v 200"),
        viewRequests.toString());
    Assertions.assertTrue(
        viewRequests.contains("DELETE /iceberg/v1/namespaces/irc42_views/views/v 204"),
        viewRequests.toString());
  }

  /**
   * Spark 4.2 lists views with tables in SHOW TABLES, now that the view routes are listed. In a
   * database whose views are disabled (the default) the view list is empty, so SHOW TABLES shows
   * the tables and SHOW VIEWS nothing (the native view list answers 404 "Views are disabled", which
   * would fail both).
   */
  @Test
  void whereViewsAreDisabledShowTablesListsTheTablesAndShowViewsNothing() throws Exception {
    Assertions.assertTrue(
        rows("SHOW TABLES IN irc." + DB).stream().anyMatch(r -> "flat".equals(r.get(1))));
    Assertions.assertEquals(Collections.emptyList(), rows("SHOW VIEWS IN irc." + DB));
    awaitRequest("GET /iceberg/v1/namespaces/irc42/views 200");
  }

  // ---------------------------------------------------------------- unserved routes

  /**
   * Statements for spec routes the service does not serve fail on the client with Iceberg's "does
   * not support endpoint" error, and send nothing (they used to reach the server and get a 406).
   */
  @Test
  void unservedRoutesAreRefusedOnTheClient() throws Exception {
    assertRefusedLocally(
        failure("CREATE NAMESPACE irc.irc42_new_ns"), "POST /v1/{prefix}/namespaces");
    assertRefusedLocally(
        failure("ALTER NAMESPACE irc." + DB + " SET PROPERTIES ('k'='v')"),
        "POST /v1/{prefix}/namespaces/{namespace}/properties");
    try (RESTCatalog catalog = rest(READER)) {
      assertRefusedLocally(
          Assertions.assertThrows(
              UnsupportedOperationException.class,
              () -> catalog.commitTransaction(Collections.emptyList())),
          "POST /v1/{prefix}/transactions/commit");
    }
    for (String path :
        new String[] {"POST /iceberg/v1/namespaces ", "/properties ", "/transactions/commit "}) {
      Assertions.assertEquals(Collections.emptyList(), requestsFor(path), path);
    }
  }

  // ---------------------------------------------------------------- newly served routes

  /**
   * Register is listed (V7), so the 1.12 client sends it (it used to refuse it locally). The server
   * owns locations: metadata that does not lie in the directory OpenHouse assigned to a table of
   * that name is refused, as Iceberg's {@link BadRequestException} with the server's message.
   */
  @Test
  void registerReachesTheServerWhichRefusesAMetadataLocationItDidNotAssign() throws Exception {
    try (RESTCatalog catalog = rest(READER)) {
      BadRequestException refused =
          Assertions.assertThrows(
              BadRequestException.class,
              () ->
                  catalog.registerTable(
                      TableIdentifier.of(DB, "registered"), "/tmp/nowhere/metadata.json"));
      Assertions.assertTrue(
          refused.getMessage().contains("OpenHouse assigns the location of a table"),
          refused.getMessage());
      Assertions.assertFalse(catalog.tableExists(TableIdentifier.of(DB, "registered")));
    }
    awaitRequest("POST /iceberg/v1/namespaces/irc42/register 400");
  }

  /**
   * The metrics route is listed (V6) and the config no longer turns reporting off, so the 1.12
   * client posts the scan report of a SELECT, from a background thread, and the service takes it.
   */
  @Test
  void aSelectPostsItsScanReport() throws Exception {
    Assertions.assertFalse(rows("SELECT * FROM irc." + DB + ".flat").isEmpty());
    awaitRequest("POST /iceberg/v1/namespaces/irc42/tables/flat/metrics 204");
    Assertions.assertEquals(
        Collections.emptyList(),
        requests().stream()
            .filter(line -> line.contains("/metrics ") && !line.endsWith(" 204"))
            .collect(Collectors.toList()));
  }

  // ---------------------------------------------------------------- the OpenHouse routes

  /** The capability names reach the client's merged properties; they are not endpoints. */
  @Test
  void theCatalogPropertiesNameTheOpenHouseCapabilities() throws Exception {
    try (RESTCatalog catalog = rest(READER)) {
      Assertions.assertEquals(
          "policies,grants,unlock", catalog.properties().get("openhouse.capabilities"));
    }
  }

  /** The native ACL request body. */
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

  /** The native ACL response body. */
  static class AclPolicies implements RESTResponse {
    private List<Map<String, Object>> results;

    @Override
    public void validate() {}
  }

  /**
   * The planned capability client's shape on Iceberg 1.12: Iceberg's {@link HTTPClient} and {@link
   * AuthManager} built from the catalog's own properties (URI, token), calling the grants and
   * unlock routes. The seeded tables are not shared, so a grant is the native 400; a revoke, a
   * listing and an unlock of an unlocked table succeed; a denied principal gets the native 403.
   */
  @Test
  void icebergs112HttpClientAndAuthManagerReachTheOpenHouseRoutes() throws Exception {
    String table = "v1/namespaces/" + DB + "/tables/flat/openhouse";
    for (String principal : new String[] {READER, STRANGER}) {
      Map<String, String> properties = new HashMap<>();
      properties.put("uri", IrcSpark.service().uri() + "/iceberg");
      properties.put("token", IrcSpark.service().token(principal));
      try (HTTPClient http = HTTPClient.builder(properties).uri(properties.get("uri")).build();
          AuthManager auth = AuthManagers.loadAuthManager("probe", properties);
          AuthSession session = auth.catalogSession(http, properties)) {
        RESTClient client = http.withAuthSession(session);
        if (principal.equals(STRANGER)) {
          Assertions.assertThrows(
              ForbiddenException.class,
              () ->
                  client.post(
                      table + "/acl-policies",
                      new AclUpdate("TABLE_VIEWER", "u1", "REVOKE"),
                      null,
                      Collections.emptyMap(),
                      ErrorHandlers.defaultErrorHandler()));
          continue;
        }
        AclPolicies shown =
            client.get(
                table + "/acl-policies",
                AclPolicies.class,
                Collections.emptyMap(),
                ErrorHandlers.tableErrorHandler());
        Assertions.assertEquals(Collections.emptyList(), shown.results);
        BadRequestException unshared =
            Assertions.assertThrows(
                BadRequestException.class,
                () ->
                    client.post(
                        table + "/acl-policies",
                        new AclUpdate("TABLE_VIEWER", "u1", "GRANT"),
                        null,
                        Collections.emptyMap(),
                        ErrorHandlers.defaultErrorHandler()));
        Assertions.assertEquals(
            "Malformed request: irc42.flat is not a shared table", unshared.getMessage());
        Assertions.assertNull(
            client.post(
                table + "/acl-policies",
                new AclUpdate("TABLE_VIEWER", "u1", "REVOKE"),
                null,
                Collections.emptyMap(),
                ErrorHandlers.defaultErrorHandler()));
        Assertions.assertNull(
            client.delete(
                table + "/lock",
                AclPolicies.class,
                Collections.emptyMap(),
                ErrorHandlers.defaultErrorHandler()));
        Assertions.assertNull(
            client.delete(
                table + "/lock/SYSTEM_ONLY",
                AclPolicies.class,
                Collections.emptyMap(),
                ErrorHandlers.defaultErrorHandler()));
      }
    }
    awaitRequest("POST /iceberg/v1/namespaces/irc42/tables/flat/openhouse/acl-policies 403");
    List<String> logged = requestsFor("/irc42/tables/flat/openhouse/");
    Assertions.assertTrue(
        logged.contains("POST /iceberg/v1/namespaces/irc42/tables/flat/openhouse/acl-policies 403"),
        logged.toString());
    Assertions.assertTrue(
        logged.contains("DELETE /iceberg/v1/namespaces/irc42/tables/flat/openhouse/lock 204"),
        logged.toString());
    // The seeded table is unchanged: the unlocks of an unlocked table wrote nothing.
    Assertions.assertEquals(
        Collections.singletonList(row(3L)), rows("SELECT count(*) FROM irc." + DB + ".flat"));
  }
}
