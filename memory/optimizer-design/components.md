# Optimizer Components Narrative

One section per component. Each covers: responsibility, public interface, config properties,
and known gaps.

---

## 1. Optimizer Service (`services/optimizer`)

### Responsibility
Owns the mutable state store for the optimizer system. Persists three tables:
`table_operations` (lifecycle of a maintenance job per table), `table_stats`
(latest commit-time metrics per table), and `table_operations_history`
(append-only audit log of completed jobs).

All writes to `table_operations` go through this service's REST API regardless of caller.
The Analyzer and Scheduler access the DB directly via JPA (shared credentials) but do not
bypass the REST service for lifecycle transitions — PATCH (SUCCESS/FAILED) comes from the
Spark app via HTTP.

### Public Interface

| Method | Path | Purpose |
|---|---|---|
| PUT | `/v1/table-operations/{id}` | Create or refresh an operation row. Client-generated UUID in path; `tableUuid`, `databaseName`, `tableName`, `operationType` in body. Insert as PENDING if not found; no-op update if found. |
| GET | `/v1/table-operations?operationType=X` | List all active (PENDING + SCHEDULED) rows for the given type. |
| PATCH | `/v1/table-operations/{id}` | Mark an operation SUCCESS or FAILED. Valid only from SCHEDULED; returns 409 on invalid transition. |
| PUT | `/v1/table-stats/{tableUuid}` | Upsert the latest stats for a table. Called fire-and-forget at every commit. |
| GET | `/v1/table-stats/{tableUuid}` | Retrieve stats for a single table. |
| POST | `/v1/table-operations-history` | Append a completed-job record. |
| GET | `/v1/table-operations-history/{tableUuid}?limit=` | Retrieve recent history for a table. |

### Config Properties (`application.properties`)

| Property | Default | Meaning |
|---|---|---|
| `spring.datasource.url` | — | MySQL JDBC URL |
| `spring.datasource.username` / `password` | — | DB credentials |
| `spring.jpa.hibernate.ddl-auto` | `none` | Schema managed by `optimizer-schema.sql` |
| `server.port` | `8080` (mapped 8003 in compose) | HTTP port |

### Known Gaps / Trade-offs

| Gap | Classification |
|---|---|
| `upsertTableStats` has a TODO to catch `OptimisticLockException` and retry on concurrent writes from multiple commit pipelines. Currently surfaces a 500. | Two-way door — add retry wrapper without schema change. |
| No auth on REST endpoints — relies on internal network isolation. | Two-way door — add authentication middleware without schema change. |
| No metrics / tracing on individual endpoints. | Two-way door — add via Spring Actuator + Micrometer. |

---

## 2. OptimizerTableStatsClient (`services/tables`)

### Responsibility
Bridge between the Tables Service commit pipeline and the optimizer's `table_stats` store.
Called once per Iceberg commit after the table is saved. Extracts file-count and size
metrics from the snapshot JSON summaries already in memory, builds the PUT body, and fires
a non-blocking reactive HTTP call.

### Public Interface
`reportCommitStats(tableUuid, databaseId, tableName, clusterId, tableVersion,
  tableLocation, numFilesAdded, numFilesDeleted, tableSizeBytes, numCurrentFiles,
  tableProperties)` — returns void; errors are swallowed.

### Config Properties

| Property | Meaning |
|---|---|
| `cluster.optimizer.base-uri` (`clusterOptimizerBaseUri`) | Base URL of the Optimizer Service. Set per-cluster in `ClusterProperties`. |

### Known Gaps / Trade-offs

| Gap | Classification |
|---|---|
| No retry: if the PUT fails (5xx, timeout), that commit's stats are silently missing until the next commit writes a fresh row. This is acceptable for a derivative signal but not for a required invariant. | Two-way door — add retry via WebClient `.retryWhen` without caller impact. |
| Stats are scraped from snapshot summary JSON via string parsing (`Long.parseLong`). Malformed fields default to 0 (no error). | Two-way door — add validation/logging without schema impact. |

---

## 3. apps/optimizer — Shared JPA Library

### Responsibility
Single source of truth for JPA entity and repository definitions shared by the Analyzer
and Scheduler. Prevents the two apps from defining conflicting mappings for the same
MySQL tables.

### Public Interface

**Entities**

| Class | Table | Notes |
|---|---|---|
| `TableOperationRow` | `table_operations` | `version` is a plain `Long` (not `@Version`) — atomic claim uses `UPDATE WHERE version=` instead of Hibernate's `@Version` to allow explicit optimistic locking. |
| `TableStatsRow` | `table_stats` | `stats` (typed `TableStats`) and `tableProperties` (`Map<String,String>`) stored as JSON via `@TypeDef/@Type(type="json")` from `hibernate-types-55`. |

**Repositories**

| Method | Used by |
|---|---|
| `findByTypeAndStatuses(type, statuses)` | Analyzer: loads active ops for a given type |
| `findPendingByType(type)` | Scheduler: loads rows to schedule |
| `claimOperation(id, version, now)` | Scheduler: `UPDATE SET status=SCHEDULED, scheduledAt=now WHERE id=? AND version=?` — returns 1 on success, 0 if already claimed |
| `TableStatsRepository.findAllById(ids)` | Scheduler: bulk file-count lookup for bin packing |

### Config Properties
None in the library itself. Consuming apps must provide:
- `spring.datasource.*`
- `spring.jpa.hibernate.ddl-auto=none`
- `@EntityScan("com.linkedin.openhouse.optimizer.entity")`
- `@EnableJpaRepositories("com.linkedin.openhouse.optimizer.repository")`

### Known Gaps / Trade-offs

| Gap | Classification |
|---|---|
| `TableStatsRepository.findAll()` in the Analyzer is unbounded — O(all tables in the catalog). For catalogs with millions of tables this risks OOM or slow GC pause. | Two-way door — add keyset pagination or a `since` window filter with a schema migration to add an index on `updated_at`. |

---

## 4. Analyzer App (`apps/optimizer-analyzer`)

### Responsibility
Determines which tables are eligible for each maintenance operation and ensures a PENDING
row exists in `table_operations` for each eligible table. Runs once and exits (no daemon).

Reads `table_stats` for table enumeration and latest metrics. Reads active
`table_operations` to skip tables already queued. Inserts PENDING rows for the delta.

### Public Interface
No HTTP port. Invoked as a Docker container, runs `AnalyzerRunner.analyze()` and exits.

**Extension point**: implementing `OperationAnalyzer` registers a new operation type
(enabled check + cadence check). `OrphanFilesDeletionAnalyzer` is the only current impl.

**`CadencePolicy` state machine**

| Current State | Condition | Decision |
|---|---|---|
| No row | — | Schedule (first time) |
| PENDING | — | Schedule (idempotent: runner skips insert since row exists) |
| SCHEDULED | — | Skip (in-flight) |
| SUCCESS | `scheduledAt` > `successRetryInterval` ago | Schedule |
| FAILED | `scheduledAt` > `failureRetryInterval` ago | Schedule |

### Config Properties (`application.properties`)

| Property | Default | Meaning |
|---|---|---|
| `spring.datasource.*` | H2 in-mem (dev), MySQL (prod) | Optimizer DB |
| `ofd.success-retry-hours` | 24 | Interval between successful OFD runs |
| `ofd.failure-retry-hours` | 1 | Retry interval after a failed OFD run |
| `spring.retry.max-attempts` | 3 | DB retry for transient failures |
| `spring.retry.initial-interval-ms` | 1000 | Backoff start |

### Known Gaps / Trade-offs

| Gap | Classification |
|---|---|
| Concurrent analyzer instances can both insert PENDING rows for the same table — no DB-level uniqueness constraint on `(table_uuid, operation_type)`. The Scheduler's version guard handles duplicates at claim time. | Two-way door — add a partial unique index on `(table_uuid, operation_type)` for active statuses, or rely on existing behavior (duplicate rows are harmless). |
| `statsRepo.findAll()` is unbounded (see shared library gap above). | Same as above. |

---

## 5. Scheduler App (`apps/optimizer-scheduler`)

### Responsibility
Converts PENDING operation rows into batched Spark jobs. For each configured operation
type, it reads PENDING rows, groups tables into file-count bins (greedy FFD), claims rows
atomically, and submits one Spark job per bin.

Operates one operation type per invocation (configured via `scheduler.operation-type`).
Multiple operation types require multiple scheduler runs (or multiple containers with
different configs).

### Public Interface
No HTTP port. Invoked as a Docker container, runs `SchedulerRunner.schedule()` and exits.

**Bin-packing algorithm**: Greedy First Fit Descending — tables sorted descending by
`numCurrentFiles`; each table assigned to the first open bin with remaining capacity.
Tables larger than `maxFilesPerBin` get their own bin (never dropped).

**Claim protocol**: `UPDATE SET status=SCHEDULED, scheduledAt=now WHERE id=? AND version=?`
— atomic claim prevents two scheduler instances from submitting the same row. Only claimed
rows are included in the job submission. If job launch fails, claimed rows stay SCHEDULED.

### Config Properties (`application.properties`)

| Property | Default | Meaning |
|---|---|---|
| `spring.datasource.*` | MySQL (prod) | Optimizer DB |
| `jobs.base-uri` | — | Jobs Service base URL |
| `scheduler.bin-size-max-files` | 1,000,000 | Max total files per Spark job |
| `scheduler.operation-type` | ORPHAN_FILES_DELETION | Which operation type to schedule |
| `scheduler.results-endpoint` | — | Optimizer Service PATCH URL passed to Spark |

### Known Gaps / Trade-offs

| Gap | Classification |
|---|---|
| No watchdog for stale SCHEDULED rows. If a job launch fails after claim (or the Spark job dies without calling PATCH), rows stay SCHEDULED forever. A watchdog that detects `scheduledAt` older than N hours and resets to PENDING is required for production reliability. | One-way door consideration — the watchdog needs the `scheduledAt` column to be present (it is) and a new background process. Without it, tables are silently stuck. |
| Scheduling one operation type per invocation means N types require N scheduler runs. For the current single OFD type this is fine; adding types requires either config variants or a loop over types. | Two-way door — extend scheduler to iterate all configured types without schema change. |

---

## 6. BatchedOrphanFilesDeletionSparkApp (`apps/spark`)

### Responsibility
Executes orphan file deletion for a batch of tables in a single Spark session using a
driver-side thread pool. Each table's deletion runs concurrently. After all futures
complete, PATCHes each table's outcome (SUCCESS/FAILED + metrics) back to the Optimizer
Service.

### Public Interface
CLI arguments (from Jobs Service job submission):

| Arg | Meaning |
|---|---|
| `--tableNames` | Comma-separated `db.table` identifiers |
| `--operationIds` | Parallel list of operation UUIDs — `tableNames[i]` maps to `operationIds[i]` |
| `--resultsEndpoint` | Base URL for `PATCH /v1/table-operations/{id}` |

Exit code 0 if at least one table succeeded; non-zero if all failed.

### Config Properties
Inherited from Spark session (cluster, executor count, memory) + table-level TTL from
table properties (e.g. `maintenance.optimizer.ofd.ttl-days`).

### Known Gaps / Trade-offs

| Gap | Classification |
|---|---|
| If the PATCH call fails (optimizer service unreachable), the operation stays SCHEDULED — same watchdog dependency as the Scheduler. | Same as Scheduler watchdog gap above. |
| Partial failure (some tables fail, some succeed) is allowed — the app throws only if ALL tables fail. Tables that failed silently will be retried after `ofd.failure-retry-hours` once the Analyzer re-runs. | Two-way door — current behavior is intentional. |
