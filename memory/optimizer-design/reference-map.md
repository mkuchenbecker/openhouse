# Optimizer Reference Map

Look here first before searching code. One-line description per file.
All paths relative to repo root. Branch: `mkuchenb/optimizer`.

---

## DB Schemas

| File | Description |
|---|---|
| `services/optimizer/src/main/resources/db/optimizer-schema.sql` | Three tables: `table_operations` (PK=id UUID, table_uuid, status, version), `table_stats` (PK=table_uuid, stats TEXT, table_properties TEXT), `table_operations_history` (PK=id BIGINT AUTO_INCREMENT) |
| `services/housetables/src/main/resources/schema.sql` | HTS schema; `table_stats` added here (PK=table_uuid, stats TEXT, table_properties TEXT) — same shape as optimizer's copy but owned by HTS |

---

## Optimizer Service (`services/optimizer`)

### Entry point
| File | Description |
|---|---|
| `src/main/java/.../OptimizerServiceApplication.java` | `@SpringBootApplication` main class |
| `src/main/resources/application.properties` | MySQL datasource, JPA ddl-auto=none, port 8003 |
| `src/main/resources/db/optimizer-schema.sql` | See DB Schemas above |

### Controllers
| File | Description |
|---|---|
| `api/controller/TableOperationsController.java` | `PUT /v1/table-operations/{id}` (upsert), `GET /v1/table-operations?operationType=` (list active), `PATCH /v1/table-operations/{id}` (mark SUCCESS/FAILED) |
| `api/controller/TableOperationsHistoryController.java` | `POST /v1/table-operations-history` (append), `GET /v1/table-operations-history/{tableUuid}?limit=` |
| `api/controller/TableStatsController.java` | `PUT /v1/table-stats/{tableUuid}` (upsert), `GET /v1/table-stats/{tableUuid}` |

### Service layer
| File | Description |
|---|---|
| `service/OptimizerDataService.java` | Interface: upsertTableOperation, patchTableOperation, upsertTableStats, getTableStats, appendHistory, getHistory |
| `service/OptimizerDataServiceImpl.java` | All logic: upsert=find-by-id or insert-PENDING; patch=find+update-status; history=append-only |

### Entities
| File | Description |
|---|---|
| `entity/TableOperationsRow.java` | JPA: `table_operations`. Typed enums for status/operationType. `@Version` for optimistic lock. `@Convert` for OperationMetrics JSON. |
| `entity/TableOperationsHistoryRow.java` | JPA: `table_operations_history`. Append-only; `@Convert` for JobResult JSON. |
| `entity/TableStatsRow.java` | JPA: `table_stats`. Uses `@TypeDef/@Type(type="json")` via hibernate-types-55 for stats + tableProperties. |
| `entity/package-info.java` | Package-level `@TypeDef` declaration required by hibernate-types-55 |

### Repositories
| File | Description |
|---|---|
| `repository/TableOperationsRepository.java` | `find(OperationType)` → active rows; `findAll()` used for unfiltered GET |
| `repository/TableOperationsHistoryRepository.java` | `find(tableUuid, limit)` → recent history |
| `repository/TableStatsRepository.java` | `JpaRepository<TableStatsRow, String>` — findById, save |

### Models / DTOs
| File | Description |
|---|---|
| `api/model/OperationStatus.java` | Enum: PENDING, SCHEDULED, SUCCESS, FAILED |
| `api/model/OperationType.java` | Enum: ORPHAN_FILES_DELETION (extensible) |
| `api/model/TableOperationsDto.java` | Full operation record DTO including tableUuid, status, metrics |
| `api/model/UpsertTableOperationsRequest.java` | PUT body: tableUuid, databaseName, tableName, operationType, metrics |
| `api/model/PatchTableOperationRequest.java` | PATCH body: status (SUCCESS/FAILED), metrics |
| `api/model/TableOperationsHistoryDto.java` | History entry DTO: tableUuid, status, jobId, result |
| `api/model/OperationMetrics.java` | Nested class: tableSizeBytes, numSnapshots, numCurrentFiles |
| `api/model/JobResult.java` | Nested class: orphanFilesDeleted, bytesDeleted, durationMs, errorMessage, errorType |
| `api/model/TableStats.java` | `SnapshotMetrics` + `CommitDelta` — stored as JSON in table_stats |
| `api/model/TableStatsDto.java` | DTO wrapping TableStats |
| `api/model/UpsertTableStatsRequest.java` | PUT body: databaseId, tableName, stats, tableProperties |
| `api/mapper/OptimizerMapper.java` | MapStruct-style hand-written mapper: entity ↔ DTO for all three tables |

### Converters (JPA AttributeConverter)
| File | Description |
|---|---|
| `config/OperationMetricsConverter.java` | OperationMetrics ↔ JSON TEXT |
| `config/JobResultConverter.java` | JobResult ↔ JSON TEXT |

---

## HouseTables Service (`services/housetables`)

| File | Description |
|---|---|
| `api/spec/request/UpsertTableStatsRequest.java` | PUT /v1/hts/table-stats body: databaseId, tableName, stats, tableProperties |
| `controller/TableStatsController.java` | `PUT /v1/hts/table-stats/{tableUuid}`, `GET /v1/hts/table-stats` (bulk), `GET /v1/hts/table-stats/{tableUuid}` |
| `services/TableStatsService.java` | Interface: upsertTableStats, getAllTableStats, getTableStats |
| `services/TableStatsServiceImpl.java` | upsert=find-or-create; getAllTableStats=findAll→mapToDto |
| `model/TableStatsRow.java` | JPA entity for HTS table_stats; uses `@TypeDef/@Type(type="json")` |
| `dto/model/TableStats.java` | HTS-local TableStats POJO (same shape as optimizer's: SnapshotMetrics + CommitDelta) |
| `dto/model/TableStatsDto.java` | HTS DTO |
| `dto/mapper/TableStatsMapper.java` | entity ↔ DTO |
| `impl/jdbc/TableStatsHtsJdbcRepository.java` | `JpaRepository<TableStatsRow, String>` |
| `config/TablePropertiesConverter.java` | Map<String,String> ↔ JSON TEXT (JPA AttributeConverter) |
| `config/TableStatsConverter.java` | TableStats ↔ JSON TEXT (JPA AttributeConverter) |

---

## Tables Service Integration (`services/tables`)

| File | Description |
|---|---|
| `config/OptimizerTableStatsClient.java` | Fire-and-forget WebClient: extracts stats from snapshot summaries and PUTs to optimizer `/v1/table-stats/{tableUuid}`. Errors are logged and swallowed — never fails a commit. |
| `services/IcebergSnapshotsServiceImpl.java` | After saving the table, loops snapshot JSON summaries to accumulate numFilesAdded, numFilesDeleted, tableSizeBytes, numCurrentFiles, then calls `reportCommitStats`. |
| `cluster/configs/ClusterProperties.java` | Provides `clusterOptimizerBaseUri` config value to the client |

---

## Shared JPA Library (`apps/optimizer`)

| File | Description |
|---|---|
| `build.gradle` | `openhouse.java-minimal-conventions`; deps: spring-data-jpa, hibernate-types-55, h2 (test). Custom `buildDir` to avoid collision with `services:optimizer`. |
| `entity/TableOperationRow.java` | Unified JPA entity for `table_operations`. String fields (not enums) for status/operationType — usable without the optimizer service's enum classes. Includes `version` (plain Long, not `@Version` — scheduler uses manual optimistic lock via UPDATE WHERE version=). |
| `entity/TableStatsRow.java` | Unified JPA entity for `table_stats`. `@TypeDef/@Type(type="json")` for stats (typed `TableStats`) and tableProperties (`Map<String,String>`). |
| `model/TableStats.java` | Canonical `TableStats` model shared by analyzer and scheduler: `SnapshotMetrics` (clusterId, tableVersion, tableLocation, numSnapshots, tableSizeBytes, numCurrentFiles) + `CommitDelta` (numFilesAdded, numFilesDeleted). |
| `repository/TableOperationsRepository.java` | Three queries: `findByTypeAndStatuses` (analyzer), `findPendingByType` (scheduler), `claimOperation` (@Modifying UPDATE WHERE version= — atomic claim). |
| `repository/TableStatsRepository.java` | `JpaRepository<TableStatsRow, String>` — inherited `findAllById` used by scheduler for bulk file-count lookup. |

---

## Analyzer App (`apps/optimizer-analyzer`)

| File | Description |
|---|---|
| `build.gradle` | Spring Boot app; depends on `:apps:optimizer`; mysql runtime, h2 test |
| `src/main/resources/application.properties` | datasource (defaults to H2), hts.base-uri, ofd.success-retry-hours=24, ofd.failure-retry-hours=1 |
| `AnalyzerApplication.java` | `@SpringBootApplication` + `@EntityScan("com.linkedin.openhouse.optimizer.entity")` + `@EnableJpaRepositories("com.linkedin.openhouse.optimizer.repository")` |
| `AnalyzerRunner.java` | Main loop: `statsRepo.findAll()` → for each analyzer: load active ops → filter enabled tables → insert PENDING rows for tables where `shouldSchedule` returns true and no active row exists |
| `OperationAnalyzer.java` | Interface: `getOperationType()`, `isEnabled(TableSummary)`, `shouldSchedule(TableSummary, Optional<TableOperationRecord>)` |
| `OrphanFilesDeletionAnalyzer.java` | OFD impl; enabled by property `maintenance.optimizer.ofd.enabled=true`; delegates cadence to `CadencePolicy` |
| `CadencePolicy.java` | State machine: PENDING→reschedule, SCHEDULED→skip, SUCCESS→reschedule after successRetryInterval, FAILED→reschedule after failureRetryInterval, null scheduledAt→reschedule |
| `config/AnalyzerConfig.java` | `RetryTemplate` bean (3 attempts, 1s backoff) |
| `model/TableSummary.java` | Table identity (uuid, databaseId, tableId) + tableProperties + TableStats |
| `model/TableOperationRecord.java` | Active operation state: id, tableUuid, status, scheduledAt |

---

## Scheduler App (`apps/optimizer-scheduler`)

| File | Description |
|---|---|
| `build.gradle` | Spring Boot app; depends on `:apps:optimizer`; mysql runtime, h2 test |
| `src/main/resources/application.properties` | datasource, jobs.base-uri, scheduler.bin-size-max-files=1000000, scheduler.operation-type, scheduler.results-endpoint |
| `SchedulerApplication.java` | `@SpringBootApplication` + `@EntityScan` + `@EnableJpaRepositories`; `CommandLineRunner` bean calls `runner.schedule()` |
| `SchedulerRunner.java` | Reads PENDING rows, joins with table_stats for file counts, packs bins via BinPacker, then per-bin: claim (version guard) then submit job. Rows stay SCHEDULED on launch failure. |
| `BinPacker.java` | Greedy First Fit Descending: sort desc by numCurrentFiles, assign to first bin with remaining capacity; oversized tables get their own bin. |
| `client/JobsServiceClient.java` | `POST /jobs` with `jobName`, `clusterId`, `jobConf.jobType`, `jobConf.args` (--tableNames, --operationIds, --resultsEndpoint). Returns Optional<jobId>. |
| `config/SchedulerConfig.java` | Wires `JobsServiceClient` WebClient bean; exposes `@Value` properties |
| `src/test/resources/schema.sql` | H2 test schema mirroring optimizer-schema.sql |

---

## Spark App (`apps/spark`)

| File | Description |
|---|---|
| `spark/BatchedOrphanFilesDeletionSparkApp.java` | Processes a batch of tables in a single Spark session using a driver-side thread pool. Each table runs `deleteOrphanFilesWithMetrics` concurrently. After all futures resolve, PATCHes each table's outcome to `{resultsEndpoint}/{operationId}`. Throws only if ALL tables failed (partial failure allowed). |
| `spark/Operations.java` | Added `deleteOrphanFilesWithMetrics` returning `OrphanFilesResult` with bytesDeleted |

---

## Infra

| File | Description |
|---|---|
| `optimizer-service.Dockerfile` | Spring Boot JAR runner for the optimizer REST service; EXPOSE 8080 |
| `optimizer-analyzer.Dockerfile` | Same pattern; no EXPOSE (CommandLineRunner, runs once and exits) |
| `optimizer-scheduler.Dockerfile` | Same pattern; no EXPOSE |
| `infra/recipes/docker-compose/common/oh-services.yml` | Base service definitions (build context) for optimizer, analyzer, scheduler |
| `infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml` | Full stack: optimizer on 8003, analyzer + scheduler under `run-analyzer` / `run-scheduler` profiles, env wiring |
| `infra/recipes/docker-compose/oh-only/docker-compose.yml` | Minimal stack (no Spark) with optimizer service |
| `smoke-test.sh` | End-to-end: Spark commit → table-stats populated → analyzer creates PENDING → scheduler claims SCHEDULED |
| `build.gradle` (root) | Adds `clusterOptimizerBaseUri` config key to ClusterProperties |
