package com.linkedin.openhouse.tables.e2e.h2;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The stored recordings of LinkedIn main are of {@value LinkedInMainRecording#COMMIT}, say how they
 * were made, and are sorted lines of a key and an outcome, so that a diff of one shows exactly what
 * changed in what main is recorded to do.
 */
public class LinkedInMainRecordingFilesTest {

  /**
   * The keys main answered differently in different recording runs, reviewed: none in this
   * recording. A key with a second line was added by hand and fails.
   */
  private static final java.util.Map<String, java.util.Set<String>> VARYING =
      java.util.Collections.emptyMap();

  @ParameterizedTest
  @ValueSource(strings = {LinkedInMainAnswers.SUITE})
  void isARecordingOfLinkedInMain(String suite) throws Exception {
    List<String> lines = lines(suite);
    LinkedInMainRecording recording = LinkedInMainRecording.parse(suite, lines);
    Assertions.assertTrue(
        recording.header().getOrDefault("commit", "").startsWith(LinkedInMainRecording.COMMIT),
        "the recording is not of LinkedIn main "
            + LinkedInMainRecording.COMMIT
            + ": "
            + recording.header());
    for (String name :
        new String[] {"recorded", "jdk", "iceberg", "path", "command", "runs", "test doubles"}) {
      Assertions.assertTrue(
          recording.header().containsKey(name), "the header does not say " + name);
    }
    Assertions.assertTrue(
        recording.header().get("test doubles").contains("House Tables:")
            && recording.header().get("test doubles").contains("authorization:"),
        "the header must name the House Tables stand-in and the authorization backend main ran"
            + " with: "
            + recording.header().get("test doubles"));
    Assertions.assertFalse(recording.keys().isEmpty(), "nothing recorded");
    List<String> body = new ArrayList<>();
    for (String line : lines) {
      if (!line.startsWith("#")) {
        Assertions.assertTrue(line.indexOf('\t') > 0, "not a key and an outcome: " + line);
        body.add(line);
      }
    }
    List<String> sorted = new ArrayList<>(body);
    sorted.sort(null);
    Assertions.assertEquals(sorted, body, "the lines are not sorted");
    Assertions.assertEquals(
        body.size(), new java.util.HashSet<>(body).size(), "a line is there twice");
    java.util.Set<String> withMoreThanOne = new java.util.TreeSet<>();
    for (String key : recording.keys()) {
      if (recording.outcomes(key).size() > 1) {
        withMoreThanOne.add(key);
      }
    }
    Assertions.assertEquals(
        VARYING.getOrDefault(suite, java.util.Collections.emptySet()),
        withMoreThanOne,
        "the keys with more than one outcome must be the reviewed ones main answered differently"
            + " in different runs");
  }

  /**
   * The recordings whose keys start with a test class's simple name, with the package of those
   * classes. The comparison of a class runs only when the class does, so a recorded class that is
   * gone or disabled as a whole would leave its rows unseen: it must resolve and be enabled.
   */
  private static final java.util.Map<String, String> CLASS_KEYED =
      java.util.Collections.singletonMap(
          LinkedInMainAnswers.SUITE, "com.linkedin.openhouse.tables.e2e.h2.");

  @ParameterizedTest
  @ValueSource(strings = {LinkedInMainAnswers.SUITE})
  void everyRecordedTestClassExistsAndIsEnabled(String suite) throws Exception {
    LinkedInMainRecording recording = LinkedInMainRecording.parse(suite, lines(suite));
    String pkg = CLASS_KEYED.get(suite);
    java.util.Set<String> classes = new java.util.TreeSet<>();
    for (String key : recording.keys()) {
      classes.add(key.substring(0, key.indexOf('.')));
    }
    Assertions.assertFalse(classes.isEmpty(), "no recorded class");
    for (String simpleName : classes) {
      Class<?> testClass;
      try {
        testClass = Class.forName(pkg + simpleName);
      } catch (ClassNotFoundException e) {
        throw new AssertionError(
            "recorded on LinkedIn main, but the test class is gone: "
                + pkg
                + simpleName
                + " (re-record without it)");
      }
      Assertions.assertFalse(
          testClass.isAnnotationPresent(org.junit.jupiter.api.Disabled.class),
          "recorded on LinkedIn main, but the test class is disabled: " + testClass.getName());
    }
  }

  private static List<String> lines(String suite) throws Exception {
    InputStream in =
        LinkedInMainRecordingFilesTest.class.getResourceAsStream(
            "/linkedin-main/" + suite + ".tsv");
    Assertions.assertNotNull(in, "no recording of " + suite);
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    return lines;
  }
}
