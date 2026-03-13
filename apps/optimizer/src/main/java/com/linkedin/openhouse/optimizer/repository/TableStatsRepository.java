package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@code table_stats} rows in the optimizer DB. */
public interface TableStatsRepository extends JpaRepository<TableStatsRow, String> {}
