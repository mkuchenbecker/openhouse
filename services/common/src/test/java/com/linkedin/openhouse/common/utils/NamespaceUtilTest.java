package com.linkedin.openhouse.common.utils;

import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NamespaceUtilTest {

  /**
   * The depth-1 half of the compatibility claim: with the property at its shipped default, every
   * answer is the one the hardcoded constant used to give.
   *
   * <p>Calibration: changing {@code ==} to {@code >=} in {@code isTableNamespace} leaves the first
   * three assertions passing and turns the last two red.
   */
  @Test
  public void testIsTableNamespaceAtTheDefaultDepthMatchesTheHardcodedRule() {
    int depth = NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH;
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(null, depth));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.empty(), depth));
    Assertions.assertTrue(NamespaceUtil.isTableNamespace(Namespace.of("db"), depth));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.of("a", "b"), depth));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.of("a", "b", "c"), depth));
  }

  /**
   * Raising the property moves the predicate, which is the point of wiring it. A table namespace is
   * exactly the configured depth, so raising the bound moves the level tables live at rather than
   * widening it to a range.
   */
  @Test
  public void testIsTableNamespaceFollowsTheConfiguredDepth() {
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.of("db"), 2));
    Assertions.assertTrue(NamespaceUtil.isTableNamespace(Namespace.of("a", "b"), 2));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.of("a", "b", "c"), 2));
    Assertions.assertTrue(NamespaceUtil.isTableNamespace(Namespace.of("a", "b", "c"), 3));
  }

  @Test
  public void testValidateOperationNamespaceAllowsEmpty() {
    Assertions.assertDoesNotThrow(
        () ->
            NamespaceUtil.validateOperationNamespace(
                Namespace.empty(), NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH));
  }

  @Test
  public void testValidateOperationNamespaceAllowsSingleLevel() {
    Assertions.assertDoesNotThrow(
        () ->
            NamespaceUtil.validateOperationNamespace(
                Namespace.of("db"), NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH));
  }

  /**
   * The rejection path at the shipped depth, message included: wiring the bound to configuration is
   * supposed to be invisible at depth 1, and a caller reading the message would see it otherwise.
   */
  @Test
  public void testValidateOperationNamespaceRejectsMultiLevel() {
    int depth = NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH;
    ValidationException twoLevel =
        Assertions.assertThrows(
            ValidationException.class,
            () -> NamespaceUtil.validateOperationNamespace(Namespace.of("a", "b"), depth));
    Assertions.assertEquals("Input namespace has more than one levels a.b", twoLevel.getMessage());

    ValidationException threeLevel =
        Assertions.assertThrows(
            ValidationException.class,
            () -> NamespaceUtil.validateOperationNamespace(Namespace.of("a", "b", "c"), depth));
    Assertions.assertEquals(
        "Input namespace has more than one levels a.b.c", threeLevel.getMessage());
  }

  @Test
  public void testValidateOperationNamespaceFollowsTheConfiguredDepth() {
    Assertions.assertDoesNotThrow(
        () -> NamespaceUtil.validateOperationNamespace(Namespace.of("a", "b"), 2));
    Assertions.assertDoesNotThrow(
        () -> NamespaceUtil.validateOperationNamespace(Namespace.of("db"), 2));
    ValidationException tooDeep =
        Assertions.assertThrows(
            ValidationException.class,
            () -> NamespaceUtil.validateOperationNamespace(Namespace.of("a", "b", "c"), 2));
    Assertions.assertEquals("Input namespace has more than 2 levels a.b.c", tooDeep.getMessage());
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

  /**
   * The one-way-door assertion for the charset widening: a depth-1 encoded namespace is exactly the
   * database name, so the widened charset sees the same strings it has always seen.
   */
  @Test
  public void testNamespaceIdentifierCharsetAcceptsEveryDepthOneDatabaseName() {
    for (String databaseId : new String[] {"db", "my_database", "DB_1", "a", "0", "_"}) {
      Assertions.assertEquals(
          databaseId, NamespaceUtil.encode(Namespace.of(databaseId)), databaseId);
      Assertions.assertTrue(
          NamespaceUtil.isValidNamespaceIdentifier(databaseId),
          "widened charset must still accept " + databaseId);
      Assertions.assertTrue(
          databaseId.matches(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX),
          "precondition: this is what the old charset accepted");
    }
  }

  /**
   * The '.' is the only thing the widening adds. Everything the narrow charset rejected is still
   * rejected, so the widening cannot have let anything else through.
   *
   * <p>Calibration: replacing {@code NAMESPACE_ID_REGEX} with {@code ".*"} turns every assertion in
   * this method red; dropping the {@code (\.[a-zA-Z0-9_]+)*} group turns only the previous method's
   * multi-level cases red.
   */
  @Test
  public void testNamespaceIdentifierCharsetAddsOnlyTheSeparator() {
    String[] rejectedBefore = {
      "has space", "not-legal", "", "db;drop", "db/sub", "a\tb", "db%", "d\u00e9b", "db\n", "*"
    };
    for (String candidate : rejectedBefore) {
      Assertions.assertFalse(
          candidate.matches(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX),
          "precondition: the old charset rejected " + candidate);
      Assertions.assertFalse(
          NamespaceUtil.isValidNamespaceIdentifier(candidate),
          "the widened charset must still reject " + candidate);
    }
    Assertions.assertTrue(NamespaceUtil.isValidNamespaceIdentifier("a.b"));
    Assertions.assertTrue(NamespaceUtil.isValidNamespaceIdentifier("a.b.c"));
    Assertions.assertFalse(NamespaceUtil.isValidNamespaceIdentifier(null));
  }

  /** A separator is a separator, not a character a level may carry. */
  @Test
  public void testNamespaceIdentifierCharsetRejectsDegenerateSeparatorPlacement() {
    for (String candidate : new String[] {".", ".a", "a.", "a..b", "..", "a.b."}) {
      Assertions.assertFalse(
          NamespaceUtil.isValidNamespaceIdentifier(candidate),
          "must reject degenerate separator placement: " + candidate);
    }
  }

  @Test
  public void testValidateNamespaceIdentifierThrowsForTheSameInputsThePredicateRejects() {
    Assertions.assertDoesNotThrow(() -> NamespaceUtil.validateNamespaceIdentifier("db"));
    Assertions.assertDoesNotThrow(() -> NamespaceUtil.validateNamespaceIdentifier("a.b"));
    Assertions.assertThrows(
        ValidationException.class, () -> NamespaceUtil.validateNamespaceIdentifier("not-legal"));
    Assertions.assertThrows(
        ValidationException.class, () -> NamespaceUtil.validateNamespaceIdentifier(null));
  }

  /**
   * The table-id charset must NOT have moved with it: the metadata-table discriminator a later
   * slice depends on reads {@code db.table.history} as one table and one metadata suffix, which
   * only holds while a table id cannot contain a '.'.
   */
  @Test
  public void testTableIdCharsetStillRejectsTheSeparator() {
    Assertions.assertFalse("table.history".matches(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX));
    Assertions.assertFalse("a.b".matches(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX));
    Assertions.assertTrue("my_table".matches(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX));
  }

  /**
   * The subtree range is a range over the encoded id, and it has to be exactly the subtree: the
   * parent below it, the descendants inside it, and the parent's siblings above it.
   *
   * <p>Calibration: changing the upper bound's successor character from {@code /} to {@code .}
   * empties the range and turns the descendant assertions red.
   */
  @Test
  public void testSubtreeRangeBracketsExactlyTheDescendants() {
    String lower = NamespaceUtil.subtreeLowerBound("a");
    String upper = NamespaceUtil.subtreeUpperBound("a");
    Assertions.assertEquals("a.", lower);
    Assertions.assertEquals("a/", upper);
    for (String inside : new String[] {"a.b", "a.b.c", "a._", "a.z9"}) {
      Assertions.assertTrue(
          inside.compareTo(lower) >= 0 && inside.compareTo(upper) < 0, inside + " is a descendant");
    }
    for (String outside : new String[] {"a", "ab", "ab.c", "b", "a0", "aa"}) {
      Assertions.assertFalse(
          outside.compareTo(lower) >= 0 && outside.compareTo(upper) < 0,
          outside + " is not a descendant of a");
    }
  }

  /** Direct children only: the parent is not its own child and a grandchild is not a child. */
  @Test
  public void testIsDirectChildExcludesTheParentAndTheGrandchildren() {
    Assertions.assertTrue(NamespaceUtil.isDirectChild("a", "a.b"));
    Assertions.assertTrue(NamespaceUtil.isDirectChild("a.b", "a.b.c"));
    Assertions.assertFalse(NamespaceUtil.isDirectChild("a", "a"));
    Assertions.assertFalse(NamespaceUtil.isDirectChild("a", "a.b.c"));
    Assertions.assertFalse(NamespaceUtil.isDirectChild("a", "ab"));
    Assertions.assertFalse(NamespaceUtil.isDirectChild("a", "b.c"));
    Assertions.assertFalse(NamespaceUtil.isDirectChild("a", "a."));
    Assertions.assertFalse(NamespaceUtil.isDirectChild("a", null));
  }

  /**
   * The underscore is why the store's subtree query is a range and not a {@code LIKE} prefix: SQL
   * reads {@code _} as a single-character wildcard, so {@code LIKE 'my_db.%'} would also match
   * {@code myXdb.a}. The range does not.
   */
  @Test
  public void testSubtreeRangeIsNotFooledByAnUnderscoreInTheParentName() {
    String lower = NamespaceUtil.subtreeLowerBound("my_db");
    String upper = NamespaceUtil.subtreeUpperBound("my_db");
    Assertions.assertTrue(
        "my_db.child".compareTo(lower) >= 0 && "my_db.child".compareTo(upper) < 0);
    Assertions.assertFalse(
        "myXdb.child".compareTo(lower) >= 0 && "myXdb.child".compareTo(upper) < 0);
    Assertions.assertFalse(NamespaceUtil.isDirectChild("my_db", "myXdb.child"));
  }
}
