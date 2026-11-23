package com.linkedin.openhouse.spark42.irc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockState;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The tables service for the Spark 4.2 SQL-extensions tests, in its own JVM: the same service as
 * {@link IrcReadTestService} ({@link SpringH2TestApplication}, in-memory H2, local storage, the
 * dummy token interceptor; an authorization handler that denies {@value
 * IrcReadTestService#STRANGER}, here {@link RecordingAuthorization}, which also stores grants),
 * without the read test's seeded tables.
 *
 * <p>Instead it serves a small control API on a second local port, so the test can do, through the
 * existing OpenHouse Java client over the native API, what Spark 3.5 with the OpenHouse catalog
 * does for a policy statement:
 *
 * <ul>
 *   <li>{@code POST /create {"table", "partitioned", "patches": [...]}}: creates {@code
 *       irc42x.<table>} with columns {@code id bigint, name string, ts timestamp} (partitioned by
 *       {@code hours(ts)} if asked), then applies each patch in turn.
 *   <li>{@code POST /patch {"table", "patch"}}: sets the policy patch as the Spark 3.5 exec nodes
 *       do ({@code updateProperties().set("updated.openhouse.policy", patch).commit()}); the Java
 *       client merges it onto the loaded policies and sends the native PUT. A failure answers 400
 *       with its class and message.
 *   <li>{@code GET /properties?table=<table>}: the table's properties, freshly loaded through the
 *       Java client.
 *   <li>{@code GET /acls?resource=<db or db.table>}: the grants the service stored for it, as
 *       {@code principal=role} in grant order.
 *   <li>{@code POST /lock {"table", "reason"}}: locks {@code irc42x.<table>} through the native
 *       lock route as the writer ({@code reason} omitted: a LEGACY lock); no SQL statement locks.
 *   <li>{@code GET /lockState?table=<table>}: {@code unlocked}, {@code locked <reason>} or {@code
 *       missing}, from the service's table store (loading a SYSTEM_ONLY-locked table fails).
 * </ul>
 *
 * <p>It writes {@code ready.json} (both ports and the tokens) and serves until its standard input
 * closes. Usage: {@code IrcExtensionsTestService <work directory>}.
 */
public final class IrcExtensionsTestService {

  static final String DB = "irc42x";
  static final String POLICY_PATCH = "updated.openhouse.policy";

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()),
          Types.NestedField.optional(3, "ts", Types.TimestampType.withZone()));

  private static final ObjectMapper JSON = new ObjectMapper();

  private IrcExtensionsTestService() {}

  public static void main(String[] args) throws Exception {
    Path work = Paths.get(args[0]).toAbsolutePath();
    // Not under the work directory: a metadata location has to fit House Tables' 255 characters.
    Path warehouse = ServiceWarehouse.create();
    ConfigurableApplicationContext context =
        new SpringApplication(SpringH2TestApplication.class, RecordingAuthorization.Config.class)
            .run(
                "--server.port=0",
                "--server.shutdown=immediate",
                "--server.tomcat.basedir=" + work.resolve("tomcat"),
                "--cluster.security.token.interceptor.classname="
                    + DummyTokenInterceptor.class.getName(),
                "--cluster.storages.default-type=local",
                "--cluster.storages.types.local.endpoint=file://",
                "--cluster.storages.types.local.rootpath=" + warehouse);
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();

    Map<String, String> tokens = new LinkedHashMap<>();
    for (String principal :
        Arrays.asList(
            IrcReadTestService.WRITER, IrcReadTestService.READER, IrcReadTestService.STRANGER)) {
      tokens.put(principal, new DummyTokenInterceptor.DummySecurityJWT(principal).buildNoopJWT());
    }
    Catalog openhouse = javaClient(port, tokens.get(IrcReadTestService.WRITER));

    HttpServer control =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    control.createContext("/create", exchange -> handle(exchange, body -> create(openhouse, body)));
    control.createContext("/patch", exchange -> handle(exchange, body -> patch(openhouse, body)));
    control.createContext(
        "/properties",
        exchange ->
            handle(
                exchange,
                body -> {
                  String table = exchange.getRequestURI().getQuery().replaceFirst("^table=", "");
                  Map<String, Object> result = new LinkedHashMap<>();
                  result.put(
                      "properties",
                      new LinkedHashMap<>(
                          openhouse.loadTable(TableIdentifier.of(DB, table)).properties()));
                  return result;
                }));
    RecordingAuthorization acls = context.getBean(RecordingAuthorization.class);
    OpenHouseInternalRepository repository = context.getBean(OpenHouseInternalRepository.class);
    control.createContext(
        "/acls",
        exchange ->
            handle(
                exchange,
                body -> {
                  Map<String, Object> result = new LinkedHashMap<>();
                  result.put("acls", acls.stored(query(exchange, "resource")));
                  return result;
                }));
    control.createContext(
        "/lock",
        exchange ->
            handle(
                exchange,
                body ->
                    lock(
                        port,
                        tokens.get(IrcReadTestService.WRITER),
                        body.get("table").asText(),
                        body.path("reason").asText(null))));
    control.createContext(
        "/lockState",
        exchange ->
            handle(
                exchange,
                body -> {
                  Map<String, Object> result = new LinkedHashMap<>();
                  result.put("lock", lockState(repository, query(exchange, "table")));
                  return result;
                }));
    control.start();

    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("port", port);
    ready.put("warehouse", warehouse.toString());
    ready.put("controlPort", control.getAddress().getPort());
    ready.put("tokens", tokens);
    Path tmp = work.resolve("ready.json.tmp");
    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(tmp.toFile(), ready);
    Files.move(tmp, work.resolve("ready.json"), StandardCopyOption.ATOMIC_MOVE);
    System.out.println("IRC extensions test service ready on port " + port);

    // Serve until the parent closes our standard input (it stopped us, or it died).
    InputStream in = System.in;
    while (in.read() != -1) {
      // ignore
    }
    control.stop(0);
    SpringApplication.exit(context);
    System.exit(0);
  }

  private interface Handler {
    Map<String, Object> apply(JsonNode body) throws Exception;
  }

  /** Answers 200 with the handler's result, or 400 with the failure's class and message. */
  private static void handle(HttpExchange exchange, Handler handler) throws IOException {
    int status;
    Map<String, Object> result;
    try (InputStream in = exchange.getRequestBody()) {
      byte[] bytes = in.readAllBytes();
      JsonNode body = bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
      result = handler.apply(body);
      status = 200;
    } catch (Exception e) {
      result = new LinkedHashMap<>();
      result.put("error", e.getClass().getName() + ": " + e.getMessage());
      status = 400;
    }
    byte[] response = JSON.writeValueAsBytes(result);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, response.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(response);
    }
  }

  private static Map<String, Object> create(Catalog openhouse, JsonNode body) {
    String table = body.get("table").asText();
    PartitionSpec spec =
        body.path("partitioned").asBoolean(false)
            ? PartitionSpec.builderFor(SCHEMA).hour("ts").build()
            : PartitionSpec.unpartitioned();
    openhouse.createTable(TableIdentifier.of(DB, table), SCHEMA, spec);
    for (JsonNode patch : body.path("patches")) {
      applyPatch(openhouse, table, patch.asText());
    }
    return new LinkedHashMap<>();
  }

  private static Map<String, Object> patch(Catalog openhouse, JsonNode body) {
    applyPatch(openhouse, body.get("table").asText(), body.get("patch").asText());
    return new LinkedHashMap<>();
  }

  /** What a Spark 3.5 policy exec does on the OpenHouse catalog, after its own checks. */
  private static void applyPatch(Catalog openhouse, String table, String patch) {
    Table loaded = openhouse.loadTable(TableIdentifier.of(DB, table));
    loaded.updateProperties().set(POLICY_PATCH, patch).commit();
  }

  /** The value of the request's only query parameter, {@code name=value}. */
  private static String query(HttpExchange exchange, String name) {
    return URLDecoder.decode(
        exchange.getRequestURI().getRawQuery().replaceFirst("^" + name + "=", ""),
        StandardCharsets.UTF_8);
  }

  /** Locks a table through the native lock route; a failure answers 400 with the response. */
  private static Map<String, Object> lock(int port, String token, String table, String reason)
      throws Exception {
    String body =
        reason == null
            ? "{\"locked\":true,\"message\":\"locked by the test\"}"
            : "{\"locked\":true,\"reason\":\"" + reason + "\"}";
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/v1/databases/"
                                + DB
                                + "/tables/"
                                + table
                                + "/lock"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 201) {
      throw new IllegalStateException(response.statusCode() + " " + response.body());
    }
    return new LinkedHashMap<>();
  }

  private static String lockState(OpenHouseInternalRepository repository, String table) {
    Optional<TableDto> dto =
        repository.findById(TableDtoPrimaryKey.builder().databaseId(DB).tableId(table).build());
    if (!dto.isPresent()) {
      return "missing";
    }
    LockState lock =
        dto.get().getPolicies() == null ? null : dto.get().getPolicies().getLockState();
    return lock == null || !lock.isLocked() ? "unlocked" : "locked " + lock.getReason();
  }

  /** The existing OpenHouse Java client, as the Spark 3.5 tests configure it. */
  private static Catalog javaClient(int port, String token) {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port);
    properties.put("cluster", "local-cluster");
    properties.put("auth-token", token);
    return CatalogUtil.loadCatalog(
        "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
        "openhouse",
        properties,
        new Configuration());
  }
}
