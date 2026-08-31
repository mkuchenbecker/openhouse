package com.linkedin.openhouse.housetables.mock.api;

import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.model.SoftDeletedUserTableKey;
import com.linkedin.openhouse.housetables.api.spec.model.TableToggleStatusKey;
import com.linkedin.openhouse.housetables.api.spec.model.UserTable;
import com.linkedin.openhouse.housetables.api.spec.model.UserTableKey;
import java.util.Arrays;
import java.util.List;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Every place House Tables charset-validates a namespace identifier, in one test.
 *
 * <p>They are asserted together on purpose. A charset that one seam widens and another does not is
 * an identifier one accepts and the other rejects, which is a worse state than the narrow charset
 * everywhere; the only safe change is all of them at once. Listing them here also makes the set
 * countable, so a seam added later without the widening shows up as a missing row rather than as a
 * rejected write in production.
 */
public class NamespaceIdentifierCharsetTest {

  private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = FACTORY.getValidator();

  /** Depth-1 identifiers: what production actually stores today. */
  private static final List<String> ACCEPTED_BEFORE_AND_NOW =
      Arrays.asList("db", "my_database", "DB_1", "a0");

  /** The one addition: the separator {@code NamespaceUtil.encode} writes between levels. */
  private static final List<String> ACCEPTED_ONLY_NOW =
      Arrays.asList("a.b", "a.b.c", "my_db.child");

  /** Rejected before the widening, and still rejected — the widening added nothing else. */
  private static final List<String> REJECTED_BEFORE_AND_NOW =
      Arrays.asList("not-legal", "has space", "db;drop", "db/sub", "db%", "a.", ".a", "a..b", "");

  private static void assertNamespaceCharset(Class<?> beanType, String property) {
    for (String accepted : ACCEPTED_BEFORE_AND_NOW) {
      Assertions.assertTrue(
          accepted.matches(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX),
          "precondition: the narrow charset accepted " + accepted);
      Assertions.assertTrue(
          VALIDATOR.validateValue(beanType, property, accepted).isEmpty(),
          beanType.getSimpleName() + "." + property + " must still accept " + accepted);
    }
    for (String accepted : ACCEPTED_ONLY_NOW) {
      Assertions.assertTrue(
          VALIDATOR.validateValue(beanType, property, accepted).isEmpty(),
          beanType.getSimpleName() + "." + property + " must accept the separator in " + accepted);
    }
    for (String rejected : REJECTED_BEFORE_AND_NOW) {
      Assertions.assertFalse(
          VALIDATOR.validateValue(beanType, property, rejected).isEmpty(),
          beanType.getSimpleName() + "." + property + " must still reject " + rejected);
    }
  }

  @Test
  public void databaseIdIsHeldToTheNamespaceCharsetOnEveryHouseTablesModel() {
    assertNamespaceCharset(Database.class, "databaseId");
    assertNamespaceCharset(DatabaseKey.class, "databaseId");
    assertNamespaceCharset(UserTable.class, "databaseId");
    assertNamespaceCharset(UserTableKey.class, "databaseId");
    assertNamespaceCharset(SoftDeletedUserTableKey.class, "databaseId");
    assertNamespaceCharset(TableToggleStatusKey.class, "databaseId");
  }

  /**
   * The other half of the rule, and the reason the charsets are two constants rather than one
   * widened one: {@code db.table.history} must keep exactly one reading, which it only does while a
   * table id cannot contain a '.'.
   *
   * <p>Calibration: pointing any of these {@code @Pattern}s at {@code NAMESPACE_ID_REGEX} turns
   * this red while leaving every other test in this class green.
   */
  @Test
  public void tableIdIsNotWidenedWithIt() {
    for (Class<?> beanType :
        new Class<?>[] {
          UserTable.class,
          UserTableKey.class,
          SoftDeletedUserTableKey.class,
          TableToggleStatusKey.class
        }) {
      Assertions.assertFalse(
          VALIDATOR.validateValue(beanType, "tableId", "table.history").isEmpty(),
          beanType.getSimpleName() + ".tableId must still reject a '.'");
      Assertions.assertTrue(
          VALIDATOR.validateValue(beanType, "tableId", "my_table").isEmpty(),
          beanType.getSimpleName() + ".tableId must still accept a plain table id");
    }
  }
}
