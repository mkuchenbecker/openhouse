package com.linkedin.openhouse.optimizer.binpack;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableStatsDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TableSizeBinItemTest {

  private static TableOperationDto op() {
    return TableOperationDto.builder()
        .id(UUID.randomUUID().toString())
        .tableUuid(UUID.randomUUID().toString())
        .databaseName("db1")
        .tableName("tbl1")
        .operationType(OperationTypeDto.DATA_COMPACTION)
        .build();
  }

  private static TableStatsDto statsWithSize(Long sizeBytes) {
    return TableStatsDto.builder()
        .snapshot(TableStatsDto.SnapshotMetrics.builder().tableSizeBytes(sizeBytes).build())
        .build();
  }

  @Test
  void fromOpAndStats_buildsFullyQualifiedNameAndOperationId() {
    TableOperationDto op = op();
    BinItem item = new TableSizeBinItem().fromOpAndStats(op, statsWithSize(42L));

    assertThat(item.getFullyQualifiedTableName()).isEqualTo("db1.tbl1");
    assertThat(item.getOperationId()).isEqualTo(op.getId());
  }

  @Test
  void fromOpAndStats_weightIsTableSizeBytes() {
    BinItem item = new TableSizeBinItem().fromOpAndStats(op(), statsWithSize(1_073_741_824L));
    assertThat(item.getWeight()).isEqualTo(1_073_741_824L);
  }

  @Test
  void fromOpAndStats_nullStats_weightIsZero() {
    BinItem item = new TableSizeBinItem().fromOpAndStats(op(), null);
    assertThat(item.getWeight()).isEqualTo(0L);
  }

  @Test
  void fromOpAndStats_nullSnapshot_weightIsZero() {
    BinItem item = new TableSizeBinItem().fromOpAndStats(op(), TableStatsDto.builder().build());
    assertThat(item.getWeight()).isEqualTo(0L);
  }

  @Test
  void fromOpAndStats_nullSizeBytes_weightIsZero() {
    BinItem item = new TableSizeBinItem().fromOpAndStats(op(), statsWithSize(null));
    assertThat(item.getWeight()).isEqualTo(0L);
  }

  @Test
  void emptyInstance_doesNotShareStateWithPopulated() {
    TableSizeBinItem empty = new TableSizeBinItem();
    BinItem populated = empty.fromOpAndStats(op(), statsWithSize(7L));

    assertThat(empty.getWeight()).isEqualTo(0L);
    assertThat(populated.getWeight()).isEqualTo(7L);
  }
}
