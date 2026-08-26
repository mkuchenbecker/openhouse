# Appendix D: Design & Estimate — Moving to an Iceberg REST-Catalog-Native Commit

Server-side commit from typed `(requirements, updates)` pairs; catalog service remains the single writer of `metadata.json`; HTS row CAS retained as the atomic pointer swap.

OpenHouse paths are repo-relative at commit `2a9dac8`; `iceberg-ref:` prefixes reference an apache/iceberg checkout ( 1.5.2.x line, matching the `com.linkedin.iceberg` 1.5.2 fork the OpenHouse server builds against — `buildSrc/src/main/groovy/openhouse.iceberg-conventions-1.5.2.gradle:6-9`). Companion documents: [protocol.md](protocol.md) (current protocol), [Appendix A](appendix-a-snapshot-drop-bug.md) (#612 motivation), [Appendix C](appendix-c-iceberg-commit-protocol.md) (REST-native reference).

---

## 0. Headline decision and one honest correction of framing

**Decision:** Replace the OpenHouse-proprietary commit wire contract (`PUT .../tables/{t}` + `PUT .../iceberg/v2/snapshots` carrying `baseTableVersion` + full serialized snapshot list) with the Iceberg REST catalog commit contract (`POST /v1/{prefix}/namespaces/{ns}/tables/{t}` carrying `CommitTableRequest{requirements, updates}`), implemented server-side by an adapted copy of `CatalogHandlers.commit` running on top of the **existing** `OpenHouseInternalTableOperations` + HTS pointer CAS.

**Correction of framing:** today's OpenHouse client does **not** write `metadata.json` — the Tables service already writes it (`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java:356-383`). What the client *does* own is worse: it is **authoritative over the content** of the next metadata — it ships the entire final snapshot list + refs as opaque JSON smuggled through table properties, and the server merges it **subtractively** (anything absent from the payload is deleted, `OpenHouseInternalTableOperations.java:314-354`). The migration is therefore not "move the file write to the server" but "**move metadata authorship to the server**": the client sends semantic deltas plus assertions, and the server rebuilds `TableMetadata` from a fresh base every time. That is the structural change that eliminates the #612 bug class (§4.5).

What stays exactly as-is:

- **HTS as the commit point.** The single-row JPA update with `@Version` optimistic lock (`services/housetables/src/main/java/com/linkedin/openhouse/housetables/services/UserTablesServiceImpl.java:98-127`, `model/UserTableRow.java:28`) remains the one atomic arbiter. Nothing in the REST protocol requires changing HTS at all.
- **metadata.json naming, storage layout, FileIO resolution** (`OpenHouseInternalTableOperations.java:191-201`, `OpenHouseInternalCatalog.java:302-325`).
- **Data plane**: engines still write data/manifest/manifest-list files directly to storage before committing.

---

## 1. Target architecture

### 1.1 Wire contract

Adopt the Iceberg REST catalog spec (`iceberg-ref:open-api/rest-catalog-open-api.yaml`) for the table lifecycle surface, served by the existing Tables Spring service alongside the legacy endpoints during migration:

| Endpoint | Spec ref | Backing |
|---|---|---|
| `GET /v1/config` | yaml (ConfigResponse) | static: prefix, defaults (catalog name, warehouse) |
| `GET /v1/{prefix}/namespaces/{ns}/tables/{t}` (loadTable) | `ResourcePaths.java:64` | HTS row → metadata.json → `LoadTableResponse{metadata-location, metadata}` |
| `POST /v1/{prefix}/namespaces/{ns}/tables/{t}` (commit) | yaml `:592,659-663` | **new commit handler (§1.2)** |
| `POST /v1/{prefix}/namespaces/{ns}/tables` (create) | spec | maps to existing create flow, or `CatalogHandlers.createTable` |
| `POST /v1/{prefix}/transactions/commit` | yaml `:953` | phase-2+ optional; single-table subset first |

Namespace mapping: OpenHouse `databaseId` ↔ single-level REST namespace. Multi-level namespaces are rejected with 400 (OpenHouse identifiers are strictly `db.table`; `OpenHouseInternalCatalog` already assumes `TableIdentifier.of(db, table)`).

Auth: the existing JWT bearer scheme and `AuthorizationInterceptor` (`services/tables/src/main/java/com/linkedin/openhouse/tables/authorization/AuthorizationInterceptor.java`, `OpaAuthorizationHandler.java`) extend to the new routes; stock `RESTSessionCatalog` passes a static `token` header, which is compatible with OpenHouse's bearer-token model (client config: `uri`, `token`, no `credential`/OAuth2 flow needed initially).

### 1.2 Server-side commit loop (the core)

Model directly on `CatalogHandlers.commit` (`iceberg-ref:core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java:345-388`), which the server's iceberg-core 1.5.2 fork already contains on the classpath:

```
POST commit → OpenHouseRestCommitService:
  authz + locked-table + preserved-props gate (§1.4)
  table = internalCatalog.loadTable(ident)          // OpenHouseInternalCatalog
  ops   = ((BaseTable) table).operations()          // = OpenHouseInternalTableOperations
  Tasks.foreach(ops).retry(N).onlyRetryOn(CommitFailedException)
    .run(taskOps -> {
       base = isRetry ? taskOps.refresh() : taskOps.current();   // fresh HTS row + metadata.json
       request.requirements().forEach(r -> r.validate(base));    // failure → ValidationFailureException → 409, no server retry
       builder = TableMetadata.buildFrom(base);
       request.updates().forEach(u -> u.applyTo(builder));       // server re-derives metadata
       updated = builder.build();
       if (updated.changes().isEmpty()) return;
       taskOps.commit(base, updated);               // writes metadata.json + HTS CAS save
    });
```

**Key verified fact making this cheap:** `OpenHouseInternalTableOperations.doCommit` already degrades to exactly the needed primitive when the smuggled properties are absent. With no `SNAPSHOTS_JSON_KEY`, the subtractive merge block is skipped (`OpenHouseInternalTableOperations.java:314` guard); with no `COMMIT_KEY`, `abortIfWriterBaseDivergedFromCatalog` returns early (`:604-635`, guard on `SNAPSHOTS_JSON_KEY` at `:609` per report 02) and `failIfRetryUpdate` only bumps a metric (`:642-664`, else-branch `:658-663`). What remains is: `processSchemas` (no-op without evolved-schema props), openhouse.* property stamping (`:274-288`), metadata.json write (`:356-383`), and `houseTableRepository.save` with the HTS `@Version` CAS (`:401-411`). So the REST commit loop can call `taskOps.commit(base, updated)` on the **unmodified** operations class for the prototype, and the legacy-only branches are deleted in the decommission phase rather than rewritten up front.

**Server-side retries become safe and are re-enabled.** Today server-side retry is deliberately disabled (`commit.num-retries=0` forced at `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java:201-207`, plus the `failIfRetryUpdate` poison cache) because a server-side re-apply would silently rebase the client's *absolute* snapshot-list payload. With typed updates, re-application onto a refreshed base is the *defined semantics* (`MetadataUpdate.applyTo`, `iceberg-ref:core/src/main/java/org/apache/iceberg/MetadataUpdate.java:31-34`), and requirement failures are explicitly excluded from server retry via the `ValidationFailureException` wrap (`CatalogHandlers.java:363-367,383-385`). Two conflict classes emerge, exactly as in stock Iceberg:

- **Requirement failed** (e.g. `assert-ref-snapshot-id` on `main` no longer matches): 409 to the client; client refreshes and re-derives. This is the #612 defense, now first-class.
- **Store-level race** (requirements held, but a concurrent commit won the HTS `@Version` CAS between our `refresh()` and `save`): `HouseTableConcurrentUpdateException` → `CommitFailedException` (`OpenHouseInternalTableOperations.java:448-451`) → the *server* loop refreshes, re-validates, re-applies, retries — invisible to the client.

### 1.3 Reuse vs. reimplement (from the iceberg-core 1.5.2 fork)

| Component | Verdict | Notes |
|---|---|---|
| `MetadataUpdate` + `applyTo` + JSON parsers | **Reuse as-is** | `core/.../MetadataUpdate.java`, `MetadataUpdateParser` — the wire vocabulary |
| `UpdateRequirement` / `UpdateRequirements` | **Reuse as-is** | `core/.../UpdateRequirement.java:38-232`, `UpdateRequirements.java:50-174` |
| `UpdateTableRequest` / `LoadTableResponse` / `RESTSerializers` | **Reuse as-is** | `core/.../rest/requests/UpdateTableRequest.java:28`, `rest/responses/LoadTableResponse.java`; register the Jackson module on a dedicated `ObjectMapper` bean (do **not** touch the service-wide mapper) |
| `CatalogHandlers.commit` | **Adapt (copy ~60 lines)** | The loop body is reusable verbatim, but OpenHouse needs pre-commit policy gates, metrics, and its own exception→HTTP mapping around it; `CatalogHandlers` is a static utility so wrapping is trivial. Create-path (`CatalogHandlers.create`/`createTable`) reimplemented because OpenHouse allocates table location + UUID + preserved props itself (`OpenHouseInternalRepositoryImpl.java:126-153`) |
| `RESTCatalogAdapter`-style servlet | **Do not use** | OpenHouse is Spring MVC; write thin `@RestController`s that deserialize spec types and delegate |
| `ErrorHandlers` semantics (client) | **Free** | stock clients already map 409→`CommitFailedException`, 500/502/504→`CommitStateUnknownException` (`core/.../rest/ErrorHandlers.java:80-99`) |
| `OpenHouseInternalTableOperations` | **Keep, then shrink** | commit/refresh/HTS plumbing retained; legacy-only branches (§2 rows 1,3,4,5,7) deleted in Phase 4 |

Version note: server modules build against iceberg-core **1.5.2** which contains all of the above (verified against an apache/iceberg 1.5.2.x checkout). The fork (`com.linkedin.iceberg`) must be audited for patches touching `rest/`, `UpdateRequirements`, `TableMetadata.Builder` (fork patch risk → §5 R6).

### 1.4 Where OpenHouse policies hook in

All hooks live in the new commit service **before** entering the retry loop (cheap, fail-fast) plus a per-attempt *update validator* inside the loop (authoritative, sees fresh state):

1. **AuthZ**: route-level via `AuthorizationInterceptor`; operation-level by classifying the update list — snapshot/schema/property updates require the same table-write privilege the legacy `putSnapshots`/`updateTable` paths check in `TablesServiceImpl.putTable` (`services/tables/src/main/java/com/linkedin/openhouse/tables/services/TablesServiceImpl.java:99-165`). ACL grant/revoke stays on its dedicated endpoints (`updateAclPolicies`) — it is not expressible as a `TableUpdate` and should not be.
2. **Locked tables**: reject any commit when `policies.lockState.locked` (mirror `TablesServiceImpl.java:125-129` and `IcebergSnapshotsServiceImpl.java:69-76`). Implemented as a check on the loaded `TableDto`/policies before the loop; because lock state lives in the `policies` property inside metadata, also re-checked per attempt against the fresh base (a concurrent lock must not be raced past).
3. **Preserved `openhouse.*` properties**: a `RestUpdateValidator` rejects `SetProperties`/`RemoveProperties` updates touching keys where `PreservedKeyChecker.isKeyPreservedForTable` is true (`services/tables/src/main/java/com/linkedin/openhouse/tables/repository/PreservedKeyChecker.java:9-34`; semantics today enforced in `InternalRepositoryUtils.java:170-172` and `OpenHouseInternalRepositoryImpl.java:681-694`), including `policies` (`POLICIES_KEY`, staged today at `OpenHouseInternalRepositoryImpl.java:561-563`). Policy mutation continues through OpenHouse-specific endpoints/DDL, which internally may *reuse the same commit loop* with a server-constructed `SetProperties(policies=...)` update — one commit path for everything.
4. **Server-stamped bookkeeping**: `openhouse.tableVersion/tableLocation/lastModifiedTime/creationTime` remain stamped inside `doCommit` (`OpenHouseInternalTableOperations.java:274-288`) — they are catalog-owned, not client updates. `AssignUUID` from clients is rejected except during create.
5. **Replication**: replicated-table bootstrap currently rides a `CREATE` + post-commit **in-place rewrite** of the just-committed metadata.json (`OpenHouseInternalTableOperations.java:281-284,420-422` → `utils/MetadataUpdateUtils.java:37-59`, flagged by `CatalogConstants.OPENHOUSE_IS_TABLE_REPLICATED_KEY`, `CatalogConstants.java:16`). Target: replication becomes a sequence of ordinary typed commits (add-snapshot/set-ref/add-schema with `lastUpdatedMillis` supplied via a privileged server-side path), or spec `registerTable` for initial bootstrap — the final metadata is computed **before** the single write; the non-atomic rewrite (smell #3, report 01 §7) is deleted.
6. **Feature toggle**: the existing per-table toggle framework (`services/tables/src/main/java/com/linkedin/openhouse/tables/toggle/TableFeatureToggle.java:32`, `FeatureToggleAspect.java`) gates the new endpoint per table (featureId e.g. `enable-rest-commit`) — the migration flag comes for free (§4).

---

## 2. Gap analysis: every divergence → target → concrete code

| # | Current divergence (file:line) | What it becomes | Change surface |
|---|---|---|---|
| 1 | **Client-authoritative metadata content**: full snapshot list + refs smuggled as transaction properties `SNAPSHOTS_JSON_KEY`/`SNAPSHOTS_REFS_KEY` — staged at `OpenHouseInternalRepositoryImpl.java:696-708` (and CTAS `:565-573`), parsed+merged at `OpenHouseInternalTableOperations.java:298-299,314-354` via `SnapshotsUtil.java:33-47,77-90` | Typed `AddSnapshot`/`SetSnapshotRef`/`RemoveSnapshots`/`RemoveSnapshotRef` updates applied server-side to a fresh base | **Add**: REST commit controller/service (§6). **Delete (Ph4)**: `doUpdateSnapshotsIfNeeded`, the merge block `:314-354`, `SnapshotsUtil` parse paths, `SNAPSHOTS_*` constants (`CatalogConstants.java:5-6`) |
| 2 | **Subtractive snapshot merge** — absence in payload ⇒ deletion (`:337-344`); expiration and append share one ambiguous channel | Deletion only via explicit `remove-snapshots` update; append cannot delete by construction | Same as row 1; expiration jobs (`apps/`) emit `RemoveSnapshots` updates via stock client |
| 3 | **Version token = metadata path**: `baseTableVersion` → `TableDto.tableVersion` (`TablesMapper.java:71-72,94-95`), advisory `versionCheck` (`OpenHouseInternalRepositoryImpl.java:451-475`), `COMMIT_KEY` stamp (`:196`), catalog CAS `abortIfWriterBaseDivergedFromCatalog` (`OpenHouseInternalTableOperations.java:269,604-635`) | Typed requirements (`AssertTableUUID` always; `AssertRefSnapshotID` per touched ref; schema/spec/order asserts per update — `UpdateRequirements.java:50-174`) validated against the fresh base inside the commit loop | **Delete (Ph4)**: `versionCheck`, `COMMIT_KEY` plumbing, `abortIfWriterBaseDivergedFromCatalog` (superseded by requirements at the same point in the flow). HTS keeps its path-equality precheck + `@Version` CAS unchanged (`UserTableVersionMapper.java:20-47`) |
| 4 | **Server transaction retries disabled**: `commit.num-retries=0` override (`OpenHouseInternalRepositoryImpl.java:201-207,750-781`) + `failIfRetryUpdate` poison cache (`OpenHouseInternalTableOperations.java:93-94,642-664`) | Server retry loop **re-enabled** for store-level races only (`Tasks...onlyRetryOn(CommitFailedException)`; requirement failures excluded via `ValidationFailureException` wrap per `CatalogHandlers.java:348-388`) | **Add**: retry loop in new commit service. **Delete (Ph4)**: `overrideProperty` machinery, `failIfRetryUpdate` + Guava `CACHE` |
| 5 | **Dedup cache** (per-JVM, pre-commit, keyed on base path — smell #2, report 01 §7) | Obsolete: retries re-derive metadata; idempotency for *ambiguous* client retries optionally improved by server-side `AddSnapshot` snapshot-id dedupe (spec gap, report 03 §4.5) | Delete with row 4; optional `AddSnapshot`-dedupe check in commit service (Ph2) |
| 6 | **Entire Iceberg-transaction smuggling layer**: `OpenHouseInternalRepositoryImpl.save` update branch (`:178-223`) staging evolved schema (`:643-674`), sort order (`:269-280`), user props, policies, staged flags as properties; `processSchemas` unwind in doCommit (`:686-718`, swallow-bug smell #6) | Direct `TableMetadata.buildFrom(base)` + `applyTo`; schema evolution arrives as `AddSchema`/`SetCurrentSchema`, sort order as `AddSortOrder`/`SetDefaultSortOrder` | Legacy repository stays for legacy endpoints during the window; **Delete (Ph4)** with the endpoints. New path bypasses `OpenHouseInternalRepositoryImpl` entirely |
| 7 | **Replicated-create in-place rewrite** (`OpenHouseInternalTableOperations.java:281-284,420-422`; `MetadataUpdateUtils.java:37-59` `fs.create(path, true)` — smell #3) | Metadata fully computed pre-write (timestamps via privileged updates or registerTable); single immutable write | **Add**: replication commit path (Ph3). **Delete (Ph4)**: `MetadataUpdateUtils` in-place rewrite, `isReplicatedTableCreate` branches |
| 8 | **Error mapping asymmetries**: snapshots path misses `CommitStateUnknown`→503 catch (`IcebergSnapshotsServiceImpl.java:91-109` vs `TablesServiceImpl.java:171-193`); OH uses 503 for unknown while REST spec uses 500/502/504 ≙ unknown, 503 ≙ retryable-not-committed (yaml `:706-758`, `ErrorHandlers.java:90-94,220-221`) | One exception mapper for REST routes: requirement-fail/CAS-lose→409; `CommitStateUnknownException`→**500** with `"type":"CommitStateUnknownException"` body; known-not-committed overload→503 | **Add**: `IcebergRestExceptionHandler` beside `OpenHouseExceptionHandler.java` (spec `ErrorResponse` body format, not OH envelope) |
| 9 | **Custom client protocol**: `OpenHouseTableOperations.doCommit` routing (`integrations/java/iceberg-1.2/.../OpenHouseTableOperations.java:142-169,364-391`), custom error mapping (`:418-464`), `doRefresh` via `GET tables/{t}` + client-side metadata.json read (`:97-128`) | Stock `RESTTableOperations`/`RESTSessionCatalog` (`core/.../rest/RESTTableOperations.java:105-158`); refresh returns metadata in-band; commit response returns `metadata-location` + metadata (yaml `:3294-3303`) — client never re-reads the file | **Deprecate (Ph3-4)**: `openhouse-java-runtime` custom ops for table IO; keep a thin extensions client for policies/ACL DDL (§3) |
| 10 | **Stage-create / WAP** via properties `IS_STAGE_CREATE_KEY` (`OpenHouseInternalTableOperations.java:300-303,412-419`; illegal-state at `TablesServiceImpl.putTable:120-123`) | Spec staged-create: `stage-create` on create route + later commit with `assert-create` requirement (`CatalogHandlers.java:315-343`); WAP snapshots = `add-snapshot` without `set-snapshot-ref` (stage-only is native, report 03 §3.3) | Handled by adopting `CatalogHandlers` create/commit shapes (Ph2/Ph3) |
| 11 | **Rename** as a doCommit branch hitting non-CAS JPQL (`OpenHouseInternalTableOperations.java:386-400`; `UserTableHtsJdbcRepository.java:115-125` — smell #4) | Spec `POST /v1/{prefix}/tables/rename` — separate from commit; opportunity to route through a CAS-checked HTS update | **Add (Ph3)**: rename route; HTS rename CAS fix is an independent pre-existing bug, tracked separately |

---

## 3. Client story

**Can stock Iceberg REST clients talk to it when done? Yes — that is the acceptance criterion.** Any engine bundling Iceberg ≥1.2 configures:

```
spark.sql.catalog.oh = org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.oh.catalog-impl = org.apache.iceberg.rest.RESTCatalog
spark.sql.catalog.oh.uri = https://<tables-service>/
spark.sql.catalog.oh.token = <jwt>
```

- The commit-relevant protocol (`UpdateTableRequest`, requirements/updates) is stable across Iceberg 1.2→1.5, so both existing client stacks (`integrations/java/iceberg-1.2`, `integrations/java/iceberg-1.5`; Spark 3.1 and 3.5 under `integrations/spark/`) can use stock `RESTCatalog`. Validate the 1.2-client↔1.5-server matrix explicitly (older clients omit newer update types — fine; server must not *require* them).
- Clients still need storage credentials for the **data plane** (data/manifest writes) exactly as today; only metadata authorship moves. Credential vending (`LoadTableResponse.config`) is a later, optional win.
- **`OpenHouseTableOperations`/`OpenHouseCatalog` (custom client)**: retired for table IO at the end of the window. What it provides beyond Iceberg — policies DDL via Spark SQL extensions, ACL calls, OpenHouse-specific catalog ops (`GetTableResponseBody` surface) — survives as a thin "OpenHouse extensions" client hitting the OpenHouse-specific endpoints, while all read/commit traffic goes through stock `RESTCatalog`. The Spark extension syntax keeps working; only its transport changes.
- **Dual-protocol window**: both endpoint families run in the same service against the same HTS + storage. This is *safe by construction*: every commit — legacy or REST — funnels through `OpenHouseInternalTableOperations.doCommit` → `houseTableRepository.save` → HTS `@Version` CAS, so a legacy writer racing a REST writer is serialized by the same arbiter as two legacy writers today, and each loser re-derives from the winner's metadata (REST: server/client re-apply; legacy: engine retry loop). A cross-protocol concurrent-commit test is mandatory (§4 P2).
- Legacy clients never break mid-window: they keep speaking the old protocol until their runtime is swapped; tables have no protocol affinity (a table can be written by both in the same hour).

---

## 4. Phased migration plan

### Phase 0 — Prototype (internal, no client change) — see §6

Proves the core loop against real HTS + storage fixtures (`tables-test-fixtures/`). Exit: prototype test matrix green.

### Phase 1 — REST read plane + config + auth

`GET /v1/config`, `loadTable`, `listTables`, namespace mapping, spec `ErrorResponse` mapping, token auth on new routes.
**Testing**: stock `RESTCatalog` (both 1.2 and 1.5) reads/plans tables created and written via legacy protocol; golden-file comparison of `LoadTableResponse.metadata` vs the metadata.json HTS points to; authz parity tests (same principal matrix as legacy GET).
**Rollback**: new routes are additive; disable route = rollback.

### Phase 2 — Commit endpoint, production-hardened, per-table feature toggle

Commit service + retry loop, requirement validation, `RestUpdateValidator` (preserved props/policies/locked/authz classification), spec exception mapper, metrics (reuse `metricsReporter` tags), `AddSnapshot` idempotency dedupe (optional), toggle `enable-rest-commit` via `TableFeatureToggle` (`toggle/TableFeatureToggle.java:32`) so only enrolled tables accept REST commits (others 404/400 on the route).
**Testing**:
- Unit: full prototype matrix (§6) + policy-gate cases (locked table 400/409, preserved-key set → 400, unauthorized principal → 403).
- **Cross-protocol concurrency**: legacy writer vs REST writer from the same base — assert exactly one wins per round, zero snapshot loss, loser sees retriable 409 (both directions).
- **Shadow validation** (cheap, high value): for legacy `putSnapshots` commits on canary tables, additionally compute the equivalent `(requirements, updates)` server-side from (base, payload) and log/metric any divergence between merge-result and rebuild-result metadata (property-normalized diff). This validates the rebuild semantics against months of real traffic without dual-writing files.
- Fault injection: HTS 5xx/timeout on save → assert 500+`CommitStateUnknownException` body and that the orphan metadata.json is never referenced; storage IOException before write vs after write.
**Rollback**: toggle off per table; no data migration exists to undo — both protocols produce identical on-disk artifacts and HTS rows.

### Phase 3 — Client migration + remaining surface

Create/drop/rename/stage-create on REST routes; replication path redesign (row 7); `openhouse-spark-runtime` switched to stock `RESTCatalog` + extensions client, released as a new runtime version; canary tables → canary databases → default-on for new tables → org-wide. Maintenance jobs (`apps/`: snapshot expiration, orphan cleanup) move to stock client with explicit `remove-snapshots`.
**Testing**: full Spark integration suites (3.1/3.5) on the new runtime against a dual-protocol service; soak on canary databases with production-shaped concurrency; replication end-to-end (create-from-source, incremental snapshot sync, timestamp fidelity — the `lastUpdatedMillis` semantics currently patched in-place must be byte-equivalent).
**Rollback**: runtime pin rollback per workload; tables remain readable/writable by legacy runtime throughout.

### Phase 4 — Decommission legacy protocol

Announce deprecation; legacy write endpoints return 410 after traffic hits zero (metrics-gated); delete: snapshot-smuggling staging (`OpenHouseInternalRepositoryImpl.java` update branch), merge block + CAS trio + dedup cache + retry-disable machinery + `MetadataUpdateUtils` rewrite (rows 1-7); `IcebergSnapshotsController`/`Service` and client `SnapshotApi`.
**Testing**: full regression on the slimmed `doCommit`; assert deleted defenses are provably subsumed (the #612 regression test `OpenHouseInternalTableOperationsTest.java:258` is *ported* to the REST path before its production counterpart is deleted).

### 4.5 How the #612 bug class is structurally eliminated

The incident mechanism was: client-computed absolute snapshot list + `BaseTransaction.applyUpdates` silent rebase + subtractive merge ⇒ racing snapshot `S_r` computed as "to remove" (report 02 §2). In the target:

1. **No client snapshot-list authority.** The client cannot express "the snapshot set is exactly X". It can only `add-snapshot` (monotone) or explicitly `remove-snapshots [ids]`. An append literally has no vocabulary to delete `S_r`.
2. **Requirements validated against fresh state at the commit point.** `assert-ref-snapshot-id(main, expected)` is checked inside the same loop iteration that performs the CAS (`CatalogHandlers.java:358-363` shape), against `taskOps.refresh()` state — not, as today's `versionCheck`, at request-validation time before the race window.
3. **The silent-rebase machinery ceases to exist.** No Iceberg `Transaction`/`applyUpdates` on the server path, no property smuggling to be re-stamped onto a refreshed base. Server retries re-run *requirement validation first*; a stale expectation aborts with 409 rather than rebasing.
4. **Defense in depth retained**: the HTS `@Version` row CAS still backstops any server bug, exactly as today.

Invariant test (in the permanent suite, §6): *the final snapshot set ⊇ every acknowledged snapshot not explicitly named in a `remove-snapshots` update*, under randomized interleavings of concurrent appends/expirations/property commits.

---

## 5. Effort estimate and risk register

Assumptions: 1–2 engineers already fluent in this codebase; fork audit turns up no blocking iceberg-core patches; HTS untouched; estimates are engineering effort, excluding org-wide client-rollout calendar time (Phase 3 soak is weeks of wall-clock at low engineering cost).

| Phase | Scope | Eng-weeks |
|---|---|---|
| P0 | Prototype + test matrix | **2–3** |
| P1 | Read plane, config, auth, error mapping | **2–4** |
| P2 | Commit path hardened: policy gates, toggle, shadow validation, cross-protocol tests, metrics, idempotency dedupe | **5–8** |
| P3 | Create/drop/rename/stage, replication redesign, client runtime swap + extensions client, canary rollout | **4–7** |
| P4 | Decommission + deletion + regression port | **2–3** |
| **Total** | | **15–25 eng-weeks** |

Risk register (top risks × mitigations):

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Replication path** is the most bespoke consumer (in-place timestamp rewrite, intermediate schemas, `LAST_UPDATED_MS` fidelity); redesign uncovers hidden invariants | Keep replicated tables on the legacy path until last; dedicated mini-design in P3; byte-diff metadata produced by old vs new bootstrap on real replicated tables |
| R2 | **Mixed-protocol concurrency window** exposes an interaction the single-protocol tests miss (e.g. legacy subtractive merge racing a REST `remove-snapshots`) | HTS single-arbiter property + explicit cross-protocol concurrency suite in P2; shadow-validation metric on canaries before widening; per-table toggle limits blast radius |
| R3 | **Client version skew**: Spark 3.1 / iceberg-1.2 stock REST client against a 1.5-built server (serialization drift, missing update types, behavioral gaps in 1.2's RESTCatalog) | Explicit 1.2↔server compatibility matrix in P1/P2; if gaps are found, keep 3.1 workloads on the legacy protocol and gate its decommission on Spark-3.1 retirement (protocol window supports this indefinitely) |
| R4 | **Semantic regressions in server rebuild** vs today's merge (snapshot-log truncation, sequence numbers, ref edge cases like WAP/cherry-pick) | Shadow validation on real traffic (P2) is the primary catch-net; port every existing `OpenHouseInternalTableOperationsTest` scenario to the REST path |
| R5 | **CommitStateUnknown/idempotency**: client retry after ambiguous 5xx double-applies or spuriously 409s | Keep `checkCommitStatus` probing; `AddSnapshot` snapshot-id dedupe on the server (return success if the exact snapshot already landed at the asserted ref); correct 500-vs-503 spec mapping so clients never blind-retry ambiguity |
| R6 | **iceberg fork divergence**: `com.linkedin.iceberg` patches (e.g. the transaction-retryer behavior the retry-disable hack relies on) alter `rest/`/builder classes | Day-1 audit of fork patch list against `org.apache.iceberg.rest`, `UpdateRequirement*`, `TableMetadata`; pin prototype to fork artifacts, not upstream |
| R7 | Policy/authz gaps in the generic update vocabulary (a client smuggles `policies` or `openhouse.*` via set-properties) | Deny-by-default `RestUpdateValidator` driven by `PreservedKeyChecker`; fuzz test: every update type × preserved key ⇒ 400 |

---

## 6. Prototype scope (feeds the follow-up implementation task)

**Goal**: the smallest honest proof of the core loop — accept `(requirements, updates)`, validate against current HTS-backed state, rebuild metadata server-side, write metadata.json, CAS the HTS row — using production classes, not mocks of them (HTS may be the H2/test-fixture repo; storage may be local FS via existing `FileIOManager` test wiring in `tables-test-fixtures/`).

### Classes to add

1. **`services/tables/src/main/java/com/linkedin/openhouse/tables/resthandler/IcebergRestCommitController.java`**
   `@RestController`; `POST /v1/rest/namespaces/{namespace}/tables/{tableId}/commit` (prototype-private route; the spec-exact path/prefix lands in P1). Body deserialized to `org.apache.iceberg.rest.requests.UpdateTableRequest` via a dedicated `ObjectMapper` configured with `RESTSerializers.registerAll` (new `@Bean` in a `resthandler/IcebergRestJacksonConfig.java`). Response: `LoadTableResponse` JSON (metadata-location + metadata).
2. **`services/tables/src/main/java/com/linkedin/openhouse/tables/resthandler/IcebergRestCommitService.java`** — the adapted `CatalogHandlers.commit` (§1.2):
   - `table = openHouseInternalCatalog.loadTable(TableIdentifier.of(ns, tableId))` (bean already wired for `OpenHouseInternalRepositoryImpl`); `ops = ((BaseTable) table).operations()`.
   - `Tasks.foreach(ops).retry(4).exponentialBackoff(...).onlyRetryOn(CommitFailedException.class)` loop, with the `ValidationFailureException` wrapper class copied locally (it is package-private in `CatalogHandlers`, `CatalogHandlers.java:95-106`).
   - `taskOps.commit(base, updated)` — **no changes to `OpenHouseInternalTableOperations`**: with no smuggled properties present, `doCommit` already skips the merge (`:314` guard), the CAS (`:604-635` early return), and the dedup poison (`:642-664` else-branch), and performs stamp → write (`:356-383`) → HTS save (`:401-411`). One thing to verify empirically in the prototype: `BaseMetastoreTableOperations.commit`'s `base != current()` identity check holds across the loop's `refresh()` (it does in `CatalogHandlers` usage — same pattern).
   - Exception mapping (prototype-local `@ExceptionHandler`): `ValidationFailureException`→409 (spec `ErrorResponse`, type `CommitFailedException`); `CommitFailedException` (retries exhausted)→409; `CommitStateUnknownException` / `HouseTableRepositoryStateUnknownException`→**500** with type `CommitStateUnknownException`; `IllegalArgumentException`/`ValidationException` (builder rejects update)→400.
3. **`services/tables/src/main/java/com/linkedin/openhouse/tables/resthandler/RestUpdateValidator.java`** — minimal for prototype: reject `SetProperties`/`RemoveProperties` touching `PreservedKeyChecker`-preserved keys; reject `AssignUUID`; reject commits on locked tables (reuse the `isTableLocked` predicate shape from `TablesServiceImpl.java:474-477` against the loaded table's `policies` property).

No HTS changes; no `OpenHouseInternalTableOperations` changes; no client changes (prototype driven by tests constructing `UpdateTableRequest` with `UpdateRequirements.forUpdateTable(base, metadata.changes())` — i.e. exercising the exact objects a stock client would send).

### Test matrix (must pass; lives in `services/tables/src/test/.../resthandler/` using `tables-test-fixtures`, plus ops-level tests beside `iceberg/openhouse/internalcatalog/src/test/.../OpenHouseInternalTableOperationsTest.java`)

| Test | Assertion |
|---|---|
| **Snapshot-append happy path**: requirements `[assert-table-uuid, assert-ref-snapshot-id(main, S0)]`, updates `[add-snapshot S1, set-snapshot-ref main→S1]` | 200; response metadata-location == HTS row `metadataLocation`; written metadata.json contains S0+S1, `main`→S1; `openhouse.tableVersion` == prior location |
| **Concurrent-commit conflict (same ref)**: two commits from the same base, submitted sequentially against the handler (second's requirements now stale) | First: 200. Second: 409 with `CommitFailedException` type; **no metadata.json referenced by HTS was written by the loser**; HTS `@Version` advanced exactly once |
| **Concurrent independent commits (server-side re-apply)**: property-only commit racing a snapshot commit — inject an HTS `@Version` conflict on first save attempt (spy repository) so requirements still hold on refresh | Loser retried server-side, final metadata contains **both** the property change and the snapshot; client saw one 200 each |
| **Requirement failure → 409**: `assert-ref-snapshot-id(main, wrong-id)` | 409; no file write attempted (verify via spy FileIO/metrics); no server-side retry occurred (attempt counter == 1) |
| **Ambiguous HTS failure → CommitStateUnknown**: repository spy throws `HouseTableRepositoryStateUnknownException` on save | 500 with body type `CommitStateUnknownException`; metadata.json exists as unreferenced orphan; HTS row unchanged; a follow-up loadTable still serves the old metadata |
| **No-snapshot-loss regression (mirrors #612 / `OpenHouseInternalTableOperationsTest.java:258`)**: base advances T_X→T_Y (racing snapshot S_r landing on `main`) after writer computed its request at T_X; writer submits `[assert-ref-snapshot-id(main, S_TX)] + [add-snapshot S_w, set-ref main→S_w]` | 409; S_r present in current metadata afterward; then the recomputed commit (requirements re-derived at T_Y) succeeds with **both** S_r and S_w in the snapshot set |
| **Explicit expiration only**: updates `[remove-snapshots [S0]]` with valid requirements | S0 gone, everything else retained — proving deletion requires naming, and (inverse) an append request has no removal side-channel |
| **Preserved-key rejection**: `set-properties {"openhouse.tableLocation": ...}` and `{"policies": ...}` | 400, nothing written |

Exit criterion: matrix green against H2-HTS + local-FS storage fixture, plus one manual end-to-end against a dev MySQL HTS (the #612 postmortem noted H2-vs-MySQL behavioral divergence — don't certify concurrency semantics on H2 alone).

---

## Appendix: primary code anchors

- OpenHouse server commit core: `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java` (`doCommit` :250-489; merge :314-354; CAS :604-635; dedup :642-664; replicated rewrite :281-284, :420-422)
- Legacy staging/smuggling: `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java` (:111-226 save; :196 COMMIT_KEY; :201-207 retry-disable; :451-475 versionCheck; :696-708 snapshot staging)
- HTS CAS (unchanged): `services/housetables/src/main/java/com/linkedin/openhouse/housetables/services/UserTablesServiceImpl.java` :98-127; `model/UserTableRow.java` :28; `dto/mapper/UserTableVersionMapper.java` :20-47
- Policy hooks: `TablesServiceImpl.java` :125-129/:474-477 (lock), `authorization/AuthorizationInterceptor.java`, `repository/PreservedKeyChecker.java` :9-34, `toggle/TableFeatureToggle.java` :32
- Iceberg reference: `iceberg-ref:core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java` :283-388; `UpdateRequirements.java` :50-174; `UpdateRequirement.java` :38-232; `rest/RESTTableOperations.java` :105-158; `open-api/rest-catalog-open-api.yaml` :592-772, :2772-2788, :3294-3303
- Legacy client to retire: `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseTableOperations.java` :97-128, :142-169, :364-391, :418-464
