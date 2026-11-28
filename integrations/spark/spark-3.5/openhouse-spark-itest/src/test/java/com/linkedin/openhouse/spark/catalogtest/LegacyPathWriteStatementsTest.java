package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.spark.smoke.LinkedInMainComparison;
import com.linkedin.openhouse.spark.smoke.SparkSqlWriteStatements;
import java.util.Collections;
import java.util.List;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;

/**
 * The write statements ({@link SparkSqlWriteStatements}) on the legacy catalog path, the OpenHouse
 * catalog of Spark 3.5 over the native tables API, reading what the service stored as {@link
 * RestHttpPathWriteStatementsTest} does. Every check's value is compared with what LinkedIn main
 * gave on the same path ({@code linkedin-main/write-statements.tsv}, recorded by running main's
 * code): equal, or a difference {@link LinkedInMainDifferences} declares with its cause. Against
 * the suite's own baseline (Spark 4.2 over Iceberg REST) it differs where the Spark 3.5 plain REST
 * path does.
 */
public class LegacyPathWriteStatementsTest extends RestHttpPathWriteStatementsTest {

  private final LinkedInMainComparison main;

  public LegacyPathWriteStatementsTest() throws Exception {
    main =
        new LinkedInMainComparison("write-statements", LinkedInMainDifferences.writeStatements());
  }

  /** The shared session's {@value #CATALOG} catalog is the OpenHouse catalog already. */
  @Override
  protected void configurePath(SparkSession session, SparkSession base) {}

  @Override
  protected void value(String test, String key, String actual) {
    main.observe(test, key, actual);
  }

  @Override
  protected String outcomesFile() {
    String dir = System.getProperty("smoke.outcomes.dir");
    return dir == null ? null : dir + "/spark-3.5-legacy-write-outcomes.tsv";
  }

  @AfterEach
  void sameAsLinkedInMain() {
    List<String> problems = main.endTest();
    if (!problems.isEmpty()) {
      Assertions.fail(
          problems.size() + " value(s) not LinkedIn main's:\n" + String.join("\n", problems));
    }
  }

  @AfterAll
  void recordingMatchesTheSuite() throws Exception {
    String dir = System.getProperty("smoke.outcomes.dir");
    List<String> wrong =
        main.endSuite(
            Collections.emptyMap(),
            dir == null ? null : dir + "/spark-3.5-legacy-write-vs-linkedin-main.tsv");
    wrong.addAll(
        main.recordedTestsNotDeclared(
            LinkedInMainComparison.enabledTests(getClass(), m -> m),
            "",
            LinkedInMainDifferences.absentTests()));
    Assertions.assertEquals(Collections.emptyList(), wrong);
  }
}
