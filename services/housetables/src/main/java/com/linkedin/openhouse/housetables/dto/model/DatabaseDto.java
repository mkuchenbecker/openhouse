package com.linkedin.openhouse.housetables.dto.model;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * Needed to avoid coupling the transport and controller layer by sharing the same {@link
 * com.linkedin.openhouse.housetables.api.spec.model.Database} object.
 */
@Builder(toBuilder = true)
@Value
public class DatabaseDto {
  String databaseUuid;

  String databaseId;

  Long creationTime;

  Map<String, String> properties;
}
