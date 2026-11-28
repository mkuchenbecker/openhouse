# V6 and V7: the IRC metrics report and register table routes

Bottom line: both routes are now served over the Iceberg REST Catalog. Pull request 112 in mkuchenbecker/openhouse
is merged into `irc-integration` as merge commit `8876a7f295aaca02b49baa3e306fe44774ccc1f9`. A metrics report is
counted and logged, never stored. A register is a native create read from the metadata file, never a pointer to the
caller's file. The compatibility kit moved from 38 passed, 99 failed, 23 skipped to 39 passed, 98 failed, 23 skipped.
Still not served over IRC: multi-table commits, scan planning, credential vending and view rename. Every fact below
is marked [ran] or [read]. Dated 2026-11-23. Written by the orchestrator from the delegated agent's report.

## What the investigation found: metrics (V6)
- [read] Natively, a scan or commit report never reaches the service. The client-side reporter named by
  `metrics-reporter-impl` logs the report's JSON at info level in the client. The service has no route, store or log
  for Iceberg reports. Its own `MetricsReporter` is a Micrometer wrapper and is unrelated.
- [read] The specification route is `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics`. It answers
  204, 400, 401, 403 and 404. Iceberg 1.5.2's client posts inline; 1.12's posts from a background executor and only
  when the endpoint is listed.

## Metrics: before and after
- Before [ran, test red]: 406 from the catch-all.
- After [ran]: the body is parsed with Iceberg's parser, and 400 if it is not a report. Ids are validated (400). A
  missing table, or a table in a multi-level namespace, is 404. The caller needs the read privilege on the
  house-table row, else 403 with the native wording. The report is counted as `irc_metrics_report`, tagged by report
  type, and logged at debug. The answer is 204. Nothing is stored. It is served on every Iceberg line and listed in
  the advertised endpoints.
- Reporting stays off by default: the config route still sends `rest-metrics-reporting-enabled=false` (D34).
  With it on [ran]: Spark 3.5 `catalogTest` logged 2 warnings, the kit logged 22, and 22 of 189 requests in a Spark
  4.2 run were reports.

## What the investigation found: register (V7)
- [read] The native API has no register. The nearest things are a create carrying `openhouse.tableUUID`, a
  create-with-data, and replica creation. All need only the create privilege on the database.
- [read] Every table write goes through a `TableDto` and the server rebuilds the metadata, so a house-table row
  cannot be pointed at a caller's file without bypassing the create and the commit core.
- [read] The native drop always purges the table's files. [ran] The kit's `testRegisterTable` confirms it: after its
  own drop, register finds no file.
- [read] The specification route is `POST /v1/{prefix}/namespaces/{namespace}/register`. It answers 200, 400, 401,
  403, 404 and 409. The request class does not exist on Iceberg 1.2.

## Register: before and after
- Before [ran, test red]: 406.
- After [ran]: a native create read from the file, through the same code the completion of a staged create already
  used. The server writes a new metadata file in the same directory and the house-table row. Order of checks: ids
  (400), name taken by a table or view (409), create privilege on the database (403, before anything is read),
  location, rows, read, content, create.
- Not kept, as for any create: the Iceberg table UUID, the metadata log and the snapshot log. `overwrite` is not
  read; a taken name is 409 whatever it says.

## Refusals
All are 400, and each changes no row and no file [ran, with tests]:
- the file is not `<x>.metadata.json` directly in the directory the server allocates for that name, that database
  and the UUID in the directory's name (outside the root, another database, another table's directory, a
  subdirectory, or via `..`);
- a live row points into the directory or has its UUID (a table renamed away);
- a soft-deleted row does;
- the metadata's own `location` is not that directory;
- a snapshot's manifest list lies outside it;
- no file, or a file that does not parse;
- a format version other than the cluster's;
- more than one schema, or a current schema id other than 0.

Four refusals exist in code with no test [read]: a partition-spec history, field ids a create would renumber,
statistics files, and a replica table type. Work item T13.

## Counts [ran] on the final tree
| Suite | Default build | Under `-PicebergFork` | Before |
| --- | --- | --- | --- |
| `:services:tables:test` | 1560 passed, 0 failed | 1560 passed, 0 failed | 1536 |
| Fixtures, Iceberg 1.2 runtime | 41, 0 failed | not in that build | 40 |
| Fixtures, Iceberg 1.5 | 41, 0 failed | 41, 0 failed | 40 |
| Spark 3.5 `catalogTest` | 269, 0 failed, 1 skipped | not run there | 269 |
| Spark 4.2 `test` | not in that build | 40, 0 failed | 38 |
| Compatibility kit | not in that build | 39 passed, 98 failed, 23 skipped | 38, 99, 23 |
| `spotlessCheck` | passes | passes | |

Tests were red before code: V6 12 failed of 45 run, V7 14 failed of 23 run. New tests: 7 metrics route, 2 real
`RESTCatalog` metrics, 13 register, and additions to the auth, boundary and Spark 4.2 endpoint tests.
Kit: `testRegisterExistingTable` now passes. `testRegisterTable` still fails and moved to a new group, DROPPURGE,
because OpenHouse's drop deletes the table's files. That is an OpenHouse rule.

## What was not run
Spark 3.5 `statementTest` and `test`; Spark 3.1 suites; `:libs:datalayout` and the apps; the real House Tables
service and its soft-deleted paging; storages other than local; a view occupying the name (409 is [read]); an
unknown report type; register with a partition-spec history, renumbered ids or statistics files; CI.

## Known gaps
- Register refuses a table whose schema or partition spec ever changed.
- A reporting client's create with data logs one warning, because its report precedes the table.
- The kit and the fork's `spotlessCheck` ran on the merged head; the other suites ran on the commit before the
  merge of pull request 111, which changed only workflows, one document and the kit's service.
- Data-loss finding found while reading: `21-findings-continued.md`, eleventh round. Work items V16, N5, T13.

## References
D34 (`37-decisions-continued-3.md`); D9, D19, D24; `42-v13b-spec-deviations-result.md`.
