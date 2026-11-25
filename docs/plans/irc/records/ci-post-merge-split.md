# CI: confirm a merge sooner (post-merge build) (record)

Bottom line: draft pull request #144 (head 2abb70a3cbdf3ca4fecb2483b59a82dbc61882f4, one commit)
runs the post-merge build as the pull request's split, with one cache writer per line (`rest`)
and a per-commit concurrency group, so merges' runs overlap. Merge to answer: 63 minutes on
average (39.9 to 106.5) before, about 34 after on the branch's two dispatched runs, with no queue.
Same test tasks and results on both lines. Cost: about 130 runner minutes per merge, up from 71.
Pull request CI: run 45513674220, all 13 jobs green (Docker job skipped on the reference line, as
always), 25.6 minutes, 58 runner minutes.

## Measurement (ten post-merge runs on irc-integration, 2026-11-24, minutes)
| run | merge | created (UTC) | queue | default job | reference job | merge to answer |
|---|---|---|---|---|---|---|
| 45480821854 | 3079859d | 20:26 | 50.0 | 46.0 | 23.0 | 95.9 |
| 45480177836 | af210f1b | 20:22 | 0.1 | 53.5 | 25.2 | 53.6 |
| 45459658564 | dfcd15a0 | 18:26 | 0.1 | 67.4 | 29.0 | 67.5 |
| 45443122100 | 461ae437 | 16:57 | 13.4 | 41.8 | 23.1 | 55.2 |
| 45437773020 | e6c52c21 | 16:29 | 0.1 | 41.1 | 21.1 | 41.2 |
| 45417569821 | c2c52144 | 14:45 | 18.3 | 25.5 | 20.4 | 43.8 |
| 45401786716 | 4b696a55 | 13:16 | 50.3 | 56.2 | 26.9 | 106.5 |
| 45397520628 | e2823b69 | 12:51 | 22.6 | 53.4 | 25.5 | 75.9 |
| 45394689450 | d30cb9f6 | 12:33 | 0.1 | 39.9 | 21.7 | 39.9 |
| 45381813435 | 7e57bd21 | 11:11 | 0.1 | 51.6 | 19.1 | 51.6 |
Means: queue 15.5, default job 47.6, reference 23.5, merge to answer 63.1. Queue = run created
to first job started. About a quarter of the lag was queueing, three quarters the default job (its
67-minute run: build step 59.3, Docker 4.7, compile 2.5). No run of the last 30 was cancelled.
Dataloader post-merge: 13 to 16 minutes, queue 0 to 12; not the lag, not changed.

## Change (commit 2abb70a3)
- post-merge-build.yml: calls split-build.yml for both lines (`gradle-cache-write: true`);
  concurrency `post-merge-build-${{ github.ref }}-${{ github.sha }}`, no cancel.
- split-build.yml: input `gradle-cache-write`; tests job `cache-read-only:
  ${{ !(inputs.gradle-cache-write && matrix.group == 'rest') }}`; KEY_JOB names unchanged.
- iceberg-linkedin-build.yml removed (no caller). build-run-tests.yml, pr-validations.yml:
  comments only. docs/development/iceberg-fork.md CI section updated.
- Not touched: build-tag-publish.yml, dataloader-tests.yml, both actions, scripts.

## Why this choice
- Split alone cuts run time to about 30; overlap removes the queue. Cancelling a superseded
  run (b) would leave merges with no run of their own; overlap keeps one complete run per merge
  commit, which is what the history shows. Overlap adds no runner minutes per merge.
- One writer per line: an entry is about 530 MB and setup-gradle's cleanup keeps only what the
  job used (dependency entries are content-hashed per job, too), so a writer per job would save
  about eleven entries a merge and crowd the fork's entry out. `rest` compiles every project.

## Proof of equal tests
- check-test-groups.py on head: default 775 tasks / 43 test tasks, reference 686 / 34; holds.
- Before = post-merge 45500958657 (single jobs, f07a92e1); after = dispatch 45502632555 (split,
  all executed). Default: 43 = 43 task names, 1489 results of the 19 tasks executed in both
  identical, no before result missing; 5314 results after. Reference: 34 = 34, 471 identical,
  none missing; 4092 after. Extra results are all in tasks that were FROM-CACHE before (24 / 22).
  95 FAILED = compat kit's expected failures, same both. Files: scratchpad pm/{def,ref}-*.txt.

## Cache, exercised
- Dispatch works on the branch (post-merge already had workflow_dispatch; it is registered).
  Branch runs use the branch's cache scope, not irc-integration's.
- 45502632555 (cold, 33.5 min, green): only the two rest jobs saved gradle-home entries
  (`...|build-and-run-tests[db78f2e3...]-2abb70a3...`, `...|iceberg-linkedin-build[...]`); all
  others "Cache is read-only". Fork published and saved by one job.
- 45508625175 (warm, 34.1 min, green): every read-only job "Cache hit for restore-key:
  ...build-and-run-tests[db78f2e3...]" (the job-level key pull requests use); every default job
  "Cache restored from key: iceberg-fork-home-...". Long pole Default apps-spark-4.2 (test 31.2
  min; 26 on the cold run and on pull request 143).
- Only the first post-merge run can show the new writer's entry in irc-integration's scope and a
  pull request restoring it.

## Cost
Runner minutes per merge: about 71 before; 130 warm, 206 cold after. Pull request jobs other
than rest lose build-cache hits for their test tasks (more runner minutes, same wall time).

## Not verified
- A red post-merge job (print failed tests, upload reports): same steps as the pull request's.
- The new writer's entry in irc-integration scope and a pull request restoring it.
- Runner concurrency with two overlapping post-merge runs plus pull requests (26+ jobs).
- A fork pin change after merge: five default jobs would publish at once; first save wins.

## Pull request CI (run 45513674220, head 2abb70a3, green, 25.6 min)
Every job success. Default rest, apps-spark-4.2 and Reference rest logs: "Cache hit for
restore-key: gradle-home-v1|Linux-X64|build-and-run-tests[3c82eef1...]-f07a92e1..." (the single
job's entry on irc-integration) and "Cache restored from key: iceberg-fork-home-...".
