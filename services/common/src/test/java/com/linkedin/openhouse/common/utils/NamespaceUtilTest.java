package com.linkedin.openhouse.common.utils;

import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NamespaceUtilTest {

  @Test
  public void testIsTableNamespace() {
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(null));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.empty()));
    Assertions.assertTrue(NamespaceUtil.isTableNamespace(Namespace.of("db")));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.of("a", "b")));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.of("a", "b", "c")));
  }

  @Test
  public void testValidateOperationNamespaceAllowsEmpty() {
    Assertions.assertDoesNotThrow(
        () -> NamespaceUtil.validateOperationNamespace(Namespace.empty()));
  }

  @Test
  public void testValidateOperationNamespaceAllowsSingleLevel() {
    Assertions.assertDoesNotThrow(
        () -> NamespaceUtil.validateOperationNamespace(Namespace.of("db")));
  }

  @Test
  public void testValidateOperationNamespaceRejectsMultiLevel() {
    ValidationException twoLevel =
        Assertions.assertThrows(
            ValidationException.class,
            () -> NamespaceUtil.validateOperationNamespace(Namespace.of("a", "b")));
    Assertions.assertEquals("Input namespace has more than one levels a.b", twoLevel.getMessage());

    ValidationException threeLevel =
        Assertions.assertThrows(
            ValidationException.class,
            () -> NamespaceUtil.validateOperationNamespace(Namespace.of("a", "b", "c")));
    Assertions.assertEquals(
        "Input namespace has more than one levels a.b.c", threeLevel.getMessage());
  }

  @Test
  public void testEncodeIsIdentityForAnExistingDatabase() {
    // The whole compatibility argument in one assertion: nothing about an existing database moves.
    Assertions.assertEquals("db", NamespaceUtil.encode(Namespace.of("db")));
    Assertions.assertEquals(
        Namespace.of("db").toString(), NamespaceUtil.encode(Namespace.of("db")));
  }

  @Test
  public void testEncodeDotJoinsLevels() {
    Assertions.assertEquals("a.b", NamespaceUtil.encode(Namespace.of("a", "b")));
    Assertions.assertEquals("a.b.c", NamespaceUtil.encode(Namespace.of("a", "b", "c")));
  }

  @Test
  public void testDecodeRoundTripsEncode() {
    for (Namespace namespace :
        new Namespace[] {Namespace.of("db"), Namespace.of("a", "b"), Namespace.of("a", "b", "c")}) {
      Assertions.assertEquals(namespace, NamespaceUtil.decode(NamespaceUtil.encode(namespace)));
    }
  }

  @Test
  public void testValidateAcceptsASingleLevelNamespaceAtTheDefaultDepth() {
    Assertions.assertDoesNotThrow(
        () ->
            NamespaceUtil.validate(
                Namespace.of("db_1"), NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH));
  }

  @Test
  public void testValidateRejectsNamespacesDeeperThanTheCap() {
    Assertions.assertThrows(
        ValidationException.class,
        () ->
            NamespaceUtil.validate(
                Namespace.of("a", "b"), NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH));
    Assertions.assertDoesNotThrow(() -> NamespaceUtil.validate(Namespace.of("a", "b"), 2));
  }

  @Test
  public void testValidateRejectsTheEmptyNamespace() {
    Assertions.assertThrows(
        ValidationException.class, () -> NamespaceUtil.validate(Namespace.empty(), 1));
    Assertions.assertThrows(ValidationException.class, () -> NamespaceUtil.validate(null, 1));
  }

  @Test
  public void testValidateRejectsLevelsOutsideTheIdentifierCharset() {
    Assertions.assertThrows(
        ValidationException.class, () -> NamespaceUtil.validate(Namespace.of("not-legal"), 1));
    Assertions.assertThrows(
        ValidationException.class, () -> NamespaceUtil.validate(Namespace.of("has space"), 1));
    Assertions.assertThrows(
        ValidationException.class, () -> NamespaceUtil.validate(Namespace.of(""), 1));
  }

  @Test
  public void testValidateRejectsAnEncodedNamespaceWiderThanTheColumn() {
    StringBuilder tooLong = new StringBuilder();
    for (int i = 0; i <= NamespaceUtil.MAX_ENCODED_NAMESPACE_LENGTH; i++) {
      tooLong.append("a");
    }
    Assertions.assertThrows(
        ValidationException.class,
        () -> NamespaceUtil.validate(Namespace.of(tooLong.toString()), 1));
  }
}
