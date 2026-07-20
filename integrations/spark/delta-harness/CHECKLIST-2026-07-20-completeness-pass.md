# CHECKLIST — 2026-07-20 — completeness pass (post self-review)

New file per task. Re-read first each turn. Parent: PROJECT-PLAN.md. Source: 4-agent self-review of the
diff from main + user directives (2026-07-20).

## USER DIRECTIVES (verbatim intent)
- G2–G14 should have DOCUMENTATION (not fixes — test-only PR).
- Docs with smaller test counts were ACCURATE when written — NOT defects. We hunt MATERIAL defects /
  inaccuracies only. Consolidate to ONE set of documentation.
- register_table is OUT OF SCOPE. ADD OTHER TESTS (the untested fork commits).
- D6 = BLANKET DOUBLE (ORC on every block, not concentrated). → close orc-coverage.
- D5 (G14 classify) + D7 (encryption/KMS) — user lacks context to decide → LEAVE OPEN, keep pins.
- Budget is fine → RTAS full-cross + WAP/branching mega-axis are greenlit.

## WORK ITEMS
0. [x] Persist this checklist.
1. [ ] D6 blanket ORC: audit which blocks are still parquet-only; add ORC so every block ≥ parquet+orc.
       Close CHECKLIST-2026-07-16-orc-coverage.md (mark resolved: blanket-double, user-confirmed).
2. [ ] ADD fork-commit tests (the real "test the fork" gap). Priority order:
       (a) #249 partitioned write distribution = NONE — INSERT into partitioned table w/o
           write.distribution-mode → assert NONE (file-count vs a HASH baseline); DELETE/UPDATE still HASH.
       (b) #229/#219 delete-file replication (table + spark-sql prop + repl factor) — prop round-trip +
           behavior where observable.
       (c) #233 bin-pack weight by data-file length (compaction) — characterize output sizing.
       (d) #228 split-size SparkSQLProperty — read split behavior.
       (e) #234 stream-results for remove_orphan_files — OOM-avoidance path executes.
       (f) #189 compaction ordering — complete the "partial" coverage.
       Run under BOTH published + branch runtime where the behavior is fork-specific.
3. [ ] Build the greenlit mega phases (budget fine):
       (a) RTAS full-cross (DDL-TEST-PLAN Phase 28, ~+650).
       (b) WAP / branching mega-axis (Phase 29, full 3×).
4. [ ] Code cleanup (self-review B): remove dead HtsBootProbe.scala (ask? — flagged); drop/demote
       control.undrop.lifecycle dead case; strengthen weak pins (register_table is OUT — leave; but
       wapExpireRefTarget message-anchor). Keep it minimal.
5. [ ] G2–G14 documentation: ensure AUDIT-FINDINGS.md documents each (what, repro/evidence, status,
       attribution). Fix the AUDIT-FINDINGS:88 material contradiction (500 vs silently-accepted-daily).
6. [ ] Doc consolidation → ONE authoritative set. Pick the canonical doc(s); mark the rest HISTORICAL
       with a banner pointing to canonical. Fix MATERIAL inaccuracies only (not historical counts):
       - PROJECT-PLAN sub-goal F/C statuses (say BLOCKED but shipped) — material.
       - doc-13 "avro disabled" if presented as current — material.
       - AUDIT-FINDINGS:88 contradiction — material.
       - one source-of-truth for the current case count.
7. [ ] Full both-mode verify (published + branch), record run, commit + push, update PROJECT-PLAN.

## OPEN (left per user)
- D5 G14 classification — pin kept; awaiting context.
- D7 encryption/KMS — plaintext pin kept; awaiting context.

## PROGRESS
- [x] Self-review code fixes (branch-swap guard, readApplyProbe assert). Committed 95f4fef.
- [x] #249 partitioned write-distribution test (item 2a) — green both runtimes. Committed 2f32358.
- [x] Doc protocol codified in CLAUDE.md + `index.md` master map created; checklists bannered.
- [x] D6 CORRECTED to BLANKET-DOUBLE (owner: "difference between should-be-vacuous and is-vacuous").
      My earlier "multiplex format-sensitive only" was the exact error the harness catches — assuming an
      axis-independence rather than verifying it (cf. G8/G10; the fork has patched ORC paths). Rule now:
      ONLY structural vacuity (no table = no format axis) is pruned; predicted vacuity is TESTED. Mechanism:
      per-case `seedFmt` thread-local + `crossFmt` wrapper (low-churn; coreCreateParquet + 22 inline creates
      read $seedFmt). 13 table-creating blocks doubled (partitionTransforms/Evolution, branching, surface,
      hazards, interactions, negatives, ddlNegatives/Props/Misc/Policy/CtasRtas/TagAcl). STUB 2382/11/0, 0
      divergence — the "format-inert" hypothesis held (now verified). Commit dacfc81. Kept single: table-LESS
      control-plane @embedded/@core ops (lock/undrop-admin test server/REST layer, format not in that code
      path — defensible; overridable) + encryption plaintext pin (asserts Parquet PAR1, format-locked).
- [x] (superseded) earlier D6 stage note: format is now a PARAMETER,
      not a baked constant. Stage 1 = coreTwoSnapshots(fmt) + timeTravel/restore/maintenance multiplex
      parquet+orc (77ce4da). Stage 2 = cowCreate/morCreate(fmt) + readerWriter (CDC/incremental/streaming)
      multiplex (67f2ef6). DECISION (flagged in chat): multiplex the FORMAT-SENSITIVE blocks only. The
      remaining parquet-only blocks (branching, surface metadata, partitionTransforms/evolution, negatives,
      ddl schema/props/policy/acl/rejection) are format-INERT — their format-sensitive data behaviors are
      already covered by the 3-format DML/MoR/CoW layout matrix + maintenance(compaction)×orc +
      readerWriter(decode)×orc, so doubling them = vacuous cells (violates "no vacuous tests"). They remain
      un-bakeable (coreTwoSnapshots/cowCreate now take fmt) but single-format by design. Un-baking is the
      architectural fix; multiplex is applied where it is not vacuous. If owner wants literal doubling of
      the inert blocks too, that is a one-line-per-block change to iterate dataFormats.
- [x] Remaining fork tests 2b-2f (sub-agent, worktree; 8 cases green). Denormalized to ICEBERG-FORK-AUDIT
      (tested column). Commit 19e00e4.
- [ ] Mega phases (RTAS full-cross, WAP mega-axis) — budget approved.
- [x] G2–G14 documentation: already complete in AUDIT-FINDINGS.md (LIVING doc). Fixed the material
      AUDIT-FINDINGS:88 wording (code-verified: bad-count->500, bad-suffix->silent daily; not a contradiction).
- [ ] Doc consolidation per the new protocol (index-driven).

## STATUS: D6 blanket-format refactor (un-bake format from the parquet-only blocks).
