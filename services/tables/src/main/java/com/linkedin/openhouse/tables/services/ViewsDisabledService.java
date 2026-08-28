package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
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
 * The {@link ViewsService} used when view support is switched off for this deployment: every
 * operation reports that views are disabled.
 *
 * <p>It throws a {@link ViewApiException} carrying {@link ViewErrorCode#VIEWS_DISABLED}, which the
 * views exception handler renders as a spec 404: {@code NoSuchNamespaceException} on the create and
 * list routes and {@code NoSuchViewException} on the per-view routes, matching the spec's own
 * per-route 404 vocabulary. A stock {@code RESTCatalog} client therefore treats the views surface
 * as absent — Spark's {@code ResolveViews} falls through to {@code loadTable} — which preserves the
 * design's default-off posture with zero client-side special-casing.
 *
 * <p>Note that this class throws rather than answering the "absent" value the contract offers
 * ({@code Optional.empty()}, {@code false}). That is deliberate and is the one place the
 * distinction matters: an empty {@code Optional} from {@link #loadView} would mean "this catalog
 * serves views and has no such view", which renders the same 404 but is a different claim from
 * "this catalog does not serve views". Only the second is true here, and the disabled posture
 * depends on it staying true for {@link #viewExists} and {@link #dropView}, whose {@code false}
 * would otherwise be indistinguishable from a served-but-empty catalog.
 */
public class ViewsDisabledService implements ViewsService {

  /**
   * Fixed and redacted. The message is copied into the error body and into service audit events, so
   * it must never echo request content.
   */
  static final String VIEWS_DISABLED_MESSAGE = "Views are disabled";

  @Override
  public Optional<ViewMetadata> loadView(TableIdentifier identifier, String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public boolean viewExists(TableIdentifier identifier, String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public ViewIdentifiersPage listViews(
      String databaseId, ViewPageRequest pageRequest, String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public ViewMetadata createView(ViewCreationRequest request, String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public Optional<ViewMetadata> replaceView(
      TableIdentifier identifier,
      List<UpdateRequirement> requirements,
      List<MetadataUpdate> updates,
      String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public boolean dropView(TableIdentifier identifier, String actingPrincipal) {
    throw viewsDisabled();
  }

  private ViewApiException viewsDisabled() {
    return new ViewApiException(ViewErrorCode.VIEWS_DISABLED, VIEWS_DISABLED_MESSAGE);
  }
}
