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

## STATUS: step 1 (D6 blanket ORC audit).
