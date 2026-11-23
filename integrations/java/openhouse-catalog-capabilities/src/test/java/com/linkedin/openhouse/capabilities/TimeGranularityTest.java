package com.linkedin.openhouse.capabilities;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TimeGranularityTest {

  @Test
  void valuesAreTheServiceGranularitiesInOrder() {
    // The spelling the policy patches carry ("granularity":"DAY"), which is the service's
    // TimePartitionSpec granularity and the generated client's GranularityEnum value.
    Assertions.assertEquals(
        Arrays.asList("HOUR", "DAY", "MONTH", "YEAR"),
        Arrays.stream(TimeGranularity.values())
            .map(TimeGranularity::getValue)
            .collect(Collectors.toList()));
  }

  @Test
  void toStringIsTheValue() {
    for (TimeGranularity granularity : TimeGranularity.values()) {
      Assertions.assertEquals(granularity.getValue(), granularity.toString());
    }
  }

  @Test
  void fromValueIsExactAndNullWhenUnknown() {
    for (TimeGranularity granularity : TimeGranularity.values()) {
      Assertions.assertSame(granularity, TimeGranularity.fromValue(granularity.getValue()));
    }
    Assertions.assertNull(TimeGranularity.fromValue("day"));
    Assertions.assertNull(TimeGranularity.fromValue("D"));
    Assertions.assertNull(TimeGranularity.fromValue(null));
  }
}
