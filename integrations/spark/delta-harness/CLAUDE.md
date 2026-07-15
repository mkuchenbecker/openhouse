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
- No vacuous or stupid tests. When an estimate is inflated by vacuous cells, correct it in the open
  rather than chasing the number. Prune vacuous crosses (e.g. format is vacuous for metadata/ref
  reconstruction; delete-free MoR reads == CoW).
- The goal is to find BROKEN feature interactions, not just to rack up green cases. Rejections are
  behavior PINS (tripwires), not contracts — if OpenHouse later supports X, the pinned test should
  flip and be updated, not silently pass.

## Mechanics
- Run under JDK 17 (`JAVA17_HOME=/usr/lib/jvm/java-17-openjdk-amd64`); Lombok breaks on 21.
- Iterate on single-id / narrow slices; run the full suite only as the final gate.
- `HARNESS_REAL_HTS=1` boots the real embedded House Table Service (undrop leg). Default = in-memory stub.
- Git hooks are broken here — use `git commit --no-verify` / `git push --no-verify`.
