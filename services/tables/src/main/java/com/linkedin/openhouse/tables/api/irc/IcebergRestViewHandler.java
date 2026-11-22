package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.tables.api.handler.ViewsApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateViewRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ViewRepresentation;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAllViewsResponseBody;
import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.model.ReservedViewProperties;
import com.linkedin.openhouse.tables.services.ViewsServiceImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ImmutableLoadViewResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.access.AuthorizationServiceException;
import org.springframework.stereotype.Component;

/**
 * The view routes of the Iceberg REST Catalog (IRC) protocol over the native view API. Every view
 * operation goes through the native view handler ({@link ViewsApiHandler}): its validator, the
 * views feature gate, the service's authorization and House Tables checks, the view commit engine
 * and the view audit. An IRC write is spelled as the OpenHouse Java client's {@code
 * OpenHouseViewOperations} spells the same write:
 *
 * <ul>
 *   <li>A create is the native POST of the view's definition: schema, SQL representations, default
 *       catalog and namespace, properties, and the source dialect derived from a single SQL
 *       representation when the request does not name one. The server owns location and UUID, so a
 *       create naming a location is refused.
 *   <li>A commit is checked here, against the stored view: each requirement, then each update is
 *       applied to it (an update that sets another location, UUID or format version is refused).
 *       The result is sent as the native PUT of the complete definition on the stored metadata
 *       location, so the native base-version check refuses a base that moved meanwhile.
 *   <li>The native view API has no rename, so the rename route answers the spec's 406.
 * </ul>
 *
 * <p>Failures keep the native status and message; the type is the Iceberg exception {@code
 * RESTCatalog} should raise. With views disabled the native API answers 404 on every route; here
 * that is {@code NoSuchViewException} on a view's own routes and {@code NoSuchNamespaceException}
 * on the namespace's routes (list, create), which is what a catalog without views answers.
 *
 * <p>Registered only where the Iceberg view API exists, as {@link ViewsServiceImpl} is.
 */
@Component
@ConditionalOnClass(name = "org.apache.iceberg.view.ViewMetadata")
public class IcebergRestViewHandler {

  /** The page size asked of the native list while collecting every view. */
  static final int LIST_PAGE_SIZE = 100;

  private static final String SQL = "sql";

  private static final String NAMESPACE_SEPARATOR = Pattern.quote("\u001f");

  @Autowired private ViewsApiHandler viewsApiHandler;

  @Autowired private ViewsApiValidator viewsApiValidator;

  @Autowired private ViewsServiceImpl viewsService;

  @Autowired private IcebergRestApiHandler readHandler;

  /** Every view of the namespace, through the native list, page by page. */
  public ListTablesResponse listViews(String namespace, String actingPrincipal) {
    String databaseId = IcebergRestApiHandler.databaseId(namespace);
    List<TableIdentifier> views = new ArrayList<>();
    String pageToken = null;
    do {
      String token = pageToken;
      GetAllViewsResponseBody page =
          onNamespace(
              () ->
                  viewsApiHandler
                      .getAllViews(databaseId, token, LIST_PAGE_SIZE, null, actingPrincipal)
                      .getResponseBody());
      if (page.getResults() != null) {
        page.getResults()
            .forEach(view -> views.add(TableIdentifier.of(databaseId, view.getViewId())));
      }
      pageToken = page.getNextPageToken();
    } while (pageToken != null && !pageToken.isEmpty());
    if (views.isEmpty()) {
      // The native list answers an empty page for a database that does not exist; the spec's
      // answer is 404, as for the table list.
      readHandler.requireNamespace(databaseId);
    }
    return ListTablesResponse.builder().addAll(views).build();
  }

  public LoadViewResponse loadView(String namespace, String viewId, String actingPrincipal) {
    String databaseId = viewDatabaseId(namespace, viewId);
    return onView(() -> response(load(databaseId, viewId, actingPrincipal)));
  }

  public void viewExists(String namespace, String viewId, String actingPrincipal) {
    String databaseId = viewDatabaseId(namespace, viewId);
    onView(() -> viewsApiHandler.getView(databaseId, viewId, actingPrincipal));
  }

  public LoadViewResponse createView(
      String namespace, CreateViewRequest request, String actingPrincipal) {
    String databaseId = IcebergRestApiHandler.databaseId(namespace);
    if (request.location() != null) {
      throw new BadRequestException(
          "Cannot create view %s.%s: the server owns the view location; location must not be set",
          databaseId, request.name());
    }
    CreateUpdateViewRequestBody body = createBody(databaseId, request);
    onNamespace(() -> viewsApiHandler.createView(body, actingPrincipal));
    return onView(() -> response(load(databaseId, request.name(), actingPrincipal)));
  }

  public LoadViewResponse commitView(
      String namespace, String viewId, UpdateTableRequest request, String actingPrincipal) {
    String databaseId = viewDatabaseId(namespace, viewId);
    ViewsServiceImpl.LoadedView base = onView(() -> load(databaseId, viewId, actingPrincipal));
    ViewMetadata candidate = apply(databaseId, viewId, base.getMetadata(), request);
    CreateUpdateViewRequestBody body =
        replaceBody(databaseId, viewId, base.getView().getMetadataLocation(), candidate);
    onView(() -> viewsApiHandler.updateView(body, actingPrincipal));
    return onView(() -> response(load(databaseId, viewId, actingPrincipal)));
  }

  public void dropView(String namespace, String viewId, String actingPrincipal) {
    String databaseId = viewDatabaseId(namespace, viewId);
    onView(() -> viewsApiHandler.deleteView(databaseId, viewId, actingPrincipal));
  }

  /** The native view API has no rename; the native client refuses one with the same words. */
  public void renameView(RenameTableRequest request) {
    throw new IcebergRestRouteException(
        String.format(
            "OpenHouse views do not support rename: %s -> %s",
            request.source(), request.destination()),
        true);
  }

  // ---------------------------------------------------------------- spelling

  /** The native view load: the native validator, then the service's load with its checks. */
  private ViewsServiceImpl.LoadedView load(
      String databaseId, String viewId, String actingPrincipal) {
    viewsApiValidator.validateGetView(databaseId, viewId);
    return viewsService.loadView(databaseId, viewId, actingPrincipal);
  }

  private static LoadViewResponse response(ViewsServiceImpl.LoadedView loaded) {
    return ImmutableLoadViewResponse.builder()
        .metadataLocation(loaded.getView().getMetadataLocation())
        .metadata(loaded.getMetadata())
        .build();
  }

  /** The create as the Java client's view operations send it for this definition. */
  private static CreateUpdateViewRequestBody createBody(
      String databaseId, CreateViewRequest request) {
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
  private static CreateUpdateViewRequestBody replaceBody(
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
  private static List<ViewRepresentation> representations(ViewVersion version) {
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
  private static List<String> levels(Namespace namespace) {
    return namespace == null || namespace.isEmpty() ? null : Arrays.asList(namespace.levels());
  }

  /**
   * Checks the commit's requirements against the stored view and applies its updates to it. A
   * failed requirement is the client's 409 ({@code CommitFailedException}); a requirement that is
   * not a view's, an update that is not a view's, or one that would set what the server owns, is a
   * 400 naming it.
   */
  private static ViewMetadata apply(
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
    ViewMetadata.Builder builder = ViewMetadata.buildFrom(base);
    for (MetadataUpdate update : updates) {
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

  private static void refuseServerOwned(
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

  // ---------------------------------------------------------------- namespaces and failures

  /**
   * The database of a view route's namespace. No view exists in a namespace of more than one level,
   * so that is a missing view (404), as on the table routes: Spark probes {@code loadView} for a
   * metadata table such as {@code db.t.snapshots} and must get "no such view" to go on to the
   * table.
   */
  private static String viewDatabaseId(String namespace, String viewId) {
    List<String> levels = Arrays.asList(namespace.split(NAMESPACE_SEPARATOR, -1));
    if (levels.size() > 1 && levels.stream().noneMatch(String::isEmpty)) {
      throw new NoSuchViewException("View does not exist: %s.%s", String.join(".", levels), viewId);
    }
    return IcebergRestApiHandler.databaseId(namespace);
  }

  private static <T> T onView(Supplier<T> call) {
    return translate(call, false);
  }

  private static <T> T onNamespace(Supplier<T> call) {
    return translate(call, true);
  }

  /**
   * Runs a native view call and rethrows its failure as the Iceberg exception for the native
   * status, with the native message. {@code namespaceRoute} picks the type of the views-disabled
   * 404.
   */
  private static <T> T translate(Supplier<T> call, boolean namespaceRoute) {
    try {
      return call.get();
    } catch (ViewApiException e) {
      throw translated(e, namespaceRoute);
    } catch (AuthorizationServiceException e) {
      // The native view routes answer this 503 with a fixed message.
      throw new ServiceUnavailableException("Authorization service unavailable");
    }
  }

  private static RuntimeException translated(ViewApiException e, boolean namespaceRoute) {
    String message = e.getMessage();
    switch (e.getErrorCode()) {
      case NO_SUCH_VIEW:
        return new NoSuchViewException("%s", message);
      case DATABASE_NOT_FOUND:
        return new NoSuchNamespaceException("%s", message);
      case VIEWS_DISABLED:
        return namespaceRoute
            ? new NoSuchNamespaceException("%s", message)
            : new NoSuchViewException("%s", message);
      case VIEW_ALREADY_EXISTS:
      case NAME_ALREADY_EXISTS_AS_TABLE:
        return new AlreadyExistsException("%s", message);
      case CONCURRENT_VIEW_MODIFICATION:
        return new CommitFailedException("%s", message);
      case INVALID_VIEW_DEFINITION:
      case UNSUPPORTED_VIEW_DIALECT:
      case UNSUPPORTED_VIEW_SCHEMA:
        return new BadRequestException("%s", message);
      case COMMIT_STATE_UNKNOWN:
        return new CommitStateUnknownException(message, e);
      case VIEW_SERVICE_UNAVAILABLE:
        return new ServiceUnavailableException("%s", message);
      case INTERNAL_VIEW_ERROR:
        return new ServiceFailureException("%s", message);
      default:
        // 422: no Iceberg exception; the error carries the status and the native message.
        return e;
    }
  }
}
