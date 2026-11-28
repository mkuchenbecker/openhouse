package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.spark.smoke.LinkedInMainComparison;
import com.linkedin.openhouse.spark.smoke.LinkedInMainComparison.MainDifference;
import com.linkedin.openhouse.spark.smoke.LinkedInMainRecording;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The comparison with LinkedIn main's recording cannot be satisfied by anything but main's answer
 * or a declaration that pins this build's: no marker in the file excuses a statement.
 */
public class LinkedInMainComparisonTest {

  private static LinkedInMainRecording recording(String... rows) {
    return LinkedInMainRecording.parse(
        "suite", Arrays.asList(("# commit: bb8929e1\n" + String.join("\n", rows)).split("\n")));
  }

  private static List<String> compare(
      LinkedInMainRecording recording, List<MainDifference> declared, String key, String here) {
    LinkedInMainComparison comparison = new LinkedInMainComparison("suite", recording, declared);
    comparison.observe(key.split(" ")[0], key, here);
    return comparison.endTest();
  }

  @Test
  void aStatementWithNoRecordedRowFails() {
    List<String> problems =
        compare(recording("t1 #1 a\trows []"), Collections.emptyList(), "t1 #1 b", "rows []");
    Assertions.assertEquals(1, problems.size(), String.valueOf(problems));
    Assertions.assertTrue(
        problems.get(0).contains("no outcome of LinkedIn main is recorded"), problems.get(0));
    Assertions.assertFalse(problems.get(0).contains("NOT ON MAIN"), problems.get(0));
  }

  @Test
  void aRecordedRowWithTheOldMarkerIsRefused() {
    IllegalStateException refused =
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> recording("t1 #1 a\t" + LinkedInMainRecording.OLD_NOT_ON_MAIN_MARKER + "why"));
    Assertions.assertTrue(refused.getMessage().contains("not a marker"), refused.getMessage());
  }

  @Test
  void aStatementMainNeverReachedNeedsADeclarationThatPinsThisBuildsAnswer() {
    List<MainDifference> declared =
        Collections.singletonList(
            MainDifference.on("t1 #1 b")
                .main(LinkedInMainComparison.NOT_OBSERVED_ON_MAIN)
                .here("rows [x]")
                .because("kind", "reason"));
    LinkedInMainRecording recording = recording("t1 #1 a\trows []");
    Assertions.assertEquals(
        Collections.emptyList(), compare(recording, declared, "t1 #1 b", "rows [x]"));
    List<String> other = compare(recording, declared, "t1 #1 b", "rows [y]");
    Assertions.assertEquals(1, other.size(), String.valueOf(other));
    Assertions.assertTrue(other.get(0).contains("declared: rows [x]"), other.get(0));
  }
}
