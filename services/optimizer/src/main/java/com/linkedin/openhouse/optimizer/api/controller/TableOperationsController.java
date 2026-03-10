package com.linkedin.openhouse.optimizer.api.controller;

import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.UpsertTableOperationsRequest;
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
   * Create or update an operation recommendation.
   *
   * <p>{@code id} is a client-generated UUID. On first call a new row is created with {@code
   * status=PENDING}. On subsequent calls with the same {@code id} the metrics snapshot is
   * refreshed. Idempotent: retrying with the same {@code id} is safe.
   */
  @PutMapping("/{id}")
  public ResponseEntity<TableOperationsDto> upsertTableOperation(
      @PathVariable String id, @RequestBody UpsertTableOperationsRequest request) {
    return ResponseEntity.ok(service.upsertTableOperation(id, request));
  }

  /**
   * List all active (PENDING or SCHEDULED) operation recommendations.
   *
   * @param operationType optional filter; omit to return all operation types
   */
  @GetMapping
  public ResponseEntity<List<TableOperationsDto>> listTableOperations(
      @RequestParam(required = false) OperationType operationType) {
    return ResponseEntity.ok(service.getAllTableOperations(operationType));
  }
}
