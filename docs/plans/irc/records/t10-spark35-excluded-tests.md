# T10: the Spark 3.5 itest compiles and runs its own ALTER TABLE and CREATE TABLE tests

Every fact is marked [ran] (I executed it and read the result) or [read] (I read it in the code, git or GitHub).

- Branch `irc/t10-spark35-excluded-tests`, worktree `/home/user/oh-t10`, base 8876a7f2, later merged with b1dfe9b0 (#113) and 0a3bf529 (#116).
- Pull request 115 (draft) into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/115
- Head: cbd3bc05c01e465f5ccd90c02099e80746ebd4f9

## 1. The claim, verified

- [read] `integrations/spark/spark-3.5/openhouse-spark-itest/build.gradle` on 8876a7f2 excludes `**/AlterTable*.java` and `**/CreateTable*.java` from the test source set (which is `src/test/java` plus `src/shared/test/java`).
- [read] Files matching: only this project's own `e2e/ddl/AlterTableSchemaTestSpark3_5`, `AlterTableTestSpark3_5`, `CreateTablePartitionedTestSpark3_5`, `CreateTableTestSpark3_5`, `CreateTableWithPropsTestSpark3_5`. Nothing in `src/shared/test/java` matches either pattern.
- [ran] On 8876a7f2, default build and `-PicebergFork`: `build/openhouse-spark-3.5-itest/classes/java/test/.../e2e/ddl/` holds DescribeTable, DropTable, ShowTables (Spark3_5), InsertOverwriteTableTest, ShowDatabasesTest, UseCatalogTest and none of the five; no JUnit XML for the five.

### History
- [read] LinkedIn #221 (b7a38eb83c9940a58cb86fec79f92e705022b7df, 2024-11-19, "Add integration for openhouse-java-iceberg-1.5-runtime and openhouse-spark-3.5-runtime") created the project. It compiled the Spark 3.1 itest's directory plus its own, with `exclude '**/PartitionTest.java', '**/AlterTable*.java', '**/CreateTable*.java', '**/DescribeTableTest.java', '**/DropTableTest.java', '**/ShowTablesTest.java', '**/InsertIntoTableTest.java'`. The same commit added all `*Spark3_5` classes. So the five were written and never compiled: not "ran once and broke later".
- [read] The exclude line is unchanged in every later upstream version of the file (19e27cef, e2a9c56e, f62978db).
- [read] #353 (29b16a955aebba93a537aeb1b27a23b0f1c98850, 2025-08-05, bucket partitioning) edited the uncompiled `CreateTablePartitionedTestSpark3_5` (removed `testCreatePartitionedTableUnsupported`).
- [read] bf26aa84 (pull request 110) moved the shared tests, dropped the five exact-name patterns, kept the two wildcard ones with a comment that they hide five own classes.
- The local repository is shallow (root 63789621); upstream history was read through the GitHub API.

## 2. The change (5 commits)

| Commit | What | Why |
|---|---|---|
| d7e12edc | six CREATE TABLE tests queue two 404 answers, not three | the client reads twice before the create (#538); the third 404 went to the create |
| e119ebc3 | `CreateTableTestSpark3_5.testAuthZFailure` expects `WebClientResponseWithMessageException`, the message and status 403 | the client has thrown that class since #172; the Spark 3.1 version asserts the same |
| 8ee8b41d | the two exclude patterns and their comment are removed | the five classes compile and run |
| e4f818a0 | merge of origin/irc-integration (#113, V9d) | to run the suites on what will be merged; no conflicts |
| cbd3bc05 | merge of origin/irc-integration (#116, V16, N5, T13) | same; the tables service the catalog tests embed changed; no conflicts |

Diff against `irc-integration`: 4 files, +4 -15. No product code.

## 3. The five classes, run

[ran] Compiled without any source change. First run (default build, excludes removed, tests untouched): 12 tests, 7 failed, 5 passed.

| Class | Tests | First run | After fixes (both builds) |
|---|---|---|---|
| AlterTableSchemaTestSpark3_5 | 1 | 1 pass | 1 pass |
| AlterTableTestSpark3_5 | 2 | 2 pass | 2 pass |
| CreateTablePartitionedTestSpark3_5 | 5 | 4 fail, 1 pass | 5 pass |
| CreateTableTestSpark3_5 | 3 | 2 fail, 1 pass | 3 pass |
| CreateTableWithPropsTestSpark3_5 | 1 | 1 fail | 1 pass |

### Failure A (six tests): classification (a), stale test
`testCreateTable`, `testCreateTableWithPropsSuccessful`, `testSimpleCreateTimePartitionAndClusteredTable`, `testCreateTimePartitionAndTransformClusteredTable`, `testCreateTimePartitionedTableSuccessful`, `testCreateStringClusteringTableSuccessful`.
- [ran] `AssertionFailedError: Unexpected exception thrown: NoSuchTableException: Table dbCreate.<t> doesn't exist` at `OpenHouseTableOperations.handleCreateUpdateHttpError(OpenHouseTableOperations.java:525)`: the create received a 404.
- [read] Each test queued three 404 answers before the 201.
- [read] LinkedIn #538 (f921014eb45b79f633477ac149dd0c54b1307b6d, 2026-04-23, "Fix OpenHouse metadata fallback validation") added `OpenHouseCatalog.isValidIdentifier` and removed exactly this third 404 from the Spark 3.1 `CreateTableTest` (1), `CreateTableWithPropsTest` (1), `CreateTablePartitionedTest` (4) and `CTASTest` (4). At that commit the Iceberg 1.5 client compiled the Iceberg 1.2 client's sources (`srcDirs +=` in its build.gradle), so the change applied to Spark 3.5 too.
- [ran] The Spark 3.1 versions assert the same thing with two 404 answers and pass (Spark 3.1 itest `test`: 137 tests, 0 failures).
- [ran] Temporary probe (not committed) recording requests on Spark 3.5 for CREATE TABLE: `GET, GET, PUT, GET /v1/databases/dbProbe/tables/tb1`, identical on Iceberg 1.5.2.24 and 1.12.0-ohfork.a88f556c5067.
- Fix: two 404 answers. The assertion is unchanged.

### Failure B (one test): classification (a), stale test, wrong since written
`CreateTableTestSpark3_5.testAuthZFailure`.
- [ran] `Unexpected exception type thrown, expected: <...relocated...WebClientResponseException> but was: <com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException>`.
- [read] `WebClientResponseWithMessageException extends WebClientWithMessageException extends RuntimeException`; `handleCreateUpdateHttpError` wraps every other 4xx in it. The class dates from #172 (969fc6d9, 2024-08-27), before the test (#221).
- [ran] Spark 3.1 `CreateTableTest.testAuthZFailure` asserts the client's class, the message and status 403 and passes. [ran] `InsertIntoTableTestSpark3_5.testAuthZFailure` asserts the same class on Spark 3.5 and passes on both builds.
- Fix: assert class, message and `403 == getStatusCode()`, as the Spark 3.1 version does.

No (b) LinkedIn defect and no (c) Iceberg 1.12-only difference was found: [ran] the 12 tests give the same results on both builds.

## 4. Executed classes and counts

All from JUnit XML, tasks run with `--rerun`. The project has three test tasks: `catalogTest`, `statementTest`, `test` [read].

Spark 3.5 itest:

| Build | Task | Before | After | After merge with #113, and again with #116 |
|---|---|---|---|---|
| default | catalogTest | 269 (1 skipped) / 34 classes | same | same |
| default | statementTest | 137 / 10 | same | same |
| default | test | 124 / 17 | 136 / 22 | 136 / 22 |
| default | total | 530 / 61 | 542 / 66 | 542 / 66 |
| -PicebergFork | catalogTest | 269 (1 skipped) / 34 | same | same |
| -PicebergFork | statementTest | 137 / 10 | same | same |
| -PicebergFork | test | 124 / 17 | 136 / 22 | 136 / 22 |
| -PicebergFork | total | 530 / 61 | 542 / 66 | 542 / 66 |

0 failures, 0 errors everywhere [ran]. `diff` of the before and after lists (task, class, tests, failures, errors, skipped) on each build shows only five added lines, the five classes in `test` [ran]. The lists on the two builds are identical [ran].

Spark 3.1 itest, default build: catalogTest 87 (1 skipped) / 14, statementTest 126 / 9, test 137 / 22, total 350 / 45, 0 failures, before the merges and after each of them (#113, #116) [ran]. No "before my change" run was made for it: [read] no file the Spark 3.1 project compiles is changed.

[ran] `checkstyleTest` of the Spark 3.5 itest passes on both builds. `spotlessApply` changed nothing; `spotlessCheck` passes with and without the switch, before the merges and after each.

Lists: `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/t10/{before,after,after2,after3}-{default,fork}.classes`, `spark31-default{,-2,-3}.classes` (after2 = merged with #113, after3 = merged with #116; identical to after).

## 5. Coverage of the ten Spark 3.1-only classes on Spark 3.5 [read; the Spark 3.5 tests named ran green]

| Spark 3.1 class | Method | Spark 3.5 coverage |
|---|---|---|
| catalogtest.PartitionTest | testCreateTablePartitionedWithNestedColumn | PartitionTestSpark3_5.testCreateTablePartitionedWithNestedColumn2 |
| | testCreateTablePartitionedWithBucketTransform | nothing in the itest; in part apps/spark-3.5 BucketBehaviorTest.testBucketDistributionValidation and testSeparateBucketingByMultipleKeys (create with bucket and insert, spec via Iceberg API). DESCRIBE output of a bucket transform: nothing |
| | testBucketPartitioningCreatesCorrectNumberOfPartitions | nothing (BucketBehaviorTest asserts "at most", swallows exceptions, does not read `.partitions`) |
| e2e.ddl.AlterTableSchemaTest | testAlterTableUpdateSchema | AlterTableSchemaTestSpark3_5 same name (new here); real service: SparkSqlSmokeTest.s06_schema_addColumn |
| e2e.ddl.AlterTableTest | testSetTableProps, testUnsetTableProps | AlterTableTestSpark3_5 same names (new here); real service: SparkSqlSmokeTest.s07_properties_setUnsetAndReserved |
| | testSetPoliciesTablePropsBlocked | nothing for the `policies` key; in part s07 (openhouse.* keys rejected, message names `policies`) |
| e2e.ddl.CreateTablePartitionedTest | 5 methods | CreateTablePartitionedTestSpark3_5 same names (new here) |
| e2e.ddl.CreateTableTest | 3 methods | CreateTableTestSpark3_5 same names (new here) |
| e2e.ddl.CreateTableWithPropsTest | 1 method | CreateTableWithPropsTestSpark3_5 same name (new here) |
| e2e.ddl.DescribeTableTest | 3 methods | DescribeTableTestSpark3_5 same names (ran before) |
| e2e.ddl.DropTableTest | 5 methods | DropTableTestSpark3_5 same names (ran before); test503Error there lacks the status 503 assertion |
| e2e.ddl.ShowTablesTest | 3 methods | ShowTablesTestSpark3_5 same names (ran before) |
| e2e.dml.InsertIntoTableTest | 3 methods | InsertIntoTableTestSpark3_5 same names (ran before); testAuthZFailure expects the client's exception, Spark 3.1 expects SparkException |

Gaps after V9e if nothing is ported: the two bucket tests of PartitionTest (DESCRIBE output and exact partition count) and `SET TBLPROPERTIES('policies'=...)` being rejected.

## 6. Other findings (not changed)

- [read] The premise "ALTER TABLE and CREATE TABLE would be tested nowhere" holds for the mock-service tests. Against a real embedded service, Spark 3.5 already runs CREATE TABLE and ALTER TABLE in SparkSqlSmokeTest (s01, s06, s07, as LegacyPathSparkSqlSmokeTest and RestShimPathSparkSqlSmokeTest, 31 tests each [ran]).
- [read] `CreateTableWithPropsTest` (both versions) sends no TBLPROPERTIES in its SQL.
- [ran] The probe recorded GET, GET, PUT, GET for ALTER TABLE SET TBLPROPERTIES run after a CREATE in the same session; `AlterTableTestSpark3_5` labels three reads before the commit. All its queued answers are 200 with a table, so it passes either way.
- [ran] With a POSIX locale a fresh Gradle daemon fails `compileTestJava` of the Spark 3.5 itest ("unmappable character for encoding US-ASCII" in SnapshotExpirationRefsTest and BranchTestSpark3_5, both on the base). The build sets no source encoding. I ran every Gradle command with `LC_ALL=C.UTF-8`.
- [ran] The git hooks print `[[: not found` (line 14) and go on with `-q`, so they show nothing about spotless; I ran `spotlessApply` and `spotlessCheck` myself.

## 7. Deviations

- Merged origin/irc-integration into the branch twice after it moved (b1dfe9b0, #113; 0a3bf529, #116, on the coordinator's instruction), and ran everything again on each merged tree.
- Added `LC_ALL=C.UTF-8` to Gradle commands (see 6).
- Hooks call `./gradlew` themselves; I passed the worker limit and idle timeout to them through `GRADLE_OPTS` (`-Dorg.gradle.workers.max=2 -Dorg.gradle.daemon.idletimeout=60000`).
- `gh pr create` is blocked here (GraphQL); the pull request was opened through the REST API.

## 8. CI on the pull request [read]

- Head e4f818a0 (merged with #113): `build-run-tests / Build and Run Tests` success, `iceberg-fork-build / Opt-in build against the Iceberg fork` success.
- Head cbd3bc05 (merged with #116): both workflows had started and not finished when this record was written.
