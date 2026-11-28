# Re-check of pull request 165 at `0442277697373ebe3707e9e4c807aeea57d2aafa`

- **Head:** `0442277697373ebe3707e9e4c807aeea57d2aafa`. GitHub's head equals the worktree's.
- **Base:** `origin/irc-integration` `67989d16`.
- **New since `bb81ddc9`:** f3402db0, aeb2c695, 07c9d64b, ed517ec7, 4216e630; the merge d8975dfe; 04422776.
- **How reviewed:** read only; nothing was built or run.

## Verdict

**MERGE.** Nothing is MUST FIX.

Three minor items remain open. None blocks the merge:

- an orphaned javadoc;
- the register race is not noted in code;
- older commit messages carry planning ids.

## 1. The UUID fix (f3402db0 tests first, aeb2c695 fix)

### Code

- New `utils/AllocatedDirectory.uuidOf(location)`. It returns the UUID that the name of the metadata file's directory ends in (`-<36-char uuid>`); otherwise it returns empty. `namesUuid` now delegates to it.
- `IcebergRestApiHandler.reportMetrics:357-371` replaces the row resource's UUID with `uuidOf(row.getTableLocation())`. If there is none, it keeps the row's UUID, which is null.
- `ViewsServiceImpl.icebergRestAuthorizationReference:346-355` is the same for views. It is used only by `loadReadableView:151` and `viewReference:161`.

### (c) The native callers still get null (checked by callers, not only by the pins)

| Native decision | Builder | Changed? |
|---|---|---|
| View replace, `ViewsServiceImpl:280` | `authorizationReference` | No |
| View drop, `ViewsServiceImpl:323` | `authorizationReference` | No |
| DROP TABLE, `TablesServiceImpl:416` | `findTableRefById` | No |

- `TablesServiceImpl:196` (`tableExists`) also uses `findTableRefById`, unchanged; it makes no decision.
- `AllocatedDirectory` has no other caller.

### (b) Nothing is read or revealed before a decision

- `uuidOf` parses a string the code already holds. No metadata read was added.
- The order is unchanged: row, then decision, then metadata (view load), or row, then decision, then counter (metrics).

### (a) Trust: who controls the directory name, and can it borrow another entity's grants?

**Where the directory UUID comes from.** It is always the UUID the server allocated the directory for, and that is the same UUID the entity's metadata carries:

- **Native create:** the UUID is generated or taken from properties, and the location is allocated from it (`BaseStorage.allocateTableLocation`; for a replica, `calculateTableLocationId` uses the source id with the same UUID).
- **CTAS:** the UUID comes from the manifest-list path.
- **Iceberg REST staged create:** the UUID is parsed from the allocated location (`tableUUID()`).
- **Iceberg REST register:** the UUID comes from the allocated directory name, and `create(..., tableUUID)` writes that UUID into the metadata.
- **Register-view:** the UUID comes from the allocated directory, and `requireWrittenForThisView` checks the view metadata against it.
- **Rename:** keeps both the directory and the UUID.
- **Afterwards:** `openhouse.*` properties cannot be rewritten.

So for every OpenHouse-allocated entity the directory UUID equals the metadata UUID. That metadata UUID is what the metadata-based paths (table load, commit, ACL routes) already decide on, and what `grantRole` keys grants on.

**Consequence.** The fix makes the three Iceberg REST decisions agree with the decisions the same entity already gets elsewhere. It opens no new way to choose a UUID.

**Can a CREATE_TABLE holder register under another entity's UUID?** Not for one that has a row:

- A live, soft-deleted or view row of the same database whose directory is named for the UUID is refused, on all three Iceberg REST routes.
- Rows in other databases are not checked. That does not matter, because OPA keys roles on `(db_id, tbl_id)`.

**Residual cases.** In each one the direction is widening, but only toward grants the same entity already gets on its metadata paths.

| Case | What happens | New with this fix? |
|---|---|---|
| A purged or hard-dropped entity's UUID | No row is left, so a register with that UUID passes the check. Grants are not revoked on drop (no `revokeRole` on the drop paths). The new entity inherits the stale table-level grants. The registrant must know the old UUID and be able to write a metadata file under `<db>/<name>-<uuid>/`. | For tables, no: the registered table's metadata UUID is the same, so its table load and commit already evaluate against those grants, and LinkedIn main's replica create (`TableUUIDGenerator`, no path check for replicas) allows the same. For views, only the Iceberg REST view load and commit decisions are new. View-level grants cannot be made through this service; `updateAclPolicies` loads a table. |
| A UUID that never existed but has grants | Grants come only from `updateAclPolicies` on an existing table, or directly in OPA. | Same as the purged case. |
| The register race | Two concurrent registers of one UUID under two names can both pass. | Same exposure as on the metadata paths. Still not noted in code (minor). |
| A directory name merely ending in `-<uuid>` | Only allocated or registered-in-allocated locations reach rows. A storage with another naming scheme yields empty (null, as before) or a UUID the entity does not carry. | That is a misattribution only under a non-OpenHouse naming scheme. Documented in `AllocatedDirectory`'s javadoc. |
| A native replica create with a live table's UUID in the same database (main allows it) | Not covered by this pull request's register check. Both entities already share the UUID on metadata paths. | No: LinkedIn main's behavior. |

### (d) The tests

- **Failing before the fix:** each new assertion compares the decided resource's UUID with the entity's metadata UUID. That comparison fails while the UUID is null, so the tests-first commit is meaningful.
  - The assertions are in `theIrcMetricsReportDecidesOnTheTablesUuid`, `theIrcViewCommitDecidesOnTheViewsUuidExceptInTheNativeReplace`, the three `aGrantOn…` tests, and `IrcViewRoutesAuthTest:252-256`.
- **The grant backend `grantOnlyOn`:** it allows only when `resource.getTableUUID()` equals the granted UUID and the privilege is listed, and it denies every database-level decision.
  - That is faithful to `OpaHandler.checkAccessDecision`, which sends `tbl_id = getTableUUID()`, so a null UUID cannot match.
  - It does not model `db_id` keying. That is harmless here: one database per test.
- **Negative cases:** each test also has a random-UUID case, which shows the grant is what allows.

### (e) Which decisions carry a UUID

The statement is accurate:

| Iceberg REST decision | UUID? | Why |
|---|---|---|
| View load | Yes | `loadReadableView` |
| View commit: its own decisions | Yes | `authorizeCommit` (read and replace), then the base read and the answer read |
| View commit: the native replace it reaches through `updateView` | No | Native `putView:280` |
| View drop | No | Native `deleteView:323` |
| Metrics report | Yes | `reportMetrics` |

The pinned decision order is `GET uuid, UPDATE uuid, GET uuid, UPDATE null, GET uuid`.

**Is the half-fixed commit worse than before?** No.

- **Before the fix:** a principal holding only a view-level grant was refused at the first decision.
- **Now:** that principal passes the read decision, so the base metadata is read (which the principal may read), and the requirements and updates are applied in memory. A stale requirement then answers 409 rather than 403. The native `putView` refuses before `commitView` (decision at `:276-280`), so nothing is written.
- **What is revealed:** only state the principal holds the read privilege for. The pin `aGrantOnTheViewAlonePassesTheIrcCommitsDecisionsAndNotTheNativeReplace` records the refusal.

## 2. The reworked clean-up pin (07c9d64b)

- **The swap.** The test takes the ultimate target of the shared `LocalStorageClient` and swaps its `fs` field for a spy whose `open` throws, inside `try`. It restores the original in `finally`, on every path, failure included.
- **Context.** `@SpringBootTest` with `PropertyOverrideContextInitializer`. That is a shared key, so no context is added.
- **Leakage.** There is no parallel test execution in this suite (no JUnit parallel configuration).
  - The FileIO beans (`FileIOConfig:41,60`, `MainApplicationConfig:160`) took the file system at context start, so they never see the spy.
  - Only callers of `getNativeClient()` during the test see it. That is `OpenHouseInternalTableOperations:1153`, the path under test.
  - So nothing leaks to later tests. The metadata file left behind is under the test's own table, and the row is removed in `@AfterEach`.

## 3. The merge (d8975dfe) and 04422776

- **Parents:** d8975dfe has both parents, `4216e630` and `67989d16`.
- **Files only the base changed:** each equals the base.
- **Files only the branch changed:** each equals the branch.
- **Files both sides changed** (`IcebergRestApiHandler`, `IcebergRestWriteHandler`, `IrcWriteRoutesAuthTest`): the merge differs from 162's version by exactly the branch's own hunks (13, 29 and 4 changed lines, equal to the branch's change). Nothing of 162 is lost.
- **04422776** removes the third `entityManager.clear()`, which 162 added in `IrcWriteRoutesAuthTest:1239-1244`. No `entityManager.clear()` remains in the tables tests.

## 4. CI on `04422776`

- All jobs passed. The Docker job on the reference line, and the two parent workflow entries, are skipped by design.
- From the `Default build, rest` and `Reference build, rest` logs:

| Test class | Passed in each `rest` job | Failed |
|---|---|---|
| `HouseTableRepositoryImplContractTest` | 33 | 0 (confirmed) |
| `HouseTablesH2RepositoryContractTest` (both stand-in runs together) | 66 | 0 |
| `RowBuiltAuthorizationResourceTest` | 8 | 0 |
| `CommitIoFailureCleanUpPinnedDefectTest` | 1 | 0 |
| `IrcWriteRoutesAuthTest` | 30 | 0 |
| `IrcViewRoutesAuthTest` | 10 | 0 |
| `IrcRegisterRouteTest` | 18 | 0 |
| `HouseTablesH2DatabaseTest` | 4 | 0 |

## 5. Earlier minors

| Earlier finding | Status | Where |
|---|---|---|
| F4: soft-deleted listing `tableId` | Closed | `HouseTablesH2StoreImpl` checks it against `ALPHA_NUM_UNDERSCORE_PATTERN_SEARCH_REGEX`; contract assertion added. It matches the real equality query. |
| F5: the Iceberg REST view drop | Closed | Named in the `RowBuiltAuthorizationResourceTest` javadoc. |
| F3: extra context | Closed | 07c9d64b. |
| F1: Iceberg REST null UUID | Closed for the three branch-only decisions | As above. |
| F6: register race | Open (can follow) | No note beside the read-then-write. |
| F7: planning ids in commit messages | Open (can follow) | Older branch commits (9e67f5c0 and others) still carry RF/FF/BC ids, and the merge subject carries `#162`. The tree is clean and the guard passed. Reword when the branch is squashed. |

**New minor (can follow).** `ViewsServiceImpl.java:332-335`: the inserted method's javadoc sits after `authorizationReference`'s existing javadoc. That javadoc is now orphaned, and `authorizationReference` (`:357`) has none.

- **Fix:** move the comment `The object resource of a captured view for authorization…` down to `authorizationReference`.
