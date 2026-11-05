package com.linkedin.openhouse.tables.model;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Outcome of a sparse property update on a database: which keys were set, which were removed, and
 * which removals named a key that was not present.
 */
@Builder(toBuilder = true)
@Value
public class DatabasePropertiesUpdateDto {
  List<String> updated;

  List<String> removed;

  List<String> missing;
}
