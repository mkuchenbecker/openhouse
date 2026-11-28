# X1 and W6 result: format v3 tables in the tables service

Bottom line: done. Tables can now be created at format v3 or upgraded from v2 in the service, through both the native
API (Spark 3.5) and the Iceberg REST Catalog (Spark 4.2), behind one cluster switch that is off by default. Spark's
default writers work on a v3 table, including deletion vectors. Maintenance jobs, v3-only types, replication and the
defaults migration are not done. It is pull request 124 in mkuchenbecker/openhouse, merged into `irc-integration` as
merge commit `e2e02bfa0d1b674cb774f591355e19c911fee46b`. Nothing is merged outside the forks. Dated 2026-11-23. The
source record is `x1-format-v3.md` in the session's reports directory (outside the repository). The orchestrator's
decisions are in `56-decisions-continued-5.md` (D43 and its addendum).

## What a user writes and what happens

The switch is `cluster.iceberg.format-v3.enabled`, default false. It never makes a table v3 by itself, and
`cluster.iceberg.format-version` stays 2.

- Switch off: no write path of either API can leave a table above the cluster's format version. A test sweeps every
  path. A request asking for v3 is refused with 400.
- Switch on: a table becomes v3 only through `CREATE TABLE ... TBLPROPERTIES ('format-version'='3')` or
  `ALTER TABLE ... SET TBLPROPERTIES ('format-version'='3')`. The statements are the same on the native API (Spark
  3.5) and over IRC (Spark 4.2). A table is never lowered. A replace keeps the table's version.
- Turning the switch off again refuses new v3 creates and upgrades. Existing v3 tables keep working.
- Without a `format-version` property a table is v2, as before.
- On LinkedIn's 1.5.2 client the same statements fail in the client's own Iceberg before any request is sent.

## What works on a v3 table with Spark's default writers

Tested on Spark 3.5 and on Spark 4.2 unless stated:

- INSERT with row lineage on the snapshot, and `_row_id` readable.
- Row ids given to old rows by the first commit after an upgrade.
- DELETE, UPDATE and MERGE, in both copy-on-write and merge-on-read. In merge-on-read, deletion vectors are written
  and applied on read.
- INSERT OVERWRITE, ADD COLUMN and CTAS.
- RTAS: on Spark 3.5 fully, on Spark 4.2 at the HTTP level only.
- Branch writes and policy statements (SET POLICY, SET TAG): on Spark 3.5 only.
- Defaults set through the Iceberg API are stored as committed (W6). Reading old files with them is W7.

## What is refused, and the message

Each refusal names the later work item where there is one.

- A version other than 3 above the table's, or v3 with the switch off: 400 `FORMAT_VERSION_REFUSED`.
- A v3-only type (`timestamp_ns`, `timestamptz_ns`, `unknown`, geometry, geography): 400, naming X5. A `variant`
  column is refused by request validation (`Unsupported type: variant`) and that message does not name X5.
- A replica or replicated table at v3: 400, naming X7.
- Encryption keys, encrypted manifest lists and the encryption properties on a v3 table: 400.
- Register of a v3 metadata file: 400, as before.
- The column-defaults opt-in on v3: 400 `COLUMN_DEFAULTS_OPT_IN_ON_V3`, naming W8.
- A changed `initial-default` of an existing column on v3: 400 `COLUMN_INITIAL_DEFAULT_IMMUTABLE`.
- A new `write-default` below v3: 400 `COLUMN_DEFAULT_REQUIRES_V3`. A table that already carries one is not blocked.
- SQL column DEFAULT is refused by Iceberg's Spark integration before any request is sent (W9).

## What changed on the native API

Four answers change, as the source record lists them (the first two were measured before the change).

1. A create asking for a format version above the cluster's: was 201 at v2, now 400 `FORMAT_VERSION_REFUSED`.
2. A table PUT asking for a higher format version: 3 gave 200 and upgraded the table, 4 or 5 gave 500; now 400, except
   an upgrade to 3 with the cluster switch on.
3. Below v3, a commit that introduces or changes a column `write-default`: now 400 `COLUMN_DEFAULT_REQUIRES_V3`.
4. A replace of a v3 table keeps v3 instead of forcing the cluster's version.

Note, 2026-11-23: the list of four answers was corrected. The earlier list had one wrong item and has been replaced by
the list above.

The OpenHouse Java client now sends `format-version` only when it commits v3 metadata over an absent or lower base.
A v2 request is byte-identical to before. Before this work, on Spark 3.5 the `CREATE TABLE` statement silently made a
v2 table and the `ALTER TABLE` statement sent nothing and reported success.

## Evidence

- 46 OpenHouse rules run against both a v2 and a v3 table give the same answers.
- No existing golden file changed. Both smoke suites are unchanged. v2 tables are untouched.
- The compatibility kit is now 41 passed, 96 failed, 23 skipped (was 39, 98, 23), because its service runs with the
  switch on.
- Final head `1ec7f2408a9f6c5905fe56864a099a1046cf2261`: service suite 1636 tests on the default line, 0 failed, 1
  skipped; 1636 on the reference line (`-PicebergLinkedIn`), 0 failed, 29 skipped, the skips being tests that need a v3
  table. Spark 3.5 `catalogTest` 279, `statementTest` 137, `test` 137; Spark 4.2 `test` 48, `extensionsTest` 24,
  `smokeTest` 65; none failing. Spotless passes on both lines.
- Pull request CI was green on both jobs at the final head (run 45266134619).
- The work was started by one agent and finished by another after the usage limit. The second checked all eleven
  requirements against code and tests, added two tests for the one gap it found (a v2 table that already has a
  `write-default`), and reran every suite uncached.

## Not run

- The delta-harness on the final head (it ran earlier at stub level: 2556 of 2574 passed, identical case by case to the
  earlier record).
- Real S3 or ADLS, and the real House Tables service.
- RTAS on Spark 4.2 beyond the HTTP level; branch writes and policy statements on v3 on Spark 4.2.
- Any maintenance job on a v3 table.

## What remains

- X6, maintenance jobs on v3 tables, is next and carries the most risk: a job that mishandles deletion vectors or row
  ids loses data. None has run on a v3 table.
- X2 and X3 are partly proven by the list above. The wider scenarios remain, and row ids kept across compaction.
- X4 and W7: reading old files with native defaults through Spark.
- X5 (types) and X7 (replication) are refused today.
- W8, migrating tables that use the old defaults bridge, is untouched and is a one-way door per table.
- Turning the switch on for a real cluster and upgrading a real table are Mike's decisions (`58-waiting-on-mike.md`).
