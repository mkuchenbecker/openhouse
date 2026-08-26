# Appendix C: Apache Iceberg's Native Commit Protocol

*All `path:line` references are into the apache/iceberg checkout at `/home/user/iceberg` (version `1.5.2.x` per `version.properties`; line numbers verified against this tree). Repo-relative paths are used throughout.*

---

## 1. The Core Protocol: TableOperations and Compare-and-Swap on Metadata Location

### 1.1 The contract

Iceberg's entire commit protocol is built on one tiny SPI, `TableOperations`:

- `current()` — the currently loaded metadata, no update check — `core/src/main/java/org/apache/iceberg/TableOperations.java:35`
- `refresh()` — reload metadata after checking the catalog for updates — `core/src/main/java/org/apache/iceberg/TableOperations.java:42`
- `commit(TableMetadata base, TableMetadata metadata)` — atomically replace `base` with `metadata` — `core/src/main/java/org/apache/iceberg/TableOperations.java:64`

The Javadoc on `commit` *is* the protocol contract (`TableOperations.java:44-63`):

1. "Implementations must check that the base metadata is current to avoid overwriting updates" — i.e., the swap must be conditional on the table still pointing at `base` (optimistic CAS).
2. "Once the atomic commit operation succeeds, implementations must not perform any operations that may fail" — nothing fallible after the atomic step, because a post-commit failure is indistinguishable from a commit failure.
3. Implementations **must throw `CommitStateUnknownException`** when success/failure cannot be determined (e.g. network partition after the request was sent), "because downstream users of this API need to know whether they can clean up the commit or not; if the state is unknown then it is not safe to remove any files. All other exceptions will be treated as if the commit has failed."

The strict-cleanup gate is also part of the SPI: `requireStrictCleanup()` defaults to `true`, meaning uncommitted metadata files are only cleaned up for exceptions marked `CleanableFailure` (`core/src/main/java/org/apache/iceberg/TableOperations.java:127-129`).

### 1.2 What the CAS token is

For metastore catalogs the compare-and-swap token is the **metadata file location string**. The catalog (HMS, Glue, JDBC, Nessie, ...) stores a single pointer property, `metadata_location` (`core/src/main/java/org/apache/iceberg/BaseMetastoreTableOperations.java:57`), and a commit is "swing the pointer from old metadata.json to new metadata.json iff it still equals the old one." Immutability of each metadata.json file plus atomicity of the pointer swap yields linearizable per-table commits.

### 1.3 BaseMetastoreTableOperations commit flow

`core/src/main/java/org/apache/iceberg/BaseMetastoreTableOperations.java`:

- `commit(base, metadata)` at `BaseMetastoreTableOperations.java:117-143`:
  - `:119-127` — **local staleness pre-check**: `if (base != current())` throw `CommitFailedException("Cannot commit: stale table metadata")` (identity comparison — the caller must have applied its changes onto the freshest refreshed metadata). If `base == null` while `current()` is non-null, the commit was a create racing an existing table → `AlreadyExistsException`.
  - `:129-132` — no-op short-circuit if `base == metadata`.
  - `:135` — `doCommit(base, metadata)` — subclass hook that performs write + CAS (`:145-147` throws `UnsupportedOperationException` by default).
  - `:136` — `deleteRemovedMetadataFiles(base, metadata)` — post-commit trimming of old metadata.json files.
  - `:137` — `requestRefresh()` — mark cached metadata stale so the next `current()` refreshes.
- `refresh()` at `:94-110` delegates to `doRefresh()` (`:112`), which implementations satisfy by reading the pointer from the catalog and calling `refreshFromMetadataLocation(...)` (`:175-224`), which retries the metadata.json read with exponential backoff (`:202-208`) and enforces a **table-UUID continuity check** on refresh (`:210-217`: `Preconditions.checkState(newUUID.equals(currentMetadata.uuid()))`).

### 1.4 How metadata.json is written (write-then-swap, versioned naming)

- `writeNewMetadataIfRequired(newTable, metadata)` at `BaseMetastoreTableOperations.java:157-161` — writes the new metadata file **before** any catalog call; the commit then only swaps the pointer.
- `writeNewMetadata(metadata, version)` at `:163-173` — writes to a fresh object; the comment at `:167-169` notes overwrite is safe "because the metadata location is always unique because it includes a UUID."
- File naming: `newTableMetadataFilePath` at `:373-380` — `String.format("%05d-%s%s", newVersion, UUID.randomUUID(), fileExtension)`, e.g. `00042-<uuid>.metadata.json`, placed under `<table-location>/metadata/` or `write.metadata.path` (`:226-234`). The leading zero-padded number is a monotonically increasing **version hint**, parsed back by `parseVersion` at `:389-403` (returns `-1` if unparsable — versioning is advisory, the pointer is authoritative).

Concrete `doCommit` (HiveTableOperations, `hive-metastore/src/main/java/org/apache/iceberg/hive/HiveTableOperations.java`):

- `:173` — write new metadata.json first (`writeNewMetadataIfRequired`).
- `:180-182` — acquire the HMS lock (used because HMS `alter_table` isn't a conditional put on all versions).
- `:208-214` — **the CAS check**: reload the HMS table, compare its `metadata_location` parameter against `base.metadataFileLocation()`; mismatch → `CommitFailedException("Cannot commit: Base metadata location '%s' is not same as the current table metadata location...")`.
- `:239-243` — `persistTable(...)` swaps the pointer (with `expected_parameter_value` verification when lockless); on success `commitStatus = SUCCESS`.
- `:244-251` — lost lock heartbeat → `commitStatus = UNKNOWN` + `CommitStateUnknownException`.
- `:261-293` — any other throwable: "Cannot tell if commit succeeded, attempting to reconnect and check" → `checkCommitStatus(...)`, then re-throw / `CommitStateUnknownException` per the result (`:286-293`).

### 1.5 Commit-status UNKNOWN handling

`checkCommitStatus(newMetadataLocation, config)` at `BaseMetastoreTableOperations.java:311-371` is the canonical disambiguation routine: it repeatedly `refresh()`es and declares SUCCESS iff the attempted metadata location **is the current location or appears in `previousFiles()`** — the history search handles the race where another writer already committed on top of ours (`:344-347`; rationale in the Javadoc `:300-310`). If it can never confirm, status stays `UNKNOWN` (`:363-370`), and the caller surfaces `CommitStateUnknownException` — whose message (`api/src/main/java/org/apache/iceberg/exceptions/CommitStateUnknownException.java:27-33`) explicitly warns: no files will be deleted, do not blindly retry ("Retrying an already successful operation will result in duplicate records").

### 1.6 Cleanup of orphaned metadata on failure

- Hive: `finally { cleanupMetadataAndUnlock(...) }` at `HiveTableOperations.java:306-308` → `HiveOperationsBase.cleanupMetadata` at `hive-metastore/src/main/java/org/apache/iceberg/hive/HiveOperationsBase.java:146-155`: **delete the just-written metadata.json only when `commitStatus == FAILURE`** — never on UNKNOWN.
- Old metadata trimming after successful commits: `deleteRemovedMetadataFiles` at `BaseMetastoreTableOperations.java:412-439`, gated on `write.metadata.delete-after-commit.enabled`; the retained window is governed by `write.metadata.previous-versions-max` (default **100**, `core/src/main/java/org/apache/iceberg/TableProperties.java:274-276`).

---

## 2. The Retry Loop: Re-Apply, Never Re-Write

This is the piece naive protocols get wrong. When an Iceberg commit conflicts, the client does **not** retry pushing the same (now stale) metadata. Every operation (`AppendFiles`, `RewriteFiles`, `UpdateSchema`, ...) is a `PendingUpdate` (`api/src/main/java/org/apache/iceberg/PendingUpdate.java:30-55`) whose `apply()` **re-derives** the new metadata from the freshest base on every attempt.

### 2.1 SnapshotProducer.commit()

`core/src/main/java/org/apache/iceberg/SnapshotProducer.java:366-419`:

```java
Tasks.foreach(ops)
    .retry(base.propertyAsInt(COMMIT_NUM_RETRIES, ...))          // :374  (commit.retry.num-retries, default 4)
    .exponentialBackoff(...)                                     // :375-379
    .onlyRetryOn(CommitFailedException.class)                    // :380  ← ONLY conflicts are retried
    .run(taskOps -> {
        Snapshot newSnapshot = apply();                          // :384  ← re-applies changes on refreshed base
        TableMetadata.Builder update = TableMetadata.buildFrom(base);  // :386
        ... update.setBranchSnapshot(newSnapshot, targetBranch); // :389-394
        TableMetadata updated = update.build();                  // :396
        if (updated.changes().isEmpty()) return;                 // :397-402  no-op guard
        taskOps.commit(base, updated.withUUID());                // :408  ← CAS attempt
    });
```

The key line is inside `apply()` at `SnapshotProducer.java:226-234`: **`refresh()` first** (`:227` → `:361-364`, `this.base = ops.refresh()`), then `validate(base, parentSnapshot)` (`:233`) re-runs conflict validation (e.g. serializable-isolation checks) against the *new* base, and the abstract `apply(TableMetadata, Snapshot)` (`:223`) rebuilds the manifest list for the new parent. So each retry produces a *new snapshot object rooted at the winner's snapshot*, with a fresh sequence number (`:230`, `base.nextSequenceNumber()`). Operations are semantic ("add these files", "delete files matching this filter"), so re-application on a changed base is well-defined; when it isn't, `validate` throws `ValidationException` and the commit aborts rather than clobbering.

### 2.2 Tasks retry machinery

`core/src/main/java/org/apache/iceberg/util/Tasks.java`:
- `retry(n)` sets `maxAttempts = n + 1` (`Tasks.java:162-165`); `onlyRetryOn(...)` (`:167-181`); `exponentialBackoff(...)` (`:183`).
- The actual loop, `runTaskWithRetry`, at `Tasks.java:403-466`: rethrow once `attempt >= maxAttempts` or total duration exceeded (`:418-423`); retry only if the exception matches `onlyRetryOn` (`:430-441`); sleep `min(minSleep * scale^(attempt-1), maxSleep)` plus 10% jitter (`:452-459`).

### 2.3 Exception taxonomy

| Exception | Meaning | Retried? | Cleanup? |
|---|---|---|---|
| `CommitFailedException` — `api/.../exceptions/CommitFailedException.java:24` ("commit fails because of out of date metadata"); implements `CleanableFailure` | CAS lost / requirement failed | Yes (`SnapshotProducer.java:380`) | yes, once retries exhausted |
| `CleanableFailure` (marker) — `api/.../exceptions/CleanableFailure.java:25` | "state is known to be failure and uncommitted metadata can be cleaned up" | n/a | yes |
| `ValidationException` — `api/.../exceptions/ValidationException.java:35` (also `CleanableFailure`) | changes cannot legally apply to current metadata (conflict under the chosen isolation level, or illegal update) | No | yes |
| `CommitStateUnknownException` — `api/.../exceptions/CommitStateUnknownException.java:25` | outcome unknown | No | **never** |

The cleanup decision is exactly `SnapshotProducer.java:411-419`: `CommitStateUnknownException` is re-thrown untouched (no `cleanAll`); otherwise `cleanAll()` (delete staged manifest lists/manifests, `:481-487`) runs only when `!strictCleanup || e instanceof CleanableFailure`. After a *successful* commit, `:421-439` refreshes, finds the committed snapshot by id, and deletes manifests/manifest lists staged by earlier failed attempts that did not make it into the final snapshot — and deliberately **skips cleanup if the committed snapshot cannot be loaded back** (`:435-438`).

---

## 3. TableMetadata Internals Relevant to Commits

`core/src/main/java/org/apache/iceberg/TableMetadata.java` — TableMetadata is immutable; the only way to mutate is `TableMetadata.buildFrom(base)` (`TableMetadata.java:856-858`) / `buildFromEmpty()` (`:860`), producing a `Builder` (`:864`).

### 3.1 Change tracking: MetadataUpdate

Every builder mutation appends a typed change record: `private final List<MetadataUpdate> changes` (`TableMetadata.java:892`, exposed via `changes()` at `:555-557`). Examples: `addSnapshot` appends `MetadataUpdate.AddSnapshot` (`:1179`), `setRef` appends `MetadataUpdate.SetSnapshotRef` (`:1235-1243`), `setProperties` (`:1377-1385`), `removeSnapshots` → `MetadataUpdate.RemoveSnapshot` per id (`:1345-1375`).

`MetadataUpdate` (`core/src/main/java/org/apache/iceberg/MetadataUpdate.java:30`) is a serializable command object with `applyTo(TableMetadata.Builder)` (`:31-34`) — i.e. each change knows how to **re-apply itself to any base**. Concrete classes: `AssignUUID` (`:41`), `UpgradeFormatVersion` (`:63`), `AddSchema` (`:85`), `SetCurrentSchema` (`:113`), `AddPartitionSpec` (`:130`), `AddSortOrder` (`:168`), `SetStatistics` (`:206`), `AddSnapshot` (`:284`), `RemoveSnapshot` (`:301`), `RemoveSnapshotRef` (`:318`), `SetSnapshotRef` (`:335`), `SetProperties` (`:394`), `SetLocation` (`:438`), etc. **This list is exactly the wire vocabulary of the REST commit protocol** (§4).

### 3.2 Sequence numbers, snapshot log, metadata log

- `lastSequenceNumber` field (`TableMetadata.java:242`), accessor `:401-403`, and `nextSequenceNumber()` at `:405-407` (`lastSequenceNumber + 1` for v2+). `Builder.addSnapshot` enforces monotonicity — "Cannot add snapshot with sequence number %s older than last sequence number %s" (`:1167-1173`) — and advances it (`:1176`).
- Snapshot history: `snapshotLog` (`:257`, accessor `:547-549`); `setRef("main", ...)` appends a `SnapshotLogEntry` (`:1225-1232`). On `build()`, `updateSnapshotLog` (`:1737-1775`) rewrites the log: intermediate snapshots from multi-attempt commits are dropped, and **removing any snapshot referenced by history truncates all earlier history** to avoid time-travel gaps (comment at `:1761-1766`).
- Metadata-file history: `previousFiles` (`:258`); `build()` (`:1428-1488`) calls `addPreviousFile` (`:1670-1698`) which appends the predecessor metadata.json location and trims to `write.metadata.previous-versions-max`.

### 3.3 Ref-based (branch) commits

Refs are first-class: `refs` map of name → `SnapshotRef` in TableMetadata; `SnapshotProducer` targets a branch via `targetBranch` (default `main`, `SnapshotProducer.java:103`; setter `:161-172`). Commit path: `setBranchSnapshot(snapshot, branch)` = `addSnapshot` + set ref (`TableMetadata.java:1189-1193`); `setRef` (`:1211-1245`) updates `currentSnapshotId`/snapshot log only for `main`; `removeRef` (`:1247-1259`). Stage-only commits (`stageOnly`, `SnapshotProducer.java:99,390-391`) add the snapshot without moving any ref (WAP).

### 3.4 Snapshot addition/expiry

- Addition: `Builder.addSnapshot` (`TableMetadata.java:1149-1182`).
- Expiry: `RemoveSnapshots` (`core/src/main/java/org/apache/iceberg/RemoveSnapshots.java:60`) computes retained refs/snapshots and calls `updatedMetaBuilder.removeSnapshots(idsToRemove)` in `internalApply` (`RemoveSnapshots.java:208`); its `commit()` uses the same `Tasks`/`onlyRetryOn(CommitFailedException)` loop (`:295-309`), then deletes now-unreachable data/manifest files **after** the successful metadata commit (`cleanExpiredSnapshots`, `:312, :321`). File deletion is always post-commit and best-effort — failure leaves orphans, never corruption.

---

## 4. The REST Catalog Protocol (key for the OpenHouse proposal)

The REST protocol changes *what is shipped* on commit: not a metadata.json pointer, but **(requirements, updates)** — the assertions plus the semantic change list of §3.1. The server owns validation, the retry loop, and the metadata.json write.

### 4.1 The endpoint

`open-api/rest-catalog-open-api.yaml`:

- Route: `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}` — path at `rest-catalog-open-api.yaml:592`, `post` with `operationId: updateTable` at `:659-663`.
- Spec prose (`:664-681`): "Commits have two parts, requirements and updates. Requirements are assertions that will be validated before attempting to make and commit changes... after asserting that the current main ref is at the expected snapshot, a commit may add a new child snapshot and set the ref to the new snapshot id." Staged creates commit through the same route with `assert-create`.
- `CommitTableRequest` at `:2772-2788`: required `requirements: [TableRequirement]` + `updates: [TableUpdate]` (plus `identifier` for multi-table transactions).
- `TableUpdate` at `:2535-2551` / `BaseUpdate` discriminator at `:2208-2238`: `assign-uuid`, `upgrade-format-version`, `add-schema`, `set-current-schema`, `add-spec`, `set-default-spec`, `add-sort-order`, `set-default-sort-order`, `add-snapshot`, `set-snapshot-ref`, `remove-snapshots`, `remove-snapshot-ref`, `set-location`, `set-properties`, `remove-properties`, `set-statistics`, ... — a 1:1 JSON rendering of `MetadataUpdate`.
- `CommitTableResponse` at `:3294-3303`: **`metadata-location` + full `metadata`** — the server tells the client which metadata.json now stands; the client never computes it.
- Multi-table transactions: `POST /v1/{prefix}/transactions/commit` at `:953` with `CommitTransactionRequest` (`:2807-2816`, list of `CommitTableRequest`s).

### 4.2 Requirement types and what each protects

Schema at `rest-catalog-open-api.yaml:2566-2704`; server-side implementations in `core/src/main/java/org/apache/iceberg/UpdateRequirement.java` (each throws `CommitFailedException` on violation):

| Wire type (yaml) | Impl (`UpdateRequirement.java`) | Protects against |
|---|---|---|
| `assert-create` (`:2585`) | `AssertTableDoesNotExist` (`:38-47`) | concurrent create of the same table |
| `assert-table-uuid` (`:2597`) | `AssertTableUUID` (`:49-68`) | table dropped & re-created under the same name (ABA on identity) |
| `assert-ref-snapshot-id` (`:2611`) | `AssertRefSnapshotID` (`:91-127`) | **lost updates on a branch/tag**: named ref must still point at the expected snapshot; `snapshot-id: null` asserts the ref does not exist yet (`:113-116`) |
| `assert-last-assigned-field-id` (`:2631`) | `AssertLastAssignedFieldId` (`:129-148`) | concurrent schema addition reusing/racing column ids |
| `assert-current-schema-id` (`:2646`) | `AssertCurrentSchemaID` (`:150-169`) | concurrent change of the current schema |
| `assert-last-assigned-partition-id` (`:2661`) | `AssertLastAssignedPartitionId` (`:171-190`) | concurrent partition-spec addition racing field ids |
| `assert-default-spec-id` (`:2676`) | `AssertDefaultSpecID` (`:192-211`) | concurrent default-spec change |
| `assert-default-sort-order-id` (`:2691`) | `AssertDefaultSortOrderID` (`:213-232`) | concurrent default-sort-order change |

**Requirements are per-change-scoped, not whole-table**: `UpdateRequirements.forUpdateTable` (`core/src/main/java/org/apache/iceberg/UpdateRequirements.java:50-58`) always adds `AssertTableUUID`, then derives further requirements *only from the updates present* (`Builder.update`, `:92-111`): a `SetSnapshotRef` adds `AssertRefSnapshotID` for that ref only (`:113-125`); `AddSchema` adds `AssertLastAssignedFieldId` (`:127-134`); etc. Consequence: **two appends to different branches, or an append and a property change, don't conflict at the protocol level** — the server serializes them by re-validating and rebuilding metadata. This is finer-grained than a single "metadata location must equal X" CAS.

### 4.3 Client side: RESTTableOperations / RESTSessionCatalog

`core/src/main/java/org/apache/iceberg/rest/RESTTableOperations.java`:

- `commit(base, metadata)` at `:105-158`. For a plain update (`SIMPLE`, `:134-139`): `updates = metadata.changes()` and `requirements = UpdateRequirements.forUpdateTable(base, updates)`; CREATE/REPLACE variants at `:110-132` prepend staged create changes and use `forCreateTable`/`forReplaceTable`. It then builds `UpdateTableRequest` (`:146`) and `client.post(path, request, LoadTableResponse.class, headers, errorHandler)` (`:151-152`). **Note what is absent: the client never writes a metadata file and never computes a metadata location.** Comment at `:148-150`: "the error handler will throw necessary exceptions like CommitFailedException and UnknownCommitStateException."
- The response's metadata becomes the new current (`updateCurrentMetadata`, `:165-175`) — the post-commit refresh comes for free in the response.
- `refresh()` is `GET` on the same resource (`:99-102`).
- Ops are constructed in `RESTSessionCatalog.loadTable` at `core/src/main/java/org/apache/iceberg/rest/RESTSessionCatalog.java:389-395` with path `v1/{prefix}/namespaces/{ns}/tables/{table}` (`core/src/main/java/org/apache/iceberg/rest/ResourcePaths.java:64`); multi-table `commitTransaction` at `RESTSessionCatalog.java:1028-1043`.
- The client-side SnapshotProducer retry loop (§2) still runs above RESTTableOperations: on a 409 the client refreshes, re-applies, and sends a *new* (requirements, updates) pair.

### 4.4 Server side: CatalogHandlers.commit — the server is the single writer

`core/src/main/java/org/apache/iceberg/rest/CatalogHandlers.java`:

- `updateTable(catalog, ident, request)` at `:283-309` — create-transaction requests (detected by `AssertTableDoesNotExist`, `isCreate` `:315-330`) go to `create(...)` (`:332-343`, "create transactions do not retry. if the table exists, retrying is not a solution"); everything else loads the table and calls `commit(ops, request)` (`:299-302`).
- **`commit(ops, request)` at `:345-388` — the heart of the proposal-relevant flow:**
  ```java
  Tasks.foreach(ops)
      .retry(COMMIT_NUM_RETRIES_DEFAULT) ... .onlyRetryOn(CommitFailedException.class)   // :348-355
      .run(taskOps -> {
          TableMetadata base = isRetry.get() ? taskOps.refresh() : taskOps.current();     // :358
          try {
            request.requirements().forEach(requirement -> requirement.validate(base));    // :363
          } catch (CommitFailedException e) {
            throw new ValidationFailureException(e);                                      // :366  → no retry, back to client as 409
          }
          TableMetadata.Builder metadataBuilder = TableMetadata.buildFrom(base);          // :370
          request.updates().forEach(update -> update.applyTo(metadataBuilder));           // :371  ← server re-applies updates
          TableMetadata updated = metadataBuilder.build();                                // :373
          if (updated.changes().isEmpty()) return;                                        // :374-377
          taskOps.commit(base, updated);                                                  // :380  ← server writes metadata.json + CAS
      });
  ```
  Two distinct conflict classes are separated here (Javadoc `:86-94`, class `ValidationFailureException` `:95-106`):
  - **Client-visible conflict**: a *requirement* fails → wrapped so the server-side loop does **not** retry (`:364-367`, unwrap+rethrow at `:383-385`) → HTTP 409 → the *client* must refresh/reapply.
  - **Server-internal conflict**: the requirement held, but the backing store's CAS lost to a concurrent writer (`taskOps.commit` threw `CommitFailedException`) → the *server* refreshes (`:358`) , re-validates, re-applies the updates on the new base, and retries — transparently to the client.
  - `taskOps.commit(base, updated)` at `:380` is the backend `TableOperations` (e.g. JDBC/HMS-backed): **it is the server process that runs `writeNewMetadataIfRequired` and the pointer swap** (§1.4). The client's storage credentials are never needed for metadata; the catalog service is the single writer of metadata.json.

### 4.5 Error codes

Spec, `updateTable` responses (`open-api/rest-catalog-open-api.yaml:687-772`):
- **409** — "Conflict - CommitFailedException, one or more requirements failed. The client may retry." (`:706-712`)
- **500 / 502 / 504** — commit state **unknown**; examples all carry `"type": "CommitStateUnknownException"` (`:715-758`). 503 is `ServiceUnavailable` (retryable, commit known not to have happened) — the deliberate asymmetry: 5xx that may have reached the commit path is UNKNOWN; 503 is not.

Client mapping, `core/src/main/java/org/apache/iceberg/rest/ErrorHandlers.java` `CommitErrorHandler` (`:80-99`): 404 → `NoSuchTableException`; **409 → `CommitFailedException`** (`:88-89`, which re-arms the client's retry loop); **500/502/504 → `CommitStateUnknownException(ServiceFailureException)`** (`:90-94`, which stops everything and forbids cleanup); other codes fall to `DefaultErrorHandler` (`:188-224`; 503 → `ServiceUnavailableException` at `:220-221`).

**Idempotency gap**: the protocol has no commit/request token. If the client gets a 504 but the server committed, retrying the same `(requirements, updates)` would double-apply (e.g. add a duplicate snapshot or fail `assert-ref-snapshot-id`), which is exactly why the client surfaces `CommitStateUnknownException` instead of retrying (`ErrorHandlers.java:90-94`; `CommitStateUnknownException.java:27-33`). A server implementation *can* close this gap (e.g. detect an already-present snapshot id), but the spec does not require it.

---

## 5. Behavior / Guarantee Comparison Data

| Property | Metastore-pointer commit (§1) | REST catalog commit (§4) |
|---|---|---|
| Atomicity primitive | CAS on `metadata_location` pointer in catalog (`HiveTableOperations.java:208-214`) or atomic file rename (`HadoopTableOperations.java:161-162`) | Catalog service's internal CAS (`CatalogHandlers.java:380` → backend `doCommit`) |
| Linearizability | Per-table linearizable if the store's swap is truly conditional; whole-metadata granularity | Per-table linearizable at the server; **requirement-scoped granularity** (only asserted facts must hold) |
| Lost-update prevention | `base != current()` identity check (`BaseMetastoreTableOperations.java:119`) + pointer CAS | Explicit `UpdateRequirement` validation (`CatalogHandlers.java:363`; `UpdateRequirement.java:38-232`) |
| Conflict retry | Client-side only: refresh + re-apply (`SnapshotProducer.java:373-409`) | Two-level: server retries store-level races (`CatalogHandlers.java:348-381`); client retries requirement failures (409) |
| Who writes metadata.json | The **client/engine process** (`writeNewMetadataIfRequired`, `BaseMetastoreTableOperations.java:157-173`) — every writer needs storage write creds for `<table>/metadata/` | The **catalog service** (`CatalogHandlers.java:370-380`); response returns `metadata-location` (`yaml:3294-3303`) |
| Unknown-outcome handling | `checkCommitStatus` probe of current + history (`BaseMetastoreTableOperations.java:311-371`); else `CommitStateUnknownException` | HTTP 500/502/504 ≙ `CommitStateUnknownException` (`ErrorHandlers.java:90-94`); no retry, no cleanup |
| Idempotency | None — retry of an ambiguous commit can double-apply; mitigated only by status probe | Same gap (no request token in `CommitTableRequest`, `yaml:2772-2788`); server may dedupe but isn't required to |
| metadata.json growth | One new file per commit (`%05d-<uuid>.metadata.json`, `BaseMetastoreTableOperations.java:373-380`); in-file `metadata-log` capped at `write.metadata.previous-versions-max` = 100 (`TableProperties.java:274-276`); old files deleted only if `write.metadata.delete-after-commit.enabled` (`BaseMetastoreTableOperations.java:412-439`, default off) | Same file mechanics, but centralized: the service can enforce trimming/compaction policy uniformly |
| Snapshot expiry | Client-run `ExpireSnapshots` commit (`RemoveSnapshots.java:295-309`) + post-commit file deletes (`:312-321`) | Same operation expressed as `remove-snapshots` updates over REST (`yaml:2222`); file cleanup still a data-plane job |
| Orphan files | Failed-attempt manifests cleaned only on `CleanableFailure` (`SnapshotProducer.java:413-416`); UNKNOWN outcomes intentionally leave orphans for `RemoveOrphanFiles` | Client writes only data/manifests; metadata orphans confined to the server; same manifest-orphan story on aborted client attempts |
| Multi-table transactions | Not possible (one pointer per table) | `POST /v1/{prefix}/transactions/commit` (`yaml:953`, `:2807-2816`; client: `RESTSessionCatalog.java:1028-1043`) — atomicity is the server's responsibility |

---

## 6. Step-Numbered Commit Sequences (for sequence diagrams)

### (a) HadoopCatalog / metastore-style commit (client writes metadata.json)

Actors: **Engine** (SnapshotProducer + TableOperations), **Storage** (FS/S3), **Catalog** (HMS pointer; for HadoopCatalog, storage doubles as catalog).

Happy path:
1. Engine: `PendingUpdate.commit()` enters retry loop — `SnapshotProducer.java:373-382`.
2. Engine → Catalog/Storage: `refresh()` current metadata (`SnapshotProducer.java:227,361-364`; Hadoop: read `version-hint.text` + `vN.metadata.json`, `HadoopTableOperations.java:104-127`).
3. Engine: `validate(base, parent)` + re-apply operation; write manifests + manifest list to Storage — `SnapshotProducer.java:233-271`.
4. Engine: build new `TableMetadata` from base (`buildFrom` + `setBranchSnapshot`) — `SnapshotProducer.java:386-396`.
5. Engine → Storage: write new metadata.json to a unique temp/versioned path — Hadoop: `HadoopTableOperations.java:154-155`; metastore: `BaseMetastoreTableOperations.java:157-173` (step happens inside 6's `doCommit`).
6. Engine → Catalog: **atomic swap** — Hadoop: `renameToFinal(temp → v(N+1).metadata.json)` (`HadoopTableOperations.java:161-162, 361-385`); Hive: lock + verify `metadata_location == base` + `alter_table` (`HiveTableOperations.java:180-243`).
7. Engine: post-commit — write version hint (`HadoopTableOperations.java:167`), delete aged metadata (`BaseMetastoreTableOperations.java:136`), refresh + clean unused attempt artifacts (`SnapshotProducer.java:421-439`).

Conflict path (replaces 6):
6c. Swap fails because pointer/vN moved → `CommitFailedException` (`HadoopTableOperations.java:364-369`; `HiveTableOperations.java:210-214`).
7c. Tasks loop catches (only) `CommitFailedException`, sleeps with backoff — `Tasks.java:430-459`.
8c. Next attempt **restarts at step 2**: refresh picks up winner's metadata; `apply()` re-validates and rebuilds the snapshot on the new parent with a new sequence number — `SnapshotProducer.java:226-234`. Stale metadata is never re-sent.
9c. Retries exhausted → `CommitFailedException` propagates; `cleanAll()` deletes staged manifests (CleanableFailure) — `SnapshotProducer.java:413-416`. Ambiguous error instead → `checkCommitStatus` (`BaseMetastoreTableOperations.java:311-371`); UNKNOWN → `CommitStateUnknownException`, no cleanup (`SnapshotProducer.java:411-412`).

### (b) REST catalog commit (server writes metadata.json)

Actors: **Engine** (SnapshotProducer + RESTTableOperations), **Storage**, **Catalog service** (CatalogHandlers + backend store).

Happy path:
1. Engine: `PendingUpdate.commit()` retry loop (same as (a) step 1) — `SnapshotProducer.java:373-382`.
2. Engine → Service: `GET /v1/{prefix}/namespaces/{ns}/tables/{t}` refresh — `RESTTableOperations.java:99-102`.
3. Engine: validate + re-apply; write manifests/manifest list to Storage (data plane unchanged) — `SnapshotProducer.java:233-271`.
4. Engine: build updated metadata; collect `metadata.changes()`; derive requirements — `RESTTableOperations.java:134-139`, `UpdateRequirements.java:50-58`.
5. Engine → Service: `POST .../tables/{t}` with `CommitTableRequest{requirements, updates}` — `RESTTableOperations.java:146-152`; yaml `:659-686`.
6. Service: load table, enter server retry loop — `CatalogHandlers.java:299-302, 348-356`.
7. Service: validate every requirement against current base — `CatalogHandlers.java:363`; `UpdateRequirement.java`.
8. Service: `buildFrom(base)`; `update.applyTo(builder)` for each update — `CatalogHandlers.java:370-373`.
9. Service → Storage: **write new metadata.json** and CAS the pointer in its backing store — `CatalogHandlers.java:380` → `BaseMetastoreTableOperations.java:117-143,157-173`.
10. Service → Engine: 200 with `metadata-location` + full metadata — yaml `:3294-3303`; engine adopts it as current — `RESTTableOperations.java:157,165-175`.

Conflict paths:
- **Server-internal race** (requirements hold, backend CAS loses): backend throws `CommitFailedException` → server loop refreshes (`CatalogHandlers.java:358`), re-validates (7), re-applies (8), retries (9). Client sees only a slower 200.
- **Requirement failure** (e.g. `assert-ref-snapshot-id` mismatch): wrapped in `ValidationFailureException` to skip server retries (`CatalogHandlers.java:363-367,383-385`) → **409** (yaml `:706-708`) → client maps to `CommitFailedException` (`ErrorHandlers.java:88-89`) → client loop (`SnapshotProducer.java:380`) restarts at step 2: refresh, re-apply, send *new* requirements+updates.
- **Ambiguous**: 500/502/504 → `CommitStateUnknownException` (`ErrorHandlers.java:90-94`; yaml `:715-758`); client stops, no cleanup, no retry.
- Retries exhausted → `CommitFailedException` out of the client loop; `cleanAll()` as in (a).

---

## Appendix: Implications for an OpenHouse-style protocol ("server stores version, client writes metadata.json")

1. **Writer locus.** In OpenHouse's model the client writes metadata.json and the server CAS-es a version; in REST-native, `CatalogHandlers.java:370-380` makes the *server* both applier and writer. That removes client storage-write access to `metadata/`, removes trust in client-constructed metadata (the server rebuilds it from typed updates and can reject illegal ones via `TableMetadata.Builder` validation, e.g. `TableMetadata.java:1155-1173`), and lets the server absorb store-level races without a client round-trip.
2. **Conflict semantics.** A single version-number CAS forces *any* concurrent pair into conflict. Requirements (`UpdateRequirements.java:92-174`) conflict only on what a change actually depends on, and the server's refresh+reapply loop (`CatalogHandlers.java:358-381`) commits logically-independent concurrent changes without bouncing them to clients.
3. **Retry correctness.** Iceberg never re-submits stale metadata: retries re-derive it (`SnapshotProducer.java:226-234` client-side; `CatalogHandlers.java:370-371` server-side). A protocol whose retry unit is "the metadata.json I already wrote" cannot do this — on conflict the client must rebuild the file from scratch, which is the classic naive-implementation bug the `MetadataUpdate.applyTo` design exists to prevent.
