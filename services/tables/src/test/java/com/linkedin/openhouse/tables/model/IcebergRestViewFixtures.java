package com.linkedin.openhouse.tables.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.CreateViewRequestParser;
import org.apache.iceberg.rest.requests.ImmutableCreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.ImmutableSQLViewRepresentation;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;

/**
 * Deterministic fixtures for the Iceberg REST views wire surface, built with Iceberg's own model
 * classes and serialized with its parsers. Everything here is a fixed literal: no random
 * identifiers, no {@code UUID.randomUUID()} and no {@code System.currentTimeMillis()}, so contract
 * assertions stay byte-stable across runs.
 */
public final class IcebergRestViewFixtures {

  private IcebergRestViewFixtures() {}

  public static final String DATABASE_ID = "my_database";
  public static final String VIEW_ID = "my_view";
  public static final String VIEW_UUID = "42e626f5-4d1e-46c9-b58a-0d4a1a4d4a4d";
  public static final String SOURCE_DIALECT = "spark";
  public static final String VIEW_SQL = "SELECT id, name FROM my_database.my_table";
  public static final long TIMESTAMP_MS = 1651002318265L;
  public static final String LOCATION = "file:/tmp/openhouse/my_database/my_view";
  public static final String METADATA_LOCATION = LOCATION + "/metadata/00000-fixed.metadata.json";

  public static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.StringType.get()),
          Types.NestedField.required(2, "name", Types.StringType.get()));

  public static final SQLViewRepresentation SPARK_REPRESENTATION =
      ImmutableSQLViewRepresentation.builder().sql(VIEW_SQL).dialect(SOURCE_DIALECT).build();

  public static ViewVersion viewVersion() {
    return ImmutableViewVersion.builder()
        .versionId(1)
        .timestampMillis(TIMESTAMP_MS)
        .schemaId(0)
        .summary(Collections.singletonMap("operation", "create"))
        .addRepresentations(SPARK_REPRESENTATION)
        .defaultNamespace(Namespace.of(DATABASE_ID))
        .defaultCatalog("openhouse")
        .build();
  }

  public static ViewVersion viewVersionWithSummary(Map<String, String> summary) {
    return ImmutableViewVersion.builder().from(viewVersion()).summary(summary).build();
  }

  public static ViewVersion viewVersionWithRepresentations(
      SQLViewRepresentation... representations) {
    return ImmutableViewVersion.builder()
        .from(viewVersion())
        .representations(Arrays.asList(representations))
        .build();
  }

  public static SQLViewRepresentation representation(String dialect, String sql) {
    return ImmutableSQLViewRepresentation.builder().sql(sql).dialect(dialect).build();
  }

  public static CreateViewRequest createViewRequest() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("owner", "openhouse");
    return ImmutableCreateViewRequest.builder()
        .name(VIEW_ID)
        .schema(SCHEMA)
        .viewVersion(viewVersion())
        .properties(properties)
        .build();
  }

  public static CreateViewRequest createViewRequest(ViewVersion viewVersion) {
    return ImmutableCreateViewRequest.builder()
        .from(createViewRequest())
        .viewVersion(viewVersion)
        .build();
  }

  public static String createViewRequestJson() {
    return CreateViewRequestParser.toJson(createViewRequest());
  }

  public static String createViewRequestJson(CreateViewRequest request) {
    return CreateViewRequestParser.toJson(request);
  }

  public static UpdateTableRequest commitViewRequest() {
    return UpdateTableRequest.create(
        TableIdentifier.of(DATABASE_ID, VIEW_ID),
        Collections.singletonList(
            new org.apache.iceberg.UpdateRequirement.AssertViewUUID(VIEW_UUID)),
        Arrays.asList(
            new org.apache.iceberg.MetadataUpdate.AddViewVersion(viewVersion()),
            new org.apache.iceberg.MetadataUpdate.SetCurrentViewVersion(-1)));
  }

  public static String commitViewRequestJson() {
    return UpdateTableRequestParser.toJson(commitViewRequest());
  }

  /** Complete, deterministic view metadata for response-side contract assertions. */
  public static ViewMetadata viewMetadata() {
    ViewMetadata built =
        ViewMetadata.builder()
            .assignUUID(VIEW_UUID)
            .setLocation(LOCATION)
            .setCurrentVersion(viewVersion(), SCHEMA)
            .setProperties(Collections.singletonMap("owner", "openhouse"))
            .build();
    // The metadata location can only be attached to already-committed metadata (no pending
    // changes), which is exactly what a served LoadViewResult carries.
    return ViewMetadata.buildFrom(built).setMetadataLocation(METADATA_LOCATION).build();
  }
}
