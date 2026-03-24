#!/usr/bin/env bash
# demo-check.sh — Run after OFD completes. Shows HDFS file counts and orphan totals per table.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOKEN=$(cat "$SCRIPT_DIR/services/common/src/main/resources/dummy.token")

if [ ! -f /tmp/demo_ofd_locs.txt ]; then
  echo "ERROR: /tmp/demo_ofd_locs.txt not found — run demo-setup.sh first"
  exit 1
fi

echo "=== HDFS data files after OFD ==="
while IFS='=' read -r TABLE TABLE_LOC; do
  FILE_COUNT=$(docker exec local.namenode \
    hdfs dfs -ls -R "$TABLE_LOC/data/" 2>/dev/null \
    | grep -c "\.orc" || echo "0")
  echo "  $TABLE: $FILE_COUNT files remaining"
done < /tmp/demo_ofd_locs.txt

echo ""
echo "=== Operation history ==="
curl -sf \
  "http://localhost:8003/v1/table-operations-history?databaseName=db1" \
  | jq '.[] | {table: .tableName, status, orphanFilesDeleted, orphanBytesDeleted, submittedAt}'
