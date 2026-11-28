# C10: the Spark image builds on a CI runner; the oh-hadoop-spark recipe and the dataloader integration tests are green in CI: full record

Bottom line: done. The Spark image of the default build (Spark 3.5.2, Java 17, the fork's Iceberg 1.12 runtime,
Livy from the pinned commit) builds on a GitHub runner in six to seven minutes, the `oh-hadoop-spark` recipe
starts, and the dataloader's integration tests pass against it: three runs, three different commits, each green
at its first attempt. No test expectation was changed and no Spark SQL behaviour difference was found. No
version choice was changed. The integration job is back on pull requests and now also runs after each merge.

Written by a delegated agent on 2026-11-23. Marks: **[ran]** run for this record (CI run ids given),
**[read]** taken from code, configuration, a log or an earlier record.

- Branch `irc/c10-spark-image-ci`, worktree `/home/user/oh-c10`, base `440249d1` (pull request 118);
  `irc-integration` did not move while the work ran [ran: fetched before each push and at the end].
- Draft pull request 122 into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/122
- Head `f5aff0188452353bb46c201a732b2c1054e238a0`. Its checks [ran]: "Dataloader Integration Tests" success
  (run 45212160246, job 137823568817), "Dataloader Build and Test" success (job 137823570068),
  "build-run-tests / Build and Run Tests" success in 28:25 (run 45212160192, job 137823569052),
  "iceberg-linkedin-build / Reference build on LinkedIn's Iceberg 1.5.2" failure (job 137823569657: HTTP 429
  for the OpenAPI generator jar, section 3, not this change). On the first head, 2d383d11, both jobs of Pull
  Request Validations were green (run 45203965828: 25:07 and 22:26).
- Logs: `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/c10/logs/`
  (`run1-integration.log`, `run1-build.log`, `run2-integration.log`, `run3-integration.log`,
  `run3-reference-job.log`); what I read of Livy's and the plugin's sources:
  `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/c10/dl/`.

## 1. The five points

| Point | State | Evidence |
| --- | --- | --- |
| 1. Livy's build finishes without the archive host; the image builds in reasonable time | proven | [ran] runs 45203945887, 45208967049, 45212160246: `maven-download-plugin:wget skipped`, `BUILD SUCCESS`; step "Build the images" 6:04, 6:21, 6:37 |
| 2. The pinned Livy commit runs sessions on Spark 3.5.2 and Java 17 | proven for `kind: sql` sessions | [ran] the same runs: the tests' eight Spark SQL statements go through a Livy session; `/version` answers revision `4d8a912699683b973eee76d4e91447d769a0cb0d` |
| 3. `/opt/spark` from Apache's image is sufficient for the recipe | proven for what the recipe and the tests use | [ran] the same runs' listing and the tests (section 4) |
| 4. The recipe starts and the dataloader integration tests pass on the default line | proven | [ran] the same runs: 13 containers up, 15 of 15 checks pass, "All integration tests passed" |
| 5. The integration job on pull requests again; the post-merge question | done; decided yes | [ran] runs 45208967049 and 45212160246 were started by the pull request itself; the push trigger is not exercised yet (section 6) |

## 2. The three runs

Each run is a different commit; none is a re-run. Job "Dataloader Integration Tests".

| | Run 45203945887 | Run 45208967049 | Run 45212160246 |
| --- | --- | --- | --- |
| Head, event | 2d383d11, `workflow_dispatch` | 6c536fa4, `pull_request` | f5aff018, `pull_request` |
| Job id, result | 137794491006, success | 137812259889, success | 137823568817, success |
| Job, whole | 22:10 | 15:13 | 16:35 |
| Restore or publish the fork | 5:50 (cache miss: published in 5:48, saved) | 0:03 (cache hit) | 0:03 (cache hit) |
| `./gradlew shadowJar dockerPrereqs` | 6:17 (no Gradle cache) | 4:16 (Gradle cache of `build-and-run-tests` restored) | 4:23 |
| Build the images | 6:04 | 6:21 | 6:37 |
| of which the Livy stage (Maven's own total) | 3:27 (3:22) | 4:16 (4:10) | 4:34 (4:26) |
| Start the containers | 0:48 | 0:43 | 0:54 |
| Wait | 0:01 (the old step, which did not wait) | 0:50 (3 polls) | 1:07 (4 polls) |
| The tests (`make integration-tests`) | 2:16 | 2:02 | 2:21 |
| Checks passed | 15 of 15 | 15 of 15 | 15 of 15 |

The unit job "Dataloader Build and Test" (`make sync verify`) passed in each: 0:40, 0:36, 0:39.

Image build, cold and warm: every build on a runner is cold, nothing is kept between jobs. Cold: 6:04, 6:21,
6:37. Of the first [read: `run1-build.log`]: image pulls about 45 s, the Livy stage 207 s, the final stage's
layers about 35 s, exporting the three Spark images 84 s. There is no warm number because I added no layer
cache (section 7, decision 5).

## 3. Failures met, root causes, fixes

1. **Livy's build downloads a Spark release from archive.apache.org** (C9's failure 3, the reason the image
   never finished). Root cause: `thriftserver/server/pom.xml` at the pinned commit binds
   `download-maven-plugin:wget` (`download-spark-files`, phase `test-compile`) with no skip, so `-DskipTests`
   does not stop it [read: the pom at the commit]. Fix (2d383d11): `-Ddownload.plugin.skip=true` on both `mvn`
   lines. **C9's proposed `-Ddownload.skip=true` would not have worked**: the `plugin.xml` of the pinned
   plugin version 1.3.0 binds the `skip` parameter to `${download.plugin.skip}` [ran: read from the jar on
   Maven Central; 1.4.0 and 1.9.0 the same]. In CI: `[INFO] --- download:1.3.0:wget (download-spark-files) @
   livy-thriftserver ---` followed by `[INFO] maven-download-plugin:wget skipped` [ran, all three runs].
2. **The wait step never waited** (found in the first green run, not a red one). `curl -w "%{http_code}"`
   prints `000` for a refused connection and exits non-zero, so with `|| echo "000"` the value was `000000`,
   which is not `000`: `Poll 1: tables=000000 livy=000000`, `Services ready after 5s` with nothing listening
   [ran, run 45203945887]. The step is as old as the workflow [read: the file before 5d65dc46]. The tests
   passed because installing their packages and building their image (about 1.5 minutes) outlasted the
   services' start; that run's request for Livy's version printed nothing. Fix (6c536fa4): the step waits,
   up to 600 s, for the tables and house tables services' `/actuator/health` (200), Livy's `/sessions` (200),
   HDFS out of safe mode with one live datanode, and the Spark master with one alive worker. In CI: ready
   after 50 s and 67 s, the first poll showing the two services, Livy and the datanode not ready [ran, runs
   45208967049 and 45212160246].
3. **Not mine, on the final head**: "Reference build on LinkedIn's Iceberg 1.5.2" failed in 39 s at
   `:client:common:setUp`: `curl: (22) The requested URL returned error: 429` (the OpenAPI generator jar from
   Maven Central) [ran: job 137823569657 of run 45212160192]. My diff touches no Java or Gradle file. Not
   re-run, on the coordinator's instruction; branch `irc/c14-generator-jar-download` is fixing the download.

Nothing else failed. I cancelled no run by hand. GitHub's concurrency rule cancelled one superseded run of
mine (Pull Request Validations 45208967168 on 6c536fa4, when f5aff018 was pushed).

## 4. What the image holds and does (points 2 and 3)

From the job's listing [ran, runs 45208967049 and 45212160246]:

- Java: `openjdk version "17.0.21.1"` (Temurin). Spark: `version 3.5.2`, `Using Scala version 2.12.18`,
  `Spark 3.5.2 (git revision bb7846dd487) built for Hadoop 3.3.4`.
- `/opt/spark`: `R RELEASE bin data examples jars logs python sbin tests work-dir` and the two OpenHouse jars
  under the names `jobs.yaml` and the tests use (`openhouse-spark-apps_2.12-latest-all.jar` 345.8 MB,
  `openhouse-spark-runtime_2.12-latest-all.jar` 21.7 MB). No `conf` directory: the tarball's holds only
  templates, and nothing in the recipes reads one [ran: grep of the repository].
- Hadoop client: `hadoop-client-api-3.3.4.jar`, `hadoop-client-runtime-3.3.4.jar`; `fs.defaultFS` is
  `hdfs://namenode:9000` from the base image's `HADOOP_CONF_DIR`. Spark wrote the tables' data files to the
  recipe's HDFS 2.8 with it and the tests read them back with a Hadoop 2.8 client: no error.
- Iceberg: `iceberg-spark-runtime-3.5_2.12-1.12.0-ohfork.a88f556c5067.jar` in `/opt/spark/jars`.
- Python: none (`python`, `python3` not found); `/opt/spark/python` holds `lib` and `pyspark`. The Spark 3.1
  image had no interpreter either [read: its final stage copied only `/opt/livy` and `/opt/spark` onto the
  Hadoop image], so nothing changes for users; Livy `pyspark` sessions do not work in the image, as before.
- Livy: `/opt/livy/{bin,conf,jars,repl_2.11-jars,repl_2.12-jars,rsc-jars}`, `bin/livy-server` executable,
  jars `0.8.0-incubating-SNAPSHOT`; `GET /version`: `"revision":"4d8a912699683b973eee76d4e91447d769a0cb0d"`.
  Its log: `WARN utils.LivySparkUtils$: Current Spark (3,5) is not verified in Livy, please use it carefully`
  and `Spark version (3,5) is greater then the maximum version (3,0) supported by Livy, will choose Scala
  version 2.12 instead`. At the pinned commit that check only warns [read: `LivySparkUtils.scala`].
- Containers [ran, `docker compose ps -a`]: all 13 up (namenode and datanode healthy; the three Spark
  containers show "health: starting" for good, because they inherit the namenode image's health check, as the
  Spark 3.1 image did).
- Noise, not new: `/opt/spark/bin/load-spark-env.sh: line 68: ps: command not found` (the base image has no
  `ps`; the line is in Spark 3.5.2's own script [read], so the tarball has it too).

Not proven: a job submitted through the jobs service (a Livy batch from the apps jar; V9d ran the same app by
`spark-submit` locally); Livy `spark` (Scala) statements; the `default` builder stage, which got the same
Maven property but still needs archive.apache.org for Spark and Maven and was not built.

## 5. The dataloader tests, and behaviour differences

`integrations/python/dataloader/tests/integration_tests.py` is one script with 15 checks; it is unchanged. All
15 pass in each of the three runs, with identical output but for snapshot ids and timings [ran: `diff` of the
`PASS` and `SQL` lines of the three logs]:

1. nonexistent table raises `NoSuchTableError`; 2. empty table returns no splits and its custom property is
readable; 3. all 3 rows in 2 batches (`batch_size=2`); 4. row filter returns 2 rows; 5. column projection;
6. after a second insert, 4 rows; 7. DataLoader and Spark agree on 2 rows for ``WHERE `score` > 2.0 AND `name`
IN (...)``; 8. pinned to the first snapshot, 3 rows; 9. invalid snapshot id raises `ValueError`; 10. a split
materialises in a child process; 11. planner JVM arguments honoured (`MaxHeapSize=134217728`); 12. worker JVM
arguments honoured (`MaxHeapSize=266338304`); 13. day-partitioned table, all 3 rows; 14. datetime range filter,
2 rows from 2 splits; 15. tight datetime filter, 1 row from 1 split.

Spark SQL statements that ran through Livy on Spark 3.5.2 / Iceberg 1.12: `CREATE TABLE ... USING iceberg
TBLPROPERTIES (...)`, `INSERT INTO ... VALUES` (three times), `SELECT ... WHERE ... ORDER BY`, `CREATE TABLE
... PARTITIONED BY (days(ts))`, `DROP TABLE IF EXISTS` (twice).

**Behaviour differences against Spark 3.1 / Iceberg 1.2: none found.** Every expectation written for the old
line holds unchanged. (The suite's reach is those statements; it is no substitute for the Gradle suites.)

## 6. What each trigger runs now, and the minutes

| Trigger | Before | Now |
| --- | --- | --- |
| Pull request into `main` touching `integrations/python/dataloader/**` | unit job (0:40) | unit job and integration job |
| Pull request into `irc-integration` touching the dataloader | nothing | unit job and integration job |
| Pull request into either touching `infra/recipes/docker-compose/common/**`, `.../oh-hadoop-spark/**`, a root `*.Dockerfile`, `scripts/iceberg-fork/**`, `dataloader-tests.yml` or the fork action | nothing | unit job and integration job |
| Any other pull request | default build and reference build | the same; nothing added |
| Push to `irc-integration` | `post-merge-build.yml` | the same, and beside it a run of `dataloader-tests.yml` (both jobs), never cancelled |
| By hand | integration job and unit job | the same |

Added runner minutes: about 16 per selected pull request push (15:13 and 16:35 measured, plus 0:40 for the unit
job), and about the same per merge to `irc-integration`. Wall-clock of a pull request does not grow: the
default build takes 24 to 27 minutes beside it. Pull request 118's 43 to 46 minutes per pull request become
about 60 on the pull requests the paths select, and stay 43 to 46 on the others.

Not exercised: the `push` trigger (it can only fire on `irc-integration`): the first merge after this one
starts it. Same job, same commands as the two pull request runs; what differs is the concurrency setting
(`cancel-in-progress` false) and that the fork action may save the fork (as `post-merge-build.yml` does; if
both publish at once, the second save is refused with a warning and nothing fails).

## 7. Decisions for the orchestrator to record

1. **No version choice changed.** Livy stays the pinned commit `4d8a9126` with the repository's patch; Spark
   3.5.2 from Apache's image; the recipe's HDFS stays 2.8. The released Livy 0.8.0 was not needed.
2. **The skip property is `download.plugin.skip`**, not `download.skip` as C9's record and the dockerfile's
   comment proposed.
3. **Triggers.** Pull requests into `main` and `irc-integration` (before: `main` only; with `main` alone the
   job would never run on this fork's pull requests). Paths widened from the dataloader to the recipe, the
   images' sources, the fork pin and the workflow. The pin is included because publishing the fork costs
   about 6 minutes on a runner, not 60 to 75 as the guide said (decision 6), and a new pin is where the
   image's Spark behaviour can change.
4. **A Spark image build belongs after each merge: yes**, as a `push` trigger of the Dataloader workflow, not
   as a third job of `post-merge-build.yml`. Why: a change to the Spark runtime or the apps reaches the image
   on no pull request the paths select, and this is the only check that runs the deployed image (Livy, the
   Java 17 module opens, HDFS 2.8, the uber jars as shipped). Why its own run: the post-merge build's result
   and duration stay what they were. Cost: about 16 runner minutes per merge. To reverse: delete the three
   `push:` lines.
5. **No Docker layer cache.** Livy's stage is 3.5 to 4.5 of the 15 to 16 minutes. The runners' Docker uses
   the containerd image store [read: the build log's "exporting manifest list", "unpacking"], so a
   `type=gha` or `type=local` cache of that stage would probably work, at the price of a runtime-token step
   or an `actions/cache` entry and of space in the repository's 10 GB cache, which also holds the fork and
   the Gradle caches. Not worth it at this size; say so if you want it.
6. **The guide's fork publishing time is corrected** from "60 to 75 minutes on a runner" to "about 6": 5:48
   from nothing in run 45203945887 (119 tasks executed, no Gradle cache) [ran]; C9's run took 8.
7. **The wait step is stricter than the one it replaces** (which accepted anything): health 200, not any
   HTTP answer. If a service's health endpoint ever answers otherwise while the service works, the step
   fails after 600 s and prints every poll.

## 8. Deviations, side effects, things to know

- Wrong in a pushed commit message, not correctable without a force push: 2d383d11 says `download.skip` "is
  the property of later plugin versions". It is not a property of any version I read (1.3.0, 1.4.0, 1.9.0).
  The dockerfile and the guide are corrected in f5aff018, which says so.
- Run 45203945887 saved the fork under my branch's cache scope (a branch run by hand cannot read
  `irc-integration`'s). I cannot list or delete caches (the proxy refuses the cache API).
- `git push -u` made `origin/irc/c10-spark-image-ci` the branch's upstream (the brief's worktree command had
  set `origin/irc-integration`).
- Every commit and push held the shared lock; the stash stack was empty before and after each [ran]; no
  foreign entry was seen. Nothing was built with Docker or Gradle on this machine apart from the hooks'
  `spotlessCheck`.
- The check name "Dataloader Integration Tests (by hand)" becomes "Dataloader Integration Tests".
- CI time used: 15:19 to 16:32 UTC, three Dataloader runs and three Pull Request Validations runs (one
  cancelled by the next push).

## 9. Commits

| Commit | Why |
| --- | --- |
| 2d383d11 | Livy's build no longer downloads a Spark release from archive.apache.org (`-Ddownload.plugin.skip=true`); the image build is a step of its own with a 30 minute limit; the job prints more of what the image holds |
| 6c536fa4 | the wait step waits for what the tests use (it never waited); the integration job runs on pull requests and after merges again; the guide says what the image is known to do |
| f5aff018 | the guide's and the comments' durations as measured; the wrong sentence about the plugin's property removed |

## 10. What remains

1. Watch the first `push` run of `dataloader-tests.yml` after the merge.
2. A job through the jobs service (a Livy batch from the apps jar) in the recipe has never run on a runner.
3. The `default` builder stage is still unbuildable on a runner (archive.apache.org for Spark and Maven, 200
   Ubuntu packages); delete it or keep it for machines where those hosts are fast.
4. The Spark containers report "health: starting" for good (the inherited namenode health check).
5. `scripts/python/livy_cli.py` line 215 names `spark-examples_2.12-3.0.2.jar`; the image has Spark 3.5.2's.
