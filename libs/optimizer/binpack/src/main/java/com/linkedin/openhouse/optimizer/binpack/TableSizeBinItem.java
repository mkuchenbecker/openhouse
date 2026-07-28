package com.linkedin.openhouse.optimizer.binpack;

import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableStatsDto;
import java.util.Optional;
import lombok.Getter;
import lombok.ToString;

/**
 * {@link BinItem} that weights by the table's on-disk size in bytes ({@code tableSizeBytes}).
 * Suitable for any operation whose Spark cost scales with the volume of data read and rewritten —
 * data compaction being the canonical case, where every byte in the table is potentially read,
 * re-sorted into target-sized files, and written back. File <i>count</i> alone is a poor proxy
 * here: a table of a million tiny files and a table of a thousand multi-GB files can carry wildly
 * different rewrite costs, and it is the byte volume — not the file count — that drives shuffle,
 * I/O, and commit time. The implementation knows nothing about which operation type it is wired up
 * to.
 *
 * <p>Construction: callers pass {@code TableSizeBinItem::new} as the {@code Supplier<T>} to {@link
 * FirstFitDecreasingBinPacker}; the packer calls the supplier per operation to get an empty
 * instance, then {@link #fromOpAndStats} on it to get a populated copy.
 */
@Getter
@ToString
public class TableSizeBinItem implements BinItem {

  private final String fullyQualifiedTableName;
  private final String operationId;
  private final long weight;

  /** Empty constructor: call {@link #fromOpAndStats} on the result to get a populated instance. */
  public TableSizeBinItem() {
    this("", "", 0L);
  }

  private TableSizeBinItem(String fullyQualifiedTableName, String operationId, long weight) {
    this.fullyQualifiedTableName = fullyQualifiedTableName;
    this.operationId = operationId;
    this.weight = weight;
  }

  @Override
  public BinItem fromOpAndStats(TableOperationDto op, TableStatsDto stats) {
    return new TableSizeBinItem(
        op.getDatabaseName() + "." + op.getTableName(), op.getId(), tableSizeBytes(stats));
  }

  private static long tableSizeBytes(TableStatsDto stats) {
    return Optional.ofNullable(stats)
        .map(TableStatsDto::getSnapshot)
        .map(TableStatsDto.SnapshotMetrics::getTableSizeBytes)
        .orElse(0L);
  }
}
