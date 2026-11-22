package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.tables.authorization.Privileges;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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
 * The view routes of the Iceberg REST Catalog (IRC) protocol, under the same base path as {@link
 * IcebergRestCatalogController} (whose catch-all they take precedence over, being more specific).
 * Each route is secured as the native view routes are; the work is {@link
 * IcebergRestViewHandler}'s. Hidden from the generated OpenAPI spec, like the other IRC routes.
 *
 * <p>A separate controller, registered only where the Iceberg view API exists (as {@link
 * IcebergRestViewHandler} is), so the IRC table routes still start where it does not. There the
 * view routes keep the catch-all's 406.
 */
@Hidden
@RestController
@ConditionalOnClass(name = "org.apache.iceberg.view.ViewMetadata")
public class IcebergRestViewController {

  private static final String V1 = IcebergRestCatalogController.BASE_PATH + "/v1";

  @Autowired private IcebergRestViewHandler handler;

  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC list views: OK"))
  @GetMapping(V1 + "/namespaces/{namespace}/views")
  @Secured(value = Privileges.Privilege.AUTHENTICATED_VIEWS_OPS)
  public ResponseEntity<byte[]> listViews(
      @PathVariable("namespace") String namespace,
      @RequestParam(value = "pageToken", required = false) String pageToken,
      @RequestParam(value = "pageSize", required = false) String pageSize) {
    return IcebergRestJson.ok(handler.listViews(namespace, extractAuthenticatedUserPrincipal()));
  }

  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC create view: OK"))
  @PostMapping(V1 + "/namespaces/{namespace}/views")
  @Secured(value = Privileges.Privilege.AUTHENTICATED_VIEWS_OPS)
  public ResponseEntity<byte[]> createView(
      @PathVariable("namespace") String namespace, @RequestBody(required = false) byte[] body) {
    return IcebergRestJson.ok(
        handler.createView(
            namespace,
            IcebergRestJson.read(body, CreateViewRequest.class),
            extractAuthenticatedUserPrincipal()));
  }

  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC load view: OK"))
  @GetMapping(V1 + "/namespaces/{namespace}/views/{view}")
  @Secured(value = Privileges.Privilege.AUTHENTICATED_VIEWS_OPS)
  public ResponseEntity<byte[]> loadView(
      @PathVariable("namespace") String namespace, @PathVariable("view") String view) {
    return IcebergRestJson.ok(
        handler.loadView(namespace, view, extractAuthenticatedUserPrincipal()));
  }

  @ApiResponses(@ApiResponse(responseCode = "204", description = "IRC view exists"))
  @RequestMapping(value = V1 + "/namespaces/{namespace}/views/{view}", method = RequestMethod.HEAD)
  @Secured(value = Privileges.Privilege.AUTHENTICATED_VIEWS_OPS)
  public ResponseEntity<byte[]> viewExists(
      @PathVariable("namespace") String namespace, @PathVariable("view") String view) {
    handler.viewExists(namespace, view, extractAuthenticatedUserPrincipal());
    return IcebergRestJson.noContent();
  }

  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC replace view: OK"))
  @PostMapping(V1 + "/namespaces/{namespace}/views/{view}")
  @Secured(value = Privileges.Privilege.AUTHENTICATED_VIEWS_OPS)
  public ResponseEntity<byte[]> replaceView(
      @PathVariable("namespace") String namespace,
      @PathVariable("view") String view,
      @RequestBody(required = false) byte[] body) {
    return IcebergRestJson.ok(
        handler.commitView(
            namespace,
            view,
            IcebergRestJson.read(body, UpdateTableRequest.class),
            extractAuthenticatedUserPrincipal()));
  }

  @ApiResponses(@ApiResponse(responseCode = "204", description = "IRC drop view: NO_CONTENT"))
  @DeleteMapping(V1 + "/namespaces/{namespace}/views/{view}")
  @Secured(value = Privileges.Privilege.AUTHENTICATED_VIEWS_OPS)
  public ResponseEntity<byte[]> dropView(
      @PathVariable("namespace") String namespace,
      @PathVariable("view") String view,
      @RequestParam(value = "purgeRequested", required = false) String purgeRequested) {
    handler.dropView(namespace, view, extractAuthenticatedUserPrincipal());
    return IcebergRestJson.noContent();
  }

  /** Answers 406: the native view API has no rename. */
  @ApiResponses(@ApiResponse(responseCode = "406", description = "IRC rename view: unsupported"))
  @PostMapping(V1 + "/views/rename")
  @Secured(value = Privileges.Privilege.AUTHENTICATED_VIEWS_OPS)
  public ResponseEntity<byte[]> renameView(@RequestBody(required = false) byte[] body) {
    handler.renameView(IcebergRestJson.read(body, RenameTableRequest.class));
    return IcebergRestJson.noContent();
  }
}
