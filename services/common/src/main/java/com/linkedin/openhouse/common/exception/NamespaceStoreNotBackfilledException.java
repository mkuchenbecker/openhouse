package com.linkedin.openhouse.common.exception;

/**
 * Raised when a namespace read cannot be served because the namespace store has not been shown to
 * hold every database the cluster has.
 *
 * <p>Existence used to be composed from two stores: a database with no namespace row still existed
 * because the table store held tables under it. That derivation is gone, and the namespace store
 * alone answers now. On a cluster whose store has not been backfilled the honest answer to "which
 * databases exist" is not "none" — it is "this service cannot tell you yet". Reporting an empty
 * catalog would be silent, plausible and indistinguishable from a genuinely empty cluster, and a
 * client acting on it would recreate tables that already exist.
 *
 * <p>The message names the remedy, because the caller who sees it is rarely the operator who can
 * apply it and the two are connected only by what this string says.
 */
public class NamespaceStoreNotBackfilledException extends RuntimeException {

  public NamespaceStoreNotBackfilledException(String message) {
    super(message);
  }

  public NamespaceStoreNotBackfilledException(String message, Throwable cause) {
    super(message, cause);
  }
}
