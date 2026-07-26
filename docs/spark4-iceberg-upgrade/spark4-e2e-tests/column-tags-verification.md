# Column policy-tag DDL — Spark-4.0 / Iceberg-1.11 REST-lane verification

Audit record for the ported `ALTER TABLE ... MODIFY COLUMN ... SET TAG` column policy-tag DDL,
verified end-to-end on the REST-first Spark-4.0 lane.

## Result

Column tags work **as-is on the REST lane — no code fix was required**. The ported exec + the
existing server `translatePolicyPatch` `columnTags` branch already persist column tags correctly.
Verified with a new test class, all methods green.

## DDL exercised

Grammar (`OpenhouseSqlExtensions.g4`, `columnPolicy` rule), valid tags are `PII` and `HC` only:

```sql
ALTER TABLE <t> MODIFY COLUMN <col> SET TAG = (PII)        -- single tag
ALTER TABLE <t> MODIFY COLUMN <col> SET TAG = (PII, HC)    -- multiple tags
ALTER TABLE <t> MODIFY COLUMN <col> SET TAG = (NONE)       -- clear form
```

## Chain: how it is parsed, carried, and persisted

1. **Parse.** `OpenhouseSqlExtensionsAstBuilder.visitSetColumnPolicyTag` builds the
   `SetColumnPolicyTag(tableName, colName, policyTags)` logical plan. `visitColumnPolicy` returns
   the tag names from `multiTagIdentifier` for the tag form, or `Seq.empty` for the `(NONE)` form.

2. **Carrier.** `SetColumnPolicyTagExec` (module
   `:integrations:spark:spark-4.0:openhouse-spark-4.0-runtime_2.13`) lowers the plan onto the
   reserved carrier property on the stock `RESTCatalog`:

   ```
   key   = updated.openhouse.policy
   value = {"columnTags":{"<col>": {"tags": [<PII|HC>, ...]}}}
   ```

   For `(NONE)` `policyTags` is empty, so the emitted carrier is `{"columnTags":{"<col>":
   {"tags": []}}}` — an **empty tags array**, not a tombstone. The tag tokens are written unquoted
   (e.g. `[PII, HC]`); this is accepted because the server parses the carrier with Gson in lenient
   mode (see below).

3. **Server merge.** `IcebergRestCatalogController.translatePolicyPatch` parses the carrier via the
   shared `PoliciesSpecMapper.toPoliciesObject` (Gson `fromJson`, which is lenient → unquoted
   `PII`/`HC` deserialize into `PolicyTag.Tag.PII` / `.HC`). The `columnTags` branch applies:

   ```java
   if (isClearedSubPolicy(patchObj, "columnTags")) {   // whole columnTags == {}
     merged.columnTags(null);
   } else if (patch.getColumnTags() != null) {
     merged.columnTags(patch.getColumnTags());          // override with patch map
   }
   ```

   The merged `Policies` is folded into the reserved `policies` property. Server model:
   `Policies.columnTags : Map<String, PolicyTag>`, `PolicyTag.tags : Set<Tag>` with `Tag ∈ {PII, HC}`.

4. **Readback.** The test reads the raw `policies` property string via `SHOW TBLPROPERTIES` and
   asserts on the serialized JSON (the itest module has no `Policies` gen-model on its compile
   classpath, matching `PolicySqlDdlTestSpark4_0`).

## Test class

`integrations/spark/spark-4.0/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark/catalogtest/ColumnTagsTestSpark4_0.java`
(package `com.linkedin.openhouse.spark.catalogtest`, extends `OpenHouseRestSparkITest`). Each method
creates its table, runs the DDL, reads back `policies`, asserts, and cleans up in a `finally` with
`DROP TABLE IF EXISTS`.

| Method | DDL | Assertions on the raw `policies` string |
|---|---|---|
| `testSetSingleColumnTagViaSqlDdl` | `SET TAG = (PII)` on `ssn` | contains `columnTags`, `ssn`, `PII` |
| `testSetMultipleColumnTagsViaSqlDdl` | `SET TAG = (PII, HC)` on `ssn` | contains `columnTags`, `ssn`, `PII`, `HC` |
| `testClearColumnTagViaSqlDdl` | `(PII)` then `(NONE)` on `ssn` | after set contains `PII`; after clear does **not** contain `PII` |

## `(NONE)` clear — supported, with a caveat

The `(NONE)` clear form **is wired and tested** (`testClearColumnTagViaSqlDdl`). Note the exact
semantics: the exec emits `{"columnTags":{"<col>":{"tags":[]}}}` (empty tag set for that column),
which the server merge treats as a normal override of the `columnTags` map — **not** the empty-object
tombstone (`isClearedSubPolicy` only fires when the whole `columnTags` value is `{}`). The observable
effect is that the previously-set tags for the column are gone (no `PII`/`HC`), which is what the
test asserts. The column key itself remains in the `columnTags` map with an empty `tags` array,
rather than the entire `columnTags` entry being removed. This matches the legacy behavior and is
sufficient as a "clear the tags" operation.

## Verification output

```
cd /home/user/openhouse && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 LANG=C.UTF-8 \
  && ./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
       --tests "*.ColumnTagsTestSpark4_0" -Dfile.encoding=UTF-8

ColumnTagsTestSpark4_0 > testSetMultipleColumnTagsViaSqlDdl() PASSED
ColumnTagsTestSpark4_0 > testSetSingleColumnTagViaSqlDdl() PASSED
ColumnTagsTestSpark4_0 > testClearColumnTagViaSqlDdl() PASSED

BUILD SUCCESSFUL
```

## Limitations / residuals

- `(NONE)` leaves the column key present with an empty `tags` array rather than dropping the
  `columnTags` entry entirely (see caveat above). No functional issue; noted for completeness.
- No code changes were needed; this item is verification-only.
