# Three more pull requests merged after an independent review: 162, 165 and 161 (2026-11-28)

Bottom line: three pull requests merged into `irc-integration` on 2026-11-28, each after an independent sub-agent review under
the owner's standing order S58: 162 (merge `67989d16`, about 10:03 UTC), 165 (merge `35cc7b1e`, about 11:01 UTC) and 161 (merge
`81cb4b74`, about 12:10 UTC, the head). `irc-integration` is at `81cb4b74`; 86 pull requests are merged, fifteen of them under the
standing review order (every number from 151 to 165). What is true now that was not at the last notes commit (`8539df30`, which
recorded 83 merged through 164 and listed 161, 162 and the follow-up to 163 as in flight):

1. The suites that carry the claim "existing behavior did not change" now compare with a stored recording of LinkedIn main (161).
   LinkedIn main `bb8929e1` was run with its own build, six runs in fresh JVMs; 734 statements are stored; on both Iceberg lines
   every one gives main's answer or a declared difference that quotes main's answer and names the cause. The declared product
   differences are 9 on the reference line (all the owner's namespace stack) and 17 on the default line, each with a ledger row
   (BC-029, BC-030, BC-046 to BC-049, BC-092, BC-093); there are 16 declared test-double differences on each line, on the commit
   path only; there is no difference on write statements on either line. This is a measurement of one path, not a proof that
   behavior is unchanged: the statement of what it proves and does not prove is in section 3, in full.
2. Register table, the completion of a staged create and register view refuse a directory named for another entity's UUID, which
   production accepted (165; the check had compared against row UUIDs, which production rows do not have). Three Iceberg REST
   decisions (the metrics report, the view load, the view commit's own decisions) authorize on the entity's UUID, so a table-level or
   view-level grant can now allow under a UUID-keyed policy. Three decisions of LinkedIn main's (native DROP TABLE, native view
   replace, native view drop) still authorize with no UUID, as on main, and are pinned by tests (RF-069).
3. The `/iceberg` handlers answer an authorization outage with 503 on namespace existence checks, never guess a view-commit answer,
   and turn two 500s into 409 and 400 (162).

Not true yet, and not claimed: pull request 166 (FF-27, FF-29, FF-30) is not merged; the comparison with main covers one path (a
full re-record is FF-55); the deployed authorization policy is unknown (RF-069); the store-failure asymmetry on namespace existence
is left (FF-59); the real-store contract still runs on H2, not MySQL with the production DDL (FF-56). The compatibility kit: no merge
message of these three mentions it, so it is "not re-read" (the last reading, 160 tests: 54 passed, 90 failed, 16 skipped (CORRECTION 2026-11-28, from pull request 167: the kit then ran with format v3 hard-coded on, so this is the format v3 run; the default configuration is 52 passed, 92 failed, 16 skipped), was by the
authors of 157, 160, 158 and 163; the review of 161 saw 90 failed kit test lines in the default `rest` log at `210d54f8`, as
expected). Nothing is merged into either fork's main or into LinkedIn's or Apache's repositories.

Sources: the three merge commit messages (`67989d16`, `35cc7b1e`, `81cb4b74`), the authors' records
(`records/iceberg-rest-handler-minors.md`, `records/stand-in-follow-up.md`, `records/baseline-of-linkedin-main.md`), the pull request
body of 161 (read with the GitHub API; the plans for FF-23 and FF-24 and the table of differences are there) and the reviewers'
reports (`records/pr-review-162.md`, `-162-recheck.md`, `-165.md`, `-165-recheck.md`, `-161.md`, `-161-recheck.md`), under the common
brief `records/pr-review-brief-common.md`. Marks: [ran] executed and seen; [read] read in a file, log or merge message; [inferred]
concluded without either. The reviewers ran nothing except what is said in section 3 (the re-check of 161 ran two read-only CI
scripts and Python over the files); every other review claim below is [read]. Test and build results are the authors', as the merge
messages report them.

## Post-merge CI, as read by the orchestrator on 2026-11-28 UTC

- 158 (`4b9d152d`), 163 (`a0239ad7`) and 164 (`35bd941c`): green [read].
- 162 (`67989d16`), 165 (`35cc7b1e`) and 161 (`81cb4b74`): not read.
- Pull request builds before merge, from the merge messages: 162 on `448fcd6f`, 13 jobs green and 1 skipped by design (the private-id
  guard ran and passed); 165 on `04422776`, 13 jobs green and 3 skipped by design; 161 on `028f295f` (which includes the base at
  `35cc7b1e`), 13 jobs green and 1 skipped by design, with no recorded answer moved on that base.

## 1. Pull request 162: the `/iceberg` handler minors (merge `67989d16`)

Bottom line: closes the entries of FF-53 that concern the Iceberg REST handlers and findings C1 and C3 of the re-check of 158; the
rest of FF-53 is as listed in `86-...`, and the store-failure asymmetry is now FF-59. Two review rounds: the first MERGE AFTER FIXES
(one missing test and seven smaller findings), the second MERGE. Head reviewed in the second round: `448fcd6f`, which is also the head
on GitHub.

### What it leaves true
- During an authorization outage a namespace existence check on `/iceberg` answers 503 unless the namespace holds a table or the
  database list shows it. While the decision cannot be made, a missing name and a hidden record cannot be told apart, so both answer
  the same for every caller. A denial still reads as "no record" (404), which hides a record-only namespace from a caller who may not
  read it. A database that holds a table or only a view answers as before.
- A view commit is never answered with a version it may not have stored: a commit result with no metadata location answers "commit
  state unknown" before anything is read back. By reading, that case is not reachable today.
- Completing a staged create on a table that already exists reads nothing of that table and answers 409. Before, an unreadable
  metadata file gave a 500 that named the file's path to any caller, a denied one included.
- An Iceberg REST commit that lowers the format-version property answers 400, from the one format-version rule, after the native
  validation and the lock checks, and is audited. Before: 500. A replace that lowers it is pinned: 200, and the table stays at
  version 2.
- The busy 503 with `Retry-After`, the timeout answer, the start-up refusal for a non-positive planning bound and the rename's 409
  mappings are run end to end.
- No native route changes: the changed rule has one caller, the Iceberg REST commit path (the re-check verified by `git grep` that
  the native table PUT and the native snapshots PUT never reach it); the native repository's check only had its message moved into a
  shared helper, byte-identical.

### Behavior changes (ledger BC-095 to BC-098; all on `/iceberg`, none with a LinkedIn main baseline)
1. During an authorization outage every namespace name that holds no table and is not in the database list answers 503 instead of
   404, names that do not exist included.
2. A view commit with no metadata location answers 500 "commit state unknown" instead of 200 (not reachable today).
3. A staged-create completion on an existing table with unreadable metadata answers 409 instead of 500.
4. A non-replace commit or one-table transaction that lowers format-version answers 400 instead of 500, and a request that lowers the
   version and also fails a later rule (the format v3 types refusal, the client-capability gate, the column-default refusal, the
   repository's own checks) now gets the format-version 400 instead of that refusal.

### What the independent review found and how each finding was closed

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | F1: a namespace that holds only a view during an outage had no test; deleting the `isListed` branch kept every test green | MUST FIX | `d5080e6f` (a test seen failing with the branch removed locally: expected 204, was 503). Confirmed in round 2: HEAD 204, lists and load 200, the rename into it 406 and not 404 |
| 1 | F2: the existence checks and `loadNamespace` follow different rules on a store failure (404 on the checks, 500 on load) while the javadoc said they are one | SHOULD FIX | `7635dd2c`: the javadoc states both rules; behavior left (D83); the pull request body names the smallest alignment (FF-59) |
| 1 | F3: the timeout test could flake (a zero bound and a plan that may finish first) | MINOR | `91956108`: a 100 ms bound and a planner pool that waits in `beforeExecute` until the test releases it |
| 1 | F4: the "House Tables conflict" rename tests inject the conflict in the authorization stub, the comment said "last decision" where it is the first of three, and `join()` had no timeout | MINOR | `2e15b32f`: renamed `aRenameCommitConflictOn*`, the seam stated, `join(30_000)` and an `isAlive` check |
| 1 | F5: the lowering rule existed twice, and the new refusal ran before the native validation; its audit claim was untested | MINOR | `2bdba951`: one rule, `FormatVersionRule.refuseUpdate`, back after the validation and the lock rule; two UPDATE FAILED events asserted |
| 1 | F6: the FF-53 "left" list was incomplete | MINOR | the pull request body |
| 1 | F7: C1's "view create" path does not exist (it was the empty branch of the view list) | MINOR | the pull request body says so |
| 1 | F8: one of six outage assertions passed for another reason | MINOR | `d5080e6f`. The re-check says F8 was itself wrong (a cross-database rename is refused 406 before the existence check could matter); the added assertion is harmless |
| 2 | R1: the lowered request that also fails a later rule gets a different refusal than before | can follow | said in change 4 of the merge message |
| 2 | R2: one `entityManager.clear()` with a comment made false by 163 remained in `IrcWriteRoutesAuthTest` | can follow | removed by pull request 165 (`04422776`) |

The re-check read both merges of the base as identical to a clean automatic merge (`448fcd6f` finished by hand after the commit hook
dropped the merge state; its tree equals `git merge-tree` of its two parents), found no Spring context added, and found the final
diff to be 12 files, all on this branch.

### Known gaps (merge message)
When the namespace record store itself fails, a record-only namespace answers 404 on the existence checks and 500 on load; left on
purpose, a test pins the 404, and aligning them is its own change (FF-59: in `hasRecordOrIsListed`, rethrow every failure when the
database list does not show the namespace, as load does; the 404 asserted under `storeFails()` would become 500). Retained test
contexts keep the suite's heap at 640 MB. The table probe answers the requested spelling on load. The audit kind of a refused commit
is approximated. The format v3 types authorization test lacks the Iceberg REST allowed-caller case. A create at a cluster version
above 2 with the switch off answers a wrapped 500 (a misconfiguration). The rename tests inject the failure at the first of the
native rename's decisions and do not cause a real House Tables conflict. One stale `entityManager.clear()` with a false comment
remained in a test (removed by 165). The reviewer did not read CI logs to confirm the new test methods ran rather than being skipped.

## 2. Pull request 165: the follow-up to 163 (merge `35cc7b1e`)

Bottom line: closes FF-54 and RF-012, fixes the three null-UUID decisions that were this branch's own and pins the three that are
LinkedIn main's (RF-069), pins the clean-up defect (RF-070). Two review rounds: the first MERGE AS IS (once both `rest` jobs were
green) with three SHOULD FIX findings, which were folded in; the second MERGE. Heads: round 1 `bb81ddc9`, round 2 `04422776`.

### Why it exists (merge message)
The House Tables store keeps no UUID on a row. The stand-in used by the tests did, until pull request 163 made it faithful, and that
exposed code that compared against, or authorized on, a value production never has: this branch's register check could not fire in
production, and six authorization decisions passed a resource with no table UUID, so that under a policy keyed on the UUID a
table-level or view-level grant could never allow. Three of the six are this branch's own Iceberg REST code; three are LinkedIn main's.

### What it leaves true
- The UUID of the entity a row belongs to is read from the name of the directory OpenHouse allocated for it (`<database>/<name>-<uuid>`,
  kept across a rename), by one helper (`AllocatedDirectory.uuidOf`). For every directory the server allocates this equals the UUID in
  the entity's metadata, which is what table load and commit already authorize on and what grants are keyed on (the re-check
  verified it for native create, a replica, CTAS, Iceberg REST staged create, register, register view and rename).
- Register table, the completion of a staged create, and register view refuse (400) a directory named for the UUID of a live,
  soft-deleted or view row in the database. The check reads only the rows it already read and no metadata file, after the
  create-table decision.
- The Iceberg REST metrics report, view load, and the view commit's own decisions authorize on the entity's UUID. No read was added
  before a decision.
- LinkedIn main's three decisions (native DROP TABLE, native view replace, native view drop) still authorize with no UUID, exactly
  as on main, and are pinned by tests that say so. The clean-up after an I/O failure in the table commit path, which calls a delete
  the real repository refuses, is pinned as it behaves: the caller gets an unsupported-operation error and the row and the metadata
  file stay.
- The stand-in validates names and refuses the repository methods the real store refuses; the contract has 33 cases and passes on the
  real store in both lines' `rest` jobs (33 in each, 66 for the two stand-in runs together, per the re-check). The fixtures module's
  README tells outside consumers what changed (`tables-test-fixtures/tables-test-fixtures-iceberg-1.5/README.md`; ledger BC-039).

### The six decisions, verified (RF-069; review 165, its call-site table and the re-check)

| # | Decision | Native or Iceberg REST | Privilege | LinkedIn main's | After 165 |
|---|---|---|---|---|---|
| 1 | DROP TABLE (`TablesServiceImpl.deleteTable`, via `findTableRefById`) | Native | DELETE_TABLE | Yes | No UUID, as on main, pinned |
| 2 | View replace (`ViewsServiceImpl.putView`, `authorizationReference`); also reached by the Iceberg REST view commit's `updateView` | Native | UPDATE_TABLE_METADATA | Yes | No UUID, as on main, pinned |
| 3 | View drop (`ViewsServiceImpl.deleteView`); also reached by the Iceberg REST `dropView` | Native | DELETE_TABLE | Yes | No UUID, as on main, pinned |
| 4 | Metrics report (`IcebergRestApiHandler.reportMetrics`) | Iceberg REST | GET_TABLE_METADATA | No | UUID |
| 5 | View load, and the view commit's base read and answer (`ViewsServiceImpl.loadReadableView`) | Iceberg REST | GET_TABLE_METADATA | No | UUID |
| 6 | View commit's own decisions (`viewReference`, `authorizeCommit`) | Iceberg REST | GET_TABLE_METADATA and UPDATE_TABLE_METADATA | No | UUID, then decision 2 with none |

What this repository's code establishes (read, not run): the policy input lacks the table id when the UUID is null (`OpaHandler`
sends `tbl_id`, and Gson drops a null map value), so under the sample policy the table-level rules and the `"*"` table-viewer rule are
undefined: a table-level or view-level grant cannot allow on those decisions, database-level roles and the two fixed principals
still can, and it fails closed. Grants are made with the metadata UUID, so such a grant can never match them. What is not known:
the deployed policy and any other `AuthorizationHandler` used in production.

### Behavior changes (ledger BC-099, BC-100; BC-039 updated), none on native routes, none with a LinkedIn main baseline
On the Iceberg REST register, staged-create completion and register-view routes a directory named for another entity's UUID is
refused where production accepted it. On the Iceberg REST metrics report, view load and view commit a principal holding only a
table-level or view-level grant is now allowed by a UUID-keyed policy where the grant could not match before. For consumers of the
fixtures: invalid names and unsupported repository methods are refused.

### What the independent review found and how each finding was closed

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | F1: the Iceberg REST metrics report, view load and view commit decided on a resource with no UUID; they are the branch's own routes, not inherited, and a cheap fix existed (the directory-name UUID) | SHOULD FIX | `f3402db0` (tests first, a grant backend keyed on the UUID alone: allowed with the grant, 403 without, a random UUID refused) and `aeb2c695` (the fix). Confirmed in round 2, including that the three native callers still pass null |
| 1 | F2: both `rest` jobs, which carry the tables-service suite and the real-store contract, had not finished | SHOULD FIX | They finished green on `04422776`: `HouseTableRepositoryImplContractTest` 33, `HouseTablesH2RepositoryContractTest` 66, `RowBuiltAuthorizationResourceTest` 8, `CommitIoFailureCleanUpPinnedDefectTest` 1, `IrcWriteRoutesAuthTest` 30, `IrcViewRoutesAuthTest` 10, `IrcRegisterRouteTest` 18, `HouseTablesH2DatabaseTest` 4, in each `rest` job |
| 1 | F3: `@SpyBean` on the storage client made one more Spring context, with a measured heap margin of about 96 MB | SHOULD FIX | `07c9d64b`: the test swaps the `fs` field of the shared client for a spy inside `try` and restores it in `finally`; no context added; no leak to later tests (no parallel test execution) |
| 1 | F4: the stand-in's soft-deleted listing did not check the `tableId` it is given | MINOR | `ed517ec7`, `4216e630`: checked against the search pattern, with a contract assertion |
| 1 | F5: the call-site table omitted the Iceberg REST view drop | MINOR | named in the `RowBuiltAuthorizationResourceTest` javadoc |
| 1 | F6: the existing read-then-write race now also covers the UUID half (two registers of one UUID under two names both pass) | MINOR | open, can follow: no code note beside it (RF-072) |
| 1 | F7: private planning ids in the commit messages of the branch | MINOR | open, can follow: the tree is clean and the guard passed; reword if squashed |
| 1 | F8: `namesUuid` accepts any name ending in a UUID | MINOR | documented in the javadoc; it can miss and never refuse wrongly |
| 2 | An orphaned javadoc in `ViewsServiceImpl` (lines 332 to 335: the inserted method's javadoc sits after `authorizationReference`'s own, which has none now) | can follow | open (FF-53) |

The re-check also verified: the merge of the base (`d8975dfe`) lost nothing of 162 (the three files both sides changed differ from
162's version by exactly the branch's own hunks); `04422776` removed the third `entityManager.clear()` (none remains in the tables
tests); and the review of 163's findings F2 and F4 to F8 are each closed or declared.

### Known gaps (merge message)
- The Iceberg REST view commit passes its own decisions with the UUID and then reaches the native view replace, which has none: a
  principal with only a view-level grant gets further (the base metadata is read, which it may read) and is then refused; nothing is
  written. The Iceberg REST view drop decides only through the native drop. Closing either changes native behavior and is the
  owner's decision (RF-073). The re-check judged the half-fixed commit not worse than before: before 165 that principal was refused at
  the first decision.
- Dropping an entity does not revoke its grants, and the register check covers live, soft-deleted and view rows only: registering
  under the UUID of a purged entity, or winning the pre-existing read-then-write race between two registers, can inherit stale
  grants. Tables already had this exposure through the metadata-based paths (and, per the reviewer, LinkedIn main's replica create
  can choose a UUID); for views it is new on the Iceberg REST load and commit decisions (RF-072).
- The UUID parse accepts any directory name ending in a UUID: it can miss, never refuse wrongly. A storage that allocates
  directories differently makes the check miss.
- What the deployed policy does with a missing UUID is unknown from this repository. No run against MySQL. What an HTTP caller gets
  on the clean-up path is inferred (500), not run.
- Minor: one orphaned javadoc in `ViewsServiceImpl`; no code note beside the register race.

## 3. Pull request 161: the evidence suites compare with a stored recording of LinkedIn main (merge `81cb4b74`)

Bottom line: closes FF-20 and FF-21, FF-22 in part, and RF-060 in part. No product change. Three review rounds: the first MERGE
AFTER FIXES (head `210d54f8`: three must-fix findings M1 to M3 and six should-fix S1 to S6), the second MERGE (head `0e5472cd`),
the third a confirmation of the last five commits after the base moved (head `028f295f`, MERGE). 161 was held for that third round
(D85): the re-check had said MERGE on `67989d16`, then 165 merged, and the author merged `35cc7b1e` and ran the comparisons on it.

### Why it exists (merge message)
The claim "existing behavior did not change" rested on suites whose expected answers were literals written while looking at this
branch, so the baseline moved with the code it was meant to hold still (FF-20, FF-21, FF-22 in part; RF-060). The owner's rule is
that a behavior change against LinkedIn main needs justification; that needs main's answers measured, not remembered.

### What it leaves true
- LinkedIn main `bb8929e1` was run with its own build (six runs, fresh JVMs, raw output kept) and what it answered is stored in the
  repository: 734 recorded statements across the Spark SQL smoke scenarios (302), the write statements (80), the catalog plugin
  contract (42) and the commit-path tests (310). The reviewer checked all four stored files against the union of the six raw runs:
  byte-identical. The header of each names the commit, the JDK, the Iceberg jars loaded and the test doubles.
- On both Iceberg lines, in CI, every one of those statements gives main's recorded answer or a declared difference that quotes
  main's answer exactly and names the cause. A statement with no recorded answer fails. An unused declaration, and a declaration whose
  statement gives main's answer again, fail. A recorded test or test class that is gone or disabled fails. A second answer added to
  a key by hand fails. Record mode refuses to run on anything but main's commit.
- Every place a test branches on the Iceberg line is in a reviewed list checked in CI (92 occurrences on 50 lines: 11 difference from
  main, 6 declared difference, 63 one line has the feature, 5 Iceberg client version, 7 detection, per the pull request body).
- Declared product differences: 9 on the reference line (LinkedIn Iceberg 1.5.2), all from the owner's namespace stack; 17 on the
  default line (the fork's Iceberg 1.12): 5 namespace stack, 6 from Iceberg 1.12's namespace-existence check, 2 from the
  column-defaults capability, 1 for the row-lineage columns `DESCRIBE TABLE EXTENDED` now lists, 3 for Jackson's location text in 500
  messages. No difference on write statements on either line (80 checks) or on the commit path on the reference line (65 tests).
- Declared test-double differences, a separate kind: 16 on each line, on the commit path only. That suite was recorded on main's code
  with this branch's earlier House Tables stand-in (`e2e/h2/HouseTablesH2Repository` at `0fe4678a`) copied into main, not main's own;
  the stand-in now answers as the real store does. For each, the product code on the path (controller, service, repository, catalog,
  House Tables repository) is identical text on main and here, verified by the reviewer.

### The declared differences, by kind and line

| Kind | Reference line | Default line |
|---|---|---|
| Product (behavior of this branch's code or its dependencies) | 9: smoke 6, contract 3 | 17: smoke 7, contract 7, commit path 3 |
| Test double (the House Tables stand-in, not the product) | 16, commit path | 16, commit path |

By cause, with the ledger row each is filed under:

| Cause | Reference line | Default line | Ledger |
|---|---|---|---|
| The owner's namespace stack (namespace records: `CREATE`, `USE`, `DESCRIBE`, `ALTER`, `DROP NAMESPACE`; the contract's namespace tests) | 9 (smoke 6, contract 3) | 5 (smoke 2, contract 3) | BC-046 to BC-049 |
| Iceberg 1.12's namespace-existence check ("Checking if database exists is not supported" where main says "Describing database is not supported") | 0 | 6 (smoke 4, contract 2) | BC-030 |
| The column-defaults capability (the catalog declares none, so one more 422 `CLIENT_CAPABILITY_MISSING`) | 0 | 2 (contract `capabilityMismatch`) | BC-029 |
| The row-lineage columns `_row_id` and `_last_updated_sequence_number` listed by `DESCRIBE TABLE EXTENDED` on a format 2 table (scenario s26; the fork's `SparkTable`) | 0 | 1 | BC-092 (new in the ledger) |
| Jackson's location text in three 500 messages for a body that is not JSON (`[Source: (String)"not json"; ...column: 4]` on main, `[Source: REDACTED ...column: 1]` here; jackson-core changed the default of `INCLUDE_SOURCE_IN_LOCATION` in 2.16) | 0 | 3 (commit path) | BC-093 (new in the ledger; D80) |
| Total | 9 | 17 | |

Known, found by the reviewer, and outside the recording: BC-094, a merge-on-read `DELETE` names its snapshot "delete" on the default
line where main says "overwrite" (the fork's `BaseRowDelta`). No write statement runs a merge-on-read delete, and the write-statements
suite is shared by four platforms, so a statement was not added (FF-55).

The 16 test-double differences: 9 soft-deleted listings (`pageSize` and `size` 1 from the old stand-in, 10 as the real store pages),
1 read by another spelling of a name (404 before, the same table now), 1 restore request (`cc_restore_unknown`: a missing
soft-deleted row and a restore over a live table are answered as the real store does), and 5 answers of one renamed test (a rename
onto an occupied key of another spelling: 409, where the old stand-in let it make a second table). Each cites the contract case that
pins the real store's answer (`theSoftDeletedListingIgnoresCaseSortsAsAskedAndPagesInTheStore`,
`aRestoreOfAMissingSoftDeletedRowIsNotFound`, `aRestoreOverALiveTableIsAConflictAndChangesNothing`,
`aCreateIsReadBackByAnySpellingWithTheSpellingItWasCreatedWith`, `aRenameOntoAnOccupiedKeyOfAnySpellingIsAConflictAndChangesNothing`);
all five exist in `HouseTableRepositoryContract` and assert what is claimed [read, re-check]. The five answers of the renamed test pin
the branch's own answer through `NOT_OBSERVED_ON_MAIN` and the old test name is in `absentTests()`: for those five the comparison holds
the branch to itself, not to main (main's answer under the real store is inferred from the contract, not recorded). The merge message of
163 said "about a dozen" such answers; the count is 16.

### What a green run proves, and what it does not (the merge message, in full)

> On Spark 3.5 through the existing OpenHouse catalog over the native API, and for the native route shapes the commit-path tests
> send, each recorded statement gives main's answer or a declared one, after normalization (ids, UUIDs, long numbers, timestamps,
> the OS user, JSON key order) and under test doubles that allow nearly every principal everything. It does not prove existing
> behavior is unchanged. Not measured: authorization decisions, whether two ids are the same id, the bodies of successful writes,
> error fields other than the message, Spark 3.1 and 4.x, the maintenance jobs, House Tables routes, the Java client outside the
> contract test, published artifacts. One known Spark SQL difference on the default line is outside the recording: a merge-on-read
> DELETE names its snapshot "delete" where main says "overwrite".

The pull request body adds, in the same spirit [read]: the nine native route shapes the commit-path tests send (`DELETE` table, `/lock`,
`/purge`; `GET` table and `softDeletedTables`; `PATCH /rename`; `POST` create and `/lock`; `PUT` table, `/iceberg/v2/snapshots`,
`/restore`); answers to one request within a test are compared as a multiset, so which request got which answer is lost; answers
sent from race threads are compared by status only; the rest-shim and rest-http paths are not compared with main; and the test
doubles differ by suite: the Spark suites ran on main's own House Tables stand-in and this branch's grant-storing authorization
backend (smoke) or the fixture's allow-everything handler (write statements, contract), and the commit-path suite on the earlier
stand-in above.

### The stand-in measurements
Main's Spark suites were re-run three times with this branch's stand-ins compiled in (before the stand-in rewrite, after it, after its
follow-up): 0 of 424 Spark keys differ each time (302 smoke, 80 write, 42 contract). The reviewer recomputed two of the runs from the
raw output: 424 keys, none outside the stored outcomes, none missing, none extra, and the fixtures module was recompiled in both
runs. So the difference between main's stand-in and the branch's changes no recorded Spark answer. This replaced the pull request's
first claim ("same fixtures as the branch, so only production code differs"), which was false for the Spark suites (M1).

### Behavior changes
None in the product. Build (ledger BC-101, BC-102): one more Spark 3.5 test class, `LegacyPathWriteStatementsTest` (about 14 s per
line); two small Python checks in the test-groups job (the Iceberg-line check, `scripts/ci/check-iceberg-line-branches.py`, is named
by the records; the merge message says two and the second is not named [not verified]).

### What the independent review found and how each finding was closed

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | M1: the premise "only production code differs" was false for the Spark suites: they ran on main's own stand-in and the branch's rewritten one (compare-and-swap, the delete flag read the other way round, case-insensitive listing, a rename guard) | MUST FIX | `5f4db2e4`, `b03b8757`: every recording's header names its test doubles (asserted by the files test); the three measurements above; the text corrected |
| 1 | M2: the `NOT ON MAIN` marker turned any red statement green by a hand edit, and the failure message advertised it | MUST FIX | `1877920a`: removed; an old marker is refused at parse; `NOT_OBSERVED_ON_MAIN` must pin the branch's own answer in code |
| 1 | M3: the Iceberg-line check found `IcebergLine.current()` only and collapsed duplicates (missed `IcebergV3.supported()` and the `openhouse.iceberg.line` property) | MUST FIX | `d1b4eecf`, `035765ea`: all three forms, counted per entry; 92 occurrences on 50 lines |
| 1 | S1: a removed or disabled test dropped its recording silently | SHOULD FIX | `29bfbe28`: found by reflection; named in `absentTests()` with a reason or it fails |
| 1 | S2: the commit-path recording predates the stand-in now in the tree | SHOULD FIX | `6f2fc38d`: the 16 test-double differences declared; a full re-record is FF-55 |
| 1 | S3: default-line differences in no ledger row (the merge-on-read DELETE, Jackson) | SHOULD FIX | `b361eeca`, `6b3db036`: refiled and reworded; BC-092 to BC-094 written; no write statement added (shared by four platforms) |
| 1 | S4: normalization erased identity (every UUID is `<uuid>`) | SHOULD FIX | left, FF-55 |
| 1 | S5: what the commit-path comparison does not compare (bodies of successful writes, errors by message only, a sorted multiset) | SHOULD FIX | left, FF-55 |
| 1 | S6: one environment variable turned every comparison into a green no-op | SHOULD FIX | `090e1d61`: record mode refused unless the commit is `bb8929e1` and no branch-only class is on the classpath |
| 2 | Hand-added second outcome lines; a whole recorded class deleted; `record.sh` writing fixed header text; stale text; a public citation reworded | can follow | all closed by `7a9a287c`, `3db9c32b`, `028f295f` and the merge (`record.sh` refuses `tables` and writes the stand-in line only from a measurement made in that run) |
| 3 | A narrow gap: deleting a whole Spark recording's owning class (the smoke and write recordings are keyed by scenario or method name) would not fail | can follow | open (FF-53) |

The re-check verified: the three merges of the base each have both parents and lost nothing (in `96a25fb0` two branch-only files
changed inside the merge, only to reword "Apache Iceberg pull request 14507/11868", which the private-id guard requires); the 16
declarations quote main's answers exactly (19 of 19 with the three Jackson ones); the product methods on the 16 paths are the same text
at `bb8929e1` and at head (it extracted and compared each); CI on `0e5472cd` all green (per line: 65 `CommitPath*` tests, 21 contract, 34 smoke, 19
write, 3 and 1 files tests, 6 comparison self-tests in each module), and on `028f295f` 13 jobs succeeded and 1 was skipped by design; and
that the five commits of the third round change no recorded answer, no declaration beyond wording, and no comparison logic.

### What merging 161 first buys for 166
The re-check lists what the comparison would see if pull request 166's build changes moved a native answer on the default line (a status,
an error's `message`, a GET body's keys and values) and what it would miss (bodies of successful writes, error fields other than the
message, key order, number formatting inside GET bodies, timestamp formats, request-parsing strictness except in the few malformed-body
tests). The recording has no malformed-body native answer. This is why 161 merges before 166 (D85).

### Known gaps (merge message)
Only that one path is compared with main. The comparison's blind spots above need a full re-record with placeholders that keep id
identity, write-response bodies and ordered answers per request; re-recording the commit path first needs the stand-in ported into
main's test sources (FF-55). Deleting a whole Spark recording's owning class would not fail. Comparing the shipped jars' contents with
main's (FF-23) and a scheduled run of the older delta harness (FF-24) are left with plans (in the pull request body, not the author's
record). The unstable column-tag order on main hides one ledger row (BC-005) from the suite.

## In flight, not results

- **Pull request 166** (`irc/ci-check-name-and-build-hygiene`; FF-27, FF-29, FF-30 and three follow-ups to the private-id guard). First
  review (`records/pr-review-166.md`): everything except its Jackson commit MERGE AS IS; the Jackson commit DO NOT MERGE. The orchestrator
  withdrew the Jackson alignment (D84); the author removed it, kept exact pins of each line's Jackson list, and merged the base. A
  re-check report exists (`records/pr-review-166-recheck.md`, written at `e5da0456` on base `35cc7b1e`, before 161 merged) with the
  verdict MERGE, every must-fix closed; I record that a report exists, not a merge: 166 was not merged when these notes were written, and it
  merges after 161 (D85), so it takes the new base and the comparison with main runs on it.

## Pointers

- Ledger: `85-behavior-change-ledger.md`, part F (BC-095 to BC-100), part C (BC-101, BC-102), BC-039 and BC-083 and BC-084 updated, and
  the rewritten section on what the recording of LinkedIn main measured.
- Findings: `86-review-findings-and-fix-forward.md` (the fifth status update at the top; RF-012 closed; RF-069 rewritten; RF-070 pinned;
  RF-072, RF-073 new; FF-20, FF-21 done; FF-22 partly; FF-23, FF-24 not started; FF-54 done; FF-53 partly; FF-55 extended; FF-59 new;
  FF-27, FF-29, FF-30 in flight).
- Decisions: `90-decisions-continued-7.md` D84 (the Jackson alignment withdrawn), D85 (merge order), D86 (503 in an outage), D62
  extended a second time, D79 corrected, D83 marked merged. Waiting on the owner: `58-waiting-on-mike.md` (the verified null-UUID
  entry, the half-fixed Iceberg REST view commit and drop, grants that a drop does not revoke, the clean-up's unsupported delete).
- State: `18-state.md`. Merged list: `45-merged-pull-requests.md`.
- Where to start to use the recording of LinkedIn main: `00-map.md` section 9; in the code repository `scripts/linkedin-main-baseline/`
  (README, `record.sh`).
- Records: `records/pr-review-162.md`, `-162-recheck.md`, `-165.md`, `-165-recheck.md`, `-161.md`, `-161-recheck.md`,
  `records/iceberg-rest-handler-minors.md`, `records/stand-in-follow-up.md`, `records/baseline-of-linkedin-main.md`; in flight
  `records/pr-review-166.md`, `-166-recheck.md`, `records/ci-check-name-and-build-hygiene.md`.

## Where the brief and the records differ, and corrections to earlier notes

- The plans for FF-23 and FF-24 are not in `records/baseline-of-linkedin-main.md` (which says only "left with plans"); they are in the
  pull request body of 161 (FF-23: main's jars recorded as entry lists and a CI job building the same on the reference line, not
  small; FF-24: a scheduled job of about 25 minutes with `-Xmx3g` and a machine-readable expected list, not cheap).
- A re-check report of 166 exists (`records/pr-review-166-recheck.md`, verdict MERGE at `e5da0456`); the brief said a re-check was
  running with no results. Recorded as a report only.
- The stand-in of the commit-path recording: earlier notes (`94-...`, D79, the ledger section on the recording) said that main's own
  old stand-in produced it, and that about a dozen answers differ. The recording was made with this branch's earlier stand-in copied into
  main, and the declared test-double differences are 16 on each line. Corrected in the ledger and in D79; `94-...` carries a pointer.
- The merge message of 161 says "two small Python checks in the test-groups job"; its pull request body says "one Python check
  (0.1 s)", and the records name one. The second is not identified [not verified].
- The first review of 165 speaks of four Iceberg REST paths (it counts the view drop, which reaches a native decision); the verified
  list has six decisions, three of them Iceberg REST (the view drop is the native decision 3, reached through the Iceberg REST route).
- Not verified here: whether pull request 165's merge touched anything of FF-36's dead-code list other than the UUID branch (the merge
  message does not say); whether a release of the fixtures module carries the consumer note.
