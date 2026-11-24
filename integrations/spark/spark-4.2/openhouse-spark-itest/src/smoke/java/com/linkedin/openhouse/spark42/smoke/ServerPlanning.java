package com.linkedin.openhouse.spark42.smoke;

import java.util.function.BiConsumer;

/**
 * Whether this smoke JVM runs the suite with server-side scan planning: the build's {@code
 * smokeTestServerPlanning} task sets the system property {@value #PROPERTY} to {@code server}, and
 * then both IRC catalogs of the session get the catalog property {@code scan-planning-mode=server},
 * so each statement's scans are planned by the service's plan route. The {@code smokeTest} task
 * sets nothing: the catalogs are default clients and never send a plan request.
 */
final class ServerPlanning {

  static final String PROPERTY = "irc42.scan-planning-mode";

  private ServerPlanning() {}

  /** True when this JVM runs the whole suite with server-side planning. */
  static boolean selected() {
    return "server".equals(System.getProperty(PROPERTY));
  }

  /** Adds {@code scan-planning-mode=server} to the catalog at {@code prefix}, if selected. */
  static void apply(String prefix, BiConsumer<String, String> set) {
    if (selected()) {
      set.accept(prefix + ".scan-planning-mode", "server");
    }
  }
}
