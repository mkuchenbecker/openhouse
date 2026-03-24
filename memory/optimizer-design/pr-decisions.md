# Optimizer PR #4 — Design Decisions

Extracted from all 57 review threads. Each entry: decision made, alternative rejected,
and which thread(s) it came from. Organized by topic.

---

## API Design

**[api-1] Request body ≠ internal DTO**
- Decision: Controller accepts a narrow request model (`UpsertTableOperationsRequest`,
  `PatchTableOperationRequest`) distinct from the internal DTO. They may share fields
  but evolve independently.
- Rejected: Passing the DTO directly to the controller method (ambiguous field ownership,
  API contract coupled to internal representation).
- Threads: 1, 28

**[api-2] Typed enums in DTOs**
- Decision: `TableOperationsDto.status` is `OperationStatus`; `operationType` is
  `OperationType`. History DTO gets `OperationHistoryStatus {SUCCESS, FAILED}`.
  All string fields for status/type replaced with enums.
- Rejected: String fields in DTOs while enums exist in entity layer — the enums existed
  but were silently bypassed by the DTO.
- Threads: 3, 9

**[api-3] Typed nested classes for JSON payloads**
- Decision: `Map<String, Object>` replaced with typed nested classes: `OperationMetrics`
  (tableSizeBytes, numSnapshots, numCurrentFiles), `JobResult` (orphanFilesDeleted,
  bytesDeleted, durationMs, errorMessage, errorType), `TableStats` (SnapshotMetrics +
  CommitDelta). No dynamic maps anywhere in the API surface.
- Rejected: `Map<String, Object>` for flexible payload (loses type safety, forces callers
  to cast, no IDE/compiler help).
- Threads: 4, 5, 10, 11, 24, 25

**[api-4] HTTP 201 on appendHistory is correct**
- Decision: `POST /table-operations-history` always returns 201; the status field in the
  body is the operation outcome, not the HTTP status. Spring propagates any service
  exception before the 201 is constructed.
- Threads: 2

**[api-5] No protos**
- Decision: Plain JSON DTOs over REST; no protobuf. Consistent with existing pattern
  in the rest of the project (housetables, tables).
- Threads: 6

---

## Entity / Persistence Model

**[entity-1] stats as a single JSON blob, not individual columns**
- Decision: `table_stats` stores all stats in a single `TEXT` column (`stats TEXT`).
  Java uses a typed `TableStats` class with `SnapshotMetrics` and `CommitDelta`
  inner classes. Adding a new metric = Java class change only, no schema migration.
- Rejected: Individual columns per metric (every new metric = ALTER TABLE in prod).
- Threads: 29

**[entity-2] table_uuid as single-column PK for table_stats**
- Decision: `table_uuid VARCHAR(36) NOT NULL PRIMARY KEY`. `database_id` and
  `table_name` are regular NOT NULL columns. A re-created table gets a new UUID
  and starts clean.
- Rejected: Composite `(database_id, table_id)` PK — `table_id` was actually a
  table name, not a UUID; naming was misleading and the composite key added complexity.
- Threads: 30

**[entity-3] No hand-written Hibernate JsonType / HibernateConfig**
- Decision: First pivot: replace with standard JPA `AttributeConverter<T, String>`
  (no external dependency). Second pivot: use `@TypeDef/@Type(type="json")` from
  `com.vladmihalcea:hibernate-types-55` (cleaner, no boilerplate converter for
  `Map<String,String>` and nested POJOs).
- Rejected: Custom `JsonType` + SPI file registration (rolled our own when a library
  already handles this).
- Threads: 8, 23, 53, 54

**[entity-4] Nullable metrics null-through in converter**
- Decision: PENDING rows created by the Analyzer without an initial metrics payload
  have `metrics = null`. The `AttributeConverter` stores NULL in the column and
  restores null on read. Callers must null-check.
- Threads: 31

**[entity-5] IllegalStateException (not IllegalArgumentException) in converters**
- Decision: A deserialization failure in a converter means the DB contains data the
  application cannot interpret — that is inconsistent persistent state, not a bad
  caller argument.
- Threads: 32

**[entity-6] Shared apps/optimizer module**
- Decision: `TableOperationRow`, `TableStatsRow`, and their repositories live in a
  single `apps/optimizer` library module. Both analyzer and scheduler depend on it.
  Deployment topology (separate JARs) does not dictate code organization (shared entity
  definition).
- Rejected: Duplicate entity classes in each app (same table, two JPA mappings — prone
  to drift).
- Threads: 55

---

## Stats Ingestion

**[stats-1] tableProperties stored in table_stats alongside stats**
- Decision: `table_stats` carries a `table_properties TEXT` column (JSON blob of the
  full table property map). Written by `OptimizerTableStatsClient` at every commit.
  Lets the Analyzer decide scheduling eligibility (opt-in flags) from the DB without
  calling the Tables Service.
- Rejected: Calling Tables Service HTTP to look up properties at analysis time (N HTTP
  calls per analyzer run).
- Threads: 47

**[stats-2] Typed parameters in reportCommitStats, not raw JSON**
- Decision: `OptimizerTableStatsClient.reportCommitStats` takes typed Java values
  (`numFilesAdded`, `numFilesDeleted`, `tableSizeBytes`, `numCurrentFiles`,
  `tableProperties`). Snapshot JSON parsing lives in `IcebergSnapshotsServiceImpl`
  before the call.
- Rejected: Passing raw snapshot JSON strings to the client (parsing in wrong layer,
  caller can't validate).
- Threads: 37

**[stats-3] Fire-and-forget — stats errors never fail a commit**
- Decision: `OptimizerTableStatsClient` calls `.onErrorResume(e -> Mono.empty()).subscribe()`.
  Stats delivery is best-effort. If the optimizer service is down, the commit succeeds
  and that interval's stats are simply missing.
- Rationale: Stats are a derivative signal, not authoritative data. A stats outage
  should degrade gracefully, not cascade to table writes.
- Threads: 36

---

## Data Access

**[data-1] Date-bounded queries — no unbounded findAll on table_operations**
- Decision: `getAllTableOperations` takes an `Instant since` parameter; the unbounded
  `findAll()` path is removed. The Analyzer always has a lookback window and passes it.
- Rejected: Unbounded query (O(100M rows/day) → OOM).
- Threads: 17, 18, 20

**[data-2] findByTypeAndStatuses with caller-supplied statuses**
- Decision: Repository query accepts `Collection<String> statuses` so the caller
  controls what "active" means. The analyzer passes `{"PENDING","SCHEDULED"}`.
- Rejected: Hardcoding `status IN ('PENDING','SCHEDULED')` — business logic embedded
  in the data layer.
- Threads: 14, 51

**[data-3] Mapper for entity↔DTO conversion in appendHistory**
- Decision: `OptimizerMapper.toRow(TableOperationsHistoryDto)` handles the conversion
  so `appendHistory` is a single `mapper.toRow(dto)` call, same pattern as housetables.
- Threads: 21

---

## Analyzer Design

**[analyzer-1] Table enumeration from table_stats (JPA), not Tables Service HTTP**
- Decision: The Analyzer reads `table_stats` directly via JPA (`statsRepo.findAll()`).
  Any table with committed data has a `table_stats` row. No N+1 HTTP calls to the
  Tables Service.
- Rejected: `GET /databases` → `GET /tables/{db}` per database (O(databases) HTTP
  calls; Tables Service down = analyzer blocked).
- Threads: 42, 49

**[analyzer-2] Internal TableSummary POJO — no coupling to Tables Service client model**
- Decision: `OperationAnalyzer` interface takes `TableSummary` (internal POJO), not
  `GetTableResponseBody` (generated client class). `TablesServiceClient` (now removed)
  would have mapped to it; now `AnalyzerRunner.toSummary()` maps from `TableStatsRow`.
- Rejected: Using the generated `GetTableResponseBody` directly in the analyzer domain
  (compile-time coupling to Tables Service client library).
- Threads: 44

**[analyzer-3] CadencePolicy extracted as a composable class**
- Decision: `CadencePolicy(successRetryInterval, failureRetryInterval)` encapsulates
  the time-based scheduling decision. `OrphanFilesDeletionAnalyzer` injects and
  delegates to it. Future operation types reuse `CadencePolicy` with different
  configured intervals — no duplication.
- Rejected: Hardcoding cadence logic inside each analyzer's `shouldSchedule` (forces
  copy-paste for every new operation type).
- Threads: 46

**[analyzer-4] PENDING → reschedule (refresh, not idempotent skip)**
- Decision: When `shouldSchedule` sees an existing PENDING row, it returns `true`
  BUT `AnalyzerRunner` only inserts if no active row exists. Net effect: PENDING rows
  are left alone; a new PENDING row is only created when there is no active row at all.
- Rationale: The analyzer doesn't need to update existing PENDING rows — the scheduler
  picks them up as-is.
- Threads: 43

**[analyzer-5] AnalyzerRunner.analyze() — not CommandLineRunner**
- Decision: `AnalyzerRunner` exposes a typed `analyze()` method. `AnalyzerApplication`
  owns the `CommandLineRunner` bean that calls `runner.analyze()`. Cleaner separation
  and easier to test.
- Threads: 52

**[analyzer-6] Config values for magic numbers**
- Decision: Retry intervals (`ofd.success-retry-hours`, `ofd.failure-retry-hours`) and
  HTTP retry settings (max attempts, backoff) live in `application.properties` with
  `@Value` defaults.
- Rejected: Hardcoded 24 / 1 in source code.
- Threads: 40, 45

**[analyzer-7] TableOperationRecord not TableOperationView**
- Decision: Renamed to avoid confusion with SQL views.
- Threads: 41

---

## Architecture

**[arch-1] No OptimizerServiceClient — analyzer connects directly to optimizer DB**
- Decision: The Analyzer is a component of the Optimizer, not an external caller.
  It reads `table_operations` and `table_stats` directly via JPA, same credentials
  as the REST service. No HTTP hop between analyzer and optimizer.
- Rejected: Analyzer calls `GET /v1/table-operations?operationType=X` over HTTP (extra
  network hop, optimizer service must be up, REST API rate limits apply to own service).
- Threads: 39, 47

**[arch-2] Optimizer REST service is required (not a lambda)**
- Decision: The Scheduler uses REST to claim operations and report outcomes; that
  requires a running HTTP server. The analyzer is the lambda-style component (runs
  once and exits); the REST service is separate.
- Threads: 22, 35

**[arch-3] Pivot: table_stats lives in optimizer DB, not HTS**
- Decision (final): `table_stats` owned by the optimizer service (MySQL). The Tables
  Service writes to it via HTTP (`OptimizerTableStatsClient`). The Analyzer reads it
  directly via JPA.
- Earlier pivot: `table_stats` was briefly proposed to live in HTS. Moved back to
  optimizer because: (a) optimizer DB is already the shared store for both analyzer
  and scheduler, (b) no need for an additional HTTP hop from the analyzer to HTS,
  (c) HTS stats would need pagination/filtering for large catalogs.
- Threads: 12, 48, 49, 50

---

## Concurrency

**[concurrency-1] Claim-before-submit prevents double-scheduling**
- Decision: `SchedulerRunner.submitBin` calls `claimOperation` (UPDATE WHERE version=)
  first; only claimed rows are submitted. If job launch fails, rows stay SCHEDULED
  (not PENDING) — a watchdog handles stale SCHEDULED rows.
- Rejected: Submit-then-claim (race window between two scheduler instances both seeing
  PENDING and both submitting).
- Threads: 57

**[concurrency-2] OptimisticLockException TODO on table_stats upsert**
- Decision: High-frequency write paths (every Iceberg commit) can race on
  `upsertTableStats`. Added a TODO to catch `OptimisticLockException` and retry
  rather than surfacing a 500.
- Threads: 27

---

## Operations / Infra

**[ops-1] Docker / compose must be validated end-to-end**
- Decision: Added smoke test (`smoke-test.sh`) that: triggers a Spark commit, confirms
  `table_stats` is populated, runs the analyzer container, asserts PENDING row created,
  runs the scheduler container, asserts row transitions to SCHEDULED.
- Threads: 34
