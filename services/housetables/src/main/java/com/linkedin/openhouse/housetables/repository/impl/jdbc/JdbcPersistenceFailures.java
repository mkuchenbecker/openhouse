package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.common.exception.CorruptEntityTypeException;
import com.linkedin.openhouse.common.exception.StorageIntegrityViolationException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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

  /**
   * ... distinguished by vendor code: {@code ER_DUP_ENTRY} (1062) and {@code
   * ER_DUP_ENTRY_WITH_KEY_NAME} (1586). Spring 5.3's own {@code sql-error-codes.xml} classifies
   * only 1062 as a duplicate key for MySQL; 1586 is the same duplicate reported with the key's
   * name, which newer Spring generations added to the same bucket, so both are recognized here.
   */
  private static final Set<Integer> MYSQL_DUPLICATE_ENTRY_CODES =
      Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(1062, 1586)));

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
   * duplicate-entry vendor codes under SQLSTATE {@code 23000}.
   */
  public static boolean isDuplicateKey(DataIntegrityViolationException exception) {
    if (exception instanceof DuplicateKeyException) {
      return true;
    }
    for (Throwable cause : boundedCauseChain(exception)) {
      if (cause instanceof SQLException && isDuplicateKeySqlException((SQLException) cause)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The module-owned rendering of an integrity violation that {@link #isDuplicateKey} disclaimed:
   * not a concurrent writer, not the caller's input (ingress bounds those), so a server-side
   * storage failure. Translating here keeps the raw Spring type from leaving the service layer; the
   * advice renders the result as a sealed 500 whose detail lives in the server log.
   */
  public static StorageIntegrityViolationException serverFailure(
      DataIntegrityViolationException exception) {
    return new StorageIntegrityViolationException(
        "A House Tables write broke a storage constraint other than the row key", exception);
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
    for (Throwable cause : boundedCauseChain(exception)) {
      if (cause instanceof CorruptEntityTypeException) {
        return Optional.of((CorruptEntityTypeException) cause);
      }
    }
    return Optional.empty();
  }

  /**
   * The cause chain from {@code exception} inclusive, bounded by {@link #CAUSE_CHAIN_MAX_DEPTH} and
   * by identity so a cyclic chain terminates instead of spinning.
   */
  private static List<Throwable> boundedCauseChain(Throwable exception) {
    List<Throwable> chain = new ArrayList<>();
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = exception;
    for (int depth = 0; current != null && depth < CAUSE_CHAIN_MAX_DEPTH; depth++) {
      if (!visited.add(current)) {
        break;
      }
      chain.add(current);
      current = current.getCause();
    }
    return chain;
  }

  private static boolean isDuplicateKeySqlException(SQLException sqlException) {
    String sqlState = sqlException.getSQLState();
    if (SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
      return true;
    }
    return SQLSTATE_INTEGRITY_VIOLATION.equals(sqlState)
        && MYSQL_DUPLICATE_ENTRY_CODES.contains(sqlException.getErrorCode());
  }
}
