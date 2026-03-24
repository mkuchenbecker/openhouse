# OpenHouse Table Optimizer — Design RFC

**Author**: mkuchenb
**Branch**: `mkuchenb/optimizer`
**Status**: Draft

---

## 1. Mission

OpenHouse tables accumulate orphan files over time, degrading read performance and
inflating storage costs. Today maintenance runs on a fixed cron schedule that submits
one Spark job per table — O(tables) jobs regardless of whether any table needs work.
This does not scale.

The Table Optimizer replaces fixed-cron table maintenance with an adaptive,
signal-driven scheduling loop: only tables that need work get scheduled, and scheduling
frequency is proportional to table activity.

---

## 2. Proposed Solution

Introduce a continuous analyze → schedule loop alongside the existing Tables Service.
At every Iceberg table commit, lightweight statistics (file counts, snapshot counts,
table properties) are pushed to a dedicated Optimizer Service. A periodic Analyzer
examines these statistics, applies per-operation cadence rules, and creates scheduling
requests for eligible tables. A periodic Scheduler reads those requests, groups tables
into right-sized Spark job bins, and submits one batched job per bin. Each Spark job
reports its outcome back via REST when complete, closing the feedback loop.

---

## 3. Design Sketch

Five components interact through two shared paths: the **commit path** (Tables Service →
Optimizer DB) and the **maintenance path** (Analyzer → Optimizer DB → Scheduler →
Jobs Service → Spark → Optimizer Service).

```
┌──────────────────────────────────────────────────────────────┐
│  COMMIT PATH (per Iceberg table write)                       │
│                                                              │
│  Iceberg Client ──► Tables Service ──► OptimizerTableStats  │
│                                         Client               │
│                                           │ fire-and-forget  │
│                                           ▼                  │
│                                       Optimizer Service      │
│                                       (table_stats)          │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  MAINTENANCE PATH (periodic, runs once per invocation)       │
│                                                              │
│  Analyzer ──(JPA)──► Optimizer DB ◄──(JPA)── Scheduler      │
│  (reads table_stats,   (table_operations,   (claims PENDING, │
│   writes PENDING)       table_stats)         submits bins)   │
│                                                    │         │
│                                                    ▼         │
│                                             Jobs Service     │
│                                                    │         │
│                                                    ▼         │
│                                     BatchedOFD Spark App     │
│                                                    │         │
│                                                    │ PATCH   │
│                                                    ▼         │
│                                         Optimizer Service    │
│                                         (SUCCESS / FAILED)   │
└──────────────────────────────────────────────────────────────┘
```

**Key design choices visible at this level:**

1. The Analyzer and Scheduler share the Optimizer DB directly (JPA, same credentials).
   No HTTP hop between these co-owned components.

2. Stats delivery is fire-and-forget. A stats outage degrades scheduling fidelity but
   never blocks a table write.

3. Analyzer and Scheduler are `CommandLineRunner` processes — they run once and exit.
   Scheduling frequency is controlled by how often the container is invoked (cron /
   Kubernetes CronJob), not by a daemon sleep loop.

---

## 4. Detailed Design

### 4.1 Data Model

Three tables in the Optimizer DB (MySQL):

#### `table_operations`
Tracks the scheduling lifecycle of a maintenance operation for a specific table.
One active row per `(table_uuid, operation_type)`.

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) PK | Client-generated UUID. Stable across retries. |
| `table_uuid` | VARCHAR(36) NOT NULL | Iceberg table UUID. Indexed. |
| `database_name` | VARCHAR(255) NOT NULL | Display name for job submission. |
| `table_name` | VARCHAR(255) NOT NULL | Display name for job submission. |
| `operation_type` | VARCHAR(64) NOT NULL | e.g. `ORPHAN_FILES_DELETION` |
| `status` | VARCHAR(32) NOT NULL | `PENDING`, `SCHEDULED`, `SUCCESS`, `FAILED` |
| `metrics` | TEXT (JSON) | `OperationMetrics` — tableSizeBytes, numSnapshots, numCurrentFiles |
| `created_at` | DATETIME NOT NULL | When the PENDING row was first inserted |
| `scheduled_at` | DATETIME | When the Scheduler last claimed this row |
| `version` | BIGINT NOT NULL DEFAULT 0 | Optimistic-lock counter for atomic claim |

**Status lifecycle:**

```
[no row] ──── Analyzer PUT ────► PENDING ──── Scheduler claim ────► SCHEDULED
                                                                          │
                                                             Spark PATCH  │
                                                                 ┌────────┴────────┐
                                                                 ▼                 ▼
                                                              SUCCESS           FAILED
                                                                 │                 │
                                                    after 24h ◄─┘    after 1h ◄───┘
                                                  next Analyzer run inserts new PENDING row
```

#### `table_stats`
Latest commit-time metrics per table. One row per table UUID, updated at every commit.

| Column | Type | Notes |
|---|---|---|
| `table_uuid` | VARCHAR(36) PK | Iceberg table UUID |
| `database_id` | VARCHAR(255) NOT NULL | Catalog identifier |
| `table_name` | VARCHAR(255) NOT NULL | Table name |
| `stats` | TEXT (JSON) | `TableStats` — `SnapshotMetrics` + `CommitDelta` |
| `table_properties` | TEXT (JSON) | Full table property map (opt-in flags, TTL, etc.) |

Stats stored as a single JSON blob. Adding a new metric requires only a Java class change,
not a schema migration.

#### `table_operations_history`
Append-only audit log of completed job records. Written by the Optimizer Service after
each PATCH call.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT AUTO_INCREMENT PK | |
| `table_uuid` | VARCHAR(36) NOT NULL | Indexed |
| `operation_type` | VARCHAR(64) NOT NULL | |
| `status` | VARCHAR(32) NOT NULL | `SUCCESS` or `FAILED` |
| `job_id` | VARCHAR(255) | Jobs Service job ID |
| `result` | TEXT (JSON) | `JobResult` — orphanFilesDeleted, bytesDeleted, durationMs, errorMessage |
| `created_at` | DATETIME NOT NULL | |

---

### 4.2 API Surface

#### Optimizer Service (port 8003)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| PUT | `/v1/table-operations/{id}` | `UpsertTableOperationsRequest` | 200 `TableOperationsDto` | Idempotent — inserts PENDING on first call, no-op on repeat |
| GET | `/v1/table-operations?operationType=X` | — | `List<TableOperationsDto>` | Returns PENDING + SCHEDULED rows only |
| PATCH | `/v1/table-operations/{id}` | `PatchTableOperationRequest` | 200 | Valid transitions: SCHEDULED→SUCCESS, SCHEDULED→FAILED. Returns 409 otherwise. |
| PUT | `/v1/table-stats/{tableUuid}` | `UpsertTableStatsRequest` | 200 | Upsert; last-writer-wins |
| GET | `/v1/table-stats/{tableUuid}` | — | `TableStatsDto` | |
| POST | `/v1/table-operations-history` | `TableOperationsHistoryDto` | 201 | Append-only |
| GET | `/v1/table-operations-history/{tableUuid}` | `?limit=` | `List<TableOperationsHistoryDto>` | |

#### Key request / response models

**`UpsertTableOperationsRequest`**
```json
{
  "tableUuid": "...",
  "databaseName": "db1",
  "tableName": "tbl1",
  "operationType": "ORPHAN_FILES_DELETION",
  "metrics": { "tableSizeBytes": 100, "numSnapshots": 5, "numCurrentFiles": 200 }
}
```

**`PatchTableOperationRequest`**
```json
{
  "status": "SUCCESS",
  "metrics": { "orphanFilesDeleted": 10, "bytesDeleted": 5000, "durationMs": 3000 }
}
```

**`UpsertTableStatsRequest`**
```json
{
  "databaseId": "db1",
  "tableName": "tbl1",
  "stats": {
    "snapshotMetrics": { "numSnapshots": 5, "tableSizeBytes": 100, "numCurrentFiles": 200 },
    "commitDelta": { "numFilesAdded": 10, "numFilesDeleted": 2 }
  },
  "tableProperties": { "maintenance.optimizer.ofd.enabled": "true" }
}
```

---

### 4.3 Data Flows

#### Flow 1: Commit → Stats Ingestion

```mermaid
sequenceDiagram
    participant Client as Iceberg Client
    participant TS as Tables Service<br/>(IcebergSnapshotsServiceImpl)
    participant OTC as OptimizerTableStatsClient
    participant OS as Optimizer Service<br/>(PUT /v1/table-stats/:uuid)
    participant DB as Optimizer DB<br/>(table_stats)

    Client->>TS: PUT /v1/databases/:db/tables/:tbl/iceberg/v2/snapshots
    TS->>TS: save table (openHouseInternalRepository.save)
    TS->>TS: loop snapshot JSON summaries:<br/>accumulate numFilesAdded, numFilesDeleted,<br/>tableSizeBytes, numCurrentFiles
    TS->>OTC: reportCommitStats(tableUuid, databaseId, tableName,<br/>clusterId, tableVersion, tableLocation,<br/>numFilesAdded, numFilesDeleted,<br/>tableSizeBytes, numCurrentFiles,<br/>tableProperties)
    Note over OTC: builds JSON body; non-blocking subscribe()
    OTC-->>OS: PUT /v1/table-stats/:tableUuid  async
    OS->>DB: UPSERT table_stats row<br/>(find-by-uuid or insert)
    DB-->>OS: saved row
    OS-->>OTC: 200 OK
    Note over OTC: errors logged as WARN, swallowed
    TS-->>Client: 200 OK (commit succeeds regardless of stats result)
```

#### Flow 2: Analyzer Loop

```mermaid
sequenceDiagram
    participant App as AnalyzerApplication<br/>(CommandLineRunner)
    participant Runner as AnalyzerRunner
    participant StatsRepo as TableStatsRepository<br/>(optimizer DB)
    participant OpsRepo as TableOperationsRepository<br/>(optimizer DB)
    participant Analyzer as OperationAnalyzer<br/>(e.g. OrphanFilesDeletionAnalyzer)
    participant Cadence as CadencePolicy

    App->>Runner: analyze()
    Runner->>StatsRepo: findAll()
    StatsRepo-->>Runner: all TableStatsRow entries (all tables with committed data)
    Runner->>Runner: map to TableSummary list<br/>(uuid, databaseId, tableId, tableProperties, stats)

    loop for each OperationAnalyzer bean
        Runner->>OpsRepo: findByTypeAndStatuses(operationType, PENDING or SCHEDULED)
        OpsRepo-->>Runner: active rows indexed by tableUuid

        loop for each TableSummary
            Runner->>Analyzer: isEnabled(table)
            alt table opt-in property = "true"
                Runner->>Runner: currentOp = opsByUuid.get(tableUuid)
                Runner->>Analyzer: shouldSchedule(table, Optional currentOp)
                Analyzer->>Cadence: shouldSchedule(currentOp)
                alt no active row
                    Cadence-->>Analyzer: true
                else PENDING
                    Cadence-->>Analyzer: true (but runner skips insert — row exists)
                else SCHEDULED
                    Cadence-->>Analyzer: false
                else SUCCESS and scheduledAt older than successRetryInterval
                    Cadence-->>Analyzer: true
                else FAILED and scheduledAt older than failureRetryInterval
                    Cadence-->>Analyzer: true
                end
                alt shouldSchedule=true AND no active row
                    Runner->>OpsRepo: save new row: id=UUID, status=PENDING, version=0
                end
            end
        end
    end
    Runner-->>App: (returns; process exits)
```

#### Flow 3: Scheduler Loop

```mermaid
sequenceDiagram
    participant App as SchedulerApplication<br/>(CommandLineRunner)
    participant Runner as SchedulerRunner
    participant OpsRepo as TableOperationsRepository<br/>(optimizer DB)
    participant StatsRepo as TableStatsRepository<br/>(optimizer DB)
    participant Packer as BinPacker
    participant Jobs as JobsServiceClient
    participant JS as Jobs Service<br/>(POST /jobs)
    participant Spark as BatchedOrphanFilesDeletionSparkApp
    participant OS as Optimizer Service<br/>(PATCH /v1/table-operations/:id)

    App->>Runner: schedule()
    Runner->>OpsRepo: findPendingByType(operationType)
    OpsRepo-->>Runner: PENDING rows

    Runner->>StatsRepo: findAllById(tableUuids)
    StatsRepo-->>Runner: TableStatsRow entries
    Runner->>Runner: build fileCount map by tableUuid<br/>(missing uuid defaults to 0)

    Runner->>Packer: pack(pending, fileCountByUuid, maxFilesPerBin)
    Note over Packer: sort desc by fileCount<br/>greedy first-fit descending<br/>oversized tables get own bin
    Packer-->>Runner: bins, list of lists

    loop for each bin
        loop for each row in bin
            Runner->>OpsRepo: claimOperation(id, version, now)<br/>UPDATE SET status=SCHEDULED, scheduledAt=now<br/>WHERE id=? AND version=?
            OpsRepo-->>Runner: 1 (claimed) or 0 (already claimed)
        end
        Note over Runner: claimed = rows where claimOperation returned 1

        alt claimed is not empty
            Runner->>Jobs: launch(jobName, operationType,<br/>tableNames, operationIds, resultsEndpoint)
            Jobs->>JS: POST /jobs jobName, clusterId,<br/>jobConf.jobType, args: tableNames, operationIds, resultsEndpoint
            JS-->>Jobs: jobId
            Jobs-->>Runner: Optional.of(jobId)

            JS->>Spark: starts BatchedOrphanFilesDeletionSparkApp

            loop for each table in parallel (thread pool)
                Spark->>Spark: deleteOrphanFilesWithMetrics(table, ttl)
            end

            loop for each completed table
                Spark->>OS: PATCH /v1/table-operations/:operationId<br/>status=SUCCESS or FAILED, metrics
                OS-->>Spark: 200 OK
            end

        else all rows already claimed
            Runner->>Runner: log "already claimed; skipping"
        end
    end

    Runner-->>App: (returns; process exits)
```

---

### 4.4 Error Handling

| Scenario | Component | Behavior |
|---|---|---|
| Optimizer Service down during commit | OptimizerTableStatsClient | `onErrorResume` swallows; commit succeeds; stats missing for that interval |
| Stats row has concurrent writers | Optimizer Service | Last writer wins (no version guard on stats); acceptable for approximate signals |
| Analyzer crashes mid-loop | AnalyzerRunner | Re-run is safe — no duplicate PENDING rows (active-row check before insert) |
| Concurrent analyzer instances | AnalyzerRunner | Both may insert PENDING for the same table; Scheduler version guard drops duplicate at claim time |
| Job launch fails after claim | SchedulerRunner | Row stays SCHEDULED; requires watchdog to detect stale rows (**not yet implemented**) |
| Concurrent scheduler instances | SchedulerRunner | `claimOperation` UPDATE WHERE version= prevents double-submit |
| Spark table failure (partial) | BatchedOFD Spark App | Per-table PATCH with FAILED; job continues for remaining tables; throws only if ALL fail |
| PATCH call fails from Spark | BatchedOFD Spark App | Operation stays SCHEDULED — same watchdog dependency |
| DB connection failure in Analyzer/Scheduler | Spring | Propagates as exception; process exits non-zero; next invocation retries |

---

### 4.5 Observability

| Signal | Source | Notes |
|---|---|---|
| Stats delivery latency/error | `OptimizerTableStatsClient` WARN log | Per-commit; surfaced when optimizer service is degraded |
| PENDING rows count (per operation type) | `GET /v1/table-operations?operationType=X` | Backlog indicator; rising count = scheduler not keeping up |
| SCHEDULED rows with stale `scheduledAt` | Direct DB query | Proxy for orphaned jobs; watchdog will alert on this |
| Job completion rate | `table_operations_history` | SUCCESS/FAILED ratio over time |
| End-to-end smoke test | `smoke-test.sh` | Commit → stats populated → PENDING → SCHEDULED, validated in CI |

No metrics endpoint is currently wired (Spring Actuator not enabled in this PR). Adding
Micrometer counters for PUT/PATCH operations is a straightforward follow-up.

---

## 5. Table: Opt-In Mechanism

Tables opt in per operation type via table properties. Current keys:

| Property | Value | Meaning |
|---|---|---|
| `maintenance.optimizer.ofd.enabled` | `true` | Enable Orphan Files Deletion |

This matches the `maintenance.*` prefix pattern used by existing table maintenance
properties. Future operations will follow the same pattern.

Database-level opt-in (apply to all tables in a database) is not yet implemented.
A separate discussion is in progress in the Tables Service team.

---

## Appendix A: Design Decisions

Each decision classified as one-way door (hard to change without breaking callers or
migrating data) or two-way door (reversible with localized changes).

| ID | Topic | Decision | Alternative Rejected | Door |
|---|---|---|---|---|
| D-1 | Stats storage | `table_stats` stored in optimizer DB, not HTS | HTS ownership (would require extra HTTP hop from analyzer to HTS; pagination for large catalogs) | Two-way |
| D-2 | Stats delivery | Fire-and-forget from Tables Service | Synchronous delivery (would block table commits when optimizer is degraded) | One-way (semantics contract) |
| D-3 | Metrics schema | Single JSON blob (`stats TEXT`) | Individual columns per metric (ALTER TABLE on every new metric) | One-way (column semantics) |
| D-4 | Table enumeration | Read `table_stats` directly via JPA | `GET /databases` → `GET /tables/{db}` per DB (O(databases) HTTP calls; Tables Service outage = analyzer blocked) | Two-way |
| D-5 | Analyzer→DB access | Direct JPA (shared credentials) | HTTP to Optimizer Service REST API (extra hop; analyzer is a co-owned component) | Two-way |
| D-6 | Operation identity | Client-generated UUID in PUT path | Server-generated UUID on first insert (requires two-step create+update) | Two-way |
| D-7 | Atomic claim | `UPDATE WHERE version=` (manual optimistic lock) | `@Version` Hibernate (can't distinguish "lost race" from "error") | Two-way |
| D-8 | Cadence logic | `CadencePolicy` composable class | Hardcode inside each analyzer's `shouldSchedule` (copy-paste for every new op type) | Two-way |
| D-9 | PENDING on refresh | Analyzer skips insert for existing PENDING rows | Update existing PENDING row with fresh metrics (unnecessary; scheduler uses stats from `table_stats`) | Two-way |
| D-10 | tableProperties in stats | Written to `table_stats` at every commit | Call Tables Service HTTP at analysis time (N HTTP calls per analyzer run) | Two-way |
| D-11 | Batch scheduling | Group tables into file-count bins (FFD) | One job per table (O(tables) Jobs Service calls; existing pattern that didn't scale) | Two-way |
| D-12 | Partial Spark failure | Success if any table succeeds; FAILED per table otherwise | Fail-fast (one error cancels all remaining tables in the bin) | Two-way |
| D-13 | Concurrent instance protection | Claim-before-submit via version guard | Submit-then-claim (race window between two schedulers) | One-way (correctness) |
| D-14 | Stale SCHEDULED watchdog | Not implemented in this PR | Implement watchdog (reset rows with scheduledAt > N hours to PENDING) | **One-way risk** — without watchdog, stuck tables require manual DB intervention |

---

## Appendix B: Open Questions

| Question | Owner | Status |
|---|---|---|
| Stale SCHEDULED watchdog — what is the right reset threshold? Should it live in the Scheduler or as a separate process? | mkuchenb | Open — blocking for production readiness |
| Unbounded `findAll` on `table_stats` — what is the expected catalog size? At what point do we need keyset pagination? | mkuchenb | Open — needs capacity estimate |
| Database-level opt-in — how should a DB-level flag interact with per-table flags? Override, AND, OR? | Tables Service team | Open — in-progress discussion |
| Concurrent analyzer insert — add partial unique index on `(table_uuid, operation_type, status)` or rely on version guard? | mkuchenb | Open — two-way door, can defer |
| Observability — add Micrometer counters for PATCH outcomes, PENDING backlog gauge? | mkuchenb | Open — straightforward follow-up |

---

## Appendix C: Component Reference

See [`components.md`](components.md) for per-component responsibility, interface, config, and gaps.

See [`architecture.md`](architecture.md) for standalone sequence diagrams with failure modes.

See [`pr-decisions.md`](pr-decisions.md) for the full PR thread decision log.
