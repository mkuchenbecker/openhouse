package com.linkedin.openhouse.housetables.e2e.tablestats;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.housetables.e2e.SpringH2HtsApplication;
import com.linkedin.openhouse.housetables.model.TableStatsRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.TableStatsHtsJdbcRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(classes = SpringH2HtsApplication.class)
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class TableStatsRepositoryTest {

  @Autowired TableStatsHtsJdbcRepository repository;

  @AfterEach
  public void tearDown() {
    repository.deleteAll();
  }

  @Test
  public void testSaveAndFindByDatabaseAndTable() {
    TableStatsRow row =
        TableStatsRow.builder()
            .databaseId("db1")
            .tableId("tbl1")
            .tableUuid("uuid-001")
            .clusterId("cluster-1")
            .numSnapshots(5)
            .tableSizeBytes(1024L)
            .numFilesAdded(10L)
            .numFilesDeleted(2L)
            .build();

    repository.save(row);

    Optional<TableStatsRow> found = repository.findByDatabaseIdAndTableId("db1", "tbl1");
    assertThat(found).isPresent();
    assertThat(found.get().getTableUuid()).isEqualTo("uuid-001");
    assertThat(found.get().getNumSnapshots()).isEqualTo(5);
    assertThat(found.get().getNumFilesAdded()).isEqualTo(10L);
  }

  @Test
  public void testFindReturnsEmpty_whenNotFound() {
    Optional<TableStatsRow> found = repository.findByDatabaseIdAndTableId("ghost", "missing");
    assertThat(found).isEmpty();
  }

  @Test
  public void testUpdate_incrementsVersion() {
    TableStatsRow row =
        TableStatsRow.builder().databaseId("db1").tableId("tbl2").tableSizeBytes(512L).build();

    TableStatsRow saved = repository.save(row);
    assertThat(saved.getVersion()).isEqualTo(0L);

    TableStatsRow updated = saved.toBuilder().tableSizeBytes(2048L).build();
    TableStatsRow resaved = repository.save(updated);
    assertThat(resaved.getVersion()).isGreaterThan(0L);
    assertThat(resaved.getTableSizeBytes()).isEqualTo(2048L);
  }
}
