# CHECKLIST — 2026-07-20 — RTAS full cross (DDL-TEST-PLAN Phase 28)

New file per task. Re-read first each turn. Parent: `index.md` (master map) → completeness-pass follow-up.
Indexed in `index.md` under CHECKLISTS as ACTIVE.

## GOAL
Extend the RTAS substrate (`prep.rtas:*`) so a table created via **CREATE OR REPLACE (RTAS)** carries the
**same full DML coverage** the core `CREATE` path already has — validating that the typed delta-assertion
model holds identically over a **replace-lineage** table (RTAS re-creates the table definition wholesale;
G9/G10 show the replace path skips update-path guards, so its data-plane behavior must be pinned as
carefully as the create path).

## CURRENT vs FULL (measured)
- Core `dml` block  = `layouts` (6 = {unpart,part} × {parquet,orc,avro}) × `operations` (55).
- Core `partitioned` = partitioned layouts × `partitionedOperations`.
- Current `prepRtas` = `rtasPrepShapes` (4 = {unpart,part} × {parquet,**orc**}, **avro excluded**) × `operations`
  (55) = 220 cases. It does NOT run `partitionedOperations`, and it omits the avro shapes.
- **Gap (the "full cross"):**
  1. add the **avro** RTAS shapes → `operations` × all 6 layouts (i.e. add {unpart,part}/avro): **+110** (55×2).
  2. add **`partitionedOperations`** on the partitioned RTAS shapes ({part}/{parquet,orc,avro}): + (|partitionedOps| × 3).
  (Also confirm the RTAS×MoR leg `prepRtasMor` — currently parquet+orc, `mutationOperations` — is complete
   or extend to avro if the core MoR layouts include avro. Note it, decide with the data.)

## STEPS
0. [ ] Persist this checklist + register in index.md (ACTIVE). ← do FIRST.
1. [ ] BLOCKED until the fork-tests sub-agent patch is integrated (same file) — avoid a merge conflict in
       OpenHouseMatrix.scala. Integrate that first, recompile green, THEN start here.
2. [ ] Extend `rtasPrepShapes` to include avro (mirror `partitionVariants × {parquet,orc,avro}`), so the
       existing `prepRtas` comprehension picks up the 2 new avro shapes automatically. Verify
       `createAndSeedRtas(pc, 3, "avro")` builds a valid avro RTAS table.
3. [ ] Add a `prepRtasPartitioned` block = partitioned RTAS shapes × `partitionedOperations` (mirror the
       core `partitioned` block but with `createAndSeedRtas`). Wire into the assembly + final concat.
4. [ ] Reconcile `prepRtasMor` against the core MoR layout coverage; extend to avro iff the core MoR block
       covers avro (keep parity, no vacuous adds). Record the decision in this checklist.
5. [ ] SMOKE narrowly: `./run-openhouse.sh prep.rtas` (and `prep.rtasPartitioned`) — new avro + partitioned
       cases green; spot-check a couple that the delta assertions hold over replace-lineage.
6. [ ] VERIFICATION: full both-mode run (STUB + REAL-HTS), 0 failures, 0 ORC↔Parquet divergence; record the
       new counts in VERIFIED-RUN-openhouse.txt; update index.md pointer.
7. [ ] Commit + push each stage; mark this checklist COMPLETED in index.md + header; denormalize the new
       RTAS coverage into BUILD-STATUS.md; freeze.

## VERIFICATION (definition of done)
Full STUB + REAL-HTS runs green (0 failed, 0 divergence) with the new `prep.rtas:*` avro + partitioned
cases present, counts recorded in VERIFIED-RUN. Any case where the RTAS/replace-lineage delta diverges
from the create-lineage baseline is a FINDING (not a bug to hide) — document in AUDIT-FINDINGS.

## STATUS: step 0 (persist + index). Step 1 BLOCKED on fork-tests sub-agent patch integration.
