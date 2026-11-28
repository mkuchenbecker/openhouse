# V9d: the deployables build and start from a `-PicebergFork` build: full record

Bottom line: done, with two parts not run on this machine. Under `-PicebergFork` the uber jars, `dockerPrereqs`,
the service images, the jobs-scheduler image and the Spark image build without any artifact of the Iceberg 1.2 /
Spark 3.1 projects, and CI's Docker integration step passes on them. Without the switch the jars, the images and
the compose configuration are what they were. Not run here: the Spark image's own builder stage (the proxy refuses
the Livy repository) and the whole `oh-hadoop-spark` recipe with the dataloader tests (disk).

Written by a delegated agent on 2026-11-23, JDK 17, two Gradle workers. Marks: **[ran]** run here for this
record, **[read]** taken from code, configuration or an earlier record.

- Branch `irc/v9d-deployables-1.12`, worktree `/home/user/oh-v9d`, base `6cc7b092` (pull request 111). The work
  started on `7e8941ab`; `irc-integration` moved before the first commit, so the change was committed on the new
  base (fast-forward, nothing pushed before, no force push).
- Draft pull request 113 into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/113
- Head `26c6e16e7ec593904f535cec9f20d9f7beeba100`, eight commits.
- Logs and comparison data: `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/v9d/`
  (`logs/`, `snap/`, `dump-default-before/`, `dump-default-after/`, `compose/`, `img/`, `xml/`, `sparktrial/`).

## 1. Why the uber jars failed, and the fix

`./gradlew -PicebergFork shadowJar` on the base [ran]: BUILD FAILED, `:apps:openhouse-spark-apps-1.5_2.12:shadowJar`
and `:tables-test-fixtures:tables-test-fixtures-iceberg-1.5_2.12:shadowJar`, both
`java.lang.IllegalArgumentException: Unsupported class file major version 65`.

Cause [ran: an init script listed every file of each `shadowJar` task's configurations, and a script read the
class-file version of every class in every jar]:

| Uber jar | Input jar with Java 21 class files | Where |
| --- | --- | --- |
| Spark 3.5 apps | `com.fasterxml.jackson.core:jackson-core:2.22.2` | `META-INF/versions/21/.../FastDoubleSwar.class`, `FastIntegerMath.class` |
| 1.5 fixtures | the same `jackson-core:2.22.2` | the same two |
| 1.5 fixtures | `org.apache.parquet:parquet-jackson:1.17.1` | the same two classes, shaded, under `META-INF/versions/21/shaded/parquet/` |

`iceberg-spark-runtime-3.5` of the fork has four such classes too, but the apps' `shadowJar` already leaves out
every `com.linkedin.iceberg` artifact. The shadow plugin 7.1.2 reads each class it packs with an ASM that stops at
Java 18 class files. On the default build the same classpaths hold `jackson-core:2.15.2`, whose newest variant is
`META-INF/versions/19`, which `openhouse.apps-spark-common` already excludes for that reason.

Fix (commit 693b2ffd): `exclude 'META-INF/versions/21/'` in the `shadowJar` of `openhouse.apps-spark-common` and
`openhouse.tables-test-fixtures-common`. Why this and not a newer plugin: a plugin change would rebuild every default jar
with a different tool, and the version that reads Java 21 class files has, as far as I know, a new plugin id
(not checked here; no other plugin version was tried). Nothing is lost: the uber jars'
manifests do not declare `Multi-Release: true` [ran], so a JVM never reads a `META-INF/versions/*` entry in them.
A later dependency with `META-INF/versions/22` or higher will fail the same way and need the same line.

After [ran, at head 26c6e16e]: `./gradlew -PicebergFork shadowJar dockerPrereqs` BUILD SUCCESSFUL. Spark 3.5 apps
uber jar 157,148 entries, fixtures uber jar 81,100 entries, neither with a `META-INF/versions/21` entry.

No input of a default-build `shadowJar` has a `META-INF/versions/21` entry or a class file newer than Java 19
[ran: the same scan on the default build's eight shadow classpaths], so the exclude does nothing there.

## 2. What was built, and how each choice is selected

The rule: every choice is the switch, a build argument or a docker compose variable, and its default is what the
file did before.

| Thing | Default (unchanged) | Under the switch / with `iceberg-fork.env` | Selected by |
| --- | --- | --- | --- |
| `dockerPrereqs` | Spark 3.1 runtime, Spark 3.5 runtime, Iceberg 1.2-line apps uber jar, bootJars, tokens, OTel agent | Spark 3.5 runtime, Spark 3.5 apps uber jar, `stageIcebergSparkRuntime`, the rest the same | `-PicebergFork` |
| `dockerBuild`, `dockerUp`, `dockerDown` | `docker compose build`, `up -d`, `down` | the same with `--env-file infra/recipes/docker-compose/iceberg-fork.env` | `-PicebergFork` |
| `jobs-scheduler.Dockerfile` | `build/openhouse-spark-apps_2.12/libs/*-uber.jar` | `build/openhouse-spark-apps-1.5_2.12/libs/*-uber.jar` | build arg `OH_SPARK_APPS_NAME` |
| Spark services of every recipe | `spark-base-hadoop2.8.dockerfile` (Spark 3.1.1, Java 8) | `spark-3.5-base-hadoop3.2.dockerfile` with `OH_SPARK_BASE=iceberg-fork` | compose variables `OH_SPARK_DOCKERFILE`, `OH_SPARK_BASE`, `OH_SPARK_APPS_NAME` |
| Jobs service's job definitions | `/var/config/jobs.yaml` | `/var/config/jobs-iceberg-fork.yaml` (no `spark.jars.packages`) | compose variable `OPENHOUSE_JOBS_CONFIG_PATH` |
| Dataloader integration tests, `livy_cli.py` | ask Spark for `org.apache.iceberg:iceberg-spark-runtime-3.1_2.12:1.2.0` | ask for nothing | environment variable `OH_SPARK_JARS_PACKAGES` set to the empty string |
| `log4j2.properties` in the Spark 3.5 apps jar | not packaged | packaged | `-PicebergFork` |
| Service images (tables, housetables, jobs, optimizer) | no change needed: they copy `build/<service>/libs` | the same | nothing |

`infra/recipes/docker-compose/iceberg-fork.env` holds the four compose variables. The compose files pass a build
argument or the jobs variable only when it is set where compose runs (list form without a value), so without the
file nothing is passed [ran: `docker compose config`].

The `iceberg-fork` stage of the Spark 3.5 dockerfile adds, on top of the unchanged base image
(`bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8`, Debian 9, glibc 2.24):
- the Java 17 runtime of `eclipse-temurin:17-jre-jammy` at `/opt/java/openjdk-17`, as `JAVA_HOME` and first on
  `PATH`, because the jars of a `-PicebergFork` build are Java 17 class files, the token generator the image runs
  at build time included;
- `LIVY_SERVER_JAVA_OPTS` with the module opens Spark gives its own JVMs. Found by running it [ran]: without
  them the Livy server's RPC serializer fails on Java 17 (`InaccessibleObjectException ... does not "opens
  java.lang.invoke"`) and every session dies with "Client closed before SASL negotiation finished";
- the fork's `iceberg-spark-runtime-3.5_2.12` in `/opt/spark/jars`, from `build/iceberg-spark-runtime/`.

The OpenHouse jars keep their names inside the image (`/opt/spark/openhouse-spark-runtime_2.12-latest-all.jar`,
`/opt/spark/openhouse-spark-apps_2.12-latest-all.jar`), as the Spark 3.5 dockerfile already did for the runtime.
So the jar paths in `jobs.yaml` (lines 20, 22), `livy_cli.py` (line 152 at the base), the dataloader integration
test (line 40 at the base) and `spark-client.sh` (line 14) are right on both lines [read: each verified].

The references the brief named, verified [read, on 7e8941ab]: `jobs-scheduler.Dockerfile` line 6;
`spark-base-hadoop2.8.dockerfile` lines 86 and 87; `spark-3.5-base-hadoop3.2.dockerfile` line 86. One more kind,
not in the brief: the Apache Iceberg 1.2 runtime download in `jobs.yaml` line 10, `livy_cli.py` line 25, the
integration test line 41, `SETUP.md` lines 306 and 324 and `spark-client.sh` line 13.

## 3. `log4j2.properties`

Decision: the Spark 3.5 apps jar needs it, so it packages it under the switch. Why [ran]: the jobs-scheduler image
runs `java -cp <apps uber jar> ...JobsScheduler`. From the default build's two jars, same command, same arguments:
the Iceberg 1.2-line jar prints `INFO JobsScheduler:193 - Starting scheduler`, `OH_SCHED_START ...` and the rest;
the Spark 3.5 jar prints only the `ERROR` lines, because Log4j finds no configuration and falls back to its
default. After the change the scheduler in the image built from the fork build logs at INFO [ran].

How: the file moved to `apps/spark-3.5/src/shared/main/resources` (the place V9c made for what both apps projects
use). The Iceberg 1.2-line project names that directory; the Spark 3.5 project adds it only under the switch.
Both default jars keep their entries [ran, section 5].

Not evaluated: a Spark deployment that puts the Spark 3.5 apps uber jar on the driver's own classpath before
Log4j starts would take this configuration (console, INFO, stdout) in place of Spark's default (console, INFO,
stderr). In the image's Livy and `spark-submit` runs the jar is added after Log4j started.

## 4. What ran under the switch

Gradle [ran]:
- `./gradlew -PicebergFork shadowJar dockerPrereqs`: BUILD SUCCESSFUL (head 26c6e16e).
- `:tables-test-fixtures:tables-test-fixtures-iceberg-1.5_2.12:test`: 40 / 0 / 0 (tests / failed / skipped).
- `:apps:openhouse-spark-apps-1.5_2.12:test`: 304 / 0 / 5. Both at head; both tasks executed, not from cache.
- `spotlessCheck`: passes.

Docker [ran], with a private daemon, on the tree that became the head (same files):

1. CI's step (`build-run-tests.yml`) for the 1.12 line:
   `docker compose --env-file infra/recipes/docker-compose/iceberg-fork.env -f infra/recipes/docker-compose/oh-only/docker-compose.yml up -d --build openhouse-tables`,
   30 s, then `python scripts/python/integration_test.py ./tables-test-fixtures/tables-test-fixtures-iceberg-1.5/src/shared/main/resources/dummy.token`:
   "All tests passed successfully". The `tables.jar` in the container is byte-identical to the fork build's, holds
   `iceberg-core-1.12.0-ohfork.a88f556c5067.jar` and Java 17 classes, and runs on the image's
   `openjdk 23-ea+11`. `/iceberg/v1/config` answers 200. Difference from CI: only `openhouse-tables` and what it
   depends on were started; `oauth` (Keycloak) cannot be pulled, `quay.io` is refused by the proxy, and the
   script does not use it [read].
2. Scheduler image through compose with the env file (`--profile with_jobs_scheduler build openhouse-jobs-scheduler`
   on `oh-hadoop-spark`): `APP_NAME=openhouse-spark-apps-1.5_2.12`, the jar is the 345,805,703-byte Spark 3.5
   apps uber jar; `JobsScheduler` starts in it and logs at INFO.
3. Jobs image and the three optimizer images build. The jobs container has
   `OPENHOUSE_JOBS_CONFIG_PATH=/var/config/jobs-iceberg-fork.yaml` with the env file and no such variable
   without it. The jobs service was not started for real: alone it stops at "UnknownHostException: namenode".
   The two job files parse to the same YAML except for the 14 removed `spark.jars.packages` entries.
4. Spark image, `--build-arg OH_SPARK_BASE=iceberg-fork --build-arg OH_SPARK_APPS_NAME=openhouse-spark-apps-1.5_2.12`,
   every line from the first runtime stage on as committed (the builder stage replaced, see section 6): builds,
   4.52 GB. In it: `java -version` 17.0.21.1 (Temurin), `spark-submit --version` 3.5.2 on that JVM, the three
   jars and the two tokens in place. Then, with tables, housetables and OPA from the fork build on the same
   network and one shared directory as the warehouse:
   - `spark-sql` with the OpenHouse catalog and extensions, no `--packages`: CREATE TABLE partitioned by
     `days(ts)`, INSERT, SELECT (2 rows), `ALTER TABLE ... SET POLICY (RETENTION=30d)`, SHOW TBLPROPERTIES,
     SHOW TABLES: all pass; the snapshot summary says `Apache Iceberg 1.12.0-ohfork.a88f556c5067`.
   - Livy started by the image's `start-spark.sh` (`SPARK_WORKLOAD=livy`), a `kind: sql` session with the
     dataloader tests' configuration minus the package: CREATE, INSERT, SELECT (`[[1,"alice",1.1],...]`), DROP:
     all pass.
   - `spark-submit --class com.linkedin.openhouse.jobs.spark.OpenHouseCatalogSQLTestSparkApp` from the apps jar
     in the image: exit 0, its four statements ran. (It logs 404s from House Tables for the made-up job id.)

## 5. Proof that the default build and its images are unchanged

- Jar entry lists [ran]: `unzip -Z1 <jar> | sort` of all 35 jars under `build/*/libs` after
  `./gradlew dockerPrereqs shadowJar`, on 7e8941ab and with the change (twice: before committing and at head):
  `diff -r` empty, 570,053 entries. Entry contents (CRC) differ only where V9c found the same: `PageJob.class`
  and `PageUserTable.class` of `:client:hts` (generated code, project untouched) and their copies, the timestamp
  comment in `META-INF/spring.factories` of the fixtures uber jars, and the nested jars of the Spring Boot jars.
- Resolved artifacts [ran]: every resolvable configuration and every source set of all 60 projects, 36,574
  lines, 7e8941ab against the change: one differing line, `src apps/spark-3.5/src/shared/main/resources` added
  to the main source set of `:apps:openhouse-spark-apps_2.12`.
- Task graph [ran]: without the switch `dockerPrereqs` depends on the same eleven tasks as before, and the three
  docker tasks run `docker compose build`, `up -d`, `down`.
- Compose [ran]: `docker compose --profile '*' config` of the six recipes, 7e8941ab against the change:
  identical for five; `oh-hadoop-spark` gains one line, `OPENHOUSE_JOBS_CONFIG_PATH: null` under the jobs
  service, and the container has no such variable [ran].
- Scheduler image [ran]: built from the old and the new Dockerfile on the default build's jar: image
  configuration identical, file list identical (5,259 entries: mode, owner, size, path), same jar checksum.
- Spark 3.5 image with default arguments [ran]: built from the old and the new dockerfile with the same
  stand-in builder stage: configuration identical, file list identical (52,745 entries), no Java 17 and no
  Iceberg runtime in it. The Spark 3.1 dockerfile is not touched.
- Suites on the default build [ran, on 7e8941ab plus the change; pull request 111 touches no file they build]:
  fixtures 1.2 40 / 0 / 0, fixtures 1.5 40 / 0 / 0, `:apps:openhouse-spark-apps_2.12:test` 298 / 0 / 5,
  `:apps:openhouse-spark-apps-1.5_2.12:test` 304 / 0 / 5. `spotlessCheck` passes at head.
- Python [ran]: with `OH_SPARK_JARS_PACKAGES` unset, the integration test's `SPARK_CONF` and the CLI's template
  have the same keys in the same order and the same package as before; ruff check and format pass on the test.

One thing in a default path does change: the Spark 3.5 dockerfile's builder stage gains the Livy `pom.xml`
rewrite the Spark 3.1 dockerfile got in #865. Without it that image cannot be built at all [read: #865's
message says the snapshot was removed; not run, the proxy refuses `repository.apache.org`].

Pull request CI on the head [ran, run 45137048703]: `build-run-tests` (default `./gradlew clean build`, the
default `oh-only` containers, the integration tests) success in 32 minutes; `iceberg-fork-build` (the opt-in
job of pull request 111) success in 16 minutes.

## 6. What could not be run here, and why

1. **The Spark image's builder stage, either line.** It clones `https://github.com/apache/incubator-livy`; the
   proxy answers 403 for that repository (also for `codeload.github.com`), and `repository.apache.org` is refused
   too. Inside a build container it would also need the proxy's CA, and `apt` over plain HTTP, which the proxy
   does not serve. The runs of section 4 replaced the stage by Spark 3.5.2 (`archive.apache.org`) and Apache's
   released `apache-livy-0.8.0-incubating_2.12-bin` copied in from the host
   (`sparktrial/gen_local_dockerfile.py`). So: the pinned Livy commit, built with the repository's patch against
   Spark 3.1.1, has not been run against Spark 3.5.2 and Java 17. If it fails where the release works, the
   builder stage can download the release instead.
2. **`dataloader-tests.yml` (the `oh-hadoop-spark` recipe, then `make integration-tests`).** Disk: the two HDFS
   images (3.95 GB), MySQL, the Spark image (4.5 GB) and the service images do not fit in what this machine had
   free (7 to 11 GB, shared with other builds). The disk filled twice while I tried (see section 9). So Spark 3.5
   with its Hadoop 3.3 client against the recipe's HDFS 2.8 namenode, and the Python dataloader reading those
   tables, were not run. The Spark runs used the local filesystem.
3. **`./gradlew -PicebergFork dockerBuild` / `dockerUp` as tasks**: they build the Spark image. Their command
   lines and dependencies were printed [ran].
4. **Pulls** needed retries: Docker Hub's anonymous limit (100 per hour per address) was used up on the shared
   egress addresses; a retry loop got every image within a few minutes.
5. `make verify` of the dataloader (its environment needs about 1 GB of disk); only ruff ran on the changed file.

## 7. For V9e: what to flip, what to delete

Flip (each is the default of a choice this change added):
1. `jobs-scheduler.Dockerfile`: `OH_SPARK_APPS_NAME` default becomes `openhouse-spark-apps-1.5_2.12` (or drop
   the argument and write the `ENV`). `build-tag-publish.yml` line 87 builds the file without arguments, so the
   published image follows.
2. `common/oh-services.yml`: delete the `args` block of `openhouse-jobs-scheduler` (3 lines).
3. `common/spark-services.yml`: `dockerfile:` becomes the Spark 3.5 path; delete the three `args` blocks and
   the header comment.
4. `spark-3.5-base-hadoop3.2.dockerfile`: delete `ARG OH_SPARK_BASE`, the alias `spark-base-default` and
   `FROM spark-base-${OH_SPARK_BASE}`; the four instructions of the `iceberg-fork` stage join the one runtime
   stage; the apps name becomes a literal.
5. Root `build.gradle`: `stageIcebergSparkRuntime` and its configuration lose their `if (icebergFork)`;
   `dockerPrereqs` keeps the `icebergFork` arm; `dockerSparkAppsName` becomes a literal; `dockerCompose(...)`
   goes back to plain commands; the header comment keeps only the new list.
6. `oh-hadoop-spark/`: `git mv jobs-iceberg-fork.yaml jobs.yaml`; delete `- OPENHOUSE_JOBS_CONFIG_PATH` and its
   comment in `docker-compose.yml`.
7. `integrations/python/dataloader/tests/integration_tests.py` and `scripts/python/livy_cli.py`: delete
   `SPARK_JARS_PACKAGES` and the conditional entry; `Makefile`: delete `-e OH_SPARK_JARS_PACKAGES`.
8. `apps/spark-3.5/build.gradle`: drop the `if` around the shared resources directory.
9. `dataloader-tests.yml`: besides the fork publish steps it must run `dockerPrereqs` (or
   `stageIcebergSparkRuntime`), not only `shadowJar`: the Spark image copies `build/iceberg-spark-runtime/`.
10. `docs/development/iceberg-fork.md`, section "Docker images"; `SETUP.md` lines 306 and 324 and
    `spark-client.sh` line 13 (the `--packages` of the Iceberg 1.2 runtime).

Delete: `infra/recipes/docker-compose/iceberg-fork.env`; `spark-base-hadoop2.8.dockerfile`; the old `jobs.yaml`
(replaced in 6); the `else` arm in `dockerPrereqs`; and the old projects and `settings.gradle` entries of V9c's
list. The two `META-INF/versions/21` excludes stay.

Published names that disappear [read: V9c's record, not re-derived]: Maven artifacts
`openhouse-spark-runtime_2.12`, `openhouse-spark-apps_2.12`, `tables-test-fixtures_2.12`, `openhouse-java-runtime`
(each also as `-uber`), `openhouse-java-itest`, `openhouse-spark-itest`; `all-modules` lists fewer modules. The
`jobs-scheduler` image keeps its name; inside it the jar and `APP_NAME` become `openhouse-spark-apps-1.5_2.12`.
The jar names inside the Spark image do not change.

## 8. Steps for the opt-in CI job (not added here; the workflow files are being changed elsewhere)

After "Compile everything under -PicebergFork" in `iceberg-fork-build.yml`:

```yaml
      - name: Build the uber jars and the Docker prerequisites under -PicebergFork
        run: ./gradlew -PicebergFork shadowJar dockerPrereqs

      - name: Build the jobs-scheduler image from the Spark 3.5 apps jar
        run: >-
          docker build --build-arg OH_SPARK_APPS_NAME=openhouse-spark-apps-1.5_2.12
          -f jobs-scheduler.Dockerfile -t jobs-scheduler:iceberg-fork .

      - name: Start the oh-only recipe from the -PicebergFork jars
        run: >-
          docker compose --env-file infra/recipes/docker-compose/iceberg-fork.env
          -f infra/recipes/docker-compose/oh-only/docker-compose.yml up -d --build

      - name: Wait for the containers
        run: sleep 30

      - name: Set up Python
        uses: actions/setup-python@v6
        with:
          python-version: '3.12'

      - name: Run the integration tests against the containers
        run: |
          pip install -r scripts/python/requirements.txt
          python scripts/python/integration_test.py ./tables-test-fixtures/tables-test-fixtures-iceberg-1.5/src/shared/main/resources/dummy.token

      - name: Stop the containers
        if: always()
        run: >-
          docker compose --env-file infra/recipes/docker-compose/iceberg-fork.env
          -f infra/recipes/docker-compose/oh-only/docker-compose.yml down
```

The first run of the Spark image and the dataloader tests on the 1.12 line belongs in CI too, where the Livy
repository is reachable (about what `dataloader-tests.yml` does, after `./gradlew -PicebergFork shadowJar dockerPrereqs`):

```bash
docker compose --env-file infra/recipes/docker-compose/iceberg-fork.env \
    -f infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml up -d --build
# wait for tables (8000) and Livy (9003) as dataloader-tests.yml does, then:
cd integrations/python/dataloader && OH_SPARK_JARS_PACKAGES= make integration-tests \
    TOKEN_FILE=../../../tables-test-fixtures/tables-test-fixtures-iceberg-1.5/src/shared/main/resources/dummy.token
```

## 9. Decisions, deviations and things to know

Decisions:
- Compose variables with defaults, not override files: one env file serves all six recipes, and V9e flips
  three defaults. An override file would be needed per recipe.
- Args in the existing Spark 3.5 dockerfile, not a new dockerfile: after V9e there is one Spark dockerfile.
- Jar names inside the Spark image stay: four files that name them need no per-line variant.
- The fork's Iceberg runtime goes into `/opt/spark/jars`: every Spark application in the image has it without
  configuration, where the default has Spark download Apache's 1.2.0 runtime per session.
- Service images keep their bases. The brief says "on a Java 17 base"; `openjdk:23-ea-11-slim` and
  `eclipse-temurin:17-jre-jammy` run Java 17 class files, and tables and housetables ran on the former [ran].
  A new base would be a change to the default images for no gain. Say so if a literal Java 17 base is wanted.
- Java 17 for the whole Spark image, Livy included, with the module opens, not Java 8 for Livy and 17 for Spark:
  one JVM in the image, and the token generator needs 17 anyway.

Deviations and incidents:
- **The shared disk filled twice because of my Docker builds** (about 07:16 and 07:35 UTC: 0 MB, then 588 MB
  free, each for some seconds until I removed images). A build of another agent running at those moments may
  have failed with "No space left on device". After the second time I switched my daemon to the classic image
  store and ran a guard that pruned my build cache below 2 GB free; it never fired.
- I changed a test file (`integration_tests.py`) and its Makefile. With the variable unset the test sends what
  it sent before.
- I moved `log4j2.properties` out of an old project's directory (the brief says not to delete or rename the old
  projects; this follows V9c's pattern for shared files).
- I fixed the Spark 3.5 dockerfile's Livy build, which is outside "selected by a switch"; see section 5.
- The default suites ran on 7e8941ab plus the change, before I moved to 6cc7b092; the fork suites at head.
- The repository's commit hook stashes with a bare `git stash` on the stack all worktrees share. I committed
  with nothing unstaged each time; the hook's own entry was pushed and popped within the hook, and the stack was
  empty after every commit [ran].
- The hook's Gradle run used a daemon that was already running (not mine); I left it alone.
- `integrations/python/dataloader/CLAUDE.md` still names the old `dummy.token` path (V9c's note); not touched.

Known gaps:
- Section 6, items 1 and 2.
- W13 is still open: the scheduler in the image warns "Iceberg line not detectable ... declaring
  column-defaults as on the legacy line" [ran].
- The fork's `iceberg-spark-runtime-3.5` is built for Spark 3.5.9 and the image runs 3.5.2 (I6f's note); the
  runs above passed on that pair.
- `jobs-iceberg-fork.yaml` duplicates `jobs.yaml` until V9e; a change to one must be made in the other.
- A Docker builder without BuildKit builds unused stages and would fail on the default build for want of
  `build/iceberg-spark-runtime/`. Docker 23 and later and docker compose v2 use BuildKit.
- With the env file, `oh-only` behaves as without it (no scheduler, no Spark there).

## 10. Clean-up [ran]

My Docker daemon, its guard and pull loops are stopped and `/tmp/v9d-docker` is removed. The base worktree
`/home/user/oh-v9d-base`, the stand-in downloads (`build/v9d-local`) and the scratch Python environment are
removed. No Gradle build, test JVM or container of mine is running.
