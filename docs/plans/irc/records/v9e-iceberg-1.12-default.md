# V9e: Iceberg 1.12 is the default build; the Iceberg 1.2 / Spark 3.1 projects are removed: full record

Bottom line: done. A plain `./gradlew build` builds and tests the Iceberg 1.12 line (the Iceberg 1.5
variant, the Spark 4.2 modules and Apache's REST compatibility kit) on Java 17. LinkedIn's Iceberg 1.5.2
line is selected with `-PicebergLinkedIn` and kept as the behavior reference. The Iceberg 1.2 / Spark 3.1
projects, their Spark image and their published artifact names are gone. No Spark SQL behavior difference
was found. Not proven: the Spark image and the dataloader integration tests on a CI runner (by hand only,
as before for this line).

Written by a delegated agent on 2026-11-23, JDK 17, two Gradle workers. Marks: **[ran]** run here for this
record (CI run ids where CI is the evidence), **[read]** taken from code, configuration, a log or an
earlier record.

- Branch `irc/v9e-iceberg-1.12-default`, worktree `/home/user/oh-v9e`, base `5ea51d35`, merged forward to
  `irc-integration` at `a217cce9` (pull requests 117, 114, 119, 120).
- Draft pull request 118 into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/118
- Head `e8287715f76de1d503290f4226cd27e42ba4b10d`.
- Logs and data: `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/v9e/` (`logs/`,
  `snap/` jar entry lists and compiled test classes, `xml-*/` JUnit results, `*.classes` per-class counts,
  `probe-*.txt`, `remaining-hits.txt`, `foreign-stash/`).

## 1. What the build is now

| | Before | After |
| --- | --- | --- |
| No property | LinkedIn Iceberg 1.5.2 and 1.2, Spark 3.5 and 3.1, Java 8 target | fork Iceberg 1.12, Spark 3.5 and 4.2, compatibility kit, Java 17 target |
| `-PicebergFork` | the 1.12 line | accepted for one cycle, does nothing, prints a deprecation warning; an error together with `-PicebergLinkedIn` |
| `-PicebergLinkedIn` | did not exist | LinkedIn Iceberg 1.5.2, Spark 3.5, Java 8 target; no Spark 4.2, no kit, no deployables |
| Delta-harness | default 1.5.2; `HARNESS_ICEBERG_FORK=1` for 1.12 | default 1.12; `HARNESS_ICEBERG_LINKEDIN=1` for 1.5.2; `HARNESS_ICEBERG_FORK=1` accepted, does nothing |

- A plain build needs `scripts/iceberg-fork/publish-local.sh` run once per pinned commit per machine; every
  Gradle command, `help` and the hooks' `spotlessCheck` included, otherwise stops with a message that names
  the script and the opt-out [ran].
- `./gradlew build` now includes the compatibility kit (`check` depends on `restCompatKitTest`), and the kit
  module's own empty `test` task no longer fails with "No tests found" (work item T12) [ran].
- The reference build's docker tasks (`dockerPrereqs`, `dockerBuild`, `dockerUp`) stop with a message [ran].
- The default build refuses to publish to a remote Maven repository (section 8).

## 2. The three ported tests, and Spark 3.5 against Spark 3.1

Ported before any deletion, into `PartitionTestSpark3_5` and `AlterTableTestSpark3_5` (commit b4bf6333):

| Test | Result on Spark 3.5 / LinkedIn 1.5.2 | on Spark 3.5 / fork 1.12 | Change from the Spark 3.1 test |
| --- | --- | --- | --- |
| `testCreateTablePartitionedWithBucketTransform` | pass | pass | none |
| `testBucketPartitioningCreatesCorrectNumberOfPartitions` | pass | pass | none |
| `testSetPoliciesTablePropsBlocked` | pass | pass | asserts `BadRequestException` and "400 ", where the original asserted `Exception.class` |

[ran: before the deletion on both lines; again in the final full builds.] **No Spark SQL difference between
Spark 3.1 and Spark 3.5 for these statements.** For the policies test a probe (not committed) recorded, on
Spark 3.1 / Iceberg 1.2, Spark 3.5 / 1.5.2 and Spark 3.5 / 1.12, the same requests (three GETs, one PUT)
and the same `org.apache.iceberg.exceptions.BadRequestException: 400 , {...}` (`probe-*.txt`), so the port
pins that class.

## 3. What was deleted

- Projects (46 files): `:apps:spark`, `:integrations:java:iceberg-1.2:openhouse-java-runtime` and
  `-itest`, `:integrations:spark:spark-3.1:openhouse-spark-runtime` and `-itest`,
  `:tables-test-fixtures:tables-test-fixtures-iceberg-1.2`; their `settings.gradle` entries;
  `buildSrc/.../openhouse.iceberg-conventions-1.2.gradle`; `iceberg_1_2_version` and `spark_version`.
- `infra/recipes/docker-compose/common/spark/spark-base-hadoop2.8.dockerfile`,
  `infra/recipes/docker-compose/iceberg-fork.env`, the old `oh-hadoop-spark/jobs.yaml` (replaced by the
  former `jobs-iceberg-fork.yaml`), `.github/workflows/dataloader-tests-iceberg-fork.yml`,
  `.github/workflows/iceberg-fork-build.yml` (now `iceberg-linkedin-build.yml`).
- The 1.2.x rule of `.github/renovate.json`.
- Published Maven names that are no longer built [ran: `all-modules` POM on both lines, 30 OpenHouse
  modules and 12 classifier entries, the same on both; read: the six deleted build files applied
  `openhouse.maven-publish`]: `openhouse-spark-runtime_2.12`, `openhouse-spark-apps_2.12`,
  `tables-test-fixtures_2.12`, `openhouse-java-runtime` (each also `-uber`), `openhouse-java-itest`,
  `openhouse-spark-itest`. No new name appears: the Spark 4.2 modules and the kit are not published.
- The `jobs-scheduler` image keeps its name; inside it `APP_NAME` and the jar are
  `openhouse-spark-apps-1.5_2.12`. Jar names inside the Spark image are unchanged.
- Check name that disappears: "iceberg-fork-build / Opt-in build against the Iceberg fork"; new:
  "iceberg-linkedin-build / Reference build on LinkedIn's Iceberg 1.5.2".

## 4. Tests: counts per task and every difference

Final head, `./gradlew clean build spotlessCheck --continue --no-build-cache` (every task executed, nothing
from the build cache), with a private Docker daemon for the Testcontainers suites. Tests / failed / skipped.

| Task | No property (1.12), head e8287715 | `-PicebergLinkedIn` (1.5.2), head e8287715 | `irc-integration` a217cce9, its default (1.5.2) / its `-PicebergFork` |
| --- | --- | --- | --- |
| `:services:tables:test` | 1595 / 0 / 0 | 1595 / 0 / 0 | 1595 / 1595 |
| `internalcatalog`, `htscatalog` | 319, 20 / 0 / 0 | 319, 20 / 0 / 0 | same |
| `:services:housetables:test`, `jobs`, `common` | 414, 52, 37 / 0 / 0 | the same | same |
| optimizer service, `optimizer-common`, `scheduler`, `analyzer`, `binpack` | 23, 24, 25, 59, 23 / 0 / 0 | the same | same |
| `secureclient`, `storage`, `libs:datalayout` | 20, 11, 17 / 0 / 0 | the same | same |
| capabilities `test` | 45 / 0 / 0 | 45 / 0 / 0 | same |
| Java 1.5 itest | 136 / 0 / 0 | 136 / 0 / 0 | same |
| fixtures 1.5 `test` | 41 / 0 / 0 | 41 / 0 / 0 | 41 / 41 |
| Spark 3.5 runtime `test` | 28 / 0 / 0 | 28 / 0 / 0 | 28 / 28 |
| Spark 3.5 itest `catalogTest` | 272 / 0 / 1 | 272 / 0 / 1 | 270 / 270 (1 skipped) |
| Spark 3.5 itest `statementTest` | 137 / 0 / 0 | 137 / 0 / 0 | 137 / 137 |
| Spark 3.5 itest `test` | 137 / 0 / 0 | 137 / 0 / 0 | 136 / 136 |
| Spark 3.5 apps `test` | 304 / 0 / 5 | 304 / 0 / 5 | 304 / 304 (5 skipped) |
| Spark 4.2 runtime `test` | 177 / 0 / 0 | not in the build | none / 177 |
| Spark 4.2 itest `test`, `extensionsTest`, `smokeTest` | 41, 24, 65 / 0 / 0 | not in the build | none / 41, 24, 65 |
| `restCompatKitTest` | passes: 160 kit tests, 98 failed, 23 skipped, exactly the listed 121 | not in the build | none / the same |
| Spark 3.1 itest, Java 1.2 itest, Iceberg 1.2 apps, fixtures 1.2 | deleted | deleted | 350, 90, 298 (5 skipped), 41 / not in the build |
| **Total without the kit** | **4046 / 0 / 6** | **3739 / 0 / 6** | |
| Gradle | BUILD SUCCESSFUL in 34 min 27 s, 406 tasks executed, 8 up to date, 0 from cache | BUILD SUCCESSFUL in 29 min 36 s, 373 executed, 8 up to date, 0 from cache | |

The last column is [read]: T10's and V9c's records and the coordinator's counts for pull requests 117, 119
and 120; the Iceberg 1.2 apps count is [ran] (298 / 0 / 5 in step b, before the deletion). `spotlessCheck`
was part of both commands and passed. The same two commands on the head before pull request 120
(0edd9f60) [ran]: 4029 / 0 / 6 without the kit (36 min, 335 executed) and 3723 / 0 / 6 (32 min, 308
executed); the differences to the final head are pull request 120's tests (tables 1579 to 1595, Spark 4.2
itest `test` 40 to 41).

Differences in which tests run, against `irc-integration` at `a217cce9` (counts there: the coordinator's
for pull requests 117, 119 and 120, T10's and V9c's records):

1. Added by this change: 3 test methods (section 2): Spark 3.5 itest `catalogTest` 270 to 272, `test` 136
   to 137, on both lines.
2. Gone with the Iceberg 1.2 / Spark 3.1 line: the Spark 3.1 itest (350 tests: `catalogTest` 87,
   `statementTest` 126, `test` 137), the Iceberg 1.2 Java itest (90), the Iceberg 1.2 apps tests (298, 5
   skipped) and the Iceberg 1.2 fixtures tests (41). By class [ran: compiled test classes before and after]:
   only ten classes existed nowhere else, the Spark 3.1-only `PartitionTest`, `AlterTableSchemaTest`,
   `AlterTableTest`, `CreateTablePartitionedTest`, `CreateTableTest`, `CreateTableWithPropsTest`,
   `DescribeTableTest`, `DropTableTest`, `ShowTablesTest`, `InsertIntoTableTest`. Every method of them has
   a Spark 3.5 counterpart that runs (T10's table plus the three ports). All other classes of those four
   projects are the shared classes, which the 1.5 projects still compile and run on both lines.
3. Nothing else: the compiled test classes of every surviving project are the same before and after on
   each line [ran: `snap/*.testclasses`], apart from classes pull requests 119 and 120 added; my diff
   against `irc-integration` changes test code in the two ported classes only (other test files: comments).
4. A test that ran on the old default job and now runs on the default job only on 1.12 still runs on
   1.5.2 in the reference job: both lines run the same 3,7xx tests of the shared projects (the per-class
   counts of the two lines are identical [ran: `diff` of `final-*.classes`]).

## 5. Jar comparison

`unzip -Z1 <jar> | sort` of every jar under `build/*/libs` after `assemble shadowJar` (`snap/`):

| Comparison | Jars | Entries | Differences |
| --- | --- | --- | --- |
| `-PicebergFork` before (b4bf6333) against no property after the flip (98328ea2) | 46 | 351,995 | none |
| default before against `-PicebergLinkedIn` after the flip | 52 | 570,867 | none |
| default before against default after step b (datalayout, tables tests) | 52 | 570,867 | none |
| no property after the deletion (dec16c80) | 46 | 351,995 | none |
| `-PicebergLinkedIn` after the deletion | 42 | 340,439 | the 10 jars of the deleted projects are gone; the Spark 3.5 apps jar and uber jar gain `log4j2.properties` |
| `-PicebergLinkedIn` on the final head (e8287715) against default before | 42 | 340,445 | as the row above, plus `ResolveRenameTableTarget` (3 classes) in the Spark 3.5 runtime jar and uber jar, added by pull request 119 |
| no property on the final head against `-PicebergFork` before | 46 | 352,007 | `ResolveRenameTableTarget` (3 classes) in the Spark 3.5 and Spark 4.2 runtime jars and uber jars, added by pull request 119; nothing else |

Every surviving published jar is in these lists. So: before under `-PicebergFork` equals after on the
default build, and before on the default build equals after under the opt-out, except for
`log4j2.properties` in the Spark 3.5 apps jars on the reference line and the classes pull request 119
added on both. (The final reference-line snapshot also holds three `*-test-fixtures.jar` files the test
tasks of the preceding full build left in `build/*/libs`; `assemble shadowJar` alone does not build them.)

`log4j2.properties` on the reference line is deliberate: the per-line condition around it was removed with
the jar that used to carry it.

## 6. Delta-harness on the new default

[ran] `GRADLE_BIN=<repo>/gradlew FORCE_CP=1 HARNESS_PARALLELISM=2 ./run-openhouse.sh` (stub mode, legacy
catalog path, no line variable, private TMPDIR) on the final head e8287715; the classpath's runtime is
`iceberg-spark-runtime-3.5_2.12-1.12.0-ohfork.a88f556c5067.jar`:

| Run | Cases | Passed | Failed | Skipped |
| --- | --- | --- | --- | --- |
| I6f, 1.12 line, recorded (pin 28742df3) | 2574 | 2554 | 9 | 11 |
| New default, head e8287715 | 2574 | 2556 | 7 | 11 |
| New default, before pull requests 114, 119 and 120 (3c2bbb5a) | 2574 | 2556 | 7 | 11 |

The result equals I6f's record apart from the two `interact.branch.ttBeforeBranchPoint` cases (Parquet,
ORC), which pass since the pin a88f556c. The 7 failures are I6f's other 7 with the same messages: the three
lock cases of the baseline, `fork.colDefault.addColumnInert` on Parquet and ORC (D31),
`fork.colDefault.readApplyProbe` (D5, D11) and `fork.fileReplicationFactor` (harness-internal). The 11
skips are the baseline's. No other change; no case passed only on retry; the two runs here have identical
outcomes case by case (`harness-default-*.outcomes`).

The reference selection works [ran]: `HARNESS_ICEBERG_LINKEDIN=1 ./run-openhouse.sh delete parquet` ran on
`iceberg-spark-runtime-3.5_2.12-1.5.2.24.jar`: 238 cases, 237 passed, 1 skipped, as the baseline's slice.
Both variables together stop the script. The full harness was not run on the reference line.

## 7. CI

[ran] Pull request 118, head e8287715, run 45183635403, both jobs success at the first attempt (no job
was re-run on any head):

| Job | Result | Time | Notes |
| --- | --- | --- | --- |
| `build-run-tests / Build and Run Tests` (job 137723511955), the default 1.12 build | success | 27 min 08 s | fork restored from the cache in 3 s; compile 2:42; `build --continue` 20:22; Docker steps 3:39 |
| `iceberg-linkedin-build / Reference build on LinkedIn's Iceberg 1.5.2` (job 137723512358) | success | 18 min 43 s | `-PicebergLinkedIn clean build --continue` |

The Docker steps on the runner [read from the job's log]: the scheduler image built and its first INFO
line found; `oh-only` up, both services healthy, `integration_test.py` passed; the tables container runs
`openjdk version "23-ea"` and holds `BOOT-INF/lib/iceberg-core-1.12.0-ohfork.a88f556c5067.jar` and no other
`iceberg-core`.

The previous head, 0edd9f60, run 45170041383 [ran]: default job 137676899598 success in 24 min 09 s,
reference job 137676899950 success in 18 min 53 s ("BUILD SUCCESSFUL in 18m 30s, 316 actionable tasks:
266 executed, 50 from cache"). Earlier heads: 98328ea2 was pushed with the workflows not yet flipped (its
default job was expected to be red); 81440114's run 45169193291 was cancelled by the next push.

CI minutes per pull request: before, 30 to 34 for the default job plus 15 to 18 for the opt-in job, 46 to
52 in all (C9's record). Now 24 to 27 plus 19: **43 to 46 minutes** (43:02 and 45:51 in the two runs),
wall-clock 24 to 27 minutes, the longer job. The reference job would be 4 to 5 minutes shorter with a
list of line-dependent suites instead of the whole build; the coordinator chose the whole build.

`actionlint` 1.7.12 passes on every workflow [ran]. Not exercised: `post-merge-build.yml` and
`build-tag-publish.yml` with the new job layout (they call the same two files), the first post-merge run's
Gradle cache under the new job id, and `dataloader-tests.yml` by hand.

## 8. Publishing guard (the coordinator's request)

- What triggers `build-tag-publish.yml` today [read]: a push to `main`. Its tag, `./gradlew publish` and
  image push steps run only when the repository is `linkedin/openhouse`. Nothing in this fork triggers a
  publish, and I did not trigger the workflow.
- The guard is in the Gradle build (`gradle/iceberg-fork.gradle`, commit 3c2bbb5a), not in the workflow:
  that file is applied exactly when the build's Iceberg is the fork's, and the task graph is the one place
  every publish passes through. A graph holding a `PublishToMavenRepository` task with a non-`file:`
  repository fails when the graph is ready, before any task runs, with the reason (the POMs would name
  `com.linkedin.iceberg:*:1.12.0-ohfork.<commit>`, hosted nowhere; D7, I6d) and the two ways forward.
- Verified without publishing [ran, dry runs of the task graph]: `publish --dry-run` on the default fails
  with the message before listing a task (31 remote publish tasks, target
  `https://linkedin.jfrog.io/artifactory/openhouse`); `-PicebergLinkedIn publish --dry-run` lists the 31
  tasks and succeeds; `publishToMavenLocal --dry-run` succeeds; a single `...ToLinkedInJFrogRepository`
  task fails and the `...ToMavenLocalRepository` one succeeds.
- Known gap, not changed: in that workflow the tag step runs before `./gradlew publish`, so on
  `linkedin/openhouse` a tag would be pushed and the publish would then stop. That only matters if this
  line reaches LinkedIn's `main`, a one-way door.

## 9. Deployables

- Compose [ran]: `docker compose --profile '*' config` of the six recipes, before with the env file against
  after without it: `oh-only` and `oh-hadoop` identical; the four Spark recipes differ only in the build
  arguments that are now the files' defaults and in `OPENHOUSE_JOBS_CONFIG_PATH`.
- `OH_SPARK_BUILDER` stays in the Spark dockerfile with the env file's value (`dockerhub`) as default; the
  three sentences C9's record named as wrong are corrected (dockerfile header and stage comment, guide).
- Service images keep their base images (`openjdk:23-ea-11-slim`, `eclipse-temurin:17-jre-jammy`); CI ran
  tables and housetables on them [ran, CI].
- Not built here (disk): any image. CI builds the scheduler image and the `oh-only` recipe.
- **The default line's Spark image and dataloader CI are unproven after the flip**: the image has never
  finished building on a runner (Livy's build downloads a Spark release from archive.apache.org), on either
  builder. `dataloader-tests.yml` keeps `make sync verify` on pull requests into `main`; its integration
  job runs by hand only (`workflow_dispatch`), as reachable as `dataloader-tests-iceberg-fork.yml` was.

## 10. What remains

Grep hits for the old names (`remaining-hits.txt`, 70 lines) and why each kind stays:
- `build.gradle`, `settings.gradle`: the deprecation handling of `-PicebergFork`; the variable `icebergFork`.
- `scripts/iceberg-fork/publish-local.sh` line 4 ("opt-in -PicebergFork build"): left on purpose, its hash
  keys CI's fork cache (60 to 75 minutes to rebuild).
- Jar names inside the Spark image (`openhouse-spark-runtime_2.12-latest-all.jar`,
  `openhouse-spark-apps_2.12-latest-all.jar`) in `jobs.yaml`, `livy_cli.py`, the dataloader test, `SETUP.md`,
  `spark-client.sh`, the dockerfile: kept so that no consumer of the image changes.
- `hadoop2.8` in `hdfs-services.yml` and the dataloader test image: the recipe's HDFS, not the Spark image.
- `3.1.1` in `livy_spark3_hadoop3.patch`: Livy's own build profile (the next item's subject).
- `1.2.0.6` (`iceberg-aws` on the reference line), `1.2.0` in `uv.lock`: unrelated versions.
- Comments that explain history: `src/shared` notes in five build files, the guide, the Renovate guide.
- Comments in the tables service (below).

Follow-up simplifications (decision 5; nothing removed here):
- `@ConditionalOnClass` on `IcebergRestWriteController`, `IcebergRestWriteHandler`, `IcebergRestViewController`,
  `IcebergRestViewHandler`, `ViewCommitEngineConfiguration`: the classes they test for exist on both
  remaining lines.
- `IcebergRestRequirements` (keeps `UpdateRequirement` out of bean signatures), the 406 arm of
  `IcebergRestCatalogController`, the note in `IcebergRestServedRoutes`, and their tests
  `IcebergRestBeanSignaturesTest`, `IcebergRestRoutesBoundaryTest`, `IcebergViewBeanBoundaryTest`: they
  guard an Iceberg 1.2 runtime that no longer embeds the service.
- `IcebergLine`/`IcebergLineTest`: the 1.2 wording and the 1.2 arm of the expected-line rule.
- Fold `src/shared` into `src/main` and `src/test` in five projects (193 files; the Spark 4.2 copy check
  reads `SparkSqlSmokeTest.java` by path, which is in `src/test` and does not move).
- Names that now mislead: `iceberg_1_5_version`, `iceberg-1.5` project and artifact names,
  `openhouse.iceberg-conventions-1.5.2`, on a build whose Iceberg is 1.12.
- Remove `-PicebergFork` and `HARNESS_ICEBERG_FORK` after one cycle.
- Set the Java source encoding in the build (not done; Gradle still needs a UTF-8 locale here).

Not run: the Spark image; `oh-hadoop-spark`; the dataloader integration tests and `make verify`; any image
build on this machine; the Azure sandbox script and the ADLS `spark-shell` command (edited); real S3;
harness real-HTS mode and rest-shim mode; `build-tag-publish.yml`.

## 11. Deviations and incidents

- Order: the deletion (dec16c80) is committed before the deployables and workflow commits, which were held
  for pull request 114. Between dec16c80 and 0720d817 a plain `docker compose` of a Spark recipe names
  files that are gone; the Gradle docker tasks worked throughout.
- The reference CI job runs the whole build, the reference line builds no deployables,
  `publish-local.sh` is untouched: the three choices the coordinator accepted.
- Beyond the brief: the kit in `check` and the T12 fix (needed for `./gradlew clean build`); the publish
  guard (requested later); `libs:datalayout` is built on Spark 3.5 / Iceberg 1.5 on the reference line too
  (its jar's entries are unchanged, its POM's dependencies are not); the Spark 3.5 apps jar carries
  `log4j2.properties` on the reference line too; the dataloader integration tests no longer run on pull
  requests into `main` (the coordinator's instruction, section 9).
- `testSetPoliciesTablePropsBlocked` asserts more than the test it was ported from (section 2).
- Stash incident, 11:14 UTC: the shared pre-commit hook in my worktree popped the N6 agent's stash entry.
  N6 lost nothing [ran: its commit 7989f2cf held both files byte-identical]; I removed the two files from
  my tree and dropped my own leftover entry. Every later commit, merge and push held the shared lock.
- The merge of pull request 114 (de9b8d2f) was finished with `git write-tree` and `git commit-tree`: the
  hook's stash removed the merge state. The other merges were made by `git merge` under the lock.
- A private Docker daemon (`/tmp/v9edk`, data in the scratchpad) ran for the Testcontainers suites; images
  pulled: `mysql:8.0.46` and `testcontainers/ryuk:0.7.0` only. Stopped and removed at the end.
- Clean-up [ran]: the daemon is stopped and its data removed, `/home/user/oh-v9e/build` (2.2 GB) and the
  harness work directory are removed, the stash stack is empty, the worktree is clean and equal to the
  pushed head. No Gradle build, test JVM or container of mine is running; the two Gradle daemons left on
  the machine are not mine (one predates this work, one is building `/home/user/oh-v15`).
- The first final run of the default build stopped after 5 minutes on HTTP 429 from Maven Central for the
  OpenAPI generator jar that `clean` deletes; rerun after a wait (`logs/final-default-build-429.log`).
- Wrong in a pushed commit message, not correctable without a force push: dec16c80 says the `all-modules`
  POM lists 27 OpenHouse modules; it lists 30 (and 12 classifier entries), the same on both lines.
- In CI the reference job reported "266 executed, 50 from cache" (run 45170041383): Gradle reused results
  of identical inputs. Locally every task executed on both lines.

## 12. Commits

| Commit | Why |
| --- | --- |
| b4bf6333 | the three Spark 3.1-only tests without a counterpart run on Spark 3.5 before their project is deleted |
| 1ba08a38 | `libs:datalayout` and the `services:tables` tests no longer depend on the old projects |
| c4995729 | merge of pull request 117 (N3b), so the flip carries `smokeTest` |
| 98328ea2 | the flip: no property is the 1.12 line, `-PicebergLinkedIn` the reference, `-PicebergFork` a deprecated no-op; harness, kit in `check` |
| dec16c80 | the Iceberg 1.2 / Spark 3.1 projects, their entries, conventions plugin and documents are deleted |
| 3c2bbb5a | the default build refuses to publish to a remote Maven repository |
| de9b8d2f | merge of pull request 114 (C9), whose files the next two commits flip |
| 0720d817 | the images are built from the Spark 3.5 jars without switches; the Spark 3.1 image and the env file are deleted |
| 81440114 | the CI jobs swap roles; the Dataloader integration job is by hand only |
| 0edd9f60 | merge of pull request 119 (N6) for the final runs |
| e8287715 | merge of pull request 120 (V8) for the final runs |
