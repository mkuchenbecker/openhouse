# delta-harness — FEATURE ANALYSIS PLAN (analysis-first)

Companion to `TEST-PLAN.md` / `DDL-TEST-PLAN.md` / `BRANCHING-PLAN.md` / `INTERACTION-AUDIT.md`.
Those plans grew coverage bottom-up (operations × layouts, then pairs). This plan is the top-down
successor, built on the lesson of **G11**: the worst defects live in **state flows**, not operation
pairs, and they are findable **before running anything** if the feature's state dependencies are
understood first. **No feature is tested until its understanding checklist is complete.**

Supporting research: `INDUSTRY-RETENTION-SURVEY.md` (the A/B/C/D reference models cited throughout)
and `MODALITY-RECON.md` (H1–H8: the first eight predicted hazards, now code/bytecode-verified —
checklist items marked "recon pending/in-flight" below are ANSWERED there: streaming×expiration=H1,
changelog×expiration=H2, tags×RTAS=H3, lock×maintenance=H4, retention×branches=H5, rename=H6,
wap-gate=H7, ADD COLUMN×INSERT=H8).

---

## 1. The method

### 1.1 The state model (generalize G11, don't re-derive it)

- Every feature **F** has a **state-dependency set S(F)**: the metadata / data / lineage its
  commands **consume** — not just what a read touches at rest, but what its *future* commands need
  (a branch read needs only the ref head; the branch **merge** needs the ancestry path between
  refs — that difference is exactly where G11 hid).
- Every operation **O** has a **destruction set D(O)**: state it removes, rewrites, or invalidates.
  Expiration destroys ancestry; RTAS destroys lineage/schema/props/**policies** (G10); DDL destroys
  writer-schema assumptions; rename destroys identity bindings; compaction destroys file identity;
  and the **policy jobs run destroyers automatically on a schedule** (3-day default TTL even
  unconfigured — G11).
- A **modality hazard** exists wherever `S(F) ∩ D(O) ≠ ∅` with **no preservation rule, no guard,
  and no documented window**. The feature's command surface silently partitions into pre-O and
  post-O behavior — it becomes **modal with respect to time** — and pairwise tests stay green
  because the pair validates the wrong consumer.

### 1.2 Contract-quality tiers (how a hazard resolves)

| Tier | Definition | Reference model |
|---|---|---|
| **TOTAL** | Surface unaffected by O — preservation rule or true commutation | git/Nessie reachability-retention (A); lakeFS ancestry-forever (B) |
| **CONSISTENTLY PARTIAL** | Documented window + honest error naming the retention rule | Delta VACUUM (D); Snowflake consumer-pinning (C); lakeFS HTTP 410 |
| **BROKEN** | Spurious error blaming the wrong cause, silent partial success, or silent destruction with deferred loud failure | G11 (`"not an ancestor"` when the real cause is expired ancestry; cherry-pick silent commit loss; staged-WAP silent delete, loud only at publish) |

Iceberg's per-ref recency expiration — the substrate OpenHouse inherits — is the industry outlier:
the only mainstream design that deletes **reachable** ancestry, and the modality is documented
nowhere upstream. Assume BROKEN until proven otherwise for anything downstream of expiration.

### 1.3 The consumer battery (CB)

The fixed set of state-consuming operations run **after** every destroyer. A destroyer is not
"covered" when reads survive it; it is covered when the battery is green (or its breaks are
classified and pinned):

| id | Consumer | Consumes |
|---|---|---|
| CB-W | full DML write (insert/update/delete/merge) | current schema, head snapshot, writer assumptions |
| CB-R | read / scan / projection | head snapshot + reachable files |
| CB-TT | time travel to a **pre-O** snapshot (version + timestamp) | snapshot list, schemas list, historical files |
| CB-RS | restore across O (`rollback_to_snapshot`, `set_current_snapshot`) | ancestry (rollback) / snapshot presence (set_current) |
| CB-BR | branch create + write + **MERGE** (`fast_forward`, cherry-pick) + WAP publish | ancestry connectivity between refs, staged snapshots |
| CB-ST | streaming resume from a **pre-O** checkpoint | checkpointed snapshot pointer + intermediate lineage |
| CB-CDC | changelog / incremental read **over the O boundary** | contiguous snapshot range + change metadata |
| CB-MX | maintenance ops (expire / compact / orphan) after O | consistent metadata to operate on |
| CB-GOV | read-back of grants / column tags / policies / reserved props | policies plane, ACL bindings, identity |

### 1.4 The gate sequence — UNDERSTAND → CLASSIFY → PREDICT → TEST

1. **UNDERSTAND** — complete the feature's understanding checklist (§3) from code / bytecode /
   docs. Every knob enumerated; every S/D line sourced to a file:line or an existing probe.
2. **CLASSIFY** — assign each `S(F) ∩ D(O)` intersection a *claimed* tier (TOTAL / PARTIAL /
   BROKEN) with the mechanism that justifies it. "Probably fine" is not a classification.
3. **PREDICT** — write the expected outcome of each battery cell **before running**: exact
   behavior, exact error text tier, exact silent-loss mode. *A test that cannot state its
   predicted modality is not ready to run.* Predictions are recorded in the matrix (§4) as 🎯.
4. **TEST** — run the bounded battery slice (§3 "test gate"). Green confirms the classification;
   red is either a finding (tag, `AUDIT-FINDINGS.md`/`BUGS.md`) or a wrong prediction (go back
   to UNDERSTAND — the model was incomplete, which is itself the signal).

Rules carried over from the G11 post-mortem (INTERACTION-AUDIT §6): lifecycle-complete fixtures
(a branch fixture ends in merge-or-drop, never a read); interpose destroyers with **production
defaults** (3-day TTL), not benign parameters; assert the destroyed **precondition** (branch point
survives) as well as the consumer outcome (merge works) — the precondition assertion diagnoses,
the outcome assertion only detects.

---

## 2. Feature inventory (the complete surface)

Enumerated from: harness case ids (`OpenHouseMatrix.scala`, 1,231+ cases), `services/tables`
(controllers: Tables / IcebergSnapshots / Databases; lock, ACL, policies, soft-delete endpoints),
`apps/spark/.../jobs` (Retention, SnapshotsExpiration, OrphanFilesDeletion ×3 variants,
DataCompaction, StagedFilesDeletion, TableStats/SortStats/DataLayoutStrategy), `services/jobs` +
scheduler, `integrations/java+spark` (the forked Iceberg client: commit split, WAP, branch refs).

| # | Feature | Role | Status today (harness) |
|---|---|---|---|
| F1 | Core DML (insert/append/overwrite, delete, update, merge, truncate) | consumer + destroyer(overwrite) | ✅ 660-case baseline |
| F2 | Schema evolution (ADD/widen/comment/position; rejected: drop/rename/narrow/NOT NULL) | destroyer | ✅ B/N + prep.evolved; composition hazard open |
| F3 | Partition spec + transforms (+ clustering-as-spec) | destroyer (via RTAS only) | ✅ transforms; evolution pinned rejected; G9 |
| F4 | Sort order / write distribution (`WRITE ORDERED BY`, distribution-mode) | benign prep | ✅ + prep.ordered ×318 |
| F5 | Table properties & flags (WAP, replace, MoR modes, format-version, target-file-size, previous-versions-max, write.format.default) | destroyer (toggles) | ✅ partial; G4 |
| F6 | CTAS / RTAS | **destroyer (worst single)** | ✅ contract + interact.rtas.* ; G2/G9/G10 |
| F7 | Branching + tags (refs, fast_forward, cherry-pick, replace/drop branch) | consumer (ancestry) | ✅ B1–B5 + G11 demonstrated |
| F8 | WAP staging / publish (`wap.id`, cherrypick, publish_changes) | consumer (staged snapshots) | ✅ core; staged-loss = G11-P3 |
| F9 | Time travel (VERSION/TIMESTAMP AS OF, ref names, incremental) | consumer | ✅ + interact.ddl/rtas TT |
| F10 | Restore / rollback (rollback_to_snapshot/timestamp, set_current_snapshot) | consumer + destroyer (orphans rolled-past snapshots) | ✅ core + across-RTAS + expire-permanence |
| F11 | Snapshot expiration (CALL + `SnapshotsExpirationSparkApp`) | **destroyer (worst automatic)** | ✅ CALL + G11; job-path semantics ❓ |
| F12 | Orphan file removal (remove_orphan_files; Orphan/Batched/TableDirectory/StagedFiles jobs) | destroyer | ✅ smoke only (`maintenance.removeOrphanFiles`, `surface.proc.removeOrphanReal`) |
| F13 | Compaction ×3 (rewrite_data_files / manifests / position_deletes) | destroyer (file identity) | ✅ smoke + compactEvolved |
| F14 | Metadata tables + hidden columns (snapshots/history/files/manifests/refs/…, `_file`,`_pos`,…) | consumer (diagnostic) | ✅ surface.meta.* sweep |
| F15 | Streaming read / write (+ checkpoint resume) | consumer (checkpoint lineage) | ✅ smoke (`surface.stream.{read,write}`); resume ❓ in-flight |
| F16 | CDC / changelog (`create_changelog_view`, incremental snapshot-range read) | consumer (contiguous ranges) | ✅ smoke (`surface.cdc.changelogView`); boundary ❓ in-flight |
| F17 | Policy: retention TTL (`SET POLICY RETENTION`) + `RetentionSparkApp` | destroyer (data rows, scheduled) | ✅ DDL round-trip; job effects ❓ in-flight |
| F18 | Policy: history (max-age/versions) + `TableSnapshotsExpirationTask` | destroyer (drives F11 automatically) | ✅ DDL; 3-day default = G11 amplifier |
| F19 | Policy: sharing + ACL GRANT/REVOKE/SHOW GRANTS (table + db scope) | consumer (identity, policies plane) | ✅ partial (embedded auth is a fidelity gap) |
| F20 | Policy: replication (+ tableType, replica commit path) | consumer (source lineage) | ✅ SQL-reachable; G3/G7 repo-layer ❓ |
| F21 | Policy JOBS plane (scheduler, `Operations.java`, job defaults) | **destroyer-orchestrator** | ❌ untested as a plane; ❓ in-flight (lock×jobs, retention×branches) |
| F22 | Table lock (REST `/lock`) | guard (a deliberate destroyer of writability) | ✅ enforcement; G2 bypass demonstrated |
| F23 | Column tags (`SET TAG (PII/HC)`) | consumer (schema identity, policies plane) | ✅ round-trip; ×RTAS ❓ in-flight |
| F24 | Namespaces / databases (implicit create; CREATE/DROP rejected) | substrate | ✅ negatives pinned |
| F25 | Rename table | destroyer (identity bindings) | ✅ B; × consumers ❓ in-flight |
| F26 | Register / import procedures (register_table, snapshot, add_files, migrate) | destroyer (contract bypass?) | ✅ pin (`surface.pin.importProcs`); managed-create bypass ❓ |
| F27 | Encryption | **deferred** (OSS un-wired, plugin private — BUGS.md) | 🐛 tagged SKIP; contract documented |
| F28 | Soft-delete / undrop | **deferred** (embedded stub + `purge=true` hard-coded — AUDIT C) | 🐛 tagged SKIP |
| F29 | Concurrency / commit protocol (CAS, retries, metadata-vs-snapshot split, subtractive merge) | cross-cutting substrate | ✅ smoke (`surface.conc.*`); protocol semantics ❓ |

---

## 3. Per-feature analysis checklists

Template per feature: **Surface** (100% of the feature) · **Contract + industry ref** ·
**S/D lines** · **Understanding checklist** (recon before testing; `[x] (ref)` = already answered)
· **Predicted modality hazards** (both directions) · **Test gate** (battery slice + count).

### F1 — Core DML
- **Surface:** INSERT INTO/OVERWRITE (static+dynamic), DataFrame append/overwrite/overwritePartitions, DELETE (all predicate shapes), UPDATE, MERGE (all clause forms), TRUNCATE; CoW + MoR modes.
- **Contract:** ACID row mutation on the current table state. Industry ref: total by definition — DML is the canonical **consumer**, not a modal feature. INSERT OVERWRITE is also a destroyer (rewrites row state, +lineage stays intact).
- **S(F):** current schema + head snapshot + write-mode props. **D(F):** overwrite destroys prior row state (recoverable via TT until expiration).
- **Understanding checklist:**
  - [x] Full DML surface green in isolation ×6 layouts ×CoW/MoR (TEST-PLAN 1–5)
  - [x] Partial-column INSERT rejected — no null-fill (`insert.explicitColumns` bug, BUGS.md)
  - [ ] Is required-by-default (vs Iceberg optional) the *cause* of the null-fill rejection? (BUGS.md follow-up — determines whether F2's ADD COLUMN breaks ALL existing explicit-column writers)
  - [ ] ❓ in-flight: ADD COLUMN × INSERT writer-compat recon (results pending)
- **Predicted hazards:** consumed by everything; destroyed by nothing except its writers' schema assumptions (see F2). Reverse direction: INSERT OVERWRITE + expiration = permanent row loss (documented-window question, tier ❓).
- **Test gate:** none new standalone — F1 *is* CB-W/CB-R for every other feature. ~0 cases.

### F2 — Schema evolution
- **Surface:** ADD COLUMN(S) (single/multi/COMMENT/AFTER/nested — `surface.schema.nestedAddField`), ALTER COLUMN TYPE widen (int→bigint, decimal — `surface.schema.decimalWiden`), relax NOT NULL, reorder; **rejected:** DROP COLUMN, RENAME COLUMN (silent no-op bug), narrow, SET NOT NULL, nested drop.
- **Contract:** additive evolution with schema-per-snapshot history. Industry ref: (D)-adjacent — Iceberg schema ids are versioned like Delta's; intended TOTAL for reads, PARTIAL for writers.
- **S(F):** schemas list in metadata. **D(F):** destroys **writer-schema assumptions** — every existing explicit-column and positional writer, every streaming writer mid-flight, every branch writer (G8-mirror).
- **Understanding checklist:**
  - [x] TT reads historical schema per snapshot (`interact.ddl.ttAfterAddColumn`)
  - [x] Restore reverts data, NOT schema (`interact.ddl.restoreAfterAddColumn`)
  - [x] DDL is table-global: instantly breaks old-arity branch writers (`interact.branch.mainDdlImmediate`, G8-mirror)
  - [x] prep.evolved had to EXCLUDE insert/merge ops — the +1-column arity break is real (DDL-TEST-PLAN Phase 24)
  - [ ] ❓ in-flight: does ADD COLUMN break **explicit-column-list** writers too (not just positional)? Combined with the `insert.explicitColumns` bug, predicted YES → ADD COLUMN may break **all** existing writers, a composition hazard worse than either bug alone
  - [ ] Does a schema change invalidate a streaming writer's running query / a resumed checkpoint's expected schema? (feeds F15)
  - [ ] Does changelog output carry schema-change boundaries or silently project? (feeds F16)
- **Predicted hazards:** D(F2) ∩ S(F1-writers) 🎯 BROKEN (silent until the writer's next batch, error blames arity not the DDL); D(F2) ∩ S(F15/F16) ❓. Consumed-by: RTAS redefines schema wholesale bypassing F2's guards (G9 — drop-via-RTAS reachable NOW).
- **Test gate:** CB-W(explicit-column writers pre/post ADD), CB-ST, CB-CDC across an ADD COLUMN. ~6 cases.

### F3 — Partition spec + transforms
- **Surface:** PARTITIONED BY identity/bucket/truncate/years/months/days/hours; clustering columns (= spec fields, Phase 21); **rejected:** ADD/DROP/REPLACE PARTITION FIELD (pinned).
- **Contract:** fixed-at-create spec ("recreate the table" — an honest PARTIAL by rejection). Industry ref: stricter than Iceberg (which allows evolution) — a deliberate narrowing.
- **S(F):** spec in metadata + physical file layout agreement. **D(F):** n/a via ALTER (rejected); via RTAS = G9.
- **Understanding checklist:**
  - [x] Evolution rejected + pinned; dormant ⏸ row documented (INTERACTION-AUDIT ⏸³)
  - [x] RTAS bypasses the spec guard (G9, demonstrated live)
  - [x] Replica commit path skips spec check (G3, code recon)
  - [ ] After a G9 spec change: do old-spec files still scan correctly (mixed-spec table)? Does `.partitions` metadata table lie? Does partition-predicate DELETE (metadata-only path) still hit the right files?
- **Predicted hazards:** post-RTAS-spec-change table is a spec-evolved table OpenHouse never intended to exist → every partition-aware consumer (dynamic overwrite, partition-predicate delete, retention job's partition-column TTL) is 🎯 unknown-territory on it. Retention policy names a partition column — RTAS re-partitioning away that column with retention set = 🎯 silent policy orphaning (compounds G10).
- **Test gate:** consumer battery on a G9-produced mixed-spec table: CB-W (dynamic overwrite), CB-R, retention-policy read-back. ~5 cases.

### F4 — Sort order / write distribution
- **Surface:** WRITE ORDERED BY (single/multi/DESC NULLS FIRST), WRITE UNORDERED, WRITE DISTRIBUTED BY, `write.distribution-mode` (`surface.write.distributionHash`).
- **Contract:** write-time layout hint; zero read-side semantics. Industry ref: TOTAL — nothing consumes sort order except the writer.
- **S(F):** sortOrder in metadata. **D(F):** nothing (arity-neutral, proven by prep.ordered ×318).
- **Understanding checklist:**
  - [x] Full DML cross green (Phase 24)
  - [x] Table-global — leaks to main from a branch like all DDL (G8 leg, `branch.leak.writeOrderedBy`)
  - [ ] Does RTAS preserve or reset sort order? (one recon read of the replace path — predicted reset, same plane as G10)
- **Predicted hazards:** none inbound (no consumer). Outbound: covered by G8.
- **Test gate:** 1 case (sort order across RTAS, folded into F6's battery).

### F5 — Table properties & feature flags
- **Surface:** user props SET/UNSET; reserved `openhouse.*`/`policies` (rejected); forced `format-version`; honored `previous-versions-max`, `write.format.default`, `write.target-file-size-bytes` (`surface.write.targetFileSize`); **flags:** `write.wap.enabled`, `replace.enabled`, `write.{delete,update,merge}.mode`, `write.distribution-mode`, `write.metadata.delete-after-commit.enabled`.
- **Contract:** three property planes (user / reserved / behavioral flags) with different guard rules. Industry ref: flags SHOULD be (D)-style guarded state transitions (Delta protocol-version upgrades are one-way and checked); OpenHouse's are free-toggle → G4.
- **S(F):** props map. **D(F):** **flag toggles destroy the state machines built on them**: WAP-off strands staged snapshots (G4); replace-off closes the G2/G9/G10 hole (good) but silently; MoR-toggle changes physical write path mid-history (`interact.mor.alterToMor` ✅).
- **Understanding checklist:**
  - [x] G4 mechanism (plain `write.*` props, no guard) — code recon
  - [x] `branch.wapToggle.noGuard` demonstrates the toggle ✅
  - [ ] ❓ in-flight: `wap.enabled` gate recon — what exactly does the flag gate (staging? branch conf? publish?) and what dangles when flipped off with each artifact present
  - [ ] Enumerate the FULL flag list from `TableProperties`/create-path defaulting code — are there more unguarded state-machine flags (e.g. `delete-after-commit` — does toggling it on retro-delete old metadata.json files that TT-by-timestamp needs)?
- **Predicted hazards:** D(F5-toggle) ∩ S(F8-staged) 🎯 BROKEN (silent stranding, loud only at publish — G4); `delete-after-commit` × metadata-file consumers ❓.
- **Test gate:** toggle-with-artifacts-present battery: WAP-off with staged; replace-off then RTAS; delete-after-commit + TT-by-timestamp. ~5 cases.

### F6 — CTAS / RTAS
- **Surface:** CTAS; CREATE OR REPLACE (RTAS) gated on `replace.enabled`; stage-replace branch of the commit path; property/policy merge semantics across replace.
- **Contract:** atomic re-definition preserving identity (tableUUID) and history (old snapshots survive via subtractive merge). Industry ref: intended (B)-style — new lineage, old lineage retained for TT. Actual: **BROKEN in four demonstrated ways** — the replace path is the single largest guard-bypass cluster.
- **S(F):** replace privilege + `replace.enabled`. **D(F):** destroys **lineage continuity** (new snapshot line — rollback across it rejected, `set_current` is the escape hatch), **policies plane** (G10), **update-path guard coverage** (G2 lock, G9 spec/schema), and ❓ tags/ACL/sortOrder.
- **Understanding checklist:**
  - [x] History preserved, TT works across replace (`interact.rtas.historyPreserved`)
  - [x] rollback rejected typed / set_current recovers (`interact.rtas.{restoreRejected,setCurrentRecovery}`)
  - [x] Props merge semantics settled (user survive, statement wins, create-defaulting re-runs)
  - [x] G2/G9/G10 demonstrated live
  - [x] Branch refs survive replace (`interact.rtas.withBranch`)
  - [ ] ❓ in-flight: column tags × RTAS (predicted wiped — same plane as G10)
  - [ ] Grants/sharing state × RTAS (sharing lives in `policies` → predicted wiped with it → orphaned grants on an unshared table?)
  - [ ] Streaming checkpoint × RTAS: a resumed reader whose checkpoint points into the OLD lineage — silent empty batch, wrong data, or loud error? (feeds F15)
  - [ ] Changelog across the replace boundary (feeds F16)
- **Predicted hazards:** D(F6) intersects S of nearly everything: F17–F20 policies 🎯 (G10 + extensions), F15/F16 🎯 (lineage break), F22 ✅ broken (G2), F3 ✅ broken (G9). Inbound: F5's `replace.enabled` toggle is F6's only guard — an unguarded guard (G4-class).
- **Test gate:** complete the RTAS consumer battery: CB-ST, CB-CDC, CB-GOV(tags+grants), sort order. ~7 cases. (RTAS full-cross F remains a separate DDL-TEST-PLAN item.)

### F7 — Branching + tags
- **Surface:** CREATE/DROP/REPLACE BRANCH, CREATE TAG, `t.branch_x` writes, `VERSION AS OF 'ref'`, `spark.wap.branch` conf, `fast_forward`, `cherrypick_snapshot`, `.refs` metadata.
- **Contract:** git-model isolate-then-integrate (industry ref A). Actual: BROKEN post-expiration (G11) — the merge half of the surface is modal w.r.t. time; reads are TOTAL.
- **S(F):** ref map (server-opaque) + **ancestry connectivity between refs** (merge) + table-global schema/spec/props (writers). **D(F):** DDL-on-branch destroys main's schema (G8 — the feature destroys *outside* its own scope); DROP BRANCH orphans branch-only snapshots (→ expiration/orphan fodder).
- **Understanding checklist:**
  - [x] Isolation, both targeting mechanisms, lifecycle ops (B1–B5)
  - [x] G8 leak, 3 legs (`branch.leak.*`)
  - [x] G11 full mechanism: per-ref head-anchored retention, silent ancestry-walk truncation, no rebase, cherry-pick silent loss — bytecode-verified + live
  - [x] Refs can never dangle (builder validation, G11-P4); CAS blocks resurrection (P5)
  - [ ] ❓ in-flight: retention **job** (row TTL) × branches — does `RetentionSparkApp` delete rows on main only, and do branch snapshots keep referencing the deleted files (safe until orphan removal?) → 3-chain retention→orphan→branch-read
  - [ ] REPLACE BRANCH semantics (`branch.replaceBranch` exists — confirm what it pins)
  - [ ] What does DROP BRANCH leave behind, and how fast does the expiration job harvest it (staged-orphan window)?
- **Predicted hazards:** inbound: F11 ✅ BROKEN (G11), F17-job ❓ 🎯 (the retention→orphan→branch 3-chain — same shape as G11 with data files instead of snapshots), F2 ✅ (writer break), F6 partially ✅. Outbound: G8.
- **Test gate:** the retention-job 3-chain + DROP-BRANCH-then-maintenance. ~4 cases (G11 battery already landed).

### F8 — WAP staging / publish
- **Surface:** `write.wap.enabled`, `spark.wap.id`, staged (unreferenced) snapshots, `cherrypick_snapshot`, `publish_changes` (`surface.proc.publishChanges`), wap.id summary lookup; negatives (double cherry-pick, id+branch conflict).
- **Contract:** stage invisibly, publish atomically — audit-then-promote. Industry ref: (C)-shaped — the staged artifact should be **pinned** for its publish window. Actual: staged snapshots are unreferenced → BROKEN under age-based expiration (G11-P3: silent delete, loud `Cannot apply unknown WAP ID` only at publish).
- **S(F):** staged snapshot presence + wap.id summary + `write.wap.enabled` staying on. **D(F):** none (additive) — but its artifacts are uniquely fragile.
- **Understanding checklist:**
  - [x] Stage→publish green; staged-loss demonstrated (`interact.branch.expireMerge.stagedWapLoss`)
  - [x] G4: WAP-off strands staged (code recon + `branch.wapToggle.noGuard`)
  - [ ] What is the intended publish window? No documented TTL for staged snapshots anywhere (the D-tier fix would be a named property) — confirm absence upstream, then file as the G11-P3 recommendation
  - [ ] Does `remove_orphan_files` treat staged-snapshot files as referenced (they're in the snapshot list) — and does the **StagedFilesDeletionSparkApp** job target exactly these? (job name says yes — recon its selection criteria + age threshold)
- **Predicted hazards:** inbound: F11 ✅ BROKEN (P3), F5-toggle ✅ (G4), **F12/StagedFilesDeletion job 🎯** (a job purpose-built to delete the feature's working state — window + signal unknown = predicted BROKEN-or-undocumented-PARTIAL).
- **Test gate:** StagedFilesDeletion job vs an in-window staged snapshot; orphan-removal vs staged files. ~3 cases.

### F9 — Time travel
- **Surface:** `VERSION AS OF <id|ref>`, `TIMESTAMP AS OF`, `.snapshots/.history/.metadata_log_entries`, conf-precedence with `spark.wap.branch` (pinned ✅).
- **Contract:** read any retained snapshot with its historical schema. Industry ref: (D) — honestly PARTIAL, window = expiration policy. The question is whether the error at the window edge is honest.
- **S(F):** snapshot list + schemas list + data files + (for TIMESTAMP) metadata-log history. **D(F):** none.
- **Understanding checklist:**
  - [x] Schema-per-snapshot correct (`interact.ddl.ttAfterAddColumn`); works across RTAS; branch/tag refs; precedence pinned
  - [ ] Error-message tier when traveling to an **expired** snapshot: does it say "expired per policy X" (D-tier) or a bare "cannot find snapshot" (MEH)? One run, graded per Audit B
  - [ ] TIMESTAMP AS OF after `delete-after-commit` metadata pruning + after `previous-versions-max` trimming — same window, different mechanism, same honest-error question
- **Predicted hazards:** inbound only: F11 (window — expected), F10 (rolled-past + expire = permanent, ✅ known), F13 ⛔ (compaction keeps old snapshots). Tier prediction: CONSISTENTLY PARTIAL with MEH errors.
- **Test gate:** error-grading cases only. ~3 cases.

### F10 — Restore / rollback
- **Surface:** `rollback_to_snapshot`, `rollback_to_timestamp` (untested), `set_current_snapshot` (no ancestry check — the RTAS escape hatch), targets-main-even-under-wap.branch (pinned ✅).
- **Contract:** move HEAD backwards, non-destructively (history retained). Industry ref: (A)-style reflog semantics intended. Actual: PARTIAL-undocumented — rolled-past snapshots become unreferenced → the scheduled expiration job makes the rollback **permanent silently** (✅ `interact.restore.expireAfterRollback`).
- **S(F):** ancestry (rollback) / snapshot presence (set_current). **D(F):** un-references rolled-past snapshots (deferred destruction via F11/F18 defaults).
- **Understanding checklist:**
  - [x] Across-RTAS behavior, expire-permanence, wap.branch targeting — all pinned
  - [ ] `rollback_to_timestamp` — same or different resolution path (metadata log vs snapshot log)?
  - [ ] Write-after-rollback then re-rollback forward: is the abandoned "future" line mergeable/travelable before expiration? (divergence without branches)
  - [ ] Does streaming/CDC handle HEAD moving backwards? (a checkpoint now points at a snapshot "ahead of" HEAD — feeds F15/F16) 🎯 predicted confusion
- **Predicted hazards:** outbound: rolled-past artifacts (tag-to-pin is the only preservation rule — undocumented); inbound: expiration (✅ known). F15/F16 over a rollback boundary 🎯.
- **Test gate:** rollback_to_timestamp; stream-resume + changelog across a rollback. ~4 cases.

### F11 — Snapshot expiration
- **Surface:** `CALL expire_snapshots(older_than, retain_last, snapshot_ids…)`; `SnapshotsExpirationSparkApp` (…: **3-day default TTL even unconfigured**); history policy (F18) driving `TableSnapshotsExpirationTask`; `cleanExpiredFiles(false)` in the job (files survive, metadata dies).
- **Contract (claimed):** remove unneeded history, keep refs safe (job is ref-aware). Industry ref: **the Iceberg outlier** — per-ref head-anchored recency; deletes reachable ancestry; documented nowhere. Actual tier: **BROKEN** for every ancestry consumer (G11).
- **S(F):** snapshot log + refs. **D(F):** ancestry connectivity (G11), staged WAP snapshots (P3), rolled-past snapshots, TT reachability, ❓ streaming/CDC ranges.
- **Understanding checklist:**
  - [x] Full G11 mechanism + 7-property contract table (AUDIT-FINDINGS G11)
  - [x] Job is ref-aware for heads; ancestry between refs unprotected
  - [ ] ❓ in-flight: streaming checkpoint × expiration (predicted 🎯 BROKEN: checkpointed snapshot expired → resume fails spuriously or silently skips — the G11 shape on the read side)
  - [ ] ❓ in-flight: changelog × expired range (predicted 🎯: silent gap or wrong-cause error)
  - [ ] CALL-path vs job-path parameter interaction: `retain_last` vs `older_than` precedence; can a CALL with aggressive params bypass the history-policy bounds (1–3 days validator)? — i.e. is the policy a floor or advisory?
  - [ ] `expire_snapshots(snapshot_ids => …)` targeted form: can it delete a ref target / branch point directly (should be guarded — `wap.neg.expireRefTarget` pins part of this)?
- **Predicted hazards:** the master destroyer — intersects S(F7-merge) ✅BROKEN, S(F8) ✅BROKEN, S(F9) PARTIAL, S(F10) ✅, S(F15) 🎯, S(F16) 🎯. Inbound: none destroys expiration.
- **Test gate:** CB-ST + CB-CDC across expiration (the two open in-flight recons → tests); CALL-vs-policy precedence. ~6 cases.

### F12 — Orphan file removal
- **Surface:** `CALL remove_orphan_files(older_than)` (24h guard, needs literal ts); jobs: `OrphanFilesDeletionSparkApp`, `BatchedOrphanFilesDeletionSparkApp`, `OrphanTableDirectoryDeletionSparkApp`, `StagedFilesDeletionSparkApp`.
- **Contract:** delete files unreachable from ANY metadata. Industry ref: (B) — data-GC below an intact commit DAG; safe iff the reachability walk is complete. The entire feature's correctness = one question: **what does the walk consider reachable?**
- **S(F):** full metadata reachability closure. **D(F):** physical files — the only destroyer whose mistakes are **unrecoverable** (no metadata record of what died).
- **Understanding checklist:**
  - [ ] Reachability completeness: are staged WAP snapshots, ALL branch/tag refs, statistics/puffin files, position-delete files, and metadata.json history in the walk? (Iceberg's is metadata-complete upstream; verify the fork + the OpenHouse job's own listing logic — `Operations.java`)
  - [ ] The 3-chain: expiration (drops staged/rolled-past from metadata) → orphan removal (files now truly orphaned → deleted) → G11's "copy-out recovery" claim **dies** (data files no longer survive). This bounds G11's recovery window — recon the two jobs' relative schedules/defaults
  - [ ] `OrphanTableDirectoryDeletionSparkApp`: deletes whole dirs of dropped tables — interaction with rename (old dir = orphan dir?) and register_table
  - [ ] Race: file written by an in-flight commit (uncommitted) vs the 24h `older_than` guard — is the guard the only protection?
- **Predicted hazards:** inbound-safe by design IF the walk is complete (❓). Outbound 🎯: post-expiration file deletion closes G11's only recovery path (predicted, high-severity, silent). Rename × orphan-dir job 🎯 ❓.
- **Test gate:** reachability probes (staged/branch/stats files survive ORF); the G11-recovery-window chain. ~5 cases.

### F13 — Compaction ×3
- **Surface:** `rewrite_data_files` (+ strategy/sort/where opts ❓), `rewrite_manifests`, `rewrite_position_deletes`; `DataCompactionSparkApp`; DataLayoutStrategyGenerator/SortStatsCollection (advisory input plane).
- **Contract:** semantics-preserving physical rewrite (new snapshot, op=replace). Industry ref: TOTAL for readers (A/B-style — old snapshots keep old files); the risk is consumers of **file identity** and **change semantics**.
- **S(F):** current data/delete files. **D(F):** file identity (paths change); creates `replace`-operation snapshots that CDC must classify as non-changes.
- **Understanding checklist:**
  - [x] Rows preserved, evolved-schema values preserved (`maintenance.rewriteDataFiles`, `interact.maint.compactEvolved`); manifests + position-deletes procedures run (`surface.proc.*`)
  - [ ] Does the changelog/incremental read emit compaction snapshots as changes (false CDC events) or skip them? 🎯 key CDC question
  - [ ] Streaming read across a compaction snapshot: duplicate delivery? (`streaming-skip-overwrite/delete-snapshots` options — do they exist in the fork and what are defaults?)
  - [ ] `rewrite_position_deletes` on a table mid-MoR-toggle (F5) — coherent?
  - [ ] Full option surface of `rewrite_data_files` in the fork (sort/zorder/where) — enumerate, most is untested
- **Predicted hazards:** D(F13) ∩ S(F16) 🎯 (false/missing CDC events — classic upstream trap); ∩ S(F15) 🎯 (replay/duplicates); TT/restore/branch ⛔ (snapshot-based).
- **Test gate:** CB-CDC + CB-ST across each rewrite kind. ~6 cases.

### F14 — Metadata tables + hidden columns
- **Surface:** `.snapshots .history .refs .files .manifests .partitions .entries .metadata_log_entries .all_*` + hidden `_file _pos _spec_id _partition _deleted` (`surface.meta.*` ✅ sweep).
- **Contract:** honest introspection. Industry ref: TOTAL (pure reads).
- **S(F):** whatever metadata exists. **D(F):** none.
- **Understanding checklist:**
  - [x] Sweep green (`surface.meta.tableSweep`, `hiddenColumns`, `positionDeletes`)
  - [ ] Do metadata tables stay honest across the destroyers (post-RTAS `.history`, post-expiration `.snapshots` vs `.metadata_log_entries` divergence)? Cheap diagnostic assertions to fold into other batteries, not standalone
- **Test gate:** assertions folded into F6/F11 batteries. ~0 standalone.

### F15 — Streaming read / write
- **Surface:** `readStream`/`writeStream` on OpenHouse tables (✅ smoke both), checkpoint dir, resume-from-checkpoint, trigger modes, `stream-from-timestamp`/rate-limit options (fork surface ❓), streaming into a branch (❓ — `surface.write.dfToBranch` covers batch).
- **Contract:** exactly-once incremental consumption anchored to a **checkpointed snapshot pointer**. Industry ref: (D)-need — Delta documents the VACUUM/checkpoint window; here the window is undocumented.
- **S(F):** checkpointed snapshot id **+ every intermediate snapshot since it** (the largest ancestry appetite of any consumer — strictly bigger than TT's). **D(F):** none.
- **Understanding checklist:**
  - [ ] ❓ in-flight: checkpoint × expiration (predicted 🎯 BROKEN — the single highest-probability undiscovered G11-class defect: 3-day default TTL vs any paused-for-a-weekend stream)
  - [ ] Failure mode taxonomy: resume after checkpointed snapshot expired → loud? silent skip-to-earliest (data loss)? spurious?
  - [ ] Resume across RTAS (new lineage), rollback (HEAD behind checkpoint), compaction (replace snapshots), ADD COLUMN (schema drift)
  - [ ] Fork's streaming option surface (skip-delete/overwrite-snapshots etc.) — enumerate before testing
  - [ ] Streaming WRITE: commit protocol under concurrent batch writers; does it respect lock/WAP?
- **Predicted hazards:** inbound from F11 🎯, F6 🎯, F10 🎯, F13 🎯 — streaming is the consumer column with the most predicted breaks and zero chain coverage today.
- **Test gate:** CB-ST is this feature — one resume fixture × {expire, RTAS, rollback, compact, addColumn} destroyers. ~6 cases (slow ones — streaming cases cost ~10-30s each; budget separately).

### F16 — CDC / changelog
- **Surface:** `create_changelog_view` (✅ smoke), incremental read (`timeTravel.incrementalRead` ✅), start/end snapshot options, net-changes option (❓), `_deleted`/change-type columns.
- **Contract:** complete, correctly-typed change stream over a snapshot range. Industry ref: (D)-need — same window problem as streaming.
- **S(F):** contiguous snapshot range + change metadata per snapshot. **D(F):** none.
- **Understanding checklist:**
  - [ ] ❓ in-flight: changelog over an expired range (predicted 🎯: silent gap = BROKEN, or wrong-cause error = MEH)
  - [ ] Changelog across RTAS boundary (lineage break mid-range), across compaction (false events?), across rollback (negative progress)
  - [ ] MoR tables: are position-deletes surfaced as row-level change events correctly?
  - [ ] Option surface of `create_changelog_view` in the fork — enumerate
- **Predicted hazards:** mirror of F15 (same inbound destroyers, batch-shaped).
- **Test gate:** CB-CDC × {expire, RTAS, compact, rollback}. ~5 cases.

### F17 — Policy: retention TTL (+ RetentionSparkApp)
- **Surface:** `SET POLICY (RETENTION=Nd [ON COLUMN c WHERE pattern=…])` (✅ round-trip); `RetentionSparkApp` executing row deletion by partition/column age; granularity/pattern validators.
- **Contract:** declarative row TTL executed by a scheduled job. Industry ref: the job is a **destroyer running on autopilot** — needs (C)-style consumer awareness it almost certainly lacks.
- **S(F):** policies blob + the named partition column existing. **D(F):** data rows (via ordinary DELETE commits — so TT-recoverable until expiration; the retention→expiration chain makes row loss permanent on the policy's schedule).
- **Understanding checklist:**
  - [x] DDL round-trip + validator negatives (Phase 20)
  - [x] Policies blob wiped by RTAS (G10)
  - [ ] ❓ in-flight: retention job × branches — job deletes on main; do branch heads keep the rows (divergence by policy)? Is the job even branch-aware (predicted: main-only → silent divergence 🎯)?
  - [ ] Does the job run on tables whose retention column was removed by a G9 RTAS re-partition (silent no-op? crash?)
  - [ ] Job's delete mechanics: partition drop vs row DELETE — determines CDC visibility of policy deletions
- **Predicted hazards:** inbound: G10 ✅ (policy silently wiped → TTL silently stops — governance modality); G9 🎯 (column gone). Outbound: branch divergence 🎯, CDC noise ❓.
- **Test gate:** retention job × branch fixture; post-G10 job behavior. ~4 cases (needs job invocation via `Operations`/local `CALL`-equivalent — the jobs are Spark apps callable in-process per Phase 27 reframing).

### F18 — Policy: history (max-age / versions)
- **Surface:** `SET POLICY (HISTORY MAX_AGE=… VERSIONS=…)` (✅ + validator negatives); drives `TableSnapshotsExpirationTask`; **3-day default when unset**.
- **Contract:** bounded snapshot history. Reality: **the automation that weaponizes F11** (G11's "destroyer on a schedule").
- **S(F):** policies blob. **D(F):** = D(F11), scheduled.
- **Understanding checklist:**
  - [x] Bounds validators (1–3 days / 2–100 versions) GOOD-tier; default TTL confirmed (`SnapshotsExpirationSparkApp:44-46`)
  - [ ] Interplay with `retain_last`-style minimums: does VERSIONS floor protect branch points? (predicted no — per-ref, G11) — one recon read
  - [ ] Is there ANY documented warning that history policy breaks branch merge / staged WAP / old checkpoints? (predicted no → doc finding)
- **Predicted hazards:** = F11's, automatic. Inbound: G10 wipes it → table silently reverts to the 3-day DEFAULT (wiping a *lenient* policy makes expiration MORE aggressive — a nasty G10 corollary 🎯 worth one test).
- **Test gate:** the G10-corollary case. ~1 case.

### F19 — Policy: sharing + ACL
- **Surface:** `SET POLICY (SHARING=…)`, GRANT/REVOKE/SHOW GRANTS (table + database scope), grant-on-unshared/locked negatives; embedded-auth fidelity gap (REST-FIDELITY-EVAL — OPA/security excluded).
- **Contract:** ACL gated on sharing flag. Industry ref: identity-bound metadata (consumer of table identity).
- **S(F):** sharing flag in policies + ACL store keyed by table identity. **D(F):** REVOKE/sharing-off (intended).
- **Understanding checklist:**
  - [x] Grant/unshared negative; share+grant accepted (Phase 22); enforcement untestable embedded (fidelity gap, documented)
  - [ ] Grants × rename: keyed by name or UUID? (feeds F25 — predicted name-keyed → orphaned grants 🎯)
  - [ ] Grants × RTAS: sharing lives in the wiped policies blob (G10) — do grants survive pointing at a now-unshared table (inconsistent state 🎯)?
  - [ ] Database-scoped grants — surface untested (SHOW GRANTS, REVOKE listed as follow-ups)
- **Predicted hazards:** inbound: F25 🎯, F6/G10 🎯. Test only read-back consistency (enforcement needs Docker/OPA — out of scope, documented).
- **Test gate:** grants read-back across rename + RTAS; REVOKE/SHOW GRANTS surface. ~4 cases.

### F20 — Policy: replication (+ replica commit path)
- **Surface:** `SET/UNSET POLICY (REPLICATION=…)` (✅), RTAS⊕replication guard (✅), interval validator (🐛 500 finding), tableType immutability (✅); repo-layer: replica commit `skipEligibilityCheck` (G3/G7), verbatim snapshot copy, external mover contract.
- **Contract:** primary→replica snapshot-walk copy. Industry ref: (B)-walk — internally consistent (G1 withdrawn); the risk is the all-or-nothing eligibility bypass (G3/G7).
- **S(F):** source snapshot lineage + source-region absolute file paths on the replica. **D(F):** none in OSS (mover external).
- **Understanding checklist:**
  - [x] Mover external; walk mechanism; G1 withdrawn; G3/G7 filed (code recon)
  - [ ] Repo-layer probes from Phase 23 (replica commit retains type, verbatim snapshots, intermediate-schema replay) — still open, needs control-plane invocation
  - [ ] Source-side ORF/expiration vs replica's copied refs: the walk covers expiration-as-snapshot, but does it cover **orphan file deletion** (not a snapshot!) — predicted uncovered 🎯 (dangling replica file refs — the surviving sibling of withdrawn G1)
- **Predicted hazards:** ORF × replica 🎯 (recon before test — may be out-of-scope external); G7 (replica path can rewrite policies/lock — repo-layer probe).
- **Test gate:** repo-layer probes ~4 cases (gated on control-plane harness lift, already partially built via Rest shim).

### F21 — Policy JOBS plane (cross-cutting)
- **Surface:** `JobsScheduler` + per-table task selection; apps: Retention, SnapshotsExpiration, OrphanFilesDeletion(+Batched,+TableDirectory), StagedFilesDeletion, DataCompaction, TableStats/SortStats/DataLayoutStrategy (advisory); `Operations.java` shared plumbing; job defaults.
- **Contract:** ops autopilot. **This is the destroyer-orchestrator: every D(O) in this plan runs unattended on its schedule.** Industry ref: none of A–D run destroyers automatically without consumer awareness — this is OpenHouse's most differentiated risk surface.
- **S(F):** policies + table metadata. **D(F):** union of D(F11), D(F12), D(F13), D(F17) — scheduled.
- **Understanding checklist:**
  - [ ] ❓ in-flight: lock × maintenance jobs — do jobs respect a locked table (predicted: CALL procedures commit via the same client → lock applies; but any job using repo-layer/skipEligibility paths would bypass 🎯 — G2-shaped question)
  - [ ] Job ordering/interleaving: expiration then ORF vs ORF then expiration (determines the G11 recovery window — F12 item)
  - [ ] Per-job defaults inventory (every threshold/age/TTL each app applies when unconfigured) — one recon pass over the apps' arg parsing; the 3-day TTL was found this way, there are likely siblings
  - [ ] Does any job write state that another job or the catalog consumes (stats/data-layout outputs — who reads them, what breaks if stale/wrong)?
- **Predicted hazards:** everything in §4's job rows; the plane multiplies every destroyer by "and it happens by itself".
- **Test gate:** lock×job probe; defaults inventory is recon-only. ~3 cases.

### F22 — Table lock
- **Surface:** REST `POST/DELETE /lock`; `LOCKED_TABLE_OPERATION` on update/rename/grant paths; LOCK_ADMIN gating (untestable embedded).
- **Contract:** freeze all mutation. Tier today: **BROKEN** — replace path bypasses (G2, live).
- **S(F):** lockState in HTS. **D(F):** destroys *writability* (deliberately — the guard IS a destroyer of the write surface; its own battery asks "is the freeze total?").
- **Understanding checklist:**
  - [x] Enforcement on UPDATE ✅; G2 bypass ✅ live
  - [ ] Freeze totality sweep: which of {DML, DDL each-kind, policy DDL, branch write, WAP publish, maintenance CALLs, jobs (❓ in-flight), register_table, rename} does the lock actually block? Enumerate from `isTableLocked` call sites — predicted more G2-shaped holes (any path not routed through the update branch)
- **Predicted hazards:** each unrouted path is a bypass 🎯 (G2 pattern).
- **Test gate:** lock-totality battery (one op per commit path, not per op). ~6 cases.

### F23 — Column tags (PII/HC)
- **Surface:** `ALTER TABLE … MODIFY COLUMN c SET TAG=(PII|HC)`; read-back; no masking (✅ asserted).
- **Contract:** metadata annotation for external governance. Consumer of schema identity + policies plane.
- **Understanding checklist:**
  - [x] Round-trip + reads-unaffected (Phase 22)
  - [ ] ❓ in-flight: tags × RTAS (predicted wiped with the policies plane — G10 sibling 🎯)
  - [ ] Tags × rejected-rename / silent-rename-no-op: what happens to a tag if rename ever lands (dormant, pin-gated); tags on a column dropped via G9-RTAS (orphaned tag?)
- **Test gate:** tag survival across RTAS (folded into F6 battery). ~1 case.

### F24 — Namespaces / databases
- **Surface:** implicit db-on-create; SHOW DATABASES/TABLES; CREATE/DROP/ALTER/DESCRIBE NAMESPACE rejected (pinned, wrong-verb message finding).
- **Contract:** implicit namespaces. TOTAL/static — no destroyer intersects (no namespace state to destroy beyond tables themselves).
- **Understanding checklist:** [x] complete (Phase 19). Dormant pins gate any future namespace lifecycle.
- **Test gate:** none. ~0 cases.

### F25 — Rename table
- **Surface:** `ALTER TABLE … RENAME TO` same-db (✅), conflict 409 (✅), cross-catalog rejected; HTS identity row update.
- **Contract:** identity move preserving everything else. Industry ref: should be TOTAL (UUID-keyed); every **name-keyed** binding is a hazard.
- **S(F):** table identity row. **D(F):** destroys the **name binding** — consumed by: grants (❓), policies/jobs scheduling (job selection is by table listing — probably fine), streaming checkpoints (checkpoint stores table name/path? ❓), external refs, file-system path (does rename move the directory? → F12 orphan-dir job question).
- **Understanding checklist:**
  - [x] Basic rename + conflict
  - [ ] ❓ in-flight: rename × consumers recon — enumerate every name-keyed vs UUID-keyed store (ACL, policies, HTS, jobs bookkeeping, data path)
  - [ ] Does the data directory move? If not: old-name path + `OrphanTableDirectoryDeletionSparkApp` 🎯 (job sees a directory with no matching table → deletes live data — the unrecoverable-loss shape)
  - [ ] Streaming checkpoint referencing the old name — resume behavior
- **Predicted hazards:** rename→orphan-dir-job 🎯 is the sleeper (F12 intersection); grants orphaning 🎯 (F19).
- **Test gate:** rename then {grants read, job-selection, checkpoint resume, dir-location assert}. ~4 cases.

### F26 — Register / import procedures
- **Surface:** `register_table` (✅ works — pinned, surprisingly), `snapshot`, `migrate`, `add_files`.
- **Contract question (open):** do imports flow through managed-create (defaults, UUID stamping, policy init, HTS row) or bypass it? Bypass = G-class hole in the managed contract (AUDIT-FINDINGS "Observation").
- **Understanding checklist:**
  - [ ] Trace `register_table` server-side: create-time defaulting? tableUUID? policies init? HTS bookkeeping? (queued in AUDIT-FINDINGS — the recon IS the feature analysis)
  - [ ] Probe `snapshot` / `migrate` / `add_files` reachability (may be rejected — then pin)
  - [ ] A registered table × the jobs plane: does the scheduler pick it up (no HTS row → invisible to jobs → no expiration ever → unbounded metadata? or crash?)
- **Predicted hazards:** registered tables as second-class citizens 🎯: outside the policy/jobs/ACL contract while looking identical in SQL — a governance modality (tables partition into managed vs smuggled).
- **Test gate:** post-register CB-GOV + job-selection probe. ~4 cases.

### F27 — Encryption — DEFERRED
- OSS un-wired (plaintext; plugin private). Tagged SKIP asserting intended behavior; plugin contract documented (DDL-TEST-PLAN 24b). No analysis possible without an implementation to analyze. ~0 cases.

### F28 — Soft-delete / undrop — DEFERRED
- Embedded repo is a @Primary stub AND public DELETE hard-codes `purge=true` (product finding, AUDIT C). Analysis complete; testing blocked on the SpringH2HtsApplication restructure (REST-FIDELITY-EVAL addendum). ~0 cases until unblocked.

### F29 — Concurrency / commit protocol (cross-cutting)
- **Surface:** CAS on base metadata version; client retry policy; the metadata-vs-snapshot commit split; server subtractive merge (`doCommit:314-354`); COMMIT_KEY/SNAPSHOTS_JSON payload contract; `surface.conc.{appendAppend,updateUpdate,rtasVsAppend}` ✅ smoke.
- **Contract:** optimistic concurrency; loser retries or fails typed. Industry ref: standard OCC (A-adjacent); the fork-specific risk is the **split** — metadata and snapshots ride different payload keys, and the subtractive merge trusts the payload's ref/snapshot list.
- **S(F):** base version token. **D(F):** a concurrent winner destroys the loser's base assumption (intended); the subtractive merge lets a *stale but well-formed* payload **remove** refs/snapshots it never knew about (G5's mechanism; G11-P5 held only because clients always send the full list).
- **Understanding checklist:**
  - [x] P5 conditional hold (CAS active because clients send COMMIT_KEY+snapshots) — bytecode
  - [x] 3 conc smokes green
  - [ ] Retry semantics per op class: which Spark ops auto-retry on CommitFailedException vs surface it (write vs DDL vs procedures)?
  - [ ] Concurrent branch-write × main-write (disjoint refs — should commute; does the split make them conflict?); concurrent DDL × DML; concurrent expire × write
  - [ ] The stale-payload subtraction hazard (G5): can a delayed commit built on old metadata silently drop a ref created in between? (recon the merge keying, then one adversarial test)
- **Predicted hazards:** ref-drop via stale subtractive merge 🎯 (silent-destruction class); cross-ref false conflicts ❓.
- **Test gate:** ~5 cases (branch×main concurrency, expire×write, stale-subtraction adversarial).

---

## 4. Destroyer × consumer master matrix

Rows = destroyers, columns = consumer battery. Marks: **✅** tested (case id; `✅G#` = tested,
known-broken, finding filed) · **🎯** predicted-break (reason) · **❓** unknown → recon item ·
**⛔** structurally commuting (why). *Honesty note: the ST and CDC columns are almost entirely
❓/🎯 — that is the point of this plan.*

Columns: **W** DML write · **R** read · **TT** time travel · **RS** restore · **BW** branch write ·
**BM** branch merge/WAP publish · **ST** streaming resume · **CDC** changelog · **MX** maintenance ·
**GOV** grants/tags/policies read.

| Destroyer ↓ | W | R | TT | RS | BW | BM | ST | CDC | MX | GOV |
|---|---|---|---|---|---|---|---|---|---|---|
| **Expiration** (CALL/job, 3d default) | ⛔ head kept | ✅ `maintenance.expireSnapshots` | ✅ window (err tier ❓) | ✅ `interact.restore.expireAfterRollback` | ✅ `interact.branch.expireProtectsRefs` | ✅**G11** spurious reject + silent cherry-pick loss + staged-WAP loss | 🎯 checkpoint snapshot expired (❓ in-flight) | 🎯 range punctured (❓ in-flight) | ⛔ ops on live meta | ⛔ separate plane |
| **Orphan removal** (CALL + 4 jobs) | ⛔ if walk complete ❓ | ✅ `maintenance.removeOrphanFiles` | ❓ post-expire chain kills G11 copy-out recovery 🎯 | ❓ | ❓ staged/branch files in walk? | 🎯 closes G11 recovery window | ❓ | ❓ | ⛔ | ⛔ |
| **Compaction: data** | ✅ `maintenance.rewriteDataFiles` | ✅ `interact.maint.compactEvolved` | ⛔ old snaps keep old files | ⛔ | ⛔ snapshot-based | ⛔ | 🎯 replace-snapshot replay/dupes | 🎯 false CDC events | ✅ smoke | ⛔ |
| **Compaction: manifests** | ✅ `surface.proc.rewriteManifests` | ✅ | ⛔ | ⛔ | ⛔ | ⛔ | ❓ | ❓ | ⛔ | ⛔ |
| **Compaction: pos-deletes** | ✅ `surface.proc.rewritePositionDeletes` | ✅ | ⛔ | ⛔ | ❓ MoR branch | ⛔ | ❓ | 🎯 delete-event fidelity | ⛔ | ⛔ |
| **RTAS** | ✅ `interact.rtas.writeAfter` | ✅ | ✅ `interact.rtas.historyPreserved` | ✅ typed reject + `setCurrentRecovery` | ✅ `interact.rtas.withBranch` | ❓ merge across lineage break | 🎯 checkpoint in old lineage | 🎯 range spans lineage break | ⛔ (audit ⛔⁵) | ✅**G10** policies wiped; tags ❓ in-flight; grants 🎯 |
| **DDL: ADD COLUMN / widen** | ✅partial — arity breaks writers (`prep.evolved` exclusions, `insert.explicitColumns` 🐛; explicit-col ❓ in-flight) | ✅ | ✅ `interact.ddl.ttAfterAddColumn` | ✅ `interact.ddl.restoreAfterAddColumn` (schema kept) | ✅**G8-mirror** `interact.branch.mainDdlImmediate` | ❓ merge after divergent-era schema | 🎯 mid-stream schema drift | ❓ schema boundary in range | ✅ `interact.maint.compactEvolved` | ⛔ |
| **DDL: flag toggles** (wap/replace/MoR off-on) | ✅ `interact.mor.alterToMor` | ⛔ | ⛔ | ⛔ | ⛔ | ✅**G4** `branch.wapToggle.noGuard` (staged stranded) | ❓ | ❓ | ⛔ | ⛔ |
| **Rename table** | ✅ `ddl.renameTable` | ✅ | ❓ | ❓ | ❓ | ❓ | 🎯 name-keyed checkpoint | ❓ | 🎯 orphan-dir job vs old path | 🎯 name-keyed grants/policies (❓ in-flight) |
| **Rollback / set_current** | ✅ write-after | ✅ | ⛔ until expire (chain ✅) | ✅ | ✅ `interact.branch.rollbackWhileWapConf` | ❓ | 🎯 checkpoint ahead of HEAD | 🎯 negative progress | ✅ expire chain | ⛔ |
| **Retention job** (row TTL) | ⛔ normal deletes | ⛔ | ⛔ TT-recoverable until expire | ⛔ | 🎯 main-only → branch divergence (❓ in-flight) | ❓ | ❓ | ❓ policy deletes as CDC events | ⛔ | ✅**G10** corollary: wipe → 3d default 🎯 |
| **Lock** | ✅ `control.lock.enforcement` | ⛔ reads pass | ⛔ | ❓ blocked? | ❓ | ❓ publish blocked? | ⛔ | ⛔ | ❓ jobs respect lock? (in-flight) | ✅ grant-on-locked N |
| **Concurrent commit** | ✅ `surface.conc.*` ×3 | ⛔ | ⛔ | ❓ | ❓ branch×main commute? | ❓ | ❓ | ❓ | ❓ expire×write | 🎯 stale subtractive merge drops refs (G5) |
| **Register/import** | ❓ post-register writes | ✅ pin | ❓ | ❓ | ❓ | ❓ | ❓ | ❓ | 🎯 invisible to jobs | 🎯 no policy/UUID init |

Cell count honesty: ~35 ✅ (incl. 4 known-broken G-findings), ~25 🎯, ~55 ❓, ~45 ⛔. The plan's
job is to convert 🎯/❓ → classified, at the §3 rate of a handful of cases per feature, not by
matrix-filling brute force (most ❓ collapse in bulk once one recon question answers a whole row).

---

## 5. Prioritized execution order

Score = predicted-break density × production likelihood × silent-failure severity.
Severity ordering: **silent loss (G11-class) > spurious error > honest error.**

| Rank | Understanding checklist | Why now | Class |
|---|---|---|---|
| 1 | **F15 Streaming** (× expiration first) | Highest-probability undiscovered G11-class: 3-day default TTL × any paused stream; silent skip = data loss; zero chain coverage; in-flight recon lands here | silent loss |
| 2 | **F16 CDC** (× expiration, × compaction) | Same window, batch-shaped; false events from compaction are silent corruption of downstream consumers | silent loss |
| 3 | **F12 Orphan removal** (reachability walk + G11-recovery-window chain + rename×orphan-dir) | Only destroyer with **unrecoverable** mistakes; closes G11's sole recovery path; the rename→dir-job hazard is a live-data-deletion shape | silent, unrecoverable |
| 4 | **F21 Jobs plane** (defaults inventory + lock×jobs) | Multiplies every destroyer by "automatic"; the defaults inventory is cheap recon with outsized predictive yield (the 3-day TTL was one grep away) | amplifier |
| 5 | **F6 RTAS battery completion** (tags/grants/sort/ST/CDC) | The replace-path hole cluster keeps paying (G2→G9→G10→predicted tags/grants); completing its GOV column closes the plane | silent governance loss |
| 6 | **F22 Lock totality** + **F29 concurrency** | G2 pattern says unrouted paths exist; stale-subtraction is a silent ref-loss shape | spurious + silent |
| 7 | **F25 Rename** + **F19 grants** consumers | Identity-binding recon (in-flight) — likely orphaned grants/policies; the orphan-dir intersection is rank-3's twin | silent |
| 8 | **F17 Retention job × branches** + **F26 register contract** | Governance modality (managed vs smuggled tables; policy divergence on branches) | silent divergence |
| 9 | **F2/F5/F9/F10/F13 residuals** | Bounded singles (explicit-col writer compat, delete-after-commit, TT error grading, rollback_to_timestamp, rewrite options) | mixed |
| 10 | F20 repo-layer, F14 folds | Gated on control-plane lift / folded into other batteries | — |

Ranks 1–4 before anything else: they are the cells where a production defect is **currently
invisible to every test in the suite** and would present as silent data loss.

---

## 6. Effort estimate

Recon-hours = code/bytecode reading + probe runs to complete §3 checklists (the gate); cases =
new harness cases after the gate opens. Baseline: ~0.73s/case marginal, ~120s fixed (cached-cp
slice ~40s); current suite 1,231 cases ≈ 15 min. **Budget ceiling: full suite ≤ ~1h ⇒ ≤ ~4,900
cases total ⇒ ~3,600 case headroom.** This plan spends ~90 — the constraint is recon hours, not
runtime. Exception: streaming cases run ~10–30s each (Spark trigger latency), so F15's 6 cases
cost like ~150 ordinary ones — still trivial against the ceiling.

| Feature block | Recon h | New cases | Notes |
|---|---|---|---|
| F15 streaming | 3 | 6 | fork option surface + 5-destroyer resume battery |
| F16 CDC | 2 | 5 | shares fixtures with F15 |
| F12 orphan | 3 | 5 | reachability walk read is the bulk |
| F21 jobs plane | 2 | 3 | defaults inventory is grep-scale; lock×jobs probe |
| F6 RTAS completion | 1 | 7 | mechanism known; battery mechanical |
| F22 lock totality | 1.5 | 6 | call-site enumeration then one case per commit path |
| F29 concurrency | 2.5 | 5 | stale-subtraction adversarial needs payload crafting |
| F25 rename + F19 | 2 | 8 | identity-binding store enumeration |
| F17 retention job + F26 register | 2.5 | 8 | in-process job invocation plumbing (~half the recon) |
| F2/F3/F5/F7/F8/F9/F10/F13/F18/F23 residuals | 3 | 32 | bounded singles listed per block |
| **Total** | **~22.5 h** | **~85** | suite → ~1,320 cases ≈ 17 min — far under the 1h ceiling |

Deferred (no estimate until unblocked): F27 encryption (private plugin), F28 undrop (HTS
restructure), F20 repo-layer beyond the Rest shim, Docker/OPA auth enforcement.

**Standing rule:** every case added under this plan carries its §1.4 PREDICT record (expected
tier + expected failure mode) in the case comment. A red case with a correct prediction is a
finding; a red case with a wrong prediction reopens the feature's UNDERSTAND gate.
