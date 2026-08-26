package com.linkedin.openhouse.tables.resthandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prototype endpoint for the Iceberg REST-catalog-native table commit.
 *
 * <p>Accepts the Iceberg REST spec {@code UpdateTableRequest} — a list of typed {@code
 * requirements} (assertions validated against fresh server-side state) and {@code updates}
 * (semantic metadata deltas applied to a fresh base) — and returns the spec {@code
 * LoadTableResponse} (committed metadata plus its location), so a client never has to re-read
 * metadata.json after a commit.
 *
 * <p>The route is prototype-private ({@code /v1/rest/...}); the spec-exact path/prefix ({@code POST
 * /v1/&#123;prefix&#125;/namespaces/&#123;ns&#125;/tables/&#123;table&#125;}) lands with the REST
 * read plane in a later phase. Namespaces map 1:1 to OpenHouse databaseIds; multi-level namespaces
 * are rejected.
 *
 * <p>Serialization deliberately goes through the dedicated {@link IcebergRestSerde} mapper rather
 * than the service-wide MVC mapper, so Iceberg's wire types never leak into the legacy endpoints'
 * serialization behavior.
 */
@RestController
@Slf4j
public class IcebergRestCommitController {

  /** Multi-level namespace separator used by the Iceberg REST spec (%1F). */
  private static final String NAMESPACE_SEPARATOR = "\u001F";

  @Autowired private IcebergRestCommitService icebergRestCommitService;

  @Autowired private IcebergRestSerde icebergRestSerde;

  @Operation(
      summary = "Commit typed metadata updates to a table (Iceberg REST-native prototype)",
      description =
          "Commits Iceberg REST spec (requirements, updates) pairs to an existing table. "
              + "Requirements are validated against fresh state inside the commit loop; updates "
              + "are re-applied server-side onto a fresh metadata base.",
      tags = {"IcebergRestCommit"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Commit succeeded"),
        @ApiResponse(responseCode = "400", description = "Invalid update request"),
        @ApiResponse(responseCode = "404", description = "Table not found"),
        @ApiResponse(responseCode = "409", description = "Commit conflict"),
        @ApiResponse(responseCode = "500", description = "Commit state unknown")
      })
  @PostMapping(
      value = "/v1/rest/namespaces/{namespace}/tables/{tableId}/commit",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> commitTable(
      @PathVariable(value = "namespace") String namespace,
      @PathVariable(value = "tableId") String tableId,
      @RequestBody String requestBody)
      throws JsonProcessingException {
    validateSingleLevelNamespace(namespace);

    UpdateTableRequest request;
    try {
      request = icebergRestSerde.fromJson(requestBody, UpdateTableRequest.class);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new BadRequestException(e, "Malformed UpdateTableRequest: %s", e.getMessage());
    }

    LoadTableResponse response = icebergRestCommitService.commit(namespace, tableId, request);
    return ResponseEntity.status(HttpStatus.OK)
        .contentType(MediaType.APPLICATION_JSON)
        .body(icebergRestSerde.toJson(response));
  }

  /**
   * OpenHouse identifiers are strictly {@code db.table}: a databaseId maps to exactly one REST
   * namespace level. Reject multi-level namespaces explicitly rather than silently treating the
   * separator as part of a database name.
   */
  private static void validateSingleLevelNamespace(String namespace) {
    if (namespace == null || namespace.isEmpty() || namespace.contains(NAMESPACE_SEPARATOR)) {
      throw new BadRequestException(
          "OpenHouse only supports single-level namespaces (databaseId); got: %s", namespace);
    }
  }
}
