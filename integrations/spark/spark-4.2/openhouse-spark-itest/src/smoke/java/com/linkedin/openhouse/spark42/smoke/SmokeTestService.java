package com.linkedin.openhouse.spark42.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.spark.smoke.StoredTable;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The tables service for the smoke suite ({@code IrcSmokeTestService} in this module's {@code
 * service} source set), started once per test JVM in its own JVM, as {@code
 * IrcReadTestServiceProcess} starts the read test's, and stopped when the test JVM exits. Also the
 * client of its control API, which shows what the service stored for a table through the existing
 * OpenHouse Java client in the service JVM, without Spark.
 */
final class SmokeTestService {

  private static final long START_TIMEOUT_SECONDS = 300;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static SmokeTestService instance;

  private final Process process;
  private final Path work;
  private final JsonNode ready;
  private final HttpClient http = HttpClient.newHttpClient();

  private SmokeTestService(Process process, Path work, JsonNode ready) {
    this.process = process;
    this.work = work;
    this.ready = ready;
  }

  static synchronized SmokeTestService get() throws Exception {
    if (instance == null) {
      instance = start();
      Runtime.getRuntime().addShutdownHook(new Thread(instance::stop, "irc42-smoke-service-stop"));
    }
    return instance;
  }

  private static SmokeTestService start() throws Exception {
    Path parent = Paths.get(required("irc42.workdir"));
    Files.createDirectories(parent);
    Path work = Files.createTempDirectory(parent, "smoke-service-");
    List<String> command =
        Arrays.asList(
            required("irc42.java"),
            "-Xmx768m",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "-cp",
            required("irc42.service.classpath"),
            "com.linkedin.openhouse.spark42.irc.IrcSmokeTestService",
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
            "IRC smoke test service exited with " + process.exitValue() + ":\n" + logTail(work));
      }
      if (System.nanoTime() > deadline) {
        process.destroyForcibly();
        throw new IllegalStateException("IRC smoke test service did not start:\n" + logTail(work));
      }
      Thread.sleep(250);
    }
    return new SmokeTestService(process, work, JSON.readTree(readyFile.toFile()));
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

  /** The bearer token of the principal the tests act as. */
  String token() {
    return ready.get("token").asText();
  }

  /** The directory the service keeps tables in. */
  String warehouse() {
    return ready.get("warehouse").asText();
  }

  Path work() {
    return work;
  }

  /**
   * What the service stored for {@code <db>.<table>}, freshly loaded through the OpenHouse Java
   * client over the native API; fails when the table cannot be loaded.
   */
  StoredTable stored(String name) throws Exception {
    HttpResponse<String> response = table(name);
    JsonNode result = JSON.readTree(response.body());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(name + ": " + result.path("error").asText());
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> stored = JSON.convertValue(result, Map.class);
    return new StoredTable(stored);
  }

  /** Whether the service has a table {@code <db>.<table>}, asked through the Java client. */
  boolean exists(String name) throws Exception {
    HttpResponse<String> response = table(name);
    if (response.statusCode() == 200) {
      return true;
    }
    String error = JSON.readTree(response.body()).path("error").asText();
    if (error.startsWith("org.apache.iceberg.exceptions.NoSuchTableException")) {
      return false;
    }
    throw new IllegalStateException(name + ": " + error);
  }

  private HttpResponse<String> table(String name) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + ready.get("controlPort").asInt()
                        + "/table?name="
                        + URLEncoder.encode(name, StandardCharsets.UTF_8)))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
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
