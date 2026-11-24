package com.linkedin.openhouse.javaclient;

import com.linkedin.openhouse.gen.tables.client.api.SnapshotApi;
import com.linkedin.openhouse.gen.tables.client.api.TableApi;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileIO;

/**
 * The OpenHouse Spark catalog, whose table operations run {@link CommitHook}'s armed step at the
 * start of each commit attempt ({@code doCommit}, which sends the snapshots PUT or the table PUT to
 * the tables service), and record each attempt's outcome. Everything else is the production catalog
 * and client: test only, set as {@code catalog-impl} on the session of the writer that is to lose
 * the race.
 */
public class CommitHookCatalog extends com.linkedin.openhouse.spark.OpenHouseCatalog {

  @Override
  public OpenHouseTableOperations newTableOps(TableIdentifier tableIdentifier) {
    OpenHouseTableOperations ops = super.newTableOps(tableIdentifier);
    return new HookedOperations(
        ops.getTableIdentifier(),
        ops.getFileIO(),
        ops.getTableApi(),
        ops.getSnapshotApi(),
        ops.getCluster());
  }

  static final class HookedOperations extends OpenHouseTableOperations {
    HookedOperations(
        TableIdentifier tableIdentifier,
        FileIO fileIO,
        TableApi tableApi,
        SnapshotApi snapshotApi,
        String cluster) {
      super(tableIdentifier, fileIO, tableApi, snapshotApi, cluster);
    }

    @Override
    public void doCommit(TableMetadata base, TableMetadata metadata) {
      CommitHook.beforeAttempt(tableName());
      try {
        super.doCommit(base, metadata);
      } catch (RuntimeException e) {
        CommitHook.afterAttempt(tableName(), e);
        throw e;
      }
      CommitHook.afterAttempt(tableName(), null);
    }
  }
}
