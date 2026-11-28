package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.spark.smoke.LinkedInMainRecording;
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

  @ParameterizedTest
  @ValueSource(strings = {"smoke-scenarios", "write-statements", "catalog-plugin-contract"})
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
        new String[] {
          "recorded", "jdk", "spark", "iceberg", "path", "command", "runs", "test doubles"
        }) {
      Assertions.assertTrue(
          recording.header().containsKey(name), "the header does not say " + name);
    }
    Assertions.assertTrue(
        recording.header().get("test doubles").contains("House Tables:")
            && recording.header().get("test doubles").contains("authorization:"),
        "the header must name the House Tables stand-in and the authorization backend main ran"
            + " with: "
            + recording.header().get("test doubles"));
    // The Spark itest's service takes its House Tables stand-in from tables-test-fixtures, which
    // the recording cannot replace on main: the header says whether the branch's stand-in changes
    // any answer.
    Assertions.assertTrue(
        recording.header().getOrDefault("stand-in check", "").startsWith("identical"),
        "the header must say how the answers compare with the branch's fixtures stand-in: "
            + recording.header().get("stand-in check"));
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
