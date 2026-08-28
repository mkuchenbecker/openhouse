package com.linkedin.openhouse.common.exception;

/**
 * Exception indicating a stored discriminator value outside the vocabulary its column may hold.
 * Server-side corruption rather than a bad request, and deliberately not an {@link
 * IllegalArgumentException}: the two outcomes map to different HTTP categories, so this type shares
 * no catch-compatibility with client-input failures. The housetables persistence boundary
 * translates the ORM wrappers this exception hydrates under, so callers above it see this
 * module-owned failure and the HTTP advice maps it to a stable server error.
 */
public class CorruptEntityTypeException extends RuntimeException {

  public CorruptEntityTypeException(String message) {
    super(message);
  }

  public CorruptEntityTypeException(String message, Throwable cause) {
    super(message, cause);
  }
}
