package com.linkedin.openhouse.spark42.irc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Run after every other test class of this JVM ({@code @Order} with the class orderer the build
 * configures): no plan request reached a service from any test except {@link
 * SparkIrcScanPlanningTest}, whose catalogs alone are configured with {@code
 * scan-planning-mode=server}. So a default-configured Spark 4.2 catalog, across the whole suite's
 * reads and writes (SELECT, CTAS, INSERT, DELETE, UPDATE, MERGE, metadata tables, views), never
 * asks the service to plan: the service lists the route but tells no client to use it.
 *
 * <p>In the {@code testServerPlanning} run of the same suite ({@link ServerPlanning}), every
 * catalog asks for server planning, and this check asserts the converse: the other tests'
 * statements did send plan requests.
 */
@Order(Integer.MAX_VALUE)
public class SparkIrcNoPlanRequestTest {

  @Test
  void onlyTheServerPlanningCatalogsSentPlanRequests() throws Exception {
    // Make sure at least the shared service ran, so that there is a log to read.
    IrcSpark.service();
    List<Path> works = IrcReadTestServiceProcess.startedWorkDirectories();
    Assertions.assertFalse(works.isEmpty());
    List<String> lines = new ArrayList<>();
    for (Path work : works) {
      try (Stream<Path> files = Files.list(work.resolve("access"))) {
        for (Path file : files.collect(Collectors.toList())) {
          lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        }
      }
    }
    Assertions.assertFalse(lines.isEmpty(), "the access logs are empty");
    List<String> plans =
        lines.stream()
            .filter(l -> l.matches(".*/tables/[^/ ]+/(plan|tasks)[/ ].*"))
            .collect(Collectors.toList());
    List<String> others =
        plans.stream()
            .filter(
                l ->
                    !l.startsWith(
                        "POST /iceberg/v1/namespaces/" + SparkIrcScanPlanningTest.DB + "/tables/"))
            .collect(Collectors.toList());
    // Either way, a client only ever posts a plan: the service answers every plan completed or
    // failed, so no client fetches a stored plan, cancels one, or pages its tasks.
    Assertions.assertEquals(
        new ArrayList<String>(),
        plans.stream()
            .filter(l -> !l.matches("POST /iceberg/v1/namespaces/[^/ ]+/tables/[^/ ]+/plan .*"))
            .collect(Collectors.toList()),
        "requests for stored plans or plan tasks");
    if (ServerPlanning.selected()) {
      // The suite's own run with server planning (testServerPlanning): every catalog asked for it
      // but those declared with the reason (format v3 tables, which the route refuses), so the
      // other tests' reads and writes did plan on the server.
      Assertions.assertFalse(others.isEmpty(), "no plan request from the other tests");
      List<String> declared = ServerPlanning.notApplied();
      Assertions.assertFalse(declared.isEmpty(), "the format v3 catalogs are declared");
      for (String line : declared) {
        Assertions.assertTrue(line.endsWith(ServerPlanning.FORMAT_V3), line);
      }
      System.out.println("Catalogs that kept client planning, declared: " + declared);
      System.out.println(
          "Plan requests in this JVM (server planning selected): "
              + plans.size()
              + ", of which "
              + others.size()
              + " from tests other than the scan planning tests, of "
              + lines.size()
              + " requests");
      return;
    }
    Assertions.assertEquals(new ArrayList<String>(), others, "plan requests from other tests");
    System.out.println(
        "Plan requests in this JVM: "
            + plans.size()
            + " (all from SparkIrcScanPlanningTest), of "
            + lines.size()
            + " requests");
  }
}
