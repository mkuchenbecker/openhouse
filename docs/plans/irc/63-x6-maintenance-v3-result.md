# X6: maintenance jobs on format v3 tables, result

Bottom line: every maintenance job that opens a table ran on a format v3 table with deletion vectors, row lineage and
12 snapshots, and lost no live data. No production code changed, nothing on v2 changed and no job refuses v3. Three
behaviours were found and pinned, not changed; one of them is put to Mike (D46). Pull request 125, merged 2026-11-24
into `irc-integration` as merge commit `262a1416b982ec99694713cfdc8ed8d26fa3e344` (pull request head `3bce6326`).
Tests only, apart from one test-only system property in `apps/spark-3.5/build.gradle` naming the Iceberg line.
Marks: [ran] run in a worktree; [read] read in code, not run; [inferred] not observed.

## What ran, per job

All the jobs live in `:apps:openhouse-spark-apps-1.5_2.12` (`apps/spark-3.5`). Each ran its real app code through
`runInner` and `runValidations`; compaction also ran through `createApp` with the scheduler's exact arguments.

| Job | What ran | Result on v3 |
| --- | --- | --- |
| Data compaction, layout strategy execution | Iceberg binPack, strict settings and production arguments | Passed untouched; row ids and last-updated sequence numbers kept [ran] |
| Snapshot expiry | With file deletion, and metadata-only followed by orphan removal | Passed untouched [ran] |
| Orphan file deletion, single and batched | Orphans planted beside a live Puffin file, time to live 0 | Passed untouched; only planted orphans removed [ran] |
| Retention | Backup on and off | Passed untouched; live data kept; backup contents pinned below [ran] |
| Staged files deletion | A two-day-old file in `.trash` | Passed untouched [ran] |
| Table stats, single and batched | All four sinks | Passed untouched; sizes over-reported (X12) [ran] |
| Layout strategy generation | Strategy property written | Passed untouched [ran] |
| Sort stats collection | Property written | Passed untouched on 1.12; fails on v2 too on 1.5.2, pinned [ran] |
| Orphan directory deletion | Not run on v3 | Never opens Iceberg metadata, so format-independent [read] |
| Table directory deletion, replication | No Spark app here; X1 refuses v3 replicas (X7) | Not applicable |

The fixture is a table partitioned by days of `ts`, merge-on-read, with 8 data files, two DELETEs, an UPDATE and a
MERGE: 12 snapshots, deletion vectors in more than one Puffin file, a Puffin file partly replaced and partly live.
After each job: full row contents equal, the 10 deleted ids still deleted, `_row_id` and
`_last_updated_sequence_number` equal, time travel to every retained snapshot reads as before, and every file a retained
snapshot references exists. The same cases run on v2 tables on the default service and pass.

## Pinned and not changed

- Retention backup on a merge-on-read table [ran]: the backup holds all 6 data files of the 2 expired partitions and a
  `data_manifest` listing only data files. It therefore holds rows deleted before retention, ids 1 to 16 including 2, 3,
  6, 10, 14 and 15, and old versions of updated rows, with nothing recording the deletes. On v2 the position delete files
  are moved into the backup unlisted. On v3 Puffin files sit at the `data/` root, so the handler never moves them and no
  deletion vector reaches the backup. This predates v3. No restore code exists in this repository; the history (pull
  requests 384, 408 and 418) names an external uploader that reads the manifests. Put to Mike as D46.
- A Puffin file spanning an expired and a kept partition survives retention by both paths; the kept partition's deleted
  rows stay deleted [ran]. No data loss, no fix.
- Sort stats collection on the LinkedIn Iceberg 1.5.2 line fails on v2 with "Cannot parse order: parser is not an
  Iceberg ExtendedParser" and rewrites nothing. The job configuration uses the same extension order, so a deployment is
  expected to fail the same way [inferred] (X11).
- The scheduler's compaction arguments (table name only; layout arguments; strategy JSON) rewrite 6 of the 8 files
  carrying deletes on 1.12, dropping the rewritten files' deletion vectors with none left dangling. On 1.5.2 they
  rewrite nothing for this fixture [ran].

## The review and the second round

The first round reported that every job passed untouched. Because this was the riskiest v3 item, the orchestrator had an
independent agent review the tests, read-only, before merging. Its verdict was "proves the requirement with named gaps":
the retention backup was not examined, compaction was not run with the scheduler's real arguments, several "the job did
real work" claims were not asserted, and nothing guarded against the v3 cases being silently skipped. The author closed
each gap in a second round (head `51d44ef9`): the backup pin, the production-arguments test, assertions that files were
rewritten or deleted, and `v3CasesAreNotSkippedOnTheDefaultLine`. The review's line reference for the strategy path was
off; it is `DataCompactionSparkApp.java` lines 163 and 164.

## Evidence

At head `3bce6326`, with `irc-integration` merged in, JDK 17; tests, failed, skipped [ran]:

| Build line | Whole `apps/spark-3.5` suite | `FormatV3MaintenanceTest` |
| --- | --- | --- |
| Iceberg 1.12 (default) | 343 / 0 / 5 (the 5 skips predate this work) | 39 / 0 / 0 |
| 1.5.2 (`-PicebergLinkedIn`) | 343 / 0 / 24 (the 19 v3 cases skip) | 39 / 0 / 19 |

CI on the pull request passed on both jobs at `3bce6326`. An earlier head shows a cancelled job superseded by the next
push. Post-merge CI on `irc-integration` has not been read yet.

## Not covered

- Writes concurrent with compaction or expiry, HDFS and object stores (local file system only), branches and tags,
  partition evolution on v3, equality deletes (Spark does not write them), the scheduler's table selection, the jobs
  service and Livy. Work item X10.
- A 1.5.2 job against a v3 table in a mixed deployment: expected to fail before acting, because the 1.5.2 parser rejects
  the version [read from the class file, not run].
- Statistics files: the native service stores none, so the checks over them had nothing to check.
- Table stats on v3 count each deletion vector as a delete file and add the whole Puffin size; reporting only (X12).
