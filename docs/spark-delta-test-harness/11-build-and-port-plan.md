# 11 — Build & Port Plan (Checklist)

*Prerequisites: all prior docs, especially `10-iceberg-test-inventory.md`.*

> **STATUS: PLANNING / INVESTIGATING.** Nothing here is built yet. This is a living checklist;
> items get refined as investigation completes.

## Two independent work axes

| Axis | Goal | Output | Reviewed? |
|---|---|---|---|
| **A — Harness bring-up** | A *simple, extensible* harness proven end-to-end | Code + <10 tests for **one** matrix permutation, then matrix extension | **Yes — user reviews the code** |
| **B — Contract-test port** | Evaluate and **port ALL** Apache Iceberg OSS contract tests | Ported tests riding on the Axis-A harness | Per-category evaluation notes |

They are independent: **A** makes the machinery work on a handful of tests; **B** is the bulk
port that scales on top of it. A is the critical path that unblocks B's scale.

## Guiding approach (the through-line)

1. Build the **simplest** harness that runs the `map→filter→mapPartitions→collect` pipeline
   (docs `01`–`04`) for a **single fixed `Axis` point** — no cross-product yet.
2. Author **<10 tests** for that one permutation; prove the harness end-to-end; **user reviews**.
3. **Then** extend the matrix one axis at a time (doc `07`), re-running the same authored
   deltas across the growing cube.
4. In parallel, evaluate + **port all** OSS contract tests (doc `10`) onto the harness.

Keep the harness **extensible over the matrix, not over the plumbing** — adding a dimension
should be an `Axis` change, never a pipeline change.

---

## Axis A — Harness bring-up

### Phase A0 — Minimal skeleton (single permutation)
Build bottom-up (doc `09` sequencing); verify each layer before the next.

- [ ] Outcome model + classifier — `Outcome`, `InfraError`, `classify` (docs `02`, `03`). **Pure JVM, no Spark.**
- [ ] `Managed` + `bracket` + the single edge (doc `04`). Verify §3 lifecycle.
- [ ] `DeltaTest` (`observe`/`operation`/`expect`) + `BaseTest` (curried `setup`/`test`) (docs `05`, `06`).
- [ ] Trivial generator for **one fixed `Axis`** — no `enumerate` cross-product yet (doc `07`).
- [ ] Spark `local[*]` `mapPartitions` runner + per-partition resource via `TaskContext` (docs `01`, `04`).
- [ ] Sink: console pretty-print + **non-zero exit on any `Failed`** (doc `01`).
- [ ] **<10 hand-written tests** for the single permutation (see proposed starter point below).
- [ ] **← USER REVIEW GATE.** Do not extend the matrix until the harness shape is approved.

**Proposed starter permutation** (confirm at review — it's the canonical Iceberg default):
`formatVersion = 2, fileFormat = parquet, partitioned = false, vectorized = false,
writeMode = copy-on-write, executionMode = Once`.

**Proposed <10 starter tests** (one behavior each, delta-modeled, all on the starter point):
- [ ] append rows → row-count delta
- [ ] `DELETE FROM` matching predicate → row-count + absent-keys delta
- [ ] `UPDATE` matching predicate → changed-column delta, count unchanged
- [ ] `MERGE` insert+update → combined delta
- [ ] schema add-column → schema delta, existing rows null-filled
- [ ] partition-spec read-back → identity on unpartitioned
- [ ] snapshot count increments by 1 per commit → snapshot delta
- [ ] `RepeatAfterRtasReset` on the DELETE test → identical delta across history (doc `06`)
- [ ] injected transient IO fault → `Errored` then `Passed` (firewall, doc `09` §2)
- [ ] deliberately-wrong `expect` → `Failed`, gate trips (firewall, doc `09` §2)

### Phase A1 — Extend the matrix (one axis at a time)
Each step: turn on `axes.enumerate` for one dimension, confirm generated count, re-run the
same authored deltas across it, add the matching verification (doc `09`).

- [ ] `fileFormat`: parquet → + orc, avro
- [ ] `formatVersion`: 2 → + 1, 3 (respect applicability, e.g. no v1 MoR — doc `07`)
- [ ] `writeMode`: copy-on-write → + merge-on-read (the COW/MOR axis, doc `10`)
- [ ] `partitioned`: false → + true (+ partition specs)
- [ ] `vectorized`: false → + true
- [ ] `executionMode`: Once → + RepeatAfterRtasReset, warm-fixture
- [ ] `distribution`: modes
- [ ] Structured identity `(baseId, Axis)` + sliceable reporting online (doc `07`)
- [ ] Disable subsystem (doc `08`) once there are real cases to disable

---

## Axis B — Iceberg contract-test evaluation & port-all

### Phase B0 — Complete the inventory (INVESTIGATING NOW)
- [x] Count `spark-extensions/.../extensions`, `spark/.../source`, `spark/.../sql` → **1,271 methods** (doc `10`)
- [ ] Count `spark/.../actions/` (action-level rewrite/expire/orphan — higher value than CALL) — *agent running*
- [ ] Count `spark/.../functions/` — *agent running*
- [ ] Count `spark/.../data/` and top-level `Test*Util` — *agent running*
- [ ] Fold results into doc `10`; publish the true grand total (the port-all denominator)

### Phase B1 — Evaluate + map (per test)
For each contract test, record: behavior, the delta it implies (`observe`/`operation`/`expect`),
the `Axis` params it exercises, and any **prerequisite** (feature OpenHouse must support).
Evaluation ≠ exclusion — the disposition is always *port* (doc `10` banner).

### Phase B2 — Port-all, ordered by the wedge (doc `10`)
Port in priority order, but everything lands eventually:
1. [ ] Row-level DML family — `TestDelete`/`TestUpdate`/`TestMerge` + COW/MOR (the wedge; ~206 declared)
2. [ ] Partition evolution + transforms
3. [ ] Reads / time-travel / pushdown (incl. streaming once supported)
4. [ ] Writes / distribution
5. [ ] Snapshots / branch / tag / WAP
6. [ ] Metadata tables
7. [ ] Types / defaults
8. [ ] Procedures + `actions/` (mapped onto OpenHouse maintenance surface)
9. [ ] DDL (diff vs OpenHouse's 263) + Views (once view support confirmed)

---

## Open investigation items (gate porting, do NOT remove tests)

These determine *how* a category is ported, not *whether*:

- [ ] **OpenHouse feature support:** Iceberg views? structured streaming? MERGE/UPDATE/DELETE +
      COW/MOR config? Which metadata tables are exposed? (Gates categories #3, #5, #8, #9.)
- [ ] **Maintenance assertion surface:** OpenHouse runs rewrite/expire/orphan as *jobs*, not
      `CALL`. Decide what the harness asserts against — the job API or the `actions/` layer.
      (Gates category #8; makes `actions/` likely the better port target than CALL procs.)
- [ ] **Starter permutation** confirmed at the Phase A0 review gate.
- [ ] **Scala vs Java** for the harness sources (the repo's itests are Java; Scala was the
      original ask). Decide before Phase A0 code.

---

## Reconciliation with doc 10

Doc `10`'s COPY/ADAPT/SKIP grades are **effort/risk signals only** now — every test is ported
(see doc `10` banner). SKIP was re-read as BLOCKED/DEFER with a named prerequisite. This plan
is the sequencing; doc `10` is the inventory it draws from.
