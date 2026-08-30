package com.linkedin.openhouse.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** B7: the property-bag bounds of the multi-level namespaces design, section 5.8.4. */
public class NamespacePropertiesValidatorTest {

  @Test
  public void anEmptyOrAbsentBagIsWithinBounds() {
    assertThat(NamespacePropertiesValidator.violations(null)).isEmpty();
    assertThat(NamespacePropertiesValidator.violations(new HashMap<>())).isEmpty();
  }

  @Test
  public void theEntryCountIsBounded() {
    assertThat(NamespacePropertiesValidator.violations(entries(100))).isEmpty();
    assertThat(NamespacePropertiesValidator.violations(entries(101)))
        .anySatisfy(violation -> assertThat(violation).contains("more than the maximum of 100"));
  }

  @Test
  public void theEntrySizeIsBoundedByKeyPlusValueBytes() {
    Map<String, String> atLimit = new HashMap<>();
    atLimit.put("k", repeat("v", NamespacePropertiesValidator.MAX_PROPERTY_ENTRY_BYTES - 1));
    assertThat(NamespacePropertiesValidator.violations(atLimit)).isEmpty();

    Map<String, String> overLimit = new HashMap<>();
    overLimit.put("k", repeat("v", NamespacePropertiesValidator.MAX_PROPERTY_ENTRY_BYTES));
    assertThat(NamespacePropertiesValidator.violations(overLimit))
        .anySatisfy(violation -> assertThat(violation).contains("per-entry maximum"));
  }

  /** Bytes, not characters: a multi-byte value must not slip past a character-counting bound. */
  @Test
  public void sizeIsCountedInUtf8Bytes() {
    Map<String, String> multiByte = new HashMap<>();
    multiByte.put("k", repeat("é", 600));
    assertThat(NamespacePropertiesValidator.violations(multiByte))
        .anySatisfy(violation -> assertThat(violation).contains("per-entry maximum"));
  }

  @Test
  public void theTotalSizeIsBounded() {
    Map<String, String> big = new HashMap<>();
    for (int i = 0; i < 20; i++) {
      big.put("k" + i, repeat("v", 500));
    }
    assertThat(NamespacePropertiesValidator.violations(big))
        .anySatisfy(violation -> assertThat(violation).contains("in total"));
  }

  /**
   * A null value is dropped by the JSON encoding, so accepting one would acknowledge a lost write.
   */
  @Test
  public void aNullValueIsAViolationRatherThanARemoval() {
    Map<String, String> withNull = new HashMap<>();
    withNull.put("k", null);
    assertThat(NamespacePropertiesValidator.violations(withNull))
        .anySatisfy(violation -> assertThat(violation).contains("null value"));
  }

  @Test
  public void aNullOrEmptyKeyIsAViolation() {
    Map<String, String> withNullKey = new HashMap<>();
    withNullKey.put(null, "v");
    withNullKey.put("", "v");
    assertThat(NamespacePropertiesValidator.violations(withNullKey)).hasSize(2);
  }

  private static Map<String, String> entries(int count) {
    Map<String, String> properties = new HashMap<>();
    for (int i = 0; i < count; i++) {
      properties.put("k" + i, "v");
    }
    return properties;
  }

  private static String repeat(String unit, int times) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < times; i++) {
      builder.append(unit);
    }
    return builder.toString();
  }
}
