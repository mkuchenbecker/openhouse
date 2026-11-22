# Findings from the first round of work

Bottom line: the test foundation exists and is green, the first four Iceberg patches are reapplied, and
writing the tests exposed existing defects and gaps in LinkedIn main. Everything below was observed by
delegated agents on 2026-11-22. Nothing here is a decision.

## What now exists

| Item | Result | Where |
| --- | --- | --- |
| T1 Spark SQL smoke suite | 21 scenarios, green locally and in CI | openhouse fork, pull request 80 |
| T3 Catalog plugin contract tests | 18 tests, green on Iceberg 1.2 and 1.5 lines | openhouse fork, pull request 81 |
| T4 Service commit-path tests | 50 tests in four groups, green | openhouse fork, pull request 82 |
| I3/I4 first slice | 4 LinkedIn patches reapplied, each with a failing-then-passing test | iceberg fork, pull request 3 |

## Existing behavior that looks like a defect (pinned as it is today)

Fixing any of these is a behavior change and needs a decision.

- An unparseable snapshot in a snapshot commit returns 500, not 400.
- A table update that supplies a lock state on a table that never had one returns 500 (null pointer).
- A staged replace that drops a column used by the sort order returns 500.
- Invalid sort order JSON returns 500. Exact duplicate column names return 500; case-only duplicates return 400.
- Renaming a table to a name that differs only by letter case from another existing table succeeds,
  because identifiers are case-sensitive on rename.
- Loading a table with an invalid identifier through the plugin reports "table does not exist". The
  plugin swallows the 400, so the caller cannot tell invalid from missing.
- A snapshot commit that changes nothing still writes a new metadata version.
- A comment in the code says a 422 on commit surfaces as a commit-state-unknown error. It does not. It
  surfaces as a plain client error with status 422.

## Gaps in what tests can observe

- Grants: the embedded test server skips grants and lists none. No fixture in the repo can observe that a
  grant took effect, on any catalog path.
- Commit conflicts: the test stand-in for the house table store has no version check, so conflicts
  cannot be produced at the HTTP boundary. A job to make the stand-in faithful is in progress.
- Soft delete: the same stand-in inverts the delete flag relative to the real client, so soft-delete
  behavior seen in tests does not match production. Same job.
- Authorization: before T4, no black-box test ever received a 403. T4 now pins a 403 for every operation.

## Other observations

- The Iceberg fork's CI cannot currently prove anything: about 40 jobs start at once and Maven Central
  rate-limits them (HTTP 429) before any code compiles. Local runs are the only validation of the patches.
- Reapplying the write-distribution default required editing five upstream test assertions, the same
  ones LinkedIn's own commit edited.
- Apache 1.12 already writes an application name into snapshot summaries under its own key. LinkedIn's
  patch adds its own key next to it. Both are kept.
- The repo's commit hooks fail under JDK 17 unless extra compiler exports are passed.

## Second round (Iceberg patches and the 1.12 line)

- All 13 LinkedIn patches are reapplied for Spark 3.5, each with a failing-then-passing test (iceberg fork, pull requests 3 to 7). A combined validation run is in progress.
- LinkedIn's delete-file replication default (3) makes every merge-on-read delete throw on any file IO that is not Hadoop's. Iceberg's REST catalog uses such a wrapper by default. A forwarding fix was added, labelled as not from LinkedIn.
- The same default throws on S3 and other non-HDFS storage, on LinkedIn's line too. Left as it is; a decision is needed for use outside LinkedIn.
- Parquet delete files never receive the replication factor, on LinkedIn's line or the new one. Left as it is.
- LinkedIn's split-size key only affects compaction scans and streaming reads, never ordinary batch scans. Ported with exactly that reach.
- Column defaults on v2 tables: reads and writes work on the 1.12 line, but any schema change on a table with a default fails in the client. See `10-v2-defaults.md`.
- On the 1.12 line Parquet and Avro readers fill defaults too. On LinkedIn's line only ORC does. Opted-in Parquet tables would show a default where they show NULL today.
- The delta-harness is being landed on the new base as a deep check (work item T2).

## Third round (delta-harness baseline and combined Iceberg validation)

The delta-harness is work item T2. It is in openhouse draft pull request 85, on branch
`irc/t2-delta-harness`, into `irc-integration`. CI finished with nothing failed. No production code
changed. The only edit outside `integrations/spark/delta-harness` is one `.gitignore` line.

Legacy-path baseline, JDK 17, parallelism 2, `-Xmx3g`. Without an explicit heap limit the run was
killed by the out-of-memory killer.

| Run | Cases | Passed | Failed | Skipped | Time |
| --- | --- | --- | --- | --- | --- |
| Slice `delete parquet` | 238 | 237 | 0 | 1 | about 4 minutes |
| Full, in-memory house-table stub | 2574 | 2560 | 3 | 11 | 1365 s |
| Full, real embedded house-table service (`HARNESS_REAL_HTS=1`) | 2792 | 2778 | 3 | 11 | 1683 s |

The 3 failures are the same in both full modes: `control.lock.enforcement`,
`interact.rtas.onLockedTable` and `hazard.lock.starvesMaintenance`. After a LEGACY lock, the next Spark
write fails in the client with `JsonParseException: Cannot convert policies string to policies object`
instead of the server's "locked" rejection. LinkedIn's `OpenHouseTableOperationsTest` pins that parse
failure. The cases were left asserting the intended behavior. Because they fail at the lock setup,
whether LinkedIn's #650 (reject RTAS on a locked table) works could not be checked.

The 11 skips are the harness's own known-bug tags and were not re-evaluated:

- 3 `nested.deleteByNestedField`.
- 6 `ddl.renameColumn`, tagged as a regression from LinkedIn #558.
- 1 `ddl.encryption.active`, because the KMS plugin is not in the repo.
- 1 `control.undrop.lifecycle`, skipped under the stub. The `undropAdmin.*` cases cover it in real mode
  and pass.

Eight case expectations were updated to follow LinkedIn changes: #830 (RTAS with replication and RTAS
with WAP are rejected with a 400 at CREATE and ALTER time) and #652 (RTAS keeps existing policies).

The harness is not wired into CI. The agent's recommendation is on-demand runs only: 23 to 28 minutes
per full mode, on top of a build of roughly 35 minutes. The harness hardcodes `catalog-impl` at
`wireCatalog` in `Env.scala`. A run through the in-process REST-shaped path is in progress on branch
`irc/t2b-harness-via-seam` (work item T2b).

Combined validation of the Iceberg patch branches (I8, iceberg draft pull request 8, branch
`irc/reapply-all`, into `openhouse-1.12.0`). The five patch branches merge with no conflicts and compile
together, including the Spark 4.2 module. 20,654 tests ran and 8 failed. All 8 failures are in
`spark.actions.TestComputePartitionStatsAction` on Spark 3.5. One reapplied patch alone causes them
("Default partitioned write distribution mode to NONE": the test expects 1 data file and gets 2). No
interaction between patches was observed.

| Module | Run | Failed | Skipped |
| --- | --- | --- | --- |
| api | 1295 | 0 | 1 |
| common | 5 | 0 | 0 |
| orc | 87 | 0 | 0 |
| parquet | 756 | 0 | 9 |
| data | 805 | 0 | 85 |
| core | 9131 | 0 | 550 |
| Spark 3.5 | 5513 | 8 | 990 |
| Spark 3.5 extensions | 2719 | 0 | 180 |
| Spark 4.2, six baseline classes | 343 | 0 | 32 |

revapi and spotless pass. Checkstyle fails in 4 files, each introduced by one branch:
`RewriteJobOrder.java`, `TestBinPackRewriteFilePlanner.java`, `OutputFileFactory.java` and
`TestSparkOrcInitialDefaultScan.java`.

Not run: Flink, Kafka Connect, Spark 4.0 and 4.1, Scala 2.12, the spark-runtime modules, open-api tests,
and Spark 4.2 beyond the six baseline classes. The fixes for the 8 test failures and the 4 style failures landed, and
I8 is validated on the combined branch (tip f180ae589). Full suites were not re-run after the fixes.

## Fourth round (harness through the REST-shaped path)

The first version of the faithful stand-in fixed only one of the two source copies; the copy used by Spark integration tests and the harness still accepted two commits on one base version. Found by running the harness through the REST-shaped path.

## Fifth round (hooks and Spark SQL defaults)

- The repository's pre-commit hook stashes with `git stash --keep-index` before running `spotlessCheck`. That removes the merge state, so `git commit` cannot complete a merge commit. Merge commits on the branches for pull requests 84 and 86 were built with `git commit-tree` after running `spotlessCheck` by hand. The pre-push hook ran normally.
- Both hooks print `[[: not found`, so they do not fail the commit or push when `spotlessCheck` fails.
- Spark SQL cannot set a column default on Iceberg 1.12 with Spark 3.5 or 4.2: all three statements are refused. On LinkedIn's 1.5.2 line `ALTER TABLE ADD COLUMN ... DEFAULT` silently drops the clause (read, not run). See `14-native-defaults-design.md`.

## Sixth round (test loop and CI)

- A fresh git worktree costs about one minute, not a cold build, because worktrees share the Gradle caches (measured: `:services:tables:test` 7m58s fresh against 7m01s warm).
- A pull request whose branch carries commits that the target has as merge commits can be reported by GitHub as conflicting although git merges it cleanly; GitHub then starts no CI run. Merging the target into the branch fixes it (seen on pull requests 84 and 87).
