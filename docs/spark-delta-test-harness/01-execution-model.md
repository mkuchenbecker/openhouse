# 01 — Execution Model

*Prerequisite: `index.md`.* This document defines how a test run is structured.

## Spark is the driver; the tests are the data

There is no test *framework* driving this run. The suite of ~20k cases is a **dataset**, and
Spark distributes it across executors. If a test framework like ScalaTest appears at all, it
survives only as *assertion logic inside a case* — never as the thing that orchestrates the
run.

The entire run is **one pipeline of four verbs**:

```
generate  →  filter  →  mapPartitions(execute)  →  collect
```

| Verb | Runs on | Responsibility |
|---|---|---|
| `generate` | driver | Expand base tests into the full `Dataset[TestCase]` (see `07`). |
| `filter` | driver/exec | **Selection**: keep only the cases in scope for *this run* (see below). |
| `mapPartitions(execute)` | executors | Run each case, produce a `TestResult`. The only Spark-aware, resource-holding stage. |
| `collect` | driver | Gather results (one small row per case) and hand to the sink. |

`collect` is safe at 20k because a `TestResult` is a small row (id, axis, outcome, timing),
not table data.

### Skew is a source property, not a fifth verb

Cases are not uniform cost (a `groupBy` on 10 rows vs a `MERGE` on a partitioned table). The
straggler partition sets wall-clock. Balance it by choosing the partition count / cost-aware
partitioning **where the matrix is created**, not by adding a stage to the pipeline.

## Build locally, run anywhere

One self-contained artifact. It runs identically on a laptop (`local[*]`), in CI, and as a
production smoke test. **The three contexts differ only in configuration values — never in
code paths.** The moment "smoke" becomes its own source file, this property is lost.

The four knobs that vary:

| Knob | Local | CI | Smoke |
|---|---|---|---|
| **master** | `local[*]` | `local[*]` or cluster | cluster or `local[*]` |
| **selector** (the `filter` predicate) | a slice you're iterating on | the full matrix | `tag == smoke` (a curated handful) |
| **target** (what the black box points at) | ephemeral fixture | ephemeral fixture | a real deployed endpoint |
| **sink** (what happens to results) | pretty-print | JUnit-XML + non-zero exit on any `Failed` | aggregate to a pass-rate, gate on threshold |

The reason the matrix is *data* is precisely so these become `filter` predicates and sink
choices instead of forked programs.

## Three separated stages inside the code

Keep these independent so contexts can swap parts without touching the rest:

1. **Generate** — pure, deterministic, driver-side. Produces the case dataset. Needs no
   Spark to *define* a case.
2. **Execute** — the only Spark-aware stage (`mapPartitions`). Identical in all contexts.
3. **Report** — a pluggable sink, swapped per context.

Local/CI/smoke change stage 1's predicate and stage 3's sink. **Stage 2 is written once and
never touched.** That is the reuse.

## What "in scope" means (forward reference)

The `filter` verb does **selection** — "is this case meant to run in this context?" A
selected-out case is genuinely removed and never appears in the report. This is distinct
from *applicability* (an invalid combination, pruned at generation) and *disable* (a
known-broken case, kept visible as `Skipped`). See `07` and `08`. Do not merge them.
