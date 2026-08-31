package com.linkedin.openhouse.common.utils;

import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import java.util.Arrays;
import java.util.Locale;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NamespaceUtilTest {

  /**
   * The depth-1 half of the compatibility claim: with the property at its shipped default, every
   * answer is the one the hardcoded constant used to give.
   *
   * <p>Calibration: dropping the {@code depth <= maxDepth} clause from {@code isTableNamespace}
   * leaves the first three assertions passing and turns the last two red; dropping the {@code depth
   * >= 1} clause turns the second red.
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
   * Raising the property widens the predicate to a range rather than moving a single admitted
   * depth. {@code max-depth} is a ceiling: a table may live in any namespace from one level up to
   * it. This is the assertion the previous "exactly {@code maxDepth}" rule got backwards — under it
   * the first assertion here was false, which means raising a live cluster's max-depth to 2 would
   * have stopped every existing one-level database from hosting a table.
   *
   * <p>Calibration: restoring {@code levels().length == maxDepth} turns the shallower-than-max
   * assertions red and leaves the at-max and beyond-max ones passing.
   */
  @Test
  public void testIsTableNamespaceAdmitsEveryDepthUpToTheConfiguredMaximum() {
    // Shallower than the maximum: still a namespace, still hosts tables.
    Assertions.assertTrue(NamespaceUtil.isTableNamespace(Namespace.of("db"), 2));
    // At the maximum.
    Assertions.assertTrue(NamespaceUtil.isTableNamespace(Namespace.of("a", "b"), 2));
    // Beyond the maximum.
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.of("a", "b", "c"), 2));

    // And the same shape one level up, where there are two admitted depths below the maximum.
    Assertions.assertTrue(NamespaceUtil.isTableNamespace(Namespace.of("db"), 3));
    Assertions.assertTrue(NamespaceUtil.isTableNamespace(Namespace.of("a", "b"), 3));
    Assertions.assertTrue(NamespaceUtil.isTableNamespace(Namespace.of("a", "b", "c"), 3));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.of("a", "b", "c", "d"), 3));

    // The floor does not move with the ceiling: the empty namespace has no database to host a
    // table in, at any configured depth.
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.empty(), 2));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(Namespace.empty(), 3));
    Assertions.assertFalse(NamespaceUtil.isTableNamespace(null, 3));
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

  /**
   * The per-level charset inside a namespace and the charset the wire seams enforce are one
   * constant, not two that happen to agree today.
   */
  @Test
  public void testTheLevelCharsetIsTheSameOneTheWireSeamsEnforce() {
    Assertions.assertTrue(NamespaceUtil.isValidNamespaceIdentifier("legal_1"));
    Assertions.assertThrows(
        ValidationException.class, () -> NamespaceUtil.validate(Namespace.of("not-legal"), 1));
    Assertions.assertFalse(NamespaceUtil.isValidNamespaceIdentifier("not-legal"));
    Assertions.assertEquals(
        "^[a-zA-Z0-9_]+$", ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX, "charset is unchanged");
    Assertions.assertEquals(
        "^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)*$", ValidatorConstants.NAMESPACE_ID_REGEX);
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

  /**
   * The discriminator's whole job: {@code a.b.history} is a metadata table, {@code db.history} is a
   * table someone created and called {@code history}. The depth floor is the only thing separating
   * them, so it is asserted from both sides.
   *
   * <p>Calibration: dropping the depth floor turns the second block red; keeping the floor but
   * matching the namespace's last level instead of the identifier's name turns the first block red.
   */
  @Test
  public void testIsMetadataTableIdentifierNeedsBothDepthAndAMetadataName() {
    Assertions.assertTrue(
        NamespaceUtil.isMetadataTableIdentifier(
            TableIdentifier.of(Namespace.of("a", "b"), "history")));
    Assertions.assertTrue(
        NamespaceUtil.isMetadataTableIdentifier(
            TableIdentifier.of(Namespace.of("a", "b"), "snapshots")));
    Assertions.assertFalse(
        NamespaceUtil.isMetadataTableIdentifier(
            TableIdentifier.of(Namespace.of("a", "b"), "sales")));

    // Depth 1 is the shipped configuration, and there a metadata-table name is just a name.
    Assertions.assertFalse(
        NamespaceUtil.isMetadataTableIdentifier(TableIdentifier.of(Namespace.of("db"), "history")));
    Assertions.assertFalse(
        NamespaceUtil.isMetadataTableIdentifier(
            TableIdentifier.of(Namespace.of("db"), "snapshots")));
    Assertions.assertFalse(NamespaceUtil.isMetadataTableIdentifier(null));
  }

  /**
   * The set of metadata table names is Iceberg's, not a list copied into OpenHouse. Asserting over
   * {@code MetadataTableType.values()} is what makes a type added upstream a covered case rather
   * than a silently creatable table name.
   */
  @Test
  public void testEveryIcebergMetadataTableTypeIsRecognised() {
    Assertions.assertTrue(MetadataTableType.values().length > 0);
    Arrays.stream(MetadataTableType.values())
        .forEach(
            type -> {
              String lower = type.name().toLowerCase(Locale.ROOT);
              Assertions.assertTrue(
                  NamespaceUtil.isMetadataTableName(lower), "not recognised: " + lower);
              Assertions.assertTrue(NamespaceUtil.isMetadataTableName(type.name()));
              Assertions.assertTrue(
                  NamespaceUtil.isMetadataTableIdentifier(
                      TableIdentifier.of(Namespace.of("a", "b"), lower)));
            });
    Assertions.assertFalse(NamespaceUtil.isMetadataTableName("histories"));
    Assertions.assertFalse(NamespaceUtil.isMetadataTableName(null));
  }

  /**
   * The create-time admission rule reads the encoded namespace the write actually carries, so the
   * question it asks is the same one the catalog will ask of the resulting identifier.
   */
  @Test
  public void testCollidesWithMetadataTableFollowsTheEncodedNamespaceDepth() {
    Assertions.assertTrue(NamespaceUtil.collidesWithMetadataTable("a.b", "history"));
    Assertions.assertFalse(NamespaceUtil.collidesWithMetadataTable("db", "history"));
    Assertions.assertFalse(NamespaceUtil.collidesWithMetadataTable("a.b", "histories"));
    Assertions.assertFalse(NamespaceUtil.collidesWithMetadataTable(null, "history"));
    Assertions.assertFalse(NamespaceUtil.collidesWithMetadataTable("a.b", null));
    Assertions.assertFalse(NamespaceUtil.collidesWithMetadataTable("", "history"));
  }

  /**
   * A table identifier built from an encoded namespace has to get its levels back, and at depth 1
   * has to be the identifier the two-string overload produces -- byte for byte, level for level.
   *
   * <p>Calibration: using {@code TableIdentifier.of(encoded, tableId)} instead leaves the depth-1
   * assertion green and turns the nested one red, which is exactly the bug the helper exists for.
   */
  @Test
  public void testTableIdentifierDecodesTheNamespaceAndIsIdenticalAtDepthOne() {
    Assertions.assertEquals(
        TableIdentifier.of("db", "t"), NamespaceUtil.tableIdentifier("db", "t"));
    Assertions.assertEquals(
        TableIdentifier.of(Namespace.of("a", "b"), "t"), NamespaceUtil.tableIdentifier("a.b", "t"));
    Assertions.assertEquals(
        2, NamespaceUtil.tableIdentifier("a.b", "t").namespace().levels().length);
  }
}
