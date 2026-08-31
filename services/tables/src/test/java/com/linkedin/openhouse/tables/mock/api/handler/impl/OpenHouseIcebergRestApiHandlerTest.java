package com.linkedin.openhouse.tables.mock.api.handler.impl;

import static com.linkedin.openhouse.tables.api.handler.IcebergRestApiHandler.ICEBERG_REST_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.internal.catalog.OpenHouseInternalCatalog;
import com.linkedin.openhouse.tables.api.handler.TablesApiHandler;
import com.linkedin.openhouse.tables.api.handler.impl.IcebergRestTableWriteAdapter;
import com.linkedin.openhouse.tables.api.handler.impl.OpenHouseIcebergRestApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAllTablesResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.generated.iceberg.model.ListTablesResponse;
import java.util.Collections;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class OpenHouseIcebergRestApiHandlerTest {

  @Mock private TablesApiHandler tablesApiHandler;
  @Mock private OpenHouseInternalCatalog openHouseInternalCatalog;
  @Mock private IcebergRestTableWriteAdapter tableWriteAdapter;

  private OpenHouseIcebergRestApiHandler handler;

  @BeforeEach
  void setUp() {
    // @Value defaults do not apply to a directly-constructed properties bean, and depth 0 would
    // make every identifier illegal -- which would pass the 404 assertions below for the wrong
    // reason. Set the shipped depth explicitly.
    ClusterProperties clusterProperties = new ClusterProperties();
    ReflectionTestUtils.setField(clusterProperties, "clusterTablesNamespaceMaxDepth", 1);
    handler =
        new OpenHouseIcebergRestApiHandler(
            tablesApiHandler, openHouseInternalCatalog, tableWriteAdapter, clusterProperties);
  }

  @Test
  void configAdvertisesOnlyImplementedEndpoints() {
    assertThat(handler.getConfig("openhouse").getOverrides())
        .containsEntry("prefix", ICEBERG_REST_PREFIX);
    assertThat(handler.getConfig("openhouse").getEndpoints())
        .containsExactly(
            "GET /v1/{prefix}/namespaces",
            "POST /v1/{prefix}/namespaces",
            "GET /v1/{prefix}/namespaces/{namespace}",
            "HEAD /v1/{prefix}/namespaces/{namespace}",
            "DELETE /v1/{prefix}/namespaces/{namespace}",
            "POST /v1/{prefix}/namespaces/{namespace}/properties",
            "GET /v1/{prefix}/namespaces/{namespace}/tables",
            "POST /v1/{prefix}/namespaces/{namespace}/tables",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/tables/rename",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics");
  }

  /**
   * Registering an existing metadata file is a capability OpenHouse declines, so the route must
   * stay off the advertised list. A client that saw it there would be told the catalog can adopt a
   * file it does not own, and get a 404 from a route that was never generated.
   */
  @Test
  void configDoesNotAdvertiseADeclinedRoute() {
    assertThat(handler.getConfig("openhouse").getEndpoints())
        .doesNotContain("POST /v1/{prefix}/namespaces/{namespace}/register");
  }

  /**
   * The rename route reads the source as an identifier that names a table -- so a name OpenHouse
   * could never hold is a 404 -- and the destination as a namespace that must be able to hold one,
   * which is the same split {@code createTable} makes. Neither reaches the write adapter.
   */
  @Test
  void anIllegalIdentifierOnTheRenameRouteIsANotFound() {
    assertThatThrownBy(
            () ->
                handler.renameTable(
                    ICEBERG_REST_PREFIX,
                    RenameTableRequest.builder()
                        .withSource(TableIdentifier.of("db", "not-a-table"))
                        .withDestination(TableIdentifier.of("db", "t2"))
                        .build()))
        .isInstanceOf(NoSuchTableException.class);
    assertThatThrownBy(
            () ->
                handler.renameTable(
                    ICEBERG_REST_PREFIX,
                    RenameTableRequest.builder()
                        .withSource(TableIdentifier.of("db", "t1"))
                        .withDestination(TableIdentifier.of("non-existing", "t2"))
                        .build()))
        .isInstanceOf(NoSuchNamespaceException.class);
    verifyNoInteractions(tableWriteAdapter);
  }

  /**
   * An empty page token is what every Iceberg Java client since 1.6.0 sends on a first request; it
   * used to be rejected, which made {@code RESTCatalog.listTables()} fail outright.
   */
  @Test
  void anEmptyPageTokenIsTheFirstPage() {
    when(tablesApiHandler.searchTables("db", 0, 1, "tableId", Collections.emptyList(), "undefined"))
        .thenReturn(pageResponse("db", "t1", 0, 1, 1));

    assertThat(handler.listTables(ICEBERG_REST_PREFIX, "db", "", 1).getIdentifiers())
        .containsExactly(TableIdentifier.of("db", "t1"));
  }

  /**
   * An identifier OpenHouse's charset cannot express names a table that cannot exist. That is a
   * 404, not the 400 the service layer's own validator would raise: {@code tableExists} in
   * particular has to answer "no", not "you asked wrongly".
   */
  @Test
  void anIllegalIdentifierOnATableRouteIsANotFound() {
    assertThatThrownBy(() -> handler.tableExists(ICEBERG_REST_PREFIX, "non-existing", "t1"))
        .isInstanceOf(NoSuchTableException.class);
    assertThatThrownBy(() -> handler.tableExists(ICEBERG_REST_PREFIX, "db", "not-a-table"))
        .isInstanceOf(NoSuchTableException.class);
    assertThatThrownBy(
            () ->
                handler.loadTable(
                    ICEBERG_REST_PREFIX, "non-existing", "t1", null, null, "all", null))
        .isInstanceOf(NoSuchTableException.class);
  }

  @Test
  void listTablesUsesOpaquePaginationToken() {
    when(tablesApiHandler.searchTables("db", 0, 1, "tableId", Collections.emptyList(), "undefined"))
        .thenReturn(pageResponse("db", "t1", 0, 1, 2));

    ListTablesResponse firstPage = handler.listTables(ICEBERG_REST_PREFIX, "db", null, 1);

    assertThat(firstPage.getIdentifiers()).containsExactly(TableIdentifier.of("db", "t1"));
    assertThat(firstPage.getNextPageToken()).isNotBlank();

    when(tablesApiHandler.searchTables("db", 1, 1, "tableId", Collections.emptyList(), "undefined"))
        .thenReturn(pageResponse("db", "t2", 1, 1, 2));
    ListTablesResponse secondPage =
        handler.listTables(ICEBERG_REST_PREFIX, "db", firstPage.getNextPageToken(), null);

    assertThat(secondPage.getIdentifiers()).containsExactly(TableIdentifier.of("db", "t2"));
    assertThat(secondPage.getNextPageToken()).isNull();
  }

  @Test
  void rejectsInvalidPrefixAndPageToken() {
    assertThatThrownBy(() -> handler.listTables("other", "db", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prefix");
    assertThatThrownBy(() -> handler.listTables(ICEBERG_REST_PREFIX, "db", "invalid", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("page token");
    // Every route checks the prefix, including the two that do no other work of their own: the
    // metrics route discards its body, so the prefix check is the only thing standing between a
    // request on a prefix this catalog does not serve and a 204 saying it was accepted.
    assertThatThrownBy(
            () ->
                handler.renameTable(
                    "other",
                    RenameTableRequest.builder()
                        .withSource(TableIdentifier.of("db", "t1"))
                        .withDestination(TableIdentifier.of("db", "t2"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prefix");
    assertThatThrownBy(() -> handler.reportMetrics("other", "db", "t1", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prefix");
  }

  /** The metrics route accepts a report and keeps nothing: no service, no catalog, no write. */
  @Test
  void aMetricsReportIsDiscardedWithoutTouchingAnything() {
    handler.reportMetrics(ICEBERG_REST_PREFIX, "db", "t1", null);

    verifyNoInteractions(tablesApiHandler, openHouseInternalCatalog, tableWriteAdapter);
  }

  @Test
  void rejectsUnsupportedSnapshotProjection() {
    assertThatThrownBy(
            () -> handler.loadTable(ICEBERG_REST_PREFIX, "db", "t1", null, null, "refs", null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("snapshots=refs");
  }

  @Test
  void loadTableReusesExistingReadHandlerBeforeCatalogLoad() {
    when(tablesApiHandler.getTable(eq("db"), eq("t1"), eq("undefined")))
        .thenReturn(
            ApiResponse.<GetTableResponseBody>builder()
                .httpStatus(HttpStatus.OK)
                .responseBody(GetTableResponseBody.builder().databaseId("db").tableId("t1").build())
                .build());
    TableMetadata metadata = testMetadata("hdfs://warehouse/db/t1");
    TableOperations operations = org.mockito.Mockito.mock(TableOperations.class);
    when(operations.current()).thenReturn(metadata);
    when(openHouseInternalCatalog.loadTable(TableIdentifier.of("db", "t1")))
        .thenReturn(new BaseTable(operations, "openhouse.db.t1"));

    assertThat(
            handler
                .loadTable(ICEBERG_REST_PREFIX, "db", "t1", null, null, "all", null)
                .tableMetadata()
                .location())
        .isEqualTo(metadata.location());
    verify(tablesApiHandler).getTable("db", "t1", "undefined");
  }

  private static ApiResponse<GetAllTablesResponseBody> pageResponse(
      String databaseId, String tableId, int page, int size, int total) {
    GetTableResponseBody table =
        GetTableResponseBody.builder().databaseId(databaseId).tableId(tableId).build();
    return ApiResponse.<GetAllTablesResponseBody>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            GetAllTablesResponseBody.builder()
                .pageResults(
                    new PageImpl<>(
                        Collections.singletonList(table), PageRequest.of(page, size), total))
                .build())
        .build();
  }

  private static TableMetadata testMetadata(String location) {
    Schema schema = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    return TableMetadata.newTableMetadata(
        schema,
        PartitionSpec.unpartitioned(),
        SortOrder.unsorted(),
        location,
        Collections.emptyMap());
  }
}
