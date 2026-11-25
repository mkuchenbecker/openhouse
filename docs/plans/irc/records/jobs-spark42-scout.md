# Maintenance jobs on Spark 4.2 over the Iceberg REST routes (record: scout, then landing J1-J5)

Marks: [ran] run in worktree /home/user/oh-jobs42, JDK 17; [read] read in code; [inferred] reasoning, not run.
Branch `irc/jobs-spark42-scout`, head f5d384bc8189e5eb2ffc62570a2e25a37a0bc022. Draft pull request 135 (https://github.com/mkuchenbecker/openhouse/pull/135). Base merged: origin/irc-integration 7e57bd21.

## Bottom line

- [ran] `:apps:openhouse-spark-apps-4.2_2.13` (apps/spark-4.2) builds apps/spark-3.5's shared main and test sources on
  Spark 4.2.0 / Scala 2.13 / iceberg-spark-runtime-4.2_2.13; every catalog is Iceberg's RESTCatalog at the service's
  `/iceberg` routes. Its `check` on the merged tree: 395 tests, 0 failed, 5 skipped; 21m47s (test time 1225 s).
- [ran] Its tests are part of `check`; `scripts/ci/check-test-groups.py` (default line): partition holds, new group
  `apps-spark-4.2` (143 tasks, test task `:apps:openhouse-spark-apps-4.2_2.13:test`).
- [ran] Spark 3.5 apps suite before any change (97fe7a39): 1.12 line 389/0/5, -PicebergLinkedIn 389/0/53.
  After (merged tree): 1.12 394/0/5, -PicebergLinkedIn 394/0/53; per test no status change; +5 new tests,
  8 variant tests renamed. check-test-groups.py: partition holds on both lines. spotlessCheck passes.
- [ran] Variant tables on Spark 4.2: every job completes and validates (compaction rewrites, keeps variant values,
  row ids, sequence numbers); D48's default could flip once the jobs for those tables run on 4.2 (owner).

## CI on f5d384bc [ran, check-runs API]

All 14 check runs success (Docker job skipped on the reference line). Default build: apps-spark-4.2 17m26s,
apps-spark-3.5 22m27s, spark-3.5 itest 12m56s, spark-4.2 itest 16m27s, rest 14m27s, Docker images 6m44s, test groups.
Reference build: apps-spark-3.5 17m22s, spark-3.5 itest 11m48s, rest 13m42s, apps-spark-4.2 and spark-4.2 itest
about 30s (nothing to run), test groups.

## Commits (on top of the scout's four)

1. 5312296d J1 tests part of check. 2. 3cb44106 J2 per-line expectations. 3. 0e54d09c J3 test (type=rest).
4. d77c3d54 J3 fix. 5. 99c1e698 J4 header on REST only (+ test). 6. 42ff51fc J5 temp table version (+ test).
7. f5d384bc merge origin/irc-integration 7e57bd21.

## Differences declared for the Spark 4.2 line (each in the test, with its reason) [ran]

| Where | Spark 3.5 (OpenHouse catalog) | Spark 4.2 (RESTCatalog) | Reason |
|---|---|---|---|
| FormatV3ConcurrencyTest refusal (22 cases) | `CommitFailedException: 409 ... Conflict detected` | `CommitFailedException: Commit failed: Requirement failed: ...` | the REST route answers the spec's conflict; same refusal |
| loser racing a snapshot expiry (6 cases) | attempt built on N refused, retried | attempt accepted ("ACCEPTED_SERIAL_EQUIVALENT") | REST sends changes + requirements; expiry does not move the branch |
| FormatV3VariantJobsTest (8) / TypesJobsTest (1) | Spark-reading jobs and every closing validation fail | every job completes correctly, validations pass | Spark 3.5 has no variant type |
| OFD replica setup (2) | SQL CREATE with `openhouse.tableType` | native API create (UUID, databaseId, tableId) | REST routes refuse to set the table type |
| AppsTest SYSTEM reader | `action-type` | also `header.X-OpenHouse-Action-Type` | RESTCatalog does not read action-type |

Accepted-over-REST cases, asserted on both lines [ran]: outcome SERIAL, rows = serial order, surviving row ids kept and
unique, every referenced file exists, no expired snapshot back (`rlw` races: [N, write]; `expiry-then-delete`: [N+1]
on both lines: the job's age pass is the raced attempt, its keep-last pass then runs on N+1), expired files not
referenced and gone. None failed, so no REST commit-path defect seen.

Helper fix: `VariantData.read` reads the projection's columns by name (files with deletes add a row position).

## J3 to J5 (changes to shared job source) [ran unless marked]

- J3 `Operations.getCatalog`: catalog-impl set -> the same `loadCatalog` call as before; catalog-impl absent and type
  set -> `CatalogUtil.buildIcebergCatalog`; neither -> fails as before. Test `Spark42TypeRestCatalogTest` (4.2 only):
  failed before the fix (NPE) [ran in scout], passes after. New catalog per `getTable` left as is (listed).
- J4 `BaseSparkApp.declareSystemActionOverRest`: adds the header property only when the app declares SYSTEM and
  `Operations.isRestCatalog` (type=rest or catalog-impl assignable to RESTCatalog). `SystemActionDeclarationTest`
  (both lines): OpenHouse catalog -> whole session conf unchanged; REST -> only the header added; non-SYSTEM app ->
  unchanged. AppsTest SYSTEM_ONLY cases pass on 4.2.
- J5 `SortStatsCollectionSparkApp.createTempTableStatement`: v1/v2 source -> the exact old statement; v3 -> adds
  `tblproperties ('format-version'='3'[, 'write.format.default'=<source's>])`. The app loads the source once more to
  read its version. `SortStatsTempTableTest` (both lines): statements, and through the service v2 -> v2 copy, v3 -> v3.

## Spark 3.5 unchanged: what was checked

- Suite counts before/after on both lines (table in the final report).
- J3/J4/J5 code paths for the OpenHouse catalog are the old calls (read in diff); SystemActionDeclarationTest and
  SortStatsTempTableTest pin it.
- No 3.5 build file changed; apps/spark-3.5 diff is shared sources and tests only.

## Owner items (not in this pull request)

- J6 4.2 uber jar (relocation rules factored out of `openhouse.apps-spark-common`, which brings Hadoop 2.10).
- J7 jobs service token key for RESTCatalog (`token`; JobsRegistry writes `auth-token`), 4.2 `jobs.yaml` entries,
  a Spark 4.2 image. `JobsRegistry.from` refuses two apps of one type.
- J8 route variant tables (or a cluster) to the 4.2 jobs; then D48's default; the service's refusal of a retention
  policy on variant tables (retention runs on 4.2 [ran]).
- J9 job state (StateManager -> House Tables) and optimizer reporting from the Spark driver, if a REST-only platform
  cannot reach those services.
- Later: one catalog per Operations instead of per `getTable` (241 `GET /config` in one suite [ran, scout]).

## Scout measurements kept

Scout full run before J2-J5: 392/41/5; causes: 28 concurrency (22 wording, 6 accepted), 9 variant pins, 2 AppsTest
SYSTEM, 2 replica setup. `type=rest`: MaintenanceTest 39/39 NPE. Access log: 0 native requests from the jobs.
