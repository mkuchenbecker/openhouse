# delta-harness (prototype)

A standalone Scala prototype of the distributed delta-test harness designed in
`docs/spark-delta-test-harness/`. It runs the **DELETE** category at a single matrix
permutation against real Spark 3.5 + Iceberg and **self-verifies** (each check declares its
expected outcome; the run exits non-zero on any mismatch).

**Status:** proof-of-concept, verified end-to-end against **two catalogs**:
- **OpenHouse catalog** (primary) — `src/main/scala/harness/openhouse/OpenHouseDeleteSlice.scala`,
  run via `run-openhouse.sh`. Boots the embedded `OpenHouseLocalServer` and wires the real
  `OpenHouseCatalog`. Verified: `VERIFIED-RUN-openhouse.txt`.
- **Local Iceberg Hadoop catalog** (quick smoke) — `src/main/scala/harness/Main.scala`,
  standalone Maven (`pom.xml`, `run.sh`). Verified: `VERIFIED-RUN.txt`.

The OpenHouse wiring is **copied** from `OpenHouseLocalServer` + `TestSparkSessionUtil`
(read, not extended). No OpenHouse test class is subclassed and no existing test is altered;
`OpenHouseEnv` composes the embedded server as a component.

### Fast inner loop (test selection)
Pass case-id substrings as args to `run-openhouse.sh`; a case runs only if its id contains
**all** of them (AND). No args runs the full matrix.
```bash
./run-openhouse.sh delete parquet          # delete tests on parquet
./run-openhouse.sh merge unpartitioned/orc # merge tests on one state
./run-openhouse.sh delete.byPredicate      # a single test across states
```
A narrow slice is ~25s end-to-end (embedded-server + Spark startup dominates; the cases
themselves are milliseconds), which keeps the edit/run cycle well under a minute.

### Environment note (important)
The OpenHouse build pins **Lombok 1.18.20, which does not compile under JDK 21+** (javac
`JCTree.qualid` change). Build/run the OpenHouse variant on **JDK 17** — set `JAVA17_HOME`.
The two source files share class names in package `harness`; they are **compiled
independently** (Maven for the local variant, `run-openhouse.sh` for the OpenHouse variant),
never together.

## What it demonstrates (maps to the design docs)
- Outcome model + allowlist classifier (`02`), errors-as-values (`03`).
- `Managed` + `bracket`, single edge (`04`).
- Curried `setup` + delta `test` (`observe`/`operation`/`expect`) (`05`, `06`).
- The DELETE slice from `12-first-slice-delete.md`: 4 in-permutation behaviors + a rejection test.
- The two firewall self-tests (`09` §2): a transient `IOException` heals via retry (infra never
  becomes `Failed`); a deliberately-wrong expectation is reported as `Failed`.

## Run it
Requires Maven + a JDK. Spark 3.5 on JDK 17+ needs `--add-opens`.

```bash
mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java $(cat run-jvm-args) -cp "target/classes:$(cat cp.txt)" harness.Main
```

See `run.sh` for the full JVM module flags.

## Next steps
1. Point `setup` at the OpenHouse catalog (swap the Hadoop-catalog config) — the `setup` seam
   is exactly this substitution.
2. Turn on `axes.enumerate` to extend the single permutation into the matrix (`07`, `11`).
3. Decide final build home: a Gradle module beside the other `integrations/spark/*` projects,
   or keep standalone.
