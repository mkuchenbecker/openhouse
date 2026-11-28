# V16 (IRC directory reuse), N5 (native directory reuse, pinned), T13 (register refusal tests): result

Branch `irc/v16-directory-reuse-guard`, based on `irc-integration` at 8876a7f2 (pull request 112), with
`irc-integration` at b1dfe9b0 (pull request 113, V9d) merged in. Pull request 116 (draft, https://github.com/mkuchenbecker/openhouse/pull/116). Head
`2a2bb4278f4a55c38a0205469ca271842f16c9bb`.
Every fact is marked [ran] or [read]. Dated 2026-11-23. Written by the delegated agent.

## Bottom line
- The data-loss path is real on both APIs [ran]. A second table can be created in the directory of a live table
  that was renamed away; dropping the second table answers 204, deletes every file in the directory, and the first
  table then answers 500 on every read ("Failed to open input stream for file ...metadata.json").
- Over IRC it had three ways in, not one; all three are closed, with tests that failed first [ran].
- Natively it has four ways in; none is changed; five tests pin them [ran]. A fix is proposed below for the owner.
- The four register refusals work as written; four tests added, no code change [ran].

## The sequence
`db.a` is created (directory `<root>/db/a-<uuid>`), given a snapshot, and renamed to `db.b`. The rename changes the
house-table row's key; the directory keeps its name and the row keeps its UUID. A create of `db.a` that ends up with
that UUID is a second table in that directory. The drop deletes the row, then the directory
(`OpenHouseInternalCatalog.dropTable` -> `deletePrefix`) [read], [ran].

## V16: IRC entry points, before the fix [ran in a scratch probe, not kept; and by the red tests]
| Entry point | Before | Kept test |
| --- | --- | --- |
| Completion of a staged create, naming `db.b`'s directory as its location, no data | 200, table in `db.b`'s directory; drop 204 left 0 files; `db.b` load 500 | `aCompletionNamingALiveTablesDirectoryIsRefusedAndChangesNothing` |
| The same with data (snapshot written in that directory) | 200, same destruction | `aCompletionWithDataNamingALiveTablesDirectoryIsRefusedAndChangesNothing` |
| Completion naming its own staged directory, snapshot's manifest list in `db.b`'s | 200, and the table was created in `db.b`'s directory, not the one named; same destruction | `aCompletionWhoseSnapshotLiesInALiveTablesDirectoryIsRefusedAndChangesNothing` |
| Completion naming a soft-deleted table's directory | 200 | `aCompletionNamingASoftDeletedTablesDirectoryIsRefusedAndChangesNothing` |
| Plain create whose properties carry `openhouse.tableUUID` (with `openhouse.tableLocation`, or with `openhouse.isTableReplicated=true`) | 200 in `db.b`'s directory; same destruction | `aCreateCarryingALiveTablesUuidIsCreatedInADirectoryOfItsOwn` |
| Staged create with those properties | 200, staged location is `db.b`'s directory (writes nothing; a client then completes there) | `aStagedCreateCarryingALiveTablesUuidIsStagedInADirectoryOfItsOwn` |
| CTAS through an unmodified `RESTCatalog` built `withProperties` of that kind | staged in `db.b`'s directory | `IrcRestCatalogDirectoryReuseTest.aCtasCarryingALiveTablesUuidIs...` |
| Plain or staged create naming the directory as `location` | made elsewhere (D30) | `aCreateNamingALiveTablesLocationIsCreatedInADirectoryOfItsOwn` (passed first time) |
| CTAS through `RESTCatalog` `withLocation` | made elsewhere | `IrcRestCatalogDirectoryReuseTest.aCtasNamingALiveTablesLocationIs...` (passed first time) |
| Completion by a principal without the create privilege | 403 (native), but only because the native create refused it later | `IrcWriteRoutesAuthTest.aDeniedCompletionNamingALiveTablesDirectoryIs403AndDoesNotSayWhoseItIs` |

- Why the completion was open [read]: the server stores nothing for a staged create, so the location a completion
  carries is the client's word; `tableUUID()` checked only that it is a location the server would allocate for that
  name. A client need not have staged anything.
- Why the snapshot variant exists [read]: the native create-with-data takes the UUID from the directory of the
  first snapshot's manifest list (`TableUUIDGenerator.extractUUIDFromRequestBody`), whatever location is named.
- Why the property variant exists [read]: an IRC create hands its properties to the native create, which reads
  `openhouse.tableUUID` (`TableUUIDGenerator.generateUUID`).
- Red before the fix [ran]: 8 of the 10 new IRC tests failed (36 run with the register, native and auth classes).

## V16: what changed on IRC (one class, `IcebergRestWriteHandler`)
- The commit that completes a staged create, when it names a location, is refused with 400 `BadRequestException`
  (the spec lists 400 for the commit route [read, fork's open-api yaml]) and changes no row and no file [ran] when:
  - a live or soft-deleted house-table row points into that directory or carries its UUID:
    `Cannot create table db.a: that directory belongs to db.b`, or
    `...belongs to the soft-deleted table db.a, which can be restored`. This is register's check
    (`refuseADirectoryInUse`), called from both places; register's messages are byte-equal to before [ran, register
    tests assert them].
  - a snapshot's manifest list does not lie in that directory:
    `Cannot create table db.a: the manifest list of snapshot N does not lie in the table's directory`. Also
    register's check, extracted and shared (`requireSnapshotsIn`).
- Order on the completion: name taken 409 -> rewritten properties, format version, location shape 400 -> manifest
  lists 400 -> create privilege 403 (native message) -> rows 400 -> the native create. The privilege is asked before
  the rows are read, as on register, so a denied principal is not told whose directory it is [ran].
- A create and a staged create no longer hand `openhouse.tableUUID` to the native create: the table is created, or
  staged, in a directory of its own under a new UUID, as for a named location (D30) [ran].
- The completion a staged create asks for (its own fresh directory) is unchanged [ran]; after each case the drop of
  the second table leaves the first one's row, files and load as they were [ran].
- Native classes changed: none. `git diff 8876a7f2 -- services/tables/src/main` touches one file.

## N5: the native API (pinned, not changed)
- Reachable, four ways, each a 201 [ran, `NativeCreateDirectoryReusePinnedDefectTest`, 4 tests, native HTTP only]:
  1. create with `openhouse.tableUUID` + `openhouse.databaseId` + `openhouse.tableId` + `openhouse.tableLocation`
     naming any existing file of that directory (the renamed table's own metadata file);
  2. the same UUID with `openhouse.isTableReplicated=true` (+ `last-updated-ms`): no path check;
  3. a `REPLICA_TABLE` create with the UUID: no path check;
  4. create with data (snapshots PUT on the initial version) whose first snapshot's manifest list lies there.
- After each [ran]: both rows carry one UUID and point into one directory; drop of the second 204; no file left in
  the directory; GET of the first table 500 "Failed to open input stream for file".
- Who can do it [ran, `CommitPathAuthorizationTest.pinnedDefectCreateInARenamedTablesDirectoryNeedsOnly...`]: with
  every decision on a table denied, the create still answers 201; the only decision asked is `CREATE_TABLE` on the
  database. No privilege on the table whose directory is taken.
- What the service checks [read]: `TableUUIDGenerator.validatePathOfProvidedRequest` wants `openhouse.databaseId`
  and `openhouse.tableId` (taken from the properties, never compared with the request's ids) and, unless the type is
  `REPLICA_TABLE` or `openhouse.isTableReplicated=true`, `Storage.isPathValid`: the path starts with
  `<root>/<db>/<table>-<uuid>` and exists. `OpenHouseTablesApiValidator` wants a parseable UUID for a replica and a
  `last-updated-ms` not in the future for a replicated table. Nothing reads house-table rows.
- Proposed smallest fix, for the owner (not made): in `TableUUIDGenerator`, the one place a create accepts a UUID
  from the request (property or manifest-list path), refuse with the existing 400
  (`RequestValidationFailureException`) when a live or soft-deleted house-table row of that database already
  carries that UUID. About 20 lines and one injected repository.
  What it would change natively: (a) the four requests above answer 400 instead of 201 when the UUID is in use in
  that database; (b) every create that names a UUID or carries data (each native CTAS, each replica create) makes
  one more House Tables listing of the database's rows and of its soft-deleted rows (House Tables has no lookup by
  UUID [read]); (c) re-creating a replica under its old name while a renamed table with that UUID still exists in
  the same database would be refused; (d) nothing else: a create with a server-drawn UUID, updates, stored values
  and other messages are untouched. Alternative, larger: make the drop refuse to purge a directory another row
  points into.

## T13: the four register refusals (tests only; all passed first time, nothing fixed) [ran]
| Refusal | Test in `IrcRegisterRouteTest` | Cases |
| --- | --- | --- |
| Partition-spec history | `metadataWithAPartitionSpecHistoryIsRefused` | two specs, second the default; a second spec never made default |
| Field ids a create would renumber | `metadataWithFieldIdsACreateWouldRenumberIsRefused` | columns numbered from 11; partition field id 1005 |
| Statistics files | `metadataWithStatisticsFilesIsRefused` | table statistics; partition statistics |
| Replica table type | `metadataOfAReplicaTableIsRefused` | `openhouse.tableType=REPLICA_TABLE` |
Each expects 400 `BadRequestException` with the refusal's message, no row or file changed, and then registers the
untouched file (200).

## Counts
All [ran], one Gradle build at a time, each on the head 2a2bb427 and, before the merge of pull request 113, on
5246b357 with the same counts.
| Suite | Default build | `-PicebergFork` | Before [read, note 46] |
| --- | --- | --- | --- |
| `:services:tables:test` | 1579, 0 failed | 1579, 0 failed | 1560 |
| `tables-test-fixtures_2.12:test` (Iceberg 1.2 runtime) | 41, 0 failed | not in that build | 41 |
| `tables-test-fixtures-iceberg-1.5_2.12:test` | 41, 0 failed | 41, 0 failed | 41 |
| Spark 3.5 itest `catalogTest` | 269, 0 failed, 1 skipped | not run there | 269, 1 skipped |
| Spark 4.2 itest `test` | not in that build | 40, 0 failed | 40 |
| `restCompatKitTest` | not in that build | 160: 39 passed, 98 failed, 23 skipped = list | 39, 98, 23 |
| `spotlessCheck` | passes | passes | |

- 1579 = 1560 + 19 new tests: `IrcDirectoryReuseRoutesTest` 7, `IrcRestCatalogDirectoryReuseTest` 2,
  `IrcWriteRoutesAuthTest` +1, `NativeCreateDirectoryReusePinnedDefectTest` 4, `CommitPathAuthorizationTest` +1,
  `IrcRegisterRouteTest` +4.
- `expected-outcomes.txt` is unchanged; no kit outcome moved.
- On the head the fork fixture suite was first reported up to date, then restored from the build cache; it was run
  a third time with `--no-build-cache` after `cleanTest`, and that run is the count above.
- The stored-metadata golden tests are in `:services:tables:test` and passed unchanged; no golden file changed.
- Red run before the fix [ran]: 36 tests of the five touched classes, 8 failed (the 8 named in the V16 table that
  did not pass first time).
- Logs: `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/v16/` (`probe1.log` is the scratch
  probe, `red1.log` the red run, `h-*.log` the runs on the head).

## Not run
Spark 3.5 `statementTest` and `test`; Spark 3.1 suites; Spark 4.2 `extensionsTest`; `:libs:datalayout`, apps;
the real House Tables service (MySQL) and its soft-deleted paging; storages other than local (HDFS, S3, ADLS: the
endpoint stripping in the shared check is [read]); the Iceberg 1.2 runtime needs no guard because it serves no IRC
write route [read, 406]; pull request CI.

## Deviations and decisions
1. The IRC proof of what the drop destroys ran in a scratch probe that is not committed: once fixed it cannot be a
   passing IRC test. The same drop evidence is kept permanently by the pinned native tests.
2. V16 grew beyond the completion: the create and staged-create property path and the snapshot path were found by
   the neighbour tests and fixed, because the brief says a create can never be given another table's directory.
3. `openhouse.tableUUID` on an IRC create is ignored, not refused (D30's choice for a location). Before, such a
   create with incomplete companion properties got the native 400; now it is created under a new UUID.
4. The completion asks for the create privilege before the row scan (register's order). A denied principal whose
   completion also breaks a native validation rule now gets 403 where it got that rule's 400. The privilege is then
   asked a second time by the native create.
5. N5 pins five tests, not one: the four requests and who may send them.
6. `irc-integration` moved to b1dfe9b0 (pull request 113) during the runs; it was merged in (no conflict, no
   tables-service source) and every suite was run again on the merge.
7. One Gradle command (`./gradlew projects`, a listing) ran without the two required flags; it attached to a
   daemon already running and started no process.

## Findings outside the items
- [read] `openhouse.isTableReplicated=true` in an IRC create's properties is still handed to the native create,
  which stores it; it no longer places the table. Whether IRC should accept it is a separate question.
- [read] `openhouse.replicaTableLocationId` is filtered out before the location is allocated by the base
  preserved-key checker, so it does not name a directory in this build (note 46 says it does).
- [read] The shared check reads all of a database's house-table rows on each completion that names a location
  (every CTAS over IRC). A House Tables lookup by UUID would make it one row.

## Commits
- 44f95bbc test: pin the native create that takes a live table's directory (N5).
- a88908b4 test: the four register refusals that had no test (T13).
- 8aabbbdb test: an IRC create cannot be given another table's directory (V16, red: 8 of 10 fail).
- 066f4ed8 fix: an IRC create is never put in a directory another table uses (V16).
- 5246b357 test: pin who can make the native create that takes a table's directory (N5).
- 2a2bb427 merge of irc-integration at pull request 113, so the suites ran on the tree to be merged.
