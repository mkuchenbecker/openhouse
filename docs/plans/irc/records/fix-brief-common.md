# Common brief for fix-forward work (read all of it before starting)

You own one fix-forward pull request end to end: confirm each finding against today's code, tests first, the fix,
pushed branch, draft pull request, green CI, written record. The orchestrator does not write code; your final report
is all it sees. An independent reviewer reads your pull request before it is merged (the owner's standing order), so
write it to be reviewed.

## Where this comes from

The branch `irc-integration` of `mkuchenbecker/openhouse` is LinkedIn main (`bb8929e1`) plus merged work that makes
OpenHouse serve the Iceberg REST Catalog protocol and support Iceberg format v3. Nine independent reviewers read all
of it on 2026-11-27. Their findings are consolidated in the planning notes (read only for you), in
`/home/user/openhouse/docs/plans/irc/`:

- `86-review-findings-and-fix-forward.md`: every defect (`RF-...`) and the fix-forward items (`FF-...`). Your
  assignment names FF ids: read those items and the RF and BC rows they cite, completely.
- `85-behavior-change-ledger.md`: every behavior change against LinkedIn main (`BC-...`) with its disposition
  (RESTORE, GATE, KEEP-FIX, KEEP-DIRECTED, OWNER).
- `records/review-<area>.md`: the reviewers' full reports, with file and line and quoted code for each finding.
  Read the report sections behind your items before touching code; the consolidated row is a summary.
- `87-clean-reimplementation-guide.md`: what the eventual clean version should look like. Do not restructure toward
  it now; just do not move further away from it.

The owner's rule that governs this work (his words, 2026-11-27): "This is a hard requirement in that this can't be
contributed upstream given behavior changes without sufficient justification. it's not a two way door to change
behavior. it is a two way door when it's documented for follow up and not a foundation decision that invalidates
significant work." And: "all prs should have a sub agent review. this is a standing order."

## Rules for the change itself

- First confirm each finding by reading today's code (it may have moved; another fix may have landed). If a finding
  is wrong or already fixed, say so with the evidence and do not invent work.
- RESTORE means LinkedIn main's behavior comes back exactly: get main's behavior from `git show bb8929e1:<path>` and,
  where the answer depends on runtime behavior you cannot read with certainty, say that it is inferred. GATE means the
  new behavior stays behind a property whose DEFAULT reproduces main; name the property like its neighbours, document
  it where the others are documented, and test both settings.
- Tests first: for each item a test that fails on today's code for the right reason and passes after. Assertions must
  be able to fail and must pin WHICH behavior (status, error type, message, what is written), not just "no error".
  Where an existing test pins the behavior you are removing as "expected", change it and list it in your report with
  the old and the new assertion.
- Nothing beyond your items changes. No drive-by cleanup, no renames, no refactors, except what an item names.
- Two Iceberg lines exist: a plain `./gradlew` is the fork's Iceberg 1.12 (Java 17); `-PicebergLinkedIn` is LinkedIn's
  Iceberg 1.5.2, the reference line. Code under `src/iceberg-1.12` compiles only on the first. Run your touched test
  classes on BOTH lines unless your item exists on one line only (say so).
- No private ids in code: comments and user-facing messages never cite work items, decision ids, review ids (RF, FF,
  BC) or pull request numbers. Comments explain the code. Ids belong in commit messages and the pull request body.
- Your pull request body and final report carry a section "Behavior changes against the base": every change in what
  any existing path does or answers, for whom, in which direction (back toward LinkedIn main, or away from it), or
  "none". For each change away from main, the justification and where it is written in the code.

## Environment rules (each has bitten before)

- `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH LC_ALL=C.UTF-8`
  before everything, including git commits (the commit hook's format check fails under the default Java 21). Every
  Gradle command gets `--max-workers=2 -Dorg.gradle.daemon.idletimeout=60000`. Never `-Dorg.gradle.jvmargs`, never
  `./gradlew --stop`.
- About 15 GB of memory is shared with up to three other building agents. Before each build run `free -g` and
  `df -h /home/user`; wait (sleep and re-check) until at least 6 GB of memory is available; one build at a time;
  delete your own `build` output if free disk drops under 8 GB. Iterate with `--tests` filters on single classes.
  Full suites are left to CI: name the CI job you rely on for each (`Default build, rest`, `Reference build, rest`,
  `... apps-spark-3.5`, `... apps-spark-4.2`, `... spark-3.5-openhouse-spark-itest`, `... spark-4.2-openhouse-spark-itest`).
- The container has restarted three times: keep your record current after every step, commit as soon as files
  compile, push your branch after every commit.
- Worktree: `cd /home/user/oh-c1 && git fetch origin irc-integration && git worktree add /home/user/<dir> -b <branch> origin/irc-integration`
  (directory and branch are in your assignment; if the fetch prints "cannot lock ref", retry after a few seconds).
  Work only there.
- Git hooks share one stash stack across all worktrees. Wrap EVERY `git commit` and `git push` exactly like this,
  check `git stash list` is unchanged before and after, never a bare `git stash`, never `--no-verify`:
  `flock /tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/git-hook-stash.lock env LC_ALL=C.UTF-8 GRADLE_OPTS=-Dorg.gradle.workers.max=2 JAVA_TOOL_OPTIONS="--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED" git commit ...`
  The hook prints `[[: not found` and may skip the format check silently: run `spotlessApply` on touched projects
  yourself and check `git status`. Commit with nothing unstaged in the worktree. If the hook drops merge state
  mid-merge, finish with `git write-tree` + `git commit-tree` (both parents) + `git merge --quit` +
  `git reset --soft`; if it leaves a stash entry named `GitHookUnstagedStash...`, your unstaged changes are in it:
  restore with `git stash apply <sha>` and drop that one entry by its message.
- Never skip, disable, quarantine or delete a test to get green. Never re-run a CI job to get green; a red job prints
  its failed tests: read them. A job that dies resolving or downloading dependencies from Maven Central (a 429, or
  "Could not resolve net.minidev:json-smart") ran no test: report it, do not re-run. Never force-push. Do not comment
  on any pull request.
- Commits: one per FF item, its tests before its fix, each message says WHY and names the FF, RF and BC ids it
  closes. End every commit message with these two lines, unless your session shows a system notice giving different
  attribution lines, in which case follow the notice and say so in your report:
  `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01T3dbirnudathz3xU8K18AE`
  No model names anywhere else.
- Before the final push: fetch and merge `origin/irc-integration` (under the lock; other fixes are landing, so expect
  it to have moved), rerun what the merge could affect, and run `python3 scripts/ci/check-test-groups.py`. Push your
  branch. Open a DRAFT pull request against base `irc-integration` with the GitHub MCP tools (load via ToolSearch; no
  `gh pr`); the title states the result; the body: the items closed (FF, RF, BC ids with one line each), what was
  wrong, what changed, "Behavior changes against the base", how it was verified on each line, what is deliberately
  left, ending with:
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)`
  (blank line) `https://claude.ai/code/session_01T3dbirnudathz3xU8K18AE`
  Do not mark it ready and do not merge.
- Wait for the pull request's CI on your pushed head (a split build of about 14 jobs, 25 to 30 minutes; poll
  `gh api repos/mkuchenbecker/openhouse/commits/<sha>/check-runs --jq '.check_runs[]|[.name,.status,(.conclusion//"-")]|@tsv'`
  every few minutes). When every job has finished, send ONE final report. Then stop every polling loop and background
  process.

## Record and final report

Record at `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/reports/<record name from your assignment>`,
kept current, bottom line first, no length limit, "pull request" in full, never the word "phase".

Final report (one message): bottom line in two sentences; per FF item: confirmed or not (with evidence), the cause,
what changed (files), the test that fails before and passes after, RF and BC ids closed; "Behavior changes against the
base"; existing tests whose pinned expectation you changed (old and new assertion); anything you found beyond your
items (listed, not fixed); what you left and why; test counts as actually run on each line and which suites you left
to which CI job; pull request number and pushed 40-character head; each CI job's result; a proposed merge commit
message (Why / Leaves true / Evidence / Behavior changes / Known gaps / Commits with a reason each); what you did NOT
verify. Say plainly what you ran versus read versus inferred.
