# C9: the Docker checks of the Iceberg 1.12 line in CI: full record

Bottom line: items 1 and 2 are in pull request CI and green. Items 3 and 4 are not green: the Spark image of
the 1.12 line has never finished building on a GitHub runner, so the `oh-hadoop-spark` recipe and the dataloader
tests have never started there. The job for them exists and runs by hand only (`workflow_dispatch`); no pull
request and no merge runs it. The default line's CI, images and compose configuration are unchanged.

Written by a delegated agent on 2026-11-23. Marks: **[ran]** run for this record (CI run ids given), **[read]**
taken from code, configuration, a log or an earlier record.

- Branch `irc/c9-docker-steps-1.12-ci`, worktree `/home/user/oh-c9`, base `b1dfe9b0` (pull request 113).
- Draft pull request 114 into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/114
- Head `71fd8365b875a589db86d95cb82fa0a2d96bf0cf`. It contains `irc-integration` at `c1303849` (pull requests
  115, 116, 117), merged in twice while the work was in CI; pull request 117's `smokeTest` line is kept.
- Logs: `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/c9/logs/`
  (`run1-forkjob.log`, `run2-forkjob.log`, `run4-forkjob.log`, `run1-dataloader.log`, `run2-dataloader.log`,
  `run4-dataloader.log`; the `*-dl-build.log` files are their Docker build parts).

## 1. The four items

| Item | State | Evidence |
| --- | --- | --- |
| 1. oh-only recipe from the `-PicebergFork` jars, with `integration_test.py` | green, in pull request CI | [ran] run 45160091707 (head), job 137642967882; also runs 45142667522 and 45151785544 |
| 2. Scheduler image from the Spark 3.5 apps jar | green, in pull request CI | the same three runs |
| 3. Spark image's builder stage on the 1.12 line (Livy from the pinned commit) | not green; by hand only | [ran] runs 45142667670, 45151785551, 45160094100, each cancelled by me inside the image build |
| 4. `oh-hadoop-spark` recipe and dataloader integration tests on the 1.12 line | not reached; by hand only | the same three runs: no container was ever started |

Item 1, from the head's log [ran, run 45160091707]: `Poll 4: tables=200 housetables=200`, `All tests passed
successfully`, and the tables container's jar holds `BOOT-INF/lib/iceberg-core-1.12.0-ohfork.a88f556c5067.jar`
and no other `iceberg-core` (the step fails otherwise), on the image's `openjdk version "23-ea"`.

Item 2, same log: the image is built through docker compose with `iceberg-fork.env`
(`oh-hadoop-spark-openhouse-jobs-scheduler`), `APP_NAME=openhouse-spark-apps-1.5_2.12`, and started without
arguments it prints `INFO  JobsScheduler:193 - Starting scheduler` before stopping on the missing options. The
INFO line needs the image's Java to run the jar's Java 17 classes and the jar to carry `log4j2.properties`.

## 2. What was built

- `.github/actions/iceberg-fork/action.yml` (new): the five steps that restore or publish the fork, moved
  unchanged out of `iceberg-fork-build.yml`; same cache key, same save rule. Both jobs use it.
- `iceberg-fork-build.yml`: after the suites and the compatibility kit, whatever their outcome, and not after a
  failed compile: `-PicebergFork shadowJar dockerPrereqs`; the scheduler image; the oh-only recipe, a wait until
  both services answer `/actuator/health` (16 s in each run; the default build sleeps 30 s), Python,
  `integration_test.py`, the `iceberg-core` check; the containers' logs when one of these steps failed; `down`
  always. The two steps that print and upload failed suites stay last, so a suite failure still ends the log.
- `dataloader-tests.yml`: an input `iceberg-fork` (`workflow_call`). With it: the fork action,
  `-PicebergFork shadowJar dockerPrereqs` in place of `clean build shadowJar -x test`, `--env-file
  iceberg-fork.env` on `up` and `down`, a step that prints what the Spark image holds, and
  `OH_SPARK_JARS_PACKAGES=` before `make integration-tests`. Its own concurrency group and job name suffix.
- `dataloader-tests-iceberg-fork.yml` (new): calls it with the input; trigger `workflow_dispatch` only.
- `spark-3.5-base-hadoop3.2.dockerfile`: build argument `OH_SPARK_BUILDER`, default `default`. `dockerhub`
  takes `/opt/livy` from a stage built in `maven:3.9.4-eclipse-temurin-11` (same Livy commit, patch, Maven and
  Java versions; no package installed; modules `integration-test` and `coverage` left out) and `/opt/spark` from
  `apache/spark:3.5.2-scala2.12-java17-python3-r-ubuntu`. `spark-services.yml` passes the argument when set;
  `iceberg-fork.env` sets it.
- `docs/development/iceberg-fork.md`: the Docker checks, the Dataloader job's input, the trigger to add later.

## 3. Failures met

1. **Spark tarball from archive.apache.org** [ran, run 45142667670]. The builder stage's
   `curl ... spark-3.5.2-bin-hadoop3.tgz` had 102 of 382 MB after 39 minutes: `26  382M   26  102M ...  46041
   ... 2:25:05`. Cancelled by me after 48 minutes in the step. From this machine the same host gave 7.8 MB in
   60 s [ran]. Cause: the host is rate-limited; the stage depends on it for 400 MB. First fix (commit
   a9d4723f): two arguments, Spark from Apache's Docker image and extra Maven arguments for Livy.
2. **apt-get in the builder stage** [ran, run 45151785551]. `#64 DONE 2497.6s`: 42 minutes for
   `apt-get install` of the stage's package list (about 200 packages with the PySpark scientific stack), many
   lines `Ign: ... http://archive.ubuntu.com/ubuntu ...` a minute apart; 8.7 minutes in the first run.
   Cancelled by me after 46 minutes, before Maven's own download from archive.apache.org had started. Cause:
   the stage installs packages it does not need (the final image copies only `/opt/livy` and `/opt/spark` out
   of it) from a host that timed out from the runner. Fix (commit c8c07771): `OH_SPARK_BUILDER=dockerhub`,
   which replaces the first fix's two arguments; the builder stage is byte for byte what it was at the base.
3. **Livy's thriftserver module downloads Spark too** [ran, run 45160094100, head]. With the `dockerhub`
   stages the image pulls took seconds and Livy built 14 of 22 modules in 3 minutes 15 seconds, then:
   `[INFO] --- download:1.3.0:wget (download-spark-files) @ livy-thriftserver ---` at 11:13:31, and nothing
   more until I cancelled at 11:42. That execution downloads `spark-3.1.1-bin-hadoop2.7.tgz` (228 MB) from
   archive.apache.org for tests that are skipped. I had left out `integration-test`, which has the same
   execution [read: Livy 0.7.1's pom on Maven Central], and did not know `thriftserver/server` has it too.
   **Not fixed.** Next step, not tried: add `-Ddownload.skip=true` to the `mvn` line of the `livy-dockerhub`
   stage (the plugin's skip property, as far as I know present in 1.3.0; if it is not, the stage needs
   another way to keep that execution from running). The `-pl` exclusion is then probably unnecessary.
4. **A green run with an error annotation** [ran, run 45142667522]: the scheduler's expected
   `MissingOptionException` was printed raw and the runner's Java matcher annotated it. Fix (36c96b02): the
   output is printed with a prefix; 0 error lines in the logs of runs 45151785544 and 45160091707.

Not a re-run anywhere: every run above is a different commit, and no job was re-run to get green.

## 4. Where items 3 and 4 stand, exactly

- Proven on a runner [ran, run 45160094100]: the fork is published or restored, `-PicebergFork shadowJar
  dockerPrereqs` passes (7.5 minutes in that run, 4.7 and 5.4 in the pull request runs), the service images build, the
  `maven` and `apache/spark` images pull, the pinned Livy commit clones, takes the patch and compiles through
  `livy-server` and `livy-thriftserver-session` on Java 11 with Maven 3.9.4.
- Not proven: that Livy's build finishes and its distribution unpacks with `jar xf`; that the pinned Livy,
  compiled against Spark 3.1.1, runs sessions on Spark 3.5.2 and Java 17; that `/opt/spark` from Apache's image
  is enough for the recipe (the comment in the dockerfile about what it lacks is from memory of that image's
  build, not from a listing); Spark 3.5 against HDFS 2.8; the dataloader tests. The job prints the image's
  contents and, after a failure, Livy's sessions, the executors' output and the containers' logs.
- The default builder stage cannot be used on a runner today on either line: it needs archive.apache.org for
  Spark (400 MB), Maven (9 MB) and, inside Livy's build, Spark 3.1.1 (228 MB, twice: `integration-test` and
  `thriftserver`). The default line's `spark-base-hadoop2.8.dockerfile` has the same stage [read]; its
  Dataloader job (pull requests into `main` that touch the dataloader) did not run here.

Wrong in the head because of failure 3, to be corrected with its fix: the guide's sentence "nothing comes from
those two hosts", the env file's "stages that use only Docker Hub, GitHub and Maven Central" and the dockerfile's
comment name only `integration-test` as the module that downloads Spark.

## 5. What each trigger runs now, and the minutes

| Trigger | Before | Now |
| --- | --- | --- |
| Every pull request (`pr-validations.yml`) | default build; opt-in job | the same two jobs; the opt-in job has the Docker steps, +3 min 20 s (3:18, 3:19, 3:19 in three runs) |
| Push to `irc-integration` (`post-merge-build.yml`) | the same two jobs | the same, with the same added steps (file not changed) |
| Pull request into `main` touching `integrations/python/dataloader/**` | Dataloader job, default line | the same commands [read]; one more step that only prints, after a failure |
| By hand, `dataloader-tests-iceberg-fork.yml` | did not exist | Dataloader job on the 1.12 line (never finished) |

Pull request wall-clock does not grow: the opt-in job took 15:16, 15:49 and 18:28 (the last with pull request
117's `smokeTest`), the default build 34:06, 30:06 and 32:38 beside it [ran, the three runs].

## 6. Proof that the default line is unchanged

- Files [ran: `git diff b1dfe9b0 HEAD`]: C9's commits change eight files (section 2). Not changed:
  `build-run-tests.yml`, `pr-validations.yml`, `post-merge-build.yml`, `build-tag-publish.yml`, every
  `*.Dockerfile`, `spark-base-hadoop2.8.dockerfile`, every Gradle file, every Python file and Makefile.
- Compose [ran, at the head]: `docker compose --profile '*' config` of the six recipes without the env file is
  byte-identical to the base's (119, 241, 620, 420, 366 and 139 lines).
- Spark 3.5 dockerfile [ran: diff]: against the base, 43 added lines and two changed ones,
  `COPY --from=builder` to `COPY --from=spark-builder`, where `spark-builder` is `FROM builder` when the
  argument is not given. Not built with default arguments (see section 4).
- Default build in CI [ran]: `build-run-tests` success on 5d65dc46 (run 45142667522), d661f6ff (45151785544)
  and the head (45160091707, job 137642968252).
- `dataloader-tests.yml` without the input [read, not run: nothing here triggers it]: `COMPOSE_ENV_FILE` is
  empty, so `docker compose $COMPOSE_ENV_FILE -f ...` is the old command; the build step is the old one; `run:`
  of the integration tests is `make integration-tests TOKEN_FILE=...` with no prefix; the concurrency group
  evaluates to `dataloader-<n>` and the job name to `Dataloader Build and Test`. `actionlint` 1.7.12 passes.

## 7. Decisions for the orchestrator to record

1. **The 1.12 line's Spark image takes Livy's build tools and Spark from Docker Hub**
   (`OH_SPARK_BUILDER=dockerhub` in `iceberg-fork.env`). It changes what a user of the env file builds: Spark
   3.5.2 from Apache's image of the release instead of the tarball, Livy built in Maven's image. The other
   choice was to set it only in CI and leave the env file alone; I chose the env file because the documented
   command otherwise takes hours wherever archive.apache.org is slow. One line to reverse.
2. **Items 3 and 4 run by hand only.** The trigger equal to the default line's (pull requests touching the
   dataloader), widened by the recipe and image files, is in the guide, ready to add once the job is green.
   I did not add the job to `post-merge-build.yml`.
3. **The oh-only wait is a poll of both services' health, not `sleep 30`**, and the opt-in job has two checks
   the default build does not have (the scheduler's first log line; the one `iceberg-core` in the container).
4. **The Docker steps run after the suites and the kit, even when those failed**, and not after a failed
   compile.

## 8. Deviations, side effects, things to know

- I cancelled three runs, all mine (45142667670, 45151785551, 45160094100), and one superseded run of mine
  (45159839173): a job's log is readable only once it ends. The head therefore shows one cancelled check,
  `dataloader-tests / Dataloader Build and Test (-PicebergFork)`, from the by-hand run; no pull request event
  creates that check.
- The by-hand run on the branch missed the fork cache (a branch reads only its own and the default branch's
  caches), published the fork in 8 minutes and saved a copy under the branch's scope. I could not list or
  delete it (the proxy refuses the cache API). It counts toward the repository's cache quota until evicted.
- Once, to compare compose configurations, I ran a bare `git stash` and `git stash pop` six times within a few
  seconds in my worktree (about 10:00 UTC), before the warning about the shared stash stack. My files came
  back as they were and the stack was empty afterwards [ran]. Every commit was made with nothing unstaged; the
  last commit and push predate the lock arrangement, and nothing was committed after it.
- The first merge commit's message names pull request 116 only, but it also brought in 115; the second names
  115 and 117 and brought in 117. The messages cannot be corrected without a force push.
- `workflow_dispatch` of `dataloader-tests-iceberg-fork.yml` works on a branch although the file is not on
  `main` [ran, run 45160094100].
- CI time used: 08:56 to 11:43 UTC.

## 9. What remains

1. Failure 3: keep Livy's `download-spark-files` execution from running, then see the image build finish.
2. Everything in "Not proven" of section 4, in order; then add the pull request trigger from the guide.
3. Correct the three sentences named in section 4.
4. The default line's Dataloader job has the same slow stage; it will not finish on a runner today either.
