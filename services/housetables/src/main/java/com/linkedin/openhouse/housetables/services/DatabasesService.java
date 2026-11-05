package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.dto.model.DatabaseDto;
import com.linkedin.openhouse.housetables.dto.model.DatabasePropertiesUpdateDto;
import java.util.List;
import java.util.Map;

/**
 * Service for the database entity. Database rows are independent of user table rows: nothing here
 * reads or writes {@link com.linkedin.openhouse.housetables.model.UserTableRow}.
 */
public interface DatabasesService {

  /**
   * Create a database row with a server-generated UUID.
   *
   * @param database the database to create; the UUID and creation time in it are ignored.
   * @return the created row.
   * @throws com.linkedin.openhouse.common.exception.AlreadyExistsException if a database with the
   *     same name (ignoring case) exists.
   */
  DatabaseDto createDatabase(Database database);

  /**
   * Look up a database row by name, ignoring case.
   *
   * @throws com.linkedin.openhouse.common.exception.NoSuchEntityException if absent.
   */
  DatabaseDto getDatabase(String databaseId);

  /**
   * Delete a database row by name, ignoring case. Table rows in the database are untouched.
   *
   * @throws com.linkedin.openhouse.common.exception.NoSuchEntityException if absent.
   */
  void deleteDatabase(String databaseId);

  /**
   * Apply a sparse property update: set every key in {@code updates}, remove every key in {@code
   * removals}, leave every other property untouched. Performed as one optimistic read-modify-write
   * on the row.
   *
   * @param databaseId name of the database, matched ignoring case.
   * @param updates keys to set; may be null.
   * @param removals keys to remove; may be null.
   * @return the keys that were updated, removed, and requested for removal but missing.
   * @throws com.linkedin.openhouse.common.exception.UnprocessableEntityException if a key is in
   *     both {@code updates} and {@code removals}; checked before any read.
   * @throws com.linkedin.openhouse.common.exception.NoSuchEntityException if absent.
   * @throws com.linkedin.openhouse.common.exception.EntityConcurrentModificationException if the
   *     row changed between the read and the write.
   */
  DatabasePropertiesUpdateDto updateDatabaseProperties(
      String databaseId, Map<String, String> updates, List<String> removals);
}
