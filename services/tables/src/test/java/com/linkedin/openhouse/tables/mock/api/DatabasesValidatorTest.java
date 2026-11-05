package com.linkedin.openhouse.tables.mock.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateDatabaseRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.tables.api.validator.DatabasesApiValidator;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class DatabasesValidatorTest {

  @Autowired private DatabasesApiValidator databasesApiValidator;

  @Test
  public void validateUpdateAclPoliciesSpecialCharacter() {
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateUpdateAclPolicies(
                "%%",
                UpdateAclPoliciesRequestBody.builder()
                    .operation(UpdateAclPoliciesRequestBody.Operation.GRANT)
                    .principal("DUMMY_USER")
                    .role("AclEditor")
                    .build()));
  }

  @Test
  public void validateUpdateAclPoliciesEmptyDatabaseId() {
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateUpdateAclPolicies(
                "",
                UpdateAclPoliciesRequestBody.builder()
                    .operation(UpdateAclPoliciesRequestBody.Operation.GRANT)
                    .principal("DUMMY_USER")
                    .role("AclEditor")
                    .build()));
  }

  @Test
  public void validateUpdateAclPoliciesEmptyValues() {
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateUpdateAclPolicies(
                "d",
                UpdateAclPoliciesRequestBody.builder()
                    .operation(UpdateAclPoliciesRequestBody.Operation.GRANT)
                    .principal("")
                    .role("AclEditor")
                    .build()));

    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateUpdateAclPolicies(
                "d",
                UpdateAclPoliciesRequestBody.builder()
                    .operation(UpdateAclPoliciesRequestBody.Operation.GRANT)
                    .principal("DUMMY_USER")
                    .role("")
                    .build()));
  }

  @Test
  public void validateUpdateAclPoliciesSuccess() {
    assertDoesNotThrow(
        () ->
            databasesApiValidator.validateUpdateAclPolicies(
                "d",
                UpdateAclPoliciesRequestBody.builder()
                    .operation(UpdateAclPoliciesRequestBody.Operation.GRANT)
                    .principal("DUMMY_USER")
                    .role("AclEditor")
                    .build()));
  }

  @Test
  public void validateGetAclPoliciesInvalidValues() {
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesApiValidator.validateGetAclPolicies(""));

    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesApiValidator.validateGetAclPolicies("d#$"));
  }

  @Test
  public void validateGetAclPoliciesSuccess() {
    assertDoesNotThrow(() -> databasesApiValidator.validateGetAclPolicies("d"));
  }

  @Test
  public void validateCreateDatabaseSuccess() {
    Map<String, String> properties = new HashMap<>();
    properties.put("a", "1");
    assertDoesNotThrow(
        () ->
            databasesApiValidator.validateCreateDatabase(
                CreateDatabaseRequestBody.builder()
                    .databaseId("d1")
                    .properties(properties)
                    .build()));
    assertDoesNotThrow(
        () ->
            databasesApiValidator.validateCreateDatabase(
                CreateDatabaseRequestBody.builder().databaseId("d1").build()));
  }

  @Test
  public void validateCreateDatabaseFailures() {
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesApiValidator.validateCreateDatabase(null));
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateCreateDatabase(
                CreateDatabaseRequestBody.builder().databaseId("").build()));
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateCreateDatabase(
                CreateDatabaseRequestBody.builder().databaseId("d-1").build()));
    Map<String, String> blankKey = new HashMap<>();
    blankKey.put(" ", "v");
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateCreateDatabase(
                CreateDatabaseRequestBody.builder().databaseId("d1").properties(blankKey).build()));
    Map<String, String> nullValue = new HashMap<>();
    nullValue.put("k", null);
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateCreateDatabase(
                CreateDatabaseRequestBody.builder()
                    .databaseId("d1")
                    .properties(nullValue)
                    .build()));
  }

  @Test
  public void validateGetAndDeleteDatabase() {
    assertDoesNotThrow(() -> databasesApiValidator.validateGetDatabase("d1"));
    assertDoesNotThrow(() -> databasesApiValidator.validateDeleteDatabase("D_1"));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesApiValidator.validateGetDatabase(""));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesApiValidator.validateGetDatabase("d.1"));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesApiValidator.validateDeleteDatabase("d 1"));
  }

  @Test
  public void validateUpdateDatabasePropertiesSuccess() {
    Map<String, String> updates = new HashMap<>();
    updates.put("a", "1");
    assertDoesNotThrow(
        () ->
            databasesApiValidator.validateUpdateDatabaseProperties(
                "d1",
                UpdateDatabasePropertiesRequestBody.builder()
                    .updates(updates)
                    .removals(Collections.singletonList("b"))
                    .build()));
    assertDoesNotThrow(
        () ->
            databasesApiValidator.validateUpdateDatabaseProperties(
                "d1", UpdateDatabasePropertiesRequestBody.builder().build()));
  }

  @Test
  public void validateUpdateDatabasePropertiesBadRequests() {
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateUpdateDatabaseProperties(
                "d-1", UpdateDatabasePropertiesRequestBody.builder().build()));
    assertThrows(
        RequestValidationFailureException.class,
        () -> databasesApiValidator.validateUpdateDatabaseProperties("d1", null));
    Map<String, String> blankKey = new HashMap<>();
    blankKey.put("", "v");
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateUpdateDatabaseProperties(
                "d1", UpdateDatabasePropertiesRequestBody.builder().updates(blankKey).build()));
    assertThrows(
        RequestValidationFailureException.class,
        () ->
            databasesApiValidator.validateUpdateDatabaseProperties(
                "d1",
                UpdateDatabasePropertiesRequestBody.builder()
                    .removals(Collections.singletonList(""))
                    .build()));
  }

  @Test
  public void validateUpdateDatabasePropertiesKeyInBothSetsIsUnprocessable() {
    Map<String, String> updates = new HashMap<>();
    updates.put("a", "1");
    assertThrows(
        UnprocessableEntityException.class,
        () ->
            databasesApiValidator.validateUpdateDatabaseProperties(
                "d1",
                UpdateDatabasePropertiesRequestBody.builder()
                    .updates(updates)
                    .removals(Collections.singletonList("a"))
                    .build()));
  }
}
