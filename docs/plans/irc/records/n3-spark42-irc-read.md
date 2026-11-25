# N3 (read-only): Spark 4.2 on Iceberg 1.12 reads OpenHouse over IRC

Delegated agent, 2026-11-22. Branch `irc/n3-spark42-irc-read` in mkuchenbecker/openhouse, based on
`irc-integration` at b732cc7b. Draft pull request 92, head b0e447ecb2c13a689d507a8d841045bee06c5777.
Marks: (ran) means run here on JDK 17. (read) means taken from code or from earlier records only.

## Bottom line

It works. Spark 4.2.0 on the fork's `iceberg-spark-runtime-4.2_2.13` (commit 28742df3e538) reads
OpenHouse tables over the IRC read routes. It uses Iceberg's own `SparkCatalog` and `RESTCatalog`
(`type=rest`, `uri=<service>/iceberg`, `token=<bearer>`), and no OpenHouse class is on the Spark
classpath (asserted). Results equal what was written for every statement listed below (ran).
Two server-side changes were needed:

1. A compile fix. Under the switch, `:services:tables` did not compile on `irc-integration`.
2. Table routes in a multi-level namespace now answer 404 instead of 400. Without this, no metadata
   table could be read.

Writes fail as expected (406). Their behavior is pinned.

## Statements (all ran, all green)

All of these were run on `parted` (partitioned by `p`, Parquet), `flat` (Parquet) and `flat_orc`
(ORC):

- `SHOW NAMESPACES IN irc` returns `[irc42]`.
- `SHOW TABLES IN irc.irc42` returns three rows.
- `SHOW NAMESPACES IN irc.irc42` returns an empty list.
- `DESCRIBE TABLE` shows the columns and the partition section.
- `DESCRIBE TABLE EXTENDED` and `SHOW TBLPROPERTIES` work.
- `SELECT` with a filter, and with `GROUP BY` / `count` / `sum` / `max`, returns the written rows.
- `SELECT ... VERSION AS OF <first snapshot id>` returns the first snapshot's rows.
- The `.snapshots`, `.history` and `.files` metadata tables return 2, 2 and 3 (or 2) rows. Their
  columns and every value equal what the OpenHouse Java client shows through `MetadataTableUtils`.

Before fix 2, every metadata-table read failed. The error was
`BadRequestException: Malformed request: OpenHouse namespaces are databases and have exactly one
level: irc42.parted`. That was 11 of 30 tests, and each failed for this reason only (ran).

## Server changes for the 1.12 client (test-first, at the HTTP boundary)

1. **Namespace split.** Iceberg 1.12 removed `RESTUtil.NAMESPACE_SPLITTER`, so
   `IcebergRestApiHandler` no longer compiled under `-PicebergFork`. This was a latent break on
   `irc-integration`: the CI after merge builds only the default variant (read).
   - Fix: the handler now splits on `\u001f` itself, as `OpenHouseRestAdapter` already does.
   - Test fix found on the way: the multi-level cases in `IrcReadRoutesTest` never reached the
     split. MockMvc's URI template re-encoded `%1F`, so the name validator rejected the request
     first. They now send the encoded URI. The expected answer is still 400.
2. **404 for a table in a multi-level namespace.** This applies to `GET` and `HEAD`
   `/v1/namespaces/{a%1Fb}/tables/{t}`.
   - Why: `RESTCatalog` (1.12 and 1.5.2 alike) loads `db.t.snapshots` as table `snapshots` in
     namespace `db.t`. It falls back to the metadata table only on 404 `NoSuchTableException`.
   - New answer: 404 `NoSuchTableException`, "Table does not exist: db.t.name".
   - Unchanged: namespace routes still answer 400 for multi-level namespaces, and an empty level is
     still a 400 on every route.
   - 1.5.2 client: only this case changes, and it now works. There is a new 1.5.2 test,
     `IcebergRestCatalogReadTest.metadataTablesLoadThroughTheRestCatalog`. It failed before the fix
     and passes after (ran).

No `endpoints` list, HEAD support, query parameter, header or metrics change was needed for reads:

- With no `endpoints` list in the config response, 1.12 falls back to its default endpoints.
  `tableExists` and `namespaceExists` then go through GET. The client sends `snapshots=all`, which
  the service accepts.
- Each scan posts a metrics report. The service answers 406, and the client logs
  `WARN RESTMetricsReporter: Failed to report metrics to REST endpoint v1/namespaces/irc42/tables/<t>/metrics`
  and carries on (ran, seen in the test output).
- Nothing was added at the JSON level.

## How the service runs and how tables are seeded

**Service: in its own JVM.** Embedding was ruled out by evidence (ran,
`dependencies --configuration`):

- Spark 4.2's test classpath resolves jackson-databind 2.21.2, and Spark's Scala module refuses
  other versions.
- The service runs on Spring Boot 2.7 with the build's forced Jackson 2.13.4.
- The service's unshaded Iceberg jars would sit next to the engine's shaded runtime, which is the
  same clash the Spark 3.5 itest has to exclude.
- A separate JVM also makes "no OpenHouse class in the engine" checkable.

Setup of that JVM:

- `IrcReadTestServiceProcess` in the test starts `IrcReadTestService` (source set `service` of the
  new module) on port 0, with a private work directory.
- The service is `SpringH2TestApplication`, the same in-memory H2 stores the embedded fixture uses,
  plus the dummy token interceptor and an authorization handler that denies `irc_stranger`.
- Storage is the local file system under the work directory.
- The test waits for `ready.json`, which holds the port, tokens, snapshot ids and the Java client's
  view of each table.
- The service exits when its stdin closes: a shutdown hook closes it, and it also closes if the
  test JVM dies.
- No service or test JVM of mine was left running (checked).

**Seeding: in the service JVM, through the existing OpenHouse Java client** (shaded
`openhouse-java-iceberg-1.5-runtime`) over the native HTTP API:

- `createTable` goes through the native POST.
- Iceberg's `GenericFileWriterFactory` writes real Parquet and ORC files to the table location.
- `newAppend().commit()` goes through `OpenHouseTableOperations` to the native PUT.

So every snapshot is a native commit, as Spark 3.5 with the OpenHouse catalog makes them. This is
the least invasive option: it needs no Spark 3.5 in the loop and no new protocol code, and nothing
writes metadata behind the service's back.

## Differences from the Spark 3.5 + OpenHouse catalog path (all observed)

Sources:

- The Java client view was recorded in the same run (ran).
- Spark 3.5 + OpenHouse output came from a scratch probe run under `-PicebergFork` on the embedded
  server, with equivalent tables written by Spark 3.5 SQL. The probe was not committed (ran).

| # | What | Spark 4.2 + IRC | Spark 3.5 + OpenHouse catalog |
|---|---|---|---|
| 1 | Table name | DESCRIBE EXTENDED has no Name row: Catalog=irc, Namespace=irc42, Database=irc42, Table=parted. Java client `name()` = `openhouse.irc42.parted` | `Name openhouse.n3probe.parted` |
| 2 | DESCRIBE EXTENDED detail rows | Catalog, Namespace, Database, Table, Type(MANAGED), Location, Provider, Table_type(EXTERNAL), Table Properties, Statistics | Name, Type(MANAGED), Location, Provider, Owner(root), Table Properties |
| 3 | Metadata columns in DESCRIBE EXTENDED (format v2 table) | _spec_id, _partition, _file, _pos, _deleted | adds _row_id, _last_updated_sequence_number |
| 4 | SHOW TABLES in a missing namespace | fails: `org.apache.iceberg.exceptions.NoSuchNamespaceException: Namespace does not exist: irc42_never` (Iceberg's own exception, not Spark's SCHEMA_NOT_FOUND) | empty result |
| 5 | SHOW NAMESPACES IN cat.db | empty list | `UnsupportedOperationException: Openhouse supports 2-lvl namespace <schema>.<table>` |
| 6 | Denied principal | lists namespaces and tables; SELECT and DESCRIBE fail with `org.apache.iceberg.exceptions.ForbiddenException: Forbidden: Operation on table irc42.flat failed as user irc_stranger is unauthorized` | not run in Spark 3.5 here. The Java client raises `WebClientResponseWithMessageException` 403 with the same message, and existence answers anyone (V2 record, read) |
| 7 | Bad token | every statement, including SHOW NAMESPACES, fails at the catalog's config call: `NotAuthorizedException: Not authorized: Unauthorized` | not run (the embedded default server has no token interceptor) |
| 8 | Scan metrics | WARN line per scan (406 swallowed) | none |
| 9 | INSERT / CREATE TABLE | refused, see below | work |

The following were **equal** (ran):

- DESCRIBE TABLE output.
- Missing table: `ExtendedAnalysisException [TABLE_OR_VIEW_NOT_FOUND] ...` in both, including a
  table in a missing namespace.
- Location.
- Table properties: SHOW TBLPROPERTIES minus the keys Spark derives equals the Java client's map,
  including every `openhouse.*` key.
- Every column and every value of the snapshots, history and files metadata tables, compared with
  the Java client.
- Query results.

Not observable through Spark SQL and not re-checked here: FileIO (`ResolvingFileIO` against
`HadoopFileIO`) and the `last-updated-ms` restamp. Both are from the V2 record for 1.5.2 (read).

## Writes today (ran, pinned in `SparkIrcWriteTest`)

- `INSERT INTO irc.irc42.flat VALUES (9, 9.5)` fails with
  `org.apache.iceberg.exceptions.RESTException: Unable to process (code: 406, type:
  UnsupportedOperationException): This server does not support POST
  /v1/namespaces/irc42/tables/flat yet`. The table keeps its 2 snapshots and 3 rows. I did not check
  whether the data files of the failed insert were cleaned up.
- `CREATE TABLE irc.irc42.created (id bigint) USING iceberg` fails with the same exception and
  `... POST /v1/namespaces/irc42/tables yet`. No table is created.
- Because the service sends no `endpoints` list, the client attempts the write instead of refusing
  it locally.

## Default build untouched (ran)

- Method: an init script (not committed) dumped, for all 59 projects, the resolved artifacts of
  every resolvable configuration and every source set's directories. It ran before any change and
  again at the final head.
- Result: 36,446 lines each, and `diff -r` is empty.
- `settings.gradle` includes the new project only `if (icebergFork)`.
- The root build's Jackson/ORC/Avro forces are cleared only on the new module's test classpaths,
  inside its own build file.
- `spotlessCheck` is clean on `:services:tables`, the Spark 3.5 itest and the new module.

## Memory

The repository-default daemon (`-Xmx2g`) was enough. There were no heap errors and no retries with
`-Xmx3g`.

- Spark 4.2 test JVM: `-Xmx1536m`.
- Service JVM: `-Xmx768m`.

## Test counts (final runs, ran)

| Suite | Result |
|---|---|
| `-PicebergFork :integrations:spark:spark-4.2:openhouse-spark-4.2-itest:test` | 31 tests, 0 failed (Read 15, Differences 10, Failure 4, Write 2) |
| `-PicebergFork :services:tables:test` | 1297 tests, 0 failed (6m40s) |
| `:services:tables:test` (default) | 1297 tests, 0 failed |
| Spark 3.5 itest `catalogTest` (default) | 225 tests, 1 skipped, 0 failed |

`publish-local.sh`:

- The Spark 4.2 runtime published in 1m31s. The 3.5 set was already there, copied from another
  worktree at the same pinned commit, so the script's skip path was used.
- A cold run of the full script was not done.

## Pull request

https://github.com/mkuchenbecker/openhouse/pull/92 (draft). Head:
b0e447ecb2c13a689d507a8d841045bee06c5777.

## Merge commit

Title: Merge N3: Spark 4.2 on Iceberg 1.12 reads OpenHouse over IRC (opt-in build)

Why: The target platform is Spark 4.2 on Iceberg 1.12 talking to OpenHouse over the Iceberg REST
Catalog only. This is the first end-to-end proof of its read half, with Iceberg's own SparkCatalog
and RESTCatalog and no OpenHouse class in the engine.

Leaves true: Under -PicebergFork, Spark 4.2 reads OpenHouse tables over IRC. That covers listing,
describe, filtered and aggregated selects, time travel by snapshot id, and the snapshots, history
and files metadata tables, on partitioned Parquet, unpartitioned Parquet and ORC tables. Results
equal what was written and what the OpenHouse Java client shows. :services:tables compiles on the
1.12 line again. The IRC table routes answer 404 for a table in a multi-level namespace, so metadata
tables load on 1.12 and on 1.5.2. Writes over IRC still answer 406, and that behavior is pinned. The
default build is unchanged.

Evidence: The new module has 31 tests, all passing. -PicebergFork :services:tables:test: 1297 tests,
0 failed. Default :services:tables:test: 1297 tests, 0 failed. Default Spark 3.5 catalogTest: 225
tests, 1 skipped, 0 failed. Before the 404 fix, 11 of 30 Spark 4.2 tests and the new 1.5.2
RESTCatalog test failed, each with the 400 multi-level namespace error. The resolved-classpath and
source-set dump of all 59 default-build projects is identical before and after (36,446 lines).

Known gaps: A missing namespace in SHOW TABLES surfaces Iceberg's NoSuchNamespaceException rather
than Spark's SCHEMA_NOT_FOUND. Every scan logs a WARN for the metrics POST answering 406. Without an
endpoints list, writes are attempted and refused by the server instead of locally. Denied-principal
and bad-token output were not compared against Spark 3.5 with the OpenHouse catalog. The Spark 3.5
comparison came from a scratch run on equivalent tables, not the same tables. FileIO and
last-updated-ms differences were not re-checked from Spark.

Commits:
- 8e9c18a9 build: publish the fork's Spark 4.2 / Scala 2.13 runtime. The test needs the shaded 4.2
  runtime from the pinned commit. A second Iceberg build is needed because one build has one Scala
  version.
- 443ff7df test: send %1F namespaces encoded. The multi-level cases never reached the namespace
  split, because MockMvc re-encoded the %.
- ec34ae74 irc: split namespace levels without RESTUtil.NAMESPACE_SPLITTER. 1.12 removed it, so
  :services:tables did not compile under the switch.
- 38bb756c test: Spark 4.2 on the fork's Iceberg 1.12 reads OpenHouse over IRC. This is the
  end-to-end proof: the service runs in its own JVM and is seeded through the native API. The
  metadata-table reads fail at this commit.
- 885ff32f test: a table in a multi-level IRC namespace is 404. Pins at the HTTP boundary, and for a
  1.5.2 RESTCatalog, what metadata tables need.
- 99f1ceaa irc: answer 404 for a table in a multi-level namespace. RESTCatalog falls back to
  metadata tables only on 404.
- e3e23e28 test: pin how Spark 4.2 over IRC describes a table and nested namespaces. Records the
  further user-visible differences from Spark 3.5 with the OpenHouse catalog.
- b0e447ec docs: describe the Spark 4.2 IRC read module. Tells users of the opt-in build how the
  module runs, why, and that the write test is meant to fail.

Decision record: docs/plans/irc/16-decisions-continued.md (D16, D18) on branch claude/openhouse-rest-v3-planning-g42c0h of mkuchenbecker/openhouse.

Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01T3dbirnudathz3xU8K18AE

## Not run

- The full build.
- Spark 3.5 itest `statementTest` and `test`.
- The java iceberg-1.5 itest.
- The new module's tests in the default build: the module does not exist there.
- A cold `publish-local.sh` run.
- Spark 3.5 + OpenHouse for a denied principal or a bad token.
- CI on PR 92.
- Whether the failed INSERT's data files were cleaned up.
- A check of which commits on `irc-integration` broke the 1.12 compile. Inferred: V2 (#88) used
  `NAMESPACE_SPLITTER`, and #91 merged after it.
