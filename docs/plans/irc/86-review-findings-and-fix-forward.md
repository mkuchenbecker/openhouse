# Review findings and the fix-forward work list

Bottom line: the nine retroactive reviews of 2026-11-27 found 68 distinct defects: 5 BLOCKER, 24 SHOULD FIX and 39
MINOR, of which 7 are inherited from LinkedIn main. The four new blockers are two unauthenticated reads on new Iceberg
REST routes (an empty table commit returns the table's metadata; view load and list return view definitions with no
check), LinkedIn's release workflow tagging and then publishing nothing, and the absence of any recorded baseline of
LinkedIn main behind the claims that behavior is preserved. The inherited blocker is the native directory-reuse data
loss (put to the owner as D35). The work list below has 49 pull-request-sized items (FF-01 to FF-50; FF-39 was folded
into FF-35; FF-47 to FF-50 were added on 2026-11-28) in the order the orchestrator asked: authorization on new routes, then restores of native behavior on the reference line, then the evidence gap,
then release and build, then the stand-in, then cruft, then the remaining defects. Four items are ready because the
orchestrator already decided them (FF-01, FF-02, FF-09, FF-10); most others are ready because S60 decides them; nine
wait on a decision (FF-25 only in part), and FF-03 waits on the scout reading the owner's earlier view decisions.

Sources: `records/review-*.md` (reviewer ids as in `85-behavior-change-ledger.md`: CW, RR, VC, CS, FV, SP, JB, BD, CF).
Ledger rows are `BC-NNN`, in `85-behavior-change-ledger.md`. "[checked by the consolidator]" means the statement was
read in the code at `81b4cd4d` or at `bb8929e1` while writing this file. Certainty is the reviewer's own.

## Status on 2026-11-28

Bottom line: of the 49 items, three are done (FF-10, FF-14, FF-15, by pull request 154) and one is partly done (FF-16); ten
are in review in three open pull requests (152, 155, 156); the rest are not started. Ids are stable; the work list in section 3 is unchanged except
for the four items added at its end (FF-47 to FF-50). The independent review of each pull request is in `records/pr-review-*.md`
and summarized in `91-first-reviewed-merges-result.md`.

| Item | Status | Pull request, evidence |
|---|---|---|
| FF-10 | Done | Pull request 154, merge `7cf347d7` (2026-11-28). The reviewers showed the premise of the item wrong in effect: policy statements on a non-Iceberg wrapper catalog fail at planning on main too, so the resolver change cannot be reached from SQL (ledger BC-007 corrected). The refusal over a REST server that does not advertise policies stays, as a fix (D65, BC-053) |
| FF-14 | Done | Pull request 154. Scheduler files are main's byte for byte; sort stats sends main's statements (BC-019, BC-020, BC-021, RF-046, RF-047; W13 is done with no change on the wire) |
| FF-15 | Done | Pull request 154 (BC-023, RF-053). Side effect BC-054 (recipe and tests lose the INFO configuration); the CI jar check passes silently if `unzip` fails (FF-49) |
| FF-16 | Partly done | Pull request 154: the capabilities module is published and the thin runtime's POM names it (BC-008 in part, BC-055). Remaining: the adapter's capability constant (still read by the opt-in seam), the Java runtime's compile dependency on the module (RF-021, deferred to FF-37), and a classpath acceptance test (a consumer of the thin jar plus the published Java runtime runs a GRANT without `NoClassDefFoundError`). `records/pr-review-154.md` finding F1. No test pins the POM dependency |
| FF-01, FF-02, FF-03 | In review | Pull request 152. The first review found a remaining bypass (`records/pr-review-152.md` F1: a commit whose updates change nothing reads the table past the lock and read checks), being closed |
| FF-09, FF-12, FF-13, FF-18, FF-19, FF-40 | In review | Pull request 156 (`records/restore-native-service-behavior.md`; all CI jobs green on `f2d7f6f6` as the author read them) |
| FF-47 | In review | Pull request 155 (new item, below) |
| FF-11 | Not started | One property for the whole Iceberg REST base; waits on the orchestrator's decision on its default |
| FF-48, FF-49, FF-50 | Not started | New items, below |
| every other item | Not started | |

Work items closed with this: X2 and X3 (pull request 151, merge `afe8d3c8`), which are work items, not FF items.

## 1. Defects

Bottom line: 68 defects in ten areas; inherited ones are marked "inherited" and are not regressions: fixing any of them
changes LinkedIn main's behavior and needs its own justification.

| Severity | Count | Of which inherited |
|---|---|---|
| BLOCKER | 5 | 1 (RF-030) |
| SHOULD FIX | 24 | 2 (RF-004, RF-005) |
| MINOR | 39 | 4 (RF-009, RF-031, RF-048, RF-049) |
| Total | 68 | 7 |

| Area | BLOCKER | SHOULD FIX | MINOR |
|---|---|---|---|
| Iceberg REST table write routes | 1 | 4 | 5 |
| Iceberg REST read, register, metrics | 0 | 0 | 7 |
| Iceberg REST views and capability routes | 1 | 2 | 1 |
| Scan planning | 0 | 3 | 4 |
| Native path and House Tables | 1 (inherited) | 1 | 2 |
| Java client and Spark runtime | 0 | 1 | 5 |
| Format v3 and column defaults | 0 | 3 | 4 |
| Maintenance jobs | 0 | 2 | 3 |
| Build and release | 1 | 4 | 4 |
| CI, fixtures and evidence | 1 | 4 | 4 |

### Iceberg REST table write routes

| Id | Severity | File and line | Statement | Certainty (reviewer) | Fix |
|---|---|---|---|---|---|
| RF-001 | BLOCKER | `services/tables/src/main/java/com/linkedin/openhouse/tables/api/irc/IcebergRestWriteHandler.java:258-273, 346-349` [checked by the consolidator]; `IcebergRestWriteController.java:76-77` has no `@Secured` | A commit with no updates (`{"requirements":[],"updates":[]}`, or updates the builder records as no change) returns the table's full metadata, properties, policies and location to any authenticated principal: `loadBase` runs "with no read checks", `commitOn` returns early, and the write checks in `TableCommitCore.commit` are never reached. The transaction route has the same branch and answers 204, leaking existence | Certain from reading (CW D-1) | FF-01 |
| RF-002 | SHOULD FIX | `IcebergRestWriteHandler.commitOn:337-398`; `services/TableCommitService.java:69-84` | Requirements, update application and validation run before the write-privilege check, so a principal with no access gets the stored UUID, ref snapshot ids, schema, spec and sort-order ids in 409 messages | Certain (CW D-2) | FF-01 |
| RF-003 | SHOULD FIX | `IcebergRestWriteHandler.renameTable:983-1007` | A rename onto a view's name runs the native rename, which fails after five retries leaving five orphan metadata files (about 1.8 s), then maps to 409; any House Tables conflict during a rename, including a real concurrent update, is answered "Table already exists", which a client does not retry | Certain (CW D-3) | FF-08 |
| RF-004 | SHOULD FIX, inherited | `repository/impl/OpenHouseInternalRepositoryImpl.java:192-215`; `OpenHouseInternalTableOperations.java:930-932` | A replace commit has no base check; an append landing between the server's `loadBase` and `replaceTable` is acknowledged and lost. Native RTAS behaves the same; the IRC javadoc (`:83-88`) claims the core's guards refuse it | Likely (CW D-4) | Javadoc now (FF-41); the check, gated to IRC, in FF-46 |
| RF-005 | SHOULD FIX, inherited | `OpenHouseInternalTableOperations.java:152-153, 454, 978-993` | After a commit fails past `failIfRetryUpdate`, the per-JVM 5-minute `CACHE` refuses every later commit on that base on that instance; IRC rebuilds on the server-loaded base, so every client retry is refused until expiry | Likely; needs fault injection (CW D-5) | FF-46 |
| RF-006 | MINOR | `IcebergRestWriteHandler.applyTakingOutThePatch:430`; `IcebergRestErrors.toError` | A view update (`add-view-version`) sent to the table commit route throws `UnsupportedOperationException`, answered 500 and logged at error | Likely (CW D-6) | FF-41 |
| RF-007 | MINOR | `IcebergRestWriteHandler.commitTable:267` | The commit answer reloads the table, so a commit that lands in between is returned as the client's own result | Certain (CW D-7) | FF-41 |
| RF-008 | MINOR | `IcebergRestWriteHandler.commitCreate:487-501` | The directory guards run only when the candidate has a location; today a null location is unreachable only because of format-version rules | Certain (CW D-8) | FF-41 |
| RF-009 | MINOR, inherited | `services/TableCommitCore.java` `checkIfLockPoliciesUpdated`; `services/PolicyPatch.java:121-130` | A policy patch carrying a lock state on a table whose stored policies are null: a table update dereferences null (500); a snapshots commit sets the lock state unchecked. Both are native behavior, now reachable from any plain Iceberg client | Certain (CW D-10, VC D5) | FF-41 (IRC caller only) |
| RF-010 | MINOR | `IcebergRestWriteHandler.applyTakingOutThePatch` (about `:416-443`) | A table that holds a stored `updated.openhouse.policy` (written raw natively) has that stale patch re-applied by every IRC commit, a plain append included. Client-equivalent and pinned by `aStoredKeyIsTreatedAsTheClientTreatsIt` | Certain (VC section 5) | None proposed; documented here |

### Iceberg REST read, register and metrics routes

| Id | Severity | File and line | Statement | Certainty | Fix |
|---|---|---|---|---|---|
| RF-011 | MINOR | `ServiceAuditAspect.auditSuccessfulRequests`; `CachingRequestBodyFilter` | Every IRC request body, the metrics report included (any size, from a principal with read access to one table), is copied unbounded into the service audit | Certain (RR D8) | FF-07 |
| RF-012 | MINOR | `IcebergRestWriteHandler.java:849-855` | The UUID half of the directory-in-use check is dead in production (`user_table_row` has no UUID column), while the stand-in stores UUIDs, so tests run a branch production lacks; the location prefix alone covers the named cases | Certain (RR D2) | FF-36 (remove), FF-32 (stand-in) |
| RF-013 | MINOR | `IcebergRestApiHandler.java:387-403` | The namespace check mixes a case-insensitive table probe with an exact database-list match; `HEAD /namespaces/DB` answers 204 or 404 depending on whether `db` holds a table; untestable on the case-sensitive stand-in | Likely (RR D3) | FF-42 after FF-32 |
| RF-014 | MINOR | `IcebergRestWriteHandler.java:810-846` | The directory-in-use check reads live rows, then soft-deleted rows; a restore between the two reads hides the row from both, and a register or completion can land in its directory | Likely (RR D4) | FF-42 |
| RF-015 | MINOR | `IcebergRestWriteHandler.java:874`, `:568-572` | Register refuses format v3 metadata even where v3 creates are enabled. RR files it as a defect; FV lists it as correct. Both are right in part: D43 decided the refusal, so the defect is only that it is unadvertised and that the shared `create()` keeps a v3 branch register cannot reach | Certain (RR D5; FV section 2) | FF-42 |
| RF-016 | MINOR | `IcebergRestTableFileIo.java:263-271`; `FileIOManager.java:83-95` | A table on an unknown storage type inherits the default storage's `io-impl`; a per-table `io-impl` overrides a client's own; `getStorage` throws (400) for any wrapped FileIO | Certain on the first two; cannot tell on the third (RR D6) | FF-42 |
| RF-017 | MINOR | `IcebergRestWriteHandler.loaded:1086-1093` versus `IcebergRestApiHandler.loadTable:252` | Create, commit and register answers omit the read-bridge config that a load answers | Certain (RR D7) | FF-42 |

### Iceberg REST views and OpenHouse capability routes

| Id | Severity | File and line | Statement | Certainty | Fix |
|---|---|---|---|---|---|
| RF-018 | BLOCKER | `api/irc/IcebergRestViewHandler.java:119` (`loadView`), `:172-173` (`load`: `validateGetView`, then `viewsService.loadView`) [lines checked by the consolidator; the reviewer cited 233-236 and 283-288]; routes carry only `@Secured(AUTHENTICATED_VIEWS_OPS)` | IRC view load and list hand the full view definition (SQL, schema, properties, history) to any authenticated principal with no authorization decision. Natively the view GET is pointer-only (`GetViewResponseBody` "Pointer-only read contract", checked at `bb8929e1`) and the definition sits in a file the caller reads with its own storage credentials. Pinned as correct by `IrcViewRoutesAuthTest.readsMakeNoAuthorizationDecisionAsNatively`. With register-view (pull request 149) a principal with create privilege can bring an old view back and everyone can then read it | Certain from reading; exposure depends on how deployments protect view directories (VC D1) | FF-03, waits for the scout |
| RF-019 | SHOULD FIX | `IcebergRestViewHandler.java:256-262` | A view commit loads metadata and checks requirements (409), applies updates (400) and server-owned fields (400) before `viewsApiHandler.updateView`, where the privilege check and the view audit are; a denied principal gets 409 or 400 instead of 403, and none of these refusals is audited | Certain (VC D3) | FF-02 |
| RF-020 | SHOULD FIX | `IcebergRestViewHandler.java:253-265`; `IcebergRestViewSpelling.apply:105-117` | View replace through IRC has no client-visible compare-and-set: the base sent to the native PUT is the one the server loaded in the same request, so a stale handle overwrites a newer native or IRC version that the native client would have got as 409. Iceberg's REST semantics; declared in 95's known gaps only | Certain (VC D2) | Document in code (FF-31); no protocol fix |
| RF-021 | MINOR | `integrations/java/iceberg-1.5/openhouse-java-runtime/.../OpenHouseRestAdapter.java:92-101` | The comment says the adapter advertises the capabilities "as the tables service's IRC config advertises them"; the value is only `policies` and the service sends `policies,grants,unlock`. Nothing reads the value (`CONFIG_OVERRIDES` is dead, CS) | Certain (VC D4, CS section 4) | FF-16 |

### Scan planning (default line only)

| Id | Severity | File and line | Statement | Certainty | Fix |
|---|---|---|---|---|---|
| RF-022 | SHOULD FIX, needs a decision | `services/tables/src/iceberg-1.12/java/.../IcebergRestScanController.java:84, 92` [checked: `@Secured(GET_TABLE_METADATA)`]; `IcebergRestScanPlanner.java:100` | The plan answer carries every planned file's lower and upper bounds (data values), partition values, paths and delete files, read with the service's cluster-wide credentials, under the metadata privilege only. Natively, reading manifests needs storage access the caller holds. `Privileges.SELECT` exists on main and is unused (checked at `bb8929e1`) | Certain about what is returned; whether deployments grant `GET_TABLE_METADATA` more widely than storage read cannot be told (SP D-1) | FF-04 (gate), FF-05 (privilege) |
| RF-023 | SHOULD FIX | `IcebergRestApiHandler.java:255-265`; `audit/TableAuditAspect.java:116-119` | Plan and credentials calls emit no table audit event: no pointcut covers `loadIcebergTable` or `TablesService.loadTable` [checked by the consolidator: the advised methods are `IcebergRestApiHandler.loadTable` and the native handlers]. The merge message of 127 and the javadoc claim the opposite | Certain (SP D-2) | FF-04 |
| RF-024 | SHOULD FIX | `IcebergRestScanPlanner.java:60-63, 83-85, 101-105` | Planning is unbounded before the 10,000-task cap (manifest reads, the `DeleteFileIndex`, the shared worker pool, concurrent requests, wall-clock time, a full Jackson tree of the answer, an unbounded request body), and 0 or a negative value means "default", so the route cannot be switched off | Certain; impact needs a load run (SP D-3) | FF-04, FF-06 |
| RF-025 | MINOR | `IcebergRestScanPlanner.java:172-179` | With `use-snapshot-schema=false` and a branch that moved since the load, the scan falls back to the snapshot's schema, giving a 400 client planning would not | Certain; rare race (SP D-4) | FF-06 |
| RF-026 | MINOR | `IcebergRestErrors.java:114-117` | Any `IllegalArgumentException` during planning or serialization (server faults) answers 400 | Certain (SP D-5) | FF-06 |
| RF-027 | MINOR | `IcebergRestScanController.java:96-103` | Every refused plan logs at WARN; a client retrying a table above the cap floods the log | Certain (SP D-6) | FF-06 |
| RF-028 | MINOR | design | An unpinned 1.12 `RESTTableScan` sends no snapshot id, so the server plans its current main, not the snapshot the client loaded; documented nowhere | Certain (SP section 1, item 4) | FF-31 (document) |

### Native path and House Tables

| Id | Severity | File and line | Statement | Certainty | Fix |
|---|---|---|---|---|---|
| RF-029 | SHOULD FIX | `OpenHouseInternalTableOperations.java:699, 716-728`; `HouseTableRowLimits.java:44, 96-99` [checked] | The row-length check's default (255, no off value) refuses on the bootstrap store (512) every create and every commit to an existing table whose location is 256 to 512 characters, maintenance jobs included (BC-001) | Certain (RR D1) | FF-09 |
| RF-030 | BLOCKER, inherited | `TableUUIDGenerator.validatePathOfProvidedRequest`; `OpenHouseInternalCatalog.dropTable` (`deletePrefix`) | A native create can take a renamed-away table's directory with only CREATE_TABLE on the database; dropping it deletes the other table's files. Closed over IRC (pull request 116), pinned natively by `NativeCreateDirectoryReusePinnedDefectTest` and `CommitPathAuthorizationTest.pinnedDefectCreateInARenamedTablesDirectory...` | Certain (RR section 5, CF section 5) | Owner, D35 (FF-46) |
| RF-031 | MINOR, inherited | `services/housetables/ddl/0000__baseline.sql:20, 64` [checked: `user_table_row.table_id` 255, `soft_deleted_user_table_row.table_id` 128] | On the production store a rename to 129 to 255 characters succeeds and the table then cannot be soft-deleted; the new limit's documentation presents 255 as safe | Likely (RR D9) | Upstream issue (FF-46); correct `DEPLOY.md` in FF-09 |
| RF-032 | MINOR | `repository/OpenHouseInternalRepository.java`; `services/TablesService.java` | New interface methods without defaults break any other implementation (BC-009) | Certain (CW D-9) | FF-12 |

### Java client and Spark runtime

| Id | Severity | File and line | Statement | Certainty | Fix |
|---|---|---|---|---|---|
| RF-033 | SHOULD FIX | `spark-3.5/.../capabilities/OpenHouseCapabilities.java:46-53, 129-142` [checked] | Policy statements are refused on any Spark catalog that is not Iceberg's own, even when it returns an OpenHouse `SparkTable`; the message blames the server (BC-007) | Certain; real exposure depends on whether LinkedIn runs such catalogs (CS D-1) | FF-10 |
| RF-034 | MINOR | `spark-3.5/openhouse-spark-runtime/build.gradle:58-60, 85-87` | The published thin Spark 3.5 runtime jar needs the capabilities module, which is not published (BC-008) | Certain (CS D-2); likely (BD D-8) | FF-16 |
| RF-035 | MINOR | `OpenHouseRestAdapter.java:115-137, 160-163, 301-308`; `OpenHouseRestCatalog.java:113` | The in-process seam ignores `prefix`; with one set, every call fails "No route for request" | Certain (CS D-3) | FF-43 |
| RF-036 | MINOR | `OpenHouseRestCatalog.java:66-67, 357-370` | The seam's stale-base check covers loaded and created tables, not builder transactions, while the javadoc says tables reject a stale base | Certain (CS D-4) | FF-43 |
| RF-037 | MINOR | `capabilities/src/iceberg-1.12/.../RestTransport.java:230-245` | Each GRANT, REVOKE, SHOW GRANTS or UNLOCK builds a new HTTP client and auth session (one token request with OAuth), without the `initSession` that `RESTSessionCatalog` calls; a custom auth manager may authenticate differently | Likely (CS D-5) | FF-43 |
| RF-038 | MINOR | `OpenHouseCapabilities.java:69-77` | A client-side `openhouse.capabilities` value turns capabilities on, against servers that do not send it | Certain; declared in 100 and 103 (CS D-6) | None (keep the declaration) |

### Format v3 and column defaults

| Id | Severity | File and line | Statement | Certainty | Fix |
|---|---|---|---|---|---|
| RF-039 | SHOULD FIX | `OpenHouseTablesApiValidator.java:505-546`; `FormatV3TypesSwitchOffTest.java:73-79` [checked] | On the default build, v3-only column types on a v2 table answer 500 `Invalid schema for v2`; main answered 400; the 500 is pinned as "as before" (BC-031) | Certain (FV D1) | FF-18 |
| RF-040 | SHOULD FIX | `FormatVersionRule.java:93-100, 186-188` | `cluster.iceberg.format-version=3` makes every new table v3 with the v3 switch off; the switch does not guard what its javadoc says | Certain (FV D2) | FF-40 |
| RF-041 | SHOULD FIX | `OpenHouseInternalRepositoryImpl` update path; `GetTableResponseBody` | An upgrade to v3 (irreversible, cuts off every 1.5.2 reader) answers a plain 200 with no log line, metric or audit field, and the native GET cannot show a table's format version | Certain (FV D3) | FF-40 |
| RF-042 | MINOR | `FormatVersionCommitPolicy.keepInitialDefaults`; `FormatV3Types.checkOnV3` | A default value on a `variant` or `unknown` column answers 500 on every table version (the author's statement, not fixed or pinned) | Cannot tell the path without running (FV D4) | FF-18 |
| RF-043 | MINOR | `OpenHouseInternalTableOperations.java:414-424` | On a cluster at `format-version=1`, a create rebuilt from empty (v2) is now refused (BC-015) | Likely (FV D5) | FF-13 |
| RF-044 | MINOR | `OpenHouseInternalRepositoryImpl.java:321-338, 355-368` | Every replace on the default build reads the stored table again outside the transaction; an upgrade racing it is decided on a stale read (worst case a refused replace) (BC-032) | Certain for the extra read; the race inferred (FV D6) | FF-19 |
| RF-045 | MINOR | `FormatV3Types.java:119-120`; `FormatVersionCommitPolicy.java:134, 142` [checked: "(work item W8)", "(work item X7)"]; `gradle/iceberg-fork.gradle:63-86` ("decisions D7 and I6d") | Error messages returned to users and developers cite this effort's private work-item and decision ids | Certain (FV section 4, BD section 4) | FF-34 |

### Maintenance jobs

| Id | Severity | File and line | Statement | Certainty | Fix |
|---|---|---|---|---|---|
| RF-046 | SHOULD FIX | `apps/spark-3.5/src/shared/main/.../TablesClientFactory.java:25-38, 65-117` | The scheduler's Iceberg-line detection can never succeed in its image (no Iceberg and no `IcebergLine` in the apps uber jar), so it always declares `column-defaults` and warns at every start; on the default line its catalogs declare nothing, so it schedules tables its jobs are refused on. The only test runs on a classpath that has `IcebergLine` | Certain from build files and the Dockerfile (JB D1) | FF-14 (reference line); the default-line mismatch is BC-029 (OWNER) |
| RF-047 | SHOULD FIX | `SortStatsCollectionSparkApp.java:226-252` | The 1.5.2 fallback walks private parser fields with `setAccessible(true)` and takes the first `ParserInterface`; fragile against any new field and strong encapsulation (BC-019) | Likely fragile; works as tested (JB D2) | FF-14 |
| RF-048 | MINOR, inherited | `SortStatsCollectionSparkApp.java:150-152`; `jobs.yaml` | On the session `jobs.yaml` configures, sort stats fails at its copy from `spark_catalog` before and after pull request 145 | Certain (JB D3; X17 in `58-waiting-on-mike.md`) | Owner (FF-46) |
| RF-049 | MINOR, inherited | `Operations.java:518-560` | Retention backups carry data files only; deletes and deletion vectors are not backed up, so a restore resurrects deleted rows | Certain (JB D4; D46 with the owner) | Owner (FF-46) |
| RF-050 | MINOR | `Operations.java:816-838, 854-860` | `close()` swallows catalog-close failures and nulls the catalog; a later `getTable` silently builds a new one on a stopped session | Certain; test concern only (JB D5) | FF-44 |

### Build and release

| Id | Severity | File and line | Statement | Certainty | Fix |
|---|---|---|---|---|---|
| RF-051 | BLOCKER | `.github/workflows/build-tag-publish.yml:33, 56` (unchanged since main, checked); `gradle/iceberg-fork.gradle:23-29, 72-86` [checked] | On a push to LinkedIn main the release pushes a tag, then `./gradlew publish` fails at configuration (no fork step in that job) or at the publish guard; images and the Python package never run. An orphan tag per merge (BC-034). BD notes the docs say it "would now stop at this check" when it stops earlier, at the missing fork | Certain (BD D-1, CF D1) | FF-26 |
| RF-052 | SHOULD FIX | `libs/datalayout/build.gradle:9-17, 32-48`; `build.gradle:90-96` [checked] | The reference line's published `datalayout` is not main's: Spark 3.5, the line's Iceberg, no root forces (BC-024) | Certain (BD D-2) | Owner with BC-025 |
| RF-053 | SHOULD FIX | `apps/spark-3.5/build.gradle:73-77` [checked] | `log4j2.properties` in the apps uber jar on both lines can change every maintenance job's logging (BC-023) | Likely; depends on the cluster's Log4j2 setup (BD D-3) | FF-15 |
| RF-054 | SHOULD FIX | `scripts/iceberg-fork/publish-local.sh:69-74, 89-93`; the fork's `gradlew:89-90`; `.github/actions/iceberg-fork/action.yml` | The fork build downloads `gradle-wrapper.jar` with `curl` and no checksum, then runs it, on every developer machine and CI cache miss; the output is published under LinkedIn's `com.linkedin.iceberg` group, which no LinkedIn repository holds; the CI cache of the fork is used with no check beyond its key | Certain (BD D-4) | FF-29; the group id waits on hosting (owner) |
| RF-055 | SHOULD FIX | `gradle/iceberg-fork.gradle:23-29` | Every Gradle command on a fresh clone of the default line fails until `publish-local.sh` has run (needs git, network to a personal GitHub fork, JDK 17+ unchecked, about 4 GB, minutes) (BC-035) | Certain (BD D-5) | FF-29 interim; owner for hosting |
| RF-056 | MINOR | `settings.gradle:31`; `build.gradle:43`; `buildSrc/...`; `apps/spark-3.5/build.gradle:22, 83, 96` | Every line check tests presence, so `-PicebergLinkedIn=false` selects the reference line | Certain (BD D-6) | FF-29 |
| RF-057 | MINOR | `build.gradle:100-103` | The tables service on the default line likely ships `jackson-databind` 2.13.4 with `jackson-core` 2.22.x | Cannot tell without `dependencyInsight` (BD D-7) | FF-30 |
| RF-058 | MINOR | `infra/recipes/docker-compose/common/spark/spark-3.5-base-hadoop3.2.dockerfile`; `build.gradle:244-262` | The Spark 3.5 image cannot be built from the reference line (BC-026) | Certain (BD D-9) | FF-26 |
| RF-059 | MINOR | `docs/development/iceberg-fork.md:101-102, 117-118, 266`; `README.md:94-101`; `SETUP.md:328`; `publish-local.sh:4` | Documentation inaccuracies: where the release stops; "not deployed" against "the only line that can publish"; old Spring Boot and Livy versions; an ADLS command mixing Iceberg lines; a stale opt-in comment kept because the script's hash is the CI cache key | Certain (BD D-10) | FF-31 |

### CI, shared test fixtures and evidence

| Id | Severity | File and line | Statement | Certainty | Fix |
|---|---|---|---|---|---|
| RF-060 | BLOCKER | `SparkSqlSmokeScenarios`, `SparkSqlSmokeTest.differences()`, `CatalogPluginContractTest`, `CommitPath*Test` | No suite compares against LinkedIn main: expectations are literals in source, run against the branch's own code; the "baseline" is the legacy path at head on `-PicebergLinkedIn`. Baselines were edited in place (s19 and s24 split per line; s31 loosened and then re-pinned after a service change; s17 re-pinned after a fixture change); about 32 `IcebergLine.current()` branches in test code accept default-build differences with no reason; the smoke outcomes go to `build/reports/smoke/*.tsv` and are never compared. The only recording of main is the delta-harness's `BASELINE-2026-11-22.md`, not in CI | Certain (CF D2) | FF-20 to FF-23 |
| RF-061 | SHOULD FIX | `integrations/java/openhouse-rest-compat-kit/build.gradle:128-206`; `expected-outcomes.txt` | The kit's ratchet is two-way on name and outcome but ignores cause (95 FAILED entries where a new cause is invisible), disappearance (the total is only logged) and configuration (it runs with v3 on); several filings are wrong (section 2f) | Certain for three filings; one cannot be told (CF D3) | FF-25 |
| RF-062 | SHOULD FIX | `tables-test-fixtures/.../tablestest/HouseTablesH2Repository.java` and `services/tables/src/test/.../e2e/h2/HouseTablesH2Repository.java` | The House Tables stand-in is faithful in compare-and-set but differs from the real store where tests rely on it, and its two copies disagree with each other (section 2e) | Certain (CF D4) | FF-32, FF-33 |
| RF-063 | SHOULD FIX | `integrations/spark/delta-harness/` | The delta-harness, the only recording of main, has had no full run since the flip (about 30 merges); not in CI | Certain (CF D5) | FF-24 |
| RF-064 | SHOULD FIX | `.github/workflows/pr-validations.yml` | LinkedIn's pull request check names change and runners go from one to about 14; branch protection requiring the old name would block every pull request (BC-036) | The name change certain; LinkedIn's protection cannot be told (CF D6) | FF-27 |
| RF-065 | MINOR | `scripts/ci/check-test-groups.py` | The partition checker never runs in CI (the partition is sound by construction) | Certain (CF D7) | FF-27 |
| RF-066 | MINOR | `integrations/spark/spark-3.5/openhouse-spark-itest/src/shared/authorization/.../GrantStoringAuthorization.java:48-63` | The grant tests assert a role-to-privilege model the tests invented, not the deployed policy; grants survive DROP and recreate in the stand-in | Certain; not reachable from production wiring (CF D8) | FF-45 (document) |
| RF-067 | MINOR | `apps/spark-3.5/src/test/.../TableStatsFormatV3TypesTest.java:48-53` | A line-gated skip catches any `RuntimeException`, so a parsing regression on the default line skips instead of failing | Certain (CF D9) | FF-45 |
| RF-068 | MINOR | `split-build.yml`, `build-run-tests.yml` (`print-failed-tests.py --exclude restCompatKitTest`) | When the kit's ratchet fails, the per-test listing is suppressed; the failure shows only in the Gradle exception text | Certain; by design (CF D10) | FF-45 (optional) |

### Where the reviewers disagreed, and what the code shows

- Register of format v3 metadata (RF-015): RR calls it a defect, FV calls it correct. D43 decided the refusal; the code
  refuses always; the remaining fault is that it is unadvertised and that `create()` keeps an unreachable v3 branch.
- House Tables column widths: RR says the production DDL has `table_id` 255 and the soft-delete table 128; CF says the
  real schema has 128 ids and a 512 location. Both are right about different files [checked]: `schema.sql` (the
  bootstrap store the service creates itself) has 128, 128 and 512; `ddl/0000__baseline.sql` (the production DDL,
  applied out of band) has 255 and 255 for `user_table_row` and 128 for the soft-delete table's `table_id`.
- The release workflow: BD and CF agree on the failure; BD says it stops at the missing fork, before the guard the docs
  name; the code agrees with BD [checked]. BD cites the publish step at line 51; it is at line 56 [checked].
- IRC view load lines: VC cites `IcebergRestViewHandler.java:233-236` and `:283-288`; at `81b4cd4d` the methods are at
  `:119` and `:172-173` [checked]. The finding stands.
- Whether the column-defaults change reaches the jobs: FV B7 says the jobs on 1.12 send nothing while the scheduler
  still declares; JB D1 confirms the scheduler always declares. They agree; JB shows why (the jar has no `IcebergLine`).

## 2. Unpinned claims and weak tests

Bottom line: the evidence that native behavior is preserved compares the branch with itself; several assertions cannot
fail; four tests pin a defect as expected and are wrong; the kit's list and the stand-in each carry known infidelities.

### 2a. Evidence that does not compare against LinkedIn main

- The smoke, contract and commit-path suites (pull requests 80, 81, 82, 146) run hand-written literals against the
  branch's own code; the reference line carries every shared change the branch made (CF D2). Only s01 to s20 and the
  T3 and T4 suites were written on unchanged code.
- Baseline edits recorded by CF: `adb70040` and `c11671f9` (s19, s24 per line); `87547373` (s31 loosened); `7f8de45f`
  (s31 re-pinned after the service change of 139, with no declared difference); `fe0ae995` (s17 after the fixture
  change of 140); `1598eb5b`, `e7eb4aac`, `ca87ee83` (s21 to s33 written after earlier merges).
- About 32 `IcebergLine.current()` branches in test sources accept default-build differences (`RTASTest`,
  `CatalogPluginContractTest.capabilityMismatch` line 527, `OpenHouseTableOperationsTest`, `TablesClientTest`,
  `FormatV3SparkTest` and others) outside the `Difference` mechanism (CF D2).
- The smoke outcomes TSV is written to `build/` and never compared with a stored file (CF D2).
- The delta-harness baseline of 2026-11-22 is not in CI and has not had a full run since the flip (CF D5).
- No check compares the reference line's resolved dependencies, POMs or uber-jar contents with `bb8929e1`; jar entry
  lists cannot see bytecode compiled against another Spark (BD section 5).
- Pull request 89's "no behavior change" rests on `StoredMetadataGoldenTest` (stored files, normalized) and
  `CommitPathUpdateRulesTest` (four rules); neither pins the order of lock, privilege and capability checks or the
  conflict wording; `TableCommitServiceTest` was written against the new entry point (CW section 5).
- Pull request 129's first commit "pin today's answers before any change" pinned the branch after the flip (FV
  section 5); pull request 124's "before" answers are the branch's (FV).
- `FormatVersionSwitchOffTest.aNativePutAskingForNoHigherVersionAnswersAsBefore` (`:230-247`) pins 500 for a downgrade
  and a non-numeric version as "as before" with no run on main (FV section 5).
- `IrcHouseTableReadsTest` counts reads against the H2 stand-in only; the real repository's paged and unpaged reads are
  not exercised (RR section 5). On MySQL in non-strict mode the "before" of pull request 121 is silent truncation,
  which nobody checked (RR section 5).
- Pull request 139's "the spec is equal as JSON to before": no checked-in spec and no test compares them (RR).
- Pull request 84's "no expectation changed" cannot be told now, after 128 rewrote the smoke test; 128 says equivalence
  "was read, not proven by a diff of outcomes" (CS section 5).
- The publish guard was "verified by dry runs of the task graph"; no test runs it; nothing tests the release workflow
  (BD section 5).

### 2b. Assertions that cannot fail

- `IcebergCatalogMapperTest` has no `@Test` annotations and uses Java `assert` (off without `-ea`): its five methods
  never run (CS section 5).
- `CatalogPluginContractTest` grants section (about lines 505-515) asserts the ACL listings are empty after granting:
  the fixture's handler without OPA, not OpenHouse behavior (CF section 5).
- The kit's `test` task has `failOnNoMatchingTests = false` and runs nothing (CF, BD).
- X9's metric is asserted as `longThat(n -> n > 0)` (`FormatV3MaintenanceTest:666-675`); a per-file miscount passes (JB).
- `IcebergLineTest.expectedFromBuildVersion` accepts 1.2 as LEGACY, which cannot occur (BD).
- `RestHttpPathLineTest` and `FormatV3MaintenanceTest` assert that the build set a system property: they test the build
  script (BD).
- `TableStatsFormatV3TypesTest` skips on any exception (RF-067).
- Pull request 131's "a refusal costs under 1 MB" is printed by `IrcScanPlanCostTest`, not asserted (SP).
- The scan auth test stubs every access decision to all-true or all-false, so plan and load are equal by construction
  (SP section 5).
- `SystemActionDeclarationTest` checks session configuration, not the header on the wire (JB).
- `TablesClientTest:91` runs where `IcebergLine` is on the classpath; production's scheduler has none (JB).
- The s33 lost-update readback of pull request 146 was never seen to fail against an injected lost update (CF).
- `IrcPolicyPatchTest.twoPolicyCommitsOnTheSameLoadedTableBothApply` races two different fields only; same-field races
  are sound by reading but unpinned (VC).

### 2c. Pins of defects

Wrong pins (they pin a defect introduced by this effort as expected behavior):
- `IrcViewRoutesAuthTest.readsMakeNoAuthorizationDecisionAsNatively` (`:159-165`): right for the native GET, wrong for
  IRC load (RF-018).
- `OpenHouseCapabilitiesTest.aNonIcebergCatalogOffersNothing`: pins the refusal of BC-007.
- `FormatV3TypesSwitchOffTest.everyFormatV3TypeIsRefusedAsBeforeWithTheSwitchOff` (`:73-79`): pins a 500 as "as before"
  (RF-039).
- `IrcWriteRoutesTest.aFailedRequirementIs409CommitFailedAndChangesNothing` runs with permissive authorization and so
  pins requirement-before-authorization (RF-002).
- The jobs-scheduler INFO check (`.github/actions/default-build-deployables/action.yml`) pins the scheduler logging at
  INFO, the cause of BC-023, and checks no maintenance-job logging (BD).

Right pins of main's behavior (inherited; keep while unfixed):
- `NativeCreateDirectoryReusePinnedDefectTest`, `CommitPathAuthorizationTest.pinnedDefectCreateInARenamedTablesDirectory...`
  (RF-030; deserves an upstream issue).
- `CommitPathStateRulesTest:210-227` and `CommitPathSnapshotsTest:193` (NPE on a lock-state PUT).
- Pull request 106's native 500 for a rename onto a view (right natively; the IRC test should also assert no metadata
  file is written once RF-003 is fixed).
- `SortStatsParserTest.onTheJobsYamlSessionTheJobStopsAtItsCopyFromSparkCatalog` (RF-048).
- X13's dangling deletion vector after a DELETE racing retention, and orphan removal with TTL 0: Iceberg's behavior.

Declared and pinned choices (not defects, but their consequences are unstated): the replace heuristic in
`IrcWriteRoutesTest` (a hand-made commit without assertions is a replace, so it skips `versionCheck` and the partition
evolution check, CW); view last-writer-wins in `IcebergRestCatalogViewTest` (RF-020); `aStoredKeyIsTreatedAsTheClientTreatsIt`
(RF-010); `minRowsRequestedIsIgnored` and the exclusive incremental start in scan planning (SP: both reasons hold).

### 2d. Declared differences with wrong or unreviewed reasons

- s31 was re-pinned to the new service order with no `Difference`: a service behavior change written into the baseline
  (CF D2; BC-005).
- Free-text difference kinds such as "(d) IRC path, not decided" need no review (8 of Spark 4.2's 26, and the s32 and
  s33 entries) (CF).
- The question in `58-waiting-on-mike.md` on v3 types ("a 400 would change v2 answers") rests on the wrong baseline
  (FV; BC-031).
- Checked and found right: CS checked 12 stated smoke reasons and CF checked 16; all hold. s22 and s26 (Spark 4)
  are backed by `Spark42WithoutOpenHouseTest` on Iceberg's Hadoop catalog and were not re-derived from Spark's source.

### 2e. The House Tables stand-in's infidelities (CF D4, RR D2, D3)

1. Compare-and-set is faithful, but one JVM-wide `SAVE_LOCK` serializes every key in every application context (the
   real store uses per-row versions); rename does not take the lock.
2. A save does not stamp the entity type: a table saved with a view's version overwrites the view row; the real store
   refuses.
3. Rename of a missing or view source is a silent no-op (real: 404); a cross-database rename checks occupancy at the
   wrong key and can overwrite an occupant (the services/tables copy moves the row instead).
4. Delete of a missing row is a silent no-op (real: 404).
5. Restore over a live table silently overwrites it (real: 409); restore of a missing soft-deleted row is a no-op in one
   copy and throws in the other (real: 404).
6. The soft-deleted listing is a static map shared across contexts and never cleared, case-sensitive, unsorted, and
   reports the page size as the number of pages.
7. Purge compares ids case-sensitively (real: not).
8. H2's primary key is case-sensitive, so `DB.T` over `db.t` inserts a second row; the services/tables copy's
   `findStoredLocation` is case-sensitive, so a create of `DB.T` over `db.t` is accepted (real: 409).
9. Lengths: every string column is 255 (real bootstrap: ids 128, location 512), so the stand-in accepts ids the real
   store refuses and refuses locations it accepts.
10. A too-long value raises `DataIntegrityViolationException` (503 in the service); the real store maps it to 409; the
    stand-in never produces the 400/401/403/429 or 5xx/DNS client paths, so the retry and state-unknown branches are
    untested end to end.
11. `storageType` is left null (real: `hdfs`).
12. The H2 reuse fix is a `@PropertySource`, which any consumer's `spring.datasource.url` silently overrides; nothing
    asserts the effective URL.
13. It stores the table UUID, which `user_table_row` lacks, so the UUID branch of the directory check runs only in tests
    (RR D2); and it is case-sensitive where production is not (RR D3).

### 2f. The compatibility kit list's misfilings (CF D3)

- `testLoadTableWithMissingMetadataFile | FAILED | e`: the reason says the server answers 500; it is a defect (class d,
  the empty class), not a message-text filing; "message text 9" is therefore 8.
- The SPEC group's reason "partition evolution and bucket specs are refused 400" is contradicted by
  `testCompleteCreateTable`, which creates with a bucket spec and passes; only evolution is refused.
- `OpenHouseRestCompatibilityKitViewCatalogTests.requiresNamespaceCreate()` is documented "a database comes into being
  with its first view", while 24 VIEWDB entries say a view cannot create its database; one is false; if views should
  create their database as tables do, the 24 are an implementation gap (class c or d), not a model difference.
- `testLoadNamespaceMetadata` is filed as "namespace create or drop is not served (406)", but its first assertion is a
  message check; which one fails cannot be told without running.
- The only class-d entry ever listed (HTSKEY, rename onto a view's name) left the list when a stand-in change made the
  test pass: a fixture fix, not proof that the real route refuses.

## 3. The fix-forward work list

Bottom line: 45 items (FF-39 was folded into FF-35), each one agent, one pull request, under about 2,000 lines and 20 files, one idea. Ready means it
can start now: "decided" when the orchestrator already decided it, "rule" when S60 decides it. "Toward main" means the
item restores LinkedIn main's behavior; "new fix" means it changes behavior that main also had or that only new routes
have. Each item's tests come first, and the first test must fail on the current head.

### Security and authorization on new routes

| Id | What it does | Closes | Files | Depends on | Behavior | Tests first | Ready? |
|---|---|---|---|---|---|---|---|
| FF-01 | On the Iceberg REST table commit route and the one-table transaction route, run the write-privilege and lock checks immediately after `loadBase`, before requirements, updates and validation, including for a commit with no updates | RF-001, RF-002 | `api/irc/IcebergRestWriteHandler.java`, `services/TableCommitService.java`, `services/TableCommitCore.java` (expose the check), `e2e/h2/IrcWriteRoutesAuthTest.java`, `e2e/h2/IrcWriteRoutesTest.java` | none | New fix, new routes only; native routes unchanged | A denied principal posting `{"requirements":[],"updates":[]}` gets 403 (today 200 with metadata); a denied principal with a failing `assert-table-uuid` gets 403, not 409; the transaction route with a denied empty change gets 403, not 204; `aFailedRequirementIs409...` then runs with a permitted principal | Ready (decided by the orchestrator) |
| FF-02 | On the Iceberg REST view commit route, authorize before loading or applying anything, and audit the refusals that remain before the native handler | RF-019 | `api/irc/IcebergRestViewHandler.java`, `IcebergRestViewSpelling.java`, the view audit aspect, `e2e/h2/IrcViewRoutesAuthTest.java` | none | New fix, new route | A denied principal with a failing `assert-view-uuid` gets 403, not 409; a refused view commit appears in the view audit | Ready (decided by the orchestrator) |
| FF-03 | IRC view load and list check a read privilege before the definition is read; `loadView` moves onto `ViewsService`; the native GET stays pointer-only and unchecked | RF-018 (and the register-view exposure of 149) | `IcebergRestViewHandler.java`, `services/ViewsService.java`, `ViewsServiceImpl.java`, `IrcViewRoutesAuthTest.java` | the scout's reading of the owner's earlier view decisions (3b) | New fix, new routes | Flip `readsMakeNoAuthorizationDecisionAsNatively` to expect 403 on IRC load for a principal without the chosen privilege, and 200 on the native GET | Waits for the scout |
| FF-04 | Scan planning behind its own property in `ClusterProperties`, off by default (the routes then answer 406 and are not listed); an audit advice on `loadIcebergTable`; the cap property moved out of the controller; "0" means off | RF-022 (interim), RF-023, RF-024 (switch) | `src/iceberg-1.12/.../IcebergRestScanController.java`, `IcebergRestScanPlanner.java`, `ClusterProperties.java`, `audit/TableAuditAspect.java`, `IrcScanPlanningBoundTest.java`, a new audit test, the kit and Spark 4.2 itest configurations | none | New fix, new route; the default changes from served to off | A plan call emits a table READ audit event (fails today); with the property unset the plan route answers 406 and the endpoint list omits it | Needs the orchestrator's decision: D44 served the route by default |
| FF-05 | Decide and enforce the privilege for manifest contents on the plan route (for example `SELECT`, or a storage-level read check) | RF-022 | `IcebergRestScanController.java`, `Privileges` use, an auth test with metadata allowed and storage denied | FF-04 | New fix | A principal with `GET_TABLE_METADATA` and no storage read gets 403 from plan | Needs the owner: it is the same access contract as D45 (which privilege grants read, per storage) |
| FF-06 | Bound planning: a time budget checked in the loop, a concurrency limit, serialization failures answered 500, a counter instead of a WARN per refusal, and the table schema projected when `use-snapshot-schema=false` meets a moved branch | RF-024, RF-025, RF-026, RF-027 | `IcebergRestScanPlanner.java`, `IcebergRestScanController.java`, `IcebergRestErrors.java` or `IcebergRestJson.ok`, tests | FF-04 | New fix, new route | A plan exceeding the budget answers `failed`; a serializer `checkArgument` answers 500; a branch moved between load and plan plans with the table schema | Ready (rule) |
| FF-07 | Exclude the metrics route's body from the service audit's payload capture, or cap the captured size for every IRC route | RF-011 | `CachingRequestBodyFilter`, `ServiceAuditAspect`, a test | none | New fix (audit content of new routes) | A 5 MB metrics report leaves no payload (or a capped one) in the audit event | Ready (rule) |
| FF-08 | IRC rename onto a view's name answers 409 from a House Tables view lookup before any write; other House Tables conflicts during a rename answer 409 `CommitFailedException` | RF-003 | `IcebergRestWriteHandler.java`, `IrcWriteRoutesTest.java` | none | New fix, new route | The rename writes no metadata file; a simulated concurrent update is `CommitFailedException`, not `AlreadyExistsException` | Ready (rule) |

### Restores of native behavior on the reference line

| Id | What it does | Closes | Files | Depends on | Behavior | Tests first | Ready? |
|---|---|---|---|---|---|---|---|
| FF-09 | The House Tables row-length checks are off by default: a non-positive value means no check, both properties default to 0; `DEPLOY.md` tells a production-DDL deployment to set 255 and names the soft-delete table's 128 | BC-001, BC-002, BC-003, RF-029, RF-031 (docs) | `iceberg/openhouse/internalcatalog/.../HouseTableRowLimits.java`, `cluster/configs/.../ClusterProperties.java`, `services/tables/DEPLOY.md`, `HouseTableRowLimitsTest`, `MetadataLocationConfiguredLimitTest`, `TableIdConfiguredLimitTest`, `DefaultClusterPropertiesTest`, `OpenHouseInternalTableOperationsTest` | none | Toward main | With the properties unset, a table at a 300-character location takes a commit on a 512-wide store (fails today); with 255 set, the 400 as now | Ready (decided by the orchestrator) |
| FF-10 | Policy statements are offered on Spark catalogs that are not Iceberg's own (`NOT_ICEBERG` offers `policies`, as `OTHER_ICEBERG` does); the exec's `openhouse.tableId` check stays the gate; mirrored on Spark 4.2 | BC-007, RF-033 | `spark-3.5/.../capabilities/OpenHouseCapabilities.java`, `spark-4.2/.../OpenHouseCapabilities.java`, both `OpenHouseCapabilitiesTest`, a new exec test with a delegating `TableCatalog` | none | Toward main | A wrapper `TableCatalog` returning an OpenHouse `SparkTable`: `ALTER TABLE ... SET POLICY` commits (fails today with "does not support OpenHouse policies") | Ready (decided by the orchestrator) |
| FF-11 | One property for the whole Iceberg REST base, for example `cluster.tables.irc.enabled`, default false; with it off, `/iceberg/**` answers as on main | BC-004 | one `@Configuration` with `@ConditionalOnProperty` over the IRC controllers and handlers, `config/TablesMvcConfigurer.java`, the shared test application properties of the fixtures, the kit, the Spark itests | none | Toward main (the fork's own configurations turn it on) | With the property unset, `GET /iceberg/v1/config` is 404 through the native interceptor, as on main | Needs the orchestrator's decision: the property's name, and whether the fork's deployables turn it on |
| FF-12 | Defaults for `findWithMetadataById`, `createTable(body, creator, UUID)` and `stageCreateTable`; the clustering-column limit checked before mapping | BC-009, BC-010, RF-032 | `repository/OpenHouseInternalRepository.java`, `services/TablesService.java`, `dto/mapper/iceberg/PartitionSpecMapper.java`, a compile test with a minimal implementation, a mapper test | none | Toward main | A test implementation of each interface that does not override the new methods compiles; five clustering columns with an unmappable type give the limit error | Ready (rule) |
| FF-13 | The format-version rule on the reference line: the below-v3 `write-default` refusal runs only where the line can write v3; no metric for a passing format-version check; a rebuild-from-empty create exempt from the floor on a v1 cluster; one run of main to record BC-012's status; D43's reasons written beside BC-011 and BC-012 | BC-011 to BC-015 (write-ups for 011 and 012), RF-043 | `formatversion/FormatVersionCommitPolicy.java`, `FormatVersionRule.java`, `internal/catalog/PreCommitCheckRunner.java` or `api/ApiConfig.java`, `OpenHouseInternalTableOperations.java`, `FormatVersionSwitchOffTest`, a new v1-cluster test | none | Toward main on the reference line | On `-PicebergLinkedIn`, a v2 commit carrying a write-default is stored (fails today); a passing commit emits no `format-version` metric; a v1 cluster's CTAS succeeds | Needs the orchestrator's decision: D43 item 5 and D11(e) disagree on the write-default rule for 1.5.2 |
| FF-14 | Jobs: remove the reflective sort-stats fallback; read the format version without a second catalog load; the scheduler sends main's constant `column-defaults` with no detection and no WARN | BC-019, BC-020, BC-021, RF-046 (reference line), RF-047; releases the held W13 | `apps/spark-3.5/src/shared/main/.../SortStatsCollectionSparkApp.java`, `.../client/TablesClientFactory.java`, `SortStatsParserTest`, `TablesClientTest` | none | Toward main | A sort-stats run on a v2 table makes no extra table GET; the scheduler logs no WARN with `IcebergLine` absent (run `declaresColumnDefaults` without it) | Ready (rule). The default line's scheduler mismatch waits on BC-029 (owner) |
| FF-15 | `log4j2.properties` out of the Spark 3.5 apps jar; the scheduler gets `-Dlog4j2.configurationFile` in `jobs-scheduler.Dockerfile` | BC-023, RF-053 | `apps/spark-3.5/build.gradle`, `jobs-scheduler.Dockerfile`, the resource file's new place, `.github/actions/default-build-deployables/action.yml` | none | Toward main | The uber jar's entry list has no `log4j2.properties`; the scheduler image still logs at INFO | Ready (rule) |
| FF-16 | The Spark 3.5 runtime's thin jar needs only declared artifacts: publish and declare the capabilities module, or compile its types into the runtime; relocate it in the uber jar; delete `CONFIG_OVERRIDES` and the Java runtime's compile dependency on the module | BC-008, RF-034, RF-021 | `integrations/spark/spark-3.5/openhouse-spark-runtime/build.gradle`, `integrations/java/openhouse-catalog-capabilities/build.gradle`, `openhouse-java-runtime/build.gradle`, `OpenHouseRestAdapter.java`, `buildSrc/.../openhouse.maven-publish.gradle` | none | Toward main | A consumer of the thin jar plus the published Java runtime runs a GRANT without `NoClassDefFoundError` (a classpath test) | Needs the orchestrator's decision: publish the module or fold it |
| FF-17 | Write up the KEEP-FIX rows in code and in their merge records, without private ids: policy tag order, the shared `Page` schema customiser in `services/common`, one catalog per job run (test-only counter replaced by a counting test catalog), the retention signal (exact counts tested), partition labels (no WARN per null value), the `type`-only catalog | BC-005, BC-006, BC-016, BC-017, BC-018, BC-022 | `PolicyTag.java`, `MainApplicationConfig.java`, `HouseTablesSpringApplication.java`, `services/common/...`, `Operations.java`, `AppConstants.java`, `TableStatsCollectorUtil.java`, `OperationsCatalogTest`, `FormatV3MaintenanceTest` | none | Removes one WARN per null partition value (toward main's log volume); otherwise none | The retention metric test asserts exact counts per kind; a multi-spec table logs no WARN per null value | Ready (rule) |

### Restores of main's answers on the default line

| Id | What it does | Closes | Files | Depends on | Behavior | Tests first | Ready? |
|---|---|---|---|---|---|---|---|
| FF-18 | One 400 below v3 for every v3-only type, naming the type, at request validation; a 400 for a default value on a `variant` or `unknown` column | BC-031, RF-039, RF-042 | `formatversion/FormatV3Types.java`, `api/validator/impl/OpenHouseTablesApiValidator.java`, `FormatV3TypesSwitchOffTest`, a defaults test | none | Toward main | Flip the pin at `:73-79` to expect 400 `schema : ...` naming the type on the default line; a default on a `variant` column answers 400 | Ready (rule); the question about it in `58-waiting-on-mike.md` is moot |
| FF-19 | A replace takes the stored format version from its own base, with no second load | BC-032, RF-044 | `repository/impl/OpenHouseInternalRepositoryImpl.java`, a test counting House Tables reads on a replace | none | Toward main | A replace makes one House Tables read (two today) | Ready (rule) |

### The evidence gap: a recorded baseline of LinkedIn main

What exists today (from CF and BD): the delta-harness's `BASELINE-2026-11-22.md`, recorded at `bb8929e1` (2574 of 2792
cases), on demand only; and the smoke suite's per-statement outcomes, written to `build/reports/smoke/*.tsv` on every
run and never compared with anything.

| Id | What it does | Closes | Files | Depends on | Behavior | Tests first | Ready? |
|---|---|---|---|---|---|---|---|
| FF-20 | Record the smoke scenarios' outcomes on LinkedIn main: run s01 to s33 with the legacy runner on a worktree of `bb8929e1` plus only the suite; commit `smoke-baseline-bb8929e1.tsv`; the reference line's `catalogTest` compares its TSV with it in CI; an edit to the file must name a decision | RF-060 | the smoke runner, a comparison test, the TSV, the itest `build.gradle`, `split-build.yml` | none | None (tests) | The comparison fails on today's reference line exactly where Part A of the ledger touches Spark SQL (s31's tag order first); that failure list is the deliverable before any restore lands | Ready (rule) |
| FF-21 | The same for the contract suite (T3) and the commit-path suite (T4): outcomes recorded on `bb8929e1`, compared on the reference line in CI | RF-060 | `CatalogPluginContractTest`, `CommitPath*Test` runners, two recorded files, `build.gradle` | FF-20 (same mechanism) | None (tests) | The comparison fails where native answers changed (BC-001 on a long location, BC-011 to BC-013) | Ready (rule) |
| FF-22 | Route the about 32 `IcebergLine.current()` branches in test code through `Difference` or one reviewed list, each with a reason, each failing when it no longer differs | RF-060 | the test classes holding the branches (`RTASTest`, `CatalogPluginContractTest`, `OpenHouseTableOperationsTest`, `TablesClientTest`, `FormatV3SparkTest` and others), the `Difference` helper | FF-20 | None (tests) | A branch whose two outcomes are equal fails | Ready (rule); split in two if it passes 20 files |
| FF-23 | Compare the reference line's published artifacts with `bb8929e1` in CI: `dependencies --configuration runtimeClasspath` per published module, the POMs, and the uber jars' entry lists | BD section 5; evidence for BC-008, BC-023, BC-024, BC-025 | a script under `scripts/ci/`, recorded files from `bb8929e1`, `split-build.yml` | none | None (CI) | The comparison lists today's differences (the datalayout POM, `log4j2.properties`, the capability classes) | Ready (rule) |
| FF-24 | A scheduled job runs the delta-harness on both lines and diffs against `BASELINE-2026-11-22.md` | RF-063 | a workflow, the harness script | none | None (CI) | The first run reports the 7-against-3 failures recorded at the flip, or fewer | Ready (rule) |
| FF-25 | The kit's ratchet asserts the total and the passing list, records each FAILED entry's first failing assertion type and message prefix, refiles `testLoadTableWithMissingMetadataFile` as d, corrects the SPEC wording, and runs once at the default configuration and once with v3 on | RF-061, BC-043 | `integrations/java/openhouse-rest-compat-kit/build.gradle`, `expected-outcomes.txt`, the kit's service configuration | none | None (tests) | A FAILED test failing for a new reason fails the build | Ready (rule), except the view-creates-its-database question, which needs the orchestrator's decision |

### Release and build breakage

| Id | What it does | Closes | Files | Depends on | Behavior | Tests first | Ready? |
|---|---|---|---|---|---|---|---|
| FF-26 | LinkedIn's release builds and publishes from the reference line (`-PicebergLinkedIn` on the publish step and on `build-run-tests.yml` when the release calls it); the reference line may build its images; the tag is pushed only after a successful publish | BC-034, BC-026, RF-051, RF-058 | `.github/workflows/build-tag-publish.yml`, `build-run-tests.yml`, root `build.gradle` (docker tasks), the Spark 3.5 dockerfile's staging, `docs/development/iceberg-fork.md` | none | Toward main (the tag order is a new fix) | A dry run of the release graph on the reference line succeeds; `./gradlew publish --dry-run` on the default line still refuses | Needs the orchestrator's decision that the reference line is the release line until the fork is hosted; hosting stays the owner's |
| FF-27 | A final aggregating job named "Build and Run Tests" that needs every group; the reference line documented as optional upstream; the partition checker runs in the `plan` job | BC-036, RF-064, RF-065 | `.github/workflows/split-build.yml`, `pr-validations.yml`, `scripts/ci/check-test-groups.py` | none | Toward main (check name) | A pull request shows the old check name; a group partition gap fails `plan` | Ready (rule) |
| FF-28 | `build-run-tests.yml` on the release path and `dataloader-tests.yml` get main's triggers and steps back on main's paths; the tee, the printer and the health poll are kept as their own change | BC-037, BC-038 | the two workflows, the composite action | FF-26 | Toward main | Workflow lint plus a run on a fork pull request | Ready (rule) |
| FF-29 | Build hygiene: verify `gradle-wrapper.jar`'s SHA-256 in `publish-local.sh` before running it; check `java -version`; read the line property once in `settings.gradle` as a boolean (so `=false` means false); fail only the tasks that need Iceberg when the fork is missing | RF-054 (wrapper), RF-055 (interim), RF-056 | `scripts/iceberg-fork/publish-local.sh`, `settings.gradle`, `build.gradle`, `gradle/iceberg-fork.gradle`, the `buildSrc` plugins, `apps/spark-3.5/build.gradle`, the CI fork action | none | None for builds that work today | `-PicebergLinkedIn=false` builds the default line; `./gradlew help` works on a fresh clone | Ready (rule); the group id waits on hosting (owner) |
| FF-30 | Run `dependencyInsight` for `jackson-core` on the tables service's runtime classpath on the default line; pin it with the BOM or raise the force deliberately | RF-057 | `build.gradle` | none | Default line only | A test asserting one Jackson minor on the runtime classpath | Ready (rule) |
| FF-31 | Documentation and false comments: the release stop point, "not deployed", Spring Boot and Livy versions, the ADLS command; javadocs that claim false things (replace guard, plan audit, the scheduler's parity, the seam's stale-base check, `ViewsServiceImpl.loadView`'s "disabled implementation", `IcebergRestViewSpelling`'s deleted-test reason); declared differences documented in code (view last-writer-wins, unpinned scans plan current main) | RF-059, RF-020 (doc), RF-028 (doc), RF-004 (doc) | `docs/development/iceberg-fork.md`, `README.md`, `SETUP.md`, the named classes | none | None | none (documentation) | Ready (rule) |

### The House Tables stand-in's fidelity

| Id | What it does | Closes | Files | Depends on | Behavior | Tests first | Ready? |
|---|---|---|---|---|---|---|---|
| FF-32 | One stand-in, in the fixture; delete the services/tables copy; a parity test that runs one table of operations through the stand-in and through the real House Tables service (H2 with `schema.sql`, or embedded MySQL) and diffs the results | RF-062 (items 3 to 5 and 13 by the parity test), RF-012 (test side) | `tables-test-fixtures/.../HouseTablesH2Repository.java`, `services/tables/src/test/.../e2e/h2/HouseTablesH2Repository.java` (deleted), its `SpringH2Application`, the duplicate `HouseTablesH2ReadAfterCommitTest`, a parity test in `services/housetables` | none | Published fixture semantics (BC-039): consumers relying on silent no-ops get exceptions | The parity test fails today on restore over a live table (409 against an overwrite) and on rename of a missing row (404 against a no-op) | Ready (rule); may pass 20 files with the tests that rely on the infidelities: then split the fidelity changes into FF-33 |
| FF-33 | Fidelity changes: the real DDL lengths, case-insensitive keys, entity type stamped, `DataIntegrityViolationException` mapped to 409, no UUID stored, `storageType` `hdfs`, per-context lock and soft-deleted map, sorted paging, the page-count bug; the H2 setting where it cannot be silently overridden, with a test asserting the effective URL | RF-062 (items 1, 2, 6 to 12), BC-039 | the fixture stand-in, `SpringH2TestApplication`, the properties file, tests | FF-32 | Published fixture semantics; release note for consumers | A save over a view row with a table version fails; `DB.T` over `db.t` is 409; a 300-character location is accepted | Ready (rule) |

### Cruft that is safe to remove before a re-implementation

Private ids in messages that reach users come first; the rest only touches comments, dead code and local duplication.
Folding `src/shared` into `src/main` (193 pure renames, BD) is not on this list: it is far over 20 files and belongs to
the re-implementation (`87-clean-reimplementation-guide.md`).

| Id | What it does | Closes | Files | Depends on | Behavior | Tests first | Ready? |
|---|---|---|---|---|---|---|---|
| FF-34 | Remove private work-item and decision ids from every message a user or developer sees: `FormatV3Types.java:119-120` ("work item X5"), `FormatVersionCommitPolicy.java:134` ("W8"), `:142` ("X7"), `gradle/iceberg-fork.gradle:63-86` ("decisions D7 and I6d"), any IRC error text naming D-ids, and the tests asserting those texts | RF-045 | those files and their tests (`FormatV3*Test`) | none | Message text of new refusals changes | A test greps main sources for `work item` and `D[0-9]+` inside string literals and fails today | Ready (rule) |
| FF-35 | Remove private ids and history narration from main-source comments and build files: `IcebergRestWriteHandler.java:77, 90, 230, 351, 465, 504, 681, 691, 1063`; `IcebergRestViewHandler.java:46, 50, 132`; `IcebergRestViewSpelling.java:103`; `IcebergRestScanController.java:28-29, 67`; `IcebergRestScanPlanner.java:24, 60, 71, 90, 118, 183`; `IcebergRestApiHandler.java:384`; `IcebergRestTableFileIo.java:17`; `PolicyTag.java:32`; `PoliciesSpecMapper.java:159`; `MainApplicationConfig.java:200`; `HouseTablesSpringApplication.java:33`; `ReadBridgeConfigResolver.java:36`; `TableCommitCore.java:94-95`; `CreateHistoryScope.java:10`; `CreateHistory.java:18`; `OpenHouseInternalRepositoryImpl.java:182`; `OpenHouseInternalTableOperations.java:435`; `FormatVersionRule.java:61`; `FormatVersionCommitPolicy.java:33`; `OpenHouseTableOperations.java:184`; `IcebergLine.java:12-13`; `spark-3.5/.../OpenHouseCatalog.java:31`; `RestCapabilityClient.java:26`; `AppConstants.java:25`; `Operations.java:564-566`; `TablesClientFactory.java:77`; and the build files (`settings.gradle`, `gradle/iceberg-fork.gradle`, the capabilities, Spark 4.2 and `apps/spark-3.5` build files) | cruft (CW, RR, VC, CS, FV, SP, JB, BD section 4) | the files listed; split by module if over 20 files | none | None | The same grep as FF-34, over comments, scoped to `src/main` and build files | Ready (rule) |
| FF-36 | Dead code and guards that guard nothing, service side: `IcebergUpdates.java:35-52` (unused overload); `TableCommitService.commit(TableCommit)` private; `HouseTableRowLimits.requireTableIdFits` and its test-only constructors; the unused `@Named` at `PoliciesSpecMapper.java:167`; `@ConditionalOnClass` on `IcebergRestScanController.java:58` and `IcebergRestRegisterViewController.java:31`; `IcebergV3.nextRowId` moved to tests; the UUID branch of `uses()` (`IcebergRestWriteHandler.java:849-855`) and its javadoc | RF-012 (code), cruft | the files listed | FF-32 (the UUID branch's tests) | None | Existing tests stay green; the directory-in-use tests run on a stand-in without UUIDs | Ready (rule) |
| FF-37 | Dead code, client and build side: `IcebergCatalogMapper` and its never-running test; the Scala `Principal` object; `-PicebergFork` and `HARNESS_ICEBERG_FORK`; the 1.2 arm of `IcebergLine`; the unused `default` builder stage of the Spark dockerfile; the kit's empty `test` task; the orphaned javadoc at `OpenHouseRestAdapter.java:240` | cruft (CS, BD, CF) | the files listed | FF-16 (`CONFIG_OVERRIDES`) | None | Existing tests stay green | Ready (rule) |
| FF-38 | Local duplicates: `FormatVersionRule` and `FormatVersionCommitPolicy` as beans (seven `new` sites); the strip-skip block moved inside `prepare` (four copies); one `<table>-<uuid>` parser (`IcebergRestWriteHandler.java:618-650` and `:754-795`); one memoizer; `JobsLine.restCatalog` calls `Operations.isRestCatalog`; one Spark 4.2 classpath test; `IcebergRestRequirements.isReplace` shared with the seam's copy | cruft (CW, RR, FV, JB, CS) | the files listed | none | None | Existing tests stay green | Ready (rule) |

### Remaining defects

| Id | What it does | Closes | Files | Depends on | Behavior | Tests first | Ready? |
|---|---|---|---|---|---|---|---|
| FF-39 | (reserved: merged into FF-35) | | | | | | |
| FF-40 | The v3 switch bounds `cluster.iceberg.format-version` (refuse to start, or clamp, above 2 unless the switch is on); an upgrade to v3 is logged and counted, and the format version is exposed on the native GET as a new additive field | RF-040, RF-041 | `FormatVersionRule.java`, `ClusterProperties.java`, `OpenHouseInternalRepositoryImpl.java`, `GetTableResponseBody`, its mapper, tests | none | New fix; the new response field is an API addition | A context with `format-version=3` and the switch off fails to start; an upgrade emits one log line and one counter | Needs the orchestrator's decision (start failure or clamp; the new field) |
| FF-41 | IRC write route small fixes: a view update on the table route answers 400; the commit answer is the committed metadata, not a reload; a completion with snapshots and no location is refused explicitly; the lock-flip check null-safe for IRC callers (the native NPE stays pinned); the replace-guard javadoc corrected | RF-006, RF-007, RF-008, RF-009 (IRC side), RF-004 (doc) | `IcebergRestWriteHandler.java`, `IcebergRestErrors.java`, `TableCommitCore.java`, `IrcWriteRoutesTest.java` | FF-01 | New fix, new routes | Each case has a failing test first | Ready (rule) |
| FF-42 | IRC read and register small fixes: soft-deleted rows read before live rows; one case rule for both namespace lookups; per-table `io-impl` only for a known storage class and never over a client's own; read-bridge config in create, commit and register answers; register's v3 refusal advertised and the unreachable v3 branch of `create()` removed | RF-013, RF-014, RF-015, RF-016, RF-017 | `IcebergRestApiHandler.java`, `IcebergRestWriteHandler.java`, `IcebergRestTableFileIo.java`, tests | FF-32, FF-33 (case-insensitive stand-in) | New fix, new routes | `HEAD /namespaces/DB` answers the same with and without a table in `db`, on a case-insensitive stand-in | Ready (rule) |
| FF-43 | Seam and capability-client small fixes: refuse `prefix` in the in-process seam; narrow its stale-base javadoc or wrap its transactions; one REST transport per catalog with an explicit close | RF-035, RF-036, RF-037 | `OpenHouseRestCatalog.java`, `OpenHouseRestAdapter.java`, both `RestTransport.java`, tests | none | New fix, opt-in paths only | A catalog with `prefix` fails at `initialize` with a clear message | Ready (rule) |
| FF-44 | `Operations` fails fast after `close()` instead of rebuilding a catalog | RF-050 | `Operations.java`, `OperationsCatalogTest` | FF-17 | New fix (no production caller after close) | `getTable` after `close()` throws | Ready (rule) |
| FF-45 | CI test small fixes: the v3-types skip keyed on `IcebergLine`, not on any exception; the grant test model documented as the tests' own; optionally the kit failure listing kept | RF-067, RF-066 (doc), RF-068 | `TableStatsFormatV3TypesTest.java`, `GrantStoringAuthorization.java`, the workflows | none | None | A parse failure on the default line fails the test | Ready (rule) |
| FF-46 | Inherited defects, each its own behavior-changing, gated change with its own justification: a base check on IRC replaces (RF-004), the retry cache's semantics after a failed write (RF-005, fault-injection test first), the native directory-reuse fix (RF-030), the soft-delete column width (RF-031, an upstream issue), sort stats on `jobs.yaml`'s session (RF-048), retention backups and deletes (RF-049) | RF-004, RF-005, RF-030, RF-031, RF-048, RF-049 | per defect | none | New fixes to main's behavior, each gated | per defect | Needs decisions: RF-004 and RF-005 the orchestrator's; RF-030 the owner's (D35); RF-048 the owner's (X17); RF-049 the owner's (D46); RF-031 an issue only |
| FF-47 | CI dependency resolution: read-only CI jobs take dynamic-version lookups (the `[1.3.1,2.3]` range of `json-smart` through `nimbus-jose-jwt`, `mysql-connector-java:8.+`, the `log4j-core` and `logback-core` ranges) from the restored Gradle cache instead of a Maven Central round trip, by an init script that sets `cacheDynamicVersionsFor` to 20 days (a longer value overflows an int of milliseconds at 24.8 days and makes every lookup stale); the cache writer downloads every configuration's files (`ciResolveDependencies`, `scripts/ci/resolve-all-dependencies.gradle`); the same step for the dataloader integration job | CI failures of three jobs on 2026-11-27 (`Repository maven is disabled`, 429) | `scripts/ci/`, the init script, `.github/workflows` (split build, dataloader tests) | none | None for the product; CI only | Resolution identity on both lines (14 dependency reports and every resolvable configuration identical, probed); the pull request's run shows 0 maven-metadata requests in every read-only job (2 without the script) | Pull request 155 in review; cause and evidence in `records/ci-dynamic-version-resolution.md`. Known remainder: one request per reader job for the Spring Boot plugin marker of `libs/optimizer/*` (a missing artifact Gradle caches for 24 hours; not fixable by a public setting) |
| FF-48 | The `/iceberg` table routes answer 403 where the view routes and the native routes answer 503 when the authorization service itself fails: in `IcebergRestErrors.toError` test `AuthorizationServiceException` (a subclass of `AccessDeniedException`) before `AccessDeniedException`; a test per route family | review of pull request 152, F3 (`records/pr-review-152.md`); inherited by the new early decisions | `api/irc/IcebergRestErrors.java`, route tests | FF-01 | New fix on new routes; fails closed today | An authorization backend that throws `AuthorizationServiceException` gets 503 on table load, commit, transaction and empty commit (403 today) | Not started |
| FF-49 | Private ids out of two build files (`integrations/java/openhouse-catalog-capabilities/build.gradle:1` "(decision D23)", `apps/spark-3.5/build.gradle:77-78` "work item X5"; the test file `FormatV3MaintenanceTest` also names X6, X9, X10, X12); the CI check that the apps jar has no `log4j2.properties` fails when `unzip` fails (write `unzip -Z1 "$jar" > entries.txt` first, then `grep -qx`); the capabilities module's comment says that publishing makes its API public and that the artifact's content depends on the Iceberg line | review of pull request 154, F5, F8, F9, F1 (comment) | the two build files, `.github/actions/default-build-deployables/action.yml` | none | None | The check step fails with a missing or unreadable jar | Not started; merges with FF-34 where the files overlap |
| FF-50 | The namespace stack's follow-up in this repository: the delta-harness expectations for the 1.5.2 line (`NegativeDdlScenarios.scala:231-241`, `SurfaceScenarios.scala:36-37`; the three cases that now fail, and the leftover `a_new_db` and `nope_ns` records on a second run); `DEPLOY.md` for the new table, the DDL, the MySQL 8 requirement and the second namespace API; the 405-versus-handler cases of the route boundary test if the prefix is ever validated | review of pull request 153, F5, F6 (`records/pr-review-153.md`) | `integrations/spark/delta-harness/`, `services/tables/DEPLOY.md` | none | None | The harness run on the 1.5.2 line passes; `DEPLOY.md` names the table | Not started. His own files (`NAMESPACES.md`, `docs/specs/catalog.md`, `housetables.md`) are put to him, not edited (`58-waiting-on-mike.md`) |

FF-39 was folded into FF-35 while writing; the id is kept so later references do not shift.

### Decisions the work list waits on

- The orchestrator: FF-04 (scan planning off by default), FF-11 (the IRC base property and the fork's own default),
  FF-13 (D43 item 5 against D11(e)), FF-16 (publish the capabilities module or fold it), FF-25 (whether a view creates
  its database), FF-26 (the reference line as the release line), FF-40 (start failure or clamp; the new GET field),
  FF-46 (RF-004, RF-005).
- The owner: FF-05 (the privilege for manifest contents), FF-46 (RF-030, RF-048, RF-049), the hosting behind FF-29's
  group id and BC-035, and the seven OWNER rows of the ledger (BC-024, BC-025, BC-028, BC-029, BC-030, BC-033, BC-035),
  which decide whether the default line's foundation consequences stand.
- The scout: FF-03.

## 3b. Open point: which privilege a view read needs

Stated plainly, not resolved: the view load finding (RF-018) depends on which privilege an Iceberg REST view read should
need. The native view GET is pointer-only and makes no authorization decision; the definition is protected by storage
permissions. Over IRC the service reads the definition with its own credentials, so some privilege must be checked, but
which one (`GET_TABLE_METADATA` on the view's authorization reference, as VC proposes, or something else) is a question
the owner's earlier decisions about views may already answer. A scout is reading those decisions separately. FF-03 is
therefore marked "waits for the scout" and must not start until that report is in.
