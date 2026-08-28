# Code Manifest — OpenHouse Commit Path Review

Files a reviewer should read to review the table write/commit path, in suggested reading order. All paths relative to `/home/user/openhouse`.

## Client side (engine → REST)
1. `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseTableOperations.java` — client Iceberg TableOperations: doRefresh via GET table, doCommit routing (createUpdateTable / putSnapshots / putSnapshotsForReplace), baseTableVersion stamping, HTTP→CommitFailed/CommitStateUnknown mapping.
2. `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseCatalog.java` — client catalog; newTableOps wiring, REST client setup (Spark's catalog at `integrations/spark/spark-3.1/.../spark/OpenHouseCatalog.java` is a 1-line subclass).

## Tables REST service (controller → service → repository)
3. `services/tables/src/main/java/com/linkedin/openhouse/tables/controller/IcebergSnapshotsController.java` — PUT .../iceberg/v2/snapshots endpoint (the data-commit entry point).
4. `services/tables/src/main/java/com/linkedin/openhouse/tables/controller/TablesController.java` — POST/PUT table endpoints (metadata-only commits, creation).
5. `services/tables/src/main/java/com/linkedin/openhouse/tables/services/IcebergSnapshotsServiceImpl.java` — snapshots-put orchestration: authz, lock check, CommitFailed→409 translation.
6. `services/tables/src/main/java/com/linkedin/openhouse/tables/services/TablesServiceImpl.java` — putTable orchestration + saveTableDto exception translation (409/503).
7. `services/tables/src/main/java/com/linkedin/openhouse/tables/dto/mapper/TablesMapper.java` — request→TableDto mapping; baseTableVersion→tableVersion ("store base version to check later").
8. `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java` — the pivotal service-side class: create/replace/update branches, versionCheck (first CAS), COMMIT_KEY stamping, commit.num-retries=0 override, snapshots→properties staging, transaction.commitTransaction().
9. `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/InternalRepositoryUtils.java` — getSchemeLessPath, convertToTableDto (what tableVersion/tableLocation the client gets back).

## Server-side internal catalog (the commit engine)
10. `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java` — THE core file: doRefresh from HTS, doCommit (metadata file naming/write, tableVersion/tableLocation property flip, snapshot add/remove/refs merge, HTS save, error classification, checkCommitStatus fallback), abortIfWriterBaseDivergedFromCatalog CAS, failIfRetryUpdate dedup cache.
11. `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalCatalog.java` — newTableOps wiring, FileIO resolution, dropTable/renameTable paths.
12. `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/CatalogConstants.java` — property keys that carry the protocol (commitKey, snapshotsJsonToBePut, INITIAL_VERSION, transient prefixes).
13. `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/SnapshotsUtil.java` — snapshot/ref (de)serialization between REST payload and Iceberg objects.
14. `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/mapper/HouseTableMapper.java` — TableMetadata properties → HouseTable row extraction (openhouse.* fields).
15. `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/model/HouseTable.java` — the row model the tables service persists (tableLocation, tableVersion columns).

## HTS client (tables service → HTS)
16. `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImpl.java` — WebClient repo: save (no write retries), HTTP status→typed exception mapping (409→HouseTableConcurrentUpdateException, 5xx→StateUnknown), read retry template.
17. `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HtsRetryUtils.java` — retry attempts/backoff policy for HTS reads.

## House Tables service (the commit point)
18. `services/housetables/src/main/java/com/linkedin/openhouse/housetables/services/UserTablesServiceImpl.java` — putUserTable: find-then-save, optimistic-lock exception → EntityConcurrentModificationException; rename/delete/restore flows.
19. `services/housetables/src/main/java/com/linkedin/openhouse/housetables/dto/mapper/UserTableVersionMapper.java` — the CAS rule: request tableVersion (a metadata path) must equal the row's metadataLocation to inherit the JPA @Version; else 409.
20. `services/housetables/src/main/java/com/linkedin/openhouse/housetables/model/UserTableRow.java` — the entity: `@Version Long version` (the actual monotonically increasing number) + metadataLocation.
21. `services/housetables/src/main/java/com/linkedin/openhouse/housetables/repository/impl/jdbc/UserTableHtsJdbcRepository.java` — JPA repository; note renameTableId bypasses @Version.
22. `services/housetables/src/main/java/com/linkedin/openhouse/housetables/controller/UserHouseTablesController.java` — /hts/tables endpoints (with `api/handler/OpenHouseUserTableHtsApiHandler.java` for status codes).
23. `services/housetables/src/main/java/com/linkedin/openhouse/housetables/dto/mapper/UserTablesMapper.java` — UserTable⇄UserTableRow⇄DTO mapping (metadataLocation⇄tableVersion surface mapping).

## Cross-cutting
24. `services/common/src/main/java/com/linkedin/openhouse/common/exception/handler/OpenHouseExceptionHandler.java` — exception→HTTP status table for both services (409 conflict, 503 commit-state-unknown, 400, 500 fallback).
25. `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/utils/MetadataUpdateUtils.java` — post-commit in-place metadata.json rewrite used by the replicated-table-create path (non-atomic; review carefully).
