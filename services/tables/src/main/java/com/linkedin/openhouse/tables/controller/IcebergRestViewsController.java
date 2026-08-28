package com.linkedin.openhouse.tables.controller;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.*;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.tables.api.handler.ViewsApiHandler;
import com.linkedin.openhouse.tables.api.icebergrest.IcebergRestViewPaths;
import com.linkedin.openhouse.tables.authorization.Privileges;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Iceberg REST catalog views surface: {@code GET /v1/config} plus the six view routes under
 * {@code /v1/namespaces/{namespace}/views}. Paths are served un-prefixed — {@code /v1/config}
 * returns no {@code prefix} override — and collide with nothing in the OpenHouse tables API, whose
 * routes live under {@code /v1/databases/...}.
 *
 * <p>The controller is registered regardless of whether views are enabled — client bootstrap must
 * precede the per-route 404s — and holds no business logic.
 *
 * <p><b>Wire mechanics:</b> request and response bodies are raw JSON strings ({@code
 * RESTCatalogAdapter} style). Nothing here goes through Spring's Jackson binding: Iceberg's own
 * parsers do all the (de)serialization inside the handler, so wire compliance — kebab-case naming,
 * required fields, update/requirement polymorphism — is a property of the Iceberg dependency, and a
 * malformed body is a views-surface error rather than a global-handler concern. Request bodies are
 * declared optional so an absent body also reaches the views error surface instead of Spring's.
 *
 * <p>Errors on these routes use the {@code IcebergErrorResponse} envelope, rendered by {@link
 * IcebergRestViewsExceptionHandler}. Out of scope by design: {@code rename-view}, {@code
 * register-view}, the tables/namespaces REST routes and the OAuth token endpoint — probing them
 * lands on the {@code /v1/**} unresolved-path 404.
 */
@RestController
public class IcebergRestViewsController {

  private final ViewsApiHandler viewsApiHandler;

  @Autowired
  public IcebergRestViewsController(ViewsApiHandler viewsApiHandler) {
    this.viewsApiHandler = viewsApiHandler;
  }

  @Operation(
      summary = "Catalog configuration",
      description =
          "Client bootstrap for the Iceberg REST views surface. Returns empty defaults and"
              + " overrides plus the explicit endpoints capability list. Served even while views"
              + " are disabled.",
      tags = {"IcebergRestView"})
  @ApiResponses(
      value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Config GET: OK"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Config GET: UNAUTHORIZED")
      })
  @GetMapping(
      value = IcebergRestViewPaths.CONFIG_TEMPLATE,
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<String> getConfig() {
    return toResponseEntity(viewsApiHandler.getConfig());
  }

  @Operation(
      summary = "List all view identifiers underneath a given namespace",
      description = "Returns a ListTablesResponse of view identifiers under this namespace.",
      tags = {"IcebergRestView"})
  @ApiResponses(
      value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "View LIST: OK"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "View LIST: BAD_REQUEST"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "View LIST: UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "View LIST: FORBIDDEN"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "View LIST: NOT_FOUND (NoSuchNamespaceException)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "View LIST: SERVICE_UNAVAILABLE")
      })
  @GetMapping(
      value = IcebergRestViewPaths.VIEWS_COLLECTION_TEMPLATE,
      produces = {MediaType.APPLICATION_JSON_VALUE})
  @Secured(value = Privileges.Privilege.LIST_VIEW)
  public ResponseEntity<String> listViews(
      @Parameter(description = "Namespace identifier", required = true) @PathVariable
          String namespace,
      @RequestParam(required = false) String pageToken,
      @RequestParam(required = false) Integer pageSize) {
    return toResponseEntity(
        viewsApiHandler.listViews(
            namespace, pageToken, pageSize, extractAuthenticatedUserPrincipal()));
  }

  @Operation(
      summary = "Create a view in the given namespace",
      description =
          "Creates a view from a CreateViewRequest and returns a LoadViewResult carrying the"
              + " complete view metadata.",
      tags = {"IcebergRestView"})
  @ApiResponses(
      value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "View CREATE: OK"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "View CREATE: BAD_REQUEST"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "View CREATE: UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "View CREATE: FORBIDDEN"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "View CREATE: NOT_FOUND (NoSuchNamespaceException)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "View CREATE: CONFLICT (AlreadyExistsException)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "View CREATE: SERVICE_UNAVAILABLE")
      })
  @PostMapping(
      value = IcebergRestViewPaths.VIEWS_COLLECTION_TEMPLATE,
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE})
  @Secured(value = Privileges.Privilege.CREATE_VIEW)
  public ResponseEntity<String> createView(
      @Parameter(description = "Namespace identifier", required = true) @PathVariable
          String namespace,
      @Parameter(description = "CreateViewRequest JSON document") @RequestBody(required = false)
          byte[] createViewRequest) {
    return toResponseEntity(
        viewsApiHandler.createView(
            namespace, utf8Body(createViewRequest), extractAuthenticatedUserPrincipal()));
  }

  @Operation(
      summary = "Load a view from the catalog",
      description = "Returns a LoadViewResult carrying the complete view metadata.",
      tags = {"IcebergRestView"})
  @ApiResponses(
      value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "View LOAD: OK"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "View LOAD: BAD_REQUEST"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "View LOAD: UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "View LOAD: FORBIDDEN"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "View LOAD: NOT_FOUND (NoSuchViewException)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "View LOAD: SERVICE_UNAVAILABLE")
      })
  @GetMapping(
      value = IcebergRestViewPaths.VIEW_ITEM_TEMPLATE,
      produces = {MediaType.APPLICATION_JSON_VALUE})
  @Secured(value = Privileges.Privilege.SELECT)
  public ResponseEntity<String> loadView(
      @Parameter(description = "Namespace identifier", required = true) @PathVariable
          String namespace,
      @Parameter(description = "View name", required = true) @PathVariable String view) {
    return toResponseEntity(
        viewsApiHandler.loadView(namespace, view, extractAuthenticatedUserPrincipal()));
  }

  @Operation(
      summary = "Replace a view",
      description =
          "Commits updates to a view from a CommitViewRequest (requirements plus typed updates)"
              + " and returns a LoadViewResult carrying the resulting view metadata.",
      tags = {"IcebergRestView"})
  @ApiResponses(
      value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "View REPLACE: OK"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "View REPLACE: BAD_REQUEST"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "View REPLACE: UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "View REPLACE: FORBIDDEN"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "View REPLACE: NOT_FOUND (NoSuchViewException)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "View REPLACE: CONFLICT (CommitFailedException)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "View REPLACE: SERVICE_UNAVAILABLE")
      })
  @PostMapping(
      value = IcebergRestViewPaths.VIEW_ITEM_TEMPLATE,
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE})
  @Secured(value = Privileges.Privilege.UPDATE_VIEW_METADATA)
  public ResponseEntity<String> replaceView(
      @Parameter(description = "Namespace identifier", required = true) @PathVariable
          String namespace,
      @Parameter(description = "View name", required = true) @PathVariable String view,
      @Parameter(description = "CommitViewRequest JSON document") @RequestBody(required = false)
          byte[] commitViewRequest) {
    return toResponseEntity(
        viewsApiHandler.replaceView(
            namespace, view, utf8Body(commitViewRequest), extractAuthenticatedUserPrincipal()));
  }

  @Operation(
      summary = "Drop a view from the catalog",
      description = "Removes a view from the catalog.",
      tags = {"IcebergRestView"})
  @ApiResponses(
      value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "View DROP: NO_CONTENT"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "View DROP: BAD_REQUEST"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "View DROP: UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "View DROP: FORBIDDEN"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "View DROP: NOT_FOUND (NoSuchViewException)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "View DROP: SERVICE_UNAVAILABLE")
      })
  @DeleteMapping(value = IcebergRestViewPaths.VIEW_ITEM_TEMPLATE)
  @Secured(value = Privileges.Privilege.DELETE_VIEW)
  public ResponseEntity<Void> dropView(
      @Parameter(description = "Namespace identifier", required = true) @PathVariable
          String namespace,
      @Parameter(description = "View name", required = true) @PathVariable String view) {
    return toVoidResponseEntity(
        viewsApiHandler.dropView(namespace, view, extractAuthenticatedUserPrincipal()));
  }

  @Operation(
      summary = "Check if a view exists",
      description = "Returns 204 when the view exists; this request returns no response body.",
      tags = {"IcebergRestView"})
  @ApiResponses(
      value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "View EXISTS: NO_CONTENT"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "View EXISTS: BAD_REQUEST"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "View EXISTS: UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "View EXISTS: NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "View EXISTS: SERVICE_UNAVAILABLE")
      })
  @RequestMapping(method = RequestMethod.HEAD, value = IcebergRestViewPaths.VIEW_ITEM_TEMPLATE)
  @Secured(value = Privileges.Privilege.SELECT)
  public ResponseEntity<Void> viewExists(
      @Parameter(description = "Namespace identifier", required = true) @PathVariable
          String namespace,
      @Parameter(description = "View name", required = true) @PathVariable String view) {
    return toVoidResponseEntity(
        viewsApiHandler.viewExists(namespace, view, extractAuthenticatedUserPrincipal()));
  }

  /** Forwards status, any handler-supplied headers and the serialized JSON body. */
  private static ResponseEntity<String> toResponseEntity(ApiResponse<String> apiResponse) {
    return ResponseEntity.status(apiResponse.getHttpStatus())
        .headers(apiResponse.getHttpHeaders())
        .contentType(MediaType.APPLICATION_JSON)
        .body(apiResponse.getResponseBody());
  }

  /** Forwards status and any handler-supplied headers for the bodyless routes. */
  private static ResponseEntity<Void> toVoidResponseEntity(ApiResponse<Void> apiResponse) {
    return ResponseEntity.status(apiResponse.getHttpStatus())
        .headers(apiResponse.getHttpHeaders())
        .build();
  }

  /**
   * Decode a request body as UTF-8 explicitly. Bodies are received as raw bytes because Spring's
   * {@code StringHttpMessageConverter} would otherwise decode a charset-less {@code
   * application/json} body with its ISO-8859-1 default, silently mangling multibyte SQL; JSON is
   * UTF-8 by RFC 8259.
   */
  private static String utf8Body(byte[] body) {
    return body == null ? null : new String(body, StandardCharsets.UTF_8);
  }
}
