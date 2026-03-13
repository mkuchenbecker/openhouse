#!/usr/bin/env bash
set -e

export TOKEN=$(cat services/common/src/main/resources/dummy.token)

# Spark catalog conf shared between setup and teardown sessions
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
  [ "$status" = "ok" ] || { echo "FAIL: statement error for: $sql"; exit 1; }
}

# ---------------------------------------------------------------------------
# Step 1: Setup — optimizer API smoke, create table with data and OFD opt-in
# ---------------------------------------------------------------------------
echo "=== [setup] Optimizer API ==="
OP_ID=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
curl -sf -X PUT "http://localhost:8003/v1/table-operations/$OP_ID" \
  -H "Content-Type: application/json" \
  -d "{\"tableUuid\":\"00000000-0000-0000-0000-000000000001\",\"databaseName\":\"db1\",\"tableName\":\"tbl1\",\"operationType\":\"ORPHAN_FILES_DELETION\"}"
echo
curl -sf "http://localhost:8003/v1/table-operations?operationType=ORPHAN_FILES_DELETION"
echo
curl -sf -X POST http://localhost:8003/v1/table-operations-history \
  -H "Content-Type: application/json" \
  -d '{"tableUuid":"00000000-0000-0000-0000-000000000001","databaseName":"db1","tableName":"tbl1","operationType":"ORPHAN_FILES_DELETION","status":"SUCCESS","submittedAt":"2026-03-09T00:00:00Z"}'
echo
curl -sf http://localhost:8003/v1/table-operations-history/00000000-0000-0000-0000-000000000001
echo

echo "=== [setup] Create table with OFD opt-in ==="
livy_session_start
run_sql "DROP TABLE IF EXISTS openhouse.db1.smoke_tbl"
run_sql "CREATE TABLE openhouse.db1.smoke_tbl (id STRING, val STRING)"
run_sql "INSERT INTO openhouse.db1.smoke_tbl VALUES ('1', 'a')"
run_sql "INSERT INTO openhouse.db1.smoke_tbl VALUES ('2', 'b')"
run_sql "ALTER TABLE openhouse.db1.smoke_tbl SET TBLPROPERTIES ('maintenance.optimizer.ofd.enabled'='true')"
livy_session_stop

echo "=== [setup] Get table UUID ==="
TABLE_UUID=$(curl -sf \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "http://localhost:8000/v1/databases/db1/tables/smoke_tbl" | jq -r '.tableUUID')
echo "Table UUID: $TABLE_UUID"

echo "=== [setup] Update optimizer tableProperties with OFD opt-in ==="
sleep 5
curl -sf -X PUT "http://localhost:8003/v1/table-stats/$TABLE_UUID" \
  -H "Content-Type: application/json" \
  -d "{\"databaseId\":\"db1\",\"tableName\":\"smoke_tbl\",\"tableProperties\":{\"maintenance.optimizer.ofd.enabled\":\"true\"}}"
echo "DONE: tableProperties updated in optimizer"

echo "=== [setup] Assert stats row written to optimizer ==="
OFD_PROP=$(curl -sf "http://localhost:8003/v1/table-stats/$TABLE_UUID" \
  | jq -r '.tableProperties["maintenance.optimizer.ofd.enabled"]')
[ "$OFD_PROP" = "true" ] || { echo "FAIL: expected tableProperties opt-in=true, got '$OFD_PROP'"; exit 1; }
echo "PASS: optimizer stats row verified"

# ---------------------------------------------------------------------------
# Step 2: Run — execute the analyzer
# ---------------------------------------------------------------------------
echo "=== [run] Run optimizer analyzer ==="
docker compose \
  -f infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml \
  --profile run-analyzer \
  run --build --rm openhouse-optimizer-analyzer

# ---------------------------------------------------------------------------
# Step 3: Teardown — assert result, drop table
# ---------------------------------------------------------------------------
echo "=== [teardown] Assert analyzer created PENDING row ==="
OFD_STATUS=$(curl -sf \
  "http://localhost:8003/v1/table-operations?operationType=ORPHAN_FILES_DELETION" \
  | jq -r --arg uuid "$TABLE_UUID" '.[] | select(.tableUuid == $uuid) | .status')
[ "$OFD_STATUS" = "PENDING" ] || { echo "FAIL: expected PENDING, got '$OFD_STATUS'"; exit 1; }
echo "PASS: analyzer created PENDING row for table UUID $TABLE_UUID"

echo "=== [teardown] Drop smoke table ==="
livy_session_start
run_sql "DROP TABLE IF EXISTS openhouse.db1.smoke_tbl"
livy_session_stop

echo "DONE"
