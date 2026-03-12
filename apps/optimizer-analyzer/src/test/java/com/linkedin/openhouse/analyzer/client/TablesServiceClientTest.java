package com.linkedin.openhouse.analyzer.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.analyzer.model.TableSummary;
import com.linkedin.openhouse.tables.client.api.DatabaseApi;
import com.linkedin.openhouse.tables.client.api.TableApi;
import com.linkedin.openhouse.tables.client.model.GetAllDatabasesResponseBody;
import com.linkedin.openhouse.tables.client.model.GetAllTablesResponseBody;
import com.linkedin.openhouse.tables.client.model.GetDatabaseResponseBody;
import com.linkedin.openhouse.tables.client.model.GetTableResponseBody;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class TablesServiceClientTest {

  @Mock private TableApi tableApi;
  @Mock private DatabaseApi databaseApi;

  private TablesServiceClient client;

  @BeforeEach
  void setUp() {
    client = new TablesServiceClient(tableApi, databaseApi, RetryTemplate.defaultInstance());
  }

  @Test
  void getDatabases_returnsDatabaseIds() {
    GetDatabaseResponseBody db1 = mock(GetDatabaseResponseBody.class);
    when(db1.getDatabaseId()).thenReturn("db1");
    GetDatabaseResponseBody db2 = mock(GetDatabaseResponseBody.class);
    when(db2.getDatabaseId()).thenReturn("db2");

    GetAllDatabasesResponseBody response = mock(GetAllDatabasesResponseBody.class);
    when(response.getResults()).thenReturn(Arrays.asList(db1, db2));
    when(databaseApi.getAllDatabasesV1()).thenReturn(Mono.just(response));

    List<String> databases = client.getDatabases();

    assertThat(databases).containsExactly("db1", "db2");
  }

  @Test
  void getDatabases_returnsEmptyList_onError() {
    when(databaseApi.getAllDatabasesV1())
        .thenReturn(Mono.error(new RuntimeException("connection refused")));

    List<String> databases = client.getDatabases();

    assertThat(databases).isEmpty();
  }

  @Test
  void getAllTables_returnsTableSummaryList() {
    // searchTablesV1 returns sparse summaries (tableUUID/tableProperties are null)
    GetTableResponseBody summary1 = mock(GetTableResponseBody.class);
    when(summary1.getTableId()).thenReturn("t1");
    GetTableResponseBody summary2 = mock(GetTableResponseBody.class);
    when(summary2.getTableId()).thenReturn("t2");

    GetAllTablesResponseBody response = mock(GetAllTablesResponseBody.class);
    when(response.getResults()).thenReturn(Arrays.asList(summary1, summary2));
    when(tableApi.searchTablesV1("db1")).thenReturn(Mono.just(response));

    // getTableV1 returns full detail objects
    GetTableResponseBody detail1 = mock(GetTableResponseBody.class);
    when(detail1.getTableUUID()).thenReturn("uuid-1");
    when(detail1.getDatabaseId()).thenReturn("db1");
    when(detail1.getTableId()).thenReturn("t1");
    GetTableResponseBody detail2 = mock(GetTableResponseBody.class);
    when(detail2.getTableUUID()).thenReturn("uuid-2");
    when(detail2.getDatabaseId()).thenReturn("db1");
    when(detail2.getTableId()).thenReturn("t2");
    when(tableApi.getTableV1("db1", "t1")).thenReturn(Mono.just(detail1));
    when(tableApi.getTableV1("db1", "t2")).thenReturn(Mono.just(detail2));

    List<TableSummary> tables = client.getAllTables("db1");

    assertThat(tables).hasSize(2);
    assertThat(tables).extracting(TableSummary::getTableId).containsExactly("t1", "t2");
    assertThat(tables).extracting(TableSummary::getTableUuid).containsExactly("uuid-1", "uuid-2");
  }

  @Test
  void getAllTables_returnsEmptyList_onError() {
    when(tableApi.searchTablesV1("db1")).thenReturn(Mono.error(new RuntimeException("timeout")));

    List<TableSummary> tables = client.getAllTables("db1");

    assertThat(tables).isEmpty();
  }
}
