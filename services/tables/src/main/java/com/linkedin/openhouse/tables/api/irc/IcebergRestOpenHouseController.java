package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.tables.api.handler.DatabasesApiHandler;
import com.linkedin.openhouse.tables.api.handler.TablesApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockReason;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAclPoliciesResponseBody;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenHouse features that the Iceberg REST Catalog (IRC) protocol has no route for, served under
 * the IRC base path beside the spec's routes, as sub-resources of the IRC table and namespace paths
 * ({@code .../openhouse/...}): ACL policies (GRANT, REVOKE, SHOW GRANTS) and unlocking a table
 * (UNLOCK). A client finds them through the config response's {@value
 * IcebergRestApiHandler#CAPABILITIES} ({@code grants}, {@code unlock}); they are not spec routes,
 * so they are not in its {@code endpoints}.
 *
 * <p>Each route is a thin adapter over the native route's API handler: the same handler method,
 * with the same arguments, so validation, authorization, storage and audit are the native route's.
 * The request and response bodies are the native ones, read and written by the same Spring
 * converters as on the native routes; the success status is the handler's. Only the method of an
 * update differs: the native ACL update is a PATCH, which Iceberg's REST client cannot send (it has
 * GET, HEAD, POST and DELETE), so here it is a POST with the same body. Failures are answered in
 * the spec's error shape by the IRC exception handler, like every IRC route.
 *
 * <p>Namespaces have one level, as on the other IRC routes: on a table route a namespace of more
 * levels names no table (404 {@code NoSuchTableException}); on a namespace route it is malformed
 * (400). The database ACL routes do not require the database to exist, as natively (a CREATE TABLE
 * grant precedes the first table).
 *
 * <p>Registered on every Iceberg line: nothing here uses an Iceberg API.
 */
@Hidden
@RestController
public class IcebergRestOpenHouseController {

  private static final String NAMESPACE = IcebergRestCatalogController.BASE_PATH + "/v1/namespaces";

  private static final String TABLE_ACL =
      NAMESPACE + "/{namespace}/tables/{table}/openhouse/acl-policies";

  private static final String DATABASE_ACL = NAMESPACE + "/{namespace}/openhouse/acl-policies";

  private static final String LOCK = NAMESPACE + "/{namespace}/tables/{table}/openhouse/lock";

  private static final String JSON = "application/json";

  @Autowired private TablesApiHandler tablesApiHandler;

  @Autowired private DatabasesApiHandler databasesApiHandler;

  /** SHOW GRANTS ON TABLE: the native {@code GET .../tables/{t}/aclPolicies}. */
  @ApiResponses(
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "200",
          description = "IRC OpenHouse table ACL policies GET: OK"))
  @IcebergRestCapability(IcebergRestCapability.GRANTS)
  @GetMapping(value = TABLE_ACL, produces = JSON)
  public ResponseEntity<GetAclPoliciesResponseBody> getTableAclPolicies(
      @PathVariable("namespace") String namespace, @PathVariable("table") String tableId) {
    return respond(
        tablesApiHandler.getAclPolicies(
            IcebergRestApiHandler.tableDatabaseId(namespace, tableId),
            tableId,
            extractAuthenticatedUserPrincipal()));
  }

  /** GRANT or REVOKE on a table: the native {@code PATCH .../tables/{t}/aclPolicies}. */
  @ApiResponses(
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "204",
          description = "IRC OpenHouse table ACL policies update: NO_CONTENT"))
  @IcebergRestCapability(IcebergRestCapability.GRANTS)
  @PostMapping(value = TABLE_ACL, produces = JSON)
  public ResponseEntity<Void> updateTableAclPolicies(
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String tableId,
      @RequestBody UpdateAclPoliciesRequestBody body) {
    return respond(
        tablesApiHandler.updateAclPolicies(
            IcebergRestApiHandler.tableDatabaseId(namespace, tableId),
            tableId,
            body,
            extractAuthenticatedUserPrincipal()));
  }

  /** SHOW GRANTS ON DATABASE: the native {@code GET /databases/{db}/aclPolicies}. */
  @ApiResponses(
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "200",
          description = "IRC OpenHouse database ACL policies GET: OK"))
  @IcebergRestCapability(IcebergRestCapability.GRANTS)
  @GetMapping(value = DATABASE_ACL, produces = JSON)
  public ResponseEntity<GetAclPoliciesResponseBody> getDatabaseAclPolicies(
      @PathVariable("namespace") String namespace) {
    return respond(
        databasesApiHandler.getDatabaseAclPolicies(
            IcebergRestApiHandler.databaseId(namespace), extractAuthenticatedUserPrincipal()));
  }

  /** GRANT or REVOKE on a database: the native {@code PATCH /databases/{db}/aclPolicies}. */
  @ApiResponses(
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "204",
          description = "IRC OpenHouse database ACL policies update: NO_CONTENT"))
  @IcebergRestCapability(IcebergRestCapability.GRANTS)
  @PostMapping(value = DATABASE_ACL, produces = JSON)
  public ResponseEntity<Void> updateDatabaseAclPolicies(
      @PathVariable("namespace") String namespace, @RequestBody UpdateAclPoliciesRequestBody body) {
    return respond(
        databasesApiHandler.updateDatabaseAclPolicies(
            IcebergRestApiHandler.databaseId(namespace),
            body,
            extractAuthenticatedUserPrincipal()));
  }

  /** UNLOCK (a LEGACY lock): the native {@code DELETE .../tables/{t}/lock}. */
  @ApiResponses(
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "204",
          description = "IRC OpenHouse unlock: NO_CONTENT"))
  @IcebergRestCapability(IcebergRestCapability.UNLOCK)
  @DeleteMapping(value = LOCK, produces = JSON)
  public ResponseEntity<Void> deleteLock(
      @PathVariable("namespace") String namespace, @PathVariable("table") String tableId) {
    return respond(
        tablesApiHandler.deleteLock(
            IcebergRestApiHandler.tableDatabaseId(namespace, tableId),
            tableId,
            extractAuthenticatedUserPrincipal()));
  }

  /**
   * UNLOCK REASON r: the native {@code DELETE .../tables/{t}/lock/{reason}}, whose reason converts
   * the same way (the enum constant's exact name).
   */
  @ApiResponses(
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "204",
          description = "IRC OpenHouse unlock by reason: NO_CONTENT"))
  @IcebergRestCapability(IcebergRestCapability.UNLOCK)
  @DeleteMapping(value = LOCK + "/{reason}", produces = JSON)
  public ResponseEntity<Void> deleteLockByReason(
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String tableId,
      @PathVariable("reason") LockReason reason) {
    return respond(
        tablesApiHandler.deleteLock(
            IcebergRestApiHandler.tableDatabaseId(namespace, tableId),
            tableId,
            reason,
            extractAuthenticatedUserPrincipal()));
  }

  /** The handler's answer, as the native controllers return it. */
  private static <T> ResponseEntity<T> respond(ApiResponse<T> response) {
    return new ResponseEntity<>(
        response.getResponseBody(), response.getHttpHeaders(), response.getHttpStatus());
  }
}
