# Appendix D: Design & Estimate — Moving to an Iceberg REST-Catalog-Native Commit

Server-side commit from typed `(requirements, updates)` pairs; catalog service remains the single writer of `metadata.json`; HTS row CAS retained as the atomic pointer swap.

OpenHouse paths are repo-relative at commit `2a9dac8`; `iceberg-ref:` prefixes reference an apache/iceberg checkout (1.5.2.x line, matching the `com.linkedin.iceberg` 1.5.2 fork the OpenHouse server builds against — `buildSrc/src/main/groovy/openhouse.iceberg-conventions-1.5.2.gradle:6-9`). Companion documents: [protocol.md](protocol.md) (current protocol), [Appendix A](appendix-a-snapshot-drop-bug.md) (#612 motivation), [Appendix B](appendix-b-code-review.md) (the defects this design removes or inherits), [Appendix C](appendix-c-iceberg-commit-protocol.md) (REST-native reference).

---

## 1. Problem

The OpenHouse client is authoritative over the *content* of the next metadata. It does not write `metadata.json` — the Tables service already does that (`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java:356-383`) — but it ships the entire final snapshot list plus refs as opaque JSON smuggled through table properties, and the server merges that payload **subtractively**: anything absent from the payload is deleted (`OpenHouseInternalTableOperations.java:314-354`, [protocol.md](protocol.md) §5).

An append and an expiration are therefore the same request shape, distinguished only by what the payload omits. If the payload was computed against a base the server is no longer committing on top of, the merge computes a concurrently committed snapshot as "to remove" and silently deletes durably acknowledged data. That is incident #612 ([Appendix A](appendix-a-snapshot-drop-bug.md) §2), and the only defense against it is a trio of string comparisons on the metadata path ([protocol.md](protocol.md) §2), one of which had to be added after the loss. Two structural consequences follow and are the reason this document exists:

1. **The snapshot set has no structural protection**, only checks. Every commit flavor that skips a check — rename, replace, stage-create — is wholesale-authoritative over the snapshot set by construction ([Appendix A](appendix-a-snapshot-drop-bug.md) §3 "deliberate scope exclusions"; [Appendix B](appendix-b-code-review.md) findings 3 and 14).
2. **Conflict granularity is whole-table.** Any two concurrent commits conflict regardless of logical independence, because the unit of comparison is the metadata pointer rather than the facts a commit depends on ([protocol.md](protocol.md) §7).

The fix that removes both by construction is to move metadata *authorship* to the server: the client sends semantic deltas plus assertions, and the server rebuilds `TableMetadata` from a fresh base on every attempt. §4.3 shows why that eliminates the #612 class rather than narrowing its window.

What must not change, and does not change under any option below: HTS remains the commit point (the single-row JPA update with `@Version` optimistic lock at `services/housetables/src/main/java/com/linkedin/openhouse/housetables/services/UserTablesServiceImpl.java:98-127`, `model/UserTableRow.java:28`); `metadata.json` naming, storage layout, and FileIO resolution stay as they are (`OpenHouseInternalTableOperations.java:191-201`, `OpenHouseInternalCatalog.java:302-325`); and engines keep writing data, manifest, and manifest-list files directly to storage before committing.

---

## 2. Requirements

Four must-requirements decide the choice; each one rejects at least one option in §3, and each is grounded in the problem above rather than in the design that follows.

| # | Must | Where it comes from |
|---|---|---|
| M1 | **Deletion requires naming.** No commit may remove a snapshot the request did not explicitly identify, whatever raced it. | The loss mechanism itself: subtractive merge over a client-absolute payload ([Appendix A](appendix-a-snapshot-drop-bug.md) §2, [protocol.md](protocol.md) §5) |
| M2 | **No exempt write path.** Every operation that moves the table pointer is covered by the same conflict check, at the same point in the flow. | The exclusions that survived fix #612: rename ([Appendix B](appendix-b-code-review.md) finding 3), replace/stage-create ([Appendix B](appendix-b-code-review.md) finding 14, [Appendix A](appendix-a-snapshot-drop-bug.md) §3) |
| M3 | **Every OpenHouse policy gate keeps its hook at the commit point**: lock state, preserved `openhouse.*` and `policies` keys, authorization classification, per-table feature toggle. | These gates exist only in the Spring service layer today (`TablesServiceImpl.java:125-129`, `PreservedKeyChecker.java:9-34`, `AuthorizationInterceptor`, `toggle/TableFeatureToggle.java:32`); a commit path that bypasses them is a regression regardless of its concurrency properties |
| M4 | **HTS's `@Version` row CAS stays the single atomic arbiter**, with no HTS schema change, no storage-layout or `metadata.json`-naming change, and no data migration. | [protocol.md](protocol.md) §4: the whole protocol's atomicity argument rests on that one row update, and it is the only part of the system with no known defect |

One further must constrains the plan rather than the choice, because every option that keeps the legacy endpoints running satisfies it identically: **legacy clients keep committing unmodified for the whole migration window, with per-table rollback and no data migration to undo.**

Should-requirements, in descending order of what they are worth:

| # | Should | Status under the recommendation |
|---|---|---|
| S1 | Server-side retry of store-level races becomes safe and is re-enabled (it is disabled and poisoned today, `OpenHouseInternalRepositoryImpl.java:201-207`, [Appendix B](appendix-b-code-review.md) finding 5) | Met: re-applying typed updates onto a refreshed base is the defined semantics |
| S2 | Unknown commit state renders per the ecosystem contract (typed 500/502/504, not 503 — [Appendix B](appendix-b-code-review.md) finding 12) | Met, and free: stock client error handling already assumes it |
| S3 | Conflicts are scoped to asserted facts rather than to the whole table ([protocol.md](protocol.md) §7 limit 1) | Met |
| S4 | An ambiguous client retry is answered idempotently rather than as a fresh conflict ([Appendix B](appendix-b-code-review.md) finding 15) | **Not met.** The Iceberg REST protocol shares this gap ([protocol.md](protocol.md) §7 limit 3); §5.6 R5 buys back only the `AddSnapshot` case |

Won't do / out of scope, declared so that they are not relitigated as oversights:

1. HTS schema or storage-layout change of any kind (M4).
2. Multi-level namespaces: OpenHouse identifiers are strictly `db.table`, so a multi-level namespace is rejected with 400.
3. ACL grants and policy DDL expressed as `TableUpdate`s. They are not expressible in that vocabulary and stay on their dedicated endpoints (§5.2).
4. Multi-table transactions (`POST /v1/{prefix}/transactions/commit`). Single-table commit first; the multi-table route is optional later work.
5. The HTS rename CAS. It is a pre-existing defect on a path this migration does not touch, and it ships as its own change ([Appendix B](appendix-b-code-review.md) finding 3).
6. OAuth2 credential flow and storage-credential vending. The existing JWT bearer scheme carries the new routes; credential vending is a later, optional win.

---

## 3. Options and recommendation

| # | Option | M1 deletion requires naming | M2 no exempt path | M3 policy gates keep their hook | M4 HTS unchanged as sole arbiter |
|---|---|---|---|---|---|
| **a** | **REST-catalog-native commit** (recommended): adopt the Iceberg REST `CommitTableRequest{requirements, updates}` contract, served by the existing Tables service on top of the existing `OpenHouseInternalTableOperations` + HTS CAS | **Yes** — an append has no vocabulary to remove | **No** — rename and replace stay outside the commit route | Yes, re-implemented as `@RestController` gates (§5.2) | Yes |
| b | Harden in place: ship [Appendix B](appendix-b-code-review.md)'s nine blocking fixes and extend the #612 CAS to the exempt flavors, keeping the proprietary wire contract | **No** — a payload still says "the snapshot set is exactly X"; guards narrow windows, they do not remove the vocabulary | Yes — that is what the option buys | Yes, untouched | Yes |
| c | Server-authored metadata over the **existing** wire contract: the server derives typed updates by diffing the payload against the writer's *declared* base, then applies them to a fresh base | **Yes** — a removal set computed against the declared base cannot contain a snapshot that landed after it | **No** — same exempt flavors as (a) | Yes, untouched | Yes |
| d | Put an off-the-shelf REST catalog implementation (`RESTCatalogAdapter`, or a separate catalog product) in front of HTS | Yes | Not addressed | **No** — a servlet adapter runs outside the Spring interceptor chain the gates live in; a separate catalog product has none of them | **No** — a separate product brings its own commit point and store; the adapter variant needs the gate plumbing that *is* option (a) |
| e | Do nothing | **No** | **No** | Yes | Yes |

M1 rejects (b) and (e). M3 and M4 reject (d). What remains is (a) and (c), and **no must-requirement separates them**: both make deletion explicit, both leave rename and replace to be fixed on their own terms, both keep HTS and every policy gate.

**Recommendation: adopt (a), conditional on OpenHouse committing to retire its bespoke client protocol.** That commitment is the deciding criterion, and it is a should rather than a must: (a) expresses commits in a vocabulary the client stacks already implement (`MetadataUpdate`, `UpdateRequirement`, on the classpath of the 1.5.2 fork the server already builds against), so stock `RESTCatalog` replaces `OpenHouseTableOperations` for table IO, and the five client-side defects in [Appendix B](appendix-b-code-review.md) (findings 6, 9, 14, 21, 27) stop being OpenHouse's to maintain. (c) reaches M1 with a vocabulary OpenHouse would have to specify, version, and defend alone.

What the recommendation trades away is time to the fix. (c) removes the #612 class **with no client change at all** — the derivation runs entirely inside `doCommit`, which already holds both the declared base and the payload — so it lands in a fraction of the effort priced in §5.6 and carries none of R3's client-version-skew risk. If the client-retirement commitment is not made, (c) is the better decision and (a) becomes optional modernization. The two are not exclusive: (c)'s server-side derivation is the same rebuild-from-fresh-base semantics that (a)'s Phase 2 shadow validation computes anyway (§5.5), so shipping (c) first is a down payment on (a) rather than a detour.

Either way, the nine blocking findings in [Appendix B](appendix-b-code-review.md) still have to ship. The legacy path stays live for the whole window under every option here, and none of them repairs it.

The rejected options are developed in the appendix.

---

## 4. Sketch

### 4.1 Wire contract

Adopt the Iceberg REST catalog spec (`iceberg-ref:open-api/rest-catalog-open-api.yaml`) for the table lifecycle surface, served by the existing Tables Spring service alongside the legacy endpoints during migration:

| Endpoint | Spec ref | Backing |
|---|---|---|
| `GET /v1/config` | yaml (ConfigResponse) | static: prefix, defaults (catalog name, warehouse) |
| `GET /v1/{prefix}/namespaces/{ns}/tables/{t}` (loadTable) | `ResourcePaths.java:64` | HTS row → metadata.json → `LoadTableResponse{metadata-location, metadata}` |
| `POST /v1/{prefix}/namespaces/{ns}/tables/{t}` (commit) | yaml `:592,659-663` | **new commit handler (§4.2)** |
| `POST /v1/{prefix}/namespaces/{ns}/tables` (create) | spec | maps to existing create flow, or `CatalogHandlers.createTable` |
| `POST /v1/{prefix}/transactions/commit` | yaml `:953` | out of scope for this program (§2) |

Namespace mapping: OpenHouse `databaseId` ↔ single-level REST namespace, with multi-level namespaces rejected 400 (`OpenHouseInternalCatalog` already assumes `TableIdentifier.of(db, table)`).

Auth: the existing JWT bearer scheme and `AuthorizationInterceptor` (`services/tables/src/main/java/com/linkedin/openhouse/tables/authorization/AuthorizationInterceptor.java`, `OpaAuthorizationHandler.java`) extend to the new routes; stock `RESTSessionCatalog` passes a static `token` header, which is compatible with OpenHouse's bearer-token model (client config: `uri`, `token`, no `credential`/OAuth2 flow needed initially).

### 4.2 Server-side commit loop

Model directly on `CatalogHandlers.commit` (`iceberg-ref:core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java:345-388`), which the server's iceberg-core 1.5.2 fork already contains on the classpath:

```
POST commit → OpenHouseRestCommitService:
  authz + locked-table + preserved-props gate (§5.2)
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

The loop runs on the **unmodified** operations class, because `OpenHouseInternalTableOperations.doCommit` already degrades to exactly the needed primitive when the smuggled properties are absent. With no `SNAPSHOTS_JSON_KEY`, the subtractive merge block is skipped (`:314` guard); with no `COMMIT_KEY`, `abortIfWriterBaseDivergedFromCatalog` returns early (`:604-635`, guard on `SNAPSHOTS_JSON_KEY` at `:609`) and `failIfRetryUpdate` only bumps a metric (`:642-664`, else-branch `:658-663`). What remains is `processSchemas` (a no-op without evolved-schema props), `openhouse.*` property stamping (`:274-288`), the metadata.json write (`:356-383`), and `houseTableRepository.save` with the HTS `@Version` CAS (`:401-411`). The legacy-only branches are therefore deleted in the decommission phase rather than rewritten up front. §5.7 records that this degradation holds in practice: the delivered prototype commits through that class with no changes to it.

**Server-side retries become safe and are re-enabled.** Today server-side retry is deliberately disabled (`commit.num-retries=0` forced at `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java:201-207`, plus the `failIfRetryUpdate` poison cache) because a server-side re-apply would silently rebase the client's *absolute* snapshot-list payload. With typed updates, re-application onto a refreshed base is the *defined semantics* (`MetadataUpdate.applyTo`, `iceberg-ref:core/src/main/java/org/apache/iceberg/MetadataUpdate.java:31-34`), and requirement failures are explicitly excluded from server retry via the `ValidationFailureException` wrap (`CatalogHandlers.java:363-367,383-385`). Two conflict classes emerge, exactly as in stock Iceberg:

- **Requirement failed** (e.g. `assert-ref-snapshot-id` on `main` no longer matches): 409 to the client; client refreshes and re-derives. This is the #612 defense, now first-class.
- **Store-level race** (requirements held, but a concurrent commit won the HTS `@Version` CAS between our `refresh()` and `save`): `HouseTableConcurrentUpdateException` → `CommitFailedException` (`OpenHouseInternalTableOperations.java:448-451`) → the *server* loop refreshes, re-validates, re-applies, retries — invisible to the client.

### 4.3 Why the #612 bug class is eliminated rather than narrowed

The incident mechanism was: client-computed absolute snapshot list + `BaseTransaction.applyUpdates` silent rebase + subtractive merge ⇒ racing snapshot `S_r` computed as "to remove" ([Appendix A](appendix-a-snapshot-drop-bug.md) §2). In the target:

1. **No client snapshot-list authority.** The client cannot express "the snapshot set is exactly X". It can only `add-snapshot` (monotone) or explicitly `remove-snapshots [ids]`. An append literally has no vocabulary to delete `S_r`.
2. **Requirements validated against fresh state at the commit point.** `assert-ref-snapshot-id(main, expected)` is checked inside the same loop iteration that performs the CAS (`CatalogHandlers.java:358-363` shape), against `taskOps.refresh()` state — not, as today's `versionCheck`, at request-validation time before the race window.
3. **The silent-rebase machinery ceases to exist.** No Iceberg `Transaction`/`applyUpdates` on the server path, no property smuggling to be re-stamped onto a refreshed base. Server retries re-run *requirement validation first*; a stale expectation aborts with 409 rather than rebasing.
4. **Defense in depth retained**: the HTS `@Version` row CAS still backstops any server bug, exactly as today.

The permanent suite pins this as an invariant: *the final snapshot set ⊇ every acknowledged snapshot not explicitly named in a `remove-snapshots` update*, under randomized interleavings of concurrent appends, expirations, and property commits. The delivered prototype carries the two-writer instance of it (§5.7).

---

## 5. Details

### 5.1 Reuse vs. reimplement (from the iceberg-core 1.5.2 fork)

Most of the wire vocabulary is already on the classpath; the only substantial new code is the commit service and its gates.

| Component | Verdict | Notes |
|---|---|---|
| `MetadataUpdate` + `applyTo` + JSON parsers | **Reuse as-is** | `core/.../MetadataUpdate.java`, `MetadataUpdateParser` — the wire vocabulary |
| `UpdateRequirement` / `UpdateRequirements` | **Reuse as-is** | `core/.../UpdateRequirement.java:38-232`, `UpdateRequirements.java:50-174` |
| `UpdateTableRequest` / `LoadTableResponse` / `RESTSerializers` | **Reuse as-is** | `core/.../rest/requests/UpdateTableRequest.java:28`, `rest/responses/LoadTableResponse.java`; register the Jackson module on a dedicated `ObjectMapper` bean (do **not** touch the service-wide mapper) |
| `CatalogHandlers.commit` | **Adapt (copy ~60 lines)** | The loop body is reusable verbatim, but OpenHouse needs pre-commit policy gates, metrics, and its own exception→HTTP mapping around it; `CatalogHandlers` is a static utility so wrapping is trivial. Create-path (`CatalogHandlers.create`/`createTable`) reimplemented because OpenHouse allocates table location + UUID + preserved props itself (`OpenHouseInternalRepositoryImpl.java:126-153`) |
| `RESTCatalogAdapter`-style servlet | **Do not use** | OpenHouse is Spring MVC and its policy gates live in that chain (M3); write thin `@RestController`s that deserialize spec types and delegate |
| `ErrorHandlers` semantics (client) | **Free** | stock clients already map 409→`CommitFailedException`, 500/502/504→`CommitStateUnknownException` (`core/.../rest/ErrorHandlers.java:80-99`) |
| `OpenHouseInternalTableOperations` | **Keep, then shrink** | commit/refresh/HTS plumbing retained; legacy-only branches (§5.3 rows 1, 3, 4, 5, 7) deleted in Phase 4 |

Version note: server modules build against iceberg-core **1.5.2**, which contains all of the above (verified against an apache/iceberg 1.5.2.x checkout). The fork (`com.linkedin.iceberg`) must be audited for patches touching `rest/`, `UpdateRequirements`, `TableMetadata.Builder` (fork patch risk → §5.6 R6).

### 5.2 Where OpenHouse policies hook in

All hooks live in the new commit service **before** entering the retry loop (cheap, fail-fast), plus a per-attempt update validator inside the loop (authoritative, sees fresh state). The four-column shape below is what an implementer auditing M3 needs: every gate that exists today, where it moves to, and the code that enforces it now.

| # | Policy | Where it hooks in the new path | Enforcing code today | Target behaviour |
|---|---|---|---|---|
| 1 | AuthZ | Route-level interceptor plus operation-level classification of the update list | `AuthorizationInterceptor`; `TablesServiceImpl.putTable` (`services/tables/src/main/java/com/linkedin/openhouse/tables/services/TablesServiceImpl.java:99-165`) | Snapshot/schema/property updates require the same table-write privilege the legacy `putSnapshots`/`updateTable` paths check. ACL grant/revoke stays on `updateAclPolicies` — it is not expressible as a `TableUpdate` (§2, out of scope 3) |
| 2 | Locked tables | Pre-loop check on the loaded table, re-checked per attempt against the fresh base | `TablesServiceImpl.java:125-129`, `IcebergSnapshotsServiceImpl.java:69-76` | Reject any commit when `policies.lockState.locked`. Lock state lives in the `policies` property inside metadata, so a concurrent lock must not be raced past |
| 3 | Preserved `openhouse.*` properties and `policies` | `RestUpdateValidator`, pre-loop | `PreservedKeyChecker.isKeyPreservedForTable` (`services/tables/src/main/java/com/linkedin/openhouse/tables/repository/PreservedKeyChecker.java:9-34`), enforced at `InternalRepositoryUtils.java:170-172` and `OpenHouseInternalRepositoryImpl.java:681-694`; `POLICIES_KEY` staged at `OpenHouseInternalRepositoryImpl.java:561-563` | Reject `SetProperties`/`RemoveProperties` touching preserved keys. Policy mutation continues through OpenHouse endpoints and DDL, which internally reuse the same commit loop with a server-constructed `SetProperties(policies=...)` — one commit path for everything |
| 4 | Server-stamped bookkeeping | Inside `doCommit`, unchanged | `OpenHouseInternalTableOperations.java:274-288` | `openhouse.tableVersion/tableLocation/lastModifiedTime/creationTime` stay catalog-owned, never client updates; `AssignUUID` from clients is rejected except during create |
| 5 | Replication | New replication commit path (Phase 3) | Bootstrap rides a `CREATE` plus a post-commit in-place rewrite (`OpenHouseInternalTableOperations.java:281-284,420-422` → `utils/MetadataUpdateUtils.java:37-59`, flagged by `CatalogConstants.OPENHOUSE_IS_TABLE_REPLICATED_KEY`) | Replication becomes a sequence of ordinary typed commits (add-snapshot / set-ref / add-schema with `lastUpdatedMillis` supplied through a privileged server-side path), or spec `registerTable` for initial bootstrap: the final metadata is computed **before** the single write, so the non-atomic rewrite ([Appendix B](appendix-b-code-review.md) finding 2) is deleted |
| 6 | Feature toggle | Route gate, per table | `services/tables/src/main/java/com/linkedin/openhouse/tables/toggle/TableFeatureToggle.java:32`, `FeatureToggleAspect.java` | The existing per-table toggle framework gates the new endpoint (featureId e.g. `enable-rest-commit`), so the migration flag comes for free (§5.5) |

### 5.3 Gap analysis: every divergence → target → concrete code

| # | Current divergence (file:line) | What it becomes | Change surface |
|---|---|---|---|
| 1 | **Client-authoritative metadata content**: full snapshot list + refs smuggled as transaction properties `SNAPSHOTS_JSON_KEY`/`SNAPSHOTS_REFS_KEY` — staged at `OpenHouseInternalRepositoryImpl.java:696-708` (and CTAS `:565-573`), parsed+merged at `OpenHouseInternalTableOperations.java:298-299,314-354` via `SnapshotsUtil.java:33-47,77-90` | Typed `AddSnapshot`/`SetSnapshotRef`/`RemoveSnapshots`/`RemoveSnapshotRef` updates applied server-side to a fresh base | **Add**: REST commit controller/service (§5.7). **Delete (Ph4)**: `doUpdateSnapshotsIfNeeded`, the merge block `:314-354`, `SnapshotsUtil` parse paths, `SNAPSHOTS_*` constants (`CatalogConstants.java:5-6`) |
| 2 | **Subtractive snapshot merge** — absence in payload ⇒ deletion (`:337-344`); expiration and append share one ambiguous channel | Deletion only via explicit `remove-snapshots` update; append cannot delete by construction | Same as row 1; expiration jobs (`apps/`) emit `RemoveSnapshots` updates via stock client |
| 3 | **Version token = metadata path**: `baseTableVersion` → `TableDto.tableVersion` (`TablesMapper.java:71-72,94-95`), advisory `versionCheck` (`OpenHouseInternalRepositoryImpl.java:451-475`), `COMMIT_KEY` stamp (`:196`), catalog CAS `abortIfWriterBaseDivergedFromCatalog` (`OpenHouseInternalTableOperations.java:269,604-635`) | Typed requirements (`AssertTableUUID` always; `AssertRefSnapshotID` per touched ref; schema/spec/order asserts per update — `UpdateRequirements.java:50-174`) validated against the fresh base inside the commit loop | **Delete (Ph4)**: `versionCheck`, `COMMIT_KEY` plumbing, `abortIfWriterBaseDivergedFromCatalog` (superseded by requirements at the same point in the flow). HTS keeps its path-equality precheck + `@Version` CAS unchanged (`UserTableVersionMapper.java:20-47`) |
| 4 | **Server transaction retries disabled**: `commit.num-retries=0` override (`OpenHouseInternalRepositoryImpl.java:201-207,750-781`) + `failIfRetryUpdate` poison cache (`OpenHouseInternalTableOperations.java:93-94,642-664`) | Server retry loop **re-enabled** for store-level races only (`Tasks...onlyRetryOn(CommitFailedException)`; requirement failures excluded via the `ValidationFailureException` wrap per `CatalogHandlers.java:348-388`) | **Add**: retry loop in new commit service. **Delete (Ph4)**: `overrideProperty` machinery, `failIfRetryUpdate` + Guava `CACHE` |
| 5 | **Dedup cache**: per-JVM, pre-commit, keyed on base path ([Appendix B](appendix-b-code-review.md) finding 5) | Obsolete: retries re-derive metadata. Idempotency for *ambiguous* client retries is optionally improved by server-side `AddSnapshot` snapshot-id dedupe, which is the most the spec vocabulary allows ([Appendix B](appendix-b-code-review.md) finding 15 is the general gap; S4) | Delete with row 4; optional `AddSnapshot`-dedupe check in commit service (Ph2) |
| 6 | **Entire Iceberg-transaction smuggling layer**: `OpenHouseInternalRepositoryImpl.save` update branch (`:178-223`) staging evolved schema (`:643-674`), sort order (`:269-280`), user props, policies, staged flags as properties; `processSchemas` unwind in doCommit (`:686-718`, which swallows per-schema parse failures — [Appendix B](appendix-b-code-review.md) finding 4) | Direct `TableMetadata.buildFrom(base)` + `applyTo`; schema evolution arrives as `AddSchema`/`SetCurrentSchema`, sort order as `AddSortOrder`/`SetDefaultSortOrder` | Legacy repository stays for legacy endpoints during the window; **Delete (Ph4)** with the endpoints. New path bypasses `OpenHouseInternalRepositoryImpl` entirely |
| 7 | **Replicated-create in-place rewrite** (`OpenHouseInternalTableOperations.java:281-284,420-422`; `MetadataUpdateUtils.java:37-59` `fs.create(path, true)` — [Appendix B](appendix-b-code-review.md) finding 2) | Metadata fully computed pre-write (timestamps via privileged updates or registerTable); single immutable write | **Add**: replication commit path (Ph3). **Delete (Ph4)**: `MetadataUpdateUtils` in-place rewrite, `isReplicatedTableCreate` branches |
| 8 | **Error mapping asymmetries**: snapshots path misses `CommitStateUnknown`→503 catch (`IcebergSnapshotsServiceImpl.java:91-109` vs `TablesServiceImpl.java:171-193`); OH uses 503 for unknown while the REST spec uses 500/502/504 ≙ unknown, 503 ≙ retryable-not-committed (yaml `:706-758`, `ErrorHandlers.java:90-94,220-221`) | One exception mapper for REST routes: requirement-fail/CAS-lose→409; `CommitStateUnknownException`→**500** with `"type":"CommitStateUnknownException"` body; known-not-committed overload→503 (S2) | **Add**: `IcebergRestExceptionHandler` beside `OpenHouseExceptionHandler.java` (spec `ErrorResponse` body format, not OH envelope) |
| 9 | **Custom client protocol**: `OpenHouseTableOperations.doCommit` routing (`integrations/java/iceberg-1.2/.../OpenHouseTableOperations.java:142-169,364-391`), custom error mapping (`:418-464`), `doRefresh` via `GET tables/{t}` + client-side metadata.json read (`:97-128`) | Stock `RESTTableOperations`/`RESTSessionCatalog` (`core/.../rest/RESTTableOperations.java:105-158`); refresh returns metadata in-band; commit response returns `metadata-location` + metadata (yaml `:3294-3303`) — client never re-reads the file | **Deprecate (Ph3-4)**: `openhouse-java-runtime` custom ops for table IO; keep a thin extensions client for policies/ACL DDL (§5.4) |
| 10 | **Stage-create / WAP** via properties `IS_STAGE_CREATE_KEY` (`OpenHouseInternalTableOperations.java:300-303,412-419`; illegal-state at `TablesServiceImpl.putTable:120-123`) | Spec staged-create: `stage-create` on the create route plus a later commit with an `assert-create` requirement (`CatalogHandlers.java:315-343`); WAP snapshots are `add-snapshot` without `set-snapshot-ref`, which is native in Iceberg ([Appendix C](appendix-c-iceberg-commit-protocol.md) §3.3) | Handled by adopting `CatalogHandlers` create/commit shapes (Ph2/Ph3) |
| 11 | **Rename** as a doCommit branch hitting non-CAS JPQL (`OpenHouseInternalTableOperations.java:386-400`; `UserTableHtsJdbcRepository.java:115-125` — [Appendix B](appendix-b-code-review.md) finding 3) | Spec `POST /v1/{prefix}/tables/rename` — separate from commit; opportunity to route through a CAS-checked HTS update | **Add (Ph3)**: rename route. The HTS rename CAS fix is an independent pre-existing bug and ships separately (§2, out of scope 5) — this is M2's gap in the recommendation |

### 5.4 Client story

Any engine bundling Iceberg ≥1.2 talks to the finished service with stock configuration, which is the point of choosing this contract over a bespoke one:

```
spark.sql.catalog.oh = org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.oh.catalog-impl = org.apache.iceberg.rest.RESTCatalog
spark.sql.catalog.oh.uri = https://<tables-service>/
spark.sql.catalog.oh.token = <jwt>
```

- The commit-relevant protocol (`UpdateTableRequest`, requirements/updates) is stable across Iceberg 1.2→1.5, so both existing client stacks (`integrations/java/iceberg-1.2`, `integrations/java/iceberg-1.5`; Spark 3.1 and 3.5 under `integrations/spark/`) can use stock `RESTCatalog`. Validate the 1.2-client↔1.5-server matrix explicitly (older clients omit newer update types — fine; the server must not *require* them).
- Clients still need storage credentials for the **data plane** (data/manifest writes) exactly as today; only metadata authorship moves. Credential vending (`LoadTableResponse.config`) is a later, optional win.
- **`OpenHouseTableOperations`/`OpenHouseCatalog` (custom client)**: retired for table IO at the end of the window. What it provides beyond Iceberg — policies DDL via Spark SQL extensions, ACL calls, OpenHouse-specific catalog ops (`GetTableResponseBody` surface) — survives as a thin "OpenHouse extensions" client hitting the OpenHouse-specific endpoints, while all read and commit traffic goes through stock `RESTCatalog`. The Spark extension syntax keeps working; only its transport changes.
- **Dual-protocol window**: both endpoint families run in the same service against the same HTS and storage. This is safe by construction: every commit, legacy or REST, funnels through `OpenHouseInternalTableOperations.doCommit` → `houseTableRepository.save` → HTS `@Version` CAS, so a legacy writer racing a REST writer is serialized by the same arbiter as two legacy writers today, and each loser re-derives from the winner's metadata (REST: server or client re-apply; legacy: engine retry loop). A cross-protocol concurrent-commit test is mandatory (§5.5 P2).
- Legacy clients never break mid-window: they keep speaking the old protocol until their runtime is swapped, and tables have no protocol affinity — a table can be written by both in the same hour.

### 5.5 Phased migration plan

Each phase below carries its scope, testing, and rollback; §5.6 prices them.

**Phase 0 — Prototype (delivered).** The core loop is built, tested, and committed on this branch; §5.7 describes what it does and what it does not. Its exit criterion is met apart from the MySQL end-to-end noted there.

**Phase 1 — REST read plane, config, and auth.** `GET /v1/config`, `loadTable`, `listTables`, namespace mapping, spec `ErrorResponse` mapping, token auth on the new routes.
*Testing*: stock `RESTCatalog` (both 1.2 and 1.5) reads and plans tables created and written via the legacy protocol; golden-file comparison of `LoadTableResponse.metadata` against the metadata.json HTS points to; authz parity tests over the same principal matrix as the legacy GET.
*Rollback*: new routes are additive; disabling the route is the rollback.

**Phase 2 — Commit endpoint, production-hardened, per-table feature toggle.** Commit service and retry loop, requirement validation, `RestUpdateValidator` (preserved props, policies, locked tables, authz classification), spec exception mapper, metrics (reuse `metricsReporter` tags), optional `AddSnapshot` idempotency dedupe, and the `enable-rest-commit` toggle via `TableFeatureToggle` (`toggle/TableFeatureToggle.java:32`) so only enrolled tables accept REST commits.
*Testing*: the prototype matrix (§5.7) extended with policy-gate cases (locked table, preserved-key set, unauthorized principal); **cross-protocol concurrency** — legacy writer against REST writer from the same base, asserting exactly one winner per round, zero snapshot loss, and a retriable 409 for the loser in both directions; **shadow validation**, which computes the equivalent `(requirements, updates)` server-side from (base, payload) for legacy `putSnapshots` commits on canary tables and logs any divergence between merge-result and rebuild-result metadata, validating the rebuild semantics against months of real traffic without dual-writing files; fault injection on HTS 5xx and timeout during save, asserting a typed 500 and that the orphan metadata.json is never referenced.
*Rollback*: toggle off per table. No data migration exists to undo — both protocols produce identical on-disk artifacts and HTS rows.

**Phase 3 — Client migration and remaining surface.** Create, drop, rename, and stage-create on REST routes; replication path redesign (§5.3 row 7); `openhouse-spark-runtime` switched to stock `RESTCatalog` plus the extensions client, released as a new runtime version; canary tables → canary databases → default-on for new tables → org-wide. Maintenance jobs (`apps/`: snapshot expiration, orphan cleanup) move to the stock client with explicit `remove-snapshots`.
*Testing*: full Spark integration suites (3.1 and 3.5) on the new runtime against a dual-protocol service; soak on canary databases with production-shaped concurrency; replication end to end (create-from-source, incremental snapshot sync, and `lastUpdatedMillis` fidelity — the timestamps currently patched in place must be byte-equivalent).
*Rollback*: runtime pin rollback per workload; tables stay readable and writable by the legacy runtime throughout.

**Phase 4 — Decommission the legacy protocol.** Announce deprecation; legacy write endpoints return 410 once traffic hits zero (metrics-gated); delete the snapshot-smuggling staging, merge block, CAS trio, dedup cache, retry-disable machinery, and `MetadataUpdateUtils` rewrite (§5.3 rows 1-7), along with `IcebergSnapshotsController`/`Service` and the client `SnapshotApi`.
*Testing*: full regression on the slimmed `doCommit`; the #612 regression test (`OpenHouseInternalTableOperationsTest.java:258`) is *ported* to the REST path before its production counterpart is deleted, so no deleted defense goes unreplaced.

### 5.6 Effort estimate and risk register

**13-22 engineering-weeks remain** of a program originally scoped at 15-25. Phase 0 is delivered (§5.7); the plan budgeted it at 2-3 weeks, and that slice is no longer outstanding. Phase 2 is the program's centre of gravity — a third of the remaining effort and the home of R2, R4, and R7 — so it is the phase to staff first and the one whose slip moves the whole date.

Assumptions: 1-2 engineers already fluent in this codebase; the fork audit turns up no blocking iceberg-core patches; HTS untouched. The figures are engineering effort and exclude org-wide client-rollout calendar time (the Phase 3 soak is weeks of wall clock at low engineering cost).

| Phase | Scope | Eng-weeks |
|---|---|---|
| P0 | Prototype + test matrix | **delivered** (scoped 2-3) |
| P1 | Read plane, config, auth, error mapping | **2-4** |
| P2 | Commit path hardened: policy gates, toggle, shadow validation, cross-protocol tests, metrics, idempotency dedupe | **5-8** |
| P3 | Create/drop/rename/stage, replication redesign, client runtime swap + extensions client, canary rollout | **4-7** |
| P4 | Decommission + deletion + regression port | **2-3** |
| **Remaining** | | **13-22 eng-weeks** |

Risk register (top risks × mitigations):

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Replication path** is the most bespoke consumer (in-place timestamp rewrite, intermediate schemas, `LAST_UPDATED_MS` fidelity); the redesign uncovers hidden invariants | Keep replicated tables on the legacy path until last; dedicated mini-design in P3; byte-diff the metadata produced by old and new bootstrap on real replicated tables |
| R2 | **Mixed-protocol concurrency window** exposes an interaction the single-protocol tests miss (e.g. a legacy subtractive merge racing a REST `remove-snapshots`) | HTS single-arbiter property plus an explicit cross-protocol concurrency suite in P2; shadow-validation metric on canaries before widening; per-table toggle limits blast radius |
| R3 | **Client version skew**: Spark 3.1 / iceberg-1.2 stock REST client against a 1.5-built server (serialization drift, missing update types, behavioral gaps in 1.2's `RESTCatalog`) | Explicit 1.2↔server compatibility matrix in P1/P2; if gaps are found, keep 3.1 workloads on the legacy protocol and gate its decommission on Spark-3.1 retirement (the protocol window supports this indefinitely) |
| R4 | **Semantic regressions in the server rebuild** versus today's merge (snapshot-log truncation, sequence numbers, ref edge cases such as WAP and cherry-pick) | Shadow validation on real traffic (P2) is the primary catch-net; port every existing `OpenHouseInternalTableOperationsTest` scenario to the REST path |
| R5 | **CommitStateUnknown and idempotency** (S4): a client retry after an ambiguous 5xx double-applies or spuriously 409s | Keep `checkCommitStatus` probing; `AddSnapshot` snapshot-id dedupe on the server (return success if the exact snapshot already landed at the asserted ref); correct 500-vs-503 spec mapping so clients never blind-retry ambiguity |
| R6 | **iceberg fork divergence**: `com.linkedin.iceberg` patches (for example the transaction-retryer behavior the retry-disable hack relies on) alter `rest/` or builder classes | Day-1 audit of the fork patch list against `org.apache.iceberg.rest`, `UpdateRequirement*`, `TableMetadata`; pin the prototype to fork artifacts, not upstream |
| R7 | Policy or authz gaps in the generic update vocabulary (a client smuggles `policies` or `openhouse.*` via set-properties) | Deny-by-default `RestUpdateValidator` driven by `PreservedKeyChecker`; fuzz test every update type × preserved key ⇒ 400 |

### 5.7 The prototype, as delivered

The prototype proves the core loop against real HTS and storage fixtures using production classes rather than mocks of them: it accepts `(requirements, updates)`, validates them against current HTS-backed state, rebuilds metadata server-side, writes metadata.json, and CASes the HTS row.

It ships as five classes under `services/tables/src/main/java/com/linkedin/openhouse/tables/resthandler/`:

| Class | What it does |
|---|---|
| `IcebergRestCommitController` | `@RestController` serving `POST /v1/rest/namespaces/{namespace}/tables/{tableId}/commit`. Deserializes the body to `org.apache.iceberg.rest.requests.UpdateTableRequest`, rejects multi-level namespaces with 400, and returns `LoadTableResponse` JSON (metadata plus metadata-location) |
| `IcebergRestCommitService` | The adapted `CatalogHandlers.commit` loop of §4.2: `Tasks.foreach(ops).retry(...).onlyRetryOn(CommitFailedException)`, per-attempt refresh, requirement validation wrapped in a local `ValidationFailureException` so requirement failures never retry, `TableMetadata.buildFrom(base)` + `applyTo`, then `taskOps.commit(base, updated)`. A no-change commit returns without touching the table |
| `RestUpdateValidator` | The M3 gates that exist so far: request-shape checks (preserved `openhouse.*` and `policies` keys, `assign-uuid`) run once before the loop; the locked-table check runs inside the loop against each refreshed base, so a concurrently applied lock cannot be raced past |
| `IcebergRestExceptionHandler` | Spec `ErrorResponse` bodies scoped to this controller only (`assignableTypes`), so legacy endpoints keep the OpenHouse envelope: 409 `CommitFailedException`, **500** `CommitStateUnknownException`, 404 unknown table, 400 for invalid updates or payloads |
| `IcebergRestSerde` | A dedicated `ObjectMapper` with `RESTSerializers` registered, kept off the service-wide mapper |

`OpenHouseInternalTableOperations`, HTS, and the client are unchanged, which is the load-bearing claim of §4.2: with no smuggled properties present, `doCommit` already skips the merge, the catalog CAS, and the dedup poison, and performs stamp → write → HTS save.

The test matrix is 35 tests across three suites, all green against H2-backed HTS and local-FS storage:

| Suite | Count | What it covers |
|---|---|---|
| `IcebergRestCommitControllerTest` (`services/tables/.../e2e/h2/`) | 15 | Snapshot-append happy path; same-ref conflict; independent commit re-applied on an advanced base; store-level race retried server-side and invisible to the client; requirement failure as 409 with no write and no retry; ambiguous HTS failure as `CommitStateUnknownException` leaving only an orphan; **a stale writer failing to expire a racing snapshot** (the #612 interleaving); removal requiring an explicit `remove-snapshots`; preserved-key, locked-table, `assign-uuid`, unknown-table, multi-level-namespace, malformed-body, and no-op cases |
| `RestNativeCommitOperationsTest` (`iceberg/openhouse/internalcatalog/.../`) | 6 | The ops-level invariants: typed re-apply on a fresh base cannot drop a racing snapshot; `assert-ref-snapshot-id` validates against the fresh base; requirement derivation per update shape; explicit removal removes only the named snapshot; store-failure semantics; and no legacy transport properties on the persisted metadata |
| `RestUpdateValidatorTest` (`services/tables/.../resthandler/`) | 14 | Every preserved-key, policies, `assign-uuid`, and lock-state branch, including malformed `policies` JSON |

What is not built, and must not be mistaken for built:

1. **Operation-level authorization.** The cluster token interceptor covers all routes (`TablesMvcConfigurer.java:44`), but the OPA privilege classification the legacy paths run in `TablesServiceImpl` is not wired into this service. The route belongs in test and dev environments only until Phase 2 adds it.
2. **The per-table feature toggle**, and with it the enrollment story of §5.5 P2.
3. **The spec-exact route and prefix**, plus the entire read plane (`GET /v1/config`, `loadTable`, `listTables`) — Phase 1.
4. **MySQL HTS certification.** The matrix is green on H2 with a fault hook simulating the `@Version` CAS loss; the #612 postmortem recorded H2-versus-MySQL behavioral divergence ([Appendix A](appendix-a-snapshot-drop-bug.md) §4), so concurrency semantics are not certified until the same matrix runs against a dev MySQL HTS.
5. Everything in §5.3 marked Ph2 or later: replication, stage-create, rename, create and drop routes, metrics, and shadow validation.

---

## Appendix 1: the rejected options, developed

**(b) Harden the existing protocol in place.** Ship the nine blocking findings of [Appendix B](appendix-b-code-review.md), extend `abortIfWriterBaseDivergedFromCatalog` to the flavors it deliberately skips (replace, stage-create), and make the HTS rename conditional. This is the cheapest option and it is strictly necessary anyway, because the legacy path stays live for the whole window of any other option. It fails M1 for a reason no amount of guarding fixes: while the payload means "the snapshot set is exactly X", every guard is a race against the window between the writer's read and the server's commit, and each new commit flavor must rediscover which guards apply — which is exactly how rename ended up with none.

**(c) Server-authored metadata over the existing wire contract.** Keep `PUT .../iceberg/v2/snapshots` and the client untouched. Inside `doCommit`, load the writer's *declared* base (an immutable file at a known path), diff the payload against it to obtain the writer's intended adds and removes, and apply those to the fresh base instead of merging the payload subtractively. Removals then express the writer's own intent rather than an accident of what raced it, which satisfies M1 without a single client change; the machinery is the same derivation Phase 2's shadow validation computes to check the REST path. Its costs are that OpenHouse keeps a proprietary contract to specify and defend, keeps the bespoke client and its five findings, keeps whole-table conflict granularity (S3), and gains nothing from the ecosystem's clients or tooling. It is the right decision if the client-retirement commitment in §3 is not made, and a sound first step even if it is.

**(d) An off-the-shelf REST catalog in front of HTS.** `RESTCatalogAdapter` (`iceberg-ref:core/.../rest/RESTCatalogAdapter.java`) serves the REST spec over any `Catalog` implementation, so pointing it at `OpenHouseInternalCatalog` looks like adoption for free. It is not: the adapter is a servlet-style dispatcher outside the Spring interceptor chain where OpenHouse's authorization, lock, preserved-key, and toggle gates live, so M3 requires re-plumbing all of them through it — at which point the remaining code is the thin `@RestController` of option (a) with a less direct exception surface. A separate catalog product (Nessie, Polaris, a JDBC catalog) fails M4 outright by bringing its own commit point and store, which would make HTS's row CAS no longer the arbiter and would require a data migration.

**(e) Do nothing.** The #612 CAS holds for snapshot-bearing commits from first-party clients, so this is survivable rather than absurd. It fails M1 and M2, keeps every finding in [Appendix B](appendix-b-code-review.md) open, and leaves the protocol's correctness resting on a property no compiler checks: that the payload's freshness matches the base the server commits on.

## Appendix 2: primary code anchors

- OpenHouse server commit core: `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java` (`doCommit` :250-489; merge :314-354; CAS :604-635; dedup :642-664; replicated rewrite :281-284, :420-422)
- Legacy staging/smuggling: `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java` (:111-226 save; :196 COMMIT_KEY; :201-207 retry-disable; :451-475 versionCheck; :696-708 snapshot staging)
- HTS CAS (unchanged): `services/housetables/src/main/java/com/linkedin/openhouse/housetables/services/UserTablesServiceImpl.java` :98-127; `model/UserTableRow.java` :28; `dto/mapper/UserTableVersionMapper.java` :20-47
- Policy hooks: `TablesServiceImpl.java` :125-129/:474-477 (lock), `authorization/AuthorizationInterceptor.java`, `repository/PreservedKeyChecker.java` :9-34, `toggle/TableFeatureToggle.java` :32
- Prototype: `services/tables/src/main/java/com/linkedin/openhouse/tables/resthandler/` (five classes, §5.7); tests at `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/IcebergRestCommitControllerTest.java`, `services/tables/src/test/java/com/linkedin/openhouse/tables/resthandler/RestUpdateValidatorTest.java`, `iceberg/openhouse/internalcatalog/src/test/java/com/linkedin/openhouse/internal/catalog/RestNativeCommitOperationsTest.java`
- Iceberg reference: `iceberg-ref:core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java` :283-388; `UpdateRequirements.java` :50-174; `UpdateRequirement.java` :38-232; `rest/RESTTableOperations.java` :105-158; `open-api/rest-catalog-open-api.yaml` :592-772, :2772-2788, :3294-3303
- Legacy client to retire: `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseTableOperations.java` :97-128, :142-169, :364-391, :418-464
