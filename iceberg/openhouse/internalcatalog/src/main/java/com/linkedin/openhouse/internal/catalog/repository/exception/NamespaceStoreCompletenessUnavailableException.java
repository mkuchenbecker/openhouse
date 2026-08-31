package com.linkedin.openhouse.internal.catalog.repository.exception;

/**
 * The state of the database backfill could not be read from House Tables.
 *
 * <p>It exists so that the reader's caller does not have to know what House Tables is reached
 * through. Without it the WebClient's own {@code WebClientResponseException}, or whatever the
 * transport raises next, would travel out through a repository interface, and every caller that
 * wanted to distinguish "cannot tell" from "not complete" would be matching on a type owned by the
 * HTTP client.
 */
public class NamespaceStoreCompletenessUnavailableException extends RuntimeException {

  public NamespaceStoreCompletenessUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
