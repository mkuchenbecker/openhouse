-- Optimizer Service Schema
-- Compatible with MySQL (production) and H2 in MySQL mode (tests).
--
-- SCOPE COLUMNS (M6, directory-deletion): table_uuid and table_name are NULLABLE, and
-- operation_scope / directory_path were added, so directory/database-scoped operations
-- (ORPHAN_DIRECTORY_DELETION, TABLE_DIRECTORY_DELETION) can be persisted without a live table.
-- The change is ADDITIVE and NON-BREAKING: per-table operations always set operation_scope='TABLE'
-- with a non-null table_uuid/table_name, exactly as before. Only directory ops use the new
-- nullable columns (operation_scope='DATABASE', null table_uuid/table_name). See
-- services/optimizer/DIRECTORY-DELETION-DESIGN.md.
--
-- Fresh environments (and the H2 test DB) get the columns from the CREATE TABLE below. Existing
-- production databases apply this one-time migration:
--   ALTER TABLE table_operations MODIFY table_uuid VARCHAR(36) NULL;
--   ALTER TABLE table_operations MODIFY table_name VARCHAR(128) NULL;
--   ALTER TABLE table_operations ADD COLUMN operation_scope VARCHAR(20) NULL;
--   ALTER TABLE table_operations ADD COLUMN directory_path VARCHAR(1024) NULL;
--   UPDATE table_operations SET operation_scope = 'TABLE' WHERE operation_scope IS NULL;
--   -- (and the identical five statements for table_operations_history)
CREATE TABLE IF NOT EXISTS table_operations (
  id              VARCHAR(36)   NOT NULL,
  table_uuid      VARCHAR(36),
  database_name   VARCHAR(128)  NOT NULL,
  table_name      VARCHAR(128),
  operation_type  VARCHAR(50)   NOT NULL,
  operation_scope VARCHAR(20),
  directory_path  VARCHAR(1024),
  status          VARCHAR(20)   NOT NULL,
  created_at      TIMESTAMP(6)  NOT NULL,
  scheduled_at    TIMESTAMP(6),
  job_id          VARCHAR(255),
  -- TODO: per-operation metric columns will be added as operations are onboarded.
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS table_stats (
  table_uuid       VARCHAR(36)   NOT NULL,
  database_name    VARCHAR(128)  NOT NULL,
  table_name       VARCHAR(128)  NOT NULL,
  snapshot         TEXT,
  table_properties TEXT,
  updated_at       TIMESTAMP(6)  NOT NULL,
  PRIMARY KEY (table_uuid)
);

CREATE TABLE IF NOT EXISTS table_stats_history (
  id             VARCHAR(36)   NOT NULL,
  table_uuid     VARCHAR(36)   NOT NULL,
  database_name  VARCHAR(128)  NOT NULL,
  table_name     VARCHAR(128)  NOT NULL,
  snapshot       TEXT,
  delta          TEXT,
  recorded_at    TIMESTAMP(6)  NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_tsh_table_uuid (table_uuid),
  INDEX idx_tsh_recorded_at (recorded_at)
);

CREATE TABLE IF NOT EXISTS table_operations_history (
  id              VARCHAR(36)   NOT NULL,
  table_uuid      VARCHAR(36),
  database_name   VARCHAR(128)  NOT NULL,
  table_name      VARCHAR(128),
  operation_type  VARCHAR(50)   NOT NULL,
  operation_scope VARCHAR(20),
  directory_path  VARCHAR(1024),
  completed_at    TIMESTAMP(6)  NOT NULL,
  status          VARCHAR(20)   NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_toph_db_table (database_name, table_name),
  -- Drives DirectoryDeletionAnalyzerRunner cadence: latest DATABASE-scoped history per database.
  INDEX idx_toph_optype_scope_db_completed (operation_type, operation_scope, database_name, completed_at),
  -- Drives TableOperationHistoryRepository.findLatestPerTable: the correlated
  -- MAX(completed_at) subquery becomes an index-only lookup per (operation_type,
  -- table_uuid) instead of an O(N²) scan.
  INDEX idx_toph_optype_uuid_completed (operation_type, table_uuid, completed_at)
);
