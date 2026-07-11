# delta-harness — known product bugs (follow-up)

Genuine OpenHouse/Iceberg behavior bugs surfaced by the harness. Each is tagged in
`Plan.knownBugs` (so its case reports `SKIP (bug: …)` instead of failing the suite) and listed
here for follow-up. A tagged case is a **deferred bug**, not a passing test — do not build further
coverage on top of the broken behavior.

| Case (id substring) | Reason | Found | Follow-up |
|---|---|---|---|
| `insert.explicitColumns` | `INSERT INTO t (foo_col_long, foo_col_string) VALUES …` is rejected with `INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_FIND_DATA: Cannot find data for the output column foo_col_int`. Vanilla Iceberg creates columns as *optional* and null-fills omitted columns on a partial-column INSERT. | Phase 4 | Confirm whether OpenHouse creates columns as **required** by default (a policy divergence from Iceberg's optional default), or whether Spark default-column fill is simply disabled here. If required-by-default is intended, this becomes a **negative** test (assert the rejection) rather than a bug. |
| `nested.deleteByNestedField` | `DELETE FROM t WHERE s.x = 2` (predicate on a nested struct field) fails with `[INTERNAL_ERROR] The Spark SQL phase optimization failed … NullPointerException`, across parquet/orc/avro. `SELECT … WHERE s.x = 2` and `UPDATE … SET s.x = …` on the same field both succeed, so it's specific to the DELETE plan. | Phase 6 | Genuine internal error (optimizer NPE), not a syntax/analysis rejection. Capture the full stack trace; likely an Iceberg/Spark rewrite-plan bug for row-level DELETE with a nested-field predicate. Report upstream once confirmed against a clean Iceberg+Spark 3.5. |
