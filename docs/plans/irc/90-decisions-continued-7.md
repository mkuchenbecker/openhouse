# Decision log, continued a seventh time

Continues `67-decisions-continued-6.md` (D48 to D52; D52 is its last). D53 to D78 follow (D65 to D68, D69 to D76 and D77 to D78 added 2026-11-28).

Bottom line: twenty-six decisions, all made by the orchestrator (D53 to D64 on 2026-11-27, D65 to D68, D69 to D76 and D77 to D78 on 2026-11-28; D69, D74 and D75 are now implemented by merged pull requests 158 and 160, and D69 and D75 were extended, not duplicated, for the two decisions the merge of 158 asked for; D67 is dated 2026-11-27 and interim), after the nine retroactive reviews
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
