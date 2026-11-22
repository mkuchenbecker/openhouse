package com.linkedin.openhouse.javaclient;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTRequest;
import org.apache.iceberg.rest.RESTResponse;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;

/**
 * An in-process, REST-shaped {@link RESTClient}: each request Iceberg's {@code RESTSessionCatalog}
 * would send over HTTP is dispatched to a handler that calls today's {@link OpenHouseCatalog} (the
 * "backing" catalog) directly. There is no serialization and no HTTP.
 *
 * <p>Every route calls Iceberg's {@link CatalogHandlers}.
 *
 * <p>Errors: the original exception thrown by the backing catalog is rethrown unchanged. No {@link
 * ErrorResponse} is built, so the client's error handlers never re-map it. Only a request with no
 * route goes through the error handler.
 *
 * <p>{@link #close()} does not close the backing catalog: {@code RESTSessionCatalog} closes the
 * client it uses for the config call right after that call.
 */
public class OpenHouseRestAdapter implements RESTClient {

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
        return ConfigResponse.builder().build();

      case LIST_NAMESPACES:
        {
          Namespace parent =
              vars.containsKey("parent")
                  ? Namespace.of(
                      RESTUtil.NAMESPACE_SPLITTER
                          .splitToStream(vars.get("parent"))
                          .toArray(String[]::new))
                  : Namespace.empty();
          return CatalogHandlers.listNamespaces(backing, parent);
        }

      case CREATE_NAMESPACE:
        return CatalogHandlers.createNamespace(backing, (CreateNamespaceRequest) body);

      case NAMESPACE_EXISTS:
        if (!backing.namespaceExists(namespace(vars))) {
          throw new org.apache.iceberg.exceptions.NoSuchNamespaceException(
              "Namespace does not exist: %s", namespace(vars));
        }
        return null;

      case LOAD_NAMESPACE:
        return CatalogHandlers.loadNamespace(backing, namespace(vars));

      case DROP_NAMESPACE:
        CatalogHandlers.dropNamespace(backing, namespace(vars));
        return null;

      case UPDATE_NAMESPACE:
        return CatalogHandlers.updateNamespaceProperties(
            backing, namespace(vars), (UpdateNamespacePropertiesRequest) body);

      case LIST_TABLES:
        return CatalogHandlers.listTables(backing, namespace(vars));

      case CREATE_TABLE:
        {
          CreateTableRequest request = (CreateTableRequest) body;
          request.validate();
          return request.stageCreate()
              ? CatalogHandlers.stageTableCreate(backing, namespace(vars), request)
              : CatalogHandlers.createTable(backing, namespace(vars), request);
        }

      case TABLE_EXISTS:
        if (!backing.tableExists(ident(vars))) {
          throw new NoSuchTableException("Table does not exist: %s", ident(vars));
        }
        return null;

      case LOAD_TABLE:
        return CatalogHandlers.loadTable(backing, ident(vars));

      case UPDATE_TABLE:
        return CatalogHandlers.updateTable(backing, ident(vars), (UpdateTableRequest) body);

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

  // ------------------------------------------------------------------ path variables

  private static Namespace namespace(Map<String, String> vars) {
    String encoded = vars.get("namespace");
    // An empty namespace is encoded as an empty path segment.
    return encoded == null || encoded.isEmpty()
        ? Namespace.empty()
        : RESTUtil.decodeNamespace(encoded);
  }

  private static TableIdentifier ident(Map<String, String> vars) {
    return TableIdentifier.of(namespace(vars), RESTUtil.decodeString(vars.get("name")));
  }
}
