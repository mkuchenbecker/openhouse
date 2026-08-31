-- DDL in this file is in alpha stage and would be subject to change.
CREATE TABLE IF NOT EXISTS user_table_row (
                         database_id         VARCHAR (128)     NOT NULL,
                         table_id            VARCHAR (128)     NOT NULL,
                         version             BIGINT            NOT NULL,
                         metadata_location   VARCHAR (512)     ,
                         storage_type        VARCHAR (128)     DEFAULT 'hdfs' NOT NULL,
                         creation_time       BIGINT            DEFAULT NULL,
                         last_modified_time  TIMESTAMP         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                         ETL_TS              DATETIME(6)       DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                         PRIMARY KEY (database_id, table_id)
);

-- FIXME: Index is not added at this point.
-- FIXME: Types of timestamp column to be discussed.
CREATE TABLE IF NOT EXISTS job_row (
    job_id                  VARCHAR (359)     NOT NULL,
    state                   VARCHAR (128)     NOT NULL,
    version                 BIGINT            ,
    job_name                VARCHAR (128)     NOT NULL,
    cluster_id              VARCHAR (128)      NOT NULL,
    creation_time_ms        BIGINT ,
    start_time_ms           BIGINT ,
    finish_time_ms          BIGINT ,
    last_update_time_ms     BIGINT ,
    job_conf                MEDIUMTEXT,
    heartbeat_time_ms       BIGINT ,
    execution_id            VARCHAR (128),
    engine_type             VARCHAR (128),
    ETL_TS                  datetime(6)      DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    retention_time_sec      BIGINT ,
    PRIMARY KEY (job_id)
    );

CREATE TABLE IF NOT EXISTS table_toggle_rule (
    feature                  VARCHAR (128)     NOT NULL,
    database_pattern         VARCHAR (128)     NOT NULL,
    table_pattern            VARCHAR (512)     NOT NULL,
    id                       BIGINT            AUTO_INCREMENT,
    creation_time_ms         BIGINT ,
    ETL_TS                   DATETIME(6)       DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE (feature, database_pattern, table_pattern)
    );

-- DDL in this file is in alpha stage and would be subject to change.
CREATE TABLE IF NOT EXISTS soft_deleted_user_table_row (
    database_id         VARCHAR (128)     NOT NULL,
    table_id            VARCHAR (128)     NOT NULL,
    deleted_at_ms       BIGINT            NOT NULL,
    version             BIGINT            NOT NULL,
    metadata_location   VARCHAR (512)     ,
    storage_type        VARCHAR (128)     DEFAULT 'hdfs' NOT NULL,
    creation_time       BIGINT            DEFAULT NULL,
    last_modified_time  TIMESTAMP         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ETL_TS              DATETIME(6)       DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    purge_after_ms      BIGINT          NOT NULL,
    PRIMARY KEY (database_id, table_id, deleted_at_ms)
);

-- Databases are the stored form of a namespace. The primary key is the encoded namespace, the same
-- bytes that appear in user_table_row.database_id for every database that exists today.
--
-- OBLIGATION: database_row.database_id must carry the SAME collation as user_table_row.database_id
-- in every environment. The two columns are two halves of one key space -- "does namespace n hold
-- tables" is asked by comparing them -- so if one folds case and the other does not, a namespace
-- can be loaded under a spelling that no listing contains and dropped by a name nobody created.
-- No collation is pinned here on purpose: user_table_row above does not pin one either, and pinning
-- only this one would be the fastest way to break the equality the obligation is about. When
-- user_table_row's collation is pinned, pin this one to the same value in the same change.
CREATE TABLE IF NOT EXISTS database_row (
    database_id         VARCHAR (128)     NOT NULL,
    version             BIGINT            ,
    properties          MEDIUMTEXT        ,
    creation_time       BIGINT            DEFAULT NULL,
    last_modified_time  BIGINT            DEFAULT NULL,
    ETL_TS              DATETIME(6)       DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (database_id)
);

-- Durable state of the backfill that gives every database in user_table_row a row in database_row.
-- Exactly one row lives here, under id = 'database_row_backfill'.
--
-- watermark is the resume point of a scan in flight, and is cleared by the scan that reaches the
-- end. scan_complete_time_ms says a scan RAN. verified_complete_time_ms says a pass read the store
-- back and found nothing missing -- that is the only column that may be read as "database_row is
-- complete", and a verification that finds a gap sets it back to NULL.
CREATE TABLE IF NOT EXISTS database_backfill_row (
    id                          VARCHAR (128)     NOT NULL,
    version                     BIGINT            ,
    watermark                   VARCHAR (128)     DEFAULT NULL,
    scan_complete_time_ms       BIGINT            DEFAULT NULL,
    verified_complete_time_ms   BIGINT            DEFAULT NULL,
    last_verify_time_ms         BIGINT            DEFAULT NULL,
    missing_count               BIGINT            DEFAULT NULL,
    ETL_TS                      DATETIME(6)       DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);
