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

**"Dot-join vs `%1F`" is not one decision.** §2.1 gets the first half right: it calls `%1F`
*Iceberg's own wire encoding*, which it is. The defect is the sentence immediately after —
"whichever is chosen, it becomes the HTS key format permanently the moment a client creates a
nested namespace over REST" — which puts the wire form and the persisted form in one slot and so
invites `%1F` into `house_table.database_id`. There are two encodings with two different owners:

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
identifier draws `400 IllegalArgumentException` where the test requires `404` carrying
`type = NoSuchTableException`. The type matters as much as the code, because Iceberg's client
switches on it — see §5.2. That is an **error-mapping** contract, fixable without touching the
charset — and Iceberg's own
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
  merge ahead of the REST write path without a rollout plan. This requires the `/v1` identifier
  pattern to be gated on the cap and not merely widened alongside it; §5.1 states why.
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
  Appendix C. Confirmed by owner ruling; §5.8 states the ordering that makes shadowing sufficient.
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
  lazily. That is WS2's decision about the store, and PR #56 has made it: §5.8's registrar makes
  populate-on-write the floor and explicit backfill the ceiling. This boundary is **not** satisfied
  either way, and an earlier draft claiming so was wrong: eager-vs-lazy decides whether `/v1`
  `createTable` into a fresh database starts returning `404`, and none of I1–I12 can detect that
  break, because all twelve are stated over encoding, paths, ACL subjects and `isValidIdentifier`.
  What this document therefore owes is a contract row rather than a fresh decision — §5.2 pins `/v1`
  `createTable` to implicit creation, citing PR #56 §5.6.
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
should push on. It has a second half, less obvious and worth naming because it escapes this
repository. `TableUri.toString` (`TableUri.java:26-34`) composes
`clusterId + "." + databaseId + "." + tableId`, so for namespace `a.b` the URI is `cluster.a.b.tbl`
— four fields, three dots, and no way to split them back apart. No parser for that value exists in
this repository, so depth 1 is unaffected and nothing here breaks; but the URI is published as the
reserved table property `openhouse.tableUri`, so it crosses to consumers this design cannot see,
and any of them that splits on `.` is relying on a shape dot-joining makes ambiguous.

Both halves are acceptable for the same reason: the current charset already excludes `.`, Iceberg's
reference suite has a capability flag for declining it, and no OpenHouse client can be relying on a
character the validator has always rejected. The `TableUri` half is called out separately because
its blast radius is outside this repository, which is exactly the kind of consequence a one-way
door should state rather than discover.

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
| `/v1` `databaseId` pattern | Widened to `^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)*$`, **gated on `max-depth > 1`** | Widened unconditionally (breaks S3); left depth-1-only | §5.1 |
| HTS wire charset | `databaseId` widens on the HTS wire too — seven enforcement points, two services, one change | Treating the HTS repository as an unchanged seam | §5.5 |
| `tableId` charset | Unchanged, `^[a-zA-Z0-9_]+$`, at every seam | Widening it alongside `databaseId` (destroys M3 and §5.4) | §5.5 |
| Immediate-children query | `childrenOf(encodedParent)` as a range over WS2's `databaseId` ordering | Point lookup only; full-table scan and filter | §5.5 |
| `Namespace` construction | Only via `NamespaceUtil.decode`, at every seam that sees a `databaseId` string | `Namespace.of(databaseId)` — a one-level namespace containing dots | §5.3, I15 |

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
| `POST /v1/{prefix}/namespaces` | `createNamespace` | `CreateNamespaceRequest` | `200 CreateNamespaceResponse` | `CREATE_TABLE` on the parent; `CREATE_TABLE` at the root (§5.6) |
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

**`/v1` legacy routes.** The jobs scheduler, the optimizer's retention/compaction/orphan-deletion
apps and the Spark plugin all address tables as `/v1/databases/{databaseId}/tables/{tableId}`, so
`databaseId` has to admit a dot-joined encoded namespace or a nested table is invisible to every
data-management job in the fleet (S4). Declining it altogether is the alternative in appendix E.
Three things have to hold together, and the first is where an earlier draft of this document was
wrong.

**(i) The widening is gated on the depth cap.** `databaseId` widens from
`ALPHA_NUM_UNDERSCORE_REGEX` to `^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)*$` **only when
`cluster.tables.namespace.max-depth > 1`**; at the shipped default of 1 the pattern is byte-identical
to today's. Unconditional widening would break S3 on the day it merged, and the reason is subtle
enough to state: the `/v1` seam does not build multi-level namespaces. `OpenHouseInternalRepositoryImpl`
builds `Namespace.of(databaseId)` — a **one-level** namespace whose level text contains dots — and
Iceberg's `Namespace.of` rejects only the null byte (1.5.2 `Namespace.java:38-51`), so the dots pass.
`isTableNamespace` then counts one level and approves. With the pattern widened unconditionally,
`POST /v1/databases/a.b.c.d/tables/t` would succeed at `max-depth=1`, and "a literal no-op until an
operator raises it" would be false from the first commit. The gate closes it at the default; §5.3's
construction rule closes it once the cap *is* raised. Widening remains **strictly additive** in the
raised configuration: every string that validates today still validates unchanged, and every string
newly admitted was previously a `400` naming a resource that could not exist.

**(ii) Only `databaseId` widens, and it widens in two services.** `tableId` stays
`^[a-zA-Z0-9_]+$` everywhere. And the widening is not local to the Tables Service: House Tables
re-validates `databaseId` against the same regex on its own wire, so §5.1 alone would 400 every
nested-table write at the HTS boundary. That is a cross-service contract change and §5.5 owns it,
enforcement point by enforcement point.

**(iii) The edit target is a new helper, not the existing one.** On `pr44` both
`OpenHouseTablesApiValidator` and `OpenHouseDatabasesApiValidator` delegate to
`ApiValidatorUtil.validateIdentifier` (`ApiValidatorUtil.java:78-86`), which has ten call sites in
main source spanning `databaseId`, `tableId`, and the view surface's `namespace` and `name`.
Widening *that* helper would widen table and view names too, destroying M3 and §5.4's discriminator.
The widening therefore introduces a separate
`ApiValidatorUtil.validateNamespaceIdentifier(fieldName, value, failures)` and moves exactly the
namespace-shaped call sites onto it:

| Call site (`pr44`) | Field | Moves? |
|---|---|---|
| `OpenHouseDatabasesApiValidator.java:48` | `databaseId` | **Yes** |
| `OpenHouseTablesApiValidator.java:510` | `databaseId` | **Yes** |
| `OpenHouseTablesApiValidator.java:514` | `tableId` | No — `tableId` never widens |
| `OpenHouseViewsApiValidator.java:109`, `:117`, `:129`, `:161` | view `namespace` | **Yes, with X4** — the view routes take the same path parameter and inherit this decision mechanically, but the views backend is PR #44's, so these four move when X4 is taken up |
| `OpenHouseViewsApiValidator.java:110`, `:162` | `view` | No |
| `OpenHouseViewsApiValidator.java:130` | view `name` | No |

`GET /v1/databases` then returns dot-joined encoded namespaces, which for every existing depth-1
database is the identical string it returns today.

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
`RESTUtil.encodeNamespace` are called, and the only place a `Namespace` is built from a *wire*
string. (The other producer of `Namespace` values is `NamespaceUtil.decode`, which owns everything
built from a persisted or `/v1` string — §5.3. Between them they are exhaustive, which is what I15
asserts.) It resolves the acting principal
(`AuthenticationUtils.extractAuthenticatedUserPrincipal`) and delegates to
`org.apache.iceberg.rest.CatalogHandlers` for document construction, so that
`UpdateNamespacePropertiesResponse`'s `updated`/`removed`/`missing` partition is Iceberg's
semantics rather than ours (S2).

**Must not decide:** the persisted encoding (that is `NamespaceUtil`'s, invoked below it);
authorization outcomes (that is the service layer's); property resolution (§5.8).

**Error contract** — these mappings are the boundary, not a suggestion. The `type` field is not
decoration: Iceberg's client selects the exception it throws from it, and it selects differently per
route family, so `type` and message are pinned wherever `CatalogTests` asserts on them.

| Condition | Route family | Exception | HTTP | `IcebergErrorResponse.type` | Message |
|---|---|---|---|---|---|
| Namespace absent | namespace routes | `NoSuchNamespaceException` | 404 | `NoSuchNamespaceException` | `Namespace does not exist: <ns>` — asserted, `CatalogTests.java:264-266` |
| **Namespace absent or syntactically invalid** (bad charset, over depth cap, over length) | **table routes** (`loadTable`, `tableExists`, `listTables`) | `NoSuchTableException` | **404** | **`NoSuchTableException`** | `Table does not exist: <encoded-ns>.<table>` — asserted, `CatalogTests.java:974-975` |
| **Namespace syntactically invalid** | namespace **read** routes | `NoSuchNamespaceException` | **404** | `NoSuchNamespaceException` | `Namespace does not exist: <ns>` |
| Namespace syntactically invalid | `createNamespace` | `ValidationException` | 400 | `ValidationException` | — |
| `createNamespace` on an existing namespace | `createNamespace` | `AlreadyExistsException` | 409 | `AlreadyExistsException` | — |
| `createNamespace` whose parent does not exist | `createNamespace` | `NoSuchNamespaceException` | 404 | `NoSuchNamespaceException` | — |
| `dropNamespace` on a namespace holding tables, views, or child namespaces | `dropNamespace` | `NamespaceNotEmptyException` | 409 | `NamespaceNotEmptyException` | must contain `is not empty` — asserted, `CatalogTests.java:422-424` and `:455-457` |
| A key appears in both `removals` and `updates` | `updateProperties` | `IllegalArgumentException` (from Iceberg's own request validation) | **422** | `UnprocessableEntityException` | — |
| Reserved (`openhouse.`-prefixed) key in `updates` | `updateProperties` | `ValidationException` | 400 | `ValidationException` | — |
| Principal lacks the privilege | all | `AccessDeniedException` | 403 | **`ForbiddenException`** | — |
| `createTable` into a namespace with no stored row | `/v1` `POST /v1/databases/{db}/tables` | — | **201, no error** | — | the database is created implicitly; see below |
| `createTable` into a namespace that does not exist | REST `createTable` | `NoSuchNamespaceException` | 404 | `NoSuchNamespaceException` | per spec |

**Why the invalid-namespace rows split by route family.**
`ErrorHandlers.TableErrorHandler.accept` (Iceberg 1.11.0, `ErrorHandlers.java:145-155`) throws
`NoSuchNamespaceException` when `type` is `NoSuchNamespaceException` and `NoSuchTableException`
otherwise, while `testLoadTableWithNonExistingNamespace` (`CatalogTests.java:969-976`) asserts
`NoSuchTableException` with a message starting `Table does not exist: `. So mandating
`type = NoSuchNamespaceException` on a *table* route — which an earlier draft of this document did —
selects exactly the branch that makes a conformant client throw the wrong exception, and fails the
test the mapping exists to retire. `NoSuchNamespaceException` is the correct type only on the
namespace routes and on `createTable`, where the client uses a namespace-shaped handler.

Nothing new is needed on the emitting side. PR #34's handler already throws
`new NoSuchTableException("Table does not exist: %s.%s", databaseId, table)`
(`OpenHouseIcebergRestApiHandler.java:101`), and `IcebergRestExceptionHandler.java:25-26` already
maps it to `404` with that type. The only change is letting a syntactically invalid `databaseId`
*reach* that path instead of being converted to a `400` by the validator first. The `403` row is the
same kind of correction in the other direction: `DefaultErrorHandler.accept`
(`ErrorHandlers.java:342-345`) maps `401` to `NotAuthorizedException` and `403` to
`ForbiddenException`, and `IcebergRestExceptionHandler.java:40-42` already emits `ForbiddenException`.

**A read route never returns 400 for a well-formed URL naming a resource that cannot exist** — the
distinction between "you asked wrongly" and "it isn't there" belongs to write routes only. Errors on
these routes carry the `IcebergErrorResponse` envelope, never OpenHouse's `ErrorResponseBody`, and
never a serialized stack trace.

**`/v1` `createTable` keeps implicit database creation; REST `createTable` does not.** Today the
database is implicit: `TablesServiceImpl.java:144-145` checks `CREATE_TABLE` on the `databaseId` and
the database materializes as a side effect of the first table. That does not change here, and it is
not this document's decision to make — PR #56 §5.6 states that the consumer may not assume, before
its migration state S8, that any existing `DatabasesService` method can fail with a "database not
found", and PR #56 §5.8's registrar makes populate-on-write the floor, so the row appears without
`/v1` having to ask for it. REST `createTable` returns `404` per spec. The two surfaces differ
deliberately; X2 records that the difference is a citation rather than a new decision.

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
"anti-pattern" its own TODO comment calls it — PR #56 §5.9.2 deletes that arm at migration state
S9, and appendix G records what goes with it.

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

`isTableNamespace` **widens**; it is not a rename. Today it is
`namespace.levels().length == MAX_NAMESPACE_DEPTH` — an equality — and it becomes
`1 <= depth <= maxDepth`. The two are the same predicate at `maxDepth == 1`, which is why the change
is invisible at the default, but above 1 it is a genuine change of meaning at every call site,
`isValidIdentifier` (§5.4) included. Calling it a rename would hide the one place the widening bites.

**Where `Namespace` is constructed, and by what.** A seam is only a seam if nothing bypasses it, and
on the `/v1` estate everything does. `OpenHouseInternalRepositoryImpl` builds
`Namespace.of(databaseId)` at `:847`, `:860`, `:876` and `:977`, and
`TableIdentifier.of(databaseId, tableId)` at `:117`, `:797`, `:817`, `:833`, `:840`, `:935` and
`:967-968`. Every one of those is a **one-level** namespace whose level text contains dots —
`Namespace.of("a.b")`, never `Namespace.of("a","b")` — because Iceberg's `Namespace.of` rejects only
the null byte (1.5.2 `Namespace.java:38-51`). The inverse seam has the same shape:
`OpenHouseInternalCatalog.java:108`, `:112`, `:121` and `:125` rebuild
`TableIdentifier.of(houseTable.getDatabaseId(), houseTable.getTableId())` straight from the persisted
key. That is a decode, performed by `TableIdentifier.of` rather than by `NamespaceUtil.decode`.

The rule, therefore: **every `Namespace` and every `TableIdentifier` built from a `databaseId`
string, and every one built from a `HouseTable` row, is built through `NamespaceUtil.decode`.**
`Namespace.of` and the varargs `TableIdentifier.of` are not called on a value that came from a
`databaseId` column, a `/v1` path segment, or an HTS response. Two things depend on this and nothing
else provides them: the depth cap is otherwise unenforceable on `/v1` (the cap counts levels, and
these values always have exactly one, whatever they spell), and N1's `decode(encode(ns)).equals(ns)`
otherwise fails for every namespace the `/v1` path constructs. I15 pins it as a testable rule.

**Owns:** the encoding, the depth and charset predicates, the `isValidIdentifier` verdict, and — by
I15 — sole authorship of every `Namespace` the internal catalog sees.
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

Rule (b) is needed on the `/v1` route specifically; at the SPI seam predicate (a) already suffices.
`BaseMetastoreCatalogTableBuilder`'s constructor asserts
`Preconditions.checkArgument(isValidIdentifier(identifier), ...)` (`BaseMetastoreCatalog.java:148-149`),
and (a) makes that false for a metadata-table name at depth ≥ 2, so an in-JVM create is already
impossible. What (b) adds is the *boundary's* answer: a `400` raised at the validator with a message
naming the rule, instead of an `IllegalArgumentException` surfacing from inside the commit path. It
is an error-contract rule that happens to also be a prohibition, not a second prohibition.

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
parsed. Rule (a) is what keeps that SPI both sound and fast; rule (b) is what makes the `/v1`
boundary say so in the right status code.

### 5.5 The HTS repository and storage-path seams

**HTS repository — a cross-service contract change, not an unchanged seam.**
`HouseTablePrimaryKey.databaseId` and every `HouseTableRepository.*ByDatabaseId(String databaseId,
...)` *signature* is unchanged, and what changes at that signature is the documented meaning of the
parameter: it is *the encoded namespace*, not *a database name*, and the repository must not split,
parse, or interpret it. But calling the seam itself unchanged, as an earlier draft did, is wrong.
`HouseTableRepositoryImpl` is not a repository — it is a generated HTTP client
(`housetables.client.api.UserTableApi`) to a **separately deployed service**, and that service
re-validates `databaseId` against `^[a-zA-Z0-9_]+$` on its own wire. Ship §5.1's widening alone and
every nested-table write is a `400` at the HTS boundary, surfacing to the caller at commit time as
an opaque server-side failure.

Nine enforcement points carry the charset for `databaseId`, and §5.1 reaches only two of them —
the path-parameter checks in `OpenHouseDatabasesApiValidator` and `OpenHouseTablesApiValidator`.
The other seven are below. All nine widen together, in one change, across two services; that is the
contract:

| # | Enforcement point | Service | Kind |
|---|---|---|---|
| 1 | `UserTableKey.java:33` | House Tables | `@Pattern`, primary key |
| 2 | `UserTable.java:37` | House Tables | `@Pattern`, entity |
| 3 | `SoftDeletedUserTableKey.java:31` | House Tables | `@Pattern` |
| 4 | `TableToggleStatusKey.java:23` | House Tables | `@Pattern` |
| 5 | `OpenHouseUserTableHtsApiValidator.java:27` | House Tables | imperative, `validateGetEntity` |
| 6 | `OpenHouseUserTableHtsApiValidator.java:117` | House Tables | imperative, query validation |
| 7 | `CreateUpdateTableRequestBody.java:47` | Tables | `@Pattern`, `/v1` request body |

**Only `databaseId` widens.** The `tableId` `@Pattern` sitting beside each of the above
(`UserTableKey.java:23`, `UserTable.java:27`, `SoftDeletedUserTableKey.java:23`,
`TableToggleStatusKey.java:31`, `OpenHouseUserTableHtsApiValidator.java:33`,
`CreateUpdateTableRequestBody.java:39`) stays `^[a-zA-Z0-9_]+$`. Widening it would admit a dot into a
table name, which makes the persisted `(databaseId, tableId)` pair non-injective as a rendering of
`namespace.table` (M3) and destroys §5.4's metadata-table discriminator, since `db.tbl.snapshots`
would stop being distinguishable from a table literally named `tbl.snapshots`. This is the sharpest
place in the design where "widen the charset" must not be read as one edit. I14 checks the widened
half; the property test behind M3 checks the unwidened one.

Two obligations follow at the column:

- **Length.** `database_id VARCHAR(128)`. The boundary rejects an encoded namespace longer than 128
  characters with `400 ValidationException` before it reaches the repository. Silent truncation at
  a primary key would be a data-corruption bug, and this is the real bound on depth.
- **Collation.** Case-sensitivity of namespace comparison is whatever the `database_id` column
  collation already gives depth-1 databases, applied to the whole encoded string. This design
  introduces no normalization of its own; OQ-5 records the audit PR #56 owns.

**What this boundary requires of WS2's namespace store.** Namespace rows themselves (properties,
existence, parent links) are WS2's entity. Three things are required of it. They are requirements
rather than assumptions because §5.1's routes cannot be implemented without them, and WS1 and WS2
ship together, so a dependency here is a seam to name rather than a defect to route around.

*The key.* **`databaseId` bytes are unchanged for every database that exists today, and the
namespace store's key is `encode(ns)` — the same string that appears in `house_table.database_id`,
for every namespace at every depth.** (PR #56 carries this sentence verbatim; it is the shared
handoff.) It is what makes "does namespace `a.b` contain tables" a question over one column family
rather than a join across two encodings.

*`childrenOf(encodedParent)` — the immediate children, not the subtree.* §5.1's
`listNamespaces?parent=` must return immediate children as *full* namespaces, which is what
`testListNestedNamespaces` (`CatalogTests.java:504-548`) asserts: with `parent.child1` and
`parent.child2` created, `listNamespaces(parent)` must equal exactly
`[["parent","child1"], ["parent","child2"]]`. PR #56 §5.7 offers only point lookup (`databaseExists`)
plus a complete list ordered by `databaseId`. That ordering is sufficient, which is why this is a
contract addition and not a new store: under dot-join `.` is `0x2E` and sorts below every character
in `[0-9A-Z_a-z]` (`0x30`–`0x7A`), and `/` is `0x2F`, so a parent's whole subtree is the contiguous
range `[parent + ".", parent + "/")`. `childrenOf` is therefore a **range**, not a scan. Contiguity
gives the *subtree*, so the second half must be stated rather than assumed: the range is filtered to
rows with no further `.` after the prefix. WS2 owns the ordering that makes it a range; WS1 owns the
no-further-separator filter. The range holds under any collation that orders `.` below the
identifier charset — every ASCII-ordered and every UCA-derived collation does — which is one more
reason the collation is pinned rather than inherited (OQ-5).

*`dropNamespace`'s emptiness answer is composed here.* Emptiness spans two stores, and PR #56 §5.7
offers neither predicate: `databaseExists(id)` answers existence, which is not emptiness. This
boundary composes it — `hasTables` from `HouseTableRepository.findAllByDatabaseId(encode(ns))`,
`hasChildNamespaces` from `childrenOf(encode(ns))`, non-empty is the disjunction. OQ-6 fixes the
verdict (`409` for either kind of occupant); this fixes who computes it, which is the part a
contract mismatch would otherwise leave to whichever side implemented last.

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
*parent* namespace, and `CREATE_TABLE` at the root when creating a top-level namespace.
`CREATE_TABLE` at the root is what actually matches who can create a database today:
`TablesServiceImpl.java:144-145` checks `CREATE_TABLE` on the `databaseId` and the database
materializes as a side effect of the first table. There is no `SYSTEM_ADMIN` check on database
creation for a root-level rule to mirror — `SYSTEM_ADMIN` exists in `Privileges` but is used only
for replica-table updates (`AuthorizationUtils.java:65`) — so requiring it at the root would be a
new restriction dressed as a status-quo mapping. If the owner wants root creation held to a higher
bar than table creation, that is a deliberate asymmetry to state, not one to inherit by mis-citation.
`dropNamespace` checks `DELETE_TABLE` on the namespace itself. No privilege check walks the tree.

**The asymmetry, stated plainly.** Properties inherit; privileges do not. So a principal who can
edit namespace `a` can set a property that takes effect in tables under `a.b` that they may not be
able to read. That is consistent with the model OpenHouse already has — `TablesServiceImpl` sources
`CREATE_TABLE` from a *database*-level grant, so database-level control already implies control
over what gets created beneath it — but it is a judgment call and it is OQ-3.

### 5.7 Identifier validation, in one place

Charset per level `^[a-zA-Z0-9_]+$` (W5); depth `1..cluster.tables.namespace.max-depth`; encoded
length ≤ 128. Empty namespace is legal only as the internal "all databases" sentinel in
`validateOperationNamespace`, is never addressable over REST (`supportsEmptyNamespace()` stays
`false`), and is transitional — PR #56 removes it at migration state S9 (appendix G). Validation lives in `NamespaceUtil` and nowhere else; the handler maps its
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

**The request-side counterpart, which is not optional.** A projection on the response is only half a
contract. On `/v1` the projected map comes straight back in the next request, and two pieces of
existing code then either write it down or reject it outright. Both are on the path every retention
and compaction job already takes, so this is the hot path, not an edge.

`OpenHouseInternalRepositoryImpl.checkIfPreservedTblPropsModified` (`:484-503`) throws
`ALTER_RESERVED_TBLPROPS` when the preserved keys of the existing table differ from those in the
provided body, and `BasePreservedKeyChecker.isKeyPreserved` (`:21-22`) makes every
`openhouse.`-prefixed key preserved. `openhouse.inheritedProperties` is never persisted, so
`existing` can never contain it while `provided` always will after a GET. Every `/v1` GET→PUT beneath
a propertied ancestor would fail — hard, with a reserved-property error — which is the provenance key
breaking read-modify-write on the surface that has the most of it.

`InternalRepositoryUtils.alterPropIfNeeded` (`:46-71`) is a whole-document replace: keys in the
provided map and absent from the existing one are `set`, keys in the existing map and absent from the
provided one are `remove`d. So inherited *user* keys returned in the effective map are written into
the table's own `metadata.json` on the first ordinary update. Inheritance degrades to
copy-on-first-write on the primary surface, through code that already exists, violating I11 with no
new line written anywhere.

The rule: **the `/v1` write path subtracts the resolved ancestor map and drops
`openhouse.inheritedProperties` from the provided properties before `checkIfPreservedTblPropsModified`
and `alterPropIfNeeded` see them.** Subtraction, not filtering by key name: a key whose provided
value *differs* from the inherited value is a deliberate local pin and survives, while a key whose
provided value equals the value the server just served is not a write at all. I13 states this as a
round-trip invariant, which is the form it can be tested in.

**What this costs, stated rather than hidden: pinning is not expressible on `/v1`.** A `/v1` PUT
carries a whole property map and no diff, so "pin this key to the value I was served" and "echo back
what I was served" are the same bytes on the wire. Subtraction has to read them the same way, and it
reads them as the echo, because the echo is what every existing client and every maintenance job
sends and a pin is what nobody has ever sent. Pinning stays expressible where the wire distinguishes
it: the REST `updateProperties` request carries an explicit `updates` map, and a key named there is a
write whatever its value. If `/v1` ever needs an explicit pin, the affordance is a new one, not a
reinterpretation of the round trip.

This is strictly larger than OQ-4, which admits only `replaceTable`. `replaceTable` is the case where
pinning is correct and merely surprising; the `/v1` GET→PUT is the case where it is a defect.

**The sanctioned ordering, and why the missing `/v1` pin verb is a shape rather than a gap: set the
value per table first, then set the namespace default.** This is the supported workflow for rolling
out a namespace-level default, and it is the reason the absence of a `/v1` pin affordance is not
something a later round should try to close. A reader who meets the subtraction rule cold will read
it as an unfinished defect; it is not.

1. **Set the value on each table that needs it, individually, while no ancestor carries the key.**
   The resolved ancestor map has no entry for that key, so subtraction removes nothing and the value
   is persisted as an ordinary local property. This is a plain `/v1` write — no named-key surface,
   no new verb.
2. **Set the namespace property afterwards**, as the default for everything that did not opt in. A
   namespace property edit rewrites no `metadata.json`, so step 1's local values are untouched by it.
3. **From then on the local value wins**, by the precedence order below: table-local outranks
   nearest-ancestor on read, and on write the provided value now *differs* from the inherited one,
   so subtraction reads it as the deliberate local value it is and every subsequent `/v1` GET→PUT
   leaves it alone. The state is stable, not merely correct once.

What the ordering buys is that a pin never has to be distinguished from an echo: at the moment the
value is written there is nothing to echo. "Pinning is not expressible on `/v1`" is precisely the
statement that a `/v1` client cannot pin a value it was *served* — it says nothing about a value the
client sets *before* the ancestor exists, which is local from the moment it lands.

**One boundary of that workflow, stated so nobody meets it in production.** If the namespace default
is later set to *exactly* the value a table already holds locally, the table's local entry becomes
byte-identical to the inherited one, and the next ordinary `/v1` GET→PUT subtracts it —
`alterPropIfNeeded` then sees the key absent from the provided map and present in the existing one,
and removes it. Nothing observable changes at that instant, because the effective value is the same
either way; but the table stops holding its own value and will follow later edits to the namespace
default. A table that must hold a value the namespace *also* holds needs the named-key REST route,
which is the same conclusion as the paragraph above. This is the table-level twin of appendix C's
`replaceTable` sharp edge, running in the opposite direction.

**The reverse ordering — default set first, then one table must diverge.** This is the case an
operator will actually hit, and it is a supported `/v1` operation that needs no new surface. The
client GETs the table, changes the key to the value it wants, and PUTs the whole map back: the
provided value differs from the inherited one, so subtraction leaves it and it persists locally.
Returning that table to the default is expressible too — drop the key from the PUT body entirely,
and `alterPropIfNeeded` removes the local entry, after which the table inherits again. Exactly one
thing is not expressible on `/v1` in this direction: freezing a table at the *current* default so
that a later namespace edit does not move it, since that write is byte-identical to the echo. That
one needs the REST properties route, which names the key it writes. And making an inherited key
*absent* is not expressible on any surface (W4).

**Precedence, highest first.** (1) Reserved `openhouse.*` properties — server-computed per entity
(`openhouse.tableId`, `openhouse.databaseId`, `openhouse.tableUri`, `openhouse.tableUUID`,
`openhouse.clusterId`, `openhouse.tableVersion`). Never inherited, never overridable, and rejected
in `updates` on a namespace with `400`. (2) The table's own local properties. (3) The nearest
ancestor namespace, walking up: `a.b.c` then `a.b` then `a`.

**Conflict: nearest wins, silently.** The same key at several ancestors resolves to the deepest
ancestor's value. No merge, no union, no error. Deterministic and explainable in one sentence,
which is the property that matters for something a user will debug at 3am.

**Override: yes, by setting a local value.** A table sets the key locally and its value wins at any
depth. Setting a local value equal to the currently inherited value is **not** a no-op — it pins the
value, so a later namespace edit does not move it. That is deliberate: it is the only way a table can
insulate itself. The pin is expressible on any surface that names the key it is writing, which is the
REST `updateProperties` request; it is *not* expressible in a `/v1` whole-map PUT, for the reason
given above — which is what the per-table-then-default ordering above is for.

**Un-set: no (W4).** A table cannot make an inherited key absent. `remove-properties` on a key the
table holds locally returns the key to its *inherited* value, not to absent — and the response
reports it under `removed`, because the local entry genuinely was removed. Appendix C develops the
tombstone alternative and why it is not worth its wire surface in v1. This is **settled by owner
ruling**, not a pending recommendation: `/v1` deliberately carries neither a pin verb nor a tombstone
verb, the named-key REST properties route carries the pin, and the per-table-then-default ordering
above is what makes that sufficient. It was formerly OQ-2 and is no longer an open question.

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
| I12 | With `cluster.tables.namespace.max-depth=1` (the default), every namespace-related behaviour is identical to the pre-change build | the existing tables suite (`services/tables/src/test`), run unmodified at the default cap |
| I13 | A `/v1` GET→PUT round trip on an unmodified table writes no property, for **any** set of ancestor properties — no `metadata.json` commit, no `ALTER_RESERVED_TBLPROPS`, no inherited key promoted to local | tables e2e, parameterized over ancestor property sets (§5.8) |
| I14 | Every HTS API surface accepts `encode(ns)` for every legal `ns` — every enforcement point named in §5.5, exercised through the generated client rather than in-process, because the boundary being checked is an HTTP one | HTS API test plus a cross-service test through `HouseTableRepositoryImpl` |
| I15 | No `Namespace` or `TableIdentifier` reaching the internal catalog is built by `Namespace.of` / `TableIdentifier.of` from a `databaseId` string, a `/v1` path segment or a `HouseTable` row; every such value goes through `NamespaceUtil.decode` | a construction rule over `services/tables` and `iceberg/openhouse/internalcatalog`, plus a behavioural test that `POST /v1/databases/a.b.c.d/tables/t` is rejected at `max-depth=1` |

I12 is the strongest of these and the cheapest: at the shipped default the whole change is inert,
so the existing suite *is* the regression guard, and I1–I11 are what must additionally hold once an
operator raises the cap. I12 is a claim about the **default configuration only**. It is a different
run of the build from §7's conformance gate, which requires the cap raised, and §7 states the
configuration it needs rather than leaving the two claims to be read as one.

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

**The configuration this gate runs under, which is not the shipped default.**
`tests/iceberg-rest-catalog-compat` runs with `cluster.tables.namespace.max-depth` **raised to the
OQ-1 value of 6**, not at the shipped default of 1. That has to be stated, because at the default
the two multi-level tests do not skip — they *fail*. `testListNestedNamespaces`
(`CatalogTests.java:504-548`) and `testDropNamespaceWithNestedNamespace` (`:436-471`) each
`assumeThat(supportsNestedNamespaces()).isTrue()` and then call
`createNamespace(Namespace.of("parent","child1"))`; with the flag flipped true the assumption
passes, and with the cap at 1 the create is rejected. Flipping `supportsNestedNamespaces()` is a
claim about a *configuration*, not about the build, and the flag and the cap have to move together
or the suite is red.

So there are two claims here and they are separate runs of the build. Neither substitutes for the
other:

| Claim | Configuration | What it guards |
|---|---|---|
| **I12** | `max-depth=1`, the shipped default | Regression. The existing tables suite is the guard; the compat module's nested tests are not part of it, and the compat module does not run at this cap. |
| **§7** | `max-depth=6`, compat module only | Conformance. Requires WS2's namespace store behind it, and says nothing about behaviour at the default. |

The nineteen, and what each one is waiting on:

| Test | Blocker retired by |
|---|---|
| `testCreateNamespace` | `createNamespace` route + `SupportsNamespaces` (§5.1, §5.3) |
| `testCreateExistingNamespace` | `AlreadyExistsException → 409` (§5.2) |
| `testCreateNamespaceWithProperties` | namespace properties + the subset rule (§5.8) |
| `testLoadNamespaceMetadata` | `loadNamespaceMetadata` route |
| `testSetNamespaceProperties` | `updateProperties` route |
| `testUpdateNamespaceProperties` | `updateProperties` route |
| `testUpdateAndSetNamespaceProperties` | `updateProperties` route, called twice — the test issues two `setProperties` calls and asserts each is a *subset* of what `loadNamespaceMetadata` returns (`CatalogTests.java:319-343`); it never exercises the `updated`/`removed`/`missing` partition |
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

**One test outside the nineteen goes green, rather than changing category.**
`testLoadTableWithNonExistingNamespace` is currently disabled under `BLOCKED_IDENTIFIER_CHARSET`.
§5.2's rule — a **table** route returns `404` with `type = NoSuchTableException`, not `400`, for a
syntactically invalid namespace — retires that blocker outright, and there is no second blocker
behind it: the test body creates nothing. It asserts `tableExists` is false, then that `loadTable`
throws `NoSuchTableException` with a message starting `Table does not exist: `
(`CatalogTests.java:969-976`). So its `@Disabled` **disappears**; it does not move to
`NEEDS_CREATE_TABLE`, as an earlier draft of this section claimed.

The emitting side already exists — `OpenHouseIcebergRestApiHandler.java:101` throws exactly that
exception with exactly that message, and `IcebergRestExceptionHandler.java:25-26` maps it to `404`
with that type — so the whole of the change is letting an invalid `databaseId` reach that path
instead of the validator's `400`. It is counted separately from the nineteen because it is a table
route, not a namespace one.

**Multi-level-specific coverage is thin**, and it is worth saying so: only two of the nineteen
actually exercise nesting. The invariants in §6 — particularly I8, I9 and I12 — carry more of the
validation weight than `CatalogTests` does here, and appendix F lists the cases `CatalogTests` does
not reach at all.

---

## 8. Open questions

Each carries a recommended default — or, where a sibling design has already answered it, a
citation that closes it — so none of these blocks the next phase. Each is a place where the owner's
answer should override mine.

| # | Question | Recommended default |
|---|---|---|
| **OQ-1** | What is `cluster.tables.namespace.max-depth` when an operator enables nesting? | **6**, subordinate to the hard 128-character encoded-length cap of §5.5. Deep enough for `org.team.domain.dataset`-style hierarchies, shallow enough that the length cap is rarely the thing a user hits first. |
| **OQ-3** | Properties inherit but privileges do not. Is that asymmetry acceptable? | **Yes for v1** (W1, appendix D). It is consistent with OpenHouse's existing model, where a database-level `CREATE_TABLE` grant already implies control over what is created beneath it. But it is the single judgment call in this document most worth challenging, because it cannot be reversed once inherited access exists in the wild. |
| **OQ-4** | `replaceTable` carries the full effective property map, which pins every inherited value into the table's local map. Accept or diff? | **Accept and document** — for `replaceTable`, where the client is deliberately restating the whole table. Nothing observable changes at that instant, and the alternative guesses at intent and would silently discard a deliberate pin. This is narrower than it first looked: the `/v1` GET→PUT is the *same* shape and is **not** accepted, because there the client is not restating anything. §5.8's request-side rule subtracts the resolved ancestor map on that path, and I13 tests it. What remains open is only whether `replaceTable` should be brought under the same subtraction for consistency. |
| **OQ-5** | Is namespace comparison case-sensitive at depth ≥ 2? | **Resolved by citation; no longer open.** PR #56 §5.2 #12 has closed it, and harder than this document framed it: the derived `/v1/databases` path `distinct()`s in **Java** over exact strings while every HTS table lookup is `…IgnoreCase…` and the MySQL collation folds case, so two tables spelling their database differently in case are listed twice today and once after — a pre-existing data inconsistency, not a code question. PR #56 §5.9.1 therefore makes a case-variant audit (`GROUP BY BINARY database_id` against a case-folded grouping) a **pre-migration** obligation that no later state can repair. This design inherits that answer and adds no normalization of its own. **One audit site to contribute back, which PR #56's inventory does not list:** `OpenHouseInternalCatalog.renameTable` (`:217-220`) preserves the source spelling when `from.namespace().toString().equalsIgnoreCase(to.namespace().toString())` — a case-insensitive comparison over the *whole encoded namespace*, which under dot-join makes `A.b` and `a.B` the same rename target at every depth. It belongs in the S0 audit alongside `DatabasesServiceImpl.getAllDatabases()` and `findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase`. |
| **OQ-6** | `dropNamespace` on a namespace containing only *child namespaces* (no tables) — empty or not empty? | **Not empty → `409 NamespaceNotEmptyException`.** This is what `testDropNamespaceWithNestedNamespace` asserts, and cascade is unrecoverable. It also settles decision 5 of the sequencing analysis's own list, in the non-cascading direction. |
| **OQ-7** | Does a namespace get an entry in `GET /v1/databases` before any table exists in it? | **Yes** once WS2 stores namespaces — an empty namespace is exactly the thing the current derived-database model cannot represent. Worth confirming no `/v1` client treats "listed" as "has tables". |

**OQ-2 is closed and no longer listed.** It asked whether a table should be able to *tombstone* an
inherited property rather than merely shadow it. The owner has ruled: the `/v1` surface deliberately
has no pin verb and no tombstone verb, the REST properties route — which names the key it writes —
has the pin, and §5.8's per-table-then-default ordering is what makes that sufficient. v1 ships
shadowing only (W4). The remaining numbering is left as it was so that citations to OQ-3–OQ-7
elsewhere in this document and in review still resolve.

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
Rejected on the read-modify-write promotion hazard, which is now the whole of the argument: a client
that loads a table and commits a full property map back would silently promote every inherited value
into the table's local map, converting inheritance into a one-time copy. §5.8 shows this is not
hypothetical — `alterPropIfNeeded` is exactly that client and `checkIfPreservedTblPropsModified`
turns the provenance key into a hard failure — and resolving *above* the catalog is what keeps the
hazard addressable at one seam instead of baked into the catalog's own view of a table, which is the
property that makes I11 checkable at all.

**The hot-path ground an earlier draft gave here does not hold, and is withdrawn.** Resolving at the
serialization boundary is the same hot path one layer up; it is not cheaper by being higher, and per
request it is *dearer*, because a list response resolves once per element rather than once per load.
That cost belongs in the design rather than in a rejected alternative. Two things bound it, both
already available. Within a response, the ancestor chain is a function of the namespace, and on `/v1`
a page is single-namespace by construction, so the chain is resolved **once per response** and shared
across elements — a page of 1000 tables under a depth-6 namespace is 6 ancestor lookups, not 6000.
Across responses, PR #56 §5.4's database cache already holds the property map the resolution reads,
so the steady state is a cache read per level rather than a store read. Where that cache is not
available, the resolver carries a request-scoped memo of `encode(ancestor) → properties`; that memo
is the floor, and it is a requirement on the implementation phase rather than an open question.

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
**Closed by owner ruling** rather than merely recommended, and closed together with the absence of a
`/v1` pin verb, which is the same question from the other side: neither verb exists on `/v1`, the
named-key REST route carries the pin, and §5.8's per-table-then-default ordering is what makes that
enough. Formerly OQ-2.

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
behaviour, which is M1 for this seam. Additive is not the same as inert, though, which is why §5.1
gates the pattern on `max-depth > 1`: at the default cap the newly accepted strings are not
unreachable, they are reachable and wrong, because the `/v1` seam builds a one-level namespace out
of whatever the segment spells.

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
| **Operation namespace** | The `Namespace` argument to a namespace-scoped catalog method; depth `0..max-depth`, where empty is the internal "all databases" sentinel. **Transitional** — see below. |
| **Local property** | A property in a table's own `metadata.json`. The only thing ever persisted. |
| **Effective property** | What a read returns: ancestors ⊕ local ⊕ reserved. Never persisted. |
| **Reserved property** | `openhouse.`-prefixed, or `policies`. Server-owned, rejected on write, never inherited. |

**The empty-namespace sentinel does not survive the migration.** `listTables(Namespace.empty())`'s
"all databases" arm — the one its own `TODO` calls an anti-pattern — is removed at PR #56's migration
state **S9** (PR #56 §5.2 #14 and §5.9.2), and `NamespaceUtil.validateOperationNamespace`'s depth-0
arm goes with it: once `listNamespaces` exists, no caller needs "every database" spelled as an empty
namespace. After S9, "operation namespace" and "table namespace" differ only in their upper bound,
and §5.7's "legal only as the internal sentinel" clause has nothing left to except. Recorded here
rather than in §5.3 because it is a definition that expires, and a reader meeting the term later
should not have to reconstruct why.
