# Review of pull request 163: one House Tables stand-in, held to the real store by one contract

Reviewed head `88bec497159822b1d213a121ea6af93e9a543e42` (unchanged on GitHub at the time of writing), base
`origin/irc-integration` `4b9d152d` (the merge base). Commits `3e9e3984` (contract, tests first), `ddb6f68a`
(`HouseTablesH2DatabaseTest`, tests first), `0c45a0f5` (stand-in rewrite and corrected tests), `88bec497` (merge of
the base). Read-only: nothing was built or run. CI was read from the check runs and from the logs of the two `rest`
jobs.

## 1. Verdict

**MERGE AFTER FIXES.** Only F1 must be done first, and it is a merge-order condition with pull request 161, not a
defect in this diff.

- **F1 (MUST, coordination):** merging 163 changes about a dozen answers that 161's `commit-path.tsv` records as
  "LinkedIn main's". They are answers of main's stand-in, not of main's product. Whichever of 161 and 163 merges
  second turns `CommitPath*Test` red unless it declares these answers as stand-in differences.

The three things that matter most:

1. **The contract is real, and it ran.**
   - `HouseTableRepositoryImplContractTest` drives the production `HouseTableRepositoryImpl` over HTTP to the
     production House Tables service (`HouseTablesSpringApplication`, in-JVM, random port).
   - It runs on H2 (`MODE=MYSQL;IGNORECASE=TRUE`) with the bootstrap `schema.sql`. It is not MySQL and not the
     production DDL.
   - It passed 30/30 in CI on both lines: `Default build, rest` and `Reference build, rest`, task
     `:services:tables:namespaceIntegrationTest`, which `check` depends on. No tag or assumption can skip it.
   - The two stand-in runs passed 60/60 between them on each line.
2. **Every difference on the assigned list is closed or declared.** The PR also pins two real product gaps that
   relied on the stand-in: House Tables keeps no UUID, so the view authorization resource carries a null UUID, and the
   UUID half of the register-view directory check is dead. Both are pinned with assertions that will fail when
   someone fixes them.
   - A third gap was found and reported but is not pinned: the IOException clean-up calls `delete(entity)`, which the
     real store refuses.
   - The same null-UUID defect on the native table DROP authorization (`findTableRefById`) is named in passing and not
     pinned.
3. **Some differences remain undeclared, all small, and the contract has a few gaps:**
   - The stand-in validates no name on reads, deletes or listings; the real store answers 400.
   - Spring Data still serves `saveAll`, `count`, `findAll(Sort)` and others, which the real repository refuses.
   - Sort order for names that mix digits and underscores follows Java/H2, not MySQL's collation.
   - Not pinned by the contract: the paged database list (`findAll(Pageable)`, which the product calls), and the
     database spelling a rename writes.
   - The persistence-context cause is gone, but two tests still call `entityManager.clear()` under a comment that is
     now false.

## 2. Findings

### F1. MUST FIX BEFORE MERGE (merge-order condition): 161's recorded commit-path answers change

- **What goes wrong.** Pull request 161 compares `CommitPath*Test` answers with
  `services/tables/src/test/resources/linkedin-main/commit-path.tsv`, recorded by running main's code with main's
  services-copy stand-in (161 head `210d54f8`).
  - `LinkedInMainComparison` fails on an undeclared difference and on a statement with no recorded outcome.
  - After 163 these recorded lines no longer match. Each is a stand-in artifact: main's real store would answer as 163
    does.
- **Every `GET /v1/databases/<db>/softDeletedTables`** (lines 22, 31, 64, 69, 77, 95, 101, 103 and 292 of the
  recording). They record `"pageable":{..."pageSize":1...},"size":1`, which is the old page-count-as-page-size bug. The
  new stand-in answers the requested page size, and `totalPages` changes accordingly.
- **`CommitPathStateRulesTest.renameToTheSameNameDifferingOnlyInCaseIsRejected GET .../st_ren_case/tables/T`.**
  Recorded `404`; now `200`, the same table, because the tables service reads ignore case.
- **`CommitPathStateRulesTest.renameOntoANameDifferingOnlyInCaseFromAnExistingTableSucceeds`.** Recorded `204`
  (lines 264 to 269); 163 renames the test to `...IsAConflict` and the request now answers `409`. The old key has no
  run and the new key has no recording.
- **`CommitPathConcurrencyTest.restoreOfAnUnknownDeletionTimeIsNotFoundAndKeepsTheSoftDeletedRow PUT .../restore`.**
  Recorded `204 ; 2 x 404`; now `204`, `404`, `409`.
- **Fix.** Whichever pull request merges second re-runs `CommitPath*Test` on the merge. It declares each line above in
  `CommitPathLinkedInMainDifferences` with a cause such as "main's House Tables stand-in answered this; the real
  store answers ...". The recorder runs main's own stand-in, so re-recording cannot remove these differences.
- **Certainty.** Certain for the lines quoted, from reading the recording against the new code. Whether more lines
  change can only be told by running the suite.
- **Spark side of 161** (`smoke-scenarios.tsv`, `write-statements.tsv`, `catalog-plugin-contract.tsv`). No change is
  expected, though none was run:
  - Every recorded `SHOW TABLES` / `SHOW VIEWS` / `SHOW DATABASES` answer has zero or one row, so the new sort order is
    invisible.
  - Renames onto an existing table are refused by the tables service before House Tables is called.
  - No Spark statement soft-deletes, restores or lists soft-deleted tables.
  - Row UUIDs and creators never reach a Spark answer; the answers come from metadata files.

### F2. SHOULD FIX: two tests still clear the persistence context, with a comment that is now false

- **Where:**
  - `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/IrcWriteRoutesAuthTest.java:1107-1109`
  - `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/IrcViewRoutesAuthTest.java:471-473`. This
    file is touched by this PR.
- **The lines:**

  ```
  // The House Tables stand-in serves this request's reads from the request's own
  // persistence context; the real House Tables service is read afresh each time.
  entityManager.clear();
  ```
- **What goes wrong.** The cause is removed: the stand-in now reads by projection
  (`HouseTablesH2StoreImpl.read`, `inDatabase`, `databases`) and detaches what it inserts. These two race tests still
  clear, so they no longer show the fix holds on their path, and they would hide a regression.
  `HouseTablesH2DatabaseTest.aReadInsideAnOpenPersistenceContextSeesALaterCommit` pins the fix directly, so this can
  wait. But it is two deletions and belongs to this PR's purpose.
- **Certainty.** Certain from reading.
- **Fix.** Delete both `clear()` calls and their comments; the tests should stay green.

### F3. SHOULD FIX: product gaps found but not pinned

- **Native table DROP authorization carries a null UUID in production.**
  - `TablesServiceImpl.deleteTable` (`services/tables/src/main/.../TablesServiceImpl.java:410-421`) authorizes a
    `TableDto` from `findTableRefById`. That method copies `houseTable.getTableUUID()`
    (`OpenHouseInternalRepositoryImpl.java:1155`), which the real store never fills (`UserTable` has no UUID field).
  - `OpaAuthorizationHandler` (`:165`, `:196`) and `OpaHandler` (`:64`, `:98`, `:156`) key table-level roles on the
    UUID.
  - Inherited: the same code is in `bb8929e1` (`TablesServiceImpl` lines 100 and 262).
  - The PR body mentions `findTableRefById` only as also being in main. No test pins it.
- **IOException clean-up.** `OpenHouseInternalTableOperations.java:613` calls `houseTableRepository.delete(houseTable)`.
  The real repository throws `UnsupportedOperationException`, which the catch (`HouseTableCallerException |
  HouseTableNotFoundException | HouseTableConcurrentUpdateException`) does not cover. The clean-up therefore replaces
  the `CommitFailedException` with an unmapped runtime error. Inherited from main (line 596). Reported, not pinned.
- **Severity for a real deployment.** On an OPA deployment, table-level and view-level grants are ignored on DROP TABLE,
  on view update and delete, and on the new IRC view load: only database-level grants decide. The IOException path
  answers a server error instead of 409 (pinned for views by `IrcViewRoutesAuthTest:256-258`; not pinned for DROP).
- **Fix.**
  - Pin the DROP case beside the view one: assert the `TableDto` passed to `checkTableDropPrivilege` has a null UUID.
  - Pin the clean-up with a test that injects an IOException on create and asserts what comes out.
  - Record both as RF items in `86-review-findings-and-fix-forward.md`, with the two pinned in this PR.
- **Certainty.** Certain from reading for both.

### F4. MINOR: undeclared stand-in differences

`HouseTablesH2StoreImpl.java:66-80` says it lists every difference. It does not list these:

- **Name validation on reads, deletes and listings.** The real House Tables service validates names on
  `GET`/`DELETE`/list/rename (`OpenHouseUserTableHtsApiValidator.validateGetEntity` and `validateGetEntities`). An
  invalid name (`a-b`) is a 400, which the client turns into `HouseTableCallerException`. The stand-in validates only
  on `save`, `saveView` and `rename` (`requireValidName`). `findById` answers 404, `findEntityById` answers empty,
  `deleteById` answers 404, `findAllByDatabaseId` answers an empty list.
  - Most tables-service and IRC routes validate names first (`tablesApiValidator.validateGetTable`), so reachability is
    low.
  - The recorded `CatalogPluginContractTest.loadTableWithInvalidIdentifierReportsNoSuchTable` with `bad db!.t` may
    depend on this; that cannot be told without running.
- **CRUD methods served by Spring Data.** `SimpleJpaRepository` still serves `saveAll`, `count`, `findAll(Sort)`,
  `findAllById`, `deleteAllById` and `deleteAll(Iterable)`. They write and read the JPA entity directly, with UUID,
  creator and URI kept and no compare-and-set. The real `HouseTableRepositoryImpl.java:265-345` throws
  `UnsupportedOperationException` for each. The comment declares only `existsById` and `deleteAll()`. No product code
  calls them (checked).
- **Sort order of names with digits and underscores.** The stand-in uses `String.CASE_INSENSITIVE_ORDER`; the real run
  uses H2 `VARCHAR_IGNORECASE`. Production MySQL `utf8mb4_0900_ai_ci` (UCA) orders punctuation before digits: `a_` <
  `a1` in MySQL, `a1` < `a_` in Java. This only moves page boundaries in paged listings. The contract uses
  single-letter names, so it cannot see it.
- **Soft-deleted rows are not transactional.** `deleteById(key, false)` puts the archived row into the map before
  `deleteRows`, and `restoreTable` removes it before `insert`. A failure after either step leaves the map changed where
  the real store's `@Transactional` rolls back. The lock and the prior reads make this practically unreachable today.

Fix: one bullet each in the class comment, or validation in the reads.

### F5. MINOR: contract gaps on paths the product uses

- **The paged database list.** `findAll(Pageable)` is called by `OpenHouseInternalCatalog.java:133`. It has no
  contract case, so its default sort, its collapsing of case variants and its page totals are held only by reading
  (`UserTablesServiceImpl.listDatabases(page, size, sortBy)`, default `databaseId`).
- **The spelling a rename writes.** The real rename writes `fromDatabaseId` as the caller spelled it
  (`UserTableHtsJdbcRepository.renameTableId`, called with `fromDatabaseId` for both). The stand-in does the same
  (`HouseTablesH2StoreImpl.java:279`). But `aRenameMovesTheRowAndItsLocation` renames through `db.toLowerCase()` and
  never asserts `renamed.getDatabaseId()`, so the respelling is unpinned.
- **Fix.** Add `assertEquals(db.toLowerCase(), renamed.getDatabaseId())` to the rename case, and add one case for
  `findAll(PageRequest.of(0, 1))` over two databases.

### F6. MINOR: weaker replacements in the view tests

- **Where.** `ViewsDualStorageH2IntegrationTest:124-129` and `ViewsServiceH2IntegrationTest:207-210, 255-258`.
- **What changed.** Before, both asserted that the row's UUID names the view root and stays the same across a replace.
  That was wrong: the real store keeps no UUID. Now they assert that the root ends in *some* UUID and that the root is
  unchanged.
- **What is lost.** Neither test now checks that the UUID is the view's own (its metadata's `view-uuid`), so a root
  named for the wrong UUID would pass.
- **Fix.** Compare the root's last 36 characters with the created view's metadata UUID.

### F7. MINOR: the fixtures' public surface changes with no release note yet

- **Published artifact.** The changes below are in `tables-test-fixtures-iceberg-1.5_2.12`, an artifact published by
  `openhouse.maven-publish`.
- **Removed members of `HouseTablesH2Repository`:**
  - `softDeletedTables`, `SAVE_LOCK`, `SOFT_DELETE_RETENTION_MS`
  - `findStoredLocation`, `saveAndFlush`
  - `findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase`, `findByDatabaseId(..)`, `findByDatabaseIdIgnoreCase(..)`,
    `findByDatabaseIdAndTableId`
  - the static helpers
- **Other changes:**
  - The resource `tables-test-fixtures-h2.properties` is removed.
  - `SpringH2TestApplication` now imports a `DataSource` bean, so a consumer's own `DataSource` bean, or its
    `spring.datasource.*` settings, no longer take effect.
  - `findById` of a missing row now throws instead of returning empty.
  - `HouseTableRepositoryContract` is added to main sources. It is relocated in the uber jar along with JUnit and is
    harmless.
- **Who could break.** In this repository, only the tables-service tests changed. An outside consumer that reads
  `HouseTablesH2Repository.softDeletedTables` stops compiling. One that expects `Optional.empty()` from `findById`, or
  saves a view through `save`, fails its tests.
- **Status.** The PR body lists all of this and says the release note (BC-039) is still owed. Make sure the note
  exists before the next publish.

### F8. MINOR: the author's record is stale in one place

`reports/house-tables-stand-in-fidelity.md` says "`HouseTablesH2Configuration`: ... location columns widened to
512". The code does not widen them; the stand-in keeps H2's 255, and the PR body says so correctly.

## 3. Behavior changes

| Change | Where | Product? | Toward or away from main | Author lists it |
|---|---|---|---|---|
| `namespaceIntegrationTest` description string | `services/tables/build.gradle:176` | No (build metadata) | n/a | Implicitly |
| Stand-in answers 404, 409 or 400 where it did nothing or overwrote; only House Tables fields kept | fixtures `src/shared/main` | No, test support (published) | n/a; the real store's answers | Yes |
| `DataSource` bean replaces the `@PropertySource` URL; consumer `spring.datasource.*` ignored | `HouseTablesH2Configuration`, `SpringH2TestApplication` | No, test support (published) | n/a | Yes (surface list); consequence for consumers' own settings: partly |
| Fixtures public types removed and added | fixtures | No, test support (published) | n/a | Yes |

- No file under `services/*/src/main`, `iceberg/`, `cluster/` or `client/` is in the diff (checked from the stat).
- Non-test files in the diff: `services/tables/build.gradle` (one description line) and the fixtures'
  `src/shared/main` sources and resource. Both are accounted for above.

## 4. Tests

**`HouseTableRepositoryContract`: 30 cases, three runs.**

- It can fail for the right reason, and before the rewrite it did: 7 of 30 passed on the fixtures stand-in and 4 of
  30 on the service copy, per the author; not re-run.
- I checked the conflict, not-found, case, length, paging, sort and soft-delete expectations against the production
  code end to end: `HouseTableRepositoryImpl` error mapping and retries, `UserTablesServiceImpl`,
  `UserTableHtsJdbcRepository`, `SoftDeletedUserTableHtsJdbcRepository`, `UserTablesMapper` / `UserTableVersionMapper`,
  `OpenHouseUserTableHtsApiValidator`, and the controller defaults (soft-deleted `sortBy` defaults to `databaseId`;
  table and view pages default to `tableId`; `getSortByStr` drops the direction). Each matches.
- **Expectations that hold only because H2 stands in for MySQL:**
  - Case-insensitive key uniqueness: rename onto `U`, and an update through another spelling. These need
    `IGNORECASE=TRUE`. They model production correctly, because the DDL says `COLLATE utf8mb4_0900_ai_ci`.
  - Too-wide value → 409. On MySQL this depends on strict `sql_mode`; production's is unknown, so this cannot be told.
  - Widths are per store: bootstrap 128/512 on the real run, production DDL 255/255 on the stand-ins. The stand-ins'
    255 is never checked against a real store, only against the DDL file. Declared.
- **Real run's wiring.** The repository is built by reflection with a default `ApiClient`, not production's Spring
  wiring (WebClient settings). The answers are unaffected.

**`HouseTablesH2DatabaseTest`, 4 cases.** Each targets one cause: the persistence context, rows per context, the lock
per context, and the URL that no property can override. The author says each was red on the old stand-in. The two
concurrency cases use 10 s and 30 s timeouts: a small flake risk on a loaded runner.

**Changed expectations in existing tests.** Each is classified correctly; the evidence is in the contract run on the
real store unless noted.

| Test | Old | New | Classification |
|---|---|---|---|
| `CommitPathStateRulesTest` case-only rename | 204, two tables | 409 | Right: rename onto `U` |
| `CommitPathStateRulesTest` same-name case | `GET T` 404 | Same table | Right: case-insensitive read |
| `CommitPathConcurrencyTest` second restore | 404 | 409 | Right: `aRestoreOverALiveTableIsAConflict`; occupancy is checked first in `restoreUserTable` |
| `TablesServiceTest` restore of a missing row | `NoSuchUserTableException` | `NoSuchSoftDeletedUserTableException` | Right: the catalog maps the 404 (`OpenHouseInternalCatalog.restoreTable:317`) |
| `RepositoryTest`, `HouseTablesH2ViewAccessorTest` descending pages | Descending order honoured | Ascending; page sort `unsorted` | Right: the product never sends a descending sort (only `ViewPaginationAdapter:69`, ascending); `PageImpl(PageRequest.of(n, s))` |
| `RepositoryTest` row fields | cluster, URI, UUID from the row | null | Right: `UserTable` has no such fields |
| View goldens | UUID and creator | null | Right |
| `IrcRegisterViewRaceTest` competitor | Identified by creator | Identified by location | Right |
| `IrcDirectoryReuseRoutesTest`, `IrcRegisterViewRouteTest` | UUID from the row | UUID from metadata | Right |
| `ViewsCorruptPointerH2Test` empty location | Written by save | Written directly | Right: 400 on save |
| `IrcHouseTableReadsTest` harness | — | 404 counted as a miss | Harness only |
| Cleanups | — | Type-aware | Harness only |
| `IrcViewRoutesAuthTest` | View's UUID | null | Product relied on stand-in: pinned; real |
| `IrcRegisterViewRouteTest...UuidIs400` | 400 | `...IsRegistered`, 200 | Product relied on stand-in: pinned; real; RF-012 / FF-36 |

- **Forbidden move.** None found. One test file was deleted: the services duplicate of `HouseTablesH2ReadAfterCommitTest`.
  The fixtures copy remains and both applications now share its `DataSource` configuration.
- No `@Disabled` or assumption was added, and no assertion was weakened without a real-store reason, except F6.
- **Unpinned claims:**
  - The paged database list and the rename's respelling (F5).
  - The undeclared differences (F4).
  - The DROP-path UUID and the IOException clean-up (F3).

## 5. Checked and found sound

**Lines of attack 1 and 2, the assigned differences.**

| Difference | Status | Where; contract case |
|---|---|---|
| Restore over a live table | Closed: 409 | `HouseTablesH2StoreImpl.restoreTable`; `aRestoreOverALiveTableIsAConflictAndChangesNothing`, `aRestoreOverAViewIsAConflict` |
| Rename, delete, restore of a missing row | Closed: 404 | `aRenameOfAMissingRowOrAViewIsNotFound...`, `aDeleteOfAMissingRowOrAViewIsNotFound...`, `aRestoreOfAMissingSoftDeletedRowIsNotFound` |
| Cross-database rename key | Closed: 400, as the real validator does | `aRenameToAnotherDatabaseOrToItsOwnNameIsTheCallersError...` |
| Overwrite through merge | Closed: reads and writes are JPQL or projections, with no merge | `aTableSaveOverAViewIsAConflict...`, `aViewSaveOverATableIsAConflict...` |
| Column widths | Declared (255 production DDL, 128/512 bootstrap, soft-deleted 128 not reproduced); contract parameterized | `theColumnWidthsBoundAndAWiderValueIsAConflict`. The reason is true (`ddl/0000__baseline.sql`, `schema.sql`) |
| Soft-deleted listing: case, sort, page size | Closed | `theSoftDeletedListingIgnoresCaseSortsAsAskedAndPagesInTheStore` |
| Static lock and soft-deleted store | Closed: instance fields of the fragment bean, one per context | `twoApplicationContextsShareNoRowsAndNoSoftDeletedRows`, `aWriteInOneContextDoesNotWaitForAnotherContext` |
| H2 result reuse through a lowest-precedence property source | Closed: `DataSource` bean | `noPropertyTurnsResultReuseBackOn` |
| Two copies disagreeing | Closed: one implementation | Services copy, its toggle repository and its properties deleted |
| Two lookups exact where production ignores case | Closed | `upper()` in `KEY` and `IN_DATABASE`; `theViewPagesIgnoreCaseAndHoldOnlyViews`, `aCreateIsReadBackByAnySpelling...` |

**Line of attack 3, one stand-in.**

- Exactly one implementation: `HouseTablesH2Repository` (fixtures) with its fragment `HouseTablesH2StoreImpl`.
- The second application class `SpringH2Application` remains. It now uses
  `@EnableJpaRepositories(basePackageClasses = HouseTablesH2Repository.class)` and imports `HouseTablesH2Configuration`.
- The services test package holds no other JPA repository, so the explicit `@EnableJpaRepositories` drops nothing.

**Line of attack 6, persistence context and per-context state.**

- The stale-read cause is removed (projections, detach after insert), not hidden, apart from F2.
- No JVM-static mutable state is left in the stand-in. The contract's static counter only names databases.
- Each context gets its own database name, lock and map, so the suite's cached contexts cannot interfere.
- Neither the product nor the tables service opens an outer transaction (no `@Transactional` in `services/tables/src/main`
  or the internal catalog), so the lock is held for one repository call.

**Line of attack 7, cost.**

- No Spring context is added to the tables suite: the service's contract test shares its cache key with
  `HouseTablesH2RepositoryTest`, so the 640 MB heap and its margin are unaffected.
- `namespaceIntegrationTest` gains one House Tables context (`@DirtiesContext`).
- The fixtures suite gains about 1.5 minutes in `HouseTablesH2DatabaseTest`: six application starts, 08:10:03 to
  08:11:37 in the default `rest` log. That job (16 min 22 s) runs beside longer jobs.

**Line of attack 8, pull request 162.**

- No file overlaps with 163.
- 162's tests do not reference the removed static members, row UUIDs or creators.
- `FormatVersionSwitchOffTest` deletes an existing row only.
- `IrcNamespaceRoutesFailureTest` mocks the namespace repository, not the table-row repository.
- Low risk, but re-run 162's tests on whichever merge comes second.

**Other checks.**

- Line of attack 5: no production file is in the diff.
- No private ids or pull request numbers in the added lines.
- CI on `88bec497`: all jobs green; the one skipped job is skipped by design.
- The REST compatibility kit's 106 non-passing tests are exactly its listed outcomes.

## 6. Not checked

- No build or run. Contract outcomes are taken from the CI logs; before and after counts from the author.
- Behavior on real MySQL: strict mode, collation sort order and error codes.
- Whether any LinkedIn-internal consumer of the published fixtures uses the removed members.
- 161's full comparison after a merge with 163: only the recordings were read, the suites were not run.
