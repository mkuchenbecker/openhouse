package com.linkedin.openhouse.tables.api.validator;

import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;

/**
 * Structural validation for the Iceberg REST views API. No SQL is parsed, translated or validated
 * against an engine here: view SQL stays opaque and semantic rejection belongs to a later admission
 * step. Wire-level shape (required fields, kebab-case naming, update/requirement polymorphism) is
 * already enforced by Iceberg's parsers before these methods run; what is validated here are the
 * OpenHouse deployment rules layered on top.
 *
 * <p>Every method throws {@link
 * com.linkedin.openhouse.tables.exception.ViewRequestValidationFailureException} carrying all
 * accumulated failures joined with {@code "; "}.
 */
public interface ViewsApiValidator {

  /**
   * Validate the path identifiers of a per-view route (load, replace, drop, exists).
   *
   * @param databaseId decoded single-level namespace from the path
   * @param viewId view name from the path
   */
  void validateViewIdentifier(String databaseId, String viewId);

  /**
   * Validate a request to list views in a database.
   *
   * @param databaseId decoded single-level namespace from the path
   * @param pageToken opaque continuation token, or {@code null}; never shape-validated
   * @param pageSize requested page size, or {@code null}
   */
  void validateListViews(String databaseId, String pageToken, Integer pageSize);

  /**
   * Validate a parsed create-view request.
   *
   * @param databaseId decoded single-level namespace from the path
   * @param request the parsed request; parser-required fields are already present
   */
  void validateCreateView(String databaseId, CreateViewRequest request);

  /**
   * Validate a parsed commit-view (replace) request.
   *
   * @param databaseId decoded single-level namespace from the path
   * @param viewId view name from the path
   * @param request the parsed commit envelope
   */
  void validateReplaceView(String databaseId, String viewId, UpdateTableRequest request);
}
