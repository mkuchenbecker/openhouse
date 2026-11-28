# Current state

Bottom line (2026-11-28, seventh update, PAUSED): the owner paused execution at about 15:00 UTC on 2026-11-28 ("please pause
execution after the current tasks complete"). Nothing is in flight. 90 pull requests are merged into `irc-integration` and its head
is `00d78552a35f02194d0b319f78d6008b0fff08f4` (pull request 169, merged about 15:30 UTC: the Iceberg REST layer answers 11 more
compatibility-kit tests as the specification asks, no native answer changes; `97-reviewed-merge-169-and-pause.md`). The
compatibility kit now stands at, default configuration: 160 tests, 63 passed, 81 failed, 16 skipped; with format v3 on 65, 79, 16
(read from the CI log of `Default build, rest` on `ce78afdf`, the branch head that was merged). The 81 default failures by what
fixes them: 27 need views creatable in a namespace that holds no table (a native change, the owner's: FF-51, FF-60); 14 more sit
behind those (view dialect rules); 32 are the owner's table policies (replace 16, partition spec 9, schema 4, format version 2,
drop with purge 1); 4 need native House Tables APIs that do not exist; 2 remain in the Iceberg REST layer and are right by the
specification as they are (a missing metadata file answers 500; a view error type the specification does not fix); 2 are the
format v3 switch. 27 + 14 + 32 + 4 + 2 + 2 = 81 (checked; it matches the kit documentation's "What would move the number" rows
as the reviewer of 169 summed them).

Post-merge CI as read by the orchestrator: 168 (`66a3c75a`) not read; 169 (`00d78552`) not read. Earlier readings stand (below).

What would be next when work resumes, in order of value, as these notes already rank it:
1. The owner's answers on the native view-create question (FF-51, FF-60; `58-waiting-on-mike.md`): the biggest conformance lever
   (the reviewer's estimate is that it alone turns about 15 to 18 kit tests green, not 27; not run).
2. FF-61: the fork publish's exposure to the Maven Central rate limit in CI (the failed post-merge run of 166).
3. FF-31: documentation and false comments.
4. FF-50: the namespace stack's follow-up in this repository.
5. FF-55: the full re-record of the LinkedIn main recording.
6. FF-56: the table-row repository's contract against MySQL.
7. FF-57: renames of identifiers and test data that encode private ids.
8. FF-58: the delta harness decision (needs a decision).
9. FF-59: the store-failure asymmetry on namespace existence (needs a decision).
10. The minors collected in FF-53 (now with the three of 169's review).
Ten ledger rows and the scout's questions wait on the owner (`58-waiting-on-mike.md`); none blocks work. A new reader starts from
`97-reviewed-merge-169-and-pause.md`, section "State at the pause".

The text below is the sixth update (written before 169 merged); where it says 169 is in flight, 89 are merged, or the kit's
default is 52 passed, 92 failed, 16 skipped, this section above supersedes it.

Sixth update (superseded above): 89 pull requests are merged into `irc-integration` and its head is `66a3c75a` (pull
request 168). Eighteen of them merged under the owner's standing order of an independent review (S58), which is every number from
151 to 168: 154, 151, 153 (`91-first-reviewed-merges-result.md`), 152, 155, 156, 157, 159
(`92-reviewed-merges-152-to-159-result.md`), 160 and 158 (`93-reviewed-merges-158-and-160-result.md`), 163 and 164
(`94-reviewed-merges-163-and-164-result.md`), 162, 165 and 161 (`95-reviewed-merges-162-165-161-result.md`) and, since, 166, 167 and
168 (`96-reviewed-merges-166-167-168-result.md`). What each merge left true, changed and left open is in those files; the behavior
changes are in `85-behavior-change-ledger.md` (BC-103 to BC-112 came from the last three merges, and BC-004 and BC-036 are
RESTORED). The decisions of the day are D69 to D90 (`90-decisions-continued-7.md`). Nothing is merged into either fork's main or
into LinkedIn's or Apache's repositories.

The compatibility kit, corrected: until pull request 167 the kit ran with format v3 hard-coded on, so the number these notes gave as
its default, 54 passed, 90 failed, 16 skipped, was the format v3 run. Now (167; read by its reviewer in the CI log, and again in
168's) it runs twice: at the default configuration 160 tests, 52 passed, 92 failed, 16 skipped; with format v3 on 54, 90, 16. The 92
default failures by what fixes them: 27 are views in a namespace that holds no table (a native change, the owner's: FF-60, FF-51) and 14 more sit behind
those (view dialect rules); 32 are the owner's table policies (replace 16, partition spec evolution 9, schema changes 4, format
version 2, drop with purge 1); 4 need native House Tables APIs that do not exist (list of namespace records 2, view rename 2); 13
are in the Iceberg REST layer alone (message text 8, identifiers 3, missing metadata file 1, view error type 1), now in flight as pull
request 169; 2 are the format v3 switch. Every earlier place that states 54 / 90 / 16 as the default carries a dated correction.
Also corrected: LinkedIn main answers an unmapped `/iceberg` path with 400 from its no-handler path, not "404 through the token
interceptor" (measured in 168; ledger last section).

What the last three merges (166, 167, 168) changed: LinkedIn's required check `build-run-tests / Build and Run Tests` reports again
(BC-036 restored) and the fork build verifies its wrapper and JDK (166); the kit's list checks each failing test's cause on both
configurations (167); the whole Iceberg REST surface is off unless `cluster.tables.irc.enabled` is true, and off it answers exactly as
LinkedIn main (168; BC-004 restored; the published test fixtures keep it on, D88). So a deployment that takes this build and
changes no configuration sees no `/iceberg` surface. Earlier in the day:

What the three merges before them (162, 165, 161) changed: the evidence suites now compare with a stored recording of LinkedIn main (161: 734 statements
recorded with main's own build, compared on both Iceberg lines in CI; 9 declared product differences on the reference line, 17 on
the default line, 16 test-double differences on each; none on write statements; what a green run proves and does not prove is
in the ledger and in `95-...`), so "existing behavior did not change" is now measured on the one path it covers and not asserted;
register table, the completion of a staged create and register view refuse a directory named for another entity's UUID, which
production accepted before, and the Iceberg REST metrics report, view load and view commit authorize on the entity's UUID (165;
the three native decisions that pass no UUID are LinkedIn main's and are pinned, RF-069); and the `/iceberg` handlers answer an
authorization outage with 503 on namespace existence checks, never guess a view-commit answer and turn two 500s into 409 and 400
(162).

What the Iceberg REST routes (`/iceberg`) serve now, when `cluster.tables.irc.enabled` is true (off by default since 168; D87): every route except scan planning and credentials (off by default behind
`cluster.tables.irc.scan-planning.enabled`, since 158; D75), the stored-plan routes, credential signing and view rename; and
the namespace writes since 157. A namespace exists if it has a record or holds a table or a view, under any spelling of its
name, and every namespace route uses that one rule (160). Authorization is decided before anything stored is used or answered
(152, 158): a commit needs the read privilege as well as the write privilege (D69), a commit answers the version it stored, an
authorization outage answers 503 and a denial 403, a rename onto a view writes nothing, and metrics bodies are capped at 1 MiB.
Native answers are LinkedIn main's again by default (156). Nothing native changes with every new property unset (158's merge
message; the re-check read the classes).

Post-merge CI, as read by the orchestrator on 2026-11-28 UTC (the last reading at about 14:50 UTC): green for the merges of 157
(`518a2134`), 159 (`8a5a9980`), 160 (`96acfca3`), 158 (`4b9d152d`), 163 (`a0239ad7`), 164 (`35bd941c`), 162 (`67989d16`), 165
(`35cc7b1e`), 161 (`81cb4b74`) and 167 (`0b3cafb6`); NOT read for 168 (`66a3c75a`). FAILED for 166 (`315a6e92`): `Default build,
rest`, and therefore `Build and Run Tests`, failed and no test ran. Pull request 166 changed `publish-local.sh`, which changed the
Iceberg fork's cache key, so that post-merge run had to publish the fork from scratch; the fork's own Gradle build then got HTTP 429
Too Many Requests from Maven Central while resolving its build classpath (`log4j-core`, `commons-io`, `jdom2` and 38 more with the
same cause) and failed at configuration (job `build-run-tests / Default build, rest`, id 140988141284). Not re-run (the rule); the next
post-merge run (167) was green. The cause is FF-61 (not started): the fork publish on a cache miss resolves Iceberg's build
dependencies with none of the retry and cache settings OpenHouse's own build gets, so a cache-key change exposes every job that
publishes the fork to the rate limit at once. Earlier merges (151 to 156) passed on both lines, as recorded in
`92-reviewed-merges-152-to-159-result.md`. The orchestrator's error after 155 (a cache warm-up reported clean from truncated output)
stays recorded there.

In flight when the sixth update was written (merged since as 169, see the top; the plan below was carried out for 11 of the 13, and the author's report held):
- Pull request 169 (draft, `irc/kit-failures-in-the-rest-layer`): the 13 compatibility-kit failures that sit in the Iceberg REST
  layer alone (message text 8, identifiers 3, missing metadata file 1, view error type 1), each changed only if right by the Iceberg
  REST specification, no native change. The author's record (`records/kit-failures-in-the-rest-layer.md`, in progress, not a result)
  plans to change 11 of them (message text 8 to the reference implementation's wording; the 3 identifiers to answer "does not exist"
  on read routes) and to leave the missing metadata file (the specification allows 5XX) and the view error type with their reasons;
  at its head `49d9a8a5` the author reports the kit at default 63 passed, 81 failed, 16 skipped and with format v3 65, 79, 16,
  waiting for CI [author's report, not reviewed, not merged].

Queued next: documentation and false comments (FF-31); the namespace stack's follow-up in this repository (FF-50); the full re-record
of the LinkedIn main recording (FF-55; the stand-in is ported into main's test sources first for the commit path, which `/iceberg`
rows also need); the table-row repository's contract against MySQL (FF-56); renames of identifiers and test data that encode private
ids (FF-57); the delta harness decision (FF-58, needs a decision); the store-failure asymmetry on namespace existence (FF-59, needs a
decision). New and not started: FF-60 (the 27 kit tests behind the namespace contradiction; the owner's native change) and FF-61 (the
fork publish and the rate limit). Also not started: FF-23 and FF-24 (plans in the pull request body of 161) and the rest of FF-53
(now with the can-follow findings of 166, 167 and 168) and FF-36. Held and not started: W14 and N9. Ten ledger rows and the scout's
questions wait on the owner, with the items of 2026-11-28 (`58-waiting-on-mike.md`: now including the verified null-UUID decisions,
the half-fixed Iceberg REST view commit and drop, grants that a drop does not revoke, and the measured weight of the view-in-a-
namespace question); the scan planning question (FF-05) no longer exposes anything while open. None blocks work.

Every sub-agent runs on the larger fallback model or the small one, none on the orchestrator's model (the owner's statement of
2026-11-28, S62).

Where things are: `records/` holds the authors' records and the reviewers' reports: the retroactive reviews
(`records/review-*.md`, brief `records/review-brief-common.md`), the scout (`records/scout-namespace-and-prior-work.md`), the
pull request reviews (`records/pr-review-*.md`, brief `records/pr-review-brief-common.md`) and the brief every fix-forward
agent works under (`records/fix-brief-common.md`). Fixes start from `86-...`, section 3 (status at its top). The map of every
artifact is `00-map.md`. The plan for resuming is `83-resume-checklist-and-plan.md`, and the owner's answer to the self
review is the last section of `84-self-review.md`.

Bottom line (state of the product, last rewritten 2026-11-25; the review of 2026-11-27 and the merges of 2026-11-28 correct
some of it, see below): the service is a nearly complete Iceberg REST Catalog server, Spark 4.2 works over it, and the
maintenance jobs pass their whole suite on Spark 4.2 over it. Iceberg 1.12 is the default build, and the Spark image and
dataloader checks pass in CI. View rename stopped on D47. An audit of the open work items found 8 of 62 already true and 8
partly done (`74-work-table-audit.md`; 14 of the open items had closed by 2026-11-24 and ten more since). This is the single
place for current state.

- Conforming IRC server: every route is served except the stored-plan routes, credential vending and signing, and view
  rename. Namespace create, update properties and drop are served since pull request 157. The compatibility kit passes 63 of
  160 at the default configuration (81 failed, 16 skipped; 65 and 79 with format v3 on; since pull request 169, see the top; it was 52 and 92, and 54 and 90 with format v3 on, before) and its failures by cause are at the top of this file; the older grouping that follows is of 2026-11-28 morning, with the old number: 76 of the failures are OpenHouse model differences, 10 are kit assumptions, 2 are view rename and
  2 are the unlisted records (`58-waiting-on-mike.md`, D32; `39-v13-compat-kit-result.md`,
  `42-v13b-spec-deviations-result.md`). What "conforming" should mean is still put to Mike (D32).
- Spark SQL: on the target platform (Spark 4.2, Iceberg 1.12, IRC only) 199 of 210 smoke statements behave as on the
  Spark 3.5 baseline [CI]. The 11 differences are listed and are accepted or are Spark 4's own (D37,
  `50-n3b-spark42-smoke-result.md`, `52-c9-n6-result.md`). On Spark 3.5 with Iceberg's plain REST catalog 201 of 210
  behave as the baseline on both Iceberg lines; the 9 differences are declared, none in rows read or in what a write
  stores. The OpenHouse catalog and the REST-shaped shim give 208 of 210 on 1.12 and 210 of 210 on 1.5.2 [ran]
  (`64-n1c-n7-result.md`, `77-t6-n10-result.md`). Grants are listed after GRANT and gone after REVOKE on every path.
- Maintenance jobs: they pass their whole suite on Spark 4.2 over the REST catalog (395 tests, 0 failed, 5 skipped); the
  Spark 3.5 jobs are unchanged (false by the review: BC-016 to BC-023 in `85-behavior-change-ledger.md`; BC-019, 020, 021 and 023 restored by pull request 154). Deploying them is Mike's (N12 to N15) (`73-n11-jobs-spark42-result.md`) [ran].
- Iceberg 1.12: the default build since pull request 118 (V9e, `54-v9e-flip-result.md`). LinkedIn's 1.5.2 line is the
  reference, selected with `-PicebergLinkedIn`.
- Format v3: tables can be created and upgraded behind a switch that is off by default, and Spark's row-level writes
  work on them. Spark 3.5 and 4.2 read old files through native defaults (`61-w7-x4-result.md`). Every maintenance job
  runs on a v3 table without losing live data (`63-x6-maintenance-v3-result.md`), and row-level writes racing the jobs
  lose none either (`65-x10-concurrency-result.md`), nor do tables with branches, evolved specs or equality deletes
  (`76-x10-shapes-result.md`); the retention backup's handling of deletes is put to Mike (D46).
  Column types are decided type by type (`68-x5-v3-types-result.md`). Replication and the defaults migration remain
  (`57-x1-format-v3-result.md`).
- One data-loss risk remains, natively only: a create naming a renamed-away table's directory is closed over IRC and
  pinned natively, and the fix is put to Mike (D35).

## What works today

- The native API and Spark 3.5 with the OpenHouse catalog are unchanged. The review of 2026-11-27 found this false:
  26 behavior changes are visible on the reference line (`85-behavior-change-ledger.md`, part A).
- Over the Iceberg REST Catalog, an unmodified Iceberg client and Spark 3.5 can read and write tables and views
  (`27-v3-irc-write-result.md`, `31-v4-irc-views-result.md`). Views cannot be renamed.
- Every OpenHouse statement (policies, column tags, grants, unlock) works through an unmodified Iceberg `RESTCatalog` on
  Spark 3.5 and on Spark 4.2, where the engine holds no OpenHouse catalog or client class
  (`33-v5-policies-over-irc-result.md`, `35-n3x1-spark42-extensions-result.md`, `36-v5-3-grants-unlock-endpoints-result.md`,
  `38-c2-3-rest-capability-client-result.md`). Failures keep the native message but arrive as Iceberg exception types.
- An IRC create can no longer be given another table's directory: a completion naming a directory in use is refused
  with 400, and `openhouse.tableUUID` in a create is ignored (D36, `48-v16-directory-reuse-result.md`).
- Spark 4.2 over IRC passes the Spark SQL smoke suite (210 statements) and 19 write statements, all storing what they
  should (pull request 117, 140); renaming to a target that names the catalog works since N6 (pull request 119).
- Served over IRC now: config with the endpoint list, namespace reads, table create, staged create, load, commit,
  rename, drop, register, metrics report, one-table transactions, views except rename (register-view on the 1.12 line only, 406 on 1.5.2), and the OpenHouse capability
  routes (policies, grants, unlock). On the Iceberg 1.12 line also the synchronous plan route and the credentials
  route, which answers an empty list (pull request 127, `62-v11a-v12a-result.md`); since pull request 158 both are off by default behind
  `cluster.tables.irc.scan-planning.enabled` and answer 406 like the 1.5.2 line, which has no controller.
- Not atomic: multi-table transactions. A transaction of one table commits; several tables are refused whole with
  400 (D39, pull request 120, `53-v8-multi-table-commit-result.md`). Real atomicity is put to Mike (D40).

## How far from the goal

- Maintenance jobs: proven on format v3 tables in every shape examined: plain, under concurrent row-level writes, with
  the new column types, with branches and tags, with evolved partition specs and with equality deletes, on Spark 3.5
  and on Spark 4.2 over the REST routes (`63-`, `65-`, `68-` and `76-` result files) [ran]. Not covered: a job run
  against a branch, a table built by a replace, HDFS and object stores.
- Conformance: of the kit's 95 failures, 76 are OpenHouse's own rules, 9 are routes not served and 10 are kit
  assumptions (D32; `60-v11-v12-investigation-result.md`).
- Iceberg 1.12 as the default: done, and the 1.2 and Spark 3.1 projects are deleted. Open: the default build cannot
  publish anywhere until Mike decides where the fork's Iceberg jars live.

## Merged pull requests

90 pull requests are merged into `irc-integration` (the last is 169, merge commit 00d78552; older text below stops at 149) and 10 into the Iceberg
fork's `openhouse-1.12.0` (the last is 12, commit a88f556c). The full tables are in `45-merged-pull-requests.md`.
The latest: 150 and 149 in `82-v10-x18-result.md`; 147 and 148 in `81-v17-x16-result.md`; 146 in `80-c3-c4-result.md`; 145 and 144 in `79-c20-x11-x12-x13-result.md`.

## Done

P1, P2, I1 to I5, I8, I6e, N1, N2, C8, C12, T12, I6a to I6c, I6g, I6h, T1 to T5, T2b, C1, V1, V2, V3, V4, V5-0, V5-gap, V5-1, V5-2, V5-3, V10a,
C2-1, C2-2, C2-3, V10, X18, N8, V18, C15, T6, N10, V19, V20, V22, N16, X9, C19, C20, X11, X12, X13, C3, C4, V17, X16, N3-x1, N3-x2, N3a, N3b, N3, N6, T10, C9 (green parts), C5, I6f, V13a, V13b, I11, V9a, V9b, V9c, V9e, C6, T9, V6, V7, V8, V9d, V16, T13, C10, C14, X1, W6, W7, X4, X5, X6, V15, V3b, V3c, V11a, V11b, V11c, V11e, V12a, V14, C16, N11 (landable part), N1-c, N7, W1 to W4, W12, I10, the two CI fix-forwards of pull requests 107 and 108, and the X0 design. The meaning of each item is in `04-work.md`.

## How to resume

Bottom line: pull requests 121 and 124 to 150 are merged, and `irc-integration` is at `81b4cd4d`. X2 with X3 is
in draft pull request 151 and W13 is closed by D60 pending its pull request; W14 and N9 are open and not started (below); view rename stopped on D47 with no branch pushed. If the session stops, a future session
reads each record below and the branch's log, and resumes from what they say.

- Usage limit: subagents on the default model are unavailable until 2026-11-30 09:00 UTC.
- Resumed 2026-11-27, X2 and X3: the wider row-level scenarios on format v3. Worktree `/home/user/oh-x23`, branch
  `irc/x2-x3-v3-row-level`, record `x2-x3-v3-row-level.md` in the reports directory.
- W13 is closed by D60 (2026-11-27) pending the pull request; W14 and N9 are open and not started. Earlier, held together: worktree `/home/user/oh-small2`, branch `irc/small-batch-w13-w14-n9`, record
  `small-batch-w13-w14-n9.md` in the reports directory.
- Post-merge CI [read 2026-11-25]: it passed on both lines for the merges of 147 (28 minutes), 148 (32 minutes) and 150
  (30 minutes); for 149 it passed too (read 07:30 UTC: 15 of 16 jobs success, 1 skipped by design). The pull request builds of 149 (head `1ae8e370`) and
  150 (head `ad50d763`) were green on both lines. Before merging 149 the orchestrator read the diff to shared main
  sources: one new method in the native view audit, one visibility change, comments.
- Hazard: the build of the notes pull request had one red job on 2026-11-25 at head `b1c9d0f3` because Maven Central
  answered "429 Too Many Requests" while the job downloaded jars. A job that dies this way ran no test and is reported,
  not re-run; the post-merge builds on `irc-integration` afterwards were not affected.
- Audit of the open work items: `74-work-table-audit.md` (62 open at the time). Read it before launching any small item.
- The container restarted three times (2026-11-24, 2026-11-25 and 2026-11-27) with nothing lost each time: agents push after every commit and keep a record file.
- The reports directory is `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/reports`.

What comes next, after the work in flight:
1. The rest of format v3: W8, X7 (refused today; investigated first), X14 (needs Mike's say) and X17 (the job configuration's missing catalog, Mike's), X8 once D46 is decided, and the work after N16 (N17, Mike's); N12 to N15 are Mike's.
2. V11d and V12b to V12e are Mike's; see `41-work-platforms-v3.md`.
3. The small follow-ups: C11, C13, C17 (first lead from C15), C18, T8, T11, C7. The order of the large work is D29.

## Known red on `irc-integration`

Nothing known red at this time. Post-merge CI for every merge up to and including 150 was read and every job passed;
that of 149 passed (read 07:30 UTC 2026-11-25). Unexplained and not red: the Spark 4.2 `test` task failed once in six runs, and
`SparkIrcEndpointsTest.existenceChecksAreHeadRequests` failed once on reading the access log early (T8, open until a CI
failure names its test); the compaction failure seen once locally was X16, a fixture defect, fixed. V19 and V20, found by pull request 121, are fixed (pull request 142).

## How CI runs

A pull request's CI is the split build from C16 (`71-c16-ci-split-result.md`): parallel jobs, about 22 minutes, with
check names like `build-run-tests / Default build, apps-spark-3.5`, running the same tests as before. Since pull request
135 it has a job for the Spark 4.2 jobs project, `Default build, apps-spark-4.2`, about 17 minutes. Since pull request
144 (C20) the build after a merge is the same split on both lines plus the Docker steps, about 31 to 34 minutes from merge
to answer, and runs for different merges overlap; only the `rest` job of each line writes the Gradle cache. The old
single jobs remain only for the release workflow (`79-c20-x11-x12-x13-result.md`). Since pull request 138 the
print-failed-tests step also reads the build's console log, so a dead test process still lists its failures (not yet
seen on a red job).

## How work is tested

Post-merge results are read after every merge, not assumed (D33). Under D18, before a merge an agent runs the test classes it touched plus the black-box suite of the module it
changed, and Claude merges on that without waiting for the pull request's CI. The full build runs on
`irc-integration` after each merge and a failure is fixed forward, so work that starts from the branch checks the
known-red list above first. A new write route, or a change with authorization consequences, gets an independent read-only review before merge (done for X6 and for register-view; both found real gaps). An item whose claim is "no behavior change" runs the full suite before merge. D26 adds the cheap checks every brief
now requires (`21-findings-continued.md`); D33: a suite counts only if it ran on the 1.12 line.

## Waiting on Mike

Nothing blocks work. The list is in `58-waiting-on-mike.md`: the meaning of "conforming" (D32), whether the server turns on server-side scan planning (V11d), credential vending (V12b), the one-way doors
(D12, D24, D34, D38, D40), view rename and the House Tables API (D47), the native directory-reuse fix (D35), the Spark 4.2 differences (D37), and, new, turning
the v3 switch on for a real cluster and upgrading a real table, and the retention backup's handling of deletes (D46).
Three questions from X5's column types are there too, and X14 (an age floor in orphan removal) needs his say; nothing waits on them.

## How to audit

- Decisions are in `13-decisions.md`, `16-decisions-continued.md`, `22-decisions-continued-2.md` and
  `37-decisions-continued-3.md` (D30 to D36) and `51-decisions-continued-4.md` (D37 to D42, with dated addenda) and `56-decisions-continued-5.md` (D43 to D47) and `67-decisions-continued-6.md` (D48 to D52).
- Findings are in `08-findings.md` and the numbered result files listed in `README.md`.
- Only `01-stated.md` carries Mike's authority.
