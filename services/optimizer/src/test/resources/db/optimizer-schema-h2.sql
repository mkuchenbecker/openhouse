-- Optimizer Service Schema (H2 — test only)
-- Note: table_stats lives in the housetables service.
CREATE TABLE
  IF NOT EXISTS table_operations (
    id VARCHAR(36) NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    scheduled_at TIMESTAMP(6),
    version BIGINT,
    metrics VARCHAR(4096),
    PRIMARY KEY (id),
    UNIQUE (database_name, table_name, operation_type)
  );

CREATE TABLE
  IF NOT EXISTS table_operations_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    database_name VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    submitted_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    job_id VARCHAR(255),
    result VARCHAR(4096),
    PRIMARY KEY (id)
  );
