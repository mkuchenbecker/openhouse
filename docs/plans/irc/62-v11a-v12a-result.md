# Result of V11a and V12a: scan planning (synchronous) and the credentials route

Bottom line: done and merged. Pull request 127 was merged into `irc-integration` on 2026-11-24 as merge commit
`91e175c4` (pull request head `37d85483`). On the Iceberg 1.12 line the service now plans a scan synchronously and
answers an empty credentials list. Both routes run the checks a table load runs. The service never sends
`scan-planning-mode`, so a default client sends no plan request. No existing route answers differently. LinkedIn's
Iceberg 1.5.2 reference line serves neither route and still answers 406 for both. Voice: observation by a delegated
agent, from its record and the merge message; the decisions below are the author's, two-way doors, and are recorded as
D44 and D45 in `56-decisions-continued-5.md`.

## What each route answers

- `POST /iceberg/v1/namespaces/{ns}/tables/{t}/plan` plans on the table the load itself returns and answers
  `completed` with every file scan task inline: the data file, its delete files (position, equality and deletion
  vectors) and the residual filter, under a random plan id. Nothing is stored.
- It honours snapshot id, branch, select, filter, case sensitivity, stats fields, and incremental start and end.
  `min-rows-requested` is accepted and ignored.
- `GET .../credentials` answers 200 with an empty storage-credentials list. No credential of any kind is returned.
  The `X-Iceberg-Access-Delegation` header is read by no code.
- Errors, both routes: the load's 403, 404, 422 and lock answers, because both call the same `TablesService.loadTable`;
  the checks run before the body is parsed.
- Errors, plan only: 400 in the specification's error shape for an unparsable body or filter, a filter that does not
  bind (missing column), an unknown snapshot id, an incremental start without an end, and a negative
  `min-rows-requested`. An unknown selected column name is ignored, as on the client.
- Not served, still 406 and not listed: `GET` and `DELETE` on `plan/{id}`, and `POST .../tasks`.
- Both served routes are listed in the config response's endpoints. A plan or credentials call emits the load's READ
  audit event.

## That no existing route answers differently, and how it was checked

- The first commit (`a183800c`) pinned today's 406 answers and the header behaviour before the routes existed: plan and
  credentials answered 406 and were not listed, and a load or create answered the same with the header.
- The credentials route is checked by a test that a load answers byte for byte the same with and without the header.
- The endpoint list, the unserved list and the boundary test of the fixtures changed in the commit that added the
  routes, because the list is derived from the served routes.
- Only one internal change touches existing code: `TableDtoWithMetadata` also carries the Iceberg `Table` it was loaded
  from, left out of equality and the string form.
- Access logs: the Spark 4.2 test JVM sent 25 plan requests out of 999, all from the new planning test; `smokeTest`
  sent 0 of 1228. Two last-run checks assert that the suites sent no plan request.
- The compatibility kit result is unchanged: 41 passed, 96 failed, 23 skipped, and `expected-outcomes.txt` untouched.

## The 1.5.2 reference line

Iceberg 1.5.2 has no classes for either route. The code lives in `services/tables/src/iceberg-1.12`, compiled only in
the default build and registered by class presence (`@ConditionalOnClass(PlanTableScanRequest)`). With
`-PicebergLinkedIn` both routes answer 406 as before, tested on both lines by `IrcPlanCredentialsRoutesTest`. Six new
tests skip on 1.5.2. Spark 3.5 on 1.5.2 has no server planning.

## Decisions the author made

1. A per-line source directory, as `openhouse-catalog-capabilities` has, so the reference line keeps answering 406.
2. The load's checks run before the body is parsed, so a caller without access learns nothing from a 400.
3. Planning with `use-snapshot-schema=false` and a snapshot id reads the branch whose head is that snapshot (the table
   schema); otherwise the snapshot's schema, so the answer matches client planning.
4. Incremental scans take the start as exclusive and the end as inclusive, as the specification says;
   `CatalogHandlers` takes the start as inclusive. No 1.12 client sends an incremental plan.
5. The answer is written by Iceberg's own response classes and parsers, not `CatalogHandlers.planTableScan`, which
   keeps plans in a process-wide map and cuts the task list to `min-rows-requested`.
6. The stored-plan routes answer 406 and stay unlisted, so no state is kept.
7. Credentials use Iceberg's `LoadCredentialsResponse`, and `planId` is ignored.
8. A plan or credentials call emits the load's READ audit event, so access stays visible.
9. The Spark 4.2 `test` and `smokeTest` tasks run classes in a set order (`junit.jupiter.testclass.order.default`),
   so the no-plan-request checks run last; other classes keep discovery order (checked by timestamps).

## Evidence, run on the pull request head `37d85483`, JDK 17

| Build line or suite | Result |
| --- | --- |
| `:services:tables:test`, Iceberg 1.12 (default) | 1695 tests, 0 failed, 1 skipped (`FormatV3NativeApiTest`, existing) |
| `:services:tables:test`, 1.5.2 (`-PicebergLinkedIn`) | 1679 tests, 0 failed, 39 skipped (16 tests exist only on 1.12) |
| `tables-test-fixtures` (1.12) | 41 passed |
| Spark 4.2 `test` | 67 passed |
| `smokeTest` | 66 passed |
| `restCompatKitTest` | 160: 41 passed, 96 failed, 23 skipped, the expected outcomes |
| CI on `37d85483` | Build and Run Tests passed; Reference build on 1.5.2 passed |

- New tests: `IrcScanPlanningTest` (15) and `IrcScanPlanningFormatV3Test` (1) run two real REST catalogs, one planning
  on the client and one on the server, and get equal plans and equal rows for unfiltered, partition filter, stats
  pruning, time travel, projection, branch, stats fields, incremental, v2 position and equality deletes, v3 deletion
  vectors, an empty table and a table of a few hundred files. `IrcPlanCredentialsRoutesTest` (9),
  `IrcPlanCredentialsAuthTest` (4), and `SparkIrcScanPlanningTest` (3): Spark 4.2 with server planning reads the same
  rows as with client planning on a v2 table with position deletes and a v3 table with deletion vectors, including a
  `LIMIT` over a file whose rows are all deleted.
- Not re-run: `spotlessApply` ran on every touched project; `spotlessCheck` was up to date on current sources and was
  not re-executed. `extensionsTest` was not touched and not run locally; it is part of CI.

## Known gaps

- A plan is unbounded. A client that asks for server planning of a very large table makes the service plan and
  serialize all of it; memory and latency were not measured. Bounding is V11c.
- Spark MERGE, UPDATE and DELETE with server planning were not run (V11b). Only local storage was used, so S3 and ADLS
  are untested.
- No real credential vending or remote signing (V12b to V12e, the owner's decision). Turning server planning on for
  clients from the server (V11d) is also the owner's decision.
- The post-merge CI run for the merge commit `91e175c4` has not been read yet.
