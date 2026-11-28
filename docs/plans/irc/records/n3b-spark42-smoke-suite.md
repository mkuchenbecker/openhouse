# N3b: the Spark SQL smoke suite on Spark 4.2 + Iceberg 1.12 + IRC only (record)

Branch `irc/n3b-spark42-smoke-suite`, draft pull request 117 into `irc-integration`
(https://github.com/mkuchenbecker/openhouse/pull/117), head
`d272ec0c98c40bbff1889033a15e3532eac9e87c`, which contains merge commit 0a3bf529 and
`irc-integration` at 5ea51d35 (pull request 115). Every fact is marked [ran] (run by me on JDK 17) or
[read] (taken from code or from earlier records).

## Bottom line

- The smoke suite has 31 scenarios and 177 statements (a statement is one `spark.sql` call of a
  scenario; the `DROP TABLE IF EXISTS` setup before a scenario is not counted). All 177 run on Spark
  4.2.0 with the fork's `iceberg-spark-runtime-4.2_2.13` 1.12.0-ohfork.a88f556c5067, through
  Iceberg's own `SparkCatalog` and `RESTCatalog`, with the OpenHouse SQL extensions and no OpenHouse
  catalog or client class in the Spark JVM. [ran]
- 161 statements give the baseline outcome. 16 do not: 5 of class (a), 2 of class (b), 0 of class
  (c), 9 of class (d) (6 independent and 3 that follow from the first), 0 of class (e). [ran]
- 23 of the 31 scenarios are the baseline in every statement. The 8 others: s13b, s19, s22, s24,
  s25, s26, s27, s29. [ran]
- "Same" means the baseline's own assertion holds, as `SparkSqlSmokeTest` states it: rows exactly,
  a failure by exception class and a fragment of its message. Full message texts of failures were not
  compared with a Spark 3.5 run. Known from earlier records [read]: Spark 4 appends a SQLSTATE and a
  search path to its own messages, and a native 400 over IRC reads "Malformed request: <native
  message>".
- One statement that worked on the baseline fails here: a `RENAME TO` whose target names the catalog
  (s13b). Two statements that failed on the baseline answer here: `DESCRIBE NAMESPACE <catalog>.<db>`
  and `SHOW NAMESPACES IN <catalog>.<db>`. Every other difference is a failure that stays a failure
  with another class or message, a different row of `DESCRIBE TABLE EXTENDED`, or a different
  snapshot operation.

## The 16 differing statements

| # | Statement | Baseline (Spark 3.5, OpenHouse catalog, Iceberg 1.5.2) | Spark 4.2, Iceberg 1.12, IRC [ran] | Class | Evidence |
| --- | --- | --- | --- | --- | --- |
| s13b #3 | `ALTER TABLE openhouse.smoke_t1.s13b_rename RENAME TO openhouse.smoke_t1.s13b_renamed` | succeeds | `BadRequestException`: Malformed request: OpenHouse namespaces are databases and have exactly one level: openhouse.smoke_t1 | (d) | [read] `OpenHouseCatalog.renameTable` takes a first namespace level equal to its own name off (`CatalogAndDbNameFromNamespace`). [ran] `RENAME TO probe.n2` and `RENAME TO n4` work over IRC (scratch probe). |
| s13b #4 | `SELECT * FROM ...s13b_rename` | `TABLE_OR_VIEW_NOT_FOUND` | rows [1] | (d), follows from #3 | |
| s13b #5 | `SELECT * FROM ...s13b_renamed` | rows [1] | `TABLE_OR_VIEW_NOT_FOUND` | (d), follows from #3 | |
| s13b #6 | `SHOW TABLES IN openhouse.smoke_t1 LIKE 's13b_*'` | rows [smoke_t1,s13b_renamed,false] | rows [smoke_t1,s13b_rename,false] | (d), follows from #3 | |
| s19 #3 | `CREATE NAMESPACE openhouse.smoke_t1_new_ns` | `UnsupportedOperationException`: Describing database is not supported | `UnsupportedOperationException`: Server does not support endpoint: POST /v1/{prefix}/namespaces | (a) D20 | [read] D20: namespace create, update and drop stay unsupported. The wording is the 1.12 client's refusal of a route the config does not list (pinned since V10a in `SparkIrcEndpointsTest`). No decision names the wording. |
| s22 #11 | `SELECT operation FROM ...s22_rtas.snapshots ORDER BY committed_at` (after CREATE OR REPLACE TABLE AS SELECT) | rows [append, append] | rows [append, overwrite] | (b) | [ran] the same on Iceberg's Hadoop catalog (`Spark42WithoutOpenHouseTest`). [ran] javap: Spark 3.5.2 `V2CreateTableAsSelectBaseExec.writeToTable` references only `AppendData`; Spark 4.2.0's also `OverwriteByExpression`. [read] the Spark 3.5 suite expects [append, append] on Iceberg 1.12 too. Rows, schema and properties after the replace are the baseline's. |
| s24 #2 | `DESCRIBE NAMESPACE openhouse.smoke_t1` | `UnsupportedOperationException`: Describing database is not supported | rows [Catalog Name,openhouse], [Namespace Name,smoke_t1] | (d) | [read] V2 record, difference 6: namespace calls answer on `RESTCatalog`. [ran] Iceberg's Hadoop catalog answers as well. |
| s24 #3 | `ALTER NAMESPACE openhouse.smoke_t1 SET DBPROPERTIES ('k'='v')` | `UnsupportedOperationException`: Set properties on a database is not supported | `UnsupportedOperationException`: Server does not support endpoint: POST /v1/{prefix}/namespaces/{namespace}/properties | (a) D20 | as s19 #3 |
| s24 #4 | `DROP NAMESPACE openhouse.smoke_t1` | `UnsupportedOperationException`: Describing database is not supported | `UnsupportedOperationException`: Server does not support endpoint: DELETE /v1/{prefix}/namespaces/{namespace} | (a) D20 | as s19 #3 |
| s24 #5 | `SHOW NAMESPACES IN openhouse.smoke_t1` | `UnsupportedOperationException`: Openhouse supports 2-lvl namespace <schema>.<table> | no rows | (d) | [read] N3a record, difference 5, pinned in `SparkIrcDifferencesTest`. [ran] Iceberg's Hadoop catalog answers no rows as well. |
| s25 #1 | `SHOW TABLES IN openhouse` | `ValidationException`: DatabaseId was not provided, for SQL please run "SHOW TABLES IN <databaseId>" instead | `org.apache.iceberg.exceptions.NoSuchNamespaceException`: Invalid namespace: | (d) | [read] `RESTSessionCatalog.checkNamespaceIsValid` refuses an empty namespace in the engine (fork source). |
| s25 #3 | `SHOW TABLES` after `USE openhouse` | as s25 #1 | as s25 #1 | (d) | as s25 #1 |
| s26 #2 | `DESCRIBE TABLE EXTENDED openhouse.smoke_t1.s26_describe` | has rows `Name,openhouse.smoke_t1.s26_describe,`, `Type,MANAGED,`, `Provider,iceberg,`, `Location,<root>/smoke_t1/s26_describe-...` | no `Name` row; rows `Catalog,openhouse,`, `Namespace,smoke_t1,`, `Table,s26_describe,`; Type, Provider and Location as the baseline | (b) | [ran] the same on Iceberg's Hadoop catalog (`Spark42WithoutOpenHouseTest`). [read] N3a record, difference 1. |
| s27 #3 | `ALTER TABLE openhouse.smoke_t1.s27_missing UNLOCK` | `com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException`: ...User table smoke_t1.s27_missing cannot be found | `org.apache.iceberg.exceptions.NoSuchTableException`: User table smoke_t1.s27_missing cannot be found | (a) goal, D23 | The baseline class is the Java client's, which the target platform excludes by definition. [read] C2-3 record, difference 1. No numbered decision names the Iceberg type. |
| s29 #2 | `ALTER TABLE openhouse.smoke_t1.s29_rename RENAME TO smoke_t1.s29_rename` | `WebClientResponseWithMessageException`: ...Cannot rename a table to the same current db name and table name: s29_rename | `BadRequestException`: Malformed request: Cannot rename a table to the same current db name and table name: s29_rename | (a) goal, D16 | As s27 #3. [read] V3 record, error mapping: a native 400 is `BadRequestException` with the native message. |
| s29 #3 | `ALTER TABLE openhouse.smoke_t1.s29_rename RENAME TO other_catalog.smoke_t1.s29_renamed` | `UnsupportedOperationException`: Cannot rename tables across catalogs: from=openhouse.smoke_t1.s29_rename, to=other_catalog.smoke_t1.s29_renamed | `BadRequestException`: Malformed request: OpenHouse namespaces are databases and have exactly one level: other_catalog.smoke_t1 | (d) | Same cause as s13b #3: the check was in `OpenHouseCatalog.renameTable`. |

Classes: (a) accepted by a recorded decision; (b) Spark 4 versus 3.5, nothing to do with OpenHouse;
(c) Iceberg 1.12 versus 1.5; (d) IRC path, not decided; (e) defect.

How I placed the (a) rows, so that they can be moved: s19 #3, s24 #3 and s24 #4 stay refused with the
same exception class, which D20 decides, and only the wording is new. s27 #3 and s29 #2 raise an
Iceberg type where the baseline raises a class of the OpenHouse Java client; no numbered decision
accepts the type, but the goal (no OpenHouse client class in the engine) leaves no alternative. A
stricter reading puts all five in (d).

## What a decision is needed for (class d)

1. A `RENAME TO` target that names the catalog (s13b, and the message of s29 #3). Options: an
   analyzer rule in the SQL extensions that takes the catalog's own name off the target and refuses
   another catalog's name with the baseline message (keeps Spark SQL behavior; it would be wanted in
   the Spark 3.5 runtime too for `type=rest`); or accepting the difference. The service cannot fix it:
   it does not know what a client calls its catalog.
2. `DESCRIBE NAMESPACE <catalog>.<db>` answers, and `SHOW NAMESPACES IN <catalog>.<db>` answers an
   empty list, where the baseline refuses both.
3. `SHOW TABLES IN <catalog>` and `SHOW TABLES` with no current database stay refused, with Iceberg's
   class and message.

## Defects

- Fixed: none. Left: none of class (e) was found. No main code was changed.
- Noticed, not investigated: the service stores a property `owner=root` for tables Spark 4.2 creates
  over IRC [ran, scratch probe]. `SHOW TBLPROPERTIES` hides it on both Spark lines, so the smoke
  suite cannot show whether the baseline stores it too. Not compared.

## The extra write statements [ran]

`SparkIrcWriteStatementsTest`, 18 tests, all pass. Each asserts what the service stored, read through
the OpenHouse Java client in the service JVM (columns, properties, snapshot operations and summaries,
refs, and the rows a reader of each ref gets with deletes applied).

| Statement | Stored state asserted |
| --- | --- |
| INSERT INTO (VALUES, then SELECT) | 3 rows; operations append, append; record counts |
| INSERT OVERWRITE ... PARTITION (p='x') | the other partition untouched; append, overwrite |
| INSERT OVERWRITE without a partition, static mode | every row replaced; append, overwrite |
| INSERT OVERWRITE, `partitionOverwriteMode=dynamic` | only the written partitions replaced; summary `replace-partitions=true` |
| MERGE INTO, default (copy-on-write) | rows; append, overwrite; 0 delete files |
| MERGE INTO, merge-on-read properties | rows with the delete file applied; 1 delete file, 1 position delete |
| UPDATE ... WHERE | rows; append, overwrite |
| DELETE ... WHERE | rows; append, overwrite |
| CREATE TABLE AS SELECT | columns, rows, one append, `openhouse.tableVersion=INITIAL_VERSION` |
| CREATE OR REPLACE TABLE AS SELECT, `replace.enabled=true` | new columns and rows; `team`, `extra`, `replace.enabled` kept; append, overwrite; the replaced snapshot kept outside main's history |
| CREATE OR REPLACE TABLE AS SELECT, replace not enabled | refused (`BadRequestException`, native message); columns, rows and snapshots unchanged |
| ALTER TABLE ADD COLUMN | columns; old row reads null |
| ALTER TABLE RENAME COLUMN | refused by the native schema rule ("invalid schema evolution"); nothing changed |
| ALTER TABLE DROP COLUMN | refused the same way; nothing changed |
| SET and UNSET TBLPROPERTIES, and an `openhouse.` key | set, unset, and the reserved key refused and not stored |
| ALTER TABLE CREATE BRANCH, INSERT INTO t.branch_b1 | refs b1 and main; main unchanged; branch has both rows |
| INSERT with `spark.wap.id`, then `CALL openhouse.system.cherrypick_snapshot` | staged snapshot with `wap.id`, not in main; after the cherry-pick main has its rows |
| INSERT with `spark.wap.branch` | refs main and staging; main unchanged; staging has both rows |

Not established for these 18: the Spark 3.5 baseline. They assert Spark 4.2 over IRC only. Known
from earlier records [read]: rename and drop of a column are refused natively on every path; the
replace snapshot is an append on Spark 3.5 (smoke s22).

## How the suite is shared: it is a copy, guarded

- The statements and baseline outcomes are in
  `integrations/spark/spark-4.2/openhouse-spark-itest/src/shared/test/java/com/linkedin/openhouse/spark/smoke/SparkSqlSmokeScenarios.java`.
  It needs only `spark.sql` and JUnit, and compiles against Spark 3.5.2 / Scala 2.12 with
  `--release 8` [ran: javac outside Gradle]. It is a copy of the scenarios of the Spark 3.5 itest's
  `SparkSqlSmokeTest`, because the brief said not to edit that project.
- `SmokeScenariosCopyTest` reads both source files and fails when the scenario names differ or a
  scenario's string literals differ, apart from five listed deviations: the snapshot id is named in
  s11; s19 and s24 hold only the Iceberg 1.5.2 message (the 3.5 suite also holds the 1.12 message,
  D14b); s21 reads properties in the scenario, not through a helper; s26 takes the storage root from
  the platform. [ran, and ran with a changed literal: it failed]
- Follow-up that leaves one list (not done): the Spark 3.5 itest names this directory as a source
  directory, `SparkSqlSmokeTest` extends `SparkSqlSmokeScenarios` and keeps only its sessions and
  views server, the D14b message becomes a declared difference of the Iceberg 1.12 line, and
  `SmokeScenariosCopyTest` is deleted. It needs the Spark 3.5 `catalogTest` on both builds. Pull
  request 115 (T10) merged while this branch was open, so the project is free again.

## How a difference is kept from hiding

`SparkSqlSmokeScenarios` fails a scenario when: a statement differs from the baseline without a
declared difference; a declared difference's outcome is the baseline's; a declared difference quotes
another baseline than the scenario states; a declared difference is for a statement that was not run.
Each was provoked by hand and failed with the expected message [ran, not committed]. Every statement
of a scenario runs even after an unexpected outcome. The run writes
`build/openhouse-spark-4.2-itest/reports/smoke/spark-4.2-irc-outcomes.tsv`; the full table below is
that file.

## Counts [ran], on head d272ec0c, JDK 17, `-PicebergFork`

| Task | Result |
| --- | --- |
| `:integrations:spark:spark-4.2:openhouse-spark-4.2-itest:smokeTest` (new) | 58 tests, 0 failed, 0 skipped (smoke suite 31, write statements 18, without OpenHouse 4, classpath 3, copy check 2) |
| `...:test` | 40 tests, 0 failed, 0 skipped |
| `...:extensionsTest` | 24 tests, 0 failed, 0 skipped |
| `...:spotlessCheck` under the switch; `spotlessCheck` on the default build | pass |
| `...:check` (runs the three tasks again; `spotbugsSmoke` skipped as `spotbugsTest` is) | pass; 40, 24 and 58 again |
| `scripts/iceberg-fork/publish-local.sh` | no-op ("already published") |
| CI on pull request 117, head d272ec0c, run 45157171783 [ran in CI, read from the job log] | `iceberg-fork-build / Opt-in build against the Iceberg fork`: success, including `-PicebergFork assemble testClasses` and `smokeTest` with 58 tests PASSED on the runner. `build-run-tests / Build and Run Tests` (default build): success. |

The smoke task was green in four full local runs and once in CI.

## Not run

Locally: the full build; `-PicebergFork assemble testClasses`; the Spark 3.5 itest on either build;
the Spark 3.1 itest; `:services:tables:test` and `:tables-test-fixtures:tables-test-fixtures_2.12:test`
(no service code changed); the Spark 4.2 runtime module's tests (not touched). CI on the pull request
ran the default build and the opt-in job. A Spark 3.5 run to compare full failure messages or the 18
write statements was not made.

## Deviations from the brief

1. A new Gradle task `smokeTest` with its own source set, where the brief names `test` and
   `extensionsTest`. Reason: the existing `SparkJvmClasspathTest` asserts that every OpenHouse class
   in a class directory of its JVM is under `com/linkedin/openhouse/spark42/`, and the shared
   scenarios are in a neutral package. A separate source set leaves that test untouched and true;
   `SmokeJvmClasspathTest` makes the same assertions for the smoke JVM. The opt-in CI job gained one
   line for the task (`.github/workflows/iceberg-fork-build.yml`).
2. A third test service main (`IrcSmokeTestService`), so that the smoke statements meet the service
   the baseline was recorded against (the service's own authorization handler, which drops grants).
3. The suite is copied, not shared (above).
4. Class (a) is used for five statements where no numbered decision names the exact outcome (above).

## Commits

- c8bae814 test: a Spark 4.2 test service that is the Spark 3.5 smoke suite's fixture
- 21f2e25e test: the Spark SQL smoke suite runs on Spark 4.2 over IRC, with every difference listed
- f615be44 test: the copied smoke scenarios cannot drift from the Spark 3.5 suite unnoticed
- f4e78d1a test: what Spark 4.2 does without OpenHouse for the smoke statements that differ
- 8980f62d test: the Spark SQL write statements from Spark 4.2 over IRC store what they should
- d272ec0c Merge origin/irc-integration (pull request 115) into N3b

## Full per-statement table [ran]

From the outcomes file of the final run. Long cells are cut with "…" and the warehouse directory is
shown as `<warehouse>`. The baseline column is the assertion the suite makes; "succeeds" means the
suite only requires the statement not to fail.

| Scenario | # | Statement | Baseline (Spark 3.5, OpenHouse catalog) | Spark 4.2, Iceberg 1.12, IRC | Verdict |
| --- | --- | --- | --- | --- | --- |
| s01 | 1 | `CREATE TABLE openhouse.smoke_t1.s01_create (id bigint, name string, ts timestamp) PARTITIONED BY (days(ts), name)` | succeeds | rows [] | same |
| s01 | 2 | `DESCRIBE openhouse.smoke_t1.s01_create` | rows [id,bigint,null, name,string,null, ts,timestamp,null, ,,, # Partitioning,,, Part 0,days(ts),, Part 1,name,] | rows [id,bigint,null, name,string,null, ts,timestamp,null, ,,, # Partitioning,,, Part 0,days(ts),, Part 1,name,] | same |
| s01 | 3 | `SHOW TABLES IN openhouse.smoke_t1 LIKE 's01_create'` | rows [smoke_t1,s01_create,false] | rows [smoke_t1,s01_create,false] | same |
| s01 | 4 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s01_create ('openhouse.tableId')` | rows [openhouse.tableId,s01_create] | rows [openhouse.tableId,s01_create] | same |
| s01 | 5 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s01_create ('openhouse.databaseId')` | rows [openhouse.databaseId,smoke_t1] | rows [openhouse.databaseId,smoke_t1] | same |
| s01 | 6 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s01_create ('openhouse.tableType')` | rows [openhouse.tableType,PRIMARY_TABLE] | rows [openhouse.tableType,PRIMARY_TABLE] | same |
| s01 | 7 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s01_create ('format-version')` | rows [format-version,2] | rows [format-version,2] | same |
| s01 | 8 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s01_create ('write.format.default')` | rows [write.format.default,orc] | rows [write.format.default,orc] | same |
| s01 | 9 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s01_create ('policies')` | rows [policies,] | rows [policies,] | same |
| s02 | 1 | `CREATE TABLE openhouse.smoke_t1.s02_insert (id bigint, name string)` | succeeds | rows [] | same |
| s02 | 2 | `INSERT INTO openhouse.smoke_t1.s02_insert VALUES (1,'a'),(2,'b')` | succeeds | rows [] | same |
| s02 | 3 | `INSERT INTO openhouse.smoke_t1.s02_insert VALUES (3,'c')` | succeeds | rows [] | same |
| s02 | 4 | `SELECT * FROM openhouse.smoke_t1.s02_insert ORDER BY id` | rows [1,a, 2,b, 3,c] | rows [1,a, 2,b, 3,c] | same |
| s02 | 5 | `SELECT count(*) FROM openhouse.smoke_t1.s02_insert` | rows [3] | rows [3] | same |
| s02 | 6 | `SELECT count(*) FROM openhouse.smoke_t1.s02_insert.snapshots` | rows [2] | rows [2] | same |
| s02 | 7 | `SELECT operation FROM openhouse.smoke_t1.s02_insert.snapshots ORDER BY operation` | rows [append, append] | rows [append, append] | same |
| s03 | 1 | `CREATE TABLE openhouse.smoke_t1.s03_overwrite (id bigint, p string) PARTITIONED BY (p)` | succeeds | rows [] | same |
| s03 | 2 | `INSERT INTO openhouse.smoke_t1.s03_overwrite VALUES (1,'x'),(2,'y')` | succeeds | rows [] | same |
| s03 | 3 | `INSERT OVERWRITE openhouse.smoke_t1.s03_overwrite PARTITION (p='x') SELECT 10` | succeeds | rows [] | same |
| s03 | 4 | `SELECT * FROM openhouse.smoke_t1.s03_overwrite ORDER BY id` | rows [2,y, 10,x] | rows [2,y, 10,x] | same |
| s03 | 5 | `SELECT operation FROM openhouse.smoke_t1.s03_overwrite.snapshots ORDER BY operation` | rows [append, overwrite] | rows [append, overwrite] | same |
| s04 | 1 | `CREATE TABLE openhouse.smoke_t1.s04_ctas_src (id bigint, name string)` | succeeds | rows [] | same |
| s04 | 2 | `INSERT INTO openhouse.smoke_t1.s04_ctas_src VALUES (1,'a'),(2,'b')` | succeeds | rows [] | same |
| s04 | 3 | `CREATE TABLE openhouse.smoke_t1.s04_ctas AS SELECT * FROM openhouse.smoke_t1.s04_ctas_src` | succeeds | rows [] | same |
| s04 | 4 | `DESCRIBE openhouse.smoke_t1.s04_ctas` | rows [id,bigint,null, name,string,null] | rows [id,bigint,null, name,string,null] | same |
| s04 | 5 | `SELECT * FROM openhouse.smoke_t1.s04_ctas ORDER BY id` | rows [1,a, 2,b] | rows [1,a, 2,b] | same |
| s05 | 1 | `CREATE TABLE openhouse.smoke_t1.s05_replace_on (id bigint, name string)` | succeeds | rows [] | same |
| s05 | 2 | `INSERT INTO openhouse.smoke_t1.s05_replace_on VALUES (1,'a')` | succeeds | rows [] | same |
| s05 | 3 | `ALTER TABLE openhouse.smoke_t1.s05_replace_on SET TBLPROPERTIES ('replace.enabled'='true')` | succeeds | rows [] | same |
| s05 | 4 | `CREATE OR REPLACE TABLE openhouse.smoke_t1.s05_replace_on AS SELECT 7 AS id, 'z' AS name` | succeeds | rows [] | same |
| s05 | 5 | `SELECT * FROM openhouse.smoke_t1.s05_replace_on` | rows [7,z] | rows [7,z] | same |
| s05 | 6 | `CREATE TABLE openhouse.smoke_t1.s05_replace_off (id bigint, name string)` | succeeds | rows [] | same |
| s05 | 7 | `INSERT INTO openhouse.smoke_t1.s05_replace_off VALUES (1,'a')` | succeeds | rows [] | same |
| s05 | 8 | `CREATE OR REPLACE TABLE openhouse.smoke_t1.s05_replace_off AS SELECT 7 AS id, 'z' AS name` | fails with org.apache.iceberg.exceptions.BadRequestException: ...REPLACE TABLE AS SELECT is not enabled for table openhouse.smoke_t1.s05_replace_off... | fails with org.apache.iceberg.exceptions.BadRequestException: Malformed request: REPLACE TABLE AS SELECT is not enabled for table openhouse.smoke_t1.s05_replace_off. You… | same |
| s05 | 9 | `SELECT * FROM openhouse.smoke_t1.s05_replace_off` | rows [1,a] | rows [1,a] | same |
| s06 | 1 | `CREATE TABLE openhouse.smoke_t1.s06_schema (id bigint)` | succeeds | rows [] | same |
| s06 | 2 | `INSERT INTO openhouse.smoke_t1.s06_schema VALUES (1)` | succeeds | rows [] | same |
| s06 | 3 | `ALTER TABLE openhouse.smoke_t1.s06_schema ADD COLUMN extra string` | succeeds | rows [] | same |
| s06 | 4 | `INSERT INTO openhouse.smoke_t1.s06_schema VALUES (2,'v')` | succeeds | rows [] | same |
| s06 | 5 | `SELECT * FROM openhouse.smoke_t1.s06_schema ORDER BY id` | rows [1,null, 2,v] | rows [1,null, 2,v] | same |
| s06 | 6 | `DESCRIBE openhouse.smoke_t1.s06_schema` | rows [id,bigint,null, extra,string,null] | rows [id,bigint,null, extra,string,null] | same |
| s07 | 1 | `CREATE TABLE openhouse.smoke_t1.s07_props (id bigint)` | succeeds | rows [] | same |
| s07 | 2 | `ALTER TABLE openhouse.smoke_t1.s07_props SET TBLPROPERTIES ('team'='x')` | succeeds | rows [] | same |
| s07 | 3 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s07_props ('team')` | rows [team,x] | rows [team,x] | same |
| s07 | 4 | `ALTER TABLE openhouse.smoke_t1.s07_props UNSET TBLPROPERTIES ('team')` | succeeds | rows [] | same |
| s07 | 5 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s07_props` | has no row starting with [team,] | rows [current-snapshot-id,none, format,iceberg/orc, format-version,2, history.expire.max-ref-age-ms,604800000, openhouse.clusterId,local-cluster, openhouse.creationTime,… | same |
| s07 | 6 | `ALTER TABLE openhouse.smoke_t1.s07_props SET TBLPROPERTIES ('openhouse.foo'='hack')` | fails with org.apache.iceberg.exceptions.BadRequestException: ...Bad tblproperties provided: Can't add, alter or drop table properties due to the restriction: [table pro… | fails with org.apache.iceberg.exceptions.BadRequestException: Malformed request: Bad tblproperties provided: Can't add, alter or drop table properties due to the restric… | same |
| s07 | 7 | `ALTER TABLE openhouse.smoke_t1.s07_props SET TBLPROPERTIES ('openhouse.tableId'='hack')` | fails with org.apache.iceberg.exceptions.BadRequestException: ...Bad tblproperties provided: Can't add, alter or drop table properties due to the restriction: [table pro… | fails with org.apache.iceberg.exceptions.BadRequestException: Malformed request: Bad tblproperties provided: Can't add, alter or drop table properties due to the restric… | same |
| s07 | 8 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s07_props ('openhouse.tableId')` | rows [openhouse.tableId,s07_props] | rows [openhouse.tableId,s07_props] | same |
| s08 | 1 | `CREATE TABLE openhouse.smoke_t1.s08_delete (id bigint, name string)` | succeeds | rows [] | same |
| s08 | 2 | `INSERT INTO openhouse.smoke_t1.s08_delete VALUES (1,'a'),(2,'b'),(3,'c')` | succeeds | rows [] | same |
| s08 | 3 | `DELETE FROM openhouse.smoke_t1.s08_delete WHERE id = 2` | succeeds | rows [] | same |
| s08 | 4 | `SELECT * FROM openhouse.smoke_t1.s08_delete ORDER BY id` | rows [1,a, 3,c] | rows [1,a, 3,c] | same |
| s08 | 5 | `SELECT operation FROM openhouse.smoke_t1.s08_delete.snapshots ORDER BY operation` | rows [append, overwrite] | rows [append, overwrite] | same |
| s09 | 1 | `CREATE TABLE openhouse.smoke_t1.s09_update (id bigint, name string)` | succeeds | rows [] | same |
| s09 | 2 | `INSERT INTO openhouse.smoke_t1.s09_update VALUES (1,'a'),(2,'b')` | succeeds | rows [] | same |
| s09 | 3 | `UPDATE openhouse.smoke_t1.s09_update SET name='B' WHERE id = 2` | succeeds | rows [] | same |
| s09 | 4 | `SELECT * FROM openhouse.smoke_t1.s09_update ORDER BY id` | rows [1,a, 2,B] | rows [1,a, 2,B] | same |
| s10 | 1 | `CREATE TABLE openhouse.smoke_t1.s10_merge (id bigint, name string) TBLPROPERTIES ('write.merge.mode'='merge-on-read','write.delete.mode'='merge-on-re…` | succeeds | rows [] | same |
| s10 | 2 | `CREATE TABLE openhouse.smoke_t1.s10_merge_src (id bigint, name string)` | succeeds | rows [] | same |
| s10 | 3 | `INSERT INTO openhouse.smoke_t1.s10_merge VALUES (1,'a'),(2,'b')` | succeeds | rows [] | same |
| s10 | 4 | `INSERT INTO openhouse.smoke_t1.s10_merge_src VALUES (2,'B'),(3,'c')` | succeeds | rows [] | same |
| s10 | 5 | `MERGE INTO openhouse.smoke_t1.s10_merge t USING openhouse.smoke_t1.s10_merge_src s ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.name = s.name WHEN N…` | succeeds | rows [] | same |
| s10 | 6 | `SELECT content, record_count FROM openhouse.smoke_t1.s10_merge.delete_files` | rows [1,1] | rows [1,1] | same |
| s10 | 7 | `SELECT operation FROM openhouse.smoke_t1.s10_merge.snapshots ORDER BY operation` | rows [append, overwrite] | rows [append, overwrite] | same |
| s10 | 8 | `SELECT * FROM openhouse.smoke_t1.s10_merge ORDER BY id` | rows [1,a, 2,B, 3,c] | rows [1,a, 2,B, 3,c] | same |
| s11 | 1 | `CREATE TABLE openhouse.smoke_t1.s11_timetravel (id bigint)` | succeeds | rows [] | same |
| s11 | 2 | `INSERT INTO openhouse.smoke_t1.s11_timetravel VALUES (1)` | succeeds | rows [] | same |
| s11 | 3 | `INSERT INTO openhouse.smoke_t1.s11_timetravel VALUES (2)` | succeeds | rows [] | same |
| s11 | 4 | `SELECT snapshot_id FROM openhouse.smoke_t1.s11_timetravel.snapshots WHERE parent_id IS NULL` | succeeds | rows [1782670797275774591] | same |
| s11 | 5 | `SELECT * FROM openhouse.smoke_t1.s11_timetravel VERSION AS OF <the first snapshot's id>` | rows [1] | rows [1] | same |
| s11 | 6 | `SELECT * FROM openhouse.smoke_t1.s11_timetravel ORDER BY id` | rows [1, 2] | rows [1, 2] | same |
| s12 | 1 | `CREATE TABLE openhouse.smoke_t1.s12_branch (id bigint)` | succeeds | rows [] | same |
| s12 | 2 | `INSERT INTO openhouse.smoke_t1.s12_branch VALUES (1)` | succeeds | rows [] | same |
| s12 | 3 | `ALTER TABLE openhouse.smoke_t1.s12_branch CREATE BRANCH b1` | succeeds | rows [] | same |
| s12 | 4 | `INSERT INTO openhouse.smoke_t1.s12_branch.branch_b1 VALUES (2)` | succeeds | rows [] | same |
| s12 | 5 | `SELECT * FROM openhouse.smoke_t1.s12_branch ORDER BY id` | rows [1] | rows [1] | same |
| s12 | 6 | `SELECT * FROM openhouse.smoke_t1.s12_branch.branch_b1 ORDER BY id` | rows [1, 2] | rows [1, 2] | same |
| s12 | 7 | `SELECT name, type FROM openhouse.smoke_t1.s12_branch.refs ORDER BY name` | rows [b1,BRANCH, main,BRANCH] | rows [b1,BRANCH, main,BRANCH] | same |
| s13 | 1 | `CREATE TABLE openhouse.smoke_t1.s13_rename (id bigint)` | succeeds | rows [] | same |
| s13 | 2 | `INSERT INTO openhouse.smoke_t1.s13_rename VALUES (1)` | succeeds | rows [] | same |
| s13 | 3 | `ALTER TABLE openhouse.smoke_t1.s13_rename RENAME TO smoke_t1.s13_renamed` | succeeds | rows [] | same |
| s13 | 4 | `SELECT * FROM openhouse.smoke_t1.s13_rename` | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: ...[TABLE_OR_VIEW_NOT_FOUND] The table or view... | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: [TABLE_OR_VIEW_NOT_FOUND] The table or view `openhouse`.`smoke_t1`.`s13_rename` cannot be found. Veri… | same |
| s13 | 5 | `SELECT * FROM openhouse.smoke_t1.s13_renamed` | rows [1] | rows [1] | same |
| s13 | 6 | `SHOW TABLES IN openhouse.smoke_t1 LIKE 's13_*'` | rows [smoke_t1,s13_renamed,false] | rows [smoke_t1,s13_renamed,false] | same |
| s13b | 1 | `CREATE TABLE openhouse.smoke_t1.s13b_rename (id bigint)` | succeeds | rows [] | same |
| s13b | 2 | `INSERT INTO openhouse.smoke_t1.s13b_rename VALUES (1)` | succeeds | rows [] | same |
| s13b | 3 | `ALTER TABLE openhouse.smoke_t1.s13b_rename RENAME TO openhouse.smoke_t1.s13b_renamed` | succeeds | fails with org.apache.iceberg.exceptions.BadRequestException: Malformed request: OpenHouse namespaces are databases and have exactly one level: openhouse.smoke_t1 | different: (d) IRC path, not decided |
| s13b | 4 | `SELECT * FROM openhouse.smoke_t1.s13b_rename` | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: ...[TABLE_OR_VIEW_NOT_FOUND] The table or view... | rows [1] | different: (d) IRC path, not decided |
| s13b | 5 | `SELECT * FROM openhouse.smoke_t1.s13b_renamed` | rows [1] | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: [TABLE_OR_VIEW_NOT_FOUND] The table or view `openhouse`.`smoke_t1`.`s13b_renamed` cannot be found. Ve… | different: (d) IRC path, not decided |
| s13b | 6 | `SHOW TABLES IN openhouse.smoke_t1 LIKE 's13b_*'` | rows [smoke_t1,s13b_renamed,false] | rows [smoke_t1,s13b_rename,false] | different: (d) IRC path, not decided |
| s14 | 1 | `CREATE TABLE openhouse.smoke_t1.s14_drop (id bigint)` | succeeds | rows [] | same |
| s14 | 2 | `DROP TABLE openhouse.smoke_t1.s14_drop` | succeeds | rows [] | same |
| s14 | 3 | `SELECT * FROM openhouse.smoke_t1.s14_drop` | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: ...[TABLE_OR_VIEW_NOT_FOUND] The table or view... | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: [TABLE_OR_VIEW_NOT_FOUND] The table or view `openhouse`.`smoke_t1`.`s14_drop` cannot be found. Verify… | same |
| s14 | 4 | `SHOW TABLES IN openhouse.smoke_t1 LIKE 's14_drop'` | rows [] | rows [] | same |
| s15 | 1 | `CREATE TABLE openhouse.smoke_t1.s15_partevo (id bigint, ts timestamp) PARTITIONED BY (days(ts))` | succeeds | rows [] | same |
| s15 | 2 | `ALTER TABLE openhouse.smoke_t1.s15_partevo ADD PARTITION FIELD id` | fails with org.apache.iceberg.exceptions.BadRequestException: ...Evolution of table partitioning and clustering columns are not supported... | fails with org.apache.iceberg.exceptions.BadRequestException: Malformed request: Evolution of table partitioning and clustering columns are not supported, recreate the t… | same |
| s15 | 3 | `DESCRIBE openhouse.smoke_t1.s15_partevo` | rows [id,bigint,null, ts,timestamp,null, ,,, # Partitioning,,, Part 0,days(ts),] | rows [id,bigint,null, ts,timestamp,null, ,,, # Partitioning,,, Part 0,days(ts),] | same |
| s16 | 1 | `CREATE TABLE openhouse.smoke_t1.s16_policy (id bigint, ts timestamp) PARTITIONED BY (days(ts))` | succeeds | rows [] | same |
| s16 | 2 | `ALTER TABLE openhouse.smoke_t1.s16_policy SET POLICY (RETENTION=30d)` | succeeds | rows [] | same |
| s16 | 3 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s16_policy ('policies')` | rows [policies,{ "retention": { "count": 30, "granularity": "DAY" }, "sharingEnabled": false }] | rows [policies,{ "retention": { "count": 30, "granularity": "DAY" }, "sharingEnabled": false }] | same |
| s17 | 1 | `CREATE TABLE openhouse.smoke_t1.s17_grants (id bigint)` | succeeds | rows [] | same |
| s17 | 2 | `GRANT SELECT ON TABLE openhouse.smoke_t1.s17_grants TO smoke_user` | fails with java.lang.IllegalArgumentException: ...smoke_t1.s17_grants is not a shared table... | fails with java.lang.IllegalArgumentException: smoke_t1.s17_grants is not a shared table | same |
| s17 | 3 | `ALTER TABLE openhouse.smoke_t1.s17_grants SET POLICY (SHARING=TRUE)` | succeeds | rows [] | same |
| s17 | 4 | `GRANT SELECT ON TABLE openhouse.smoke_t1.s17_grants TO smoke_user` | succeeds | rows [] | same |
| s17 | 5 | `SHOW GRANTS ON TABLE openhouse.smoke_t1.s17_grants` | rows [] | rows [] | same |
| s17 | 6 | `REVOKE SELECT ON TABLE openhouse.smoke_t1.s17_grants FROM smoke_user` | succeeds | rows [] | same |
| s17 | 7 | `SHOW GRANTS ON TABLE openhouse.smoke_t1.s17_grants` | rows [] | rows [] | same |
| s18 | 1 | `CREATE TABLE openhouse_views.smoke_t1_views.s18_views (id bigint)` | succeeds | rows [] | same |
| s18 | 2 | `INSERT INTO openhouse_views.smoke_t1_views.s18_views VALUES (1),(2)` | succeeds | rows [] | same |
| s18 | 3 | `CREATE VIEW openhouse_views.smoke_t1_views.s18_views_v AS SELECT id FROM openhouse_views.smoke_t1_views.s18_views` | succeeds | rows [] | same |
| s18 | 4 | `SELECT * FROM openhouse_views.smoke_t1_views.s18_views_v ORDER BY id` | rows [1, 2] | rows [1, 2] | same |
| s18 | 5 | `SHOW VIEWS IN openhouse_views.smoke_t1_views LIKE 's18_views_v'` | rows [smoke_t1_views,s18_views_v,false] | rows [smoke_t1_views,s18_views_v,false] | same |
| s18 | 6 | `DROP VIEW openhouse_views.smoke_t1_views.s18_views_v` | succeeds | rows [] | same |
| s18 | 7 | `SHOW VIEWS IN openhouse_views.smoke_t1_views LIKE 's18_views_v'` | rows [] | rows [] | same |
| s18 | 8 | `SELECT * FROM openhouse_views.smoke_t1_views.s18_views_v` | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: ...[TABLE_OR_VIEW_NOT_FOUND] The table or view... | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: [TABLE_OR_VIEW_NOT_FOUND] The table or view `openhouse_views`.`smoke_t1_views`.`s18_views_v` cannot b… | same |
| s19 | 1 | `CREATE TABLE openhouse.smoke_t1.s19_namespaces (id bigint)` | succeeds | rows [] | same |
| s19 | 2 | `SHOW DATABASES IN openhouse` | has the row [smoke_t1] | rows [smoke_t1] | same |
| s19 | 3 | `CREATE NAMESPACE openhouse.smoke_t1_new_ns` | fails with java.lang.UnsupportedOperationException: ...Describing database is not supported... | fails with java.lang.UnsupportedOperationException: Server does not support endpoint: POST /v1/{prefix}/namespaces | different: (a) decided |
| s19 | 4 | `SHOW DATABASES IN openhouse LIKE 'smoke_t1_new_ns'` | rows [] | rows [] | same |
| s20 | 1 | `SELECT * FROM openhouse.smoke_t1.s20_does_not_exist` | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: ...[TABLE_OR_VIEW_NOT_FOUND] The table or view... | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: [TABLE_OR_VIEW_NOT_FOUND] The table or view `openhouse`.`smoke_t1`.`s20_does_not_exist` cannot be fou… | same |
| s21 | 1 | `CREATE TABLE openhouse.smoke_t1.s21_ctas_src (id bigint, name string)` | succeeds | rows [] | same |
| s21 | 2 | `INSERT INTO openhouse.smoke_t1.s21_ctas_src VALUES (1,'a')` | succeeds | rows [] | same |
| s21 | 3 | `CREATE TABLE openhouse.smoke_t1.s21_ctas AS SELECT * FROM openhouse.smoke_t1.s21_ctas_src` | succeeds | rows [] | same |
| s21 | 4 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s21_ctas` | first column, sorted, is [current-snapshot-id, format, format-version, history.expire.max-ref-age-ms, openhouse.clusterId, openhouse.creationTime, openhouse.databaseId, … | rows [current-snapshot-id,5025876664914031888, format,iceberg/orc, format-version,2, history.expire.max-ref-age-ms,604800000, openhouse.clusterId,local-cluster, openhous… | same |
| s21 | 5 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s21_ctas_src` | first column, sorted, is [current-snapshot-id, format, format-version, history.expire.max-ref-age-ms, openhouse.clusterId, openhouse.creationTime, openhouse.databaseId, … | rows [current-snapshot-id,3998354827878048858, format,iceberg/orc, format-version,2, history.expire.max-ref-age-ms,604800000, openhouse.clusterId,local-cluster, openhous… | same |
| s21 | 6 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s21_ctas ('openhouse.tableVersion')` | rows [openhouse.tableVersion,INITIAL_VERSION] | rows [openhouse.tableVersion,INITIAL_VERSION] | same |
| s21 | 7 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s21_ctas ('openhouse.tableId')` | rows [openhouse.tableId,s21_ctas] | rows [openhouse.tableId,s21_ctas] | same |
| s21 | 8 | `SELECT count(*) FROM openhouse.smoke_t1.s21_ctas.snapshots` | rows [1] | rows [1] | same |
| s22 | 1 | `CREATE TABLE openhouse.smoke_t1.s22_rtas (id bigint, name string) TBLPROPERTIES ('team'='x')` | succeeds | rows [] | same |
| s22 | 2 | `INSERT INTO openhouse.smoke_t1.s22_rtas VALUES (1,'a')` | succeeds | rows [] | same |
| s22 | 3 | `ALTER TABLE openhouse.smoke_t1.s22_rtas SET TBLPROPERTIES ('replace.enabled'='true')` | succeeds | rows [] | same |
| s22 | 4 | `CREATE OR REPLACE TABLE openhouse.smoke_t1.s22_rtas TBLPROPERTIES ('extra'='y') AS SELECT 7L AS id, 'z' AS name, 'q' AS more` | succeeds | rows [] | same |
| s22 | 5 | `SELECT * FROM openhouse.smoke_t1.s22_rtas` | rows [7,z,q] | rows [7,z,q] | same |
| s22 | 6 | `DESCRIBE openhouse.smoke_t1.s22_rtas` | rows [id,bigint,null, name,string,null, more,string,null] | rows [id,bigint,null, name,string,null, more,string,null] | same |
| s22 | 7 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s22_rtas ('team')` | rows [team,x] | rows [team,x] | same |
| s22 | 8 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s22_rtas ('extra')` | rows [extra,y] | rows [extra,y] | same |
| s22 | 9 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s22_rtas ('replace.enabled')` | rows [replace.enabled,true] | rows [replace.enabled,true] | same |
| s22 | 10 | `SHOW TBLPROPERTIES openhouse.smoke_t1.s22_rtas ('openhouse.creationTime')` | rows [openhouse.creationTime,0] | rows [openhouse.creationTime,0] | same |
| s22 | 11 | `SELECT operation FROM openhouse.smoke_t1.s22_rtas.snapshots ORDER BY committed_at` | rows [append, append] | rows [append, overwrite] | different: (b) Spark 4 |
| s22 | 12 | `CREATE OR REPLACE TABLE openhouse.smoke_t1.s22_rtas_new AS SELECT 1L AS id` | succeeds | rows [] | same |
| s22 | 13 | `SELECT * FROM openhouse.smoke_t1.s22_rtas_new` | rows [1] | rows [1] | same |
| s23 | 1 | `CREATE TABLE openhouse.smoke_t1.s23_replace (id bigint)` | succeeds | rows [] | same |
| s23 | 2 | `REPLACE TABLE openhouse.smoke_t1.s23_replace (id bigint, x string)` | fails with org.apache.iceberg.exceptions.BadRequestException: ...REPLACE TABLE AS SELECT is not enabled for table openhouse.smoke_t1.s23_replace... | fails with org.apache.iceberg.exceptions.BadRequestException: Malformed request: REPLACE TABLE AS SELECT is not enabled for table openhouse.smoke_t1.s23_replace. You can… | same |
| s23 | 3 | `DESCRIBE openhouse.smoke_t1.s23_replace` | rows [id,bigint,null] | rows [id,bigint,null] | same |
| s23 | 4 | `ALTER TABLE openhouse.smoke_t1.s23_replace SET TBLPROPERTIES ('replace.enabled'='true')` | succeeds | rows [] | same |
| s23 | 5 | `REPLACE TABLE openhouse.smoke_t1.s23_replace (id bigint, x string)` | succeeds | rows [] | same |
| s23 | 6 | `DESCRIBE openhouse.smoke_t1.s23_replace` | rows [id,bigint,null, x,string,null] | rows [id,bigint,null, x,string,null] | same |
| s24 | 1 | `CREATE TABLE openhouse.smoke_t1.s24_namespaces (id bigint)` | succeeds | rows [] | same |
| s24 | 2 | `DESCRIBE NAMESPACE openhouse.smoke_t1` | fails with java.lang.UnsupportedOperationException: ...Describing database is not supported... | rows [Catalog Name,openhouse, Namespace Name,smoke_t1] | different: (d) IRC path, not decided |
| s24 | 3 | `ALTER NAMESPACE openhouse.smoke_t1 SET DBPROPERTIES ('k'='v')` | fails with java.lang.UnsupportedOperationException: ...Set properties on a database is not supported... | fails with java.lang.UnsupportedOperationException: Server does not support endpoint: POST /v1/{prefix}/namespaces/{namespace}/properties | different: (a) decided |
| s24 | 4 | `DROP NAMESPACE openhouse.smoke_t1` | fails with java.lang.UnsupportedOperationException: ...Describing database is not supported... | fails with java.lang.UnsupportedOperationException: Server does not support endpoint: DELETE /v1/{prefix}/namespaces/{namespace} | different: (a) decided |
| s24 | 5 | `SHOW NAMESPACES IN openhouse.smoke_t1` | fails with java.lang.UnsupportedOperationException: ...Openhouse supports 2-lvl namespace <schema>.<table>... | rows [] | different: (d) IRC path, not decided |
| s24 | 6 | `SHOW TABLES IN openhouse.smoke_t1 LIKE 's24_namespaces'` | rows [smoke_t1,s24_namespaces,false] | rows [smoke_t1,s24_namespaces,false] | same |
| s25 | 1 | `SHOW TABLES IN openhouse` | fails with org.apache.iceberg.exceptions.ValidationException: ...DatabaseId was not provided, for SQL please run "SHOW TABLES IN <databaseId>" instead... | fails with org.apache.iceberg.exceptions.NoSuchNamespaceException: Invalid namespace:  | different: (d) IRC path, not decided |
| s25 | 2 | `USE openhouse` | succeeds | rows [] | same |
| s25 | 3 | `SHOW TABLES` | fails with org.apache.iceberg.exceptions.ValidationException: ...DatabaseId was not provided, for SQL please run "SHOW TABLES IN <databaseId>" instead... | fails with org.apache.iceberg.exceptions.NoSuchNamespaceException: Invalid namespace:  | different: (d) IRC path, not decided |
| s25 | 4 | `USE spark_catalog` | succeeds | rows [] | same |
| s26 | 1 | `CREATE TABLE openhouse.smoke_t1.s26_describe (id bigint)` | succeeds | rows [] | same |
| s26 | 2 | `DESCRIBE TABLE EXTENDED openhouse.smoke_t1.s26_describe` | has the row [Name,openhouse.smoke_t1.s26_describe,], has the row [Type,MANAGED,], has the row [Provider,iceberg,], has a row starting with [Location,<warehouse>/smoke_t1… | rows [id,bigint,null, ,,, # Metadata Columns,,, _spec_id,int,, _partition,struct<>,, _file,string,, _pos,bigint,, _deleted,boolean,, ,,, # Detailed Table Information,,, … | different: (b) Spark 4 |
| s27 | 1 | `CREATE TABLE openhouse.smoke_t1.s27_unlock (id bigint)` | succeeds | rows [] | same |
| s27 | 2 | `ALTER TABLE openhouse.smoke_t1.s27_unlock UNLOCK` | succeeds | rows [] | same |
| s27 | 3 | `ALTER TABLE openhouse.smoke_t1.s27_missing UNLOCK` | fails with com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException: ...User table smoke_t1.s27_missing cannot be found... | fails with org.apache.iceberg.exceptions.NoSuchTableException: User table smoke_t1.s27_missing cannot be found | different: (a) decided |
| s28 | 1 | `CREATE VIEW openhouse.smoke_t1.s28_view AS SELECT 1` | fails with org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException: ...[SCHEMA_NOT_FOUND]... | fails with org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException: [SCHEMA_NOT_FOUND] The schema `smoke_t1` cannot be found. Verify the spelling and correctness… | same |
| s28 | 2 | `SHOW VIEWS IN openhouse.smoke_t1` | rows [] | rows [] | same |
| s28 | 3 | `DROP VIEW IF EXISTS openhouse.smoke_t1.s28_view` | succeeds | rows [] | same |
| s29 | 1 | `CREATE TABLE openhouse.smoke_t1.s29_rename (id bigint)` | succeeds | rows [] | same |
| s29 | 2 | `ALTER TABLE openhouse.smoke_t1.s29_rename RENAME TO smoke_t1.s29_rename` | fails with com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException: ...Cannot rename a table to the same current db name and table name: s29_re… | fails with org.apache.iceberg.exceptions.BadRequestException: Malformed request: Cannot rename a table to the same current db name and table name: s29_rename | different: (a) decided |
| s29 | 3 | `ALTER TABLE openhouse.smoke_t1.s29_rename RENAME TO other_catalog.smoke_t1.s29_renamed` | fails with java.lang.UnsupportedOperationException: ...Cannot rename tables across catalogs: from=openhouse.smoke_t1.s29_rename, to=other_catalog.smoke_t1.s29_renamed... | fails with org.apache.iceberg.exceptions.BadRequestException: Malformed request: OpenHouse namespaces are databases and have exactly one level: other_catalog.smoke_t1 | different: (d) IRC path, not decided |
| s29 | 4 | `ALTER TABLE openhouse.smoke_t1.s29_missing RENAME TO smoke_t1.s29_missing2` | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: ...[TABLE_OR_VIEW_NOT_FOUND] The table or view... | fails with org.apache.spark.sql.catalyst.ExtendedAnalysisException: [TABLE_OR_VIEW_NOT_FOUND] The table or view `openhouse`.`smoke_t1`.`s29_missing` cannot be found. Ver… | same |
| s30 | 1 | `CREATE TABLE openhouse.smoke_t1.s30_meta (id bigint, name string)` | succeeds | rows [] | same |
| s30 | 2 | `INSERT INTO openhouse.smoke_t1.s30_meta VALUES (1,'a')` | succeeds | rows [] | same |
| s30 | 3 | `ALTER TABLE openhouse.smoke_t1.s30_meta ALTER COLUMN name COMMENT 'hello'` | succeeds | rows [] | same |
| s30 | 4 | `SELECT count(*) FROM openhouse.smoke_t1.s30_meta.history` | rows [1] | rows [1] | same |
| s30 | 5 | `SELECT count(*) FROM openhouse.smoke_t1.s30_meta.files` | rows [1] | rows [1] | same |
| s30 | 6 | `DESCRIBE openhouse.smoke_t1.s30_meta` | rows [id,bigint,null, name,string,hello] | rows [id,bigint,null, name,string,hello] | same |
| s30 | 7 | `CALL openhouse.system.expire_snapshots(table => 'smoke_t1.s30_meta', retain_last => 1)` | rows [0,0,0,0,0,0] | rows [0,0,0,0,0,0] | same |
