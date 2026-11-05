package com.linkedin.openhouse.housetables.api.validator.impl;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.ALPHA_NUM_UNDERSCORE_ERROR_MSG;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX;

import com.linkedin.openhouse.common.api.validator.ApiValidatorUtil;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.housetables.api.validator.DatabasesHtsApiValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Class implementing validations for all /hts/databases REST endpoints. Only syntactic checks live
 * here; the semantic check that a key is not in both {@code updates} and {@code removals} is made
 * by the service so it is enforced regardless of the entry point.
 */
@Component
public class OpenHouseDatabasesHtsApiValidator implements DatabasesHtsApiValidator {

  @Autowired private Validator validator;

  @Override
  public void validateGetEntity(DatabaseKey databaseKey) {
    List<String> validationFailures = new ArrayList<>();
    validateDatabaseId(
        databaseKey == null ? null : databaseKey.getDatabaseId(), validationFailures);
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  @Override
  public void validateDeleteEntity(DatabaseKey databaseKey) {
    // Validation is identical to GetEntity.
    validateGetEntity(databaseKey);
  }

  @Override
  public void validateGetEntities(Database database) {
    throw new UnsupportedOperationException(
        "Listing databases through /hts/databases is unsupported");
  }

  @Override
  public void validateGetEntities(Database database, int page, int size, String sortBy) {
    throw new UnsupportedOperationException(
        "Listing databases through /hts/databases is unsupported");
  }

  @Override
  public void validatePutEntity(Database database) {
    List<String> validationFailures = new ArrayList<>();
    if (database == null) {
      throw new RequestValidationFailureException("entity : cannot be empty");
    }
    for (ConstraintViolation<Database> violation : validator.validate(database)) {
      validationFailures.add(
          String.format("%s : %s", ApiValidatorUtil.getField(violation), violation.getMessage()));
    }
    validatePropertyKeys(database.getProperties(), "properties", validationFailures);
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  @Override
  public void validateRenameEntity(DatabaseKey fromKey, DatabaseKey toKey) {
    throw new UnsupportedOperationException("Renaming a database is unsupported");
  }

  @Override
  public void validateUpdateProperties(
      DatabaseKey databaseKey,
      UpdateDatabasePropertiesRequestBody updateDatabasePropertiesRequestBody) {
    validateGetEntity(databaseKey);
    List<String> validationFailures = new ArrayList<>();
    if (updateDatabasePropertiesRequestBody == null) {
      throw new RequestValidationFailureException("request body : cannot be empty");
    }
    validatePropertyKeys(
        updateDatabasePropertiesRequestBody.getUpdates(), "updates", validationFailures);
    List<String> removals = updateDatabasePropertiesRequestBody.getRemovals();
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
  }

  private void validateDatabaseId(String databaseId, List<String> validationFailures) {
    if (StringUtils.isEmpty(databaseId)) {
      validationFailures.add("databaseId : cannot be empty");
    } else if (!databaseId.matches(ALPHA_NUM_UNDERSCORE_REGEX)) {
      validationFailures.add(
          String.format("databaseId provided: %s, %s", databaseId, ALPHA_NUM_UNDERSCORE_ERROR_MSG));
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
