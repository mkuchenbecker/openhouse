package com.linkedin.openhouse.javaclient;

import java.util.ArrayList;
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

  /**
   * The config response's override: the server capabilities, as the tables service's IRC config
   * advertises them ({@code IcebergRestApiHandler.config()}), so that every REST-shaped path reads
   * the same capability. {@code policies}: a policy patch ({@code updated.openhouse.policy}) is
   * applied; here the backing OpenHouse catalog's table operations apply it, as they always have.
   */
  static final Map<String, String> CONFIG_OVERRIDES =
      ImmutableMap.of("openhouse.capabilities", "policies");

  enum Method {
    GET,
    HEAD,
    POST,
    DELETE
  }

  /**
   * The REST routes this adapter serves. Patterns follow the Iceberg REST spec without prefix, with
   * the spec's path variable names ({@code table}, {@code view}), so that {@link #endpoint()} is
   * the spec's endpoint string.
   */
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
    TABLE_EXISTS(Method.HEAD, "v1/namespaces/{namespace}/tables/{table}"),
    LOAD_TABLE(Method.GET, "v1/namespaces/{namespace}/tables/{table}"),
    UPDATE_TABLE(Method.POST, "v1/namespaces/{namespace}/tables/{table}"),
    DROP_TABLE(Method.DELETE, "v1/namespaces/{namespace}/tables/{table}"),
    RENAME_TABLE(Method.POST, "v1/tables/rename"),
    REGISTER_TABLE(Method.POST, "v1/namespaces/{namespace}/register"),
    REPORT_METRICS(Method.POST, "v1/namespaces/{namespace}/tables/{table}/metrics"),
    LIST_VIEWS(Method.GET, "v1/namespaces/{namespace}/views"),
    CREATE_VIEW(Method.POST, "v1/namespaces/{namespace}/views"),
    LOAD_VIEW(Method.GET, "v1/namespaces/{namespace}/views/{view}"),
    UPDATE_VIEW(Method.POST, "v1/namespaces/{namespace}/views/{view}"),
    DROP_VIEW(Method.DELETE, "v1/namespaces/{namespace}/views/{view}"),
    RENAME_VIEW(Method.POST, "v1/views/rename");

    private final Method method;
    private final String pattern;
    private final String[] parts;

    Route(Method method, String pattern) {
      this.method = method;
      this.pattern = pattern;
      this.parts = pattern.split("/", -1);
    }

    /**
     * This route as an Iceberg REST endpoint string ({@code Endpoint.toString()} on 1.12): the
     * method, a space and the path with the {@code {prefix}} segment, for example {@code POST
     * /v1/{prefix}/namespaces/{namespace}/views}. The config route has none: it is how a client
     * learns the endpoints.
     */
    String endpoint() {
      return method + " /v1/{prefix}" + pattern.substring("v1".length());
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
  /**
   * Iceberg 1.12's {@code RESTSessionCatalog} asks for the response headers of some calls (for
   * example the ETag of a table load); {@code RESTClient}'s default for these overloads throws
   * {@code UnsupportedOperationException("Returning response headers is not supported")}. An
   * in-process call has no HTTP response headers, so these hand over none and otherwise answer as
   * the overloads without them. Iceberg 1.5.2's {@code RESTClient} has no such overloads, so they
   * carry no {@code @Override} and are unused there.
   */
  public <T extends RESTResponse> T get(
      String path,
      Map<String, String> queryParams,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<ErrorResponse> errorHandler,
      Consumer<Map<String, String>> responseHeaders) {
    T response = get(path, queryParams, responseType, headers, errorHandler);
    if (responseHeaders != null) {
      responseHeaders.accept(Collections.emptyMap());
    }
    return response;
  }

  /** See {@link #get(String, Map, Class, Map, Consumer, Consumer)}. */
  public <T extends RESTResponse> T post(
      String path,
      RESTRequest body,
      Class<T> responseType,
      Map<String, String> headers,
      Consumer<ErrorResponse> errorHandler,
      Consumer<Map<String, String>> responseHeaders) {
    T response = post(path, body, responseType, headers, errorHandler);
    if (responseHeaders != null) {
      responseHeaders.accept(Collections.emptyMap());
    }
    return response;
  }

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
    Route route = route(method, requestParts);
    if (route != null) {
      Map<String, String> allVars =
          ImmutableMap.<String, String>builder()
              .putAll(queryParams == null ? Collections.emptyMap() : queryParams)
              .putAll(route.match(method, requestParts))
              .build();
      Object response = handle(route, allVars, body);
      return responseType == null || response == null ? null : responseType.cast(response);
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

  /** The route that serves {@code method path} (a path without prefix), or {@code null}. */
  static Route route(Method method, String path) {
    return route(method, path.split("/", -1));
  }

  private static Route route(Method method, String[] requestParts) {
    for (Route route : Route.values()) {
      if (route.match(method, requestParts) != null) {
        return route;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ config

  /**
   * The endpoints the config response advertises on the v3 line: every route but {@link
   * Route#CONFIG}, as Iceberg endpoint strings. Derived from the dispatch table, so a route cannot
   * be served without being advertised or advertised without being served.
   */
  static List<String> advertisedEndpoints() {
    List<String> endpoints = new ArrayList<>();
    for (Route route : Route.values()) {
      if (route != Route.CONFIG) {
        endpoints.add(route.endpoint());
      }
    }
    return endpoints;
  }

  /**
   * The config response: {@link #CONFIG_DEFAULTS}, {@link #CONFIG_OVERRIDES}, plus on the v3 line
   * ({@link IcebergLine}) the {@link #advertisedEndpoints()}.
   *
   * <p>Iceberg 1.12's {@code RESTSessionCatalog} calls only advertised endpoints and, when a server
   * advertises none, assumes a fixed default set that has no view routes (and a transaction commit
   * route this adapter does not serve), so it refused every view statement locally. Iceberg 1.5.2's
   * config response has no endpoints, and its client calls every route; there the response is
   * unchanged.
   */
  private static ConfigResponse configResponse() {
    ConfigResponse.Builder builder =
        ConfigResponse.builder().withDefaults(CONFIG_DEFAULTS).withOverrides(CONFIG_OVERRIDES);
    if (IcebergLine.current() == IcebergLine.V3) {
      V3Endpoints.advertise(builder, advertisedEndpoints());
    }
    return builder.build();
  }

  /**
   * Adds endpoints to a config response through Iceberg 1.12's public API, {@code
   * Endpoint.create(String, String)} and {@code ConfigResponse.Builder.withEndpoints(List)}, looked
   * up by name because neither exists on Iceberg 1.5.2, which this source also compiles against.
   * Only called on the v3 line; if that line lacks either method the config call fails, rather than
   * advertising nothing.
   *
   * <p>The class name is built from {@link RESTClient}'s package, not written as a string literal:
   * the runtime jar's shadow relocation rewrites string literals that look like {@code org.*}
   * names, and it turned that literal into a relocated name no class has.
   */
  private static final class V3Endpoints {
    private static final String ENDPOINT_CLASS =
        RESTClient.class.getPackage().getName() + ".Endpoint";

    private V3Endpoints() {}

    static void advertise(ConfigResponse.Builder builder, List<String> endpoints) {
      try {
        Class<?> endpointClass =
            Class.forName(ENDPOINT_CLASS, true, ConfigResponse.class.getClassLoader());
        java.lang.reflect.Method create =
            endpointClass.getMethod("create", String.class, String.class);
        List<Object> created = new ArrayList<>();
        for (String endpoint : endpoints) {
          int space = endpoint.indexOf(' ');
          created.add(
              create.invoke(null, endpoint.substring(0, space), endpoint.substring(space + 1)));
        }
        ConfigResponse.Builder.class
            .getMethod("withEndpoints", List.class)
            .invoke(builder, created);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(
            "Iceberg " + IcebergLine.current() + " line without REST endpoints in ConfigResponse",
            e);
      }
    }
  }

  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  private Object handle(Route route, Map<String, String> vars, Object body) {
    switch (route) {
      case CONFIG:
        return configResponse();

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
        return CatalogHandlers.loadView(backing, viewIdent(vars));

      case UPDATE_VIEW:
        return CatalogHandlers.updateView(backing, viewIdent(vars), (UpdateTableRequest) body);

      case DROP_VIEW:
        CatalogHandlers.dropView(backing, viewIdent(vars));
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
    return ident(vars, "table");
  }

  private static TableIdentifier viewIdent(Map<String, String> vars) {
    return ident(vars, "view");
  }

  private static TableIdentifier ident(Map<String, String> vars, String nameVariable) {
    return TableIdentifier.of(namespace(vars), RESTUtil.decodeString(vars.get(nameVariable)));
  }
}
