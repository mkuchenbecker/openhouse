# 03 — Error Handling

*Prerequisites: `index.md`, `02-outcome-model.md`.* This document defines how outcomes are
produced as **typed values** rather than via thrown exceptions, and how retry works.

## Principle: errors are values, not control flow

`throw` is a side channel — it bypasses the type system and hides where failure can occur.
This harness forbids it as control flow. Every operation that can fail returns its failure as
a **typed value**. There is exactly **one** `try/catch` in the entire library, at the
executor edge, and its only job is to convert an *escaped* runtime exception back into a
typed value.

## The infra error is an ADT, not a `Throwable` hierarchy

```scala
sealed trait InfraError { def retryable: Boolean }
object InfraError {
  final case class StorageUnavailable(detail: String) extends InfraError { val retryable = true  }
  final case class Timeout(detail: String)            extends InfraError { val retryable = true  }
  final case class Unclassified(captured: Throwable)  extends InfraError { val retryable = false } // edge only
}
```

`Unclassified` is the **only** place a `Throwable` is stored — it is the typed capture of an
escaped exception, carried as data and marked non-retryable (fail closed, per `02`).
Boundary adapters (storage, catalog, session) type *their own* failures at the point of
contact and return `Either[InfraError, A]`; no exception crosses an adapter boundary as
control flow.

## Two nested `Either`s keep the lanes uncrossable

A case has two distinct kinds of failure — infra and verdict — so its result type has two
layers. You **cannot** accidentally file an infra error as a bug, because they are not the
same type.

```scala
type Diff = ...                              // describes an assertion mismatch (the verdict lane)

def runCase(bt: BaseTest, a: Axis): Outcome = {
  val program: Either[InfraError, Either[Diff, Unit]] =
    bracket(bt.setup(a))(table => Right(bt.test(table)))   // see 04 for bracket / setup

  program match {
    case Left(infra)       => Outcome.Errored(infra)   // outer Left = infra lane
    case Right(Left(diff)) => Outcome.Failed(diff)     // inner Left = verdict lane (bug)
    case Right(Right(()))  => Outcome.Passed
  }
}
```

- **Outer `Either`** = infrastructure (short-circuits, retryable).
- **Inner `Either`** = verdict (`Failed` vs `Passed`).

Note the assertion (`bt.test`) returns `Either[Diff, Unit]` — a failed assertion is
`Left(diff)`, a **value**, not a thrown `AssertionError`. The side channel is gone.

## The single edge

Third-party code may still `throw` instead of returning. That is caught in exactly one place
— the executor edge — and immediately re-typed:

```scala
def runAtEdge(bt: BaseTest, a: Axis): Outcome =
  try runCase(bt, a)
  catch { case NonFatal(t) => Outcome.Errored(InfraError.Unclassified(t)) }
```

- `NonFatal` so `OutOfMemoryError` / `VirtualMachineError` still kill the task (never swallow
  a dying JVM).
- The escaped exception becomes `Unclassified` → terminal, surfaced loudly (per `02`'s
  fail-closed rule). It is never silently retried and never silently passed.
- **No other `try/catch` exists in the library.**

## Retry is a pure fold over a typed field

Because `InfraError.retryable` is a field on a value, retry is a decision over *data* — no
exception matching:

```scala
@tailrec def attempt(n: Int)(run: => Outcome): Outcome = run match {
  case Outcome.Errored(e) if e.retryable && n < policy.max => attempt(n + 1)(run)
  case terminal                                            => terminal
}
```

- Only `Errored(retryable)` is retried. `Failed` is **never** retried (a bug reproduces;
  retrying masks flakiness and wastes the matrix). `Passed`/`Skipped` are terminal.
- Retry is only sound because cases are **hermetic and idempotent** (see `06`) — a retry
  re-establishes its own precondition and leaves no residue.
- **Correlated failure caveat:** a dead executor fails *all* its cases together. In-partition
  retry handles transient faults (timeout, throttle); Spark's own task retry is the coarse
  backstop for node death. Never `throw` to force a reschedule — that risks aborting the
  stage after `spark.task.maxFailures`.

→ Where `setup`/`bracket`/`Managed` come from is `04`. What `bt.test` decomposes into is
`06`.
