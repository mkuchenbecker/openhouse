# RETENTION (Optimizer maintenance operation)

Operator-facing reference for the RETENTION operation as integrated into the
continuous OpenHouse Optimizer. It mirrors the Orphan-Files-Deletion (OFD)
slice; this doc describes only the behavior that is specific to retention.

## What it does

RETENTION deletes rows that have aged out of a table's data-retention window.
Each table configures a retention **policy** — a time column plus a TTL
expressed as `count` × `granularity` (e.g. keep the last `3 day`s). A retention
run deletes every row whose retention time column is older than
`now - count·granularity`.

- The retention column may be a real timestamp/date column or a `String` column,
  in which case a `columnPattern` (a `DateTimeFormat`) is used to parse it.
- The table does **not** have to be time-partitioned, but the retention column
  must be a time column.
- If the table's `retention.backup.enabled` property is `true`, the deleted data
  files are first backed up under `backupDir` before the delete is applied.

This is the same delete logic the single-table `RetentionSparkApp` performs; the
optimizer path just runs it continuously and in batches.

## When it runs

For each table on every analyzer pass, RETENTION is scheduled only if **all** of
the following hold. The first two are evaluated by
`CadenceBasedRetentionAnalyzer.isEnabled(...)`; the last two by the shared
`CadencePolicy`.

### 1. Opt-in

The table must set the table property:

```
maintenance.optimizer.retention.enabled = true
```

Without this flag the analyzer ignores the table entirely.

### 2. Retention-eligibility gate

Even when opted in, a table is scheduled only if it is genuinely
retention-eligible. The authoritative jobs-side rule lives in
`TableRetentionTask.shouldRunTask()` /`TablesClient.getTableRetention()`:

> primary table **and** a configured retention policy **and** a resolvable time
> column — either a time-partitioning spec **or** an explicit string
> `retention.columnPattern`.

The optimizer's `TableDto` only carries the table's property map (plus size /
file-count stats). OpenHouse serializes the table's policies into the preserved
`policies` table property, so the retention policy and its optional
`columnPattern` **are** visible to the analyzer. The gate therefore parses the
`policies` property and admits the table iff it contains a `retention` block that
carries a resolvable string time column, i.e.:

```json
{"retention": {"count": 3, "granularity": "DAY",
               "columnPattern": {"columnName": "datepartition",
                                 "pattern": "yyyy-MM-dd"}}}
```

**Scheduled vs skipped (from the analyzer's view):**

| Table state (opted in)                                     | Scheduled? |
| ---------------------------------------------------------- | ---------- |
| `policies.retention` with a `columnPattern.columnName`     | Yes        |
| `policies.retention` but no `columnPattern` (string col)   | No         |
| `policies` present but no `retention` block (unset)        | No         |
| No `policies` property / blank / unparseable               | No         |

This is a strict **subset** of the jobs-side rule: every table the analyzer
admits is genuinely eligible, so it never schedules a wasted or failing retention
job (no false positives).

**Two deferred signals (documented gap).** Two jobs-side signals are not modeled
on `TableDto` and cannot be evaluated in the analyzer:

- **Time-partitioning.** A time-partitioned table qualifies even without a
  `columnPattern` (its retention column is the partition column), but the Iceberg
  partition spec is not a table property and is absent from the DTO. Such tables
  are conservatively **skipped** by the analyzer today — a false negative.
- **Primary vs. replica table type.** `openhouse.tableType` is not guaranteed on
  the stats DTO, so the primary-only guard is likewise not evaluated here.

Closing this gap requires plumbing a partitioning / retention-column signal (and
table type) onto `TableDto` / `TableStatsDto`. Until then:

- the jobs-side `TableRetentionTask.shouldRunTask()` remains the authoritative
  gate, and
- the batched Spark app is a **runtime backstop**: it re-resolves each table's
  retention column and **no-ops** (marks the operation success without deleting)
  any table whose column cannot be resolved, so an over-scheduled or ineligible
  table never produces a bad delete.

### 3. Cadence since the last completed run

- No prior history → schedule immediately.
- Last run `SUCCESS` → wait `retention.success-retry-hours` (default **16h**)
  after its `completedAt` before scheduling again.
- Last run `FAILED` → wait `retention.failure-retry-hours` (default **1h**)
  before retrying.

The success interval is set below 24h so at least one re-evaluation is guaranteed
in any rolling 24-hour window; the failure interval is shorter so transient
failures recover quickly.

### 4. No active operation already in flight

If the table already has a non-`CANCELED` operation row (`PENDING`,
`SCHEDULING`, or `SCHEDULED`), the scheduler already owns it and the analyzer
stays out. A `CANCELED` row does not block — it is treated as if no operation
exists, and step 3's cadence is applied to the history instead.

## Configuration

### Per-table properties

| Property                                  | Meaning                                              | Default |
| ----------------------------------------- | ---------------------------------------------------- | ------- |
| `maintenance.optimizer.retention.enabled` | Opt the table into optimizer-driven retention        | `false` |
| `policies` (retention block)              | Retention time column + TTL (`count`, `granularity`) | —       |
| `retention.backup.enabled`                | Back up deleted data files before deleting           | `false` |

### Analyzer cadence (`analyzerapp/application.properties`)

| Key                             | Meaning                                   | Default |
| ------------------------------- | ----------------------------------------- | ------- |
| `retention.success-retry-hours` | Cooldown after a successful run           | `16`    |
| `retention.failure-retry-hours` | Retry delay after a failed run            | `1`     |

### Scheduler bin caps (`schedulerapp/application.properties`)

Retention is bin-packed with a `FirstFitDecreasingBinPacker` over
`TotalFilesBinItem` (file count is the dominant cost — a retention pass rewrites
the affected partitions/files). Caps per bin; `0` disables a dimension.

| Key                                              | Env override                              | Default   |
| ------------------------------------------------ | ----------------------------------------- | --------- |
| `optimizer.scheduler.retention.max-files-per-bin` | `SCHEDULER_RETENTION_MAX_FILES_PER_BIN`  | `1000000` |
| `optimizer.scheduler.retention.max-tables-per-bin`| `SCHEDULER_RETENTION_MAX_TABLES_PER_BIN` | `50`      |

There is also a hard ceiling `RETENTION_MAX_BATCH_SIZE = 200` on the number of
tables a single batched job can carry (a footgun stop on the CLI-arg envelope,
not the operating point — tune the operating point with the scheduler's
per-job batch size).

### Batched Spark app CLI options (`BatchedRetentionSparkApp`)

The scheduler launches one batched job per bin (job type `RETENTION_BATCH`) with:

| Option               | Meaning                                              | Default   |
| -------------------- | ---------------------------------------------------- | --------- |
| `--tableNames`       | Comma-separated fully-qualified table names (db.tbl) | required  |
| `--operationIds`     | Operation UUIDs, parallel to `--tableNames`          | —         |
| `--tableUuids`       | Table UUIDs, parallel to `--tableNames`              | —         |
| `--resultsEndpoint`  | Base URL of the Optimizer Service (results callback) | required  |
| `--driverParallelism`| Worker threads processing tables in the batch        | `1`       |
| `--backupDir` (`-b`) | Backup directory for deleted data                    | `.backup` |

(`--operationIds` / `--tableUuids` are populated on the optimizer-service path;
the legacy scheduler path leaves them unset.)

## What the batched Spark app does per table

`BatchedRetentionSparkApp` extends the shared `BatchedMaintenanceSparkApp`. One
Spark job processes the bin's `(table, operationId)` pairs; each table runs on a
worker thread and its result is reported independently, so one table's failure
does not sink the batch (the job exits 0 if at least one table succeeds).

Because a batch carries only table names, each table's retention parameters are
**re-resolved at runtime** from its own `policies` property, mirroring
`TablesClient.getTableRetention`:

- `count` and `granularity` come from the `policies.retention` block;
- the retention **column** is `retention.columnPattern.columnName` when present
  (string time column), otherwise the table's time-partition column;
- `columnPattern` is passed through when present, else empty.

If no retention column can be resolved (retention unset, or non-partitioned with
no `columnPattern`), the table is a **no-op success** — the safe backstop for the
analyzer's deferred time-partitioning / table-type signals, rather than failing
the whole bin. Otherwise it runs the same per-table delete as the single-table
`RetentionSparkApp` (`Operations.runRetention`), honoring `retention.backup.enabled`
and `--backupDir`.

### Results callback

After each table, the app PATCHes the per-operation outcome (`SUCCESS` / `FAILED`)
back to `--resultsEndpoint` (the Optimizer Service). If the callback exhausts its
retries the operation row is left `SCHEDULED` so the analyzer's stale-timeout can
re-queue it. When `--resultsEndpoint` is absent (the legacy scheduler-driven
path) the callback is skipped and lifecycle is tracked via HTS instead.
