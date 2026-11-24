package com.linkedin.openhouse.tables.repository;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The schema, partition spec and sort order history ({@code
 * com.linkedin.openhouse.internal.catalog.CreateHistory}) of the create the current thread is
 * running, for the one caller that has one: the Iceberg REST commit that completes a staged create
 * (work item V14). The create itself runs unchanged, through the native create's services, rules
 * and audit, and the repository hands the history to the commit that writes the table's first
 * metadata. Kept beside the call rather than in a request or a DTO, so that no request of either
 * API can set it and no signature an aspect or a deployment binds to changes.
 */
public final class CreateHistoryScope {
  private static final ThreadLocal<String> HISTORY = new ThreadLocal<>();

  private CreateHistoryScope() {}

  /** Runs {@code create} with {@code history} ({@code null}: none) as the create's history. */
  public static <T> T during(String history, Supplier<T> create) {
    if (history == null) {
      return create.get();
    }
    String outer = HISTORY.get();
    HISTORY.set(history);
    try {
      return create.get();
    } finally {
      if (outer == null) {
        HISTORY.remove();
      } else {
        HISTORY.set(outer);
      }
    }
  }

  /** The history of the create the current thread is running, if it has one. */
  public static Optional<String> current() {
    return Optional.ofNullable(HISTORY.get());
  }
}
