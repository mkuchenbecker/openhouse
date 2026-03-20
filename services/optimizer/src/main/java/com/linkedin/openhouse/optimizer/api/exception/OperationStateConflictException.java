package com.linkedin.openhouse.optimizer.api.exception;

/** Thrown when a PATCH targets a row that is not in SCHEDULED state. */
public class OperationStateConflictException extends RuntimeException {
  public OperationStateConflictException(String message) {
    super(message);
  }
}
