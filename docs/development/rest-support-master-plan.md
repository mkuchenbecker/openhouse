# Iceberg REST support: the master plan

**The goal.** A stock, unmodified Apache Iceberg client works against OpenHouse.

**The measure.** Apache Iceberg's own reference `CatalogTests` suite, run against the OpenHouse REST
facade by `tests/iceberg-rest-catalog-compat`. It has 97 test cases. **15 execute today. The goal is
97.** The count is the scoreboard because it cannot be argued with: a test either runs against a
stock client and passes, or it does not.

A second, wider measure: the Iceberg REST OpenAPI defines **32 operations**. OpenHouse serves
**10**. Every operation is either implemented or carries a recorded, reviewed decision not to serve
it — silence is not an answer.

## Standing rules

These bind every workstream. They exist because each has already been violated once in this
programme, and each violation produced a number that looked like progress and was not.

1. **Never make a test stop executing.** Not by `@Disabled`, not by flipping a capability flag
   down, not by withdrawing a route so the suite self-skips. A test that stops running is worse
   than a failing one, because the suite still reports green.
2. **Never weaken an assertion to reach green.** Updating a golden to match intended new behaviour
   is legitimate; relaxing exact-set equality to a subset or length check is not.
3. **Contract tests are the specification.** Where OpenHouse behaviour and `CatalogTests` disagree,
   the suite is right, unless OpenHouse deliberately declines the capability — in which case use
   the suite's own capability flag and say so.
4. **A capability is not implemented until it works without a fallback that hides its absence.**
   Stored namespaces passed 14 tests while databases were still derived from their tables. The
   tests did not catch it because the suite creates its own namespaces and reads them straight
   back.
5. **Verify against the tree, not against these documents.** Counts, line numbers and claims decay.
   Re-derive them.
6. **CI is the bar, not a local run.** The local Gradle suites are a strict subset of CI, which
   also runs a PyIceberg smoke test and docker-compose integration tests. A local green has been
   wrong twice.

## Build environment

Verified; violating these costs hours.

- `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`. The system default is JDK 21 and the build
  **cannot run on it** — Gradle 7.6.2 dies with `Unsupported class file major version 65`.
- **Never build inside a git worktree.** A Gradle `Copy` task targets `.git/hooks`; a worktree's
  `.git` is a file, not a directory. Use a real clone.
- Build output is in the **top-level** `build/<module>/test-results/test/*.xml`, not module-local.
- The pre-commit and pre-push hooks run Spotless through Gradle and need `JAVA_HOME` too.
- `spotlessCheck` fails on `main` on a file the fork sync added. Inherited, not ours; `enforceCheck
  = false` keeps it out of CI, but it trips the local hook on any branch that merges `main`.

## Progress — 2026-08-31

Every branch below is code from this session. **None is merged to `main`.** CI is scoped
`branches: [main]`, so a pull request targeting any other branch runs no checks at all: only
PR #60 carries real CI, and every other number here is from a local Gradle run on JDK 17.
The conformance suite itself lives only on the namespace stack, not on `main`.

| Goal | State | PRs |
|---|---|---|
| 1 — database data model | **Implemented** | #59 → #61 → #63 |
| 2 — multi-level namespaces | **Implemented** | #64 → #65 → #67 |
| 3 — write path | **Implemented** | #60 → #62 → #66 |
| 4 — remaining REST surface | In progress | rename/register/metrics in flight |

**The scoreboard: 15 → 43 executing, 0 failures**, measured on the write stack
(`claude/ws3-rest-write-routes`). The nesting stack independently reports 17 executing. The two
stacks have not yet been merged, so **the combined figure is not yet known** — that merge and its
measurement is the next verification owed.

`claude/integration-rest-write` is the working branch that merges the namespace/facade stack with
the commit-engine stack. It needs remaking once the nesting and rename slices land.

### What the remaining 42 disabled tests are

Counted from the tree on `claude/ws3-rest-write-routes`, not from this document.

| Kind | Count | Owner |
|---|---|---|
| Route not built — rename 6, register 2, metrics 1 | 9 | Goal 4, in flight |
| Genuine defects — `CLIENT_TABLE_DEFAULTS_NOT_SENT` 4, `COMMIT_FAILURE_MESSAGE_TEXT` 2, `MISSING_METADATA_FILE_IS_A_500` 1, `STALE_UPDATES_NOT_A_CONFLICT` 1 | 8 | queued |
| **Capabilities OpenHouse declines** — partition evolution 10, schema narrowing 8, catalog-chosen location 3, catalog-chosen format version 2, column defaults 1, intermediate schemas 1 | **25** | **owner decision** |

A further 12 self-skip on the suite's own capability flags. 43 + 42 + 12 = 97.

**The target of 97 executing is only reachable if OpenHouse adopts the 25 declined capabilities.**
That is a product decision, not engineering work. If OpenHouse genuinely declines them, the honest
target is roughly 60 executing, with every decline recorded and — wherever the suite offers a
capability flag — expressed as a flag rather than an `@Disabled`, so the suite skips them
legitimately instead of carrying them as a to-do list that will never be done.

### Lessons this programme paid for

1. **A brief that points an agent at 90–140KB of design documents produces nothing.** Two agents ran
   seven hours and never got past reading. Short, explicit reading lists; forbid the big documents;
   one narrow scope; require a push inside the first hour.
2. **Do not idle between slices.** Waiting on a decision that only affects a later step cost hours.
   Where two stacks need combining, merge them on a working branch and verify — that needs no
   permission and is not a merge to `main`.
3. **A test that would pass vacuously must be calibrated by mutation.** Several real defects were
   caught only because a test was proven able to fail: the backfill's verification never traversed
   its paging loop, and would have marked an incomplete store complete.

---

## Where the 78 remaining disabled tests sit

| Block | Tests | Workstream |
|---|---|---|
| Transactions | 29 | WS3 |
| `commitTable` | 20 | WS3 |
| `createTable` | 13 | WS3 |
| `NEEDS_CREATE_TABLE_FOR_NAMESPACE_ASSERTION` | 2 | WS3 |
| `renameTable` | 5 | WS4 |
| `dropTable` | 4 | WS3 |
| `registerTable` | 2 | WS4 |
| `reportMetrics` | 1 | WS4 |
| Facade defects | 2 | WS1 / WS3 |

**62 of 78 are WS3.** Four fifths of the distance to the goal is the write path.

---

# WS1 — The database gets its own data model

**Status: IMPLEMENTED** — PRs #59, #61, #63, unmerged. Registration on every table-creating path
(the audit found three, not the two expected: `putTable`, `putIcebergSnapshots`, `restoreTable`); a
backfill with keyset paging, a resumable watermark and a verified-complete marker distinct from
"a scan ran"; the derived fallback deleted from all six read paths; and a read gate that refuses
loudly on an unverified store rather than reporting zero databases. Registration is fatal again now
that the store is authoritative. The description below is the original problem statement.

`database_row` existed as a table, with a repository, service and
`/hts/databases` routes. It is **not the source of truth.** Every read path in
`NamespacesServiceImpl` is `stored OR derived`, falling back to `searchTables(...)` /
`findAllIds()` against the *table* store when no row exists. A database's existence is still a
function of whether it has tables — which is the exact thing this workstream exists to eliminate.

**Done when:** a database exists because there is a row saying so, and for no other reason.

### Checklist

**A. Backfill — make the store complete**
- [ ] Idempotent backfill enumerating distinct `database_id` from the table store
- [ ] Resumable watermark, so a partial run is not a lost run
- [ ] Completion marker, durable, distinguishing "ran" from "ran and verified"
- [ ] Verification pass: `derived \ stored` is empty
- [ ] Explicit trigger — not automatic on boot; a startup full scan is the failure mode the review
      flagged as B5
- [ ] Bounded: paged, rate-limited, with the MySQL read cost stated
- [ ] Re-runnable safely against an already-complete store

**B. Registrar — keep the store complete**
- [ ] `ensureNamespace` on `TablesServiceImpl.putTable` (exists)
- [ ] `ensureNamespace` on `IcebergSnapshotsServiceImpl.putIcebergSnapshots` (**missing** — this
      route creates tables and does not register)
- [ ] Audit for any other path that creates a table; the registrar must be on all of them
- [ ] Ordering: registration precedes the table write, so a failed write leaves a harmless orphan
      rather than a table with no database
- [ ] Failure semantics stated: non-fatal before the store is authoritative, fatal after
- [ ] Idempotent under concurrency (exists, tested)

**C. Delete the derived fallback**
- [ ] `createNamespace` conflict check — store only
- [ ] `loadNamespaceMetadata` — store only
- [ ] `namespaceExists` — store only
- [ ] `listNamespaces` — store only (drops the second unpaged read it currently adds)
- [ ] `dropNamespace` emptiness — table occupancy from the table store, child namespaces from the
      namespace store; no existence fallback
- [ ] `requireStoredOrRegisterDerived` — delete
- [ ] `findDerived` — delete

**D. Guard the transition**
- [ ] Reads refuse loudly if the store is not marked complete, rather than reporting zero databases
- [ ] The refusal names the backfill as the remedy
- [ ] Test: an unbackfilled store fails closed, not empty

**E. One model, one surface**
- [ ] `GET /v1/databases` reads the store (currently byte-for-byte unchanged and purely derived)
- [ ] Resolve the asymmetry: a namespace created over REST is invisible in `/v1/databases` today
- [ ] `listTables(Namespace.empty())`'s "Unused" sentinel loses its last consumer and is deleted

**F. Facts the model must carry**
- [ ] Collation obligation discharged: production audit, then pin `database_row.database_id` and
      `user_table_row.database_id` to the same value in one change
- [ ] Out-of-band DDL record in `services/housetables/ddl/`, not only `schema.sql`
- [ ] `creationTime` / `lastModifiedTime` monotonic and indexed if anything reads them
- [ ] Case-insensitive lookup with stored-spelling echo — shipped; keep the test that pins it

**G. Correctness fixes owed here**
- [ ] `ClusterProperties` javadoc claims raising `max-depth` enables nesting; it fails startup
- [ ] `docs/iceberg-rest-catalog.md` says concurrent creates give "one 201 and one 409"; code
      returns 200
- [ ] `NamespacesServiceImpl` javadoc asserts an invariant B falsifies until fixed
- [ ] The handoff sentence names `house_table.database_id`; the column is `user_table_row.database_id`

---

# WS2 — Multi-level namespacing

**Status: IMPLEMENTED** — PRs #64, #65, #67, unmerged. The startup guard is deleted,
`supportsNestedNamespaces()` is `true`, and the two nesting reference tests execute and pass
(15 → 17 on that stack). Two bugs found that were in no plan: Iceberg 1.5.2's
`RESTUtil.decodeNamespace` splits on the literal text `"%1F"`, but Spring hands over path variables
already percent-decoded, so every nested route answered 404; and `renameTable` did not re-check its
destination, so a table could be renamed into an identifier a create refuses. `isTableNamespace`
now means `1 <= depth <= max-depth` — a ceiling, not a required depth — and a create into a missing
parent is refused rather than creating ancestors implicitly. The description below is the original
problem statement.

`NamespacesServiceImpl.rejectUnimplementedNamespaceDepth`
throws at startup for any `max-depth` but 1. What exists is the *encoding* only: `NamespaceUtil.encode`
dot-joins levels, `encode(Namespace.of("db")) == "db"` byte-for-byte, so depth-1 is unaffected and
the one-way door is closed. That is the foundation, not the feature.

**Done when:** `max-depth > 1` boots, nested namespaces work end to end, and
`supportsNestedNamespaces()` is `true` with the two nesting reference tests executing.

### Checklist

**A. The five seams the startup guard names**
- [ ] `/hts/databases` charset accepts the `.` separator — an encoded `a.b` currently cannot cross
      the wire between the two services
- [ ] `isTableNamespace` wired to the cluster property, not the file-private constant
- [ ] `validateOperationNamespace` likewise
- [ ] Metadata-table discriminator: `isMetadataTableIdentifier` requiring depth ≥ 2, plus the
      create-time admission rule rejecting a `MetadataTableType`-named table at depth ≥ 2
- [ ] `childrenOf(encodedParent)` range query on the namespace store — new repository method, new
      HTS route, spec regeneration, generated client method
- [ ] `/v1` nested-path routing: widen `databaseId`, **gated on `max-depth > 1`**

**B. The nine charset enforcement points**
- [ ] All nine widen together, in one change, across two services — the contract, not a preference
- [ ] `validateNamespaceIdentifier` helper; move only the namespace call sites, leave `tableId`
      alone (widening it destroys the metadata-table discriminator)

**C. Remove the guard, prove it**
- [ ] Delete `rejectUnimplementedNamespaceDepth` only when all five seams exist
- [ ] `supportsNestedNamespaces()` → `true`
- [ ] `testListNestedNamespaces` and `testDropNamespaceWithNestedNamespace` execute (they
      currently self-skip, having moved from explicitly-blocked to silently-not-running)

**D. Invariants that must hold at every depth**
- [ ] Depth-1 keys, storage paths, ACL subjects and routes byte-identical — the whole point
- [ ] Storage stays flat: `a.b` is one directory name, not nesting (nesting breaks
      `TableUUIDGenerator` and the orphan-directory walk)
- [ ] Property inheritance: precedence, subtraction rule on the `/v1` write path, provenance key
- [ ] Privilege inheritance decision recorded (currently: none, and `parentOrSelf` is the hook)

---

# WS3 — REST commit semantics: the write path

**Status: IMPLEMENTED** — PRs #60, #62, #66. #60 is the one branch in this programme with real CI,
and it is green. The engine (`UpdateRequirementValidator`, `MetadataUpdateApplier`) landed with no
callers, then under the existing `/v1` path as a behaviour-preserving refactor the existing suite
validated, then behind REST `createTable` / staged create / `updateTable` / `dropTable`.
**15 → 43 executing, 0 failures.** Three defects found along the way that no plan predicted:
`RESTCatalog.listTables()` could never have succeeded, because every Iceberg client since 1.6.0
sends an empty `pageToken=` and the table route answered 400; unmapped exceptions reported 500 on
commits, which an Iceberg client reads as `CommitStateUnknownException` — "the write may or may not
have landed"; and the policy merge had to read the raw JSON, because the server's
`Policies.sharingEnabled` is a primitive where the client's is boxed, so merging off the parsed
object would silently unshare a table. Confirmed empirically: the transaction reference tests use
the single-table API, so 11 pass with no `/v1/transactions/commit` route at all. Known divergence:
`purgeRequested=false` is accepted and ignored, because OpenHouse has one drop and it purges.
The description below is the original problem statement.

`commitTable` → 0 non-test files. `UpdateRequirement` → 0. No write
operation is advertised in `/v1/config`. **This is 62 of the 78 remaining tests** and the single
largest distance to the goal.

**Done when:** the 62 write-path reference tests execute and pass.

### Checklist

**A. The applier — the core**
- [ ] `MetadataUpdate` applier over `TableMetadata.Builder`
- [ ] `UpdateRequirement` checker (`assert-table-uuid`, `assert-ref-snapshot-id`,
      `assert-last-assigned-field-id`, …)
- [ ] Land it **underneath the current `/v1` API first** — turns the riskiest work into a
      behaviour-preserving refactor the existing 592-test tables suite already validates
- [ ] Re-express OpenHouse's admission rules over update lists: `SchemaValidator`, partition-spec
      immutability, reserved properties, policies
- [ ] Schema case-normalization must survive (`admit` returns a rewritten list, not `void`)

**B. Conflict detection — delete, do not rebuild**
- [ ] The durable linearization point (HTS `metadataLocation` + JPA `@Version`) is already wired
      and **does not move**
- [ ] Delete the two protocol-level checks that exist only because the client declares its own base
- [ ] `abortIfWriterBaseDivergedFromCatalog` and `failIfRetryUpdate` go dormant on the REST path

**C. Routes**
- [ ] `createTable`, including staged create
- [ ] `updateTable` (the spec's `commitTable`)
- [ ] `commitTransaction` — note the 29 transaction tests use the **single-table** API and land on
      the table commit route, not `/v1/transactions/commit`
- [ ] `dropTable`, mapped against soft-delete / purge / restore
- [ ] `x-openhouse-support: supported` annotations; flip `skipDefaultInterface=true` so an
      unimplemented supported route is a compile error, not a runtime 501

**D. Every plugin field gets a home**
- [ ] Field-by-field mapping of all 15 `CreateUpdateTableRequestBody` fields
- [ ] `clusterId`, `baseTableVersion`, `tableType` server-defaulted — a stock client sends none
- [ ] `policies` — **settable over REST**, because `SET POLICY` is a property commit; read-only
      would break all six policy statements
- [ ] `updated.openhouse.policy` becomes a server-side reserved key with the merge ported from the
      client (today the server has never seen it; a REST `SET POLICY` would silently no-op)
- [ ] Namespace must exist → `404`, and `createTable` must register it (WS1's registrar, re-homed)

**E. Migration off the adapter**
- [ ] Shadow comparison with a defined oracle and per-field divergence naming
- [ ] Stateful plumbing switch, not a boolean; `/v1/config` `overrides` as the cohort lever
- [ ] Runtime-switchable — rollback must never need a restart
- [ ] Client shim: `OpenHouseRESTCatalog extends RESTCatalog implements SupportsGrantRevoke`
- [ ] Irreversibility guard defined as a predicate over the planned commit, cluster-scoped

**F. Facade defects owed here**
- [ ] Empty `pageToken=` on the table list route → 400 (the namespace route already fixes this)
- [ ] Error mapping: illegal identifier on a table route → 404 `NoSuchTableException`, not 400.
      Retires `BLOCKED_IDENTIFIER_CHARSET`, whose constant text misnames its own defect

---

# WS4 — The remaining REST surface

**Status: in progress.** The write routes landed with Goal 3, so config, three table reads, six
namespace routes, `createTable` (with staged create), `updateTable` and `dropTable` are served and
advertised. `rename`/`register`/`reportMetrics` are in flight. Views (8), scan planning (4) and
credentials/auth (3) remain — the last two were never designed and still need a recorded decision.
The description below is the original problem statement.

Ten were served: config, three table reads, six
namespace routes.

**Done when:** every one of the 32 is implemented or carries a recorded, reviewed decision not to
serve it.

### Checklist

**A. Views — 8 operations**
- [ ] `createView`, `loadView`, `replaceView`, `dropView`, `listViews`, `viewExists`, `renameView`,
      `registerView`
- [ ] Built on the #44 line, absent from this branch — port or rebuild, decide which
- [ ] Iceberg views reach Spark and Trino through no protocol but the REST catalog, so this is not
      optional if views matter
- [ ] `ViewCatalogTests` as the conformance gate, the way `CatalogTests` is for tables

**B. Scan planning — 4 operations, NEVER PLANNED**
- [ ] `planTableScan`, `fetchPlanningResult`, `fetchScanTasks`, `cancelPlanning`
- [ ] **No design exists.** Not mentioned in any of the four planning documents, including the
      sequencing analysis that claimed to inventory the blockers
- [ ] Decide whether OpenHouse serves server-side scan planning at all, and record it

**C. Credentials and auth — 3 operations, NEVER PLANNED**
- [ ] `loadCredentials`, `getToken`, `signRequest`
- [ ] The read facade declined credential vending; never revisited for the write path, where it
      matters more
- [ ] Decide and record

**D. The remainder**
- [ ] `renameTable` — 5 tests
- [ ] `registerTable` — 2 tests
- [ ] `reportMetrics` — 1 test

**E. Close the surface honestly**
- [ ] Every unimplemented operation is absent from `/v1/config` `endpoints`, so a stock client is
      never told a route exists that returns 501
- [ ] Every declined operation has a written reason a reviewer can disagree with

---

# Document debt

The four planning documents describe a system that substantially does not exist. Two audits found:
WS2's entire migration apparatus (resolver, cache, control surface, nine states, backfill) has zero
code while being marked as that design's obligations; property inheritance and the metadata-table
discriminator are absent; ~25 passages treat `max-depth` as a live knob; and the derived fallback —
the largest decision in the shipped branch — appears in no design.

Correcting the documents is not a separate workstream. **Each workstream corrects its own
documents as it lands**, because what the documents should say depends on what gets built. The
standing rule: a document that describes unbuilt work says so in the section that describes it.

Known factual errors to fix wherever they appear:

- `getAllDatabases()` costed as O(tables). House Tables answers the unfiltered query with
  `SELECT DISTINCT database_id` — one row per database. O(databases).
- `BLOCKED_IDENTIFIER_CHARSET` diagnosed as a charset restriction. It is an error-mapping defect.
- `SET POLICY` described as reaching the catalog through `IcebergCatalogMapper`. It does not; only
  `GRANT`/`REVOKE` and `SHOW GRANTS` do. This inflates the client-shim scope fourfold.
- "Read-only policies is a much smaller v1." False for tables: `SET POLICY` is a property commit.
- All counts predating PR #57: 95 → 78 disabled, 1 → 15 executing.
