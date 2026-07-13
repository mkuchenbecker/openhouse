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

## Phase B1 — Branch targeting: the two mechanisms  (B + N) — ✅ green (core)
**(a) Direct branch ops** (no WAP needed):
- [x] `branch.direct.isolation`: CREATE BRANCH b; `INSERT INTO t.branch_b`; `SELECT … VERSION AS OF 'b'`
      = 4, main = 3 → **branch isolation**
- [x] follow-up: `DELETE`/`UPDATE` on `t.branch_b` isolated → done in B4 (`branch.dml.updateDelete`)

**(b) `spark.wap.branch` conf ("everything on branch")** (requires `write.wap.enabled=true`):
- [x] `branch.wapConf.routing`: conf routes INSERT + SELECT to the branch (=4); main unchanged (=3); unset reverts
- [x] follow-up negatives: `spark.wap.id`+`spark.wap.branch` together → error → done in B5 (`branch.neg.wapIdAndBranch`,
      `ValidationException` "Cannot set both WAP ID and branch")

## Phase B2 — WAP stage → publish isolation  (B + N) — ✅ green (core)
- [x] `wap.stagePublish`: `spark.wap.id=w1` INSERT → staged (main stays 3); staged snapshot found via
      `t.snapshots WHERE summary['wap.id']='w1'`; `cherrypick_snapshot` publishes → main = 4
- [ ] follow-up negatives: double cherry-pick → `DuplicateWAPCommitException`; expire a referenced snapshot → error

## Phase B3 — DDL-on-branch is NOT isolated (characterization → finding G8)  — ✅ green (leak demonstrated)
- [x] `branch.ddlLeak.addColumn`: with `spark.wap.branch=leakbr` set, `ADD COLUMN` → **main's schema
      gained the column** (the leak). Recorded as missing-guard **G8** in `AUDIT-FINDINGS.md`.
- [ ] follow-up: same characterization for `SET TBLPROPERTIES` and `WRITE ORDERED BY`
- **Finding G8:** no guard prevents table-global DDL while operating on a branch; it should reject
  (analogous to RTAS-while-WAP).

## Phase B4 — Representative branch DML  (B, minimal) — ✅ green
- [x] `branch.dml.updateDelete`: `UPDATE`/`DELETE` on `t.branch_b` mutate the branch; main row-set unchanged.
      One layout (`unpartitioned/parquet`), not the full matrix. (MERGE folded into the same isolation
      assertion — UPDATE+DELETE exercise the row-level write path on the branch identically.)

## Phase B5 — Branch lifecycle ops (verify the unverified)  (B / N — probe) — ✅ green (settled)
- [x] `branch.lifecycle.tag` (`CREATE TAG`): **SUPPORTED** — `t.refs WHERE name='mytag' AND type='TAG'` = 1.
- [x] `branch.lifecycle.dropBranch` (`DROP BRANCH`): **SUPPORTED** — ref count 1 → 0 after drop.
- [x] `branch.neg.wapIdAndBranch`: `spark.wap.id`+`spark.wap.branch` together → `ValidationException`
      "Cannot set both WAP ID and branch" (client-side guard; expected).
- [x] `branch.neg.insertNonexistentBranch`: INSERT into `t.branch_nope` (never created) →
      `ValidationException` "Cannot use branch (does not exist)".
- Result: the previously-unverified lifecycle ops (tag/drop-branch) are **supported** via the server's
  generic ref-sync; no OpenHouse-specific guard rejects them. `REPLACE BRANCH`/`set_current_snapshot`/
  `fast_forward` left as future probes (lower value — same generic ref-sync path).

---

## DML-after-DDL audit (separate ask) — audited, ~5 gaps to fix
**Nuance:** the harness already does an **implicit read-back after every step** (`run()` calls
`currentRows` = `SELECT … ORDER BY key` after each step), so any DDL that left the table *unreadable*
already fails. The stronger property — an explicit post-DDL **write** (or a read touching the DDL's
effect) proving the table stays *usable* — is what a few tests lack. And the prep multipliers already
give full post-DDL DML for **ADD COLUMN** (`prep.evolved:*`) and **single-col WRITE ORDERED BY**
(`prep.ordered:*`).

**Fix these (highest value first) — add an `INSERT` + read after the DDL:** — ✅ all done (commit 8650505)
- [x] **`ddl.featureFlag.distributionMode`** — now creates + `insert(3)` + read so the write-path flag
      is actually exercised.
- [x] **`ddl.props.formatVersionForced`** — now `insert(3)` + read proving the table is writable at the
      forced `format-version=2`.
- [x] **`ddl.sortOrder.orderedByMulti`** — now `insert(2)` asserting `view.after.size == 5` — the
      multi-column ordered write is exercised.
- [x] **`ddl.acl.grantShared`** — now reads back the shared/granted table (still queryable).
- [x] **`ddl.policy.sharing` / `ddl.policy.history` / `ddl.policy.retention`** — each now asserts
      `view.after.size == 3` (a data read after the policy SET).

**Leave as-is:** negatives + drop-like (post-DML N/A); metadata-only asserts already covered by the
implicit read-back (`addColumn.comment/position/multiple`, `props.userRoundTrip`,
`props.previousVersionsHonored`); and everything already doing an explicit read-back or riding a prep
multiplier (`addColumn.single`, `alterColumn.typeWiden`, `renameTable`, `ctas`, `rtas.enabled`,
`colTag`, `policy.replication`, `maintenance.*`).

## Case-count estimate — actual
9 branching cases landed on `parquet` (1 layout): `branch.direct.isolation`, `branch.wapConf.routing`,
`wap.stagePublish`, `branch.ddlLeak.addColumn`, `branch.dml.updateDelete`, `branch.lifecycle.tag`,
`branch.lifecycle.dropBranch`, `branch.neg.wapIdAndBranch`, `branch.neg.insertNonexistentBranch`.
Deliberately bounded (behavior-focused, format-agnostic → no ×6), well under the +24 ceiling.

## Open decisions — settled
1. **B3 framing:** ✅ went with characterization-of-the-leak (`branch.ddlLeak.addColumn` passes, proving
   G8 concretely) + the `AUDIT-FINDINGS` G8 entry. Not parked as a skip.
2. **B5 probes:** ✅ settled — `CREATE TAG` and `DROP BRANCH` are **supported** at runtime via generic
   ref-sync (no OpenHouse guard). `REPLACE BRANCH`/`set_current_snapshot`/`fast_forward` deferred as
   lower-value future probes on the same path.
