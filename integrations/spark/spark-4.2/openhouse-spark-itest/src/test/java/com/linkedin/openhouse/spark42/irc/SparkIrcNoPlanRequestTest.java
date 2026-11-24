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
    Assertions.assertEquals(new ArrayList<String>(), others, "plan requests from other tests");
    System.out.println(
        "Plan requests in this JVM: "
            + plans.size()
            + " (all from SparkIrcScanPlanningTest), of "
            + lines.size()
            + " requests");
  }
}
