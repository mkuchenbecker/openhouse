package com.linkedin.openhouse.optimizer.api.controller;

import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.PatchTableOperationRequest;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.service.OptimizerDataService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
   * Transition an operation to SUCCESS or FAILED. Called by the Spark job after completing work for
   * a single table within a batch. Returns 404 if the operation does not exist.
   */
  @PatchMapping("/{id}")
  public ResponseEntity<TableOperationsDto> patchTableOperation(
      @PathVariable String id, @RequestBody PatchTableOperationRequest request) {
    return service
        .patchTableOperation(id, request)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /**
   * Fetch a single operation row by its ID, regardless of status. Returns 404 if not found. Used by
   * the smoke test and monitoring to poll for SUCCESS after a Spark job completes.
   */
  @GetMapping("/{id}")
  public ResponseEntity<TableOperationsDto> getTableOperation(@PathVariable String id) {
    return service
        .getTableOperation(id)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
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
