# Test iteration friction: diagnosis

Delegated agent, 2026-11-22, read-only, no builds run. Marks: [M] measured, [E] estimated.
## Verdict
Yes, but for memory and test selection, not for cold builds. Gradle is about 90% of a validation task's
wall time [M: logs sum 216 of 241 min and 177 of 197 min], almost all of it test execution with nothing
recompiled. Roughly 20 to 35% of that is avoidable: out-of-memory kills and reruns, fixed sleeps inside
ten tests, and idle daemons that force the two-build cap [E].

## The three sources
1. **Memory, made worse by idle and oversized Gradle daemons.**
   - The shared memory limit is saturated: 13.2 to 13.67 of 13.67 GiB in use, `oom_kill` went from 9 to 10
     during the observation [M: memory.oom_control, dmesg].
   - Kill 10 hit a build daemon (`-Xmx4g`, 5.45 GB resident) about 9 minutes into a run [M]. At that
     moment four daemons with no build activity held about 4.8 GB [M: resident size; idleness inferred].
   - Three incompatible daemon flavours exist (the brief's `-Xmx4g`, the repo default `-Xmx2g` with 512m
     metaspace used by the git hooks, and JDK 21), each idling for the default 3 hours. 311 daemon JVMs
     started in 15.5 hours, 236 of them single-use [M].
   - Size: 10 kills at 10 to 25 minutes lost each is 100 to 250 minutes [E], plus the cap itself.
2. **Long runs are test time, and a large slice is fixed sleeping.**
   - Builds of 10 minutes or more are 519 of 1,058 logged Gradle minutes, typically "4 executed, 51
     up-to-date" [M: 338 build lines].
   - Ten OpenHouse tests sleep through Iceberg's metadata-refresh retry: 2 x 92 s in `TablesServiceTest`
     (3.1 of the 7 minutes of `:services:tables:test`), and 4 x 92 s plus 68 s and 60 s in two other
     classes (8.3 of about 12 minutes of internal catalog suite time) [M: test result files].
   - No test task sets `maxParallelForks`, so each module runs in one fork [M].
   - Runs that failed cost 256 minutes across 80 builds, each a full-suite run to find the failures [M].
3. **CI is one serial uncached job.**
   - 22 successful runs took 24.9 to 40.1 minutes, median 28.4. `./gradlew clean build` is 24.0 of 25.9 and
     33.3 of 35.0 minutes in the two runs opened [M: Actions runs 45037943046, 45044471304].
   - "Setup Gradle" takes 0 to 1 s, so nothing is restored; only a push to `main` writes the cache [M: step
     times and workflow file; "nothing restored" is inferred].
   - CI runs off this container and in parallel across pull requests, so it costs wait time only. 5 of the
     last 30 runs were cancelled by a newer push.

## Corrections to the orchestrator's observations

- A fresh worktree is not a 35 minute cold build; that figure was an estimate for empty caches. With the
  shared Gradle home a fresh worktree costs about one minute: `:services:tables:test` 7m58s fresh against
  7m01s warm, Spark 3.5 itests 9m59s against 9m01s [M].
- HTTP 429 is small locally: 5 failed builds, about 7 minutes, plus 60 s sleeps [M]. It matters more in CI.
- The harness `-Xmx3g` is for the harness JVM, not the Gradle daemon [M]. CI uses the repo default 2g.
- `:services:tables:test` is steady at 7m01s to 7m58s over four runs. The run "cut off at 30 minutes" has
  a daemon-disappeared kill in its log [M].
- CPU is not the binding limit: pressure averages 10% over 5 minutes and 28% over 10 s with two builds [M].
  Disk is fine: 38 GB free [M].
- The same task list varies from 10m18s to 18m54s between runs; the cause was not isolated [M].

## Changes ranked by payoff against effort and risk

Free, by flags or briefing habits:
1. Add `-Dorg.gradle.daemon.idletimeout=60000` to every briefed Gradle command. It frees up to about 4.8 GB
   of idle daemon memory, the margin the kills happen in [E]. It costs about 17 s per daemon start [M].
   Do not use `./gradlew --stop`; it stops other agents' busy daemons.
2. For OpenHouse, stop overriding the daemon heap. That caps each daemon near 2.7 GB instead of 5.4 GB
   and makes agent and hook daemons compatible [E]. Risk: a task that needs more fails with a heap error.
3. Run only the touched classes in the inner loop and leave the full suite to the end. It avoids about 10
   minutes of sleeping per full local pass [M]. Risk: a regression shows up later.
4. Budget concurrency by memory, not by count. An Iceberg Spark run is a 3160m test fork plus the daemon,
   so never two together; two OpenHouse runs fit once 1 and 2 are in place [E].
5. Hooks cannot fail today and start a default-flavour daemon per commit and push. Accept that, or run
   `spotlessCheck` in a flagged invocation and commit with `--no-verify`; the owner's call.

Need a repository or environment change:
6. More memory, or a second container. It lifts the two-build cap directly [E].
7. CI: let pull requests into `irc-integration` write the Gradle cache, or add a push-triggered build on
   that branch. It saves roughly 3 to 6 minutes per run and most Maven Central traffic [E].
8. Shorten the retry backoff in the ten sleeping tests through a test-only setting. It saves about 11
   minutes per full pass [M]. It edits default tests, so it needs the owner's agreement.
9. Not now: parallel CI jobs, `maxParallelForks`, configuration cache (memory or flakiness risk).

## Not determined without running a build

- Why an OpenHouse daemon that only orchestrates tests grows to 5.4 GB under `-Xmx4g`, so item 2 is
  unproven locally.
- The fixed per-invocation overhead of Iceberg `--no-daemon` runs (one 17 s data point).
- How much of the CI Gradle step is tests, compile and download (step-level timing only).
- First measurement: one `:services:tables:test` run on the repo-default daemon settings with `--profile`,
  sampling the daemon with `jstat -gc` every 30 s.
