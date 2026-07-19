# SESSION CHECKLIST — persistent anchor (re-read FIRST every turn, esp. after compaction)

RULE: no edits without a live checklist. Step 0 of every task is to persist the checklist here.
Reconcile the working tree against this file before any action.

## ACTIVE TASK: Forensic audit — full history of what was done, what was LOST, and rationale (for user audit)
0. [in progress] Persist this checklist (this file + TaskList).  ← doing now
1. [ ] Reconstruct full git history of branch `claude/hts-embed-plan-k7drzg` since branch point: every
       commit, diffstat, one-line rationale.
2. [ ] Find FILE deletions/renames across the branch (`git log --diff-filter=DR --summary`).
3. [ ] Find COVERAGE/BEHAVIOR losses: SKIP additions, test reclassifications (SKIP↔pin), case-count
       drops, format drops (the Avro deletion), any removed tests/ops/scenarios.
4. [ ] Cross-reference each change against the conversation's stated intent; flag divergences.
5. [ ] Produce the audit report: per-commit what+rationale + a definitive "WHAT WAS LOST" section.
6. [ ] Present to user. Do NOT commit the pending Avro-restore/ORC-add fix until user says go.

## PENDING (separate, NOT part of the audit — do not action without user go)
- Working tree has UNCOMMITTED fix: Avro restored to 3-format blocks + ORC added to parquet-only
  blocks + CLAUDE.md format-policy correction. Unverified. Remote still holds the broken Avro-deleted
  commit. Awaiting user go to verify → commit → push → refresh PR.

## STANDING RULES (do not violate)
- No edits without a live persisted checklist; re-read it first each turn.
- I don't DELETE — append/mark/supersede. Any removal (file, task, or test coverage) is an explicit
  surfaced decision, never a side effect.
- Approvals close only via explicit user chat opinion or PR +1. Markdown ≠ approval.
- Work only on PR #11 (stacked on #9). Never touch #9.
- Format policy is ADDITIVE: ORC+Parquet everywhere; never remove existing Avro coverage.
