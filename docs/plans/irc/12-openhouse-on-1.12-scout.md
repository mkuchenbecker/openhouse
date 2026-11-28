# i6 scout: OpenHouse on the fork's Iceberg 1.12 line

Bottom line: OpenHouse compiles against the mkuchenbecker Iceberg fork's 1.12 line with 11 compile errors, and every other module compiles untouched. Once the tests run, 34 fail, from four runtime causes: non-null column defaults on v2 tables, a null configuration passed to `HadoopFileIO`, a caffeine version split, and a replace-table-as-select (RTAS) that now succeeds after a partition source column is dropped. Six of the failing Spark tests pass on unmodified 1.5.2, so these are real behavior changes and not test noise. The server modules share one Iceberg copy with the 1.2 and Spark 3.1 line, and that line cannot keep sharing them once the build needs Java 17. This was a local experiment only. Nothing was pushed, published to a remote or merged.

Marks: **[ran]** means the command was run and its log was kept outside the repository. **[inferred]** means the claim comes from reading code or configuration and was not run.

Inputs: OpenHouse `irc-integration` at bb8929e1 and Iceberg `irc/reapply-all` at f180ae589. Both were used in local worktrees with uncommitted changes.

## How LinkedIn publishes and how OpenHouse resolves

- LinkedIn's Iceberg is published to Maven Central under the group `com.linkedin.iceberg`, with four-part versions such as 1.5.2.24 and 1.2.0.25. The latest 1.5 build is 1.5.2.24 **[ran]**.
- Release builds are tagged by CI and published to Sonatype, then to Maven Central **[inferred]**.
- OpenHouse resolves from Maven Central only, with no `mavenLocal` in its consumer build. Its Iceberg versions are set in `build.gradle` (`iceberg_1_2_version` and `iceberg_1_5_version`) **[ran]**.
- The 1.5 convention covers services, housetables, jobs, tables, the HTS and internal catalogs, the storage cluster, and the Spark 3.5 apps. The 1.2 convention covers the Spark 3.1 runtime, `apps:spark`, `libs:datalayout` and the 1.2 test fixtures **[ran]**.
- Even the 1.5 line pulls `iceberg-aws` 1.2.0.6 through a shared convention **[ran]**.
- Several build files exclude by group name `com.linkedin.iceberg::`, so a group change would break them **[ran]**.
- For the scout, the fork was published under its LinkedIn group with version `1.12.0-mkscout`. OpenHouse read it from a local Maven directory only when given `-PicebergForkVersion`. The default build is therefore unchanged **[inferred]**. The flagged build was run, and the default build was not.

## Versions

- OpenHouse's Spark 3.5 modules use Spark 3.5.2 and Scala 2.12 **[ran]**.
- The fork's Spark 3.5 build targets Spark 3.5.9 and Scala 2.12 by default, with 2.13 also listed **[ran]**.
- The fork's artifacts are Java 17 bytecode. OpenHouse targets Java 1.8 by default and targets 17 only under the flag **[ran]**.

## Compile errors by cause [ran]

| Cause | Errors | Where | Scout patch |
| --- | --- | --- | --- |
| `GenericAppenderFactory` removed | 2 | `htscatalog` `IcebergDataUtils.java` (main); `apps/spark-3.5` `SparkMoRFunctionalTest.java` (test) | Ported to `GenericFileWriterFactory.Builder`; test patched |
| `TableMetadata.Builder.addSchema(Schema,int)` removed | 7 | Six lines in `SparkMultiSchemaEvolutionTest.java`; one in the shared `ReadBridgeTest.java` | Patched |
| Java 17 module encapsulation | 2 | `TablesValidatorTest.java` imports a `com.sun` class | Replaced with a local constant |
| Total | 11 | 1 main, 10 test | |

There were no Scala, shading or transitive compile errors. One Maven Central HTTP 429 went away on retry. After the `ReadBridgeTest.java` edit, the same shared file fails to compile on the 1.2 line, which is one further error **[ran]**.

## Test counts [ran]

| Suite | Tests | Failed | Notes |
| --- | --- | --- | --- |
| services:tables | 1205 | 0 | Jackson forced to 2.13.4 |
| internalcatalog | 319 | 2 | caffeine split |
| htscatalog | 20 | 0 | After two dependency changes; the first run had 7 of 15 failing |
| Spark 3.5 itest, catalogTest | 106 | 6 | 5 column-default and 1 RTAS; all 6 pass on unmodified 1.5.2 |
| Spark 3.5 itest, statementTest | 137 | 0 | Run with catalogTest excluded |
| Spark 3.5 itest, test | 124 | 0 | Run with catalogTest excluded |
| java-iceberg-1.5 itest | 121 | 26 | 23 `HadoopFileIO`, 3 column-default |

The catalogTest and the 1.5 itest counts depend on the placeholder and classpath changes described below.

## Runtime and behavior differences [ran]

- **Null table location is rejected.** Iceberg 1.12 allows a null table location only from format v4. OpenHouse's client returns null from `defaultWarehouseLocation()`, because the server owns the location. Without a change, catalog tests fail with "Table location is required in format v2". The scout used the placeholder `openhouse:/i6-scout-placeholder`. This is a hack, not a fix.
- **`HadoopFileIO(null)` throws.** `OpenHouseCatalog.loadFileIO` passes a null configuration (`OpenHouseCatalog.java:221` in the 1.5 runtime). On 1.12 the constructor wraps it eagerly and throws a NullPointerException. This causes 23 failures, mostly in SmokeTest.
- **Non-null column defaults are rejected on v2 tables.** Iceberg 1.12 says a non-null default "is not supported until v3". LinkedIn's 1.5.2 allowed them. This causes 8 failures: 5 in `ColumnDefaultSparkITest` and 3 in `OpenHouseTableOperationsTest`.
- **RTAS after dropping a partition source column succeeds.** On 1.5.2 it fails with "Cannot find source column for partition field". One test, `RTASTest.testRTASPartitionColumnDropEnabledReachesIcebergValidation`, expects the old error.

## Dependency findings

- Root `force` pins override what 1.12 needs. Jackson 2.22.2 falls to 2.13.4, Avro 1.12.2 falls to 1.11.4, and orc-core 1.9.9 falls to 1.8.3 **[ran]**. The Avro pin caused a `NoSuchMethodError` on `LogicalTypes.timestampNanos()` in htscatalog, so the scout skipped the Avro and ORC pins under the flag.
- `iceberg-data` keeps Parquet and ORC as `compileOnly`. The 1.12 format registry registers all three formats together, so without the Parquet and ORC jars even Avro is silently unregistered. The scout added `runtimeOnly` Parquet and ORC to the 1.5.2 convention **[ran]**.
- internalcatalog declares caffeine 2.8.8, but iceberg-core 1.12 brings caffeine 3.2.4 at runtime. `CacheConfigurationTest` fails with `NoClassDefFoundError` on `Policy$Expiration`. Not fixed **[ran]**.
- A thin `iceberg-orc` jar beside the shaded Spark runtime binds to Spark's own ORC 1.9.4 and fails. The scout removed the thin ORC and Parquet jars from the itest runtime classpath **[ran]**.
- `iceberg-aws` 1.2.0.6 stays on the 1.5 line beside core 1.12. This is a mixed-version hazard **[ran for the coordinate, inferred for the risk]**.
- The server modules are single-copy and pinned to 1.5. Once they need Java 17 bytecode, the 1.2 and Spark 3.1 consumers cannot share them, and running them under Spark 3.1 or Java 8 is not credible **[inferred]**. Shared test source `ReadBridgeTest.java` cannot compile on both lines once the API ports are applied **[ran]**.

## Hosting options for the fork's artifacts

| Option | Viable | Evidence |
| --- | --- | --- |
| GitHub Packages on the fork | Yes, from GitHub Actions | Proxy refused the connection with 403 **[ran]**. Reading even public packages needs a token **[known limitation, not tested]** |
| JitPack | Unlikely | Proxy refused `jitpack.io` with 403 **[ran]**. JitPack would rebuild all of Iceberg on its side |
| Maven Central under a fork-owned group | Yes, mirrors LinkedIn's route | `repo.maven.apache.org` and `repo1.maven.org` returned 200 **[ran]**. Sonatype hosts were refused **[ran]**. Needs namespace verification and signing. A group change breaks the excludes above |
| Static Maven repository on a branch, read through `raw.githubusercontent.com` | Yes, no auth for a public repository | Raw file returned 200 **[ran]**. GitHub Pages was refused with 403 **[ran]** |
| Gradle composite build of an Iceberg checkout | Plausible, untested | Both repositories use Gradle 8.14.5 **[ran]**. Iceberg's settings read a system property that probably needs `-D` flags on the OpenHouse command line **[inferred]** |
| Git submodule plus a CI step running `publishToMavenLocal` | Yes | `git ls-remote` returned f180ae589 **[ran]**. The step took 2m16s on a warm cache. A cold CI cache pays for a full Iceberg build **[inferred]** |

## Not run

- The housetables, jobs and apps:spark-3.5 test suites.
- The 1.2 and Spark 3.1 line under the flag.
- Anything that needs Docker, since no daemon was available.
- A 1.5.2 baseline for the java-iceberg-1.5 itest.
- The Spark 3.5.2 versus 3.5.9 runtime comparison.
- The default build with the flag absent. Its unchanged status is inferred.
- Full Iceberg test suites on the 1.12 line.

## Status

This was a local experiment. Nothing was pushed, published to a remote or opened as a pull request. The two worktrees, `/home/user/oh-i6` and `/home/user/iceberg-i6`, hold uncommitted changes. The artifacts went to a private Maven directory outside the repository. The catalogTest and 1.5 itest counts reflect the placeholder and classpath changes, so they are not a clean build.
