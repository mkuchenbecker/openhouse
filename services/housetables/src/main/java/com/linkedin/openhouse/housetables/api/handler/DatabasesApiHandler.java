package com.linkedin.openhouse.housetables.api.handler;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.housetables.api.spec.response.EntityResponseBody;
import com.linkedin.openhouse.housetables.api.spec.response.UpdateDatabasePropertiesResponseBody;

/**
 * Interface layer between REST and the HouseTables backend for the database entity. Unlike {@link
 * HouseTablesApiHandler}, the database entity is create-only (no upsert), has no list or rename
 * operation, and supports a sparse property update, so it carries its own contract.
 */
public interface DatabasesApiHandler {

  /**
   * Function to create a database row. The UUID is generated server-side.
   *
   * @param database The database to create; {@code databaseUuid} and {@code creationTime} are
   *     ignored.
   * @return the created row as part of response body that would be returned to the client.
   */
  ApiResponse<EntityResponseBody<Database>> createEntity(Database database);

  /**
   * Function to GET a database row given its key.
   *
   * @param key The key object to identify the row to obtain.
   * @return the row as part of response body that would be returned to the client.
   */
  ApiResponse<EntityResponseBody<Database>> getEntity(DatabaseKey key);

  /**
   * Function to delete a database row given its key. Table rows in the database are untouched.
   *
   * @param key The key object to identify the row to delete.
   * @return empty response body.
   */
  ApiResponse<Void> deleteEntity(DatabaseKey key);

  /**
   * Function to apply a sparse property update to a database row.
   *
   * @param key The key object to identify the row to update.
   * @param updateDatabasePropertiesRequestBody keys to set and keys to remove.
   * @return the keys that were updated, removed and missing.
   */
  ApiResponse<UpdateDatabasePropertiesResponseBody> updateProperties(
      DatabaseKey key, UpdateDatabasePropertiesRequestBody updateDatabasePropertiesRequestBody);
}
