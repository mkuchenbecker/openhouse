# OPTIMIZE

`OPTIMIZE` is an OpenHouse Spark SQL extension that improves an OpenHouse Iceberg table's data
layout: bin-pack compaction by default, or a sort / z-order clustering rewrite when the table
configures clustering keys. It is sugar over the underlying Iceberg maintenance stored procedures.

## Syntax

```sql
OPTIMIZE <table> [FULL] [REWRITE MANIFESTS]
```

- `<table>` — an OpenHouse table identifier (e.g. `openhouse.db.table`).
- `FULL` — *(optional)* recluster everything up to the age floor instead of only the slice added
  since the last run. Has no effect when no clustering keys are configured.
- `REWRITE MANIFESTS` — *(optional)* also compact the table's manifests, in a second commit, after
  the data rewrite.

The command returns `files_before` / `files_after` / `files_removed` / `snapshots_committed`.

## Behavior

With no `optimize.cluster.keys` set, `OPTIMIZE` is a plain bin-pack compaction
(`rewrite_data_files` with defaults). With clustering configured it performs a scoped sort or
z-order rewrite that is **incremental by default**: it rewrites only the forward slice of the
leading key that has arrived since the previous run, bounded by an age floor that keeps it off the
partition a streaming writer is actively extending.

After the data rewrite it compacts merge-on-read position delete files and drops deletes the
rewrite made dangling; on copy-on-write or delete-free tables that step is a no-op.

Snapshot expiration is deliberately **not** part of `OPTIMIZE` — that is `VACUUM`'s job.

## Table properties

Clustering is configured with ordinary (user-settable) table properties:

| Property | Meaning | Default |
| -------- | ------- | ------- |
| `optimize.cluster.keys` | Comma-separated clustering keys. Empty means plain bin-pack. | *(unset)* |
| `optimize.cluster.sort-mode` | `zorder` or `sort`. | `zorder` |
| `optimize.cluster.min-snapshot-age-minutes` | Age floor: snapshots younger than this are held back. | `30` |
| `optimize.cluster.max-commits` | Partial-progress commit budget for one run. | `10` |

`OPTIMIZE` also writes back the state it needs to stay incremental —
`optimize.cluster.hwm-snapshot-id`, `optimize.cluster.config-id` and `optimize.cluster.state` — in
a single atomic property update, so they never disagree. `ANALYZE TABLE <t> COMPUTE CLUSTERING
QUALITY` reads that same state to report how well the table is clustered.

## Interaction with the scheduled maintenance jobs

`OPTIMIZE` refuses to run on a table that has been opted out of platform maintenance, via
`maintenance.disabled = 'true'` or `maintenance.DATA_COMPACTION.disabled = 'true'` — the same
switches the jobs scheduler consults before dispatching work for a table.

**Known gap.** The scheduled data-compaction job does not read `optimize.cluster.*`. It bin-packs
according to the data-layout strategies persisted for the table, so on a clustered table a
scheduled compaction can rewrite files without preserving the clustering `OPTIMIZE` established,
and it does not advance or respect the incremental watermark. Until that job learns this
configuration, treat clustered tables as owned by `OPTIMIZE` rather than by scheduled compaction.
