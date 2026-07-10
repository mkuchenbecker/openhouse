# 05 — Test Case Model

*Prerequisites: `index.md`, `04-resource-management.md`.* This document defines the unit a
human authors and how it becomes runnable.

## The authored unit: a `BaseTest`

```scala
final case class BaseTest(
  id:    String,
  axes:  AxisSpec,                       // which dimensions THIS test varies + valid ranges (see 07)
  setup: Axis => Managed[Table],         // axis shapes the fixture; storage-heavy; retryable
  test:  Table => Either[Diff, Unit]     // the invariant behaviour; axis-blind; the verdict lane
)
```

Two halves, authored separately, with a deliberate boundary between them.

## Curry the fixture; keep the test axis-blind

The axis flows into **`setup`, not into `test`.**

- `setup: Axis => Managed[Table]` — partially applying the axis yields a resource-managed
  table fixture (see `04`). This is the storage-heavy half: creating the table and writing
  data files under each `(formatVersion, fileFormat)`. It is therefore exactly where
  `IOException` lives, which is why it is the parameterized, retryable side.
- `test: Table => Either[Diff, Unit]` — asserts behaviour that must hold *regardless of how
  the table was built*. It never sees the axis. This is why it is the verdict lane.

**The curry boundary is the infra/verdict boundary.** You author the assertion once; the
generator (see `07`) exercises it against N differently-configured fixtures.

```scala
def runCase(bt: BaseTest, a: Axis): Outcome =            // (repeated from 03 for context)
  bracket(bt.setup(a))(t => Right(bt.test(t))) match {
    case Left(infra)       => Outcome.Errored(infra)     // infra came from setup
    case Right(Left(diff)) => Outcome.Failed(diff)       // verdict came from test
    case Right(Right(()))  => Outcome.Passed
  }
```

## Cribbing from Iceberg tests

The intended way to reach ~1000 base tests is to port Iceberg's own parameterized test suite.
An Iceberg JUnit `@Test` splits cleanly into these two halves:

| Iceberg construct | Becomes |
|---|---|
| `@Parameter` fields (format version, file format, vectorization…) | the `Axis` consumed by `setup` |
| table/catalog setup in `@Before` / test body | `setup` |
| `assertThat(...)` body | `test` |

The parameters in an Iceberg test were always shaping the *fixture*, never the assertion —
currying just makes that explicit. Cribbing a test = lift the setup into `setup`, lift the
assertion into `test`, and declare the former `@Parameter`s as this test's `AxisSpec`.

## `test` is normally built from a delta

A bare `Table => Either[Diff, Unit]` is the interface, but you rarely write it directly. In
practice `test` is constructed from a **delta test** — observe pre-state, apply the
operation, assert the change — which is what makes cases history-agnostic and combinable.
That is the subject of `06`, and it is where most of the authoring leverage comes from.
