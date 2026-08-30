# Native REST commit semantics: API boundary design

**Recommendation.** Make the Iceberg REST commit vocabulary — `requirements[]` +
`updates[]` applied by `TableMetadata.Builder` — the *authoritative* internal commit
representation on the server, and re-express today's whole-document
`CreateUpdateTableRequestBody` route as a translator into it. On the client, keep
`OpenHouseCatalog` in the Spark `catalog-impl` slot as a **router** over two plumbings,
with a thin `OpenHouseRESTCatalog extends RESTCatalog implements SupportsGrantRevoke`
as the REST leg. Conflict detection moves from a client-supplied metadata *file path*
(`baseTableVersion`) to spec `UpdateRequirement`s checked against a **server-loaded**
base, while the durable linearization point — HTS's `metadataLocation` compare plus the
JPA `@Version` lock on `UserTableRow` — is left byte-for-byte unchanged. That is option
**B** in §3.

This is an **API boundary design only**. It fixes signatures, ownership, and error
contracts across seven seams. It deliberately contains no implementation design.

**Scope:** Workstream 3 of the REST support programme.
**Companions:** [rest-support-sequencing.md](rest-support-sequencing.md) (programme
sequencing; §7.4 of this document corrects three of its claims),
[views-iceberg-rest-compliance.md](views-iceberg-rest-compliance.md) §3.2 (the
field-mapping method this document applies to tables),
[views-client-plugin-plan.md](views-client-plugin-plan.md) §5.1 (the lazy-embedded-
`RESTCatalog` client precedent).

**Standing constraint from the owner, and it shapes §5.7 and §5.8.** *The canary is
arbitrarily long, and rolling back by restart takes a long time and may never complete.*
Two consequences run through the whole design: **every state of the migration must be
indefinitely sustainable**, and **mixed operation is a permanent mode, not a
transition**. There is no soak window, no "until the rollout finishes", and no
transition condition that may be phrased as elapsed time. Rollback must take effect at
runtime without restarting catalogs or Spark sessions.

---

## 1. Problem

OpenHouse's write path does not speak the protocol the Iceberg REST catalog spec
defines, and the two do not translate field-by-field.

**Today.** `OpenHouseTableOperations.constructMetadataRequestBody` builds the whole
target `TableMetadata` on the *client*, serializes the schema and sort order to JSON
*strings*, and `PUT`s one document — `CreateUpdateTableRequestBody{schema,
newIntermediateSchemas, timePartitioning, clustering, tableProperties, policies,
tableType, stageCreate, stageReplace, replaceCommit, baseTableVersion, clusterId,
sortOrder}` — or, when snapshots changed, an `IcebergSnapshotsRequestBody` wrapping it.
The *server* then reconstructs what changed:
`OpenHouseInternalRepositoryImpl.save` runs `doUpdateSchemaIfNeeded`,
`doUpdateUserPropsIfNeeded`, `doUpdateSnapshotsIfNeeded`, `doUpdateSortOrderIfNeeded`,
and a policy merge; the snapshot delta itself is computed one layer lower, in
`OpenHouseInternalTableOperations.doCommit:314-350`, by differencing the client's whole
submitted snapshot list against the loaded metadata.

**The spec.** `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}` carries
`CommitTableRequest{requirements[], updates[]}`: typed `MetadataUpdate`s applied by
`TableMetadata.Builder`, and typed `UpdateRequirement`s validated server-side, with
`409` on a failed requirement.

The mismatch is structural, not cosmetic:

| Axis | Today | Spec |
|---|---|---|
| Who computes the delta | server, by diffing a submitted whole document | client, as an explicit typed list |
| What a schema is on the wire | a JSON **string** in one field | a structured `Schema` inside `add-schema`, plus `set-current-schema` |
| How many schemas per commit | one, plus a replication-only `newIntermediateSchemas` escape hatch | as many `add-schema` updates as the commit needs |
| Snapshot change | whole list, differenced | additive `add-snapshot` / explicit `remove-snapshots` |
| Conflict token | `baseTableVersion`, a metadata **file path**, supplied by the client | typed requirements over UUID, refs, schema id, spec id, sort-order id, last-assigned ids |
| Staged create / replace | three booleans (`stageCreate`, `stageReplace`, `replaceCommit`) on the document route | `stage-create` on `POST …/tables`, completed by a commit on `POST …/tables/{table}`; replace is an ordinary commit on that same route against a server-loaded base |
| OH-only concepts | first-class body fields (`policies`, `tableType`, `clusterId`) | no home; must become properties, config, or an explicit drop |

The consequence is measurable: of PR #34's 95 disabled reference tests, **62 are the
write path** — 29 `NEEDS_TRANSACTIONS`, 20 `NEEDS_COMMIT_TABLE`, 13
`NEEDS_CREATE_TABLE`. No stock Iceberg client can write to OpenHouse, and none will
until this seam exists.

A second, subtler problem: there is no way to *prove* a new commit plumbing is
equivalent to the old one before depending on it. The current protocol has three
independent conflict checks layered on top of each other (§5.5), and any
re-plumbing that quietly changes one of them is a data-integrity bug that surfaces
only under concurrency.

---

## 2. Requirements

### Must

- **M1. Native wire shape.** Requests and responses on the REST write routes are the
  spec's documents, serialized by Iceberg's own parsers. No OpenHouse fields are added
  to any spec request body, and no custom `TableUpdate` or `TableRequirement` type is
  invented.
- **M2. A stock client can write.** An unmodified Apache Iceberg `RESTCatalog` at the
  supported client floor can create, commit to, and drop an OpenHouse table without
  client-side patching. *This requirement is in tension with the irreversibility guard
  of §5.8 for as long as any cohort remains on the plugin, which the owner constraint
  makes an unbounded period. §5.8 names that tension rather than absorbing it.*
- **M3. Every field the current plugin sends has a demonstrated home.** Each field of
  `CreateUpdateTableRequestBody` and `IcebergSnapshotsRequestBody` maps to a named spec
  structure, a reserved `openhouse.*` property, a `GET /v1/config` entry, or an explicit
  and reviewable decision to drop or defer. §5.2 is the table.
- **M4. The durable linearization point does not move.** The HTS `metadataLocation`
  compare and the JPA `@Version` optimistic lock remain the single durable
  linearization point for a table commit, and no second one is introduced. Two writers
  racing for the same table state still serialize, and the loser still fails.

  *Stated deliberately as linearization, not as "no commit that fails today may succeed
  over REST".* It cannot be the latter, and this design does not pretend otherwise:
  `assert-ref-snapshot-id` is a narrower guard than `baseTableVersion` for property-only
  commits, so a commit that today fails on a version mismatch it never cared about will
  succeed over REST (§5.5, consequence 2). That is the spec's intended behaviour, it is
  safe because the HTS compare-and-swap still linearizes, and it is the single
  disclosed exception this requirement carries.
- **M5. Both sides are designed as one contract.** Client and server seams are stated
  together, with the ownership boundary between them explicit at each seam.
- **M6. The SQL extensions keep working throughout.** `GRANT`, `REVOKE`,
  `SHOW GRANTS`, and all `SET POLICY` variants function in every state of the
  migration, including the intermediate ones — which, per the owner constraint, are
  states the system may occupy permanently.
- **M7. Read-only shadow mode, as a standing invariant check.** Before anything depends
  on the REST plumbing, a parallel non-authoritative rendering of the same operation is
  produced and compared, with a defined comparison, a defined severity taxonomy, and
  defined emissions. Shadow mode never performs a second write and never fails the
  authoritative operation. Because mixed operation is permanent, shadow comparison is
  not a migration instrument that gets deleted: it remains available for as long as two
  plumbings exist, at a stated per-commit cost and a stated sampling rate.
- **M8. Switching is a state machine, not a boolean, and every state is indefinitely
  sustainable.** States, per-state invariants, forward transition conditions, and a
  rollback path for each state are specified. Forward conditions are stated as
  *evidence*, never as elapsed time. A binary on/off flag is rejected. Any state whose
  safety argument depends on that state being brief is a defect, to be named.
- **M9. Rollback is safe for data, and does not require a restart.** From any state,
  returning to the previous state leaves every table readable and writable by the
  plugin path, and takes effect at runtime within a stated propagation bound.
- **M10. Error responses carry no stack trace, no request URL, and no submitted
  document.** (Inherited from the views surface's requirement 5; the write routes
  accept far more user data than the read routes do.)

### Should

- **S1. The migration reduces code.** At the end, the whole-document protocol is a
  translator with one caller, not a parallel implementation.
- **S2. Server-side proof precedes client-side proof.** The riskiest change (the
  applier) is validated on 100% of existing production traffic under the *existing*
  API before any new wire surface depends on it.
- **S3. Reserved-property discipline is preserved.** The `openhouse.*` namespace stays
  server-owned and unwritable by clients on the REST path, as it is on the `/v1` path.
- **S4. Named reference tests, counted as executed assertions.** Each delivered
  capability names the PR #34 `@Disabled` entries it removes, and distinguishes tests
  that will *run* from tests that will merely stop being disabled (§5.9).

### Won't (this milestone)

- **W1. Implementation design.** No class decomposition, no method bodies, no
  sequencing of files. This document stops at signatures, ownership, and errors.
- **W2. Namespace endpoints.** The 19 `NEEDS_CREATE_NAMESPACE` /
  `NEEDS_LIST_NAMESPACES` / `NEEDS_LOAD_NAMESPACE` / `NEEDS_NAMESPACE_PROPERTIES` /
  `NEEDS_DROP_NAMESPACE` tests belong to the namespaces-as-entity workstream. This
  design assumes depth-1 namespaces and does not change `NamespaceUtil`.
- **W3. `registerTable`, `renameTable`, and `reportMetrics`.** 8 tests. Independent of
  commit semantics; each is small and can land on the seams this document defines
  without changing them.
- **W4. Synchronous purge.** `DELETE .../tables/{table}?purgeRequested=true` is
  rejected `400` in v1; OpenHouse purge is asynchronous and policy-gated
  (`purgeAfterMs`, `deletedAtMs`, a separate `/v1` restore route), and the spec's
  purge contract cannot be honestly satisfied. `testDropTableWithPurge` stays disabled
  with a new, accurate reason. Basis: honouring it would require either lying about
  completion or changing OpenHouse's soft-delete product behaviour, neither of which is
  a commit-semantics decision.
- **W5. REPLICA_TABLE commits over REST.** Replication writes keep using the `/v1`
  document route for the life of this milestone. Basis: the replication bypass
  (`skipEligibilityCheck`) is keyed on the *submitted* `openhouse.clusterId` differing
  from the existing table's, and M3 sends `clusterId` to server ownership — a
  server-defaulted `clusterId` would silently disable the bypass. Making replication
  REST-native requires a separate decision about how a cross-cluster write declares its
  origin; it does not block anything here.
- **W6. Spark 3.1 / Iceberg 1.2.** This design assumes that line stays on the plugin
  permanently and designs no compatibility path for it. **That assumption is
  recommended but not yet ratified**: the sequencing analysis calls it "almost
  certainly" the right answer (§6.4) and lists it as an open decision (§7, "Decisions
  needed before Phase 1 ends", item 4), as does open question 2 below. If it is
  overturned, the affected work is backporting `/v1/config` endpoint advertisement and
  the pagination fix into the 1.2 fork — additive to this design, not a change to any
  seam in it.

### Out of scope

- Multi-level namespaces and the namespace encoding one-way door.
- Views (already spec-shaped; PR #44).
- Two of the three facade defects — empty `pageToken` and the token-interceptor leak.
  They gate *other* tests, not these. (The third, previously listed here as "identifier
  charset", is not a charset defect and is not out of scope: it is an error-mapping
  defect whose fix lives in the §5.1 error table this design rewrites. See §5.1 and
  §5.9.)
- Server-side scan planning, credential vending, and the signing routes.

---

## 3. Options

Options are whole designs — both sides — differing in **where the spec's commit
vocabulary becomes authoritative** and **what sits in the Spark `catalog-impl` slot**.

| | M1 native wire | M2 stock client writes | M3 every field homed | M4 linearization unmoved | M5 one contract, both sides | M6 SQL extensions survive | M7 shadow possible | M8/M9 stateful, restart-free rollback | S1 net code reduction |
|---|---|---|---|---|---|---|---|---|---|
| **A. Edge translation** — REST handler lowers `CommitTableRequest` into `CreateUpdateTableRequestBody` and calls `TablesService.putTable`; plugin untouched | yes | partial — `remove-snapshots`, multi-`add-schema`, and `set-snapshot-ref` have no document representation | no — the document is the bottleneck, so anything it cannot say is silently dropped | **weaker** — requirements must be re-derived into a `baseTableVersion` the client never sent, so the service-layer check becomes the server attesting to its own base | **no** — "plugin untouched" means the client half is never designed; a REST writer and a plugin writer share no stated contract | yes (plugin untouched) | request-shadow only | boolean-ish; the adapter is permanent | no — adds a second adapter |
| **B. Native applier; the document route becomes the translator** ✅ **recommended** | yes | yes | yes — §5.2 | **unmoved by construction** † | yes — §5.3 through §5.8 state both sides at each seam | yes — `SupportsGrantRevoke` shim + router | all three shadows (§5.7) | yes — §5.8 | yes — one commit representation, `save` becomes a translator |
| **C. Native applier + bespoke spec extension** — as B, plus `openhouse.*` fields on `CreateTableRequest` and a custom `assert-openhouse-version` requirement | **no** — violates M1 | no — a stock client cannot produce the extension | yes, trivially | unmoved | yes | yes | yes | yes | no — a permanent fork of the spec's models |
| **D. Stock `RESTCatalog` in the Spark slot** — as B server-side; drop the plugin and the SQL extensions outright | yes | yes | yes | unmoved | partial — one side only; the client becomes a stock class and the OpenHouse-side contract is deleted rather than designed | **no** — `IcebergCatalogMapper` unwraps to `SupportsGrantRevoke`; `GRANT`/`REVOKE`/`SHOW GRANTS` fail immediately | no — nothing holds both renderings | no — a one-way cutover | yes, but by deleting a working feature |

† **The M4 cell carries one disclosed exception, not an unqualified equality.** The HTS
compare-and-swap is untouched and still linearizes, and requirements are added on top of
it. But `assert-ref-snapshot-id` permits a concurrent property-only commit that
`baseTableVersion` rejects today, so the set of *failing* commits shrinks even though
the linearization point does not move. See M4's second paragraph, §5.5 consequence 2,
and §5.10 risk 3.

**Recommendation: option B.** The deciding criterion is **M4 read together with M7**:
B is the only option where the durable conflict seam is provably unchanged (the HTS
`metadataLocation` + `@Version` CAS is untouched) *and* where the same operation exists
in two renderings at the same time, which is what makes shadow comparison possible at
all. M5 independently eliminates A: "the plugin is untouched" is exactly the property
that leaves the client half of the contract undesigned, which is unaffordable once
mixed operation is permanent. C fails M1 outright. D fails M6 outright.

Sketches for A, C, and D are in Appendix B.

---

## 4. Sketch

```
CLIENT (Spark 3.5 / iceberg-1.5)                    SERVER (services:tables)
─────────────────────────────────                   ──────────────────────────────────

spark.sql.catalog.oh.catalog-impl
  = c.l.o.spark.OpenHouseCatalog                    POST /v1/{prefix}/ns/{ns}/tables
    (1-line subclass of                             POST /v1/{prefix}/ns/{ns}/tables/{t}
     c.l.o.javaclient.OpenHouseCatalog,             DELETE …/tables/{t}
     which holds the router)                        [POST …/transactions/commit — later,
        │                                            multi-table clients only]
        │  C5: router                                     │  C1: routes (generated iface)
        │  state from a bounded, fail-open                ▼
        │  /v1/config probe owned by the             IcebergRestCatalogController
        │  router (C7 state machine)                       │  HTTP only
        ├── PLUGIN leg ──────────────┐                     ▼
        │   OpenHouseTableOperations │              OpenHouseIcebergRestApiHandler
        │   → PUT /v1/…/tables       │                    │  C2: authz (incl. replace
        │                            │                    │      privilege), identity
        └── REST leg ────────────┐   │                    │      defaults, namespace
            OpenHouseRESTCatalog │   │                    │      decode, protocol →
            extends RESTCatalog  │   │                    │      exception mapping
            + SupportsGrantRevoke│   │                    ▼
              (GRANT/REVOKE/     │   │              TableCommitService        ◄── C3
               SHOW GRANTS only) │   │                    │  requirements.validate(base)
                                 │   │                    │  allow-list check
                    C6: shadow ◄─┴───┘                    │  OpenHouseCommitPolicy.admit(…)
                    compare + emit                        │    (incl. lock state,
                    (standing, sampled)                   │     policy-patch merge)
                                                          │  updates.applyTo(builder)
                                                          ▼
                                                    OpenHouseInternalTableOperations
                                                          │  commit(base, result)
                                                          ▼
                                                    HouseTableRepository ──► HTS  ◄── C4
                                                       tableVersion == row.metadataLocation
                                                       + JPA @Version  (unchanged)

           ┌──────────────────────────────────────────────────────────────┐
           │ PUT /v1/…/tables (legacy document route) — after migration    │
           │ step M1, translates into the SAME MetadataUpdate list and     │
           │ enters at C3. One commit representation, two front doors.     │
           └──────────────────────────────────────────────────────────────┘
```

Four things this picture asserts, each defended below:

1. **C3 is entered from both front doors.** That is what makes the applier
   provable under the existing API before REST depends on it (S2), and what makes the
   legacy protocol shrink to a translator (S1).
2. **C4 does not move.** The base metadata location the server CASes on becomes
   server-derived rather than client-supplied. Nothing else about HTS changes — and on
   the update path, as §5.5 shows, not even that.
3. **The client router holds both legs simultaneously.** That is the only place where
   two renderings of one operation coexist, which is what M7 requires — permanently,
   not for the duration of a rollout.
4. **Everything `TablesServiceImpl` does before `save` has a home.** The REST path
   bypasses `TablesService`, so every precondition that class enforces — lock state,
   replace privilege, staged-table sanity, the no-op short-circuit, read-bridge strip
   protection — must be re-homed explicitly in C2 or C3. §5.3 enumerates them. A REST
   route must never reach a decision the `/v1` route would not have reached, in either
   direction.

---

## 5. Details

### 5.1 C1 — the REST write routes

**Signature.** Spec routes, marked `x-openhouse-support: supported` in
`spec/iceberg-rest-catalog-open-api.yaml`, which is the single source of truth: the
codegen gate in `buildSrc/src/main/groovy/openhouse.iceberg-rest-openapi.gradle`
generates the Spring interface *and* the `IcebergRestOpenHouseSupport.SUPPORTED_ENDPOINTS`
list that `GET /v1/config` advertises.

Marking a route supported and not implementing it is a compile error — **once
`skipDefaultInterface` is flipped; today it is a runtime 501.**
`openhouse.iceberg-rest-openapi.gradle:155` passes
`skipDefaultInterface=false`, so the generator emits `default` method bodies returning
`NOT_IMPLEMENTED`. (Corroborated by the controller overriding `getRequest()`, which the
generator only emits with default implementations enabled.) A route can therefore be
marked supported, advertised through `GET /v1/config`, and return `501` at runtime with
nothing failing the build — the exact failure mode the safety net is supposed to
exclude. **Flipping `skipDefaultInterface=true` is part of migration step M2a** (§5.9);
until then, "supported" is an assertion no compiler checks.

| Route | Request | Success | Failure |
|---|---|---|---|
| `POST /v1/{prefix}/namespaces/{ns}/tables` | `CreateTableRequest` (incl. `stage-create`) | `200` `LoadTableResponse` | `404` `NoSuchNamespaceException` · `409` `AlreadyExistsException` · `400` `IllegalArgumentException` · `403` `ForbiddenException` |
| `POST /v1/{prefix}/namespaces/{ns}/tables/{table}` | `CommitTableRequest` | `200` `LoadTableResponse` | `404` `NoSuchTableException` · `409` `CommitFailedException` · `400` `IllegalArgumentException` / `ValidationException` · `403` · `500` `CommitStateUnknownException` |
| `DELETE /v1/{prefix}/namespaces/{ns}/tables/{table}?purgeRequested=` | — | `204` | `404` `NoSuchTableException` · `400` when `purgeRequested=true` (W4) · `403` |
| `POST /v1/{prefix}/transactions/commit` — **optional, later** | `CommitTransactionRequest` | `204` | `409` `CommitFailedException` · `404` · `400` · `403` |

**Why the transactions route is optional, and what it does not buy.** It is reached only
from `RESTSessionCatalog.commitTransaction(SessionContext, List<TableCommit>)` — the
*multi-table* API. Single-table `Transaction.commitTransaction()`, including every
staged create and every replace transaction, commits on the table route above:
disassembly of the pinned `com.linkedin.iceberg:iceberg-core:1.5.2.17` shows
`RESTSessionCatalog$Builder.replaceTransaction()` does not stage a create at all — it
calls `viewExists`, then `loadInternal`, then builds
`RESTTableOperations(path = ResourcePaths.table(ident), UpdateType.REPLACE)`. The spec
agrees: `spec/iceberg-rest-catalog-open-api.yaml:1066`, inside `updateTable`, says
staged creates "are committed using this route". And Iceberg's `CatalogTests` contains
no reference to `TableCommit` or `Catalog.commitTransaction` at all — all 29
`NEEDS_TRANSACTIONS` tests call the single-table `Transaction.commitTransaction()`.
**The multi-table route therefore gates zero reference tests.** It is worth
implementing for stock multi-table clients; it is not on the critical path and should
not be sequenced as if it were.

**Ownership.** `IcebergRestCatalogController` owns HTTP and nothing else: it
implements the generated interface, unwraps parameters, and returns
`ResponseEntity`. It contains no validation, no authorization, and no catalog access.
It is the only class allowed to know about `ResponseEntity`.

**Error contract.** Every failure leaves through `IcebergRestExceptionHandler` as an
`IcebergErrorResponse`. The table below is built by walking the exceptions the REST
commit path can actually raise — principally `OpenHouseInternalTableOperations.doCommit`'s
catch blocks (`:437-475`) — not by transcribing the `/v1` route's vocabulary. Only
Iceberg types appear here, because C2 translates everything else (§5.3):

| Exception (as it reaches C1) | Status | `type` in the envelope | Client sees |
|---|---|---|---|
| `org.apache.iceberg.exceptions.CommitFailedException` | 409 | `CommitFailedException` | retriable; Iceberg's commit loop refreshes and retries |
| `org.apache.iceberg.exceptions.AlreadyExistsException` | 409 | `AlreadyExistsException` | `TableAlreadyExistsException` |
| `org.apache.iceberg.exceptions.BadRequestException` | **400** | `BadRequestException` | terminal. **New, and load-bearing:** `doCommit:438-445` raises this for a malformed snapshot (`InvalidIcebergSnapshotException`, `IllegalArgumentException`) and for a non-stale `ValidationException`. It appears in no existing handler, so today it would fall to the catch-all and a REST `add-snapshot` with a malformed snapshot would return **500 instead of 400** |
| `org.apache.iceberg.exceptions.ValidationException` | 400 | `ValidationException` | terminal; an OpenHouse admission rule refused the update list |
| `org.apache.iceberg.exceptions.NoSuchTableException` / `NoSuchNamespaceException` | 404 | as named | **including** the case where the identifier is not a legal OpenHouse identifier. The spec requires 404 with `type = NoSuchTableException` for a table under a namespace that does not exist; today `IcebergRestExceptionHandler` maps `IllegalArgumentException → 400`, which is why `testLoadTableWithNonExistingNamespace` fails. Identifier legality is not the defect; the mapping is, and it is this design's to fix |
| `org.apache.iceberg.exceptions.ForbiddenException` | 403 | `ForbiddenException` | terminal |
| `org.apache.iceberg.exceptions.CommitStateUnknownException` | 500 | `CommitStateUnknownException` | commit **not** aborted; metadata files not cleaned up |

The `500` row is load-bearing and is the one place this design accepts a `5xx` as a
*contract* rather than a bug: on an ambiguous persist outcome the server must not
report failure, because a client that believes a commit failed deletes the metadata
files it wrote. `IcebergRestExceptionHandler`'s current catch-all maps every unhandled
`Exception` to a flat `500 "Internal server error"`; that stays, and it is
indistinguishable from the ambiguous case *to a client*, which is the safe direction.
The `BadRequestException` row exists precisely because that catch-all is otherwise
reached by an ordinary malformed request, which is *not* the safe direction.

`IcebergRestExceptionHandler`'s existing `UnsupportedOperationException → 501` handler
stays as the generated-default backstop, and becomes dead once
`skipDefaultInterface=true`.

**M10 restated as a route obligation:** the handler's message field is the exception's
message. Exception messages that today embed the submitted document — for example
`checkIfPreservedTblPropsModified`, which pretty-prints a `Maps.difference` of the
provided and existing property maps — must be reduced to key names before they can be
raised from a REST route.

**One required change outside the routes.** `IcebergRestHttpMessageConverter` is
write-only today (`canRead` returns `false`, `readInternal` throws). The write routes
require the mirror: deserialization of `CreateTableRequest`, `CommitTableRequest`, and
`CommitTransactionRequest` through the *same* `RESTSerializers`-registered, kebab-case
`ObjectMapper` that `IcebergRestSerde` already owns. Using Spring's default mapper
would silently mis-parse every `TableUpdate` discriminator. Stated as a contract: **the
converter is the only path by which an Iceberg REST document enters or leaves this
service, in either direction.**

### 5.2 M3 — the field-by-field mapping

This table discharges M3. Every field of `CreateUpdateTableRequestBody` and
`IcebergSnapshotsRequestBody` appears exactly once, in the method of
`views-iceberg-rest-compliance.md` §3.2.

| Plugin field | REST home | Consequence |
|---|---|---|
| `tableId` | path `{table}`; `CreateTableRequest.name` | identity stops being repeated in the body |
| `databaseId` | path `{namespace}` | depth-1 only, per W2 |
| `clusterId` | **dropped from the body.** Server-owned; advertised in `GET /v1/config` `overrides["openhouse.clusterId"]`; readable as the `openhouse.clusterId` table property | disables the replication bypass for REST commits → W5 |
| `schema` (JSON string) | `CreateTableRequest.schema` (structured `Schema`); `AddSchemaUpdate` + `SetCurrentSchemaUpdate` on commit | `SchemaValidator.validateWriteSchema(old, new, uri)` is re-expressed as a check over the `(add-schema, set-current-schema)` pair; `set-current-schema: -1` means "the last added schema". **Open decision, §5.4:** today's path also *rewrites* the submitted schema (case normalization); a pure `admit` cannot |
| `newIntermediateSchemas` | **the update list itself** — N `add-schema` updates in one commit | the field exists only because the document can carry one schema. REST removes the need; the field is deleted at migration step M4 |
| `timePartitioning` + `clustering` | `CreateTableRequest.partition-spec`; `AddPartitionSpecUpdate` + `SetDefaultSpecUpdate` | `TimePartitionSpecBuilder` / `ClusteringSpecBuilder` / `PartitionSpecMapper` become **read-side only**. `checkPartitionSpecEvolution` becomes a rejection of `add-partition-spec` when the added spec is not equivalent to the current default — which is *stricter* than today's rule; see §5.10 risk 4 |
| `sortOrder` (JSON string) | `CreateTableRequest.write-order`; `AddSortOrderUpdate` + `SetDefaultSortOrderUpdate` | today it is persisted as the `sortOrder` *property* (`CatalogConstants.SORT_ORDER_KEY`), not in `metadata.sort-orders`. Named migration risk — see §5.10 |
| `tableProperties` (whole map) | `SetPropertiesUpdate` / `RemovePropertiesUpdate` (a delta) | `checkIfPreservedTblPropsModified` stops diffing two whole maps and becomes: reject a `set-properties`/`remove-properties` whose key set intersects `PreservedKeyChecker`'s preserved space. This is *stronger*, not weaker — a delta names exactly what the client intended to change |
| `policies` | **`SetPropertiesUpdate` on `updated.openhouse.policy`, admitted by the commit policy as a reserved key, consumed and stripped server-side** | the client already sends this key; the **server has never seen it** and must learn to. See the row below and §7.4 correction 1. Recommendation: **policies are settable over REST in v1** |
| `updated.openhouse.policy` (today client-only) | a **reserved key on the server**: `OpenHouseCommitPolicy` admits it, the applier consumes and strips it, and a server-side port of the client's `buildUpdatedPolicies` merges the JSON patch into `policies` | **without this, `SET POLICY` silently breaks over REST.** `OpenHouseTableOperations:243-246` strips the key before the wire and `:242` folds it into the `policies` request field client-side via `buildUpdatedPolicies` (`:304-390`, ~80 lines of per-plane patch semantics). `BasePreservedKeyChecker.isKeyPreserved` does not match it — `IS_OH_PREFIXED` tests `openhouse.`, and this key is `updated.openhouse.` — so a stock REST client's `ALTER TABLE t SET POLICY (RETENTION=30d)` sends an ordinary `set-properties`, the JSON patch persists as a plain user property, `policies` is never updated, retention silently stops applying, **and the statement reports success**. The merge semantics (per-plane patch, null means "leave alone", `policies == null` means "the patch is the whole policy") are a named seam, not an implementation detail: they decide what `SET POLICY` means |
| `tableType` | `openhouse.tableType` property; server-defaulted to `PRIMARY_TABLE` when absent | `checkIfTableTypeModified` becomes a rejection of a `set-properties` that changes it |
| `stageCreate` | `CreateTableRequest.stage-create: true` on `POST …/tables`, completed by a commit on `POST …/tables/{table}` carrying `assert-create` | exact spec match (`spec/…:1066`); only ever set by `OpenHouseCatalog.OpenHouseTableBuilder.createStagedMetadata` |
| `stageReplace` | **no staged create at all.** A replace transaction is an ordinary commit on `POST …/tables/{table}`, whose requirements are built by `RESTTableOperations` in `UpdateType.REPLACE` against a **server-loaded** base | the spec has no `stage-replace`, and 1.5.2.17's `RESTSessionCatalog$Builder.replaceTransaction()` does not create one: it calls `viewExists`, `loadInternal`, then `RESTTableOperations(ResourcePaths.table(ident), UpdateType.REPLACE)`. `validateReplaceTable`'s RTAS gate (`replace.enabled`, and the WAP/replication exclusions) becomes an admission check on that commit, and the replace-privilege check moves to C2 |
| `replaceCommit` | the same table-route commit | only ever set by `putSnapshotsForReplace`; it does not imply a transaction endpoint |
| `baseTableVersion` | **server-derived.** The client-visible conflict contract becomes `requirements[]`: `assert-create`, `assert-table-uuid`, `assert-ref-snapshot-id`, `assert-current-schema-id`, `assert-default-spec-id`, `assert-default-sort-order-id`, `assert-last-assigned-field-id`, `assert-last-assigned-partition-id` | the durable CAS is unchanged, and on the update path so is its input (§5.5). This is the single most consequential row in the table |
| `IcebergSnapshotsRequestBody.jsonSnapshots` | `AddSnapshotUpdate`; expiry becomes explicit `RemoveSnapshotsUpdate` | subtractive → additive. Named migration risk — see §5.10 |
| `IcebergSnapshotsRequestBody.snapshotRefs` | `SetSnapshotRefUpdate` / `RemoveSnapshotRefUpdate` | |
| `openhouse.isTableReplicated` | preserved property; not settable over REST | |
| `openhouse.tableUUID` | `assert-table-uuid` on commit; assigned by the server on create | `TableUUIDGenerator`'s manifest-path derivation stays a create-path concern |
| `GetTableResponseBody.config` (read bridge) | `LoadTableResponse.config` | spec-native; already how PR #34 serves reads |
| `GRANT` / `REVOKE` / `SHOW GRANTS` | **no spec home.** Stays on `/v1/tables/{db}/{t}/aclPolicies` and `/v1/databases/{db}/aclPolicies`, reached through `SupportsGrantRevoke` on the client shim (C5) | the only genuinely non-spec surface left after this design |
| soft delete / purge / restore | `DELETE …?purgeRequested=false` → OpenHouse soft delete. `purgeRequested=true` → 400 (W4). Restore stays `/v1`-only | |

**A dependency this mapping inherits and must not break.** All six `SET POLICY` execs
guard on `iceberg.table().properties().containsKey("openhouse.tableId")` before doing
anything. They survive a stock `RESTCatalog` only because `CatalogHandlers.loadTable`
returns the table's full metadata and every HTS field is stamped into properties under
`openhouse.`. **Stripping `openhouse.*` from the load response would silently break all
six statements** — they would fall through to their `case table =>` branch and throw
`UnsupportedOperationException`. S3 keeps `openhouse.*` unwritable by clients; it must
not be read as an argument for making it unreadable.

### 5.3 C2 — the handler seam

**Signature.** `IcebergRestApiHandler` gains five methods alongside its four read
methods:

```java
LoadTableResponse createTable(String prefix, String namespace,
                              CreateTableRequest request, String dataAccess);

LoadTableResponse updateTable(String prefix, String namespace, String table,
                              CommitTableRequest request);

void dropTable(String prefix, String namespace, String table, Boolean purgeRequested);

void commitTransaction(String prefix, CommitTransactionRequest request);  // later; §5.1

/* renameTable is W3; listed so the interface's eventual shape is visible. */
```

**Ownership.** The handler owns exactly four things and no others:

1. **Authorization — enumerated by walking `TablesServiceImpl.putTable` and
   `IcebergSnapshotsServiceImpl.putIcebergSnapshots`, not `updateEligibilityCheck`
   alone.** The `/v1` routes apply, in order:

   | Precondition | Where it lives today | Where it lives on the REST path |
   |---|---|---|
   | `Privileges.CREATE_TABLE` on the database, when no table exists | `TablesServiceImpl:143-145`, `IcebergSnapshotsServiceImpl:90-91` | **C2** |
   | `Privileges.UPDATE_TABLE_METADATA` on the table, for an ordinary update | `TablesServiceImpl:135-136`, `IcebergSnapshotsServiceImpl:86-87` | **C2** |
   | **`checkReplaceTablePrivilege`, for a replace** | `TablesServiceImpl:119`, `IcebergSnapshotsServiceImpl:84` | **C2**, keyed on the commit carrying `UpdateType.REPLACE`'s requirement shape rather than on a `stageReplace` boolean |
   | `Privileges.DELETE_TABLE` for drop | `TablesServiceImpl` drop path | **C2** |
   | **lock state — a table with `policies.lockState.locked` rejects every write** | `TablesServiceImpl:129-134`, repeated at `IcebergSnapshotsServiceImpl:76-81` | **C3**, as an `OpenHouseCommitPolicy` rule over the loaded base (§5.4). It is a property of the base metadata, so it belongs where the base is |
   | `checkIfLockPoliciesUpdated` — transitions of the lock itself | `TablesServiceImpl:128` | **C3**, same rule |
   | staged-table sanity (`isStageCreate` on a persisted row → `IllegalStateException`) | `TablesServiceImpl:124-127` | **C3** |
   | the no-op short-circuit | `TablesServiceImpl.updateNeeded:139` **and** `save`'s `if (schemaUpdated ‖ propsUpdated ‖ …)` | **C3** (§5.4, last error row) |
   | `readBridgeStripProtection.prepare` → `ColumnDefaultException` | `TablesServiceImpl:169-172`, `IcebergSnapshotsServiceImpl:94-97` | **C3** |

   **The seam, stated as a rule: everything `TablesServiceImpl` does before `save` must
   have a home.** Without it, a table locked by `SET POLICY … LOCK` is writable by any
   stock REST client at `REST_WRITE`, and a replace skips the replace-privilege check —
   both violating the invariant that a REST route must never reach a privilege decision
   the `/v1` route would not have reached.
2. **OpenHouse identity defaulting.** A stock client sends no `clusterId`, no
   `tableCreator`, no `tableUri`, no `tableType`. The handler supplies them from
   cluster config and the authenticated principal, before C3 sees the commit.
3. **Namespace decoding**, reusing PR #34's `decodeSingleLevelNamespace`.
4. **Exception translation** into Iceberg types (below).

The handler explicitly does **not** apply updates, validate requirements, or touch
`TableMetadata`. That is C3's job. This boundary is what lets the `/v1` document route
reach C3 without going through the handler at all.

**Error contract.** The handler is the boundary at which OpenHouse exception types
stop and Iceberg exception types begin. **No OpenHouse type crosses it** — that is what
lets `IcebergRestExceptionHandler` (§5.1) import nothing from
`com.linkedin.openhouse.common.exception`:

| In (as raised on the REST path) | Out |
|---|---|
| `NoSuchUserTableException`, `org.apache.iceberg.exceptions.NoSuchTableException` from the load | `NoSuchTableException` |
| `AlreadyExistsException` (OpenHouse) | `org.apache.iceberg.exceptions.AlreadyExistsException` |
| `InvalidSchemaEvolutionException` | `ValidationException` (→ 400) |
| `RequestValidationFailureException` | `IllegalArgumentException` (→ 400) |
| `UnsupportedClientOperationException` | **translated to `ValidationException`** (→ 400). It is a bare `RuntimeException` subclass, not an `UnsupportedOperationException`, so nothing forces it across the boundary; letting it through would make the controller's advice import OpenHouse types for one row |
| `InvalidTableMetadataException` | `ValidationException` (→ 400) |
| `OpenHouseCommitStateUnknownException` | `CommitStateUnknownException` (→ 500) |
| `AccessDeniedException` | `ForbiddenException` (→ 403) |
| `org.apache.iceberg.exceptions.{CommitFailedException, BadRequestException, ValidationException, CommitStateUnknownException, NotFoundException}` from `doCommit` | passed through unchanged; C1 maps them |
| anything else | propagates to C1's catch-all → 500 |

**`EntityConcurrentModificationException` is deliberately absent.** It is manufactured
in `TablesServiceImpl:183-185` and `IcebergSnapshotsServiceImpl:103`, both of which wrap
`openHouseInternalRepository.save`. The REST path does not traverse either, so a row
mapping it to `CommitFailedException` could never fire. What the REST path actually
sees is the `CommitFailedException` that `doCommit` raises directly (`:437`, `:443`,
`:451`, `:469`) — already an Iceberg type, already 409, and it needs no translation.
HTS still raises `EntityConcurrentModificationException` internally; it is converted to
`HouseTableConcurrentUpdateException` below `doCommit` and re-emerges as
`CommitFailedException` at `:451`.

No Spring type (`WebClientResponseException`, `DataIntegrityViolationException`,
`ObjectOptimisticLockingFailureException`) may cross this boundary either.

### 5.4 C3 — the metadata-update applier and requirement checker

This is the seam the whole design exists to create. It lives in
`iceberg/openhouse/internalcatalog`, not in `services:tables`, because both front
doors must reach it.

**Signature.**

```java
/** Plans one table commit. Pure with respect to storage: plans, never writes. */
public interface TableCommitPlanner {
  CommitPlan plan(TableMetadata base,              // null iff this is a create
                  List<UpdateRequirement> requirements,
                  List<MetadataUpdate> updates,
                  CommitContext context);
}

public final class CommitPlan {
  TableMetadata result();                 // what the caller must commit
  List<MetadataUpdate> applied();         // the normalized, admitted update list
}

public final class CommitContext {
  TableIdentifier identifier();
  String actingPrincipal();
  String clusterId();
  boolean stagedCreate();
  Origin origin();                        // REST | LEGACY_DOCUMENT — for metrics only
}

/**
 * OpenHouse admission rules, expressed over update lists rather than documents.
 * Returns the admitted list, which MAY differ from the submitted one — see the
 * open decision below.
 */
public interface OpenHouseCommitPolicy {
  List<MetadataUpdate> admit(TableMetadata base,
                             List<MetadataUpdate> updates,
                             CommitContext context);
}
```

**Open decision, named because the signature above already takes a side.** A pure
`void admit(...)` cannot express what the current path does. `doUpdateSchemaIfNeeded`
(`OpenHouseInternalRepositoryImpl:657-665`) *rewrites* the submitted schema before
comparing or storing it: it normalizes top-level column casing to the casing already in
the table, matched by field id, so a writer that submits `id` for a column named `ID`
does not change the table's casing. Either case-insensitive writes silently regress
over REST — a behaviour change that belongs in §5.10 and is not currently there — or
`admit` returns a rewritten update list, as above, and the shadow compares the
*admitted* list rather than the submitted one. This design takes the second option;
the first is available but must be accepted explicitly, because it is a user-visible
change to what `INSERT` accepts.

**Ownership — stated precisely, because this is where the design's leverage is.**

- **iceberg-core owns the semantics.** `UpdateRequirement#validate(TableMetadata)`
  checks requirements. `MetadataUpdate#applyTo(TableMetadata.Builder)` applies updates
  onto `TableMetadata.buildFrom(base)`. OpenHouse writes neither.
- **OpenHouse owns admission only** — `OpenHouseCommitPolicy` decides whether an
  update list is *allowed* (and, per the decision above, in what normalized form),
  never what it *means*. The existing rules become implementations of it:
  `SchemaValidator`, partition-spec immutability, preserved-`openhouse.*` properties,
  table-type immutability, the RTAS gate, **lock state and lock transitions**
  (§5.3), **staged-table sanity**, and **the `updated.openhouse.policy` merge**
  (§5.2). Seven rules and a rewrite, not five rules.
- **The planner never persists.** It returns a `TableMetadata`; the caller passes it to
  `OpenHouseInternalTableOperations.commit(base, result)`. This keeps C4 the only
  writer and is what makes the server-side shadow (§5.7, S3) possible: a plan can be
  computed and compared without being committed.
- **A closed allow-list.** The planner accepts a fixed set of `MetadataUpdate` types
  and rejects everything else with `UnsupportedClientOperationException`. v1 list:
  `assign-uuid`, `upgrade-format-version`, `add-schema`, `set-current-schema`,
  `add-partition-spec`, `set-default-spec`, `add-sort-order`, `set-default-sort-order`,
  `add-snapshot`, `set-snapshot-ref`, `remove-snapshots`, `remove-snapshot-ref`,
  `set-properties`, `remove-properties`. Rejected in v1: `set-location`,
  `set-statistics`, `remove-statistics`, `set-partition-statistics`,
  `remove-partition-statistics`, `remove-partition-specs`, `remove-schemas`,
  `add-encryption-key`, `remove-encryption-key`. Rationale: OpenHouse owns table
  locations (`BaseStorage.allocateTableLocation`) and `Storage.isPathValid` round-trips
  through them, so `set-location` is a data-loss primitive in client hands; the rest
  have no OpenHouse behaviour behind them yet, and an allow-list makes that visible as
  a 400 rather than as a silently-applied no-op. **This closed list is what makes
  shadow comparison meaningful** — the set of things that can enter the applier is
  finite and enumerable, so a divergence taxonomy over it is exhaustive.
  Consequence to accept: `testRemoveUnusedSpec` and `testRemoveUnusedSchemas` (both in
  `NEEDS_COMMIT_TABLE`) stay disabled until `remove-partition-specs` and
  `remove-schemas` are admitted.

  The allow-list is a set of *types*. The irreversibility guard of §5.8 is **not** an
  intersection with it, because the properties it must enforce are not properties of a
  type — see there.

**Error contract.**

| Condition | Exception | Status | Retriable |
|---|---|---|---|
| a requirement fails to validate | `CommitFailedException` | 409 | yes — client refreshes and retries |
| `assert-create` on an existing table | `AlreadyExistsException` | 409 | no |
| an update is not on the allow-list | `UnsupportedClientOperationException` → `ValidationException` at C2 | 400 | no |
| an update is allowed but violates an OpenHouse rule (incl. a locked table) | `ValidationException` / `InvalidSchemaEvolutionException` | 400 | no |
| the update list is internally inconsistent (e.g. `set-current-schema` naming an id no `add-schema` provided) | `ValidationException` | 400 | no |
| a submitted snapshot is malformed | `org.apache.iceberg.exceptions.BadRequestException` (from `doCommit:438-439`) | 400 | no |
| empty `updates[]`, or updates that change nothing | success, no new metadata written | 200 | — |

The last row matters for parity, and there are **two** guards to preserve, not one:
`OpenHouseInternalRepositoryImpl.save` writes nothing when nothing changed
(`if (schemaUpdated || propsUpdated || …)`), and `TablesServiceImpl.updateNeeded:139`
short-circuits one layer above it, returning the existing `TableDto` without reaching
`save` at all. The planner must preserve both, or every no-op commit produces a
metadata file and an HTS write.

### 5.5 C4 — the HTS optimistic-concurrency seam

**The finding this seam rests on.** There are three conflict checks today, not one, and
only the third is durable:

| Layer | Mechanism | Where |
|---|---|---|
| Service | request `baseTableVersion` vs `openhouse.tableLocation` on the loaded table, compared scheme-lessly | `OpenHouseInternalRepositoryImpl.versionCheck:451-456` |
| Catalog | `commitKey` property vs `base.metadataFileLocation()`; plus a 5-minute replay cache | `OpenHouseInternalTableOperations.abortIfWriterBaseDivergedFromCatalog`, `failIfRetryUpdate` |
| **Storage** | **existing row's `metadataLocation` must equal the submitted `tableVersion`, then a JPA `@Version` lock on `UserTableRow`** | **`UserTableVersionMapper.toVersion` + `UserTablesServiceImpl.putUserTable`** |

The first two are *plugin-protocol* artifacts: they exist because the client declares
its base and the server has to police that declaration. The third is a genuine
compare-and-swap against persisted state and is the only one that linearizes
concurrent writers.

**Signature — unchanged, restated as a contract.**

```java
// services/housetables — UserTablesService
Pair<UserTableDto, Boolean> putUserTable(UserTable userTable);
//  precondition:  userTable.tableVersion equals the existing row's metadataLocation,
//                 or INITIAL_VERSION when no row exists
//  postcondition: row.metadataLocation == userTable.tableLocation,
//                 row.version incremented under the JPA @Version lock
//  error:         EntityConcurrentModificationException
```

**Ownership.** HTS is the **sole** durable linearization point for a table commit.
This design adds no second one — no advisory lock, no `If-Match`, no new version
column. M4 is discharged by *not touching this seam*.

**What changes on the update path: nothing at all.** The token HTS compares is already
server-derived. `OpenHouseInternalTableOperations.doCommit:274-277` sets
`openhouse.tableVersion` from the *inherited* `openhouse.tableLocation` property of the
metadata the server itself loaded, defaulting to `INITIAL_VERSION`; it never reads the
request field. The client's `baseTableVersion` reaches `TablesMapper:70-72`, which maps
it to `TableDto.tableVersion` and to nothing else — in particular never to
`tableLocation` — where the *service-layer* check consumes it and stops. So removing
`baseTableVersion` from the wire deletes the service-layer check (§7.4 correction 3) and
leaves the HTS input byte-identical.

This is a stronger result than "the value is no longer client-supplied", and it is
worth stating in the stronger form, because the weaker form invites a concrete bug.
**Do not name `base.metadataFileLocation()` as the new token.** `versionCheck` compares
scheme-lessly via `InternalRepositoryUtils.getSchemeLessPath`;
`UserTableVersionMapper.toVersion` compares with plain `String.equals` against the
persisted `metadataLocation`, which is stored without a scheme. Substituting a
scheme-bearing `metadataFileLocation()` for the inherited property would produce a
mismatch that the service layer used to normalize away and HTS does not.

**Two consequences that must be designed for, not discovered.**

1. **`COMMIT_KEY` must be absent on REST commits.** `abortIfWriterBaseDivergedFromCatalog`
   only fires when the metadata carries both `snapshotsJsonToBePut` and `commitKey`;
   a REST commit carries neither, so it is a no-op — correct, since the REST commit's
   base *is* the server-loaded base by construction. But `failIfRetryUpdate:662`
   increments `InternalCatalogMetricsConstant.MISSING_COMMIT_KEY` on every commit
   without one. That counter must become path-aware, or it fires on 100% of REST
   traffic and stops meaning anything. Naming it here so it is a design decision and
   not an alert-fatigue incident.
2. **`assert-ref-snapshot-id` is a strictly stronger guard than `baseTableVersion` for
   appends, and strictly weaker for property-only commits.** A stock client appending to
   `main` sends `assert-ref-snapshot-id{ref: "main", snapshot-id: <parent>}`, which
   catches exactly the conflict that matters and *permits* a concurrent property-only
   commit that `baseTableVersion` would have rejected. That is the spec's intended
   behaviour and it is a real behaviour change: some commits that fail today will
   succeed over REST. It is safe (the HTS CAS still linearizes) but it is visible, and
   it is the one behavioural difference shadow mode cannot flag as a divergence because
   it only manifests under concurrency. It is the exception M4 carries, and it is why
   the option-B M4 cell is footnoted rather than unqualified.

### 5.6 C5 — the client catalog seam

**Which `OpenHouseCatalog`.** Two classes share the simple name.
`com.linkedin.openhouse.javaclient.OpenHouseCatalog` (the iceberg-1.5 copy, in
`integrations/java/iceberg-1.5/openhouse-java-runtime`) holds all the behaviour and
**is the class that becomes the router**.
`com.linkedin.openhouse.spark.OpenHouseCatalog` is a one-line subclass that exists only
to give the Spark `catalog-impl` property a stable name; it stays a one-line subclass.

**The constraint, verified.** `IcebergCatalogMapper.toIcebergCatalog` reflectively
unwraps `SparkSessionCatalog.icebergCatalog` → `SparkCatalog.icebergCatalog` →
`CachingCatalog.catalog` and returns a `Catalog`; `GrantRevokeStatementExec` and
`ShowGrantsStatementExec` then pattern-match it as `SupportsGrantRevoke`. A stock
`RESTCatalog` in that slot fails the match and both statements throw
`UnsupportedOperationException`. The constraint is real.

**The constraint is also narrower than the sequencing analysis states.** All **six**
policy execs — `SetRetentionPolicyExec`, `SetSharingPolicyExec`, `SetHistoryPolicyExec`,
`SetReplicationPolicyExec`, `SetColumnPolicyTagExec`, and `UnSetReplicationPolicyExec` —
never touch `IcebergCatalogMapper`. They call `catalog.loadTable(ident)`, match a
`SparkTable` carrying `openhouse.tableId`, and do
`updateProperties().set("updated.openhouse.policy", json).commit()`. They therefore
work over *any* catalog that produces such a `SparkTable` — including a stock
`RESTCatalog` — **provided the server understands `updated.openhouse.policy` on the
commit path, which today it does not**. See §5.2 and §7.4, correction 1. **Only two SQL
surfaces need the shim.**

**Signatures.**

```java
/** The REST leg. Adds the two non-spec surfaces and nothing else. */
public class OpenHouseRESTCatalog
    extends org.apache.iceberg.rest.RESTCatalog
    implements SupportsGrantRevoke, Closeable { }

/** The Spark catalog-impl slot. Internally a router. */
public class OpenHouseCatalog                      // c.l.o.javaclient
    extends BaseMetastoreViewCatalog
    implements Configurable, SupportsNamespaces, SupportsGrantRevoke, Closeable {

  private volatile PlumbingState state;              // C7
  private final Supplier<Catalog> legacyLeg;         // today's WebClient plumbing
  private final Supplier<OpenHouseRESTCatalog> restLeg;  // lazily built (PR #44 §5.1)
  private final CommitShadow shadow;                 // C6
}
```

**The router's surface is not quite unchanged, and the difference is deliberate.**
Today's class declares `Configurable, SupportsNamespaces, SupportsGrantRevoke`; it does
**not** implement `Closeable`. Adding it is a one-method public-surface addition, and it
is required: the router now owns a lazily-built `RESTCatalog` and a bounded shadow
executor, both of which need a deterministic release point. Spark closes catalogs that
implement it; nothing else will.

**Which `Catalog` methods the router overrides.** `BaseMetastoreViewCatalog` supplies a
whole machinery — `buildTable` returning a `BaseMetastoreCatalogTableBuilder`,
`loadTable`, `newTableOps`, `defaultWarehouseLocation`, and the view equivalents — that
assumes *this* class performs the commit. In every REST state that machinery must be
bypassed **wholesale**, not selectively: `buildTable`, `loadTable`, `dropTable`,
`listTables`, `tableExists`, `newTableOps`, `defaultWarehouseLocation`, `buildView`,
`loadView`, `listViews`, `dropView`, and the `SupportsNamespaces` methods each dispatch
on `state` and delegate to the chosen leg. Overriding some and inheriting others is the
failure mode to design against: an inherited `newTableOps` would silently reintroduce
the plugin commit path underneath a REST-state `loadTable`.

**Ownership.**

- `OpenHouseRESTCatalog` owns **only** `updateTableAclPolicies`,
  `updateDatabaseAclPolicies`, `getTableAclPolicies`, `getDatabaseAclPolicies`, backed
  by the existing generated `TableApi` / `DatabaseApi` WebClient. It owns **no commit
  logic**: it must not override `newTableOps`, `buildTable`, `commitTable`, or
  `loadTable`. Any override of those is a design violation, because it would reintroduce
  a bespoke commit path on the client after this whole design removed one.
- The router owns state resolution and shadow dispatch. It owns no protocol.
- Both legs are built **lazily** — PR #44's argument transfers exactly: `RESTCatalog`
  initialization eagerly fetches `/v1/config`, so building it in `initialize()` would
  let a REST bootstrap failure break plugin table operations. Built lazily, a bootstrap
  failure fails only the operation that triggered it, caches nothing, and the next
  operation retries. **The state probe of §5.8 is a deliberate, narrow exception to
  this rule, and is owned by the router rather than by the REST leg.**
- Configuration of the REST leg follows PR #44 §5.2 verbatim: `uri` from the existing
  `uri` property, `header.Authorization` from `auth-token` (not the REST `token`
  property — the OAuth2 session machinery stays out of the loop), identity headers
  mirroring `TablesApiClientFactory`, `prefix` from `/v1/config`. The `trust-store`
  asymmetry PR #44 documents applies here too and is inherited, not re-litigated.

**Error contract.** The router never converts one leg's failure into the other leg's
success: there is **no automatic failover**. A REST commit that fails in `REST_WRITE`
raises the REST exception; it does not silently retry over the plugin. Failover would
make the state machine unobservable and could double-write. Rollback is an operator
action (§5.8), not an exception handler.

**Terminal state — which may never be reached, and that is not a failure.** If every
cohort in a cluster reaches `REST_ONLY`, the router class is deleted and `catalog-impl`
points directly at `OpenHouseRESTCatalog`. The owner constraint makes that an
open-ended *if*: the router is not scaffolding with a scheduled end, it is a component
that must be maintainable indefinitely. Nothing in this design may be justified by the
router's eventual deletion.

### 5.7 C6 — the shadow-comparison seam

Shadow mode answers one question: *does the REST rendering of this operation produce
the same table state as the plugin rendering?* It must answer it on real traffic,
without writing twice and without risking the real operation — and, per M7, it must go
on answering it for as long as both plumbings exist.

**Three shadows, because the question has three parts.**

| | Where | Authoritative path | Shadow action | Proves |
|---|---|---|---|---|
| **S1 request shadow** | client router | plugin | render the same operation as `requirements[]`+`updates[]`; **apply the rendered updates to the loaded base locally** via `TableMetadata.Builder`; compare the resulting `TableMetadata` to the plugin's target metadata under the normalization below; send nothing | the client-side mapping (§5.2) is total and lossless |
| **S2 read-back shadow** | client router | plugin | after a successful commit, `GET /v1/{prefix}/namespaces/{ns}/tables/{t}` and compare the returned `TableMetadata` to the plugin's post-commit `TableMetadata` | the REST **read** rendering of live post-commit state agrees; this is the read-only parallel call |
| **S3 applier shadow** | server, inside the `/v1` document route | today's `save` | additionally build the update list and run `TableCommitPlanner.plan(...)`; compare `plan.result()` to what `save` produced; **do not commit the plan** | the applier (C3) is equivalent to `save` — on 100% of existing production traffic, with no client change (discharges S2 of §2) |

**S1's oracle is stated deliberately, because the obvious version has none.** The
plugin leg renders a `CreateUpdateTableRequestBody`; the REST leg renders
`requirements[] + updates[]`. Those are different document types with no natural
correspondence, and the divergence taxonomy below is defined only over normalized
`TableMetadata`. Comparing the two documents directly cannot produce a divergence, so
the state's promotion condition would be satisfied by construction and the first gate
of the state machine would be vacuous. Applying the rendered updates locally and
comparing the *result* reuses the same normalization as S2 and S3, and gives all three
stages one comparison function.

S3 is the most valuable and it is available *first*, before any REST wire surface
exists. That is the point of entering C3 from both front doors.

**Signature.**

```java
public interface CommitShadow {
  /** Never called before the authoritative operation returns. Never throws. */
  void observe(ShadowSubject subject);
}

public final class ShadowSubject {
  TableIdentifier   table();
  ShadowKind        kind();               // S1 | S2 | S3
  Operation         operation();          // CREATE | COMMIT | STAGED_CREATE | DROP
  TableMetadata     authoritativeResult();
  TableMetadata     shadowResult();
  List<MetadataUpdate>     updates();     // the admitted list (§5.4)
  List<UpdateRequirement>  requirements();
  Duration          authoritativeLatency();
}
```

**Ownership.** The shadow observer owns comparison and emission and **nothing that can
affect the authoritative operation**. Concretely: it runs on a bounded single-thread
executor with a bounded queue; a full queue drops the sample and increments
`shadow.dropped`; every exception inside `observe` is caught and counted as
`SHADOW_ERROR`. It performs no write on any path. `observe` returning is not a
precondition of anything.

**Sampling, because the cost is now permanent.** Shadow comparison is a standing
invariant check, not a migration instrument that gets deleted, so its per-commit cost
is a permanent cost. Each shadow carries a configurable sampling rate, applied by
table-identifier hash so that a given table is consistently sampled or not (a random
per-commit coin makes a per-table divergence intermittent and much harder to
localize). Rate `1.0` is the right setting while a state is being evaluated; a
materially lower rate is the right setting for a state a cohort has settled into. The
coverage gauge below is what makes a reduced rate safe to reason about: it reports what
was actually observed, not what was configured.

**What is compared.** `TableMetadata` normalized by dropping a fixed volatile set,
then compared field-wise:

- **Dropped (volatile or path-specific by construction, never a divergence):**
  `metadata-location`, `last-updated-ms`, the metadata log, and the properties
  `openhouse.tableLocation`, `openhouse.tableVersion`, `openhouse.lastModifiedTime`,
  `commitKey`, `snapshotsJsonToBePut`, `snapshotsRefs`, `evolved.table.schema`,
  `client.table.schema`, `newIntermediateSchemas`, `sortOrder`, `isStageCreate`,
  `isStageReplace`, `isReplaceCommit`, and every key prefixed `__transient_restore_`
  or `__transient_added_`.

  The second half of that list is not decoration. Each of those keys is stamped or
  stripped on exactly one of the two paths — `doCommit:298-304` removes
  `snapshotsJsonToBePut`, `snapshotsRefs`, `isStageCreate`, `isStageReplace`, and
  `sortOrder` from the properties map before building the metadata to commit, and the
  transient prefixes are restore/append bookkeeping — so leaving any of them in the
  compared set produces a guaranteed divergence on every commit of that shape, which
  would block every forward transition for a reason that has nothing to do with
  correctness.
- **Compared:** `format-version`, `table-uuid`, `location`, `last-column-id`,
  `last-sequence-number`, `schemas` (by id) and `current-schema-id`, `partition-specs`
  and `default-spec-id`, `sort-orders` and `default-sort-order-id`, `snapshots` (by
  snapshot id), `refs`, and `properties` minus the dropped keys.

**What a mismatch means.** Four severities, because "mismatch" is not one thing:

| Class | Definition | Meaning | Action |
|---|---|---|---|
| `IDENTICAL` | normalized forms equal | the plumbing agrees | counted |
| `BENIGN` | differs only inside the dropped set | expected; the two paths stamp different metadata file names and different path-local bookkeeping | counted, never alerted |
| `DIVERGENT` | differs in a compared field | **the mapping is wrong.** Blocks every forward transition | counted, logged WARN, alerted |
| `SHADOW_ERROR` | the shadow threw or timed out | says nothing about correctness; says something about the shadow's own availability | counted separately; must never be read as evidence of agreement |

The `SHADOW_ERROR` / `DIVERGENT` split is deliberate: a promotion gate that counted
errors as passes would promote on a shadow that never ran.

**What is emitted.**

- One counter per severity, tagged `{kind, operation, path}`, plus for `DIVERGENT` a
  `field` tag drawn from the fixed compared-field enum above — bounded cardinality by
  construction. The tag stays coarse; the detail goes in the log line below.
- One structured WARN per `DIVERGENT`: table identifier, operation, kind, the names of
  the fields that differed, **and, within each differing field, the identifiers of the
  differing members** — the property *key* names, the schema ids, the spec ids, the
  sort-order ids, the snapshot ids, the ref names. **Never the values**: schemas,
  property values, and locations carry user data, and M10 applies to logs as much as to
  responses. Key names are already safe to emit by this document's own reasoning — §5.1
  requires `checkIfPreservedTblPropsModified`'s message to be reduced *to key names*
  precisely because key names are not user data.

  Without this, a divergence names a *field*, so a property divergence reports
  `properties` and nothing else. One unexplained divergence at 0.1% then blocks a
  cohort permanently with no way to find the key responsible, and the gate has no
  clearing mechanism — a state that cannot be exited is worse than a state that is
  never entered.
- A gauge of shadow coverage (`observed / authoritative`) per path, so a promotion
  decision can distinguish "zero divergences" from "zero samples" — and, with sampling
  enabled, from "zero samples taken".

**Promotion gate.** A cohort may advance a state only when, over the state's evaluation
window: `DIVERGENT == 0`, coverage ≥ the configured floor, and `SHADOW_ERROR` rate
below the configured ceiling. **The window is defined by evidence accumulated, not by
time elapsed** — enough sampled commits of each operation shape to make the coverage
floor meaningful. There is no soak period, because there is no bounded canary. This is
the mechanism by which M7 "prove[s] the plumbing is correct before anything depends on
it".

### 5.8 C7 — the plumbing state machine

Per catalog instance. **Every state below is a state the system may occupy
indefinitely**, and several cohorts may occupy different states at once, writing the
same tables, for an unbounded period.

| State | Reads | Writes | Shadow active | What is true in this state |
|---|---|---|---|---|
| `PLUGIN_ONLY` | plugin | plugin | none | today's behaviour, byte-identical. Zero REST traffic. |
| `SHADOW_REQUEST` | plugin | plugin | S1 | every write is rendered both ways, applied locally, and compared; still zero REST traffic. A REST-side outage is invisible here. |
| `SHADOW_READBACK` | plugin | plugin | S1 + S2 | the REST read path is exercised on live post-commit state. First state where a REST outage is user-visible — as an extra request, never as a failure (S2 failures are `SHADOW_ERROR`). Its permanent cost is one extra read per sampled commit. |
| `REST_READ` | REST | plugin | S2 inverted (plugin read-back) | `loadTable`/`listTables` are authoritative over REST; every write still goes through the proven plugin path. A REST read regression is caught before any write moves. |
| `REST_WRITE` | REST | REST | plugin read-back | REST commits are authoritative. The plugin leg is still constructed and reachable. The **cluster-scoped commit restriction** is on (below). |
| `REST_ONLY` | REST | REST | none | plugin leg no longer constructed; `SupportsGrantRevoke` is served by `OpenHouseRESTCatalog`. The commit restriction does **not** lift here — see below. |

**Where the state comes from, and why it is a probe rather than an initialization
step.** Resolution order, first match wins:

1. `GET /v1/config` → `overrides["openhouse.plumbing"]` — the cluster/cohort lever.
2. `spark.sql.catalog.<name>.openhouse.plumbing` — the session's request.
3. default `PLUGIN_ONLY`.

`overrides`, not `defaults`, and this is the whole point: the spec applies `overrides`
*after* client configuration (`spec/iceberg-rest-catalog-open-api.yaml:105-107`), so a
cluster can force a cohort backwards regardless of what a session asked for. `defaults`
would let a stale Spark config win, which would make rollback advisory. PR #34 already
emits a non-empty `overrides` map (it carries `prefix`), so the lever costs one map
entry.

**The defect this replaces.** Resolving state once at catalog initialization would
require an eager `GET /v1/config` — exactly what §5.6 forbids, because a REST bootstrap
failure would then break plugin table operations in catalogs that send zero REST
traffic. Deferring resolution instead makes the server-side lever unreadable in
precisely the states an operator would want to roll back from. Both horns are
unacceptable, and the owner constraint removes a third option — "roll back by
restarting sessions" — because a restart takes a long time and may never complete.

**The resolution, stated as a contract:**

- **Owner:** the router, not the REST leg. It is a plain HTTP `GET` against the
  configured `uri`, issued with the same identity headers, and it does **not**
  construct a `RESTCatalog`. A failure here therefore cannot fail a plugin operation.
- **Trigger:** refreshed on a fixed interval and on `updateAuthToken`; never on the
  commit path synchronously.
- **Bounded:** a short connect and read timeout, one attempt per interval, no retry
  storm. The probe's own latency is never on a user operation's critical path.
- **Fail-open, with a stated fallback order:** on probe failure the router **holds the
  last successfully resolved state**; if none has ever been resolved, it uses
  `PLUGIN_ONLY`. A config outage therefore degrades toward the path that is known to
  work, and never fails an operation.
- **Propagation bound:** a change to `overrides["openhouse.plumbing"]` takes effect
  within one refresh interval plus one in-flight operation, **without a restart**. That
  bound is the rollback SLA, and it is the only rollback lever this design has. It must
  be configured short enough that an operator is willing to rely on it, and long enough
  that the probe is not a load source; the number is configuration, the bound is
  design.
- **Narrow exception, stated as such:** this is the one place a `/v1/config` call
  happens outside a REST operation. It is justified by being router-owned, bounded, and
  fail-open — three properties that the eager-`RESTCatalog` construction §5.6 rejects
  does not have.

**Forward transition conditions.** All three must hold. **None of them is a duration.**

1. `DIVERGENT == 0` on the shadow that state exercises, at or above the coverage floor,
   over an evidence window (§5.7);
2. the reference-test gate for the endpoints the next state depends on is green **with
   executed assertions** — the named `@Disabled` deletions of §5.9, counted as tests
   that actually ran, not tests that were skipped by a capability flag;
3. an explicit operator change to `overrides`. No automatic promotion.

The previous version of this list began "the cohort has held the current state for its
soak window". That condition is deleted: there is no bounded canary, so elapsed time
carries no information and a time-based gate would either block forever or pass
vacuously.

**Rollback.** Every state's predecessor is reachable by changing one server-side map
entry, and takes effect within the propagation bound above. Rollback is never automatic
and never an exception handler (§5.6), and it never requires a restart (M9).

**Rollback from `REST_WRITE` needs a restriction, and the restriction is not what an
earlier draft said it was.** Data is always safe — a table written over REST is an
ordinary OpenHouse table with the same HTS row and the same `metadata.json`, so M9
holds. What is *not* automatically recoverable is a table whose metadata contains a
construct the whole-document protocol cannot express: several schemas added in one
commit, an explicit `remove-snapshots` that the document protocol can only render as a
shorter list, or a sort order that lives in `metadata.sort-orders` rather than in the
`sortOrder` property (§5.10).

> **Plugin-expressibility restriction.** While **any** cohort in a cluster is on a
> plugin-writing state, the server rejects (400) any commit whose *plan* is not
> plugin-expressible, on every path.
>
> **It is a predicate over the whole planned commit, not a set of allowed update
> types.** `OpenHouseCommitPolicy` evaluates it against the admitted update list
> *together with* the resulting `TableMetadata`: "at most one `add-schema` in this
> commit", "no `remove-snapshots` whose removals the document protocol could not
> reproduce as a shorter list", "the resulting `sort-orders` is expressible in the
> `sortOrder` property". Those are properties of the list's multiplicity and of the
> resulting metadata; none of them is expressible as membership in a set of
> `MetadataUpdate` types, so intersecting the §5.4 allow-list with a "plugin-expressible
> subset" cannot implement it.
>
> **It is scoped to the cluster, not to the cohort.** States are per catalog instance;
> tables are global. A cohort-scoped restriction would let the first cohort to reach
> `REST_ONLY` write plugin-inexpressible metadata into tables that other cohorts still
> write through the plugin, and the rollback-safety claim would not survive that shared
> table. The table-property pin (below) is explicitly not a defence: it is an assertion
> read after load, not a router, and it does not exist at create time.
>
> **It lifts only when every cohort in the cluster has reached `REST_ONLY`** — which,
> under the owner constraint, may be never.

**This is the state whose safety previously rested on being brief, and it is now a
defect to be named rather than absorbed.** The restriction was originally justified as
costing "a few stock-client commit shapes during the window". There is no window. For
as long as any cohort remains on the plugin — an unbounded period — a stock Iceberg
`RESTCatalog` is refused a small set of commits it is entitled to make, which is a
standing, partial violation of **M2**. "We will finish the rollout soon" is not
available as an argument. The three honest options are: accept the standing restriction
and document the refused shapes in the client-facing error message; make the document
protocol able to express them (which is work in the opposite direction of S1); or accept
that rollback out of `REST_WRITE` is not fully safe for tables that used those shapes
and say so. This design takes the first, and flags it as the one requirement it
knowingly does not fully meet. Open question 4 records it as an owner decision.

**The table-property pin is an assertion, not a router.** `openhouse.plumbing` on a
table causes the server to reject a REST commit to a `plugin`-pinned table, and the
router to refuse a plugin commit to a `rest`-pinned table. It never selects a path: a
property can only be read after the table has already been loaded through one of the
two paths, and it does not exist at all for create. Per-table safety without per-table
routing — and, per the restriction above, not a substitute for cluster scope.

### 5.9 Migration off the adapter, and the tests each step re-enables

| Step | What lands | Front door | Named `@Disabled` entries removed |
|---|---|---|---|
| **M0** | C3 (planner, policy, allow-list, the seven admission rules) + S3 applier shadow; `save` also builds the update list, compares, and **commits its own result** | `/v1` only | none — this step is invisible |
| **M1** | `save` commits the *planner's* result; S3 shadow retained as a standing check. The document route is now a translator | `/v1` only | none |
| **M2a** | C1/C2 `createTable`, including `stage-create`; **`skipDefaultInterface` flipped to `true`** (§5.1) | REST | the 13 `NEEDS_CREATE_TABLE`: `testBasicCreateTable`, `testBasicCreateTableThatAlreadyExists`, `testCompleteCreateTable`, `testCreateTableWithDefaultColumnValue`, `testDefaultTableProperties`, `testOverrideTableProperties`, `testLoadTable`, `testLoadMetadataTable`, `testLoadTableWithMissingMetadataFile`, `testUUIDValidation`, `createTableInUniqueLocation`, `testTableNameWithDot`, `testTableNameWithSlash` |
| **M2b** | C1/C2 `updateTable` — the single commit route, which is also where **every** staged create is completed and where **every** replace transaction commits (`UpdateType.REPLACE` requirements against a server-loaded base) | REST | 18 of the 20 `NEEDS_COMMIT_TABLE` **plus all 29 `NEEDS_TRANSACTIONS`** — see the split below |
| **M2c** | C1/C2 `dropTable` | REST | 3 of the 4 `NEEDS_DROP_TABLE`: `testDropTable`, `testDropTableWithoutPurge`, `testDropMissingTable` |
| **M3** | C5 router + `OpenHouseRESTCatalog`, C6 shadows, C7 state probe; cohorts walk `SHADOW_REQUEST` → `REST_WRITE` at whatever pace the evidence allows | both | none (client-side) |
| **M4** | `/v1` `PUT /tables` and `PUT /snapshots` deprecated; `baseTableVersion`, `stageCreate`, `stageReplace`, `replaceCommit`, `newIntermediateSchemas`, `clusterId` removed from the request body | `/v1` retained for Spark 3.1 (W6) and replication (W5) | none |
| **M5** | router class deleted; `catalog-impl` = `OpenHouseRESTCatalog` | REST | none |
| **Later, optional** | `POST /v1/{prefix}/transactions/commit` for multi-table clients | REST | **none — this route gates no reference test** (§5.1) |

M4 and M5 are conditional on every cohort having left the plugin, which the owner
constraint makes indefinite. Neither is a scheduled step, and nothing earlier may
depend on them.

**Where the 29 `NEEDS_TRANSACTIONS` tests actually land.** They do not need a
transactions endpoint. Eleven are create transactions, which need M2a's `stage-create`
*and* M2b's commit route; eighteen are replace transactions, which need M2b alone. All
29 complete at M2b, the later of the two steps.

- *Create transactions (M2a + M2b):* `testCreateTransaction`,
  `testCompleteCreateTransaction`, `testCompleteCreateTransactionMultipleSchemas`,
  `testCompleteCreateTransactionV2`, `testConcurrentCreateTransaction`,
  `testCreateOrReplaceTransactionCreate`, `testCompleteCreateOrReplaceTransactionCreate`,
  `testCreateOrReplaceTransactionConcurrentCreate`,
  `testDefaultTablePropertiesCreateTransaction`,
  `testOverrideTablePropertiesCreateTransaction`, `createTableTransaction`.
- *Replace transactions (M2b):* `testCreateOrReplaceReplaceTransactionReplace`,
  `testCompleteCreateOrReplaceTransactionReplace`, `testReplaceTransaction`,
  `testCompleteReplaceTransaction`, `testReplaceTransactionRequiresTableExists`,
  `testReplaceTableKeepsSnapshotLog`, `testConcurrentReplaceTransactions`,
  `testConcurrentReplaceTransactionSchema`, `testConcurrentReplaceTransactionSchema2`,
  `testConcurrentReplaceTransactionSchemaConflict`,
  `testConcurrentReplaceTransactionPartitionSpec`,
  `testConcurrentReplaceTransactionPartitionSpec2`,
  `testConcurrentReplaceTransactionPartitionSpecConflict`,
  `testConcurrentReplaceTransactionSortOrder`,
  `testConcurrentReplaceTransactionSortOrderConflict`,
  `testDefaultTablePropertiesReplaceTransaction`,
  `testOverrideTablePropertiesReplaceTransaction`, `replaceTableTransaction`.
- The 18 `NEEDS_COMMIT_TABLE` at M2b: `testUpdateTableSchema`,
  `testUpdateTableSchemaServerSideRetry`, `testUpdateTableSchemaConflict`,
  `testUpdateTableSchemaAssignmentConflict`, `testUpdateTableSchemaThenRevert`,
  `testUpdateTableSpec`, `testUpdateTableSpecServerSideRetry`,
  `testUpdateTableSpecConflict`, `testUpdateTableAssignmentSpecConflict`,
  `testUpdateTableSpecThenRevert`, `testUpdateTableSortOrder`,
  `testUpdateTableSortOrderServerSideRetry`, `testUpdateTableOrderThenRevert`,
  `testAppend`, `testConcurrentAppendEmptyTable`, `testConcurrentAppendNonEmptyTable`,
  `testUpdateTransaction`, `testMetadataFileLocationsRemovalAfterCommit`.

**Total un-`@Disabled` by this design: 63. Total that will actually execute
assertions: 55.** Eight of the 63 are `assumeTrue`-guarded on capability flags that
PR #34 sets `false`; removing `@Disabled` turns them from *disabled* into *skipped*,
and a promotion gate that reads a skip as green would promote on evidence that was
never gathered.

| Test | Guarded on | Declared at |
|---|---|---|
| `testUpdateTableSchemaServerSideRetry` | `supportsServerSideRetry` | `OpenHouseIcebergRestCatalogTests.java:148-151` |
| `testUpdateTableSpecServerSideRetry` | `supportsServerSideRetry` | same |
| `testUpdateTableSortOrderServerSideRetry` | `supportsServerSideRetry` | same |
| `testConcurrentReplaceTransactionSchemaConflict` | `supportsServerSideRetry` | same |
| `testConcurrentReplaceTransactionPartitionSpecConflict` | `supportsServerSideRetry` | same |
| `testCreateOrReplaceTransactionConcurrentCreate` | `supportsServerSideRetry` | same |
| `testTableNameWithSlash` | `supportsNamesWithSlashes` | `:154-157` |
| `testTableNameWithDot` | `supportsNamesWithDot` | `:160-163` |

**Which flags this workstream flips, and which it does not.**
`supportsNamesWithSlashes` / `supportsNamesWithDot` belong to the namespace-encoding
one-way door (W2) and stay `false` here. `supportsServerSideRetry` is a **real product
decision this workstream owns**: it asserts that the server retries a commit internally
on a requirement failure, and OpenHouse today forces `COMMIT_NUM_RETRIES` to `"0"`
(`OpenHouseInternalRepositoryImpl:203-207`). Flipping it means changing that, and the
three concurrency tests it gates —
`testConcurrentReplaceTransactionSchemaConflict`,
`testConcurrentReplaceTransactionPartitionSpecConflict`,
`testCreateOrReplaceTransactionConcurrentCreate` — are exactly the behaviour partition
that the conflict-semantics change of §5.5 governs, and exactly the partition §5.5
consequence 2 admits shadow mode cannot observe. Leaving the flag `false` means the
only mechanism that could have caught a conflict-semantics regression is switched off.
Recorded as open question 5.

**Consequently, the §5.8 gate reads executed assertions, not a green run.** A step is
delivered when its named tests *ran*; a skip is reported alongside `SHADOW_ERROR` as
absence of evidence, never as agreement.

**Explicitly not re-enabled, with reasons:**

- `testRemoveUnusedSpec`, `testRemoveUnusedSchemas` (2, `NEEDS_COMMIT_TABLE`) — need
  `remove-partition-specs` / `remove-schemas`, excluded from the v1 allow-list (§5.4).
- `testDropTableWithPurge` (1) — W4.
- `testListTables` (`NEEDS_CREATE_TABLE_AND_LIST_FIX`) — createTable removes half its
  blocker; the empty-`pageToken` 400 remains. Reclassify its reason rather than delete
  it.
- The 19 namespace tests (W2), 5 rename + 2 register + 1 metrics (W3).
- `testLoadTableWithNonExistingNamespace` (1) — carries PR #34's
  `BLOCKED_IDENTIFIER_CHARSET` label, **which misnames its own defect**. The reason
  string itself says the namespace "is rejected with 400 (IllegalArgumentException)
  instead of 404 (NoSuchTableException)". That is an error-*mapping* defect, not a
  charset restriction, and its fix is the `NoSuchTableException` row of §5.1's error
  table — which this design owns and is rewriting. It is re-enabled by that row, not
  deferred to another workstream. Relabel it.

`testTableNameWithDot` and `testTableNameWithSlash` are listed as un-disabled at M2a
because `createTable` unblocks their *setup*. They will then skip on their capability
flags (above) and never reach a decision about identifier legality, so there is no
fallback reason to assign them.

### 5.10 Named risks this design creates

Six, each stated so review can accept or reject them rather than discover them.

1. **Subtractive → additive snapshot semantics.** Today's server derives adds and
   deletes by differencing the client's whole submitted snapshot list against the loaded
   metadata; the REST path expresses them as explicit `add-snapshot` and
   `remove-snapshots`. Snapshot *expiry* is the case where the two can disagree: a
   document commit that simply omits expired snapshots becomes, in the update list, a
   `remove-snapshots` that has to be constructed. If it is not constructed, expiry
   silently stops working. This is exactly what S3 catches, and it is the strongest
   argument for landing the applier under the existing API first.

   **Where that diff actually lives, and what happens to it.** Not in
   `SnapshotsUtil.symmetricDifferenceSplit` — that method's only caller is its own unit
   test. The live diff is `OpenHouseInternalTableOperations.doCommit:314-350`, which is
   *below* the C4 seam this document declares unchanged. It is keyed on the presence of
   the `snapshotsJsonToBePut` property (`:298`), which a REST commit never carries, so
   the whole block **goes dormant on the REST path** while staying live for the legacy
   front door. That is the intended outcome, but it means the snapshot delta moves from
   below C4 to above it, and the dormancy is a silent condition — nothing fails if the
   REST path forgets to construct the removals.
2. **Sort order lives in a property, not in metadata.** `doUpdateSortOrderIfNeeded`
   persists `SortOrderParser.toJson(...)` into the `sortOrder` property
   (`CatalogConstants.SORT_ORDER_KEY`), which `doCommit:304-312` then pops and applies;
   the spec puts it in `metadata.sort-orders` with `default-sort-order-id`. Existing
   tables therefore carry sort order in a place a stock client does not read. Either the
   applier keeps writing both during the migration, or `LoadTableResponse` synthesizes
   the sort order from the property on read. Both are implementation choices; the
   *contract* obligation is that a table written by the plugin and read by a stock REST
   client reports the same sort order. S2 is the shadow that proves it.
3. **`assert-ref-snapshot-id` permits commits `baseTableVersion` rejected.** §5.5,
   consequence 2. Behaviourally correct per the spec, invisible to shadow mode, and the
   disclosed exception in M4. Worth an explicit sign-off.
4. **Partition-spec re-expression is strictly stronger than the rule it replaces.**
   Today's `checkPartitionSpecEvolution:639-648` compares only partition *column names*
   (`arePartitionColumnNamesSame:1019-1023`, an element-wise comparison of
   `PartitionField::name`). A change of *transform* on the same columns — a different
   bucket count, a different date granularity that keeps the field name — passes today.
   The §5.2 rule ("reject `add-partition-spec` when the added spec is not equivalent to
   the current default") would newly reject it. That is arguably the correct rule, but
   it is a behaviour change on the `/v1` path too, since M1 routes both front doors
   through the same policy, and it will surface as commits that used to succeed and
   stop succeeding. Either keep the name-only comparison in the re-expressed rule, or
   accept and announce the tightening.
5. **Schema case-normalization has no home in a pure admission check.** §5.4's open
   decision. If `admit` stays pure, case-insensitive writes silently regress over REST.
6. **`updated.openhouse.policy` becomes a server-side concept for the first time.** The
   merge semantics currently live in ~80 lines of client code
   (`OpenHouseTableOperations.buildUpdatedPolicies:304-390`) that no server has ever
   executed, and `SET POLICY` over a stock REST client depends entirely on porting them
   faithfully. A partial port fails *silently and successfully* — the statement returns
   OK and the policy does not change (§5.2). This is the only field in §5.2 whose
   failure mode is a silent success, and it deserves a dedicated equivalence test
   against the client implementation rather than shadow coverage alone.

---

## 6. Appendix A — background and definitions

**`baseTableVersion`.** A metadata *file path* (`.../metadata/00003-….metadata.json`),
or the sentinel `INITIAL_VERSION`. Compared scheme-lessly by
`InternalRepositoryUtils.getSchemeLessPath` at the service layer, and by plain
`String.equals` at HTS (`UserTableVersionMapper.toVersion`). It appears in three places
with three different meanings: the request field, the `commitKey` property, and HTS's
`tableVersion` column. Untangling those three is §5.5.

**`commitKey`.** `CatalogConstants.COMMIT_KEY` — a table property set by
`OpenHouseInternalRepositoryImpl.save` to the request's `baseTableVersion`, consumed
and stripped inside `doCommit`, and additionally used as a replay-detection key in a
5-minute `Cache`. It never reaches persisted metadata.

**`updated.openhouse.policy`.** A table property written by all six of Spark's policy
execs. Today it is a **client-side patch key**: `OpenHouseTableOperations` folds it into
the `policies` request field via `buildUpdatedPolicies` (`:242`) and strips it from
`tableProperties` before the wire (`:243-246`). **The server has never received it**,
and `BasePreservedKeyChecker` does not treat it as preserved — `IS_OH_PREFIXED` matches
`openhouse.`, not `updated.openhouse.`. Making policies work over REST therefore
requires teaching the server the key, not inventing a new concept for policies. §5.2.

**`openhouse.tableId`, and why the load response cannot be trimmed.** All six policy
execs match `case iceberg: SparkTable if iceberg.table().properties().containsKey(
"openhouse.tableId")` before doing anything, falling through to a thrown
`UnsupportedOperationException` otherwise. They survive a stock `RESTCatalog` only
because `CatalogHandlers.loadTable` returns full metadata with every HTS field stamped
under `openhouse.`. A change that stripped `openhouse.*` from the load response would
break all six statements silently. Stated as a dependency, in §5.2.

**Origin of the 95.** PR #34's `OpenHouseIcebergRestCatalogTests` extends Iceberg's
`CatalogTests` (101 methods); 95 are overridden and `@Disabled` with a reason naming
the missing endpoint, 5 skip themselves through capability flags, 1 passes. Six
capability flags are overridden `false` in that class (`:132`, `:138`, `:144`, `:149`,
`:155`, `:161`); three of them also silently skip tests that this design un-disables
(§5.9).

**Spec structures referenced.** `CreateTableRequest{name, location, schema,
partition-spec, write-order, stage-create, properties}`;
`CommitTableRequest{identifier, requirements[], updates[]}`;
`CommitTransactionRequest{table-changes[]}`; `TableUpdate` (23 variants);
`TableRequirement` (8 variants). All present in
`spec/iceberg-rest-catalog-open-api.yaml` on PR #34; none currently marked
`x-openhouse-support: supported`.

---

## 7. Appendix B — developed alternatives

### 7.1 Option A — edge translation

The REST handler receives `CommitTableRequest`, loads the table, replays the updates
into a `TableMetadata` *locally*, and then serializes that back down into a
`CreateUpdateTableRequestBody` (schema to JSON string, spec to `timePartitioning` +
`clustering`, sort order to a JSON string, `baseTableVersion` from the loaded base) and
calls `TablesService.putTable`.

Attractive because it touches almost nothing. Rejected on four counts. First, the
document is lossy in ways the spec is not: `remove-snapshots` and `remove-snapshot-ref`
have no representation at all, several `add-schema` updates in one commit collapse to
one schema (`newIntermediateSchemas` is replication-only and would have to be
generalized), and `set-snapshot-ref` can only be expressed through the whole-refs-map
field on the snapshots route. Second, it weakens M4 by synthesizing a `baseTableVersion`
the client never sent — the server would be attesting to its own base, which makes the
service-layer check tautological while leaving it in place, the worst of both. Third, it
fails M5: "the plugin is untouched" is precisely the property that leaves the client
half of the contract undesigned, which is unaffordable once mixed operation is
permanent rather than transitional. Fourth, it makes the whole-document body a permanent
ceiling on what a REST client may express, which is the opposite of the migration this
workstream exists to enable.

Worth noting it is not *wrong*, only terminal: it is the fastest way to make some of
the 62 tests pass and the slowest way to finish.

### 7.2 Option C — native applier plus bespoke extension

As B, but `CreateTableRequest` gains `openhouse-policies` and `openhouse-table-type`
fields, and a custom `assert-openhouse-version` `TableRequirement` carries the metadata
file path so the existing service-layer check survives untouched.

Rejected on M1 and M2. A stock `RESTCatalog` cannot produce either extension, so every
OpenHouse client would again need a fork — which is the situation this programme
exists to leave. The custom requirement is also unnecessary: §5.5 shows the durable CAS
does not need a client-supplied token at all, and on the update path never read one.
The extension would preserve a check that should be deleted.

The one thing it gets right, and which option B keeps: OpenHouse concepts do need
somewhere to live. B's answer is reserved `openhouse.*` properties and `/v1/config`,
the same convention PR #44 established for views with `openhouse.source-dialect`.

### 7.3 Option D — stock `RESTCatalog`, drop the extensions

Configure `spark.sql.catalog.oh.catalog-impl=org.apache.iceberg.rest.RESTCatalog` and
delete the plugin. Clean, and it is the eventual shape of `REST_ONLY` minus the ACL
methods.

Rejected on M6. `GRANT`, `REVOKE`, and `SHOW GRANTS` reach the catalog through
`IcebergCatalogMapper`'s reflective unwrap and pattern-match on `SupportsGrantRevoke`;
a stock `RESTCatalog` fails the match and all three statements throw. The alternative —
moving ACLs onto `updateNamespaceProperties` and `set-properties` — is a real option,
but it is a *permissions model* change (ACL grants would become table properties,
readable by anyone who can read the table) and it belongs to the authorization
workstream, not to commit semantics. It also fails M7: with only one leg there is
nothing to compare against, so the switch would have to be taken on faith — and under
the owner's constraint that faith would have to hold indefinitely, since there is no
bounded canary at the end of which it gets validated.

Option D's genuine contribution is the observation that the shim's surface is small.
This design shrinks it further: two SQL surfaces, not eight (§5.6).

### 7.4 Corrections to `rest-support-sequencing.md`

Three claims in the sequencing analysis are wrong or incomplete in ways that change
this design.

1. **§6.1 overstates the client constraint — but the correction cuts both ways.** It
   lists "`GRANT`/`REVOKE`, `SHOW GRANTS`, and `SET POLICY {RETENTION, SHARING, HISTORY,
   REPLICATION, COLUMN TAG}`" as reaching `OpenHouseCatalog` through
   `IcebergCatalogMapper`. `SET POLICY` does not. All five variants and
   `UNSET REPLICATION` call `catalog.loadTable(ident)`, match a `SparkTable` with an
   `openhouse.tableId` property, and commit
   `updateProperties().set("updated.openhouse.policy", …)`. They are ordinary property
   commits and work over any catalog that returns `openhouse.*` in table properties.
   **Consequence for the shim:** it needs only `SupportsGrantRevoke`, for two
   statements.

   **Consequence for policies, stated carefully, because the obvious reading is wrong.**
   It is *not* the case that "policies require no new REST concept because Spark already
   sends them as a property set". The **client** already sends them as a property set;
   the **server must learn to read it**. `updated.openhouse.policy` is consumed
   client-side by `buildUpdatedPolicies` and stripped before the wire, so no OpenHouse
   server has ever seen the key, and `BasePreservedKeyChecker` does not preserve it.
   Over REST there is no client to do the folding. Unless the server admits the key,
   consumes it, and merges it into `policies`, `ALTER TABLE … SET POLICY` becomes a
   stock `set-properties` that persists a JSON blob as a user property, leaves
   `policies` untouched, silently stops retention from applying, and **reports
   success**. §5.2 states the server-side contract.
2. **§3 item 5's "read-only policies is a much smaller v1" is false for tables.** (The
   passage is item 5 of §3, not a §3.5.) It is true for views, where there is no
   installed base. For tables, making policies read-only over REST breaks
   `ALTER TABLE … SET POLICY` the moment a cohort reaches `REST_WRITE`, because that
   statement *is* a property commit (correction 1). Policies must be settable over REST
   in v1 — and, unlike what correction 1 was previously read to imply, that costs a
   named piece of server work, not nothing.
3. **§3's account of conflict detection is incomplete.** "detects conflict by comparing
   `baseTableVersion` … against the current head" describes only the service-layer check.
   There are three (§5.5), and the durable one is HTS's `metadataLocation` compare plus
   the JPA `@Version` lock — which is *not* a file-path comparison in any meaningful
   sense (the path is the CAS token, but the linearization is the row lock). This
   matters because §3's step 1 says the work is "wiring [iceberg-core primitives] to
   HTS's optimistic-concurrency version column rather than to a file-path comparison."
   The version column is already wired, does not move, and on the update path is not
   even fed from the client's field. The work is removing the two *protocol-level*
   checks that exist only because the client declares its own base.

One thing the sequencing analysis gets exactly right and this design depends on:
landing the applier underneath the current API first (§3, "Sequencing note"). §5.7's
S3 shadow is the mechanism that makes that step provable rather than merely plausible.

---

## 8. Open questions

Each carries a recommended default so no downstream work is blocked on an answer.

1. **Minimum client Iceberg version eligible for REST writes.** *Default:* the same
   floor the read facade takes (≥1.6, for `/v1/config` endpoint advertisement and the
   pagination fix), with Spark 3.1 / Iceberg 1.2 permanently exempt — which is W6's
   assumption and is itself unratified.
2. **Confirmation of W6.** Does Spark 3.1 / Iceberg 1.2 stay on the plugin
   permanently? The sequencing analysis says "almost certainly" and lists it as a
   decision needed before its Phase 1 ends. *Default:* yes. It is listed separately from
   question 1 because it is the larger scoping decision of the two.
3. **Cohort granularity for C7, and where the plugin-expressibility restriction is
   enforced.** *These are one decision, not two.* Enforcing the restriction server-side
   requires the server to know a request's cohort, which is only well-defined if cohorts
   are per *cluster*; a per-application or per-user cohort would make the restriction
   unenforceable at the only place a stock client can be constrained. *Default:*
   **cohorts are per cluster**, resolved via `/v1/config` `overrides` with a
   session-level override that `overrides` can always win back, and the restriction is
   enforced **server-side and cluster-wide** (§5.8) — a client in `REST_WRITE` may be
   any stock client and cannot be trusted to self-restrict.
4. **Owner decision: is a standing, partial violation of M2 acceptable?** The
   plugin-expressibility restriction refuses a small set of legal stock-client commits
   for as long as any cohort remains on the plugin — under the owner constraint, an
   unbounded period (§5.8). *Default:* accept it, with the refused shapes named in the
   400's message. The alternatives are extending the document protocol, or accepting
   that rollback out of `REST_WRITE` is unsafe for tables that used those shapes.
5. **Does this workstream flip `supportsServerSideRetry`?** It gates six reference
   tests, three of which are the concurrency behaviour §5.5's change governs and which
   shadow mode admits it cannot observe. Flipping it is a product decision:
   `COMMIT_NUM_RETRIES` is forced to `"0"` today
   (`OpenHouseInternalRepositoryImpl:203-207`). *Default:* leave it `false` for M2a–M2c
   and re-open before the first cohort enters `REST_WRITE`, since that is the first
   state where the untested behaviour becomes reachable.
6. **Does `admit` return a rewritten update list, or do case-insensitive writes
   regress?** §5.4, §5.10 risk 5. *Default:* return a rewritten list; the shadow
   compares the admitted list.
7. **Coverage floors and `SHADOW_ERROR` ceilings for each transition, and the state
   probe's refresh interval.** *Default:* placeholders for the operator to set; the
   gate's *shape* (§5.7) and the propagation bound's *existence* (§5.8) are the design,
   the numbers are configuration. **Soak windows are not among them** — per the owner
   constraint there is no bounded canary, so no transition condition may be expressed
   as elapsed time.

**Closed since the previous revision.**

- *Does the pinned `com.linkedin.iceberg:1.5.2.17` fork ship
  `CatalogHandlers.commitTransaction`?* **Answered: no.** `javap` over the pinned
  artifact confirms the method is absent; upstream added it after 1.5. **The stakes are
  lower than this question implied**, and it is recorded as closed rather than carried
  as a risk: per §5.1 the multi-table transactions route gates no reference test, and
  every single-table transaction — staged create and replace alike — commits on the
  ordinary table route. If and when the multi-table route is implemented, its
  orchestration lives in C2 and C2's contract is unchanged either way.
