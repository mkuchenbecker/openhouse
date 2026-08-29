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
`doUpdateUserPropsIfNeeded`, `doUpdateSnapshotsIfNeeded` (a **subtractive** diff —
`SnapshotsUtil.symmetricDifferenceSplit` computes adds and deletes from the client's
whole submitted snapshot list), `doUpdateSortOrderIfNeeded`, and a policy merge.

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
| Staged create / replace | three booleans (`stageCreate`, `stageReplace`, `replaceCommit`) | `stage-create` plus `POST /v1/{prefix}/transactions/commit` |
| OH-only concepts | first-class body fields (`policies`, `tableType`, `clusterId`) | no home; must become properties, config, or an explicit drop |

The consequence is measurable: of PR #34's 95 disabled reference tests, **62 are the
write path** — 29 `NEEDS_TRANSACTIONS`, 20 `NEEDS_COMMIT_TABLE`, 13
`NEEDS_CREATE_TABLE`. No stock Iceberg client can write to OpenHouse, and none will
until this seam exists.

A second, subtler problem: there is no way to *prove* a new commit plumbing is
equivalent to the old one before depending on it. The current protocol has three
independent conflict checks layered on top of each other (§5.4), and any
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
  client-side patching.
- **M3. Every field the current plugin sends has a demonstrated home.** Each field of
  `CreateUpdateTableRequestBody` and `IcebergSnapshotsRequestBody` maps to a named spec
  structure, a reserved `openhouse.*` property, a `GET /v1/config` entry, or an explicit
  and reviewable decision to drop or defer. §5.2 is the table.
- **M4. Conflict detection is at least as strong as today.** No commit that today
  fails with `CommitFailedException` may succeed over REST. Specifically: the HTS
  `metadataLocation` compare and the `@Version` optimistic lock stay the single durable
  linearization point, and no second one is introduced.
- **M5. Both sides are designed as one contract.** Client and server seams are stated
  together, with the ownership boundary between them explicit at each seam.
- **M6. The SQL extensions keep working throughout.** `GRANT`, `REVOKE`,
  `SHOW GRANTS`, and all `SET POLICY` variants function in every state of the
  migration, including the intermediate ones.
- **M7. Read-only shadow mode.** Before anything depends on the REST plumbing, a
  parallel non-authoritative rendering of the same operation is produced and compared,
  with a defined comparison, a defined severity taxonomy, and defined emissions. Shadow
  mode never performs a second write and never fails the authoritative operation.
- **M8. Switching is a state machine, not a boolean.** States, per-state invariants,
  forward transition conditions, and a rollback path for each state are specified. A
  binary on/off flag is rejected.
- **M9. Rollback is safe for data.** From any state, returning to the previous state
  leaves every table readable and writable by the plugin path.
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
- **S4. Named reference tests.** Each delivered capability names the PR #34 `@Disabled`
  entries it removes (§5.9).

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
- **W6. Spark 3.1 / Iceberg 1.2.** That line stays on the plugin permanently. This
  document treats that as settled (see the sequencing analysis §6.4) and designs no
  compatibility path for it.

### Out of scope

- Multi-level namespaces and the namespace encoding one-way door.
- Views (already spec-shaped; PR #44).
- The facade defects — empty `pageToken`, identifier charset, the token-interceptor
  leak. They gate *other* tests, not these.
- Server-side scan planning, credential vending, and the signing routes.

---

## 3. Options

Options are whole designs — both sides — differing in **where the spec's commit
vocabulary becomes authoritative** and **what sits in the Spark `catalog-impl` slot**.

| | M1 native wire | M2 stock client writes | M3 every field homed | M4 conflict strength | M6 SQL extensions survive | M7 shadow possible | M8/M9 stateful rollback | S1 net code reduction |
|---|---|---|---|---|---|---|---|---|
| **A. Edge translation** — REST handler lowers `CommitTableRequest` into `CreateUpdateTableRequestBody` and calls `TablesService.putTable`; plugin untouched | yes | partial — `remove-snapshots`, multi-`add-schema`, and `set-snapshot-ref` have no document representation | no — the document is the bottleneck, so anything it cannot say is silently dropped | **weaker** — requirements must be re-derived into a `baseTableVersion` the client never sent | yes (plugin untouched) | request-shadow only | boolean-ish; the adapter is permanent | no — adds a second adapter |
| **B. Native applier; the document route becomes the translator** ✅ **recommended** | yes | yes | yes — §5.2 | **equal by construction** — HTS seam unchanged, requirements added on top | yes — `SupportsGrantRevoke` shim + router | all three shadows (§5.7) | yes — §5.8 | yes — one commit representation, `save` becomes a translator |
| **C. Native applier + bespoke spec extension** — as B, plus `openhouse.*` fields on `CreateTableRequest` and a custom `assert-openhouse-version` requirement | **no** — violates M1 | no — a stock client cannot produce the extension | yes, trivially | equal | yes | yes | yes | no — a permanent fork of the spec's models |
| **D. Stock `RESTCatalog` in the Spark slot** — as B server-side; drop the plugin and the SQL extensions outright | yes | yes | yes | equal | **no** — `IcebergCatalogMapper` unwraps to `SupportsGrantRevoke`; `GRANT`/`REVOKE`/`SHOW GRANTS` fail immediately | no — nothing holds both renderings | no — a one-way cutover | yes, but by deleting a working feature |

**Recommendation: option B.** The deciding criterion is **M4 read together with M7**:
B is the only option where the durable conflict seam is provably unchanged (the HTS
`metadataLocation` + `@Version` CAS is untouched) *and* where the same operation exists
in two renderings at the same time, which is what makes shadow comparison possible at
all. A is cheaper today and strictly worse tomorrow — it makes the whole-document body
a permanent bottleneck on what a REST client may express, and it weakens M4 by
synthesizing a conflict token the client never sent. C fails M1 outright. D fails M6
outright.

Sketches for A, C, and D are in Appendix B.

---

## 4. Sketch

```
CLIENT (Spark 3.5 / iceberg-1.5)                    SERVER (services:tables)
─────────────────────────────────                   ──────────────────────────────────

spark.sql.catalog.oh.catalog-impl
  = c.l.o.spark.OpenHouseCatalog                    POST /v1/{prefix}/ns/{ns}/tables
        │                                           POST /v1/{prefix}/ns/{ns}/tables/{t}
        │  C5: router                               DELETE …/tables/{t}
        │  state from /v1/config overrides          POST /v1/{prefix}/transactions/commit
        │       (C7 state machine)                        │  C1: routes (generated iface)
        ├── PLUGIN leg ──────────────┐                    ▼
        │   OpenHouseTableOperations │              IcebergRestCatalogController
        │   → PUT /v1/…/tables       │                    │  HTTP only
        │                            │                    ▼
        └── REST leg ────────────┐   │              OpenHouseIcebergRestApiHandler
            OpenHouseRESTCatalog │   │                    │  C2: authz, identity defaults,
            extends RESTCatalog  │   │                    │      protocol → exception mapping
            + SupportsGrantRevoke│   │                    ▼
              (GRANT/REVOKE/     │   │              TableCommitService        ◄── C3
               SHOW GRANTS only) │   │                    │  requirements.validate(base)
                                 │   │                    │  allow-list check
                    C6: shadow ◄─┴───┘                    │  OpenHouseCommitPolicy.admit(…)
                    compare + emit                        │  updates.applyTo(builder)
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

Three things this picture asserts, each defended below:

1. **C3 is entered from both front doors.** That is what makes the applier
   provable under the existing API before REST depends on it (S2), and what makes the
   legacy protocol shrink to a translator (S1).
2. **C4 does not move.** The base metadata location the server CASes on becomes
   server-derived rather than client-supplied. Nothing else about HTS changes.
3. **The client router holds both legs simultaneously.** That is the only place where
   two renderings of one operation coexist, which is what M7 requires.

---

## 5. Details

### 5.1 C1 — the REST write routes

**Signature.** Spec routes, marked `x-openhouse-support: supported` in
`spec/iceberg-rest-catalog-open-api.yaml`, which is the single source of truth: the
codegen gate in `buildSrc/src/main/groovy/openhouse.iceberg-rest-openapi.gradle`
generates the Spring interface *and* the `IcebergRestOpenHouseSupport.SUPPORTED_ENDPOINTS`
list that `GET /v1/config` advertises. Marking a route supported and not implementing
it is therefore a compile error, not a runtime 501.

| Route | Request | Success | Failure |
|---|---|---|---|
| `POST /v1/{prefix}/namespaces/{ns}/tables` | `CreateTableRequest` | `200` `LoadTableResponse` | `404` `NoSuchNamespaceException` · `409` `AlreadyExistsException` · `400` `IllegalArgumentException` · `403` `ForbiddenException` |
| `POST /v1/{prefix}/namespaces/{ns}/tables/{table}` | `CommitTableRequest` | `200` `LoadTableResponse` | `404` `NoSuchTableException` · `409` `CommitFailedException` · `400` `IllegalArgumentException` / `ValidationException` · `403` · `500` `CommitStateUnknownException` |
| `DELETE /v1/{prefix}/namespaces/{ns}/tables/{table}?purgeRequested=` | — | `204` | `404` `NoSuchTableException` · `400` when `purgeRequested=true` (W4) · `403` |
| `POST /v1/{prefix}/transactions/commit` | `CommitTransactionRequest` | `204` | `409` `CommitFailedException` · `404` · `400` · `403` |

**Ownership.** `IcebergRestCatalogController` owns HTTP and nothing else: it
implements the generated interface, unwraps parameters, and returns
`ResponseEntity`. It contains no validation, no authorization, and no catalog access.
It is the only class allowed to know about `ResponseEntity`.

**Error contract.** Every failure leaves through `IcebergRestExceptionHandler` as an
`IcebergErrorResponse`. Four handlers must be added to the existing five:

| Exception | Status | `type` in the envelope | Client sees |
|---|---|---|---|
| `CommitFailedException` | 409 | `CommitFailedException` | retriable; Iceberg's commit loop refreshes and retries |
| `AlreadyExistsException` | 409 | `AlreadyExistsException` | `TableAlreadyExistsException` |
| `CommitStateUnknownException` | 500 | `CommitStateUnknownException` | commit **not** aborted; metadata files not cleaned up |
| `UnsupportedClientOperationException` | 400 | `UnsupportedClientOperationException` | terminal; message names the rule |

The `500` row is load-bearing and is the one place this design accepts a `5xx` as a
*contract* rather than a bug: on an ambiguous persist outcome the server must not
report failure, because a client that believes a commit failed deletes the metadata
files it wrote. `IcebergRestExceptionHandler`'s current catch-all maps every unhandled
`Exception` to a flat `500 "Internal server error"`; that stays, and it is
indistinguishable from the ambiguous case *to a client*, which is the safe direction.

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
| `schema` (JSON string) | `CreateTableRequest.schema` (structured `Schema`); `AddSchemaUpdate` + `SetCurrentSchemaUpdate` on commit | `SchemaValidator.validateWriteSchema(old, new, uri)` is re-expressed as a check over the `(add-schema, set-current-schema)` pair; `set-current-schema: -1` means "the last added schema" |
| `newIntermediateSchemas` | **the update list itself** — N `add-schema` updates in one commit | the field exists only because the document can carry one schema. REST removes the need; the field is deleted at migration step M4 |
| `timePartitioning` + `clustering` | `CreateTableRequest.partition-spec`; `AddPartitionSpecUpdate` + `SetDefaultSpecUpdate` | `TimePartitionSpecBuilder` / `ClusteringSpecBuilder` / `PartitionSpecMapper` become **read-side only**. `checkPartitionSpecEvolution` becomes a rejection of `add-partition-spec` when the added spec is not equivalent to the current default |
| `sortOrder` (JSON string) | `CreateTableRequest.write-order`; `AddSortOrderUpdate` + `SetDefaultSortOrderUpdate` | today it is persisted as the `sortOrder` *property* (`CatalogConstants.SORT_ORDER_KEY`), not in `metadata.sort-orders`. Named migration risk — see §5.10 |
| `tableProperties` (whole map) | `SetPropertiesUpdate` / `RemovePropertiesUpdate` (a delta) | `checkIfPreservedTblPropsModified` stops diffing two whole maps and becomes: reject a `set-properties`/`remove-properties` whose key set intersects `PreservedKeyChecker`'s preserved space. This is *stronger*, not weaker — a delta names exactly what the client intended to change |
| `policies` | **`SetPropertiesUpdate` on `updated.openhouse.policy`** | no new concept needed: Spark's five `SET POLICY` execs already commit exactly this property (§7.4, correction 1). Recommendation: **policies are settable over REST in v1** |
| `tableType` | `openhouse.tableType` property; server-defaulted to `PRIMARY_TABLE` when absent | `checkIfTableTypeModified` becomes a rejection of a `set-properties` that changes it |
| `stageCreate` | `CreateTableRequest.stage-create: true` | exact spec match; only ever set by `OpenHouseCatalog.OpenHouseTableBuilder.createStagedMetadata` |
| `stageReplace` | a staged `CreateTableRequest` against an existing table, completed by `POST /v1/{prefix}/transactions/commit` carrying `assert-table-uuid` | the spec has no `stage-replace`. `validateReplaceTable`'s RTAS gate (`replace.enabled`, and the WAP/replication exclusions) becomes an admission check on the staged create |
| `replaceCommit` | the transaction commit itself | only ever set by `putSnapshotsForReplace` |
| `baseTableVersion` | **server-derived.** The client-visible conflict contract becomes `requirements[]`: `assert-create`, `assert-table-uuid`, `assert-ref-snapshot-id`, `assert-current-schema-id`, `assert-default-spec-id`, `assert-default-sort-order-id`, `assert-last-assigned-field-id`, `assert-last-assigned-partition-id` | the durable CAS is unchanged (§5.4). This is the single most consequential row in the table |
| `IcebergSnapshotsRequestBody.jsonSnapshots` | `AddSnapshotUpdate`; expiry becomes explicit `RemoveSnapshotsUpdate` | subtractive → additive. Named migration risk — see §5.10 |
| `IcebergSnapshotsRequestBody.snapshotRefs` | `SetSnapshotRefUpdate` / `RemoveSnapshotRefUpdate` | |
| `openhouse.isTableReplicated` | preserved property; not settable over REST | |
| `openhouse.tableUUID` | `assert-table-uuid` on commit; assigned by the server on create | `TableUUIDGenerator`'s manifest-path derivation stays a create-path concern |
| `GetTableResponseBody.config` (read bridge) | `LoadTableResponse.config` | spec-native; already how PR #34 serves reads |
| `GRANT` / `REVOKE` / `SHOW GRANTS` | **no spec home.** Stays on `/v1/tables/{db}/{t}/aclPolicies` and `/v1/databases/{db}/aclPolicies`, reached through `SupportsGrantRevoke` on the client shim (C5) | the only genuinely non-spec surface left after this design |
| soft delete / purge / restore | `DELETE …?purgeRequested=false` → OpenHouse soft delete. `purgeRequested=true` → 400 (W4). Restore stays `/v1`-only | |

### 5.3 C2 — the handler seam

**Signature.** `IcebergRestApiHandler` gains five methods alongside its four read
methods:

```java
LoadTableResponse createTable(String prefix, String namespace,
                              CreateTableRequest request, String dataAccess);

LoadTableResponse updateTable(String prefix, String namespace, String table,
                              CommitTableRequest request);

void dropTable(String prefix, String namespace, String table, Boolean purgeRequested);

void commitTransaction(String prefix, CommitTransactionRequest request);

/* renameTable is W3; listed so the interface's eventual shape is visible. */
```

**Ownership.** The handler owns exactly four things and no others:

1. **Authorization.** `Privileges.CREATE_TABLE` on the database for create;
   `Privileges.UPDATE_TABLE_METADATA` on the table for commit;
   `Privileges.DELETE_TABLE` for drop — the same checks `TablesServiceImpl` applies to
   the `/v1` routes, via the same `AuthorizationUtils`. A REST route must never reach a
   privilege decision the `/v1` route would not have reached.
2. **OpenHouse identity defaulting.** A stock client sends no `clusterId`, no
   `tableCreator`, no `tableUri`, no `tableType`. The handler supplies them from
   cluster config and the authenticated principal, before C3 sees the commit.
3. **Namespace decoding**, reusing PR #34's `decodeSingleLevelNamespace`.
4. **Exception translation** into Iceberg types (below).

The handler explicitly does **not** apply updates, validate requirements, or touch
`TableMetadata`. That is C3's job. This boundary is what lets the `/v1` document route
reach C3 without going through the handler at all.

**Error contract.** The handler is the boundary at which OpenHouse exception types
stop and Iceberg exception types begin:

| In | Out |
|---|---|
| `NoSuchUserTableException` | `NoSuchTableException` |
| `AlreadyExistsException` (OpenHouse) | `org.apache.iceberg.exceptions.AlreadyExistsException` |
| `EntityConcurrentModificationException` | `CommitFailedException` |
| `InvalidSchemaEvolutionException` | `ValidationException` (→ 400) |
| `RequestValidationFailureException` | `IllegalArgumentException` (→ 400) |
| `UnsupportedClientOperationException` | passed through; handled at 400 by C1 |
| `AccessDeniedException` | `ForbiddenException` (→ 403) |
| anything else | propagates to C1's catch-all → 500 |

No Spring type (`WebClientResponseException`, `DataIntegrityViolationException`,
`ObjectOptimisticLockingFailureException`) may cross this boundary.

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

/** OpenHouse admission rules, expressed over update lists rather than documents. */
public interface OpenHouseCommitPolicy {
  void admit(TableMetadata base, List<MetadataUpdate> updates, CommitContext context);
}
```

**Ownership — stated precisely, because this is where the design's leverage is.**

- **iceberg-core owns the semantics.** `UpdateRequirement#validate(TableMetadata)`
  checks requirements. `MetadataUpdate#applyTo(TableMetadata.Builder)` applies updates
  onto `TableMetadata.buildFrom(base)`. OpenHouse writes neither.
- **OpenHouse owns admission only** — `OpenHouseCommitPolicy` decides whether an
  update list is *allowed*, never what it *means*. The five existing rules
  (`SchemaValidator`, partition-spec immutability, preserved-`openhouse.*` properties,
  table-type immutability, the RTAS gate) all become implementations of it.
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

**Error contract.**

| Condition | Exception | Status | Retriable |
|---|---|---|---|
| a requirement fails to validate | `CommitFailedException` | 409 | yes — client refreshes and retries |
| `assert-create` on an existing table | `AlreadyExistsException` | 409 | no |
| an update is not on the allow-list | `UnsupportedClientOperationException` | 400 | no |
| an update is allowed but violates an OpenHouse rule | `ValidationException` / `InvalidSchemaEvolutionException` | 400 | no |
| the update list is internally inconsistent (e.g. `set-current-schema` naming an id no `add-schema` provided) | `ValidationException` | 400 | no |
| empty `updates[]` | success, no new metadata written | 200 | — |

The last row matters for parity: today `save` writes nothing when nothing changed
(`if (schemaUpdated || propsUpdated || …)`). The planner must preserve that, or every
no-op commit produces a metadata file.

### 5.5 C4 — the HTS optimistic-concurrency seam

**The finding this seam rests on.** There are three conflict checks today, not one, and
only the third is durable:

| Layer | Mechanism | Where |
|---|---|---|
| Service | request `baseTableVersion` vs `openhouse.tableLocation` on the loaded table | `OpenHouseInternalRepositoryImpl.versionCheck` |
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

**What changes.** Only the provenance of `tableVersion`. Today it is
`tableDto.getTableVersion()`, which traces back to the client's `baseTableVersion`
field. On the REST path it is `base.metadataFileLocation()` of the metadata the server
itself loaded inside the same request. The compare is identical; the value is no longer
attacker- or bug-supplied.

**Two consequences that must be designed for, not discovered.**

1. **`COMMIT_KEY` must be absent on REST commits.** `abortIfWriterBaseDivergedFromCatalog`
   only fires when the metadata carries both `snapshotsJsonToBePut` and `commitKey`;
   a REST commit carries neither, so it is a no-op — correct, since the REST commit's
   base *is* the server-loaded base by construction. But `failIfRetryUpdate` increments
   `InternalCatalogMetricsConstant.MISSING_COMMIT_KEY` on every commit without one.
   That counter must become path-aware, or it fires on 100% of REST traffic and stops
   meaning anything. Naming it here so it is a design decision and not an alert-fatigue
   incident.
2. **`assert-ref-snapshot-id` is a strictly stronger guard than `baseTableVersion` for
   appends, and strictly weaker for property-only commits.** A stock client appending to
   `main` sends `assert-ref-snapshot-id{ref: "main", snapshot-id: <parent>}`, which
   catches exactly the conflict that matters and *permits* a concurrent property-only
   commit that `baseTableVersion` would have rejected. That is the spec's intended
   behaviour and it is a real behaviour change: some commits that fail today will
   succeed over REST. It is safe (the HTS CAS still linearizes) but it is visible, and
   it is the one behavioural difference shadow mode cannot flag as a divergence because
   it only manifests under concurrency. Flagged as a decision, not a bug.

### 5.6 C5 — the client catalog seam

**The constraint, verified.** `IcebergCatalogMapper.toIcebergCatalog` reflectively
unwraps `SparkSessionCatalog.icebergCatalog` → `SparkCatalog.icebergCatalog` →
`CachingCatalog.catalog` and returns a `Catalog`; `GrantRevokeStatementExec` and
`ShowGrantsStatementExec` then pattern-match it as `SupportsGrantRevoke`. A stock
`RESTCatalog` in that slot fails the match and both statements throw
`UnsupportedOperationException`. The constraint is real.

**The constraint is also narrower than the sequencing analysis states.** All five
`SET POLICY` execs — `SetRetentionPolicyExec`, `SetSharingPolicyExec`,
`SetHistoryPolicyExec`, `SetReplicationPolicyExec`, `SetColumnPolicyTagExec`, plus
`UnSetReplicationPolicyExec` — never touch `IcebergCatalogMapper`. They call
`catalog.loadTable(ident)`, match a `SparkTable` carrying `openhouse.tableId`, and do
`updateProperties().set("updated.openhouse.policy", json).commit()`. They therefore
work over *any* catalog that produces such a `SparkTable` — including a stock
`RESTCatalog` — provided the server understands `updated.openhouse.policy` on the
commit path. See §7.4, correction 1. **Only two SQL surfaces need the shim.**

**Signatures.**

```java
/** The REST leg. Adds the two non-spec surfaces and nothing else. */
public class OpenHouseRESTCatalog
    extends org.apache.iceberg.rest.RESTCatalog
    implements SupportsGrantRevoke, Closeable { }

/** The Spark catalog-impl slot. Unchanged public surface; internally a router. */
public class OpenHouseCatalog
    extends BaseMetastoreViewCatalog
    implements Configurable, SupportsNamespaces, SupportsGrantRevoke, Closeable {

  private volatile PlumbingState state;              // C7
  private final Supplier<Catalog> legacyLeg;         // today's WebClient plumbing
  private final Supplier<OpenHouseRESTCatalog> restLeg;  // lazily built (PR #44 §5.1)
  private final CommitShadow shadow;                 // C6
}
```

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
  operation retries.
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

**Terminal state.** After the last cohort reaches `REST_ONLY`, the router class is
deleted and `catalog-impl` points directly at `OpenHouseRESTCatalog`. The router is
migration scaffolding with a stated end.

### 5.7 C6 — the shadow-comparison seam

Shadow mode answers one question: *does the REST rendering of this operation produce
the same table state as the plugin rendering?* It must answer it on real traffic,
without writing twice and without risking the real operation.

**Three shadows, because the question has three parts.**

| | Where | Authoritative path | Shadow action | Proves |
|---|---|---|---|---|
| **S1 request shadow** | client router | plugin | render the same operation as `requirements[]`+`updates[]`; compare *documents*; send nothing | the client-side mapping (§5.2) is total and lossless |
| **S2 read-back shadow** | client router | plugin | after a successful commit, `GET /v1/{prefix}/namespaces/{ns}/tables/{t}` and compare the returned `TableMetadata` to the plugin's post-commit `TableMetadata` | the REST **read** rendering of live post-commit state agrees; this is the read-only parallel call |
| **S3 applier shadow** | server, inside the `/v1` document route | today's `save` | additionally build the update list and run `TableCommitPlanner.plan(...)`; compare `plan.result()` to what `save` produced; **do not commit the plan** | the applier (C3) is equivalent to `save` — on 100% of existing production traffic, with no client change (discharges S2 of §2) |

S3 is the important one and it is available *first*, before any REST wire surface
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
  Operation         operation();          // CREATE | COMMIT | STAGED_CREATE | TXN_COMMIT | DROP
  TableMetadata     authoritativeResult();
  TableMetadata     shadowResult();       // null for S1, where the comparison is document-level
  List<MetadataUpdate>     updates();
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

**What is compared.** `TableMetadata` normalized by dropping a fixed volatile set,
then compared field-wise:

- **Dropped (volatile by construction, never a divergence):** `metadata-location`,
  `last-updated-ms`, the metadata log, `openhouse.tableLocation`,
  `openhouse.tableVersion`, `openhouse.lastModifiedTime`, `commitKey`,
  `snapshotsJsonToBePut`, `snapshotsRefs`, `evolved.table.schema`,
  `newIntermediateSchemas`.
- **Compared:** `format-version`, `table-uuid`, `location`, `last-column-id`,
  `last-sequence-number`, `schemas` (by id) and `current-schema-id`, `partition-specs`
  and `default-spec-id`, `sort-orders` and `default-sort-order-id`, `snapshots` (by
  snapshot id), `refs`, and `properties` minus the dropped keys.

**What a mismatch means.** Four severities, because "mismatch" is not one thing:

| Class | Definition | Meaning | Action |
|---|---|---|---|
| `IDENTICAL` | normalized forms equal | the plumbing agrees | counted |
| `BENIGN` | differs only inside the dropped set | expected; the two paths stamp different metadata file names | counted, never alerted |
| `DIVERGENT` | differs in a compared field | **the mapping is wrong.** Blocks every forward transition | counted, logged WARN, alerted |
| `SHADOW_ERROR` | the shadow threw or timed out | says nothing about correctness; says something about the shadow's own availability | counted separately; must never be read as evidence of agreement |

The `SHADOW_ERROR` / `DIVERGENT` split is deliberate: a promotion gate that counted
errors as passes would promote on a shadow that never ran.

**What is emitted.**

- One counter per severity, tagged `{kind, operation, path}`, plus for `DIVERGENT` a
  `field` tag drawn from the fixed compared-field enum above — bounded cardinality by
  construction.
- One structured WARN per `DIVERGENT`: table identifier, operation, kind, and **the
  names of the fields that differed**. Never the values: schemas, properties, and
  locations carry user data, and M10 applies to logs as much as to responses.
- A gauge of shadow coverage (`observed / authoritative`) per path, so a promotion
  decision can distinguish "zero divergences" from "zero samples".

**Promotion gate.** A cohort may advance a state only when, over the state's soak
window: `DIVERGENT == 0`, coverage ≥ the configured floor, and `SHADOW_ERROR` rate
below the configured ceiling. This is the mechanism by which M7 "prove[s] the plumbing
is correct before anything depends on it."

### 5.8 C7 — the plumbing state machine

Per catalog instance, resolved at catalog initialization and re-resolved on
`updateAuthToken` and session restart.

| State | Reads | Writes | Shadow active | What is true in this state |
|---|---|---|---|---|
| `PLUGIN_ONLY` | plugin | plugin | none | today's behaviour, byte-identical. Zero REST traffic. |
| `SHADOW_REQUEST` | plugin | plugin | S1 | every write is *rendered* both ways and compared; still zero REST traffic. A REST-side outage is invisible here. |
| `SHADOW_READBACK` | plugin | plugin | S1 + S2 | the REST read path is exercised on live post-commit state. First state where a REST outage is user-visible — as an extra request, never as a failure (S2 failures are `SHADOW_ERROR`). |
| `REST_READ` | REST | plugin | S2 inverted (plugin read-back) | `loadTable`/`listTables` are authoritative over REST; every write still goes through the proven plugin path. A REST read regression is caught before any write moves. |
| `REST_WRITE` | REST | REST | plugin read-back | REST commits are authoritative. The plugin leg is still constructed and reachable. The **irreversibility guard** is on (below). |
| `REST_ONLY` | REST | REST | none | plugin leg no longer constructed; the guard lifts; `SupportsGrantRevoke` is served by `OpenHouseRESTCatalog`. |

**Where the state comes from, and why.** Resolution order, first match wins:

1. `GET /v1/config` → `overrides["openhouse.plumbing"]` — the cluster/cohort lever.
2. `spark.sql.catalog.<name>.openhouse.plumbing` — the session's request.
3. default `PLUGIN_ONLY`.

`overrides`, not `defaults`, and this is the whole point: the spec applies `overrides`
*after* client configuration, so a cluster can force a cohort backwards regardless of
what a session asked for. `defaults` would let a stale Spark config win, which would
make rollback advisory. PR #34 already emits a non-empty `overrides` map (it carries
`prefix`), so the lever costs one map entry.

**Forward transition conditions.** All four must hold:

1. the cohort has held the current state for its soak window;
2. `DIVERGENT == 0` on the shadow that state exercises, at or above the coverage floor;
3. the reference-test gate for the endpoints the next state depends on is green — the
   named `@Disabled` deletions of §5.9;
4. an explicit operator change to `overrides`. No automatic promotion.

**Rollback.** Every state's predecessor is reachable by changing one server-side map
entry and restarting sessions. Rollback is never automatic and never an exception
handler (§5.6).

**Rollback from `REST_WRITE` is the one asymmetric case, and it needs a guard.** Data
is always safe — a table written over REST is an ordinary OpenHouse table with the same
HTS row and the same `metadata.json`, so M9 holds. What is *not* automatically
recoverable is a table whose metadata contains a construct the whole-document protocol
cannot express: several schemas added in one commit, an explicit `remove-snapshots`
that the document protocol can only render as a shorter list, or a sort order that
lives in `metadata.sort-orders` rather than in the `sortOrder` property (§5.10).
Therefore:

> **Irreversibility guard.** While a cohort is in `REST_WRITE`, C3's allow-list is
> intersected with a *plugin-expressible* subset, and the server rejects (400) any
> commit outside it. The guard lifts only at `REST_ONLY`, per cohort. Its cost is that
> a few stock-client commit shapes are refused during the window; its benefit is that
> rollback stays a configuration change rather than a data migration.

**The table-property pin is an assertion, not a router.** `openhouse.plumbing` on a
table causes the server to reject a REST commit to a `plugin`-pinned table, and the
router to refuse a plugin commit to a `rest`-pinned table. It never selects a path: a
property can only be read after the table has already been loaded through one of the
two paths, and it does not exist at all for create. Per-table safety without per-table
routing.

### 5.9 Migration off the adapter, and the tests each step re-enables

| Step | What lands | Front door | Named `@Disabled` entries removed |
|---|---|---|---|
| **M0** | C3 (planner, policy, allow-list) + S3 applier shadow; `save` also builds the update list, compares, and **commits its own result** | `/v1` only | none — this step is invisible |
| **M1** | `save` commits the *planner's* result; S3 shadow deleted. The document route is now a translator | `/v1` only | none |
| **M2a** | C1/C2 `createTable` (incl. `stage-create`) | REST | the 13 `NEEDS_CREATE_TABLE`: `testBasicCreateTable`, `testBasicCreateTableThatAlreadyExists`, `testCompleteCreateTable`, `testCreateTableWithDefaultColumnValue`, `testDefaultTableProperties`, `testOverrideTableProperties`, `testLoadTable`, `testLoadMetadataTable`, `testLoadTableWithMissingMetadataFile`, `testUUIDValidation`, `createTableInUniqueLocation`, `testTableNameWithDot`, `testTableNameWithSlash` |
| **M2b** | C1/C2 `updateTable` | REST | 18 of the 20 `NEEDS_COMMIT_TABLE`: `testUpdateTableSchema`, `testUpdateTableSchemaServerSideRetry`, `testUpdateTableSchemaConflict`, `testUpdateTableSchemaAssignmentConflict`, `testUpdateTableSchemaThenRevert`, `testUpdateTableSpec`, `testUpdateTableSpecServerSideRetry`, `testUpdateTableSpecConflict`, `testUpdateTableAssignmentSpecConflict`, `testUpdateTableSpecThenRevert`, `testUpdateTableSortOrder`, `testUpdateTableSortOrderServerSideRetry`, `testUpdateTableOrderThenRevert`, `testAppend`, `testConcurrentAppendEmptyTable`, `testConcurrentAppendNonEmptyTable`, `testUpdateTransaction`, `testMetadataFileLocationsRemovalAfterCommit` |
| **M2c** | C1/C2 `dropTable` | REST | 3 of the 4 `NEEDS_DROP_TABLE`: `testDropTable`, `testDropTableWithoutPurge`, `testDropMissingTable` |
| **M2d** | C1/C2 `commitTransaction` + staged replace | REST | all 29 `NEEDS_TRANSACTIONS`: `testCreateTransaction`, `testCompleteCreateTransaction`, `testCompleteCreateTransactionMultipleSchemas`, `testCompleteCreateTransactionV2`, `testConcurrentCreateTransaction`, `testCreateOrReplaceTransactionCreate`, `testCompleteCreateOrReplaceTransactionCreate`, `testCreateOrReplaceReplaceTransactionReplace`, `testCompleteCreateOrReplaceTransactionReplace`, `testCreateOrReplaceTransactionConcurrentCreate`, `testReplaceTransaction`, `testCompleteReplaceTransaction`, `testReplaceTransactionRequiresTableExists`, `testReplaceTableKeepsSnapshotLog`, `testConcurrentReplaceTransactions`, `testConcurrentReplaceTransactionSchema`, `testConcurrentReplaceTransactionSchema2`, `testConcurrentReplaceTransactionSchemaConflict`, `testConcurrentReplaceTransactionPartitionSpec`, `testConcurrentReplaceTransactionPartitionSpec2`, `testConcurrentReplaceTransactionPartitionSpecConflict`, `testConcurrentReplaceTransactionSortOrder`, `testConcurrentReplaceTransactionSortOrderConflict`, `testDefaultTablePropertiesCreateTransaction`, `testDefaultTablePropertiesReplaceTransaction`, `testOverrideTablePropertiesCreateTransaction`, `testOverrideTablePropertiesReplaceTransaction`, `createTableTransaction`, `replaceTableTransaction` |
| **M3** | C5 router + `OpenHouseRESTCatalog`, C6 shadows, C7 states; cohorts walk `SHADOW_REQUEST` → `REST_WRITE` | both | none (client-side) |
| **M4** | `/v1` `PUT /tables` and `PUT /snapshots` deprecated; `baseTableVersion`, `stageCreate`, `stageReplace`, `replaceCommit`, `newIntermediateSchemas`, `clusterId` removed from the request body | `/v1` retained for Spark 3.1 (W6) and replication (W5) | none |
| **M5** | router class deleted; `catalog-impl` = `OpenHouseRESTCatalog` | REST | none |

**Total re-enabled by this design: 63** of the 95 (62 write-path plus `testDropTable`'s
group less `testDropTableWithPurge`; precisely, 13 + 18 + 3 + 29 = 63).

**Explicitly not re-enabled, with reasons:**

- `testRemoveUnusedSpec`, `testRemoveUnusedSchemas` (2, `NEEDS_COMMIT_TABLE`) — need
  `remove-partition-specs` / `remove-schemas`, excluded from the v1 allow-list (§5.4).
- `testDropTableWithPurge` (1) — W4.
- `testListTables` (`NEEDS_CREATE_TABLE_AND_LIST_FIX`) — createTable removes half its
  blocker; the empty-`pageToken` 400 remains. Reclassify its reason rather than delete
  it.
- The 19 namespace tests (W2), 5 rename + 2 register + 1 metrics (W3), 1 charset
  (out of scope).
- `testTableNameWithDot` / `testTableNameWithSlash` are listed as re-enabled at M2a
  because `createTable` unblocks their *setup*; they may then fail on the identifier
  charset, which is the facade defect this workstream does not own. If they do, they
  move to the charset reason rather than back to `NEEDS_CREATE_TABLE`.

### 5.10 Named risks this design creates

Three, each stated so review can accept or reject them rather than discover them.

1. **Subtractive → additive snapshot semantics.** Today's server derives adds and
   deletes with `SnapshotsUtil.symmetricDifferenceSplit` over the client's whole
   submitted list; the REST path expresses them as explicit `add-snapshot` and
   `remove-snapshots`. Snapshot *expiry* is the case where the two can disagree: a
   document commit that simply omits expired snapshots becomes, in the update list, a
   `remove-snapshots` that has to be constructed. If it is not constructed, expiry
   silently stops working. This is exactly what S3 catches, and it is the strongest
   argument for landing the applier under the existing API first.
2. **Sort order lives in a property, not in metadata.** `doUpdateSortOrderIfNeeded`
   persists `SortOrderParser.toJson(...)` into the `sortOrder` property
   (`CatalogConstants.SORT_ORDER_KEY`); the spec puts it in `metadata.sort-orders` with
   `default-sort-order-id`. Existing tables therefore carry sort order in a place a
   stock client does not read. Either the applier keeps writing both during the
   migration, or `LoadTableResponse` synthesizes the sort order from the property on
   read. Both are implementation choices; the *contract* obligation is that a table
   written by the plugin and read by a stock REST client reports the same sort order.
   S2 is the shadow that proves it.
3. **`assert-ref-snapshot-id` permits commits `baseTableVersion` rejected.** §5.5,
   consequence 2. Behaviourally correct per the spec, invisible to shadow mode, and
   worth an explicit sign-off.

---

## 6. Appendix A — background and definitions

**`baseTableVersion`.** A metadata *file path* (`.../metadata/00003-….metadata.json`),
or the sentinel `INITIAL_VERSION`. Compared scheme-lessly by
`InternalRepositoryUtils.getSchemeLessPath`. It appears in three places with three
different meanings: the request field, the `commitKey` property, and HTS's
`tableVersion` column. Untangling those three is §5.5.

**`commitKey`.** `CatalogConstants.COMMIT_KEY` — a table property set by
`OpenHouseInternalRepositoryImpl.save` to the request's `baseTableVersion`, consumed
and stripped inside `doCommit`, and additionally used as a replay-detection key in a
5-minute `Cache`. It never reaches persisted metadata.

**`updated.openhouse.policy`.** A table property written by Spark's `SET POLICY` execs
and consumed by `OpenHouseTableOperations.buildUpdatedPolicies` on the client, which
folds it into the `policies` request field. It is the reason policies need no new REST
concept.

**Origin of the 95.** PR #34's `OpenHouseIcebergRestCatalogTests` extends Iceberg's
`CatalogTests` (101 methods); 95 are overridden and `@Disabled` with a reason naming
the missing endpoint, 5 skip themselves through capability flags, 1 passes.

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

Attractive because it touches almost nothing. Rejected on three counts. First, the
document is lossy in ways the spec is not: `remove-snapshots` and `remove-snapshot-ref`
have no representation at all, several `add-schema` updates in one commit collapse to
one schema (`newIntermediateSchemas` is replication-only and would have to be
generalized), and `set-snapshot-ref` can only be expressed through the whole-refs-map
field on the snapshots route. Second, it fails M4 by synthesizing a `baseTableVersion`
the client never sent — the server would be attesting to its own base, which makes the
service-layer check tautological while leaving it in place, the worst of both. Third,
it makes the whole-document body a permanent ceiling on what a REST client may express,
which is the opposite of the migration this workstream exists to enable.

Worth noting it is not *wrong*, only terminal: it is the fastest way to make some of
the 62 tests pass and the slowest way to finish.

### 7.2 Option C — native applier plus bespoke extension

As B, but `CreateTableRequest` gains `openhouse-policies` and `openhouse-table-type`
fields, and a custom `assert-openhouse-version` `TableRequirement` carries the metadata
file path so the existing service-layer check survives untouched.

Rejected on M1 and M2. A stock `RESTCatalog` cannot produce either extension, so every
OpenHouse client would again need a fork — which is the situation this programme
exists to leave. The custom requirement is also unnecessary: §5.5 shows the durable CAS
does not need a client-supplied token at all. The extension would preserve a check that
should be deleted.

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
nothing to compare against, so the switch would have to be taken on faith.

Option D's genuine contribution is the observation that the shim's surface is small.
This design shrinks it further: two SQL statements, not six (§5.6).

### 7.4 Corrections to `rest-support-sequencing.md`

Three claims in the sequencing analysis are wrong or incomplete in ways that change
this design.

1. **§6.1 overstates the client constraint.** It lists
   "`GRANT`/`REVOKE`, `SHOW GRANTS`, and `SET POLICY {RETENTION, SHARING, HISTORY,
   REPLICATION, COLUMN TAG}`" as reaching `OpenHouseCatalog` through
   `IcebergCatalogMapper`. `SET POLICY` does not. All five variants and
   `UNSET REPLICATION` call `catalog.loadTable(ident)`, match a `SparkTable` with an
   `openhouse.tableId` property, and commit
   `updateProperties().set("updated.openhouse.policy", …)`. They are ordinary property
   commits and work over any catalog. **Consequences:** the shim needs only
   `SupportsGrantRevoke` for two statements; and `policies` require no new REST concept
   at all, because Spark already sends them as a property set.
2. **§3.5's "read-only policies is a much smaller v1" is false for tables.** It is true
   for views, where there is no installed base. For tables, making policies read-only
   over REST breaks `ALTER TABLE … SET POLICY` the moment a cohort reaches
   `REST_WRITE`, because that statement *is* a property commit (correction 1). Policies
   must be settable over REST in v1 — and by correction 1, they already are, for free.
3. **§3's account of conflict detection is incomplete.** "detects conflict by comparing
   `baseTableVersion` … against the current head" describes only the service-layer check.
   There are three (§5.5), and the durable one is HTS's `metadataLocation` compare plus
   the JPA `@Version` lock — which is *not* a file-path comparison in any meaningful
   sense (the path is the CAS token, but the linearization is the row lock). This
   matters because §3's step 1 says the work is "wiring [iceberg-core primitives] to
   HTS's optimistic-concurrency version column rather than to a file-path comparison."
   The version column is already wired and does not move. The work is removing the two
   *protocol-level* checks that exist only because the client declares its own base.

One thing the sequencing analysis gets exactly right and this design depends on:
landing the applier underneath the current API first (§3, "Sequencing note"). §5.7's
S3 shadow is the mechanism that makes that step provable rather than merely plausible.

---

## 8. Open questions

Each carries a recommended default so no downstream work is blocked on an answer.

1. **Does the pinned `com.linkedin.iceberg:1.5.2.17` fork ship
   `CatalogHandlers.commitTransaction`?** Upstream added it after 1.5. *Default:* assume
   not, and place multi-table transaction orchestration in C2 (the handler) rather than
   depending on `CatalogHandlers`. C2's contract is unchanged either way.
2. **Minimum client Iceberg version eligible for REST writes.** *Default:* the same
   floor the read facade takes (≥1.6, for `/v1/config` endpoint advertisement and the
   pagination fix), with Spark 3.1 / Iceberg 1.2 permanently exempt (W6).
3. **Cohort granularity for C7.** Per cluster, per Spark application, or per user?
   *Default:* per cluster via `/v1/config` `overrides`, with a session-level override
   that `overrides` can always win back.
4. **Soak windows and coverage floors for each transition.** *Default:* placeholders
   for the operator to set; the gate's *shape* (§5.7) is the design, the numbers are
   configuration.
5. **Should the irreversibility guard's plugin-expressible subset be enforced
   server-side, client-side, or both?** *Default:* server-side, because a client in
   `REST_WRITE` may be any stock client and cannot be trusted to self-restrict.
