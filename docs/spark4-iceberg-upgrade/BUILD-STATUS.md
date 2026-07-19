# BUILD STATUS — Spark 4.0 / Iceberg 1.11 / DV Upgrade (built vs. plan)

**Lead every status reply with this ledger, per rung — not the latest green slice count.** One row
per phase/rung; a rung is ✅ only when its harness gate is green (or every non-green case is a
tagged, reasoned known-bug). The plan is `10-phase-plan.md`; risks/decisions `20-risks-...md`.

## Rung ledger

| Rung | Stack | Harness gate | Status |
|---|---|---|---|
| **0** | Spark 3.5 / Iceberg 1.5.2 fork / Scala 2.12 / JDK-17 build | **1670 passed, 27 skipped, 0 failed (1697)** — FROZEN | ✅ frozen (0.1) |
| **1 (stock)** | Spark 3.5 / stock Iceberg **1.10** / Hadoop **3.3.4** / Java 17 rt | full matrix ≡ rung-0 | ✅ 1669/28/0 (1 WAP tagged). Compat test — NOT the real upgrade. |
| **F (fork)** | Spark 3.5 / **LinkedIn fork ported onto Apache 1.10** (`1.10.0-openhouse`) / Hadoop 3.3.4 / Java 17 rt | full matrix ≡ stock-1.10 | ✅ **1669 passed / 28 skipped / 0 failed — IDENTICAL to stock-1.10.** All 10 custom patches ported (half were already upstream); fork compiles (core+spark-3.5), published to mavenLocal, **OpenHouse recompiled against it, harness green.** F6 push pending (destructive fork-branch overwrite + publish target = user decisions). Partial: #234 stream-results optimization (correctness wired), #219/#214 end-to-end HDFS behavior (non-harness-tested). |
| **2** | **Spark 4.0** / Iceberg 1.10 / **Scala 2.13** / Java **17** (REST) | full matrix ≡ rung-1 | 🟡 **1637 passed / 28 skipped / 32 failed** (vs 1669/28/0). Stack GREEN end-to-end (stock RESTCatalog → `/iceberg` controller; all core DML happy paths pass). 32 fails: 12 custom policy/ACL/colTag SQL (no parser in REST-first), 2 CTAS/RTAS→501, 18 REST-vs-native validation-path divergences. See `harness-spark4/`. |
| **3** | Spark 4.0 / **Iceberg 1.11** / Java 17 | matrix ≡ rung-2 + **DV battery** | ⬜ not started |
| **4** | both lanes, coexistence | legacy itests + rung-3 in one CI pass | ⬜ not started |

## Phase-0 gate ledger (must clear before rung 1)

| Item | What | Status |
|---|---|---|
| 0.1 | Rung-0 baseline re-frozen | ⬜ |
| 0.2 | JDK-17 toolchain, legacy still target-8 | ⬜ |
| 0.3 | 🔬 Spike A — HDFS client on Java 17 vs RBF | ⬜ |
| 0.4 | 🔬 Spike B — REST write/commit fidelity | ⬜ |
| 0.5 | 🎯 Policy-DDL disposition (D2) | ⬜ |
| 0.6 | Fork-patch ledger finalized (D3) | ⬜ (draft in 20-...§C) |

## Current state (planning)

- **Docs written; no code changed.** This is design-of-record. Branch:
  `claude/iceberg-spark-upgrade-4h7pwb` (openhouse + iceberg).
- **Strategy committed:** REST-first, sequenced along the 1.10→spark4→1.11 ladder (see `00-index.md`
  §3). Path B is fallback, gated on Spike B.
- **Live task list** mirrors this ledger (tasks #1–#9).

## Discipline note

**No build work without a live checklist.** Every rung diffs against the previous rung's frozen
green set; a *net loss of green* is a blocker, a *newly-passing* case is a recorded finding, a *new
failure* becomes a tagged bug only with a root cause. Drop fork backports rather than re-port them.
Never advance two axes in one rung.
