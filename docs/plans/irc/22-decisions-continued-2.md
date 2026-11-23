# Decision log, continued again

Continues `16-decisions-continued.md`.

Bottom line: eight more decisions (D22 to D29). Four were made after the IRC table write routes landed and the
capabilities design was written: two accept behavior as built, one adopts a design, and one is a temporary refusal.
D24 sets aside the one-way doors for Mike. D26 and D27 came after the red build and the view routes. D28 and D29 came
after the policy, capability and grants work landed. Each has its reason, the evidence it rests on, and how to reverse it.
None is agreed by Mike unless the entry cites a statement.

Dates are 2026-11-22 for D22 to D28 and 2026-11-23 for D29. Claude wrote every entry.

## D22. Behaviors of the IRC table write routes are accepted as built

- Date: 2026-11-22.
- Decision: the behaviors below of the IRC table write routes (pull request 94) are accepted.
  - DROP always deletes the data files, whatever the client's purge flag says, because the native drop does.
  - A replace is detected from the commit's shape. It overwrites a commit that landed between the client's load
    and the replace, which is Iceberg's replace semantics.
  - A staged create writes nothing.
  - The config route sends `io-impl=HadoopFileIO` and `rest-metrics-reporting-enabled=false` as defaults, each on
    evidence.
- Follow-ups, not blockers: derive the FileIO default from the cluster's storage configuration (item V3b), and
  put failed IRC requests in the service audit (item V3c).
- Why: the first follows the native drop, the second is Iceberg's own replace semantics, and the third and fourth
  were each shown necessary by a test or a log. The evidence for each is in the result file.
- References: `27-v3-irc-write-result.md`, "Replace detection rule", "Staged create" and "Choices where the brief
  left room".
- Reverse by: changing the IRC layer's handling, with no native change.

## D23. The capabilities design is adopted for work inside the forks

- Date: 2026-11-22.
- Decision: the design in `28-capabilities-over-irc-design.md` is adopted for work inside the forks.
  - Policies ride the standard IRC commit as the property `updated.openhouse.policy`, translated by the server.
  - Grants and unlock get OpenHouse routes under the IRC base, called by a small capability client that sits
    beside an unmodified `RESTCatalog`.
  - The server advertises `openhouse.capabilities` in the config response.
  - One resolver in the SQL extensions finds features by capability on all three catalog paths.
- Why: what users type in Spark SQL is unchanged, and no OpenHouse catalog class is needed in the engine. S16 says
  client features should be modeled as capabilities, S21 that they ideally compose with REST, and S22 and S23 that a
  Spark 4.2 target with IRC is valid.
- References: S16, S21, S22, S23; `28-capabilities-over-irc-design.md`; `29-capabilities-over-irc-work.md`.
- Reverse by: removing the routes and the translation.

## D24. The design's one-way doors stay with Mike

- Date: 2026-11-22.
- Decision: these one-way doors identified by the capabilities design stay with Mike before anything is released
  outside these forks.
  - Accepting `updated.openhouse.policy` and its JSON shape on a public IRC endpoint.
  - The config key `openhouse.capabilities` and the names `policies`, `grants` and `unlock`.
  - The paths and methods of the OpenHouse routes under the IRC base.
  - The capability module's API, if it is published as a library.
- Why: others can depend on these once they are public. Building them inside the forks is a two-way door, so work
  continues under D23.
- References: `28-capabilities-over-irc-design.md`, "One-way doors"; D12.
- Reverse by: not applicable until released. Mike decides.

## D25. Interim refusal of policy keys over IRC

- Date: 2026-11-22.
- Decision: until the policy translation lands (item V5-1), an IRC create or commit that sets or removes
  `updated.openhouse.policy` is refused with a 400 naming the key. Today it is silently stored as a stray property
  with no policy applied. In progress on branch `irc/v3-irc-write-gaps`.
- Why: a visible refusal is better than a success that did nothing.
- References: `28-capabilities-over-irc-design.md`, "The finding"; work item V5-0 in `04-work.md`.
- Reverse by: V5-1 replaces the refusal with the translation.

## D26. Additions to the testing policy after the red build

- Date: 2026-11-22.
- Decision: D18 stands, with these additions to every brief.
  - A change to `services/tables` main code includes `:tables-test-fixtures:tables-test-fixtures_2.12:test` (the
    Iceberg 1.2 fixture) and compiling the service under `-PicebergFork`.
  - A change to client or Spark extension code includes the Spark 3.1 itest tasks as well as the Spark 3.5 ones.
  - No IRC Spring bean references Iceberg types that the 1.2 line lacks. `IcebergRestBeanSignaturesTest` guards it.
- Why: the policy trades merge-time coverage for speed, and these are the cheap checks that cover the build
  variants a change can break. Skipping them left the default build red from 14f23870 until cc76d485.
- References: `21-findings-continued.md`; pull requests 96, 97 and 98.
- Reverse by: removing the lines from the brief template. The red build would then be possible again.

## D27. View rename over IRC answers 406

- Date: 2026-11-22.
- Decision: renaming a view over IRC answers 406, because the native view API has no rename. View routes register
  only where the Iceberg view API exists, so the Iceberg 1.2 line answers 406 for them.
- Why: there is no native path to build on, and 406 is the response the spec lists for this route.
- References: `31-v4-irc-views-result.md`, "What does not work, and why".
- Reverse by: adding a native view rename, then serving the IRC route.

## D28. The IRC view list answers an empty list where views are switched off

- Date: 2026-11-22.
- Decision: the IRC view list of a database whose views switch is off answers an empty list, where it answered the
  native 404 "Views are disabled". A missing database is still 404, and the native view list is unchanged.
- Why: once the server advertises view support, Spark 4.2 on Iceberg 1.12 also lists views for SHOW TABLES, and the
  404 failed SHOW TABLES in every database with views off, which is the default. This reverses part of what pull
  request 95 pinned.
- References: `36-v5-3-grants-unlock-endpoints-result.md`; pull request 102.
- Reverse by: restoring the 404 in the IRC view list.

## D29. Order of the remaining large work

- Date: 2026-11-23.
- Decision: the remaining large work goes in this order, by leverage.
  - First, measure the opt-in Iceberg 1.12 build against everything not yet run under it and against the full Spark
    SQL harness (item I6f, in progress).
  - Then make the 1.12 line the default build and remove the Iceberg 1.2 and Spark 3.1 projects (item V9, which
    follows D3).
  - Then format v3 tables and the v3 features.
  - In parallel, the remaining IRC areas (metrics, register, multi-table commit, scan planning, credentials) and
    Apache's compatibility kit as the proof of conformance.
- Why: every v3 item needs the service on 1.12, and the compatibility kit ships with the newer Iceberg line. This is an
  ordering of independent work items by dependency, not a schedule.
- References: `04-work.md` items I6f, V9, X1 and V13; D3.
- Reverse by: choosing a different order. Nothing is built on the order itself.
