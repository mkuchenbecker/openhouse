package com.linkedin.openhouse.jobs42;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tables service ({@code JobsTestService}, this module's {@code service} source set) in its own
 * JVM, on a free port with a private work directory, stopped when its standard input closes or when
 * this JVM exits. The build passes the service classpath, the java executable and the parent work
 * directory as system properties ({@code jobs42.*}).
 */
public final class JobsTestServiceProcess implements AutoCloseable {

  private static final long START_TIMEOUT_SECONDS = 300;

  private final Process process;
  private final Path work;
  private final int port;

  private JobsTestServiceProcess(Process process, Path work, int port) {
    this.process = process;
    this.work = work;
    this.port = port;
  }

  /** @param appArgs arguments for the application, e.g. {@code --cluster.x=y} */
  public static JobsTestServiceProcess start(String... appArgs) throws Exception {
    Path parent = Paths.get(required("jobs42.workdir"));
    Files.createDirectories(parent);
    Path work = Files.createTempDirectory(parent, "service-");
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                required("jobs42.java"),
                "-Xmx768m",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "-cp",
                required("jobs42.service.classpath"),
                "com.linkedin.openhouse.jobs42.JobsTestService",
                work.toString()));
    command.addAll(Arrays.asList(appArgs));
    Process process =
        new ProcessBuilder(command)
            .directory(work.toFile())
            .redirectErrorStream(true)
            .redirectOutput(work.resolve("service.log").toFile())
            .start();
    Path ready = work.resolve("ready.json");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
    while (!Files.exists(ready)) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "jobs test service exited with " + process.exitValue() + ":\n" + logTail(work));
      }
      if (System.nanoTime() > deadline) {
        process.destroyForcibly();
        throw new IllegalStateException("jobs test service did not start:\n" + logTail(work));
      }
      Thread.sleep(250);
    }
    Matcher m =
        Pattern.compile("\"port\":\\s*(\\d+)")
            .matcher(new String(Files.readAllBytes(ready), StandardCharsets.UTF_8));
    if (!m.find()) {
      throw new IllegalStateException("no port in " + ready);
    }
    JobsTestServiceProcess started =
        new JobsTestServiceProcess(process, work, Integer.parseInt(m.group(1)));
    Runtime.getRuntime().addShutdownHook(new Thread(started::close, "jobs42-service-stop"));
    return started;
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

  public int port() {
    return port;
  }

  /** The native API's base, e.g. {@code http://localhost:43123}. */
  public String uri() {
    return "http://localhost:" + port;
  }

  /** The Iceberg REST Catalog routes' base. */
  public String ircUri() {
    return uri() + "/iceberg";
  }

  public Path work() {
    return work;
  }

  @Override
  public void close() {
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
