package com.linkedin.openhouse.optimizer.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stats payload for a table, sourced from nearline commit events.
 *
 * <p>Fields are partitioned into two categories:
 *
 * <ul>
 *   <li><b>Delta fields</b> — accumulated on each upsert: {@code numFilesAdded}, {@code
 *       numFilesDeleted}. Each commit event contributes an increment; the stored value is a running
 *       total.
 *   <li><b>Snapshot fields</b> — overwritten on each upsert: all other fields. These reflect the
 *       state at the time of the most recent commit event.
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableStats {

  // --- Snapshot fields (overwritten on every upsert) ---

  private String clusterId;
  private String tableVersion;
  private String tableLocation;
  private Integer numSnapshots;

  /** Total table size in bytes at the time of the last commit event. */
  private Long tableSizeBytes;

  // --- Delta fields (accumulated across commit events) ---

  /** Running total of data files added across all recorded commit events. */
  private Long numFilesAdded;

  /** Running total of data files deleted across all recorded commit events. */
  private Long numFilesDeleted;
}
