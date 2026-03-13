package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for reading and writing {@code table_stats} rows. */
public interface TableStatsRepository extends JpaRepository<TableStatsRow, String> {}
