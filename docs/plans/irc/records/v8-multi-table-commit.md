# V8: the IRC multi-table commit route (`POST /v1/transactions/commit`): record

Bottom line: the tables service cannot commit two tables atomically, because House Tables only offers a
compare-and-set of one row per call. So the route is served for a transaction that changes one table (it is
then that table's ordinary commit, answered 204), and a transaction with more than one table change is refused
whole with 400 before anything is read or written. The route is listed in the advertised endpoints. Nothing native
changed. A design for real atomicity is at the end, as a proposal and not as code.

Branch `irc/v8-multi-table-commit`, base `92c5e117` (`irc-integration`), head
`e151114e758808c8299e525884aa6107d222ab3a`, draft pull request 120. Every fact is marked [ran] or [read].
Written by the delegated agent for V8, 2026-11-23.

## 1. What atomicity House Tables can give

- [read] The client the tables service uses is `HouseTableRepositoryImpl` (internalcatalog). Its write is `save`,
  one `PUT /hts/tables` with one entity, one attempt, no retry. `saveAll` throws
  `UnsupportedOperationException("saveAll is not supported.")`. The other writes are `rename`, `deleteById`,
  `restoreTable`, `purgeSoftDeletedTables`, `saveView`, `deleteViewById`: each acts on one key.
- [read] The House Tables service (`services/housetables`): `UserHouseTablesController.putUserTable` takes one
  `UserTable` and calls `UserTablesServiceImpl.putUserTable` -> `persistTypedEntity`: read the row at the key,
  refuse another entity type, map the caller's `tableVersion` to the row version (`UserTableVersionMapper`: the
  stored `metadataLocation` must equal the caller's `tableVersion`, else 409), then `htsJdbcRepository.save`, which
  JPA guards with the row's `@Version` column. That method is not `@Transactional`. No controller method, service
  method or repository method writes more than one `user_table_row` in one call.
- [read] Storage: Spring Data JPA over JDBC (`UserTableHtsJdbcRepository`), MySQL InnoDB in production and H2 in
  tests. DDL (`ddl/0000__baseline.sql`, `0001__...`): `user_table_row` has primary key `(database_id, table_id)`,
  a `version BIGINT` and `metadata_location`; engine InnoDB. So the database itself can change several rows in one
  transaction; the service already opens database transactions in two places (`deleteUserTable` and
  `restoreUserTable`, which touch `user_table_row` and `soft_deleted_user_table_row` together).
- Answer: per-row compare-and-set only. A multi-row atomic write needs (a) a new House Tables endpoint taking
  several rows, each with its expected current metadata location, (b) a `@Transactional` service method that runs
  the existing per-row check and versioned save for each row, in a fixed key order, and rolls back on the first
  conflict, (c) the regenerated client (`client/hts` is generated from the service's OpenAPI spec) and a new
  `HouseTableRepository` method, and (d) the same semantics in the two in-memory stand-ins
  (`HouseTablesH2Repository` in the service tests and in `tables-test-fixtures`). No DDL change.
- [read] Iceberg's own reference server for this route (`RESTCatalogAdapter.commitTransaction`, test code, on both
  lines) validates every change and then commits table by table; its comment says it "does not guarantee true
  transactional atomicity, which is left to the implementation details of a REST server".

## 2. What the commit core does per table besides the row write

In order, for one table [read, `TableCommitService`, `TableCommitCore`, `OpenHouseInternalRepositoryImpl.save`,
`OpenHouseInternalTableOperations.doCommit`, `TableAuditAspect`]:

1. Native request validation, lock checks, the write privilege and the system-only lock check, the capability
   gate, the read-bridge strip. Nothing is written yet.
2. `doCommit`: the base-version check, the property rules, the pre-commit checks, the retry cache; then the new
   metadata file is written to storage and the metadata cache is seeded. This happens before the row write.
3. `houseTableRepository.save(row)`: the compare-and-set. When it is refused, the metadata file stays where it was
   written (the catch maps the refusal to `CommitFailedException` and deletes nothing), exactly as natively.
4. After a successful save: the post-commit operations (`PostCommitOperationRunner`, asynchronous; an extension
   point for which the repository ships an abstract commit-statistics publisher and the open-source deployment
   registers nothing), the commit metrics, and the table audit event (`SUCCESS`). Readers can load the new version
   from the moment of step 3.

Not undoable once step 3 has happened: the row version (a later writer may already have built on it), what
readers and replication have seen, the audit event, the post-commit publications. The metadata file of step 2 is
an orphan if step 3 fails, which is harmless and already happens on every lost race.

## 3. Who sends the route

- [read] Iceberg 1.5.2 (`li/openhouse-1.5.2`, the default build's line) and the fork's 1.12 (`a88f556c`): the only
  callers in main code are `RESTCatalog.commitTransaction(List<TableCommit>)` and its varargs form, which call
  `RESTSessionCatalog.commitTransaction`. No class in `spark/`, `flink/`, `kafka-connect/` or elsewhere calls
  them. The only test callers are three tests of `TestRESTCatalog`, which is not the compatibility kit.
- [read] A table's own commit, whatever started it (Spark's INSERT, MERGE, ALTER, CTAS, a `Transaction` on one
  table), goes through `RESTTableOperations.commit`, which posts to `/v1/{prefix}/namespaces/{ns}/tables/{t}`.
- [ran] Spark 4.2 with Iceberg 1.12: the access log of the service in `SparkIrcEndpointsTest` holds no request to
  `/transactions/commit` after CREATE, CTAS, two INSERTs, DELETE, ALTER SET TBLPROPERTIES and ALTER ADD COLUMN,
  nor from any request the JVM's other test classes had sent by then (the new test asserts the list is empty
  before its own two calls).
- [read] Spark 3.5 with Iceberg 1.5.2: same code path; not measured with an access log (the 3.5 itest has none).
- [read] The kit's two suites (`CatalogTests`, `ViewCatalogTests` through `RESTCompatibilityKit*Tests`) contain no
  call of the route. [ran] Its counts and its list of expected outcomes are unchanged (see counts).
- [read] OpenHouse: nothing calls it (`grep` over the repository: only the tests added here).
- [read] `TableCommit.create(identifier, base, updated)`, the only public way Iceberg builds a transaction's
  change, derives requirements with `UpdateRequirements.forUpdateTable`: it never sends a create.

So no existing user of OpenHouse, and no Spark statement, depends on this route. It is reached only by code that
calls `RESTCatalog.commitTransaction` itself.

## 4. What was built, and why this option

Option (a) of the brief. `POST /iceberg/v1/transactions/commit`:

- One table change: the ordinary commit of that table, through `TableCommitService.commit(...)`, with the same
  requirement check against the stored table, the same native validation and rules, the same guards and audit.
  Answer 204, no body.
- More than one table change (two changes to one table included): 400 `BadRequestException`, decided on the
  request's shape alone, before any table is loaded, any privilege asked or anything written. Message:
  `Cannot commit a transaction with 2 table changes (db.a, db.b): OpenHouse commits one table per transaction,
  because it cannot commit several tables atomically. Nothing was committed. Commit each table in a transaction,
  or a commit, of its own.`
- A table that does not exist: 404 `NoSuchTableException` (the only answer the spec lists for it on this route),
  also for a view's name, for a namespace of several levels, and for a change that asserts the table does not
  exist. The route never creates a table.
- Malformed: 400 (no body, no `table-changes`, an empty list, a change without identifier, an unknown update or
  requirement), as the spec requires.

Why not option (b), commit table by table with compensation: the compensation is itself a compare-and-set on the
first table's row, so a writer that commits to that table in between makes it fail, and by then readers, the
audit and the post-commit publications have seen the first commit; and if the service process dies between two
tables nothing runs the compensation at all, since no record of the transaction exists outside the process. In
both cases some tables are changed and others are not, which the caller is either told wrongly or not told.

Why 400 and not another status: the spec lists 400, 401, 403, 404, 409, 419, 500, 502, 503, 504 and 5XX for this
route. 409 tells the client to retry, and no retry can succeed. 500, 502 and 504 are read by the 1.12 client's
commit error handler as `CommitStateUnknownException` [read, `ErrorHandlers.CommitErrorHandler`], which is the
opposite of what happened: nothing was committed, and the state is known. 400 is definite, is not retried, and is
what this server already answers for a request it understands and does not honour (D32).

Why the route is listed in the config's `endpoints`: the 1.12 client checks the list first
(`RESTSessionCatalog.commitTransaction` calls `Endpoint.check(endpoints, V1_COMMIT_TRANSACTION)`) and, when the
route is missing, throws `UnsupportedOperationException("Server does not support endpoint: ...")` without sending
anything [read; and ran before this change: `SparkIrcEndpointsTest.unservedRoutesAreRefusedOnTheClient` asserted
exactly that]. Nothing catches it: there is no fallback to per-table commits in Iceberg, Spark or Flink [read].
So not listing the route protects nobody and makes the one-table transaction impossible; listing it makes a
transaction of several tables fail at the server with the reason, where it failed locally before [ran]. The
1.5.2 client has no endpoint list and always sends the request [read; ran: before the change it got the 406 as
`RESTException: Unable to process: This server does not support POST /v1/transactions/commit yet`].

## 5. Decisions made (for the decision record)

1. Option (a): a transaction of several table changes is refused whole; no compensation scheme.
2. The refusal is 400 `BadRequestException`, not 406 (not listed for the route), not 409 (retry), not 5xx (read
   as unknown commit state).
3. The refusal is decided on the shape alone, before reading, authorizing or writing. A principal with no rights,
   and tables that do not exist, get the same 400; it reveals nothing.
4. Two changes to the same table in one transaction count as several and are refused (the spec does not say
   against which state the second change's requirements would be checked).
5. The route does not create tables: `assert-create` on a missing table is 404, on an existing one 409 as on the
   ordinary route. (The ordinary route completes a staged create with it.)
6. The route is listed in `endpoints`, on the evidence in section 4.
7. The route carries the `policies` capability annotation like the ordinary commit, because a policy patch in a
   transaction's change is applied as in a commit. The advertised capability list does not change.
8. The `Idempotency-Key` header the 1.12 spec names is not honoured, as on every other route.
9. The response is 204 with no body, and the table is not reloaded after the commit (the ordinary route reloads
   it to build its response).
10. Registered only where the write controller is (Iceberg 1.4 and later). On the Iceberg 1.2 runtime the route
    answers 406 as before.

## 6. Before and after

| | Before | After |
| --- | --- | --- |
| `POST /iceberg/v1/transactions/commit`, one table change | 406 | 204, the table's ordinary commit |
| same, several table changes | 406 | 400, nothing read or written |
| same, unknown table or a view's name | 406 | 404 `NoSuchTableException` |
| Config `endpoints` (Iceberg 1.5.2 and 1.12 runtimes) | 18 routes | 19, with `POST /v1/{prefix}/transactions/commit` |
| Iceberg 1.12 `RESTCatalog.commitTransaction` | refused locally, nothing sent | sent; one table commits, several get `BadRequestException` |
| Iceberg 1.5.2 `RESTCatalog.commitTransaction` | `RESTException` (406) | one table commits, several get `BadRequestException` |
| Iceberg 1.2 runtime (fixture) | 406 | 406 |
| Native API, ordinary IRC commit, Spark SQL | | unchanged |

## 7. Code

- `IcebergRestWriteController.commitTransaction`: the mapping.
- `IcebergRestWriteHandler.commitTransaction`: the refusal and the single-table path. `commitTable`'s body from
  the requirement check on moved, unchanged, into `commitOn(identifier, base, request, principal)`, which both
  routes call; it returns whether anything was committed.
- `IcebergRestCatalogController`: the route's line in the list of spec routes answered 406 when unserved moved
  to the write routes' group (it still applies on Iceberg 1.2).

## 8. Tests (all at the HTTP boundary or through a real client)

New: `IrcTransactionRouteTest` (10), `IrcRestCatalogTransactionTest` (4, a real `RESTCatalog`: 1.5.2 on the default
build, 1.12 under the switch), two in `IrcWriteRoutesAuthTest`, one in the Spark 4.2 `SparkIrcEndpointsTest`.
Changed because the route is now served: `IrcConfigEndpointsTest` (the list), `IrcReadRoutesTest` and
`IrcWriteRoutesTest` (the route left their lists of unserved routes), `IcebergRestRoutesBoundaryTest` (19
endpoints; 400 on 1.5, 406 on 1.2), `SparkIrcEndpointsTest.unservedRoutesAreRefusedOnTheClient` (no longer
includes the transaction). No test was skipped, disabled, deleted or loosened.

Red before the code [ran]: 16 of 16 new service tests and `theEndpointsAreTheServedSpecRoutesInTheSpecsFormat`
(17 failed of 28 run); 2 of 6 boundary tests on the 1.5 fixture; the new Spark 4.2 test (see counts).

## 9. Counts

All [ran] on head `e151114e758808c8299e525884aa6107d222ab3a`, JDK 17, one Gradle build at a time; each test
task executed (none restored from the build cache; the fixtures suites were re-executed with `--no-build-cache`
after one invocation restored them).

| Suite | Default build | Under `-PicebergFork` |
| --- | --- | --- |
| `:services:tables:test` | 1595 tests, 0 failed, 0 skipped | 1595 tests, 0 failed, 0 skipped |
| `:tables-test-fixtures:tables-test-fixtures_2.12:test` (Iceberg 1.2 runtime) | 41, 0 failed | not in that build |
| `:tables-test-fixtures:tables-test-fixtures-iceberg-1.5_2.12:test` | 41, 0 failed | 41, 0 failed |
| Spark 3.5 itest `catalogTest` | 270, 0 failed, 1 skipped | not run there |
| `restCompatKitTest` | not in that build | 160: 39 passed, 98 failed, 23 skipped; the task passes |
| Spark 4.2 itest `test` | not in that build | 41, 0 failed |
| Spark 4.2 itest `smokeTest` | not in that build | 65, 0 failed |
| `spotlessCheck` (whole build) | passed | passed |

Before, on base `92c5e117`: not re-run. By arithmetic the service suite had 1579 tests (16 added) and the Spark
4.2 `test` task 40 (1 added); the other suites gained and lost no test. Kit before: 39 / 98 / 23 [read,
`18-state.md`]; `expected-outcomes.txt` is unchanged (98 FAILED and 23 SKIPPED lines) and the task compares against
it in both directions, so no kit test moved [ran].

Red runs before the code [ran]: service classes touched, 28 tests, 17 failed (10 of 10 `IrcTransactionRouteTest`,
4 of 4 `IrcRestCatalogTransactionTest`, the 2 new auth tests, the config list); 1.5 fixture, 41 tests, 2 failed
(both in `IcebergRestRoutesBoundaryTest`), 1.2 fixture boundary test 6 of 6 passed; Spark 4.2
`SparkIrcEndpointsTest`, 10 tests, 1 failed (the new one, with the client's local `UnsupportedOperationException`).

CI on pull request 120 at the same head [read from the check runs]: `build-run-tests` success,
`iceberg-fork-build` success.

## 10. A design for real atomicity (proposal, for a later item; not built)

Smallest design that gives all-or-nothing across tables:

1. House Tables: one new endpoint, for example `PUT /hts/tables/batch`, body a list of the existing `UserTable`
   entity (each with `tableVersion`, the metadata location the writer built on, and the new `metadataLocation`).
   One `@Transactional` service method: sort the rows by upper-cased key (a fixed lock order, so two batches
   cannot deadlock), and for each row run today's `persistTypedEntity` steps (read, type check, version mapping,
   versioned save), then flush. Any conflict throws, the database transaction rolls back, and the answer is 409
   naming the key. Limits: existing TABLE rows only (no create, no view), no duplicate key, a maximum size. No DDL
   change: `user_table_row` is InnoDB with a version column. A lock timeout or deadlock must be answered as a
   definite failure (rolled back), not as 5xx.
2. Client: regenerate `client/hts`; add `HouseTableRepository.saveAll` (today it throws) over the new endpoint, one
   attempt, never retried; a timeout is "unknown" for the whole batch. Both in-memory stand-ins get the same
   all-or-nothing behaviour, with a test that a conflict on the last row leaves the first unchanged.
3. Commit core, the hard part: split a commit into prepare and publish. Prepare runs everything up to and
   including the metadata file write for every table (rules, privileges, lock, capability gate, base-version
   check, property rules) and yields the row it would save instead of saving it; the staged-create branch of
   `doCommit` already does "write the file, skip the row". Then one `saveAll`. Then, per table, what today follows
   the save: cache, post-commit operations, metrics, audit `SUCCESS`. A failed batch leaves orphan metadata files
   and nothing else, as a lost race does today, and audits every table as `FAILED`.
4. The IRC route then validates all changes, prepares all, publishes once: 204, or 409 with nothing changed, or
   500 `CommitStateUnknownException` when the batch call's outcome is unknown (the database transaction keeps even
   that case all-or-nothing).

Cost: House Tables about 300 to 400 lines with tests and a deployment before the tables service (an older House
Tables answers 404, so the tables service needs a switch and must keep refusing until it is on); internalcatalog
about 150 lines in the most delicate method of the commit path, behind a mode only this route uses, plus the two
stand-ins; the route about 100 lines; tests for concurrent writers and for a crash between prepare and publish.
It is a new House Tables API, so a one-way door for the owner (like D24) if LinkedIn runs another House Tables
implementation. Not solved by it: readers still load tables one by one, so a reader can see one table's new
version and another's old one around the commit instant; the spec asks for an atomic commit, not for
cross-table snapshot isolation. Given that no engine sends the route (section 3), the owner may prefer to leave
it as built.

## 11. Not run, and deviations

Not run: the suites on the base commit (before counts are arithmetic or read); Spark 3.5 `statementTest` and
`test`; Spark 4.2 `extensionsTest`; the delta-harness; the House Tables service's own tests and a real House Tables
(MySQL) behind the route, so section 1 is read, not run; a Spark 3.5 access log for section 3; any client other
than Iceberg's Java client (PyIceberg, Trino and others were not read).

Deviations from the brief: none in what was built. Three existing tests were changed in what they expect, because
they pinned the route as unserved (the two 406 lists and the old Spark 4.2 assertion that the client refuses the
transaction locally); that is the behaviour this item reverses, and nothing else in them changed. During the
work the production change was once in the working tree while the "red" service run executed (Spotless restored
the handler file from its own stale output after I had set it aside); the controller mapping was absent in that
run, so the route was unserved and the run is a valid red run, and the commits were then made strictly tests
first with the production files set aside as a patch.

One hazard for other agents, observed [ran]: after `spotlessApply` has reformatted a file, reverting that file by
hand and running `spotlessApply` again can copy the stale formatted version back over the reverted file. Check
`git status` after every `spotlessApply`.

## 12. Proposed merge commit message

```
Merge V8: the IRC multi-table commit route commits one table and refuses several tables whole (#120)

Why: the Iceberg REST Catalog route POST /v1/transactions/commit must commit all its table
changes or none. House Tables only offers a compare-and-set of one row per call, so the
tables service cannot commit two tables atomically, and committing table by table with an
undo is not safe under a concurrent writer or a crash between two tables. The route is
therefore served for one table, as that table's ordinary commit, and a transaction with
more than one table change is refused with 400 before anything is read or written.

Leaves true: the native API, the table's own IRC commit route and Spark SQL are unchanged.
A one-table transaction goes through TableCommitService.commit with every OpenHouse rule
and stores what the ordinary commit stores. No request can leave some tables changed and
others not. The route never creates a table. The config lists the route (19 endpoints) on
Iceberg 1.5.2 and 1.12; on the Iceberg 1.2 runtime it still answers 406.

Evidence: :services:tables:test 1595 tests, 0 failed, on the default build and under
-PicebergFork; both test-fixture suites 41, 0 failed (the 1.5 one on both builds); Spark
3.5 catalogTest 270, 0 failed, 1 skipped; Spark 4.2 test 41 and smokeTest 65, 0 failed;
the compatibility kit unchanged at 39 passed, 98 failed, 23 skipped against an unchanged
list; spotlessCheck on both builds; both CI jobs green on e151114e. Each new test failed
before the code.

Known gaps: no atomic multi-table commit. It needs a new House Tables endpoint that
updates several rows in one database transaction and a commit core split into prepare and
publish; the design is in the decision record. House Tables' behaviour was read, not run
against a real House Tables. No engine sends the route.

Commits:
515133cf test: the route's contract at the HTTP boundary and through a real RESTCatalog, red first
ba7f8ed8 test: the four tests that pinned the route as unserved now expect it listed and served
dd7c86ff test: the Iceberg 1.12 client on Spark 4.2 reaches the server, and Spark sends no transaction
e151114e feat: the route; one table is the ordinary commit, several tables are refused whole

Decision record: docs/plans/irc/52-v8-multi-table-commit-result.md on branch
claude/openhouse-rest-v3-planning-g42c0h of mkuchenbecker/openhouse.
```
