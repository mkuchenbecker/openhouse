package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.internal.catalog.model.SoftDeletedTableDto;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateLockRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockReason;
import com.linkedin.openhouse.tables.api.spec.v0.response.components.AclPolicy;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoWithMetadata;
import java.util.List;
import java.util.UUID;
import org.apache.iceberg.TableMetadata;
import org.springframework.data.domain.Page;
import org.springframework.data.util.Pair;

/** Service Interface for Implementing /tables endpoint. */
public interface TablesService {

  /**
   * Given a databaseId and tableId, prepare {@link TableDto} if actingPrincipal has the right
   * privilege.
   *
   * @param databaseId
   * @param tableId
   * @param actingPrincipal
   * @return TableDto.
   */
  TableDto getTable(String databaseId, String tableId, String actingPrincipal);

  /**
   * Same lookup and the same checks, in the same order, as {@link #getTable}; also returns the
   * Iceberg table metadata the {@link TableDto} was built from, from the same load.
   *
   * <p>A service that does not offer it keeps the default, which refuses.
   *
   * @throws com.linkedin.openhouse.common.exception.NoSuchUserTableException if table is not found.
   */
  default TableDtoWithMetadata loadTable(
      String databaseId, String tableId, String actingPrincipal) {
    throw new UnsupportedOperationException("loadTable is not supported by this service");
  }

  /**
   * Check whether a table identified by databaseId and tableId exists.
   *
   * @param databaseId
   * @param tableId
   * @return true iff the table exists in the catalog.
   */
  boolean tableExists(String databaseId, String tableId);

  /**
   * Given a databaseId, prepare list of {@link TableDto}s.
   *
   * @param databaseId
   * @return list of {@link TableDto}
   */
  List<TableDto> searchTables(String databaseId);

  /**
   * Given a databaseId, prepare list of {@link TableDto}s. The returned dtos are populated with
   * identifier fields plus any HouseTable-resident fields listed in {@code fields}. Passing null or
   * empty returns identifier-only dtos.
   *
   * <p>When {@code fields} is non-empty, {@code actingPrincipal} must hold the {@code
   * GET_TABLE_METADATA} privilege on the database — per-table sharing ACLs are not consulted by
   * this call, so this guard prevents callers from bulk-reading field data on tables they would not
   * be authorized to read individually.
   *
   * @param databaseId
   * @param page
   * @param size
   * @param sortBy
   * @param fields optional list of {@link TableDto}/{@code GetTableResponseBody} field names to
   *     populate in addition to identifiers
   * @param actingPrincipal authenticated user; required when {@code fields} is non-empty
   * @return list of {@link TableDto}
   */
  Page<TableDto> searchTables(
      String databaseId,
      int page,
      int size,
      String sortBy,
      List<String> fields,
      String actingPrincipal);

  /**
   * Given a {@link CreateUpdateTableRequestBody}, create or update a Openhouse table for it
   *
   * @param createUpdateTableRequestBody
   * @param tableCreatorUpdater authenticated user principal that created a table.
   * @param failOnExist boolean to determine whether to throw an {@link
   *     com.linkedin.openhouse.common.exception.AlreadyExistsException} if table already exists
   * @return A pair of objects: the first {@link TableDto} is the actual saved object, the second
   *     boolean is set to true iff creation occurred. This is to differentiate between creation and
   *     update of {@link TableDto}.
   */
  Pair<TableDto, Boolean> putTable(
      CreateUpdateTableRequestBody createUpdateTableRequestBody,
      String tableCreatorUpdater,
      Boolean failOnExist);

  /**
   * Creates the table {@code createUpdateTableRequestBody} describes, exactly as {@link
   * #putTable(CreateUpdateTableRequestBody, String, Boolean)} with {@code failOnExist} creates one,
   * except that the table's OpenHouse UUID (which decides its location) is {@code tableUUID}
   * instead of one derived from the request. For a caller that told its client the table's location
   * before the create, in a staged create ({@link #stageCreateTable}). A service that does not
   * offer it keeps the default, which refuses.
   */
  default Pair<TableDto, Boolean> createTable(
      CreateUpdateTableRequestBody createUpdateTableRequestBody,
      String tableCreator,
      UUID tableUUID) {
    throw new UnsupportedOperationException(
        "createTable with a given UUID is not supported by this service");
  }

  /**
   * What {@link #putTable(CreateUpdateTableRequestBody, String, Boolean)} would create for {@code
   * createUpdateTableRequestBody}, after the same create checks in the same order (existence,
   * create privilege, client capability, the create's own repository checks), with nothing written:
   * no metadata file, no house-table row. A service that does not offer it keeps the default, which
   * refuses.
   *
   * @return the table's Iceberg metadata as the create would initialize it
   */
  default TableMetadata stageCreateTable(
      CreateUpdateTableRequestBody createUpdateTableRequestBody, String tableCreator) {
    throw new UnsupportedOperationException("stageCreateTable is not supported by this service");
  }

  /**
   * Delete a table represented by databaseId and tableId if actingPrincipal has the right privilege
   *
   * @param databaseId
   * @param tableId
   * @param actingPrincipal
   */
  void deleteTable(String databaseId, String tableId, String actingPrincipal);

  /**
   * Rename a table represented by databaseId and tableId if actingPrincipal has the right
   * privilege.
   *
   * @param fromDatabaseId
   * @param fromTableId
   * @param toDatabaseId
   * @param toTableId
   * @param actingPrincipal
   */
  void renameTable(
      String fromDatabaseId,
      String fromTableId,
      String toDatabaseId,
      String toTableId,
      String actingPrincipal);

  /**
   * Update aclPolicy on a table represented by databaseId and tableId if actingPrincipal has the
   * right privilege.
   *
   * @param databaseId
   * @param tableId
   * @param updateAclPoliciesRequestBody
   * @param actingPrincipal
   */
  void updateAclPolicies(
      String databaseId,
      String tableId,
      UpdateAclPoliciesRequestBody updateAclPoliciesRequestBody,
      String actingPrincipal);

  /**
   * Given a databaseId and a tableId list all the aclPolicies on the table if actingPrincipal has
   * the right privilege.
   *
   * @param databaseId
   * @param tableId
   * @param actingPrincipal
   * @return list of aclPoilcies on the table
   */
  List<AclPolicy> getAclPolicies(String databaseId, String tableId, String actingPrincipal);

  /**
   * Given a databaseId and tableId list all the aclPolicies on the table for a user principal if
   * actingPrincipal (the caller) has the right privilege.
   *
   * @param databaseId
   * @param tableId
   * @param actingPrincipal
   * @param userPrincipal
   * @return list of aclPolicies on the table
   */
  List<AclPolicy> getAclPolicies(
      String databaseId, String tableId, String actingPrincipal, String userPrincipal);

  /**
   * @param databaseId
   * @param tableId
   * @param createUpdateLockRequestBody
   * @param tableCreatorUpdator
   */
  void createLock(
      String databaseId,
      String tableId,
      CreateUpdateLockRequestBody createUpdateLockRequestBody,
      String tableCreatorUpdator);

  /**
   * Delete a table lock represented by databaseId and tableId if actingPrincipal has the right
   * privilege. This overload only removes LEGACY locks.
   *
   * @param databaseId
   * @param tableId
   * @param actingPrincipal
   */
  void deleteLock(String databaseId, String tableId, String actingPrincipal);

  /**
   * Remove a lock only when its reason matches, using existing lock authorization. An inactive lock
   * is a no-op.
   *
   * @param databaseId
   * @param tableId
   * @param reason expected lock reason
   * @param actingPrincipal authenticated caller requiring LOCK_ADMIN permission
   */
  void deleteLock(String databaseId, String tableId, LockReason reason, String actingPrincipal);

  /**
   * Given a databaseId, return a paginated list of soft deleted {@link TableDto}s.
   *
   * @param databaseId
   * @param tableId
   * @param pageable
   * @param sortBy The field to sort by (optional)
   * @return list of {@link SoftDeletedTableDto} soft deleted table metadata
   */
  Page<SoftDeletedTableDto> searchSoftDeletedTables(
      String databaseId, String tableId, int page, int size, String sortBy);

  /**
   * Deletes soft-deleted tables that are older than the specified timestamp.
   *
   * @param databaseId
   * @param tableId
   * @param purgeAfterMs
   * @param actingPrincipal
   * @return list of {@link TableDto}
   */
  void purgeSoftDeletedTables(
      String databaseId, String tableId, long purgeAfterMs, String actingPrincipal);

  /**
   * Restore a soft deleted table
   *
   * @param databaseId
   * @param tableId
   * @param deletedAtMs
   * @param actingPrincipal
   */
  void restoreTable(String databaseId, String tableId, long deletedAtMs, String actingPrincipal);
}
