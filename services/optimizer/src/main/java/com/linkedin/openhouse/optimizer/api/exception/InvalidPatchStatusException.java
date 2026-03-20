package com.linkedin.openhouse.optimizer.api.exception;

/** Thrown when a PATCH request contains an invalid target status (not SUCCESS or FAILED). */
public class InvalidPatchStatusException extends RuntimeException {
  public InvalidPatchStatusException(String message) {
    super(message);
  }
}
