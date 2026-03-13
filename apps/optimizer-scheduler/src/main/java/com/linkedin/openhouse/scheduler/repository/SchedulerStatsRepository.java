package com.linkedin.openhouse.scheduler.repository;

import com.linkedin.openhouse.scheduler.entity.SchedulerStatsRow;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for reading {@code table_stats} rows from the optimizer DB. */
public interface SchedulerStatsRepository extends JpaRepository<SchedulerStatsRow, String> {}
