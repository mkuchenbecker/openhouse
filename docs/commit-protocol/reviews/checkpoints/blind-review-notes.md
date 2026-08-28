# Blind review notes — OpenHouse commit protocol (HEAD 2a9dac8)

## Protocol understanding
- CAS token = previous metadata.json path ("tableVersion"). Atomic commit point = HTS putUserTable:
  UserTableVersionMapper.toVersion (string compare metadataLocation vs client tableVersion) + JPA @Version
  optimistic lock on UserTableRow at save() flush.
- Tables-service writes metadata.json (server-side, UUID-suffixed name) BEFORE HTS save; HTS row is pointer.
- Client (OpenHouseTableOperations in integrations/java) sends baseTableVersion; server versionCheck (repo layer),
  abortIfWriterBaseDivergedFromCatalog (COMMIT_KEY vs base.metadataFileLocation), CACHE dedup, then HTS CAS.

## Confirmed findings
1. CRITICAL (intent) / MAJOR (actual): doCommit catch(IOException) — post-commit-point failure path
   (updateMetadataFieldForTable, replicated create, runs AFTER houseTableRepository.save succeeded) →
   checkCommitStatus result IGNORED; deletes committed HTS row; throws CommitFailedException (cleanable!).
   At HEAD houseTableRepository.delete(entity) throws UnsupportedOperationException (HouseTableRepositoryImpl:320)
   so the block dies with UOE → 500. Violates TableOperations.java:50 ("must not perform operations that may
   fail after atomic commit succeeds"). OpenHouseInternalTableOperations.java:424-437, 420-422, 804-821.
2. CRITICAL: MetadataUpdateUtils.updateMetadataField rewrites committed metadata.json IN PLACE
   (fs.create overwrite) after HTS pointer set → crash mid-rewrite corrupts committed metadata; cache seeded
   with pre-rewrite content diverges. MetadataUpdateUtils.java:36-57; OpenHouseInternalTableOperations.java:420-422,367.
3. CRITICAL: HTS renameTableId bulk JPQL UPDATE has no version predicate, no @Version bump, unconditional
   metadataLocation overwrite → concurrent snapshot commit (already durable via CAS) silently lost when rename
   lands second. UserTableHtsJdbcRepository.java:115-125; rename path OpenHouseInternalTableOperations.java:386-400;
   UserTablesServiceImpl.java:140-167 (existsById check-then-act only).
4. MAJOR: failIfRetryUpdate dedup = static in-JVM Guava cache (5min TTL, 1000 entries), keyed on COMMIT_KEY;
   (a) useless across LB instances; (b) CACHE.put happens BEFORE commit attempt → clean failure (e.g. HTS 5xx→
   fail, or IOException) poisons key: legitimate client retry from still-current base falsely 409'd for 5 min
   on that instance; (c) when it DOES catch a retry whose first attempt succeeded, it answers CommitFailedException
   (409→client CommitFailedException=CleanableFailure → cleanAll deletes manifests of a COMMITTED snapshot →
   corruption) instead of success/unknown. OpenHouseInternalTableOperations.java:93-94,642-664.
5. MAJOR: no server-side idempotency for ambiguous outcomes: retry of a commit that actually landed gets 409
   (HTS location mismatch) = "clean failure" signal; engine retry loop re-applies snapshot payload on refreshed
   base → duplicate appends, or cleanAll. Systemic (HTS CAS is location-compare only).
6. MAJOR: 503 for OpenHouseCommitStateUnknownException (handler:146-159) conflicts with REST-catalog convention
   (503=safe-retry/not-processed; unknown should be 500/502/504 — rest-catalog-open-api.yaml:725-756). OpenHouse's
   own client maps 503→CommitStateUnknown (client:430-438) but proxies/standard tooling may blind-retry.
7. MAJOR: IcebergSnapshotsServiceImpl.putIcebergSnapshots catches only BadRequest+CommitFailed; CommitStateUnknown
   falls to generic handler → 500 (inconsistent with tables path 503; accidental correct client behavior).
   IcebergSnapshotsServiceImpl.java:89-109 vs TablesServiceImpl.java:183-193.
8. MINOR: processSchemas swallows intermediate-schema parse exceptions (log-only) → commit proceeds with partial
   schema chain. OpenHouseInternalTableOperations.java:704-715.
9. MINOR: HTS client rename/delete retried on IllegalStateException; retry after success → NotFound surfaces as
   failure; rename block() has no timeout. HouseTableRepositoryImpl.java:219-254.
10. MINOR: doRefresh concurrent-drop → IllegalStateException → 500 (should be 404/409). OpenHouseInternalTableOperations.java:126-130.
11. MINOR: snapshotRefs omitted while jsonSnapshots present → doCommit removes ALL refs (client-derived subtractive
    merge; tables-service only sets SNAPSHOTS_REFS_KEY if map non-empty). OpenHouseInternalRepositoryImpl.java:696-708,
    OpenHouseInternalTableOperations.java:317-351.
12. MINOR: TableUUIDGenerator skips path validation for REPLICA/isTableReplicated → client-supplied UUID trusted.
13. MINOR: dropTable = HTS row delete then file purge, non-atomic (orphan files on crash); service lock/authz checks
    TOCTOU vs second load in repo.save.
14. NOTE: versionCheck TOCTOU benign (HTS CAS final arbiter). Metadata cache keyed by immutable location → safe.
15. NOTE: COMMIT_NUM_RETRIES=0 override relies on LinkedIn iceberg fork; guards 4+4b cover stock behavior anyway.

## Not read (charter: blind): docs/commit-protocol/*
