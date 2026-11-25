# W7, X4: Spark reads through native default values on format v3 tables: record

Bottom line: done, CI green. Spark 3.5 (native API) and Spark 4.2 (IRC only) read old Parquet and ORC files
through native v3 defaults correctly in every scenario tested. The one failure was in OpenHouse: widening a
defaulted column's type was refused. It is fixed in `FormatVersionCommitPolicy`. Draft pull request 126, head
`82a40d9ab8292975424129ff901171b18eb72636`. CI run 45303138024 green on both jobs.

Worktree `/home/user/oh-w7`, branch `irc/w7-x4-v3-default-reads` from `origin/irc-integration` 72947228 (no newer
integration commits at push time, so no merge). Marks: [ran] run here; [read] read in code; [inferred] reasoning.

## Tests
- Spark 3.5: `integrations/spark/spark-3.5/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark/catalogtest/NativeColumnDefaultsSparkTest.java`
  (15). It uses its own embedded service with the v3 switch on, under catalog name `v3defaults`. The default-value
  API is called by reflection, because 1.5.2 lacks `addColumn(..., Literal)` and `updateColumnDefault`. It skips on
  the reference line.
- Spark 4.2 IRC: `integrations/spark/spark-4.2/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark42/irc/SparkIrcNativeDefaultsTest.java`
  (15), on catalog `ircv3` against the switch-on service process, with tables `irc42.nd_*`.
- Service: `FormatV3NativeApiTest.aTypeWideningCarriesTheInitialDefaultOverOnV3` (1), plus `FormatV3.withType`.
- Surface used to add the defaulted columns: the Iceberg API on both lines. SQL `ADD COLUMN ... DEFAULT` is refused by
  Iceberg's Spark integration on both lines, for v2 and v3 tables ("setting default values in Spark is currently
  unsupported") [ran].

## Results [ran]
- Spark 3.5, first run: 11 of 15 passed untouched. The 4 schema-change cases failed because of
  (a) RENAME and DROP COLUMN, which OpenHouse's schema-evolution rule refuses on every table. That is existing
  behaviour, now pinned as a refusal. (b) ALTER COLUMN TYPE bigint on an int column with a default:
  `COLUMN_INITIAL_DEFAULT_IMMUTABLE ... from 7 to 7` (Integer compared with Long). This was a bug in OpenHouse,
  now fixed.
- After the fix, Spark 3.5 passes 15 of 15 and Spark 4.2 passes 15 of 15. Spark 4.2 was not run before the fix.
- Spark 3.5 writes: a column-list INSERT that omits a defaulted column is refused ("Cannot find data for the output
  column"), and `DEFAULT` writes NULL. This is an Iceberg/Spark 3.5 limitation, pinned. Spark 4.2 writes the
  write-default.
- A vectorized Parquet scan of the primitive columns is columnar (`ColumnarToRow` in the plan) exactly when asked
  for. The ORC vectorized cases are not checked for columnar execution.
- Filters reach the Iceberg scan (`c_str = 'US'` and `c_int = 7` appear in the plan). Pruning and aggregate
  pushdown give correct answers on old files that lack the column.

## Commits
241a744f test Spark 3.5; 2d2e1972 test schema change within OpenHouse's rules; 883c5e60 service test;
4fe86b03 fix; ca80fbc8 test Spark 4.2; 82a40d9a plan check for vectorized reads.

## Counts on 82a40d9a, --rerun [ran] (tests/failed/skipped)
- services:tables: default 1666/0/1; LinkedIn 1666/0/33.
- Spark 3.5: catalogTest default 294/0/1, LinkedIn 294/0/22 (all 15 new tests skip); statementTest 137/0/0 on both
  lines; test 137/0/0 on both lines.
- Spark 4.2: test 63/0/0, extensionsTest 24/0/0, smokeTest 65/0/0.
- spotlessCheck: passes on both lines.

## Environment note
The commit hook runs `./gradlew spotlessCheck` with whatever `java` is on PATH. Here that is Java 21, and the check
fails under it. Export JAVA_HOME and put Java 17 first on PATH before committing.

## CI
Green on 82a40d9a: run 45303138024, build-run-tests success, iceberg-linkedin-build success.
