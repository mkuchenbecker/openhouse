# 04 — Resource Management

*Prerequisites: `index.md`, `03-error-handling.md`.* This document defines how a case
acquires and releases expensive resources (a Spark session, a storage client, a temp
namespace) on a Spark executor.

## Resources are thunks — ship the recipe, not the resource

Spark serializes the closure you hand to `mapPartitions` and sends it to executors. A live
`SparkSession` or storage client **is not serializable**; a function that *builds* one is.
So you pass a **thunk** — `() => R` — and materialize it lazily on the executor.

> Capturing a live resource in the closure kills the job at serialization. Capturing a thunk
> that builds it does not. This is not a style choice; it is what makes per-partition
> resources possible at all.

## `Managed` + `bracket`

A managed resource is a pair of thunks. `bracket` is a plain function that returns typed
errors — no effect library, no `throw` into the flow.

```scala
final case class Managed[R](acquire: () => R, release: R => Unit) {
  def flatMap[S](f: R => Managed[S]): Managed[S] = // compose nested resources:
    Managed(                                       // acquire in order, release in reverse
      acquire = () => f(acquire()).acquire(),
      release = /* threads the outer R through so both releases run */ ???)
}

def bracket[R, A](m: Managed[R])(use: R => Either[InfraError, A]): Either[InfraError, A] = {
  val r = try m.acquire() catch { case NonFatal(t) => return Left(classifyAcquire(t)) } // an edge
  try use(r)
  finally try m.release(r) catch { case NonFatal(_) => () }  // release never masks the verdict
}
```

- Acquire and release are **edges** (boundaries with the outside world), so the `try/catch`
  here is legitimate and immediately re-types into `InfraError` (consistent with `03` — the
  "single edge" is really "the edges", all of which re-type rather than propagate).
- A **release failure is swallowed** (logged as data), never allowed to overwrite a real
  `Passed`/`Failed`.

## The lazy-iterator hazard (read carefully)

`mapPartitions` hands you a lazy `Iterator[TestCase]`. The naive loan pattern is a **bug**:

```scala
mapPartitions { it => bracket(session) { s => Right(it.map(runCase(s, _))) } }   // WRONG
```

`bracket` returns the instant `use` yields the *lazy* iterator — so `release` fires and
closes the session **before a single row is computed.** Every case then runs against a closed
resource.

The fix keeps the iterator lazy (constant memory over the partition) while binding release to
the *task's* lifetime, using Spark's own mechanism:

```scala
mapPartitions { it =>
  val s = sessionThunk()                                              // acquire once per partition, lazily
  TaskContext.get.addTaskCompletionListener[Unit](_ => release(s))    // fires on success, failure, OR partial consumption
  it.map(c => runCase(s, c))                                          // stays lazy
}
```

`TaskContext`'s completion listener runs when the task ends **regardless of outcome** — even
if the partition is only partially consumed or the task dies. That is what reconciles "one
resource per partition, reused across a lazy stream" with "always released."

## Lifecycle summary

**thunk** (serializable, deferred) → **acquired once per partition** on the executor →
**reused across the streaming iterator** → **released by `TaskContext` completion**. Acquire
and release are edges that return typed `InfraError`; nothing throws into the flow.

→ How `setup` produces a `Managed[Table]` per axis is `05`.
