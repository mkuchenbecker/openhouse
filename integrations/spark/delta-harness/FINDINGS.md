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

**Fix (applied, least invasive).** The classpath is resolved through Gradle with a proper
dependency exclusion in `scripts/print-cp.init.gradle`: the unshaded
`com.linkedin.iceberg:iceberg-{api,common,core,data}` modules are excluded, so the resolved
graph carries a single Iceberg — the shaded fat jar — and its shaded Avro. (The shaded jar
provides all `org.apache.iceberg.*` classes, so excluding the unshaded modules is safe.) This
is dependency resolution, not post-hoc jar filtering. With it, **all three formats pass,
including Avro**; Parquet/ORC unaffected. Avro is re-enabled in `OpenHouseMatrix.scala`.

**If a real deployment ever co-locates them**, the product-side fix is a build dependency
exclusion: exclude `org.apache.iceberg:iceberg-{core,api,data,common}` from the configuration
that carries the shaded `iceberg-spark-runtime`.

## Environment / methodology pitfalls (fresh container)

The remote container is **ephemeral** and starts with only what the base image ships. Two
recurring setup traps, each of which has cost a full debugging detour more than once:

1. **JDK: only JDK 21 is pre-installed; the OpenHouse build needs JDK 17.** The repo pins
   Lombok 1.18.20, which does **not** compile on JDK 21+ (annotation-processor breakage). There
   is no JDK 17 on the image. Install it — but a bare `apt-get install openjdk-17-jdk-headless`
   **fails with 404s** because the cached package index points at point-releases that have since
   been superseded on the mirror. You must `sudo apt-get update` **first**, then install:
   ```bash
   sudo apt-get update
   sudo apt-get install -y openjdk-17-jdk-headless   # lands at /usr/lib/jvm/java-17-openjdk-amd64
   export JAVA17_HOME=/usr/lib/jvm/java-17-openjdk-amd64
   ```
2. **Gradle wrapper cannot download (proxy 403); use the system Gradle.** `/opt/gradle/bin/gradle`
   (8.x) is present and works. Point the run script at it:
   ```bash
   export GRADLE_BIN=/opt/gradle/bin/gradle
   ```
   The Scala compiler jars (2.12.18) are already in `~/.m2`, so no extra fetch is needed there.

With both exports set, `./run-openhouse.sh <filters>` resolves the classpath, compiles the
harness, and runs it on the embedded server. A narrow slice is ~25s end-to-end.

3. **Commits will show as "Unverified" on GitHub — this is environmental, don't chase it.** The
   committer email is already `noreply@anthropic.com` (correct), but the SSH signing key at
   `/home/claude/.ssh/commit_signing_key.pub` is a **0-byte empty file**, so `git` cannot sign.
   `git commit --amend --reset-author` only rewrites author metadata; with no key material it
   cannot add a signature, so it will not clear the flag. The stop-hook nags about this every
   turn — acknowledge and move on; there is no in-container fix.

## Verified green (JDK 17, embedded OpenHouse server, `openhouse.dbMatrix`)
- **Parquet, ORC, Avro** × **unpartitioned / partitioned** — CREATE, READ (projection + filter),
  format-materialization, DELETE (×4), UPDATE (×3), MERGE (×5), INSERT/append/overwrite.
- **120 cases: 120 passed, 0 skipped, 0 failed.** (19 operations × 6 layouts + 6 per-layout creates.)
