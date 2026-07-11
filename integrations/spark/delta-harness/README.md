# delta-harness (prototype)

A Scala prototype of the delta-test harness designed in `docs/spark-delta-test-harness/`. It
runs customer-facing DML **operations** as **typed pipelines** against the **real OpenHouse
catalog** (an embedded `OpenHouseLocalServer` wired to `OpenHouseCatalog`), and reports
pass / skip / fail per case.

Verified: **18 passed, 0 skipped, 0 failed** — see `VERIFIED-RUN-openhouse.txt`.

## Model
A test is a **typed pipeline** — `TableTest[S <: Schema]`. The type parameter declares which
table implementation the test depends on, and every step references that schema's columns
through typed handles (`row.get(CoreTable.long): Long`), so the compiler forbids mixing schemas
or naming a column the schema doesn't declare.

- **`Schema`** — columns only. Each `Column[T]` carries its Scala type `T` and a deterministic
  `literalAt(rowIndex)` generator; `CoreTable` is a representative table with one column per
  common data type plus a string `datepartition` (`YYYY-MM-DD-HH`).
- **Preparation prefix + operation suffix** — both are same-schema pipeline segments composed
  with `andThen`. `createAndSeed` yields a known state; an operation runs on it. The set is
  `preparations × operations`, so RTAS / drop+undrop wire in later as alternate preparations
  without touching any operation.
- **Delta assertions** — each step's validation thunk gets a `StepView` with `before`/`after`
  row snapshots and `snapshotsBefore`/`snapshotsAfter` commit counts, so every operation asserts
  a **delta** (never an absolute row set) and holds on any starting state.

Operations covered: read (projection, filter), delete (×4), update (×3), merge (×4),
insert/append, overwrite (×2), plus create. Operation sources are written as **explicit
literals**. Today all run on one `core` starting state; **Stage 3** reintroduces the
`parquet`/`orc`/`avro` × `unpartitioned`/`partitioned` layout multiplier as `Layout` values
crossed with each scenario.

The OpenHouse wiring is **copied** from `OpenHouseLocalServer` + `TestSparkSessionUtil` (read,
not extended): no OpenHouse test class is subclassed and no existing test is altered;
`OpenHouseEnv` composes the embedded server as a component.

## Run it
Requires **JDK 17** (the repo pins Lombok 1.18.20, which does not compile on JDK 21+). Set
`JAVA17_HOME`. `run-openhouse.sh` resolves the classpath via Gradle, compiles the Scala with
`scalac`, and runs on the embedded OpenHouse server.

```bash
./run-openhouse.sh                          # full set (18 cases)
./run-openhouse.sh delete                   # a fast slice (~25s): the delete tests
./run-openhouse.sh merge                    # the merge tests
./run-openhouse.sh delete.byPredicate       # one test
```
Args are AND-substring filters on the case id. A narrow slice is ~25s end-to-end
(embedded-server + Spark startup dominates; the cases are milliseconds).

## Notes
- **Gradle wrapper** cannot download in restricted networks (proxy 403); use a system Gradle 8.x
  (`GRADLE_BIN`). Gradle is used only to resolve the classpath and build OpenHouse's own jars.
- **Avro** required a classpath fix (duplicate shaded/unshaded Iceberg) — resolved via a Gradle
  dependency exclusion in `scripts/print-cp.init.gradle`. See `FINDINGS.md` (F1).

## Next
**Stage 3** — reintroduce the layout multiplier (`Layout` = format × partition) crossed with
each scenario, restoring the ~100-case matrix. **Stage 4** — a partition-per-datatype schema
family, then RTAS'd / drop+undrop preparations wired into every DML test (RTAS being the
validation of the incremental model).
