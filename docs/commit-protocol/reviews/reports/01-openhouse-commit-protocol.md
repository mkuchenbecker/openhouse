# OpenHouse Table Commit Protocol — End-to-End Control Flow

All paths are relative to `/home/user/openhouse`. Line numbers verified against the working tree at commit `2a9dac8` (fork synced with linkedin/openhouse main; includes fix #612 `abortIfWriterBaseDivergedFromCatalog`). Server modules build against the forked `com.linkedin.iceberg:iceberg-core:1.5.2.17` (`buildSrc/src/main/groovy/openhouse.iceberg-conventions-1.5.2.gradle:6-9`, `build.gradle:33-34`); the Java/Spark client runtime uses the 1.2 fork (`integrations/java/iceberg-1.2/...`).

---

## 1. The three-layer architecture and the full call chain

There are **two** Iceberg `TableOperations` implementations in play — one client-side, one server-side — plus a plain JPA row store at the bottom:

| Layer | Catalog class | TableOperations | Persistence |
|---|---|---|---|
| Client (Spark/Java engine) | `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseCatalog.java` | `OpenHouseTableOperations` (client) | REST calls to Tables service |
| Tables REST service | `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalCatalog.java` | `OpenHouseInternalTableOperations` | writes metadata.json to storage; row via HTS REST client |
| House Tables service (HTS) | — | — | Spring Data JPA (`UserTableRow`) in a JDBC database |

### 1a. Client side (Spark → OpenHouse catalog client)

- `integrations/spark/spark-3.1/openhouse-spark-runtime/src/main/java/com/linkedin/openhouse/spark/OpenHouseCatalog.java:18` — the Spark catalog is literally `public class OpenHouseCatalog extends com.linkedin.openhouse.javaclient.OpenHouseCatalog {}`; Spark drives it through Iceberg's `SparkCatalog`/`BaseMetastoreCatalog` machinery.
- `javaclient/OpenHouseCatalog.newTableOps` — `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseCatalog.java:282-290` builds an `OpenHouseTableOperations` with generated REST clients `TableApi` + `SnapshotApi`.
- Client refresh: `OpenHouseTableOperations.doRefresh` — `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseTableOperations.java:97-128`. Calls `GET /v1/databases/{db}/tables/{table}` (`tableApi.getTableV1`), takes `GetTableResponseBody.tableLocation` (line 119) — the **path of the current metadata.json** — and loads it from storage via `refreshFromMetadataLocation` (line 126). The client reads metadata.json directly from HDFS/blob storage; the REST service only hands out the pointer.
- Client commit: an engine-side commit (e.g. Spark append → Iceberg `SnapshotProducer.commitOperation` → `TableOperations.commit(base, metadata)` in Iceberg's `BaseMetastoreTableOperations`) lands in `OpenHouseTableOperations.doCommit` — `OpenHouseTableOperations.java:142-169`. It routes on what changed:
  - snapshots + metadata changed with existing table → `putSnapshotsForReplace` (`:411-416`, sets `replaceCommit(true)`)
  - snapshots changed → `putSnapshots` (`:399-403`) → `commitSnapshots` (`:364-391`) → **`PUT /v1/databases/{db}/tables/{table}/iceberg/v2/snapshots`** (`snapshotApi.putSnapshotsV1`, `:379-390`)
  - only metadata changed → `createUpdateTable` (`:187-203`) → **`PUT /v1/databases/{db}/tables/{table}`** (`tableApi.updateTableV1`)
- Both request bodies carry the CAS token: `baseTableVersion = base == null ? "INITIAL_VERSION" : base.metadataFileLocation()` — `OpenHouseTableOperations.java:208-209` (metadata body) and `:369-370` (snapshots body). The snapshots body also carries the **entire** snapshot list and refs of the new metadata: `jsonSnapshots` = every `SnapshotParser.toJson` of `newMetadata.snapshots()` and `snapshotRefs` (`:371-376`).
- HTTP error → Iceberg exception mapping (client): `handleCreateUpdateHttpError` — `OpenHouseTableOperations.java:418-464`: 404→`NoSuchTableException`; **409→`CommitFailedException`** (retriable by the engine); **500/503/504→`CommitStateUnknownException`** (deliberately, so Iceberg does *not* clean up data/manifest files, `:430-438`); 400→`BadRequestException`; anything else (incl. request-level failures with no response) → `CommitStateUnknownException` (`:449-463`).

### 1b. Tables REST service

- Controllers: `services/tables/src/main/java/com/linkedin/openhouse/tables/controller/TablesController.java:189-196` (`PUT .../tables/{tableId}` → `updateTable`; `POST` create at `:157`) and `services/tables/src/main/java/com/linkedin/openhouse/tables/controller/IcebergSnapshotsController.java:41-66` (`PUT .../iceberg/v2/snapshots` → `putSnapshots`).
- Handlers: `services/tables/src/main/java/com/linkedin/openhouse/tables/api/handler/impl/OpenHouseTablesApiHandler.java:127-133` (update → 200, create → 201) and `OpenHouseIcebergSnapshotsApiHandler.java:29-40` (created? 201 : 200).
- Services:
  - `services/tables/src/main/java/com/linkedin/openhouse/tables/services/TablesServiceImpl.java:99-165` `putTable` — lock/authz checks, no-op short-circuit (`:135-137`), builds `TableDto` via `TablesMapper.toTableDto` and calls `saveTableDto` (`:167-194`).
  - `services/tables/src/main/java/com/linkedin/openhouse/tables/services/IcebergSnapshotsServiceImpl.java:36-110` `putIcebergSnapshots` — same shape for the snapshots endpoint.
  - Mapping of the CAS token: `services/tables/src/main/java/com/linkedin/openhouse/tables/dto/mapper/TablesMapper.java:71-72` and `:94-95` — `requestBody.baseTableVersion → TableDto.tableVersion` ("store base version to check later"). Snapshot payload: `:91-92` `jsonSnapshots`/`snapshotRefs` onto the DTO.
- Repository (the service's "JPA-like" facade over the Iceberg catalog): `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java:111-226` `save(TableDto)`:
  - **create** (`:126-153`): `versionCheck(null, dto)` requires `tableVersion == "INITIAL_VERSION"` (`:466-474`), allocates table location, `catalog.buildTable(...).create()` (`:228-242`).
  - **replace / RTAS** (`:154-177`): `replaceTransaction()` + `txn.commitTransaction()` (`:244-267`).
  - **update** (`:178-223`): `catalog.loadTable` → `table.newTransaction()`; `updateEligibilityCheck` (`:434-445`) runs **`versionCheck`** (`:451-475`): compares `existingTable.properties()["openhouse.tableLocation"]` (current metadata.json path) against the request's `tableVersion` (scheme-less compare via `InternalRepositoryUtils.getSchemeLessPath`, `services/tables/.../impl/InternalRepositoryUtils.java:161`), throwing `CommitFailedException` on mismatch — this is the *first* (advisory) CAS check.
  - Stages the payload as **table properties on the transaction**: evolved schema (`:643-674`), user props (`:681-694`), snapshots (`doUpdateSnapshotsIfNeeded` `:696-708` — sets `SNAPSHOTS_JSON_KEY`/`SNAPSHOTS_REFS_KEY` properties), policies, sort order (`:269-280`), then **`updateProperties.set(COMMIT_KEY, tableDto.getTableVersion()).commit()`** (`:196`) — the writer's declared base travels to the catalog layer as the `commitKey` property. It also forces `commit.num-retries=0` via `overrideProperty` (`:201-207`, `:750-781`) — relying on the forked iceberg-core to build the `BaseTransaction` retryer from the *new* properties — so the **server-side Iceberg transaction never auto-retries** (an auto-retry would silently rebase onto a concurrent commit).
  - If anything changed, `transaction.commitTransaction()` (`:216`) → Iceberg `BaseTransaction` → `OpenHouseInternalTableOperations.commit/doCommit`.
- Exception → HTTP mapping (service layer): `TablesServiceImpl.saveTableDto:171-193` and `IcebergSnapshotsServiceImpl:91-109` — `BadRequestException`→`RequestValidationFailureException`(400), `CommitFailedException`→`EntityConcurrentModificationException`(**409**), `CommitStateUnknownException`→`OpenHouseCommitStateUnknownException`(**503**). The advice that turns these into responses: `services/common/src/main/java/com/linkedin/openhouse/common/exception/handler/OpenHouseExceptionHandler.java:130-137` (409), `:146-152` (503), `:207-236` (AlreadyExists→409), `:402-415` (fallback 500). Note `IcebergSnapshotsServiceImpl` does **not** catch `CommitStateUnknownException` — it falls through to the generic 500 handler (client still maps 500→CommitStateUnknown, but the response body/status differ from the tables path).

### 1c. Server-side catalog (`OpenHouseInternalTableOperations`)

`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java` (extends the forked `BaseMetastoreTableOperations`):

- Instantiated per request: `OpenHouseInternalCatalog.newTableOps` — `iceberg/openhouse/internalcatalog/.../OpenHouseInternalCatalog.java:72-84` (wires `HouseTableRepository`, `FileIO` resolved from the table's storage type `:302-325`, `HouseTableMapper`, metrics, metadata cache).
- `doRefresh` — `OpenHouseInternalTableOperations.java:106-132`: `houseTableRepository.findById(db, table)` → `HouseTable.getTableLocation()` → `refreshMetadata` (`:135-173`) → Iceberg `refreshFromMetadataLocation(loc, null, 20, this::loadTableMetadataWithCache)` (parses metadata.json from storage, through `TableMetadataCache`, `:849-852`).
- `commit(base, metadata)` override — `:212-222`: delegates to `BaseMetastoreTableOperations.commit` (which validates `base == current()` in-memory, calls `doCommit`, then forces a refresh), disabling the forced refresh for stage-create/stage-replace.
- **`doCommit(base, metadata)`** — `:250-489`, the heart of the protocol. In order:
  1. `processSchemas` (`:686-718`) — applies client/evolved/intermediate schemas by rebuilding `TableMetadata`.
  2. `int version = currentVersion() + 1` (`:258`) — monotonically increasing metadata **file ordinal** (count of known metadata files; only used to prefix the file name).
  3. `rootMetadataFileLocation(metadata, version)` (`:191-201`, used at `:262`) — new metadata path `"{tableLocation}/%05d-%s.metadata.json" % (version, randomUUID)`. The UUID makes concurrent writers at the same version collision-free.
  4. `abortIfWriterBaseDivergedFromCatalog(base, metadata)` (`:269`, impl `:604-635`) — **catalog-level CAS**: for snapshot-bearing commits, compares the writer's `commitKey` property (declared base metadata location) against `base.metadataFileLocation()` (the catalog's current base as of `doRefresh`); mismatch → `CommitFailedException` (409, retriable). Added by fix #612 to close the `BaseTransaction.applyUpdates` silent-rebase hole.
  5. `failIfRetryUpdate(properties)` (`:271`, impl `:642-664`) — an in-JVM Guava cache of seen `commitKey`s (5-min TTL, max 1000, `:93-94`); a repeated key means Iceberg's *internal* retry re-submitted the same user commit → hard `CommitFailedException` telling the user to retry from the application. Then strips `commitKey` from the properties.
  6. Property bookkeeping (`:272-305`): `restoreOverriddenProperties` un-stashes the `__transient_restore_/__transient_added_` overrides; **`openhouse.tableVersion` ← previous `openhouse.tableLocation`** (or `INITIAL_VERSION`); **`openhouse.tableLocation` ← newMetadataLocation** (`:274-278`); `lastModifiedTime`/`creationTime`; strips transport-only keys (`snapshotsJsonToBePut`, `snapshotsRefs`, `sortOrder`, staging flags, evolved/intermediate schemas).
  7. Snapshot reconciliation (`:314-354`) — see §5.
  8. **Write metadata.json**: `TableMetadataParser.write(updatedMetadata, io().newOutputFile(newMetadataLocation))` (`:356-383`), then seeds the metadata cache (`:367`).
  9. **Persist the pointer**: `houseTable = houseTableMapper.toHouseTable(metadata, fileIO)` (`:385`; `mapper/HouseTableMapper.java:29-31` extracts the `openhouse.*` properties — tableLocation, tableVersion, tableUri, UUID, creator, times — into the `HouseTable` row model `internal/catalog/model/HouseTable.java:21-54`); then either `houseTableRepository.rename(...)` for a rename commit (`:386-400`) or **`houseTableRepository.save(houseTable)`** (`:401-411`); staged tables skip HTS entirely and just refresh from the new file (`:412-419`).
  10. Error translation (`:424-476`): `IOException`→`checkCommitStatus` + HTS-row cleanup attempt + `CommitFailedException`; `InvalidIcebergSnapshotException|IllegalArgumentException`→`BadRequestException`; `ValidationException`→409 if stale-sequence-number (`isStaleSnapshotError` `:670-675`) else 400; `HouseTableCallerException|HouseTableNotFoundException|HouseTableConcurrentUpdateException`→**`CommitFailedException`** (409); any other `Throwable` → `checkCommitStatus(newMetadataLocation, metadata)` (inherited Iceberg helper that re-reads the catalog pointer to classify SUCCESS/FAILURE/UNKNOWN) → `CommitFailedException` or **`CommitStateUnknownException`**.

### 1d. HTS client + service

- `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImpl.java:152-162` `save(HouseTable)` → `PUT /hts/tables` via generated `UserTableApi.putUserTable`, **no retries on writes** (`:60-61`), 60 s timeout. HTTP→typed exception translation `handleHtsHttpError` (`:188-217`): 404→`HouseTableNotFoundException`, **409→`HouseTableConcurrentUpdateException`**, 400/401/403/429→`HouseTableCallerException`, 5xx→`HouseTableRepositoryStateUnknownException` ("Cannot determine if HTS has persisted the proposed change"). Reads retry 3× with exponential backoff (`HtsRetryUtils.java:15-22`).
- HTS controller: `services/housetables/src/main/java/com/linkedin/openhouse/housetables/controller/UserHouseTablesController.java:215-225` `putUserTable` → handler `api/handler/OpenHouseUserTableHtsApiHandler.java:92-93` (existed? 200 : 201) → **`services/housetables/src/main/java/com/linkedin/openhouse/housetables/services/UserTablesServiceImpl.java:98-127` `putUserTable`**:
  - `findById` current row (`:99-104`),
  - `userTablesMapper.toUserTableRow(userTable, existingRow)` (`:106-107`; `dto/mapper/UserTablesMapper.java:47-50`) which delegates the version resolution to `UserTableVersionMapper.toVersion` — see §2,
  - `htsJdbcRepository.save(targetRow)` (`:111`) — Spring Data JPA save on `UserTableHtsJdbcRepository` (`repository/impl/jdbc/UserTableHtsJdbcRepository.java:23`),
  - `CommitFailedException | ObjectOptimisticLockingFailureException | DataIntegrityViolationException` → `EntityConcurrentModificationException` (`:112-124`) → HTTP **409** via `OpenHouseExceptionHandler.java:130-137`.

---

## 2. The "version": what it actually is, where generated/stored/compared

There are **three** distinct version-like artifacts; only one is the real CAS guard:

1. **`tableVersion` (client-facing / OH protocol) = a metadata.json path string, not a number.**
   - Generated implicitly: it *is* the previous commit's `metadataLocation`.
   - Client sends `baseTableVersion = base.metadataFileLocation()` (`OpenHouseTableOperations.java:208-209, 369-370`); `"INITIAL_VERSION"` sentinel for creation (`:95`, `CatalogConstants.java:19`, `ValidatorConstants.INITIAL_TABLE_VERSION`).
   - Stored twice server-side: as table property `openhouse.tableVersion` = *previous* metadata location (stamped in `OpenHouseInternalTableOperations.java:274-277`) inside the metadata.json itself, and as the HTS row column `tableVersion` (`HouseTable.java:35`; the DTO surface maps `UserTableRow.metadataLocation → UserTableDto.tableVersion`, `UserTablesMapper.java:59`).
   - Compared in three places: (a) tables-service advisory check `versionCheck` (`OpenHouseInternalRepositoryImpl.java:451-475` — request `tableVersion` vs current `openhouse.tableLocation`); (b) catalog CAS `abortIfWriterBaseDivergedFromCatalog` (`OpenHouseInternalTableOperations.java:604-635` — `commitKey` vs `base.metadataFileLocation()`, path-normalized); (c) HTS `UserTableVersionMapper.toVersion` (`services/housetables/src/main/java/com/linkedin/openhouse/housetables/dto/mapper/UserTableVersionMapper.java:20-47` — request `tableVersion` vs the row's stored `metadataLocation`; mismatch → `EntityConcurrentModificationException`; nonexistent row + non-INITIAL version → "deleted by other processes").

2. **HTS `UserTableRow.version` — a JPA `@Version Long`** (`services/housetables/src/main/java/com/linkedin/openhouse/housetables/model/UserTableRow.java:28`). This is the *actual monotonically increasing number* and the **authoritative CAS**: `UserTableVersionMapper` copies the existing row's `@Version` onto the new entity only when the caller's `tableVersion` equals the row's current `metadataLocation` (`UserTableVersionMapper.java:33-36`); Hibernate then issues `UPDATE ... SET version = v+1 ... WHERE version = v`. If any concurrent writer bumped the row between HTS's own `findById` and `save`, the DB update matches 0 rows → `ObjectOptimisticLockingFailureException` → 409. So the end-to-end optimistic concurrency is: *string-equality precheck on the metadata pointer + DB optimistic lock as the atomic arbiter*.

3. **`int version = currentVersion() + 1`** (`OpenHouseInternalTableOperations.java:258`) — an Iceberg-internal counter of metadata files used only to build the `%05d-` file-name prefix and error messages. It is *not* checked by anything; uniqueness comes from the UUID suffix.

CAS decision chain on conflict (two writers from same base): both pass client-side; both may pass `versionCheck` and `abortIfWriterBaseDiverged` (they raced past `doRefresh`); both write their metadata.json (different UUID names — safe); the **first** `htsJdbcRepository.save` wins and bumps `@Version`; the second gets 409 from HTS → `HouseTableConcurrentUpdateException` (`HouseTableRepositoryImpl.java:191-192`) → `CommitFailedException` (`OpenHouseInternalTableOperations.java:448-451`) → 409 from Tables service → client `CommitFailedException` → Iceberg engine refreshes and retries (see §3).

---

## 3. metadata.json writes, conflict detection, and retries

**Who writes metadata.json:** only the Tables service (server side), in `doCommit` step 8 (`OpenHouseInternalTableOperations.java:361-366`), via `TableMetadataParser.write(meta, io().newOutputFile(loc))`. The client never writes table metadata (it writes data/manifest/manifest-list files directly to storage before calling the REST API; those are referenced by the serialized snapshots it submits).

**Naming scheme:** `{tableLocation}/{%05d version}-{UUID}{.metadata.json[.gz]}` (`:191-201`), written into the **table root directory** (not `/metadata`; documented at `:175-190`). Compression suffix from `write.metadata.compression-codec`.

**Atomicity:** the file write is a plain create (no write-temp-then-rename); it is *not* the commit point and doesn't need atomicity — the UUID name guarantees no two writers target the same path, and the file is unreachable garbage until the HTS row points at it. **The commit point is the single-row HTS DB update.** After-commit retention of old metadata files follows normal Iceberg semantics, configured at table creation: `write.metadata.delete-after-commit.enabled` + `write.metadata.previous-versions-max` from cluster properties (`OpenHouseInternalRepositoryImpl.java:601-618`).

**Conflict detection sides & exceptions:**
- Tables service pre-check: `versionCheck` → `CommitFailedException` (`OpenHouseInternalRepositoryImpl.java:457-464`) — advisory, non-atomic.
- Catalog CAS: `abortIfWriterBaseDivergedFromCatalog` → `CommitFailedException` (`OpenHouseInternalTableOperations.java:629-634`).
- Iceberg metadata validation during snapshot rebuild: `ValidationException` "Cannot add snapshot with sequence number ... older than last sequence number" reclassified as retriable → `CommitFailedException` (`:440-445`, `:670-675`); other `ValidationException`s → 400.
- HTS (authoritative): `EntityConcurrentModificationException` → 409 (`UserTablesServiceImpl.java:110-124`, `UserTableVersionMapper.java:24-45`).
- REST status mapping: 409 CONFLICT (`OpenHouseExceptionHandler.java:130-137`), 503 for commit-state-unknown (`:146-152`), 400 for validation (`:177-186`), fallback 500 (`:402-415`).

**Retry loops (three, deliberately collapsed to one):**
1. *Iceberg engine retry (client)* — the only sanctioned retry. 409 → client `CommitFailedException` (`OpenHouseTableOperations.java:424-428`) → Iceberg's `SnapshotProducer`/`Transactions` retry loop (per-table `commit.retry.num-retries`, default 4) re-runs `doRefresh` (picking up the winner's metadata) and reapplies the operation.
2. *Server-side Iceberg transaction retry* — **disabled**: `commit.num-retries` forced to `"0"` on every update transaction (`OpenHouseInternalRepositoryImpl.java:201-207`), and `failIfRetryUpdate` (`OpenHouseInternalTableOperations.java:642-664`) additionally poisons any internal retry that re-presents the same `commitKey` (e.g. iceberg-core `PropertiesUpdate.commit()`'s own retryer), because a server-side reapply would rebase the user's payload without the user's knowledge.
3. *HTS client retry* — reads retry 3× w/ backoff; **writes never retry** (`HouseTableRepositoryImpl.java:58-61`), so an ambiguous write stays ambiguous and is surfaced as unknown-state rather than risking a duplicate CAS.

---

## 4. Failure windows and atomicity

Commit steps (server), with crash-between-steps analysis. **Source of truth = the HTS row (`metadataLocation` column); metadata.json files are subordinate; the metadata cache and client caches are hints.**

| # | Step | Crash/failure after this step ⇒ |
|---|---|---|
| S1 | `doRefresh` loads HTS row + metadata.json | nothing written; request fails 5xx; no state change. |
| S2 | validations (versionCheck, abort-on-divergence, failIfRetryUpdate) | same. Note `failIfRetryUpdate` **caches the commitKey before the commit succeeds** (`OpenHouseInternalTableOperations.java:648-654`), so a failed commit's key is already burned in that JVM — a client-engine retry with the same base against the *same instance* gets a spurious "stale" 409 and must rebase (by design, but see smells). |
| S3 | metadata.json written to storage (`:361-366`) | **orphan metadata file**: HTS still points at old location; table state unchanged; readers unaffected. Orphan is invisible garbage cleaned by maintenance jobs. Client sees 409/503/500 depending on the exception class. |
| S4 | `houseTableRepository.save` → HTS HTTP call in flight | *The ambiguous window.* (a) HTS returns 409 → clean loss, `CommitFailedException`, 409 to client, engine retries. (b) HTS returns 5xx or times out → `HouseTableRepositoryStateUnknownException` — **not** in the `HouseTable*` catch at `:448-451`, so it falls to the `Throwable` handler (`:452-476`) → `checkCommitStatus(newMetadataLocation, metadata)` (Iceberg base-class helper; re-reads the pointer with retries): if HTS actually persisted → treated as SUCCESS; if provably not → `CommitFailedException`; else → `CommitStateUnknownException` → 503 → client keeps its files (no cleanup) and surfaces unknown-state to the application. |
| S5 | HTS row updated in DB (the **atomic commit point**: one-row JPA update with `@Version` CAS; `UserTablesServiceImpl.java:111`) | commit is durable regardless of what fails later. Tables-service crash before responding ⇒ client gets connection error → `CommitStateUnknownException` (`OpenHouseTableOperations.java:449-463`). A client/application retry from the old base now gets 409 and must refresh — correct behavior. |
| S6 | post-commit extras: cache seed already done at S3 (`:367`); replicated-create **in-place rewrite** of the just-committed metadata.json (`:420-422` → `utils/MetadataUpdateUtils.java:37-59`, `fs.create(path, true)`) | crash mid-rewrite ⇒ committed pointer references a truncated/corrupt file (replication-create path only). Non-atomic overwrite of committed state — see smells. |
| S7 | HTTP 200/201 with new `tableVersion`/`tableLocation` | client `commit()` (Iceberg base class) triggers `doRefresh`, sees its own commit. |

**Is the commit atomic?** Yes at exactly one point: the HTS row UPDATE (single DB transaction, optimistic-locked). Everything else is write-ahead (metadata.json) or best-effort after-effects. There is no 2-phase coordination between the file write and the row update; the design instead makes the file write idempotent-by-uniqueness and meaningless until referenced. The dangerous residue is limited to (a) orphan metadata files, (b) `CommitStateUnknown` ambiguity for the client, (c) the S6 in-place rewrite.

**Special paths:**
- **stage-create / stage-replace (WAP)**: metadata.json is written but **HTS is never updated** (`:412-419`); `commit()` override suppresses the forced refresh (`:212-222`). The "table" exists only as a file until a later real commit. `TablesServiceImpl.putTable:120-123` treats a *persisted* staged table as an illegal state (500).
- **rename commit**: `doCommit` routes to `houseTableRepository.rename` (`:386-400`) → HTS `renameTableId` JPQL update (`UserTableHtsJdbcRepository.java:115-125`) — a direct UPDATE that neither checks nor bumps `@Version` (see smells).
- **drop**: `OpenHouseInternalCatalog.dropTable` (`OpenHouseInternalCatalog.java:157-192`) deletes the HTS row first, then purges files; crash between ⇒ orphaned data directory (leak, not corruption).
- **IOException during doCommit** (`:424-437`): runs `checkCommitStatus`, then tries `houseTableRepository.delete(houseTable)` — but `HouseTableRepositoryImpl.delete(entity)` **throws `UnsupportedOperationException`** (`HouseTableRepositoryImpl.java:319-322`), which is not in the catch list at `:429-431`, so it would mask the `CommitFailedException(ioe)` and surface as a generic 500. See smells.

---

## 5. Snapshot handling path (background for the bug hunt)

Client → server payload:
1. Engine produces new `TableMetadata` (snapshots appended/expired, refs moved). `OpenHouseTableOperations.areSnapshotsUpdated` (`OpenHouseTableOperations.java:343-349`) compares `base.snapshots()`/`base.refs()` with the new metadata.
2. `commitSnapshots` (`:364-391`) serializes the **full final snapshot list** (`SnapshotParser.toJson` each) and **full refs map** (`SnapshotRefParser`) — i.e., the payload is *declarative absolute state*, not a delta — plus `baseTableVersion` and a nested `CreateUpdateTableRequestBody` (schema, props, policies…).
3. Tables service: `IcebergSnapshotsServiceImpl.putIcebergSnapshots` (`IcebergSnapshotsServiceImpl.java:36-110`) → `TablesMapper.toTableDto(dto, IcebergSnapshotsRequestBody)` (`TablesMapper.java:88-125`; `jsonSnapshots`/`snapshotRefs`/`baseTableVersion` mappings at `:91-95`) → `OpenHouseInternalRepositoryImpl.save`.
4. In `save`'s update branch, `doUpdateSnapshotsIfNeeded` (`OpenHouseInternalRepositoryImpl.java:696-708`) stashes the serialized lists into transaction properties `snapshotsJsonToBePut` / `snapshotsRefs` (`CatalogConstants.java:5-6`). For CTAS, `computePropsForTableCreation` does the same (`:565-573`). (So snapshots ride *inside table properties* through the Iceberg transaction machinery — a key design quirk.)
5. Server catalog `doCommit` reconciliation (`OpenHouseInternalTableOperations.java:298-354`):
   - pops the two properties (`:298-299`), parses via `SnapshotsUtil.parseSnapshots`/`parseSnapshotRefs` (`iceberg/openhouse/internalcatalog/.../SnapshotsUtil.java:33-47, 77-90`);
   - `TableMetadata.buildFrom(metadataToCommit)`; computes existing vs payload snapshot-ID sets (`:324-330`);
   - **adds** payload snapshots not already present (`builder.addSnapshot`, `:332-335`);
   - **removes** existing snapshots absent from the payload (`builder.removeSnapshots(toRemove)`, `:337-344`) — this is how snapshot expiration is expressed through the same endpoint, and equally how a **stale payload silently expires other writers' snapshots** if the base checks are bypassed (the bug class that `abortIfWriterBaseDivergedFromCatalog` at `:604-635` was added to close — commit `9407819` "fix(catalog): abort doCommit on stale-base divergence (#612)");
   - **syncs refs**: removes refs not in the payload, then `setRef` for each payload ref (`:346-351`) — WAP/staged snapshots arrive with no ref; branch pointers (e.g. `main`) are set explicitly;
   - `builder.build()` lets Iceberg 1.5's `TableMetadata.Builder` recompute `last-sequence-number`, snapshot log, `current-snapshot-id` (from refs); a stale sequence number surfaces as `ValidationException` → 409 via `isStaleSnapshotError` (`:670-675`).
   - Legacy classification constants (`APPENDED_SNAPSHOTS`, `STAGED_SNAPSHOTS`, `CHERRY_PICKED_SNAPSHOTS`, `DELETED_SNAPSHOTS`, `CatalogConstants.java:23-26`) are no longer referenced in main code — the older append/stage/cherry-pick classifier was replaced by this set-difference merge.
6. Data files/manifests referenced by those snapshots were already written by the engine directly to storage; the server never validates their existence — it trusts the serialized snapshot JSON (`parseSnapshots` takes `FileIO` but `parse` at `:45-47` never uses it).

---

## 6. Happy-path commit sequence (for a sequence diagram)

Actors: **ENG** (Spark/engine, Iceberg core), **OHC** (client `OpenHouseCatalog`/`OpenHouseTableOperations`), **TS** (Tables REST service: controller→handler→service→`OpenHouseInternalRepositoryImpl`), **ITO** (`OpenHouseInternalTableOperations` + internal catalog), **ST** (storage: HDFS/blob), **HTS** (House Tables REST service), **DB** (HTS JDBC database).

1. ENG: writer finishes data files → Iceberg builds new snapshot; data/manifest/manifest-list files written **directly to ST** by the engine.
2. ENG → OHC: `TableOperations.commit(base, newMetadata)` (Iceberg `BaseMetastoreTableOperations.commit`) → `OpenHouseTableOperations.doCommit` (`OpenHouseTableOperations.java:142`).
3. OHC → TS: `PUT /v1/databases/{db}/tables/{t}/iceberg/v2/snapshots` with `{baseTableVersion = base.metadataFileLocation(), jsonSnapshots[], snapshotRefs{}, createUpdateTableRequestBody}` (`:364-391`).
4. TS: `IcebergSnapshotsController.putSnapshots:48` → handler (`OpenHouseIcebergSnapshotsApiHandler:29`) → `IcebergSnapshotsServiceImpl.putIcebergSnapshots:36` — authz + lock checks; maps request → `TableDto` (`tableVersion = baseTableVersion`).
5. TS: `OpenHouseInternalRepositoryImpl.save:114` — `catalog.loadTable` triggers **ITO.doRefresh:108** → HTS `GET /hts/tables?databaseId&tableId` → DB `SELECT` → returns row (`metadataLocation`, `@Version`) → ITO reads metadata.json from **ST** (cached).
6. TS: `versionCheck:451` — request base == current `openhouse.tableLocation` ✔; stages snapshots/schema/props on an Iceberg transaction; sets `commitKey = baseTableVersion` (`:196`); forces `commit.num-retries=0` (`:201-207`); `transaction.commitTransaction():216`.
7. ITO `doCommit:253`: `abortIfWriterBaseDivergedFromCatalog:269` ✔ (commitKey == base) ; `failIfRetryUpdate:271` ✔ (first sighting); stamps `openhouse.tableVersion ← old location`, `openhouse.tableLocation ← new location` (`:274-278`); merges payload snapshots/refs into `TableMetadata` (`:314-354`).
8. ITO → ST: write `{tableLoc}/00042-{uuid}.metadata.json` (`TableMetadataParser.write`, `:361-366`); seed metadata cache (`:367`).
9. ITO → HTS: `PUT /hts/tables` with `UserTable{tableVersion = old location, metadataLocation = new location, ...}` (`HouseTableRepositoryImpl.save:152`).
10. HTS: `UserTablesServiceImpl.putUserTable:98` — DB `SELECT` row; `UserTableVersionMapper.toVersion:21` verifies `row.metadataLocation == request.tableVersion`, carries over JPA `@Version`.
11. HTS → DB: `UPDATE userTableRow SET metadataLocation = new, version = v+1 WHERE pk AND version = v` — **THE atomic commit point** (Hibernate optimistic lock). 1 row updated.
12. HTS → ITO: 200 OK with saved entity (201 if created). ITO `doCommit` returns; `commitStatus = SUCCESS` (`:423`).
13. ITO/TS: Iceberg `commit()` wrapper refreshes from HTS (now sees own commit); repo converts final metadata → `TableDto` (`convertToTableDto`, `InternalRepositoryUtils.java:101`).
14. TS → OHC: 200 OK `GetTableResponseBody{tableLocation = new metadata.json path, tableVersion = old path, ...}`.
15. OHC/ENG: client `commit()` triggers `doRefresh` (`OpenHouseTableOperations.java:97`) → `GET /v1/.../tables/{t}` → reads new metadata.json from ST → engine's table view advances. Commit complete.

Conflict variant: at step 11 the `UPDATE` matches 0 rows → `ObjectOptimisticLockingFailureException` → HTS 409 (steps 10–12 replaced) → ITO `HouseTableConcurrentUpdateException` → `CommitFailedException` → TS 409 (`EntityConcurrentModificationException`) → OHC `CommitFailedException` → ENG Iceberg retry loop: re-runs steps 2–15 from a fresh refresh (up to `commit.retry.num-retries`).

---

## 7. Correctness smells noticed en route (for the review agent; not deep-dived)

1. **`houseTableRepository.delete(houseTable)` in the `IOException` handler is a no-op landmine** (`OpenHouseInternalTableOperations.java:424-437`): production impl `HouseTableRepositoryImpl.delete(entity)` throws `UnsupportedOperationException` (`HouseTableRepositoryImpl.java:319-322`), which is not among the caught exception types (`:429-431`) → masks the original `CommitFailedException(ioe)` as a 500. Worse, if it *were* implemented, deleting the row after an IOException on an **update** commit would delete the whole table pointer, and `houseTable` may still be the empty `HouseTable.builder().build()` (`:264`) when the IOException predates mapping.
2. **`failIfRetryUpdate`'s dedup cache is per-JVM and pre-commit** (`:93-94`, `:648-654`): keys are burned *before* the commit succeeds, and the Guava cache isn't shared across Tables-service replicas — behind a load balancer the "retry detection" is best-effort, and a same-instance engine retry after a *transient* failure is spuriously 409'd. Also, `commitKey` is just the base metadata path: two *different* users committing from the same base get serialized by the cache rather than by the real CAS (fast-fail with a misleading "stale" message).
3. **In-place rewrite of committed metadata.json** for replicated-table create (`:420-422`, `MetadataUpdateUtils.java:46` `fs.create(path, true)`) — non-atomic overwrite after the HTS commit point; crash mid-write corrupts the committed pointer's target; also invalidates the just-seeded metadata cache entry silently (cache now disagrees with the file).
4. **HTS rename bypasses optimistic locking** (`UserTableHtsJdbcRepository.java:115-125`): direct JPQL `UPDATE` with no `@Version` check/bump and no CAS on `metadataLocation`; a rename racing a normal commit can clobber `metadataLocation` (rename writes the metadata location computed by its own doCommit) without either side detecting a conflict.
5. **HTS CAS compares paths as raw strings** (`UserTableVersionMapper.java:34`), while the two upstream checks normalize schemes (`getSchemeLessPath`, `versionCheck` at `OpenHouseInternalRepositoryImpl.java:456`; `Path.toUri().getPath()` at `OpenHouseInternalTableOperations.java:624-628`). Server always persists schemeless paths so this is consistent today, but any scheme drift (fs migration) makes the layers disagree.
6. **`processSchemas` swallows per-schema parse failures** (`OpenHouseInternalTableOperations.java:704-715`, `catch (Exception e) { log.error(...) }`) — a bad intermediate schema is silently skipped rather than failing the commit.
7. **Snapshot payload is trusted wholesale**: `SnapshotsUtil.parse` ignores its `FileIO` (`SnapshotsUtil.java:45-47`) — no existence/ownership validation of manifest lists; combined with the subtractive merge (`removeSnapshots` of anything absent from the payload, `:337-344`), any client that passes the base checks is authoritative over the entire snapshot set (the defense is *only* the base CAS trio).
8. **`IcebergSnapshotsServiceImpl` lacks the `CommitStateUnknownException` catch** that `TablesServiceImpl.saveTableDto:183-192` has → unknown-state on the snapshots path returns generic 500 instead of the typed 503, relying on the client's 500→CommitStateUnknown mapping.
9. **`UserTablesServiceImpl.putUserTable` catches Iceberg's `CommitFailedException`** (`UserTablesServiceImpl.java:112`) — leftover from the (unused-by-default) Iceberg-backed HTS repository; harmless but confusing cross-layer coupling.
10. **Non-transactional read-then-write in HTS `putUserTable`** (`:99-111`) is safe *only* because of the `@Version` column; the earlier string compare in `UserTableVersionMapper` can pass on a row that changes before `save` — correctness rests entirely on Hibernate's optimistic lock, worth keeping in mind when reviewing any change that removes/renames `@Version`.
