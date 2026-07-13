# delta-harness — feature-INTERACTION audit

The suite (1,231 cases) tests each feature thoroughly **in isolation** plus two deliberate interaction
multipliers (`prep.ordered:*`, `prep.evolved:*`). This audit maps the full feature×feature interaction
space: what's tested, what's a real gap, what's irrelevant, and what's obscure-but-valuable. Backed by
(a) a code-level recon of the server commit paths and (b) **7 empirical probes run live** against the
embedded server (probe code preserved at `scratchpad/interaction-probes.patch`; results below are
observed, not predicted).

Legend: ✅ tested today · 🧪 probed live (needs promotion to a real test) · ❌ relevant gap (untested)
· 🌀 obscure but valuable · ⛔ irrelevant (documented why)

---

## 1. The interaction matrix

Features: **SE** schema evolution · **PS** partition spec · **PR** props/feature-flags (WAP, replace,
MoR mode, format-version) · **PO** policies · **RT** RTAS/replace · **BR** branch/WAP · **TT** time
travel · **RS** restore/rollback · **MX** maintenance (expire/compact/orphan) · **LK** lock · **DML**.

|      | PS | PR | PO | RT | BR | TT | RS | MX | LK | DML |
|------|----|----|----|----|----|----|----|----|----|-----|
| **SE**  | ⛔¹ | ⛔¹ | ⛔¹ | ❌ **A4** | 🧪 **A3** | 🧪 **A1** | 🧪 **A2** | 🌀 C3 | ⛔² | ✅ `prep.evolved` ×174 |
| **PS**  |    | ⛔¹ | ⛔¹ | ❌ **A5 (G9)** | ⛔³ | ⛔³ | ⛔³ | ⛔³ | ⛔² | ✅ transforms/partitioned ops |
| **PR**  |    |    | ⛔¹ | 🧪 A9 (partial) | ✅ wap gate | ⛔¹ | ⛔¹ | ⛔¹ | ⛔² | ✅ distributionMode, MoR@create; ❌ **A8** MoR@ALTER |
| **PO**  |    |    |    | ✅ repl⊕RTAS | ⛔⁴ | ⛔⁴ | 🌀 C1 | 🌀 C1 | ⛔² | ✅ policy DDL + read-back |
| **RT**  |    |    |    |    | 🌀 C2 | 🧪 **A6** | 🧪 **A6** | ⛔⁵ | ❌ **A7 (G2)** | ❌ minor: no write-after-RTAS |
| **BR**  |    |    |    |    |    | 🧪 **A3** | 🌀 C4 | 🧪 **A10** | ⛔² | ✅ B4 branch DML |
| **TT**  |    |    |    |    |    |    | ✅ implicit | 🌀 msg-quality | ⛔² | ✅ |
| **RS**  |    |    |    |    |    |    |    | 🌀 **C1** | ⛔² | ✅ |
| **MX**  |    |    |    |    |    |    |    |    | ⛔² | ✅ |

⛔¹ metadata planes that commute — each is an independent key/section of table metadata; no shared
mechanism to interact through (verified: schema/props/policies ride different validators).
⛔² lock rejects EVERY mutation uniformly at the service entry (`isTableLocked` before op dispatch) —
one representative test (`control.lock.enforcement`) covers the class. **Exception: the replace path,
which never reaches the check — that's G2/A7, a real gap.**
⛔³ partition-spec **evolution is rejected** in OpenHouse (tested), so downstream interactions with
branches/travel/restore can't arise via ALTER. **Exception: the RTAS bypass — A5/G9.**
⛔⁴ policies are server-side metadata consumed by *jobs*; branch/travel reads don't consult them.
⛔⁵ expire-after-RTAS is just expire on the new lineage (no special path — same subtractive merge).
Also ⛔: **format (parquet/orc/avro) × everything above** — branch/travel/restore/RTAS operate on
snapshots/metadata, never on file encoding (the declared branching-plan principle, still correct).

---

## 2. Your examples — verdicts (each probed live unless noted)

**E1. Add col → insert into col → read → drop col → read.**
Partially tested; probed end-to-end (`probe.ddl.dropColAfterData`). ADD + insert + read ✅ (also
`prep.evolved`). DROP COLUMN is **rejected** by OpenHouse (BadRequestException, the known
schema-dump message) — and after the rejected drop the table is intact: the new column's data is
still queryable (`extra_col=42` → 1 row) and inserts still work. **No breakage; the "read after
drop" leg can't exist by design.** Gap → promote the probe: today no test combines
data-in-new-column with the drop rejection. *(Also: drop-via-RTAS may bypass this rejection — A4.)*

**E2. Restore after DDL** — untested; probed (`probe.ddl.ttRestore`). `rollback_to_snapshot` to a
pre-ADD-COLUMN snapshot: **data reverts (3 rows), schema does NOT roll back** (still 7 columns) —
current-schema semantics; rolled-back rows read the new column as null; new-arity inserts still work.
Coherent but surprising → promote as characterization test.

**E3. Time travel after DDL** — untested; probed (same). `VERSION AS OF` a pre-ADD-COLUMN snapshot
reads with the **historical schema** (6 cols, no `extra_col`), current read has 7. Correct
schema-per-snapshot semantics (server keeps the full `schemas` list —
`OpenHouseInternalTableOperations.rebuildTblMetaWithSchemaBuilder:236-238`). Promote.

**E4. Time travel on a branch to before the branch point** — untested; probed
(`probe.branch.ttBefore`). Travel by ancestor snapshot-id works (=3, pre-branch state). With
`spark.wap.branch` set, explicit `VERSION/TIMESTAMP AS OF` **overrides the branch conf and resolves
against main's history** (=3, not branch head 6). Sensible precedence, worth pinning. Promote.

**E5. DDL on main → immediate impact on branches** — untested; probed (`probe.branch.mainDdl`).
**Immediate and total**: after ADD COLUMN on main, branch reads instantly show the new column, and
a branch writer using the old arity is **broken mid-flight** (`INSERT_COLUMN_ARITY_MISMATCH`);
new-arity branch writes work. Schema is table-global in both directions — this is the G8 leak's
mirror image. Promote as characterization (a WAP job can be broken by concurrent main DDL).

**E6. Same with partition evolution** — moot via ALTER (evolution rejected, tested ✅). **But NOT
moot via RTAS**: code recon found `checkPartitionSpecEvolution` runs only on the update path
(`OpenHouseInternalRepositoryImpl:373-383`); the replace path (`:154-173`) never calls it and
`validateReplaceTable` doesn't check the spec → **RTAS can change the partition spec**, bypassing
the guard. New finding **G9** (AUDIT-FINDINGS.md). Not yet demonstrated live → top-priority test.

**E7. Restore after RTAS** — untested; probed (`probe.rtas.history`). `rollback_to_snapshot` to a
pre-RTAS snapshot is **rejected**: `ValidationException: Cannot roll back to snapshot, not an
ancestor of the current state` — the replace snapshot starts a new lineage, and rollback requires
ancestry. Typed, reasonable. **Open sub-case (not probed): `set_current_snapshot` has NO ancestry
requirement — it is likely the working escape hatch to "undo" an RTAS.** Both belong in the suite.

**E8. Time travel after RTAS** — untested; probed (same). **Works**: pre-RTAS snapshots survive the
replace (client sends the full snapshot list; server's subtractive merge keeps them —
`OpenHouseTableOperations:408-413` → `doCommit:314-354`), snapshot count = 2 after replace, and
`VERSION AS OF <pre-RTAS id>` reads the old 3 rows. Also observed: `replace.enabled` survives the
replace. Promote.

**E9. State-transition interactions; enable-at-CREATE vs enable-via-ALTER** — partially tested;
probed (`probe.createVsAlter`). `write.wap.enabled` + `replace.enabled` at **CREATE** are honored
(read back true; CREATE BRANCH works) — previously only the ALTER path was exercised. The RTAS⊕WAP
mutual exclusion fires identically from create-time flags ✅. Known server asymmetries (from code):
`format-version` forced at create only (tested), `write.metadata.previous-versions-max` defaulted at
create (tested), reserved `openhouse.*`/`policies` keys filtered at create but hard-rejected on
ALTER (tested one side). **Gap A8: ALTER an existing CoW table to `merge-on-read`** — probe came
back 0 delete files but with a multi-file seed (whole-file delete = legit metadata delete), so it's
**inconclusive**; the real test needs the `COALESCE(1)` single-file seed the MoR discriminator uses.

**E10. Snapshots expiring on a branch (your "obscure" example)** — untested; probed
(`probe.branch.expire`). `expire_snapshots retain_last=1` on a table with a branch: **branch refs
survive** (`refs` = main+eb), branch stays readable, but shared **ancestry is pruned** (4→2
snapshots: only the two ref heads survive) — so time travel to pre-branch-point snapshots dies for
everyone. OpenHouse's own expiration job is safe the same way: `SnapshotsExpirationSparkApp` uses
Iceberg's ref-aware `expireSnapshots()` API (`apps/spark/.../Operations.java:268-287`). Promote.

---

## 3. Audit of the existing tests (interaction lens)

- **`prep.ordered:*` / `prep.evolved:*` (492 cases)** — genuine DDL×DML interaction multipliers ✅.
  But they cover exactly 2 DDLs; the audit above shows the missing third axis is *history* (travel/
  restore/expire after DDL), not more DDL×DML volume. Note: prep multipliers run on the 6 CoW
  layouts only — **MoR × evolved-schema DML is uncovered** (low risk: position deletes track file
  paths, not schemas; documented, not planned).
- **`timeTravel.*`, `restore.*`, `maintenance.*`** — all built on `coreTwoSnapshots`, a pure-DML
  lineage. None has a DDL, RTAS, or branch in its history — exactly the blind spot E2/E3/E7/E8/E10
  expose. The probes now define the missing cases.
- **`ddl.rtas.*`** — verifies the replace + both guards ✅, but never looks at the table's *history*
  after replace (snapshots/refs/travel), and has **no write-after-RTAS** (the one DML-after-DDL gap
  the earlier audit pass missed — every other DDL got one).
- **`branch.*`** — one direction only (branch→main leak, G8). The main→branch direction (E5) and
  branch×maintenance (E10) were unprobed until now. `branch.ddlLeak.addColumn` and
  `probe.branch.mainDdl` are two halves of the same table-global-schema story.
- **`control.lock.enforcement`** — locks one op (UPDATE). Correctly representative for the normal
  path (single shared check) — but the **replace path skips the lock check entirely (G2)**, so
  "representative" breaks exactly there. RTAS-on-locked-table needs its own characterization test.
- **`morVerify` (CoW/MoR discriminator)** — create-time MoR only; the ALTER-to-MoR transition (A8)
  is untested and the naive probe is confounded by file layout (needs the single-file seed).
- **Negatives** — all assert typed exception + message substring ✅; the planned "not a raw
  stacktrace/INTERNAL_ERROR" readability regression guard (AUDIT-FINDINGS Audit B) is still pending.

## 4. New product findings (fed into AUDIT-FINDINGS.md)

- **G9 — RTAS bypasses the partition-spec-evolution guard** (code recon; live demo pending).
  `checkPartitionSpecEvolution` only runs in `updateEligibilityCheck` (update branch); the
  stage-replace/replace branch never calls it, and `validateReplaceTable` checks RTAS/WAP/replication
  but not the spec. Same shape as G2 (lock) — the replace path dodges update-path guards. Likely
  also lets RTAS **drop columns** (blocked via ALTER — the E1 rejection) since replace redefines the
  schema wholesale: A4.
- **Behavior note (not a guard gap): rolled-past snapshots are expirable.** After
  `rollback_to_snapshot`, the snapshots you rolled *past* are unreferenced; the history-policy-driven
  expiration job (`TableSnapshotsExpirationTask:44-58`) will delete them — the rollback becomes
  permanent, silently. Pinning with a tag prevents it. Worth one 🌀 test (C1) + a doc note.
- **Confirmed sound:** OpenHouse's expiration job is ref-aware (Iceberg `ExpireSnapshots`), matching
  the probe result — branch heads survive, shared ancestry prunes per `retain_last`.

## 5. Prioritized execution plan (next phase)

**P1 — promote the 7 probes to real assertion tests** (~10 cases, code already drafted in
`interaction-probes.patch`; convert DIAG→assert): `ddl.tt.afterAddColumn`, `ddl.restore.afterAddColumn`,
`rtas.historyPreserved`, `rtas.restoreRejected` (typed), `branch.ttBeforeBranchPoint` (+conf-precedence),
`branch.mainDdlImmediate` (characterization, pairs with G8), `branch.expireProtectsRefs`,
`ddl.dropCol.afterData`, `createFlags.wapReplaceAtCreate`.
**P1 — G-gap characterizations (2 cases):** `rtas.onLockedTable` (G2 live: lock → RTAS succeeds =
the bug, characterization like G8) and `rtas.partitionSpecChange` (G9 live: RTAS with a different
`PARTITIONED BY` succeeds where ALTER is rejected). Plus `rtas.dropsColumn` (A4).
**P2 (~5 cases):** `set_current_snapshot` across RTAS (the recovery path); RTAS on a table with an
existing branch (refs survive into the new lineage? readable?); `rollback_to_snapshot` while
`spark.wap.branch` is set (does restore respect the branch conf or hit main? — C4); expire-after-
rollback (C1, the permanence footgun); write-after-RTAS on `ddl.rtas.enabled`.
**P3 (~3 cases):** ALTER-to-MoR with single-file seed (A8); `rewrite_data_files` on an evolved-schema
table (C3); user-prop survival across RTAS.

Budget: ~20 new cases, all single-layout — interaction behaviors, not multipliers. No new axes crossed
with format/partitioning (the ⛔ rows above say why).
