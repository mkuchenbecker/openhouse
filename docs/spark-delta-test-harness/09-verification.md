# 09 — Verification

*Prerequisites: all prior documents.* This document defines how to prove the **harness
itself** is trustworthy. A test harness produces verdicts; if the harness is wrong, every
verdict above it is a lie. So the harness must be verified before it is trusted.

## Principle: test the tester

Verify with **fixtures whose outcomes are known a priori.** Plant one case of each kind, run
it through the real harness, and assert the harness reports **exactly** the expected outcome.
Fault-inject at every lane boundary. These self-tests are part of the harness and run always.

## 1. Classification correctness (golden self-tests)

Plant known cases; assert the reported `Outcome`:

- Truly-passing delta test ⇒ `Passed`.
- Deliberately-wrong `expect` ⇒ `Failed`, and the run goes red / the gate trips.
- `setup` throws a **transient** `IOException` ⇒ `Errored`, retried, eventually `Passed`.
- `setup`/`operation` throws a **deterministic** `NullPointerException` ⇒ terminal
  `Unclassified`, surfaced loudly — **not** retried away, **not** passed.
- A wrapped transient (`SparkException(ExecutionException(IOException))`) ⇒ still classified
  retryable (proves the cause-chain unwrap from `02`).

## 2. The firewall (the property that justifies the whole harness)

These two are always-on and non-negotiable:

- Inject a transient storage fault that heals after *k* attempts ⇒ assert **100% of cases end
  `Passed` with attempts > 1** — infra never manufactured a `Failed`.
- Inject a deterministic bug ⇒ assert it surfaces as `Failed`/terminal **every** time and the
  gate trips — a real bug never hid in the `Errored` lane.

If either of these fails, nothing else about the harness can be believed.

## 3. Resource lifecycle (`04`)

- Fault-inject **acquire** failure ⇒ `Errored`, no leak.
- Assert **release runs** on: normal completion, task failure, and *partial* iterator
  consumption (drive via `TaskContext`). **Count acquire vs release — they must match.**
- Assert the lazy path does **not** release early: a case mid-stream still sees a live
  resource (guards against the lazy-iterator hazard).

## 4. Generation and identity (`07`)

- Author one base test with a declared sub-cube ⇒ assert the **exact** generated count and
  the **exact** structured ids.
- Assert invalid combinations are **absent** (e.g. v1 merge-on-read never generated).
- Assert adding a base test requires **zero** changes to the runner/pipeline (decoupling).
- Property: `|generated| == Σ per-test |axes.enumerate|`; ids injective; no duplicates.

## 5. Delta / history-agnosticism (`06`)

- Run the `RepeatAfterRtasReset` combinator over a sample ⇒ assert both runs yield
  **identical deltas** despite advanced snapshot metadata.
- **Negative control:** an intentionally *absolute*-state assertion must **fail run 2** —
  proving the combinator actually exercises history sensitivity (otherwise the test is
  vacuous).

## 6. Disable subsystem (`08`)

- A config-disabled case ⇒ `Skipped` in the report (not dropped).
- `ENV OH_TEST_DISABLE` adds; `ENV OH_TEST_ENABLE` overrides to run.
- An **expired** disable ⇒ surfaced as a failure.
- `--report-disabled` dry-run lists exactly the disabled set with reasons and runs **no**
  cases.

## 7. Portability (`01`)

- Run the identical job under `local[*]`; assert the smoke selector yields the tagged subset,
  the CI selector yields the full set, and each sink emits correctly (console / JUnit-XML +
  exit code / threshold).
- Assert **non-zero exit ⇔ ≥ 1 `Failed`.** The gate must actually gate.

## 8. Determinism and idempotency

- Same case run twice ⇒ identical verdict.
- Reorder partitions / re-run ⇒ stable outcome set.
- A case run **alone** vs **inside a full partition** ⇒ same result (no inter-case state
  leak). This is what makes retry sound.

## 9. Purity guard

- Assert (test or static check) that **no throwable escapes execution except `NonFatal` at
  the edge** — e.g. wrap a partition and assert only the edge ever produces `Unclassified`.

## Build & verify bottom-up

Everything above an unverified classifier inherits its lies. Verify each layer in isolation
before building the next:

1. Outcome model + classifier (`02`) → verify §1–2 in **pure JVM, no Spark** (fastest
   feedback; it is the spine).
2. `Managed` / `bracket` / edge (`03`, `04`) → verify §3.
3. Generator + identity (`07`) → verify §4.
4. Spark pipeline under `local[*]` (`01`) → verify §7–9.
5. Delta combinators (`06`) → verify §5.
6. Disable subsystem (`08`) → verify §6.
