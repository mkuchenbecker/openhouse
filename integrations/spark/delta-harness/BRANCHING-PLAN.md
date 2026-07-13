# delta-harness — branching / WAP test plan (evaluation)

Companion to `DDL-TEST-PLAN.md`. Scoped per direction: **the full format×partition matrix is overkill
for WAP/branch** (it's format-agnostic), and **main-affecting DDL on a branch should be tested as
BLOCKED, not matrixed**. This is a bounded, behavior-focused axis (~2 dozen cases), not a multiplier.

## Key findings that shape the plan (from the branch/WAP recon)
- **OpenHouse is branch/WAP-agnostic server-side.** No `spark.wap.branch`/`spark.wap.id`/branch-name
  ever reaches the server; all branch/WAP semantics are client-side (the forked Iceberg-Spark runtime).
  The server persists refs as an **opaque `name→SnapshotRef` map with no ref-type/count/name validation**.
- **Main-affecting DDL on a branch silently mutates the whole table (main) — NO guard exists** (client
  splits commits into metadata-vs-snapshot; schema/spec/props/sortOrder are table-global;
  `OpenHouseInternalTableOperations.doCommit` applies them via `setCurrentSchema`/`replaceSortOrder`,
  table-global; only refs are branch-aware). **This confirms the hypothesis** — such DDL *should* be
  blocked and isn't. So it's a **finding**, tested single-dimension, not crossed.
- **Both targeting mechanisms are supported** (see B1). WAP staging + cherrypick isolation is testable
  (see B2). CREATE BRANCH works (even on empty tables); DROP/REPLACE BRANCH, CREATE TAG,
  set_current_snapshot are **unverified** (no OpenHouse test coverage) — probe candidates.

## Sizing / axis policy (why this isn't a multiplier)
- **Format-agnostic → ONE representative layout** (`unpartitioned/parquet`), plus `partitioned/parquet`
  only where partitioning is actually relevant. **No orc/avro, no ×6.**
- Behavior-focused: isolation, the two mechanisms, DDL-blocked-on-branch (findings), stage→publish,
  lifecycle. A **representative** DML slice on a branch, not all ops.
- **Budget ≈ +24 cases** (vs. the ~+2,400 a full 3× cross would cost). This is the "subset" you asked for.

---

## Phase B1 — Branch targeting: the two mechanisms  (B + N)
**(a) Direct branch ops** (no WAP needed):
- [ ] `ALTER TABLE t CREATE BRANCH b`; `INSERT INTO t.branch_b VALUES …`; `SELECT … FROM t VERSION AS OF 'b'`
      (or `t.branch_b`) shows the branch write; `SELECT … FROM t` (main) does NOT → **branch isolation**
- [ ] `DELETE`/`UPDATE` on `t.branch_b` isolated from main

**(b) `spark.wap.branch` conf ("everything on branch")** (requires `write.wap.enabled=true`):
- [ ] set conf → `INSERT INTO t` and `SELECT * FROM t` both transparently hit branch `b`; unset → reverts to main
- [ ] **negatives:** `spark.wap.id` + `spark.wap.branch` set together → error; writing `t.branch_x` while
      `wap.branch` is set → error; insert into a non-existent branch → error

## Phase B2 — WAP stage → publish isolation  (B + N)
- [ ] `write.wap.enabled=true`; `spark.wap.id=w1`; `INSERT` → **staged**: main row-count unchanged,
      `main` ref still at the pre-stage snapshot, `t.snapshots` +1 but `t.refs` unchanged; staged
      snapshot discoverable only via `t.snapshots WHERE summary['wap.id']='w1'`
- [ ] `CALL … cherrypick_snapshot('db.t', <stagedId>)` publishes → main advances (fast-forward: main==staged;
      non-fast-forward: new snapshot tagged `summary['published-wap-id']='w1'`)
- [ ] **negatives:** cherry-pick the same `wap.id` twice → `DuplicateWAPCommitException`; `expire_snapshots`
      on a still-referenced snapshot → error

## Phase B3 — DDL-on-branch is NOT isolated (SHOULD-BE-BLOCKED → findings)  (finding)
Main-affecting DDL executed while "on a branch" leaks to main (no guard). Test as a **characterization
of the leak** (proves the finding), and record as a product gap in `AUDIT-FINDINGS.md`:
- [ ] with `spark.wap.branch=b` set (or targeting a branch), run `ADD COLUMN` → assert **main's schema
      changed** (the leak) — the DDL was NOT branch-scoped and was NOT blocked
- [ ] same for `SET TBLPROPERTIES` and `WRITE ORDERED BY` → main's props / sort order changed
- **Finding:** OpenHouse has no guard preventing table-global DDL while operating on a branch; it should
  reject (analogous to the RTAS-while-WAP block). Ranks with the missing-guard audit (G-series).

## Phase B4 — Representative branch DML  (B, minimal)
- [ ] a small slice (`delete.byPredicate`, `update.byPredicate`, `merge.upsert`) executed on a branch,
      each asserting branch isolation (main row-set unchanged) — **on 1 layout**, not the full matrix

## Phase B5 — Branch lifecycle ops (verify the unverified)  (B / N — probe)
- [ ] `CREATE TAG`, `DROP BRANCH`, `REPLACE BRANCH`, `set_current_snapshot` — ❓ probe (supported vs rejected;
      recon: plausibly supported via generic ref-sync, but untested in OpenHouse)
- [ ] `fast_forward` on divergent lineage → fails; `cherrypick_snapshot` of a bad/nonexistent id → fails

---

## DML-after-DDL audit (separate ask) — audited, ~5 gaps to fix
**Nuance:** the harness already does an **implicit read-back after every step** (`run()` calls
`currentRows` = `SELECT … ORDER BY key` after each step), so any DDL that left the table *unreadable*
already fails. The stronger property — an explicit post-DDL **write** (or a read touching the DDL's
effect) proving the table stays *usable* — is what a few tests lack. And the prep multipliers already
give full post-DDL DML for **ADD COLUMN** (`prep.evolved:*`) and **single-col WRITE ORDERED BY**
(`prep.ordered:*`).

**Fix these (highest value first) — add an `INSERT` + read after the DDL:**
- [ ] **`ddl.featureFlag.distributionMode`** — the property *governs the write path* yet no row is ever
      written (prop read-back only). Insert + read so the flag is actually exercised. **(top priority)**
- [ ] **`ddl.props.formatVersionForced`** — CREATE-only, never writes. Insert + read to prove the table
      is writable at the forced `format-version=2`.
- [ ] **`ddl.sortOrder.orderedByMulti`** — the one sort-order variant NOT covered by `prep.ordered:*`
      (which is single-column). Insert + read to exercise the multi-column ordered write.
- [ ] **`ddl.acl.grantShared`** — terminal `GRANT` has a no-op validate; add a read-back that the
      shared/granted table is still queryable.
- [ ] **`ddl.policy.sharing` / `ddl.policy.history` / `ddl.policy.retention`** — SET POLICY then only a
      `policies`-blob read; add a data read/write after (retention/sharing can gate data). Med/low.

**Leave as-is:** negatives + drop-like (post-DML N/A); metadata-only asserts already covered by the
implicit read-back (`addColumn.comment/position/multiple`, `props.userRoundTrip`,
`props.previousVersionsHonored`); and everything already doing an explicit read-back or riding a prep
multiplier (`addColumn.single`, `alterColumn.typeWiden`, `renameTable`, `ctas`, `rtas.enabled`,
`colTag`, `policy.replication`, `maintenance.*`).

## Case-count estimate
B1 ≈ 6 · B2 ≈ 5 · B3 ≈ 4 (findings) · B4 ≈ 4 · B5 ≈ 5 → **≈ +24 cases** on 1–2 layouts. Bounded by design.

## Open decisions
1. **B3 framing:** characterization-of-the-leak (proves the bug, passes) vs. assert-should-be-blocked
   (fails → tagged SKIP). Recommend characterization + an `AUDIT-FINDINGS` entry — it demonstrates the
   gap concretely rather than parking it as a skip.
2. **B5 probes** settle supported-vs-rejected for tags / drop-branch / replace-branch at runtime.
