# V9d result: the deployables build and start on the 1.12 line

Bottom line: V9d is done (pull request 113, merge commit `b1dfe9b06156ba365631d30e748f8279a40c0bd5`, merged into
`irc-integration`). Under the 1.12 switch (`-PicebergFork`) the uber jars, the Docker prerequisites, the scheduler
image and the Spark image (Spark 3.5 on Java 17 with the fork's Iceberg runtime) build and start without any
artifact of the Iceberg 1.2 / Spark 3.1 projects. Without the switch the jars, images and compose configuration
are unchanged. The pull request's own CI passed both builds. Two things were not run: the Spark image's Livy
build on Java 17, and the `oh-hadoop-spark` recipe with the dataloader tests. A new item, C9, puts those checks
into CI. Dated 2026-11-23. Source: the delegated agent's report; marks are as it gave them.

## What changed

- Uber jars failed under the switch with "Unsupported class file major version 65". Two dependencies carry Java 21
  class files under `META-INF/versions/21/` (`jackson-core` 2.22.2, and `parquet-jackson` 1.17.1 in the 1.5
  fixtures). The shadow plugin 7.1.2 cannot read them. Fix: both shadow conventions exclude
  `META-INF/versions/21/`. Nothing is lost, because the jars do not declare `Multi-Release`. A later dependency
  with `versions/22` or higher will need the same line.
- Every choice is the switch, a build argument or a compose variable, and its default is what the file did before.
  `dockerPrereqs` and the three docker tasks follow the switch; `jobs-scheduler.Dockerfile` takes the build
  argument `OH_SPARK_APPS_NAME`; the Spark services take the compose variables `OH_SPARK_DOCKERFILE`,
  `OH_SPARK_BASE` and `OH_SPARK_APPS_NAME` from `infra/recipes/docker-compose/iceberg-fork.env`; the jobs service
  reads `jobs-iceberg-fork.yaml` (no `spark.jars.packages`) through `OPENHOUSE_JOBS_CONFIG_PATH`; the dataloader
  tests and `livy_cli.py` ask Spark for no package when `OH_SPARK_JARS_PACKAGES` is empty.
- The Spark 3.5 image gains an `iceberg-fork` stage: the Java 17 runtime of `eclipse-temurin:17-jre-jammy`, Livy
  server module opens (without them every Livy session dies on Java 17), and the fork's
  `iceberg-spark-runtime-3.5_2.12` in `/opt/spark/jars`. Jar names inside the image are unchanged.
- `log4j2.properties` moved to `apps/spark-3.5/src/shared/main/resources` and is packaged under the switch. Without
  it the scheduler in the Spark 3.5 apps jar logged only errors.
- One default path changed: the Spark 3.5 dockerfile's builder stage got the Livy `pom.xml` rewrite that the
  Spark 3.1 one already had, without which it cannot be built. Not run (see below).

## Evidence

- Under the switch (head `26c6e16e`): `shadowJar dockerPrereqs` succeeds; fixtures 1.5 tests 40 / 0 failed / 0
  skipped; Spark 3.5 apps tests 304 / 0 / 5; `spotlessCheck` passes.
- Docker, private daemon: the CI step for the 1.12 line (`oh-only`, tables) passed the integration script ("All
  tests passed"); the tables jar in the container is byte-identical to the fork build's and `/iceberg/v1/config`
  answers 200. The scheduler image starts the scheduler and logs at INFO. The jobs image and the three optimizer
  images build. The Spark image builds (4.52 GB) with Java 17.0.21 and Spark 3.5.2. In it, `spark-sql` created,
  inserted into, read, altered (policy) and listed tables with no `--packages`; a Livy `sql` session created,
  inserted, read and dropped; `spark-submit` of the scheduler's test app exited 0.
- Default build unchanged: the entry lists of all 35 jars match `diff -r` (570,053 entries; only generated-code
  and timestamp differences already seen in V9c); resolved artifacts of all 60 projects differ in one line
  (the new source directory); the task graph is the same eleven tasks; five of six compose recipes are identical
  and `oh-hadoop-spark` gains one null variable; the scheduler image and the default Spark 3.5 image have equal
  configuration and file lists (5,259 and 52,745 entries). Default suites: fixtures 1.2 40 / 0 / 0, fixtures 1.5
  40 / 0 / 0, Spark 3.1 apps 298 / 0 / 5, Spark 3.5 apps 304 / 0 / 5.
- Pull request CI on the head (run 45137048703): the default build and the opt-in job both passed.

## What was not run, and why

1. The Spark image's builder stage, on either line. It clones the Livy repository, which this environment's
   network refuses (it also refuses `repository.apache.org`). The runs above used Spark 3.5.2 and Apache's
   released Livy 0.8.0 instead. So the pinned Livy commit with the repository's patch has not run against Spark
   3.5.2 on Java 17. If it fails, the stage can download the release.
2. The `oh-hadoop-spark` recipe and the dataloader integration tests. The images (about 9 GB with the Spark image)
   did not fit in the free disk, which filled twice. So Spark 3.5 with its Hadoop 3.3 client against the recipe's
   HDFS 2.8 namenode was not run; the Spark runs used the local filesystem.
3. The `dockerBuild` and `dockerUp` tasks themselves (their command lines were printed), and the dataloader's
   `make verify`.

Both items 1 and 2 are the content of C9, which runs where the Livy repository is reachable.

## For V9e: what to flip and what to delete

Flip, each the default of a choice this change added:
1. `jobs-scheduler.Dockerfile`: `OH_SPARK_APPS_NAME` defaults to `openhouse-spark-apps-1.5_2.12`. The publish
   workflow builds it without arguments, so the published image follows.
2. `common/oh-services.yml`: delete the `args` block of `openhouse-jobs-scheduler`.
3. `common/spark-services.yml`: `dockerfile:` becomes the Spark 3.5 path; delete the three `args` blocks and the
   header comment.
4. `spark-3.5-base-hadoop3.2.dockerfile`: delete `ARG OH_SPARK_BASE`, the alias `spark-base-default` and
   `FROM spark-base-${OH_SPARK_BASE}`; the `iceberg-fork` stage's four instructions join the one runtime stage; the
   apps name becomes a literal.
5. Root `build.gradle`: `stageIcebergSparkRuntime` loses its `if (icebergFork)`; `dockerPrereqs` keeps the
   `icebergFork` arm; `dockerSparkAppsName` becomes a literal; `dockerCompose(...)` goes back to plain commands.
6. `oh-hadoop-spark/`: `git mv jobs-iceberg-fork.yaml jobs.yaml`; delete `OPENHOUSE_JOBS_CONFIG_PATH` and its
   comment in `docker-compose.yml`.
7. `integrations/python/dataloader/tests/integration_tests.py` and `scripts/python/livy_cli.py`: delete
   `SPARK_JARS_PACKAGES` and the conditional entry; `Makefile`: delete `-e OH_SPARK_JARS_PACKAGES`.
8. `apps/spark-3.5/build.gradle`: drop the `if` around the shared resources directory.
9. `dataloader-tests.yml` must run `dockerPrereqs` (or `stageIcebergSparkRuntime`), not only `shadowJar`, because
   the Spark image copies `build/iceberg-spark-runtime/`.
10. Documents naming the old runtime: `docs/development/iceberg-fork.md` section "Docker images", `SETUP.md` lines
    306 and 324, and `spark-client.sh` line 13.

Delete: `infra/recipes/docker-compose/iceberg-fork.env`; `spark-base-hadoop2.8.dockerfile`; the old `jobs.yaml`
(replaced in step 6); the `else` arm in `dockerPrereqs`; and the old projects and `settings.gradle` entries from the
V9c list in `44-v9abc-flip-prep-result.md`. The two `META-INF/versions/21` excludes stay.

Published names that disappear (from V9c's record, not re-derived): the Maven artifacts
`openhouse-spark-runtime_2.12`, `openhouse-spark-apps_2.12`, `tables-test-fixtures_2.12`, `openhouse-java-runtime`
(each also as `-uber`), `openhouse-java-itest` and `openhouse-spark-itest`; `all-modules` lists fewer modules. The
`jobs-scheduler` image keeps its name; inside it the jar and `APP_NAME` become `openhouse-spark-apps-1.5_2.12`. Jar
names inside the Spark image do not change.

## Known gaps

- Service images are not literally on a Java 17 base. They keep `openjdk:23-ea-11-slim`, which runs the Java 17
  class files (tables and housetables ran on it). A new base would change the default images for no gain; a
  literal Java 17 base is a change V9e can make if wanted.
- W13 is still open: the scheduler in the image warns that the Iceberg line is not detectable.
- The fork's Spark runtime is built for 3.5.9 and the image runs 3.5.2 (I6f's note); the runs passed on that pair.
- `jobs-iceberg-fork.yaml` duplicates `jobs.yaml` until V9e.
- A Docker builder without BuildKit builds unused stages and would fail on the default build. Docker 23 and later
  use BuildKit.
- A Spark deployment that puts the Spark 3.5 apps jar on the driver's classpath before Log4j starts would take
  the packaged log configuration instead of Spark's default. Not evaluated.
- The agent's Docker builds filled the shared disk twice (seconds each); another agent's build at those moments
  may have failed with "No space left on device".
