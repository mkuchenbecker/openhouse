# Snapshots Expiration (Optimizer)

Operator-facing reference for the continuous-optimizer integration of `SNAPSHOTS_EXPIRATION`.
It is the sibling of Orphan-Files-Deletion (OFD) and is wired the same way: a per-table
cadence analyzer decides *when*, the scheduler bin-packs eligible tables into batches, and a
batched Spark app does the work and reports each table's result back to the Optimizer Service.

## What it does

Snapshot expiration drops old Iceberg snapshots from a table so its metadata history — and the
storage those stale snapshots pin — stops growing without bound.

Per table, the job:

- **Expires** every snapshot older than the configured retention window (`maxAge` × `granularity`).
- If a version cap is set, additionally **retains only the last `versions` snapshots** (see
  Configuration).
- **Always preserves the current snapshot.** A table's live state is never removed.
- Runs with `cleanExpiredFiles(false)`: it removes *snapshot references and expired metadata*, but
  does **not** delete the now-unreferenced data files. Reclaiming those data files is the job of
  **Orphan-Files-Deletion**, which runs on its own cadence. Enable both if you want snapshots
  expired *and* the freed data files deleted.

## When it runs

Eligibility is decided per table by `CadenceBasedSnapshotsExpirationAnalyzer`. A table is scheduled
only when **all** of the following hold:

1. **Opt-in.** The table sets the property `maintenance.optimizer.snapshotsExpiration.enabled=true`.
   Without this flag the analyzer ignores the table entirely. (Default: absent → disabled.)
2. **Primary table.** Replicas track the primary's snapshot lineage and must not have snapshots
   expired independently. A table is treated as primary unless its `openhouse.tableType` property is
   explicitly `REPLICA_TABLE`. This mirrors the legacy `TableSnapshotsExpirationTask.shouldRunTask()`
   guard (`metadata.isPrimary()`).
3. **No active operation already in flight.** If the table already has a non-`CANCELED` operation row
   (`PENDING`, `SCHEDULING`, or `SCHEDULED`), the scheduler already owns it and the analyzer stays
   out. A `CANCELED` row does not block — it is treated as if no operation exists.
4. **Cadence elapsed since the last completed run:**
   - No prior history → schedule immediately.
   - Last run `SUCCESS` → wait `snapshotsExpiration.success-retry-hours` (default **16h**) after its
     `completedAt` before scheduling again. Set below 24h so at least one re-evaluation lands within
     any rolling 24-hour window.
   - Last run `FAILED` → wait `snapshotsExpiration.failure-retry-hours` (default **1h**) before
     retrying — shorter than the success interval so transient failures recover quickly.

## Configuration

Every option the feature reads, with defaults.

### Per-table property (Tables Service)

| Property | Default | Meaning |
| --- | --- | --- |
| `maintenance.optimizer.snapshotsExpiration.enabled` | absent (disabled) | Opt the table in. Must equal the string `true`. |
| `openhouse.tableType` | treated as primary | Only an explicit `REPLICA_TABLE` value makes a table ineligible. |

### Analyzer app (`apps/optimizer/analyzerapp` `application.properties`)

| Key | Default | Meaning |
| --- | --- | --- |
| `snapshotsExpiration.success-retry-hours` | `16` | Hours to wait after a successful run before re-evaluating the table. |
| `snapshotsExpiration.failure-retry-hours` | `1` | Hours to wait after a failed run before retrying. |

### Scheduler app (`apps/optimizer/schedulerapp` `application.properties`)

Snapshots-expiration operations are bin-packed with a first-fit-decreasing packer weighted by the
table's current file count (`TotalFilesBinItem`), the same strategy OFD uses.

| Key | Default | Meaning |
| --- | --- | --- |
| `optimizer.scheduler.snapshotsExpiration.max-files-per-bin` | `1000000` | Max summed file count per batch. `0` disables this dimension. |
| `optimizer.scheduler.snapshotsExpiration.max-tables-per-bin` | `50` | Max tables per batch. `0` disables this dimension. |

(Both are overridable via the `SCHEDULER_SNAPSHOTS_EXPIRATION_MAX_FILES_PER_BIN` /
`SCHEDULER_SNAPSHOTS_EXPIRATION_MAX_TABLES_PER_BIN` environment variables.)

### Batched Spark app CLI (`BatchedSnapshotsExpirationSparkApp`)

The scheduler passes the batch-membership options; the retention options come from the job template
in `jobs.yaml` (which ships with `args: []`, so the defaults below apply).

| Option (alias) | Default | Meaning |
| --- | --- | --- |
| `--tableNames` | required | Comma-separated fully-qualified table names in this batch. |
| `--operationIds` | (unset) | Comma-separated operation UUIDs, parallel to `--tableNames`. Unset on the legacy path. |
| `--tableUuids` | (unset) | Comma-separated table UUIDs, parallel to `--tableNames`. Unset on the legacy path. |
| `--resultsEndpoint` | required | Base URL of the Optimizer Service for the results callback. |
| `--driverParallelism` | `1` | Worker threads processing tables concurrently within the batch. |
| `--maxAge` (`-a`) | `0` → `3` | Expire snapshots older than `maxAge` `granularity`s. `0` means "unconfigured": the app applies a default of `3` days. |
| `--granularity` (`-g`) | `""` → `days` | Time unit for `--maxAge`. Defaulted to days when `--maxAge` is unconfigured. |
| `--versions` (`-v`) | `0` | If `> 0`, retain only the last N snapshots (after the age-based pass). `0` = no version cap. |

Hard cap: a single batch may carry at most **200** tables (`SNAPSHOTS_EXPIRATION_MAX_BATCH_SIZE`).
Exceeding it fails argument parsing before the job starts; it is a footgun stop, not the operating
point — tune the real batch size with the scheduler's per-bin caps above.

## What the batched Spark app does per table

`BatchedSnapshotsExpirationSparkApp` extends the shared `BatchedMaintenanceSparkApp` base:

- One Spark job handles the whole bin. Each table runs on its own worker thread
  (`--driverParallelism`).
- Per table it calls `Operations.expireSnapshots(fqtn, maxAge, granularity, versions)` — the same
  single-table call the standalone `SnapshotsExpirationSparkApp` makes.
- **Failures are isolated.** If one table throws, it is caught, logged, and reported independently;
  the remaining tables continue. The job exits successfully if at least one table succeeded, and
  fails only if every table in the batch failed.

### Results callback

For each table the app PATCHes the outcome to the Optimizer Service at `--resultsEndpoint`
(`UpdateOperationRequest` with `operationType = SNAPSHOTS_EXPIRATION` and status `SUCCESS` or
`FAILED`). That terminal status is what the analyzer's cadence reads on the next pass.

- If `--resultsEndpoint` is not set, the app is running under the legacy `JobsScheduler`; the
  callback is skipped and lifecycle is tracked via HTS instead.
- If the callback exhausts its retries, the operation row is left `SCHEDULED` and the analyzer's
  stale-timeout re-queues it — the run is not silently lost.

## Known limitations / deferred signals

- **Cadence-driven, not need-driven.** The optimizer's table stats do not yet expose snapshot count
  or age, so the analyzer cannot tell whether a table actually has expirable snapshots. It therefore
  fires on a fixed time cadence and lets the Spark job no-op when there is nothing to expire. When
  snapshot count/age lands in the stats model, scheduling can become need-based (skip tables with no
  expirable snapshots) rather than purely time-based.
- **Batch-wide retention, not per-table policy.** The batched path applies one `maxAge` /
  `granularity` / `versions` (from the job template, i.e. the defaults) across every table in the
  batch. The legacy single-table path read each table's own retention policy (`HistoryConfig`); that
  per-table plumbing is not yet wired into the batched path. Until it is, operators relying on
  non-default per-table snapshot-retention windows should keep those tables on the single-table job.
