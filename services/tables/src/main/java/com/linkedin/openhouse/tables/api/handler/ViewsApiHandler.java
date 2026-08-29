package com.linkedin.openhouse.tables.api.handler;

import com.linkedin.openhouse.common.api.spec.ApiResponse;

/**
 * Layer between the Iceberg REST views routes and the view service. Implementations hold no
 * business logic: they parse the wire body with Iceberg's parsers, validate, unwrap the envelope
 * into catalog-domain types for the service, and serialize the service's result back into the
 * spec's response documents.
 *
 * <p>Bodies are raw JSON strings in both directions — the controllers neither bind nor serialize
 * through Spring's message converters, so wire compliance is a property of Iceberg's parsers and a
 * malformed body is a views-surface 400 rather than a global-handler concern.
 */
public interface ViewsApiHandler {

  /**
   * Client bootstrap: {@code GET /v1/config}. Served even while views are disabled — bootstrap must
   * precede the per-route 404s.
   *
   * @return 200 with the catalog config document, including the explicit endpoints list
   */
  ApiResponse<String> getConfig();

  /**
   * List views in a namespace.
   *
   * @param namespace raw decoded namespace path segment
   * @param pageToken opaque continuation token, or {@code null}
   * @param pageSize requested page size, or {@code null}
   * @param actingPrincipal authenticated user
   * @return 200 with a {@code ListTablesResponse} document
   */
  ApiResponse<String> listViews(
      String namespace, String pageToken, Integer pageSize, String actingPrincipal);

  /**
   * Create a view.
   *
   * @param namespace raw decoded namespace path segment
   * @param requestJson raw {@code CreateViewRequest} body, possibly {@code null}
   * @param actingPrincipal authenticated user
   * @return 200 with a {@code LoadViewResult} document
   */
  ApiResponse<String> createView(String namespace, String requestJson, String actingPrincipal);

  /**
   * Load a view's complete metadata.
   *
   * @param namespace raw decoded namespace path segment
   * @param view view name
   * @param actingPrincipal authenticated user
   * @return 200 with a {@code LoadViewResult} document
   */
  ApiResponse<String> loadView(String namespace, String view, String actingPrincipal);

  /**
   * Commit updates to a view (the spec's replace-view operation).
   *
   * @param namespace raw decoded namespace path segment
   * @param view view name
   * @param requestJson raw {@code CommitViewRequest} body, possibly {@code null}
   * @param actingPrincipal authenticated user
   * @return 200 with a {@code LoadViewResult} document
   */
  ApiResponse<String> replaceView(
      String namespace, String view, String requestJson, String actingPrincipal);

  /**
   * Drop a view.
   *
   * @param namespace raw decoded namespace path segment
   * @param view view name
   * @param actingPrincipal authenticated user
   * @return 204 with no body
   */
  ApiResponse<Void> dropView(String namespace, String view, String actingPrincipal);

  /**
   * Check whether a view exists ({@code HEAD}).
   *
   * @param namespace raw decoded namespace path segment
   * @param view view name
   * @param actingPrincipal authenticated user
   * @return 204 with no body when the view exists
   */
  ApiResponse<Void> viewExists(String namespace, String view, String actingPrincipal);
}
