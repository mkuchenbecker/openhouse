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
}
