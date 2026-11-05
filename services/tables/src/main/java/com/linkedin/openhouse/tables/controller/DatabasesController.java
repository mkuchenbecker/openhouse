package com.linkedin.openhouse.tables.controller;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.*;

import com.linkedin.openhouse.tables.api.handler.DatabasesApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateDatabaseRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAclPoliciesResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAllDatabasesResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetDatabaseResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.UpdateDatabasePropertiesResponseBody;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DatabasesController is the controller class for Databases API. This class is responsible for
 * handling all the API requests that are specific to databases. The class uses DatabasesApiHandler
 * to delegate the request to the service layer.
 *
 * <p>The database entity routes (create, get, delete, update properties) follow the Iceberg REST
 * catalog namespace semantics for request and response bodies and status codes, mounted on the
 * OpenHouse {@code /v1/databases} path family. The list routes are unchanged and continue to derive
 * databases from table metadata.
 */
@RestController
public class DatabasesController {
  @Autowired DatabasesApiHandler databasesApiHandler;

  @Operation(
      summary = "List all Databases",
      description = "Returns a list of Database resources.",
      tags = {"Database"})
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Database GET_ALL: OK")})
  @GetMapping(
      value = {"/v0/databases", "/v1/databases"},
      produces = {"application/json"})
  public ResponseEntity<GetAllDatabasesResponseBody> getAllDatabases() {
    com.linkedin.openhouse.common.api.spec.ApiResponse<GetAllDatabasesResponseBody> apiResponse =
        databasesApiHandler.getAllDatabases();

    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Paginated list of all Databases",
      description = "Returns a Page of Database resources.",
      tags = {"Database"})
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Database GET_ALL: OK")})
  @GetMapping(
      value = {"/v2/databases"},
      produces = {"application/json"})
  public ResponseEntity<GetAllDatabasesResponseBody> getAllDatabasesPaginated(
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size,
      @RequestParam(required = false) String sortBy) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<GetAllDatabasesResponseBody> apiResponse =
        databasesApiHandler.getAllDatabases(page, size, sortBy);

    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Create a Database",
      description =
          "Creates a Database entity identified by databaseId with a server-generated UUID and"
              + " optional properties. Creating a Database entity does not create or alter any"
              + " table. Requires the SYSTEM_ADMIN privilege.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Database POST: CREATED"),
        @ApiResponse(responseCode = "400", description = "Database POST: BAD_REQUEST"),
        @ApiResponse(responseCode = "401", description = "Database POST: UNAUTHORIZED"),
        @ApiResponse(responseCode = "403", description = "Database POST: FORBIDDEN"),
        @ApiResponse(responseCode = "409", description = "Database POST: DB_EXISTS"),
        @ApiResponse(responseCode = "503", description = "Database POST: SERVICE_UNAVAILABLE")
      })
  @PostMapping(
      value = {"/v1/databases"},
      produces = {"application/json"},
      consumes = {"application/json"})
  public ResponseEntity<GetDatabaseResponseBody> createDatabase(
      @Parameter(
              description = "Request containing the name and optional properties of the Database",
              required = true,
              schema = @Schema(implementation = CreateDatabaseRequestBody.class))
          @RequestBody
          CreateDatabaseRequestBody createDatabaseRequestBody) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<GetDatabaseResponseBody> apiResponse =
        databasesApiHandler.createDatabase(
            createDatabaseRequestBody, extractAuthenticatedUserPrincipal());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Get a Database",
      description =
          "Returns the Database entity identified by databaseId, matched ignoring case, with its"
              + " UUID and properties. A database that only exists as table metadata and has no"
              + " Database entity is NOT FOUND.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Database GET: OK"),
        @ApiResponse(responseCode = "400", description = "Database GET: BAD_REQUEST"),
        @ApiResponse(responseCode = "401", description = "Database GET: UNAUTHORIZED"),
        @ApiResponse(responseCode = "404", description = "Database GET: DB_NOT_FOUND"),
        @ApiResponse(responseCode = "503", description = "Database GET: SERVICE_UNAVAILABLE")
      })
  @GetMapping(
      value = {"/v1/databases/{databaseId}"},
      produces = {"application/json"})
  public ResponseEntity<GetDatabaseResponseBody> getDatabase(
      @Parameter(description = "Database ID", required = true) @PathVariable String databaseId) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<GetDatabaseResponseBody> apiResponse =
        databasesApiHandler.getDatabase(databaseId, extractAuthenticatedUserPrincipal());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Delete a Database",
      description =
          "Deletes the Database entity identified by databaseId. Tables in the database are left"
              + " untouched and the database is not required to be empty. Requires the SYSTEM_ADMIN"
              + " privilege.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Database DELETE: NO_CONTENT"),
        @ApiResponse(responseCode = "400", description = "Database DELETE: BAD_REQUEST"),
        @ApiResponse(responseCode = "401", description = "Database DELETE: UNAUTHORIZED"),
        @ApiResponse(responseCode = "403", description = "Database DELETE: FORBIDDEN"),
        @ApiResponse(responseCode = "404", description = "Database DELETE: DB_NOT_FOUND"),
        @ApiResponse(responseCode = "503", description = "Database DELETE: SERVICE_UNAVAILABLE")
      })
  @DeleteMapping(value = {"/v1/databases/{databaseId}"})
  public ResponseEntity<Void> deleteDatabase(
      @Parameter(description = "Database ID", required = true) @PathVariable String databaseId) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<Void> apiResponse =
        databasesApiHandler.deleteDatabase(databaseId, extractAuthenticatedUserPrincipal());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Update properties of a Database",
      description =
          "Applies a sparse update to the properties of the Database entity identified by"
              + " databaseId: keys in updates are set, keys in removals are removed, and properties"
              + " not named in the request are not modified. A key present in both updates and"
              + " removals is rejected. Requires the SYSTEM_ADMIN privilege.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Database properties POST: UPDATED"),
        @ApiResponse(responseCode = "400", description = "Database properties POST: BAD_REQUEST"),
        @ApiResponse(responseCode = "401", description = "Database properties POST: UNAUTHORIZED"),
        @ApiResponse(responseCode = "403", description = "Database properties POST: FORBIDDEN"),
        @ApiResponse(responseCode = "404", description = "Database properties POST: DB_NOT_FOUND"),
        @ApiResponse(responseCode = "409", description = "Database properties POST: CONFLICT"),
        @ApiResponse(
            responseCode = "422",
            description = "Database properties POST: UNPROCESSABLE_ENTITY"),
        @ApiResponse(
            responseCode = "503",
            description = "Database properties POST: SERVICE_UNAVAILABLE")
      })
  @PostMapping(
      value = {"/v1/databases/{databaseId}/properties"},
      produces = {"application/json"},
      consumes = {"application/json"})
  public ResponseEntity<UpdateDatabasePropertiesResponseBody> updateDatabaseProperties(
      @Parameter(description = "Database ID", required = true) @PathVariable String databaseId,
      @Parameter(
              description = "Property keys to set and property keys to remove",
              required = true,
              schema = @Schema(implementation = UpdateDatabasePropertiesRequestBody.class))
          @RequestBody
          UpdateDatabasePropertiesRequestBody updateDatabasePropertiesRequestBody) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<UpdateDatabasePropertiesResponseBody>
        apiResponse =
            databasesApiHandler.updateDatabaseProperties(
                databaseId,
                updateDatabasePropertiesRequestBody,
                extractAuthenticatedUserPrincipal());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Get AclPolicies on Database",
      description = "Returns principal to role mappings on resource identified by databaseId.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "AclPolicies GET: OK"),
        @ApiResponse(responseCode = "400", description = "AclPolicies GET: BAD_REQUEST"),
        @ApiResponse(responseCode = "401", description = "AclPolicies GET: UNAUTHORIZED"),
        @ApiResponse(responseCode = "404", description = "AclPolicies GET: DB_NOT_FOUND")
      })
  @GetMapping(
      value = {
        "/databases/{databaseId}/aclPolicies",
        "/v0/databases/{databaseId}/aclPolicies",
        "/v1/databases/{databaseId}/aclPolicies"
      },
      produces = {"application/json"})
  public ResponseEntity<GetAclPoliciesResponseBody> getDatabaseAclPolicies(
      @Parameter(description = "Database ID", required = true) @PathVariable String databaseId) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<GetAclPoliciesResponseBody> apiResponse =
        databasesApiHandler.getDatabaseAclPolicies(databaseId, extractAuthenticatedUserPrincipal());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }

  @Operation(
      summary = "Update AclPolicies on database",
      description = "Updates role for principal on database identified by databaseId",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "AclPolicies PATCH: UPDATED"),
        @ApiResponse(responseCode = "400", description = "AclPolicies PATCH: BAD_REQUEST"),
        @ApiResponse(responseCode = "401", description = "AclPolicies PATCH: UNAUTHORIZED"),
        @ApiResponse(responseCode = "403", description = "AclPolicies PATCH: FORBIDDEN"),
        @ApiResponse(responseCode = "404", description = "AclPolicies PATCH: DB_NOT_FOUND")
      })
  @PatchMapping(
      value = {"/v0/databases/{databaseId}/aclPolicies", "/v1/databases/{databaseId}/aclPolicies"},
      produces = {"application/json"})
  public ResponseEntity<Void> updateDatabaseAclPolicies(
      @Parameter(description = "Database ID", required = true) @PathVariable String databaseId,
      @Parameter(
              description = "Request containing aclPolicies of the Database to be updated",
              required = true,
              schema = @Schema(implementation = UpdateAclPoliciesRequestBody.class))
          @RequestBody
          UpdateAclPoliciesRequestBody updateAclPoliciesRequestBody) {
    com.linkedin.openhouse.common.api.spec.ApiResponse<Void> apiResponse =
        databasesApiHandler.updateDatabaseAclPolicies(
            databaseId, updateAclPoliciesRequestBody, extractAuthenticatedUserPrincipal());
    return new ResponseEntity<>(
        apiResponse.getResponseBody(), apiResponse.getHttpHeaders(), apiResponse.getHttpStatus());
  }
}
