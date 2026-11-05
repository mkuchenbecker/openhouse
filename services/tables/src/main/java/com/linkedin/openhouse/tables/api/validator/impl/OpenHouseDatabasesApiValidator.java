package com.linkedin.openhouse.tables.api.validator.impl;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.ALPHA_NUM_UNDERSCORE_ERROR_MSG;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX;

import com.linkedin.openhouse.common.api.validator.ApiValidatorUtil;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateDatabaseRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.tables.api.validator.DatabasesApiValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OpenHouseDatabasesApiValidator implements DatabasesApiValidator {

  @Autowired private Validator validator;

  @SuppressWarnings("checkstyle:OperatorWrap")
  @Override
  public void validateUpdateAclPolicies(
      String databaseId, UpdateAclPoliciesRequestBody updateAclPoliciesRequestBody) {
    List<String> validationFailures = new ArrayList<>();
    for (ConstraintViolation<UpdateAclPoliciesRequestBody> violation :
        validator.validate(updateAclPoliciesRequestBody)) {
      validationFailures.add(
          String.format("%s : %s", ApiValidatorUtil.getField(violation), violation.getMessage()));
    }
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }

    // Validate databaseId
    validateDatabaseId(databaseId);
  }

  @Override
  public void validateGetAclPolicies(String databaseId) {
    // Validate databaseId
    validateDatabaseId(databaseId);
  }

  @Override
  public void validateGetAllDatabases(int page, int size, String sortBy) {
    List<String> validationFailures = new ArrayList<>();
    ApiValidatorUtil.validatePageable(page, size, sortBy, validationFailures);
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  @Override
  public void validateCreateDatabase(CreateDatabaseRequestBody createDatabaseRequestBody) {
    if (createDatabaseRequestBody == null) {
      throw new RequestValidationFailureException("request body : cannot be empty");
    }
    List<String> validationFailures = new ArrayList<>();
    for (ConstraintViolation<CreateDatabaseRequestBody> violation :
        validator.validate(createDatabaseRequestBody)) {
      validationFailures.add(
          String.format("%s : %s", ApiValidatorUtil.getField(violation), violation.getMessage()));
    }
    validatePropertyKeys(
        createDatabaseRequestBody.getProperties(), "properties", validationFailures);
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  @Override
  public void validateGetDatabase(String databaseId) {
    validateDatabaseId(databaseId);
  }

  @Override
  public void validateDeleteDatabase(String databaseId) {
    validateDatabaseId(databaseId);
  }

  @Override
  public void validateUpdateDatabaseProperties(
      String databaseId, UpdateDatabasePropertiesRequestBody updateDatabasePropertiesRequestBody) {
    validateDatabaseId(databaseId);
    if (updateDatabasePropertiesRequestBody == null) {
      throw new RequestValidationFailureException("request body : cannot be empty");
    }
    List<String> validationFailures = new ArrayList<>();
    Map<String, String> updates = updateDatabasePropertiesRequestBody.getUpdates();
    List<String> removals = updateDatabasePropertiesRequestBody.getRemovals();
    validatePropertyKeys(updates, "updates", validationFailures);
    if (removals != null) {
      for (String key : removals) {
        if (StringUtils.isBlank(key)) {
          validationFailures.add("removals : property keys cannot be empty");
          break;
        }
      }
    }
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
    // Iceberg REST catalog contract: a key in both removals and updates is 422, not 400.
    if (updates != null && removals != null) {
      for (String key : removals) {
        if (updates.containsKey(key)) {
          throw new UnprocessableEntityException(
              String.format(
                  "Property key [%s] cannot be present in both updates and removals", key));
        }
      }
    }
  }

  private void validateDatabaseId(String databaseId) {
    List<String> validationFailures = new ArrayList<>();
    if (StringUtils.isEmpty(databaseId)) {
      validationFailures.add("databaseId : Cannot be empty");
    } else if (!databaseId.matches(ALPHA_NUM_UNDERSCORE_REGEX)) {
      validationFailures.add(
          String.format("databaseId provided: %s, %s", databaseId, ALPHA_NUM_UNDERSCORE_ERROR_MSG));
    }
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  private void validatePropertyKeys(
      Map<String, String> properties, String fieldName, List<String> validationFailures) {
    if (properties == null) {
      return;
    }
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      if (StringUtils.isBlank(entry.getKey())) {
        validationFailures.add(String.format("%s : property keys cannot be empty", fieldName));
        break;
      }
      if (entry.getValue() == null) {
        validationFailures.add(
            String.format("%s : value for key %s cannot be null", fieldName, entry.getKey()));
      }
    }
  }
}
