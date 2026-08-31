package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.housetables.model.DatabaseBackfillRow;
import com.linkedin.openhouse.housetables.repository.HtsRepository;

/**
 * JDBC-backed {@link HtsRepository} for the single {@link DatabaseBackfillRow}.
 *
 * <p>Configured in {@link com.linkedin.openhouse.housetables.config.db.DatabaseConfiguration} with
 * the other House Table repositories.
 */
public interface DatabaseBackfillHtsJdbcRepository
    extends HtsRepository<DatabaseBackfillRow, String> {}
