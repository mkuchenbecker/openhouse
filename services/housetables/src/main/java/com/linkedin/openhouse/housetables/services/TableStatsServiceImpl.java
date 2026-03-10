package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.housetables.dto.mapper.TableStatsMapper;
import com.linkedin.openhouse.housetables.dto.model.TableStats;
import com.linkedin.openhouse.housetables.dto.model.TableStatsDto;
import com.linkedin.openhouse.housetables.model.TableStatsRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.TableStatsHtsJdbcRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link TableStatsService}. */
@Component
@RequiredArgsConstructor
public class TableStatsServiceImpl implements TableStatsService {

  private final TableStatsHtsJdbcRepository repository;
  private final TableStatsMapper mapper;

  @Override
  public Optional<TableStatsDto> getTableStats(String tableUuid) {
    return repository.findById(tableUuid).map(mapper::toDto);
  }

  @Override
  @Transactional
  // TODO: catch OptimisticLockException and retry — high-frequency write paths can collide
  public TableStatsDto upsertTableStats(TableStatsDto dto) {
    Optional<TableStatsRow> existing = repository.findById(dto.getTableUuid());
    TableStatsRow row;
    if (existing.isPresent()) {
      TableStatsRow e = existing.get();
      row =
          e.toBuilder()
              .databaseId(dto.getDatabaseId())
              .tableName(dto.getTableName())
              .stats(merge(e.getStats(), dto.getStats()))
              .build();
    } else {
      row = mapper.toRow(dto);
    }
    return mapper.toDto(repository.save(row));
  }

  private TableStats merge(TableStats existing, TableStats incoming) {
    if (existing == null) {
      return incoming;
    }
    if (incoming == null) {
      return existing;
    }
    TableStats.CommitDelta existingDelta =
        existing.getDelta() != null
            ? existing.getDelta()
            : TableStats.CommitDelta.builder().build();
    TableStats.CommitDelta incomingDelta =
        incoming.getDelta() != null
            ? incoming.getDelta()
            : TableStats.CommitDelta.builder().build();
    return TableStats.builder()
        .snapshot(incoming.getSnapshot())
        .delta(
            TableStats.CommitDelta.builder()
                .numFilesAdded(
                    accumulate(existingDelta.getNumFilesAdded(), incomingDelta.getNumFilesAdded()))
                .numFilesDeleted(
                    accumulate(
                        existingDelta.getNumFilesDeleted(), incomingDelta.getNumFilesDeleted()))
                .build())
        .build();
  }

  private long accumulate(Long existing, Long incoming) {
    return (existing != null ? existing : 0L) + (incoming != null ? incoming : 0L);
  }
}
