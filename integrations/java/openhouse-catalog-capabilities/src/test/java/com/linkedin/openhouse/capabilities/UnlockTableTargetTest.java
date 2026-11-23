package com.linkedin.openhouse.capabilities;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UnlockTableTargetTest {

  @Test
  void keepsComponentsVerbatim() throws UnlockValidationException {
    UnlockTableTarget target = UnlockTableTarget.of(new String[] {"my db"}, "ta`ble");
    Assertions.assertEquals("my db", target.getDatabaseId());
    Assertions.assertEquals("ta`ble", target.getTableId());
    Assertions.assertEquals("my db.ta`ble", target.toString());
  }

  @Test
  void doesNotRetainTheMutableNamespace() throws UnlockValidationException {
    String[] namespace = {"db"};
    UnlockTableTarget target = UnlockTableTarget.of(namespace, "t");
    namespace[0] = "..";
    Assertions.assertEquals("db", target.getDatabaseId());
  }

  @Test
  void rejectsWhatTheJavaClientRejectsWithTheSameMessages() {
    assertRejected(null, "t", "UNLOCK requires exactly one database namespace");
    assertRejected(new String[] {}, "t", "UNLOCK requires exactly one database namespace");
    assertRejected(
        new String[] {"db", "extra"}, "t", "UNLOCK requires exactly one database namespace");
    assertRejected(new String[] {null}, "t", "Database name must not be null or empty");
    assertRejected(new String[] {""}, "t", "Database name must not be null or empty");
    assertRejected(new String[] {"."}, "t", "Database name must not be '.'");
    assertRejected(new String[] {".."}, "t", "Database name must not be '..'");
    assertRejected(new String[] {"db"}, null, "Table name must not be null or empty");
    assertRejected(new String[] {"db"}, "", "Table name must not be null or empty");
    assertRejected(new String[] {"db"}, ".", "Table name must not be '.'");
    assertRejected(new String[] {"db"}, "..", "Table name must not be '..'");
  }

  @Test
  void equalTargetsAreEqual() throws UnlockValidationException {
    Assertions.assertEquals(
        UnlockTableTarget.of(new String[] {"db"}, "t"),
        UnlockTableTarget.of(new String[] {"db"}, "t"));
    Assertions.assertEquals(
        UnlockTableTarget.of(new String[] {"db"}, "t").hashCode(),
        UnlockTableTarget.of(new String[] {"db"}, "t").hashCode());
    Assertions.assertNotEquals(
        UnlockTableTarget.of(new String[] {"db"}, "t"),
        UnlockTableTarget.of(new String[] {"db"}, "u"));
  }

  private static void assertRejected(String[] namespace, String table, String message) {
    UnlockValidationException e =
        Assertions.assertThrows(
            UnlockValidationException.class, () -> UnlockTableTarget.of(namespace, table));
    Assertions.assertEquals(message, e.getMessage());
  }
}
