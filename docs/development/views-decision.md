# Views decision: the eight Iceberg REST view operations

## Problem

Views are the last block of the Iceberg REST surface carrying neither an implementation nor a
recorded decision. The specification defines eight operations, none annotated
`x-openhouse-support: supported` (the annotation appears 17 times, none past line 1403, and the view
paths start at line 1554):

| Operation | Line in `spec/iceberg-rest-catalog-open-api.yaml` |
|---|---|
| `listViews` / `createView` | `:1564` / `:1599` |
| `loadView` / `replaceView` | `:1650` / `:1698` |
| `dropView` / `viewExists` | `:1800` / `:1834` |
| `renameView` / `registerView` | `:1864` / `:1931` |

**This document defers building the eight, names the trigger that would start them, and records the
decline in the meantime as an omission from `/v1/config`.** The reason is that the claim the
programme's plan treated as forcing — that views reach engines through no protocol but REST — is
true in form and empty in substance: OpenHouse stores no view today, so the eight routes would not
expose views a user already has; they would create the feature outright. This document changes no
code.

## Requirements

**Must**

1. Every claim about current behaviour is cited to a file and line, or marked unverified.
2. A stock Apache Iceberg 1.11 client must keep working against OpenHouse for tables under whatever
   is decided, without client-side configuration.
3. The decision must be durable — expressed by a mechanism that survives a client upgrade, not by a
   test annotation someone must remember to retire.
4. Options are judged on what a Spark or Trino user loses under each, not only on what OpenHouse
   avoids by declining.

**Should**

5. Prefer a decline a client can discover from `/v1/config` over one it discovers from a failed
   request.

**Won't**

6. No implementation, no `@Disabled` change, no capability-flag change, and no change to the
   existing gated plugin view path described below. Where this document concludes something should
   move, it says so and leaves the change to a slice with tests.
7. No decision about *materialized* views, which the 1.11 specification does not define as a
   separate resource.

## Options

Requirement 1 is checked throughout rather than tabulated; requirement 3 is satisfied identically by
every option (see "How the decline is expressed").

| Option | Client keeps working for tables (req. 2) | What a user loses (req. 4) | Discoverable (req. 5) | Cost |
|---|---|---|---|---|
| **A. Defer, decline for now, named trigger (recommended)** | Yes | Catalog-stored views; nothing that works today stops | Yes, by omission from `endpoints` | None |
| B. Build all eight (runner-up) | Yes | Nothing | Yes, by advertising them | Six slices; a new persisted entity end-to-end (below) |
| C. Decline permanently | Yes | Same as A, but forecloses the only portable route | Yes | None, and a decision that would have to be reopened |
| D. Build a create/load/list/drop subset | Yes | Replace and rename; `ViewCatalogTests` fails regardless | Partly | Most of B's cost for a fraction of its conformance |

The deciding criterion is requirement 4, and it cuts the other way from what the programme's plan
assumed. **Declining costs a user nothing they have**, because OpenHouse holds no view to serve:
there is no view row in House Tables, no view metadata written to storage, and no view route in the
facade. That absence was established by searching every main source set for view operations: in
`services/tables/src/main/java` the only hit is a comment noting that `loadTable` ignores the
`referenced-by` view-load parameter
(`.../tables/api/handler/impl/OpenHouseIcebergRestApiHandler.java:106`), and in
`services/housetables/src/main/java` there is none. The single exception is on the client side and
is dissected next. Option B is the runner-up rather than option C because for a *future* view the
plan's claim is essentially right — REST is the only portable protocol — so this is a deferral, not
a foreclosure. Option D is dominated: `ViewCatalogTests` exercises replace and rename heavily, so a
subset pays most of B's cost and still passes none of the conformance bar.

## The crux: is REST really the only protocol?

The plan asserts that "Iceberg views reach Spark and Trino through no protocol but the REST
catalog." **In effect true, but the tree contains one exception, and it is a mock.**

**View support that exists today, in full.** `OpenHouseCatalog` (the iceberg-1.5 / Spark-3.5 copy)
extends `BaseMetastoreViewCatalog`, making it an Iceberg `ViewCatalog`
(`integrations/java/iceberg-1.5/openhouse-java-runtime/src/iceberg-version-specific/java/com/linkedin/openhouse/javaclient/OpenHouseCatalog.java:103`),
compiled into the module's main source set (`.../openhouse-java-runtime/build.gradle:37-39`). It is
gated by the client property `iceberg-views-enabled`, default `false` (`OpenHouseCatalog.java:139`,
`:142`, `:183-184`). **Its backing store is a `ConcurrentHashMap` in the client JVM**
(`:148-149`), written by an inline `ViewOperations.commit` that logs "in-memory only, not persisted
to any service" (`:628-634`). `listViews`, `dropView` and `renameView` read that map directly
(`:695-696`, `:713`, `:727-731`), and `buildView` invents a location under `mock://openhouse/views/`
(`:678-680`). Its own javadoc calls it "the first increment of OpenHouse view support" and names the
evolution: replace the mock with a Views-service-backed `OpenHouseViewOperations` (`:80-87`).

So the non-compliant plugin-based view path is real and it is here. What it is not is a protocol by
which a view reaches a second engine: a view created through it lives in one Spark driver's heap,
is invisible to every other session, and is gone at exit. The iceberg-1.2 / Spark-3.1 copy is
table-only (`integrations/java/iceberg-1.2/.../OpenHouseCatalog.java:72`), so the path does not even
span OpenHouse's own two Spark versions.

**Trino.** There is no OpenHouse Trino catalog connector in this tree. `integrations/` holds `java`,
`python`, `spark` and `delta-harness` only; the Trino code that exists is a JDBC client OpenHouse
uses to *query* Trino for statistics (`apps/spark/src/main/java/com/linkedin/openhouse/jobs/client/TrinoClient.java:21-26`).
`ARCHITECTURE.md:108` records Trino integration as "work in progress". For Trino, then, the plan's
claim holds without qualification — and it holds for tables as much as for views.

**What a user actually loses.** Not "views" — a Spark user can still define a view over OpenHouse
tables in Spark's own session catalog. What they lose is a view that is *stored in the catalog*, and
therefore visible to a second engine, a second session, and Trino when Trino arrives. That is a real
loss, and it is a loss of something that does not exist today rather than a regression.

## Consequence of declining, verified against the stock client

A stock 1.11 `RESTCatalog` degrades cleanly, and this was verified by disassembling the client jar
this repository's compatibility module already depends on
(`tests/iceberg-rest-catalog-compat/build.gradle`, Iceberg 1.11.0):

- `RESTSessionCatalog.loadView` calls `Endpoint.check(endpoints, V1_LOAD_VIEW, ...)`, and when the
  endpoint is unadvertised the supplied exception is **`NoSuchViewException`**: "Unable to load view
  %s.%s: Server does not support endpoint %s" (`javap -c` on `lambda$loadView$6`). This is the
  exception Spark's `SparkCatalog.loadView` catches to fall back to table resolution — the same
  degradation the plugin path deliberately reproduces when views are disabled, and for the same
  reason (`OpenHouseCatalog.java:641-651`).
- `RESTSessionCatalog.viewExists` tests `endpoints.contains(V1_VIEW_EXISTS)`; when absent it falls
  through to `BaseViewSessionCatalog.viewExists` and its exception table catches
  `NoSuchViewException` to `return false`. No error surfaces.
- The write operations fail explicitly. `CREATE VIEW` reaches `buildView` and raises the same
  endpoint-not-supported error, naming the endpoint — accurate, and raised at the statement rather
  than mid-query. `integrations/spark/delta-harness/src/main/scala/harness/openhouse/SurfaceScenarios.scala:486-491`
  already pins `CREATE VIEW` as an expected-unsupported tripwire.

Table reads and writes are untouched: the facade's config response advertises only the implemented
endpoints (`services/tables/src/main/java/com/linkedin/openhouse/tables/api/handler/impl/OpenHouseIcebergRestApiHandler.java:60-65`),
and `docs/iceberg-rest-catalog.md:130` already records that view endpoints are not advertised.

## The scoreboard this does not move

`CatalogTests` — the suite this programme is measured by — **contains no view test.** `javap` on
`org.apache.iceberg.catalog.CatalogTests` in the 1.11 `iceberg-core` tests jar lists 107 `public
void` methods and eight `protected boolean` capability flags; not one method name, and no flag,
contains "view". Views live in a separate class, `org.apache.iceberg.view.ViewCatalogTests`, with
**50 test methods** and two abstract hooks, `catalog()` and `tableCatalog()`.

Building views therefore moves a different scoreboard. Not one of the 29 `@Disabled` annotations in
`tests/iceberg-rest-catalog-compat/src/test/java/com/linkedin/openhouse/tables/icebergrestcompat/OpenHouseIcebergRestCatalogTests.java`
is a view test, and none would be retired by this work.

## How the decline is expressed

By omission from `/v1/config`, not by a capability flag, and no flag is available to express it. The
mechanism is already in place: the build generates `IcebergRestOpenHouseSupport.SUPPORTED_ENDPOINTS`
from `x-openhouse-support` in the spec, an operation without the annotation cannot be advertised,
and one with it fails compilation until implemented
(`buildSrc/src/main/groovy/openhouse.iceberg-rest-openapi.gradle:91-125`, `:165-172`;
`docs/iceberg-rest-catalog.md:140-156`). The client half of the contract is `Endpoint.check`,
verified above. Nothing needs to change for the decline to hold, and nothing can silently undo it.

There *is* a flag in the tree, but it governs the other surface: `iceberg-views-enabled` on the
Spark plugin (`OpenHouseCatalog.java:139`). It is not the REST decline and cannot express it. This
document recommends, but does not make, one follow-on: while views are deferred, that flag enables a
mock store in production code, and the two surfaces now say different things about whether OpenHouse
has views. Retiring it or renaming it to say "mock" belongs in a slice with tests.

## What the trigger buys: the six slices

If the trigger fires, option B breaks into six slices, each delivering something checkable. This is
a sequence, not a design.

1. **View record in House Tables.** A `ViewRow` entity, primary key, JDBC repository and `/hts/views`
   routes, mirroring `UserTableRow` — six fields, `tableId`/`databaseId`/`version`/`metadataLocation`
   (`services/housetables/src/main/java/com/linkedin/openhouse/housetables/model/UserTableRow.java:22-35`)
   and its repository (`.../repository/impl/jdbc/UserTableHtsJdbcRepository.java`). *Delivers:* a
   view metadata pointer persists and survives restart.
2. **View metadata in storage.** Writing Iceberg `ViewMetadata` JSON through the same resolved
   `FileIO` the internal catalog uses for a table's `metadata.json`
   (`iceberg/openhouse/internalcatalog/.../OpenHouseInternalCatalog.java:352-379`), at a location the
   storage selector allocates
   (`services/tables/.../repository/impl/OpenHouseInternalRepositoryImpl.java:137-145`). *Delivers:*
   create, load and drop round-trip server-side, engine-independently.
3. **The eight routes.** Spec annotations, generated Spring interfaces, controller and handler,
   sized like the existing table write adapter (393 lines) plus its wire forms. *Delivers:* a stock
   client's `ViewCatalog` works end to end.
4. **Table/view namespace unification.** `ViewCatalogTests` requires a table create, rename or
   register to fail when a view holds the name and the reverse — roughly ten of its 50 cases
   (`createTableThatAlreadyExistsAsView`, `renameTableTargetAlreadyExistsAsView`,
   `registerTableThatAlreadyExistsAsView`, and the three transaction variants). This reaches back
   into shipped table code, not just new code
   (`services/tables/.../IcebergRestTableWriteAdapter.java:145-149`, `:189`, `:267-270`).
   *Delivers:* one namespace, two entity kinds, consistently.
5. **`ViewCatalogTests` harness.** A subclass supplying `catalog()` and `tableCatalog()` against the
   in-process server, mirroring the 463-line `CatalogTests` harness. *Delivers:* a number to report.
6. **Retire the plugin mock.** Replace the in-memory store with the service path, or delete it.
   *Delivers:* one answer to "does OpenHouse have views", across both surfaces.

Slice 4 is the one an "eight routes" estimate misses, and the reason option D is not cheaper: it is
not additive work, it modifies code that already passes tests. The honest total is comparable in aggregate to the namespace and
table-write work this branch already carries.

## What would change the recommendation

- **A second engine that must read an OpenHouse view.** The decisive fact, and it is not in this
  tree: whether any user needs a view that outlives one Spark session or is readable from Trino.
  That is held by the OpenHouse product owner and the deployment's engine roadmap, not by the code.
  If the answer is yes, the recommendation flips to B immediately, because no other protocol
  delivers it (verified above) — and slice 6 stops being cleanup and becomes a correctness fix.
- **Trino integration landing.** `ARCHITECTURE.md:108` puts it in progress. A Trino connector that
  reaches OpenHouse through the REST catalog makes views the first capability a Trino user asks for
  that Spark users could fake locally and Trino users cannot.
- **A user discovering the mock.** If anyone enables `iceberg-views-enabled` and depends on it,
  views stop being deferred and become an outage waiting for a driver restart. That flips the
  follow-on above from a recommendation to a bug.

What would *not* flip it: a conformance percentage. Views do not appear in the 97-case scoreboard,
and choosing to build them for that number would be measuring the wrong thing.

## Appendix A: review

One pass, `writing-review` (with `DESIGN-DOCS.md`) and `arch-review`, against the humanizer
structure rules and sentence catalog fetched at review time.

| Raised | Reviewer | Disposition |
|---|---|---|
| The Problem section's recommendation ran three clauses into one sentence and ended on a comma splice, so the conclusion a triaging reader stops at was the hardest sentence to parse | writing (rule 1, rule 6) | Fixed: split into the recommendation and its reason |
| Requirement 4 was written as an obligation on the document, so the options table's "what a user loses" column graded something the requirements had not committed to | writing (DESIGN-DOCS, "criteria columns don't match the stated requirements") | Fixed: requirement 4 rephrased as a criterion the options are judged on |
| "OpenHouse stores no view today" carries the whole recommendation and rested on an unstated search; an absence claim has to say how absence was established | arch, `confirmed` | Fixed: the search and its single hit are now cited, including the one comment that mentions views |
| Slice 2 assumes the server's pinned `com.linkedin.iceberg` 1.5.2 fork can write view metadata a 1.11 client reads; the fork's view classes were not checked | arch, `probable` | Recorded in Appendix B rather than fixed: verifying it is a slice-1 task, not a decision-document task, and it changes the cost of B rather than the choice between A and B |
| Two sentence-level tells: a forced closing punchline sizing the work, and a sentence announcing what the reader should conclude | writing (patterns 31, 28) | Fixed: both replaced with the plain claim |
| The six slices elaborate option B in the main line, where DESIGN-DOCS puts a rejected alternative in the appendix | writing (DESIGN-DOCS, "rejected alternative elaborated in the main line") | Accepted as conforming: the slices are the payload of option A's trigger, and without them the deferral has no checkable content |
| Option A's cost reads "None" while the document recommends a follow-on to retire the plugin's mock flag | arch, `confirmed` | Accepted: requirement 6 places that follow-on outside this decision, and the column grades the options rather than the recommendations attached to them |
| Whether this document should itself retire the mock plugin path | arch | Tabled: requirement 6 forbids it; recorded as a follow-on with the reason it is not urgent, and promoted to a bug by the third flip condition |
| Em-dash frequency and bolded recommendation sentences | writing (pattern 4 guardrail) | Accepted as conforming: house style matching `docs/development/rest-surface-decisions.md`, and DESIGN-DOCS requires the recommendation to be marked |

## Appendix B: unverified

- **Demand for catalog-stored views.** Nothing in this tree records a user asking for one. The
  deferral rests on that absence, which is weaker than evidence of no demand.
- **Slice sizing.** The six slices are sized by analogy to the namespace and table-write work in
  this branch, not by an estimate anyone has committed to. Slice 4 in particular could be larger if
  the soft-delete and rename paths need view-aware handling.
- **Whether the pinned server fork can write view metadata.** Slice 2 assumes the server runtime's
  `com.linkedin.iceberg` 1.5.2 fork can serialize the view metadata format a 1.11 client expects.
  The 1.5 client copy in this tree imports `org.apache.iceberg.view.ViewMetadata`
  (`OpenHouseCatalog.java:80-87` and its imports), which makes this likely, but the server fork's
  view classes were not checked and no cross-version round-trip was run.
- **`SparkCatalog` behaviour.** That Spark falls back to table resolution on `NoSuchViewException`
  is asserted by `OpenHouseCatalog.java:91-99` and consistent with the client bytecode above, but
  was not verified against Spark's own source in this pass.
