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
- [ ] CoW vs MoR (`write.{delete,update,merge}.mode`) — are both allowed?
- [ ] Time travel `VERSION AS OF` / `TIMESTAMP AS OF`
- [ ] Rollback/restore procedures (`CALL openhouse.system.rollback_to_snapshot`, `set_current_snapshot`)
- [ ] Partition evolution (`ALTER TABLE … ADD/DROP PARTITION FIELD`)
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

## Phase 4 — INSERT / OVERWRITE / APPEND  (~8)
- [x] INSERT INTO VALUES; DataFrame append; INSERT OVERWRITE (static); DataFrame overwrite(true)
- [ ] INSERT INTO with an explicit column list (subset → null-fill)
- [ ] dynamic partition overwrite (`partitionOverwriteMode=dynamic`) → only touched partitions replaced
- [ ] `writeTo(t).overwritePartitions()`
- [ ] `INSERT INTO … SELECT` from another table

## Phase 5 — Copy-on-write vs Merge-on-read  (free multiplier)
- [ ] add `write.{delete,update,merge}.mode` to the layout/prep axis; run phases 1–3 under both
- [ ] under MoR, assert delete files are produced where expected (`.files` / position-deletes metadata)

## Phase 6 — Nested / complex types  (new `NestedTable` schema)
- [ ] define `NestedTable`: `struct<x:int,y:string>`, `array<int>`, `map<string,int>`, struct-in-struct
- [ ] write + read-back roundtrip of every nested column
- [ ] project a nested field (`SELECT s.x`)
- [ ] filter on a nested field
- [ ] UPDATE a nested struct field (`SET s.x = …`)
- [ ] MERGE updating / inserting nested columns
- [ ] delete by predicate on a nested field
- [ ] null nested values (null struct, empty array / map)

## Phase 7 — Partitioning: transforms + evolution
- [ ] create + write + read, one per transform: identity, `bucket[N]`, `truncate[W]`, `year/month/day/hour`, `void`
- [ ] multi-field partition spec
- [ ] partition evolution: ADD field then write; DROP field then write; read spans both specs
- [ ] hidden-partitioning pruning (query filter prunes partitions)

## Phase 8 — Type edge coverage
- [ ] per-type roundtrip incl. nulls: bigint/int, float/double (NaN, ±inf), decimal(p,s) near-overflow, boolean, string (unicode/empty), binary, date, timestamp, timestamp_ntz
- [ ] min / max boundary values

## Phase 9 — Time travel (read historical)
- [ ] `VERSION AS OF <snapshot_id>` reproduces the recorded after-state of each step
- [ ] `TIMESTAMP AS OF <ts>` reproduces a commit's state
- [ ] metadata tables: `.snapshots`, `.history`, `.files`, `.manifests`, `.partitions` counts across a sequence
- [ ] incremental read between two snapshots returns exactly the delta

## Phase 10 — Restore / rollback (pointer)
- [ ] `rollback_to_snapshot` → state == step k after-rows
- [ ] `rollback_to_timestamp`
- [ ] `set_current_snapshot` (incl. a non-ancestor snapshot)
- [ ] write after rollback composes forward
- [ ] a rolled-past snapshot is still readable via time travel
- [ ] (if supported) OpenHouse table undrop / restore
- [ ] negative: rollback to an invalid snapshot id → error

## Phase 11 — Negative / contract
- [ ] type mismatch on insert (string → bigint) → error
- [ ] null into a required column → error
- [ ] DataFrame append with a missing / extra column → schema mismatch
- [ ] decimal precision overflow → error
- [ ] MERGE cardinality violation (source matches a target row twice) → runtime error
- [ ] MERGE conflicting updates (same column assigned twice) → analysis error
- [ ] MERGE / UPDATE / DELETE with a non-deterministic condition (`rand()`) → rejected
- [ ] reference to a non-existent column → analysis error
- [ ] partition by a non-existent column → error
- [ ] partition transform on an incompatible type (`days(string)`, `bucket(boolean)`) → error
- [ ] two time transforms on two columns (`days(ts1), months(ts2)`) → error (mirror OpenHouse's own `tb_bad_partitioned`)
- [x] write at a specific snapshot → rejected

---
**Target:** ~130–160 distinct behaviors. Crossed with 6 layouts (and ×2 for CoW/MoR where it
applies) the case count runs well past that — which is the point: behaviors are written once and
the axes multiply them for free.
