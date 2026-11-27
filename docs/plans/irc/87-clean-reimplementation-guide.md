# Guide for the clean re-implementation

Bottom line: the owner expects the work to be re-implemented once it is proven, because 71 linear merges "will read
like slop" (S58). This guide merges the nine reviewers' section 7 into one plan. Most of the design survives: one
commit core for native and Iceberg REST writes, Iceberg REST routes as thin adapters over the native handlers, a
capability vocabulary, one format-version rule, a stateless plan route, and a shared Spark SQL scenario list with
declared differences. What must not survive is the way it was built: a default build flipped to an Iceberg nobody
hosts, a reference line that is not LinkedIn's build, three copies of the Spark SQL extensions, two House Tables
stand-ins, private ids in messages, and baselines edited in place. The proposed upstream sequence below has 48 commits:
evidence first, then behavior-preserving refactors on LinkedIn's own line, then fixes of main's defects each with its
own justification, then new surface that is strictly additive and off by default, and the move to Iceberg 1.12 as its
own explicit change near the end, with the owner's sign-off.

Sources: `records/review-*.md`, section 7 of each (reviewer ids as in `85-behavior-change-ledger.md`). Ledger rows are
`BC-NNN`; findings are `RF-NNN` and work items `FF-NN` in `86-review-findings-and-fix-forward.md`.

## 1. Rules for the whole re-implementation

Bottom line: every commit is one idea, reviewable alone, and says whether it changes behavior; nothing changes
LinkedIn's line by accident.

- Behavior-preserving steps come before behavior-adding ones; a fix of a defect on main is its own commit with its
  justification in the message and in the code.
- New routes, new modules and format v3 are strictly additive on LinkedIn's own line: off by default, and with the
  switch off the answers are main's, proven against a recorded baseline of `bb8929e1` (U5 to U7 below).
- The move to Iceberg 1.12 (and Java 17) is its own, separately justified change, never a side effect of another commit.
- No private ids (work items, decisions, pull request numbers) in code, comments, messages or build files; the reason
  goes in the comment and the history goes in the commit message.
- No switch that guards nothing, no test seam in main sources, no reflection to straddle two Iceberg lines unless a
  second line still ships.

## 2. Per area: what must survive, the tests that are the specification, what must not be carried over

### Build and the Iceberg lines (BD)

Must survive:
- One property, read once (in `settings.gradle`, or one version catalog per line), decides the Iceberg line; no module
  tests the property itself.
- The fork's Iceberg comes from a hosted repository under a group LinkedIn controls, at an immutable release-like
  version; a local source build stays only as a developer override, pinned by full commit, verified after checkout,
  with the wrapper jar verified, in an `exclusiveContent` repository.
- The Java target follows the Iceberg line (17 with 1.12), stated once, and stated as a compatibility break for client
  jars.
- Root forces per line in the version catalog, not as `if` branches in `allprojects`.
- Single `src/main` and `src/test` per project; no `src/shared`, no `iceberg-version-specific`.
- Uber jars exclude `META-INF/versions/21+` (or the shadow plugin is upgraded), in one convention plugin.
- The Spark image takes Spark from Apache's image and the Iceberg runtime from the build, with one builder stage; the
  scheduler image passes its own log configuration and the apps jar carries none.
- The release publishes only from a line whose dependencies consumers can resolve, and tags after a successful
  publish.
- The OpenAPI generator CLI as a Gradle dependency (123) and UTF-8 source encoding (136) stay as they are.

Tests that are the specification: a dependency, POM and uber-jar entry comparison of each published module against
`bb8929e1` on LinkedIn's line (FF-23); a release dry run.

Must not be carried over: `-PicebergFork` and `HARNESS_ICEBERG_FORK`; the docker and publish refusals once artifacts
are hosted; `@ConditionalOnClass` inside line-specific roots; the 1.2 arm of `IcebergLine`; `apps/spark-4.2`
compiling another project's directories by path; duplicated pin parsing, marker checks and `--add-opens` lists; the
unused `default` builder stage; one 461-line `docs/development/iceberg-fork.md` mixing build, CI and product behavior.

### Test evidence, fixtures and CI (CF)

Must survive:
- A Spark SQL scenario list that depends only on `spark.sql` and JUnit, one source shared by every engine line.
- A per-path `Difference` declaration that quotes the baseline, must really differ, and must be consumed; plus a stored
  baseline file recorded from LinkedIn main, compared on every build of LinkedIn's line, and the per-statement outcomes
  TSV committed as that baseline.
- Black-box commit-path tests at the HTTP boundary (T4), with pinned defects labelled; the catalog-plugin contract suite
  through public Iceberg interfaces (T3).
- Apache's REST compatibility kit with a ratchet on name, outcome and cause (first failing assertion type and message
  prefix), a passing list, and runs at the default configuration and with v3 on.
- One House Tables stand-in with the real DDL lengths, case-insensitive keys, compare-and-set, 404 and 409 parity, and a
  parity test against the real House Tables service.
- A grant-storing test authorization backend, kept out of production source sets.
- CI split inside Gradle (an init script with `only` and `except`), the partition checked by a dry run in CI, one
  composite action for the Docker steps, tee under `pipefail` and a failed-test printer, a post-merge run per commit,
  one cache writer per line, and an aggregating job that keeps LinkedIn's check name.

Must not be carried over: baselines edited in place; `IcebergLine` branches in test bodies; free-text difference kinds
with no review; two stand-ins, two read-after-commit reproduction tests, two `ServiceWarehouse` classes; a JVM-global
`SAVE_LOCK` and a static soft-deleted map in a published fixture; `@PropertySource` for a setting that must hold;
catch-any-exception assumptions; dated timing narratives in workflow comments.

### Commit core and Iceberg REST table writes (CW, RR)

Must survive:
- One service-layer commit core for an existing table, called by the native routes and the IRC route, in the native
  order: lock flip (PUT only), legacy lock, write privilege, SYSTEM_ONLY, no-op, capability gate, lock preservation,
  strip, save.
- An IRC commit built server-side: load the base, authorize immediately (before requirements, updates or validation,
  including for a no-op commit), check requirements against the base, apply updates with Iceberg's
  `TableMetadata.Builder`, commit with the loaded base location; the repository's `versionCheck` and the House Tables
  compare-and-set are the real compare-and-set.
- The candidate spelled as the native request the Java client would send, validated by the native validator, decoded
  by the native mapper; refuse, never drop, what the update path does not store (location, UUID, format version except
  the allowed upgrade, statistics, encryption, removing every snapshot, an inexpressible spec).
- Create and staged create spelled as the native create; staged create writes nothing; the completion verifies the
  location is the one the server allocates, that snapshots lie in that directory, and that no live or soft-deleted row
  owns it (by location; read soft-deleted rows first).
- Create history kept only after checking the final parts equal the create's, passed explicitly.
- Rename and drop delegate to native, with IRC status mapping; the view occupant is checked before any write.
- A multi-table transaction refused on shape; a one-table transaction is the ordinary commit.
- Error mapping: 409 `CommitFailedException` for conflicts, 500 `CommitStateUnknownException` for unknown state, no
  stack traces.

Tests that are the specification: `IrcWriteRoutesAuthTest` with a denied no-op commit; an order test for lock,
privilege and capability on the native PUT; `StoredMetadataGoldenTest`; the directory-reuse tests on a stand-in that
stores no UUID.

Must not be carried over: the `ThreadLocal` plus transient-property channel for create history
(`CreateHistoryScope`); static create-path helpers in the update core; the dead overload and double memoization; one
1,100-line handler; the UUID half of the directory check; two `<table>-<uuid>` parsers; the IRC handler's own
format-version checks beside the rule.

### Iceberg REST read, config, register and metrics (RR)

Must survive:
- Routes under `/iceberg`, hidden from springdoc, with their own `ObjectMapper` and exception advice scoped to the IRC
  package, so native JSON and error bodies are untouched; one switch for the whole base, off by default upstream.
- Token interceptor reuse through a wrapper that only rewrites the refusal body.
- Load equal to native `getTable` checks in native order from one catalog load; metadata served as stored.
- Namespaces are databases, one level; existence a one-row table probe then the database list, both with one case
  rule; list and exists without authorization, as natively; paging accepted and ignored.
- The endpoint list derived from Spring handler mappings.
- Config: `rest-metrics-reporting-enabled=false` and `io-impl` by default storage as defaults, capabilities as an
  override.
- Metrics authorized on the row, counted by type, body excluded from audit payload capture.
- Register as a native create read from the file, with the directory rules above and the privilege checked before any
  row or file is read.
- A House Tables row-width check before the metadata write, off by default.
- Policy tags in declaration order on write; deterministic OpenAPI output from one shared customiser.

Must not be carried over: `required=false` defaults and field injection of limits; static `requireTableIdFits` and
test-only constructors; the duplicated customiser; `@ApiResponses` used as an unnamed audit trigger.

### Views and the OpenHouse capability routes (VC)

Must survive:
- IRC view routes as adapters over the native view handler and service; one `loadView` on `ViewsService` returning
  pointer plus metadata, with an explicit read privilege for the IRC body (the privilege waits on the scout, see
  `86-...` section 3b).
- Commit spelled as a native PUT of the complete definition, authorized before anything is applied; server-owned
  location, UUID and format refused or ignored; system properties frozen by the engine.
- Error translation by native error code to Iceberg exception types with the native message; views-disabled answers
  404 on a view's routes and an empty list on list (needed by Spark 4.2 SHOW TABLES).
- The policy patch applied server-side, merged onto policies read under the commit's own base, validated inside the
  audited commit, mapped after lock and privilege checks.
- Grants and unlock as POST and DELETE adapters under `.../openhouse/...`, calling the native handler methods unchanged.
- `endpoints` and `openhouse.capabilities` derived from registered mappings, with a documented versioning rule for
  capability names.

Must not be carried over: the static-helper split justified by a deleted test; the concrete `ViewsServiceImpl`
dependency; a test-side copy of the client merge; the compile-only dependency of the service on a client integration
module; the "reads make no decision" pin; a second hand-written copy of the view-create pre-write checks in
register-view.

### Client seam, capabilities and the Spark extensions (CS)

Must survive:
- A capability vocabulary in a plain-Java module: server capabilities `policies`, `grants`, `unlock` advertised as
  `openhouse.capabilities`; client capability `column-defaults` in `X-OpenHouse-Client-Capabilities`.
- One resolver per Spark line, using `HasIcebergCatalog.icebergCatalog()` plus one `CachingCatalog` unwrap, in the order:
  catalogs implementing OpenHouse interfaces, then Iceberg `RESTCatalog` by advertised names, then other Iceberg
  catalogs; non-Iceberg catalogs keep their old policy behavior.
- `GrantsCapability` and `UnlockCapability` with plain value types, used by the Java client too.
- A small REST capability client for grants and lock routes that reuses the catalog's properties, HTTP client and auth
  session, with #103's error mapping.
- Policy statements refusing on a REST server not advertising `policies`, before writing anything.
- The rename-target rule for OpenHouse-over-REST catalogs with the legacy messages.
- Spark 4.2 parser additions (`parsePlanWithParameters`, `parseRoutineParam`).
- The in-process seam, if kept, in a clearly test-named module, not in the shipped runtime jar.

Must not be carried over: the third copy of the extensions; the Java-client and capability-type round trip;
`IcebergCatalogMapper`; the Scala `Principal`; `CONFIG_OVERRIDES`; duplicate classpath tests; five Spark 4.2 test-service
fixtures where one would do.

### Format v3 and native column defaults (FV)

Must survive:
- One `FormatVersionRule` bean: create stores the cluster's version unless the request asks for 3 and v3 is enabled; an
  update may only raise to 3 when enabled; never lower; one invariant in the pre-commit hook.
- The switch off by default, bounding `cluster.iceberg.format-version` too.
- The upgrade path is Iceberg's own, on both APIs; replace keeps the stored version unless it explicitly asks for 3;
  rebuild-from-empty creates at the metadata's own version (on LinkedIn's line, as main did).
- On v3: native `initial-default` and `write-default` stored as committed, `initial-default` immutable modulo
  promotion; below v3 a new `write-default` refused only on a line that can write v3.
- Read-bridge opt-in refused on v3 and its config not sent for v3.
- Per-type decisions in the pre-commit hook; below v3 one 400 for every v3-only type.
- `variant` behind its own switch, refused with ORC data files, ORC write format and retention; replication and
  encryption refused on v3 until supported.
- An upgrade logged, counted and visible on GET; the Java client sends `format-version` only when a commit raises to 3.

Must not be carried over: private ids in messages; the IRC handler's parallel checks; ad hoc `new FormatVersionRule`;
`refuseVariantBelowV3`'s reproduction of an accidental message; `IcebergV3.nextRowId` in main; the duplicated line
probe; four copies of the strip-skip block; pins of 500 answers as "before".

### Scan planning (SP)

Must survive:
- One stateless synchronous `POST .../plan` answering `completed` with inline tasks and delete files, serialized by
  Iceberg's parsers; no stored plans, no fetch, tasks or cancel routes listed.
- Request mapping: incremental start exclusive and end inclusive; `snapshot-id` with `use-snapshot-schema=true` is
  `useSnapshot`, with false the table schema (by `useRef`, or a projection of `table.schema()`); `select`, `filter`,
  `case-sensitive`, `stats-fields` passed through; `min-rows-requested` ignored.
- The late-column rule for Spark 4.2's pinned reads.
- Refuse format v3 and above until scan tasks carry sequence numbers.
- A task cap checked while streaming, a time budget and a concurrency limit, answered `failed`; an on/off property, off
  by default; a decided read privilege for manifest contents; an audit event.
- Never send `scan-planning-mode`; `GET .../credentials` answering an empty list after the load checks.
- Parity tests: client planning against server planning on real `RESTCatalog`s, and Spark DML parity on v2.

Must not be carried over: `@ConditionalOnClass` on a class that only compiles where the class exists; the `@Value`
property in a controller; the cost harness in the unit suite; three layers of Spark read parity tests; the false audit
javadoc.

### Maintenance jobs (JB)

Must survive:
- One catalog per `Operations` instance, lazily built, thread-safe, closed with the instance, tested with a counting
  test catalog.
- A catalog built from `type` as well as `catalog-impl`.
- The SYSTEM declaration via `header.<name>` only for a `RESTCatalog`.
- Partition stats labelled by the partition struct's own names, without a WARN per value.
- The sort stats temporary table inheriting a v3 source's format version and write format; v2 statement unchanged.
- A retention signal for delete files in backups (metric and one log line) until D46 is decided, with exact counts.
- The concurrency harness (a commit hook in a test-only catalog) and the per-shape job tests on v2 and v3.
- One source tree for the jobs, compiled by a Spark 4.2 test project if wanted.

Must not be carried over: the reflective parser walk; `IcebergLine` reflection in `TablesClientFactory` (decide the
scheduler's capabilities by configuration); test-only counters in `Operations`; the `JobsLine.restCatalog` duplicate;
the `String.join` relocation dodge; `src/shared`; same-named per-line test classes chosen by source-set excludes.

## 3. Where reviewers proposed conflicting structures, and the choice

Bottom line: four real conflicts; each is settled here with a reason.

1. Sharing code between the Spark 3.5 and Spark 4.2 lines. CS proposes one shared source directory compiled by both
   Spark modules with per-line overlays; BD proposes making the shared job code a library project both apps projects
   depend on, and objects to one project compiling another's directories. Choice: a shared source directory that
   belongs to no project (for example `integrations/spark/common-src/` and `apps/common-src/`), compiled by each
   Spark-line module, with per-line differences behind an interface implemented in each module's own `src/main`. Reason:
   Spark 3.5 is Scala 2.12 and Spark 4.2 is Scala 2.13 against different Spark APIs, so one library jar cannot serve
   both; but BD's objection stands against one project reaching into another's tree and replacing files by path
   exclusion, which this layout avoids.
2. One switch or several for the Iceberg REST surface. RR and VC ask for one switch for the whole base; SP asks for a
   planning switch. Choice: both: `cluster.tables.irc.enabled` for the base, and a planning property under it, each off
   by default upstream. Reason: planning has its own risks (RF-022, RF-024) that a deployment may want off while the
   base is on.
3. The House Tables row-width check. RR proposes checking creates and renames only, or every commit only when a width
   is configured; the orchestrator decided off by default with a non-positive value meaning no check. Choice: off by
   default; when configured, every write is checked (the code today). Reason: the orchestrator's decision restores main
   and keeps the fix available to a production-DDL deployment.
4. Where the policy-patch merge lives. VC proposes moving the merge into a module both the Java client and the server use;
   CS's capability module holds names and interfaces only. Choice: put the merge rules in the capabilities module as plain
   Java, used by the Java client and by the service. Reason: three copies (client, `PolicyPatch`, the test's
   `ClientPolicyMerge`) become one, and the module is already plain Java with no Iceberg line in it.

The commit sequences themselves agree with each other: every reviewer puts the default-line change last and asks for it
to be justified separately (BD 11, CS 8, FV 10, CF 11).

## 4. One proposed upstream commit sequence

Bottom line: 48 commits, numbered U1 to U48. "Depends" names what must land first. Each says whether it changes
behavior on LinkedIn's line; every commit up to U27 is on LinkedIn's line with Iceberg 1.5.2 as the default.

### Evidence first (tests and CI only; no product change)

| Commit | What | Depends | Behavior on LinkedIn's line |
|---|---|---|---|
| U1 | CI: tee Gradle output under `pipefail`; the failed-test printer with its self-test | none | none |
| U2 | CI: the Docker steps of `build-run-tests.yml` extracted into a composite action, unchanged | none | none |
| U3 | build: UTF-8 source encoding (136 as is) | none | none, except on a non-UTF-8 locale (a fix) |
| U4 | build: the OpenAPI generator CLI as a Gradle dependency (123), with a check that the generated client is byte-identical | none | none |
| U5 | tests: the shared smoke scenario list and the legacy runner, with the outcomes TSV of main committed as the baseline and compared on every build | none | none |
| U6 | tests: the catalog-plugin contract suite (T3) | U5 | none |
| U7 | tests: the commit-path suite (T4), pinned defects labelled | none | none |
| U8 | fixture: one House Tables stand-in with the real DDL, compare-and-set, 404 and 409 parity, per-context state, H2 result reuse off where it cannot be overridden, and a parity test against `services/housetables`; release note for fixture consumers | none | fixture semantics for consumers (fixes) |
| U9 | tests: the grant-storing test authorization backend and s17's grant listing | U5 | none |
| U10 | CI: the split build (init script, matrix, partition check in `plan`), with an aggregating job keeping "Build and Run Tests" | U1, U2 | none (check name kept) |

### Behavior-preserving refactors on LinkedIn's line

| Commit | What | Depends | Behavior |
|---|---|---|---|
| U11 | build: the Spark 3.5 apps, Java client, itest and fixtures get their own `src/main` and `src/test` (pure `git mv`; the 1.2 / 3.1 projects read the new place while they exist) | none | none |
| U12 | service: extract the native update rules into a `TableCommitCore` bean, with an order test for lock, privilege and capability | U7 | none |
| U13 | service: a repository error-mapping helper for the create and update paths | U12 | none |
| U14 | service: `TablesServiceImpl.getTable` extracts `checkLoad`; `findWithMetadataById` (with a default) and `loadTable`; `TableDtoWithMetadata` carries the `Table` | U12 | none |
| U15 | service: `ServiceAuditAspect` builder split; `OpenHouseExceptionHandler.unreadableMessage`; `ViewsService.loadView` with `getView` delegating | none | none |
| U16 | service: `FormatVersionRule` as a bean used by today's create path with today's behavior; the pre-commit hook gets the table `FileIO` | U12 | none |
| U17 | client: the capabilities module (names, interfaces, value types, the policy merge rules) with tests and no callers | none | none |
| U18 | client: the Java client implements the capability interfaces with its types re-pointed; request bodies and headers pinned | U17 | none |
| U19 | Spark 3.5: the resolver for grants, SHOW GRANTS and unlock; `IcebergCatalogMapper` deleted; messages pinned | U18 | none |
| U20 | Spark 3.5: policy statements consult the resolver and refuse only on a `RESTCatalog` that does not advertise `policies`; non-Iceberg catalogs unchanged | U19 | none on LinkedIn's line (no REST catalog there) |
| U21 | jobs: one catalog per `Operations` instance, closed on close, counted by a test catalog | U11 | one connection pool per run instead of per load (a fix, stated as such) |

### Fixes of defects on main, each its own justified change

| Commit | What | Depends | Behavior |
|---|---|---|---|
| U22 | fix: policy tags in declaration order on write; a reorder is not a change | U5 | yes, justified: main's order was per-process |
| U23 | fix: deterministic `Page` schema order from one customiser in `services/common` | none | build output order (a fix) |
| U24 | fix: jobs build a catalog from `type`; partition stats labelled by the struct's names; the retention backup signal for delete files with exact-count tests | U21 | yes, each justified (a failing configuration; a mislabel; a silent data defect, D46) |
| U25 | fix: the Spark 3.5 itest stops excluding its own `*Spark3_5` tests | U11 | tests only |
| U26 | feat, off by default: the House Tables row-width check (`cluster.housetables.metadata-location-max-length`, `table-id-max-length`, 0 = no check) | U12 | none with the default |
| U27 | (owner's call, D35) fix: the native directory-reuse defect, with the pinned test flipped | U7 | yes, a data-loss fix with its own justification |

### LinkedIn-line build changes that need the owner and LinkedIn

| Commit | What | Depends | Behavior |
|---|---|---|---|
| U28 | build: per-line version catalogs and one line switch; the default stays LinkedIn 1.5.2 | U11 | none |
| U29 | build: `datalayout` on Spark 3.5, declared as a POM change (only if BC-024 is kept) | U28, owner | POM change |
| U30 | build: remove the Spark 3.1 / Iceberg 1.2 projects, plugin, image and Renovate rule, listing every artifact no longer published; the scheduler image runs the 3.5 apps jar with its log configuration passed in the Dockerfile (only if BC-025 is kept) | U28, owner | yes, artifacts removed |
| U31 | build: an Iceberg 1.12 line selectable by the switch, resolving the fork from a hosted repository; Java 17, its forces and its S3 SDK under that line only | U28, hosting (owner) | none on LinkedIn's line |
| U32 | build: shadow `META-INF/versions/21` exclusion; the Spark image on Java 17 with the line's Iceberg runtime | U31 | none on LinkedIn's line |

### New surface, strictly additive and off by default

| Commit | What | Depends | Behavior |
|---|---|---|---|
| U33 | feat: the Iceberg REST base behind `cluster.tables.irc.enabled` (off): read routes, config, namespaces, list, exists, load, error advice, interceptor wrapper, READ audit, endpoint list from mappings, metrics route | U14, U15 | none with the switch off |
| U34 | feat: the candidate entry (spells, validates and commits the native request, with the refusals of what is not stored) and the IRC commit route, authorize first, with the no-op auth test | U13, U33 | none with the switch off |
| U35 | feat: IRC create, drop and rename through the native handlers (rename pre-checks a view occupant); staged create writing nothing; the completion with a table-directory ownership component; register with the same rules | U34, U8 | none with the switch off |
| U36 | feat: create history, passed explicitly into the create; the one-table transaction route | U35 | none with the switch off |
| U37 | feat: per-table `io-impl` in IRC table answers (only for a known storage class, never over a client's own) | U33 | none with the switch off |
| U38 | feat: IRC view routes with a decided read privilege; commit authorized first | U15, U33 | none with the switch off |
| U39 | feat: the policy patch on IRC create, staged create and commit; grants and unlock routes; `endpoints` and `openhouse.capabilities` with a versioning rule for names | U17, U34 | none with the switch off |
| U40 | client: the REST capability client (one transport per catalog); the rename-target rule for OpenHouse-over-REST | U19, U39 | none on LinkedIn's catalog |
| U41 | client: the in-process seam, if kept, in a test-named module | U18 | none (not shipped) |
| U42 | feat: Apache's REST compatibility kit with the cause-aware ratchet, run at the default configuration and with v3 on | U36, U38 | tests only |

### The Iceberg 1.12 line: Spark 4.2, format v3, scan planning (additive, on that line only)

| Commit | What | Depends | Behavior |
|---|---|---|---|
| U43 | Spark: extension sources moved to the shared directory (section 3, choice 1); the Spark 4.2 runtime with parser and strategy overlays; the Spark 4.2 jobs test project over the same job sources | U31, U20 | none on LinkedIn's line |
| U44 | format v3 on the 1.12 line, switch off by default and bounding the cluster version: below-v3 400 for v3-only types; refusal of a version above the cluster's; `write-default` refusal below v3; create, upgrade (logged, counted, visible on GET), replace and rebuild rules; the Java client sends `format-version` on the raise | U16, U31 | none on LinkedIn's line; on the 1.12 line restores main's 400 for v3-only types |
| U45 | format v3: native defaults (no strip, immutability with promotion, read-bridge opt-in refused, no read-bridge config); v3 types one by one (`unknown`; refusals of nanosecond and geo types; `variant` behind its switch with the ORC and retention rules); jobs: the sort stats temporary table on v3 | U44 | none with the switch off |
| U46 | scan planning on the 1.12 line behind its property (off): the planner, the route with load checks, the decided privilege and an audit advice, the cap, time budget and concurrency limit, the v3 refusal, the late-column rule, the empty credentials route; parity tests | U33, U31 | none with the switch off |

### The move to Iceberg 1.12 as the default, explicitly and separately

| Commit | What | Depends | Behavior |
|---|---|---|---|
| U47 | (owner's call, BC-029) the read-bridge migration W8, before any default build stops declaring `column-defaults` | U45 | migrates tables |
| U48 | (owner's call) make Iceberg 1.12 the default, together with the release workflow change (publish from a resolvable line, tag after publish), the Java 17 statement for client jars and the Spark SQL differences of BC-030 listed; and, only when LinkedIn no longer needs it, a later removal of the 1.5.2 line and its per-line roots | U31, U47, hosting | yes: the one commit that changes LinkedIn's default build, justified on its own |

Follow-ups gated and separate, each with its own justification: a base check on IRC replaces (RF-004), the retry
cache after a failed write (RF-005), sort stats on 1.5.2 by extension order with the deployment owners (RF-048), the
scheduler's capabilities by configuration (RF-046), the retention backup's handling of deletes (D46).

## 5. Cruft kinds to avoid, with the worst examples

Bottom line: fourteen kinds; the first reaches users and is the worst.

1. Private ids in messages users see: `FormatV3Types.java:119-120` ("work item X5"); `FormatVersionCommitPolicy.java:134`
   ("work item W8") and `:142` ("work item X7"); `gradle/iceberg-fork.gradle:63-86` ("decisions D7 and I6d").
2. Private ids in comments and build files: `IcebergRestWriteHandler.java:77, 90, 230, 351, 465, 504, 681, 691, 1063`
   (D30, D19, D20, V14); `IcebergRestScanPlanner.java:24, 60, 71, 90, 118, 183`; `Operations.java:564-566` and
   `AppConstants.java:25` (X9, D46); 63 lines in the Spark 3.5 itest and 45 in the Spark 4.2 itest; 14 in
   `docs/development/iceberg-fork.md`.
3. History narrated instead of code explained: `PolicyTag` javadoc ("Before, the order was a HashSet's..."); the five
   build files saying "until that project was deleted ... can be folded" (`apps/spark-3.5/build.gradle:65-67`,
   `openhouse-java-runtime/build.gradle:28-37`, `openhouse-java-itest/build.gradle:10-11`,
   `openhouse-spark-itest/build.gradle:10-18`, `tables-test-fixtures-iceberg-1.5/build.gradle:13-15`);
   `split-build.yml:15-17` and `post-merge-build.yml:12-15` (dated timings).
4. Classes grown by accretion: `IcebergRestWriteHandler.java` (1,103 lines: create, staged completion, register,
   directory forensics, rename remapping, policy patching); `Operations.java` (about 1,050 lines);
   `SortStatsCollectionSparkApp.java` (138 to 284 lines for a parser-order problem).
5. Dead code: `IcebergUpdates.java:35-52`; `OpenHouseRestAdapter.CONFIG_OVERRIDES` (`:92-101`) and the compile
   dependency it alone needs; `IcebergCatalogMapper` with a test that never runs; `HouseTableRowLimits.requireTableIdFits`;
   `IcebergV3.nextRowId` (`IcebergV3.java:44-48`); the UUID branch at `IcebergRestWriteHandler.java:849-855`.
6. Test seams in main sources: `Operations.java:83-84, 841-852` (`catalogsBuilt`, `currentCatalog()`);
   `@Autowired(required = false)` defaults at `OpenHouseInternalCatalog.java:77`, `OpenHouseInternalRepositoryImpl.java:135`,
   `OpenHouseTablesApiValidator`.
7. Switches that guard nothing: `@ConditionalOnClass` at `IcebergRestScanController.java:58` and
   `IcebergRestRegisterViewController.java:31`; `-PicebergFork` (`settings.gradle:31-43`) and `HARNESS_ICEBERG_FORK`;
   static analysis disabled for the kit's and Spark 4.2 itest's source sets with no reason.
8. Reflection and string tricks to straddle two Iceberg lines: `OpenHouseRestAdapter.V3Endpoints` (`:372-399`);
   `TablesClientFactory.java:31-38, 82-110` (`String.join` against a relocation that never applies);
   `"PUFFIN".equals(...)` at `Operations.java:583`; `IcebergV3`'s reflection; `IcebergLine`'s 1.2 arm.
9. Indirection to dodge signatures: `CreateHistoryScope` (a `ThreadLocal`, `repository/CreateHistoryScope.java`) plus a
   transient property read in the shared `doCommit` (`OpenHouseInternalTableOperations.java:436`).
10. Stale justifications: `IcebergRestViewSpelling.java:26-32` (cites a bean-boundary test that no longer exists);
    `ViewsServiceImpl.loadView` javadoc (a "disabled implementation" that was deleted).
11. Naming that follows the order of work: `iceberg_1_5_version` holding `1.12.0-ohfork...`; the plugin
    `openhouse.iceberg-conventions-1.5.2` and `openhouse-spark-apps-1.5_2.12` built on 1.12; `src/shared`; `smoke_t1`;
    `refuseWhatV3DoesNotHandleYet`.
12. Benchmarks in the unit suite: `IrcScanPlanCostTest` (384 lines, 1,000- and 10,000-file tables on every run).
13. JVM-global state in a published fixture: `SAVE_LOCK` and the static soft-deleted map.
14. Annotations used for a side effect: `@ApiResponses` on hidden IRC methods only so that `ServiceAuditAspect` audits
    them.

## 6. Duplication to collapse

Bottom line: the four the orchestrator named, and the smaller ones the reviewers found.

- Three copies of the Spark SQL extensions: the Spark 3.5 runtime; the Spark 4.2 runtime (22 of 30 files
  byte-identical, eight differing by 4 to 20 lines, including `.g4`, plan nodes, five execs, `GrantableResourceTypes`,
  `ResolveRenameTableTarget`); and inside Spark 3.5 the Java-client versus capability value types
  (`javaclient/mapper/Privileges.java` against `capabilities/Privileges.java`, two `UnlockTableTarget`, two
  `UnlockValidationException`, the Scala `Principal`) with the unlock round trip (`OpenhouseDataSourceV2Strategy.scala:36-42`,
  `JavaClientCapabilities.java:47-80`). Collapse to one shared source directory with per-line overlays and one set of
  value types.
- Two House Tables stand-ins: `services/tables/src/test/.../e2e/h2/HouseTablesH2Repository.java` (357 lines) and
  `tables-test-fixtures/.../tablestest/HouseTablesH2Repository.java` (349 lines), which disagree on case sensitivity,
  cross-database rename and restore of a missing row; with them two `HouseTablesH2ReadAfterCommitTest` (183 and 182
  lines, each adding two 3,000-commit loops to every build) and two properties files. Keep one, in the fixture.
- Per-line helper copies: two `RestTransport` (`openhouse-catalog-capabilities/src/iceberg-1.5` and `src/iceberg-1.12`);
  `apps/spark-3.5/src/test-iceberg-1.5` against `src/test-iceberg-1.12` (`IcebergFiles`, `VariantData`, 125 and 342
  lines); `apps/spark-4.2` replacing `FormatV3TablesServer`, `CommitHookCatalog`, `OpenHouseSparkITest` by path
  exclusion; `IcebergV3.probe()` against `IcebergLine.detect()`. A single line removes most; until then an interface
  with per-line implementations.
- Duplicated build logic: the pin file parsed in `gradle/iceberg-fork.gradle:12-13`, `apps/spark-4.2/build.gradle:24-25`,
  the Spark 4.2 runtime `:14-15` and itest `:23`, and again in bash (`publish-local.sh:28`) and the composite action
  (`action.yml:22-24`); marker-file checks four times; the Java 17 target restated three times; 19 `--add-opens` three
  times plus `LIVY_SERVER_JAVA_OPTS`; `forcedModules = []` repeated; `build-run-tests.yml` beside `split-build.yml`
  with the compile, test and print steps written twice.
- Smaller ones: the policy merge three times (client, `PolicyPatch`, `ClientPolicyMerge`); two `ServiceWarehouse`;
  five Spark 4.2 test-service fixtures; two 133-line classpath tests; `pageSchemaPropertyOrderCustomizer` twice; two
  `<table>-<uuid>` parsers; two memoizers; the candidate spelled twice (`CandidateViews.toView`,
  `IcebergUpdates.toUpdateBody`); `isReplace` twice (`IcebergRestRequirements`, `OpenHouseRestAdapter`);
  `new FormatVersionRule` in seven places; the strip-skip block four times; the literal 3 as four constants;
  `Operations.isRestCatalog` against the test's `JobsLine.restCatalog`; `Difference` against 32 `IcebergLine`
  branches; the IRC handler's own format-version checks against the rule; `IcebergRestViewRegistration` restating
  `putView` and `refuseADirectoryInUse`; three layers of Spark read-parity tests for scan planning.
