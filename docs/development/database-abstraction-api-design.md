# The Database abstraction: API boundary design

**Conclusion first.** Store databases as their own House Tables entity — a new `database_row`
table with its own repository, service and `/hts/databases` routes — and put a **three-mode
resolver** in front of it in the Tables Service, backed by a **static write-through cache** that
holds every database in memory. Population is by an **idempotent registrar** with two callers:
populate-on-write (the floor, no completeness guarantee) and an explicit backfill (the ceiling,
with a verification marker that `trust` mode refuses to start without). The migration runs through
nine independently safe states, each behind its own flag, and the derived path stays compiled and
reachable until the last one. Deciding criterion: it is the only option that satisfies "no
behaviour change through the migration" **and** collapses `GET /v1/databases` from a full scan of
`user_table_row` to a cache read, and it is the only one that can represent an empty database — the
thing the whole Iceberg REST namespace surface is blocked on.

This document is an **API boundary design**. It specifies contracts, modes, counters, states and
guarantees. It does not specify implementations, and it does not design migration tooling; the
migration *state plan* (§5.9) is in scope because a state a tool cannot roll back is a design
defect, not a tooling defect.

**Companions:** [rest-support-sequencing.md](rest-support-sequencing.md) §2.2 (why this block is
first) · [views-execution-checklist.md](views-execution-checklist.md) (the `entity_type`
discriminator stack this design builds on) · a sibling agent owns the multi-level namespace design;
§5.7 is the seam where the two meet and is deliberately one-sided.

---

## 1. Problem statement

**Databases do not exist.** `DatabasesServiceImpl.getAllDatabases()` reads
`openHouseInternalRepository.findAllIds()` — *every table primary key in the cluster* — and
`distinct()`s the `databaseId` out of it in Java. The call chain is:

```
GET /v1/databases
  → DatabasesServiceImpl.getAllDatabases()                    services/tables/…/services/DatabasesServiceImpl.java:32
  → OpenHouseInternalRepositoryImpl.findAllIds()              …/repository/impl/OpenHouseInternalRepositoryImpl.java:882
  → OpenHouseInternalCatalog.listTables(Namespace.empty())    iceberg/openhouse/internalcatalog/…/OpenHouseInternalCatalog.java:102
  → HouseTableRepositoryImpl.findAll()                        …/repository/HouseTableRepositoryImpl.java:269
  → GET /hts/tables/query with no filters                     services/housetables/…/controller/UserHouseTablesController.java
  → SELECT … FROM user_table_row                              (unbounded)
```

`OpenHouseInternalCatalog.listTables` marks its own empty-namespace arm as an anti-pattern in a
`TODO`. It is: it materialises one `TableIdentifier` per table, throws the table id away
(`TableIdentifier.of(houseTable.getDatabaseId(), "Unused")`), and hands back a list whose length is
the table count in order to answer a question whose answer's length is the database count.

Six consequences, in the order they hurt:

1. **An empty database cannot be represented.** A database exists exactly while it has at least one
   live table. This blocks `createNamespace`, `dropNamespace`, `loadNamespaceMetadata` and
   `updateNamespaceProperties` — the 19 disabled reference tests in
   [#34](https://github.com/mkuchenbecker/openhouse/pull/34)'s `CatalogTests` subclass — and it is
   why that suite runs with `requiresNamespaceCreate()` and `supportsNamespaceProperties()` both
   returning `false`.
2. **Namespace properties have nowhere to live.** There is no property map on a database at all.
3. **It blocks multi-level namespaces in practice**, not only on paper: an intermediate namespace
   `a` containing only namespace `a.b` and no tables has no representation.
4. **The read cost is O(tables), not O(databases).** Every `/v1/databases` call moves the whole
   `user_table_row` primary-key space across an HTTP hop and through a Java `distinct()`. The
   maintenance scheduler calls it on every run (`TablesClient.getDatabases`), for every job type.
5. **Database-scoped state has a model but no storage.** `DatabaseMetadata.jobExecutionProperties`
   exists, `DatabaseMetadata.isMaintenanceJobDisabled(jobType)` reads it — and
   `TablesClient.getDatabaseMetadataList()` builds every `DatabaseMetadata` with the map left at its
   empty default. The per-database maintenance switch is unreachable code today.
6. **Database-scoped decisions are scattered across seven mechanisms** with no common home: a glob
   table in MySQL, a cluster-level regex, a storage path convention, an external OPA document, a
   CLI regex, a table-property override, and two copies of an identifier charset. §5.2 inventories
   them.

**What is already built, and is not this document's work.** The `entity_type` discriminator stack
ported in [#45](https://github.com/mkuchenbecker/openhouse/pull/45) →
[#47](https://github.com/mkuchenbecker/openhouse/pull/47) and merged into
[#44](https://github.com/mkuchenbecker/openhouse/pull/44) establishes the *pattern* a second catalog
entity follows — a nullable discriminator column with an out-of-band DDL record, a pinned collation,
typed repository predicates, typed deletes that refuse to cross types, a neutral collision-detection
read, a typed put that supplies its own type, and a `PutResult` that names create-vs-replace. It
does **not** establish a database entity: `EntityType` has exactly two constants, `TABLE` and
`VIEW`. §"Already built" in the covering report enumerates the files.

---

## 2. Requirements

### 2.1 What a Database *is* in OpenHouse

A **Database** is the single-level namespace that OpenHouse tables and views live in, and it is the
unit of seven separate things that are currently held together only by the string being spelled the
same way in seven places:

| Role | Where it is exercised today |
|---|---|
| **Naming** — level 1 of a table identifier, `database.table` | `NamespaceUtil.MAX_NAMESPACE_DEPTH = 1`; `tableIdentifier.namespace().toString()` at every call site |
| **Authorization subject** — ACLs attach to a database, and a table inherits from its database | `DatabasesServiceImpl.checkDatabasePrivilege`; OPA `data.user_roles[input.db_id]` (`infra/recipes/docker-compose/common/opa/policy.rego`) |
| **Storage layout** — a path component of every table location | `BaseStorage.allocateTableLocation` → `{endpoint}{rootPrefix}/{databaseId}/{tableId}-{uuid}`; round-tripped by `Storage.isPathValid` |
| **Storage selection** — which backing store a new table lands on | `RegexStorageSelector.selectStorage(db, table)` matches the regex against `db + "." + table` |
| **Feature-rollout cohort** — which tables a server-side feature is on for | `table_toggle_rule.database_pattern` + `WildcardTableToggleRuleMatcher` |
| **Maintenance scope** — which tables a job walks, and which whole-database jobs run | `DatabaseTableFilter` / `--databaseFilter`; `DatabaseOperationTask`; `TableDirectoryDeletionTask` |
| **Reporting and ownership boundary** — what `/v0,/v1,/v2 /databases` enumerate | `DatabasesController`; `GetAllDatabasesResponseBody` |

**What a Database must guarantee.**

- **G1 — Durable existence.** A database exists because a record says so, not because a table
  happens to reference it. Its lifetime is independent of its table population.
- **G2 — Stable identity.** The `databaseId` is the key. It is case-insensitively unique (matching
  the existing `findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase` contract on tables), and its bytes
  are unchanged for every database that exists today.
- **G3 — Complete enumeration.** Listing databases returns every database, and every database that
  contains a live table is in that list. A table can never reference a database that is not listed.
- **G4 — Bounded properties.** A database carries a `Map<String,String>` of properties with a
  reserved `openhouse.` prefix that users cannot write, mirroring `BasePreservedKeyChecker`. The map
  is bounded in entries and bytes — see §5.8.4; without a bound the in-memory cache has no memory
  bound and the recommended option collapses.
- **G5 — Authorization continuity.** The database remains the ACL subject. Making databases stored
  must not change any access decision.
- **G6 — Path stability.** The storage prefix a database's tables were written under is a property
  of the database, not a recomputation from current cluster config.

**Who owns it.** The **Tables Service** owns the Database *API contract* (`DatabasesService`,
`/v0,/v1,/v2 /databases`, and — via Workstream 1 — `/v1/{prefix}/namespaces`). The **House Tables
Service** owns the *record* (`database_row`, `/hts/databases`). Neither owns the other's vocabulary:
HTS speaks rows and keys, Tables speaks `DatabaseDto` and Iceberg `Namespace`.

**What depends on it.** `DatabasesController` (three route versions); the REST namespace endpoints
Workstream 1 is designing; `TablesClient.getDatabases()` and everything in `apps/spark`'s scheduler
built on it; `OpaAuthorizationHandler` for every table and database access decision;
`BaseStorage.allocateTableLocation` for every table create; `OpenHouseInternalCatalog.listTables`
for the empty-namespace arm; and the 19 disabled conformance tests.

### 2.2 Must

- **M1 — Represent an empty database.** A database with zero tables is listable, loadable, and
  droppable. (Without this the whole namespace block stays blocked.)
- **M2 — Carry a bounded property map** with a reserved `openhouse.` space.
- **M3 — No behaviour change through the migration.** Every database, client and job observes
  byte-identical responses in every state up to and including fleet-wide `trust`. New behaviour
  (empty databases, properties, drop) arrives only in a later, separately flagged state.
- **M4 — Treat MySQL read volume as a budget, not an afterthought.** State a per-replica ceiling and
  meet it. `GET /v1/databases` must stop being O(tables).
- **M5 — Controllable, observable rollout.** A resolver with `original` / `compare` / `trust`, where
  `compare` reads the new path, compares, **discards the new result**, and emits divergence signal
  that identifies *where* the two disagree.
- **M6 — Population with a stated completeness guarantee.** Explicit backfill carries one;
  populate-on-write does not; both exist and the design says which applies when. Cold databases are
  covered by backfill or by a deliberate poke.
- **M7 — 100k databases.** The static write-through cache must have a *provable* memory ceiling at
  100k × 10 KB ≈ 1 GB, and a defined, safe behaviour when the ceiling is exceeded.
- **M8 — Independently safe migration states**, each with: what is true, what can go wrong, how it is
  detected, how it is rolled back. Transitions are flag-controlled and reversible.
- **M9 — Every crossed boundary is an explicit contract**: the resolver seam, the cache seam, the HTS
  repository seam, the `DatabasesService` seam, and the REST namespace surface.

### 2.3 Should

- **SH1 — Reuse the `entity_type` stack's shapes** (typed put, `PutResult`, service-owned query value
  object, out-of-band DDL record, pinned collation) so a reviewer of #45–#47 recognises this.
- **SH2 — Give database-scoped conditionals a home.** Each conditional in §5.2 is classified as a
  table property or a database entry, so the abstraction is defined by what it must carry.
- **SH3 — Delete the `listTables(Namespace.empty())` anti-pattern arm** once `trust` is fleet-wide.
- **SH4 — Make the maintenance switch reachable.** `DatabaseMetadata.jobExecutionProperties` gets a
  source.
- **SH5 — Keep the derived path compiled and flag-reachable** until the final state, so rollback is a
  configuration change and never a deploy.

### 2.4 Won't (this phase)

- **W1 — Won't implement anything.** This is a boundary design; the implementation design is the
  next phase. Basis: the owner's phase split.
- **W2 — Won't design the backfill *tool*.** The backfill *mechanism contract* — idempotency,
  resumability, watermark, completion marker, verification predicate — is specified in §5.8.3 because
  `trust` mode's precondition depends on it. How the job is packaged, scheduled and operated is
  downstream. Basis: the mechanism is what `trust` gates on; the packaging is not.
- **W3 — Won't lift `MAX_NAMESPACE_DEPTH`.** Multi-level namespaces are Workstream 1 and Phase 3 of
  the sequencing plan. This design is depth-1 and must stay byte-identical at depth 1 so that a
  prefix-preserving encoding lands on top of it for free. Basis: rest-support-sequencing §2.1.
- **W4 — Won't widen the identifier charset.** `^[a-zA-Z0-9_]+$` stays. It is the same edit and the
  same review as the namespace separator, which Workstream 1 owns. Consequence accepted: the
  `BLOCKED_IDENTIFIER_CHARSET` conformance test stays disabled after this work. Basis: one-way door,
  wrong owner.
- **W5 — Won't make `dropNamespace` semantics binding here.** The design reserves the state
  (§5.9 S8) and names the decision; cascade-vs-`NamespaceNotEmptyException` is an owner call
  recorded in the open questions. Basis: it is a product decision, not an API-shape decision, and
  it does not block any earlier state.
- **W6 — Won't move ACL storage.** OPA keeps `data.user_roles[db_id]`. The database record becomes
  the *subject* that is checkable for existence; it does not become the ACL store. Basis: G5, and
  moving the ACL store is a security review of its own.
- **W7 — Won't emit database-scoped audit events.** Table audit (`TableAuditAspect`) has an
  allowlist and byte caps; a database-scoped equivalent is a separate design. Basis: no consumer
  yet.

### 2.5 Out of scope

Iceberg-native commits (sequencing §3); the catalog-level plumbing switch (§6); views persistence
(§5); the facade defects (§4) except where a database identifier is the cause; the client shim.
None of these are prerequisites of, or blocked by, this design.

---

## 3. Options, with the recommendation

Columns are the must-requirements from §2.2. ✅ satisfied · ⚠️ partly, at a cost named in the notes
· ❌ not satisfiable by the option.

| # | Option | M1 empty db | M2 properties | M3 no behaviour change | M4 MySQL budget | M5 3-mode resolver | M6 backfill guarantee | M7 100k scale | M9 contracts |
|---|---|---|---|---|---|---|---|---|---|
| A | **Status quo** — derive from `user_table_row` PKs | ❌ | ❌ | ✅ (trivially) | ❌ O(tables)/call | n/a | n/a | ⚠️ works, cost grows with tables | ❌ no seam |
| B | **Derive + memoise** the distinct set in the Tables Service | ❌ | ❌ | ⚠️ list becomes stale | ⚠️ O(tables) per refresh | ⚠️ nothing to compare against | n/a | ⚠️ refresh cost is O(tables) | ⚠️ cache seam only |
| C | **`entity_type = DATABASE` sentinel row** in `user_table_row` | ✅ | ❌ no property column | ❌ neutral reads and the soft-delete store change | ✅ | ✅ | ✅ | ✅ | ⚠️ overloads the table seam |
| D | **New `database_row` HTS entity + resolver + static write-through cache** | ✅ | ✅ | ✅ | ✅ O(1) steady state | ✅ | ✅ | ✅ bounded by G4 | ✅ five named contracts |
| E | New `database_row`, **no cache** — read MySQL per request | ✅ | ✅ | ✅ | ⚠️ one query per call, N replicas | ✅ | ✅ | ⚠️ list of 100k per call | ✅ |
| F | **Separate namespace service** | ✅ | ✅ | ⚠️ new hop in every table create | ⚠️ new datastore | ✅ | ✅ | ✅ | ✅ but a new deployable |

**Recommended: D.** The deciding criterion is **M3 against M1**: D is the only option that both
represents an empty database and leaves every existing read path byte-identical, because the new
rows live in a table no existing query touches. C fails exactly there — a `DATABASE` row in
`user_table_row` is invisible to the *typed* predicates (`TABLE_ROW_PREDICATE`,
`VIEW_ROW_PREDICATE`) but visible to every **neutral** one: `findAll()`, `findAllByFilters(...)`,
`findAllDistinctDatabaseIds()`, and `getNeutralEntity` — the collision-detection read the views
stack added precisely so that two entity types cannot silently share a key. It also has no column
for a property map, and `soft_deleted_user_table_row` carries no discriminator at all, so a database
row routed there would restore as a table. E is D minus the cache and fails M4/M7 at 100k. F is D
plus an operational burden nobody asked for. B cannot answer M1 at all, which is the point of the
exercise.

Alternatives C, E and F are developed in Appendix A.

---

## 4. Sketch

```
                                    Tables Service (N replicas)
  ┌───────────────────────────────────────────────────────────────────────────────────┐
  │  DatabasesController          IcebergRestNamespaceController  [Workstream 1]      │
  │  /v0,/v1,/v2 /databases       /v1/{prefix}/namespaces…                            │
  │            │                            │                                         │
  │            └──────────┬─────────────────┘                                         │
  │                       ▼                                                           │
  │              DatabasesService                    ← §5.6 contract (widened, not    │
  │                       │                            reshaped: existing four methods│
  │                       ▼                            keep their signatures)         │
  │        ┌──── DatabaseResolver ────┐               ← §5.5 contract. THE SEAM.      │
  │        │   mode: original         │                 One flag. Three modes.        │
  │        │         compare          │                                               │
  │        │         trust            │                                               │
  │        └────┬──────────────┬──────┘                                               │
  │             │              │                                                      │
  │   ORIGINAL  │              │  STORED                                              │
  │             ▼              ▼                                                      │
  │   OpenHouseInternal   DatabaseCache             ← §5.4 contract. Static,          │
  │   Repository          (all databases,             write-through, bounded,         │
  │   .findAllIds()        in heap)                   watermark-refreshed.            │
  │        │                   │  ▲                                                   │
  │        │                   │  │ ensure()  ← DatabaseRegistrar: ONE idempotent     │
  │        │                   │  │             primitive, FOUR callers (§5.8)        │
  │        │                   ▼  │                                                   │
  │        │        HouseTableDatabaseRepository    ← §5.3 contract                   │
  └────────┼───────────────────┼──────────────────────────────────────────────────────┘
           │ GET /hts/tables/query (unfiltered)     │ GET/PUT/DELETE /hts/databases
           ▼                                        ▼
  ┌───────────────────────────────────────────────────────────────────────────────────┐
  │  House Tables Service                                                             │
  │    user_table_row  (database_id, table_id, …, entity_type)    ← untouched         │
  │    database_row    (database_id, properties, …)               ← NEW               │
  └───────────────────────────────────────────────────────────────────────────────────┘
```

Read it in one sentence: **one flag chooses which of two paths answers a database question; in
`compare` the second path runs but its answer is thrown away and its disagreement is counted; the
stored path never touches MySQL on the hot path because the whole database set is in heap.**

---

## 5. Details

### 5.1 Master checklist

Sub-checklists follow in the sections named. `[ ]` items are this design's obligations; items marked
**(next phase)** are named here so the boundary is complete, and are not this document's work.

- [ ] **A. Establish what already exists.** ✅ done — §1 and the covering report. Net-new is the
      `database_row` entity, the registrar, the resolver, the cache, and the migration plan; reused
      is the discriminator stack's *shapes*, not its code.
- [ ] **B. Business requirements** — §2.1. Seven roles, six guarantees, one owner per layer.
- [ ] **C. Conditional inventory** — §5.2. Sub-checklist there. Every item classified **table
      property** / **database entry** / **neither**, with a reason.
- [ ] **D. Contracts** — one section each, with preconditions, postconditions, errors, idempotency,
      consistency, and an explicit "may not assume" clause.
  - [ ] D1 HTS repository seam — §5.3
  - [ ] D2 Cache seam — §5.4
  - [ ] D3 Resolver seam — §5.5
  - [ ] D4 `DatabasesService` seam — §5.6
  - [ ] D5 REST namespace surface (Workstream 1 boundary) — §5.7
- [ ] **E. Population** — §5.8. Registrar primitive; explicit backfill with a completion guarantee;
      populate-on-write without one; the cold-database poke. Sub-checklist there.
- [ ] **F. Scale and MySQL budget** — §5.8.4 and §5.10. Property bound (G4) → memory bound (M7) →
      overflow behaviour. Read ceiling per replica.
- [ ] **G. Migration state plan** — §5.9. Nine states, each with truth/hazard/detection/rollback.
      Sub-checklist there.
- [ ] **H. Divergence observability** — §5.5.3. Counter taxonomy with a denominator, bounded
      cardinality, identifiers in sampled logs.
- [ ] **I. Pre-migration audits** — §5.9.1. Two queries that must be run before S1, because both can
      make the migration wrong in ways no state transition can fix.
- [ ] **(next phase)** Implementation design: JPA mappings, cache data structure, refresh scheduling,
      HTTP client wiring, test plan.
- [ ] **(next phase)** Backfill tooling: packaging, scheduling, operator runbook.

### 5.2 Inventory of database conditionals and special-cased databases

This is a deliverable, not background. Each row states the mechanism, where it lives, and the
model — **table property**, **database entry**, or **neither** — with the reason. "Database entry"
means the fact belongs on the `database_row` record (as a column or a property); "table property"
means it belongs in table metadata and stays there.

| # | Mechanism | Location | Model | Why |
|---|---|---|---|---|
| 1 | **Feature-toggle rules globbed by database name.** `table_toggle_rule(feature, database_pattern, table_pattern)`; `AntPathMatcher` match on both; presence ⇒ ACTIVE | `services/housetables/…/model/TableToggleRule.java`; `…/services/WildcardTableToggleRuleMatcher.java`; `…/services/ToggleStatusesServiceImpl.java` | **Database entry** (resolved activation), rule table retained as the authoring surface | The rule's *subject* is the database; a glob is an authoring convenience over a set of databases. Resolving activation onto the database record is what lets `TableFeatureToggle`'s own `TODO` come true — "evaluate rules from a locally replicated snapshot so a table read no longer blocks on HouseTables". Today every gated table read is an HTS round trip. |
| 2 | **Per-table self-service override** of #1: `<featureId>.enabled` table property, unparseable ⇒ fails closed | `services/tables/…/toggle/TableFeatureToggle.java` (`ENABLED_PROPERTY_SUFFIX`) | **Table property** — already correct, keep | This is the exact two-level shape the whole inventory should converge on: a database-scoped decision with a per-table opt-out. It is also the precedent for why authorization-bearing features must use `isFeatureActivated` and not the override form. |
| 3 | **Storage selection by regex over `db.table`.** `RegexStorageSelector` compiles one cluster-level regex; a match selects a named storage type, else cluster default | `cluster/storage/…/selector/impl/RegexStorageSelector.java`; configured under `cluster.storages.storage-selector` | **Database entry** (default), **table property** (realisation) | Editing the cluster regex silently changes where *future* tables in an existing database land, splitting one database across two stores with no record of why. The chosen store must be durable on the database. The already-persisted per-table `user_table_row.storage_type` stays as the frozen realisation — a table's store must never be recomputed. |
| 4 | **Database as a storage path component.** `{endpoint}{rootPrefix}/{databaseId}/{tableId}-{uuid}`, round-tripped by `Storage.isPathValid` | `cluster/storage/…/BaseStorage.java:allocateTableLocation`, `Storage.java:91` | **Database entry** (`rootPrefix` at database granularity) | G6. The prefix must survive both a cluster-config change and Workstream 1's namespace-encoding decision. Recomputing it from live config is how a `isPathValid` precondition starts failing on old tables. |
| 5 | **Replica-table location pin.** `openhouse.replicaTableLocationId` overrides the `{tableId}-{uuid}` leaf | `BaseStorage.calculateTableLocationId` | **Table property** — already correct, keep | Per-table by construction; a replica's source table id is a fact about that table. Listed because it is the only existing property that participates in path construction, and it constrains #4: the database entry owns the *prefix*, never the leaf. |
| 6 | **ACLs keyed by database, in an external document.** OPA `data.user_roles[input.db_id]`, with a table arm nested under it; `DatabaseDto.builder().databaseId(id).build()` is synthesised on demand with no existence check | `infra/recipes/docker-compose/common/opa/policy.rego`; `services/tables/…/services/DatabasesServiceImpl.java` (`checkDatabasePrivilege`, `getDatabaseAclPolicies`) | **Database entry** (the subject), **not** the ACL store (W6) | `getDatabaseAclPolicies("does_not_exist")` returns `[]` and `updateDatabaseAclPolicies` on a non-existent database succeeds — because there is nothing to check existence against. Once databases are stored, existence *becomes* checkable, which is a behaviour change; M3 forbids taking it before S8. Recorded as a deliberate non-change with a state that owns it. |
| 7 | **Maintenance scoping by database regex.** `--databaseFilter` (default `.*`) → `DatabaseTableFilter.applyDatabaseName`, applied in six places in `TablesClient` | `apps/spark/…/util/DatabaseTableFilter.java`; `…/scheduler/JobsScheduler.java:511,724`; `…/client/TablesClient.java` | **Database entry** | An operator-supplied regex on a job invocation is the least durable place a per-database policy can live: it is invisible to the database's owner, unversioned, and different for every job type. |
| 8 | **Per-database maintenance disable — a model with no storage.** `DatabaseMetadata.isMaintenanceJobDisabled(jobType)` reads `jobExecutionProperties["disabled"]` and `"{jobType}.disabled"`; `TablesClient.getDatabaseMetadataList()` constructs every `DatabaseMetadata` without ever setting the map | `apps/spark/…/util/DatabaseMetadata.java`; `…/client/TablesClient.java:321-326`; consumed by `DatabaseOperationTask.shouldRun()` | **Database entry** — and it is the first consumer of database properties | The dead-code finding. The per-database switch is written, tested, wired into `shouldRun()`, and can never return `true`. `GetDatabaseResponseBody` gaining a properties map turns three existing classes on at once. This is the strongest argument that the abstraction is *wanted*, not merely spec-driven. |
| 9 | **Reserved property namespace.** `openhouse.` prefix + the `policies` key are unwritable | `services/tables/…/repository/impl/BasePreservedKeyChecker.java`; `HouseTableSerdeUtils.IS_OH_PREFIXED` | **Table property** (existing) → **mirrored as a database-property rule** | G4. `updateNamespaceProperties` is a user-facing write into the database property map. Without the mirrored rule a client can set `openhouse.databaseId` on a namespace and create a second, contradictory source of truth. The rule must exist *before* the property map is writable (S8), not after. |
| 10 | **Identifier charset, enforced twice.** `databaseId.matches(ALPHA_NUM_UNDERSCORE_REGEX)` in two validators | `…/api/validator/impl/OpenHouseDatabasesApiValidator.java:60`; `…/impl/OpenHouseTablesApiValidator.java:529` | **Database entry** (a constraint on the key column and its collation) | Two copies of one rule; the stored entity gives it one home. Widening it is W4 / Workstream 1, but the *duplication* is this design's to remove, and the column collation must be pinned in the same breath (see #12). |
| 11 | **`clusterId` on every database response.** `databasesMapper.toDatabaseDto(dbId, clusterProperties.getClusterName())` | `…/services/DatabasesServiceImpl.java:35,48`; `DatabaseDto.clusterId` | **Neither** — a response projection | Deliberately *not* stored. It is a fact about the serving cluster, not about the database. Persisting it would create a second source of truth for cluster identity that goes stale on any cluster rename, and would make a database row cluster-specific for no gain. The resolver must project it identically in all three modes, and a mismatch is a divergence class (§5.5.3 `attribute_mismatch{field="clusterId"}`). |
| 12 | **Case folding asymmetry.** The derived path `distinct()`s in **Java** over exact strings (`DatabaseDto.equals`), while every HTS table lookup is `…IgnoreCase…` and the MySQL collation folds case | `DatabasesServiceImpl.getAllDatabases()`; `UserTableHtsJdbcRepository.findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase` | **Database entry** (one case-insensitive key), and a **pre-migration audit** | If any two tables spell their database differently in case, today's `/v1/databases` lists **both spellings** and the stored path will list **one**. That is a real, pre-existing inconsistency that the migration will surface as a divergence. It must be audited before S1 (§5.9.1) because no state transition can repair it after the fact — it is a data question, not a code question. |
| 13 | **A database whose only rows are soft-deleted vanishes.** `soft_deleted_user_table_row` has no `entity_type` and is not consulted by `findAllIds()` | `services/housetables/ddl/0000__baseline.sql`; `SoftDeletedUserTableHtsJdbcRepository` | **Database entry** — the database outlives its tables (G1) | Today, dropping the last table in a database deletes the database. Under G1 it does not. This is the *intended* new behaviour, and therefore a classified expected divergence in `compare` (`extra_in_stored{cause=soft_deleted_only}`) rather than an alarm. Naming it up front is what stops it being triaged as a bug at 3am. |
| 14 | **The empty-namespace anti-pattern arm.** `listTables(Namespace.empty())` returns one identifier per table with the table id replaced by the literal `"Unused"` | `iceberg/openhouse/internalcatalog/…/OpenHouseInternalCatalog.java:102-114` (its own `TODO` says so) | **Neither** — deleted by this design | It is a conditional *on the absence of* the database abstraction. Requirement SH3; removed at migration state S9. |
| 15 | **Empty-string `databaseId` as an "all databases" sentinel** on the HTS query seam | `HouseTableRepositoryImpl.findAllByDatabaseId` (`Strings.isNotEmpty` guard) | **Neither** — a query-vocabulary defect | Fixed by giving the new seam a typed query value object in the shape of `UserViewQuery`, where "every database" is a distinct factory method rather than a magic empty string (§5.3). |
| 16 | **Namespace depth cap.** `MAX_NAMESPACE_DEPTH = 1`; `isTableNamespace` doubles as the metadata-table discriminator | `services/common/…/utils/NamespaceUtil.java` | **Neither** — Workstream 1's seam | Noted so the boundary is complete. This design must not change it and must not depend on it changing (W3). |

**Inventory sub-checklist**

- [x] Grep database-name comparisons (`databaseId.equals/matches/startsWith/contains`) → #10 only; there are **no** hardcoded database-name comparisons in main source. Recorded as a positive finding: there is no special-cased database *by name* anywhere in the tree.
- [x] Grep allow/deny/allowlist/blocklist → the only allowlist is `cluster.iceberg.tables.audit.table-properties-allowlist` (property keys, not databases). Recorded as not-applicable.
- [x] Grep database patterns / globs / regexes → #1, #3, #7.
- [x] Grep config keyed by database (`*.yaml`, `*.properties`) → none; every `database:` key in cluster config is the *JDBC* database of HTS itself, not an OpenHouse database. Recorded so a reviewer does not re-run the search.
- [x] Grep per-database storage, path and selection → #3, #4, #5.
- [x] Grep authorization by database → #6.
- [x] Grep job/maintenance scoping by database → #7, #8.
- [x] Grep feature toggles by database → #1, #2.
- [x] Read the derived read path end to end for asymmetries → #11, #12, #13, #14, #15.
- [ ] **(owner)** Confirm no LinkedIn-internal deployment carries a database-keyed config that is absent from this repository. The inventory is complete for the open-source tree; a fork-only override would change #3 and #7.

### 5.3 Contract — the House Tables repository seam

**Owner:** House Tables Service. **Consumer:** Tables Service, through
`HouseTableDatabaseRepository` (the counterpart of `HouseTableRepositoryImpl`).

**Record.** A new table `database_row`, recorded as an out-of-band DDL file in
`services/housetables/ddl/` in the established style (`0000__baseline.sql`,
`0001__add_entity_type…`, `0002__pin_entity_type_collation.sql`) — the service does not execute it;
it is an operations record and a reconstruction snapshot. The record's fields, as a contract rather
than a schema:

| Field | Contract |
|---|---|
| `databaseId` | The primary key. Case-insensitively unique. Collation **must be pinned in the same DDL that creates the column**, for the reason `0002__pin_entity_type_collation.sql` documents: an unpinned column lets SQL and Java disagree about which spellings are the same value. |
| `properties` | The bounded map of G4. Serialized representation is an implementation-phase decision; the *bound* is not — see §5.8.4. |
| `version` | Optimistic concurrency, matching `UserTableRow.@Version`. Every write is compare-and-set; a lost update is a caller-visible conflict, never a silent overwrite. |
| `creationTime`, `lastModifiedTime` | `lastModifiedTime` is **load-bearing**, not decorative: it is the cache's refresh watermark (§5.4). It must be monotonic per row and indexed. |
| `origin` | How the row came to exist: `BACKFILL`, `WRITE_PATH`, `API`, `POKE`. Not user-visible. It is what makes a backfill's completeness auditable after the fact and what distinguishes "this database was created by a user" from "this database was inferred from its tables" — a distinction S8's drop semantics will need. |

**Operations.** Route prefix `/hts/databases`, mirroring `/hts/tables` and `/hts/views`.

| Operation | Preconditions | Postconditions | Errors | Idempotent |
|---|---|---|---|---|
| `findById(databaseId)` | none | returns the row or empty | 5xx propagates; **absence must mean genuine absence** — a repository or hydration failure propagates rather than reading as "free", exactly as `getNeutralEntity`'s contract states | yes |
| `findAll(query)` where `query` is a typed value object (`allDatabases()` / `matching(idPattern)`), never a magic empty string (#15) | none | complete set, in `databaseId` order | 5xx propagates | yes |
| `findAllModifiedSince(watermark)` | `watermark` from a prior response | rows with `lastModifiedTime > watermark`, plus tombstones | 5xx propagates | yes |
| `put(database)` | payload's key is non-empty and matches the charset | row created or replaced; returns a `PutResult`-shaped outcome naming create-vs-replace, so the HTTP layer renders 201-vs-200 without decoding a boolean | `409` on version conflict; `400` on validation | **no** (it is a CAS), but `ensure` below is |
| `ensure(databaseId, origin)` | none | row exists; existing row untouched including its `origin` and `lastModifiedTime` | 5xx propagates | **yes** — this is the registrar primitive of §5.8 |
| `delete(databaseId)` | S8 only | row removed | `409` if non-empty, per the S8 (W5) decision | yes |

**The consumer may not assume:** that a database row exists for a database that has tables (until
S3 verified); that `findAll` order is stable across releases beyond `databaseId` ordering; that
`put` and `ensure` are the same operation; or that a delete is visible to another replica before its
next refresh interval.

**HTS may not assume:** that the caller has validated anything. Validation is duplicated at the HTS
boundary in the established style (`OpenHouseUserTableHtsApiValidator`), because HTS is reachable
independently of the Tables Service.

**Deliberately absent:** any `entity_type` on `database_row`. A database is not an occupant of the
`(database_id, table_id)` key space and must not compete for it. This is the concrete form of the
option-C rejection.

### 5.4 Contract — the cache seam

**Shape: a static write-through cache.** Every database is resident in the Tables Service heap.
"Static" means the working set is the whole set: there is no eviction policy, no admission policy,
and no per-key TTL, because a cache that can evict cannot answer "list all databases" without going
back to MySQL, which is the cost the cache exists to remove.

```
interface DatabaseCache {
  Optional<Database> get(String databaseId);       // point read
  List<Database>     list();                       // complete, in databaseId order
  Page<Database>     list(Pageable pageable);      // paged projection of the same order
  boolean            isAuthoritative();            // false ⇒ trust mode must not be served
  void               applyWrite(Database persisted);   // write-through, post-commit
  void               applyDelete(String databaseId);   // write-through, post-commit
}
```

**Consistency contract, stated exactly, because this is where M3 is won or lost.**

- **Read-your-writes, same replica: exact.** A write is applied to the cache only after the MySQL
  write commits, from the row the write returned — never from the request. Order is MySQL first,
  heap second; a heap update cannot fail in a way that leaves MySQL wrong.
- **Point reads, any replica: strongly consistent.** A cache *miss* falls through to a `findById`
  against HTS. A miss is bounded — it happens only for a database this replica has not yet seen —
  so the fallthrough cannot be a load source at steady state. This is what keeps
  `loadNamespaceMetadata` and `databaseExists` correct across replicas without waiting for a
  refresh.
- **List reads, other replicas: eventually consistent, bounded by the refresh interval.** This is
  the one genuine consistency change in the design. Today `/v1/databases` is a live query and a
  table created on replica A is in replica B's list immediately. Under the cache it is in replica
  B's list within `cluster.databases.cache.refresh-interval` (recommended default **30s**). The
  cost of avoiding it is a full scan per call, which is option E. **This requires owner
  ratification** and is in the open questions with that default.
- **Refresh is incremental, not a reload.** Each replica polls
  `findAllModifiedSince(watermark)` on the interval. Typical result: zero rows. This is what makes
  M4's ceiling hold at N replicas.
- **Negative results are not cached** as a separate structure; the cache is the complete set, so
  absence in an authoritative cache *is* the negative result — but only when `isAuthoritative()`.

**Authoritativeness and the overflow rule (M7).** The cache is authoritative iff the full load
completed, the load was not truncated by the weight cap, and the last refresh is within
`2 × refresh-interval`. If the weight cap is exceeded at load time, the cache **does not** serve a
partial set: `isAuthoritative()` returns `false`, the resolver refuses `trust` and degrades to
`compare` (or `original` if compare is also unavailable), and `database_cache_overflow_total`
increments. A partial cache serving `trust` would silently under-report databases, which is the
worst failure this design can have — it makes `listNamespaces` lie. Failing loudly into the old path
is strictly better.

**The consumer may not assume:** that `list()` reflects another replica's write within the current
request; that `get()` returning empty means the database does not exist when `isAuthoritative()` is
false; or that the cache survives a restart (it does not — it reloads).

### 5.5 Contract — the resolver seam

This is the seam. One interface, one flag, three modes.

```
interface DatabaseResolver {
  List<DatabaseDto>  listDatabases();
  Page<DatabaseDto>  listDatabases(int page, int size, String sortBy);
  Optional<DatabaseDto> findDatabase(String databaseId);
  boolean            databaseExists(String databaseId);
}
```

Flag: `cluster.databases.resolver.mode = original | compare | trust`. Default `original`. The mode
is read per request, not cached at startup, so a change takes effect without a restart — that is
what makes rollback a configuration change (SH5).

#### 5.5.1 The three modes

| Mode | Reads original | Reads stored | Returns | MySQL cost vs today | Rollback |
|---|---|---|---|---|---|
| `original` | yes | **no** | original | identical | n/a — this *is* the rollback target |
| `compare` | yes | yes | **original** (the stored result is discarded) | identical + one cache read (no MySQL) | flag → `original` |
| `trust` | **no** | yes | stored | O(1) — the full scan disappears | flag → `compare` or `original` |

**`compare` must be unable to change the response.** Three rules make that structural rather than
aspirational:

1. The stored read and the comparison run **after** the original result is fully materialised.
2. The whole stored-read-and-compare block is exception-swallowing: any throwable is counted
   (`database_resolver_compare_error_total{class}`) and dropped. A failure in the new path can never
   fail a request in `compare`.
3. The comparison is time-boxed. Exceeding the box counts
   `database_resolver_compare_skipped_total{reason="deadline"}` and returns the original.

**`compare` must not be a MySQL load source.** It reads the *cache*, not MySQL. That is deliberate:
compare mode is on for a long time, on every replica, and a compare that doubled the query load
would make the safest state the most expensive one.

#### 5.5.2 What "the same" means, per operation

| Operation | Compared on |
|---|---|
| `listDatabases()` | the **set** of `databaseId`s; then, for each id in both, the projected `clusterId`; then the **order** of the two sequences |
| `listDatabases(page,size,sortBy)` | `totalElements`; the page's element set; the page's order. When `sortBy` is null the derived order is not deterministic across calls, so the comparator emits `compare_skipped{reason="unsortable"}` rather than a false divergence |
| `findDatabase(id)` | presence, then each projected field |
| `databaseExists(id)` | the boolean |

#### 5.5.3 Divergence counters — designed to say *where*

A counter that only says "they differed" sends an operator to a full-table diff. These say which
side, which kind, and how much, with bounded cardinality.

**Names** (service-owned constants class in the style of `ViewMetricsConstant`, so a rename is a
Tables Service change and not a `services:common` release):

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `database_resolver_comparison_total` | counter | `op` | **The denominator.** Without it a divergence count is unreadable. |
| `database_resolver_agreement_total` | counter | `op` | Comparisons that found nothing. Agreement rate = this / comparison. |
| `database_resolver_divergence_events_total` | counter | `op`, `kind` | One per diverging *comparison*. |
| `database_resolver_divergence_items_total` | counter | `op`, `kind` | Incremented by the **magnitude** — how many databases differ, not how many requests noticed. |
| `database_resolver_divergence_magnitude` | distribution summary | `op`, `kind` | The shape of the divergence over time: is it one stuck database or a growing gap? |
| `database_resolver_compare_error_total` | counter | `class` | The compare block threw. |
| `database_resolver_compare_skipped_total` | counter | `reason` | `unsortable`, `deadline`, `cache_not_authoritative`. |
| `database_resolver_reads_total` | counter | `mode`, `source` | `source ∈ {derived, cache, hts_point_read}`. This is the M4 instrument. |
| `database_cache_size` | gauge | — | Entries resident. |
| `database_cache_weight_bytes` | gauge | — | Against the cap. |
| `database_cache_overflow_total` | counter | — | Authoritativeness lost. |
| `database_cache_staleness_seconds` | gauge | — | Now minus last successful refresh. |

**`kind` — the taxonomy is the design.** Each value names a *cause*, which is what makes the counter
actionable:

| `kind` | Meaning | Expected? | First action |
|---|---|---|---|
| `missing_in_stored` | Has tables, no `database_row` | **No, after S3** | Backfill gap or populate-on-write gap. Poke it (§5.8.3); investigate the write path. |
| `extra_in_stored_empty` | Row exists, zero live tables | **Yes, from S8** — and yes from S3 for #13's soft-delete case | None. This is G1 working. Alarm thresholds must exclude it. |
| `extra_in_stored_nonempty` | Row exists, tables exist, derived did not list it | **No** | Almost certainly a read straddling a concurrent drop; re-compare once before escalating. |
| `case_variant_collapse` | Derived lists ≥2 case spellings, stored has one | **Yes if the pre-migration audit found any** (§5.9.1); no otherwise | Pre-existing data inconsistency, surfaced not caused. Resolve as data. |
| `attribute_mismatch` (tag `field`) | Present in both, a projected field differs | **No** | Today the only possible `field` is `clusterId` (#11). |
| `order_mismatch` | Same set, different sequence | Tolerable for `/v1`; **not** for `/v2` paging | Paged clients see shifted page boundaries. |
| `count_mismatch` | `totalElements` differs on a paged compare | **No** | Usually implies one of the set kinds above. |
| `page_content_mismatch` | Same total, different page N | **No** | Ordering or a straddled write. |

**Cardinality and identifiers.** Tags are bounded — `op` (4 values) × `kind` (8) × `field` (1) — so
the whole taxonomy is a few dozen series. Database identifiers are **never** tags. They go to a
rate-limited WARN log carrying the diverging ids (capped at the first N per interval), a
`divergence_id` correlation field shared with the counter increment, and the counts per kind. That
is the pair that answers "where": the counter says which kind and how many, the log says which
databases.

**The consumer may not assume:** that `compare` implies the stored path is correct (that is what S3's
verification marker is for, not what compare is for); or that a zero divergence count means
comparisons ran — always read it against `database_resolver_comparison_total`.

### 5.6 Contract — the `DatabasesService` seam

`DatabasesService` is already the public service boundary and **keeps all four of its existing
signatures unchanged**: `getAllDatabases()`, `getAllDatabases(page,size,sortBy)`,
`updateDatabaseAclPolicies(...)`, `getDatabaseAclPolicies(...)`. Its implementation stops calling
`openHouseInternalRepository.findAllIds()` and calls `DatabaseResolver` instead. That single
substitution is the entire Tables-Service-side change of states S4–S6, which is why the states are
cheap to enter and leave.

Widened with, **available only from S8**:

| Method | Contract |
|---|---|
| `createDatabase(databaseId, properties, actingPrincipal)` | Requires a create privilege on the database (a new `Privileges` constant, in the shape of the `CREATE_VIEW` / `LIST_VIEW` constants the views work added). Idempotency is a decided question — recommended: `201` on create, `409 AlreadyExists` on a second call, matching the Iceberg spec's `createNamespace`. |
| `dropDatabase(databaseId, actingPrincipal)` | Behaviour on a populated database is the W5 decision. |
| `getDatabase(databaseId)` | `404` when absent — **only from S8**. Before S8 the absence of a database is not observable, per M3 and #6. |
| `updateDatabaseProperties(databaseId, updates, removals, actingPrincipal)` | Reserved-key rule of #9 applies; bounds of §5.8.4 apply; CAS on `version`. |

**The consumer may not assume**, before S8, that any of the four existing methods can fail with a
"database not found" — because today they cannot, and M3 says they still cannot.

### 5.7 Contract — the REST namespace surface (Workstream 1 boundary)

**Deliberately one-sided.** A sibling agent owns the namespace endpoint design. This section states
only what that design may rely on from this one, and what this design will not do.

**What Workstream 1 may assume, from S8 onward:**

- `DatabaseResolver.findDatabase(id)` / `databaseExists(id)` are the existence oracle, and they are
  strongly consistent for point reads across replicas (§5.4).
- `DatabasesService` exposes create / drop / get / update-properties with the contracts of §5.6.
- The property map has a reserved `openhouse.` space that the namespace surface must not let a client
  write (#9), and bounds that the namespace surface must enforce at ingress (§5.8.4).
- `listDatabases` is complete and ordered by `databaseId`; paging is a projection of that order.

**What this design does not decide, and Workstream 1 owns:**

- The namespace encoding (dot-join vs `%1F`) and the charset that goes with it. This design is
  depth-1 and prefix-preserving by construction: for every database that exists today, the
  `database_row` key is the same bytes as the current `databaseId`. That is the invariant that lets a
  prefix-preserving encoding land on top with no migration.
- Whether privileges inherit down a namespace tree.
- `dropNamespace` on a populated namespace (W5).
- The HTTP shape, status codes and error envelopes of `/v1/{prefix}/namespaces`.
- Whether namespace properties are readable through the existing `/v0,/v1,/v2 /databases` API.

**Handoff obligation, both directions:** neither design may change `databaseId` bytes for an existing
database. That single sentence is the whole contract between the two workstreams, and it is testable.

### 5.8 Population: one primitive, four callers

#### 5.8.1 The registrar primitive

```
DatabaseRegistrar.ensure(databaseId, Origin) -> Database
```

Idempotent, safe to call concurrently, and **never mutates an existing row** — not its properties,
not its `origin`, not its `lastModifiedTime`. Everything below is a caller of it. That is what makes
four population strategies one thing to reason about, one thing to test, and one thing to roll back.

#### 5.8.2 The two mechanisms, and when each applies

| | **Explicit backfill** | **Populate-on-write-if-absent** |
|---|---|---|
| Trigger | An operator-driven pass over `SELECT DISTINCT database_id FROM user_table_row`, in key order, paged, resumable from a watermark | `ensure(databaseId, WRITE_PATH)` on every table create (and, from S8, every namespace-scoped write) |
| Completeness | **Guaranteed**, and *provable* — see the marker below | **None.** It covers only databases that are written to. |
| Cold databases | Covered | **Not covered.** A database whose tables are never written again never materialises. This is the reason backfill is not optional. |
| Cost | One pass, bounded, offline | One extra idempotent call per table create; a cache hit after the first |
| Rollback | Rows carry `origin=BACKFILL` and can be deleted as a set | Flag off; rows stay (harmless — nothing reads them before S4) |
| When it applies | Once, at state S3, and re-runnable as a repair | Always on from S2 onward, so the population can never regress |

They are not alternatives. **Populate-on-write is the floor and explicit backfill is the ceiling.**
Populate-on-write is turned on *first* (S2) so that the set of databases the backfill must cover
stops growing before the backfill starts; the backfill then closes the fixed remainder. Running the
backfill without populate-on-write already on means the backfill races table creation and can never
be declared complete.

#### 5.8.3 The completion guarantee, and the poke

The backfill's completion guarantee is a **verification marker**, not a log line:

1. The backfill records a resumable watermark (last `database_id` processed, in key order).
2. On finishing, it runs a **verification pass**: re-derive `COUNT(DISTINCT database_id)` from
   `user_table_row` and compare against `COUNT(*)` from `database_row`, and re-derive the set
   difference. It records `backfill_completed_at`, `backfill_verified_at`, `derived_count`,
   `stored_count`.
3. **`trust` mode refuses to start** unless `backfill_verified_at` is present and
   `derived_count == stored_count` (allowing for the classified expected extras of #13). This is the
   guarantee: it is not a promise in a runbook, it is a precondition the resolver enforces.

**The deliberate poke.** A cold database that the backfill somehow missed, or that appears through a
path nobody anticipated, is repaired by calling the same `ensure` primitive. Three pokes exist:

- **Operator poke** — `ensure` over an explicit list of database ids. The minimal, auditable repair.
- **Compare-mode self-heal** — when the comparator classifies `missing_in_stored`, it calls
  `ensure(id, POKE)`. Elegant, because the divergence detector becomes the repair mechanism, and it
  guarantees that any database anyone actually *looks at* is materialised. **Gated by its own flag**
  (`cluster.databases.resolver.compare.self-heal`), default **off**, because a read path that writes
  is a load hazard: a systematic backfill gap would turn every list request into a write storm. Turn
  it on deliberately, with the write rate-limited.
- **Re-run the backfill.** Idempotent by construction, so this is always available.

#### 5.8.4 Bounds (the link from G4 to M7)

The 1 GB memory ceiling is only a ceiling if the property map is bounded **at the write API**, before
anything is stored. Recommended bounds, enforced at ingress in the `DatabasesService` /
`updateNamespaceProperties` path *and* re-enforced at the HTS boundary:

| Bound | Recommended | Why this number |
|---|---|---|
| Entries per database | 100 | Matches the practical shape of Iceberg namespace properties |
| Bytes per key + value (UTF-8) | 1 KiB | Same order as `TableAuditAspect`'s per-value cap, scaled down |
| Total serialized bytes per database | 8 KiB | 100k × 8 KiB = 800 MB serialized worst case, inside the owner's 10 KB/row sizing |
| Cache weight cap | configurable, default 1 GiB | The M7 figure. Exceeding it costs authoritativeness, not correctness (§5.4) |

The owner's sizing — 100k rows at 10 KB/row ≈ 1 GB — is deliberately generous, and these bounds are
what make it a guarantee. A realistic row (a 128-byte key, a few columns, a small property map) is
one to two orders of magnitude smaller; the ceiling is a safety property, not a forecast.

**Population sub-checklist**

- [ ] `ensure` is idempotent and never mutates an existing row — asserted, not assumed.
- [ ] Populate-on-write is enabled (S2) strictly before the backfill starts (S3).
- [ ] Backfill is resumable from a watermark and re-runnable.
- [ ] Verification pass computes `derived_count`, `stored_count`, and the set difference.
- [ ] `trust` mode's precondition reads the marker and refuses without it.
- [ ] Expected-extra classification (#13, and S8's empty databases) is defined *before* verification
      runs, or verification fails on correct data.
- [ ] Self-heal is a separate flag, default off, rate-limited.
- [ ] Property bounds are enforced at two boundaries, not one.

### 5.9 Migration state plan

Nine states. Each is a resting place: the system can sit in it indefinitely and be correct. Each
transition is one flag. **Every state's rollback is a configuration change**, never a deploy, through
S7. S8 is a flag but is not cleanly reversible, and S9 needs a deploy — which is why those two are
last and separate.

Flags:

- `cluster.databases.entity.enabled` — the HTS routes and repository exist and are reachable
- `cluster.databases.populate-on-write.enabled`
- `cluster.databases.resolver.mode` — `original | compare | trust`
- `cluster.databases.resolver.compare.self-heal`
- `cluster.databases.cache.enabled`, `.refresh-interval`, `.max-weight`
- `cluster.databases.mutable.enabled` — the S8 gate

#### 5.9.1 S0 — pre-flight audits (before any code ships)

Two data questions that no later state can repair, because they are properties of the existing rows:

1. **Case-variant audit** (#12). `SELECT database_id FROM user_table_row GROUP BY BINARY database_id`
   compared against a case-folded grouping. Any database spelled two ways today is listed twice by
   the derived path and once by the stored path — a permanent, expected divergence unless the data is
   fixed. **If this returns rows, fix the data or accept and pre-classify the divergence before S1.**
2. **Database-count and cardinality audit.** `COUNT(DISTINCT database_id)` and the distribution of
   tables per database. This is the number the whole cache sizing rests on; the design assumes 100k,
   and a real measurement either confirms it or changes M7.

- *What is true:* nothing has changed.
- *What can go wrong:* the audits are skipped and a data problem is discovered in `compare` and
  mistaken for a code defect.
- *Detection:* n/a — this state *is* the detection.
- *Rollback:* n/a.

#### 5.9.2 The states

| State | Flags | What is true | What can go wrong | How it is detected | Rollback |
|---|---|---|---|---|---|
| **S1 — schema present, unused** | `entity.enabled=true`; everything else off | `database_row` exists and is empty. No code reads or writes it. `/hts/databases` is reachable but unused. | DDL applied wrong (unpinned collation, wrong charset, missing index on `lastModifiedTime`) | A schema-shape assertion in the HTS test suite; the deployment recipes that mount `ddl/` in filename order re-apply the migration path on every run, which is how the views stack keeps its DDL honest | Drop the table. Nothing references it. |
| **S2 — populate on write** | `+ populate-on-write.enabled=true` | Every table create also calls `ensure(db, WRITE_PATH)`. Nothing reads `database_row`. Population starts growing and can never regress. | `ensure` failure fails a table create — **the one real hazard in this state** | Table-create error rate; `database_registrar_error_total` | Flag off. Rows remain and are inert. **Design obligation:** `ensure` failure must be non-fatal to the table create in S2–S5 (it is a population optimisation there, not a correctness requirement) and **fatal from S6**, the first state where a missing row is observable as a missing database. Make both halves explicit, or S2 turns a new dependency into a new outage mode and S6 turns a swallowed error into a lost database. |
| **S3 — backfill run and verified** | unchanged | `database_row` contains every database that has a live table. `backfill_verified_at` is set. | Backfill races creates (mitigated: S2 precedes it); backfill partially completes and is believed complete | The verification pass is the detection. `derived_count != stored_count` ⇒ not verified ⇒ `trust` refuses to start | Delete `origin=BACKFILL` rows; clear the marker; re-run. |
| **S4 — cache on** | `+ cache.enabled=true` | Each replica loads the full set at startup and refreshes on the watermark. Nothing serves from it yet. | Load exceeds the weight cap; refresh silently stops | `database_cache_size`, `database_cache_weight_bytes`, `database_cache_staleness_seconds`, `database_cache_overflow_total` — all four exist for this state | Flag off. |
| **S5 — compare** | `resolver.mode=compare` | Responses are still the derived ones, byte for byte. The stored path runs and disagreements are counted. | Compare throws or is slow and affects the response — structurally prevented (§5.5.1); a divergence is mis-triaged | The §5.5.3 taxonomy, read against its denominator | `mode=original`. |
| **S6 — trust, cohort** | `resolver.mode=trust` on a subset of replicas | Some replicas serve from the cache. Both answers are in production simultaneously — which is safe precisely because S5 proved they agree. | A divergence that compare could not see (e.g. an ordering difference under a `sortBy` compare skipped as unsortable) | `/v2/databases` paging assertions; client-side error rates; keep `mode=compare` on the remaining replicas so the comparison keeps running while trust is live | `mode=compare` on the cohort. |
| **S7 — trust fleet-wide** | `resolver.mode=trust` everywhere | `GET /v1/databases` no longer scans `user_table_row`. M4 is met. The derived path is still compiled and one flag away. | Cache authoritativeness lost fleet-wide (all replicas restart into an overflow) | `database_cache_overflow_total`; the resolver's own degrade-to-compare behaviour is the safety net | `mode=compare`, then `original`. |
| **S8 — databases become mutable** | `+ mutable.enabled=true` | Create / drop / properties are live. **Behaviour changes here, deliberately and for the first time.** Empty databases exist; `getDatabase` can 404; ACL operations on an absent database may start failing. | Every M3 exemption lands at once; `compare` becomes semantically meaningless for the `extra_in_stored` kinds | This state must not be entered while any replica is in `compare`, or the divergence counters become noise. Enforce the ordering. | Flag off. Existing user-created empty databases must then be handled — **this is the one transition that is not cleanly reversible**, and it is why it is separate from S7 and from S9. |
| **S9 — remove the derived path** | delete code | `findAllIds()` is gone from `DatabasesServiceImpl`; `listTables(Namespace.empty())`'s anti-pattern arm (#14) is deleted; the resolver collapses to one implementation. | A rollback now requires a deploy | Normal release process | A deploy. Enter S9 only after S7 — and, if taken, S8 — has soaked. |

**Migration sub-checklist**

- [ ] Pre-flight audits run and their results recorded (S0). Non-negotiable.
- [ ] Each flag defaults to the safe value and is independently settable.
- [ ] `ensure` failure is non-fatal to table creates in S2–S5 and fatal from S6; both halves stated.
- [ ] S2 strictly precedes S3.
- [ ] `trust` enforces the verification marker as a precondition in code, not in a runbook.
- [ ] S6 keeps some replicas in `compare` so the comparison keeps running against live `trust`.
- [ ] S8 is blocked while any replica is in `compare`.
- [ ] S8's irreversibility is stated to the owner before entering it.
- [ ] Every state above has a named metric that says whether it is healthy.

### 5.10 Scale, and the MySQL read budget

**Today.** One `GET /v1/databases` = one unfiltered `SELECT` over `user_table_row`, every row
serialized over HTTP from HTS to Tables and `distinct()`ed in Java. Cost is O(tables), which at a
million tables is a million rows to answer a question with a hundred-thousand-row answer. The
maintenance scheduler calls it once per job type per run.

**Target ceiling, per Tables Service replica, from S7 (trust fleet-wide):**

| Work | Frequency | Cost |
|---|---|---|
| Full cache load | once per process lifetime | 100k rows, paged (100 queries at 1000/page) |
| Incremental refresh | once per `refresh-interval` (default 30s) | one indexed range scan on `lastModifiedTime > watermark`; typical result **zero rows** |
| Point-read fallthrough | once per cache miss | one primary-key lookup; misses are bounded by the set of databases this replica has not seen |
| Writes | one per database create / property update | one upsert |
| **`GET /v1/databases`** | per request | **zero MySQL queries** |

At 20 replicas and a 30-second interval that is 40 indexed range queries per minute across the
fleet, against today's per-request full scans. The load reduction is the *reason* the cache is
stateful, and it is why "static write-through" is the right shape rather than a TTL cache: a TTL
cache re-reads the whole set on expiry, which is option B's cost with option D's complexity.

**Why the constraint is MySQL and not the heap.** 1 GB of heap on a service that already holds an
Iceberg metadata cache (`cluster.iceberg.tables.metadata-cache`) is a sizing conversation. A full
scan of `user_table_row` on every `/v1/databases` call, from every replica, on every scheduler run,
is a shared resource that every other OpenHouse operation contends for. That asymmetry is what makes
the stateful design correct rather than merely faster.

---

## 6. Appendix

### A. Alternatives, developed

**A.1 — Option C: `entity_type = DATABASE` in `user_table_row`.** The attraction is real: the
discriminator, its converter, its pinned collation, its typed predicates, its typed deletes, its
neutral collision read and its `PutResult` are all built and reviewed (#45–#47). Adding a third
constant to `EntityType` looks like the cheapest possible change.

It fails on four counts. **(i) The key does not fit.** The primary key is
`(database_id, table_id)`; a database has no `table_id`. A sentinel — empty string or a reserved
name — is either rejected by the `^[a-zA-Z0-9_]+$` charset or occupies a name a user could pick.
**(ii) Neutral reads change.** `TABLE_ROW_PREDICATE` and `VIEW_ROW_PREDICATE` would hide the new
rows from typed queries, but `findAll()`, `findAllByFilters(...)`, `findAllDistinctDatabaseIds()`
and `getNeutralEntity(...)` carry no type predicate — the last one deliberately, so writers can spot
a collision at a shared key. Database rows would appear in all four, which is exactly the
behaviour change M3 forbids, in the most load-bearing read path in the system. **(iii) There is no
property column.** `user_table_row` has `metadata_location`, `storage_type`, `creation_time`,
`version` — a table's shape. M2 would need a new column on the hottest table in the schema.
**(iv) The soft-delete store has no discriminator at all.** `soft_deleted_user_table_row` carries no
`entity_type`; the views work handles this by making view deletes always hard, with a comment saying
why. A database row routed into that store would restore as a table. Taken together these are not
four inconveniences, they are four ways for a database row to be mistaken for a table by code that
predates the concept.

**A.2 — Option E: the entity without the cache.** Correct, simple, and it satisfies M1, M2, M3, M5,
M6 and M9. It fails M4 and M7 in the same breath: every `/v1/databases` becomes a 100k-row query
from every replica, which is better than today's million-row query but is still O(databases) per
call on a shared resource, and it puts a 100k-element list on the wire per request. It is worth
keeping in mind as the *degraded* mode: if the cache is ever found to be unworkable, E is where the
design lands, and every contract in §5.3, §5.5 and §5.6 survives unchanged. That is a property worth
having, and it is why the cache is behind its own flag (S4) rather than being fused to the resolver.

**A.3 — Option F: a separate namespace service.** Clean boundaries, independent scaling, and a
natural home for the multi-level namespace tree later. It is rejected on cost, not on shape: it adds
a deployable, a datastore, a deployment recipe, a client, a failure mode in the table-create path,
and an operational surface — for a dataset of 100k rows that fits in the heap of a service that
already exists. If databases ever grow properties large enough to be a service of their own, F is
the migration target and the §5.3 contract is the interface it would implement.

**A.4 — Option B: memoise the derived set.** Cheap, and it would help M4 today. It cannot represent
an empty database, cannot carry properties, and has nothing to compare against, so M1, M2 and M5 are
all out of reach. It is listed because it is the tempting incremental step, and naming why it is a
dead end prevents it from being proposed again as "a smaller first step" — it is not a step toward D
at all, since none of its code survives.

### B. Definitions

| Term | Meaning here |
|---|---|
| **Database** | The single-level OpenHouse namespace; §2.1 |
| **Derived path / original** | Computing the database set from `user_table_row` primary keys, today's behaviour |
| **Stored path** | Reading `database_row`, through the cache |
| **Registrar** | The idempotent `ensure(databaseId, origin)` primitive; §5.8.1 |
| **Poke** | A deliberate call to the registrar for a database that no write path will reach; §5.8.3 |
| **Authoritative cache** | Loaded completely, not truncated, and refreshed recently enough to serve `trust`; §5.4 |
| **Expected divergence** | A `compare` disagreement that the design predicts and classifies, so it does not read as a fault; §5.5.3 |
| **Verification marker** | The persisted evidence that the backfill finished and its output matched the derived set; the precondition `trust` enforces; §5.8.3 |

### C. Evidence index

Every claim in this document is grounded in one of these files.

**The derived path**
`services/tables/src/main/java/com/linkedin/openhouse/tables/services/DatabasesServiceImpl.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java` (`findAllIds`, ~L882) ·
`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalCatalog.java` (`listTables`, L102) ·
`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImpl.java` (`findAll`, L269)

**The API surface**
`services/tables/src/main/java/com/linkedin/openhouse/tables/controller/DatabasesController.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/services/DatabasesService.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/model/DatabaseDto.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/api/validator/impl/OpenHouseDatabasesApiValidator.java`

**The discriminator stack this design builds on (#45–#47, merged in #44)**
`services/housetables/src/main/java/com/linkedin/openhouse/housetables/model/EntityType.java` ·
`.../model/EntityTypeConverter.java` · `.../model/UserTableRow.java` ·
`.../repository/impl/jdbc/UserTableHtsJdbcRepository.java` ·
`.../services/UserTablesService.java` · `.../services/PutResult.java` · `.../services/UserViewQuery.java` ·
`.../controller/UserHouseTablesController.java` ·
`services/housetables/ddl/0000__baseline.sql`, `0001__add_entity_type_to_user_table_row.sql`, `0002__pin_entity_type_collation.sql`

**Inventory sources**
`services/housetables/src/main/java/com/linkedin/openhouse/housetables/model/TableToggleRule.java` ·
`.../services/WildcardTableToggleRuleMatcher.java` · `.../services/ToggleStatusesServiceImpl.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/toggle/TableFeatureToggle.java` ·
`.../toggle/FeatureToggleAspect.java` · `.../repository/impl/BasePreservedKeyChecker.java` ·
`cluster/storage/src/main/java/com/linkedin/openhouse/cluster/storage/BaseStorage.java` ·
`.../storage/Storage.java` · `.../storage/selector/impl/RegexStorageSelector.java` ·
`.../storage/selector/StorageSelector.java` ·
`apps/spark/src/main/java/com/linkedin/openhouse/jobs/util/DatabaseTableFilter.java` ·
`.../jobs/util/DatabaseMetadata.java` · `.../jobs/client/TablesClient.java` ·
`.../jobs/scheduler/JobsScheduler.java` · `.../jobs/scheduler/tasks/DatabaseOperationTask.java` ·
`infra/recipes/docker-compose/common/opa/policy.rego` ·
`services/common/src/main/java/com/linkedin/openhouse/common/utils/NamespaceUtil.java` ·
`services/common/src/main/java/com/linkedin/openhouse/common/metrics/MetricsConstant.java` ·
`services/housetables/src/main/java/com/linkedin/openhouse/housetables/metrics/ViewMetricsConstant.java`

**Conformance**
`tests/iceberg-rest-catalog-compat/src/test/java/com/linkedin/openhouse/tables/icebergrestcompat/OpenHouseIcebergRestCatalogTests.java` (on `pr34`) — the 19 namespace `@Disabled` entries and the `requiresNamespaceCreate()` / `supportsNamespaceProperties()` overrides this design unblocks.
