package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.model.ViewIdentifiersPage;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;
import org.springframework.stereotype.Component;

/**
 * The only {@link ViewsService} bean today. View business logic is intentionally out of scope for
 * this API-only increment, so every operation reports that views are disabled.
 *
 * <p>It throws a {@link ViewApiException} carrying {@link ViewErrorCode#VIEWS_DISABLED}, which the
 * views exception handler renders as a spec 404: {@code NoSuchNamespaceException} on the create and
 * list routes and {@code NoSuchViewException} on the per-view routes, matching the spec's own
 * per-route 404 vocabulary. A stock {@code RESTCatalog} client therefore treats the views surface
 * as absent — Spark's {@code ResolveViews} falls through to {@code loadTable} — which preserves the
 * design's default-off posture with zero client-side special-casing.
 *
 * <p>The later real service replaces this bean and implements the per-database gate.
 */
@Component
public class ViewsDisabledService implements ViewsService {

  /**
   * Fixed and redacted. The message is copied into the error body and into service audit events, so
   * it must never echo request content.
   */
  static final String VIEWS_DISABLED_MESSAGE = "Views are disabled";

  @Override
  public ViewMetadata loadView(TableIdentifier identifier, String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public boolean viewExists(TableIdentifier identifier, String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public ViewIdentifiersPage listViews(
      String databaseId, String pageToken, Integer pageSize, String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public ViewMetadata createView(
      TableIdentifier identifier,
      Schema schema,
      ViewVersion requestedVersion,
      String location,
      Map<String, String> properties,
      String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public ViewMetadata replaceView(
      TableIdentifier identifier,
      List<UpdateRequirement> requirements,
      List<MetadataUpdate> updates,
      String actingPrincipal) {
    throw viewsDisabled();
  }

  @Override
  public void dropView(TableIdentifier identifier, String actingPrincipal) {
    throw viewsDisabled();
  }

  private ViewApiException viewsDisabled() {
    return new ViewApiException(ViewErrorCode.VIEWS_DISABLED, VIEWS_DISABLED_MESSAGE);
  }
}
