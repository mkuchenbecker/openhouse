# Decision log, continued a seventh time

Continues `67-decisions-continued-6.md` (D48 to D52; D52 is its last). D53 to D64 follow.

Bottom line: twelve decisions, all made by the orchestrator on 2026-11-27, after the nine retroactive reviews
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
