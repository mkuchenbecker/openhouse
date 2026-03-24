package com.linkedin.openhouse.optimizer.api.controller;

import com.linkedin.openhouse.optimizer.api.model.TableStatsDto;
import com.linkedin.openhouse.optimizer.api.model.UpsertTableStatsRequest;
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

/** REST controller for managing per-table stats in the optimizer DB. */
@RestController
@RequestMapping("/v1/table-stats")
@RequiredArgsConstructor
public class TableStatsController {

  private final OptimizerDataService service;

  /**
   * Create or overwrite the stats row for {@code tableUuid}. Called by the Tables Service on every
   * Iceberg commit. Idempotent.
   */
  @PutMapping("/{tableUuid}")
  public ResponseEntity<TableStatsDto> upsertTableStats(
      @PathVariable String tableUuid, @RequestBody UpsertTableStatsRequest request) {
    return ResponseEntity.ok(service.upsertTableStats(tableUuid, request));
  }

  /** Fetch the stats row for {@code tableUuid}. Returns 404 if no stats have been written yet. */
  @GetMapping("/{tableUuid}")
  public ResponseEntity<TableStatsDto> getTableStats(@PathVariable String tableUuid) {
    return service
        .getTableStats(tableUuid)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /**
   * List stats rows matching the given filters. All parameters are optional — omit all to return
   * every row.
   */
  @GetMapping
  public ResponseEntity<List<TableStatsDto>> listTableStats(
      @RequestParam(required = false) String databaseId,
      @RequestParam(required = false) String tableName,
      @RequestParam(required = false) String tableUuid) {
    return ResponseEntity.ok(service.listTableStats(databaseId, tableName, tableUuid));
  }
}
