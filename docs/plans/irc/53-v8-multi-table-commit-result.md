# V8 result: the IRC multi-table commit route

Bottom line: the route `POST /iceberg/v1/transactions/commit` is now served, and it never half-applies. A
transaction that changes one table is that table's ordinary commit and answers 204. A transaction that changes
several tables is refused whole with 400 before anything is read or written. Real atomicity across tables is not
possible today because House Tables only offers a compare-and-set of one row per call. No engine sends this route, so
nothing a user does today is affected. Pull request 120 in mkuchenbecker/openhouse is merged into `irc-integration`
as merge commit `a217cce94f9592c088a4193dad87a06969c0fd4c`. A design for real atomicity is below, and whether to
build it is put to Mike (D40). The decision is D39 in `51-decisions-continued-4.md`. Written 2026-11-23 by the
orchestrator's recorder from the delegated agent's report. Facts marked [ran] were run, [read] were read in code.

## The three investigation answers

1. What atomicity House Tables can give: one row per call. [read] The client's only write is one `PUT /hts/tables`
   with one entity and one attempt, and `saveAll` throws "not supported". The service checks the caller's expected
   metadata location against the stored one (409 if it differs) and saves with a version column. No method writes
   more than one table row. The database (InnoDB, primary key database and table) could change several rows in one
   transaction, but no endpoint offers it. Iceberg's own reference server for this route also commits table by table
   and says it does not guarantee atomicity.
2. What the commit core does besides the row write: it validates, checks locks and privileges, writes the new
   metadata file, then saves the row, and only after that runs the cache, post-commit work, metrics and the audit
   event. [read] Once the row is saved it cannot be undone: a later writer may have built on it, and readers, the audit
   and post-commit publications have seen it. A failed save leaves an orphan metadata file, which already happens on
   every lost race.
3. Who sends the route: nobody that matters. [read] In Iceberg 1.5.2 and the fork's 1.12 only
   `RESTCatalog.commitTransaction` calls it, and no engine module calls that. A one-table change from Spark, whatever
   statement starts it, uses the table's own commit route. [ran] A Spark 4.2 access log after create, CTAS, inserts,
   delete and two alters holds no request to this route. The compatibility kit has no test for it. Spark 3.5 was not
   measured with an access log.

## Before and after

| | Before | After |
| --- | --- | --- |
| Route, one table change | 406 | 204, the table's ordinary commit |
| Route, several table changes | 406 | 400, nothing read or written |
| Route, unknown table or a view's name | 406 | 404 |
| Config endpoint list | 18 routes | 19, with `POST /v1/{prefix}/transactions/commit` |
| Iceberg 1.12 `RESTCatalog.commitTransaction` | refused locally, nothing sent | sent; one table commits, several get `BadRequestException` |
| Iceberg 1.5.2 `RESTCatalog.commitTransaction` | `RESTException` from the 406 | same as 1.12 |
| Iceberg 1.2 runtime | 406 | 406 |
| Native API, ordinary IRC commit, Spark SQL | | unchanged |

## Why commit table by table with an undo is unsafe

The undo is itself a compare-and-set on the first table's row. A writer that commits to that table in between makes
the undo fail, and by then readers, the audit and the post-commit publications have seen the first commit. If the
service dies between two tables, nothing runs the undo at all, because no record of the transaction exists outside
the process. In both cases some tables are changed and others are not, and the caller is told wrongly or not told.

## Why 400, and why the route is listed

- 409 tells the client to retry, and no retry can succeed. 500, 502 and 504 are read by the 1.12 client as commit
  state unknown, which is the opposite of the truth: nothing was committed. 400 is definite and is not retried.
- The 1.12 client refuses locally when the route is not in the endpoint list, and nothing falls back to per-table
  commits. So not listing it protects nobody and makes a one-table transaction impossible. The 1.5.2 client has no
  list and always sends the request.

## What was built

The controller maps the route. The write handler refuses several changes on the request's shape alone and otherwise
runs the ordinary commit through a shared method, `commitOn`, which both routes call. The route's line moved from
the unserved list to the write routes' group. Nothing native changed. On the Iceberg 1.2 runtime the write controller
is not registered, so the route stays 406.

## Tests and counts

All [ran] on head `e151114e`, JDK 17, one build at a time, each test task executed.

| Suite | Default build | Under `-PicebergFork` |
| --- | --- | --- |
| `:services:tables:test` | 1595, 0 failed, 0 skipped | 1595, 0 failed, 0 skipped |
| fixtures, Iceberg 1.2 runtime | 41, 0 failed | not in that build |
| fixtures, Iceberg 1.5 | 41, 0 failed | 41, 0 failed |
| Spark 3.5 `catalogTest` | 270, 0 failed, 1 skipped | not run there |
| Spark 4.2 `test` and `smokeTest` | not in that build | 41 and 65, 0 failed |
| Compatibility kit | not in that build | 160: 39 passed, 98 failed, 23 skipped, list unchanged |
| `spotlessCheck` | passed | passed |

- New tests: `IrcTransactionRouteTest` (10), `IrcRestCatalogTransactionTest` (4, a real `RESTCatalog`), two in
  `IrcWriteRoutesAuthTest`, one in the Spark 4.2 `SparkIrcEndpointsTest`. Each failed before the code (17 of 28 run
  red in the service; 2 of 41 on the 1.5 fixture; the Spark 4.2 test).
- Five existing tests changed what they expect because they pinned the route as unserved. Nothing was skipped,
  disabled, deleted or loosened.
- CI on pull request 120 at that head: `build-run-tests` and `iceberg-fork-build` both succeeded.

## The proposal for real atomicity (not built; D40)

1. House Tables: one new endpoint taking a list of the existing table entity, each with the metadata location the
   writer built on. One database transaction runs today's per-row check and versioned save for each row in a fixed
   key order, and any conflict rolls everything back with 409 naming the key. Existing table rows only, no
   duplicates, a size limit. No DDL change. A lock timeout must answer as a definite failure.
2. Client: regenerate the House Tables client and implement `saveAll` over the new endpoint, one attempt, never
   retried. Both in-memory stand-ins get the same all-or-nothing behaviour and a test for it.
3. Commit core, the hard part: split a commit into prepare (everything up to writing the metadata file, yielding the
   row it would save) and publish (one `saveAll`, then per table the cache, post-commit work, metrics and audit). A
   failed batch leaves orphan files only, and audits every table as failed.
4. The route validates all changes, prepares all, publishes once: 204, or 409 with nothing changed, or an unknown
   state answer if the batch call's outcome is unknown.

Cost: roughly 300 to 400 lines in House Tables with tests, 150 in the commit core plus the stand-ins, 100 in the
route. House Tables deploys first (an older one answers 404, so the tables service needs a switch). It is a new
House Tables API, so a one-way door. Readers would still see tables one by one around the commit instant.

## What was not run

The suites on the base commit (before counts are arithmetic: 1579 service tests, 40 on Spark 4.2); Spark 3.5
`statementTest` and `test`; Spark 4.2 `extensionsTest`; the delta-harness; House Tables' own tests and a real House
Tables behind the route; a Spark 3.5 access log; any client other than Iceberg's Java client.

## Hazard for other agents

After `spotlessApply` reformats a file, reverting the file by hand and running `spotlessApply` again can copy the
stale formatted version back. Check `git status` after every `spotlessApply`.
