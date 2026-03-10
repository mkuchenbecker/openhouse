#!/usr/bin/env bash
set -e

export TOKEN=$(cat services/common/src/main/resources/dummy.token)

# ---------------------------------------------------------------------------
# Optimizer service
# ---------------------------------------------------------------------------

echo "=== PUT table-operations ==="
curl -sf -X PUT http://localhost:8003/v1/table-operations/db1/tbl1/ORPHAN_FILES_DELETION \
  -H "Content-Type: application/json" -d '{}'
echo

echo "=== GET table-operations ==="
curl -sf "http://localhost:8003/v1/table-operations?operationType=ORPHAN_FILES_DELETION"
echo

echo "=== POST table-operations-history ==="
curl -sf -X POST http://localhost:8003/v1/table-operations-history \
  -H "Content-Type: application/json" \
  -d '{"databaseName":"db1","tableName":"tbl1","operationType":"ORPHAN_FILES_DELETION","status":"SUCCESS","submittedAt":"2026-03-09T00:00:00Z"}'
echo

echo "=== GET table-operations-history ==="
curl -sf http://localhost:8003/v1/table-operations-history/db1/tbl1
echo

# ---------------------------------------------------------------------------
# Table stats — driven by real Spark commits through the OpenHouse catalog
# ---------------------------------------------------------------------------

echo "=== Create Livy session ==="
SESSION_BODY=$(jq -n --arg token "$TOKEN" '{
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
SESSION_ID=$(curl -sf -X POST http://localhost:9003/sessions \
  -H "Content-Type: application/json" \
  -d "$SESSION_BODY" | jq -r '.id')
echo "Session ID: $SESSION_ID"

echo "=== Wait for session to be idle ==="
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

echo "=== Create table via Spark ==="
run_sql "DROP TABLE IF EXISTS openhouse.db1.smoke_tbl"
run_sql "CREATE TABLE openhouse.db1.smoke_tbl (id STRING, val STRING)"

echo "=== Insert commit 1 ==="
run_sql "INSERT INTO openhouse.db1.smoke_tbl VALUES ('1', 'a')"

echo "=== Insert commit 2 ==="
run_sql "INSERT INTO openhouse.db1.smoke_tbl VALUES ('2', 'b')"

echo "=== Delete Livy session ==="
curl -sf -X DELETE "http://localhost:9003/sessions/$SESSION_ID"
echo

echo "=== Get table UUID from tables service ==="
TABLE_UUID=$(curl -sf \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "http://localhost:8000/v1/databases/db1/tables/smoke_tbl" | jq -r '.tableUUID')
echo "Table UUID: $TABLE_UUID"

echo "=== Wait for async stats to propagate ==="
sleep 5

echo "=== GET table-stats ==="
RESULT=$(curl -sf "http://localhost:8001/v1/hts/table-stats/$TABLE_UUID")
echo "$RESULT"
ADDED=$(echo "$RESULT" | jq -r '.stats.delta.numFilesAdded')
[ "$ADDED" -ge 1 ] 2>/dev/null || { echo "FAIL: expected numFilesAdded>=1, got $ADDED"; exit 1; }
echo "PASS: numFilesAdded=$ADDED"
