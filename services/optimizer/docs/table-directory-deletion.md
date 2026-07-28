# Table Directory Deletion

Operator-facing guide for the `TABLE_DIRECTORY_DELETION` maintenance operation as driven by the
continuous Optimizer.

## What it does

Purges the **storage directory of dropped/purged tables**. Where `ORPHAN_DIRECTORY_DELETION` stages
then reclaims *orphan* directories cautiously (age-gated, because it inferred they were abandoned),
`TABLE_DIRECTORY_DELETION` handles directories the system already knows belong to dropped tables and
removes them outright: any staged trash is cleared and the directory tree is deleted.

## When it runs

The operation is **database-scoped**. The reference implementation runs per database and lets the job
identify the dropped-table directories within it; the Optimizer models it the same way:

1. **Analyzer** (`TableDirectoryDeletionAnalyzer` + `DirectoryDeletionAnalyzerRunner`): enumerates the
   databases known to the Optimizer and creates a `PENDING` database-scoped operation for each
   **opted-in** database whose cadence has elapsed.
2. **Scheduler** (`SchedulerRunner.scheduleDirectory`): batches PENDING databases and launches one
   `TABLE_DIRECTORY_DELETION_BATCH` Spark job per bin, passing `--databaseNames`.
3. **Spark job** (`BatchedTableDirectoryDeletionSparkApp`): purges the directories and PATCHes
   SUCCESS/FAILED back per operation.

**Cadence:** re-evaluated `success-retry-hours` (default 24h) after a SUCCESS, `failure-retry-hours`
(default 1h) after a FAILED run; a database with an operation in flight is never double-queued.

> Note: `TABLE_DIRECTORY_DELETION` had **no** single-target reference Spark app; this batched app is
> new. Per-database discovery of *which* dropped-table directories to purge requires Tables-Service
> access and is the one remaining piece (see
> [`DIRECTORY-DELETION-DESIGN.md`](../DIRECTORY-DELETION-DESIGN.md)). Until it lands, a
> `--databaseNames` run fails fast rather than guessing; the app runs today with explicit
> `--tableDirectoryPaths`.

## Configuration

**Opt-in is required and off by default.**

| Property | Default | Meaning |
|----------|---------|---------|
| `optimizer.analyzer.table-directory-deletion.enabled` | `false` | Master opt-in. When false, the analyzer emits nothing for this operation. |
| `optimizer.analyzer.table-directory-deletion.success-retry-hours` | `24` | Hours to wait after a SUCCESS before re-evaluating a database. |
| `optimizer.analyzer.table-directory-deletion.failure-retry-hours` | `1` | Hours to wait after a FAILED run before retrying. |
| `optimizer.scheduler.table-directory-deletion.max-databases-per-bin` | `25` | Max databases packed into a single launched Spark job. |

Spark-app knobs:

| Arg | Default | Meaning |
|-----|---------|---------|
| `--trashDir` | `.trash` | Trash subdirectory cleared before the directory tree is deleted. |
| `--driverParallelism` | `1` | Worker threads processing the batch concurrently. |

Batch-size safety cap: `AppConstants.TABLE_DIRECTORY_DELETION_MAX_BATCH_SIZE` (200).

## What the batched Spark app does

`BatchedTableDirectoryDeletionSparkApp` processes a batch on a fixed-size worker pool. For each
dropped-table directory it calls `Operations.deleteStagedOrphanDirectory` (clear staged trash, then
delete the tree) and emits a `staged_directory_count` metric. Per-operation SUCCESS/FAILED is PATCHed
to the Optimizer when a `--resultsEndpoint` is supplied.

## See also

- [`DIRECTORY-DELETION-DESIGN.md`](../DIRECTORY-DELETION-DESIGN.md) — the model decision and schema
  rationale.
- [`orphan-directory-deletion.md`](./orphan-directory-deletion.md) — the sibling orphan-directory
  operation.
