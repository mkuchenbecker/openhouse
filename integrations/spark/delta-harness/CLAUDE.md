# delta-harness — working rules (persistent memory)

These are standing directives for anyone (esp. Claude) working in this harness. They were set by the
repo owner and must persist across sessions.

## Autonomy / when to stop
- **Do NOT stop while there are actionable tasks that do not require user input.** If work remains on
  the checklist (or is implied by the agreed scope) and it can be done without a decision only the
  user can make, keep going — build, run, verify, commit, push, then continue to the next item. A
  finished slice is not a reason to halt; it is a cue to start the next one.
- Only stop for: (a) a genuine decision that needs the user (ambiguous requirement, destructive or
  outward-facing action, a real fork in approach), (b) a hard blocker you cannot clear yourself, or
  (c) the entire agreed scope is actually complete.
- Merging/closing PRs is the user's call — never merge without explicit approval — but that gate does
  NOT license stopping the *build* work; keep making the branch better until told otherwise.

## Checklist discipline
- **No build/task work without a live checklist** (TaskCreate), full stop — even a single-item list.
- **Every status reply LEADS with built-vs-estimate**, not the latest slice's green count.
- **Never stop without a global status update** covering the full scope (done / in-flight / remaining).

## Test quality
- **Format policy is ADDITIVE: every test covers at least ORC + Parquet.** Blocks that were
  parquet-only get ORC added. This is a STRICTLY ADDITIVE correction — do NOT remove existing Avro
  coverage. The 3-format blocks (core layouts, morVerify, cowVerify, nested, types) keep parquet+orc+
  avro. "Avro is not needed" meant do-not-also-add-Avro to the newly-ORC'd blocks, NOT delete Avro.
  (Hard lesson: I misread this as "drop Avro" and destroyed prior coverage — never turn an additive
  request into a destructive change.)
- **Never silently skip or prune coverage.** It is fine to DEFER, but not silently: write the test,
  tag it low-quality/deferred, and BRING IT HERE for a decision. Proceed without blocking on the
  answer, but the decision must be surfaced explicitly in chat — never made silently in a doc. (Strong
  lesson from the user.)
- Approvals: the user is NOT reading markdown. A decision is closed ONLY by the user's explicit opinion
  in chat (or a +1 on the PR). Writing it in a doc or getting no pushback does NOT close it. Enumerate
  open decisions in chat.
- No vacuous or stupid tests. When an estimate is inflated by vacuous cells, correct it in the open.
  (But format is NOT a vacuity axis to prune away — see the ORC+Parquet policy above.)
- The goal is to find BROKEN feature interactions, not just to rack up green cases. Rejections are
  behavior PINS (tripwires), not contracts — if OpenHouse later supports X, the pinned test should
  flip and be updated, not silently pass.

## PR discipline
- Work ONLY on the current stacked PR (one above the previous). NEVER touch the parent PR (#9) — other
  agents reference it. Do not open new PRs. Persist all working knowledge in the PR so any agent can
  bootstrap from it later.

## Role
- Currently the TESTING + UNDERSTANDING silo for OpenHouse AND the iceberg fork — not the master agent
  (the user will say when). The master plan is orthogonal to testing the iceberg surface on OpenHouse.
  Sequence the user set: bootstrap tests → fix → modify. For now: document findings, don't fix
  production code.

## Mechanics
- Run under JDK 17 (`JAVA17_HOME=/usr/lib/jvm/java-17-openjdk-amd64`); Lombok breaks on 21.
- Iterate on single-id / narrow slices; run the full suite only as the final gate.
- `HARNESS_REAL_HTS=1` boots the real embedded House Table Service (undrop leg). Default = in-memory stub.
- Git hooks are broken here — use `git commit --no-verify` / `git push --no-verify`.
