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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The namespace-record routes ({@code /v1/{prefix}/namespaces...} at the service root, any one
 * segment as the prefix) next to the Iceberg REST routes under {@code /iceberg/v1} and the native
 * routes ({@code /v1/databases/...}) in the embedded tables service: which handler Spring's handler
 * mapping picks for each, which authentication interceptor runs, what each answers over HTTP, which
 * paths the namespace-record routes took over from "no handler", and that the config route lists
 * only routes served under {@code /iceberg}.
 */
public class NamespaceRoutesBoundaryTest {

  private static final String NATIVE_TOKEN_INTERCEPTOR = "DummyTokenInterceptor";

  private static final String IRC_TOKEN_INTERCEPTOR = "IcebergRestAuthenticationInterceptor";

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
    // The token interceptor the kit's service and production configure (the fixture's default runs
    // none), so that which routes it guards is visible.
    Map<String, Object> properties = new HashMap<>();
    properties.put("server.port", "0");
    properties.put(
        "cluster.security.token.interceptor.classname",
        "com.linkedin.openhouse.common.security.DummyTokenInterceptor");
    application.setDefaultProperties(properties);
    context = application.run();
    port = ((WebServerApplicationContext) context).getWebServer().getPort();
    try (InputStream in =
        NamespaceRoutesBoundaryTest.class.getClassLoader().getResourceAsStream("dummy.token")) {
      token = new String(readAll(in), StandardCharsets.UTF_8).trim();
    }
  }

  @AfterAll
  public static void stop() {
    context.close();
  }

  /** "Controller#method" of the handler Spring maps the request to, and the interceptors run. */
  private static String handler(String method, String path) throws Exception {
    RequestMappingHandlerMapping mapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setContentType("application/json");
    ServletRequestPathUtils.parseAndCache(request);
    HandlerExecutionChain chain = mapping.getHandler(request);
    Assertions.assertNotNull(chain, method + " " + path);
    HandlerMethod handler = (HandlerMethod) chain.getHandler();
    List<String> interceptors =
        Arrays.stream(chain.getInterceptors())
            .map(i -> i.getClass().getSimpleName())
            .filter(n -> n.equals(NATIVE_TOKEN_INTERCEPTOR) || n.equals(IRC_TOKEN_INTERCEPTOR))
            .collect(Collectors.toList());
    return handler.getBeanType().getSimpleName()
        + "#"
        + handler.getMethod().getName()
        + " "
        + interceptors;
  }

  @Test
  public void theNamespaceRecordRoutesMapToTheirControllerBehindTheNativeTokenInterceptor()
      throws Exception {
    String records = "IcebergNamespacesController#";
    String nativeAuth = " [" + NATIVE_TOKEN_INTERCEPTOR + "]";
    Assertions.assertEquals(
        records + "createNamespace" + nativeAuth, handler("POST", "/v1/openhouse/namespaces"));
    Assertions.assertEquals(
        records + "loadNamespaceMetadata" + nativeAuth,
        handler("GET", "/v1/openhouse/namespaces/db"));
    Assertions.assertEquals(
        records + "updateNamespaceProperties" + nativeAuth,
        handler("POST", "/v1/openhouse/namespaces/db/properties"));
    Assertions.assertEquals(
        records + "dropNamespace" + nativeAuth, handler("DELETE", "/v1/openhouse/namespaces/db"));
    // The prefix is any one segment, "iceberg" included: the first segment decides.
    Assertions.assertEquals(
        records + "createNamespace" + nativeAuth, handler("POST", "/v1/iceberg/namespaces"));
  }

  @Test
  public void theIcebergRestNamespaceRoutesStayUnderIcebergBehindItsInterceptor() throws Exception {
    String irc = "IcebergRestCatalogController#";
    String ircAuth = " [" + IRC_TOKEN_INTERCEPTOR + "]";
    Assertions.assertEquals(
        irc + "listNamespaces" + ircAuth, handler("GET", "/iceberg/v1/namespaces"));
    Assertions.assertEquals(
        irc + "loadNamespace" + ircAuth, handler("GET", "/iceberg/v1/namespaces/db"));
    Assertions.assertEquals(
        irc + "namespaceExists" + ircAuth, handler("HEAD", "/iceberg/v1/namespaces/db"));
    // Namespace writes under /iceberg are the Iceberg REST controller's (over the same namespace
    // service); a prefix under /iceberg is still no route (the catch-all).
    Assertions.assertEquals(
        irc + "createNamespace" + ircAuth, handler("POST", "/iceberg/v1/namespaces"));
    Assertions.assertEquals(
        irc + "dropNamespace" + ircAuth, handler("DELETE", "/iceberg/v1/namespaces/db"));
    Assertions.assertEquals(
        irc + "updateNamespaceProperties" + ircAuth,
        handler("POST", "/iceberg/v1/namespaces/db/properties"));
    Assertions.assertEquals(
        irc + "unserved" + ircAuth, handler("POST", "/iceberg/v1/openhouse/namespaces"));
  }

  /**
   * A database named "namespaces" makes native paths that the namespace-record patterns also match
   * (prefix "databases"). Where a native route maps the path and method, it is the more specific
   * pattern (fewer path variables) and keeps it.
   */
  @Test
  public void theNativeRoutesKeepTheirPaths() throws Exception {
    String nativeAuth = " [" + NATIVE_TOKEN_INTERCEPTOR + "]";
    Assertions.assertEquals(
        "DatabasesController#getAllDatabases" + nativeAuth, handler("GET", "/v1/databases"));
    Assertions.assertEquals(
        "DatabasesController#getDatabaseAclPolicies" + nativeAuth,
        handler("GET", "/v1/databases/namespaces/aclPolicies"));
    Assertions.assertEquals(
        "TablesController#createTable" + nativeAuth,
        handler("POST", "/v1/databases/namespaces/tables"));
    Assertions.assertEquals(
        "TablesController#getTable" + nativeAuth,
        handler("GET", "/v1/databases/namespaces/tables/properties"));
    Assertions.assertEquals(
        "ViewsController#getAllViews" + nativeAuth,
        handler("GET", "/v1/databases/namespaces/views"));
  }

  /**
   * Requests that had no handler and now reach the namespace-record routes, because the prefix is
   * never validated: any {@code /v1/<segment>/namespaces...} path, and, for a database named
   * "namespaces", every native path whose method no native route maps. Each row: method, path,
   * body, the status the base build answered (measured by running the same requests against the
   * embedded service of the build before namespace records were served: 405 where a native pattern
   * matched the path with other methods, 400 "cannot be resolved by server" where nothing matched),
   * and the status now.
   */
  private static final String[][] TAKEN_OVER = {
    {"GET", "/v1/databases/namespaces/tables", null, "405", "404"},
    {"HEAD", "/v1/databases/namespaces/tables", null, "405", "404"},
    {"DELETE", "/v1/databases/namespaces/tables", null, "405", "404"},
    {"DELETE", "/v1/databases/namespaces/views", null, "405", "404"},
    {"DELETE", "/v1/databases/namespaces/aclPolicies", null, "405", "404"},
    {"DELETE", "/v1/databases/namespaces/softDeletedTables", null, "405", "404"},
    {"POST", "/v1/databases/namespaces/tables/properties", "{\"updates\":{}}", "405", "404"},
    {"POST", "/v1/databases/namespaces/views/properties", "{\"updates\":{}}", "405", "404"},
    {"GET", "/v1/databases/namespaces", null, "400", "405"},
    {"POST", "/v1/databases/namespaces", "{\"namespace\":[\"nsb_taken_db\"]}", "400", "200"},
    {"GET", "/v1/anything/namespaces", null, "400", "405"},
    {"POST", "/v1/anything/namespaces", "{\"namespace\":[\"nsb_taken\"]}", "400", "200"},
    {"GET", "/v1/anything/namespaces/nsb_taken", null, "400", "200"},
    {"POST", "/v1/anything/namespaces/nsb_taken/properties", "{\"updates\":{}}", "400", "200"},
    {"DELETE", "/v1/anything/namespaces/nsb_taken", null, "400", "204"},
    {"DELETE", "/v1/databases/namespaces/nsb_taken_db", null, "400", "204"},
  };

  @Test
  public void pathsWithNoHandlerBeforeNowReachTheNamespaceRecordRoutes() throws Exception {
    String records = "IcebergNamespacesController#";
    for (String[] row : TAKEN_OVER) {
      if (!"GET /v1/databases/namespaces".equals(row[0] + " " + row[1])
          && !"GET /v1/anything/namespaces".equals(row[0] + " " + row[1])) {
        Assertions.assertTrue(
            handler(row[0], row[1]).startsWith(records),
            row[0] + " " + row[1] + " (was " + row[3] + ")");
      }
      Response response = call(row[0], row[1], row[2], true);
      Assertions.assertEquals(
          Integer.parseInt(row[4]),
          response.status,
          row[0] + " " + row[1] + " (was " + row[3] + "): " + response.body);
    }
  }

  /**
   * Over HTTP: the namespace-record routes store and answer a namespace record through the embedded
   * stand-in; the Iceberg REST routes answer from the same record (load, exists, a duplicate
   * create), each API in its own error shape; the Iceberg REST and native database lists stay
   * table-derived, so a record alone is not listed (a known gap: House Tables lists no records).
   */
  @Test
  public void bothNamespaceApisAnswerFromTheSameRecords() throws Exception {
    String records = "/v1/openhouse/namespaces";
    Assertions.assertEquals(
        401, call("POST", records, "{\"namespace\":[\"nsb_db\"]}", false).status, "no token");

    Response created =
        call("POST", records, "{\"namespace\":[\"Nsb_Db\"],\"properties\":{\"k\":\"v\"}}", true);
    Assertions.assertEquals(200, created.status, created.body);
    JsonNode createdBody = new ObjectMapper().readTree(created.body);
    Assertions.assertEquals("Nsb_Db", createdBody.path("namespace").path(0).asText());
    Assertions.assertEquals("v", createdBody.path("properties").path("k").asText());
    Response duplicate = call("POST", records, "{\"namespace\":[\"NSB_DB\"]}", true);
    Assertions.assertEquals(409, duplicate.status, "case variant: " + duplicate.body);

    Response loaded = call("GET", records + "/nsb_db", null, true);
    Assertions.assertEquals(200, loaded.status, loaded.body);
    Assertions.assertEquals(
        "Nsb_Db", new ObjectMapper().readTree(loaded.body).path("namespace").path(0).asText());

    Response ircLoaded = call("GET", "/iceberg/v1/namespaces/nsb_db", null, true);
    Assertions.assertEquals(200, ircLoaded.status, ircLoaded.body);
    JsonNode ircLoadedBody = new ObjectMapper().readTree(ircLoaded.body);
    Assertions.assertEquals("Nsb_Db", ircLoadedBody.path("namespace").path(0).asText());
    Assertions.assertEquals("v", ircLoadedBody.path("properties").path("k").asText());
    Assertions.assertEquals(204, call("HEAD", "/iceberg/v1/namespaces/nsb_db", null, true).status);
    Response list = call("GET", "/iceberg/v1/namespaces", null, true);
    Assertions.assertEquals(200, list.status, list.body);
    Assertions.assertFalse(list.body.toLowerCase().contains("nsb_db"), list.body);
    Response databases = call("GET", "/v1/databases", null, true);
    Assertions.assertEquals(200, databases.status, databases.body);
    Assertions.assertFalse(databases.body.toLowerCase().contains("nsb_db"), databases.body);
    Response ircDuplicate =
        call("POST", "/iceberg/v1/namespaces", "{\"namespace\":[\"NSB_DB\"]}", true);
    Assertions.assertEquals(409, ircDuplicate.status, ircDuplicate.body);
    Assertions.assertEquals(
        "Namespace already exists: NSB_DB",
        new ObjectMapper().readTree(ircDuplicate.body).path("error").path("message").asText());

    Response updated =
        call(
            "POST",
            records + "/NSB_DB/properties",
            "{\"updates\":{\"a\":\"1\"},\"removals\":[\"k\",\"missing\"]}",
            true);
    Assertions.assertEquals(200, updated.status, updated.body);
    JsonNode updatedBody = new ObjectMapper().readTree(updated.body);
    Assertions.assertEquals("[\"a\"]", updatedBody.path("updated").toString());
    Assertions.assertEquals("[\"k\"]", updatedBody.path("removed").toString());
    Assertions.assertEquals("[\"missing\"]", updatedBody.path("missing").toString());

    Assertions.assertEquals(
        "1",
        new ObjectMapper()
            .readTree(call("GET", "/iceberg/v1/namespaces/nsb_db", null, true).body)
            .path("properties")
            .path("a")
            .asText());

    Assertions.assertEquals(204, call("DELETE", records + "/nsb_db", null, true).status);
    Assertions.assertEquals(404, call("GET", "/iceberg/v1/namespaces/nsb_db", null, true).status);
    Response gone = call("GET", records + "/nsb_db", null, true);
    Assertions.assertEquals(404, gone.status, gone.body);
    Assertions.assertEquals(
        "NoSuchNamespaceException",
        new ObjectMapper().readTree(gone.body).path("error").path("type").asText());
  }

  /**
   * Every endpoint the config route lists is served under {@code /iceberg}: the request it names,
   * sent under {@code /iceberg}, reaches an Iceberg REST controller and not the catch-all. The
   * namespace writes are among them: {@code /iceberg} serves them over the same namespace service
   * as the namespace-record routes.
   */
  @Test
  public void theConfigListsOnlyRoutesServedUnderIceberg() throws Exception {
    Response config = call("GET", "/iceberg/v1/config", null, true);
    Assertions.assertEquals(200, config.status, config.body);
    List<String> endpoints = new ArrayList<>();
    new ObjectMapper()
        .readTree(config.body)
        .path("endpoints")
        .forEach(endpoint -> endpoints.add(endpoint.asText()));
    Assertions.assertFalse(endpoints.isEmpty(), config.body);
    for (String endpoint : endpoints) {
      String[] parts = endpoint.split(" ", 2);
      String path =
          "/iceberg" + parts[1].replace("/v1/{prefix}", "/v1").replaceAll("\\{[^}]+\\}", "x");
      String served = handler(parts[0], path);
      Assertions.assertTrue(
          served.startsWith("IcebergRest") && !served.contains("#unserved"),
          endpoint + " -> " + served);
    }
    for (String route :
        Arrays.asList(
            "POST /v1/{prefix}/namespaces",
            "DELETE /v1/{prefix}/namespaces/{namespace}",
            "POST /v1/{prefix}/namespaces/{namespace}/properties")) {
      Assertions.assertTrue(endpoints.contains(route), route + " not in " + endpoints);
    }
  }

  private static final class Response {
    final int status;
    final String body;

    Response(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  private static Response call(String method, String path, String body, boolean authenticated)
      throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
    connection.setRequestMethod(method);
    if (authenticated) {
      connection.setRequestProperty("Authorization", "Bearer " + token);
    }
    if (body != null) {
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      try (OutputStream out = connection.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      }
    }
    try {
      int status = connection.getResponseCode();
      InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
      String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
      return new Response(status, text);
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
