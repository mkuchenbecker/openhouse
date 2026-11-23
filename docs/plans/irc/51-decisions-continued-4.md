# Decision log, continued a fourth time

Continues `37-decisions-continued-3.md`, which is full.

Bottom line: five decisions, all dated 2026-11-23. D37 to D39 and D41 are the orchestrator's, and D40 is put to Mike.
D37 settles the 16 statements where Spark 4.2 over IRC differs from the baseline: one problem is fixed (the rename
target, N6, done) and the rest are accepted. D38 records how V9e makes Iceberg 1.12 the default. D39 makes the IRC
multi-table commit route serve one table and refuse several whole. D40 asks Mike whether to build real atomic
multi-table commit. D41 refuses an over-long metadata location on both APIs. None is agreed by Mike unless the entry
cites a statement. Claude wrote every entry.

## D37. The differences of Spark 4.2 over IRC: rename target fixed, the rest accepted

- Date: 2026-11-23. The orchestrator's decision. Two-way, except the release note at the end.
- Not accepted, to be fixed: `ALTER TABLE ... RENAME TO <catalog>.<db>.<t>` worked on the baseline and is refused
  on the target platform, because the OpenHouse catalog strips its own name from the target and Iceberg's catalog
  does not. It is fixed in the SQL extensions, not in the service, because the service cannot know what a client
  calls its catalog. A target naming another catalog keeps the baseline's refusal. Work item N6, running on branch
  `irc/n6-spark42-rename-target`. It affects 5 of the 16 statements.
- Accepted:
  - `DESCRIBE NAMESPACE` and `SHOW NAMESPACES IN <catalog>.<db>` answer where the baseline refused. A refusal
    becoming an answer is the same family as D14.
  - `SHOW TABLES` without a database fails with Iceberg's exception class and message instead of OpenHouse's.
    Both fail.
  - Three namespace statements (create, set properties, drop) stay refused with the same class and the new
    client's wording (D20).
  - Two statements raise an Iceberg exception type with the same native message where the baseline raised an
    OpenHouse client class that the target platform cannot have.
- Spark 4 itself, nothing to decide, recorded: after RTAS the second snapshot's operation is `overwrite` where
  Spark 3.5 writes `append`; `DESCRIBE TABLE EXTENDED` has no `Name` row and has `Catalog`, `Namespace`, `Table`
  rows instead.
- Also decided: three Spark 3.1 tests with no Spark 3.5 equivalent (two on bucket partitioning, one on blocking the
  `policies` property) are ported to Spark 3.5 and run there before the Spark 3.1 project is deleted, as the first
  step of V9e. Any difference from Spark 3.1 behaviour is reported as a finding (`49-t10-spark35-tests-result.md`).
- One-way door for Mike, not blocking: the accepted differences become user-visible behaviour of the Spark 4.2
  platform at release. The list is in `50-n3b-spark42-smoke-result.md` for him to veto.
- Why: the rename is a working statement that stops working, so it is a loss. The others turn refusals into
  answers or change only the wording of a refusal.
- References: pull request 117 (merge commit `c1303849f3baf92b95896b75317171886768fec8`); pull request 115 (merge
  commit `5ea51d35aacd62f124ce2a05ad7c1c004ad2a3c4`); D14, D16, D20, D23.
- Reverse by: for the accepted differences, a new declared difference in the smoke suite changes nothing in
  behaviour; making a refusal again needs a change in the extensions. N6 can be dropped to accept the rename
  difference.

- Addendum, 2026-11-23, after N6 (pull request 119, `52-c9-n6-result.md`): the rename target is fixed and the
  smoke suite declares 11 differences, 166 of 177 statements as on the baseline. One more detail is accepted: the
  rule refuses during analysis, so `EXPLAIN` of a refused rename prints the refusal where the baseline prints a
  plan, and `EXPLAIN` of an accepted rename shows `db.t2` where the baseline shows `openhouse.db.t2`. The
  `owner` property is the same on every line, so no difference is recorded for it.

## D38. How V9e makes Iceberg 1.12 the default

- Date: 2026-11-23. The orchestrator's decision, a two-way door. V9e is running on branch
  `irc/v9e-iceberg-1.12-default`. Extends D3.
- Decision:
  - A plain build becomes the 1.12 line on Java 17, including the Spark 4.2 modules.
  - LinkedIn's Iceberg 1.5.2 line stays selectable by one explicit property, only as the behaviour reference (D8)
    for the delta-harness and for regressions.
  - CI keeps two jobs with their roles swapped: the default job builds the 1.12 line and the other builds the 1.5.2
    reference.
  - The orchestrator waits for green CI before merging V9e, because it is a no-behaviour-change item (D18 asks for
    the full suite before such a merge).
- C9 (the Docker checks for the 1.12 line in CI, pull request 114) is still running.
- References: `44-v9abc-flip-prep-result.md`, `47-v9d-deployables-result.md`; D3, D8, D18.
- Reverse by: flipping the default property back; the 1.2 and Spark 3.1 projects are deleted by the same item and
  would come back from git history.
- Addendum, 2026-11-23, after the first V9e results (draft pull request 118; `52-c9-n6-result.md` for C9):
  - The three Spark 3.1-only tests are ported and pass unchanged on Spark 3.5 on both Iceberg lines, so there is no
    Spark SQL difference between Spark 3.1 and 3.5 for those statements.
  - Locally the default is switched: a plain build is the 1.12 line, `-PicebergLinkedIn` selects LinkedIn's 1.5.2
    reference line, and `-PicebergFork` is a no-op with a warning. Jar contents equal what each line produced before.
  - Accepted by the orchestrator: the reference line builds no deployables; the reference CI job runs the whole
    build under the opt-out; the publish script is left untouched because its hash keys CI's fork cache.
  - Added to V9e's scope: a guard so the release workflow fails early with an explanation. After the flip it would
    publish artifacts whose metadata names fork Iceberg jars that no repository hosts.
  - Publishing the fork's Iceberg jars anywhere is a one-way door and stays Mike's decision. It does not block work.
  - C9 is merged in part (pull request 114). The orchestrator still waits for green CI before merging V9e. The
    Spark image checks it did not get are C10, after V9e.

## D39. The IRC multi-table commit route serves one table and refuses several whole

- Date: 2026-11-23. The orchestrator's decision, a two-way door. Done by V8 (pull request 120, merge commit
  `a217cce94f9592c088a4193dad87a06969c0fd4c`; result in `53-v8-multi-table-commit-result.md`).
- Decision: a commit that can half-apply is not acceptable. `POST /iceberg/v1/transactions/commit` commits a
  transaction that changes one table as that table's ordinary commit (204) and refuses a transaction that changes
  several tables whole, with 400, before anything is read or written.
- Why: House Tables only offers a compare-and-set of one row per call, so two tables cannot be committed
  atomically, and committing table by table with an undo is unsafe under a concurrent writer or a crash between two
  tables. Nothing sends the route: Spark 3.5 and 4.2 never do and the compatibility kit has no test for it (kit
  unchanged at 39 passed, 98 failed, 23 skipped).
- Sub-choices adopted from the report's list of ten:
  - The refusal is 400, decided on the request's shape alone, so a caller with no rights and a missing table get the
    same answer. Not 409 (invites a retry), not 5xx (the 1.12 client reads it as commit state unknown).
  - Two changes to one table count as several and are refused.
  - The route never creates a table: an unknown table or a view's name is 404.
  - The route is listed in the advertised endpoints (now 19), because the 1.12 client refuses locally when it is
    not listed and has no fallback.
  - `Idempotency-Key` is not honoured, as on every other route.
  - On the Iceberg 1.2 runtime the route stays 406.
  - The route carries the policies capability annotation like the ordinary commit; the response is 204 with no body.
- References: D14, D32; `53-v8-multi-table-commit-result.md`.
- Reverse by: removing the route's mapping and its line in the endpoint list returns it to 406. A refusal turned
  into real atomicity is D40.

## D40. Put to Mike: whether to build real atomic multi-table commit

- Date: 2026-11-23. Put to Mike by the orchestrator. Not blocking.
- Question: should OpenHouse make a multi-table transaction truly all-or-nothing?
- What it takes: a new House Tables endpoint that updates several rows in one database transaction (no DDL change),
  a regenerated House Tables client, and the commit core split into prepare and publish. Roughly 300 to 400 lines in
  House Tables, 150 in the commit core plus the stand-ins, and 100 in the route. House Tables deploys first. The
  design is in `53-v8-multi-table-commit-result.md`.
- One-way door: it is a new House Tables API.
- Recommendation: since no engine sends the route, leaving it as built (D39) is a reasonable choice. Build it only
  if a client that needs multi-table transactions appears.
- Reverse by: not applicable until built.

## D41. A create whose metadata location will not fit 255 characters is refused with 400 on both APIs

- Date: 2026-11-23. The orchestrator's decision, a two-way door. Work item V15, running in the bundle on branch
  `irc/v15-v3b-v3c-service-followups` with V3b and V3c.
- Decision: the service refuses a create whose metadata location will not fit House Tables' 255-character column,
  before anything is written, on both the native and the IRC API.
- Why: today such a request ends in 503 with commit state unknown and leaves an orphan metadata file
  (`21-findings-continued.md`, ninth round). A clean 400 takes nothing away from any client that works today.
- Reverse by: removing the check restores the 503.
