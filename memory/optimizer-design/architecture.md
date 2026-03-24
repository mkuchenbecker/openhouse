# Optimizer Architecture — Sequence Diagrams

Three flows, each self-contained. All actors from the current implementation.

---

## 3a. Commit → Stats Ingestion

Every Iceberg table commit triggers a fire-and-forget stats push from the Tables
Service to the Optimizer Service. This is the sole write path into `table_stats`.

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

### Failure Modes

| Scenario | Behavior |
|---|---|
| Optimizer Service is down | `onErrorResume` swallows; commit succeeds; that interval's stats are missing until next commit |
| Snapshot summary JSON malformed | `parseLong` returns 0 for bad fields; warning logged; stats recorded with partial data |
| Concurrent commits to same table | Last writer wins on `table_stats` (no version guard on stats row); acceptable since stats are approximate signals |
| No snapshots in request | `reportCommitStats` not called; `table_stats` row unchanged |

---

## 3b. Analyzer Loop

The Analyzer is a `CommandLineRunner` — it runs once and exits. For each registered
`OperationAnalyzer` it loads the current active-operation state from the DB, iterates
all tables in `table_stats`, and inserts PENDING rows for eligible tables.

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

### Failure Modes

| Scenario | Behavior |
|---|---|
| Analyzer crashes mid-loop | Safe to re-run — insert is idempotent at the table level; already-PENDING rows are not duplicated (checked before insert) |
| table_stats empty (no commits yet) | `findAll()` returns empty list; loop body never executes; no PENDING rows created |
| Table has active SCHEDULED row | `shouldSchedule` returns false; analyzer skips it; in-flight job is not disturbed |
| Concurrent analyzer instances | Both see same `table_stats`; both may attempt insert for the same table — no DB-level uniqueness constraint; result is duplicate PENDING rows. Scheduler's version guard handles duplicates (claims first, skips rest). Current implementation does not prevent this. |
| DB connection failure | Spring throws on `findAll()`; process exits with non-zero; next scheduled run retries |

---

## 3c. Scheduler Loop

The Scheduler is also a `CommandLineRunner` — runs once and exits. It reads PENDING
rows, looks up file counts for bin-packing, claims rows atomically before submitting,
and submits one batched Spark job per bin.

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

### Failure Modes

| Scenario | Behavior |
|---|---|
| Job launch fails after claim | Rows stay SCHEDULED. Another scheduler won't retry (version guard). Requires a watchdog to detect stale SCHEDULED rows and reset to PENDING. **Known gap: watchdog not yet implemented.** |
| Concurrent scheduler instances | `claimOperation` UPDATE WHERE version= acts as optimistic lock. First instance claims; second gets 0 rows back and skips. No double-submit. |
| Spark table failure (not all tables) | Per-table PATCH with FAILED status. Job continues for remaining tables. Exception thrown only if ALL tables fail. |
| PATCH call fails from Spark | `patchOperationStatus` throws — the exception propagates out of the future, which causes `future.get()` to re-throw wrapped in `RuntimeException`. The overall job counts this as a failure. |
| Jobs Service unreachable | `JobsServiceClient` catches all exceptions, logs error, returns `Optional.empty()`. Claimed rows stay SCHEDULED — same watchdog scenario as launch failure. |
| Table has no stats entry | `fileCountByUuid.getOrDefault(uuid, 0L)` returns 0. Table is still schedulable; placed in a bin as if it has 0 files. |
| Table larger than maxFilesPerBin | Assigned its own bin (oversized bin allowed — tables are never dropped). |
