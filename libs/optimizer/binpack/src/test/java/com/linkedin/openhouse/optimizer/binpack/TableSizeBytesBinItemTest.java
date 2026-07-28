package com.linkedin.openhouse.optimizer.binpack;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableStatsDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TableSizeBytesBinItemTest {

  private static TableOperationDto op() {
    return TableOperationDto.builder()
        .id(UUID.randomUUID().toString())
        .tableUuid(UUID.randomUUID().toString())
        .databaseName("db1")
        .tableName("tbl1")
        .operationType(OperationTypeDto.DATA_LAYOUT_STRATEGY_EXECUTION)
        .build();
  }

  private static TableStatsDto statsWithBytes(Long sizeBytes) {
    return TableStatsDto.builder()
        .snapshot(TableStatsDto.SnapshotMetrics.builder().tableSizeBytes(sizeBytes).build())
        .build();
  }

  @Test
  void fromOpAndStats_buildsFullyQualifiedNameAndOperationId() {
    TableOperationDto op = op();
    BinItem item = new TableSizeBytesBinItem().fromOpAndStats(op, statsWithBytes(42L));

    assertThat(item.getFullyQualifiedTableName()).isEqualTo("db1.tbl1");
    assertThat(item.getOperationId()).isEqualTo(op.getId());
  }

  @Test
  void fromOpAndStats_weightIsTableSizeBytes() {
    BinItem item = new TableSizeBytesBinItem().fromOpAndStats(op(), statsWithBytes(123_456L));
    assertThat(item.getWeight()).isEqualTo(123_456L);
  }

  @Test
  void fromOpAndStats_nullStats_weightIsZero() {
    BinItem item = new TableSizeBytesBinItem().fromOpAndStats(op(), null);
    assertThat(item.getWeight()).isEqualTo(0L);
  }

  @Test
  void fromOpAndStats_nullSnapshot_weightIsZero() {
    BinItem item =
        new TableSizeBytesBinItem().fromOpAndStats(op(), TableStatsDto.builder().build());
    assertThat(item.getWeight()).isEqualTo(0L);
  }

  @Test
  void fromOpAndStats_nullSizeBytes_weightIsZero() {
    BinItem item = new TableSizeBytesBinItem().fromOpAndStats(op(), statsWithBytes(null));
    assertThat(item.getWeight()).isEqualTo(0L);
  }

  @Test
  void emptyInstance_doesNotShareStateWithPopulated() {
    TableSizeBytesBinItem empty = new TableSizeBytesBinItem();
    BinItem populated = empty.fromOpAndStats(op(), statsWithBytes(7L));

    assertThat(empty.getWeight()).isEqualTo(0L);
    assertThat(populated.getWeight()).isEqualTo(7L);
  }
}
