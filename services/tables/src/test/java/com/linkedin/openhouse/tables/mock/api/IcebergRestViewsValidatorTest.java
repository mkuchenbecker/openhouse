package com.linkedin.openhouse.tables.mock.api;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.MAX_VIEW_SQL_BYTES;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.VIEW_SOURCE_DIALECT_SUMMARY_KEY;

import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.exception.ViewRequestValidationFailureException;
import com.linkedin.openhouse.tables.model.IcebergRestViewFixtures;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.ImmutableCreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Structural validation rules of the Iceberg REST views requests under the default deployment
 * configuration ({@code cluster.tables.views.supported-dialects} defaults to {@code spark}).
 * Multi-dialect behavior is pinned separately by {@link IcebergRestViewsValidatorMultiDialectTest}.
 */
@SpringBootTest
public class IcebergRestViewsValidatorTest {

  @Autowired private ViewsApiValidator validator;

  private static final String DB = IcebergRestViewFixtures.DATABASE_ID;
  private static final String VIEW = IcebergRestViewFixtures.VIEW_ID;

  // ---------------------------------------------------------------------------------------------
  // Create
  // ---------------------------------------------------------------------------------------------

  /** F7: a stock client's create — single representation, no source-dialect summary — passes. */
  @Test
  public void createWithSingleRepresentationAndNoSourceDialectSummaryPasses() {
    Assertions.assertDoesNotThrow(
        () -> validator.validateCreateView(DB, IcebergRestViewFixtures.createViewRequest()));
  }

  @Test
  public void createWithMatchingSourceDialectSummaryPasses() {
    Map<String, String> summary = new LinkedHashMap<>();
    summary.put("operation", "create");
    summary.put(VIEW_SOURCE_DIALECT_SUMMARY_KEY, "spark");
    Assertions.assertDoesNotThrow(
        () ->
            validator.validateCreateView(
                DB,
                IcebergRestViewFixtures.createViewRequest(
                    IcebergRestViewFixtures.viewVersionWithSummary(summary))));
  }

  @Test
  public void createWithUnsupportedDialectFailsWithTheDialectCode() {
    CreateViewRequest request =
        IcebergRestViewFixtures.createViewRequest(
            IcebergRestViewFixtures.viewVersionWithRepresentations(
                IcebergRestViewFixtures.representation("trino", "SELECT 1")));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, request));
    Assertions.assertEquals(ViewErrorCode.UNSUPPORTED_VIEW_DIALECT, exception.getErrorCode());
    Assertions.assertTrue(exception.getMessage().contains("supported dialects"));
  }

  @Test
  public void createWithDuplicateDialectsFailsWithTheDialectCode() {
    CreateViewRequest request =
        IcebergRestViewFixtures.createViewRequest(
            IcebergRestViewFixtures.viewVersionWithRepresentations(
                IcebergRestViewFixtures.representation("spark", "SELECT 1"),
                IcebergRestViewFixtures.representation("SPARK", "SELECT 2")));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, request));
    Assertions.assertEquals(ViewErrorCode.UNSUPPORTED_VIEW_DIALECT, exception.getErrorCode());
    Assertions.assertTrue(exception.getMessage().contains("must be unique"));
  }

  @Test
  public void createWithNoRepresentationsFails() {
    CreateViewRequest request =
        IcebergRestViewFixtures.createViewRequest(
            ImmutableViewVersion.builder()
                .from(IcebergRestViewFixtures.viewVersion())
                .representations(Collections.emptyList())
                .build());

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, request));
    Assertions.assertTrue(
        exception.getMessage().contains("at least one representation"), exception.getMessage());
  }

  /** The SQL size ceiling is counted in UTF-8 bytes, not UTF-16 characters. */
  @Test
  public void createWithOversizedSqlFailsOnByteCount() {
    char[] characters = new char[MAX_VIEW_SQL_BYTES / 2 + 1];
    Arrays.fill(characters, 'é'); // two UTF-8 bytes per character
    CreateViewRequest oversized =
        IcebergRestViewFixtures.createViewRequest(
            IcebergRestViewFixtures.viewVersionWithRepresentations(
                IcebergRestViewFixtures.representation("spark", new String(characters))));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, oversized));
    Assertions.assertTrue(exception.getMessage().contains("UTF-8"), exception.getMessage());

    char[] atLimit = new char[MAX_VIEW_SQL_BYTES / 2];
    Arrays.fill(atLimit, 'é');
    Assertions.assertDoesNotThrow(
        () ->
            validator.validateCreateView(
                DB,
                IcebergRestViewFixtures.createViewRequest(
                    IcebergRestViewFixtures.viewVersionWithRepresentations(
                        IcebergRestViewFixtures.representation("spark", new String(atLimit))))));
  }

  @Test
  public void createWithReservedPropertyKeysFails() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("openhouse.clusterId", "c");
    properties.put("policies", "{}");
    properties.put("legal", "value");
    CreateViewRequest request =
        ImmutableCreateViewRequest.builder()
            .from(IcebergRestViewFixtures.createViewRequest())
            .properties(properties)
            .build();

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, request));
    Assertions.assertTrue(exception.getMessage().contains("reserved keys"));
    Assertions.assertTrue(exception.getMessage().contains("openhouse.clusterId"));
    Assertions.assertTrue(exception.getMessage().contains("policies"));
  }

  @Test
  public void createWithABadNameAndABadNamespaceAccumulatesBothFailures() {
    CreateViewRequest request =
        ImmutableCreateViewRequest.builder()
            .from(IcebergRestViewFixtures.createViewRequest())
            .name("bad-name!")
            .build();

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView("bad-db!", request));
    Assertions.assertTrue(exception.getMessage().contains("namespace :"));
    Assertions.assertTrue(exception.getMessage().contains("name :"));
    Assertions.assertTrue(exception.getMessage().contains("; "));
  }

  // ---------------------------------------------------------------------------------------------
  // Identifiers and list parameters
  // ---------------------------------------------------------------------------------------------

  @Test
  public void viewIdentifierRulesRejectCharsetAndLengthViolations() {
    Assertions.assertDoesNotThrow(() -> validator.validateViewIdentifier(DB, VIEW));
    Assertions.assertThrows(
        ViewRequestValidationFailureException.class,
        () -> validator.validateViewIdentifier("bad-db!", VIEW));
    char[] tooLong = new char[129];
    Arrays.fill(tooLong, 'a');
    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateViewIdentifier(DB, new String(tooLong)));
    Assertions.assertTrue(exception.getMessage().contains("maximum length"));
    Assertions.assertFalse(
        exception.getMessage().contains(new String(tooLong)),
        "An over-long identifier must not be echoed into the message.");
  }

  @Test
  public void listAcceptsAbsentPagingAndAnOpaqueToken() {
    Assertions.assertDoesNotThrow(() -> validator.validateListViews(DB, null, null));
    Assertions.assertDoesNotThrow(
        () -> validator.validateListViews(DB, "any opaque token !!%%", 10));
  }

  @Test
  public void listRejectsANonPositivePageSize() {
    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateListViews(DB, null, 0));
    Assertions.assertTrue(exception.getMessage().contains("pageSize"));
  }

  // ---------------------------------------------------------------------------------------------
  // Replace (commit envelope)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void replaceWithViewRequirementsAndUpdatesPasses() {
    Assertions.assertDoesNotThrow(
        () -> validator.validateReplaceView(DB, VIEW, IcebergRestViewFixtures.commitViewRequest()));
  }

  @Test
  public void replaceWithATableRequirementFails() {
    UpdateTableRequest request =
        UpdateTableRequest.create(
            null,
            Collections.singletonList(new UpdateRequirement.AssertTableUUID("uuid")),
            Collections.singletonList(new MetadataUpdate.SetCurrentViewVersion(-1)));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateReplaceView(DB, VIEW, request));
    Assertions.assertTrue(exception.getMessage().contains("assert-view-uuid"));
  }

  @Test
  public void replaceWithATableUpdateActionFails() {
    UpdateTableRequest request =
        UpdateTableRequest.create(
            null,
            Collections.emptyList(),
            Collections.singletonList(new MetadataUpdate.SetCurrentSchema(1)));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateReplaceView(DB, VIEW, request));
    Assertions.assertTrue(exception.getMessage().contains("view-update set"));
  }

  @Test
  public void replaceWithAMismatchedBodyIdentifierFails() {
    UpdateTableRequest request =
        UpdateTableRequest.create(
            TableIdentifier.of(DB, "some_other_view"),
            Collections.emptyList(),
            Collections.singletonList(new MetadataUpdate.SetCurrentViewVersion(-1)));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateReplaceView(DB, VIEW, request));
    Assertions.assertTrue(exception.getMessage().contains("identifier"));
  }

  /** An add-view-version update is held to the same representation rules as a create. */
  @Test
  public void replaceAddViewVersionIsHeldToTheCreateRepresentationRules() {
    UpdateTableRequest request =
        UpdateTableRequest.create(
            null,
            Collections.emptyList(),
            Collections.singletonList(
                new MetadataUpdate.AddViewVersion(
                    IcebergRestViewFixtures.viewVersionWithRepresentations(
                        IcebergRestViewFixtures.representation("trino", "SELECT 1")))));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateReplaceView(DB, VIEW, request));
    Assertions.assertEquals(ViewErrorCode.UNSUPPORTED_VIEW_DIALECT, exception.getErrorCode());
  }

  @Test
  public void replaceSetPropertiesIsHeldToTheReservedKeyRules() {
    UpdateTableRequest request =
        UpdateTableRequest.create(
            null,
            Collections.emptyList(),
            Collections.singletonList(
                new MetadataUpdate.SetProperties(
                    Collections.singletonMap("openhouse.tableUri", "x"))));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateReplaceView(DB, VIEW, request));
    Assertions.assertTrue(exception.getMessage().contains("reserved keys"));
  }
}
