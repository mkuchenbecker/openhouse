# Decision log, continued a seventh time

Continues `67-decisions-continued-6.md` (D48 to D52; D52 is its last). D53 to D86 follow (D65 to D68, D69 to D76, D77 to D78, D79 to D83 and D84 to D86 added 2026-11-28).

Bottom line: thirty-four decisions, all made by the orchestrator (D53 to D64 on 2026-11-27, D65 to D68, D69 to D76, D77 to D78, D79 to D83 and D84 to D86 on 2026-11-28; D62 was extended twice, not duplicated, for the defects the faithful House Tables stand-in exposed and for what pull request 165 fixed and left; D69, D74 and D75 are now implemented by merged pull requests 158 and 160, and D69 and D75 were extended, not duplicated, for the two decisions the merge of 158 asked for; D67 is dated 2026-11-27 and interim), after the nine retroactive reviews
(`88-review-summary.md`, `85-behavior-change-ledger.md`, `86-review-findings-and-fix-forward.md`) and the scout's report
on the owner's namespace line (`89-scout-namespace-and-prior-work-result.md`), under the owner's statements S57 to S61
(`01-stated.md`). Most of them restore LinkedIn main's behavior (D53 closes an unchecked read, D56 to D61 restore or gate
what the reviews found changed, D62 leaves inherited defects alone). D54 and D64 build on the owner's namespace stack,
D55 withdraws two earlier statements that his design contradicts, and D63 is a reading of what goes upstream that the
owner is asked to confirm. Each is reversible on the branch, and each that touches existing behavior is in the ledger
(`85-behavior-change-ledger.md`). None is agreed by the owner. Claude wrote the entries.

## D53. A view load over the Iceberg REST routes requires the privilege a table load requires there

- Date: 2026-11-27. The orchestrator's decision, after the review of the views and capabilities work and the scout.
- Decided: a view load over the Iceberg REST routes requires `GET_TABLE_METADATA` on that view, the privilege a table
  load requires there, and the check runs before the definition is read. The view list stays names only.
- Why: the route returns the full definition (SQL, schema, properties, history), read with the service's credentials,
  where the native view GET returns only a pointer. The review found it unchecked and pinned as correct by a test (a
  BLOCKER). The scout found that the earlier draft that deferred the REST view operations (fork pull request 71,
  "OpenHouse persists no view anywhere") has been superseded by the views work that landed on LinkedIn main, and that
  no ruling of the owner on views stands in the way (`89-...`, section 6).
- What would change it: the owner ruling that a view definition read needs a different privilege, or no check.
- Reference: `records/review-irc-views-capabilities.md` finding 1; fix-forward item FF-03 (`86-...`); ledger part A where
  it applies.

## D54. `irc-integration` builds on the owner's namespace stack by merging it

- Date: 2026-11-27. The orchestrator's decision under S57; a two-way door.
- Decided: `irc-integration` builds on the owner's namespace stack by merging the head of his upstream pull requests 869,
  870 and 871 as merge commits, and merging again each time he pushes. There is no rebase and no re-creation of the
  branch. The Iceberg REST namespace routes are built on top of his service afterwards (D64).
- Why: S57 ("there are in flight namespace prs authored by me. make sure you are using that and building on top of it").
  The trial merge has one conflicted file, with import-line conflicts only. A rebase would redo 71 merge commits, change
  every SHA in the notes and records, and repeat on each of his pushes, for a branch that will be re-implemented anyway
  (S58).
- What would change it: his stack being rewritten so that a re-merge no longer works, or a decision by him to take the
  routes from another base.
- Reference: `89-scout-namespace-and-prior-work-result.md` section 3; `records/scout-namespace-and-prior-work.md`
  section 4; branch `irc/build-on-owner-namespace-stack`.

## D55. Two earlier statements about namespaces are withdrawn

- Date: 2026-11-27. The orchestrator's decision under S57; the owner's design makes stored, possibly empty namespaces
  part of OpenHouse.
- Decided, withdrawn: (a) the last clause of D20 ("namespace create, update and drop stay unsupported, as natively");
  (b) in D32's working reading, the item "databases that exist only while they hold tables or views" as an OpenHouse
  rule. The rest of D20 and of D32 stands as written. A dated one-line note under D20 and under D32 points here.
- Why: S57 and the owner's own design: a namespace record is optional and independent of tables, may exist with no table,
  and is created, changed and dropped through his routes (`89-...`, section 7, O1 and O2).
- What would change it: the owner saying that the records are not to be served over the Iceberg REST routes.
- Reference: `16-decisions-continued.md` (D20), `37-decisions-continued-3.md` (D32); `89-...` sections 2 and 7.

## D56. The scan planning route is off by default

- Date: 2026-11-27. The orchestrator's decision after the scan planning review; a two-way door.
- Decided: the scan planning route is switched off by default behind a cluster property, and when on it emits a table
  audit event. The synchronous, stateless design of D44 stands; serving the route by default is withdrawn. Which
  privilege the route should need when on is the owner's question, the same access question as credential vending (D45).
  A dated note under D44 points here.
- Why: the review found that the route exposes per-file column bounds on the metadata privilege alone, is not audited as
  its merge message claimed, and cannot be turned off.
- What would change it: the owner deciding the privilege and that the route may be on by default.
- Reference: `records/review-scan-planning.md` findings 1 to 3; fix-forward items FF-04 to FF-06 (FF-05 is the owner's);
  `56-decisions-continued-5.md` (D44).

## D57. The format-version rule answers as LinkedIn main with the format v3 switch off

- Date: 2026-11-27. The orchestrator's decision under S60; a two-way door.
- Decided: with the format v3 switch off, the format-version rule answers as LinkedIn main does: the refusals that came
  with v3 support apply only when the switch is on. The switch also bounds the cluster's configured format version, and
  an upgrade to v3 is logged and audited. Dated notes under D43 and D11 point here.
- Why: three native answers changed by default with no justification (ledger part A), and D43 item 5 contradicted D11(e)
  on the reference line. S60 settles it.
- What would change it: the owner's justification for any of the changed answers.
- Reference: `records/review-format-v3.md` rows B1 to B5 and defects D1 to D3; FF-13, FF-18, FF-40.

## D58. The House Tables row-length checks are off by default

- Date: 2026-11-27. The orchestrator's decision; it revises D41. A two-way door.
- Decided: the House Tables row-length checks are off by default (a non-positive limit means no check). A dated note
  under D41 points here.
- Why: the default of 255 refused writes main accepted on stores with a wider column and made existing tables in that
  range uncommittable.
- What would change it: a deployment whose production DDL has the 255-character column setting the limit, which stays
  possible.
- Reference: `records/review-irc-read-register-limits.md` defect D1; FF-09.

## D59. Policy statements and the other SQL extension statements work again on every Spark catalog on which they worked on main

- Date: 2026-11-27. The orchestrator's decision under S60.
- Decided: policy statements and the other SQL extension statements work again on every Spark catalog on which they
  worked on main. The capability resolver's refusal of catalogs that are not Iceberg's own is removed where main
  succeeded.
- Why: the refusal was a behavior change against main with no justification.
- What would change it: the owner's justification for refusing them.
- Reference: `records/review-client-seam-spark.md` defect D-1; FF-10.

## D60. The jobs scheduler sends what it sent on main

- Date: 2026-11-27. The orchestrator's decision under S60.
- Decided: the jobs scheduler sends what it sent on main and logs no warning. The sort stats reflection fallback is
  restored to main's behavior unless it can be kept as a clean, written-up fix. The default-on job changes classed
  KEEP-FIX stay, and their justification is written in the code. This closes work item W13 as "no change on the wire".
- What would change it: the owner's justification for a different declaration.
- Reference: `records/review-jobs.md`; FF-14; ledger row BC-021.

## D61. The capabilities module is published with the Spark runtime; the apps uber jar ships no logging configuration

- Date: 2026-11-27. The orchestrator's decision under S60.
- Decided: the capabilities module is published with the Spark runtime that depends on it; the apps uber jar stops
  shipping a logging configuration.
- Why: the thin jar needs only declared artifacts, and the log file in the jar changed every consumer's logging.
- What would change it: the owner preferring that the capability types be compiled into the runtime.
- Reference: FF-15, FF-16; ledger rows BC-008 and BC-023.

## D62. Inherited defects of LinkedIn main in the native commit path are not fixed here

- Date: 2026-11-27. The orchestrator's decision under S60.
- Decided: two inherited defects of LinkedIn main that the reviews found in the native commit path are not fixed on this
  branch: a replace commit skipping the version check, so an append between load and replace can be lost; and the
  per-instance stale-commit cache refusing every later commit on a base for five minutes. Fixing them changes native
  behavior and needs its own justification upstream. They are documented (review findings RF-004 and RF-005 in
  `86-review-findings-and-fix-forward.md`) and put to the owner (`58-waiting-on-mike.md`).
- What would change it: the owner asking for either fix, with the justification that goes upstream.
- Reference: `86-...` RF-004, RF-005.
- Extension, 2026-11-28 (after pull request 163): the same rule holds for the inherited defects of LinkedIn main that the faithful House Tables stand-in exposed. They are pinned by tests and reported, not fixed, because fixing them changes native behavior, which needs its own justification upstream: RF-069 (authorization receives a resource with a null table UUID wherever the service builds it from a House Tables row: the view load and, by the reviewer's reading, the native DROP TABLE), RF-070 (the clean-up after an I/O failure calls a delete the real repository refuses with `UnsupportedOperationException`), RF-071 (a descending page request comes back ascending on the real store). They go to the owner (`58-waiting-on-mike.md`). Rests on: `records/pr-review-163.md` F3 and the merge message of `a0239ad7`; the follow-up pins them (FF-54). To undo: the owner asks for a fix; each becomes a new item with its own justification. What is NOT covered by this extension: the register-view directory check (RF-012), which is this branch's own code and is fixed in the follow-up.
- Second extension, 2026-11-28 (after pull request 165, merge `35cc7b1e`): the null-UUID defect (RF-069) is split by whose code it is. Of the six authorization decisions that pass a resource with no table UUID, the three that are this branch's own Iceberg REST code (the metrics report, the view load, the view commit's own decisions) are fixed in 165, because they are not inherited and the fix was cheap (the UUID in the name of the directory OpenHouse allocated for the row); the three that are LinkedIn main's (native DROP TABLE, native view replace, native view drop) are left exactly as on main and pinned by tests that say so, as this decision requires. The Iceberg REST view commit (which then reaches the native replace) and view drop (which decides only through the native drop) stay inconsistent until the native paths change; that is the owner's decision (RF-073, `58-waiting-on-mike.md`). The clean-up's unsupported delete (RF-070) is pinned as it behaves. Rests on: the merge message of `35cc7b1e`; `records/pr-review-165.md` F1 and its call-site table; `records/pr-review-165-recheck.md` section 1. To undo: the owner asks for a native fix, which becomes an item with its own justification (a native change that carries the UUID into `TablesServiceImpl.deleteTable`, `ViewsServiceImpl.putView` and `deleteView`).

## D63. What goes upstream: the orchestrator's reading, to be confirmed by the owner

- Date: 2026-11-27. The orchestrator's reading, not a decision of the owner. It is a foundation question, so it is put
  to him.
- Read as: the Iceberg REST routes and everything else that compiles on LinkedIn's own Iceberg line are contributed as
  additive changes on that line. The move to Iceberg 1.12 (with Java 17 and the removal of the Iceberg 1.2 and Spark 3.1
  projects) is its own separately justified change. Format v3 comes after it. Part B of the ledger (the consequences of
  the 1.12 move) is documented, not undone; its OWNER rows are the questions (`58-waiting-on-mike.md`).
- What would change it: the owner saying where the line between the changes runs.
- Reference: `85-behavior-change-ledger.md` parts A and B.

## D64. Until the owner answers, the Iceberg REST namespace routes are built under `/iceberg`

- Date: 2026-11-27. The orchestrator's decision; a two-way door, cheap to move either way.
- Decided: until the owner answers where the Iceberg REST routes live (his namespace routes are at the service root on
  the specification's paths; this effort's routes are under `/iceberg/v1`), the Iceberg REST namespace routes are built
  under `/iceberg` as a thin layer over his service.
- Why: no path collision exists, and the layer calls his `NamespacesService`, so moving it changes only the mapping.
- What would change it: his answer to question (a) in `58-waiting-on-mike.md`, "From the scout".
- Reference: `89-scout-namespace-and-prior-work-result.md` sections 3 and 7 (O6).

## D65. The refusal of a policy statement over a REST server that does not advertise policies is kept, as a fix

- Date: 2026-11-28. The orchestrator's decision under S60; reversible.
- Decided: a policy statement (`SET POLICY`, `SET TAG`, `UNSET POLICY` and the other execs) through Iceberg's own
  `SparkCatalog` over a REST server that does not advertise `policies` stays refused. It is a behavior change against
  LinkedIn main (main's code would have committed the policy patch as an ordinary table property) and is entered in the
  ledger as BC-053, KEEP-FIX.
- Why: main had no REST path, so no deployed behavior is lost, and writing a policy patch as an ordinary property would be
  silent data in the wrong place; a clear refusal is better. This narrows the literal text of the FF-10 decision ("every
  OpenHouse SQL statement works again on any Spark catalog where it worked on LinkedIn main"), which the reviewer of pull
  request 154 pointed out.
- What would change it: the owner preferring that the patch be written as a property, or a REST server that advertises
  policies in another way.
- Reference: `records/pr-review-154.md` F2; `85-behavior-change-ledger.md` BC-053; `91-first-reviewed-merges-result.md`.

## D66. A pull request sent back by its review gets a re-check of the fix commits by the same reviewer

- Date: 2026-11-28. The orchestrator's decision under S58; reversible.
- Decided: when the independent review of a pull request sends it back, the same reviewer re-checks the fix commits before
  merge when the fixes are more than labelling, and always when the author answers a failing test by changing the test.
- Why: pull request 151: a new check failed on every path, the author changed the check, and the reviewer confirmed
  against the specification and the fork's snapshot producer that the test, not the product, was wrong. An author
  changing a failing test is the case where a second reader matters most.
- What would change it: the owner asking for a different rule.
- Reference: `records/pr-review-151.md` ("Re-check at b27e5133", F3); `records/pr-review-153.md` ("Re-check at 94528ec9").

## D67. The Iceberg REST namespace routes: built under `/iceberg`, a thin layer over the owner's namespace service (interim)

- Date: 2026-11-27 (interim; recorded 2026-11-28). The orchestrator's decision, with open questions to the owner
  (`58-waiting-on-mike.md`, 2026-11-28). It follows D64.
- Decided: the Iceberg REST namespace routes are built under `/iceberg` as a thin layer over his namespace service:
  - create, update properties and drop delegate to his service with his authorization;
  - a namespace exists if it has a record or holds a table or view;
  - the `/iceberg` drop refuses a namespace that still holds a table or view, as the specification requires, which is
    stricter than his route (his drop removes the record and nothing else);
  - a list of records is not added to House Tables;
  - the native view and table create do not start accepting a record as proof that a database exists.
- Why: no path collision with his routes at the service root, and moving the routes later changes only the mapping (D64).
  Two choices are stricter or narrower than his routes, so they are put to him.
- What would change it: his answers; his stack being changed; the work on branch `irc/iceberg-rest-namespace-routes`
  finding that a choice cannot be built as stated.
- Reference: the brief of the work on branch `irc/iceberg-rest-namespace-routes`; `records/scout-namespace-and-prior-work.md`;
  `89-scout-namespace-and-prior-work-result.md`.

## D68. The rule "never re-run a CI job to get green" stands, including for a job that ran no test because a download failed

- Date: 2026-11-28. The orchestrator's decision.
- Decided: the rule stands unchanged. The orchestrator tried to relax it for jobs that ran no test because a download
  failed; the permission system refused the change, so the cause is being removed instead (pull request 155, FF-47: the
  lookup of a dynamic version no longer goes to Maven Central in read-only jobs). Whether one re-run for a no-test download
  failure is acceptable is asked of the owner and is unanswered.
- Why: a re-run turns a failure into a pass without a change; the three failures of 2026-11-27 were a round trip the cache
  could have served (`records/ci-dynamic-version-resolution.md`).
- What would change it: the owner's answer; pull request 155 making the question moot.
- Reference: `58-waiting-on-mike.md` (2026-11-28); `86-review-findings-and-fix-forward.md` FF-47.

## D69. A commit over the Iceberg REST routes requires the read privilege as well, and answers the version it stored

- Date: 2026-11-28. The orchestrator's decision; revisitable until release.
- Decided: a commit over the Iceberg REST routes requires the read privilege as well as the write privilege, and a commit answers the version it stored, not a reload.
- Why: the specification's commit response carries the full metadata and a client cannot commit without loading the table, so a writer without the read privilege is already unable to use the route; the reload is also unchecked and can be of a later commit (RF-007).
- What would change it: the owner wanting a writer without the read privilege to commit (the answer would then have to be a summary, as the native update gives); a reviewer showing an Iceberg client that commits without loading.
- Reference: `records/pr-review-152.md` (the re-check's point 2); implemented in pull request 158, merge `4b9d152d` (2026-11-28).
- Extension, 2026-11-28 (after the merge of 158): on `/iceberg` a commit requires the read privilege as well as the write privilege because the answer carries table metadata. This is a deliberate difference from the native update route, which answers a summary and needs the write privilege only; it is written in the javadoc of the early decision in `api/irc/`. Effect: a writer without the read privilege is refused a commit with 403 where it got 200 (ledger BC-080). The answer is the version the commit stored (BC-084). Rests on: the merge message of `4b9d152d`; `records/pr-review-158.md`. To undo: make the commit answer a summary as the native update does, then drop the read requirement from the early decision (a change in the same class, and the ledger row BC-080 reverts to 200).

## D70. A commit that changes nothing is a read

- Date: 2026-11-28. The orchestrator's decision; revisitable until release.
- Decided: a commit that changes nothing is a read and gets every check a table load gets; which requests are reads is decided by what the request does, not by whether its update list is empty.
- Why: the first fix covered only a literally empty update list and a commit whose updates change nothing (`set-properties {}`) still returned the full metadata on the write decision alone.
- What would change it: a table load stopping to answer what a no-change commit answers (the two answers are the same stored metadata).
- Reference: merge `68e2557d` (pull request 152); `records/pr-review-152.md` F1, F2; ledger BC-061.

## D71. For an update carrying a v3-only type with the switch off, the refusal comes after the privilege check, at the cost of main's order

- Date: 2026-11-28. The orchestrator's decision; revisitable until release.
- Decided: for an update, replace or PUT that creates, carrying a v3-only type with the format v3 switch off, the refusal comes after the privilege check; a caller without the privilege gets 403 where main gave 400, and other validation failures are answered first and alone. Creates keep main's order.
- Why: keeping main's order (400 before 403) needs a read of the stored table before authorization; that read lets an unauthorized caller learn whether a table exists at format v3 and is the pattern pull request 152 removed.
- What would change it: the owner preferring main's order over not reading before authorization; a way to know the stored version without a read.
- Reference: merge `0d54d2fd`; `records/pr-review-156.md` (re-check sections, R1); ledger BC-057.

## D72. With the switch off and a configured format version above 2 the service starts and refuses creates and replaces

- Date: 2026-11-28. The orchestrator's decision; revisitable until release.
- Decided: with the switch off and the cluster's format version configured above 2, the service starts and refuses creates and replaces with a message saying what to change; it does not refuse to start.
- Why: closer to main (reads, updates and snapshot commits keep working; main failed such creates with Iceberg's own text), and a start-up refusal makes a configuration slip an outage.
- What would change it: the owner preferring a start failure.
- Reference: merge `0d54d2fd`; `records/pr-review-156.md` (re-check, F11); ledger BC-058.

## D73. A namespace that holds a table or view answers as before on the Iceberg REST read routes when the namespace record cannot be read

- Date: 2026-11-28. The orchestrator's decision; revisitable until release.
- Decided: a namespace that exists because it holds a table or view answers as before on the `/iceberg` read routes whenever the namespace record cannot be read (store failure, authorization-service failure), with a warning in the log; only a record-only namespace fails when the store fails.
- Why: it was a regression on existing routes: reads that never used the store came to depend on it, with no test and a false statement. The reviewer would have left this to the owner; it was decided because of the regression, and it is listed for him.
- What would change it: the owner's view of how `/iceberg` should depend on his store.
- Reference: merge `518a2134`; `records/pr-review-157.md` F1 and its re-check; ledger BC-071, BC-050.

## D74. There is one existence rule on `/iceberg`, and create and drop use the one the read routes use

- Date: 2026-11-28. The orchestrator's decision; revisitable until release.
- Decided: a namespace exists on `/iceberg` if it has a record, or holds a table, or holds a view whether or not the views gate hides it; create and drop use that rule.
- Why: after 157 a database that holds only hidden views existed for HEAD, load and list views but not for the writes: drop answered 404 and create stored a record over an existing database.
- What would change it: the owner's answer on what existence is for his routes (`58-waiting-on-mike.md`).
- Reference: `records/pr-review-157.md` (re-check, F12); pull request 160, merge `96acfca3` (2026-11-28; the rule ignores letter case, `records/pr-review-160.md` G1); ledger BC-070, BC-074, BC-075 to BC-077.

## D75. Scan planning is off by default; when on it is audited and bounded

- Date: 2026-11-28. The orchestrator's decision; revisitable until release.
- Decided: scan planning is off by default behind `cluster.tables.irc.scan-planning.enabled` (this implements D56); when on it is audited as a table read and bounded (time bound answers `failed`, concurrency and size caps, a refused plan logged at DEBUG, a 503 with `Retry-After` when busy).
- Why: the plan route reads manifest contents with the service's own credentials and its privilege is the owner's open question (FF-05); off by default reproduces main until he answers.
- What would change it: his answer to FF-05.
- Reference: pull request 158, merge `4b9d152d` (2026-11-28); `records/rest-authorization-and-exposure-gaps.md`; `records/pr-review-158.md`.
- Extension, 2026-11-28 (after the merge of 158): scan planning and credentials are off by default until the owner decides which privilege the plan route requires, because the plan answer exposes data values, partition values and file paths to a caller holding only the metadata privilege (ledger BC-078). Rests on: `records/review-scan-planning.md` findings 1 to 3 (the retroactive review), `records/pr-review-158.md`, and the merge message of `4b9d152d`. To undo: set `cluster.tables.irc.scan-planning.enabled` to true (a property, no code change), or change its default once FF-05 is decided; the Spark 4.2 server-planning tasks already run with it on. When it is off the routes answer 406 and are not listed in the config answer.

## D76. Notes are updated once per group of merges, not per merge

- Date: 2026-11-28. The orchestrator's decision; revisitable until release.
- Decided: the planning notes are updated once per group of merges, not once per merge.
- Why: the owner asked for results, not noise (S61).
- What would change it: the owner asking for a different rhythm.
- Reference: `01-stated.md` S61; this commit group (`92-reviewed-merges-152-to-159-result.md`).

## D77. The tables service test heap is 640 MB, on the author's measurement, until the residual retention is found

- Date: 2026-11-28. The orchestrator's decision; a two-way door.
- Decided: `:services:tables:test` keeps a 640 MB maximum heap (it was the Gradle default of 512 MB) as pull request 158 sets it, and returns to 512 MB when the retainer of about nine closed Spring contexts is found.
- Why: the author measured full runs. Before the registry fix the closed contexts' meter registries grew from 21 to 95 over a run; after it 9 to 20, with a peak of 544 MB in use after a full collection at 640 MB, and a peak of about 496 MB at 512 MB, which leaves no margin and would invite out-of-memory failures (the first head's `Default build, rest` died that way). The reviewer's re-check agreed that this justifies 640 over 512. A production service runs one context and has one registry.
- What it rests on: `records/rest-authorization-and-exposure-gaps.md` (Heap), `records/pr-review-158.md` S2, `records/pr-review-158-recheck.md` (S2, C2), the comment above `maxHeapSize` in `services/tables/build.gradle`; ledger BC-087.
- What would change it: finding what retains the closed contexts (a heap dump would name it; FF-53, C2).
- To undo: set the heap back to 512 MB in `services/tables/build.gradle` and run the full suite; the registry-release listener in the test sources stays.

## D78. Pull request 158 merged with three can-follow findings open

- Date: 2026-11-28. The orchestrator's decision; revisitable.
- Decided: pull request 158 was merged with the re-check's three findings C1 to C3 open, and they go to the minors pull request (FF-53): C1 an authorization outage answers 404 instead of 503 for a namespace that has only a record, on five paths; C2 about nine closed test contexts are still retained; C3 a view commit with a null metadata location answers the view as stored now.
- Why: each fails closed or is test-only: C1 tells a client "does not exist" during an outage and leaks nothing; C2 is test heap only; C3 cannot happen with today's native handler. The reviewer's re-check verdict was MERGE with no finding that must be fixed. The four findings left on purpose (lock-flip rule position, client-capability gate after a no-change 409, planning that only partly stops, the rename conflict type) were agreed with the reviewer.
- What it rests on: `records/pr-review-158-recheck.md` section 4 and section 2 (the minors); the merge message of `4b9d152d` (Known gaps).
- What would change it: C1 or C3 being seen to leak or mislead in a deployment; the owner asking for them before the minors pull request.
- To undo: nothing to undo; the fix of C1 is a rethrow of `AuthorizationServiceException` in `IcebergRestApiHandler.storedNamespace` (only when the namespace also holds nothing), of C3 a null check in `IcebergRestViewHandler.answered`.

## D79. Pull request 163 merged before 161, so 161 declares the stand-in's answers as test-double differences, a kind of its own

- Date: 2026-11-28. The orchestrator's decision; revisitable.
- Decided: pull request 163 (one faithful House Tables stand-in) merged before pull request 161 (the recording of LinkedIn main). Draft 161 therefore declares the commit-path answers that changed under the new stand-in (the merge message says about a dozen; the author's record says 16 declared, commit `6f2fc38d`) as TEST-DOUBLE differences, a kind kept distinct from product differences, with a cause such as "main's House Tables stand-in answered this; the real store answers ...". The stored recording is not edited by hand.
- Why: the recording ran main's code with main's old stand-in, so those answers are the stand-in's, not main's product's (review 163 F1: certain for the lines quoted, such as the softDeletedTables page size, a case-only rename, a second restore). The honest classification is neither "main's answer" nor "a change of the product". Editing the stored file by hand is the escape hatch that review 161 M2 asked to remove.
- What it rests on: `records/pr-review-163.md` F1; the merge message of `a0239ad7` (Known gaps); `records/pr-review-161.md` M1, M2; `records/baseline-of-linkedin-main.md`.
- What would change it: a recording taken with the faithful stand-in on main (FF-55), after which the declarations go.
- To undo: remove the test-double declarations once the full re-record exists; no product code is involved.
- Correction, 2026-11-28 (after the merge of 161): the paragraph above says the recording ran main's code with main's old stand-in and counts about a dozen answers. For the commit-path recording that is wrong in the stand-in and in the count: it was recorded on main's code with this branch's EARLIER stand-in of `services/tables`' tests (`e2e/h2/HouseTablesH2Repository` at commit `0fe4678a`, copied into main by the recording script, not main's own), and 161 declares 16 test-double differences on each line (merge message of `81cb4b74`; `records/pr-review-161-recheck.md` section 2 checked the 16 against the recording and the product methods). The Spark suites did run main's own stand-in; with this branch's stand-ins compiled in, 0 of 424 Spark keys differ, three times. The decision itself stands.

## D80. The Jackson location text stays as the newer Jackson gives it

- Date: 2026-11-28. The orchestrator's decision; revisitable until release.
- Decided: the three commit-path 500 messages for a body that is not JSON keep the newer text on the default line (`[Source: REDACTED ...]`, a column counted from 1) and are declared as a difference from LinkedIn main (ledger BC-093; BC-027 covers the dependency move in general).
- Why: the cause is jackson-core's change of the default of `INCLUDE_SOURCE_IN_LOCATION` from true to false in 2.16, a security default (the source text of a rejected body is not echoed). Restoring the source text takes one line at service start but restores only the text, not the column, and gives back what upstream Jackson stopped doing on purpose.
- What it rests on: `records/pr-review-161.md` section 3 (the jars' bytecode: true in 2.14.3 and 2.15.2, false in 2.16.0 and 2.22.2; the fork's bill of materials pins 2.22.2); not verified: which jackson-core each line's tables service resolves.
- What would change it: the owner or a consumer needing the old text; the owner's answer to BC-030.
- To undo: `JsonUtil.factory().enable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)` at service start (the reviewer's suggestion), and remove the declaration in 161.

## D81. The row-lineage columns in `DESCRIBE TABLE EXTENDED` are not restored in OpenHouse and go to the owner

- Date: 2026-11-28. The orchestrator's decision; revisitable.
- Decided: `DESCRIBE TABLE EXTENDED` on a format 2 table on the default line also lists `_row_id` and `_last_updated_sequence_number` (ledger BC-092); OpenHouse does not restore main's answer. It joins the owner's open question about the Spark SQL differences that come with Iceberg 1.12 (BC-030, `58-waiting-on-mike.md`). The merge-on-read `DELETE` snapshot named "delete" (BC-094) has the same disposition and the same question.
- Why: the cause is the fork's `SparkTable`, which lists the columns for every table; Iceberg's `SparkCatalog` builds that class, so nothing in OpenHouse can change it; only a small patch in the Iceberg fork (gating on format version 3 or above) would. Whether to patch the fork is the question D14 and D31 already put to the owner ("Reverse by: patching the fork").
- What it rests on: `records/pr-review-161.md` section 3 (`SparkTable.java:276-287`, `BaseRowDelta.java:55-56`); `records/baseline-of-linkedin-main.md` (scenario s26).
- What would change it: the owner asking for the fork patch.
- To undo: patch the fork's `SparkTable` and move the pin of the fork commit (`scripts/iceberg-fork/iceberg-fork.properties`); remove the declaration in 161.

## D82. The delta harness keeps its ids for now and is the guard's one declared skip; renames are deferred

- Date: 2026-11-28. The orchestrator's decision; revisitable.
- Decided: pull request 164 leaves the delta harness (about 26 lines citing ids and documents not in the repository; built by no CI workflow) untouched, and the private-id check skips it, the one declared skip. Identifiers and test-data names that encode ids (five test and method names; tables, jobs and files named from work-item ids in sixteen files) are not renamed in 164; each is held by an allow-list entry "test data name, rename pending" (FF-57, FF-58).
- Why: other open branches touch those tests, and a rename now would conflict with them; the harness is a standalone tool whose text reaches no product user (review 164 F3). The check does not look inside identifiers, so the deferral is visible only in the allow-list.
- What it rests on: the merge message of `35bd941c` (Known gaps); `records/pr-review-164.md` F3 and F5; `records/pr-review-164-recheck.md` section 3 (the entries cannot hide a new id).
- What would change it: the open branches merging; a decision on the harness's future (FF-58, with FF-24).
- To undo: rename, delete the allow-list entries, run the check.

## D83. On a store failure a record-only namespace answers 404 on the existence checks and 500 on load, left as is in pull request 162 with the comment corrected

- Date: 2026-11-28. The orchestrator's decision about pull request 162; revisitable. Pull request 162 merged as `67989d16` with this behavior and the corrected javadoc; the alignment is now FF-59.
- Decided: in pull request 162, when the record store fails, a namespace that has only a record answers 404 on HEAD, the lists and the rename target, and 500 on load. The behavior is left as is, and the javadoc that said the two rules are one is corrected (the record's commit `7635dd2c`). Aligning the existence checks with load's rule is its own change.
- Why: aligning the checks with load's rule is a behavior change of its own, wider than the findings C1 and C3 of the re-check of 158 that this pull request closes; the author kept the behavior and corrected the comment.
- What it rests on: `records/pr-review-162.md` F2; `records/iceberg-rest-handler-minors.md` (F2 javadoc, behavior unchanged).
- What would change it: a client seen to mislead on a store outage; the owner's answer on what existence is for his routes.
- To undo: apply load's rule (answer as before records existed when the namespace holds something, otherwise the failure) in `IcebergRestApiHandler.hasRecordOrIsListed`; one test per path.

## D84. The Jackson alignment on the default line is withdrawn: jackson-databind stays at LinkedIn main's 2.13.4 on both lines

- Date: 2026-11-28. The orchestrator's decision about draft pull request 166 (FF-30); revisitable only as the last bullet says.
- Decided: pull request 166 first moved the tables service on the default line to Jackson 2.22.2 (databind and eight other modules). That is withdrawn. jackson-databind stays at LinkedIn main's 2.13.4 on both lines (the force in `build.gradle` is main's, byte for byte); the default line's jackson-core stays at 2.22.2 as Iceberg 1.12's bill of materials resolves it (the reference line's is 2.14.3, main's). Each line's exact list of Jackson modules is pinned by tests (`JacksonRuntimeVersionsTest` for the tables service, `JacksonShadedVersionsTest` for the fixtures that shade it), so a module that moves, appears or disappears fails. The author removed the alignment and kept the exact pins; the re-check read the withdrawal as complete (`build.gradle` has one changed line, the boolean line property).
- Why: the reviewer of 166 checked every class, method and field reference in the jars and found the mix (core 2.22.2 with databind 2.13.4) link-compatible: 707 databind classes, 0 missing classes, 0 missing members; no module of the base compares versions at run time; no warning in any CI log of the base; and it is the pattern LinkedIn main itself uses (Iceberg's metadata asks for a newer Jackson and LinkedIn forces databind back to 2.13.4). The author's stated reason, that Iceberg 1.12 needs jackson-core 2.22, was false on the jars (Iceberg 1.12 links against core 2.14.3; its one newer need is a databind method in the remote-signing request parser, which the service never runs). A probe on the jars showed the alignment changes native answers: the text of one native 400 for an unreadable body (`element of java.util.LinkedHashMap<java.lang.String,java.lang.String>` becomes `element of java.util.LinkedHashMap`, and the `cause` with it) and the `stacktrace` field of every native error answer; the Spark suites could not have shown either, because the test fixtures still ran databind 2.13.4. A behavior change against LinkedIn main with no defect behind it does not go in (the owner's rule S60).
- What it rests on: `records/pr-review-166.md` findings F1 to F3 (and F4, the scope contradiction); `records/pr-review-166-recheck.md` section 1; `records/ci-check-name-and-build-hygiene.md`.
- What would change it: a demonstrated defect of the mix.
- To undo: only with a recorded comparison against LinkedIn main first: the reviewer's conditions were pull request 161's comparison run on the default line with the change, a few malformed-body native answers recorded on main and compared (the recording has none, `grep -c 'Unacceptable JSON'` of the commit-path file was 0), the fixtures on the same Jackson, and the `stacktrace` difference declared in the behavior-change list.

## D85. Merge order: 161 before 166, and 161 was held one round after its MERGE verdict

- Date: 2026-11-28. The orchestrator's decision; no reversal needed.
- Decided: pull request 161 (the recording of LinkedIn main) merges before 166 (build hygiene and the check name), so that the comparison with main runs together with 166's build changes before they land: 166 merges the base after 161 and its CI then runs `LinkedInMainComparisonTest` on both lines. Separately, 161 was held for one more round after the re-check's MERGE verdict at `0e5472cd`, because the base had moved (165 merged, 11:01 UTC) and its comparisons had not run on that base: the author merged `35cc7b1e` (`903b8c84`), closed the last items and the re-check was confirmed at `028f295f`, with CI green on the head including the new base and no recorded answer moved (merge about 12:10 UTC).
- Why: the re-check of 161 (its section 7, "what merging 161 first buys for pull request 166", `records/pr-review-161-recheck.md`) lists what the comparison would see on the default line (a changed status, an error's message, a GET body's keys and values) and what it would miss (bodies of successful writes, error fields other than the message, key order, number formatting in GET bodies, timestamp formats, request-parsing strictness). The recording is the only measurement of native answers against main; letting it see 166 first costs one merge order. The hold for the moved base: a comparison that had not run on the base it merges into proved nothing about that base, and 165 had changed the House Tables stand-in's surface and the answers of three routes.
- What it rests on: `records/pr-review-161-recheck.md` section 7 and its confirmation round; the merge message of `81cb4b74` (Evidence); `records/pr-review-166-recheck.md` (its base was `35cc7b1e`, before 161 merged).
- What would change it: 166 needing to land first for a CI reason.
- To undo: nothing to undo; 166 can merge first, and then 161's CI runs the comparison against it.

## D86. During an authorization outage `/iceberg` namespace existence checks answer 503 for every name that cannot be shown to exist

- Date: 2026-11-28. The orchestrator's decision, implemented by pull request 162 (merge `67989d16`; ledger BC-095); revisitable.
- Decided: while the authorization service fails, a namespace existence check on `/iceberg` (HEAD, list tables, list namespaces with a parent, the view list, the target of a cross-database rename) answers 503 unless the namespace holds a table or the database list shows it. That includes names that do not exist at all. A denial still reads as "no record" (404), which hides a record-only namespace from a caller who may not read it; a database that holds a table or only a view answers as before.
- Why: a missing name and a hidden record cannot be told apart while the decision cannot be made, so both answer the same for every caller; 404 would claim knowledge the server does not have, and the load of a namespace already answered 503. The re-check found it reveals nothing: the decision is made before the record is read, and the database list consulted is shown to anyone in the base too. The cost: a client that probes for a name during an outage is told 503, not 404.
- What it rests on: the merge message of `67989d16` (Leaves true, Behavior changes 1); `records/pr-review-162.md` (finding F1 and the behavior table), `records/pr-review-162-recheck.md` sections 1 and 2; D74 (one existence rule) and D83 (the store failure is a different case).
- What would change it: a client that treats 503 on an existence probe as fatal where 404 was recoverable.
- To undo: treat the authorization failure as "no record" again (swallow it in `IcebergRestApiHandler.hasRecordOrIsListed`, as `storedNamespace` did before 162); the tests that pin 503 move to 404.
