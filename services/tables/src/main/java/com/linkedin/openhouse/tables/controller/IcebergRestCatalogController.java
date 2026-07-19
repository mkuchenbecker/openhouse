package com.linkedin.openhouse.tables.controller;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.services.DatabasesService;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.RESTSerializers;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-side implementation of the <a
 * href="https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml">Iceberg
 * REST Catalog spec</a>, mounted under {@code /iceberg} so that a stock {@code
 * org.apache.iceberg.rest.RESTCatalog} (Spark 4.0) can drive OpenHouse with no custom client jar.
 *
 * <p>TABLE operations delegate to Iceberg's server driver {@link CatalogHandlers}, which routes
 * through the injected {@link Catalog} (the {@code OpenHouseInternalCatalog} bean) and its {@code
 * TableOperations} -- so the OpenHouse reserved-key interception (snapshot smuggling, {@code
 * COMMIT_KEY}) in {@code OpenHouseInternalTableOperations.commit(...)} still applies.
 *
 * <p>NAMESPACE operations are implemented directly here because {@code OpenHouseInternalCatalog}
 * does NOT implement {@link org.apache.iceberg.catalog.SupportsNamespaces} -- OpenHouse databases
 * are implicit (a database "exists" iff it has at least one table). See {@code
 * docs/spark4-iceberg-upgrade/rest-endpoint} for the full design, decisions, and pitfalls.
 *
 * <p>Serialization is done manually with an {@link ObjectMapper} configured exactly like Iceberg's
 * internal {@code RESTObjectMapper} (kebab-case, field visibility, {@link RESTSerializers}). We read
 * the raw request body as a String and write the raw response as a String to avoid depending on the
 * Spring MVC {@code ObjectMapper} having Iceberg's REST (de)serializers registered.
 *
 * <p>Errors are mapped to the Iceberg {@link ErrorResponse} envelope by local {@link
 * ExceptionHandler} methods, which take precedence over the global {@code
 * OpenHouseExceptionHandler @ControllerAdvice} for exceptions thrown by this controller.
 */
@Slf4j
@RestController
@RequestMapping(path = "/iceberg/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class IcebergRestCatalogController {

  @Autowired private Catalog catalog;

  @Autowired private DatabasesService databasesService;

  /** Configured identically to Iceberg's package-private {@code RESTObjectMapper}. */
  private static final ObjectMapper MAPPER = newRestObjectMapper();

  private static ObjectMapper newRestObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mapper.setPropertyNamingStrategy(new PropertyNamingStrategies.KebabCaseStrategy());
    RESTSerializers.registerAll(mapper);
    return mapper;
  }

  // ---------------------------------------------------------------------------
  // Config
  // ---------------------------------------------------------------------------

  /**
   * Called by RESTCatalog on init. We return empty defaults/overrides and no prefix, so the client
   * uses the spec paths directly under this controller's base path.
   */
  @GetMapping("/config")
  public ResponseEntity<String> getConfig(
      @RequestParam(value = "warehouse", required = false) String warehouse) {
    return json(HttpStatus.OK, ConfigResponse.builder().build());
  }

  // ---------------------------------------------------------------------------
  // Namespaces (implemented directly -- no SupportsNamespaces on the catalog)
  // ---------------------------------------------------------------------------

  /**
   * Lists namespaces. OpenHouse databases are implicit, so this returns the distinct set of
   * databases that currently have at least one table (derived from the databases service). The
   * spec's {@code parent} query param (hierarchical listing) is not an OpenHouse concept and is
   * ignored -- a non-empty parent yields an empty list.
   */
  @GetMapping("/namespaces")
  public ResponseEntity<String> listNamespaces(
      @RequestParam(value = "parent", required = false) String parent) {
    if (parent != null && !parent.isEmpty()) {
      // OpenHouse namespaces are single-level; there are no child namespaces.
      return json(HttpStatus.OK, ListNamespacesResponse.builder().build());
    }
    Set<Namespace> namespaces =
        databasesService.getAllDatabases().stream()
            .map(DatabaseDto::getDatabaseId)
            .map(Namespace::of)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return json(HttpStatus.OK, ListNamespacesResponse.builder().addAll(namespaces).build());
  }

  /**
   * Create namespace. OpenHouse databases are created implicitly on first table creation, so this is
   * a no-op that echoes the requested namespace with empty properties (properties are NOT persisted;
   * OpenHouse has no namespace property store).
   */
  @PostMapping("/namespaces")
  public ResponseEntity<String> createNamespace(@RequestBody(required = false) String body) {
    // We only need the namespace back; parse minimally and validate it is single-level.
    org.apache.iceberg.rest.requests.CreateNamespaceRequest request =
        readRequest(body, org.apache.iceberg.rest.requests.CreateNamespaceRequest.class);
    Namespace namespace = request.namespace();
    validateSingleLevel(namespace);
    return json(
        HttpStatus.OK,
        CreateNamespaceResponse.builder()
            .withNamespace(namespace)
            .setProperties(java.util.Collections.emptyMap())
            .build());
  }

  /**
   * Load namespace metadata. OpenHouse has no namespace property store, so this returns empty
   * properties for any valid single-level namespace (optimistic existence -- see class doc and the
   * pitfalls note).
   */
  @GetMapping("/namespaces/{namespace}")
  public ResponseEntity<String> loadNamespaceMetadata(@PathVariable("namespace") String namespace) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    validateSingleLevel(ns);
    return json(
        HttpStatus.OK,
        GetNamespaceResponse.builder()
            .withNamespace(ns)
            .setProperties(java.util.Collections.emptyMap())
            .build());
  }

  /**
   * Namespace existence check. Returns 204 for any valid single-level namespace (optimistic -- see
   * class doc); 404 with no body only for malformed (multi-level) namespaces.
   */
  @RequestMapping(path = "/namespaces/{namespace}", method = RequestMethod.HEAD)
  public ResponseEntity<Void> namespaceExists(@PathVariable("namespace") String namespace) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    if (ns.levels().length != 1) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * Drop namespace. Not supported: OpenHouse databases have no independent lifecycle (they disappear
   * when the last table is dropped), so there is nothing to drop. Returns 501.
   */
  @DeleteMapping("/namespaces/{namespace}")
  public ResponseEntity<String> dropNamespace(@PathVariable("namespace") String namespace) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    return json(
        HttpStatus.NOT_IMPLEMENTED,
        errorBody(
            HttpStatus.NOT_IMPLEMENTED,
            "UnsupportedOperationException",
            "Dropping a namespace is not supported by OpenHouse; databases are implicit and are "
                + "removed when their last table is dropped. Namespace: "
                + ns));
  }

  // ---------------------------------------------------------------------------
  // Tables (delegate to CatalogHandlers -> Catalog -> TableOperations)
  // ---------------------------------------------------------------------------

  @GetMapping("/namespaces/{namespace}/tables")
  public ResponseEntity<String> listTables(@PathVariable("namespace") String namespace) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    return json(HttpStatus.OK, CatalogHandlers.listTables(catalog, ns));
  }

  @PostMapping("/namespaces/{namespace}/tables")
  public ResponseEntity<String> createTable(
      @PathVariable("namespace") String namespace, @RequestBody(required = false) String body) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    CreateTableRequest request = readRequest(body, CreateTableRequest.class);
    if (request.stageCreate()) {
      return json(HttpStatus.OK, CatalogHandlers.stageTableCreate(catalog, ns, request));
    }
    return json(HttpStatus.OK, CatalogHandlers.createTable(catalog, ns, request));
  }

  @GetMapping("/namespaces/{namespace}/tables/{table}")
  public ResponseEntity<String> loadTable(
      @PathVariable("namespace") String namespace, @PathVariable("table") String table) {
    TableIdentifier ident = TableIdentifier.of(RESTUtil.decodeNamespace(namespace), table);
    return json(HttpStatus.OK, CatalogHandlers.loadTable(catalog, ident));
  }

  @RequestMapping(path = "/namespaces/{namespace}/tables/{table}", method = RequestMethod.HEAD)
  public ResponseEntity<Void> tableExists(
      @PathVariable("namespace") String namespace, @PathVariable("table") String table) {
    TableIdentifier ident = TableIdentifier.of(RESTUtil.decodeNamespace(namespace), table);
    // Throws NoSuchTableException (-> 404) when absent; returns 204 when present.
    CatalogHandlers.tableExists(catalog, ident);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/namespaces/{namespace}/tables/{table}")
  public ResponseEntity<String> updateTable(
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table,
      @RequestBody(required = false) String body) {
    TableIdentifier ident = TableIdentifier.of(RESTUtil.decodeNamespace(namespace), table);
    UpdateTableRequest request = readRequest(body, UpdateTableRequest.class);
    return json(HttpStatus.OK, CatalogHandlers.updateTable(catalog, ident, request));
  }

  @DeleteMapping("/namespaces/{namespace}/tables/{table}")
  public ResponseEntity<String> dropTable(
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table,
      @RequestParam(value = "purgeRequested", required = false, defaultValue = "false")
          boolean purgeRequested) {
    TableIdentifier ident = TableIdentifier.of(RESTUtil.decodeNamespace(namespace), table);
    if (purgeRequested) {
      CatalogHandlers.purgeTable(catalog, ident);
    } else {
      CatalogHandlers.dropTable(catalog, ident);
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/tables/rename")
  public ResponseEntity<Void> renameTable(@RequestBody(required = false) String body) {
    RenameTableRequest request = readRequest(body, RenameTableRequest.class);
    CatalogHandlers.renameTable(catalog, request);
    return ResponseEntity.noContent().build();
  }

  // ---------------------------------------------------------------------------
  // (De)serialization helpers
  // ---------------------------------------------------------------------------

  private <T> T readRequest(String body, Class<T> type) {
    if (body == null || body.isEmpty()) {
      throw new BadRequestException("Missing request body for %s", type.getSimpleName());
    }
    try {
      return MAPPER.readValue(body, type);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new BadRequestException(
          "Malformed request body for %s: %s", type.getSimpleName(), e.getMessage());
    }
  }

  private static ResponseEntity<String> json(HttpStatus status, Object payload) {
    try {
      return ResponseEntity.status(status)
          .contentType(MediaType.APPLICATION_JSON)
          .body(MAPPER.writeValueAsString(payload));
    } catch (Exception e) {
      throw new RESTException(e, "Failed to serialize REST response: %s", e.getMessage());
    }
  }

  private static void validateSingleLevel(Namespace namespace) {
    if (namespace == null || namespace.levels().length != 1) {
      throw new ValidationException(
          "OpenHouse supports single-level namespaces (databaseId); got: %s", namespace);
    }
  }

  private static ErrorResponse errorBody(HttpStatus status, String type, String message) {
    return ErrorResponse.builder()
        .responseCode(status.value())
        .withType(type)
        .withMessage(message)
        .build();
  }

  // ---------------------------------------------------------------------------
  // Error handling -> Iceberg ErrorResponse envelope
  //
  // These local handlers win over the global OpenHouseExceptionHandler @ControllerAdvice for
  // exceptions thrown by this controller, so the RESTCatalog client always receives the Iceberg
  // error envelope with the spec-mandated HTTP status.
  // ---------------------------------------------------------------------------

  @ExceptionHandler({NoSuchTableException.class, NoSuchNamespaceException.class})
  public ResponseEntity<String> handleNotFound(Exception e) {
    return error(HttpStatus.NOT_FOUND, e);
  }

  @ExceptionHandler({
    AlreadyExistsException.class,
    CommitFailedException.class,
    CommitStateUnknownException.class
  })
  public ResponseEntity<String> handleConflict(Exception e) {
    return error(HttpStatus.CONFLICT, e);
  }

  @ExceptionHandler(NotAuthorizedException.class)
  public ResponseEntity<String> handleUnauthorized(Exception e) {
    return error(HttpStatus.UNAUTHORIZED, e);
  }

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<String> handleForbidden(Exception e) {
    return error(HttpStatus.FORBIDDEN, e);
  }

  @ExceptionHandler({
    ValidationException.class,
    BadRequestException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<String> handleBadRequest(Exception e) {
    return error(HttpStatus.BAD_REQUEST, e);
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ResponseEntity<String> handleUnsupported(Exception e) {
    return error(HttpStatus.NOT_IMPLEMENTED, e);
  }

  @ExceptionHandler({RESTException.class, Exception.class})
  public ResponseEntity<String> handleGeneric(Exception e) {
    log.warn("Unmapped exception in Iceberg REST endpoint", e);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
  }

  private static ResponseEntity<String> error(HttpStatus status, Exception e) {
    return json(status, errorBody(status, e.getClass().getSimpleName(), e.getMessage()));
  }
}
