# Decision log, continued

Continues `13-decisions.md`.

Bottom line: nine more decisions (D13 to D21) Claude made on two-way doors, forced by the opt-in build on the
fork's Iceberg 1.12 line, by the plan for service-side routes, by the owner's testing statement, or by the commit
core. Each has its reason, the evidence it rests on,
and how to reverse it. None is agreed by Mike unless the entry cites a statement.

Dates are all 2026-11-22. Claude wrote every entry.

## D13. A placeholder location on create

- Date: 2026-11-22.
- Decision: on create, when the caller gives no location, the client builds local metadata with the
  placeholder location `openhouse:/location-assigned-by-service`.
- Why: Iceberg 1.12 rejects table metadata without a location. The client has never sent a location and
  the server assigns one. Learning the server's location first would need an extra staged-create call that
  writes a metadata file. A test shows the create request body is byte-identical with and without the
  placeholder and between the default and opt-in builds. No file system handles that scheme, so accidental
  use fails loudly.
- References: `15-optin-build-result.md`; test `OpenHouseCatalogCreateTableTest`; openhouse pull request 87.
- Reverse by: removing the placeholder once the client creates tables through the REST path.

## D14. Two Iceberg 1.12 behavior differences are accepted

- Date: 2026-11-22.
- Decision: two behavior differences of the Iceberg 1.12 line are accepted as the new line's behavior, and
  the tests that assert the old behavior get expectations per Iceberg line.
  - (a) An RTAS that drops a column used by an old partition spec succeeds on 1.12 and fails on LinkedIn's
    1.5.2. Apache fixed this in pull request 11868, and undoing an upstream fix in the fork would cost more
    than the difference.
  - (b) `CREATE NAMESPACE` still fails as unsupported, with a different message, because Iceberg's Spark
    catalog now calls `namespaceExists` (Apache pull request 14507).
- Why: a failure becoming a success, and an error message changing, are both two-way doors.
- References: `15-optin-build-result.md`, "The 10 remaining failures, by class"; work item I6h in
  `04-work.md`.
- Reverse by: patching the fork.

## D15. The 1.2 and Spark 3.1 projects are excluded under the opt-in switch

- Date: 2026-11-22.
- Decision: under the opt-in switch the Iceberg 1.2 / Spark 3.1 projects are excluded from the build rather
  than left as a hybrid of 1.2 and 1.12 artifacts. This follows D3.
- Why: the single-copy server modules compile against 1.12 and the 1.2 consumers embed them, so the 1.2
  line cannot be a faithful build under the switch.
- References: D3; `15-optin-build-result.md`, "The 1.2 and Spark 3.1 line under the switch"; work item
  I6g in `04-work.md`.
- Reverse by: giving that line pinned copies of the shared modules.

## D16. Service-side IRC routes live in the existing tables service

- Date: 2026-11-22.
- Decision: service-side IRC routes are mounted in the existing tables service under the base path
  `/iceberg`. They use the service's current Iceberg line's REST classes and serializers on a JSON mapper
  private to those routes. They go through the same service-layer calls as the native API, so
  authentication, authorization and capability gating are the same.
- Why: this keeps the native API byte-for-byte unchanged and avoids a second deployment.
- References: `07-seam-design.md`; work items V2 and V3 in `04-work.md`.
- Reverse by: moving the routes.

## D17. Briefing habits adopted from the iteration-friction diagnosis

- Date: 2026-11-22.
- Decision: briefs follow these habits.
  - Every Gradle command in a brief carries `-Dorg.gradle.daemon.idletimeout=60000`.
  - The inner loop runs only the touched test classes, and each full suite runs once at the end.
  - Concurrency is budgeted by memory (never two Iceberg Spark runs together), not by a count of two.
  - Dropping the 4 GB daemon heap override for OpenHouse is measured on the next job before it is adopted.
- Why: the shared memory limit was saturated, with 10 out-of-memory kills and about 4.8 GB held by idle
  daemons. Not adopted, because they change a repository or the environment and are Mike's to decide: more
  memory, a CI cache for pull requests, and editing the ten sleeping tests.
- References: `20-iteration-friction.md`; `18-state.md`, "Waiting on Mike".
- Reverse by: removing the flag from briefs.

## D18. Testing policy: selective before merge, full build after

- Date: 2026-11-22.
- Decision: before a merge an agent runs the test classes it touched plus the black-box suite of the module it
  changed. Claude merges on that without waiting for the pull request's CI. The full build runs on
  `irc-integration` after each merge (workflow `post-merge-build.yml`, pull request 90) and a failure is fixed
  forward. Heavy runs (full delta-harness, full Iceberg Spark suites) run after merges when memory is free.
  Exception: an item whose claim is "no behavior change" runs the full suite before merge.
- Why: S50 says selective testing matters when the build cycle is long, and the full suite is only needed on merge,
  asynchronously. Consequence: work that starts from `irc-integration` must check `18-state.md` for a known-red
  list.
- References: S50; `20-iteration-friction.md`; openhouse pull request 90; first post-merge run 45055600595 passed
  on b78743fe.
- Reverse by: waiting for pull request CI again.

## D19. The candidate entry point refuses changes the update path never stores

- Date: 2026-11-22.
- Decision: the candidate entry point of the commit core refuses, with a 400 naming the field, a candidate that
  changes something the update path never stores: location, UUID, format version, statistics files, removal of
  every snapshot, or a partition spec the native API cannot express.
- Why: those changes would otherwise be silently dropped. Only the new entry point can reach the refusal, so native
  requests are unaffected.
- References: `23-v1-commit-core-result.md`; openhouse pull request 89.
- Reverse by: changing the refusal in the candidate conversion only.

## D20. Rules for the IRC write routes (work item V3)

- Date: 2026-11-22.
- Decision: requirements are checked by the IRC layer against the loaded base, and a failed requirement is 409
  `CommitFailedException`. The server owns location, UUID and format version and refuses a request that sets them.
  A replace is detected from the commit's shape, as the client seam does, and handed to the core as a replace
  intent. Namespace create, update and drop stay unsupported, as natively. Policies, grants and unlock over IRC
  wait for V5.
- Why: the native rules must hold identically over IRC.
- References: `23-v1-commit-core-result.md`, "What V3 can rely on"; `04-work.md`, V3 and V5.
- Reverse by: changing the mapping in the IRC layer only.

## D21. A bigger container is not available; separate machines are Mike's call

- Date: 2026-11-22.
- Decision: a bigger container is not available from the settings Claude can read (network, variables and setup
  script only). The alternative is sibling cloud sessions on separate machines. None has been started, and starting
  them is Mike's call because of the token cost.
- Why: S49 says Mike is fine with a bigger container if one is available, and the diagnosis found memory to be the
  limit.
- References: S49; `20-iteration-friction.md`; `18-state.md`, "Waiting on Mike".
- Reverse by: Mike asking for worker sessions.
