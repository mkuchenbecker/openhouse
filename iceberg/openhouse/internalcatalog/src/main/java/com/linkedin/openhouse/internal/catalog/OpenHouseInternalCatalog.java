package com.linkedin.openhouse.internal.catalog;

import static com.linkedin.openhouse.internal.catalog.InternalCatalogMetricsConstant.METRICS_PREFIX;

import com.linkedin.openhouse.cluster.metrics.micrometer.MetricsReporter;
import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.common.api.spec.TableUri;
import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.NoSuchSoftDeletedUserTableException;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.internal.catalog.cache.TableMetadataCache;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.model.SoftDeletedTableDto;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableNotFoundException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.SupportsPrefixOperations;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.view.BaseMetastoreViewCatalog;
import org.apache.iceberg.view.ViewOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Iceberg Catalog Implementation for OpenHouse User Table persisted as Iceberg tables. Built on-top
 * of HouseTableService where the Iceberg table root pointer is persisted. A custom implementation
 * can be built on top of this by extending this class and making that bean the primary.
 */
@Slf4j
@Component
public class OpenHouseInternalCatalog extends BaseMetastoreViewCatalog {

  @Autowired HouseTableRepository houseTableRepository;

  @Autowired FileIOManager fileIOManager;

  @Autowired StorageManager storageManager;

  @Autowired StorageSelector storageSelector;

  @Autowired StorageType storageType;

  @Autowired HouseTableMapper houseTableMapper;

  @Autowired MeterRegistry meterRegistry;

  @Autowired TableMetadataCache tableMetadataCache;

  @Override
  protected TableOperations newTableOps(TableIdentifier tableIdentifier) {
    FileIO fileIO = resolveFileIO(tableIdentifier);
    MetricsReporter metricsReporter =
        new MetricsReporter(this.meterRegistry, METRICS_PREFIX, Lists.newArrayList());
    return new OpenHouseInternalTableOperations(
        houseTableRepository,
        fileIO,
        houseTableMapper,
        tableIdentifier,
        metricsReporter,
        fileIOManager,
        tableMetadataCache);
  }

  @Override
  protected boolean isValidIdentifier(TableIdentifier tableIdentifier) {
    return tableIdentifier != null && NamespaceUtil.isTableNamespace(tableIdentifier.namespace());
  }

  @Override
  public String name() {
    return getClass().getSimpleName();
  }

  @Override
  protected String defaultWarehouseLocation(TableIdentifier tableIdentifier) {
    throw new UnsupportedOperationException("Location will be provided explicitly");
  }

  @Override
  public List<TableIdentifier> listTables(Namespace namespace) {
    NamespaceUtil.validateOperationNamespace(namespace);
    // TODO: Implement SupportsNamespace interface and listNamespaces() method to remove this
    //  branch. This is anti-pattern and only a temporary solution.
    if (namespace.isEmpty()) {
      return StreamSupport.stream(houseTableRepository.findAll().spliterator(), false)
          .map(houseTable -> TableIdentifier.of(houseTable.getDatabaseId(), "Unused"))
          .collect(Collectors.toList());
    }
    return houseTableRepository.findAllByDatabaseId(namespace.toString()).stream()
        .map(houseTable -> TableIdentifier.of(houseTable.getDatabaseId(), houseTable.getTableId()))
        .collect(Collectors.toList());
  }

  public Page<TableIdentifier> listTables(Namespace namespace, Pageable pageable) {
    NamespaceUtil.validateOperationNamespace(namespace);
    if (namespace.isEmpty()) {
      return houseTableRepository
          .findAll(pageable)
          .map(houseTable -> TableIdentifier.of(houseTable.getDatabaseId(), "Unused"));
    }
    return houseTableRepository
        .findAllByDatabaseId(namespace.toString(), pageable)
        .map(houseTable -> TableIdentifier.of(houseTable.getDatabaseId(), houseTable.getTableId()));
  }

  /**
   * Paginated listing that preserves the underlying {@link HouseTable} rows, so callers can read
   * HTS-resident columns (e.g. tableLocation) without an extra metadata.json load per table.
   */
  public Page<HouseTable> listHouseTables(Namespace namespace, Pageable pageable) {
    NamespaceUtil.validateOperationNamespace(namespace);
    return houseTableRepository.findAllByDatabaseId(namespace.toString(), pageable);
  }

  /**
   * Direct HTS lookup that returns the {@link HouseTable} row without parsing metadata.json. Use
   * this when only HTS-resident columns (e.g. tableUUID, tableLocation) are needed — for example,
   * to authorize a drop without loading the full Iceberg table, which is important when the
   * underlying metadata is corrupted and {@link #loadTable} would throw.
   */
  public Optional<HouseTable> findHouseTable(TableIdentifier identifier) {
    HouseTablePrimaryKey primaryKey =
        HouseTablePrimaryKey.builder()
            .databaseId(identifier.namespace().toString())
            .tableId(identifier.name())
            .build();
    try {
      return houseTableRepository.findById(primaryKey);
    } catch (HouseTableNotFoundException e) {
      return Optional.empty();
    }
  }

  @Override
  public boolean dropTable(TableIdentifier identifier, boolean purge) {
    // Look up the HouseTable row directly instead of calling loadTable(), so drop works even when
    // the table's metadata.json is corrupted and cannot be parsed by TableMetadataParser.
    HouseTable houseTable =
        findHouseTable(identifier)
            .orElseThrow(() -> new NoSuchTableException("Table does not exist: %s", identifier));

    HouseTablePrimaryKey primaryKey =
        HouseTablePrimaryKey.builder()
            .databaseId(identifier.namespace().toString())
            .tableId(identifier.name())
            .build();
    String tableLocation = getTableBaseLocation(houseTable, identifier);
    FileIO fileIO = resolveFileIO(identifier);
    log.debug("Dropping table {}, purge:{}", tableLocation, purge);
    try {
      houseTableRepository.deleteById(primaryKey, purge);
    } catch (HouseTableRepositoryException houseTableRepositoryException) {
      throw new RuntimeException(
          String.format("The table %s cannot be dropped due to the server side error:", identifier),
          houseTableRepositoryException);
    }
    if (purge) {
      if (fileIO instanceof SupportsPrefixOperations) {
        log.debug("Deleting files for table {}", tableLocation);
        ((SupportsPrefixOperations) fileIO).deletePrefix(tableLocation);
      } else {
        log.debug(
            "Failed to delete files for table {}. fileIO does not support prefix operations.",
            tableLocation);
        throw new UnsupportedOperationException(
            "Drop table is supported only with a fileIO instance that SupportsPrefixOperations");
      }
    }
    return true;
  }

  /**
   * Returns the table base directory derived from the HouseTable's metadata location. OpenHouse
   * writes metadata.json directly under the table base subdir, so the parent of the metadata.json
   * path is the same value that {@link org.apache.iceberg.Table#location()} would return.
   */
  private static String getTableBaseLocation(HouseTable houseTable, TableIdentifier identifier) {
    String metadataLocation = houseTable.getTableLocation();
    // Defensive check to avoid any unintentional deletion
    if (!metadataLocation.endsWith(".metadata.json")) {
      throw new IllegalStateException(
          String.format(
              "Refusing to drop %s: metadata_location does not look like a metadata.json file: %s",
              identifier, metadataLocation));
    }
    return new Path(metadataLocation).getParent().toString();
  }

  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    Table fromTable = loadTable(from);
    String tableClusterId = fromTable.properties().get(CatalogConstants.OPENHOUSE_CLUSTERID_KEY);

    // Preserve existing case if databases are the same
    String toDatabaseName =
        from.namespace().toString().equalsIgnoreCase(to.namespace().toString())
            ? from.namespace().toString()
            : to.namespace().toString();

    TableUri tableUri =
        TableUri.builder()
            .clusterId(tableClusterId)
            .databaseId(toDatabaseName)
            .tableId(to.name())
            .build();

    Transaction transaction = fromTable.newTransaction();
    UpdateProperties updateProperties = transaction.updateProperties();
    log.info(
        "Setting preserved table properties {} to {}, {} to {}, and {} to {} for table rename",
        CatalogConstants.OPENHOUSE_TABLEID_KEY,
        to.name(),
        CatalogConstants.OPENHOUSE_DATABASEID_KEY,
        toDatabaseName,
        CatalogConstants.OPENHOUSE_TABLEURI_KEY,
        tableUri.toString());
    updateProperties.set(CatalogConstants.OPENHOUSE_TABLEID_KEY, to.name());
    updateProperties.set(CatalogConstants.OPENHOUSE_DATABASEID_KEY, toDatabaseName);
    updateProperties.set(CatalogConstants.OPENHOUSE_TABLEURI_KEY, tableUri.toString());
    updateProperties.commit();
    transaction.commitTransaction();
  }

  public Page<SoftDeletedTableDto> searchSoftDeletedTables(
      Namespace namespace, String tableId, Pageable pageable) {
    NamespaceUtil.validateOperationNamespace(namespace);

    try {
      return houseTableRepository
          .searchSoftDeletedTables(namespace.toString(), tableId, pageable)
          .map(
              houseTable ->
                  SoftDeletedTableDto.builder()
                      .tableId(houseTable.getTableId())
                      .databaseId(houseTable.getDatabaseId())
                      .tableLocation(houseTable.getTableLocation())
                      .deletedAtMs(houseTable.getDeletedAtMs())
                      .purgeAfterMs(houseTable.getPurgeAfterMs())
                      .build());
    } catch (Exception e) {
      throw new RuntimeException(
          String.format(
              "Failed to search soft deleted tables with namespace %s and tableId %s",
              namespace.toString(), tableId),
          e);
    }
  }

  public void purgeSoftDeletedTables(String databaseId, String tableId, long purgeAfterMs) {
    log.info(
        "Purging soft deleted tables for databaseId: {}, tableId: {}, purgeAfterMs: {}",
        databaseId,
        tableId,
        purgeAfterMs);
    houseTableRepository.purgeSoftDeletedTables(databaseId, tableId, purgeAfterMs);
  }

  public void restoreTable(String databaseId, String tableId, long deletedAtMs) {
    log.info(
        "Restoring soft deleted table for databaseId: {}, tableId: {}, deletedAtMs: {}",
        databaseId,
        tableId,
        deletedAtMs);
    try {
      houseTableRepository.restoreTable(databaseId, tableId, deletedAtMs);
    } catch (HouseTableNotFoundException e) {
      throw new NoSuchSoftDeletedUserTableException(databaseId, tableId, deletedAtMs, e);
    } catch (HouseTableConcurrentUpdateException e) {
      throw new AlreadyExistsException("Table", databaseId + "." + tableId, e);
    }
  }

  /**
   * Get the file IO for a table. if table exists, return the fileIO for the storageType in hts else
   * return the fileio for storageType returned by storage selector
   *
   * @param tableIdentifier
   * @return fileIO
   */
  protected FileIO resolveFileIO(TableIdentifier tableIdentifier) {
    Optional<HouseTable> houseTable = Optional.empty();
    try {
      houseTable =
          houseTableRepository.findById(
              HouseTablePrimaryKey.builder()
                  .databaseId(tableIdentifier.namespace().toString())
                  .tableId(tableIdentifier.name())
                  .build());
    } catch (HouseTableNotFoundException e) {
      log.info(
          "House table entry not found {}.{}",
          tableIdentifier.namespace().toString(),
          tableIdentifier.name());
    }
    StorageType.Type type =
        houseTable.isPresent()
            ? storageType.fromString(houseTable.get().getStorageType())
            : storageSelector
                .selectStorage(tableIdentifier.namespace().toString(), tableIdentifier.name())
                .getType();

    return fileIOManager.getFileIO(type);
  }

  /**
   * Builds the operations a view commit runs through.
   *
   * <p>Deliberately not given the metrics reporter or the metadata cache that {@link #newTableOps}
   * passes along. Both are keyed to table commits — the reporter's counters name table operations
   * and the cache stores {@code TableMetadata} — so handing them to a view would either miscount or
   * not typecheck. Views get their own once there is something worth measuring.
   */
  @Override
  protected ViewOperations newViewOps(TableIdentifier viewIdentifier) {
    return new OpenHouseViewOperations(
        houseTableRepository, resolveFileIO(viewIdentifier), fileIOManager, viewIdentifier);
  }

  /**
   * The view operations for an identifier, for callers that need {@link
   * org.apache.iceberg.view.ViewMetadata} rather than a {@link org.apache.iceberg.view.View}.
   *
   * <p>Iceberg's {@code View} interface deliberately exposes no metadata document — it offers
   * schema, versions, history and properties, but not the object the REST spec's {@code
   * LoadViewResult} is built from, and not the base a commit compares against. The REST layer needs
   * both, so it needs the operations. This is a widening of {@link #newViewOps} rather than a
   * second path to it: same object, public.
   *
   * @param viewIdentifier the view
   * @return operations bound to that identifier, not yet refreshed
   */
  public ViewOperations viewOperations(TableIdentifier viewIdentifier) {
    return newViewOps(viewIdentifier);
  }

  /**
   * Every view in a namespace.
   *
   * <p>Reads through the view-scoped House Tables route, so a table sharing the namespace is not
   * returned. Unlike {@link #listTables} there is no empty-namespace branch: that branch exists to
   * enumerate databases, which is a table-side concern.
   */
  @Override
  public List<TableIdentifier> listViews(Namespace namespace) {
    NamespaceUtil.validateOperationNamespace(namespace);
    return houseTableRepository.findAllViewsByDatabaseId(namespace.toString()).stream()
        .map(houseTable -> TableIdentifier.of(houseTable.getDatabaseId(), houseTable.getTableId()))
        .collect(Collectors.toList());
  }

  /**
   * Drops a view, returning whether this call was the one that removed it.
   *
   * <p>The row is looked up first rather than deleted blind, so that dropping something that is not
   * there is reported as {@code false} instead of succeeding silently — {@code
   * ViewCatalog.dropView} is specified to answer whether the view existed.
   *
   * <p>The metadata file is deliberately left behind. The table path purges storage on drop because
   * a table owns data files whose bytes dominate; a view owns one metadata document, and deleting
   * it eagerly would make a concurrent reader holding that location fail on a missing file rather
   * than on a missing view. Cleanup belongs to whatever sweeps orphaned metadata.
   */
  @Override
  public boolean dropView(TableIdentifier identifier) {
    HouseTablePrimaryKey primaryKey =
        HouseTablePrimaryKey.builder()
            .databaseId(identifier.namespace().toString())
            .tableId(identifier.name())
            .build();
    if (!houseTableRepository.findViewById(primaryKey).isPresent()) {
      return false;
    }
    houseTableRepository.deleteViewById(primaryKey);
    return true;
  }

  /**
   * Not supported, and not merely unimplemented here.
   *
   * <p>House Tables' rename is table-only by construction: {@code renameUserTable} is the sole
   * rename on that service and its contract says views are not renameable. Serving the spec's
   * {@code rename-view} therefore needs a view rename on House Tables first, which is a change to
   * that service rather than to this catalog.
   *
   * <p>Throwing is safe here in a way it would not be on {@code loadView}: Iceberg's REST client
   * reaches this only for an explicit {@code ALTER VIEW … RENAME TO}, never as part of resolving an
   * identifier, so nothing probes it speculatively.
   */
  @Override
  public void renameView(TableIdentifier from, TableIdentifier to) {
    throw new UnsupportedOperationException(
        String.format(
            "Cannot rename view %s to %s: this catalog does not support renaming views", from, to));
  }
}
