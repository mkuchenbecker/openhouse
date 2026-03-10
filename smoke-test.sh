#!/usr/bin/env bash
set -e

echo "=== PUT table-operations ==="
curl -s -X PUT http://localhost:8003/v1/table-operations/db1/tbl1/ORPHAN_FILES_DELETION \
  -H "Content-Type: application/json" -d '{}'
echo

echo "=== GET table-operations ==="
curl -s "http://localhost:8003/v1/table-operations?operationType=ORPHAN_FILES_DELETION"
echo

echo "=== POST table-operations-history ==="
curl -s -X POST http://localhost:8003/v1/table-operations-history \
  -H "Content-Type: application/json" \
  -d '{"databaseName":"db1","tableName":"tbl1","operationType":"ORPHAN_FILES_DELETION","status":"SUCCESS","submittedAt":"2026-03-09T00:00:00Z"}'
echo

echo "=== GET table-operations-history ==="
curl -s http://localhost:8003/v1/table-operations-history/db1/tbl1
echo

echo "=== PUT table-stats #1 ==="
curl -s -X PUT http://localhost:8001/v1/hts/table-stats/db1/tbl1 \
  -H "Content-Type: application/json" \
  -d '{"tableUuid":"uuid-docker-001","stats":{"snapshot":{"numSnapshots":3,"tableSizeBytes":512},"delta":{"numFilesAdded":5,"numFilesDeleted":1}}}'
echo

echo "=== GET table-stats by UUID (after #1) ==="
curl -s http://localhost:8001/v1/hts/table-stats/uuid-docker-001
echo

echo "=== PUT table-stats #2 ==="
curl -s -X PUT http://localhost:8001/v1/hts/table-stats/db1/tbl1 \
  -H "Content-Type: application/json" \
  -d '{"tableUuid":"uuid-docker-001","stats":{"snapshot":{"numSnapshots":4,"tableSizeBytes":600},"delta":{"numFilesAdded":2,"numFilesDeleted":0}}}'
echo

echo "=== GET table-stats by UUID (after #2) ==="
curl -s http://localhost:8001/v1/hts/table-stats/uuid-docker-001
echo
