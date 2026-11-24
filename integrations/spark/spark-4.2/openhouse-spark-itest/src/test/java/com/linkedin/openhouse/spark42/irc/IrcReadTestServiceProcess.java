package com.linkedin.openhouse.spark42.irc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The tables service for these tests, in its own JVM ({@code IrcReadTestService} in this module's
 * {@code service} source set), started once per test JVM on a free port with a private work
 * directory, and stopped when the test JVM exits. The build passes the service classpath, the java
 * executable and the parent work directory as system properties.
 *
 * <p>The service learns that it should stop when its standard input closes, so it also stops if
 * this JVM dies without running its shutdown hooks.
 */
final class IrcReadTestServiceProcess {

  private static final long START_TIMEOUT_SECONDS = 300;
  private static IrcReadTestServiceProcess instance;
  private static IrcReadTestServiceProcess formatV3Instance;

  private final Process process;
  private final Path work;
  private final JsonNode ready;

  private IrcReadTestServiceProcess(Process process, Path work, JsonNode ready) {
    this.process = process;
    this.work = work;
    this.ready = ready;
  }

  static synchronized IrcReadTestServiceProcess get() throws Exception {
    if (instance == null) {
      instance = start(Collections.emptyList());
      Runtime.getRuntime().addShutdownHook(new Thread(instance::stop, "irc42-service-stop"));
    }
    return instance;
  }

  /**
   * A second service, the same but for the cluster switch {@code
   * cluster.iceberg.format-v3.enabled}, which it has on (a cluster has it off by default, and so
   * does {@link #get()}). Started on first use, for the format v3 tests only.
   */
  static synchronized IrcReadTestServiceProcess getWithFormatV3() throws Exception {
    if (formatV3Instance == null) {
      formatV3Instance =
          start(Collections.singletonList("-Dcluster.iceberg.format-v3.enabled=true"));
      Runtime.getRuntime()
          .addShutdownHook(new Thread(formatV3Instance::stop, "irc42-v3-service-stop"));
    }
    return formatV3Instance;
  }

  /**
   * A service of its own for one test class, with {@code serviceJvmArgs} (system properties are
   * configuration properties of the service). Not shared: the caller stops it with {@link #stop()};
   * it is stopped when the test JVM exits at the latest.
   */
  static IrcReadTestServiceProcess startOwn(List<String> serviceJvmArgs) throws Exception {
    IrcReadTestServiceProcess own = start(serviceJvmArgs);
    Runtime.getRuntime().addShutdownHook(new Thread(own::stop, "irc42-own-service-stop"));
    return own;
  }

  /**
   * @param serviceJvmArgs more arguments for the service's JVM; a system property is a
   *     configuration property of the service
   */
  private static IrcReadTestServiceProcess start(List<String> serviceJvmArgs) throws Exception {
    Path parent = Paths.get(required("irc42.workdir"));
    Files.createDirectories(parent);
    Path work = Files.createTempDirectory(parent, "service-");
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                required("irc42.java"), "-Xmx768m", "--add-opens=java.base/java.net=ALL-UNNAMED"));
    command.addAll(serviceJvmArgs);
    command.addAll(
        Arrays.asList(
            "-cp",
            required("irc42.service.classpath"),
            "com.linkedin.openhouse.spark42.irc.IrcReadTestService",
            work.toString()));
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
            "IRC test service exited with " + process.exitValue() + ":\n" + logTail(work));
      }
      if (System.nanoTime() > deadline) {
        process.destroyForcibly();
        throw new IllegalStateException("IRC test service did not start:\n" + logTail(work));
      }
      Thread.sleep(250);
    }
    return new IrcReadTestServiceProcess(
        process, work, new ObjectMapper().readTree(readyFile.toFile()));
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

  /** What the seeding recorded for {@code table}: snapshot ids and the Java client's view. */
  JsonNode table(String table) {
    return ready.get("tables").get(table);
  }

  Path work() {
    return work;
  }

  /** The work directories of the services this JVM started so far (each has an access log). */
  static synchronized List<Path> startedWorkDirectories() {
    List<Path> started = new ArrayList<>();
    for (IrcReadTestServiceProcess service :
        new IrcReadTestServiceProcess[] {instance, formatV3Instance}) {
      if (service != null) {
        started.add(service.work);
      }
    }
    return started;
  }

  void stop() {
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
