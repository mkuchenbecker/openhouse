package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.spark.smoke.LinkedInMainComparison.MainDifference;
import java.util.Collections;
import java.util.List;

/** On main itself nothing differs from main: the recording run declares no difference. */
final class LinkedInMainDifferences {
  private LinkedInMainDifferences() {}

  static List<MainDifference> catalogPluginContract() {
    return Collections.emptyList();
  }

  /**
   * Tests recorded on main that this build no longer runs, each with the reason. None: a test that
   * is removed or disabled must be named here, or the comparison fails.
   */
  static java.util.Map<String, String> absentTests() {
    return java.util.Collections.emptyMap();
  }
}
