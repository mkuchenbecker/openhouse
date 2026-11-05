package com.linkedin.openhouse.tables.api.validator;

import com.linkedin.openhouse.tables.api.spec.v0.request.CreateDatabaseRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateDatabasePropertiesRequestBody;

public interface DatabasesApiValidator {
  /**
   * Function to validate a request to update aclPolicies on a Database Resource with a given
   * databaseId
   *
   * @param databaseId
   * @param updateAclPoliciesRequestBody
   * @throws com.linkedin.openhouse.common.exception.RequestValidationFailureException if request is
   *     invalid
   */
  void validateUpdateAclPolicies(
      String databaseId, UpdateAclPoliciesRequestBody updateAclPoliciesRequestBody);

  /**
   * Function to validate a request to get aclPolicies on a Database Resource with a given
   * databaseId
   *
   * @param databaseId
   */
  void validateGetAclPolicies(String databaseId);

  /**
   * Function to validate a request to get all Database Resources with pagination and sorting
   *
   * @param page
   * @param size
   * @param sortBy
   */
  void validateGetAllDatabases(int page, int size, String sortBy);

  /**
   * Function to validate a request to create a Database entity.
   *
   * @param createDatabaseRequestBody
   * @throws com.linkedin.openhouse.common.exception.RequestValidationFailureException if the name
   *     or a property key is invalid
   */
  void validateCreateDatabase(CreateDatabaseRequestBody createDatabaseRequestBody);

  /**
   * Function to validate a request to load a Database entity.
   *
   * @param databaseId
   */
  void validateGetDatabase(String databaseId);

  /**
   * Function to validate a request to delete a Database entity.
   *
   * @param databaseId
   */
  void validateDeleteDatabase(String databaseId);

  /**
   * Function to validate a sparse property update on a Database entity.
   *
   * @param databaseId
   * @param updateDatabasePropertiesRequestBody
   * @throws com.linkedin.openhouse.common.exception.RequestValidationFailureException if the name
   *     or a property key is invalid
   * @throws com.linkedin.openhouse.common.exception.UnprocessableEntityException if a key is
   *     present in both {@code updates} and {@code removals}
   */
  void validateUpdateDatabaseProperties(
      String databaseId, UpdateDatabasePropertiesRequestBody updateDatabasePropertiesRequestBody);
}
