# N11 scout result: the maintenance jobs on Spark 4.2

Bottom line: the maintenance jobs compile on Spark 4.2 with Iceberg 1.12 with no source change. The first full run, with
every catalog being Iceberg's own REST catalog at the tables service's Iceberg REST routes, ran 392 tests: 41 failed and 5
were skipped. No failure loses data. The format v3 maintenance suite passes 39 of 39. Every failure is a convention of the
OpenHouse catalog that the tests or the job code carry, not Spark 4.2. Orchestrator decision D50
(`67-decisions-continued-6.md`) lands the first five items as one pull request and sends the deployables to Mike.
Source: the scout's record `jobs-spark42-scout.md` in the reports directory. Worktree `/home/user/oh-jobs42`, branch
`irc/jobs-spark42-scout`, head `97fe7a39`, base `70b35d7a`. No pull request existed when the scout reported. Dated 2026-11-24.
Marks: [ran] run in the worktree on JDK 17; [read] read in code; [inferred] reasoning, not run.

## The new project [ran unless marked]

- `apps/spark-4.2` (`:apps:openhouse-spark-apps-4.2_2.13`) builds the same `apps/spark-3.5/src/shared/main` sources on
  Spark 4.2.0, Scala 2.13 and `iceberg-spark-runtime-4.2_2.13`. Its test sources are the shared ones plus
  `src/test-spark-4.2`; two Spark 3.5 test files are excluded and replaced by same-named classes. It exists only in the
  default 1.12 build, like the other Spark 4.2 modules.
- Dependency fixes, all in its build file: Jackson pinned to Spark's 2.21 line (Iceberg 1.12 asks for 2.22 and Spark's
  `jackson-module-scala` refuses it); root forces cleared; the Hadoop 2.10 jars of OpenHouse libraries excluded in favour
  of Spark's `hadoop-client-api` 3.5.0; Spark 3.5 and Scala 2.12 jars that `libs:datalayout` declares excluded (its bytecode
  runs on 4.2); commons-lang 2.6 added, which Hadoop 2.10 used to bring.
- Test helpers: `tablestest.OpenHouseSparkITest` (service in its own JVM, catalogs `openhouse` and `default_iceberg` are
  `SparkCatalog` over `RESTCatalog` at `<service>/iceberg`); `jobs.spark.FormatV3TablesServer` (a second service with the
  v3 and `variant` switches); `javaclient.CommitHookCatalog` (a `RESTCatalog` whose HTTP client runs `CommitHook` around
  table commits); `JobsTestService`. New tests: `Spark42VariantCompactionTest` 3/0/0 and `Spark42SystemActionHeaderTest` 1/0/0.
- The test task was opt-in (`-PappsSpark42Tests`) on the branch because the suite was red. D50 makes it run in CI.
- Existing builds [ran]: the Spark 3.5 apps project compiles on the default line and on `-PicebergLinkedIn`. The diff
  against the base is `settings.gradle` (3 lines in the 1.12 block) and new files under `apps/spark-4.2` [read]. No
  Spark 3.5 test suite was rerun.

## Compile [ran]

| Source set | Errors | Cause and fix |
| --- | --- | --- |
| main, 100 files | 0 | None needed; Scala `JavaConverters` calls compile on 2.13. |
| test, first try | 20 | `IcebergLine` not on the classpath (plain Java client jar added); `CommitHookCatalog` extended the OpenHouse Spark catalog (now a `RESTCatalog`); an exclude pattern hid the new `FormatV3TablesServer` (made file-exact). |
| test, after | 0 | |

## Tests, Spark 4.2 over REST only [ran]

The service access log showed 8098 requests, none to the native API.

| Suite | Tests | Failed | Skipped |
| --- | --- | --- | --- |
| `FormatV3MaintenanceTest` (v2 and v3 maintenance) | 39 | 0 | 0 |
| `FormatV3ConcurrencyTest` (v2 and v3 races) | 34 | 28 | 0 |
| `FormatV3VariantJobsTest`, `FormatV3TypesJobsTest` | 8, 2 | 8, 1 | 0 |
| `AppsTest`, `OrphanFilesDeletionSparkAppTest` | 9, 6 | 2, 2 | 0 |
| `Spark42VariantCompactionTest` (new) | 3 | 0 | 0 |
| the other 37 classes (`OperationsTest` 51, `TableStatsCollectionSparkAppTest` 37, `TablesClientTest` 24, ...) | 291 | 0 | 5 |
| total, one full run | 392 | 41 | 5 |
| `Spark42SystemActionHeaderTest` (new, run separately) | 1 | 0 | 0 |
| `FormatV3MaintenanceTest` with `type=rest` instead of `catalog-impl` | 39 | 39 | 0 |

## Cause of each group of failures

- Concurrency, 28: every data check passed (rows, unique and kept row ids, referenced files exist, at most one deletion
  vector per file). Only the last assertion, how the service refused, fails. 22 read `CommitFailedException: Commit
  failed: Requirement failed: branch main has changed` where the test pins the native client's `409 ... Conflict
  detected`. Six (`rowLevelWritePreparedThenSnapshotExpiryRuns` four times, `snapshotExpiryPreparedThenADeleteCommits`
  twice) differ in outcome: the loser's commit is accepted, because expiry does not move `main`, so the REST requirements
  still hold and the service applies the updates on the new metadata; the native client sends whole metadata and gets
  409 [ran; reason inferred]. D50 decides these six are correct.
- Variant and types, 9: the tests pin Spark 3.5's failures. Seven no longer fail (expiry, orphan files, staged files,
  stats, both compactions, layout strategy generation), which is the improvement. One is a real job bug:
  `SortStatsCollectionSparkApp` creates its temporary table by CTAS as format v2, and the service refuses it with
  "variant is not supported until v3". The types test no longer meets the Spark 3.5 failure.
- `type=rest`, 39: `Operations.getCatalog` calls `CatalogUtil.loadCatalog` on `catalog-impl`, which is null (NPE "impl
  class name is null"). `CatalogUtil.buildIcebergCatalog` gives the same result when `catalog-impl` is set [inferred].
- `AppsTest`, 2 (SYSTEM-only lock): jobs declare SYSTEM through `spark.sql.catalog.openhouse.action-type`, which only the
  OpenHouse catalog reads, so over REST the service answers 423. Setting `header.X-OpenHouse-Action-Type=SYSTEM` instead
  passes (`Spark42SystemActionHeaderTest`): the Iceberg REST routes already honor the header.
- Orphan files deletion, 2: the fixture creates a replica table through `openhouse.tableType`, which the REST routes refuse
  ("table type cannot be set through the Iceberg REST routes"). Setup only; the job's replica branch is untested over REST.
- Fixture quirk: `VariantData.read` appends `_pos` for files with deletes, so rows differ after any rewrite; the new test
  compares the first five values. It matters for anyone reusing the helper.

## Compaction of a `variant` table on Spark 4.2 [ran]

The fixture is the one of `FormatV3VariantJobsTest`: v3, Parquet, variant objects, arrays, strings and nulls, deletion
vectors in two Puffin files, 26 live rows. Strict compaction (minimum input files 2, delete file threshold 1) and its
closing validation succeed. It writes a `replace` snapshot, rewrites all 8 data files into fewer with none reused,
leaves no live delete file, and keeps all 26 rows equal on id, variant, timestamp, `_row_id` and
`_last_updated_sequence_number`; deleted ids stay deleted. Production arguments plan nothing (snapshot unchanged,
validation passes). Spark SQL reads `variant`. Expiry, orphan files, staged files, stats and layout generation complete
and validate. Sort stats fails until the CTAS bug is fixed. With the fix and the jobs on Spark 4.2, the case against
turning the `variant` property on by default (D48) is gone for the jobs [inferred].

## OpenHouse-catalog dependencies in the job code

| Dependency | Where [read] | Over REST today |
| --- | --- | --- |
| `catalog-impl` lookup | `Operations.getCatalog` | Works with `catalog-impl=RESTCatalog`; `type=rest` fails [ran]; one-line fix. |
| SYSTEM action | `BaseSparkApp.sessionBuilder` (`action-type`) | Ignored; the `header.X-OpenHouse-Action-Type` header works [ran]; the job must set it. |
| token key | `JobsRegistry` writes `...openhouse.auth-token` | `RESTCatalog` reads `token`: a jobs service change [read]. |
| OpenHouse properties (cluster id, table UUID, policies, retention) | stats, orphan files, retention | Returned by load table; those suites pass [ran]. |
| `ALTER TABLE SET TBLPROPERTIES` | operations, layout, sort stats | Works [ran]. |
| policies, history, table list, orphan directories | scheduler `TablesClient` (native API) | The scheduler runs in OpenHouse's own deployment and needs no REST [inferred]. |
| job state | `StateManager` to the House Tables jobs API (`--storageURL`) from the driver | Not a tables route: the platform must reach House Tables, or the scheduler reports instead [read]. |
| optimizer reporting | batched orphan files and stats apps to the optimizer service | The same: needs reach to that service [read]. |
| Iceberg line detection | `TablesClientFactory` loads `javaclient.IcebergLine` | Absent without the Java client, so column defaults count as legacy; scheduler only [read]. |

Also [ran]: `getCatalog` builds a new `RESTCatalog` per `getTable`; one suite sent 241 `GET /config`.

## Packaging and launch [read]

- Today one uber jar (`:apps:openhouse-spark-apps-1.5_2.12:shadowJar`) serves the jobs scheduler image and the Spark 3.5
  image. `jobs.yaml` has per app: `jar-path`, `dependencies` and Spark properties (`catalog-impl`, `uri`).
- Spark 4.2 needs: an uber jar (the relocation config factored out of `apps-spark-common`, which also brings Hadoop 2.10);
  an image with `iceberg-spark-runtime-4.2`, the 4.2 extensions jar and the uber jar; `jobs.yaml` entries with REST
  `catalog-impl` or `type`, `uri=<tables>/iceberg` and `token`.
- The jobs service refuses two apps of one type (`JobsRegistry.from`), so Spark 3.5 and 4.2 are a choice per deployment,
  unless per-table routing is built in the scheduler and jobs service. The scheduler stays on the 3.5 jar.

## The nine proposed items and who owns them

| Scout item | What | Owner under D50 |
| --- | --- | --- |
| J1 | Land `apps/spark-4.2` | Orchestrator, in the pull request; tests run in CI. |
| J2 | Per-line expectations: refusal wording, the six accepted commits, variant pins, replica fixture | Orchestrator, in the pull request. |
| J3 | `Operations.getCatalog` through `buildIcebergCatalog` | Orchestrator, in the pull request. |
| J4 | SYSTEM action also sent as a request header | Orchestrator, in the pull request. |
| J5 | Sort stats temporary table keeps the source format version | Orchestrator, in the pull request. |
| J6 | Spark 4.2 uber jar and a jar-content test | Mike (published artifact); work item N12. |
| J7 | Token key for REST, Spark 4.2 image, `jobs.yaml` entries | Mike (deployables); work item N13. |
| J8 | Route `variant` tables or a cluster to Spark 4.2 jobs, then the D48 default | Mike; work item N14. |
| J9 | Job state and optimizer reporting without House Tables access | Mike; work item N15. |

## Not done

The uber jar was not built and no image exists. No job jar ran through Livy or `spark-submit`. The scheduler was not run
against Spark 4.2 jobs. The replica-table orphan files path over REST is untested. No Spark 3.5 suite was rerun, so D50
requires it before the pull request lands.
