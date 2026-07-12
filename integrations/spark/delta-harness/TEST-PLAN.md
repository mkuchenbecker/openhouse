# delta-harness — DML test plan

Each bullet is a test or a small collection of near-identical tests. We work **top-down, one
group at a time**. A group is done only when it is green — or every non-green case is a **tagged
known-bug** with a recorded reason.

## Execution protocol
- Add the group's tests, run them. Each either passes or we record exactly why it doesn't.
- **Genuine product bug → stop.** Tag the failing case as a known bug (`Plan.knownBugs`, with a
  one-line reason). It then reports `SKIP (bug: …)`, is excluded from failures, and is appended to
  `BUGS.md` for follow-up. Do **not** build further tests on top of behavior we know is broken.
- Our own test/harness mistake → fix it and continue.
- Every test automatically composes with the layout matrix (format × partition) and, once added,
  the CoW/MoR and preparation (RTAS/rollback) axes. That multiplication is free by design.

## Gate #0 — verify OpenHouse actually supports the axis before writing its tests
Mark ❓ until confirmed against the running OpenHouse catalog; don't assume Iceberg parity.
- [x] CoW vs MoR (`write.{delete,update,merge}.mode`) — **both supported** (MoR needs `format-version=2`); verified by 264 green MoR cases
- [x] Time travel `VERSION AS OF` / `TIMESTAMP AS OF` — **supported**
- [x] Rollback/restore procedures (`CALL openhouse.system.rollback_to_snapshot`, `set_current_snapshot`) — **supported**
- [x] Partition evolution (`ALTER TABLE … ADD/DROP PARTITION FIELD`) — **NOT supported** (OpenHouse 400: "recreate the table with new partition spec")
- [ ] Branches / tags (likely unsupported)
- [ ] OpenHouse table undrop / restore (soft-drop preparation)

## Phase 0 — framework prep (prerequisite)
- [x] Literal `CREATE` DDL (option B: one `columns` literal constant reused per layout); keep the 120-case matrix.
- [x] Tagging / known-bug mechanism: `Plan.knownBugs` → `SKIP (bug: …)`, excluded from failures; `BUGS.md` follow-up list.
- [ ] Give `TableTest` a `name` to remove the double-naming / silent-skip registration wart.

## Phase 1 — DELETE  ✅ (14 behaviors × 6 layouts)
- [x] delete by predicate shapes: `<`/range, `IN (list)`, `IN (subquery)`, `NOT IN (subquery)`, `EXISTS`, `NOT EXISTS`, scalar subquery
- [x] delete with null condition (`col IS NULL`) — no null rows seeded → removes nothing, no error
- [x] delete all (no WHERE) → empty
- [x] delete none — real predicate, no match → unchanged, **+1** snapshot (scanned, unlike folded WHERE false)
- [x] delete WHERE false (constant-folded) → unchanged, **no** snapshot
- [x] delete with partition-only predicate (metadata-only delete on the partitioned layout)
- [x] TRUNCATE → empty
- [x] delete with table alias
- [x] delete at a specific snapshot → rejected (negative)

## Phase 2 — UPDATE  ✅ (14 behaviors × 6 layouts)
- [x] update by predicate; update without condition; update no-match (+1 snapshot)
- [x] update with subquery conditions: IN / NOT IN / EXISTS / NOT EXISTS / scalar
- [x] update with table alias
- [x] update multiple columns in one statement
- [x] update a column by expression over itself (`SET long = long + 10`, updates the key)
- [x] update a partition column so the row moves partitions
- [x] update with null assignment (`SET string = NULL`)

## Phase 3 — MERGE  ✅ (16 behaviors × 6 layouts)
- [x] insert-not-matched; update-matched; delete-matched; upsert; delete-not-matched-by-source
- [x] conditional matched: `WHEN MATCHED AND <cond> THEN UPDATE`
- [x] multiple matched clauses (update then delete; first match wins)
- [x] conditional not-matched: `WHEN NOT MATCHED AND <cond> THEN INSERT`
- [x] matched + not-matched + not-matched-by-source in one statement
- [x] `UPDATE SET *` (and `INSERT *`, already used throughout)
- [x] explicit column-specification INSERT form
- [x] source as a CTE; source as a set operation (UNION ALL)
- [x] merge into an empty target (all rows inserted)
- [x] merge with a null join key (never matches, no error)
- [x] merge resolves columns by name (source column order differs)

## Phase 4 — INSERT / OVERWRITE / APPEND  ✅ (+ 1 tagged bug)
- [x] INSERT INTO VALUES; DataFrame append; INSERT OVERWRITE (static); DataFrame overwrite(true)
- [🐛] INSERT INTO with an explicit column list (subset → null-fill) — **tagged bug**: partial-column INSERT rejected (`CANNOT_FIND_DATA`); see `BUGS.md`
- [x] dynamic partition overwrite (`partitionOverwriteMode=dynamic`) → only touched partitions replaced *(partitioned-only)*
- [x] `writeTo(t).overwritePartitions()` *(partitioned-only)*
- [x] `INSERT INTO … SELECT`

Introduced a **partitioned-only operations axis** (crossed only with the partitioned layouts) for
tests whose correctness depends on partitioning; reused in Phase 7.

## Phase 5 — Copy-on-write vs Merge-on-read  ✅ (correctness + physical delete-file discriminator)
- [x] MoR layouts (`write.{delete,update,merge}.mode=merge-on-read`, `format-version=2`) crossed with
  all 44 mutation operations × 6 layouts = 264 cases, all green — correctness holds under MoR
- [x] under MoR, assert delete files ARE produced and under CoW they are NOT — `mor.writesDeleteFiles`
  / `cow.writesNoDeleteFiles` × {parquet, orc, avro}. Seeds all rows into one data file
  (`COALESCE(1)`) and deletes a strict subset, so the write can't be satisfied by whole-file
  elimination: MoR adds exactly one position-delete file, CoW adds none — deterministic on every
  format. (A boundary-aligned delete that covers a whole data file is a legitimate metadata delete
  with no position-delete file, which is why the seed forces a single file.)

## Phase 6 — Nested / complex types  ✅ (+ 1 tagged bug) — NestedTable × 3 formats
- [x] define `NestedTable`: `struct<x:int,y:string>`, `array<int>`, `map<string,int>`, struct-in-struct
- [x] write + read-back roundtrip of every nested column
- [x] project a nested field (`SELECT s.x`)
- [x] filter on a nested field
- [x] UPDATE a nested struct field (`SET s.x = 99`)
- [x] MERGE inserting nested columns
- [🐛] delete by predicate on a nested field — **tagged bug**: `DELETE WHERE s.x = 2` internal optimizer NPE; see `BUGS.md`
- [x] null / empty nested values (null struct, empty array / map)

## Phase 7 — Partitioning: transforms + evolution  ✅ (7 supported + 4 rejection negatives)
- [x] one per **supported** transform: identity, `bucket[N]`, `truncate[W]`, `years/months/days/hours` (on timestamp)
- [x] **rejection negatives** (OpenHouse contract): `void(n)` unsupported; `days(date)` unsupported (only identity/truncate/bucket on date)
- [x] partition **evolution rejected** — `ALTER … ADD/DROP PARTITION FIELD` → OpenHouse 400 (evolution not supported); captured as negatives
- [ ] hidden-partitioning pruning (query filter prunes partitions) — optional refinement, not added

## Phase 8 — Type edge coverage  ✅ — TypesTable × 3 formats
- [x] per-type roundtrip incl. nulls: bigint/int, double (NaN, ±inf), decimal(10,2), string (unicode/empty), binary, date, timestamp, **timestamp_ntz** (supported)
- [x] min / max boundary values (Long.MaxValue, Int.MaxValue, decimal near-precision)

## Phase 9 — Time travel (read historical)  ✅ (supported)
- [x] `VERSION AS OF <snapshot_id>` reproduces the state at snapshot A (3 rows) and B (5 rows)
- [x] `TIMESTAMP AS OF <ts>` reproduces snapshot A's state
- [x] metadata tables: `.snapshots`, `.history`, `.files`, `.manifests` counts across a two-snapshot sequence
- [x] incremental read between two snapshots returns exactly the 2 added rows

## Phase 10 — Restore / rollback (pointer)  ✅ (procedures supported)
- [x] `CALL … rollback_to_snapshot` → state reverts to the 3-row snapshot
- [x] `CALL … set_current_snapshot` → state reverts to the 3-row snapshot
- [ ] rollback_to_timestamp / write-after-rollback / undrop / invalid-snapshot negative — refinements, not yet added

## Phase 11 — Negative / contract  ✅ (7 + the Phase-7 partition negatives)
- [x] insert arity mismatch (too few columns) → rejected
- [x] MERGE cardinality violation (source matches a target row twice) → runtime error
- [x] MERGE conflicting updates (same column assigned twice) → error
- [x] non-deterministic condition (`rand()`) in DELETE and UPDATE → rejected
- [x] reference to a non-existent column → error
- [x] partition by a non-existent column → error
- [x] partition transform on an incompatible type — `void(n)`, `days(date)` (Phase 7)
- [x] partition evolution rejected (Phase 7); write at a specific snapshot → rejected (Phase 1)
- [ ] type mismatch (string→bigint), decimal overflow, DataFrame schema-mismatch — deferred (non-ANSI Spark often nulls rather than errors, so not clean rejections here)

---
**Target:** ~130–160 distinct behaviors. Crossed with 6 layouts (and ×2 for CoW/MoR where it
applies) the case count runs well past that — which is the point: behaviors are written once and
the axes multiply them for free.
