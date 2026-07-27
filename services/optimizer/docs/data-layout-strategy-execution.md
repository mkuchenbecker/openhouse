# Data Layout Strategy Execution

Operation type: `DATA_LAYOUT_STRATEGY_EXECUTION`

## What it does

Execution is the second half of the data-layout-strategy pair. It **applies** a
strategy that generation already produced, by compacting the table: it rewrites
the table's data files according to the stored strategy's compaction config
(target file size, min/max size ratios, min input files, concurrency, partial
progress). This is the same rewrite path the single-table data-compaction app
uses, driven by the generated strategy rather than by ad-hoc CLI arguments.

Execution never invents a strategy. It only runs one that generation stored in
the table property `write.data-layout.strategies`. That property is the coupling
signal between the two operations.

## When it runs

A table is scheduled for execution only when **all** of the following hold:

1. **Opt-in.** The table sets
   `maintenance.optimizer.dataLayoutStrategyExecution.enabled=true`.
2. **A generated strategy is present (the strategy-presence gate).** The table
   property `write.data-layout.strategies` is set to a **non-empty** strategy
   list. An absent property, a blank value, or the empty list `[]` all count as
   "no pending strategy", and the table is skipped — no execution operation is
   ever created for a table that has nothing to apply. This is what couples
   execution to generation: with no generated strategy, there is nothing to do.
3. **No active operation already in flight** (PENDING / SCHEDULING / SCHEDULED;
   CANCELED does not block) **and cadence elapsed** — same contract as
   orphan-files-deletion:
   - No prior history → schedule immediately.
   - Last run SUCCESS → wait `dls-execution.success-retry-hours` (default 24h).
   - Last run FAILED → wait `dls-execution.failure-retry-hours` (default 1h).

### The strategy-presence gate and its fallback

The presence gate reads `write.data-layout.strategies` from the optimizer's
per-table property map (`TableDto.tableProperties`) — the same map the opt-in
flag is read from. When the strategy blob is present there, the analyzer only
schedules tables that actually have pending work.

Whether that strategy blob is propagated all the way into the optimizer's
**ingested** stats/properties depends on the upstream stats-ingestion pipeline,
which is outside this operation's control. If a deployment does not propagate the
strategy property into optimizer stats, the presence gate has nothing to test and
**degrades to a pure opt-in cadence trigger** — it may schedule an opted-in table
that has no strategy.

That degradation is safe because of the **runtime guarantee**: the batched
execution app re-reads `write.data-layout.strategies` directly from the live
table when it runs, and a table whose property is absent or empty is a **no-op**
(logged, counted as success — nothing to execute yet). So the worst case is a
harmless empty job, never an incorrect rewrite. Presence-gating in the analyzer
is the optimization (don't schedule empty work); the batched app's runtime no-op
is the guarantee.

## What the batched app does per table

The scheduler bin-packs eligible tables and launches one Spark job
(`BatchedDataLayoutStrategyExecutionSparkApp`, job type
`DATA_LAYOUT_STRATEGY_EXECUTION_BATCH`). Each table is processed by its own worker
thread; per-table failures are caught and reported back independently.

Per table the app:

1. Reads `write.data-layout.strategies` from the live table.
2. If it is absent or empty → **no-op** (nothing to execute; reported as success).
3. Otherwise, for each stored strategy, rewrites the table's data files
   (compaction) using that strategy's compaction config, and emits
   added/rewritten file-count and byte metrics.

## Configuration

### Per-table opt-in (table property)

| Property | Meaning |
|---|---|
| `maintenance.optimizer.dataLayoutStrategyExecution.enabled` | Set to `true` to opt the table into strategy execution. Absent/false → the table is skipped. |
| `write.data-layout.strategies` | Written by the generation operation. Must be present and non-empty for execution to be scheduled; also re-checked at runtime. Operators do not set this by hand. |

### Analyzer cadence (analyzer `application.properties`)

| Key | Default | Meaning |
|---|---|---|
| `dls-execution.success-retry-hours` | `24` | Hours to wait after a SUCCESS before re-executing. |
| `dls-execution.failure-retry-hours` | `1` | Hours to wait after a FAILED run before retrying. |

### Scheduler bin-packing (scheduler `application.properties`)

Execution rewrites data files, so the dominant cost is the **volume of bytes**
shuffled, not the raw file count. Bins are packed by table size in bytes
(`TableSizeBytesBinItem`), which is why the weight cap is a byte budget rather
than a file-count budget.

| Key | Default | Meaning |
|---|---|---|
| `optimizer.scheduler.dls-execution.max-bytes-per-bin` | `5497558138880` (5 TiB) | Max total table-size bytes per batch. `0` disables this dimension. |
| `optimizer.scheduler.dls-execution.max-tables-per-bin` | `20` | Max number of tables per batch (smaller than the scan-oriented operations, because each table does real rewrite work). |

A hard ceiling of `DATA_LAYOUT_STRATEGY_EXECUTION_MAX_BATCH_SIZE` (200) tables per
job is enforced at CLI-parse time as a footgun stop; the per-batch operating point
is the `max-bytes-per-bin` / `max-tables-per-bin` caps above.
