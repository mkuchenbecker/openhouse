package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.tables.authorization.Privileges;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Arrays;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read routes of the Iceberg REST Catalog (IRC) protocol, served under {@value #BASE_PATH} in
 * the same service as the native API (a client sets {@code uri=<server>/iceberg}). Bodies are
 * written by {@link IcebergRestJson} and failures by the IRC-scoped exception handler, so the
 * native routes' JSON and error bodies are untouched. Hidden from the service's generated OpenAPI
 * spec, from which the native clients are generated.
 *
 * <p>Every other request under the base path is answered in the spec's error shape: 406 for a route
 * of the specification this server does not serve yet, 400 for a path that is not an IRC route.
 */
@Hidden
@RestController
public class IcebergRestCatalogController {

  /** Base path of the IRC routes. */
  public static final String BASE_PATH = "/iceberg";

  private static final String V1 = BASE_PATH + "/v1";

  @Autowired private IcebergRestApiHandler handler;

  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC config: OK"))
  @GetMapping(V1 + "/config")
  public ResponseEntity<byte[]> getConfig(
      @RequestParam(value = "warehouse", required = false) String warehouse) {
    return IcebergRestJson.ok(handler.config());
  }

  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC list namespaces: OK"))
  @GetMapping(V1 + "/namespaces")
  public ResponseEntity<byte[]> listNamespaces(
      @RequestParam(value = "parent", required = false) String parent,
      @RequestParam(value = "pageToken", required = false) String pageToken,
      @RequestParam(value = "pageSize", required = false) String pageSize) {
    return IcebergRestJson.ok(handler.listNamespaces(parent));
  }

  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC load namespace: OK"))
  @GetMapping(V1 + "/namespaces/{namespace}")
  public ResponseEntity<byte[]> loadNamespace(@PathVariable("namespace") String namespace) {
    return IcebergRestJson.ok(handler.loadNamespace(namespace));
  }

  @ApiResponses(@ApiResponse(responseCode = "204", description = "IRC namespace exists"))
  @RequestMapping(value = V1 + "/namespaces/{namespace}", method = RequestMethod.HEAD)
  public ResponseEntity<byte[]> namespaceExists(@PathVariable("namespace") String namespace) {
    handler.namespaceExists(namespace);
    return IcebergRestJson.noContent();
  }

  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC list tables: OK"))
  @GetMapping(V1 + "/namespaces/{namespace}/tables")
  public ResponseEntity<byte[]> listTables(
      @PathVariable("namespace") String namespace,
      @RequestParam(value = "pageToken", required = false) String pageToken,
      @RequestParam(value = "pageSize", required = false) String pageSize) {
    return IcebergRestJson.ok(handler.listTables(namespace));
  }

  /**
   * Loads a table. Secured like the native get-table route; the parameter names are the ones the
   * authorization interceptor reads.
   */
  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC load table: OK"))
  @GetMapping(V1 + "/namespaces/{namespace}/tables/{table}")
  @Secured(value = Privileges.Privilege.GET_TABLE_METADATA)
  public ResponseEntity<byte[]> loadTable(
      @PathVariable("namespace") String databaseId,
      @PathVariable("table") String tableId,
      @RequestParam(value = "snapshots", required = false) String snapshots) {
    return IcebergRestJson.loadTable(
        handler.loadTable(
            IcebergRestApiHandler.databaseId(databaseId),
            tableId,
            snapshots,
            extractAuthenticatedUserPrincipal()));
  }

  @ApiResponses(@ApiResponse(responseCode = "204", description = "IRC table exists"))
  @RequestMapping(
      value = V1 + "/namespaces/{namespace}/tables/{table}",
      method = RequestMethod.HEAD)
  public ResponseEntity<byte[]> tableExists(
      @PathVariable("namespace") String namespace, @PathVariable("table") String tableId) {
    handler.tableExists(namespace, tableId);
    return IcebergRestJson.noContent();
  }

  /** Routes of the IRC specification this server does not serve (yet), as METHOD and pattern. */
  private static final List<String[]> UNSERVED_SPEC_ROUTES =
      Arrays.asList(
          new String[] {"POST", "/v1/oauth/tokens"},
          new String[] {"POST", "/v1/namespaces"},
          new String[] {"DELETE", "/v1/namespaces/*"},
          new String[] {"POST", "/v1/namespaces/*/properties"},
          new String[] {"POST", "/v1/namespaces/*/tables"},
          new String[] {"POST", "/v1/namespaces/*/tables/*"},
          new String[] {"DELETE", "/v1/namespaces/*/tables/*"},
          new String[] {"POST", "/v1/namespaces/*/register"},
          new String[] {"POST", "/v1/namespaces/*/register-view"},
          new String[] {"POST", "/v1/namespaces/*/tables/*/unregister"},
          new String[] {"POST", "/v1/namespaces/*/tables/*/metrics"},
          new String[] {"POST", "/v1/namespaces/*/tables/*/plan"},
          new String[] {"GET", "/v1/namespaces/*/tables/*/plan/*"},
          new String[] {"DELETE", "/v1/namespaces/*/tables/*/plan/*"},
          new String[] {"POST", "/v1/namespaces/*/tables/*/tasks"},
          new String[] {"GET", "/v1/namespaces/*/tables/*/credentials"},
          new String[] {"POST", "/v1/namespaces/*/tables/*/sign"},
          new String[] {"POST", "/v1/tables/rename"},
          new String[] {"POST", "/v1/transactions/commit"},
          new String[] {"GET", "/v1/namespaces/*/views"},
          new String[] {"POST", "/v1/namespaces/*/views"},
          new String[] {"GET", "/v1/namespaces/*/views/*"},
          new String[] {"HEAD", "/v1/namespaces/*/views/*"},
          new String[] {"POST", "/v1/namespaces/*/views/*"},
          new String[] {"DELETE", "/v1/namespaces/*/views/*"},
          new String[] {"POST", "/v1/views/rename"},
          new String[] {"GET", "/v1/namespaces/*/functions"},
          new String[] {"GET", "/v1/namespaces/*/functions/*"});

  private static final AntPathMatcher MATCHER = new AntPathMatcher();

  /** Every other request under the base path: see the class comment. */
  @RequestMapping(BASE_PATH + "/**")
  public ResponseEntity<byte[]> unserved(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    String route = path.substring(BASE_PATH.length());
    String method = request.getMethod();
    boolean specRoute =
        UNSERVED_SPEC_ROUTES.stream()
            .anyMatch(r -> r[0].equals(method) && MATCHER.match(r[1], route));
    throw new IcebergRestRouteException(
        specRoute
            ? String.format("This server does not support %s %s yet", method, route)
            : String.format("No IRC route for %s %s", method, route),
        specRoute);
  }
}
