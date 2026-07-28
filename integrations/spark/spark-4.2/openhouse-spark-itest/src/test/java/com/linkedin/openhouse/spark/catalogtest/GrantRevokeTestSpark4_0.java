package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;

/**
 * Spark-4.0 / Iceberg-1.11 / REST-first verification of {@code GRANT} / {@code REVOKE} / {@code
 * SHOW GRANTS} DDL.
 *
 * <p>Design: the REST-lane Spark catalog is the stock {@code RESTCatalog}, which (unlike the legacy
 * custom {@code OpenHouseCatalog}) does NOT implement {@code SupportsGrantRevoke}. The Spark-4.0
 * execs therefore call the OpenHouse server ACL endpoint ({@code /v1/databases/.../aclPolicies})
 * directly over HTTP (see {@code docs/spark4-iceberg-upgrade/grant-revoke-rest-lane.md}).
 *
 * <p>Feasibility: the embedded OpenHouse server's only {@code AuthorizationHandler} bean is {@code
 * OpaAuthorizationHandler}, which requires an external OPA service. With no OPA base URI configured
 * (the embedded default) {@code grantRole}/{@code revokeRole} are no-ops and {@code
 * listAclPolicies} returns an empty list — so a full in-JVM ACL round-trip (grant then observe it
 * in SHOW GRANTS) is impossible. This test therefore matches the LEGACY client-contract bar (as the
 * spark-3.1 {@code GrantStatementTest} did with a mock dispatcher):
 *
 * <ol>
 *   <li><b>Real embedded server</b> ({@link #testGrantRevokeAgainstEmbeddedServerSucceeds}): GRANT
 *       / REVOKE against the actual server return non-4xx (HTTP 204) — proving the exec derives the
 *       right URI + token, maps the privilege to the right role, and hits the real endpoint.
 *   <li><b>Capturing stub</b> ({@link #testGrantEmitsCorrectPatchRequest} et al.): a local {@code
 *       HttpServer} captures the exact request, proving operation + role + principal + path are
 *       correct and that SHOW GRANTS parses server rows and reverse-maps role to privilege.
 * </ol>
 */
public class GrantRevokeTestSpark4_0 extends OpenHouseRestSparkITest {

  private static final String DATABASE = "grant_revoke";

  // ---------------------------------------------------------------------------------------------
  // (1) Real embedded-server client-contract bar: the exec hits the actual /aclPolicies endpoint.
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testGrantRevokeAgainstEmbeddedServerSucceeds() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String t = "openhouse." + DATABASE + ".grant_embedded";
      spark.sql("CREATE TABLE " + t + " (name string)");
      spark.sql("INSERT INTO " + t + " VALUES ('foo')");
      // The server only accepts ACL updates on a shared table, so enable sharing first.
      spark.sql("ALTER TABLE " + t + " SET POLICY (SHARING=TRUE)");

      // The embedded server accepts the PATCH (mapped role passes validation) and returns 204.
      assertDoesNotThrow(() -> spark.sql("GRANT SELECT ON TABLE " + t + " TO someuser"));
      assertDoesNotThrow(() -> spark.sql("GRANT ALTER ON TABLE " + t + " TO admin_user"));
      assertDoesNotThrow(() -> spark.sql("REVOKE SELECT ON TABLE " + t + " FROM someuser"));

      // SHOW GRANTS issues the GET and returns the (privilege, principal) schema. The embedded
      // server has no OPA store, so the list is empty, but the DDL executes end-to-end.
      List<Row> rows =
          assertDoesNotThrow(() -> spark.sql("SHOW GRANTS ON TABLE " + t).collectAsList());
      assertNotNull(rows);
      List<String> cols =
          Arrays.asList(spark.sql("SHOW GRANTS ON TABLE " + t).schema().fieldNames());
      assertEquals(Arrays.asList("privilege", "principal"), cols);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // (2) Capturing-stub bar: prove the EXACT request bytes and SHOW GRANTS row parsing.
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testGrantEmitsCorrectPatchRequest() throws Exception {
    CapturingAclServer stub = new CapturingAclServer();
    try {
      try (SparkSession spark = wireStubCatalog(stub)) {
        spark.sql("GRANT SELECT ON TABLE stubcat." + DATABASE + ".t TO someuser");

        assertEquals("PATCH", stub.lastMethod);
        assertEquals("/v1/databases/" + DATABASE + "/tables/t/aclPolicies", stub.lastPath);
        assertTrue(stub.lastBody.contains("\"operation\":\"GRANT\""), stub.lastBody);
        assertTrue(stub.lastBody.contains("\"role\":\"TABLE_VIEWER\""), stub.lastBody);
        assertTrue(stub.lastBody.contains("\"principal\":\"someuser\""), stub.lastBody);
        assertEquals("Bearer stub-token", stub.lastAuth);
      }
    } finally {
      stub.stop();
    }
  }

  @Test
  public void testGrantAlterMapsToTableAdmin() throws Exception {
    CapturingAclServer stub = new CapturingAclServer();
    try {
      try (SparkSession spark = wireStubCatalog(stub)) {
        spark.sql("GRANT ALTER ON TABLE stubcat." + DATABASE + ".t TO admin_user");
        assertTrue(stub.lastBody.contains("\"role\":\"TABLE_ADMIN\""), stub.lastBody);
        assertTrue(stub.lastBody.contains("\"principal\":\"admin_user\""), stub.lastBody);
      }
    } finally {
      stub.stop();
    }
  }

  @Test
  public void testRevokeEmitsRevokeOperation() throws Exception {
    CapturingAclServer stub = new CapturingAclServer();
    try {
      try (SparkSession spark = wireStubCatalog(stub)) {
        spark.sql("REVOKE SELECT ON TABLE stubcat." + DATABASE + ".t FROM someuser");
        assertEquals("PATCH", stub.lastMethod);
        assertTrue(stub.lastBody.contains("\"operation\":\"REVOKE\""), stub.lastBody);
        assertTrue(stub.lastBody.contains("\"role\":\"TABLE_VIEWER\""), stub.lastBody);
      }
    } finally {
      stub.stop();
    }
  }

  @Test
  public void testShowGrantsParsesServerRows() throws Exception {
    CapturingAclServer stub = new CapturingAclServer();
    // Server returns one policy: role TABLE_VIEWER for principal alice -> reverse-maps to SELECT.
    stub.getResponseBody = "{\"results\":[{\"principal\":\"alice\",\"role\":\"TABLE_VIEWER\"}]}";
    try {
      try (SparkSession spark = wireStubCatalog(stub)) {
        List<Row> rows =
            spark.sql("SHOW GRANTS ON TABLE stubcat." + DATABASE + ".t").collectAsList();
        assertEquals(1, rows.size());
        assertEquals("SELECT", rows.get(0).getString(0));
        assertEquals("alice", rows.get(0).getString(1));
        assertEquals("GET", stub.lastMethod);
        assertEquals("/v1/databases/" + DATABASE + "/tables/t/aclPolicies", stub.lastPath);
      }
    } finally {
      stub.stop();
    }
  }

  /**
   * Builds a Spark session with an extra catalog {@code stubcat} pointing at the capturing stub, so
   * GRANT/REVOKE/SHOW GRANTS on {@code stubcat.*} target the stub rather than the embedded server.
   * The stub answers the Iceberg REST {@code /iceberg/v1/config} needed for catalog init.
   */
  private SparkSession wireStubCatalog(CapturingAclServer stub) {
    SparkSession spark = getSparkSession();
    String prefix = "spark.sql.catalog.stubcat";
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".catalog-impl", "org.apache.iceberg.rest.RESTCatalog");
    spark.conf().set(prefix + ".uri", stub.baseUri() + "/iceberg");
    spark.conf().set(prefix + ".token", "stub-token");
    return spark;
  }

  /**
   * Minimal {@link HttpServer} that: (a) answers {@code GET /iceberg/v1/config} so a stock {@code
   * RESTCatalog} initializes, and (b) captures the {@code /aclPolicies} PATCH/GET request.
   */
  private static final class CapturingAclServer {
    private final HttpServer server;
    volatile String lastMethod;
    volatile String lastPath;
    volatile String lastBody;
    volatile String lastAuth;
    // Default: empty ACL list. Overridable per-test to exercise SHOW GRANTS row parsing.
    volatile String getResponseBody = "{\"results\":[]}";

    CapturingAclServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    String baseUri() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      if (path.startsWith("/iceberg/v1/config")) {
        respond(exchange, 200, "{\"defaults\":{},\"overrides\":{}}");
        return;
      }
      if (path.endsWith("/aclPolicies")) {
        lastMethod = method;
        lastPath = path;
        lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
        lastBody = IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8.name());
        if ("GET".equals(method)) {
          respond(exchange, 200, getResponseBody);
        } else {
          // PATCH grant/revoke -> 204 No Content (matches the real server's success contract).
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        }
        return;
      }
      respond(exchange, 404, "{}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }
  }
}
