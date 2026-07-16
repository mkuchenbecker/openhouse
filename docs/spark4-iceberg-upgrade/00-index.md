# OpenHouse → Spark 4.0 / Iceberg 1.11 Upgrade — Design of Record

**Read this file first.** It defines the goal, the non-negotiables, the strategy, and points to
one document per part of the plan. Everything here is *design of record*. The work is not built
yet; these documents specify the form an implementer must build to. The model mirrors the
`docs/spark-delta-test-harness/` system that this repo already trusts: **intent** (this index +
the phase plan), **progress** (`BUILD-STATUS.md`, a built-vs-plan ledger), and **pitfalls**
(`20-risks-decisions-findings.md`).

---

## 1. The goal (and why)

Upgrade OpenHouse's engine integration to **Apache Spark 4.0 + Apache Iceberg 1.11**, on **Scala
2.13 / Java 17 runtime**, and land **v3 DataSource-V2 deletion vectors (DVs)** as a first-class,
validated capability. DVs are the *explicit* objective; Spark 4.0 is the vehicle. Everything else
(Java, Hadoop, Scala, the fork) is a dependency of that objective, not an end in itself.

The acceptance bar is behavioral: the **delta-harness** (`integrations/spark/delta-harness`,
~1,697 verified cases against the embedded OpenHouse catalog) must run **green on the new stack**,
with any delta from the rung-0 baseline either explained or tagged. The harness is the final
validation vehicle — the same standard used for the Spark/Scala testing work this plan inherits.

## 2. The hard constraints (established, load-bearing)

| # | Constraint | Consequence |
|---|---|---|
| C1 | **Spark 4.0 requires Java 17** (17/21) and **Scala 2.13 only** (2.12 dropped). | The Spark-4 lane runs on a Java 17 JVM; any shared Scala cross-compiles to 2.13. |
| C2 | **Iceberg 1.11 requires Java 17** (dropped Java 8 at 1.7, Java 11 at 1.11). **1.10 still runs on Java 11 or 17.** | 1.10 is a real intermediate rung; 1.11 forces Java 17 everywhere it is linked, **including the server**. |
| C3 | **OpenHouse writes the Iceberg metadata file directly** (server-side `OpenHouseInternalTableOperations`). | To author **v3/DV** metadata the *server's* `iceberg-core` must be 1.11 → server **runtime** = Java 17 by rung 3. Not optional. |
| C4 | **Bytecode target is a JVM floor, not an inter-jar contract.** A Java-8-bytecode jar runs fine on a Java 17 JVM beside Java-17 Iceberg. | Legacy + HDFS-facing + client-consumed modules **keep `targetCompatibility 1.8`**. Only the new Spark-4/1.11 lane emits Java-17 bytecode. We move *runtimes*, not the legacy bytecode target. |
| C5 | **HDFS is 3.1/3.2 fronted by RBF; RPC wire protocol is stable across 3.x.** | The `hadoop-client 2.10` pin is compile-time only. Running the client on Java 17 is a **bounded runtime spike** (`--add-opens` + jaxb), validated by wire-compat — not a Hadoop rewrite. |
| C6 | **v3/DV tables are unreadable by pre-1.9 Iceberg.** | The retained Java-8 / Iceberg-1.5.2 consumers are **locked out of DV tables**. `format-version=3` is **opt-in per table**; enabling it is governed, never global. |

## 3. The strategy (COMMITTED)

**REST-first, sequenced along a three-rung validation ladder.**

- **REST-first (the *what*).** The Spark 4.0 client is the **stock** `iceberg-spark-runtime-4.0`
  pointed at an OpenHouse **Iceberg REST Catalog** endpoint. OpenHouse ships **no custom Java-17
  Spark catalog jar** and does **not** re-port the fork onto the client. The only new engine-side
  code is server-side REST controllers (Java-8 bytecode, inside the existing Spring services).
  Prior-art exists in `linkedin/openhouse` (PR #607 in-process adapter; #498–500 REST Phase 1) —
  but it is **read-only**; the **write/commit path over REST is the real deliverable** (rung 2).
  - *Asterisk:* the 8 OpenHouse **policy-DDL SQL extensions** (retention/sharing/replication/
    history/column-tag/grants) are **not** stock Iceberg. REST-first is therefore *not* "delete all
    custom Spark code" — either a **thin Spark-4 extension** keeps that SQL sugar, or those
    operations move to `SET TBLPROPERTIES`/REST. Decided by a rung-2 spike.
  - *Fallback:* **Path B** (port the custom `OpenHouseCatalog` + parser + 8 logical plans + 8 exec
    nodes to Spark 4 Catalyst/DSv2) is engaged **only if** the rung-2 spike shows the REST write
    path cannot meet fidelity. It is a fallback, not a coin-flip.

- **The ladder (the *order*).** Each rung moves **exactly one axis** so the harness can attribute
  any regression to a single cause. The ladder is orthogonal to REST-first; the REST cutover *is*
  rung 2.

| Rung | Iceberg | Spark | Scala | Java (runtime) | Hadoop | Harness gate |
|---|---|---|---|---|---|---|
| **0 (today)** | 1.5.2 fork | 3.1 + 3.5 | 2.12 | 8 | 2.10 pin / 3.1–3.2 real | 1,697 green **baseline** |
| **1** | **1.10** | 3.5 | 2.12 | **11** | client-on-J11 (RBF) | green on 3.5/1.10/J11 |
| **2** | 1.10 | **4.0** | **2.13** | **17** | client-on-J17 | green on 4.0/1.10/J17 (**REST cutover**) |
| **3 (goal)** | **1.11** | 4.0 | 2.13 | 17 | client-on-J17 | green + **DV battery** |

Verified enabling fact: `iceberg-spark-runtime-4.0_2.13:1.10.0` is published on Maven Central, so
rung 2 (Spark 4 on Iceberg 1.10) is a genuine intermediate, not a fiction.

## 4. Coexistence model (not a big-bang cutover)

The new Java-17 / Spark-4 / Iceberg-1.11 lane is **additive**, exactly as this repo already ships
`spark-3.1` + `spark-3.5` and `iceberg-1.2` + `iceberg-1.5` side by side. The existing Java-8 /
Spark-3.x / Iceberg-1.5.2 modules **remain, unchanged**, serving HDFS/legacy consumers. Nothing in
this plan downgrades their compatibility or removes them. Retirement of the old lane is a separate,
later decision gated on consumer migration — out of scope here.

## 5. Non-negotiable invariants (anti-goals — never do these)

- **No rung advances until its harness gate is green** (or every non-green case is a tagged,
  reasoned known-bug). One axis per rung; never move two at once "to save time."
- **Never move a legacy module's bytecode target off Java 8** to chase the upgrade (C4). Move the
  *runtime*, not the target.
- **Never enable `format-version=3` globally** or by default. It is opt-in per table, gated on
  reader migration (C6).
- **Never confuse an infra/runtime failure (add-opens, jaxb, classpath) with a behavior failure.**
  The harness's infra-vs-failure firewall is inherited; honor it.
- **No "not-a-test."** Do not assert behavior that has no in-repo implementation to exercise; such
  cases are tagged-SKIP with a recorded reason (inherited principle).
- **Lead every status with built-vs-plan**, per rung, not the latest green slice count.
- **Drop pure backports.** A fork patch that is a backport of an upstream fix already in the target
  Iceberg is *deleted*, not re-ported. Only LinkedIn-original behavior carries forward.

## 6. Document map

| File | Covers |
|---|---|
| `00-index.md` (this) | Goal, constraints, committed strategy, coexistence, invariants |
| `10-phase-plan.md` | The five phases, each with Steps × {Solution, Verification, Enumeration} |
| `30-rung2-detailed-plan.md` | **Deep** rung-2 plan: Spike-B REST write path, spark-4.0 module set, Scala 2.13, Path A/B, harness port, Spark-4 behavior diff surface |
| `20-risks-decisions-findings.md` | Risks, open decisions (incl. the rung-2 REST-write spike), fork-patch ledger, running findings |
| `BUILD-STATUS.md` | Built-vs-plan ledger; per-rung gate records |

## 7. Tracks that span all phases

Three concerns thread through every rung and are called out where they land:
- **Fork track** — re-port LinkedIn-original Iceberg patches 1.5.2 → 1.10 → 1.11; drop backports.
- **Server/metadata-writer track** — `OpenHouseInternalTableOperations` climbs iceberg-core
  versions and (rung 3) becomes v3/DV-aware; its runtime reaches Java 17.
- **Harness track** — the delta-harness itself is ported to each rung's stack (Scala 2.13 + Spark 4
  at rung 2) and is the gate at every rung.
