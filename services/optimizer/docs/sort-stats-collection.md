# SORT_STATS_COLLECTION

Operator-facing reference for the `SORT_STATS_COLLECTION` maintenance operation
in the OpenHouse Optimizer.

## What it does

`SORT_STATS_COLLECTION` estimates how much a table would benefit from sorting by
**sampling** its data and measuring the resulting compression. It does not sort
the real table; it works on a temporary sample so the estimate is cheap and
non-destructive.

Per table, the batched Spark app (`BatchedSortStatsCollectionSparkApp`):

1. Samples the table's average record size and computes how many rows are needed
   to fill a target file (512 MB).
2. Copies up to that many rows from a recent date partition into a temporary
   sample table.
3. Measures the sample's total data-file size, then runs a sort rewrite
   (`rewrite_data_files` with the sort strategy) on the sample.
4. Measures the size again, computes the compression rate, and stores it on the
   table as the `sort-compression-rate` table property.
5. Drops the temporary sample table (always, even on failure).

Each table in a batch is processed by its own worker thread; a failure on one
table is caught, logged, and reported back independently — the job continues for
the remaining tables and the per-operation result (SUCCESS/FAILED) is reported
to the Optimizer Service.

## When it runs

The analyzer (`CadenceBasedSortStatsCollectionAnalyzer`) schedules a run for a
table only when **all** of the following hold:

1. **Opt-in.** The table sets
   `maintenance.optimizer.sortStatsCollection.enabled=true` in its table
   properties.
2. **Primary table.** The table must be a primary (non-replica) table, because
   the sort rewrite targets the primary copy. Primary is defined as **not
   replica**: the check reads the `openhouse.tableType` table property and treats
   only an explicit `REPLICA_TABLE` value as a replica. An **absent** property
   therefore defaults to primary — matching `metadata.isPrimary()` and the rest
   of the codebase. Replicas are skipped.
3. **No active operation already in flight.** If the table already has a
   non-CANCELED operation row (PENDING, SCHEDULING, or SCHEDULED), the scheduler
   owns it and the analyzer stays out. A CANCELED row does not block.
4. **Cadence elapsed since the last completed run.**
   - No prior history → schedule immediately.
   - Last run SUCCESS → wait `sortStatsCollection.success-retry-hours` before
     scheduling again.
   - Last run FAILED → wait `sortStatsCollection.failure-retry-hours` before
     retrying.

## Configuration

### Opt-in (per table)

| Property | Meaning |
| --- | --- |
| `maintenance.optimizer.sortStatsCollection.enabled` | Set to `true` on a table to opt it into sort-stats collection. |
| `openhouse.tableType` | Table type. `REPLICA_TABLE` marks a replica (skipped). Any other value — or the property being absent — is treated as primary and is eligible. |

### Cadence (analyzer app: `apps/optimizer/analyzerapp` `application.properties`)

| Key | Default | Meaning |
| --- | --- | --- |
| `sortStatsCollection.success-retry-hours` | `20` | Hours to wait after a successful run before re-evaluating. Below 24h so a daily refresh is guaranteed within any rolling 24-hour window regardless of when the prior run landed. |
| `sortStatsCollection.failure-retry-hours` | `1` | Hours to wait after a failed run before retrying — shorter than the success interval so transient failures recover quickly. |

### Bin caps (scheduler app: `apps/optimizer/schedulerapp` `application.properties`)

The scheduler groups eligible tables into batches with a first-fit-decreasing
bin-packer keyed on file count.

| Key | Default | Meaning |
| --- | --- | --- |
| `optimizer.scheduler.sortStatsCollection.max-files-per-bin` | `1000000` | Max total file count across all tables in one batch; `0` disables this dimension. |
| `optimizer.scheduler.sortStatsCollection.max-tables-per-bin` | `50` | Max number of tables in one batch; `0` disables this dimension. |

### Batch-size hard cap

`AppConstants.SORT_STATS_COLLECTION_MAX_BATCH_SIZE` (`200`) is a footgun stop on
the number of tables a single batched job can carry on the command line — not the
operating point. The operating batch size is tuned scheduler-side via the bin
caps above.

## Dispatch

The optimizer launches the multi-table batched Spark app. The scheduler maps the
operation to the `SORT_STATS_COLLECTION_BATCH` Jobs-service JobType, which
`jobs.yaml` routes to `BatchedSortStatsCollectionSparkApp`.
