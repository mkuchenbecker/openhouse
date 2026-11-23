package com.linkedin.openhouse.spark42.smoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@code SparkSqlSmokeScenarios} is a copy of the scenarios of the Spark 3.5 itest's {@code
 * SparkSqlSmokeTest}, because the Spark 3.5 suite could not be changed to share them when the copy
 * was made. This test keeps the copy honest until there is one list: it reads both source files and
 * fails when a scenario is in one and not in the other, or when a scenario's string literals (the
 * pieces of its statements, its expected rows, its expected exception classes and messages) are not
 * the same in both, apart from the deviations listed in {@link #DEVIATIONS}.
 *
 * <p>The follow-up that removes the copy and this test: the Spark 3.5 itest names this module's
 * {@code src/shared/test/java} as a source directory (plain files, no new dependency, as the Spark
 * 3.1 itest names the Spark 3.5 itest's), {@code SparkSqlSmokeTest} extends {@code
 * SparkSqlSmokeScenarios} and keeps only its sessions, and the message of the namespace statements
 * on Iceberg 1.12 (decision D14b) becomes a declared difference of that line.
 */
public class SmokeScenariosCopyTest {

  private static final Pattern SCENARIO =
      Pattern.compile("^  public void (s\\w+)\\(\\) throws Exception \\{$");
  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  /** What a scenario has only in the Spark 3.5 suite, and only in the copy, as literals. */
  private static final class Deviation {
    final List<String> onlyInSpark35;
    final List<String> onlyInCopy;

    Deviation(List<String> onlyInSpark35, List<String> onlyInCopy) {
      this.onlyInSpark35 = sorted(onlyInSpark35);
      this.onlyInCopy = sorted(onlyInCopy);
    }

    @Override
    public String toString() {
      return "only in the Spark 3.5 suite " + onlyInSpark35 + ", only in the copy " + onlyInCopy;
    }
  }

  private static final Map<String, Deviation> DEVIATIONS = new TreeMap<>();

  static {
    // The copy names the snapshot id in what it records for the statement.
    DEVIATIONS.put(
        "s11_timeTravel_versionAsOf",
        new Deviation(
            Collections.emptyList(),
            Arrays.asList(" VERSION AS OF <the first snapshot's id>", "SELECT * FROM ")));
    // The Spark 3.5 suite also holds the message of its Iceberg 1.12 build (decision D14b). The
    // copy's baseline is the Iceberg 1.5.2 message only.
    DEVIATIONS.put(
        "s19_namespaces_showAndCreate",
        new Deviation(
            Collections.singletonList("Checking if database exists is not supported"),
            Collections.emptyList()));
    DEVIATIONS.put(
        "s24_namespaces_otherStatementsUnsupported",
        new Deviation(
            Collections.singletonList("Checking if database exists is not supported"),
            Collections.emptyList()));
    // The Spark 3.5 suite reads all properties through a helper outside the scenario.
    DEVIATIONS.put(
        "s21_ctas_tablePropertiesMatchCreate",
        new Deviation(
            Collections.emptyList(), Arrays.asList("SHOW TBLPROPERTIES ", "SHOW TBLPROPERTIES ")));
    // The directory the service keeps tables in is the platform's in the copy (/tmp on the
    // baseline).
    DEVIATIONS.put(
        "s26_describeExtended_nameTypeProvider",
        new Deviation(
            Collections.singletonList("Location,/tmp/"), Arrays.asList("Location,", "/")));
  }

  @Test
  void theCopyHasExactlyTheScenariosOfTheSpark35Suite() throws IOException {
    Assertions.assertEquals(
        new ArrayList<>(new TreeMap<>(literals("smoke.spark35.suite")).keySet()),
        new ArrayList<>(new TreeMap<>(literals("smoke.scenarios.source")).keySet()));
  }

  @Test
  void everyScenarioHasTheSameStatementsAndExpectedValuesInBoth() throws IOException {
    Map<String, List<String>> spark35 = literals("smoke.spark35.suite");
    Map<String, List<String>> copy = literals("smoke.scenarios.source");
    Map<String, String> found = new TreeMap<>();
    for (Map.Entry<String, List<String>> scenario : spark35.entrySet()) {
      List<String> onlyInSpark35 = new ArrayList<>(scenario.getValue());
      List<String> onlyInCopy =
          new ArrayList<>(copy.getOrDefault(scenario.getKey(), Collections.emptyList()));
      for (String literal : scenario.getValue()) {
        if (onlyInCopy.remove(literal)) {
          onlyInSpark35.remove(literal);
        }
      }
      if (!onlyInSpark35.isEmpty() || !onlyInCopy.isEmpty()) {
        found.put(scenario.getKey(), new Deviation(onlyInSpark35, onlyInCopy).toString());
      }
    }
    Map<String, String> listed = new TreeMap<>();
    DEVIATIONS.forEach((scenario, deviation) -> listed.put(scenario, deviation.toString()));
    Assertions.assertEquals(listed, found);
  }

  /** Each scenario method of the source file a system property names, with its string literals. */
  private static Map<String, List<String>> literals(String property) throws IOException {
    String path = System.getProperty(property);
    Assertions.assertNotNull(path, "System property " + property + " is not set by the build");
    Map<String, List<String>> scenarios = new LinkedHashMap<>();
    List<String> current = null;
    for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
      Matcher scenario = SCENARIO.matcher(line);
      if (scenario.matches()) {
        current = new ArrayList<>();
        scenarios.put(scenario.group(1), current);
      } else if (line.equals("  }")) {
        current = null;
      } else if (current != null && !line.trim().startsWith("//")) {
        Matcher literal = LITERAL.matcher(line);
        while (literal.find()) {
          current.add(literal.group(1));
        }
      }
    }
    Assertions.assertFalse(scenarios.isEmpty(), "No scenario found in " + path);
    return scenarios;
  }

  private static List<String> sorted(List<String> values) {
    List<String> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted;
  }
}
