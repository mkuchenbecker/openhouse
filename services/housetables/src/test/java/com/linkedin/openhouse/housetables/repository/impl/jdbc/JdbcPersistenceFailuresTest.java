package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.common.exception.CorruptEntityTypeException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.function.Supplier;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.orm.jpa.JpaSystemException;

public class JdbcPersistenceFailuresTest {

  @Test
  public void duplicateKeyExceptionIsDuplicate() {
    Assertions.assertTrue(JdbcPersistenceFailures.isDuplicateKey(new DuplicateKeyException("dup")));
  }

  /** The JPA dialect reports duplicates generically; the SQLSTATE underneath still says 23505. */
  @Test
  public void h2StyleUniqueViolationUnderGenericWrapperIsDuplicate() {
    SQLException unique = new SQLException("Unique index or primary key violation", "23505");
    DataIntegrityViolationException wrapped =
        new DataIntegrityViolationException(
            "could not execute statement", new RuntimeException("hibernate wrapper", unique));
    Assertions.assertTrue(JdbcPersistenceFailures.isDuplicateKey(wrapped));
  }

  @Test
  public void mysqlDuplicateEntryVendorCodeIsDuplicate() {
    SQLException duplicateEntry =
        new SQLIntegrityConstraintViolationException(
            "Duplicate entry 'db1-t1' for key 'PRIMARY'", "23000", 1062);
    Assertions.assertTrue(
        JdbcPersistenceFailures.isDuplicateKey(
            new DataIntegrityViolationException("could not execute statement", duplicateEntry)));
  }

  /** MySQL's generic 23000 covers every integrity violation; only ER_DUP_ENTRY is a duplicate. */
  @Test
  public void otherIntegrityViolationsAreNotDuplicates() {
    SQLException notNull =
        new SQLIntegrityConstraintViolationException(
            "Column 'metadata_location' cannot be null", "23000", 1048);
    Assertions.assertFalse(
        JdbcPersistenceFailures.isDuplicateKey(
            new DataIntegrityViolationException("could not execute statement", notNull)));

    SQLException tooLong = new SQLException("Data too long for column 'table_id'", "22001", 1406);
    Assertions.assertFalse(
        JdbcPersistenceFailures.isDuplicateKey(
            new DataIntegrityViolationException("could not execute statement", tooLong)));

    Assertions.assertFalse(
        JdbcPersistenceFailures.isDuplicateKey(
            new DataIntegrityViolationException("no sql cause at all")));
  }

  @Test
  public void cyclicCauseChainTerminates() {
    Assertions.assertFalse(
        JdbcPersistenceFailures.isDuplicateKey(
            new DataIntegrityViolationException("cycle", new SelfCausedException("cycle"))));
  }

  // -------------------------------------------------------------------------------------------
  // surfacingCorruption: the module-owned corruption failure is unwrapped from the ORM wrappers
  // at this persistence boundary, so nothing above it inspects ORM vocabulary.
  // -------------------------------------------------------------------------------------------

  private static final String CORRUPT_MSG =
      "Column user_table_row.entity_type holds unrecognized value ['TÁBLE']; "
          + "only TABLE, VIEW (in any case) and NULL are valid";

  private static final String HIBERNATE_MSG = "Error attempting to apply AttributeConverter";

  /** The shape Hibernate produces when the attribute converter fails mid-result-set. */
  @Test
  public void jpaSystemExceptionUnwrapsToItsCorruptCause() {
    CorruptEntityTypeException corrupt =
        new CorruptEntityTypeException(CORRUPT_MSG, new IllegalArgumentException("TÁBLE"));
    JpaSystemException wrapped =
        new JpaSystemException(new PersistenceException(HIBERNATE_MSG, corrupt));

    CorruptEntityTypeException surfaced =
        Assertions.assertThrows(
            CorruptEntityTypeException.class,
            () ->
                JdbcPersistenceFailures.surfacingCorruption(
                    (Supplier<Object>)
                        () -> {
                          throw wrapped;
                        }));
    Assertions.assertSame(corrupt, surfaced);
  }

  /** The other wrapper the JPA translator can pick. */
  @Test
  public void invalidDataAccessApiUsageExceptionUnwrapsToItsCorruptCause() {
    CorruptEntityTypeException corrupt = new CorruptEntityTypeException(CORRUPT_MSG);

    Assertions.assertThrows(
        CorruptEntityTypeException.class,
        () ->
            JdbcPersistenceFailures.surfacingCorruption(
                (Runnable)
                    () -> {
                      throw new InvalidDataAccessApiUsageException(HIBERNATE_MSG, corrupt);
                    }));
  }

  @Test
  public void deeplyNestedCorruptCauseIsStillUnwrapped() {
    CorruptEntityTypeException corrupt = new CorruptEntityTypeException(CORRUPT_MSG);
    JpaSystemException wrapped =
        new JpaSystemException(
            new PersistenceException(
                HIBERNATE_MSG,
                new IllegalStateException("outer", new RuntimeException("inner", corrupt))));

    CorruptEntityTypeException surfaced =
        Assertions.assertThrows(
            CorruptEntityTypeException.class,
            () ->
                JdbcPersistenceFailures.surfacingCorruption(
                    (Runnable)
                        () -> {
                          throw wrapped;
                        }));
    Assertions.assertSame(corrupt, surfaced);
  }

  /** A wrapper carrying no corruption is not translated; it stays exactly what it was. */
  @Test
  public void unrelatedDataAccessExceptionPassesThroughUntouched() {
    JpaSystemException unrelated =
        new JpaSystemException(new PersistenceException("connection reset"));

    JpaSystemException surfaced =
        Assertions.assertThrows(
            JpaSystemException.class,
            () ->
                JdbcPersistenceFailures.surfacingCorruption(
                    (Runnable)
                        () -> {
                          throw unrelated;
                        }));
    Assertions.assertSame(unrelated, surfaced);
  }

  /** Failures outside the data-access hierarchy are not this boundary's business. */
  @Test
  public void nonDataAccessFailuresAreNotIntercepted() {
    IllegalStateException unrelated = new IllegalStateException("not persistence");

    IllegalStateException surfaced =
        Assertions.assertThrows(
            IllegalStateException.class,
            () ->
                JdbcPersistenceFailures.surfacingCorruption(
                    (Runnable)
                        () -> {
                          throw unrelated;
                        }));
    Assertions.assertSame(unrelated, surfaced);
  }

  @Test
  public void cyclicCauseChainInWrapperTerminates() {
    Assertions.assertThrows(
        JpaSystemException.class,
        () ->
            JdbcPersistenceFailures.surfacingCorruption(
                (Runnable)
                    () -> {
                      throw new JpaSystemException(new SelfCausedException("cycle"));
                    }));
  }

  @Test
  public void successfulInteractionReturnsItsValue() {
    Assertions.assertEquals("value", JdbcPersistenceFailures.surfacingCorruption(() -> "value"));
  }

  /**
   * {@link Throwable#initCause} forbids a self-referential cause, so the cycle is expressed by
   * overriding the accessor.
   */
  static class SelfCausedException extends RuntimeException {
    SelfCausedException(String message) {
      super(message);
    }

    @Override
    public synchronized Throwable getCause() {
      return this;
    }
  }
}
