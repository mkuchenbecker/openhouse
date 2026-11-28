# V14: a create transaction keeps its schema, partition spec and sort order history

Bottom line: this was a real defect, not only a failure of the compatibility kit. A table created through a create
transaction on the Iceberg REST routes that changed its schema or partition spec before committing could not be
scanned, because the stored table lacked the schema and spec its own snapshots named. Now the table keeps the
schemas, specs and sort orders the client built, with their ids, the same as Iceberg's in-memory catalog produces for
the same calls. The native API and creates without history are unchanged. The kit goes from 41 passed, 96 failed, 23
skipped to 42, 95, 23. Pull request 134 merged on 2026-11-24 as `d30cb9f6` into `irc-integration`, head `335f59a6`.
Dated 2026-11-24. Record: `v14-create-history.md` in the reports directory. Post-merge CI for the merge has not been
read yet.

## What was lost, and why

- Before the change a table made by a create transaction kept only its final schema, spec and sort order, renumbered
  to ids 0, 0 and 1, while its snapshots and manifests still named the ids the client had built. A scan failed with a
  NullPointerException because the spec was missing. A column added in the transaction got a different id from the one
  in the client's data files, and a dropped-then-added column got id 3 where the client wrote 4 [ran: the pinning
  test failed 4 of 6 cases before the change].
- The cause was an accident of reconstruction, not an OpenHouse rule. The commit that completes a staged create
  spelled the native create from the final parts only (`IcebergCreates.toCreateBody`), and Iceberg's
  `newTableMetadata` numbers everything afresh [read in code].
- No rule refuses history [read in code]: the create rules judge only the final parts, the partition spec evolution
  rule applies only to updates, and a replace already stores tables with several specs.

## What the metadata holds now

| Case | Stored |
| --- | --- |
| A create transaction that changes schema, spec and sort order before committing | All schemas, specs and sort orders the client built, with the client's current and default ids, last column id and last partition id |
| `createOrReplaceTransaction` on a missing table | The same |
| A transaction that drops one column and adds another | The added column keeps the id the client gave it |
| A create without history, which is every CTAS | Exactly as before; the history is handed over only when it differs from what the create would build anyway |
| A final spec OpenHouse refuses | Refused with the same 400 as before |
| Format v3 | History, row lineage and native default values match the reference; a later commit carries the lineage on |

Location, properties, UUID, format version and row lineage are still the create's own. A table created this way is
readable and writable through the native catalog from Spark 3.5 and survives later schema changes and commits
through both APIs [ran].

## How the history is carried

- The history travels beside the native create in a thread-scoped carrier (`CreateHistoryScope`) and a transient
  internal property, spliced into the first metadata when it is built. The repository's create branch strips that
  property from incoming properties, so no request of either API can set the history.
- New parameters on the service and the data transfer objects were rejected: `TableAuditAspect` binds `createTable`
  and `putIcebergSnapshots` by exact arity, so a new parameter would silently drop audits. No audited method
  signature changed, and the services suite, which includes the audit tests, is green.
- The eight history fields are spliced as JSON rather than rebuilt with Iceberg's builder, because the builder
  renumbers ids and leaves gaps.
- Before splicing, the code checks that the native create built exactly the client's final parts; a mismatch throws
  an `IllegalStateException`. This is an invariant that should never fire, pinned by `CreateHistoryTest`.
- Only the final spec is judged by OpenHouse's partitioning model; intermediate specs are kept as the client built
  them, as the older specs kept by a replace already are.

## The native API and creates without history are unchanged

`CreateHistory.of` returns null when the history equals what `newTableMetadata` would give, so a plain create or CTAS
takes exactly the old path. Checked by `aCreateTransactionWithoutHistoryIsStoredAsBefore`, by the Spark 4.2 CTAS test
and by the Spark 3.5 native-path test `IcebergRestCreateHistoryTest`, which creates through the native catalog [ran].
Every create rule still judges the final parts and gives the same answers.

## Kit count

| Run | Passed | Failed | Skipped |
| --- | --- | --- | --- |
| Before | 41 | 96 | 23 |
| After | 42 | 95 | 23 |

`testCompleteCreateTransactionMultipleSchemas` passes and its line, with the now empty group CREATEHIST, leaves
`expected-outcomes.txt` [ran]. In the breakdown by cause the group of small model differences goes from 7 to 6.

## Evidence per build line

- New tests: `IrcRestCatalogCreateHistoryTest` (a real `RESTCatalog` over HTTP against Iceberg's in-memory catalog;
  committed first as a pin, where 4 of 6 cases failed, then 6 of 6 pass), `IrcRestCatalogCreateHistoryV3Test`,
  `CreateHistoryTest` (4, the invariants), Spark 3.5 `IcebergRestCreateHistoryTest` and Spark 4.2
  `SparkIrcCreateHistoryTest` (2, against the Hadoop catalog).
- Iceberg 1.12 (default), run locally [ran]: `:services:tables:test` 1727 tests, 0 failed, 1 skipped;
  internalcatalog 331, 0, 0; Spark 3.5 `catalogTest` 334, 0 failed, 1 skipped, with smoke outcomes unchanged
  (legacy 191 of 193, rest-shim 191 of 193, rest-http 184 of 193); `restCompatKitTest` green; `spotlessCheck` passed.
- Reference line (`-PicebergLinkedIn`), run locally [ran]: `:services:tables:test` 1699, 0 failed, 51 skipped (the six
  REST tests pass on 1.5.2, the v3 one is skipped by an assumption); internalcatalog 331, 0 failed.
- Not completed locally: the full Spark 4.2 `test` and `smokeTest` (the Gradle daemon was killed for memory) and
  Spark 3.5 `catalogTest` on the reference line (the local queue hit its time limit). CI ran both groups on both
  lines on the head and they passed.
- CI on the pull request: all jobs of the split build passed on `335f59a6`, 1 skipped by design (the Docker job of
  the reference line); the earlier head `5cf98a45` was green on the single jobs [ran]. The merge commit
  `335f59a6` merged `irc-integration` at `7e57bd21` (the CI split), so the split CI ran on this branch.

## Known gaps

- `TableStatsCollectorUtil` maps partition values by the current spec's positions, so it would mislabel partitions
  on a table whose current spec dropped a field of an earlier spec. Read in code, not run; tables made by a replace
  have the same exposure [read]. Recorded as X15.
- Data written under an older spec was checked by scan planning only, not read back through Spark.
- Register still refuses history (D34).
- Nothing in `apps/` assumes spec id 0 or a single spec [read in code].
