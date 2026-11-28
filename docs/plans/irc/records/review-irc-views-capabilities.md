# Review: IRC view routes and OpenHouse capability routes (slug irc-views-capabilities)

Scope: pull requests 95 (merge 71b3e826), 99 (1fa8cb34), 102 (6015e111); 149 (81b4cd4d) only for fit. Code read at
`81b4cd4d` in `/home/user/oh-review`. All paths below are relative to that checkout; `irc/` means
`services/tables/src/main/java/com/linkedin/openhouse/tables/api/irc/`.

## 1. Bottom line

The area is mostly sound as an adapter layer: every write goes through the native handler, service and commit engine;
the grants and unlock routes call exactly the native API-handler methods with the same arguments; the only native
classes touched are refactored without behavior change. What matters most:

1. **The IRC view load serves the full view definition (SQL, schema, properties, history) to any authenticated
   principal with no authorization decision.** The native view GET is deliberately pointer-only; the definition
   natively sits in a metadata file the caller must read from storage with its own credentials. Through IRC the
   service reads it with its own credentials and hands it out. A test pins this as "as natively". This is a read
   widening, not an equivalence.
2. **Replace on a view has no client-visible compare-and-set.** The base location sent to the native PUT is the one
   the server loaded inside the same request, so an IRC replace from a stale handle silently overwrites a newer native
   or IRC version. Declared and pinned as "Iceberg's own behavior"; it is a real difference against the native client,
   which gets 409.
3. **The policy patch over the commit route is a faithful re-implementation of the client merge** (same privilege
   UPDATE_TABLE_METADATA, same validators, same lock rule) and the "no lost update" claim holds for every field kind,
   because each patch field is replace-or-per-column-replace and the server merges onto policies read under the same
   compare-and-set it commits with. The cost is a third copy of the merge rules and a lenient-Gson wire format now
   exposed on a public endpoint.
4. **Capability names are unversioned wire names** (`openhouse.capabilities=policies,grants,unlock`) with no switch to
   turn any of them, or the IRC base, off. They are advertised from Spring mappings, so they cannot drift from what is
   registered, but nothing ties `policies` to a format version of the patch.
5. Cruft: decision ids in main-source comments, a stale justification for `IcebergRestViewSpelling` (the bean-signature
   test it cites no longer exists), stale "disabled implementation" comment in `ViewsServiceImpl.loadView`, and a
   second hand-written copy of the native view-create pre-write checks in the 1.12 register path.

## 2. Defects

### D1. BLOCKER (security; certain from reading). IRC view load and list hand out view definitions without authorization
- `irc/IcebergRestViewHandler.java:233-236` (`loadView`) and `:283-288` (`load`) call
  `viewsApiValidator.validateGetView` then `viewsService.loadView`, which (`services/ViewsServiceImpl.java`, `loadView`)
  checks only the views gate and the House Tables row, then reads the metadata file with the service's FileIO and
  returns `ViewMetadata`. `response(...)` puts that metadata in the `LoadViewResponse` body.
- Natively, `GetViewResponseBody` is explicitly "Pointer-only ... SQL, schema, representations, version history, UUID,
  properties and resolution context are read from the metadata file, not this response"
  (`api/spec/v0/response/GetViewResponseBody.java`). The OpenHouse client then reads the file itself, with its own
  storage credentials. So on main the read boundary for a view's definition is the storage permission on the view
  directory; through IRC it is "any authenticated principal".
- Contrast tables: the IRC table load runs `TablesService.loadTable`, which checks `GET_TABLE_METADATA`.
- Pinned as correct: `services/tables/src/test/.../e2e/h2/IrcViewRoutesAuthTest.java:159-165`
  `readsMakeNoAuthorizationDecisionAsNatively` (denies every decision and expects 200 on IRC load and list). The
  record (`docs/plans/irc/records/v4-irc-views.md:91`) calls this "Same". It is the same decision count, not the same
  exposure.
- Combined with register-view (pull request 149) on the 1.12 line, a principal with create privilege can bring an old
  view file back under a name, and then everyone can read it through IRC.
- Fix: decide the read privilege for IRC view load (`GET_TABLE_METADATA` on the view's authorization reference, as
  `ViewsServiceImpl.authorizationReference` builds it for drop/replace) and check it in `loadView`; leave the native GET
  pointer-only and unchecked. Test: flip the pinned test to expect 403 on IRC load. SMALL.

### D2. SHOULD FIX (certain from reading). View replace through IRC is last-writer-wins against native writers
- `irc/IcebergRestViewHandler.java:253-265`: `base` is loaded inside this request and
  `replaceBody(..., base.getView().getMetadataLocation(), ...)` sends that as `baseMetadataLocation`, so the native
  `checkBaseVersion` (`ViewsServiceImpl.java`, `checkBaseVersion`) only guards the few milliseconds of this request.
  The client's requirement is only `assert-view-uuid` (`IcebergRestViewSpelling.apply`, lines 105-117).
- Effect: Spark/RESTCatalog `CREATE OR REPLACE VIEW` or `ALTER VIEW SET TBLPROPERTIES` from a stale handle overwrites a
  version written by the native client meanwhile, which the native client itself would have got as 409. Declared in the
  merge message of 95 ("Known gaps") and pinned in `IcebergRestCatalogViewTest`.
- This is Iceberg's REST semantics, so it is defensible for IRC; but for a mixed fleet it is a lost update. Smallest
  mitigation: none at the protocol level (Iceberg sends no base); document it as a declared difference in code rather
  than only in a merge message.

### D3. SHOULD FIX (certain). Validation and requirement failures of a view commit happen before authorization and outside the view audit
- `irc/IcebergRestViewHandler.java:256-262`: `load` (a metadata read) and `IcebergRestViewSpelling.apply` (requirements
  409, update application 400, server-owned field 400) run before `viewsApiHandler.updateView`, which is where the
  privilege check and `ViewAuditAspect.auditUpdateView` are. A denied principal gets 409/400 instead of 403 for a bad
  commit, and none of those refusals reach the view audit. Declared in 95's "Known gaps" ("refusals made before the
  native handler are not in the view audit"). Natively the validator also runs before the privilege, so 400-before-403
  is native; the 409 requirement check and the metadata read before authorization are not.
- Fix: run `apply` inside the audited handler call, or audit refusals explicitly. SMALL.

### D4. MINOR (certain). The REST adapter's advertised capabilities disagree with the comment that says they match the service
- `integrations/java/iceberg-1.5/openhouse-java-runtime/.../OpenHouseRestAdapter.java:92-101`: "the server
  capabilities, as the tables service's IRC config advertises them" but the value is only `policies`, while the service
  advertises `policies,grants,unlock` (`IcebergRestServedRoutes.capabilities`). Either the comment or the value is
  wrong; the adapter does not serve the OpenHouse routes, so the value is right and the comment is wrong.

### D5. MINOR (certain). A policy patch on a table with no stored policies can set a lock state through a snapshot commit
- `services/PolicyPatch.java:121-130` (`asPolicies`) copies `patch.lockState` when stored policies are null; for a
  `SNAPSHOTS_UPDATE` commit `TableCommitCore.commit` skips `checkIfLockPoliciesUpdated` (lines 67-69). This is exactly
  what the OpenHouse client plus native snapshots PUT do today, so it is not new exposure, but the IRC route now makes it
  reachable from any plain Iceberg client. With a TABLE_UPDATE on such a table the lock check dereferences
  `tableDto.getPolicies()` and fails with a NullPointerException (500), also as natively (noted in 99's gaps).

Lines of attack that found nothing:
- Grants/unlock privilege, principal, resource: identical to native; `irc/IcebergRestOpenHouseController.java` calls
  `tablesApiHandler.getAclPolicies/updateAclPolicies/deleteLock` and `databasesApiHandler.*AclPolicies` with the same
  arguments as `controller/TablesController.java:325-480` and `controller/DatabasesController.java:78-120`. Neither side
  has `@Secured`; authentication is the same token interceptor wrapped by `IcebergRestAuthenticationInterceptor`
  (`config/TablesMvcConfigurer.java:39-58`). Nothing but namespace parsing precedes the handler.
- Path tricks: the namespace is split on U+001F; more than one level is 404 on table routes and 400 on namespace routes
  (`IcebergRestApiHandler.tableDatabaseId`/`databaseId`, lines 330-354); no route lets the database come from anywhere
  but the single level. A grant on a view id reaches the same native handler a native call would.
- Service audit: the OpenHouse routes carry swagger `@ApiResponses`, which is the `ServiceAuditAspect` pointcut, and the
  table audit is on `TablesApiHandler`, so both run as natively.
- Policy privilege: native PUT and IRC both require UPDATE_TABLE_METADATA (SYSTEM_ADMIN for replica tables) for any
  policy kind; no policy kind has a separate privilege on main.
- Policy lost update: sound (see section 5).
- Raw `policies` in a create is 400 (`IcebergRestWriteHandler.refuseRewrittenCreateProperties`), in a commit 400
  (`IrcWriteRoutesTest.java:770-781`). `updated.openhouse.policy` on a view is a plain user property on both APIs.
  Lookalike keys (case variants) are plain properties on both.
- View/table name collision: native `putView` checks the House Tables occupant; table create over a view hits the
  House Tables key. Drop leaves files on both paths (`ViewsServiceImpl.deleteCapturedView` deletes the row only).
- Server-owned view fields: location ignored on create, set-location ignored on replace, other location/UUID/format
  changes 400 (`IcebergRestViewSpelling.refuseServerOwned`); system properties are frozen by the engine's
  `checkSystemPropertiesUnchanged`; creator is the acting principal on create and preserved on replace.

## 3. Behavior changes against LinkedIn main

Files in this area changed by pull requests 95, 99, 102 that existed at `bb8929e1` (from
`git diff --diff-filter=MDR` on each merge): three. All others are new files.

| File | Before / after | PR | Default-on | Justification | Follow-up doc | Upstream as is | Size |
|---|---|---|---|---|---|---|---|
| `services/tables/.../services/ViewsServiceImpl.java` | `getView` read the metadata inside `toPointerDto` (base `getView` + `toPointerDto`); now `getView` = `loadView(...).getView()`, same gate, row read, pointer check, single file read, same catch/translate | 95 | n/a | "same checks and the same single metadata read" (merge message) | none needed | yes | none: refactor, no behavior change, checked by reading both bodies line by line |
| `services/common/.../OpenHouseExceptionHandler.java` | unreadable-body message built inline (base ~lines 441-465); now `unreadableMessage(...)`, same two branches, same text | 102 | n/a | shared with IRC routes | none | yes | none: refactor, checked by diff |
| `services/tables/build.gradle` | adds `compileOnly project(':integrations:java:openhouse-catalog-capabilities')` | 102 | yes (build graph) | constants inlined, no runtime classpath change | build.gradle comment | likely no: a service depending on a client-integration module is the wrong direction | SMALL: copy three constants or move `CapabilityNames` to a shared module |

New behavior (new routes, not behavior changes by the brief's rule, but always on with no switch):
- IRC view routes (95), the policy-patch application on IRC create/commit and the `openhouse.capabilities` override (99),
  six `/iceberg/v1/.../openhouse/...` routes and the `endpoints` list (102). None is behind a property; the IRC base has
  no `@ConditionalOnProperty` anywhere in `irc/`. An upstream reviewer will ask for one switch for the whole IRC base.
- Native view, ACL and lock routes: unchanged (checked by reading the controllers and handlers at both commits).

## 4. Cruft

Private ids and history in main sources:
- `irc/IcebergRestViewHandler.java:46,50,132` and `irc/IcebergRestViewSpelling.java:103`: "(D30)". Replace with the rule
  itself ("the server owns view locations").
- `irc/IcebergRestWriteHandler.java` (policy section) and `IcebergRestViewHandler` list javadoc reference D28/V14 in the
  same style (V14 at the create-history comment). Same treatment.

Stale justifications (two-Iceberg-line leftovers):
- `irc/IcebergRestViewSpelling.java:26-32`: "Static, and not a bean: Spring beans name no Iceberg view type in their
  signatures (the bean-boundary test of the test fixtures allows that only in the view codec and the commit engine)".
  No such test exists at HEAD (no `BeanSignatures` class in the tree; the Iceberg 1.2 line was removed in 139/142). The
  class can be merged into the handler or kept as a plain helper with a true reason.
- `services/ViewsServiceImpl.java` `loadView` javadoc: "not on ViewsService, whose disabled implementation runs where
  the Iceberg view types do not exist". `ViewsDisabledService` was deleted; `loadView` can go on the interface and the
  IRC handler can depend on `ViewsService` instead of the concrete `ViewsServiceImpl`
  (`IcebergRestViewHandler.java:190`).
- PR 95 merge claim "view controller registers only when the Iceberg view API is on the classpath" and PR 102 claim
  "grants,unlock on the 1.2 line": superseded; no code left, only notes.

Two implementations of one thing:
- `services/PolicyPatch.java`: the client's five merge rules restated on the server model (the class javadoc admits it).
  Plus a test-side copy `e2e/h2/ClientPolicyMerge.java` ("line-for-line copy of the client's merge"). Three copies of
  one merge. A clean version moves the merge into one module both client and server use.
- `irc/IcebergRestViewHandler.load` re-implements `ViewsApiHandler.getView` (validator + service) to get the metadata;
  fine once `loadView` is on the interface and the handler.
- `src/iceberg-1.12/.../IcebergRestViewRegistration.java:137-160` restates `ViewsServiceImpl.putView`'s pre-write order
  (gate, database, occupant, privilege, name) and `:256-305` restates `IcebergRestWriteHandler.refuseADirectoryInUse`
  for views. Pull request 149 territory; noted only for the fit: two copies to keep in step with `putView`.

Other:
- `irc/IcebergRestCapability.java:172` `String[] ORDER` is a mutable public array on an annotation type; use a `List`
  in `IcebergRestServedRoutes`.
- `irc/IcebergRestViewController.java:44-45,100`: `pageToken`, `pageSize`, `purgeRequested` accepted and ignored
  (deliberate, list returns everything); say so or drop the bindings.
- Long narrative javadoc in `IcebergRestOpenHouseController`, `IcebergRestServedRoutes`, `IcebergRestApiHandler.config`
  explaining which work item added which name ("Each name is added by the item that serves it" was in 99, since
  reworded); trim to the contract.

## 5. Tests

- Pins a defect: `IrcViewRoutesAuthTest.readsMakeNoAuthorizationDecisionAsNatively` (see D1). The pin is wrong for IRC
  load; right for the native GET.
- `IrcPolicyPatchTest.twoPolicyCommitsOnTheSameLoadedTableBothApply` (lines 648-698) only races two different fields
  (sharing, history). The claim "final state is the same" also holds for same-field races and for removals
  (`{"replication":{}}`, sharing FALSE) because every field is replace-on-present and column tags are replaced per
  column, and the server merges onto policies read under the same base it commits with
  (`IcebergRestWriteHandler.commitOn`, `tableCommitService.loadBase` then `commit(... base.getTableDto().getTableLocation() ...)`).
  No test races two writes of the same field or of two different column-tag sets on one column; the claim is sound by
  reading but not pinned.
- "No lost update" does not hold for a table that holds a stored `updated.openhouse.policy` (written raw through the
  native API): every IRC commit, including a plain append, re-applies that stale patch over current policies before
  removing the key (`IcebergRestWriteHandler.applyTakingOutThePatch`, lines ~416-443). `aStoredKeyIsTreatedAsTheClientTreatsIt`
  pins this as client-equivalent, which it is; it is still a surprising write on a data commit.
- `IrcGrantsUnlockRoutesTest` (870 lines) compares native and IRC on twin tables including the recorded access
  decisions; by reading, the comparison would catch a privilege mismatch. Good.
- `IrcConfigEndpointsTest` checks endpoints against served routes in both directions; nothing checks that a capability
  name means a specific patch format (unversioned wire name).
- 95's claim "the OpenHouse client sees the same metadata file and metadata" after IRC writes: covered by
  `IrcViewRoutesTest` golden comparisons (version summary, ids and timestamps excluded, declared).
- 99's Spark-side tests were "written after the service code and passed on their first run" (merge message): not run
  failing first.
- 102 lists as not run: the 1.5 Java itest, Spark 3.5 statementTest, `:services:tables:test` under the 1.12 build.

## 6. Per pull request

| PR | "Leaves true" holds | Why |
|---|---|---|
| 95 | partly | Writes go through the native handler as claimed; "authorization ... all run" is false for reads in substance (D1); "native code unchanged except getView delegates" holds; views-disabled 404 claims later changed by 102 (list now empty, D28); 1.2-line claim obsolete. |
| 99 | yes | Merge rules, validation inside the audited entry, mapping after lock/privilege, key never stored, raw `policies` refused, capability override: all match the code. Lost-update claim sound by reading (section 5). |
| 102 | yes, with notes | Six routes are thin adapters with identical arguments; endpoints/capabilities read from handler mappings; native API unchanged. "constants inlined and no resolved classpath changed" true at runtime, but the build graph gained a service-to-integration dependency. 1.2-line wording obsolete. |
| 149 (fit only) | n/a | Sits on `IcebergRestViewHandler.onNamespace` and the view audit; duplicates `putView`'s pre-write checks; inherits D1 (registered views become readable by all through IRC). |

## 7. For the clean re-implementation

Keep:
1. IRC view routes as adapters over the native view handler/service; one `loadView` on `ViewsService` returning pointer
   plus metadata, with an explicit read privilege for the IRC body.
2. Commit spelled as native PUT of the complete definition; server-owned location/UUID/format refused or ignored on
   replace; system properties frozen by the engine.
3. Error translation by native error code to Iceberg exception types, with the native message.
4. Views-disabled answers: 404 NoSuchView on a view's routes, empty list on list (D28, needed by Spark 4.2 SHOW TABLES).
5. Policy patch applied server-side: key removed, merged onto stored policies read under the commit's own base, result
   validated by the native update validator inside the audited commit, mapped after lock/privilege checks.
6. Grants/unlock as POST/DELETE adapters under `.../openhouse/...` calling the native API-handler methods unchanged.
7. `endpoints` and `openhouse.capabilities` derived from registered mappings (annotation on the route).

Do not carry over: decision ids in comments; the static-helper split justified by a deleted test; the concrete
`ViewsServiceImpl` dependency; the test-side copy of the client merge (put the merge in a shared module instead); the
compile-only dependency of the service on a client integration module; the "reads make no decision" pin.

Commit sequence for upstream:
1. Refactor: `ViewsService.loadView` (pointer plus metadata), `getView` delegates. No behavior change.
2. Refactor: extract `unreadableMessage` in `OpenHouseExceptionHandler`. No behavior change.
3. Move the policy-patch merge into a shared module used by the Java client (client behavior unchanged, pinned by its
   existing tests).
4. Feature switch for the IRC base (off by default) if not already upstream.
5. IRC view routes (read with privilege, create, replace, drop, list, exists; rename 406).
6. Policy patch on IRC create/staged create/commit.
7. OpenHouse grants/unlock routes.
8. Config: `endpoints` and `openhouse.capabilities`, with a documented versioning rule for capability names.

## 8. Not checked

- Did not run anything (read-only brief). D1's exploitability depends on how a deployment protects view directories
  in storage; by reading only.
- Did not read `IcebergRestExceptionHandler`/`IcebergRestErrors` mappings for every native exception on the ACL routes
  beyond what the tests claim.
- Did not review pull request 149's internals (already reviewed), the table write handler outside the policy-patch
  path, `ServiceAuditAspect` changes from 121, or the Spark 3.5/4.2 integration tests beyond their names and claims.
- Did not verify whether `TablesService.getAclPolicies` refuses a view id (same handler on both sides, so no difference).
