# Orphan Directory Deletion

Operator-facing guide for the `ORPHAN_DIRECTORY_DELETION` maintenance operation as driven by the
continuous Optimizer.

## What it does

Reclaims storage left behind by **dropped tables**. When a table is dropped, its data directory can
linger on the filesystem with no catalog entry pointing at it (an *orphan* directory). This operation
finds those directories and removes them in a safe two-phase manner:

1. **Stage** — files older than the orphan threshold are moved into a `.trash` subdirectory.
2. **Delete** — a directory that is already fully staged is hard-deleted once its staged files have
   sat in trash past the staged-delete grace window.

This is distinct from `ORPHAN_FILES_DELETION` (OFD), which removes unreferenced *files inside a live
table*. Orphan **directory** deletion removes whole directories of tables that no longer exist.

## When it runs

The operation is **database-scoped**, not per-table (a dropped table has no live row to key on). The
Optimizer drives it in three stages:

1. **Analyzer** (`OrphanDirectoryDeletionAnalyzer` + `DirectoryDeletionAnalyzerRunner`): once per
   analyzer run, enumerates the databases known to the Optimizer and, for each **opted-in** database
   whose cadence has elapsed, creates a `PENDING` database-scoped operation.
2. **Scheduler** (`SchedulerRunner.scheduleDirectory`): batches PENDING databases and launches one
   `ORPHAN_DIRECTORY_DELETION_BATCH` Spark job per bin, passing `--databaseNames`.
3. **Spark job** (`BatchedOrphanTableDirectoryDeletionSparkApp`): performs the stage/delete work and
   PATCHes SUCCESS/FAILED back to the Optimizer per operation.

**Cadence:** after a SUCCESS, a database is re-evaluated after `success-retry-hours` (default 24h);
after a FAILED run, after `failure-retry-hours` (default 1h). A database with an operation already in
flight (PENDING/SCHEDULING/SCHEDULED) is never double-queued.

> Note: per-database directory *discovery* inside the Spark job (deciding exactly which directories
> are orphaned) requires Tables-Service access and is the one piece still to be completed — see the
> "remaining piece" in [`DIRECTORY-DELETION-DESIGN.md`](../DIRECTORY-DELETION-DESIGN.md). Until then,
> a `--databaseNames` run fails fast rather than guessing; the app runs today with explicit
> `--tableDirectoryPaths`.

## Configuration

**Opt-in is required and off by default.** Enable per operation via the analyzer app's properties:

| Property | Default | Meaning |
|----------|---------|---------|
| `optimizer.analyzer.orphan-directory-deletion.enabled` | `false` | Master opt-in. When false, the analyzer emits nothing for this operation. |
| `optimizer.analyzer.orphan-directory-deletion.success-retry-hours` | `24` | Hours to wait after a SUCCESS before re-evaluating a database. |
| `optimizer.analyzer.orphan-directory-deletion.failure-retry-hours` | `1` | Hours to wait after a FAILED run before retrying. |
| `optimizer.scheduler.orphan-directory-deletion.max-databases-per-bin` | `25` | Max databases packed into a single launched Spark job. |

Spark-app knobs (defaults shown), passed through `jobs.yaml` / job args:

| Arg | Default | Meaning |
|-----|---------|---------|
| `--trashDir` | `.trash` | Subdirectory files are staged into before deletion. |
| `--orphanDaysOld` | `7` | Files this old are staged (phase 1). |
| `--stagedDeleteDaysOld` | `3` | Staged files this old are deleted, then the directory is removed (phase 2). |
| `--driverParallelism` | `1` | Worker threads processing the batch concurrently. |

Batch-size safety cap: `AppConstants.ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE` (200) bounds the
command-line argv.

## What the batched Spark app does

`BatchedOrphanTableDirectoryDeletionSparkApp` processes a batch of work targets on a fixed-size
worker pool, reporting each independently. It reuses the exact two-phase deletion logic of the
single-target `OrphanTableDirectoryDeletionSparkApp` (`Operations.deleteOrphanDirectory` then
`deleteStagedOrphanDirectory`) and emits `orphan_directory_count` / `staged_directory_count`
metrics. Per-operation SUCCESS/FAILED is PATCHed to the Optimizer when a `--resultsEndpoint` is
supplied.

## See also

- [`DIRECTORY-DELETION-DESIGN.md`](../DIRECTORY-DELETION-DESIGN.md) — the model decision, the schema
  change (nullable `table_uuid` + `operation_scope`), and why this is database-scoped.
- [`table-directory-deletion.md`](./table-directory-deletion.md) — the sibling drop-cleanup operation.
