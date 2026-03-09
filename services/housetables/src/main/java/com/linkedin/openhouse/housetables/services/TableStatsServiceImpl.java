package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.housetables.dto.mapper.TableStatsMapper;
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
  public Optional<TableStatsDto> getTableStats(String databaseId, String tableId) {
    return repository.findByDatabaseIdAndTableId(databaseId, tableId).map(mapper::toDto);
  }

  @Override
  @Transactional
  public TableStatsDto upsertTableStats(TableStatsDto dto) {
    Optional<TableStatsRow> existing =
        repository.findByDatabaseIdAndTableId(dto.getDatabaseId(), dto.getTableId());
    TableStatsRow row;
    if (existing.isPresent()) {
      TableStatsRow e = existing.get();
      row =
          e.toBuilder()
              // Snapshot fields — overwrite with incoming values
              .tableUuid(dto.getTableUuid())
              .clusterId(dto.getClusterId())
              .tableVersion(dto.getTableVersion())
              .tableLocation(dto.getTableLocation())
              .numSnapshots(dto.getNumSnapshots())
              .tableSizeBytes(dto.getTableSizeBytes())
              // Delta fields — accumulate
              .numFilesAdded(accumulate(e.getNumFilesAdded(), dto.getNumFilesAdded()))
              .numFilesDeleted(accumulate(e.getNumFilesDeleted(), dto.getNumFilesDeleted()))
              .build();
    } else {
      row = mapper.toRow(dto);
    }
    return mapper.toDto(repository.save(row));
  }

  private long accumulate(Long existing, Long incoming) {
    return (existing != null ? existing : 0L) + (incoming != null ? incoming : 0L);
  }
}
