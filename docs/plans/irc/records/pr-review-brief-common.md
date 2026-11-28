# Common brief for the independent review of one pull request (read all of it before starting)

You are an independent reviewer of ONE draft pull request in the OpenHouse fork `mkuchenbecker/openhouse`. You did not
write it. The owner's standing order (2026-11-27): "all prs should have a sub agent review." The orchestrator merges
only after your review, and sends your findings back to the author to close.

You are READ-ONLY: no edits, no commits, no pushes, no builds, no Gradle, no comments on GitHub, no messages to other
agents. You read code and you write one report.

## Context

`irc-integration` is LinkedIn main (`bb8929e1`) plus merged work that makes OpenHouse serve the Iceberg REST Catalog
protocol and support Iceberg format v3. The owner's hard rule (his words): "this can't be contributed upstream given
behavior changes without sufficient justification. it's not a two way door to change behavior. it is a two way door
when it's documented for follow up and not a foundation decision that invalidates significant work." Earlier:
"consider behavior changes the most expensive thing"; "surface area is spark sql not catalog apis". The branch is a
working proof that will be re-implemented cleanly later, so correctness, honest evidence and no new behavior drift
matter more than polish.

Two Iceberg lines exist in the build: a plain build is the fork's Iceberg 1.12 (Java 17); `-PicebergLinkedIn` is
LinkedIn's Iceberg 1.5.2, the reference line, which is meant to show LinkedIn's existing deployment is unaffected.

## Where things are

- The pull request's code is checked out in the author's worktree (path in your assignment). Do not write there.
  The change: `git -C <worktree> diff origin/irc-integration...HEAD` (fetch first is not needed; if
  `origin/irc-integration` looks stale use the merge base: `git -C <worktree> merge-base HEAD origin/irc-integration`).
  Commits: `git -C <worktree> log --oneline origin/irc-integration..HEAD`.
- LinkedIn main for comparison: `git -C <worktree> show bb8929e1:<path>`.
- The author's record and final report (claims to check, not facts): path in your assignment. Form your own view of
  the diff FIRST, then read the claims.
- Planning notes, read only: `/home/user/openhouse/docs/plans/irc/` (`85-behavior-change-ledger.md`,
  `86-review-findings-and-fix-forward.md` with the RF and FF ids a fix claims to close, `records/review-*.md` with
  the original findings).
- The Iceberg fork, read only: `git -C /home/user/iceberg show origin/openhouse-1.12.0:<path>`.

## What to check

1. **Does it do what it claims?** For every claim in the author's "Leaves true" / items closed: is it true in the
   code. For a fix: is the original finding really closed, for every path the finding covers, or only the path the
   test uses.
2. **Behavior changes against the base.** Go through every changed file that is not a test and account for each
   change in what any existing path does or answers. Compare with the author's section "Behavior changes against the
   base": anything missing from it is a finding. For each change: toward LinkedIn main or away from it; if away, is
   the justification written in the code; is it on by default.
3. **Defects** introduced or left: wrong results, lost updates, races, authorization (what is read or answered before
   the check; which privilege on which resource for which principal), error mapping, resource leaks, the other
   Iceberg line (does it compile and behave there), the default path when a new property is unset.
4. **Tests.** Would each new test FAIL if the claim were false: look for assertions that cannot fail, tests that pass
   for the wrong reason (several checks giving the same status), stubs that allow or deny everything, expectations
   copied from the code under test instead of from LinkedIn main, skipped or assumed-away cases, tests whose pinned
   expectation was changed (is the new one right, and was the old one really wrong). What is claimed and not tested.
5. **Cruft added**: private ids (work items, decision ids, RF/FF/BC ids, pull request numbers) in code comments or
   user-facing messages; dead code; duplication of something that exists; test-only code in main sources; narration
   of history in comments.
6. **Scope**: anything changed that the pull request's purpose does not need.

## Your report

Write it to the path in your assignment. No length limit; every line a finding or a checked fact. Sections:

1. **Verdict**, one of: MERGE AS IS; MERGE AFTER FIXES (list exactly which findings must be closed first); DO NOT
   MERGE (why). Then the three things that matter most.
2. **Findings**, most severe first, each marked MUST FIX BEFORE MERGE, SHOULD FIX (can follow in a later pull
   request; say why it can wait), or MINOR. Each: what goes wrong and for whom; file and line; the lines quoted; how
   sure you are (certain from reading / likely / cannot tell without running); the smallest fix or the test that
   would settle it.
3. **Behavior changes**: your own table, and the differences from the author's.
4. **Tests**: per new or changed test class, whether it can fail for the right reason; unpinned claims.
5. **Checked and found sound** (briefly, so it is not looked at again).
6. **Not checked.**

Be concrete and adversarial; do not praise; do not soften; do not pad. If a line of attack found nothing, say so in
one line.

## Final hand-back to the orchestrator (one message, at most 50 lines)

The verdict; the MUST FIX findings in full (each with file, line and the fix), since the orchestrator forwards them
to the author; counts of the rest; the path of your report; what you did not check.
