package com.linkedin.openhouse.spark42.irc;

import java.util.function.BiConsumer;

/**
 * Whether this test JVM runs the suite with server-side scan planning: the build's {@code
 * testServerPlanning} task sets the system property {@value #PROPERTY} to {@code server}, and then
 * every catalog the suite configures for the service also gets the catalog property {@code
 * scan-planning-mode=server}, so each SELECT and each row-level write plans its scans through the
 * service's plan route. The {@code test} task sets nothing: every catalog is configured as a
 * default client, and only the catalogs that ask for it plan on the server.
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
