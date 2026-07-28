# Rung 2 — Detailed Plan (Spark 3.5 → 4.0, Scala 2.12 → 2.13, Java 17, REST cutover)

Expands Phase 2 of `10-phase-plan.md`. **One axis held: Iceberg stays 1.10** (from rung 1). What
moves: **Spark 3.5→4.0, Scala 2.12→2.13, Java-11→17 runtime**, and the **client catalog** from the
custom `OpenHouseCatalog` to the **stock Iceberg REST client** (REST-first). Rung-1 stack
(Iceberg 1.10, Hadoop 3.3.4, Java-8 bytecode + resolution relax, the F1 shaded/unshaded exclusion,
`HARNESS_STACK=1`) is the baseline this builds on.

Legend: 🎯 decision gate · ✅ harness/itest gate · 🔬 spike · ⚠️ known Spark-4 breakage class.

---

## 0. Entry gate & the two-path structure

Rung 2 forks on **Spike B** (the REST write/commit path). Do Spike B **first**; it decides which
body of rung-2 work runs.

- **Path A — REST-first (committed default).** Spark 4 uses the **stock**
  `iceberg-spark-runtime-4.0_2.13:1.10.0` configured as an Iceberg `RESTCatalog` pointed at a new
  OpenHouse `/iceberg/v1/*` endpoint. OpenHouse ships **no** custom Spark-4 catalog jar. Custom
  `OpenHouseCatalog` / parser / plans / execs are **not** ported (except possibly the policy-DDL
  sugar, see §5). Engine-side new code ≈ 0; the work is server-side REST controllers (Java-8
  bytecode, existing Spring services) + the harness port.
- **Path B — fallback (only if Spike B fails fidelity).** Produce
  `integrations/spark/spark-4.0/openhouse-spark-runtime_2.13`: port `OpenHouseCatalog` +
  `OpenhouseSparkSqlExtensionsParser` + the ANTLR grammar + 8 logical plans + 8 V2 exec nodes to
  Spark-4 Catalyst/DSv2 on Scala 2.13. Larger, owned forever.

**🎯 Gate G-REST (from Spike B):** a stock Iceberg REST client performs create / append / MoR
delete / read against the embedded `OpenHouseLocalServer`, reaching the same server commit path as
the native client, with correct error mapping. Pass → Path A. Fail → Path B.

---

## 1. 🔬 Spike B — REST write/commit path (the pivot; do first)

**Goal:** prove OpenHouse can serve the Iceberg REST *commit* protocol over its existing two-stage
optimistic-lock snapshot commit, so a stock client can write.

### 1.1 Server surface to build
- New package `com.linkedin.openhouse.tables.rest` in `services/tables` (Java-8 bytecode, Spring).
  ~12 endpoints under `/iceberg/v1/`:
  - **config**: `GET /v1/config`
  - **namespaces**: `GET/POST /v1/namespaces`, `GET/DELETE /v1/namespaces/{ns}`,
    `HEAD /v1/namespaces/{ns}` (→ reuse `DatabasesApiHandler`)
  - **tables**: `GET /v1/namespaces/{ns}/tables`, `POST …/tables` (create),
    `GET/HEAD/DELETE …/tables/{t}` (load/exists/drop), **`POST …/tables/{t}` (commit /
    updateTable)** ← the crux (→ reuse `TablesApiHandler` + `IcebergSnapshotsApiHandler`)
- **Serde**: Iceberg REST wire types (`LoadTableResponse`, `UpdateTableRequest`/`MetadataUpdate`,
  `CommitTableResponse`, `CreateTableRequest`, error model). Prefer contract-first codegen from the
  vendored Iceberg REST OpenAPI spec (mirror linkedin/openhouse #498 approach) over hand-rolling.
- **Commit routing**: the `UpdateTableRequest` carries `requirements` + `updates`. Discriminate
  **snapshot updates** (`add-snapshot`, `set-snapshot-ref`) → `IcebergSnapshotsService`, vs
  **metadata-only** (schema/spec/props/policy) → `TablesService`. Both land on
  `OpenHouseInternalTableOperations.doCommit` → `TableMetadataParser.write` (server writes metadata
  directly — C3). The overridden `commit`/`doRefresh` behavior (F-COMMIT1) must be preserved.
- **Exception mapping**: a scoped `@RestControllerAdvice` → Iceberg REST `ErrorResponse` (respect
  the S1 finding — surface `ErrorResponseBody.message`, not a 6000-char stacktrace).

### 1.2 Validation (embedded server, LocalFS+H2 — same fidelity envelope as the harness)
- Stock `RESTCatalog` (Iceberg 1.10, from a Java client or a Spark-3.5 session to keep the spike off
  the Spark-4 critical path) does: create → `INSERT` (append) → MoR `DELETE` → `SELECT` read-back,
  all committing through the real controllers.
- Confirm optimistic-lock conflict path (two concurrent commits → one 409 `CommitFailedException`).

### 1.3 Known MVP constraints to record (from #607)
Single-level namespaces only; no views, no multi-table transactions, no credential vending. Note
these as REST-endpoint scope limits (the OpenHouse table model is single-level DB.table anyway).

**Exit:** G-REST decided. Everything below assumes **Path A**; §5B carries the Path-B fallback.

---

## 2. Build topology — the `spark-4.0` module set

### 2.1 New modules (mirror the spark-3.5 tree)
- `integrations/spark/spark-4.0/openhouse-spark-runtime` → project name `openhouse-spark-4.0-runtime_2.13`
- `integrations/spark/spark-4.0/openhouse-spark-itest`   → `openhouse-spark-4.0-itest`
- `apps/spark-4.0` (maintenance jobs; can lag — not harness-critical)
- Under **Path A** the runtime module is thin/near-empty (maybe only a `spark-session-extensions`
  registration for policy DDL, §5A). Under Path B it is the full ported runtime.

### 2.2 `settings.gradle`
- `include ':integrations:spark:spark-4.0:openhouse-spark-runtime'` (+ itest), `':apps:spark-4.0'`.
- Rename block: `project(':integrations:spark:spark-4.0:openhouse-spark-runtime').name =
  'openhouse-spark-4.0-runtime_2.13'`, etc. Mirror the existing `_2.12` rename wiring.

### 2.3 Versions / conventions
- Root `build.gradle` ext: `spark_40_version = '4.0.0'`, `scala_213_version = '2.13.x'`.
- New convention `openhouse.apps-spark-4.0-common.gradle` (Scala 2.13 variant of
  `apps-spark-common`), or parameterize the existing one by Scala version.
- Depend on **stock** `org.apache.iceberg:iceberg-spark-runtime-4.0_2.13:1.10.0` (shaded fat jar).
- Java: the spark-4.0 modules compile to **Java-17 bytecode** (Spark 4 needs 17; these are the one
  lane that is *not* Java-8 — invariant C4). Server + legacy stay Java-8 bytecode.

### 2.4 Scala 2.12/2.13 coexistence
- The `_2.12` (spark-3.1/3.5) and `_2.13` (spark-4.0) trees coexist (additive). No shared Scala
  module may be consumed by both without cross-build; keep the spark-4.0 Scala self-contained.

---

## 3. Scala 2.12 → 2.13

Scope is small under Path A (the runtime is thin) but the **harness** is Scala and must cross to
2.13 (§6). Known 2.13 source deltas to expect:
- `scala.collection.JavaConverters` → `scala.jdk.CollectionConverters`.
- `scala.Seq` is now `immutable.Seq`; `varargs`/`.to(coll)` signature changes; `breakOut` gone.
- `mapValues`/`filterKeys` now return views (`.toMap` to force).
- Numeric/`Ordering` and `TupleN` implicit tweaks.
- Fetch scala-compiler/reflect/library **2.13.x** jars into `~/.m2` (mirror the rung-1 fetch of
  2.12.18) and update `run-openhouse.sh` `SCALAC_CP` + `SCALA_LIB`.

---

## 4. Java 17 runtime

- Spark 4 requires Java 17 (already the harness runtime). Reuse the rung-1 `--add-opens` set +
  `jakarta.xml.bind`/jaxb pattern (`apps-spark-common` already carries `>= VERSION_1_9` blocks).
- Verify no new `--add-opens` are needed for Spark 4's Catalyst/Tungsten (Spark 4 documents its
  required opens; align `run-openhouse.sh` OPENS with Spark 4's list).

---

## 5. Realizing the catalog decision

### 5A. Path A (REST-first) — client wiring + policy DDL
- **Catalog config**: the harness/itests configure a stock Iceberg `RESTCatalog`:
  `spark.sql.catalog.openhouse = org.apache.iceberg.spark.SparkCatalog`,
  `…​.catalog-impl = org.apache.iceberg.rest.RESTCatalog`, `…​.uri = http://localhost:<port>/iceberg/v1`,
  `…​.token = dummy.token`. No `OpenHouseCatalog` class.
- **Policy DDL (D2 — decide in Phase 0.5, realize here).** The 8 OpenHouse policy statements
  (`SET POLICY (RETENTION=…)`, sharing/replication/history/column-tag/grants) are **not** stock
  Iceberg SQL. Two options:
  - **(D2-a) `SET TBLPROPERTIES` over REST (leaner; default lean).** Express policies as reserved
    table properties the server interprets; drop the custom grammar. Stock `ALTER TABLE … SET
    TBLPROPERTIES` flows through the REST commit → server policy interpreter. **Removes the parser +
    8 plans + 8 execs entirely.** Cost: SQL surface changes for users (migration/UX).
  - **(D2-b) Thin Spark-4 SessionExtension.** Re-implement ONLY the policy parser + plans + execs on
    Spark-4 Catalyst/DSv2 (Scala 2.13), issuing REST/property updates. Preserves the SQL sugar.
    ⚠️ carries the Catalyst-parser port cost (§5B parser notes apply).
- The fork's LinkedIn-original write-behavior patches (rung-1 §C.2) that don't auto-carry to a stock
  client are re-homed as **server-set table properties** here (distribution-mode=none default,
  split-size, delete-file replication) — not client patches.

### 5B. Path B (fallback) — port the custom Spark integration to Spark 4
Only if G-REST fails. Port surface + ⚠️ Spark-4 breakage classes:
- `OpenHouseCatalog` / `OpenHouseSparkCatalog` (extends Iceberg `SparkCatalog`/`SparkSessionCatalog`)
  — ⚠️ DSv2 `TableCatalog`/`StagingTableCatalog` signature changes; `Table`/`ScanBuilder` API.
- `OpenhouseSparkSqlExtensionsParser.scala` + `OpenhouseSqlExtensions.g4` + `OpenhouseSqlExtensionsAstBuilder`
  — ⚠️ Spark 4 `ParserInterface`/`AstBuilder` signature changes; regenerate ANTLR against Spark 4's
  grammar base; `ParserUtils` moved/renamed.
- 8 logical plans (`sql/catalyst/plans/logical/*`) — ⚠️ `LogicalPlan`/`UnaryCommand` API; `output`
  and `withNewChildrenInternal` now required.
- 8 V2 exec nodes (`execution/datasources/v2/*Exec`) — ⚠️ `V2CommandExec` API changes.
- `OpenhouseSparkSessionExtensions.scala` — extension registration API is stable-ish; verify.

---

## 6. Port the delta-harness to Spark 4.0 / Scala 2.13 (the gate vehicle)

The harness IS the rung-2 gate; it must run on Spark 4 / Scala 2.13 before it can judge anything.
- **Scala 2.13** cross-compile of `src/main/scala/harness/**` (§3 deltas).
- **Catalog wiring** (`OpenHouseMatrix.start()` ~line 3692): point Spark at the stock `RESTCatalog`
  (Path A) instead of `OpenHouseCatalog`; keep `OpenHouseLocalServer` boot + `fs.defaultFS=file:///`.
- **Spark/Iceberg API touch-ups** the harness uses: DataFrame `writeTo`/`overwritePartitions`,
  `CALL openhouse.system.*` procedures (stock Iceberg procedures under REST — verify names),
  metadata-table reads (`.snapshots/.history/.files`), `MERGE`/DML SQL.
- **`run-openhouse.sh`**: Scala 2.13 scalac; Spark-4 OPENS; classpath via the same
  `printHarnessCp` init-script (update the F1 exclusion group if the spark-4.0 fat jar bundles
  differently; likely identical `org.apache.iceberg` exclude).
- **Self-test the harness** (the "test-the-tester" layer) before trusting the gate.

---

## 7. ✅ Rung-2 gate + the Spark-4 behavior diff surface

Run the full matrix on **Spark 4.0 / Iceberg 1.10 / Scala 2.13 / Java 17 (REST)**. Target: green
**≡ rung-1** (1696 green + tags). Spark 4 *legitimately* changes behavior; each diff is match or a
recorded finding (never blanket-updated). Enumerate the expected diff surface:
- ⚠️ **ANSI mode ON by default** (Spark 4) — the deferred type-mismatch/overflow negatives
  (TEST-PLAN Phase 11) may now become clean errors rather than nulls; the harness's non-ANSI
  assumptions in a few negatives will shift → re-evaluate, likely *newly assertable*.
- ⚠️ **Error/exception classes** — Spark 4 error-class framework changes messages/types; the
  negative/contract phase (asserts typed exceptions) is the sharp edge. Use `HARNESS_STACK=1` to
  root-cause each, as with F-WAP-BRANCH.
- ⚠️ **MERGE / row-level** semantics + **CoW/MoR** defaults may differ.
- The F-WAP-BRANCH tagged case: re-check under Spark 4 (the `useRef` precedence lives in
  Iceberg 1.10, unchanged here, so likely still tagged).
- **REST-path parity**: DML/DDL/negatives must behave the same *through REST* as they did through
  `OpenHouseCatalog` — any REST-vs-native divergence is a Spike-B follow-up finding.

**Exit:** rung-2 gate green-or-tagged; REST-first proven end-to-end on Spark 4 (or Path B shipped);
policy-DDL disposition realized; fork write-patches re-homed as server properties.

---

## 8. Rung-2-specific risks
- **R2-REST-WRITE** (highest) — commit-path fidelity; mitigated by Spike B first.
- **R2-ANSI** — ANSI-on changes many type/negative behaviors at once; isolate via the harness diff.
- **R2-PARSER** (Path B or D2-b only) — Catalyst parser/AstBuilder port is the thorniest Spark-4 code.
- **R2-PROC** — Iceberg stored procedures (`rollback_to_snapshot`, `rewrite_data_files`, …) under
  the REST/stock path: confirm they're callable and namespaced the same.
- **R2-SCALA213** — harness cross-compile; contained, self-tested.

## 9. Sequencing
```
Spike B (§1) ──🎯G-REST──► [A] REST endpoint (§1.1 hardened) ─┐
                           [B] port catalog (§5B) ────────────┤
build topology spark-4.0 (§2) ──► Scala 2.13 (§3) ──► Java 17 (§4) ──► catalog wiring (§5A) ──►
   harness port (§6) ──► ✅ rung-2 gate (§7)
```
Server REST endpoint (§1.1) and build topology (§2) can proceed in parallel after G-REST.

## 10. What this rung does NOT do
- Iceberg stays **1.10** (no 1.11, no v3/DV — that's rung 3).
- No HDFS validation (LocalFS+H2 envelope unchanged; F-VACUITY-HADOOP still stands).
- No retirement of the spark-3.5/_2.12 lane (coexistence).
