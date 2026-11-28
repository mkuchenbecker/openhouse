# Decision log, continued a third time

Continues `22-decisions-continued-2.md`.

Bottom line: seven more decisions (D30 to D36), all dated 2026-11-23. D35 is a question put to Mike (the native directory-reuse fix) and D36 extends D30 to `openhouse.tableUUID`. D34 adopts the choices made in pull request 112
(metrics report and register table). D30 reverses part of D20 for creates after
Apache's compatibility kit showed the cost. D31 accepts two differences of Iceberg 1.12 and names one that is not
accepted. D32 is a question put to Mike, because it defines the goal; work proceeds on the first reading until he
answers. D33 tightens the testing rule after the opt-in build was found red in CI. Each has its reason, the evidence it rests on, and how to reverse it. None is agreed by Mike unless the
entry cites a statement. Claude wrote every entry.

## D30. A location named by an IRC client is ignored on create

- Date: 2026-11-23.
- Decision: on IRC create, staged create and replace, a location named by the client is ignored, and the server's
  location is used and returned. A commit that changes the location of an existing table or view is still refused.
  This replaces the refusal chosen in D20 for creates. Carried out 2026-11-23 by pull request 106 (merge commit
  `bfdcf266e6f1`, `42-v13b-spec-deviations-result.md`).
- Why: Apache's compatibility kit has a switch for servers that own locations and expects them to override, which
  moves 13 kit tests. The response tells the client the real location, so no client is misled.
- References: `39-v13-compat-kit-result.md` (location group, work item 3); D20 in `16-decisions-continued.md`.
- Reverse by: restoring the 400.

## D31. Differences of Iceberg 1.12 accepted, and one not accepted

- Date: 2026-11-23.
- Decision: accepted as the new line's behavior on Iceberg 1.12:
  - `ALTER TABLE ... ADD COLUMN ... DEFAULT` is refused, where LinkedIn's line accepted the statement and silently
    dropped the default. This is consistent with D5 and D11: a refusal is better than a silent no-op.
  - After repeated DELETEs on one data file there is one live position delete file, where LinkedIn's line has
    three, because 1.12 replaces earlier delete files. The rows returned are the same.
- NOT accepted: an explicit time-travel read fails while a write-audit-publish branch is set in the session. That
  was fixed on 2026-11-23 in the Iceberg fork by pull request 12, merged into `openhouse-1.12.0` as `a88f556c`
  (item I11). It was fixed on Spark 3.5; Spark 4.2 needed no change. OpenHouse still pins the older fork commit until V9a.
- Why: the first two are upstream behavior with no loss of data or of rows, and they keep the line free of fork
  patches. The third makes a working read fail for a user who stages writes.
- References: `40-i6f-full-regression-result.md`, "The delta-harness on 1.12" and "The three failures that were
  fixed"; D5, D11.
- Reverse by: patching the fork to accept the default statement, or to keep one delete file per commit.

## D32. What "conforming Iceberg REST Catalog server" means is put to Mike

- Date: 2026-11-23. Question asked 2026-11-23. Not decided.
- Question: does conforming mean meeting the specification while keeping OpenHouse's own rules, or passing
  Apache's compatibility kit by relaxing those rules over IRC?
- Working reading until he answers: the first. Spec-level deviations are fixed, routes not yet served are added,
  and OpenHouse's rules are not changed. Those rules are replace gating, no partition evolution, no column drop or
  rename, the Spark-only view dialect, databases that exist only while they hold tables or views, and the
  cluster-fixed format version.
- Why Claude did not decide it: it defines the goal, and the second reading changes rules that LinkedIn's
  deployment relies on.
- References: `39-v13-compat-kit-result.md` (31 of 160 kit tests pass; 84 of the rest are OpenHouse rules that the
  specification permits).
- Reverse by: Mike's answer. If it is the second reading, the class b work items in that file become the plan.

- Note, 2026-11-27: in the working reading, the item "databases that exist only while they hold tables or views" as an OpenHouse rule is withdrawn by D55 in `90-decisions-continued-7.md`; the rest stands.

## D33. A suite passes under `-PicebergFork` only if it ran there, and pull request CI runs the opt-in job

- Date: 2026-11-23. The orchestrator's decision, a two-way door.
- Decision:
  - A suite counts as passing under `-PicebergFork` only when it ran there. "Compiled only" is reported as not
    tested. Any suite a change touches is run on the default build and under the switch before merge. This tightens
    D26.
  - Pull request CI will run the opt-in job as well as the default build, through one reusable workflow called from
    both `post-merge-build.yml` and `pr-validations.yml`. Not yet done: it is work item C6, to start after V9a
    merges, because V9a touches the same workflow files.
  - Post-merge CI results are read after every merge, not assumed.
- Why: the opt-in job was red for five post-merge runs because only post-merge runs covered the switch, and
  nobody read the result. The measured cost is 11.5 to 18 minutes per push, beside a 24 to 34 minute default job,
  so wall-clock time does not grow.
- References: `43-optin-ci-red-result.md`; D18 and D26.
- Reverse by: removing the second call of the workflow, and going back to local runs under the switch.

## D34. The choices made in pull request 112 are adopted

- Date: 2026-11-23. The orchestrator's decision, a two-way door except for the last point.
- Decision:
  - Metrics: a report is counted and logged, never stored, which matches today, because natively a report never
    reaches the service. Authorization is the read privilege on the house-table row only. A report for a missing
    table is 404, as the specification lists.
  - The config route still sends `rest-metrics-reporting-enabled=false`, so clients report only if they opt in. With
    it on, each report costs a house-table lookup and an authorization decision, reports were 22 of 189 requests in
    a Spark 4.2 run, and clients log warnings for reports that race a create or a drop.
  - Register is a native create read from the metadata file, never a pointer to the caller's file. It is allowed only
    for a file directly in the directory the server allocates for that name, database and table UUID, and only when no
    live or soft-deleted row uses that directory. Metadata a create cannot store exactly is refused (same principle
    as D19). A taken name is 409 whatever the overwrite flag says. Not kept: the Iceberg table UUID, metadata log,
    snapshot log.
  - The kit's drop-then-register test cannot pass, because OpenHouse's drop deletes the table's files. That is an
    OpenHouse rule, kit group DROPPURGE.
  - One-way door, waiting on Mike at release and not blocking: the register and metrics route semantics become a
    public contract at release, like the items in D24.
- Why: see the reasons above; the evidence is in `46-v6-v7-metrics-register-result.md`.
- References: pull request 112, merge commit `8876a7f295aaca02b49baa3e306fe44774ccc1f9`; D19, D24; D30.
- Reverse by: deleting one default line turns reporting on. Register's rules are loosened in their own item.

## D35. Whether to fix the native directory-reuse defect is put to Mike

- Date: 2026-11-23. Question asked 2026-11-23. Not decided. Not blocking. It changes native behavior, so it is a
  door only Mike opens.
- Question: should the native create refuse a UUID that a live or soft-deleted row of the same database already
  carries? Today four native requests answer 201 and create a second table in a renamed table's directory;
  dropping the second deletes the first's files, and every read of the first answers 500 (`48-v16-directory-reuse-result.md`).
- Proposed smallest fix: `TableUUIDGenerator` refuses such a UUID with the existing 400. About 20 lines and one
  injected repository.
- Effects: (a) the four requests go from 201 to 400. (b) Every create that names a UUID or carries data (every
  CTAS, every replica create) makes one more House Tables listing, because House Tables has no lookup by UUID.
  (c) Re-creating a replica under its old name while a renamed table with that UUID exists is refused. Nothing
  else changes. The larger alternative is making drop refuse to purge a directory another row points into.
- Until he answers: the defect is pinned by five tests (D9) and the IRC half is closed (D36).
- References: pull request 116; `21-findings-continued.md`, eleventh round.
- Reverse by: not applicable until decided; if made, remove the check.

## D36. On IRC, `openhouse.tableUUID` in a create's properties is ignored

- Date: 2026-11-23. The orchestrator's decision, a two-way door. Extends D30.
- Decision: an IRC create and staged create ignore `openhouse.tableUUID` in their properties, as they ignore a
  requested location. The table is created in a directory of its own under a new UUID.
- Accepted IRC-only differences: such a create with incomplete companion properties used to get the native 400
  and is now created under a new UUID; a denied principal whose completion also breaks a native validation rule
  now gets 403 where it got that rule's 400.
- Why: the property was one of three IRC ways to put a second table in a live table's directory.
- References: pull request 116 (merge commit `0a3bf5292fd861405d416b26227d1f504d2a1288`); D30; D35.
- Reverse by: passing the property on to the native create again, which reopens the defect of D35.
