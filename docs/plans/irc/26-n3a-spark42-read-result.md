# Spark 4.2 reads OpenHouse over IRC (N3a): result

Bottom line: it works. Spark 4.2.0 on the fork's `iceberg-spark-runtime-4.2_2.13` (commit 28742df3e538) reads
OpenHouse tables over the IRC read routes, using Iceberg's own `SparkCatalog` and `RESTCatalog`
(`type=rest`, `uri=<service>/iceberg`, `token=<bearer>`). No OpenHouse class is on the Spark classpath, and a
test asserts that. Results equal what was written for every statement below. Writes fail with a 406, and that
is pinned. Written by a delegated agent from branch `irc/n3-spark42-irc-read` (pull request 92, merged as
8b9bfe83). "Ran" means run on JDK 17. "Read" means taken from code or earlier records only.

## What works (ran)

Run on `parted` (partitioned by `p`, Parquet), `flat` (Parquet) and `flat_orc` (ORC).

- `SHOW NAMESPACES IN irc` returns `[irc42]`. `SHOW TABLES IN irc.irc42` returns three rows. `DESCRIBE TABLE`,
  `DESCRIBE TABLE EXTENDED` and `SHOW TBLPROPERTIES` work.
- `SELECT` with a filter, and with `GROUP BY`, `count`, `sum` and `max`, returns the written rows.
- `SELECT ... VERSION AS OF <first snapshot id>` returns the first snapshot's rows.
- The `.snapshots`, `.history` and `.files` metadata tables equal what the OpenHouse Java client shows.

## The two server changes

Both were needed for the 1.12 client and were written test first at the HTTP boundary.

1. A compile fix. Iceberg 1.12 removed `RESTUtil.NAMESPACE_SPLITTER`, so `IcebergRestApiHandler` did not compile
   under the opt-in switch, a latent break because the post-merge build compiles only the default variant (read).
   The handler now splits on `\u001f` itself, as `OpenHouseRestAdapter` does. The multi-level cases in
   `IrcReadRoutesTest` had never reached the split, because MockMvc re-encoded `%1F`. They now send the encoded
   URI and still expect 400.
2. A table route in a multi-level namespace now answers 404 `NoSuchTableException` instead of 400. This applies
   to `GET` and `HEAD` on `/v1/namespaces/{a%1Fb}/tables/{t}`. `RESTCatalog` loads `db.t.snapshots` as table
   `snapshots` in namespace `db.t`, and it falls back to the metadata table only on a 404. Before the fix, 11 of
   30 Spark 4.2 tests failed on the 400 (ran). Namespace routes still answer 400. The fix also repairs metadata
   tables for the 1.5.2 client, with a new test that failed before and passes after (ran).

No `endpoints` list, header or metrics change was needed. With no list, 1.12 uses its default endpoints and
existence checks go through GET. Each scan posts a metrics report, the service answers 406, and the client logs a
WARN and carries on (ran).

## How the service runs and how tables are seeded

The service runs in its own JVM, not inside the Spark test. Evidence (ran, `dependencies --configuration`):

- Spark 4.2 needs jackson-databind 2.21.2. The service runs on Spring Boot 2.7 with Jackson 2.13.4 forced.
- The service's unshaded Iceberg jars would clash with the engine's shaded runtime, as in the Spark 3.5 itest.
- A separate JVM also makes "no OpenHouse class in the engine" checkable.

`IrcReadTestServiceProcess` starts `IrcReadTestService` on port 0 with a private work directory. It is
`SpringH2TestApplication` (in-memory H2 stores, local file storage) plus the dummy token interceptor and an
authorization handler that denies `irc_stranger`. The test waits for `ready.json`, which holds the port, tokens,
snapshot ids and the Java client's view of each table. The service exits when its stdin closes.

Tables are seeded in the service JVM through the existing OpenHouse Java client over the native HTTP API.
`createTable` is the native POST, Iceberg's `GenericFileWriterFactory` writes real Parquet and ORC files, and
`newAppend().commit()` goes through `OpenHouseTableOperations` to the native PUT. So every snapshot is a native
commit, as Spark 3.5 with the OpenHouse catalog makes them. It needs no Spark 3.5 and no new protocol code, and
nothing writes metadata behind the service's back.

## Nine differences from Spark 3.5 with the OpenHouse catalog (observed)

The Spark 3.5 output came from an uncommitted scratch probe on equivalent tables, run under the opt-in build.

1. Table name: `DESCRIBE EXTENDED` has no Name row and shows Catalog, Namespace, Database and Table instead.
   Spark 3.5 shows `Name openhouse.n3probe.parted`.
2. `DESCRIBE EXTENDED` detail rows differ: 4.2 adds Table_type and Statistics; 3.5 shows Name and Owner.
3. Metadata columns of a format v2 table: 4.2 shows `_spec_id`, `_partition`, `_file`, `_pos`, `_deleted`;
   3.5 adds `_row_id` and `_last_updated_sequence_number`.
4. `SHOW TABLES` in a missing namespace fails with Iceberg's `NoSuchNamespaceException`, not Spark's
   `SCHEMA_NOT_FOUND`. Spark 3.5 returns an empty result.
5. `SHOW NAMESPACES IN cat.db` returns an empty list. Spark 3.5 throws "Openhouse supports 2-lvl namespace".
6. A denied principal can list namespaces and tables, but `SELECT` and `DESCRIBE` fail with `ForbiddenException`.
   Not run on Spark 3.5. The Java client raises a 403 with the same message (read, V2 record).
7. A bad token fails every statement, including `SHOW NAMESPACES`, at the config call with
   `NotAuthorizedException`. Not run on Spark 3.5, because the embedded default server has no token interceptor.
8. Each scan logs a WARN for the metrics POST answering 406. Spark 3.5 logs none.
9. `INSERT` and `CREATE TABLE` are refused. They work on Spark 3.5.

Equal in both (ran): `DESCRIBE TABLE`, the missing-table error, location, table properties including every
`openhouse.*` key, the three metadata tables, and query results. Not re-checked: FileIO class and the
`last-updated-ms` restamp (read, from the V2 record).

## What a user saw for writes at that time (ran, pinned in `SparkIrcWriteTest`)

- `INSERT INTO irc.irc42.flat VALUES (9, 9.5)` failed with `RESTException: Unable to process (code: 406,
  type: UnsupportedOperationException): This server does not support POST /v1/namespaces/irc42/tables/flat yet`.
  The table kept 2 snapshots and 3 rows. Whether the failed insert's data files were cleaned up was not checked.
- `CREATE TABLE irc.irc42.created (id bigint) USING iceberg` failed the same way, and no table was created.
- The service sends no `endpoints` list, so the client attempted the write instead of refusing it locally.
- Pull request 94 (V3) has since made the write routes work, so this 406 expectation is obsolete.

## Default build untouched, daemon heap, counts

- An uncommitted init script dumped the resolved artifacts and source-set directories of all 59 projects before
  any change and at the final head. Both are 36,446 lines and `diff -r` is empty (ran). The new project is
  included only under the switch. `spotlessCheck` is clean on the three touched modules.
- Heap: the repository-default daemon (`-Xmx2g`) was enough, with no heap errors. The Spark 4.2 test JVM used
  `-Xmx1536m` and the service JVM `-Xmx768m`.

| Suite | Result (ran) |
| --- | --- |
| Opt-in build, Spark 4.2 itest | 31 tests, 0 failed (Read 15, Differences 10, Failure 4, Write 2) |
| Opt-in build, `:services:tables:test` | 1297 tests, 0 failed |
| Default build, `:services:tables:test` | 1297 tests, 0 failed |
| Default build, Spark 3.5 `catalogTest` | 225 tests, 1 skipped, 0 failed |

## Not run

The full build; Spark 3.5 `statementTest` and `test`; the java iceberg-1.5 itest; a cold `publish-local.sh`
(the Spark 4.2 runtime published in 1m31s, the Spark 3.5 set was copied); Spark 3.5 for a denied principal or a
bad token; CI on pull request 92; and whether the failed insert left data files. Inferred: the compile break came
from V2 (pull request 88) using `NAMESPACE_SPLITTER`.
