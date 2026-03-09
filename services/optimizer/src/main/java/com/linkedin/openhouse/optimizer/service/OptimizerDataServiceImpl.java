package com.linkedin.openhouse.optimizer.service;

import com.linkedin.openhouse.optimizer.api.mapper.OptimizerMapper;
import com.linkedin.openhouse.optimizer.api.model.OperationStatus;
import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.entity.TableOperationsHistoryRow;
import com.linkedin.openhouse.optimizer.entity.TableOperationsRow;
import com.linkedin.openhouse.optimizer.repository.TableOperationsHistoryRepository;
import com.linkedin.openhouse.optimizer.repository.TableOperationsRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link OptimizerDataService}. */
@Service
@RequiredArgsConstructor
public class OptimizerDataServiceImpl implements OptimizerDataService {

  private final TableOperationsRepository operationsRepository;
  private final TableOperationsHistoryRepository historyRepository;
  private final OptimizerMapper mapper;

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
