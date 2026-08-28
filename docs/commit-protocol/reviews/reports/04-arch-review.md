# Architecture review — OpenHouse table commit path

Reviewer: `arch-review` · Target: commit-protocol code of `/home/user/openhouse` at HEAD `2a9dac8` (module/path scope per `01-code-manifest.md`) · Date: 2026-08-26

## TL;DR

The commit protocol has one sound atomic core — the HTS single-row update guarded by JPA `@Version` — surrounded by an architecture that repeatedly routes commit decisions around that core. The #612 snapshot-loss incident was not a one-off bug: it was the property-bag commit protocol (commit payload smuggled as Iceberg table properties through `BaseTransaction`) doing exactly what that design permits, and the same structural fault still has two open siblings at HEAD: the **rename path bypasses every CAS and can silently clobber a concurrent commit's pointer** (the same lost-update class as incident-12185), and the **replicated-create path rewrites a committed metadata.json in place**, with an error handler that can only ever run *after* the commit point yet reports `CommitFailedException` — the one signal a client is allowed to trust as "safe to clean up".

18 findings: **6 blockers, 8 suggestions, 4 nits.** The blockers cluster on two structural causes: (1) the commit payload and commit decisions have no owned, typed seam — they ride as strings through machinery (Iceberg transactions, Spring Data interfaces, MapStruct mappers) that was not designed to carry them; (2) the failure channel does not distinguish pre-commit-point from post-commit-point failure, so several paths report known-failure for state that is durably committed or unknown.

## Findings index

| # | Severity | Confidence | Location (primary) | One-line claim |
|---|----------|-----------|--------------------|----------------|
| 1 | blocker | confirmed | `OpenHouseInternalTableOperations.java:424-437` | `catch (IOException)` in `doCommit` is reachable only after the commit point, yet reports `CommitFailedException`, attempts an unimplemented row delete, and ignores its own `checkCommitStatus` result |
| 2 | blocker | confirmed | `OpenHouseInternalTableOperations.java:420-422`, `MetadataUpdateUtils.java:45` | Replicated-create rewrites the already-committed metadata.json in place (non-atomic overwrite) and silently invalidates the metadata cache it just seeded |
| 3 | blocker | confirmed | `UserTableHtsJdbcRepository.java:115-125` | Rename bypasses every CAS: an unconditional JPQL `UPDATE` of `metadataLocation` with no `@Version` check can erase a concurrent commit — the incident-12185 lost-update class through a channel fix #612 does not cover |
| 4 | blocker | confirmed | `OpenHouseInternalRepositoryImpl.java:183-216`, `OpenHouseInternalTableOperations.java:267-312` | The commit payload (snapshots, refs, CAS token, flags, schemas) travels as string table-properties through Iceberg's transaction machinery — the structural cause of #612, still fork-coupled and order-dependent at HEAD |
| 5 | blocker | probable | `OpenHouseTableOperations.java:142-169` (client) | Client `doCommit` routes on a non-total diff classification; a commit whose changes fall outside schema/props/spec/sortOrder/snapshots/refs is acknowledged as success without any REST call |
| 6 | blocker | confirmed | `OpenHouseInternalTableOperations.java:704-715` | `processSchemas` swallows per-schema parse failures inside the commit path and commits anyway with wrong schema lineage |
| 7 | suggestion | confirmed | `UserTableVersionMapper.java:34` (+3 other CAS sites) | The base-version CAS has four owners with three different comparison semantics; the authoritative one (HTS) compares raw strings while the others normalize schemes |
| 8 | suggestion | confirmed | `OpenHouseTableOperations.java:147-151`, `OpenHouseInternalTableOperations.java:620-622` | "Replace" — the one commit flavor exempt from the catalog CAS — is inferred from a state diff at the client edge instead of declared intent |
| 9 | suggestion | confirmed | `IcebergSnapshotsServiceImpl.java:89-109` | The data-commit endpoint's exception surface is not total: `CommitStateUnknownException` falls to the generic 500 handler instead of the typed 503, and 503 is absent from the endpoint's declared responses |
| 10 | suggestion | confirmed | `OpenHouseInternalTableOperations.java:642-664` | `failIfRetryUpdate` burns the commit key into a per-JVM cache *before* the commit succeeds, converting transient failures into hard "retry from application" 409s |
| 11 | suggestion | confirmed | `OpenHouseInternalTableOperations.java:670-675` | Retriable-conflict vs bad-request classification depends on matching Iceberg's exception message strings |
| 12 | suggestion | confirmed | `HouseTableRepository.java:17`, `UserTablesServiceImpl.java:112` | HTS boundary vocabulary is wrong in both directions: a Spring Data `PagingAndSortingRepository` facade advertises 10+ unsupported methods (the finding-1 landmine), and the HTS service catches Iceberg's `CommitFailedException` |
| 13 | suggestion | probable | `OpenHouseTableOperations.java:108` (client) | Client refresh maps HTTP 400 to "table absent", converting an error into a wrong answer that propagates as truth |
| 14 | suggestion | confirmed | `SnapshotsUtil.java:45-47`, `OpenHouseInternalTableOperations.java:337-344` | The snapshot payload is trusted wholesale — the parse ignores its `FileIO`, and the subtractive merge makes any client that passes the base CAS authoritative over the entire snapshot set |
| 15 | nit | confirmed | `OpenHouseExceptionHandler.java:404-415` | Every error response, including the 500 fallback, embeds abbreviated stack traces and `exception.toString()` in the response body |
| 16 | nit | confirmed | `UserTableVersionMapper.java:30,44`, `MetadataUpdateUtils.java:55` | Fabricated (`new RuntimeException()`) and dropped (`new IOException(errMsg)` without cause) exception causes destroy the failure descent record |
| 17 | nit | confirmed | `OpenHouseTableOperations.java:156-167` (client) | `InterruptedException` detected via `getCause()` without restoring the interrupt flag |
| 18 | nit | confirmed | `OpenHouseInternalTableOperations.java:126-130` | A concurrently-dropped table surfaces as `IllegalStateException` → HTTP 500, though concurrent deletion is an expected outcome in this system |

All paths below are relative to `/home/user/openhouse`. Client files live under `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/`; server catalog files under `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/`; tables service under `services/tables/src/main/java/com/linkedin/openhouse/tables/`; HTS under `services/housetables/src/main/java/com/linkedin/openhouse/housetables/`.

---

## Orientation: module roles and the ground truth for judgments

Module graph (from `settings.gradle` and module `build.gradle` files): `services:tables` → {`iceberg:openhouse:internalcatalog`, `services:common`, `client:hts`}; `iceberg:openhouse:internalcatalog` → {`services:common`, `client:hts`, `cluster:*`}; `services:housetables` → {`services:common`, `iceberg:openhouse:htscatalog`}. The Java/Spark client (`integrations/java/iceberg-1.2`) is a separate runtime speaking REST.

Roles: the two `TableOperations` classes are **edge translation layers** between Iceberg's commit contract and OpenHouse's REST/HTS contracts — broad catches and protocol mapping are legitimate there. `OpenHouseInternalRepositoryImpl` is **interior orchestration** inside `services:tables`. HTS is the **bottom store**; it should know nothing above itself. The commit point is the single HTS row update (`UserTablesServiceImpl.java:111` → Hibernate `UPDATE ... WHERE version = v`), with `UserTableRow.version` (`UserTableRow.java:28`) the only atomic arbiter. Everything else — the metadata.json write, the caches, the advisory checks — is write-ahead or best-effort, which the design correctly exploits via unique file names (`OpenHouseInternalTableOperations.java:191-201`).

I verified the ten pre-flagged smells in the protocol brief independently; all ten reproduce at HEAD. They are absorbed into the findings below (mapping in the appendix) rather than reported as separate items.

---

## Blockers

### Finding 1 — The `doCommit` IOException handler runs only after the commit point, yet reports clean failure

- **location**: `iceberg/openhouse/internalcatalog/.../OpenHouseInternalTableOperations.java:424-437`; reachability from `:421` and `:804-821`; landmine at `iceberg/openhouse/internalcatalog/.../repository/HouseTableRepositoryImpl.java:319-322`
- **principle**: Dependency failure is its own category (state-known vs state-unknown); Enumerable outcomes; Fail fast when decidable, halt when not
- **claim**: The `catch (IOException)` block is dead code for its stated purpose (metadata-write cleanup) and live code only for post-commit failures, where each of its three actions is wrong.
- **evidence**: `IOException` is checked, so the compiler proves its sources. Inside the try block the only declared thrower is `updateMetadataFieldForTable` (`:804-805`, called at `:421`) — which runs *after* `houseTableRepository.save` (`:404`) has durably advanced the pointer. `TableMetadataParser.write` throws unchecked `RuntimeIOException`; the HTS repository methods throw the `HouseTable*` hierarchy. Given that, the handler:
  1. computes `commitStatus = checkCommitStatus(...)` (`:425`) and then ignores it — even a `SUCCESS` result proceeds to cleanup-and-throw;
  2. calls `houseTableRepository.delete(houseTable)` (`:428`) — the production implementation throws `UnsupportedOperationException` (`HouseTableRepositoryImpl.java:319-322`), which is not among the caught types at `:429-431`, so it escapes, masks the original failure, and surfaces as a generic 500. Had `delete` been implemented, deleting the row after a *post-commit* IOException would remove the live table pointer — for this path, of a table that just committed successfully;
  3. throws `CommitFailedException(ioe)` (`:437`) — the one exception class the Iceberg protocol defines as "the commit definitely did not happen, cleanup of uncommitted files is safe". Reporting it after the commit point is the exact misreport class that turns an engine-side cleanup into deletion of committed files.
- **failure scenario**: A replicated-table create commits; the HTS row now points at the new metadata.json; `fs.create` inside `updateMetadataField` throws `IOException` (transient HDFS error). Today: `UnsupportedOperationException` escapes → 500 → the OH client maps 500 to `CommitStateUnknownException` — survivable only because the landmine detonates *before* the misreport. If anyone implements `delete` (the interface advertises it; see finding 12), the same event deletes the committed table pointer and then reports `CommitFailedException` for a commit that succeeded.
- **options**:
  - *as-is*: correctness rests on an `UnsupportedOperationException` accidentally pre-empting a data-destroying code path.
  - *enabling refactor*: move the commit point out of the try's failure-conflation zone — relocate the replicated-create rewrite (see finding 2) and delete the `catch (IOException)` block outright; the compiler then enforces that no checked IOException source re-enters silently. Touches only this class.
  - *restructure*: split `doCommit` at the commit point into "prepare (fail = `CommitFailedException`)" and "publish + after-effects (fail = `CommitStateUnknownException` or logged advisory)", so the pre/post distinction is structural rather than per-catch-block. Touches this class and its tests.
- **severity**: blocker · **confidence**: confirmed · **reviewer**: arch-review

### Finding 2 — In-place rewrite of a committed metadata.json (replicated-create)

- **location**: `OpenHouseInternalTableOperations.java:420-422`, `:804-821`; `iceberg/openhouse/internalcatalog/.../utils/MetadataUpdateUtils.java:37-57` (`fs.create(new Path(hdfsPath), true)` at `:45`); cache seed at `OpenHouseInternalTableOperations.java:367`
- **principle**: Minimal knowledge — prefer designs where the rules are unbreakable by construction. The protocol's entire atomicity story (protocol brief §4) rests on metadata files being immutable and uniquely named once referenced; this path mutates a referenced file.
- **claim**: After the HTS pointer commits, the replicated-create path re-opens the published metadata.json and overwrites it non-atomically to patch `last-updated-ms`.
- **failure scenario**: Crash or storage error between `fs.create(path, true)` truncating the file and the write completing → the committed pointer references a truncated/corrupt file → every subsequent `doRefresh` throws (`refreshMetadata` → `InvalidTableMetadataException`, `:154-164`) → the table is fully unavailable for reads and writes until manual repair. Independent of crashes: the metadata cache was seeded with the pre-rewrite object at `:367`, so until TTL expiry this instance (and only this instance) serves metadata that disagrees with the file — replicas behind the load balancer disagree with each other.
- **options**:
  - *as-is*: a small window, but the blast is total table unavailability on a production replication path, plus permanent cache/file divergence semantics.
  - *enabling refactor*: patch the serialized JSON *before* the single `TableMetadataParser.write`-equivalent write at `:361-366` (the desired `last-updated-ms` value is already in hand at `:281-284`), then delete `MetadataUpdateUtils` from this path. One write, one file, immutable afterward; the checked-IOException source in finding 1 disappears with it. Touches this class only.
  - *restructure*: model replication timestamps as HTS-row/table-property state rather than a patched metadata field, removing the need to control the JSON field at all. Touches replication consumers.
- **severity**: blocker · **confidence**: confirmed · **reviewer**: arch-review

### Finding 3 — Rename bypasses every CAS: an open lost-update channel of the incident-12185 class

- **location**: `services/housetables/.../repository/impl/jdbc/UserTableHtsJdbcRepository.java:115-125`; callers `UserTablesServiceImpl.java:162`, `HouseTableRepositoryImpl.java:240-254`, `OpenHouseInternalTableOperations.java:386-400`; CAS exemption at `OpenHouseInternalTableOperations.java:610-615`
- **principle**: Narrow contracts, one seam — the pointer-mutation judgment must have one address; Minimal knowledge — the store should make an unguarded pointer write unrepresentable
- **claim**: `renameTableId` is a direct JPQL `UPDATE` that rewrites `metadataLocation` with no `@Version` check or bump and no expected-base comparison, and the rename commit flavor is exempt from `abortIfWriterBaseDivergedFromCatalog` (no `SNAPSHOTS_JSON_KEY`) and from `versionCheck` — so a rename racing a normal commit resolves by silent clobber instead of by conflict.
- **failure scenario**: Writer A appends to `db.t`: its `putSnapshots` commit lands, HTS row now points at `M_a` (contains A's acknowledged snapshot), `@Version` bumps. Concurrently, rename R (`ALTER TABLE db.t RENAME TO db.t2`) had already refreshed at the older base: `renameTable` (`OpenHouseInternalCatalog.java:292-244` region) commits a property-change transaction; `doCommit` writes `M_r` — derived from the pre-A metadata, without A's snapshot — and calls `houseTableRepository.rename(..., M_r)` (`:395-400`); `renameTableId` overwrites `metadataLocation = M_r` unconditionally. A's committed, acknowledged snapshot is gone. No error on either side. This is byte-for-byte the incident-12185 outcome (a durably committed snapshot silently rebased out), through a channel fix #612 explicitly does not defend.
- **options**:
  - *as-is*: renames are presumably rare relative to commits, but the deployment posture (silent data loss already happened once; a fix costs a release cycle) argues against leaving a known-open instance of the same class.
  - *enabling refactor*: make the rename write conditional — add `AND table.metadataLocation = :expectedBase` (and a `version` bump) to the JPQL, thread the expected base from `doCommit` (which holds `base.metadataFileLocation()`) through `HouseTableRepository.rename` and the HTS rename API (the API already carries `metadataLocation`; it needs the *from* location too); 0 rows updated → 409 → engine/user retries. Touches: JDBC repo, HTS service/handler/controller, HTS client API spec, `HouseTableRepositoryImpl`, `OpenHouseInternalTableOperations` — mechanical, no semantic change for the non-racing case.
  - *restructure*: route rename through the same guarded seam as `putUserTable` (single arbiter for all pointer mutations; see finding 7's ideal shape).
- **severity**: blocker · **confidence**: confirmed (the code paths and the absence of any guard are verified; the race window is the ordinary refresh-to-write window every commit has) · **reviewer**: arch-review

### Finding 4 — The commit payload rides as string properties through machinery that doesn't own it

- **location**: staging: `services/tables/.../repository/impl/OpenHouseInternalRepositoryImpl.java:183-216` (`COMMIT_KEY` at `:196`, forked-retryer comment at `:197-207`, transient-prefix stashing at `:750-781`), `:565-599` (create-path flags); consumption: `OpenHouseInternalTableOperations.java:267-312` (ordered strip sequence), `CatalogConstants.java:5-31`
- **principle**: Owned vocabulary — a module's interface, including its control signals, is expressed in its own vocabulary, not encoded into a third party's data structure; Enumerable outcomes — the contract is discoverable only from the implementation's strip order
- **claim**: Snapshots, refs, the CAS token, staging flags, sort order, evolved and intermediate schemas all cross the repository→catalog boundary as entries in Iceberg's table-property map, threaded through `BaseTransaction` — a carrier that actively rewrites its cargo.
- **evidence and impedance-mismatch classification**: This is the structural cause of #612: `BaseTransaction.applyUpdates` silently refreshed the base and re-stamped the stale property payload on top of it — the carrier behaved exactly as Iceberg designed it to, and the smuggled protocol had no defense. The system now carries three compensating mechanisms that all exist only because of the carrier choice: the `commit.num-retries=0` override whose correctness "relies on forked iceberg-core to use this property for building the base transaction retryer" (`OpenHouseInternalRepositoryImpl.java:197-207` — a fork-divergence dependency in the commit path's correctness argument); the `failIfRetryUpdate` dedup cache (finding 10); and the #612 CAS itself, whose call order relative to the key-stripping is documented as load-bearing (`OpenHouseInternalTableOperations.java:598-599`). The ideal shape of this seam, in a vacuum: the repository hands the catalog a typed commit request — `{base, snapshots, refs, schema changes, flags}` — through a channel nothing else reads or rewrites, and `doCommit` receives it as an argument, not as an archaeology exercise over a property map. Both sides are server-side, same process; `newTableOps` is constructed per request (`OpenHouseInternalCatalog.java:73-85`), so a per-operation context object is a natural fit.
- **failure scenario**: Already materialized once (incident-12185). Residual at HEAD: any iceberg-core upgrade or fork-drift that changes how transactions copy, refresh, or re-apply properties silently re-opens the class — the compiler cannot see this contract, so nothing announces the break (Contract evolution is API evolution).
- **options**:
  - *as-is*: defended for snapshot-bearing commits by the CAS trio; rename (finding 3) and replace (finding 8) flavors remain outside the defense; the fork coupling persists.
  - *enabling refactor*: keep the Iceberg transaction for genuine metadata edits but move the OH protocol fields out of properties into a typed per-operation context passed from `OpenHouseInternalRepositoryImpl` through `OpenHouseInternalCatalog.newTableOps` to the ops instance. Deletes the transient-prefix stash/restore machinery and the strip-order constraint. Touches repository, catalog, ops, and their tests; no API or client change.
  - *restructure*: drop server-side `BaseTransaction` entirely — the client already sends absolute desired state, so the repository can build the target `TableMetadata` and call `ops.commit(base, target)` directly. Removes the rebase vector, the retries override, the fork dependency, and `failIfRetryUpdate`. Blast radius: `OpenHouseInternalRepositoryImpl.save` and tests.
- **severity**: blocker (graded on the enabling refactor being available and contained; the class has already caused a production data-loss incident) · **confidence**: confirmed · **reviewer**: arch-review

### Finding 5 — Client `doCommit` routing is not total: unclassified commits are acknowledged and dropped

- **location**: `integrations/java/iceberg-1.2/.../javaclient/OpenHouseTableOperations.java:142-169`; `isMetadataUpdated` `:171-180`; `areSnapshotsUpdated` `:343-349`
- **principle**: Enumerable outcomes — the routing must cover the full space of `TableMetadata` change, or state its remainder loudly
- **claim**: `doCommit` dispatches on exactly six compared dimensions (schema, properties, spec, sortOrder, snapshots, refs); a commit whose only changes lie outside them (table location via `updateLocation`, statistics files, removed schemas/specs) matches no branch, makes no REST call, and returns — Iceberg's commit wrapper then reports success to the engine.
- **failure scenario**: `ALTER TABLE openhouse.db.t SET LOCATION '...'` (or an `UpdateStatistics` commit) on the Spark runtime: base and new metadata differ only in a dimension the classifier ignores → no HTTP request → the engine and user see a successful commit → the change never reaches the server and evaporates on the next refresh. An acknowledged-but-unpersisted commit is the same contract violation as a lost update, delivered politely.
- **options**:
  - *as-is*: exposure limited to operations OH doesn't intend to support — but the failure mode for those is silent success, the worst rendering of "unsupported".
  - *enabling refactor*: add a final `else` that throws (`BadRequestException`/`UnsupportedOperationException`: "commit contains changes the OpenHouse protocol does not carry"), so unsupported commit shapes fail loudly at the source. One file.
  - *restructure*: route on declared operation intent (the update list) rather than state diffing — aligns with finding 8's remediation.
- **severity**: blocker · **confidence**: probable (routing hole verified in code; that Spark exposes a reachable operation with only-unclassified changes is argued, not executed) · **reviewer**: arch-review

### Finding 6 — `processSchemas` swallows parse failures inside the commit and commits anyway

- **location**: `OpenHouseInternalTableOperations.java:704-715` (`catch (Exception e) { log.error(...) }` inside the stream lambda over intermediate + final schemas)
- **principle**: Caller owns policy — the commit path may not decide that a corrupt schema is ignorable; Narrow contracts, one seam — a try/catch inside a stream lambda marks an unhandled boundary smuggled into interior code
- **claim**: When intermediate schemas are present (replication), every schema after the first — including the final evolved schema — is applied inside a lambda whose `catch (Exception)` logs and continues, so a malformed schema JSON yields a successful commit with silently wrong schema lineage.
- **failure scenario**: Replication submits N intermediate schemas; entry k is malformed (encoding bug, truncation). The commit succeeds with schemas k..N missing; the replica's schema history and current schema diverge from the primary's with no error anywhere; data written or read against the expected schema misbehaves later, far from the cause.
- **options**: *as-is*; or the one-line enabling fix — let the exception propagate (it lands in the existing `IllegalArgumentException`/`Throwable` classification in `doCommit`, rendering as 400/failed commit), forcing the replication controller to retry or surface. Nothing else touches this seam.
- **severity**: blocker (silent wrong committed metadata on a production path) · **confidence**: confirmed for the mechanism, probable for encounter frequency · **reviewer**: arch-review

---

## Suggestions

### Finding 7 — Four CAS sites, three comparison semantics, and the authoritative one is buried in a mapper

- **location**: (a) `OpenHouseInternalRepositoryImpl.java:451-475` (`versionCheck`, scheme-less via `InternalRepositoryUtils.getSchemeLessPath:161-163`); (b) `OpenHouseInternalTableOperations.java:604-635` (hadoop `Path.toUri().getPath()`); (c) `OpenHouseInternalTableOperations.java:642-664` (dedup cache); (d) `services/housetables/.../dto/mapper/UserTableVersionMapper.java:20-47` (raw `String.equals` at `:34`, feeding the `@Version` inheritance that the DB enforces)
- **principle**: Narrow contracts, one seam — judgment with an address can be reviewed; judgment smeared across four layers has no owner; Parse, don't validate — three re-checks of the same datum, none of which the others can rely on
- **claim**: The base-version comparison is implemented four times with divergent normalization, and the *authoritative* instance — the only one the DB backs atomically — lives inside a MapStruct mapper plugin (`UserTableVersionMapper`) where a "mapping" throws the concurrency exception; every new commit flavor must independently rediscover which of the four checks apply to it (rename discovered none, finding 3).
- **failure scenario**: Any scheme drift in stored locations (storage migration, a client sending `hdfs://nn/path` where rows hold `/path`): checks (a)/(b) pass on normalized paths while (d) fails raw equality → every commit to the affected tables permanently 409s (or the inverse: (a)/(b) reject what (d) would accept, producing inconsistent conflict reporting across layers). Additionally `putUserTable`'s find-then-map-then-save is non-transactional (`UserTablesServiceImpl.java:98-127`) — safe today *only* because of `@Version`; the string comparison in (d) is advisory without it, which is invisible at the call site.
- **options**: *as-is*; *enabling refactor*: canonicalize the location at each service boundary (one normalization function used by tables service and HTS; store canonical form), move the HTS version decision out of the mapper into `UserTablesServiceImpl` where the transaction lives, and document (a)/(b) as fast-fail advisories over (d) as the sole arbiter; *restructure*: one pointer-arbiter component in HTS through which `putUserTable`, `renameUserTable`, and `restoreUserTable` all pass (subsumes finding 3's restructure option).
- **severity**: suggestion · **confidence**: confirmed · **reviewer**: arch-review

### Finding 8 — "Replace", the CAS-exempt commit flavor, is inferred from a diff instead of declared

- **location**: client inference `OpenHouseTableOperations.java:147-151` (`metadataUpdated && snapshotsUpdated && base != null` ⇒ `replaceCommit(true)`, `:411-416`); server exemption `OpenHouseInternalTableOperations.java:620-622` (`writerClaimedBase == null` ⇒ CAS skipped) via the replace branch not stamping `COMMIT_KEY` (`OpenHouseInternalRepositoryImpl.java:154-177`, `computePropsForTableCreation`); gate `validateReplaceTable:329-370`
- **principle**: Thin, total protocol surfaces — intent is a core concept, and re-deriving it from state diffs at the edge couples correctness to the diff heuristic; Enumerable outcomes
- **claim**: The one commit class that is authoritative over the entire snapshot set (and deliberately undefended by the #612 CAS) is selected by a client-side heuristic that also matches legitimate non-replace commits combining metadata and snapshot changes.
- **failure scenario**: A schema-evolving append (e.g., a merge-schema write) changes schema and snapshots in one transaction → classified `replaceCommit`. With RTAS disabled (default): the write hard-fails with `RTAS_DISABLED` telling the user to enable REPLACE — misleading and wrong for an append. With RTAS enabled on the table: the commit proceeds CAS-exempt with full authority over the snapshot set — a stale such commit can expire a concurrent writer's snapshots exactly as in #612, "by design".
- **options**: *as-is* (the RTAS property gate contains the silent-loss variant to opted-in tables); *enabling refactor*: server-side, require freshness even for replace — when a base exists, demand the replace declare it and abort on divergence (replace keeps authority over content, loses the right to be stale); touches `computePropsForTableCreation` + `abortIfWriterBaseDivergedFromCatalog`; *restructure*: carry the engine's actual operation kind in the request body instead of inferring, and key both authorization (`checkReplaceTablePrivilege`, `IcebergSnapshotsServiceImpl.java:78-84`) and CAS exemption on the declared, server-verified intent.
- **severity**: suggestion · **confidence**: confirmed (mechanism); probable (the merge-schema trigger) · **reviewer**: arch-review

### Finding 9 — The snapshots endpoint's outcome surface drifted from its sibling: unknown-state renders as 500

- **location**: `services/tables/.../services/IcebergSnapshotsServiceImpl.java:89-109` (no `CommitStateUnknownException` catch) vs `TablesServiceImpl.java:167-194` (has it, → `OpenHouseCommitStateUnknownException` → 503 at `OpenHouseExceptionHandler.java:146-160`); declared responses omit 503 (`IcebergSnapshotsController.java:34-40`)
- **principle**: Thin, total protocol surfaces — when the core grew the `CommitStateUnknown` outcome, every surface must consciously choose a rendering; here two hand-rolled copies of the same translation diverged; One boundary, one translation
- **claim**: On the *data* commit path — the one where unknown-state matters most — `CommitStateUnknownException` falls through to the generic `Exception` handler and returns 500 with a stack-trace body, not the typed 503.
- **failure scenario**: Any REST consumer that is not the OH Java client (the OpenAPI spec advertises only 200/201/400/409) treats the 500 as a plain failure, retries from its stale base or cleans up files whose references may have committed — re-opening the data-loss channel the 5xx→`CommitStateUnknown` client mapping (`OpenHouseTableOperations.java:430-438`) exists to close. The OH client is safe only because it defensively maps `InternalServerError` too.
- **options**: *as-is* (OH-client-only deployments are belt-protected); *enabling refactor*: extract one shared `translateSaveException` used by both services and add 503 to the endpoint's declared responses — trivial blast radius, both classes are in the same module.
- **severity**: suggestion · **confidence**: confirmed · **reviewer**: arch-review

### Finding 10 — `failIfRetryUpdate` burns commit keys before success, in a per-JVM cache

- **location**: `OpenHouseInternalTableOperations.java:93-94` (static Guava cache, 5-min TTL), `:642-664` (`CACHE.put` at `:654`, *before* the commit outcome is known)
- **principle**: Caller owns policy — "please retry from application" overrides the engine's sanctioned retry loop using information the server doesn't have; Trust interiors — the mechanism guards against the server's *own* transaction machinery, which is finding 4's defect wearing a cache
- **claim**: The dedup key (the base metadata path) is cached at first sight, so a commit that then fails for a transient reason poisons that base; the engine's legitimate retry (which re-refreshes and finds the base unchanged, because the commit truly failed) re-presents the same key to the same instance and receives a hard 409 instructing an application-level restart.
- **failure scenario**: HTS returns 429 under load → `HouseTableCallerException` → `CommitFailedException` (409) → the Spark engine's Iceberg retry loop refreshes (base unchanged) and retries → LB routes to the same instance within 5 minutes → `"table version ... is stale, please consider retry from application"` — a transient throttle escalated into a failed job. Behind the load balancer the cache also can't do the one job it claims for cross-instance retries; its real, valid scope (in-JVM `PropertiesUpdate.commit()` internal retries) is already largely covered by the `commit.num-retries=0` override and the #612 CAS.
- **options**: *as-is*; *enabling refactor*: move `CACHE.put` after the successful `houseTableRepository.save` so only committed keys are burned (a repeat then genuinely means replay-after-success, which the CAS also catches via the advanced base); *restructure*: with finding 4's restructure (no server-side transaction), delete the mechanism.
- **severity**: suggestion · **confidence**: confirmed (mechanism), probable (scenario) · **reviewer**: arch-review

### Finding 11 — Conflict-vs-bad-request classification by message-string matching

- **location**: `OpenHouseInternalTableOperations.java:670-675` (`msg.contains("Cannot add snapshot with sequence number")…`), used at `:440-445`
- **principle**: Failures flow low to high — matching on another layer's message strings is a dependency the compiler cannot see
- **claim**: Whether a concurrent-write `ValidationException` is retriable (409) or a client error (400) depends on Iceberg's exception wording.
- **failure scenario**: An iceberg-core upgrade (or fork drift — the server already runs a fork) rewords the message → genuine concurrency conflicts render as 400 `BadRequestException` → engines stop retrying → routine concurrent writers see hard failures; no test fails at build time because the coupling is invisible.
- **options**: *as-is*; *enabling refactor*: detect the condition structurally — compare the payload's sequence numbers against the base before `builder.build()` (the inputs are all in hand in the merge block at `:314-354`) and throw `CommitFailedException` directly, leaving remaining `ValidationException`s as 400.
- **severity**: suggestion · **confidence**: confirmed · **reviewer**: arch-review

### Finding 12 — The HTS boundary's vocabulary is wrong in both directions

- **location**: downward: `iceberg/openhouse/internalcatalog/.../repository/HouseTableRepository.java:16-18` extends Spring Data `PagingAndSortingRepository`; `HouseTableRepositoryImpl.java:257-337` throws `UnsupportedOperationException` from 10+ inherited methods. Upward: `services/housetables/.../services/UserTablesServiceImpl.java:27,112` imports and catches `org.apache.iceberg.exceptions.CommitFailedException`
- **principle**: Enumerable outcomes — an interface advertising operations its implementation cannot honor turns every call site into a latent landmine (finding 1 stepped on `delete`); Owned vocabulary / Directed dependencies — the bottom store service interprets the top layer's storage-technology exception because an alternate Iceberg-backed repository leaks its vocabulary through the repository seam instead of translating there
- **claim**: The HTS contract is simultaneously too wide toward its callers (framework CRUD surface, mostly unimplemented) and pointed the wrong way internally (HTS service code knowing Iceberg's failure vocabulary).
- **failure scenario**: The concrete one already exists as finding 1 — `doCommit` called an advertised-but-unimplemented method in a cleanup path where the resulting `UnsupportedOperationException` masks the real failure. The upward reference means an Iceberg version bump ripples into `services:housetables`, a module with no business knowing Iceberg exists.
- **options**: *as-is*; *enabling refactor*: replace `PagingAndSortingRepository` inheritance with a hand-written interface listing exactly the supported operations (the impl already documents them by elimination), and translate the Iceberg-backed HTS repository's failures into an HTS-owned exception at that repository, deleting the `CommitFailedException` catch in the service. Blast radius: internalcatalog + tables-service call sites (mechanical), HTS service/repo.
- **severity**: suggestion · **confidence**: confirmed · **reviewer**: arch-review

### Finding 13 — Client refresh maps HTTP 400 to "table absent"

- **location**: `OpenHouseTableOperations.java:108` (`onErrorResume(WebClientResponseException.BadRequest.class, e -> Mono.empty())`), consumed at `:119-126`
- **principle**: Dependency failure is its own category — never surface an infrastructure/protocol failure as a domain outcome; Absence is a type — invalid-input and absent are different triage categories
- **claim**: A 400 during `doRefresh` is treated identically to 404: the table is reported nonexistent.
- **failure scenario**: A server-side validation change (or gateway emitting 400s) makes `GET /v1/.../tables/{t}` return 400 for an existing table: a previously-loaded client throws `NoSuchTableException "maybe another process deleted it"`; a fresh load reports the table absent, and a CTAS-style flow proceeds down its create path — an outage converted into the wrong answer "your table does not exist", propagated as truth.
- **options**: *as-is*; *enabling refactor*: drop the 400→empty mapping (let 400 surface as the request error it is) — one line; if a legacy server behavior motivated it, gate it on the specific known response.
- **severity**: suggestion · **confidence**: probable (the mapping is confirmed; the motivating server behavior for it was not recoverable from the code) · **reviewer**: arch-review

### Finding 14 — The snapshot payload is trusted wholesale, and the merge is subtractive

- **location**: `SnapshotsUtil.java:45-47` (the `FileIO` parameter is never used — no manifest-list existence or ownership check); `OpenHouseInternalTableOperations.java:337-344` (`removeSnapshots` of everything absent from the payload), `:346-351` (refs wholesale-synced; a snapshots-payload with absent refs key removes all refs, `:317-320`)
- **principle**: Parse, don't validate — the boundary converts the payload into `Snapshot` objects but validates none of the semantics downstream code relies on; Minimal knowledge — the only defense for a destructive full-state write is the base CAS trio (finding 7)
- **claim**: Any request that passes the pointer CAS is authoritative over the entire snapshot set and ref set, with no validation that referenced manifest lists exist, belong to this table's location, or that the removal set is plausible for the declared base.
- **failure scenario**: A buggy or version-skewed client (the serialization is hand-rolled Gson-of-`SnapshotParser` strings on both sides) submits a payload with a wrong-but-parsable manifest-list path, or an empty snapshot list against a populated base: the server commits metadata pointing at nonexistent files, or expires every snapshot — both acknowledged as success. The #612 CAS defends against *stale* payloads, not *wrong* ones.
- **options**: *as-is* (single-client ecosystems mostly self-consistent); *enabling refactor*: at the parse seam, verify manifest-list paths fall under the table location and (optionally, behind a flag) exist via the already-provided `FileIO`; bound the removal set (e.g., refuse to remove the base's current snapshot unless the request is an explicit expiration). Touches `SnapshotsUtil` + the merge block.
- **severity**: suggestion · **confidence**: confirmed (absence of validation); probable (encounter) · **reviewer**: arch-review

---

## Nits

### Finding 15 — Stack traces and `exception.toString()` in every error response
- **location**: `services/common/.../exception/handler/OpenHouseExceptionHandler.java` — every builder includes `.stacktrace(getAbbreviatedStackTrace(...))`; the fallback 500 additionally sets `message(exception.toString())` (`:402-415`)
- **principle**: Thin, total protocol surfaces / Dependency failure is its own category — vendor and internal detail belong in edge logs, not the client body
- **claim / failure scenario**: Internal class names, HTS endpoints, and storage paths leak to any API consumer on every failure; combined with finding 9, the data path's unknown-state response is a 500 whose body is a stack trace.
- **options**: gate stacktrace fields behind a debug flag; keep message text service-owned. Severity: nit · confidence: confirmed · reviewer: arch-review

### Finding 16 — Fabricated and dropped causes destroy the descent record
- **location**: `UserTableVersionMapper.java:30,44` (`EntityConcurrentModificationException(..., new RuntimeException())`); `MetadataUpdateUtils.java:52-56` (`throw new IOException(errMsg)` — original `e` dropped — after log-and-rethrow)
- **principle**: Failures flow low to high — wrapping without the cause destroys the evidence debugging needs
- **failure scenario**: A production 409/IO failure's root cause is unreconstructible from logs precisely on the paths (CAS conflict, post-commit rewrite) where forensics matter most.
- **options**: pass the real cause; delete the duplicate log line. Severity: nit · confidence: confirmed · reviewer: arch-review

### Finding 17 — Interrupt detected, flag not restored
- **location**: `OpenHouseTableOperations.java:156-167` (client `doCommit` inspects `e.getCause() instanceof InterruptedException`, maps to `CommitStateUnknownException`, never calls `Thread.currentThread().interrupt()`)
- **principle**: Enumerable outcomes / Caller owns policy (symptom catalog: interrupt swallowing)
- **failure scenario**: The engine's executor loses the cancellation signal; a cancelled Spark task keeps running its remaining commit machinery.
- **options**: restore the flag before rethrowing. Severity: nit · confidence: confirmed · reviewer: arch-review

### Finding 18 — Concurrent drop surfaces as `IllegalStateException` → 500
- **location**: `OpenHouseInternalTableOperations.java:126-130` (row vanished between requests ⇒ `IllegalStateException`), contrast client `:120-123` which models the same event as `NoSuchTableException`
- **principle**: Fail fast when decidable — this *is* decidable (absent), not an invariant violation; concurrent deletion is an expected outcome in a multi-writer control plane
- **failure scenario**: A writer racing a drop gets 500 "internal server error" instead of a 404-family outcome its retry policy could act on.
- **options**: throw the catalog's not-found type. Severity: nit · confidence: confirmed · reviewer: arch-review

---

## Appendix

### A. What was examined
All 25 manifest files were read at HEAD `2a9dac8`, plus: `settings.gradle` and the four relevant module `build.gradle` files (module-graph orientation), `HouseTableRepository` (interface), `SpringTableMetadataCache`/`CacheConfiguration`, `OpenHouseUserTableHtsApiHandler`, `OpenHouseIcebergSnapshotsApiHandler`, and the client `OpenHouseCatalog.newTableOps`. Caller analysis for every finding traces through the chain documented in the protocol brief and was independently verified against the code (all `file:line` pointers above re-checked at HEAD).

### B. Disposition of the 10 pre-flagged smells (verified independently)
| Smell | Verdict | Finding |
|---|---|---|
| 1 delete() landmine in IOException handler | confirmed, and worse: the catch is *only* reachable post-commit | 1 |
| 2 dedup cache per-JVM, pre-commit burn | confirmed | 10 |
| 3 in-place metadata rewrite | confirmed (+ cache divergence + cause-drop) | 2, 16 |
| 4 rename bypasses optimistic locking | confirmed; graded blocker (open lost-update of the #612 class) | 3 |
| 5 HTS raw-string CAS compare | confirmed | 7 |
| 6 processSchemas swallow | confirmed; includes the final schema when intermediates present | 6 |
| 7 payload trusted wholesale / unused FileIO | confirmed | 14 |
| 8 snapshots path missing 503 | confirmed; 503 also absent from declared API responses | 9 |
| 9 HTS catches Iceberg CommitFailedException | confirmed | 12 |
| 10 non-transactional put safe only via @Version | confirmed; folded into 7 | 7 |

### C. Notes on what was deliberately not reported
Per the anti-noise commitments: the near-duplicate row/DTO types on the two sides of the HTS REST boundary (`HouseTable` vs `UserTable`/`UserTableRow`) are intentional boundary duplication — their mapping functions were reviewed instead (the `tableLocation⇄metadataLocation` mappings are total and consistent). No translation layers were demanded between same-module components (e.g., between `IcebergSnapshotsServiceImpl` and `OpenHouseInternalRepositoryImpl`); finding 9's remediation is intra-module reuse of an existing translation, not new wrapping. The `versionCheck` advisory pre-check duplicating the catalog CAS was judged as CAS-ownership smear (finding 7), not as duplication to consolidate blindly — it has value as a fast-fail if demoted explicitly to that role.
