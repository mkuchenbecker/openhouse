# Work: native path, platforms and v3 functions

Moved out of `04-work.md` to keep that file under 150 lines. Columns and rules are as there.

## Native path and platforms

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| N1 | oh | The same config value selects native REST over HTTP on Spark 3.5. | C2-3 (done), V3, V4, V5-3 | T1 identical on all three paths. Differences priced. |
| N2 | oh | A Spark 3.5 runtime built on the new Iceberg line. | I4, I6, N1 | T1 and T2 pass on it. |
| N1-c | oh | `SparkSqlSmokeTest` gains a third path, `rest-http` (`SparkCatalog type=rest`). Policy, grant and unlock cases pass. Differences are listed. | C2-3 | s16, s17 and s27 plus a test per SET POLICY form, on all three paths. |
| N3 | oh | A Spark 4.2 runtime, IRC only, with capabilities and SQL extensions. | I5, I6, V3, V4, V5-1, V5-2, V5-3 | T1 ported to Spark 4.2 passes. |
| N3-x1 | oh | **Done 2026-11-22 (pull request 101).** Result in `35-n3x1-spark42-extensions-result.md`. A Spark 4.2 and Scala 2.13 extensions module: ANTLR 4.13.1, the new parser methods, the resolver, `SQLConf.get`. It depends only on C2-1. | C2-2 | Ported parser and statement unit tests. |
| N3-x2 | oh | **Done 2026-11-23: the policy part with N3-x1 (pull request 101), the rest with C2-3 (pull request 103).** A Spark 4.2 itest: every OpenHouse statement over IRC, with no OpenHouse catalog class on the classpath. | N3-x1, V5-3 | A 4.2 itest beside the read itest, asserting stored state through the OpenHouse Java client. |
| N3b | oh | Spark 4.2 on Iceberg 1.12 writes OpenHouse tables over IRC. | V3, N3a, C5 | The Spark SQL write statements pass in the Spark 4.2 module under the opt-in build. |
| N4 | oh | The native rename of a table onto a view's name answers 409 and leaves no orphan metadata files. Today it answers 500, writes five metadata files (one per commit retry) and takes about 1.8 s; the IRC rename goes through the same path. Pinned by a test as it is (D9). Found by V13b. | none | The native rename answers 409 and leaves no files. |

## v3 functions

Each exists on the native path only (J3).

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| X1 | oh | Tables can be created at v3 or upgraded from v2, gated per table. The service returns v3 metadata exactly as committed. | V9e, V3 | Round-trip test. v2 tables untouched. |
| X2 | oh | Deletion vectors written and read through Spark SQL. | X1, N2 or N3 | Delete, update and merge scenarios on a v3 table. |
| X3 | oh | Row lineage maintained across writes and compaction. | X1, N2 or N3 | Row ids stable across a rewrite. |
| X4 | oh | Native default values on v3 tables. The v2 read bridge is untouched. See W7. | X1, N2 or N3 | Add a defaulted column, read old files. |
| X5 | oh | New v3 types pass schema validation and column policies. | X1 | Create and evolve with each type. |
| X6 | oh | Maintenance jobs run on the new line and are correct for X2 to X5. | V9e, I4, X2 | Each job on a v3 table loses no live data. |
| X7 | oh | Replication of v3 tables. Investigate first (J24). | X1 | Defined by the investigation. |
