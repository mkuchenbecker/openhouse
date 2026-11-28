# Opt-in build on the fork's Iceberg 1.12 line: result

Bottom line: OpenHouse's Iceberg 1.5 variant now builds and tests against the fork's 1.12 line behind one
switch, `-PicebergFork`, and the default build is unchanged. Every listed suite passes except 10 tests,
and all 10 are Iceberg behavior differences, not OpenHouse defects. This covers items I6a to I6c, in draft
pull request 87 (branch `irc/i6-iceberg-1.12-optin`). A delegated agent observed it on 2026-11-22.
Nothing here is agreed.

Marks: **[ran]** the agent ran it. **[inferred]** the agent read code but did not run it.

## Default build unchanged [ran]

- Baseline is `irc-integration` (bb8929e1) plus the two merged test branches (merge commit 361bc090).
- The resolved artifacts of every resolvable classpath configuration in all 59 projects are byte-identical
  before and after: 32,715 lines, and `diff -r` of the two dumps is empty.
- Default compile and tests match the baseline. The 1.5 Java integration test goes from 121 to 124 tests
  because 3 were added.

## Test counts

Opt-in build; `-PicebergFork assemble testClasses` succeeds for all 171 tasks [ran]:
| Task | Tests | Failures |
| --- | --- | --- |
| tables | 1205 | 0 |
| internalcatalog | 319 | 0 |
| htscatalog | 20 | 0 |
| spark-3.5 itest catalogTest | 145 (1 skipped) | 7 |
| spark-3.5 itest statementTest | 137 | 0 |
| spark-3.5 itest test | 124 | 0 |
| java-iceberg-1.5 itest | 124 | 3 |

## How the switch, pin and script work

- **Pin.** `scripts/iceberg-fork/iceberg-fork.properties` holds the repository URL, the commit
  (6d9af8280912, the tip of iceberg pull request 9), the group, the base version and the Avro, ORC and
  Caffeine pins. The version is `1.12.0-ohfork.6d9af8280912`.
- **Script.** `scripts/iceberg-fork/publish-local.sh` shallow-fetches that commit into
  `build/iceberg-fork/src` and publishes api, bundled-guava, common, core, data, parquet, orc, bom and
  spark-runtime-3.5_2.12 into `build/iceberg-fork/repo`. A marker file skips a repeat publish. It never
  touches `~/.m2`, publishes nothing remotely and commits no binaries.
- **Switch.** `gradle/iceberg-fork.gradle` is applied only when `-PicebergFork` is set. It sets
  `iceberg_1_5_version`, adds an exclusive repository for `com.linkedin.iceberg` at the fork version, and
  fails fast if nothing is published yet.
- **Guarded spots** check the same property: the root forces, the Java 17 target, runtime Parquet and ORC
  in `iceberg-conventions-1.5.2`, and excludes of thin jars that clash with the shaded runtime.
- **Iceberg side.** Iceberg pull request 9 (`irc/publish-plumbing`) adds `-PpublishGroup` and
  `-PpublishVersion`, because the group was hard-coded twice. Apache defaults are unchanged. It is merged
  into `openhouse-1.12.0` as merge commit 984904f02. Docs: `docs/development/iceberg-fork.md`.

## Source ports

All compile on both Iceberg lines, so no switch-selected source sets were needed.
- `addSchema(Schema,int)` became `setCurrentSchema(Schema,int)`, which exists in 1.2.0.25, 1.5.2.24 and
  1.12 (checked with `javap`), in `SparkMultiSchemaEvolutionTest` and the shared `ReadBridgeTest`.
- `GenericAppenderFactory` was replaced by `Avro.write` and `Avro.writeDeletes` in htscatalog
  `IcebergDataUtils` (main code) and `ORC.writeDeletes` in `SparkMoRFunctionalTest`.
  `GenericFileWriterFactory` is package-private in 1.5.2.
- `TablesValidatorTest` now uses an inline `"characters"` instead of an internal Xalan constant.

## Two client fixes, test first [ran]

**1. `HadoopFileIO` with a null configuration.**
- Test: `OpenHouseCatalogFileIOTest`. The catalog must initialize without `setConf` and use a
  configuration when given one.
- Failing: under the switch `initializesWithoutAHadoopConfiguration` threw a null pointer in
  `SerializableConfiguration`, and 23 `SmokeTest` cases failed the same way.
- Fix: a null configuration uses `new HadoopFileIO()`, as `CatalogUtil.loadFileIO` does.
- Passing under the switch; on the default build they pass before and after.

**2. Null table location on create.**
- The client never sends a location and the server allocates it.
- Test: `OpenHouseCatalogCreateTableTest`, against a mock tables service. The request body must be
  identical with and without `withLocation("file:/tmp/client-chosen-location")`, contain no "location",
  and `table.location()` must be the service-assigned one.
- Failing: under the switch, `IllegalArgumentException: Table location is required in format v2`.
- Fix, in `OpenHouseTableBuilder.create()`: with no caller location it supplies the placeholder
  `openhouse:/location-assigned-by-service`. `defaultWarehouseLocation()` still returns null.
- Passing: on both builds. The create request body is byte-identical between the default and opt-in
  builds, and to the default build before the fix (`create-body-default.txt`, `create-body-fork.txt`).
- Why a placeholder: Iceberg 1.12 rejects table metadata without a location. The create must keep
  building that metadata on the client, where Iceberg assigns ids, applies defaults and validates.
  Learning the server's location first needs an extra staged-create call that writes a metadata file.
  The placeholder's scheme has no file system, so accidental use fails loudly. See D13.

## The 10 remaining failures, by class [ran]

All 10 were left untouched and failing.
**Non-null column defaults on v2 tables, 8 tests.** "Invalid initial default ... not supported until v3".
This is the class behind X0, addressed by W3 and W4.
- `ColumnDefaultSparkITest`, 5 tests, including `readBridgeOverlaysInitialDefaultAfterSparkAddColumn`.
- `OpenHouseTableOperationsTest`: `constructMetadataRequestBody_sendsStampedIdsKeepsUnstampedColumnDefaults`,
  `constructMetadataRequestBody_withoutConfigLeavesWriterDefaults`,
  `testDoRefreshSkipReloadStillSendsStampedDefaults`.

**RTAS after a dropped partition source column, 1 test.**
`RTASTest.testRTASPartitionColumnDropEnabledReachesIcebergValidation`: "Expected SparkException ...
nothing was thrown".
- Cause: apache/iceberg pull request 11868, "API, Core, Spark: Ignore partition fields that are dropped
  from the current-schema" (commit 8134815a3a, 2025-07-02). It is on the fork's `1.10.x` branch, so it
  first shipped in 1.10.0 [ran: GitHub compare].
- On 1.12 old specs bind with `bind(schema, true)` and `checkCompatibility` skips fields whose source
  column is gone.
- LinkedIn's 1.5.2.24 has only the strict `UnboundPartitionSpec.bind(Schema)` [ran: `javap`], so the RTAS
  fails with "Cannot find source column for partition field: 1000: id: identity(1)". On 1.12 it succeeds.

**`CREATE NAMESPACE` message, 1 test.** `LegacyPathSparkSqlSmokeTest.s19_namespaces_showAndCreate`.
- It still fails with `UnsupportedOperationException`, but the message is now "Checking if database
  exists is not supported", not "Describing database is not supported".
- Cause: apache/iceberg pull request 14507, "Spark: Improve namespace existence verification logic"
  (ccc3e14df9, 2025-11-05). `SparkCatalog.namespaceExists` now calls `catalog.namespaceExists`, where
  `OpenHouseCatalog` throws the new message. Before, Spark's default called `loadNamespaceMetadata`.
- Left unfixed: restoring the old message would change the Java API on the default build.

Spark outputs were not diffed statement by statement, so silent differences in untested statements are
not excluded. Apache pull request 14457 (existence checks) may cause more message changes [inferred].
Plain `CREATE TABLE` has no difference: the request is byte-identical [ran].

## The 1.2 and Spark 3.1 line under the switch [ran]

- It stays in the build, because the 1.5 modules reuse its sources. It compiles against LinkedIn's
  1.2.0.25 with a Java 17 target, but only as a hybrid, with single-copy server modules on 1.12.
- On its runtime classpaths `iceberg-api` resolves to 1.12 while core is 1.2.0.25, and two test classpaths
  no longer resolve because strict 1.2.0.25 conflicts with 1.12 for bundled-guava.
- The decision is D15: exclude the 1.2 projects under the switch (item I6g).

## CI cost

- `publish-local.sh` took 3m46s on a first run in a fresh checkout with a warm dependency cache. A repeat
  run is a no-op (0.02 s). The opt-in test list takes about 17 minutes plus 1.6 minutes to compile [ran].
- Estimated CI job [inferred]: 30 to 40 minutes cold, about 20 minutes with the Iceberg build cached on the
  pinned commit. No CI job was added.

## Not run

- Tests of the 1.2 and Spark 3.1 line under the switch; their test classpaths do not resolve.
- housetables, jobs, apps:spark-3.5 and Docker-backed tests, on either build (item I6f).
- Nothing was published remotely.
- The pre-commit hook prints `[[: not found`, so its spotless check is skipped. The agent ran
  `spotlessApply` by hand.
