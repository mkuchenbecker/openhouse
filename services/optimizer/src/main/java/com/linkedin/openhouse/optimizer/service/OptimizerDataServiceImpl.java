package com.linkedin.openhouse.optimizer.service;

import com.linkedin.openhouse.optimizer.api.mapper.OptimizerMapper;
import com.linkedin.openhouse.optimizer.api.model.OperationStatus;
import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.api.model.TableStats;
import com.linkedin.openhouse.optimizer.api.model.TableStatsDto;
import com.linkedin.openhouse.optimizer.entity.TableOperationsHistoryRow;
import com.linkedin.openhouse.optimizer.entity.TableOperationsRow;
import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import com.linkedin.openhouse.optimizer.repository.TableOperationsHistoryRepository;
import com.linkedin.openhouse.optimizer.repository.TableOperationsRepository;
import com.linkedin.openhouse.optimizer.repository.TableStatsRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link OptimizerDataService}. */
@Service
@RequiredArgsConstructor
public class OptimizerDataServiceImpl implements OptimizerDataService {

  private final TableStatsRepository statsRepository;
  private final TableOperationsRepository operationsRepository;
  private final TableOperationsHistoryRepository historyRepository;
  private final OptimizerMapper mapper;

  // --- TableStats ---

  @Override
  public Optional<TableStatsDto> getTableStats(String databaseName, String tableName) {
    return statsRepository.find(databaseName, tableName).map(mapper::toDto);
  }

  @Override
  @Transactional
  public TableStatsDto upsertTableStats(TableStatsDto dto) {
    TableStatsRow row =
        statsRepository
            .find(dto.getDatabaseName(), dto.getTableName())
            .map(existing -> mergeStats(existing, dto))
            .orElse(
                TableStatsRow.builder()
                    .tableUuid(dto.getTableUuid())
                    .databaseName(dto.getDatabaseName())
                    .tableName(dto.getTableName())
                    .stats(dto.getStats())
                    .build());
    return mapper.toDto(statsRepository.save(row));
  }

  /**
   * Merge incoming stats into an existing row. Delta fields are accumulated (added to the existing
   * total); snapshot fields are overwritten with the incoming values.
   */
  private TableStatsRow mergeStats(TableStatsRow existing, TableStatsDto incoming) {
    TableStats prev = existing.getStats() != null ? existing.getStats() : new TableStats();
    TableStats next = incoming.getStats() != null ? incoming.getStats() : new TableStats();

    TableStats merged =
        TableStats.builder()
            // Snapshot fields — overwrite with incoming values
            .clusterId(next.getClusterId())
            .tableVersion(next.getTableVersion())
            .tableLocation(next.getTableLocation())
            .numSnapshots(next.getNumSnapshots())
            .tableSizeBytes(next.getTableSizeBytes())
            // Delta fields — accumulate
            .numFilesAdded(accumulate(prev.getNumFilesAdded(), next.getNumFilesAdded()))
            .numFilesDeleted(accumulate(prev.getNumFilesDeleted(), next.getNumFilesDeleted()))
            .build();

    return existing.toBuilder().stats(merged).build();
  }

  private long accumulate(Long existing, Long incoming) {
    return (existing != null ? existing : 0L) + (incoming != null ? incoming : 0L);
  }

  // --- TableOperations ---

  @Override
  public List<TableOperationsDto> getAllTableOperations(OperationType operationType) {
    return operationsRepository.findAll().stream()
        .filter(r -> operationType == null || operationType.equals(r.getOperationType()))
        .map(mapper::toDto)
        .collect(Collectors.toList());
  }

  @Override
  @Transactional
  public TableOperationsDto upsertTableOperation(
      String databaseName, String tableName, OperationType operationType, TableOperationsDto dto) {
    Instant now = Instant.now();
    TableOperationsRow row =
        operationsRepository
            .findByDatabaseNameAndTableNameAndOperationType(databaseName, tableName, operationType)
            .map(existing -> existing.toBuilder().metrics(dto.getMetrics()).build())
            .orElse(
                TableOperationsRow.builder()
                    .id(UUID.randomUUID().toString())
                    .databaseName(databaseName)
                    .tableName(tableName)
                    .operationType(operationType)
                    .status(OperationStatus.PENDING)
                    .createdAt(now)
                    .metrics(dto.getMetrics())
                    .build());
    return mapper.toDto(operationsRepository.save(row));
  }

  // --- TableOperationsHistory ---

  @Override
  @Transactional
  public TableOperationsHistoryDto appendHistory(TableOperationsHistoryDto dto) {
    TableOperationsHistoryRow row =
        TableOperationsHistoryRow.builder()
            .databaseName(dto.getDatabaseName())
            .tableName(dto.getTableName())
            .operationType(dto.getOperationType())
            .submittedAt(dto.getSubmittedAt() != null ? dto.getSubmittedAt() : Instant.now())
            .status(dto.getStatus())
            .jobId(dto.getJobId())
            .result(dto.getResult())
            .build();
    return mapper.toDto(historyRepository.save(row));
  }

  @Override
  public List<TableOperationsHistoryDto> getHistory(
      String databaseName, String tableName, int limit) {
    return historyRepository.find(databaseName, tableName, limit).stream()
        .map(mapper::toDto)
        .collect(Collectors.toList());
  }
}
