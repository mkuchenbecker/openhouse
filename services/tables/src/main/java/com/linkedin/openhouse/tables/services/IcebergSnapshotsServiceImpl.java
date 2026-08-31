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
   * <p>A registration failure is logged and swallowed because the namespace store is not yet the
   * source of truth for a database's existence — reads still derive it from the table store, so a
   * missing row costs nothing a client can observe, while failing the write would break table
   * creation over a store that is not load-bearing. This must become fatal once the store is the
   * source of truth and the derived fallback is deleted — here and in its twin, {@code
   * TablesServiceImpl#registerNamespace}, which the same flip has to reach.
   *
   * <p>Concurrent registration of the same database is already the store's problem to absorb, and
   * is pinned by {@code NamespacesServiceImplTest#ensureNamespaceIsIdempotent}.
   *
   * <p>Swallowed is not the same as unobserved: every failure here is a database that now exists
   * with no namespace row, so each one increments {@link
   * MetricsConstant#NAMESPACE_REGISTRATION_FAILED_CTR}. A namespace store failing writes shows up
   * there rather than in whatever the backfill later has to clean up.
   */
  private void registerNamespace(String databaseId) {
    try {
      namespacesService.ensureNamespace(databaseId);
    } catch (Exception e) {
      meterRegistry.counter(MetricsConstant.NAMESPACE_REGISTRATION_FAILED_CTR).increment();
      log.warn(
          "Failed to register database {} in the namespace store while creating a table through a"
              + " snapshot commit; the table write continues.",
          databaseId,
          e);
    }
  }

  private boolean isTableLocked(TableDto tableDto) {
    return tableDto.getPolicies() != null
        && tableDto.getPolicies().getLockState() != null
        && tableDto.getPolicies().getLockState().isLocked();
  }
}
