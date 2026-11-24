package com.linkedin.openhouse.tables.model;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.Value;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;

/**
 * A table as one load from the internal catalog saw it: the {@link TableDto} the native API is
 * built from, and the Iceberg {@link TableMetadata} that DTO was converted from. Serving both from
 * the same load means an IRC load response and its authorization checks see the same version.
 *
 * <p>{@link #getTable()} is the Iceberg {@link Table} that load returned, whose current metadata is
 * {@link #getMetadata()}: what the IRC scan planning route plans on, with the table's own FileIO.
 * It takes no part in equality or the string form.
 */
@Value
public class TableDtoWithMetadata {
  @NonNull TableDto tableDto;
  @NonNull TableMetadata metadata;

  @NonNull @EqualsAndHashCode.Exclude @ToString.Exclude Table table;
}
