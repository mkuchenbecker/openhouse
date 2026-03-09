package com.linkedin.openhouse.housetables.controller;

import com.linkedin.openhouse.housetables.dto.model.TableStatsDto;
import com.linkedin.openhouse.housetables.services.TableStatsService;
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
@RequestMapping("/v1/hts/table-stats")
@RequiredArgsConstructor
public class TableStatsController {

  private final TableStatsService service;

  /**
   * Upsert stats for a table. Called by the ingestion pipeline after each commit event.
   *
   * <p>The path variables are authoritative for identity. Delta fields ({@code numFilesAdded},
   * {@code numFilesDeleted}) are accumulated; all other fields are overwritten.
   */
  @PutMapping("/{databaseId}/{tableId}")
  public ResponseEntity<TableStatsDto> upsertTableStats(
      @PathVariable String databaseId,
      @PathVariable String tableId,
      @RequestBody TableStatsDto dto) {
    dto.setDatabaseId(databaseId);
    dto.setTableId(tableId);
    return ResponseEntity.ok(service.upsertTableStats(dto));
  }

  /** Return the latest stats for a table, or 404 if no stats have been recorded yet. */
  @GetMapping("/{databaseId}/{tableId}")
  public ResponseEntity<TableStatsDto> getTableStats(
      @PathVariable String databaseId, @PathVariable String tableId) {
    return service
        .getTableStats(databaseId, tableId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }
}
