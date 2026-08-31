package com.linkedin.openhouse.tables.api.handler.impl;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import com.linkedin.openhouse.common.exception.NoSuchUserTableException;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateTableRequest;
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

  private static final Pattern TABLE_NAME_PATTERN =
      Pattern.compile(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX);

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

    TableIdentifier identifier = readTableIdentifier(namespace, table);
    requireTable(identifier);
    return CatalogHandlers.loadTable(openHouseInternalCatalog, identifier);
  }

  @Override
  public void tableExists(String prefix, String namespace, String table) {
    validatePrefix(prefix);
    requireTable(readTableIdentifier(namespace, table));
  }

  @Override
  public LoadTableResponse createTable(
      String prefix, String namespace, CreateTableRequest request, String accessDelegation) {
    validatePrefix(prefix);
    // A create names a table that does not exist yet, so an unusable name is the client's mistake
    // to correct (400) rather than a table that happens to be absent (404); the identifier is left
    // for the service layer's own validator to reject and describe.
    return tableWriteAdapter.createTable(
        decodeSingleLevelNamespace(namespace), request, extractAuthenticatedUserPrincipal());
  }

  @Override
  public LoadTableResponse updateTable(
      String prefix, String namespace, String table, UpdateTableRequest request) {
    validatePrefix(prefix);
    TableIdentifier identifier = readTableIdentifier(namespace, table);
    return tableWriteAdapter.updateTable(
        identifier.namespace(), identifier.name(), request, extractAuthenticatedUserPrincipal());
  }

  @Override
  public void dropTable(String prefix, String namespace, String table, Boolean purgeRequested) {
    validatePrefix(prefix);
    TableIdentifier identifier = readTableIdentifier(namespace, table);
    tableWriteAdapter.dropTable(
        identifier.namespace(), identifier.name(), extractAuthenticatedUserPrincipal());
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

  private static void validatePrefix(String prefix) {
    if (!ICEBERG_REST_PREFIX.equals(prefix)) {
      throw new IllegalArgumentException("Unsupported Iceberg REST prefix");
    }
  }

  /**
   * Decode and validate the table a route names.
   *
   * <p>A route that names an existing table never answers "you asked wrongly": an identifier
   * OpenHouse's charset cannot express names a table that cannot exist, which is a 404 with the
   * same type and message an absent table gets. Reaching the service layer with such an identifier
   * produced a 400 instead, which told a client its request was malformed when what it had actually
   * done was look for something that is not there -- and, for {@code tableExists}, turned "no" into
   * an error. This is the table-route counterpart of the namespace handler's {@code readNamespace}.
   */
  private TableIdentifier readTableIdentifier(String encodedNamespace, String table) {
    Namespace namespace = RESTUtil.decodeNamespace(encodedNamespace);
    try {
      NamespaceUtil.validate(namespace, clusterProperties.getClusterTablesNamespaceMaxDepth());
      if (table == null || !TABLE_NAME_PATTERN.matcher(table).matches()) {
        throw new ValidationException(
            "Table name %s must match %s", table, TABLE_NAME_PATTERN.pattern());
      }
    } catch (ValidationException e) {
      throw new NoSuchTableException(
          "Table does not exist: %s.%s", NamespaceUtil.encode(namespace), table);
    }
    return TableIdentifier.of(namespace, table);
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
