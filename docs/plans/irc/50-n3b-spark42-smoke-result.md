# N3b result: the Spark SQL smoke suite runs on Spark 4.2 over IRC only
> Note, 2026-11-23: N6 (pull request 119) removed five of the 16 rows below, the rename rows of s13b and s29. 11 differences remain and 166 of 177 statements behave as on the baseline. The table below is the record as of pull request 117 and is not rewritten. See `52-c9-n6-result.md`.


Bottom line: the Spark SQL smoke suite (31 scenarios, 177 statements) runs on Spark 4.2 with Iceberg 1.12 over the
Iceberg REST Catalog only, with no OpenHouse catalog or client class in the engine. 161 statements give the Spark
3.5 baseline outcome. 16 differ: 5 follow from a recorded decision or from the goal, 2 are Spark 4 itself, 9 come
from the IRC path, none is Iceberg 1.12 alone, and none is a defect. Of the 9, 5 are one fixable problem (N6, D37)
and 4 are accepted. 18 further write statements store what they should. This closes N3. Written 2026-11-23 from the
agent's report. One-way door for Mike: the accepted differences become user-visible behaviour of the Spark 4.2
platform at release; the list is below for him to veto.

- Pull request 117 into `irc-integration`, merge commit `c1303849f3baf92b95896b75317171886768fec8`.
- CI on the last head: the default build and the Iceberg 1.12 job both passed; the new smoke task ran 58 tests.
- A difference that is not declared fails the suite, and each way of hiding one was provoked by hand and failed.

## The 16 differing statements

Baseline is Spark 3.5 with the OpenHouse catalog. Class: (a) a decision or the goal, (b) Spark 4 itself, (d) the
IRC path. The `Same?` column of the full 177-row table is in the agent's report only.

| Scenario and number | Statement | Baseline | Spark 4.2 over IRC | Class |
| --- | --- | --- | --- | --- |
| s13b 3 | RENAME TO a target with the catalog name | succeeds | refused, "namespaces are databases and have exactly one level" | d, N6 |
| s13b 4 | read the old name after that rename | not found | rows | d, follows 3 |
| s13b 5 | read the new name after that rename | rows | not found | d, follows 3 |
| s13b 6 | SHOW TABLES after that rename | new name | old name | d, follows 3 |
| s29 3 | RENAME TO another catalog's name | refused, "across catalogs" | refused, the one-level message | d, N6 |
| s19 3 | CREATE NAMESPACE | refused | refused, "Server does not support endpoint" | a, D20 |
| s24 3 | ALTER NAMESPACE SET DBPROPERTIES | refused | refused, same new wording | a, D20 |
| s24 4 | DROP NAMESPACE | refused | refused, same new wording | a, D20 |
| s27 3 | UNLOCK on a missing table | OpenHouse client class | Iceberg `NoSuchTableException`, same message | a, goal |
| s29 2 | RENAME to the same name | OpenHouse client class | Iceberg `BadRequestException`, same message | a, goal |
| s24 2 | DESCRIBE NAMESPACE db | refused | rows (catalog and namespace name) | d |
| s24 5 | SHOW NAMESPACES IN db | refused | no rows | d |
| s25 1 | SHOW TABLES IN the catalog | OpenHouse exception | Iceberg `NoSuchNamespaceException` | d |
| s25 3 | SHOW TABLES with no database | OpenHouse exception | the same Iceberg exception | d |
| s22 11 | snapshot operations after RTAS | append, append | append, overwrite | b |
| s26 2 | DESCRIBE TABLE EXTENDED | has a `Name` row | no `Name` row; `Catalog`, `Namespace`, `Table` rows | b |

How the five class (a) rows were placed: no numbered decision names the exact outcome of the last two. A stricter
reading would put all five in class (d). D37 records them as accepted either way.

## What "same as baseline" means, and what was not compared

- Same means the baseline's own assertion holds, as the Spark 3.5 suite states it: rows exactly, and for a failure
  the exception class and a fragment of its message.
- Full message texts of failures were not compared with a Spark 3.5 run. Spark 4 appends a SQLSTATE and a search
  path to its own messages, and a native 400 over IRC reads "Malformed request:" before the native message.
- The 18 write statements were not compared with a Spark 3.5 run; they assert what the service stored on Spark 4.2.
- Not run locally: the full build, the Spark 3.5 and Spark 3.1 test projects, the service tests.

## The 18 extra write statements

They assert stored state, read through the OpenHouse Java client in the service JVM. All 18 pass.

- INSERT INTO; INSERT OVERWRITE with a partition, without one, and in dynamic mode.
- MERGE INTO copy-on-write and merge-on-read (one delete file applied); UPDATE; DELETE.
- CREATE TABLE AS SELECT; CREATE OR REPLACE TABLE AS SELECT with replace enabled (properties kept, replaced
  snapshot kept outside main) and not enabled (refused, nothing changed).
- ADD COLUMN works; RENAME COLUMN and DROP COLUMN are refused by the native schema rule, as on every path.
- SET and UNSET TBLPROPERTIES, and an `openhouse.` key refused.
- A branch with a write to it; a write-audit-publish write by id then cherry-pick; a write-audit-publish branch.

## Open finding

Tables that Spark 4.2 creates over IRC store a property `owner=root`. `SHOW TBLPROPERTIES` hides it on both
lines, so whether the baseline stores it is unknown. N6 is investigating (`21-findings-continued.md`, twelfth round).

## How the suite is shared

The scenarios in the Spark 4.2 module are a copy of the Spark 3.5 suite's, because the brief said not to edit that
project. A check fails when a scenario name or string literal drifts, apart from five listed deviations. N7 makes
the Spark 3.5 suite extend the shared scenarios and deletes the copy check; it follows V9e.

## Counts

The new `smokeTest` task has 58 tests: smoke 31, write statements 18, without OpenHouse 4, classpath 3, copy check
2. The module's `test` (40) and `extensionsTest` (24) pass. The task has its own source set so the existing
classpath test stays true. A third test service uses the service's own authorization handler, as the baseline did.
