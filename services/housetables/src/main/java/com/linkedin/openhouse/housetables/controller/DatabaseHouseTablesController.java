package com.linkedin.openhouse.housetables.controller;

import com.linkedin.openhouse.housetables.api.handler.DatabaseHtsApiHandler;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.request.CreateUpdateEntityRequestBody;
import com.linkedin.openhouse.housetables.api.spec.response.EntityResponseBody;
import com.linkedin.openhouse.housetables.api.spec.response.GetAllEntityResponseBody;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for the /hts/databases endpoints. Databases are the stored form of a namespace; the
 * Tables Service owns the namespace API contract, House Tables owns the record.
 */
@RestController
public class DatabaseHouseTablesController {

  private static final String HTS_DATABASES_GENERAL_ENDPOINT = "/hts/databases";
  private static final String HTS_DATABASES_QUERY_ENDPOINT = "/hts/databases/query";

  @Autowired private DatabaseHtsApiHandler databaseHtsApiHandler;

  @Operation(
      summary = "Get a Database identified by databaseId.",
      description = "Returns a Database House Table entry identified by databaseId.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Database GET: OK"),
        @ApiResponse(responseCode = "400", description = "Database GET: BAD_REQUEST"),
        @ApiResponse(responseCode = "404", description = "Database GET: DB_NOT_FOUND")
      })
  @GetMapping(
      value = HTS_DATABASES_GENERAL_ENDPOINT,
      produces = {"application/json"})
  public ResponseEntity<EntityResponseBody<Database>> getDatabase(
      @RequestParam(value = "databaseId") String databaseId) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<EntityResponseBody<Database>> apiResponse =
        databaseHtsApiHandler.getEntity(DatabaseKey.builder().databaseId(databaseId).build());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "List all Databases.",
      description = "Returns every Database House Table entry, ordered by databaseId.",
      tags = {"Database"})
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Database GET: OK")})
  @GetMapping(
      value = HTS_DATABASES_QUERY_ENDPOINT,
      produces = {"application/json"})
  public ResponseEntity<GetAllEntityResponseBody<Database>> getDatabases() {
    com.linkedin.openhouse.common.api.spec.ApiResponse<GetAllEntityResponseBody<Database>>
        apiResponse = databaseHtsApiHandler.getEntities();
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Create or update a Database.",
      description = "Creates or replaces the Database House Table entry for databaseId.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Database PUT: UPDATED"),
        @ApiResponse(responseCode = "201", description = "Database PUT: CREATED"),
        @ApiResponse(responseCode = "400", description = "Database PUT: BAD_REQUEST"),
        @ApiResponse(responseCode = "409", description = "Database PUT: CONFLICT")
      })
  @PutMapping(
      value = HTS_DATABASES_GENERAL_ENDPOINT,
      produces = {"application/json"},
      consumes = {"application/json"})
  public ResponseEntity<EntityResponseBody<Database>> putDatabase(
      @RequestBody CreateUpdateEntityRequestBody<Database> createUpdateEntityRequestBody) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<EntityResponseBody<Database>> apiResponse =
        databaseHtsApiHandler.putEntity(createUpdateEntityRequestBody.getEntity());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Delete a Database.",
      description = "Deletes the Database House Table entry identified by databaseId.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Database DELETE: NO_CONTENT"),
        @ApiResponse(responseCode = "400", description = "Database DELETE: BAD_REQUEST"),
        @ApiResponse(responseCode = "404", description = "Database DELETE: DB_NOT_FOUND"),
        @ApiResponse(responseCode = "409", description = "Database DELETE: CONFLICT")
      })
  @DeleteMapping(value = HTS_DATABASES_GENERAL_ENDPOINT)
  public ResponseEntity<Void> deleteDatabase(
      @RequestParam(value = "databaseId") String databaseId,
      @RequestParam(value = "version", required = false) Long version) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<Void> apiResponse =
        databaseHtsApiHandler.deleteEntity(
            DatabaseKey.builder().databaseId(databaseId).version(version).build());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }
}
