package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.tables.api.handler.ViewsApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateViewRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAllViewsResponseBody;
import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.audit.ViewAuditAspect;
import com.linkedin.openhouse.tables.audit.model.ViewOperationType;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewRequestValidationFailureException;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
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
import org.apache.iceberg.view.ViewMetadata;
import org.springframework.beans.factory.annotation.Autowired;
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
 *       representation when the request does not name one. The server owns location and UUID: a
 *       location the create names is ignored, and the view is created, and returned, where the
 *       server puts it.
 *   <li>A commit is first authorized as the native replace is, on the stored view's House Tables
 *       row ({@link #authorizeCommit}), then checked here, against the stored view: each
 *       requirement, then each update is applied to it (an update that sets another location, UUID
 *       or format version is refused, except that a replace, which sets a new current version,
 *       ignores a set-location, which is how a client's replace that names a location arrives,
 *       since the server owns the location). The result is sent as the native PUT of the complete
 *       definition on the stored metadata location, so the native base-version check refuses a base
 *       that moved meanwhile.
 *   <li>The native view API has no rename, so the rename route answers the spec's 406.
 *   <li>A registration (Iceberg 1.12 line only) is {@code IcebergRestViewRegistration}'s: it runs
 *       the native create's gate, database, name and authorization checks, then stores the view row
 *       pointing at the metadata file named, in place.
 * </ul>
 *
 * <p>Failures keep the native status and message; the type is the Iceberg exception {@code
 * RESTCatalog} should raise. Two failures of a named view are worded as Iceberg's catalogs word
 * them instead: a missing view ("View does not exist: db.v") and a name a table holds ("Table with
 * same name already exists: db.v"). With views disabled the native API answers 404 on every route;
 * here that is {@code NoSuchViewException} on a view's own routes and {@code
 * NoSuchNamespaceException} on the namespace's routes (list, create), which is what a catalog
 * without views answers.
 */
@ConditionalOnIcebergRest
@Component
public class IcebergRestViewHandler {

  /** The page size asked of the native list while collecting every view. */
  static final int LIST_PAGE_SIZE = 100;

  private static final String NAMESPACE_SEPARATOR = Pattern.quote("\u001f");

  @Autowired private ViewsApiHandler viewsApiHandler;

  @Autowired private ViewsApiValidator viewsApiValidator;

  @Autowired private ViewsServiceImpl viewsService;

  @Autowired private IcebergRestApiHandler readHandler;

  @Autowired private ViewsFeatureGate viewsFeatureGate;

  @Autowired private ViewAuditAspect viewAudit;

  /**
   * Every view of the namespace, through the native list, page by page. A database whose views are
   * disabled holds no views: the list is empty (if the database exists), where the native list
   * answers 404 "Views are disabled". The OpenHouse client shows such a database no views too, and
   * a client that lists views with tables (Spark 4.2's SHOW TABLES on Iceberg 1.12) would fail
   * every listing on the 404. The gate is the one the native list consults.
   */
  public ListTablesResponse listViews(String namespace, String actingPrincipal) {
    String databaseId = IcebergRestApiHandler.databaseId(namespace);
    readHandler.requireValidNamespaceId(databaseId);
    if (!viewsFeatureGate.isEnabled(databaseId)) {
      readHandler.requireNamespace(databaseId);
      return ListTablesResponse.builder().build();
    }
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

  /**
   * The view's definition, which the service reads from storage for the caller, so it is decided as
   * the IRC table load is: the read privilege on the view, after the House Tables read (a missing
   * view is 404 first, as a missing table is) and before the metadata file is read ({@link
   * ViewsServiceImpl#loadReadableView}). The native GET, which answers only the pointer, makes no
   * decision and is unchanged.
   */
  public LoadViewResponse loadView(String namespace, String viewId, String actingPrincipal) {
    String databaseId = viewDatabaseId(namespace, viewId);
    requireValidViewIds(databaseId, viewId);
    return onView(
        databaseId,
        viewId,
        () -> response(viewsService.loadReadableView(databaseId, viewId, actingPrincipal)));
  }

  public void viewExists(String namespace, String viewId, String actingPrincipal) {
    String databaseId = viewDatabaseId(namespace, viewId);
    requireValidViewIds(databaseId, viewId);
    onView(databaseId, viewId, () -> viewsApiHandler.getView(databaseId, viewId, actingPrincipal));
  }

  /**
   * Fails as for a missing view unless the native rules accept both ids: no view can have an id
   * they refuse, so the existence check and the load answer it as a missing view, where the native
   * routes refuse it (400), as the table routes do ({@code IcebergRestApiHandler}). The writes
   * still refuse it.
   */
  private void requireValidViewIds(String databaseId, String viewId) {
    try {
      viewsApiValidator.validateGetView(databaseId, viewId);
    } catch (ViewRequestValidationFailureException refused) {
      throw new NoSuchViewException("View does not exist: %s.%s", databaseId, viewId);
    }
  }

  public LoadViewResponse createView(
      String namespace, CreateViewRequest request, String actingPrincipal) {
    String databaseId = IcebergRestApiHandler.databaseId(namespace);
    // request.location() is not read: the server owns the location, and the native create
    // allocates it as for a request that names none.
    CreateUpdateViewRequestBody body = IcebergRestViewSpelling.createBody(databaseId, request);
    TableIdentifier view = TableIdentifier.of(databaseId, request.name());
    onNamespace(view, () -> viewsApiHandler.createView(body, actingPrincipal));
    return onView(
        databaseId,
        request.name(),
        () -> response(load(databaseId, request.name(), actingPrincipal)));
  }

  public LoadViewResponse commitView(
      String namespace, String viewId, UpdateTableRequest request, String actingPrincipal) {
    String databaseId = viewDatabaseId(namespace, viewId);
    authorizeCommit(databaseId, viewId, actingPrincipal);
    // The view the request is checked against and built on is read with the read decision made on
    // the row read (the decision above was made on an earlier read of it), and so is the view the
    // commit answers: a view replaced by another of its name in between is not answered to a
    // caller that may not read it.
    ViewsServiceImpl.LoadedView base =
        onView(databaseId, viewId, () -> loadReadable(databaseId, viewId, actingPrincipal));
    ViewMetadata candidate =
        IcebergRestViewSpelling.apply(databaseId, viewId, base.getMetadata(), request);
    CreateUpdateViewRequestBody body =
        IcebergRestViewSpelling.replaceBody(
            databaseId, viewId, base.getView().getMetadataLocation(), candidate);
    String committed =
        onView(databaseId, viewId, () -> viewsApiHandler.updateView(body, actingPrincipal))
            .getResponseBody()
            .getMetadataLocation();
    return answered(databaseId, viewId, committed, base.getMetadata().uuid(), actingPrincipal);
  }

  /**
   * The answer to a view commit that stored the view at {@code committed}: that version, never a
   * later one, as a table commit is answered. The view is read back, with the read decision, and
   * answered when its pointer is still {@code committed}; when another write moved it on, the
   * version this commit stored is read from its own metadata file. When that file is gone, the
   * commit landed all the same: the view as stored now is answered if it is the same view (the same
   * view UUID), and otherwise the commit's state is unknown ({@link CommitStateUnknownException},
   * the spec's 500), not a failure.
   *
   * <p>The native replace always answers the location it stored: the location House Tables answers
   * for the row it saved, or, for a replace that changes nothing, the stored row's, which the
   * commit engine requires to be set. An answer without one breaks that contract; which version the
   * commit stored cannot then be told, so the commit's state is unknown, and no version is answered
   * as a guess.
   */
  private LoadViewResponse answered(
      String databaseId,
      String viewId,
      String committed,
      String committedUuid,
      String actingPrincipal) {
    if (committed == null) {
      throw new CommitStateUnknownException(
          String.format(
              "The write to view %s.%s landed, but the view service answered no metadata"
                  + " location, so the version it stored cannot be told",
              databaseId, viewId),
          new IllegalStateException("The native view replace answered no metadata location"));
    }
    ViewsServiceImpl.LoadedView now =
        onView(databaseId, viewId, () -> loadReadable(databaseId, viewId, actingPrincipal));
    if (committed.equals(now.getView().getMetadataLocation())) {
      return response(now);
    }
    ViewMetadata stored;
    try {
      stored = viewsService.readViewVersion(databaseId, viewId, committed);
    } catch (RuntimeException unreadable) {
      if (committedUuid.equals(now.getMetadata().uuid())) {
        return response(now);
      }
      throw new CommitStateUnknownException(
          String.format(
              "The write to view %s.%s landed, but the version it stored (%s) can no longer be"
                  + " read, and the view of that name is now another view",
              databaseId, viewId, committed),
          unreadable);
    }
    return ImmutableLoadViewResponse.builder().metadataLocation(committed).metadata(stored).build();
  }

  /**
   * Decides whether {@code principal} may make {@code request} on the stored view, before its
   * metadata is read and the request checked against it: its requirements are answered with stored
   * values (the view UUID) and its updates fail on what the view holds. Only what the native routes
   * tell anyone comes first: the ids (400), the views gate and whether the view exists (404).
   *
   * <p>A commit answers the view's whole definition, as the IRC view load does, so it needs the
   * read privilege the load needs ({@link ViewsServiceImpl#authorizeRead}), whatever its updates:
   * an Iceberg client loads a view before it commits to it, so a caller that may not read the view
   * could not commit through one either. Then the native replace's decision, on the same reference
   * ({@link ViewsServiceImpl#authorizeReplace}); the native PUT that follows makes it again. A
   * refusal is audited exactly as the native replace audits its refusal.
   */
  private void authorizeCommit(String databaseId, String viewId, String principal) {
    onView(
        databaseId,
        viewId,
        () -> {
          viewsApiValidator.validateGetView(databaseId, viewId);
          TableDto reference = viewsService.viewReference(databaseId, viewId);
          viewAudit.auditRefusal(
              databaseId,
              viewId,
              ViewOperationType.REPLACE,
              () -> {
                viewsService.authorizeRead(reference, principal);
                viewsService.authorizeReplace(reference, principal);
              });
          return null;
        });
  }

  public void dropView(String namespace, String viewId, String actingPrincipal) {
    String databaseId = viewDatabaseId(namespace, viewId);
    onView(
        databaseId, viewId, () -> viewsApiHandler.deleteView(databaseId, viewId, actingPrincipal));
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

  /** {@link #load}, with the read decision made on the row it reads. */
  private ViewsServiceImpl.LoadedView loadReadable(
      String databaseId, String viewId, String actingPrincipal) {
    viewsApiValidator.validateGetView(databaseId, viewId);
    return viewsService.loadReadableView(databaseId, viewId, actingPrincipal);
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

  /** A native call on the view {@code databaseId.viewId}, on one of the view's own routes. */
  private static <T> T onView(String databaseId, String viewId, Supplier<T> call) {
    return translate(call, false, TableIdentifier.of(databaseId, viewId));
  }

  /** A native call on a namespace's route that names no view (the list). */
  private static <T> T onNamespace(Supplier<T> call) {
    return translate(call, true, null);
  }

  /**
   * A native call on a namespace's route that names {@code view} (the create, and the register
   * route's, {@code IcebergRestViewRegistration}, on the Iceberg 1.12 line).
   */
  static <T> T onNamespace(TableIdentifier view, Supplier<T> call) {
    return translate(call, true, view);
  }

  /**
   * Runs a native view call and rethrows its failure as the Iceberg exception for the native
   * status, with the native message, except for {@code view} ({@link #translated}). {@code
   * namespaceRoute} picks the type of the views-disabled 404.
   */
  private static <T> T translate(Supplier<T> call, boolean namespaceRoute, TableIdentifier view) {
    try {
      return call.get();
    } catch (ViewApiException e) {
      throw translated(e, namespaceRoute, view);
    } catch (AuthorizationServiceException e) {
      // The native view routes answer this 503 with a fixed message.
      throw new ServiceUnavailableException("Authorization service unavailable");
    }
  }

  /**
   * The Iceberg exception for a native view failure. A missing view and a name a table holds, on a
   * route that names the view, are worded as Iceberg's catalogs word them ({@code
   * BaseMetastoreViewCatalog}, {@code CatalogHandlers}), the words the reference implementation
   * answers; every other failure keeps the native message.
   */
  private static RuntimeException translated(
      ViewApiException e, boolean namespaceRoute, TableIdentifier view) {
    String message = e.getMessage();
    switch (e.getErrorCode()) {
      case NO_SUCH_VIEW:
        return view != null
            ? new NoSuchViewException("View does not exist: %s", view)
            : new NoSuchViewException("%s", message);
      case DATABASE_NOT_FOUND:
        return new NoSuchNamespaceException("%s", message);
      case VIEWS_DISABLED:
        return namespaceRoute
            ? new NoSuchNamespaceException("%s", message)
            : new NoSuchViewException("%s", message);
      case NAME_ALREADY_EXISTS_AS_TABLE:
        return view != null
            ? new AlreadyExistsException("Table with same name already exists: %s", view)
            : new AlreadyExistsException("%s", message);
      case VIEW_ALREADY_EXISTS:
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
