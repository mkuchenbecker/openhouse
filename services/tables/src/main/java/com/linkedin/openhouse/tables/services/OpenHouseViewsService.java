package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.internal.catalog.OpenHouseInternalViewCatalog;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewCommitConflictException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.exception.ViewNameConflictException;
import com.linkedin.openhouse.tables.model.ViewCreationRequest;
import com.linkedin.openhouse.tables.model.ViewIdentifiersPage;
import com.linkedin.openhouse.tables.model.ViewPageRequest;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewBuilder;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewOperations;
import org.apache.iceberg.view.ViewRepresentation;
import org.apache.iceberg.view.ViewVersion;

/**
 * The {@link ViewsService} that actually stores views, over {@link OpenHouseInternalViewCatalog}.
 *
 * <h2>Why the catalog rather than the operations alone</h2>
 *
 * <p>Create goes through Iceberg's {@link ViewBuilder}, not through hand-assembled {@link
 * ViewMetadata}. The builder owns decisions this service should not re-derive — version-id and
 * schema-id assignment, the initial version log entry, UUID generation — and every one of them is
 * checked by Iceberg's own conformance suite. Storage placement is the exception: the location is
 * supplied here rather than defaulted by the builder, because where an entity lives is the
 * cluster's decision. Reads and commits use the operations directly, because {@code View} exposes
 * no metadata document and a commit needs the base to compare against.
 *
 * <h2>Outcome translation</h2>
 *
 * <p>The catalog speaks Iceberg's unchecked exceptions because that is the SPI's contract. This
 * class is where they become the outcomes {@link ViewsService} declares: absence a value,
 * contention checked, and the one genuinely exceptional case — a commit whose outcome the server
 * cannot determine — a {@code ViewApiException} carrying the status that says so. Nothing above
 * this class sees an Iceberg exception, and nothing below it sees a {@code ViewApiException}.
 */
public class OpenHouseViewsService implements ViewsService {

  private final OpenHouseInternalViewCatalog catalog;
  private final StorageSelector storageSelector;

  public OpenHouseViewsService(
      OpenHouseInternalViewCatalog catalog, StorageSelector storageSelector) {
    this.catalog = catalog;
    this.storageSelector = storageSelector;
  }

  @Override
  public Optional<ViewMetadata> loadView(TableIdentifier identifier, String actingPrincipal) {
    return currentMetadata(identifier);
  }

  @Override
  public boolean viewExists(TableIdentifier identifier, String actingPrincipal) {
    return currentMetadata(identifier).isPresent();
  }

  @Override
  public ViewIdentifiersPage listViews(
      String databaseId, ViewPageRequest pageRequest, String actingPrincipal) {
    List<TableIdentifier> identifiers = catalog.listViews(Namespace.of(databaseId));
    // Complete on every request, tokened or not. The spec obliges a complete answer to an
    // un-tokened request, and this catalog has no cursor to resume from, so a token it did not
    // issue cannot mean anything to it. Returning everything is correct for both cases; returning
    // a continuation token would promise a resumption this service cannot honour.
    return ViewIdentifiersPage.complete(identifiers);
  }

  @Override
  public ViewMetadata createView(ViewCreationRequest request, String actingPrincipal)
      throws ViewNameConflictException {
    ViewVersion requested = request.getRequestedVersion();
    SQLViewRepresentation sql = soleSqlRepresentation(request);
    rejectIfATableHoldsTheName(request.getIdentifier());

    ViewBuilder builder =
        catalog
            .buildView(request.getIdentifier())
            .withSchema(request.getSchema())
            .withQuery(sql.dialect(), sql.sql())
            .withDefaultNamespace(requested.defaultNamespace())
            .withProperties(request.getProperties())
            .withLocation(allocateLocation(request.getIdentifier(), actingPrincipal));
    if (requested.defaultCatalog() != null) {
      builder = builder.withDefaultCatalog(requested.defaultCatalog());
    }

    try {
      builder.create();
    } catch (AlreadyExistsException e) {
      // Iceberg raises the same type whichever entity holds the name, so ask which it was. The two
      // render the same 409 but not the same message, and a client debugging a failed create needs
      // to know whether it collided with a view or with a table.
      throw new ViewNameConflictException(
          request.getIdentifier(),
          conflictKind(request.getIdentifier()),
          String.format("View already exists: %s", request.getIdentifier()));
    } catch (CommitStateUnknownException e) {
      throw commitStateUnknown(request.getIdentifier(), e);
    }

    // buildView().create() returns a View, which carries no metadata document, so read it back.
    return currentMetadata(request.getIdentifier())
        .orElseThrow(
            () ->
                new ViewApiException(
                    ViewErrorCode.NO_SUCH_VIEW,
                    String.format(
                        "View %s was created and then dropped before it could be read back",
                        request.getIdentifier())));
  }

  @Override
  public Optional<ViewMetadata> replaceView(
      TableIdentifier identifier,
      List<UpdateRequirement> requirements,
      List<MetadataUpdate> updates,
      String actingPrincipal)
      throws ViewCommitConflictException {
    ViewOperations operations = catalog.viewOperations(identifier);
    ViewMetadata base = operations.refresh();
    if (base == null) {
      return Optional.empty();
    }

    // Requirements are validated against the base we just read, before any update is applied, so a
    // stale client is rejected without a partially-built metadata document existing at all.
    for (UpdateRequirement requirement : requirements) {
      try {
        requirement.validate(base);
      } catch (CommitFailedException e) {
        throw new ViewCommitConflictException(
            identifier,
            String.format("Cannot commit view %s: a requirement failed", identifier),
            e);
      }
    }

    ViewMetadata.Builder builder = ViewMetadata.buildFrom(base);
    for (MetadataUpdate update : updates) {
      update.applyTo(builder);
    }

    try {
      operations.commit(base, builder.build());
    } catch (CommitFailedException e) {
      throw new ViewCommitConflictException(
          identifier,
          String.format("Cannot commit view %s: it was modified concurrently", identifier),
          e);
    } catch (CommitStateUnknownException e) {
      throw commitStateUnknown(identifier, e);
    }
    return currentMetadata(identifier);
  }

  @Override
  public boolean dropView(TableIdentifier identifier, String actingPrincipal) {
    return catalog.dropView(identifier);
  }

  /**
   * The stored metadata, or empty when no view holds the identifier.
   *
   * <p>Absence arrives as a null from {@code refresh()}, which is why this is the only place that
   * touches one: {@link Optional} is what every caller above sees. A view that exists but cannot be
   * read is a different outcome and is not caught here — it is a fault the caller cannot fix, and
   * turning it into a 404 would tell them their view is gone when it is not.
   */
  private Optional<ViewMetadata> currentMetadata(TableIdentifier identifier) {
    return Optional.ofNullable(catalog.viewOperations(identifier).refresh());
  }

  /**
   * The commit reached House Tables and no answer came back.
   *
   * <p>Distinct from a conflict, and the distinction is the whole point: a conflict means the write
   * definitely did not land, so a client can rebuild and retry. Here it may have landed. The
   * response says so — a 500 typed {@code CommitStateUnknownException}, which is what tells a
   * client to re-read the view rather than retry blind.
   */
  private ViewApiException commitStateUnknown(
      TableIdentifier identifier, CommitStateUnknownException cause) {
    return new ViewApiException(
        ViewErrorCode.COMMIT_STATE_UNKNOWN,
        String.format(
            "Cannot determine whether the commit to view %s was applied; re-read the view",
            identifier),
        cause);
  }

  /**
   * Where this view's metadata will be written.
   *
   * <p>The location is always supplied explicitly: {@code OpenHouseInternalViewCatalog} refuses to
   * invent one, because storage placement is the cluster's decision rather than Iceberg's. Views go
   * through the same allocator as tables, so a view's directory sits beside its database's tables
   * under the configured root prefix, and the {@code -<uuid>} suffix keeps a re-created view from
   * landing on the directory its dropped predecessor left behind.
   *
   * <p>The property map handed to the allocator is empty on purpose. {@code allocateTableLocation}
   * reads a property that can override the directory name, and view properties are caller-authored:
   * passing them through would hand the caller the choice this method exists to make.
   *
   * <p>The UUID here names the directory and nothing else. It is not the view's identity — that is
   * the UUID Iceberg assigns to the metadata document, which is what {@code assert-view-uuid}
   * compares against and what the House Tables row records — and the two are generated
   * independently because the location has to exist before there is any metadata to read a UUID
   * from.
   */
  private String allocateLocation(TableIdentifier identifier, String actingPrincipal) {
    return storageSelector
        .selectStorage(identifier.namespace().toString(), identifier.name())
        .allocateTableLocation(
            identifier.namespace().toString(),
            identifier.name(),
            UUID.randomUUID().toString(),
            actingPrincipal,
            Collections.emptyMap());
  }

  /**
   * Refuses a create whose name a table already holds.
   *
   * <p>Iceberg's own already-exists check asks the view operations, and those are view-scoped: a
   * table at the identifier reads as absent, so the create would sail past it. House Tables is the
   * authority — it keeps tables and views in one key space and rejects a view write onto a table's
   * key — but its rejection surfaces as a failed commit, after a metadata file has been written and
   * with no way to say what the name collided with. Asking first turns the common case into the
   * conflict it is, before anything is allocated or written.
   *
   * <p>This is a check, not a lock: a table created between here and the commit still gets caught,
   * by House Tables, and reaches the caller through the {@link AlreadyExistsException} handler
   * below. That is the narrow race; this is the wide case.
   */
  private void rejectIfATableHoldsTheName(TableIdentifier identifier)
      throws ViewNameConflictException {
    if (aTableHoldsTheName(identifier)) {
      throw new ViewNameConflictException(
          identifier,
          ViewNameConflictException.Kind.TABLE,
          String.format("View already exists: %s", identifier));
    }
  }

  /**
   * Which entity holds a contested name.
   *
   * <p>Asked only after a create has already failed, so the extra lookup costs nothing on the happy
   * path. A table at the identifier is the interesting case: views and tables share one House
   * Tables key space, so a create can collide with a table the caller never mentioned.
   */
  private ViewNameConflictException.Kind conflictKind(TableIdentifier identifier) {
    return aTableHoldsTheName(identifier)
        ? ViewNameConflictException.Kind.TABLE
        : ViewNameConflictException.Kind.VIEW;
  }

  /**
   * Whether a table row holds this identifier.
   *
   * <p>Deliberately the House Tables row rather than {@code tableExists}, which loads the table and
   * so parses its {@code metadata.json}. Answering "is this name taken" must not depend on the
   * named entity being readable: a table whose metadata is missing or corrupt still holds its name,
   * and a probe that threw there would turn a name conflict into a 500 about someone else's table.
   * It is also a great deal cheaper — one row read instead of an object-store round trip.
   */
  private boolean aTableHoldsTheName(TableIdentifier identifier) {
    return catalog.findHouseTable(identifier).isPresent();
  }

  /**
   * The single SQL representation a create carries.
   *
   * <p>Validation has already rejected a request with no SQL representation, with a non-SQL one, or
   * with two sharing a dialect, so anything other than exactly one here is a defect upstream rather
   * than bad input — hence an unchecked failure rather than a checked outcome.
   */
  private SQLViewRepresentation soleSqlRepresentation(ViewCreationRequest request) {
    for (ViewRepresentation representation : request.getRequestedVersion().representations()) {
      if (representation instanceof SQLViewRepresentation) {
        return (SQLViewRepresentation) representation;
      }
    }
    throw new ViewApiException(
        ViewErrorCode.REQUIRED_REPRESENTATION_MISSING,
        String.format("View %s carries no SQL representation", request.getIdentifier()));
  }
}
