# Industry survey — branching × retention/GC × merge (reference models)

Context for FEATURE-ANALYSIS-PLAN.md and finding G11. Question: how does the field design the
interaction between versioning/branching, retention/garbage-collection, and later integration
(merge/time-travel/restore) — and where does Iceberg's design sit? Labels: [WEB] = fetched this
session; [KNOWLEDGE] = from training, labeled where the fetch failed.

## The four industry models

| Model | Systems | Retention root | Can GC break a live branch's merge? | TT/restore failure mode | Modality documented? |
|---|---|---|---|---|---|
| **(A) Reachability-retention** | git, Nessie (default `NONE` cutoff) | anything reachable from ANY ref is never collected (+ reflog/prune grace that only EXTENDS retention) | **Never** — the merge-base is by definition reachable from both tips | only genuinely-unreachable history; loud ("bad object") | inherent to the model |
| **(B) Ancestry-forever + data-GC** | lakeFS | commit DAG is NEVER punctured; GC deletes only data objects past per-branch retention | **Never** — merges use the intact DAG | old-commit data read → **HTTP 410 Gone** (purpose-built "intentionally expired" signal) | yes, protocol-level |
| **(C) Consumer-pinning** | Snowflake zero-copy clones | any live consumer (clone) pins the micro-partitions it references | n/a (no merge) — but a clone can never be starved by source retention | bounded by named windows (Time Travel 0-90d + Fail-safe 7d) | yes, named windows |
| **(D) Honest-documented-modality** | Delta Lake VACUUM | recency window on data files (`delta.deletedFileRetentionDuration`, default 7d) | n/a (no branching) | past-window TT/RESTORE → loud error tied to the NAMED property; shallow-clone-vs-VACUUM footgun documented on the feature page | **exemplary** |

Key detail per system:
- **git**: invariant is *reachable ⇒ retained*; recency (reflog 90d/30d, prune 2w) only extends
  retention, never overrides reachability. Unreachable clusters expire atomically ("all or none"). [WEB]
- **Nessie**: mark-and-sweep over its OWN commit graph across ALL named refs; default cutoff `NONE`
  (everything live). Crucially its merge operates on the catalog commit graph, NOT on Iceberg
  snapshot ancestry — the mergeable history lives in a structure whose default policy keeps it. [WEB]
- **lakeFS**: "garbage collection does not remove any commits" — ancestry permanent, data windowed,
  and the failure signal (410 Gone) distinguishes "expired by policy" from "missing/corrupt". [WEB]
- **Snowflake**: clone = pointers to source micro-partitions; partitions stay alive until source AND
  all clones stop referencing them — retention respects consumers. [WEB]
- **Delta**: every way to lose old data (VACUUM window, RESTORE limit, clone/source coupling, CDF
  retention) is (i) a named tunable property, (ii) documented on the page of the feature it breaks,
  (iii) fails with an error pointing back at the rule. [WEB]

## Where Iceberg sits: the outlier on both axes

Iceberg branch retention (`min-snapshots-to-keep` / `max-snapshot-age-ms` / `max-ref-age-ms`,
default "forever" but overridden by table-level expire args like `retain_last`) is a **per-ref
recency window, unioned across refs** — NOT a reachability closure. An intermediate ancestor of a
live branch that falls outside every ref's window is expired **even though it is reachable from a
live tip**. None of A/B/C ever delete reachable/referenced history. And unlike D, the modality is
**documented nowhere upstream**: the branching docs list the properties with no warning that
expiration can sever ancestry that fast_forward/cherry-pick/merge consume; the WAP docs don't warn
that unpublished staged snapshots are expirable. The failure then surfaces later, in a different
operation, with an error that names the wrong cause ("not an ancestor"). [WEB: docs checked; the
retention-algorithm details and the causal link are KNOWLEDGE + bytecode-verified locally, since no
upstream source names the hazard — which is itself the finding.]

**Judgment**: the field's consensus invariant is *referenced ⇒ retained* (A/B/C), and where that is
relaxed, the loss is made honest (D). Iceberg's design is the only one that deletes reachable
ancestry, and it does so without the documentation/error discipline that would make the loss honest.
An Iceberg *catalog* (OpenHouse) is positioned to restore the invariant: protect merge-connectivity
between live refs at expiration time (A), or keep snapshot lineage metadata while expiring only data
(B), or pin ancestry consumed by known consumers — branches, streaming checkpoints (C), or at
minimum document the window and fix the error messages (D).
