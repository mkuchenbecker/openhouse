package com.linkedin.openhouse.tables.api.handler;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateDatabaseRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAclPoliciesResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAllDatabasesResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetDatabaseResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.UpdateDatabasePropertiesResponseBody;

public interface DatabasesApiHandler {
  /**
   * Function to Get all Database Resources in openhouse
   *
   * @return Response containing a list of Databases that would be returned to the client.
   */
  ApiResponse<GetAllDatabasesResponseBody> getAllDatabases();

  /**
   * Function to Get a Page of Database Resources in openhouse
   *
   * @param page
   * @param size
   * @param sortBy
   * @return Response containing a Page of Databases that would be returned to the client.
   */
  ApiResponse<GetAllDatabasesResponseBody> getAllDatabases(int page, int size, String sortBy);

  /**
   * Function to update aclPolicies on a Database Resource identified by databaseId.
   *
   * @param databaseId
   * @param updateAclPoliciesRequestBody
   * @param actingPrincipal
   * @return empty body on successful update
   */
  ApiResponse<Void> updateDatabaseAclPolicies(
      String databaseId,
      UpdateAclPoliciesRequestBody updateAclPoliciesRequestBody,
      String actingPrincipal);

  /**
   * Function to list aclPolicies on a Database Resource identified by databaseId.
   *
   * @param databaseId
   * @param actingPrincipal
   * @return the acl policies defined on the database that would be returned to the client
   */
  ApiResponse<GetAclPoliciesResponseBody> getDatabaseAclPolicies(
      String databaseId, String actingPrincipal);

  /**
   * Function to create a Database entity (UUID, name, properties).
   *
   * @param createDatabaseRequestBody name and optional properties
   * @param actingPrincipal
   * @return the created database, carrying its server-generated UUID
   */
  ApiResponse<GetDatabaseResponseBody> createDatabase(
      CreateDatabaseRequestBody createDatabaseRequestBody, String actingPrincipal);

  /**
   * Function to load a Database entity identified by databaseId.
   *
   * @param databaseId
   * @param actingPrincipal
   * @return the database entity with its UUID and properties
   */
  ApiResponse<GetDatabaseResponseBody> getDatabase(String databaseId, String actingPrincipal);

  /**
   * Function to delete a Database entity identified by databaseId. Tables are untouched.
   *
   * @param databaseId
   * @param actingPrincipal
   * @return empty body on successful delete
   */
  ApiResponse<Void> deleteDatabase(String databaseId, String actingPrincipal);

  /**
   * Function to apply a sparse property update to a Database entity identified by databaseId.
   *
   * @param databaseId
   * @param updateDatabasePropertiesRequestBody keys to set and keys to remove
   * @param actingPrincipal
   * @return the keys that were updated, removed and missing
   */
  ApiResponse<UpdateDatabasePropertiesResponseBody> updateDatabaseProperties(
      String databaseId,
      UpdateDatabasePropertiesRequestBody updateDatabasePropertiesRequestBody,
      String actingPrincipal);
}
