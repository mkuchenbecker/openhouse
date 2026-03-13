package com.linkedin.openhouse.scheduler.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read-only mirror of the {@code table_stats} row in the optimizer DB.
 *
 * <p>Only the {@code stats} JSON blob is needed; {@code numCurrentFiles} is extracted from it at
 * runtime via Jackson.
 */
@Entity
@Table(name = "table_stats")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SchedulerStatsRow {

  @Id
  @Column(name = "table_uuid", nullable = false, length = 36)
  private String tableUuid;

  /** Raw JSON blob — parsed by {@code SchedulerRunner} to extract {@code numCurrentFiles}. */
  @Column(name = "stats", columnDefinition = "TEXT")
  private String stats;
}
