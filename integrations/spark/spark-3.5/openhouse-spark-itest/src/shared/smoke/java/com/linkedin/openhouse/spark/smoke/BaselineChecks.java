package com.linkedin.openhouse.spark.smoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;

/**
 * Checks against a baseline with declared differences, the mechanism of the write statements
 * ({@link SparkSqlWriteStatements}) and the row-level statements ({@link
 * SparkSqlRowLevelStatements}), so that every platform that runs one of those lists states the
 * checks on which it differs in the same way. A check without a declared difference must give the
 * baseline; one with a declaration must give exactly what it declares, must really differ, and must
 * quote the baseline as the test states it; a declaration that no check consulted fails the run.
 * Every check of a test runs even after one gave an unexpected value, and the test then fails with
 * all of them. With {@link #outcomesFile()} set, the run writes one line per check: test, check,
 * baseline, this platform's value and the verdict.
 *
 * <p>Moved out of {@link SparkSqlWriteStatements} unchanged (work items X2 and X3).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaselineChecks {

  /** The checks on which this platform differs from the baseline. None by default. */
  protected List<Difference> differences() {
    return Collections.emptyList();
  }

  /** The file the run writes its outcomes to, or null for none. */
  protected String outcomesFile() {
    return null;
  }

  // ---------- declared differences ----------

  /** One check on which a platform differs from the baseline. */
  public static final class Difference {
    private final String test;
    private final String check;
    private String baseline = "";
    private String here = "";
    private String kind = "";
    private String why = "";

    private Difference(String test, String check) {
      this.test = test;
      this.check = check;
    }

    /** A difference on {@code check} of the test method named {@code test}. */
    public static Difference on(String test, String check) {
      return new Difference(test, check);
    }

    /** The baseline value, exactly as the test states it. */
    public Difference baseline(Object value) {
      this.baseline = String.valueOf(value);
      return this;
    }

    /** What this platform stores instead. */
    public Difference here(Object value) {
      this.here = String.valueOf(value);
      return this;
    }

    /** The kind of difference and the reason or evidence for it. */
    public Difference because(String differenceKind, String reason) {
      this.kind = differenceKind;
      this.why = reason;
      return this;
    }

    private String key() {
      return test + " / " + check;
    }
  }

  // ---------- running and recording ----------

  private Map<String, Difference> declared;
  private final Set<String> consulted = new LinkedHashSet<>();
  private final Set<String> testsRun = new LinkedHashSet<>();
  private final Set<String> testsDiffering = new LinkedHashSet<>();
  private final List<String> outcomeLines = new ArrayList<>();
  private String test;
  private List<String> unexpected;

  @BeforeEach
  public void beginTest(TestInfo info) {
    if (declared == null) {
      declared = new HashMap<>();
      for (Difference difference : differences()) {
        Assertions.assertNull(
            declared.put(difference.key(), difference),
            "Two differences declared for " + difference.key());
      }
    }
    test = info.getTestMethod().map(m -> m.getName()).orElse("");
    testsRun.add(test);
    unexpected = new ArrayList<>();
  }

  @AfterEach
  public void endTest() {
    if (!unexpected.isEmpty()) {
      Assertions.fail(
          unexpected.size()
              + " unexpected value(s) in "
              + test
              + ":\n"
              + String.join("\n", unexpected));
    }
  }

  @AfterAll
  public void endSuite() throws IOException {
    String file = outcomesFile();
    if (file != null) {
      Path path = Paths.get(file);
      Files.createDirectories(path.getParent());
      List<String> lines = new ArrayList<>(outcomeLines);
      lines.add(
          "# "
              + (testsRun.size() - testsDiffering.size())
              + " of "
              + testsRun.size()
              + " tests identical to the baseline");
      Files.write(path, lines, StandardCharsets.UTF_8);
    }
    List<String> unused =
        (declared == null ? Collections.<String, Difference>emptyMap() : declared)
            .values().stream()
                .filter(d -> testsRun.contains(d.test))
                .map(Difference::key)
                .filter(key -> !consulted.contains(key))
                .sorted()
                .collect(Collectors.toList());
    Assertions.assertEquals(
        Collections.emptyList(), unused, "Differences declared for checks that were not made");
  }

  /**
   * One check: {@code actual} must be {@code baseline}, or what this platform declares for the
   * check instead.
   */
  protected final void check(String check, Object baseline, Object actual) {
    String key = test + " / " + check;
    Difference difference = declared.get(key);
    String expected = String.valueOf(baseline);
    String value = String.valueOf(actual);
    boolean same = Objects.equals(expected, value);
    String verdict;
    if (difference == null) {
      verdict = same ? "same" : "UNEXPECTED";
      if (!same) {
        testsDiffering.add(test);
        unexpected.add("  [" + key + "]\n    baseline: " + expected + "\n    here:     " + value);
      }
    } else {
      consulted.add(key);
      testsDiffering.add(test);
      verdict = "different: " + difference.kind;
      if (!expected.equals(difference.baseline)) {
        verdict = "UNEXPECTED";
        unexpected.add(
            "  ["
                + key
                + "]\n    the difference quotes a baseline the test does not state\n"
                + "    test:       "
                + expected
                + "\n    difference: "
                + difference.baseline);
      } else if (same) {
        verdict = "UNEXPECTED";
        unexpected.add(
            "  [" + key + "]\n    a difference is declared but the value is the baseline's");
      } else if (!value.equals(difference.here)) {
        verdict = "UNEXPECTED";
        unexpected.add(
            "  [" + key + "]\n    declared: " + difference.here + "\n    here:     " + value);
      }
    }
    outcomeLines.add(
        String.join(
            "\t",
            test,
            oneLine(check),
            oneLine(expected),
            oneLine(value),
            verdict,
            difference == null ? "" : oneLine(difference.why)));
  }

  private static String oneLine(String text) {
    return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
  }
}
