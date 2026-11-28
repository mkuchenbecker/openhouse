# Two more pull requests merged after an independent review: 160 and 158 (2026-11-28)

Bottom line: two pull requests merged into `irc-integration` on 2026-11-28, each after two rounds of independent sub-agent
review under the owner's standing order S58: 160 (merge `96acfca3`, about 05:48 UTC) and 158 (merge `4b9d152d`, about 06:40 UTC).
`irc-integration` is at `4b9d152d`; 81 pull requests are merged, ten of them under the standing review order (every number from
151 to 160). What is true now that was not at the last notes commit (`c16664e6`):

1. One existence rule decides every `/iceberg` namespace route, under any spelling of the name (160): a namespace exists if it
   has a record, or holds a table, or holds a view whether or not the views gate hides it. Wherever exists finds it, create
   answers 409 and drop never answers not-found.
2. The `/iceberg` routes decide authorization before using anything stored (158): the core's lock checks, the write privilege
   and the read privilege come first; a commit answers the version it stored; an authorization outage is 503 on every table
   and namespace route; a rename onto a view writes nothing; metrics bodies are capped at 1 MiB.
3. Scan planning and credentials are off by default (158), behind `cluster.tables.irc.scan-planning.enabled`, until the owner
   decides which privilege the plan route requires (FF-05). When on they are audited and bounded.
4. Nothing native changes with every new property unset, on either Iceberg line (158; the re-check read every main class that
   differs and found only new fields, extractions with the same order and messages, and calls that only the Iceberg REST code
   makes). The compatibility kit is unchanged at 160 tests: 54 passed, 90 failed, 16 skipped.

Not true yet, and not claimed: the plan route's privilege is the owner's (FF-05); the post-merge CI of 158 has not been read;
three minors found by the re-check of 158 are open (C1 to C3, FF-53); the baseline of LinkedIn main (draft pull request 161)
and the House Tables stand-in fidelity branch are in flight with no result. Nothing is merged into either fork's main or into
LinkedIn's or Apache's repositories.

Sources: the two merge commit messages (`96acfca3`, `4b9d152d`), the authors' records (`records/iceberg-rest-namespace-routes.md`
for the namespace work, `records/rest-authorization-and-exposure-gaps.md` for 158) and the reviewers' reports
(`records/pr-review-160.md`, `records/pr-review-158.md` and `records/pr-review-158-recheck.md`), under the common brief
`records/pr-review-brief-common.md`. Marks: [ran] executed and seen; [read] read in a file, log or merge message; [inferred]
concluded without either. The reviewers ran nothing: every review claim below is [read]. Test and build results are the
authors', as the merge messages report them.

## Post-merge CI, as read by the orchestrator on 2026-11-28 UTC

- Green for the merges of 157 (`518a2134`) and 159 (`8a5a9980`) [read].
- 160 (`96acfca3`): the run was in progress with nothing failed at 05:50 UTC [read]. Not known to be finished.
- 158 (`4b9d152d`): not yet read.
- Pull request builds before merge: 160 green on `39cbf161` (merge message); 158: 13 jobs green and 3 skipped by design on
  `e79cee5e`, the compatibility kit's failed list identical to the base's run on `96acfca3` (the reviewer compared the two lists of
  names) [read].

## 1. Pull request 160: one existence rule on every `/iceberg` namespace route, ignoring letter case (merge `96acfca3`)

Bottom line: no FF id; it corrects the inconsistency the second review of 157 found (F12) and decision D74. Two review rounds;
the first found the case-sensitive comparison of the database list.

### What it leaves true
- A namespace exists on `/iceberg` if it has a record, or holds a table, or holds a view whether or not the views gate hides
  it, under any spelling of its name. One method decides "holds something" (the one-row table probe, then the native database
  list compared ignoring case) and every namespace route uses it: exists, load, the parent listing, list tables, list views,
  the rename target, create and drop.
- Wherever exists finds the namespace, create answers 409 and drop never answers not-found. A namespace found only through the
  database list loads with its stored spelling. A load reads the record and checks what the namespace holds once each, also
  while the record store fails.
- The test stand-ins for House Tables list a database's tables ignoring case, as the real store does. Authorization still comes
  first; nothing native and none of the owner's namespace files is touched [read, review].
- Evidence [ran, by the author]: an existence matrix over five cases (views only with views enabled; views only with views
  disabled; tables only; record only; missing) and four mixed-case cases, each asserting that exists, load, create and drop
  agree; the pinned House Tables reads per route; a House Tables test that the database list includes databases holding only
  views (run in CI against MySQL); the stand-ins' own case tests; the compatibility kit unchanged; 1120 tests on the default
  line and 238 on the reference line; pull request build green on `39cbf161`.

### Behavior changes (all on `/iceberg`, which does not exist on LinkedIn main; ledger BC-075 to BC-077)
- A database holding only views, views disabled, no record: create 200 with a stored record before, 409 now; drop 404 before,
  409 not-empty now.
- A database holding only views, reached by another spelling: exists, load, the parent listing, list tables, list views and the
  rename target answered not-found and now find it; create and drop answer 409.
- A create or drop of a name that holds no table also reads the database list, after the authorization decision.

### What the independent review found and how each finding was closed
Round 1 on `87140d87`: merge after fixes. Round 2 on `39cbf161`: merge, by the same reviewer who found the original
inconsistency in the review of 157.

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | G1: the "is listed" half of the rule compared the database name exactly while the table probe and the record match ignoring case, so a case variant of a view-only database bypassed the drop guard and the create conflict (drop 204 and the record deleted; create stored a record over it); an undeclared regression against the base | MUST FIX | `39cbf161`: one comparison, `equalsIgnoreCase`, used by every route; four mixed-case rows. Confirmed in round 2: the record-plus-views row would have failed at `87140d87` |
| 1 | G2: the load failure path asked `holdsSomething` twice | MINOR | `39cbf161`: one holdings check per load |
| 1 | G3: an assertion that could not fail (`assertEquals(expectedDrop, 404, db)` after the drop was asserted 404) | MINOR | Closed in round 2 |
| 2 | Requested versus stored spelling on load | note | Left and declared (below) |
| 2 | The row comment "Tables in irc_ns_mix_t: already true before; pinned" was true of production only; under the old exact-spelling stand-in the row would have answered HEAD 404 | correction | The stand-ins were made faithful to the real store's case rule (`upper(u.databaseId) = upper(:databaseId)`) |

The reviewer also checked [read] that House Tables' database list has no entity-type predicate, so it lists a database holding
only views, and that the authorization order of create and drop is unchanged.

### Known gaps (merge message)
- A name found by the table probe under another spelling still answers the requested spelling on load, while one found through
  a record or the database list answers the stored spelling; no client compares it.
- Two lookups of the stand-ins still compare exactly where production does not (views by database in the fixtures; find by id in
  the service's copy); the existence tests do not use them.
- Not run against a real House Tables store beyond the one query test. The reviewer did not read House Tables' Iceberg-backed
  store (`UserTableHtsRepository`).

## 2. Pull request 158: authorization before anything stored; scan planning off by default (merge `4b9d152d`)

Bottom line: closes FF-04, FF-07, FF-08, FF-48, FF-06 (the bounding part), the three leftovers of the review of 152 (lock checks
first, a commit requires the read privilege, a commit answers the version it stored: D69), and the findings of its own review
(M1, M2, S1 to S4, N3, N5). Two review rounds; the first found a branch in conflict with the base and no CI on its head.

### What it leaves true
- A commit on `/iceberg` is decided on the core's lock checks and on the write and the read privilege before anything stored is
  used or answered.
- A commit or a view commit answers the version it stored. When that version can no longer be read it answers the same table as
  stored now, or "commit state unknown" (500) when another table holds the name. Both Iceberg client lines read that as "the
  commit may have landed" and neither retries or cleans up files [read: the fork's 1.12 `CommitErrorHandler` and LinkedIn's
  1.5.2.24 bytecode, by the reviewer].
- An authorization outage answers 503 on every `/iceberg` table and namespace route; a denial answers 403.
- Scan planning and credentials are served only when `cluster.tables.irc.scan-planning.enabled` is on (default off: 406, not
  listed in the config answer), and when on are audited and bounded (503 with `Retry-After` when busy, a failed plan on timeout).
- Metrics bodies are capped at 1 MiB and audited by identity and size. A rename onto a view writes nothing. The tables service
  tests no longer keep every closed Spring context reachable through the global meter registry.
- Evidence [ran, by the author]: each fix has a test seen failing before it. Full tables service suite on the default line (197
  classes, 1919 results, 0 failed), 96 classes on the reference line (0 failed), selected Spark 4.2 classes in both launchers.
  CI on `e79cee5e`: 13 jobs green, 3 skipped by design; the compatibility kit's failed list identical to the base's (160 tests, 90
  failed). Heap measured before and after the registry fix: 95 registries retained before, 9 to 20 after; peak 496 MB at 512,
  544 MB at 640.

### Behavior changes (the merge message's paragraph; ledger BC-078 to BC-088)
- None on native routes, with every new property unset.
- Against the branch: plan and credentials go from served to not served by default (BC-078, BC-079); the privilege that route
  should require is the owner's open question, and off by default removes the exposure without deciding it.
- On `/iceberg` only, away from native, justified in javadoc: a writer without the read privilege is refused a commit (403, was
  200; BC-080); a lock-state patch on a table without a lock state answers 400 (native keeps its 500; BC-081).
- Toward native: the native 400 for updates on a legacy-locked table; 423 for SYSTEM_ONLY with a failing requirement (BC-082);
  503 for an authorization outage (BC-083). Also the answer of a commit (BC-084), the metrics cap (BC-085) and the rename (BC-086),
  which the merge message lists under Leaves true.
- Build: test heap 512 MB to 640 MB (BC-087, D77); default Spark runs execute with planning off and the four planning classes run
  only in the server-planning task (BC-088).

### What the independent review found and how each finding was closed
Round 1 on `e84034c9`: merge after fixes. Round 2 on `e79cee5e` (re-check): merge, every finding closed or left on purpose.

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | M1: the branch did not merge into the base after 157 landed (GitHub: `mergeable: false`), and 157's test pinned 403 for an authorization outage where this pull request answers 503 | MUST FIX | `2a482f60`: the base merged; 157's outage pins flipped to 503. Confirmed in round 2 |
| 1 | M2: no CI had run on the head (the conflict stopped the workflow); the earlier run's `Default build, rest` died of `OutOfMemoryError` in the tables service tests | MUST FIX | CI green on `e79cee5e` (13 jobs). Confirmed |
| 1 | S1: a commit that succeeded could answer 500 when another write landed before the answer | SHOULD FIX | `53ab4af0`: same table as stored now, or `CommitStateUnknownException` (500); two tests, each red before |
| 1 | S2: the heap raise unexplained (768 MB) | SHOULD FIX | `4a68f782`: the registries are released on context close; measured; 640 MB. A residue stays (C2) |
| 1 | S3: a view commit answered the view as stored now, not the version it stored | SHOULD FIX | `53ab4af0`: `readViewVersion` after a read decision; one test, red before |
| 1 | S4: no Spark run exercised the default server configuration (planning off) | SHOULD FIX | `08405765`: default runs keep planning off; `SparkIrcServerPlanningOffTest`, `SparkIrcNoPlanRequestTest`; the planning classes run in `testServerPlanning` |
| 1 | N3: two refusals not audited (the lock rule's, a view update sent to the table commit route) | MINOR | `53ab4af0`: one FAILED UPDATE event each, asserted |
| 1 | N5: small points, among them rewritten comments keeping private ids | MINOR | `0479a119`: ids out of the comments the branch touched |
| 1 | N1, N2, N4 and the rename's 409 type (N5 point 4) | MINOR | Left on purpose, with the reviewer's agreement: the position of the lock-flip rule, the client-capability gate after a no-change 409, planning that only partly stops, the rename conflict type |
| 2 | C1: an authorization outage answers 404 instead of 503 for a namespace that has only a record, on five paths (list namespaces with a parent, list tables, view list, view create, the rename target), because `storedNamespace` counts every failure as "no record"; from the existence rule of 160, not from this pull request | can follow | Open: FF-53 (D78) |
| 2 | C2: about nine closed test contexts are still retained, cause not identified; the heap could return to 512 MB once it is | can follow, test only | Open: FF-53 (D77, D78) |
| 2 | C3: `IcebergRestViewHandler.answered` answers the view as stored now when the commit's metadata location is null (cannot happen with today's native handler) | minor | Open: FF-53 (D78) |

### Known gaps (merge message)
- The plan route's privilege (FF-05) is undecided.
- During an authorization outage a namespace that has only a record answers 404 instead of 503 on five list, view and
  rename-target paths (fails closed; C1).
- A view commit whose metadata location is null answers the view as stored now (C3).
- About nine closed test contexts are still retained by something not identified, which is why the heap is 640 and not 512 (C2).
- The native rename onto a view still answers 500.
- Not run end to end: the start-up refusal for a non-positive planning bound, the 503 and timeout answers through HTTP, and the
  rename's 409 type (unit-tested or read).
- Four findings left on purpose with the reviewer's agreement: the position of the lock-flip rule, the client-capability gate
  after a no-change 409, planning that only partly stops, the rename conflict type.

## Pointers

- Ledger: `85-behavior-change-ledger.md`, parts F (BC-075 to BC-086) and C (BC-087, BC-088); rows BC-004, BC-068, BC-070, BC-073,
  BC-074 were changed by these merges and say so.
- Findings: `86-review-findings-and-fix-forward.md` (status at the top; FF-53's list has C1 to C3, the four findings left on
  purpose and the items not run end to end).
- Decisions: `90-decisions-continued-7.md` D69 and D75 (extended), D74 (implemented), D77 and D78 (new).
- Records: `records/pr-review-158.md`, `records/pr-review-158-recheck.md`, `records/pr-review-160.md`,
  `records/rest-authorization-and-exposure-gaps.md`, `records/iceberg-rest-namespace-routes.md` (copied again, it is newer).
- In flight, not results: `records/baseline-of-linkedin-main.md` (draft pull request 161), `records/house-tables-stand-in-fidelity.md`.
