# Rung 3 — Iceberg v3 DSv2 deletion vectors on the Spark-4.0 REST lane (THE GOAL)

Audit-grade write-up of the spike's goal rung: **prove Iceberg v3 DataSource-V2 deletion vectors
work end-to-end** on the Spark-4.0 / Iceberg-1.11 / REST-first lane, with a real JUnit test, without
disturbing the currently-green default-v2 itest suite.

Status: **DONE + verified.** Test class `DeletionVectorTestSpark4_0` (2 methods) PASSED in an
isolated v3 fork; the default-v2 `test` task is unchanged.

- Test: `integrations/spark/spark-4.0/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark/catalogtest/DeletionVectorTestSpark4_0.java`
- Gradle wiring: `integrations/spark/spark-4.0/openhouse-spark-itest/build.gradle` (`deletionVectorTest` task + `test` exclude)

---

## 1. How v3 is enabled (given the server-forced format version + singleton server)

### The constraint
OpenHouse does **not** let the client choose the table format version. On every table **create** and
on every **stage-replace / replace-commit** (RTAS/CTAS overwrite), the server calls
`OpenHouseInternalRepositoryImpl.computePropsForTableCreation(...)`
(`services/tables/.../OpenHouseInternalRepositoryImpl.java:468`), which unconditionally does:

```java
propertiesMap.put(
    TableProperties.FORMAT_VERSION,
    Integer.toString(clusterProperties.getClusterIcebergFormatVersion()));   // ~line 555
```

So a client `TBLPROPERTIES('format-version'='3')` hint is **overwritten**. The only knob is the
cluster config `cluster.iceberg.format-version` (Spring `@Value("${cluster.iceberg.format-version:2}")`,
`cluster/configs/.../ClusterProperties.java:43`, default **2**). The embedded test server
(`SpringH2TestApplication`, started by `OpenHouseLocalServer`) resolves that property from JVM system
properties, so launching the JVM with `-Dcluster.iceberg.format-version=3` makes the server author
v3 metadata. (Confirmed independently by the standalone `run-dvprobe.sh` probe; this rung turns that
into a first-class JUnit test.)

Nuance used in the cost assessment (§5): the force is **create/replace-only**, not on ordinary
appends/updates/deletes — an existing v2 table stays v2 on normal writes; only *new* and *replaced*
tables pick up the cluster default.

### The isolation problem
The Spark-4.0 itest base `OpenHouseRestSparkITest` starts `OpenHouseLocalServer` as a **per-JVM
singleton** (double-checked locking). A cluster-config choice therefore applies to the whole itest
JVM. Setting `-Dcluster.iceberg.format-version=3` for the shared `test` fork would flip **all**
`catalogtest` classes (currently 1637 green / 28 skip / 32 known-fail) from v2 to v3 — a
CI-risking change. In-test tricks (setting the system property in `@BeforeAll`, or standing up a
second dedicated server in the same JVM) all depend on **which test class boots the singleton
first**, i.e. on JUnit ordering — fragile.

### The chosen mechanism (robust): a separate Gradle Test task + JVM fork
`build.gradle` carves the DV test into its **own** forked JVM:

```gradle
test {
  ...
  exclude '**/DeletionVectorTestSpark4_0*'          // never runs in the default-v2 fork
}

tasks.register('deletionVectorTest', Test) {
  useJUnitPlatform()
  testClassesDirs = sourceSets.test.output.classesDirs
  classpath = sourceSets.test.runtimeClasspath
  jvmArgs(sparkModuleOpens)                          // same Spark-4.0/Java-17 --add-opens set
  systemProperty 'cluster.iceberg.format-version', '3'   // v3 for THIS fork only
  include '**/DeletionVectorTestSpark4_0*'
}
check.dependsOn 'deletionVectorTest'
```

Why this is robust (not ordering-dependent):
- Gradle runs each `Test` task in its **own** forked JVM. `systemProperty` lands on the
  `deletionVectorTest` fork **only**; the default `test` fork never sees it → stays v2.
- The DV test is **excluded** from `test`, so it can never accidentally run at v2 (which would fail
  its `format-version==3` assertion). Verified: running `test --tests "*.DeletionVectorTestSpark4_0"`
  reports *"No tests found for given includes … (exclude rules)"*.
- Isolation is by **JVM fork**, not by JUnit ordering or by racing the singleton — deterministic.
- The evaluated alternatives (a) pre-set `OPENHOUSE_CLUSTER_CONFIG_PATH`/system-property before the
  singleton starts, and (b) a second dedicated `OpenHouseLocalServer` in the same JVM, both leave a
  window where another test class boots the shared singleton first and pins the JVM's server to the
  wrong format version. Rejected for that fragility.

---

## 2. Metadata-writer v3 proof — `testServerAuthorsFormatVersion3()`

Creates a plain table on the v3 fork and proves the OpenHouse metadata-writer emitted format-version 3
three independent ways:

1. Table property: `SHOW TBLPROPERTIES … format-version = 3`.
2. Iceberg API: `((HasTableOperations) table).operations().current().formatVersion() == 3` on the
   `TableMetadata` the server wrote (loaded through the stock `RESTCatalog`).
3. On-disk metadata JSON: the server-authored `*.metadata.json` literally declares
   `"format-version":3` (assertion is whitespace-tolerant; OpenHouse writes **compact** JSON, not
   pretty-printed).

Contrast control: the default `test` fork (no system property) creates v2 tables — verified
unchanged via `CatalogOperationTestSpark4_0.testCasingWithCTAS` PASSED.

---

## 3. Deletion-vector proof — `testMergeOnReadDeleteWritesDeletionVector()`

On a v3 merge-on-read table:

```sql
CREATE TABLE openhouse.dbdv.dv_delete (id bigint, data string) USING iceberg
TBLPROPERTIES (
  'write.format.default'='parquet',
  'write.delete.mode'='merge-on-read',
  'write.update.mode'='merge-on-read',
  'write.merge.mode'='merge-on-read');
INSERT INTO … VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e'),(6,'f');   -- 6 rows
DELETE FROM … WHERE id = 2;                                            -- row-level MOR delete
```

Assertions (all passed):
- **Read-back correctness:** survivors are exactly `[1,3,4,5,6]`; `SELECT count(*) WHERE id=2 == 0`.
- **DV, not classic pos-delete (metadata):** the `.delete_files` metadata table shows a delete file
  with `file_format = PUFFIN` and **zero** `PARQUET` delete files. In Iceberg both a classic
  positional delete and a deletion vector report `content = 1` (POSITION_DELETES); the v2-vs-v3
  discriminator is `file_format` — `PARQUET` = classic `*-deletes.parquet`, `PUFFIN` = deletion
  vector.
- **DV, physically:** the delete file is a `*.puffin` whose bytes carry a `deletion-vector-v1` blob
  (the on-disk fingerprint of an Iceberg v3 deletion vector).

Captured `[DV-PROOF]` stdout from the passing run:

```
[DV-PROOF] format-version=3
[DV-PROOF] delete_files: content=1 file_format=PUFFIN record_count=1 \
    path=/tmp/dbdv/dv_delete-<uuid>/data/00000-5-<uuid>-00001-deletes.puffin
[DV-PROOF] puffin …-00001-deletes.puffin carries deletion-vector-v1 blob (size=479B); survivors=[1, 3, 4, 5, 6]
```

This is the merge-on-read `DELETE` → **puffin deletion vector** → correct read-back chain, committed
through the OpenHouse `/iceberg` REST server's v3-metadata-write path.

---

## 4. Spark-4.0 / Iceberg-1.11 gotchas hit

- **Format version is server-forced, not client-set.** A `TBLPROPERTIES('format-version'='3')` hint
  is silently overwritten by the cluster default (§1). The only lever is
  `cluster.iceberg.format-version`. This is the whole reason the test needs a configured server fork
  rather than a client-side property.
- **Singleton server per JVM.** `OpenHouseRestSparkITest` boots one shared server per JVM, so the
  format-version choice is JVM-global. Hence the separate-fork isolation (§1); do **not** try to mix
  v2 and v3 tables in one itest JVM by ordering.
- **Compact metadata JSON.** OpenHouse's writer emits compact JSON (`"format-version":3`), not the
  Iceberg default pretty-printer (`"format-version" : 3`). A naive `contains("\"format-version\" : 3")`
  assertion fails; match with tolerant whitespace.
- **DV files report `content=POSITION_DELETES`, not a distinct "DELETION_VECTORS" content code.** The
  proof that a DV (not a classic pos-delete) was written is `file_format=PUFFIN` + the
  `deletion-vector-v1` puffin blob — **not** the `content` column (which is `1` for both).
- **No 1.11 fork patch on spark/v4.0.** The Spark-4.0 lane is stock upstream Iceberg 1.11; DVs work
  with no OpenHouse Spark-side change. (The 1.11 core/api fork patches are unrelated to DVs — see
  `rung3-dv/10-progress.md`.)
- **Not a version-bump gotcha:** Iceberg **1.10 already** writes mature DVs at v3; 1.11 keeps the
  behavior identical (see `rung3-dv/10-progress.md` — same DV shape on both). 1.11 is not the
  enabling version; the enabling lever is `cluster.iceberg.format-version=3`.

---

## 5. Cost assessment — flipping the cluster default to v3 **globally**

This rung deliberately does **not** flip `cluster.iceberg.format-version` to 3 in production/CI
cluster config; it enables v3 only in the isolated DV fork. If the default were flipped globally,
the cost surface is:

- **v2 reader cliff (the big one).** Every **new** and **replaced** table would be authored v3, and
  v3 deletion vectors (puffin) are unreadable by engines/clients that predate v3 DV support: older
  Spark/Iceberg runtimes, older Trino/Presto, and any bespoke v2-only reader in the fleet would fail
  to read affected tables (or silently miss deletes). This is precisely the "v3 read cliff" flagged
  for **Rung 9**; it must be assessed against the actual reader population before any global flip.
- **Existing tables are NOT auto-upgraded on normal writes.** The force runs only on create and
  full replace (RTAS/CTAS), not on appends/updates/deletes (`OpenHouseInternalRepositoryImpl.save`,
  §1). So a global flip upgrades **new** and **replaced** tables to v3 but leaves already-created v2
  tables at v2 until they are explicitly replaced — a gradual, not instantaneous, cliff.
- **Green-suite risk.** The Spark-4.0 itest suite is validated at the **v2** default (1637/28/32).
  Flipping the itest JVM to v3 globally could turn currently-green tests red where they assert
  v2-specific delete/metadata behavior — exactly why this rung isolates v3 to one fork and leaves
  `test` at v2. Any future global flip must re-baseline the whole matrix at v3 first.
- **Write-path behavior change.** At v3 with merge-on-read, row-level deletes/updates/merges emit
  puffin DVs instead of parquet positional deletes: different file layout, different compaction/maint
  characteristics, and puffin-aware tooling required for any downstream that inspects delete files.
- **Low-risk aspects.** The 1.11 metadata-writer emits valid v3 metadata (proven here); the write
  path itself is stable, and v3 tables read correctly within the modern stack. The risk is almost
  entirely on the **read** side (fleet compatibility), not the write side.

Recommendation: keep the cluster default at **2**; enable v3 per-table/per-cluster only once the
Rung-9 read-cliff assessment clears the reader population. The isolated fork proves the capability
without paying the cliff.

---

## 6. Verification (targeted, local)

```
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 LANG=C.UTF-8
# v3 DV proof (isolated fork, cluster.iceberg.format-version=3):
./gradlew -Dfile.encoding=UTF-8 \
  :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:deletionVectorTest
# default-v2 path unchanged:
./gradlew -Dfile.encoding=UTF-8 \
  :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
  --tests "*.CatalogOperationTestSpark4_0.testCasingWithCTAS"
```

Results:

```
DeletionVectorTestSpark4_0 > testServerAuthorsFormatVersion3() PASSED
DeletionVectorTestSpark4_0 > testMergeOnReadDeleteWritesDeletionVector() PASSED
BUILD SUCCESSFUL

CatalogOperationTestSpark4_0 > testCasingWithCTAS() PASSED            (default v2 fork, unchanged)
BUILD SUCCESSFUL

test --tests "*.DeletionVectorTestSpark4_0"  →  No tests found … (exclude rules)   (isolation proven)
```

CI note: the DV test runs only in the new `deletionVectorTest` task (wired to `check`), in its own
v3 fork. The existing `test` task — the one the green `Branch 1.11 CI` push run exercises — is
byte-for-byte the same set of classes at v2, with `DeletionVectorTestSpark4_0` excluded, so the
existing green suite cannot flip.
