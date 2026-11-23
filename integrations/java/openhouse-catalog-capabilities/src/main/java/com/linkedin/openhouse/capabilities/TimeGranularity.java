package com.linkedin.openhouse.capabilities;

/**
 * The granularity of a retention or history duration in a policy statement ({@code 24h}, {@code
 * 30d}, {@code 2m}, {@code 1y}). The values, their order and their spelling are the service's time
 * partition granularities, which is how the policy patches spell them ({@code
 * "granularity":"DAY"}).
 */
public enum TimeGranularity {
  HOUR("HOUR"),
  DAY("DAY"),
  MONTH("MONTH"),
  YEAR("YEAR");

  private final String value;

  TimeGranularity(String value) {
    this.value = value;
  }

  /** The service's spelling of this granularity. */
  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return value;
  }

  /**
   * The granularity with exactly this spelling.
   *
   * @return the granularity, or null if none is spelled {@code value}
   */
  public static TimeGranularity fromValue(String value) {
    for (TimeGranularity granularity : values()) {
      if (granularity.value.equals(value)) {
        return granularity;
      }
    }
    return null;
  }
}
