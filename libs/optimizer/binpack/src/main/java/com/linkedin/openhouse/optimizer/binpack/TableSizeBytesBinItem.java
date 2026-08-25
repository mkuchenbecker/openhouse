package com.linkedin.openhouse.optimizer.binpack;

import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableStatsDto;
import java.util.Optional;
import lombok.Getter;
import lombok.ToString;

/**
 * {@link BinItem} that weights by the table's on-disk size in bytes. Suitable for any operation
 * whose Spark cost scales with the volume of data rewritten — data compaction and data-layout
 * strategy execution, where the driver reads and rewrites file groups and the dominant cost is the
 * number of bytes shuffled, not the raw file count. The implementation knows nothing about which
 * operation type it is wired up to.
 *
 * <p>Construction mirrors {@link TotalFilesBinItem}: callers pass {@code
 * TableSizeBytesBinItem::new} as the {@code Supplier<T>} to {@link FirstFitDecreasingBinPacker};
 * the packer calls the supplier per operation to get an empty instance, then {@link
 * #fromOpAndStats} on it to get a populated copy.
 */
@Getter
@ToString
public class TableSizeBytesBinItem implements BinItem {

  private final String fullyQualifiedTableName;
  private final String operationId;
  private final long weight;

  /** Empty constructor: call {@link #fromOpAndStats} on the result to get a populated instance. */
  public TableSizeBytesBinItem() {
    this("", "", 0L);
  }

  private TableSizeBytesBinItem(String fullyQualifiedTableName, String operationId, long weight) {
    this.fullyQualifiedTableName = fullyQualifiedTableName;
    this.operationId = operationId;
    this.weight = weight;
  }

  @Override
  public BinItem fromOpAndStats(TableOperationDto op, TableStatsDto stats) {
    return new TableSizeBytesBinItem(
        op.getDatabaseName() + "." + op.getTableName(), op.getId(), tableSizeBytes(stats));
  }

  private static long tableSizeBytes(TableStatsDto stats) {
    return Optional.ofNullable(stats)
        .map(TableStatsDto::getSnapshot)
        .map(TableStatsDto.SnapshotMetrics::getTableSizeBytes)
        .orElse(0L);
  }
}
