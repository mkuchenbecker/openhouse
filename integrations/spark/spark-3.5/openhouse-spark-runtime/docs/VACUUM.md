# VACUUM

**Status: Alpha.** `VACUUM` is opt-in per table. Please See [Enabling VACUUM](#enabling-vacuum)).

`VACUUM` is an OpenHouse Spark SQL extension that reclaims storage for an OpenHouse
Iceberg table by removing files that are no longer needed. It is thin, ergonomic sugar
over the underlying Iceberg maintenance stored procedures, and it reads the same table
properties as the scheduled snapshot-expiration and orphan-file-deletion jobs, so running
it by hand and letting the platform run it agree about the same table.

## Syntax

```sql
VACUUM <table> [REMOVE ORPHAN FILES] [RETAIN <n> HOURS]
```

- `<table>` — an OpenHouse table identifier (e.g. `openhouse.db.table`).
- `REMOVE ORPHAN FILES` — *(optional)* also delete orphaned files (see below). Off by default.
- `RETAIN <n> HOURS` — *(optional)* retention window in whole hours. When omitted, each step
  falls back to what the corresponding maintenance job would have used for this table.

The command returns the windows it resolved, so you can see what each step actually used:

```
metric                       value
---------------------------  ------------------------
orphan_files_retain_hours    168
orphan_files_retain_source   default
snapshots_retain_hours       72
snapshots_retain_source      policies.history (3 DAY)
```

## Behavior

Running `VACUUM` reclaims files beyond the retention window that are no longer referenced
by the current version of the table.

1. **Orphan-file deletion** (`REMOVE ORPHAN FILES`, opt-in). Orphan files are files under the table's location that are not referenced by any table metadata typically left behind by failed or aborted writes. This step only deletes files from storage; it does not commit table metadata, so it succeeds even when the table is out of write quota.

2. **Snapshot expiration** always runs. It removes snapshots older than the retention window and deletes the data, delete, manifest, and manifest-list files that those expired snapshots exclusively referenced. This command adds a commit and can conflict with in-flight transactions.

3. **Retention** (`RETAIN <n> HOURS`) bounds both operations: only files older than `now - n hours` are eligible. The cutoff is resolved to a concrete timestamp in the session time zone at execution time.

### Default retention

With no `RETAIN`, each step uses the same window its scheduled job would have used:

| Step | Window when `RETAIN` is omitted |
| ---- | ------------------------------- |
| Snapshot expiration | The table's history policy — `maxAge` x `granularity`. If the policy also sets `versions`, at most that many snapshots survive, regardless of age. With no history policy, the job default of **3 days** applies. |
| Orphan-file deletion | **7 days**, or **1 day** when the table sets `ofd.one_day_ttl.enabled = 'true'`. |

Set the history policy the same way the scheduled job reads it:

```sql
ALTER TABLE openhouse.db.table SET POLICY (HISTORY MAX_AGE=3D VERSIONS=10);
```

An explicit `RETAIN` overrides both, including below the defaults — that lever is deliberate,
so an operator can reclaim space in an emergency.

## Enabling VACUUM

`VACUUM` is Alpha and must be enabled on each table before use:

```sql
ALTER TABLE openhouse.db.table
  SET TBLPROPERTIES ('maintenance.vacuum.enabled' = 'true');
```

| Property                     | Value    | Meaning                                         |
| ---------------------------- | -------- | ----------------------------------------------- |
| `maintenance.vacuum.enabled` | `'true'` | Opt this table into the Alpha `VACUUM` command.  |

Any other value (or the property being absent) leaves `VACUUM` disabled for the table, and
running the command throws an `UnsupportedOperationException` that explains how to enable it.
`VACUUM` is only supported on OpenHouse tables; running it on a non-OpenHouse table also
throws.

The property is in the `maintenance.` namespace rather than `openhouse.` because the /tables
service treats `openhouse.`-prefixed keys as reserved and rejects any attempt to set them.

## When VACUUM refuses to run

| Situation | Why |
| --------- | --- |
| `maintenance.disabled = 'true'`, or `maintenance.SNAPSHOTS_EXPIRATION.disabled` / `maintenance.ORPHAN_FILES_DELETION.disabled` for the step being run | The table has been opted out of platform maintenance; a manual `VACUUM` should not sidestep that. |
| The table is a replica (`openhouse.tableType = 'REPLICA_TABLE'`) | The scheduled expiration job runs on primary tables only. A replica's snapshots are replication state, and expiring them by hand can strand an incremental replication. Maintenance for replicas stays with the scheduled jobs. |
| `REMOVE ORPHAN FILES` on a table configured for orphan backups (`retention.backup.enabled` / `retention.backup.dir`) | On those tables the scheduled job *moves* orphans into the backup directory instead of deleting them. The stored procedure has no equivalent hook, so it would destroy files the platform expects to remain recoverable — and treat the backup directory's own contents as orphans. |

## Examples

Enable the feature, then expire snapshots older than 24 hours:

```sql
ALTER TABLE openhouse.db.table
  SET TBLPROPERTIES ('maintenance.vacuum.enabled' = 'true');

VACUUM openhouse.db.table RETAIN 24 HOURS;
```

Expire snapshots using the table's history policy:

```sql
VACUUM openhouse.db.table;
```

Also remove orphaned files, retaining anything from the last 168 hours (7 days):

```sql
VACUUM openhouse.db.table REMOVE ORPHAN FILES RETAIN 168 HOURS;
```

## Notes and caveats

- **`REMOVE ORPHAN FILES` is expensive.** It performs a recursive listing of the table's
  location to find unreferenced files. On tables with very large file counts this can be
  slow and memory-intensive, and may require a larger Spark driver to avoid running out of
  memory.
- **Low Retention causes in-flight operations to fail** A query sees the same snapshot of the table they start with, and expiring a snapshot that is in-use will cause transactions to fail. Deleting orphans of in-flight transactions can cause failure.  24 hours is the suggested minimum but can be lowered to mitigate emergency scenarios. 
- **Snapshot expiration requires write quota**; orphan-file deletion does not. This is why
  orphan-file deletion runs first — on a table that is out of quota, orphan cleanup still
  proceeds even though expiration cannot commit.
- **Expiration here also deletes files.** The scheduled expiration job deliberately leaves file
  deletion to orphan-file deletion; `VACUUM` deletes the files the expired snapshots exclusively
  referenced, because reclaiming that storage is the point of running it by hand.
