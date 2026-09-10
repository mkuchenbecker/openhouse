# OPTIMIZE

`OPTIMIZE` is an OpenHouse Spark SQL extension that improves an OpenHouse Iceberg table's data
layout: bin-pack compaction by default, or a sort / z-order clustering rewrite when the table
configures clustering keys. It drives the Iceberg maintenance actions directly.

## Syntax

```sql
OPTIMIZE <table> [FULL] [REWRITE MANIFESTS]
```

- `<table>` — an OpenHouse table identifier (e.g. `openhouse.db.table`).
- `FULL` — *(optional)* rewrite every file that does not carry the current layout, including files
  clustered under a previous key selection. Has no effect when no clustering keys are configured.
- `REWRITE MANIFESTS` — *(optional)* also compact the table's manifests, in a second commit, after
  the data rewrite.

The command returns `(metric, value)` rows: `files_before`, `files_after`, `files_removed`,
`snapshots_committed`, `files_rewritten`, `bytes_rewritten`, and for clustered tables
`layout_id`, `hwm_seq_before`, `hwm_seq_after`, `files_failed`.

## Behavior

With no `optimize.cluster.keys` set, `OPTIMIZE` is a plain bin-pack compaction. With clustering
configured it performs a sort or z-order rewrite whose progress is tracked two ways at once, both
in table metadata that survives snapshot expiration:

- **Per-file layout stamp.** Every file the rewrite writes records the layout id as its Iceberg
  `sort_order_id`, and carries `openhouse.cluster.layout-id` and `openhouse.cluster.layout` (the
  layout JSON) in its footer (ORC user metadata, Parquet key-value metadata). A file is clustered
  iff its stamp is the current layout. A file rewritten by anything else, such as a scheduled
  bin-pack or another engine, loses the stamp and becomes eligible again.
- **Sequence watermark.** `optimize.cluster.hwm-seq` is the highest data sequence number a run
  has consumed. Output files are committed with the data sequence number of the newest input they
  replaced, so a run's own output never reads as new data.

Incremental (the default) rewrites files above the watermark that do not carry the current
layout. `FULL` rewrites every file that does not carry the current layout, whatever its sequence.
Files stamped with an older layout are skipped by incremental runs and taken by `FULL`.

After the data rewrite it compacts merge-on-read position delete files and drops deletes the
rewrite made dangling; on copy-on-write or delete-free tables that step is a no-op.

Snapshot expiration is deliberately **not** part of `OPTIMIZE` — that is `VACUUM`'s job.

Clustering requires a format-version 2 table; version 1 tables have no sequence numbers and are
refused. OpenHouse creates version 2 tables by default.

## Table properties

Clustering is configured with ordinary (user-settable) table properties:

| Property | Meaning | Default |
| -------- | ------- | ------- |
| `optimize.cluster.keys` | Comma-separated clustering keys. Empty means plain bin-pack. | *(unset)* |
| `optimize.cluster.sort-mode` | `zorder` or `sort`. | `zorder` |
| `optimize.cluster.max-commits` | Partial-progress commit budget for one run. | `10` |

`OPTIMIZE` writes back the state it needs in a single atomic property update, so the pieces never
disagree:

| Property | Meaning |
| -------- | ------- |
| `optimize.cluster.layout-id` | Id of the current layout: a CRC of the keys and sort mode, offset above 1000 so it never collides with a registered sort order id. |
| `optimize.cluster.layout.<id>` | JSON `{id, keys, mode}` for every layout id that has ever stamped a file. |
| `optimize.cluster.hwm-seq` | The sequence watermark. |
| `optimize.cluster.epochs` | JSON history of `(layout, lowerSeq, upperSeq]` ranges consumed per layout. |

`ANALYZE TABLE <t> COMPUTE CLUSTERING QUALITY` reads the stamps and the watermark to report how
well the table is clustered.

A table clustered under the earlier snapshot-id watermark (`optimize.cluster.hwm-snapshot-id`,
`optimize.cluster.state`, `optimize.cluster.config-id`, `optimize.cluster.min-snapshot-age-minutes`)
is migrated on its next `OPTIMIZE`: a live watermark snapshot becomes its sequence number, an
expired one becomes 0, and the old properties are removed. Files clustered before the migration
carry no stamp, so the first run after it reclusters them.

## Runtime requirement

The clustering path uses rewrite options of the OpenHouse Iceberg fork (`openhouse-1.5.2`,
release 1.5.2.22 or later): `min-data-sequence-number`, `exclude-sort-order-ids`,
`use-max-input-sequence-number`, `output-sort-order-id` and `output-file-metadata.*`. On an older
runtime a clustered `OPTIMIZE` fails with `Cannot use options ...`; bin-pack is unaffected.

## Interaction with the scheduled maintenance jobs

`OPTIMIZE` refuses to run on a table that has been opted out of platform maintenance, via
`maintenance.disabled = 'true'` or `maintenance.DATA_COMPACTION.disabled = 'true'` — the same
switches the jobs scheduler consults before dispatching work for a table.

A scheduled bin-pack of a clustered table strips the layout stamps from the files it rewrites, so
those files read as unclustered and the next `OPTIMIZE` reclusters them.
