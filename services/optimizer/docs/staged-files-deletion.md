# Staged Files Deletion (SFD)

Operator-facing guide to the continuous-optimizer integration for Staged
Files Deletion. This is the multi-table, results-aware counterpart of the
single-table `StagedFilesDeletionSparkApp`, wired into the same
analyze -> schedule -> bin-pack -> batched-launch -> report pipeline as
Orphan Files Deletion (OFD).

## What it does

SFD removes files left behind by staged or aborted writes that were never
committed. For each table it deletes files under

```
<table location>/<trashDir>
```

(default `<table location>/.trash`) whose modification time is older than a
day threshold (`daysOld`, default 3 days). Deletion is best-effort per file:
a file that fails to delete is logged and skipped, and the run continues.
The number of files deleted per table is emitted as the `staged_file_count`
metric, tagged with the table name.

SFD only ever touches the staging/trash directory; it never deletes live
data files or table metadata.

## When it runs

Scheduling is decided per table by `CadenceBasedStagedFilesDeletionAnalyzer`.
A table is scheduled only when **all** of the following hold:

1. **Opt-in.** The table sets

   ```
   maintenance.optimizer.stagedFilesDeletion.enabled = true
   ```

   in its table properties. Without this flag the analyzer ignores the table
   entirely. This is the *only* eligibility gate — there is no additional
   per-table condition (the legacy `TableStagedFilesDeletionTask.shouldRunTask()`
   returns `true` unconditionally, so nothing beyond opt-in is ported).

2. **No active operation already in flight.** If the table already has a
   non-CANCELED operation row (`PENDING`, `SCHEDULING`, or `SCHEDULED`), the
   scheduler already owns it and the analyzer stays out. A `CANCELED` row does
   not block — it is treated as if no operation exists.

3. **Cadence elapsed since the last completed run.**
   - No prior history -> schedule immediately.
   - Last run `SUCCESS` -> wait `stagedFilesDeletion.success-retry-hours`
     (default 16h) after its `completedAt` before scheduling again. The
     default is deliberately below 24h so that even a run landing at an
     unlucky time of day is re-evaluated at least once in any rolling
     24-hour window.
   - Last run `FAILED` -> wait `stagedFilesDeletion.failure-retry-hours`
     (default 1h) before retrying — shorter than the success interval so
     transient failures recover quickly.

Once eligible tables are collected, the scheduler bin-packs their pending
operations and launches one batched Spark job per bin.

## Configuration

### Opt-in (per table, table properties API)

| Property | Default | Meaning |
| --- | --- | --- |
| `maintenance.optimizer.stagedFilesDeletion.enabled` | (unset = off) | Set to `true` to opt the table into SFD. |

### Analyzer cadence (`analyzerapp` application.properties)

| Property | Default | Meaning |
| --- | --- | --- |
| `stagedFilesDeletion.success-retry-hours` | `16` | Hours to wait after a successful run before re-scheduling. |
| `stagedFilesDeletion.failure-retry-hours` | `1` | Hours to wait after a failed run before retrying. |

### Scheduler bin caps (`schedulerapp` application.properties)

Bins are packed with a `FirstFitDecreasingBinPacker` over `TotalFilesBinItem`
— file count is the cost driver, since SFD is a per-file list + delete
workload. Both caps apply per bin; `0` disables that dimension.

| Property | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `optimizer.scheduler.sfd.max-files-per-bin` | `SCHEDULER_SFD_MAX_FILES_PER_BIN` | `1000000` | Max total files across all tables in one bin. |
| `optimizer.scheduler.sfd.max-tables-per-bin` | `SCHEDULER_SFD_MAX_TABLES_PER_BIN` | `50` | Max tables in one bin. |

A separate hard ceiling, `AppConstants.SFD_MAX_BATCH_SIZE` (200), caps the
number of tables a single batched job may carry. It is a footgun stop
enforced at CLI-parse time, not the operating point — tune the operating
point with the scheduler bin caps above.

### Batched Spark app CLI options (`BatchedStagedFilesDeletionSparkApp`)

Batch identity / routing options (parallel CSV lists, one element per table):

| Option | Meaning |
| --- | --- |
| `--tableNames` | Comma-separated fully-qualified table names (`db.table`). Required. |
| `--operationIds` | Comma-separated operation UUIDs, parallel to `--tableNames`. |
| `--tableUuids` | Comma-separated table UUIDs, parallel to `--tableNames`. |
| `--resultsEndpoint` | Base URL of the Optimizer Service the app reports results back to. Required. |
| `--driverParallelism` | Worker threads processing tables concurrently (default `1`). |

Deletion-behavior options (mirror the single-table app's defaults):

| Option | Alias | Default | Meaning |
| --- | --- | --- | --- |
| `--trashDir` | `-b` | `.trash` | Directory under each table's location to delete staged files from. |
| `--daysOld` | `-o` | `3` | Minimum age in days a file must be before it is deleted. |
| `--recursive` | `-r` | `true` | Recurse into subdirectories of `<trashDir>`. |

## What the batched Spark app does

The scheduler launches one `BatchedStagedFilesDeletionSparkApp` job per bin.
The bin's tables arrive as the parallel `--tableNames/--operationIds/--tableUuids`
CSV lists. The app:

1. Parses the lists into per-table `(fqtn, operationId, tableUuid)` entries,
   enforcing `SFD_MAX_BATCH_SIZE` at parse time.
2. Runs a fixed-size worker pool (`--driverParallelism` threads). Each worker
   handles one table: it resolves `<table location>/<trashDir>` and deletes
   files older than `daysOld` (recursively when `--recursive`), then emits
   `staged_file_count` for that table.
3. Reports each operation's outcome **independently** back to the Optimizer
   Service (`--resultsEndpoint`) via a per-operation `UpdateOperationRequest`
   update carrying `operationType = STAGED_FILES_DELETION` and status
   `SUCCESS` or `FAILED`. A per-table exception marks only that operation
   `FAILED`; the job continues for the remaining tables.
4. Exits 0 if at least one table succeeded; exits non-zero only when every
   table in the batch failed.

If `--resultsEndpoint` is absent (the legacy `JobsScheduler` path), the
per-operation callback is skipped and lifecycle is tracked via HouseTables
instead. When a result update exhausts its retries, the operation row is left
`SCHEDULED` so the analyzer's stale-timeout can re-queue it.

## Dispatch wiring

The scheduler derives the Jobs-service job type from the operation via
`OperationTypeDto.toJobType()`, which suffixes `_BATCH` — so SFD launches as
`STAGED_FILES_DELETION_BATCH`. That job type is registered in
`JobConf.JobType` and mapped to `BatchedStagedFilesDeletionSparkApp` in the
jobs-service app catalog (`jobs.yaml`).
