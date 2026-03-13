-- Optimizer Service Schema
-- Compatible with MySQL (production) and H2 in MySQL mode (tests).
CREATE TABLE IF NOT EXISTS table_operations (
  id             VARCHAR(36)   NOT NULL,
  table_uuid     VARCHAR(36)   NOT NULL,
  database_name  VARCHAR(255)  NOT NULL,
  table_name     VARCHAR(255)  NOT NULL,
  operation_type VARCHAR(50)   NOT NULL,
  status         VARCHAR(20)   NOT NULL,
  created_at     TIMESTAMP(6)  NOT NULL,
  scheduled_at   TIMESTAMP(6),
  version        BIGINT,
  metrics        TEXT,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS table_stats (
  table_uuid       VARCHAR(36)   NOT NULL,
  database_id      VARCHAR(255)  NOT NULL,
  table_name       VARCHAR(255)  NOT NULL,
  stats            TEXT,
  table_properties TEXT,
  PRIMARY KEY (table_uuid)
);

CREATE TABLE IF NOT EXISTS table_operations_history (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  table_uuid     VARCHAR(36)   NOT NULL,
  database_name  VARCHAR(255)  NOT NULL,
  table_name     VARCHAR(255)  NOT NULL,
  operation_type VARCHAR(50)   NOT NULL,
  submitted_at   TIMESTAMP(6)  NOT NULL,
  status         VARCHAR(20)   NOT NULL,
  job_id         VARCHAR(255),
  result         TEXT,
  PRIMARY KEY (id)
);
