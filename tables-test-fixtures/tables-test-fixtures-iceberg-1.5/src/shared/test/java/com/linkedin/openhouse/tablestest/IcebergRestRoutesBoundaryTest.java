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
 * Guards the Iceberg 1.2 / 1.5 boundary of the Iceberg REST Catalog (IRC) routes. Both fixtures
 * compile and run this, so the expectation is chosen at runtime from the classpath.
 *
 * <p>The IRC write routes need {@code org.apache.iceberg.UpdateRequirement}, a top-level class only
 * from Iceberg 1.4 on. Under Iceberg 1.2 the embedded server must still start and serve the read
 * routes, and answer the write routes 406 (not served), as it did before they existed; under 1.5
 * the write routes are served.
 */
public class IcebergRestRoutesBoundaryTest {

  private static final String WRITE_API_CLASS = "org.apache.iceberg.UpdateRequirement";

  private static final String WRITE_HANDLER_CLASS =
      "com.linkedin.openhouse.tables.api.irc.IcebergRestWriteHandler";

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

  private static boolean icebergWriteApiPresent() {
    try {
      Class.forName(WRITE_API_CLASS);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @Test
  public void theWriteHandlerExistsOnlyWhereItsIcebergApiDoes() throws Exception {
    Class<?> handler = Class.forName(WRITE_HANDLER_CLASS, false, getClass().getClassLoader());
    String[] beans = context.getBeanNamesForType(handler, false, false);
    Assertions.assertEquals(
        icebergWriteApiPresent() ? 1 : 0,
        beans.length,
        "IRC write handler beans under this Iceberg runtime: " + Arrays.toString(beans));
  }

  @Test
  public void theReadRoutesAreServedOnEveryIcebergRuntime() throws IOException {
    Assertions.assertEquals(200, call("GET", "/iceberg/v1/config", null));
    Assertions.assertEquals(
        404, call("GET", "/iceberg/v1/namespaces/irc_boundary_db/tables/no_such_table", null));
  }

  @Test
  public void theWriteRoutesAreNotServedUnderIceberg12() throws IOException {
    if (icebergWriteApiPresent()) {
      // Served: an empty create body is refused by the route itself (400), not as unserved (406).
      Assertions.assertEquals(
          400, call("POST", "/iceberg/v1/namespaces/irc_boundary_db/tables", "{}"));
      return;
    }
    Assertions.assertEquals(
        406, call("POST", "/iceberg/v1/namespaces/irc_boundary_db/tables", "{}"));
    Assertions.assertEquals(
        406, call("POST", "/iceberg/v1/namespaces/irc_boundary_db/tables/t", "{}"));
    Assertions.assertEquals(
        406, call("DELETE", "/iceberg/v1/namespaces/irc_boundary_db/tables/t", null));
    Assertions.assertEquals(406, call("POST", "/iceberg/v1/tables/rename", "{}"));
  }

  /** The spec routes served on every Iceberg runtime: the reads. */
  private static final List<String> READ_ROUTES =
      Arrays.asList(
          "GET /v1/{prefix}/namespaces",
          "GET /v1/{prefix}/namespaces/{namespace}",
          "HEAD /v1/{prefix}/namespaces/{namespace}",
          "GET /v1/{prefix}/namespaces/{namespace}/tables",
          "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}");

  /**
   * The config lists the spec routes served under this runtime and the OpenHouse capabilities
   * registered: under Iceberg 1.2 the reads only, and no policy patch (it rides the write routes);
   * under 1.5 the reads, the table writes and the views.
   */
  @Test
  public void theConfigAdvertisesWhatThisIcebergRuntimeServes() throws IOException {
    JsonNode config = new ObjectMapper().readTree(get("/iceberg/v1/config"));
    List<String> endpoints = new ArrayList<>();
    config.path("endpoints").forEach(endpoint -> endpoints.add(endpoint.asText()));
    String capabilities = config.path("overrides").path("openhouse.capabilities").asText();
    if (!icebergWriteApiPresent()) {
      Assertions.assertEquals(READ_ROUTES, endpoints);
      Assertions.assertEquals("grants,unlock", capabilities);
      return;
    }
    Assertions.assertTrue(endpoints.containsAll(READ_ROUTES), endpoints.toString());
    Assertions.assertTrue(
        endpoints.contains("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}"),
        endpoints.toString());
    Assertions.assertTrue(
        endpoints.contains("GET /v1/{prefix}/namespaces/{namespace}/views/{view}"),
        endpoints.toString());
    Assertions.assertEquals(16, endpoints.size(), endpoints.toString());
    Assertions.assertEquals("policies,grants,unlock", capabilities);
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
