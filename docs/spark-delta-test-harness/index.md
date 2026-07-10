# OpenHouse Spark Delta-Test Harness — Design Index

**Read this file first.** It assumes you know nothing about this project. It defines the
problem, the vocabulary, the non-negotiable rules, and points to one detailed document per
part of the design. Everything here is *design of record* — the harness is not built yet;
these documents specify the form an implementer must build to.

---

## 1. The problem

OpenHouse manages [Apache Iceberg](https://iceberg.apache.org/) tables and exposes them
through Apache Spark (SQL + DataFrame APIs). Iceberg's *observable behavior* — how an
`INSERT`, `MERGE`, `DELETE`, schema change, or partition change affects a table — must be
correct across a large cross-product of conditions:

- **format version** (v1 / v2 / v3),
- **file format** (Parquet / ORC / Avro),
- **read path** (vectorized / non-vectorized),
- **partitioning** (partitioned / unpartitioned, various specs),
- **distribution mode**, and more.

Correctness must hold for *every valid combination*. That cross-product is on the order of
**20,000 cases**. Writing and maintaining 20,000 hand-authored tests is untenable.

## 2. The goal

> A human authors **O(100–1000)** parameterized *base tests*. The harness **generates
> O(20k)** concrete *cases* from them. The 10–100× multiplication is a **generator, never a
> keyboard.**

**Golden rule:** if you ever find yourself hand-writing a per-combination expected value,
the form is wrong. Stop and fix the form. Good form is the entire point — it is what makes
the function (the 20k cases) cheap.

## 3. Core mental model (four ideas)

1. **Spark is the driver; the tests are the data.** The whole run is one data pipeline:
   `generate → filter → mapPartitions(execute) → collect`. No test framework "drives" it.
   → `01-execution-model.md`
2. **Errors are typed values, and an infrastructure failure is not a test failure.** A wrong
   answer (bug) and a flaky storage call (infra) are different outcomes that must never be
   confused. → `02-outcome-model.md`, `03-error-handling.md`
3. **Tests measure deltas, not absolute state.** A test asserts *how an operation changed a
   table*, relative to the state it observed this run — so it is invariant to commit history
   and cheap to combine. This is what makes the matrix generatable. → `06-delta-model.md`
4. **Build locally, run anywhere.** One artifact runs on your laptop, in CI, and as a smoke
   test. The three contexts differ only by *configuration values*, never by code path.
   → `01-execution-model.md`

## 4. Vocabulary

| Term | Meaning |
|---|---|
| **Base test** | The unit a human authors: a parameterized description of one behavior. ~100–1000 of these. |
| **Axis** | One dimension of variation (e.g. file format). A fixed, typed set of dimensions. |
| **Concrete case** | A base test bound to one specific point in the axis space. ~20k of these, all generated. |
| **Matrix** | The full set of generated concrete cases. |
| **Outcome** | The typed result of running a case: `Passed` / `Failed` / `Errored` / `Skipped`. |
| **Delta test** | A test modeled as `{precondition} operation {postcondition}` where the postcondition is a *change* relative to the precondition. |
| **Disable** | Marking a known-broken case so it is reported as `Skipped` (not silently dropped) while a bug is outstanding. |

## 5. Non-negotiable invariants (anti-goals — never do these)

- Hand-writing per-combination expected state.
- Confusing an infrastructure error with a test failure (or vice-versa).
- `try/catch` anywhere except the single executor edge.
- Capturing a live `SparkSession`/client in a Spark closure (it isn't serializable — pass a *thunk*).
- Nesting a `SparkContext` inside an executor task.
- Absolute-state assertions on Iceberg metadata (snapshot ids, sequence numbers, commit counts).
- Silently dropping disabled or invalid cases so they vanish from the report.
- Collapsing *applicability*, *selection*, and *disable* into one filter (they are three distinct things).
- Thresholding `Failed` ("we tolerate 1% failures") or retrying an unrecognized exception.

## 6. Document map

Read in order for a full picture; each file also stands alone.

| File | Covers | Read when |
|---|---|---|
| `01-execution-model.md` | Spark-as-driver, the four-verb pipeline, local/CI/smoke config knobs | Understanding how a run is structured |
| `02-outcome-model.md` | The four result lanes, the infra-vs-failure firewall, the 100%-green contract, throwable classification | Understanding what a result *is* |
| `03-error-handling.md` | Errors as values, the two-layer `Either`, the single edge, retry policy | Implementing case execution |
| `04-resource-management.md` | Thunk-based resources, `Managed`/`bracket`, per-partition lifecycle | Wiring Spark sessions / storage clients |
| `05-test-case-model.md` | The authored unit: curried axis-shaped fixture + axis-blind assertion; cribbing Iceberg tests | Authoring or generating cases |
| `06-delta-model.md` | History-agnostic delta assertions and why they make the matrix cheap | Understanding the multiplier |
| `07-generation-and-identity.md` | The `Axis` type, per-test cross-products, applicability/selection/disable, case identity | Building the generator |
| `08-disable-subsystem.md` | Layered code/config/env overrides, owner/ticket/expiry, visibility | Managing known bugs |
| `09-verification.md` | Proving the harness itself is trustworthy (test-the-tester) | Before trusting any output |

## 7. Suggested build & reading order for an implementer

Build **bottom-up**, verifying each layer before the next — everything above an unverified
classifier inherits its mistakes:

1. Outcome model + classifier (`02`) → verify in pure JVM, no Spark.
2. Error handling + resources (`03`, `04`).
3. Test-case model + delta model (`05`, `06`).
4. Generation + identity (`07`).
5. Execution pipeline on `local[*]` (`01`).
6. Disable subsystem (`08`).
7. Full verification pass (`09`).
