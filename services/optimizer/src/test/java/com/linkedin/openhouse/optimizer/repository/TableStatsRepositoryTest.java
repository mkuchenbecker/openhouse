package com.linkedin.openhouse.optimizer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.api.model.TableStats;
import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TableStatsRepositoryTest {

  @Autowired TableStatsRepository repository;

  @Test
  void saveAndFindByDatabaseAndTable() {
    TableStats stats =
        TableStats.builder().numSnapshots(5).tableSizeBytes(1024L).clusterId("cluster-1").build();

    TableStatsRow row =
        TableStatsRow.builder()
            .tableUuid("uuid-001")
            .databaseName("db1")
            .tableName("tbl1")
            .stats(stats)
            .build();

    repository.save(row);

    Optional<TableStatsRow> found = repository.find("db1", "tbl1");
    assertThat(found).isPresent();
    assertThat(found.get().getTableUuid()).isEqualTo("uuid-001");
    assertThat(found.get().getStats().getNumSnapshots()).isEqualTo(5);
    assertThat(found.get().getStats().getTableSizeBytes()).isEqualTo(1024L);
  }

  @Test
  void find_returnsEmpty_whenNotFound() {
    Optional<TableStatsRow> found = repository.find("ghost", "missing");
    assertThat(found).isEmpty();
  }
}
