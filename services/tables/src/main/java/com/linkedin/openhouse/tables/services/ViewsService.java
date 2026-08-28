package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.tables.exception.ViewCommitConflictException;
import com.linkedin.openhouse.tables.exception.ViewNameConflictException;
import com.linkedin.openhouse.tables.model.ViewCreationRequest;
import com.linkedin.openhouse.tables.model.ViewIdentifiersPage;
import com.linkedin.openhouse.tables.model.ViewPageRequest;
import java.util.List;
import java.util.Optional;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.view.ViewMetadata;

/**
 * Service interface backing the Iceberg REST views endpoints.
 *
 * <p>The interface speaks unwrapped catalog-domain types — {@link ViewMetadata}, {@link
 * MetadataUpdate}, {@link UpdateRequirement}, identifiers and page requests — never the wire
 * envelopes ({@code CreateViewRequest}, {@code UpdateTableRequest}). Unwrapping is the API
 * handler's job, which keeps this seam reusable by a future non-REST caller and keeps wire-shape
 * churn out of the service contract.
 *
 * <h2>Outcome contract</h2>
 *
 * <p>Outcomes a caller is expected to handle are in the signature; only the genuinely exceptional
 * is thrown unchecked. Concretely:
 *
 * <ul>
 *   <li><b>Absence is a value.</b> {@link #loadView} returns an empty {@link Optional} and {@link
 *       #dropView} returns {@code false}. A view that is not there is half of what these methods
 *       are for, so it is not signalled by unwinding the stack.
 *   <li><b>Contention is checked.</b> {@link ViewNameConflictException} and {@link
 *       ViewCommitConflictException} are ordinary results of racing writers, so the compiler makes
 *       every caller say what it does about them.
 *   <li><b>Nothing is nullable.</b> No parameter or return of this interface accepts or produces
 *       {@code null}; optionality is carried by {@link Optional} or by a request type that models
 *       the absent case ({@link ViewPageRequest#unpaged()}, {@link
 *       ViewCreationRequest#getLocation()}).
 *   <li><b>Unchecked means unexpected.</b> {@code ViewApiException} still escapes for input the
 *       validator was supposed to have rejected, for admission-control refusals, and for storage
 *       that is unreachable. Those are not outcomes a caller chooses between.
 * </ul>
 *
 * <p>This is deliberately not the shape of the surrounding service code, which reports expected
 * outcomes by throwing unchecked exceptions and passes {@code null} to mean absent. That vocabulary
 * is a constraint at the HTTP edge — the Spring advice renders unchecked {@code ViewApiException}s
 * and cannot be changed from here — not a pattern to carry inward. The adapter in the API handler
 * is where these results become that vocabulary again, and it is the only place that should.
 */
public interface ViewsService {

  /**
   * Load the complete metadata of a view.
   *
   * @param identifier view identifier (single-level namespace plus view name)
   * @param actingPrincipal authenticated user
   * @return the view's metadata, or empty if no such view exists
   * @throws com.linkedin.openhouse.tables.exception.ViewApiException carrying {@code
   *     DATABASE_NOT_FOUND} or {@code VIEWS_DISABLED}
   */
  Optional<ViewMetadata> loadView(TableIdentifier identifier, String actingPrincipal);

  /**
   * Check whether a view exists.
   *
   * @param identifier view identifier
   * @param actingPrincipal authenticated user
   * @return true iff the view exists and the principal may know that
   * @throws com.linkedin.openhouse.tables.exception.ViewApiException carrying {@code
   *     DATABASE_NOT_FOUND} or {@code VIEWS_DISABLED}
   */
  boolean viewExists(TableIdentifier identifier, String actingPrincipal);

  /**
   * List view identifiers in a database.
   *
   * <p>Pagination contract (spec obligation): for a {@link ViewPageRequest#isUnpaged()} request the
   * service must return <b>all</b> results in a single page with no continuation token. This is not
   * a nicety — the 1.5.2.17 client issues one GET and follows no token, so paginating an un-tokened
   * request silently truncates that client's listing.
   *
   * @param databaseId single-level namespace to list
   * @param pageRequest the caller's paging instruction, never {@code null}
   * @param actingPrincipal authenticated user
   * @return one page of identifiers plus the continuation token
   * @throws com.linkedin.openhouse.tables.exception.ViewApiException carrying {@code
   *     DATABASE_NOT_FOUND} or {@code VIEWS_DISABLED} (rendered {@code NoSuchNamespaceException} /
   *     404 on this route)
   */
  ViewIdentifiersPage listViews(
      String databaseId, ViewPageRequest pageRequest, String actingPrincipal);

  /**
   * Create a view.
   *
   * <p>The service owns version-id, schema-id and timestamp assignment, and defaults an absent
   * {@code openhouse.source-dialect} summary entry to the sole representation's dialect.
   *
   * @param request the unwrapped creation request
   * @param actingPrincipal authenticated user
   * @return the complete metadata of the created view
   * @throws ViewNameConflictException when a view or a table already holds the name
   * @throws com.linkedin.openhouse.tables.exception.ViewApiException carrying {@code
   *     DATABASE_NOT_FOUND} or {@code VIEWS_DISABLED}, or an admission code
   */
  ViewMetadata createView(ViewCreationRequest request, String actingPrincipal)
      throws ViewNameConflictException;

  /**
   * Commit updates to an existing view (the spec's replace-view operation).
   *
   * @param identifier view identifier
   * @param requirements commit requirements; the views surface supports {@code assert-view-uuid}
   * @param updates typed metadata updates to apply
   * @param actingPrincipal authenticated user
   * @return the complete metadata after the commit, or empty if no such view exists
   * @throws ViewCommitConflictException when a requirement fails against the current view
   * @throws com.linkedin.openhouse.tables.exception.ViewApiException carrying {@code
   *     DATABASE_NOT_FOUND} or {@code VIEWS_DISABLED}, or an admission code
   */
  Optional<ViewMetadata> replaceView(
      TableIdentifier identifier,
      List<UpdateRequirement> requirements,
      List<MetadataUpdate> updates,
      String actingPrincipal)
      throws ViewCommitConflictException;

  /**
   * Drop a view.
   *
   * @param identifier view identifier
   * @param actingPrincipal authenticated user
   * @return true if this call dropped the view, false if it did not exist
   * @throws com.linkedin.openhouse.tables.exception.ViewApiException carrying {@code
   *     DATABASE_NOT_FOUND} or {@code VIEWS_DISABLED}
   */
  boolean dropView(TableIdentifier identifier, String actingPrincipal);
}
