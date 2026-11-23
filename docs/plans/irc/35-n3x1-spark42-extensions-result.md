# OpenHouse SQL extensions for Spark 4.2 (N3-x1, policy part of N3-x2): result

Bottom line: Spark 4.2 on the fork's Iceberg 1.12, with no OpenHouse catalog or client class, runs every OpenHouse
policy statement over IRC and stores what Spark 3.5 with the OpenHouse catalog stores. GRANT, REVOKE, SHOW GRANTS and
UNLOCK parse and analyze, and fail with the Spark 3.5 "does not support" messages until the REST capability client
lands (C2-3). Written by a delegated agent from branch `irc/n3-x1-spark42-extensions` (pull request 101, merged as
b07613f9). Opt-in build only. "Ran" means run on JDK 17. "Read" means taken from code only.

## What works (ran)

The module is `:integrations:spark:spark-4.2:openhouse-spark-4.2-runtime_2.13` at
`integrations/spark/spark-4.2/openhouse-spark-runtime`. It exists only under `-PicebergFork`.
- Parse and analyze on Spark 4.2: every SET and UNSET POLICY form, MODIFY COLUMN ... SET TAG, GRANT and REVOKE on a
  table or a database, SHOW GRANTS, and ALTER TABLE ... UNLOCK [REASON].
- Execute over IRC through Iceberg's own `SparkCatalog` and `RESTCatalog`: all 21 rows of the Spark 3.5 patch table,
  43 outcomes. 37 were applied, and in each the stored `policies` equal what the same statement stores from Spark
  3.5. 6 were refused (30d, 2m, 1y on an hour-partitioned table) with the same native message, storing nothing.
- Pinned as unsupported: GRANT, REVOKE, SHOW GRANTS (table and database) and UNLOCK fail as on Spark 3.5 over
  `RESTCatalog` ("Catalog 'irc' does not support Grant Revoke Statements", "... does not support UNLOCK", and with the
  switch off "ALTER TABLE UNLOCK is disabled..."). So does `irc_adv`, a catalog that claims `policies,grants,unlock`
  on the client side. A plain `RESTCatalog` had no client for those routes, and the server did not yet serve them.
  The server now serves them (pull request 102).

## Adaptations from the Spark 3.5 sources

Every file not listed is byte-identical to its 3.5 counterpart. Paths are under `src/main` of the module.
- `OpenhouseSqlExtensions.g4` is unchanged but generated with ANTLR 4.13.1 (3.5 uses 4.7.1), because Spark 4.2 ships
  runtime 4.13.1 (read) and the ATN format changed after 4.9.
- `OpenhouseSparkSqlExtensionsParser.scala` adds `parsePlanWithParameters`, because the default calls `parsePlan` and
  would drop the parameters of delegated SQL, and a delegated `parseRoutineParam`, abstract in 4.2. These are the
  only two new methods (`javap`). An unused import is dropped.
- The AST builder, `UnlockTable.scala` and `ResolveUnlockTable.scala` use the module's `UnlockReason`,
  `UnlockTableTarget`, `UnlockValidationException` and new `TimeGranularity`, because the engine has no generated or
  Java client. `ResolveUnlockTable` reads the switch with `SQLConf.get`. Collections use `scala.jdk.CollectionConverters`.
- `OpenhouseDataSourceV2Strategy.scala` takes `Strategy` from `org.apache.spark.sql.classic` (Spark 4 moved it, read).
  `ShowGrantsStatementExec.scala` adds `.toSeq`, because `Seq` is immutable in 2.13.
- `OpenHouseCapabilities.java` does not ask `JavaClientCapabilities` first. Left out on purpose: the two OpenHouse
  catalog classes, `JavaClientCapabilities.java`, `IcebergCatalogMapper.scala` and the Scala `Principal`.

The capabilities module gained `TimeGranularity` (3 tests). Compiled unchanged on 4.2: the `OpenhouseParseException`
constructor, `AnalysisException(..., cause = ...)`, `LeafV2CommandExec`, `LeafCommand`, `ParserUtils.string`, `SparkTable`.

## Every user-visible difference from Spark 3.5 (ran: a scratch probe, not committed, ran the same 35 statements on both and diffed)

1. Spark's own messages gain a SQLSTATE suffix (`42601`, `42P01`, `XX000`). This covers the parse error for a
   statement the extensions do not route, `TABLE_OR_VIEW_NOT_FOUND` (which also gains a `Search path` line), and
   `INTERNAL_ERROR` for an OpenHouse statement on a non-Iceberg catalog. Pinned in `UserVisibleOutcomes`.
2. EXPLAIN quotes digit-leading identifier parts (`` `0_`.`0_` `` where 3.5 shows `0_.0_`), from Spark 4's
   `Identifier.toString`. Pinned in three ported statement tests.
3. A refused policy over IRC is `BadRequestException: Malformed request: <native message>`, from 4.2 as from 3.5 over
   IRC. Through the OpenHouse catalog it is the Java client's exception carrying the response body. That is a
   difference between paths, not between Spark lines.

Identical, class and full message: every OpenHouse parse error, the HISTORY, lock-reason and time-zone checks, the
six "non-Openhouse table" refusals, grants and unlock "does not support", UNLOCK disabled, and the SHOW GRANTS rows.

## Dependencies and the default build (ran)

- `compileClasspath` is exactly `spark-sql_2.13:4.2.0`, the fork's `iceberg-spark-runtime-4.2_2.13` and the
  capabilities module. No Spring and no other OpenHouse project appear in any configuration.
- The uber jar (285 KB) holds only extensions and capabilities packages. A byte grep of its classes for Spring, the
  Java client, the generated client and the catalog classes finds nothing.
- The default build is untouched: a dump of every resolvable configuration and source-set directory of all 60
  projects, before and after, is identical (35,818 lines). The module is included only under `icebergFork`.
- The classpath assertion was reworded on purpose. `SparkIrcReadTest.theSparkJvmHasNoOpenHouseCatalogClasses` is
  unchanged and still true. The new `SparkJvmClasspathTest` replaces "nothing of OpenHouse" with: every OpenHouse
  class on the classpath is in the extensions uber jar or is a test, and none is a catalog, client, generated client
  or service class. Its three tests check the jar contents, the absence of any catalog, and eight classes not loading.

## Test counts (ran, final code)

| Suite | Result |
| --- | --- |
| `-PicebergFork` module `test` | 160, 0 failed |
| `-PicebergFork` Spark 4.2 itest `extensionsTest` (new) | 10, 0 failed |
| `-PicebergFork` Spark 4.2 itest `test` | 31, 0 failed (unchanged) |
| Default capabilities module `test` | 28, 0 failed (25 before) |
| Default Spark 3.5 runtime `test`, and itest `statementTest` | 24 and 137, 0 failed |
| `spotlessCheck` and `checkstyle` on touched projects | pass |

Red first: tests only gave no grammar for `runAntlr`; the grammar alone gave 100 `compileTestJava` errors; the first
run with the code had 3 failures, all EXPLAIN quoting, then pinned. The itest tests were written after the code and
were not red first. Their first run had 2 failures, both test expectations.

## What the REST capability client (C2-3) needs from here

- Add the client to `OpenHouseCapabilities.resolve` on the `ICEBERG_REST` path, mirroring 3.5, with only Iceberg's
  relocated classes (`HTTPClient`, `AuthManagers`, `ResourcePaths`) and the capabilities module, and no Spring.
- Flip the three pins in `SparkIrcOpenHouseStatementsTest` (grants and unlock unsupported, and the resolver's
  `offered` set). `IrcExtensionsTestService` can read grants and locks back through the Java client.

## Choices where the brief left room

- The module is not published, is a plain `java-library` plus `scala` plus shadow, and has Spark and Iceberg
  compileOnly. The statement tests live in its own unit tests.
- The Spark 3.5 reference for stored policies is the Java client applying the 3.5 exec's literal patch in the service
  JVM, not a live Spark 3.5 session. The literals are copied from the 3.5 `PolicyPatchLiteralsTest`.
- `extensionsTest` runs in its own test and service JVMs, because Spark fixes extensions when the session is built.
  It is excluded from `test`, run by `check`, and listed in CI.

## Known gaps and noticed

- The Spark 3.5 message comparison came from the uncommitted probe on Hadoop-catalog tables.
- The pre-commit Spotless hook runs on the default build only, so it does not check the fork-only Spark 4.2
  projects. Run `-PicebergFork spotlessCheck` for them. A comment reflow slipped through once.

## Not run

The full build; Spark 3.5 itest `catalogTest` and `test`; `:services:tables:test`; the Java 1.5 itest; continuous
integration on pull request 101; a cold `publish-local.sh`.
