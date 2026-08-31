package com.linkedin.openhouse.tables.mock.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.validator.DatabasesApiValidator;
import com.linkedin.openhouse.tables.api.validator.TablesApiValidator;
import java.util.Arrays;
import java.util.List;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every place the Tables Service charset-validates a namespace identifier, in one test, for the
 * same reason the House Tables counterpart exists: the two services must widen together or an
 * encoded namespace becomes an identifier one accepts and the other rejects.
 */
@SpringBootTest
public class NamespaceIdentifierCharsetTest {

  private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
  private static final Validator BEAN_VALIDATOR = FACTORY.getValidator();

  private static final List<String> ACCEPTED_BEFORE_AND_NOW =
      Arrays.asList("db", "my_database", "DB_1", "a0");
  private static final List<String> ACCEPTED_ONLY_NOW =
      Arrays.asList("a.b", "a.b.c", "my_db.child");
  private static final List<String> REJECTED_BEFORE_AND_NOW =
      Arrays.asList("not-legal", "has space", "db;drop", "db/sub", "db%", "a.", ".a", "a..b", "");

  @Autowired private TablesApiValidator tablesApiValidator;

  @Autowired private DatabasesApiValidator databasesApiValidator;

  /** Enforcement point: the {@code databaseId} constraint on the create/update request body. */
  @Test
  public void theRequestBodyDatabaseIdIsHeldToTheNamespaceCharset() {
    for (String accepted : ACCEPTED_BEFORE_AND_NOW) {
      assertTrue(
          accepted.matches(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX),
          "precondition: the narrow charset accepted " + accepted);
      assertTrue(
          BEAN_VALIDATOR
              .validateValue(CreateUpdateTableRequestBody.class, "databaseId", accepted)
              .isEmpty());
    }
    for (String accepted : ACCEPTED_ONLY_NOW) {
      assertTrue(
          BEAN_VALIDATOR
              .validateValue(CreateUpdateTableRequestBody.class, "databaseId", accepted)
              .isEmpty(),
          "must accept the separator in " + accepted);
    }
    for (String rejected : REJECTED_BEFORE_AND_NOW) {
      assertFalse(
          BEAN_VALIDATOR
              .validateValue(CreateUpdateTableRequestBody.class, "databaseId", rejected)
              .isEmpty(),
          "must still reject " + rejected);
    }
  }

  /**
   * The table id on the same body did not move with it: {@code db.table.history} must keep exactly
   * one reading.
   */
  @Test
  public void theRequestBodyTableIdIsNotWidenedWithIt() {
    assertFalse(
        BEAN_VALIDATOR
            .validateValue(CreateUpdateTableRequestBody.class, "tableId", "table.history")
            .isEmpty());
    assertTrue(
        BEAN_VALIDATOR
            .validateValue(CreateUpdateTableRequestBody.class, "tableId", "my_table")
            .isEmpty());
  }

  /** Enforcement point: {@code OpenHouseTablesApiValidator.validateDatabaseId}. */
  @Test
  public void theTablesApiValidatorAcceptsAnEncodedNamespaceAndStillRejectsTheRest() {
    for (String accepted : ACCEPTED_BEFORE_AND_NOW) {
      assertDoesNotThrow(() -> tablesApiValidator.validateGetTable(accepted, "t"));
    }
    for (String accepted : ACCEPTED_ONLY_NOW) {
      assertDoesNotThrow(() -> tablesApiValidator.validateGetTable(accepted, "t"));
    }
    for (String rejected : REJECTED_BEFORE_AND_NOW) {
      assertThrows(
          RequestValidationFailureException.class,
          () -> tablesApiValidator.validateGetTable(rejected, "t"),
          "databaseId " + rejected + " must still be rejected");
    }
    assertThrows(
        RequestValidationFailureException.class,
        () -> tablesApiValidator.validateGetTable("a.b", "t.history"),
        "tableId is held to the narrower charset");
  }

  /** Enforcement point: {@code OpenHouseDatabasesApiValidator.validateDatabaseId}. */
  @Test
  public void theDatabasesApiValidatorAcceptsAnEncodedNamespaceAndStillRejectsTheRest() {
    for (String accepted : ACCEPTED_BEFORE_AND_NOW) {
      assertDoesNotThrow(() -> databasesApiValidator.validateGetAclPolicies(accepted));
    }
    for (String accepted : ACCEPTED_ONLY_NOW) {
      assertDoesNotThrow(() -> databasesApiValidator.validateGetAclPolicies(accepted));
    }
    for (String rejected : REJECTED_BEFORE_AND_NOW) {
      assertThrows(
          RequestValidationFailureException.class,
          () -> databasesApiValidator.validateGetAclPolicies(rejected),
          "databaseId " + rejected + " must still be rejected");
    }
  }
}
