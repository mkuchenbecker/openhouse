# X6: maintenance jobs on format v3 tables: record

Marks: [ran] = run in worktree /home/user/oh-x6 (branch irc/x6-maintenance-v3); [read] = read in code, not run.

## Bottom line

Every maintenance job that opens a table (compaction, snapshot expiry, orphan-file removal, retention,
staged-file removal, table stats, batched stats, data layout generation, sort stats) ran on a v3 table with
deletion vectors, row lineage and 12 snapshots, and lost no live data, with no change to any job [ran, 1.12
line]. Row ids and last-updated sequence numbers survive compaction. So this item adds tests only: no
production code changed, nothing on v2 changed, and no job refuses v3.

## State for a restart

- Branch irc/x6-maintenance-v3, pull request 125 (draft). Heads: 98123df7 (first report; CI green both jobs),
  then review round: 51d44ef9 pushed (backup pin, production compaction, non-vacuity, skip guard).
- X6 class at 51d44ef9 [ran]: 1.12 39/0/0; 1.5.2 39/0/19 skipped (the v3 cases). Full 1.12 suite at
  51d44ef9 [ran]: 343/0/5. Merged pull requests 126 (ddb4ad8b) and 127 (3bce6326, integration 91e175c4);
  3bce6326 pushed. Final full suites on 3bce6326, both lines: logs scratchpad/x6-final-112.log, -152.log.

## Review round (coordinator's five findings)

1. Retention with backup and delete files: CONFIRMED as read (Operations.java:526-533 lists task.file() only;
   147-168 moves any file under data/ whose backup partition has a data_manifest, deletes the rest).
   Test retentionBackupOfPartitionsWithDeleteFiles (v2/v3 x expiry/orphans), observed and pinned [ran]:
   (a) .backup holds all 6 data files of the 2 expired partitions; each partition's data_manifest_<ts>.json
   is {file_count, files} listing exactly those data files. Backed-up files hold ids 1-16, i.e. also the
   rows deleted before retention (2, 3, 6, 10, 14, 15) and old versions of updated rows 7 and 11; nothing
   records the deletes. v2: the position delete files are moved into .backup too (same partition dir) but
   are not listed. v3: Puffin files are written at data/ root, not in partition dirs, so the handler never
   moves them: a Puffin serving only expired partitions is deleted, so no deletion vector reaches the backup.
   No restore code in this repository; history (pull requests 384, 408, 418) names an external Uploader
   that finds partitions by data_manifest. (b) Spark writes one Puffin per write task, spanning partitions
   (shown: e.g. ...-707-...puffin holds DVs for ts_day=2026-11-14 expired and 2026-11-23 kept). It survives
   both paths (reachability by path), the kept partition's deleted rows stay deleted. No live-data loss, no
   fix. Options for (a) go to the coordinator (final report).
2. Production compaction arguments: CONFIRMED (TableDataCompactionTask.getArgs passes --tableName only;
   TableDataLayoutStrategyExecutionTask.getArgs never passes deleteFileThreshold). Reviewer's line
   reference is off: the strategy path is DataCompactionSparkApp.java:163-164 (file has 215 lines). Test
   compactionWithProductionArgumentsKeepsLiveRowsDeletesAndRowIds (table-only, layout-args, strategy-json;
   through createApp): on 1.12 each commits and rewrites 6 of 8 delete-carrying files on v2 and v3, DVs of
   rewritten files dropped, the other DVs unchanged; on 1.5.2 nothing is rewritten (pinned).
3. Non-vacuity: now asserted (rewritten files and their DVs gone, no dangling DV, partially-live Puffin before
   expiry, expiry/orphan removal delete data or delete files, seq number non-null, planted orphan listed).
   Strict compaction on v3: zero live delete files afterwards.
4. Skip guard: v3CasesAreNotSkippedOnTheDefaultLine, with system property openhouse.iceberg.line from
   apps/spark-3.5/build.gradle.
5. Not covered, for their own work item: concurrency (a job racing writers), HDFS / object stores, branches
   and tags, partition evolution on v3.

## Inventory [read]

All the jobs live in one project, `:apps:openhouse-spark-apps-1.5_2.12` (apps/spark-3.5), on Spark 3.5.2. The
default build uses the fork's Iceberg 1.12 (1.12.0-ohfork.a88f556c5067) on Java 17; `-PicebergLinkedIn` uses
LinkedIn's Iceberg 1.5.2. The project has one `test` task, and CI runs it on both lines (build-run-tests.yml,
iceberg-linkedin-build.yml). Job types come from JobConf and jobs.yaml.

| Job type | App, and the code it runs | v3 result |
| --- | --- | --- |
| DATA_COMPACTION, DATA_LAYOUT_STRATEGY_EXECUTION | DataCompactionSparkApp: Operations.rewriteDataFiles (Iceberg binPack) | passed untouched |
| SNAPSHOTS_EXPIRATION | SnapshotsExpirationSparkApp: expireSnapshots, metadata-only or with the delete/backup handler | passed untouched (both modes) |
| ORPHAN_FILES_DELETION, ORPHAN_FILES_DELETION_BATCH | OrphanFilesDeletionSparkApp, BatchedOrphanFilesDeletionSparkApp: deleteOrphanFiles | passed untouched |
| RETENTION | RetentionSparkApp: SQL DELETE, with backup manifests when enabled | passed untouched (backup on and off) |
| STAGED_FILES_DELETION | StagedFilesDeletionSparkApp: deletes files under <table>/.trash | passed untouched |
| TABLE_STATS_COLLECTION, TABLE_STATS_COLLECTION_BATCH | TableStatsCollectionSparkApp, BatchedTableStatsCollectionSparkApp | passed untouched |
| DATA_LAYOUT_STRATEGY_GENERATION | DataLayoutStrategyGeneratorSparkApp | passed untouched |
| SORT_STATS_COLLECTION | SortStatsCollectionSparkApp (a sample copy through spark_catalog, a property on the table) | passed untouched on 1.12; on 1.5.2 it fails on v2 too (parser), pinned |
| ORPHAN_DIRECTORY_DELETION | OrphanTableDirectoryDeletionSparkApp | not run on v3: format-independent [read] |
| TABLE_DIRECTORY_DELETION | no Spark app in this repository (task only) | n/a |
| REPLICATION | no app in this repository; X1 refuses v3 replica and replicated tables (X7) | n/a |

Orphan directory deletion: TablesClient.getOrphanTableDirectories selects a directory only when no table is
registered under its name, and the app moves or deletes the files without opening Iceberg metadata. A live
table, v2 or v3, is never selected [read].

## The tests

`apps/spark-3.5/src/test/java/com/linkedin/openhouse/jobs/spark/FormatV3MaintenanceTest.java` (28 cases) and
`FormatV3TablesServer.java`, a second embedded tables service with `cluster.iceberg.format-v3.enabled=true`.
v3 tables live on that service, reached through a `newSession()` whose `openhouse` and `default_iceberg`
catalogs point at it. v2 tables live on the default service (switch off, as in production).

The fixture is a table partitioned by days(ts) and merge-on-read for delete, update and merge, with 4
partitions x 2 INSERTs (8 data files), a DELETE touching every file, a second DELETE touching one file (on v3 it
replaces one deletion vector, so the first Puffin files stay live for the others), an UPDATE and a MERGE (update,
delete, insert): 12 snapshots. The setup asserts the format version, Puffin deletion vectors in more than one
file (v3), no Puffin on v2, and a `_row_id` on every row (v3).

Checks after each job: live rows equal with full contents (`SELECT *`); the 10 deleted ids stay deleted;
`_row_id` and `_last_updated_sequence_number` equal per row (v3); time travel (`VERSION AS OF`) to every
retained snapshot reads as before; and every file a remaining snapshot references exists (manifest lists,
manifests, every data and delete file entry in them, statistics files, the current metadata file).

| Test | Cases | Job specifics |
| --- | --- | --- |
| compactionKeepsLiveRowsDeletesAndRowIds | v2/v3 x orc/parquet | minInputFiles 2, deleteFileThreshold 1; asserts a `replace` snapshot and fewer data files |
| snapshotExpiryWithFileDeletionKeepsWhatRetainedSnapshotsNeed | v2, v3 | deleteFiles, keep 2 versions; asserts exactly the last 2 remain and the expired-only files are gone |
| metadataOnlyExpiryThenOrphanRemovalKeepsWhatRetainedSnapshotsNeed | v2, v3 | metadata-only expiry, keep 3, then orphan removal with a 0 TTL |
| orphanFileRemovalRemovesOnlyPlantedOrphans | v2/v3 x single/batched | orphans planted next to a live Puffin (.puffin, .orc) and in metadata/; TTL 0 |
| deleteOrphanFilesNeverListsALiveFile | v2, v3 | the Iceberg action's orphan list holds no referenced file |
| retentionDeletesOnlyExpiredPartitionsAndKeepsRowIds | v2/v3 x backup on/off | 3 days: ids 1-16 gone, the rest identical, row ids kept |
| stagedFileRemovalTouchesOnlyTheTrashDirectory | v2, v3 | a 2-day-old file in .trash removed |
| tableStatsCollectionReadsAV3TableAndChangesNothing | v2, v3 | stats not null, 12 snapshots, events non-empty |
| batchedTableStatsCollectionPublishesAV3TableAndChangesNothing | v2, v3 | all four sinks receive the table |
| dataLayoutStrategyGenerationReadsAV3TableAndChangesNoData | v2, v3 | strategy property written |
| sortStatsCollectionReadsAV3TableAndChangesNoData | v2, v3 | spark_catalog pointed at OpenHouse; property written |

## Run history [ran]

1. First run: 24/24 failed in the fixture (I counted 13 snapshots; there are 12). Fixed in the test only.
2. Second run: 24/24 passed, no job changed.
3. Added batched stats and sort stats. The sort stats fixture first failed (it needs the `header.memberId` /
   `header.time` columns the app sorts by, and my session copy left out the openhouse keys); both are test
   fixes. Then 28/28 passed.

## Decisions (two-way doors)

- Test the jobs through `runInner` + `runValidations` with a null state manager, not `run()`: `run()` builds
  its own SparkSession and calls the jobs service; the job logic is identical.
- v2 cases run on the default service (switch off), not on the v3 service, so they show production v2 behaviour.
- Orphan TTL 0 and expiry with no age margin: the strictest case (every live file is old enough to be deleted
  if it were misclassified).
- Compaction with deleteFileThreshold 1, so the files carrying deletion vectors are rewritten.
- v3 cases skip on the 1.5.2 line by an assumption that probes `MetadataColumns.ROW_ID`.
- No refusal added: no job failed on v3.

## Not verified

- Jobs built on the 1.5.2 line against a v3 table (a mixed deployment): the 1.5.2 TableMetadataParser carries
  "Cannot read unsupported version %s" [read from the class file], so it would fail before acting. Not run.
- Real HDFS / object storage, Livy, the scheduler's selection and the jobs service: local FS only.
- Statistics files: the native tables service does not store them, so none exist to check; the check covers
  them if present.
- Equality deletes (Spark does not write them), branches and tags, partition evolution on v3, Spark 4.2 jobs
  (there are none), DATA_LAYOUT_STRATEGY_EXECUTION with a strategy JSON (the same app and path as compaction).
- Whether compaction leaves dangling deletion vectors (that would not be data loss; not asserted).
- Stats on v3 count each deletion vector as a position delete file and add the Puffin size once per vector, which
  over-reports sizes. That is reporting only, not asserted.
