package com.linkedin.openhouse.housetables.controller;

import com.linkedin.openhouse.housetables.api.spec.request.UpsertTableStatsRequest;
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
   * Upsert stats for a table.
   *
   * <p>The path variable {@code tableUuid} is the authoritative identity. Snapshot fields are
   * overwritten; delta fields are accumulated across commit events.
   */
  @PutMapping("/{tableUuid}")
  public ResponseEntity<TableStatsDto> upsertTableStats(
      @PathVariable String tableUuid, @RequestBody UpsertTableStatsRequest req) {
    TableStatsDto dto =
        TableStatsDto.builder()
            .tableUuid(tableUuid)
            .databaseId(req.getDatabaseId())
            .tableName(req.getTableName())
            .stats(req.getStats())
            .build();
    return ResponseEntity.ok(service.upsertTableStats(dto));
  }

  /** Return the latest stats for a table, or 404 if no stats have been recorded yet. */
  @GetMapping("/{tableUuid}")
  public ResponseEntity<TableStatsDto> getTableStats(@PathVariable String tableUuid) {
    return service
        .getTableStats(tableUuid)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }
}
