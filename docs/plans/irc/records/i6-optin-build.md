# i6: opt-in `-PicebergFork` build of OpenHouse's Iceberg 1.5 variant on the fork's 1.12 line

Labels: **[ran]** I ran it here; logs and dumps are under `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/i6b/`. **[inferred]** I read code but did not run it.

## Bottom line

- **Default build unchanged [ran].**
  - Baseline = `origin/irc-integration` (bb8929e1) plus the two merged test branches (merge commit 361bc090).
  - Resolved artifacts of every resolvable classpath configuration in all 59 projects are byte-identical before and after (32,715 lines, `diff -r cp-base.d cp-final.d` is empty).
  - Compiled `.class` hashes differ only in the files I edited (listed under Evidence).
  - Default compile (`assemble testClasses`) and tests give the same results as the baseline. The only change is the 1.5 Java itest, which goes from 121 to 124 tests because I added 3.
- **Opt-in build compiles everything [ran].** `-PicebergFork assemble testClasses` succeeds for all 171 tasks, including the 1.2 / Spark 3.1 modules.
- **Opt-in test counts, as tests / failures [ran]:**

| task | tests | failures |
|---|---|---|
| tables | 1205 | 0 |
| internalcatalog | 319 | 0 |
| htscatalog | 20 | 0 |
| spark-3.5 itest catalogTest | 145 (1 skipped) | 7 |
| spark-3.5 itest statementTest | 137 | 0 |
| spark-3.5 itest test | 124 | 0 |
| java-iceberg-1.5 itest | 124 | 3 |

- **10 failures remain [ran]. All are Iceberg-side behavior differences; none is an OpenHouse defect.**
  - 8 are the v2 column-default rejection (item 5).
  - 1 is the RTAS case (item 5).
  - 1 is outside item 5: a `CREATE NAMESPACE` error message, Iceberg change #14507.
  - I left all 10 untouched and failing.

## Pull requests

- OpenHouse (draft, into `irc-integration`): https://github.com/mkuchenbecker/openhouse/pull/87
  - Branch `irc/i6-iceberg-1.12-optin`.
  - The PR body says the diff includes `irc/t1-smoke-suite` and `irc/t3-plugin-tests` because they were merged in.
- Iceberg fork (draft, into `irc/reapply-all`): https://github.com/mkuchenbecker/iceberg/pull/9 "Build: Allow overriding group and version when publishing"
  - Branch `irc/publish-plumbing` = f180ae589 + 6d9af8280.
  - Adds `-PpublishGroup` / `-PpublishVersion`. The Apache defaults are unchanged and no LinkedIn coordinates are added in the fork.
  - An edit was needed because `groupId` was hard-coded twice and the version came only from `version.txt` or git.
  - I did not act on Iceberg CI.

## What was built

- **Pin.** `scripts/iceberg-fork/iceberg-fork.properties` holds:
  - `repoUrl`
  - `commit` = 6d9af8280912… (the plumbing branch tip)
  - `group`, `baseVersion`
  - the Avro 1.12.2, ORC 1.9.9 and Caffeine 3.2.4 pins, taken from the fork's `libs.versions.toml`
- **Version.** `1.12.0-ohfork.6d9af8280912`. Four-part numeric LinkedIn versions cannot collide with it.
- **Script.** `scripts/iceberg-fork/publish-local.sh`:
  - shallow-fetches the commit by SHA into `build/iceberg-fork/src`
  - publishes these modules with `publishToMavenLocal -Dmaven.repo.local=build/iceberg-fork/repo`: api, bundled-guava, common, core, data, parquet, orc, bom, spark-runtime-3.5_2.12
  - writes a marker file, and skips the publish when the marker exists
  - It never touches `~/.m2` and publishes nothing remotely. No binaries are committed.
- **Switch.** One property, `-PicebergFork`. `gradle/iceberg-fork.gradle` is applied only when it is set. It:
  - sets `iceberg_1_5_version`
  - adds an `exclusiveContent` repository filtered to exactly `com.linkedin.iceberg` at the fork version
  - fails fast if nothing is published yet
- **Guarded spots.** Each of these checks the same property:
  - root forces: the ORC/Avro forces are swapped for the pins, and Caffeine is pinned
  - `java-minimal-conventions`: Java 17 target
  - `iceberg-conventions-1.5.2`: `runtimeOnly` iceberg-parquet/orc
  - spark-3.5 itest and apps/spark-3.5: exclude thin iceberg-orc/parquet, because they clash with the shaded runtime
- **Docs.** `docs/development/iceberg-fork.md`, 50 lines.

## Source changes that compile on both lines (no switch-selected source sets were needed)

- **`addSchema(Schema,int)`** becomes `setCurrentSchema(Schema,int)`, which exists in 1.2.0.25, 1.5.2.24 and 1.12 (checked with javap).
  - Where a step only added a schema, a following `setCurrentSchema(previousId)` restores the current schema, so the metadata is the same as before.
  - Files: `SparkMultiSchemaEvolutionTest` and the shared `ReadBridgeTest`, which now compiles on 1.2 too.
- **`GenericAppenderFactory`** is replaced by the exact writer builders it used internally: `Avro.write` / `Avro.writeDeletes` in htscatalog `IcebergDataUtils` (main code), and `ORC.writeDeletes` in `SparkMoRFunctionalTest`.
  - These builders exist on 1.5.2 and 1.12.
  - `GenericFileWriterFactory` was not an option: it is package-private in 1.5.2.
  - htscatalog runs only inside housetables, which is on the 1.5 line.
- **`TablesValidatorTest`.** The `com.sun...xalan...Constants.CHARACTERS` import is replaced by an inline constant with the same value, `"characters"`. That is valid on Java 8 and 17.

## Runtime fixes, test-first [ran]

1. **HadoopFileIO with a null configuration.**
   - Test: `OpenHouseCatalogFileIOTest`. It checks that the catalog initializes without `setConf`, and that it uses a given configuration.
   - Before the fix, under the switch: `initializesWithoutAHadoopConfiguration` failed with an NPE in `SerializableConfiguration`, and 23 `SmokeTest` cases failed the same way.
   - Fix: when `conf == null`, use `new HadoopFileIO()`. That is what `CatalogUtil.loadFileIO` does for a null conf.
   - After the fix: all of those pass under the switch. On the default build the tests pass before and after.
   - On 1.5.2 with a null conf, the no-arg constructor still fails on first file access, as before; only the exception site differs [inferred]. Spark always calls `setConf`.
2. **Null table location on CREATE.**
   - What the server does with a client location [ran + read]:
     - The client never sends one. `CreateUpdateTableRequestBody` has no location field, and `constructMetadataRequestBody` does not read `metadata.location()`.
     - The server allocates the location itself (`storageSelector…allocateTableLocation` in `OpenHouseInternalRepositoryImpl`).
   - Test: `OpenHouseCatalogCreateTableTest`, against a mock Tables service. It checks that:
     - the PUT body is identical with and without `withLocation("file:/tmp/client-chosen-location")`
     - the body contains no "location" at all
     - `table.location()` is the service-assigned location
   - Before the fix, under the switch: `IllegalArgumentException: Table location is required in format v2`.
   - **Deviation from "no placeholder", with the reason:**
     - Iceberg 1.12 rejects any v1–v3 `TableMetadata` without a location.
     - OpenHouse's create must keep building that metadata client-side. It is where Iceberg assigns fresh IDs, applies persisted defaults and validates, and bypassing it would also silently hide the v2-default rejection.
     - The server's location cannot be known before the create without an extra staged-create RPC, which writes a metadata file.
   - Fix, scoped to `OpenHouseTableBuilder.create()`:
     - when the caller gave no location, it supplies `openhouse:/location-assigned-by-service`
     - its scheme has no FileSystem, so any accidental use fails loudly
     - `defaultWarehouseLocation()` still returns null
   - After the fix:
     - the test passes on both builds
     - the create request body is **byte-identical between the default and opt-in builds**, and identical to the default build's before the fix (`create-body-default.txt` vs `create-body-fork.txt`)
     - so the stored table is the same

## Remaining opt-in failures, classified [ran]

**Iceberg-side difference, item 5: non-null column defaults on v2 (8 tests).** Message: "Invalid initial default … not supported until v3".
- `ColumnDefaultSparkITest`, 5 tests: `readBridgeOverlaysInitialDefaultAfterSparkAddColumn`, plus 2 × `vectorized=false` and 2 × `vectorized=true` parameterizations.
- `OpenHouseTableOperationsTest`, 3 tests: `constructMetadataRequestBody_sendsStampedIdsKeepsUnstampedColumnDefaults`, `constructMetadataRequestBody_withoutConfigLeavesWriterDefaults`, `testDoRefreshSkipReloadStillSendsStampedDefaults`.

**Iceberg-side difference, item 5: RTAS after a dropped partition source column (1 test).** `RTASTest.testRTASPartitionColumnDropEnabledReachesIcebergValidation`: "Expected SparkException … nothing was thrown".
- Responsible change: **apache/iceberg#11868** "API, Core, Spark: Ignore partition fields that are dropped from the current-schema", commit 8134815a3a (2025-07-02).
  - It is on the fork's `1.10.x` branch, so it first shipped in 1.10.0 [ran: gh compare].
  - `PartitionSpecParser.fromJson(schema, json)` now binds historical specs with `bind(schema, true)` (allowMissingFields), and `PartitionSpec.checkCompatibility` skips fields whose source column is gone.
- LinkedIn 1.5.2.24 has only `UnboundPartitionSpec.bind(Schema)`, which is strict [ran: javap]. On 1.5.2 the old spec 0 (`identity(id)`) cannot bind to the new schema without `id`, so the RTAS fails with "Cannot find source column for partition field: 1000: id: identity(1)".
- On 1.12 the old spec is kept unbound-tolerant and the RTAS succeeds.

**Other, Iceberg-side but not in item 5 (1 test).** `LegacyPathSparkSqlSmokeTest.s19_namespaces_showAndCreate`.
- `CREATE NAMESPACE openhouse.x` still fails with `UnsupportedOperationException`.
- The message is now "Checking if database exists is not supported" instead of "Describing database is not supported".
- Cause: **apache/iceberg#14507** "Spark: Improve namespace existence verification logic" (ccc3e14df9, 2025-11-05).
  - Since then, Iceberg's `SparkCatalog.namespaceExists` calls `catalog.namespaceExists`; OpenHouseCatalog throws the "Checking…" message there.
  - 1.5.2's SparkCatalog does not override it, so Spark's default `namespaceExists` called `loadNamespaceMetadata` and got the "Describing…" message.
- I left it unfixed. Making OpenHouse throw the old message from `namespaceExists` would change the Java API on the default build.

**Fixed by me:** the 23 SmokeTest failures plus 2 new tests (HadoopFileIO and location). The location fix also lets every Spark CREATE TABLE path pass; the scout saw catalogTest 8/8 fail before its hack.

**Fixed by the switch's build plumbing**, which went beyond the scout's state:
- internalcatalog `CacheConfigurationTest` × 2: Caffeine pinned to 3.2.4. internalcatalog is now 319/0.
- htscatalog: avro 1.12.2, and parquet/orc at runtime for format-model registration.
- Spark ORC clash: the excludes.

## Spark SQL behavior differences between the builds

These are all the ones I observed [ran]:
1. A non-null column default on v2 tables is rejected on 1.12 and accepted on 1.5.2. This affects CREATE / ALTER ADD COLUMN with defaults, and Spark reads that go through the read-bridge.
2. `REPLACE TABLE … AS SELECT` that drops a column a former partition spec used: it succeeds on 1.12 and fails on 1.5.2 with a ValidationException.
3. `CREATE NAMESPACE`: same exception class, different message.
4. Plain `CREATE TABLE`: no difference. The request is byte-identical; it was checked through the Java catalog, which is the path Spark uses.

Beyond what the suites assert, I did not diff Spark outputs statement by statement, so silent differences in untested statements are not excluded. apache/iceberg#14457 (table/view/function existence checks) is a likely source of further message-level differences [inferred].

## 1.2 / Spark 3.1 line under the switch

- **Still in the build [ran].** The 1.5 modules reuse its sources through `sourceSets` and `evaluationDependsOn`, so it cannot be excluded without restructuring.
- **Compiles, against LinkedIn's 1.2.0.25** for core / common / data / bundled-guava / spark-runtime-3.1, all strictly pinned, with Java 17 target [ran].
- **But it is a hybrid [ran: resolution dumps]:**
  - On apps:spark and spark-3.1 itest runtime classpaths, `iceberg-api` resolves to 1.12, through the single-copy server modules, while core is 1.2.0.25. Thin iceberg-orc/parquet 1.12 also land there.
  - The `testRuntimeClasspath` of spark-3.1 runtime and libs:datalayout no longer resolves: strict 1.2.0.25 conflicts with 1.12 for bundled-guava.
  - apps:spark and the spark-3.1 itest fail offline on `aircompressor:2.0.2`, which I did not try online.
- **Single-copy server modules** (services:*, internalcatalog, htscatalog, cluster:storage, iceberg:azure) are compiled against 1.12 under the switch, and the 1.2 consumers embed them. That is a real problem for the 1.2 line under the switch.
- **Needs a decision, not solved here:** exclude or neutralize the 1.2 line under the switch, give it its own server copies, or retire it. The docs say not to run 1.2 tests with the switch.
- **Also unchanged:** `iceberg-aws:1.2.0.6` sits next to 1.12 core on the 1.5 line in both builds, a mixed-version hazard. Jackson stays forced to 2.13.4; the fork wants 2.22. No test failed from either.

## Evidence

**Classpaths.** `cp-base.d` / `cp-final.d` / `cp-fork.d` were produced with init script `dump-classpaths.gradle`, offline. It records every resolvable `*Classpath` / `shadow` / `otelAgent` configuration.

**Class-hash diff** of the default build against baseline: only these differ.
- `IcebergDataUtils`
- `OpenHouseCatalog` (+3 inner classes)
- `ReadBridgeTest` (×2 modules)
- `SparkMultiSchemaEvolutionTest`
- `SparkMoRFunctionalTest`
- `TablesValidatorTest` (+$1)
- 2 new test classes

**Logs.**
- baseline: `base-test.log`, `base-compile2.log`
- default after: `final-def-compile.log`, `final-def-test.log`, `final-def-rerun.log` (forced rerun of the two test tasks that came FROM-CACHE)
- opt-in: `fork-compile.log`, `fork-test-{a,b,c}.log`, results copy in `fork-results/`
- before/after fixes: `fork-java-prefix.log` (28 failures), `fork-java-fix1.log` (4), `fork-java-fix2.log` (3), and the `def-java-*.log` counterparts (0 each)

**Iceberg plumbing check.** `:iceberg-core:properties` gives `org.apache.iceberg` / `1.13.0-SNAPSHOT` without properties, and the overridden values with them. The published POMs carry `com.linkedin.iceberg:1.12.0-ohfork.6d9af8280912`, including on inter-module dependencies.

## Timings and CI cost

- **publish-local.sh.** 3m46s for the first run in a fresh checkout. That run had a warm `~/.gradle` dependency cache and a partly warm build cache (109 of 111 tasks executed). Re-running after that is a no-op (0.02s).
- **Opt-in test list.** About 17 min (15.5 + 1 + 0.6), plus about 1.6 min to compile.
- **Estimated CI job [inferred]:**
  - cold (empty Gradle caches, Iceberg and OpenHouse dependency downloads): about 30–40 min
  - warm (Iceberg repo cached keyed on the pinned SHA, Gradle caches restored): about 20 min
- No CI job was added.

## Not run / not done

- 1.2 / Spark 3.1 tests under the switch (their test classpaths do not resolve, as described above).
- housetables, jobs, apps:spark-3.5 tests and Docker-based tests, on either build.
- Nothing published remotely, and no Iceberg CI action.
- The scout worktrees and its private m2 were not touched.
- The pre-commit hook printed `[[: not found`. That is a bug in the existing hook: its `sh` does not support `[[`, so the spotless exit check is silently skipped. I ran `spotlessApply` myself before committing.
