# V6 (IRC metrics report) and V7 (IRC register table): result

Branch `irc/v6-v7-metrics-register`, based on `irc-integration` at 7e8941ab (contains 6bc6fb05 and the V9c merge).
Pull request 112 (draft, https://github.com/mkuchenbecker/openhouse/pull/112), head
91e3d1deafa8c4fbde7772eca5803d79c8058bac (a merge of irc-integration at 6cc7b092, pull request 111, on top of 3e89edd2). Every fact is marked [ran] or [read].

## V6: what the investigation found
- [read] On the native path a scan or commit report never reaches the service. The OpenHouse catalog hands it to the
  client-side reporter named by `metrics-reporter-impl` (`OpenHouseMetricsReporter` -> `OpenHouseReportLogPublish`,
  which logs the report's JSON at info in the client). `OpenHouseRestCatalog` loads the same reporter, and its
  in-process config sends `rest-metrics-reporting-enabled=false`. The service has no route, store, metric or log for
  Iceberg reports; its `MetricsReporter` is a Micrometer wrapper, unrelated.
- [read] The spec (1.5.2 jar's request class, fork's `open-api/rest-catalog-open-api.yaml`): `POST
  /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics`, body `ReportMetricsRequest`, answers 204, 400, 401,
  403, 404 (NoSuchTable). `ReportMetricsRequest` and its serializer exist on Iceberg 1.2, 1.5.2 and 1.12.
- [read] Iceberg 1.5.2's `RESTMetricsReporter` posts inline; 1.12's posts from a background executor and only when
  the endpoint is listed.

## V6: behaviour
- Before [ran, test red]: 406 from the catch-all.
- After [ran]: the body is parsed with Iceberg's parser (400 if it is not a report); ids validated (400); a table
  in a multi-level namespace or a missing table is 404 `NoSuchTableException`; the caller needs the read privilege
  (`GET_TABLE_METADATA`), else 403 with the native wording; the report increments counter `irc_metrics_report`
  tagged `report_type` (`scan_report`, `commit_report`) in the service's `MeterRegistry` and is logged at debug;
  204. Nothing is stored. Served on every Iceberg line (mapped on the read controller); listed in `endpoints`.

## V7: what the investigation found
- [read] The native API has no register and no "create from a metadata file". The nearest things:
  (a) a create whose properties carry `openhouse.tableUUID` + `openhouse.tableLocation` reuses the directory
  `<root>/<db>/<table>-<uuid>` if the path exists (`TableUUIDGenerator`, `Storage.isPathValid`); (b) the
  create-with-data (snapshots PUT on the initial version) takes the UUID from the first snapshot's manifest-list
  path, which must be under `<root>/<db>/<table>-<uuid>/`; (c) replica creation (`tableType=REPLICA_TABLE` or
  `openhouse.isTableReplicated`) skips the path check and `openhouse.replicaTableLocationId` names the directory;
  (d) `client.table.schema` keeps a client's field ids. All need only `CREATE_TABLE` on the database.
- [read] Every table write goes through a `TableDto` and the server rebuilds the metadata (`repository.save`,
  `doCommit`): a house-table row cannot be pointed at a caller's file without bypassing the create and the commit core.
- [read] The native drop always purges: `deleteById` -> `catalog.dropTable(id, true)` -> the row, then
  `deletePrefix(table directory)`. Soft delete exists in the house-table store but nothing in the tables service's
  HTTP surface uses it. [ran] The kit's `testRegisterTable` confirms it: after its drop, register finds "no file".
- [read] Spec: `POST /v1/{prefix}/namespaces/{namespace}/register`, body `name`, `metadata-location`, and on 1.12
  `overwrite`; answers 200 (load-table response), 400, 401, 403, 404 (namespace), 409. It does not list 406.
  `RegisterTableRequest` does not exist on Iceberg 1.2.

## V7: behaviour (implemented, not refused)
A registration is a native create read from the file: schema, spec, sort order, properties, policies, snapshots and
refs go through `IcebergCreates` -> the native create validation -> `TablesService.createTable(body, creator, uuid)`,
or the native create-with-data when there are snapshots: the code the completion of a staged create already used,
now shared. The server writes a new metadata file in the same directory and the house-table row.
- Order [ran]: ids (400) -> name taken by a table or view (409 `Table already exists: db.t`) -> `CREATE_TABLE` on
  the database (403, native message, before anything is read) -> location -> rows -> read -> content -> create.
- Refusals, all 400 `BadRequestException`, each changing no row and no file [ran]: the file is not
  `<x>.metadata.json` directly in the directory the server allocates for that name, that database and the UUID in
  the directory's name (outside the root, another database, another table's directory, a subdirectory, via `..`);
  a live row points into the directory or has its UUID (a table renamed away); a soft-deleted row does; the
  metadata's own `location` is not that directory (the foreign location is not echoed); a snapshot's manifest list
  lies outside it; no file; not parseable (content not echoed); format version other than the cluster's; more than
  one schema or current schema id not 0. [read, no test] also refused: more than one partition spec, field ids a
  create would renumber, statistics files, a replica table type, unreadable policies.
- Why it is safe: register reaches no directory the native create-with-data does not already reach for the same
  principal (a table named t in a directory allocated to a table t of that database), and adds the row scan, the
  location and manifest-list checks. Two registrations of one directory are for one name, so the house-table
  compare-and-set lets one win.
- Not kept (as for any create): Iceberg table UUID, metadata log, snapshot log. `overwrite` is not read.

## Decisions (for the record)
1. V6 authorization is the read privilege checked on the house-table row alone (as the native drop), not a table
   load; the lock and capability checks of a load are not run. Reason: cost per report; a report reads nothing.
2. V6: `rest-metrics-reporting-enabled=false` is STILL sent (the brief's default was to stop). Reasons, all [ran]
   with it removed: Spark 3.5 `catalogTest` logged 2 "Failed to report metrics" warnings with stack traces (a 1.5.2
   client's create-with-data posts its commit report before the table exists -> 404); the kit logged 22 (1.12's
   background reporter after the table was dropped); 22 of 189 requests in a Spark 4.2 itest access log were
   reports. A client opts in with `rest-metrics-reporting-enabled=true`. Reverse by deleting one `withDefault`.
3. V6: a report for a missing table is 404 (brief and spec), although the reference server answers 204 always.
4. V6 is served on the Iceberg 1.2 runtime too; V7 only where the write routes are (no request class on 1.2).
5. V7 is implemented as a create from the file, never as a pointer to it.
6. V7 requires the directory to be the one allocated for the registered name (stricter than "under the root").
7. V7 keeps the file's stored policies by handing them to the native create as the request's policies.
8. V7 refuses metadata a create cannot store exactly (as D19 does on commit) instead of storing it lossily.
9. V7: a taken name is 409 whatever `overwrite` says.
10. Kit: `testRegisterTable` reclassified c -> b (new group DROPPURGE).
11. Outside the items: one `awaitRequest` added to a Spark 4.2 test that raced the access log (possibly T8).

## Tests added [ran]
`IrcMetricsRouteTest` 7, `IrcRestCatalogMetricsTest` 2 (real RESTCatalog: reports counted when asked, none by
default), `IrcRegisterRouteTest` 13, `IrcReadRoutesAuthTest` +1, `IrcWriteRoutesAuthTest` +1,
`IcebergRestRoutesBoundaryTest` +1 and updated, `SparkIrcEndpointsTest` +2 and one changed. Updated expectations:
`IrcConfigEndpointsTest` (endpoint list 16 -> 18), the two unserved-route lists, the config test.
Red before code [ran]: V6 12 failed of 45 run; V7 14 failed of 23 run.

## Counts [ran] on the final tree
| Suite | Default build | `-PicebergFork` | Before [read, notes] |
| --- | --- | --- | --- |
| `:services:tables:test` | 1560, 0 failed | 1560, 0 failed | 1536 |
| `tables-test-fixtures_2.12:test` (Iceberg 1.2 runtime) | 41, 0 failed | not in that build | 40 |
| `tables-test-fixtures-iceberg-1.5_2.12:test` | 41, 0 failed | 41, 0 failed | 40 |
| Spark 3.5 itest `catalogTest` | 269, 0 failed, 1 skipped | not run there | 269, 1 skipped |
| Spark 4.2 itest `test` | not in that build | 40, 0 failed | 38 |
| `restCompatKitTest` | not in that build | 39 passed, 98 failed, 23 skipped = list | 38, 99, 23 |
| `spotlessCheck` | passes | passes | |

Kit: `testRegisterExistingTable` FAILED -> passes (left the list). `testRegisterTable` stays FAILED, moved from
class c (406, not served) to class b, group DROPPURGE (register answers 400 "no file": the drop removed it).
The kit and the fork `spotlessCheck` ran on the head 91e3d1de; every other suite ran on 3e89edd2, the commit
before the merge of pull request 111, which changes only workflows, one document and the kit's service.
With reporting on by default (an earlier state of this branch, commits fb992aad to c104ddb3) [ran]: default
`:services:tables:test` 1560/0, fixtures 41/41, `catalogTest` 269/0/1 with 2 report warnings, kit 39/98/23 with 22
report warnings, Spark 4.2 40/0 with 22 metrics requests of 189, all 204.

## Not run
Spark 3.5 `statementTest`/`test`; Spark 3.1 suites; `:libs:datalayout`, apps; the real House Tables service (MySQL)
and its soft-deleted search paging; HDFS/S3/ADLS storages (local storage only); a view occupying the name (409 is
[read]); an unknown report type; register with a partition-spec history, renumbered ids or statistics; CI.

## Findings outside the items
- [read, not run] The native create with `openhouse.tableUUID` and the IRC staged-create completion accept the
  directory of a live table that was renamed away (neither scans rows); a later drop of the new table would purge
  the renamed table's files. Register refuses this case. Proposed work item.
- [ran] Disk filled twice during runs ("No space left on device", another job's Docker work); reruns passed.

## Proposed merge commit message
```
Merge V6, V7: the IRC metrics report and register table routes are served (#112)

Why: two spec routes answered 406. A client that reports metrics had nowhere to send them, and a table whose
house-table row was lost could not be brought back over IRC.

Leaves true: the native API is unchanged. A metrics report is validated, authorized with the read privilege,
counted by type and answered 204; nothing is stored, and clients still report only if they ask to. A registration
is a native create read from the metadata file, so the registered table is stored, authorized and governed as a
created table is; only metadata in the table's own allocated directory, used by no live or soft-deleted table, can
be registered, and the caller needs the create privilege before anything is read.

Evidence: default build :services:tables:test 1560 tests 0 failed, both fixture modules 41 each, Spark 3.5
catalogTest 269 with 0 failed and 1 skipped; under -PicebergFork :services:tables:test 1560 and 0 failed,
fixtures 41, Spark 4.2 itest 40 and 0 failed, compatibility kit 39 passed 98 failed 23 skipped equal to its list
(was 38, 99, 23); spotlessCheck passes both ways.

Known gaps: register does not keep the Iceberg table UUID, metadata log or snapshot log, and refuses a table whose
schema or partition spec ever changed. OpenHouse's drop removes a table's files, so the kit's drop-then-register
test cannot pass. A reporting client's create with data logs one warning (its report precedes the table). Not run:
the real House Tables service, storages other than local, Spark 3.5 statementTest.

Commits:
0a5cda95 test: metrics report tests, red before the route existed
fb992aad feat: the metrics route, counted and logged, authorized on the house-table row
d41f8c4b test: register tests, the proof and every refusal
22ebd7ba feat: register as a native create read from the file, sharing the staged-create completion's tail
c104ddb3 test: kit list and Spark 4.2 test follow the two served routes
3edc84e3 test: a Spark 4.2 test waits for its last access-log line (a race, possibly T8)
3e89edd2 fix: reporting stays off by default, after the suites showed its warnings and cost
91e3d1de merge of irc-integration at pull request 111, so the kit ran on the current base

Decision record: docs/plans/irc/... on branch claude/openhouse-rest-v3-planning-g42c0h of mkuchenbecker/openhouse.
```
