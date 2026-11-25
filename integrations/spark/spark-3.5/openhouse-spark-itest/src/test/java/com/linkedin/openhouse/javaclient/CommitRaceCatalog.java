package com.linkedin.openhouse.javaclient;

import com.linkedin.openhouse.gen.tables.client.api.SnapshotApi;
import com.linkedin.openhouse.gen.tables.client.api.TableApi;
import com.linkedin.openhouse.spark.smoke.CommitRace;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileIO;

/**
 * The OpenHouse Spark catalog whose table operations call {@link CommitRace#beforeCommit} at the
 * start of each commit attempt ({@code doCommit}, which sends the snapshots PUT or the table PUT to
 * the tables service, after Iceberg built the commit on its base), and record each attempt's
 * outcome. Everything else is the production catalog and client: test only, the {@code
 * catalog-impl} of the smoke suite's second catalog on the legacy path, and the backing catalog of
 * {@link CommitRaceRestShimCatalog}. As {@code CommitHookCatalog} of the jobs' concurrency tests.
 */
public class CommitRaceCatalog extends com.linkedin.openhouse.spark.OpenHouseCatalog {

  @Override
  public OpenHouseTableOperations newTableOps(TableIdentifier tableIdentifier) {
    OpenHouseTableOperations ops = super.newTableOps(tableIdentifier);
    return new RacingOperations(
        ops.getTableIdentifier(),
        ops.getFileIO(),
        ops.getTableApi(),
        ops.getSnapshotApi(),
        ops.getCluster());
  }

  static final class RacingOperations extends OpenHouseTableOperations {
    RacingOperations(
        TableIdentifier tableIdentifier,
        FileIO fileIO,
        TableApi tableApi,
        SnapshotApi snapshotApi,
        String cluster) {
      super(tableIdentifier, fileIO, tableApi, snapshotApi, cluster);
    }

    @Override
    public void doCommit(TableMetadata base, TableMetadata metadata) {
      CommitRace.beforeCommit(tableName());
      try {
        super.doCommit(base, metadata);
      } catch (RuntimeException e) {
        CommitRace.afterCommit(tableName(), e);
        throw e;
      }
      CommitRace.afterCommit(tableName(), null);
    }
  }
}
