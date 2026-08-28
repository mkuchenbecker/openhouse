package com.linkedin.openhouse.housetables.api.validator.impl;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.*;

import com.linkedin.openhouse.common.api.validator.ApiValidatorUtil;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.housetables.api.spec.model.UserTable;
import com.linkedin.openhouse.housetables.api.spec.model.UserTableKey;
import com.linkedin.openhouse.housetables.api.validator.HouseTablesApiValidator;
import java.util.ArrayList;
import java.util.List;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Class implementing validations for all /hts/tables REST endpoints. */
@Component
public class OpenHouseUserTableHtsApiValidator
    implements HouseTablesApiValidator<UserTableKey, UserTable> {

  /**
   * Storage bounds, from the DDL this service persists into ({@code
   * services/housetables/ddl/0000__baseline.sql}: {@code database_id}, {@code table_id} and {@code
   * storage_type} are {@code VARCHAR(128)}, {@code metadata_location} is {@code VARCHAR(512)}).
   * Enforced at ingress on every field a write persists — and on the {@code databaseId} query
   * filter, which addresses the same key column — so an over-length value is the client's 400, not
   * a database integrity violation misreported as a concurrent modification or a bare server
   * failure. A read-side {@code tableId} LIKE pattern is deliberately not bounded here: it is a
   * predicate, not a stored value, so the write bound does not apply to it.
   *
   * <p>Lengths are compared in Java's UTF-16 code units while MySQL counts characters; a
   * supplementary character costs two units here and one there, so this check is deliberately the
   * conservative side of the column bound, never the permissive one.
   */
  static final int MAX_IDENTIFIER_LENGTH = 128;

  static final int MAX_METADATA_LOCATION_LENGTH = 512;

  private static final String IDENTIFIER_TOO_LONG_MSG =
      "%s provided has %d characters, exceeding the maximum of %d";

  @Autowired private Validator validator;

  @Override
  public void validateGetEntity(UserTableKey userTableKey) {
    List<String> validationFailures = new ArrayList<>();
    if (!userTableKey.getDatabaseId().matches(ALPHA_NUM_UNDERSCORE_REGEX)) {
      validationFailures.add(
          String.format(
              "databaseId provided: %s, %s",
              userTableKey.getDatabaseId(), ALPHA_NUM_UNDERSCORE_ERROR_MSG));
    }
    if (!userTableKey.getTableId().matches(ALPHA_NUM_UNDERSCORE_REGEX)) {
      validationFailures.add(
          String.format(
              "tableId provided: %s, %s",
              userTableKey.getTableId(), ALPHA_NUM_UNDERSCORE_ERROR_MSG));
    }
    validateLength(
        "databaseId", userTableKey.getDatabaseId(), MAX_IDENTIFIER_LENGTH, validationFailures);
    validateLength("tableId", userTableKey.getTableId(), MAX_IDENTIFIER_LENGTH, validationFailures);
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  private static void validateLength(
      String field, String value, int maxLength, List<String> validationFailures) {
    if (value != null && value.length() > maxLength) {
      validationFailures.add(
          String.format(IDENTIFIER_TOO_LONG_MSG, field, value.length(), maxLength));
    }
  }

  @Override
  public void validateDeleteEntity(UserTableKey userTableKey) {
    // Validation is similar to GetEntity.
    validateGetEntity(userTableKey);
  }

  @Override
  public void validateGetEntities(UserTable userTable) {
    List<String> validationFailures = new ArrayList<>();
    validateUserTable(userTable, validationFailures);
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  @Override
  public void validateGetEntities(UserTable userTable, int page, int size, String sortBy) {
    List<String> validationFailures = new ArrayList<>();
    validateUserTable(userTable, validationFailures);
    ApiValidatorUtil.validatePageable(page, size, sortBy, validationFailures);
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  @Override
  public void validatePutEntity(UserTable userTable) {
    List<String> validationFailures = new ArrayList<>();

    for (ConstraintViolation<UserTable> violation : validator.validate(userTable)) {
      validationFailures.add(
          String.format("%s : %s", ApiValidatorUtil.getField(violation), violation.getMessage()));
    }
    validateLength(
        "databaseId", userTable.getDatabaseId(), MAX_IDENTIFIER_LENGTH, validationFailures);
    validateLength("tableId", userTable.getTableId(), MAX_IDENTIFIER_LENGTH, validationFailures);
    validateLength(
        "metadataLocation",
        userTable.getMetadataLocation(),
        MAX_METADATA_LOCATION_LENGTH,
        validationFailures);
    validateLength(
        "storageType", userTable.getStorageType(), MAX_IDENTIFIER_LENGTH, validationFailures);

    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  /** The rename writes exactly one non-key field, bounded like every other persisted field. */
  @Override
  public void validateRenameEntity(
      UserTableKey fromUserTableKey, UserTableKey toUserTableKey, String metadataLocation) {
    validateRenameKeys(fromUserTableKey, toUserTableKey);
    List<String> validationFailures = new ArrayList<>();
    validateLength(
        "metadataLocation", metadataLocation, MAX_METADATA_LOCATION_LENGTH, validationFailures);
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  /** The key half of the rename validation, shared by no other entry point. */
  private void validateRenameKeys(UserTableKey fromUserTableKey, UserTableKey toUserTableKey) {
    validateGetEntity(fromUserTableKey);
    validateGetEntity(toUserTableKey);
    List<String> validationFailures = new ArrayList<>();
    if (fromUserTableKey.getDatabaseId().equalsIgnoreCase(toUserTableKey.getDatabaseId())
        && fromUserTableKey.getTableId().equalsIgnoreCase(toUserTableKey.getTableId())) {
      validationFailures.add(
          String.format(
              "Cannot rename a table to the same current db name and table name: %s",
              fromUserTableKey));
    }
    // Currently do not support cross database rename
    if (!fromUserTableKey.getDatabaseId().equalsIgnoreCase(toUserTableKey.getDatabaseId())) {
      validationFailures.add(
          String.format(
              "Cross database rename is not supported: %s to %s",
              fromUserTableKey, toUserTableKey));
    }
    if (!validationFailures.isEmpty()) {
      throw new RequestValidationFailureException(validationFailures);
    }
  }

  private void validateUserTable(UserTable userTable, List<String> validationFailures) {
    // This will be removed when we start to support general filters
    if (!(userTable.getTableVersion() == null
        && userTable.getMetadataLocation() == null
        && userTable.getStorageType() == null
        && userTable.getCreationTime() == null)) {
      validationFailures.add("Only databaseId and tableId are supported for the query");
    }

    if (userTable.getDatabaseId() != null
        && !userTable.getDatabaseId().matches(ALPHA_NUM_UNDERSCORE_REGEX)) {
      validationFailures.add(
          String.format(
              "databaseId provided: %s, %s",
              userTable.getDatabaseId(), ALPHA_NUM_UNDERSCORE_ERROR_MSG));
    }
    // databaseId addresses the key column exactly, so it carries the storage bound; the tableId
    // filter is a LIKE pattern (a predicate, not a stored value) and is deliberately unbounded
    // here — the search regex above still governs its shape.
    validateLength(
        "databaseId", userTable.getDatabaseId(), MAX_IDENTIFIER_LENGTH, validationFailures);

    if (userTable.getTableId() != null) {
      if (userTable.getDatabaseId() == null) {
        validationFailures.add("tableId cannot be provided without databaseId");
      }

      if (!userTable.getTableId().matches(ALPHA_NUM_UNDERSCORE_PATTERN_SEARCH_REGEX)) {
        validationFailures.add(
            String.format(
                "tableId provided: %s, %s",
                userTable.getTableId(), ALPHA_NUM_UNDERSCORE_PATTERN_SEARCH_ERROR_MSG));
      }
    }
  }
}
