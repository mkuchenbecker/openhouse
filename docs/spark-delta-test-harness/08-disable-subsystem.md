# 08 — Disable Subsystem

*Prerequisites: `index.md`, `02-outcome-model.md`, `07-generation-and-identity.md`.* This
document defines how known-broken cases are turned off while a bug is outstanding — without
lying about the health of the suite.

## Why disable exists

The suite holds a **100%-green contract** (see `02`): zero `Failed`, no thresholds. But bugs
exist. The only honest way to keep green true while a bug is open is to **disable** the
affected case: it is reported as `Skipped` (with a ticket), not as a tolerated `Failed`, and
not silently dropped. `Skipped` is the visible, auditable ledger of what the suite owes.

Disable is distinct from *applicability* and *selection* (see `07`) — a disabled case is
in-scope and valid; it is just known to be broken right now.

## A disable rule targets cases by identity or axis slice

Because identity is structured `(baseId, Axis)` (see `07`), a rule can match a specific case
or a whole axis slice:

```scala
final case class DisableRule(
  matcher: CaseSelector,        // by id, by baseId, or by axis coordinate (e.g. fileFormat=ORC)
  reason:  String,              // REQUIRED — a bug/ticket link
  owner:   String,              // REQUIRED — who owns the fix
  expires: Instant              // REQUIRED — see "expiry" below
)
```

`reason`, `owner`, and `expires` are mandatory. A disable without them is how a suite rots.

## Layered sources, later wins

The effective disabled set is composed from layers, so you can disable durably in code, add
ad-hoc disables via config review, and disable/re-enable at ops time via environment without
a deploy:

```
effectiveDisabled = ( codeRegistry            // checked-in, known-broken
                    ∪ configFile              // reviewed disable list
                    ∪ ENV  OH_TEST_DISABLE )  // ops-time, no deploy:  tag:fileFormat=orc,id:merge.x[...]
                    \ ENV  OH_TEST_ENABLE      // force-run to verify a fix, overrides the above
```

- **ENV disable** lets you silence a newly-flapping case immediately.
- **ENV force-enable** lets you re-run a disabled case to confirm a fix, without editing the
  durable list.

## Resolve on the driver; keep disabled cases visible

Disable is pure metadata — no execution needed to decide it. Resolve it on the **driver** and
split the case set, so disabled cases still appear in the report but cost no executor time:

```scala
val (toRun, toSkip) = cases.partition(disablePolicy.isEnabled)
val executed = run(toRun)                                                   // the pipeline (see 01)
val skipped  = toSkip.map(c => TestResult(c.id, c.axis,
                    Outcome.Skipped(disablePolicy.reason(c)), attempts = 0, durationMillis = 0))
report(executed ++ skipped)                                                 // union → full visibility
```

## Expiry keeps the ledger honest

Every disable has an `expires` timestamp. An **expired disable is surfaced as its own
failure** — "this case has been disabled past its expiry; fix it or renew the disable with a
reason." Without expiry, disabled-forever becomes a graveyard and the green signal quietly
degrades into a lie.

## Visualization comes for free

Because `Skipped` is a first-class row in the same `Dataset[TestResult]`, carrying the case's
axis tags (see `07`), *"what is disabled and why"* is a query, not a side channel:

```scala
results.filter(_.outcome.isSkipped).groupBy(_.reason)   // or group by any axis coordinate
```

Provide a **dry-run mode** — `--report-disabled` — that runs *only* generation +
disable-resolution (no execution) and renders the manifest: id, reason, owner, expiry, and
whether each is expired. That is how you audit suite health without running 20k cases.
