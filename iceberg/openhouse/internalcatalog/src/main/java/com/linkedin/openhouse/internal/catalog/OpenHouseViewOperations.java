package com.linkedin.openhouse.internal.catalog;

import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableNotFoundException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.view.BaseViewOperations;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewMetadataParser;

/**
 * {@link org.apache.iceberg.view.ViewOperations} over House Tables, the view counterpart of {@link
 * OpenHouseInternalTableOperations}.
 *
 * <h2>Two deliberate departures from the table implementation</h2>
 *
 * <p><b>The House Tables row is built from the metadata, not from properties.</b> The table path
 * round-trips its House Tables fields through {@code openhouse.}-prefixed entries in the table's
 * own properties, which the mapper then extracts. Views do not: every field below is read off
 * {@link ViewMetadata} and the identifier directly. View properties are user-supplied, and a
 * property-derived row would let a caller choose values the server owns — most sharply {@code
 * entityType}, which decides whether House Tables stores this key as a view or a table.
 *
 * <p><b>No {@code commit} override.</b> {@link OpenHouseInternalTableOperations} overrides {@code
 * commit} to suppress the forced refresh after a staged create or replace, because a staged table
 * writes metadata that was never persisted to House Tables. Iceberg's view API has no staged create
 * — {@code BaseMetastoreViewCatalog.buildView().create()} commits directly — so there is no
 * corresponding state to protect and the inherited {@code commit} is correct as it stands.
 *
 * <h2>The reads and writes are view-scoped</h2>
 *
 * <p>Every House Tables call below goes through the repository's view methods rather than the table
 * ones. House Tables scopes access by entity type: its table-scoped point read treats a view at the
 * key as absent, so a view written through the table put would have been invisible to its own
 * {@link #doRefresh} — created, then not found. The distinction is not an optimization and the two
 * families are not interchangeable.
 *
 * <h2>Failure vocabulary</h2>
 *
 * <p>This class throws Iceberg's unchecked exceptions rather than returning typed outcomes. That is
 * the {@code ViewOperations} SPI's contract, which Iceberg's own commit loops branch on and which
 * cannot be changed from here. It is the constraint at this edge, not the pattern used above it:
 * the service interface this eventually backs states its outcomes in its signatures.
 */
@Slf4j
public class OpenHouseViewOperations extends BaseViewOperations {

  private final HouseTableRepository houseTableRepository;
  private final FileIO fileIO;
  private final FileIOManager fileIOManager;
  private final TableIdentifier viewIdentifier;

  public OpenHouseViewOperations(
      HouseTableRepository houseTableRepository,
      FileIO fileIO,
      FileIOManager fileIOManager,
      TableIdentifier viewIdentifier) {
    this.houseTableRepository = houseTableRepository;
    this.fileIO = fileIO;
    this.fileIOManager = fileIOManager;
    this.viewIdentifier = viewIdentifier;
  }

  @Override
  protected String viewName() {
    return viewIdentifier.toString();
  }

  @Override
  protected FileIO io() {
    return fileIO;
  }

  @Override
  protected void doRefresh() {
    Optional<HouseTable> houseTable = Optional.empty();
    try {
      houseTable = houseTableRepository.findViewById(primaryKey());
    } catch (HouseTableNotFoundException absent) {
      // Expected while a view is being created: Iceberg refreshes before the first commit.
      log.debug("No House Tables entry for view {}", viewName());
    }

    if (!houseTable.isPresent() && currentMetadataLocation() != null) {
      // We held a metadata location and the row is gone, so something else dropped the view under
      // us. Refreshing to "absent" here would silently turn a concurrent drop into a create on the
      // next commit.
      throw new IllegalStateException(
          String.format(
              "Cannot find view %s after refresh, maybe another process deleted it", viewName()));
    }

    refreshFromMetadataLocation(houseTable.map(HouseTable::getTableLocation).orElse(null));
  }

  @Override
  protected void doCommit(ViewMetadata base, ViewMetadata metadata) {
    String newMetadataLocation = rootMetadataFileLocation(metadata, currentVersion() + 1);

    // Written before the House Tables row is updated. A metadata file with no row pointing at it is
    // garbage a later cleanup can find; a row pointing at a file that was never written is a view
    // that cannot be loaded.
    ViewMetadataParser.write(metadata, io().newOutputFile(newMetadataLocation));

    try {
      houseTableRepository.saveView(buildHouseTable(base, metadata, newMetadataLocation));
    } catch (HouseTableConcurrentUpdateException e) {
      // House Tables rejected the compare-and-swap on tableVersion (a 409): another writer
      // committed between our refresh and this save, and ours definitely did not land.
      // CommitFailedException is the type Iceberg's commit loops treat as retriable.
      throw new CommitFailedException(
          e, "Cannot commit view %s: it was modified concurrently", viewName());
    } catch (HouseTableRepositoryStateUnknownException e) {
      // House Tables answered 5xx, or the call timed out. The write may or may not have been
      // applied, and the difference matters: CommitFailedException would tell Iceberg the commit
      // definitely did not happen, so it would delete the metadata file we just wrote and let the
      // caller retry — orphaning a committed view or writing a second version over it.
      // CommitStateUnknownException is the type that suppresses both behaviours.
      throw new CommitStateUnknownException(e);
    }
  }

  /**
   * The metadata file path: {@code <view-location>/%05d-<uuid>.metadata.json}.
   *
   * <p>Root directory and uncompressed, matching the OpenHouse table convention rather than
   * Iceberg's {@code <location>/metadata/….json.gz} default. This is why {@code doCommit} writes
   * the file itself instead of calling the inherited {@code writeNewMetadataIfRequired}: that
   * helper routes through a private path builder which hard-codes the {@code metadata/} segment and
   * the gzip extension, with no hook to redirect either.
   *
   * <p>The {@code %05d-} prefix is not decorative. Iceberg's {@code
   * BaseViewOperations.parseVersion} reads the version back out of the file name by taking the
   * substring before the first {@code -} after the last {@code /}, so a name that did not start
   * with the zero-padded version would make every refresh report version {@code -1}.
   */
  private static String rootMetadataFileLocation(ViewMetadata metadata, int newVersion) {
    return String.format(
        "%s/%s",
        metadata.location(),
        String.format("%05d-%s%s", newVersion, UUID.randomUUID(), ".metadata.json"));
  }

  private HouseTablePrimaryKey primaryKey() {
    return HouseTablePrimaryKey.builder()
        .databaseId(viewIdentifier.namespace().toString())
        .tableId(viewIdentifier.name())
        .build();
  }

  /**
   * Builds the row House Tables will store.
   *
   * <p>{@code tableVersion} carries the <i>previous</i> metadata location, or {@code
   * INITIAL_VERSION} on a create. That is the compare-and-swap token: House Tables rejects a write
   * whose token does not match what it holds, which is what makes concurrent commits safe.
   */
  private HouseTable buildHouseTable(
      ViewMetadata base, ViewMetadata metadata, String newMetadataLocation) {
    String now = String.valueOf(Instant.now(Clock.systemUTC()).toEpochMilli());
    return HouseTable.builder()
        .databaseId(viewIdentifier.namespace().toString())
        .tableId(viewIdentifier.name())
        .tableUUID(metadata.uuid())
        .tableLocation(newMetadataLocation)
        .tableVersion(
            base == null
                ? CatalogConstants.INITIAL_VERSION
                : Optional.ofNullable(currentMetadataLocation())
                    .orElse(CatalogConstants.INITIAL_VERSION))
        .storageType(fileIOManager.getStorage(fileIO).getType().getValue())
        .entityType(CatalogConstants.VIEW_ENTITY_TYPE)
        .lastModifiedTime(Long.parseLong(now))
        .creationTime(base == null ? Long.parseLong(now) : 0L)
        .build();
  }
}
