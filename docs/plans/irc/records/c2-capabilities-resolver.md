# C2-1, C2-2: OpenHouse features are capabilities found by one resolver (record)

Pull request: https://github.com/mkuchenbecker/openhouse/pull/100 (draft, into irc-integration)
Head: 864a0d9edb893e2e5fcb94a05077fea66c27560b, branch irc/c2-capabilities-resolver.
It includes a merge of origin/irc-integration at 1fa8cb34 (pull request 99).
Marks: [ran] means executed here; [read] means taken from the code.

## Bottom line

- **Module.** `integrations/java/openhouse-catalog-capabilities` (project `:integrations:java:openhouse-catalog-capabilities`) is plain Java. It has no Spring, generated client, Spark or Scala dependency. Iceberg's API is compileOnly, and the module is not published (D24).
  - `CapabilityNames`: `policies`, `grants`, `unlock`, `column-defaults`, `openhouse.capabilities`, `X-OpenHouse-Client-Capabilities`, the server and client sets, and `parse`.
  - `OpenHouseTableProperties`: `openhouse.tableId` and `updated.openhouse.policy`.
  - `GrantsCapability` and `UnlockCapability`.
  - Value types: `UnlockReason`, `UnlockTableTarget` (an interface with a validating `of`), `UnlockValidationException`, `AclPolicy`, `Principal` and `Privileges`.
- **Spark 3.5 runtime.**
  - `JavaClientCapabilities` adapts the Java client's interfaces.
  - `OpenHouseCapabilities` and `CatalogCapabilities` form the resolver. They are written in Java, so Spark 4.2 can reuse them.
  - The four `IcebergCatalogMapper` call sites use the resolver.
  - The six policy execs call `requirePolicies` before they commit.
- **Behavior.** Legacy and in-process paths: unchanged in the counts below [ran]. Plain `RESTCatalog`: policy statements need the advertised `policies` (this server advertises it after pull request 99); grants and unlock give today's "does not support" error.

## What the resolver answers, by path and capability [ran: OpenHouseCapabilitiesTest, 24 runtime tests]

| Path (how it is recognized) | policies | grants | unlock |
|---|---|---|---|
| Legacy `OpenHouseCatalog`: the Iceberg catalog implements the Java client's `SupportsGrantRevoke` / `SupportsUnlock` | offered (the catalog translates the patch) | adapter over `SupportsGrantRevoke` | adapter over `SupportsUnlock` |
| In-process `OpenHouseRestCatalog`: same interfaces, checked before any property, although it is a `RESTCatalog` | offered | adapter | adapter |
| Catalog implementing `GrantsCapability` / `UnlockCapability` itself | offered | itself | itself |
| Iceberg `RESTCatalog`: `properties().get("openhouse.capabilities")`, merged (the server override wins) | offered only when advertised; otherwise `UnsupportedOperationException` "Catalog 'x' does not support OpenHouse policies: its server does not advertise 'policies' in openhouse.capabilities (advertised: [...])" | never, even if advertised (C2-3): "Catalog 'x' does not support Grant Revoke Statements" | never: "Catalog 'x' does not support UNLOCK" |
| Other Iceberg catalog (for example Hadoop, used by the statement tests) | offered (the exec's own `openhouse.tableId` check decides, as before) | not offered, today's text | not offered, today's text |
| Not Iceberg (`SparkCatalog` / `SparkSessionCatalog` not involved) | not offered | not offered | not offered |

Each run sends the same SparkCatalog through both the cache-enabled and cache-disabled cases. The SparkSessionCatalog wrapping is tested too.

## Every statement on a plain RESTCatalog (`SparkCatalog type=rest`), before and after [ran: IcebergRestOpenHouseStatementsTest]

| Statement | Base cc76d485 (server advertised nothing; D25 refusal) | This change on the base server | This change after merging pull request 99 (server advertises `policies`) |
|---|---|---|---|
| SET POLICY RETENTION / SHARING / HISTORY / REPLICATION, UNSET POLICY REPLICATION, MODIFY COLUMN SET TAG | Reached the server; Iceberg `BadRequestException` naming `updated.openhouse.policy`; nothing stored | Fails in the client with `UnsupportedOperationException` "...does not support OpenHouse policies..."; nothing sent | Applied as a policy change; no stray key; `IcebergRestPolicySqlTest` green |
| Same, with the client-side `openhouse.capabilities=policies,...` | As above (server 400) | Reaches the server, which answers 400 (D25) | Applied (the server override wins) |
| GRANT / REVOKE on a table or a database; SHOW GRANTS on a table or a database | UOE "Catalog 'irc' does not support Grant Revoke Statements" | Same | Same |
| ALTER TABLE ... UNLOCK [REASON ...] | Disabled by default (same message); when enabled, UOE "Catalog 'irc' does not support UNLOCK" | Same | Same |

## Legacy and in-process paths unchanged [ran]

| Suite | Before (cc76d485) | After (head) |
|---|---|---|
| Spark 3.5 itest catalogTest | 247 tests, 1 skipped, 0 failed | 255 tests, 1 skipped, 0 failed. The 247 base cases match by name and status; the 8 added cases are the new pin (4) and pull request 99's IcebergRestPolicySqlTest (3) and PolicyPatchLiteralsTest (1). |
| Spark 3.5 itest statementTest | 137 / 0 failed | 137 / 0 failed, identical per case |
| Spark 3.1 itest catalogTest | 87, 1 skipped | 87, 1 skipped, identical per case |
| Spark 3.1 itest statementTest | 126 | 126, identical per case |
| Smoke s16, s17, s27 on the legacy and rest-shim paths | pass | pass (default and `-PicebergFork`) |
| New module tests | n/a | 25 / 0 failed |
| Spark 3.5 runtime unit tests (new) | none existed | 24 / 0 failed (default and fork) |

The shared statement tests ran unchanged on both lines, including UnlockTableStatementTest's `assertSame` of the analyzed target and GrantRevokeStatementTest's "does not support" text.

The final head run's catalogTest came FROM-CACHE. Its cache key matched the executed run from before the last two commits. This also shows the adapter's class bytes did not change.

## Opt-in Iceberg 1.12 build [ran]

- `publish-local.sh` ran in 35 s.
- With `-PicebergFork`, the module and runtime tests pass after commit 864a0d9e. The fake REST client needed `withAuthSession` on 1.12.
- The Spark 3.5 itest catalogTest gave 255 tests and 8 failures. All 8 are view cases in IcebergRestCatalogViewTest (5) and IcebergRestSparkSqlViewTest (3), with "Server does not support endpoint GET .../views/...".
  - The same 8 fail on the base cc76d485 and on the tip 1fa8cb34 without this change [ran]. They are already on the branch, not caused by C2, and the cause was not examined.
  - Every other case passes, including the new pin, IcebergRestPolicySqlTest and smoke s16, s17 and s27.

## Default build classpaths [ran]

- Every resolvable configuration of every project was dumped before (59 projects, 35,557 lines) and after (60 projects).
- The diff is the new project's own file plus 5 lines. All 5 are the module, in `:integrations:spark:spark-3.5:openhouse-spark-3.5-runtime_2.12` (compileClasspath, testCompileClasspath, testRuntimeClasspath, fatJarPackagedDependencies) and in `:integrations:java:iceberg-1.5:openhouse-java-iceberg-1.5-runtime` (compileClasspath).
- No other project changed. The itest gets the classes inside the uber jar.
- Script: scratchpad `c2/cp/dump.gradle`; diff: `c2/cp/diff.txt`.

## What the Spark 4.2 extensions module will need

- The resolver already avoids the 4.2 break. It uses the public `HasIcebergCatalog.icebergCatalog()` (present on 1.5.2 [read via javap] and on 1.12/4.2 [read, design]). The only reflection left is `CachingCatalog.catalog`. On 1.5.2 it returns the same object as the old reflection.
- `OpenHouseCapabilities.java` and `CatalogCapabilities.java` are Java and name only Spark's `CatalogPlugin` and Iceberg's `SparkCatalog`, `SparkSessionCatalog`, `HasIcebergCatalog`, `CachingCatalog`, `RESTCatalog` and `DynFields`. The 4.2 module can copy them as they are, minus the two lines that call `JavaClientCapabilities`, which it leaves out.
- On 3.5 the logical plans (`UnlockTable`, `ResolvedUnlockTable`), `ResolveUnlockTable` and the parser keep the Java client's `ReasonEnum` and `UnlockTableTarget`. The shared tests pin those types and the very target object. The 4.2 port should use `UnlockReason` and `UnlockTableTarget` from the module there, and port those tests with the new types.
- On 4.2 the parser also imports the generated `TimePartitionSpec` (read: `OpenhouseSqlExtensionsAstBuilder:8`), and it still needs a module type. That is not done here.
- Still open from the design: ANTLR 4.13.1, `parseRoutineParam` / `parsePlanWithParameters`, `SQLConf.get`, and the REST capability client (C2-3) for grants and unlock.

## Choices where the brief left room

1. **Where the module lives.**
   - At `integrations/java/openhouse-catalog-capabilities`, with no Iceberg-line or Scala suffix, beside the `iceberg-1.x` directories.
   - It uses its own `java-library` plus checkstyle and the same Java target (8, or 17 under the fork). I did not use `openhouse.java-minimal-conventions`, because that convention adds `api` mapstruct and servlet-api.
   - Not published.
2. **How today's catalogs meet the interfaces.**
   - Through adapters in the Spark 3.5 runtime, not by changing the Java runtime. That runtime's sources are shared with the 1.2 / Spark 3.1 line, so this keeps every classpath unchanged.
   - The module is compileOnly plus uber-jar only, as the Java client already is.
3. **`UnlockTableTarget` is an interface.** The shared UnlockTableStatementTest asserts that the analyzed target object reaches the catalog, so the adapter wraps it and unwraps it rather than rebuilding it.
4. **Privilege stays a String in `GrantsCapability`.** The grammar's column-level privilege form reaches the catalog unmapped today, so converting it earlier would move a `NoSuchElementException`. `Privileges` is in the module for C2-3.
5. **Policies on "other Iceberg" catalogs are offered.** This keeps today's behavior: the statement tests use Hadoop catalogs with `openhouse.tableId`. Only the `RESTCatalog` path is gated.
6. **Where the policy check sits.** After the exec's own table check and right before the commit, so a non-OpenHouse table keeps its message. The policy message text is new; the brief set none.
7. **`offers(x)` means "usable".** On REST, a name that is advertised but has no client yet (grants, unlock) is in `advertised()` but not in `offered()`.
8. **Spark 3.1 is left as is.** It has its own copy of the extensions, builds against Iceberg 1.2 (where `SparkCatalog` has no `icebergCatalog()` [read]) and gets no new features. It still uses `IcebergCatalogMapper`. In 3.5, `IcebergCatalogMapper` and the Scala `Principal` stay and delegate, because the 3.1 itest's `IcebergCatalogMapperTest` (shared, and without `@Test`) compiles against them.
9. **Merge handling (coordinator's note).**
   - The pin was updated in the merge commit, as the resolution of a semantic conflict, so the merge stays green.
   - `OpenHouseRestAdapter` now uses `CapabilityNames`. The module is compileOnly in the Java runtime because the constants are inlined [ran: javap shows no reference].
   - The tables service could depend on the module in the same way, since the module is plain Java with no runtime need for constants. I did not change the service, and it keeps `IcebergRestApiHandler.CAPABILITIES` and `SERVED_CAPABILITIES`. That is a follow-up.
10. **The "five call sites" are four in production code.** GRANT/REVOKE, SHOW GRANTS, the UNLOCK rule and UNLOCK's exec in the 3.5 runtime use the mapper. The fifth reference is the shared test.

## Known gaps / not verified

- **A client can claim `policies`.** A client-side `openhouse.capabilities` counts when the server sends no override, because `properties()` is the merged map.
- **The "fail loudly" case is only covered in unit tests.** It is proven with a controlled config response. The real server now advertises `policies`, so no itest can show it.
- **The 8 fork view failures are already on the branch.** They are not diagnosed.
- **Not run:** the whole `./gradlew clean build`, the services, the Java 1.2 / 1.5 itests in full (only `OpenHouseRestAdapter*` ran), the delta-harness, the fork build beyond the listed tasks, and any Spark 4.2 build.
