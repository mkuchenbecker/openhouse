package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

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
