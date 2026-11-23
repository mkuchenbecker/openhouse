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
