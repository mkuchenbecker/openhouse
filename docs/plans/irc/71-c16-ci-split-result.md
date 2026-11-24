# C16: a pull request's CI is split into parallel jobs

Bottom line: a pull request's CI now takes 21.6 minutes of wall time, down from 52.2, and runs the same tests: the
test lists of the split jobs and of the single jobs are identical on both build lines [ran, parsed from job logs].
The price is machine time: 113 runner minutes for a pull request run, up from 78 (about 35 minutes, 44 percent,
more). Pull request 133 merged on 2026-11-24 as `7e57bd21` into `irc-integration`, head `fa5d7df2`. Post-merge runs
are unchanged: two single jobs that run everything, and they remain the only writers of the Gradle cache. Dated
2026-11-24. Record: `ci-split-build-job.md` in the reports directory.

## Where the 52 minutes went

Measured on run 45359466552, the default line's single job, which took 52.2 minutes [ran]. The `build --continue`
step took 44.3 minutes because suites competed for 4 cores inside one job.

| Part | Minutes |
| --- | --- |
| Setup and restoring the fork | 0.5 |
| `clean assemble testClasses` | 2.6 |
| `build --continue` | 44.3 |
| of which the apps test task, a single task | 38.0 |
| of which `services:tables:test` | 19.3 |
| of which the Spark 4.2 itest tasks, one after another | 22 |
| of which the Spark 3.5 itest tasks | 17.5 |
| Docker steps (shadow jar, images, recipe, integration test) | 4.8 |

The reference line's job took 25.9 minutes (its apps test 21.2). The default job had grown from about 36 minutes
the same morning because each recent pull request added suites. The owner's stated preference is a short iteration
loop with the full suite on merge.

## How the build is cut

- The cut is by project directory, not by task name. `scripts/ci/test-groups.py` makes one job for each directory
  matching `apps/spark-*` or `integrations/spark/spark-*/openhouse-spark-itest`, and that job runs the project's
  `check`. A catch-all job `rest` runs `clean build` with exactly those `check` tasks excluded. The cutting happens
  inside Gradle, in the init script `scripts/ci/test-groups.gradle`, by project directory.
- A project that no pattern matches lands in `rest`, so it cannot fall through. A new project that matches a pattern
  gets its own job with no workflow edit. Checked by dry run on the branch that adds `apps/spark-4.2`: that project
  gets its own job on the default line and nothing on the reference line [ran].
- `scripts/ci/check-test-groups.py` proves by Gradle dry runs that the union of the jobs equals `clean build` (754
  tasks on the default line, 686 on the reference line, 775 on the Spark 4.2 branch) and that each test task is in
  exactly one job [ran].
- Default line: apps-spark-3.5, spark-3.5 itest, spark-4.2 itest, rest, and a Docker job. Reference line: the same
  without Docker; its spark-4.2 itest job finds no project and says so (about 0.5 minutes).
- The Docker steps are one composite action, `.github/actions/default-build-deployables`, used by the pull request's
  Docker job and by the single job, so the two cannot drift.
- Check names now look like `build-run-tests / Default build, apps-spark-3.5`. Nothing depends on them:
  `irc-integration` has no protection or rules, the one ruleset covers `main` and requires no checks, and nothing in
  the repository names the jobs [read in the repository settings and files].

## Proof of equal test lists

Per-task test events were parsed from the job logs. Before: post-merge run 45367882824 (single jobs, tree
`9f9561e6`). After: the union of the split jobs of run 45374890816.

| Line | Test tasks | Test results | Differences |
| --- | --- | --- | --- |
| Default | 42 | 3869 | 0 |
| Reference | 34 | 3299 | 0 |

The same comparison held for the final run (45377983486) and the measurement run [ran]. In the final run 13 default
and 11 reference test tasks came from the build cache, in the single jobs and the split jobs alike.

## Timings

Final run 45377983486 (head `fa5d7df2`), all 11 jobs green; 21.7 and 18.4 minutes on two other runs [ran].

| Job | Minutes |
| --- | --- |
| Default build, apps-spark-3.5 (the longest) | 21.4 |
| Default spark-4.2 itest | 16.4 |
| Default rest | 15.7 |
| Default spark-3.5 itest | 11.8 |
| Default Docker | 7.1 |
| Reference apps, rest, spark-3.5 itest | 15.1, 14.9, 9.8 |

## What stays on the single jobs, and why

- Post-merge and release keep the single jobs. They run everything after each merge and write the Gradle cache
  (about 530 MB an entry); eight split jobs would write eight entries a merge and evict the fork cache. Pull request
  jobs restore the single job's entry and write none.
- A shared prepare job was rejected: it would put the 2.6 minute compile and an artifact transfer in front of every
  job, and Gradle's up-to-date state does not travel with build directories. Each job compiles what it needs; the
  cost is runner minutes, not wall time.

## What was not exercised

- Post-merge CI of pull request 133's merge was read and passed on 2026-11-24, and the reshaped Docker step, which
  the record listed as not yet exercised from post-merge, ran and passed in that post-merge job [ran]. Release has
  not run through the composite action.
- No split job has failed yet, so the failure path (print failed tests, upload reports) has not run red in a split
  job; its conditions and script are unchanged.
- A pull request that changes the Iceberg fork pin would make five jobs publish the fork at once; only the first
  cache save wins. Not exercised.
- A measurement commit (`754d6849`, a comment in the tables service) was added and reverted (`fa5d7df2`); the merged
  tree has no code change.

## Proposals not acted on

- Path-based selection [inferred]. Safe only for narrow cases: a pull request touching only documentation could skip
  all Gradle jobs (about 113 runner minutes, 21 wall minutes), and one touching only recipes could run the Docker job
  alone. The obvious example would be wrong: the test fixtures embed `services/tables`, so a change under that path
  must run the apps and Spark suites. A wrong filter skips silently, so the post-merge full run stays the backstop.
  Recorded as C18.
- Finding why the default line's large test tasks never come from Gradle's build cache [inferred]. The build cache
  skipped 9 to 11 reference-line test tasks with unchanged inputs, but on the default line the big suites always
  execute, so some input changes from run to run. Fixing that would skip unchanged suites with no false skips, which
  is better than path filters. Not investigated. Recorded as C17.
