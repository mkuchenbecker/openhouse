# Opt-in build against an Iceberg fork (`-PicebergFork`)

The default build compiles and tests the Iceberg 1.5 variant (Spark 3.5, Scala 2.12) against
LinkedIn's `com.linkedin.iceberg` 1.5.2 releases from Maven Central. `-PicebergFork` builds that
same variant against a newer Iceberg built from source instead, so work on that line can be proven
here without touching the default build. Without the property nothing changes.

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
./gradlew -PicebergFork clean build    # clean keeps the published fork
./gradlew -PicebergFork :services:tables:test
./gradlew -PicebergFork :integrations:spark:spark-3.5:openhouse-spark-3.5-itest:test
```

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

## What the switch changes

All in [`gradle/iceberg-fork.gradle`](../../gradle/iceberg-fork.gradle) or guarded by the same
property:

- `iceberg_1_5_version` becomes the fork version for every module on the 1.5 line.
- A repository limited to `com.linkedin.iceberg` at exactly that version, served only from
  `~/.gradle/iceberg-fork/<version>`.
- `iceberg-aws` (the service's `S3FileIO` and `AwsClientFactories`, in `openhouse.iceberg-aws-conventions`)
  is the fork's too, with the AWS SDK at the pin file's `awsSdkVersion` plus `kms`, which it
  needs; `s3` brings `apache5-client`, its default HTTP client. The SDK's `netty-nio-client` is
  excluded: OpenHouse builds no async S3 client, and that SDK's Netty would split the service's
  Netty between 4.1.138 and 4.1.86 and break its WebClient. So no Iceberg jar of another line is
  on any classpath, and snapshot summaries name the fork's version. The default build keeps
  LinkedIn's `iceberg-aws` 1.2.0.6 and SDK 2.20.18; there its `iceberg-build.properties` can come
  before core's on a classpath, and `IcebergBuild.version()` then says 1.2.0.6 (seen on the Spark 3.5
  apps test classpath; LinkedIn's 1.5.2 does not record the version in snapshot summaries).
- Every module compiles to Java 17 (`openhouse.java-minimal-conventions`).
- The root ORC/Avro forces are replaced by the pinned versions, and Caffeine is pinned.
- `iceberg-parquet`/`iceberg-orc` are added at runtime on the 1.5 line and kept off the Spark 3.5
  test classpaths, where the shaded `iceberg-spark-runtime` already carries them.

The Iceberg 1.2 / Spark 3.1 projects are left out of the build under the switch (decision D15,
`settings.gradle`): `apps/spark`, `integrations/java/iceberg-1.2/*`, `integrations/spark/spark-3.1/*`
and `tables-test-fixtures/tables-test-fixtures-iceberg-1.2`. The single-copy server modules compile
against the fork there, so those projects could only be a hybrid of 1.2 and 1.12 artifacts. The
sources they share with the 1.5 projects live in the 1.5 projects, under `src/shared`, and the 1.2
projects name those directories, so nothing in the build under the switch reads a directory of a
left-out project. `services:tables` tests
use the 1.5 fixtures (built from the same sources), and `libs:datalayout`, which the Spark 3.5 apps
share, is built on the Spark 3.5 / fork line. There it skips the root dependency forces and keeps
the thin `iceberg-orc`/`iceberg-parquet` jars off its test classpath, as the Spark 3.5 apps do.
The deployables are built from the projects that are in the build: see "Docker images" below.

The delta-harness runs on this build with `HARNESS_ICEBERG_FORK=1`
(`integrations/spark/delta-harness/HARNESS-GUIDE.md`).

## Docker images

`./gradlew -PicebergFork shadowJar` builds every uber jar, and `./gradlew -PicebergFork
dockerPrereqs` the jars the images need, from the projects that are in this build: the Spark 3.5
apps uber jar where the default build takes the Iceberg 1.2 / Spark 3.1 one, and no Spark 3.1
runtime. It also copies the fork's `iceberg-spark-runtime-3.5_2.12` to `build/iceberg-spark-runtime/`
(`stageIcebergSparkRuntime`), because a Docker build reads only the checkout and no repository
serves that jar.

[`infra/recipes/docker-compose/iceberg-fork.env`](../../infra/recipes/docker-compose/iceberg-fork.env)
holds the docker compose variables that select the images of this build. `./gradlew -PicebergFork
dockerBuild`, `dockerUp` and `dockerDown` pass it; by hand:

```bash
./gradlew -PicebergFork dockerPrereqs
docker compose --env-file infra/recipes/docker-compose/iceberg-fork.env \
    -f infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml up -d --build
```

Without the file, and without the switch, every recipe and image is built as before. With it:

- The service images (tables, housetables, jobs, optimizer) need nothing: their Dockerfiles copy
  `build/<service>/libs`, whichever build filled it, and their base images
  (`openjdk:23-ea-11-slim`, `eclipse-temurin:17-jre-jammy`) run Java 17 class files.
- `jobs-scheduler.Dockerfile` takes `OH_SPARK_APPS_NAME=openhouse-spark-apps-1.5_2.12` as a build
  argument. The Spark 3.5 apps jar carries `log4j2.properties` under the switch (the Iceberg 1.2
  line's apps jar always has): without it the scheduler logs errors only.
- The Spark services (`OH_SPARK_DOCKERFILE`) are built from `spark-3.5-base-hadoop3.2.dockerfile`
  with `OH_SPARK_BASE=iceberg-fork`: Spark 3.5.2, a Java 17 runtime for every JVM in the image (the
  jars are Java 17 class files), the fork's Iceberg runtime in `/opt/spark/jars`, and the Spark 3.5
  apps jar. The OpenHouse jars keep the names they have in the Spark 3.1 image
  (`/opt/spark/openhouse-spark-runtime_2.12-latest-all.jar`,
  `/opt/spark/openhouse-spark-apps_2.12-latest-all.jar`), so `jobs.yaml`, `livy_cli.py` and the
  dataloader integration tests name the same paths on both.
- `OH_SPARK_BUILDER=dockerhub` selects where that image's Livy and Spark come from. The
  dockerfile's own builder stage installs some 200 Ubuntu packages and downloads Spark and Maven
  from `archive.apache.org`, and Livy's build downloads a second Spark release from there; on
  GitHub's runners that host served 46 kB/s (two and a half hours for one tarball) and `apt-get`
  took 9 and 42 minutes. With `dockerhub`, Livy (the same commit and patch) is built in Maven's
  Docker image without its `integration-test` and `coverage` modules, and `/opt/spark` is copied
  from Apache's Docker image of the same release (`apache/spark:3.5.2-...`): nothing comes from
  those two hosts. Without the argument the dockerfile does what it did.
- The jobs service reads `jobs-iceberg-fork.yaml` (`OPENHOUSE_JOBS_CONFIG_PATH`): `jobs.yaml` without
  `spark.jars.packages`, which has Spark download Apache's `iceberg-spark-runtime-3.1_2.12:1.2.0`.
  Keep the two files equal otherwise.
- A client that opens Spark sessions on that image must not ask for that download either: set
  `OH_SPARK_JARS_PACKAGES` to the empty string for the dataloader's `make integration-tests` and for
  `scripts/python/livy_cli.py`.

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

The opt-in build is one reusable workflow, `iceberg-fork-build.yml`, with one job
(`iceberg-fork-build`). Two workflows call it, each beside its default build job, which it neither
blocks nor waits for:

- `post-merge-build.yml`, after each merge to `irc-integration` and by hand (`workflow_dispatch`).
  A running post-merge run is never cancelled.
- `pr-validations.yml`, on every pull request (decision D33): a change that breaks the build under
  the switch is red before it merges. A newer push to a pull request cancels the pull request's
  older run.

The job runs `publish-local.sh`, then `-PicebergFork assemble testClasses`, then the opt-in suites:
`:services:tables:test`, `:iceberg:openhouse:internalcatalog:test`,
`:iceberg:openhouse:htscatalog:test`, the capabilities module's tests, the 1.5 Java itest, the
Spark 3.5 itest (`catalogTest`, `statementTest`, `test`), the Spark 4.2 SQL extensions' tests and
the Spark 4.2 itest (`test`, `extensionsTest`), and last, in a step of its own that runs even when a
suite failed, Apache Iceberg's REST compatibility kit (`restCompatKitTest`).

After the suites and the kit, whatever their outcome (but not after a failed compile), the job checks
the deployables of the build ("Docker images" above):

- `-PicebergFork shadowJar dockerPrereqs`.
- The jobs-scheduler image, built through docker compose with `iceberg-fork.env` from the Spark 3.5
  apps jar. The scheduler is started in it without arguments and must log its first line at INFO,
  which needs the image's Java to run the jar and the jar to carry its `log4j2.properties`.
- The `oh-only` recipe with `iceberg-fork.env`, then `scripts/python/integration_test.py` against it,
  as the default build does for its own containers. The tables container's jar must hold the fork's
  `iceberg-core` and no other. The containers are stopped in every case, and their logs are printed
  when one of these steps failed.

The Spark image and the `oh-hadoop-spark` recipe are checked by the Dataloader job,
`dataloader-tests.yml`, which takes an input, `iceberg-fork`. With it the job restores the fork,
runs `-PicebergFork shadowJar dockerPrereqs`, starts the recipe with `iceberg-fork.env` (which builds
the Spark 3.5 / Java 17 image, Livy included) and runs the dataloader's integration tests with
`OH_SPARK_JARS_PACKAGES` empty. Without the input it is the default build's job, unchanged: pull
requests into `main` that touch `integrations/python/dataloader/**`.
`dataloader-tests-iceberg-fork.yml` calls it with the input:

- on a pull request into `irc-integration` or `main` that touches the dataloader, as on the default
  build, or a file that defines the recipe and its images on this line
  (`infra/recipes/docker-compose/common/**`, `oh-hadoop-spark/**`, `iceberg-fork.env`, the root
  `*.Dockerfile`s, these two workflows, the action below). Other pull requests do not run it;
- by hand (`workflow_dispatch`), on any branch.

The steps that restore or publish the fork are one composite action, `.github/actions/iceberg-fork`,
used by both jobs.

The published `~/.gradle/iceberg-fork/<version>` is cached, keyed on the pinned commit and the
script, so the fork is built (60 to 75 minutes on a runner) only when either changes:

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

When a suite fails, the job's last two steps print each failed test with the start of its failure
(`scripts/ci/print-failed-tests.py`, read from the JUnit XML results, plus the end of the Spark 4.2
tests' service logs) and upload the test reports as the artifact `iceberg-fork-test-reports` (kept
7 days). The failed tests are therefore at the end of the job's log, not only where they ran.

## Spark 4.2 over the IRC read routes

`:integrations:spark:spark-4.2:openhouse-spark-4.2-itest` exists only under the switch
(`settings.gradle`). Its tests run Spark 4.2 (the pin file's `spark42Version`) with the fork's
`iceberg-spark-runtime-4.2_2.13`, Iceberg's own `SparkCatalog` and `RESTCatalog`
(`type=rest`, `uri=<service>/iceberg`, `token=<bearer>`) and no OpenHouse catalog or client class on
the classpath, against the tables service's IRC routes:

```bash
./gradlew -PicebergFork :integrations:spark:spark-4.2:openhouse-spark-4.2-itest:test
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

`:integrations:spark:spark-4.2:openhouse-spark-4.2-runtime_2.13` (only under the switch) is the
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
./gradlew -PicebergFork :integrations:spark:spark-4.2:openhouse-spark-4.2-runtime_2.13:test
./gradlew -PicebergFork :integrations:spark:spark-4.2:openhouse-spark-4.2-itest:extensionsTest
```

Over a plain `RESTCatalog` every OpenHouse statement works. The policy statements ride the IRC
commit (the server advertises `policies` and applies the patch). GRANT, REVOKE, SHOW GRANTS and
UNLOCK go through the REST capability client in the capabilities module (`capabilities.rest`), which
calls the OpenHouse routes under the IRC base with the catalog's own URI, prefix, `header.*` values
and authentication, and gives the outcomes Spark 3.5 gives over `RESTCatalog`. The client's
transport is the one per-line part: the module compiles `src/iceberg-1.5/java` by default and
`src/iceberg-1.12/java` (Iceberg's `AuthManager`) under `-PicebergFork`. `extensionsTest` runs in its
own JVM and service JVM, because Spark fixes a session's extensions when the session is built.

## Apache Iceberg's REST compatibility kit

`:integrations:java:openhouse-rest-compat-kit` (only under the switch) runs Apache Iceberg's REST
compatibility kit (RCK), the fork's `iceberg-open-api` tests, against the tables service's IRC
routes with Iceberg 1.12's own `RESTCatalog`: the kit's table suite (`CatalogTests`) and view suite
(`ViewCatalogTests`), every test of both.

```bash
scripts/iceberg-fork/publish-local.sh   # also publishes iceberg-open-api's tests and test fixtures
./gradlew -PicebergFork :integrations:java:openhouse-rest-compat-kit:restCompatKitTest
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
