package com.linkedin.openhouse.tables.model;

import lombok.NonNull;
import lombok.Value;
import org.apache.iceberg.TableMetadata;

/**
 * A table as one load from the internal catalog saw it: the {@link TableDto} the native API is
 * built from, and the Iceberg {@link TableMetadata} that DTO was converted from. Serving both from
 * the same load means an IRC load response and its authorization checks see the same version.
 */
@Value
public class TableDtoWithMetadata {
  @NonNull TableDto tableDto;
  @NonNull TableMetadata metadata;
}
