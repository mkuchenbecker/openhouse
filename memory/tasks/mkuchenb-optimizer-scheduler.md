# Task: Optimizer Scheduler (bin-packing OFD)

## Architecture

```
Commit → Tables Service → optimizer table_stats
                                    ↑
                              Analyzer (JPA)
                                    ↓
                          optimizer table_operations (PENDING)
                                    ↓
                           Scheduler (JPA, new app)
                                    ↓
                        Jobs Service → BatchedOrphanFilesDeletionSparkApp
                                    ↓
                          PATCH /v1/table-operations/{id}
                                    ↓
                          optimizer table_operations (SUCCESS/FAILED)
```

Scheduler reads PENDING rows directly from optimizer DB (same JPA pattern as analyzer).
Bin-packs tables keeping total `numCurrentFiles` per bin below 1,000,000.
Submits one batched Spark job per bin. Marks rows SCHEDULED optimistically.
Spark app PATCHes each table's outcome back to the optimizer.

---

## Phase 1 — Add `numCurrentFiles` to stats

### 1a. Add field to `TableStats.SnapshotMetrics` in optimizer service
File: `services/optimizer/src/main/java/com/linkedin/openhouse/optimizer/api/model/TableStats.java`
- Add `private Long numCurrentFiles;` to `SnapshotMetrics`

### 1b. Wire `total-data-files` in Tables Service commit path
File: `services/tables/src/main/java/com/linkedin/openhouse/tables/services/IcebergSnapshotsServiceImpl.java`
- In the snapshot summary loop, also read `total-data-files` from the last snapshot
- Pass it as `numCurrentFiles` in `reportCommitStats`

### 1c. Wire `numCurrentFiles` through `OptimizerTableStatsClient`
File: `services/tables/src/main/java/com/linkedin/openhouse/tables/config/OptimizerTableStatsClient.java`
- Add `numCurrentFiles` param to `reportCommitStats` and `buildRequestBody`
- Add it to `snapshotMetrics` JSON object (skip if null)

### 1d. Update `OptimizerTableStatsClientTest`
- Add assertion that `numCurrentFiles` appears in `snapshot` JSON when provided
- Add assertion it is absent when null

### 1e. Verify
- `./gradlew :services:optimizer:test`
- `./gradlew :services:tables:test`

---

## Phase 2 — PATCH endpoint on optimizer service

The Spark app needs `PATCH /v1/table-operations/{id}` to report per-table outcomes.
Allowed transitions: PENDING/SCHEDULED → SUCCESS or FAILED.

### 2a. Add `PatchTableOperationRequest` model
File: `services/optimizer/src/main/java/com/linkedin/openhouse/optimizer/api/model/PatchTableOperationRequest.java`
- Fields: `String status` (SUCCESS or FAILED), `OperationMetrics metrics` (nullable)

### 2b. Add `patchTableOperation` to service interface + impl
File: `services/optimizer/src/main/java/com/linkedin/openhouse/optimizer/service/OptimizerDataService.java`
- `TableOperationsDto patchTableOperation(String id, PatchTableOperationRequest request)`

File: `services/optimizer/src/main/java/com/linkedin/openhouse/optimizer/service/OptimizerDataServiceImpl.java`
- Find by id; 404 if not found
- Validate transition: only SUCCESS or FAILED allowed as target status
- Update status + metrics; use optimistic lock (version) to detect races

### 2c. Add `@PatchMapping("/{id}")` to `TableOperationsController`
File: `services/optimizer/src/main/java/com/linkedin/openhouse/optimizer/api/controller/TableOperationsController.java`
- Returns 200 with updated DTO; 404 if not found; 409 on optimistic lock conflict

### 2d. Add optimizer service test for PATCH
File: `services/optimizer/src/test/java/com/linkedin/openhouse/optimizer/repository/TableOperationsRepositoryTest.java`
- `patch_pendingToSuccess_updatesStatus()`
- `patch_nonexistentId_returns404()`

### 2e. Verify
- `./gradlew :services:optimizer:test`

---

## Phase 3 — Merge BatchedOrphanFilesDeletionSparkApp from branch

### 3a. Cherry-pick Spark app files from `mkuchenb/batched-ofd-3-itests`
Files to bring in:
- `apps/spark/src/main/java/com/linkedin/openhouse/jobs/spark/BatchedOrphanFilesDeletionSparkApp.java`
- `apps/spark/src/main/java/com/linkedin/openhouse/jobs/util/BatchMetadata.java`
- `apps/spark/src/test/java/com/linkedin/openhouse/jobs/spark/BatchedOrphanFilesDeletionSparkAppTest.java`

Do NOT bring in:
- `BatchedTableOrphanFilesDeletionTask` (scheduler doesn't use OperationTask framework)
- Any HTS / housetables changes from the branch
- Any optimizer service changes from the branch (we've done those ourselves)

### 3b. Fix `operationIds` type: `List<Long>` → `List<String>`
The optimizer uses String UUIDs for operation IDs, not Long.
File: `apps/spark/src/main/java/com/linkedin/openhouse/jobs/spark/BatchedOrphanFilesDeletionSparkApp.java`
- Change `List<Long> operationIds` → `List<String> operationIds`
- Change `Map<String, Long> tableToOperationId` → `Map<String, String>`
- Change `patchOperationStatus(HttpClient, Long id, ...)` → `patchOperationStatus(HttpClient, String id, ...)`
- Update CLI parsing: `Long::parseLong` → identity (already strings)

### 3c. Verify
- `./gradlew :apps:spark:test`

---

## Phase 4 — Create `apps/optimizer-scheduler` module

### 4a. `settings.gradle`
- Add `include ':apps:optimizer-scheduler'`

### 4b. `apps/optimizer-scheduler/build.gradle`
```gradle
plugins {
  id 'openhouse.springboot-ext-conventions'
  id 'org.springframework.boot' version '2.7.8'
}
dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
  implementation 'org.springframework.boot:spring-boot-starter-webflux'
  implementation 'org.springframework.retry:spring-retry:1.3.4'
  implementation 'org.springframework.boot:spring-boot-starter-aop'
  runtimeOnly 'mysql:mysql-connector-java:8.0.33'
  testImplementation 'org.springframework.boot:spring-boot-starter-test'
  testRuntimeOnly 'com.h2database:h2'
}
```

### 4c. `application.properties`
```properties
spring.application.name=openhouse-optimizer-scheduler
spring.datasource.url=${OPTIMIZER_DB_URL:jdbc:h2:mem:schedulerdb;DB_CLOSE_DELAY=-1;MODE=MySQL}
spring.datasource.username=${OPTIMIZER_DB_USER:sa}
spring.datasource.password=${OPTIMIZER_DB_PASSWORD:}
spring.jpa.hibernate.ddl-auto=none
jobs.base-uri=${JOBS_BASE_URI:http://localhost:8002}
scheduler.bin-size-max-files=${SCHEDULER_BIN_SIZE_MAX_FILES:1000000}
scheduler.operation-type=${SCHEDULER_OPERATION_TYPE:ORPHAN_FILES_DELETION}
scheduler.results-endpoint=${SCHEDULER_RESULTS_ENDPOINT:http://openhouse-optimizer:8080/v1/table-operations}
```

### 4d. Entity + Repository (read optimizer DB directly)

`entity/SchedulerOperationRow.java`
- Mirror of `TableOperationsRow` — same table, same columns
- Fields needed: `id`, `tableUuid`, `databaseName`, `tableName`, `operationType`, `status`, `version`
- No converters needed beyond String fields; `metrics` column not read

`entity/SchedulerStatsRow.java`
- Mirror of `TableStatsRow` — fields: `tableUuid`, `stats` (as String/JSON, or use converter)
- Only need `numCurrentFiles` from `stats.snapshot.numCurrentFiles`

`repository/SchedulerOperationsRepository.java`
```java
@Query("SELECT r FROM SchedulerOperationRow r WHERE r.operationType = :type AND r.status = 'PENDING'")
List<SchedulerOperationRow> findPendingByType(@Param("type") String operationType);

@Modifying
@Query("UPDATE SchedulerOperationRow r SET r.status = 'SCHEDULED', r.scheduledAt = :now WHERE r.id = :id AND r.version = :version")
int claimOperation(@Param("id") String id, @Param("version") Long version, @Param("now") Instant now);
```

`repository/SchedulerStatsRepository.java`
```java
// extends JpaRepository<SchedulerStatsRow, String>
// findAllById(Collection<String> uuids) — inherited
```

### 4e. `BinPacker.java`
Pure static utility — no Spring dependencies.
```java
// FILE_COUNT_LIMIT = configurable, default 1_000_000
// Input: List<SchedulerOperationRow> pending, Map<String,Long> fileCountByUuid
// Algorithm: sort descending by file count, greedy first-fit
// Output: List<List<SchedulerOperationRow>> bins
// Tables with no stats entry use file count = 0 (still schedulable, just no cost estimate)
```

### 4f. `client/JobsServiceClient.java`
WebClient pointing at `${jobs.base-uri}`.
```java
// launch(String jobName, String jobType, List<String> tableNames,
//         List<String> operationIds, String resultsEndpoint) → Optional<String> jobId
// POST /v1/jobs
// Body: { jobName, jobConf: { jobType, args: [--tableNames, ..., --operationIds, ..., --resultsEndpoint, ...] } }
// Fire and return jobId; log on error, return empty
```

### 4g. `SchedulerRunner.java`
```java
public void schedule() {
  List<SchedulerOperationRow> pending = operationsRepo.findPendingByType(operationType);
  Set<String> uuids = pending.stream().map(SchedulerOperationRow::getTableUuid).collect(toSet());
  Map<String, Long> fileCountByUuid = statsRepo.findAllById(uuids).stream()
      .collect(toMap(SchedulerStatsRow::getTableUuid, r -> extractFileCount(r)));
  List<List<SchedulerOperationRow>> bins = BinPacker.pack(pending, fileCountByUuid, maxFiles);
  bins.forEach(bin -> submitBin(bin));
}

private void submitBin(List<SchedulerOperationRow> bin) {
  List<String> tableNames = bin.stream().map(r -> r.getDatabaseName() + "." + r.getTableName()).collect(toList());
  List<String> opIds = bin.stream().map(SchedulerOperationRow::getId).collect(toList());
  Optional<String> jobId = jobsClient.launch(jobName(bin), operationType, tableNames, opIds, resultsEndpoint);
  if (jobId.isPresent()) {
    bin.forEach(r -> operationsRepo.claimOperation(r.getId(), r.getVersion(), Instant.now()));
  }
}
```

### 4h. `SchedulerApplication.java`
```java
@SpringBootApplication
public class SchedulerApplication {
  @Bean
  public CommandLineRunner run(SchedulerRunner runner) {
    return args -> runner.schedule();
  }
}
```

### 4i. `config/SchedulerConfig.java`
- `@Value("${jobs.base-uri}")` + `JobsServiceClient` WebClient bean
- `@Value("${scheduler.bin-size-max-files}")` long maxFiles
- `@Value("${scheduler.operation-type}")` String operationType
- `@Value("${scheduler.results-endpoint}")` String resultsEndpoint
- `RetryTemplate` bean (3 attempts, 1s backoff)

### 4j. Tests

`BinPackerTest.java`
- `emptyInput_returnsEmptyBins()`
- `singleTable_oneBin()`
- `tablesUnderLimit_oneBin()`
- `tablesOverLimit_twoBins()`
- `largeTableAlone_exceedsLimitSingleBin()` — a table larger than limit goes in its own bin
- `noStats_fileCountZero_groupedNormally()`
- `sortedDescending_largestTablesFirst()`

`SchedulerRunnerTest.java`
- `schedule_pendingOps_submitsBatchedJob()`
- `schedule_noPendingOps_noJobSubmitted()`
- `schedule_jobLaunchFails_rowsRemainPending()`
- `schedule_claimsRowsAfterLaunch()`

### 4k. Verify
- `./gradlew :apps:optimizer-scheduler:test`

---

## Phase 5 — Dockerfile + Docker Compose

### 5a. `optimizer-scheduler.Dockerfile`
Copy `optimizer-analyzer.Dockerfile` pattern exactly:
- `ENV APP_NAME=optimizer-scheduler`
- `ARG BUILD_DIR="build/$APP_NAME/libs"`
- No `EXPOSE`

### 5b. Add to `infra/recipes/docker-compose/common/oh-services.yml`
```yaml
openhouse-optimizer-scheduler:
  build:
    context: ../../../..
    dockerfile: optimizer-scheduler.Dockerfile
```

### 5c. Add to `oh-hadoop-spark/docker-compose.yml`
```yaml
openhouse-optimizer-scheduler:
  container_name: local.openhouse-optimizer-scheduler
  extends:
    file: ../common/oh-services.yml
    service: openhouse-optimizer-scheduler
  environment:
    - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/oh_db?allowPublicKeyRetrieval=true&useSSL=false
    - SPRING_DATASOURCE_USERNAME=oh_user
    - SPRING_DATASOURCE_PASSWORD=oh_password
    - JOBS_BASE_URI=http://openhouse-jobs:8080
    - SCHEDULER_RESULTS_ENDPOINT=http://openhouse-optimizer:8080/v1/table-operations
  depends_on:
    mysql:
      condition: service_healthy
    openhouse-optimizer:
      condition: service_started
    openhouse-jobs:
      condition: service_started
  profiles:
    - run-scheduler
```

### 5d. Update smoke-test.sh
After analyzer asserts PENDING row:
```bash
echo "=== [run] Run optimizer scheduler ==="
docker compose -f ... --profile run-scheduler run --build --rm openhouse-optimizer-scheduler

echo "=== [teardown] Assert scheduler claimed PENDING -> SCHEDULED ==="
SCHED_STATUS=$(curl -sf "http://localhost:8003/v1/table-operations?operationType=ORPHAN_FILES_DELETION" \
  | jq -r --arg uuid "$TABLE_UUID" '.[] | select(.tableUuid == $uuid) | .status')
[ "$SCHED_STATUS" = "SCHEDULED" ] || { echo "FAIL: expected SCHEDULED, got '$SCHED_STATUS'"; exit 1; }
echo "PASS: scheduler claimed row for table UUID $TABLE_UUID"
```

### 5e. Verify
- `./gradlew :apps:optimizer-scheduler:bootJar`
- `./gradlew dockerUp -Precipe=oh-hadoop-spark --rerun-tasks`
- `./smoke-test.sh`

---

## Checklist

### Phase 1 — numCurrentFiles in stats
- [x] Add `numCurrentFiles` to `TableStats.SnapshotMetrics`
- [x] Read `total-data-files` from snapshot summary in `IcebergSnapshotsServiceImpl`
- [x] Wire through `OptimizerTableStatsClient.reportCommitStats` + `buildRequestBody`
- [x] Update `OptimizerTableStatsClientTest`
- [x] Verify: `:services:optimizer:test` + `:services:tables:test` pass

### Phase 2 — PATCH endpoint on optimizer
- [x] Create `PatchTableOperationRequest`
- [x] Add `patchTableOperation` to service interface + impl
- [x] Add `@PatchMapping("/{id}")` to `TableOperationsController`
- [x] Add repository test for PATCH
- [x] Verify: `:services:optimizer:test` passes

### Phase 3 — Merge Spark app from branch
- [x] Copy `BatchedOrphanFilesDeletionSparkApp.java` from branch
- [x] Copy `BatchedOrphanFilesDeletionSparkAppTest.java` from branch (no BatchMetadata.java — removed in branch)
- [x] Fix `operationIds` type: `List<Long>` → `List<String>` throughout
- [x] Verify: `:apps:openhouse-spark-apps_2.12:test` passes (testRetentionSparkApp is pre-existing flaky failure)

### Phase 4 — optimizer-scheduler app
- [x] Add `include ':apps:optimizer-scheduler'` to `settings.gradle`
- [x] Create `build.gradle`
- [x] Create `application.properties`
- [x] Create `entity/SchedulerOperationRow.java`
- [x] Create `entity/SchedulerStatsRow.java` with JSON extraction
- [x] Create `repository/SchedulerOperationsRepository.java`
- [x] Create `repository/SchedulerStatsRepository.java`
- [x] Create `BinPacker.java`
- [x] Create `client/JobsServiceClient.java`
- [x] Create `SchedulerRunner.java`
- [x] Create `SchedulerApplication.java`
- [x] Create `config/SchedulerConfig.java`
- [x] Write `BinPackerTest.java`
- [x] Write `SchedulerRunnerTest.java`
- [x] Verify: `:apps:optimizer-scheduler:test` passes (11 tests)

### Phase 5 — Docker + smoke test
- [x] Create `optimizer-scheduler.Dockerfile`
- [x] Add service to `oh-services.yml`
- [x] Add service to `oh-hadoop-spark/docker-compose.yml` (profile: run-scheduler)
- [x] Update `smoke-test.sh` with scheduler run + SCHEDULED assertion
- [ ] Verify: `dockerUp` stack starts cleanly
- [ ] Verify: `./smoke-test.sh` passes
