package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.tables.api.handler.ViewsApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateViewRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAllViewsResponseBody;
import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.services.ViewsServiceImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ImmutableLoadViewResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadViewResponse;
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
    CreateUpdateViewRequestBody body = IcebergRestViewSpelling.createBody(databaseId, request);
    onNamespace(() -> viewsApiHandler.createView(body, actingPrincipal));
    return onView(() -> response(load(databaseId, request.name(), actingPrincipal)));
  }

  public LoadViewResponse commitView(
      String namespace, String viewId, UpdateTableRequest request, String actingPrincipal) {
    String databaseId = viewDatabaseId(namespace, viewId);
    ViewsServiceImpl.LoadedView base = onView(() -> load(databaseId, viewId, actingPrincipal));
    CreateUpdateViewRequestBody body =
        IcebergRestViewSpelling.replaceBody(
            databaseId,
            viewId,
            base.getView().getMetadataLocation(),
            IcebergRestViewSpelling.apply(databaseId, viewId, base.getMetadata(), request));
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
