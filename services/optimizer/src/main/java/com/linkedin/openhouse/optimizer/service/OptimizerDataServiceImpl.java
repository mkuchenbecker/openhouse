package com.linkedin.openhouse.optimizer.service;

import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.api.model.TableStatsDto;
import com.linkedin.openhouse.optimizer.entity.OperationStatus;
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

  // --- TableStats ---

  @Override
  public Optional<TableStatsDto> getTableStats(String databaseName, String tableName) {
    return statsRepository.findByDatabaseNameAndTableName(databaseName, tableName).map(this::toDto);
  }

  @Override
  @Transactional
  public TableStatsDto upsertTableStats(TableStatsDto dto) {
    TableStatsRow row =
        statsRepository
            .findByDatabaseNameAndTableName(dto.getDatabaseName(), dto.getTableName())
            .map(existing -> existing.toBuilder().stats(dto.getStats()).build())
            .orElse(
                TableStatsRow.builder()
                    .tableUuid(dto.getTableUuid())
                    .databaseName(dto.getDatabaseName())
                    .tableName(dto.getTableName())
                    .stats(dto.getStats())
                    .build());
    return toDto(statsRepository.save(row));
  }

  // --- TableOperations ---

  @Override
  public List<TableOperationsDto> getAllTableOperations(String operationType) {
    if (operationType != null) {
      return operationsRepository.findByOperationType(operationType).stream()
          .map(this::toDto)
          .collect(Collectors.toList());
    }
    return operationsRepository.findAll().stream().map(this::toDto).collect(Collectors.toList());
  }

  @Override
  @Transactional
  public TableOperationsDto upsertTableOperation(TableOperationsDto dto) {
    Instant now = Instant.now();
    TableOperationsRow row =
        operationsRepository
            .findByDatabaseNameAndTableNameAndOperationType(
                dto.getDatabaseName(), dto.getTableName(), dto.getOperationType())
            .map(existing -> existing.toBuilder().metrics(dto.getMetrics()).build())
            .orElse(
                TableOperationsRow.builder()
                    .id(UUID.randomUUID().toString())
                    .databaseName(dto.getDatabaseName())
                    .tableName(dto.getTableName())
                    .operationType(dto.getOperationType())
                    .status(OperationStatus.PENDING.name())
                    .createdAt(now)
                    .metrics(dto.getMetrics())
                    .build());
    return toDto(operationsRepository.save(row));
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
    return toDto(historyRepository.save(row));
  }

  @Override
  public List<TableOperationsHistoryDto> getHistory(String databaseName, String tableName) {
    return historyRepository
        .findByDatabaseNameAndTableNameOrderBySubmittedAtDesc(databaseName, tableName).stream()
        .map(this::toDto)
        .collect(Collectors.toList());
  }

  // --- Mappers ---

  private TableStatsDto toDto(TableStatsRow row) {
    return TableStatsDto.builder()
        .tableUuid(row.getTableUuid())
        .databaseName(row.getDatabaseName())
        .tableName(row.getTableName())
        .lastUpdatedAt(row.getLastUpdatedAt())
        .stats(row.getStats())
        .build();
  }

  private TableOperationsDto toDto(TableOperationsRow row) {
    return TableOperationsDto.builder()
        .id(row.getId())
        .databaseName(row.getDatabaseName())
        .tableName(row.getTableName())
        .operationType(row.getOperationType())
        .status(row.getStatus())
        .createdAt(row.getCreatedAt())
        .scheduledAt(row.getScheduledAt())
        .metrics(row.getMetrics())
        .build();
  }

  private TableOperationsHistoryDto toDto(TableOperationsHistoryRow row) {
    return TableOperationsHistoryDto.builder()
        .id(row.getId())
        .databaseName(row.getDatabaseName())
        .tableName(row.getTableName())
        .operationType(row.getOperationType())
        .submittedAt(row.getSubmittedAt())
        .status(row.getStatus())
        .jobId(row.getJobId())
        .result(row.getResult())
        .build();
  }
}
