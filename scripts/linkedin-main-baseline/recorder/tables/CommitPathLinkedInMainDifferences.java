package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.tables.e2e.h2.LinkedInMainComparison.MainDifference;
import java.util.Collections;
import java.util.List;

/** On main itself nothing differs from main: the recording run declares no difference. */
final class CommitPathLinkedInMainDifferences {
  private CommitPathLinkedInMainDifferences() {}

  static List<MainDifference> differences() {
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
