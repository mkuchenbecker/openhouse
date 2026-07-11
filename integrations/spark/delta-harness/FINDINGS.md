# delta-harness — findings

Real issues surfaced by running the harness against the OpenHouse catalog. These are the
harness doing its job: distinguishing environment/packaging problems from test failures.

## F1 — Avro data path: shaded vs unshaded Iceberg on one classpath (RESOLVED)

**Symptom.** Any test that writes/reads **Avro** data files (`write.format.default=avro`)
failed on the first data op (CREATE succeeds — metadata only):

```
java.lang.ClassCastException: class org.apache.avro.Schema$RecordSchema cannot be cast to
class org.apache.iceberg.shaded.org.apache.avro.Schema
```

**Root cause (confirmed).** The run classpath carries **two copies of Iceberg**:
- `iceberg-spark-runtime-3.5_2.12-1.5.2.15.jar` — the **shaded** fat jar, which relocates Avro
  to `org.apache.iceberg.shaded.org.apache.avro`, and
- `iceberg-{api,common,core,data}-1.5.2.15.jar` — the **unshaded** à-la-carte jars, which use
  plain `org.apache.avro`.

The Iceberg runtime fat jar is a self-contained bundle meant to stand alone; Iceberg warns
against putting it on a classpath alongside the individual jars. The harness does exactly that
because it runs the embedded OpenHouse **server** (unshaded `iceberg-core`) and the Spark
**client** (shaded `iceberg-spark-runtime`) in the **same JVM**. Parquet/ORC never cross the
shaded↔unshaded boundary; Avro does, hence the cast. In production the server and client are
separate processes, so this is a **co-located-test artifact**, not a shipping defect.

**Ruled out.** Swapping the unshaded Avro jar `1.11.4 → 1.11.2` made **no difference** (identical
failure). It was never the Avro version — it is the duplicated Iceberg.

**Fix (applied, least invasive).** `run-openhouse.sh` de-duplicates the classpath: it drops the
unshaded `iceberg-{api,common,core,data}` jars and lets everything resolve Iceberg — and its
shaded Avro — from the single fat jar. With this, **all three formats pass, including Avro**.
No product change; Parquet/ORC unaffected. Avro is re-enabled in `OpenHouseMatrix.scala`.

**If a real deployment ever co-locates them**, the product-side fix is a build dependency
exclusion: exclude `org.apache.iceberg:iceberg-{core,api,data,common}` from the configuration
that carries the shaded `iceberg-spark-runtime`.

## Verified green (JDK 17, embedded OpenHouse server, `openhouse.dbMatrix`)
- **Parquet, ORC, Avro** — CREATE, READ (projection + filter), format-materialization, DELETE
  (×4), UPDATE (×3), MERGE (×4), INSERT/append/overwrite.
- **109 cases: 109 passed, 0 skipped, 0 failed.**
