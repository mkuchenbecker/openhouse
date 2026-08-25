# Data Layout Strategy Generation

Operation type: `DATA_LAYOUT_STRATEGY_GENERATION`

## What it does

Generation is the first half of the data-layout-strategy pair. For each opted-in
table it:

1. Reads the table's file, partition, and snapshot statistics.
2. Runs the data-layout strategy generator to produce candidate **compaction
   strategies** — recommendations for how the table's data files should be
   rewritten (target file size, min/max size ratios, input-file thresholds,
   concurrency, etc.).
3. Persists those strategies onto the table itself, in the table property
   `write.data-layout.strategies` (and, for partitioned tables, partition-scope
   strategies in `write.data-layout.partition-strategies`).

Generation does **not** rewrite any data. It only analyzes the table and records
a recommendation. Applying that recommendation is the job of the separate
`DATA_LAYOUT_STRATEGY_EXECUTION` operation, which reads the
`write.data-layout.strategies` property to know a table has pending work. That
stored property is the single signal that couples generation to execution.

Because the recommendation should track the table's current shape, generation is
a pure cadence job: it periodically re-generates strategies regardless of whether
a prior recommendation was ever executed.

## When it runs

A table is scheduled for generation only when **all** of the following hold
(identical cadence contract to orphan-files-deletion):

1. **Opt-in.** The table sets
   `maintenance.optimizer.dataLayoutStrategyGeneration.enabled=true` in its table
   properties. Tables without this flag are ignored entirely.
2. **No active operation already in flight.** If the table already has a
   non-CANCELED generation operation (PENDING, SCHEDULING, or SCHEDULED), the
   scheduler owns it and the analyzer stays out. A CANCELED row does not block.
3. **Cadence elapsed since the last completed run.**
   - No prior history → schedule immediately.
   - Last run SUCCESS → wait `dls-generation.success-retry-hours` (default 24h)
     after it completed.
   - Last run FAILED → wait `dls-generation.failure-retry-hours` (default 1h) —
     shorter, so transient failures recover quickly.

## What the batched app does per table

The scheduler bin-packs eligible tables and launches one Spark job
(`BatchedDataLayoutStrategyGenerationSparkApp`, job type
`DATA_LAYOUT_STRATEGY_GENERATION_BATCH`). Each table in the batch is processed by
its own worker thread; per-table failures are caught and reported back
independently, so one bad table does not sink the batch.

Per table the app:

1. Builds file / partition / snapshot stats for the table.
2. Runs the strategy generator (table scope always; partition scope additionally
   for partitioned tables).
3. Saves the generated strategies into the table property
   `write.data-layout.strategies` (and the partition-scope property for
   partitioned tables) — the signal the execution operation consumes.

## Configuration

### Per-table opt-in (table property)

| Property | Meaning |
|---|---|
| `maintenance.optimizer.dataLayoutStrategyGeneration.enabled` | Set to `true` to opt the table into strategy generation. Absent/false → the table is skipped. |

### Analyzer cadence (analyzer `application.properties`)

| Key | Default | Meaning |
|---|---|---|
| `dls-generation.success-retry-hours` | `24` | Hours to wait after a SUCCESS before re-generating. |
| `dls-generation.failure-retry-hours` | `1` | Hours to wait after a FAILED run before retrying. |

### Scheduler bin-packing (scheduler `application.properties`)

Generation is a per-table stats scan, so cost tracks the number of files /
manifests read; bins are packed by **file count** (`TotalFilesBinItem`).

| Key | Default | Meaning |
|---|---|---|
| `optimizer.scheduler.dls-generation.max-files-per-bin` | `1000000` | Max total current-file count per batch. `0` disables this dimension. |
| `optimizer.scheduler.dls-generation.max-tables-per-bin` | `50` | Max number of tables per batch. |

A hard ceiling of `DATA_LAYOUT_STRATEGY_GENERATION_MAX_BATCH_SIZE` (200) tables
per job is enforced at CLI-parse time as a footgun stop; the per-batch operating
point is the `max-tables-per-bin` cap above.
