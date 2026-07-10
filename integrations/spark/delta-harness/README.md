# delta-harness (prototype)

A standalone Scala prototype of the distributed delta-test harness designed in
`docs/spark-delta-test-harness/`. It runs the **DELETE** category at a single matrix
permutation against real Spark 3.5 + Iceberg and **self-verifies** (each check declares its
expected outcome; the run exits non-zero on any mismatch).

**Status:** proof-of-concept, verified end-to-end (see `VERIFIED-RUN.txt`). Standalone Maven
build — *not yet wired into the Gradle build_ and not pointed at the OpenHouse catalog yet
(uses a local Iceberg Hadoop catalog). Both are deliberate next steps, not oversights.

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
