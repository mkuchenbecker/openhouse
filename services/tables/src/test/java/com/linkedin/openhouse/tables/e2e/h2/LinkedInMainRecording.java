package com.linkedin.openhouse.tables.e2e.h2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What LinkedIn main ({@value #COMMIT}) answered to every request of a suite, as recorded by
 * running main's own code (the native tables API) with nothing of this branch but the suite's test
 * sources; the same format and code as the Spark 3.5 itest's class of this name, which this module
 * cannot see, and stored next to the suites as {@code linkedin-main/<suite>.tsv} on the test
 * classpath.
 *
 * <p>The file is data, never written by a test run: a run only reads it. Regenerating it is an
 * explicit step against a checkout of main ({@code scripts/linkedin-main-baseline/record.sh}), the
 * only code that sets the record directory ({@value #RECORD_DIR_ENV}) this class writes to instead.
 *
 * <p>Format: header lines start with {@code #}; {@code # name: value} lines say what was recorded,
 * from which commit, when, with which JDK, Spark and Iceberg, and by which command. Every other
 * line is {@code key<TAB>outcome}, sorted, where the key names the statement and the outcome is
 * what the statement gave on main after {@link #normalize}: the rows, or the exception class and
 * message. A key on more than one line gave each of those outcomes in different recording runs
 * (main's answer was not stable from one JVM to the next); any of them is main's. An outcome that
 * starts with {@value #NOT_ON_MAIN} marks a statement main cannot run, with the reason.
 */
public final class LinkedInMainRecording {

  /** The commit every recording is of. */
  public static final String COMMIT = "bb8929e1";

  /** The prefix of an outcome that marks a statement main cannot run, followed by the reason. */
  public static final String NOT_ON_MAIN = "NOT ON MAIN: ";

  /** The environment variable that switches a run on main from comparing to recording. */
  public static final String RECORD_DIR_ENV = "OPENHOUSE_LINKEDIN_MAIN_RECORD_DIR";

  private final String suite;
  private final Map<String, String> header;
  private final Map<String, Set<String>> outcomes;

  private LinkedInMainRecording(
      String suite, Map<String, String> header, Map<String, Set<String>> outcomes) {
    this.suite = suite;
    this.header = header;
    this.outcomes = outcomes;
  }

  /** The stored recording of {@code suite}; fails if there is none on the classpath. */
  public static LinkedInMainRecording load(String suite) throws IOException {
    String resource = "/linkedin-main/" + suite + ".tsv";
    InputStream in = LinkedInMainRecording.class.getResourceAsStream(resource);
    if (in == null) {
      throw new IllegalStateException(
          "No recording of LinkedIn main on the classpath: " + resource);
    }
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    return parse(suite, lines);
  }

  /** Parses the lines of a recording file. */
  public static LinkedInMainRecording parse(String suite, List<String> lines) {
    Map<String, String> header = new LinkedHashMap<>();
    Map<String, Set<String>> outcomes = new LinkedHashMap<>();
    for (String line : lines) {
      if (line.startsWith("#")) {
        int colon = line.indexOf(':');
        if (colon > 0) {
          header.put(line.substring(1, colon).trim(), line.substring(colon + 1).trim());
        }
        continue;
      }
      if (line.isEmpty()) {
        continue;
      }
      int tab = line.indexOf('\t');
      if (tab < 0) {
        throw new IllegalStateException("Not a key and an outcome: " + line);
      }
      outcomes
          .computeIfAbsent(line.substring(0, tab), k -> new LinkedHashSet<>())
          .add(line.substring(tab + 1));
    }
    return new LinkedInMainRecording(suite, header, outcomes);
  }

  public String suite() {
    return suite;
  }

  /** The header's {@code name: value} pairs. */
  public Map<String, String> header() {
    return Collections.unmodifiableMap(header);
  }

  /** Every recorded key, in file order. */
  public Set<String> keys() {
    return Collections.unmodifiableSet(outcomes.keySet());
  }

  /** Main's outcomes of {@code key} (more than one if main's answer varied), or null. */
  public Set<String> outcomes(String key) {
    Set<String> recorded = outcomes.get(key);
    return recorded == null ? null : Collections.unmodifiableSet(recorded);
  }

  /** Main's outcomes of {@code key} as one text, the form a declared difference quotes. */
  public String quoted(String key) {
    Set<String> recorded = outcomes.get(key);
    return recorded == null ? null : String.join(" | one of | ", new TreeSet<>(recorded));
  }

  /** Whether {@code key} is marked as a statement main cannot run. */
  public boolean notOnMain(String key) {
    Set<String> recorded = outcomes.get(key);
    return recorded != null && recorded.stream().anyMatch(o -> o.startsWith(NOT_ON_MAIN));
  }

  // ---------- normalization ----------

  private static final Pattern[] PATTERNS = {
    // Cron schedules the service draws at random (a replication policy's minute and hour).
    Pattern.compile("0 [0-9]+ [0-9]+/([0-9]+) \\? \\* \\* \\*"),
    Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
    Pattern.compile("(localhost|127\\.0\\.0\\.1):[0-9]+"),
    Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?"),
    // Spark's expression ids in a logical plan (name#12), not a numbering such as "thrown #1".
    Pattern.compile("(?<=[A-Za-z0-9_])#[0-9]+"),
    // Object identities in a default toString.
    Pattern.compile("@[0-9a-f]{5,8}\\b"),
    // Snapshot ids, epoch milliseconds and other values of ten digits or more.
    Pattern.compile("[0-9]{10,}"),
  };

  private static final String[] REPLACEMENTS = {
    "0 <minute> <hour>/$1 ? * * *",
    "<uuid>",
    "<host:port>",
    "<time>",
    "#<id>",
    "@<identity>",
    "<number>",
  };

  /**
   * An outcome with the values that differ from one run to the next replaced by a placeholder
   * (random ids, ports, times, snapshot ids, plan expression ids, a random replication schedule),
   * on one line: what the recording stores and what a run compares with it.
   */
  public static String normalize(String outcome) {
    return oneLine(normalizeValues(outcome));
  }

  /** As {@link #normalize}, without putting the text on one line. */
  public static String normalizeValues(String outcome) {
    String text = String.valueOf(outcome);
    for (int i = 0; i < PATTERNS.length; i++) {
      text = PATTERNS[i].matcher(text).replaceAll(REPLACEMENTS[i]);
    }
    return OS_USER == null ? text : OS_USER.matcher(text).replaceAll("<os user>");
  }

  /**
   * The operating system user the JVM runs as, which Spark reports as a table's owner: root on one
   * machine, runner on a CI runner.
   */
  private static final Pattern OS_USER = osUser();

  private static Pattern osUser() {
    String user = System.getProperty("user.name");
    return user == null || user.length() < 3
        ? null
        : Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(user) + "(?![A-Za-z0-9_])");
  }

  /**
   * Several answers to one observation as one outcome: sorted, equal ones counted ({@code 3 x
   * <answer>}), joined by {@code " ; "}.
   */
  public static String answers(List<String> answers) {
    Map<String, Integer> counted = new TreeMap<>();
    for (String answer : answers) {
      counted.merge(normalizeValues(answer), 1, Integer::sum);
    }
    return counted.entrySet().stream()
        .map(e -> e.getValue() == 1 ? e.getKey() : e.getValue() + " x " + e.getKey())
        .collect(Collectors.joining(" ; "));
  }

  /** A text on one line: backslash, tab and line breaks escaped. */
  public static String oneLine(String text) {
    return text.replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
  }

  // ---------- recording (run only against a checkout of main) ----------

  /** The environment variable the recording step sets to the commit it checked out. */
  public static final String COMMIT_ENV = "OPENHOUSE_LINKEDIN_MAIN_COMMIT";

  /** The environment variable the recording step sets to the command it ran. */
  public static final String COMMAND_ENV = "OPENHOUSE_LINKEDIN_MAIN_COMMAND";

  /**
   * The header of a recording made in this JVM over {@code path}: the commit and command the
   * recording step names, today's date, and the JDK, Spark and Iceberg this JVM runs.
   */
  public static Map<String, String> header(String path) {
    Map<String, String> header = new LinkedHashMap<>();
    header.put("commit", env(COMMIT_ENV));
    header.put("recorded", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
    header.put(
        "jdk",
        System.getProperty("java.vendor")
            + " "
            + System.getProperty("java.runtime.version", System.getProperty("java.version")));
    header.put("spark", version("org.apache.spark.package", "SPARK_VERSION"));
    // The jars the classes come from: iceberg-build.properties is in several Iceberg jars, and the
    // first on the classpath need not be the Iceberg that runs.
    header.put(
        "iceberg",
        "core from "
            + jarOf("org.apache.iceberg.Table")
            + ", Spark catalog from "
            + jarOf("org.apache.iceberg.spark.SparkCatalog"));
    header.put("path", path);
    header.put("command", env(COMMAND_ENV));
    return header;
  }

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null ? "(not set)" : value;
  }

  private static String jarOf(String className) {
    try {
      java.security.CodeSource source =
          Class.forName(className).getProtectionDomain().getCodeSource();
      String location = source == null ? "(unknown)" : source.getLocation().getPath();
      return location.substring(location.lastIndexOf('/', location.length() - 2) + 1);
    } catch (ClassNotFoundException | LinkageError e) {
      return "(none)";
    }
  }

  private static String version(String className, String method) {
    try {
      return String.valueOf(Class.forName(className).getMethod(method).invoke(null));
    } catch (ReflectiveOperationException | LinkageError e) {
      return "(none)";
    }
  }

  /** The directory to record into, from {@value #RECORD_DIR_ENV}, or null when the run compares. */
  public static Path recordDirectory() {
    String dir = System.getenv(RECORD_DIR_ENV);
    return dir == null || dir.isEmpty() ? null : Paths.get(dir);
  }

  /**
   * Writes {@code observed} (key to normalized outcome) as {@code <suite>.tsv} into {@code dir},
   * with the header lines given, sorted by key.
   */
  public static void write(
      Path dir, String suite, Map<String, String> headerLines, Map<String, String> observed)
      throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add("# LinkedIn main's outcomes of the " + suite + " statements");
    for (Map.Entry<String, String> entry : headerLines.entrySet()) {
      lines.add("# " + entry.getKey() + ": " + entry.getValue());
    }
    lines.add("# key<TAB>outcome, sorted by key");
    lines.addAll(
        new TreeMap<>(observed)
            .entrySet().stream()
                .map(e -> oneLine(e.getKey()) + "\t" + e.getValue())
                .collect(Collectors.toList()));
    Files.createDirectories(dir);
    Files.write(dir.resolve(suite + ".tsv"), lines, StandardCharsets.UTF_8);
  }
}
