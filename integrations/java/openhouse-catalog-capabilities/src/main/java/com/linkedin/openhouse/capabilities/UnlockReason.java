package com.linkedin.openhouse.capabilities;

/**
 * The lock an UNLOCK statement removes. The values, their order and their spelling are the
 * service's lock reasons.
 */
public enum UnlockReason {
  /** The lock set by the legacy lock route; what an UNLOCK without REASON removes. */
  LEGACY("LEGACY"),
  /** The lock only the system may hold. */
  SYSTEM_ONLY("SYSTEM_ONLY");

  private final String value;

  UnlockReason(String value) {
    this.value = value;
  }

  /** The service's spelling of this reason. */
  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return value;
  }

  /**
   * The reason with exactly this spelling.
   *
   * @return the reason, or null if no reason is spelled {@code value}
   */
  public static UnlockReason fromValue(String value) {
    for (UnlockReason reason : values()) {
      if (reason.value.equals(value)) {
        return reason;
      }
    }
    return null;
  }
}
