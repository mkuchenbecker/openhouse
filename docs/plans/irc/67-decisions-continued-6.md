# Decision log, continued a sixth time

Continues `56-decisions-continued-5.md` (141 lines, D43 to D47). D48 was numbered by the orchestrator; D49 to D51 follow it.

Bottom line: four decisions, all made by the orchestrator (D48 to D50 on 2026-11-24, D51 on 2026-11-25; D51 is last in the file and keeps the rest-shim refusing a gated replace at commit). D48 and D49 are two-way doors; D50 splits the
Spark 4.2 jobs into a two-way part that lands and a one-way part that goes to Mike. D48 puts the `variant`
column type behind its own cluster property, off by default, so that turning on format v3 does not by itself open a
one-way door with degraded maintenance. D49 makes the scan planning route refuse format v3 tables until scan tasks carry
sequence numbers. D50 lets the maintenance jobs run on Spark 4.2 over the Iceberg REST routes without deploying anything. None is agreed by
Mike. Claude wrote the entries.

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
- Delegated to the implementer with a correctness condition: whether a filter on a column added after the current
  snapshot, which answered 400 under server planning, can be made to equal client planning. How it ended: the
  late-added-column case was fixed, because every listed case was shown equal to client planning (`69-v11e-result.md`).
- Status: implemented by pull request 132, merge commit `9f9561e6` (V11e, `69-v11e-result.md`), 2026-11-24.

## D50. The maintenance jobs get a Spark 4.2 project over the Iceberg REST routes; the first five items land together

- Date: 2026-11-24. The orchestrator's decision. Work item N11; the scout is in `70-n11-jobs-spark42-scout-result.md`.
- Decision, what lands (two-way doors), as one pull request: the project `apps/spark-4.2` compiling the same shared job
  sources, its suite green and run by CI, not opt-in; expectations that differ on that line declared per line with
  reasons; `Operations.getCatalog` working with `type=rest`; the SYSTEM action sent as a request header when the catalog
  is the REST catalog; the temporary table of sort stats collection taking the source table's format version.
- Condition: the Spark 3.5 jobs behave exactly as before on format v2 tables, shown by their full suite on both build lines.
- Decided inside D50: six concurrency cases behave differently over REST and that is correct. A commit prepared before a
  snapshot expiry is accepted over REST, where the native client is refused and retries. A REST commit sends changes plus
  requirements, and expiry does not move the branch, so the requirements hold. The result equals the serial order and the
  data checks pass. The tests pin it with assertions that no expired snapshot returns.
- What does not land and goes to Mike (one-way: deployables, published artifacts, contracts): a Spark 4.2 uber jar; a Spark
  4.2 image and `jobs.yaml` entries; the jobs service's token key for the REST catalog; routing `variant` tables or a whole
  cluster to the Spark 4.2 jobs and then turning the `variant` property on by default (D48); job state and optimizer
  reporting for a Spark platform that cannot reach House Tables. They are N12 to N15 in `41-work-platforms-v3.md` and an
  entry in `58-waiting-on-mike.md`.
- Why two-way: nothing is deployed or published, and the Spark 3.5 jobs are shown unchanged by their suite.
- Status: implemented by pull request 135, merge commit `e2823b69` (`73-n11-jobs-spark42-result.md`), 2026-11-24. One detail
  was decided during implementation: the catalog is still built on every table load (241 config requests in one suite).
  It is left as is because changing it could not be shown to leave the Spark 3.5 path unchanged; it is work item N16.

## D51. The rest-shim path keeps refusing a gated replace at commit

- Date: 2026-11-25. The orchestrator's decision, a two-way door. Work item C3; found by pull request 146 (merge commit
  `22017134`).
- Decided: a gated replace-as-select on the rest-shim path is refused at commit, as on the plain REST paths. The shim is
  not changed to send the native stage-replace first, which would refuse before any write as the legacy path does.
- Why: (1) the paths the work is heading to, the plain Iceberg REST catalog on Spark 3.5 and Spark 4.2, cannot refuse
  earlier, because Iceberg's REST client first tells the server about a replace at commit, after Spark wrote the data;
  making only the intermediate shim match the legacy path buys nothing lasting. (2) The change would add an unreferenced
  metadata file to every allowed replace on that path: the legacy path writes two metadata files per allowed replace and
  the others one (scenario s32) [ran]. (3) What a refusal leaves is one data file under the table's location, invisible
  to SQL, and the orphan files job removes it at its age threshold.
- What would change it: a refusal-before-write requirement on the REST paths, or the Iceberg fork's Spark staged-table
  abort deleting what the failed replace wrote (an empty method in the fork's 1.12 code [read]), which would remove the
  leftover on every REST path. That is a change in the Iceberg fork and is open work item N18, not started.
- Reference: `80-c3-c4-result.md`, scenario s32, merge `22017134`.
