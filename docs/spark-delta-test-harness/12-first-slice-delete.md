# 12 — First Slice: Harness + DELETE

*Prerequisites: `01`–`07`, `11`.* The concrete checklist for the first build: the minimal
harness plus one simple category — **`DELETE FROM`** (Iceberg `TestDeleteFrom`, the SQL
surface, 6 methods, copy-on-write default). One permutation. This is the Phase A0 review slice.

## Starter permutation (single point — no cross-product yet)

`formatVersion = 2 · fileFormat = parquet · writeMode = copy-on-write · partitioned = false · executionMode = Once`

Language: **TBD (Scala vs Java)** — resolve before writing Part A. Checklist is language-agnostic.

---

## Part A — Minimal harness (only what DELETE needs)

Build bottom-up (doc `09` order); verify each before the next.

- [ ] **Outcome model + classify** — `Outcome{Passed,Failed,Errored,Skipped}`, `InfraError`, allowlist classifier (docs `02`,`03`). Pure JVM.
- [ ] **Edge + resources** — `Managed`/`bracket`, single `NonFatal` edge, per-partition `TaskContext` release (doc `04`).
- [ ] **Fixture** — `setup: Axis => Managed[Table]`: create Iceberg table via OpenHouse catalog + append seed rows (doc `05`).
- [ ] **Observation type `S`** — capture (a) row multiset via `SELECT * ORDER BY id`, (b) snapshot count. Cheap, comparable (doc `06`).
- [ ] **`DeltaTest`/`BaseTest`** — `observe`/`operation`/`expect`, curried `setup`+`test` (docs `05`,`06`).
- [ ] **Single-`Axis` generator** — no `enumerate`; emit one `TestCase` per base at the starter point (doc `07`).
- [ ] **Runner + sink** — `local[*]` `mapPartitions`, console output, non-zero exit on any `Failed` (doc `01`).
- [ ] **Two firewall self-tests** — injected transient IO → `Errored`→retry→`Passed`; deliberately-wrong `expect` → `Failed`+gate trips (doc `09` §2).
- [ ] **← USER REVIEW GATE** — do not extend the matrix (Phase A1) until this shape is approved.

---

## Part B — DELETE tests to add (the 6, delta-mapped)

Iceberg asserts absolute row lists; we rewrite each as a **delta** (doc `06`) — the removed
set + invariants — so it survives history and the future matrix.

| # | Test (Iceberg method) | operation | expect (delta) | in starter? |
|---|---|---|---|---|
| 1 | `testDeleteFromUnpartitionedTable` | `DELETE WHERE id<2`, then `DELETE WHERE id<4` | removed = rows matching predicate; remaining untouched; applied over two sequential steps | ✅ |
| 2 | `testDeleteFromTableAtSnapshot` | `DELETE FROM t.snapshot_id_<id> …` | **rejection**: `expect` = throws `IllegalArgumentException` "Cannot delete from table at a specific snapshot" (verdict, not infra) | ✅ |
| 3 | `testDeleteFromWhereFalse` | `DELETE WHERE false` | Δrows = 0 **and** Δsnapshots = 0 (no-op produces no snapshot) | ✅ |
| 4 | `testTruncate` | `TRUNCATE TABLE` | rows → ∅ (full removal) | ✅ |
| 5 | `testDeleteFromPartitionedTable` | `DELETE WHERE id>2`, then `id<2` on `PARTITIONED BY (truncate(id,2))` | removed = matched; remaining untouched | ⏸ needs `partitioned` axis |
| 6 | `testDeleteFromTablePartitionedByVarbinary` | `DELETE WHERE data=X'bcd1'` on binary-partitioned table | removed = matched; binary predicate + type round-trip | ⏸ needs `partitioned` + binary type |

**4 of 6 land in the starter permutation.** Tests 5–6 are genuine behaviors but require the
`partitioned` axis — they get added when Phase A1 turns partitioning on, re-using the same
`expect` logic.

Two of these earn their keep beyond DELETE mechanics:
- **#2** proves the verdict-vs-infra firewall on a *rejection* (an expected throw is a `Passed`, a
  wrong throw is `Failed`, a storage hiccup is `Errored`).
- **#3** forces `observe` to capture **snapshot count**, exercising metadata observation early.

---

## Prerequisites to confirm (block porting, per doc `11`)

- [ ] OpenHouse supports `DELETE FROM` (copy-on-write) and `TRUNCATE TABLE` via Spark SQL.
- [ ] Snapshot-ref table naming (`t.snapshot_id_<id>`) rejection behavior matches (for #2), or
      adjust the expected error to OpenHouse's.
- [ ] Catalog/namespace substitution: `USING iceberg` on the `openhouse` catalog.

## After this slice

Phase A1 extends the matrix (doc `11`): add `partitioned` (unlocks #5–6), then `fileFormat`,
`formatVersion`, and `writeMode=merge-on-read` — at which point the richer extensions
`TestDelete` (41 methods, COW/MOR-parameterized) becomes the next port target.
