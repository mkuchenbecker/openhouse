package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.housetables.api.spec.model.UserTable;
import com.linkedin.openhouse.housetables.dto.model.UserTableDto;
import java.util.List;
import org.springframework.data.domain.Page;

/** Service Interface for Implementing /hts/tables endpoint. */
public interface UserTablesService {
  /**
   * @param databaseId part of the primary composite key
   * @param tableId part of the primary composite key
   * @return {@link UserTableDto}. Avoid using {@link UserTable} directly for decoupling between
   *     service and transport layer.
   */
  UserTableDto getUserTable(String databaseId, String tableId);

  /**
   * Reads the occupant of a key whatever its type, for collision detection. Absence must mean
   * genuine absence, so repository and hydration failures propagate rather than reading as free.
   */
  UserTableDto getNeutralEntity(String databaseId, String tableId);

  /** View-scoped point read; a table or legacy null at the key resolves as absent. */
  UserTableDto getUserView(String databaseId, String tableId);

  /**
   * Given a partially filled {@link UserTable} object, prepare list of {@link UserTableDto}s that
   * matches with the provided {@link UserTable}. See
   * com.linkedin.openhouse.housetables.dto.model.UserTableDto#match for the definition of match.
   *
   * @param userTable object served as filtering condition.
   * @return list of {@link UserTableDto}s that matches the provided {@link UserTable}
   */
  List<UserTableDto> getAllUserTables(UserTable userTable);

  /**
   * Given a partially filled {@link UserTable} object, prepare a paginated {@link UserTableDto}s
   * that matches with the provided {@link UserTable}. See
   * com.linkedin.openhouse.housetables.dto.model.UserTableDto#match for the definition of match.
   *
   * @param userTable
   * @param page The page number to be retrieved
   * @param size The number of {@link UserTableDto}s in the specified page
   * @param sortBy The results sorted by field in {@link UserTable}. For example, tableId,
   *     databaseId
   * @return
   */
  Page<UserTableDto> getAllUserTables(UserTable userTable, int page, int size, String sortBy);

  /**
   * Unlike {@link #getAllUserTables(UserTable)}, an empty query returns every view rather than a
   * projection of database names; database enumeration stays type-agnostic on the table query.
   *
   * <p>Takes the service-owned {@link UserViewQuery} rather than the transport {@link UserTable}:
   * the handler maps a validated request into it at the boundary, so the view read contract admits
   * exactly a database id and an optional table pattern. The table-query methods still accept the
   * transport type; changing them uniformly is a service-wide refactor tracked separately.
   */
  List<UserTableDto> getAllUserViews(UserViewQuery userViewQuery);

  Page<UserTableDto> getAllUserViews(
      UserViewQuery userViewQuery, int page, int size, String sortBy);

  /**
   * Given a databaseId and tableId, delete the user table entry from the House Table. The {@code
   * isSoftDelete} flag is table-only; {@link #deleteUserView} has no equivalent by design.
   */
  void deleteUserTable(String databaseId, String tableId, boolean isSoftDelete);

  /**
   * Always a hard delete: {@code soft_deleted_user_table_row} carries no discriminator, so a view
   * routed through it would restore as a table.
   */
  void deleteUserView(String databaseId, String tableId);

  /**
   * Create or update a table row in House table. This entry point supplies its own {@code TABLE}
   * type to the shared persistence primitive, so a caller cannot persist a view through it: a
   * payload may agree with the type or omit it, and a contradiction is rejected.
   *
   * @param userTable The object attempted to be used for update/creation.
   * @return the row as persisted, together with whether the write replaced an existing occupant or
   *     created the key.
   * @throws com.linkedin.openhouse.common.exception.RequestValidationFailureException if the
   *     payload's {@code entityType} contradicts this entry point's {@code TABLE}.
   * @throws com.linkedin.openhouse.common.exception.StorageIntegrityViolationException if the write
   *     breaks a storage constraint other than the row key.
   */
  PutResult putUserTable(UserTable userTable);

  /**
   * The view-typed twin of {@link #putUserTable}: supplies {@code VIEW} itself, so the method
   * establishes the invariant its name promises even for a caller that bypasses the controller's
   * wire mismatch check.
   *
   * @throws com.linkedin.openhouse.common.exception.RequestValidationFailureException if the
   *     payload's {@code entityType} contradicts this entry point's {@code VIEW}.
   * @throws com.linkedin.openhouse.common.exception.StorageIntegrityViolationException if the write
   *     breaks a storage constraint other than the row key.
   */
  PutResult putUserView(UserTable userView);

  /**
   * Rename a {@link UserTable} row in House table. Table-only: views are not renameable, so there
   * is deliberately no view equivalent.
   *
   * @param fromDatabaseId The databaseId of the row to rename.
   * @param fromTableId The tableId of the row to rename.
   * @param toDatabaseId The new databaseId of the renamed row.
   * @param toTableId The new tableId of the renamed row.
   * @param metadataLocation The new metadata file of the table with updated table properties for
   *     updated ids.
   * @throws com.linkedin.openhouse.common.exception.StorageIntegrityViolationException if the
   *     rename breaks a storage constraint other than the destination key.
   */
  void renameUserTable(
      String fromDatabaseId,
      String fromTableId,
      String toDatabaseId,
      String toTableId,
      String metadataLocation);

  /**
   * Restore a soft-deleted user table identified by its databaseId, tableId, and deletedAtMs
   *
   * @param databaseId
   * @param tableId
   * @param deletedAtMs
   * @throws com.linkedin.openhouse.common.exception.StorageIntegrityViolationException if the
   *     restoring write breaks a storage constraint other than the row key.
   */
  UserTableDto restoreUserTable(String databaseId, String tableId, Long deletedAtMs);

  /**
   * Delete soft deleted user tables given a databaseId, tableId that are older than the given
   * purgeAfterMs
   *
   * @param databaseId
   * @param tableId
   * @param purgeAfterMs
   */
  void purgeSoftDeletedUserTables(String databaseId, String tableId, Long purgeAfterMs);

  /**
   * Get all soft deleted tables by filters.
   *
   * <p>Currently the filters supported are limited to databaseId, tableId, and purgeAfterMs.
   *
   * @param userTable
   * @param page
   * @param size
   * @param sortBy
   * @return
   */
  Page<UserTableDto> getAllSoftDeletedTables(
      UserTable userTable, int page, int size, String sortBy);
}
