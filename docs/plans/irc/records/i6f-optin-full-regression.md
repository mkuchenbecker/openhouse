# I6f: full regression of the opt-in Iceberg 1.12 build (`-PicebergFork`)

Date 2026-11-23. Branch `irc/i6f-optin-full-regression` in mkuchenbecker/openhouse, from `irc-integration`
b07613f9, merged forward to c174e388 (pull requests 102 and 103). "Ran" means run here for this record;
"read" means taken from code, configuration or earlier records only. JDK 17, `--max-workers=2`.

## Bottom line

Not ready to become the default yet, but close. Every test task that `./gradlew build` runs under the switch
passes on the final head (after two build-wiring fixes and one per-line test expectation made here), the
Docker-backed tests pass, and the CI's Docker integration step passes with fork-built service images. What
blocks the flip:

1. Two Spark SQL differences on 1.12 that no decision accepts yet (delta-harness, ran): explicit time travel
   with `spark.wap.branch` set fails, and `ALTER TABLE ... ADD COLUMN ... DEFAULT` is refused. Each needs a
   decision (accept, or patch the fork) before the default moves.
2. The build cannot do what CI does: `./gradlew -PicebergFork clean build` fails, because `clean` deletes the
   published fork in `build/iceberg-fork/repo` after configuration (ran). The fork's artifacts must live
   outside `build/` or be hosted (item I6d).
3. The deployables are not ready for the 1.2 line to disappear: the jobs-scheduler image and both Spark
   images copy the Spark 3.1 apps uber jar, `dockerPrereqs` names the 1.2 projects, and the Spark images run
   Java 8 while everything under the switch is Java 17 bytecode (read).
4. `iceberg-aws` stays at LinkedIn 1.2.0.6 under the switch: 1.2-era `S3FileIO` linked against 1.12 core,
   untested against real S3, and its `iceberg-build.properties` makes snapshot summaries claim
   `Apache Iceberg 1.2.0.6` (ran, read).
5. The scheduler cannot detect its Iceberg line (W13, known) and the 1.12 runtime is built for Spark 3.5.9
   while every test here runs Spark 3.5.2 (read; not run on 3.5.9).

## Part 1: the whole build under the switch (ran)

Everything `./gradlew -PicebergFork build --dry-run` lists was run, as task groups with `--continue`. Counts are
tests / failed / skipped on the final head c174e388 + this branch (96219ee4 and f8a33773), with a Docker daemon
started for the run (see Docker below). "First run" is the same task on b07613f9 before the fixes.

| Task | First run (b07613f9) | Final head | Class of each first-run failure |
| --- | --- | --- | --- |
| `assemble`, all `checkstyleMain/Test(Fixtures)`, all `spotbugsMain/Test(Fixtures)/Service`, `spotlessCheck` (every project under the switch) | pass | pass | none |
| `:services:housetables:test` | 414 / 323 / 0 without Docker | 414 / 0 / 0 | (d) Testcontainers MySQL, no Docker daemon; identical failing set on the default build; 414 / 0 on both builds with Docker |
| `:services:jobs:test` | 52 / 0 / 0 | 52 / 0 / 0 | |
| `:services:common:test` | 37 / 0 / 0 | 37 / 0 / 0 | |
| `:services:optimizer:test` | 22 / 21 / 0 without Docker | 23 / 0 / 0 | (d) as housetables; 23 / 0 on both builds with Docker |
| `:libs:optimizer:optimizer-common:test` | 24 / 24 / 0 without Docker | 24 / 0 / 0 | (d) as housetables |
| `:libs:optimizer:scheduler:test` | 25 / 6 / 0 without Docker | 25 / 0 / 0 | (d) as housetables |
| `:libs:optimizer:analyzer:test` | 59 / 0 / 0 | 59 / 0 / 0 | |
| `:libs:optimizer:binpack:test` | 23 / 0 / 0 | 23 / 0 / 0 | |
| `:libs:datalayout:test` | 17 / 14 / 0 | 17 / 0 / 0 | (b) build wiring, fixed: commits bd527d8b, f598a5e5 |
| `:apps:openhouse-spark-apps-1.5_2.12:test` (retention, snapshot expiration, orphan files, compaction, stats, replication and the rest) | 304 / 1 / 5 | 304 / 0 / 5 | (b) new 1.12 difference, per-line expectation: commit ae9f5910 |
| `:integrations:spark:spark-3.5:openhouse-spark-3.5-runtime_2.12:test` | 24 / 0 / 0 | 27 / 0 / 0 | (count grew with pull request 103) |
| `:integrations:java:iceberg-1.5:openhouse-java-iceberg-1.5-itest:test` | 136 / 0 / 0 | 136 / 0 / 0 | |
| `:integrations:java:openhouse-catalog-capabilities:test` | 28 / 0 / 0 | 45 / 0 / 0 | (count grew with pull request 103) |
| `:cluster:storage:test` | 11 / 0 / 0 | 11 / 0 / 0 | |
| `:client:secureclient:test` | 20 / 0 / 0 | 20 / 0 / 0 | |
| `:tables-test-fixtures:tables-test-fixtures-iceberg-1.5_2.12:test` | 37 / 0 / 0 | 39 / 0 / 0 | |
| Spark 3.5 itest `catalogTest` / `statementTest` / `test` | not run first (known 8 view failures) | 269 / 0 / 1, 137 / 0 / 0, 124 / 0 / 0 | the 8 known view failures are gone with pull request 102 (V10a) |
| `:iceberg:azure:test` | 0 tests (only a container helper in `src/test`) | same | |
| `:client:hts`, `jobsclient`, `optimizerclient`, `tableclient`, `:cluster:configs`, `:cluster:metrics`, java 1.5 runtime, `dummytokens`, `analyzerapp`, `schedulerapp`, `all-modules` `test` | NO-SOURCE | NO-SOURCE | |

Not re-run here because the opt-in post-merge job covers them: `:services:tables:test`, `internalcatalog`,
`htscatalog`, the Spark 4.2 modules (`test`, `extensionsTest`). They are part of `build` under the switch
(`check` depends on the Spark 4.2 `extensionsTest`).

### The failures and what was done

1. `libs:datalayout`, 14 of 17 (b, build wiring of the switch). `SparkThrowableHelper` failed to initialize:
   "Scala module 2.15.2 requires Jackson Databind version >= 2.15.0 and < 2.16.0 - Found jackson-databind
   version 2.13.4". I6g moved datalayout to Spark 3.5 under the switch, but the root `force` of databind 2.13.4
   (`build.gradle`, `excludedProjects`) still applied to it; its only consumer on that line, the Spark 3.5 apps,
   is exempt. Fix bd527d8b: under the switch only, `:libs:datalayout` joins `excludedProjects`.
2. `libs:datalayout`, then 11 of 17 (b). `ClassCastException: org.apache.orc.TypeDescription cannot be cast to
   ...shaded...TypeDescription`: the 1.5 fixtures bring the thin `iceberg-orc`/`iceberg-parquet` jars the switch
   adds (`openhouse.iceberg-conventions-1.5.2`) next to the shaded runtime. Fix f598a5e5: under the switch the
   datalayout test classpath excludes them, as the Spark 3.5 apps and itest already do. 17 / 17 after, on both
   builds. The default build's `dependencies` output for `runtimeClasspath`, `testRuntimeClasspath` and
   `compileClasspath` of `:libs:datalayout`, `:apps:openhouse-spark-apps_2.12` and
   `:apps:openhouse-spark-apps-1.5_2.12` is byte-identical before and after (ran).
3. `SparkMoRFunctionalTest.testCompactionCanRemovePositionDeleteFiles` (b; a 1.12 behavior difference no
   decision names yet). After three merge-on-read DELETEs on one data file, `numCurrentSnapshotPositionDeleteFiles`
   is 1 on 1.12, 3 on 1.5.2. Cause (read in the fork source): `SparkWriteConf.deleteGranularity()` defaults to
   `FILE` (spark/v3.5 `SparkWriteConf.java:759-766`) and `SparkPositionDeltaWrite` merges the earlier position
   deletes of the data file into the new file and calls `rowDelta.removeDeletes` (lines 194-238); the snapshot
   summaries show `removed-position-deletes -> 1` and `-> 2`. A probe (not committed) measured every later step of
   the test on 1.12: total delete files 3, first compaction 1 added / 1 rewritten / 1 live delete (the dangling
   delete), second compaction 0 live, rows `[1,b], [2,e]`, all as on 1.5.2. Fix ae9f5910: only that one
   expectation is per line via `IcebergLine`. Ran on both builds (class 4 / 0 each). This is upstream Iceberg
   behavior (fewer delete files); I propose accepting it with the D14 rule; it is not yet in a decision.

### Docker (ran)

- No Docker daemon runs in this container, but `dockerd` is installed and starts as root. I started a private
  daemon (`/tmp/i6fdk`, socket `unix:///tmp/i6fdk/sock`) and ran with `DOCKER_HOST` set; Docker Hub pulls work
  through the proxy, `quay.io` is refused.
- With it, every Testcontainers suite passed on both builds: housetables 414 / 0, optimizer 23 / 0,
  optimizer-common 24 / 0, scheduler 25 / 0.
- The CI integration step (`build-run-tests.yml`: `docker compose ... oh-only up -d --build`, then
  `scripts/python/integration_test.py` with the dummy token) passed with images built from the fork build's
  `tables.jar` and `housetables.jar` (Java 17 classes, `iceberg-core-1.12.0-ohfork.28742df3e538.jar` inside):
  "All tests passed successfully". The tables service ran on the image's Java 23-ea. Difference from CI: the
  `oauth` service (Keycloak from quay.io) could not be pulled, so only `openhouse-tables` and its dependencies
  (housetables, prometheus, opa) were started; the integration script does not use oauth (read).
- `dataloader-tests.yml` (oh-hadoop-spark recipe) was not run: its Spark images need the Spark 3.1 apps jar and
  run Java 8 (read, Part 3).

### What the switch still cannot build or run

- `./gradlew -PicebergFork clean build` (what CI does) fails: `clean` deletes `build/iceberg-fork/repo` after
  `gradle/iceberg-fork.gradle` checked its marker, and the next compile cannot resolve `iceberg-core` (ran with
  `clean :iceberg:openhouse:htscatalog:compileJava`). Today the opt-in CI job never cleans.
- `dockerPrereqs`, `dockerBuild` (they name `:apps:openhouse-spark-apps_2.12` and the Spark 3.1 runtime), the
  jobs-scheduler image (copies `build/openhouse-spark-apps_2.12/libs/*-uber.jar`), both Spark images (read).
- The Iceberg 1.2 / Spark 3.1 projects and their tests (excluded, D15).
- Spark 3.5.9: the fork's `iceberg-spark-runtime-3.5` is built against Spark 3.5.9 (fork
  `gradle/libs.versions.toml`), OpenHouse compiles and tests against 3.5.2 (not run on 3.5.9).

### Other findings

- `iceberg-aws` is pinned at `com.linkedin.iceberg:iceberg-aws:1.2.0.6` in `openhouse.iceberg-aws-conventions`
  for `services:tables`, `internalcatalog` and `cluster:storage`, under both builds. Under the switch its
  1.2-era `org.apache.iceberg.aws.*` classes (e.g. `S3FileIO`, used by `FileIOConfig`/`FileIOManager`) are
  linked against 1.12 core; only mock S3 tests exercise it. Its jar carries `iceberg-build.properties`, which
  wins over core's, so `IcebergBuild.version()` and every snapshot summary written in the apps tests say
  `Apache Iceberg 1.2.0.6 (commit 1cf3a9a0...)` on the fork build (ran, seen in the probe). Product problem on
  the 1.12 line: before the flip, move `iceberg-aws` to the fork's version (the fork can publish it with the same
  script) and test S3 for real.

## Part 2: the delta-harness on 1.12 (ran)

Change: `HARNESS_ICEBERG_FORK=1` in `run-openhouse.sh` (commit 788c43a6) adds `-PicebergFork` to the classpath
resolution, uses its own work directory, and refuses to run when the classpath's Iceberg runtime is not the
requested line. Unset, the script behaves as before: the default `delete parquet` slice gave 237 passed,
1 skipped, runtime `iceberg-spark-runtime-3.5_2.12-1.5.2.24.jar` (ran after the change). On the fork the
classpath carries only fork Iceberg jars plus `iceberg-aws-1.2.0.6`, with Spark 3.5.2.

Runs (stub mode, `HARNESS_PARALLELISM=2`, `-Xmx3g -XX:MaxDirectMemorySize=512m -XX:ReservedCodeCacheSize=160m`,
private TMPDIR):

| Run | Cases | Passed | Failed | Skipped | Wall |
| --- | --- | --- | --- | --- | --- |
| Baseline, default build, legacy (BASELINE-2026-11-22.md) | 2574 | 2560 | 3 | 11 | 1365 s |
| Fork, slice `delete parquet`, legacy | 238 | 237 | 0 | 1 | 197 s |
| Fork, full, legacy (on b07613f9 + fixes) | 2574 | 2554 | 9 | 11 | 1346 s |
| Fork, full, rest-shim (on b07613f9 + fixes) | 2574 | 2554 | 9 | 11 | 1264 s |
| Fork, full, legacy, final head (c174e388 + this branch) | 2574 | 2554 | 9 | 11 | 1264 s |

The three full fork runs have identical outcomes case by case. The 3 lock failures and the 11 skips are the
baseline's, with the same messages. No case passed only on retry. `surface.conc.rtasVsAppend @ orc` passed on
rest-shim (the stub race of `11-harness-via-seam.md` D1 did not occur). Six cases differ from the baseline:

1. `interact.branch.ttBeforeBranchPoint @ parquet` and `@ orc`: NEW, not accepted by any decision.
   Asserts: on a WAP-enabled table with branch `tb`, while `spark.wap.branch=tb` is set, an explicit
   `TIMESTAMP AS OF <first commit>` and `VERSION AS OF <first snapshot>` read main's history (3 rows).
   On 1.12: `IllegalArgumentException: Cannot override ref, already set snapshot id=...` (the branch-head read and
   the snapshot-id read before setting the conf passed). Cause (read): `SparkReadConf.branch()` returns the WAP
   branch when `write.wap.enabled` and `spark.wap.branch` are set (fork spark/v3.5 `SparkReadConf.java:118-127`);
   `SparkScanBuilder.buildBatchScan` applies the time-travel snapshot and then `useRef(branch)`
   (`SparkScanBuilder.java:508-518`), which `SnapshotScan.java:123` refuses. LinkedIn 1.5.2.24 has the same WAP
   logic in `SparkReadConf.branch()` (bytecode read); why 1.5.2 reached main's history was not determined. User
   effect: a session that stages WAP writes cannot time travel on that table until it unsets `spark.wap.branch`.
2. `fork.colDefault.addColumnInert @ parquet` and `@ orc`: NEW as a statement-level change; in substance under
   D5 and D11(d) (no Spark SQL statement sets a default on either line), but the change itself is named nowhere.
   Asserts: `ALTER TABLE t ADD COLUMN c int DEFAULT 5` is accepted, the default is not persisted, old rows read
   NULL, an INSERT omitting `c` fails with `CANNOT_FIND_DATA`. On 1.12: the ALTER fails with
   `UnsupportedOperationException: Cannot add column c since setting default values in Spark is currently
   unsupported` (fork `Spark3Util.java:236-241`). Silently dropped DEFAULT becomes a loud refusal.
3. `fork.colDefault.readApplyProbe @ core`: ACCEPTED, D5 and D11(b). Asserts: a non-null `initial-default`
   committed through `TableMetadata` on a format v2 Hadoop-catalog table persists. On 1.12: `IllegalStateException:
   Invalid schema for v2: Invalid initial default for c: non-null default (5) is not supported until v3`. On the
   1.5.2 release the case takes its "API absent" branch and passes trivially.
4. `fork.fileReplicationFactor @ core`: harness-internal, not a behavior difference; the patch itself is kept
   (D4). Asserts by reflection that `OutputFileFactory` stamps `file-replication-factor` into the map returned by a
   private `getProperties()`. On 1.12: `NoSuchMethodException: OutputFileFactory.getProperties()`. The reapplied
   LinkedIn patch has the constant and `Builder.replicationFactor(short)` (both asserted and passing) and passes
   the property straight to `FileIO.newOutputFile(location, Map)` (fork `OutputFileFactory.java:117-127`), so the
   private method the case reflects on no longer exists. `fork.deleteFileReplication @ mor` passes. The case
   expectation was not changed.

Real-HTS mode (`HARNESS_REAL_HTS=1`) was not run on the fork.

## Real product problems on 1.12

- WAP branch plus time travel (Part 2, item 1): an Iceberg 1.12 Spark behavior; no OpenHouse code is involved.
  Needs a decision: accept, or patch `SparkScanBuilder`/`SparkReadConf` in the fork so explicit time travel wins.
- `iceberg-aws` 1.2.0.6 on the 1.12 line (Part 1, other findings): mixed Iceberg versions in the service and
  a wrong version string in every snapshot summary.
- None found in OpenHouse code itself: every OpenHouse test task passes on 1.12.

## Part 3: what making the 1.12 line the default would take

Each point is marked ran or read.

1. Decide the open differences (ran): the WAP time-travel failure and the ADD COLUMN DEFAULT refusal, plus the
   position-delete count change this branch made per line. Until then the flip changes Spark SQL behavior without
   a decision.
2. Artifacts (ran, read). Today `publish-local.sh` builds the pinned commit from source: 52 s here with Gradle's
   build cache warm (two Iceberg builds, 26 s and 21 s), 1 min 43 s cold-ish in `25-optin-green-result.md`,
   estimated 60 to 75 minutes total for a cold CI job in `32-optin-green-ci-result.md`; about 410 MB on disk.
   For a default build it must survive `clean` (move the repository out of `build/`, e.g. under
   `~/.gradle` or a root `.iceberg-fork/`, or host it, item I6d), every developer runs it once per pin, and CI
   caches it as the opt-in job does. Hosting (I6d) removes the step; D7 says no binaries are published today.
3. Build files (read). `build.gradle`: `iceberg_1_5_version` becomes the fork version, apply
   `gradle/iceberg-fork.gradle` unconditionally, replace the avro/orc forces by the pinned ones;
   `openhouse.java-minimal-conventions` targets 17; `settings.gradle` drops the 1.2 projects (D3); every
   `rootProject.hasProperty('icebergFork')` branch (about 15 files: datalayout, apps-1.5, Spark 3.5 itest, 1.5
   runtime, 1.5 fixtures, conventions, Spark 4.2 projects) keeps its fork arm; `iceberg-aws` moves to the fork.
4. The 1.2 / Spark 3.1 projects (read; D3, D15). Remove `:apps:spark` (but keep its sources, which
   `apps/spark-3.5` and the 1.5 projects reuse by directory: move them into the 1.5 projects first),
   `:integrations:java:iceberg-1.2:*` (same: `IcebergLine` and the Java client live there), Spark 3.1, and the
   1.2 fixtures (the dummy token CI uses is under `tables-test-fixtures-iceberg-1.2/src/main/resources`: move it
   and update `build-run-tests.yml` and `dataloader-tests.yml`). `libs:datalayout` loses its Spark 3.1 arm.
   Published artifacts `openhouse-spark-runtime_2.12` (Spark 3.1) and `openhouse-spark-apps_2.12` stop; any
   consumer of them must move to the 3.5 ones.
5. Java 17 at runtime (read). The build already uses JDK 17 (README, SETUP, CI `setup-java` 17), but the default
   build emits Java 8 bytecode; under the switch everything is 17. Services: the tables, housetables, jobs and
   scheduler images use `openjdk:23-ea-11-slim` (an early-access image) and the optimizer images
   `eclipse-temurin:17-jre-jammy`, so the services run (ran for tables and housetables). The Dockerfiles still
   set a Java 8 `PATH` entry and `/usr/java/default` link to `JDK-1_8_0_172` (harmless, misleading). The Helm
   values run `java -cp ...` from the image. Spark: both Spark images end on
   `bde2020/hadoop-namenode:*-java8` and run `java -jar /dummytokens.jar` on Java 8 at build time; with Java 17
   bytecode for the OpenHouse runtime, apps and dummytokens they break, so they need a Java 17 base (Spark 3.5
   supports 17; Livy is built with Java 11). Any user Spark 3.5 cluster that loads the OpenHouse runtime jar must
   run Java 17.
6. Deployables that name the 1.2 line (read): `dockerPrereqs` (Spark 3.1 runtime, `openhouse-spark-apps_2.12`
   shadowJar), `jobs-scheduler.Dockerfile` (`APP_NAME=openhouse-spark-apps_2.12`), both Spark dockerfiles
   (`openhouse-spark-apps_2.12-uber.jar`); all move to `openhouse-spark-apps-1.5_2.12`. W13 (the scheduler
   cannot detect its line) stays open: the apps take the Spark runtime compile-only, so whether the 1.5 apps
   uber jar carries `IcebergLine` was not checked.
7. CI (read). `build-run-tests.yml` `./gradlew clean build` becomes the 1.12 build plus the publish step and its
   cache (as in `post-merge-build.yml`'s `iceberg-fork-build` job); `dataloader-tests.yml` the same plus the Spark
   image change; `build-tag-publish.yml` publishes the 1.12 artifacts and builds the images; the opt-in job
   becomes an opt-out job or goes away; `renovate.yml` (linkedin/iceberg sync) loses its purpose for this line.
   CI runners have Docker, so the Testcontainers suites keep running.
8. What remains of LinkedIn's 1.5.2 line and whether an opt-out is cheap (read). After the flip 1.5.2 is used by
   nothing unless kept as `-PicebergLegacy`. The switch's mechanics are symmetric (one version property, the
   forces, the Java target), so an opt-out of the build files is cheap. What is not cheap: the service on Java 17
   and 1.12 types (V9, IRC routes on 1.12 APIs, `IcebergRestBeanSignaturesTest`) cannot be built against 1.5.2
   for long, so an opt-out would cover clients and Spark 3.5 only, and every per-line test keeps two
   expectations. I would keep it for one release as a client-only fallback, then drop it.
9. Order that keeps every step valid (read):
   (a) decide the open differences; (b) move the fork repository out of `build/` (or host it) and make
   `clean build` work under the switch; (c) move `iceberg-aws` to the fork; (d) move the reused 1.2 sources and the
   dummy token into the 1.5 projects (no behavior change on either build); (e) move the scheduler and Spark
   images to the 1.5 apps jar and a Java 17 base, still on the default build; (f) run the default CI job also
   with `-PicebergFork` for a few merges (both green); (g) flip: the property's meaning inverts, 1.2 projects
   removed (D3), Java 17 target; (h) remove the opt-out after a release.

## What changed (this branch)

| Commit | Why |
| --- | --- |
| bd527d8b | datalayout is built on Spark 3.5 under the switch and needs Jackson 2.15; the root force of 2.13.4 broke all its Spark tests |
| f598a5e5 | the switch's thin iceberg-orc/parquet jars next to the shaded runtime broke datalayout's ORC reads |
| ae9f5910 | 1.12 keeps one live position delete file per data file after several DELETEs; one expectation per line |
| 788c43a6 | the delta-harness can run on the opt-in build (`HARNESS_ICEBERG_FORK=1`), with a guard against the wrong line |
| 96219ee4 | merge of irc-integration (pull requests 102, 103) so the final runs are on the current head |
| f8a33773 | the fork docs list the newly found differences, marked as not yet accepted |

## Not run, and why

- `:services:tables`, `internalcatalog`, `htscatalog`, Spark 4.2 tests: covered by the opt-in post-merge job.
- Harness real-HTS mode on the fork; harness rest-shim on the final head (the earlier rest-shim run was on
  b07613f9 plus the fixes).
- Spark 3.5.9; real S3; the oauth container (quay.io refused); `dataloader-tests.yml`; the Spark images;
  `build-tag-publish.yml`.
- The Iceberg 1.2 / Spark 3.1 tests under the switch (excluded by design, D15).

## Pull request

Draft pull request 105 into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/105, head
f8a33773.
