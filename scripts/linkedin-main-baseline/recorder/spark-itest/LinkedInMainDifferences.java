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
}
