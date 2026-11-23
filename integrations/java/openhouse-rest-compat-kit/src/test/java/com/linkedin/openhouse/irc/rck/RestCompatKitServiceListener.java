package com.linkedin.openhouse.irc.rck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Starts the tables service ({@code RestCompatKitService} in this module's {@code service} source
 * set) in its own JVM when the test JVM's JUnit session opens, before the kit's classes are even
 * discovered, and stops it when the session closes. The kit configures its {@code RESTCatalog} from
 * the system properties ({@code RCKUtils.initCatalogClient}), so this sets {@code uri} to the
 * service's IRC base path and {@code token} to the bearer token of a principal that is allowed
 * everything. The build passes the service classpath, the java executable and the parent work
 * directory as system properties.
 *
 * <p>The service learns that it should stop when its standard input closes, so it also stops if
 * this JVM dies without closing the session.
 */
public final class RestCompatKitServiceListener implements LauncherSessionListener {

  private static final long START_TIMEOUT_SECONDS = 300;

  private Process process;

  @Override
  public void launcherSessionOpened(LauncherSession session) {
    if (System.getProperty("rck.service.classpath") == null || process != null) {
      // Not the kit's test task (for example an IDE run of another class): nothing to start.
      return;
    }
    try {
      Path parent = Paths.get(required("rck.service.workdir"));
      Files.createDirectories(parent);
      Path work = Files.createTempDirectory(parent, "service-");
      List<String> command =
          Arrays.asList(
              required("rck.service.java"),
              "-Xmx768m",
              "--add-opens=java.base/java.net=ALL-UNNAMED",
              "-cp",
              required("rck.service.classpath"),
              "com.linkedin.openhouse.irc.rck.RestCompatKitService",
              work.toString());
      process =
          new ProcessBuilder(command)
              .directory(work.toFile())
              .redirectErrorStream(true)
              .redirectOutput(work.resolve("service.log").toFile())
              .start();
      Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "rck-service-stop"));
      Path readyFile = work.resolve("ready.json");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
      while (!Files.exists(readyFile)) {
        if (!process.isAlive()) {
          throw new IllegalStateException(
              "REST compatibility kit service exited with "
                  + process.exitValue()
                  + ":\n"
                  + logTail(work));
        }
        if (System.nanoTime() > deadline) {
          stop();
          throw new IllegalStateException(
              "REST compatibility kit service did not start:\n" + logTail(work));
        }
        Thread.sleep(250);
      }
      JsonNode ready = new ObjectMapper().readTree(readyFile.toFile());
      System.setProperty("uri", "http://localhost:" + ready.get("port").asInt() + "/iceberg");
      System.setProperty("token", ready.get("token").asText());
    } catch (IOException e) {
      throw new IllegalStateException("Could not start the REST compatibility kit service", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted starting the service", e);
    }
  }

  @Override
  public void launcherSessionClosed(LauncherSession session) {
    stop();
  }

  private synchronized void stop() {
    if (process == null) {
      return;
    }
    try {
      process.getOutputStream().close();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      process.destroyForcibly();
    }
    process = null;
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
}
