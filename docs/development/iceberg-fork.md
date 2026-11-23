# The Iceberg lines of the build: 1.12 by default, LinkedIn's 1.5.2 as the reference (`-PicebergLinkedIn`)

The default build compiles and tests the Iceberg 1.5 variant (Spark 3.5, Scala 2.12), the Spark 4.2
modules and Apache Iceberg's REST compatibility kit against Iceberg 1.12, built from a pinned commit
of the Iceberg fork. `./gradlew build` with no property is that build, on Java 17.

`-PicebergLinkedIn` builds the reference line instead: the Iceberg 1.5 variant against LinkedIn's
`com.linkedin.iceberg` 1.5.2 releases from Maven Central. It exists for one purpose: LinkedIn's
line is the behavior the 1.12 line is measured against (decision D8), so its suites and the
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

Every Gradle command on the default build, `help` and the git hooks' `spotlessCheck` included,
needs the fork published first: until then it stops with a message that names the script.

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
deleted (decision D3). The sources they shared with the 1.5 projects are those projects'
`src/shared` directories, ordinary source roots now.

The delta-harness runs on the default build; `HARNESS_ICEBERG_LINKEDIN=1` runs it on the reference
line (`integrations/spark/delta-harness/HARNESS-GUIDE.md`).

## Publishing

The default build refuses to publish to a remote Maven repository. Its Iceberg is hosted nowhere
(decision D7), so the POMs would name `com.linkedin.iceberg:*:<baseVersion>-ohfork.<commit>`, which
no consumer can resolve. `./gradlew publish`, or any single task that publishes to a repository
that is not a `file:` URL, stops when the task graph is ready, before any task runs, with a message
that says so (`gradle/iceberg-fork.gradle`). The ways forward are to publish from the reference
line (`./gradlew -PicebergLinkedIn publish`), or to host the fork's Iceberg artifacts first, which
is the repository owner's decision (work item I6d). `publishToMavenLocal` is not affected.
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
  (`openhouse-spark-apps-1.5_2.12`), which carries `log4j2.properties`: without it the scheduler
  logs errors only.
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
  build in about 6 minutes, of which Livy's build is three and a half.
  `OH_SPARK_BUILDER=default docker compose ... build` uses the dockerfile's older builder stage,
  which installs some 200 Ubuntu packages and downloads Spark and Maven from
  `archive.apache.org`; on GitHub's runners that host served 46 kB/s (two and a half hours for one
  tarball) and `apt-get` took 9 and 42 minutes, so that stage has never finished there.
- Livy's own build would download one more Spark release (228 MB) from `archive.apache.org`, in
  its `thriftserver` and `integration-test` modules, for tests that are skipped. Both builder
  stages pass `-Ddownload.plugin.skip=true`, the skip property of the `download-maven-plugin`
  version Livy's pom pins (1.3.0; later versions of the plugin call it `download.skip`).
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

- Column defaults (decisions D5, D11a): on the 1.12 line the client applies no
  `openhouse.read-bridge.column-default.*` overlay and the Spark catalogs do not declare the
  `column-defaults` capability, so a table with `required-capability.column-defaults=true` is
  refused with 422 `CLIENT_CAPABILITY_MISSING`. Iceberg 1.12 itself refuses a non-null
  `initial-default` below format v3; tests that need one build a v3 table on that line.
- `REPLACE TABLE ... AS SELECT` that drops a former partition source column succeeds on 1.12 and
  fails Iceberg validation on 1.5.2 (decision D14a, apache/iceberg#11868).
- `CREATE NAMESPACE` and `DROP NAMESPACE` are unsupported on both lines, with the message "Checking
  if database exists is not supported" on 1.12 instead of "Describing database is not supported"
  (decision D14b, apache/iceberg#14507).
- After several merge-on-read `DELETE`s on one data file, 1.12 keeps one live position delete file
  for it (Spark's delete granularity defaults to `file` and the earlier file is replaced), where
  1.5.2 keeps one per `DELETE` (`SparkMoRFunctionalTest`, decision D31).
- `ALTER TABLE ... ADD COLUMN c int DEFAULT 5` is refused ("setting default values in Spark is
  currently unsupported") instead of being accepted with the default dropped (decision D31).

With `write.wap.enabled=true` and `spark.wap.branch` set, an explicit `TIMESTAMP AS OF` or
`VERSION AS OF` read reads main's history on both lines (delta-harness
`interact.branch.ttBeforeBranchPoint`). Apache Iceberg 1.12 refuses it ("Cannot override ref,
already set snapshot id"); D31 does not accept that, and the fork's Spark 3.5 time-travel reads
ignore the session WAP branch from the pinned commit a88f556c on (fork pull request 12, item I11;
its tests pin the same on Spark 4.2).

On the REST-shaped path (`catalog-impl` `OpenHouseRestCatalog`), Iceberg 1.12's client calls only
the endpoints a server advertises, and with none advertised assumes a default set without views. So
on the 1.12 line the in-process adapter's config response lists every route it dispatches (all but
config itself, derived from its route table); on the legacy line it lists none, as before, because
Iceberg 1.5.2 has no endpoints in its config response. `OpenHouseRestAdapterEndpointsTest` keeps the
list and the routes equal on both lines.

The jobs client (`TablesClientFactory`, apps) declares `column-defaults` by the same rule as the
Spark catalogs. The scheduler's own jar carries neither `IcebergLine` nor Iceberg, so there the line
is not detectable and it declares the capability as before, with a warning.

## CI

Two jobs, each one reusable workflow, run beside each other on every pull request
(`pr-validations.yml`, decision D33) and after each merge to `irc-integration`
(`post-merge-build.yml`, also by hand with `workflow_dispatch`). Neither blocks nor waits for the
other. A newer push to a pull request cancels the pull request's older run; a running post-merge
run is never cancelled.

**The default build**, `build-run-tests.yml` (job `build-and-run-tests`):

- restores the published fork from the cache or publishes it (the composite action
  `.github/actions/iceberg-fork`);
- `./gradlew clean assemble testClasses`, then `./gradlew build --continue`: every project's tests,
  the Spark 3.5 itest (`catalogTest`, `statementTest`, `test`), the Spark 4.2 SQL extensions' tests
  and the Spark 4.2 itest (`test`, `extensionsTest`, `smokeTest`), and Apache Iceberg's REST
  compatibility kit (`restCompatKitTest`, part of `check`);
- after the tests, whatever their outcome (but not after a failed compile), the deployables
  ("Docker images" above): `./gradlew shadowJar dockerPrereqs`; the jobs-scheduler image, built
  through docker compose, in which the scheduler is started without arguments and must log its
  first line at INFO (which needs the image's Java to run the jar and the jar to carry its
  `log4j2.properties`); the `oh-only` recipe, then `scripts/python/integration_test.py` against
  it. The tables container's jar must hold the fork's `iceberg-core` and no other. The containers
  are stopped in every case, and their logs are printed when one of these steps failed.

`build-tag-publish.yml` calls the same workflow before a release.

**The reference build**, `iceberg-linkedin-build.yml` (job `iceberg-linkedin-build`): `./gradlew
-PicebergLinkedIn clean build --continue`, the whole build on LinkedIn's Iceberg 1.5.2, so that a
change that breaks the line the default is measured against is red before it merges. It needs no
published fork and builds no image.

When a suite fails, either job's last two steps print each failed test with the start of its
failure (`scripts/ci/print-failed-tests.py`, read from the JUnit XML results, plus, on the default
build, the end of the Spark 4.2 tests' service logs) and upload the test reports as the artifact
`default-build-test-reports` or `reference-build-test-reports` (kept 7 days). The failed tests are
therefore at the end of the job's log, not only where they ran.

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

A run takes about 16 minutes, and about 22 when it has to publish the fork first.

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

The Gradle cache is written only on `irc-integration`; a pull request and a branch run by hand read
it.

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
  are in `src/shared/test/java` (`SparkSqlSmokeScenarios`). It needs only `spark.sql` and JUnit. It
  is a copy of the Spark 3.5 suite's scenarios for now: `SmokeScenariosCopyTest` compares the two
  source files, and its comment names the change that leaves one list.
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
(`ViewCatalogTests`), every test of both.

```bash
scripts/iceberg-fork/publish-local.sh   # also publishes iceberg-open-api's tests and test fixtures
./gradlew :integrations:java:openhouse-rest-compat-kit:restCompatKitTest
```

The service runs in its own JVM as in the Spark 4.2 itest (`SpringH2TestApplication`, in-memory
H2, local storage, the dummy token interceptor, an authorization handler that allows everything,
views enabled for the namespaces the kit uses). A JUnit session listener in the test JVM starts it
and points the kit at `<service>/iceberg` (`rck.local=false`, `uri`, `token`). Its log and access
log are under `build/openhouse-rest-compat-kit/tmp/restCompatKitTest/service-*`. Its warehouse is
not: as for the Spark 4.2 test services, it is a new directory (`ohrck-*`) under the system's
temporary directory (`ready.json` names it), removed when the service exits, so that a metadata
location fits House Tables' 255 characters wherever the repository is checked out.

The kit runs through two subclasses, `OpenHouseRestCompatibilityKitCatalogTests` and
`OpenHouseRestCompatibilityKitViewCatalogTests`, that only answer the kit's capability flags with
what OpenHouse is (no namespace create, no namespace properties, one-level namespaces, no empty
namespaces, server-owned locations). The tests that still do not pass are listed, each with its
outcome, a class and a reason, in `expected-outcomes.txt` in the module: (a) a model difference the
kit has a flag for, (b) a model difference it has none for, (c) a route or feature not served yet,
(d) a defect, (e) an assumption that holds only for Iceberg's reference implementation.
`restCompatKitTest` passes exactly when the tests that do not pass, and their outcomes, are the
listed ones: a test that newly passes must be removed from the list, and one that newly fails is a
regression until it is listed. No kit test is skipped or excluded to get there.
