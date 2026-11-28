# Re-check of pull request 166 at `e5da0456`

Head: `e5da0456cdbe039ee57cd0db34069c09b09796fe`. Base: `origin/irc-integration` at `35cc7b1e`, which is also the merge
base. New commits since my review: `1ae1657f` (Jackson withdrawn, pin tests), `5edf2778` (partition job),
`2810a4cd` (`!cancelled()`), `a8fd7676` (the minors), and `e5da0456` (merge of the base).

## Verdict: MERGE

Every MUST FIX (F1 to F3) is closed, because the Jackson alignment is gone. F4 lapses with it. F5, F6 and F8 to F12
are closed. F7 is closed by documentation only, which is acceptable; it can follow. Nothing is open that must be
fixed before merging.

## 1. The Jackson withdrawal is complete

- `git diff origin/irc-integration...HEAD -- build.gradle` has exactly one changed line:
  `def icebergFork = !hasProperty('icebergLinkedIn')` became `!gradle.ext.icebergLinkedIn`. The forces block
  (`jackson-bom` and `jackson-databind` at 2.13.4, and the ORC, Guava and Avro forces) is byte-identical to the base.
  `jacksonVersion` and `ext.icebergForkJacksonVersion` are gone. No other build file in the diff mentions Jackson,
  apart from the two new test wirings.
- Nothing else in the diff can change what either line resolves:
  - `exclusiveContent` for the fork group at the fork version is the base's block, unchanged.
  - The new `afterResolve` hook (`gradle/iceberg-fork.gradle:150-189`) can only throw, after resolution. It never
    changes a selected version or the order in which repositories are tried. It is installed only when a marker is
    missing.
  - The four removed marker checks only threw at configuration time; they had no influence on resolution.
  - The boolean line property gives the same line as the base for every value the build, the workflows, the scripts
    and the delta harness actually pass: no value means reference, absent means default. Only `=false`, `=no-value`
    spellings in `gradle.properties` and refused values behave differently, and nothing passes those.
- `JacksonRuntimeVersionsTest` (services/tables) now pins exact lists. The reference list is main's. The default
  list is main's with jackson-core 2.22.2. A second test checks the test JVM's own core and databind.
  - Either assertion fails if any module moves, appears or disappears, on either line, which is the right reason.
  - The comments are now true. I checked them against my earlier jar checks: databind 2.13.4 and the other modules
    link cleanly against core 2.22.2, and no Jackson module compares versions at run time. Iceberg 1.12's metadata
    asks for 2.22.2 of the BOM, core and databind.
  - One sentence overstates: "Changing any of these versions changes answers". Not every version change would. It
    is harmless.
- `JacksonShadedVersionsTest` (fixtures): the fixtures' `test` task receives the Jackson modules of the fixture
  project's `runtimeClasspath`.
  - Shadow 7.1.2's `shadowJar` is built from `runtimeClasspath` by default. Neither the fixture build nor
    `openhouse.tables-test-fixtures-common.gradle` sets `configurations =`, and the `shadowJar` excludes there do not
    touch Jackson. So it pins what is shaded.
  - The lists are identical to the service's.
  - The test sits in `src/shared/test/java`, which the fixture project adds to its `test` sources.
- The "task input" wiring, in both projects: `inputs.property('jacksonRuntime', provider)` resolves
  `runtimeClasspath` when the task's inputs are fingerprinted. So with `org.gradle.caching=true`, a changed classpath
  changes the cache key and the test re-runs. The system property is set in `doFirst` from the same provider.
  Sound.

## 2. F5: the partition check

- It is now a separate job `partition` with no `needs`. The aggregate has `needs: [plan, partition, tests, docker]`
  (`split-build.yml:92`), and the verdict script fails any result other than success. The self-test has the case
  "the partition red", so a red partition job makes the aggregate red and it still gates a merge.
- The plan job is back to the base's steps: 8 s on CI (11:23:01 to 11:23:09). The partition jobs take 63 s
  (default) and 51 s (reference) beside the groups, so nothing waits for them.
- Running it without the fork: the default-line partition job has no fork step in its log (0 runs of
  `iceberg-fork`). It reported "build: 781 tasks, 44 test tasks; partition holds", the same counts the plan job
  gave with the fork restored at `06682b5e`.
  - Reasoning: a missing fork module cannot add or remove tasks. Only project dependencies contribute task edges,
    and the fork's modules depend on no OpenHouse project. So the check is sound without the fork.
  - `check-test-groups.py` captures Gradle's output, so the missing-fork warning does not show in the log. That is
    harmless.

## 3. F6: `if: ${{ !cancelled() }}`

What GitHub documents, to my knowledge:
- `cancelled()` is true only when the workflow run was cancelled.
- A job skipped by its `if` reports success, and does not block a merge even when it is a required check. This is
  in the Actions docs on job conditions.
- For a required check, only a success, neutral or skipped conclusion passes, so a cancelled one blocks.

Case by case:
- All green: the aggregate runs and is green.
- One needed job failed: the run is not cancelled, so the aggregate runs, sees `failure` and is red.
- One job cancelled on its own (a time limit) while the run continues: the aggregate runs. Its result is
  `cancelled` or `failure`, and either is red. The self-test has this case.
- A job skipped because an earlier one failed: red, as only the Docker job on the reference line may be skipped.
- The whole run cancelled by a newer push: the aggregate does not run and ends `cancelled`. Observed on `2810a4cd`:
  both aggregates `completed cancelled`, not skipped. That blocks the old SHA, but branch protection reads the
  head's checks, and the head's run is the newer one.
- A manual cancel of the head's run: the same mechanism, so `cancelled`, which blocks until someone re-runs it. I
  have not observed this case; it is inference.

Could the check ever be skipped, and so pass, when it should be red? Only if GitHub marked a not-run aggregate as
`skipped` instead of `cancelled` in a cancelled run. The observed run shows `cancelled`. With `always()` this risk
did not exist, but superseded commits showed a red check.

Could the check be absent? It is absent when the caller job is skipped on an `edited` event without a base change.
Then the earlier run's check on the same SHA stands, as before this pull request.

So I found no case in which a pull request merges when it should not.

## 4. The minors

- F10: closed.
  - The hook now also refuses a fork module that resolves while its set's marker is missing (`allComponents` matched
    against the missing sets). So a partly published set fails with the instruction.
  - Can a complete, correct fork be refused? Only if its artifacts are complete but a marker is absent, for example
    a fork home copied without its dot files, or published by a script older than the marker. Refusing that is the
    intended behavior.
  - Each set is classified by module name. The Spark 4.2 set is exactly `iceberg-spark-runtime-4.2_2.13`; the REST
    kit set is anything starting with `iceberg-open-api`; the rest belong to the base set. That matches what each
    `publish` call publishes.
- F11: closed. `sed -E 's/^1\.([0-9]+).*/\1/; s/^([0-9]+).*/\1/'` gives 17 for `17.0.13` and `17-ea`, 21 for `21`, and
  8 for `1.8.0_392`. An empty value is still refused.
- F8: closed, probed with the guard's own matching:
  - `H2`, `(H2)`, `H264`, `H265`, `O365`, `the G1 collector`, `G1 GC` and `-XX:+UseG1GC` pass.
  - `H2a`, `H2-x`, `see G1`, `G1 said`, `H21` and `R2 bucket` count. `R2` is still a possible false positive; it
    costs an allow-list line.
  - The self-test has 0 failures, and the scan of the worktree exits 0.
- F7: the docstring now says the skip is a decision that the check does not enforce. Can follow: a check that no
  tracked file exists under `docs/plans/` on non-draft pull requests.
- F9: closed. `docs/development/iceberg-fork.md:41-42` now says an IDE import is expected to fail until the
  script has run.
- F12: in the pull request body, per the record. I did not re-read the body.
- `check-needed-jobs.py --self-test`: 13 cases, 0 failures.

## 5. The merge of the base

- Parents: `a8fd7676` and `35cc7b1e`.
- `git diff a8fd7676 e5da0456` touches only 20 Java test and stand-in files. No `.gradle`, `.properties`, `.toml`,
  workflow or script file is touched.
- Every file that pull request 165 changed (`67989d16..35cc7b1e`) is identical in `35cc7b1e` and `e5da0456`, so
  nothing of 165 is lost.

## 6. CI on the head

- 22 check runs, all success except three skipped by design: the reference line's Docker job, and the two caller
  checks skipped on the `edited` event.
- New partition jobs: `Default build, test-group partition` and `Reference build, test-group partition`, both
  success.
- Aggregates:
  - `build-run-tests / Build and Run Tests` (140970034115): success. Its log reads "docker: success, partition:
    success, plan: success, tests: success".
  - `iceberg-linkedin-build / Build and Run Tests` (140967730984): success, with "docker: skipped, as this line
    does by design".
- Pin tests: both `JacksonRuntimeVersionsTest` tests and `JacksonShadedVersionsTest` passed in the default line's
  `rest` job (140962477263) and the reference line's (140962457744).

## Not checked

- Gradle resolution itself; I relied on the pin tests passing in CI on both lines.
- The pull request body (F12).
- A manual cancel of a run.
