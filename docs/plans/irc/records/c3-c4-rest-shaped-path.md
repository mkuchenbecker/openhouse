# C3 and C4: a refused replace writes nothing; a stale handle cannot lose another client's change

Bottom line: C4: no lost update on any path (13 races x 4 paths, both Iceberg lines for Spark 3.5).
C3: only the legacy catalog refuses a gated replace before writing; rest-shim, rest-http and Spark 4.2
leave the one data file Spark wrote (+ .crc). Pinned, not fixed; one decision for the orchestrator
(rest-shim stage-replace). Only test code changed. Draft pull request 146, head
`8de993f6efe5b219cb236e6f4a5d5f6d78131176`. CI on that head: all 13 jobs passed (Docker-on-reference skipped by design).
Marks: [ran] run here, [read] read in code, [inferred].

## C3 per path [ran, s32]

| Path | Refused CORTAS / RTAS | Refused REPLACE TABLE (no query) | Allowed CORTAS adds |
| --- | --- | --- | --- |
| legacy | refused before any write (native stage-replace) | nothing | 1 data, 1 manifest, 1 list, 2 metadata (+crc) |
| rest-shim | refused at commit; 1 data file + its .crc left | nothing | 1 data, 1 manifest, 1 list, 1 metadata (+crc) |
| rest-http (3.5) | same as rest-shim | nothing | as rest-shim |
| Spark 4.2 | same as rest-shim | nothing | as rest-shim |

After each refusal, on every path: same rows, same snapshot ids, same metadata files [ran].
Manifests are gone after the refusal [ran]: `BaseTransaction.cleanAllUpdates` on failure [read];
`StagedSparkTable.abortStagedChanges` is a TODO no-op, so the data file stays [read].

Earliest refusal point [read]: plain REST: replace = GET load + client-side transaction; the commit is
the first request with the intent. rest-shim: the seam could call the native stage-replace first,
but an allowed native stage-replace writes an unreferenced metadata file (s32: legacy allowed = 2
metadata files, rest-shim 1) -> changes the allowed replace. Not done; decision. Cleanup: orphan files
job (`OrphanFilesDeletionSparkApp`, ttl default 7 days, min 1 day, to `.backup`) [read].

## C4 per path [ran, s33]

All 13 races: both changes kept in serial order on all four paths. Attempts: legacy, rest-shim
`[CommitFailedException, committed]`; rest-http, Spark 4.2 `[committed]` (changed keys + assert-table-uuid,
`UpdateRequirements.forUpdateTable` [read]; server applies onto stored table, `PolicyPatch` merge).

## Local runs [ran], all 0 failed

- 1.12: Legacy/RestShim/RestHttp smoke classes + RestHttpPathLineTest: 103 passed. Statements 300 each:
  legacy 298 same/2 declared; rest-shim 296/4; rest-http 276/24.
- 1.5.2 (-PicebergLinkedIn): same classes 103 passed; legacy 300/0, rest-shim 298/2, rest-http 276/24.
- Spark 4.2 smokeTest 67 passed (300: 274 same/26 declared); smokeTestServerPlanning 67 passed.
- spotlessCheck both itests; check-test-groups: partition holds.
- Left to CI: full Spark 3.5 itest (job spark-3.5-openhouse-spark-itest, both lines), Spark 4.2 test/extensionsTest/testServerPlanning (spark-4.2-openhouse-spark-itest), everything else (rest, apps).

## Commits

- e7eb4aac test(C3) s32 + harness; ca87ee83 test(C4) s33; b922414e C3 pins; 8de993f6 C4 declarations.

## Log

- origin/irc-integration had nothing new at the final fetch (still 9dfbbca7): no merge needed.
- Attribution: system notice gave `Co-Authored-By: Claude Opus 5.5`; used that instead of the brief's line.
