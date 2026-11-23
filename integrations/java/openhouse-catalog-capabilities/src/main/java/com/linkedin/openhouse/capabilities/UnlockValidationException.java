package com.linkedin.openhouse.capabilities;

/** Invalid UNLOCK input, rejected before any catalog request. */
public class UnlockValidationException extends Exception {
  private static final long serialVersionUID = 1L;

  public UnlockValidationException(String message) {
    super(message);
  }
}
