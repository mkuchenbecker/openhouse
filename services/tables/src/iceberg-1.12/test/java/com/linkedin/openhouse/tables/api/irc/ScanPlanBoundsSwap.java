package com.linkedin.openhouse.tables.api.irc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Test access to the plan route's bounds: puts bounded plans of the test's own size in place of the
 * route's, and holds every planner thread of them, so that the route's answers to a busy server and
 * to a plan that takes too long can be asked for through HTTP. {@link #close} puts the route's own
 * bounds back.
 */
public final class ScanPlanBoundsSwap implements AutoCloseable {

  private final IcebergRestScanController controller;
  private final Object original;
  private final IcebergRestBoundedPlans replacement;
  private final CountDownLatch release = new CountDownLatch(1);

  private ScanPlanBoundsSwap(IcebergRestScanController controller, int maxConcurrent, long ms) {
    this.controller = controller;
    this.original = ReflectionTestUtils.getField(controller, "plans");
    this.replacement = new IcebergRestBoundedPlans(maxConcurrent, ms);
    ReflectionTestUtils.setField(controller, "plans", replacement);
  }

  /**
   * One planner thread, which a plan holds until {@link #close}: every plan the route is asked for
   * meanwhile is refused as busy.
   */
  public static ScanPlanBoundsSwap busy(IcebergRestScanController controller)
      throws InterruptedException {
    ScanPlanBoundsSwap swap = new ScanPlanBoundsSwap(controller, 1, TimeUnit.MINUTES.toMillis(1));
    CountDownLatch started = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () ->
                swap.replacement.run(
                    () -> {
                      started.countDown();
                      swap.release.await();
                      return null;
                    },
                    () -> null),
            "irc-scan-plan-holder");
    holder.setDaemon(true);
    holder.start();
    if (!started.await(30, TimeUnit.SECONDS)) {
      throw new IllegalStateException("the holding plan did not start");
    }
    return swap;
  }

  /**
   * A time bound of 100 ms, and a planner thread that starts no plan before {@link #close}: every
   * plan the route is asked for meanwhile is still waiting to start when its bound expires, so it
   * is answered as one that took too long, whatever the scheduling.
   */
  public static ScanPlanBoundsSwap timingOut(IcebergRestScanController controller) {
    ScanPlanBoundsSwap swap = new ScanPlanBoundsSwap(controller, 1, 100L);
    ThreadPoolExecutor held =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
              Thread thread = new Thread(runnable, "irc-scan-plan-held");
              thread.setDaemon(true);
              return thread;
            }) {
          @Override
          protected void beforeExecute(Thread thread, Runnable plan) {
            try {
              swap.release.await();
            } catch (InterruptedException e) {
              // The plan was cancelled when its bound expired: it does not run.
              Thread.currentThread().interrupt();
            }
          }
        };
    ExecutorService unused =
        (ExecutorService) ReflectionTestUtils.getField(swap.replacement, "planners");
    unused.shutdownNow();
    ReflectionTestUtils.setField(swap.replacement, "planners", held);
    return swap;
  }

  @Override
  public void close() {
    release.countDown();
    replacement.close();
    ReflectionTestUtils.setField(controller, "plans", original);
  }
}
