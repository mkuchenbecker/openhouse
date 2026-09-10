# OPTIMIZE / ANALYZE clustering: tracking redesign

Durable record for this line of work. Goals, decisions (with who made them), open
questions, and status. Mike reviews nothing until the end and does not infer agreement
from silence: every decision below names its owner, and anything not listed as decided
is undecided.

_Session: claude/openhouse-optimization-prs-6brd0f. Started 2026-09-10._

## Goal

Replace the snapshot-id watermark in the OPTIMIZE / ANALYZE Spark SQL extensions with
two tracking mechanisms, both built:

- **A. Sequence watermark.** Track progress by Iceberg data sequence number, which
  lives in manifests and survives snapshot expiration.
- **B. Per-file layout stamp.** Persist the id/hash of the clustering configuration
  that wrote each file (Databricks ZCUBE style), in the manifest (`sort_order_id`) and
  in the file footer (ORC user metadata now, Parquet key-value metadata later).

Prerequisite: bring the mkuchenbecker/iceberg fork up to linkedin/iceberg, then make
the Iceberg changes there.

## Decisions

| # | Decision | Owner | Date |
|---|----------|-------|------|
| 1 | Both A and B get built. | Mike | 2026-09-10 |
| 2 | How and where any of this lands (upstream PRs #661/#662/#663, fork #28, ordering) is Mike's call. Claude builds and does not decide landing strategy. | Mike | 2026-09-10 |
| 3 | `sort_order_id` on data files is the per-file layout id. Mike's reasoning: the query planner consults file metadata, so this is legitimate file metadata. Fact recorded by Claude, not a counter-argument: no read or scan path in Iceberg 1.5 spark-3.5 reads `DataFile.sortOrderId()` (grepped `spark/v3.5/spark/src/main`, `core/src/main`); only parsers and copy constructors touch it. Consequence: a layout id that is not a registered table sort order is harmless to readers. | Mike | 2026-09-10 |
| 4 | No age floor. The "newest snapshot older than N minutes" gate from the original implementation is dropped, not carried. Claude had presented it as a requirement; it was never one. Work per run is bounded by the byte budget instead. | Mike | 2026-09-10 |
| 5 | Position-delete compaction (`rewrite_position_delete_files`) stays inside OPTIMIZE. Fork #28 had removed it. | Mike | 2026-09-10 |
| 6 | OpenHouse calls the Iceberg action API (`SparkActions.rewriteDataFiles`) directly from `OptimizeTableExec` instead of building `CALL ... system.rewrite_data_files(...)` strings. Mike declined to decide; Claude chose. Reversible. | Claude | 2026-09-10 |
| 7 | The original implementation and the reference material (upstream #662/#663, fork #28, Databricks docs) carry no authority. Anything kept from them is kept because it is right, not because it was there. | Mike | 2026-09-10 |
| 8 | The layout id is an int allocated by OpenHouse (CRC32 of `keys|mode`), with the config JSON in a table property. Reason: the OpenHouse tables service cannot register an additional Iceberg sort order after table creation (the client commits only the default sort order JSON, and the service's update path writes it to a table property). | Claude | 2026-09-10 |
| 9 | Alternative A watermarks on `data_sequence_number`, not `file_sequence_number`. A rewrite bumps `file_sequence_number` on its outputs to the new commit, so a watermark on it re-picks its own outputs. `data_sequence_number` is set by the commit manager and can be pinned to the max input sequence per file group. | Claude | 2026-09-10 |

| 10 | Both signals live in one mechanism instead of a per-table mode switch. Incremental selection is `data_seq > hwm AND stamp != current layout`; FULL is `stamp != current layout`. A "sequence only" mode and a "stamp only" mode select the same files in every case that can occur (a run's own output sits at or below the watermark; a foreign rewrite's output sits above it; an old layout's files sit below it), so a switch would be ceremony. ANALYZE reports both views. Reversible: the two signals are independent properties and options. | Claude | 2026-09-10 |
| 11 | Plain Spark writes record `sort_order_id = 0` (the unsorted order), not null. The Iceberg fork's `include-/exclude-sort-order-ids` treat the token `null` as "0 or null", and OpenHouse treats both as unstamped. | Claude (fact found by test) | 2026-09-10 |
| 12 | Layout ids start at 1000 (`MIN_LAYOUT_ID`) so they never collide with a table's registered sort order ids, which Iceberg allocates from 1. | Claude | 2026-09-10 |
| 13 | The byte budget (`optimize.cluster.max-bytes-per-run`) defaults to unbounded. Default value is an open question for Mike. | Claude, pending Mike | 2026-09-10 |
| 14 | The watermark after a run is read back from the stamps: `max(sequence_number)` over live files stamped with the current layout. Outputs carry the newest input's sequence, so this equals the highest sequence any run consumed, and a budget-truncated run advances only as far as it got. No Iceberg result-API change needed. | Claude | 2026-09-10 |
| 15 | VACUUM refuses the whole command, not only `REMOVE ORPHAN FILES`, on a table with `retention.backup.enabled` or an existing backup directory: since #447 the scheduled expiration job moves data files to backup too, and since #687 orphan deletion honors data manifests regardless of the flag. | Claude | 2026-09-10 |
| 16 | The scheduled compaction and DLO-strategy-execution tasks skip tables with `optimize.cluster.keys` (`TableMetadata.hasClusteringKeys`). Whether the job should instead run the clustered path is open. | Claude, pending Mike | 2026-09-10 |
| 17 | OpenHouse keeps `iceberg_1_5_version = 1.5.2.21` in the tree. The clustering path names the fork's rewrite options as string constants, compiles against 1.5.2.21, and fails at runtime with "Cannot use options" on it. Verification here ran against a locally published `1.5.2.22-SNAPSHOT` of the fork. The version bump is part of landing, which is Mike's. | Claude | 2026-09-10 |

| 18 | The merge-on-read statement test (`testOptimizeCompactsMergeOnReadDeletesAndKeepsRowsCorrect`) is `@Disabled`: the itest classpath carries both the Parquet shaded into `iceberg-spark-runtime` and the unshaded Parquet of `tables-test-fixtures`, and reading a position delete fails in `org.apache.iceberg.parquet.ReadConf` with a `ClassCastException` before OPTIMIZE runs. Position-delete compaction stays in OPTIMIZE (decision 5); the test needs a clean classpath, which is a test-infrastructure fix, not a product one. Fork #28 had deleted this test without saying why; this is probably why. | Claude | 2026-09-10 |

## Facts the design rests on (verified in source)

- OpenHouse defaults `cluster.iceberg.format-version` to 2. v1 tables have all-zero
  sequence numbers; A refuses them.
- linkedin/iceberg #189 (openhouse-1.5.2, commit 04d2cd2) already provides
  `rewrite-job-order=files-min-sequence-number-asc|desc` and
  `max-total-files-size-bytes`. Budgeted oldest-first rewriting exists.
- `RewriteDataFilesSparkAction.planFileGroups` filters files only by a data
  `Expression`. No metadata-level (sequence, sort-order-id) filter exists.
- `RewriteDataFilesCommitManager` sets output `data_sequence_number` to the starting
  snapshot's sequence when `use-starting-sequence-number=true`, otherwise the commit's.
- Spark rewrites write files with `sort_order_id = null`: `SparkWrite` builds
  `SparkFileWriterFactory` without `dataSortOrder`.
- `ORC.DataWriteBuilder.meta(k, v)` and `Parquet.DataWriteBuilder.meta(k, v)` write
  footer user metadata. `SparkFileWriterFactory.configureDataWrite` never calls them.
- `.entries` metadata table exposes `sequence_number` (data), `file_sequence_number`,
  `data_file.sort_order_id`, and `readable_metrics`. `.files` has `sort_order_id` but
  no sequence numbers.
- mkuchenbecker/iceberg `openhouse-1.5.2` = d1603c8; linkedin = 39df55d; five commits
  behind, all ORC default-value work. OpenHouse pins `iceberg_1_5_version = 1.5.2.21`.
- mkuchenbecker/openhouse `main` (59101d3) is 32 commits behind linkedin `main`
  (252478b) and predates the #660 merge. Work here is based on linkedin main.
- Since #660 merged upstream: #447 made the scheduled snapshot-expiration job delete
  files; #687 made orphan-file deletion move files to backup whenever a
  `data_manifest_*.json` exists, regardless of the backup flag; #708 delegates
  branch/tag retention to Iceberg via `history.expire.max-ref-age-ms`.

## Design

### State (table properties)

| Property | Meaning |
|----------|---------|
| `optimize.cluster.keys`, `optimize.cluster.sort-mode` | User config (unchanged) |
| `optimize.cluster.layout-id` | Current layout id (int; CRC32 of `keys|mode`) |
| `optimize.cluster.layout.<id>` | JSON `{keys, mode}` for every layout id ever used |
| `optimize.cluster.hwm-seq` | Highest data sequence number consumed under the current layout |
| `optimize.cluster.epochs` | JSON `[{layout, lowerSeq, upperSeq}]`, history across layout changes |
| `optimize.cluster.max-bytes-per-run` | Byte budget per OPTIMIZE run (default TBD) |

Removed: `optimize.cluster.hwm-snapshot-id`, `optimize.cluster.state`,
`optimize.cluster.min-snapshot-age-minutes`, `optimize.cluster.config-id`.

Migration: a live `hwm-snapshot-id` becomes `hwm-seq = snapshot.sequenceNumber()`;
an expired one becomes 0. Old `state` is dropped.

### OPTIMIZE

- No keys: bin-pack (unchanged), then position-delete compaction, then manifests if asked.
- Incremental: rewrite files where `data_seq > hwm-seq` and `sort_order_id` is null,
  sort/zorder by keys, oldest first, up to the byte budget. Outputs get
  `data_seq = max input seq` in their group and `sort_order_id = layout-id`, plus a
  footer stamp. `hwm-seq` advances to the max input sequence actually rewritten.
- FULL: rewrite files where `sort_order_id != layout-id` (null or stale), any sequence,
  same budget. Idempotent and resumable.
- Files stamped with an older layout are skipped by incremental and taken by FULL.
- Position-delete compaction runs after the data rewrite in every mode.
- v1 tables refused.

### ANALYZE

One aggregate over `.entries WHERE status < 2`:
covered = `sort_order_id = layout-id`; stale = other non-null; new = `seq > hwm`;
damaged = null and `seq <= hwm`. Depth stats unchanged. Tail = oldest uncovered
sequence, mapped to hours through the snapshots table, falling back to "older than the
oldest live snapshot".

### Iceberg fork changes (openhouse-1.5.2)

1. `RewriteDataFiles` options `min-data-sequence-number` (exclusive),
   `max-data-sequence-number` (inclusive), `exclude-sort-order-ids` (csv), applied as a
   task predicate in `planFileGroups`.
2. `output-sequence-number=max-input`: commit manager pins each group's output data
   sequence to its max input sequence.
3. `SparkWriteOptions.OUTPUT_SORT_ORDER_ID` (raw int) through `SparkWrite` to the
   writer factory; rewrite option `output-sort-order-id` feeds it.
4. Rewrite options `output-file-metadata.<k>=<v>` feed `builder.meta(k, v)` for ORC and
   Parquet in `SparkFileWriterFactory`.

### OpenHouse changes

- VACUUM (from fork #28): rebased on main; doc and backup-detection fixes for #447/#687.
- OPTIMIZE and ANALYZE as above.
- `DataCompactionSparkApp`: must not destroy a clustered layout. Minimum: skip tables
  with `optimize.cluster.keys`.

## Open questions (undecided until Mike answers)

- Landing strategy (decision 2 says it is Mike's; nothing assumed).
- Default byte budget per run.
- Whether the scheduled compaction job skips clustered tables or runs the clustered
  path itself.
- mkuchenbecker/openhouse `main` is behind linkedin `main`; a PR from the designated
  branch will carry the 32 upstream commits until fork main is synced. Claude will not
  push to fork `main` without permission.

## Status (2026-09-10, end of session)

Branches: mkuchenbecker/openhouse `claude/openhouse-optimization-prs-6brd0f` (based on
linkedin main 252478b) and mkuchenbecker/iceberg `claude/openhouse-optimization-prs-6brd0f`
(based on openhouse-1.5.2 39df55d, draft PR mkuchenbecker/iceberg#2).

- [x] PR0 fork sync: mkuchenbecker/iceberg `openhouse-1.5.2` fast-forwarded to linkedin 39df55d.
- [x] PR1+PR2 iceberg (one commit, `dafa841`): sequence bounds, sort-order-id include/exclude,
      use-max-input-sequence-number, output-sort-order-id, output-file-metadata.*.
      10 new tests pass; existing `TestRewriteDataFilesAction`, `TestSparkDataWrite`,
      `TestSparkFileWriterFactory`, `TestRewriteFileGroup` pass. Published locally as
      1.5.2.22-SNAPSHOT for the OpenHouse runs below.
- [x] PR3 VACUUM (`e7d4297`): rebased fork #28 onto linkedin main; #447/#687 semantics.
- [x] PR4 OPTIMIZE and PR5 ANALYZE (`3b312d9`): stamp + sequence watermark, migration, docs.
- [x] PR6 jobs (`e67c006`): compaction and DLO execution skip tables with clustering keys.
- [x] Tests: spark-3.5 runtime unit tests, apps scheduler/client tests, itest
      `statementTest` (VACUUM/OPTIMIZE/ANALYZE statement tests on a Hadoop catalog) and
      `catalogTest` (the three `*TestSpark3_5` suites against the embedded OpenHouse server)
      pass against the fork snapshot. Test-helper fixes were the only failures in the last runs.
- [ ] Landing: Mike's call (decision 2). Not done: upstream PRs #661/#662/#663 untouched,
      fork PR #28 untouched, OpenHouse `iceberg_1_5_version` still 1.5.2.21 (the clustered
      OPTIMIZE path needs the fork release), linkedin/iceberg PR not opened.
- [ ] mkuchenbecker/openhouse `main` is 32 commits behind linkedin `main`; the PR from the
      designated branch shows those commits until fork main is synced.

Local-only, uncommitted, in the sandbox checkout: `build.gradle` points
`iceberg_1_5_version` at 1.5.2.22-SNAPSHOT and adds `mavenLocal()`. Not part of any commit.
