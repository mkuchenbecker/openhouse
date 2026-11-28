# Iceberg fork: native column defaults, W2 (pins) and W1 (ORC fails on defaults it cannot fill)

Delegated agent, 2026-11-22. **[ran]** = executed in `/home/user/iceberg-w1`. **[read]** = from code.

## Bottom line

- Draft PR: https://github.com/mkuchenbecker/iceberg/pull/11 (`irc/native-defaults` into `openhouse-1.12.0`). It has 3 commits on top of d701a1b9b. `openhouse-1.12.0` already contains `irc/spark42-patches`, so the diff holds only these 3 commits.
  1. `20ce70a24` Spark 3.5, 4.2: Pin native column default behavior in tests (W2). Test-only, and green on the unchanged base.
  2. `dc89be3a2` ORC, Data, Spark 3.5, 4.2: Test that ORC readers fail on defaults they cannot fill (W1 tests). This commit is red by itself, and the message says so.
  3. `5a6a731ea` ORC: Fail on absent fields with defaults that a reader cannot fill (W1 code).
- **The design was wrong about Spark 4.2 ORC.** On this base the 4.2 port (c20993a7b, 8c4bb9094, b0c58fb49) already opts in: 4.2 `SparkFormatModels` uses `ORCFormatModel.createWithInitialDefaults`, and 4.2 `SparkBatch` has the vectorization fallback.
  - So Spark 4.2 fills ORC defaults today, the same as 3.5 [ran].
  - It was never "silently NULL", and after W1 it does not fail either.
  - The design read 4.2 on `irc/reapply-all`, before the port.
  - I did not add or remove any 4.2 ORC filling.
- After W1, these ORC readers fail with Apache's error instead of returning NULL: the generic reader, Flink, any Spark reader that does not opt in, and name-mapped or partly annotated files on every reader [ran]. Readers that opt in still fill the default on files that carry their own field ids [ran].

## Pinned behavior (all [ran], format v3 unless stated)

| Statement / operation | Spark 3.5 | Spark 4.2 |
|---|---|---|
| `CREATE TABLE ... (d string DEFAULT 'q')` | `AnalysisException [UNSUPPORTED_FEATURE.TABLE_OPERATION] ... Table ... does not support column default value.` | same |
| `ALTER TABLE ADD COLUMN c7 int DEFAULT 7` | `UnsupportedOperationException: Cannot add column c7 since setting default values in Spark is currently unsupported` | same |
| `ALTER COLUMN country SET DEFAULT 'ZZ'` | `UnsupportedOperationException: Cannot apply unknown table change: org.apache.spark.sql.connector.catalog.TableChange$UpdateColumnDefaultValue@…` | `UnsupportedOperationException: Cannot apply unknown table change: ALTER COLUMN country SET DEFAULT 'ZZ'` (**different text**) |
| `ALTER COLUMN country DROP DEFAULT` | same as SET, with the `@…` object text | `Cannot apply unknown table change: ALTER COLUMN country DROP DEFAULT` |
| API `addColumn(name, type, Literal)` | Sets `initial-default` and `write-default` to the same value in the metadata JSON | same |
| Old rows, Parquet row reader | default; `='US'` returns the old row; `IS NULL` returns only the explicit NULL | same |
| Old rows, Parquet vectorized | same | same |
| Old rows, Avro | same | same |
| Old rows, ORC row reader | same (opt-in) | same (opt-in, from the 4.2 port) |
| Old rows, ORC with vectorization on | same: falls back to the row reader | same |
| Nested struct-field default (`addColumn("loc","city",…,"X")`): Parquet, Avro, ORC | old row reads `X`; filter `loc.city='X'` returns it | same |
| API `updateColumnDefault(c, "XX")` | Changes `write-default` only; old rows still read `US` | same |
| `INSERT VALUES (…, DEFAULT)` | NULL | **the write-default** (`US`, or `XX` after a change) |
| `INSERT INTO t (id, name) …` (omits the defaulted column) | `AnalysisException [INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_FIND_DATA] … Cannot find data for the output column `country`` | **writes the write-default**; NULL when there is no write-default |
| `updateColumnDefault(c, null)` (design had no result) | Drops `write-default` only. JSON: `"initial-default":"US"}` with no write-default | same |
| initial-default with no write-default: round trip for Parquet, ORC and Avro | old rows `US`; `DEFAULT` writes NULL; filter works; metadata unchanged | same |
| v2 `addColumn` with a default | `IllegalStateException: Invalid schema for v2:\n- Invalid initial default for country: non-null default (US) is not supported until v3` | same |
| v2 `updateColumnDefault(c2, "W")` | Accepted; persists `"write-default":"W"` in v2 metadata; reads unchanged. The test comments that the OpenHouse service must refuse it (W5) | same |
| `SET TBLPROPERTIES('format-version'='3')` on v2 | upgrades | same |
| Then `'format-version'='2'` | `SparkException … Cannot downgrade v3 table to v2` | same |
| After the upgrade, add a default; old v2 Parquet rows | read the default (`[1,US]`) | same |

The tests are in `spark/v{3.5,4.2}/spark/src/test/java/org/apache/iceberg/spark/sql/TestNativeColumnDefaults.java`. Each test is named for the behavior it pins and has a one-line comment on why it matters to OpenHouse.

## What W1 changed

`ORCSchemaUtil.buildOrcProjection` restores Apache 1.12.0's checks for an absent field that was not omitted for a reader that fills defaults. It brings back Apache's `Schema root` parameter so errors name the column path.
- A non-null `initial-default` throws `UnsupportedOperationException: ORC cannot read default value for field <path> (<type>): <value>`.
- A required field with no default throws `IllegalArgumentException: Missing required field: <path> (<type>)`.
- An absent optional field with no default still reads NULL, both as a null column [ran] and through the generic reader [ran].
- Javadoc and comments in `ORC.java`, `OrcIterable.java` and `ORCSchemaUtil.java` are updated. Public signatures are unchanged, and revapi passes.

Which readers are affected:

| Reader | Before | After |
|---|---|---|
| Generic `IcebergGenerics` ORC, v3 table, old file | `[1,null]` | throws (new `TestGenericOrcInitialDefaults`) [ran] |
| Spark 3.5 / 4.2 row reader, file with all ids embedded | fills | fills (unchanged) [ran] |
| Spark 3.5 / 4.2, name-mapped file (no ids) | NULL | throws [ran] |
| Spark 3.5 / 4.2, partly annotated file | NULL | throws [ran] |
| Spark 3.5 / 4.2, name-mapped file that has the column | stored value | stored value (new test) [ran] |
| `SparkOrcReader` without `supportsInitialDefaults()` | NULL | throws (new test) [ran] |
| Flink ORC (`ORCFormatModel.create`) | NULL | throws [read]. Flink has no ORC default tests, and its existing ORC tests pass [ran] |
| Spark 4.0 / 4.1 ORC (no LinkedIn port, no opt-in) | NULL | throws, as Apache does [read, not run] |
| Batched / vectorized ORC | Spark never sends a projection that contains a default to it: `SparkBatch.hasNoInitialDefaults` | Unchanged. If a batched read ever reached such a field it would now throw. That is **safe**: it fails loudly rather than returning a wrong answer [read; the fallback result was ran] |

The search-argument guard from 39df55d00 (`ExpressionToSearchArgument`) is untouched, and `TestExpressionToSearchArgument` passes [ran].

## Decision on the two tests aligned by 5cce4931f

`TestBuildOrcProjection.testRequiredNestedFieldMissingInFile` and `testRequiredNestedFieldWithDefaultMissingInFile` now assert Apache's expectations again:
- `IllegalArgumentException "Missing required field: b.d (long)"`
- `UnsupportedOperationException "ORC cannot read default value for field b.d (long): 34"`

Evidence:
- The "LinkedIn" text `Field %d of type %s is required and was not found.` is upstream Apache 1.5.2 text (`apache-iceberg-1.5.2:orc/.../ORCSchemaUtil.java:330`). `li/openhouse-1.5.2:…:426` carries it unchanged, so it is not a LinkedIn patch [read].
- The fork has no main-code matches on that text, and neither does `/home/user/openhouse` [ran grep].
- For the with-default case, W1 itself requires Apache's `UnsupportedOperationException`. A LinkedIn-style `IllegalArgumentException` would contradict W1.
- For the no-default case, the exception type does not change, and restoring Apache's code block word for word keeps the fork's ORC code closest to upstream.

The same reasoning updated the LinkedIn-era analogues `testPublicProjectionRejectsRequiredFieldWithDefaultMissingInFile` and `testRequiredFieldWithoutDefaultMissingInFileIsRejected`. Three tests that asserted NULL synthesis were renamed and now assert the throw (non-opt-in, name-mapped, public 2-arg). Two tests were added: a nested non-opt-in throw, and a null-default optional field that still reads NULL. No test was skipped, disabled or deleted.

## Test counts (all [ran])

Fail, then pass:
- W2 on the base before W1: 3.5 `TestNativeColumnDefaults` 23/23 pass. 4.2: 24/24 pass after adjusting 5 assertions to what 4.2 actually does (first run: 5 failed). Both still pass after W1.
- W1 tests before the fix:

  | Class | Tests | Failed (as intended) |
  |---|---|---|
  | `TestBuildOrcProjection` | 20 | 8 |
  | `TestGenericOrcInitialDefaults` | 3 | 1 |
  | 3.5 `TestSparkOrcReaderForFieldsWithDefaultValue` | 8 | 3 |
  | 4.2 `TestSparkOrcReaderForFieldsWithDefaultValue` | 8 | 3 |

  All pass after the fix.

Suites after the fix (tests / skipped / failed):
- Full `:iceberg-orc:test`: 13 classes, 89 tests, 0 skipped, 0 failed.
- Full `:iceberg-data:test`: 27 classes, 808 tests, 85 skipped, 0 failed.
- Spark 3.5, filtered to `*Orc*`, `*ORC*`, `*Default*`, `TestSparkFormatModel` and `TestNativeColumnDefaults`: 42 classes, 823 tests, 174 skipped, 0 failed. Includes `TestSparkOrcInitialDefaultScan` 6, `TestSparkBatchScanInitialDefaults` 3 and `TestSparkFormatModel` 392.
- Spark 4.2, same filter: 46 classes, 944 tests, 181 skipped, 0 failed. Includes `TestSparkDefaultValues` 24, which exists only in 4.2.
- Flink 2.3 (newest supported; it needs `-DscalaVersion=2.12`, because there is no `flink-table-planner_2.13`): `TestFlinkOrcReaderWriter` 53 (27 skipped) and `TestFlinkFormatModel` 392 (26 skipped), 0 failed.

The skips are the existing assumption-based ones, such as ORC not declaring default support in shared bases.

Static checks: compile, plus `checkstyleMain checkstyleTest spotlessCheck` for orc, data, Spark 3.5 and Spark 4.2, plus `:iceberg-orc:revapi`, all pass. `revapiAnalyze` was served from the build cache.

## Where the adopted design was wrong when run

1. **Spark 4.2 ORC fills defaults.** The design said NULL silently, and expected the error after W1. Neither is true, because the 4.2 port opts in. The pinned outcome on 4.2 is the default.
2. **The 4.2 ALTER COLUMN SET/DROP DEFAULT message differs** from 3.5: it names the SQL, not the `TableChange` object.
3. **On 4.2, an INSERT with a column list that omits a defaulted column writes the write-default.** The design only covered the `DEFAULT` keyword.
4. **`updateColumnDefault(name, null)` works and drops only the write-default.** The design had no result for it. The initial-default-only field round-trips through Spark reads and writes for Parquet, ORC and Avro.
5. Confirmed rather than corrected: Avro and ORC fill nested struct-field defaults, and vectorized ORC falls back to the row reader.

## Not run

- Spark 4.0 and 4.1: nothing was run. After W1, their ORC readers throw for absent defaulted fields, which is Apache's behavior [read].
- Flink 1.20, 2.1 and 2.2.
- Full Spark 3.5 and 4.2 suites: only the filters above were run.
- Fork CI: not acted on.
- OpenHouse: not built.
- No PR subscription.

Logs, all under `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/`: `w2-35-run1.txt`, `w2-42-run1.txt`, `w2-42-run2.txt`, `w1-before-35.txt`, `w1-before-42.txt`, `w1-after-35.txt`, `w1-after-35b.txt`, `w1-after-42.txt`, `w1-checks-3.5.txt`, `w1-checks-4.2.txt`, `w1-flink23.txt`.
