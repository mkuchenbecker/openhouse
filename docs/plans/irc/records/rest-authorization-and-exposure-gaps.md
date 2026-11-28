# rest-authorization-and-exposure-gaps

Bottom line: review round done; pull request 158 head e79cee5e941b33d6b87538407b9a71e07fc0936b, every CI job green (13 success, 3 skipped by design).

Heap measurements: sec2/m-before, sec2/m-after (512 MB), sec2/m-640.

## Summary

Fix-forward on the Iceberg REST routes of the tables service (`/iceberg`, package `tables.api.irc`). This closes the smaller authorization and exposure gaps that pull request 152's two review rounds and the earlier retroactive reviews found. There is one commit per item, and each commit's tests were seen failing on the code before it.

## Items closed

| # | Commit | Ids | One line |
|---|---|---|---|
| 3 | `7d3bc845` | FF-48 (review of 152: F3) | An authorization-service failure answers 503 on the `/iceberg` table routes, not 403 |
| 1 | `5bbde718` | review of 152, re-check R1 (RF-002 class) | For a commit with updates, the early decision is the commit core's checks in the core's order (legacy lock, write privilege, SYSTEM_ONLY), using the core's own method, before any requirement is checked |
| 2 | `700ab000` | review of 152, re-check "Point 2", F7, F5 (RF-007; commit-write defect 7) | A commit also needs the read privilege, decided with the write privilege. A commit answers the version it stored, not an unchecked reload. A view commit's base and answer are read with a read decision |
| 6 | `fcb06c64` | FF-08 (RF-003; commit-write defect 3) | A rename onto a view's name is refused with 409 before the native rename writes anything. Other House Tables conflicts during a rename answer 409 `CommitFailedException`, not "already exists" |
| 5 | `b69c71ec` | FF-07 (RF-011; read-register-limits D8) | The metrics report body is read with a 1 MiB bound. The service audit records the report's identity and size, not its content |
| 7 | `bafc9032` | commit-write defects 6 and 10; RF-027 (part of FF-06) | A view update sent to the table commit route answers 400, not 500. A lock state in a policy patch, on a table that has none, gets the lock rule's refusal instead of the NullPointerException. A refused plan is logged at DEBUG, not WARN |
| 4 | `8fef2ee8` | FF-04, the bounding part of FF-06 (RF-022 interim gate, RF-023, RF-024) | Scan planning is off unless `cluster.tables.irc.scan-planning.enabled` turns it on. A plan call is audited as a table READ. The number of concurrent plans and the time one plan may take are bounded, and a non-positive bound is refused at startup |

## Review round (independent review of this pull request)

| Finding | Commit | Closed by |
|---|---|---|
| M1 merge the base (#157); flip its outage pin | `2a482f60` | Conflicts resolved. The fixture endpoint count is now `22 + (planning ? 2 : 0) + (registerView ? 1 : 0)`, where `planning` = planning classes present AND the embedded server's environment turns planning on (asserted off), so it holds for both settings. `IrcNamespaceRoutesFailureTest` pins **503** `ServiceUnavailableException` with the backend's message for a namespace outage (was 403). The namespace layer's fallback (`readRecord`) rethrows an outage and reads a denial as no record, which agrees with the error mapping. Also merged #160 (`e79cee5e`, no conflict) |
| S1 a landed commit answered 500 | `53ab4af0` | The race fallback catches a failed read of the committed file. If the same table (same UUID): answer the table as stored now. Otherwise: `CommitStateUnknownException`, the spec's 500 for an unknown commit state. Tests: `aCommitWhoseStoredVersionIsGoneIsAnsweredWithTheSameTableAsStoredNow`, `aCommitWhoseTableWasReplacedIsAnsweredCommitStateUnknown` (both 500 `ServiceFailureException` before) |
| S2 heap | `4a68f782` | Measured. Fixed on the test side (`ReleaseMeterRegistriesOnClose`; one shared context for the credentials tests). Test heap 640 MB instead of 768 MB. See "Heap" below |
| S3 view commit answer | `53ab4af0` | The view commit answers the version it stored, with the same rule as S1 (`ViewsServiceImpl.readViewVersion`, IRC only). Test: `aViewCommitAnswersTheVersionItStoredNotALaterOne` (before: the later write's property was answered) |
| S4 Spark default configuration | `08405765` | The default `test` and `smokeTest` runs keep planning off; only `testServerPlanning` and `smokeTestServerPlanning` turn it on. The four planning-comparison classes run only in `testServerPlanning`. New `SparkIrcServerPlanningOffTest` pins Spark 4.2's answer: `IllegalStateException` "Server requires server-side scan planning for table irc42.flat but does not support endpoint POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan" |
| N3 two unaudited refusals | `53ab4af0` | The lock rule's refusal and the view-update 400 are each audited as one FAILED UPDATE (asserted) |
| N5.5 private ids | `0479a119` | Removed from the comments this branch touched |

**Heap.** Measured locally on full `:services:tables:test` runs: a full collection every 30 s, then the heap in use and instance counts.
- Before the fix (768 MB): meter registries grew with every context created, 21 after four minutes and 95 at the end. Heap in use after collection: 560 to 680 MB in the last third.
- After: 9 to 20 registries, with no growth over the run. Heap in use after collection: 300 to 545 MB, peaking at 544 MB.
- At 512 MB the suite passes, but its peak is about 496 MB, which leaves no margin. **At 640 MB it passes with about 96 MB to spare.**
- What still retains memory: up to about nine closed contexts beyond the cache's eight (8 to 17 reachable). Their retainer is not identified, because no heap dump was analysed. It is no longer the global registry.
- A production service runs one context, so it has one registry and no growth.

**Left, with the reviewer's reasoning:**
- **Lock-flip rule position.** For TABLE_UPDATE the core runs `checkIfLockPoliciesUpdated` before the lock checks. The IRC early decision runs it after, so a lock flip on a legacy-locked table gets "locked state" instead of "Lock state cannot be updated". The status is the same, and the IRC order is the stricter one.
- **Client-capability gate.** It is the one load check not decided before a no-change commit's requirement 409. It concerns compatibility, not privilege.
- **Planning interruption.** On a single-manifest scan whose filter matches nothing, the plan is not interrupted: the manifest is read to its end on the plan thread. The concurrency bound still holds and the answer is the timeout's.
- **Rename conflict type.** A non-key House Tables conflict during a rename answers 409 `CommitFailedException`. The spec lists 409 for rename only as "already exists", and both clients read any 409 there as `AlreadyExistsException`.

Earlier fixes on the branch:
- `99ef2efa`, the first test-heap fix (since revised by `4a68f782`). CI's `Default build, rest` on the first head failed with `OutOfMemoryError: Java heap space`: the last contexts of the suite would not load in the default 512 MB test worker, and item 4's two planning-on test contexts pushed it over. I reproduced the failure locally. The fix sets `maxHeapSize '768m'` for `:services:tables:test` and closes the two planning-on contexts after their class. At 768 MB the full suite passes locally, and the heap in use in the second half of the suite stays between about 450 and 660 MB.
- `e84034c9`, a merge of `irc-integration` (#156). It had one conflict, in `TableCommitService.core()`: #156 added a `ClusterProperties` argument to `TableCommitCore`, and `core()` now passes it.

## Orchestrator's decisions (recorded as given)

- **Item 2 (the orchestrator's decision).** On the `/iceberg` routes, a commit requires the READ privilege in addition to the write privilege, and both are decided together before anything else. The reasons given:
  - the specification's commit response carries the full metadata;
  - an Iceberg client cannot commit without first loading the table, and the load already needs the read privilege, so a principal with write but not read could never use these routes through a client.

  This applies to the table commit, the one-table transaction and the view commit. A create (no table exists yet) answers its creator as before. That matches native: `POST /v1/databases/{d}/tables` answers the creator the created table, with no read decision.
- **Item 4 (the orchestrator's decision).** The plan route is not served unless a cluster property turns it on. The reasons: the plan answer exposes data values (lower and upper bounds), partition values and file paths under the metadata privilege alone, and the privilege the route should need is not decided. That privilege question is the owner's and is not changed here. It is stated in the property's documentation (`ClusterProperties`, `DEPLOY.md`).

## What was wrong, and what changed

1. **Lock checks before requirement checks.**
   - Before: for a commit with updates, `authorize` decided only the write privilege. `commitOn` then checked the requirements before the core's lock checks. On a locked table, a writer without LOCK_ADMIN (legacy lock) or without system-only access (SYSTEM_ONLY lock) who sent a failing requirement got a 409 that named the stored UUID.
   - Now: `TableCommitCore.checkWriteAccess` holds the three checks (the legacy-lock refusal, the write privilege or SYSTEM_ADMIN on a replica table, the SYSTEM_ONLY decision). `TableCommitCore.commit` calls it where it ran the checks inline, so native behavior and order are unchanged. `TableCommitService.authorizeWrite` calls the same method.
   - The commit kind that words the legacy-lock message is read from the request, the same way the refusal's audit kind already was.
2. **The read privilege, and what a commit answers.**
   - The early decision for a commit with updates now ends with `GET_TABLE_METADATA`. A refusal is audited as the commit's refusal.
   - `commitOn` returns the `TableDto` that the service layer stored. The answer (`answered`) reads the table back as before. If another write landed after this one, the stored version is read from its own metadata file instead, so the answer is never a later version or another table of the same name.
   - The same answer path is used for create, staged-create completion and register. The plain IRC create is included too, because its answer was the same unchecked reload.
   - View commit: the read privilege and the replace privilege are decided for every commit. The base view and the answered view are read through `loadReadableView`, which decides on the row it reads (F5).
3. **503 for a failing authorization service.** `IcebergRestErrors.toError` now tests for `AuthorizationServiceException` before `AccessDeniedException`.
   - I checked every other pair of branches in the mapping for the same shadowing (a subclass tested after its superclass). I read the OpenHouse exception classes and checked the Iceberg exception hierarchies with `javap` on both lines. **None found.**
   - The one subclass pair that is checked, `SystemOnlyLockAccessDeniedException` extends `UnsupportedClientOperationException`, is already in the right order (423 before 400).
4. **Scan planning off by default.**
   - `ClusterProperties.IRC_SCAN_PLANNING_ENABLED` (`cluster.tables.irc.scan-planning.enabled`, default false) puts `@ConditionalOnProperty` on `IcebergRestScanController`. When it is off, the controller is not registered: both routes go through the catch-all, which answers its 406 with its own message, and the served-routes list does not include them because that list is read from the registered mappings. Neither `IcebergRestServedRoutes` nor the config handler needed a code change; only their comments changed.
   - The credentials route goes with planning. The specification ties its `planId` to a plan, and its only other use, refreshing vended credentials, is never pointed at this server, because this server vends none.
   - **Audit:** an advice on `IcebergRestApiHandler.loadIcebergTable`, which now returns the loaded `TableDtoWithMetadata` (its only callers are the two routes). Plan and credentials calls emit the IRC load's READ event, with the same metadata location.
   - **Bounds (`IcebergRestBoundedPlans`).** Each plan runs on a pool of `max-concurrent-plans` threads (default 4) with no queue.
     - A plan asked for beyond that answers **503 `ServiceUnavailableException` with `Retry-After: 1`**. The fork's OpenAPI document lists 503 for `planTableScan` ("not ready to handle the request") with an optional `Retry-After`.
     - A plan that runs longer than `timeout-ms` (default 60000) answers the spec's **`failed` plan status** (`ScanPlanTimeoutException`), the same way the task cap does, because the same scan would take as long again. Its planning thread is interrupted, and it keeps its thread until it stops, so the concurrency bound still holds.
   - The task cap moved into `ClusterProperties` (default 10000). When planning is on, a value of zero or less for any of the three bounds stops the service at startup (`IllegalStateException`) instead of being replaced silently.
   - The Spark 4.2 itest's two services turn the property on.
5. **Metrics.**
   - `IcebergRestMetricsReports.read` reads up to 1 MiB, using the Content-Length when the request has one and otherwise counting as it streams. A larger body answers **400 `BadRequestException`** ("the report is N bytes, larger than the 1048576 bytes this server accepts"), because the spec lists 400 and not 413 for this route. It is not counted as a report.
   - The audited payload is `{"report-type","table-name","snapshot-id","bytes"}`, or `{"bytes"}` for a refused body. It is set through a new request attribute, `ServiceAuditAspect.AUDITED_REQUEST_PAYLOAD`, which only this route sets.
6. **Rename onto a view.**
   - One keyed House Tables read of the target as a view, before the native rename. It runs at the point where the native rename looks for a table there: after the request's validation, and only when the source exists, so a missing source still gets the native 404. If the target is a view, the answer is the spec's 409 "View already exists", with nothing written.
   - The refusal is audited as the native rename's refusal (one FAILED RENAME_FROM).
   - A House Tables refusal during the rename itself answers "already exists" only when the target's key is now taken. Otherwise it answers 409 `CommitFailedException`.
7. **Small ones.**
   - Applying a view-only update to table metadata throws `UnsupportedOperationException`. That is now answered 400 with Iceberg's message, both on the commit route and in a staged-create completion.
   - The IRC commit reads an absent stored lock state as unlocked. A patch that would lock the table gets the lock rule's refusal ("Lock state cannot be updated for Table d.t", 400). A patch that leaves the lock state unlocked has its lock state dropped. The core and the native PUT are unchanged.
   - A refused plan is logged at DEBUG instead of WARN.

## Behavior changes against the base

No native route changes its answer. `TableCommitCore` was refactored only to extract its checks; their order and behavior are the same. The `ServiceAuditAspect` attribute is read only when set, and only the `/iceberg` metrics route sets it.

**Default configuration:** the plan and credentials routes go from **served to not served**. They now answer 406 and are no longer in the config answer's endpoint list, so clients plan on the client side, as they do natively. Why: the route exposes data values from manifests under the metadata privilege alone, read with the service's own storage access, and the privilege it should need is the owner's open question. Where it is written: `ClusterProperties` (`IRC_SCAN_PLANNING_ENABLED` javadoc), `DEPLOY.md` "Iceberg REST scan planning", and the `IcebergRestScanController` javadoc. This moves toward LinkedIn main, where the routes do not exist.

With planning on, every answer to an allowed caller is byte-identical to the base, except the changes below. All are on `/iceberg` routes only:

| Change | Who | Direction |
|---|---|---|
| Table commit with updates, or one-table transaction, without `GET_TABLE_METADATA`: 200/204 becomes 403, nothing written, audited FAILED (the commit's kind) | write-only principals | Away from the native PUT, which answers a writer a table summary with no read decision. The `/iceberg` answer is the full metadata, and a client cannot commit without loading first. Written in the `IcebergRestWriteHandler.authorize` javadoc |
| View commit with updates without `GET_TABLE_METADATA`: 200 becomes 403 | write-only principals on views | Same reason. Written in the `IcebergRestViewHandler.authorizeCommit` javadoc |
| A no-change commit WITH updates, refused for the read privilege: audited FAILED UPDATE instead of FAILED READ (same 403 and message) | denied callers | n/a (audit kind) |
| Legacy-locked table, commit with updates (changing or not), by a caller denied LOCK_ADMIN or the write privilege: 403, or a 409 with stored ids, becomes the native PUT's 400 "locked state" | denied callers | Toward native (the PUT refuses a legacy-locked table first, for everyone) |
| Legacy-locked table, no-change commit with updates by a LOCK_ADMIN holder: was answered as a load, now the native PUT's 400 | lock admins | Toward native |
| SYSTEM_ONLY-locked table, commit with a failing requirement by a caller denied system-only access: 409 with stored ids becomes the native 423 | denied callers | Toward native |
| An authorization-service failure: 403 `ForbiddenException` becomes 503 `ServiceUnavailableException` with the backend's message | everyone, during an outage | Toward native (503) |
| A commit answer when another write landed between the commit and the answer: was the later version, now the committed version | allowed writers, racing | New behavior. Written in the `IcebergRestWriteHandler.answered` javadoc |
| Rename onto a view's name: same 409 and message, but no metadata files written and about 2 s faster. A non-key House Tables conflict: "Table already exists" becomes 409 `CommitFailedException` | everyone | Route is new since main. Native unchanged (still 500) |
| Metrics report: service audit payload is identity and size instead of the full body. A body over 1 MiB: 204 becomes 400 | reporting clients | Route is new since main |
| View update on the table commit route: 500 becomes 400 | malformed requests | Route is new since main |
| Policy patch with a lock state, on a table with no stored lock state: 500 NPE becomes 400 (locks) or accepted without a lock change (unlocks) | patch senders | Away from the native PUT's 500 (an NPE, kept on the native route). Written in the `IcebergRestWriteHandler.withTheLockRule` javadoc |
| Plan or credentials call (planning on): now emits a table READ audit event | everyone | Route is new since main |
| Plan beyond the concurrency bound: 503 with Retry-After. Plan slower than the bound: `failed`. Non-positive bound: startup refused | planning on | Route is new since main |

The test worker heap of `:services:tables:test` goes from 512 MB (the default) to 640 MB. This changes the build only, not any route.

Changes from the review round:
- **An authorization outage on the `/iceberg` namespace routes (#157)** (load, create, update, drop): 403 `ForbiddenException` becomes 503 `ServiceUnavailableException` with the backend's message. This moves toward native.
- **A commit or view commit whose stored version can no longer be read when it is answered:**
  - same table: answered with the table or view as stored now;
  - another table or view under the name: 500 `CommitStateUnknownException`.

  These answer races only, after a commit that landed. Written in `answered`'s javadoc in both handlers.
- **View commit in a race:** answers the version it stored, not a later one.
- **Audit:** the lock rule's refusal and the view-update 400 on the table commit route now emit a FAILED UPDATE table audit event.
- **Spark 4.2 itests:** the default runs run with planning off (test configuration only).

## Existing tests whose pinned expectation changed

- `IrcWriteRoutesAuthTest.knownDefectAnAuthorizationBackendFailureIs403OnTheIrcTableRoutes` is renamed `anAuthorizationBackendFailureIs503OnTheIrcTableRoutesAsNatively`. Old: 403 `ForbiddenException` on the load and two commits. New: 503 `ServiceUnavailableException` with the native message, on the load, the empty commit, a commit with updates and the transaction.
- `IrcWriteRoutesAuthTest.onALegacyLockedTableACommitThatChangesNothingNeedsLockAdmin`. Old: no-change commits with updates got the load's 403 when LOCK_ADMIN was denied, and a write-denied caller got 403. New: both get the native PUT's 400 "locked state". The empty commit still gets 403.
- `IrcWriteRoutesAuthTest.updatesIcebergRecordsAsAChangeAreWrites`. Old: `denyOnly(GET_TABLE_METADATA)` was answered 200. New: 403, and both shapes are first shown to give 200 when allowed.
- `IrcWriteRoutesAuthTest.aCommitThatChangesNothingIsALoad`. Old: a refusal was audited READ for every shape. New: READ for no updates, UPDATE for shapes with updates.
- `IrcPolicyPatchTest.assertSameOutcome`. Old: the IRC answer had to equal the native 500 for "unpartitioned, no policies: a lock state". New: the one difference is declared: native 500 (unchanged), IRC 400 with the rule's message, nothing stored on either.
- `IrcConfigEndpointsTest.served()` and `IrcReadRoutesTest.unservedSpecRoutesAre406UnsupportedOperation`. Old: plan and credentials listed and served on 1.12. New: never listed, and always 406, with the default configuration.
- `IcebergRestRoutesBoundaryTest` (tables-test-fixtures). Old: plan and credentials listed on 1.12. New: never listed; the count is 19, or 20 with register-view.
- `IrcScanPlanCostTest`. Removed: `bound(0) == bound(-1) == 10000` and `bound(7) == 7`. `bound()` is gone because a non-positive cap is now refused.
- `IrcViewRoutesTest.renameOfATableOntoAViewsNameIsRefused`. Assertions added: no metadata file written, and the same audit as the native refusal.

## How it was verified

All runs below are local.

- **Default line (Iceberg 1.12):**
  - Every new and changed test was run against the code before its fix and failed for the stated reason. Examples: a 409 naming the stored UUID; 200 for write-only principals; the race answering the later version; five more metadata files on a rename; the full report in the audit payload; 500s; plan listed and unaudited.
  - Final run on the merged head: `*Irc*`, `*View*`, `*Audit*`, `*CommitPath*` and `TableCommitServiceTest` in `:services:tables`, **99 classes, 1035 tests, 0 failed, 0 skipped**.
  - `:services:common:test` passed.
  - `IcebergRestRoutesBoundaryTest`: 5 of 5.
- **Reference line (`-PicebergLinkedIn`, 1.5.2):** the touched `:services:tables` classes (IrcWriteRoutesAuthTest, IrcViewRoutesAuthTest, IrcViewRoutesTest, IrcServiceAuditTest, IrcWriteRoutesTest, IrcPolicyPatchTest, IrcConfigEndpointsTest, IrcReadRoutesTest, IrcPlanCredentials*, IrcMetricsRouteTest, TableCommitServiceTest, CommitPath*RulesTest) ran **277 tests, 0 failed**. The 7 skipped are the planning tests' `assumeTrue(served())`. `IcebergRestRoutesBoundaryTest`: 5 of 5.
- **REST compatibility kit (`restCompatKitTest`), before item 4 and on the final head:** both runs gave **160 tests, 42 passed, 95 failed, 23 skipped**, exactly the outcomes listed in `expected-outcomes.txt`. No kit test calls the plan or credentials routes, so their outcomes are unchanged (none).
- `python3 scripts/ci/check-test-groups.py`: the partition holds.
- **After the merge of #156 (local):**
  - default line: `*Irc*`, `*View*`, `*Audit*`, `*CommitPath*`, `TableCommitServiceTest`, `*FormatV3*`, `ExtensionPointDefaultsTest`, `DefaultClusterPropertiesTest`: 110 classes, 1085 tests, 0 failed;
  - reference line: IrcWriteRoutes*, IrcViewRoutesAuthTest, IrcConfigEndpointsTest, TableCommitServiceTest, CommitPath*: 170 tests, 0 failed;
  - the full default-line `:services:tables:test` at 768 MB (before the merge): passed, 0 failed.
- **Left to CI:** the full `:services:tables` suites (`Default build, rest`, `Reference build, rest`), and the Spark 4.2 itest including `testServerPlanning` and `smokeTestServerPlanning` with the property on (`... spark-4.2-openhouse-spark-itest`). Spark 3.5 runs in `... spark-3.5-openhouse-spark-itest`.

## Deliberately left

- **The plan route's privilege (FF-05).** It still asks for `GET_TABLE_METADATA` when planning is on. That is the owner's question, and it is stated in the property's documentation.
- **The rest of FF-06:**
  - serialization `checkArgument` failures still answer 400 (RF-026);
  - with `use-snapshot-schema=false` and a branch that moved since the load, the scan still falls back to the snapshot's schema (RF-025);
  - the body of the plan request is still unbounded, like every IRC route other than metrics;
  - a refused plan is not counted by a metric (only the log level changed).
- **The native rename onto a view's name** still answers 500; that is the owner's question.
- **Not tested:**
  - the `CommitFailedException` answer for a real concurrent change during a rename (the fixture has no fault injection for it);
  - the startup refusal end to end (unit-tested on the check that `@PostConstruct` calls);
  - the route-level 503 and timeout through HTTP (unit-tested on `IcebergRestBoundedPlans`; the controller wiring was checked by reading);
  - the commit-answer race for a table dropped and recreated in between. By reading, the committed file's read then fails and the client sees a 500, instead of being handed another table's metadata.
- A busy 503 is returned by the plan controller itself, so the service audit records it as a 503 without an error message, not through the IRC exception handler.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01T3dbirnudathz3xU8K18AE
