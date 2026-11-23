# Decision log, continued a fourth time

Continues `37-decisions-continued-3.md`, which is full.

Bottom line: two more decisions, both dated 2026-11-23 and both the orchestrator's, each with a dated addendum. D37
settles the 16 statements where Spark 4.2 over IRC differs from the baseline: one problem is fixed (the rename
target, N6, done) and the rest are accepted. D38 records how V9e makes Iceberg 1.12 the default. None is agreed by Mike unless the entry cites a
statement. Claude wrote every entry.

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
