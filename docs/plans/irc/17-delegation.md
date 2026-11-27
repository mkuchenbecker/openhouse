# Who does the work

Bottom line: Claude orchestrates and reviews every item in `04-work.md` before it is called done. This
file says which kind of agent takes which item. It moved out of `04-work.md` to keep that file under the
length limit.

Delegation by difficulty:

- Smallest capable agent: P2, I2, T2, and all inventories and verification runs.
- Mid-tier agent: T1, T3, T4, I3 to I5, V2, V4, V6, V7, V10, X5.
- Most capable agent, or Claude directly: C1, C2, V1, V3, V5, V8, V9, V11, V12, N1 to N3, X1 to X4, X6, X7.

## Hazard: the shared stash stack, dated 2026-11-23

Bottom line: every brief must wrap `git commit` and `git push` in a shared lock, because the repository's hooks use
a stash stack that all worktrees share.

- The pre-commit and pre-push hooks run `git stash -q --keep-index` and `git stash pop`. The stash stack is shared by
  every worktree of the repository, and the hook creates an entry even when only staged changes exist.
- On 2026-11-23 two agents committing within the same half minute swapped entries. Nothing was lost, verified byte
  for byte.
- Rule from now on, written into every brief: wrap every `git commit` and `git push` in
  `flock /tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/git-hook-stash.lock ...`. Check
  that `git stash list` is empty before and after. Never use a bare `git stash`. Report foreign entries instead of
  touching them.
- The hook itself is not edited, and `--no-verify` is never used.

## Brief rule from the Iceberg 1.12 default, dated 2026-11-23

Bottom line: every brief must now say that no property is the Iceberg 1.12 line.

- Pull request 118 made a plain build the fork's Iceberg 1.12 line (`54-v9e-flip-result.md`).
- `-PicebergLinkedIn` selects LinkedIn's Iceberg 1.5.2 line, the reference line. `-PicebergFork` does nothing.
- The Iceberg 1.2 fixtures project and the Spark 3.1 projects no longer exist; a brief must not name them.

## Standing orders from 2026-11-27

Bottom line: four orders now bind every brief. They follow the owner's answer to the self review (`01-stated.md` S58, S60,
S61; `84-self-review.md`, last section).

- (a) Every pull request gets an independent sub-agent review before merge.
- (b) Every pull request body and merge message has a section listing behavior changes against the base, or says none.
- (c) Existing behavior is not changed without justification written where a reviewer will find it, and each such change
  is entered in the behavior-change ledger.
- (d) Each agent's record is copied into `records/` in the notes commit for its work.
- The 150-line limit on notes is lifted (S61); a brief must not impose one.
