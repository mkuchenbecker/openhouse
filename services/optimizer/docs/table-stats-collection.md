# TABLE_STATS_COLLECTION

Operator-facing reference for the `TABLE_STATS_COLLECTION` maintenance operation
in the OpenHouse Optimizer.

## What it does

`TABLE_STATS_COLLECTION` collects Iceberg table statistics for a table — file
counts, file sizes, and snapshot information — and records them so the rest of
the optimizer can reason about the table.

It is the **input** the stats-driven analyzers consume: the numbers this
operation produces (for example total file count per table) are what the
scheduler's bin-packer and the other cadence/threshold analyzers read when
deciding what work to schedule and how to group it into batches. Because it
produces those `table_stats` rows rather than reading them, it does not itself
depend on stats to decide when to run — it is purely cadence-driven.

Per table, the batched Spark app (`BatchedTableStatsCollectionSparkApp`):

1. Loads the table and collects Iceberg table-level stats.
2. Collects commit events, partition-level commit events, and partition-level
   stats.
3. Publishes each of the above (skipping any section that came back empty, e.g.
   partition data for an unpartitioned table).

Each table in a batch is processed by its own worker thread; a failure on one
table is caught, logged, and reported back independently — the job continues for
the remaining tables and the per-operation result (SUCCESS/FAILED) is reported
to the Optimizer Service.

## When it runs

The analyzer (`CadenceBasedTableStatsCollectionAnalyzer`) schedules a run for a
table only when **all** of the following hold:

1. **Opt-in.** The table sets
   `maintenance.optimizer.tableStatsCollection.enabled=true` in its table
   properties. This is the only eligibility gate — there is no table-type or
   other restriction. Without the flag, the analyzer ignores the table entirely.
2. **No active operation already in flight.** If the table already has a
   non-CANCELED operation row (PENDING, SCHEDULING, or SCHEDULED), the scheduler
   owns it and the analyzer stays out. A CANCELED row does not block.
3. **Cadence elapsed since the last completed run.**
   - No prior history → schedule immediately.
   - Last run SUCCESS → wait `tableStatsCollection.success-retry-hours` before
     scheduling again.
   - Last run FAILED → wait `tableStatsCollection.failure-retry-hours` before
     retrying.

Because it feeds the other analyzers, keep this operation opted-in (and its
cadence fresh) on any table where you want the stats-driven maintenance
operations to make good decisions.

## Configuration

### Opt-in (per table)

| Property | Meaning |
| --- | --- |
| `maintenance.optimizer.tableStatsCollection.enabled` | Set to `true` on a table to opt it into table-stats collection. |

### Cadence (analyzer app: `apps/optimizer/analyzerapp` `application.properties`)

| Key | Default | Meaning |
| --- | --- | --- |
| `tableStatsCollection.success-retry-hours` | `20` | Hours to wait after a successful run before re-evaluating. Below 24h so a daily refresh is guaranteed within any rolling 24-hour window regardless of when the prior run landed. |
| `tableStatsCollection.failure-retry-hours` | `1` | Hours to wait after a failed run before retrying — shorter than the success interval so transient failures recover quickly. |

### Bin caps (scheduler app: `apps/optimizer/schedulerapp` `application.properties`)

The scheduler groups eligible tables into batches with a first-fit-decreasing
bin-packer keyed on file count.

| Key | Default | Meaning |
| --- | --- | --- |
| `optimizer.scheduler.tableStatsCollection.max-files-per-bin` | `1000000` | Max total file count across all tables in one batch; `0` disables this dimension. |
| `optimizer.scheduler.tableStatsCollection.max-tables-per-bin` | `50` | Max number of tables in one batch; `0` disables this dimension. |

### Batch-size hard cap

`AppConstants.TABLE_STATS_COLLECTION_MAX_BATCH_SIZE` (`200`) is a footgun stop on
the number of tables a single batched job can carry on the command line — not the
operating point. The operating batch size is tuned scheduler-side via the bin
caps above.

## Dispatch

The optimizer launches the multi-table batched Spark app. The scheduler maps the
operation to the `TABLE_STATS_COLLECTION_BATCH` Jobs-service JobType, which
`jobs.yaml` routes to `BatchedTableStatsCollectionSparkApp`.
