# Review of pull request 158 (irc/rest-authorization-and-exposure-gaps, head e84034c9)

Reviewer: independent sub-agent, read only. Diff read against the merge base 0d54d2fd (= irc-integration before #157).
origin/irc-integration is now 518a2134 (#157, namespace writes), which this branch has NOT merged.

## 1. Verdict

MERGE AFTER FIXES. Close M1 and M2 first.

The three things that matter most:

1. The branch does not merge into the current base, and once it does, a test that #157 added fails. GitHub reports the
   pull request `mergeable: false, mergeable_state: dirty`. A read-only `git merge-tree 0d54d2fd HEAD 518a2134`
   shows 3 conflict hunks in 2 test files, plus 5 more files changed on both sides. The real conflict is in meaning:
   #157's `IrcNamespaceRoutesFailureTest.whenTheAuthorizationServiceFailsANamespaceThatHoldsATableReadsAsBefore`
   expects 403 `ForbiddenException` for an authorization-service outage, and it gets that answer "through the IRC
   error mapping", which item 3 of this pull request changes to 503. After the merge that test fails (M1).
2. No CI has run on the head. Because the pull request is in conflict, the pull_request workflow did not start for
   e84034c9: the check-runs list is empty, and the combined status is `pending` with 0 statuses. The only run is on
   0df81df6, and its `Default build, rest` job failed with OOM: Java heap space in `:services:tables:test`
   (`ViewsCorruptPointerH2Test`, 2 tests). The heap raise, 99ef2efa, has never run in CI (M2).
3. The code itself holds up under attack. The early decision runs the core's three checks, then the read check,
   before any requirement is checked or any update applied, on every route that commits to an existing table. A
   write-only caller learns only that the table exists, and on a legacy-locked table that it is locked, which is
   what the native PUT tells anyone. The only native change is the extraction of the core's checks into one method,
   which keeps their behavior. With planning off, plan and credentials cannot be reached by any route. The remaining
   findings are a narrow race after a successful commit (S1), and an explanation of the heap growth that comes from
   the test harness, not from a production leak (S2).

## 2. Findings

### M1. MUST FIX BEFORE MERGE: merge the current base and flip #157's outage pins to 503

- **What goes wrong.** #157 (518a2134) added `IrcNamespaceRoutesFailureTest`. In
  `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/IrcNamespaceRoutesFailureTest.java:193-216`
  (at 518a2134) it pins:

  ```
  * ... The IRC error mapping answers it 403
  * {@code ForbiddenException}, as on every IRC route that asks the authorization service (the
  * failure type is a subtype of the denial, which the mapping tests first); pinned as it is.
  ...
          assertIrcError(result, 403, "ForbiddenException").message());
  ```

  This pull request's `IcebergRestErrors` now tests `AuthorizationServiceException` before `AccessDeniedException`
  and answers 503 (`IcebergRestErrors.java:119-123`). After the merge, the namespace load, create, update and drop
  answer 503, and this test fails.
- **The textual conflicts** (from `git merge-tree`):
  - the class comment of `IrcReadRoutesTest.unservedSpecRoutesAre406UnsupportedOperation`;
  - in `tables-test-fixtures/.../IcebergRestRoutesBoundaryTest.java`, the javadoc and the endpoint count. #157 has
    `22 + (planning ? 2 : 0) + (registerView ? 1 : 0)`; with planning off by default it must become
    `22 + (registerView ? 1 : 0)`, and its `planning` variable must go.
- **Changed on both sides, merging cleanly but needing a re-check:** `IcebergRestApiHandler`,
  `IcebergRestCatalogController` (#157 removes the namespace entries from `UNSERVED_SPEC_ROUTES`),
  `IcebergRestErrors` (#157 adds `NamespaceNotEmptyException`), `IrcConfigEndpointsTest`, `IrcWriteRoutesTest`.
- **How sure:** certain from reading, for the merge state and the failing pin. Whether more namespace pins flip
  cannot be told without running; `IrcNamespaceRoutesAuthTest:123` pins a 403 for a denial, which should stay 403.
- **Fix:**
  1. Merge origin/irc-integration (518a2134).
  2. Change the namespace outage pin, and its javadoc, to 503 `ServiceUnavailableException` with the backend's
     message. Add the namespace routes to the author's "Behavior changes" row for the outage.
  3. Resolve the boundary-test count as above.

### M2. MUST FIX BEFORE MERGE: a green CI run on the merged head

- **What we know.** On 0df81df6 every job passed except `build-run-tests / Default build, rest` (job 140841867121):
  - `:services:tables:test` ran 901 tests with 2 failures, both `ViewsCorruptPointerH2Test`
    (`incompleteStoredPointerIsASanitizedServerFault`, corruption=blankTableLocation and blankStorageType), each
    "Failed to load ApplicationContext ... OutOfMemoryError: Java heap space". Gradle Test Executor 13 also died
    with OOM.
  - The kit (`restCompatKitTest`, ignoreFailures) ran 160 tests, 95 failed, 23 skipped. The failed list is identical
    to the base run at 4d9362ee (I compared the lists of FAILED test names: no difference).
- **What is missing.** No run exists for 99ef2efa or e84034c9, so the claim that 768 MB fixes the OOM rests on a
  local run only. #157 adds about 1,950 lines of tests, including new Spring test classes (`IrcNamespaceRoutes*`,
  `IrcRestCatalogNamespaceTest`), so the merged suite needs more heap than the suite measured.
- **How sure:** certain (gh API: empty check-runs, combined status pending with 0 statuses, PR `dirty`).
- **Fix:** after M1, push and wait for all jobs, in particular both `rest` jobs and the Spark 4.2 itest jobs.

### S1. SHOULD FIX: a commit that succeeded can be answered 500 when another write lands before the answer

- **Where.** `IcebergRestWriteHandler.answered`, `IcebergRestWriteHandler.java:1317-1331`:

  ```
  Table table = written != null ? written : stored.getTable();
  return new IcebergRestLoadedTable(
      committed,
      TableMetadataParser.read(table.io(), committed),
  ```

- **What goes wrong.** When another write lands between this commit and its answer, the committed file is read
  again. If that read fails, the exception is Iceberg `NotFoundException` or `RuntimeIOException`. Neither is mapped
  in `IcebergRestErrors`, so both answer 500 `ServiceFailureException`. The read fails when:
  - a later commit deleted the file (`write.metadata.delete-after-commit.enabled` with a small
    `previous-versions-max`), or
  - the table was dropped and recreated in between (the author's own reading).
- **Consequence for clients.** The commit error handlers of both client lines read 500 as
  `CommitStateUnknownException`, so the client keeps the published files: no orphan cleanup of live files, which
  was the dangerous mode. But the client's job fails although its commit landed. Before this pull request, that race
  was answered 200 with the later version.
- **Older, unchanged edge.** The `loadBase` at the start of `answered` still answers 404 `NoSuchTableException` after
  a successful commit when the table was dropped in between. A client reads that as a failed commit and cleans up,
  but the table is gone, so nothing live is lost. This was not introduced here.
- **How sure:** certain from reading. The frequency is low: it needs the race plus a cleanup or drop. It can wait
  for a later pull request because the failure is "state unknown", which is safe for the client's data.
- **Smallest fix:** catch the read failure in the fallback branch. When `stored` is the same table (same table UUID
  as `written`), answer `stored` as before (the later version, which is what the base did). Otherwise throw
  `CommitStateUnknownException` explicitly, so the type says what happened.
- **Settling test:** extend `aCommitAnswersTheVersionItStoredNotALaterOne`: delete the committed metadata file in the
  racing writer, then assert the answer.

### S2. SHOULD FIX: the heap raise, explained

- **Count of Spring contexts.**
  - Spring test classes: 132 on both sides. Classes with `@DirtiesContext`: 25 to 27.
  - Net new context creations: 2. `IrcPlanCredentialsRoutesTest` and `IrcPlanCredentialsAuthTest` used to share
    cached contexts and now each have `properties = PLANNING_ON` and `@DirtiesContext(AFTER_CLASS)`.
  - The five 1.12 planning classes (`IrcScanPlanningTest`, `...LateColumnTest`, `...FormatV3Test`,
    `...BoundTest`, `IrcScanPlanCostTest`) already had a context of their own (`@DirtiesContext` and their own
    `@Import`). Adding the property does not create another one.
- **Why the count alone does not explain it.** The cache is capped at 8 (`spring.test.context.cache.maxSize=8` in
  `services/tables/build.gradle`), and closed contexts are not kept. If closed contexts were fully collected, the
  peak heap would not depend on how many contexts the suite creates, and two more would not push it over 512 MB.
- **What does retain memory per context: the test harness, not this change.** Spring Boot 2.7.8's
  `MeterRegistryConfigurer` adds each context's MeterRegistry to the static `Metrics.globalRegistry`. I checked the
  bytecode: a `getstatic Metrics.globalRegistry`, and no removal path. The service's
  `MetricsReporter.of(...)` (`cluster/metrics/.../MetricsReporter.java:24`) records on `Metrics.globalRegistry`. So:
  - every closed context's registry stays reachable;
  - every meter recorded later is created in every registry ever added.

  Memory therefore grows with the number of contexts the run has created, not with the number cached.
  `ViewsApiHandlerAuditTest:330` and `ViewsManagedFailureAuditTest:882` assert that the context's registry is in
  the global one, which confirms the mechanism is live in this suite. How sure: likely. I did not take a heap dump.
- **Production objects of this pull request:** none leak.
  - `IcebergRestBoundedPlans` is created once per controller in `@PostConstruct`, and `@PreDestroy` shuts it down
    (`shutdownNow`). Its threads are daemon threads.
  - `IcebergRestMetricsReports` has no state. Its request attribute lives for one request.
  - The new `TableAuditAspect` advice has no state.
  - The controller exists only with planning on, and its executor is shut down when the context closes.
- **The raise is acceptable for now; smallest reductions:**
  1. Give the two credentials classes one identical context configuration (one creation instead of two).
  2. Better: a test-execution listener, or a `@PreDestroy` in a test configuration, that calls
     `Metrics.removeRegistry(meterRegistry)` when a context closes. This removes the growth for the whole suite.
- **Why it can wait:** test only. A production process has one context.

### S3. SHOULD FIX: the view commit answers the view as stored now, not the version it stored

- **Where.** `IcebergRestViewHandler.commitView`:

  ```
  onView(() -> viewsApiHandler.updateView(body, actingPrincipal));
  return onView(() -> response(loadReadable(databaseId, viewId, actingPrincipal)));
  ```

- **What goes wrong.** Item 2's rule ("answer the version the write stored, never a later one") holds for tables
  only. A racing view replace is answered as this commit's result. The caller is authorized for it, because the read
  decision is made again on the row read, so nothing leaks. After a successful update, a 403 or 404 from this
  reload tells the client that the commit failed. For views that costs no data.
- **How sure:** certain from reading.
- **Fix:** answer from the metadata location that `updateView` returns, or state the difference in the javadoc and
  in the author's behavior-change table.

### S4. SHOULD FIX: no Spark run exercises the default server configuration (planning off)

- **Where.** `IrcReadTestService` and `IrcSmokeTestService` (Spark 4.2 itest) now always pass
  `--cluster.tables.irc.scan-planning.enabled=true`, for the default smoke runs too. So the default deployment's
  answer (plan and credentials 406, not advertised) is tested only in `IrcConfigEndpointsTest`,
  `IrcReadRoutesTest` and `IcebergRestRoutesBoundaryTest`, never end to end with a Spark client.
- **Risk:** low. A 1.12 client plans on the server only when configured to.
- **Fix:** turn the property on only in the server-planning runs (`testServerPlanning`, `smokeTestServerPlanning`).
  Or note in `DEPLOY.md` that the default is not exercised by Spark.

### N1. MINOR: the early decision for TABLE_UPDATE skips the core's first check

- **Where.** `TableCommitCore.commit:71-75`. For `TABLE_UPDATE` the core runs `checkIfLockPoliciesUpdated` before
  `checkWriteAccess`. The IRC early decision runs only `checkWriteAccess` and the read check.
- **Effect.** A patch that flips the lock on a legacy-locked table answers 400 "locked state ... cannot be updated".
  The native PUT would answer 400 "Lock state cannot be updated" first. Same status, different message, `/iceberg`
  only. The IRC order is the stricter one: the native rule reveals the stored lock state to any caller.
- **Fix:** none needed. State it in the `authorize` javadoc, which now says "in the core's order".

### N2. MINOR: only the client-capability gate is undecided before stored ids can be answered

- **The window.** A body with updates that turn out to be no-ops has its requirements checked
  (`IcebergRestRequirements.validate`, which can name stored ids in a 409) after write, read and SYSTEM_ONLY are
  decided. The one load check not yet run is the client-capability gate (`checkLoad` in `answerAsALoad`).
- **Effect.** A caller with read can get stored ids in a 409 from a client that the load's compatibility gate would
  turn away. That gate concerns compatibility, not privilege, so nothing is learned that the caller may not read.
- **Certainty:** certain from reading.

### N3. MINOR: two IRC refusals are not audited

- `withTheLockRule` (`IcebergRestWriteHandler.java`) refuses a lock flip before `tableCommitService.commit`, so the
  TableAuditAspect commit advice never sees it: no FAILED UPDATE event. The native lock-rule refusal is audited,
  because it runs inside the core.
- A view update sent to the table commit route (the new `applyTo` 400) is likewise not audited. That is the same
  pattern as other request-shape failures before the commit, so it is consistent, but the lock refusal is a policy
  refusal.

### N4. MINOR: the time bound stops planning only partly

- `IcebergRestScanPlanner.plan` checks `isInterrupted()` only between planned tasks.
- On a multi-manifest table, Iceberg plans on the shared worker pool. There, `ParallelIterable` sleeps and throws on
  interrupt, and closing the iterable cancels the workers, so the work stops.
- On a single-manifest scan whose filter matches nothing, the loop body never runs, and the manifest is read to its
  end on the plan thread.
- The concurrency bound still holds, because the thread stays occupied. The answered plan is the timeout's either
  way. Documented by the author; acceptable.

### N5. MINOR: other small points

1. In the race fallback for a create (`written == null`), `answered` reads the committed file with
   `stored.getTable().io()` and answers `tableFileIo.config(stored.getTable())`. When the table was dropped and
   recreated, that is the new table's FileIO config. The storage is the same, so there is probably no effect.
2. `requirePositive` replaces the old "not positive means default" reading of `max-file-scan-tasks` (default 0
   before, 10000 now). The property exists only on this fork, and no config in the repository sets it, so failing
   to start is acceptable. Nothing in the field has the property. The refusal is tested on the static check only,
   not on a context start.
3. A busy 503 bypasses the IRC exception handler, so the service audit records it without a message (the author
   notes this).
4. The rename's new 409 `CommitFailedException` for a House Tables conflict that is not about the key: the client
   is unchanged, because both clients' table error handler throws `AlreadyExistsException` for any 409. The
   specification lists 409 for rename only as "already exists", so a 409 with a commit-failed type is outside the
   specification; a 503 or a 500 would describe it better.
5. Rewritten comments keep old private ids (`D44`, `D45`, `V11c` in `IcebergRestScanPlanner` and
   `IrcConfigEndpointsTest`). The lines existed before; no new ids were added.

## 3. Behavior changes

My table. Native routes: none change.

| Change | Path | Default | Direction | Justified in code |
|---|---|---|---|---|
| `TableCommitCore` checks extracted to `checkWriteAccess`, same order and messages | native PUT, snapshots PUT, IRC | n/a | none (pure refactor) | yes |
| `ServiceAuditAspect` reads `AUDITED_REQUEST_PAYLOAD` if it is a `JsonElement`; only the IRC metrics route sets it; a client cannot set request attributes | all services' audit | n/a | none for native | yes |
| `TableAuditAspect`: READ advice on `IcebergRestApiHandler.loadIcebergTable`, whose only callers are plan and credentials | IRC, planning on | off | new route | yes |
| Plan and credentials not registered (406, not advertised) | IRC 1.12 | yes | toward main | `ClusterProperties`, `DEPLOY.md`, controller javadoc |
| Authorization outage: 403 becomes 503 | all IRC table routes; after the merge also the namespace routes (M1) | yes | toward native | yes |
| Commit or transaction with updates needs GET_TABLE_METADATA | IRC | yes | away from native PUT, IRC only | `authorize` javadoc |
| View commit needs read for every commit (before: only with no updates or no-op updates) | IRC views | yes | IRC only | yes |
| Legacy or SYSTEM_ONLY lock decided before requirements, for every caller with updates (400, 423) | IRC | yes | toward native | yes |
| Commit, create, staged-create completion, register, create-with-data answer the committed version in a race | IRC | yes | new | yes. Adds the S1 failure mode |
| Rename onto a view: 409 before any write; other House Tables conflicts: 409 CommitFailed | IRC | yes | IRC only | yes |
| Metrics body capped at 1 MiB (400); audit payload is identity and size | IRC | yes | IRC only | yes |
| View update on the table commit route 400; lock patch on a table without a lock state 400, or dropped | IRC | yes | away from native's NPE 500 (native kept) | yes |
| Non-positive planning bound: startup refused (was: default used, for the task cap) | planning on | n/a | config | yes |
| Plan READ audit; plan 503 with Retry-After (fork OpenAPI lists 503 for `planTableScan`; the 1.12 `ExponentialHttpRequestRetryStrategy` retries a 503 that has Retry-After even for a POST); time bound answers `failed` | planning on | off | new | yes |
| Test worker heap 512 MB to 768 MB | build | n/a | test only | yes (S2) |

Differences from the author's table:

- The namespace routes' outage answer flips too, once merged (M1).
- The view commit's answer is not "the version stored" (S3).
- The new 500 after a successful commit in the race-and-read-failure case is not listed (S1).
- The author says the "Rename ... Native unchanged (still 500)" row is new since main. Correct.

## 4. Tests

- **`IrcWriteRoutesAuthTest`.** Uses deny-one-privilege-allow-the-rest throughout (`denyOnly`).
  - `aWriterThatMayNotReadIsRefusedACommitAndNothingIsWritten` compares the 403 message with the native GET's
    refusal (an independent source), checks no stored id, a FAILED UPDATE audit, and that nothing is written. Then
    allowed gives 200. It can fail for the right reason.
  - `onALockedTableAFailingRequirementIsTheNativeRefusalAndTellsNothingStored` compares with the native PUT's
    message for each denied privilege, and the 423 for SYSTEM_ONLY. Good.
  - `anAllowedWritesAnswerIsTheStoredTableByteForByte` builds its expectation with the base's old `loaded()`
    formula (`storedAnswer`). That is "the answer as before", which is the claim; it is not independent of
    OpenHouse, but it is independent of the new branch logic. It would catch a spelling mismatch of the location
    (the fallback answers `committed`). It covers commit, snapshot commit, create and staged completion. It does
    not cover create-with-data (`putIcebergSnapshots` response location) or register.
  - `aCommitAnswersTheVersionItStoredNotALaterOne` makes a real concurrent commit from the audit hook, checks that
    the later property is absent, and checks the metadata-log predecessor. It can fail.
  - The outage test is renamed and now pins 503 with the native message on load, empty commit, commit and
    transaction. Correct.
- **Changed pins** (`onALegacyLocked...`, `updatesIcebergRecordsAsAChangeAreWrites`, `aCommitThatChangesNothingIsALoad`
  audit kind, `IrcPolicyPatchTest.assertSameOutcome` declared difference, `IrcConfigEndpointsTest`,
  `IrcReadRoutesTest`, `IcebergRestRoutesBoundaryTest`): each new expectation follows from the stated change, and
  the old ones were the gaps being closed.
- **`IrcScanPlanCostTest`.** The `bound(0)`, `bound(-1)`, `bound(7)` assertions were removed because `bound()` is
  gone. They are covered by `IcebergRestBoundedPlansTest.aBoundThatIsNotPositiveIsRefusedAtStartup` (7 passes; 0 and
  -1 refused) and by the default value in `ClusterProperties`. The 10,000 default is still asserted on the constant,
  and end to end by the 10,001-task table.
- **`IcebergRestBoundedPlansTest`.** Real latches. It tests busy, permit release, timeout with interruption and the
  thread held, rethrown failure, and the static startup check. It does not cover the route's 503, its Retry-After
  header, or the timeout through HTTP (the author says so).
- **`IrcPlanCredentialsRoutesTest.aPlanAndACredentialsCallAreAuditedAsTheLoadIs`** compares with the load's own
  events (one event), for an existing and a missing table. Good.
- **`IrcConfigEndpointsTest.scanPlanningAndCredentialsAreNotServedUnlessTheClusterTurnsThemOn`** checks: not listed,
  406 with the catch-all message, and no handler mapping ending in /plan or /credentials. It proves unreachability
  with planning off on both lines (shared test source).
- **`IrcServiceAuditTest` (metrics).** Identity and size payload, and a 400 for an oversized body. The oversized case
  goes through the declared Content-Length path only; the streaming count path (chunked, no Content-Length) is not
  tested.
- **`IrcViewRoutesTest` (rename).** No metadata file written, and the audit equals the native refusal's. Good.
  `IrcViewRoutesAuthTest`: the writer without read is refused, nothing is written, and the allowed answer equals
  the load's.
- **Unpinned claims:**
  - S1's failure path;
  - rename `CommitFailedException` (the author says so);
  - the startup refusal on a real context;
  - the default-off configuration under Spark (S4).

## 5. Checked and found sound

- **Item 1.** `authorizeWrite` delegates to the core's own `checkWriteAccess`. It is called by `authorize` on the
  table commit, replace (same route) and one-table transaction. Each route runs only id validation, the shape check,
  `loadBase` (404) and `isCreate` (request only) first. Staged-create completion and register are creates: no stored
  table, and the native create order (CREATE_TABLE) applies. On an existing table, the completion answers only
  "already exists" (`validateCreate` before `validate`). The legacy-lock message equals the native PUT's (asserted).
- **Item 2.** The read is decided inside the same `auditRefusal`, before `commitOn`, so before requirements and
  updates. A write-only caller learns existence and, for a legacy lock, the lock: native parity. The decision is on
  the stored `TableDto` for the acting principal. In the no-race case the answer is constructed identically to the
  base. Creates and register answer the creator with no read decision, as native. The transaction requires read too.
- **Item 3.** No other IRC catch maps the outage to 403. The view handler already maps it to 503. The branch order is
  right; no other shadowed pair was found in reading.
- **Item 4.** `@ConditionalOnProperty(havingValue="true")`, and matchIfMissing is false by default. The catch-all
  lists the plan and credentials patterns as unserved spec routes. `IcebergRestServedRoutes` reads the registered
  mappings. 1.5.2 has no controller. The permit is a pool thread with no queue: it is released on every path because
  the future completes, and a timed-out plan keeps it while it runs, which is the intent. A slow-reading client
  holds no permit, because serialization happens on the request thread after the plan returns. The READ audit is
  emitted once per call through the advised proxy method, before planning, so busy and failed plans also have
  exactly one event.
- **Item 5.** The cap is enforced while reading (declared length checked first, then counted per chunk). The
  attribute is request-scoped, set only by the metrics route, and ignored unless it is a `JsonElement`, so native
  audit payloads are unchanged. `ServiceAuditAspect` lives in services/common: housetables and other services are
  unaffected.
- **Item 6.**
  - Key: target database and destination name.
  - Order: validation, cross-database handling, then the view lookup, which only answers when the source exists.
    That is the native rename's order exactly (source 404, then target 409, both before any privilege decision in
    `TablesServiceImpl.renameTable:431-458`). So nothing beyond native leaks.
  - Race: a view created after the lookup falls back to the House Tables refusal, which is still answered 409.
  - Client: both 409 shapes reach clients as `AlreadyExistsException`.
- **Item 7 and shared classes.** Native PUT keeps its NullPointerException 500: `checkIfLockPoliciesUpdated` is
  unchanged, and `withTheLockRule` is IRC only. `TableCommitService.core()` is a pure extraction. `ClusterProperties`
  only adds fields. `TablesServiceImpl` is not changed.
- **The other line.** The reference build passed on 0df81df6: all its test groups, rest, apps-spark, and the Spark
  3.5 and 4.2 itests. The Spark 4.2 default itest passed with the property on.
- **The kit.** The failure set is identical to the base.

## 6. Not checked

- No build or test was run (read only). The heap mechanism in S2 is inferred from bytecode and code; no heap dump
  was taken.
- The merged state was not built: only `git merge-tree` and reading #157's tests.
- `DEPLOY.md` wording was not read in detail.
- The behavior of the 1.5.2 client against the 503 Retry-After was not checked (planning is not served there).
- OPA semantics, such as whether SYSTEM_ADMIN on a replica implies GET_TABLE_METADATA, cannot be told from this
  repository. If it does not, a replication principal holding only SYSTEM_ADMIN is now refused IRC commits.
