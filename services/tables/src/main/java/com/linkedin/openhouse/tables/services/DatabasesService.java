package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.components.AclPolicy;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.DatabasePropertiesUpdateDto;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

/** Service Interface for Implementing /databases endpoint. */
public interface DatabasesService {
  /**
   * Get a list of all {@link DatabaseDto}s in openhouse.
   *
   * @return list of {@link DatabaseDto}
   */
  List<DatabaseDto> getAllDatabases();

  /**
   * Get a Page of all {@link DatabaseDto}s in openhouse.
   *
   * @param page
   * @param size
   * @param sortBy
   * @return Page of {@link DatabaseDto}
   */
  Page<DatabaseDto> getAllDatabases(int page, int size, String sortBy);

  /**
   * Update aclPolicies on a database represented by databaseId if actingPrincipal has the right
   * privilege.
   *
   * @param databaseId
   * @param updateAclPoliciesRequestBody
   * @param actingPrincipal
   */
  void updateDatabaseAclPolicies(
      String databaseId,
      UpdateAclPoliciesRequestBody updateAclPoliciesRequestBody,
      String actingPrincipal);

  /**
   * @param databaseId
   * @param actingPrincipal
   * @return list of aclPolicies on the databaseId
   */
  List<AclPolicy> getDatabaseAclPolicies(String databaseId, String actingPrincipal);

  /**
   * Create a database entity (UUID, name, properties) if actingPrincipal has the {@code
   * SYSTEM_ADMIN} privilege. Creating a database entity does not create or alter any table.
   *
   * @param databaseId logical database name
   * @param properties optional initial properties
   * @param actingPrincipal
   * @return the created {@link DatabaseDto}, carrying the server-generated UUID
   * @throws com.linkedin.openhouse.common.exception.AlreadyExistsException if a database entity
   *     with that name exists, ignoring case
   */
  DatabaseDto createDatabase(
      String databaseId, Map<String, String> properties, String actingPrincipal);

  /**
   * Load the database entity by name, ignoring case. Any authenticated principal may read it.
   *
   * @throws com.linkedin.openhouse.common.exception.NoSuchEntityException if no database entity
   *     exists with that name, even when tables exist in that database
   */
  DatabaseDto getDatabase(String databaseId, String actingPrincipal);

  /**
   * Delete the database entity by name if actingPrincipal has the {@code SYSTEM_ADMIN} privilege.
   * Tables in the database are left untouched; the database is not required to be empty.
   *
   * @throws com.linkedin.openhouse.common.exception.NoSuchEntityException if absent
   */
  void deleteDatabase(String databaseId, String actingPrincipal);

  /**
   * Apply a sparse property update to the database entity if actingPrincipal has the {@code
   * SYSTEM_ADMIN} privilege: set every key in {@code updates}, remove every key in {@code
   * removals}, leave every other property untouched.
   *
   * @return the keys that were updated, removed, and requested for removal but missing
   * @throws com.linkedin.openhouse.common.exception.NoSuchEntityException if absent
   * @throws com.linkedin.openhouse.common.exception.UnprocessableEntityException if a key is in
   *     both sets
   */
  DatabasePropertiesUpdateDto updateDatabaseProperties(
      String databaseId,
      Map<String, String> updates,
      List<String> removals,
      String actingPrincipal);
}
