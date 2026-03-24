# Performance Tier Feature — Design & Build Plan

Branch: `mkuchenb/optimizer` (or new branch TBD)

## Design Summary

A table-level `performanceTier` policy controlling HDFS block replication for all files
(metadata, manifests, data). Customers pick a tier or leave it on auto for the system
to tune over time. Higher tier = more replicas = more disk space = better read performance.

### Property model (stored in `policies` JSON inside Iceberg table properties)
```json
{
  "performanceTier": {
    "auto": true,
    "resolved": "STANDARD"
  }
}
```

- `auto` (boolean, default `true`) — system manages the tier; autotuner can change `resolved`
- `resolved` (enum: `STANDARD | HIGH | MAX`) — the effective tier; always present, always authoritative
- When `auto=false`, customer pins `resolved`; service validates and writes it, never changes it
- When `auto=true`, `resolved` starts at cluster default and an autotuner OperationType can adjust it

### Cluster-level config (in cluster.yaml → ClusterProperties)
```yaml
cluster:
  performance-tier:
    default-tier: STANDARD
    hdfs-replication:
      STANDARD: 3
      HIGH: 9
      MAX: 27
```
Operator-configured per cluster. Maps tiers to HDFS replication factors.

### Write-path integration
- **Server-side (metadata.json):** `OpenHouseInternalTableOperations.doCommit()` reads
  `resolved` from table properties, creates a short-lived `HadoopFileIO` with
  `dfs.replication` set to the mapped value before writing `metadata.json`.
- **Client-side (manifests + data files):** `OpenHouseTableOperations.io()` returns a
  `HadoopFileIO` initialized with `dfs.replication` from the table's `resolved` tier.
  The tier → replication mapping must be available client-side (returned in table metadata).

---

## Checklist

### Phase 1: API & Data Model

- [x] 1.1 Create `PerformanceTier` enum (`STANDARD`, `HIGH`, `MAX`)
  - `services/tables/src/main/java/com/linkedin/openhouse/tables/api/spec/v0/request/components/PerformanceTier.java`

- [x] 1.2 Create `PerformanceTierConfig` POJO (auto: boolean, resolved: PerformanceTier)
  - Same package as other policy components

- [x] 1.3 Add `performanceTier` field to `Policies.java`
  - Nullable; when null the service treats it as `{auto: true, resolved: <cluster default>}`

- [x] 1.4 Create `PerformanceTierValidator` (extends `PolicySpecValidator`)
  - `services/tables/src/main/java/com/linkedin/openhouse/tables/api/validator/impl/PerformanceTierValidator.java`

- [x] 1.5 Wire `PerformanceTierValidator` into `OpenHouseTablesApiValidator`

### Phase 2: Cluster Configuration

- [x] 2.1 Add performance-tier config to `ClusterProperties`
  - `cluster.performance-tier.default-tier` (String, default `STANDARD`)
  - `cluster.performance-tier.hdfs-replication.STANDARD` (int, default 3)
  - `cluster.performance-tier.hdfs-replication.HIGH` (int, default 9)
  - `cluster.performance-tier.hdfs-replication.MAX` (int, default 27)

- [x] 2.2 Tier resolution logic in `PoliciesSpecMapper.resolvePerformanceTier()` + `getHdfsReplicationFactor()` (no separate bean needed)
  - `resolve(PerformanceTierConfig input) → PerformanceTierConfig` — fills `resolved` if
    auto=true using cluster default; validates against known tiers
  - `getHdfsReplicationFactor(PerformanceTier tier) → short` — maps tier to replication factor

### Phase 3: Policy Resolution in Tables Service

- [x] 3.1 Add tier resolution to `PoliciesSpecMapper.mapPolicies()`
- [x] 3.2 Tests in `PoliciesSpecMapperTest`: defaultResolution, customerPinnedPreserved, replicationFactor
- [x] 3.3 Store resolved HDFS replication factor in table properties via `putPerformanceTierReplication()` / `updatePerformanceTierReplication()` in `OpenHouseInternalRepositoryImpl`
  - Key: `CatalogConstants.OPENHOUSE_PERFORMANCE_TIER_REPLICATION_KEY`

### Phase 4: Server-Side Write Path (metadata.json replication)

- [x] 4.1 `OpenHouseInternalTableOperations.resolveWriteIO()` — reads replication property,
  creates per-table `HadoopFileIO` with `dfs.replication` set; used in `doCommit()`
- [x] 4.2 Test: verify metadata.json replication (needs MiniDFS or mocked FileSystem)

### Phase 5: Client-Side Write Path (manifests + data file replication)

- [x] 5.1 `OpenHouseTableOperations.io()` overridden — reads replication from `current().properties()`,
  returns cached `HadoopFileIO` with `dfs.replication` set; conf passed from `OpenHouseCatalog`
- [x] 5.2 `OpenHouseCatalog.newTableOps()` passes `this.conf` to builder
- [x] 5.3 Test: verify manifest/data file replication (needs integration test with HDFS)

### Phase 6: Spark SQL Extension (optional, nice-to-have)

- [ ] 6.1 Add `ALTER TABLE SET PERFORMANCE TIER = HIGH` syntax
  - Or use existing `ALTER TABLE SET TBLPROPERTIES` path via policies update

### Phase 7: Autotuner OperationType (future — part of optimizer work)

- [ ] 7.1 Add `PERFORMANCE_TIER_TUNE` to `OperationType`
- [ ] 7.2 Implement `PerformanceTierAnalyzer` in optimizer-analyzer
  - Reads table access patterns / size metrics
  - For tables with `auto=true`, decides whether to change `resolved`
  - Calls Tables Service to update the policy

### Phase 8: Tests & Documentation

- [ ] 8.1 Unit tests for `PerformanceTierResolver`, `PerformanceTierValidator`
- [ ] 8.2 Integration tests in tables e2e (H2) for create/update with performanceTier policy
- [ ] 8.3 Spark integration test for property round-trip
- [ ] 8.4 Update OpenAPI spec / swagger annotations

---

## Key Design Decisions

1. **Tier names, not raw replication factors, in the API** — customers set STANDARD/HIGH/MAX,
   never a number. The number is cluster-specific and operator-controlled.

2. **Denormalized replication factor in table properties** — the resolved HDFS replication
   factor (e.g. `9`) is written to `openhouse.performance-tier.hdfs-replication` so clients
   can read it directly without knowing the cluster's tier-to-replication mapping.

3. **`auto` is a control-plane signal only** — clients and the write path read `resolved`
   (and the denormalized replication factor). They never look at `auto`.

4. **Replication only affects new writes** — existing files retain their original replication.
   This is inherent to HDFS and should be documented.

5. **Server fills in defaults** — a table with no `performanceTier` policy gets
   `{auto: true, resolved: STANDARD}` and `openhouse.performance-tier.hdfs-replication: 3`
   at creation time (or on next update via backfill).

6. **Three fields total**: `auto` (who controls), `resolved` (the tier), and the denormalized
   `openhouse.performance-tier.hdfs-replication` (the number). No `requested` field — when
   `auto=false`, the customer writes `resolved` directly.
