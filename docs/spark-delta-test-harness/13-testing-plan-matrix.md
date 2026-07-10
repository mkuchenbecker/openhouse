# 13 — Testing Plan & Matrix (prioritized)

*Prerequisites: `10-iceberg-test-inventory.md` (counts), `11-build-and-port-plan.md` (axes),
`12-first-slice-delete.md`.* This is the master worklist: **every test category and dimension,
by priority, with current status.** Counts are declared OSS Iceberg methods from
`apache/iceberg@d411012`, `spark/v3.5` (grand total **1,867**).

---

## A. Current status — what is built and green

Harness (Scala, `integrations/spark/delta-harness/OpenHouseMatrix.scala`), run against the
**real OpenHouse catalog** (embedded server), **26/26 green**:

| Piece | Status |
|---|---|
| Outcome model + allowlist classifier (docs 02–03) | ✅ built + firewall-verified |
| `Managed`/`bracket` edge, retry (doc 04) | ✅ |
| Delta model `observe`/`operation`/`expect` (doc 06) | ✅ |
| Axis + generator + structured ids (doc 07) | ✅ (`fileFormat`) |
| Disable subsystem (doc 08) | ✅ (avro slice, with reason) |
| OpenHouse catalog wiring (copied, not extended) | ✅ |

**Categories done:** CREATE (schema), READ (projection, filter), format-materialization,
DELETE (4 behaviors). **Dimension done:** `fileFormat` — parquet ✅, orc ✅, avro ⚠ disabled
(finding F1, `FINDINGS.md`).

---

## B. Dimensions (axes), by priority

The matrix multiplies along these. Order = how soon each should come online.

| # | Dimension | Values | Status | Applicability / notes |
|---|---|---|---|---|
| D1 | **fileFormat** | parquet, orc, avro | parquet ✅ orc ✅ · avro ⚠ disabled | avro blocked by runtime shaded-Avro (F1) |
| D2 | **formatVersion** | 1, 2, 3 | ▫ todo | v1 has no row-level deletes / MoR → prune those combos |
| D3 | **writeMode** | copy-on-write, merge-on-read | ▫ todo | unlocks delete-file path; gates the big DML families |
| D4 | **partitioned** | unpartitioned, partitioned | ▫ todo | + spec variants: identity, bucket, truncate, days/hours/months/years |
| D5 | **vectorized** | on, off | ▫ todo | read-path only |
| D6 | **distribution** | none, hash, range | ▫ todo | write-path ordering/distribution |
| D7 | **executionMode** | Once, RepeatAfterRtasReset, warm-fixture | ▫ todo | history-agnostic combinator (doc 06) |

---

## C. Test categories, by priority

Priority = value × delta-model fit × gap-vs-OpenHouse. Counts are OSS declared methods.

| P | Category | OSS count | Delta-fit | Status | First target files |
|---|---|---:|---|---|---|
| 1 | **Row-level DML** — DELETE→UPDATE→MERGE (COW/MoR) | **206** | excellent | DELETE basic ✅; UPDATE/MERGE ▫ | `TestDeleteFrom`✅→`TestDelete`, `TestUpdate`, `TestMerge` |
| 2 | **Reads / query semantics** | **293** | good | projection+filter ✅; rest ▫ | `TestSelect`, `TestFilteredScan`, `TestAggregatePushDown` |
| 3 | **Writes / distribution** | **100** | good | ▫ | `TestSparkDataWrite`, `TestSetWriteDistributionAndOrdering` |
| 4 | **Partition evolution + transforms** | **100** | good | ▫ | `TestAlterTablePartitionFields`, transform fns |
| 5 | **Snapshots / branch / tag / WAP** | **56** | good | ▫ | `TestBranchDDL`, `TestTagDDL`, `TestSnapshotSelection` |
| 6 | **Metadata tables** | **90** | good | partial (`.snapshots`,`.files` used) | `TestMetadataTables`, `TestIcebergSourceTablesBase` |
| 7 | **Maintenance actions** | **279** (128 core) | good | ▫ | `actions/`: rewrite-data, expire, remove-orphan (via OpenHouse jobs) |
| 8 | **Types / defaults** | **9** | good | ▫ | `TestTimestampWithoutZone` |
| 9 | **DDL** | **143** | n/a (absolute) | create ✅; rest ▫ | `TestCreateTable`, `TestAlterTable`; **Views (82) gated** |
| — | **CALL procedures** | 255 | mixed | defer | overlaps `actions/`; migration procs N/A |
| — | **Internal unit tests** | ~240 | ✗ no table state | out of harness | util/conf/filter converters — port as plain unit tests if at all |

**In-scope for the delta harness ≈ 900–950** table-behavior methods (P1–P8 + create/read of
P9). Maintenance ≈ 150–200 unique. The ~240 internal-unit and Views (82, gated) are separate.

---

## D. Priority-ordered worklist (the sequence)

Each step is one increment; earlier steps unlock later ones.

1. ✅ **DELETE basic + CREATE + READ @ parquet/orc** (done, 26/26 green)
2. ▫ **D2 formatVersion** (2 → 1, 3) across current tests, with applicability pruning
3. ▫ **D3 writeMode** (COW → MoR): set `write.delete.mode`; unlock delete-file behaviors
4. ▫ **P1 UPDATE** — port `TestUpdate` basics × current dims
5. ▫ **P1 MERGE** — port `TestMerge` basics (matched/not-matched clauses) × dims
6. ▫ **D4 partitioned** + transforms; broadens DELETE/UPDATE/MERGE and adds partition category
7. ▫ **P2 Reads** — pushdown, aggregates, time-travel (`VERSION/TIMESTAMP AS OF`)
8. ▫ **P5 Snapshots / branch / tag** (+ D7 RepeatAfterRtasReset combinator)
9. ▫ **P6 Metadata tables** (systematic)
10. ▫ **P7 Maintenance actions** — assert against OpenHouse's job surface
11. ▫ **P3 Writes / distribution** (D6), **P8 Types**
12. ▫ **P9 remaining DDL**; resolve **Views** support decision

---

## E. Near-term per-test checklist (the wedge: steps 2–5)

**Step 2 — formatVersion axis**
- [ ] add `formatVersion ∈ {1,2,3}` to `Axis`; DDL sets `'format-version'`
- [ ] applicability: prune v1 × (delete-at-snapshot needs v2+? MoR needs v2+)
- [ ] re-run the current 8 base tests × {parquet,orc} × {1,2,3}

**Step 3 — writeMode axis**
- [ ] add `writeMode ∈ {copy-on-write, merge-on-read}`; DDL sets `write.delete.mode` (+update/merge modes)
- [ ] applicability: MoR requires formatVersion ≥ 2
- [ ] verify MoR path writes delete files (assert via `.delete_files` / position-deletes metadata)

**Step 4 — UPDATE (port `TestUpdate` basics)**
- [ ] `update.setByPredicate` — `UPDATE t SET data=... WHERE id=...` → changed-column delta, count unchanged
- [ ] `update.noMatch` — predicate matches nothing → no-op, no snapshot
- [ ] `update.allRows` — unqualified update → all rows changed

**Step 5 — MERGE (port `TestMerge` basics)**
- [ ] `merge.insertOnly` — WHEN NOT MATCHED THEN INSERT → additions delta
- [ ] `merge.updateOnly` — WHEN MATCHED THEN UPDATE → change delta
- [ ] `merge.upsert` — matched update + not-matched insert → combined delta
- [ ] `merge.delete` — WHEN MATCHED THEN DELETE → removal delta

---

## Legend
✅ done & verified · partial = some cases done · ▫ todo · ⚠ disabled (reason recorded) ·
gated = blocked on an OpenHouse feature-support decision.
