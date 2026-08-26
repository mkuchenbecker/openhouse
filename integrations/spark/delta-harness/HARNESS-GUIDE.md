# Delta harness guide

## Purpose

The delta harness verifies customer-facing OpenHouse behavior through Spark SQL, DataFrame writes,
metadata tables, procedures, and the small REST surface used for table locks. It runs each case
against a fresh table and checks the table state that the operation produced.

This branch contains the standard copy-on-write framework and standard scenarios. Its ordered
catalog contains 1,181 cases. RTAS, merge-on-read, branch, and WAP coverage belong to stacked child
branches and are outside this guide.

The same portable Scala sources serve two environments:

- OpenHouse runs them locally against an embedded catalog through the `runOpenHouse` Gradle task.
- The li-openhouse acceptance adapter supplies a remote `Ctx` and runs deterministic Airflow shards
  against a configured OpenHouse catalog.

`Env.scala` contains only the embedded boot and runner code. The published harness jar excludes that
file, so the scenario and assertion code stays identical in both environments.

## Run the standard harness locally

The harness requires JDK 17.

From the repository root:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

./gradlew --no-daemon \
  :integrations:spark:openhouse-spark-delta-harness_2.12:runOpenHouse
```

Pass one or more case-id substrings through `--args` to run a smaller slice:

```bash
./gradlew --no-daemon \
  :integrations:spark:openhouse-spark-delta-harness_2.12:runOpenHouse \
  --args='read.projection'
```

The wrapper performs the same JDK check and invokes the same Gradle task:

```bash
export JAVA17_HOME=$(/usr/libexec/java_home -v 17)
integrations/spark/delta-harness/run-openhouse.sh read.projection
```

Each argument is an AND-substring filter. For example, these arguments select cases whose IDs
contain both strings:

```bash
integrations/spark/delta-harness/run-openhouse.sh delete.byPredicate parquet
```

`HARNESS_PARALLELISM` controls the number of concurrent case attempts. The default is the number of
available processors. Set it to `1` when debugging sequentially.

## Read a test from preparation to assertion

The harness separates starting state from behavior under test.

### Layout

A `Layout` describes one table shape:

- A stable label used in the case ID.
- A human description of the resulting table.
- The complete `CREATE TABLE` statement.

The standard layouts cross `parquet`, `orc`, and `avro` with unpartitioned and date-partitioned
tables.

### Table preparation

A `TablePreparation` is an immutable recipe that creates one fresh table. It has a stable label and
a required human description. Its `TableTest` pipeline keeps table creation, seeding, and any
additional starting-state transition visible as separate steps.

Examples include:

- Three deterministic seed rows in a standard layout.
- The same rows after `WRITE ORDERED BY`.
- The same rows after adding one column.
- The same rows plus a row whose string value is null.
- An empty table for insert and merge cases that require that starting state.

The description explains the state the test receives. A reviewer should not need to infer that state
from a case prefix.

### DML test case

A `DmlTestCase` describes one operation. It owns:

- A stable operation ID.
- A required human description.
- The operation and every assertion about its effect.
- An optional explicit known-bug reason.

Matrix assembly names the compatible preparation and test lists directly:

```scala
preparedCoreTables.flatMap { preparation =>
  allDmlTestCases.map(_.runOn(preparation))
}
```

The test body remains readable without opening `Plan.scala`:

```scala
import org.apache.spark.sql.Row

DmlTestCase(
  "insert.into",
  "INSERT appends one row and commits one snapshot.",
  table => {
    val before = table.state

    table.spark.sql(
      s"INSERT INTO ${table.name} VALUES " +
        "(4L, 4, 'row-4', 4.5, true, '2024-01-04-03')")

    val after = table.state
    val expectedRows =
      (before.rows :+ Row(4L, 4, "row-4", 4.5, true, "2024-01-04-03"))
        .sortBy(_.getLong(0))
    assert(after.rows == expectedRows)
    assert(after.snapshotCount == before.snapshotCount + 1)
  })
```

The exact test implementations use typed column handles and complete expected row sets. The example
shows the required flow: capture the starting state, execute one operation, capture the resulting
state, and assert the delta.

### Bespoke cases

DDL, maintenance, reader/writer, control-plane, interaction, surface, and hazard cases remain
bespoke. Their table construction is part of the behavior being tested, or their state transition
does not fit a reusable DML cross product cleanly.

Direct bespoke `Plan.Case` values require one human test description. A bespoke case built through
`TablePreparation.test` also carries the preparation description. Their assertions follow the
contract of the family instead of the reusable DML matrix contract.

All bespoke cases keep the action and assertions together. They assert the relevant rows, snapshots,
metadata, or rejection state for the transition under test.

## Fresh-table lifecycle

`TablePreparation.test` creates one `Plan.Case`. When that case runs:

1. `TableTest` allocates a unique table name.
2. Each preparation step runs. A step with a validator checks its immediate result.
3. `PreparedTable` captures the prepared rows and snapshot count.
4. The localized test body runs.
5. Any preparation-level postcondition runs.
6. The table is dropped in `finally`.

A test failure remains the primary failure. Cleanup or postcondition failures are attached as
suppressed exceptions when another failure is already in flight.

Every case attempt receives a fresh `spark.newSession()`. The worker pool controls how many attempts
run concurrently, and the result report remains in deterministic catalog order.

## Assertions are relative to the preparation

`PreparedTable.state` returns a `TableState` containing the complete ordered row set and snapshot
count. DML cases compare a captured `before` state with the state after the operation.

Use these rules when adding a case:

- An insert asserts the exact appended row and the expected snapshot delta.
- An update asserts the complete row set with only the selected values changed.
- A delete asserts the complete row set with only the selected rows removed.
- A merge asserts the complete target state for all matched and unmatched clauses.
- A read asserts that rows and snapshot count are unchanged.
- A no-match mutation asserts unchanged rows and the operation's documented snapshot delta.
- DML snapshot oracles use deltas relative to the prepared table.

This keeps one test valid across every compatible preparation.

## Source map

The source map separates reusable DML structure from the bespoke standard families.

| File | Responsibility |
|------|----------------|
| `Framework.scala` | Typed schemas, row generation, `TableTest`, `TablePreparation`, `PreparedTable`, `DmlTestCase`, outcomes, retries, and REST helpers. |
| `ScenarioKit.scala` | Standard layouts, preparation collections, table-state helpers, and shared case builders. |
| `DmlScenarios.scala` | Read, insert, overwrite, delete, update, merge, compatibility lists, and standard DML matrix assembly. |
| `NestedTypesScenarios.scala` | Nested structures, type-edge coverage, partition transforms, and partition evolution. |
| `MaintControlScenarios.scala` | Time travel, restore and rollback, maintenance procedures, and lock control-plane cases. |
| `NegativeDdlScenarios.scala` | Schema and table DDL, properties, policies, tags, ACLs, encryption pins, and rejection cases. |
| `InteractionScenarios.scala` | Standard DDL and state-transition compositions. |
| `SurfaceScenarios.scala` | Standard procedures, metadata tables, concurrency, schema edges, readers, and write configuration. |
| `HazardReaderWriterScenarios.scala` | Copy-on-write reader/writer coverage and standard hazard compositions. |
| `ForkScenarios.scala` | Behavior pins for the LinkedIn Iceberg fork that belong to the standard layer. |
| `ImplementationPinScenarios.scala` | The plaintext data-file implementation pin. |
| `Plan.scala` | The deterministic ordered catalog assembled from scenario-owned lists. |
| `OpenHouseMatrix.scala` | The standard scenario trait composition. |
| `Env.scala` | Embedded OpenHouse boot, local Spark sessions, filtering, retries, and result reporting. |

## Catalog and regression tests

`CaseCatalogTest` pins the standard catalog:

- Case count: `1181`
- SHA-256 of ordered case IDs:
  `377f65959e3034c51e078fea72491444b06a6055f37c051184bdc379234b3d57`

`DmlCaseCatalogTest` pins the readable DML structure, compatibility lists, preparation descriptions,
and matrix order. `TablePreparationTest` pins case-ID formatting and the propagation of test and
preparation descriptions into `Plan.Case`.

Run the catalog tests with:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

./gradlew --no-daemon \
  :integrations:spark:openhouse-spark-delta-harness_2.12:test
```

## Add standard coverage

For a new reusable DML operation:

1. Add one described `DmlTestCase`.
2. Capture `before` and `after` in the body.
3. Assert complete expected rows and the relative snapshot delta.
4. Add the case to plainly named compatibility lists.
5. Let the existing preparation cross products create the localized cases.
6. Update the focused catalog tests and the ordered catalog baseline.

For a new preparation:

1. Describe the resulting table state.
2. Keep creation and seeding as separate visible steps.
3. Add the preparation to the applicable matrix lists.
4. Add a focused test that pins its description and compatibility.

For bespoke DDL or interaction coverage:

1. Keep setup, action, and assertions in one localized case.
2. Give every case a human test description. Add a preparation description when the case uses
   `TablePreparation.test`.
3. Add the case through its owning scenario list.
4. Preserve the deterministic order in `Plan.scala`.
