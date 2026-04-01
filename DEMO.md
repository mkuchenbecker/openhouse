# OpenHouse Table Optimizer Demo

End-to-end walkthrough of the continuous table optimizer: analyze tables, schedule
maintenance, and delete orphan files — all coordinated through the optimizer service.

## Architecture

```graphviz
digraph {
  node [style=filled]
  rankdir=LR
  CommitStats -> Analyzer [dir=back]
  Analyzer -> Operations
  Operations -> Scheduler [dir=back]
  Scheduler -> SparkJob
  SparkJob -> OperationsHistory

  Analyzer [fillcolor=lightblue]
  Scheduler [fillcolor=lightblue]
  SparkJob [fillcolor=lightblue]
}


```

**Services (always running):**
| Service | Port | Purpose |
|---------|------|---------|
| Tables | 8000 | Table CRUD, pushes commit stats to optimizer |
| Jobs | 8002 | Spark job submission via Livy |
| Optimizer | 8003 | Stores `table_operations`, `table_operations_history`, `table_stats` |
| Livy | 9003 | Spark session/batch management |

**Apps (run-once):**
| App | Profile | Purpose |
|-----|---------|---------|
| Analyzer | `run-analyzer` | Scans tables, creates PENDING operations |
| Scheduler | `run-scheduler` | Claims PENDING ops, submits batched Spark jobs |

---

## Prerequisites

### 1. Build the project

```bash
./gradlew build -x test -x spotlessCheck
```

```bash
./gradlew :apps:openhouse-spark-apps_2.12:shadowJar -x test -x spotlessCheck
```

### 2. Start the cluster (clean slate)

```bash
docker compose -f infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml down -v
```

```bash
docker compose -f infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml up -d --build
```

### 3. Wait for services

```bash
curl -sf http://localhost:8003/v1/table-operations
```

Should return `[]`. If it fails, wait a few seconds and retry.

---

## Steps 1-4: Populate, expire, analyze, schedule

Run `demo-setup.sh` to create tables with orphan files, expire snapshots, run the analyzer,
and submit the batched Spark job via the scheduler:

```bash
./demo-setup.sh
```

This script does the following:

1. **Create 3 tables** (`demo_ofd_a`, `demo_ofd_b`, `demo_ofd_c`) with `maintenance.optimizer.ofd.enabled=true`
   and multiple `INSERT OVERWRITE` operations to generate orphan-eligible data files
2. **Expire old snapshots** via the Jobs Service — removes metadata references but leaves
   data files on disk (these become orphans)
3. **Run the analyzer** — scans `table_stats`, creates PENDING `ORPHAN_FILES_DELETION` operations
4. **Run the scheduler** — claims PENDING operations, bin-packs by file count, submits a
   batched Spark job through the Jobs Service

When the script finishes, the batched OFD Spark job is running.

### Verify each stage manually (optional)

Stats pushed to optimizer:

```bash
curl -sf http://localhost:8003/v1/table-stats | jq '.[].tableName'
```

Orphan files on HDFS after expiration:

```bash
docker exec local.namenode hdfs dfs -ls -R /data/openhouse/db1/ | grep -c "\.orc"
```

PENDING operations created by analyzer:

```bash
curl -sf http://localhost:8003/v1/table-operations | jq '.[] | {tableName, operationType, status}'
```

Operations moved to SCHEDULED:

```bash
curl -sf http://localhost:8003/v1/table-operations | jq '.[] | {tableName, status}'
```

---

## Step 5: Wait for the batched OFD Spark job

The `BatchedOrphanFilesDeletionSparkApp` processes all tables in a single Spark application,
deletes orphan files from HDFS, and POSTs results back to the optimizer service.

### Poll for completion (~1-3 min)

```bash
curl -sf "http://localhost:8003/v1/table-operations-history?databaseName=db1" \
  | jq '[.[] | select(.status=="SUCCESS")] | length'
```

Repeat until the count reaches `3`.

### Verify: orphan files deleted

```bash
docker exec local.namenode hdfs dfs -ls -R /data/openhouse/db1/ | grep -c "\.orc"
```

Expected: `3` (one live file per table).

### View operation history

```bash
curl -sf "http://localhost:8003/v1/table-operations-history?databaseName=db1" \
  | jq '.[] | {table: .tableName, status, orphanFilesDeleted, orphanBytesDeleted, submittedAt}'
```

---

## Automated run

To run all steps as a single script:

```bash
./demo.sh
```

## Cleanup

```bash
docker compose -f infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml down -v
```
