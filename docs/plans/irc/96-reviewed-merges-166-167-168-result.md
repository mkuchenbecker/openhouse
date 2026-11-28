# Three more pull requests merged after an independent review: 166, 167 and 168 (2026-11-28)

Bottom line: three pull requests merged into `irc-integration` on 2026-11-28, each after two rounds of independent sub-agent review
under the owner's standing order S58: 166 (merge `315a6e92287f1c5b7f15bf0a2992f464dc3ea482`, about 13:06 UTC), 167 (merge
`0b3cafb6e0fe8ffde9f94fed43ae525eb79b917a`, about 13:46 UTC) and 168 (merge `66a3c75a13f610ae708e9037e9f170c3033b3ff5`, about 14:45
UTC, the head). `irc-integration` is at `66a3c75a`; 89 pull requests are merged, eighteen of them under the standing review order
(every number from 151 to 168). What is true now that was not at the last notes commit (`f7277f72`, which recorded 86 merged through
161):

1. A deployment that takes this build and changes no configuration sees no `/iceberg` surface: the Iceberg REST routes are served only
   when `cluster.tables.irc.enabled` is true, and off, every request under `/iceberg`, with or without a token, gets LinkedIn main's
   answer (168; ledger BC-004 RESTORED). The published test fixtures keep the routes on, with a README note (D88).
2. LinkedIn's ruleset for main requires the check `build-run-tests / Build and Run Tests`, and it reports again (166; BC-036
   RESTORED). The fork build verifies its wrapper jar and the JDK, `-PicebergLinkedIn=false` selects the default line, the build
   configures without the fork, and each line's exact Jackson list is pinned by tests with no Jackson version changed against the base.
3. The compatibility kit's list checks every failing test's cause and the kit runs twice (167). The number these notes gave as the
   default was wrong: the kit had format v3 hard-coded on. Default configuration: 160 tests, 52 passed, 92 failed, 16 skipped; with
   format v3 on: 54, 90, 16. The 92 are filed under named causes, by what fixes them: 27 views in a namespace with no table (native
   change; FF-60, FF-51), 14 more behind those (view dialect rules), 32 the owner's table policies (replace 16, partition spec 9,
   schema 4, format version 2, drop with purge 1), 4 native House Tables APIs that do not exist, 13 in the Iceberg REST layer alone
   (pull request 169 in flight), 2 the format v3 switch.
4. A correction of the notes themselves: LinkedIn main answers an unmapped `/iceberg` path with 400 from its no-handler path, not 404
   through the token interceptor (measured in 168).

Not true yet, and not claimed: post-merge CI of 166 failed before any test ran (below) and that of 168 was not read; a CI run in
which a job actually fails was not observed for the new aggregate check; the 27 view tests wait on the owner's native change; the
House Tables and jobs services have no Jackson pin; the comparison with main still covers one path (FF-55). Nothing is merged into
either fork's main or into LinkedIn's or Apache's repositories.

Sources: the three merge commit messages (read with `git log -1 --format=%B`), the authors' records (`records/ci-check-name-and-build-hygiene.md`,
`records/compat-kit-list-checks-causes.md`, `records/one-switch-for-the-iceberg-rest-base.md`) and the reviewers' reports
(`records/pr-review-166.md`, `-166-recheck.md`, `-167.md` (its re-check is appended), `-168.md`, `-168-recheck.md`). Marks: [ran]
executed and seen; [read] read in a file, log or merge message. The reviewers ran little (the private-id and line-branch checks, one
probe on jars for 166); the test and build results are the authors', as the merge messages report them.

## Post-merge CI, as read by the orchestrator on 2026-11-28 UTC (about 14:50 UTC)

- Green: 162 (`67989d16`), 165 (`35cc7b1e`), 161 (`81cb4b74`) and 167 (`0b3cafb6`).
- Not read: 168 (`66a3c75a`).
- FAILED, no test ran: 166 (`315a6e92`). `build-run-tests / Default build, rest` failed, and therefore `Build and Run Tests` failed
  (job id 140988141284). Pull request 166 changed `publish-local.sh`, which changed the Iceberg fork's cache key, so that post-merge
  run had to publish the fork from scratch; the fork's own Gradle build then got HTTP 429 Too Many Requests from Maven Central while
  resolving its build classpath (`log4j-core`, `commons-io`, `jdom2` and 38 more with the same cause) and failed at configuration.
  Not re-run (the rule); the next post-merge run (167) was green. Cause and fix are FF-61 (not started): the fork publish on a cache
  miss resolves Iceberg's build dependencies with none of the retry and cache settings OpenHouse's own build gets
  (`scripts/ci/prepare-gradle-home.sh`, `dependency-cache.gradle`).
- The brief for this notes update first said none of these six were read; the orchestrator's later message gave the readings above.

## 1. Pull request 166: the check name and build hygiene (merge `315a6e92`)

What it leaves true (merge message, [read]):
- Each line's last job, "Build and Run Tests", is green only when every needed job succeeded; the one allowed skip is the reference
  line's Docker job. A failed, individually cancelled or wrongly skipped job makes it red. It does not run in a run cancelled as a
  whole, so a superseded run shows cancelled, not failed. LinkedIn's ruleset requires the context `build-run-tests / Build and Run
  Tests`, read from the ruleset itself; the split build of about 14 jobs had stopped reporting it (BC-036).
- The test-group partition is checked in CI by its own job beside the groups; the job every other job waits for takes what it took
  on the base (about 8 s).
- `publish-local.sh` runs the fork's build only with a wrapper jar whose SHA-256 equals the pinned one, on Java 17 or later.
- The Iceberg-line property is read once as a boolean and means the same everywhere; values other than empty, true or false are
  refused. `-PicebergLinkedIn=false` meant true before.
- A missing or partly published fork fails only the tasks that resolve it, with the instruction; the build configures.
- Jackson: the exact versions each line resolves are pinned by tests, for the tables service and for the copy the Spark suites embed:
  on the reference line exactly LinkedIn main's list; on the default line the same with jackson-core 2.22.2, which Iceberg 1.12's
  metadata pulls up; jackson-databind stays at main's 2.13.4 on both. No Jackson version changes against the base.
- The private-id guard skips the planning notes directory, fails on an allow-list entry that matches nothing, and sees more id forms.

Review rounds (two):
- Round 1 (`records/pr-review-166.md`): everything except the Jackson alignment MERGE AS IS; the alignment DO NOT MERGE. F1 to F3 (must
  fix, for that commit): the alignment fixed no demonstrated defect and its stated reason was false (the reviewer checked every class,
  method and field reference in the jars: the existing mix is link-compatible, with no run-time version check and no warning in CI);
  "no native answer changed" was assumed and a probe showed answers that change (the 400 message for a malformed body, the stack-trace
  field of error answers); the embedded service in the Spark suites still ran databind 2.13.4. F4 the scope contradicted its own
  reasoning; F5 should fix: the plan job delayed every job; F6 to F12 minors (`if: always()` made superseded runs red; the `docs/plans/`
  skip also hides notes that do get merged; edges of the new letters; the docs claimed the IDE import works without the fork; a
  partially published fork passed configuration; the Java check refused early-access strings; the behavior list missed one image).
- Round 2 (`records/pr-review-166-recheck.md`, at `e5da0456`, written before 161 merged): MERGE; every must-fix closed because the
  alignment was withdrawn (D84; `build.gradle` has one changed line against the base, the forces byte-identical), the partition in its
  own job, `!cancelled()`, the minors closed; F7 closed by documentation only.
- The change that was made and withdrawn is on the record in the merge message: commits `83fa5bb4` (alignment) and `1ae1657f`
  (withdrawal) are both in the history; the net change is none.

Behavior changes (merge message): none in the product. Build: `-PicebergLinkedIn=false` selects the default line; without the fork the
build configures. CI: the required check name reports again (toward main); one more small job per line; the fork cache key changed once
(see FF-61). Ledger: BC-103 to BC-106, BC-108; BC-036 RESTORED; BC-027 and BC-035 updated.

Evidence (merge message): CI on `55c53abe` with the stored recording of LinkedIn main: 19 checks green, 1 skipped by design; both
aggregates green; the comparison with main passed on both lines with these build changes and nothing in the recording or declarations
changed. The wrapper check was shown refusing a planted jar and a wrong pin; the Java check refusing 8 and 11; the partition check
failing with a group dropped on purpose; the Jackson pins failing when an alignment was put back.

Known gaps: a CI run in which a job failed, or was skipped after an earlier failure, was not observed (verdict script's self-test and
GitHub's documented behavior; likewise how a cancelled required check behaves under LinkedIn's branch protection); the planning-notes
skip is documented, not enforced; the House Tables and jobs services run the same Jackson mix on the default line and have no pin; the
fork's group id and hosting wait on the owner; the CI fork cache is trusted on its key alone; that the shaded jar contains the pinned
versions was read from the build, not checked in the jar.

## 2. Pull request 167: the kit's list checks every cause, at two configurations (merge `0b3cafb6`)

What it leaves true (merge message, [read]):
- The ratchet asserts the total, the exact passing set, the exact skipped set and, for every failing test, the first failing
  assertion's exception type and a stable message prefix. A pass that turns into a failure, a failure that changes cause, a failure
  that starts passing (until its entry is moved), a test that disappears or appears, a changed skip, a service that does not start, a
  dead test JVM, and a missing, empty, duplicated or half-tagged list each fail the build.
- The kit runs twice in CI, in the rest job: at the default configuration (160 tests: 52 passed, 92 failed, 16 skipped) and with
  format v3 on (54, 90, 16). Two tests tagged as format v3 catch both runs silently sharing one configuration. The kit had format v3
  hard-coded on before, which is why the notes' "54 passed, 90 failed, 16 skipped" at default was wrong (corrected where it stood).
- Every one of the 92 default failures was reviewed against the kit's source and filed under a named cause; a green run prints the
  count per cause. The documentation says how to read the list, how to update it, how to handle a kit upgrade and what would move the
  number. The 92 by what fixes them are in the bottom line, item 3.
- A contradiction found on the way and filed as a server gap: on `/iceberg`, HEAD says a namespace with only a record exists, while
  creating a view in it answers 404 "Database not found", because native view create checks only for tables and views. One root
  cause behind 27 tests (FF-60; D89).

Review rounds (two): round 1 (`records/pr-review-167.md`) MERGE AS IS: the reviewer tried every way of keeping the ratchet green
while conformance got worse and each failed the build. F1 and F2 (should fix, record only): VIEWDB was filed as a model difference but
is a contradiction of the server; the dialect tests (14) sit behind it, so fixing it alone turns about 15 to 18 green, not 24 (an
estimate, not run). F3 to F6 minors: IDS (3) is better read as a cheap gap in the `/iceberg` layer; MISSINGMETA classed d although the
specification allows 5XX; stale comments and documentation; two SpotBugs findings in `ServiceWarehouse.delete` (not settled). Round 2:
MERGE, every list entry's outcome, type and prefix verified byte-identical to the reviewed head (162 lines compared by script); the
reasons of six categories changed; the merge kept 166's workflow layout.

Behavior changes (merge message): none in the product. Build and CI: one more kit task in the rest job (about 50 s; the old single run
about 58 s). Ledger BC-107. Evidence: both kit runs green against their lists locally and in CI on `42915635`; all 17 checks green, 3
skipped by design.

Known gaps: only each test's first failing step is checked, so the view routes are barely measured while their first step fails; the two
SpotBugs findings were reported by the author as pre-existing and not verified by the reviewer.

## 3. Pull request 168: one switch for the Iceberg REST base (merge `66a3c75a`)

What it leaves true (merge message, [read]):
- One property, `cluster.tables.irc.enabled`; only the value true turns the routes on. Unset: every request under `/iceberg`, with or
  without a token, gets LinkedIn main's answer: 400 `application/json` from main's no-handler path with main's body ("The combination
  of the method [M] and Path [uri=P] cannot be resolved by server..."), an empty body for HEAD, and one audit event UNRECOGNIZED/400. No
  Iceberg REST bean is created, nothing is mapped there, and the token interceptor is registered with exactly main's patterns.
- With it true, the base's behavior is unchanged. Scan planning needs both switches; with the base off and planning on, the service
  starts, serves nothing under `/iceberg` and logs a warning. Register view needs the base switch and the Iceberg 1.12 line.
- The Iceberg REST error advice is scoped to its own package, so it cannot answer a native route's error with the switch on or off.
- The switch is on in test and CI configurations only (the shared test context initializer, the published test fixtures' embedded
  service, both runs of the compatibility kit); no Docker recipe, compose file or main resource turns it on.

Review rounds (two): round 1 (`records/pr-review-168.md`) MERGE AFTER FIXES. F1 must fix: the off test's "without a token" requests
carried a token (a shared default header from `MockMvcBuilderConfig`), so half the test could not fail, and one test pinned a 404 for a
"tokenless" native request as main's answer (main answers 401 from its token interceptor). F2 should fix: the published fixtures turn
`/iceberg` on for downstream users (accepted, D88). F3 should fix: no real-container evidence. F4 to F9 minors (a dead field that could
disagree with the switch, the behavior list omitting the House Tables and jobs services, a private id in the commit subject, one
`DEPLOY.md` sentence, a hand-kept list of switched classes, a test filter scanned into other contexts). Round 2
(`records/pr-review-168-recheck.md`, at `7e7a607d`): MERGE; F1 closed (tokenless requests really are tokenless and the test checks the
executed request's header); F3 closed (the Docker job requested `/iceberg/v1/config` from the oh-only recipe as shipped and printed 400
and main's body, with and without a token); F8 closed with a residual gap (below); the no-handler path verified in the Spring jars
(`javap`: no interceptor runs before a handler is found); main's 401 for a tokenless native request verified against main's interceptor;
native audit unchanged in all three services; 14 off-test PASSED lines across the two lines' logs.

Evidence (merge message): the off test passes 7 of 7 on LinkedIn main's own code (a checkout of `bb8929e1` with the class copied in) and
on both lines here, and failed 4 of 7 on the base for the right reasons. The kit is unchanged: default 160 tests, 52 passed, 92 failed,
16 skipped; format v3 54, 90, 16; all 160 as listed in both runs. CI on `7e7a607d`: 17 checks green, 1 skipped by design.

Behavior changes, all toward LinkedIn main (merge message): (1) `/iceberg/**` goes from served by default to main's 400 unless the
property is true (BC-004); (2) and (3) in the tables, House Tables and jobs services a request under `/iceberg` with nothing mapped is
audited as UNRECOGNIZED instead of TABLES_SERVICE (BC-109; which service is (2) and which is (3) is not stated) ; (4) the token
interceptor's patterns are main's when off (BC-110); (5) scan planning without the base switch serves nothing and logs a warning
(BC-111); (6) `/actuator/beans` lists no Iceberg REST bean when off (BC-112). Unchanged against the base but different from main, by
decision: the fixtures' embedded service serves `/iceberg`; their README says how a consumer turns it off.

The correction: the merge message says main answers an unmapped `/iceberg` path with 400, not 404 through the token interceptor "as the
planning notes said". Corrected in BC-004, FF-11 and the ledger's list of false claims.

Known gaps: the off test finds switched classes by scanning `com.linkedin.openhouse.tables`, so a switched class in another module, or a
bean method guarded directly by the property, would be missed (none exists; the route enumeration still catches any mapped handler);
the warning's log line is not asserted; the real-container evidence covers `GET /iceberg/v1/config` only; adding `/iceberg` rows to the
stored recording of LinkedIn main needs the commit-path stand-in ported into main's test sources first (FF-55).

## In flight, not results

- Pull request 169 (draft, branch `irc/kit-failures-in-the-rest-layer`): the 13 compatibility-kit failures that sit in the Iceberg REST
  layer alone (message text 8, identifiers 3, missing metadata file 1, view error type 1), each changed only if right by the Iceberg
  REST specification, no native change. The author's record (`records/kit-failures-in-the-rest-layer.md`) says it plans to leave two
  (the missing metadata file: the specification allows 5XX; the view error type: it would need one typed 400) and, at its head
  `49d9a8a5`, reports the kit at default 63 passed, 81 failed, 16 skipped and with format v3 65, 79, 16, IRC suites without failures,
  waiting for CI. Not reviewed, not merged; no result is recorded as true.

## Pointers

- Decisions: D87 (off by default), D88 (fixtures keep the routes on), D89 (view tests refiled), D90 (merge order 166, 167, 168: 168
  conflicts with 167 in the kit service, and 167 needed 166's workflow structure) in `90-decisions-continued-7.md`; D84 (the Jackson
  withdrawal) was recorded before.
- Ledger: parts C (BC-103 to BC-108), G (BC-109 to BC-112, new), BC-004 and BC-036 RESTORED, BC-027, BC-035, BC-039 updated; the last
  section lists the two corrected claims.
- Findings: FF-11, FF-25, FF-27, FF-29, FF-30 done; FF-53 extended with the can-follow findings of 166, 167 and 168; FF-60 and FF-61 new
  (`86-review-findings-and-fix-forward.md`). Waiting on the owner: the view-in-a-namespace question now has its measured weight
  (`58-waiting-on-mike.md` (c)).
- Records: `records/pr-review-166.md`, `-166-recheck.md`, `-167.md`, `-168.md`, `-168-recheck.md`, `ci-check-name-and-build-hygiene.md`
  (copied again, newer), `compat-kit-list-checks-causes.md`, `one-switch-for-the-iceberg-rest-base.md`,
  `kit-failures-in-the-rest-layer.md` (169, in progress).

## Where the brief and the records differ, and corrections to earlier notes

- The brief said no results for 169 and none of six post-merge runs read; the records and the orchestrator's later message supersede it
  (above). The record for 169 shows progress and a plan to leave two of the 13 failures unchanged; the brief says the 13 are all in scope.
- The merge message of 168 names six behavior changes but not which of (2) and (3) concerns which service; the ledger row says so.
- Corrected in the notes: the kit's default number (52 / 92 / 16, not 54 / 90 / 16) and 404 versus 400 for `/iceberg` on main.
