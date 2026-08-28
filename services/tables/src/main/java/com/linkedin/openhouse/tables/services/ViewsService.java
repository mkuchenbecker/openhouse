package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.tables.model.ViewIdentifiersPage;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;

/**
 * Service interface backing the Iceberg REST views endpoints.
 *
 * <p>The interface speaks unwrapped catalog-domain types — {@link ViewMetadata}, {@link
 * MetadataUpdate}, {@link UpdateRequirement}, identifiers and page tokens — never the wire
 * envelopes ({@code CreateViewRequest}, {@code UpdateTableRequest}). Unwrapping is the API
 * handler's job, which keeps this seam reusable by a future non-REST caller and keeps wire-shape
 * churn out of the service contract.
 */
public interface ViewsService {

  /**
   * Load the complete metadata of a view.
   *
   * @param identifier view identifier (single-level namespace plus view name)
   * @param actingPrincipal authenticated user
   * @return the complete current view metadata
   */
  ViewMetadata loadView(TableIdentifier identifier, String actingPrincipal);

  /**
   * Check whether a view exists.
   *
   * @param identifier view identifier
   * @param actingPrincipal authenticated user
   * @return true iff the view exists and the principal may know that
   */
  boolean viewExists(TableIdentifier identifier, String actingPrincipal);

  /**
   * List view identifiers in a database.
   *
   * <p>Pagination contract (spec obligation): when {@code pageToken} is {@code null} the service
   * must return <b>all</b> results in a single page; when a token is supplied, the service returns
   * the next page and a new token, or a {@code null} token to terminate. Tokens are opaque to the
   * caller.
   *
   * @param databaseId single-level namespace to list
   * @param pageToken opaque continuation token, or {@code null} for an unpaged full listing
   * @param pageSize requested page size, or {@code null} when the caller did not specify one
   * @param actingPrincipal authenticated user
   * @return one page of identifiers plus the continuation token
   */
  ViewIdentifiersPage listViews(
      String databaseId, String pageToken, Integer pageSize, String actingPrincipal);

  /**
   * Create a view.
   *
   * @param identifier view identifier from the request path and body name
   * @param schema the view schema
   * @param requestedVersion the view version to create; the service owns version-id, schema-id and
   *     timestamp assignment, and defaults an absent {@code openhouse.source-dialect} summary entry
   *     to the sole representation's dialect
   * @param location caller-requested location, or {@code null} for the server-owned default
   * @param properties user view properties (reserved keys already rejected by validation)
   * @param actingPrincipal authenticated user
   * @return the complete metadata of the created view
   */
  ViewMetadata createView(
      TableIdentifier identifier,
      Schema schema,
      ViewVersion requestedVersion,
      String location,
      Map<String, String> properties,
      String actingPrincipal);

  /**
   * Commit updates to an existing view (the spec's replace-view operation).
   *
   * @param identifier view identifier
   * @param requirements commit requirements; the views surface supports {@code assert-view-uuid}
   * @param updates typed metadata updates to apply
   * @param actingPrincipal authenticated user
   * @return the complete metadata after the commit
   */
  ViewMetadata replaceView(
      TableIdentifier identifier,
      List<UpdateRequirement> requirements,
      List<MetadataUpdate> updates,
      String actingPrincipal);

  /**
   * Drop a view.
   *
   * @param identifier view identifier
   * @param actingPrincipal authenticated user
   */
  void dropView(TableIdentifier identifier, String actingPrincipal);
}
