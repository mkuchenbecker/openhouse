package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.common.api.spec.TableUri;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.commit.CommitContext;
import com.linkedin.openhouse.tables.commit.CommitKind;
import com.linkedin.openhouse.tables.commit.TableCommit;
import com.linkedin.openhouse.tables.dto.mapper.TablesMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import com.linkedin.openhouse.tables.readbridge.ColumnDefaultException;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeStripProtection;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import com.linkedin.openhouse.tables.utils.TableUUIDGenerator;
import java.util.Optional;
import org.apache.iceberg.catalog.TableIdentifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

@Component
public class IcebergSnapshotsServiceImpl implements IcebergSnapshotsService {

  @Autowired TablesService tablesService;

  @Autowired OpenHouseInternalRepository openHouseInternalRepository;

  @Autowired TablesMapper tablesMapper;

  @Autowired TableUUIDGenerator tableUUIDGenerator;

  @Autowired AuthorizationUtils authorizationUtils;

  @Autowired ReadBridgeStripProtection readBridgeStripProtection;

  @Autowired ClientCapabilityGate clientCapabilityGate;

  @Override
  public Pair<TableDto, Boolean> putIcebergSnapshots(
      String databaseId,
      String tableId,
      IcebergSnapshotsRequestBody icebergSnapshotRequestBody,
      String tableCreatorUpdater) {
    Optional<TableDto> tableDto =
        openHouseInternalRepository.findById(
            TableDtoPrimaryKey.builder().databaseId(databaseId).tableId(tableId).build());

    String clusterId = icebergSnapshotRequestBody.getCreateUpdateTableRequestBody().getClusterId();

    TableDto tableDtoToSave =
        tablesMapper.toTableDto(
            tableDto.orElseGet(
                () ->
                    TableDto.builder()
                        .tableId(tableId)
                        .databaseId(databaseId)
                        .clusterId(clusterId)
                        .tableUri(
                            TableUri.builder()
                                .tableId(tableId)
                                .databaseId(databaseId)
                                .clusterId(clusterId)
                                .build()
                                .toString())
                        .tableUUID(
                            tableUUIDGenerator.generateUUID(icebergSnapshotRequestBody).toString())
                        .tableCreator(tableCreatorUpdater)
                        .build()),
            icebergSnapshotRequestBody);

    if (tableDto.isPresent()) {
      // A commit on the stored table, replace (RTAS) commits included: the commit core runs the
      // lock check before the replace-vs-update split, so a replace cannot bypass the lock.
      TableDto mapped = tableDtoToSave;
      return new TableCommitCore(
              openHouseInternalRepository,
              authorizationUtils,
              clientCapabilityGate,
              readBridgeStripProtection)
          .commit(
              new TableCommit(
                  TableIdentifier.of(databaseId, tableId),
                  icebergSnapshotRequestBody.getBaseTableVersion(),
                  tableDto.get(),
                  () -> mapped,
                  CommitContext.builder()
                      .kind(CommitKind.SNAPSHOTS_UPDATE)
                      .principal(tableCreatorUpdater)
                      .clusterId(clusterId)
                      .build()));
    }
    authorizationUtils.checkDatabasePrivilege(
        databaseId, tableCreatorUpdater, Privileges.CREATE_TABLE);
    clientCapabilityGate.checkWrite(
        databaseId, tableId, tableDto, tableDtoToSave.getTableProperties());
    tableDtoToSave = LockPolicyValidator.prepare(null, tableDtoToSave);
    try {
      tableDtoToSave = readBridgeStripProtection.prepare(tableDtoToSave);
    } catch (ColumnDefaultException e) {
      throw new UnsupportedClientOperationException(
          UnsupportedClientOperationException.Operation.COLUMN_DEFAULT, e.getMessage(), e);
    }
    return Pair.of(
        TableCommitCore.saveSnapshots(
            openHouseInternalRepository,
            tableDtoToSave,
            databaseId,
            tableId,
            clusterId,
            icebergSnapshotRequestBody.getBaseTableVersion()),
        true);
  }
}
