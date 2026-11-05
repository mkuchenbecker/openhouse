-- Production DDL for the database entity introduced for database-level properties.
-- Applied out of band before the HouseTables deploy that serves /hts/databases; the HouseTables
-- Service never issues DDL (spring.jpa.hibernate.ddl-auto=none). schema.sql carries the portable
-- bootstrap equivalent used by H2 and the docker MySQL recipes.
--
-- database_uuid is the canonical identity (primary key). database_id is the logical name; the
-- unique functional index on upper(database_id) mirrors the index on user_table_row so that name
-- uniqueness is case-insensitive regardless of column collation. No existing table is altered.
CREATE TABLE IF NOT EXISTS database_row (
    database_uuid       VARCHAR(36)   NOT NULL,
    database_id         VARCHAR(128)  NOT NULL,
    version             BIGINT        NOT NULL,
    creation_time       BIGINT        DEFAULT NULL,
    properties          MEDIUMTEXT,
    ETL_TS              DATETIME(6)   DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (database_uuid),
    UNIQUE INDEX idx_database_row_upper_db ((upper(database_id)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
