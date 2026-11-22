package com.linkedin.openhouse.javaclient;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.apache.iceberg.BaseMetadataTable;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.BaseTransaction;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTRequest;
import org.apache.iceberg.rest.RESTResponse;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;

/**
 * An in-process, REST-shaped {@link RESTClient}: each request Iceberg's {@code RESTSessionCatalog}
 * would send over HTTP is dispatched to a handler that calls today's {@link OpenHouseCatalog} (the
 * "backing" catalog) directly. There is no serialization and no HTTP.
 *
 * <p>Most routes call Iceberg's {@link CatalogHandlers}. A few routes use a handler of our own,
 * because the Iceberg handler changes what OpenHouse sees compared with today's path; each one says
 * why:
 *
 * <ul>
 *   <li>staged create (CTAS): one OpenHouse stage-create, returning the server-staged metadata;
 *   <li>table commits: a single attempt (the client keeps its own retries), a create commit without
 *       a second stage-create, and a replace commit sent to the server as a replace;
 *   <li>namespace property updates: no namespace describe before the update.
 * </ul>
 *
 * <p>Errors: the original exception thrown by the backing catalog is rethrown unchanged. No {@link
 * ErrorResponse} is built, so the client's error handlers never re-map it. Only a request with no
 * route goes through the error handler.
 *
 * <p>{@link #close()} does not close the backing catalog: {@code RESTSessionCatalog} closes the
 * client it uses for the config call right after that call.
 */
public class OpenHouseRestAdapter implements RESTClient {

  /** {@code RESTSessionCatalog} property that turns REST metrics reporting on or off. */
  static final String REST_METRICS_REPORTING_ENABLED = "rest-metrics-reporting-enabled";

  /**
   * Catalog defaults the in-process "server" advertises in its config response. The client merges
   * them under its own properties, so a property the user sets still wins.
   *
   * <ul>
   *   <li>{@code io-impl}: today's catalog uses {@link HadoopFileIO} unless {@code io-impl} is set;
   *       {@code RESTSessionCatalog} would otherwise default to {@code ResolvingFileIO}.
   *   <li>{@code rest-metrics-reporting-enabled=false}: today's tables report only to the
   *       configured {@code metrics-reporter-impl}; the REST reporter would add a second reporter
   *       that posts every scan and commit report to the server.
   * </ul>
   */
  static final Map<String, String> CONFIG_DEFAULTS =
      ImmutableMap.of(
          CatalogProperties.FILE_IO_IMPL,
          HadoopFileIO.class.getName(),
          REST_METRICS_REPORTING_ENABLED,
          "false");

  enum Method {
    GET,
    HEAD,
    POST,
    DELETE
  }

  /** The REST routes this adapter serves. Patterns follow the Iceberg REST spec without prefix. */
  enum Route {
    CONFIG(Method.GET, "v1/config"),
    LIST_NAMESPACES(Method.GET, "v1/namespaces"),
    CREATE_NAMESPACE(Method.POST, "v1/namespaces"),
    NAMESPACE_EXISTS(Method.HEAD, "v1/namespaces/{namespace}"),
    LOAD_NAMESPACE(Method.GET, "v1/namespaces/{namespace}"),
    DROP_NAMESPACE(Method.DELETE, "v1/namespaces/{namespace}"),
    UPDATE_NAMESPACE(Method.POST, "v1/namespaces/{namespace}/properties"),
    LIST_TABLES(Method.GET, "v1/namespaces/{namespace}/tables"),
    CREATE_TABLE(Method.POST, "v1/namespaces/{namespace}/tables"),
    TABLE_EXISTS(Method.HEAD, "v1/namespaces/{namespace}/tables/{name}"),
    LOAD_TABLE(Method.GET, "v1/namespaces/{namespace}/tables/{name}"),
    UPDATE_TABLE(Method.POST, "v1/namespaces/{namespace}/tables/{name}"),
    DROP_TABLE(Method.DELETE, "v1/namespaces/{namespace}/tables/{name}"),
    RENAME_TABLE(Method.POST, "v1/tables/rename"),
    REGISTER_TABLE(Method.POST, "v1/namespaces/{namespace}/register"),
    REPORT_METRICS(Method.POST, "v1/namespaces/{namespace}/tables/{name}/metrics"),
    LIST_VIEWS(Method.GET, "v1/namespaces/{namespace}/views"),
    CREATE_VIEW(Method.POST, "v1/namespaces/{namespace}/views"),
    LOAD_VIEW(Method.GET, "v1/namespaces/{namespace}/views/{name}"),
    UPDATE_VIEW(Method.POST, "v1/namespaces/{namespace}/views/{name}"),
    DROP_VIEW(Method.DELETE, "v1/namespaces/{namespace}/views/{name}"),
    RENAME_VIEW(Method.POST, "v1/views/rename");

    private final Method method;
    private final String[] parts;

    Route(Method method, String pattern) {
      this.method = method;
      this.parts = pattern.split("/", -1);
    }

    /** Path variables when this route matches, otherwise {@code null}. */
    Map<String, String> match(Method requestMethod, String[] requestParts) {
      if (method != requestMethod || parts.length != requestParts.length) {
        return null;
      }
      ImmutableMap.Builder<String, String> vars = ImmutableMap.builder();
      for (int i = 0; i < parts.length; i++) {
        String part = parts[i];
        if (part.startsWith("{") && part.endsWith("}")) {
          vars.put(part.substring(1, part.length() - 1), requestParts[i]);
        } else if (!part.equalsIgnoreCase(requestParts[i])) {
          return null;
        }
      }
      return vars.build();
    }
  }

  private final OpenHouseCatalog backing;

  public OpenHouseRestAdapter(OpenHouseCatalog backing) {
    this.backing = backing;
  }

  // ------------------------------------------------------------------ RESTClient

  @Override
  public void head(String path, Map<String, String> headers, Consumer<ErrorResponse> errorHandler) {
    execute(Method.HEAD, path, null, null, null, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T delete(
      String path,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<ErrorResponse> errorHandler) {
    return execute(Method.DELETE, path, null, null, responseType, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T delete(
      String path,
      Map<String, String> queryParams,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<ErrorResponse> errorHandler) {
    return execute(Method.DELETE, path, queryParams, null, responseType, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T get(
      String path,
      Map<String, String> queryParams,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<ErrorResponse> errorHandler) {
    return execute(Method.GET, path, queryParams, null, responseType, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T post(
      String path,
      RESTRequest body,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<ErrorResponse> errorHandler) {
    return execute(Method.POST, path, null, body, responseType, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T postForm(
      String path,
      Map<String, String> formData,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<ErrorResponse> errorHandler) {
    // OAuth token requests are the only form posts; the adapter path has no OAuth.
    return execute(Method.POST, path, null, null, responseType, errorHandler);
  }

  /** Does nothing: the backing catalog outlives every client {@code RESTSessionCatalog} builds. */
  @Override
  public void close() {}

  // ------------------------------------------------------------------ dispatch

  private <T extends RESTResponse> T execute(
      Method method,
      String path,
      Map<String, String> queryParams,
      Object body,
      Class<T> responseType,
      Consumer<ErrorResponse> errorHandler) {
    String[] requestParts = path.split("/", -1);
    for (Route route : Route.values()) {
      Map<String, String> vars = route.match(method, requestParts);
      if (vars != null) {
        Map<String, String> allVars =
            ImmutableMap.<String, String>builder()
                .putAll(queryParams == null ? Collections.emptyMap() : queryParams)
                .putAll(vars)
                .build();
        Object response = handle(route, allVars, body);
        return responseType == null || response == null ? null : responseType.cast(response);
      }
    }
    ErrorResponse error =
        ErrorResponse.builder()
            .responseCode(400)
            .withType("BadRequestException")
            .withMessage(String.format("No route for request: %s %s", method, path))
            .build();
    errorHandler.accept(error);
    throw new RESTException("Unhandled error: %s", error);
  }

  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  private Object handle(Route route, Map<String, String> vars, Object body) {
    switch (route) {
      case CONFIG:
        return ConfigResponse.builder().withDefaults(CONFIG_DEFAULTS).build();

      case LIST_NAMESPACES:
        {
          Namespace parent =
              vars.containsKey("parent")
                  ? Namespace.of(vars.get("parent").split(NAMESPACE_SEPARATOR, -1))
                  : Namespace.empty();
          return CatalogHandlers.listNamespaces(backing, parent);
        }

      case CREATE_NAMESPACE:
        return CatalogHandlers.createNamespace(backing, (CreateNamespaceRequest) body);

      case NAMESPACE_EXISTS:
        if (!backing.namespaceExists(namespace(vars))) {
          throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace(vars));
        }
        return null;

      case LOAD_NAMESPACE:
        return CatalogHandlers.loadNamespace(backing, namespace(vars));

      case DROP_NAMESPACE:
        CatalogHandlers.dropNamespace(backing, namespace(vars));
        return null;

      case UPDATE_NAMESPACE:
        return updateNamespaceProperties(namespace(vars), (UpdateNamespacePropertiesRequest) body);

      case LIST_TABLES:
        return CatalogHandlers.listTables(backing, namespace(vars));

      case CREATE_TABLE:
        {
          CreateTableRequest request = (CreateTableRequest) body;
          request.validate();
          return request.stageCreate()
              ? stageCreate(namespace(vars), request)
              : CatalogHandlers.createTable(backing, namespace(vars), request);
        }

      case TABLE_EXISTS:
        if (!backing.tableExists(ident(vars))) {
          throw new NoSuchTableException("Table does not exist: %s", ident(vars));
        }
        return null;

      case LOAD_TABLE:
        return loadTable(ident(vars));

      case UPDATE_TABLE:
        return updateTable(ident(vars), (UpdateTableRequest) body);

      case DROP_TABLE:
        if (Boolean.parseBoolean(vars.getOrDefault("purgeRequested", "false"))) {
          CatalogHandlers.purgeTable(backing, ident(vars));
        } else {
          CatalogHandlers.dropTable(backing, ident(vars));
        }
        return null;

      case RENAME_TABLE:
        CatalogHandlers.renameTable(backing, (RenameTableRequest) body);
        return null;

      case REGISTER_TABLE:
        return CatalogHandlers.registerTable(backing, namespace(vars), (RegisterTableRequest) body);

      case REPORT_METRICS:
        // Today's path has no server-side metrics endpoint; reports are accepted and dropped.
        return null;

      case LIST_VIEWS:
        return CatalogHandlers.listViews(backing, namespace(vars));

      case CREATE_VIEW:
        return CatalogHandlers.createView(backing, namespace(vars), (CreateViewRequest) body);

      case LOAD_VIEW:
        return CatalogHandlers.loadView(backing, ident(vars));

      case UPDATE_VIEW:
        return CatalogHandlers.updateView(backing, ident(vars), (UpdateTableRequest) body);

      case DROP_VIEW:
        CatalogHandlers.dropView(backing, ident(vars));
        return null;

      case RENAME_VIEW:
        CatalogHandlers.renameView(backing, (RenameTableRequest) body);
        return null;

      default:
        throw new IllegalStateException("Unhandled route: " + route);
    }
  }

  // ------------------------------------------------------------------ handlers of our own

  /**
   * Staged create (CTAS). Iceberg's {@code CatalogHandlers.stageTableCreate} asks the catalog for a
   * transaction only to learn the location and returns locally built metadata with an added {@code
   * created-at} property; its create commit then stage-creates a second time with an empty schema.
   * Today's path stage-creates once and builds the transaction on the metadata the server staged.
   * This handler does the same and returns that metadata, so the client's create commit carries the
   * server's schema, partitioning, properties and UUID.
   */
  private LoadTableResponse stageCreate(Namespace namespace, CreateTableRequest request) {
    TableIdentifier ident = TableIdentifier.of(namespace, request.name());
    Transaction transaction =
        backing
            .buildTable(ident, request.schema())
            .withLocation(request.location())
            .withPartitionSpec(request.spec())
            .withSortOrder(request.writeOrder())
            .withProperties(request.properties())
            .createTransaction();
    TableMetadata staged = ((BaseTransaction) transaction).currentMetadata();
    return LoadTableResponse.builder().withTableMetadata(staged).build();
  }

  /**
   * A table commit, applied once to the table as the backing catalog loads it now.
   *
   * <p>Iceberg's {@code CatalogHandlers.updateTable} retries a failed commit up to four times
   * inside the handler, on top of the client's own retries, and turns a create commit into a second
   * stage-create with an empty schema. It also commits a replace as an ordinary update, because the
   * replace intent never reaches {@link OpenHouseTableOperations}; the server's replace checks
   * ({@code replace.enabled} and the others) only run for a commit flagged as a replace.
   *
   * <p>Here: a {@link org.apache.iceberg.exceptions.CommitFailedException} goes straight back to
   * the client, whose retry loop refreshes and retries as it does today; a create commit goes to
   * fresh table operations without a second stage-create; a replace commit is sent on operations
   * marked as a replace.
   */
  private LoadTableResponse updateTable(TableIdentifier ident, UpdateTableRequest request) {
    TableMetadata result;
    if (isCreate(request)) {
      OpenHouseTableOperations ops = backing.newTableOps(ident);
      request.requirements().forEach(requirement -> requirement.validate(ops.current()));
      TableMetadata.Builder builder = TableMetadata.buildFromEmpty();
      request.updates().forEach(update -> update.applyTo(builder));
      ops.commit(null, builder.build());
      result = ops.current();
    } else {
      Table table = backing.loadTable(ident);
      if (!(table instanceof BaseTable)) {
        throw new IllegalStateException("Cannot wrap catalog that does not produce BaseTable");
      }
      TableOperations ops = ((BaseTable) table).operations();
      if (isReplace(request) && ops instanceof OpenHouseTableOperations) {
        ((OpenHouseTableOperations) ops).markReplaceTransaction();
      }
      TableMetadata base = ops.current();
      request.requirements().forEach(requirement -> requirement.validate(base));
      TableMetadata.Builder builder = TableMetadata.buildFrom(base);
      request.updates().forEach(update -> update.applyTo(builder));
      TableMetadata updated = builder.build();
      if (updated.changes().isEmpty()) {
        result = base;
      } else {
        ops.commit(base, updated);
        result = ops.current();
      }
    }
    return LoadTableResponse.builder().withTableMetadata(result).build();
  }

  /** A create commit: the only requirement is that the table does not exist. */
  static boolean isCreate(UpdateTableRequest request) {
    return request.requirements().stream()
        .anyMatch(UpdateRequirement.AssertTableDoesNotExist.class::isInstance);
  }

  /**
   * A replace commit, as Iceberg 1.5's {@code RESTTableOperations} builds it.
   *
   * <p>A replace always carries {@code SetCurrentSchema}, {@code SetDefaultPartitionSpec} and
   * {@code SetDefaultSortOrder} ({@code RESTSessionCatalog} adds any the replacement lacks), and
   * {@code UpdateRequirements.forReplaceTable} never asserts the current schema, default spec or
   * default sort order. An ordinary update that sets any of the three always asserts the matching
   * id ({@code UpdateRequirements.forUpdateTable}), so the two cannot be confused.
   */
  static boolean isReplace(UpdateTableRequest request) {
    if (isCreate(request)) {
      return false;
    }
    List<MetadataUpdate> updates = request.updates();
    List<UpdateRequirement> requirements = request.requirements();
    return updates.stream().anyMatch(MetadataUpdate.SetCurrentSchema.class::isInstance)
        && updates.stream().anyMatch(MetadataUpdate.SetDefaultPartitionSpec.class::isInstance)
        && updates.stream().anyMatch(MetadataUpdate.SetDefaultSortOrder.class::isInstance)
        && requirements.stream()
            .noneMatch(
                requirement ->
                    requirement instanceof UpdateRequirement.AssertCurrentSchemaID
                        || requirement instanceof UpdateRequirement.AssertDefaultSpecID
                        || requirement instanceof UpdateRequirement.AssertDefaultSortOrderID);
  }

  /**
   * Namespace property updates. Iceberg's handler describes the namespace first (to report missing
   * removals), which today's catalog rejects with "Describing database is not supported" before the
   * update is attempted; today's path calls {@code setProperties} / {@code removeProperties}
   * directly.
   */
  private UpdateNamespacePropertiesResponse updateNamespaceProperties(
      Namespace namespace, UpdateNamespacePropertiesRequest request) {
    request.validate();
    if (!request.updates().isEmpty()) {
      backing.setProperties(namespace, request.updates());
    }
    if (!request.removals().isEmpty()) {
      backing.removeProperties(namespace, new HashSet<>(request.removals()));
    }
    return UpdateNamespacePropertiesResponse.builder()
        .addUpdated(request.updates().keySet())
        .addRemoved(request.removals())
        .build();
  }

  /**
   * {@code CatalogHandlers.loadTable} as in Iceberg 1.5.2, which 1.12 replaced with an overload
   * that takes a snapshot mode; this is its {@code ALL} mode, the only one 1.5.2 has.
   */
  private LoadTableResponse loadTable(TableIdentifier ident) {
    Table table = backing.loadTable(ident);
    if (table instanceof BaseTable) {
      return LoadTableResponse.builder()
          .withTableMetadata(((BaseTable) table).operations().current())
          .build();
    } else if (table instanceof BaseMetadataTable) {
      // Metadata tables are loaded on the client side.
      throw new NoSuchTableException("Table does not exist: %s", ident);
    }
    throw new IllegalStateException("Cannot wrap catalog that does not produce BaseTable");
  }

  // ------------------------------------------------------------------ path variables

  // The REST namespace separators, decoded here because Iceberg 1.5.2 and 1.12 share no helper for
  // it: 1.12 dropped RESTUtil.NAMESPACE_SPLITTER and its decodeNamespace takes the separator. The
  // client side (RESTSessionCatalog) uses these defaults on both lines; CONFIG sets no other.
  private static final String NAMESPACE_SEPARATOR = Pattern.quote("\u001f");
  private static final String NAMESPACE_ESCAPED_SEPARATOR = Pattern.quote("%1F");

  private static Namespace namespace(Map<String, String> vars) {
    String encoded = vars.get("namespace");
    // An empty namespace is encoded as an empty path segment.
    if (encoded == null || encoded.isEmpty()) {
      return Namespace.empty();
    }
    String[] levels = encoded.split(NAMESPACE_ESCAPED_SEPARATOR, -1);
    for (int i = 0; i < levels.length; i++) {
      levels[i] = RESTUtil.decodeString(levels[i]);
    }
    return Namespace.of(levels);
  }

  private static TableIdentifier ident(Map<String, String> vars) {
    return TableIdentifier.of(namespace(vars), RESTUtil.decodeString(vars.get("name")));
  }
}
