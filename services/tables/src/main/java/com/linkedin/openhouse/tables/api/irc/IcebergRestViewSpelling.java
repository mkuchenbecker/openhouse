package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateViewRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ViewRepresentation;
import com.linkedin.openhouse.tables.model.ReservedViewProperties;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;

/**
 * How {@link IcebergRestViewHandler} spells an IRC view write as the native view request the
 * OpenHouse Java client's {@code OpenHouseViewOperations} sends for it, and how it checks and
 * applies a commit to the stored view. Static, and not a bean: Spring beans name no Iceberg view
 * type in their signatures (the bean-boundary test of the test fixtures allows that only in the
 * view codec and the commit engine), so the code that does lives here.
 */
final class IcebergRestViewSpelling {
  private static final String SQL = "sql";

  private IcebergRestViewSpelling() {}

  /** The create as the Java client's view operations send it for this definition. */
  static CreateUpdateViewRequestBody createBody(String databaseId, CreateViewRequest request) {
    ViewVersion version = request.viewVersion();
    List<ViewRepresentation> representations = representations(version);
    Map<String, String> properties = new HashMap<>(request.properties());
    List<ViewRepresentation> sql =
        representations.stream().filter(r -> SQL.equals(r.getType())).collect(Collectors.toList());
    if (sql.size() == 1
        && properties.keySet().stream()
            .noneMatch(ReservedViewProperties.SOURCE_DIALECT::equalsIgnoreCase)) {
      properties.put(ReservedViewProperties.SOURCE_DIALECT, sql.get(0).getDialect());
    }
    return CreateUpdateViewRequestBody.builder()
        .viewId(request.name())
        .databaseId(databaseId)
        .schema(SchemaParser.toJson(request.schema(), false))
        .representations(representations)
        .defaultCatalog(version.defaultCatalog())
        .defaultNamespace(levels(version.defaultNamespace()))
        .viewProperties(properties)
        .build();
  }

  /** The replace as the Java client's view operations send it: the complete definition. */
  static CreateUpdateViewRequestBody replaceBody(
      String databaseId, String viewId, String baseMetadataLocation, ViewMetadata candidate) {
    ViewVersion version = candidate.currentVersion();
    return CreateUpdateViewRequestBody.builder()
        .viewId(viewId)
        .databaseId(databaseId)
        .schema(SchemaParser.toJson(candidate.schema(), false))
        .representations(representations(version))
        .defaultCatalog(version.defaultCatalog())
        .defaultNamespace(levels(version.defaultNamespace()))
        .viewProperties(new HashMap<>(candidate.properties()))
        .baseMetadataLocation(baseMetadataLocation)
        .build();
  }

  /** SQL representations as the native API spells them; any other kind is sent by type only. */
  static List<ViewRepresentation> representations(ViewVersion version) {
    return version.representations().stream()
        .map(
            representation ->
                representation instanceof SQLViewRepresentation
                    ? ViewRepresentation.builder()
                        .type(SQL)
                        .sql(((SQLViewRepresentation) representation).sql())
                        .dialect(((SQLViewRepresentation) representation).dialect())
                        .build()
                    : ViewRepresentation.builder().type(representation.type()).build())
        .collect(Collectors.toList());
  }

  /** Omitted when empty, as the Java client omits it. */
  static List<String> levels(Namespace namespace) {
    return namespace == null || namespace.isEmpty() ? null : Arrays.asList(namespace.levels());
  }

  /**
   * Checks the commit's requirements against the stored view and applies its updates to it. A
   * failed requirement is the client's 409 ({@code CommitFailedException}); a requirement that is
   * not a view's, an update that is not a view's, or one that would set what the server owns, is a
   * 400 naming it. A replace (a commit that sets a new current version, as {@code
   * RESTViewBuilder.replace} sends) ignores a set-location, which is how a replace that names a
   * location arrives: the server owns the location (D30).
   */
  static ViewMetadata apply(
      String databaseId, String viewId, ViewMetadata base, UpdateTableRequest request) {
    List<UpdateRequirement> requirements =
        request.requirements() == null ? Collections.emptyList() : request.requirements();
    for (UpdateRequirement requirement : requirements) {
      try {
        requirement.validate(base);
      } catch (CommitFailedException e) {
        throw e;
      } catch (ValidationException e) {
        throw new BadRequestException("%s", e.getMessage());
      }
    }
    List<MetadataUpdate> updates =
        request.updates() == null ? Collections.emptyList() : request.updates();
    boolean replace =
        updates.stream().anyMatch(MetadataUpdate.SetCurrentViewVersion.class::isInstance);
    ViewMetadata.Builder builder = ViewMetadata.buildFrom(base);
    for (MetadataUpdate update : updates) {
      if (replace && update instanceof MetadataUpdate.SetLocation) {
        continue;
      }
      refuseServerOwned(databaseId, viewId, base, update);
      try {
        update.applyTo(builder);
      } catch (RuntimeException e) {
        throw new BadRequestException(
            "Cannot apply %s to view %s.%s: %s",
            update.getClass().getSimpleName(), databaseId, viewId, e.getMessage());
      }
    }
    try {
      return builder.build();
    } catch (RuntimeException e) {
      throw new BadRequestException(
          "Invalid commit on view %s.%s: %s", databaseId, viewId, e.getMessage());
    }
  }

  static void refuseServerOwned(
      String databaseId, String viewId, ViewMetadata base, MetadataUpdate update) {
    String field = null;
    if (update instanceof MetadataUpdate.SetLocation
        && !Objects.equals(base.location(), ((MetadataUpdate.SetLocation) update).location())) {
      field = "location";
    } else if (update instanceof MetadataUpdate.AssignUUID
        && !base.uuid().equalsIgnoreCase(((MetadataUpdate.AssignUUID) update).uuid())) {
      field = "view UUID";
    } else if (update instanceof MetadataUpdate.UpgradeFormatVersion
        && base.formatVersion() != ((MetadataUpdate.UpgradeFormatVersion) update).formatVersion()) {
      field = "format version";
    }
    if (field != null) {
      throw new BadRequestException(
          "Commit on view %s.%s changes what the server owns: %s", databaseId, viewId, field);
    }
  }
}
