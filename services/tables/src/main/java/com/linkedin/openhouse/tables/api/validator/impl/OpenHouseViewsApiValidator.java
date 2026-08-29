package com.linkedin.openhouse.tables.api.validator.impl;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.MAX_VIEW_IDENTIFIER_LENGTH;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.MAX_VIEW_SCHEMA_BYTES;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.MAX_VIEW_SQL_BYTES;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.SQL_VIEW_REPRESENTATION_TYPE;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.VIEW_SOURCE_DIALECT_SUMMARY_KEY;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.api.validator.ApiValidatorUtil;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils;
import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.config.ConditionalOnIcebergViewApi;
import com.linkedin.openhouse.tables.exception.ViewRequestValidationFailureException;
import com.linkedin.openhouse.tables.exception.ViewValidationErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewRepresentation;
import org.apache.iceberg.view.ViewVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Structural validation of Iceberg REST views requests, layered on top of what Iceberg's parsers
 * already enforce (required fields, field types, update/requirement polymorphism).
 *
 * <p><b>Security invariant:</b> no message built here interpolates SQL text or schema text.
 * Messages are copied verbatim into the error response body and into service audit events, so every
 * payload-derived failure uses a fixed redacted message. Identifiers, dialects and property keys
 * are user-authored names rather than payload text and may be echoed.
 *
 * <p>SQL is opaque: nothing here parses, translates or engine-validates a view definition.
 */
@Slf4j
@ConditionalOnIcebergViewApi
@Component
public class OpenHouseViewsApiValidator implements ViewsApiValidator {

  /**
   * Exact server-owned property key. {@code InternalRepositoryUtils.POLICIES_KEY} carries the same
   * literal but is {@code protected} in another package, so it cannot be referenced from here; the
   * {@code openhouse.} prefix check does reuse its canonical predicate.
   */
  private static final String POLICIES_PROPERTY_KEY = "policies";

  /**
   * The view-update subset of the commit vocabulary. Every other {@link MetadataUpdate} action is
   * table-only and rejected on the views surface.
   *
   * <p>{@code set-location} is in the spec's view-update set but not in this one, for the same
   * reason a create request's {@code location} is rejected: this server allocates view storage and
   * a caller-chosen path would be written to verbatim. See {@link #validateServerOwnedLocation}.
   */
  private static final List<Class<? extends MetadataUpdate>> SUPPORTED_VIEW_UPDATES =
      Collections.unmodifiableList(
          java.util.Arrays.asList(
              MetadataUpdate.AssignUUID.class,
              MetadataUpdate.UpgradeFormatVersion.class,
              MetadataUpdate.AddSchema.class,
              MetadataUpdate.SetProperties.class,
              MetadataUpdate.RemoveProperties.class,
              MetadataUpdate.AddViewVersion.class,
              MetadataUpdate.SetCurrentViewVersion.class));

  @Autowired private ClusterProperties clusterProperties;

  /**
   * The dialects this deployment accepts, normalized once at startup. Configured values are
   * lowercased and deduplicated so a comma-separated property is compared the same way whatever
   * spacing or casing it was written with.
   */
  private Set<String> supportedDialects;

  /** Fixed, server-owned text: the configured set never contains caller-supplied payload. */
  private String supportedDialectsText;

  @PostConstruct
  void resolveSupportedDialects() {
    Set<String> sorted =
        clusterProperties.getViewsSupportedDialects().stream()
            .filter(StringUtils::isNotBlank)
            .map(dialect -> dialect.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));
    supportedDialects = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    supportedDialectsText = String.join(", ", supportedDialects);
    log.info("Views API accepting the configured view dialects: {}", supportedDialectsText);
  }

  @Override
  public void validateViewIdentifier(String databaseId, String viewId) {
    ViewValidationFailures failures = new ViewValidationFailures();
    validateIdentifier("namespace", databaseId, failures);
    validateIdentifier("view", viewId, failures);
    failures.throwIfPresent();
  }

  @Override
  public void validateListViews(String databaseId, String pageToken, Integer pageSize) {
    ViewValidationFailures failures = new ViewValidationFailures();
    validateIdentifier("namespace", databaseId, failures);
    if (pageSize != null && pageSize < 1) {
      failures.addGeneric(
          String.format("pageSize : provided %d, must be greater than 0", pageSize));
    }
    // pageToken is deliberately opaque: no shape validation.
    failures.throwIfPresent();
  }

  @Override
  public void validateCreateView(String databaseId, CreateViewRequest request) {
    ViewValidationFailures failures = new ViewValidationFailures();
    validateIdentifier("namespace", databaseId, failures);
    validateIdentifier("name", request.name(), failures);
    validateSchemaSize("schema", request.schema(), failures);
    validateViewVersion("view-version", request.viewVersion(), failures);
    validateProperties("properties", request.properties(), failures);
    validateServerOwnedLocation("location", request.location(), failures);
    failures.throwIfPresent();
  }

  /**
   * Storage placement is the server's to decide, so a caller-supplied {@code location} is refused
   * rather than ignored.
   *
   * <p>The spec permits a client to name a location on create, and Iceberg's reference catalog
   * honours it. This deployment cannot: it allocates every entity's location through its configured
   * storage, which is what applies the cluster's root prefix and its directory permissions, and the
   * supplied path is later written to verbatim by {@code FileIO}. Honouring an arbitrary path would
   * let a caller place a view's metadata anywhere the service account can reach — including over
   * another entity's directory. Ignoring it silently would be worse than refusing it: the view
   * would be created somewhere other than the client asked, with nothing in the response saying so.
   */
  private void validateServerOwnedLocation(
      String field, String location, ViewValidationFailures failures) {
    if (location != null) {
      failures.addGeneric(
          String.format("%s : is assigned by the server and cannot be supplied", field));
    }
  }

  @Override
  public void validateReplaceView(String databaseId, String viewId, UpdateTableRequest request) {
    ViewValidationFailures failures = new ViewValidationFailures();
    validateIdentifier("namespace", databaseId, failures);
    validateIdentifier("view", viewId, failures);
    if (request.identifier() != null
        && !(request.identifier().namespace().levels().length == 1
            && databaseId.equals(request.identifier().namespace().level(0))
            && viewId.equals(request.identifier().name()))) {
      failures.addGeneric(
          String.format(
              "identifier : provided %s, doesn't match the request path %s.%s",
              request.identifier(), databaseId, viewId));
    }
    validateRequirements(request.requirements(), failures);
    validateUpdates(request.updates(), failures);
    failures.throwIfPresent();
  }

  /**
   * The views surface supports exactly one commit requirement, {@code assert-view-uuid}. Every
   * other requirement type belongs to the table commit vocabulary and is rejected with a 400 rather
   * than silently ignored: a client that sent it is asserting something this server would not
   * check.
   */
  private void validateRequirements(
      List<UpdateRequirement> requirements, ViewValidationFailures failures) {
    for (int index = 0; index < requirements.size(); index++) {
      UpdateRequirement requirement = requirements.get(index);
      if (!(requirement instanceof UpdateRequirement.AssertViewUUID)) {
        failures.addGeneric(
            String.format(
                "requirements[%d] : only assert-view-uuid is supported on the views surface",
                index));
      }
    }
  }

  /**
   * Restricts update actions to the spec's view-update set and applies the same structural rules to
   * an {@code add-view-version} payload as a create request's {@code view-version} gets. Shape
   * compliance and capability scope are separate axes: the stubbed service may still reject
   * combinations it does not implement.
   */
  private void validateUpdates(List<MetadataUpdate> updates, ViewValidationFailures failures) {
    for (int index = 0; index < updates.size(); index++) {
      MetadataUpdate update = updates.get(index);
      if (update == null || !isSupportedViewUpdate(update)) {
        failures.addGeneric(
            String.format("updates[%d] : action is not part of the view-update set", index));
        continue;
      }
      if (update instanceof MetadataUpdate.AddViewVersion) {
        validateViewVersion(
            String.format("updates[%d].view-version", index),
            ((MetadataUpdate.AddViewVersion) update).viewVersion(),
            failures);
      } else if (update instanceof MetadataUpdate.AddSchema) {
        validateSchemaSize(
            String.format("updates[%d].schema", index),
            ((MetadataUpdate.AddSchema) update).schema(),
            failures);
      } else if (update instanceof MetadataUpdate.SetProperties) {
        validateProperties(
            String.format("updates[%d].updates", index),
            ((MetadataUpdate.SetProperties) update).updated(),
            failures);
      }
    }
  }

  private static boolean isSupportedViewUpdate(MetadataUpdate update) {
    return SUPPORTED_VIEW_UPDATES.stream().anyMatch(supported -> supported.isInstance(update));
  }

  /**
   * The size ceiling is measured on the canonical serialized form, in UTF-8 bytes: the limit
   * protects storage and transport, and byte count is what a multibyte document actually costs.
   * Parseability is already proven — the request reached this point through Iceberg's parser.
   */
  private void validateSchemaSize(String field, Schema schema, ViewValidationFailures failures) {
    if (schema == null) {
      // Parser-required; unreachable for a parsed request but kept total for direct callers.
      return;
    }
    if (utf8Size(SchemaParser.toJson(schema)) > MAX_VIEW_SCHEMA_BYTES) {
      failures.addSchema(
          String.format(
              "%s : exceeds maximum UTF-8 size of %d bytes", field, MAX_VIEW_SCHEMA_BYTES));
    }
  }

  /**
   * Shared rules for a {@code view-version}, whether it arrives in a create request or in an {@code
   * add-view-version} update: at least one representation, SQL-typed representations only, every
   * dialect supported and unique, SQL within the size ceiling, and the {@code
   * openhouse.source-dialect} summary rule. The source-dialect summary key is optional with a
   * single representation — the unique-dialect rule makes the server-side default well defined —
   * and required only when representations are plural, so a stock client's create passes
   * unmodified.
   */
  private void validateViewVersion(
      String field, ViewVersion viewVersion, ViewValidationFailures failures) {
    if (viewVersion == null) {
      // Parser-required; unreachable for a parsed request but kept total for direct callers.
      return;
    }
    List<ViewRepresentation> representations = viewVersion.representations();
    if (representations.isEmpty()) {
      failures.addGeneric(
          String.format("%s.representations : must contain at least one representation", field));
    }
    List<SQLViewRepresentation> sqlRepresentations = new ArrayList<>();
    for (int index = 0; index < representations.size(); index++) {
      ViewRepresentation representation = representations.get(index);
      if (!(representation instanceof SQLViewRepresentation)) {
        failures.addGeneric(
            String.format(
                "%s.representations[%d].type : must be '%s'",
                field, index, SQL_VIEW_REPRESENTATION_TYPE));
        continue;
      }
      SQLViewRepresentation sqlRepresentation = (SQLViewRepresentation) representation;
      sqlRepresentations.add(sqlRepresentation);
      if (!supportedDialects.contains(normalizeDialect(sqlRepresentation.dialect()))) {
        failures.addDialect(
            String.format(
                "%s.representations[%d].dialect : must be one of the supported dialects: %s",
                field, index, supportedDialectsText));
      }
      if (utf8Size(sqlRepresentation.sql()) > MAX_VIEW_SQL_BYTES) {
        failures.addGeneric(
            String.format(
                "%s.representations[%d].sql : exceeds maximum UTF-8 size of %d bytes",
                field, index, MAX_VIEW_SQL_BYTES));
      }
    }
    validateUniqueDialects(field, sqlRepresentations, failures);
    validateSourceDialectSummary(field, viewVersion, sqlRepresentations, failures);
    validateDefaultCatalog(field, viewVersion.defaultCatalog(), failures);
    validateDefaultNamespace(field, viewVersion.defaultNamespace(), failures);
  }

  /**
   * Dialects identify a representation, so two representations claiming the same dialect are
   * ambiguous. Compared case-insensitively: {@code SPARK} and {@code spark} name the same engine,
   * and rejecting the pair as duplicates is more useful than reporting only the casing failure.
   */
  private void validateUniqueDialects(
      String field, List<SQLViewRepresentation> representations, ViewValidationFailures failures) {
    Set<String> seen = new HashSet<>();
    Set<String> duplicates = new TreeSet<>();
    for (SQLViewRepresentation representation : representations) {
      String normalized = normalizeDialect(representation.dialect());
      if (normalized != null && !seen.add(normalized)) {
        duplicates.add(normalized);
      }
    }
    if (!duplicates.isEmpty()) {
      failures.addDialect(
          String.format(
              "%s.representations : dialects must be unique, duplicated: %s",
              field, String.join(", ", duplicates)));
    }
  }

  /**
   * The {@code openhouse.source-dialect} summary entry: optional with a single representation,
   * required with several. When present it must name a supported dialect and one of the supplied
   * representations — the first is about what the server can serve, the second about what this
   * request defines.
   */
  private void validateSourceDialectSummary(
      String field,
      ViewVersion viewVersion,
      List<SQLViewRepresentation> representations,
      ViewValidationFailures failures) {
    String sourceDialect = viewVersion.summary().get(VIEW_SOURCE_DIALECT_SUMMARY_KEY);
    if (sourceDialect == null) {
      if (representations.size() > 1) {
        failures.addDialect(
            String.format(
                "%s.summary.%s : required when multiple representations are supplied",
                field, VIEW_SOURCE_DIALECT_SUMMARY_KEY));
      }
      return;
    }
    String normalized = normalizeDialect(sourceDialect);
    if (!supportedDialects.contains(normalized)) {
      failures.addDialect(
          String.format(
              "%s.summary.%s : must be one of the supported dialects: %s",
              field, VIEW_SOURCE_DIALECT_SUMMARY_KEY, supportedDialectsText));
      return;
    }
    // Only meaningful when at least one SQL representation was supplied; with none, the missing
    // representation is already reported and a second message would duplicate that diagnosis.
    if (!representations.isEmpty()
        && representations.stream()
            .noneMatch(
                representation -> normalized.equals(normalizeDialect(representation.dialect())))) {
      failures.addDialect(
          String.format(
              "%s.summary.%s : does not name a supplied representation",
              field, VIEW_SOURCE_DIALECT_SUMMARY_KEY));
    }
  }

  /**
   * The resolution catalog is optional, but supplying a blank or unbounded one is a client bug
   * rather than an omission, so it is rejected instead of silently ignored.
   */
  private void validateDefaultCatalog(
      String field, String defaultCatalog, ViewValidationFailures failures) {
    if (defaultCatalog == null) {
      return;
    }
    if (StringUtils.isBlank(defaultCatalog)) {
      failures.addGeneric(
          String.format("%s.default-catalog : cannot be blank when provided", field));
    } else if (defaultCatalog.length() > MAX_VIEW_IDENTIFIER_LENGTH) {
      failures.addGeneric(
          String.format(
              "%s.default-catalog : exceeds the maximum length of %d characters",
              field, MAX_VIEW_IDENTIFIER_LENGTH));
    }
  }

  /**
   * Resolution namespace segments follow the same identifier rules as a path namespace. An empty
   * namespace is legal — the spec requires the field but allows it empty, meaning unset. Messages
   * are indexed but fixed: the offending segment is only echoed through the shared identifier rule,
   * which reports names, never payload.
   */
  private void validateDefaultNamespace(
      String field,
      org.apache.iceberg.catalog.Namespace defaultNamespace,
      ViewValidationFailures failures) {
    if (defaultNamespace == null) {
      return;
    }
    for (int index = 0; index < defaultNamespace.levels().length; index++) {
      validateIdentifier(
          String.format("%s.default-namespace[%d]", field, index),
          defaultNamespace.level(index),
          failures);
    }
  }

  /**
   * View properties are user-owned, with two exceptions carved out for the server: the {@code
   * openhouse.} namespace, whose canonical case-sensitive predicate is reused from the internal
   * catalog, and the exact key {@code policies}. Case sensitivity is deliberate and inherited: a
   * user property such as {@code OpenHouse.myTeam} stays legal.
   *
   * <p>Property keys are user-authored identifiers rather than payload text, so listing the
   * offending keys is intentional and does not breach the SQL/schema redaction invariant.
   */
  private void validateProperties(
      String field, Map<String, String> properties, ViewValidationFailures failures) {
    boolean blankKey = false;
    Set<String> reservedKeys = new TreeSet<>();
    for (Map.Entry<String, String> property : properties.entrySet()) {
      String key = property.getKey();
      if (StringUtils.isBlank(key)) {
        blankKey = true;
        continue;
      }
      if (HouseTableSerdeUtils.IS_OH_PREFIXED.test(key) || POLICIES_PROPERTY_KEY.equals(key)) {
        reservedKeys.add(key);
      }
    }
    if (blankKey) {
      failures.addGeneric(String.format("%s : property keys cannot be blank", field));
    }
    if (!reservedKeys.isEmpty()) {
      failures.addGeneric(
          String.format(
              "%s : reserved keys are not allowed: %s", field, String.join(", ", reservedKeys)));
    }
  }

  private void validateIdentifier(String field, String value, ViewValidationFailures failures) {
    List<String> identifierFailures = new ArrayList<>();
    ApiValidatorUtil.validateIdentifier(field, value, identifierFailures);
    if (!identifierFailures.isEmpty()) {
      identifierFailures.forEach(failures::addGeneric);
    } else if (value.length() > MAX_VIEW_IDENTIFIER_LENGTH) {
      // Deliberately omits the offending value: an over-long identifier is by definition large
      // and the message is copied into the error body and into service audit events.
      failures.addGeneric(
          String.format(
              "%s : exceeds the maximum length of %d characters",
              field, MAX_VIEW_IDENTIFIER_LENGTH));
    }
  }

  private static String normalizeDialect(String dialect) {
    return dialect == null ? null : dialect.toLowerCase(Locale.ROOT);
  }

  /** Counts UTF-8 bytes, not UTF-16 characters. */
  private static int utf8Size(String value) {
    return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * Accumulates failure messages in discovery order while separately remembering whether a schema
   * or dialect rule failed, so the thrown exception can carry the most specific internal code.
   *
   * <p>Precedence is schema, then dialect, then the generic definition code. All three map to 400
   * with the {@code BadRequestException} type, so the choice is observable only to internal callers
   * and tests.
   */
  private static final class ViewValidationFailures {
    private final List<String> messages = new ArrayList<>();
    private boolean schemaFailure;
    private boolean dialectFailure;

    private void addGeneric(String message) {
      messages.add(message);
    }

    private void addSchema(String message) {
      schemaFailure = true;
      messages.add(message);
    }

    private void addDialect(String message) {
      dialectFailure = true;
      messages.add(message);
    }

    private void throwIfPresent() {
      if (messages.isEmpty()) {
        return;
      }
      throw new ViewRequestValidationFailureException(errorCode(), messages);
    }

    private ViewValidationErrorCode errorCode() {
      if (schemaFailure) {
        return ViewValidationErrorCode.UNSUPPORTED_VIEW_SCHEMA;
      }
      if (dialectFailure) {
        return ViewValidationErrorCode.UNSUPPORTED_VIEW_DIALECT;
      }
      return ViewValidationErrorCode.INVALID_VIEW_DEFINITION;
    }
  }
}
