# delta-harness (prototype)

A Scala prototype of the delta-test harness designed in `docs/spark-delta-test-harness/`. It
runs customer-facing DML **operations** as a matrix of `(operation × starting state)` against
the **real OpenHouse catalog** (an embedded `OpenHouseLocalServer` wired to `OpenHouseCatalog`),
and reports pass / skip / fail per case.

Verified: **109 passed, 0 skipped, 0 failed** — see `VERIFIED-RUN-openhouse.txt`.

## Model (maps to the design docs)
- **`StartingState`** — a prep function that seeds a table in a physical shape
  (partitioning × file format today; RTAS'd / soft-dropped plug in the same way). Doc `07`.
- **`TableTest`** — a state-agnostic operation test. It observes the table's current rows,
  runs its operation, and asserts the **delta** (never an absolute row set), so it holds on
  any starting condition and two tests compose on one base table. Docs `05`, `06`.
- **`StandaloneTest`** — a self-contained test for state-transition cases (e.g. `CREATE`) that
  need a specific/absent table and are not crossed with the states.
- The run is `states × TableTests + standalone`. Adding a state runs every test on it; adding
  a test runs it on every state.

Operations covered: read (projection, filter), insert/append, overwrite, update (×3),
delete (×4), merge (×4), plus standalone create — across `unpartitioned`/`partitioned` ×
`parquet`/`orc`/`avro`.

The OpenHouse wiring is **copied** from `OpenHouseLocalServer` + `TestSparkSessionUtil` (read,
not extended): no OpenHouse test class is subclassed and no existing test is altered;
`OpenHouseEnv` composes the embedded server as a component.

## Run it
Requires **JDK 17** (the repo pins Lombok 1.18.20, which does not compile on JDK 21+). Set
`JAVA17_HOME`. `run-openhouse.sh` resolves the classpath via Gradle, compiles the Scala with
`scalac`, and runs on the embedded OpenHouse server.

```bash
./run-openhouse.sh                          # full matrix (109 cases)
./run-openhouse.sh delete parquet           # a fast slice (~25s): delete tests on parquet
./run-openhouse.sh merge unpartitioned/orc  # merge tests on one state
./run-openhouse.sh delete.byPredicate       # one test across states
```
Args are AND-substring filters on the case id (test / state / format). A narrow slice is ~25s
end-to-end (embedded-server + Spark startup dominates; the cases are milliseconds).

## Notes
- **Gradle wrapper** cannot download in restricted networks (proxy 403); use a system Gradle 8.x
  (`GRADLE_BIN`). Gradle is used only to resolve the classpath and build OpenHouse's own jars.
- **Avro** required a classpath fix (duplicate shaded/unshaded Iceberg) — resolved via a Gradle
  dependency exclusion in `scripts/print-cp.init.gradle`. See `FINDINGS.md` (F1).

## Next
Broaden the starting-state axis (more partition specs, seeded-differently, feature-enabled),
then RTAS'd / soft-dropped states — RTAS being the validation of the incremental model.
