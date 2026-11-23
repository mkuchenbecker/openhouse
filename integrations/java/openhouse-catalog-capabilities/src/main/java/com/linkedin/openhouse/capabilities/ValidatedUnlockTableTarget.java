package com.linkedin.openhouse.capabilities;

import java.util.Objects;

/** The {@link UnlockTableTarget} that {@link UnlockTableTarget#of} builds. */
final class ValidatedUnlockTableTarget implements UnlockTableTarget {
  private final String databaseId;
  private final String tableId;

  ValidatedUnlockTableTarget(String databaseId, String tableId) {
    this.databaseId = databaseId;
    this.tableId = tableId;
  }

  @Override
  public String getDatabaseId() {
    return databaseId;
  }

  @Override
  public String getTableId() {
    return tableId;
  }

  @Override
  public String toString() {
    return databaseId + "." + tableId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ValidatedUnlockTableTarget)) {
      return false;
    }
    ValidatedUnlockTableTarget that = (ValidatedUnlockTableTarget) other;
    return databaseId.equals(that.databaseId) && tableId.equals(that.tableId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(databaseId, tableId);
  }

  static void validatePathSegment(String name, String value) throws UnlockValidationException {
    if (value == null || value.isEmpty()) {
      throw new UnlockValidationException(name + " must not be null or empty");
    }
    if (".".equals(value) || "..".equals(value)) {
      throw new UnlockValidationException(name + " must not be '" + value + "'");
    }
  }
}
