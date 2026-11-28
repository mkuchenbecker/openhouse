package com.linkedin.openhouse.spark42.irc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Whether this test JVM runs the suite with server-side scan planning: the build's {@code
 * testServerPlanning} task sets the system property {@value #PROPERTY} to {@code server}, and then
 * every catalog the suite configures for the service also gets the catalog property {@code
 * scan-planning-mode=server}, so each SELECT and each row-level write plans its scans through the
 * service's plan route. The {@code test} task sets nothing: every catalog is configured as a
 * default client, and only the catalogs that ask for it plan on the server.
 *
 * <p>Declared exception: the catalogs whose tables are format v3 ({@link #FORMAT_V3}) keep client
 * planning in that run, because the plan route refuses format v3 tables and every statement of
 * those classes would fail with the refusal. The refusal itself is tested where it is the subject
 * ({@code SparkIrcScanPlanningTest}, {@code SparkIrcServerPlanningWritesTest}).
 */
final class ServerPlanning {

  static final String PROPERTY = "irc42.scan-planning-mode";

  /**
   * The service's cluster property that serves scan planning, off by default: the services of the
   * server-planning run turn it on, those of the default run leave it off.
   */
  static final String SERVICE_PROPERTY = "cluster.tables.irc.scan-planning.enabled";

  private ServerPlanning() {}

  /** True when this JVM runs the whole suite with server-side planning. */
  static boolean selected() {
    return "server".equals(System.getProperty(PROPERTY));
  }

  /** Why a catalog of format v3 tables is not switched to server planning. */
  static final String FORMAT_V3 =
      "its tables are format v3, and the plan route refuses format v3 tables: a scan task"
          + " carries no sequence number, so row lineage would be wrong";

  private static final List<String> NOT_APPLIED = Collections.synchronizedList(new ArrayList<>());

  /**
   * Declares that the catalog at {@code prefix} keeps client planning in the server-planning run,
   * and why; recorded, printed, and listed by {@code SparkIrcNoPlanRequestTest}.
   */
  static void notApplied(String prefix, String why) {
    if (selected()) {
      String line = prefix + ": " + why;
      NOT_APPLIED.add(line);
      System.out.println("Server planning not applied to " + line);
    }
  }

  /** The catalogs declared with {@link #notApplied} so far. */
  static List<String> notApplied() {
    return new ArrayList<>(NOT_APPLIED);
  }

  /** Adds {@code scan-planning-mode=server} to the catalog at {@code prefix}, if selected. */
  static void apply(String prefix, BiConsumer<String, String> set) {
    if (selected()) {
      set.accept(prefix + ".scan-planning-mode", "server");
    }
  }
}
