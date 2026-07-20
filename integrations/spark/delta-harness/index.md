# index.md — delta-harness MASTER MAP

Read this FIRST. This is the single map of the whole effort. It says what each doc/checklist is, its
STATUS, and where CURRENT truth lives. Follow only the entries relevant to your task; skip the rest.

Protocol (see CLAUDE.md → "Documentation protocol"): checklists are point-in-time. On completion they are
marked COMPLETED here + in their own header, their durable lessons are denormalized into the LIVING docs,
and then frozen. Current truth is the LIVING docs, never a checklist.

---

## CURRENT STATE (pointer)
- **Verified run (latest):** STUB **2571** (2560/11/0), REAL-HTS **2789** (2778/11/0), 0 failed — see
  `VERIFIED-RUN-openhouse.txt` for authoritative dated counts. On PR #9 branch (`claude/spark-scala-test-
  env-k7drzg`); includes fork tests 2b-2f + RTAS full-cross (Phase 28) + D6 blanket-double + WAP mega-axis
  (Phase 29, stages A/B/C). New finding WAP1 (staged DELETE bypasses WAP). All major planned blocks built.
- **Active checklist:** `CHECKLIST-2026-07-20-completeness-pass.md` (fork-commit tests, D6 blanket-format
  refactor, mega phases, G2–G14 docs, doc consolidation).
- **Role:** testing + understanding silo for OpenHouse + the `com.linkedin.iceberg` 1.5.2 fork. Not the
  master agent. Sequence: bootstrap tests → fix → modify. Document findings; don't fix production code yet.
- **PR:** work only on the current stacked PR (branch `claude/hts-embed-plan-k7drzg`); never touch the
  parent PR #9.

## OVERARCHING GOAL
Comprehensively TEST + UNDERSTAND OpenHouse's Iceberg surface AND the `com.linkedin.iceberg` 1.5.2 fork,
persisting all working knowledge in the PR so any agent can bootstrap. Tests must not bake in a single
file format (or other axis) — they multiplex/compose across formats, layouts, and modalities.

---

## LIVING DOCS — current truth (read these for the real state)
| Doc | Purpose |
|---|---|
| `README.md` | How the harness works + how to run it (incl. branch mode via `ICEBERG_RUNTIME_JAR`). |
| `VERIFIED-RUN-openhouse.txt` | **Source of truth for run counts** (dated, both modes, release + branch). |
| `ICEBERG-FORK-AUDIT.md` | The fork's custom commits vs Apache 1.5.2 + which are tested; fork behaviors. |
| `AUDIT-FINDINGS.md` | Findings ledger G2–G14 (+ H1–H8 hazards); evidence, status, attribution. |
| `BUGS.md` | Bug ledger (insert.explicitColumns pin, nested-DELETE NPE, renameColumn regression, encryption). |
| `BUILD-STATUS.md` | Block-by-block built ledger. |
| `CLAUDE.md` | Standing rules (autonomy, checklist + documentation protocol, test quality, PR, mechanics). |
| `index.md` | This map. |

## CHECKLISTS — point-in-time (status authoritative here)
| Checklist | Status | One line |
|---|---|---|
| `CHECKLIST-2026-07-20-wap-mega-axis.md` | **COMPLETED (2026-07-20)** | Phase 29: stages A/B/C built + green. branchWap 456, branchDdl (G8 leak), wapStaged. Findings WAP1 + G11(d). |
| `CHECKLIST-2026-07-20-rtas-full-cross.md` | **COMPLETED (2026-07-20)** | Phase 28: `prep.rtas:*` extended to full 6-layout + partitionedOperations + 3-format RTAS×MoR over replace-lineage. Green. |
| `CHECKLIST-2026-07-20-completeness-pass.md` | **ACTIVE** | Post-self-review: fork tests (sub-agent), D6 format refactor (done), G-docs (done), doc consolidation. |
| `CHECKLIST-2026-07-20-test-the-branch.md` | COMPLETED (2026-07-20) | Built branch-HEAD iceberg runtime, swapped it in, re-ran; branch-vs-release = no correctness delta. Lessons → ICEBERG-FORK-AUDIT + VERIFIED-RUN. |
| `CHECKLIST-2026-07-16-column-default-tests.md` | COMPLETED — TABLED (2026-07-20) | #251 column defaults characterized (api/core only). Lessons → ICEBERG-FORK-AUDIT #251 section. |
| `CHECKLIST-2026-07-16-orc-coverage.md` | SUPERSEDED (2026-07-20) | D6 resolved = blanket-double; the refactor is executed under the completeness-pass checklist. |
| `CHECKLIST-2026-07-16-audit-and-restore.md` | COMPLETED (commit 2436fc5) | Avro regression repaired + broad ORC added. Lessons → CLAUDE.md format policy + VERIFIED-RUN. |

## HISTORICAL — frozen planning / analysis (accurate WHEN WRITTEN; do not chase stale counts)
| Doc | What it was |
|---|---|
| `PROJECT-PLAN.md` | Prior fractal root/index — **superseded by this index.md**. Kept for history. |
| `SESSION-CHECKLIST.md` | Superseded stray session checklist (see audit-and-restore). |
| `SESSION-CHECKPOINT.md` | Old session snapshot (early counts, old branch name). Historical. |
| `TEST-PLAN.md` / `DDL-TEST-PLAN.md` / `FEATURE-ANALYSIS-PLAN.md` | Pre-implementation test plans + phase ledgers. |
| `BRANCHING-PLAN.md` / `MODALITY-RECON.md` / `SURFACE-APPRAISAL.md` | Branch/WAP + modality + surface analyses. |
| `REST-FIDELITY-EVAL.md` / `INTERACTION-AUDIT.md` / `INDUSTRY-RETENTION-SURVEY.md` | Transport-fidelity, interaction, retention research. |
| `HTS-EMBED-PLAN.md` / `HTS-EMBED-IMPL.md` | Embedded-HTS plan + execution (Option A). Now shipped; see README/BUILD-STATUS. |
| `FINDINGS.md` | Early findings (F1 classpath, etc.). |
| `docs/spark-delta-test-harness/*.md` | The original doc-site plan (pre-implementation). |

---

## DECISION LEDGER (D1–D8 + follow-ups) — OPEN items only need action
- CLOSED: D1 HTS embed, D2 fixtures @ConditionalOnProperty, D3 insert.explicitColumns pin, D4 renameColumn
  document-only, D6 ORC = **blanket-double** (un-bake format so tests multiplex), D8 PR structure.
- **OPEN — awaiting user context:** D5 (G14 dangling-delete: pin vs bug classification), D7 (encryption/KMS:
  go further vs plaintext pin). Pins kept in place meanwhile.
