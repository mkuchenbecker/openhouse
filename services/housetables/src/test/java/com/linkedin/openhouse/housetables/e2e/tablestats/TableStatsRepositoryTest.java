package com.linkedin.openhouse.housetables.e2e.tablestats;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.housetables.dto.model.TableStats;
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
  public void testSaveAndFindByUuid() {
    TableStats stats =
        TableStats.builder()
            .snapshot(TableStats.SnapshotMetrics.builder().tableSizeBytes(1024L).build())
            .delta(TableStats.CommitDelta.builder().numFilesAdded(10L).numFilesDeleted(2L).build())
            .build();

    TableStatsRow row =
        TableStatsRow.builder()
            .tableUuid("uuid-001")
            .databaseId("db1")
            .tableName("tbl1")
            .stats(stats)
            .build();

    repository.save(row);

    Optional<TableStatsRow> found = repository.findById("uuid-001");
    assertThat(found).isPresent();
    assertThat(found.get().getDatabaseId()).isEqualTo("db1");
    assertThat(found.get().getTableName()).isEqualTo("tbl1");
    assertThat(found.get().getStats().getSnapshot().getTableSizeBytes()).isEqualTo(1024L);
    assertThat(found.get().getStats().getDelta().getNumFilesAdded()).isEqualTo(10L);
  }

  @Test
  public void testFindReturnsEmpty_whenNotFound() {
    Optional<TableStatsRow> found = repository.findById("unknown-uuid");
    assertThat(found).isEmpty();
  }

  @Test
  public void testUpdate_incrementsVersion() {
    TableStatsRow row =
        TableStatsRow.builder()
            .tableUuid("uuid-002")
            .databaseId("db1")
            .tableName("tbl2")
            .stats(
                TableStats.builder()
                    .snapshot(TableStats.SnapshotMetrics.builder().tableSizeBytes(512L).build())
                    .build())
            .build();

    TableStatsRow saved = repository.save(row);
    assertThat(saved.getVersion()).isEqualTo(0L);

    TableStatsRow updated =
        saved
            .toBuilder()
            .stats(
                TableStats.builder()
                    .snapshot(TableStats.SnapshotMetrics.builder().tableSizeBytes(2048L).build())
                    .build())
            .build();
    TableStatsRow resaved = repository.save(updated);
    assertThat(resaved.getVersion()).isGreaterThan(0L);
    assertThat(resaved.getStats().getSnapshot().getTableSizeBytes()).isEqualTo(2048L);
  }

  @Test
  public void testStatsArePersisted_whenNull() {
    TableStatsRow row =
        TableStatsRow.builder().tableUuid("uuid-003").databaseId("db1").tableName("tbl3").build();

    repository.save(row);

    Optional<TableStatsRow> found = repository.findById("uuid-003");
    assertThat(found).isPresent();
    assertThat(found.get().getStats()).isNull();
  }
}
