package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.housetables.api.spec.model.Database;
import java.util.List;
import org.springframework.data.util.Pair;

/** Service Interface for implementing the /hts/databases endpoints. */
public interface DatabasesService {

  /**
   * @param databaseId the primary key, that is, the encoded namespace
   * @return the stored {@link Database}
   * @throws com.linkedin.openhouse.common.exception.NoSuchEntityException when no row exists
   */
  Database getDatabase(String databaseId);

  /** @return every stored {@link Database}, ordered by databaseId. */
  List<Database> getAllDatabases();

  /**
   * The direct children of {@code parentDatabaseId} — namespaces exactly one level deeper — ordered
   * by databaseId. Grandchildren are excluded, and the parent is not its own child.
   *
   * <p>Returns an empty list when the parent has no children, including when the parent itself does
   * not exist: existence is the Tables Service's question, and answering it here would make one
   * listing call return two different failures for the same state.
   */
  List<Database> getChildDatabases(String parentDatabaseId);

  /**
   * Create or replace a {@link Database} row, conditional on {@link Database#getVersion()}: a null
   * version asserts that no row exists yet, a non-null one asserts that the stored row is at that
   * version.
   *
   * @return a pair of the saved object and a boolean that is true when an existing row was
   *     overwritten, so the HTTP layer can render 200-vs-201 without decoding a boolean.
   * @throws com.linkedin.openhouse.common.exception.EntityConcurrentModificationException when the
   *     assertion does not hold, which the HTTP layer renders as 409
   */
  Pair<Database, Boolean> putDatabase(Database database);

  /**
   * Delete the row for {@code databaseId}, optionally conditional on {@code version}.
   *
   * @param version the version the delete is based on, or null for an unconditional delete
   * @throws com.linkedin.openhouse.common.exception.NoSuchEntityException when no row exists
   * @throws com.linkedin.openhouse.common.exception.EntityConcurrentModificationException when
   *     {@code version} is supplied and does not match the stored row
   */
  void deleteDatabase(String databaseId, Long version);
}
