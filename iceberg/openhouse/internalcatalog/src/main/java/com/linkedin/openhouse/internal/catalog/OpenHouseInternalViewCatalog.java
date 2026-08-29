package com.linkedin.openhouse.internal.catalog;

import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.view.BaseMetastoreViewCatalog;
import org.apache.iceberg.view.ViewOperations;

/**
 * The Iceberg {@link org.apache.iceberg.catalog.ViewCatalog} over House Tables.
 *
 * <h2>Why this is a separate class</h2>
 *
 * <p>The view surface would sit naturally on {@link OpenHouseInternalCatalog}, and did until this
 * split. It cannot: that class is a Spring {@code @Component}, Spring reads a component's
 * superclass chain while parsing the context, and {@link BaseMetastoreViewCatalog} does not exist
 * before Iceberg 1.4. The iceberg-1.2 test fixture boots the tables application with Iceberg 1.2 so
 * that Spark 3.1 integration tests have a server to talk to, and a component extending a class that
 * is not on that classpath fails startup outright — not the view routes, the whole application.
 *
 * <p>Keeping the view catalog out of the component scan puts every {@code org.apache.iceberg.view}
 * reference behind a construction the 1.2 context never performs. This class is instantiated only
 * where views are switched on, which is by definition a deployment running an Iceberg that has
 * them.
 *
 * <h2>Table operations are the shared catalog's</h2>
 *
 * <p>{@link BaseMetastoreViewCatalog} extends Iceberg's table catalog, so this class inherits a
 * table surface it has no business reimplementing. Every table-side method delegates to the one
 * catalog bean, so a caller that reaches a table through here gets the same object, the same
 * metrics and the same metadata cache as one that went to the bean directly. Nothing here is a
 * second implementation of anything.
 */
public class OpenHouseInternalViewCatalog extends BaseMetastoreViewCatalog {

  private final OpenHouseInternalCatalog catalog;

  public OpenHouseInternalViewCatalog(OpenHouseInternalCatalog catalog) {
    this.catalog = catalog;
  }

  @Override
  public String name() {
    return getClass().getSimpleName();
  }

  @Override
  protected boolean isValidIdentifier(TableIdentifier identifier) {
    return identifier != null && NamespaceUtil.isTableNamespace(identifier.namespace());
  }

  // ------------------------------------------------------------------------------------------
  // Views
  // ------------------------------------------------------------------------------------------

  /**
   * Builds the operations a view commit runs through.
   *
   * <p>Deliberately not given the metrics reporter or the metadata cache that the table path passes
   * along. Both are keyed to table commits — the reporter's counters name table operations and the
   * cache stores {@code TableMetadata} — so handing them to a view would either miscount or not
   * typecheck. Views get their own once there is something worth measuring.
   */
  @Override
  protected ViewOperations newViewOps(TableIdentifier viewIdentifier) {
    return new OpenHouseViewOperations(
        catalog.houseTableRepository,
        catalog.resolveFileIO(viewIdentifier),
        catalog.fileIOManager,
        viewIdentifier);
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
    return catalog.houseTableRepository.findAllViewsByDatabaseId(namespace.toString()).stream()
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
    HouseTablePrimaryKey primaryKey = primaryKey(identifier);
    if (!catalog.houseTableRepository.findViewById(primaryKey).isPresent()) {
      return false;
    }
    catalog.houseTableRepository.deleteViewById(primaryKey);
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

  /**
   * The House Tables row a <b>table</b> holds at this identifier, if any.
   *
   * <p>Exposed here so the views service can answer "is this name already taken" without holding a
   * second catalog reference. Views and tables share one key space, and the answer is a row read
   * rather than a table load: a table whose metadata cannot be parsed still holds its name.
   *
   * @param identifier the name to check
   * @return the table's row, or empty when no table holds the name
   */
  public Optional<HouseTable> findHouseTable(TableIdentifier identifier) {
    return catalog.findHouseTable(identifier);
  }

  // ------------------------------------------------------------------------------------------
  // Tables, delegated
  // ------------------------------------------------------------------------------------------

  @Override
  protected TableOperations newTableOps(TableIdentifier tableIdentifier) {
    return catalog.newTableOps(tableIdentifier);
  }

  @Override
  protected String defaultWarehouseLocation(TableIdentifier tableIdentifier) {
    // Same refusal as the table catalog's: locations are allocated by the service, never invented
    // by Iceberg. Reached through this class when a view create omits a location.
    throw new UnsupportedOperationException("Location will be provided explicitly");
  }

  @Override
  public List<TableIdentifier> listTables(Namespace namespace) {
    return catalog.listTables(namespace);
  }

  @Override
  public boolean dropTable(TableIdentifier identifier, boolean purge) {
    return catalog.dropTable(identifier, purge);
  }

  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    catalog.renameTable(from, to);
  }

  private static HouseTablePrimaryKey primaryKey(TableIdentifier identifier) {
    return HouseTablePrimaryKey.builder()
        .databaseId(identifier.namespace().toString())
        .tableId(identifier.name())
        .build();
  }
}
