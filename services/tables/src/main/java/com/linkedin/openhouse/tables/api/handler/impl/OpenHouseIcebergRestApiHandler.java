package com.linkedin.openhouse.tables.api.handler.impl;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.common.exception.NoSuchUserTableException;
import com.linkedin.openhouse.internal.catalog.OpenHouseInternalCatalog;
import com.linkedin.openhouse.tables.api.handler.IcebergRestApiHandler;
import com.linkedin.openhouse.tables.api.handler.TablesApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAllTablesResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.generated.iceberg.IcebergRestOpenHouseSupport;
import com.linkedin.openhouse.tables.generated.iceberg.model.CatalogConfig;
import com.linkedin.openhouse.tables.generated.iceberg.model.ListTablesResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.ReportMetricsRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** Default Iceberg REST adapter backed by existing OpenHouse API handlers and catalog behavior. */
@Component
@ConditionalOnProperty(value = "cluster.tables.iceberg-rest.enabled", havingValue = "true")
public class OpenHouseIcebergRestApiHandler implements IcebergRestApiHandler {

  static final int DEFAULT_PAGE_SIZE = 100;
  static final int MAX_PAGE_SIZE = 1000;
  private static final String PAGE_TOKEN_VERSION = "v1";

  private final TablesApiHandler tablesApiHandler;
  private final OpenHouseInternalCatalog openHouseInternalCatalog;
  private final IcebergRestTableWriteAdapter tableWriteAdapter;
  private final ClusterProperties clusterProperties;

  public OpenHouseIcebergRestApiHandler(
      TablesApiHandler tablesApiHandler,
      OpenHouseInternalCatalog openHouseInternalCatalog,
      IcebergRestTableWriteAdapter tableWriteAdapter,
      ClusterProperties clusterProperties) {
    this.tablesApiHandler = tablesApiHandler;
    this.openHouseInternalCatalog = openHouseInternalCatalog;
    this.tableWriteAdapter = tableWriteAdapter;
    this.clusterProperties = clusterProperties;
  }

  @Override
  public CatalogConfig getConfig(String warehouse) {
    return new CatalogConfig(
            Collections.singletonMap("prefix", ICEBERG_REST_PREFIX), Collections.emptyMap())
        .endpoints(IcebergRestOpenHouseSupport.SUPPORTED_ENDPOINTS);
  }

  @Override
  public ListTablesResponse listTables(
      String prefix, String namespace, String pageToken, Integer pageSize) {
    validatePrefix(prefix);
    Namespace icebergNamespace = decodeSingleLevelNamespace(namespace);
    PageCursor cursor = decodePageToken(pageToken, pageSize);
    ApiResponse<GetAllTablesResponseBody> response =
        tablesApiHandler.searchTables(
            icebergNamespace.level(0),
            cursor.getPage(),
            cursor.getPageSize(),
            "tableId",
            Collections.emptyList(),
            extractAuthenticatedUserPrincipal());
    Page<GetTableResponseBody> page = response.getResponseBody().getPageResults();
    LinkedHashSet<TableIdentifier> identifiers =
        page.getContent().stream()
            .map(table -> TableIdentifier.of(icebergNamespace, table.getTableId()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    String nextPageToken =
        page.hasNext() ? encodePageToken(cursor.getPage() + 1, cursor.getPageSize()) : null;
    return new ListTablesResponse().identifiers(identifiers).nextPageToken(nextPageToken);
  }

  @Override
  public LoadTableResponse loadTable(
      String prefix,
      String namespace,
      String table,
      String accessDelegation,
      String ifNoneMatch,
      String snapshots,
      String referencedBy) {
    validatePrefix(prefix);
    if (snapshots != null && !"all".equals(snapshots)) {
      throw new UnsupportedOperationException(
          "The snapshots=refs projection is not supported by this catalog");
    }
    // Iceberg 1.11 loadTable may send referenced-by for view-load chains; Phase 1 ignores it.

    TableIdentifier identifier =
        IcebergRestIdentifiers.readTableIdentifier(namespace, table, maxNamespaceDepth());
    requireTable(identifier);
    return CatalogHandlers.loadTable(openHouseInternalCatalog, identifier);
  }

  @Override
  public void tableExists(String prefix, String namespace, String table) {
    validatePrefix(prefix);
    requireTable(IcebergRestIdentifiers.readTableIdentifier(namespace, table, maxNamespaceDepth()));
  }

  @Override
  public LoadTableResponse createTable(
      String prefix, String namespace, CreateTableRequest request, String accessDelegation) {
    validatePrefix(prefix);
    // The namespace is judged here, not by the service layer: a create into a namespace whose name
    // OpenHouse could never hold is a create into a namespace that does not exist (404), and the
    // table name itself is left for the service layer's validator to reject and describe (400),
    // because a create names a table that does not exist yet.
    Namespace icebergNamespace = decodeSingleLevelNamespace(namespace);
    IcebergRestIdentifiers.requireHoldable(icebergNamespace, maxNamespaceDepth());
    return tableWriteAdapter.createTable(
        icebergNamespace, request, extractAuthenticatedUserPrincipal());
  }

  @Override
  public LoadTableResponse updateTable(
      String prefix, String namespace, String table, UpdateTableRequest request) {
    validatePrefix(prefix);
    TableIdentifier identifier =
        IcebergRestIdentifiers.readTableIdentifier(namespace, table, maxNamespaceDepth());
    return tableWriteAdapter.updateTable(
        identifier.namespace(), identifier.name(), request, extractAuthenticatedUserPrincipal());
  }

  @Override
  public void dropTable(String prefix, String namespace, String table, Boolean purgeRequested) {
    validatePrefix(prefix);
    TableIdentifier identifier =
        IcebergRestIdentifiers.readTableIdentifier(namespace, table, maxNamespaceDepth());
    tableWriteAdapter.dropTable(
        identifier.namespace(),
        identifier.name(),
        purgeRequested,
        extractAuthenticatedUserPrincipal());
  }

  @Override
  public void renameTable(String prefix, RenameTableRequest request) {
    validatePrefix(prefix);
    request.validate();
    // The source names a table: a name OpenHouse could never hold is a table that does not exist
    // (404). The destination names a table that is not supposed to exist yet, so only its
    // namespace is judged here -- the same split createTable makes, and for the same reason: the
    // destination table's own name is the service layer validator's to reject and describe (400).
    TableIdentifier source =
        IcebergRestIdentifiers.readTableIdentifier(request.source(), maxNamespaceDepth());
    TableIdentifier destination = request.destination();
    IcebergRestIdentifiers.requireHoldable(destination.namespace(), maxNamespaceDepth());
    tableWriteAdapter.renameTable(source, destination, extractAuthenticatedUserPrincipal());
  }

  /**
   * Serves {@code POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics}, by accepting
   * the report and discarding it.
   *
   * <p>OpenHouse has nowhere to put a client's scan or commit report: there is no sink for them in
   * the service, and inventing one -- a table, a log, a metric -- would be a storage decision with
   * a retention policy attached, made here as a side effect of adding a route. The specification
   * permits a server to accept a report and do nothing with it, and that is exactly what this
   * does. It is not a stub awaiting an implementation; a client that sends a report gets the 204
   * the contract promises, and nothing reads the report.
   *
   * <p>The route exists so that {@code /v1/config} can advertise {@code POST
   * .../tables/{table}/metrics} truthfully. A 1.11 client that sees it advertised pairs its own
   * reporter with a {@code RESTMetricsReporter} pointed here; a client that does not see it keeps
   * only its own. Advertising a route this facade did not serve would be worse than not
   * advertising it, which is why the endpoint list is generated from the same spec markers the
   * routes are.
   *
   * <p>The table is deliberately not looked up. The specification lists 404 for a report about a
   * table that does not exist, and this route never answers it: a lookup would cost a read and an
   * authorization check per scan, on behalf of a report that is discarded either way. A report
   * about a table that is not there is accepted and discarded like any other.
   */
  @Override
  public void reportMetrics(
      String prefix, String namespace, String table, ReportMetricsRequest request) {
    validatePrefix(prefix);
  }

  /** Existence check that speaks the REST specification's vocabulary, and authorizes the read. */
  private void requireTable(TableIdentifier identifier) {
    try {
      tablesApiHandler.getTable(
          identifier.namespace().level(0), identifier.name(), extractAuthenticatedUserPrincipal());
    } catch (NoSuchUserTableException e) {
      throw new NoSuchTableException(
          "Table does not exist: %s.%s", identifier.namespace().level(0), identifier.name());
    }
  }

  private int maxNamespaceDepth() {
    return clusterProperties.getClusterTablesNamespaceMaxDepth();
  }

  private static void validatePrefix(String prefix) {
    if (!ICEBERG_REST_PREFIX.equals(prefix)) {
      throw new IllegalArgumentException("Unsupported Iceberg REST prefix");
    }
  }

  private static Namespace decodeSingleLevelNamespace(String encodedNamespace) {
    Namespace namespace = RESTUtil.decodeNamespace(encodedNamespace);
    if (namespace.isEmpty() || namespace.levels().length != 1) {
      throw new NoSuchNamespaceException("Only single-level namespaces are supported");
    }
    return namespace;
  }

  /**
   * An absent page token and an empty one both mean "the first page". Every Iceberg Java client
   * since 1.6.0 sends {@code pageToken=} on its first request, so rejecting the empty token made
   * {@code RESTCatalog.listTables()} fail outright against this facade. The namespace list route
   * already reads an empty token that way; this is the same rule on the table list route.
   */
  private static PageCursor decodePageToken(String pageToken, Integer requestedPageSize) {
    if (pageToken == null || pageToken.isEmpty()) {
      return new PageCursor(0, validatePageSize(requestedPageSize));
    }

    try {
      String decoded = new String(Base64.getUrlDecoder().decode(pageToken), StandardCharsets.UTF_8);
      String[] parts = decoded.split(":", -1);
      if (parts.length != 3 || !PAGE_TOKEN_VERSION.equals(parts[0])) {
        throw new IllegalArgumentException("Invalid Iceberg REST page token");
      }
      int page = Integer.parseInt(parts[1]);
      int pageSize = validatePageSize(Integer.parseInt(parts[2]));
      if (page < 1 || (requestedPageSize != null && requestedPageSize != pageSize)) {
        throw new IllegalArgumentException("Invalid Iceberg REST page token");
      }
      return new PageCursor(page, pageSize);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid Iceberg REST page token", e);
    }
  }

  private static int validatePageSize(Integer requestedPageSize) {
    int pageSize = requestedPageSize == null ? DEFAULT_PAGE_SIZE : requestedPageSize;
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException(
          String.format("page-size must be between 1 and %s", MAX_PAGE_SIZE));
    }
    return pageSize;
  }

  private static String encodePageToken(int page, int pageSize) {
    String value = String.format("%s:%s:%s", PAGE_TOKEN_VERSION, page, pageSize);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  @lombok.Value
  private static class PageCursor {
    int page;
    int pageSize;
  }
}
