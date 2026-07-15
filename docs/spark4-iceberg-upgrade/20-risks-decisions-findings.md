# Risks, Open Decisions, Fork Ledger, Findings

Pitfalls half of the system. Pairs with `10-phase-plan.md` (plan) and `BUILD-STATUS.md` (progress).
Nothing here is speculative padding — each risk has a trigger, an owner phase, and a mitigation.

---

## A. Risks (ranked by "could this stop rung 3")

### R1 — HDFS client on Java 17 (metadata-writer). **Highest uncertainty.**
- **Why it bites.** OpenHouse writes metadata directly (C3); v3/DV authoring forces the server onto
  iceberg-core 1.11 → Java 17 runtime. The server links `hadoop-client` to write to HDFS, and
  Hadoop's Java-17 runtime support is not fully official until 3.5.0 (3.4.x runs on 17 with fixes).
- **Not a bytecode issue** (C4): a Java-8-bytecode client runs fine on a Java 17 JVM beside
  Java-17 Iceberg. The risk is *runtime* — reflective-access (`--add-opens`) and JDK-removed APIs
  (JAXB → add `jakarta.xml.bind`).
- **Mitigation.** Phase 0 **Spike A**, before committing rung 3. HDFS is 3.1/3.2 + RBF with a stable
  wire protocol, so a client bump is validated by wire-compat, and the current client may already
  work on 17 with flags (the build already carries `>= VERSION_1_9` add-opens blocks).
- **Fallback if it fails.** A v3-authoring path that is not the Java-8 server (e.g. client-side
  metadata authoring for v3 tables, or a separate Java-17 authoring service) — an infra escalation,
  explicitly out of the happy path.

### R2 — REST write/commit fidelity.
- **Why it bites.** REST-first's entire value is that Spark 4 uses stock Iceberg. Prior-art
  (#607/#498–500) is **read-only**; the commit path over REST is unproven against OpenHouse's
  two-stage optimistic-lock model.
- **Mitigation.** Phase 0 **Spike B** decides REST-first vs the Path-B fallback *before* rung 2
  builds on it. The embedded `OpenHouseLocalServer` already runs the real controllers/services, so
  the spike is high-fidelity and cheap.
- **Fallback.** Path B — port the custom `OpenHouseCatalog` + parser + 8 plans + 8 execs to Spark 4
  Catalyst/DSv2 on Scala 2.13. Larger, owned-forever, but no REST dependency.

### R3 — Spark 3.5 → 4.0 behavior drift (the harness diff surface).
- **Why it bites.** Spark 4.0 changes ANSI defaults, error/exception types, MERGE/row-level
  semantics, and DataSource-V2 APIs. The harness's **negative/contract** and **DDL** phases assert
  *typed exceptions* and will legitimately shift.
- **Mitigation.** One-axis-per-rung means these land only at rung 2, isolated from Iceberg changes.
  Each shifted assertion is triaged as match-or-finding, never blanket-updated.

### R4 — Fork re-port scope creep.
- **Why it bites.** Re-porting LinkedIn patches onto a moving Iceberg is where hidden effort hides,
  especially Spark-integration patches (the Spark tree churns 3.5→4.0).
- **Mitigation.** Drop backports aggressively (ledger below); prefer stock 1.10/1.11 per module;
  shrink the fork every rung. The re-port shortlist is small and mostly Core/API.

### R5 — v3 read-compatibility cliff (governance, not code).
- **Why it bites.** `format-version=3`/DV tables are unreadable by pre-1.9 Iceberg → the retained
  Java-8/1.5.2 consumers break if v3 is enabled on tables they read (C6).
- **Mitigation.** v3 is **opt-in per table**, never global/default (invariant §5). Phase 4 ships the
  governance doc. The harness carries an explicit v3-cliff negative so the boundary is asserted.

### R6 — Scala 2.12 → 2.13 source incompatibilities.
- **Why it bites.** Collections API changes, `CollectionConverters`, variance nits — mostly in the
  Scala harness and any policy-DDL extension.
- **Mitigation.** Confined to rung 2; the harness self-tests gate before it is trusted.

---

## B. Open decisions (resolve in Phase 0)

| # | Decision | Options | Resolved by | Default lean |
|---|---|---|---|---|
| D1 | REST-first vs Path B | stock-Iceberg-REST client / port custom catalog | Spike B (0.4) | **REST-first** (committed; Path B is fallback) |
| D2 | Policy-DDL disposition | thin Spark-4 extension / `SET TBLPROPERTIES`+server | 0.5 | lean **TBLPROPERTIES** (deletes the most custom Spark code) — confirm against UX/compat |
| D3 | Keep a fork at all, or go stock 1.10/1.11 | keep shrunk fork / upstream-then-stock | 0.6 + per-rung | **shrink toward stock**; keep only LinkedIn-originals with no upstream equivalent |
| D4 | Server iceberg-core version cadence | move server 1.5.2→1.10→1.11 in lockstep / lag until rung 3 | Phase 1 vs 3 | server may **lag at 1.10** through rung 2 (v2 tables); must reach 1.11 for rung 3 DV authoring |

---

## C. Fork-patch ledger (draft — finalize in 0.6)

LinkedIn branch `openhouse-1.5.2`. Categorize each: **[KEEP]** LinkedIn-original, must re-port;
**[DROP]** backport already upstream in target; **[BUMP]** dep/CVE the target already carries;
**[?]** verify. Backports are deleted, not re-ported (invariant §5).

| PR | Subject | Category | Notes |
|---|---|---|---|
| #251 | NestedField column-default APIs + `Expressions.lit()` (~upstream #9502) | **[?]** | If #9502 shipped ≤1.10, DROP; else KEEP (Core/API). |
| #249 | Spark 3.5: default partitioned write distribution mode NONE | **[KEEP]** | LinkedIn behavior; Spark-integration → re-home in fork `spark/v3.5`, re-eval for Spark 4. |
| #248 | Bump Avro 1.11.4 (CVE-2024-47561) | **[BUMP]** | 1.10/1.11 carry modern Avro; verify ≥1.11.4. |
| #245 | Backport ParallelIterable memory fixes (#9402/#10787/#10979) | **[DROP]** | Upstream; in 1.10. |
| #246 | Backport apache/iceberg#10680 | **[DROP]** | Upstream; verify in 1.10. |
| #241 | CachingCatalog metadata-table load fix (#11738) | **[DROP]** | Upstream; verify in 1.10. |
| #234 | stream-results for remove_orphan_files | **[?]** | Likely upstream; verify. |
| #236 | app-name in EnvironmentContext (audit metadata) | **[KEEP]** | LinkedIn-original; Core. |
| #233 | bin-pack compaction undersized-output fix | **[?]** | Check if upstreamed. |
| #229 | delete-file replication (table + spark-sql property) | **[KEEP]** | LinkedIn-original; Core + Spark. |
| #228 | SparkSQLProperty for split-size | **[KEEP]** | LinkedIn-original; Spark. |
| #219 | configure replication factor of delete files | **[KEEP]** | LinkedIn-original; Core. |
| #224 | do not rewrite Spark views (LinkedIn Spark 3.5) | **[KEEP→?]** | LinkedIn-Spark-specific; may be **moot** on stock Spark 4 — re-eval at rung 2. |
| #214 | application name in snapshot metadata | **[KEEP]** | LinkedIn-original; Spark write path. |

Re-port shortlist (provisional): **Core/API** — #236, #229, #219 (+ #251 if not upstream);
**Spark-integration** — #249, #228, #214 (+ #224 pending Spark-4 re-eval). Everything else DROP/BUMP
after verification.

### C.1 — Verified scope of the KEEP patches (Phase 0.6, read against the fork)
All six KEEP candidates are **LinkedIn-original** (not backports). Their file scope:

| PR | Core touch | Spark touch | Nature |
|---|---|---|---|
| #249 distribution-mode NONE | — | `SparkWriteConf` | write-planning **default** |
| #229 delete-file replication | `core/TableProperties` | `SparkSQLProperties`, `SparkWriteConf`, `SparkWriteOptions` | table-prop + write knob |
| #228 split-size SQL prop | — | `SparkReadConf`, `SparkSQLProperties` | read-planning knob |
| #219 delete-file replication factor | `io/FileIO`, `hadoop/HadoopOutputFile` | `SparkWriteOptions`, `SparkWrite` | HDFS write-side |
| #214 app-name in snapshot | — | `SparkWrite`, `SparkWriteBuilder`, `SparkTable`, `SparkPositionDeltaWrite` | commit metadata |
| #224 don't-rewrite-views | — | `IcebergSparkSqlExtensionsParser.scala` | LinkedIn-Spark parser |

### C.2 — KEY IMPLICATION for REST-first (refines D3)
The Spark-4 client under REST-first is **stock Iceberg** — so these **engine-write-path** patches do
**not** auto-carry to the Spark-4 lane. Disposition per patch on the new lane:
- **#249 distribution-mode NONE** → set as a **server-side default table property**
  (`write.distribution-mode=none`); the stock client honors it. No fork needed.
- **#219/#229 delete-file replication (+ factor)** → the *replication factor* is an HDFS-write
  concern. Delete/DV files are written by the **engine**, so a stock client won't apply it unless
  it is a table property the client honors; the metadata/`FileIO` half is server-side. **Re-express
  as table property + server FileIO**; treat the engine-side factor as a possibly-lost optimization
  to re-raise (tag if dropped).
- **#228 split-size** → likely covered by stock `read.split.target-size` table property + read
  options; **verify equivalence**, drop the custom SparkSQLProperty if so.
- **#214 app-name in snapshot** → prefer stock `EnvironmentContext`/commit-summary injection (pairs
  with #236); **verify** the stock client can stamp it, else a small server-side summary enrichment.
- **#224 don't-rewrite-views** → **likely moot** on stock Spark 4 + stock Iceberg extensions;
  confirm at rung 2, expect DROP.

Net: on the **rung-1** (fork-on-1.10, still custom Spark 3.5 client) lane these are re-ported as
today; on the **rung-2 stock-client** lane most collapse into **server-set table properties**, not
fork patches. This is the fork **shrinking toward zero on the new lane** — the intended direction.

---

## D. Findings (append as the work surfaces them)

Format: `Fn — one-line symptom → root cause → disposition`. Seed carried from the harness baseline:

- **G13 (carried)** — CDC/changelog unsupported over a MoR table with position-delete files at
  1.5.2. **Re-evaluate under 1.11 DVs at rung 3.3** — DVs may change the outcome; record whether
  fixed, still-unsupported (tag), or newly-shaped.
- **F-REST1 (Phase 0 recon)** — the REST-catalog prior-art (linkedin/openhouse #607, #498–500) is
  **not merged into this fork** (`mkuchenbecker/openhouse`); no `iceberg/v1` controller exists here.
  So rung-2 REST work is a **port/fresh-build**, not already present. Reuse target is confirmed
  in-repo: the native commit path `IcebergSnapshotsService` + `OpenHouseInternalTableOperations`
  (`extends BaseMetastoreTableOperations`, overrides `commit`/`doCommit`, writes via
  `TableMetadataParser.write`). Spike B = a REST protocol adapter over these existing handlers.
- **F-COMMIT1** — `OpenHouseInternalTableOperations.commit(base, metadata)` is **overridden** (not
  the stock `BaseMetastoreTableOperations.commit`) to avoid the forced `doRefresh()` after
  `doCommit()`. Note for rung-3 DV work: the v3-metadata authoring change lands in this same
  override + `writeMetadata`/`doCommit` — a concentrated, well-scoped touch point, not scattered.
- *(rung-1…3 findings appended here during execution.)*

---

## E. Explicitly out of scope

- Retirement/deprecation of the Java-8 / Spark-3.x / Iceberg-1.5.2 lane (separate, later decision).
- Flink / Trino / PyIceberg REST clients (the REST endpoint enables them, but validating them is not
  this plan's gate — the delta-harness is Spark).
- v3 features beyond deletion vectors (variant type, row lineage) — note them, do not chase them.
- Encryption/KMS (absent in OSS; inherited not-a-test principle).
