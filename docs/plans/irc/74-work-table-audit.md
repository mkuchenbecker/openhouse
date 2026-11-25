# Audit of the open work items against the code

Update 2026-11-24: since this audit, N8, V18 and C15 have closed (pull request 139, merge commit `e6c52c21`; `75-n8-v18-c15-and-ci-fix-result.md`). X10 (rest) and X15 have also closed (pull request 137, merge commit `461ae437`; `76-x10-shapes-result.md`). T6 and N10 have also closed (pull request 140, merge commit `dfcd15a0`; `77-t6-n10-result.md`). V22 is new. V19, V20, V22, N16 and X9 have also closed (pull requests 142 and 141, merge commits `af210f1b` and `3079859d`; `78-v19-v20-v22-n16-x9-result.md`). C19 has also closed (pull request 143, merge commit `f07a92e1`); it was opened after the audit. C20, X11, X12 and X13 have also closed (pull requests 144 and 145, merge commits `7c9fa958` and `9dfbbca7`; `79-c20-x11-x12-x13-result.md`); X11 to X13 were open at the audit, and C20 was opened after it. C3 and C4 have also closed (pull request 146, merge commit `22017134`; `80-c3-c4-result.md`); both were open at the audit, and N18 is new. V17 and X16 have also closed (pull requests 147 and 148, merge commits `2ba15929` and `886b660b`; `81-v17-x16-result.md`); both were open at the audit. The counts below are as written at the audit.

Bottom line: 62 rows were open at the start of this audit, and 8 of them were already true in the code. Of the rest, 8 are
partly done, 16 wait on Mike, 2 cannot be settled by reading, and 28 are still open. The rows come from `04-work.md`,
`30-work-defaults.md`, `41-work-platforms-v3.md`, and W5 and W10 of `14-native-defaults-design.md` (no other file has rows
that these three lack; `29-capabilities-over-irc-work.md` repeats rows that are done). Written 2026-11-24 by reading
`irc-integration` at `4b696a55` in a fresh worktree; nothing was built or run, so every statement is [read].

| Class | Count | Rows |
| --- | --- | --- |
| ALREADY TRUE | 8 | C8, C12, T12, I6e, N1, N2, W5, W10 |
| PARTLY DONE | 8 | C4, C7, V10, N4, X0, X2, X3, X10 |
| WAITS ON MIKE | 16 | V13, N5, V21, X8, X14, N12, N13, N14, N15, V11d, V12b, V12c, V12d, V12e, W8, C13 |
| CANNOT TELL BY READING | 2 | I7, T8 |
| STILL OPEN | 28 | see the last section of the table |

An open row here is one that did not say done, proven, merged or removed. N11 is counted as done: its landable part
merged in pull request 135 and what remains is tracked as N12 to N15. V9 and V11, V12 are pointers and are not counted.

## Already true, partly done, waiting, cannot tell

| Id | Class | Evidence |
| --- | --- | --- |
| C8 | ALREADY TRUE | `integrations/python/dataloader/CLAUDE.md` lines 36 and 49 name `tables-test-fixtures-iceberg-1.5/src/shared/main/resources/dummy.token`, which exists; fixed by commit `dec16c80`. |
| T12 | ALREADY TRUE | `integrations/java/openhouse-rest-compat-kit/build.gradle:114-119` excludes the kit classes from `test` with `failOnNoMatchingTests = false`; commit `98328ea2`. |
| C12 | ALREADY TRUE | Root `build.gradle:125-131` sets `options.encoding = 'UTF-8'` on every `JavaCompile` and `Javadoc` task; pull request 136, merge commit `4b696a55`. |
| I6e | ALREADY TRUE | `settings.gradle` includes no Iceberg 1.2 or Spark 3.1 project; deleted by V9e (pull request 118) under D3. |
| N1 | ALREADY TRUE | `RestHttpPathSparkSqlSmokeTest` runs the smoke suite on the `rest-http` path (`SparkCatalog type=rest`); N1-c, pull request 128. |
| N2 | ALREADY TRUE | The default build is the fork's Iceberg 1.12 line (`settings.gradle`, V9e) and runs the Spark 3.5 suites; the harness on it is I6f (pull request 105). Not re-run. |
| W5 | ALREADY TRUE | Done inside W6 (pull request 124): a non-null `write-default` below v3 is refused with `COLUMN_DEFAULT_REQUIRES_V3`. |
| W10 | ALREADY TRUE | Done inside W6 (pull request 124): an ordinary commit cannot change the format version (`FormatVersionCommitPolicy`). |
| C4 | PARTLY DONE | `OpenHouseRestCatalog.StaleBaseCheckingOperations` (commit `609f27df`) and `CatalogPluginContractTest.rawCommitWithStaleBaseFails` run on both paths. Remains: a Spark-level property or policy commit from a stale handle (`09-seam-result.md`, row on requirement-free commits); no test. |
| C7 | PARTLY DONE | `scripts/iceberg-fork/publish-local.sh:44-50` runs without `flock` (commit `f50eecb1`) but then takes no lock; two runs at once on macOS do not serialize. macOS not run. |
| V10 | PARTLY DONE | The advertised endpoint list is `IcebergRestServedRoutes` (V10a). View registration is not served: `POST .../register-view` is in `UNSERVED_SPEC_ROUTES` (`IcebergRestCatalogController:153`). Spec revision not checked. |
| N4 | PARTLY DONE | The IRC rename onto a view's name answers 409 and changes nothing (`IrcViewRoutesTest:975-1008`, V13b). The native rename still answers 500 and is pinned so; the row said both go through one path. |
| X0 | PARTLY DONE | W1 to W7 are done; W8 (the migration, Mike's, D12) is the whole of what remains. |
| X2 | PARTLY DONE | Maintenance on v3 is X6 (done, pull request 125) and the races are X10; only the wider delete, update and merge scenarios remain. |
| X3 | PARTLY DONE | Row ids across compaction are proven by X6 (pull request 125); only the wider scenarios remain. |
| X10 | PARTLY DONE | The concurrency part is pull request 130; the open list is in the row (HDFS and object stores, branches and tags, partition evolution, equality deletes, and the rest). |
| V13 | WAITS ON MIKE | D32: what "conforming" means. Kit: 42 of 160 pass, 95 fail, matching `expected-outcomes.txt` (182 lines). |
| N5 | WAITS ON MIKE | D35: the native fix changes native behavior; pinned by five tests. |
| V21 | WAITS ON MIKE | D47: `POST .../views/rename` is still refused; House Tables has no atomic view rename. |
| X8 | WAITS ON MIKE | D46: how a retention backup treats deletes on merge-on-read tables. |
| X14 | WAITS ON MIKE | A refusal, his say (`65-x10-concurrency-result.md`); no decision id. `OrphanFilesDeletionSparkApp:110` floors only argument parsing (3 days). |
| N12, N13, N14, N15 | WAITS ON MIKE | D50 and `58-waiting-on-mike.md`: Spark 4.2 uber jar, image, `variant` routing, job state without House Tables. No Spark 4.2 jar or image in the build. |
| V11d | WAITS ON MIKE | D44: nothing sets `scan-planning-mode`; one-way for every client's read path. |
| V12b, V12c, V12d, V12e | WAITS ON MIKE | D45: vending and signing are not built; `/credentials` answers an empty list. |
| W8 | WAITS ON MIKE | D12: translating stored defaults and upgrading a real table. |
| C13 | WAITS ON MIKE | The publishing decision (D38 addendum): `.github/workflows/build-tag-publish.yml` tags at line 33 and publishes at line 51. |
| I7 | CANNOT TELL | The Iceberg fork's CI. Needs one full run of the fork's workflow on the 1.12 branch; nothing in this repository shows it. |
| T8 | CANNOT TELL | Needs a CI failure that names its test. `SparkIrcEndpointsTest.existenceChecksAreHeadRequests` exists; no failure was seen since pull request 112. |

## Still open (code shows it)

| Id | Class | Evidence |
| --- | --- | --- |
| I6d | STILL OPEN | `gradle/iceberg-fork.gradle:25` still tells the developer to run `publish-local.sh` first; no remote repository. |
| I9 | STILL OPEN | Left unaddressed by D4; no delete-file replication code for non-HDFS storage. |
| T6 | STILL OPEN | `SparkSqlSmokeScenarios.s17` expects `SHOW GRANTS` empty even after a GRANT (no OPA base URI in the fixture). |
| C3 | STILL OPEN | `09-seam-result.md`: on the REST-shaped path a gated RTAS writes data first; no stage-replace; no test. |
| T7 | STILL OPEN | Not designed; no code (D4). |
| T11 | STILL OPEN | No test reads or writes real S3; only `IrcConfigFileIoTest` names `S3FileIO`. |
| V17 | STILL OPEN | `IcebergRestWriteHandler.refuseADirectoryInUse` (line 810) lists `findAllByDatabaseId` and pages soft-deleted rows. |
| V18 | STILL OPEN | `@ConditionalOnClass` remains in `IcebergRestWriteController`, `IcebergRestViewController`, `IcebergRestViewHandler`, `IcebergRestWriteHandler`; the three boundary tests and `IcebergRestRequirements` exist. |
| V19 | STILL OPEN | `MetadataLocationLimitTest:455` pins a rename to 129 or more characters as a defect, unchanged. |
| V20 | STILL OPEN | `IcebergRestApiHandler:124` sets one `io-impl` for the cluster's default storage, not per table. |
| C11 | STILL OPEN | `src/shared` directories exist in five projects; `-PicebergFork` is still accepted and does nothing (`settings.gradle`). |
| C15 | STILL OPEN | No ordering of the OpenAPI schema properties is configured (`openhouse.springboot-conventions.gradle`, `application.properties`); not run. |
| W9, W11 | STILL OPEN | Deferred by D11; no DDL for defaults, no Spark 4.2 ORC opt-in. |
| W13 | STILL OPEN | `TablesClientFactory` still warns and declares `column-defaults` when `IcebergLine` cannot be loaded; `47-v9d-deployables-result.md:102`. |
| W14 | STILL OPEN | `NativeColumnDefaultsSparkTest.spark35WritesNoWriteDefault:479` pins the limitation; `docs/development` does not name it. |
| N8 | STILL OPEN | `PolicyTag.tags` is a `Set<Tag>` with no ordering. |
| N9 | STILL OPEN | `SparkSqlSmokeScenarios:1224` still pins the quote difference by `WITH TIMEZONE`. |
| N10 | STILL OPEN | `RestHttpPathSparkSqlSmokeTest` still assumes the 1.12 line and skips otherwise. |
| N16 | STILL OPEN | `Operations.getTable:99-101` calls `getCatalog()` on every load (`Operations.java:736`). |
| X7 | STILL OPEN | `FormatVersionCommitPolicy:142` refuses replication of v3 tables naming X7. |
| X9 | STILL OPEN | No retention code logs or counts delete files in expired partitions. |
| X11 | STILL OPEN | `FormatV3MaintenanceTest:1057` still pins "parser is not an Iceberg ExtendedParser". |
| X12 | STILL OPEN | `TableStatsCollectorUtil` counts delete files by kind with no deletion-vector case. |
| X13 | STILL OPEN | No remove-dangling-deletes call in `apps/spark-3.5/src/shared/main`. |
| X15 | STILL OPEN | `TableStatsCollectorUtil` maps partitions from `table.spec()` (current spec). |
| C17, C18 | STILL OPEN | `split-build.yml` has no path filter, and its cache is read-only; no cache fix. |

## Rows whose text contradicts the repository

- I6d says "the opt-in variant"; the 1.12 line is the default build since pull request 118. T11 says "on the opt-in
  build" for the same reason. Both are corrected in the tables.
- I6e says the 1.2 line "is removed when V9e lands" as a future event; it was removed. X2 and X3 say maintenance and
  compaction are X6 as if open; X6 is done. W13 says "the scheduler's uber jar lacks `IcebergLine`"; the scheduler image
  now carries the Spark 3.5 apps jar (V9d), and the warning remains (corrected to say so).
- Done rows keep the words "opt-in build" and `-PicebergFork` where they record what was true on their date (I6a to I6h,
  W3, W4, W12, C5, C6, I10, V9a, N3b). They are historical and are not changed. `-PicebergFork` is accepted and does
  nothing now (`settings.gradle`); `-PicebergLinkedIn` selects the reference line.
- The single-job CI wording in `18-state.md` is still true for post-merge runs and is not a contradiction. The Iceberg 1.2
  fixtures project and the Spark 3.1 projects are named only in done rows. No row carries an old line count.
