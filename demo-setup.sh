#!/usr/bin/env bash
# demo-setup.sh — Populate tables, expire snapshots, run analyzer, run scheduler.
#
# Prerequisites: docker compose down -v && docker compose up -d --build
#
# After this script completes, a batched OFD Spark job is running. Use demo-check.sh
# or poll the history endpoint to watch for completion.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DC="docker compose -f $SCRIPT_DIR/infra/recipes/docker-compose/oh-hadoop-spark/docker-compose.yml"
TOKEN=$(cat "$SCRIPT_DIR/services/common/src/main/resources/dummy.token")
JOBS_API="http://localhost:8002/jobs"
OPT_API="http://localhost:8003"
LIVY_API="http://localhost:9003"

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
echo "=== [1/4] Create tables and populate with data ==="
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

STATS_COUNT=$(curl -sf "$OPT_API/v1/table-stats" | jq 'length')
[ "$STATS_COUNT" -ge "$TABLE_COUNT" ] \
  || { echo "FAIL: expected $TABLE_COUNT stats rows, got $STATS_COUNT"; exit 1; }
echo "PASS: $STATS_COUNT stats rows written by Tables Service"

# ---------------------------------------------------------------------------
echo ""
echo "=== [2/4] Expire old snapshots ==="
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

while IFS='=' read -r TABLE TABLE_LOC; do
  COUNT=$(docker exec local.namenode \
    hdfs dfs -ls -R "$TABLE_LOC/data/" 2>/dev/null | grep -c "\.orc" || echo "0")
  [ "$COUNT" -ge 2 ] \
    || { echo "FAIL: $TABLE has $COUNT files after expiration, expected >= 2"; exit 1; }
  echo "  $TABLE: $COUNT files on HDFS ($((COUNT-1)) orphans + 1 live)"
done < /tmp/demo_ofd_locs.txt

# ---------------------------------------------------------------------------
echo ""
echo "=== [3/4] Run optimizer analyzer ==="
kill_idle_session

$DC --profile run-analyzer run --no-deps --build --rm openhouse-optimizer-analyzer \
  2>&1 | grep -E "Found [0-9]+ tables|Created PENDING|Analysis complete|ERROR"

PENDING=$(curl -sf "$OPT_API/v1/table-operations?status=PENDING" | jq 'length')
[ "$PENDING" -ge "$TABLE_COUNT" ] \
  || { echo "FAIL: expected $TABLE_COUNT PENDING rows, got $PENDING"; exit 1; }
echo "PASS: $PENDING PENDING operations created"

# ---------------------------------------------------------------------------
echo ""
echo "=== [4/4] Run optimizer scheduler ==="
$DC --profile run-scheduler run --no-deps --build --rm openhouse-optimizer-scheduler \
  2>&1 | grep -E "Packed|Submitted|ERROR"

kill_idle_session

SCHEDULED=$(curl -sf "$OPT_API/v1/table-operations?status=SCHEDULED" | jq 'length')
[ "$SCHEDULED" -ge "$TABLE_COUNT" ] \
  || { echo "FAIL: expected $TABLE_COUNT SCHEDULED rows, got $SCHEDULED"; exit 1; }
echo "PASS: $SCHEDULED operations SCHEDULED"

echo ""
echo "Setup complete. Batched OFD Spark job is now running."
echo "Run ./demo-check.sh to verify orphan file deletion once the job finishes."
