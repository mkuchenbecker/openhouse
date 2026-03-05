package com.linkedin.openhouse.optimizer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import java.util.HashMap;
import java.util.Map;
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
    Map<String, Object> stats = new HashMap<>();
    stats.put("num_snapshots", 5);
    stats.put("table_size_bytes", 1024L);

    TableStatsRow row =
        TableStatsRow.builder()
            .tableUuid("uuid-001")
            .databaseName("db1")
            .tableName("tbl1")
            .stats(stats)
            .build();

    repository.save(row);

    Optional<TableStatsRow> found = repository.findByDatabaseNameAndTableName("db1", "tbl1");
    assertThat(found).isPresent();
    assertThat(found.get().getTableUuid()).isEqualTo("uuid-001");
    assertThat(found.get().getStats()).containsKey("num_snapshots");
  }

  @Test
  void findByDatabaseAndTable_returnsEmpty_whenNotFound() {
    Optional<TableStatsRow> found = repository.findByDatabaseNameAndTableName("ghost", "missing");
    assertThat(found).isEmpty();
  }
}
