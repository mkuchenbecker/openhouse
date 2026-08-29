package com.linkedin.openhouse.common.exception;

/**
 * Exception indicating a write broke a storage constraint that is neither the row key (a duplicate
 * key is a concurrent-writer conflict, reported separately) nor the caller's input (ingress
 * validation bounds those). Module-owned so the persistence boundary that produces it never lets an
 * ORM wrapper cross into HTTP advice; the advice maps it to a stable server error whose detail
 * lives in the server log.
 */
public class StorageIntegrityViolationException extends RuntimeException {

  public StorageIntegrityViolationException(String message, Throwable cause) {
    super(message, cause);
  }
}
