# Review of pull request 165: register refuses a directory named for a live row's UUID; stand-in follow-ups; two inherited defects pinned

- **Head reviewed:** `bb81ddc95cd29679e017a02d5278a676e9831aa1`, unchanged on GitHub while reviewing.
- **Base:** `35bd941c`, which includes 163 and 164.
- **Commits:** 8d71727c, 9e67f5c0, f845a7bd, 60dc7c11, 3800e9c9, fb6ea19d, 2a44eaea, b920fdcb, c5c33d08, b3d92a0c, and the merge bb81ddc9.
- **How reviewed:** read only; nothing was built or run.

## 1. Verdict

**MERGE AS IS, once both `rest` jobs are green.**

- **CI at the time of writing:**
  - `Default build, test groups` and `Reference build, test groups` passed. Those are the jobs that run `check-private-ids.py` (`split-build.yml:74-79`).
  - Reference `apps-spark-4.2` and `spark-4.2-openhouse-spark-itest` passed.
  - Both `rest` jobs were still running. They carry the tables-service suite, the fixtures suite and the real-store contract (`namespaceIntegrationTest`). The claim that the contract passes 33/33 on the real store is therefore the author's local run only.
- **Findings:** no MUST FIX. Three SHOULD FIX, five MINOR.

The three things that matter most:

1. **The product fix is right, complete for its callers, and costs nothing extra.**
   - `namesUuid` parses the UUID from the name of the directory that holds the row's metadata file. OpenHouse allocates that directory as `<db>/<name>-<uuid>` (`BaseStorage.allocateTableLocation`, `HdfsStorage:58`, `LocalStorage:71`) and keeps it on rename.
   - It reads only the rows the check already read, and loads no metadata.
   - It runs only after the database `CREATE_TABLE` check, on all three routes.
   - It does not depend on anything only the stand-in has.
2. **The null-UUID call-site list is complete: six decision paths, plus the IRC view drop, which reaches the native drop site.** It is listed separately below.
   - The four Iceberg REST paths (metrics report, view load, view commit and, through native code, view drop) are this branch's own routes. Pinning them as "known defect" is honest: the body says "In LinkedIn main: no". But they are not inherited. A cheap fix exists, the same directory-name UUID this PR introduces. See F1.
3. **The review-163 findings are all closed or declared.** F2, F4, F5, F6, F7 and F8 are each closed, and the remaining stand-in differences are declared in its class comment.
   - Two small gaps: the soft-deleted listing does not check the `tableId` it is given (F4).
   - The commit messages carry private planning ids (F7).

## 2. Findings

### F1. SHOULD FIX: four Iceberg REST paths decide table-level privileges on a resource with no UUID

- **What goes wrong.** These paths are the branch's own code, not LinkedIn main's:
  - `IcebergRestApiHandler.reportMetrics` (`:355-360`, `GET_TABLE_METADATA`, through `findTableRefById`)
  - `ViewsServiceImpl.loadReadableView` (`:148`), reached by the IRC view load (`IcebergRestViewHandler:139`) and by the commit's base read and its answer (`:167`, `:196` through `loadReadable:274`)
  - `ViewsServiceImpl.viewReference` (`:158`), reached by `IcebergRestViewHandler.authorizeCommit:234-241` (`GET_TABLE_METADATA` and `UPDATE_TABLE_METADATA`)

  They are pinned, not fixed (`RowBuiltAuthorizationResourceTest`, `IrcViewRoutesAuthTest`).
- **Who it affects.** On an OPA deployment the decision is made without a table id. This repository's handler sends `tbl_id: tableDto.getTableUUID()` (`OpaHandler:62-64`, `:333-344`).
  - Gson's `toJsonTree` drops null map values by default, so `input.tbl_id` is undefined.
  - In the sample policy (`infra/recipes/docker-compose/common/opa/policy.rego`) both table-level rules (`current_db[input.tbl_id]`) are then undefined. Only database-level roles and the two fixed principals can allow.
  - The result fails closed: a principal with only a table- or view-level grant is refused, including a view shared to everyone through the `"*"` `TABLE_VIEWER` rule.
  - The deployed policy cannot be known from this repository.
- **The smallest fix (branch routes only).** The row's location lies in `<name>-<uuid>/`. `namesUuid`'s parse, inverted to return the UUID, gives the resource UUID without a metadata read, under the same assumption this PR already relies on.
  - The native drop, view replace and view drop are main's code. Changing them needs the owner's decision.
- **Certainty.** Certain from reading this repository. The production effect depends on the deployed policy.

### F2. SHOULD FIX: CI had not finished the jobs that carry the claims

- The real-store contract (33 cases: 30 plus the database pages, invalid names on reads, and refused methods) runs in `:services:tables:namespaceIntegrationTest`.
- The tables-service suite runs without the two `clear()` calls, and the new pins run beside it.
- All of these are in the `rest` jobs, which were still in progress.
- **Fix.** Merge only when `Default build, rest` and `Reference build, rest` are green. From the log, check that `HouseTableRepositoryImplContractTest` shows 33 PASSED, and that `IrcWriteRoutesAuthTest` and `IrcViewRoutesAuthTest` passed.

### F3. SHOULD FIX: one more Spring context in the tables-service suite

- `CommitIoFailureCleanUpPinnedDefectTest` declares `@SpyBean LocalStorageClient`, which no other class has. That makes a new context cache key, so the suite gets one more context start and one more eviction from the cache of eight. The 640 MB heap's measured margin was about 96 MB.
- `RowBuiltAuthorizationResourceTest` shares `IrcViewRoutesAuthTest`'s key (the same annotations and the same `@MockBean`), so it adds no context.
- **Fix (if the CI heap or time moves).** Fold the spy into an existing context, or stub the file system some other way. Cannot tell without the run.

### F4. MINOR: the soft-deleted listing does not check the `tableId` it is given

- The real House Tables service validates the listing's `tableId` against the search pattern (`OpenHouseUserTableHtsApiValidator.validateUserTable:120-131`).
- The stand-in checks only the database (`requireListableDatabase`).
- The class comment says names are checked "on the listings". The only callers pass `null` or a validated name.
- **Fix.** One clause in the comment, or the same check.

### F5. MINOR: the call-site table omits the IRC view drop

- `IcebergRestViewHandler.dropView:249` calls `viewsApiHandler.deleteView`, which reaches the native view-drop site.
- That site is pinned, so nothing is missing from the tests, but the list for the owner should name the Iceberg REST route too.

### F6. MINOR: a pre-existing race now also covers the UUID half

- The check runs before the registration's write, so two concurrent registers of one UUID under two names both pass and both save. Their directories differ, `<a>-X` and `<b>-X`, so a later rename can collide.
- This needs `CREATE_TABLE` and concurrency. The location half had the same race before.
- No fix proposed here; it is worth one line beside the existing read-then-write TODOs.

### F7. MINOR: private planning ids in commit messages

- Lines with `RF-012`, `RF-062`, `FF-36`, `BC-039` and `#164` appear in the messages of 9e67f5c0, f845a7bd, 60dc7c11, 3800e9c9, 2a44eaea, c5c33d08 and the merge.
- The tree is clean: no added line carries one, and the guard passed. If history matters for upstreaming, reword the messages when the branch is squashed.

### F8. MINOR: how `namesUuid` parses

- It accepts any name that ends in `-` plus 36 characters `UUID.fromString` parses. It checks neither the name before the dash nor the database segment.
- A row whose location is not OpenHouse-allocated (custom storage, metadata in a subdirectory) is simply not matched by the UUID half. That gives a false accept, never a false refusal.
- This is documented in the javadoc; fine as is.

## 3. Behavior changes

| Change | Path | Line | On main | Toward or away from main | Listed by the author |
|---|---|---|---|---|---|
| A directory whose name carries the UUID of a live or soft-deleted row is refused with 400 | IRC register table (`IcebergRestWriteHandler:947`) and staged-create completion (`:719`) | Both lines (`src/main`) | No such routes | n/a; matches the routes' documented rule; on by default | Yes |
| The same, against table, soft-deleted and view rows | IRC register view (`IcebergRestViewRegistration:167`) | Iceberg 1.12 line only | No such route | n/a | Yes |
| Published fixture: invalid names refused on reads, deletes and listings; Spring Data methods refused | `HouseTablesH2StoreImpl`, `HouseTablesH2Repository` | Both lines | — | Toward the real store | Yes, and in the README |

- **Native routes.** None changes: `uses`, `namesUuid` and `refuseIfUses` have no other callers (checked).
- **Product files in the diff.** The only two are `IcebergRestWriteHandler.java` and `IcebergRestViewRegistration.java`.

## 4. Tests

- **`IrcRegisterViewRouteTest.aDirectoryNamedForALiveTablesUuidIs400`** is back to 400.
- **`IrcRegisterRouteTest.aDirectoryNamedForALiveTablesUuidIsRefused`** is new. It asserts "belongs to" and the live table's name. Only the UUID half can produce that answer, because the directory is `w-<uuid>`, not `t`'s. Its tests-first commit (8d71727c) shows the failure.
- **False refusals** were checked against `IrcDirectoryReuseRoutesTest`:
  - A create carrying a live table's UUID is still created in a directory of its own, under a new UUID, because the server allocates both.
  - A renamed table's and a soft-deleted table's directories were already refused.
  - A dropped table has no row.
  - The view's own earlier directory cannot coexist with an occupant at that name.
  - UUID comparison ignores letter case (`UUID.equals`).
- **`RowBuiltAuthorizationResourceTest`** (5 tests) can fail for the right reason.
  - Each test asserts a table-level decision was made on a resource named for the entity, and that the resource has a null UUID, while the entity's metadata carries one.
  - That this is production behavior rests on the contract case `theStoreKeepsOnlyItsOwnFieldsAndDefaultsTheStorageType`, which asserts a null UUID on the real store.
- **`CommitIoFailureCleanUpPinnedDefectTest`** asserts `UnsupportedOperationException("Entity deletion is not supported.")`.
  - The message is the real implementation's (`HouseTableRepositoryImpl:329`).
  - The contract case `theMethodsTheRepositoryDoesNotServeAreRefused` asserts the real store refuses `delete(entity)`.
  - The test also asserts that the row and the file stay.
  - It is driven through the replicated create's metadata rewrite, which is the one checked `IOException` in that block.
- **No test weakened.**
  - The two `clear()` calls were deleted (F2 of review 163).
  - The view tests now compare the root with the metadata's `view-uuid`, and check that a replace keeps it: stronger than before.
  - The two waits in `HouseTablesH2DatabaseTest` went from 10 s and 30 s to 120 s. They are failure bounds only, and the reason is written down.

## 5. Checked and found sound

**Item A, the product change:**

- **What it reads per request:**
  - one `findAllByDatabaseId`;
  - soft-deleted pages of 100 (`SOFT_DELETED_PAGE_SIZE`; the view register's `PAGE_SIZE`);
  - for a view register, view pages of 100.
  - No metadata file is read, and nothing more than before the change.
- **What a caller can learn.**
  - The check runs after the database `CREATE_TABLE` decision, on all three routes: `registerTable:935`, `commitCreate:717`, and the view register at `:153`. Before that decision, only the name's occupancy is answered, as natively.
  - A privileged caller who already knows a UUID can learn which table in the database holds it. That is the location half's existing behavior.
- **Case and staged creates.** UUID comparison ignores case. A staged completion of an existing table fails on its own assertion before the check runs.
- **Native routes and the reference line.** No native route changes. The table register and staged completion run on both lines.

**Item C, the review-163 findings:**

| Finding | Status | Where |
|---|---|---|
| F2 | Closed | 60dc7c11 |
| F4 | Closed, with the rest declared in the class comment | Names on reads and deletes; refused methods; soft-deleted map writes ordered after the row; `_` against digits; restore and purge |
| F5 | Closed | `theDatabasePagesNameEachDatabaseOnceInNameOrder`; the rename spelling assertion |
| F6 | Closed | View tests compare with the view's own UUID |
| F7 | Closed | Fixtures README release note, which matches the code |
| F8 | Closed | Record corrected; the two waits bounded at 120 s |

**Item D:**

- No other product change.
- Fixtures: three contract cases added.
- `check-private-ids` passed in CI.

## 6. Not checked

- The `rest` jobs' results, which were still running.
- The deployed OPA policy.
- Behavior on real MySQL.
- Whether LinkedIn's internal storages allocate `<name>-<uuid>`: a different scheme only makes the UUID half miss.

## Null-UUID call sites, verified (for the owner)

The resource's `tableUUID` comes from a House Tables row, which keeps none. Each path decides through `AuthorizationUtils.checkTablePrivilege`, then `AuthorizationHandler.checkAccessDecision(principal, TableDto, privilege)`.

| # | Route | Native or Iceberg REST | Where the resource is built and decided | Privilege | Same on LinkedIn main |
|---|---|---|---|---|---|
| 1 | DROP TABLE | Native | `TablesServiceImpl.deleteTable:416-419`, via `OpenHouseInternalRepositoryImpl.findTableRefById:1155` | `DELETE_TABLE` (`checkTableDropPrivilege`) | Yes (`TablesServiceImpl:262`, `OpenHouseInternalRepositoryImpl:964`) |
| 2 | View replace (PUT of an existing view); also reached by the IRC view commit's `viewsApiHandler.updateView` (`IcebergRestViewHandler:174`) | Native | `ViewsServiceImpl.putView:276-277`, `authorizationReference:333-337` | `UPDATE_TABLE_METADATA` (`forPut(true)`) | Yes (`ViewsServiceImpl` about :174, :230-234) |
| 3 | View drop; also reached by IRC `dropView` (`IcebergRestViewHandler:249`) | Native | `ViewsServiceImpl.deleteView:319-320` | `DELETE_TABLE` (`forDelete()`) | Yes (about :217) |
| 4 | IRC metrics report | Iceberg REST | `IcebergRestApiHandler.reportMetrics:355-360`, via `findTableRefById` | `GET_TABLE_METADATA` | No (branch route) |
| 5 | IRC view load, and the commit's base read and answer | Iceberg REST | `ViewsServiceImpl.loadReadableView:148`, from `IcebergRestViewHandler:139` and `loadReadable:274` (used at `:167` and `:196`) | `GET_TABLE_METADATA` | No (branch route) |
| 6 | IRC view commit's pre-decision | Iceberg REST | `ViewsServiceImpl.viewReference:158`, then `IcebergRestViewHandler.authorizeCommit:234-241` (`authorizeRead`, `authorizeReplace`) | `GET_TABLE_METADATA` and `UPDATE_TABLE_METADATA` | No (branch route) |

**Not affected:** these paths read a `TableDto` from metadata, which carries the UUID, or make no table-level decision.

- `TablesServiceImpl.tableExists:196` (no decision)
- Table GET, update, lock, unlock and ACL routes; rename (`findById`); IRC table commits (`IcebergRestWriteHandler:414-415`, `loadBase`)
- Restore and purge (database privilege only)
- The view create and register, and every database-level check

**What the authorization handler does with it, from this repository's code only:**

- `OpaHandler.checkAccessDecision` sends `{"user", "privilege_to_check", "db_id", "tbl_id": <uuid>}`, and Gson drops the null `tbl_id`.
- In the sample policy, the table-level rules and the `"*"` `TABLE_VIEWER` rule need `input.tbl_id`, so they cannot allow. The database-level rule and the fixed principals still can. The outcome fails closed for table- and view-level grants and does not widen access.
- Grants themselves are made with the metadata UUID (`OpaHandler.grantRole:93-102`, from `updateAclPolicies`, which loads the table), so a table-level grant can never match these six decisions.
- The deployed policy, and any other `AuthorizationHandler` used in production, cannot be known from here.
