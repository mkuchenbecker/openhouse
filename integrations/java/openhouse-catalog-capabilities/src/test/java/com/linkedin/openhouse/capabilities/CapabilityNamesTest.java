package com.linkedin.openhouse.capabilities;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CapabilityNamesTest {

  @Test
  void namesAreTheWireSpellings() {
    // The server advertises these in its config response and the gate checks the client one by
    // exactly this spelling, so a renamed constant is a wire change.
    Assertions.assertEquals("policies", CapabilityNames.POLICIES);
    Assertions.assertEquals("grants", CapabilityNames.GRANTS);
    Assertions.assertEquals("unlock", CapabilityNames.UNLOCK);
    Assertions.assertEquals("column-defaults", CapabilityNames.COLUMN_DEFAULTS);
    Assertions.assertEquals("openhouse.capabilities", CapabilityNames.ADVERTISED_PROPERTY);
    Assertions.assertEquals(
        "X-OpenHouse-Client-Capabilities", CapabilityNames.CLIENT_CAPABILITIES_HEADER);
  }

  @Test
  void serverAndClientNamesAreDisjoint() {
    Assertions.assertEquals(
        new LinkedHashSet<>(Arrays.asList("policies", "grants", "unlock")),
        CapabilityNames.SERVER_CAPABILITIES);
    Assertions.assertEquals(
        Collections.singleton("column-defaults"), CapabilityNames.CLIENT_CAPABILITIES);
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> CapabilityNames.SERVER_CAPABILITIES.add("x"));
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> CapabilityNames.CLIENT_CAPABILITIES.add("x"));
  }

  @Test
  void parseReadsACommaSeparatedList() {
    Assertions.assertEquals(
        new LinkedHashSet<>(Arrays.asList("policies", "grants", "unlock")),
        CapabilityNames.parse("policies,grants,unlock"));
  }

  @Test
  void parseTrimsLowerCasesAndDropsEmptyEntries() {
    Set<String> parsed = CapabilityNames.parse(" Policies , ,GRANTS,,");
    Assertions.assertEquals(new LinkedHashSet<>(Arrays.asList("policies", "grants")), parsed);
    Assertions.assertThrows(UnsupportedOperationException.class, () -> parsed.add("unlock"));
  }

  @Test
  void parseKeepsUnknownNames() {
    // A newer server may advertise a name this client does not know; the caller decides.
    Assertions.assertEquals(
        new LinkedHashSet<>(Arrays.asList("policies", "metrics")),
        CapabilityNames.parse("policies,metrics"));
  }

  @Test
  void parseOfNothingIsEmpty() {
    Assertions.assertTrue(CapabilityNames.parse(null).isEmpty());
    Assertions.assertTrue(CapabilityNames.parse("").isEmpty());
    Assertions.assertTrue(CapabilityNames.parse(" , ").isEmpty());
  }

  @Test
  void parseIgnoresTheDefaultLocale() {
    java.util.Locale saved = java.util.Locale.getDefault();
    java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
    try {
      Assertions.assertEquals(Collections.singleton("policies"), CapabilityNames.parse("POLICIES"));
    } finally {
      java.util.Locale.setDefault(saved);
    }
  }
}
