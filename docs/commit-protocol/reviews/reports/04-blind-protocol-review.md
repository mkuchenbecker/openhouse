# Blind Protocol Review — OpenHouse Table Commit Path

Reviewer worldview: distributed-systems commit-protocol correctness.
Repo under review: `/home/user/openhouse` @ `2a9dac8` ("Sync fork with upstream linkedin/openhouse main (#31)").
Contract reference: `/home/user/iceberg` @ `d1603c8` (Apache Iceberg: `TableOperations.java`, `BaseMetastoreTableOperations.java`, `SnapshotProducer.java`, `open-api/rest-catalog-open-api.yaml`).

Note: `/home/user/openhouse/docs/commit-protocol/` appears to contain a prior protocol analysis. Per the blind-review charter I did **not** read it; everything below is from source.

---

## 1. My understanding of the protocol (independent reading)

1. Engines embed `OpenHouseTableOperations` (`integrations/java/iceberg-1.2/openhouse-java-runtime/.../OpenHouseTableOperations.java`), a `BaseMetastoreTableOperations` whose `doCommit` translates the metadata delta into REST calls: `PUT /tables` (metadata-only), `PUT /snapshots` (data commits), carrying `baseTableVersion` = the **metadata.json path of the base** the writer refreshed from.
2. The **version/CAS token is a metadata.json file path**, not a counter. The current pointer for table T lives in one row of the House Tables service (HTS) DB: `UserTableRow{databaseId, tableId, metadataLocation, @Version long}`.
3. The tables service (`OpenHouseInternalRepositoryImpl.save`) loads the table (from HTS pointer + metadata.json on storage), validates `baseTableVersion == currentLocation` (`versionCheck`, `OpenHouseInternalRepositoryImpl.java:451`), stamps the client base as `commitKey` property (`:196`), and commits an Iceberg transaction against `OpenHouseInternalTableOperations`.
4. `OpenHouseInternalTableOperations.doCommit` (server) rebuilds the metadata (schema, sort order, and a client-payload-driven **add/remove sync of snapshots and refs**), **writes the new metadata.json itself** to `<tableLocation>/<version>-<UUID>.metadata.json` (server writes metadata files; the engine never does), then updates the HTS row.
5. The **atomic commit point** is the HTS row write: `putUserTable` compares the row's `metadataLocation` to the writer's claimed base (`UserTableVersionMapper.toVersion`) and the subsequent JPA save is guarded by the `@Version` optimistic lock; losers surface as 409 → `HouseTableConcurrentUpdateException` → `CommitFailedException` → 409 to the engine.
6. Two auxiliary server-side guards run before the commit point: `abortIfWriterBaseDivergedFromCatalog` (compares `commitKey` vs the server-loaded base's file location, snapshot-bearing commits only) and `failIfRetryUpdate` (a **static in-JVM 5-minute Guava cache** of seen `commitKey`s intended to kill Iceberg-internal retries).
7. Everything before the HTS write is repeatable/orphanable (metadata.json files are UUID-unique); everything after it must not fail — but two post-commit-point steps exist (HTS `rename`, and an in-place metadata.json rewrite for replicated creates) and both can fail or corrupt.
8. Unknown outcomes: HTS 5xx/timeouts → `checkCommitStatus` re-probe → `CommitStateUnknownException` → 503 (tables path) or 500 (snapshots path); the OpenHouse engine client maps 409→`CommitFailedException` (cleanable), 500/503/504/network→`CommitStateUnknownException`.
9. Multi-instance deployments share only the HTS DB; the dedup cache and metadata cache are per-instance (metadata cache is keyed by immutable location, so it is safe; the dedup cache is not).
10. Rename and drop bypass the CAS entirely: rename is an unconditional JPQL `UPDATE` of the row; drop is find-then-delete + file purge.

---

## 2. Findings

Severity legend: **critical** = committed state can be lost/corrupted; **major** = protocol contract violated with plausible data-loss/duplicate-data consequences; **minor** = wrong status/hygiene/edge-case.

### F1 (critical) — Post-commit cleanup path deletes the committed HTS row and misreports the outcome as a clean commit failure

**Claim.** The `catch (IOException)` branch of `doCommit` runs *after* the atomic commit point, ignores the `checkCommitStatus` probe it just ran, attempts to delete the committed HTS row, and throws `CommitFailedException` — a `CleanableFailure` — for an operation that already committed.

**Evidence.** `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java:420-437`:

```java
      if (isReplicatedTableCreate(properties)) {
        updateMetadataFieldForTable(metadata, newMetadataLocation);   // throws IOException, runs AFTER save()
      }
      commitStatus = CommitStatus.SUCCESS;
    } catch (IOException ioe) {
      commitStatus = checkCommitStatus(newMetadataLocation, metadata); // result ignored
      // clean up the HTS entry
      try {
        houseTableRepository.delete(houseTable);                       // deletes COMMITTED row
      } catch (HouseTableCallerException | HouseTableNotFoundException | HouseTableConcurrentUpdateException e) { ... }
      throw new CommitFailedException(ioe);                            // "clean failure" for a committed txn
```

The only `IOException` producer inside the `try` is `updateMetadataFieldForTable` (`:804-821`, declared `throws IOException`), which executes **after** `houseTableRepository.save(houseTable)` (`:404`) succeeded — i.e., after the commit is durable. (`TableMetadataParser.write` failures are unchecked `RuntimeIOException`/`UncheckedIOException` and take the `Throwable` path instead.)

**Contract.** `core/src/main/java/org/apache/iceberg/TableOperations.java:50-60`: *"Once the atomic commit operation succeeds, implementations must not perform any operations that may fail because failure in this method cannot be distinguished from commit failure"*, and unknown outcomes must be `CommitStateUnknownException`. `CommitFailedException` is a `CleanableFailure` (`api/src/main/java/org/apache/iceberg/exceptions/CommitFailedException.java:24`), so `SnapshotProducer.commit` (`SnapshotProducer.java:414-415`) deletes the just-written manifests on receiving it.

**Failure scenario.** Replicated-table create: metadata.json written → HTS row saved (commit durable) → in-place metadata rewrite hits an HDFS hiccup → `IOException` → intended behavior: committed HTS row deleted (table vanishes; the replication controller believes the create failed cleanly and may re-create, orphaning the previous file set) and the client receives a cleanable failure for a commit that exists.

**Mitigating accident (see F8).** At HEAD, `HouseTableRepositoryImpl.delete(HouseTable)` unconditionally throws `UnsupportedOperationException` (`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImpl.java:320-322`), which is **not** in the inner catch list — so at runtime the row deletion never happens; instead `UnsupportedOperationException` escapes, replaces the intended `CommitFailedException`, and surfaces as HTTP 500 (generic handler). 500 → client `CommitStateUnknownException`, which is accidentally the least-bad outcome. The block as written is still wrong three ways (ignored probe result, row deletion, cleanable-failure signal), and any future implementation of `delete()` re-arms the data-loss path.

### F2 (critical) — Committed metadata.json is rewritten in place after the commit point (non-atomic overwrite of committed state)

**Claim.** For replicated-table creates, the server mutates the already-committed, already-pointed-to metadata.json via read-truncate-rewrite; a crash mid-rewrite leaves the table's current pointer referencing a truncated/corrupt file.

**Evidence.** `OpenHouseInternalTableOperations.java:420-422` calls `updateMetadataFieldForTable` → `MetadataUpdateUtils.updateMetadataField` (`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/utils/MetadataUpdateUtils.java:36-47`):

```java
      OutputStream outputStream = fs.create(new Path(hdfsPath), true);  // overwrite=true, same path
      writeOutputStream(outputStream, updatedJsonString);
```

This runs after `houseTableRepository.save()` made `newMetadataLocation` the table's current pointer, and after `tableMetadataCache.seed(newMetadataLocation, updatedMtDataRef)` (`:367`) cached the *pre-rewrite* bytes.

**Failure scenario.** (a) Crash/kill between `fs.create` (truncation) and write completion → the committed pointer references a zero-length/partial file → every subsequent `loadTable`/`doRefresh` on any instance throws → table bricked until manual repair. (b) No crash: the instance that committed serves the cached pre-rewrite `TableMetadata` for that location while other instances parse the rewritten file → same location, two different metadata contents — violating the immutability assumption that makes location-keyed caching and `checkCommitStatus` sound.

**Severity.** Critical (crash window corrupts committed state), confined to the replicated-create flow.

### F3 (critical) — HTS rename is an unconditional UPDATE: no CAS, no version bump → lost committed commits

**Claim.** `renameTableId` overwrites `metadataLocation` with no optimistic-lock predicate and without incrementing `@Version`, so a rename racing a (fully committed) snapshot commit silently discards that commit.

**Evidence.** `services/housetables/src/main/java/com/linkedin/openhouse/housetables/repository/impl/jdbc/UserTableHtsJdbcRepository.java:115-125`:

```java
  @Transactional
  @Modifying
  @Query(
      "UPDATE UserTableRow table SET table.tableId = :toTableId, table.metadataLocation = :metadataLocation, table.databaseId = :toDatabaseId "
          + "WHERE lower(table.databaseId) = lower(:fromDatabaseId) AND lower(table.tableId) = lower(:fromTableId)")
  void renameTableId(...)
```

No `metadataLocation = :expectedBase` / `version = :expectedVersion` predicate; JPQL bulk updates bypass the `@Version` machinery entirely. Caller `UserTablesServiceImpl.renameUserTable` (`services/housetables/.../services/UserTablesServiceImpl.java:140-167`) only does a non-atomic `existsById` pre-check. On the tables-service side, the rename commit (`OpenHouseInternalTableOperations.java:386-400`) writes a new metadata.json derived from the metadata loaded at `loadTable` time and then calls this unconditional UPDATE; `abortIfWriterBaseDivergedFromCatalog` explicitly skips rename commits (no `SNAPSHOTS_JSON_KEY`, `:610-616`), and no `COMMIT_KEY` is stamped by `OpenHouseInternalCatalog.renameTable` (`:229-243`).

**Failure scenario (concrete interleaving).**
1. t0 — rename request: server loads T at base L0, writes L1' (= L0's content + new tableId props).
2. t1 — writer W commits snapshots: HTS CAS passes (row still L0) → row = L1 (W's commit is durable, W told success).
3. t2 — rename's `renameTableId` runs: row unconditionally set to `tableId=to, metadataLocation=L1'`.
Result: W's committed snapshot (acknowledged!) is gone from the current pointer forever — a textbook lost update of *committed* state. The opposite ordering (rename first) is safe only by accident (writer's flush targets the old PK, gets `ObjectOptimisticLockingFailureException`).

**Severity.** Critical — acknowledged commits can be silently discarded; window is the full duration of a rename request (metadata load → file write → HTS call, seconds).

### F4 (major) — `failIfRetryUpdate` dedup cache: per-JVM, records attempts (not successes), and answers "retry of a maybe-successful commit" with a cleanable failure

**Claim.** The retry guard is unsound in all three directions: it doesn't work across instances, it false-positives against legitimate retries after clean failures, and when it does fire its answer (`CommitFailedException`) is the one answer that is never correct for a duplicate of a possibly-committed request.

**Evidence.** `OpenHouseInternalTableOperations.java:93-94, 642-664`:

```java
  private static final Cache<String, Integer> CACHE =
      CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(1000).build();
  ...
      if (CACHE.getIfPresent(userProvidedTblVer) != null) {
        throw new CommitFailedException(... "is stale, please consider retry from application" ...);
      } else {
        CACHE.put(userProvidedTblVer, 1);
      }
```

`failIfRetryUpdate` runs at `:271`, **before** the metadata write and the HTS save.

**Failure scenarios.**
- *(a) Poisoned clean-failure retry.* Commit from base L0 fails cleanly *after* the cache put (e.g. HTS 429 → `HouseTableCallerException` → `CommitFailedException`; catalog still at L0). Client refreshes — base unchanged — and retries with the same `commitKey=L0`; the same instance now rejects it as "stale" for up to 5 minutes even though the commit would be valid. Iceberg's `Tasks` retry loop burns all attempts against the poisoned key and the operation fails spuriously.
- *(b) Cross-instance bypass.* Behind a load balancer the duplicate/retry usually lands on another instance where the cache is empty, so the guard the design depends on (killing Iceberg-internal `PropertiesUpdate`/`BaseTransaction` re-commits — same JVM — is fine, but any HTTP-level duplicate is not caught).
- *(c) Wrong answer when it fires.* If attempt #1 actually committed (response lost) and the duplicate hits the same instance, the server replies 409 → client `CommitFailedException` → `SnapshotProducer` treats it as `CleanableFailure`: retry loop refreshes and **re-applies the same appended files onto the new base** (duplicate data), or on exhaustion `cleanAll()` deletes manifests — which, because attempt #1 committed, are *referenced by the current table metadata* (`SnapshotProducer.java:414-415`). The correct responses are "idempotent success" or `CommitStateUnknown`; never a cleanable failure.

**Severity.** Major (availability bug + participates in the duplicate/cleanup corruption path of F5).

### F5 (major) — No idempotency at the commit point: a retry of an already-committed request is indistinguishable from a genuine conflict, and is reported as one

**Claim.** The HTS CAS (`metadataLocation` string compare + `@Version`) has no memory of *which* request produced the current pointer, so any retry of a commit whose first attempt succeeded (after an ambiguous outcome: LB timeout, dropped response, HTS 5xx that actually landed) gets 409/`CommitFailedException` — the "clean loss, safe to clean up / re-apply" signal — for work that is durably committed.

**Evidence.** `services/housetables/.../dto/mapper/UserTableVersionMapper.java:33-45` (mismatch → `EntityConcurrentModificationException`, no comparison against `userTable.getMetadataLocation()` to detect "this exact write already landed"); `services/tables/.../services/IcebergSnapshotsServiceImpl.java:93-108` (`CommitFailedException` → 409); client `OpenHouseTableOperations.java:424-428` (409 → `CommitFailedException`, a `CleanableFailure`).

**Failure scenario.** Engine commit → server commits L0→L1 → response lost (client got `CommitStateUnknown`, keeps files, surfaces to application). Application-level retry re-runs the job or the engine's commit loop refreshes and re-applies: base now L1 which *includes* the appended snapshot; re-apply creates a second snapshot appending the same files → duplicate rows; alternatively a stale retry from L0 gets 409 and the engine's exhausted retry loop cleans "its" manifests, which L1 references. A commit-UUID (the unused `CatalogConstants.COMMIT_KEY` machinery is halfway there) checked against the row would close this. This gap is common to metastore catalogs, but OpenHouse's own message ("please consider retry from application", `:651`) actively invites the harmful pattern.

**Severity.** Major.

### F6 (major) — Unknown-outcome commits mapped to HTTP 503 ("safe to retry") on the tables path; 500 vs 503 inconsistency between the two commit endpoints

**Claim.** `OpenHouseCommitStateUnknownException` → **503 Service Unavailable** (`services/common/.../handler/OpenHouseExceptionHandler.java:146-159`), but in Iceberg REST-catalog convention 503 means "service unavailable, request not processed — retry safely", while ambiguous commit outcomes are 500/502/504 (`/home/user/iceberg/open-api/rest-catalog-open-api.yaml:715-758`: the `5XX` commit responses carry `"type": "CommitStateUnknownException"` for 500/502/504; 503 is the generic retryable error). Meanwhile the snapshots endpoint doesn't produce 503 at all: `IcebergSnapshotsServiceImpl.putIcebergSnapshots` (`:89-109`) catches only `BadRequestException` and `CommitFailedException`, so a `CommitStateUnknownException` from `doCommit` falls through to the generic handler → **500** (`OpenHouseExceptionHandler.java:403-415`), unlike `TablesServiceImpl.saveTableDto` (`:183-193`) which wraps it → 503.

**Failure scenario.** Any intermediary or non-OpenHouse client applying standard HTTP semantics (503 + `Retry-After` culture, idempotent-retry proxies) blind-retries a possibly-committed mutation → lands as F5's duplicate/409 scenario. OpenHouse's own Java client happens to map 503 → `CommitStateUnknownException` (`OpenHouseTableOperations.java:430-438`) so first-party engines are safe, but the wire contract is the opposite of the ecosystem convention, and the same logical condition returns different status codes depending on which endpoint the commit used.

**Severity.** Major (wire-contract), mitigated for first-party clients.

### F7 (major) — Snapshot/ref state is a subtractive merge from the client payload; refs can be wholesale-wiped by an omitted field

**Claim.** `doCommit` removes every server-side snapshot and ref not present in the client's payload; the only defenses are the base-CAS checks, and the refs half has a default that turns "field omitted" into "delete all branches/tags".

**Evidence.** `OpenHouseInternalTableOperations.java:298-353` (remove snapshots not in payload `:337-344`; remove refs not in payload `:346-349`), with `serializedSnapshotRefs == null → new HashMap<>()` (`:317-320`). On the tables-service side, `doUpdateSnapshotsIfNeeded` (`OpenHouseInternalRepositoryImpl.java:696-708`) sets `SNAPSHOTS_REFS_KEY` **only when the request's refs map is non-empty** — so a raw REST caller that PUTs `jsonSnapshots` without `snapshotRefs` (or with `{}`) commits a metadata version in which *all refs, including `main`, are removed* while the snapshots remain: the table reads as empty at HEAD.

Concurrent-lost-update exposure of the subtractive snapshot merge itself is largely closed by `abortIfWriterBaseDivergedFromCatalog` (`:604-635`) plus the HTS CAS: a stale payload can only commit if its `commitKey` matches the current base. Remaining unguarded entries into the merge: commits whose `commitKey` is absent by design (replace/stage-replace, `:620-621` comment) — acceptable per replace semantics — and any path that can spoof `openhouse.tableLocation` (see F12).

**Failure scenario.** A hand-rolled client (the API is public; the validator `IcebergSnapshotsApiValidatorImpl` does not require `snapshotRefs`) appends one snapshot without refs → server silently drops `main` → downstream readers see an empty table; history recoverable only via metadata-log archaeology.

**Severity.** Major (destructive default derived from client-supplied absence; no confirmation the client meant "delete all refs").

### F8 (major) — `HouseTableRepositoryImpl.delete(entity)` is `UnsupportedOperationException`, making `doCommit`'s IOException handler structurally dead/wrong at HEAD

**Claim.** The cleanup call site compiled against `delete(HouseTable)` can never succeed, and the exception it throws is not in the surrounding catch list, so the intended `CommitFailedException(ioe)` is unreachable whenever the delete is attempted.

**Evidence.** `HouseTableRepositoryImpl.java:319-322`:

```java
  @Override
  public void delete(HouseTable entity) {
    throw new UnsupportedOperationException("Entity deletion is not supported.");
  }
```

vs. `OpenHouseInternalTableOperations.java:427-436` catching only `HouseTableCallerException | HouseTableNotFoundException | HouseTableConcurrentUpdateException` around it.

**Failure scenario.** Any `IOException` in doCommit → `UnsupportedOperationException` escapes `doCommit` → generic 500. Untested/never-exercised cleanup path; behavior is whatever falls out of an unrelated exception type. (Interacts with F1: implementing `delete()` "to fix the UnsupportedOperationException" would activate F1's committed-row deletion.)

**Severity.** Major (dead error-path; masks F1).

### F9 (minor) — HTS client retries rename/delete on `IllegalStateException` with non-idempotent semantics and no visibility into first-attempt success

**Evidence.** `HouseTableRepositoryImpl.java:226-237` (`deleteById` retried on `IllegalStateException`), `:239-254` (`rename` retried; `.block()` without timeout so the ISE trigger mostly can't even fire, and an unbounded block hangs the request thread on a stalled connection).

**Failure scenario.** Delete/rename attempt #1 succeeds but the response is lost mid-retry-window; attempt #2 gets 404 → `HouseTableNotFoundException` propagates → caller reports failure for an operation that succeeded (drop path: `RuntimeException` out of `OpenHouseInternalCatalog.dropTable:173-178`; rename path inside doCommit: `HouseTableNotFoundException` → `CommitFailedException` at `:448-451` — a *cleanable* failure signal for a rename that landed).

### F10 (minor) — Concurrent drop during refresh surfaces as 500 `IllegalStateException` instead of a not-found/conflict

**Evidence.** `OpenHouseInternalTableOperations.java:126-130` throws `IllegalStateException("Cannot find table %s after refresh...")`; `OpenHouseExceptionHandler.java:316-329` maps `IllegalStateException` → 500. The engine-side ops throws `NoSuchTableException` in the analogous case (`OpenHouseTableOperations.java:120-123`).

**Failure scenario.** Writer refreshes while another principal drops the table → client sees 500 (which its error mapper treats as commit-state-unknown on the commit path) instead of a definitive 404 — the client then refuses to clean up files for a table that provably no longer exists.

### F11 (minor) — Tables-service TOCTOU between authorization/lock checks and the commit; lock enforcement not part of the CAS

**Evidence.** `IcebergSnapshotsServiceImpl.putIcebergSnapshots:41-88` reads the table once for lock/authz decisions; `OpenHouseInternalRepositoryImpl.save:179` loads it again for the commit; nothing re-validates the lock at the commit point.

**Failure scenario.** Lock (or ACL) is applied between the service-layer check and the HTS save → a write to a freshly locked table commits. Window is one request's duration; requires an interleaved policy change. The pointer CAS itself is unaffected (the lock change is itself a commit, which would advance the base and 409 the writer — *only if* the lock update goes through a metadata commit; ACL changes that don't touch the pointer are not defended).

### F12 (minor) — Replication/cross-cluster requests skip the eligibility checks that protect the CAS-token property

**Evidence.** `OpenHouseInternalRepositoryImpl.skipEligibilityCheck:288-312`: when the existing table is marked `openhouse.isTableReplicated=true` (or legacy replica→primary), `updateEligibilityCheck` — including `versionCheck` and `checkIfPreservedTblPropsModified` — is skipped entirely. The `openhouse.tableLocation` property, which `doCommit:274-278` turns into the HTS CAS token, is then not protected against being supplied by the request. `TableUUIDGenerator:150-164` similarly skips path validation of client-supplied UUIDs for `REPLICA_TABLE`/`isTableReplicated` requests.

**Failure scenario.** A caller with write privilege on a replicated table can carry forged preserved properties (including the location-bearing CAS inputs) through the eligibility layer; `abortIfWriterBaseDivergedFromCatalog` still guards snapshot-bearing commits via `commitKey`, but property-only commits on replicated tables ride on whatever `openhouse.tableLocation` the merged DTO carries. The flows are intended for a trusted replication service, so exploitability is bounded by deployment authz — but the invariant "the CAS token is always server-derived" does not hold on this path.

### F13 (minor) — `processSchemas` swallows schema-application failures and commits anyway

**Evidence.** `OpenHouseInternalTableOperations.java:704-715`: each intermediate-schema application is wrapped in `try/catch(Exception)` that only logs. A malformed intermediate schema silently drops part of the schema-evolution chain; the commit proceeds and the resulting metadata is served as committed truth.

### F14 (minor) — Drop is a non-atomic two-step (HTS row delete → file purge) and metadata-file GC runs post-commit

**Evidence.** `OpenHouseInternalCatalog.dropTable:157-192` deletes the HTS row first, then `deletePrefix` on the whole table dir; a crash between the two leaves orphaned files (safe ordering — the reverse would be worse — but there is no reconciliation job in this path). `BaseMetastoreTableOperations.commit:135-137` additionally calls `deleteRemovedMetadataFiles(base, metadata)` after `doCommit` using the *pre-transform* `metadata` object rather than the `metadataToCommit` actually persisted (`doCommit` rebuilds it at `:307-353`), so when `write.metadata.delete-after-commit.enabled` is on, the deletion bookkeeping and the persisted previous-files log come from two different metadata objects. Divergence is unlikely but unproven.

### F15 (note, no defect) — What actually holds the protocol together

For the mainline snapshot/metadata commit path the design is sound: `versionCheck` (fast-fail), `abortIfWriterBaseDivergedFromCatalog` (stale-base defense for the subtractive merge, including rebased-retry variants), and the HTS location-compare + `@Version` optimistic lock as the single atomic arbiter. Orphaned metadata.json files from losing committers are tolerated garbage (UUID-unique names). The `checkCommitStatus` probe (SUCCESS-or-UNKNOWN only in this Iceberg line) is correctly used in the `Throwable` path (`:452-476`): SUCCESS → swallow, UNKNOWN → `CommitStateUnknownException` — this is contract-conformant. `COMMIT_NUM_RETRIES=0` suppression of server-side transaction retries depends on the LinkedIn iceberg-core fork (`buildSrc/.../openhouse.iceberg-conventions-1.2.gradle:8`), but the two guards above cover stock-retry behavior anyway (same-JVM retries hit the dedup cache; snapshot rebases hit the base-divergence abort). The location-keyed metadata cache is safe because metadata files are immutable — except where F2 breaks exactly that assumption.

---

## 3. Summary counts

- Critical: 3 (F1, F2, F3)
- Major: 5 (F4, F5, F6, F7, F8)
- Minor: 6 (F9, F10, F11, F12, F13, F14) + contract notes (F15)
