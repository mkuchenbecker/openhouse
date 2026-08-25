# M6 — Directory-deletion integration design note

This note records the model decision for integrating the two **directory/database-scoped**
maintenance operations into the OpenHouse Optimizer:

- `ORPHAN_DIRECTORY_DELETION` — stage-then-delete storage directories whose owning table has been
  dropped and is no longer registered (leftover *orphan* directories).
- `TABLE_DIRECTORY_DELETION` — purge the storage directory of dropped/purged tables.

**Bottom line:** both operations are **database-scoped, not per-live-table**. Rather than force them
into the per-table pipeline (or leave them un-integrated), M6 adds a small, additive **scope**
extension to the schema and a **database-scoped analyzer + scheduler track** that fully drives them
end-to-end on the Optimizer side. The one remaining piece is the per-database storage *scan* inside
the batched Spark app (which needs Tables-Service access); it is called out explicitly below.

## The per-table pipeline these ops have to fit

M0 established a strictly **per-live-table, UUID-keyed** flow:

1. `AnalyzerRunner` iterates `table_stats` rows — **one per live table**, primary key `table_uuid` —
   and creates a `PENDING` `table_operations` row per opted-in table.
2. `table_operations.table_uuid` and `table_operations_history.table_uuid` were **`NOT NULL`**.
3. `SchedulerRunner` groups `PENDING` rows **by `table_uuid`**, loads `table_stats` by `table_uuid`,
   bin-packs, and launches a batched Spark job with `--tableNames` / `--tableUuids` / `--operationIds`.
4. The batched Spark app resolves each entry with `ops.getTable(db.table)` and PATCHes a
   per-`operationId` SUCCESS/FAILED result back.

Every stage assumed a **live table with a UUID and a resolvable `db.table`**.

## Why directory deletion did not fit — and how M6 resolves each blocker

The unit of work is a **directory of a table that no longer exists**. That collided with the
pipeline at three points; M6 addresses each:

| # | Blocker (before) | M6 resolution |
|---|------------------|---------------|
| 1 | **Discovery** — work lives on the filesystem, not in `table_stats`; `AnalyzerRunner` can't see it. | New **`DirectoryDeletionAnalyzerRunner`** enumerates *databases* (`TableStatsRepository.findDistinctDatabaseNames()`) and emits one PENDING op per opted-in database — matching how the reference jobs discover work (per-database scan). This is the closest the Optimizer Service can get without direct storage access. |
| 2 | **No key** — a directory has no `tableUuid`; `table_operations.table_uuid` was `NOT NULL`. | **Schema change (below):** `table_uuid`/`table_name` made NULLABLE; new `operation_scope` column (`TABLE`/`DATABASE`/`DIRECTORY`) carries the target. Database-scoped ops persist with `operation_scope='DATABASE'`, `database_name` set, null `table_uuid`. |
| 3 | **Execution shape** — the scheduler grouped by `table_uuid` and launched `--tableNames`. | New **`SchedulerRunner.scheduleDirectory`** reads PENDING `DATABASE`-scoped rows, bins them by database count (no `table_stats` weight), and launches the `<OP>_BATCH` Spark app with **`--databaseNames`** via `JobsServiceClient.launchDirectory`. Registered in `SchedulerConfig` via `registerDirectoryOperation`. |

## Schema change (additive, non-breaking)

`services/optimizer/src/main/resources/db/optimizer-schema.sql`, both `table_operations` and
`table_operations_history`:

- `table_uuid` and `table_name`: **`NOT NULL` → NULLABLE**.
- add `operation_scope VARCHAR(20)` — `TABLE` (default) / `DATABASE` / `DIRECTORY`. A null value is
  read as `TABLE`.
- add `directory_path VARCHAR(1024)` — nullable; reserved for a future per-directory (`DIRECTORY`)
  discovery path.

**Why it is non-breaking:** every existing per-table operation continues to set
`operation_scope='TABLE'` with a non-null `table_uuid`/`table_name` (enforced by
`TableOperationDto.pending(...)` and the entity `@Builder.Default`). The per-table analyzer,
scheduler, and `findLatest` queries are byte-for-byte unchanged; they never observe a null
`table_uuid`. Only directory ops use the new nullable columns. Fresh environments and the H2 test DB
get the columns from `CREATE TABLE`; existing production databases apply the one-time `ALTER`
statements recorded at the top of the schema file.

## What is fully wired vs. what remains

**Fully wired and unit-tested (Optimizer side, end-to-end):**

- Schema + `OperationScope`/`OperationScopeDto` enums + entity/DTO fields + conversions.
- Repository: `TableOperationsRepository.findByScope`, `TableOperationsHistoryRepository.findLatestByDatabaseScope`.
- Analyzer: `DirectoryOperationAnalyzer` (sibling of `OperationAnalyzer`), `DirectoryDeletionAnalyzerRunner`,
  and the two concrete analyzers (`OrphanDirectoryDeletionAnalyzer`, `TableDirectoryDeletionAnalyzer`),
  wired into `AnalyzerApplication`. Each is behind a per-op opt-in (default **off**) and a cadence
  reusing `CadencePolicy`.
- Scheduler: `SchedulerRunner.scheduleDirectory` + `JobsServiceClient.launchDirectory`, registered in
  `SchedulerConfig`, dispatched by `SchedulerApplication`. Launches `<OP>_BATCH` with `--databaseNames`.
- Spark apps accept `--databaseNames` (and still accept explicit `--tableDirectoryPaths`).

**Remaining piece (Spark-app execution side, documented, not silently dropped):**

- **Per-database directory *scan* inside the batched app.** Turning a database into the specific
  directories to delete requires the Tables-Service list of live/dropped tables (mirroring
  `TablesClient.getOrphanTableDirectories()`) — a networked client not yet wired into the Spark app.
  Until it is, the apps **throw** on a `--databaseNames` entry (reporting FAILED) rather than risk
  deleting live directories by guessing. Because the analyzer opt-in defaults to **off**, this is
  dormant until an operator deliberately enables it. The app is otherwise fully functional when
  invoked with explicit `--tableDirectoryPaths` (e.g. the legacy `JobsScheduler`).

## Why database-scoped (and not per-directory)

The Optimizer Service has DB access only — it cannot list storage directories, so it cannot discover
individual orphan directories. The reference `TABLE_DIRECTORY_DELETION` task is already
database-scoped (the job self-discovers), and `ORPHAN_DIRECTORY_DELETION` becomes database-scoped for
the same reason. The `DIRECTORY` scope and `directory_path` column are in the schema so a future
storage-aware discovery layer can emit per-directory ops without another migration.
