# CI: split the pull request build into parallel jobs (record)

Bottom line: pull request #133 (draft) cuts a pull request's CI from 52.2 minutes (run 45359466552)
to 21.6 on the final head fa5d7df270ae6f2d24f891e60d96dcfb96ae9e48 (run 45377983486, green,
all 11 jobs; 18.4 on the measurement run). The test lists are identical to the single jobs': 42 + 34
test tasks with the same outcomes, 3869 + 3299 test results. Cost: 113 runner minutes against 78
(+35, +44 %).

## Where the 52 minutes went (run 45359466552, default job)
| part | minutes |
|---|---|
| setup, fork restore | 0.5 |
| `clean assemble testClasses` | 2.6 |
| `build --continue` (4 cores, parallel) | 44.3 |
| of which: apps test (single task) | 38.0 |
| services:tables:test | 19.3 |
| spark-4.2 itest: test 12.5, testServerPlanning 6.8, smoke 0.9, smokeSP 0.9, ext 0.7 (serial) | 22 |
| spark-3.5 itest: catalogTest 16.0, statement 0.9, test 0.5 | 17.5 |
| internalcatalog 12.8, housetables 3.5, datalayout 3.5, fixtures 3.1, compat kit 1.4 | beside |
| Docker steps (shadowJar 2.8, images, recipe, integration test) | 4.8 |
Reference job 25.9: apps test 21.2 critical; tables 13.0, internalcatalog 12.7, spark-3.5 10.8.
Apps test classes: FormatV3Maintenance 11.9, FormatV3Concurrency 8.6, Operations 5.1, Bucket 3.2.

## The split
- Cut by project directory: scripts/ci/test-groups.py makes one job per directory matching
  `apps/spark-*` and `integrations/spark/spark-*/openhouse-spark-itest`, each runs that project's
  `check`; `rest` runs `clean build` with those `check`s excluded. The init script
  scripts/ci/test-groups.gradle does the cutting in Gradle (settingsEvaluated, by projectDir).
- Default line: apps-spark-3.5, spark-3.5 itest, spark-4.2 itest, rest, Docker job. Reference: the
  same minus Docker; the spark-4.2 itest job is a no-op there (says so, 0.5 min).
- A new project under a pattern (apps/spark-4.2 from irc/jobs-spark42-scout) gets its own job with
  no workflow edit; checked by dry run on that branch, both lines: partition holds.
- scripts/ci/check-test-groups.py: dry runs prove union of jobs == `clean build` (754 / 686 tasks,
  775 on the scout branch) and each test task in exactly one job.

## Timings (job durations, minutes)
| job | run 1 (190110cc, ref cached) | measurement (754d6849) | final (fa5d7df2) |
|---|---|---|---|
| Default apps-spark-3.5 | 21.6 | 18.2 | 21.4 |
| Default spark-4.2 itest | 16.8 | 16.8 | 16.4 |
| Default rest | 15.7 | 14.0 | 15.7 |
| Default spark-3.5 itest | 12.2 | 10.8 | 11.8 |
| Default Docker | 5.8 | 6.9 | 7.1 |
| Reference apps | 1.4 (cache) | 17.3 | 15.1 |
| Reference rest | 1.8 (cache) | 15.6 | 14.9 |
| Reference spark-3.5 itest | 11.0 | 11.3 | 9.8 |
| test groups x2, ref spark-4.2 no-op | 0.7 | 1.0 | 0.8 |
| span | 21.7 | 18.4 | 21.6 |
| runner minutes | 87.0 | 112.0 | 112.9 |
Before: 52.2 + 25.9 = 78.2 runner minutes (pull request run); post-merge 9f9561e6: 41.8 + 27.1.

## Proof of equal test lists
Parser: scratchpad tools/tasks.py (task headers, `Class > test RESULT` lines). Before = post-merge
run 45367882824 (single jobs, same tree 9f9561e6). After = run 45374890816 (all executed):
default 42 tasks, 3869 results, 0 differences; reference 34 tasks, 3299 results, 0 differences.
Final run 45377983486: the same, 0 differences on both lines (13 / 11 tasks FROM-CACHE in both).
Run 45371062391: default identical; reference same 34 tasks, 9 FROM-CACHE instead of executed.
Files: scratchpad ci/proof3/ (final) and ci/proof2/ (measurement), {def,ref}-{before,after}-*.txt.

## Decisions
- No shared prepare job: it would put compile (2.6 min) plus artifact transfer on every job's
  critical path, and Gradle up-to-date state does not travel with build dirs. Each job compiles
  what it needs (cost is runner minutes, not wall time).
- Post-merge and release keep the single jobs: they write the Gradle cache (~530 MB an entry);
  eight split jobs would write eight entries a merge and evict the fork cache. Pull request jobs
  read the single job's entry via GRADLE_BUILD_ACTION_CACHE_KEY_JOB and write none.
- Docker steps moved into composite .github/actions/default-build-deployables, used by both.
- Check names: irc-integration protection 404, no rules on it; the one ruleset covers main and
  requires no checks; nothing in the repository names the jobs. No aggregating job.
- Measurement commit 754d6849 (a comment in TablesSpringApplication) reverted by fa5d7df2; on the
  final head the reference tests executed anyway (the cache entry it restored was older).

## Not verified
- The failure path (print failed tests, upload reports) in a split job: no red run happened.
- A pull request that changes the fork pin (five jobs publishing the fork at once).
- Post-merge and release with the composite action (same steps; exercised only by the Docker job).

## Path-based selection (proposal only, nothing implemented)
- The suggested example would be wrong: tables-test-fixtures (a test dependency of the apps, both
  Spark itests, the compat kit and datalayout) embeds services:tables, internalcatalog and
  cluster:storage. A services/tables change has to run the apps tests.
- Safe savings: (a) a pull request touching only docs/** or *.md: skip all Gradle jobs (about 113
  runner minutes, 21 wall minutes); (b) only scripts/python/** or infra/recipes/**: Docker job only.
  A wrong filter skips silently; keep the post-merge full run as the backstop.
- Better than paths: Gradle's own selection. The build cache already skipped 9 to 11 reference
  test tasks with unchanged inputs; on the default line the big suites always execute, so some
  input of them changes from run to run. Finding and fixing that makes unchanged suites skip with
  no false skips. Not investigated.
