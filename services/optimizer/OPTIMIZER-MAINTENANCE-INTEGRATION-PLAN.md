# Optimizer maintenance-operation integration — plan

Integrate every table-scoped OpenHouse maintenance operation into the continuous **Optimizer** service,
using Orphan-Files-Deletion (OFD) as the reference. Each operation is a parallelizable milestone shipped
as its own **draft PR off `main`, targeting the `mkuchenbecker/openhouse` fork**, and delegated as a
subtask.

## Architecture (already in place, operation-agnostic)

- **Analyzer** (`services/optimizer/analyzer`): `AnalyzerRunner` iterates databases and, per opted-in
  table, asks an `OperationAnalyzer` `@Component` whether to upsert a PENDING operation. The analyzer app
  auto-loops over every `OperationAnalyzer` bean — **no app wiring per operation**. OFD reference:
  `CadenceBasedOrphanFilesDeletionAnalyzer` + `CadencePolicy`.
- **Scheduler** (`services/optimizer/scheduler`): `SchedulerRunner` holds an immutable
  `Map<OperationType, BinPacker>` registry; it reads PENDING rows, dedups per table, bin-packs, CAS-claims,
  and launches one batched Spark job per bin via `JobsServiceClient` (`jobConf.jobType = <OP>.name()`,
  args `--tableNames/--operationIds/--resultsEndpoint`). The scheduler app auto-loops over the registry.
  Registration is one line in `SchedulerConfig.registerOperation(type, packer)`.
- **Jobs**: the Spark app for `<OP>` must be **batched + results-aware** — accept the batched args and
  PATCH per-operation SUCCESS/FAILED back to the results endpoint. **Only OFD
  (`BatchedOrphanFilesDeletionSparkApp`) does this today.**
- **Persistence**: `operation_type` is `VARCHAR(50)` + `@Enumerated(STRING)` → **adding an operation type
  needs no DB migration**, only enum constants in `db/OperationType`, `model/OperationTypeDto`,
  `api/spec/OperationType`, and the generated wire enum.

## Per-operation vertical slice (the "module")

1. Enum value in the three enums (+ generated wire enum). No DDL.
2. `OperationAnalyzer` `@Component` — cadence-based (mirror OFD) or stats-driven.
3. `SchedulerConfig.registerOperation(type, BinPacker)` + bin-cap config.
4. `Batched<Op>SparkApp` (jobs side) — the real lift; speaks the batched/results protocol.
5. Opt-in table property `maintenance.optimizer.<op>.enabled` + `application.properties` cadence/bin config.
6. Tests: analyzer unit test (mirror `CadenceBasedOrphanFilesDeletionAnalyzerTest`, ~200 lines) + batched-app test.

Each subtask **ports its eligibility logic from the matching `apps/spark/.../scheduler/tasks/*Task.java`**,
which already encodes the domain rules (age thresholds, partitioned-only guards, etc.).

## Milestones

**M0 — foundational (blocks all; not parallel).**
- Extract the batched/results protocol from `BatchedOrphanFilesDeletionSparkApp` into a reusable base
  (arg parse, `BatchEntry` zip, `reportResult`→PATCH, claim/result lifecycle); refactor OFD onto it.
- Pre-add all in-scope `OperationType` enum values in one commit so operation PRs never touch the enums.
- Extract a generic `CadenceBasedAnalyzer(opType, enabledProperty, retryConfig)` so cadence operations
  collapse to ~10 lines.
- Verify: build + existing OFD analyzer/scheduler/app tests green.

**Operation milestones (each a draft PR off `main`; parallel except where noted):**

| # | Operation(s) | Analyzer | Jobs-side lift | Notes |
|---|---|---|---|---|
| M1 | `SNAPSHOTS_EXPIRATION` | cadence | build batched app | easiest |
| M2 | `STAGED_FILES_DELETION` | cadence | build batched app | easiest |
| M3 | `RETENTION` | cadence + partitioned/time-column eligibility | build batched app | port `TableRetentionTask` guard |
| M4 | `DATA_COMPACTION` | **stats-driven** (`numCurrentFiles`, `tableSizeBytes`) + weighted `BinPacker` | build batched app | flagship; highest value/complexity |
| M5 | `DATA_LAYOUT_STRATEGY_GENERATION` + `_EXECUTION` | stats-driven | 2 apps (gen + reuse compaction) | coupled pair; **after M4** |
| M6 | `ORPHAN_DIRECTORY_DELETION` + `TABLE_DIRECTORY_DELETION` | dir/db-scoped (model adaptation) | back onto `OrphanTableDirectoryDeletionSparkApp` | not per-table-UUID — design note required |
| M7 | `TABLE_STATS_COLLECTION` + `SORT_STATS_COLLECTION` | cadence | build batched app(s) | stats-collection is the analyzers' input (foundational) |

Excluded: `REPLICATION` (no Spark app in this repo), `NO_OP`/`SQL_TEST` (not maintenance).

## Coordination / conflict surface

After M0, the only shared files a per-operation PR touches are `SchedulerConfig` (one `registerOperation`
line) and `application.properties` (append). Enums are pre-added by M0. Parallel subtasks run in isolated
**git worktrees** so their working trees don't collide; each is a separate branch off `main`.

## Delegation model

- M0 runs first (I drive or delegate a single subtask), lands as a draft PR, and merges to `main` before
  the operation milestones fan out.
- Each operation milestone is delegated as its own subtask (worktree-isolated), producing a draft PR to
  `mkuchenbecker/openhouse`. M1–M4/M6/M7 parallel; M5 after M4.
