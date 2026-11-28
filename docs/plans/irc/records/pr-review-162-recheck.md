# Re-check of pull request 162 at head 448fcd6f

I reviewed head `448fcd6f5a3cc8844eaab1b1856cab6384ef1e59`, which is also the pull request's head on GitHub. The base is `origin/irc-integration` at `35bd941c`, which is the merge base. I was read-only and ran nothing.

## Verdict: MERGE

Every finding is closed, or decided by the coordinator (F2). There is one correction: my F8 was wrong (see below). Two can-follow minors remain (R1 and R2), both about comments or the pull request body. Neither needs a code change.

CI on the head: all 14 jobs passed, and one, the Reference line's Docker and integration-test job, was skipped by design:
- Default and Reference: rest, apps-spark-3.5, apps-spark-4.2, both itest jobs, and test groups.
- Default: Docker images and integration tests.

The private-id guard ran and passed. It is the step "Check for private planning ids" in job 140934497330 ("Default build, test groups"), with `--self-test` and then the full check.

## 1. The findings

### F1: closed
`IrcNamespaceRoutesFailureTest.whenTheAuthorizationServiceFailsADatabaseThatHoldsOnlyAViewReadsAsBefore` sets up a database that holds only a view, with its views turned off. It then fails the record-read decision (GET_TABLE_METADATA on the database) and asserts:
- HEAD answers 204;
- `/tables`, the children list, the view list and load answer 200, and load has no properties;
- the rename into it answers 406, the native refusal of a rename across databases, not 404.

With the `isListed` branch removed, HEAD would throw the outage and answer 503, so the test fails. That matches the author's account: "expected <204> but was <503>".

### F2: decided by the coordinator, and the javadoc is now true
The javadoc of `hasRecordOrIsListed` (`IcebergRestApiHandler.java` 505-523) now states both rules and how they differ. On a store failure, a namespace that has only a record answers 404 on the existence checks and 500 on load. The PR body lists it under "Left", with the smallest fix.

### F3: closed
`ScanPlanBoundsSwap.timingOut` now uses a 100 ms bound and a planner pool whose `beforeExecute` waits on the release latch. So the plan cannot start before its bound expires, whatever the scheduling. On expiry, `cancel(true)` interrupts the held thread and the cancelled task does not run. `close` releases the latch and shuts the pool down.

The swap replaces the `final` field `planners` by reflection. That works for an instance field on JDK 17, and it is test code only.

### F4: closed
The rename tests were renamed to `aRenameCommitConflictOn*`. Their javadoc now states the seam honestly: the failure is thrown from the first of the native rename's three decisions, so the tests do not show that a real House Tables conflict reaches the route in that shape. `join(30_000)` is followed by an `isAlive` check.

### F5: closed
The handler copy is removed. `FormatVersionRule.refuseUpdate` (line 292) now refuses any `asked < stored` with the shared `lowered(...)` text, then calls `checkUpdate("Update", ...)`.

Who calls it:
- `git grep` finds one caller: `TableCommitService.java:93`, inside the `candidateView` supplier of `commit(identifier, baseVersion, base, candidate, context)`.
- That method's only caller is `IcebergRestWriteHandler.commitOn` (`:548` before; the same path now).
- The native table PUT (`TablesServiceImpl:275`) and the native snapshots PUT (`IcebergSnapshotsServiceImpl:90`) build `TableCommitCore` directly and never reach `TableCommitService.commit`.
- Nothing under `src/iceberg-1.12` calls it, so neither Iceberg line has another caller.
- The private `checkUpdate` (native repository path, `OpenHouseInternalRepositoryImpl:237`) only had its message construction moved into `lowered(...)`. The text is byte-identical, so the native answers are unchanged.

What changes for a caller:
- `asked >= stored`: identical to before.
- `asked < stored` with `stored > cluster`: identical. The same 400 and the same text were thrown before, from the same place.
- `asked < stored` with `stored <= cluster`: changed. This request used to pass `refuseUpdate` and fail at the repository with 500 "Cannot downgrade"; it now gets 400.

Where the refusal runs: after the native update validation (`context.getValidation()` at the top of `commit`), the IRC lock-flip rule, and the core's lock and write checks (`TableCommitCore.commit` 71-74). So up to that point every answer is as before. See R1 for the refusals that come after it.

The audit is now asserted: two `UPDATE FAILED` events, one per request, on the context's shared table-audit mock, filtered by database.

### F6 and F7: closed in the PR body
The "Left" list now names C2, 160's gaps, the IRC allowed case missing from `FormatV3TypesAuthorizationTest`, and the store-failure divergence. The body states that C1's "view create" path does not exist.

### F8: my finding was wrong
A cross-database rename is refused 406 by `validateRenameTable` (`IcebergRestWriteHandler` around 1245), right after `requireNamespace(toDatabaseId)` and before the native rename's decisions. So a wrongly "existing" target would always have answered 406, not 503, and the original rename row could already fail on its own. The added assertion with `authorizationServiceFailsOnRecordReads` makes that explicit: 503 when the target's check answers the outage, 406 in the view-only test. It is harmless and makes the test clearer.

### Replace lowering `format-version`: now pinned
`anIcebergRestReplaceLoweringTheFormatVersionPropertyKeepsTheTablesVersion` asserts 200, and the table stays format version 2, both in the answer and as stored.

## 2. The behavior-change list against the final diff

The final diff is 12 files, all on this branch: 4 main files in `api/irc`, `FormatVersionRule.java`, and test sources. The PR body's four changes match it.

Change 1: during an authorization outage, every name that holds no table and is not in the database list answers 503 instead of 404, including names that do not exist. This is the right answer:
- While the decision cannot be made, "missing" and "hidden" cannot be told apart. 404 would claim knowledge the server does not have.
- It reveals nothing. Missing and record-only names answer the same for every caller.
- The decision is made before the record is read (`TablesServiceImpl.loadNamespaceMetadata`: `authorizeNamespace`, then `getByName`), so no record content is involved.
- The database list consulted in the outage branch is shown to anyone in the base too.
- The load already answered 503, and a namespace that holds a table or a view answers as before.

Change 4 is stated correctly, with the nuance in R1.

## 3. The merges

- `448fcd6f` (the merge finished by hand) has two parents, `e011e171` and `35bd941c`. Its tree `c3ccc3cf` is identical to `git merge-tree --write-tree e011e171 35bd941c`: a clean automatic merge, nothing lost or added.
- `e011e171` has two parents, `4e73a4a9` and `a0239ad7`. Its tree `17cffbec` is identical to `git merge-tree --write-tree 4e73a4a9 a0239ad7`.
- `git diff origin/irc-integration HEAD` lists exactly the branch's 12 files. Every other file equals the base.
- Pull requests 163 and 164 also changed 4 of the branch's files (`IcebergRestApiHandler`, `IcebergRestViewHandler`, `IcebergRestWriteHandler`, `FormatVersionRule`). Their edits are kept, as the merge-tree equality shows.

Under the new House Tables stand-in:
- `IrcNamespaceRoutesFailureTest` stubs the namespace record store (`@MockBean HouseNamespaceRepository`), not the House Tables stand-in. Its view-only fixture is created through the native routes, and the database list includes view rows.
- `IrcWriteRoutesAuthTest` and `FormatVersionSwitchOffTest` go through HTTP.
- All passed on both lines in CI.

## 4. Spring contexts and the heap

- No `@MockBean`, `@SpyBean`, `@TestPropertySource` or `@DirtiesContext` was added anywhere. The only new `@SpringBootTest` is `IrcScanPlanBoundsRoutesTest`, which reuses the cached planning-on context of `IrcPlanCredentials*Test`: the same properties, `@Import`, initializers and `@MockBean` set.
- `FormatVersionSwitchOffTest` autowires the existing `tableAuditHandler` bean, which is a Mockito mock in `SpringH2Application`. No new context.
- `IcebergRestScanControllerStartupTest` uses a plain `AnnotationConfigApplicationContext` with `ClusterProperties`, the controller and one mock singleton. There is no Spring Boot, no database and no component scan, and it is outside the Spring test cache. It refreshes 6 times in total, each closed by try-with-resources; the default case starts a planner pool that `@PreDestroy` closes.
- It lives in `src/iceberg-1.12/test`, which the `test` source set includes on the default line only. So it runs in the `services:tables:test` task with the 640 MB heap, and its cost there is negligible.

So the count is unchanged: zero cached contexts added.

## 5. Still open (both can follow)

### R1. Can follow, PR body only: a lowered request can now get a different refusal from a check that comes after
Before this change, a lowered request on a table at or below the cluster version went past `refuseUpdate` to later refusals:
- the v3-types refusal (`refuseFormatV3TypesBelowV3`, 400);
- the client-capability gate (`clientCapabilityGate.checkWrite`);
- the read-bridge column-default refusal (400);
- the repository's own checks.

A lowered request that also fails one of those now gets the format 400 instead of that refusal. This only affects requests that ask for a lower version, every one of which used to end in a 4xx or the 500. Fix: one sentence in change 4, naming those checks as coming after the refusal.

### R2. Can follow, test comment only: a comment the new stand-in made stale
At `IrcWriteRoutesAuthTest.java:501-503`, the added `entityManager.clear()` carries this comment, copied from the base's line 1109:
```java
// The House Tables stand-in serves this request's reads from the request's own
// persistence context; ...
```
Since 163 the stand-in's reads are projections with no persistence context, so the comment is false and the call is redundant. The same stale text is in the base at 1109, so this is not introduced by this pull request's logic. Fix: drop the call and the comment in both places, in a later clean-up.

## 6. Not checked

- I ran nothing.
- I did not read the CI logs to confirm that the new test methods executed rather than being skipped. Every job passed.
