package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.model.TableDto;
import org.springframework.data.util.Pair;

/** Service layer for loading Iceberg {@link org.apache.iceberg.Snapshot} provided by client. */
public interface IcebergSnapshotsService {

  /** @return pair of {@link TableDto} object and flag if the table was created. */
  default Pair<TableDto, Boolean> putIcebergSnapshots(
      String databaseId,
      String tableId,
      IcebergSnapshotsRequestBody icebergSnapshotRequestBody,
      String tableCreator) {
    return putIcebergSnapshots(
        databaseId, tableId, icebergSnapshotRequestBody, tableCreator, /*icebergRestCommit*/ false);
  }

  /**
   * As above, but able to say that the commit arrived through the Iceberg REST facade, whose
   * preconditions are Iceberg {@code UpdateRequirement}s rather than a client-declared base
   * version. See {@link TablesService#putTable(
   * com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody, String,
   * Boolean, boolean)}.
   */
  Pair<TableDto, Boolean> putIcebergSnapshots(
      String databaseId,
      String tableId,
      IcebergSnapshotsRequestBody icebergSnapshotRequestBody,
      String tableCreator,
      boolean icebergRestCommit);
}
