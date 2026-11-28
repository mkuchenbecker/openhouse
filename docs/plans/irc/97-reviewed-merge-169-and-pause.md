# Pull request 169 reviewed and merged, and the state at the pause

Bottom line: pull request 169 merged into `irc-integration` of mkuchenbecker/openhouse as `00d78552a35f02194d0b319f78d6008b0fff08f4`
on 2026-11-28 at about 15:30 UTC (90 merged). The Iceberg REST layer now answers 11 more compatibility-kit tests as the
specification asks; no native answer changes. At the default configuration the kit is 160 tests, 63 passed, 81 failed, 16
skipped (was 52, 92, 16); with format v3 on 65, 79, 16 (was 54, 90, 16). An independent review (`records/pr-review-169.md`)
said MERGE AS IS once CI is green, with four minors; CI on `ce78afdf` had 17 checks green and 1 skipped by design (the merge
message). The owner paused execution at about 15:00 UTC the same day ("please pause execution after the current tasks complete");
nothing is in flight. Post-merge CI of 168 (`66a3c75a`) and of 169 was not read. The last section is a start for a new reader.

Sources: the merge commit message of `00d78552` (`git log -1 --format=%B 00d78552` on `irc-integration`), the reviewer's report
`records/pr-review-169.md` (read in full), the author's record `records/kit-failures-in-the-rest-layer.md` (copied while the author
was still writing; its last progress note stops at head `49d9a8a5`, before the merge). The code was not re-read for this file.

## 1. What 169 leaves true

- Of the 92 kit tests failing at the default configuration, 13 failed in the Iceberg REST layer alone (message text 8,
  identifiers 3, a missing metadata file 1, a view error type 1). 11 now pass (the 8 and the 3). Exactly 11 entries of the kit's
  reviewed list moved from failed to passed; no other entry changed outcome, cause, exception type or message prefix (the reviewer
  diffed the list against the base by machine). 52 + 11 = 63 and 54 + 11 = 65.
- Messages reworded on `/iceberg` only, each true wherever it is produced: "Table does not exist" comes only from a missing row;
  "View does not exist" only from a missing or non-view row, and an access denial stays 403; "Table with same name already
  exists" is answered after the privilege check; "Table already exists" on create and rename only when a table holds the name (a
  view holding it keeps the native wording).
- An existence check on an identifier the native rules refuse answers "does not exist" before any read or authorization decision.
  No entity can have such a name, so nothing is revealed. Writes still answer 400, and no name the native rules refuse is accepted.
- Two of the thirteen are left, both right by the specification: a missing metadata file answers 500 (the specification's 404 on
  load means the table does not exist; 5XX is for a server-side problem), and the view error type stays (the specification fixes no
  type for that 400).
- No native route changes: every changed production file is in the Iceberg REST packages, whose error handler is scoped to them
  (`basePackageClasses`); validators, the tables service and the House Tables client are untouched.
- The 81 default failures by what fixes them: 27 need views creatable in a namespace that holds no table (a native change, the
  owner's: FF-51, FF-60); 14 sit behind those (view dialect rules); 32 are the owner's table policies (replace 16, partition spec 9,
  schema 4, format version 2, drop with purge 1); 4 need native House Tables APIs that do not exist; 2 remain in the Iceberg REST
  layer and are right as they are (the two left above); 2 are the format v3 switch. 27 + 14 + 32 + 4 + 2 + 2 = 81, checked, and the
  reviewer found the kit documentation's rows ("What would move the number") sum to 81 too. The four that need native House Tables
  APIs are named in the notes as list of namespace records 2 and view rename 2 (from the sixth update's grouping; the merge message
  does not repeat the split).

## 2. The review

Reviewer's report: `records/pr-review-169.md`, reviewed head `ce78afdf` (the merge of `irc-integration` at `66a3c75a` into the
branch). One round. Verdict MERGE AS IS once CI is green; no MUST FIX and no SHOULD FIX. Checked and found sound, by reading
(the reviewer ran no test or build): no native change; every throw site of `NoSuchUserTableException` and `NO_SUCH_VIEW` means
what the new text says; authorization is decided before anything is answered and no denial is turned into "does not exist"; the
base switch of 168 is respected (no new class or bean outside it); the private-id guard and the line-branch check pass (the
latter reports 98 Iceberg-line branches, all listed); each new text is the reference implementation's. Not checked by the reviewer:
that each new assertion fails with its fix reverted (judged by reading), and CI at the head, which was still running when the
report was written (the merge message gives the CI result above).

## 3. Behavior changes

All on `/iceberg`, none with a LinkedIn main baseline (the routes do not exist on main; off unless `cluster.tables.irc.enabled`
is true, since 168). Ledger rows in `85-behavior-change-ledger.md`, part F:
- BC-113: the reworded messages (missing table, missing view, create onto a name a table holds, rename onto a table).
- BC-114: existence checks and reads on an identifier the native rules refuse answer 404 instead of 400.
- BC-115: on the Iceberg 1.12 line, scan planning (POST `.../plan`) and credentials (GET `.../credentials`) for an invalid table
  identifier answer 404 `NoSuchTableException` instead of 400. Found by the reviewer (M1); not in the pull request's own list;
  not pinned by a test. True and allowed by the specification.

## 4. Known gaps (the minors, now in FF-53)

- M1: BC-115 is pinned by no test (one assertion in `IrcPlanCredentialsRoutesTest` or the read test beside it).
- M2: a comment in `IrcMetricsRouteTest` (about lines 182 to 184) says the routes that post or delete answer 400 for such an
  identifier; the plan route posts and answers 404. Fix: "as on the writes (drop)".
- M3: the fallback in `nameTaken` (`IcebergRestWriteHandler`) to the native message when its House Tables lookup fails is untested.
  It costs latency on the refusal path during an outage and reveals nothing new.
- M4: the ledger had no rows for these changes (closed by BC-113 to BC-115).
- Also unpinned: a commit to a missing view over the Iceberg REST route.
- Left for when work resumes (the orchestrator's decision, not done): the author recommends refiling the missing-metadata-file kit entry in `expected-outcomes.txt` from category d (the server breaks the specification) to e (the kit assumes the reference implementation's answer), since a 500 is allowed there. [As relayed by the coordinator; the final record's text, `records/kit-failures-in-the-rest-layer.md`, says only that the entry is left, with its reasons in the pull request body and the docs: not verified there.]
- Where they are tracked: no work item existed for the thirteen; `86-review-findings-and-fix-forward.md` tracks them under FF-53
  ("169" in its list) and no id was created.

## 5. State at the pause

Bottom line: the service is a nearly complete Iceberg REST Catalog server behind one switch that is off by default; 90 pull
requests are merged, each from 151 to 169 after an independent review; nothing is in flight; what to do first on resuming is the
owner's answer on the native view-create question. `18-state.md` is the one place for current state, and has the ranked list.

Where the code is:
- `irc-integration` of mkuchenbecker/openhouse at `00d78552a35f02194d0b319f78d6008b0fff08f4`: LinkedIn main (`bb8929e1`) plus every
  landed change. Iceberg fork: `openhouse-1.12.0` at `a88f556c` (pull requests 3 to 12 merged). These notes: branch
  `claude/openhouse-rest-v3-planning-g42c0h` (draft pull request 79, never merged). Nothing is merged into either fork's main or into
  LinkedIn's or Apache's repositories. Where each area lives: `00-map.md` section 4.
- Not in git: the working container's worktrees and scratch, the transcripts (`00-map.md` section 7).

The instruments (each is a check a new reader can run or read before believing a claim):
- The stored recording of LinkedIn main `bb8929e1` (`scripts/linkedin-main-baseline/`, the `linkedin-main` resource directories,
  the `LinkedInMain*` test classes; 734 recorded statements): the evidence suites compare with it on both Iceberg lines in CI. It
  measures "existing behavior did not change" on the one path it covers and does not prove more (`00-map.md` section 9;
  `95-reviewed-merges-162-165-161-result.md` section 3). The full re-record is FF-55.
- The House Tables contract: one contract of the table-row repository, written from the real implementation, that passes on the
  production repository over HTTP to a real House Tables service and on the one stand-in in both suites (30 cases from 163, 33 with
  165's additions); not yet run against MySQL (FF-56).
- The kit's cause-checking list: `integrations/java/openhouse-rest-compat-kit/expected-outcomes.txt` lists every test's outcome and
  cause (the first failing assertion's exception type and message prefix), and the kit runs at the default configuration and with
  format v3; a test that changes outcome, cause or type fails the check. Only each test's first failing step is checked.
- The private-id guard: `scripts/ci/check-private-ids.py` (in the default line's plan job) keeps this effort's private work-item
  and decision ids out of code, messages and docs; renames still held by an allow-list are FF-57, the delta harness is FF-58.
- The line-branch list: `scripts/ci/iceberg-line-branches.tsv` with `scripts/ci/check-iceberg-line-branches.py`, a list of every
  test that branches on the Iceberg line, checked in CI.

The numbers (2026-11-28, as of `00d78552`): 90 pull requests merged; ledger 115 rows (`85-...`); work list FF-01 to FF-61 (`86-...`);
decisions D1 to D90; the kit at the default configuration 160 tests, 63 passed, 81 failed, 16 skipped, with format v3 on 65, 79, 16.
Post-merge CI: green for 157, 159, 160, 158, 163, 164, 162, 165, 161 and 167; 166 failed before any test ran (a Maven Central rate
limit, FF-61); 168 and 169 not read.

What would be next, in order of value (`18-state.md`): the owner's answers on the native view-create question (FF-51, FF-60), then
FF-61, FF-31, FF-50, FF-55, FF-56, FF-57, FF-58, FF-59, and the minors in FF-53.

Open questions for the owner: `58-waiting-on-mike.md` (the first is the native view-create question: a namespace with only a record
exists on `/iceberg`, but a view cannot be created in it; 41 of the 81 default kit failures sit behind it, and the reviewer's
estimate is that fixing it alone turns about 15 to 18 green, not run). Only `01-stated.md` carries the owner's authority.
