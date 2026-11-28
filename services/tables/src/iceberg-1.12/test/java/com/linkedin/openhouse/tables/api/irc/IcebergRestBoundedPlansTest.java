package com.linkedin.openhouse.tables.api.irc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The two bounds on scan planning ({@link IcebergRestBoundedPlans}): how many plans run at once and
 * how long one may take; and the startup refusal of a bound that is not positive.
 */
class IcebergRestBoundedPlansTest {

  /** A plan answered within the time bound is its own answer. */
  @Test
  void aPlanWithinTheBoundsIsAnswered() {
    try (IcebergRestBoundedPlans plans = new IcebergRestBoundedPlans(1, 10_000)) {
      assertEquals("planned", plans.run(() -> "planned", () -> "timed out"));
      // The thread is free again for the next plan.
      assertEquals("planned again", plans.run(() -> "planned again", () -> "timed out"));
    }
  }

  /**
   * With as many plans running as the bound allows, another is not started: {@link
   * IcebergRestBoundedPlans.Busy}, which the route answers 503. When one ends, a plan runs again.
   */
  @Test
  void aPlanBeyondTheConcurrencyBoundIsRefusedAsBusy() throws Exception {
    CountDownLatch running = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService callers = Executors.newFixedThreadPool(2);
    try (IcebergRestBoundedPlans plans = new IcebergRestBoundedPlans(2, 30_000)) {
      Future<?>[] held = new Future<?>[2];
      for (int i = 0; i < 2; i++) {
        held[i] =
            callers.submit(
                () ->
                    plans.run(
                        () -> {
                          running.countDown();
                          release.await();
                          return "held";
                        },
                        () -> "timed out"));
      }
      assertTrue(running.await(10, TimeUnit.SECONDS));
      IcebergRestBoundedPlans.Busy busy =
          assertThrows(
              IcebergRestBoundedPlans.Busy.class,
              () -> plans.run(() -> "third", () -> "timed out"));
      assertEquals(
          "This server is planning 2 scans, as many as it plans at once; retry later",
          busy.getMessage());
      release.countDown();
      for (Future<?> future : held) {
        assertEquals("held", future.get(10, TimeUnit.SECONDS));
      }
      // A plan that ended frees its thread; give the pool a moment to take the next one.
      String answer = null;
      for (int attempt = 0; attempt < 100 && answer == null; attempt++) {
        try {
          answer = plans.run(() -> "after", () -> "timed out");
        } catch (IcebergRestBoundedPlans.Busy stillBusy) {
          Thread.sleep(20);
        }
      }
      assertEquals("after", answer);
    } finally {
      callers.shutdownNow();
    }
  }

  /**
   * A plan that takes longer than the time bound is answered as timed out, and its planning is
   * interrupted; until it stops it keeps its thread, so it still counts against the concurrency
   * bound.
   */
  @Test
  void aPlanBeyondTheTimeBoundIsAnsweredTimedOutAndInterrupted() throws Exception {
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch stop = new CountDownLatch(1);
    try (IcebergRestBoundedPlans plans = new IcebergRestBoundedPlans(1, 100)) {
      long start = System.nanoTime();
      String answer =
          plans.run(
              () -> {
                try {
                  Thread.sleep(30_000);
                } catch (InterruptedException e) {
                  interrupted.countDown();
                  // Planning that does not stop at once (a manifest read to its end).
                  stop.await();
                }
                return "planned";
              },
              () -> "timed out");
      assertEquals("timed out", answer);
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 10_000);
      assertTrue(interrupted.await(10, TimeUnit.SECONDS));
      assertThrows(
          IcebergRestBoundedPlans.Busy.class, () -> plans.run(() -> "next", () -> "timed out"));
      stop.countDown();
    }
  }

  /** A failure of the plan is the route's failure, as it was before plans were bounded. */
  @Test
  void aPlansFailureIsRethrownAsItIs() {
    IllegalArgumentException failure = new IllegalArgumentException("Cannot bind filter");
    try (IcebergRestBoundedPlans plans = new IcebergRestBoundedPlans(1, 10_000)) {
      assertSame(
          failure,
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  plans.run(
                      () -> {
                        throw failure;
                      },
                      () -> "timed out")));
    }
  }

  /**
   * A bound that is not positive is refused when the route starts, rather than read as another
   * value: the task cap, the concurrency bound and the time bound alike.
   */
  @Test
  void aBoundThatIsNotPositiveIsRefusedAtStartup() {
    assertEquals(7, IcebergRestScanController.requirePositive("p", 7));
    for (long value : new long[] {0, -1}) {
      IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () ->
                  IcebergRestScanController.requirePositive(
                      IcebergRestScanController.MAX_FILE_SCAN_TASKS, value));
      assertEquals(
          IcebergRestScanController.MAX_FILE_SCAN_TASKS
              + " is "
              + value
              + ": with cluster.tables.irc.scan-planning.enabled on it must be positive",
          refused.getMessage());
    }
  }
}
