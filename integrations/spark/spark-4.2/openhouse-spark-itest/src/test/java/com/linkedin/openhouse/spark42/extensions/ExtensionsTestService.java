package com.linkedin.openhouse.spark42.extensions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The tables service for the SQL-extensions tests ({@code IrcExtensionsTestService} in this
 * module's {@code service} source set), started once per test JVM in its own JVM, as {@code
 * IrcReadTestServiceProcess} starts the read test's, and stopped when the test JVM exits. Also the
 * client of its control API, which acts on tables through the existing OpenHouse Java client in the
 * service JVM: that is the Spark 3.5 + OpenHouse catalog reference, without Spark 3.5.
 */
final class ExtensionsTestService {

  static final String DB = "irc42x";
  static final String WRITER = "irc_writer";
  static final String STRANGER = "irc_stranger";
  static final String POLICY_PATCH = "updated.openhouse.policy";

  private static final long START_TIMEOUT_SECONDS = 300;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static ExtensionsTestService instance;

  private final Process process;
  private final Path work;
  private final JsonNode ready;
  private final HttpClient http = HttpClient.newHttpClient();

  private ExtensionsTestService(Process process, Path work, JsonNode ready) {
    this.process = process;
    this.work = work;
    this.ready = ready;
  }

  static synchronized ExtensionsTestService get() throws Exception {
    if (instance == null) {
      instance = start();
      Runtime.getRuntime().addShutdownHook(new Thread(instance::stop, "irc42x-service-stop"));
    }
    return instance;
  }

  private static ExtensionsTestService start() throws Exception {
    Path parent = Paths.get(required("irc42.workdir"));
    Files.createDirectories(parent);
    Path work = Files.createTempDirectory(parent, "extensions-service-");
    List<String> command =
        Arrays.asList(
            required("irc42.java"),
            "-Xmx768m",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "-cp",
            required("irc42.service.classpath"),
            "com.linkedin.openhouse.spark42.irc.IrcExtensionsTestService",
            work.toString());
    Process process =
        new ProcessBuilder(command)
            .directory(work.toFile())
            .redirectErrorStream(true)
            .redirectOutput(work.resolve("service.log").toFile())
            .start();
    Path readyFile = work.resolve("ready.json");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
    while (!Files.exists(readyFile)) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "IRC extensions test service exited with "
                + process.exitValue()
                + ":\n"
                + logTail(work));
      }
      if (System.nanoTime() > deadline) {
        process.destroyForcibly();
        throw new IllegalStateException(
            "IRC extensions test service did not start:\n" + logTail(work));
      }
      Thread.sleep(250);
    }
    return new ExtensionsTestService(process, work, JSON.readTree(readyFile.toFile()));
  }

  private static String required(String property) {
    String value = System.getProperty(property);
    if (value == null) {
      throw new IllegalStateException("System property " + property + " is not set by the build");
    }
    return value;
  }

  private static String logTail(Path work) {
    try {
      List<String> lines = Files.readAllLines(work.resolve("service.log"), StandardCharsets.UTF_8);
      return String.join("\n", lines.subList(Math.max(0, lines.size() - 80), lines.size()));
    } catch (IOException e) {
      return "(no log: " + e + ")";
    }
  }

  /** The service's base URI, for example {@code http://localhost:43123}. */
  String uri() {
    return "http://localhost:" + ready.get("port").asInt();
  }

  String token(String principal) {
    return ready.get("tokens").get(principal).asText();
  }

  Path work() {
    return work;
  }

  /**
   * Creates {@code irc42x.<table>} ({@code id bigint, name string, ts timestamp}, partitioned by
   * {@code hours(ts)} if asked) through the Java client, then applies each policy patch in turn.
   */
  void create(String table, boolean partitioned, String... patches) throws Exception {
    ObjectNode body = JSON.createObjectNode().put("table", table).put("partitioned", partitioned);
    Arrays.stream(patches).forEach(body.putArray("patches")::add);
    String error = post("/create", body);
    if (error != null) {
      throw new IllegalStateException("create " + table + ": " + error);
    }
  }

  /**
   * Applies a policy patch through the Java client, as a Spark 3.5 policy exec does on the
   * OpenHouse catalog.
   *
   * @return null, or the failure's class and message
   */
  String patch(String table, String patch) throws Exception {
    return post("/patch", JSON.createObjectNode().put("table", table).put("patch", patch));
  }

  /** The table's properties, freshly loaded through the Java client. */
  Map<String, String> properties(String table) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    control(
                        "/properties?table=" + URLEncoder.encode(table, StandardCharsets.UTF_8)))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    JsonNode result = JSON.readTree(response.body());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("properties " + table + ": " + result.path("error").asText());
    }
    Map<String, String> properties = new LinkedHashMap<>();
    result
        .get("properties")
        .fields()
        .forEachRemaining(e -> properties.put(e.getKey(), e.getValue().asText()));
    return properties;
  }

  /** The grants the service stored for {@code resource} ({@code db} or {@code db.table}). */
  List<String> acls(String resource) throws Exception {
    JsonNode result = get("/acls?resource=" + URLEncoder.encode(resource, StandardCharsets.UTF_8));
    List<String> acls = new ArrayList<>();
    result.get("acls").forEach(acl -> acls.add(acl.asText()));
    return acls;
  }

  /** Locks {@code irc42x.<table>} through the native lock route; null reason: a LEGACY lock. */
  void lock(String table, String reason) throws Exception {
    ObjectNode body = JSON.createObjectNode().put("table", table);
    if (reason != null) {
      body.put("reason", reason);
    }
    String error = post("/lock", body);
    if (error != null) {
      throw new IllegalStateException("lock " + table + ": " + error);
    }
  }

  /** {@code unlocked}, {@code locked <reason>} or {@code missing}, from the service's store. */
  String lockState(String table) throws Exception {
    return get("/lockState?table=" + URLEncoder.encode(table, StandardCharsets.UTF_8))
        .get("lock")
        .asText();
  }

  private JsonNode get(String pathAndQuery) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(control(pathAndQuery)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    JsonNode result = JSON.readTree(response.body());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(pathAndQuery + ": " + result.path("error").asText());
    }
    return result;
  }

  private String post(String path, JsonNode body) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(control(path))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return response.statusCode() == 200
        ? null
        : JSON.readTree(response.body()).path("error").asText();
  }

  private URI control(String pathAndQuery) {
    return URI.create("http://127.0.0.1:" + ready.get("controlPort").asInt() + pathAndQuery);
  }

  private void stop() {
    try {
      process.getOutputStream().close();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      process.destroyForcibly();
    }
  }
}
