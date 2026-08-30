package com.linkedin.openhouse.common.utils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The size contract for a namespace property bag, per the multi-level namespaces design §5.8.4.
 *
 * <p>It is deliberately a pure function returning violation messages rather than a thrower: the
 * bounds have to hold at two independent boundaries that owe their callers different exception
 * types — the Tables Service ingress ({@code ValidationException} → 400) and the House Tables
 * boundary ({@code RequestValidationFailureException} → 400) — and House Tables is reachable
 * without the Tables Service in front of it, so neither may trust the other to have checked.
 *
 * <p>A {@code null} value is a violation rather than a deletion: the JSON encoding used by the
 * storage column drops null-valued entries, so accepting one would acknowledge a write ({@code
 * updated: ["k"]}) that never lands.
 */
public final class NamespacePropertiesValidator {
  private NamespacePropertiesValidator() {}

  /** Maximum number of entries in a namespace property bag. */
  public static final int MAX_PROPERTY_ENTRIES = 100;

  /** Maximum size in bytes of one entry, counted as UTF-8 key bytes plus UTF-8 value bytes. */
  public static final int MAX_PROPERTY_ENTRY_BYTES = 1024;

  /** Maximum total size in bytes of every entry summed. */
  public static final int MAX_PROPERTIES_BYTES = 8192;

  /** @return every way {@code properties} breaks the contract, empty when it is within bounds. */
  public static List<String> violations(Map<String, String> properties) {
    if (properties == null || properties.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> violations = new ArrayList<>();
    if (properties.size() > MAX_PROPERTY_ENTRIES) {
      violations.add(
          String.format(
              "Namespace properties hold %s entries, more than the maximum of %s",
              properties.size(), MAX_PROPERTY_ENTRIES));
    }
    long total = 0;
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || key.isEmpty()) {
        violations.add("Namespace property keys cannot be null or empty");
        continue;
      }
      if (value == null) {
        violations.add(
            String.format(
                "Namespace property %s has a null value; remove the key instead of setting it to"
                    + " null",
                key));
        continue;
      }
      int size = sizeInBytes(key) + sizeInBytes(value);
      total += size;
      if (size > MAX_PROPERTY_ENTRY_BYTES) {
        violations.add(
            String.format(
                "Namespace property %s is %s bytes, more than the per-entry maximum of %s",
                key, size, MAX_PROPERTY_ENTRY_BYTES));
      }
    }
    if (total > MAX_PROPERTIES_BYTES) {
      violations.add(
          String.format(
              "Namespace properties are %s bytes in total, more than the maximum of %s",
              total, MAX_PROPERTIES_BYTES));
    }
    return violations;
  }

  private static int sizeInBytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
