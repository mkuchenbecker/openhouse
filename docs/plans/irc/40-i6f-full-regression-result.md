# Full regression of the opt-in Iceberg 1.12 build (I6f): result

> Correction, 2026-11-23: %s In CI the opt-in job failed on five post-merge runs in a row (6015e111 to bfdcf266) and
> first passed on run 45120792269 at 3222cf78. See `43-optin-ci-red-result.md`. The text below is unchanged.

Bottom line: the 1.12 line is not ready to become the default, but it is close. Every test task that
`./gradlew build` runs under `-PicebergFork` passes on the final head, the Docker-backed tests pass, and the CI's
Docker integration step passes with images built from the fork build. No problem was found in OpenHouse's own
code. Written by a delegated agent, 2026-11-23, from branch `irc/i6f-optin-full-regression` (pull request 105,
merged as 7ddc5bb4), on JDK 17 with two workers. "Ran" means run here. "Read" means taken from code,
configuration or earlier records.

What blocks the flip:
1. One Spark SQL difference no decision accepts (ran): an explicit time-travel read fails while `spark.wap.branch`
   is set. The `ADD COLUMN ... DEFAULT` refusal and the position-delete count needed a decision too (now D31).
2. `./gradlew -PicebergFork clean build` fails, because `clean` deletes the published fork in `build/` (ran).
3. The deployables still name the 1.2 line, and the Spark images run Java 8 (read).
4. `iceberg-aws` stays at LinkedIn 1.2.0.6 under the switch: untested against real S3, and snapshot summaries claim
   `Apache Iceberg 1.2.0.6` (ran, read).
5. The scheduler cannot detect its Iceberg line (W13), and the runtime is built for Spark 3.5.9 while every test
   here runs 3.5.2 (read).

## The whole build under the switch (ran)

Every task `./gradlew -PicebergFork build --dry-run` lists was run, with `--continue`. Counts are tests / failed /
skipped. "First" is b07613f9 before the fixes. "Final" is c174e388 plus this branch, with a Docker daemon running.

| Task | First | Final | Class of each first failure |
| --- | --- | --- | --- |
| `assemble`, checkstyle, spotbugs, `spotlessCheck`, all projects | pass | pass | none |
| `:services:housetables:test` | 414 / 323 / 0 | 414 / 0 / 0 | (d) no Docker daemon; same set fails on the default build |
| `:services:jobs:test`, `:services:common:test` | 52 / 0 / 0, 37 / 0 / 0 | same | |
| `:services:optimizer:test` | 22 / 21 / 0 | 23 / 0 / 0 | (d) as housetables |
| optimizer `optimizer-common`, `scheduler` | 24 / 24 / 0, 25 / 6 / 0 | 24 / 0 / 0, 25 / 0 / 0 | (d) as housetables |
| optimizer `analyzer`, `binpack` | 59 / 0 / 0, 23 / 0 / 0 | same | |
| `:libs:datalayout:test` | 17 / 14 / 0 | 17 / 0 / 0 | (b) build wiring, fixed in two steps |
| `:apps:openhouse-spark-apps-1.5_2.12:test` | 304 / 1 / 5 | 304 / 0 / 5 | (b) one 1.12 difference, per-line expectation |
| Java 1.5 itest `test` | 136 / 0 / 0 | same | |
| Spark 3.5 runtime `test`, capabilities `test` | 24, 28 / 0 / 0 | 27, 45 / 0 / 0 | counts grew with pull request 103 |
| `cluster:storage`, `client:secureclient`, 1.5 fixtures `test` | 11, 20, 37 / 0 / 0 | 11, 20, 39 / 0 / 0 | |
| Spark 3.5 itest `catalogTest`, `statementTest`, `test` | not run | 269 / 0 / 1, 137 / 0 / 0, 124 / 0 / 0 | the 8 known view failures went with pull request 102 |

### The three failures that were fixed

1. `libs:datalayout`, 14 of 17 (b, build wiring). The root `force` of Jackson databind 2.13.4 still applied to
   datalayout, which moved to Spark 3.5 under the switch, so Spark's Scala module refused to start. Fixed by putting
   datalayout in `excludedProjects` under the switch only.
2. `libs:datalayout`, then 11 of 17 (b). `ClassCastException` between `org.apache.orc.TypeDescription` and the shaded
   one: the switch adds thin `iceberg-orc` and `iceberg-parquet` jars next to the shaded runtime. Fixed by excluding
   them from datalayout's test classpath, as the Spark 3.5 apps do. 17 / 17 on both builds. The default build's
   resolved classpaths for datalayout and both apps are byte-identical (ran).
3. `SparkMoRFunctionalTest.testCompactionCanRemovePositionDeleteFiles` (b). After three merge-on-read DELETEs on one
   data file there is 1 live position delete file on 1.12 and 3 on 1.5.2. Cause (read): the delete granularity
   defaults to FILE, so each write merges earlier position deletes into the new file. A probe showed every later
   step behaves as on 1.5.2. Fixed by one per-line expectation. This is upstream behavior. D31 accepts it.

### Docker (ran)

- No Docker daemon runs in the container, so a private one was started. Every Testcontainers suite then passed on
  both builds (housetables 414, optimizer 23, optimizer-common 24, scheduler 25).
- The CI integration step passed with images built from the fork build's jars. The `oauth` image could not be
  pulled (`quay.io` is refused), so it did not start; the script does not use oauth (read).
- The switch still cannot run `clean build`, `dockerPrereqs`, `dockerBuild` or the scheduler and Spark images.

## The delta-harness on 1.12 (ran)

`HARNESS_ICEBERG_FORK=1` in `run-openhouse.sh` adds `-PicebergFork` and refuses to run when the classpath's Iceberg
runtime is the wrong line. Unset, the script behaves as before (the `delete parquet` slice gave 237 passed).

| Run (stub mode, two in parallel) | Cases | Passed | Failed | Skipped | Wall |
| --- | --- | --- | --- | --- | --- |
| Baseline, default build, legacy | 2574 | 2560 | 3 | 11 | 1365 s |
| Fork, full, legacy (b07613f9 plus fixes) | 2574 | 2554 | 9 | 11 | 1346 s |
| Fork, full, rest-shim (same) | 2574 | 2554 | 9 | 11 | 1264 s |
| Fork, full, legacy, final head | 2574 | 2554 | 9 | 11 | 1264 s |

The three full fork runs agree case by case. The 3 lock failures and 11 skips are the baseline's. Six cases
differ from the baseline:

1. `interact.branch.ttBeforeBranchPoint` at parquet and orc: NEW and not accepted. With a WAP-enabled table and
   `spark.wap.branch=tb` set, an explicit `TIMESTAMP AS OF` or `VERSION AS OF` should read main's history. On 1.12
   it fails with `IllegalArgumentException: Cannot override ref, already set snapshot id=...`. Cause (read):
   `SparkReadConf.branch()` returns the WAP branch, and `SparkScanBuilder` applies the time-travel snapshot and then
   `useRef(branch)`, which `SnapshotScan` refuses. LinkedIn 1.5.2.24 has the same WAP logic; why it reached main's
   history was not determined. A session that stages WAP writes cannot time travel on that table until it unsets
   `spark.wap.branch`.
2. `fork.colDefault.addColumnInert` at parquet and orc: `ALTER TABLE t ADD COLUMN c int DEFAULT 5` was accepted with
   the default dropped; on 1.12 it fails with `UnsupportedOperationException`. A silent drop became a loud refusal.
3. `fork.colDefault.readApplyProbe` at core: ACCEPTED (D5, D11). A non-null `initial-default` on a v2 table now
   fails with "not supported until v3".
4. `fork.fileReplicationFactor` at core: harness-internal. The case reflects on a private
   `OutputFileFactory.getProperties()` that no longer exists. The reapplied patch itself works. Case not changed.

## Real product problems on 1.12

- WAP branch plus time travel: an Iceberg 1.12 Spark behavior, being patched in the fork (D31, item I11).
- `iceberg-aws` 1.2.0.6 on the 1.12 line: mixed Iceberg versions in the service and a wrong version string in
  every snapshot summary (item V9b). None found in OpenHouse code itself.

## What making 1.12 the default would take

Each point is marked ran or read.
1. Decide the open differences (ran): the WAP time-travel failure, the `ADD COLUMN DEFAULT` refusal and the
   position-delete count. Until then the flip changes Spark SQL behavior without a decision.
2. Artifacts (ran, read). `publish-local.sh` builds the pinned commit from source: 52 s with Gradle's cache warm,
   an estimated 60 to 75 minutes for a cold CI job (`32-optin-green-ci-result.md`), about 410 MB. It must survive
   `clean` (move the repository out of `build/`, or host it, item I6d). D7 says no binaries are published today.
3. Build files (read). `iceberg_1_5_version` becomes the fork version, `gradle/iceberg-fork.gradle` applies
   unconditionally, the avro and orc forces become the pinned ones, `openhouse.java-minimal-conventions` targets 17,
   `settings.gradle` drops the 1.2 projects (D3), every `icebergFork` branch (about 15 files) keeps its fork arm,
   and `iceberg-aws` moves to the fork.
4. The 1.2 and Spark 3.1 projects (read; D3, D15). Remove `:apps:spark`, but first move the sources that
   `apps/spark-3.5` and the 1.5 projects reuse by directory. Remove `:integrations:java:iceberg-1.2:*` (the same
   care: `IcebergLine` and the Java client live there), Spark 3.1 and the 1.2 fixtures. CI's dummy token is in
   those fixtures: move it and update `build-run-tests.yml` and `dataloader-tests.yml`. The artifacts
   `openhouse-spark-runtime_2.12` and `openhouse-spark-apps_2.12` stop, so consumers must move to the 3.5 ones.
5. Java 17 at runtime (read). The default build emits Java 8 bytecode; under the switch everything is 17. The
   service images use `openjdk:23-ea-11-slim` or `eclipse-temurin:17-jre-jammy`, so services run (ran for tables
   and housetables). Both Spark images use a `-java8` base and need a Java 17 base. Any user Spark 3.5 cluster that
   loads the OpenHouse runtime jar must run Java 17.
6. Deployables that name the 1.2 line (read): `dockerPrereqs`, `jobs-scheduler.Dockerfile` and both Spark
   dockerfiles move to `openhouse-spark-apps-1.5_2.12`. W13 stays open; whether the 1.5 apps uber jar carries
   `IcebergLine` was not checked.
7. CI (read). `build-run-tests.yml` becomes the 1.12 build plus the publish step and its cache, as in
   `post-merge-build.yml`; `dataloader-tests.yml` the same plus the Spark image change; `build-tag-publish.yml`
   publishes the 1.12 artifacts and builds the images; the opt-in job becomes an opt-out job or goes away;
   `renovate.yml` loses its purpose for this line.
8. What remains of 1.5.2 (read). After the flip nothing uses it unless kept as `-PicebergLegacy`. The build-file
   mechanics are symmetric, so an opt-out is cheap, but the service on Java 17 and 1.12 types cannot be built
   against 1.5.2 for long, so it would cover clients and Spark 3.5 only, and every per-line test keeps two
   expectations. Keep it for one release as a client-only fallback, then drop it.
9. The order that keeps every step valid (read):
   (a) decide the open differences; (b) move the fork repository out of `build/` or host it, and make `clean build`
   work under the switch; (c) move `iceberg-aws` to the fork; (d) move the reused 1.2 sources and the dummy token
   into the 1.5 projects, with no behavior change on either build; (e) move the scheduler and Spark images to the
   1.5 apps jar and a Java 17 base, still on the default build; (f) run the default CI job also with
   `-PicebergFork` for a few merges, both green; (g) flip: the property's meaning inverts, the 1.2 projects go
   (D3), the target is Java 17; (h) remove the opt-out after a release.

## Not run

`:services:tables`, `internalcatalog`, `htscatalog` and Spark 4.2 tests (the opt-in job covers them); the harness
in real-HTS mode; Spark 3.5.9; real S3; `dataloader-tests.yml`; the Spark images; `build-tag-publish.yml`.
