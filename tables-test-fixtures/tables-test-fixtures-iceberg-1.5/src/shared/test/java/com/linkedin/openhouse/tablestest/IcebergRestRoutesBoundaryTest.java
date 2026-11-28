package com.linkedin.openhouse.tablestest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The Iceberg REST Catalog (IRC) routes of the embedded server, on the Iceberg line of the build
 * (the fork's 1.12 or LinkedIn's 1.5.2): the reads, writes and views are served on both, the view
 * register only where Iceberg has its class (1.12). The scan planning and credentials routes are
 * not served on either: the embedded server has the default cluster configuration, which leaves
 * scan planning off, and the fixtures' own settings, which turn the Iceberg REST routes on (they
 * are off by default). The expectation is chosen at runtime from the classpath.
 */
public class IcebergRestRoutesBoundaryTest {

  private static ConfigurableApplicationContext context;

  private static int port;

  private static String token;

  @BeforeAll
  public static void start() throws IOException {
    try {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.register();
    } catch (Error e) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    }
    SpringApplication application = new SpringApplication(SpringH2TestApplication.class);
    application.setDefaultProperties(Collections.singletonMap("server.port", "0"));
    context = application.run();
    port = ((WebServerApplicationContext) context).getWebServer().getPort();
    try (InputStream in =
        IcebergRestRoutesBoundaryTest.class.getClassLoader().getResourceAsStream("dummy.token")) {
      token = new String(readAll(in), StandardCharsets.UTF_8).trim();
    }
  }

  @AfterAll
  public static void stop() {
    context.close();
  }

  private static boolean classPresent(String name) {
    try {
      Class.forName(name);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @Test
  public void theReadRoutesAreServedOnEveryIcebergRuntime() throws IOException {
    Assertions.assertEquals(200, call("GET", "/iceberg/v1/config", null));
    Assertions.assertEquals(
        404, call("GET", "/iceberg/v1/namespaces/irc_boundary_db/tables/no_such_table", null));
  }

  @Test
  public void theWriteRoutesAreServed() throws IOException {
    // Served: an empty create body is refused by the route itself (400), not as unserved (406).
    Assertions.assertEquals(
        400, call("POST", "/iceberg/v1/namespaces/irc_boundary_db/tables", "{}"));
    Assertions.assertEquals(
        400, call("POST", "/iceberg/v1/namespaces/irc_boundary_db/register", "{}"));
    Assertions.assertEquals(400, call("POST", "/iceberg/v1/transactions/commit", "{}"));
  }

  /** Spec routes served on every Iceberg line: the reads and the metrics report. */
  private static final List<String> READ_ROUTES =
      Arrays.asList(
          "GET /v1/{prefix}/namespaces",
          "GET /v1/{prefix}/namespaces/{namespace}",
          "HEAD /v1/{prefix}/namespaces/{namespace}",
          "GET /v1/{prefix}/namespaces/{namespace}/tables",
          "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics");

  /**
   * The config lists the spec routes served under this runtime and the OpenHouse capabilities
   * registered: the reads, the metrics report, the table and namespace writes and the views on
   * every line, the view register on 1.12, and scan planning and credentials on 1.12 only where the
   * cluster turns scan planning on, which the embedded server's default configuration does not.
   * Reporting stays off unless the client's own configuration turns it on.
   */
  @Test
  public void theConfigAdvertisesWhatThisIcebergRuntimeServes() throws IOException {
    JsonNode config = new ObjectMapper().readTree(get("/iceberg/v1/config"));
    List<String> endpoints = new ArrayList<>();
    config.path("endpoints").forEach(endpoint -> endpoints.add(endpoint.asText()));
    String capabilities = config.path("overrides").path("openhouse.capabilities").asText();
    Assertions.assertEquals(
        "false",
        config.path("defaults").path("rest-metrics-reporting-enabled").asText(),
        config.toString());
    Assertions.assertTrue(endpoints.containsAll(READ_ROUTES), endpoints.toString());
    Assertions.assertTrue(
        endpoints.contains("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}"),
        endpoints.toString());
    Assertions.assertTrue(
        endpoints.contains("GET /v1/{prefix}/namespaces/{namespace}/views/{view}"),
        endpoints.toString());
    Assertions.assertTrue(
        endpoints.contains("POST /v1/{prefix}/namespaces/{namespace}/register"),
        endpoints.toString());
    Assertions.assertTrue(
        endpoints.contains("POST /v1/{prefix}/transactions/commit"), endpoints.toString());
    // The scan planning and credentials routes are served where Iceberg has their classes (the
    // 1.12 line) and the cluster turns scan planning on; the embedded server's default
    // configuration leaves it off, so here they are served on neither line.
    boolean planning =
        classPresent("org.apache.iceberg.rest.requests.PlanTableScanRequest")
            && Boolean.parseBoolean(
                context.getEnvironment().getProperty("cluster.tables.irc.scan-planning.enabled"));
    Assertions.assertFalse(planning, "scan planning is off in the default configuration");
    Assertions.assertEquals(
        planning,
        endpoints.contains("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan"),
        endpoints.toString());
    Assertions.assertEquals(
        planning,
        endpoints.contains("GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials"),
        endpoints.toString());
    // The view register route is served where Iceberg has its class, RegisterViewRequest (1.12).
    boolean registerView = classPresent("org.apache.iceberg.rest.requests.RegisterViewRequest");
    Assertions.assertEquals(
        registerView,
        endpoints.contains("POST /v1/{prefix}/namespaces/{namespace}/register-view"),
        endpoints.toString());
    // The namespace writes, on every line.
    for (String write :
        Arrays.asList(
            "POST /v1/{prefix}/namespaces",
            "DELETE /v1/{prefix}/namespaces/{namespace}",
            "POST /v1/{prefix}/namespaces/{namespace}/properties")) {
      Assertions.assertTrue(endpoints.contains(write), endpoints.toString());
    }
    Assertions.assertEquals(
        22 + (planning ? 2 : 0) + (registerView ? 1 : 0), endpoints.size(), endpoints.toString());
    Assertions.assertEquals("policies,grants,unlock", capabilities);
  }

  /**
   * The metrics report route is served on every Iceberg runtime: a report for a missing table is
   * the route's own 404, not the 406 of a route that is not served, and a body that is not a report
   * is its 400.
   */
  @Test
  public void theMetricsRouteIsServedOnEveryIcebergRuntime() throws IOException {
    String route = "/iceberg/v1/namespaces/irc_boundary_db/tables/no_such_table/metrics";
    Assertions.assertEquals(
        404,
        call(
            "POST",
            route,
            "{\"report-type\":\"commit-report\",\"table-name\":\"t\",\"snapshot-id\":1,"
                + "\"sequence-number\":1,\"operation\":\"append\",\"metrics\":{}}"));
    Assertions.assertEquals(400, call("POST", route, "{}"));
  }

  /** The OpenHouse grants and unlock routes use no Iceberg API and are served everywhere. */
  @Test
  public void theGrantsAndUnlockRoutesAreServedOnEveryIcebergRuntime() throws IOException {
    String table = "/iceberg/v1/namespaces/irc_boundary_db/tables/no_such_table/openhouse";
    Assertions.assertEquals(404, call("GET", table + "/acl-policies", null));
    Assertions.assertEquals(404, call("DELETE", table + "/lock", null));
    Assertions.assertEquals(404, call("DELETE", table + "/lock/SYSTEM_ONLY", null));
  }

  private static String get(String path) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
    connection.setRequestProperty("Authorization", "Bearer " + token);
    try (InputStream in = connection.getInputStream()) {
      Assertions.assertEquals(200, connection.getResponseCode());
      return new String(readAll(in), StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }
  }

  private static int call(String method, String path, String body) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
    connection.setRequestMethod(method);
    connection.setRequestProperty("Authorization", "Bearer " + token);
    if (body != null) {
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      try (OutputStream out = connection.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      }
    }
    try {
      return connection.getResponseCode();
    } finally {
      connection.disconnect();
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }
}
