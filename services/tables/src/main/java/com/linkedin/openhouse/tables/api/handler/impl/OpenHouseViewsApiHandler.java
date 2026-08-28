package com.linkedin.openhouse.tables.api.handler.impl;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.tables.api.handler.ViewsApiHandler;
import com.linkedin.openhouse.tables.api.icebergrest.IcebergRestWire;
import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.model.ViewIdentifiersPage;
import com.linkedin.openhouse.tables.services.ViewsService;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.view.ViewMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Default Iceberg REST views API handler. The flow is strictly parse, validate, unwrap, delegate to
 * the service, and serialize: no business logic and no feature gating live here.
 *
 * <p>Namespace handling: OpenHouse namespaces are single-level. The spec encodes multi-level
 * namespaces with the {@code 0x1F} unit separator in the path segment; a namespace carrying one
 * names a namespace this catalog can never contain and is reported as the spec's 404 for an absent
 * namespace, not as a validation 400.
 */
@Component
public class OpenHouseViewsApiHandler implements ViewsApiHandler {

  /** The spec's multipart-namespace separator, url-encoded {@code %1F} on the wire. */
  private static final String NAMESPACE_SEPARATOR = "\u001F";

  private final ViewsApiValidator viewsApiValidator;

  private final ViewsService viewsService;

  @Autowired
  public OpenHouseViewsApiHandler(ViewsApiValidator viewsApiValidator, ViewsService viewsService) {
    this.viewsApiValidator = viewsApiValidator;
    this.viewsService = viewsService;
  }

  @Override
  public ApiResponse<String> getConfig() {
    return jsonResponse(HttpStatus.OK, IcebergRestWire.toCatalogConfigJson());
  }

  @Override
  public ApiResponse<String> listViews(
      String namespace, String pageToken, Integer pageSize, String actingPrincipal) {
    String databaseId = singleLevelNamespace(namespace);
    viewsApiValidator.validateListViews(databaseId, pageToken, pageSize);
    ViewIdentifiersPage page =
        viewsService.listViews(databaseId, pageToken, pageSize, actingPrincipal);
    return jsonResponse(
        HttpStatus.OK,
        IcebergRestWire.toListViewsJson(page.getIdentifiers(), page.getNextPageToken()));
  }

  @Override
  public ApiResponse<String> createView(
      String namespace, String requestJson, String actingPrincipal) {
    String databaseId = singleLevelNamespace(namespace);
    CreateViewRequest request = IcebergRestWire.parseCreateViewRequest(requestJson);
    viewsApiValidator.validateCreateView(databaseId, request);
    ViewMetadata metadata =
        viewsService.createView(
            TableIdentifier.of(databaseId, request.name()),
            request.schema(),
            request.viewVersion(),
            request.location(),
            request.properties(),
            actingPrincipal);
    return jsonResponse(HttpStatus.OK, IcebergRestWire.toLoadViewResultJson(metadata));
  }

  @Override
  public ApiResponse<String> loadView(String namespace, String view, String actingPrincipal) {
    String databaseId = singleLevelNamespace(namespace);
    viewsApiValidator.validateViewIdentifier(databaseId, view);
    ViewMetadata metadata =
        viewsService.loadView(TableIdentifier.of(databaseId, view), actingPrincipal);
    return jsonResponse(HttpStatus.OK, IcebergRestWire.toLoadViewResultJson(metadata));
  }

  @Override
  public ApiResponse<String> replaceView(
      String namespace, String view, String requestJson, String actingPrincipal) {
    String databaseId = singleLevelNamespace(namespace);
    UpdateTableRequest request = IcebergRestWire.parseCommitViewRequest(requestJson);
    viewsApiValidator.validateReplaceView(databaseId, view, request);
    ViewMetadata metadata =
        viewsService.replaceView(
            TableIdentifier.of(databaseId, view),
            request.requirements(),
            request.updates(),
            actingPrincipal);
    return jsonResponse(HttpStatus.OK, IcebergRestWire.toLoadViewResultJson(metadata));
  }

  @Override
  public ApiResponse<Void> dropView(String namespace, String view, String actingPrincipal) {
    String databaseId = singleLevelNamespace(namespace);
    viewsApiValidator.validateViewIdentifier(databaseId, view);
    viewsService.dropView(TableIdentifier.of(databaseId, view), actingPrincipal);
    return ApiResponse.<Void>builder().httpStatus(HttpStatus.NO_CONTENT).build();
  }

  @Override
  public ApiResponse<Void> viewExists(String namespace, String view, String actingPrincipal) {
    String databaseId = singleLevelNamespace(namespace);
    viewsApiValidator.validateViewIdentifier(databaseId, view);
    if (viewsService.viewExists(TableIdentifier.of(databaseId, view), actingPrincipal)) {
      return ApiResponse.<Void>builder().httpStatus(HttpStatus.NO_CONTENT).build();
    }
    // Deliberately a throw rather than a 404 return: an absent view goes through the exception
    // path like every other failure, so it gets the same failure-path service audit event (the
    // envelope itself is suppressed for HEAD by the exception handler either way).
    throw new ViewApiException(
        ViewErrorCode.NO_SUCH_VIEW, String.format("View %s.%s does not exist", databaseId, view));
  }

  /**
   * Reject a multi-level namespace as the spec's 404 for an absent namespace. The message echoes no
   * payload: the namespace segments are identifiers, but an arbitrarily long compound is not worth
   * copying into audit events, so the message is fixed.
   */
  private static String singleLevelNamespace(String namespace) {
    if (namespace != null && namespace.contains(NAMESPACE_SEPARATOR)) {
      throw new ViewApiException(
          ViewErrorCode.DATABASE_NOT_FOUND,
          "Namespace does not exist: OpenHouse namespaces are single-level, and a multi-level"
              + " namespace was provided");
    }
    return namespace;
  }

  private static ApiResponse<String> jsonResponse(HttpStatus status, String body) {
    return ApiResponse.<String>builder().httpStatus(status).responseBody(body).build();
  }
}
