package com.linkedin.openhouse.optimizer.api.controller;

import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.service.OptimizerDataService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for {@code table_operations}. */
@RestController
@RequestMapping("/v1/table-operations")
@RequiredArgsConstructor
public class TableOperationsController {

  private final OptimizerDataService service;

  /**
   * Upsert an operation recommendation. Called by the Analyzer after each analysis pass.
   *
   * <p>Creates the row on first call; on subsequent calls for the same {@code (databaseName,
   * tableName, operationType)} and updates the metrics snapshot.
   */
  @PutMapping("/{databaseName}/{tableName}/{operationType}")
  public ResponseEntity<TableOperationsDto> upsertTableOperation(
      @PathVariable String databaseName,
      @PathVariable String tableName,
      @PathVariable String operationType,
      @RequestBody TableOperationsDto dto) {
    dto.setDatabaseName(databaseName);
    dto.setTableName(tableName);
    dto.setOperationType(operationType);
    return ResponseEntity.ok(service.upsertTableOperation(dto));
  }

  /**
   * List all operation recommendations. The Scheduler calls this for bin-packing.
   *
   * @param operationType optional filter; omit to return all operation types
   */
  @GetMapping
  public ResponseEntity<List<TableOperationsDto>> listTableOperations(
      @RequestParam(required = false) String operationType) {
    return ResponseEntity.ok(service.getAllTableOperations(operationType));
  }
}
