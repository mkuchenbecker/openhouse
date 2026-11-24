# Decision log, continued a sixth time

Continues `56-decisions-continued-5.md` (141 lines, D43 to D47). D48 was numbered by the orchestrator and D49 follows it.

Bottom line: two decisions, both made by the orchestrator on 2026-11-24, both two-way doors. D48 puts the `variant`
column type behind its own cluster property, off by default, so that turning on format v3 does not by itself open a
one-way door with degraded maintenance. D49 makes the scan planning route refuse format v3 tables until scan tasks carry
sequence numbers. Neither is agreed by Mike. Claude wrote the entries.

## D48. The `variant` column type goes behind its own cluster property, off by default

- Date: 2026-11-24. The orchestrator's decision, a two-way door. Work item X5, pull request 129 (merged 2026-11-24).
- Context: X5 decides each column type format v3 adds. Its author ran every maintenance job on a `variant` table holding
  data and deletes. No job loses or changes data. But the jobs run on Spark 3.5, which cannot read `variant`, so on such
  a table: it is never compacted; every scheduled job reports failure on every run (the closing validation step reads the
  table through Spark 3.5); Spark 3.5 users cannot SELECT or DESCRIBE it; and it is refused a retention policy.
  OpenHouse refuses DROP COLUMN on every table, so taking a `variant` column is permanent for that table.
- Decision: turning on the cluster's format v3 switch (`cluster.iceberg.format-v3.enabled`) must not by itself open
  that. `variant` needs `cluster.iceberg.format-v3.variant.enabled`, default false. With it off, a `variant` column is
  refused with the existing refusal token and a message that names the property.
- Why two-way: the property can be turned on per cluster, and its default can change once the jobs run on Spark 4.
- Rejected: refusing `variant` outright, because it works for a Spark 4.2 platform reading through the Iceberg REST
  routes. Supporting it under the v3 switch alone, because that opens a per-table one-way door with degraded maintenance.
- Status: implemented by pull request 129, merge commit `70b35d7a` (`68-x5-v3-types-result.md`). One detail was decided
  during implementation: a table that already has a `variant` column keeps it and is not blocked when the property is
  turned off again; only new `variant` columns are refused.
- Related: the three X5 questions in `58-waiting-on-mike.md`; this decision narrows the `variant` one.

## D49. The scan planning route refuses format v3 tables

- Date: 2026-11-24. The orchestrator's decision, a two-way door. Work item V11e. Finding from pull request 131
  (`66-v11b-v11c-result.md`).
- Finding: on format v3 a scan task carries no sequence number (the specification's data file has none and Iceberg 1.12's
  parser reads none). A server-planned read returns a null `_last_updated_sequence_number`, and a server-planned
  copy-on-write rewrite stores wrong row lineage for the rows it keeps.
- Decision: until scan tasks carry sequence numbers, the plan route answers a format v3 table with a visible failure
  (status `failed`, as for a plan above the bound) instead of planning it.
- Why two-way: no default client plans on the server, and the refusal can be lifted when the specification and client
  carry sequence numbers.
- Rejected: serving v3 plans and documenting the difference, because that is a wrong answer and a silent, permanent
  lineage change.
- Bearing on V11d (Mike's decision on turning server planning on from the server): server planning must not be offered
  to writers of format v3 tables. Noted on the V11d row in `41-work-platforms-v3.md`.
- Undecided, delegated to the implementer with a correctness condition: whether a filter on a column added after the
  current snapshot, which answers 400 under server planning, can be made to equal client planning. If it cannot be made
  equal, it stays pinned as it is.
- Status: being implemented as V11e (worktree `/home/user/oh-v11e`, branch `irc/v11e-plan-v3-and-late-columns`, record
  `v11e-plan-v3-and-late-columns.md` in the reports directory).
