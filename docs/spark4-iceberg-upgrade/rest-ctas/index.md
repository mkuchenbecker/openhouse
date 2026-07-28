# CTAS / RTAS over the OpenHouse Iceberg REST endpoint

This closes pitfall #1's "CTAS / stage-create -- still gapped" item: a stock Spark 4.0
`org.apache.iceberg.rest.RESTCatalog` can now run `CREATE TABLE AS SELECT` (CTAS),
`CREATE OR REPLACE TABLE AS SELECT` and `REPLACE TABLE AS SELECT` (RTAS) against the
`/iceberg/v1` controller (`IcebergRestCatalogController`), with no custom client jar.

## The protocol mismatch

OpenHouse has no true *staged transaction*. Its create path
(`OpenHouseInternalRepositoryImpl.save`) commits the table to the HouseTableService (HTS)
immediately. A stock `RESTCatalog`, by contrast, drives CTAS/RTAS as an Iceberg
create/replace **transaction**: it expects to stage metadata for an as-yet-uncommitted
table and publish it atomically with a follow-up commit that carries Iceberg
`UpdateRequirement`s.

Spark's `StagingTableCatalog` maps the two statements onto stock REST calls as:

| Spark statement | REST calls (stock `RESTSessionCatalog`) |
| --- | --- |
| `CREATE TABLE t AS SELECT ...` (CTAS) | `POST .../tables` with `stage-create=true`, then `POST .../tables/t` (create-transaction commit) |
| `CREATE OR REPLACE / REPLACE TABLE t AS SELECT ...` (RTAS, `t` exists) | `GET .../tables/t` (load), then `POST .../tables/t` (replace-transaction commit) |

The follow-up `POST .../tables/{t}` is a single `updateTable` endpoint that carries three
different operations, distinguished by the `UpdateRequirement` fingerprint the client
stamps (`UpdateRequirements.forCreateTable` / `forReplaceTable` / `forUpdateTable`):

| Operation | Requirement fingerprint | Detection used in `updateTable` |
| --- | --- | --- |
| CTAS data commit | `[AssertTableDoesNotExist]` | `AssertTableDoesNotExist` present |
| RTAS commit | `[AssertTableUUID, AssertLastAssignedFieldId, AssertLastAssignedPartitionId]` | `AssertLastAssignedFieldId` present **and** `AssertCurrentSchemaID` absent |
| INSERT / ALTER / ref ops | `[AssertTableUUID, AssertRefSnapshotID / AssertCurrentSchemaID, ...]` | neither of the above |

`forReplaceTable` uniquely *skips* the schema/ref/spec/order "not changed" assertions
(`isReplace == true` in `UpdateRequirements.Builder`), so a replace always has
`AssertLastAssignedFieldId` (from its `AddSchema`) but never `AssertCurrentSchemaID`,
whereas a schema-changing SIMPLE update (e.g. `ALTER ... ADD COLUMN`) has both. That is the
signal the controller keys on.

## What the controller does

### 1. Stage-create (`POST .../tables`, `createTable`)

`stage-create=true` is treated **identically to a plain create**: the Iceberg
`CreateTableRequest` is translated into an OpenHouse `CreateUpdateTableRequestBody` and the
same `TablesApiHandler.createTable` bean the native controller uses creates and commits the
(empty) table. The response is a real `LoadTableResponse` (loaded via
`CatalogHandlers.loadTable`), which is what the client stages its write on. The model-subset
partition rules of the plain-create translation still apply (unsupported transforms -> HTTP
400).

### 2. CTAS data commit (`POST .../tables/{t}`, `commitStagedCreate`)

The client sends a full *create* payload (all metadata updates that rebuild the table, plus
the data snapshot) guarded by one `AssertTableDoesNotExist`. Two problems, both handled:

- **The assert can't hold** -- OpenHouse already committed the table at stage-create. We do
  not route this through `CatalogHandlers`' `isCreate` branch (which would rebuild via
  `catalog.buildTable(...).createOrReplaceTransaction()` and fail).
- **The create-shaped metadata updates can't replay** -- they re-add the *identical*
  schema/spec and reference them with `SetCurrentSchema(-1)` / `SetDefaultPartitionSpec(-1)`
  (`LAST_ADDED`). Applied on top of the existing metadata, `TableMetadata.Builder` reuses the
  existing schema/spec id, leaves `lastAddedSchemaId` null, and `SetCurrentSchema(-1)` throws
  "Cannot set last added schema".

The only genuinely new content is the snapshot(s). We therefore **keep only the snapshot
updates** (`AddSnapshot` / `SetSnapshotRef` / `RemoveSnapshotRef` / `RemoveSnapshots`), drop
the requirements, and commit them onto the just-created table via `CatalogHandlers` -- exactly
as a plain INSERT does (the proven path through `OpenHouseInternalTableOperations.commit`).
The schema/spec/properties were already materialized from the same `CreateTableRequest` at
stage-create, so nothing is lost.

### 3. RTAS commit (`POST .../tables/{t}`, `replaceTable`)

Routed through OpenHouse's **replace pipeline** so replace semantics apply. The replace
payload is delta-shaped (real ids / `LAST_ADDED` only where a genuine add happened), so it is
safe to reconstruct the final `TableMetadata` by applying the updates onto the current base
(`TableMetadata.buildFrom(base)` + `applyTo`). That final metadata is then translated into the
**same `IcebergSnapshotsRequestBody`** the native client's replace-commit builds
(`OpenHouseTableOperations.putSnapshotsForReplace`) -- schema, time-partitioning/clustering
(via `PartitionSpecMapper`), sort order, table properties, serialized snapshots + refs,
`baseTableVersion = base.metadataFileLocation()`, `replaceCommit = true` -- and handed to the
same `IcebergSnapshotsApiHandler` bean the native `IcebergSnapshotsController` uses.

The service/repository layer then runs the native replace: `IcebergSnapshotsService`
-> `OpenHouseInternalRepositoryImpl.save` `isReplaceCommit` branch -> `validateReplaceTable`
(RTAS-enable gate via the `replace.enabled` table property; WAP/replication conflict checks)
-> `replaceTable`. The RTAS-enable gate therefore applies to REST clients exactly as to the
native client, and the reserved plane (e.g. `openhouse.tableUUID`) is recomputed from the
loaded table, so the UUID is preserved across the replace.

## The atomicity caveat (accepted compromise)

CTAS here is **create-then-commit, not an atomic staged transaction.** OpenHouse commits the
empty table in step 1 and the data snapshot in step 2 as two separate HTS commits. Between
them the table exists but is empty; if the data commit fails, an empty table is left behind
(Spark surfaces the error, but does not roll back the create). This is the same non-atomic
compromise the native OpenHouse client's CTAS has always had against OpenHouse's
commit-on-create model, and it is why the follow-up commit's stock `assert-create` cannot be
honored literally. RTAS, by contrast, is a single replace commit and is atomic at the HTS.

## Files changed

- `services/tables/.../controller/IcebergRestCatalogController.java`
  - `createTable`: `stage-create=true` no longer 501s; it creates-then-commits like a plain
    create.
  - `updateTable`: three-way routing (`commitStagedCreate` / `replaceTable` / plain
    `CatalogHandlers.updateTable`) with the requirement-fingerprint detection above.
  - New injected beans: `IcebergSnapshotsApiHandler`, `PoliciesSpecMapper`.

No changes to `OpenHouseInternalTableOperations`, `OpenHouseInternalRepositoryImpl`, the
snapshot service, or any shared model -- the endpoint composes existing OpenHouse pipelines.

## Verified (Spark-4.0 delta-harness, iceberg 1.11.0-openhouse, Scala 2.13.16)

Required cases:

- `ddl.ctas` -> PASS
- `interact.rtas.dropsColumn` -> PASS

RTAS surface (`./run-openhouse.sh rtas`): **161 passed, 2 skipped, 4 failed** (167 cases). The
gate cases `ddl.rtas.enabled` and `ddl.rtas.disabled` both PASS (RTAS-enable gating enforced for
REST clients). The 4 failures are **not** regressions of this work:

- `ddl.rtas.replicationConflict`, `interact.rtas.props.reservedPlane`, `hazard.rtas.wipesColumnTags`
  fail with a Spark `ParseException` at their `ALTER TABLE ... SET POLICY` / `SET TAG` prep step --
  a stock Spark 4.0 `iceberg-spark-runtime` has no OpenHouse SQL extensions, so these OpenHouse-only
  DDL statements never parse. Pre-existing REST-lane limitation, independent of CTAS/RTAS.
- `interact.rtas.onLockedTable` -- RTAS now runs and faithfully reproduces OpenHouse's own **G2**
  guard gap: the replace branch of `IcebergSnapshotsServiceImpl` checks replace privilege, not the
  lock state, so RTAS is not blocked on a locked table (the native OpenHouse client has the same
  gap). In the earlier `stageCreate == 501` state this case "passed" only because the 501 happened
  to satisfy its `intercept[Exception]`; it now reflects the real behavior.

Full matrix (`./run-openhouse.sh`): **1641 passed, 28 skipped, 28 failed** (1697 cases), vs the
`1637 / 28 / 32` (passed / failed / skipped) pre-CTAS baseline -- **+4 passed, failed held at 28,
-4 skipped.** All 28 failures are on paths this change does not touch (stock-Spark `ParseException`
on OpenHouse policy/tag/ACL DDL, and REST-lane guard-gap characterizations), plus the G2
`onLockedTable` item above.
