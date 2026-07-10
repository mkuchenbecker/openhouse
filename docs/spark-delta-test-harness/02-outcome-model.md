# 02 — Outcome Model

*Prerequisite: `index.md`.* This document defines what the result of running a case *is*, and
the rules that keep results trustworthy. This is the spine of the whole design.

## Four lanes, all typed values

Running a case yields exactly one `Outcome`. None of these are *thrown* — they are returned
values (see `03`).

```scala
sealed trait Outcome
object Outcome {
  case object Passed                                extends Outcome  // the contract held
  final case class Failed (diff: Diff)              extends Outcome  // a real bug in the system under test
  final case class Errored(err: InfraError)         extends Outcome  // no verdict yet — infrastructure hiccup
  final case class Skipped(reason: DisableReason)   extends Outcome  // disabled on purpose (see 08)
}
```

| Lane | Meaning | Retried? | Counts against "green"? |
|---|---|---|---|
| `Passed` | contract held | — | — |
| `Failed` | real bug | **never** | **yes — absolute** |
| `Errored` | infra, no verdict | yes (see `03`) | **no** |
| `Skipped` | disabled | — | no (but audited) |

## The firewall: infra failure is NOT test failure

This is the single most important property of the harness. At scale, roughly **1% of jobs
fail for infrastructure reasons** (a storage timeout, a flaky node). If those 1% get reported
as `Failed`, then on 20k cases you manufacture ~200 fake bugs per run and the real signal
drowns.

So the two channels must be **impossible to confuse**, enforced by types, not by discipline:

- A **failed assertion** → `Failed`. This is a verdict about the system under test.
- An **infrastructure exception** (e.g. `IOException` from storage) → `Errored`. This is *not*
  a verdict; it means "we couldn't get an answer."

## The 100%-green contract

**Green means zero `Failed`. Absolutely.** No thresholds, no "acceptable failure rate." One
`Failed` = the run is red.

Consequences:

- **Gate only on `Failed`** (and expired disables, see `08`). Never gate on baseline
  `Errored` — ~1% is expected weather. Instead, **alarm on an `Errored`-*rate* spike** (e.g.
  errored > 2%), because a spike is itself a signal even though the baseline is not.
- A known bug has exactly two honest resolutions: **fix it, or disable it** (→ `Skipped`,
  with a ticket). You never leave it as a tolerated `Failed`. This keeps the red/green
  signal binary and trustworthy, and `Skipped` becomes the visible, auditable ledger of debt.

## Classifying a throwable

Adapters return typed errors at their boundary (see `03`), but when a raw `Throwable` must be
classified, the rules are:

1. **Phase is primary.** Where did it come from?
   - From the **assertion** → `Failed` (regardless of exception type).
   - From anywhere else (setup, operation, teardown, storage I/O) → candidate `Errored`.
2. **Type is secondary, and retryability is an allowlist — fail closed.** Within the
   `Errored` lane, only *known-transient* types are retryable (`IOException`,
   `SocketTimeoutException`, connection-reset, throttling/5xx, provider-specific such as S3
   `SdkClientException` or Azure `StorageException`). **Anything not on the allowlist is
   terminal and surfaced loudly** as an unclassified error.
3. **Always unwrap the cause chain** before matching:
   `SparkException → ExecutionException → IOException`. A wrapped transient that isn't
   unwrapped looks unclassified and fails closed incorrectly.

```scala
sealed trait Verdict
case object Terminal  extends Verdict   // Failed OR unclassified-error: never retry
case object Retryable extends Verdict   // known transient: retry (+ reschedule)

def classify(phase: Phase, t: Throwable): Verdict = phase match {
  case Phase.Assert                     => Terminal                  // assertion = verdict
  case _ if isKnownTransient(unwrap(t)) => Retryable                 // allowlist only
  case _                                => Terminal                  // fail closed, escalate
}
```

### Why the allowlist direction matters

If the default were "unknown ⇒ retryable," a deterministic bug that throws (say) a
`NullPointerException` would be mis-filed as infra, retried a few times, and **escape the
green gate forever**. That is the exact failure mode this whole design exists to prevent. An
exception you don't recognize must fail *visibly*, never hide in the retry lane.

→ How these outcomes are produced without `throw`, and how retry is driven, is in `03`.
