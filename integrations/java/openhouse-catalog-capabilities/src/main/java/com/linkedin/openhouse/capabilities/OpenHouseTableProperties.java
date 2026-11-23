package com.linkedin.openhouse.capabilities;

/** Table property keys the OpenHouse SQL extensions read or write. */
public final class OpenHouseTableProperties {

  /** Present on every OpenHouse table; the policy statements refuse a table without it. */
  public static final String TABLE_ID = "openhouse.tableId";

  /**
   * The request property a policy statement sets with a JSON patch of the table's policies. It is
   * never stored: the OpenHouse catalog (or, over the Iceberg REST catalog, the server) merges the
   * patch onto the stored policies.
   */
  public static final String POLICY_PATCH = "updated.openhouse.policy";

  private OpenHouseTableProperties() {}
}
