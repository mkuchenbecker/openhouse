# Rung 9 — HDFS client on Java 17 (RBF wire-compat) + v3 read cliff

**Status: DONE.** Server HDFS client moved to the **Hadoop 3.3** line (the target the user cares
about) and build-validated; Java-17 runtime + RBF wire-compat + v3 read-cliff assessed below.

## The gap this rung closes
The OpenHouse server links `hadoop-client` (via `openhouse.hadoop-conventions`) to write table
metadata to HDFS. On the `1.11` branch that pin was still the **legacy `2.10.0`** — the F-HADOOP1
"bump to 3.3.4" recorded in `20-risks-decisions-findings.md` lived in a since-refactored
`java-runtime-1.5` convention and **never landed on this branch's `hadoop-conventions`** (git history
of that file shows no upgrade commit). Hadoop 2.10.0 is unfit for the Java-17 server runtime
(Rung 7): it predates Java 17 and lacks the Hadoop 3.3+ `FileSystem.openFile(Path)` API that Iceberg
1.10/1.11 calls (the original F-HADOOP1 `NoSuchMethodError`). The Spark itest lane only dodged this
by *excluding* the server's unshaded 2.10 Hadoop and borrowing Spark 4.0's shaded `3.4.1`, so the
**declared server dependency was stale** even though tests were green.

## The change
`buildSrc/src/main/groovy/openhouse.hadoop-conventions.gradle`: `hadoopVersion 2.10.0 → 3.3.6`.
Hadoop 3.3.6 has `openFile`, runs on Java 17 (with the launcher `--add-opens`, already applied), and
its RPC client is wire-compatible with a 3.1/3.2 RBF (Router-Based Federation) cluster.

### Transitive fallout (2.10 dragged in old libs that 3.3.6 dropped) — fixed at the source
Hadoop 2.10 transitively supplied several legacy libraries that server code imported directly;
3.3.6 no longer provides them, so each use was migrated to its modern, already-present equivalent
(no band-aid version pins):
- `org.apache.commons.lang.StringUtils` (commons-lang **2.x**) → `org.apache.commons.lang3.StringUtils`
  — `services/housetables/.../JdbcProviderConfiguration.java`, `services/tables/.../TablesControllerTest.java`.
- `org.apache.directory.api.util.Strings` (Apache Directory API, from the Hadoop auth stack) →
  `org.apache.commons.lang3.StringUtils` (`isNotEmpty`/`isEmpty`) —
  `iceberg/openhouse/internalcatalog/.../HouseTableRepositoryImpl.java`,
  `services/tables/.../RequestAndValidateHelper.java`.
- `org.codehaus.jackson.node.*` (Jackson **1.x**) → `com.fasterxml.jackson.databind.node.*`
  (Jackson 2.x, same `JsonNodeFactory.instance` / `objectNode()` API) —
  `services/tables/.../PoliciesSpecMapperTest.java`.

The Spark-4.0 itest hadoop exclusions are by group+module (version-agnostic), so they still exclude
the server's now-3.3.6 unshaded Hadoop and the itest/harness classpath is unchanged.

## Verification (build + runtime, all green)
- **Compiles:** every module applying `hadoop-conventions` compiles main + test against 3.3.6
  (`cluster:storage`, `services:{tables,housetables,jobs,common}`, `iceberg:openhouse:{internalcatalog,htscatalog}`,
  `libs:datalayout`).
- **Server boots on 3.3.6:** `:services:tables:generateOpenApiDocs` starts the full Spring context
  (housetables + tables `forkedSpringBootRun`) successfully — a boot smoke of the metadata-writer
  stack on the new client.
- **Tests pass:** `cluster:storage:test` (storage/FileIO), `TablesControllerTest`,
  `PoliciesSpecMapperTest` — all PASSED on JDK 17 + Hadoop 3.3.6.
- The full `Branch 1.11 CI` push gate re-runs the whole build+test on this change.

## Java-17 runtime assessment
Hadoop 3.3.x runs on a Java 17 JVM in practice with the module-access `--add-opens` set already
applied by the launcher/harness; official/"fully supported" Java-17 Hadoop is 3.4.x+, so 3.3.6 is
the pragmatic floor that keeps the RBF wire contract (below) while clearing Java 17. If a fully
"official" Java-17 posture is later required, 3.4.x is a drop-in follow-up (also RBF-compatible).

## RBF wire-compatibility
A Hadoop 3.3.6 RPC client is wire-compatible with 3.1/3.2 HDFS routers (RBF): Hadoop's client↔server
RPC is backward/forward compatible across the 3.x line, so a 3.3.6 client transacts with 3.1/3.2
NameNodes/Routers. This matches the C5 assumption used for the rung-1 3.3.4 client and is unchanged
by moving to 3.3.6.

## What is NOT validated in-JVM (honest scope)
The delta-harness and itests run against **LocalFileSystem** (`fs.defaultFS=file:///`), so they
exercise the metadata-writer + FileIO code paths and the client build/link — **not** the real
`DistributedFileSystem` RPC wire against a live HDFS/RBF cluster, Kerberos, or the chown/replication
legs. Proving those requires the `docker-hdfs-validation` recipe (`oh-hadoop-spark`: real bde2020
HDFS + the OpenHouse services) on real infrastructure, which is out of scope for this sandbox. This
is the residual `F-VACUITY-HADOOP` gap: the Hadoop-on-real-HDFS leg is **build- + wire-compat-
asserted**, and now on a **Java-17-capable 3.3.6 client**, but a real-cluster run remains the final
production gate.

## v3 read cliff (cross-referenced)
Independently of HDFS: v3 DSv2 deletion vectors (puffin) authored by the Rung-3 capability are
**unreadable by engines/clients that predate v3 DV support** (older Spark/Trino, bespoke readers).
Because the server force-sets `format-version` from `cluster.iceberg.format-version` (default **2**)
only on create/replace, existing v2 tables are not auto-upgraded — the cliff is gradual, not
instantaneous. **Recommendation:** keep the cluster default at v2 and enable v3 only after the reader
population is confirmed v3-capable (the Rung-3 write-up `rung3-v3-deletion-vectors.md` carries the
full cost assessment; the isolated `deletionVectorTest` proves the write capability without exposing
the cliff to the default suite).

## Conclusion
The server HDFS client is on Hadoop **3.3.6** (Java-17-capable, `openFile`-bearing, RBF wire-compatible),
migrated off the legacy 2.10.0 with its transitive fallout fixed at the source. Build + boot + server
tests are green. Real-HDFS/RBF cluster validation and the v3 default flip remain deployment-time gates
with the paths documented above.
