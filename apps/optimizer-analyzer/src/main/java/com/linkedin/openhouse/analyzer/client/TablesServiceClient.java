package com.linkedin.openhouse.analyzer.client;

import com.linkedin.openhouse.tables.client.api.DatabaseApi;
import com.linkedin.openhouse.tables.client.api.TableApi;
import com.linkedin.openhouse.tables.client.model.GetAllDatabasesResponseBody;
import com.linkedin.openhouse.tables.client.model.GetAllTablesResponseBody;
import com.linkedin.openhouse.tables.client.model.GetDatabaseResponseBody;
import com.linkedin.openhouse.tables.client.model.GetTableResponseBody;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;

/** Thin client for enumerating databases and tables from the Tables Service. */
@Slf4j
@RequiredArgsConstructor
public class TablesServiceClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableApi tableApi;
  private final DatabaseApi databaseApi;
  private final RetryTemplate retryTemplate;

  /** Returns all database IDs registered in the Tables Service. */
  public List<String> getDatabases() {
    try {
      return retryTemplate.execute(
          ctx -> {
            GetAllDatabasesResponseBody response = databaseApi.getAllDatabasesV1().block(TIMEOUT);
            return Optional.ofNullable(response == null ? null : response.getResults())
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .map(GetDatabaseResponseBody::getDatabaseId)
                .collect(Collectors.toList());
          });
    } catch (Exception e) {
      log.error("Failed to fetch databases", e);
      return Collections.emptyList();
    }
  }

  /**
   * Returns all tables for the given database with full details (UUID, properties).
   *
   * <p>The search endpoint returns only sparse table summaries (tableUUID and tableProperties are
   * null), so each table is fetched individually via the single-table GET endpoint.
   */
  public List<GetTableResponseBody> getAllTables(String databaseId) {
    try {
      List<GetTableResponseBody> summaries =
          retryTemplate.execute(
              ctx -> {
                GetAllTablesResponseBody response =
                    tableApi.searchTablesV1(databaseId).block(TIMEOUT);
                return Optional.ofNullable(response == null ? null : response.getResults())
                    .orElse(Collections.emptyList());
              });
      List<GetTableResponseBody> full = new java.util.ArrayList<>();
      for (GetTableResponseBody summary : summaries) {
        try {
          GetTableResponseBody detail =
              retryTemplate.execute(
                  ctx -> tableApi.getTableV1(databaseId, summary.getTableId()).block(TIMEOUT));
          if (detail != null) {
            full.add(detail);
          }
        } catch (Exception e) {
          log.warn("Failed to fetch details for table {}.{}", databaseId, summary.getTableId(), e);
        }
      }
      return full;
    } catch (Exception e) {
      log.error("Failed to fetch tables for database {}", databaseId, e);
      return Collections.emptyList();
    }
  }
}
