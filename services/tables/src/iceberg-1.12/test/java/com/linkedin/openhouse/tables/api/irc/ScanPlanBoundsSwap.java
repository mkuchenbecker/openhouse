package com.linkedin.openhouse.tables.api.irc;

import java.util.concurrent.CountDownLatch;
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
   * A time bound of zero: the asking request stops waiting as soon as the plan is handed to its
   * thread, before any plan can finish, so every plan is answered as one that took too long. (The
   * route refuses to start with such a bound; it is set here past that check, only to reach the
   * answer.)
   */
  public static ScanPlanBoundsSwap timingOut(IcebergRestScanController controller) {
    return new ScanPlanBoundsSwap(controller, 1, 0L);
  }

  @Override
  public void close() {
    release.countDown();
    replacement.close();
    ReflectionTestUtils.setField(controller, "plans", original);
  }
}
