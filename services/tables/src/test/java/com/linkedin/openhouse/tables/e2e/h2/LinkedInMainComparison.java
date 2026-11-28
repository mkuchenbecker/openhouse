package com.linkedin.openhouse.tables.e2e.h2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares a suite's outcomes on this build with what LinkedIn main gave ({@link
 * LinkedInMainRecording}), statement by statement (a copy of the Spark 3.5 itest's class of this
 * name, which this module cannot see), for a platform that runs the suite on main's path. A
 * statement whose outcome is not main's must be declared ({@link MainDifference}): the declaration
 * quotes main's recorded outcome exactly, states this build's outcome exactly and gives the reason.
 * A declaration whose statement gives main's outcome, or that quotes something main did not give,
 * fails; so does a declaration no statement consulted, a statement with no recorded outcome, and a
 * recorded statement of a test that ran but that the test no longer runs.
 *
 * <p>A platform calls {@link #observe} with every outcome, {@link #endTest} after each test (and
 * fails the test with what it returns), and {@link #endSuite} once. With a recording directory set
 * ({@link LinkedInMainRecording#recordDirectory()}), which only the recording step against a
 * checkout of main sets, it records instead and compares nothing.
 */
public final class LinkedInMainComparison {

  /** One statement on which this build's outcome is not LinkedIn main's. */
  public static final class MainDifference {
    private final String key;
    private String main = "";
    private String here = "";
    private String kind = "";
    private String why = "";

    private MainDifference(String key) {
      this.key = key;
    }

    /** A difference on the statement {@code key}, as the recording names it. */
    public static MainDifference on(String key) {
      return new MainDifference(key);
    }

    /** Main's outcome, exactly as the recording stores it. */
    public MainDifference main(String outcome) {
      this.main = outcome;
      return this;
    }

    /** This build's outcome, normalized as the recording is. */
    public MainDifference here(String outcome) {
      this.here = outcome;
      return this;
    }

    /** The kind of difference and its cause, with where the cause is in the code. */
    public MainDifference because(String differenceKind, String reason) {
      this.kind = differenceKind;
      this.why = reason;
      return this;
    }

    public String key() {
      return key;
    }

    public String kind() {
      return kind;
    }
  }

  /**
   * What a difference quotes as main's outcome for a statement that main's run never reached (the
   * test took another way on main): the recording has no line for it.
   */
  public static final String NOT_OBSERVED_ON_MAIN = "(main gave no outcome: not reached on main)";

  private final String suite;
  private final LinkedInMainRecording recording;
  private final Path recordDir;
  private final Map<String, MainDifference> declared = new HashMap<>();
  private final Set<String> consulted = new LinkedHashSet<>();
  private final Map<String, String> observed = new HashMap<>();
  private final Set<String> testsRun = new LinkedHashSet<>();
  private final List<String> reportLines = new ArrayList<>();
  private List<String> problems = new ArrayList<>();

  /**
   * The comparison of {@code suite} with its stored recording, with the differences this build
   * declares; or, when the run records, a recorder.
   */
  public LinkedInMainComparison(String suite, Collection<MainDifference> differences)
      throws IOException {
    this.suite = suite;
    this.recordDir = LinkedInMainRecording.recordDirectory();
    this.recording = recordDir == null ? LinkedInMainRecording.load(suite) : null;
    for (MainDifference difference : differences) {
      if (declared.put(difference.key, difference) != null) {
        throw new IllegalStateException("Two differences declared for " + difference.key);
      }
    }
  }

  /** The stored recording this run compares with; null when the run records. */
  public LinkedInMainRecording recording() {
    return recording;
  }

  /**
   * One outcome of the test {@code test}: {@code key} names the statement (the test's name first,
   * as the recording's keys are), {@code outcome} is what it gave.
   */
  public void observe(String test, String key, String outcome) {
    testsRun.add(test);
    String name = LinkedInMainRecording.oneLine(key);
    String here = LinkedInMainRecording.normalize(outcome);
    if (observed.containsKey(name) && !observed.get(name).equals(here) && recordDir != null) {
      throw new IllegalStateException("Two outcomes for one statement in one run: " + name);
    }
    observed.put(name, here);
    if (recording == null) {
      return;
    }
    Set<String> main = recording.outcomes(name);
    MainDifference difference = declared.get(name);
    String verdict;
    if (main == null && difference != null) {
      consulted.add(name);
      verdict = "different: " + difference.kind;
      if (!NOT_OBSERVED_ON_MAIN.equals(difference.main)) {
        verdict = "UNEXPECTED";
        problems.add(
            "  ["
                + name
                + "]\n    main gave no outcome for this statement; the difference quotes: "
                + difference.main);
      } else if (!here.equals(difference.here)) {
        verdict = "UNEXPECTED";
        problems.add(
            "  [" + name + "]\n    declared: " + difference.here + "\n    here:     " + here);
      }
    } else if (main == null) {
      verdict = "NOT RECORDED";
      problems.add(
          "  ["
              + name
              + "]\n    no outcome of LinkedIn main is recorded for this statement: record it"
              + " against main (scripts/linkedin-main-baseline/record.sh), or add it to"
              + " linkedin-main/"
              + suite
              + ".tsv as \""
              + LinkedInMainRecording.NOT_ON_MAIN
              + "<the reason>\"\n    here: "
              + here);
    } else if (recording.notOnMain(name)) {
      verdict = "not on main";
      if (difference != null) {
        consulted.add(name);
        problems.add("  [" + name + "]\n    a difference is declared for a statement not on main");
      }
    } else if (difference == null) {
      verdict = main.contains(here) ? "same" : "UNEXPECTED";
      if (!main.contains(here)) {
        problems.add(
            "  ["
                + name
                + "]\n    differs from LinkedIn main and no difference is declared\n    main: "
                + recording.quoted(name)
                + "\n    here: "
                + here);
      }
    } else {
      consulted.add(name);
      verdict = "different: " + difference.kind;
      if (!recording.quoted(name).equals(difference.main)) {
        verdict = "UNEXPECTED";
        problems.add(
            "  ["
                + name
                + "]\n    the difference quotes an outcome main did not give\n    main:      "
                + recording.quoted(name)
                + "\n    quoted:    "
                + difference.main);
      } else if (main.contains(here)) {
        verdict = "UNEXPECTED";
        problems.add(
            "  [" + name + "]\n    a difference is declared but the outcome is main's: " + here);
      } else if (!here.equals(difference.here)) {
        verdict = "UNEXPECTED";
        problems.add(
            "  [" + name + "]\n    declared: " + difference.here + "\n    here:     " + here);
      }
    }
    reportLines.add(
        String.join(
            "\t",
            name,
            recording.quoted(name) == null ? "" : recording.quoted(name),
            here,
            verdict,
            difference == null ? "" : LinkedInMainRecording.oneLine(difference.why)));
  }

  /** What differed from main without a declaration in the test that ended; empty if nothing. */
  public List<String> endTest() {
    List<String> ended = problems;
    problems = new ArrayList<>();
    return ended;
  }

  /**
   * Ends the run: records, or writes the comparison to {@code reportFile} (if not null) and returns
   * what is wrong with the declarations and the recording for the tests that ran.
   */
  public List<String> endSuite(Map<String, String> recordHeader, String reportFile)
      throws IOException {
    if (recordDir != null) {
      LinkedInMainRecording.write(recordDir, suite, recordHeader, observed);
      return new ArrayList<>();
    }
    if (reportFile != null) {
      Path path = Paths.get(reportFile);
      Files.createDirectories(path.getParent());
      Files.write(path, reportLines, StandardCharsets.UTF_8);
    }
    List<String> wrong = new ArrayList<>();
    for (MainDifference difference : declared.values()) {
      if (testsRun.contains(testOf(difference.key)) && !consulted.contains(difference.key)) {
        wrong.add(
            "a difference from main is declared for a statement that was not run: "
                + difference.key);
      }
    }
    for (String key : recording.keys()) {
      if (testsRun.contains(testOf(key)) && !observed.containsKey(key)) {
        wrong.add(
            "recorded on LinkedIn main but no longer run (re-record, or remove it from"
                + " linkedin-main/"
                + suite
                + ".tsv with the change that removed it): "
                + key);
      }
    }
    return wrong.stream().sorted().collect(Collectors.toList());
  }

  /** The test a key belongs to: the key up to its first space. */
  private static String testOf(String key) {
    int space = key.indexOf(' ');
    return space < 0 ? key : key.substring(0, space);
  }
}
