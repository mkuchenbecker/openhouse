# Review: scan-planning (PRs 127, 131, 132)

Reviewer slug `scan-planning`. Checkout `/home/user/oh-review` at `81b4cd4d`. Merges: `91e175c4` (#127),
`1dd485d3` (#131), `9f9561e6` (#132). Reference: Iceberg fork `origin/openhouse-1.12.0` (`RESTTableScan`,
`CatalogHandlers.planTableScan`, `PlanTableScanRequest(Parser)`, `TableScanResponseParser`, `RESTFileScanTaskParser`,
`ContentFileParser`, `RESTSessionCatalog.restTableForScanPlanning`, `open-api/rest-catalog-open-api.yaml`).

## 1. Bottom line

The planning core is small and sound for format v2. `IcebergRestScanPlanner` builds the same Iceberg scan a client
would build and serializes it with Iceberg's own parsers. I found no case where a v2 plan returns fewer data files or
drops a delete file compared with client planning. Delete files are attached on the server by Iceberg's own
`DeleteFileIndex`, using the server's sequence numbers, so the client does not need sequence numbers to apply them.
The server never sends `scan-planning-mode`, so no default client changes behaviour. Nothing outside the IRC code path
changed behaviour. What matters most:

1. **Security / privilege widening (SHOULD FIX, needs an owner decision).** The plan answer holds per-file
   lower and upper bounds (data values), partition values and file paths. The service reads them from manifests with
   its own cluster-wide storage credentials (D45 says S3/ADLS keys are cluster-wide, and HDFS uses the service's own
   token). The only check is `GET_TABLE_METADATA`, the privilege of a metadata.json load. Natively, reading manifest
   contents needs storage access the caller holds. So a principal who has the metadata privilege but no storage read
   can now read column values through this route. `Privileges.SELECT` exists, but nothing uses it.
2. **The merge message's audit claim is false (SHOULD FIX).** #127 says "A plan or credentials call emits the load's
   READ audit event", and the javadoc of `IcebergRestApiHandler.loadIcebergTable` repeats it. `TableAuditAspect`
   advises `IcebergRestApiHandler.loadTable(..)` and native `getTable(..)` only. `loadIcebergTable` →
   `TablesService.loadTable` is not advised, so a direct POST to /plan reads the table's manifest contents and leaves
   no table audit event. No test checks the claim.
3. **The planning work is unbounded, on by default, and an operator cannot switch it off (SHOULD FIX).** The 10,000
   cap is checked while streaming, which is correct. But manifest reading time, the `DeleteFileIndex`
   (all matching delete files are loaded before the first task) and the shared Iceberg worker pool are not bounded.
   No timeout and no concurrency limit exist, and the request holds a Tomcat thread. A property value of 0 or below
   means "default 10,000", so no setting disables the route. The code comment admits the bound "does not bound the
   time spent reading manifests of a scan whose filter prunes most files".
4. **Unpinned scans plan the server's current main, not the snapshot the client loaded.** This is a client and spec
   property, not a server bug. A 1.12 `RESTTableScan` with no pinned snapshot sends no `snapshot-id`, and the server
   correctly plans the latest main. Spark 4.2 pins its reads, so it is not affected. Plain Java `table.newScan()`
   users see a different snapshot than client planning would give them when the table moved after their load.
   Documented nowhere.
5. Cruft is moderate: private ids throughout the comments (D44, D45, V11c, V11e), a `@ConditionalOnClass` that guards
   nothing, a cluster property read by `@Value` in a controller instead of `ClusterProperties`, and a benchmark
   (`IrcScanPlanCostTest`) running in the unit suite.

## 2. Defects

### D-1 SHOULD FIX (security; owner decision) — plan route exposes manifest contents under the metadata privilege
- What goes wrong: a principal with `GET_TABLE_METADATA` gets every planned data file's `lower-bounds`,
  `upper-bounds` (with `stats-fields`), partition values, paths, record counts, and the delete files. It needs no
  storage access, because the service reads the manifests with its own credentials.
- Where: `services/tables/src/iceberg-1.12/java/com/linkedin/openhouse/tables/api/irc/IcebergRestScanController.java:84`
  `@Secured(value = Privileges.Privilege.GET_TABLE_METADATA)` and line 92 `handler.loadIcebergTable(...)`. The planning
  runs on `table.io()` of the service-side table (`IcebergRestScanPlanner.java:100` `scan.planFiles()`).
- Natively: the metadata load returns metadata.json only. Manifest contents are read by the client with its own
  storage identity. `Privileges.SELECT` (`services/tables/src/main/java/com/linkedin/openhouse/tables/authorization/Privileges.java:19`)
  exists at `bb8929e1` and is unused.
- How sure: certain about what the route returns and which check it runs. Whether OpenHouse deployments grant
  `GET_TABLE_METADATA` more widely than storage read is something I cannot tell from the code.
- Smallest fix: decide the privilege (for example also require a storage-level read check, or `SELECT`, or a new
  privilege). Until then, gate the route behind a property that is off by default. Add a test where the metadata
  privilege is allowed and storage read is denied.

### D-2 SHOULD FIX — plan and credentials calls are not audited, contrary to the merge message and the javadoc
- Where: `services/tables/src/main/java/com/linkedin/openhouse/tables/api/irc/IcebergRestApiHandler.java:255-265`.
  The javadoc reads: "the same validation and the same {@link TablesService#loadTable} call, so the same checks in the
  same order with the same failures (and the same read audit event)".
- `services/tables/src/main/java/com/linkedin/openhouse/tables/audit/TableAuditAspect.java:116-119`, the pointcut:
  `execution(com.linkedin.openhouse.tables.api.irc.IcebergRestLoadedTable com.linkedin.openhouse.tables.api.irc.IcebergRestApiHandler.loadTable(..))`.
  No pointcut covers `loadIcebergTable` or `TablesService.loadTable`.
- Effect: a caller who POSTs /plan without loading first reads the table's file list and statistics, and no table
  audit event records it. Only the generic service request audit would see the request, and I did not check that.
- How sure: certain from reading. No test asserts an audit event for either route.
- Fix: add an advice on `loadIcebergTable` (or move the audit to `TablesService.loadTable`), and add a test.

### D-3 SHOULD FIX — planning cost unbounded before the cap; no way to disable the route
- `IcebergRestScanPlanner.java:60-63`: "it does not bound the time spent reading manifests of a scan whose filter
  prunes most files".
- `IcebergRestScanPlanner.java:83-85`: `return configured > 0 ? configured : DEFAULT_MAX_FILE_SCAN_TASKS;`. Setting 0
  or a negative value cannot disable the route.
- Not bounded:
  - Manifest reads: a filter matching nothing reads every manifest.
  - `DeleteFileIndex`: Iceberg loads every delete file that matches the filter before it emits the first task.
  - The Iceberg JVM-wide worker pool (`ThreadPools.getWorkerPool()`) that `DataTableScan` plans with. The
    service's other Iceberg work shares it.
  - Concurrent requests.
  - Wall-clock time, so one request can hold a servlet thread for as long as the manifests take.
  - The answer: built as a full Jackson tree (`IcebergRestJson.ok` → `MAPPER.valueToTree`) and then as bytes. That is
    three copies at 10,000 tasks; #131 measured about 98 MB of heap with stats on 3 columns, and a wide table with
    `stats-fields` is larger.
  - The request body: read as `byte[]` with no size limit (shared with every IRC route).
- The cap is checked while streaming (`IcebergRestScanPlanner.java:101-105`, which returns at task max+1 and closes
  the iterable), which is correct.
- How sure: certain from reading. The impact under load cannot be told without running.
- Fix: an on/off property (off by default, or at least an "off" value). A time budget checked in the loop. A
  semaphore on concurrent plans. Optionally stream the answer instead of building a tree.

### D-4 MINOR — a branch that moved between load and plan is planned with the snapshot's schema
- `IcebergRestScanPlanner.java:172-179`: with `use-snapshot-schema=false` and a snapshot that no branch currently heads,
  the scan falls back to `scan.useSnapshot(snapshotId)`, which gives the snapshot's schema. The spec says false means
  "the table schema should be used". The client sends false with a branch head it loaded. If another writer commits
  to that branch before the plan arrives, a filter on a column the snapshot's schema lacks gets a 400 that client
  planning would not give.
- Never fewer rows: in Iceberg the projection does not prune files, and a filter that does not bind fails loudly.
- How sure: certain from reading; it is a race, so it is rare.
- Fix: in that branch, project `table.schema()` (as `lateColumnProjection` already does for the other flag).

### D-5 MINOR — any IllegalArgumentException during planning or serialization answers 400
- `IcebergRestErrors.java:114-117` maps every `IllegalArgumentException` to 400 `BadRequestException`.
- Iceberg's serializer `Preconditions.checkArgument` failures are server faults, but they report as the client's
  fault. Examples: "Cannot serialize scan task with unknown spec", and "delete file missing from delete files".
- An unknown snapshot id is correctly a 400.
- Fix: catch around the serialization (`IcebergRestJson.ok`) and answer 500.

### D-6 MINOR — every refused plan logs at WARN
- `IcebergRestScanController.java:96-103`. A client retrying a table above the cap floods the log. Use a counter
  metric or DEBUG.

Lines of attack that found nothing:
- Fewer files or omitted delete files on v2: none found. The scan is Iceberg's own `DataTableScan` or
  `BaseIncrementalAppendScan`. Deletes are attached by `DeleteFileIndex` on the server's metadata, and the answer is
  written by `TableScanResponseParser.serializeScanTasks`. That parser fails rather than drop a delete file
  (checkArgument "delete file missing").
- Residuals: written by `RESTFileScanTaskParser` and re-bound by the client to its own specs. Same as `CatalogHandlers`.
- Partition spec evolution: the answer uses `table.specs()`. The only failure is loud: a spec the client lacks, when
  the server plans a newer main.
- Sequence-number weakness on v2: the reader does not need sequence numbers.
  - Equality and position deletes are associated on the server.
  - The Spark v2 merge-on-read DELETE run twice on the same files (file-scoped deletes rewritten) is compared with
    client planning, including the `.files` and snapshot summaries
    (`SparkIrcServerPlanningWritesTest.deleteMergeOnReadTwiceOnTheSameFiles`).
  - Not run: maintenance procedures (rewrite_data_files and similar) through a server-planning catalog. See section 8.
- The server telling a client to plan on the server: none. No `scan-planning-mode` key is sent in config or load
  answers (checked: the only mentions in `src` are in messages and tests). `RESTSessionCatalog.restTableForScanPlanning`
  takes the mode only from the load answer's `config`, falling back to the client's own property.
- The credentials route: no 1.12 client calls it unless a `*.refresh-credentials-endpoint` is configured. The server
  sends none. A client that did call it would fail loudly (`VendedCredentialsProvider`: "Invalid S3 Credentials:
  empty"; GCS likewise). It cannot be read as "no access needed".
- Stateless choice: GET and DELETE `plan/{id}` and POST `tasks` stay 406 and unlisted. The 1.12 client calls fetch only
  on `submitted`, tasks only with `plan-tasks`, and cancel only if `V1_CANCEL_TABLE_SCAN_PLAN` is listed
  (`RESTTableScan.java:216-219, 351-353, 378`). None of these occurs. The `completed` answer's random plan id is used
  only for cancel and credentials, so it is harmless.
- Request validation: half-incremental, snapshot plus incremental, and negative min-rows are rejected by
  `PlanTableScanRequest.validate()` and surface as 400 through `IcebergRestJson.read`.
- Authorization order: the load checks (lock, privilege, system lock, capability gate) run before the body is parsed
  and before any manifest is read. metadata.json is read before the privilege check, as on the native load path.
- Snapshot visibility: OpenHouse has no per-snapshot or per-branch authorization, so a caller who can load the table
  can plan any snapshot, exactly as client planning allows.

## 3. Behavior changes against LinkedIn main

Every IRC route is new since `bb8929e1`. `IcebergRestCatalogController`, `IcebergRestApiHandler`,
`TableDtoWithMetadata`, `findWithMetadataById` and `TablesService.loadTable` were added by #88 and #94, outside my
assignment. My three PRs touched these files that existed at `bb8929e1`:

| File | Before (bb8929e1) | After | PR | On by default | Justification / follow-up | Upstream accept? | Size |
|---|---|---|---|---|---|---|---|
| `services/tables/build.gradle` (+19 lines, near line 104) | the tables jar has no `src/iceberg-1.12` source set | without `-PicebergLinkedIn`, adds `src/iceberg-1.12/java` and its test dir to the main and test source sets, so the default jar contains `IcebergRestScanController`, `IcebergRestScanPlanner` (and, from other PRs, the view-register classes) | 127 | yes, in the default build | build comment: "Code and tests that need classes only the Iceberg 1.12 line has" | as a source set, yes; but it exists only because of the two-line build | SMALL |
| `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java:1053-1057` | (the method came after bb8929e1, from #88) | `TableDtoWithMetadata` also carries the `Table` | 127 | n/a | used only by the IRC path (`findWithMetadataById` callers: `TablesServiceImpl.loadTable`, `TableCommitService`). Refactor: no native behavior change, checked by reading its callers | yes | SMALL |
| New route `POST /iceberg/v1/namespaces/{ns}/tables/{t}/plan`, `GET .../credentials` | 406 from the IRC catch-all (itself new since bb8929e1) | served | 127 | yes: no switch | D44/D45 call it a "two-way door: the route can be withdrawn from the endpoint list". There is no property to withdraw it; it needs a code change | new route, so not a behavior change per the brief. Upstream would ask for a switch given D-1 and D-3 | SMALL |

Other files changed in these PRs (tests, the Spark 4.2 itest `build.gradle` adding `testServerPlanning` and
`smokeTestServerPlanning` to `check`, and `tables-test-fixtures-iceberg-1.5/.../IcebergRestRoutesBoundaryTest.java`)
did not exist at `bb8929e1` or are test-only. One exception: `check` now runs the Spark 4.2 suites twice. That is a
CI-time change, but the Spark 4.2 module is new, so it does not change LinkedIn's CI behavior on existing modules.
`git diff --diff-filter=MDR bb8929e1..HEAD -- services/tables/src/iceberg-1.12` is empty, since every file there is new.
The 1.5.2 line: the plan and credentials routes answer 406 through the catch-all `UNSERVED_SPEC_ROUTES`
(`IcebergRestCatalogController.java` near line 148). This is unchanged from before #127 on that line.

## 4. Cruft

Comments citing private ids or narrating history:
- `IcebergRestScanController.java:28-29` ("decision D44", "decision D45") and `:67` ("work item V11c").
- `IcebergRestScanPlanner.java:24` (D44), `:60` (V11c), `:71` ("See the V11c measurement..."), `:90` ("decision of
  V11c"), `:118` (V11e) and `:183` (V11e).
- `services/tables/build.gradle` comment ("D44 and D45").
- `IcebergRestCatalogController` near line 148, the comment naming the served controllers.
- Action: strip the ids and keep the reasons.

Switches that guard nothing, and two-line straddles:
- `IcebergRestScanController.java:58` `@ConditionalOnClass(name = SCAN_API_CLASS)` and `SCAN_API_CLASS` (`:62`). The
  class is compiled only when Iceberg 1.12 is on the classpath (`build.gradle` source set), so the condition is always
  true. Remove both.
- `IcebergRestRoutesBoundaryTest.classPresent(SCAN_API_CLASS)` and the duplicated class-name string are a cost of the
  two-line build. They are needed only while the 1.5.2 line exists.
- `UNSERVED_SPEC_ROUTES` entries for POST plan and GET credentials: on 1.12 they are dead, and on 1.5.2 they are
  needed. A two-line cost.

Configuration:
- `IcebergRestScanController.java:71-76` reads `cluster.tables.irc.scan-planning.max-file-scan-tasks` through `@Value`
  in a controller. Every other `cluster.*` property is in
  `cluster/configs/src/main/java/com/linkedin/openhouse/cluster/configs/ClusterProperties.java`. Move it there.
- The planner references the controller's constant (`IcebergRestScanPlanner.java:155`
  `IcebergRestScanController.MAX_FILE_SCAN_TASKS`), so the dependency runs backwards. Keep the constant with the
  property.
- "Not positive means default" is surprising semantics. See D-3.

Test scaffolding in the unit suite:
- `services/tables/src/iceberg-1.12/test/java/com/linkedin/openhouse/tables/api/irc/IrcScanPlanCostTest.java` (384
  lines) is a measurement harness. It prints `PLAN-COST` lines and builds 1,000- and 10,000-file tables on every
  `:services:tables:test` run. Its few assertions (completed or failed at the bound, and `bound(0) == 10_000`)
  duplicate `IrcScanPlanningBoundTest`. Move it to a benchmark task, and keep one bound assertion.
- The bound property string is redeclared in `IrcScanPlanningBoundTest.java:56` and `SparkIrcPlanBoundTest.java:25`,
  where they could reference the constant (minor).

Duplicated tests:
- `SparkIrcScanPlanningTest` (#127) overlaps with `SparkIrcServerPlanningReadsTest` (#131) and the
  `testServerPlanning` rerun of the whole suite. Three layers assert Spark read parity.

## 5. Tests

- #127 "A plan or credentials call emits the load's READ audit event": no test, and false (D-2).
- #131 "A refusal costs under 1 MB on the serving thread": the cost test prints this; nothing asserts it.
- #131 "planning stops as soon as the bound is passed": no test distinguishes stopping early from collecting
  everything and then refusing. The code does stop early, so the claim holds by reading.
- No test covers a principal with the metadata privilege but no storage access (D-1). The auth test stubs
  `checkAccessDecision` to all-true or all-false, so plan and load are equal by construction.
- Equality deletes: one case only, with the delete written after the data. No test covers an equality delete older
  than a data file (which must not apply), a position delete in a partition-scoped file applied across files, or
  partition spec evolution (data under two specs with a filter on the new partition field). These are Iceberg's own
  code paths, so the risk is low, but the "planned as on the client" claim is pinned only for the shapes tested.
- No test covers an unpinned Java scan racing a commit (section 1, item 4), or a branch moving between load and plan
  (D-4).
- `IrcScanPlanningTest.anIncrementalScanPlansTheAppendsBetweenTwoSnapshots` compares the server with
  `fromSnapshotExclusive`. It pins the spec's reading (exclusive) against Iceberg's reference handler (inclusive). The
  pin matches the spec text (`rest-catalog-open-api.yaml` line 5734: "Starting snapshot ID for an incremental scan
  (exclusive)"), so it is right.
- `minRowsRequestedIsIgnored` pins a deliberate deviation from `CatalogHandlers`. The stated reason (cutting by files
  can return fewer rows after deletes) is correct for the reference handler.
- v3: the refusal tests assert that the table is unchanged after a refused write. This is good, and it is the right
  pin while the spec's scan task carries no sequence number.
- "Declared differences": `ServerPlanning.notApplied` keeps v3 catalogs on client planning in the reruns, with reasons.
  It is acceptable, but the rerun suite therefore does not exercise server planning on v3 at all. That is consistent
  with the refusal.

## 6. Per pull request

| PR | Leaves-true claims hold? | Why |
|---|---|---|
| 127 (V11a, V12a) | partly | Plan, credentials, endpoint listing, no `scan-planning-mode`, and 406 on 1.5.2 all hold. Load checks before the body is parsed: holds. The "load's READ audit event" claim is false (D-2). "No existing route answers differently" holds. |
| 131 (V11b, V11c) | yes, with unpinned parts | The streaming cap is correct, 422 `ScanPlanTooLargeException`, and the client throws without fallback (`RESTTableScan.java:220-221`). The write and read parity tests exist and compare files and snapshots. "A refusal costs under 1 MB" is not asserted. The bound does not cover manifest time or delete-index memory, which the PR admits. |
| 132 (V11e) | yes | The refusal at `formatVersion >= 3` comes before any manifest is read. `lateColumnProjection` is limited to point-in-time scans with use-snapshot-schema=true and with every name in the current schema. v2 plans are unchanged. |

## 7. For the clean re-implementation

Essential design (what must survive):
- One stateless synchronous `POST .../plan` that builds an Iceberg scan from the request and answers `completed` with
  inline `file-scan-tasks` and `delete-files`, serialized by Iceberg's `PlanTableScanResponse`. No plan state, and no
  fetch, tasks or cancel routes listed.
- Request mapping:
  - incremental: start exclusive and end inclusive, per the spec;
  - `snapshot-id` with `use-snapshot-schema=true` → `useSnapshot`;
  - `snapshot-id` with false → the table schema, via `useRef` of the branch heading it, or else a projection of
    `table.schema()` (fixes D-4);
  - `select`, `filter`, `case-sensitive` and `stats-fields` passed straight through;
  - `min-rows-requested` ignored.
- The late-column rule: a point-in-time scan with use-snapshot-schema=true that names current-schema columns missing
  from the snapshot is projected with the current schema, because Spark 4.2 pins with `useSnapshot`.
- Refuse format v3 and above with `failed` and a typed error, until the spec's scan task carries sequence numbers.
- A cap on tasks, checked while streaming, answered `failed` (HTTP 200, error code 422), so the 1.12 client fails
  loudly.
- The load's checks (lock, privilege, system lock, capability gate) before parsing the body. Plus a decided read
  privilege for manifest contents (D-1) and an audit event (D-2).
- An on/off property, a time budget and a concurrency limit for planning.
- Never send `scan-planning-mode`.
- `GET .../credentials` answering an empty list after the load checks.
- Parity tests: two real `RESTCatalog`s (client vs server planning) comparing tasks and rows. Spark DML parity on v2
  comparing the stored files and snapshots.

Do not carry over:
- `@ConditionalOnClass` on a class that only compiles where the class exists.
- The private-id comments.
- The `@Value` property in the controller.
- The cost harness in the unit suite.
- The triple-layered Spark read parity tests (keep the rerun or the focused test, not both).
- The false audit javadoc.

Proposed upstream commit sequence:
1. `TableDtoWithMetadata` carries the loaded `Table` (refactor, no behavior change).
2. Add the cluster property set (`irc.scan-planning.enabled`, default false; `max-file-scan-tasks`; time budget) to
   `ClusterProperties` (no behavior change).
3. Planner class plus unit tests for request mapping (no route yet).
4. The plan route behind the switch, with load checks, the privilege decision and an audit advice. Endpoint listing
   only when enabled. Parity e2e tests.
5. The cap, the time budget and the concurrency limit, answered `failed`. Tests.
6. Refuse format v3 and above. Tests.
7. The late-column projection rule. Tests.
8. The credentials route with an empty list (separately, with its own justification).
9. The Spark 4.2 DML parity suite with server planning.

## 8. Not checked

- I did not run anything (read-only brief). Load or concurrency impact (D-3) and worker-pool contention are reasoned
  about, not measured.
- Spark maintenance procedures (`rewrite_data_files`, `rewrite_position_delete_files`, `expire_snapshots` with a scan)
  through a server-planning catalog. If any of them reads `dataSequenceNumber()` from planned tasks on v2, results
  could differ. Not tested by the PRs, and I did not trace every procedure.
- Whether OpenHouse deployments grant `GET_TABLE_METADATA` without storage read (decides the severity of D-1).
- Whether `ServiceAuditAspect` records these requests generically.
- The rest-compat kit's expected outcomes for the plan routes.
- The per-work records under `docs/plans/irc/records/` for V11. I read only D44 and D45.
