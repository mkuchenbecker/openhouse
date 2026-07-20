# CHECKLIST — 2026-07-20 — WAP / branching mega-axis (DDL-TEST-PLAN Phase 29)

New file per task. Re-read first each turn. Parent: `index.md` (master map). Registered ACTIVE in index.
Budget: **full 3×** greenlit by owner ("budget is fine"). This is the last big block.

## GOAL
Re-run the operation surface on THREE write targets and pin the target-specific contract of each:
  - **T0 main** — the baseline (already covered by the core CREATE-path blocks). No new work; it is the
    control the other two are asserted against.
  - **T1 branch** — every op routed to a named branch (`spark.wap.branch`): assert the BRANCH delta is
    correct AND `main` is ISOLATED (unchanged). Systematizes G8 (which DDLs LEAK to main vs stay scoped).
  - **T2 staged (WAP)** — every op written as a STAGED snapshot (`spark.wap.id`): assert main is UNCHANGED
    pre-publish, the staged snapshot exists, then `cherrypick_snapshot` PUBLISHES it and main reflects it.
The point: correctness on the target, isolation of main, and publish-visibility semantics — the exact
places OpenHouse's branch/WAP guards are thin (G8 branch-DDL leak, G11 branch×expiration merge loss).

## CURRENT COVERAGE (measured — do NOT rebuild)
- **T1 branch DML**: `branchWap` = `branchParquetLayouts` (4 = {unpart,part}×{parquet,orc}, **avro excl**)
  × `operations` (55) + `branchMainIsolation` = 220 cases. `branchWapMor` = branch×MoR mutation ops (2
  layouts). Mechanism: `createAndSeedOnBranch` (enableWap + CREATE BRANCH b + spark.wap.branch=b).
- **T1 branch DDL**: only the G8 leak legs (`branch.ddlLeak.addColumn`, + B3/B4 targeted). NOT systematic.
- **T2 staged**: only B2 `wap.stagePublish` (one staged INSERT → cherrypick). The op SURFACE is NOT run staged.
- Interactions already covered: G11 branch×expiration×merge, `interact.branch.*`, `mbranch.*` (MoR×branch
  merge fast_forward/cherry_pick/REPLACE BRANCH), `branch.replaceBranch`, B5 lifecycle (CREATE TAG/DROP BRANCH).

## GAP → STAGES (build incrementally, verify + commit each)
### Stage A — T1 branch DML to full parity (bounded, low risk)
- [ ] A1. Extend `branchWap` layouts to all 6 (add avro): `branchParquetLayouts` → all layouts (or a new
      `branchLayouts` incl avro). +≈110 (55 ops × 2 avro shapes). Keep `branchMainIsolation`.
- [ ] A2. Add `partitionedOperations` on the partitioned branch shapes (mirror core `partitioned`). +≈ small.
- [ ] A3. Extend `branchWapMor` to 3-format for parity with morLayouts. + (mutationOps × 1).
- [ ] Verify branchWap slice green on avro + partitioned; commit.

### Stage B — T1 branch DDL, systematic (the G8 systematization — HIGH VALUE)
- [ ] B1. New block `branchDdl:` = each DDL op (ddlSchemaOperations + ddlPropsOperations + ddlMiscOperations
      + ddlPolicy + ddlTagAcl) run WITH `spark.wap.branch` set, then assert: does it apply to the BRANCH
      only (branch-scoped) or LEAK to main? Pin the ACTUAL behavior per DDL (schema/props/sortOrder are
      table-global per G8 → EXPECT leak-to-main; that becomes a documented finding, not a pass-by-accident).
      Format-agnostic → single format (parquet) to avoid vacuous doubling (DDL doesn't depend on data format).
- [ ] B2. For each, DIAG + assert the pinned leak/scoped outcome; cross-reference AUDIT-FINDINGS G8.
- [ ] Verify; commit. (Estimate: |ddlOps| ≈ 40–60 cases, single-format.)

### Stage C — T2 staged (WAP) surface (NEW MECHANISM — the core of Phase 29)
- [ ] C1. Build `createAndSeedStaged` + a staged-op wrapper. Mechanism: enableWap; `spark.wap.id='w'`; run
      the op (writes a staged snapshot, NOT on main); UNSET wap.id. The delta-assertion model must be
      ADAPTED: after the staged op, `main` shows NO change (staged invisible); assert `.snapshots` gained a
      staged (unpublished, `staged-wap-id` summary) snapshot; then `cherrypick_snapshot(stagedId)` and assert
      main NOW reflects the op's delta. (This is a different assertion shape than the branch/main leg — write
      the wrapper carefully; reuse `wapStagePublish` B2 as the template.)
- [ ] C2. Run the WRITE ops (append/insert/overwrite/delete/update/merge families — NOT pure reads; reads
      have nothing to stage) staged → publish, on {unpart,part}×{parquet,orc} (avro if not vacuous). Assert
      main-unchanged-pre-publish + correct-after-publish for each. Estimate ≈ writeOps × 4 shapes.
- [ ] C3. Characterize staged EDGE cases: (a) two concurrent staged ids → independent publish order;
      (b) a staged snapshot left unpublished + `expire_snapshots` → is it stranded (G11 (d) — staged WAP
      snapshots unreferenced → age-based expiration deletes pre-publish)? pin it; (c) DDL under wap.id
      (does it stage or commit immediately? — characterization).
- [ ] Verify; commit.

### Stage D — reconcile + findings
- [ ] D1. Confirm no duplication with existing `interact.branch.*` / `mbranch.*` / B1–B5; prune overlaps.
- [ ] D2. Any target where the delta diverges from T0 main = a FINDING → AUDIT-FINDINGS (branch/WAP guard
      gaps). Especially: which DDLs leak (B), staged-expiration stranding (C3b).

## VERIFICATION (definition of done)
Full STUB + REAL-HTS runs green (0 failed, 0 ORC↔Parquet divergence) with the new `branchWap` avro/partition
cases + `branchDdl:` block + `wapStaged:` block present. New counts recorded in VERIFIED-RUN. Every pinned
leak/stranding outcome cross-referenced in AUDIT-FINDINGS (G8/G11). Estimate (full 3×, vacuity-pruned):
Stage A ≈ +130, Stage B ≈ +50 (single-format), Stage C ≈ +120–200. Total ≈ +300–380 net (far below the
raw "~+2,400" because the branch DML leg is already built and DDL/staged legs are single-format where the
data format is irrelevant — the 3× headline assumed a full re-matrix; we prune the vacuous cells per the
standing rule and surface the pruning here).

## OPEN QUESTIONS (surface to owner if they block)
- Staged assertion model (C1): confirm the "main-unchanged-then-publish-reflects" shape is what we want vs
  a simpler "staged snapshot exists + publishes" pin. Default: the fuller shape.
- Is the ~+2,400 raw 3× expected literally, or is vacuity-pruned ~+300–380 acceptable (matches "no vacuous
  tests")? Default: pruned, with the pruning documented here. FLAG in chat before the full build.

## STATUS: PLANNED. Not started (awaiting go / the RTAS verification gate to finish first).
