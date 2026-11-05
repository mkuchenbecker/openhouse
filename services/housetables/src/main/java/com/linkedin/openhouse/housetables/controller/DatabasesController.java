package com.linkedin.openhouse.housetables.controller;

import com.linkedin.openhouse.housetables.api.handler.DatabasesApiHandler;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.request.CreateUpdateEntityRequestBody;
import com.linkedin.openhouse.housetables.api.spec.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.housetables.api.spec.response.EntityResponseBody;
import com.linkedin.openhouse.housetables.api.spec.response.UpdateDatabasePropertiesResponseBody;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DatabasesController is the controller class for the /hts/databases endpoint. It persists the
 * database entity (UUID, name, properties) independently of user tables: nothing here creates,
 * loads or deletes a user table row. The Tables Service consumes this API through the generated
 * {@code DatabaseApi} client; the generated method names derive from the method names below, so
 * they are part of the contract.
 */
@RestController
public class DatabasesController {
  private static final String HTS_DATABASES_ENDPOINT = "/hts/databases";
  private static final String HTS_DATABASES_PROPERTIES_ENDPOINT = "/hts/databases/properties";

  @Autowired private DatabasesApiHandler databasesApiHandler;

  @Operation(
      summary = "Create a Database",
      description =
          "Creates a database entity identified by databaseId with a server-generated UUID. "
              + "Fails if a database with the same name already exists, ignoring case.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Database POST: CREATED"),
        @ApiResponse(responseCode = "400", description = "Database POST: BAD_REQUEST"),
        @ApiResponse(responseCode = "409", description = "Database POST: CONFLICT")
      })
  @PostMapping(
      value = HTS_DATABASES_ENDPOINT,
      produces = {"application/json"},
      consumes = {"application/json"})
  public ResponseEntity<EntityResponseBody<Database>> createDatabase(
      @Parameter(
              description = "Request containing details of the Database to be created",
              required = true)
          @RequestBody
          CreateUpdateEntityRequestBody<Database> createDatabaseRequestBody) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<EntityResponseBody<Database>> apiResponse =
        databasesApiHandler.createEntity(createDatabaseRequestBody.getEntity());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Get a Database identified by databaseId.",
      description = "Returns the database entity identified by databaseId, matched ignoring case.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Database GET: OK"),
        @ApiResponse(responseCode = "400", description = "Database GET: BAD_REQUEST"),
        @ApiResponse(responseCode = "404", description = "Database GET: DB_NOT_FOUND")
      })
  @GetMapping(
      value = HTS_DATABASES_ENDPOINT,
      produces = {"application/json"})
  public ResponseEntity<EntityResponseBody<Database>> getDatabase(
      @RequestParam(value = "databaseId") String databaseId) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<EntityResponseBody<Database>> apiResponse =
        databasesApiHandler.getEntity(DatabaseKey.builder().databaseId(databaseId).build());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Delete a Database",
      description =
          "Deletes the database entity identified by databaseId. User tables in the database are "
              + "left untouched.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Database DELETE: NO_CONTENT"),
        @ApiResponse(responseCode = "400", description = "Database DELETE: BAD_REQUEST"),
        @ApiResponse(responseCode = "404", description = "Database DELETE: DB_NOT_FOUND")
      })
  @DeleteMapping(value = HTS_DATABASES_ENDPOINT)
  public ResponseEntity<Void> deleteDatabase(
      @RequestParam(value = "databaseId") String databaseId) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<Void> apiResponse =
        databasesApiHandler.deleteEntity(DatabaseKey.builder().databaseId(databaseId).build());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Update properties of a Database",
      description =
          "Applies a sparse update to the properties of the database identified by databaseId: "
              + "keys in updates are set, keys in removals are removed, other properties are not "
              + "modified. A key present in both updates and removals is rejected.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Database properties PATCH: UPDATED"),
        @ApiResponse(responseCode = "400", description = "Database properties PATCH: BAD_REQUEST"),
        @ApiResponse(responseCode = "404", description = "Database properties PATCH: DB_NOT_FOUND"),
        @ApiResponse(responseCode = "409", description = "Database properties PATCH: CONFLICT"),
        @ApiResponse(
            responseCode = "422",
            description = "Database properties PATCH: UNPROCESSABLE_ENTITY")
      })
  @PatchMapping(
      value = HTS_DATABASES_PROPERTIES_ENDPOINT,
      produces = {"application/json"},
      consumes = {"application/json"})
  public ResponseEntity<UpdateDatabasePropertiesResponseBody> updateDatabaseProperties(
      @RequestParam(value = "databaseId") String databaseId,
      @Parameter(description = "Property keys to set and property keys to remove", required = true)
          @RequestBody
          UpdateDatabasePropertiesRequestBody updateDatabasePropertiesRequestBody) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<UpdateDatabasePropertiesResponseBody>
        apiResponse =
            databasesApiHandler.updateProperties(
                DatabaseKey.builder().databaseId(databaseId).build(),
                updateDatabasePropertiesRequestBody);
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }
}
