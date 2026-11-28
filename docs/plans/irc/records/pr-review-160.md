# Review of pull request 160 (irc/namespace-existence-consistency, head 87140d87)

Reviewer: independent sub-agent, read-only. Worktree `/home/user/oh-irc-ns2`. The change is
`git diff origin/irc-integration...HEAD`, commits `fd3216ec` (tests) and `87140d87` (code). Nothing was run.

## 1. Verdict

**MERGE AFTER FIXES.** Close G1 first.

The three things that matter most:
1. **The rule now has one implementation (G1 aside).** Every namespace route asks it:
   - HEAD, `parent=`, list views and the rename target call `requireNamespace` (table probe, record, list);
   - list tables uses its own listing as the table probe, then the record, then the list;
   - load reads the record, then calls `holdsSomething` (table probe, list);
   - create and drop call `holdsSomething`;
   - update goes to his service (404 without a record; the known F3).

   The five rows of the matrix are what the code answers.
2. **G1: the "is listed" half of the rule compares the database name exactly.** The table probe and the record match
   names case-insensitively, but a view-only database is found only under its stored spelling. So a case variant of
   a view-only database's name bypasses the drop guard and the create conflict on the views-enabled path. At the base
   (518a2134) the drop guard's view probe was case-insensitive. This is an undeclared regression, and it breaks the
   rule the pull request is for.
3. **The premise holds by reading.** House Tables' database list has no entity-type predicate, and nothing between it
   and the handler filters views. The H2 stand-in behaves the same. The authorization order is unchanged.

## 2. Findings

### G1. MUST FIX BEFORE MERGE: a case variant of a view-only database escapes the drop guard and the create conflict
- **Where:** `IcebergRestApiHandler.isListed`:
  ```java
  /** Whether the native database list shows {@code databaseId}, spelled exactly so. */
  private boolean isListed(String databaseId) {
    return databaseIds().contains(databaseId);
  }
  ```
  It is used by `holdsSomething`, which create and drop use, and by `requireNamespace` and `listTables`.
- **Two halves, two matching rules:**
  - The table probe (`searchTables` paged, the House Tables `...AllIgnoreCase` finders) and the record (collation)
    match case-insensitively.
  - The list half matches exactly.
- **Case 1:** a view-only database stored as `foo`, with a record. `DELETE /iceberg/v1/namespaces/FOO`:
  - HEAD `FOO` answers 204, from the case-insensitive record.
  - `holdsSomething("FOO")` is false: no table, and `"FOO"` is not in the list `["foo"]`.
  - His drop deletes the record while the namespace holds a view. That is the "not empty" case the guard exists for.
  - At 518a2134, with views enabled, the guard used `findAllViewsByDatabaseId` (case-insensitive) and answered 409.
- **Case 2:** the same database without a record. `POST /iceberg/v1/namespaces {"namespace":["FOO"]}`:
  - It stores a record for the database that holds the view. At 518a2134 (views enabled) it answered 409.
  - HEAD `FOO` answers 404 (exact list), so the pinned "HEAD 404 ⇒ create stores" still holds. But the same
    namespace, spelled `foo`, already exists. The rule only holds per spelling.
- **Reads were already like this.** A HEAD of `FOO` on a view-only `foo` answered 404 before. A HEAD of `FOO` on a
  table-holding `foo` answers 204. So reads already treated views and tables differently by case; the writes now
  inherit that.
- **Effect:**
  - no data loss beyond what his own drop does (the views stay);
  - the drop guard and create conflict silently fail for one spelling;
  - the stated rule ("a view counts") is not one rule across spellings;
  - an undeclared write-side behavior change against the base.
- **Certainty:** certain from reading. Not run.
- **Smallest fix:** compare case-insensitively, as the table probe and the record do:
  `databaseIds().stream().anyMatch(id -> id.equalsIgnoreCase(databaseId))`.
  - Doing it only in `holdsSomething` would split reads from writes again.
  - Doing it everywhere also changes HEAD, load, list tables and `parent=` for a case variant of a view-only database
    from 404 to 204/200. That aligns them with the table-holding case; declare it.
- **Test:** add a row to `everyRouteFollowsOneExistenceRule` with a view-only `x` and a record created as `X`.
  Assert that drop of `X` is 409 and the record is kept, and that create of `X` without a record is 409.

### G2. MINOR: the load failure path asks `holdsSomething` twice
- **Where:** `loadNamespace`. When `readRecord` fails, the catch calls `holdsSomething`. The record is then empty, so
  the code falls through to `if (!holdsSomething(databaseId)) throw missing(databaseId);` and repeats the table probe
  and the full database list.
- **Impact:** only while the store fails; the answers are correct.
- **Fix:** remember the result of the first call.

### G3. MINOR: an assertion that cannot fail
- **Where:** `assertOneExistenceRule`'s 404 branch:
  ```java
  assertEquals(expectedDrop, 404, db);
  ```
  It runs after the drop was already asserted 404.
- **What it can and cannot catch:** it can only catch a wrong `expectedDrop` argument in the test itself. Each row's
  outcome is still pinned by the asserted HEAD and the branch taken.

## 3. Behavior changes against the base (518a2134)

| # | Case | Before | After | Declared |
|---|---|---|---|---|
| 1 | Create of a view-only database, views disabled, no record | 200 (record stored) | 409 | yes |
| 2 | Drop of a view-only database, views disabled, no record | 404 | 409 | yes |
| 3 | Drop of a view-only database, views disabled, with a record | 204 (record deleted) | 409 | implied by 2 |
| 4 | Create or drop of a case variant of a view-only database, views enabled | 409 | 200 / 204 (G1) | **no** |
| 5 | Load of a name with no record and no table | two record reads, then the list | one record read, the table probe, the list (pinned `loadNamespaceMissing`) | yes |
| 6 | Create or drop of a name that holds no table: the probe after the table probe | one-row view probe (views enabled), or nothing (disabled) | the full database list (pinned in `IrcHouseTableReadsTest`) | yes, as a cost |

All other read answers are unchanged: `requireNamespace` and `listTables` keep their order (table, record, list) and
answers. `requireListedDatabase` was only renamed into `isListed` plus `missing`.

## 4. Checks asked for

- **Other cases, beyond G1:**

  | Case | Answers | Consistent |
  |---|---|---|
  | Views-only plus a record | HEAD 204 (record); load 200 with properties; create 409; drop 409 | yes |
  | Only table soft-deleted | soft-deleted rows live in a separate House Tables table, so not listed; the table probe sees live tables only; HEAD 404, create stores a record, drop 404 | yes |
  | Leftovers of a table renamed away | a rename moves the row, so nothing is left | yes |
  | Record spelled differently from the database's tables | everything is case-insensitive on those halves | yes |
- **The premise.**
  - The database list reaches House Tables through `HouseTableRepositoryImpl.findAll` → `getUserTables({})` →
    `UserTablesServiceImpl.listDatabases()` → `findAllDistinctDatabaseIds` (`SELECT DISTINCT databaseId FROM
    UserTableRow`). It has no entity-type predicate, and `UserTableRow` has no `@Where`.
  - In the tables service, `DatabasesServiceImpl.getAllDatabases` and `IcebergRestApiHandler.databaseIds` apply no
    gate and no per-database flag.
  - `HtsRepositoryTest.testFindDistinctDatabasesListsADatabaseThatHoldsOnlyViews` pins the House Tables query. It
    runs in CI only.
  - The H2 stand-in (`services/tables/.../HouseTablesH2Repository`) uses Spring Data's `findAll` over all rows of
    every entity type, so it lists view-only databases too. The matrix therefore exercises the same rule the service
    applies. It does not exercise House Tables' Iceberg-backed store (`repository/impl/iceberg/UserTableHtsRepository`,
    not read). That is not a JDBC deployment.
- **Cost.** A create or drop of a name with no table reads the database list once, after the authorization decision.
  It is the same distinct-database query as the miss of the exists route, and the native list makes no authorization
  decision of its own. Acceptable for a `SYSTEM_ADMIN` write.
- **Authorization order.** Unchanged lines in `IcebergRestNamespaceHandler`:
  - create: name and level, `authorizeWrite`, validation, then `holdsSomething`;
  - drop: `authorizeWrite`, then `refuseDropWhileItHoldsATableOrView`.

  A denied caller gets 403 in every row of the matrix; `IrcNamespaceRoutesAuthTest` is untouched and still covers
  tabled, record, new and invalid names.
- **Scope.**
  - No native file changed.
  - None of the owner's namespace files changed: `HtsRepositoryTest` is LinkedIn main's House Tables test, and its
    change is one added test.
  - No private ids were added.
  - The read handler no longer depends on `HouseTableRepository` or `ViewsFeatureGate`. That closes the duplication
    with `ViewsServiceImpl` noted in F8 of the review of pull request 157.

## 5. Tests

- **`everyRouteFollowsOneExistenceRule`:**
  - It can fail on each row: HEAD, load, create and drop are asserted per row, and the record presence afterwards.
  - The views-disabled row asserts the empty view list too, so it shows the gate is off.
  - Not covered: G1's case variant.
- **`loadNamespaceMissing`:** it pins one record read, and would fail on the old double read.
- **`whenTheRecordStoreFailsADatabaseThatHoldsOnlyAViewLoadsAsBefore`:** it closes the gap I noted at 63c65e76. It
  can fail: if `holdsSomething` lacked the list half, it would answer 500.
- **`IrcHouseTableReadsTest` create and drop:** the full database list replaces the view probe, as stated.
- **`HtsRepositoryTest`:** the right query is pinned. CI only.

## 6. Not checked

- No build or run; CI was not read.
- House Tables' Iceberg-backed store.

## Re-check at 39cbf161

Scope: `bc75c01a` (tests) and `39cbf161` (code). The merge `e2a191d9` is CI only and was not reviewed. Read only;
nothing was run.

**Verdict: MERGE.** G1, G2 and G3 are closed. Nothing new blocks the merge.

### G1: closed
- **One comparison, used by every route.** `IcebergRestApiHandler.listedAs` compares the database list with
  `equalsIgnoreCase`. It is the only list comparison: `isListed` (reads: `requireNamespace`, `listTables`) and
  `heldAs`/`holdsSomething` (load, create, drop) both go through it.
- **The four mixed-case rows of `theOneExistenceRuleIgnoresLetterCase` are what the code answers:**

  | Row | HEAD | Load | Create | Drop | Afterwards |
  |---|---|---|---|---|---|
  | Views in `irc_ns_mix_vr`, record created as `IRC_NS_MIX_VR`; asked as `IRC_NS_MIX_VR` | 204 (record) | 200, spelled `IRC_NS_MIX_VR` (the record's) | 409 (the list matches `irc_ns_mix_vr`) | 409 | record kept |
  | Views in `irc_ns_mix_v`, no record; asked as `IRC_NS_MIX_V` | 204 (list, ignoring case) | spelled `irc_ns_mix_v` (the list's) | 409 | 409 | |
  | Tables in `irc_ns_mix_t`; asked as `IRC_NS_MIX_T` | 204 (table probe ignores case) | | 409 | 409 | |
  | Record created as `IRC_NS_MIX_R`; asked as `irc_ns_mix_r` | 204 | | 409 (his duplicate) | 204 | record gone |

- **The record-plus-views row would have failed at 87140d87.** There, `isListed("IRC_NS_MIX_VR")` was false against
  `irc_ns_mix_vr`, so the drop reached his service: 204 instead of 409, and the record deleted. Both
  `assertEquals(expectedDrop, dropped)` and `hasRecord` would catch that.

### Stand-ins
- **Faithful to the real store.** House Tables lists a database's tables with
  `findAllTablesByFilters(databaseId, ...)`, whose `COMMON_FILTER_CLAUSES` compare
  `upper(u.databaseId) = upper(:databaseId)`. Spring Data's `findByDatabaseIdIgnoreCase` also compares `UPPER()`.
- **Confined to test code:**
  - `services/tables/src/test/.../HouseTablesH2Repository`;
  - `tables-test-fixtures/.../shared/main/.../HouseTablesH2Repository`, a fixtures module consumed only through
    test and harness configurations (checked in review 153).
  - Each change has its own test.
- **Existing tests.** The change can only alter tests that list a database's tables under a different spelling than
  stored. Before, such a test could see "no tables" where production sees them. Now the stand-ins agree with
  production, so the change removes a way to pass for the wrong reason rather than adding one. The author reports
  1120/1120 for `e2e.h2`; not re-run by me.
- **A correction to the test comment.** The row comment "Tables in irc_ns_mix_t: already true before; pinned" was
  true of production only. Under the old exact-spelling stand-in that row would have answered HEAD 404, so the old
  tests could not have shown it.
- **The two lookups still exact in the stand-ins do not affect these tests:**
  - The fixtures stand-in's views-by-database: the existence rule no longer uses a view probe. It reaches only the
    native view create and list in the Spark itests and kit, not these tests.
  - The service stand-in's find-by-id: a table or view key lookup, which no existence check uses.
  - Both are worth a line in the record as known infidelities, but neither touches the rule's tests.

### Requested versus stored spelling on load
- **The behavior.** A namespace found by the table probe answers the requested spelling (`heldAs` returns
  `databaseId`), as on the base and since before pull request 157. One found through the record or the list answers
  the stored spelling.
- **Neither the specification nor the clients care:**
  - The specification's `GetNamespaceResponse.namespace` is just a namespace reference. Nothing requires it to echo
    the request.
  - Iceberg's `RESTSessionCatalog.loadNamespaceMetadata` (1.5.2 and 1.12) returns only `response.properties()` and
    never compares the namespace.
  - Spark's DESCRIBE NAMESPACE prints the identifier it resolved, not the response's.
- **Verdict:** a pre-existing cosmetic inconsistency. List it; do not fix it here. Making the table-probe path
  answer a stored spelling would need another read (the probe returns no spelling) for no client-visible gain.

### G2, G3 and the rest
- **G2 closed.** `held` is computed at most once per load, and the failure path reuses it. Pinned by
  `loadNamespaceWhileTheRecordStoreFails` (the failed read, then one table probe).
- **G3 closed.** The assertion that could not fail is gone. A real one replaces it at the top of
  `assertOneExistenceRule`: HEAD 404 exactly when the drop is expected to be 404.
- **Authorization order.** `IcebergRestNamespaceHandler` is untouched by the fix commits, so the decision still comes
  first.
- **Scope.** Nothing native, none of the owner's files, no private ids. The only production file changed is
  `IcebergRestApiHandler`.
- **A behavior note for the description.** A view-only database asked under another spelling now answers HEAD 204,
  load 200 (with the stored spelling), and list tables / `parent=` 200, where it answered 404. This follows from G1's
  fix as required; check that the description lists it.
