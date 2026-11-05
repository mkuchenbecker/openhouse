package com.linkedin.openhouse.housetables.api.validator;

import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.request.UpdateDatabasePropertiesRequestBody;

/**
 * Validations for the /hts/databases REST endpoints. Extends the generic {@link
 * HouseTablesApiValidator} so the database entity follows the same validator convention as other
 * HTS entities, and adds validation of the sparse property update.
 */
public interface DatabasesHtsApiValidator extends HouseTablesApiValidator<DatabaseKey, Database> {

  /**
   * Function to validate a request to apply a sparse property update to a database row.
   *
   * @param key The key object to identify the row to update.
   * @param updateDatabasePropertiesRequestBody keys to set and keys to remove.
   * @throws com.linkedin.openhouse.common.exception.RequestValidationFailureException if the
   *     request is syntactically invalid.
   */
  void validateUpdateProperties(
      DatabaseKey key, UpdateDatabasePropertiesRequestBody updateDatabasePropertiesRequestBody);
}
