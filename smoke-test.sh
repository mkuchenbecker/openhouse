#!/usr/bin/env bash
# smoke-test.sh — End-to-end optimizer pipeline smoke test.
#
# Flow:
#   1. Create table with OFD opt-in, INSERT 2 rows into it.
#   2. Resolve the table's HDFS location from the Tables API.
#   3. Plant an orphan .parquet file directly into the HDFS data directory.
#      (Iceberg blocks expire_snapshots when gc.enabled=false, so we plant
#       the orphan directly rather than via the delete+expire mechanism.
#       This simulates real-world orphans from failed writes or migrations.)
#   4. Assert at least 2 .parquet files on HDFS (live file(s) + orphan).
#   5. Tables Service pushes stats; poll until the opt-in property is visible.
#   6. Run the analyzer → creates PENDING row.
#   7. Run the scheduler → claims PENDING→SCHEDULED, submits OFD job.
#      jobs.yaml sets --ttl 0, bypassing the 1-day age guard so the fresh
#      orphan is eligible for deletion immediately.
#   8. Poll until the Spark job completes and a SUCCESS history row appears.
#   9. Assert HDFS data-file count decreased — OFD deleted the orphan.
#  10. Assert the surviving rows ('1','a') and ('2','b') are still readable.
#  11. Teardown.
set -e

export TOKEN=$(cat services/common/src/main/resources/dummy.token)

# Always clean up the active Livy session on exit (success or failure).
SESSION_ID=""
_livy_cleanup() {
  if [ -n "$SESSION_ID" ]; then
    curl -sf -X DELETE "http://localhost:9003/sessions/$SESSION_ID" > /dev/null 2>&1 || true
  fi
}
trap _livy_cleanup EXIT

# Spark catalog conf shared across all Livy sessions in this test
SPARK_CONF=$(jq -n --arg token "$TOKEN" '{
  kind: "sql",
  conf: {
    "spark.jars": "local:/opt/spark/openhouse-spark-runtime_2.12-latest-all.jar",
    "spark.jars.packages": "org.apache.iceberg:iceberg-spark-runtime-3.1_2.12:1.2.0",
    "spark.sql.extensions": "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions",
    "spark.sql.catalog.openhouse": "org.apache.iceberg.spark.SparkCatalog",
    "spark.sql.catalog.openhouse.catalog-impl": "com.linkedin.openhouse.spark.OpenHouseCatalog",
    "spark.sql.catalog.openhouse.uri": "http://openhouse-tables:8080",
    "spark.sql.catalog.openhouse.auth-token": $token,
    "spark.sql.catalog.openhouse.cluster": "LocalHadoopCluster"
  }
}')

livy_session_start() {
  SESSION_ID=$(curl -sf -X POST http://localhost:9003/sessions \
    -H "Content-Type: application/json" \
    -d "$SPARK_CONF" | jq -r '.id')
  echo "Session ID: $SESSION_ID"
  for i in $(seq 1 60); do
    SESSION_STATE=$(curl -sf "http://localhost:9003/sessions/$SESSION_ID/state" | jq -r '.state')
    echo "  state: $SESSION_STATE"
    [ "$SESSION_STATE" = "idle" ] && break
    if [ "$SESSION_STATE" = "error" ] || [ "$SESSION_STATE" = "dead" ]; then
      echo "FAIL: Livy session state=$SESSION_STATE"; exit 1
    fi
    sleep 5
  done
  [ "$SESSION_STATE" = "idle" ] || { echo "FAIL: session never became idle"; exit 1; }
}

livy_session_stop() {
  curl -sf -X DELETE "http://localhost:9003/sessions/$SESSION_ID"
  echo
}

run_sql() {
  local sql="$1"
  local body stmt_id stmt_result state status
  body=$(jq -n --arg code "$sql" '{code: $code}')
  stmt_id=$(curl -sf -X POST "http://localhost:9003/sessions/$SESSION_ID/statements" \
    -H "Content-Type: application/json" \
    -d "$body" | jq -r '.id')
  for i in $(seq 1 60); do
    stmt_result=$(curl -sf "http://localhost:9003/sessions/$SESSION_ID/statements/$stmt_id")
    state=$(echo "$stmt_result" | jq -r '.state')
    [ "$state" = "available" ] && break
    if [ "$state" = "error" ] || [ "$state" = "cancelled" ]; then
      echo "FAIL: statement failed: $sql"; echo "$stmt_result" | jq '.output'; exit 1
    fi
    sleep 3
  done
  [ "$state" = "available" ] || { echo "FAIL: statement timed out: $sql"; exit 1; }
  status=$(echo "$stmt_result" | jq -r '.output.status')
  [ "$status" = "ok" ] || { echo "FAIL: statement error for: $sql"; echo "$stmt_result" | jq '.output'; exit 1; }
}

# ---------------------------------------------------------------------------
# Step 0: Wipe stale optimizer state for db1.smoke_tbl.
# Each DROP+CREATE rotates the table UUID, so repeated test runs leave behind
# rows in table_stats and table_operations. Clearing before setup ensures the
# analyzer sees exactly one table and creates exactly one PENDING row.
# ---------------------------------------------------------------------------
echo "=== [setup] Wipe stale optimizer state for db1.smoke_tbl ==="
docker exec local.mysql mysql -uoh_user -poh_password oh_db \
  -e "DELETE FROM table_stats WHERE database_id='db1' AND table_name='smoke_tbl';"
docker exec local.mysql mysql -uoh_user -poh_password oh_db \
  -e "DELETE FROM table_stats_history WHERE database_id='db1' AND table_name='smoke_tbl';"
docker exec local.mysql mysql -uoh_user -poh_password oh_db \
  -e "DELETE FROM table_operations WHERE database_name='db1' AND table_name='smoke_tbl';"

# ---------------------------------------------------------------------------
# Step 1: Create table and insert data.
# ---------------------------------------------------------------------------
echo "=== [setup] Create table and insert data ==="
livy_session_start
run_sql "DROP TABLE IF EXISTS openhouse.db1.smoke_tbl"
run_sql "CREATE TABLE openhouse.db1.smoke_tbl (id STRING, val STRING) TBLPROPERTIES (
  'maintenance.optimizer.ofd.enabled'='true'
)"
run_sql "INSERT INTO openhouse.db1.smoke_tbl VALUES ('1', 'a')"
run_sql "INSERT INTO openhouse.db1.smoke_tbl VALUES ('2', 'b')"
livy_session_stop

# ---------------------------------------------------------------------------
# Step 2: Resolve table UUID and HDFS location.
# ---------------------------------------------------------------------------
echo "=== [setup] Resolve table UUID and HDFS location ==="
TABLE_RESPONSE=$(curl -sf \
  -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8000/v1/databases/db1/tables/smoke_tbl")
TABLE_UUID=$(echo "$TABLE_RESPONSE" | jq -r '.tableUUID')
# tableLocation is the Iceberg metadata JSON file path; extract the table directory.
TABLE_LOC=$(dirname "$(echo "$TABLE_RESPONSE" | jq -r '.tableLocation')")
echo "Table UUID:     $TABLE_UUID"
echo "Table location: $TABLE_LOC"

# ---------------------------------------------------------------------------
# Step 3: Plant an orphan file in the HDFS data directory.
#
# Iceberg's expire_snapshots refuses to run when gc.enabled=false, so we
# plant the orphan directly. This simulates real-world orphan accumulation
# (failed writes, aborted compactions, migrations). A zero-byte .parquet
# file is sufficient — OFD uses Iceberg's FindOrphanFiles, which identifies
# any file in the table directory not referenced by any manifest entry.
# ---------------------------------------------------------------------------
echo "=== [setup] Plant orphan file in HDFS ==="
ORPHAN_PATH="$TABLE_LOC/data/orphan-smoke-$(date +%s).orc"
docker exec local.namenode hdfs dfs -touchz "$ORPHAN_PATH"
echo "Planted orphan: $ORPHAN_PATH"

# ---------------------------------------------------------------------------
# Step 4: Count HDFS data files before OFD.
# We expect at least 2: the live data file(s) from the INSERTs + the orphan.
# ---------------------------------------------------------------------------
echo "=== [assert] Orphan file present on HDFS before OFD ==="
DATA_FILES_BEFORE=$(docker exec local.namenode hdfs dfs -ls -R "$TABLE_LOC/data/" 2>/dev/null \
  | grep -c "\.orc" || echo "0")
echo "Data files before OFD: $DATA_FILES_BEFORE"
[ "$DATA_FILES_BEFORE" -ge 2 ] || {
  echo "FAIL: expected at least 2 data files before OFD (live file + orphan), got $DATA_FILES_BEFORE"
  exit 1
}
echo "PASS: orphan file confirmed present ($DATA_FILES_BEFORE data files on HDFS)"

# ---------------------------------------------------------------------------
# Step 5: Wait for the Tables Service to push stats to the optimizer.
# Each Iceberg commit triggers OptimizerTableStatsClient.reportCommitStats(),
# which fires a PUT /v1/table-stats/{uuid} asynchronously.
# ---------------------------------------------------------------------------
echo "=== [assert] Tables Service wrote stats to optimizer ==="
OFD_PROP=""
for i in $(seq 1 15); do
  OFD_PROP=$(curl -sf "http://localhost:8003/v1/table-stats/$TABLE_UUID" 2>/dev/null \
    | jq -r '.tableProperties["maintenance.optimizer.ofd.enabled"]' 2>/dev/null || echo "")
  [ "$OFD_PROP" = "true" ] && break
  echo "  waiting for stats ($i/15)..."
  sleep 3
done
[ "$OFD_PROP" = "true" ] || {
  echo "FAIL: Tables Service never wrote opt-in property to optimizer (got '$OFD_PROP')"
  exit 1
}
echo "PASS: optimizer stats row present with maintenance.optimizer.ofd.enabled=true"

# ---------------------------------------------------------------------------
# Step 6: Run the analyzer.
# The analyzer reads table_stats, finds the opt-in table, and inserts a PENDING
# row into table_operations.
# ---------------------------------------------------------------------------
echo "=== [run] Run optimizer analyzer ==="
docker compose \
  -f infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml \
  --profile run-analyzer \
  run --no-deps --build --rm openhouse-optimizer-analyzer

# ---------------------------------------------------------------------------
# Step 7: Assert the analyzer created a PENDING row; capture its ID.
# ---------------------------------------------------------------------------
echo "=== [assert] Analyzer created PENDING row ==="
OFD_ROW=$(curl -sf \
  "http://localhost:8003/v1/table-operations?operationType=ORPHAN_FILES_DELETION" \
  | jq --arg uuid "$TABLE_UUID" '.[] | select(.tableUuid == $uuid)')
OFD_STATUS=$(echo "$OFD_ROW" | jq -r '.status')
OP_ID=$(echo "$OFD_ROW" | jq -r '.id')
[ "$OFD_STATUS" = "PENDING" ] || { echo "FAIL: expected PENDING, got '$OFD_STATUS'"; exit 1; }
echo "PASS: analyzer created PENDING row (id=$OP_ID)"

# ---------------------------------------------------------------------------
# Step 8: Run the scheduler.
# jobs.yaml configures ORPHAN_FILES_DELETION with --ttl 0, which bypasses the
# 1-day minimum age guard so freshly orphaned files are eligible immediately.
# ---------------------------------------------------------------------------
echo "=== [run] Run optimizer scheduler ==="
docker compose \
  -f infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml \
  --profile run-scheduler \
  run --no-deps --build --rm openhouse-optimizer-scheduler

# ---------------------------------------------------------------------------
# Step 9: Assert the scheduler claimed the row (PENDING→SCHEDULED).
# ---------------------------------------------------------------------------
echo "=== [assert] Scheduler claimed row (PENDING -> SCHEDULED) ==="
SCHED_STATUS=$(curl -sf \
  "http://localhost:8003/v1/table-operations/$OP_ID" | jq -r '.status')
[ "$SCHED_STATUS" = "SCHEDULED" ] || { echo "FAIL: expected SCHEDULED, got '$SCHED_STATUS'"; exit 1; }
echo "PASS: scheduler claimed row (status=SCHEDULED)"

# ---------------------------------------------------------------------------
# Step 10: Poll for SUCCESS in history.
# The Spark job POSTs to /v1/table-operations/{id}/complete which writes a
# history row. Poll every 20 s for up to 10 minutes.
# ---------------------------------------------------------------------------
echo "=== [assert] Spark job completes operation (SUCCESS in history) ==="
HIST_STATUS=""
for i in $(seq 1 9); do
  HIST_STATUS=$(curl -sf \
    "http://localhost:8003/v1/table-operations-history?tableUuid=$TABLE_UUID&operationType=ORPHAN_FILES_DELETION&limit=1" \
    | jq -r '.[0].status // empty' 2>/dev/null || echo "")
  [ "$HIST_STATUS" = "SUCCESS" ] && break
  echo "  history status=${HIST_STATUS:-<none>}, waiting ($i/9)..."
  sleep 20
done
[ "$HIST_STATUS" = "SUCCESS" ] || {
  echo "FAIL: no SUCCESS history row after 3 min (last status='${HIST_STATUS:-<none>}')"
  echo "--- diagnostics ---"
  echo "Operation row:"
  curl -sf "http://localhost:8003/v1/table-operations/$OP_ID" 2>/dev/null | jq . || echo "(not found)"
  echo "History rows:"
  curl -sf "http://localhost:8003/v1/table-operations-history?tableUuid=$TABLE_UUID&limit=5" 2>/dev/null | jq . || echo "(none)"
  echo "Livy sessions:"
  curl -sf "http://localhost:9003/sessions" 2>/dev/null | jq '.sessions[] | {id, state, appId}' || echo "(none)"
  echo "---"
  exit 1
}
echo "PASS: operation $OP_ID completed with SUCCESS in history"

# ---------------------------------------------------------------------------
# Step 11: Assert OFD deleted the orphan file.
# The HDFS data-file count must be strictly less than before OFD ran.
# ---------------------------------------------------------------------------
echo "=== [assert] OFD deleted orphan data files ==="
DATA_FILES_AFTER=$(docker exec local.namenode hdfs dfs -ls -R "$TABLE_LOC/data/" 2>/dev/null \
  | grep -c "\.orc" || echo "0")
echo "Data files after OFD: $DATA_FILES_AFTER (was $DATA_FILES_BEFORE)"
[ "$DATA_FILES_AFTER" -lt "$DATA_FILES_BEFORE" ] || {
  echo "FAIL: OFD did not delete any orphan files (before=$DATA_FILES_BEFORE, after=$DATA_FILES_AFTER)"
  exit 1
}
echo "PASS: OFD deleted $((DATA_FILES_BEFORE - DATA_FILES_AFTER)) orphan file(s)"

# ---------------------------------------------------------------------------
# Step 12: Assert the rows are still readable.
# OFD must not have touched any file referenced by a live snapshot.
# After 2 INSERTs, exactly 2 rows should remain.
# ---------------------------------------------------------------------------
echo "=== [assert] Rows still readable after OFD ==="
livy_session_start
run_sql "SELECT IF(count(*) != 2, raise_error('Expected 2 rows after OFD'), 'ok') FROM openhouse.db1.smoke_tbl"
echo "PASS: 2 rows readable after OFD — live data untouched"

# ---------------------------------------------------------------------------
# Teardown
# ---------------------------------------------------------------------------
echo "=== [teardown] Drop smoke table ==="
run_sql "DROP TABLE IF EXISTS openhouse.db1.smoke_tbl"
livy_session_stop

echo "=== [teardown] Remove optimizer state for smoke_tbl ==="
docker exec local.mysql mysql -uoh_user -poh_password oh_db \
  -e "DELETE FROM table_stats WHERE database_id='db1' AND table_name='smoke_tbl';"
docker exec local.mysql mysql -uoh_user -poh_password oh_db \
  -e "DELETE FROM table_stats_history WHERE database_id='db1' AND table_name='smoke_tbl';"
docker exec local.mysql mysql -uoh_user -poh_password oh_db \
  -e "DELETE FROM table_operations WHERE database_name='db1' AND table_name='smoke_tbl';"

echo "DONE"
