package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.common.exception.CorruptEntityTypeException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.NonTransientDataAccessException;

/**
 * House Tables' own reading of the failures its JDBC persistence layer produces. The ORM and
 * Spring's translation wrap database outcomes in vocabulary that belongs to this module's
 * persistence boundary; interpreting those wrappers here keeps the service and HTTP layers speaking
 * module-owned failures instead of inspecting ORM causes.
 */
@Slf4j
public final class JdbcPersistenceFailures {

  /**
   * Cause chains are walked under this bound and by identity so a cyclic chain terminates instead
   * of spinning.
   */
  private static final int CAUSE_CHAIN_MAX_DEPTH = 20;

  /** H2 and PostgreSQL: unique or primary key violation. */
  private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

  /** MySQL reports duplicates under the generic integrity-violation SQLSTATE ... */
  private static final String SQLSTATE_INTEGRITY_VIOLATION = "23000";

  /** ... distinguished by its vendor code, ER_DUP_ENTRY. */
  private static final int MYSQL_ER_DUP_ENTRY = 1062;

  private JdbcPersistenceFailures() {}

  /**
   * Whether an integrity violation is specifically a duplicate key, meaning another writer holds
   * the row, as opposed to any other constraint the statement may have broken. Callers use this to
   * report a 409-shaped race for the duplicate case only and let everything else surface as the
   * server failure it is, instead of blaming every integrity violation on a concurrent writer.
   *
   * <p>Spring translates plain JDBC duplicates to {@link DuplicateKeyException}, but through the
   * JPA dialect a duplicate arrives as the generic {@link DataIntegrityViolationException}, so the
   * SQL exception underneath is consulted: SQLSTATE {@code 23505} (H2, PostgreSQL) or MySQL's
   * {@code ER_DUP_ENTRY} vendor code under SQLSTATE {@code 23000}.
   */
  public static boolean isDuplicateKey(DataIntegrityViolationException exception) {
    if (exception instanceof DuplicateKeyException) {
      return true;
    }
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = exception;
    for (int depth = 0; current != null && depth < CAUSE_CHAIN_MAX_DEPTH; depth++) {
      if (!visited.add(current)) {
        break;
      }
      if (current instanceof SQLException && isDuplicateKeySqlException((SQLException) current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /**
   * Runs a repository interaction and surfaces a {@link CorruptEntityTypeException} the ORM
   * wrapped. The {@code entity_type} attribute converter raises it during hydration, and JPA
   * exception translation hands it back inside {@code JpaSystemException} or {@code
   * InvalidDataAccessApiUsageException}; unwrapping here, at the module's own persistence boundary,
   * means neither the service layer nor the shared HTTP advice ever inspects ORM wrapper types. A
   * wrapper carrying no corruption is rethrown untouched.
   */
  public static <T> T surfacingCorruption(Supplier<T> repositoryInteraction) {
    try {
      return repositoryInteraction.get();
    } catch (NonTransientDataAccessException dataAccessException) {
      throw findCorruptCause(dataAccessException)
          .map(
              corrupt -> {
                log.debug(
                    "Translating a persistence wrapper into its corrupt entity type cause",
                    dataAccessException);
                return (RuntimeException) corrupt;
              })
          .orElse(dataAccessException);
    }
  }

  /** The {@link Runnable}-shaped twin of {@link #surfacingCorruption(Supplier)}. */
  public static void surfacingCorruption(Runnable repositoryInteraction) {
    surfacingCorruption(
        () -> {
          repositoryInteraction.run();
          return null;
        });
  }

  private static Optional<CorruptEntityTypeException> findCorruptCause(Throwable exception) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = exception;
    for (int depth = 0; current != null && depth < CAUSE_CHAIN_MAX_DEPTH; depth++) {
      if (!visited.add(current)) {
        break;
      }
      if (current instanceof CorruptEntityTypeException) {
        return Optional.of((CorruptEntityTypeException) current);
      }
      current = current.getCause();
    }
    return Optional.empty();
  }

  private static boolean isDuplicateKeySqlException(SQLException sqlException) {
    String sqlState = sqlException.getSQLState();
    if (SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
      return true;
    }
    return SQLSTATE_INTEGRITY_VIOLATION.equals(sqlState)
        && sqlException.getErrorCode() == MYSQL_ER_DUP_ENTRY;
  }
}
