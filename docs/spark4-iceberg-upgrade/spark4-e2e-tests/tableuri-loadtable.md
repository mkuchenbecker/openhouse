# `openhouse.tableUri` on the REST `loadTable` lane

## Backlog item

Restore the assertion in
`integrations/spark/spark-4.0/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark/catalogtest/CatalogOperationTestSpark4_0.java :: testRenameTableCatalogApi`
that the legacy 3.1 test made against the OpenHouse-only reserved property `openhouse.tableUri`
after `ALTER TABLE ... RENAME TO ...`. It had been dropped with a NOTE claiming the property is
"not surfaced on the stock REST lane."

## Empirical finding (measured, not assumed)

The property IS surfaced on the stock REST `loadTable` lane. It was never stripped. I confirmed
this by temporarily instrumenting the test to dump `loadedTable.properties()` after the rename and
running:

```
./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
  --tests "*.CatalogOperationTestSpark4_0.testRenameTableCatalogApi" -Dfile.encoding=UTF-8 --info
```

Client-side `properties()` for the renamed table contained, among others:

```
openhouse.tableUri  = local-cluster.openhouse.db.rename_test_renamed
openhouse.clusterId = local-cluster
openhouse.databaseId = openhouse.db
openhouse.tableId    = rename_test_renamed
openhouse.tableType  = PRIMARY_TABLE
openhouse.tableUUID  = 445259b0-...
```

So the earlier "dropped" NOTE was inaccurate. The real reason the original assertion would have
failed is that the **value differs from the legacy 3.1 expectation**, not that the key is missing:

- Legacy 3.1 expected: `local-cluster.db.rename_test_renamed`
- Actual on the REST lane: `local-cluster.openhouse.db.rename_test_renamed`

### Why the value differs

`openhouse.tableUri` is built in `OpenHouseInternalCatalog.renameTable`
(`iceberg/openhouse/internalcatalog/.../OpenHouseInternalCatalog.java`) via
`TableUri.builder().clusterId(...).databaseId(toDatabaseName).tableId(to.name())`, where
`toDatabaseName = to.namespace().toString()`. On the REST lane the identifier's namespace carries
the Spark catalog prefix, so `to.namespace().toString()` renders as `openhouse.db` rather than the
bare `db` seen on the legacy embedded-catalog lane. Hence the extra `openhouse.` segment. The
cluster component is `local-cluster` — the embedded test cluster name
(`ClusterProperties.getClusterName()`, used at `IcebergRestCatalogController` line ~374 as the
`clusterId`; matches `spark.sql.catalog.openhouse.cluster = local-cluster` and the fixtures'
`CLUSTER_NAME = "local-cluster"`).

The property flows end to end unchanged: rename persists it into the Iceberg metadata JSON via
`updateProperties.set(...)`; `loadTable` -> `IcebergRestCatalogController.loadTable` ->
`CatalogHandlers.loadTable` -> `OpenHouseInternalTableOperations.doRefresh/refreshMetadata` reads
the metadata JSON and reconstructs the full property map; the `LoadTableResponse` carries it back
to the stock `RESTCatalog` client. No property filter drops `openhouse.*`.

## Fix

No server change was needed. The `/iceberg` load path already surfaces `openhouse.*` reserved
properties including `openhouse.tableUri`. Only the test assertion was restored, made robust to the
embedded cluster name and the REST-lane namespace rendering by asserting on the stable tail:

```java
String tableUri = loadedTable.properties().get("openhouse.tableUri");
Assertions.assertNotNull(
    tableUri, "openhouse.tableUri should be surfaced on the REST loadTable lane");
Assertions.assertTrue(
    tableUri.endsWith("db.rename_test_renamed"),
    "openhouse.tableUri should reflect the renamed table, got: " + tableUri);
```

This replaces the dropped-assertion NOTE comment.

## Verification

```
./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
  --tests "*.CatalogOperationTestSpark4_0.testRenameTableCatalogApi" \
  --tests "*.CatalogOperationTestSpark4_0.testRenameTableCaseSensitivity" -Dfile.encoding=UTF-8
```

Result:

```
CatalogOperationTestSpark4_0 > testRenameTableCatalogApi() PASSED
CatalogOperationTestSpark4_0 > testRenameTableCaseSensitivity() PASSED
BUILD SUCCESSFUL
```

## Residual

None for this item. Note the value carries the catalog-prefixed namespace
(`local-cluster.openhouse.db.rename_test_renamed`) on the REST lane vs the bare-namespace form on
the legacy lane; the restored assertion is intentionally tail-based to tolerate that. If a future
change makes `tableUri` use the bare namespace, the assertion still holds.
