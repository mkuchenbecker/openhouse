# Result of C3 and C4 (pull request 146)

Bottom line: C4 found no defect. From a stale base, a property, policy or column tag statement ends with both clients'
changes on the table on all four catalog paths, in 13 races per path. C3 found a difference that is pinned, not fixed:
a gated replace-as-select is refused before any write only on the legacy path. On rest-shim, rest-http and Spark 4.2 the
refusal comes at commit and leaves the one data file Spark wrote (and its checksum); the table itself is unchanged. A
REPLACE TABLE without a query writes nothing on any path. Pull request 146 changed only test code (nine files, all test
sources of the two Spark itest projects, read by the orchestrator before the merge); merge commit `22017134`, 2026-11-25.
Marks: [ran] run, [read] read in code, [inferred].

## C3: what a refused replace leaves [ran, scenario s32]

| Path | Refused replace-as-select | Refused REPLACE TABLE without a query | An allowed replace-as-select adds |
| --- | --- | --- | --- |
| legacy | refused before any write (native stage-replace); nothing left | nothing | 1 data, 1 manifest, 1 manifest list, 2 metadata files (and checksums) |
| rest-shim | refused at commit; 1 data file and its checksum left | nothing | 1 data, 1 manifest, 1 manifest list, 1 metadata file |
| rest-http (Spark 3.5) | as rest-shim | nothing | as rest-shim |
| Spark 4.2 | as rest-shim | nothing | as rest-shim |

After each refusal, on every path, the table has the same rows, the same snapshot ids and the same metadata files [ran].
The manifests are gone after the refusal, because `BaseTransaction.cleanAllUpdates` runs on failure [read]. The data file
stays, because `StagedSparkTable.abortStagedChanges` in the fork's 1.12 code is an empty method [read].

## Why the plain REST paths cannot refuse earlier [read]

On the plain Iceberg REST catalog a replace is a load (GET) plus a client-side transaction. The first request that carries
the intent to replace is the commit, and Spark has written the data by then. The service has no earlier point to refuse.
The rest-shim could call the native stage-replace first and refuse early, but an allowed native stage-replace writes an
unreferenced metadata file (s32: 2 metadata files per allowed replace on legacy, 1 on the others), which changes every
allowed replace. That was not done (D51). The leftover data file lies under the table's location, is invisible to SQL, and
the orphan files job (`OrphanFilesDeletionSparkApp`, age threshold default 7 days, minimum 1 day, files moved to
`.backup`) removes it [read]. Removing it on every REST path is open work item N18 in the Iceberg fork.

## C4: stale handle, property and policy commits [ran, scenario s33]

| Path | Outcome of 13 races | Attempts of the stale commit |
| --- | --- | --- |
| legacy | both changes kept, in serial order | CommitFailedException (409), refresh, committed |
| rest-shim | both changes kept, in serial order | CommitFailedException (409), refresh, committed |
| rest-http (Spark 3.5) | both changes kept, in serial order | committed on the first attempt |
| Spark 4.2 | both changes kept, in serial order | committed on the first attempt |

Why the REST paths meet no conflict [read]: the request carries only the changed keys plus an assert-table-uuid
requirement (`UpdateRequirements.forUpdateTable`), and the service applies the change onto the stored table, merging
policies in `PolicyPatch`. A change to another key does not invalidate the requirements, so the end state equals the
serial order.

## How the stale handle is built

Scenario s33 issues each statement from a session that holds a stale table, after another client has changed the table,
and reads the table back for both clients' changes, so a lost update fails a readback (13 races per path). The shared
harness added by the pull request holds file observations, a second catalog and a commit hook (commit `e7eb4aac`). The
record does not describe the construction of the stale handle further than this, and it was not re-read in the code.

## Statement counts [ran, 300 statements per path]

| Path | Iceberg 1.12 line: same / declared | Iceberg 1.5.2 line: same / declared |
| --- | --- | --- |
| legacy | 298 / 2 | 300 / 0 |
| rest-shim | 296 / 4 | 298 / 2 |
| rest-http | 276 / 24 | 276 / 24 |
| Spark 4.2 (smokeTest) | 274 / 26 | not run |

Local runs all had 0 failed: 103 tests in the Spark 3.5 classes on each line; Spark 4.2 smokeTest 67 and
smokeTestServerPlanning 67. The pull request build was green on head `8de993f6` on both lines [read by the orchestrator].

## What was not verified

- Post-merge CI for merge `22017134` has not been read yet. (For the merge of pull request 145, `9dfbbca7`, it passed on
  both lines in 32 minutes, and the compaction case that failed once locally, X16, passed there [read].)
- The readbacks were not checked against an injected lost update.
- Local file system only; no HDFS or object store.
- Refusals for reasons other than the replace gate were read, not run.
- The Spark 3.5 itests in full and the Spark 4.2 `test`, `extensionsTest` and `testServerPlanning` ran in CI only.
