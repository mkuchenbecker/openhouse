package com.linkedin.openhouse.optimizer.api.controller;

import com.linkedin.openhouse.optimizer.api.model.TableStatsDto;
import com.linkedin.openhouse.optimizer.service.OptimizerDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for {@code table_stats}. */
@RestController
@RequestMapping("/v1/table-stats")
@RequiredArgsConstructor
public class TableStatsController {

  private final OptimizerDataService service;

  /**
   * Upsert stats for a table. Called by the Ingestion pipeline after each CommitEvent.
   *
   * <p>The request body must include {@code tableUuid} so the row can be keyed correctly on first
   * insert. On subsequent calls for the same {@code (databaseName, tableName)} the UUID is ignored
   * and the existing row is updated in place.
   */
  @PutMapping("/{databaseName}/{tableName}")
  public ResponseEntity<TableStatsDto> upsertTableStats(
      @PathVariable String databaseName,
      @PathVariable String tableName,
      @RequestBody TableStatsDto dto) {
    dto.setDatabaseName(databaseName);
    dto.setTableName(tableName);
    return ResponseEntity.ok(service.upsertTableStats(dto));
  }

  /** Return the latest stats for a table, or 404 if no stats have been recorded yet. */
  @GetMapping("/{databaseName}/{tableName}")
  public ResponseEntity<TableStatsDto> getTableStats(
      @PathVariable String databaseName, @PathVariable String tableName) {
    return service
        .getTableStats(databaseName, tableName)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }
}
