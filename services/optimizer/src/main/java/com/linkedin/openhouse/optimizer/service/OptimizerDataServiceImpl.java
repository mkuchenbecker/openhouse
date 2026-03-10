package com.linkedin.openhouse.optimizer.service;

import com.linkedin.openhouse.optimizer.api.mapper.OptimizerMapper;
import com.linkedin.openhouse.optimizer.api.model.OperationStatus;
import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.api.model.UpsertTableOperationsRequest;
import com.linkedin.openhouse.optimizer.entity.TableOperationsHistoryRow;
import com.linkedin.openhouse.optimizer.entity.TableOperationsRow;
import com.linkedin.openhouse.optimizer.repository.TableOperationsHistoryRepository;
import com.linkedin.openhouse.optimizer.repository.TableOperationsRepository;
import java.time.Instant;
import java.util.List;
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
    if (operationType != null) {
      return operationsRepository.find(operationType).stream()
          .map(mapper::toDto)
          .collect(Collectors.toList());
    }
    return operationsRepository.findAll().stream()
        .filter(
            r ->
                r.getStatus() == OperationStatus.PENDING
                    || r.getStatus() == OperationStatus.SCHEDULED)
        .map(mapper::toDto)
        .collect(Collectors.toList());
  }

  @Override
  @Transactional
  public TableOperationsDto upsertTableOperation(String id, UpsertTableOperationsRequest request) {
    TableOperationsRow row =
        operationsRepository
            .findById(id)
            .map(existing -> existing.toBuilder().metrics(request.getMetrics()).build())
            .orElse(
                TableOperationsRow.builder()
                    .id(id)
                    .tableUuid(request.getTableUuid())
                    .databaseName(request.getDatabaseName())
                    .tableName(request.getTableName())
                    .operationType(request.getOperationType())
                    .status(OperationStatus.PENDING)
                    .createdAt(Instant.now())
                    .metrics(request.getMetrics())
                    .build());
    return mapper.toDto(operationsRepository.save(row));
  }

  // --- TableOperationsHistory ---

  @Override
  @Transactional
  public TableOperationsHistoryDto appendHistory(TableOperationsHistoryDto dto) {
    TableOperationsHistoryRow row =
        TableOperationsHistoryRow.builder()
            .tableUuid(dto.getTableUuid())
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
  public List<TableOperationsHistoryDto> getHistory(String tableUuid, int limit) {
    return historyRepository.find(tableUuid, limit).stream()
        .map(mapper::toDto)
        .collect(Collectors.toList());
  }
}
