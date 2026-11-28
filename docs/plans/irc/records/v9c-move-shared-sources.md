# V9c: the shared sources live in the Iceberg 1.5 / Spark 3.5 projects: full record

Bottom line: done. Five newer projects compiled 193 files that lived in the Iceberg 1.2 / Spark 3.1
project directories. The files now live in the newer projects under `src/shared`, and the old projects
name those directories. Every jar of both builds has the same entries as before, the compiled classes of
every project are byte-identical, and the full suites of both builds pass. With the old directories and
their `settings.gradle` entries deleted, the opt-in build compiles all main, test and service code.

Written by a delegated agent on 2026-11-23, JDK 17, two Gradle workers. Marks: **[ran]** run here,
**[read]** taken from code or configuration.

- Branch `irc/v9c-move-shared-sources`, worktree `/home/user/oh-v9c`, base bfdcf266.
- Draft pull request 110 into `irc-integration`. Head e6b936fbc75ca5fcb8a109f23cf3221ea897e71a: six commits
  (fd46e0e0, 8502a799, 6604c915, bf26aa84, 831d4dbe, 826369a3), then a merge of `origin/irc-integration`
  at 152b08e7 (pull request 107). `origin/irc-integration` has since moved to 3222cf78 (pull request 108,
  Spark 4.2 test services only), which is not merged in and merges without conflict.
- Logs and comparison data: `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/v9c/`
  (`logs/`, `snap/`, `deletion-proof.patch`, `counts-after-default.txt`, `counts-after-fork.txt`).

## 1. What was shared, and where it lives now

All found by reading every `build.gradle`, `settings.gradle`, `buildSrc`, the workflows, Docker files and
scripts for `srcDirs`, `rootProject.file`, `project(...).sourceSets`, copy tasks and file paths [read], and
confirmed by the deletion proof [ran].

| Shared set | Files | Was in | Is now in | Who compiles it |
| --- | --- | --- | --- | --- |
| Tables test fixtures, main | 9 | `tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/java` | `tables-test-fixtures/tables-test-fixtures-iceberg-1.5/src/shared/main/java` | both fixtures projects |
| Tables test fixtures, resource `dummy.token` | 1 | `.../iceberg-1.2/src/main/resources` | `.../iceberg-1.5/src/shared/main/resources` | both |
| Tables test fixtures, tests | 6 | `.../iceberg-1.2/src/test/java` | `.../iceberg-1.5/src/shared/test/java` | both |
| Java client, main | 20 | `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java` | `integrations/java/iceberg-1.5/openhouse-java-runtime/src/shared/main/java` | both Java runtimes |
| Java client, resource `CodecConfigurer.properties` | 1 | `.../iceberg-1.2/openhouse-java-runtime/src/main/resources` | `.../iceberg-1.5/openhouse-java-runtime/src/shared/main/resources` | both |
| Java client tests | 4 | `integrations/java/iceberg-1.2/openhouse-java-itest/src/test/java` | `integrations/java/iceberg-1.5/openhouse-java-itest/src/shared/test/java` | both Java itests |
| Spark tests both lines run | 39 of 49 | `integrations/spark/spark-3.1/openhouse-spark-itest/src/test/java` | `integrations/spark/spark-3.5/openhouse-spark-itest/src/shared/test/java` | both Spark itests |
| Spark jobs, main | 77 | `apps/spark/src/main/java` | `apps/spark-3.5/src/shared/main/java` | both apps projects |
| Spark jobs, tests | 36 | `apps/spark/src/test/java` | `apps/spark-3.5/src/shared/test/java` | both |

193 files, every one a 100% rename (`git diff -M100% --diff-filter=R` counts 193) [ran].

The rule, the same in all five pairs: `src/shared` in the newer project is what the old line also compiles.
The newer project's `src/main` and `src/test`, and the old project's, keep the meaning they had: files
only that project compiles. So a file added to `apps/spark-3.5/src/test/java` is still Spark 3.5-only.

Why `src/shared` and not the newer project's standard roots: four of the five newer projects already have
files of their own in those roots that the old line must not compile (35 Spark 3.5-only tests, 6 Java
1.5-only tests, 3 Java 1.5-only client classes, 2 Spark 3.5-only job tests). Mixing the shared files into
those roots would need an exclude list in the old project that every new 1.5-only file has to be added to.
One uniform, explicit root is the simpler rule while both lines exist. After V9e the directories can be
folded into the standard roots with `git mv` and by dropping the `srcDirs +=` lines; nothing requires it.

What stays in the old directories (47 files, all compiled only by the old line) [ran: `git rm` count]:
the six `build.gradle` files, the Spark 3.1 runtime (grammar, Scala, one Java file: the Spark 3.5 and 4.2
runtimes have their own copies), the Iceberg 1.2 `OpenHouseCatalog`, ten Spark 3.1-only tests and that
itest's own `dummy.token` test resource, and `apps/spark/src/main/resources/log4j2.properties`.

Build wiring: the five newer `build.gradle` files add `src/shared/...` with plain relative paths and no
longer branch on `-PicebergFork` or call `evaluationDependsOn` on an old project. The five old ones add
the newer project's `src/shared/...` by `rootProject.file(...)`.

Paths that followed the moved `dummy.token`: `.github/workflows/build-run-tests.yml` (integration test
step), `.github/workflows/dataloader-tests.yml`, `integrations/python/dataloader/README.md`. Both new
relative paths exist [ran: `test -f`]. The Docker compose step of those workflows was not run here.

## 2. The deletion proof [ran]

In a throwaway detached worktree (`/home/user/oh-v9c-proof`, never committed, never pushed) at the
branch head:

```
git worktree add --detach /home/user/oh-v9c-proof HEAD
cd /home/user/oh-v9c-proof
git rm -r apps/spark integrations/java/iceberg-1.2 integrations/spark/spark-3.1 \
    tables-test-fixtures/tables-test-fixtures-iceberg-1.2          # 47 files
# settings.gradle: removed the iceberg12LineProjects list, the includeUnlessForkExcluded closure and
# its six calls, and the two `if (!icebergFork) { project(...).name = ... }` blocks (27 lines)
cp -a /home/user/oh-rckfix/build/iceberg-fork/repo build/iceberg-fork/repo
./gradlew -PicebergFork --max-workers=2 -Dorg.gradle.daemon.idletimeout=60000 --no-build-cache \
    --rerun-tasks compileJava compileTestJava compileScala compileTestScala compileServiceJava
```

Result on e6b936fb plus the deletion: `BUILD SUCCESSFUL in 1m 17s`, 120 tasks executed, none from cache,
60 compile tasks, among them the five projects that own shared sources, `libs:datalayout`,
`services:tables` tests, both Spark 4.2 projects and the REST compatibility kit. The same on 826369a3
(before the merge of pull request 107): `BUILD SUCCESSFUL`, 109 executed. The patch is
`deletion-proof.patch` (48 files changed, 3654 deletions). No file outside the four deleted directories
and `settings.gradle` is touched.

The default build cannot work in that tree, and is not meant to: `./gradlew help` fails with "Project with
path ':tables-test-fixtures:tables-test-fixtures_2.12' could not be found in project ':services:tables'"
[ran]. See blockers 1 and 2 below.

## 3. Full suites

Both without the Gradle build cache (`--no-build-cache`), so nothing was restored from another run. No
Docker daemon runs in the container; a private one was started for the Testcontainers suites
(`dockerd` with its socket at `/tmp/v9cdk/sock`, `DOCKER_HOST` set) and stopped at the end.

### Default build [ran]

`./gradlew --max-workers=2 -Dorg.gradle.daemon.idletimeout=60000 clean build shadowJar --no-build-cache --continue`
on 826369a3 (the six commits): `BUILD SUCCESSFUL in 41m 5s`, 362 tasks executed, 0 from cache. This is
CI's `./gradlew clean build` plus the uber jars.

Tests / failed / skipped, from the JUnit XML files:

| Task | Result | Task | Result |
| --- | --- | --- | --- |
| `:services:tables:test` | 1536 / 0 / 0 | `:services:housetables:test` | 414 / 0 / 0 |
| `:iceberg:openhouse:internalcatalog:test` | 319 / 0 / 0 | `:iceberg:openhouse:htscatalog:test` | 20 / 0 / 0 |
| `:apps:openhouse-spark-apps_2.12:test` | 298 / 0 / 5 | `:apps:openhouse-spark-apps-1.5_2.12:test` | 304 / 0 / 5 |
| Spark 3.1 itest `catalogTest`, `statementTest`, `test` | 87 / 0 / 1, 126 / 0 / 0, 137 / 0 / 0 | Spark 3.5 itest `catalogTest`, `statementTest`, `test` | 269 / 0 / 1, 137 / 0 / 0, 124 / 0 / 0 |
| Java 1.2 itest | 90 / 0 / 0 | Java 1.5 itest | 136 / 0 / 0 |
| fixtures 1.2 `test` | 40 / 0 / 0 | fixtures 1.5 `test` | 40 / 0 / 0 |
| Spark 3.5 runtime `test` | 27 / 0 / 0 | capabilities `test` | 45 / 0 / 0 |
| `:libs:datalayout:test` | 17 / 0 / 0 | `:services:jobs:test`, `:services:common:test` | 52 / 0 / 0, 37 / 0 / 0 |
| optimizer `service`, `optimizer-common`, `scheduler` | 23, 24, 25 / 0 / 0 | optimizer `analyzer`, `binpack` | 59, 23 / 0 / 0 |
| `:client:secureclient:test`, `:cluster:storage:test` | 20, 11 / 0 / 0 | **Total** | **4440 / 0 / 12** |

After merging pull request 107 (head e6b936fb), on the default build:
`./gradlew ... --no-build-cache --continue spotlessCheck assemble testClasses :services:tables:test`,
`BUILD SUCCESSFUL in 8m 29s`, 193 tasks executed, `:services:tables:test` 1536 / 0 / 0. The other default
suites were not run again on the merge commit; pull request 107 changes one tables test class, a workflow,
a CI script and a document. `spotlessCheck` under `-PicebergFork` on e6b936fb: successful.

### Opt-in build [ran]

`./gradlew -PicebergFork ... build :integrations:spark:spark-4.2:openhouse-spark-4.2-itest:extensionsTest :integrations:java:openhouse-rest-compat-kit:restCompatKitTest --no-build-cache --continue`.
This is every task `build` runs under the switch plus the two extra tasks of the CI job
`iceberg-fork-build`; it covers that job's list and more (apps, fixtures, datalayout, housetables, jobs,
optimizer).

How it ran. The first invocation, on 826369a3, was killed after 9 minutes: the Gradle daemon was
OOM-killed by the shared memory limit while two other agents' builds ran (`dmesg`: "Memory cgroup out of
memory: Killed process ... (java)"). Its two orphaned test workers were stopped. Seven suites had already
finished in it. I then merged `origin/irc-integration` (pull request 107, which fixed a tables test that
was red under the switch on bfdcf266) and ran the rest in five invocations, one heavy suite at a time:
`assemble testClasses`; `:services:tables:test`; `:apps:openhouse-spark-apps-1.5_2.12:test`; the Spark
3.5 itest `test`; then the whole command above, in which Gradle found the already-run suites up to date.

| Task | Result | Ran on | Task | Result | Ran on |
| --- | --- | --- | --- | --- | --- |
| `:services:tables:test` | 1536 / 0 / 0 | e6b936fb | `:services:housetables:test` | 414 / 0 / 0 | 826369a3 |
| `internalcatalog` | 319 / 0 / 0 | e6b936fb | `htscatalog` | 20 / 0 / 0 | e6b936fb |
| `:apps:openhouse-spark-apps-1.5_2.12:test` | 304 / 0 / 5 | e6b936fb | `:libs:datalayout:test` | 17 / 0 / 0 | 826369a3 |
| Spark 3.5 itest `catalogTest`, `statementTest`, `test` | 269 / 0 / 1, 137 / 0 / 0, 124 / 0 / 0 | e6b936fb | Java 1.5 itest | 136 / 0 / 0 | e6b936fb |
| fixtures 1.5 `test` | 40 / 0 / 0 | e6b936fb | Spark 3.5 runtime `test` | 27 / 0 / 0 | e6b936fb |
| capabilities `test` | 45 / 0 / 0 | e6b936fb | Spark 4.2 runtime `test` | 163 / 0 / 0 | e6b936fb |
| Spark 4.2 itest `test`, `extensionsTest` | 38 / 0 / 0, 23 / 0 / 0 | e6b936fb | optimizer `analyzer`, `binpack`, `optimizer-common`, `scheduler` | 59, 23, 24, 25 / 0 / 0 | e6b936fb |
| `jobs`, `common`, `optimizer` service | 52, 37, 23 / 0 / 0 | 826369a3 | `secureclient`, `storage` | 20, 11 / 0 / 0 | 826369a3 |
| `restCompatKitTest` | task passes: 160 kit tests, 99 failed and 23 skipped, exactly the listed outcomes | e6b936fb | **Total without the kit** | **3886 / 0 / 6** | |

The suites marked 826369a3 ran before the merge; on e6b936fb Gradle reported them `UP-TO-DATE`, which it
only does when classes, classpath and configuration are unchanged.

One task of `build` fails under the switch: `:integrations:java:openhouse-rest-compat-kit:test`, "No tests
found for given includes". The module's `test` task excludes the kit classes and the module has no other
test. It fails the same way on `origin/irc-integration` at 3222cf78 [ran], it is not in the CI job's list,
and this change does not touch that module. Reported, not fixed.

## 4. Build outputs before and after

Method [ran]: on bfdcf266 and on 826369a3, for each build, `unzip -Z1 <jar> | sort` of every jar under
`build/*/libs`, the CRC of every jar entry, and the sha1 of every file under each project's
`classes/{java,scala}/{main,test}` and `resources/{main,test}`. Script: `snap.sh`; data: `snap/`. The
"after" side of both builds was compiled without the build cache.

| | Default build | Opt-in build |
| --- | --- | --- |
| Jars compared | 52 (570,867 entries) | 44 (110,055 entries) |
| Jars whose entry list differs | 0 | 0 |
| Class and resource directories compared | 81 (2,537 files) | 74 (2,230 files) |
| Directories with a differing file | 2, neither in a changed project | 1, not in a changed project |

Jars of the projects whose layout changed (entries before = after; entry-list diff empty for all):

| Jar | Default | Opt-in |
| --- | --- | --- |
| `openhouse-spark-apps_2.12.jar` / `-uber.jar` | 130 / 127,969 | not built |
| `openhouse-spark-apps-1.5_2.12.jar` / `-uber.jar` | 129 / 154,942 | 124 / see below |
| `openhouse-java-runtime.jar` / `-uber.jar` | 48 / 13,222 | not built |
| `openhouse-java-iceberg-1.5-runtime.jar` / `-uber.jar` | 61 / 13,235 | 59 / 13,233 |
| `tables-test-fixtures_2.12.jar` / `-uber.jar` | 20 / 75,562 | not built |
| `tables-test-fixtures-iceberg-1.5_2.12.jar` / `-uber.jar` | 20 / 75,891 | 20 / see below |
| the four itest jars (no main classes) | 4 each | 4 each |
| `openhouse-spark-runtime_2.12-uber.jar`, `openhouse-spark-3.5-runtime_2.12-uber.jar` (embed the Java client) | 13,343, 13,385 | not built, 13,383 |

Compiled test classes, identical by sha1 before and after: default build 47 (apps 1.2), 49 (apps 1.5), 9
and 17 (Java itests), 58 and 82 (Spark itests), 16 and 16 (fixtures); opt-in build 48, 16, 82, 15.

The byte differences that exist are all outside the moved sources:
- `PageJob.class` and `PageUserTable.class` of `:client:hts` (code generated from the OpenAPI spec), and
  the copies of them inside the uber jars; `META-INF/spring-configuration-metadata.json` of
  `services:tables`. Both projects are untouched; the baseline side came partly from the build cache and
  the after side was compiled fresh. Why these generated files differ between two builds was not
  determined.
- `META-INF/spring.factories` in the fixtures uber jars: it starts with a timestamp comment.
- Nested jars under `BOOT-INF/lib` of the Spring Boot jars (jar timestamps).

Under the switch the two uber jars of the Spark 3.5 apps and the 1.5 fixtures cannot be built, before or
after this change: see blocker 3.

## 5. What still blocks deleting the old projects and is not source reuse

For V9d and V9e. Not fixed here.

1. **`libs/datalayout/build.gradle` lines 8 to 14** [read]: on the default build it depends on
   `:integrations:spark:spark-3.1:openhouse-spark-runtime_2.12` and `:tables-test-fixtures:tables-test-fixtures_2.12`,
   and on `iceberg_1_2_version` and Spark 3.1.1. Only its `-PicebergFork` arm is free of the old projects.
2. **`services/tables/build.gradle` lines 126 to 128** [read, ran]: on the default build the tests depend on
   `:tables-test-fixtures:tables-test-fixtures_2.12`. This is the first failure of the default build in
   the proof tree.
3. **Uber jars under the switch** [ran, on bfdcf266 too]: `./gradlew -PicebergFork shadowJar` fails for
   `:apps:openhouse-spark-apps-1.5_2.12` and `:tables-test-fixtures:tables-test-fixtures-iceberg-1.5_2.12`
   with "Unsupported class file major version 65" in the shadow plugin's class remapper (7.1.2). Some
   dependency under the switch carries Java 21 class files (multi-release `META-INF/versions/21`);
   `parquet-jackson` 1.17.x and `jackson-core` 2.18 and later do [ran: jar scan], which one is on these
   classpaths was not determined. The jobs scheduler image needs the apps uber jar, so V9d meets this.
4. **Root `build.gradle`** [read, ran]: `dockerPrereqs` depends on
   `:integrations:spark:spark-3.1:openhouse-spark-runtime_2.12:shadowJar` (line 234) and
   `:apps:openhouse-spark-apps_2.12:shadowJar` (line 238); comments and messages at lines 210 to 217 and
   258 to 260. `dockerBuild` depends on it. In the proof tree it fails with "Task with path
   ':apps:openhouse-spark-apps_2.12:shadowJar' not found". Also `iceberg_1_2_version` (line 33) and
   `spark_version = "3.1.1"` (line 30, used only by the two Spark 3.1 projects), and
   `buildSrc/src/main/groovy/openhouse.iceberg-conventions-1.2.gradle`.
5. **Docker files** [read]: `jobs-scheduler.Dockerfile` line 6 (`APP_NAME=openhouse-spark-apps_2.12`, copies
   `build/openhouse-spark-apps_2.12/libs/*-uber.jar`);
   `infra/recipes/docker-compose/common/spark/spark-base-hadoop2.8.dockerfile` lines 86 and 87 (the Spark
   3.1 runtime and apps uber jars), used by three services in `common/spark-services.yml`;
   `spark-3.5-base-hadoop3.2.dockerfile` line 86 (copies the 1.2-line apps uber jar into the Spark 3.5
   image). The image-internal jar names `openhouse-spark-apps_2.12-latest-all.jar` and
   `openhouse-spark-runtime_2.12-latest-all.jar` are used by `oh-hadoop-spark/jobs.yaml` lines 20 and 22,
   `scripts/python/livy_cli.py` line 152, `integrations/python/dataloader/tests/integration_tests.py`
   line 40 and `infra/recipes/terraform/azure/environments/sandbox/scripts/spark-client.sh` line 14.
6. **CI** [read]: `build-run-tests.yml` runs the default build, which includes the old projects;
   `dataloader-tests.yml` builds the Spark images of point 5; `build-tag-publish.yml` publishes every
   module (line 56) and builds the jobs scheduler image from the 1.2-line apps jar (line 87).
7. **Published artifact names** [read]: `openhouse-spark-runtime_2.12`, `openhouse-spark-apps_2.12`,
   `tables-test-fixtures_2.12`, `openhouse-java-runtime`, `openhouse-java-itest`, `openhouse-spark-itest`
   stop being published; `all-modules` lists every published module in its POM, so that POM shrinks.
   Consumers outside this repository were not looked at.
8. **`settings.gradle`** [read]: the six entries and two rename blocks (the 27 lines the proof removed).
9. **Tests that exist only on the old line** [ran]: the ten Spark 3.1-only tests go with the project.
   Five have Spark 3.5 counterparts that run (`PartitionTestSpark3_5`, `DescribeTableTestSpark3_5`,
   `DropTableTestSpark3_5`, `ShowTablesTestSpark3_5`, `InsertIntoTableTestSpark3_5`). The other five
   counterparts are never compiled: the Spark 3.5 itest's patterns `**/AlterTable*.java` and
   `**/CreateTable*.java` also match `AlterTableTestSpark3_5`, `AlterTableSchemaTestSpark3_5`,
   `CreateTableTestSpark3_5`, `CreateTablePartitionedTestSpark3_5` and `CreateTableWithPropsTestSpark3_5`
   (no class file for them on bfdcf266 or now). After V9e those ALTER TABLE and CREATE TABLE tests would
   run nowhere unless the patterns are removed. Whether the five classes compile and pass was not checked.
10. **`apps/spark/src/main/resources/log4j2.properties`** [read]: only the 1.2-line apps jar packages it;
    the Spark 3.5 apps jar never did. If the scheduler image moves to the 3.5 apps jar, its logging
    configuration changes unless the file is carried over on purpose.
11. **Documents and comments** [read]: `ARCHITECTURE.md` lines 71 and 176 link `apps/spark`; `SETUP.md`
    lines 307 and 325; `docs/development/intellij-setup.md` line 21; `docs/development/renovate-iceberg-sync.md`
    line 8; `docs/development/iceberg-fork.md` line 49; comments in `IcebergRestRequirements`,
    `IcebergRestWriteController` and `IcebergRestBeanSignaturesTest` about the Iceberg 1.2 embedding;
    `apps/spark-3.5/build.gradle` line 37 (an exclude of `iceberg-spark-runtime-3.1_2.12`).

## 6. Left stale by this change, on purpose

- `integrations/python/dataloader/CLAUDE.md` lines 36 and 49 still name the old `dummy.token` path. I did
  not edit a `CLAUDE.md` file without the owner asking. The fix is to replace
  `tables-test-fixtures-iceberg-1.2/src/main/resources` with `tables-test-fixtures-iceberg-1.5/src/shared/main/resources`.
- The Javadoc of `OpenHouseViewSparkITest` (a moved test) still says it "physically lives in the spark-3.1
  itest source tree". The brief says a moved test's content stays identical.

## 7. For whoever merges other branches after this one

A branch that adds a new file under one of the old shared directories (for example
`apps/spark/src/main/java`) will still merge cleanly, but only the old project would compile it. Git
follows edits to the moved files; it does not move new files. Such a file belongs under the newer
project's `src/shared`.

## 8. Clean-up [ran]

The private Docker daemon is stopped and its images pruned, the proof worktree is removed, the two
orphaned test workers of the killed run were stopped, and no Java process has its working directory in
`/home/user/oh-v9c`. Two Gradle daemons started at 06:24 with `OLDPWD=/home/user/oh-v9c` and
`PWD=/home/user/openhouse` in their environment are not mine (none of my commands ran from
`/home/user/openhouse`, and mine carry `GRADLE_OPTS`); I left them alone.

## 9. Where I did not follow the brief

- The opt-in suite did not run as one command: the first run was OOM-killed, so it ran in steps (section
  3). Seven of its suites ran on 826369a3, the rest on e6b936fb.
- I merged `origin/irc-integration` (pull request 107) into the branch, so the head is a merge commit and
  the default full suite ran on the commit before it. The base moved again afterwards (pull request 108,
  Spark 4.2 test services only); that one is not merged in, and it merges without conflict
  (`git merge-tree`).
- I changed two workflow files and a README, which the brief lists as "do not fix here" kinds of files.
  They pass the moved `dummy.token` by path, so leaving them would have broken CI.
- I started and stopped a private Docker daemon, which the brief does not mention.
- The scratchpad path is too long for a Unix socket, so the daemon's socket is reached through the
  symlink `/tmp/v9cdk` (the data stays in the scratchpad).
- Baselines: the "before" outputs were built with the Gradle build cache on (the repository default), the
  "after" outputs without it.
