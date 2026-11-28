# Review: irc-commit-write (commit core of the tables service, IRC table write routes)

Reviewer slug `irc-commit-write`. Read-only review of `/home/user/oh-review` at `81b4cd4d` against LinkedIn main `bb8929e1`.
Pull requests: 89 (`94877d49`), 94 (`14f23870`), 96 (`0010df15`), 106 (`bfdcf266`), 134 (`d30cb9f6`), 120 (`a217cce9`).
Paths below are relative to `services/tables/src/main/java/com/linkedin/openhouse/tables/` unless they start with `iceberg/`.

## 1. Bottom line

The native refactor of pull request 89 is faithful. I read the native table PUT, snapshots PUT and create paths before
and after, line by line. The order of validations, the error types and wording, what is saved, the no-op short-circuit
and the return flags are unchanged. The three guards are untouched: `versionCheck`, the retry `CACHE` and the House
Tables compare-and-set. The IRC write layer mostly calls native rules rather than copying them. The compare-and-set for
IRC update commits is real: the server builds the candidate from a base it loads itself, and the repository's
`versionCheck` compares that base with the head. What matters most:

1. **BLOCKER, security.** An IRC commit with no updates returns the table's full metadata to any authenticated
   principal, with no privilege check. `POST /v1/namespaces/{ns}/tables/{t}` with `{"requirements":[],"updates":[]}`
   reads the table without `GET_TABLE_METADATA`. No test covers it.
2. **SHOULD FIX, security.** Every IRC commit checks requirements, applies updates and runs validation before the
   write-privilege check. A principal with no access gets the stored table UUID, ref snapshot ids, schema ids and spec
   ids back in the 409 requirement messages, and builder or validator errors that describe the table.
3. **SHOULD FIX.** An IRC rename onto a view's name knowingly runs the native rename. That rename fails after five
   retries, leaves five orphan metadata files and takes about 1.8 s; only then is the failure mapped to 409. A
   House Tables view lookup beforehand would avoid every write. The same mapping also calls a genuine concurrent House
   Tables conflict "already exists".
4. **SHOULD FIX, cruft and design.** `IcebergRestWriteHandler` is 1,100 lines and accretes create, staged completion,
   register, directory-reuse forensics, rename error remapping and policy patching in one class. It cites D30, D19,
   D20, V14 and similar ids throughout. `TableCommitCore` holds the native create-path error mapping as static helpers.
   There are two memoizers, and one overload is dead.
5. Inherited, not introduced, but now reachable by a plain Iceberg client: a replace commit has no base check. The
   replace branch of `OpenHouseInternalRepositoryImpl.save` skips `versionCheck`, so a commit landing between the
   server's `loadBase` and the replace is lost. The javadoc claim "a commit that landed in between is refused by the
   core's guards" is false for replaces. Also, after a commit fails past `failIfRetryUpdate`, the per-JVM 5-minute
   `CACHE` refuses every later commit on that base version on that instance. Because IRC always rebuilds on the
   server-loaded base, every client retry carries the same `COMMIT_KEY` and is refused until the entry expires.

## 2. Defects

### D-1 BLOCKER: a no-op IRC commit is an unauthorized table read
- **Where.** `api/irc/IcebergRestWriteHandler.java:259-274` and `:338-345`. The no-op branch is also at `:356-358`.
  - `commitTable` calls `TableDtoWithMetadata base = tableCommitService.loadBase(identifier);`.
  - `loadBase` is documented in `services/TableCommitService.java:92-105` as "with no read checks (a write runs the
    write checks)".
  - `commitOn` returns early with `if (request.updates().isEmpty()) { return false; }`.
  - `commitTable` then answers `new IcebergRestLoadedTable(base.getTableDto().getTableLocation(), base.getMetadata(), tableFileIo.config(base.getTable()), false)`.
  - The write checks live in `TableCommitCore.commit`, which is never reached.
- **What goes wrong.** Any authenticated principal can read every table's full metadata: schemas, snapshots,
  properties including `policies` (PII column tags, sharing), refs and location. It only needs `POST` with empty
  updates, or with updates that the builder records as no change, such as `set-current-schema` to the current id.
  Natively, a GET of that table answers 403.
- **How sure.** Certain from reading. No test covers a denied principal with empty updates:
  `e2e/h2/IrcWriteRoutesAuthTest.java` covers only commits with a change.
- **Fix.** On the no-op branch, either run `authorizationUtils.checkTableWritePathPrivileges(...)`, as the native PUT
  does before its own no-op short-circuit (`TableCommitCore.java` lock, privilege, then `unchanged`), or load through
  `tablesService.loadTable(db, t, principal)`. Test it: stub a denial and expect 403 for `{"requirements":[],"updates":[]}`.
  The transaction route has the same branch (`:313`, answers 204), so it leaks only existence.

### D-2 SHOULD FIX: requirement and validation answers come before authorization and leak stored state
- **Where.** `IcebergRestWriteHandler.commitOn:338-398`:
  - `IcebergRestRequirements.validate(request, baseMetadata)` runs first.
  - Then the updates are applied: `update.applyTo(builder)`.
  - Then `TableCommitService.commit` runs `context.getValidation().run()` and `CandidateViews.toView(...)`
    (`TableCommitService.java:69-84`).
  - Only after all of these does `TableCommitCore.commit` check `checkTableWritePathPrivileges`.
- **What goes wrong.** Iceberg's requirement messages include the stored values, for example
  `"Requirement failed: UUID does not match: expected %s != %s", base.uuid(), uuid` and
  `"... has changed: expected id %s != %s"` (fork `core/.../UpdateRequirement.java:64-65, 118-120, 164-166`). A
  principal with no privilege on the table learns its UUID, ref snapshot ids, schema, spec and sort-order ids, and from
  the D19 refusals whether it has statistics or encryption keys.
- **Against native.** The native PUT also runs its request validator before authorization, but that validator judges
  only the request and never the stored table. Here the IRC path discloses stored state.
- **How sure.** Certain from reading.
- **Fix.** Run the write-privilege and lock checks immediately after `loadBase`, before the requirements. This also
  fixes D-1.

### D-3 SHOULD FIX: IRC rename onto a view's name writes five orphan metadata files, and a real race is answered "already exists"
- **Where.** `IcebergRestWriteHandler.renameTable:983-1007`, which calls `tablesApiHandler.renameTable(...)` and
  catches `CommitFailedException` whose cause is a `HouseTableConcurrentUpdateException`. The merge message of #106
  says "It leaves five orphan metadata files, one per commit retry, and takes about 1.8 s. The IRC rename goes through
  it, so it leaves the same files."
- **What goes wrong.** Storage leaks on every attempt. Separately, any `HouseTableConcurrentUpdateException` during a
  rename, including a genuine concurrent update of the source row, is answered 409 `AlreadyExistsException`, "Table
  already exists". That is untrue, and a client will not retry it.
- **How sure.** Certain from reading; the orphan files are stated by the author.
- **Fix.** Before calling the native rename, call `houseTableRepository.findViewById(toKey)` (already used at `:997`)
  and answer 409 without writing. Rethrow other conflicts as 409 `CommitFailedException`.

### D-4 SHOULD FIX: replace commits have no base check, so a commit between load and replace is lost
- **Where.** `repository/impl/OpenHouseInternalRepositoryImpl.java:192-215`. The replace branch never calls
  `updateEligibilityCheck`/`versionCheck`, which only the update branch at `:221-228` runs. `abortIfWriterBaseDivergedFromCatalog`
  in `iceberg/.../OpenHouseInternalTableOperations.java:930-932` states that replace commits are "intentionally not
  defended".
- **What goes wrong.** An IRC replace (RTAS through RESTCatalog) is rebuilt by the server on the base it loaded. If
  another writer's append lands between `loadBase` and `replaceTable`, the replace stores the candidate's snapshot list
  without that append. The append is acknowledged and then silently dropped.
- **Scope.** The window is milliseconds, inside one request. Iceberg replace semantics accept last-writer-wins against
  the client's base, but not against a base the server itself loaded and then failed to check. This is not a
  behavior change, since native RTAS behaves the same way.
- **Doc.** The handler javadoc (`:83-88`, "so a commit that landed in between is refused by the core's guards")
  overstates the guarantee.
- **How sure.** Likely; I cannot tell the exact interleaving without running it.
- **Fix.** On the IRC replace, compare the stored head location with `baseVersion` inside the repository before
  `replaceTable`, gated to IRC replaces so native behavior is untouched. At minimum, correct the javadoc.

### D-5 SHOULD FIX (inherited, IRC makes it systematic): a failed commit blocks that base version on the instance for 5 minutes
- **Where.** `iceberg/.../OpenHouseInternalTableOperations.java:152-153`, where `CACHE` expires after 5 minutes, and
  `failIfRetryUpdate:978-993`, called at `:454` after `prepareCommit` and before the metadata and House Tables writes.
- **What goes wrong.** If the metadata write or the House Tables write fails after `failIfRetryUpdate` has cached base
  v1, every later commit on v1 on that JVM is refused 409 "stale" for 5 minutes. The IRC server always rebuilds on the
  server-loaded base (v1 again), so all of the Iceberg client's automatic retries fail. The table stays unwritable
  through that instance until the entry expires or another instance commits.
- **Against native.** The native client also re-sends v1 after a refresh, so this is pre-existing, but IRC makes it
  systematic.
- **How sure.** Likely; I cannot tell without running a fault-injection test.
- **Fix.** Out of this area's scope; record it for follow-up. A test with an injected House Tables failure followed by
  an IRC retry would settle it.

### D-6 MINOR: a view update sent to the table commit route is a 500
- **Where.** `IcebergRestWriteHandler.applyTakingOutThePatch:430` (`update.applyTo(builder)`) and the final
  `ServiceFailureException` fallback in `IcebergRestErrors.toError`.
- **What goes wrong.** Iceberg view updates such as `add-view-version` and `set-current-view-version` throw
  `UnsupportedOperationException` from `applyTo(TableMetadata.Builder)`. That exception is not mapped, so the answer is
  500 `ServiceFailureException`, and the error is logged at error level.
- **Fix.** Map `UnsupportedOperationException` from the update application to 400.
- **How sure.** Likely; I did not check the exact default in 1.12 `MetadataUpdate`.

### D-7 MINOR: the commit response is reloaded, not the committed metadata
- **Where.** `commitTable:267` returns `loaded(identifier)`, which reloads the table after the commit.
- **What goes wrong.** If another commit lands in between, the client is answered with that later metadata as the
  result of its own commit. Iceberg's `RESTTableOperations` adopts it as current, which is mostly harmless but not
  what the spec answers.
- **Fix.** Return the stored DTO and metadata that `repository.save` produced.

### D-8 MINOR: a completion with no location relies on a format-version accident for its directory guard
- **Where.** `commitCreate:487-501`. `requireSnapshotsIn` and `refuseADirectoryInUse` run only when
  `candidate.location() != null`.
- **What goes wrong.** Today a null location is unreachable: format v1 to v3 require a location in
  `TableMetadata`'s constructor, and format 4 is refused by `requireTheClustersFormatVersion`. If either rule changes,
  a create-with-data could take its UUID from a snapshot's manifest-list path
  (`utils/TableUUIDGenerator.extractUUIDFromRequestBody`). That path can be the directory of a table renamed away from
  this name, which is the very hole the handler's comments describe.
- **Fix.** Refuse a completion that carries snapshots but no location, explicitly.

### D-9 MINOR: interfaces gained abstract methods
- **Where.**
  - `repository/OpenHouseInternalRepository.java` gained `findWithMetadataById` with no default (pull request 88/94).
  - `services/TablesService.java` gained `createTable(body, creator, UUID)` and `stageCreateTable` with no default
    (pull request 94).
- **What goes wrong.** Any other implementation, for example a deployment's own repository or service, no longer
  compiles.
- **Fix.** Give them defaults, as `stageCreate` already has.

### D-10 MINOR: lock-flip check NPE on a stored table without a lock state
- **Where.** `services/TableCommitCore.java` `checkIfLockPoliciesUpdated`, with policies from a policy patch.
- **What goes wrong.** The NPE is native behavior ("as it always has"). Through IRC, a policy patch carrying a lock
  state on a table whose stored policies have none reaches it and answers 500.
- **Fix.** Make it null-safe for the IRC caller.

## 3. Behavior changes against LinkedIn main

I accounted for every file in this area that existed at `bb8929e1` and was modified, using
`git diff --diff-filter=MDR` on services, repository, audit, dto, model and internalcatalog main.

| # | What (before → after) | PR | Default on? | Justification / follow-up | Upstream as is? | Size |
|---|---|---|---|---|---|---|
| B-1 | `services/TablesServiceImpl.java` native table PUT update path: inline rules (`bb8929e1` :152-198) → `TableCommitCore.commit` (HEAD :186-211, `TableCommitCore.java` :57-104). Order is identical (fail-on-exist, staged check, lock flip, legacy lock with "cannot be updated.", privilege, SYSTEM_ONLY, no-op after mapping, capability gate on the same property values since the mapper copies `requestBody.tableProperties`, lock prepare, strip, save). Error mapping identical. **No behavior change**, checked by reading. | 89 | n/a | merge message | yes, if `TableCommitCore` is reshaped (see §4) | — |
| B-2 | `services/IcebergSnapshotsServiceImpl.java` existing-table path: inline lock ("cannot be written to"), privilege, capability, prepare, strip, save, with ECME carrying the request clusterId → same steps in the core with `CommitKind.SNAPSHOTS_UPDATE`. Same clusterId and base version in the message, CommitStateUnknown still unmapped (raw) as before, return flag false. Create path unchanged apart from the static save helper. **No behavior change**, checked by reading. | 89 | n/a | merge message | yes | — |
| B-3 | `TablesServiceImpl.putTable` takes its UUID through a `Supplier`, still evaluated only inside `orElseGet` for a new table. **No change.** | 94 | n/a | | yes | — |
| B-4 | `strip read-bridge defaults` now skipped when `FormatVersionRule.writesFormatV3` (TablesServiceImpl :238-241, snapshots :103-106, core :94-98). Off when v3 is off. | 124 (not mine) | gated | v3 reviewer | — | — |
| B-5 | `OpenHouseInternalRepositoryImpl.save` create branch: `tableProps.remove(CreateHistory.KEY)` (:183). The key is `openhouse.`-prefixed, which `BasePreservedKeyChecker.allowKeyInCreation` already keeps out of native creates, so it is defensive only. **No observable change** for native requests. | 134 | n/a | code comment | yes | — |
| B-6 | `OpenHouseInternalTableOperations.doCommit`: `CreateHistory.apply(base, metadata)` first (:436). A no-op unless the transient key is present; it strips the key on non-creates. Native requests cannot carry it (see B-5). Other callers of the internal catalog cannot reach it either: replication sends `openhouse.*` properties through the same repository filters. I did not trace every internal-catalog caller. **No change** by reading. | 134 | n/a | | yes, but it should not live in the shared commit path (see §4) | — |
| B-7 | `OpenHouseInternalTableOperations` row-limit refusal, `rootMetadataFileLocation` computed before `prepareCommit`, `PendingCommit.io` | 121, 124 (not mine) | default on (121) | other reviewers | — | — |
| B-8 | `dto/mapper/iceberg/PartitionSpecMapper.toClusteringSpec`: the limit check moved after mapping (`toClusteringColumns`). Same message and type. Only the failure case changes: a table with more than 4 clustering columns *and* an unmappable type now fails with the type error instead of the limit error. Practically **no change**. | 96 | n/a | merge message | yes | — |
| B-9 | `audit/TableAuditAspect.java`: four new advices on new methods only (IRC load, `TableCommitService.commit`, `TablesService.createTable(…,UUID)`, `stageCreateTable`). Existing advices untouched. **No change** to native audit. | 94, 88 | n/a | | yes | — |
| B-10 | `repository/OpenHouseInternalRepository.java`, `services/TablesService.java`: new interface methods, two without default (D-9). A source-compatibility change for extenders, not a runtime behavior change. | 88, 94 | n/a | none | no (needs defaults) | SMALL |
| B-11 | `repository/impl/OpenHouseInternalRepositoryImpl` other hunks (variant refusal, format version on create, replace and update, `stageCreate`, `findWithMetadataById`); `InternalRepositoryUtils.formatVersion`; `BaseIcebergSchemaValidator`; `TablePolicyManager`; `PoliciesSpecMapper`; `OpenHouseTablesApiValidator`; `ViewsServiceImpl`; `ViewsDisabledService` (deleted) | 124, 129, 139, 95, 142 | — | not in my assignment; `stageCreate` and `findWithMetadataById` are new methods with no native caller | — | — |
| B-12 | New IRC routes (create, staged create, commit, transaction, drop, rename, register): new surface, not a behavior change. Checked that the off path is unchanged: the native routes do not call `TableCommitService`, `CandidateViews`, `IcebergUpdates` or `CreateHistoryScope`. | 94, 96, 106, 120, 134 | always registered (no switch) | | n/a | — |

Native rename, drop and create through the native routes are unchanged. `TablesServiceImpl.renameTable` and
`deleteTable` are not in the diff. The native 500 for a rename onto a view, with its orphan files, is pinned as native
(#106) and left alone.

## 4. Cruft

**Accretion and size**
- `api/irc/IcebergRestWriteHandler.java` (1,103 lines) holds create, staged create, commit, transaction, staged
  completion, register (≈250 lines), directory-reuse scanning of live and soft-deleted rows, rename remapping, the
  policy-patch plumbing and format-version refusals. Split it into a create/completion class, a register class and a
  commit/rename class. Move `withoutEndpoint`, `refuseADirectoryInUse` and `uses` into a "table directory ownership"
  component.
- `services/TableCommitCore.java` has static `saveTableUpdate` and `saveSnapshots` that native *create* paths also call
  (`TablesServiceImpl:291`, `IcebergSnapshotsServiceImpl:112`). That is create-path error mapping living in the
  "existing table" core. Move both into a small `RepositoryErrors` helper.
- `TableCommitCore` is rebuilt per call in `TablesServiceImpl.newCommitCore()` and `TableCommitService.commit(TableCommit)`,
  each passing four collaborators. Make it a Spring bean.

**Two implementations of one thing**
- Memoization exists twice: `TablesServiceImpl.memoize` (:304-318) and `TableCommit.candidate()` (lazy field). Keep
  one.
- The candidate spelling exists twice: `CandidateViews.toView` builds the DTO and `IcebergUpdates.toUpdateBody` builds
  the native request from the same candidate, both through `staticTable` and the same mappers. One converter that
  produces the request, followed by the native mapper, would remove a source of drift.

**Dead code**
- `services/IcebergUpdates.java:35-52`, the 7-argument `toUpdateBody` overload, has no caller.
- `TableCommitService.commit(TableCommit)` is public but used only inside the class; make it private.

**Comments citing private ids or narrating history**
- `IcebergRestWriteHandler.java:77, 90, 230, 351, 465, 504, 681, 691, 1063` (D30, D19, D20, V14).
- `repository/CreateHistoryScope.java:10` ("work item V14").
- `iceberg/.../CreateHistory.java:18` ("work item V14").
- `OpenHouseInternalRepositoryImpl.java:182` ("(V14)").
- `OpenHouseInternalTableOperations.java:435` ("IRC, V14").
- `TableCommitCore.java:94-95` ("work item W6").
- `CommitContext` and `TableCommitService` javadocs name "the planned Iceberg REST Catalog commit route", which is
  history.

**Leftovers and design smells**
- `CreateHistoryScope` is a `ThreadLocal` that carries data from the API layer to the repository so that "no signature
  an aspect or a deployment binds to changes". That is a workaround for the audit aspects' `args(...)` pointcuts.
  Combined with a transient property read inside the *shared* `doCommit`, it is the kind of indirection upstream would
  reject. Pass the history explicitly: a field on `TableDto`, or a create entry that takes it.
- `IcebergRestRequirements` is a static helper class whose javadoc says it was "kept apart because the replace-shape
  rule and its reasoning read on their own". Fine, but small.
- The replace-shape heuristic (`isReplace`) is duplicated with "the in-process seam's adapter
  (`OpenHouseRestAdapter.isReplace`)" according to its own comment. Two copies.

## 5. Tests

- **No test pins the authorization of an IRC commit with no updates** (D-1). `IrcWriteRoutesAuthTest` covers only
  commits with a change.
- **No test pins that requirement checks run after authorization** (D-2). The current order is effectively pinned the
  other way: `IrcWriteRoutesTest.aFailedRequirementIs409CommitFailedAndChangesNothing` runs with permissive
  authorization.
- The pull request 89 "no behavior change" claim rests on `StoredMetadataGoldenTest` (stored files only, normalized)
  and `CommitPathUpdateRulesTest` (4 rules). Neither pins ordering between lock, privilege and capability, nor the
  conflict wording. My reading found no change, but the tests would not catch one in ordering. `TableCommitServiceTest`
  was written against the new entry point, so it cannot detect a native change.
- Pull request 94 says "two writers on one table: nothing is lost" (`IcebergRestCatalogWriteTest`). That covers client
  think-time races only. The server-internal window between `loadBase` and `save`, and the replace window (D-4), are
  "read in code, not tested" by the author's own admission.
- `IrcWriteRoutesTest` pins the replace heuristic, under which a hand-made commit without assertions is a replace.
  That is a declared choice (D20), not a guess, but it also means such a commit skips `versionCheck` and the partition
  evolution check. The test does not state that consequence.
- Pull request 106 pins the native 500 for a rename onto a view "as it is". That pin is right for native, but the IRC
  test should also assert that no metadata files were written once D-3 is fixed.
- Not run by the authors (from the merge messages): the real-House Tables mode, the MySQL-backed mode, a 1.12
  RESTCatalog against the write routes (#94), the delta-harness (#94, #120), and the Spark 3.5 statement tests (#94,
  #120).

## 6. Per pull request

| PR | Leaves true holds? | Why |
|---|---|---|
| 89 V1 | yes | The native order, error mapping, guards and no-op short-circuit were read line by line and are unchanged. The D19 refusals are in `CandidateViews.refuseUnpersistedChanges`. |
| 94 V3 | partly | "Every write runs the native rules": the write-privilege rule is skipped for a commit with no updates (D-1). "IRC requirements are checked … before the candidate is built" is true, but they are also checked before authorization (D-2). The location and UUID parts of "the server owns location, UUID and format version" were later relaxed by #106 to "ignored" on create. The rest holds. |
| 96 | yes | `validateUpdateTable` runs on `IcebergUpdates.toUpdateBody` for every commit and replace (`commitOn:385-398`). Policy keys: the refusal was later replaced by the patch in #99. The `toClusteringSpec` split preserves behavior (B-8). |
| 106 V13b | yes | Location is ignored on create and on replace (SetLocation filtered); an ordinary set-location is still refused by `CandidateViews`; cross-database rename answers 404/406; a format-version mismatch on create is 400; a rename onto a view is 409. The orphan files remain (D-3). |
| 134 V14 | yes | `CreateHistory.of`/`apply` verify that the final parts are the renumbered ones. Neither API can set the key (filtered as `openhouse.` on native, stripped in the create branch). `last-column-id` is server-computed by the builder. |
| 120 V8 | yes | `changes.size() != 1` is refused before any load or authorization; one change goes through `commitOn` on the named table only; `assert-create` there fails against the loaded base, or 404 when the table is missing. Nothing lets a second table in. The one-table route inherits D-1 and D-2 (answers 204, so it leaks only existence and requirement messages). |

## 7. For the clean re-implementation

What must survive:
- One service-layer commit core for an existing table, which both native routes and the IRC route call, with the
  native order: lock flip (PUT only), legacy lock, write privilege, SYSTEM_ONLY, no-op, capability gate, lock
  preservation, strip, save.
- An IRC commit is built server-side: load the base, check requirements against it, apply the updates with Iceberg's
  `TableMetadata.Builder`, then commit with the loaded base location as `tableVersion`. The repository's `versionCheck`
  and the House Tables compare-and-set are the real compare-and-set.
- **Authorize immediately after loading the base, before requirements, updates or validation**, including for no-op
  commits.
- Spell the candidate as the native request the OpenHouse Java client would send, run the native request validator on
  it, then decode it with the native mapper.
- Refuse, rather than drop, what the update path does not store: location, UUID, format version (except the allowed
  upgrade), statistics, encryption, removing every snapshot, an inexpressible partition spec.
- Create and staged create spelled as the native create. The staged create writes nothing. The completion verifies the
  location is the one the server allocates to the name, that snapshots lie in that directory, and that no live or
  soft-deleted row owns the directory.
- Create history (schemas, specs and orders with the client's ids) kept only after checking the final parts equal the
  create's.
- A multi-table transaction refused on shape; a one-table transaction is the ordinary commit.
- Rename and drop delegate to native, with IRC status mapping. Check the view occupant *before* writing.
- IRC error mapping: 409 `CommitFailedException` for conflicts, 500 `CommitStateUnknownException` for unknown state,
  no stack traces.

What not to carry over:
- The `ThreadLocal` plus transient-property channel for create history.
- Static create-path helpers in the update core.
- The dead overload and the double memoization.
- Private ids in comments.
- One 1,100-line handler.

Commit sequence for upstream:
1. refactor: extract the native update rules into a `TableCommitCore` bean (no behavior change; golden stored-metadata
   test plus an order test for lock, privilege and capability).
2. refactor: extract the repository error mapping helper used by create and update paths.
3. feat: `findWithMetadataById` (with a default) and a TableMetadata-candidate entry that spells the native request,
   validates it and commits it, with the D19 refusals.
4. feat: IRC commit route (authorize first, then requirements, updates, candidate entry), with an auth test for the
   no-op commit.
5. feat: IRC create and drop and rename through native handlers (rename pre-checks a view occupant).
6. feat: staged create writing nothing, plus completion with directory-ownership checks (own component).
7. feat: create history, passed explicitly into the create.
8. feat: one-table transaction route.
9. Separately, for follow-up: a base check on replace commits (D-4) and retry-cache semantics (D-5), each
   behavior-changing and gated.

## 8. Not checked

- `registerTable` (added by a pull request outside my list): read only for its use of shared helpers, not reviewed
  adversarially.
- The format-v3 hunks (`FormatVersionRule`, `writesFormatV3`, `refuseVariantBelowV3`, `keepFormatVersionOnReplace`,
  `checkUpdate` on native updates) and the House Tables row limits (#121): other reviewers' areas. I only noted where
  they sit in the native path.
- The view write handler: a no-op view commit may have the same pattern as D-1, but I did not check it.
- `PolicyPatch` semantics beyond lock handling; `IcebergRestCapability`; the authentication interceptor; served-routes
  listing.
- Every non-HTTP caller of the internal catalog's `doCommit` (replication jobs, maintenance) for the `CreateHistory.KEY`
  property; I reasoned only from the repository filters.
- Nothing was run: no tests, no build. D-4 and D-5 interleavings are by reading only.
