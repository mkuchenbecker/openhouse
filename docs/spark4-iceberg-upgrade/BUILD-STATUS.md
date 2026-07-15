# BUILD STATUS — Spark 4.0 / Iceberg 1.11 / DV Upgrade (built vs. plan)

**Lead every status reply with this ledger, per rung — not the latest green slice count.** One row
per phase/rung; a rung is ✅ only when its harness gate is green (or every non-green case is a
tagged, reasoned known-bug). The plan is `10-phase-plan.md`; risks/decisions `20-risks-...md`.

## Rung ledger

| Rung | Stack | Harness gate | Status |
|---|---|---|---|
| **0** | Spark 3.5 / Iceberg 1.5.2 fork / Scala 2.12 / JDK-17 build | ~1,697 green baseline (frozen) | ⬜ not yet re-frozen (0.1) |
| **1** | Spark 3.5 / Iceberg **1.10** / Java **11** | full matrix ≡ rung-0 | ⬜ not started |
| **2** | **Spark 4.0** / Iceberg 1.10 / **Scala 2.13** / Java **17** (REST) | full matrix ≡ rung-1 | ⬜ not started |
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
