# C6, T9: pull requests run the opt-in build; the kit's warehouse leaves the checkout path

Every fact is marked [ran] (I executed it and saw the result) or [read] (I read it in a file, log or API answer).

## Result

- Pull request 111, draft, into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/111 [ran]
- Head: `f5928310b9265573d160545dfb19b6856365e25d` on `irc/c6-optin-on-pull-requests` [ran]
- Commits: `93838335` (T9), `b4b7dbe3` (C6), `f5928310` (merge of pull request 110, `7e8941ab`, no conflict) [ran]
- The pull request's run 45126400214 started both jobs at 06:46:37Z: `build-run-tests / Build and Run Tests` and
  `iceberg-fork-build / Opt-in build against the Iceberg fork` [read, GitHub API]. Neither had finished when I stopped.

## C6: what changed

- New `.github/workflows/iceberg-fork-build.yml` (`on: workflow_call`), the opt-in job's steps moved verbatim, with two
  changes: a step that detects whether a pull request changes the pin or the publish script, and the save condition.
- `post-merge-build.yml` and `pr-validations.yml` each call it. No step exists twice.
- `docs/development/iceberg-fork.md`, section CI, rewritten.

### What each trigger runs

| Trigger | Default build | Opt-in build |
| --- | --- | --- |
| Pull request (opened, synchronize, reopened; edited only on a base change) | yes, as before | yes, new |
| Push to `irc-integration` | yes | yes (same steps, now called) |
| `workflow_dispatch` of the post-merge workflow | yes | yes |

`pr-validations.yml` had no branch filter and still has none [read], so pull requests into `irc-integration` were
already covered. The opt-in job therefore runs on every pull request whose merge commit carries this workflow file.

### Concurrency

- Pull request: opt-in job group `ci-iceberg-fork-<number>`, cancel in progress; default job group `ci-<number>` as
  before. Different groups, so the jobs never cancel each other [read].
- Post-merge: the workflow-level group with `cancel-in-progress: false` is untouched; the reusable workflow sets none [read].

### Caches

- Fork cache key unchanged (pinned commit plus hash of `publish-local.sh`). Every run restores.
- A branch run saves on a miss, as before.
- A pull request saves only if it changes `iceberg-fork.properties` or `publish-local.sh`. GitHub scopes that cache to
  the pull request (`refs/pull/<n>/merge`): its later pushes restore it, nothing else can. After the merge the
  post-merge run publishes cold once more and saves for everyone.
- A pull request that misses without such a change publishes and saves nothing.
- Gradle cache: `cache-read-only: github.ref != 'refs/heads/irc-integration'`.
- On pull request 111: detection step succeeded, restore succeeded, publish step took under one second (so the fork
  was restored), save step skipped [read, job steps].

## T9: what changed

- New `ServiceWarehouse` in the kit's `service` source set; `RestCompatKitService` uses it and writes the warehouse
  into `ready.json`. The same few lines as the Spark 4.2 class, not a shared helper: the only common dependency of the
  two service source sets is the tables test fixtures, a published artifact of the default build [read].
- `expected-outcomes.txt` unchanged [ran, `git diff`].

## What I ran

| What | Result |
| --- | --- |
| Kit, before the fix, 17-character checkout | 160 tests: 38 passed, 99 failed, 23 skipped; matches the list [ran] |
| Kit, before the fix, 87-character checkout | 160 tests: 1 passed, 136 failed, 23 skipped; task FAILED [ran] |
| Kit, head f5928310, 17-character checkout | 38 / 99 / 23; matches [ran] |
| Kit, head f5928310, 87-character checkout | 38 / 99 / 23; matches [ran] |
| `spotlessApply`, `spotlessCheck` on the kit module under the switch | pass [ran] |
| Kit task without the switch | `project 'openhouse-rest-compat-kit' not found`: nothing to run on the default build [ran] |
| `actionlint` 1.7.12 on all workflows | 0 errors; shellcheck not installed, so `run:` scripts were not linted [ran] |
| Change-detection snippet in a depth-1 clone | base 6bc6fb05: `changed=false`; base f1f31c35: `changed=true` [ran] |

The longest pair of names the kit stores is `newdb.table_renamed`, 18 characters (service access log) [ran].

## Not proven

- The result of pull request 111's own CI (both jobs were in progress).
- The save path of a pull request that changes the pin; the miss-without-change path; cancellation of an older
  opt-in run by a newer push; the post-merge call of the reusable workflow (first run is after the merge).
- That a pull request restores the Gradle cache written by the post-merge opt-in job (key rules read in
  gradle/actions documentation, not observed).
- A stacked pull request whose base branch changed the pin misses the cache on every push and saves nothing.

## Deviations

- Merged `origin/irc-integration` (pull request 110) on the coordinator's instruction; the branch has a third commit.
- No new test for T9 (pull request 108 added one for Spark 4.2): the proof is the run from the deep checkout.
- The Gradle cache condition compares `github.ref` with `refs/heads/irc-integration` instead of `github.ref_name`
  with `irc-integration`: the same for branches and pull requests, stricter for a tag of that name.
- The T9 commit was amended once before the push (its message named a wrong runner path length and a wrong longest
  table).
- The hooks run Gradle themselves; I passed the worker limit and idle timeout to them through `GRADLE_OPTS`.
- The temporary 87-character worktree `/home/user/oh-t9-deep/...` is removed. No process of mine is running.
