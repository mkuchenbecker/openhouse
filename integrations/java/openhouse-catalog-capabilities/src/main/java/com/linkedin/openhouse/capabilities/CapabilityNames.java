package com.linkedin.openhouse.capabilities;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The names of OpenHouse capabilities. A capability is a lower-case name with a direction:
 *
 * <ul>
 *   <li>A <b>server capability</b> ({@link #SERVER_CAPABILITIES}) means "this endpoint serves
 *       OpenHouse feature X". An Iceberg REST server advertises the ones it serves in its config
 *       response under {@link #ADVERTISED_PROPERTY}, as a comma-separated list, so a client reads
 *       them from the catalog's properties.
 *   <li>A <b>client capability</b> ({@link #CLIENT_CAPABILITIES}) means "this client implements
 *       behavior X that a table may require". A client declares the ones it implements in the
 *       request header {@link #CLIENT_CAPABILITIES_HEADER}; a table that requires one ({@code
 *       required-capability.<name>=true}) is refused to a client that does not declare it.
 * </ul>
 *
 * <p>Both directions share this class so that a name means one thing.
 */
public final class CapabilityNames {

  /**
   * Server capability: retention, sharing, history, replication and column-tag policies, set by the
   * policy statements as the table property {@link OpenHouseTableProperties#POLICY_PATCH}.
   */
  public static final String POLICIES = "policies";

  /** Server capability: GRANT, REVOKE and SHOW GRANTS on tables and databases. */
  public static final String GRANTS = "grants";

  /** Server capability: ALTER TABLE ... UNLOCK. */
  public static final String UNLOCK = "unlock";

  /** Client capability: the client applies the column defaults the catalog loads. */
  public static final String COLUMN_DEFAULTS = "column-defaults";

  /** The config property, and so the catalog property, that lists the server capabilities. */
  public static final String ADVERTISED_PROPERTY = "openhouse.capabilities";

  /** The request header that lists the client capabilities. */
  public static final String CLIENT_CAPABILITIES_HEADER = "X-OpenHouse-Client-Capabilities";

  /** Every server capability this client knows. */
  public static final Set<String> SERVER_CAPABILITIES = setOf(POLICIES, GRANTS, UNLOCK);

  /** Every client capability this client knows. */
  public static final Set<String> CLIENT_CAPABILITIES = setOf(COLUMN_DEFAULTS);

  private CapabilityNames() {}

  /**
   * Reads a comma-separated list of names, as advertised under {@link #ADVERTISED_PROPERTY}: each
   * entry is trimmed and lower-cased, empty entries are dropped, and names this client does not
   * know are kept (a newer server may advertise more).
   *
   * @param advertised the list, or null
   * @return the names in their advertised order; empty for null or blank input; unmodifiable
   */
  public static Set<String> parse(String advertised) {
    if (advertised == null) {
      return Collections.emptySet();
    }
    Set<String> names = new LinkedHashSet<>();
    for (String entry : advertised.split(",")) {
      String name = entry.trim().toLowerCase(Locale.ROOT);
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    return Collections.unmodifiableSet(names);
  }

  private static Set<String> setOf(String... names) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
  }
}
