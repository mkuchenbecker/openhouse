# i6 scout: OpenHouse on the mkuchenbecker Iceberg fork, 1.12 line

Inputs: OpenHouse `origin/irc-integration` @ bb8929e1 (worktree `/home/user/oh-i6`, branch `scratch/i6-scout`, uncommitted). Iceberg `origin/irc/reapply-all` @ f180ae589 (worktree `/home/user/iceberg-i6`, detached, uncommitted). Nothing was pushed or published remotely. `~/.m2` was not touched: everything went through a private Maven repo at `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/i6/m2` (69 MB).

Labels: **[ran]** means I ran it and the log is under `.../scratchpad/i6/`. **[inferred]** means I read code or config but did not run it.

## Bottom line

- **Compile [ran].** I pointed the 1.5 line at the fork's 1.12 build and had to change 4 build settings. After that the whole OpenHouse build compiles with **11 compile errors**: 1 in main code and 10 in tests.
  - 10 of the 11 come from 2 Iceberg APIs that were removed: `GenericAppenderFactory` (2 errors) and `TableMetadata.Builder.addSchema(Schema,int)` (7 errors).
  - 2 errors come from moving the Java target from 8 to 17.
  - Every other module compiles untouched, including the Spark 3.5 runtime (Java and Scala 2.12).
- **Tests [ran].** After two more dependency changes and one placeholder hack (described below), these are the results:

| suite | tests | fail |
|---|---|---|
| services:tables | 1205 | 0 |
| internalcatalog | 319 | 2 |
| htscatalog | 20 | 0 |
| spark-3.5 itest, catalogTest | 106 | 6 |
| spark-3.5 itest, statementTest | 137 | 0 |
| spark-3.5 itest, test | 124 | 0 |
| java-iceberg-1.5 itest | 121 | 26 |

- The 34 failing tests come from **4 runtime causes**:
  1. Iceberg 1.12 rejects non-null column defaults on v2 tables (8 tests). LinkedIn 1.5.2 allowed them.
  2. `HadoopFileIO(null)` now throws a NullPointerException (23 tests).
  3. A caffeine 2-vs-3 classpath split (2 tests).
  4. RTAS now succeeds when a partition source column is dropped; on 1.5.2 it failed (1 test).
- I ran the 6 failing Spark tests on unmodified 1.5.2 and all 6 pass there **[ran]**. So these are real behavior changes, which matters for "Spark SQL behavior must not change".
- **Structural problem [ran + inferred].** The server modules (tables service, catalogs, storage) are single-copy and pinned to the 1.5 line. Spark 3.1 and Iceberg 1.2 modules consume them. Once 1.12 forces Java 17 bytecode, the 1.2 / Spark 3.1 line cannot keep sharing them. Shared test sources also stop compiling on both lines: I hit this in `ReadBridgeTest`.

## Q1. How LinkedIn's Iceberg publishes, and how OpenHouse consumes it

**Publishing [inferred from `li/openhouse-1.5.2`]:**
- Group `com.linkedin.iceberg`, set in `build.gradle:135` (`allprojects { group = "com.linkedin.iceberg"`) and `deploy.gradle:77,137` (`groupId`).
- Version scheme is four parts, `<apache>.<build>`, for example 1.5.2.24 and 1.2.0.25.
  - Before CI tagging existed, the version came from shipkit-auto-version (`shipkit.gradle`, `version.properties`: `version=1.5.2.901`).
  - Now `ci-create-tag.sh` bumps the 4th part of the latest tag, and `.github/workflows/java-ci.yml:89-114` runs `githubRelease publishToSonatype closeAndReleaseStagingRepository -PciVersion=... -DsparkVersions=3.5` with `SONATYPE_USER` / `SONATYPE_PWD`.
- The artifacts land on **Maven Central**. `repo.maven.apache.org/maven2/com/linkedin/iceberg/` lists iceberg-api/core/common/data/orc/parquet/arrow/aws/azure/bundled-guava/hive-metastore/spark-3.5_2.12/spark-extensions-3.5_2.12/spark-runtime-3.5_2.12, the 3.1 equivalents, flink, and others. `iceberg-core` has 89 versions; the latest is 1.5.2.24 **[ran: curl]**.
- The branch also carries a `jitpack.yml` (`./gradlew publishToMavenLocal`).

**OpenHouse resolution [ran: grep; full list in `.../scratchpad/i6/oh-coords.txt`, 69 lines]:**
- **Repositories.** Maven Central only, set in three places: `build.gradle:3` (buildscript), `build.gradle:71-79` (allprojects) and `settings.gradle` pluginManagement. No `mavenLocal`. `buildSrc/.../openhouse.maven-publish.gradle:78-84` is OpenHouse's own publishing (mavenLocal plus `linkedin.jfrog.io/artifactory/openhouse`).
- **Versions.** `build.gradle:33` `iceberg_1_2_version = "1.2.0.25"` and `build.gradle:34` `iceberg_1_5_version = "1.5.2.24"`. `buildSrc/.../openhouse.iceberg-aws-conventions.gradle:2` sets `icebergAwsVersion = '1.2.0.6'`; it is used by services:tables, internalcatalog, cluster:storage and iceberg:azure, so even the 1.5 line pulls iceberg-aws 1.2.0.6.
- **Conventions.**
  - `openhouse.iceberg-conventions-1.5.2.gradle:2,6-9` adds api bundled-guava/data/core/common.
  - `openhouse.iceberg-conventions-1.2.gradle:2,6-9` adds the same with strict `!!` pins.
  - `openhouse.apps-spark-common.gradle:31-35` declares unversioned iceberg deps, with shadow excludes of `com.linkedin.iceberg::` at :74-80.
- **Modules on the 1.5 line through the convention:** services:common (`build.gradle:3`), services:housetables (`:4`), services:jobs (`:4`), services:tables (`:6`), iceberg:openhouse:htscatalog (`:4`), iceberg:openhouse:internalcatalog (`:4`), iceberg:azure (`:3`), cluster:storage (`:6`), tables-test-fixtures-iceberg-1.5 (`:4`), apps:spark-3.5 (`:5`).
- **Explicit 1.5 coordinates:**
  - `integrations/java/iceberg-1.5/openhouse-java-runtime/build.gradle:22,53,58`
  - `integrations/java/iceberg-1.5/openhouse-java-itest/build.gradle:7,23`
  - `integrations/spark/spark-3.5/openhouse-spark-runtime/build.gradle:9,67,81` (`iceberg-spark-runtime-3.5_2.12`)
  - `apps/spark-3.5/build.gradle:12,15,20,31`
- **Explicit 1.2 coordinates:**
  - `libs/datalayout/build.gradle:8,19-22,35`
  - `integrations/java/iceberg-1.2/openhouse-java-runtime/build.gradle:22,43,48,84`
  - `integrations/java/iceberg-1.2/openhouse-java-itest/build.gradle:7,15`
  - `integrations/spark/spark-3.1/openhouse-spark-runtime/build.gradle:9,42,56`
  - `apps/spark/build.gradle:5,12,15`
  - `tables-test-fixtures-iceberg-1.2/build.gradle:4`
- **Group-keyed excludes** (these would break if the group changed): `services/tables/build.gradle:122`, `scripts/java/tools/dummytokens/build.gradle:13-14`, `apps/spark-3.5/build.gradle:20,31`, `apps-spark-common.gradle:74-80`.
- **Scala.** Every OpenHouse Spark 3.5 module uses **Scala 2.12** (`spark-sql_2.12`, `spark-hive_2.12`, `iceberg-spark-runtime-3.5_2.12`) on Spark **3.5.2**.
- **Java target.** `openhouse.java-minimal-conventions.gradle:7-8` sets source and target to 1.8.

## Q2. Publishing `irc/reapply-all` locally

**Finding [ran].** Iceberg 1.12 still builds Spark 3.5 for Scala 2.12:
- `gradle.properties` has `knownScalaVersions=2.12,2.13` and `defaultScalaVersion=2.12`.
- `settings.gradle:151-160` includes `spark-3.5_${scalaVersion}`.
- Spark 3.5 is built against Spark **3.5.9** (`libs.versions.toml:90`); OpenHouse uses 3.5.2.
- The artifacts are Java 17 bytecode (`options.release = 17`, `build.gradle:226`), and their Gradle module metadata says `org.gradle.jvm.version=17`.

**Plumbing edits.** These are uncommitted. The diff is at `.../scratchpad/i6/iceberg-plumbing.diff`.
- `build.gradle:115`: `group = "org.apache.iceberg"` becomes `"com.linkedin.iceberg"`.
- `deploy.gradle:96`: `groupId 'org.apache.iceberg'` becomes `'com.linkedin.iceberg'`.
- New `version.txt` containing `1.12.0-mkscout`. `getProjectVersion()` already prefers `version.txt` (`build.gradle:1212`).

I kept LinkedIn's group so OpenHouse only needs a version change; a group change would break the group-keyed excludes listed in Q1. That choice is for the scout only, see Q6.

**Command [ran, succeeded in 2m16s, partly from the build cache].** The command line is also saved in `.../scratchpad/i6/iceberg-publish-cmd.txt`:
```
cd /home/user/iceberg-i6 && ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx4g -DsparkVersions=3.5 -DscalaVersion=2.12 -DflinkVersions= -DkafkaVersions= \
  -Dmaven.repo.local=/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/i6/m2 \
  :iceberg-{bom,api,common,core,data,orc,arrow,parquet,bundled-guava,hive-metastore,aws,azure}:publishToMavenLocal \
  :iceberg-spark:iceberg-{spark-3.5_2.12,spark-extensions-3.5_2.12,spark-runtime-3.5_2.12}:publishToMavenLocal
```
(The real command lists each task separately; the brace form above is shorthand.)

Gotcha: the Spark projects are nested, so the task path is `:iceberg-spark:iceberg-spark-3.5_2.12`, not `:iceberg-spark-3.5_2.12`. My first try failed on that.

## Q3. Compiling OpenHouse against the local 1.12 artifacts

Everything is behind `-PicebergForkVersion=1.12.0-mkscout`, so the default build is unchanged. Command:
`./gradlew --max-workers=2 -Dorg.gradle.jvmargs=-Xmx4g -Dmaven.repo.local=<scratch m2> -PicebergForkVersion=1.12.0-mkscout --continue classes testClasses`

OpenHouse's consumer-side `mavenLocal()` honours `-Dmaven.repo.local` [ran]. The full diff is at `.../scratchpad/i6/openhouse-scout.diff` (10 files, +66/−35).

**Layers, in the order I hit them [ran]:**

| # | layer | count | cause | what I patched to proceed |
|---|---|---|---|---|
| 0 | resolution | every 1.5-line module | **Java version**: "looking for a library compatible with JVM runtime version 8, but iceberg-core:1.12.0-mkscout is only compatible with 17" | `java-minimal-conventions.gradle`: source and target 17 when the flag is set. `build.gradle`: version override, plus a `mavenLocal` filtered to `com.linkedin.iceberg` at the fork version |
| 1 | main compile | 1 error | **removed API**: `htscatalog/.../IcebergDataUtils.java:16` imports `org.apache.iceberg.data.GenericAppenderFactory`, removed upstream (apache/iceberg#17696) | ported to `GenericFileWriterFactory.Builder` plus `newDataWriter` / `newEqualityDeleteWriter(file, spec, partition)` |
| 2 | test compile | 10 errors in 4 tasks | see the next table | patched all of them |
| 3 | test compile | 1 error | the shared source `iceberg-1.2/openhouse-java-itest/.../ReadBridgeTest.java:113`, after my layer-2 edit, now fails on 1.2 | not fixed; it is evidence for Q5 |

**Layer 2, grouped by cause:**
- **Removed API, `TableMetadata.Builder.addSchema(Schema,int)`: 7 errors.**
  - `spark-3.5/openhouse-spark-itest/.../SparkMultiSchemaEvolutionTest.java:46,50,87,156,160,165`
  - `iceberg-1.2/openhouse-java-itest/.../ReadBridgeTest.java:113`, a shared source compiled into the 1.5 itest
  - The single-argument `addSchema(Schema)` does not exist in 1.2.0.25 or 1.5.2.24, and 1.12 has only that form (checked with javap) [ran].
- **Removed API, `GenericAppenderFactory`: 1 error.** `apps/spark-3.5/src/test/.../SparkMoRFunctionalTest.java:21`.
- **Java version (module encapsulation once the target is 17): 2 errors.** `services/tables/src/test/.../TablesValidatorTest.java:5` does `import static com.sun.org.apache.xalan.internal.xsltc.compiler.Constants.CHARACTERS`. I replaced it with a local constant.
- **Other causes: none at compile time.** There were no Scala errors, no shading errors and no transitive-dependency compile errors. One Maven Central HTTP 429 in the first run went away on retry.

**Modules that compile untouched against 1.12 [ran].** Every main and test compile task not named above compiled untouched. That includes:
- services:common, housetables, jobs, optimizer
- tables main
- iceberg:openhouse:internalcatalog (main and test), iceberg:azure, cluster:storage
- tables-test-fixtures-iceberg-1.5
- openhouse-java-iceberg-1.5-runtime
- openhouse-spark-3.5-runtime (compileJava and compileScala)
- apps:spark-3.5 main
- all client modules
- the libs:optimizer modules

The 1.2 / Spark 3.1 modules also compile with the Java 17 target. They stay on 1.2.0.25 because of their strict pins.

**Transitive dependency picture [ran: `dependencies` output in `.../scratchpad/i6/deps-*.txt`]:**
- The root `force` pins override what Iceberg 1.12 needs, in services:tables and in the spark-3.5 runtime compile classpath:
  - jackson-databind 2.22.2 is forced down to **2.13.4**
  - avro 1.12.2 is forced down to **1.11.4**
  - orc-core 1.9.9 is forced down to **1.8.3**
- The spark-3.5 itest sits in `excludedProjects`, so it gets jackson 2.22.2.
- `com.linkedin.iceberg:iceberg-aws:1.2.0.6` stays on the 1.5 line next to core 1.12, which is a mixed-version hazard.

## Q4. Tests

All runs below used the fork flag. Results are in `build/<module>/test-results` of the scratch worktree; logs are in `.../scratchpad/i6/oh-test-*.log`.

| suite | result | causes |
|---|---|---|
| `:iceberg:openhouse:htscatalog:test` | first 7/15 failed, then 12/20, then **20/20 pass** | (a) **transitive dependency (Avro)**: `NoSuchMethodError LogicalTypes.timestampNanos()`, caused by the root `force avro:1.11.4`. Fix: skip the avro and orc forces under the flag. (b) **changed Iceberg runtime contract**: "Format model is not registered for format AVRO and type Record". In 1.12, `FormatModelRegistry` calls `GenericFormatModels.register()`, which registers Avro, Parquet and ORC together. `iceberg-data` keeps parquet and orc as `compileOnly`, so without those jars even AVRO is silently unregistered. Fix: add `runtimeOnly` iceberg-parquet and iceberg-orc in the 1.5.2 convention |
| `:iceberg:openhouse:internalcatalog:test` | 319 tests, **2 fail** | **transitive dependency (caffeine)**: `CacheConfigurationTest` fails with `NoClassDefFoundError Policy$Expiration`. internalcatalog declares caffeine 2.8.8 (`build.gradle:14`), so tests compile against 2.8.8, while iceberg-core 1.12 brings 3.2.4 to the runtime classpath. Not fixed |
| `:services:tables:test` | **1205/1205 pass** (110 classes, Jackson 2.13.4 still forced) | the first attempt died with "Gradle build daemon disappeared", which looks like memory pressure in the shared cgroup; the retry was clean |
| spark-3.5 itest, `catalogTest`, 3-class sample | first 8/8 failed, then 7/8, then **8/8 pass** | (a) **changed Iceberg API contract**: "Table location is required in format v2". OpenHouse's client `OpenHouseCatalog.defaultWarehouseLocation()` returns null (the server owns the location), and 1.12 `TableMetadata` only allows a null location from v4 (`MIN_FORMAT_VERSION_OPTIONAL_LOCATION = 4`, apache/iceberg#16572). **Hack, not a fix**: return the placeholder `openhouse:/i6-scout-placeholder`. (b) **classpath duplication / shading**: `NoSuchMethodError org.apache.orc.storage...` in `OrcFileAppender`. The thin iceberg-orc jar I added in (b) above landed next to the shaded `iceberg-spark-runtime` and bound to Spark's orc 1.9.4. Fix: exclude thin iceberg-orc and iceberg-parquet from the itest `testRuntimeClasspath` |
| spark-3.5 itest, full `catalogTest` | 106 tests, **6 fail**, 1 skipped | **Behavior change 1, v2 column defaults**: "Invalid initial default for tags.element.country: non-null default (US) is not supported until v3". All 5 `ColumnDefaultSparkITest` failures; LinkedIn 1.5.2 allowed initial defaults on v2. **Behavior change 2, RTAS**: `RTASTest.testRTASPartitionColumnDropEnabledReachesIcebergValidation` expects "Cannot find source column for partition field"; 1.12 no longer throws. **Baseline [ran]**: all 6 pass on unmodified 1.5.2 (`oh-baseline-catalog.log`) |
| spark-3.5 itest, `statementTest` + `test` (run with `-x catalogTest`) | **137/137** and **124/124 pass** | none |
| `:integrations:java:iceberg-1.5:openhouse-java-iceberg-1.5-itest:test` | 121 tests, **26 fail** | **changed API semantics**: 23 `SmokeTest` failures. `OpenHouseCatalog.loadFileIO` calls `new HadoopFileIO(this.conf)` with a null conf (`iceberg-1.5/.../OpenHouseCatalog.java:221`), and 1.12 `HadoopFileIO(Configuration)` now wraps it eagerly in `SerializableConfiguration`, which throws a NullPointerException. 3 `OpenHouseTableOperationsTest` failures are the same v2 initial-default rejection. Not fixed. I did not run a 1.5.2 baseline for this module |

Not run: housetables, jobs and apps:spark-3.5 tests; the 1.2 / Spark 3.1 line under the flag; anything that needs Docker (no daemon is available here).

## Q5. Which variant structure 1.12 should follow

Judged only by what the build layout makes cheap:

- **Server side: replace 1.5 in place.** services:common, housetables, jobs, tables, htscatalog, internalcatalog, iceberg:azure and cluster:storage are single-copy and take Iceberg from `openhouse.iceberg-conventions-1.5.2`. A third variant would mean duplicating whole services. They compiled on 1.12 with one main-code edit. The fixtures relocate the server's Iceberg into `openhouse.relocated.org.apache.iceberg` (`tables-test-fixtures-common.gradle:107`), so the embedded server's Iceberg version is already independent of the client engine's version.
- **Client and engine side: either replace 1.5 or add a 1.12 sibling.** A sibling copies the existing 1.2 to 1.5 pattern: srcDirs inheritance plus `src/iceberg-version-specific/java`. Modules that would need a 1.12 sibling:
  - `buildSrc/openhouse.iceberg-conventions-1.12.gradle`
  - `integrations/java/iceberg-1.12/openhouse-java-runtime` (its own `src/iceberg-version-specific/java/.../OpenHouseCatalog.java`, needed for the null-location and HadoopFileIO fixes)
  - `integrations/java/iceberg-1.12/openhouse-java-itest`
  - `integrations/spark/spark-3.5` runtime and itest, either rebound in place or added as a `spark-3.5` / iceberg-1.12 pair
  - `tables-test-fixtures-iceberg-1.12`
  - `apps/spark-3.5` (it reuses `apps/spark` sources)
  - matching `settings.gradle` include and rename lines
- **Blocker for keeping 1.2 alive alongside.**
  - The 1.2 / Spark 3.1 consumers (apps:spark, libs:datalayout, spark-3.1 runtime, fixtures-1.2) depend on the server modules today, downgraded to 1.2 with `!!`. With 1.12, those modules become Java 17 bytecode and are linked against the 1.12 API, so running them under Spark 3.1 or Java 8 is not credible **[inferred]**.
  - Shared test sources that touch changed APIs cannot compile on both lines **[ran: ReadBridgeTest]**.
- **Cheapest overall:** replace 1.5 with 1.12 (rename the convention and bump the property) and decide separately whether the 1.2 / Spark 3.1 line is retired or keeps its own pinned copies.

## Q6. How CI and other developers could resolve the fork without a local publish

One line of evidence each, from this environment:

| option | viable? | evidence |
|---|---|---|
| GitHub Packages on the fork (`maven.pkg.github.com/mkuchenbecker/iceberg`) | yes, from GitHub Actions; not testable here | the proxy refuses CONNECT with 403 **[ran]**. GitHub Packages Maven needs a token even to read public packages **[known limitation, not tested]** |
| JitPack (`com.github.mkuchenbecker:iceberg`) | unlikely | the proxy refuses `jitpack.io` with 403 **[ran]**. JitPack would also rebuild the whole Iceberg build on its side; LinkedIn's branch carries a `jitpack.yml` |
| Maven Central under a fork-owned group (for example `io.github.mkuchenbecker`) | yes, mirrors LinkedIn's own route | `repo.maven.apache.org` and `repo1.maven.org` are reachable (200). Sonatype hosts `central.sonatype.com` and `s01.oss.sonatype.org` are refused here **[ran]**. Needs namespace verification and signing. Changing the group also breaks OpenHouse's group-keyed excludes (Q1) |
| Static Maven repo committed to a branch of the fork, read through `raw.githubusercontent.com` | yes, needs no auth for a public repo | `raw.githubusercontent.com/mkuchenbecker/iceberg/irc/reapply-all/settings.gradle` returns 200; API says the fork is public **[ran]**. `*.github.io` (Pages) is refused 403 |
| Gradle composite build (`includeBuild` of an Iceberg checkout, with `dependencySubstitution` `com.linkedin.iceberg:*` to the projects) | plausible, untested | both repos use Gradle 8.14.5 **[ran: wrapper properties]**. Iceberg's `settings.gradle:103` does `System.getProperty("knownScalaVersions").split(",")`, which relies on `systemProp.*` from its own `gradle.properties`; that likely needs `-D` flags on the OpenHouse command line **[inferred]** |
| Git submodule plus a CI pre-step `publishToMavenLocal` | yes | `git ls-remote https://github.com/mkuchenbecker/iceberg refs/heads/irc/reapply-all` returns `f180ae589...` **[ran]**. The pre-step took 2m16s here with a warm cache; it costs a full Iceberg build on a cold CI cache |

## Saved artifacts
- `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/i6/iceberg-plumbing.diff`
- `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/i6/openhouse-scout.diff`
- `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/i6/iceberg-publish-cmd.txt`, `iceberg-publish.log`
- `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/i6/oh-compile{1,2,3}.log`, `oh-test-*.log`, `oh-baseline-catalog.log`, `deps-*.txt`, `oh-coords.txt`
- the private Maven repo `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/i6/m2` (artifacts at version `1.12.0-mkscout`)
- the worktrees `/home/user/oh-i6` and `/home/user/iceberg-i6`, both left in place and uncommitted
