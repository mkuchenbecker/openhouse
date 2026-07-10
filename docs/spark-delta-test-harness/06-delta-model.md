# 06 — Delta Model

*Prerequisites: `index.md`, `05-test-case-model.md`.* This document defines how a test's
assertion is modeled. **This is the idea that makes the 20k matrix cheap to author.**

## Assert the change, not the absolute state

Model every test as a Hoare triple — `{P} X {Q}` — where **`Q` is expressed as a delta of the
pre-state you captured *this run*, not as an absolute snapshot.**

A test never says *"the table now equals rows {a,b,c}."* It says *"operation X moved the
observable state by **this** transform."* Because the assertion is a function of the
pre-state observed this run, it is **history-agnostic**: invariant to snapshot lineage,
commit count, and whatever state preceded it.

## The three pieces

`test` (from `05`) decomposes into:

```scala
final case class DeltaTest[S](
  observe:   Table => S,                   // capture measurable state AS A VALUE (see below)
  operation: Table => Unit,                // X — the mutation under contract
  expect:    (S, S) => Either[Diff, Unit]  // the delta contract: (pre, post) => verdict
)

def once(t: Table, dt: DeltaTest[S]): Either[Diff, Unit] = {
  val pre  = dt.observe(t)
  dt.operation(t)
  val post = dt.observe(t)
  dt.expect(pre, post)                     // asserts Q relative to P — no absolute state anywhere
}
```

- **`S` must be a cheap, comparable value** — a row count, the schema, a sorted key set, a
  partition summary. **Never the `Table` itself.** Deltas are computed by comparing two `S`
  values.
- `operation` is the system under test. Its exceptions are classified per `02`/`03` (by
  default, storage failure during X is `Errored`/retryable; a test that asserts X *rejects*
  makes "X throws" part of its verdict instead).

## Why this is the multiplier enabler

If tests asserted *absolute* state, each of the ~20× combinations would need its own expected
snapshot — you would hand-write 20,000 expectations. That is the anti-goal.

Because a delta is **invariant across combinations**, the same authored `expect` holds for
every combination, so **1000 authored deltas cover 20k cases**. And each combination "layer"
becomes a *free wrapper* over one authored triple, rather than new authoring:

### Example: history-agnosticism as a free combinator

Your worked example — create a table, run X, RTAS the table back to its initial state, run X
again; X must have identical pre/postconditions both times:

```scala
def historyAgnostic(t: Table, dt: DeltaTest[S], initial: Snapshot): Either[Diff, Unit] =
  for {
    _ <- once(t, dt)          // run 1: {P} X {Q}
    _ <- rtas(t, initial)     // reset DATA to initial; metadata/commit history now LONGER
    _ <- once(t, dt)          // run 2: same P, same Q — despite deeper snapshot lineage
  } yield ()
```

This is **not authored per test** — it is a harness combinator, and it only *works* because
the assertion is delta-based. An absolute-state assertion could never survive run 2: snapshot
ids, sequence numbers, and manifest counts have all advanced. The delta assertion doesn't
observe any of that, so it just holds. Identical deltas across both runs prove X is invariant
to commit history.

Other free layers, same reasoning:

- **warm / non-pristine fixtures** — a delta test doesn't demand a pristine table; `observe`
  establishes the baseline per run. This also buys back setup cost at scale (`setup` can hand
  out a reused table instead of rebuilding from zero 20k times).
- **deeper histories, reordered prior commits** — all invisible to a delta assertion.

Each of these is an `executionMode` axis value (see `07`) applied by the generator, not code
a human writes.

## Rule of thumb

If an assertion would break when run against a table that already has extra commits in its
history, it is an absolute-state assertion — rewrite it as a delta. History-independence is
the property that lets the generator combine cases freely.
