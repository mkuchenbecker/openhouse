# Plan: Optimizer Design Document

## Goal
Produce a coherent design document for the OpenHouse Optimizer — a system that
replaces fixed-cron table maintenance with adaptive, signal-driven scheduling.
Built iteratively; each step is its own session to avoid context blowout.

## Resume Instructions
1. Read this file.
2. Find the first unchecked `[ ]` item in the checklist.
3. Check its **Artifact** and **Verification** — if the artifact file exists and
   passes verification, mark it done and move to the next step.
4. Otherwise, execute that step.

---

## Checklist

### Step 1 — Reference map
- [x] **Done**
- **Artifact**: `memory/optimizer-design/reference-map.md` exists and has sections:
  DB Schemas, Optimizer Service, HouseTables, Tables Service, Shared JPA Library,
  Analyzer App, Scheduler App, Spark App, Infra.
- **How to produce**: Use `git diff main..HEAD --stat` for the file list (no extra
  code search needed — key files already read in current context).
- **Verification**: File exists; wc -l > 50; each listed file has a one-line description.

---

### Step 2 — PR decisions
- [x] **Done**
- **Artifact**: `memory/optimizer-design/pr-decisions.md` exists with sections:
  entity-model, stats-ingestion, analyzer-design, scheduler-design, concurrency,
  ops-infra. Each entry: topic tag, decision made, alternative rejected.
- **How to produce**:
  1. `gh api graphql` — fetch all PR #4 review threads (one call, save to temp file).
  2. `~/code/docs/optimizer-current/` — skim for background context.
  3. Parse threads; one bullet per resolved thread.
- **Verification**: File exists; grep for at least 10 decision entries; sections present.

---

### Step 3a — Commit → stats sequence diagram
- [x] **Done**
- **Artifact**: `memory/optimizer-design/architecture.md` exists; contains section
  `## 3a. Commit → Stats Ingestion` with a Mermaid `sequenceDiagram` block and a
  Failure Modes table.
- **How to produce**: From code already read:
  `IcebergSnapshotsServiceImpl` → `OptimizerTableStatsClient.reportCommitStats`
  → `PUT /v1/table-stats/{tableUuid}` → optimizer DB.
- **Verification**: File exists; contains `sequenceDiagram`; contains `Failure Modes`.

---

### Step 3b — Analyzer loop sequence diagram
- [x] **Done**
- **Artifact**: `memory/optimizer-design/architecture.md` has section
  `## 3b. Analyzer Loop` with Mermaid diagram + Failure Modes table.
- **How to produce**: From `AnalyzerRunner.java`, `OrphanFilesDeletionAnalyzer.java`,
  `CadencePolicy.java` already read in context.
- **Verification**: Section exists in file; CadencePolicy states (PENDING/SCHEDULED/
  SUCCESS/FAILED) shown; failure cases documented.

---

### Step 3c — Scheduler loop sequence diagram
- [x] **Done**
- **Artifact**: `memory/optimizer-design/architecture.md` has section
  `## 3c. Scheduler Loop` with Mermaid diagram + Failure Modes table.
- **How to produce**: From `SchedulerRunner.java`, `BinPacker.java`,
  `JobsServiceClient.java`, `BatchedOrphanFilesDeletionSparkApp.java` already read.
- **Verification**: Section exists; claim-before-submit, PATCH callback, and
  concurrent-scheduler guard all shown.

---

### Step 3.5 — RFC templates
- [x] **Done**
- **Artifact**: `memory/optimizer-design/rfc-templates.md` — headings, conventions, boilerplate
  extracted from 3 internal RFC Google Docs.
- **How to produce**: Fetch each via `https://docs.google.com/document/d/{ID}/export?format=txt`.
  If auth-blocked, note it and proceed with the others.
- **Verification**: File exists; has sections for each doc (or auth-failure note).

---

### Step 3.6 — Q&A with user
- [x] **Done**
- **Artifact**: Answers captured in `memory/optimizer-design/rfc-templates.md` or inline notes.
- **How to produce**: AskUserQuestion — audience, scope, gaps, tone, diagrams placement.
- **Verification**: All 5 questions answered; answers recorded before writing design.md.

---

### Step 4 — Components narrative
- [x] **Done**
- **Artifact**: `memory/optimizer-design/components.md` exists with one section per
  component: OptimizerService, OptimizerTableStatsClient, apps/optimizer (shared JPA),
  AnalyzerApp, SchedulerApp, BatchedOrphanFilesDeletionSparkApp.
- **How to produce**: Synthesize from reference-map.md + code already read. No new
  file reads needed beyond what Step 1 references.
- **Verification**: File exists; 6 component sections present; each has
  Responsibility, Interface, Config, Known Gaps subsections.

---

### Step 5 — Assemble design.md
- [x] **Done**
- **Artifact**: `memory/optimizer-design/design.md` exists with sections:
  Motivation, Non-Goals, System Overview (embed diagrams from architecture.md),
  Component Details (draw from components.md), Alternatives Considered
  (draw from pr-decisions.md), Open Questions.
- **How to produce**: Assemble from Steps 1–4 artifacts. Add motivation from
  `~/code/docs/table-optimizer/` + `~/code/docs/optimizer-current/` background.
- **Verification**: File exists; all 6 sections present; Mermaid diagrams render
  (no syntax errors); wc -l > 200.

---

## Sources

| Source | What it provides |
|---|---|
| `git diff main..HEAD --stat` | Complete list of files changed on this branch |
| `services/optimizer/src/main/resources/db/optimizer-schema.sql` | DB schema: table_operations, table_stats, table_operations_history |
| `apps/optimizer-analyzer/src/main/java/.../AnalyzerRunner.java` | Analyzer loop logic |
| `apps/optimizer-analyzer/src/main/java/.../CadencePolicy.java` | Scheduling cadence state machine |
| `apps/optimizer-scheduler/src/main/java/.../SchedulerRunner.java` | Scheduler loop + claim-before-submit |
| `apps/optimizer-scheduler/src/main/java/.../BinPacker.java` | Greedy FFD bin packing |
| `apps/optimizer-scheduler/src/main/java/.../client/JobsServiceClient.java` | POST /jobs call shape |
| `apps/spark/.../BatchedOrphanFilesDeletionSparkApp.java` | Per-table parallel OFD + PATCH callback |
| `services/tables/.../IcebergSnapshotsServiceImpl.java` | Where stats are extracted from snapshots |
| `services/tables/.../OptimizerTableStatsClient.java` | Fire-and-forget stats push |
| `services/optimizer/.../OptimizerDataServiceImpl.java` | All service-layer logic |
| `~/code/docs/table-optimizer/` | Original design docs (may be outdated) |
| `~/code/docs/optimizer-current/` | Current-state reference: INDEX, CURRENT-STATE, GAP-ANALYSIS |
| PR #4 review threads | All architectural decisions and trade-offs |

---

## Sub-PR Breakdown (future, not now)
1. `services/optimizer` — REST service
2. `services/tables` stats wiring
3. `apps/optimizer` shared JPA library
4. `apps/optimizer-analyzer`
5. `apps/optimizer-scheduler` + BinPacker
6. `apps/spark` BatchedOrphanFilesDeletionSparkApp
7. Docker + smoke test
