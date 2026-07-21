# delta-harness (prototype)

A Scala prototype of the delta-test harness designed in `docs/spark-delta-test-harness/`. It
runs customer-facing DML **operations** as **typed pipelines** against the **real OpenHouse
catalog** (an embedded `OpenHouseLocalServer` wired to `OpenHouseCatalog`), and reports
pass / skip / fail per case.

**Read `index.md` first — it is the master map** (living docs, checklist status, current-state pointer).
`VERIFIED-RUN-openhouse.txt` is the authoritative, dated source of truth for run counts.

Verified (latest): **STUB ~2574 · REAL-HTS ~2792, 0 failed** (`HARNESS_REAL_HTS=1` adds the real-HTS undrop
leg). The harness covers, as **substrates × operations × consumers**: the full DML surface (delete/update/
merge/insert/overwrite, CoW vs MoR incl. a physical position-delete discriminator, nested/complex types,
type edges, partition transforms, time travel, restore/rollback, typed negatives); DDL × consumer battery;
reader × writer-class (CDC/incremental/streaming); branching/WAP (incl. the **WAP mega-axis** — branch DML
parity, systematic branch-DDL leak, staged-WAP publish visibility); RTAS full-cross over replace-lineage;
the **undrop** battery (real embedded HTS); and iceberg-**fork** behavior tests (#249/#229/#219/#228/#233/
#189/#251). **Format is a per-case parameter** — every table-creating block runs on parquet AND orc. A
reversible **branch-testing mode** (`ICEBERG_RUNTIME_JAR`) runs the whole suite against a locally-built
fork-branch-HEAD iceberg runtime. Product findings are tracked in `AUDIT-FINDINGS.md` (G2–G14, H1–H8, WAP1)
and `BUGS.md`. (Exact current counts: `VERIFIED-RUN-openhouse.txt`.)

## Model
A test is a **typed pipeline** — `TableTest[S <: Schema]`. The type parameter declares which
table implementation the test depends on, and every step references that schema's columns
through typed handles (`row.get(CoreTable.long): Long`), so the compiler forbids mixing schemas
or naming a column the schema doesn't declare.

- **`Schema`** — columns only. Each `Column[T]` carries its Scala type `T` and a deterministic
  `literalAt(rowIndex)` generator; `CoreTable` is a representative table with one column per
  common data type plus a string `datepartition` (`YYYY-MM-DD-HH`).
- **Preparation prefix + operation suffix** — an operation is a **headless** pipeline segment;
  the run composes `createAndSeed(layout)` before it via `andThen`. So the set is
  `operations × layouts`, and RTAS / drop+undrop wire in later as alternate preparations without
  touching any operation.
- **Layout axis** — `Layout` = file format × partitioning, crossed with every operation. Format
  is a schema-independent TBLPROPERTY (varied blindly); partitioning references a real column
  (identity on the `datepartition` string). Six layouts: `{unpartitioned, partitioned}` ×
  `{parquet, orc, avro}`.
- **Delta assertions** — each step's validation thunk gets a `StepView` with `before`/`after`
  row snapshots and `snapshotsBefore`/`snapshotsAfter` commit counts, so every operation asserts
  a **delta** (never an absolute row set) and holds under any layout.

The core DML operation set (~55 ops: read/delete/update/merge/insert/append/overwrite + partition ops)
is authored once as **explicit literals** and composed after each preparation prefix, so the case set is
`prep-substrates × operations`. That core DML cross was the harness's first block; the suite has since grown
far beyond it (DDL, branch/WAP, RTAS, undrop, fork tests, ...) to ~2,574 — see `VERIFIED-RUN-openhouse.txt`.

The OpenHouse wiring is **copied** from `OpenHouseLocalServer` + `TestSparkSessionUtil` (read,
not extended): no OpenHouse test class is subclassed and no existing test is altered;
`OpenHouseEnv` composes the embedded server as a component.

## Run it
Requires **JDK 17** (the repo pins Lombok 1.18.20, which does not compile on JDK 21+). Set
`JAVA17_HOME`. `run-openhouse.sh` resolves the classpath via Gradle, compiles the Scala with
`scalac`, and runs on the embedded OpenHouse server.

```bash
./run-openhouse.sh                          # full matrix (see VERIFIED-RUN for the current count)
./run-openhouse.sh delete parquet           # a fast slice (~25s): delete tests on parquet
./run-openhouse.sh merge partitioned/avro   # merge tests on one layout
./run-openhouse.sh delete.byPredicate       # one test across layouts
```
Args are AND-substring filters on the case id (operation / partition / format). A narrow slice
is ~25s end-to-end (embedded-server + Spark startup dominates; the cases are milliseconds).

## Notes
- **Gradle wrapper** cannot download in restricted networks (proxy 403); use a system Gradle 8.x
  (`GRADLE_BIN`). Gradle is used only to resolve the classpath and build OpenHouse's own jars.
- **Avro** required a classpath fix (duplicate shaded/unshaded Iceberg) — resolved via a Gradle
  dependency exclusion in `scripts/print-cp.init.gradle`. See `FINDINGS.md` (F1).

## Next
A partition-per-datatype schema family, then RTAS'd / drop+undrop preparations wired into every
DML test as alternate `createAndSeed` prefixes (RTAS being the validation of the incremental
model).
