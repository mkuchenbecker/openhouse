# X10: row-level writes racing maintenance on format v3 tables: record

Marks: [ran] = run in worktree /home/user/oh-x10 (branch irc/x10-v3-maintenance-concurrency, base ca94d82e);
[read] = read in code, not run.

## Bottom line

Every race passed untouched [ran, 1.12]: 34 native cases (Spark 3.5, OpenHouse catalog) and 12 REST
cases (Spark 4.2, Iceberg RESTCatalog). In each, the service refused the loser's commit built on N (409)
and Iceberg's checks then ran against N+1: either a visible conflict with the table exactly as the winner
left it, or a serial-equivalent commit. No production code changed. Two Iceberg behaviours pinned: a
DELETE racing retention leaves a dangling DV (v3; rows correct); orphan removal with TTL 0 (constructor
only, the CLI floors at 1 day) deletes a writer's in-flight files and the writer commits a broken table.

## State for a restart

- Commits: f964dd9a tests+extraction, 84c4a232 harness fixes, 31af4c8c REST tests (all pushed).
- Base ca94d82e still the head of irc-integration (fetched at the end; nothing to merge). Pull request 130, draft, CI green on 31af4c8c. Done.
- Plan: hook catalog (test only) that runs the winner inside the loser's first commit attempt, before
  the snapshots PUT; new test class FormatV3ConcurrencyTest (apps, Spark 3.5) on helpers extracted from
  FormatV3MaintenanceTest into FormatV3Tables; Spark 4.2 REST test with a hooked RESTClient.
- f964dd9a pushed (tests + extraction). Run 1 [ran, 1.12]: 34 cases, 28 pass; 6 failures were all test
  harness/pin issues (partial-progress compaction commits from two threads; my "first attempt" check;
  retention pin), none a data-safety failure. Fixes in the test only, rerun in progress.

## Where each conflict check runs [read]

OpenHouse path, native: Iceberg's SnapshotProducer.commit: apply() refreshes (GET table) and runs the
operation's validate(base N); then OpenHouseTableOperations.doCommit sends the snapshots PUT with
baseTableVersion = N's metadata file. Service: OpenHouseInternalRepositoryImpl.versionCheck refuses
base != head (CommitFailedException, 409); OpenHouseInternalTableOperations.prepareCommit
abortIfWriterBaseDivergedFromCatalog (writer base vs. catalog base), failIfRetryUpdate, then House
Tables save is a compare-and-set on the row's version (concurrent update -> 409). Client maps 409 to
CommitFailedException; Iceberg retries: apply() refreshes to N+1 and re-runs validate against N+1.
So every Iceberg conflict check runs in the client, against the base the server will accept, never
skipped: the server never applies a commit built on another base. IRC: IcebergRestWriteHandler.commitOn
loads the stored table, checks the request's requirements (assert-ref-snapshot-id etc.) against it,
applies the updates and commits through TableCommitService with that base's version (same guards).

Checks per pair (Iceberg 1.12 fork):
- compaction (RewriteFiles) vs. new deletes: BaseRewriteFiles.validate ->
  validateNoNewDeletesForDataFiles(startingSnapshot) -> ValidationException.
- DELETE/UPDATE/MERGE (SparkPositionDeltaWrite -> RowDelta) vs. compaction: validateDataFilesExist
  (referenced files; for DELETE, files removed by a "delete" snapshot are skipped), validateFromSnapshot
  (scan snapshot), UPDATE/MERGE: validateDeletedFiles + validateNoConflictingDeleteFiles; serializable:
  validateNoConflictingDataFiles; BaseRowDelta.validate: scan snapshot must be an ancestor
  (IllegalArgumentException), validateAddedDVs (v3: a concurrent DV for the same data file).
- expiry (RemoveSnapshots / ExpireSnapshotsSparkAction): no validation; recomputed on retry; files
  deleted = referenced by the pre-commit metadata minus referenced after the commit.
- retention (Spark metadata DELETE -> DeleteFiles.deleteFromRowFilter): no validation; re-evaluated on
  retry; on v3 drops DVs of removed data files (removeDanglingDeletesFor).
- orphan removal: commits nothing; the only guard for a writer's uncommitted files is the age threshold
  (production: createApp --ttl default 7 days, floor 1 day; table opt-in 1 day; replica tables 3 days).

## Results [ran, 1.12; v2 and v3 the same unless said]

Native (FormatV3ConcurrencyTest, 34/34): compaction vs DELETE: CONFLICT (rewrite: new deletes for replaced
files); compaction vs INSERT: SERIAL; DELETE/UPDATE/MERGE vs compaction: CONFLICT (missing data files);
same data file, DELETE vs DELETE: v2 SERIAL (two position delete files), v3 CONFLICT ("Found concurrently
added DV"); UPDATE vs DELETE: CONFLICT (conflicting delete files); DELETE/UPDATE vs expiry: SERIAL, no
snapshot resurrected; expiry vs DELETE: SERIAL; write whose scan snapshot an INSERT+expiry removed:
CONFLICT (IllegalArgumentException "not an ancestor"); DELETE vs retention: SERIAL (+ dangling DV on v3,
pinned); retention vs DELETE: SERIAL; UPDATE vs INSERT (row ids reassigned on retry): SERIAL, ids unique;
orphan removal via createApp (7 d default, --ttl 0 -> 1 d): in-flight files kept, SERIAL; orphan removal
TTL 0 via constructor: all in-flight files deleted, UPDATE commits, manifest list missing (pinned).
REST (SparkIrcConcurrencyTest, 12/12): same outcomes as native for the first three; the raced request is
refused by the requirement check ("branch main has changed").
- Draft pull request 130 opened on 31af4c8c; CI running. Full apps suite 1.12 running.

## Suites on 31af4c8c [ran]

apps 1.12: 377/0 failed/5 skipped (X10 34/0/0, X6 39/0/0); apps 1.5.2: 377/0/41 (X10 34/0/17, X6 39/0/19,
v3 cases skip); Spark 4.2 itest `test`: 79/0/0 (X10 REST 12/0/0); spotlessCheck apps + 4.2 itest: pass.
services/tables and internalcatalog not touched, not run. CI on 31af4c8c: 1.5.2 reference build success.
