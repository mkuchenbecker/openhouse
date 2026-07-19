# CHECKLIST — 2026-07-16 — forensic audit + Avro-restore/ORC-add, consistent commit

New file per checklist (never rewrite a checklist file). Supersedes the stray SESSION-CHECKLIST.md
(left in place; not deleted). Re-read this FIRST each turn.

Goal: (A) forensic audit — full history of what was done, what was LOST, rationale; (B) verify the
pending fix restores every loss and is consistent with the branch; (C) commit + push to repair remote.

0. [x] Persist THIS checklist as a new file.  ← done
1. [ ] Full git log of `claude/hts-embed-plan-k7drzg` since base `5cc3dd6`: each commit + diffstat + rationale.
2. [ ] File deletions/renames across the branch (`git log --diff-filter=DR --summary`).
3. [ ] Coverage/behaviour losses: SKIP additions, test reclassifications, case-count drops, format
       drops (the Avro deletion), removed ops/scenarios — committed history AND working tree.
4. [ ] Reconcile the uncommitted working tree (Avro restore + ORC add + CLAUDE.md) against the audit:
       confirm it restores ALL losses and adds nothing inconsistent.
5. [ ] Verify: compile + full both-mode run (stub + real-HTS), 0 failures.
6. [ ] Commit + push (only if 4 & 5 pass); record run.
7. [ ] Report the audit + result to the user.

## STANDING RULES
- No edits without a persisted checklist (new file each time); re-read first every turn.
- Never delete — append/mark/supersede. Any removal (file/task/coverage) is an explicit surfaced decision.
- Format policy ADDITIVE: ORC+Parquet everywhere; never remove existing Avro coverage.
- Approvals close only via explicit user chat opinion / PR +1. Work only on PR #11, never #9.
