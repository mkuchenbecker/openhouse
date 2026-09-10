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

## Status

See the bottom of this file; updated as work lands.

- [ ] PR0 fork sync (mkuchenbecker/iceberg)
- [ ] PR1 iceberg planning/commit options
- [ ] PR2 iceberg stamping
- [ ] PR3 VACUUM on main
- [ ] PR4 OPTIMIZE
- [ ] PR5 ANALYZE
- [ ] PR6 compaction job
- [ ] Tests pinning the design
