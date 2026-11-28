# Test iteration friction: diagnosis (2026-11-22, read-only, no builds run)

[M] = measured (source named), [E] = estimated. Scratchpad = /tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad

## 1. Verdict
Yes, but for memory and test selection, not for cold builds: Gradle is about 90% of a validation task's wall time [M: s42/ logs sum 216 of 241 min, all/ 177 of 197 min], almost all of it test execution with nothing recompiled, and roughly 20 to 35% of that is avoidable (out-of-memory kills and reruns, fixed sleeps inside ten tests, idle daemons forcing the two-build cap) [E].

## 2. Top three sources of friction
1. **Memory, made worse by idle and oversized Gradle daemons.** The shared cgroup is at its limit: 13.2 to 13.67 of 13.67 GiB in use, failcnt 25.9M, `oom_kill` went 9 -> 10 while I watched [M: memory.oom_control, dmesg]. Kill 10 at about 18:02 was the oh-i6c daemon (pid 19319, `-Xmx4g`, 5.45 GB anon RSS) about 9 minutes into `i6c/final-def-test.log`; that agent has already restarted. At the same moment four daemons with no build activity held about 4.8 GB (pids 17593 1.9 GB and 12 h old, 23798 1.75 GB, 13774 0.6 GB on JDK 21, 6625 0.55 GB) [M: ps RSS; idleness inferred from CPU time]. Cause: three incompatible daemon flavours (brief's `-Xmx4g`, repo default `-Xmx2g -XX:MaxMetaspaceSize=512m` used by the git hooks, JDK 21), each idling for the default 3 hours. 311 daemon JVMs were started in 15.5 h, 236 of them single-use [M: ~/.gradle/daemon logs, saved build logs]. Size: 10 kills at 10 to 25 min lost each is 100 to 250 min [E], plus the cap itself.
2. **Long runs are test time, and a large slice is fixed sleeping.** Builds of 10 min or more are 519 of 1,058 logged Gradle minutes, typically "4 executed, 51 up-to-date" [M: 338 BUILD lines]. In OpenHouse, ten tests sleep through Iceberg's metadata-refresh retry: 2 x 92 s in TablesServiceTest (3.1 of the 7 min `:services:tables:test`), 4 x 92 s in OpenHouseInternalTableOperationsTest plus 68 s and 60 s in HouseTableRepositoryImplTest (8.3 of about 12 min of internalcatalog suite time) [M: test-result XML in oh-v2 and oh-i6c-base]. No test task sets `maxParallelForks`, so each module runs in one fork [M: build.gradle]. Failing-test runs cost 256 min across 80 builds, each a full-suite run to find the failures [M].
3. **CI is one serial uncached job.** 22 successful runs took 24.9 to 40.1 min, median 28.4; `./gradlew clean build` is 24.0 of 25.9 min and 33.3 of 35.0 min in the two runs I opened [M: Actions runs 45037943046, 45044471304]. "Setup Gradle" takes 0 to 1 s, so no dependency or build cache is restored; the only cache-writing trigger is push to `main` [M: step times, build-tag-publish.yml; "nothing restored" is inferred]. CI runs off this container and in parallel across PRs (three started 15:16 to 15:22), so it costs wait time only. 5 of the last 30 runs were cancelled by a newer push.

## Corrections to the orchestrator's observations
- A fresh worktree is **not** a 35 minute cold build. That figure is the report's estimate for empty Gradle caches. With the shared `~/.gradle` (build cache 2.6 GB, modules 2.2 GB) a fresh worktree costs about 1 min: `:services:tables:test` 7m58s fresh (15 tasks FROM-CACHE) against 7m01s warm; Spark 3.5 itests 9m59s against 9m01s [M: base-tables-test.log, v2-tables-test2.log, base-spark.log, v2-spark.log].
- HTTP 429 is small locally: 5 failed builds, about 7 min, plus 60 s sleeps [M]. It matters more in CI, which downloads everything each run.
- The harness `-Xmx3g` is `HARNESS_JVM_ARGS` for the harness JVM, not the Gradle daemon [M: reports/harness-via-seam.md]. CI passes the full build on the repo default 2g daemon [M].
- `:services:tables:test` is steady at 7m01s to 7m58s over four runs, including with a second build running; the run "cut off at 30 minutes" has a daemon-disappeared kill in its log (i6/oh-test-tables.log) [M].
- CPU is not the binding limit: pressure "some" averages 10% over 5 min, 28% over 10 s with two builds [M: /proc/pressure/cpu]. Disk is fine: 38 GB free, worktrees about 14 GB, `~/.gradle` about 6 GB [M].
- Same task list varies 10m18s to 18m54s between runs (i6b/final-def-test.log, i6c/base-def-test.log) [M]; cause not isolated.

## 3. Changes ranked by payoff against effort and risk
Free (flags or briefing habits):
1. Add `-Dorg.gradle.daemon.idletimeout=60000` to every briefed Gradle command. Frees up to about 4.8 GB of idle daemon memory, which is the margin the kills are happening in [E]. Cost: about 17 s daemon start on the next invocation [M: one no-op build]. Do not use `./gradlew --stop`; it stops other agents' busy daemons.
2. For OpenHouse, stop overriding the daemon heap (use the repo's 2g plus 512m metaspace). Caps each daemon near 2.7 GB instead of 5.4 GB and makes agent and hook daemons compatible [E]. Risk: a task that really needs more fails with a Java heap error; CI shows the default build does not.
3. Inner loop runs only the classes touched (`--tests`, already used 71 times in scripts) and leaves the full suite to CI. Avoids about 10 min of sleeping per full local OpenHouse pass [M for the sleep time]. Risk: a regression shows up 28 min later in CI instead of locally.
4. Budget concurrency by memory, not by count: an Iceberg Spark run is a 3160m test fork plus the daemon, so never two of those together; two OpenHouse runs fit once 1 and 2 are in place [E].
5. Hooks: they cannot fail today and start a default-flavour daemon per commit and push. Either accept that or have agents run `spotlessCheck` in their own flagged invocation and commit with `--no-verify`; owner's call.

Needs a repo or environment change:
6. More memory for the container (or a second container). Directly lifts the two-build cap; cores then become the limit at three builds [E].
7. CI: let PR runs into `irc-integration` write the setup-gradle cache, or add a push-triggered build on that branch. Removes most Maven Central traffic (the 30 s 429 failure) and saves roughly 3 to 6 min per run [E]. Does not change what the default build resolves or tests. Risk: 10 GB cache quota churn.
8. Shorten the retry backoff in the ten sleeping tests through a test-only setting. Saves about 11 min of serial test time per full pass [M for the time]. It keeps the assertions but edits default tests, so it needs the owner's agreement.
9. Not now: splitting CI into parallel jobs, `maxParallelForks`, configuration cache. Larger changes, each with memory or flakiness risk.

## 4. Not determined without running a build
- Why an OpenHouse daemon that only orchestrates tests grows to 5.4 GB under `-Xmx4g` (live data or uncollected garbage), so item 2 is unproven locally.
- Fixed per-invocation overhead for Iceberg `--no-daemon` runs (one 17 s data point only).
- How much of the CI Gradle step is tests against compile and download (step-level timing only).
First measurement: one OpenHouse `:services:tables:test` run on the repo-default daemon settings with `--profile`, sampling the daemon with `jstat -gc` every 30 s, to confirm it finishes under 2g and to get Gradle's own configuration/compile/test split.
