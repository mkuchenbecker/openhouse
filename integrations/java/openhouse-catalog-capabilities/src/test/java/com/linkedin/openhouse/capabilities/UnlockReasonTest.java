package com.linkedin.openhouse.capabilities;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UnlockReasonTest {

  @Test
  void valuesAreTheServiceLockReasonsInOrder() {
    // Same values, same order as the service's lock reason, so the parser's "Expected one of"
    // message lists them as today.
    Assertions.assertEquals(
        Arrays.asList("LEGACY", "SYSTEM_ONLY"),
        Arrays.stream(UnlockReason.values())
            .map(UnlockReason::getValue)
            .collect(Collectors.toList()));
  }

  @Test
  void toStringIsTheValue() {
    for (UnlockReason reason : UnlockReason.values()) {
      Assertions.assertEquals(reason.getValue(), reason.toString());
    }
  }

  @Test
  void fromValueIsExactAndNullWhenUnknown() {
    Assertions.assertSame(UnlockReason.LEGACY, UnlockReason.fromValue("LEGACY"));
    Assertions.assertSame(UnlockReason.SYSTEM_ONLY, UnlockReason.fromValue("SYSTEM_ONLY"));
    Assertions.assertNull(UnlockReason.fromValue("system_only"));
    Assertions.assertNull(UnlockReason.fromValue("OTHER"));
    Assertions.assertNull(UnlockReason.fromValue(null));
  }
}
