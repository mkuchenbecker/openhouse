# Multi-level namespaces: the API boundary

**Recommendation.** Encode a namespace for persistence by **dot-joining its levels**, with the
per-level identifier charset left at `^[a-zA-Z0-9_]+$` so `.` is structurally reserved as the
separator. `encode(Namespace.of("db")) == "db"`, byte for byte, and `encode` is exactly what
`Namespace.toString()` already returns at every seam that persists a namespace today — so
coexistence with the mono-namespace world is an identity, not a shim. The depth cap moves from a
compile-time constant to the cluster property `cluster.tables.namespace.max-depth`, **default 1**,
so landing this change is a literal no-op until an operator raises it. The wire encoding is not a
choice at all: the spec fixes it at `%1F`, and it is independent of the persisted encoding.

This document designs the API boundary only. It specifies no implementation and no implementation
design.

**Depends on:** namespaces as a first-class HTS entity (WS2). Every route below reads or writes a
stored namespace; without that store, seventeen of the nineteen conformance tests named in §7
cannot pass regardless of what this boundary says. This document defines the contract WS2 must
satisfy, not how it satisfies it.

**References:**
[`docs/development/rest-support-sequencing.md`](rest-support-sequencing.md) §2.1 ·
[Iceberg REST Catalog OpenAPI](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml) ·
`tests/iceberg-rest-catalog-compat` (PR #34) ·
[`views-iceberg-rest-compliance.md`](views-iceberg-rest-compliance.md) (the model for arguing a
spec-shaped surface in this repo)

---

## 1. Problem statement

OpenHouse has no namespace API and no namespace entity. A namespace *is* the `databaseId` string:
`NamespaceUtil.MAX_NAMESPACE_DEPTH = 1`, `OpenHouseInternalCatalog` and
`OpenHouseInternalTableOperations` call `tableIdentifier.namespace().toString()` and use the result
as the HTS primary-key column, and `OpenHouseIcebergRestApiHandler.decodeSingleLevelNamespace`
rejects anything deeper with `NoSuchNamespaceException`. Databases are *derived*:
`DatabasesServiceImpl.getAllDatabases()` reads every table primary key and `distinct()`s the
`databaseId` out of it.

Three things follow, and they are what this document has to settle:

1. **There is no CRUD surface.** Nineteen of PR #34's ninety-five disabled reference tests are
   namespace endpoints. An empty namespace cannot be represented; neither can namespace
   properties.
2. **The persisted encoding is a one-way door.** The moment a client creates a nested namespace
   over REST, whatever string lands in `house_table.database_id` is the format forever — it is a
   primary key, it is a storage path component, and it is an ACL subject. It has to be decided
   before the first REST write, and it has to be decided *at the boundary*, because five
   independent subsystems consume it.
3. **Lifting the depth cap removes an implicit discriminator.** `NamespaceUtil.isTableNamespace`
   gates `BaseMetastoreCatalog.isValidIdentifier`, and depth-1 is what currently makes
   `db.table.snapshots` resolve as a metadata table rather than as a base table named `snapshots`
   in namespace `db.table`. Raise the cap with nothing in its place and metadata-table resolution
   silently changes shape.

Layered on top, the owner requires that **table properties inherit down the namespace tree**. That
is a new semantic with no existing home in OpenHouse and no home in the Iceberg REST spec's data
model, so its contract — precedence, conflict, override, provenance on read — has to be settled at
the boundary too, or it becomes an accident of whichever component happens to merge maps first.

### 1.1 Two corrections to the sequencing analysis

`rest-support-sequencing.md` §2.1 is the inventory this document turns into a design. Two of its
sub-blockers are stated wrongly, and correcting them removes work rather than adding it.

**"Dot-join vs `%1F`" is not one decision.** §2.1 frames the encoding as a single choice between
Iceberg's wire separator and dot-joining. There are two encodings with two different owners:

- The **wire** encoding is fixed by the spec. The `namespace` path parameter and the `parent` query
  parameter are `%1F`-separated, servers *must* accept `0x1F` regardless of what they advertise,
  and `RESTUtil.encodeNamespace`/`decodeNamespace` already implement it. Choosing dot-join on the
  wire would be non-conformance, not a trade-off.
- The **persisted** encoding — HTS key, storage path, ACL subject, `/v1` path segment — is
  genuinely open, and is the one-way door.

They are independent because `RESTUtil.decodeNamespace` yields a structured `Namespace` before any
OpenHouse code sees it. §3 decides the second; the first is not a decision.

**"Widening the charset and admitting a separator are the same edit."** Under dot-join they are in
direct opposition: admitting `.` into a level destroys the injectivity that makes the encoding
usable as a primary key. They are also not coupled to the defect §2.1 cites them for. The
reference test `testLoadTableWithNonExistingNamespace` fails because a syntactically invalid
identifier draws `400 IllegalArgumentException` where the spec requires `404`. That is an
**error-mapping** contract (§5.7), fixable without touching the charset — and Iceberg's own
`CatalogTests` exposes `supportsNamesWithDot()` and `supportsNamesWithSlashes()` precisely so a
server can decline the wider charset and still conform.

---

## 2. Requirements

### Must

- **M1 — Byte-identical depth-1 behaviour.** For every existing single-level namespace: same HTS
  primary key, same storage path, same ACL subject, same route, same `isValidIdentifier` verdict,
  same on-disk `metadata.json`. Stated as a testable invariant in §6.
- **M2 — Prefix-preserving encoding.** `encode(Namespace.of("db")) == "db"` byte for byte, with no
  normalization, escaping, or case folding. This is what makes M1 an identity rather than a
  migration.
- **M3 — Injective encoding.** `encode` is total and injective over legal namespaces: no two
  distinct namespaces share a persisted key, and the key round-trips to the namespace it came from.
- **M4 — Spec-shaped CRUD.** create, load, list, update-properties, drop, exists at the spec's
  paths, carrying the spec's documents and the spec's `IcebergErrorResponse` envelope, reachable by
  a stock unmodified `RESTCatalog`.
- **M5 — Property inheritance is a contract.** Precedence order, conflict rule, override and
  un-set semantics, and read-time provenance are specified at the boundary, not left to whichever
  component merges maps.
- **M6 — Metadata-table resolution is unchanged at every depth.** `db.tbl.snapshots` resolves to
  the metadata table of `db.tbl` after the cap moves, with the same control flow and no additional
  HTS round trip, and a base table can never shadow a metadata table.
- **M7 — Every crossed boundary has a written contract**: signature, ownership, what it must not
  decide, and its error/exception mapping. Six seams: REST route, handler, internal catalog
  (`SupportsNamespaces`), HTS repository, storage path, authorization.
- **M8 — Validated by `CatalogTests`.** The design names the specific `@Disabled` entries in
  `tests/iceberg-rest-catalog-compat` its implementation retires, and the capability flags it
  flips (§7).

### Should

- **S1 — No new dual code path.** Depth-1 and depth-N travel the same code. Where a branch on depth
  is unavoidable, it is named and argued (there is exactly one: §5.4).
- **S2 — Reuse Iceberg's own protocol semantics.** `org.apache.iceberg.rest.CatalogHandlers` and
  `RESTUtil` own request/response construction, exactly as PR #34 already does for `loadTable`, so
  the OpenHouse handler owns zero protocol logic.
- **S3 — Off by default.** The change is inert until an operator raises the depth cap, so it can
  merge ahead of the REST write path without a rollout plan.
- **S4 — The `/v1` estate keeps working.** Jobs, the optimizer and the Spark plugin address tables
  through `/v1/databases/{databaseId}/...`; a nested table should not be invisible to retention and
  compaction.

### Won't, this milestone

- **W1 — Privilege inheritance down the namespace tree.** A grant on `a` confers nothing on `a.b`.
  Basis: it is a security default that cannot be un-shipped; `AuthorizationHandler` is an SPI with
  cluster-specific implementations, so a tree walk changes one access check into *depth* checks in
  code this repository does not own; and no `CatalogTests` case requires it. §5.6 and appendix D.
  This is deliberately asymmetric with property inheritance — see OQ-3.
- **W2 — `policies` inheritance.** Retention, sharing, history, replication and column tags are
  table-level OpenHouse concepts with their own validators and their own reserved `policies` key.
  They do not flow down the tree in v1. Basis: each has an admission validator whose preconditions
  are written against a single table; making them inheritable is a separate design, not a rider.
- **W3 — Namespace-level property *overrides* that beat a table's local value.** Reserved as
  `table-override.<key>` (Iceberg's own `CatalogProperties.TABLE_OVERRIDE_PREFIX` vocabulary) but
  not implemented. Basis: the owner's requirement is inheritance *by default*, which is the
  defaults direction; the override direction is a strictly larger blast radius and reserving the
  prefix is what keeps the door open. Appendix C.
- **W4 — Tombstoning an inherited property from a table.** A table shadows an inherited key by
  setting a local value; it cannot make the key absent. Basis: the spec has no verb for it, and
  `remove-properties` on a key not in the local map is a no-op in Iceberg's own semantics.
  Appendix C.
- **W5 — Widening the identifier charset.** Levels stay `^[a-zA-Z0-9_]+$`.
  `supportsNamesWithDot()` and `supportsNamesWithSlashes()` stay `false`. Basis: admitting `.`
  destroys M3 under the recommended encoding, and the reference suite sanctions declining.
- **W6 — Advertising a non-default `namespace-separator` in `/v1/config`.** The default `%1F`
  stands. Basis: 1.5.2.17-era clients do not read the override and always send `0x1F`, and the spec
  obliges servers to accept `0x1F` regardless, so advertising anything else adds a decoding mode
  and buys nothing.

### Out of scope

- **X1 — Namespace rename.** No spec route exists (`renameTable` moves tables between namespaces;
  there is no `renameNamespace`). It would also require moving storage under the recommended
  layout.
- **X2 — Backfill of existing implicit databases**, and whether they are materialized eagerly or
  lazily. That is WS2's decision about the store; this boundary is satisfied either way, because
  §6's invariant is stated over the *encoding*, not over row existence.
- **X3 — The commit path.** `requirements`/`updates`, staged create, transactions. Block B of the
  sequencing analysis.
- **X4 — Multi-level namespaces for views.** The view routes take the same `{namespace}` path
  parameter and inherit this decision mechanically; the views persistence backend is PR #44's.
- **X5 — Implementation and implementation design.** Explicitly deferred to the next phase.

---

## 3. Options: the persisted namespace encoding

Options are rows, the must-requirements are columns. This is the one-way door; the subordinate
decisions it constrains are tabulated in §3.1 and elaborated in §5.

| # | Persisted encoding | M1 depth-1 identical | M2 prefix-preserving | M3 injective | M7 survives all six seams | S1 no dual path | S4 `/v1` still addresses it |
|---|---|---|---|---|---|---|---|
| **A** | **Dot-join, charset excludes `.`** — `["a","b"] → "a.b"` | **Yes, by identity** — `Namespace.toString()` is already the persisted key at every seam | **Yes** — `"db" → "db"` | **Yes** — `.` cannot occur in a level | **Yes** — legal in a VARCHAR key, a path component, an ACL subject and a URL path segment | **Yes** — the encoder is a no-op rename of existing behaviour | **Yes** — one regex widening, strictly additive (§5.1) |
| B | `%1F` (`0x1F`) in the persisted key, matching the wire | Yes for depth 1 (no separator present) | Yes | Yes | **No** — a C0 control byte in a primary-key column, a filesystem path component, an ACL subject string and a log/metric tag | Yes | **No** — not expressible in a `/v1` path segment without escaping |
| C | Percent- or backslash-escaped join | Yes | Yes | Yes | Partly — inflates length ~3× against `database_id VARCHAR(128)` | Yes | Yes, with an escape layer |
| D | Opaque surrogate id (UUID) + name→id index | **No** — every existing key and every existing storage path changes | **No** | Yes | Requires a new index on the read path of every table load | No — a resolution step on every path | No |
| E | Reject the problem: nested namespaces are flattened by the client | Yes | n/a | n/a | n/a | Yes | Yes |

**Recommendation: A.** The deciding criterion is M2 read together with M7. Dot-joining is not a
new encoding — `OpenHouseInternalTableOperations.doRefresh`,
`OpenHouseInternalCatalog.findHouseTable`, `listTables`, `resolveFileIO` and
`searchSoftDeletedTables` all already persist `namespace().toString()`, which *is* the dot-join.
Choosing A therefore turns "make `NamespaceUtil` a real seam" into naming a function that already
exists, and makes M1 hold by construction across all six seams simultaneously rather than seam by
seam. B is the only serious rival and it fails M7 on four of the six: `0x1F` is a legal MySQL
`VARCHAR` byte but it is a hostile primary key, an unprintable path component, an unloggable
metric tag, and it cannot appear in the `/v1` route that the jobs and optimizer estate uses
(S4). D is disqualified on M1 outright. E is not a design, but it is what a server that returns
`404` for depth ≥ 2 forces on its clients, and it is what we do today.

The cost of A is W5: `.` becomes structurally reserved, so OpenHouse can never support a namespace
level containing a dot. That is a real and permanent narrowing, and it is the one thing a reviewer
should push on. It is acceptable because the current charset already excludes `.`, Iceberg's
reference suite has a capability flag for declining it, and no OpenHouse client can be relying on
a character the validator has always rejected.

### 3.1 Subordinate decisions this constrains

| Decision | Chosen | Rejected | Where |
|---|---|---|---|
| Wire encoding | `%1F` per spec, via `RESTUtil` | Anything else (non-conformance) | §1.1, §5.1 |
| `/v1/config` `namespace-separator` | Not advertised (default stands) | Advertising `.` | W6 |
| Depth cap | Cluster property, **default 1** | Compile-time constant; unbounded | §5.3 |
| Encoded-key length | Hard cap 128 chars at the boundary (`database_id VARCHAR(128)`) | Unbounded; silent truncation | §5.5 |
| Storage layout | Flat directory literally named `a.b` | Nested `a/b/` | §5.5, appendix B |
| Metadata-table discriminator | Reserved table names at depth ≥ 2 + explicit predicate | Depth-1 rule; naming convention; route-only | §5.4, appendix A |
| Inheritance resolution locus | API boundary, read-time projection, never persisted | Inside the internal catalog; materialized into `metadata.json`; client-side | §5.8, appendix C |
| Precedence | reserved `openhouse.*` > table-local > nearest ancestor | Deepest-wins-over-local; merge; error on conflict | §5.8 |
| Provenance on read | Effective map + one reserved key `openhouse.inheritedProperties` | Second properties map (wire extension); no provenance | §5.8 |
| Privilege inheritance | None | Inherit down the tree | W1, appendix D |
| `/v1` `databaseId` pattern | Widened to `^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)*$` | Left depth-1-only | §5.1 |

---

## 4. Sketch

```
                     stock Iceberg RESTCatalog  (namespace as String[], %1F on the wire)
                                 │
 ── REST route seam ─────────────┼──────────────────────────────────────────────────────
   6 generated routes, x-openhouse-support: supported in spec/iceberg-rest-catalog-open-api.yaml
   IcebergRestCatalogController — thin Spring MVC adapter, no logic
                                 │  String encodedNamespace  ("a%1Fb")
 ── handler seam ────────────────┼──────────────────────────────────────────────────────
   IcebergRestNamespaceApiHandler
     RESTUtil.decodeNamespace  →  Namespace          ← the ONLY wire-format decoder
     NamespaceUtil.validate    →  charset, depth cap, length     (throws → 400/404)
     CatalogHandlers.*         →  spec request/response documents
                                 │  Namespace
 ── internal catalog seam ───────┼──────────────────────────────────────────────────────
   OpenHouseInternalCatalog implements SupportsNamespaces
     NamespaceUtil.encode(ns)  →  String             ← the ONLY persistence encoder
     isValidIdentifier         =  isTableNamespace(ns) && !isMetadataTableIdentifier(id)
                                 │  String encodedNamespace  ("a.b")
 ── HTS repo ──── storage path ──┴─── authorization ────────────────────────────────────
   database_id = "a.b"      {root}/a.b/{tbl}-{uuid}      DatabaseDto.databaseId = "a.b"
   (VARCHAR(128))           (flat, one level, unchanged) (ACL subject, unchanged shape)
```

```java
// The whole one-way door, in three lines.
static String encode(Namespace ns)  { return String.join(".", ns.levels()); }   // == ns.toString()
static Namespace decode(String key) { return Namespace.of(key.split("\\.", -1)); }
// invariant: encode(Namespace.of("db")).equals("db")   — byte for byte, forever
```

Property resolution, highest precedence first, applied at the boundary on read only:

```
1. openhouse.*            server-owned, computed per entity, never inherited, never overridable
2. table-local            what the table's own metadata.json holds — the only thing ever persisted
3. nearest ancestor       a.b.c → a.b → a          (deepest ancestor wins; no merge, no error)
   (no catalog-level defaults in v1)

effective = ancestors_shallow_to_deep ⊕ local ⊕ reserved     (⊕ = right operand wins)
persisted = local                                            (invariant: always)
```

---

## 5. Details

### 5.1 The REST route seam

Six operations, all already present in `spec/iceberg-rest-catalog-open-api.yaml` and all currently
unannotated. The contract is established by adding `x-openhouse-support: supported` to each; PR
#34's build then **fails compilation until the handler implements them**, and regenerates
`IcebergRestOpenHouseSupport.SUPPORTED_ENDPOINTS` so `/v1/config` advertises exactly the routes
that exist. That mechanism is the contract's enforcement; nothing here is advisory.

| Method & path | operationId | Request | Success | Privilege |
|---|---|---|---|---|
| `GET /v1/{prefix}/namespaces?parent&pageToken&pageSize` | `listNamespaces` | — | `200 ListNamespacesResponse` | authenticated |
| `POST /v1/{prefix}/namespaces` | `createNamespace` | `CreateNamespaceRequest` | `200 CreateNamespaceResponse` | `CREATE_TABLE` on the parent (`SYSTEM_ADMIN` at the root) |
| `GET /v1/{prefix}/namespaces/{namespace}` | `loadNamespaceMetadata` | — | `200 GetNamespaceResponse` | `GET_TABLE_METADATA` |
| `HEAD /v1/{prefix}/namespaces/{namespace}` | `namespaceExists` | — | `204` | `GET_TABLE_METADATA` |
| `DELETE /v1/{prefix}/namespaces/{namespace}` | `dropNamespace` | — | `204` | `DELETE_TABLE` |
| `POST /v1/{prefix}/namespaces/{namespace}/properties` | `updateProperties` | `UpdateNamespacePropertiesRequest` | `200 UpdateNamespacePropertiesResponse` | `UPDATE_TABLE_METADATA` |

**Owns:** HTTP shape, status codes, the `IcebergErrorResponse` envelope, and the `{prefix}` check
(`iceberg`, as PR #34 established).
**Must not decide:** anything about encoding, depth, or inheritance. The controller stays the thin
adapter it is today.

**Pagination.** `listNamespaces` reuses PR #34's opaque base64 `v1:page:size` cursor and its
1..1000 page-size bound verbatim. Two obligations carried over from
`views-iceberg-rest-compliance.md`: an **empty** `pageToken=` must not be a `400` (every Iceberg
Java client since 1.6.0 sends it), and when no token is supplied the server must be able to return
the complete result in one page, because 1.5.2-era clients follow no continuation token.

**`listNamespaces` semantics.** `parent` absent → top-level namespaces only, *not* a flattened
tree. `parent=a%1Fb` → the immediate children of `a.b`, each returned as a full namespace
(`["a","b","c"]`, not `["c"]`). Absent parent that does not exist → `404
NoSuchNamespaceException`. This is the one place where "one level" is the spec's own wording and
must not be read as "everything below".

**`/v1` legacy routes.** `OpenHouseTablesApiValidator` and `OpenHouseDatabasesApiValidator` widen
`databaseId` from `ALPHA_NUM_UNDERSCORE_REGEX` to a dot-joined form
`^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)*$`. This is **strictly additive**: every string that validates
today still validates, unchanged; every string this newly admits was previously a `400` naming a
resource that could not exist. Doing it is what keeps S4 — a nested table stays visible to
retention, compaction, and the Spark plugin, all of which address tables through
`/v1/databases/{databaseId}/tables/{tableId}`. Declining it is the alternative in appendix E, and
its cost is that nested tables become invisible to every data-management job in the fleet.
`GET /v1/databases` then returns dot-joined encoded namespaces, which for every existing
depth-1 database is the identical string it returns today.

### 5.2 The handler seam

```java
public interface IcebergRestNamespaceApiHandler {
  ListNamespacesResponse listNamespaces(String prefix, String parent, String pageToken, Integer pageSize);
  CreateNamespaceResponse createNamespace(String prefix, CreateNamespaceRequest request);
  GetNamespaceResponse   loadNamespaceMetadata(String prefix, String namespace);
  void                   namespaceExists(String prefix, String namespace);
  void                   dropNamespace(String prefix, String namespace);
  UpdateNamespacePropertiesResponse updateProperties(
      String prefix, String namespace, UpdateNamespacePropertiesRequest request);
}
```

Sibling of PR #34's `IcebergRestApiHandler`, same package, same `@ConditionalOnProperty`.

**Owns:** the wire↔domain translation. It is the **only** place `RESTUtil.decodeNamespace` and
`RESTUtil.encodeNamespace` are called, and the only place `Namespace` is constructed from an
untrusted string. It resolves the acting principal
(`AuthenticationUtils.extractAuthenticatedUserPrincipal`) and delegates to
`org.apache.iceberg.rest.CatalogHandlers` for document construction, so that
`UpdateNamespacePropertiesResponse`'s `updated`/`removed`/`missing` partition is Iceberg's
semantics rather than ours (S2).

**Must not decide:** the persisted encoding (that is `NamespaceUtil`'s, invoked below it);
authorization outcomes (that is the service layer's); property resolution (§5.8).

**Error contract** — these mappings are the boundary, not a suggestion:

| Condition | Exception | HTTP | `IcebergErrorResponse.type` |
|---|---|---|---|
| Namespace absent, on any route that names one | `NoSuchNamespaceException` | 404 | `NoSuchNamespaceException` |
| **Namespace syntactically invalid** (bad charset, over depth cap, over length) **on a read route** | `NoSuchNamespaceException` | **404** | `NoSuchNamespaceException` |
| Namespace syntactically invalid on `createNamespace` | `ValidationException` | 400 | `ValidationException` |
| `createNamespace` on an existing namespace | `AlreadyExistsException` | 409 | `AlreadyExistsException` |
| `createNamespace` whose parent does not exist | `NoSuchNamespaceException` | 404 | `NoSuchNamespaceException` |
| `dropNamespace` on a namespace holding tables, views, or child namespaces | `NamespaceNotEmptyException` | 409 | `NamespaceNotEmptyException` |
| A key appears in both `removals` and `updates` | `IllegalArgumentException` (from Iceberg's own request validation) | **422** | `UnprocessableEntityException` |
| Reserved (`openhouse.`-prefixed) key in `updates` | `ValidationException` | 400 | `ValidationException` |
| Principal lacks the privilege | `AccessDeniedException` | 403 | `NotAuthorizedException` |

Row 2 is the fix for `BLOCKED_IDENTIFIER_CHARSET`. **A read route never returns 400 for a
well-formed URL naming a resource that cannot exist** — the distinction between "you asked wrongly"
and "it isn't there" belongs to write routes only. Errors on these routes carry the
`IcebergErrorResponse` envelope, never OpenHouse's `ErrorResponseBody`, and never a serialized
stack trace.

### 5.3 The internal-catalog seam

`OpenHouseInternalCatalog implements SupportsNamespaces`, Iceberg's own SPI, unmodified:

```java
void              createNamespace(Namespace ns, Map<String,String> metadata);
List<Namespace>   listNamespaces();                       // top level
List<Namespace>   listNamespaces(Namespace ns);           // immediate children, throws NoSuchNamespaceException
Map<String,String> loadNamespaceMetadata(Namespace ns);   // throws NoSuchNamespaceException
boolean           dropNamespace(Namespace ns);            // throws NamespaceNotEmptyException
boolean           setProperties(Namespace ns, Map<String,String> props);
boolean           removeProperties(Namespace ns, Set<String> props);
boolean           namespaceExists(Namespace ns);
```

Implementing this SPI is also what lets `listTables(Namespace.empty())` stop being the
"anti-pattern" its own TODO comment calls it.

`NamespaceUtil` becomes the single seam it was written to be, and gains the encoder:

```java
static String     encode(Namespace ns);          // String.join(".", levels) — M2, M3
static Namespace  decode(String persistedKey);
static boolean    isTableNamespace(Namespace ns);        // 1 <= depth <= maxDepth
static void       validateOperationNamespace(Namespace ns);  // depth <= maxDepth; empty allowed
static boolean    isReservedTableName(String name);      // MetadataTableType.from(name) != null
static boolean    isMetadataTableIdentifier(TableIdentifier id);
```

`MAX_NAMESPACE_DEPTH` moves to `cluster.tables.namespace.max-depth`, **default 1**. At the default
every predicate above returns exactly what it returns today, so merging the change alters no
observable behaviour anywhere (S3) — this is the mechanism by which the sequencing analysis's
"decide in Phase 0, enable in Phase 3" is realized as configuration rather than as a second
branch. Recommended value when enabled: **6**, subordinate to the hard 128-character encoded-length
cap of §5.5.

**Owns:** the encoding, the depth and charset predicates, and the `isValidIdentifier` verdict.
**Must not decide:** HTTP status codes, authorization, or property resolution. It throws Iceberg's
own exceptions and the handler maps them.

### 5.4 The metadata-table discriminator

`BaseMetastoreCatalog.loadTable` resolves a metadata table like this:

```java
if (isValidIdentifier(id)) {
  ops = newTableOps(id);                                    // ← an HTS round trip
  if (ops.current() == null) {
    if (isValidMetadataIdentifier(id)) return loadMetadataTable(id);
    throw new NoSuchTableException(...);
  }
  return new BaseTable(ops, ...);                           // ← a base table SHADOWS the metadata table
} else if (isValidMetadataIdentifier(id)) {
  return loadMetadataTable(id);                             // ← today's path for db.tbl.snapshots
}
// isValidMetadataIdentifier(id) == MetadataTableType.from(id.name()) != null
//                                 && isValidIdentifier(TableIdentifier.of(id.namespace().levels()))
```

Today `isValidIdentifier(db.tbl.snapshots)` is `false` because the namespace is depth 2, so the
`else if` fires and the metadata table loads with no HTS lookup at all. Raise the cap and
`isValidIdentifier` becomes `true`: the load still *usually* ends up correct, via the inner
fallback, but three things have changed and two of them are defects.

1. Every metadata-table load now costs an extra HTS round trip on a hot path — Spark reads
   `.snapshots`, `.files`, `.history` constantly.
2. If a real table named `snapshots` ever exists in namespace `db.tbl`, it **shadows** the metadata
   table of `db.tbl`, permanently and unrecoverably from the client's side.
3. Nothing prevents creating that table: `BaseMetastoreCatalogTableBuilder` gates only on
   `isValidIdentifier`.

**Replacement, in two parts.**

**(a) An explicit predicate replaces the depth accident.**

```java
boolean isValidIdentifier(TableIdentifier id) {
  return id != null
      && NamespaceUtil.isTableNamespace(id.namespace())
      && !NamespaceUtil.isMetadataTableIdentifier(id);
}
boolean isMetadataTableIdentifier(TableIdentifier id) {
  return isReservedTableName(id.name())                 // MetadataTableType.from, case-insensitive
      && id.namespace().levels().length >= 2;           // ← the depth branch, argued below
}
```

This restores the cheap `else if` short-circuit at *every* depth: `db.tbl.snapshots` and
`a.b.c.files` both fail `isValidIdentifier`, fall straight to `isValidMetadataIdentifier`, and load
the metadata table with **no HTS round trip** — byte-identical control flow to today, defect 1
retired.

**(b) A create-time admission rule makes shadowing impossible.** A table whose name is a
`MetadataTableType` (case-insensitively) may not be created in a namespace of depth ≥ 2. Rejected
with `400 ValidationException` at the tables validator, so it holds for the `/v1` route and the
REST route alike. Defects 2 and 3 retired.

**Why the `>= 2` branch is safe, and why it is the only one.** It is the single place this design
branches on namespace depth, and it exists for a reason that is checkable rather than
aesthetic: **at depth 1 the identifier space has an installed base with defined behaviour; at
depth ≥ 2 it is empty and always has been.** Concretely, `db.snapshots` is a legal existing
OpenHouse table today and resolves as a base table (`isValidIdentifier` true, `ops.current()`
non-null); under the predicate above it still does, because `isMetadataTableIdentifier` requires
depth ≥ 2. And `db.snapshots.history` still resolves as the metadata table of `db.snapshots`,
because `isValidMetadataIdentifier` recurses into `isValidIdentifier(db.snapshots)`, which is
depth 1 and therefore true. Meanwhile the create-time rule can never reject a table that exists or
could have existed, because the depth cap has always forbidden depth ≥ 2. The branch is safe
precisely because it cannot change the outcome for any identifier expressible today — which is M1
restated for this seam, and is directly testable as such.

**And over REST the ambiguity does not arise at all.** Namespace and table name reach the server in
separate path segments, already split by the client, and the spec has no metadata-table route —
Iceberg clients build metadata tables locally from the loaded `TableMetadata`. The ambiguity is
purely an artifact of the in-JVM `Catalog` SPI, where a flat dotted `TableIdentifier` must be
parsed. Rule (b) exists to keep that SPI sound; rule (a) exists to keep it fast.

### 5.5 The HTS repository and storage-path seams

**HTS repository.** `HouseTablePrimaryKey.databaseId` and every
`HouseTableRepository.*ByDatabaseId(String databaseId, ...)` signature is **unchanged**. What
changes is the documented meaning of the parameter: it is *the encoded namespace*, not *a database
name*. The repository must not split, parse, or interpret it — it is an opaque key. Two obligations
follow:

- **Length.** `database_id VARCHAR(128)`. The boundary rejects an encoded namespace longer than 128
  characters with `400 ValidationException` before it reaches the repository. Silent truncation at
  a primary key would be a data-corruption bug, and this is the real bound on depth.
- **Collation.** Case-sensitivity of namespace comparison is whatever the `database_id` column
  collation already gives depth-1 databases, applied to the whole encoded string. This design
  introduces no normalization of its own — see OQ-5.

Namespace rows themselves (properties, existence, parent links) are WS2's entity. The contract this
boundary places on it: **a namespace's key is `encode(ns)`**, the same string that appears in
`house_table.database_id`, so that "does namespace `a.b` contain tables" is a prefix or equality
question over one column family and never a join across two encodings.

**Storage path.** `BaseStorage.allocateTableLocation(databaseId, tableId, tableUUID, creator,
props)` is **unchanged, including its signature**, and continues to produce
`{endpoint}{rootPrefix}/{databaseId}/{tableId}-{uuid}`. For `a.b` that is a **flat directory
literally named `a.b`**, one level below `rootPrefix`, exactly where a depth-1 database directory
sits.

Flat rather than nested, for a reason that is not aesthetic:

- `TableUUIDGenerator` builds `Paths.get(rootPrefix, databaseId)` and asserts the manifest-list path
  starts with it. Under nesting, `Paths.get(rootPrefix, "a.b")` names a directory that does not
  exist and the assertion fails.
- `apps/spark` orphan-directory deletion (`StorageClient.getSubDirectoriesWithOwners`) walks one
  level of subdirectories under a database path and treats each as a table directory. Under
  nesting, the child *namespace* `b` is indistinguishable from a table directory of `a` — an
  orphan-deletion job would classify a populated namespace as a stray directory.
- `Storage.isPathValid` round-trips through `allocateTableLocation`, so any layout change is a
  change to path validation too.

Flat keeps the storage tree exactly two levels deep forever, which is the assumption every one of
those callers already encodes. `.` is a legal component character on HDFS, ADLS, S3 and local
filesystems. Nesting is developed and rejected in appendix B.

**Must not decide:** the storage layer must not parse the encoded namespace. It receives an opaque
string and concatenates. That is the whole contract, and it is why M1 holds here with no code
change at all.

### 5.6 The authorization seam

`AuthorizationHandler.checkAccessDecision(principal, DatabaseDto, Privileges)` and the
`grantRole`/`revokeRole`/`listAclPolicies` overloads are **unchanged**. `DatabaseDto.databaseId`
carries the encoded namespace, so for every depth-1 namespace the ACL subject string is byte-identical
to today's (M1). A nested namespace is simply a new subject.

**Owns:** access decisions. **Must not decide:** anything about namespace structure — it receives a
subject string and answers yes or no. This is deliberate: `AuthorizationHandler` is an SPI whose
production implementations live outside this repository, and a design that required it to
understand namespace nesting would be a change to code we cannot see. That is the load-bearing
argument for W1.

Privilege mapping for the six routes is in §5.1. `createNamespace` checks `CREATE_TABLE` on the
*parent* namespace (and `SYSTEM_ADMIN` when creating a top-level namespace, matching who can
create a database today); `dropNamespace` checks `DELETE_TABLE` on the namespace itself. No
privilege check walks the tree.

**The asymmetry, stated plainly.** Properties inherit; privileges do not. So a principal who can
edit namespace `a` can set a property that takes effect in tables under `a.b` that they may not be
able to read. That is consistent with the model OpenHouse already has — `TablesServiceImpl` sources
`CREATE_TABLE` from a *database*-level grant, so database-level control already implies control
over what gets created beneath it — but it is a judgment call and it is OQ-3.

### 5.7 Identifier validation, in one place

Charset per level `^[a-zA-Z0-9_]+$` (W5); depth `1..cluster.tables.namespace.max-depth`; encoded
length ≤ 128. Empty namespace is legal only as the internal "all databases" sentinel in
`validateOperationNamespace` and is never addressable over REST (`supportsEmptyNamespace()` stays
`false`). Validation lives in `NamespaceUtil` and nowhere else; the handler maps its
`ValidationException` per the §5.2 table, and in particular maps it to **404 on read routes**.

### 5.8 Property inheritance as a contract

**Where resolution happens: at the API boundary, on read, as a projection. Never persisted.**

```
persisted(table.properties) == local(table.properties)          — always, at every depth
returned(table.properties) == ancestors ⊕ local ⊕ reserved      — computed per response
```

This is the load-bearing choice and it has three consequences worth stating. A namespace property
edit rewrites **no** `metadata.json` — otherwise one `POST .../properties` would fan out into a
commit per table beneath it. A table's on-disk bytes are unchanged by anything that happens in the
namespace tree, which is what extends M1 from the HTS key all the way to storage. And unsetting a
namespace property actually takes effect, rather than leaving stale copies materialized in every
table that was created while it was set.

Resolution is applied by the boundary component that serializes a table document — both surfaces,
same rule: the REST handler before returning `LoadTableResponse`, and `TablesService` before
returning `GetTableResponseBody.tableProperties`. It is **not** applied inside
`OpenHouseInternalCatalog`, for reasons developed in appendix C.

**Precedence, highest first.** (1) Reserved `openhouse.*` properties — server-computed per entity
(`openhouse.tableId`, `openhouse.databaseId`, `openhouse.tableUri`, `openhouse.tableUUID`,
`openhouse.clusterId`, `openhouse.tableVersion`). Never inherited, never overridable, and rejected
in `updates` on a namespace with `400`. (2) The table's own local properties. (3) The nearest
ancestor namespace, walking up: `a.b.c` then `a.b` then `a`.

**Conflict: nearest wins, silently.** The same key at several ancestors resolves to the deepest
ancestor's value. No merge, no union, no error. Deterministic and explainable in one sentence,
which is the property that matters for something a user will debug at 3am.

**Override: yes, by setting a local value.** A table sets the key locally and its value wins at any
depth. Setting a local value equal to the currently inherited value is **not** a no-op — it pins
the value, so a later namespace edit does not move it. That is deliberate: it is the only way a
table can insulate itself.

**Un-set: no (W4).** A table cannot make an inherited key absent. `remove-properties` on a key the
table holds locally returns the key to its *inherited* value, not to absent — and the response
reports it under `removed`, because the local entry genuinely was removed. Appendix C develops the
tombstone alternative and why it is not worth its wire surface in v1.

**Provenance on read: the effective map plus one reserved key.** `LoadTableResult.metadata.properties`,
`GetNamespaceResponse.properties`, and `GetTableResponseBody.tableProperties` all carry the
**effective** map — that is what an engine needs to behave correctly, and a second parallel map
would be a wire extension of exactly the kind `views-iceberg-rest-compliance.md` argues against.
Provenance rides in a single reserved property:

```
openhouse.inheritedProperties = {"retention.days":"a.b","team.owner":"a"}
```

A JSON object, key → the encoded namespace the value came from, listing only keys whose effective
value came from an ancestor. It is server-computed, rejected on write like every other
`openhouse.` key, and **absent entirely when nothing is inherited** — so a depth-1 table under a
namespace with no properties returns precisely the map it returns today. That absence is what makes
provenance compatible with M1 rather than a new field on every response.

**Interaction with the `openhouse.` reserved convention.** `BasePreservedKeyChecker` extends
unchanged to namespaces: `openhouse.`-prefixed keys and `policies` are preserved, rejected in
`createNamespace` properties and in `updateProperties` `updates`, and never inheritable. This keeps
one rule for the whole system rather than a table rule and a namespace rule. `policies` in
particular does not flow down (W2).

**Namespace properties may include server-added keys.** The spec explicitly anticipates this
("The server might also add properties, such as `last_modified_time`"), and `CatalogTests`
asserts that created properties are a *subset* of what `loadNamespaceMetadata` returns rather than
equal to it — so `openhouse.` annotations on namespaces are conformant. Anything added must
satisfy that subset property.

---

## 6. The compatibility invariant

Stated so it can be a test, not a promise. Let `n` be any namespace with `n.levels().length == 1`,
`t` any table id, `u` any UUID.

| # | Invariant | Where it is checked |
|---|---|---|
| I1 | `NamespaceUtil.encode(n).equals(n.level(0))` — byte equality; no trim, no case fold, no escape | `NamespaceUtil` unit test, property-based over the legal charset |
| I2 | `RESTUtil.encodeNamespace(n).equals(n.level(0))` — the wire encoding is prefix-preserving too | same |
| I3 | `HouseTablePrimaryKey(encode(n), t)` equals the key built before the change | golden test over a corpus of existing `(databaseId, tableId)` pairs |
| I4 | `allocateTableLocation(encode(n), t, u, c, p)` is string-equal to the pre-change value, for every `Storage` implementation | `BaseStorage`/`HdfsStorage`/`LocalStorage` tests |
| I5 | `Storage.isPathValid` accepts exactly the paths it accepted before | storage tests |
| I6 | `DatabaseDto.databaseId == encode(n)`, so every ACL subject string is unchanged | authorization tests |
| I7 | `/v1/databases/{n}/tables/{t}` and `/v1/{prefix}/namespaces/{encodeNamespace(n)}/tables/{t}` resolve to the same table, with the same status codes as before | e2e |
| I8 | `isValidIdentifier(TableIdentifier.of(n, t))` is unchanged **for every `t`, including reserved metadata-table names** — this is what pins §5.4's depth branch | catalog unit test enumerating `MetadataTableType.values()` |
| I9 | `loadTable(n.t.<metadataType>)` performs the same number of HTS round trips as before (zero) | catalog test with a counting repository stub |
| I10 | For a table whose ancestors carry no properties, `returned(properties) == persisted(properties)` and `openhouse.inheritedProperties` is absent | tables service test |
| I11 | `persisted(properties) == local(properties)` at every depth — no inherited value is ever written | repository test |
| I12 | With `cluster.tables.namespace.max-depth=1` (the default), every namespace-related behaviour is identical to the pre-change build | the existing 660-test tables suite, run unmodified |

I12 is the strongest of these and the cheapest: at the shipped default the whole change is inert,
so the existing suite *is* the regression guard, and I1–I11 are what must additionally hold once an
operator raises the cap.

Two negative invariants complete the set:

- **N1** — no level may contain `.`; a namespace that would encode ambiguously is rejected at the
  boundary. Property test: `decode(encode(ns)).equals(ns)` for every legal `ns` (M3).
- **N2** — no table whose name is a `MetadataTableType` exists at depth ≥ 2 (§5.4b), enforced at
  create and asserted as a repository-level invariant.

---

## 7. Conformance: which disabled tests this retires

From `tests/iceberg-rest-catalog-compat`'s `OpenHouseIcebergRestCatalogTests`. All nineteen
namespace tests route through the boundary this document specifies; seventeen additionally need
WS2's namespace store behind it, which is why that dependency is stated at the top.

**Capability flags flipped** — `requiresNamespaceCreate() → true`, `supportsNamespaceProperties() →
true`, **`supportsNestedNamespaces() → true`**. `supportsEmptyNamespace()`,
`supportsNamesWithDot()` and `supportsNamesWithSlashes()` stay `false` (W5).

| Test | Blocker retired by |
|---|---|
| `testCreateNamespace` | `createNamespace` route + `SupportsNamespaces` (§5.1, §5.3) |
| `testCreateExistingNamespace` | `AlreadyExistsException → 409` (§5.2) |
| `testCreateNamespaceWithProperties` | namespace properties + the subset rule (§5.8) |
| `testLoadNamespaceMetadata` | `loadNamespaceMetadata` route |
| `testSetNamespaceProperties` | `updateProperties` route |
| `testUpdateNamespaceProperties` | `updateProperties` route |
| `testUpdateAndSetNamespaceProperties` | `CatalogHandlers.updateNamespaceProperties` partition (S2) |
| `testSetNamespacePropertiesNamespaceDoesNotExist` | `NoSuchNamespaceException → 404` (§5.2) |
| `testRemoveNamespaceProperties` | `removals` semantics (§5.8) |
| `testRemoveNamespacePropertiesNamespaceDoesNotExist` | `NoSuchNamespaceException → 404` |
| `testDropNamespace` | `dropNamespace` route |
| `testDropNonexistentNamespace` | drop-missing contract (§5.2) |
| `testDropNonEmptyNamespace` | `NamespaceNotEmptyException → 409` (§5.2) |
| **`testDropNamespaceWithNestedNamespace`** | **multi-level specific** — `supportsNestedNamespaces()` + child-namespace non-emptiness |
| `testListNamespaces` | `listNamespaces` route + `/v1/config` advertisement |
| **`testListNestedNamespaces`** | **multi-level specific** — `parent=` scoping returns immediate children as full namespaces (§5.1) |
| `testListNonExistingNamespace` | `NoSuchNamespaceException → 404` on a list of a missing namespace |
| `testNamespaceWithDot` | override **deleted**; test then self-skips on `supportsNamesWithDot() == false` (W5) |
| `testNamespaceWithSlash` | override **deleted**; test then self-skips on `supportsNamesWithSlashes() == false` (W5) |

Honest accounting: the last two become *skips*, not passes. That is a legitimate conformance
posture — Iceberg supplies the flags for exactly this — but it should not be counted as two more
green tests.

**One test outside the nineteen changes category.** `testLoadTableWithNonExistingNamespace` is
currently disabled under `BLOCKED_IDENTIFIER_CHARSET`. §5.2's rule — a read route returns 404, not
400, for a syntactically invalid namespace — retires that blocker. The test still needs
`createTable`, so its `@Disabled` reason moves from `BLOCKED_IDENTIFIER_CHARSET` to
`NEEDS_CREATE_TABLE` rather than disappearing. Moving a reason is progress that the disabled count
does not show, and the reason string is the roadmap, so it should be moved rather than left stale.

**Multi-level-specific coverage is thin**, and it is worth saying so: only two of the nineteen
actually exercise nesting. The invariants in §6 — particularly I8, I9 and I12 — carry more of the
validation weight than `CatalogTests` does here, and appendix F lists the cases `CatalogTests` does
not reach at all.

---

## 8. Open questions

Each carries a recommended default, so none of these blocks the next phase; each is a place where
the owner's answer should override mine.

| # | Question | Recommended default |
|---|---|---|
| **OQ-1** | What is `cluster.tables.namespace.max-depth` when an operator enables nesting? | **6**, subordinate to the hard 128-character encoded-length cap of §5.5. Deep enough for `org.team.domain.dataset`-style hierarchies, shallow enough that the length cap is rarely the thing a user hits first. |
| **OQ-2** | Should a table be able to *tombstone* an inherited property, not merely shadow it? | **No in v1** (W4, appendix C4). Shadowing with a local value — including the empty string — covers the realistic cases, and no spec verb expresses a tombstone, so it would be reachable only through the OpenHouse API. Addable later without changing anything specified here. |
| **OQ-3** | Properties inherit but privileges do not. Is that asymmetry acceptable? | **Yes for v1** (W1, appendix D). It is consistent with OpenHouse's existing model, where a database-level `CREATE_TABLE` grant already implies control over what is created beneath it. But it is the single judgment call in this document most worth challenging, because it cannot be reversed once inherited access exists in the wild. |
| **OQ-4** | `replaceTable` carries the full effective property map, which pins every inherited value into the table's local map. Accept or diff? | **Accept and document.** Nothing observable changes at that instant; the alternative guesses at intent and would silently discard a deliberate pin. |
| **OQ-5** | Is namespace comparison case-sensitive at depth ≥ 2? | **Inherit whatever `database_id`'s column collation already gives depth-1 databases**, applied to the whole encoded string. This design introduces no normalization of its own — but the effective collation should be confirmed against the production HTS schema rather than assumed from `schema.sql`, and `OpenHouseInternalCatalog.renameTable`'s existing `equalsIgnoreCase` case-preservation rule needs re-reading in a dotted world. |
| **OQ-6** | `dropNamespace` on a namespace containing only *child namespaces* (no tables) — empty or not empty? | **Not empty → `409 NamespaceNotEmptyException`.** This is what `testDropNamespaceWithNestedNamespace` asserts, and cascade is unrecoverable. It also settles decision 5 of the sequencing analysis's own list, in the non-cascading direction. |
| **OQ-7** | Does a namespace get an entry in `GET /v1/databases` before any table exists in it? | **Yes** once WS2 stores namespaces — an empty namespace is exactly the thing the current derived-database model cannot represent. Worth confirming no `/v1` client treats "listed" as "has tables". |

## Appendix

### A. Rejected discriminator replacements

**A1 — Keep depth-1 as the discriminator; forbid tables at depth ≥ 2.** Self-defeating: it is the
current state with a longer name.

**A2 — A naming convention for metadata tables (e.g. require a `$` or `#` prefix).** Iceberg
clients construct `db.tbl.snapshots` themselves in `MetadataTableUtils` and in Spark's identifier
parsing; a server cannot change what the client sends. It would also change depth-1 behaviour,
failing M1 outright.

**A3 — Resolve by lookup: try the base table, fall back to metadata.** This is literally what
`BaseMetastoreCatalog` does when `isValidIdentifier` is true, and it is defect 1 of §5.4 — an HTS
round trip on every metadata-table load — plus defect 2, since a base table found by that lookup
wins. It is the *absence* of a design, and it is what happens if the cap is raised with nothing
else changed.

**A4 — Route-only: declare the ambiguity a client problem.** Correct for REST (§5.4) and useless
for the in-JVM SPI, which `OpenHouseInternalCatalog` is and which the Spark plugin uses. Rejected
because it solves the half of the problem that was never broken.

**A5 — Reserve metadata-table names at *every* depth, not just depth ≥ 2.** Cleaner as a rule, and
rejected on M1: `db.snapshots` is a legal existing table today, and forbidding new ones would
change current behaviour for a live identifier space. The depth branch is the price of M1, which
is why §5.4 argues it rather than hiding it.

### B. Nested storage layout, developed

`{rootPrefix}/a/b/{tbl}-{uuid}` for namespace `a.b`. It is the intuitive layout and it is what a
filesystem-shaped mind reaches for first.

It fails on three concrete existing callers. `TableUUIDGenerator` composes
`Paths.get(rootPrefix, databaseId)` and requires the manifest-list path to start with it — under
nesting the composed path is `rootPrefix/a.b`, which does not exist, so UUID extraction throws for
every nested table. `StorageClient.getSubDirectoriesWithOwners` enumerates one level below a
database path and treats every subdirectory as a table directory — under nesting the child
namespace `b` is enumerated as a table directory of `a`, so orphan-directory deletion can classify
a populated namespace as stray. And `Storage.isPathValid` derives its prefix from
`allocateTableLocation`, so the layout change is simultaneously a path-validation change across
every storage implementation.

It also introduces a dependency that flat does not: the storage tree's *shape* would encode
namespace structure, so any future namespace rename (X1) becomes a data move. Flat keeps the tree
two levels deep forever, which is what every caller above already assumes. The cost of flat —
directory names containing dots — is cosmetic, and `.` is a legal component character on every
storage backend OpenHouse supports.

### C. Property-inheritance alternatives, developed

**C1 — Resolve inside `OpenHouseInternalCatalog.loadTable`, so every catalog consumer sees
inherited values.** Attractive because it needs no change at two separate serialization points.
Rejected on two grounds. It puts a namespace-store read on the hot path of every table load,
including the metadata-table loads §5.4 works to keep free. Worse, it creates a read-modify-write
promotion hazard: a client that loads a table and commits a full property map back — which is what
`replaceTable` does — would silently promote every inherited value into the table's local map,
converting inheritance into a one-time copy. Resolving *above* the catalog keeps the catalog's
view of a table equal to what is persisted, which is the property that makes I11 checkable.

**C2 — Materialize inherited properties into `metadata.json` at write time.** Simplest to read and
worst to own: a namespace property edit becomes a commit per table beneath it (unbounded fan-out,
non-atomic, and it rewrites tables nobody touched); unsetting a namespace property leaves stale
copies everywhere; and it breaks the extension of M1 to on-disk bytes.

**C3 — Resolve client-side from `/v1/config` `defaults`.** Iceberg's native mechanism
(`CatalogProperties.TABLE_DEFAULT_PREFIX`) and genuinely spec-sanctioned. Rejected because
`/v1/config` is per-catalog, not per-namespace: it cannot express "these properties apply under
`a.b`". It is also client-version-dependent, and the OpenHouse `/v1` surface has no equivalent, so
the two surfaces would disagree.

**C4 — Tombstones: let a table remove an inherited key.** Two shapes were considered — a reserved
sentinel value (`openhouse.unset`) and a parallel `removed-keys` list. Both add wire surface that
no spec verb maps onto: a stock client's `remove-properties` cannot express "and keep it removed
against the parent", so the feature would be reachable only through the OpenHouse API, which
defeats the point. Shadowing with a local value covers the realistic cases (the empty string is
expressible), and the tombstone can be added later without changing anything specified here.
Recorded as OQ-2.

**C5 — Namespace overrides that beat the table (`table-override.<key>`).** Iceberg's own
vocabulary and the right eventual answer for governance-mandated properties. Deferred (W3), with
the prefix reserved now so that a namespace property named `table-override.foo` cannot mean
something else later.

**Sharp edge, recorded rather than solved.** Because a local set pins a value, a `replaceTable`
carrying the full effective map will pin every inherited value it received. At that instant the
values are identical, so nothing observable changes — but the table stops tracking its namespace.
The recommended default is to accept this and document it; the alternative (diffing the incoming
map against the resolved map and dropping equal entries) guesses at intent and would silently
discard a deliberate pin. OQ-4.

### D. Privilege inheritance, developed

The case for it is real: without it, creating `a.b.c.d` means four grants, and a team that owns a
subtree has no way to say so once. Iceberg's spec is silent — authorization is entirely a server
concern — so nothing forces a choice.

Against it, three things. `AuthorizationHandler` is an SPI whose production implementations live
outside this repository; inheritance turns one `checkAccessDecision` call into up to *depth* calls,
which is a latency and semantics change in code this design cannot see or test. It is a security
default that cannot be reversed once granted-by-inheritance access exists in the wild. And no
`CatalogTests` case exercises it, so it would ship unvalidated by the harness this workstream is
being measured against.

The recommended posture is explicit grants per namespace in v1, with the door open: nothing in
§5.6 forecloses a later `AuthorizationHandler` overload that receives the ancestor chain instead of
a single subject. OQ-3 records the asymmetry with property inheritance as the thing a reviewer
should challenge.

### E. Leaving the `/v1` `databaseId` pattern at depth 1, developed

The conservative option: `/v1` stays exactly as it is, nested namespaces are reachable only over
REST, and `GET /v1/databases` never returns a dotted name.

It is genuinely safer at the validator, and it is rejected because of what lives behind `/v1`. The
jobs scheduler, the optimizer's retention/compaction/orphan-deletion apps, and the Spark plugin all
address tables as `/v1/databases/{databaseId}/tables/{tableId}`. A nested table would be invisible
to all of them: no retention, no compaction, no snapshot expiry, silently. That is a worse
compatibility story than the one the widening creates, because it is a *data-management* gap rather
than an API-shape gap, and it would not surface as an error anywhere.

The widening itself is provably additive: the new pattern
`^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)*$` accepts a superset, and every string it newly accepts was
previously a `400` for a resource that could not exist. No currently-valid request changes
behaviour, which is M1 for this seam.

### F. What `CatalogTests` does not cover here

Worth knowing before treating the harness as sufficient validation:

- Property inheritance in any form. It is an OpenHouse concept; no reference test exercises it.
  §6's I10/I11 and new OpenHouse tests are the only coverage.
- The metadata-table discriminator at depth ≥ 2. `testLoadMetadataTable` is single-level and
  disabled on `createTable`. I8/I9 are the coverage.
- Storage-path layout, ACL-subject stability, and the `/v1` surface — all outside the reference
  suite entirely.
- The compatibility invariant itself. I12 (the existing tables suite at the default cap) is the
  substitute, and it is the strongest single check in the plan.

### G. Definitions

| Term | Meaning here |
|---|---|
| **Namespace** | An ordered list of levels. Iceberg's `Namespace`. |
| **Encoded namespace** | `String.join(".", levels)` — the persisted form: HTS `database_id`, storage path component, ACL subject, `/v1` path segment. |
| **Wire namespace** | `RESTUtil.encodeNamespace(ns)` — `%1F`-separated, URL-encoded per level. Only the handler seam sees it. |
| **Table namespace** | A namespace that may host a base table: depth `1..max-depth`. |
| **Operation namespace** | The `Namespace` argument to a namespace-scoped catalog method; depth `0..max-depth`, where empty is the internal "all databases" sentinel. |
| **Local property** | A property in a table's own `metadata.json`. The only thing ever persisted. |
| **Effective property** | What a read returns: ancestors ⊕ local ⊕ reserved. Never persisted. |
| **Reserved property** | `openhouse.`-prefixed, or `policies`. Server-owned, rejected on write, never inherited. |
