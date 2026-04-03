#!/usr/bin/env bash
# demo.sh — Full end-to-end optimizer demo from a blank slate.
#
# Prerequisites: docker compose down -v && docker compose up -d --build
#
# Run lazy-optimizer in a separate terminal:
#   python lazy_optimizer.py --livy-url http://localhost:9003
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OHC_DIR="$SCRIPT_DIR"
DC="docker compose -f $OHC_DIR/infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml"
TOKEN=$(cat "$OHC_DIR/services/common/src/main/resources/dummy.token")
JOBS_API="http://localhost:8002/jobs"
OPT_API="http://localhost:8003"
LIVY_API="http://localhost:9003"

# Table name → number of INSERT OVERWRITEs  (N-2 orphan files after expire)
TABLES="demo_ofd_a:5 demo_ofd_b:7 demo_ofd_c:4"
TABLE_COUNT=3

wait_for_job() {
  local JOB_ID="$1" LABEL="$2" MAX_SECS="${3:-120}"
  local i=0
  while [ $i -lt "$MAX_SECS" ]; do
    STATE=$(curl -sf "$JOBS_API/$JOB_ID" | jq -r '.state // empty' 2>/dev/null || echo "")
    [ "$STATE" = "SUCCEEDED" ] && return 0
    [ "$STATE" = "FAILED" ] && { echo "FAIL: $LABEL job $JOB_ID FAILED"; exit 1; }
    sleep 5
    i=$((i + 5))
  done
  echo "FAIL: $LABEL job $JOB_ID timed out after ${MAX_SECS}s (last state: $STATE)"
  exit 1
}

kill_idle_session() {
  IDLE=$(curl -sf "$LIVY_API/sessions" \
    | jq -r '[.sessions[] | select(.state=="idle")] | first | .id // empty')
  if [ -n "$IDLE" ]; then
    curl -sf -X DELETE "$LIVY_API/sessions/$IDLE" > /dev/null
    echo "  Freed idle Livy session $IDLE"
  fi
}

# ---------------------------------------------------------------------------
echo "=== [1/5] Create tables and populate with data ==="
rm -f /tmp/demo_ofd_locs.txt

for entry in $TABLES; do
  TABLE="${entry%%:*}"
  WRITES="${entry##*:}"
  ORPHANS=$((WRITES - 2))

  echo "  SQL: DROP TABLE IF EXISTS openhouse.db1.$TABLE"
  "$SCRIPT_DIR/local-spark-sql.sh" "DROP TABLE IF EXISTS openhouse.db1.$TABLE" > /dev/null
  echo "  SQL: CREATE TABLE openhouse.db1.$TABLE (id STRING, val STRING) TBLPROPERTIES ('maintenance.optimizer.ofd.enabled'='true')"
  "$SCRIPT_DIR/local-spark-sql.sh" "CREATE TABLE openhouse.db1.$TABLE (
    id STRING, val STRING
  ) TBLPROPERTIES ('maintenance.optimizer.ofd.enabled'='true')" > /dev/null

  for i in $(seq 1 "$WRITES"); do
    echo "  SQL: INSERT OVERWRITE openhouse.db1.$TABLE VALUES ('$i', 'row$i')"
    "$SCRIPT_DIR/local-spark-sql.sh" \
      "INSERT OVERWRITE openhouse.db1.$TABLE VALUES ('$i', 'row$i')" > /dev/null
    printf "  $TABLE: insert %d/%d\r" "$i" "$WRITES"
  done
  echo ""

  TABLE_LOC=$(dirname "$(curl -sf \
    -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8000/v1/databases/db1/tables/$TABLE" \
    | jq -r '.tableLocation')")
  echo "  $TABLE -> $TABLE_LOC ($WRITES snapshots, $ORPHANS will become orphans)"
  echo "$TABLE=$TABLE_LOC" >> /tmp/demo_ofd_locs.txt
done

# Assert: stats exist for all 3 tables
STATS_COUNT=$(curl -sf "$OPT_API/v1/table-stats" | jq 'length')
[ "$STATS_COUNT" -ge "$TABLE_COUNT" ] \
  || { echo "FAIL: expected $TABLE_COUNT stats rows, got $STATS_COUNT"; exit 1; }
echo "PASS: $STATS_COUNT stats rows written by Tables Service"

# ---------------------------------------------------------------------------
echo ""
echo "=== [2/5] Expire old snapshots (manual) ==="
kill_idle_session

EXPIRE_JOBS=""
for entry in $TABLES; do
  TABLE="${entry%%:*}"
  BODY=$(jq -n --arg n "demo-expire-$TABLE" --arg t "db1.$TABLE" \
    '{jobName:$n, clusterId:"LocalHadoopCluster",
      jobConf:{jobType:"SNAPSHOTS_EXPIRATION",
        args:["--tableName",$t,"--maxAge","1","--granularity","days","--versions","1"]}}')
  JOB_ID=$(curl -sf -X POST "$JOBS_API" -H "Content-Type: application/json" -d "$BODY" \
    | jq -r '.jobId')
  [ -n "$JOB_ID" ] || { echo "FAIL: could not submit expiration job for $TABLE"; exit 1; }
  echo "  $TABLE: submitted $JOB_ID"
  EXPIRE_JOBS="$EXPIRE_JOBS $JOB_ID"
done

for JOB_ID in $EXPIRE_JOBS; do
  wait_for_job "$JOB_ID" "snapshot-expiration" 180
  echo "  $JOB_ID: SUCCEEDED"
done

# Assert: HDFS still has files (expire is metadata-only)
while IFS='=' read -r TABLE TABLE_LOC; do
  COUNT=$(docker exec local.namenode \
    hdfs dfs -ls -R "$TABLE_LOC/data/" 2>/dev/null | grep -c "\.orc" || echo "0")
  [ "$COUNT" -ge 2 ] \
    || { echo "FAIL: $TABLE has $COUNT files after expiration, expected >= 2 (orphans+live)"; exit 1; }
  echo "  $TABLE: $COUNT files on HDFS ($((COUNT-1)) orphans + 1 live)"
done < /tmp/demo_ofd_locs.txt

# ---------------------------------------------------------------------------
echo ""
echo "=== [3/5] Run optimizer analyzer ==="
kill_idle_session

$DC --profile run-analyzer run --no-deps --build --rm openhouse-optimizer-analyzer \
  2>&1 | grep -E "Found [0-9]+ tables|Created PENDING|Analysis complete|ERROR"

PENDING=$(curl -sf "$OPT_API/v1/table-operations?status=PENDING" | jq 'length')
[ "$PENDING" -ge "$TABLE_COUNT" ] \
  || { echo "FAIL: expected $TABLE_COUNT PENDING rows, got $PENDING"; exit 1; }
echo "PASS: $PENDING PENDING operations created"

# ---------------------------------------------------------------------------
echo ""
echo "=== [4/5] Run optimizer scheduler ==="
$DC --profile run-scheduler run --no-deps --build --rm openhouse-optimizer-scheduler \
  2>&1 | grep -E "Packed|Submitted|ERROR"

kill_idle_session

SCHEDULED=$(curl -sf "$OPT_API/v1/table-operations?status=SCHEDULED" | jq 'length')
[ "$SCHEDULED" -ge "$TABLE_COUNT" ] \
  || { echo "FAIL: expected $TABLE_COUNT SCHEDULED rows, got $SCHEDULED"; exit 1; }
echo "PASS: $SCHEDULED operations SCHEDULED"

# ---------------------------------------------------------------------------
echo ""
echo "=== [5/5] Wait for OFD Spark job to complete (up to 5 min) ==="
for i in $(seq 1 30); do
  SUCCESS=$(curl -sf "$OPT_API/v1/table-operations-history?databaseName=db1" \
    | jq '[.[] | select(.status=="SUCCESS")] | length' 2>/dev/null || echo "0")
  [ "$SUCCESS" -ge "$TABLE_COUNT" ] && break
  echo "  $i/30: $SUCCESS/$TABLE_COUNT completed..."
  sleep 10
done

[ "$SUCCESS" -ge "$TABLE_COUNT" ] \
  || { echo "FAIL: only $SUCCESS/$TABLE_COUNT operations completed after 5min"; exit 1; }

echo ""
bash "$SCRIPT_DIR/demo-check.sh"
