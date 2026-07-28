# Phase Plan — Spark 4.0 / Iceberg 1.11 / DV Upgrade

Five phases. Phase 0 is prerequisite scaffolding and the go/no-go spikes. Phases 1–3 are the three
ladder rungs (one axis each). Phase 4 is cutover hardening and sign-off. Each **Step** states its
**Solution** (what changes), **Verification** (how we prove it), and **Enumeration** (the concrete
modules/files/cases). Detail is intentionally uneven — the *shape* is fixed; leaf specifics are
discovered during execution and recorded in `BUILD-STATUS.md`.

Convention: 🎯 = decision gate; ✅ = harness/itest gate; 🔬 = spike (bounded, time-boxed).

---

## Phase 0 — Baseline, scaffolding, and go/no-go spikes

*Goal: a trustworthy starting line and the two facts that de-risk the whole ladder, before any
version bump.*

### 0.1 Capture the rung-0 baseline
- **Solution.** Run the delta-harness full matrix on the **current** stack (Spark 3.5 / Iceberg
  1.5.2 fork / Scala 2.12 / JDK 17-build) and freeze the result as the reference every later rung
  diffs against.
- **Verification.** Reproduce the recorded ~1,697-green / tagged-bug set; archive the run log as
  `BUILD-STATUS.md → rung-0`. If it does not reproduce, stop — the baseline is not trustworthy.
- **Enumeration.** `integrations/spark/delta-harness/run-openhouse.sh` (full matrix);
  `VERIFIED-RUN-openhouse.txt` as the comparand; the four existing tagged bugs (`BUGS.md`) are the
  known-non-green set.

### 0.2 Toolchain & build discipline
- **Solution.** Standardize on a **JDK 17 toolchain** that compiles legacy modules to
  `-target 8` (C4) and the new lane to 17. Confirm Lombok 1.18.20 compiles on 17 (it does; not on
  21+). Pin the Scala 2.13 line for the future Spark-4 modules.
- **Verification.** Clean build of the *current* repo on the JDK 17 toolchain, legacy artifacts
  still class-file v52; existing itests green. No behavior change — this is a toolchain move only.
- **Enumeration.** `buildSrc/src/main/groovy/openhouse.java-minimal-conventions.gradle` (keep
  `VERSION_1_8`), `openhouse.apps-spark-common.gradle` / `openhouse.tables-test-fixtures-common.gradle`
  (existing `>= VERSION_1_9` add-opens blocks are the template for Java-17 runtime flags).

### 0.3 🔬 Spike A — HDFS client on Java 17 against RBF
- **Solution.** Stand the OpenHouse server (or a minimal `FileIOManager` harness) on a **Java 17
  runtime** and exercise real read/write to the RBF-fronted HDFS 3.1/3.2 cluster with the current
  `hadoop-client`, adding `--add-opens` + a `jakarta/jaxb` dep as needed. If the current client
  fails, retry with `hadoop-client` 3.3.x/3.4.x and validate by RPC **wire-compat** against the
  routers.
- **Verification.** A metadata.json write + manifest write + read-back round-trips against the RBF
  cluster on Java 17. Record the exact flags/deps and the minimum client version that works.
- **Enumeration.** `iceberg/openhouse/internalcatalog/.../fileio/{FileIOManager,FileIOConfig}.java`;
  `openhouse.hadoop-conventions.gradle` (`hadoopVersion`); `application-hdfs-diagnostics.properties`.
- **Go/no-go.** If no client version reads/writes on Java 17 against RBF, **rung 3 is blocked** and
  escalates to an infra decision (a v3-authoring path that is not the Java-8 server). Expected
  outcome: passes with flags — bounded.

### 0.4 🔬 Spike B — REST write/commit path fidelity
- **Solution.** Prototype the Iceberg REST **commit** endpoint over OpenHouse's existing two-stage
  optimistic-lock snapshot commit (extend the read-only prior-art). Drive one real
  `updateTable`/commit from a stock Iceberg REST client against the embedded `OpenHouseLocalServer`.
- **Verification.** A stock-client `INSERT` + `DELETE` (MoR) commits and is read back correctly,
  reaching the same server code path as the native OpenHouse client. Confirm snapshot-vs-metadata
  discrimination and error mapping.
- **Enumeration.** Prior-art `com.linkedin.openhouse.tables.rest` (PR #607, ~960 lines, `/iceberg/v1/*`);
  `TablesApiHandler`, `IcebergSnapshotsApiHandler`, `DatabasesApiHandler`; server
  `OpenHouseInternalTableOperations.doCommit`.
- **🎯 Gate.** Result decides rung 2: **REST-first confirmed** (expected) vs **Path B fallback**.
  Also decides the policy-DDL question (0.5).

### 0.5 🎯 Decide the policy-DDL disposition
- **Solution.** Choose how the 8 OpenHouse policy statements survive on Spark 4: (a) a **thin
  Spark-4 SessionExtension** re-implementing only the parser + plans + execs for policy DDL over
  the REST catalog, or (b) **retire the SQL sugar** and express policies via `ALTER TABLE SET
  TBLPROPERTIES` interpreted server-side.
- **Verification.** N/A (decision). Recorded in `20-risks-decisions-findings.md`.
- **Enumeration.** `integrations/spark/spark-3.5/openhouse-spark-runtime/.../sql/catalyst/plans/logical/*`
  (Grant/Revoke, Set{Retention,Sharing,Replication,History}Policy, SetColumnPolicyTag, ShowGrants,
  UnSetReplicationPolicy) + matching `execution/datasources/v2/*Exec`; the ANTLR grammar
  `spark-3.1/.../parser/extensions/OpenhouseSqlExtensions.g4`.

### 0.6 Fork-patch triage
- **Solution.** Categorize every LinkedIn-original patch on `mkuchenbecker/iceberg@openhouse-1.5.2`
  into `{obsolete-upstream-in-1.10/1.11, LinkedIn-original-must-report, cve/dep-bump, uncertain}`.
  Drop backports; list the re-port shortlist, split Spark-integration vs Core/API.
- **Verification.** Each "obsolete" claim is checked against the target Iceberg changelog/PR before
  it is dropped. Recorded in the fork-patch ledger.
- **Enumeration.** Candidate LinkedIn-originals from the branch log: #249 (partitioned write
  distribution NONE), #214 (app-name in snapshot metadata), #236 (app-name in EnvironmentContext),
  #229 (delete-file replication), #228 (split-size SparkSQLProperty), #219 (delete-file replication
  factor), #224 (don't-rewrite-Spark-views), #251 (column-default APIs / `Expressions.lit()`).
  Backports to drop: #245, #246, #241, #234, #233 (verify each).

**Phase 0 exit:** rung-0 baseline frozen; both spikes resolved; REST-first vs Path B decided;
policy-DDL disposition decided; fork ledger drafted.

---

## Phase 1 — Rung 1: Iceberg 1.10 + Java 11 runtime + modern Hadoop (keep Spark 3.5)

*One axis: the Iceberg-version + Java-runtime + Hadoop move. Spark and Scala are held fixed. This
rung de-risks the entire Iceberg-API port and the Java-11 runtime move **without** any Spark 4
churn — so any behavior change here is attributable to Iceberg/Hadoop alone.*

### 1.1 Fork → Iceberg 1.10
- **Solution.** Rebase the LinkedIn-original patches (from 0.6) onto Apache Iceberg **1.10**; drop
  the backports. Publish a `1.10.x` fork build, or — where a patch is now upstream — consume
  **stock** 1.10 for that module. Prefer shrinking the fork.
- **Verification.** Fork builds on JDK 17 toolchain; the re-ported patches carry their original
  unit tests (or new ones) and pass. Diff the fork's public surface vs stock 1.10 → the re-port
  shortlist and nothing more.
- **Enumeration.** `buildSrc/.../openhouse.iceberg-conventions-1.5.2.gradle` → add/replace with a
  `1.10` convention; `iceberg_1_5_version` → `iceberg_1_10_version`. Spark-integration patches
  (#249, #228, #224, #214) land in the fork's `spark/v3.5` tree.

### 1.2 Server/metadata-writer → iceberg-core 1.10, Java 11 runtime
- **Solution.** Compile `internalcatalog` + tables/housetables services against iceberg-core 1.10;
  adapt to 1.5.2→1.10 API deltas (catalog/table-operations/metadata builders). Move the server
  **runtime** to Java 11; bytecode target stays 8 (C4). Apply Spike-A's Hadoop flags/version.
- **Verification.** Server unit + H2 e2e tests green (`TablesControllerTest`, the
  `OpenHouseInternalTableOperationsTest`); a table create→commit→read round-trips to the
  RBF cluster on Java 11.
- **Enumeration.** `OpenHouseInternalCatalog`, `OpenHouseInternalTableOperations`, `SnapshotsUtil`,
  `MetadataUpdateUtils`, `HouseTableSerdeUtils`, `HouseTableMapper`; `FileIOManager`;
  `services/{tables,housetables,jobs}/build.gradle`.

### 1.3 Client spark-3.5 runtime + java-runtime → 1.10
- **Solution.** Compile the existing `spark-3.5` OpenHouse runtime and `iceberg-1.5` java-runtime
  against Iceberg 1.10; keep Spark 3.5 / Scala 2.12. Custom catalog + policy extensions unchanged
  in shape — only the Iceberg API they call moves.
- **Verification.** Existing `openhouse-spark-3.5-itest` green on Iceberg 1.10.
- **Enumeration.** `integrations/spark/spark-3.5/openhouse-spark-runtime`,
  `integrations/java/iceberg-1.5/openhouse-java-runtime`; convention plugin bump.

### 1.4 ✅ Rung-1 harness gate
- **Solution.** Run the delta-harness full matrix on Spark 3.5 / Iceberg 1.10 / Java 11.
- **Verification.** Green **≡ rung-0 baseline**. Every diff is explained: a regression is a blocker;
  a newly-passing case (a fixed bug) is a recorded finding; a new failure becomes a tagged bug only
  with a root-caused reason. No net loss of green.
- **Enumeration.** Full matrix; explicit re-check of the delete-file-replication and split-size
  behaviors (fork patches #229/#228/#219) and distribution-mode default (#249).

**Phase 1 exit:** the whole stack runs on Iceberg 1.10 + Java 11, harness parity with rung-0,
Hadoop-on-J-modern proven, fork shrunk to the LinkedIn-original shortlist.

---

## Phase 2 — Rung 2: Spark 4.0 + Scala 2.13 + Java 17 (keep Iceberg 1.10) — the REST cutover

*One axis: the Spark + Scala + Java-runtime move, Iceberg held at 1.10. This is where REST-first
lands: the Spark-4 client is stock Iceberg; OpenHouse ships no custom Spark-4 catalog.*

### 2.1 Add the spark-4.0 module set
- **Solution.** Create `integrations/spark/spark-4.0/openhouse-spark-runtime` (+ `-itest`) and
  `apps/spark-4.0`, named with the `_2.13` suffix, cross-compiled to Scala 2.13, Java-17 bytecode.
  Depend on **stock** `iceberg-spark-runtime-4.0_2.13:1.10.0`.
- **Verification.** Modules compile on Scala 2.13 / JDK 17; a trivial Spark-4 session boots and
  loads the stock Iceberg REST catalog.
- **Enumeration.** `settings.gradle` (new `spark-4.0` includes + `_2.13` project-name wiring, mirror
  of the `_2.12` rename block); `openhouse.apps-spark-common.gradle` (Scala 2.13 variant);
  root `build.gradle` `spark_version`/`scala` axes.

### 2.2 REST catalog server endpoint (read + **write**)
- **Solution.** Land the Iceberg REST controller in the server (Java-8 bytecode, existing Spring
  services), reusing the read-only prior-art and adding the **commit/write** path proven in Spike B:
  `/iceberg/v1/*` namespaces + tables + commit, routed through the existing snapshot/metadata
  handlers and two-stage optimistic lock.
- **Verification.** Server e2e: stock Iceberg REST client performs create / DML (CoW **and** MoR) /
  schema evolve / read against the embedded server; error bodies mapped (the S1 systemic finding is
  respected, not regressed).
- **Enumeration.** `com.linkedin.openhouse.tables.rest.*`; `TablesApiHandler`,
  `IcebergSnapshotsApiHandler`, `DatabasesApiHandler`; commit → `OpenHouseInternalTableOperations`.

### 2.3 Policy-DDL disposition (from 0.5)
- **Solution.** Execute the chosen option: either a **thin Spark-4 SessionExtension** (parser + the
  8 plans + 8 execs re-implemented on Spark 4 Catalyst/DSv2, Scala 2.13, talking to REST), or the
  **TBLPROPERTIES** re-expression with server-side interpretation.
- **Verification.** The policy round-trips the delta-harness DDL phases exercise (retention,
  sharing, replication, history, column-tag, grants) behave identically on Spark 4.
- **Enumeration.** Either the ported `sql/catalyst/plans/logical/*` + `execution/datasources/v2/*Exec`
  on Scala 2.13, or the removal of the grammar + a server-side `TBLPROPERTIES` policy interpreter.

### 2.4 Port the delta-harness to Spark 4.0 / Scala 2.13
- **Solution.** Cross-compile the harness to Scala 2.13 and Spark 4; adapt any Spark/Iceberg API it
  touches (DataFrame writer/reader, `CALL` procedures, catalog wiring to the REST endpoint).
- **Verification.** Harness compiles and its self-tests (the "test-the-tester" layer) pass before it
  is trusted to gate.
- **Enumeration.** `integrations/spark/delta-harness/src` (Scala), `run-openhouse.sh` (classpath +
  Scala 2.13 scalac), `OpenHouseEnv`/`TestSparkSessionUtil` wiring to the REST catalog.

### 2.5 ✅ Rung-2 harness gate
- **Solution.** Run the full matrix on Spark 4.0 / Iceberg 1.10 / Java 17 / Scala 2.13, via the REST
  catalog.
- **Verification.** Green **≡ rung-1**. Spark-4 behavior changes (ANSI defaults, MERGE/CoW/MoR
  semantics, error types) are the expected diff surface — each either matches or is a recorded,
  reasoned finding. The DDL/negative phases (which assert typed exceptions) are the sharp edge here.
- **Enumeration.** Full matrix, with emphasis on the negative/contract phase (exception types shift
  most between Spark 3.5 and 4.0) and the CoW/MoR mode phases.

**Phase 2 exit:** Spark 4.0 runs green on Iceberg 1.10 through the stock Iceberg REST client;
custom Spark-4 catalog code is either absent (REST-first) or scoped to policy DDL only.

---

## Phase 3 — Rung 3: Iceberg 1.11 + v3 DSv2 deletion vectors (the goal)

*One axis: the Iceberg 1.10 → 1.11 move, unlocking format-version 3 and deletion vectors. Both
client (stock runtime) and **server** move to 1.11; the server becomes v3/DV-aware.*

### 3.1 Bump client + server to Iceberg 1.11
- **Solution.** Move the stock Spark-4 runtime to `...-4.0_2.13:1.11.x`; move server + java-runtime
  iceberg-core to 1.11 (Java-17-only). Re-port any remaining LinkedIn-original patches onto 1.11 or
  confirm retired (many should be upstream by 1.11).
- **Verification.** Full stack builds/boots on 1.11; rung-2 harness gate re-runs green on 1.11
  (v2 tables) **before** any DV work — isolates "1.10→1.11" from "enable v3."
- **Enumeration.** iceberg convention → `1.11`; fork shortlist re-check against 1.11 changelog.

### 3.2 Server: v3 / deletion-vector-aware metadata authoring
- **Solution.** Make `OpenHouseInternalTableOperations` (direct write) **and** the REST commit
  handler serialize/validate **format-version=3** metadata, including DV (Puffin) delete-file
  manifest entries — the crux of C3. Gate `format-version=3` behind an explicit, per-table opt-in.
- **Verification.** A stock Spark-4/1.11 client commits a MoR delete that produces a **deletion
  vector**; the server persists v3 metadata; a second client reads the correct post-delete rows.
  A v2 table on the same server is unaffected.
- **Enumeration.** `OpenHouseInternalTableOperations`, `SnapshotsUtil`, `MetadataUpdateUtils`,
  `HouseTableSerdeUtils`; reserved-prop/validation path for `format-version`; the REST commit route.

### 3.3 DV write/read path + DSv2 modernization
- **Solution.** Enable the DSv2-modernized connector behavior 1.11 ships; confirm MoR
  delete/update/merge emit deletion vectors (not position-delete files) on v3 tables, and reads
  apply them.
- **Verification.** Physical assertion (mirroring the harness's existing CoW/MoR delete-file
  discriminator): on a v3 table, MoR mutations produce **one DV per data file** (Puffin), CoW
  produces none; reads reflect the DV.
- **Enumeration.** New harness axis `dv-*` crossing the mutation ops with `format-version=3`.

### 3.4 ✅ Rung-3 harness gate + DV battery
- **Solution.** Run the full matrix on 1.11, **plus** a new deletion-vector case battery.
- **Verification.** Full matrix green ≡ rung-2 (on v2 tables); DV battery green. Battery enumerated:
  - DV **produced** on MoR delete / update / merge (per format).
  - DV **read** correctness (subset delete, whole-file delete boundary, multiple deletes merged into
    one DV per file).
  - DV **+ compaction** (`rewrite_data_files` / `rewrite_position_delete_files` collapse DVs).
  - DV **+ time travel / rollback** (historical read before/after the DV commit).
  - DV **+ CDC/changelog** (the G13 finding — CDC over MoR was broken with position deletes at 1.5.2;
    re-evaluate under 1.11 DVs; tag if still unsupported).
  - DV **v3-cliff negative**: confirm a pre-1.9 reader is rejected/incompatible (documented, not a
    harness failure).
- **Enumeration.** `dv-*` battery in the harness; `BUGS.md`/`AUDIT-FINDINGS.md` for any new gap.

**Phase 3 exit:** the goal — Spark 4.0 / Iceberg 1.11 / DSv2 deletion vectors — validated green by
the harness, v3 opt-in gated, server authoring v3 metadata.

---

## Phase 4 — Cutover hardening, coexistence, and sign-off

*Goal: make the additive lane production-shaped and prove the old lane is untouched.*

### 4.1 Coexistence proof
- **Solution/Verification.** Build the full repo with **both** lanes present; run the legacy
  `spark-3.1`/`spark-3.5` itests **and** the new `spark-4.0` gate in one CI pass. Prove a v2 table
  written by the legacy lane is read by the new lane and vice-versa (v2 interop).
- **Enumeration.** `settings.gradle` full module set; CI matrix; a cross-lane interop itest.

### 4.2 Docker / full-service e2e (auth dimension)
- **Solution.** Bring up the `oh-s3-spark` compose stack with a Spark-4 client against the REST
  endpoint (the one fidelity dimension the embedded shim lacks: OPA/authorization). Runnable only
  in a Docker-capable environment — flagged as such.
- **Verification.** REST DML + policy ops under real authorization.
- **Enumeration.** `infra/recipes/docker-compose/oh-s3-spark/docker-compose.yml`.

### 4.3 Docs, governance, deprecation posture
- **Solution.** Write the v3/DV **opt-in governance** doc (C6), the coexistence/lifecycle note, and
  update `SETUP.md`/`ARCHITECTURE.md` for the new lane + REST endpoint. Do **not** schedule
  old-lane retirement here (separate decision).
- **Verification.** Docs reviewed; `BUILD-STATUS.md` closed out with the rung-3 gate record.

### 4.4 Final validation sign-off
- **Solution/Verification.** The delta-harness rung-3 run (full matrix + DV battery) is the
  acceptance artifact. Sign-off = green, diffs explained, findings filed, coexistence proven.

---

## Dependency spine (execution order)

```
0 (baseline + spikes + decisions)
      └─> 1 (Iceberg 1.10 / Java 11 / Spark 3.5)        ✅ gate ≡ rung-0
              └─> 2 (Spark 4 / Scala 2.13 / Java 17, REST)   ✅ gate ≡ rung-1
                      └─> 3 (Iceberg 1.11 + DVs)             ✅ gate ≡ rung-2 + DV battery
                              └─> 4 (coexistence + sign-off)
```

Tracks (fork, server/metadata-writer, harness-port) advance *within* each phase, never ahead of the
gate that validates them.
