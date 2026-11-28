# Review of pull request 152 (`irc/authorize-first-on-rest-commits`, head `478edea6`)

Reviewed by reading only (no build, no run). Diff: `git -C /home/user/oh-authz diff origin/irc-integration...HEAD`
(merge base `81b4cd4d`, three commits `59b3c3c5`, `a51eda90`, `478edea6`).

## 1. Verdict

**MERGE AFTER FIXES**: close F1 first. Everything else can follow.

What matters most:
1. **F1. A commit whose updates change nothing still answers the table's full metadata without the load's checks.** RF-001
   names this case explicitly ("or updates the builder records as no change"). It now takes the write branch: the write
   privilege is checked, then `commitOn` returns before the core and the stored metadata is answered. It skips the read
   privilege, the legacy-lock refusal and the LOCK_ADMIN read check, and the SYSTEM_ONLY check. So a principal with
   UPDATE_TABLE_METADATA reads a legacy-locked or SYSTEM_ONLY-locked table, which both the native load and the native PUT
   refuse. A body as simple as `{"requirements":[],"updates":[{"action":"set-properties","updates":{}}]}` does it.
2. **F2. The "empty commit is a load" claim is only partly true.** The empty commit gets LOCK_ADMIN and
   GET_TABLE_METADATA, but not the load's SYSTEM_ONLY access decision (`authorizationHandler.checkSystemOnlyLockAccess`,
   423) or its capability gate (422). The author declares this. It is still a load refusal that the empty commit gets
   around, and it is one call away from being closed.
3. **F3. Fail-closed, but the status is wrong on the table routes.** When the authorization backend fails, an IRC table
   route answers 403 `ForbiddenException`, where the native routes and the IRC view routes answer 503. The cause is in
   `IcebergRestErrors` and predates this PR. This PR adds early decisions that inherit it, and nothing tests it.

## 2. Findings

### F1. MUST FIX BEFORE MERGE: a no-op commit (non-empty updates that change nothing) reads the table past LOCK_ADMIN, the legacy lock, the read privilege and SYSTEM_ONLY
- Where: `services/tables/src/main/java/com/linkedin/openhouse/tables/api/irc/IcebergRestWriteHandler.java`, `commitTable`
  (lines 263-279) with `authorize` (338-364) and `commitOn` (390-410).
  ```java
  if (request.updates().isEmpty()) { ... authorizeRead ... } else { ... authorizeWrite ... }   // authorize
  ...
  TableMetadata candidate = builder.build();
  if (candidate.changes().isEmpty() && !patch.isPresent()) {
    return false;                                                                             // commitOn
  }
  ...
  return commitOn(identifier, base, request, actingPrincipal)
      ? loaded(identifier)
      : new IcebergRestLoadedTable(base.getTableDto().getTableLocation(), base.getMetadata(), ...);
  ```
- What goes wrong: Iceberg's builder records no change for `set-properties` with an empty map, `remove-properties` with
  an empty set (`TableMetadata.Builder.setProperties`/`removeProperties` return early on empty input, checked in
  `origin/openhouse-1.12.0`), a `set-location` to the stored location, and similar updates. Such a request:
  - is decided only as a write (`checkTableWritePathPrivileges`);
  - never reaches `TableCommitCore.commit`. Natively the core refuses a legacy-locked table for everyone, then checks the
    write privilege, then SYSTEM_ONLY, and only then takes the "unchanged" shortcut (`TableCommitCore.java:66-87`).
  - answers the stored metadata in full: schemas, snapshots, refs, properties including `policies`, and the location.

  Who gains:
  - (a) A principal with UPDATE_TABLE_METADATA but without LOCK_ADMIN reads a **legacy-locked** table. The native load
    asks LOCK_ADMIN (`TablesServiceImpl.checkLoad:100-101`), and the native PUT refuses the table outright.
  - (b) The same principal reads a **SYSTEM_ONLY-locked** table without system-only access. Both native routes refuse
    this with 423.
  - (c) A write-only principal (no GET_TABLE_METADATA) reads any table it may write.

  The one-table transaction route has the same branch but answers 204, so only (a) and (b) matter there, as existence,
  which is already told.
- Scope: RF-001 (`86-review-findings-and-fix-forward.md:47`) covers "a commit with no updates ... or updates the builder
  records as no change". FF-01 asks for "the write-privilege and lock checks ... including for a commit with no
  updates". The author's route table and behavior-change list do not mention the no-op case. The empty-updates test
  covers only `updates: []`.
- How sure: certain from reading. Not run.
- Smallest fix: when `commitOn` finds no change, run the checks the native PUT runs before its own "unchanged" shortcut.
  The cleanest way is to send the no-change case through `TableCommitService.commit` / the core with
  `CommitContext.unchanged(() -> true)` and kind `TABLE_UPDATE`. That reproduces the native order: legacy-lock refusal,
  write privilege, SYSTEM_ONLY, then the shortcut. An alternative is to decide the no-change answer as a load as well
  (the full `checkLoad`, see F2).
- Test that would settle it: on a table with a legacy lock, and on one with a SYSTEM_ONLY lock, a principal with
  `denyOnly(LOCK_ADMIN)` (and the system-only stub false) posts `set-properties {}`. Expect the native PUT's refusal and
  no metadata in the body. A `denyOnly(GET_TABLE_METADATA)` principal posting the same body on an ordinary table shows
  what (c) answers, and that answer should be decided and written into the PR body either way.

### F2. SHOULD FIX: the empty commit skips the load's SYSTEM_ONLY access decision and its capability gate
- Where: `services/tables/src/main/java/com/linkedin/openhouse/tables/services/TableCommitService.java:108-118`
  (`authorizeRead`).
  ```java
  if (LockPolicyValidator.isLegacyLocked(base)) { authorizationUtils.checkLockTablePrivilege(... LOCK_ADMIN); }
  authorizationUtils.checkTablePrivilege(base, principal, Privileges.GET_TABLE_METADATA);
  ```
  The native load also runs `authorizationUtils.checkSystemOnlyLockAccess(tableDto, actingPrincipal)` and
  `clientCapabilityGate.checkLoad(tableDto)` (`TablesServiceImpl.java:98-108`).
- What goes wrong: `{"requirements":[],"updates":[]}` from a principal with GET_TABLE_METADATA returns 200 with the full
  metadata in two cases where the IRC and native loads refuse:
  - the table has a SYSTEM_ONLY lock and the caller has no system-only access (the load answers 423);
  - the client lacks a capability that the table's properties require (the load answers 422).
- Is it safe? The author's reason is that these "are not privilege decisions". The first one is an
  `authorizationHandler` decision. With the default handler the code calls it "not a security control"
  (`AuthorizationUtils.java:66-73`), but a deployment's handler may make it one. The capability gate exists to keep
  clients that cannot read a table from getting its metadata, and the empty commit hands that metadata over. Running both
  checks changes answers only for callers the native load already refuses, so the change moves toward native.
- How sure: certain from reading.
- Fix: expose `TablesServiceImpl.checkLoad` (for example as `TablesService.checkLoad(TableDto, String)`) and call it in
  place of `TableCommitService.authorizeRead`. That also removes the duplicated copy of its first half (see F11).

### F3. SHOULD FIX (inherited, now on more paths): an authorization-backend failure answers 403 on the IRC table routes, not 503
- Where: `services/tables/src/main/java/com/linkedin/openhouse/tables/api/irc/IcebergRestErrors.java:119-131`.
  ```java
  if (failure instanceof AccessDeniedException || failure instanceof ForbiddenException) {
    return error(HttpStatus.FORBIDDEN, ForbiddenException.class, message);
  }
  ...
  if (failure instanceof AuthorizationServiceException || ...) {
    return error(HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableException.class, message);
  ```
  Spring's `AuthorizationServiceException` extends `AccessDeniedException` (`ViewAuditAspect.java:233` relies on this),
  so the 503 branch can never be reached.
- What happens: the OPA handler throws `AuthorizationServiceException("Cannot perform Opa access check")`. On the new
  early decisions (table commit, transaction, empty commit), as on the IRC load before this PR, the caller gets 403
  `ForbiddenException`. Clients do not retry a 403 and report it as a permission problem. The native routes answer 503
  (`OpenHouseExceptionHandler:420`, the more specific handler). The IRC view routes answer 503, because
  `IcebergRestViewHandler.translate` catches `AuthorizationServiceException` first, and the view load keeps that type:
  `ViewsServiceImpl.load` rethrows `AccessDeniedException` and its subclasses as they are.
- Is it fail-closed? Yes, on every new decision: nothing is answered and the refusal is audited FAILED once
  (`auditRefusal` catches any `RuntimeException`). Nothing tests this, as the author says.
- How sure: certain from reading the class hierarchy and the branch order.
- Fix: move the `AuthorizationServiceException` test above the `AccessDeniedException` test in `toError`. It can go in a
  later PR, since it predates this one and fails closed.

### F4. SHOULD FIX (declare it): a principal who may replace a view but not read it can no longer replace it through an Iceberg client
- Where: `IcebergRestViewHandler.loadView` (lines 132-139) together with how clients replace a view. The Iceberg view
  builder's `replace`/`createOrReplace` and `updateProperties` all load the view first.
- What goes wrong: a write-only principal (UPDATE_TABLE_METADATA without GET_TABLE_METADATA) could replace a view through
  `RESTCatalog` or Spark before this PR. Now:
  - On 1.12 the load raises `ForbiddenException`.
  - On 1.5.2 the client wraps the 403 in `NoSuchViewException("Unable to load view ...")`. A `createOrReplace` then
    takes the create branch and gets 409 `AlreadyExists`, or 403 without CREATE_TABLE. A `viewExists` (a load on 1.5)
    says false, so `CREATE VIEW IF NOT EXISTS` on an existing view it cannot read fails with 409 instead of doing
    nothing.
  - Spark on 1.5 reports a view it may not read as not found, rather than forbidden.

  The native OpenHouse client is not affected, because the native view GET makes no decision. The PR body says "only
  callers who lack the privilege now named" see a change. That is literally true, but it does not tell the reader that a
  principal allowed to write loses the client-side replace workflow, or that 1.5 turns the 403 into "not found".
  The refusals stay closed.
- How sure: likely from reading the client paths named above. Not run.
- Fix: list it under behavior change 4 in the PR body and in `85-behavior-change-ledger.md`. No code change is needed
  unless the owner wants write-only principals kept.

### F5. MINOR: time of check versus time of use on the view commit (the row decided on is not the row used)
- Where: `IcebergRestViewHandler.commitView:156-169`. `authorizeCommit` decides on the reference built from one House
  Tables read (`viewsService.viewReference`). `load(...)` then reads the row and the metadata again, and
  `IcebergRestViewSpelling.apply` checks the requirements against the second read.
- What goes wrong: suppose the view is dropped and recreated by its owner between the two reads, with a new UUID and an
  ACL that denies the caller. The caller's `assert-view-uuid` is then checked against the new view and the 409 carries
  the new UUID. The write cannot land on the wrong view: the native PUT decides again on its own read, and the base
  metadata-location check refuses a moved base. Only the 409 text leaks, and the race window is milliseconds.
- On tables this does not happen: the decision and every check use the one `loadBase` result. The core's second decision
  uses the same `TableDto`, so it adds nothing beyond a fresh backend query. The House Tables compare-and-set on the
  metadata location keeps a write off a recreated table.
- Fix: have `load` check that the row's UUID equals `reference.getTableUUID()` (404 or 409 otherwise), or decide inside
  `load` on the row it reads.

### F6. MINOR: allowed empty or no-op commits are not audited, while their refusal is audited as a READ
- `auditRefusal` (`TableAuditAspect.java:228-249`) emits nothing on success. An allowed empty commit, which the PR calls
  "a load", therefore leaves no READ SUCCESS event, while the IRC load and the native GET do leave one. Before this PR
  nothing was emitted either, so allowed callers see no change. The audit trail of a "load" that succeeds is still
  missing. It could be fixed together with F1 and F2.

### F7. MINOR: the commit answers still give a writer without the read privilege the full metadata, and the reload is unchecked
- `commitTable` answers `loaded(identifier)` (`IcebergRestWriteHandler.java:1163-1170`), a `loadBase` with no checks.
  After a real commit, a writer without GET_TABLE_METADATA gets the full metadata. If the table is replaced between the
  commit and the reload (RF-007, FF-41), the answer is another table's metadata, with no decision made on it. The author
  declares this only for "create and replace responses". It holds for every commit answer, and it predates this PR.

### F8. MINOR: the staged-create completion on an existing table reads the metadata file only to pass a non-null base
- `commitCreate:544-551`: `tableCommitService.loadBase(identifier).getMetadata()` reads House Tables and the metadata
  file, for any caller, before `validateCreate`, and `AssertTableDoesNotExist.validate` needs only a non-null base. The
  native create's `findById` also loads the table, so this is the same as native and not a leak. It is still a storage
  read that any caller can trigger and that is not needed.

### F9. MINOR: the changed Spark pin dropped the client-level denied replace
- `integrations/spark/spark-3.5/openhouse-spark-itest/.../IcebergRestCatalogViewTest.java:274-300`. The
  stranger-replace-is-403 assertion is gone because the stranger can no longer load. It could be kept end to end by
  adding a second principal to `GrantStoringAuthorization` that may read but not write views. A denied replace is
  covered at the service level in `IrcViewRoutesAuthTest.aFailingReplaceOfADeniedPrincipalIs403AuditedAsTheNativeRefusal`,
  so the drop loses only the client path. Dropping `viewExists` is justified: on 1.5 it is a load (see F4). The new
  cause-chain walk can fail for the right reason: it fails if no `ForbiddenException` naming `irc_stranger` is found.

### F10. MINOR: the audit kind of a refused commit is approximated from the request
- `auditedKind` / `touchesSnapshots` (`IcebergRestWriteHandler.java:372-386`). The core picks its kind from the applied
  change (`changesSnapshots(base, candidate)`). The refusal picks it from the update types. They differ for a
  `set-snapshot-ref` to the ref's current snapshot: the request calls it COMMIT, the core would see no snapshot change.
  This is an edge case and only affects the audit of refused requests. Matching on the simple class name (`startsWith("RemoveSnapshot")`)
  is fragile but works on both lines.

### F11. MINOR: duplicated authorization code
- `TableCommitService.authorizeRead` repeats the first half of `TablesServiceImpl.checkLoad`, and F2 comes from that
  copy.
- `ViewsServiceImpl.authorizeRead` (lines 188-195) repeats the decision in `loadReadableView` and translates a read
  decision with `translateWriteFailureTo`. That is harmless, since both keep `AccessDeniedException`, but it reads
  wrong.

### F12. MINOR: no tests for the replica, legacy-lock and backend-failure branches
- The SYSTEM_ADMIN decision on a replica table, LOCK_ADMIN for an empty commit on a legacy-locked table, SYSTEM_ONLY, an
  authorization-backend failure, the no-op commit (F1), and the refused transaction's audit event are all read and not
  run. The author says so for replica and legacy lock. Add these with the F1 fix.

## 3. Behavior changes

| # | Change | Who | Toward/away from native | In the author's list |
|---|---|---|---|---|
| 1 | Empty table commit or transaction without GET_TABLE_METADATA (or without LOCK_ADMIN on a legacy-locked table): 200/204/409 becomes 403, audited as FAILED READ | denied callers | toward (load semantics) | yes (1) |
| 2 | Table commit or transaction with updates, without the write privilege: 409/400 (requirements, apply, validation, lock flip, legacy-lock refusal) becomes 403, audited as FAILED UPDATE/COMMIT/REPLACE_COMMIT | denied callers | mixed: the native PUT gives the lock-flip and legacy-lock 400s *before* 403 to anyone; IRC now gives 403 first | yes (2); the legacy-lock reorder is listed inside it |
| 3 | View commit without the replace privilege: 409/400 (unaudited) becomes 403 audited as FAILED REPLACE; an empty view commit also needs GET_TABLE_METADATA (200 becomes 403) | denied callers | toward | yes (3) |
| 4 | IRC view load without GET_TABLE_METADATA: 200 with the definition becomes 403; an authorization outage is 503 | denied readers | away from the native GET (which has no decision), by decision FF-03 | yes (4) |
| 4a | Consequences of 4 for clients: write-only principals cannot replace through a client; 1.5 reports "not found"; `viewExists` false on 1.5 | write-only principals, 1.5 users | new | **no** (F4) |
| 5 | Staged-create completion on an existing table: the `assert-create` failure is answered first, for everyone | hand-built requests only | n/a | yes (5). Checked: `UpdateRequirements.forCreateTable` puts `AssertTableDoesNotExist` first and, with a null base, adds no other requirement, on both `origin/openhouse-1.12.0` and `apache-iceberg-1.5.2`, so Iceberg clients send it alone. No test or kit case relies on the old order (`IrcTransactionRouteTest.everyRequirementTypeRefuses...` uses the transaction route, which never calls `commitCreate`). |
| 6 | An allowed commit triggers the write decision twice (two backend calls) | allowed callers | n/a (cost) | yes |
| - | A no-op commit still answers metadata past the read and lock checks | writers | unchanged, and wrong | not mentioned (F1) |

## 4. Tests

- **`IrcWriteRoutesAuthTest`, 7 new tests.**
  - `denyOnly` denies exactly one privilege on both table and database decisions, so there is no allow-all or deny-all
    shortcut.
  - The "tells nothing stored" checks read the response body for the stored UUID, location and metadata location. The
    allowed run of the same request asserts that the UUID *is* in the 409, so the absence check can fail for the right
    reason.
  - `anEmptyCommitIsALoad...` compares the audit event JSON with the native GET refusal's event and checks the decision
    was GET_TABLE_METADATA on `e1`. Good.
  - `aCompletionOnAnExistingTable...` would fail on the old code (old answer: "UUID mismatch" carrying the stored UUID).
  - `anUpdateThatCannotApplyIs403...` checks the status only, not the body.
  - "Allowed answers unchanged" is asserted only through `metadataFileLocation` equality within the new code, not
    against a recorded answer from the base. The existing route suites (`IrcWriteRoutesTest` and others) are the real
    pins and pass, per the author.
  - Missing: the no-op commit (F1), replica, legacy lock, SYSTEM_ONLY, backend failure, and the transaction's audit
    event.
- **`IrcViewRoutesAuthTest`.**
  - `anIrcViewLoadNeedsTheReadPrivilege` checks the decision's resource, including the UUID from the stored view, and
    that a missing view makes no decision.
  - "Byte-identical" compares two answers from the head code (allowed, then UPDATE denied). That shows UPDATE is not
    needed. It does not show the answer equals the base's.
  - `aFailingReplace...` compares the IRC refusal's audit JSON with the native PUT refusal's and checks that the stored
    metadata is unchanged. Sound.
  - `anEmptyViewCommitNeedsTheReadPrivilege` checks the status and the body, not the audit.
  - The changed pin `nativeReadsListAndExistsMakeNoDecisionAndTheIrcLoadIsRefused` is right under FF-03. The old pin
    asserted the very defect in RF-018.
- **`IrcRegisterViewAuthTest.aRegisteredViewsLoadNeedsTheReadPrivilege`**: can fail for the right reason. It checks the
  body has no SQL.
- **Spark `IcebergRestCatalogViewTest.aDeniedPrincipalIsForbiddenToReadAndWrite`**: see F9.
- **`IrcReadRoutesAuthTest`**: only an `@Import` was added to share the Spring context. Harmless.
- **Claimed but not tested**: the replica and LOCK_ADMIN branches, fail-closed on backend failure, "denied attempts
  audited exactly once" on the transaction route, and an allowed caller's audit staying exactly as before. The last one
  holds by reading, because an allowed early decision emits nothing.

## 5. Checked and found sound

- **Order of checks.** On the table commit and transaction routes, the decision follows only `validateGetTable` (ids)
  and `loadBase` (404, House Tables plus metadata read, as the native PUT's `findById`). Requirements, update
  application, validation, policy patch and lock rules all come after it.
- **Which principal, which resource.** The decision is on the stored `TableDto` from `loadBase` (stored database, id,
  UUID, table type) and the authenticated principal. The body's `identifier` is ignored on the commit route. On the
  transaction route the body's identifier names the table, and both the decision and the write are on that table's
  stored row, so nothing can steer the decision to another table.
- **Replica tables.** The SYSTEM_ADMIN branch is the core's, through the same `checkTableWritePathPrivileges`.
- **Body that does not parse.** It gets 400 from `IcebergRestJson.read` in the controller, after the authentication
  interceptor and before any read. A missing `updates` or `requirements` parses as an empty list on both lines.
- **`assert-create` cannot skip the decision.** Adding it to any commit routes the request to `commitCreate`. On an
  existing table that answers "already exists" (existence only, as the native create's 409 does to anyone). On a missing
  table it needs CREATE_TABLE.
- **Other table routes.** Plan and credentials go through `loadIcebergTable`, which is the full `TablesService.loadTable`
  checks (unchanged). Metrics: GET_TABLE_METADATA on the House Tables ref (unchanged). The table HEAD makes no decision
  (unchanged).
- **Other view routes.** List (names) and HEAD (the native GET, 204/404) make no decision, as natively. The view decision
  is on `authorizationReference(row)`, the stored row's database, id, UUID and pointer, and the view gate and the 404
  come before it.
- **View audit.** A refused view commit is audited once (`ViewAuditAspect.auditRefusal`) and never reaches the native
  PUT. An allowed one is audited once by the native PUT advice. The same holds for tables (`TableAuditAspect.auditRefusal`
  emits the same shape as `auditTableWrite`'s FAILED event, and the core's advice is not reached on a refusal).
- **Backend failure on the view load.** It is 503: `AuthorizationServiceException` is an `AccessDeniedException`, so
  `ViewsServiceImpl.load` rethrows it as it is, and `translate` maps it to 503.
- **Native routes unchanged.**
  - `TableAuditAspect` and `ViewAuditAspect` gain one public method each, with no new pointcuts.
  - `TableCommitService` gains two methods.
  - In `ViewsServiceImpl`, `loadView` now delegates to `load` with a no-op hook. The only caller of `loadView` is the IRC
    view handler. The widened catch (`AccessDeniedException`) cannot fire from the no-op hook.
  - No native controller, handler, `TablesServiceImpl` or `TableCommitCore` line changed.
- **Cruft.** No new private ids. "D30" in the rewrapped view handler comment was already there.

## 6. Not checked

- Nothing was built or run.
- Whether `catalog.loadTable` on a view's key gives 404 on the table commit route. It is unchanged by this PR.
- The register-view route's own order before its create decision (pull request 149's). Only the new test was read.
- The 1.5.2 `RESTSessionCatalog` view-builder path behind F4, beyond the author's statement that it wraps the 403 in
  `NoSuchViewException`.
- CI results. The author reports 14 of 14 jobs green on `478edea6`.
