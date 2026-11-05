package com.linkedin.openhouse.housetables.mock.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.housetables.api.validator.DatabasesHtsApiValidator;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class OpenHouseDatabasesValidatorTest {

  @Autowired private DatabasesHtsApiValidator databasesHtsApiValidator;

  private static DatabaseKey key(String databaseId) {
    return DatabaseKey.builder().databaseId(databaseId).build();
  }

  @Test
  public void validateGetAndDeleteEntitySuccess() {
    assertDoesNotThrow(() -> databasesHtsApiValidator.validateGetEntity(key("db_1")));
    assertDoesNotThrow(() -> databasesHtsApiValidator.validateDeleteEntity(key("Db_1")));
  }

  @Test
  public void validateGetAndDeleteEntityInvalidName() {
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesHtsApiValidator.validateGetEntity(key("")));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesHtsApiValidator.validateGetEntity(key("db-1")));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesHtsApiValidator.validateGetEntity(key("db 1")));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesHtsApiValidator.validateDeleteEntity(key("db?")));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesHtsApiValidator.validateDeleteEntity(key(null)));
  }

  @Test
  public void validateCreateEntitySuccess() {
    Map<String, String> properties = new HashMap<>();
    properties.put("a", "1");
    assertDoesNotThrow(
        () ->
            databasesHtsApiValidator.validatePutEntity(
                Database.builder().databaseId("db1").properties(properties).build()));
    assertDoesNotThrow(
        () ->
            databasesHtsApiValidator.validatePutEntity(
                Database.builder().databaseId("db1").build()));
  }

  @Test
  public void validateCreateEntityFailures() {
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesHtsApiValidator.validatePutEntity(null));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesHtsApiValidator.validatePutEntity(Database.builder().build()));
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesHtsApiValidator.validatePutEntity(
                Database.builder().databaseId("db-1").build()));
    Map<String, String> blankKey = new HashMap<>();
    blankKey.put(" ", "v");
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesHtsApiValidator.validatePutEntity(
                Database.builder().databaseId("db1").properties(blankKey).build()));
    Map<String, String> nullValue = new HashMap<>();
    nullValue.put("k", null);
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesHtsApiValidator.validatePutEntity(
                Database.builder().databaseId("db1").properties(nullValue).build()));
  }

  @Test
  public void validateUpdatePropertiesSuccess() {
    Map<String, String> updates = new HashMap<>();
    updates.put("a", "1");
    assertDoesNotThrow(
        () ->
            databasesHtsApiValidator.validateUpdateProperties(
                key("db1"),
                UpdateDatabasePropertiesRequestBody.builder()
                    .updates(updates)
                    .removals(Collections.singletonList("b"))
                    .build()));
    assertDoesNotThrow(
        () ->
            databasesHtsApiValidator.validateUpdateProperties(
                key("db1"), UpdateDatabasePropertiesRequestBody.builder().build()));
  }

  @Test
  public void validateUpdatePropertiesFailures() {
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesHtsApiValidator.validateUpdateProperties(
                key("db-1"), UpdateDatabasePropertiesRequestBody.builder().build()));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesHtsApiValidator.validateUpdateProperties(key("db1"), null));
    Map<String, String> blankKey = new HashMap<>();
    blankKey.put("", "v");
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesHtsApiValidator.validateUpdateProperties(
                key("db1"),
                UpdateDatabasePropertiesRequestBody.builder().updates(blankKey).build()));
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesHtsApiValidator.validateUpdateProperties(
                key("db1"),
                UpdateDatabasePropertiesRequestBody.builder()
                    .removals(Collections.singletonList(" "))
                    .build()));
  }

  @Test
  public void unsupportedOperations() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> databasesHtsApiValidator.validateGetEntities(Database.builder().build()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> databasesHtsApiValidator.validateRenameEntity(key("a"), key("b")));
  }
}
