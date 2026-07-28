# Data Compaction

Operator-facing reference for the Optimizer's `DATA_COMPACTION` operation.

## What it does

Data compaction rewrites a table's data files into fewer, larger, target-sized
files. Over time a table accumulates many small data files (frequent commits,
streaming ingestion, partition churn), which hurts read performance — every
query pays to open and plan across all those files — and bloats Iceberg
metadata. Compaction bin-packs the small files together and rewrites them into
files close to a configured target size, improving scan efficiency and reducing
file/metadata overhead.

Under the hood the batched Spark app runs Iceberg's `rewriteDataFiles` (bin-pack
strategy) per table with the standard OpenHouse compaction parameters (target
byte size, min/max byte-size ratios, min input files, concurrency, partial
progress, delete-file threshold).

## When it runs (stats-driven trigger)

Unlike purely time-based maintenance (e.g. orphan-files deletion), compaction is
only worth its substantial compute cost when the table's file layout actually
warrants it. The analyzer (`StatsBasedDataCompactionAnalyzer`) is therefore
**stats-driven**: it inspects the table's current snapshot metrics and schedules
a run only when the layout is fragmented.

A table is scheduled for compaction when **all** of the following hold:

1. **Opt-in.** The table sets `maintenance.optimizer.dataCompaction.enabled=true`
   in its table properties. Without this flag the analyzer ignores the table
   entirely.

2. **No active operation in flight, and the cadence floor has elapsed.** If the
   table already has a non-CANCELED operation row (PENDING, SCHEDULING, or
   SCHEDULED) the scheduler already owns it and the analyzer stays out (a
   CANCELED row does not block). Otherwise the decision uses the most recent
   completed run:
   - no prior history → eligible immediately;
   - last run `SUCCESS` → wait `dataCompaction.success-retry-hours` after its
     completion before re-evaluating;
   - last run `FAILED` → wait only `dataCompaction.failure-retry-hours` before
     retrying.

   This floor keeps a just-compacted — but still marginally fragmented — table
   from being re-picked on the very next analyzer pass.

3. **The file layout warrants compaction.** Evaluated from the table's latest
   snapshot metrics:
   - the table has at least `dataCompaction.min-files` current data files — a
     handful of files is never worth a rewrite, regardless of their size; **and**
   - the **average** file size (`tableSizeBytes / numCurrentFiles`) is below
     `dataCompaction.target-file-size-bytes` — i.e. the files are, on average,
     smaller than the target and there is real fragmentation to reclaim.

   A table with many but already-target-sized files is left alone (nothing to
   gain); a table with only a few files is left alone (not worth the compute).

### How the analyzer reads stats

The analyzer reads the metrics straight off `TableDto.getStats().getSnapshot()`.
The analysis loop builds each `TableDto` from the current-state `table_stats`
row (`TableDto.fromRow`), which already populates the snapshot — so no extra
stats-repository lookup is needed, and the analyzer sees the same
`SnapshotMetrics` (`numCurrentFiles`, `tableSizeBytes`) the scheduler's
bin-packer uses.

If a table has **no snapshot metrics**, the analyzer cannot assess the layout and
**skips** the table (no operation is scheduled).

## Configuration

### Trigger and cadence (analyzer app: `application.properties`)

| Property | Default | Controls |
| --- | --- | --- |
| `maintenance.optimizer.dataCompaction.enabled` (per-table property) | `false` | Opt-in. Only tables with this set to `true` are ever considered. |
| `dataCompaction.min-files` | `100` | Minimum current data-file count before compaction is even considered. Gates out trivially-small tables. |
| `dataCompaction.target-file-size-bytes` | `536870912` (512 MiB) | Target per-file size. A table whose **average** file size is below this is considered fragmented and eligible. |
| `dataCompaction.success-retry-hours` | `24` | Time floor after a successful compaction before the table is re-evaluated. |
| `dataCompaction.failure-retry-hours` | `1` | Time floor after a failed compaction before retrying (shorter than success so transient failures recover quickly). |

### Bin-pack caps (scheduler app: `application.properties`)

The scheduler groups the PENDING compaction operations into batches with a
first-fit-decreasing bin packer, then launches one Spark job per bin.

| Property | Default | Controls |
| --- | --- | --- |
| `optimizer.scheduler.dataCompaction.max-bytes-per-bin` | `5497558138880` (5 TiB) | Maximum total `tableSizeBytes` per batch. Bounds a single job's data volume. |
| `optimizer.scheduler.dataCompaction.max-tables-per-bin` | `25` | Maximum number of tables per batch. |

There is also a hard footgun ceiling, `DATA_COMPACTION_MAX_BATCH_SIZE = 100`,
enforced when the batched app parses its table list; it is a safety stop, not the
operating point (tune the operating point with the per-bin caps above).

### Why byte-weighted bin packing

Bins are weighted on **`tableSizeBytes`**, not file count. Compaction reads,
re-sorts, and rewrites **every byte** of each table, so its Spark cost — shuffle,
I/O, and commit time — scales with data volume. A bytes-per-bin cap therefore
bounds a batch's true work far better than a file-count cap would: a file-count
cap could let a bin of a few very large tables blow past the driver's budget,
while a bin of many tiny-file tables would stay trivially cheap. (Orphan-files
deletion, by contrast, is weighted on file count, because its cost is dominated
by per-file list/manifest/delete calls independent of file size.)

## What the batched Spark app does

`BatchedDataCompactionSparkApp` processes the list of `(table, operationId)`
pairs the scheduler bin-packed into one batch. Each table is handled by a worker
thread; per-table failures are caught and reported independently, so the job
continues for the remaining tables and exits successfully if at least one table
succeeds.

For each table the worker:

1. Runs Iceberg `rewriteDataFiles` (bin-pack) with the configured compaction
   parameters — the same call the single-table `DataCompactionSparkApp` makes.
2. Emits metrics: added data-file count, rewritten data-file count, rewritten
   bytes, and rewritten file-group count.
3. **Reports the result back** to the Optimizer Service via a per-operation
   `SUCCESS`/`FAILED` PATCH callback (operation type `DATA_COMPACTION`). If the
   callback exhausts retries, the operation row is left `SCHEDULED` so the
   analyzer's stale-timeout can re-queue it.
