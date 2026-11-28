package com.linkedin.openhouse.tables.formatversion;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.Value;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The format version upgrades a request's commits make, from the check that lets each one through
 * to the audit of the request that made it.
 *
 * <p>An upgrade cannot be undone, and a table upgraded to format v3 can no longer be read by
 * LinkedIn's Iceberg 1.5.2 or by the readers built on it. The format version check records each
 * upgrade it lets through ({@link #record}); the table audit takes it once the write has succeeded
 * ({@link #take}), and logs and audits it as an operation of its own. A commit that fails after the
 * check leaves its record unread, and the request's end discards it.
 *
 * <p>Records live in the request's attributes; outside a request nothing is recorded.
 */
public final class FormatVersionUpgrades {

  private static final String ATTRIBUTE = FormatVersionUpgrades.class.getName();

  /** One table's upgrade: the format version it had and the one it was given. */
  @Value
  public static class Upgrade {
    int from;
    int to;
  }

  private FormatVersionUpgrades() {}

  /** Records that the commit on {@code table} ({@code db.table}) raises its format version. */
  public static void record(String table, int from, int to) {
    Map<String, Upgrade> upgrades = upgrades(true);
    if (upgrades != null) {
      upgrades.put(table, new Upgrade(from, to));
    }
  }

  /** The upgrade recorded for {@code databaseId.tableId} in this request, removed as it is read. */
  public static Optional<Upgrade> take(String databaseId, String tableId) {
    Map<String, Upgrade> upgrades = upgrades(false);
    return upgrades == null
        ? Optional.empty()
        : Optional.ofNullable(upgrades.remove(databaseId + "." + tableId));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Upgrade> upgrades(boolean create) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return null;
    }
    Object held = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (held == null && create) {
      held = new HashMap<String, Upgrade>();
      attributes.setAttribute(ATTRIBUTE, held, RequestAttributes.SCOPE_REQUEST);
    }
    return (Map<String, Upgrade>) held;
  }
}
