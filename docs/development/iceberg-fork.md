# The Iceberg lines of the build: 1.12 by default, LinkedIn's 1.5.2 as the reference (`-PicebergLinkedIn`)

The default build compiles and tests the Iceberg 1.5 variant (Spark 3.5, Scala 2.12), the Spark 4.2
modules and Apache Iceberg's REST compatibility kit against Iceberg 1.12, built from a pinned commit
of the Iceberg fork. `./gradlew build` with no property is that build, on Java 17.

`-PicebergLinkedIn` builds the reference line instead: the Iceberg 1.5 variant against LinkedIn's
`com.linkedin.iceberg` 1.5.2 releases from Maven Central. It exists for one purpose: LinkedIn's
line is the behavior the 1.12 line is measured against, so its suites and the
delta-harness can still be run on it. The modules that need Iceberg 1.12 (Spark 4.2, the
compatibility kit) are not in that build, and it builds no deployables.

`-PicebergFork` selected the 1.12 line while it was opt-in. It is accepted for one more cycle and
does nothing: Gradle prints a deprecation warning and builds the default. Together with
`-PicebergLinkedIn` it is an error. `HARNESS_ICEBERG_FORK=1` is treated the same way by the
delta-harness.

## What is pinned

[`scripts/iceberg-fork/iceberg-fork.properties`](../../scripts/iceberg-fork/iceberg-fork.properties)
holds the fork URL, the exact commit, and the runtime versions that commit needs (Avro, ORC,
Caffeine, and the AWS SDK its `iceberg-aws` is compiled against). The published version is `<baseVersion>-ohfork.<12-char commit>`, which cannot collide
with a `com.linkedin.iceberg` release (those are four numeric parts, e.g. `1.5.2.24`). Nothing is
committed as a binary and nothing is published remotely; bump the commit to move the line.

## Use it

```bash
export JAVA_HOME=/path/to/jdk17        # the fork's Iceberg is Java 17 bytecode
scripts/iceberg-fork/publish-local.sh  # once per pinned commit per machine; FORCE=1 republishes
./gradlew clean build                  # clean keeps the published fork
./gradlew :services:tables:test
./gradlew :integrations:spark:spark-3.5:openhouse-spark-3.5-itest:test

./gradlew -PicebergLinkedIn build      # the reference line; needs no publish-local.sh
```

Until the fork is published, the default build still configures, so `help`, `projects` and the
git hooks' `spotlessCheck` work, with a warning that names the script; a task that needs Iceberg (a
compile, a test) fails where it resolves the fork's jars, with the same message. So does a set that
is only partly published (its marker file is missing, say after an interrupted run). An IDE import
resolves the modules' classpaths, so expect it to fail the same way until the script has run.
`-PicebergLinkedIn` (or `-PicebergLinkedIn=true`) selects the reference line;
`-PicebergLinkedIn=false` is the default build, and another value is refused.

The script needs Java 17 or later (`JAVA_HOME`, else `java` on the `PATH`) and stops at once
otherwise. The fork's tree has no Gradle wrapper jar; its `gradlew` would download one with no
checksum and run it, so the script downloads it itself and runs the fork's build only when the
jar's SHA-256 is the one pinned in `iceberg-fork.properties` (`gradleWrapperJarSha256`).

`publish-local.sh` fetches the pinned commit into `~/.gradle/iceberg-fork/src`, then runs the fork's
`publishToMavenLocal` for the modules OpenHouse consumes with `-Dmaven.repo.local` pointing at
`~/.gradle/iceberg-fork/<version>` (never `~/.m2`). The directory is under the Gradle user home
(`$GRADLE_USER_HOME` when set) and keyed by the published version, which carries the commit:
`./gradlew clean` does not touch it, git does not see it, and every worktree of this repository on
the machine shares one copy per pin instead of rebuilding Iceberg in each. `ICEBERG_FORK_HOME`
moves it elsewhere; set it the same way for the script and for Gradle. Because the checkout is
shared, the script holds a lock (`flock`, where available) while it fetches and publishes. A pin you
no longer use can be deleted by removing its `<version>` directory. A second Iceberg build publishes the shaded
`iceberg-spark-runtime-4.2_2.13` (one Iceberg build has one Scala version); it has its own marker
file, so a repository published before it existed gains only that jar. A third build, with its own
marker too, publishes `iceberg-open-api`'s tests and test-fixtures jars: Apache's REST compatibility
kit. It needs the fork's `-PpublishGroup`/`-PpublishVersion`
support. A cold run builds Iceberg's core and Spark 3.5 runtime and takes a few minutes (about two
with Gradle's build cache warm); once the version is published it is a no-op.

## What differs between the two lines

All in [`gradle/iceberg-fork.gradle`](../../gradle/iceberg-fork.gradle), which the default build
applies and the reference build does not, or guarded by the same property (`icebergLinkedIn`):

- `iceberg_1_5_version` is the fork version for every module of the Iceberg 1.5 variant; on the
  reference line it is the `1.5.2.x` release named in the root `build.gradle`.
- A repository limited to `com.linkedin.iceberg` at exactly the fork version, served only from
  `~/.gradle/iceberg-fork/<version>`.
- `iceberg-aws` (the service's `S3FileIO` and `AwsClientFactories`, in `openhouse.iceberg-aws-conventions`)
  is the fork's too, with the AWS SDK at the pin file's `awsSdkVersion` plus `kms`, which it
  needs; `s3` brings `apache5-client`, its default HTTP client. The SDK's `netty-nio-client` is
  excluded: OpenHouse builds no async S3 client, and that SDK's Netty would split the service's
  Netty between 4.1.138 and 4.1.86 and break its WebClient. So no Iceberg jar of another line is
  on any classpath, and snapshot summaries name the fork's version. The reference line keeps
  LinkedIn's `iceberg-aws` 1.2.0.6 and SDK 2.20.18; there its `iceberg-build.properties` can come
  before core's on a classpath, and `IcebergBuild.version()` then says 1.2.0.6 (seen on the Spark 3.5
  apps test classpath; LinkedIn's 1.5.2 does not record the version in snapshot summaries).
- Every module compiles to Java 17 (`openhouse.java-minimal-conventions`); on the reference line
  to Java 8, as LinkedIn's line always has.
- The root ORC/Avro forces are the pinned versions, and Caffeine is pinned; the reference line
  keeps ORC 1.8.3 and Avro 1.11.4.
- `iceberg-parquet`/`iceberg-orc` are added at runtime on the Iceberg 1.5 variant and kept off the
  Spark 3.5 test classpaths, where the shaded `iceberg-spark-runtime` already carries them.
- The Spark 4.2 modules and the REST compatibility kit are in the default build only
  (`settings.gradle`), and the capabilities module compiles the REST transport of its line.

The Iceberg 1.2 / Spark 3.1 projects (`apps/spark`, `integrations/java/iceberg-1.2`,
`integrations/spark/spark-3.1`, `tables-test-fixtures/tables-test-fixtures-iceberg-1.2`) are
deleted: the shared server modules need Java 17 and cannot also serve that line. The sources
they shared with the 1.5 projects are those projects' `src/shared` directories, ordinary source
roots now.

The delta-harness runs on the default build; `HARNESS_ICEBERG_LINKEDIN=1` runs it on the reference
line (`integrations/spark/delta-harness/HARNESS-GUIDE.md`).

## Publishing

The default build refuses to publish to a remote Maven repository. Its Iceberg is hosted nowhere
(it is built from a pinned fork commit on the build machine), so the POMs would name
`com.linkedin.iceberg:*:<baseVersion>-ohfork.<commit>`, which no consumer can resolve.
`./gradlew publish`, or any single task that publishes to a repository
that is not a `file:` URL, stops when the task graph is ready, before any task runs, with a message
that says so (`gradle/iceberg-fork.gradle`). The ways forward are to publish from the reference
line (`./gradlew -PicebergLinkedIn publish`), or to host the fork's Iceberg artifacts first, which
is for the repository owner to decide. `publishToMavenLocal` is not affected.
`build-tag-publish.yml` runs `./gradlew publish` only on `linkedin/openhouse`'s `main`; there it
would now stop at this check.

## Docker images

`./gradlew shadowJar` builds every uber jar, and `./gradlew dockerPrereqs` the jars the images need:
the service boot jars, the Spark 3.5 runtime and apps uber jars, and the token generator. It also
copies the fork's `iceberg-spark-runtime-3.5_2.12` to `build/iceberg-spark-runtime/`
(`stageIcebergSparkRuntime`), because a Docker build reads only the checkout and no repository
serves that jar. `./gradlew dockerBuild`, `dockerUp` and `dockerDown` wrap docker compose; by hand:

```bash
./gradlew dockerPrereqs
docker compose -f infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml up -d --build
```

The images are built from the default build only. Under `-PicebergLinkedIn`, `dockerPrereqs`,
`dockerBuild` and `dockerUp` stop with a message: the reference build is not deployed.

- The service images (tables, housetables, jobs, optimizer) copy `build/<service>/libs`, and
  their base images (`openjdk:23-ea-11-slim`, `eclipse-temurin:17-jre-jammy`) run the jars' Java 17
  class files. They are the base images these Dockerfiles had before the build moved to Java 17.
- `jobs-scheduler.Dockerfile` runs the scheduler from the Spark 3.5 apps uber jar
  (`openhouse-spark-apps-1.5_2.12`) with `-Dlog4j2.configurationFile` pointing at
  `apps/spark-3.5/jobs-scheduler/log4j2.properties`, copied into the image: without it the
  scheduler logs errors only. The file is not in the jar, which the Spark maintenance jobs load as
  well.
- The Spark services of every recipe (`common/spark-services.yml`) are built from
  `spark-3.5-base-hadoop3.2.dockerfile`: Spark 3.5.2, a Java 17 runtime for every JVM in the image
  (the jars are Java 17 class files), the fork's Iceberg runtime in `/opt/spark/jars`, and the
  Spark 3.5 runtime and apps jars. The OpenHouse jars keep the names they had in the Spark 3.1
  image, which is gone (`/opt/spark/openhouse-spark-runtime_2.12-latest-all.jar`,
  `/opt/spark/openhouse-spark-apps_2.12-latest-all.jar`), so `jobs.yaml`, `livy_cli.py`, the
  dataloader integration tests and the Azure sandbox script name the paths they always did.
- No Spark session asks for an Iceberg runtime with `spark.jars.packages` or `--packages` any more
  (`jobs.yaml`, `scripts/python/livy_cli.py`, the dataloader's integration tests, `SETUP.md`): the
  image has it. Before, each session downloaded Apache's `iceberg-spark-runtime-3.1_2.12:1.2.0`.
- The build argument `OH_SPARK_BUILDER` selects where the Spark image's Livy and Spark come from.
  Its default, `dockerhub`, builds Livy (the pinned commit with the repository's patch) in Maven's
  Docker image, without its `integration-test` and `coverage` modules, and copies `/opt/spark`
  from Apache's Docker image of the same release (`apache/spark:3.5.2-...`). It needs Docker Hub,
  GitHub and Maven Central only, and on a GitHub runner the images of the `oh-hadoop-spark` recipe
  build in a little over 6 minutes, of which Livy's build is three and a half to four and a half.
  Nothing is kept between runs, so every build there is a cold one.
  `OH_SPARK_BUILDER=default docker compose ... build` uses the dockerfile's older builder stage,
  which installs some 200 Ubuntu packages and downloads Spark and Maven from
  `archive.apache.org`; on GitHub's runners that host served 46 kB/s (two and a half hours for one
  tarball) and `apt-get` took 9 and 42 minutes, so that stage has never finished there.
- Livy's own build would download one more Spark release (228 MB) from `archive.apache.org`, in
  its `thriftserver` and `integration-test` modules, for tests that are skipped. Both builder
  stages pass `-Ddownload.plugin.skip=true`, the skip property of the `download-maven-plugin`
  (1.3.0 in Livy's pom); the build's log then says `maven-download-plugin:wget skipped`.
- What the image is known to do, from the Dataloader workflow's runs ("CI" below): the pinned Livy,
  compiled against Spark 3.1.1, runs SQL sessions on Spark 3.5.2 and Java 17 (Livy warns that it
  has not been verified with Spark 3.5); Spark's Hadoop 3.3.4 client reads and writes the recipe's
  HDFS 2.8; `/opt/spark` from Apache's image has no `conf` directory (the tarball's holds only
  templates) and nothing in the recipes needs one. The image has no Python interpreter, as the
  Spark 3.1 image had none, so Livy's `pyspark` sessions are not available in it. Not run in CI:
  a job submitted through the jobs service (a Livy batch from the apps jar).

## Behavior that differs by Iceberg line

The client detects its line with `com.linkedin.openhouse.javaclient.IcebergLine`: it asks the
Iceberg on the classpath to build format v3 metadata in memory, which LinkedIn's 1.2 and 1.5.2
releases refuse and the 1.12 line accepts. Tests that expect different results per line use the
same class.

- Column defaults, which on the 1.12 line are native Iceberg default values: there the client
  applies no `openhouse.read-bridge.column-default.*` overlay and the Spark catalogs do not declare the
  `column-defaults` capability, so a table with `required-capability.column-defaults=true` is
  refused with 422 `CLIENT_CAPABILITY_MISSING`. Iceberg 1.12 itself refuses a non-null
  `initial-default` below format v3; tests that need one build a v3 table on that line.
- `REPLACE TABLE ... AS SELECT` that drops a former partition source column succeeds on 1.12 and
  fails Iceberg validation on 1.5.2 (apache/iceberg#11868).
- `CREATE NAMESPACE` and `DROP NAMESPACE` are unsupported on both lines, with the message "Checking
  if database exists is not supported" on 1.12 instead of "Describing database is not supported"
  (apache/iceberg#14507).
- After several merge-on-read `DELETE`s on one data file, 1.12 keeps one live position delete file
  for it (Spark's delete granularity defaults to `file` and the earlier file is replaced), where
  1.5.2 keeps one per `DELETE` (`SparkMoRFunctionalTest`); the rows read are the same.
- `ALTER TABLE ... ADD COLUMN c int DEFAULT 5` is refused ("setting default values in Spark is
  currently unsupported") instead of being accepted with the default silently dropped.

With `write.wap.enabled=true` and `spark.wap.branch` set, an explicit `TIMESTAMP AS OF` or
`VERSION AS OF` read reads main's history on both lines (delta-harness
`interact.branch.ttBeforeBranchPoint`). Apache Iceberg 1.12 refuses it ("Cannot override ref,
already set snapshot id"), which would make a working read fail for a user who stages writes, so
the fork's Spark 3.5 time-travel reads ignore the session WAP branch from the pinned commit
a88f556c on (its tests pin the same on Spark 4.2, which needed no change).

On the REST-shaped path (`catalog-impl` `OpenHouseRestCatalog`), Iceberg 1.12's client calls only
the endpoints a server advertises, and with none advertised assumes a default set without views. So
on the 1.12 line the in-process adapter's config response lists every route it dispatches (all but
config itself, derived from its route table); on the legacy line it lists none, as before, because
Iceberg 1.5.2 has no endpoints in its config response. `OpenHouseRestAdapterEndpointsTest` keeps the
list and the routes equal on both lines.

The jobs client (`TablesClientFactory`, apps) declares `column-defaults` by the same rule as the
Spark catalogs. The scheduler's own jar carries neither `IcebergLine` nor Iceberg, so there the line
is not detectable and it declares the capability as before, with a warning.

## Format v3 tables

Iceberg format version 3 exists on the 1.12 line only, and a cluster allows it with one switch,
`cluster.iceberg.format-v3.enabled` (default `false`), next to `cluster.iceberg.format-version`
(which stays the version tables are created at, 2).

- A table is v3 only when it is asked for by name: `CREATE TABLE ... TBLPROPERTIES
  ('format-version'='3')` (a `CREATE OR REPLACE` alike) or `ALTER TABLE ... SET TBLPROPERTIES
  ('format-version'='3')`, through the OpenHouse catalog on Spark 3.5 or Iceberg's REST catalog on
  Spark 4.2. Everything else is created at, and stays at, the version it has. Upgrading a table
  cannot be undone: a lower version is refused, and LinkedIn's Iceberg 1.5.2 clients and services
  cannot load a v3 table.
- With the switch off, the native API answers as LinkedIn main did on Iceberg 1.5.2: a create (or
  replace) asking for a version above the cluster's is stored at the cluster's version, and an
  update asking for a version above 2 gets Iceberg 1.5.2's refusal (`Cannot upgrade table to
  unsupported format version: vN (supported: v2)`); on the 1.12 line, where Iceberg itself would
  upgrade, the service gives that refusal before anything is changed. No `FORMAT_VERSION_REFUSED`
  or `COLUMN_DEFAULT_REQUIRES_V3` answer, and no format-version pre-commit check or metric, for a
  table below v3. The Iceberg REST create still refuses a version it does not create.
- With the switch off, a cluster whose `cluster.iceberg.format-version` is above 2 makes no table of
  that version, as on LinkedIn main, where Iceberg 1.5.2 refused such creates and replaces while
  reads, updates and snapshot commits of existing tables worked: a create answers Iceberg 1.5.2's
  `Unsupported format version: vN` and a replace its upgrade refusal, each with what to change; no
  commit may leave a table above 2.
- With the switch off, a table already at v3 (made while the switch was on) keeps the rules of a v3
  table: it takes `unknown` columns and column defaults on every write path.
- The Iceberg REST commit routes answer a `format-version` property they cannot store with 400
  `FORMAT_VERSION_REFUSED`, whatever the switch: a server failure would tell an Iceberg client
  that the commit's state is unknown.
- An upgrade to v3 is logged (a warning naming the table, both versions and the principal) and
  audited as its own table audit event, `UPGRADE_FORMAT_VERSION`, after the event of the write
  that made it. A failure to send that event is logged and does not change the write's answer or
  its own event.
- One rule decides all of it, `FormatVersionRule`, and `FormatVersionCommitPolicy` checks it on
  every commit path as a catalog pre-commit check, with what a v3 table may carry: column defaults
  are Iceberg's native `initial-default` and `write-default`, stored as committed (an existing
  column's `initial-default` never changes, and the read-bridge opt-in
  `required-capability.column-defaults` is refused on v3; below v3, with the switch on, a new or
  changed `write-default` is refused with `COLUMN_DEFAULT_REQUIRES_V3`).
- The v3-only column types (`FormatV3Types`): `unknown` (Spark's `void`) is taken;
  `timestamp_ns`, `timestamptz_ns`, `geometry` and `geography` are refused
  (`FORMAT_V3_TYPE_NOT_SUPPORTED`, with the reason); `variant` is off by default, behind its own
  property, below. With the switch off, every one of them is refused by the native request
  validation with LinkedIn main's answer, 400 `schema : Cannot parse type string to primitive:
  <type>`.
- `cluster.iceberg.format-v3.variant.enabled` (default `false`; needs the v3 switch) lets a v3
  table take a variant column. Off, a variant column is refused on create and on evolution, on
  both APIs. Turning it on costs this, for every table that takes one: the column can never be
  dropped (OpenHouse refuses dropping columns); the maintenance jobs, which run on Spark 3.5, do
  not compact such a table and report failure on it on every run (their post-job validation
  cannot read it); Spark 3.5 cannot read it (SELECT and DESCRIBE fail with `Unsupported type:
  variant`; SHOW TABLES still lists it); and it cannot carry a retention policy. With it on, a
  variant column is still refused on a table that writes ORC or still has ORC data files.
  Turning it off again refuses new variant columns only; tables that have one keep it.
- Refused on v3, because OpenHouse does not handle them there: replica and replicated tables,
  table encryption, and the register of a v3 metadata file.

## CI

Two builds, the default and the reference, run beside each other on every pull request
(`pr-validations.yml`) and after each merge to `irc-integration`
(`post-merge-build.yml`, also by hand with `workflow_dispatch`). Neither blocks nor waits for the
other. A newer push to a pull request cancels the pull request's older run. A post-merge run is
never cancelled and waits for no other: each merge commit has its own concurrency group, so the
runs of merges that land close together overlap, and every merge gets a run of its own. Both on a
pull request and after a merge each build is cut into parallel jobs (`split-build.yml`, below);
the default build's single job (`build-run-tests.yml`) runs only before a release.

**The default build**, the tasks of `build-run-tests.yml` (job `build-and-run-tests`):

- restores the published fork from the cache or publishes it (the composite action
  `.github/actions/iceberg-fork`);
- `./gradlew clean assemble testClasses`, then `./gradlew build --continue`: every project's tests,
  the Spark 3.5 itest (`catalogTest`, `statementTest`, `test`), the Spark 4.2 SQL extensions' tests
  and the Spark 4.2 itest (`test`, `extensionsTest`, `smokeTest`, and `testServerPlanning` and
  `smokeTestServerPlanning` with server-side scan planning), and Apache Iceberg's REST
  compatibility kit (`restCompatKitTest` and `restCompatKitFormatV3Test`, part of `check`);
- after the tests, whatever their outcome (but not after a failed compile), the deployables
  ("Docker images" above, the composite action `.github/actions/default-build-deployables`):
  `./gradlew shadowJar dockerPrereqs`; the jobs-scheduler image, built through docker compose, in
  which the scheduler is started without arguments and must log its first line at INFO (which
  needs the image's Java to run the jar and the image to give it its `log4j2.properties`; the
  apps uber jar itself must carry none); the
  `oh-only` recipe, then `scripts/python/integration_test.py` against it. The tables container's
  jar must hold the fork's `iceberg-core` and no other. The containers are stopped in every case,
  and their logs are printed when one of these steps failed.

`build-tag-publish.yml` calls the same workflow before a release.

**The reference build**: `./gradlew -PicebergLinkedIn clean build --continue`, the whole build on LinkedIn's Iceberg 1.5.2, so that a
change that breaks the line the default is measured against is red before it merges. It needs no
published fork and builds no image.

When a suite fails, a job's last two steps print each failed test with the start of its
failure (`scripts/ci/print-failed-tests.py`, read from the JUnit XML results, plus, on the default
build, the end of the Spark 4.2 tests' service logs) and upload the test reports as the artifact
`default-build-test-reports` or `reference-build-test-reports` (kept 7 days). The failed tests are
therefore at the end of the job's log, not only where they ran.

**On a pull request and after a merge**, `split-build.yml` runs each build as parallel jobs, so
that the answer waits for the slowest job instead of one runner running every suite. The jobs are cut by
project: `scripts/ci/test-groups.py` makes a job of each project directory that matches its
patterns (`apps/spark-*`, `integrations/spark/spark-*/openhouse-spark-itest`), which runs that
project's `check` (its test tasks, checkstyle and spotbugs), and a job `rest`, which runs
`./gradlew clean build` with the `check` of each of those projects excluded. The init script
`scripts/ci/test-groups.gradle` does the cutting inside Gradle. The jobs together therefore run
every task of `build`: a new test task goes with its project, a new Spark apps or itest project
gets a job of its own, and any other new project runs in `rest`, without a change to the
workflow. On the reference line a matched project that is not in that build (Spark 4.2) is a job
that says so and runs nothing. `scripts/ci/check-test-groups.py` checks, by Gradle dry runs, that
the jobs' tasks add up to `build` with each test task in one job. A first job, the test groups,
reads the list (and on the default line checks the merged optimizer migrations); a job beside the
groups, the test-group partition, runs that check (it needs no published fork); another of the
default line runs the Docker steps above, after `./gradlew clean assemble`. The
last job of each line, `Build and Run Tests`, needs all the others and is green only when each of
them succeeded (the reference line's skipped Docker job aside). On a pull request the default
line's is the check the single job reported, `build-run-tests / Build and Run Tests`, which branch
protection can require; the reference line's, `iceberg-linkedin-build / Build and Run Tests`, is
optional upstream. Each job restores the
fork and the newest Gradle cache entry of its line, saved under one name a line
(`GRADLE_BUILD_ACTION_CACHE_KEY_JOB`: `build-and-run-tests` or `iceberg-linkedin-build`, the ids
the single jobs had). Only one job a line writes that cache, the `rest` job of the run after a
merge (input `gradle-cache-write`): an entry is about 530 MB and holds what its job used, so a
writer per job would fill the repository's cache and evict the fork's entry. A red job cancels no
other; each prints its failed tests and uploads its reports as
`default-build-test-reports-<group>` or `reference-build-test-reports-<group>`.

**The Dataloader workflow**, `dataloader-tests.yml`, has two jobs: the dataloader's own checks and
unit tests (`make sync verify`), and the integration tests. The integration job restores the fork,
runs `./gradlew shadowJar dockerPrereqs`, builds the images of the `oh-hadoop-spark` recipe (the
Spark image, Livy included), starts the recipe, waits until the tables and house tables services,
Livy, HDFS (out of safe mode, with its datanode) and the Spark master (with its worker) answer,
prints what the Spark image holds, and runs the dataloader's integration tests
(`make integration-tests`), which create and fill tables with Spark SQL through Livy and read them
back from HDFS. It runs:

- on a pull request into `main` or `irc-integration` that touches the dataloader
  (`integrations/python/dataloader/**`, the scope it had before), the recipe and what its images
  are built from (`infra/recipes/docker-compose/common/**`,
  `infra/recipes/docker-compose/oh-hadoop-spark/**`, the service Dockerfiles), the pinned fork
  (`scripts/iceberg-fork/**`), or the workflow and the fork action themselves;
- after each merge to `irc-integration`, beside `post-merge-build.yml` and never cancelled, because
  a change to the jars the Spark image carries (the Spark runtime, the apps) reaches the image on
  no pull request the paths above select;
- by hand (`workflow_dispatch`) on any branch.

A run takes about 15 minutes on a pull request, which reads the fork and the Gradle cache
`irc-integration` saved, and about 22 by hand on a branch that has neither.

The published `~/.gradle/iceberg-fork/<version>` is cached, keyed on the pinned commit and the
script, so the fork is built (about 6 minutes on a runner) only when either changes:

- Every run restores the cache. A pull request can read what its base branch saved, so it normally
  gets the fork `irc-integration` published and builds nothing.
- A run on a branch (after a merge, or by hand) saves the fork whenever it had to publish it.
- A pull request saves only when it changes `iceberg-fork.properties` or `publish-local.sh`. Its
  first run publishes the fork, and GitHub keeps the cache in the pull request's own scope: later
  pushes to that pull request restore it, and no other pull request or branch can read it. After
  the merge, the post-merge run publishes once more and saves for everyone.
- A pull request that misses the cache without changing either file (the base's cache was evicted,
  or the base's post-merge run has not saved yet) publishes the fork and saves nothing.

The Gradle cache is written only by the `rest` jobs of a post-merge run: on `irc-integration`, or
in a branch's own scope when the workflow is run there by hand. A pull request reads it.

**Maven Central and the restored cache.** Every request a job makes to Central can be refused (a
429). Gradle (8.14) tries a refused request three times, 1 s and 2 s apart, and then disables
Central for the rest of that Gradle command: plugins, dependencies and files all come from that one
repository, so everything else the command still needs from it fails too, and the job dies before
its tests. Three things keep the jobs off Central (`scripts/ci/prepare-gradle-home.sh` sets them up
after the restore, for every Gradle command of the job; none is saved with the cache, none reaches
a developer's build):

- A job that only reads the cache (every split-build job but the writer, and the Dataloader
  integration job off the default branch) installs `scripts/ci/dependency-cache.gradle` in the
  Gradle user home's `init.d`. Gradle asks Central again for two kinds of cached answers once they
  are older than 24 hours, and the restored entry is often older (any pull request when nothing
  merged for a day). The script keeps both from the cache:
  - the version lists of the build's dynamic versions (the `json-smart` range in `nimbus-jose-jwt`'s
    POM, `mysql-connector-java:8.+`, the `log4j-core` and `logback-core` ranges of
    `spotbugs-annotations`), kept 20 days instead of 24 hours;
  - the Spring Boot plugin (`id 'org.springframework.boot' version '2.7.8'`), resolved straight to
    `spring-boot-gradle-plugin` of that version, the one dependency of its marker module, whose
    missing jar Gradle otherwise asks Central about with a HEAD every 24 hours.

  The writer keeps Gradle's periods, so a merge refreshes a list older than a day; a dynamic
  version the cache has never seen is looked up as before. The trade: readers resolve a range from
  the list as the writer last saw it, so where the list decides, a release made since reaches pull
  request jobs only after the next merge's `rest` refreshes it, while developers, the writer and a
  release build get it at once; until then CI tests the previous one. Today no selection can move
  that way: the lists decide only for `json-smart` (closed at 2.3, long published) and
  `mysql-connector-java:8.+` (relocated after 8.0.33), and the open ranges are settled by a fixed
  version in the same graph (`log4j-core` 2.25.3, 2.25.4 in the Spark 4.2 apps; `logback-core`
  1.2.11 from Spring Boot), although alone they resolve to newer releases (2.26.1 and 1.6.8 on
  2026-11-28). A configuration where such a range stands alone would lag. After 20 days without a
  merge the lists are stale again and the
  readers ask Central, as before the script; the download list then warns (below).
- Every job gives Gradle seven tries for a refused request, 2 s apart and doubling (Gradle's
  internal `org.gradle.internal.repository.max.tentatives` and `.initial.backoff`, as system
  properties in the Gradle user home's `gradle.properties`): at most about two minutes before
  Central is disabled.
- The writer, after its build, fetches the files of every resolvable configuration of every project
  (`ciResolveDependencies` from `scripts/ci/resolve-all-dependencies.gradle`, at most 10 minutes,
  not part of the verdict; each project prints `<project>: <files> files, <failures> failures` and
  each failure on its own line): what only another job uses (the Spark 4.2 apps' Jackson JAX-RS
  jars, the Spark 3.5 itest's JUnit Platform suite, the Docker job's OpenTelemetry agent) is then in
  the cache the next jobs restore. The fetch is one Gradle command, so one request Central refuses
  past the retries disables Central for the rest of it and every file still missing fails ("Repository
  maven is disabled due to earlier error below", with the refused request as the cause); the cache
  saved then lacks them until a later writer fetches them. When failures are left the step therefore
  runs the task once more, a minute later, in a new command, and what still fails is a warning
  annotation on the run (`The writer's fetch left N resolution failures`).

Each job prints the restored cache's age at the start and ends by listing the dependency files it
downloaded beyond the restored cache (`scripts/ci/list-downloaded-dependencies.sh`), with a warning
when the cache is 19 days old or more. A reader whose restored cache holds what it needs makes no
request to Central. A reader that must still download something (a dependency a pull request adds
or changes, which is in the cache after its merge) makes those requests, and a refusal of one that
outlasts the retries still fails that Gradle command.

To check the writer after a merge: the `Fetch` step runs with `continue-on-error`, so its conclusion
is always `success`; read its timing and its own output instead (the task's `BUILD` line, one
`<project>: <files> files, <failures> failures` line a project, any `could not resolve:` line), then
the download lists of the jobs of a pull request run that started after the writer saved
(`<id>`: the job's or the run's id):

```
gh api repos/mkuchenbecker/openhouse/actions/jobs/<rest job id> \
  --jq '.steps[] | select(.name | startswith("Fetch")) | [.conclusion, .started_at, .completed_at] | @tsv'
gh api repos/mkuchenbecker/openhouse/actions/jobs/<rest job id>/logs \
  | grep -E 'Fetch left [0-9a-z ]+ failures|Fetch failures left: |could not resolve:' | cut -c1-300
for j in $(gh api "repos/mkuchenbecker/openhouse/actions/runs/<run id>/jobs?per_page=100" --jq '.jobs[].id'); do
  gh api repos/mkuchenbecker/openhouse/actions/jobs/$j/logs \
    | grep -hE 'beyond the restored Gradle cache|Restored Gradle cache: [0-9]+ days|status code 429|Failed to get resource'
done
```

The second command prints `Fetch failures left: 0` when the fetch completed (after a second try,
also `Fetch left N failures; once more in 60 s`); anything else, or `could not resolve:` lines with
their cause, is a fetch that left the cache incomplete.

## Spark 4.2 over the IRC read routes

`:integrations:spark:spark-4.2:openhouse-spark-4.2-itest` is not in the reference build
(`settings.gradle`). Its tests run Spark 4.2 (the pin file's `spark42Version`) with the fork's
`iceberg-spark-runtime-4.2_2.13`, Iceberg's own `SparkCatalog` and `RESTCatalog`
(`type=rest`, `uri=<service>/iceberg`, `token=<bearer>`) and no OpenHouse catalog or client class on
the classpath, against the tables service's IRC routes:

```bash
./gradlew :integrations:spark:spark-4.2:openhouse-spark-4.2-itest:test
```

The service runs in its own JVM, which the test starts on a free port (`IrcReadTestServiceProcess`)
and stops when the test JVM exits: the service exactly as the Spark 3.5 tests embed it
(`SpringH2TestApplication`, in-memory H2), with the dummy token interceptor and an authorization
handler that denies one principal. It cannot share the Spark 4.2 JVM, which needs jackson-databind
2.21 where the service runs on 2.13. The service JVM seeds the tables through the existing OpenHouse
Java client over the native API (Iceberg's generic writers write the Parquet and ORC files, the
client commits each append) and records what that client shows, for comparison. Its log and work
directory are under `build/openhouse-spark-4.2-itest/tmp/test/service-*`. Its tables are not: the
warehouse is a new `oh42-*` directory under the system's temporary directory (`ready.json` names
it), removed when the service exits. House Tables keeps a table's metadata location in 255
characters, and a warehouse under the build directory made the location's length, and so whether
a table could be created, depend on where the repository is checked out. The SQL-extensions tests'
service (`tmp/extensionsTest/extensions-service-*`) does the same.

The service serves the IRC write routes; `SparkIrcWriteTest` pins that `CREATE TABLE` and `INSERT`
succeed from Spark 4.2 and the rows read back, on tables it creates and drops.

## OpenHouse SQL extensions on Spark 4.2

`:integrations:spark:spark-4.2:openhouse-spark-4.2-runtime_2.13` (not in the reference build) is the
Spark 3.5 runtime's SQL extensions ported to Spark 4.2 / Scala 2.13: the same statements (SET and
UNSET POLICY, MODIFY COLUMN ... SET TAG, GRANT, REVOKE, SHOW GRANTS, UNLOCK), configured the same
way next to Iceberg's:

```
spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions
```

It has no OpenHouse catalog class and does not depend on the OpenHouse Java client, the generated
client or Spring: it finds OpenHouse features through `:integrations:java:openhouse-catalog-capabilities`
and Iceberg's own `SparkCatalog` and `RESTCatalog`. Its `uber` jar holds its classes and the
capabilities module's; Spark and `iceberg-spark-runtime-4.2_2.13` are the engine's own.

```bash
./gradlew :integrations:spark:spark-4.2:openhouse-spark-4.2-runtime_2.13:test
./gradlew :integrations:spark:spark-4.2:openhouse-spark-4.2-itest:extensionsTest
```

Over a plain `RESTCatalog` every OpenHouse statement works. The policy statements ride the IRC
commit (the server advertises `policies` and applies the patch). GRANT, REVOKE, SHOW GRANTS and
UNLOCK go through the REST capability client in the capabilities module (`capabilities.rest`), which
calls the OpenHouse routes under the IRC base with the catalog's own URI, prefix, `header.*` values
and authentication, and gives the outcomes Spark 3.5 gives over `RESTCatalog`. The client's
transport is the one per-line part: the module compiles `src/iceberg-1.12/java` (Iceberg's
`AuthManager`) by default and `src/iceberg-1.5/java` under `-PicebergLinkedIn`. `extensionsTest` runs in its
own JVM and service JVM, because Spark fixes a session's extensions when the session is built.

## The Spark SQL smoke suite on Spark 4.2

`smokeTest` runs the Spark SQL smoke suite (the statements of the Spark 3.5 itest's
`SparkSqlSmokeTest`) on Spark 4.2 over IRC, with the OpenHouse SQL extensions and no OpenHouse
catalog or client class, in its own JVM and service JVM:

```bash
./gradlew :integrations:spark:spark-4.2:openhouse-spark-4.2-itest:smokeTest
```

- The statements and their baseline outcomes (Spark 3.5 with the OpenHouse catalog on Iceberg 1.5.2)
  are one list, `SparkSqlSmokeScenarios`, in the Spark 3.5 itest's `src/shared/smoke/java`. It needs
  only `spark.sql` and JUnit. The Spark 3.5 itest's `SparkSqlSmokeTest` runs it on each Spark 3.5
  catalog path (`legacy`, `rest-shim`, and on the Iceberg 1.12 line `rest-http`: Iceberg's
  `SparkCatalog` with `type=rest` over the service's IRC routes, `RestHttpPathSparkSqlSmokeTest`),
  each writing `build/openhouse-spark-3.5-itest/reports/smoke/spark-3.5-<path>-outcomes.tsv`, and
  this module compiles the same directory into its `smoke` source set.
- `Spark42IrcSparkSqlSmokeTest` (`src/smoke/java`) runs every statement and lists the statements
  whose outcome is not the baseline's, each with the baseline, what Spark 4.2 does, and the kind of
  difference. A statement that differs without being listed fails, and so does a listed one that
  does not differ. To change what the platform does for a statement, change its entry.
- The run writes `build/openhouse-spark-4.2-itest/reports/smoke/spark-4.2-irc-outcomes.tsv`: one
  line per statement with the statement, the baseline, the outcome and the verdict.
- `Spark42WithoutOpenHouseTest` runs the statements that differ because of Spark 4 on Iceberg's
  Hadoop catalog, with nothing of OpenHouse.
- `SparkIrcWriteStatementsTest` runs the write statements (INSERT INTO and OVERWRITE, MERGE, UPDATE,
  DELETE, CTAS, RTAS, column and property changes, branch and write-audit-publish writes) and
  asserts what the service stored, read through the OpenHouse Java client in the service JVM.
- The service (`IrcSmokeTestService`, `tmp/smokeTest/smoke-service-*`) is the one the Spark 3.5
  smoke suite embeds, with the service's own authorization handler, so that a difference comes from
  the engine or the catalog path. It has one database with views enabled.

## Apache Iceberg's REST compatibility kit

`:integrations:java:openhouse-rest-compat-kit` (not in the reference build) runs Apache Iceberg's REST
compatibility kit (RCK), the fork's `iceberg-open-api` tests, against the tables service's IRC
routes with Iceberg 1.12's own `RESTCatalog`: the kit's table suite (`CatalogTests`) and view suite
(`ViewCatalogTests`), every test of both, at two configurations of the service:

```bash
scripts/iceberg-fork/publish-local.sh   # also publishes iceberg-open-api's tests and test fixtures
./gradlew :integrations:java:openhouse-rest-compat-kit:restCompatKitTest          # the default configuration
./gradlew :integrations:java:openhouse-rest-compat-kit:restCompatKitFormatV3Test  # format v3 tables enabled
```

`restCompatKitTest` runs the service at its defaults; `restCompatKitFormatV3Test` turns on the
cluster switch for format v3 tables (`cluster.iceberg.format-v3.enabled`), so the kit's format v3
creates are honoured and what it finds is what the routes do with v3. `check` runs both, one after
the other (each starts its own service). The service runs in its own JVM as in the Spark 4.2 itest
(`SpringH2TestApplication`, in-memory H2, local storage, the dummy token interceptor, an
authorization handler that allows everything, views enabled for the namespaces the kit uses). A
JUnit session listener in the test JVM starts it and points the kit at `<service>/iceberg`
(`rck.local=false`, `uri`, `token`). Its log and access log are under
`build/openhouse-rest-compat-kit/tmp/<task>/service-*`. Its warehouse is not: as for the Spark 4.2
test services, it is a new directory (`ohrck-*`) under the system's temporary directory
(`ready.json` names it), removed when the service exits, so that a metadata location fits House
Tables' 255 characters wherever the repository is checked out.

The kit runs through two subclasses, `OpenHouseRestCompatibilityKitCatalogTests` and
`OpenHouseRestCompatibilityKitViewCatalogTests`, that only answer the kit's capability flags with
what OpenHouse is (no namespace create needed, one-level namespaces, no empty namespace,
server-owned locations). No kit test is skipped or excluded to get a green build.

### Reading `expected-outcomes.txt`

The list in the module names every kit test and its outcome: `PASSED`, or `FAILED` or `SKIPPED`
with a cause. A cause is a named category (`category RTAS | b | replace needs ...`) with a class and
a reason: (a) a model difference the kit has a flag for, (b) a model difference it has none for,
(c) a route or feature not served yet, (d) a defect, (e) an assumption that holds only for
Iceberg's reference implementation. Each test that does not pass is filed under the first step it
fails at, with the throwable that ended it (for a failed assertion `java.lang.AssertionError`,
whose message names the exception the client threw; for a skip the kit's assumption,
`org.opentest4j.TestAbortedException`, or `disabled`) and the stable start of its message: no
paths, UUIDs, ports or times; ` ... ` stands for any text. A line holds at both configurations
unless it starts with `default: ` or `v3: `; such a test is listed once for each.

Each task passes exactly when, at its configuration, the tests the kit ran are the tests listed
(none missing, none new: the total is the list's), each has the listed outcome, and each test that
does not pass fails or is skipped with the listed throwable type and a message that starts with the
listed prefix. Otherwise it fails and prints, for each difference, what is listed and what
happened. A JUnit listener in the test JVM (`RestCompatKitOutcomeListener`) records each test's
outcome and throwable, since Gradle's own events say only that a test failed. At the end of a green
run the task prints how many tests each cause holds.

### Changing the list

- **A fix makes a listed test pass.** The task fails ("it passes now: list it as PASSED") until its
  line says `PASSED`; when the outcome differs by configuration, until both lines say what happens.
  Remove a category no test is listed under any more: the task refuses an unused one.
- **A listed test fails for another cause.** The task fails and shows the listed and the actual
  throwable and message. If the new cause is a regression, fix it. If it is the next step of the
  same test once the earlier one is gone (a fix moved it further), file it under the cause it now
  fails for, after reading the kit's test to be sure that is the step it reached.
- **A test that passed fails, or a test is new.** It is a regression until reviewed; list it only
  with the cause it truly fails for.
- **The actual outcomes in the list's format** are written to
  `build/openhouse-rest-compat-kit/tmp/<task>/actual-outcomes.txt` on every run: compare it with the
  list, and copy a line from it only after checking that its message prefix holds nothing that
  changes from run to run and that its category is right (a new test's category is `?`).
- **A kit upgrade** (a new pinned Iceberg commit) can add, remove or rename tests and change the
  reference implementation's messages: the task names every test that did not run and every test
  not listed. Run both tasks, read each new test and each changed cause in the kit's source, and
  update the list in the same change as the pin, with the reason for each new failure.
