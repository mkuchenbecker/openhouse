package com.linkedin.openhouse.jobs.util;

import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Metadata for batch operations that process multiple tables.
 *
 * <p>This is used by tasks that operate on multiple tables in a single Spark job.
 */
@Getter
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class BatchMetadata extends Metadata {
  private final List<TableMetadata> tables;

  @Override
  public String getEntityName() {
    return "batch_" + tables.size() + "_tables";
  }
}
