package com.linkedin.openhouse.jobs.util;

import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Metadata for a scheduler task that processes multiple tables in one Spark job.
 *
 * <p>Holds the list of tables so the task can pass all table names to the batched app in a single
 * job launch, avoiding per-table session overhead.
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
