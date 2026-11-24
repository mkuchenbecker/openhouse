package com.linkedin.openhouse.javaclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interleaves two writers by construction, for the concurrency tests (work item X10): a {@link
 * CommitHookCatalog} runs the step armed for a table at the start of that table's next commit
 * attempt, after Iceberg built and validated the commit against its base and before the client
 * sends it to the tables service. The step runs to completion before the attempt goes on, so what
 * it commits lands between the writer's base and its commit, every time, with no sleeps and no
 * reliance on thread timing. The hook fires once; every attempt's outcome is recorded.
 */
public final class CommitHook {
  private static final Map<String, Runnable> ARMED = new ConcurrentHashMap<>();
  private static final Map<String, List<String>> ATTEMPTS = new ConcurrentHashMap<>();

  private CommitHook() {}

  /** Runs {@code step} at the start of the next commit attempt on {@code table} ({@code db.t}). */
  public static void arm(String table, Runnable step) {
    ATTEMPTS.put(table, Collections.synchronizedList(new ArrayList<>()));
    ARMED.put(table, step);
  }

  /** Whether the armed step is still waiting for a commit attempt on {@code table}. */
  public static boolean armed(String table) {
    return ARMED.containsKey(table);
  }

  public static void disarm(String table) {
    ARMED.remove(table);
  }

  /**
   * The outcome of every commit attempt on {@code table} through a {@link CommitHookCatalog} since
   * it was armed, in order: {@code committed}, or the exception's class and message.
   */
  public static List<String> attempts(String table) {
    List<String> attempts = ATTEMPTS.get(table);
    return attempts == null ? Collections.emptyList() : new ArrayList<>(attempts);
  }

  static void beforeAttempt(String table) {
    Runnable step = ARMED.remove(table);
    if (step != null) {
      step.run();
    }
  }

  static void afterAttempt(String table, Throwable failure) {
    List<String> attempts = ATTEMPTS.get(table);
    if (attempts != null) {
      attempts.add(
          failure == null
              ? "committed"
              : failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
    }
  }
}
