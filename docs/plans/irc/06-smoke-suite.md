# Spark SQL smoke suite (work item T1): proposed scenarios

Bottom line: 20 scenarios, each a few Spark SQL statements, define the Spark SQL behavior this work
protects. All of it is Claude's judgement. Nothing is built yet. Mike's review here is the cheapest
place to change what "behavior" means.

## How it runs

- Spark 3.5, against the real tables service started in-process by the existing test fixture
  (`OpenHouseSparkITest`).
- Every statement goes through `spark.sql`. Every assertion reads back through `spark.sql`, including
  Iceberg metadata tables. No catalog API calls and no peeking at storage.
- The catalog path is a parameter. Today it has one value, the legacy path. Later items add the
  REST-shaped path and the native REST path, and the same scenarios must give the same answers.
- Each scenario records its exact result (rows, or error class and the leading part of the message) on
  the legacy path first. That recording is the expected value.

## Scenarios

| # | Area | Statements | Asserts |
| --- | --- | --- | --- |
| 1 | Create | `CREATE TABLE` partitioned by day and clustered by a string | `DESCRIBE`, `SHOW TABLES`, `SHOW TBLPROPERTIES` |
| 2 | Insert | `INSERT INTO` twice | Row count, snapshot count |
| 3 | Overwrite | `INSERT OVERWRITE` one partition | Other partitions untouched |
| 4 | CTAS | `CREATE TABLE AS SELECT` | Schema and rows |
| 5 | Replace | `CREATE OR REPLACE TABLE AS SELECT`, with and without `replace.enabled` | Allowed case replaces; gated case fails with today's error |
| 6 | Schema | `ALTER TABLE ADD COLUMN` | Old rows read null, new rows read value |
| 7 | Properties | `SET TBLPROPERTIES`, `UNSET`, and setting a reserved `openhouse.` key | User key round-trips; reserved key rejected |
| 8 | Delete | `DELETE FROM` with a predicate | Rows gone, snapshot added |
| 9 | Update | `UPDATE` with a predicate | Rows changed |
| 10 | Merge | `MERGE INTO` with matched and not-matched clauses, merge-on-read | Row set, delete files present |
| 11 | Time travel | `SELECT ... VERSION AS OF` an earlier snapshot | Earlier row set |
| 12 | Branch | `ALTER TABLE CREATE BRANCH`, insert into branch | Main unchanged, branch has rows |
| 13 | Rename | `ALTER TABLE RENAME TO` | Old name gone, new name has rows |
| 14 | Drop | `DROP TABLE`, then select | Select fails; table not listed |
| 15 | Partition evolution | `ALTER TABLE ADD PARTITION FIELD` | Rejected with today's error |
| 16 | Policy | `ALTER TABLE SET POLICY (RETENTION=30d)` | Policy visible in table properties |
| 17 | Grants | `GRANT SELECT ON TABLE`, `SHOW GRANTS`, `REVOKE` | Grant listed, then not |
| 18 | Views | `CREATE VIEW`, select, `DROP VIEW` | Rows through the view; view gone |
| 19 | Namespaces | `SHOW DATABASES`, `CREATE NAMESPACE` | Listing works; create fails with today's error |
| 20 | Missing table | `SELECT` from a table that does not exist | Today's error class |

## Deliberately left out

- Concurrency, large data, maintenance jobs, replication, and Spark 3.1. The delta-harness (T2) and the
  existing suites cover depth. This suite is for fast, readable detection of a behavior change.
- Anything specific to v3. Those scenarios arrive with the v3 items.
