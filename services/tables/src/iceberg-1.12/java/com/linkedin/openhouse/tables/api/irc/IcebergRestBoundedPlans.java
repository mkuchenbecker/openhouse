package com.linkedin.openhouse.tables.api.irc;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs scan plans with two bounds: at most {@code maxConcurrent} at once, and at most {@code
 * timeoutMs} each.
 *
 * <p>Each plan runs on a thread of its own pool of {@code maxConcurrent} threads, with no queue: a
 * plan asked for while every thread plans is not started ({@link Busy}). The asking request waits
 * for its plan for at most {@code timeoutMs}; then it is answered as timed out and the plan is
 * interrupted. Iceberg may not stop at once (a manifest being read is read to its end), and until
 * it stops the plan keeps its thread, so the bound on planning work running at once holds even for
 * plans that time out.
 */
final class IcebergRestBoundedPlans implements AutoCloseable {

  /** A plan asked for while {@code maxConcurrent} plans run. */
  static final class Busy extends RuntimeException {
    Busy(int maxConcurrent) {
      super(
          String.format(
              "This server is planning %d scans, as many as it plans at once; retry later",
              maxConcurrent));
    }
  }

  private final int maxConcurrent;

  private final long timeoutMs;

  private final ThreadPoolExecutor planners;

  IcebergRestBoundedPlans(int maxConcurrent, long timeoutMs) {
    this.maxConcurrent = maxConcurrent;
    this.timeoutMs = timeoutMs;
    AtomicInteger threads = new AtomicInteger();
    this.planners =
        new ThreadPoolExecutor(
            maxConcurrent,
            maxConcurrent,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
              Thread thread = new Thread(runnable, "irc-scan-plan-" + threads.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * {@code plan}'s answer, or {@code timedOut}'s when the plan takes longer than the timeout. A
   * failure of the plan is rethrown as it is.
   *
   * @throws Busy if {@code maxConcurrent} plans run
   */
  <T> T run(Callable<T> plan, Supplier<T> timedOut) {
    Future<T> planned;
    try {
      planned = planners.submit(plan);
    } catch (RejectedExecutionException e) {
      throw new Busy(maxConcurrent);
    }
    try {
      return planned.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      planned.cancel(true);
      return timedOut.get();
    } catch (InterruptedException e) {
      planned.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for a scan plan", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new IllegalStateException(cause);
    }
  }

  @Override
  public void close() {
    planners.shutdownNow();
  }
}
