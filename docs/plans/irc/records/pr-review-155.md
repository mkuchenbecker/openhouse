# Review of PR 155 (irc/ci-dynamic-version-resolution, head 7cbfdd61)

Reviewer: independent sub-agent, read-only. Diff read: `git diff origin/irc-integration...HEAD` (merge base 7cf347d7
= origin/irc-integration), 6 files: `.github/workflows/split-build.yml`, `.github/workflows/dataloader-tests.yml`,
`docs/development/iceberg-fork.md`, `scripts/ci/dependency-cache.gradle`, `scripts/ci/resolve-all-dependencies.gradle`,
`scripts/ci/list-downloaded-dependencies.sh`. Evidence read: author's record, PR body (REST API), the CI logs in
`scratchpad/cideps/pr/` and `scratchpad/cideps/logs/`, the probe outputs `probe-*-after/`, `m-*-after/`, `extras.py`.

## 1. Verdict

MERGE AFTER FIXES. Close F1 and F2 first.

The three things that matter most:
1. The remaining Spring Boot plugin-marker HEAD is not a hypothetical. In this PR's own run it got 429 three times in
   the WITH-script leg of the Reference `rest` job, and in the without-script leg it is what made json-smart and mysql
   fail: Gradle disabled the Central repository after the HEAD failed, and those two then failed without any request
   being made. So a reader job survives only when it needs nothing else from Central. The PR body and the record
   describe this the wrong way round, and the original incidents may have had the same trigger.
2. The writer's new step has never run (it runs only after a merge), it has no timeout, and it sits on the
   post-merge critical path. A hand-dispatched post-merge run on the branch would exercise it before the merge. The
   docs already describe that route.
3. Resolution is identical today, but only today. The one open range that can move is log4j-core `[2.17.1,3[`. CI
   then lags developers and the release build by up to the time since the writer's last lookup, and by up to 20
   days with no merge. After 20 days without a merge the readers go back to the network without any warning. Neither
   point is written where a maintainer will look.

## 2. Findings

### F1. MUST FIX BEFORE MERGE: the residual plugin-marker HEAD is misdescribed; it is observed, and it disables Central
- Evidence: job 140798103014 (Reference `rest`, PR run 46086770755, head f3048b4b), TEMPORARY step.
  - With the script:
    `3 Failed to get resource: HEAD. [HTTP HTTP/1.1 429 Too Many Requests: https://repo.maven.apache.org/maven2/org/springframework/boot/org.springframework.boot.gradle.plugin/2.7.8/org.springframework.boot.gradle.plugin-2.7.8.jar)]`,
    then `BUILD SUCCESSFUL`, with json-smart -> 2.3 / 2.4.7 and mysql -> 8.0.33 from the cache.
  - Without it: the same three HEAD 429s, then `mysql:mysql-connector-java:8.+ FAILED` and
    `net.minidev:json-smart:[1.3.1,2.3] FAILED`. That leg has no `maven-metadata` line and no other
    `repo.maven.apache.org` line, although the step's grep keeps both. So no version-list request was made: the
    two failed because the repository was already disabled.
  - The settings-level `pluginManagement` Central repository (settings.gradle:7-13) and the project repository
    (build.gradle:79-86) have the same URL and the same metadata sources (gradleMetadata, mavenPom, artifact).
    Gradle therefore treats them as one repository, and disabling one disables both. That explains why a plugin
    request takes dependency resolution down with it. This explanation is inferred from the log and the configuration.
- Consequences:
  - With this PR, a reader that gets the HEAD 429 still dies if it has to download anything afterwards in the
    same Gradle invocation. Today that is every job in the PR's table with a nonzero count (Ref 3.5-itest 4, Def
    3.5-itest 5, Def apps-4.2 3, Def Docker 2, Def apps-3.5 1) until the first post-merge writer warms the cache.
    It is also any PR that adds or bumps a dependency.
  - The original incidents (140769187919, 140788565624) print `Repository maven is disabled due to earlier error
    below:` with nothing below it. The logs cannot tell whether the first refused request was json-smart's list or
    this HEAD. The diagnosis "a dynamic-version lookup on every job" is only one of two explanations that fit.
    The fix covers both while downloads are zero, but the write-up should not claim more than that.
- What is written now:
  - Record: "A refused HEAD there could still kill a job (inferred; not observed)".
  - PR body: "In the leg without the script, this request got a 429 in this run", and "the leg without the script
    got 429 live, and json-smart and mysql FAILED. The leg with the script resolved them". This implies the 429
    was on json-smart's list request. It was on the HEAD, and in both legs.
  - Docs (iceberg-fork.md, the new paragraph) do not mention the remaining request at all.
- Sure: certain from the log lines. The repository-identity mechanism is likely.
- Fix:
  - Correct the PR body (verification bullet and "Deliberately left") and the record.
  - Add one sentence to `iceberg-fork.md` and to the header of `dependency-cache.gradle`: "One request remains per
    reader job (the HEAD for the Spring Boot plugin marker, cached as missing for 24 hours). A refusal there
    disables Central for the rest of that Gradle command, so a reader that must still download something can fail."
  - Optional mitigation as a follow-up, CI-only: give readers Gradle's internal retry and backoff properties, e.g.
    `-Dorg.gradle.internal.repository.max.retries` and `-Dorg.gradle.internal.repository.initial.backoff`, through
    `GRADLE_OPTS` in the read-only jobs. These are internal properties. Measure them before relying on them.

### F2. MUST FIX BEFORE MERGE: the writer step is unexercised and unguarded on the post-merge critical path
- split-build.yml:151-156:
  ```
  - name: Fetch every configuration's files into the Gradle cache
    if: ${{ !cancelled() && inputs.gradle-cache-write && matrix.group == 'rest' }}
    continue-on-error: true
  ```
- The step has no `timeout-minutes`, and the `tests` job has none either, so the job limit is GitHub's default of
  360 minutes. `continue-on-error` keeps the step out of the verdict, but it does not shorten the job.
  Post-merge confirmation waits for `rest`.
- The step has never run in CI. The author's record says "writer fetch only takes effect after merge". The local
  probe (same technique, `probe.gradle`) finished in 6-8 s on a warm cache with no ERR lines on either line, so a
  hang is unlikely. Network stalls on a cold or partly cold cache are the realistic case.
- "Can only be confirmed after the merge" is not true. `post-merge-build.yml` has `workflow_dispatch`, and
  iceberg-fork.md says the writer writes "in a branch's own scope when the workflow is run there by hand". Running it
  once on `irc/ci-dynamic-version-resolution` exercises the Fetch step on both lines and shows how long it takes.
  Running it a second time shows the readers' download lists at 0 from the branch-scoped entry. That settles both
  post-merge checks before the merge. The cost is about 1-2 GB of branch-scoped cache, which can push older entries
  out under the repository's 10 GB limit; the fork entry would then be rebuilt, about 6 minutes.
- Sure: certain that the step is unguarded and unexercised. A hang is unlikely.
- Fix: add `timeout-minutes: 10` to the step, and dispatch post-merge-build on the branch (twice, if the second
  check is wanted before the merge).

### F3. SHOULD FIX: the resolution lag is not written down, and "identical today" is a property of today
- What each dynamic version does under the 20-day setting. The selected versions come from the probe
  (`probe-*-after/*.tsv`, DYN lines). The release facts are reviewer knowledge, not checked against Central.
  - json-smart `[1.3.1,2.3]`: closed range whose top, 2.3, is long published. No new version can enter it, so lag
    cannot change the result. Immune.
  - `mysql:mysql-connector-java:8.+`: these coordinates were relocated to `com.mysql:mysql-connector-j`, and 8.0.33
    is the last release under them. Frozen in practice. Immune unless Oracle publishes under the old coordinates
    again.
  - logback-core `{strictly [1.2.9, 2[}`: resolves to 1.2.11 although newer 1.2.x releases exist, so another fixed
    candidate in the graph decides (likely the Spring Boot 2.7 dependency management). The version list does not
    decide today.
  - log4j-core `{strictly [2.17.1, 3[}`: open range. It resolves to 2.25.3 in most configurations and to 2.25.4 in
    two default-line configurations (`apps:openhouse-spark-apps-4.2_2.13` runtimeClasspath and
    testRuntimeClasspath). Wherever no fixed candidate pins it, the list decides. A new 2.x release (for example a
    security fix) reaches developers, the writer and LinkedIn's release build at once. It reaches PR jobs only after
    the next writer run whose own lookup is more than 24 hours old.
- The asymmetry the other way round: a breaking log4j release is first seen by the post-merge writer (`rest` red),
  while the PR and the post-merge reader jobs stay green. At most one red writer run per such release, because the
  writer then saves the new list. That asymmetry already existed within 24 hours before this PR. Now it lasts up to
  20 days.
- "Today every range resolves the same either way" is accurate for today. The PR body says so, but the script and
  the docs do not. They say "Nothing else about resolution changes", which a maintainer will read as "no
  difference".
- Why it can wait: CI-only, the release build is untouched, and today nothing differs.
- Fix: one sentence in `dependency-cache.gradle` and in iceberg-fork.md: "A release inside an open range
  (log4j-core today) reaches these jobs only after the next merge's `rest` refreshes the list; until then CI tests
  the previous one."

### F4. SHOULD FIX: past 20 days the readers silently return to the old failure mode
- After 20 days without a merge, every reader goes back to listing versions from Central, and no step says so.
  The writer only refreshes when it runs, after a merge, and only lookups that are more than 24 hours old.
  GitHub's 7-day eviction does not apply, because readers keep the entry in use.
- The script explains the 24-day ceiling (dependency-cache.gradle:21-22). It does not say what happens past 20 days,
  or why it chose 20 and not 24. The margin is presumably the writer's own up-to-24-hour lag.
- Sure: certain from reading.
- Fix: write the 20-day horizon in the docs. Optionally, have `list-downloaded-dependencies.sh` also print the age of
  the restored entry, or warn when `"$GRADLE_USER_HOME/caches/modules-2/metadata-*"` holds nothing newer than
  19 days. A scheduled refresh cannot simply be a `schedule:` trigger, because scheduled runs use the default
  branch, which is `main` here.

### F5. SHOULD FIX: `ciResolveDependencies` hides what it could not resolve
- resolve-all-dependencies.gradle:17-20:
  ```
  count += configuration.incoming.artifactView { lenient = true }.files.files.size()
  ...
  logger.lifecycle("${project.path}: ${count} files")
  ```
- A lenient view drops every unresolved dependency and missing artifact without a trace. The only output is a file
  count per project. Add to that `continue-on-error`, and the step can fetch less, or nothing, forever without
  anyone noticing. The readers' download list would show the effect, but not the cause.
- Why it can wait: it only affects cache warmth, not any verdict.
- Fix: print `configuration.incoming.artifactView { lenient = true }.artifacts.failures` (one line per failure,
  with the configuration name). Make post-merge check 1 read the step's conclusion and those lines, not just grep
  for the task name (see F6).

### F6. SHOULD FIX: post-merge check 1 cannot fail
- "`grep -A3 "ciResolveDependencies"`" matches the echoed command line even when the step failed or resolved
  nothing.
- Fix: `gh api repos/mkuchenbecker/openhouse/actions/jobs/<id> --jq '.steps[] | select(.name|startswith("Fetch")) | .conclusion'`,
  plus a check of the per-project lines and of the failure lines from F5.
- Check 2 is sound, but only on a PR run that started after the writer's save. It should also grep for
  `429` / `Failed to get resource` (F1).

### F7. MINOR: the cache-read-only condition is written twice
- split-build.yml:109 and 120 both spell out `!(inputs.gradle-cache-write && matrix.group == 'rest')`. If one is
  edited without the other, the 20-day script can land in the writer. That would not leak into the cache (see §5),
  but the writer would stop refreshing lists, and every reader after it would then freeze until a lookup exceeds 20
  days.
- Fix: a job-level `env: CACHE_READ_ONLY: ...`, used by both.

### F8. MINOR: the documentation is incomplete in places
- The `dependency-cache.gradle` header lists only the split build's jobs ("applies to every Gradle command of the job
  and to nothing outside CI"). The Dataloader job copies it too.
- In the PR table, the rows "Ref apps-4.2 0" and "Ref 4.2-itest 0" are vacuous: on the reference line those groups
  have no project. The log of 140798103029 says `CI group: no project of this build is in [apps/spark-4.2]; nothing
  to run`.
- The A/B evidence was taken in that empty job. It still proves the mechanism, because the probe tasks ran there.
  "Evidence from apps-spark-4.2" should not be read as evidence about that group's build.
- The Prepare steps use a bare `$GRADLE_USER_HOME`, while the list script falls back to `$HOME/.gradle`. This is
  harmless today, because setup-gradle exports the variable (logged `GRADLE_USER_HOME: /home/runner/.gradle`). If
  the step is moved above setup-gradle it would `mkdir /init.d` and fail loudly.

## 3. Behavior changes

| Change | Where | Toward/away from base CI | Written down |
|---|---|---|---|
| Read-only split-build jobs (PR: all; post-merge: all but `rest`; Docker) resolve dynamic versions from restored lists up to 20 days old | split-build.yml:118-126, 224-228; dependency-cache.gradle | away (CI only) | yes. Lag (F3) and 20-day horizon (F4) missing |
| Dataloader integration job does the same off `main` (the repo's default branch), including on irc-integration pushes | dataloader-tests.yml:99-103 | away (CI only) | yes |
| Writer `rest` (both lines, post-merge only) runs `ciResolveDependencies` after the build: more files in the saved entry, a longer job | split-build.yml:151-156 | away (CI only) | yes. Never run (F2) |
| Every split-build job prints the files downloaded beyond the restore | split-build.yml:161-163, 242-244 | additive, print only | yes |
| Product build, local build, build-run-tests.yml / build-tag-publish.yml | none | none | correct |

Differences from the author's list:
- The author's list omits that the residual HEAD disables Central for the rest of the invocation (F1).
- It omits the 20-day horizon (F4).
- It does not say that the writer step is untested before the merge. The author says it can only be checked after.

## 4. Tests

No test classes changed (CI-only). How strong is each piece of evidence?
- Local mechanism probe (record, `mech/`): sound. With Central unreachable, a lookup older than the duration gave
  FAILED and one within it gave 2.3. That is the right shape.
- The CI A/B in run 46086770755: real, and the init.d route is proven, because the step moved the file out of
  `$GRADLE_USER_HOME/init.d` and back.
  - I counted `maven-metadata` lines per job log. Each read-only job has 3: the echoed command plus the 2 downloads
    of the without leg. The with leg has 0. Reference `rest` has 1, the echo only (see F1 for why).
  - The A/B did not isolate the json-smart request as the refused one (F1).
- The resolution-identity probe (every resolvable configuration, with and without the script, both lines):
  identical. It cannot fail for today's reason, because the cache was fresh when it ran. It shows that the script
  changes no selection rule, not that the lists cannot age (F3).
- Not pinned anywhere: the writer step's behavior and duration (F2), and the readers' zero downloads after warming.

## 5. Checked and found sound
- No leak into the saved cache.
  - The writer never copies the file (the Prepare condition matches `cache-read-only`).
  - setup-gradle saves only `caches`, `notifications` and `.setup-gradle`. The Dataloader log shows
    `gradle-home-cache-includes: caches notifications` and the restore paths `/home/runner/.gradle/caches,
    ...notifications, ....setup-gradle`. `init.d` is not cached.
  - The file is written after setup-gradle's restore, in a later step.
- Release workflows untouched.
  - `build-tag-publish.yml` runs on `main` pushes and calls `build-run-tests.yml`. That file copies nothing and runs
    in `main`'s cache scope, which cannot see the irc-integration entries. The key `build-and-run-tests` is shared by
    name only.
  - No composite action was changed.
- Security.
  - `pr-validations.yml` is `pull_request` (not `_target`). Every job has `contents: read`.
  - No secrets or tokens were added. The writer step runs only on push to irc-integration or by hand.
  - Quoting in `list-downloaded-dependencies.sh` is correct. It prints only `group:module:version file` and always
    exits 0. `grep -c . || true` gives 0 on an empty list.
- 20 versus 30 days.
  - 2^31 ms = 24.86 days, which matches the measured 24-days-works / 25-fails boundary.
  - The reason is stated in the script (lines 21-22).
  - I did not read Gradle's source to confirm the int path.
- The 65-hour age is confirmed.
  - Both failing jobs log `Cache hit for restore-key: ...iceberg-linkedin-build[db78f2e3...]-81b4cd4d...`.
  - The writer 138992000868 restored, then saved, under that key ("Caching Gradle state" at 2026-11-25T05:18).
  - The failures were at 2026-11-27 22:37 and 23:40, 65-66 hours later.
  - The lists inside the entry could be up to 24 hours older still.
- Other causes considered.
  - The project repositories are Central only (build.gradle:79-86).
  - The fork's local repository is `exclusiveContent` for the fork group and version only
    (gradle/iceberg-fork.gradle:45-60), so it plays no part in the ranges.
  - The plugin portal is second in `pluginManagement` and the buildscript.
  - No build file sets `cacheDynamicVersionsFor`, `cacheChangingModulesFor` or dependency locking.
- `ciResolveDependencies` resolves only `canBeResolved` configurations, through artifact views with no attribute
  changes, so it starts no transforms beyond the build's own. File collections of project dependencies are not
  built. The probe using the same technique ran on both lines with no errors.
  - Cache growth: per `extras.py` over `m-*-after`, all configurations add about 4 files over `rest` (default
    1044 vs 1040 files, both about 984 MB uncompressed; reference 818 vs 814). The observed reader downloads are
    about 15 files, tens of MB. That is negligible against the roughly 0.6 GB entry and the 10 GB repository limit.
  - It runs on both lines (`LINE_FLAG`).
- The Dataloader change is needed (same `shadowJar`, same ranges) and safe. Its condition is the same as
  setup-gradle's own default for `cache-read-only`. The repository's default branch is `main` (REST API), so on
  irc-integration pushes it is a reader, which is correct.
- `buildSrc` and included builds: init.d scripts apply to every build in the tree, so the effect there is the same.
  It is harmless.
- Private ids in the added files: none (grep for D/RF/FF/BC/PR numbers).

## 6. Not checked
- Gradle 8.14's source for the int-milliseconds path, and for the claim that the plugin-marker artifact is cached as
  missing for a fixed 24 hours (`cacheMissingArtifactsFor`).
- Whether 2.25.4 is the newest log4j-core 2.x on Central, that is, whether the list decides the apps-4.2 selection
  today.
- The duration and outcome of the writer step in CI (it has never run).
- The reproduction runs in `mech/`: I read the record's summary, not every log.

## Re-check at 14adc5c1

Read: `git diff 7cbfdd61..14adc5c1 -- .github scripts/ci docs/development`, the updated record, the PR body, and
the evidence in `cideps/pr3/`, `cideps/disp/`, `cideps/r2/` and `cideps/mech2/`.
Changed CI files: `split-build.yml`, `dataloader-tests.yml`, the scripts and the docs. The release workflows
`build-run-tests.yml` and `build-tag-publish.yml` are untouched, and so are the composite actions.

**Verdict: MERGE.**

### F1: closed
- **The causal chain is now stated correctly.** It is in the script header, the docs and the PR body: a refused
  request disables Central for the rest of the Gradle command, and the record carries a correction.
- **(a) The Spring Boot plugin request is mapped to the plugin module directly.**
  - The rule is in `dependency-cache.gradle`:
    `beforeSettings { settings.pluginManagement.resolutionStrategy.eachPlugin { if (requested.id.id == 'org.springframework.boot' && requested.version != null) useModule("org.springframework.boot:spring-boot-gradle-plugin:${requested.version}") } }`.
  - It uses the requested version, so a pull request that bumps the plugin is still tested at its new version.
  - A request without a version falls through to normal resolution.
  - The rule is confined to readers: it lives in the file that only `prepare-gradle-home.sh reader` copies into
    `init.d`, and `init.d` is not saved by setup-gradle (caches, notifications and .setup-gradle only; checked in
    the first round).
  - Same implementation: in `r2/*-be-before.txt` the marker's only child is
    `org.springframework.boot:spring-boot-gradle-plugin:2.7.8`. I normalized the module coordinates of the six
    buildEnvironment before/after pairs (three projects on each line); they are identical except for the marker
    node.
  - In the cold dispatched reader 140823748421, per the record, the marker module is never requested.
- **(b) Every job now gets more retries for a refused request.**
  - `prepare-gradle-home.sh` appends (`>>`, so it does not overwrite) two `systemProp.org.gradle.internal.repository.*`
    lines to `$GRADLE_USER_HOME/gradle.properties`.
  - It does this in the writer as well. It is not saved: the file sits at the user-home root, which is not among
    the saved paths.
  - The repository's `gradle.properties` sets only jvmargs, parallel, caching and mysqlTestVersion, so there is no
    key collision. (The user-home file would win on a collision.)
  - Bound: 7 tries with waits of 2+4+8+16+32+64 = 126 s for each request that keeps failing. After that the
    repository is disabled and the remaining requests in that command fail at once.
  - Requests that are already running retry at the same time as each other, not one after another. So a command
    stalls about 2 minutes per repository before it fails, not 2 minutes per request. This bound is inferred from
    the disable-on-exhaustion behavior.
  - The 134 s it took the fork publish in the dispatched run to fail is consistent with 7 tries.
  - Acceptable.

### F2: closed for the guards; the Default line's fetch is still unverified
- The guards are in place: `timeout-minutes: 10`, and the fetch now runs only when the build ran
  (`steps.suites.outcome != 'skipped'`).
- The Reference writer's fetch was exercised in dispatch run 46094839702 (per the record): 4 s, 29570 files,
  0 failures.
- On GitHub's cache scoping the author is right: a `pull_request` run can restore only its merge ref's, its base
  branch's and the default branch's entries, never the head branch's.
  - My suggestion was a second hand-dispatched run on the branch, not a pull request run. That would have read the
    branch's own scope.
  - For the Default line it could not have helped anyway, because the Default writer never got past publishing the
    fork from a cold cache.
- Merging with the Default fetch unverified is acceptable. It cannot change a verdict (`continue-on-error`), and it
  cannot hold the run open for more than 10 minutes.
- **What a bad outcome looks like:**
  - The Default `rest` Fetch step's log shows `BUILD FAILED`, or `could not resolve:` lines, or the step ends at
    its 10-minute limit. Its conclusion still reads success.
  - The next pull request's Default jobs still list the same downloads as now: 3.5-itest 5, apps-4.2 3, apps-3.5 1,
    Docker 2.
  - If the fetch command fails, setup-gradle skips its cleanup, which only applies on success. The saved entry is
    then larger, but it is not harmful.
- It is noticed only by running the three checks in the docs and the PR body; nothing turns red.

### F3: closed, and my premise was wrong
- Alone, the ranges resolve to 2.26.1 (log4j-core) and 1.6.8 (logback-core): see `mech2/build.gradle`, a
  standalone project with both ranges only, and the record.
- The build selects 2.25.3, 2.25.4 and 1.2.11, below those tops. So a fixed version elsewhere in the graph decides
  them, not the version list.
- That leaves the lists deciding only json-smart (a closed range that cannot move) and mysql `8.+` (relocated, so
  no new releases).
- The trade is now written in the script and the docs, including that a configuration where such a range stands
  alone would lag. Accurate.

### F4 to F8: closed
- **F4.** The age of the restored cache is printed, with a `::warning::` from 19 days.
  - It works: in `pr3`, "newest file from 01:45 / 01:42 UTC" against a restore at 01:47 shows that mtimes survive
    the restore.
  - It can understate the age of the version lists by up to a day, because the writer may save lists it did not
    refresh. The 1-day margin under 20 covers that.
- **F5.** Each failure is printed, plus a `<files> files, <failures> failures` line per project.
- **F6.** The checks now read the Fetch step's start and end times and its own output, and grep for `429` and
  `Failed to get resource`.
- **F7.** The writer condition is a single job-level `GRADLE_CACHE_WRITER`.
- **F8.** The script header now names the Dataloader job.
- **The `incrementalScalaAnalysisFor*` exclusion is fine.** Only the Scala tasks resolve those configurations, with
  their own attributes, and they hold the same modules as the source set's classpath, which is still fetched.

### New in this round: no blocker
- **`prepare-gradle-home.sh`:**
  - It runs `set -eu`, and the first write is `touch "$RUNNER_TEMP/..."`. Run outside Actions, where `RUNNER_TEMP`
    is unset, it exits on the unbound variable before writing anything to a Gradle home.
  - It is invoked only from `split-build.yml` and `dataloader-tests.yml`.
  - Quoting is correct.
- **Dataloader on `main`** (the repository's default branch): the job is a writer there and gets only the retry
  properties. That workflow belongs to the fork, not to LinkedIn's release workflows.
- **MINOR:** the docs' "at most about two minutes before Central is disabled" is per repository per command, not
  per job. A job that runs several Gradle commands can wait that long in each one.

### Watch in the first post-merge run on irc-integration
1. Both `rest` jobs' Fetch step: duration, `BUILD SUCCESSFUL`, and every project line at `0 failures`. The Default
   line is the one not yet seen.
2. No `status code 429` or `Failed to get resource` in any job.
3. The first pull request run that starts after the writers saved: every job at
   `beyond the restored Gradle cache: 0`, and `Restored Gradle cache: 0 days`.
