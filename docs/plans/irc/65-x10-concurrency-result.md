# X10, concurrency part: row-level writes racing maintenance on format v3 tables, result

Bottom line: every race passed with no production code changed. In 34 native cases (Spark 3.5, OpenHouse catalog)
and 12 REST cases (Spark 4.2, Iceberg RESTCatalog), the service refused the losing commit built on the old base
(409), and Iceberg's own checks then ran against the new base. The loser either failed with a visible conflict
and the table exactly as the winner left it, or committed what a serial order gives. Two Iceberg behaviors are pinned,
not changed. Pull request 130, merged 2026-11-24 into `irc-integration` as merge commit
`adc28470278fe2c762ab37fbbe57c16bc7872a99` (pull request head `31af4c8c`). Tests only. The rest of X10 stays open.
Marks: [ran] run in a worktree; [read] read in code, not run; [inferred] not observed.
Source: the agent's record and the merge message; post-merge CI for 130 is not read yet.

## How the races are made deterministic

- The losing writer runs on a test-only catalog with a hook. The hook fires after Iceberg has built and validated the
  commit against base N, and before the request (the snapshots PUT, or the REST commit) is sent.
- The winner then runs to completion and commits N+1; the loser's attempt carries on. No sleeps.
- Each case asserts that the raced attempt itself was refused by the service, and pins which acceptable outcome it got.
- The fixture and checks of pull request 125 moved into `FormatV3Tables`, shared by `FormatV3MaintenanceTest` (bodies
  unchanged, same counts) and the new `FormatV3ConcurrencyTest` and `SparkIrcConcurrencyTest`.

After every case: rows equal a serial outcome, no deleted row is back, on v3 the `_row_id` of surviving rows is
unchanged and every row id is unique, every file any snapshot references exists, at most one deletion vector per data
file [ran].

## Results [ran, Iceberg 1.12; v2 merge-on-read and v3 the same unless said]

CONFLICT is a visible failure with the table as the winner left it. SERIAL is a commit with a serial-order result.

| Case (loser against winner) | Native path | REST path (Spark 4.2) |
| --- | --- | --- |
| Compaction against DELETE | CONFLICT (rewrite: new deletes for replaced files) | same |
| Compaction against INSERT | SERIAL | same |
| DELETE, UPDATE, MERGE against compaction | CONFLICT (missing data files) | same |
| DELETE against DELETE on the same data file | v2 SERIAL (two position delete files); v3 CONFLICT ("Found concurrently added DV") | not run |
| UPDATE against DELETE | CONFLICT (conflicting delete files) | not run |
| DELETE, UPDATE against snapshot expiry | SERIAL, no snapshot resurrected | not run |
| Expiry against DELETE | SERIAL | not run |
| Write whose scan snapshot an INSERT and expiry removed | CONFLICT (IllegalArgumentException, "not an ancestor") | not run |
| DELETE against retention | SERIAL; on v3 a dangling deletion vector (pinned) | not run |
| Retention against DELETE | SERIAL | not run |
| UPDATE against INSERT (row ids reassigned on retry) | SERIAL, ids unique | not run |
| Orphan removal through `createApp` (default 7 days; `--ttl 0` becomes 1 day) | in-flight files kept, SERIAL | not run |
| Orphan removal with age 0 through the constructor | all in-flight files deleted; the UPDATE commits and its manifest list is missing (pinned) | not run |

Over REST the raced request is refused by the requirement check ("Requirement failed: branch main has changed").
Natively the refusal is 409 "Conflict detected". The 12 REST cases are the first three rows of this table.

## Where each conflict check runs [read]

- Native: Iceberg's `SnapshotProducer.commit` refreshes, then runs the operation's validation against base N. Then
  `OpenHouseTableOperations.doCommit` sends the snapshots PUT with base N's metadata file as the base version.
  The service refuses a base that is not the head (`OpenHouseInternalRepositoryImpl.versionCheck`, 409), checks the writer's
  base against the catalog's (`abortIfWriterBaseDivergedFromCatalog`), and saves with a compare-and-set on the House
  Tables row's version. The client maps 409 to `CommitFailedException`; Iceberg refreshes to N+1 and validates again.
- So every Iceberg conflict check runs in the client, against a base the server will accept; the server never applies
  a commit built on another base.
- REST: `IcebergRestWriteHandler.commitOn` loads the stored table, checks the request's requirements against it, applies
  the updates and commits through `TableCommitService` with that base's version, under the same guards.
- Checks per pair: compaction validates that no new deletes touch replaced files; row-level writes validate that referenced
  data files exist, the scan snapshot is an ancestor, and on v3 that no concurrent deletion vector covers the same file.
  Expiry and retention have no validation and are recomputed on retry; orphan removal commits nothing, so only the age
  threshold protects a writer's uncommitted files.

## Pinned inside Iceberg (the fork is untouched)

- A DELETE prepared before retention and committed after it leaves, on v3, one deletion vector pointing at a data file
  retention removed. Rows are correct; it is a metadata leftover [ran]. Follow-up X13.
- Orphan removal with no age margin deletes a writer's in-flight files, and the writer then commits a snapshot whose
  manifest list is missing [ran]. It is reachable only through the app's constructor; the arguments floor the threshold at
  1 day. Follow-up X14, which needs Mike's say because it is a refusal.

## Evidence on head `31af4c8c` [ran], JDK 17; tests, failed, skipped

| Build line | Suite | Result |
| --- | --- | --- |
| 1.12 (default) | `:apps:openhouse-spark-apps-1.5_2.12:test` | 377, 0, 5; `FormatV3ConcurrencyTest` 34, 0, 0; `FormatV3MaintenanceTest` 39, 0, 0 |
| 1.5.2 (`-PicebergLinkedIn`) | same task | 377, 0, 41; concurrency 34, 0, 17 and maintenance 39, 0, 19 (the v3 cases skip) |
| Spark 4.2 | itest `test` | 79, 0, 0; `SparkIrcConcurrencyTest` 12, 0, 0 |

- `spotlessCheck` passed on both touched projects. `services/tables` and `internalcatalog` were not touched, not run.
- CI on the pull request: both jobs passed on `31af4c8c`. Post-merge CI for 130 has not been read.
- The first run failed 6 of 34 for harness reasons, fixed in tests only: partial-progress compaction commits from several
  threads (the race step now runs with partial progress off), the refused-attempt check looked at the wrong attempt, and
  the retention case was pinned to the wrong outcome. None was a data-safety failure.

## What is not covered

- The window inside the service between the base check and the House Tables compare-and-set: read in code, not raced.
- Partial-progress compaction raced by construction; batched orphan removal; expiry, retention and orphan removal over
  the REST routes; HDFS and object stores; branches and tags; partition evolution; equality deletes.
- Where each conflict check runs is read in code; the outcomes are what ran.
