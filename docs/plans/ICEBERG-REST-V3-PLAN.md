# Iceberg REST catalog and format v3 for OpenHouse: requirements

Status: planning only. Nothing in this document is implemented. Work lands as draft PRs against the
`mkuchenbecker/openhouse` and `mkuchenbecker/iceberg` forks, then upstreams to `linkedin/*` when stable.

## 1. The shape of the work

REST comes first and v3 rides on it. The current engine protocol serializes the writer's snapshots as
JSON strings, posts them with a flattened property bag, and has the tables service rebuild `TableMetadata`
from scratch with a subtractive snapshot merge. Every metadata concept added by a spec revision (v3's
`next-row-id` and per-snapshot `first-row-id`, deletion vectors, encryption keys, partition statistics,
`unknown`/`variant`/geo types, default values) has to be hand-plumbed through that custom protocol on both
sides and kept semantically identical to what the writer's own `TableMetadata.Builder` would have done.
The Iceberg REST protocol replaces that with the writer's own `MetadataUpdate` list plus
`UpdateRequirement` preconditions, applied server-side by the same core code the writer used. Once the
service speaks REST, a v3 commit is just another update list and the server's job is to persist it and
enforce OpenHouse invariants, not to reconstruct it.

Both forks already point this way: `mkuchenbecker/iceberg` carries a `1.11.x` branch with the LinkedIn
core patches replayed onto Apache Iceberg 1.11.0 (`SUPPORTED_TABLE_FORMAT_VERSION = 4`, server-side
`CatalogHandlers` with scan planning), an `openhouse-1.5.2` branch with the default-values ORC work, and a
`spark-4.2` branch. `mkuchenbecker/openhouse` already added a REST-style per-table `config` map to the
load-table response (#644) and the read-bridge column-default shim.

## 2. Where things stand today (verified against the forks on 2026-11-21)

| Area | Fact | Consequence |
| --- | --- | --- |
| Iceberg lines | Services, Spark 3.5 and the `iceberg-1.5` client build on `com.linkedin.iceberg` 1.5.2.17. Spark 3.1 and `iceberg-1.2` client build on 1.2.0.20. | 1.5.2 caps tables at v2. v3 needs the service on a 1.11 line. |
| Java | Every module targets Java 8 (`java-minimal-conventions`). Lombok 1.18.20 blocks JDK 21. | Iceberg 1.11 requires JDK 17 at compile time. Services and 1.11-line modules must move to 17. |
| Spring | Boot 2.7.8, WebMVC + WebFlux, springdoc OpenAPI codegen drives the Java clients. | Boot 2.7 runs on 17. The REST catalog controllers are plain MVC; they must be excluded from client codegen. |
| Engine protocol | `OpenHouseTableOperations.doCommit` posts `CreateUpdateTableRequestBody` (schema JSON, time partitioning, clustering, policies, properties, sort order) and `IcebergSnapshotsRequestBody` (snapshot JSON list, refs, base version) to `/v1/databases/{db}/tables/{t}[/iceberg/v2/snapshots]`. | Lossy for v3. Replaced for REST clients, kept for legacy clients. |
| Server commit | `OpenHouseInternalRepositoryImpl.save` turns the DTO into an Iceberg transaction with OpenHouse marker properties; `OpenHouseInternalTableOperations.doCommit` decodes those markers, rebuilds schema/snapshots/refs/sort order, stamps `tableVersion`/`tableLocation`/timestamps, writes `metadata.json`, CAS-saves to HTS. | Decode and persist are fused in one method. REST needs the persist half alone. |
| Format version | `cluster.iceberg.format-version` (default 2) is stamped on every create, overriding whatever the writer asked for. HTS's own Iceberg tables are pinned to v2. | Per-table opt-in to v3 does not exist. |
| Auth | Bearer token via a pluggable `HandlerInterceptor`; privileges checked in the service layer through `AuthorizationUtils` (`CREATE_TABLE`, `GET_TABLE_METADATA`, `UPDATE_TABLE_METADATA`, `DELETE_TABLE`, `UPDATE_ACL`, `SELECT`, `LOCK_ADMIN`, `SYSTEM_ADMIN`). | REST verbs map onto the same privileges. Nothing new in the model. |
| OpenHouse-only features | Policies (retention, sharing, column tags, replication, history), ACL grant/revoke, table lock, soft delete / restore / purge, replica tables, staged create/replace, read-bridge defaults. Driven by Spark SQL extensions calling the native API. | Must survive when the engine's catalog is a stock `RESTCatalog`. |
| Views | Client `OpenHouseCatalog` extends `BaseMetastoreViewCatalog`; the tables service has no view endpoints. | Views are out of scope for the first REST cut. |
| Maintenance | Spark apps resolve the catalog from `spark.sql.catalog.openhouse.catalog-impl`; jobs run on the 1.5.2 Spark 3.5 stack. | v3 maintenance (DVs, row lineage) needs the apps on 1.11 too. |
| Tests | `delta-harness` drives real Spark SQL against an embedded service across a format/partitioning/CoW-MoR/branch matrix. Upstream Iceberg ships `CatalogTests` and a REST conformance fixture. | Both become the acceptance gates. |

## 3. Target state

A Spark 3.5 or 4.x job configured with nothing but

```
spark.sql.catalog.oh=org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.oh.type=rest
spark.sql.catalog.oh.uri=https://<tables-service>/iceberg
spark.sql.catalog.oh.warehouse=<clusterId>
spark.sql.catalog.oh.token=<bearer>
```

creates, reads, writes, alters, renames and drops OpenHouse tables, including v3 tables with deletion
vectors, row lineage, default values and the v3 types, with OpenHouse policies, ACLs, locks and soft
delete enforced exactly as they are over the native API. The native `/v1/databases/...` API stays for
legacy clients and for the OpenHouse-only operations. Both ingress paths converge on one persist path so
there is one set of invariants.

## 4. Requirements

Numbered so PRs can cite them.

### A. Toolchain and build

- **A1.** Add a third Iceberg line, `iceberg_1_11_version`, alongside 1.2 and 1.5 in `build.gradle`,
  with a `openhouse.iceberg-conventions-1.11.gradle` convention plugin and a Renovate rule capped to
  `^1\.11\.`.
- **A2.** Move `services/*`, `iceberg/openhouse/*`, `cluster/*`, `client/*` and the new 1.11 integration
  modules to JDK 17 source/target. Keep the 1.2 line modules (Spark 3.1 runtime, `iceberg-1.2` client,
  `tables-test-fixtures_2.12`) on Java 8 bytecode so Spark 3.1 clusters still load them. Decide per
  module in a table in the PR, not globally.
- **A3.** Bump Lombok to a release that compiles on JDK 17 and 21 (1.18.30+). Verify MapStruct and
  the springdoc codegen still run.
- **A4.** Decide the artifact coordinates for the fork-built Iceberg (see Decisions, D1). The build
  must resolve them without LinkedIn's Sonatype credentials.
- **A5.** The tables service is a single JVM and links one Iceberg version. Moving it to 1.11 moves the
  embedded service inside `tables-test-fixtures-iceberg-1.2` to 1.11 as well; that module already
  relocates `org.apache.iceberg` to keep the server's Iceberg separate from Spark 3.1's. Confirm the
  relocation still shields a 1.11 server from a 1.2 Spark classpath (Jackson, Guava, Caffeine, Avro).
- **A6.** CI runs the unit and integration matrix on JDK 17. Any job that must stay on 8 (Spark 3.1
  itest) is named as such.

### B. Iceberg fork (`mkuchenbecker/iceberg`)

- **B1.** Promote `1.11.x` to an `openhouse-1.11.0` release branch with a `version.properties`
  in the existing four-part scheme (`1.11.0.N`) and the shipkit/jitpack publishing that `openhouse-1.2.0`
  and `openhouse-1.5.2` use.
- **B2.** Inventory every fork-only patch on `openhouse-1.5.2` (`git log apache-iceberg-1.5.2..openhouse-1.5.2`)
  and classify each as: upstream in 1.11, replay onto 1.11, or drop. Known core patches are already
  replayed (file replication factor, delete-file replication, `TableScanUtil` content scan task,
  `RewriteFileGroup` max total size, `FileIO.newOutputFile` default, `forceVersion`). The Spark 3.5
  patches were not replayed and must be inventoried (default partitioned write distribution NONE,
  locality flag, split-size SQL property, WAP branch writes, `ExtendedV2Writes`, mergeSchema, task abort
  handling, `spark.app.name` in snapshot summary, audit `app-name`).
- **B3.** Default-value reads for ORC exist on `openhouse-1.5.2` (LI #76 forward-port, #273, #275,
  #276). Upstream 1.11 `ORCSchemaUtil` references `initialDefault` but the fork's filter-pushdown
  guards (#275, #276) need checking against upstream behavior and replaying if absent.
- **B4.** The fork's `RESTSessionCatalog` identity-passthrough (#7088 on the 1.2 branch) must be
  checked for an upstream equivalent; OpenHouse's audit path keys on the acting principal.
- **B5.** Publish `iceberg-spark-runtime-3.5_2.12`, `iceberg-spark-runtime-4.x_2.13`, `iceberg-core`,
  `iceberg-api`, `iceberg-common`, `iceberg-data`, `iceberg-bundled-guava`, `iceberg-aws`, `iceberg-azure`
  and the shaded `iceberg-hive-metastore` from the 1.11 line.

### C. Server: Iceberg REST surface (`services/tables`)

- **C1.** Mount the REST catalog under its own base path, `/iceberg/v1/...`, not under `/v1`. The native
  API owns `/v1/databases/{databaseId}/...`; a database literally named `namespaces` would otherwise
  collide with `/v1/{prefix}/namespaces/...`. Clients set `uri=https://host/iceberg`.
- **C2.** `prefix` is the OpenHouse `clusterId`. `GET /iceberg/v1/config?warehouse=<clusterId>` returns
  `overrides.prefix=<clusterId>`, `defaults` for client behavior the server wants (at minimum
  `write.metadata.delete-after-commit.enabled` and `write.metadata.previous-versions-max` from cluster
  config), and an explicit `endpoints` list. Unknown or mismatched warehouse returns 404 per spec.
- **C3.** Endpoints in the first cut, and only these advertised:
  `GET namespaces`, `GET/HEAD namespaces/{ns}`, `GET namespaces/{ns}/tables`, `POST namespaces/{ns}/tables`
  (including `stage-create`), `GET/HEAD/POST/DELETE namespaces/{ns}/tables/{t}`, `POST tables/rename`,
  `POST namespaces/{ns}/tables/{t}/metrics`. Namespaces are exactly one level deep (a database); deeper
  namespaces return 400. `POST namespaces` (create database) is accepted only if OpenHouse keeps its
  current implicit-database model, in which case it is a no-op returning 200; otherwise 405.
- **C4.** Not advertised and returning 405 with the standard `ErrorModel`: `register`, `credentials`,
  `sign`, `transactions/commit`, `plan`, `tasks`, all `views/*`, `oauth/tokens`, namespace property
  updates, namespace delete.
- **C5.** `LoadTableResponse.metadata-location` is the current `tableLocation` from HTS;
  `metadata` is the full parsed `TableMetadata` including OpenHouse's `policies` and `openhouse.*`
  properties (they are table properties today and are visible through the native API already);
  `config` is the per-table map produced by `ReadBridgeConfigResolver` plus anything else the server
  stamps (#644 made this the contract).
- **C6.** `GET table` honors the `snapshots=all|refs` query parameter. `refs` mode trims the snapshot
  list to those referenced by branches and tags.
- **C7.** Pagination (`pageToken`, `pageSize`) on list namespaces and list tables, backed by the
  existing pageable list implementation. Soft-deleted tables are excluded from listings.
- **C8.** Error mapping follows the spec's `ErrorModel` codes: 404 `NoSuchTableException` /
  `NoSuchNamespaceException`, 409 `AlreadyExistsException` for create, 409 `CommitFailedException`
  for failed requirements, 400 for validation failures, 502/504 only for `CommitStateUnknownException`
  (the client must not retry a commit that may have landed), 401/403 from the token and privilege
  layers. The native API's `OpenHouseExceptionHandler` must not swallow these into its own envelope on
  the REST paths.
- **C9.** The REST controllers are excluded from springdoc client codegen (`openhouse.service-specgen-convention`)
  so the generated `tableclient` does not grow REST stubs. Serialization uses Iceberg's
  `RESTObjectMapper`, not Spring's default Jackson configuration.
- **C10.** `POST .../metrics` accepts `ReportMetricsRequest`, hands it to the existing
  `OpenHouseReportHandler` path server-side, and returns 204. It never fails a client.

### D. Server: single persist path

- **D1.** Split `OpenHouseInternalTableOperations.doCommit` into (a) legacy marker decoding that yields
  a complete `TableMetadata`, (b) OpenHouse invariant validation over `(base, candidate)`, and (c)
  persistence (stamp `tableVersion`, `tableLocation`, `lastModifiedTime`, `creationTime`; write
  `metadata.json` at the random-UUID root path; CAS-save HTS; cache seed; rename when the identity
  properties changed). REST enters at (b). Legacy enters at (a).
- **D2.** REST `POST table` applies `UpdateRequirement.validate(base)` then each `MetadataUpdate.applyTo`
  via `TableMetadata.buildFrom(base)`, exactly as upstream `CatalogHandlers.commit` does. Reuse
  `CatalogHandlers` where it fits; wrap `OpenHouseInternalCatalog` so `CatalogHandlers.updateTable`
  receives a `BaseTable` whose ops are the (b)+(c) path.
- **D3.** Invariants enforced in (b), identical for both ingress paths:
  reserved-key protection (`PreservedKeyChecker`, `policies`, `openhouse.*`, `tableVersion`,
  `tableLocation`, HTS mirror keys) on `set-properties`/`remove-properties`;
  no partition-spec evolution (`add-spec`/`set-default-spec` rejected unless field names match the
  current spec, matching `checkPartitionSpecEvolution`); schema evolution through
  `BaseIcebergSchemaValidator` including case-insensitive column normalization; sort-order changes
  allowed; `set-location` rejected; `assign-uuid` only on create; `upgrade-format-version` subject to G2;
  locked tables reject every mutation; soft-deleted tables are invisible; replica tables accept writes
  only from the replication principal; the `replace.enabled` gate for RTAS (#640); the stale-base
  divergence abort (#612) expressed as an `assert-ref-snapshot-id`/`assert-last-assigned-field-id`
  style check rather than string-matching exception messages.
- **D4.** Policies arrive over REST only as the `policies` property. The server validates it with the
  existing `PoliciesSpecValidator` family and `TablePolicyManager`, and the RTAS policy merge
  (`mergePoliciesFromExistingTable`) applies to REST replace commits.
- **D5.** The client-side commit retry cache keyed on `COMMIT_KEY` (`failIfRetryUpdate`) has no REST
  equivalent; REST relies on requirements. Document that legacy and REST writers to the same table are
  both safe because both end in the HTS CAS.
- **D6.** `stage-create: true` maps to `isStageCreate`; `createOrReplaceTransaction` through REST maps to
  `isStageReplace`. The disabled forced refresh after staged commits stays.
- **D7.** Drop: `DELETE table` without `purgeRequested` soft-deletes (today's trash with `purgeAfterMs`);
  `purgeRequested=true` requires `DELETE_TABLE` and performs the current purge path. Restore stays
  native-only.
- **D8.** Rename goes through the existing `houseTableRepository.rename` and the same cross-database
  privilege checks as the native rename.
- **D9.** Metrics and audit: REST commits emit the same `TableAuditEvent` (with `tableProperties`, #601)
  and the same `MetricsConstant` counters as native commits, tagged with `api=rest|native`.

### E. Authentication and authorization

- **E1.** REST requests authenticate with `Authorization: Bearer <token>` through the configured
  token interceptor. No `oauth/tokens` endpoint; `credential`/`oauth2-server-uri` client properties
  are unsupported and documented as such.
- **E2.** Privilege mapping: load/exists/list → `GET_TABLE_METADATA` (database-scoped `SELECT` for
  listing), create → `CREATE_TABLE` on the database, update → `UPDATE_TABLE_METADATA` (plus
  `checkTableWritePathPrivileges` for snapshot-bearing updates, `checkReplaceTablePrivilege` for
  replace), drop → `DELETE_TABLE`, rename → both sides. ACL mutation has no REST verb and stays native.
- **E3.** The acting principal reaches the audit event and `tableCreator` the same way it does today.
  `X-Client-Version` / `User-Agent` (#636) are recorded for REST callers.
- **E4.** Multi-tenant prefix check: a token's cluster must match `{prefix}`.

### F. OpenHouse-only features over a stock REST client

- **F1.** The Spark SQL extensions (`ALTER TABLE ... SET POLICY`, `GRANT/REVOKE`, `SHOW GRANTS`,
  replication, history, sharing) currently cast the Spark catalog to `OpenHouseCatalog`. They must work
  when the catalog is `RESTCatalog`. Approach: a thin `com.linkedin.openhouse.rest.OpenHouseRestCatalog
  extends RESTCatalog implements SupportsGrantRevoke` that holds a native `tableclient` built from the
  same `uri`, `warehouse` and `token`, and `IcebergCatalogMapper` resolves either catalog type. The
  stock `RESTCatalog` must still work for everything that is not an extension statement.
- **F2.** Policy writes via `ALTER TABLE SET TBLPROPERTIES ('policies'=...)` are rejected over REST;
  policies change only through the extension statements or the native API. (The native path already
  routes them through `buildUpdatedPolicies`.)
- **F3.** Read-bridge column defaults keep working for v2 tables on 1.2 clients unchanged. For v3 tables
  the defaults are native (`initial-default`/`write-default` in the schema) and the server stops
  stamping `openhouse.read-bridge.column-default.*` for them.
- **F4.** Table lock, soft delete listing, restore, purge, and ACL endpoints remain native. The REST
  surface returns 423-equivalent 409 with a clear message when a locked table is written.

### G. Format v3

- **G1.** The server stops overwriting `format-version` on create. The writer's requested version wins;
  `cluster.iceberg.format-version` becomes the default when the writer is silent. Values above the
  cluster's `cluster.iceberg.max-format-version` (new, default 2) are rejected with 400.
- **G2.** v3 is opt-in per table behind an `IcebergFeatureGate` toggle (`format-v3.enabled`) so it
  ramps per table or database. `upgrade-format-version` from 2 to 3 is allowed when the gate is on; any
  downgrade or jump to 4 is rejected. Upgrade is irreversible and the 400 message says so.
- **G3.** v3 content the server must round-trip without touching: `next-row-id` in metadata and
  `first-row-id`/`added-rows` on snapshots; deletion-vector delete files (Puffin, `content=2` with
  `referenced-data-file`, `content-offset`, `content-size-in-bytes`); `encryption-keys` and
  `key-id`; partition statistics files; `unknown`, `variant`, `geometry`, `geography`,
  `timestamp_ns`/`timestamptz_ns` types; column `initial-default`/`write-default`; multi-arg transforms
  in partition specs and sort orders. The legacy protocol path is **not** extended for these; v3 is REST
  only (see K2).
- **G4.** Schema validation (`OpenHouseTablesApiValidator` visitor, `BaseIcebergSchemaValidator`,
  column-tag policies) accepts the v3 types and default-valued fields and treats a change of
  `write-default` as a legal evolution and a change of `initial-default` as illegal (spec).
- **G5.** Time partitioning and clustering mappers (`PartitionSpecMapper`, `ClusteringSpecValidator`)
  must not break on v3 `bucket`/`truncate` over the new types; `timestamp_ns` time partitioning is
  either supported or rejected explicitly, decided in the PR.
- **G6.** HTS's own Iceberg tables stay v2.
- **G7.** `metadata.json` writes use `TableMetadataParser` from 1.11 so v3 fields serialize; the
  `MetadataUpdateUtils` raw JSON rewrite of `last-updated-ms` for replica creates must be replaced by
  a `TableMetadata.Builder` operation before v3 metadata flows through it.
- **G8.** Spark 3.1 (Iceberg 1.2) clients cannot read v3 tables. Listing a v3 table from a 1.2 client
  must fail at load with a clear message, not with a parser exception. The per-table `config` map or a
  table property carries `format-version` so the 1.2 `OpenHouseTableOperations.doRefresh` can refuse
  cleanly.

### H. Clients and engines

- **H1.** New module pair `integrations/java/iceberg-1.11/openhouse-java-runtime` and `-itest`,
  following the 1.5 module's inherit-shared-sources pattern, containing `OpenHouseRestCatalog` (F1)
  and the version-specific legacy `OpenHouseCatalog` for callers that keep the native protocol.
- **H2.** `integrations/spark/spark-3.5` builds a variant against the 1.11 line (Spark 3.5 is
  supported by Iceberg 1.11). The antlr grammar and catalyst rules are shared; the `IcebergCatalogMapper`
  change from F1 is the only behavioral change.
- **H3.** `integrations/spark/spark-4.x` (Scala 2.13, JDK 17) is a follow-on milestone, consuming the
  fork's `spark-4.2` branch. It is not a prerequisite for REST or v3 on Spark 3.5.
- **H4.** The docker-compose recipes (`infra/recipes/docker-compose/*`) gain a `spark-rest` profile that
  configures Spark with `type=rest` and no OpenHouse jar on the classpath, as the smoke test for
  "stock client works".
- **H5.** Python/Trino/Flink get REST for free; add a PyIceberg smoke test to the itest suite since it
  is the cheapest independent conformance check.

### I. Maintenance and jobs

- **I1.** `apps/spark-3.5` moves to the 1.11 line with the service. Jobs switch their catalog to REST
  (`catalog-impl` → `type=rest`) once F1 lands; until then they keep the native catalog.
- **I2.** v3-aware maintenance: snapshot expiration and orphan-file deletion must recognize DV Puffin
  files and partition-statistics files as referenced content (1.11 actions do; our
  `OrphanFilesDeletion` and directory-deletion paths that walk the filesystem directly need an audit);
  `RewritePositionDeleteFiles` becomes DV rewrite on v3; `TableStatsCollector` must count
  `content=2` delete files and expose row-lineage fields if we report them.
- **I3.** Retention via `DELETE FROM` on a v3 merge-on-read table produces DVs; the retention app's
  "rows deleted" accounting must read `added-rows`/removed-rows summary fields that exist on v3
  snapshots.
- **I4.** Replication of v3 tables is out of scope for the first cut; `tableType=REPLICA_TABLE` creates
  are rejected when the source metadata is v3 until the replication protocol carries `next-row-id`.

### J. Testing and acceptance

- **J1.** Run upstream Iceberg's `CatalogTests<RESTCatalog>` (and `TestRESTCatalog`'s HTTP-level
  cases) against an embedded tables service started from `tables-test-fixtures-iceberg-1.11`. Cases that
  exercise unsupported endpoints (C4) or OpenHouse-disallowed behavior (partition evolution,
  `set-location`) are listed and skipped with the requirement number as the reason; nothing else is
  skipped.
- **J2.** `delta-harness` gains two substrates: catalog mode (`native|rest`) and `format-version`
  (`2|3`). Every existing block runs on both; the `G`-series findings are re-verified under REST. New
  blocks: DV merge-on-read delete/update/merge, row-lineage assertions (`_row_id`,
  `_last_updated_sequence_number` stable across compaction), default-value add-column then read old
  files, `variant` round trip, v2→v3 upgrade mid-matrix, 1.2-client load of a v3 table (G8).
- **J3.** Mixed-writer test: one legacy-protocol writer and one REST writer alternate commits on the
  same table; the HTS CAS must serialize them and both must see consistent snapshots.
- **J4.** Authorization tests for every REST verb against every privilege in E2, including the wrong
  `{prefix}`.
- **J5.** Reserved-property tests: every key in `PreservedKeyChecker` attempted via `set-properties`
  and `remove-properties` over REST.
- **J6.** PyIceberg smoke test (H5) in CI against the docker recipe.

### K. Compatibility and rollout

- **K1.** The native API is unchanged. Existing 1.2 and 1.5 clients keep working against a 1.11
  service for v2 tables; this is a hard gate on the service upgrade PR (run the 1.2 and 1.5 itests
  against the 1.11 service).
- **K2.** v3 tables are REST-only. A legacy-protocol commit against a v3 table is rejected with 400
  and a message naming the REST endpoint. This avoids extending the lossy protocol and bounds the work.
- **K3.** Rollout order: service on 1.11 with v2-only (dark); REST surface on, advertised to no one;
  REST conformance green; Spark 3.5 REST runtime; `format-v3.enabled` gate per table; jobs on REST;
  Spark 4.x.
- **K4.** Metrics distinguish `api=rest|native` so the ramp is observable.

### L. Out of scope for this plan

Views over REST; multi-table `transactions/commit`; server-side scan planning; credential vending and
remote signing (HDFS deployments have no use for them; blob-store deployments can add them later);
`register` (table import); format v4; Flink/Trino runtime modules in this repo; replacing HTS.

## 5. Decisions needed before M0

- **D1. Fork artifact coordinates.** Publishing `com.linkedin.iceberg:*:1.11.0.N` to Maven Central needs
  LinkedIn's Sonatype credentials. Options: (a) JitPack as `com.github.mkuchenbecker.iceberg` with a
  Gradle dependency-substitution rule mapping `com.linkedin.iceberg` to it in the fork's `build.gradle`,
  (b) GitHub Packages on the fork, (c) a Gradle composite build (`includeBuild`) for local work and (a)
  for CI. Recommend (c)+(a): zero publishing friction while the API is moving, and the substitution rule
  is deleted when it upstreams.
- **D2. Database creation semantics.** Keep implicit databases (REST `POST namespaces` is a no-op) or
  make databases explicit. Recommend implicit; it changes nothing for native clients.
- **D3. Policies as a property versus a REST-visible structure.** Exposing `policies` JSON as a table
  property means stock clients see it and can try to set it (rejected by F2). The alternative, stripping
  it from `LoadTableResponse.metadata`, breaks the "metadata is the metadata.json" contract. Recommend
  exposing and rejecting writes.
- **D4. JDK 17 for the Spark 3.5 runtime.** Spark 3.5 runs on 8/11/17. Building the OpenHouse Spark 3.5
  runtime against Iceberg 1.11 forces 17 bytecode onto any Spark 3.5 cluster still on JDK 11. Confirm
  the fleet's JDK before committing to one Spark 3.5 artifact.
- **D5. Prefix as clusterId versus no prefix.** A single-cluster deployment could drop the prefix.
  Recommend keeping it: it is the only place the REST surface carries `clusterId`, which the audit and
  table URI need.

## 6. Milestones

Each is a draft PR against the fork; parallel unless noted. Numbers refer to section 4.

| # | Milestone | Requirements | Depends on |
| --- | --- | --- | --- |
| M0 | Fork: `openhouse-1.11.0` branch, patch inventory, publish/substitution wiring | B1–B5, A4 | D1 |
| M1 | Build: third Iceberg line, JDK 17 for service modules, Lombok bump, CI matrix | A1–A3, A5, A6 | M0 |
| M2 | Service on 1.11, v2 only, native itests for 1.2 and 1.5 green against it | K1, G6, G7 | M1 |
| M3 | Refactor: split decode / validate / persist in `OpenHouseInternalTableOperations` and `OpenHouseInternalRepositoryImpl`, no behavior change, `delta-harness` green | D1, D3 (as extracted today) | M2 |
| M4 | REST read surface: config, namespaces, list, load, exists, metrics, errors, auth, codegen exclusion | C1–C10, E1–E4 | M2 (parallel with M3) |
| M5 | REST write surface: create, stage-create, update, drop, rename; invariants; audit | D2–D9, F2, F4 | M3, M4 |
| M6 | Conformance: upstream `CatalogTests`, mixed-writer, authz and reserved-key suites | J1, J3–J5 | M5 |
| M7 | Client: `iceberg-1.11` runtime with `OpenHouseRestCatalog`, Spark 3.5 runtime on 1.11, extension mapper | F1, H1, H2, H4 | M5 |
| M8 | v3 enablement: format-version handling, gate, upgrade path, schema/type validation, G8 refusal | G1–G5, G8, K2 | M5 |
| M9 | `delta-harness` REST and v3 substrates, PyIceberg smoke | J2, J6, H5 | M7, M8 |
| M10 | Jobs on 1.11 and REST, v3-aware maintenance audit | I1–I4 | M7, M8 |
| M11 | Spark 4.x runtime | H3 | M7, fork `spark-4.2` |

M3 and M4 are the two biggest independent lifts and should run concurrently. M8 is small once M5 exists;
the cost of v3 is almost entirely in M2 (getting onto 1.11) and M10 (maintenance), which is the point of
section 1.

## 7. Risks

- **Relocation shielding (A5).** A 1.11 server embedded in Spark 3.1 tests shares a JVM with Iceberg
  1.2. The existing relocation was tuned for 1.5; 1.11 pulls newer Jackson, Caffeine and Guava and drops
  Java 8. If it cannot be shielded, the 1.2 itests run the service out of process (docker) instead.
- **Fork patch drift (B2).** Spark-side LinkedIn patches on 1.5.2 were never replayed. Any that
  change write semantics (distribution mode, WAP) silently change behavior for jobs moved to 1.11.
  The inventory is the mitigation; the harness is the detector.
- **Invariant parity (D3).** The native path enforces invariants on DTO fields; REST enforces them on
  `MetadataUpdate`s. A gap is a policy bypass. J5 and J3 exist for this; the single persist path (D1)
  is what makes parity structural rather than aspirational.
- **Row lineage through the legacy path (K2).** If K2 is relaxed and v3 tables accept legacy commits,
  the subtractive snapshot merge must assign `first-row-id` exactly as the writer's builder did or
  lineage silently corrupts. Keeping v3 REST-only removes the risk entirely.
- **JDK fleet (D4).** A JDK 17 Spark 3.5 artifact on JDK 11 executors fails at class load.
- **Error envelope collisions (C8).** Spring's global exception handler currently wraps everything in
  OpenHouse's envelope; REST clients parse the Iceberg `ErrorModel`. A missed path makes every client
  error opaque.
