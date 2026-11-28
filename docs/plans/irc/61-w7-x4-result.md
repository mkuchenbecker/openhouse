# W7 and X4: Spark reads through native default values on format v3 tables: result

Bottom line: done and merged (pull request 126, merge commit `57db4015ff0085f1cce6ca9c5a54ee09150a876b`, 2026-11-24).
On a format v3 table, Spark 3.5 (native API) and Spark 4.2 (IRC only) read old Parquet and ORC files through native
defaults correctly in every scenario tested. One bug was found in OpenHouse and fixed: widening the type of a column
that carries an `initial-default` was refused. Two limitations of Iceberg's Spark integration are pinned, not fixed.
CI on the pull request head `82a40d9a` passed both jobs (run 45303138024). Source: the agent's record and the merge
message; "[ran]" marks a claim run by the agent, "[inferred]" one reasoned, not run. Dated 2026-11-24.

## What was tested

The scenario was the same on both Spark lines: write rows, add a defaulted column through the Iceberg API, then read
the old rows. Every row below passed after the fix.

| Scenario | Spark 3.5 (native API) | Spark 4.2 (IRC) | Parquet | ORC |
| --- | --- | --- | --- | --- |
| Old rows read the `initial-default` (int, long, string, boolean, decimal, date, timestamp, struct field) | pass | pass | yes | yes |
| Row reader | pass | pass | yes | yes |
| Vectorized reader | pass | pass | yes, checked in the plan | yes, not checked for columnar execution |
| Projection, filter reaching the Iceberg scan, aggregate, query selecting only the defaulted column | pass | pass | yes | yes |
| After a later schema change within OpenHouse's rules | pass | pass | yes | yes |
| After row-level DELETE and UPDATE, copy-on-write and merge-on-read | pass | pass | yes | yes |
| An explicit NULL stays NULL | pass | pass | yes | yes |

- Tests: `NativeColumnDefaultsSparkTest` (15, Spark 3.5, its own embedded service with the v3 switch on, catalog
  `v3defaults`), `SparkIrcNativeDefaultsTest` (15, Spark 4.2 over IRC, catalog `ircv3`), and
  `FormatV3NativeApiTest.aTypeWideningCarriesTheInitialDefaultOverOnV3` (1, service level). 31 in all.
- Spark 3.5 calls the default-value API by reflection, because Iceberg 1.5.2 lacks `addColumn(..., Literal)` and
  `updateColumnDefault`. The 15 Spark 3.5 tests skip on the reference line.

## The one behavior change

On v3 tables only, a commit that widens the type of a column carrying an `initial-default` (int to long, float to
double, a wider decimal) is now accepted, and the value is converted as Iceberg converts it. Before, it was refused with
400 `COLUMN_INITIAL_DEFAULT_IMMUTABLE` ("from 7 to 7"), because `Integer(7)` was compared with `Long(7)`. The fix is in
`FormatVersionCommitPolicy` (commit `4fe86b03`). A real change of the value is still refused. v2 tables and the older
default mechanism (the read bridge) are untouched. The Spark 3.5 case failed before the fix [ran]. Spark 4.2 was not run
before the fix; that it would have failed is [inferred] from the shared service rule.

## Pinned limitations

- Spark 3.5 does not write a `write-default`. A column-list INSERT that omits a defaulted column is refused ("Cannot
  find data for the output column") and `DEFAULT` writes NULL. Spark 4.2 writes the `write-default`. This is Iceberg's
  Spark 3.5 integration, not OpenHouse. Pinned by `spark35WritesNoWriteDefault`; recorded as work item W14.
- SQL `ADD COLUMN ... DEFAULT` is refused by Iceberg's Spark integration on both lines, on v2 and v3 tables ("setting
  default values in Spark is currently unsupported") [ran]. A defaulted column is added through the Iceberg API (W9).
- RENAME and DROP COLUMN stay refused by OpenHouse's schema-evolution rule on every table, as before. Now pinned.

## Evidence on `82a40d9a`, JDK 17, `--rerun` [ran]

Tests, failed, skipped. The 1.12 line first, then the reference line (`-PicebergLinkedIn`).

| Task | 1.12 line | Reference line |
| --- | --- | --- |
| `:services:tables:test` | 1666 / 0 / 1 | 1666 / 0 / 33 |
| Spark 3.5 `catalogTest` | 294 / 0 / 1 | 294 / 0 / 22 |
| Spark 3.5 `statementTest` | 137 / 0 / 0 | 137 / 0 / 0 |
| Spark 3.5 `test` | 137 / 0 / 0 | 137 / 0 / 0 |
| Spark 4.2 `test`, `extensionsTest`, `smokeTest` | 63 / 0 / 0, 24 / 0 / 0, 65 / 0 / 0 | not applicable |

`spotlessCheck` passed on both lines. The first Spark 3.5 run passed 11 of 15; the four schema-change cases failed, on RENAME
and DROP COLUMN (refused by OpenHouse's rule, now pinned as refusals) and on the widening bug (now fixed).

## Known gaps

- Whether the ORC vectorized cases run columnar was not checked.
- Not covered: Avro, defaults inside lists and maps, and ORC writes on Spark 4.2.
- Real storage (S3, ADLS) and the delta-harness were not run.
- The migration of existing tables to native defaults (W8, one way per table) is untouched.
- The commit hook runs `spotlessCheck` with the `java` on PATH; under Java 21 it fails, so use Java 17.
