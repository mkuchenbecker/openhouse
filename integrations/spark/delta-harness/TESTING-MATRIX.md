# Delta harness standard testing matrix

The standard catalog contains 1,181 cases over copy-on-write tables. It covers reusable DML
operations, bespoke DDL and state transitions, standard readers and writers, maintenance,
control-plane behavior, and implementation pins.

RTAS, merge-on-read, branch, and WAP add their own preparations and cases in stacked child branches.
They are not part of this matrix.

## Case identity

A preparation-backed case usually has this shape:

```text
<optional preparation prefix><operation> @ <layout>
```

Examples:

```text
read.projection @ unpartitioned/parquet
prep.ordered:update.byPredicate @ partitioned/orc
prep.evolved:delete.byPredicate @ unpartitioned/avro
```

The operation identifies the behavior under test. The layout identifies the table shape. A prefix
identifies an additional preparation transition when that transition changes the starting state.

Bespoke families use the same stable-ID rule with a context suffix such as `@ parquet`,
`@ orc`, or `@ embedded`.

## Standard matrix axes

The axes table shows which dimensions can change the starting state or execution path of a standard
case.

| Axis | Standard values | Meaning |
|------|-----------------|---------|
| Operation | Read, insert, overwrite, delete, update, merge, DDL, maintenance, reader/writer, control, interaction, surface, hazard, and implementation pins | The behavior and assertion owned by the localized case. |
| File format | `parquet`, `orc`, `avro` | The `write.format.default` used by the preparation. Bespoke cases use the formats relevant to their contract. |
| Partitioning | Unpartitioned, partitioned by `datepartition` | The table layout before the operation. |
| Schema | `CoreTable`, `NestedTable`, `TypesTable` | The typed columns available to the operation. |
| Preparation state | Base, ordered, evolved, null-string, empty, or another bespoke standard state | The complete state the test receives. |
| Execution surface | Spark SQL, DataFrame writer, metadata table, procedure, streaming reader or writer, or REST lock operation | The customer-facing path the case exercises. |

Matrix assembly names compatible lists directly. It does not parse case IDs or use abstract capability
flags.

## Schemas

### CoreTable

`CoreTable` is the common DML and DDL schema.

| Column | SQL type |
|--------|----------|
| `foo_col_long` | `bigint` |
| `foo_col_int` | `int` |
| `foo_col_string` | `string` |
| `foo_col_double` | `double` |
| `foo_col_boolean` | `boolean` |
| `datepartition` | `string` |

The generated rows use the long column as the ordering key. The date partition value advances by one
hour per row from a fixed timestamp.

### NestedTable

`NestedTable` covers nested values.

| Column | SQL type |
|--------|----------|
| `id` | `bigint` |
| `s` | `struct<x:int,y:string>` |
| `arr` | `array<int>` |
| `m` | `map<string,int>` |
| `nested` | `struct<inner:struct<z:int>>` |

### TypesTable

`TypesTable` covers scalar and temporal edge cases.

| Column | SQL type |
|--------|----------|
| `id` | `bigint` |
| `n` | `int` |
| `x` | `double` |
| `dec` | `decimal(10,2)` |
| `str` | `string` |
| `bin` | `binary` |
| `dt` | `date` |
| `ts` | `timestamp` |
| `tsntz` | `timestamp_ntz` |

Every generated value is a pure function of its row index, so preparation data is deterministic.

## Layouts

The layout collections decide which format and partitioning combinations each family receives.

| Collection | Labels | Use |
|------------|--------|-----|
| `layouts` | `{unpartitioned,partitioned}/{parquet,orc,avro}` | The six standard copy-on-write `CoreTable` layouts. |
| `partitionedLayouts` | `partitioned/{parquet,orc,avro}` | Cases whose operation specifically requires partitioning. |
| `parquetAndOrcLayouts` | `{unpartitioned,partitioned}/{parquet,orc}` | Bespoke cases whose contract needs both columnar formats. |
| Nested layouts | Nested schema crossed with the applicable formats | Nested DML and schema behavior. |
| Type layouts | Type-edge schema crossed with the applicable formats | Scalar, binary, decimal, date, and timestamp behavior. |

Each layout has a stable label and the complete `CREATE TABLE` statement. Scaladoc above its
generator and collection describes the resulting table shape.

## Preparation collections

The preparation collections state the exact table state available before a reusable test starts.

| Collection | Starting state |
|------------|----------------|
| `preparedCoreTables` | Three deterministic rows in each of the six standard layouts. |
| `preparedPartitionedCoreTables` | Three deterministic rows in each partitioned layout, with one row per `datepartition` value. |
| `preparedOrderedCoreTables` | The standard three rows after `ALTER TABLE WRITE ORDERED BY foo_col_long`. |
| `preparedEvolvedCoreTables` | The standard three rows after `ADD COLUMN prep_extra int`. |
| `preparedNullStringCoreTables` | A base preparation plus one row whose string value is null. |
| `preparedNullStringOrderedCoreTables` | An ordered preparation plus one row whose string value is null. |
| `preparedEmptyCoreTables` | A standard layout created with no seed rows. |
| `preparedCoreFormats` | Three rows in unpartitioned Parquet and ORC tables for format-focused bespoke cases. |
| `layoutFormatPreparations` | Base and ordered preparations whose written file extensions are verified. |
| `ddlConsumerPreparations` | Evolved table states used by the DDL consumer battery. |

Scaladoc above each named preparation states this resulting state directly. Test documentation can
therefore focus on the operation and observable result.

## Reusable DML operations

The standard source contains 54 reusable `DmlTestCase` definitions. `allDmlTestCases` contains the
51 operations compatible with any three-row seed-shape preparation. One null-string delete and two
partition-scoped writes run only on their matching preparations.

| Family | Representative cases | Contract |
|--------|----------------------|----------|
| Read | `read.projection`, `read.filter` | Return the expected rows and leave rows and snapshots unchanged. |
| Delete | `delete.byPredicate`, subquery forms, alias, partition predicate, truncate, delete-all, delete-none | Remove exactly the selected rows and assert the expected snapshot delta. |
| Update | Predicate, subquery, alias, multiple columns, expressions, partition movement, null assignment | Assert the complete row set with exactly the selected values changed, plus the relative snapshot delta. |
| Merge | Insert, update, delete, upsert, conditional clauses, stars, explicit columns, source CTE, set operation, empty target, null join, by-name resolution | Assert the complete target rows after all matched and unmatched clauses, plus the relative snapshot delta. |
| Insert and overwrite | SQL insert, explicit columns, insert-select, DataFrame append, SQL overwrite, DataFrame overwrite | Assert the complete appended or replaced row set and the relative snapshot delta. |
| Null handling | `delete.byNullCondition` | Remove exactly the rows whose string value is null. |
| Partitioned writes | `insert.dynamicOverwrite`, `overwrite.partitions` | Replace only the partitions carried by the write and preserve every other partition. |

`delete.byNullCondition` runs only on null-string preparations. Additional partitioned-table operations
run only on `preparedPartitionedCoreTables`.

### Compatibility lists

The compatibility lists are part of the readable contract:

| Collection | Members |
|------------|---------|
| `allDmlTestCases` | The 51 operations compatible with any three-row seed-shape preparation. |
| `rowMutationTestCases` | Deletes, updates, and merges. |
| `testCasesCompatibleWithAnAddedColumn` | Reads, deletes, and updates. |
| `readTestCases` | The two non-mutating read cases. |
| `nullStringRowTestCases` | The null-condition delete. |
| `orderedDmlTestCases` | The standard DML order with explicit known-bug metadata on the affected ordered-table cell. |

The matrix builders declare compatibility by crossing these named lists with named preparation
collections.

## Preparation validation

`format.materialization` is a preparation check, not a DML operation. It verifies that every data file
written by a base or ordered preparation has the extension declared by `write.format.default`. Listing
the files must leave rows and snapshot count unchanged.

Each `TableTest` preparation step can also validate its immediate before and after rows and snapshot
counts. This prevents a later test from passing against an empty or malformed starting table.

## Bespoke standard families

These families remain localized because the setup or state transition is part of the behavior.

| Family | Standard scope |
|--------|----------------|
| Nested and types | DML over structs, arrays, maps, nested structs, decimals, binary values, nulls, boundaries, dates, timestamps, and timestamp-without-time-zone values. |
| Partition transforms and evolution | Transform coverage and rejected or accepted partition-spec changes. |
| Time travel, restore, and rollback | Reads and state restoration across standard copy-on-write snapshots. |
| Schema DDL | Add, alter, rename, reorder, and reject invalid column changes. |
| Table DDL | Properties, sort order, table rename, namespace rejection, policies, CTAS, tags, ACLs, feature properties, and encryption pins. |
| Maintenance | Snapshot expiration, data-file rewrite, orphan-file removal, and their state-preservation assertions. |
| Control plane | REST lock and unlock behavior. |
| DDL consumers | A state-changing DDL followed by data, metadata, and compaction consumers. |
| Reader and writer | Changelog, incremental, structured-streaming reader, and structured-streaming writer behavior for standard copy-on-write tables. |
| Interaction, surface, and hazard | Multi-operation compositions, metadata tables, procedures, concurrency, schema edges, and state-preservation hazards. |
| Fork and implementation pins | LinkedIn Iceberg fork behavior and the plaintext data-file pin that belong to the standard layer. |
| Negatives | Invalid columns, nondeterministic expressions, arity mismatches, merge conflicts, partition errors, and other required rejections. |

## Reusable DML assertion rules

Every reusable DML mutation follows this shape:

1. Capture `before = table.state`.
2. Execute one operation.
3. Capture `after = table.state`.
4. Assert the complete expected row set.
5. Assert the relative snapshot delta.

Additional rules:

- Read-only and metadata-only cases assert `after == before`.
- Rejection cases assert the expected exception or message and any relevant unchanged state.
- DML snapshot oracles use deltas relative to the prepared table.
- Expected rows are ordered by the schema key before comparison.
- Known product bugs stay explicit on the exact `Plan.Case` or `DmlTestCase`.
- Embedded-only limitations stay explicit on the exact `Plan.Case`.

Bespoke cases assert the rows, snapshots, metadata, or rejection state relevant to their family.
Scaladoc immediately above each named `Plan.Case` or `TablePreparation.test` builder describes the
operation and observable result. Preparation Scaladoc separately describes the starting table state.

## Ordered catalog

`Plan.cases` preserves the historical case order while scenario traits own the case bodies. The
standard baseline is:

```text
count=1181
sha256=377f65959e3034c51e078fea72491444b06a6055f37c051184bdc379234b3d57
```

`CaseCatalogTest` also requires unique case IDs and pins the exact count and ordered hash.
