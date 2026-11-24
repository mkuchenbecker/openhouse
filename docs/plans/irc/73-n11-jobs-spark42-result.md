# N11 result: the maintenance jobs on Spark 4.2 over the Iceberg REST routes

Bottom line: the maintenance jobs compile unchanged on Spark 4.2 and pass their whole suite there, with Iceberg's REST
catalog as the only catalog: 395 tests, 0 failed, 5 skipped [ran]. The Spark 3.5 jobs are unchanged, shown test by test
on both build lines [ran]. Merged as pull request 135 (work item N11, decision D50) into `irc-integration` on 2026-11-24,
merge commit `e2823b69` (head of the pull request `f5d384bc`). What waits on Mike is the deployment (N12 to N15).
Sources: the record `jobs-spark42-scout.md` in the reports directory and the merge message. Supersedes the scout's
`70-n11-jobs-spark42-scout-result.md` for the final state. Post-merge CI is not read yet. Dated 2026-11-24.
Marks: [ran] run, in worktree `/home/user/oh-jobs42` on JDK 17 or in CI; [read] read in code; [inferred] reasoning only.

## The new project and how CI runs it

- `:apps:openhouse-spark-apps-4.2_2.13` (directory `apps/spark-4.2`) builds the shared main and test sources of
  `apps/spark-3.5` on Spark 4.2.0, Scala 2.13 and `iceberg-spark-runtime-4.2_2.13` (Iceberg 1.12). Every catalog in its
  tests is Iceberg's `RESTCatalog` against the service's `/iceberg` routes [ran].
- Its tests are part of its `check` (the scout had them opt-in while red). The project is absent from the
  `-PicebergLinkedIn` build [read]. `scripts/ci/check-test-groups.py` holds the partition on both lines: default 775
  tasks, 43 test tasks, the project in its own group `apps-spark-4.2` (143 tasks); reference 686 and 34, group empty [ran].
- In CI it is the job `Default build, apps-spark-4.2`, 17m26s. Locally its `check` takes 21m47s [ran].
- CI on `f5d384bc`: all 14 check runs of the split build passed (the Docker job skipped on the reference line)
  [ran, check-runs API]. Default build: apps-spark-3.5 22m27s, spark-3.5 itest 12m56s, spark-4.2 itest 16m27s, rest
  14m27s, Docker images 6m44s. Reference build: apps-spark-3.5 17m22s; the 4.2 jobs have nothing to run.

## What differs by line, and why [ran]

Each is declared in the shared tests (`JobsLine`) with its reason; nothing is skipped.

1. Concurrency refusal (22 cases): Spark 3.5 gets `CommitFailedException: 409 ... Conflict detected`; Spark 4.2 gets
   `Commit failed: Requirement failed: ...`. The REST route answers the specification's conflict; the refusal is the same.
2. A commit prepared before a snapshot expiry (6 cases): refused and retried on Spark 3.5, accepted on Spark 4.2. A REST
   commit sends changes plus requirements and expiry does not move the branch, so the requirements hold. Asserted on
   both lines: outcome serial, rows in serial order, surviving row ids kept and unique, every referenced file exists, no
   expired snapshot returns, expired files gone. No REST commit-path defect was seen.
3. Variant tests (8) and a types test (1): on Spark 3.5 the Spark-reading jobs and every closing validation fail,
   because it has no variant type; on Spark 4.2 every job completes and validates.
4. Replica table setup (2): SQL `CREATE` with `openhouse.tableType` on Spark 3.5; the native API on Spark 4.2, because the
   REST routes refuse to set the table type.
5. The SYSTEM reader in `AppsTest`: Spark 3.5 reads `action-type`; Spark 4.2 also reads `header.X-OpenHouse-Action-Type`,
   because `RESTCatalog` does not read `action-type`.
6. Test helper `VariantData.read` now reads the projection's columns by name (files with deletes add a row position), so
   no test compares less than it claims.

## Three changes in shared job code, and how each was shown to leave Spark 3.5 unchanged

- `Operations.getCatalog`: with `catalog-impl` set it makes the same `loadCatalog` call as before; with only `type` set it
  calls `CatalogUtil.buildIcebergCatalog`; with neither it fails as before. Test `Spark42TypeRestCatalogTest` (4.2 only)
  failed before the fix with a null pointer and passes after [ran]. The old call is the old call in the diff [read].
- `BaseSparkApp.declareSystemActionOverRest`: adds the header property only when the app declares SYSTEM and the catalog is
  the REST catalog. `SystemActionDeclarationTest` (both lines) asserts the OpenHouse catalog's session configuration is
  unchanged, REST gets only the header, a non-SYSTEM app is unchanged [ran].
- `SortStatsCollectionSparkApp.createTempTableStatement`: a format v1 or v2 source gets the exact old statement; a v3
  source adds `format-version` 3 and the source's `write.format.default`. The app loads the source once more to read its
  version. `SortStatsTempTableTest` (both lines) pins both statements and, through the service, v2 to v2 and v3 to v3 [ran].
- No Spark 3.5 build file changed; the diff to `apps/spark-3.5` is shared sources and tests only [read].

## A `variant` table on Spark 4.2 [ran]

Every job completes and its closing validation passes. Strict compaction commits a replace snapshot, rewrites every data
file, leaves no delete files, keeps live rows, variant values, `_row_id` and `_last_updated_sequence_number`, and deleted
rows stay deleted. Retention also runs. So D48's `variant` default now waits only on N12 to N14.

## Evidence counts (tests / failed / skipped) [ran]

| Suite | Before | After |
| --- | --- | --- |
| Spark 4.2 jobs, 1.12 line | scout's first run 392 / 41 / 5 | 395 / 0 / 5 |
| Spark 3.5 jobs, 1.12 line (before at `97fe7a39`) | 389 / 0 / 5 | 394 / 0 / 5 |
| Spark 3.5 jobs, `-PicebergLinkedIn` | 389 / 0 / 53 | 394 / 0 / 53 |

Compared by test name, no Spark 3.5 test changed status on either line. The difference is 5 new tests and 8 variant tests
renamed (their names said they fail). `spotlessCheck` passes.

## What is not in it and waits on Mike (one-way doors, N12 to N15)

- The Spark 4.2 uber jar (the relocation rules sit in `openhouse.apps-spark-common`, which brings Hadoop 2.10).
- The Spark 4.2 image, `jobs.yaml` entries and the jobs service's token key (it writes `auth-token`; `RESTCatalog` reads
  `token`). `JobsRegistry.from` refuses two apps of one type, so 3.5 and 4.2 are a choice per deployment [read].
- Routing `variant` tables, or a cluster, to the Spark 4.2 jobs; then the `variant` default; the service's refusal of a
  retention policy on `variant` tables.
- Job state (to House Tables) and optimizer reporting from a Spark driver that cannot reach those services.

## Known gaps

- `getCatalog` still builds a new catalog on every table load: 241 `GET /config` requests in one suite [ran, scout].
  Left as is because changing it could not be shown to leave the Spark 3.5 path unchanged. It is work item N16.
- No uber jar, image, `spark-submit` or scheduler run was made [merge message].
- Pull request 135's branch was merged with the base before pull request 134 landed, so its post-merge run is the first on
  the combined tree. Post-merge CI for 134 and 135 is not read.
- The scout's access log showed no native request from the jobs [ran, scout].
