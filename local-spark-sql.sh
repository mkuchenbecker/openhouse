#!/usr/bin/env bash
# local-spark-sql.sh — Execute a SQL statement against the local OpenHouse docker cluster.
#
# Reuses an existing idle Livy session if one exists. First run pays the cold start (~20s),
# subsequent runs execute immediately.
#
# Usage: ./local-spark-sql.sh "INSERT INTO openhouse.db1.smoke_tbl VALUES ('3', 'c')"
#        ./local-spark-sql.sh --kill   # tear down the reusable session
set -e

LIVY="http://localhost:9003"
TOKEN=$(cat /Users/mkuchenb/code/openhouse/services/common/src/main/resources/dummy.token)

if [ "$1" = "--kill" ]; then
  SESSIONS=$(curl -sf "$LIVY/sessions" | jq -r '.sessions[].id')
  for sid in $SESSIONS; do
    curl -sf -X DELETE "$LIVY/sessions/$sid" > /dev/null 2>&1
    echo "Killed session $sid"
  done
  exit 0
fi

if [ -z "$1" ]; then
  echo "Usage: local-spark-sql.sh \"<SQL statement>\""
  echo "       local-spark-sql.sh --kill"
  exit 1
fi

SQL="$1"

# Try to find an existing idle session
SESSION_ID=$(curl -sf "$LIVY/sessions" | jq -r '[.sessions[] | select(.state == "idle")] | first | .id // empty')

if [ -z "$SESSION_ID" ]; then
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

  echo "No idle session found, creating one..."
  SESSION_ID=$(curl -sf -X POST "$LIVY/sessions" \
    -H "Content-Type: application/json" \
    -d "$SPARK_CONF" | jq -r '.id')

  for i in $(seq 1 60); do
    STATE=$(curl -sf "$LIVY/sessions/$SESSION_ID/state" | jq -r '.state')
    [ "$STATE" = "idle" ] && break
    if [ "$STATE" = "error" ] || [ "$STATE" = "dead" ]; then
      echo "FAIL: session state=$STATE"
      exit 1
    fi
    echo "  $STATE..."
    sleep 3
  done
  [ "$STATE" = "idle" ] || { echo "FAIL: session never became idle"; exit 1; }
  echo "Session $SESSION_ID ready."
else
  echo "Reusing session $SESSION_ID"
fi

echo "Executing: $SQL"
BODY=$(jq -n --arg code "$SQL" '{code: $code}')
STMT_ID=$(curl -sf -X POST "$LIVY/sessions/$SESSION_ID/statements" \
  -H "Content-Type: application/json" \
  -d "$BODY" | jq -r '.id')

for i in $(seq 1 60); do
  RESULT=$(curl -sf "$LIVY/sessions/$SESSION_ID/statements/$STMT_ID")
  STMT_STATE=$(echo "$RESULT" | jq -r '.state')
  [ "$STMT_STATE" = "available" ] && break
  if [ "$STMT_STATE" = "error" ] || [ "$STMT_STATE" = "cancelled" ]; then
    echo "FAIL: $SQL"
    echo "$RESULT" | jq '.output'
    exit 1
  fi
  sleep 2
done

STATUS=$(echo "$RESULT" | jq -r '.output.status')
if [ "$STATUS" != "ok" ]; then
  echo "FAIL: $SQL"
  echo "$RESULT" | jq '.output'
  exit 1
fi

echo "OK"
echo "$RESULT" | jq -r '.output.data // empty'
