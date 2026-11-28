# Review of pull request 162 (irc/iceberg-rest-handler-minors)

Reviewed head `f6ed28a1af46e0060c241df81a2881a421c5bdcf`, which was still the head when I started. Base `origin/irc-integration` `4b9d152d`, which is the merge base. 10 commits, each test committed before its fix. 11 files, +757/-32. I was read-only: nothing was built or run.

CI at the time of writing:
- Passed: Reference build rest, apps-spark-4.2, both itest jobs on each line, Default build Docker and integration tests, and test groups on both lines.
- Still running: Default build rest, Default apps-spark-3.5 and apps-spark-4.2, Reference apps-spark-3.5.
- Failed: none so far.

## 1. Verdict

**MERGE AFTER FIXES.** One finding must close first: F1, a missing test for a case item A requires.

The three things that matter most:
1. **Item A's code is correct by reading for all four cases**, and nothing regresses against the base. But one case item A explicitly requires has no test: a namespace that holds only a view, during an authorization outage, must answer as before. That case is the `isListed` branch inside the outage catch (F1).
2. **The existence checks still do not share `loadNamespace`'s rule.** When the record store fails, a namespace that has only a record answers 404 on HEAD and the lists, but 500 on load. The new javadoc says the two rules are the same and then describes a different one (F2).
3. **The two 500s are justified and stay on `/iceberg`.**
   - Lowering `format-version` on a commit is now 400. The native PUT already answers 400 for the same condition (`IllegalArgumentException` is mapped to 400 natively).
   - A staged-create completion on an existing table whose metadata cannot be read is now 409 instead of 500.
   - No native route's answer changes: every non-test file is in `api/irc`, and the changed methods have no non-IRC callers.

## 2. Findings

### F1. MUST FIX BEFORE MERGE: the outage case for a namespace that holds only a view is untested

- **Where:** `IcebergRestApiHandler.java:526-535`:
  ```java
  } catch (AuthorizationServiceException outage) {
    if (isListed(databaseId)) {
      ... return true;
    }
    throw outage;
  ```
- **What is untested:** item A requires that "a namespace that holds a table or a view answers as before during an outage". `IrcNamespaceRoutesFailureTest` covers the table case (`TABLED` in `whenTheAuthorizationServiceFails...HoldsATable...`), a record-only namespace and a missing one. It does not cover a namespace that holds only a view during an authorization outage.
- **What would slip through:** delete the `isListed` branch, so that every outage is rethrown, and every test still passes. Yet a view-only database would then answer 503 instead of 204/200 during an outage on HEAD, list tables, list children, the view list with views off, and the rename target. That is a regression against the base, whose `storedNamespace` swallowed the failure and then consulted `isListed`.
- **How sure:** certain from reading the test class. Its only two calls to `authorizationServiceFails()` are at lines 203 and 271.
- **Fix:** a test modelled on `whenTheRecordStoreFailsADatabaseThatHoldsOnlyAViewLoadsAsBefore` (line 316), with `authorizationServiceFails()` in place of `storeFails()`. It should assert:
  - 204 on HEAD;
  - 200 on `/tables`, on `children(db)` and on the view list with views disabled;
  - for the rename into it, not 404 (the native rename's own `CREATE_TABLE` decision on the source database then answers 503, so assert that it is not `NoSuchNamespaceException`);
  - 200 on load.

### F2. SHOULD FIX: on a store failure the existence checks and `loadNamespace` follow different rules, and the javadoc says they are one

- **Where:** `IcebergRestApiHandler.java:506-544`, compared with `loadNamespace` at `:228-262`.
- **What `loadNamespace` does:** on any failure to read the record (store or authorization), it answers as before records existed if the namespace holds something, and otherwise answers the failure.
- **What `hasRecordOrIsListed` does:** it applies that rule only to `AuthorizationServiceException`. Any other `RuntimeException` (a store failure, for example `WebClientResponseException` 500) counts as "no record". So during a store outage a namespace that has only a record answers:
  - 404 `NoSuchNamespaceException` on HEAD, list tables, list children, the view list and the rename target;
  - 500 `ServiceFailureException` on GET (load).
- **Why this is the same situation as C1:** the reasoning the author wrote for the outage ("whether there is a record cannot be known ... never 'does not exist'") applies word for word to a store failure.
- **The javadoc:** it opens with "The rule `loadNamespace` applies when the record cannot be read applies here too", then describes a different rule for the store. The justification it gives ("so a namespace that holds a view is answered as before") does not need the 404: the `isListed` fallback gives that under `loadNamespace`'s rule too.
- **The assignment:** item A asked for "one rule shared with `loadNamespace`". The PR body discloses the difference ("A store failure still counts as no record"), so this is not hidden. But the requirement is not met, and the logic is duplicated rather than shared.
- **Why it can wait:** C1 named only the authorization failure, and a test from #160 (`missesAsBefore` in `whenTheRecordStoreFails...`) pins the store behavior. Changing it is a behavior change that needs its own justification.
- **Fix, either one:**
  - (a) Rethrow every failure when the namespace is not listed, the same rule as load. The test expectation at `IrcNamespaceRoutesFailureTest.java:175` would move from 404 to 500. Record it as a behavior change.
  - (b) Keep the current behavior, correct the javadoc's first sentence, and state why HEAD may say "does not exist" while load says "failed".
- **How sure:** certain from reading.

### F3. MINOR: the timeout test can flake, rarely

- **Where:** `ScanPlanBoundsSwap.timingOut` sets a time bound of `0L`. `IcebergRestBoundedPlans.run` then calls `planned.get(0, MILLISECONDS)`.
- **The race:** `FutureTask.get` with a zero timeout returns the result if the task has already completed when it checks the state. So if the planner thread starts and finishes planning the empty table before the asking thread reaches `get`, the answer is `completed`, and `aPlanThatTakesLongerThanItsBoundIsAnsweredFailedWithNoTask` fails. That needs the request thread to be descheduled for the length of a whole plan (a table load and a metadata read, milliseconds), so it is rare, but possible on a loaded 2-vCPU runner.
- **How sure:** likely, from reading the JDK `FutureTask` semantics; I could not measure it without running.
- **Fix:** make the plan deterministically slower than the bound. For example, have the swap hold the only planner thread (as `busy` does) with a pool of 2 and a 1 ms bound. Or add a test hook that runs before the plan callable.

### F4. MINOR: the "House Tables conflict" rename tests inject the conflict in the authorization stub, not in House Tables

- **Where:** `IrcWriteRoutesAuthTest.java:415-432` (`houseTablesRefusesTheRename`). It throws `CommitFailedException(HouseTableConcurrentUpdateException)` from `checkAccessDecision(..., CREATE_TABLE)`.
- **What this exercises:** the IRC catch-and-map at `IcebergRestWriteHandler.java:1271-1296`, end to end through HTTP. It does not exercise the real path, where `catalog.renameTable` commits a transaction. There Iceberg's transaction retries on `CommitFailedException`, and the final exception is what reaches the catch. By reading `OpenHouseInternalTableOperations.java:633-636`, it has the same shape, so I expect the mapping holds. But the test cannot show it.
- **An inaccurate comment:** it says this decision is "the native rename's last decision before its write". In `TablesServiceImpl.renameTable` it is the first of three; `checkTableWritePathPrivileges` and `checkSystemOnlyLockAccess` follow it.
- **Thread handling:** the second test starts a thread inside a request and calls `join()` with no timeout. If the create ever blocks on the outer request's persistence context, the suite hangs instead of failing. Use `join(30_000)` and assert that the thread is no longer alive.
- **How sure:** certain about the seam and the comment; cannot tell about the real retry path without running.

### F5. MINOR: the format-version lowering rule now exists twice, and the core's lowering branch is unreachable from these routes

- **Where:** the new `IcebergRestWriteHandler.refuseALowerFormatVersion` (`:747-757`) refuses any lowering. `FormatVersionRule.checkUpdate` (`FormatVersionRule.java:282-305`) refuses lowering only when `stored > clusterVersion`, and is called through `refuseUpdate` from `TableCommitService.commit` (`:93`).
- **Why the core's branch is dead:** that five-argument `commit` has a single caller, the IRC write handler (`:548`), and every non-replace commit now passes the new check first. So the lowering branch of `refuseUpdate` is dead for IRC non-replace commits. The message text is duplicated by hand.
- **Fix:** put the stricter rule in `FormatVersionRule.refuseUpdate`, which is documented as being for the Iceberg REST routes only, and call it from one place.
- **Order of refusals:** the refusal now comes before the native update validation (`validateUpdateTable`) and the lock-flip rule. A request that is both invalid and lowering now gets the format 400 instead of the validation 400. Both are 400, on `/iceberg` only.
- **The audit claim is untested:** the PR says the refusal is "audited as the commit's refusal", but `FormatVersionSwitchOffTest` asserts no audit event.
- **How sure:** certain from reading.

### F6. MINOR: the FF-53 "left" list is incomplete

These entries are in `services/tables` and in scope, but are neither taken nor listed as left, in the PR body or the author's record:
- 158 re-check C2: retained Spring test contexts and the 640 MB heap.
- 160's known gaps:
  - a name found by the table probe under another spelling answers the requested spelling on load (an `/iceberg` handler);
  - the stand-in lookups compare names exactly.

Two entries are left with a weak reason:
- "`FormatV3TypesAuthorizationTest` ... its allowed-caller case covers only the native PUT" is a gap on the `/iceberg` side (the IRC allowed case is missing). The author left it as "tests native routes", which misreads the entry.
- The IRC create at a cluster version above 2 with the switch off: the reason "the IRC create is the native create" is not a reason for an `/iceberg` answer. "Misconfiguration only" is a fair reason on its own.

### F7. MINOR: C1's "view create" path is silently replaced

- C1 and FF-53 name "view create" as one of the five paths. `IcebergRestViewHandler.createView` never calls `requireNamespace`; C1's line 121 is the empty branch of the view list.
- The author's record maps it correctly ("list views gate-off 99 and empty 121"), but the PR body does not say that C1's "view create" was a misreading.
- During an outage the view create still answers 503, from the native `putView`'s `checkDatabasePrivilege`, which `ViewFailureTranslator` translates. I read this; I did not run it.
- Fix: one line in the PR body.

### F8. MINOR: one of the six outage assertions passes for a reason other than the fix

- **Where:** `existenceChecksAnswerTheOutage`, `whenTheAuthorizationServiceFailsANamespaceThatHasOnlyARecordAnswersTheOutage`.
- **Why:** if `requireNamespace` wrongly said "exists" during an outage, the rename would still answer 503, from the native rename's `CREATE_TABLE` decision on the source database.
- The other five checks (HEAD, list tables, children, both view lists) do fail on that defect, so together the tests are sound. Only the rename row is weak.
- No fix needed beyond knowing it; at most, a comment.

## 3. Behavior changes

| # | Change | Route(s) | Before → after | Toward or away from main | Justified in code | On by default |
|---|---|---|---|---|---|---|
| 1 | During an authorization outage, a name that holds no table and is not in the database list. This includes **names that do not exist at all**, not only record-only ones | `/iceberg` HEAD namespace, list tables, list namespaces with a parent, list views (views off, or on with none), cross-database rename target | 404 → 503 `ServiceUnavailableException` | No main baseline (no IRC on main); matches load and the native 503 | Yes, javadoc of `hasRecordOrIsListed` | Yes |
| 2 | A view commit whose native replace answers a null metadata location | `/iceberg` view commit | 200 (view as stored now) → 500 `CommitStateUnknownException`, before any read-back | n/a | Yes | Yes; unreachable today by reading `ViewsServiceImpl.toCommittedDto` and the stored-row check |
| 3 | A staged-create completion (`assert-create`) on an existing table. The stored metadata file is no longer read | `/iceberg` table commit | 409 as before; when the metadata cannot be read, 500 (which named the file) → 409 | n/a | Yes | Yes |
| 4 | A non-replace commit or one-table transaction whose `format-version` property is below the table's | `/iceberg` commit, transaction | 500 ("Cannot downgrade") → 400 `FORMAT_VERSION_REFUSED`, audited FAILED with the request-derived kind; now ahead of the native validation and the lock rule | Toward the native answer (native PUT answers 400 for the same condition) | Yes | Yes |

Differences from the author's list:
- For row 1, the author's wording ("a namespace that holds no table and no listed view") does cover names that do not exist, but should say so plainly: during an outage, every unknown name now answers 503 on HEAD.
- Row 4's reordering against the native validation and the lock rule, and its audit kind, are not mentioned.
- Otherwise the lists agree. Native routes: none changed. Every non-test file is in `api/irc`, and `refuseCreateOfExisting`, `hasRecordOrIsListed` and `refuseALowerFormatVersion` have no non-IRC callers; I checked with `git grep`.

## 4. Tests

| Class or test | Can fail for the right reason? | Notes |
|---|---|---|
| `IrcNamespaceRoutesFailureTest.whenTheAuthorizationServiceFails...HoldsATable...` (pinned expectation changed: `missesAsBefore` → `existenceChecksAnswerTheOutage(NONE)`) | Yes | The old 404 was wrong for an outage: the record cannot be known. The change is justified. |
| `...HasOnlyARecordAnswersTheOutage` | Yes; fails on the base with 404 | Rename row weak (F8). |
| `...MissingToACallerRefusedItsRecord` | Yes | Checks found when allowed, then 404 on all six when denied. It would catch a fix that leaked denial as 403 or 503. |
| Outage, view-only namespace | **Missing** | F1. |
| `IcebergRestViewHandlerCommitAnswerTest` (Mockito, no context) | Yes; on the base `answered` returned `response(now)`, so `assertThrows` fails | `verify(never()).readViewVersion` is a weak "nothing read". The fix also skips `loadReadable`, which the test does not verify. |
| `IrcWriteRoutesAuthTest.aCompletionOnAnExistingTableReadsNoMetadataFile` | Yes; on the base `loadBase` reads the moved-away file and answers 500 | The file is restored in `finally`. |
| `IrcWriteRoutesAuthTest.aWriterThatMayNotRead...` (F12, added transaction audit equality) | Can fail (timestamp nulled by `onlyAuditEvent`); test-only, passes before and after, as the author says | — |
| `IrcWriteRoutesAuthTest.aRenameHouseTablesRefuses*` (2) | They test the IRC mapping through HTTP, not a real House Tables conflict | F4. |
| `FormatVersionSwitchOffTest.anIcebergRestCommitLoweringTheFormatVersionPropertyIsRefusedWith400` | Yes; asserts status, type, token, wording and an unchanged metadata location, on both routes | No audit assertion (F5). Runs on both lines; the Reference rest job passed. |
| `IcebergRestScanControllerStartupTest` (1.12 only) | Yes; exact refusal message per property; the planning-off case shows the condition | Plain `AnnotationConfigApplicationContext`, closed by try-with-resources, outside the Spring test cache. |
| `IrcScanPlanBoundsRoutesTest` (1.12 only) | Busy: yes, deterministic (`SynchronousQueue`, one thread held by a latch, `started` awaited); checks `Retry-After: 1` and the message, then 200 once freed. Timeout: yes, but timing-dependent (F3) | Reuses the cached context of `IrcPlanCredentials*Test`: same properties, `@Import`, initializers and `@MockBean` set (`AuthorizationHandler` only), all in the one `services:tables:test` task. |

Spring contexts added to the test cache: **0**. The heap stays at 640 MB; `build.gradle` is untouched.

Claimed but not pinned:
- the view-only outage case (F1);
- the audit of the format-version refusal (F5);
- the real House Tables conflict path of the rename (F4).

## 5. Checked and found sound

- **Item A, `loadNamespace` and the five paths** under (outage or denial) × (record-only or holds a table or view):
  - A table holder answers as before during an outage: `holdsATable` and the non-empty table list short-circuit before the record is read.
  - A view-only namespace is listed and answers as before (by reading; see F1).
  - A record-only namespace answers 503 during an outage and 404 when denied.
  - `loadNamespace` is unchanged.
  - Nothing is revealed: authorization is decided before the record lookup (`TablesServiceImpl.loadNamespaceMetadata`: `authorizeNamespace`, then `getByName`), so during an outage missing and record-only names both answer 503, and when denied both answer 404. The database list consulted in the catch is shown to anyone in the base too.
- **No regression for a namespace that holds tables:** an outage cannot turn a working request for such a namespace into a 503 on any of the five paths.
- **Item B:** null is unreachable by reading. `toCommittedDto` takes the pointer's location, and the no-change path uses the stored row, which must be set. The new branch throws before any read.
- **F8 of the 152 review (staged-create completion):** `commitCreate` is reached only when `isCreate`, so `refuseCreateOfExisting` always throws on an existing table. The message is Iceberg's own ("Requirement failed: table already exists"). `TableMetadata.newTableMetadata(Schema, PartitionSpec, SortOrder, String, Map)` exists on both lines.
- **Format-version check:** stored properties cannot carry `format-version`. It is reserved, and `replaceProperties` strips it on both lines, so an ordinary append is not caught by the new check.
- **Error mapping:** `IcebergRestErrors` maps `AuthorizationServiceException` to 503 ahead of the denial (`:125`). `RequestValidationFailureException` maps to 400.
- **Cruft:** no private ids (FF, RF, BC, D-numbers, pull request numbers) in code or messages. They appear only in commit messages.
- **Scope:** nothing outside items A to D.
- **Both Iceberg lines:**
  - The 1.12-only tests sit under `src/iceberg-1.12/test`, which `-PicebergLinkedIn` excludes.
  - Every other change compiles on both lines.
  - The Reference rest job passed.
  - Both test-groups jobs passed.

## 6. Not checked

- **Nothing was run by me:**
  - the flake probability of F3;
  - the real House Tables retry path of the rename (F4);
  - the claimed 500 before the fix for the format-version and completion tests.
- **Replace commits that lower `format-version`** (excluded by `if (!replace)`): I did not confirm that they do not also answer 500.
- **Default build rest and the two apps jobs** were still running; I read no results for them.
