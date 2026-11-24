# Decision log, continued a fifth time

Continues `51-decisions-continued-4.md`, which is full (its D41 carries a dated amendment).

Bottom line: three decisions. D43 (2026-11-23, with a dated addendum for the merge of pull request 124) records the format
v3 design choices and one data-safety finding. D44 and D45 (2026-11-24) record the first step of IRC scan planning and of
credentials: both are small, two-way doors, and what is left is Mike's. All are the orchestrator's. Nothing here is
agreed by Mike. Claude wrote the entries.

## D43. Format v3: how requests are accepted and refused

- Date: 2026-11-23. The orchestrator's decision, a two-way door, except that upgrading a real table stays Mike's
  (D12). Work items X1 and W6, branch `irc/x1-format-v3`, pull request 124 (not merged when this was written).
- Decision, in the order a request meets them:
  - With the cluster switch off, any request asking for format version 3 is refused with 400, on create and on
    upgrade, natively and over IRC.
  - A replace keeps the table's version. It honours an explicit 3 when the switch is on, and refuses a downgrade.
  - Registering v3 metadata, replicated tables at v3, v3-only types and encryption keys are refused. The message
    names the later work item that will handle them (X5 for types, X7 for replication).
  - A `write-default` below v3 is refused only when a commit introduces or changes it. A table that already carries
    one is not broken by unrelated commits.
  - The OpenHouse Java client sends `format-version` only when it commits v3 metadata over an absent or lower base.
- One native answer changes. A raw native create asking for format version 3 used to answer 201 and create a v2
  table. It now answers 400. Chosen because on LinkedIn's line the same Spark SQL statement fails, and silently
  creating v2 is neither the baseline nor honest.
- Finding that goes with it (data safety). On the Iceberg 1.12 line, before this work, a native table update with
  `format-version=3` upgraded a v2 table to v3 with no switch and no check. The update reaches Iceberg's property
  replacement, which accepts v3. It was reachable on the integration branch since the flip (pull request 118) and
  under the opt-in build before it. Nothing is deployed from the branch, so no table is known to be affected.
  Closing it is part of pull request 124, with a test that sweeps every write path. Recorded in
  `21-findings-continued.md`, thirteenth round.
- Why: a v3 table cannot go back and older readers cannot read it (D42). A refusal that names its reason is better
  than a quiet v2 table, and a v3 request that is accepted by accident is the worst case.
- Reverse by: each refusal is one check; removing it restores the earlier answer. A table that became v3 stays v3.
- References: D12, D42, `45-merged-pull-requests.md` for what is merged; the record of the work is
  `x1-format-v3.md` in the session's reports directory (outside the repository), with pushed head `5f7c4a44`.

### Addendum, 2026-11-23: the decisions recorded when pull request 124 merged

Pull request 124 merged into `irc-integration` as `e2e02bfa0d1b674cb774f591355e19c911fee46b`. The report's section 10
lists seven answers; sections 5 and 9 carry two more design choices. These nine are the orchestrator's and are two-way
doors, except the last, which is marked. Result: `57-x1-format-v3-result.md`.

1. A native table update asking for v3 with the switch off is refused with 400, which closes the unguarded upgrade.
2. A native create asking for v3 with the switch off is refused with 400, not made silently at v2.
3. A replace with `format-version` 3 on a v2 table is honoured when the switch is on. A replace keeps the table's own
   version. A replace naming 2 on a v3 table is refused.
4. Refused rather than lost: register of a v3 file, replica tables at v3, v3-only types and encryption.
5. A `write-default` below v3 is refused only when a commit introduces or changes it.
6. Defaults on a re-cased field are kept, in their own commit and test.
7. A test sweeps every write path with the switch off and asserts none leaves a table above the cluster's version.
8. The switch never makes a table v3 by itself, and `cluster.iceberg.format-version` stays 2. Turning the switch off
   again refuses new v3 creates and upgrades and leaves existing v3 tables working.
9. One-way door: upgrading a table to v3 is irreversible, and LinkedIn 1.5.2 clients cannot load it afterwards. The
   code allows it behind the switch, but turning the switch on for a real cluster and upgrading a real table stay
   Mike's (D12; `58-waiting-on-mike.md`). On the 1.5.2 reference line every v3 request is refused.

## D44. Scan planning: a synchronous plan route with no state

- Date: 2026-11-24. The orchestrator's decision, a two-way door. Work items V11a to V11c. Reasons and evidence in
  `60-v11-v12-investigation-result.md`.
- Decision: serve `POST .../plan` synchronously. It answers `completed` with every task in one response, keeps no
  state on the service, and runs the same checks as a table load. The service never sends `scan-planning-mode`, so no
  existing client's read path changes; only a client that itself asks for server planning is affected.
- Do not reuse Iceberg's `CatalogHandlers.planTableScan`. It keeps plans in a process-wide map that only cancel
  clears, and it cuts the file list to `min-rows-requested`, which Spark fills from LIMIT.
- Rejected: asynchronous planning with stored plan state. It is not known whether the service runs as several
  instances, and stored state would be wrong there.
- Why this is a two-way door: the route can be withdrawn from the endpoint list and nothing depends on it by default.
- Left for Mike: having the server turn server-side planning on for clients (V11d), because it changes every
  client's read path (`58-waiting-on-mike.md`).

## D45. Credentials: an empty list, no vending yet

- Date: 2026-11-24. The orchestrator's decision, a two-way door. Work item V12a. Reasons in
  `60-v11-v12-investigation-result.md`.
- Decision: serve `GET .../credentials` with an empty list after the load checks, and accept and ignore the
  access-delegation header. The specification allows the server to supply access "via any or none".
- Real vending is not started. The service holds no credential it can narrow to one table: it uses cluster-wide
  long-lived keys for S3 and ADLS and its own delegation token for HDFS. Vending needs a new external identity
  dependency and a new access contract, which are Mike's to decide (V12b to V12e).
- HDFS cannot be vended to an Iceberg 1.12 client at all, because its FileIO accepts no credentials.
- Reverse by: removing the route from the endpoint list restores the 406 answer.

## D46. Retention backup and deletes on merge-on-read tables: put to Mike, not decided

- Date: 2026-11-24. Put to Mike; no decision yet. Work items X8 and X9 (`41-work-platforms-v3.md`). Evidence in
  `63-x6-maintenance-v3-result.md`.
- Question: how should a retention backup treat deletes on a merge-on-read table?
- Facts, observed by running the tests of pull request 125: the backup holds the data files of the expired partitions
  and a manifest listing only data files. It therefore contains rows that were deleted before retention, with nothing
  recording the deletes. This is true on v2 today. On v3 the deletion vectors are not in the backup at all. No restore
  code exists in this repository; the history (pull requests 384, 408 and 418) names an external uploader that reads
  the manifests.
- Options: the author's recommendation is D, rewrite the expiring partitions with deletes applied before backing up.
  Its cost is an extra rewrite of those partitions and a behavior change on v2. The record of the work (`x6-maintenance-v3.md`)
  says the options go to the orchestrator in the author's final report and does not list A to C; they are not
  reproduced here and are to be added from that report.
- Why it is Mike's: it changes the behavior of a job on v2 tables and touches a contract with a system outside this
  repository (the external uploader).
- Until decided: nothing changes. The behavior is pinned by `retentionBackupOfPartitionsWithDeleteFiles`.
  The interim step X9 (log and count, no refusal) is a two-way door.
- Correction by the orchestrator: deletion vectors are the delete mechanism only for merge-on-read writes, and
  copy-on-write remains Iceberg's default, so format v3 does not widen which tables are exposed.
