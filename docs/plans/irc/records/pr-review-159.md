# Review of pull request 159 (irc/ci-warm-up-default-line, head b5ec038d)

Reviewer: independent sub-agent, read only. Diff: `git -C /home/user/oh-ci-deps2 diff origin/irc-integration...HEAD`,
one commit (b5ec038d), three files: `scripts/ci/resolve-all-dependencies.gradle` (+27/-3),
`.github/workflows/split-build.yml` (+20/-1), `docs/development/iceberg-fork.md` (+15/-5). CI only; no product
source, no test, no build file touched.

## 1. Verdict

**MERGE AS IS.** No MUST FIX. Four MINOR findings; they can follow in a later pull request or be left.

The three things that matter most:
1. The diagnosis holds against the logs. The first writer (job 140839247995) had its fetch refused: it stalled
   02:50:34 to 02:52:11 and printed 52 `could not resolve:` lines. The originally refused request was the root
   project's `otelAgent` jar download, which was printed first and is the one line without "Repository maven is
   disabled". The cache was saved at 02:52:30, after the fetch. The second writer (140852687277) fetched in 5 s
   with 0 failures. Its job downloaded 29 files, among them every file the earlier pull request jobs downloaded.
   This pull request's run restored that writer's entry (`gradle-dependencies-v1-6124aae7`, saved by
   140852687277 at 04:04:30), and all 11 jobs I read downloaded 0 files, with no 429.
2. The retry cannot turn a red result green. The step has `continue-on-error: true` and `|| true` on every Gradle
   call, has no `id`, and no later step reads its outcome. The verdict is still the `suites` step's. The retry
   only re-runs a cache warm-up after the build and tests have finished.
3. The new code path has never run in CI. Writers run only after a merge, so the 0-download result of this pull
   request comes from the warmed entry, not from the change (the author says so). Locally, the author ran the
   shell logic against a mock (`cideps/fetchtest.sh`) and the count file on a passing build (`f-def.txt` and
   `f-ref.txt` = 0). I see no real-Gradle run of the non-zero count or `build failed` paths, and the mocked
   `fetchstep.sh` is an older text without `|| true`. The first post-merge writer with a refusal is the real
   test. I found nothing in the code that would make it fail.

## 2. Findings

### F1. MINOR: the doc's diagnostic grep also prints the step's own script line
- `docs/development/iceberg-fork.md` (new command): `grep -E 'Fetch left [0-9a-z ]+ failures|Fetch failures left: |could not resolve:'`.
- GitHub prints the step's `run:` script at the head of the step log (seen in 140839247995 lines 2597-2598), so
  the line `echo "Fetch failures left: $failures"` matches `Fetch failures left: ` on every writer log. The
  doc's next sentence ("prints `Fetch failures left: 0` when the fetch completed") is then true, but there is
  always one extra line. `Fetch left $failures` does not match, because of `$`.
- Certain from reading. Fix: anchor the pattern to the output, for example `Z Fetch failures left: `, or add
  `| grep -v echo`.
- In the same command, `cut -c1-300` can cut off the new `(cause: ...)` part of a long failure line, which is the
  part the change adds. A wider cut, or none, would keep it.

### F2. MINOR: a timeout during the second attempt leaves no `Fetch failures left` line and no warning
- `.github/workflows/split-build.yml`: the step's `timeout-minutes: 10` covers both attempts together, plus the
  60 s sleep (this is correct and enforced).
- If the first attempt stalls for most of the 10 minutes, or the second one does, the runner kills the step
  before the `echo`/`::warning::` lines. What is left is the step's own timeout error, which `continue-on-error`
  turns into a successful conclusion. The incomplete cache is then saved without the warning that this change
  promises.
- The observed stall was about 2 minutes, so both attempts fit (about 2 + 1 + 2 minutes). Likely harmless.
- Fix if wanted: run each attempt under `timeout 270 ./gradlew ...`, so the reporting lines always run.

### F3. MINOR: the warning wording when the count is not a number
- When the count file holds `build failed` or `unknown`, the step prints "Fetch left build failed failures" and
  "The writer's fetch left build failed resolution failures". The logic is right: anything other than `0` is
  retried and warned about. Only the text is odd.
- Certain from reading. Fix: `case "$failures" in ''|*[!0-9]*) msg="did not complete ($failures)";; esac`, or leave
  it.

### F4. MINOR: `gradle.buildFinished` is a legacy listener API
- `scripts/ci/resolve-all-dependencies.gradle:29`: `gradle.buildFinished { result -> ... }`.
- It works on Gradle 8.14.5 without the configuration cache, which the project does not enable
  (`gradle.properties` has no such setting). It is not supported with the configuration cache, and it is the kind
  of API Gradle 9 removes. The build already prints "Deprecated Gradle features were used ... incompatible with
  Gradle 9.0" (`warm2-ref.log` line 177), so a new deprecation, if there is one, would go unnoticed.
- Cannot tell without `--warning-mode all`. It does not break anything today. Worth a note for the eventual
  Gradle 9 move: a `doLast` on a final task, or `BuildService` plus `OperationCompletionListener`, would replace
  it.

No other findings.

## 3. Behavior changes

| Change | Where it acts | Product or developer build affected? |
|---|---|---|
| Failure lines append ` (cause: <innermost cause's first line>)` when a cause exists | Only when `ciResolveDependencies` runs, which only the writer's fetch step does, with `-I` | No |
| Count file written when `CI_RESOLVE_FAILURES` is set and the build is the root build | Same | No: the variable is set only in that step |
| The writer's fetch step runs the task a second time after 60 s when the count is not `0` (including `unknown` and `build failed`) | Post-merge writer job (`rest`, both lines) | No; the step is outside the verdict |
| `::warning::` annotation when failures remain | Same | No |

This matches the author's account. The author's record has no explicit "behavior changes" table, but its
description of the change covers all four rows. Nothing is missing.

## 4. Tests

- No tests are added; it is a CI script. The evidence is the PR run, and that run does not exercise the new path
  (verdict, point 3).
- Unpinned claims, until a real refusal happens on a writer:
  - the cause printed for a "disabled" failure is the earlier refused request with its URL and status (likely:
    Gradle chains the earlier error as the cause);
  - a new Gradle command in the same daemon starts with Central enabled again (likely: repository disabling is
    scoped to one build invocation).
- One way to settle both cheaply: a manual writer-style run with a deliberately unreachable repository URL. That
  is not required for merge.

## 5. Checked and found sound

**Logs, first writer (140839247995):**
- The main build passed at 02:50:01. The fetch started at 02:50:02.
- The root-level projects printed at 02:50:04. Nothing more until 02:52:11.
- Then 52 `could not resolve:` lines. 32 of them are `Could not resolve org.jacoco:org.jacoco.ant:0.8.13.`; the
  others are `Could not download ... Repository maven is disabled due to earlier error below`.
- `BUILD SUCCESSFUL in 2m 10s`, and "downloaded beyond the restored Gradle cache: 0".
- Saves at 02:52:24-02:52:38, with the dependencies entry `f63b6130` saved at 02:52:30. The save follows the
  fetch.

**Logs, second writer (140852687277):**
- It restored `f63b6130`, the first writer's entry.
- Fetch from 04:04:05 to `BUILD SUCCESSFUL in 5s` at 04:04:10, with 0 `could not resolve` lines.
- 29 downloads: the jackson-jaxrs base, json-provider and jaxb-annotations jars, the opentelemetry agent,
  parquet-jackson, the junit-platform launcher, runner, suite-api and suite-commons jars, jacoco, asm and slf4j.
  These include every file in the earlier pull request jobs' download lists in `cideps/pr157`.
- It saved `6124aae7` at 04:04:30.
- The 29 is the job's total; the step is not itemised. The first writer's main build downloaded none of these, so
  attributing them to the fetch is reasonable.

**The author's refutation of "the warm-up misses configurations":**
- Sound. The same script with the same configurations resolved every one of those files, with 0 failures, once
  Central did not refuse.
- Variant selection is not the cause: the jacoco failures disappeared in the second run.

**This pull request's run on b5ec038d:**
- I read all 11 jobs that ran: 6 default, 5 reference; the reference Docker job is skipped by design.
- Every one prints "downloaded beyond the restored Gradle cache: 0" and has 0 `status code 429`.
- The default jobs restored a cache with newest file 04:04 UTC and the dependencies entry `6124aae7`. The
  reference jobs restored their own line's entry (03:59 UTC).
- This includes apps-spark-4.2 (140860549853) and spark-3.5 itest (140860549827), which downloaded files before.
- The claim "0 downloads in every job" is true.

**Shell:**
- The default `bash -e`. `fetch` ends in `|| true`, so a non-zero Gradle exit does not stop the step.
- `$(cat ... 2>/dev/null || echo unknown)` handles a missing file. `$(...)` strips the newline, so `0\n`
  compares equal to `0`.
- `[ "$failures" != 0 ]` is a string comparison: `unknown` and `build failed` are retried and warned about.
- `$LINE_FLAG` is deliberately unquoted (it may be empty); the other expansions are quoted.
- If the second command dies before the init script runs, the file still holds the first attempt's non-zero
  value, so the step still warns.

**The second attempt cannot make the cache worse:**
- It only adds files. Gradle downloads to a temporary file and then moves it into place.
- setup-gradle's post step runs `gradle --stop` before it saves (140852687277, after line 12468), so no daemon is
  writing while the cache is saved.
- A step timeout was already possible before this change.

**The project's rule against re-running to get green:** within it. The retry targets a refused download in a
step that is not part of any verdict. It re-runs no test and no job, and it cannot change the conclusion of the
run it is in. Its only effect is a warmer cache for later runs, which is the purpose of pull request 155.

**The Gradle script:**
- `firstLine` handles a null message (it uses the class name) and an empty message (it prints an empty string).
- A failure without a cause prints no `(cause: ...)`.
- The loop stops on a self-cause. A two-step cause cycle would loop forever, but the JDK and Gradle do not build
  those; ignorable.
- An `AtomicInteger` is correct under `org.gradle.parallel=true`.
- `gradle.parent == null` keeps buildSrc, whose parent is the root build, from writing the file. There is no
  `includeBuild` in `settings.gradle`.
- `unknown` is written first and replaced only when the build finishes, so a build that failed at configuration
  is never read as `0`.

**Secrets:** the resolution repositories are anonymous (`repo.maven.apache.org`, `plugins.gradle.org`, and the
local file repository of the Iceberg fork). The only credentials in the build are the JFrog publishing
repository's (`buildSrc/.../openhouse.maven-publish.gradle`), which resolution never uses. A printed cause is a
public URL and a status.

**Outside CI:** the script runs only with `-I` in that step, and `CI_RESOLVE_FAILURES` is set only there. A
developer build and the release build are unchanged.

**Documentation:**
- It correctly describes the one-command disabling, the second try, and the warning text, which matches the code
  (`The writer's fetch left N resolution failures`).
- No private ids (no pull request, work item or decision ids) appear in the three files; `<rest job id>` and
  `<run id>` are placeholders.

**Scope:** only the three files, and all of the change serves the purpose.

**What remains (assignment point 5):**
- The trigger, Central refusing a request during the writer's fetch, is not removed. It is retried once and
  reported. That is an acceptable end state: a fetch that still fails costs only what was paid before pull
  request 155 (the readers download the missing files) until the next writer, and it is now visible.
- The refused request in this incident was a fixed-version jar download (the opentelemetry agent 2.12.0), not a
  dynamic-version lookup. So the writer's lookups older than 24 hours were not the exposure here.
- Lengthening the writer's lookup periods was rejected for a good reason (review of 155, F7): the writer would
  stop refreshing the lists that every reader freezes on. It would not have prevented this incident either.
- A better end state, if refusals recur:
  - longer back-off between Gradle's own retries in the writer only (prepare-gradle-home already raises the
    count);
  - or keep the previous entry's files when the fetch fails. setup-gradle already keeps what was restored, so
    that is the status quo.
  - Nothing further is needed now.

## 6. Not checked

- I did not run Gradle, so neither the cause text a real "disabled" failure prints nor whether the API in F4 adds
  a deprecation warning.
- I did not confirm that the repository is enabled again in a new command in the same daemon; it is likely, from
  how Gradle scopes it.
- I did not verify the reference line's writer logs, or the earlier pull request's job logs on GitHub (I used the
  author's saved copies in `cideps/pr157` for the list of earlier downloads).
- I did not check whether a `::warning::` from a job of a called workflow shows on the caller's run summary. By
  GitHub's usual behaviour it does.
