package com.linkedin.openhouse.tables.mock.api;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.MAX_VIEW_SCHEMA_BYTES;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.MAX_VIEW_SQL_BYTES;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.VIEW_SOURCE_DIALECT_SUMMARY_KEY;

import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.exception.ViewRequestValidationFailureException;
import com.linkedin.openhouse.tables.model.IcebergRestViewFixtures;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.ImmutableCreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.types.Types;
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
                IcebergRestViewFixtures.representation(
                    "trino", "SELECT " + SECRET_SQL_MARKER + " FROM t")));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, request));
    Assertions.assertEquals(ViewErrorCode.UNSUPPORTED_VIEW_DIALECT, exception.getErrorCode());
    Assertions.assertTrue(exception.getMessage().contains("supported dialects"));
    Assertions.assertFalse(
        exception.getMessage().contains(SECRET_SQL_MARKER),
        "No fragment of the submitted SQL may be echoed into a validation message.");
  }

  /**
   * Case-insensitive dialect acceptance is deliberate: {@code SPARK} and {@code spark} name the
   * same engine, and the configured set is compared in lowercase.
   */
  @Test
  public void upperCasedSupportedDialectIsAccepted() {
    Assertions.assertDoesNotThrow(
        () ->
            validator.validateCreateView(
                DB,
                IcebergRestViewFixtures.createViewRequest(
                    IcebergRestViewFixtures.viewVersionWithRepresentations(
                        IcebergRestViewFixtures.representation("SPARK", "SELECT 1")))));
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

  /** Distinctive marker for the no-echo pins: appears in submitted SQL, must never leak out. */
  private static final String SECRET_SQL_MARKER = "secret_validator_sql_marker";

  /** The SQL size ceiling is counted in UTF-8 bytes, not UTF-16 characters. */
  @Test
  public void createWithOversizedSqlFailsOnByteCount() {
    String prefix = "SELECT " + SECRET_SQL_MARKER + " FROM t ";
    char[] padding = new char[(MAX_VIEW_SQL_BYTES - prefix.length()) / 2 + 1];
    Arrays.fill(padding, 'é'); // two UTF-8 bytes per character
    CreateViewRequest oversized =
        IcebergRestViewFixtures.createViewRequest(
            IcebergRestViewFixtures.viewVersionWithRepresentations(
                IcebergRestViewFixtures.representation("spark", prefix + new String(padding))));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, oversized));
    Assertions.assertTrue(exception.getMessage().contains("UTF-8"), exception.getMessage());
    Assertions.assertFalse(
        exception.getMessage().contains(SECRET_SQL_MARKER),
        "No fragment of the submitted SQL may be echoed into a validation message.");

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

  // ---------------------------------------------------------------------------------------------
  // Schema size ceiling
  // ---------------------------------------------------------------------------------------------

  /**
   * A schema whose canonical serialized form is exactly {@code targetBytes} UTF-8 bytes: the
   * document overhead is measured once with a one-character field name, then the name is padded
   * (ASCII, so characters equal bytes) to hit the target.
   */
  private static Schema schemaWithSerializedSize(int targetBytes) {
    Schema probe = new Schema(Types.NestedField.required(1, "x", Types.StringType.get()));
    int overhead =
        SchemaParser.toJson(probe).getBytes(StandardCharsets.UTF_8).length - "x".length();
    char[] name = new char[targetBytes - overhead];
    Arrays.fill(name, 'a');
    Schema schema =
        new Schema(Types.NestedField.required(1, new String(name), Types.StringType.get()));
    Assertions.assertEquals(
        targetBytes,
        SchemaParser.toJson(schema).getBytes(StandardCharsets.UTF_8).length,
        "Precondition: the padded schema must serialize to exactly the target size.");
    return schema;
  }

  /** The schema ceiling mirrors the SQL ceiling: over-limit rejected, at-limit accepted. */
  @Test
  public void createWithOversizedSchemaFailsWithTheSchemaCode() {
    CreateViewRequest oversized =
        ImmutableCreateViewRequest.builder()
            .from(IcebergRestViewFixtures.createViewRequest())
            .schema(schemaWithSerializedSize(MAX_VIEW_SCHEMA_BYTES + 1))
            .build();

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, oversized));
    Assertions.assertEquals(ViewErrorCode.UNSUPPORTED_VIEW_SCHEMA, exception.getErrorCode());
    Assertions.assertTrue(exception.getMessage().contains("UTF-8"), exception.getMessage());

    Assertions.assertDoesNotThrow(
        () ->
            validator.validateCreateView(
                DB,
                ImmutableCreateViewRequest.builder()
                    .from(IcebergRestViewFixtures.createViewRequest())
                    .schema(schemaWithSerializedSize(MAX_VIEW_SCHEMA_BYTES))
                    .build()));
  }

  /**
   * Error-code precedence: when a schema rule and a dialect rule both fail, the thrown code is the
   * schema one — schema, then dialect, then the generic definition code.
   */
  @Test
  public void schemaFailureTakesPrecedenceOverDialectFailure() {
    CreateViewRequest doublyBroken =
        ImmutableCreateViewRequest.builder()
            .from(
                IcebergRestViewFixtures.createViewRequest(
                    IcebergRestViewFixtures.viewVersionWithRepresentations(
                        IcebergRestViewFixtures.representation("trino", "SELECT 1"))))
            .schema(schemaWithSerializedSize(MAX_VIEW_SCHEMA_BYTES + 1))
            .build();

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, doublyBroken));
    Assertions.assertEquals(ViewErrorCode.UNSUPPORTED_VIEW_SCHEMA, exception.getErrorCode());
    // Both failures are still reported; precedence selects only the code.
    Assertions.assertTrue(exception.getMessage().contains("supported dialects"));
  }

  /** The commit path's add-schema update is held to the same ceiling. */
  @Test
  public void replaceAddSchemaIsHeldToTheSchemaSizeCeiling() {
    UpdateTableRequest request =
        UpdateTableRequest.create(
            null,
            Collections.emptyList(),
            Collections.singletonList(
                new MetadataUpdate.AddSchema(
                    schemaWithSerializedSize(MAX_VIEW_SCHEMA_BYTES + 1), 1)));

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateReplaceView(DB, VIEW, request));
    Assertions.assertEquals(ViewErrorCode.UNSUPPORTED_VIEW_SCHEMA, exception.getErrorCode());
  }

  /**
   * F7's missing leg: a source-dialect summary entry naming a dialect this deployment does not
   * support is rejected even when the supplied representation's dialect is fine.
   */
  @Test
  public void sourceDialectSummaryNamingAnUnsupportedDialectFails() {
    Map<String, String> summary = new LinkedHashMap<>();
    summary.put(VIEW_SOURCE_DIALECT_SUMMARY_KEY, "flink");
    CreateViewRequest request =
        IcebergRestViewFixtures.createViewRequest(
            ImmutableViewVersion.builder()
                .from(
                    IcebergRestViewFixtures.viewVersionWithRepresentations(
                        IcebergRestViewFixtures.representation("spark", "SELECT 1")))
                .summary(summary)
                .build());

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, request));
    Assertions.assertEquals(ViewErrorCode.UNSUPPORTED_VIEW_DIALECT, exception.getErrorCode());
    Assertions.assertTrue(
        exception.getMessage().contains("must be one of the supported dialects"),
        exception.getMessage());
  }

  /**
   * The reserved-prefix check is case-sensitive by design (inherited from the internal catalog's
   * canonical predicate): {@code OpenHouse.myTeam} is a legal user property.
   */
  @Test
  public void differentlyCasedOpenHousePrefixedPropertyKeyIsAccepted() {
    Assertions.assertDoesNotThrow(
        () ->
            validator.validateCreateView(
                DB,
                ImmutableCreateViewRequest.builder()
                    .from(IcebergRestViewFixtures.createViewRequest())
                    .properties(Collections.singletonMap("OpenHouse.myTeam", "identity"))
                    .build()));
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
    // The boundary itself is legal: exactly 128 characters passes.
    char[] atLimit = new char[128];
    Arrays.fill(atLimit, 'a');
    Assertions.assertDoesNotThrow(() -> validator.validateViewIdentifier(DB, new String(atLimit)));
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
