# CHECKLIST — 2026-07-16 — ORC coverage (D6)

New file per workstream. Re-read first each turn. Never rewrite a checklist file.

## RATIONALE / CONTEXT — WHY ORC coverage was requested
(Origin: your D6 answer, in the same message that set the fork-audit goal.)
- Immediate: correct my parquet-only pruning. I had wrongly treated file format as a "vacuity axis"
  and pruned ORC off many blocks (branch/undrop/RTAS/merge/maintenance), so I was effectively testing
  ONLY Parquet there. Your correction: strictly ADDITIVE — add ORC; keep existing coverage.
- Deeper why — **MY INFERENCE, NEEDS YOUR CONFIRMATION (do not treat as closed):** format is
  correctness-relevant. ORC vs Parquet differ in DATA and DELETE-FILE encoding, and the fork carries
  format-sensitive custom commits (#229 delete-file replication, #219 delete-file replication factor,
  #189/#233 compaction). A format-specific correctness bug in the fork would be MASKED if only Parquet
  is tested. This ties to your stated goal: surface the "sneaky v3 backport that risks correctness."
  NOTE: the identified v3 backport (column defaults #251) is schema-metadata = format-AGNOSTIC, so ORC
  is NOT how you catch #251 specifically — ORC catches format-sensitive DATA/DELETE-path issues. If
  your actual why is different, tell me so I aim the coverage right.

## RULE (your D6 lesson)
- Never silently skip/prune coverage. Defer loudly: write the test, tag low-quality, bring it here.
- ADDITIVE only: never remove existing Avro coverage. ORC+Parquet everywhere; the 3-format blocks keep
  parquet+orc+avro.

## STEPS
0. [x] Persist this checklist (new file).
1. [ ] CONFIRM the rationale above with the user before finalizing WHERE ORC is added (targeted at
       format-sensitive paths vs blanket doubling). ← BLOCKED on user.
2. [ ] Working tree already has: Avro restored (6 sites) + ORC added to branch/undrop/RTAS/RTAS-MoR/
       maintenance-MoR/hazard-MoR/MoR-merge/DDL-consumer. Reconcile against the confirmed rationale —
       is blanket doubling right, or should ORC be concentrated on the format-sensitive (delete/data)
       blocks and NOT the metadata-routed (branch/undrop) ones?
3. [ ] Verify: compile + full both-mode run, 0 failures.
4. [ ] Commit + push; record run.

## STATUS: BLOCKED on step 1 (confirm rationale → aim the coverage).
