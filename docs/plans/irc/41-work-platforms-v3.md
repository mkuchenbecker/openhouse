# Work: native path, platforms and v3 functions

Moved out of `04-work.md` to keep that file under 150 lines. Columns and rules are as there.

## Native path and platforms

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| N1 | oh | The same config value selects native REST over HTTP on Spark 3.5. | C2-3 (done), V3, V4, V5-3 | T1 identical on all three paths. Differences priced. |
| N2 | oh | A Spark 3.5 runtime built on the new Iceberg line. | I4, I6, N1 | T1 and T2 pass on it. |
| N1-c | oh | `SparkSqlSmokeTest` gains a third path, `rest-http` (`SparkCatalog type=rest`). Policy, grant and unlock cases pass. Differences are listed. | C2-3 | s16, s17 and s27 plus a test per SET POLICY form, on all three paths. |
| N3 | oh | **Done 2026-11-23 (N3a, N3-x1, N3-x2 and N3b).** A Spark 4.2 runtime, IRC only, with capabilities and SQL extensions. The closing proof is N3b (`50-n3b-spark42-smoke-result.md`). | I5, I6, V3, V4, V5-1, V5-2, V5-3 | T1 ported to Spark 4.2 passes. |
| N3-x1 | oh | **Done 2026-11-22 (pull request 101).** Result in `35-n3x1-spark42-extensions-result.md`. A Spark 4.2 and Scala 2.13 extensions module: ANTLR 4.13.1, the new parser methods, the resolver, `SQLConf.get`. It depends only on C2-1. | C2-2 | Ported parser and statement unit tests. |
| N3-x2 | oh | **Done 2026-11-23: the policy part with N3-x1 (pull request 101), the rest with C2-3 (pull request 103).** A Spark 4.2 itest: every OpenHouse statement over IRC, with no OpenHouse catalog class on the classpath. | N3-x1, V5-3 | A 4.2 itest beside the read itest, asserting stored state through the OpenHouse Java client. |
| N3b | oh | **Done 2026-11-23 (pull request 117, merge commit `c1303849`).** Result in `50-n3b-spark42-smoke-result.md`. Spark 4.2 on Iceberg 1.12 runs the Spark SQL smoke suite over IRC only (177 statements, 161 as on the baseline) and 18 write statements. | V3, N3a, C5 | The Spark SQL write statements pass in the Spark 4.2 module under the opt-in build. |
| N4 | oh | The native rename of a table onto a view's name answers 409 and leaves no orphan metadata files. Today it answers 500, writes five metadata files (one per commit retry) and takes about 1.8 s; the IRC rename goes through the same path. Pinned by a test as it is (D9). Found by V13b. | none | The native rename answers 409 and leaves no files. |
| N6 | oh | **Done 2026-11-23 (pull request 119, merge commit `92c5e117`; D37).** Result in `52-c9-n6-result.md`. `ALTER TABLE ... RENAME TO <catalog>.<db>.<t>` works on Spark 4.2 over IRC as on the baseline, and on Spark 3.5 over a plain REST catalog, fixed in the SQL extensions, not in the service. A target naming another catalog keeps the baseline's refusal. The `owner` property is the same on every line. | N3b | The 5 differing statements match the baseline; the smoke suite's declared differences shrank from 16 to 11. |
| N7 | oh | The Spark 4.2 smoke scenarios are a guarded copy of the Spark 3.5 suite's. The Spark 3.5 suite extends the shared scenarios and the copy check is deleted. Do after V9e. | N3b, V9e | One list of scenarios; `SmokeScenariosCopyTest` is gone; the Spark 3.5 `catalogTest` passes on both builds. |

## v3 functions

Each exists on the native path only (J3).

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| X1 | oh | **Done 2026-11-23 (with W6, pull request 124, merge commit `e2e02bfa`; D42, D43).** Result in `57-x1-format-v3-result.md`. Tables are created at v3 or upgraded from v2 through the table property, on both APIs, behind `cluster.iceberg.format-v3.enabled` (default off). The service returns v3 metadata as committed. | V9e, V3 | Round-trip tests on both APIs and both Spark lines; v2 tables untouched. |
| X2 | oh | **Partly proven by X1.** Deletion vectors are written and applied on read by Spark's default writers (DELETE, UPDATE, MERGE in merge-on-read) on Spark 3.5 and 4.2, tested at the SQL level. Remaining: the wider scenarios, and maintenance, which is X6. | X1, N2 or N3 | Delete, update and merge scenarios on a v3 table, beyond X1's tests. |
| X3 | oh | **Partly proven by X1.** Row lineage is maintained by INSERT, DELETE, UPDATE, MERGE and the first commit after an upgrade, and `_row_id` reads back, on Spark 3.5 and 4.2. Remaining: row ids kept across compaction (X6) and the wider scenarios. | X1, N2 or N3 | Row ids stable across a rewrite. |
| X4 | oh | **Done 2026-11-24 (with W7, pull request 126, merge commit `57db4015`).** Result in `61-w7-x4-result.md`. Native default values on v3 tables are stored as committed (W6) and read through Spark 3.5 and Spark 4.2 over old Parquet and ORC files. The v2 read bridge is untouched. | X1, N2 or N3 | Add a defaulted column, read old files: 31 new tests. |
| X5 | oh | New v3 types pass schema validation and column policies. Refused today with 400, naming X5 (`variant` is refused by request validation without naming it). | X1 | Create and evolve with each type. |
| X6 | oh | **Next, and the v3 item with the most risk.** Maintenance jobs (compaction, snapshot expiry, orphan-file removal) run on the new line and are correct for X2 to X5, with row ids kept across compaction. None has run on a v3 table, and a job that mishandles deletion vectors or row ids loses data. | V9e, I4, X2 | Each job on a v3 table loses no live data. |
| X7 | oh | Replication of v3 tables. Refused today with 400, naming X7. Investigate first (J24). | X1 | Defined by the investigation. |

## IRC scan planning and credentials (V11, V12)

Bottom line: V11a and V12a are done (pull request 127, merge commit `91e175c4`, `62-v11a-v12a-result.md`); the rest wait on Mike or on those two. Replaces the single
rows V11 and V12 of `04-work.md`. Decisions: D44 and D45 in `56-decisions-continued-5.md`; reasons in
`60-v11-v12-investigation-result.md`. Each black-box test is written first. "Mike's" marks a one-way door that is his to decide.

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| V11a | oh | **Done 2026-11-24 (with V12a, pull request 127, merge commit `91e175c4`).** Result in `62-v11a-v12a-result.md`. `POST .../plan` is served synchronously and answers `completed` with all tasks inline, a random plan id, no state on the service, `min-rows-requested` ignored, the same checks as a table load; listed in `endpoints`; nothing sets `scan-planning-mode`. Two-way door. | V9, V2 | A client with `scan-planning-mode=server` plans the same files, deletes and residuals as client planning, for filters, a snapshot id and a branch, on v2 with position and equality deletes and on v3 with deletion vectors; 403 and 404 as load; a client without the property sends no plan request; endpoint list test updated. |
| V11b | oh | Spark 3.5 and 4.2 over IRC with server planning pass the read smoke and the MERGE, UPDATE and DELETE writes. Two-way door. | V11a | The existing smoke and write suites rerun with the catalog property set give the same results as client planning. |
| V11c | oh | Large plans are bounded: stateless `plan-tasks` (snapshot plus manifest range in the token) served by `POST .../tasks`, or a task cap answered `failed`. Two-way door (the token format is internal). | V11a | A table whose task count is above the page size plans identically; a forged token answers 400. |
| V11d | oh | **Mike's decision.** The server sets `scan-planning-mode=server` per table or cluster. One-way for him: it changes every client's read path. | V11a | The load config carries it only where configured. |
| V12a | oh | **Done 2026-11-24 (with V11a, pull request 127, merge commit `91e175c4`).** Result in `62-v11a-v12a-result.md`. `GET .../credentials` is served after the load checks and answers an empty list; the access-delegation header is accepted and ignored. Two-way door. | V9, V2 | 200 with an empty list; 403 and 404 as load; the load response is the same with and without the header. |
| V12b | oh | **Mike's decision.** A decision record: which storages vend, the identity service (STS role, Entra), which privilege grants read and which write, credential lifetime, HDFS declared not applicable. | none (decision) | None; it is a decision. |
| V12c | oh | **Mike's decision.** S3 vending: STS credentials scoped to the table prefix in `storage-credentials`, refreshed through `/credentials`. One-way (new dependency, secret, contract). | V12b | A MinIO STS container test: the vended key reads this table's prefix and is refused on another table's. |
| V12d | oh | **Mike's decision.** S3 remote signing: `/sign` with a prefix check, and `s3.remote-signing-enabled` in the load config when requested. One-way (contract, service in the data path). | V12b | MinIO: a signed GET of this table's file works, another table's is 403. |
| V12e | oh | **Mike's decision.** ADLS SAS vending through config and the refresh endpoint. One-way. | V12b | Azurite: a SAS limited to the table directory. |
