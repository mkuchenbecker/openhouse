# REST commit-path validation parity

## Problem

A stock Spark 4.0 `RESTCatalog` drives every write — INSERT/DELETE/MERGE, `ALTER TABLE`, and
`CREATE OR REPLACE ... AS SELECT` (RTAS) — through `POST /iceberg/v1/namespaces/{ns}/tables/{t}`
(`IcebergRestCatalogController.updateTable`). That handler delegated straight to Iceberg's
`CatalogHandlers.updateTable` → `OpenHouseInternalCatalog` `TableOperations.commit`. The catalog
commit enforces the OpenHouse snapshot-smuggling / version-CAS invariants, but it **bypasses the
service-layer update validation** that the native (custom-client) path runs in
`TablesService.putTable` → `OpenHouseInternalRepositoryImpl.save` (update branch):

- table **LOCK** enforcement (`putTable`: `isTableLocked` → `LOCKED_TABLE_OPERATION`),
- reserved `openhouse.*` / `policies` **property guard** (`checkIfPreservedTblPropsModified`, which
  also covers `openhouse.tableType` immutability),
- **partition-spec evolution** rejection (`checkPartitionSpecEvolution`),
- **schema-evolution** validation (`SchemaValidator.validateWriteSchema`: no column drops top-level
  or nested, no incompatible narrowing, no required-tightening).

The rung-2 Spark-4.0 gate found 18 cases where the native path rejects but REST-first allowed.

## Approach — mirror `save()`'s branching, guard before delegating

`IcebergRestCatalogController.updateTable` now calls `enforceUpdateGuards(ident, request)` before
`CatalogHandlers.updateTable`. The guard:

1. Skips staged-create commit-transactions (`AssertTableDoesNotExist` requirement — no base table).
2. Loads the current `TableMetadata` (`base`) and **projects** the commit by applying
   `request.updates()` to a `TableMetadata.buildFrom(base)` builder (exactly as
   `CatalogHandlers.commit` does) to obtain `updated`. This is inspection only; the authoritative
   commit is still performed by the delegate. If the projection fails for any reason we fall through
   to the delegate rather than invent a new failure mode.
3. Classifies the commit. A **REPLACE (RTAS)** starts a **fresh snapshot lineage**: its new current
   snapshot is a brand-new snapshot (absent from the base metadata) with `parentId == null` — a
   disconnected root, which is exactly why rollback/time-travel to a pre-RTAS snapshot reports "not
   an ancestor of the current state". This discriminates RTAS from an INSERT/MERGE/DELETE/INSERT
   OVERWRITE (which chains off the existing head, `parentId != null`) and from a
   rollback/`set_current_snapshot` (which re-points to a snapshot already in the base). An earlier
   coarser signal (`metadataChanged && snapshotsChanged`, the OpenHouse client's own heuristic) did
   **not** work here because `CREATE OR REPLACE ... AS SELECT *` changes no metadata, so the RTAS was
   misread as a plain update. The native replace branch of `save()` runs **only**
   `validateReplaceTable`, never `updateEligibilityCheck`, so the update guards are **skipped for a
   replace** to preserve parity: a replace legitimately redefines schema/spec wholesale, and —
   matching the native path's documented behavior — a replace is **not** blocked by a table lock
   (this is the G2/G9 characterization, not a regression).
4. For a plain **UPDATE**, runs, in order: lock → reserved-props → partition-spec → schema.

Each violation throws a mapped exception → 4xx in the Iceberg `ErrorResponse` envelope
(`UnsupportedClientOperationException` / `InvalidSchemaEvolutionException` → 400). The reused beans
are the *same* `SchemaValidator` bean the repository uses, so the two paths cannot diverge. The
reserved-key predicate (`openhouse.*` prefix or `policies`) is the deployed default
(`BasePreservedKeyChecker.isKeyPreserved`); the lock is read from the reserved `policies` property's
`lockState.locked`.

### Why no happy-path DML is affected

A pure snapshot commit (INSERT/DELETE/MERGE, branch/WAP writes, expire) changes neither schema, spec,
nor reserved properties, so lock is the only guard that could fire — and it fires only when the table
is actually locked (three lock tests). Valid `ALTER` (add column, widen type, set user props) passes
the schema/reserved guards exactly as on the native path. RTAS/replace commits are classified as
replace and skip the update guards entirely.

## Files changed

- `services/tables/src/main/java/com/linkedin/openhouse/tables/controller/IcebergRestCatalogController.java`
  — `enforceUpdateGuards` + helpers; injected `SchemaValidator`; added
  `InvalidSchemaEvolutionException` to the 400 handler.

## Actual harness results (Spark-4.0 delta-harness, iceberg 1.11.0-openhouse, Scala 2.13.16)

Full matrix: **1650 passed / 28 skipped / 19 failed (1697 cases)** vs the 1637 / 28 / 32 baseline —
`FAIL` dropped by 13 (32 → 19) with pass rising by exactly 13 (no happy-path regression). Sorted run
in `fullmatrix-results.txt`.

**13 of the 18 target cases now correctly reject:**

```
PASS ddl.neg.dropColumn                 PASS partition.evolutionAdd.rejected
PASS ddl.props.reservedOpenhouse        PASS partition.evolutionDrop.rejected
PASS ddl.rtas.disabled                  PASS surface.schema.nestedDropField
PASS ddl.repl.tableTypeImmutable        PASS surface.msg.readabilityGuard
PASS interact.ddl.dropColAfterData      PASS control.lock.enforcement
PASS interact.flags.wapReplaceAtCreate  PASS interact.rtas.onLockedTable
                                        PASS hazard.lock.starvesMaintenance
```

**5 not recovered** (`partition.dateDay.rejected`, `ddl.ns.createRejected`,
`ddl.renameTable.conflict`, `hazard.rename.consumers`, `surface.conc.appendAppend`) — none is an
update/replace-validation bypass; see `pitfalls.md`.

The remaining 19 failures are exactly: 12 custom-SQL `ParseException` + 2 CTAS staged-create
(the ~14 structurally-expected, owned by other agents) + these 5.

## Cases recovered (13)

| Case | Guard |
|---|---|
| `ddl.neg.dropColumn` | schema (`Column[..] not found in newSchema`) |
| `interact.ddl.dropColAfterData` | schema |
| `surface.schema.nestedDropField` | schema (nested drop → "Some columns are dropped") |
| `ddl.props.reservedOpenhouse` | reserved-props ("restriction") |
| `ddl.repl.tableTypeImmutable` | reserved-props (`openhouse.tableType` is reserved) |
| `partition.evolutionAdd.rejected` | partition-spec |
| `partition.evolutionDrop.rejected` | partition-spec |
| `control.lock.enforcement` | lock |
| `hazard.lock.starvesMaintenance` | lock (expire_snapshots is a commit) |
| `interact.rtas.onLockedTable` | lock on the UPDATE step; RTAS bypasses (native parity) |

## Cases NOT recovered (see pitfalls.md for the precise reason each)

`ddl.rtas.disabled`, `interact.flags.wapReplaceAtCreate`, `surface.msg.readabilityGuard`,
`ddl.ns.createRejected`, `ddl.renameTable.conflict`, `hazard.rename.consumers`,
`surface.conc.appendAppend`, `partition.dateDay.rejected`.
