# Spark 4.0 / Iceberg 1.11 / REST-first e2e port — residuals

This file records every case (or sub-assertion) from the legacy Spark-3.1 / Spark-3.5 in-JVM e2e
`catalogtest` suite that could NOT be carried over verbatim to the Spark-4.0 / Iceberg-1.11 /
REST-first lane, and why. The REST lane wires Spark to a STOCK `org.apache.iceberg.rest.RESTCatalog`
pointed at the OpenHouse `/iceberg/v1/*` controller. It therefore does NOT have:

- the custom OpenHouse Spark SQL extension (SET/UNSET POLICY, GRANT, column tags, `.policies`),
- the OpenHouse Java client (`com.linkedin.openhouse.javaclient.OpenHouseCatalog`,
  `WebClientResponseWithMessageException`, the `Policies` gen-model),
- OpenHouse-server-only table semantics exposed through those clients (e.g. `openhouse.tableUri`
  table property, `REPLICA_TABLE` field-id-preservation, the "has invalid metadata" surfaced error
  string).

Per porting rule #4/#6 those cases are dropped (not stubbed) and documented here.

---

## WapIdTest → WapIdTestSpark4_0
- `testWapWorkflowWithVariousOperations`: the two custom-OpenHouse statements exercised inline —
  `ALTER TABLE ... SET POLICY (SHARING=TRUE)` and `GRANT SELECT ON TABLE ... TO lejiang` — are
  dropped (no custom OpenHouse SQL extension on the REST lane). The WAP / cherry-pick / delete /
  expire portion of the workflow (the actual subject of the test) is kept verbatim and passes.
- `testExpireSnapshotsWithEmptyRefs`: BEHAVIORAL DIFFERENCE, not an omission. The 3.1 lane asserted
  that expiring the sole unpublished WAP snapshot (a table with zero refs) "does nothing" and left 1
  snapshot. On the stock Iceberg 1.11 lane that snapshot is unreferenced, so `expire_snapshots`
  correctly removes it (0 snapshots remain; still 0 rows). Assertion updated to the 1.11 behavior;
  intent (expire on empty-refs table) is unchanged.

## PartitionTest → PartitionTestSpark4_0
- `testCreateTablePartitionedWithNestedColumn`: no case dropped. The `DESCRIBE TABLE` string rendering
  of a nested identity transform is engine-specific: Spark-4.0 renders the source type (`bigint`) and
  `truncate(10, header.time)`, where Spark-3.1 rendered `header.time` / `truncate(header.time, 10)`.
  Expected strings updated to the 4.0 rendering (the underlying partition spec is identical). This
  matches what the 3.5 lane already observed.

## CTASNonNullTest / CTASNonNullTestSpark3_5 → CTASNonNullTestSpark4_0
- Ported once (both source classes were byte-identical). No omission: Spark-4.0 also leaves the CTAS
  target column nullable by default, so the assertion holds unchanged.

## RTASTest → RTASTestSpark4_0
- `testRTAS`: dropped the `ALTER TABLE ... SET POLICY (HISTORY MAX_AGE=24H)` statement (custom
  OpenHouse SQL) and the follow-on `assertEquals("", rtasTable.properties().get("policies"))`
  assertion (the `policies` table property is populated only by the custom OpenHouse catalog). The
  RTAS mechanics (schema/spec/snapshots/properties) are kept and pass. The `replace.enabled` gate is
  still enforced by the embedded server and surfaces as `BadRequestException` (unchanged).

## CatalogOperationTest → CatalogOperationTestSpark4_0
- `testAlterTableUnsetReplicationPolicy`: DROPPED entirely. Built entirely on custom
  `SET/UNSET POLICY (REPLICATION|RETENTION ...)` SQL and the `com.linkedin.openhouse.gen.tables.client.model.Policies`
  gen-model read back from the `policies` table property — none of which exist on the REST lane.
- `testRenameTable` (catalog-API variant, ported as `testRenameTableCatalogApi`): dropped the
  `openhouse.tableUri == local-cluster.db.rename_test_renamed` assertion (OpenHouse-only property not
  surfaced by the stock RESTCatalog). Rename + old-table-gone (`NoSuchTableException`) kept.
- `testRenameTableFailsConflict`: DROPPED. It verified that renaming ONTO an existing table is
  rejected (via the custom `WebClientResponseWithMessageException`). On the stock REST lane the
  OpenHouse `/iceberg` rename endpoint does NOT reject the conflict — empirically `ALTER TABLE ...
  RENAME TO <existing>` threw nothing and the source table was gone afterward (silent replace). The
  asserted behavior is absent on this lane, so the case is dropped, not stubbed.
- Java-API `createTable(id, schema, spec, props)` / `buildTable(...).createTransaction()` cases: the
  OpenHouse `/iceberg` controller NPEs on a null `PartitionSpec` (the legacy OpenHouseCatalog client
  defaulted it). Changed the passed spec from `null` to `PartitionSpec.unpartitioned()` — a
  catalog-wiring adjustment only; table intent (unpartitioned) is unchanged.
