# i6: opt-in `-PicebergFork` build made green, on the fork's openhouse-1.12.0 tip

Labels: **[ran]** = I ran it here (logs under `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/i6c/`). **[read]** = I read the code but did not run it.

## Bottom line

**Default build: same results as the base, plus the new tests [ran].** Base is cd47695f, the head of PR 87. Counts are tests / failures.

| task | base | after |
|---|---|---|
| tables | 1268 / 0 | 1268 / 0 |
| internalcatalog | 319 / 0 | 319 / 0 |
| htscatalog | 20 / 0 | 20 / 0 |
| spark-3.5 catalogTest | 208 / 0 (1 skipped) | 213 / 0 (1 skipped) |
| spark-3.5 statementTest | 137 / 0 | 137 / 0 |
| spark-3.5 test | 124 / 0 | 124 / 0 |
| java-iceberg-1.5 itest | 129 / 0 | 133 / 0 |

- The count increases are only my new tests:
  - +5 in `ColumnDefaultsCapabilityByIcebergLineTest`
  - +3 in `IcebergLineTest`
  - +1 in `testDoRefreshOverlaysStampedDefaultOnLegacyLineOnly`
- I compared two dumps against the base, and both are identical:
  - the resolved-classpath dump: 59 projects, 32,715 lines, using the earlier report's method
  - a source-set directory dump
- `assemble testClasses spotlessCheck` passes.

**Opt-in build: every listed suite passes except 2 tests [ran].**

| task | tests / failures |
|---|---|
| tables | 1268 / 0 |
| internalcatalog | 319 / 0 |
| htscatalog | 20 / 0 |
| spark-3.5 catalogTest | 213 / **2** (1 skipped) |
| spark-3.5 statementTest | 137 / 0 |
| spark-3.5 test | 124 / 0 |
| java-iceberg-1.5 itest | 133 / 0 |

- Both failures are seam-on-1.12 findings: `RestShimPathSparkSqlSmokeTest.s18_views_createSelectDrop` and `s28_views_disabledByDefault`. They are explained below.
- Before this branch, the base with the new pin did not compile its Iceberg 1.5 Java runtime under the switch. So no 1.5-line Java or Spark itest could run. tables, internalcatalog and htscatalog passed there.

**PR:** https://github.com/mkuchenbecker/openhouse/pull/91. It is a draft into `irc-integration`, branch `irc/i6-optin-green`, and sits on top of PR 87.

## How the Iceberg line is detected, and why

- **Mechanism.** `com.linkedin.openhouse.javaclient.IcebergLine` (enum `LEGACY` / `V3`) lives in the shared Java runtime sources. It asks the Iceberg on the classpath, once, to build format v3 table metadata in memory:
  - it calls `TableMetadata.newTableMetadata(..., {format-version=3})`, with a location that has no file system
  - LinkedIn's 1.2.0.25 and 1.5.2.24 throw `IllegalArgumentException: Unsupported format version: v3`, so the line is LEGACY
  - 1.12 builds the metadata, so the line is V3
- **Why this mechanism:**
  - It is the property the decision rests on: native defaults need v3, and 1.5.2 cannot build v3.
  - It uses only public API that exists on all three lines: no reflection, no parsing of version strings.
  - No system or catalog property is involved, so a user cannot set it by accident.
  - It detects the Iceberg that runs, not the one the client was compiled against.
- **How it is tested.** `IcebergLineTest` checks the detection against an independent oracle, `IcebergBuild.version()`: 1.2.x and 1.5.x must give LEGACY, 1.12 and later must give V3. [ran] It passes on the 1.2 itest, the default 1.5 itest and the fork 1.5 itest.
- **Shared helper.** All per-line tests use this same class as their single helper.

## What changed (15 commits on top of cd47695f; each commit body says why)

1. **Pin bump** to 28742df3e538.
   - Avro, ORC and Caffeine are unchanged in the fork's `libs.versions.toml`.
   - [ran] `publish-local.sh` published `1.12.0-ohfork.28742df3e538` in 1m43s.
   - [ran] `iceberg-core` resolves at that version.
2. **`IcebergLineTest`** (red by itself), then **`IcebergLine`**.
3. **Seam compile fixes for 1.12.** These were needed to get any result at all.
   - In `OpenHouseRestAdapter`, three things did not compile on 1.12:
     - `RESTUtil.NAMESPACE_SPLITTER` is gone.
     - `RESTUtil.decodeNamespace(String)` now takes a separator.
     - `CatalogHandlers.loadTable` now takes a `SnapshotMode`.
   - The fix: the adapter splits on U+001F and `%1F` itself, and loads tables as 1.5.2 did. That is the same as 1.12's ALL mode.
   - `OpenHouseRestAdapterTest` used `AddSchema(Schema,int)`, which 1.12 does not have. It now builds that update through `MetadataUpdateParser`.
4. **I6g / D15** (build).
   - Under the switch, `settings.gradle` leaves out:
     - `:apps:spark`
     - `:integrations:java:iceberg-1.2:*`
     - `:integrations:spark:spark-3.1:*`
     - `:tables-test-fixtures:tables-test-fixtures-iceberg-1.2`
   - The Java 1.5 runtime and itest, the Spark 3.5 itest, the 1.5 fixtures and apps/spark-3.5 now name the reused directories directly under the switch. Without it they keep the old code path.
   - `services:tables` tests use the 1.5 fixtures under the switch.
   - **`libs:datalayout`** is built on Spark 3.5 and the fork under the switch, because apps/spark-3.5 ships it.
     - Its `DataCompactionConfig` now writes four bin-pack defaults as literals (0.75, 1.8, 5, `Integer.MAX_VALUE`), because 1.12 removed `BinPackStrategy`.
     - The values equal 1.12's `SizeBasedFileRewritePlanner` constants. javac already inlined them before.
     - This is a small source change outside the brief's list. It was required, because datalayout depends on the Spark 3.1 runtime project.
   - Proofs [ran]:
     - Default build: classpath and source-directory dumps are identical to the base.
     - Opt-in build: 51 projects, and every recorded configuration resolves with 0 failures. The default build has pre-existing unresolvable configurations, which are unchanged.
     - Opt-in build: the only `com.linkedin.iceberg` versions are the fork's, plus `iceberg-aws:1.2.0.6`, which is unchanged and present in both builds.
     - Opt-in build: every remaining project has the same source directories as in the default build, and `assemble testClasses` succeeds.
   - `dockerPrereqs` does not work under the switch.
5. **W3 tests, then W3 code.**
   - Code:
     - `OpenHouseTableOperations.loadMetadata` still decodes the read-bridge config, so a malformed entry still fails. On V3 it does not overlay.
     - The Spark 3.5 `OpenHouseCatalog.capabilities()` declares `column-defaults` only on LEGACY.
     - The Spark `OpenHouseRestCatalog` inherits this through its backing catalog.
     - The Spark 3.1 catalog is unchanged.
   - Tests:
     - The 5 `ColumnDefaultSparkITest` cases keep their legacy assertions. On V3 they assert the 422 on creating the bridged table. None is skipped.
     - `CatalogPluginContractTest.capabilityMismatch`, which runs on both paths: on V3 it asserts that the engine catalog's create gets the 422, then sets up with a catalog that declares the capability explicitly.
     - New `ColumnDefaultsCapabilityByIcebergLineTest`:
       - It checks the header on both Spark catalog paths.
       - For a bridged table created by a session that declares the capability through `header.X-OpenHouse-Client-Capabilities`, SELECT, INSERT and ALTER ADD COLUMN work on the legacy line and get the 422 on V3. On V3 the table is left untouched.
       - CREATE with the opt-in property works on legacy and gets the 422 on V3.
       - A plain table works on both lines.
   - [ran] Red and green:
     - Before the code, the opt-in build had the 11 W3 failures: ColumnDefault 5, the new class 4, contract 2. The 1-test Java overlay check also failed.
     - On the default build the same tests passed before the code.
     - After the code, all pass on both builds.
6. **A test fix of my own.** At first my new test class left its SparkSession open. That broke `DeleteFileReplicationTestSpark.testDeleteFileReplicationFromSessionConf` on both builds: with an open session, Spark ignores a static `fs.file.impl` setting. The class now closes its sessions.
7. **Adapter: header-returning `get`/`post` overloads**, without `@Override` so the code still compiles on 1.5.2.
   - 1.12's `RESTSessionCatalog` asks for response headers, for example the ETag of a table load. `RESTClient`'s default for those overloads throws "Returning response headers is not supported".
   - The adapter now answers with no headers.
   - Before this, 43 of the 51 rest-shim tests failed.
8. **W4.** `tableWithSchema` builds format v3 on V3 and v2 on LEGACY. The assertions are unchanged.
9. **I6h / D14.**
   - RTAS on V3: the test now asserts that the RTAS succeeds, and that the result has only the `data` column, is unpartitioned and has 3 rows.
   - s19 CREATE NAMESPACE: message per line.
   - s24 DROP NAMESPACE: message per line. I extended D14b to this statement myself: same cause (apache/iceberg#14507), same exception class. s24 did not exist on the branch D14 was decided from. Please confirm this extension.
10. **Docs:** `docs/development/iceberg-fork.md`.

## Opt-in failures, classified

| test | cause | class |
|---|---|---|
| RestShimPathSparkSqlSmokeTest.s18_views_createSelectDrop | 1.12's `RESTSessionCatalog` checks the endpoints the server advertised. The adapter's CONFIG response advertises none, so the client gets the default set, which has no views: `UnsupportedOperationException: Server does not support endpoint: POST /v1/{prefix}/namespaces/{namespace}/views` | seam-on-1.12 finding, not fixed |
| RestShimPathSparkSqlSmokeTest.s28_views_disabledByDefault | same check: `UnsupportedOperationException` where today's path gives `NoSuchNamespaceException [SCHEMA_NOT_FOUND]` | seam-on-1.12 finding, not fixed |

**What fixing them would take:** the adapter's config response would have to list its endpoints, including the view routes. `ConfigResponse.Builder.withEndpoints` / `Endpoint` exist on 1.12 but not on 1.5.2, so this needs version-specific code or reflection. That is beyond a trivial incompatibility.

**Earlier failures, now passing, by class:**
- **Fixed here:**
  - seam compile (3 APIs)
  - adapter test compile
  - response-header overloads (43 tests)
  - my own session leak
- **Deliberate differences, now expected per line:**
  - D11a / W3: ColumnDefaultSparkITest ×5, contract capabilityMismatch ×2
  - W4 / D5: OpenHouseTableOperationsTest ×3
  - D14a: RTAS ×1
  - D14b: s19 ×1 on the legacy path; s24 DROP NAMESPACE ×1 is my extension of D14b
- **Seam-on-1.12 results [ran]:**
  - rest-shim contract suite: 20/20 pass after the two trivial fixes
  - rest-shim smoke suite: 29/31 pass, including s19 and s24 with the 1.12 message
  - rest-shim path tests in the Java itest: pass

## Spark SQL behavior differences between the builds (all observed [ran])

1. **Tables with `required-capability.column-defaults=true`.** SELECT, INSERT, ALTER TABLE ADD COLUMN and CREATE TABLE with that property: they work on 1.5.2 (ORC defaults filled); on 1.12 they get 422 `CLIENT_CAPABILITY_MISSING: <db.table> requires the column-defaults capability ...`, raised as `WebClientResponseWithMessageException`. Decisions D5 and D11a.
2. **REPLACE TABLE ... AS SELECT that drops a former partition source column** (with `replace.partition-column-drop.enabled=true`): fails Iceberg validation on 1.5.2 and succeeds on 1.12. Decision D14a.
3. **CREATE NAMESPACE and DROP NAMESPACE:** `UnsupportedOperationException` on both. The message is "Describing database is not supported" on 1.5.2 and "Checking if database exists is not supported" on 1.12. Decision D14b. DESCRIBE NAMESPACE, ALTER NAMESPACE and SHOW NAMESPACES keep their messages.
4. **Views on the REST-shaped path** (`catalog-impl=OpenHouseRestCatalog`): CREATE VIEW fails on the client on 1.12 with "Server does not support endpoint ...", whether views are enabled or disabled. On 1.5.2 the request reaches the catalog. Seam finding.
5. **Not SQL output, but observable by the service:** on 1.12 the Spark catalogs send no `X-OpenHouse-Client-Capabilities` header.
6. **Checked and found no difference:**
   - new-table property keys (s21)
   - DESCRIBE output
   - every other smoke and contract scenario, on both paths
   - plain CREATE / INSERT / ALTER
   - the create request body (shown byte-identical by the earlier report)
7. **Not done:** I did not diff the output of every Spark statement outside what the suites assert.

## Findings outside the brief

- **Jobs client still declares the capability.** `apps/spark` `TablesClientFactory` declares `column-defaults` unconditionally, on both lines. On 1.12 the scheduler could schedule maintenance for bridged tables that the 1.12 Spark catalog will then be refused. I left it unchanged (apps tests were not in scope). [read]
- **`DeleteFileReplicationTestSpark` is order-dependent.** It relies on no SparkSession being open when it starts. Its javadoc names a dedicated `deleteFileReplicationTest` task that does not exist in the build file. [read, and observed]
- **Memory kills.** Two Gradle daemons were OOM-killed during the full runs because the shared cgroup was at its limit.
  - I re-ran each task alone with `--no-daemon` (and `-Dorg.gradle.daemon.idletimeout=60000`); every task then completed.
  - Two orphaned test-worker JVMs from my killed runs (spark-itest and internalcatalog, parent PID 1, cwd in `/home/user/oh-i6c`) were still holding about 2 GB at the time. Stopping them was refused by the permission classifier, so they are still there.
- **Pre-push hook.** It runs `spotlessCheck` through Gradle. A push made while my suite was running failed, and passed once the suite was idle.

## Not run / not done

- `libs:datalayout` and `apps:spark-3.5` tests under the switch. I ran only compile and classpath resolution for them.
- housetables, jobs and Docker tests on either build.
- The 1.2 / Spark 3.1 tests. They are excluded under the switch, and I did not re-run them on the default build beyond `IcebergLineTest` on the 1.2 Java itest [ran].
- Spark 4.2.
- No CI action, no merge, no PR subscription.
- The worktree `/home/user/oh-i6c-base` (detached at cd47695f, with an uncommitted pin edit) is mine and was used only for the baseline. It can be removed.
