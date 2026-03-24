# Design Doc Update — Working Notes

## Source of Truth
- Code: `~/code/openhouse` branch `mkuchenb/optimizer`
- Target: `~/code/docs/table-optimizer-design.md`
- Final destination: Google Doc `1oGQWkmlVw0HG-D4Nx37q0oUEQd53Ni4q5Q7h1voaZIQ`

## Current Design Doc Structure (line numbers from grep)
```
  1  # Table Optimizer
 19  # Summary
 37  ## MVP Requirements
 55  ## Success Metrics
 60  # User Stories
 80  # Design Sketch
 87  ## Optimizer Service
141  ## Nearline Stats          [todo but leave alone for now]
145  # Detailed Design
147  ## Optimizer API            [todo explain api]
151  ## Optimizer Data Model     [todo explain data model]
155  ## Analyzer                 [todo x3]
161  ## Scheduler                [todo x2]
166  ## JobScheduler             [todo explain changes]
185  ## SparkJob Changes         [todo explain]
189  ## Needed Maintenance       [todo x2]
194  ## Onboarding Jobs          [todo]
198  ## Observability            [todo x2]
203  ## Alerts                   [todo]
207  ## Failure Conditions       [todo clean up and expand]
221  # Appendix
```

---

## Section-by-Section Notes

### 1. Optimizer Data Model (line 153)

Three MySQL tables. All in `optimizer-schema.sql`.

**table_operations** — one active row per (table, operation). Upsert semantics.
```
id              VARCHAR(36) PK   — client-generated UUID (Analyzer)
table_uuid      VARCHAR(36) NN   — Iceberg UUID, stable across renames
database_name   VARCHAR(255) NN  — denormalized for display
table_name      VARCHAR(255) NN  — denormalized for display
operation_type  VARCHAR(50) NN   — enum string: ORPHAN_FILES_DELETION
status          VARCHAR(20) NN   — PENDING | SCHEDULED | SUCCESS | FAILED
created_at      TIMESTAMP(6) NN  — set on insert
scheduled_at    TIMESTAMP(6)     — set when Scheduler claims; "last ran at" proxy
version         BIGINT           — manual optimistic lock (not @Version)
metrics         TEXT             — JSON blob: OperationMetrics
```

State machine:
```
(no row)    → Analyzer PUT   → PENDING
PENDING     → Analyzer PUT   → PENDING (metrics refresh, idempotent)
PENDING     → Scheduler claim → SCHEDULED (sets scheduledAt, version guard)
SCHEDULED   → Spark PATCH    → SUCCESS | FAILED
SUCCESS     → Analyzer PUT (after successInterval) → new row PENDING
FAILED      → Analyzer PUT (after failureInterval) → new row PENDING
```

Key: Analyzer creates NEW row (new UUID) each cycle. Old SUCCESS/FAILED rows accumulate.
TODO: need GC strategy for old terminal rows, or add unique constraint on (table_uuid, operation_type) with upsert.

**table_stats** — one row per table. Written on every Iceberg commit.
```
table_uuid       VARCHAR(36) PK  — Iceberg UUID
database_id      VARCHAR(255) NN
table_name       VARCHAR(255) NN
stats            TEXT             — JSON: {snapshot: {clusterId, tableVersion, tableLocation, numSnapshots, tableSizeBytes}, delta: {numFilesAdded, numFilesDeleted}}
table_properties TEXT             — JSON map of table properties
```
Note: snapshot fields overwritten, delta fields accumulated across commits.
Used by: Analyzer (table enumeration + opt-in check), Scheduler (file count for bin packing).

User note: Consider future table/database registry to avoid relying on commit stats exclusively.
Current approach is fine — one record per table, populated on first commit.

**table_operations_history** — append-only audit log.
```
id              BIGINT AUTO_INCREMENT PK
table_uuid      VARCHAR(36) NN
database_name   VARCHAR(255) NN
table_name      VARCHAR(255) NN
operation_type  VARCHAR(50) NN
submitted_at    TIMESTAMP(6) NN
status          VARCHAR(20) NN  — SUCCESS | FAILED
job_id          VARCHAR(255)
result          TEXT            — JSON: {errorClass, errorMessage}
```

Enums:
- OperationType: ORPHAN_FILES_DELETION (extensible)
- OperationStatus: PENDING, SCHEDULED, SUCCESS, FAILED
- OperationHistoryStatus: SUCCESS, FAILED

### 2. Optimizer API (line 149)

**Table Operations** `/v1/table-operations`
| Method | Path | Caller | Purpose |
|--------|------|--------|---------|
| PUT | `/{id}` | Analyzer | Create PENDING row or refresh metrics. `id` is client-generated UUID. Body: `{tableUuid, databaseName, tableName, operationType, metrics}` |
| PATCH | `/{id}` | Spark App | Transition SCHEDULED → SUCCESS or FAILED. Body: `{status, metrics}`. Returns 404 if not found. Only SUCCESS/FAILED accepted (throws IllegalArgumentException otherwise). |
| GET | `/` | Analyzer, Scheduler | List active ops. Optional `?operationType=X` filter. Returns PENDING + SCHEDULED rows only. |

Note: GET filters to PENDING/SCHEDULED in code (service layer). When operationType param is provided, delegates to repo `find(operationType)` which likely returns all statuses for that type.

**Table Stats** `/v1/table-stats`
| Method | Path | Caller | Purpose |
|--------|------|--------|---------|
| PUT | `/{tableUuid}` | Tables Service (on commit) | Upsert stats. Body: `{databaseId, tableName, stats, tableProperties}` |
| GET | `/{tableUuid}` | Debug | Returns 404 if no stats yet. |

**Table Operations History** `/v1/table-operations-history`
| Method | Path | Caller | Purpose |
|--------|------|--------|---------|
| POST | `/` | Spark App | Append completed-job record. Body: full HistoryDto. |
| GET | `/{tableUuid}` | Debug | Recent history, newest first, `?limit=100` default. |

### 3. Analyzer (lines 155-159)

**What it does**: Spring Boot CommandLineRunner (`AnalyzerApplication`). Runs once and exits.
For each registered `OperationAnalyzer`:
1. Loads ALL table_stats rows via JPA `statsRepo.findAll()` — this is the table enumeration source
2. Loads active (PENDING/SCHEDULED) operation rows for that type via `operationsRepo.findByTypeAndStatuses()`
3. For each table: checks `isEnabled()` (opt-in property), then `shouldSchedule()` via CadencePolicy
4. If should schedule AND no existing row → inserts new PENDING row with fresh UUID

**Why MySQL-only**: Reads table_stats and table_operations directly via JPA (shared `apps/optimizer` module).
No HTTP calls to any service. This is intentional — tighter loop, no impact on other services.
The analyzer and scheduler share the optimizer DB via JPA entities in `apps/optimizer/`.

**V0 invocation**: Kubernetes CronJob on a 10-30 minute cadence. Stateless — safe to re-run.

**CadencePolicy** (default strategy):
- No row → schedule (first time)
- PENDING → returns true but runner skips insert (row exists)
- SCHEDULED → skip (in-flight)
- SUCCESS + scheduledAt older than successRetryInterval → schedule
- FAILED + scheduledAt older than failureRetryInterval → schedule

**Example: OrphanFilesDeletionAnalyzer**
- Opt-in: `maintenance.optimizer.ofd.enabled=true` table property
- Success interval: 24h (configurable via `ofd.success-retry-hours`)
- Failure retry: 1h (configurable via `ofd.failure-retry-hours`)
- Default strategy = cadence-based, same frequency as today's cron

**OperationAnalyzer interface**:
```java
String getOperationType();
boolean isEnabled(TableSummary table);
boolean shouldSchedule(TableSummary table, Optional<TableOperationRecord> currentOp);
```

### 4. Scheduler (lines 161-164)

**What it does**: Spring Boot CommandLineRunner. Runs once and exits.
1. Loads PENDING rows for configured operation type
2. Looks up file counts from table_stats for bin-packing
3. Bin-packs using Greedy First Fit Descending (sort desc by file count)
4. For each bin: claims rows atomically (UPDATE WHERE version=), then submits one Spark job

**Bin packing**: `BinPacker.pack()` — FFD algorithm.
- Tables sorted descending by numCurrentFiles from table_stats
- Each table placed in first bin with remaining capacity
- Oversized tables (> maxFilesPerBin) get their own bin (never dropped)
- Missing stats → treated as 0 files

**V0**: Single operation type per invocation (`scheduler.operation-type` config).
Default V0 = no bin packing effectively (or set maxFiles very high). Same as today — one table per job.
Future: iterate all configured types, or multiple scheduler containers.

**Claim protocol**: `UPDATE SET status=SCHEDULED, scheduledAt=now WHERE id=? AND version=?`
Returns 1=claimed, 0=already claimed. Prevents double-submit across concurrent schedulers.

**Config**:
- `scheduler.bin-size-max-files`: 1,000,000 (default)
- `scheduler.operation-type`: ORPHAN_FILES_DELETION
- `scheduler.results-endpoint`: Optimizer Service PATCH URL passed to Spark
- `jobs.base-uri`: Jobs Service base URL

### 5. JobScheduler / Job Submission Flow (line 166)

The existing flow from the doc is already mostly documented (numbered steps 1-7).
What changes: the Scheduler now submits batched jobs instead of single-table jobs.

Flow:
1. SchedulerRunner reads PENDING ops, bin-packs, claims rows
2. For each bin: JobsServiceClient.launch() → POST /jobs with jobName, clusterId, jobType, args
3. Jobs Service stores JobDto, calls LivyJobsCoordinator.submit()
4. Livy submits to Spark cluster
5. Spark App runs (BatchedOrphanFilesDeletionSparkApp)
6. Per-table: Spark App PATCHes /v1/table-operations/{id} with SUCCESS/FAILED + metrics
7. No polling — Spark reports directly back to optimizer service

Key change from today: Scheduler doesn't poll for job completion. Spark app self-reports.

### 6. SparkJob Changes for Batching (line 187)

**BatchedOrphanFilesDeletionSparkApp** exists in `apps/spark/`.
- Takes CLI args: --tableNames (comma-separated db.table), --operationIds (parallel UUIDs), --resultsEndpoint
- Uses thread pool for parallel per-table processing
- Per-table: runs orphan file deletion, then PATCHes result back to optimizer service
- Exit code 0 if at least one table succeeded; non-zero if all failed

**Pattern for other operations**: Each operation type needs a batched Spark app following this pattern:
1. Accept --tableNames, --operationIds, --resultsEndpoint
2. Process tables in parallel (thread pool on driver)
3. PATCH per-table results back to optimizer service
4. Handle partial failure (some tables fail, some succeed)

**If batched app not yet built**: Operation can run unbatched (one table per job). The scheduler
submits bins of size 1 effectively. No code change needed — just set bin size to 1 or submit
individual jobs. The framework supports both.

### 7. Nearline Stats (line 143)

User instruction: leave alone for now.
Only requirement for this project: <15 minutes latency to land in table_stats after commit.
Current implementation: Tables Service fires async PUT to optimizer service on each commit.
Latency is effectively real-time (seconds) when optimizer service is up.
Failure mode: if optimizer is down, that commit's stats are lost until next commit.

### 8. Needed Maintenance (lines 191-192)

**Table TTL for operations rows**: Terminal rows (SUCCESS/FAILED) accumulate since the Analyzer
creates new UUIDs each cycle. Need a cleanup job to delete rows older than N days, or switch to
upsert-by-(table_uuid, operation_type) semantics.

**Stats reconciliation**: table_stats is best-effort (commit-driven, fire-and-forget). If optimizer
is down during commits, stats go stale. Need periodic reconciliation from Iceberg metadata.
Could be a separate analyzer that reads table metadata and refreshes stats rows.

Also need to discuss: what about tables that are dropped? table_stats rows for dropped tables
should be cleaned up. table_operations for dropped tables should be canceled.

### 9. Onboarding Jobs (line 196)

Goal: launch optimizer ASAP with confidence that failure = more jobs (latency), not missed jobs.

Strategy:
1. Deploy optimizer alongside existing crons
2. Optimizer runs on opted-in tables; existing crons continue for all tables
3. Cron checks: "has optimizer already handled this table recently?" → skip if yes
4. Gracefully increase opt-in percentage
5. Once confident, disable crons for opted-in operation types

Failure pattern: If optimizer misses a table, cron backstop catches it on next run.
Worst case = same as today (cron handles it). Best case = optimizer handles it faster.

Gap: Slow-moving tables that never commit won't appear in table_stats (no commit → no stats push).
These tables will only be handled by the cron backstop until we add a table/database registry
or periodic full-catalog scan.

### 10. Observability (lines 200-201)

Three categories needed:

**Operational observability** — is the system healthy?
- Analyzer run success/failure, duration, tables processed, ops created
- Scheduler run success/failure, bins packed, jobs submitted, claim conflicts
- Spark job success/failure per table, duration
- table_stats freshness (stalest row age)
- Stale SCHEDULED rows (watchdog gap — no watchdog yet)

**Reporting observability** — what is the system doing?
- ETL from optimizer MySQL → data warehouse for dashboards
- System table for operations (queryable via Trino/SQL)
- Operations per type per day, success rate, latency distribution
- Tables covered vs total catalog

**Success measurement** — are we achieving claimed improvements?
- Reduction in Spark jobs (target: 80%)
- Scheduling latency (time from table change to job submission)
- Peak load reduction (jobs/hour distribution)
- Coverage: % of eligible tables handled by optimizer vs cron backstop
- Per-table operation frequency vs optimal frequency

### 11. Alerts (line 205)

Principle: Only alert if actionable. System should self-recover otherwise.
Failure pattern = latency (jobs run later, not never).

**Alertable conditions**:
- Analyzer hasn't run successfully in N hours (cron failed)
- Scheduler hasn't run successfully in N hours
- Stale SCHEDULED rows > threshold age (watchdog gap — job submitted but never reported back)
- table_stats not updated for any table in N hours (stats pipeline broken)
- Job submission failure rate > threshold

**Self-recovery**:
- Analyzer crash → next cron invocation retries; idempotent
- Scheduler crash → next invocation picks up unclaimed PENDING rows
- Spark partial failure → individual tables retry after failureRetryInterval
- Stats miss → next commit updates; reconciliation job catches persistent gaps

**Customer communication**: Not fully fleshed out. Data exists in table_operations_history for
per-table operation status. Could expose a "table health" API or dashboard. Should be as good
or better than current system (which has no customer-facing observability).

### 12. Failure Conditions (lines 207-219) — HIGHEST PRIORITY

Existing items in doc + expansion:

**F1: Two analyzers run simultaneously creating duplicate operations**
- Current: No uniqueness constraint on (table_uuid, operation_type) for active rows.
  Both may insert PENDING rows for the same table.
- Mitigation: Scheduler's version guard claims first, skips duplicates. Harmless but wasteful.
- Fix: Add partial unique index on (table_uuid, operation_type) WHERE status IN ('PENDING','SCHEDULED'),
  or ensure only one analyzer runs at a time (K8s CronJob concurrencyPolicy=Forbid).

**F2: Scheduler runs two identical operations**
- Current: claimOperation uses UPDATE WHERE version= as optimistic lock.
  Second scheduler gets 0 rows and skips. No double-submit.
- This is handled correctly.

**F3: Individual table operation crashes within batch**
- Spark app runs per-table in parallel. If one table fails:
  - That table gets PATCH'd as FAILED
  - Other tables continue and get PATCH'd as SUCCESS
  - Job exits 0 if at least one succeeded
- Retry: Analyzer re-creates PENDING after failureRetryInterval (1h for OFD)

**F4: Spark job dies without reporting back (OOM, node loss, Livy failure)**
- Claimed rows stay SCHEDULED forever.
- **Known gap: no watchdog.** Need a process to detect scheduledAt older than N hours and
  reset to PENDING (or FAILED). Without this, tables are silently stuck.
- Mitigation until watchdog: cron backstop handles these tables.

**F5: Job submission fails after claim**
- Same as F4. Rows are SCHEDULED but no job exists.
- Same watchdog needed.

**F6: PATCH call fails from Spark app**
- Spark app throws, operation counted as failure in job.
- Row stays SCHEDULED → same watchdog scenario.

**F7: Table operation repeatedly crashes**
- No circuit breaker currently. Table gets retried every failureRetryInterval indefinitely.
- Future: After N consecutive failures, mark table as problematic and stop retrying.
  Requires tracking failure count (could use metrics field or history count).

**F8: Optimizer Service down**
- Stats writes: swallowed (WARN log). Stats stale until next commit when service is back.
- Analyzer: reads DB directly (JPA), so optimizer service being down doesn't affect it.
  But if the DB is down, analyzer exits non-zero and retries on next cron.
- Spark PATCH: fails → F6 scenario.

**F9: Table renamed**
- table_uuid is stable across renames. Operation row follows the table correctly.
- table_stats row stays correct (keyed by UUID).
- database_name/table_name in operation rows go stale (denormalized). Cosmetic issue only.

**F10: Table dropped and recreated with same name**
- New table gets new UUID. Old operation rows are orphaned (will be GC'd).
- New table starts clean — no inherited state. Correct behavior.

**F11: Slow-moving tables not in table_stats**
- Tables that never commit don't appear in table_stats.
- Analyzer only iterates table_stats rows → these tables are invisible to the optimizer.
- Mitigation: cron backstop handles them. Future: table/database registry or periodic catalog scan.

**F12: table_stats row for dropped table**
- Orphaned stats row. Analyzer will try to create operations for a table that no longer exists.
- PUT to optimizer service succeeds (creates PENDING), Scheduler submits job, Spark app fails
  (table not found), PATCH'd as FAILED. Retried after failureRetryInterval until GC.
- Mitigation: Reconciliation job that checks table existence and cleans up.

**Overall failure philosophy**: Failure mode is latency, not data loss or missed operations.
The cron backstop ensures no table goes unhandled. The optimizer improves latency and efficiency
but the cron is the safety net. This means we can launch confidently.

---

## Checklist (priority order per user)

- [ ] 12. Failure Conditions — expand and refine (HIGHEST)
- [ ] 8. Needed Maintenance — TTL, reconciliation, dropped tables
- [ ] 9. Onboarding Jobs — cron backstop, slow-movers gap
- [ ] 1. Data Model — schema, state machine, diagrams
- [ ] 2. API — endpoints, request/response
- [ ] 3. Analyzer — what, why MySQL-only, V0 cron, example strategy
- [ ] 4. Scheduler — what, bin packing, V0 default = same as today
- [ ] 5. JobScheduler — existing flow + what changes
- [ ] 6. SparkJob Changes — batched pattern, unbatched fallback
- [ ] 7. Nearline Stats — <15min requirement only
- [ ] 10. Observability — operational, reporting, success measurement
- [ ] 11. Alerts — self-recovery, latency-based, customer communication
