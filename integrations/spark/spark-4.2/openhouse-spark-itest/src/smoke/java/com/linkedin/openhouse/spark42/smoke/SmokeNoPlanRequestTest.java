package com.linkedin.openhouse.spark42.smoke;

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
 * Run after every other test class of the smoke JVM ({@code @Order} with the class orderer the
 * build configures): the whole Spark SQL smoke suite and the write statements, through
 * default-configured Spark 4.2 catalogs, sent the service no scan planning request. The service
 * lists the plan route, but nothing it sends tells a client to plan on the server.
 */
@Order(Integer.MAX_VALUE)
public class SmokeNoPlanRequestTest {

  @Test
  void theSuiteSentNoPlanRequest() throws Exception {
    Path access = SmokeTestService.get().work().resolve("access");
    List<String> lines = new ArrayList<>();
    try (Stream<Path> files = Files.list(access)) {
      for (Path file : files.collect(Collectors.toList())) {
        lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
      }
    }
    Assertions.assertFalse(lines.isEmpty(), "the access log is empty");
    Assertions.assertEquals(
        new ArrayList<String>(),
        lines.stream()
            .filter(l -> l.matches(".*/tables/[^/ ]+/(plan|tasks)[/ ].*"))
            .collect(Collectors.toList()));
    System.out.println("Requests the smoke suite sent: " + lines.size() + ", plan requests: 0");
  }
}
