package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.apache.iceberg.rest.requests.CommitTransactionRequest;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The table write routes of the Iceberg REST Catalog (IRC) protocol, under the same base path as
 * {@link IcebergRestCatalogController} (whose catch-all they take precedence over, being more
 * specific); the work is {@link IcebergRestWriteHandler}'s. Hidden from the generated OpenAPI spec,
 * like the other IRC routes. Registered on every Iceberg line this server builds with (the fork's
 * 1.12 and LinkedIn's 1.5.2).
 */
@Hidden
@ConditionalOnIcebergRest
@RestController
public class IcebergRestWriteController {

  private static final String V1 = IcebergRestCatalogController.BASE_PATH + "/v1";

  @Autowired private IcebergRestWriteHandler writeHandler;

  /**
   * Creates a table or, with {@code stage-create}, returns the metadata a create would store
   * without creating it. Not secured here, as the native create route is not: the service checks
   * the create privilege.
   */
  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC create table: OK"))
  @IcebergRestCapability(IcebergRestCapability.POLICIES)
  @PostMapping(V1 + "/namespaces/{namespace}/tables")
  public ResponseEntity<byte[]> createTable(
      @PathVariable("namespace") String namespace, @RequestBody(required = false) byte[] body) {
    String databaseId = IcebergRestApiHandler.databaseId(namespace);
    CreateTableRequest request = IcebergRestJson.read(body, CreateTableRequest.class);
    String principal = extractAuthenticatedUserPrincipal();
    if (request.stageCreate()) {
      return IcebergRestJson.staged(
          writeHandler.stageCreateTable(databaseId, request, principal),
          writeHandler.stagedTableConfig(databaseId, request.name()));
    }
    return IcebergRestJson.loadTable(writeHandler.createTable(databaseId, request, principal));
  }

  /**
   * Registers a table from a metadata file in the directory the server allocated to it, by creating
   * it there through the native create ({@link IcebergRestWriteHandler#registerTable}). Not secured
   * here, as the native create route is not: the handler checks the create privilege.
   */
  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC register table: OK"))
  @PostMapping(V1 + "/namespaces/{namespace}/register")
  public ResponseEntity<byte[]> registerTable(
      @PathVariable("namespace") String namespace, @RequestBody(required = false) byte[] body) {
    String databaseId = IcebergRestApiHandler.databaseId(namespace);
    RegisterTableRequest request = IcebergRestJson.read(body, RegisterTableRequest.class);
    return IcebergRestJson.loadTable(
        writeHandler.registerTable(databaseId, request, extractAuthenticatedUserPrincipal()));
  }

  /** Commits requirements and updates to a table, completing a staged create when asked to. */
  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC commit table: OK"))
  @IcebergRestCapability(IcebergRestCapability.POLICIES)
  @PostMapping(V1 + "/namespaces/{namespace}/tables/{table}")
  public ResponseEntity<byte[]> commitTable(
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String tableId,
      @RequestBody(required = false) byte[] body) {
    String databaseId = IcebergRestApiHandler.tableDatabaseId(namespace, tableId);
    UpdateTableRequest request = IcebergRestJson.read(body, UpdateTableRequest.class);
    return IcebergRestJson.loadTable(
        writeHandler.commitTable(
            databaseId, tableId, request, extractAuthenticatedUserPrincipal()));
  }

  /**
   * Commits a transaction: the commit of its one table change, answered with no content as the spec
   * has it. A transaction with more than one table change is refused (400) and writes nothing:
   * OpenHouse cannot commit several tables atomically ({@link
   * IcebergRestWriteHandler#commitTransaction}). The route is listed among the served endpoints
   * nonetheless, since a client that does not find it listed refuses every transaction locally,
   * those of one table included, and has no per-table fallback to lose.
   */
  @ApiResponses(
      @ApiResponse(responseCode = "204", description = "IRC commit transaction: NO_CONTENT"))
  @IcebergRestCapability(IcebergRestCapability.POLICIES)
  @PostMapping(V1 + "/transactions/commit")
  public ResponseEntity<byte[]> commitTransaction(@RequestBody(required = false) byte[] body) {
    writeHandler.commitTransaction(
        IcebergRestJson.read(body, CommitTransactionRequest.class),
        extractAuthenticatedUserPrincipal());
    return IcebergRestJson.noContent();
  }

  /**
   * Drops a table, as the native drop does: {@code purgeRequested} is accepted and changes nothing,
   * since the native drop always removes the table's files.
   */
  @ApiResponses(@ApiResponse(responseCode = "204", description = "IRC drop table: NO_CONTENT"))
  @DeleteMapping(V1 + "/namespaces/{namespace}/tables/{table}")
  public ResponseEntity<byte[]> dropTable(
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String tableId,
      @RequestParam(value = "purgeRequested", required = false) String purgeRequested) {
    writeHandler.dropTable(
        IcebergRestApiHandler.tableDatabaseId(namespace, tableId),
        tableId,
        extractAuthenticatedUserPrincipal());
    return IcebergRestJson.noContent();
  }

  /** Renames a table, as the native rename does. */
  @ApiResponses(@ApiResponse(responseCode = "204", description = "IRC rename table: NO_CONTENT"))
  @PostMapping(V1 + "/tables/rename")
  public ResponseEntity<byte[]> renameTable(@RequestBody(required = false) byte[] body) {
    writeHandler.renameTable(
        IcebergRestJson.read(body, RenameTableRequest.class), extractAuthenticatedUserPrincipal());
    return IcebergRestJson.noContent();
  }
}
