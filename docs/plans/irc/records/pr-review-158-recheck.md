# Re-check of pull request 158 at head e79cee5e

I read the code and CI only; I built and ran nothing. The base is origin/irc-integration at 96acfca3, and the
branch's merge base is 96acfca3. GitHub reports the pull request `mergeable: true, clean`.

## Verdict

**MERGE.** No finding is left that must be fixed before merge. Three findings can follow later (section 4).

## 1. CI on e79cee5e: every job finished, none failed

- **`Default build, rest`** (job 140885768705): success.
  - `IrcNamespaceRoutesFailureTest` ran its 4 tests and all passed, including the outage test, which now pins 503.
  - The compatibility kit ran 160 tests: 90 failed, 16 skipped. The list of failed tests is identical to the base's
    own run on 96acfca3 (run 46113789095); I compared the two lists of names and found no difference.
- **`Reference build, rest`**: success.
- **Default and Reference `spark-4.2-openhouse-spark-itest`**: success.
  - These planning classes passed: `SparkIrcScanPlanningTest` (3), `SparkIrcServerPlanningReadsTest` (9),
    `SparkIrcServerPlanningWritesTest` (17), `SparkIrcPlanBoundTest` (3), `SparkIrcServerPlanningOffTest` (1),
    `SparkIrcNoPlanRequestTest` (2).
- **All other jobs**: success, apart from `iceberg-linkedin-build / Default build, Docker images` and the two
  aggregate rows, which were skipped. That matches the base.

## 2. The findings of my first review

### M1: closed

- Both merges are in: 2a482f60 (#157) and e79cee5e (#160).
- `IrcNamespaceRoutesFailureTest` now pins 503 `ServiceUnavailableException` for an outage (lines 195 and 215).
- **How this PR's authorization fits the namespace layer of #157 and #160:**
  - `readRecord` (`IcebergRestApiHandler.java:533-545`) rethrows `AuthorizationServiceException` before it catches
    `AccessDeniedException`. That is the same order this PR uses in the error mapping, so an outage is never read
    as a denial.
  - All three namespace writes call `authorizeWrite` first (SYSTEM_ADMIN), before `holdsSomething`. An outage
    there answers 503 and a denial answers 403, both before anything about the namespace is answered.
  - On a load, a denial on the record counts as "no record". The namespace then exists only if it holds a table
    or a view, which anyone can already see. So a record-only namespace reads as missing (404) to a caller who may
    not read its record. That hides it and leaks nothing.
  - During an outage, a load of a namespace that holds nothing rethrows the failure, which answers 503.
- **One inconsistency, which comes from #160, not this PR:** see C1.

### M2: closed

CI is green on the head (section 1).

### S1: closed (`IcebergRestWriteHandler.answered`)

- **What happens now.** When the committed file cannot be read, the code compares the committed table's UUID
  (from the commit's own `TableDto`, or the create's response) with the UUID of the table stored now:
  - same UUID: it answers the table as stored now;
  - otherwise: it throws `CommitStateUnknownException`, which the error mapping answers 500 with that type.
- **What is read before which check.** Everything on this path comes after the request's write and read decisions
  on that same table, which has the same OpenHouse UUID. A later version of a table the caller was just allowed to
  read is something the caller may read; nothing is answered from another table. There is a theoretical window: a
  read privilege revoked between the decision and the answer is not decided again. That is negligible, and the
  base's reload had the same window.
- **The client, on both lines.**
  - The fork's 1.12 `CommitErrorHandler` reads 500 as `CommitStateUnknownException` (500, 502, 503, 504). So does
    LinkedIn's 1.5.2.24 (500, 502, 504; I checked the bytecode).
  - The 1.12 HTTP retry strategy does not retry 500.
  - A `CommitStateUnknownException` makes the client skip cleanup: no retry, no files deleted.
- **The tests fail without the fix.**
  - `aCommitWhoseStoredVersionIsGoneIsAnsweredWithTheSameTableAsStoredNow` deletes the committed file in a real
    race. Before the fix it got 500 `ServiceFailureException`.
  - `aCommitWhoseTableWasReplacedIsAnsweredCommitStateUnknown` does a drop and a recreate in the race, and asserts
    both the type and that no stored id appears in the answer. Before the fix the type was `ServiceFailureException`.

### S3: closed (`IcebergRestViewHandler.answered`)

- **The order.** The view is read back with `loadReadable`, which makes the read decision on the row read now.
  Only then is the committed file read, with the new `ViewsServiceImpl.readViewVersion`, which makes no decision.
- **Why that is enough.** The file it reads is the caller's own write, and the read decision is made first. If the
  name now belongs to a view the caller may not read, the answer is 403 and nothing is shown.
- **When the committed file is gone,** the same UUID rule as S1 applies, and it ends in `CommitStateUnknownException`.
- **Native routes are unaffected.** `ViewsServiceImpl` only gains this method, and its one caller is in the IRC code.
- **A small gap:** when `updateView` answers no metadata location, the code answers the view as stored now. That
  cannot happen with today's native handler.
- **The test** `aViewCommitAnswersTheVersionItStoredNotALaterOne` failed on the code before the fix.

### S2: closed (`4a68f782`)

- **Test sources only:**
  - `ReleaseMeterRegistriesOnClose` (in `src/test`), imported by the test applications `SpringH2Application` and
    `MockTablesApplication`;
  - the two credentials test classes;
  - the heap line in `services/tables/build.gradle`.
- **What the listener does.** On `ContextClosedEvent` it removes the closing context's own registries from
  `Metrics.globalRegistry`. `getBeansOfType` returns only that context's own beans, so the registries of contexts
  still open are kept.
- **What a test can still see.** A test asserts on meters of its own open context. A meter that only a closed
  context recorded is no longer reachable through the global registry, but no test could rely on that without
  being order-dependent, and the whole suite passes.
- **640 MB.** The author measured:
  - before the fix, registries grew from 21 to 95 over the run;
  - after it, 9 to 20, with a peak of 544 MB in use after a full collection;
  - at 512 MB the run passed with a peak of about 496 MB, which leaves no margin.

  That justifies 640 MB over 512 MB. Going back to 512 would invite flaky failures from running out of memory.
  About nine closed contexts are still reachable for a reason not yet identified (C2).
- **The two credentials classes now share one cached context.** The routes test sets its mocked authorization to
  allow everything before each test. The authorization test keeps its deny-one-privilege tests (`@MockBean
  AuthorizationHandler` at line 63).

### S4: closed (`08405765`)

- The default `test` task leaves out the four planning classes (`build.gradle:132-138`).
- `testServerPlanning` has only the extensions exclusion, so it runs every class with server planning on. That
  includes the four, and the services of that run turn the property on through `ServerPlanning.selected()`.
- `check` depends on `testServerPlanning` and `smokeTestServerPlanning`. CI's Spark 4.2 itest jobs ran both (pass
  counts in section 1).
- No class is lost between the two runs:
  - the four planning classes run in `testServerPlanning`, in the `Default build, spark-4.2-openhouse-spark-itest`
    job and its Reference twin;
  - `SparkIrcServerPlanningOffTest` and `SparkIrcNoPlanRequestTest` run in the default `test` task;
  - every other class runs in both.
- The task pattern in `check-test-groups.py` covers `testServerPlanning` and `*TestServerPlanning`, and both
  "test groups" jobs passed.
- `SparkIrcNoPlanRequestTest` now asserts no plan request at all on the default run. `SparkIrcServerPlanningOffTest`
  pins the 1.12 client's refusal when the server does not offer planning.

### Minors

- **N3, closed.** The lock rule's refusal is audited as FAILED UPDATE (`auditRefusal` around `withTheLockRule`).
  `NotATableUpdate` is audited with the commit's kind. The tests assert exactly one FAILED UPDATE event for each.
- **Private ids, closed.** No added line in the final diff carries a private id (D, V, FF, RF or BC ids, or pull
  request numbers).
- **Left on purpose:** I agree each one can wait.
  - The position of the lock-flip rule only reorders two 400 refusals, IRC only, and the IRC order is the stricter one.
  - The client-capability gate after a no-change 409: the caller has already passed the read privilege, and the
    gate is about compatibility, not authorization.
  - Planning that only partly stops: the concurrency bound holds, and a scan over many manifests does stop.
  - The 409 `CommitFailedException` on rename: both clients read any 409 there as `AlreadyExistsException`.

## 3. Nothing native changes with every new property unset, on both lines

- **Main classes outside the IRC package that differ from 96acfca3:**

  | Class | Change |
  |---|---|
  | `ClusterProperties` | new fields only |
  | `ServiceAuditAspect` | reads a request attribute that only the IRC metrics route sets |
  | `TableAuditAspect` | new advice on `IcebergRestApiHandler.loadIcebergTable`, IRC only |
  | `TableCommitCore` | the three checks extracted into one method, same order and messages |
  | `TableCommitService` | `authorizeWrite`, called only from IRC, plus the `core()` extraction |
  | `ViewsServiceImpl` | gains `readViewVersion`, called only from IRC |
  | `services/tables/build.gradle` | test heap |
  | Spark 4.2 itest build | test filters |

- **Native PUT** still answers 500 on a missing lock state, because `checkIfLockPoliciesUpdated` is unchanged.
- **Planning off by default:**
  - 1.12 line: plan and credentials are not registered (`@ConditionalOnProperty`, `havingValue="true"`), so they
    answer the catch-all's 406 and are not listed;
  - 1.5.2 line: there is no controller, as before.

  This is a change against the branch only. LinkedIn main has neither route.
- **The author's "Behavior changes against the base" list now covers my earlier differences:** the namespace
  routes' outage answer is 503, the view commit answers the version it stored, and the 500 for an unknown commit
  state is listed. I found nothing that is missing.

## 4. Findings left open: none must be fixed before merge

- **C1 (can follow; not this PR, from #160).** `IcebergRestApiHandler.storedNamespace` (lines 513-524) swallows
  every failure, an `AuthorizationServiceException` included, and counts it as no record. `requireNamespace`
  (line 454) uses it. So during an authorization outage, a namespace that has only a record (no table or view)
  answers 404 `NoSuchNamespaceException` instead of 503 on every route that calls it:
  - list namespaces with a parent (line 207) and the line-269 caller (list tables);
  - the view list and create routes (`IcebergRestViewHandler.java:99,121`);
  - the target namespace of a cross-database rename (`IcebergRestWriteHandler.java:1216`).

  The namespace load answers 503 in the same situation. It fails closed and leaks nothing, but a client is told
  "does not exist" during an outage. Fix: in `storedNamespace`, rethrow `AuthorizationServiceException`, and only
  when the namespace also holds nothing (the same rule as `loadNamespace`). This belongs to the namespace work.
- **C2 (can follow, test only).** About nine closed Spring contexts are still reachable, for a reason not yet
  identified (`services/tables/build.gradle`, comment above `maxHeapSize = '640m'`). A heap dump would name what
  retains them, and the heap could then return to 512 MB.
- **C3 (minor).** In `IcebergRestViewHandler.answered`, when `committed == null` it answers the view as stored now,
  possibly a later version. Fix: treat a null as unknown, or assert it cannot happen.
