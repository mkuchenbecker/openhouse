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
 * The owner's namespace routes (upstream 870: {@code /v1/{prefix}/namespaces...} at the service
 * root) next to this branch's Iceberg REST routes under {@code /iceberg/v1} and the native routes
 * ({@code /v1/databases/...}) in the embedded tables service: which handler Spring's handler
 * mapping picks for each, which authentication interceptor runs, what each answers over HTTP, and
 * that the config route does not advertise the owner's routes.
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
  public void theOwnersRoutesMapToHisControllerBehindTheNativeTokenInterceptor() throws Exception {
    String his = "IcebergNamespacesController#";
    String nativeAuth = " [" + NATIVE_TOKEN_INTERCEPTOR + "]";
    Assertions.assertEquals(
        his + "createNamespace" + nativeAuth, handler("POST", "/v1/openhouse/namespaces"));
    Assertions.assertEquals(
        his + "loadNamespaceMetadata" + nativeAuth, handler("GET", "/v1/openhouse/namespaces/db"));
    Assertions.assertEquals(
        his + "updateNamespaceProperties" + nativeAuth,
        handler("POST", "/v1/openhouse/namespaces/db/properties"));
    Assertions.assertEquals(
        his + "dropNamespace" + nativeAuth, handler("DELETE", "/v1/openhouse/namespaces/db"));
    // The prefix is any one segment, "iceberg" included: the first segment decides.
    Assertions.assertEquals(
        his + "createNamespace" + nativeAuth, handler("POST", "/v1/iceberg/namespaces"));
  }

  @Test
  public void theIcebergRestNamespaceRoutesAreUnchanged() throws Exception {
    String irc = "IcebergRestCatalogController#";
    String ircAuth = " [" + IRC_TOKEN_INTERCEPTOR + "]";
    Assertions.assertEquals(
        irc + "listNamespaces" + ircAuth, handler("GET", "/iceberg/v1/namespaces"));
    Assertions.assertEquals(
        irc + "loadNamespace" + ircAuth, handler("GET", "/iceberg/v1/namespaces/db"));
    Assertions.assertEquals(
        irc + "namespaceExists" + ircAuth, handler("HEAD", "/iceberg/v1/namespaces/db"));
    // Namespace writes under /iceberg stay with the catch-all (406), with or without a prefix.
    Assertions.assertEquals(irc + "unserved" + ircAuth, handler("POST", "/iceberg/v1/namespaces"));
    Assertions.assertEquals(
        irc + "unserved" + ircAuth, handler("DELETE", "/iceberg/v1/namespaces/db"));
    Assertions.assertEquals(
        irc + "unserved" + ircAuth, handler("POST", "/iceberg/v1/namespaces/db/properties"));
    Assertions.assertEquals(
        irc + "unserved" + ircAuth, handler("POST", "/iceberg/v1/openhouse/namespaces"));
  }

  /**
   * A database named "namespaces" makes native paths that the owner's patterns also match (prefix
   * "databases"). Where a native route maps the path and method, it is the more specific pattern
   * (fewer path variables) and keeps it.
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
   * Where no native route maps the method, a request to a database named "namespaces" that had no
   * handler (405) before upstream 870 now reaches the owner's controller with prefix "databases"
   * and a namespace named after the next segment. Pinned so that the change is visible.
   */
  @Test
  public void pathsNoNativeRouteMapsNowReachTheOwnersRoutes() throws Exception {
    String his = "IcebergNamespacesController#";
    String nativeAuth = " [" + NATIVE_TOKEN_INTERCEPTOR + "]";
    Assertions.assertEquals(
        his + "loadNamespaceMetadata" + nativeAuth,
        handler("GET", "/v1/databases/namespaces/tables"));
    Assertions.assertEquals(
        his + "dropNamespace" + nativeAuth, handler("DELETE", "/v1/databases/namespaces/tables"));
    Assertions.assertEquals(
        his + "updateNamespaceProperties" + nativeAuth,
        handler("POST", "/v1/databases/namespaces/tables/properties"));
  }

  /**
   * Over HTTP: the owner's routes store and answer a namespace record through the embedded
   * stand-in; the Iceberg REST routes still derive existence from tables (a record alone is not a
   * namespace there) and still refuse namespace writes with 406; the native database list stays
   * table-derived.
   */
  @Test
  public void theRoutesAnswerIndependently() throws Exception {
    String his = "/v1/openhouse/namespaces";
    Assertions.assertEquals(
        401, call("POST", his, "{\"namespace\":[\"nsb_db\"]}", false).status, "no token");

    Response created =
        call("POST", his, "{\"namespace\":[\"Nsb_Db\"],\"properties\":{\"k\":\"v\"}}", true);
    Assertions.assertEquals(200, created.status, created.body);
    JsonNode createdBody = new ObjectMapper().readTree(created.body);
    Assertions.assertEquals("Nsb_Db", createdBody.path("namespace").path(0).asText());
    Assertions.assertEquals("v", createdBody.path("properties").path("k").asText());
    Response duplicate = call("POST", his, "{\"namespace\":[\"NSB_DB\"]}", true);
    Assertions.assertEquals(409, duplicate.status, "case variant: " + duplicate.body);

    Response loaded = call("GET", his + "/nsb_db", null, true);
    Assertions.assertEquals(200, loaded.status, loaded.body);
    Assertions.assertEquals(
        "Nsb_Db", new ObjectMapper().readTree(loaded.body).path("namespace").path(0).asText());

    Assertions.assertEquals(404, call("GET", "/iceberg/v1/namespaces/nsb_db", null, true).status);
    Assertions.assertEquals(404, call("HEAD", "/iceberg/v1/namespaces/nsb_db", null, true).status);
    Response list = call("GET", "/iceberg/v1/namespaces", null, true);
    Assertions.assertEquals(200, list.status, list.body);
    Assertions.assertFalse(list.body.toLowerCase().contains("nsb_db"), list.body);
    Response databases = call("GET", "/v1/databases", null, true);
    Assertions.assertEquals(200, databases.status, databases.body);
    Assertions.assertFalse(databases.body.toLowerCase().contains("nsb_db"), databases.body);
    Assertions.assertEquals(
        406, call("POST", "/iceberg/v1/namespaces", "{\"namespace\":[\"x\"]}", true).status);
    Assertions.assertEquals(
        406, call("DELETE", "/iceberg/v1/namespaces/nsb_db", null, true).status);
    Assertions.assertEquals(
        406,
        call("POST", "/iceberg/v1/namespaces/nsb_db/properties", "{\"updates\":{}}", true).status);

    Response updated =
        call(
            "POST",
            his + "/NSB_DB/properties",
            "{\"updates\":{\"a\":\"1\"},\"removals\":[\"k\",\"missing\"]}",
            true);
    Assertions.assertEquals(200, updated.status, updated.body);
    JsonNode updatedBody = new ObjectMapper().readTree(updated.body);
    Assertions.assertEquals("[\"a\"]", updatedBody.path("updated").toString());
    Assertions.assertEquals("[\"k\"]", updatedBody.path("removed").toString());
    Assertions.assertEquals("[\"missing\"]", updatedBody.path("missing").toString());

    Assertions.assertEquals(204, call("DELETE", his + "/nsb_db", null, true).status);
    Response gone = call("GET", his + "/nsb_db", null, true);
    Assertions.assertEquals(404, gone.status, gone.body);
    Assertions.assertEquals(
        "NoSuchNamespaceException",
        new ObjectMapper().readTree(gone.body).path("error").path("type").asText());
  }

  @Test
  public void theConfigDoesNotAdvertiseTheOwnersRoutes() throws Exception {
    Response config = call("GET", "/iceberg/v1/config", null, true);
    Assertions.assertEquals(200, config.status, config.body);
    List<String> endpoints = new ArrayList<>();
    new ObjectMapper()
        .readTree(config.body)
        .path("endpoints")
        .forEach(endpoint -> endpoints.add(endpoint.asText()));
    for (String route :
        Arrays.asList(
            "POST /v1/{prefix}/namespaces",
            "DELETE /v1/{prefix}/namespaces/{namespace}",
            "POST /v1/{prefix}/namespaces/{namespace}/properties")) {
      Assertions.assertFalse(endpoints.contains(route), route + " in " + endpoints);
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
