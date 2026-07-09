# Distributed Delta-Test Harness — Agent Brief

## The goal (read this first)
Build the **form** — a Scala/Spark library — so a human authors **O(100–1000) base tests**
and the machine generates **O(20k) concrete cases**. The 10–100× multiplication is a
*generator*, never a keyboard.

> If you catch yourself hand-writing a per-combination expected value, the form is wrong.
> Stop and fix the form.

Function requires good form. Everything below is the form that makes the function cheap.

---

## Mental model
- **Spark is the driver; tests are the data.** The entire run is one pipeline:
  `generate → filter → mapPartitions(execute) → collect(report)`.
  No test framework drives the run. ScalaTest, if present at all, survives only as
  assertion *logic* inside a case — never as the runner.
- **Build locally, run anywhere.** One self-contained artifact; the Spark master is
  external config. Local / CI / smoke differ **only in values** — master, selector
  predicate, target, sink — *never* in code paths. The moment "smoke" becomes its own
  file, the reuse property is lost.

---

## The outcome algebra (the spine)
Four lanes, all **typed values**, none thrown:

| Lane | Meaning | Retry? | Gates green? |
|---|---|---|---|
| `Passed` | contract held | — | — |
| `Failed(diff)` | real bug | **never** | **yes — absolute** |
| `Errored(InfraError)` | no verdict yet | yes (+reschedule) | **no** |
| `Skipped(reason)` | disabled on purpose | — | no (but audited) |

Rules that must hold structurally, not by convention:
- **Assertion failure = terminal `Failed`.** Never retried.
- **`IOException`/transient = `Errored`.** Retryable.
- **Retryability is an allowlist. Fail closed.** An unrecognized throwable ⇒ terminal
  `Unclassified`, surfaced loudly. Never silently retried (that hides a deterministic bug
  as infra) and never silently passed.
- **Classification: phase is primary, type secondary.** An assert-phase throw is `Failed`
  regardless of type; the type allowlist only decides retryable-vs-terminal *within* the
  `Errored` lane. **Always unwrap the cause chain** before matching
  (`SparkException → ExecutionException → IOException`).

---

## Errors are values, not control flow
- Boundary adapters return `Either[InfraError, A]` and type their own failures at the
  point of contact.
- A case is a typed composition. **Two nested `Either`s keep the lanes uncrossable:**
  outer = infra (short-circuits, retryable), inner = verdict (`Failed`/`Passed`).
- **Exactly one `try/catch` in the library:** the executor edge,
  `case NonFatal(t) => Errored(Unclassified(t))`. `NonFatal` so OOM/VM errors still kill
  the task. Nothing else throws for control flow.

---

## Resources = thunks
- **Ship the recipe, not the resource.** `() => R` is serializable; a live
  `SparkSession`/client is not. Materialize lazily on the executor.
- `Managed[R](acquire, release)` + a plain `bracket` returning typed errors. Acquire and
  release are edges → typed `InfraError`, never throw into the flow. A release failure
  never masks a verdict.
- **Lazy-iterator hazard:** `bracket` wrapped around `it.map(...)` releases *before* rows
  compute. Fix: acquire **once per partition**, register
  `TaskContext.get.addTaskCompletionListener(release)` as the backstop (fires on success,
  failure, *and* partial consumption), and keep the iterator lazy for constant memory.

---

## The case shape
- **Curry the fixture.**
  `setup: Axis => Managed[Table]` — the axis shapes the fixture; storage-heavy; retryable.
  `test: Table => Either[Diff, Unit]` — axis-blind; the invariant; verdict lane.
  The **curry boundary is the infra/verdict boundary**.
- **Cribbing an Iceberg `@Test`:** its `@Parameter` fields become the `Axis` consumed by
  `setup`; its `assertThat(...)` body becomes `test`. The parameters always shaped the
  fixture, never the assertion.

---

## Delta-modeled tests (the multiplier enabler)
- Model every test as a Hoare triple **`{P} X {Q}` where `Q` is a *delta* of the pre-state
  captured this run — not an absolute snapshot.** History-agnostic by construction:
  invariant to snapshot lineage, commit count, and prior state.
- Decompose `test` into:
  - `observe: Table => S` — capture state as a **cheap comparable value** (counts, schema,
    sorted key set, partition summary). Never the table itself.
  - `operation: Table => Unit` — X, the mutation under contract.
  - `expect: (S, S) => Either[Diff, Unit]` — the delta contract.
- Because deltas are invariant across combinations, **combination layers become free
  wrappers over one authored triple:**
  - `RepeatAfterRtasReset`: run X, RTAS to initial, run X — assert identical delta despite
    deeper metadata. Proves invariance to commit history.
  - warm/non-pristine fixtures, deeper histories, reorderings.
- **This is why 1000 authored `expect`s cover 20k cases.** Absolute-state assertions would
  force 20k hand-written expectations — the anti-goal. Delta tests compose because deltas
  compose.

---

## Generation & identity
- **`Axis` is a fixed, typed set of dimensions** (formatVersion, fileFormat, vectorized,
  partitioned, distribution, executionMode…). Adding a dimension is a deliberate library
  change, not per-test freeform.
- **Cross-product is per-test-constrained**, never a uniform global multiply. Each base
  declares `axes.enumerate` (its valid sub-cube); the sum of local products ≈ 20k.
- **Three distinct notions — keep them separate:**

  | Concept | When | In report? | Mechanism |
  |---|---|---|---|
  | Applicability (invalid combo, e.g. v1 merge-on-read) | generation | no — never existed | `axes.enumerate` prunes |
  | Selection (context scope: local/CI/smoke) | `filter` verb | no — out of scope | context predicate |
  | Disable (known-broken) | pre-execute | **yes — `Skipped`** | disable policy |

- **Identity = structured `(baseId, Axis)`** →
  `merge.deleteMatched[formatVersion=2,fileFormat=parquet,vectorized=true]`. Load-bearing:
  enables disable-by-axis-slice and group-by-any-dimension reporting. Never an opaque hash.

---

## Disable subsystem
- Layered sources, later wins:
  `codeRegistry ∪ configFile ∪ ENV_disable  \  ENV_forceEnable`
  (force-enable lets you verify a fix without a deploy).
- Disabled cases are computed **on the driver** as `Skipped` rows and unioned with executed
  results — full visibility, zero wasted executors.
- Every disable carries **reason (bug link), owner, expiry**. An expired disable surfaces
  as its own failure. `--report-disabled` dry-run renders the manifest without running
  cases.

---

## Green contract
- **Gate on `Failed` (and expired disables) only.** Never gate on baseline `Errored`
  (~1% is expected weather); instead **alarm on an `Errored`-rate spike**.
- Known bug ⇒ **fix or disable**, never a tolerated `Failed`. The red/green signal stays
  binary and trustworthy.

---

## Skew & scale
- Cost-aware partitioning **at the source** (partition count where the matrix is created),
  not a fifth pipeline verb. The straggler sets wall-clock; balance expensive vs cheap.
- Results are small (one row per case) — `collect` at 20k is fine.

---

## Anti-goals (stop and reconsider if you catch yourself doing any of these)
- Hand-writing per-combination expected state.
- `try/catch` anywhere but the executor edge.
- Capturing a `SparkSession`/resource in a closure (serialization death) instead of a thunk.
- Nesting a `SparkContext` inside an executor task.
- Absolute-state assertions on Iceberg metadata (snapshot ids, sequence numbers, commit counts).
- Silent skips — dropping disabled/invalid cases so they vanish from the report.
- Collapsing applicability, selection, and disable into one filter.
- Thresholded `Failed`, or retrying an unknown exception.

---

# Verification instructions

**Principle: test the tester.** Verify with fixtures whose outcomes are known *a priori* —
plant one of each kind and assert the harness reports **exactly** the expected outcome
vector. Fault-inject at every lane boundary. A harness that can't prove its own verdicts
is worthless above it.

### 1. Classification correctness (golden self-tests)
Plant known cases; assert the reported `Outcome`:
- Truly-passing delta test ⇒ `Passed`.
- Deliberately-wrong `expect` ⇒ `Failed`, and the run goes red / the gate trips.
- `setup` throws transient `IOException` ⇒ `Errored`, retried, eventually `Passed`.
- `setup`/`operation` throws deterministic `NPE` ⇒ terminal `Unclassified`, surfaced
  loudly, **not** retried away, **not** passed.
- Wrapped transient (`SparkException(ExecutionException(IOException))`) ⇒ still classified
  retryable (proves cause-chain unwrap).

### 2. The firewall (the property that justifies the whole harness)
- Inject a transient storage fault that heals after *k* attempts ⇒ assert **100% of cases
  end `Passed` with retries > 0** — infra never manufactured a `Failed`.
- Inject a deterministic bug ⇒ assert it surfaces as `Failed`/terminal **every** time and
  the gate trips — a real bug never hid in the `Errored` lane.
- These two are always-on tests. They are the harness's reason to exist.

### 3. Resource lifecycle
- Fault-inject acquire failure ⇒ `Errored`, no leak.
- Assert release runs on **normal completion, task failure, and partial iterator
  consumption** (drive via `TaskContext`). Count acquire vs release — they must match.
- Assert the lazy path does **not** release early (a case mid-stream still sees a live
  resource).

### 4. Generation & identity
- Author one base test with a declared sub-cube ⇒ assert exact generated count and exact
  structured ids.
- Assert invalid combos are **absent** (v1 merge-on-read never generated).
- Assert adding a base test requires **zero** changes to the runner/pipeline (decoupling).
- Property: `|generated| == Σ per-test |axes.enumerate|`; ids injective; no dupes.

### 5. Delta / history-agnosticism
- Run the `RepeatAfterRtasReset` combinator over a sample ⇒ assert both runs yield
  identical deltas despite advanced snapshot metadata.
- **Negative control:** an intentionally absolute-state assertion must **fail run 2** —
  proving the combinator actually exercises history sensitivity.

### 6. Disable subsystem
- Config-disabled case ⇒ `Skipped` in report (not dropped).
- ENV disable adds; ENV force-enable overrides to run.
- Expired disable ⇒ surfaced as a failure.
- `--report-disabled` dry-run lists exactly the disabled set with reasons and runs no cases.

### 7. Portability (build locally, run anywhere)
- Run the identical job under `local[*]`; assert smoke-selector ⇒ tagged subset,
  CI-selector ⇒ full set, and each sink emits correctly
  (console / JUnit-XML + exit code / threshold).
- Assert **non-zero exit ⇔ ≥1 `Failed`**. The gate must actually gate.

### 8. Determinism & idempotency
- Same case run twice ⇒ identical verdict.
- Reorder partitions / re-run ⇒ stable outcome set.
- Case run **alone** vs **inside a full partition** ⇒ same result (no inter-case state leak).

### 9. Purity guard
- Assert (test or static check) that no throwable escapes execution except `NonFatal` at
  the edge.

### Sequencing (build and verify bottom-up)
Everything above an unverified classifier inherits its lies. Verify each layer in
isolation before the next:
1. Outcome algebra + classifier → verify §1–2 in **pure JVM, no Spark** (fastest feedback; it's the spine).
2. `Managed`/`bracket`/edge → verify §3.
3. Generator + identity → verify §4.
4. Spark pipeline under `local[*]` → verify §7–9.
5. Delta combinators → verify §5.
6. Disable subsystem → verify §6.
