# Fix: rename-onto-existing-table is not rejected (Spark-4.0 / Iceberg-1.11 REST lane)

Status: FIXED. Test `CatalogOperationTestSpark4_0#testRenameTableFailsConflict` re-enabled and GREEN.

## Symptom

On the Spark-4.0 / Iceberg-1.11 REST-first lane, renaming a table ONTO an
already-existing table was not rejected. The covering test
`integrations/spark/spark-4.0/openhouse-spark-itest/.../CatalogOperationTestSpark4_0.java#testRenameTableFailsConflict`
was `@Disabled` with reason "rename-onto-existing not rejected on REST lane
(silent replace)" and additionally asserted a client-specific exception type
(`WebClientResponseWithMessageException`) that does not apply on the stock
`RESTCatalog` lane.

The test scenario:
- create `db.rename_test2` (source, has `user.property=test_property`)
- create `db.rename_test_conflict` (pre-existing destination, no `user.property`)
- `ALTER TABLE openhouse.db.rename_test2 RENAME TO openhouse.db.rename_test_conflict`
- expect: rename rejected, both tables survive intact.

## Trace findings

Temporary logging was added to `OpenHouseInternalCatalog.renameTable` (the
identifiers it receives) and to `OpenHouseInternalTableOperations.doCommit` /
`doRefresh` (the HTS keys actually written / looked up). Running the targeted
test produced:

Conflict test (`testRenameTableFailsConflict`):
```
TRACE-RENAME from=db.rename_test2 to=openhouse.db.rename_test_conflict toExists=false
```

Sibling (passing) renames, for comparison:
```
# testRenameTableCatalogApi  (SQL RENAME)
TRACE-RENAME  from=db.rename_test           to=openhouse.db.rename_test_renamed        toExists=false
# testRenameTable            (SQL RENAME)
TRACE-RENAME  from=d1_catalog.rename_src    to=openhouse.d1_catalog.rename_dst         toExists=false
TRACE-HTSRENAME fromDb=d1_catalog fromTable=rename_src toDb=openhouse.d1_catalog toTable=rename_dst
TRACE-REFRESH lookup db=d1_catalog table=rename_dst      # <-- later SELECT finds it under db=d1_catalog
# testRenameTableCaseSensitivity (Java catalog API RENAME)
TRACE-RENAME  from=DB.RENAME_test3          to=db.rename_test_renamed_CASE_SENSITIVE   toExists=false
```

Two facts fall out of the trace:

1. **The Spark catalog name leaks into the rename DESTINATION namespace.**
   For SQL `ALTER TABLE openhouse.<db>.<x> RENAME TO openhouse.<db>.<y>`, the
   stock `RESTCatalog` sends the SOURCE correctly stripped (`namespace=[db]`)
   but the DESTINATION with the catalog name still attached
   (`namespace=[openhouse, db]`, e.g. `to=openhouse.db.rename_test_conflict`).
   The Java catalog-API rename (`testRenameTableCaseSensitivity`) is NOT
   affected — it passes a clean `to=db....`. This is a Spark SQL RENAME TO
   resolution asymmetry on the REST lane, not something OpenHouse controls.

2. **The pre-existing conflict table lives under the clean namespace.**
   `db.rename_test_conflict` was created through the Java catalog API, so its
   HTS row key is `databaseId=db`. A `tableExists(to)` check against the leaked
   `to` (namespace `openhouse.db`) therefore resolves the WRONG namespace and
   returns `false` (`toExists=false` in the trace) — the conflict is invisible
   to any guard keyed off the raw `to` identifier.

### Why the prior attempt (controller guard) did not fire

A prior attempt added `if (catalog.tableExists(request.destination())) throw
AlreadyExistsException(...)` in
`IcebergRestCatalogController#renameTable`. `request.destination()` is exactly
the leaked `to` (`openhouse.db.rename_test_conflict`), so
`catalog.tableExists(...)` looked up namespace `openhouse.db` (which has no
table) and returned `false`. The guard's condition was never true, so it never
threw — matching the observed "still did not throw". The guard was checking a
namespace the table does not live in.

### Why the non-conflicting renames still "work" despite the leaked namespace

`UserTablesServiceImpl.renameUserTable`
(`services/housetables/.../UserTablesServiceImpl.java:159-163`) deliberately
DISCARDS the destination databaseId and reuses the source's:
```java
// Use fromDatabaseId for destination db to preserve the original case of the database
// TODO: Use toDataBaseId for destination instead of fromDatabaseId once rename across databases is supported
htsJdbcRepository.renameTableId(fromDatabaseId, fromTableId, fromDatabaseId, toTableId, metadataLocation);
```
So even though `OpenHouseInternalTableOperations.doCommit` passes
`toDb=openhouse.d1_catalog`, HTS renames only the tableId WITHIN the source
database (`d1_catalog`). That is why the later `SELECT ... rename_dst` finds the
row under `db=d1_catalog` (see `TRACE-REFRESH lookup db=d1_catalog
table=rename_dst`) and the sibling tests pass. OpenHouse rename is
single-database by construction.

## Root cause

`OpenHouseInternalCatalog.renameTable` (and the REST controller that delegates
to it via `CatalogHandlers.renameTable` -> `catalog.renameTable(source,
destination)`) performed NO destination-existence check at all, so a rename onto
an occupied table name was accepted. A naive fix that checks `tableExists(to)`
cannot work because:
- the effective destination database is ALWAYS the source's namespace (HTS
  ignores the target db), and
- on the Spark SQL REST lane the raw `to` namespace carries the leaked Spark
  catalog name, so it points at a namespace the table does not live in.

The correct conflict target is the **effective destination**:
`from.namespace()` (the real, source database) combined with `to.name()` (the
new table id) — precisely the HTS row the rename will write.

## The fix

File: `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalCatalog.java`
Method: `renameTable(TableIdentifier from, TableIdentifier to)`

Placed at the correct layer (the catalog) so all callers — the REST controller
and any direct `catalog.renameTable(...)` caller — benefit.

### Before

```java
@Override
public void renameTable(TableIdentifier from, TableIdentifier to) {
  Table fromTable = loadTable(from);
  String tableClusterId = fromTable.properties().get(CatalogConstants.OPENHOUSE_CLUSTERID_KEY);
  ...
}
```

### After

```java
@Override
public void renameTable(TableIdentifier from, TableIdentifier to) {
  Table fromTable = loadTable(from);

  // Reject a rename onto an already-existing table (silent-replace guard).
  //
  // The conflict must be checked at the EFFECTIVE destination -- the source namespace combined
  // with the new table name -- NOT the raw `to` identifier. Two things make the raw `to`
  // unreliable:
  //   1. OpenHouse only supports renaming within a single database: the HTS rename
  //      (UserTablesServiceImpl.renameUserTable) discards the destination databaseId and reuses
  //      the source's, so the destination database is always the source's namespace.
  //   2. On the Spark REST lane, `ALTER TABLE openhouse.db.x RENAME TO openhouse.db.y` leaks the
  //      Spark catalog name into the destination namespace, so `to` arrives as `openhouse.db.y`
  //      (namespace [openhouse, db]) while the actual table lives under namespace [db]. A
  //      tableExists(to) check would resolve the wrong, catalog-prefixed namespace and never see
  //      the conflict.
  // Resolving the destination against the source namespace matches exactly the row the rename
  // will write, so this is the identifier the existence check must use.
  TableIdentifier effectiveDestination = TableIdentifier.of(from.namespace(), to.name());
  boolean renamingToSameTable =
      from.namespace().toString().equalsIgnoreCase(effectiveDestination.namespace().toString())
          && from.name().equalsIgnoreCase(effectiveDestination.name());
  if (!renamingToSameTable && tableExists(effectiveDestination)) {
    throw new org.apache.iceberg.exceptions.AlreadyExistsException(
        "Cannot rename %s to %s because a table already exists at %s",
        from, to, effectiveDestination);
  }

  String tableClusterId = fromTable.properties().get(CatalogConstants.OPENHOUSE_CLUSTERID_KEY);
  ...
}
```

Notes:
- `tableExists` (default `Catalog.tableExists` on `BaseMetastoreCatalog`) resolves
  through `loadTable -> doRefresh -> HouseTableRepository.findById`, which is
  case-insensitive on both databaseId and tableId, so the check matches an
  existing destination regardless of case.
- The `renamingToSameTable` guard prevents a false positive if a table is
  "renamed" to its own name (effective destination == source), which would
  otherwise report a conflict against the source itself.
- `org.apache.iceberg.exceptions.AlreadyExistsException` is already mapped to
  HTTP 409 by `IcebergRestCatalogController`'s `handleConflict` `@ExceptionHandler`,
  so the stock `RESTCatalog` client surfaces the rejection as an exception.

### Test change

File: `integrations/spark/spark-4.0/openhouse-spark-itest/.../CatalogOperationTestSpark4_0.java`
- Removed `@Disabled`.
- Removed the now-unused `WebClientResponseWithMessageException` import
  (checkstyle fails on unused imports; the `org.junit.jupiter.api.Disabled`
  import is retained because `testAlterTableUnsetReplicationPolicy` still uses it).
- Replaced the client-specific exception-type assertion with an engine-agnostic
  BEHAVIORAL assertion: the rename throws SOME exception AND both the source and
  the pre-existing destination survive intact (source still loadable; the
  destination's `user.property` was never set, proving no silent replace leaked
  the source's property onto it).

## Verification

Targeted runs (`export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && export
LANG=C.UTF-8 && ./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test
--tests "...<name>" -Dfile.encoding=UTF-8`):

| Test | Result |
| --- | --- |
| `testRenameTableFailsConflict` | PASSED (was `@Disabled`) |
| `testRenameTable` (rename to non-existent, SQL) | PASSED |
| `testRenameTableCatalogApi` (rename to non-existent, SQL) | PASSED |
| `testRenameTableCaseSensitivity` (rename to non-existent, Java API) | PASSED |

All four run together: BUILD SUCCESSFUL, 0 failures. The three
rename-to-nonexistent cases confirm the guard does not false-positive; the
conflict case confirms it now rejects and both tables survive.

All temporary trace logging was removed after the investigation; the only
non-test change is the guard in `OpenHouseInternalCatalog.renameTable`.

## Residual / follow-up

The underlying single-database-rename limitation is unchanged (HTS
`renameUserTable` still ignores the destination databaseId — see its TODO). This
fix does not add cross-database rename; it only rejects a rename that would
collide with an existing table in the (effective) destination database. If
cross-database rename is ever implemented, the effective-destination computation
here must be revisited to use the real target database.
