# 07 — Generation and Identity

*Prerequisites: `index.md`, `05-test-case-model.md`, `06-delta-model.md`.* This document
defines how base tests become the ~20k concrete cases, and how each case is named.

## The `Axis` is a fixed, typed set of dimensions

```scala
final case class Axis(
  formatVersion: Int,               // 1 | 2 | 3
  fileFormat:    FileFormat,        // Parquet | ORC | Avro
  vectorized:    Boolean,
  partitioned:   Boolean,
  distribution:  DistributionMode,
  executionMode: ExecutionMode      // Once | RepeatAfterRtasReset | ...  (see 06)
)
```

Use a **fixed, typed `Axis`**, not an open `Map[String, String]`. Every base test picks a
*sub-cube* of the same dimensions. Adding a dimension is a deliberate library change (it
touches the type), which is correct: dimensions are a small, stable, shared vocabulary
cribbed from Iceberg's own parameters. Freeform per-test dimensions would lose the typing and
make identity and reporting inconsistent.

## The cross-product is per-test-constrained

Each `BaseTest` declares, via its `AxisSpec`, the **valid sub-cube it varies over** — not a
uniform global multiply.

```scala
def generate(bases: Seq[BaseTest]): Seq[TestCase] =
  bases.flatMap { b =>
    b.axes.enumerate                                     // ONLY this test's valid combinations
     .map(a => TestCase(id = s"${b.id}[$a]", axis = a, run = () => runCase(b, a)))
  }
```

- A merge-on-read test declares `formatVersion ∈ {2,3}` — row-level deletes don't exist in v1
  — so those combinations are **never generated**.
- A simple projection test might vary only `fileFormat × vectorized`.
- One base expands to 12, another to 6; the **sum of local products** across ~1000 bases
  lands at ~20k. This is exactly Iceberg's own `@Parameters` behaviour, distributed as a job.

## Three distinct notions — never collapse them

A case can be excluded for three completely different reasons. They differ in *when* and in
*whether the case appears in the report*. Collapsing them (e.g. treating an invalid
combination as "disabled") would flood the disabled report with thousands of nonsensical rows
like "v1 merge-on-read."

| Concept | When | In report? | Mechanism |
|---|---|---|---|
| **Applicability** | generation | **no** — it never existed | `axes.enumerate` prunes invalid combos |
| **Selection** | `filter` verb (context scope, see `01`) | **no** — out of scope this run | context predicate |
| **Disable** | pre-execute (known bug, see `08`) | **yes** — as `Skipped` + ticket | disable policy |

*invalid ≠ out-of-scope ≠ broken.*

## Structured identity is load-bearing

A concrete case's identity is the **`(baseId, Axis)` tuple, stamped structurally** — never an
opaque hash:

```
merge.deleteMatched[formatVersion=2,fileFormat=parquet,vectorized=true]
```

This is what makes two later features work:

- **Disable-by-axis-slice** — `disable tag:fileFormat=orc` matches on a coordinate (see `08`).
- **Sliceable reporting** — the result `Dataset` can be grouped by any dimension
  (pass-rate per file format, per format version, etc.).

If identity were a hash, neither would be possible.

## Result rows

Execution produces one small row per case:

```scala
final case class TestResult(
  id:            String,
  axis:          Axis,
  outcome:       Outcome,     // see 02
  attempts:      Int,         // > 1 means it was retried (see 03)
  durationMillis: Long
)
```

Small enough that `collect` at 20k is fine (see `01`).
