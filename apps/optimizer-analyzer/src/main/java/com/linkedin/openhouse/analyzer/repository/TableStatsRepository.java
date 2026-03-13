package com.linkedin.openhouse.analyzer.repository;

import com.linkedin.openhouse.analyzer.entity.TableStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for reading {@code table_stats} rows from the optimizer DB. */
public interface TableStatsRepository extends JpaRepository<TableStatsEntity, String> {}
