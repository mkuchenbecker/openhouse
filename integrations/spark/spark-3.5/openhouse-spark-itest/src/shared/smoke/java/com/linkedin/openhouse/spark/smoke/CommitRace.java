package com.linkedin.openhouse.spark.smoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Interleaves two clients of one table by construction, for the stale-handle scenario of the smoke
 * suite: a catalog of the suite's second catalog ({@code openhouse_second}) calls {@link
 * #beforeCommit} when a commit on a table is about to reach the tables service, after Iceberg built
 * and validated that commit against its base. If a step is armed for the table, it runs to
 * completion first, so what it commits lands between the commit's base and the commit, every time,
 * with no sleep and no thread timing. The step fires once; the outcome of every commit attempt is
 * recorded, by simple class name ({@code committed} for success).
 *
 * <p>The step runs on its own thread, started when it is armed, outside any Spark SQL execution: a
 * statement run from inside another statement's execution on the same thread is refused by Spark,
 * and a thread started inside one inherits its execution id.
 *
 * <p>Which point is "about to reach the service" is the catalog's: the OpenHouse table operations'
 * {@code doCommit} on the legacy and rest-shim paths, the HTTP client's table commit on the Iceberg
 * REST paths ({@link CommitRaceRestCatalog}). Same pattern as {@code CommitHook} of the jobs'
 * concurrency tests.
 */
public final class CommitRace {
  private static final Map<String, Armed> ARMED = new ConcurrentHashMap<>();
  private static final Map<String, List<String>> ATTEMPTS = new ConcurrentHashMap<>();
  private static final Map<String, Boolean> RAN = new ConcurrentHashMap<>();

  private CommitRace() {}

  private static final class Armed {
    final Runnable step;
    final ExecutorService thread;

    Armed(Runnable step) {
      this.step = step;
      ThreadPoolExecutor executor =
          new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
      executor.prestartAllCoreThreads();
      this.thread = executor;
    }
  }

  /**
   * Runs {@code step} before the next commit on {@code table} ({@code db.t}) reaches the service.
   */
  public static void arm(String table, Runnable step) {
    ATTEMPTS.put(table, Collections.synchronizedList(new ArrayList<>()));
    RAN.remove(table);
    Armed previous = ARMED.put(table, new Armed(step));
    if (previous != null) {
      previous.thread.shutdownNow();
    }
  }

  /** Whether the step armed for {@code table} ran, to completion and without failing. */
  public static boolean stepRan(String table) {
    return RAN.getOrDefault(table, false);
  }

  /** Disarms {@code table}, if its step has not run. */
  public static void disarm(String table) {
    Armed armed = ARMED.remove(table);
    if (armed != null) {
      armed.thread.shutdownNow();
    }
  }

  /** The outcome of every commit attempt on {@code table} since it was armed, in order. */
  public static List<String> attempts(String table) {
    List<String> attempts = ATTEMPTS.get(table);
    return attempts == null ? Collections.emptyList() : new ArrayList<>(attempts);
  }

  /** Called by a racing catalog right before a commit on {@code table} goes to the service. */
  public static void beforeCommit(String table) {
    Armed armed = ARMED.remove(table);
    if (armed == null) {
      return;
    }
    try {
      Future<?> done = armed.thread.submit(armed.step);
      done.get(5, TimeUnit.MINUTES);
      RAN.put(table, true);
    } catch (ExecutionException e) {
      throw new IllegalStateException("The armed step on " + table + " failed", e.getCause());
    } catch (Exception e) {
      throw new IllegalStateException("The armed step on " + table + " did not finish", e);
    } finally {
      armed.thread.shutdownNow();
    }
  }

  /** Called by a racing catalog when a commit on {@code table} has ended. */
  public static void afterCommit(String table, Throwable failure) {
    List<String> attempts = ATTEMPTS.get(table);
    if (attempts != null) {
      attempts.add(failure == null ? "committed" : failure.getClass().getSimpleName());
    }
  }
}
