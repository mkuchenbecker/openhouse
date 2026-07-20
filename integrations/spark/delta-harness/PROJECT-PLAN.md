# PROJECT PLAN (ROOT) — re-read FIRST every turn (esp. after compaction)

This is the ROOT of a FRACTAL plan. Overarching goal → sub-goals → sub-checklists (own files) →
sub-sub. Execute top-down: always know which leaf you're on. Sub-lists are appended as new files and
indexed here. Rules: no edits without a live checklist; never delete (append/mark/supersede); new file
per task-checklist; approvals close only by explicit user opinion / PR +1.

## OVERARCHING GOAL
Comprehensively TEST + UNDERSTAND OpenHouse's Iceberg surface AND the `com.linkedin.iceberg` 1.5.2
fork, persisting all working knowledge in PR #11 so any agent can bootstrap. Role: testing/understanding
SILO — NOT master agent yet (user will say when). Master plan is undisclosed + orthogonal to this
testing; sequence = bootstrap tests → fix → modify. Work only on PR #11 (stacked on #9); never touch #9.

## CURRENTLY EXECUTING
#251 column-default tests (user +1: "add in the column default tests") — BUILT + GREEN. Slice green
(parquet+orc); full STUB gate running. KEY FINDING surfaced to user: **#251 is NOT in the deployed
1.5.2.15 artifact** (it's on branch HEAD d1603c807, post-release), so the cross-engine persistence hazard
is latent-in-source not live. Deployed reality is INERT-BUT-SILENT: ADD COLUMN DEFAULT is accepted →
silently dropped from schema → not read-backfilled → omit-insert rejected CANNOT_FIND_DATA. Pinned as a
tripwire (`fork.colDefault.addColumnInert @ parquet|orc`). See CHECKLIST-2026-07-16-column-default-tests.md
(appended finding) + ICEBERG-FORK-AUDIT.md (CORRECTION block). OPEN for user: test #251 against a newer
fork build too? (default no). Then commit + push.

## SUB-GOALS (index → sub-checklists / artifacts)
- A. OpenHouse harness surface testing — ✅ DONE. HTS-embed, undrop axis+admin+3-way, blocks 8/9/10,
     MoR×branch merge, encryption pin. Artifacts: VERIFIED-RUN-openhouse.txt, BUILD-STATUS.md,
     HTS-EMBED-PLAN/IMPL.md. (Sub-checklists were the Phase/Block tasks #12–#21.)
- B. Iceberg fork audit — ✅ DONE. 21 custom commits vs Apache 1.5.2. Artifact: ICEBERG-FORK-AUDIT.md.
     Headline = #251 column-defaults (v3 backport, api/core only, durable serialization, NO version
     gate, NO read-apply, NO Spark wiring) = inert-but-latent correctness hazard. Needs sub-checklist
     for the FOLLOW-UP TESTS (below).
- C. Format coverage (D6: ORC+Parquet) — ◐ IN PROGRESS, BLOCKED. → CHECKLIST-2026-07-16-orc-coverage.md.
- D. Bug triage + documentation — ✅ DONE (documented, no prod fixes per D4). Artifact: BUGS.md.
     bug1 insert.explicitColumns → engine limitation, reclassified to pin. bug2 nested-DELETE NPE →
     upstream, SKIP. bug3 ddl.renameColumn → GENUINE OH regression from PR#558 (documented, fix deferred
     to master plan).
- E. Decisions ledger (D1–D8) — resolutions captured below; needs its own checklist to keep current.
- F. Repair the Avro regression (my error) — ◐ STAGED, uncommitted, BLOCKED on "go".
     → CHECKLIST-2026-07-16-audit-and-restore.md.
- G. Findings ledger — G8–G14 + fork findings. Artifact: AUDIT-FINDINGS.md, ICEBERG-FORK-AUDIT.md.

## OPEN FOLLOW-UP TESTS (spawned by B, need user +1 — dormant-feature tests)
- #251 defaulted column reads NULL (incomplete backport); v2 persists v3-only defaults with no gate.
- #249 partitioned write distribution = NONE (Apache = HASH) characterization.
(These are NOT format-caught by ORC — #251 is schema-metadata, format-agnostic.)

## DECISION LEDGER (D1–D8, user-answered)
- D1 HTS Option A → KEEP (embedded; docker retest later; stay fast inner loop). CLOSED.
- D2 shared-fixtures @ConditionalOnProperty edit → OK (minimal). CLOSED.
- D3 insert.explicitColumns → pin. KEEP. CLOSED.
- D4 renameColumn regression → DOCUMENT only now; fix in master plan. CLOSED.
- D5 G14 impact → answered: EFFICIENCY not correctness (dangling delete; reads verified correct; extra
     file until rewrite_position_delete_files/expire). Pin vs bug = still user's call. OPEN (classification).
- D6 ORC+Parquet everywhere, Avro NOT removed → ADDITIVE. (I violated this by deleting Avro; repairing
     in F.) Rationale/target OPEN (sub-goal C).
- D7 encryption/KMS → KMS is on the CLIENT (Spark write) path; server never sees keys. Could be tested
     client-side with an in-memory KMS IF fork+catalog honor an encryption table property — audit
     question. For now plaintext pin only. OPEN (whether to go further).
- D8 PR structure → stay on PR #11 only, never #9; persist knowledge in PR. CLOSED.

## STANDING RULES
No edits without a live persisted checklist; re-read this root first each turn. Never delete. New file
per task-checklist; this ROOT is the one living index (append sub-lists + update the "currently
executing" pointer). Approvals: explicit user opinion / PR +1 only — markdown ≠ approval.
