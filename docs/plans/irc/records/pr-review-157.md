# Review of pull request 157 (irc/iceberg-rest-namespace-routes, head 362de10d)

Reviewer: independent sub-agent, read-only. Worktree `/home/user/oh-irc-ns`. Nothing was built or run. Every claim
below comes from reading code and git objects, the fork's Iceberg 1.12 sources and spec, and `javap` of LinkedIn's
`iceberg-core-1.5.2.24.jar` from the Gradle cache. The pull request description was read through the REST API.

## 1. Verdict

**MERGE AFTER FIXES.** Close F1 first. It needs a test and a correction to the description and Javadoc; no
production logic changes.

The three things that matter most:
1. **The layer is thin, and the write authorization is right.** On create, update and drop, the decision is made for
   the authenticated principal, on the lower-cased name, before any state is read. That is the resource his service
   decides on. A denied caller gets 403 for a new name, a name with a record, a name that holds tables and an invalid
   name. His 61 files are untouched.
2. **Existing `/iceberg` reads now depend on two new services (F1).** Every namespace load, and the misses of HEAD,
   `parent=`, list tables, list views and the cross-database rename, now call:
   - the authorization service, for `GET_TABLE_METADATA`;
   - the House Tables namespace store, a 30-second read with no retry.

   This is declared, but nothing pins it. The description also says `IrcReadRoutesAuthTest` pins that these routes
   make no authorization decision for a namespace. That is no longer true.
3. **A create of a namespace that holds tables is refused (409) (F2).** Together with the 404 on update, this means no
   existing database can ever get properties through `/iceberg`. His own create accepts that case. This is a third
   divergence from his routes. It is declared in the pull request, but D67 and the owner's question list
   (`58-waiting-on-mike.md` (c)) name only two.

## 2. Findings

### F1. MUST FIX BEFORE MERGE: the new store and authorization dependency of existing read routes is unpinned, and one claim about it is false
- **Where:**
  - `IcebergRestApiHandler.java:220-236`, `loadNamespace`, which calls `storedNamespace` first, on every load;
  - `:247`, `listTables` miss;
  - `:422`, `requireNamespace` miss, reached by HEAD, `parent=` (`:207`), `IcebergRestViewHandler.java:99,121` and
    `IcebergRestWriteHandler.java:1094` (rename);
  - `:449-456`, `storedNamespace`:
    ```java
    return Optional.of(
        namespacesService.loadNamespaceMetadata(databaseId, extractAuthenticatedUserPrincipal()));
    } catch (NotFoundException | AccessDeniedException e) {
    ```
- **What goes wrong.** Every other failure propagates, traced from reading:
  - A House Tables 5xx, which is what a store without `catalog_namespaces` or an `ICEBERG`-typed House Tables gives
    (`IllegalStateException` in his `NamespacesServiceImpl`), or a timeout: `HouseNamespaceRepositoryImpl.getByName`
    throws `WebClientResponseException` or a timeout exception. No branch of `IcebergRestErrors.toError` matches it, so
    it becomes 500 `ServiceFailureException`. The message is the raw WebClient text, which names the House Tables URL.
    That pattern is pre-existing for IRC 500s.
  - An authorization-service failure gives 503 (`AuthorizationServiceException`).
- **Who hits it.** Before, load of a database with tables made no authorization call and no namespace-store read.
  Now it makes both, on every call. Spark 3.5 rest-http on the 1.5.2 line implements `USE` and `namespaceExists` as a
  load (`RESTSessionCatalog` 1.5.2 has no HEAD), so those statements fail against such a deployment. Spark 4.2 and
  1.12 use HEAD, which reads the record only on a miss.
- **Hot path: none.** Table load, commit, create, staged create, register and the view load do not reach
  `requireNamespace` or `storedNamespace` (grep of `api/irc`).
- **No test shows any of this.** Every `services/tables` H2 test now runs on the in-memory stand-in
  (`SpringH2Application` now `@Import`s it), which cannot fail. The failure mode is "read, not tested".
- **The description's claim is false.** "`IrcReadRoutesAuthTest` pins that these routes make no authorization decision
  for a namespace" does not hold: load now asks for `GET_TABLE_METADATA` on every call. The test only shows that a
  denial does not change the answer (`listingAndExistenceAreAnsweredForADeniedPrincipalAsNatively` stubs every
  decision false and expects 200). The class Javadoc `IcebergRestApiHandler.java:68` ("Neither makes an authorization
  decision, as natively") is now wrong for the list-tables miss.
- **Side effect of the new decision.** A denial ledger or audit in the authorization service, such as OPA, now
  records a denial on every load by a reader without that privilege.
- **Certainty:** certain from reading. The exact status was not run.
- **Required before merge:**
  - A test in which `HouseNamespaceRepository.getByName` throws a non-404 failure, for example a
    `WebClientResponseException` 500. It should pin load of a table-holding namespace (500, and its type), HEAD of a
    table-holding namespace (204, no read) and the HEAD miss (500). Same for an `AuthorizationServiceException` from the
    handler (503).
  - Correct the description sentence and the Javadoc at `:68` to "makes a read decision for the record, whose
    refusal does not change the answer".
  - Add a ledger row for the new dependency. BC-050's "only the namespace routes fail (500)" is now incomplete: the
    `/iceberg` load fails too.
- **Best-effort reads: should follow; put it to the owner.** My recommendation:
  - Make the existence-miss paths (HEAD, `parent=`, list tables, list views, the rename target) best-effort: on a
    failure, log a WARN and treat it as no record. This hides only the existence of a record-only namespace while the
    store is down. A client that then creates it fails loudly on the write path anyway.
  - Keep load strict. Best-effort there would answer `{}` for a namespace that has properties: silently wrong data.
  - What best-effort would hide: a misconfigured deployment would look healthy until the first write.

### F2. SHOULD FIX (documentation and owner question; outside this pull request's files): the create 409 for a table-holding namespace is a third divergence from his routes and is not put to him
- **Where:** `IcebergRestNamespaceHandler.java:80-82`:
  ```java
  if (readHandler.holdsATableOrView(databaseId)) {
    throw alreadyExists(databaseId);
  }
  ```
- **His create stores a record for a populated database.** His `TablesServiceImpl.createNamespace` has no table check.
- **Combined with the update 404 (`:118`)**, an existing database (the overwhelming case) can never get properties
  through `/iceberg`, though his own route can give it some. The 409 is the spec's answer for an existing namespace.
- **D67 and `58-waiting-on-mike.md` (c)** list only the drop guard and the missing list as "stricter or narrower than
  his routes". The pull request declares the consequence ("Not solved here").
- **Fix:** add it to D67 and to the owner question in (c).
- **It can wait:** nothing is lost; the route can be loosened later.

### F3. SHOULD FIX: update of a table-only namespace answers "Namespace does not exist" while load and HEAD say it exists
- **Where:** `IcebergRestNamespaceHandler.java:118-119`. It is pinned by
  `updatingPropertiesOfANamespaceThatOnlyHoldsTablesIs404AsAConsequenceOfTheTwoExistenceRules`, and labelled KNOWN
  DEFECT in both Spark declarations (s24 ALTER: `SCHEMA_NOT_FOUND` for a database that DESCRIBE shows).
- **The labelling is honest. The answer is a false statement in the spec's terms**: 404 means "namespace not found".
- **Better options to put to the owner:**
  - 406 `UnsupportedOperationException` ("properties need a namespace record; this namespace has none"), which the spec
    lists for this route;
  - or let his create accept a populated database (F2).
- **It can wait:** it is labelled, and it is a choice for him.

### F4. MINOR: a lost race on update or drop reaches clients as the wrong exception
- **The answer.** His repository's `Conflict` becomes `EntityConcurrentModificationException`, which
  `IcebergRestErrors.java:75` maps to 409 `CommitFailedException`.
- **How clients read it.** Iceberg's `NamespaceErrorHandler` maps every 409 to `AlreadyExistsException` (1.12,
  `ErrorHandlers.java:279`; 1.5.2 per `javap`). The 1.12 `DropNamespaceErrorHandler` maps 409 to
  `NamespaceNotEmptyException` (`:295`). So:
  - a concurrent property update surfaces as "already exists";
  - a concurrent drop surfaces, on 1.12, as "not empty".
- **Spec:** it lists no 409 for update properties.
- **Status:** untested. His own route gives the same 409.
- **Smallest fix:** on the two namespace write routes, answer a conflict as 503 `ServiceUnavailableException`
  (retryable), or state it in the description as passed through.

### F5. MINOR: the authorization tests cannot tell whether the layer decides on the right resource
- **Where:** `IrcNamespaceRoutesAuthTest.denyOnly` denies by privilege for any resource. The
  `verify(..., atLeastOnce()).checkAccessDecision(..., "irc_ns_auth_rec", SYSTEM_ADMIN)` in
  `aCreateIsDecidedFirstOnTheWritePrivilege` is satisfied by his service's own decision.
- **What would go unseen:** the layer's decision moving to another resource, for example the un-lowercased name.
- **Which reorderings the tests catch:**

  | Route | Reversing the layer's order is caught by | Not discriminating |
  |---|---|---|
  | Create | the tabled name (409 vs 403) and the invalid name (400 vs 403) | the record case: his service decides first anyway |
  | Drop | the tabled name only | |
  | Update | the unparseable body (`"x"`) only | |

- **Fix:** deny by (resource, privilege) in one case, for example allow `irc_ns_auth_rec` and deny the mixed-case
  spelling's lower-case form elsewhere. Or verify exactly two `SYSTEM_ADMIN` decisions on the lower-cased name per
  create.

### F6. MINOR: the drop's view probe ignores the views gate
- **Where:** `IcebergRestApiHandler.holdsATableOrView` reads House Tables view rows whatever `ViewsFeatureGate` says.
- **What the client sees:** for a database whose views are disabled but which holds view rows created while views
  were enabled:
  - the IRC list views answers an empty list (`IcebergRestViewHandler.java:97-100`);
  - list tables shows nothing;
  - yet drop answers "Namespace X is not empty".
- **Correct for the data, so no fix is needed.** The message could say "holds a table or a view".
- **Not tested** with the gate off. The view-only test enables views.

### F7. MINOR: path names on update and drop are never pattern-checked; the collation hazard is inherited from his route
- **The paths.** Create validates the name with his pattern after the decision. Update and drop send the path name
  as-is to:
  - `checkDatabasePrivilege(name.toLowerCase(ROOT))`;
  - his `getByName` and `delete`, which run on MySQL `utf8mb4_0900_ai_ci`: case- and accent-insensitive.
- **The hazard.** A principal holding `SYSTEM_ADMIN` on a non-ASCII name such as `kåfka` is decided on that resource,
  while the store resolves the name to the record `kafka`.
- **Not this layer's doing.** His route behaves identically, and this layer decides on exactly his resource.
- **Realism:** low; it needs a grant on a non-ASCII database name.
- **Fix:** report to the owner. Optionally, the layer could refuse a path name that fails his pattern before the
  decision (content-only, like the level check).

### F8. MINOR: duplication and reach into internals
- **The namespace message.** `IcebergRestNamespaceHandler.java:180-182` copies his
  `NamespaceExceptionHandler.handleInvalidFields` text, which is an inline literal and cannot be referenced without
  editing his file. Acceptable; note it.
- **The level check.** `singleLevel` (`:164-170`) repeats `IcebergRestApiHandler.databaseId`'s level check and message
  for a `Namespace` object.
- **The view probe.** `holdsATableOrView` repeats `ViewsServiceImpl.requireDatabaseExists`'s two probes
  (`ViewsServiceImpl.java:429-430`). The read handler now autowires `HouseTableRepository` directly, bypassing the
  service layer the rest of the handler uses.
- **The double decision.** It is listed by the author. It is needed on create and drop: without it the table and view
  probes would answer 409 versus 404 before the decision. On update it only puts 403 before content-only 400s (his
  service decides before the record and the 422). There it costs one extra remote call with OPA for no state
  protection.

### F9. MINOR: kit reason text for VIEWDB names a cause these tests never reach
- **The text** (24 lines): "...and a namespace record does not count".
- **What the view suite does.** It runs with `requiresNamespaceCreate=false`, so it never creates a record. These
  tests fail because no table or view exists.
- **The description says so already:** accepting a record would not help unless the flag were flipped as well.
- **Fix:** "the view suite creates no namespace first (`requiresNamespaceCreate=false`), and the native view create
  needs a database that holds a table or a view". That is the framing LISTNS now uses.

### F10. MINOR: Spark's own properties are stored by CREATE NAMESPACE over `/iceberg`; not stated
- Spark's `CreateNamespaceExec` adds `owner = <current user>` to the properties it passes. Review 153 F1e verified this
  by `javap` for Spark 3.5.2. So s19 over rest-http (and, likely, Spark 4.2) stores an `owner` key in the record.
- The s19 reasons say only "stores a namespace record".
- **Certainty:** likely, from reading; not run.
- **Fix:** add it to the reason or the description.

### F11. MINOR: a private id in a rewritten line
- `RestHttpPathSparkSqlSmokeTest` class Javadoc: "On both Iceberg lines (work item N10)". The line was rewritten in
  this pull request and kept the id.
- No other added line has a decision, FF, RF, BC or pull request id. The removed "Decision D20" strings are gone.

## 3. Behavior changes (mine), against merge base 68e2557d

All rows are on `/iceberg` only, on both lines, on by default, with no switch. LinkedIn main has no `/iceberg`, so no
row moves toward or away from main. Each row is a change on this branch's served surface.

| # | Route or surface | Before | After | In the description |
|---|---|---|---|---|
| 1 | POST `/v1/namespaces`, POST `/{ns}/properties`, DELETE `/{ns}` | 406 | served (200, 409, 404, 400, 403, 422, 204) | yes |
| 2 | Config `endpoints` | 19 (+3 on 1.12) | 22 (+3); a 1.12 client now sends namespace writes instead of refusing them locally | yes |
| 3 | Load of a namespace with a record | 404 if record only; `{}` if it held tables | 200 with the record's spelling and properties (for a caller with `GET_TABLE_METADATA`) | yes |
| 4 | HEAD, `parent=`, list tables, list views of a record-only namespace | 404 | 204 or 200 (empty) | yes |
| 5 | Cross-database rename into a record-only namespace | 404 | 406 | yes |
| 6 | Load (every call) | no authorization call, no namespace-store read | one authorization call (`GET_TABLE_METADATA`, denial ignored) and one keyed House Tables read | partly: the read and failure are stated; "makes no authorization decision" is wrong (F1) |
| 7 | Misses of HEAD, `parent=`, list tables, list views, rename target | table probe and database list | the same, plus the decision and the keyed read | yes (rename and `parent=` named) |
| 8 | Any of 6–7 with a failing store or authorization service | answered | 500 or 503 | yes, untested (F1) |
| 9 | Spark SQL s19 CREATE, s19 USE, s24 ALTER, s24 DROP (rest-http both lines; Spark 4.2) | refused, or `SCHEMA_NOT_FOUND` | success, success, `SCHEMA_NOT_FOUND`, not-empty (1.5.2: `AlreadyExistsException`) | yes |
| 10 | Spark CREATE NAMESPACE stores Spark's `owner` property | n/a | stored | no (F10) |
| 11 | A lost race on update or drop | n/a | 409 `CommitFailedException`, read by clients as already-exists or not-empty | no (F4) |
| 12 | His routes, native routes, the Spark 3.5 legacy and rest-shim paths | | unchanged (no file of his touched; native view and table create untouched) | yes |

Differences from the author's list: rows 6 (wording), 10 and 11.

## 4. Tests

- **`IrcNamespaceRoutesAuthTest` (4):**
  - Each test denies one privilege and allows the rest.
  - The forbidden-first assertions fail if the layer's order is reversed, for the cases in F5. They cannot see the
    resource of the layer's decision (F5).
  - The read test correctly shows that a denied reader gets load `{}` for a namespace with a record and tables, and
    404 for a record-only one.
- **`IrcNamespaceRoutesTest` (13):**
  - Each can fail.
  - The drop guard is pinned for tables, record plus table, and view only; the create 409 for tabled; the 422; the
    400s; the rename 406.
  - Not covered: a lost race (F4), the views gate off (F6), a store failure (F1).
- **`IrcRestCatalogNamespaceTest` (2):**
  - It uses an unmodified `RESTCatalog`.
  - The line-dependent drop exception is chosen by reflection on `ErrorHandlers.dropNamespaceErrorHandler`. I
    confirmed by `javap` that 1.5.2 has no such method, and that its `NamespaceErrorHandler` maps 409 to
    `AlreadyExistsException`. The pin is honest.
  - The `removeProperties == false` assertion carries its cause (`RESTSessionCatalog` answers only `updated`).
- **`IrcHouseTableReadsTest`:**
  - The namespace proxy records `getByName` hits and misses and the three writes. It pins exactly one keyed read, at
    the stated position, on load (first), the HEAD miss and the list-tables miss.
  - It pins no record read on HEAD of a table-holding namespace (unchanged expectation).
  - The old lists in `namespaceExistsMissing`, `loadNamespace` and `listTablesMissing` each gained exactly one entry,
    which is the consequence of the change.
  - It runs on the stand-in only, so it shows the calls made, not their cost.
- **"Answers for namespaces without a record are unchanged".** This is asserted by unchanged test code:
  `IrcReadRoutesTest`'s namespace tests (only the three 406 rows were removed), `IrcReadRoutesAuthTest` (untouched),
  and the unchanged rows of `IrcHouseTableReadsTest`.
- **Rewritten existing tests:** each is the consequence of serving the routes.
  - `IrcWriteRoutesTest.namespaceWritesAreServed`: 406 becomes 400 for `{}` (Iceberg `validate()`: no namespace), and
    404 / 404, his `NotFoundException`.
  - `IrcConfigEndpointsTest`: +3.
  - `IrcReadRoutesTest`: −3 rows.
  - The boundary tests: handler names, the 404 becoming 200 on load of a record, the 406s becoming a 409 duplicate,
    the endpoint count 19 → 22, and "not listed" becoming "listed".
  - `SparkIrcEndpointsTest`: the local refusal is replaced by real requests.
  - The Spark declarations: per statement and per line.
  - None hides a regression.
- **Kit (`expected-outcomes.txt`):**

  | Change | Lines | Notes |
  |---|---|---|
  | NSPROPS, SKIPPED → passed | 7 | deleted |
  | NSWRITE, FAILED → passed | 5 | deleted; `testDropNonEmptyNamespace` passes only because of the guard, as declared |
  | Moved from c to d LISTREC | 2 | `testCreateNamespace`, `testListNamespaces`; the reason is true (the list is table-derived) |
  | Reason edited, outcome unchanged | 32 | four groups: EMPTYNS 5, NOCREATE 1, VIEWDB 24, LISTNS 2 |

  - The edited EMPTYNS reason is now correct: `Namespace.empty()`, as the scout found.
  - The VIEWDB wording is imprecise (F9).
  - The remaining 28 namespace-model failures are filed as 2 LISTREC, 24 VIEWDB and 2 LISTNS, with true first-step
    causes apart from the F9 wording. KITNS is separate.
  - Not re-run by me.

## 5. Checked and found sound

- **His files.** None of the 21 changed files was touched by `bb8929e1..464a220d`.
- **The merge commit.** 362de10d's tree equals `git merge-tree` of its parents (`a49298dd`). HEAD also merges cleanly
  with the current `origin/irc-integration` (4d9362ee).
- **`@Secured(SYSTEM_ADMIN)`** on the three routes is the same no-op-when-authenticated interceptor his routes use
  (`AuthorizationInterceptor` always grants). The real decision is the handler's, then his service's.
- **The write decision's resource and principal:**
  - `authorizeWrite` uses `checkDatabasePrivilege(name.toLowerCase(Locale.ROOT), principal, SYSTEM_ADMIN)`, identical
    to his `authorizeNamespace`, for `extractAuthenticatedUserPrincipal()`.
  - A case trick cannot move it. The table and view probes are case-insensitive in House Tables (`...AllIgnoreCase`
    finders), and so is the record (collation).
- **What a caller without `SYSTEM_ADMIN` sees before the decision:** only content-dependent 400s (an unparseable
  create body; a multi-level or empty level). No state is read before the decision on any write route.
- **What a reader refused `GET_TABLE_METADATA` sees:** nothing about a record. Load, HEAD, list tables, list views,
  `parent=` and the rename target all fall through to the derived checks, and the decision precedes the read. The
  writes are 403 for such a caller only if it also lacks `SYSTEM_ADMIN`; with it, the 409 is fair.
- **`IcebergRestJson.parse`:**
  - Its only caller is the namespace update. `read` is byte-for-byte the old behavior for every other route.
  - Iceberg's `UpdateNamespacePropertiesRequest.validate()` (1.12) checks only key overlap.
  - Null values and null removals still reach his bean constraints (`@NotNull`) and give 400.
  - Unknown fields and body size are unchanged.
- **Errors map as the spec lists:**

  | Answer | Route |
  |---|---|
  | 409 `AlreadyExistsException` | create |
  | 409 `NamespaceNotEmptyException` | drop |
  | 404 `NoSuchNamespaceException`, message "Namespace does not exist: <ns>" | load, update, drop |
  | 422 | update overlap |
  | 400 | malformed request |
  | 403 | denied |

  1.12's handler maps each to the right exception. The 1.5.2 drop label is honest.
- **Drop-guard race.** The end state, a table and no record, is consistent with the existence rule: the namespace
  still exists, by its table, and has lost its properties. That is what his own route does on every populated drop.
  The race adds nothing worse, so a listed gap is enough. The create probe's race leaves a record plus a table, which
  is a legitimate state.
- **Spark labels.** Both KNOWN DEFECT labels (an unlisted create; ALTER on a database with tables) are right. DROP's
  "still refused" is right.

## 6. Not checked

- No build, test, kit or Spark run. All counts and measured outcomes (54/160, 34/34, 451 tests, CI) are taken as
  reported.
- The real `HouseNamespaceRepositoryImpl` behind the `/iceberg` routes. No test runs these routes over the real
  store.
- Spark 4.2's `CreateNamespaceExec` `owner` property (F10) was not checked by `javap`.
- The delta-harness, as the author also notes.

## Re-check at 63c65e76

Scope: the fix commits `c3c461e3` (tests), `09095433` (code) and `63c65e76` (kit text, id), the updated description and
the updated record. The merge `f05b613e` was not reviewed. Read only; nothing was run.

**Verdict: MERGE.** F1 is closed as the orchestrator decided. One new SHOULD FIX (F12) comes from the F6 change. It is
a narrow inconsistency with no data loss beyond what his own route does, so it can follow.

### F1: closed, implemented as decided
- **Code:** `IcebergRestApiHandler`.
  - `readRecord` is one keyed read through his service. It returns empty on `NotFoundException` and on a denial. It
    rethrows `AuthorizationServiceException`, which is caught before `AccessDeniedException`. That order is correct:
    the failure type is a subclass of the denial type, so the old single catch did treat an authorization-service
    outage as a denial.
  - `storedNamespace`, used by the existence paths, turns any `RuntimeException` from `readRecord` into "no record"
    and logs a WARN.
  - `loadNamespace` catches a failure of `readRecord`. If `holdsATableOrIsListed` (table probe, then the database
    list, so view-only databases count), it answers `{}` with a WARN; otherwise it rethrows. The derived probe runs
    only on that failure path.
- **Every path.**
  - Load: the code above.
  - HEAD, `parent=`, the list-views miss and the rename target: all go through `requireNamespace` and
    `storedNamespace`.
  - List tables: its miss also calls `storedNamespace`.
- **The happy path** is still one keyed read on load when the namespace has a record or holds a table, and one on
  each existence miss.
- **Pre-existing at 362de10d, which I missed then (MINOR):** a load of a name with no record and no table reads the
  record twice. `readRecord` runs first, then `requireNamespace` calls `storedNamespace` again. This applies to a
  missing name (404) and to a view-only database. On the failure path for a view-only database, the table probe, the
  database list and the failing read are each repeated once more, with a second WARN. Answers are correct. The
  description's "a load still makes one keyed read when the store works" holds only for a namespace with a record or
  a table.
- **Nothing is swallowed that should not be:**
  - A denial never becomes existence: it is empty, then falls to the derived checks.
  - No write path reads through `storedNamespace`. Create, update and drop call his service directly, and their
    failures propagate (pinned as 500, or as 403 for an authorization outage). The drop and create guards read no
    record.
  - A record-only namespace answers 404 on the existence paths while the store is down, and that cannot be known
    otherwise. A create that follows cannot overwrite the record:
    - with the store down, his create fails (500, pinned);
    - with only reads failing, House Tables' unique name gives 409;
    - Spark `CREATE NAMESPACE IF NOT EXISTS` then gets AlreadyExists: a loud error, not data loss.
- **Correction to my F1 text.** An authorization-service failure answers **403 `ForbiddenException`**, not 503.
  `IcebergRestErrors.toError` tests `AccessDeniedException` before `AuthorizationServiceException`, so its 503 branch
  is dead for that type. This is pre-existing on every IRC route. The test pins it "as it is", with that reason. A
  fix-forward candidate, not this pull request's.
- **`IrcNamespaceRoutesFailureTest`: what each assertion can catch.**

  | Assertion | Can it fail if the behavior were otherwise | Why |
  |---|---|---|
  | `missesAsBefore`, all five rows | yes | not swallowing would give 500 (store) or 403 (authorization) |
  | `tabledReadsAsBefore`, load | yes | the only row that reads the record |
  | `tabledReadsAsBefore`, HEAD, list tables, `parent=`, list views | no, under any failure handling | the table probe short-circuits the record read; they pin unchanged answers |
  | NONE load: 500 under store failure | yes | a fallback would give 404 |
  | NONE load: 403 under authorization failure | yes | treating it as a denial gives 404; this is the case that failed before `09095433` |
  | The writes: 500 / 403 | yes | |
  | Tabled drop: 409 | yes | it shows the guard reads no record |

  Not pinned: the load fallback for a view-only, listed database. It differs from the tabled case only in that the
  database list answers instead of the table probe.

### Other findings
| Finding | Status | Reason |
|---|---|---|
| F2 | closed | Description line 24 names the create 409 as the third divergence; "For the owner" lists all three. The D67 and `58-waiting-on-mike.md` (c) notes are the orchestrator's, outside the pull request. |
| F3 | closed | Under "For the owner", with the 406 alternative. |
| F4 | closed | Pinned in `aLostRaceIs409CommitFailedWhichClientsReadAsAnotherException`: 409 `CommitFailedException` for update and drop; the clients' readings (AlreadyExists on both lines, NamespaceNotEmpty on a 1.12 drop) asserted through Iceberg's own `ErrorHandlers`; described in behavior change 5 with the 503 alternative. Not changed, as allowed. |
| F5 | closed | `denyOn(resource, privilege)` denies one database only. `theWriteDecisionIsOnTheLowerCasedDatabase` would fail if the layer decided on the un-lowercased name: a create of `Irc_Ns_Auth_Res` with `irc_ns_auth_res` denied is 403. It would also fail if only his service decided: the create and drop of a table-holding name are 403, not the layer's 409, and his service is never reached there. `theReadDecisionIsOnTheLowerCasedDatabase` covers the read side. |
| F6 | changed, not as I advised; see F12 | |
| F9 | closed | VIEWDB text now names the step the tests fail at. |
| F10 | closed | Spark stores exactly `{owner: sparkUser}`, pinned on Spark 3.5 rest-http (`IcebergRestSparkSqlTest`, both lines per the description) and on Spark 4.2 (`SparkIrcEndpointsTest`), and stated in the description. |
| F11 | closed | "work item N10" removed. No decision, FF, RF, BC or pull request id in added lines. |
| F7 | closed | Under "For the owner". |
| F8 | unchanged | MINOR; acceptable. |
| His files | untouched | None of the files changed by the fix commits is touched by `bb8929e1..464a220d`. |

### F12 (new). SHOULD FIX: with the views gate in the drop and create probe, a view-only database whose views are disabled exists for the reads but not for the writes
- **The change.** `holdsATableOrView` now skips the view probe when `ViewsFeatureGate.isEnabled(db)` is false.
  `requireNamespace` still counts such a database as existing through the database list (`databaseIds()`, which its
  own Javadoc says also counts databases holding only views; not re-verified down to the House Tables query).
- **What a database with hidden view rows and no record answers:**

  | Route | Answer |
  |---|---|
  | HEAD | 204 |
  | Load | 200 `{}` |
  | List views | 200 `[]` |
  | Drop | 404 "Namespace does not exist" |
  | Create | 200, a record is stored |

  The pinned test (`aNamespaceThatHoldsOnlyAViewIsNotDroppedWhileItsViewsAreEnabled`) asserts the drop and create
  answers but never asks HEAD.
- **Why it is wrong.** The create is a create of an existing namespace, which the spec answers 409 and which this
  layer answers 409 for a table-holding one. The view routes treat the database as an existing namespace with no
  views shown, not as missing.
- **Data:** the record plus hidden views is a legitimate state. A record drop that leaves hidden views is what his
  route does anyway. So it does not block.
- **Smallest fix:** revert the gate in `holdsATableOrView`. The ungated probe at 362de10d was consistent with
  existence: 409 on both. Alternatively, have create and the drop guard also refuse when `databaseIds()` lists the
  database. Pin HEAD next to the drop and create in that test.
