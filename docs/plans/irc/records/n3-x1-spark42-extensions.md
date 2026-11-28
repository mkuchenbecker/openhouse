# N3-x1 (+ N3-x2 policy part): OpenHouse SQL extensions for Spark 4.2 / Scala 2.13 (record)

- **Pull request:** https://github.com/mkuchenbecker/openhouse/pull/101 (draft, into `irc-integration`)
- **Head:** `db1dd455bcddffbcd4600719f550e7f26beb90ab`, branch `irc/n3-x1-spark42-extensions`
- **Base:** `origin/irc-integration` at 19605614. A fetch right before the push found nothing new to merge.
- **Marks:** [ran] means executed here, on JDK 17. [read] means taken from code or earlier records.

## Bottom line

**The new module.** `:integrations:spark:spark-4.2:openhouse-spark-4.2-runtime_2.13`, at `integrations/spark/spark-4.2/openhouse-spark-runtime`. It exists only under `-PicebergFork`.

**Parse and analyze on Spark 4.2 [ran].** Every OpenHouse statement:
- SET POLICY: RETENTION in every form, SHARING, HISTORY, REPLICATION;
- UNSET POLICY (REPLICATION);
- MODIFY COLUMN ... SET TAG;
- GRANT and REVOKE on a table or a database;
- SHOW GRANTS;
- ALTER TABLE ... UNLOCK [REASON].

**Execute end to end over IRC [ran].** Every policy statement runs through Iceberg's own `SparkCatalog` + `RESTCatalog`, with no OpenHouse catalog class in the engine.
- All 21 rows of the 3.5 patch table were run.
- 37 cases were applied. In every one, the stored `policies` equal what the same statement stores from Spark 3.5 with the OpenHouse catalog.
- 6 cases were refused (30d, 2m and 1y on an hour-partitioned table). They are refused with the same native message on both paths, and nothing is stored.

**Pinned as unsupported [ran].** GRANT, REVOKE, SHOW GRANTS (on a table and on a database) and UNLOCK. They resolve through the resolver and fail exactly as on Spark 3.5 over `RESTCatalog`:
- `UnsupportedOperationException: Catalog 'irc' does not support Grant Revoke Statements`
- `... does not support UNLOCK`
- with the switch off: `ALTER TABLE UNLOCK is disabled...`

This is the same on `irc_adv`, a catalog that claims `policies,grants,unlock` on the client side.

Why: a plain `RESTCatalog` has no client for the grant and lock routes yet (C2-3), and the server does not serve those routes yet (V5-3).

## Adaptations from the 3.5 sources (file: what, why)

All paths are under `src/main` of the 4.2 module. Every other ported file is byte-identical to its 3.5 counterpart.

| File | What changed | Why |
|---|---|---|
| `antlr/.../OpenhouseSqlExtensions.g4` | Unchanged. Generated with ANTLR **4.13.1** (`build.gradle`; 3.5 uses 4.7.1). | Spark 4.2 ships antlr4-runtime 4.13.1 (spark-parent `antlr4.version`) [read]. The ATN format changed after 4.9. |
| `OpenhouseSparkSqlExtensionsParser.scala` | Adds `parsePlanWithParameters`: OpenHouse statements are parsed here, everything else goes to the delegate with its parameters. | The interface's default implementation calls `parsePlan`, which would drop the parameters of delegated SQL [ran: `javap -c`]. |
| same file | Adds `parseRoutineParam`, delegated. | It is abstract in Spark 4.2's `ParserInterface` [ran: `javap`]. The exact set of new abstract or default methods is these two. |
| same file | Drops the unused `IcebergSqlExtensionsPostProcessor` import. | It does not exist on 4.2, and the class never used it. |
| `OpenhouseSqlExtensionsAstBuilder.scala` | `ReasonEnum` becomes `UnlockReason`; `TimePartitionSpec.GranularityEnum` becomes the new module type `TimeGranularity`. Uses `scala.jdk.CollectionConverters` (`JavaConversions` is gone in 2.13). | No generated client in the engine. |
| `plans/logical/UnlockTable.scala` | `ReasonEnum` and the Java client's `UnlockTableTarget` become the module's `UnlockReason` and `UnlockTableTarget`. | No Java client. |
| `analysis/ResolveUnlockTable.scala` | Uses the module's `UnlockTableTarget` and `UnlockValidationException`. Reads the switch with `SQLConf.get` instead of `spark.sessionState.conf`. | No Java client; also the design note. (`sessionState` would also have compiled: it is on the API `SparkSession` [ran: `javap`].) |
| `OpenhouseDataSourceV2Strategy.scala` | `Strategy` from `org.apache.spark.sql.classic`. Passes `ResolvedUnlockTable`'s target and reason straight to the exec, with no `JavaClientCapabilities` conversion. | Spark 4 moved the alias (as Iceberg 4.2 does) [read]. The plan already carries the module types. |
| `ShowGrantsStatementExec.scala` | `.toSeq` on the converted list. | In Scala 2.13 `Seq` is immutable, and the converted Java list is a Buffer. |
| ResolveUnlockTable, Strategy, ShowGrants, AstBuilder | `scala.jdk.CollectionConverters`. | `JavaConverters` is deprecated in 2.13. |
| `capabilities/OpenHouseCapabilities.java` | Does not ask `JavaClientCapabilities` first. The javadoc says so. | No Java client or catalog classes. |
| `capabilities/CatalogCapabilities.java` | Javadoc only. | Same reason. |
| Left out | `OpenHouseCatalog.java`, `OpenHouseRestCatalog.java`, `JavaClientCapabilities.java`, `mapper/IcebergCatalogMapper.scala`, `constants/Principal.scala` | Catalog classes and the Java client must not be in the engine. The mapper and the Scala `Principal` exist in 3.5 only for the 3.1 itest's compile. The exec nodes use the module's `Principal`. |

**Unchanged on 4.2 [ran, compiled].** The `OpenhouseParseException(msg, Some(line), Some(pos))` constructor form, `AnalysisException(..., cause = Some(cause))`, `LeafV2CommandExec`, `LeafCommand`, `ParserUtils.string`, and `SparkTable`.

**Capabilities module.** New `TimeGranularity` (HOUR, DAY, MONTH, YEAR, with `getValue` and `fromValue`), plus its test (3 tests).

**Test adaptations.**
- The capabilities module's types replace the Java client's.
- Lombok is dropped: `throws Exception` instead of `@SneakyThrows`.
- `SparkSession.close()` declares `IOException` on Spark 4.
- The legacy and in-process resolver cases are dropped. The case for a catalog implementing the interfaces now runs with and without the cache.
- `SetRetentionTimeZoneStatementTest` is not ported: it needs the service. Its client-side checks are covered in `UserVisibleOutcomesTest`.

## Every user-visible difference from Spark 3.5 for the same statement [ran: a scratch probe, not committed, ran the same 35 statements on 3.5 (Spark 3.5 itest JVM) and on 4.2; results diffed]

1. **Spark's own messages gain a SQLSTATE suffix**, for example ` SQLSTATE: 42601`, `42P01` or `XX000`. This affects:
   - the parse error for a statement the extensions do not route (`GRAN SELECT ...`);
   - `TABLE_OR_VIEW_NOT_FOUND`, whose message also gains a `Search path: [`db`].` line;
   - `INTERNAL_ERROR` "planning failed" for an OpenHouse statement on a non-Iceberg catalog. That last case is the same `SparkException` with an `AssertionError` cause on 3.5.

   These are pinned in `UserVisibleOutcomesTest.sparkOwnErrorsCarryTheSqlState`.
2. **EXPLAIN quotes digit-leading identifier parts.** The exec line shows `` `0_`.`0_` `` where 3.5 shows `0_.0_`, because of Spark 4's `Identifier.toString`. Pinned in three ported statement tests.

**Identical, class and full message:**
- every OpenHouse parse error (ANTLR 4.13.1 words them as 4.7.1 does);
- the HISTORY, lock-reason and time-zone checks;
- the "non-Openhouse table" refusals (all six);
- grants and unlock "does not support";
- UNLOCK disabled;
- the SHOW GRANTS columns (`privilege`, `principal`) and its rows.

**A refused policy over IRC.** It is `org.apache.iceberg.exceptions.BadRequestException: Malformed request: <native message>`, from 4.2 as from 3.5 over IRC. Through the OpenHouse catalog it is the Java client's exception carrying the response body. That is a difference between paths, not between Spark lines. Pinned.

## Dependencies [ran]

- **`dependencies` for the module:**
  - `compileClasspath` is exactly `spark-sql_2.13:4.2.0`, `iceberg-spark-runtime-4.2_2.13:1.12.0-ohfork.28742df3e538` and `:integrations:java:openhouse-catalog-capabilities`.
  - `runtimeClasspath`, `runtimeElements` and `shadowRuntimeElements` have no dependencies.
  - `fatJarPackagedDependencies` is only the capabilities module.
  - No Spring and no OpenHouse project other than the capabilities module appear in any configuration.
- **Jar inspection** of `openhouse-spark-4.2-runtime_2.13-uber.jar` (285 KB):
  - The only classes are `com/linkedin/openhouse/{capabilities, spark/capabilities, spark/extensions, spark/sql/...}`.
  - A byte grep of every class for `org/springframework`, `com/linkedin/openhouse/javaclient`, `.../gen/`, `.../tables/client` and `.../spark/OpenHouse` finds nothing.
- **In the Spark 4.2 JVM:** `SparkJvmClasspathTest`. See "Wording of the classpath assertion" below.

## Default build untouched [ran]

- **Method:** an init script dumped, for all 60 projects, every resolvable configuration's resolved artifacts and every source set's directories (scratchpad `x1/cp/dump.gradle`). It ran before any change and again at the final code.
- **Result:** 35,818 lines each, and `diff -r` is empty, including the capabilities module, whose dependencies did not change.
- `settings.gradle` includes the new project only `if (icebergFork)`.
- `publish-local.sh` is unchanged: the already published Spark 4.2 runtime is all the module needs.

## Wording of the classpath assertion (deliberate change)

**What existed.** `SparkIrcReadTest.theSparkJvmHasNoOpenHouseCatalogClasses` names four catalog, client and service classes. It stays unchanged, and it is still true.

**New: `SparkJvmClasspathTest`, class javadoc:**

> "The Spark 4.2 JVM has no OpenHouse catalog or client class. The OpenHouse SQL extensions jar is OpenHouse code and is on the classpath, so the check is no longer 'nothing of OpenHouse': every OpenHouse class on the classpath is in that jar (the extensions and the capabilities module) or is one of these tests, and none of them is a catalog, an OpenHouse client, the generated client, or the service. Nothing of Spring is on the classpath at all."

Its three tests check:
1. The only jar holding `com/linkedin/openhouse` or `org/springframework` classes is the extensions uber jar, and that jar holds only the extensions packages. Directories hold only `spark42` test classes.
2. No such class is an Iceberg `Catalog` or a Spark `CatalogPlugin`.
3. Eight named classes do not load: the Java client catalog, `SupportsGrantRevoke`, both Spark catalogs, the generated model, `TableApi`, the IRC controller and Spring `WebClient`.

`IrcSpark`'s comment ("Nothing of OpenHouse is configured or on the classpath") and the itest's `build.gradle` header were reworded the same way.

## Test counts [ran, final code]

| Suite | Result |
|---|---|
| `-PicebergFork :...:openhouse-spark-4.2-runtime_2.13:test` | **160, 0 failed**. GrantRevoke 35, Unlock 21, Dispatch 21, QuotedIdentifier 23, UserVisibleOutcomes 21, OpenHouseCapabilities 12, SetSharing 8, SetColumnPolicyTag 8, SetTablePolicy 4, SetTableReplication 4, SetHistory 2, PolicyPatchLiterals 1. |
| Red first | With tests only: `runAntlr` had no grammar. With the grammar alone: `compileTestJava` gave 100 errors. First run with the code: 139 tests, 3 failed, all the `0_.0_` EXPLAIN quoting, then pinned. |
| `-PicebergFork :...:openhouse-spark-4.2-itest:extensionsTest` (new) | **10, 0 failed**. Policy statements 2 (43 outcomes in one test), OpenHouse statements 5, classpath 3. First run: 2 failures, both test expectations: the jar has no version in its name, and the IRC message has the `Malformed request: ` prefix and names its own table. Fixed in the tests. |
| `-PicebergFork :...:openhouse-spark-4.2-itest:test` | **31, 0 failed** (unchanged) |
| `-PicebergFork assemble testClasses` | pass |
| Default `:integrations:java:openhouse-catalog-capabilities:test` | **28, 0 failed** (25 before, plus 3) |
| Default Spark 3.5 runtime `test` | **24, 0 failed** |
| Default Spark 3.5 itest `statementTest` (`-x catalogTest`) | **137, 0 failed** (same as C2) |
| `spotlessCheck` and `checkstyle` on the touched projects | pass |

The itest tests were written after the module code. They are end-to-end proof and were not red against missing code.

## What the REST capability client item (C2-3) will need from this module

- Add the client to `OpenHouseCapabilities.resolve` on the `ICEBERG_REST` path, the same way as in 3.5. The 4.2 copy is the same file minus the Java client lines, so the change mirrors directly.
- The client must use only Iceberg's relocated classes (`HTTPClient`, `AuthManagers`, `ResourcePaths`) from `iceberg-spark-runtime-4.2_2.13`, plus the capabilities module. It must not add Spring.
- If it lives in the capabilities module, that module must stay compileOnly against Iceberg.
- Flip the pins:
  - `SparkIrcOpenHouseStatementsTest.grantStatementsAreUnsupportedUntilTheCapabilityClientLands`
  - `...unlockIsUnsupportedUntilTheCapabilityClientLands`
  - `theResolverReadsTheServersAdvertisedCapabilities`: `offered` becomes `{policies, grants, unlock}` once the server advertises them.
- The control API in `IrcExtensionsTestService` can read grants and locks back through the Java client, for equivalence checks.
- `UserVisibleOutcomesTest` keeps the Hadoop-catalog pins, which do not change.

## Choices where the brief left room

1. **Module path and name:** `spark-4.2/openhouse-spark-runtime`, named `openhouse-spark-4.2-runtime_2.13`, parallel to 3.5.
   - Not published.
   - Plain `java-library` + `scala` + shadow, not `openhouse.java-minimal-conventions`, which adds mapstruct and servlet-api.
   - The root build's forces are cleared on the compile and test classpaths, as in the 4.2 itest.
   - Spark and Iceberg are compileOnly. The uber jar holds this module and the capabilities module.
2. **Test location:** the statement tests moved into the module's own unit tests (package `statementtest`, kept parallel to 3.1/3.5), not into the itest.
3. **The reference for "what Spark 3.5 stores":** the Java client applies the 3.5 exec's literal patch in the service JVM. That is exactly the 3.5 exec's commit on the OpenHouse catalog, without running Spark 3.5. The literals are copied from the 3.5 itest's `PolicyPatchLiteralsTest`, and the 4.2 module's port of that test pins that the 4.2 execs write the same strings.
   - Statements: the 21-row table, on a partitioned and an unpartitioned table, with and without `WITH_POLICIES` as in `IcebergRestPolicySqlTest`.
   - Sharing: the table's `TRUE`, `true` and `FALSE` instead of that test's `false`.
4. **A separate test JVM and service JVM** (`extensionsTest`, excluded from `test`, run by `check` and listed in CI). Spark fixes extensions when the session is built, and `IrcSpark` (the other agent's file) builds a session without them. So I did not touch that session, and `test --tests ...` runs stay as fast as before.
5. **The service gained a control API** (JDK `HttpServer` on loopback) in a new main class, `IrcExtensionsTestService`. It reuses `IrcReadTestService.DenyStrangerConfig` and the constants. `IrcReadTestService` is not edited.
6. **A module type was added** (`TimeGranularity`) rather than string literals.
7. **"Not an OpenHouse table" means an Iceberg Hadoop table without `openhouse.tableId`** in the same session. Every IRC table is an OpenHouse table. A non-Iceberg catalog is pinned in the unit outcomes test.
8. **Commit trailer:** the brief asked for `Co-Authored-By: Claude Fable 5.2`, but this session's system attribution instruction says `Claude Opus 5.5 <noreply@anthropic.com>`, and that instruction states it replaces earlier guidance. I used Opus 5.5 in every commit. Say if it should be the other line; it can be fixed at merge time, since the merge commit is built by hand.

## Stopped on / noticed

- Nothing blocked the work.
- **The pre-commit hook runs Spotless on the default build only**, so it does not check the fork-only Spark 4.2 projects. A comment reflow slipped through and was fixed in db1dd455. Check those projects with `-PicebergFork spotlessCheck`.
- `client:common:setUp` downloads the openapi jar with curl and got HTTP 429 once. I copied the same `openapi-generator-cli-5.3.0.jar` from `/home/user/oh-c2/build/openapi-cli` into this worktree's `build/`.
- The Spark 3.5 runtime's unit-test classpath cannot raise Spark errors: `ErrorClassesJsonReader` fails there because of the root Jackson force. Its existing tests never raise one. The 3.5 probe therefore ran in the 3.5 itest JVM.
- The published Iceberg fork repo was copied from `/home/user/oh-c2/build/iceberg-fork/repo` (same pinned commit, both markers).

## Merge commit (ready to use)

Title: `Merge N3-x1: OpenHouse SQL extensions for Spark 4.2; policy statements work over IRC (#101)`

Why: The owner's target platform is Spark 4.2 on Iceberg 1.12, talking to OpenHouse over the Iceberg REST Catalog only, with what users type in Spark SQL unchanged. The OpenHouse SQL extensions existed only for Spark 3.5 and depended on the Spring-based Java client. This ports them to Spark 4.2 / Scala 2.13 on the capabilities module, and proves that the policy statements work from Spark 4.2 through Iceberg's own SparkCatalog and RESTCatalog with no OpenHouse catalog class in the engine.

Leaves true: Under -PicebergFork, :integrations:spark:spark-4.2:openhouse-spark-4.2-runtime_2.13 builds the OpenHouse SQL extensions for Spark 4.2. It depends only on Spark, Iceberg's Spark 4.2 runtime and the capabilities module, and its uber jar holds only its own classes and the capabilities module's. Every OpenHouse statement parses and analyzes on Spark 4.2. Every SET/UNSET POLICY form and MODIFY COLUMN SET TAG executes over a plain RESTCatalog and stores what Spark 3.5 with the OpenHouse catalog stores. GRANT, REVOKE, SHOW GRANTS and UNLOCK fail with the Spark 3.5 "does not support" messages, pinned until the REST capability client lands. Every message from the extensions is the same as on Spark 3.5; Spark's own messages gain a SQLSTATE suffix, and EXPLAIN quotes digit-leading identifiers. The Spark 4.2 JVM has no OpenHouse catalog or client class and no Spring. The capabilities module gains TimeGranularity. The default build is unchanged.

Evidence: Runtime module: 160 tests, 0 failed (red first: no grammar, then 100 compile errors, then 3 EXPLAIN-quoting failures that are now pinned). Spark 4.2 itest extensionsTest: 10 tests, 0 failed. It covers 43 policy outcomes against a Java-client reference (37 equal stored policies, 6 equal native refusals), the unsupported pins, the resolver's view and the classpath checks. Spark 4.2 itest test: 31, 0 failed. -PicebergFork assemble testClasses passes. Default build: capabilities 28, Spark 3.5 runtime 24, Spark 3.5 itest statementTest 137, all 0 failed. The resolved-classpath and source-set dump of all 60 default projects is identical before and after (35,818 lines). Jar inspection and the dependency listing show no Java client, generated client, Spring or OpenHouse catalog class.

Known gaps: GRANT, REVOKE, SHOW GRANTS and UNLOCK do not work over RESTCatalog until the REST capability client (C2-3) and the server routes (V5-3) land. The Spark 3.5 comparison of messages came from a scratch probe on Hadoop-catalog tables, not committed. The Spark 3.5 reference for stored policies is the Java client applying the 3.5 exec's literal patch in the service JVM, not a live Spark 3.5 session. The pre-commit Spotless hook does not cover the fork-only projects. The full build, the Spark 3.5 catalogTest and the services' tests were not run.

Commits:
- 5068e496 test: the capabilities module names the policy time granularities. The 4.2 parser needs the granularity spelling without the generated client.
- dc5d8eea feat: TimeGranularity in the capabilities module. It is the missing Spring-free value type.
- 674e944a test: the OpenHouse statement tests on a Spark 4.2 extensions module. The port must behave as 3.5; red with no sources.
- 01c2c8f9 test: pin what a Spark 4.2 user sees from the OpenHouse statements. Differences from 3.5 must be visible, not hidden.
- 594773b8 feat: the capability resolver on Spark 4.2. One resolver without the Java client, and no cacheEnabled reflection.
- d7cd4084 feat: the OpenHouse SQL extensions on Spark 4.2 / Scala 2.13. Users type the same statements on 4.2.
- 12775e43 test: OpenHouse policy statements work from Spark 4.2 over IRC. The end-to-end proof, plus pins that fail on purpose.
- 7f1a1bb6 ci: run the Spark 4.2 SQL extensions' tests in the opt-in job. The job lists its suites explicitly.
- db1dd455 style: format IrcSpark's reworded class comment. The hook does not check fork-only projects.

Decision record: docs/plans/irc/22-decisions-continued-2.md (D23, D24, D26) and docs/plans/irc/16-decisions-continued.md (D18) on branch claude/openhouse-rest-v3-planning-g42c0h of mkuchenbecker/openhouse.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01T3dbirnudathz3xU8K18AE

## Not run

- The full build.
- Spark 3.5 itest `catalogTest` and `test`.
- `:services:tables:test`, on either build.
- The Java 1.5 itest.
- CI on pull request 101.
- A cold `publish-local.sh` (unchanged).
- The 4.2 module in the default build: it does not exist there.

No JVM of mine was left running (checked: no IrcExtensionsTestService or IrcReadTestService processes).
