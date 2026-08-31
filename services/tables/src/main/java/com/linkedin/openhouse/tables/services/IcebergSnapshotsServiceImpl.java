package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.common.api.spec.TableUri;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
import com.linkedin.openhouse.common.metrics.MetricsConstant;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.dto.mapper.TablesMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.readbridge.ColumnDefaultException;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeStripProtection;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import com.linkedin.openhouse.tables.utils.TableUUIDGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IcebergSnapshotsServiceImpl implements IcebergSnapshotsService {

  @Autowired TablesService tablesService;

  @Autowired NamespacesService namespacesService;

  /**
   * Non-fatal registration is the right call while the namespace store is not the source of truth,
   * but silent registration is not: without this counter a store outage accumulates databases with
   * no namespace row and nothing says so until the backfill finds them.
   */
  @Autowired MeterRegistry meterRegistry;

  @Autowired OpenHouseInternalRepository openHouseInternalRepository;

  @Autowired TablesMapper tablesMapper;

  @Autowired TableUUIDGenerator tableUUIDGenerator;

  @Autowired AuthorizationUtils authorizationUtils;

  @Autowired ReadBridgeStripProtection readBridgeStripProtection;

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
      // A locked table must reject every write, including CREATE OR REPLACE (RTAS). The lock is
      // checked here — before the replace-vs-update split — so the replace path can no longer
      // bypass it and silently overwrite a locked table.
      if (isTableLocked(tableDto.get())) {
        throw new UnsupportedClientOperationException(
            UnsupportedClientOperationException.Operation.LOCKED_TABLE_OPERATION,
            String.format(
                "Table %s.%s is in locked state and cannot be written to", databaseId, tableId));
      }
      if (icebergSnapshotRequestBody.getCreateUpdateTableRequestBody().isReplaceCommit()) {
        // Check if table creator has the privilege to replace the table.
        authorizationUtils.checkReplaceTablePrivilege(tableDto.get(), tableCreatorUpdater);
      } else {
        authorizationUtils.checkTableWritePathPrivileges(
            tableDto.get(), tableCreatorUpdater, Privileges.UPDATE_TABLE_METADATA);
      }
    } else {
      authorizationUtils.checkDatabasePrivilege(
          databaseId, tableCreatorUpdater, Privileges.CREATE_TABLE);
      // A snapshot commit against a table that does not exist yet creates it, and with it the
      // database it names, exactly as TablesServiceImpl.putTable does. Registering here is what
      // keeps that database from being a table-only database the namespace store has never heard
      // of. Idempotent, and already authorized by the CREATE_TABLE check above.
      registerNamespace(databaseId);
    }
    try {
      tableDtoToSave = readBridgeStripProtection.prepare(tableDto.orElse(null), tableDtoToSave);
    } catch (ColumnDefaultException e) {
      throw e.toUnsupportedClient();
    }
    try {
      return Pair.of(openHouseInternalRepository.save(tableDtoToSave), !tableDto.isPresent());
    } catch (BadRequestException e) {
      throw new RequestValidationFailureException(e.getMessage(), e);
    } catch (CommitFailedException ce) {
      throw new EntityConcurrentModificationException(
          TableUri.builder()
              .tableId(tableId)
              .databaseId(databaseId)
              .clusterId(
                  icebergSnapshotRequestBody.getCreateUpdateTableRequestBody().getClusterId())
              .build()
              .toString(),
          String.format(
              "databaseId : %s, tableId : %s, version: %s %s",
              databaseId,
              tableId,
              icebergSnapshotRequestBody.getBaseTableVersion(),
              "The requested table has been modified/created by other processes."),
          ce);
    }
  }

  /**
   * Registration runs before the table write, so a failed write leaves an unreferenced database row
   * rather than a table whose database does not exist; the reverse order cannot be repaired by
   * anything the caller sees.
   *
   * <p>A registration failure fails the snapshot commit that would have created the table. It used
   * to be swallowed, because the namespace store was not the source of truth and a missing row cost
   * nothing a client could observe: reads derived the database's existence from its tables. Those
   * reads are gone. A swallowed failure now produces a table in a database that the catalog says
   * does not exist — invisible to every listing, undroppable through the namespace API, and
   * repaired only by a backfill nobody knows to run. Refusing the write leaves the cluster
   * consistent and the client able to retry.
   *
   * <p>Concurrent registration of the same database is already the store's problem to absorb, and
   * is pinned by {@code NamespacesServiceImplTest#ensureNamespaceIsIdempotent}, so what reaches
   * this catch is a store that is actually failing.
   *
   * <p>The counter survives the flip: {@link MetricsConstant#NAMESPACE_REGISTRATION_FAILED_CTR}
   * still counts every failure, and now measures table writes lost to the namespace store rather
   * than drift accumulating silently. It is the only thing this method adds — the failure itself
   * goes to the caller, and the request handler that renders it is the one place that logs it, with
   * the request that caused it.
   */
  private void registerNamespace(String databaseId) {
    try {
      namespacesService.ensureNamespace(databaseId);
    } catch (Exception e) {
      meterRegistry.counter(MetricsConstant.NAMESPACE_REGISTRATION_FAILED_CTR).increment();
      throw e;
    }
  }

  private boolean isTableLocked(TableDto tableDto) {
    return tableDto.getPolicies() != null
        && tableDto.getPolicies().getLockState() != null
        && tableDto.getPolicies().getLockState().isLocked();
  }
}
