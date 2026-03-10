package com.linkedin.openhouse.tables.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.linkedin.openhouse.common.api.spec.TableUri;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.config.HtsTableStatsClient;
import com.linkedin.openhouse.tables.dto.mapper.TablesMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import com.linkedin.openhouse.tables.utils.TableUUIDGenerator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class IcebergSnapshotsServiceImpl implements IcebergSnapshotsService {

  @Autowired TablesService tablesService;

  @Autowired OpenHouseInternalRepository openHouseInternalRepository;

  @Autowired TablesMapper tablesMapper;

  @Autowired TableUUIDGenerator tableUUIDGenerator;

  @Autowired AuthorizationUtils authorizationUtils;

  @Autowired HtsTableStatsClient htsTableStatsClient;

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
      if (isTableLocked(tableDto.get())) {
        throw new UnsupportedClientOperationException(
            UnsupportedClientOperationException.Operation.LOCKED_TABLE_OPERATION,
            String.format(
                "Table %s.%s is in locked state and cannot be written to", databaseId, tableId));
      }
      authorizationUtils.checkTableWritePathPrivileges(
          tableDto.get(), tableCreatorUpdater, Privileges.UPDATE_TABLE_METADATA);
    } else {
      authorizationUtils.checkDatabasePrivilege(
          databaseId, tableCreatorUpdater, Privileges.CREATE_TABLE);
    }
    try {
      TableDto savedDto = openHouseInternalRepository.save(tableDtoToSave);
      List<String> jsonSnapshots = icebergSnapshotRequestBody.getJsonSnapshots();
      if (jsonSnapshots != null && !jsonSnapshots.isEmpty()) {
        long numFilesAdded = 0;
        long numFilesDeleted = 0;
        Long tableSizeBytes = null;
        Gson gson = new Gson();
        for (String snapshotJson : jsonSnapshots) {
          try {
            JsonObject snapshot = gson.fromJson(snapshotJson, JsonObject.class);
            if (!snapshot.has("summary")) {
              continue;
            }
            JsonObject summary = snapshot.getAsJsonObject("summary");
            numFilesAdded += parseLong(summary, "added-data-files");
            numFilesDeleted += parseLong(summary, "deleted-data-files");
            if (summary.has("total-files-size")) {
              tableSizeBytes = parseLong(summary, "total-files-size");
            }
          } catch (Exception e) {
            log.warn("Failed to parse snapshot summary: {}", e.getMessage());
          }
        }
        htsTableStatsClient.reportCommitStats(
            savedDto.getTableUUID(),
            savedDto.getDatabaseId(),
            savedDto.getTableId(),
            savedDto.getClusterId(),
            savedDto.getTableVersion(),
            savedDto.getTableLocation(),
            numFilesAdded,
            numFilesDeleted,
            tableSizeBytes);
      }
      return Pair.of(savedDto, !tableDto.isPresent());
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

  private boolean isTableLocked(TableDto tableDto) {
    return tableDto.getPolicies() != null
        && tableDto.getPolicies().getLockState() != null
        && tableDto.getPolicies().getLockState().isLocked();
  }

  private static long parseLong(JsonObject obj, String key) {
    if (!obj.has(key)) {
      return 0L;
    }
    try {
      return Long.parseLong(obj.get(key).getAsString());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
