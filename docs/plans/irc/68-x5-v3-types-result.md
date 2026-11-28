# Result of X5: the column types of format v3

Bottom line: pull request 129 merged on 2026-11-24 (merge commit `70b35d7a`, pull request head `fa9dbf4b`). Each
column type that format v3 adds is now supported or refused with a reason, type by type. `unknown` is supported.
`variant` is supported only when the cluster property `cluster.iceberg.format-v3.variant.enabled` is on, and it is off by
default (D48 in `67-decisions-continued-6.md`). `timestamp_ns`, `timestamptz_ns`, `geometry` and `geography` are refused.
Format v2 tables, clusters with the v3 switch off and the LinkedIn Iceberg 1.5.2 line answer as before. Marks: [ran]
was run, [read] was read in code, [inferred] is judgement. Sources: the author's record and the merge message.

## Type by type

All answers are for format v3 tables on a cluster with `cluster.iceberg.format-v3.enabled` on, Iceberg 1.12 line.
A refusal is a 400 with the token `FORMAT_V3_TYPE_NOT_SUPPORTED` and a message naming the type and the reason [ran].

| Type | Decision | Spark 3.5 | Spark 4.2 over the Iceberg REST routes | File formats | What refuses it |
| --- | --- | --- | --- | --- | --- |
| `unknown` (Spark's `void`) | Supported | Create, add, write, read [ran] | Create, add, write, read [ran] | Every format; nothing stores it | Nothing |
| `variant`, property on | Supported on some tables | Cannot read it: SELECT and DESCRIBE fail, SHOW TABLES lists the table [ran] | Create, CTAS, add column, `parse_json`, `variant_get` filter, DELETE [ran] | Parquet and Avro. No Spark ORC writer, and the ORC reader fails on old ORC files once the column exists | ORC tables; a branch or tag head that still has ORC data files; a retention policy, in either order |
| `variant`, property off (default) | Refused for new columns | Not applicable | Not applicable | Not applicable | The property, on create and on evolution, native API and REST routes |
| `timestamp_ns`, `timestamptz_ns` | Refused | No Spark integration of Iceberg 1.12 has the type | Same | None | Always |
| `geometry`, `geography` | Refused | Cannot read them | Only behind `spark.sql.geospatial.enabled`, Parquet only | Parquet only on Spark 4 | Always; bounds also break Spark 3.5 metadata tables |

How each decision was reached [read]: Iceberg 1.12 marks the v3-only types in `Schema.MIN_FORMAT_VERSIONS`; Spark 3.5's
`TypeToSparkType` maps `unknown` to `NullType` and throws on the rest; Spark 4.2's maps `variant`, `unknown` and (in the
fork, behind the flag) `geometry` and `geography`, and has no `timestamp_ns`. OpenHouse's cluster default write format is ORC.

## The `variant` property

- Name `cluster.iceberg.format-v3.variant.enabled`, default false; it is documented in `docs/development/iceberg-fork.md`.
- With it off, a new `variant` column is refused with 400 `FORMAT_V3_TYPE_NOT_SUPPORTED`, on create and on evolution,
  on the native API and the Iceberg REST routes, with the same message on both (asserted by `FormatV3VariantOffTest` [ran]):
  `FORMAT_V3_TYPE_NOT_SUPPORTED: column v (variant) of table <db>.t: variant columns are off on this cluster
  (cluster.iceberg.format-v3.variant.enabled is false): a variant column can never be dropped, and the maintenance jobs,
  which run on Spark 3.5, cannot read, compact or apply retention to a table that has one.`
- A table that already has a `variant` column keeps it and is not blocked when the property is turned off again. Only
  new `variant` columns are refused (a detail decided during implementation).
- Why it exists: OpenHouse refuses DROP COLUMN, so a `variant` column is permanent, and the maintenance jobs run on
  Spark 3.5, which cannot read it. Turning on the v3 switch must not open that by itself.

## Maintenance jobs on a `variant` table holding data and deletion vectors

No job loses or changes data [ran, Iceberg 1.12, `FormatV3VariantJobsTest` 8/0/0 on `17689d24`]. Every post-job
validation fails with "Unsupported type: variant", because it reads the table through Spark 3.5, so each scheduled job
reports failure on every run.

| Job | What it does | After the job |
| --- | --- | --- |
| Compaction, production arguments (table name only) | Plans no file group (the fixture's files are small), commits nothing | Validation fails |
| Compaction, strict arguments (minInputFiles 2, deleteFileThreshold 1) | Every file group fails to read in Spark 3.5; the action logs the failures, reports 0 files rewritten without throwing, commits nothing | Validation fails |
| Snapshot expiry | Does its work correctly | Validation fails |
| Orphan files deletion, single and batched | Does its work correctly | Validation fails |
| Staged files deletion | Does its work correctly | Validation fails |
| Table stats | Collects from metadata, changes nothing | Validation fails |
| Layout strategy generation, sort stats | Fail in their own work, commit nothing | Validation fails |
| Retention | Refused in both directions: a policy cannot be set on a `variant` table, and a `variant` column cannot be added to one with a policy | Not applicable |

Not run: a production-arguments compaction that actually plans file groups ([inferred] to fail like the strict case),
batched table stats, orphan directory deletion.

## What was checked to conclude other answers did not change

- Before any change, the first commit pinned today's answers by tests; the pins pass at the head [ran].
- Format v2 tables still answer native `variant` with 400 "schema : Unsupported type: variant" and other v3 types with
  Iceberg's 500 "Invalid schema for v2". Switch-off clusters answer as before [ran].
- The Iceberg 1.5.2 line was run with `-PicebergLinkedIn` on the services, apps and Spark 3.5 suites and in CI [ran].
- Internal change with the same output: the duplicate-column walk and the casing normalization are explicit walks
  instead of Iceberg's visitor [read]. One edge, [inferred] and not tested: on the 1.12 line a v2 request with several
  validation errors that includes a `variant` column reports the `variant` error only after the others pass.

## Evidence counts (tests / failed / skipped), merged head `fa9dbf4b`

| Build line | Services | Spark 4.2 `test` | Apps X5 classes | Compatibility kit |
| --- | --- | --- | --- | --- |
| Iceberg 1.12 (default) | 1714/0/1 | 115/0/0 | 12/0/0 | 41 passed, 96 failed, 23 skipped, as expected |
| Iceberg 1.5.2 (`-PicebergLinkedIn`) | 1692/0/50 | not applicable | not recorded at this head | not recorded |

On earlier heads [ran]: internalcatalog 327/0/0 on both lines; full apps suite 389/0/5 on 1.12 and 389/0/53 on 1.5.2;
Spark 3.5 `catalogTest` 333/0/1 on 1.12 and 333/0/56 on 1.5.2; Spark 4.2 extensions 24 and smoke 65. Spotless passed.
CI on the pull request passed both jobs on `fa9dbf4b`. Post-merge CI for 129 has not been read yet.

## Known gaps and questions for Mike

- With the property on there is no compaction and every job reports failure until the jobs run on Spark 4 (work item N11).
- Spark 4.2 extension statements (tags, SET POLICY) on these types were not run; Avro on Spark 3.5 was not run (the
  test JVM cannot write Avro at all).
- v2 tables answer 500 "Invalid schema for v2" to a v3-only type, and a default value on a `variant` or `unknown`
  column answers 500 on every table version. A 400 would change v2 answers, so both are put to Mike (`58-waiting-on-mike.md`).
- Whether and when to turn the `variant` property on for a real cluster is Mike's call.
