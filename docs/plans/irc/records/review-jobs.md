# Review: maintenance jobs and the Spark 4.2 jobs line (slug `jobs`)

Pull requests: 130, 135, 137, 141, 143, 145 (125 excluded as instructed). Head `81b4cd4d`, base `bb8929e1`.
Read-only review; nothing built or run. All "before" references are `bb8929e1:apps/spark/src/main/java/com/linkedin/openhouse/jobs/...`
(the shared sources then lived in `apps/spark` and were compiled by both `apps/spark` (Spark 3.1 / Iceberg 1.2) and
`apps/spark-3.5` (via `srcDirs`)); "after" references are `apps/spark-3.5/src/shared/main/java/com/linkedin/openhouse/jobs/...`.

## 1. Bottom line

The job code changes are small, readable and mostly sound on the Spark 3.5 / OpenHouse-catalog path; the production
diff against LinkedIn main is confined to five files (Operations, BaseSparkApp, SortStatsCollectionSparkApp,
TableStatsCollectorUtil, AppConstants) plus TablesClientFactory (#97, outside this assignment but in this area). Every
other job class moved with content identical (R100 on 71 of 77 main-source renames; verified with `git diff -M --stat`,
0 changed lines). The Spark 4.2 "jobs line" is a test-only project: the same sources compiled a second time, with no
jar, image, jobs.yaml entry or scheduler routing, so nothing about it can affect a deployment, and nothing on it can be
deployed either.

What matters most:

1. **Four default-on behavior changes in what LinkedIn's Spark 3.5 jobs do**, none gated: (a) one catalog per job run,
   shared by the batched apps' threads and now closed in `Operations.close()` (#141); (b) retention with backup emits a
   new WARN line and a new metric `retention_backup_delete_file_count` when expired partitions carry delete files
   (#141); (c) partition stats label values by the partition struct's own fields, which changes labels and the number
   of entries for multi-spec tables (#137); (d) sort stats collection on the 1.5.2 line now succeeds via a new
   reflective fallback where it used to fail, and writes `sort-compression-rate` (#145); plus (e) sort stats now loads
   the source table once more through a Java catalog on every run (#135). All are SMALL to gate or revert. All are
   defensible; (a), (c), (d) are fixes. None is documented as a behavior change for upstream in code.
2. **The scheduler's capability detection is dead code in its only production caller.** `TablesClientFactory` is used
   only by `JobsScheduler`, whose image runs the apps uber jar; that jar contains neither Iceberg (shadow excludes
   `com.linkedin.iceberg::`) nor `IcebergLine` (it is in `openhouse-java-runtime`, compile-only). So the scheduler always
   takes the fallback: it sends `column-defaults` on both lines, exactly as on LinkedIn main, and logs a WARN at every
   start. On the default (1.12) line this contradicts the Spark catalogs, which do not declare it, so the scheduler can
   list tables its jobs are then refused on. The only test runs on a classpath that has `IcebergLine`, so the fallback
   path that production takes is never exercised. Confirmed by reading.
3. **The sort stats 1.5.2 fallback (#145) is clever, reflective and fragile**: it walks every `ParserInterface`-typed
   field of the parser chain with `setAccessible(true)` and runs `SparkActions.rewriteDataFiles` instead of the
   procedure. It is functionally equivalent for the options the app passes, but #145 itself pins that on a session
   configured as jobs.yaml configures it, the job still fails earlier (copy from `spark_catalog.<table>` with
   TABLE_OR_VIEW_NOT_FOUND), so the fix is unreachable on the reference deployment recipe. A one-line jobs.yaml
   extension reorder was the alternative and was not taken.
4. **Cruft**: private work-item and decision ids in production comments (X9, D46, D11a, X5); test-only state and
   accessors in `Operations` (`catalogsBuilt` counter, `currentCatalog()`); two implementations of "is this the REST
   catalog" (`Operations.isRestCatalog` and test `JobsLine.restCatalog`); a `String.join` trick against shadow
   relocation that the shadow config already makes unnecessary; `"PUFFIN".equals(format.name())` to straddle lines;
   same-named per-line test helper copies selected by source-set tricks; the `src/shared` root the build file itself
   says should be folded into `src/main`.
5. **Tests are strong where it matters**: the concurrency races (#130) are real, deterministic and assert that the
   raced attempt itself was refused by the service (or is declared accepted), so they cannot pass trivially. Weak
   spots: the X9 metric counts are asserted only as `> 0`, equality deletes not covered; the scheduler fallback is
   untested; `SystemActionDeclarationTest` checks session conf, not the headers on the wire.

Packaging (outside this assignment's pull requests, flagged for the build reviewer): the Spark 3.1 apps jar
`openhouse-spark-apps_2.12` is deleted (#118); the scheduler image now runs `openhouse-spark-apps-1.5_2.12` (#118); the
3.5 apps jar now ships `log4j2.properties` (#113, commit 0801b209); on the default build the jar is Java 17 bytecode
built against the Iceberg 1.12 fork and its newer relocated Jackson (#118). These are FOUNDATION-level choices of other
pull requests.

## 2. Defects

### D1 SHOULD FIX: the scheduler's Iceberg-line detection can never succeed in production; it always declares `column-defaults`
- What goes wrong: on the default (Iceberg 1.12) build, the Spark catalogs declare no `column-defaults` (D11a), but the
  scheduler does, so the tables service lists to the scheduler tables that require the capability, and the jobs it
  launches on them are then refused (422 CLIENT_CAPABILITY_MISSING per IcebergLine's javadoc). The javadoc claims the
  opposite: "so it sees exactly the tables they can use".
- Where: `apps/spark-3.5/src/shared/main/java/com/linkedin/openhouse/jobs/client/TablesClientFactory.java:25-27, 36-38,
  65-67, 76-117`. Only caller: `JobsScheduler` (`grep TablesClientFactory apps/` gives only it and its test).
- Why it always falls back: `jobs-scheduler.Dockerfile` runs `java -cp openhouse-spark-apps-1.5_2.12.jar
  ...JobsScheduler`; `openhouse.apps-spark-common.gradle` shadowJar has `exclude(dependency('com.linkedin.iceberg::'))`,
  and `IcebergLine` lives in `integrations/java/iceberg-1.5/openhouse-java-runtime`, which the apps project takes
  compile-only (`compileOnly project(openhouseSparkRuntimeModule)`). `Class.forName` throws ClassNotFoundException →
  `undetected` → `log.warn("Iceberg line not detectable ({}); declaring {} as on the legacy line", ...)` → `true`.
- Before/after on the wire: main always sent `X-OpenHouse-Client-Capabilities: column-defaults`
  (`CAPABILITIES = "column-defaults"`); the scheduler now sends the same on both lines. New: one WARN per scheduler JVM.
- Sureness: certain from reading build files and Dockerfile (no jar was listed).
- Fix: either decide the scheduler's capability from build-time configuration (a property baked by the build line, or a
  CLI flag), or drop the detection and keep the constant; add a test that runs `declaresColumnDefaults` with
  `IcebergLine` absent (the `@VisibleForTesting declaresColumnDefaults(String)` seam already allows it).
- Introduced by #97 (not in this assignment); the open finding cited in the assignment is confirmed.

### D2 SHOULD FIX: sort stats fallback depends on reflective field walking of private parser internals
- `SortStatsCollectionSparkApp.java:226-252` (`icebergParserBelow`): takes the FIRST field of any class in the parser's
  hierarchy whose value is a `ParserInterface`, with `field.setAccessible(true)`. If an OpenHouse or Spark parser
  gains a second `ParserInterface` field (e.g., Spark's `AbstractSqlParser` subclasses carrying a fallback), the walk
  may go to the wrong parser; if it finds none it returns null and the job fails as before (acceptable). On JDK 17+
  with strong encapsulation, `setAccessible` on classes in named modules would throw; Spark/OpenHouse classes are on
  the unnamed module, so it works today.
- The fallback runs a different code path from the procedure: `SparkActions.get(spark).rewriteDataFiles(table)
  .options(...).sort(order).execute()` after `Spark3Util.loadIcebergTable`. Same options
  (`target-file-size-bytes`, `rewrite-all`, `partial-progress.enabled`), same sort order, no filter. Differences: no
  procedure cache refresh (the temp table's `data_files` is re-read right after; `SortStatsParserTest
  .theAppsRewriteSortsTheTableOnEitherLine` asserts `data_files` drops from 3 to 1 through SQL, so no staleness in that
  test), no output rows. I found no data-safety difference: it only ever rewrites the job's own temporary table.
- Sureness: likely fragile; works as tested.
- Fix: prefer the deployment-side fix (Iceberg extensions listed last so Iceberg's parser is outermost) after testing
  OpenHouse's SQL commands under that order, or ask OpenHouse's parser to expose its delegate explicitly instead of
  reflection.

### D3 MINOR: the X11 fix is unreachable on the session jobs.yaml configures
- #145 pins (`SortStatsParserTest.onTheJobsYamlSessionTheJobStopsAtItsCopyFromSparkCatalog`) that with jobs.yaml's
  settings the job fails at `select * from spark_catalog.<fqtn>` (`SortStatsCollectionSparkApp.java:150-152`, statement
  unchanged from main) before the rewrite. So on the only recipe in the repository the job fails both before and
  after; the fix only helps deployments that point `spark_catalog` at OpenHouse. Not a regression; the claim "On 1.5.2
  the job now completes" holds only for such deployments. Pre-existing defect left in place, pinned.

### D4 MINOR: retention backups still contain rows deleted before retention (pre-existing, now only signalled)
- `Operations.java:518-560`: the backup copies the expired partitions' data files and writes a manifest of data files
  only; position/equality deletes and deletion vectors are not carried. A restore resurrects deleted rows. This existed
  on LinkedIn main for v2 merge-on-read tables; #141 adds a WARN and a metric and does not change what is backed up
  (decision D46 open). Correctly described in the code comment. Not a regression.

### D5 MINOR: `Operations.close()` swallows catalog-close failures and nulls the catalog; a later `getTable` silently builds a new one
- `Operations.java:816-838, 854-860`: after `close()`, `catalog = null`; a subsequent `getTable` rebuilds a catalog on a
  stopped Spark session's conf (in production nothing calls it after close; `BaseSparkApp.run` is the only constructor
  and closes at the end of the try). Only a test concern (C19 notes `Spark42TypeRestCatalogTest` hit exactly this
  ordering). Fix: fail fast after close.

### Checked and found fine
- Catalog shared across the batched apps' threads (`BatchedTableStatsCollectionSparkApp.java:110-127`,
  `BatchedOrphanFilesDeletionSparkApp.java:126-143`): `getCatalog()` is `synchronized`; `OpenHouseCatalog` holds one
  ApiClient/WebClient, `HadoopFileIO` and API facades, all thread-safe for concurrent `loadTable`. It also removes a
  pre-existing race: `OpenHouseCatalog.initialize` mutates the singleton `TablesApiClientFactory` (`setStrategy`,
  `setSessionId`), which parallel per-load initializations used to race on.
- Closing the catalog while a table is in use: in production every future is awaited before `shutdownPool`, and
  `ops.close()` runs after `runInner` and `runValidations`. On the interrupt path, workers can outlive the pool; the
  catalog close (metrics reporter only on OpenHouseCatalog) adds no hazard beyond the `spark.close()` that was there.
- Freshness: `OpenHouseCatalog.loadTable` builds new table operations and refreshes from the service on each load; no
  table cache, so reuse serves no stale metadata. Token: `auth-token` read from properties in `initialize`, both
  before and after; nothing in the jobs updates it mid-run (`grep conf().set` finds only the new REST header).
- `buildCatalog` type-only branch (`Operations.java:872-878`) is taken only when `catalog-impl` is absent and `type`
  is set; on main that configuration failed in `CatalogUtil.loadCatalog(null, ...)`. The `catalog-impl` call is
  unchanged.
- `declareSystemActionOverRest` (`BaseSparkApp.java:84-90`) changes nothing with `catalog-impl =
  com.linkedin.openhouse.spark.OpenHouseCatalog` (jobs.yaml): `isRestCatalog` loads that class without initializing it
  and returns false.
- `createTempTableStatement` for a v1/v2 source returns the byte-identical old statement (pinned by
  `SortStatsTempTableTest:43-51`).
- `transformPartitionRowToColumnData` (`TableStatsCollectorUtil.java:1338-1345`): on single-spec tables the metadata
  table's `partition` struct is the spec's fields in order with the spec field names, so labels are unchanged.
- X9 counting: equality and position delete files deduplicated by path; deletion vectors counted per task (one task
  per data file from `planFiles`); correct for the stated meaning.

## 3. Behavior changes against LinkedIn main

Strict list for the job code (default build and `-PicebergLinkedIn` alike unless noted). "Default" means on with no
configuration change.

| # | What existed | Before (main) | After (head) | PR | Default | Justification in code/notes | Follow-up documented | Upstream as is? | Restore/gate size |
|---|---|---|---|---|---|---|---|---|---|
| B1 | Catalog lifetime in jobs | `Operations.getCatalog()` (main `Operations.java:734-750`) builds a new catalog on every `getTable`/`createTransaction`; `close()` only `spark.close()` | One catalog per `Operations`, built on first use (`Operations.java:854-860`), shared by all threads, closed in `close()` with failures logged at WARN (`816-838`). Effects: one client connection pool per run instead of one per load; the "Establishing connection with OpenHouse service at ..." and "Catalog ... sends configured headers" INFO lines once per run instead of per load; catalog properties read from Spark conf once; service calls unchanged on OpenHouseCatalog | #141 | on | `Operations.java:83-91` javadoc; merge message "jobs behave as before" with freshness test `OperationsCatalogTest` | #141 "Known gaps": pool still lives until JVM exit | Yes, as a fix, minus the test-only counter | SMALL |
| B2 | Retention with backup on tables with deletes | silent | WARN "Retention backup of table {}: {} of {} expired data files carry deletes (...)" plus metric `retention_backup_delete_file_count` {table_name, delete_kind} per kind with count > 0 (`Operations.java:549, 563-623`; `AppConstants.java:25-31`) | #141 | on (backup-enabled tables whose expired partitions carry deletes; on LinkedIn v2 MoR tables today) | `Operations.java:563-570` (cites X9, D46) | D46 undecided | Yes, after removing private ids | SMALL |
| B3 | Partition stats labels (`CommitEventTablePartitions`, `CommitEventTablePartitionStats` `partitionData`) | values labelled by position with the current spec's field names, only as many as the current spec has | labelled by the metadata table partition struct's own field names, one entry per union field; null values (fields of other specs) skipped with a WARN per value "Null partition value for column" (`TableStatsCollectorUtil.java:1324-1345`) | #137 | on, observable only on tables with more than one spec | javadoc `1324-1331`; merge message: "Behaviour changes: partition stats labels on tables with more than one spec, now correct" | no | Yes (bug fix); the per-value WARN on evolved tables is log noise | SMALL |
| B4 | Sort stats on the 1.5.2 line with Iceberg extensions before OpenHouse's | job fails ("Cannot parse order: parser is not an Iceberg ExtendedParser"), temp table dropped, no property | finds Iceberg's parser below OpenHouse's by reflection, runs `SparkActions.rewriteDataFiles` with the same options and order, sets `sort-compression-rate`, logs one INFO naming the parsers (`SortStatsCollectionSparkApp.java:171-200, 226-252`) | #145 | on (only where `spark_catalog` resolves the source; not on jobs.yaml's session, D3) | javadoc `160-170`; merge message | merge message names the jobs.yaml alternative not taken | Doubtful: reviewers would prefer fixing parser order or exposing the delegate over reflection | SMALL |
| B5 | Sort stats extra table load | the app touched the table only through Spark SQL | `ops.getTable(fqtn)` (`SortStatsCollectionSparkApp.java:88-96`) builds the job's Java catalog and sends one more table GET per run, and casts to `HasTableOperations` to read the format version | #135 | on, every line | merge message "(the app loads the source table once more to read its version)" | no | Yes, though `source.properties().get("format-version")`-free alternatives exist (`TableUtil.formatVersion` on 1.12 only) | SMALL |
| B6 | Sort stats on 1.12 / Spark 4.2 | procedure call | one extra in-memory `ExtendedParser.parseSortOrder` probe, then the identical SQL call (`205-213`) | #145 | on | merge message | n/a | Yes | n/a (no observable change) |
| B7 | Scheduler client capability header | `column-defaults` always (main `TablesClientFactory`, `CAPABILITIES`) | `column-defaults` always in the scheduler image (fallback), plus one WARN per start; on a classpath with `IcebergLine` on the 1.12 line, no header (`TablesClientFactory.java:65-117`) | #97 (not in this assignment) | on (WARN) | javadoc claims parity with the catalogs, false for the scheduler (D1) | no | No, as written | SMALL |
| B8 | `Operations.getCatalog` with no `catalog-impl` and a `type` | failed (`CatalogUtil.loadCatalog(null, ...)`) | builds `CatalogUtil.buildIcebergCatalog` (`872-878`) | #135 | only for configs that failed before | merge message | n/a | Yes | n/a |
| B9 | SYSTEM declaration | `action-type` property only | plus `spark.sql.catalog.openhouse.header.X-OpenHouse-Action-Type=SYSTEM` only when the catalog is a RESTCatalog (`BaseSparkApp.java:72-90`) | #135 | off for OpenHouse-catalog sessions (checked) | javadoc and test | n/a | Yes | n/a |
| B10 | Source location | `apps/spark/src/main/java` compiled by two projects | `apps/spark-3.5/src/shared/main/java`; 71 of 77 main renames R100, the others are B1-B9 | #110 (not in assignment) | n/a | build.gradle comment says fold into src/main | yes, in build.gradle | Yes, if folded | SMALL |
| B11 | Apps uber jar and scheduler image | `openhouse-spark-apps_2.12` (Spark 3.1, Iceberg 1.2) existed and the scheduler image ran it; the 3.5 jar had no `log4j2.properties` | 3.1 jar deleted; scheduler runs `openhouse-spark-apps-1.5_2.12`; 3.5 jar ships `log4j2.properties` (`build.gradle:72-77`); default-build jar is Java 17 bytecode and built against the Iceberg 1.12 fork with its newer relocated Jackson; `META-INF/versions/21/` excluded | #110, #113, #118 (not in assignment) | on | build files comments | partly | Needs its own justification upstream | FOUNDATION (the default-line switch underlies most of the effort) |

Files of this area that existed at `bb8929e1` and changed, each accounted for:
- `apps/spark/build.gradle` (deleted, #118): B11. `apps/spark-3.5/build.gradle` (modified): source roots (#110), log4j2
  resources (#113), iceberg-orc/parquet excludes on the 1.12 line (#118), test heap 1 GB and `openhouse.iceberg.line`
  system property (#143, #125): test-only except the source roots/resources (B10, B11).
- `buildSrc/.../openhouse.apps-spark-common.gradle`: one shadow exclude `META-INF/versions/21/` (B11; no effect on
  the reference line).
- `infra/recipes/docker-compose/oh-hadoop-spark/jobs.yaml`: drops `spark.jars.packages` for the 3.1 Iceberg runtime
  (#118); recipe only.
- Main sources: B1-B9 above; all other renamed main files R100 (checked by stat).
- Renamed tests with edits (R097 `BucketBehaviorTest`, `TablesClientTest`, `AppsTest`; R093
  `DataLayoutStrategyGeneratorSparkAppTest`; R089 `TableStatsCollectorUtilTest`; R061
  `OrphanFilesDeletionSparkAppTest`) and `SparkMoRFunctionalTest` (modified): test-only; read the diffs of
  `OrphanFilesDeletionSparkAppTest` (adds a native-API replica create for the REST line and an assertion that the table
  is a replica: strengthened) and the C19 closes; no assertion weakened that I saw.

## 4. Cruft

Private ids and history in production code:
- `AppConstants.java:25` "(work item X9, decision D46)"; `Operations.java:564-566` "work item X9", "decision D46, not
  decided"; `TablesClientFactory.java:77` "decision D11a"; `apps/spark-3.5/build.gradle:82-83` "work item X5"; 14 test
  files cite work items, decisions or pull request numbers. Remove or replace with the reason.
- `apps/spark-3.5/build.gradle:64-66`: "src/shared holds the sources the Iceberg 1.2 / Spark 3.1 apps compiled as well
  ... can be folded into src/main and src/test": narrates history; do the fold (the Spark 4.2 build would then point at
  `src/main` and `src/test`).

Test seams in production classes:
- `Operations.java:83-84, 841-852`: `AtomicInteger catalogsBuilt`, `catalogsBuilt()`, `currentCatalog()` exist only for
  `OperationsCatalogTest`. A test can count catalogs with a counting `catalog-impl` instead.
- `SortStatsCollectionSparkApp`: `SORT_ORDER`, `createTempTableStatement`, `rewriteSorted`, `sessionParsesSortOrder`,
  `icebergParserBelow`, `sortOrder` all `@VisibleForTesting`; acceptable but the class grew from 138 to 284 lines for a
  parser-order problem.
- `TablesClientFactory.declaresColumnDefaults(String)` `@VisibleForTesting` and the static holder class.
- Two `VisibleForTesting` annotations from different packages (`com.google.common...` in BaseSparkApp and SortStats,
  Iceberg's relocated Guava in Operations).

Two implementations of one thing:
- `Operations.isRestCatalog` (`887-907`) and test `JobsLine.restCatalog` (`apps/spark-3.5/src/test/.../JobsLine.java:41-55`)
  are the same logic; the test should call the production one (same package).

Line-straddling tricks (cost of the two-line build):
- `"PUFFIN".equals(delete.format().name())` (`Operations.java:583`): avoids `FileFormat.PUFFIN`, absent on 1.5.2. Still
  needed while the reference line compiles this code.
- `TablesClientFactory` reflection on `IcebergLine` by name, joined with `String.join(".", "com", "linkedin", ...)`
  "so the uber jar's shadow relocation ... leaves it" (`36-38`): the relocation rule already excludes
  `com.linkedin.openhouse.**` (`openhouse.apps-spark-common.gradle`), so the join is unnecessary; the reflection itself
  never succeeds in production (D1). Remove or replace.
- Per-line same-named test helpers: `src/test-iceberg-1.5` vs `src/test-iceberg-1.12` (`IcebergFiles`, `VariantData`)
  chosen in `build.gradle:84-85`; `apps/spark-4.2/build.gradle` excludes two 3.5 test files by path and supplies
  same-named replacements (`FormatV3TablesServer`, `CommitHookCatalog`, `OpenHouseSparkITest`). Works, but is
  invisible to an IDE and to a reader of the shared tests. An interface plus per-line implementations would be cleaner.
- `SortStatsCollectionSparkApp` fallback (D2) exists only for LinkedIn's 1.5.2 parser behavior.

Accretion:
- `Operations` (now ~1050 lines) gained catalog lifecycle, REST detection, and the X9 reporter; the reporter and the
  catalog holder could be separate small classes.
- `apps/spark-4.2` is a whole Gradle project whose only output is a test task (no jar, no publish); its 190-line build
  re-declares the apps-spark-common dependency list by hand. Acceptable as a CI check; it will drift from
  `openhouse.apps-spark-common.gradle`.

## 5. Tests

- **Concurrency (#130)**: real races. `CommitHookCatalog.HookedOperations.doCommit` runs the armed step after Iceberg
  built and validated the commit on base N and before the PUT; the step runs the winner to completion on another
  thread (`FormatV3ConcurrencyTest.java:267-323`). `race` asserts the loser actually reached a commit attempt
  (`stillArmed` false), and the per-case check (`484-495`) asserts the raced attempt's own outcome: "committed" only if
  the winner did not move the metadata, otherwise a service refusal (`CommitFailedException: 409 ... Conflict detected`
  natively, `Requirement failed:` over REST), or a declared acceptance. These cannot pass trivially. Caveat: the
  service's internal window (base check vs House Tables CAS) is not raced, as the merge message says.
- **Declared line difference**: 6 cases where, over REST, a commit built before a snapshot expiry is accepted while the
  native path refuses and retries (`racedWithExpiry`, `516-521`). The stated reason (expiry does not move `main`, so the
  REST requirement holds) is sound; the outcome is checked as serial-equivalent with every referenced file present.
  It is a real semantic difference between the native and REST commit paths of the service (server area).
- **X9 (#141)**: `FormatV3MaintenanceTest:666-675` asserts the metric with `longThat(n -> n > 0)` and the kind; counts
  are not pinned, equality deletes are not exercised, the WARN text is not asserted. A counting bug (for example DVs
  counted per Puffin file) would pass.
- **N16 (#141)**: `OperationsCatalogTest` pins one catalog for 300 loads via the production seam, freshness after other
  clients' commits, and close. Good, but depends on B1's test-only counter.
- **Scheduler capability (#97)**: `TablesClientTest:91` asserts `declaresColumnDefaults() ==
  IcebergLine.current().usesReadBridge()` on the test classpath, which has `IcebergLine`; the production classpath does
  not (D1). The claim "it sees exactly the tables they can use" has no test that would fail.
- **SystemActionDeclarationTest (#135)**: asserts only session conf before/after a direct call; nothing checks the
  header that a REST request carries. On the Spark 3.5 line it asserts "nothing added", which is the right pin.
- **Sort stats (#145)**: `SortStatsParserTest` asserts which path each line takes, the parsed order, one sorted file,
  same rows; `theTestSessionHasTheExtensionsAndCatalogsJobsYamlGivesAJob` ties the fixture to jobs.yaml's extension
  order. The 1.5.2 path runs only under `-PicebergLinkedIn` (88-91 skips there for v3 cases); the merge message reports
  one local failure on that line in an untouched test (timing-dependent 409 in strict compaction) turned into a work
  item: a flaky strict assertion now lives in the suite.
- **Partition labels (#137)**: a failing-first test on v2 and v3 and a unit test comparing both labelling paths on a
  single-spec table. The new per-null-value WARN on evolved tables is not considered.
- **C19 (#143)**: test-only; the 1 GB heap is a margin, not a fix; the merge message itself lists fixtures still leaking
  Operations. No heap assertion exists.
- **Skips**: v3 cases `assumeTrue` off on the 1.5.2 line (`FormatV3Tables.java:171`, `FormatV3TypesJobsTest:98`,
  `FormatV3VariantJobsTest:124`); `FormatV3MaintenanceTest:108-113` guards against silently skipping on the default line
  by requiring `openhouse.iceberg.line`. Good.
- **Pins of accepted behavior**: dangling deletion vector after DELETE racing retention (X13) and orphan removal with TTL
  0 deleting in-flight files are pinned as "expected"; both pins are correct descriptions of Iceberg behavior and do
  not hide an OpenHouse defect, though "strict compaction leaves equality delete files that apply to no live data file"
  (#137 known gaps) is a metadata leftover nobody owns.

## 6. Per pull request

| PR | "Leaves true" holds? | Why |
|---|---|---|
| 130 (X10 concurrency) | yes | Tests only (no main file in its diff); races are constructed and the refused attempt is asserted. |
| 135 (N11) | partly | Same sources compiled twice: true (`apps/spark-4.2/build.gradle` srcDirs). "Three changes in shared job code ... leave the Spark 3.5 jobs as they were": true for the type-only catalog and the SYSTEM header; the sort stats change adds one table load and catalog build per run on every line (B5), which the message mentions but calls unchanged behavior. Nothing deployable exists for 4.2 (stated as known gap). |
| 137 (X10 rest, X15) | yes | Only production change is the label fix; single-spec unchanged by reading. The extra WARN per null value on multi-spec tables is unmentioned. |
| 141 (N16, X9) | yes | One catalog per run, synchronized, closed with WARN on failure; X9 is a log plus metric and changes nothing backed up. "Only BaseSparkApp.run constructs Operations" confirmed by grep. Comments cite private ids. |
| 143 (C19) | yes | Test and build configuration only (`git diff --stat` shows no main source). |
| 145 (X11, X12, X13) | partly | The fallback works as tested, but "On 1.5.2 the job now completes" holds only where `spark_catalog` points at OpenHouse; on jobs.yaml's session it still fails earlier (pinned by its own test). X12, X13 are tests only. |

## 7. For the clean re-implementation

Keep:
1. One catalog per `Operations` instance, built lazily, thread-safe, closed with the instance (B1). Commit it alone, as
   a behavior-preserving resource fix, with a counting test catalog instead of a production counter.
2. `Operations` able to build a catalog named by `type` as well as `catalog-impl`.
3. A REST-catalog SYSTEM declaration via `header.<name>` when the catalog is a RESTCatalog; nothing added otherwise.
4. Partition stats labelled by the partition struct's own field names (and skip, without per-value WARN, fields that do
   not belong to the file's spec).
5. Sort stats temporary table inheriting a v3 source's format version and write format; v2 statement unchanged.
6. A retention signal for delete files in backups (metric plus one log line) until D46 is decided, with exact counts
   tested.
7. The concurrency harness (commit hook in a test-only catalog) and the per-shape job tests (branches/tags, evolved
   specs, equality deletes) on v2 and v3.
8. One source tree for the jobs (`src/main`, `src/test`), compiled by a Spark 4.2 test project if wanted.

Do not carry over:
- The reflective parser walk in sort stats; fix the extension order (or have OpenHouse's parser expose its delegate)
  and test OpenHouse SQL under it.
- `IcebergLine` reflection in `TablesClientFactory`; decide the scheduler's capabilities by configuration.
- Test-only counters/accessors in `Operations`; `JobsLine.restCatalog` duplicate; private ids in comments; the
  `String.join` relocation dodge; `src/shared`.
- Same-named per-line test classes selected by source-set excludes; use an interface with per-line implementations.

Commit sequence for upstream:
1. Fold `src/shared` into `src/main`/`src/test` (pure move, R100).
2. `Operations`: cache one catalog per instance, close it on close (with a test catalog that counts initializations).
3. `Operations`: build a catalog from `type` when no `catalog-impl` is given.
4. `TableStatsCollectorUtil`: label partition values by the struct's field names (test on a multi-spec table).
5. Retention: metric and log for delete files in backed-up partitions (exact-count tests on v2 position deletes).
6. Sort stats: format-version-aware temp table (v3 only; behavior-adding, after v3 support upstream).
7. `BaseSparkApp`: SYSTEM header for RESTCatalog sessions.
8. Tests: per-shape and concurrency suites, then the Spark 4.2 test project.
9. Separately, with the deployment owners: sort stats on 1.5.2 (extension order) and the scheduler's capabilities.

## 8. Not checked

- No build, jar listing or run: uber jar contents are inferred from the shadow configuration, not from a built jar.
- Pull request 125 (excluded), and the packaging pull requests 110, 113, 118 beyond what the jobs build files show.
- The server side of the declared REST-versus-native difference after expiry, and the House Tables CAS window.
- `libs/datalayout/build.gradle` changes (25 lines, not jobs code) and the Spark 4.2 test service (`JobsTestService`,
  `JobsTestServiceProcess`) beyond its build wiring.
- The notes under `docs/plans/irc/` were not read; claims were checked against merge messages and code only.
- The full bodies of `FormatV3ShapeJobsTestBase` (619 lines) and `FormatV3ConcurrencyTest` cases beyond the race
  harness and its outcome checks were sampled, not read line by line.
