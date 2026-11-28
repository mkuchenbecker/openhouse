package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.spark.smoke.LinkedInMainComparison;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;

/**
 * Runs the Spark SQL smoke suite against the legacy catalog path, and compares every statement's
 * outcome with what LinkedIn main gave on the same path ({@code linkedin-main/smoke-scenarios.tsv},
 * recorded by running main's code): equal, or a difference {@link LinkedInMainDifferences} declares
 * with its cause.
 */
public class LegacyPathSparkSqlSmokeTest extends SparkSqlSmokeTest {

  private final LinkedInMainComparison main;

  public LegacyPathSparkSqlSmokeTest() throws Exception {
    main = new LinkedInMainComparison("smoke-scenarios", LinkedInMainDifferences.smokeScenarios());
  }

  @Override
  protected String catalogPath() {
    return LEGACY;
  }

  @Override
  protected void outcome(String scenario, String key, Outcome actual) {
    main.observe(scenario, key, actual.toString());
  }

  @AfterEach
  void sameAsLinkedInMain() {
    List<String> problems = main.endTest();
    if (!problems.isEmpty()) {
      Assertions.fail(
          problems.size() + " outcome(s) not LinkedIn main's:\n" + String.join("\n", problems));
    }
  }

  @AfterAll
  void recordingMatchesTheSuite() throws Exception {
    String dir = System.getProperty("smoke.outcomes.dir");
    List<String> wrong =
        main.endSuite(
            Collections.emptyMap(),
            dir == null ? null : dir + "/spark-3.5-legacy-vs-linkedin-main.tsv");
    wrong.addAll(
        main.recordedTestsNotDeclared(
            LinkedInMainComparison.enabledTests(
                getClass(), m -> m.contains("_") ? m.substring(0, m.indexOf('_')) : m),
            "",
            LinkedInMainDifferences.absentTests()));
    Assertions.assertEquals(Collections.emptyList(), wrong);
  }
}
