# V14: an IRC create transaction keeps its schema, spec and sort order history

Bottom line: a table created through an IRC create transaction now keeps the schemas, specs and sort orders
the client built, with their ids, exactly as Iceberg's in-memory catalog does; before, it kept only the final ones
renumbered to 0/0/1 while its snapshots named the client's ids, so a scan failed. The loss was an accident of how
the first metadata is rebuilt (IcebergCreates.toCreateBody -> native create -> TableMetadata.newTableMetadata), not
an OpenHouse rule. Kit 41/96/23 -> 42/95/23. Draft pull request 134. Worktree /home/user/oh-v14, branch irc/v14-create-history from origin/irc-integration 9f9561e6.

## Status log
- Read: the completion of a staged create (IcebergRestWriteHandler.commitCreate) spells the candidate as the native
  create body from candidate.schema(), spec(), sortOrder() only (IcebergCreates.toCreateBody); the native create
  builds the first metadata with TableMetadata.newTableMetadata (fresh ids, schema 0, spec 0, order 1).
- Writing pinning tests: services/tables .../e2e/h2/IrcRestCatalogCreateHistoryTest + CreateHistory (reference:
  Iceberg InMemoryCatalog).
- RAN (1.12 line) pinning test before the change: 4 of 6 failed. Today: final schema/spec/order renumbered to 0/0/1,
  snapshots still name schema 1 and spec 1 which the table lacks; a scan fails with NPE (spec null); a dropped+added
  column gets id 3 where the client wrote 4. No-history case and refused final spec passed. Commit 06f30537.
- Implementation fc7d38f1 (CreateHistory in internalcatalog, CreateHistoryScope thread-scoped carrier, repository
  create branch, IcebergRestWriteHandler.commitCreate). RAN: 6/6 pass on 1.12 line.
- abbd1545 consequence tests. RAN 1.12 line: internalcatalog CreateHistoryTest 4/4, services CreateHistory* 7/7 (incl. v3),
  Spark 3.5 catalogTest IcebergRestCreateHistoryTest 1/1, Spark 4.2 SparkIrcCreateHistoryTest 2/2.
- READ apps/: no code assumes spec id 0 or a single spec. TableStatsCollectorUtil maps all_entries.data_file.partition
  positionally by the CURRENT spec's field names (transformPartitionRowToColumnData); on a table whose current spec
  dropped an earlier spec's field this mislabels, the same exposure an RTAS table already has. Not changed.
- Kit RAN: before 41/96/23 (per brief), after 42 passed, 95 failed, 23 skipped; CREATEHIST removed (commit above).
- Draft pull request 134 opened on head 5cf98a45; full suites running (v14-full.sh).
- RAN :services:tables:test 1.12 line: 1727 tests, 0 failed, 1 skipped (merged tree = branch; irc-integration had no new commits).
- RAN :iceberg:openhouse:internalcatalog:test 1.12: 331, 0 failed, 0 skipped.
- RAN :services:tables:test -PicebergLinkedIn: 1699, 0 failed, 51 skipped; the 6 V14 RESTCatalog tests pass on 1.5.2, the v3 one skipped (assumption).
- RAN internalcatalog:test -PicebergLinkedIn: 331, 0 failed; CreateHistoryTest 4/4 on 1.5.2.
- Spark 4.2 test+smokeTest: Gradle daemon killed mid-run (memory), not a test failure; to rerun after the queue.
- RAN Spark 3.5 catalogTest 1.12: 334 tests, 0 failed, 1 skipped. Smoke outcomes: legacy 191 same + 2 declared, rest-shim 191 + 2, rest-http 184 + 9 declared, of 193 each (same as N1c record).
- Merged origin/irc-integration 7e57bd21 (CI/scripts/docs only) as 335f59a6, pushed. check-test-groups.py: partition holds. CI on 5cf98a45 was green (both jobs).
- Local queue killed at its 2-hour background limit during Spark 3.5 catalogTest -PicebergLinkedIn (no result); spotlessCheck and the Spark 4.2 rerun not reached locally.

## Decisions (two-way doors)
- Carry the history beside the native create (thread-scoped CreateHistoryScope + transient property spliced in
  doCommit) rather than new service/DTO parameters: TableAuditAspect binds createTable/putIcebergSnapshots by exact
  arity, so a new parameter would silently drop audits. Shown by: services suite green incl. audit tests.
- Send history only when it differs from what newTableMetadata would give (CreateHistory.of null otherwise): every
  CTAS/plain create takes exactly the old path. Shown by: aCreateTransactionWithoutHistoryIsStoredAsBefore, Spark 4.2 test.
- Splice the eight history JSON fields (not rebuild with the Builder) so ids stay exact even with gaps.
- Built-vs-client final mismatch throws IllegalStateException (invariant, should not happen). CreateHistoryTest.
- Intermediate specs are not judged by the partitioning model (only the final is), as RTAS-kept specs are not.
- RAN spotlessCheck: success. CI on merged head 335f59a6d076c07548f14b1cb5ca2ec2917f9694: all 11 jobs success
  (1 skipped by design: LinkedIn-line Docker job), including both lines' spark-3.5 and spark-4.2 itest groups.
- Not run locally to completion: Spark 3.5 catalogTest -PicebergLinkedIn, Spark 4.2 full test+smokeTest (daemon killed);
  CI ran both itest groups on both lines green.
