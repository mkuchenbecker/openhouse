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
   * Create or replace a {@link Database} row.
   *
   * @return a pair of the saved object and a boolean that is true when an existing row was
   *     overwritten, so the HTTP layer can render 200-vs-201 without decoding a boolean.
   */
  Pair<Database, Boolean> putDatabase(Database database);

  /**
   * Delete the row for {@code databaseId}.
   *
   * @throws com.linkedin.openhouse.common.exception.NoSuchEntityException when no row exists
   */
  void deleteDatabase(String databaseId);
}
