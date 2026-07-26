# Feature: OpenHouse table POLICIES settable + readable on the Spark-4.0 / Iceberg-1.11 REST lane

Status: IMPLEMENTED. Server translates a policy table-property on the `/iceberg` path;
verified by `PolicyPropertyTestSpark4_0` (5 tests, GREEN) and the existing
`services:tables` policy unit tests (still GREEN).

## Problem

On the Spark-4.0 lane the stock `org.apache.iceberg.rest.RESTCatalog` drives OpenHouse
through the `/iceberg/v1/*` endpoint (`IcebergRestCatalogController`), not the native
`/tables` endpoint. OpenHouse models a table's policy as a structured `Policies` object
that the native `/tables` path validates and stores into the reserved `policies` table
property. The stock RESTCatalog has no way to speak that structured model, and the
`/iceberg` controller previously (a) had no translation for a policy set and (b) actively
rejected any change to the reserved `policies` / `openhouse.*` properties as an
`ALTER_RESERVED_TBLPROPS` violation (HTTP 400). So policies could not be set or read on
this lane. This change adds the server-side foundation: the `/iceberg` endpoint now
ACCEPTS and TRANSLATES a policy table-property into the OpenHouse `Policies` model, mirroring
what `/tables` does, while keeping the reserved-property protection intact for every
non-policy property.

## Client property contract

A stock RESTCatalog sets a policy by carrying it as a single table property:

- **Key**: `updated.openhouse.policy`
- **Value**: the JSON serialization of a (partial) OpenHouse `Policies` object.

Examples (exactly the encoding the legacy Spark `Set*PolicyExec` emitted):

```
updated.openhouse.policy = {"retention":{"count":3,"granularity":"DAY","columnPattern":{"columnName":"datecol","pattern":"yyyy-MM-dd"}}}
updated.openhouse.policy = {"sharingEnabled":true}
updated.openhouse.policy = {"replication":{"config":[{"destination":"clusterA","interval":"12H"}]}}
```

This is deliberately identical to the legacy contract. The legacy Spark SQL extension's
`SetRetentionPolicyExec` / `SetSharingPolicyExec` / ... (`integrations/spark/spark-3.x/.../v2/Set*PolicyExec.scala`)
each did `iceberg.table().updateProperties().set("updated.openhouse.policy", <json>).commit()`,
and the legacy client (`OpenHouseTableOperations.buildUpdatedPolicies`,
`integrations/java/iceberg-1.2/.../OpenHouseTableOperations.java`) consumed that key,
merged it onto the existing `policies`, and sent the full structured `Policies` to `/tables`.
Choosing the same key + encoding means a future Spark-4.0 port of the `SET POLICY` SQL
extension maps onto this server translation with no client change: it emits the same
property and the server does the rest.

The granularity token is UPPERCASE (`DAY`, `HOUR`, `MONTH`, `YEAR`) — this matches both the
server `TimePartitionSpec.Granularity` enum names and the legacy encoding: the legacy AST
builder wrote `TimePartitionSpec.GranularityEnum.DAY.getValue()`, whose generated value is
`"DAY"`. No case translation is needed or performed.

Unlike a normal table property, `updated.openhouse.policy` is NEVER persisted verbatim. On
both create and update it is stripped from the table properties and folded into the reserved
`policies` property through OpenHouse's own policy pipeline.

## Server translation

All changes are in
`services/tables/src/main/java/com/linkedin/openhouse/tables/controller/IcebergRestCatalogController.java`.

### CREATE (`POST /iceberg/v1/namespaces/{ns}/tables`)

`toCreateUpdateTableRequestBody(databaseId, CreateTableRequest)` now:
1. Removes `updated.openhouse.policy` from the pass-through table properties.
2. If present, translates it to a `Policies` object via `translatePolicyPatch(null, patch)`
   (no existing policy on create, so the patch IS the full policy).
3. Sets `.policies(policies)` on the `CreateUpdateTableRequestBody`.

The rest of create is unchanged: it still calls the SAME `TablesApiHandler.createTable`
bean the native controller uses, so the reused pipeline runs
`OpenHouseTablesApiValidator.validateCreateTable` (which calls `validatePolicies`) and
persists the policy via `OpenHouseInternalRepositoryImpl` → `computePropsForTableCreation`
→ `PoliciesSpecMapper.toPoliciesJsonString` into the reserved `policies` property.

### UPDATE / commit (`POST /iceberg/v1/namespaces/{ns}/tables/{table}`)

`updateTable(...)` dispatches on the commit shape. A new branch is added AFTER the CTAS
(`AssertTableDoesNotExist`) and RTAS (`isReplacePayload`) branches and BEFORE the plain-update
guards:

```
if (isPolicyUpdate(request)) {
  return updatePolicy(ns, ident, request);
}
```

- `isPolicyUpdate(request)` returns true iff any `MetadataUpdate.SetProperties` in the commit
  sets the `updated.openhouse.policy` key. (INSERT snapshot commits and other ALTERs never set
  it, so they fall through to the unchanged plain-update path.)
- `updatePolicy(...)`:
  1. Loads the base `TableMetadata`; rejects a locked table up front (`enforceNotLocked`,
     the same guard the plain path uses — parity with `TablesService.putTable`).
  2. Projects the commit (`TableMetadata.buildFrom(base)` + apply the requested updates) to
     obtain the final metadata.
  3. `translatePolicyPatch(base.properties().get("policies"), <patch>)` merges the patch onto
     the table's existing policy (see below).
  4. Builds a `CreateUpdateTableRequestBody` from the projected metadata (schema / partitioning
     / clustering / sortOrder / all other properties), with `updated.openhouse.policy` stripped
     from `tableProperties`, `.policies(mergedPolicies)`, and
     `baseTableVersion = base.metadataFileLocation()`. This mirrors exactly what the legacy
     client's `constructMetadataRequestBody` sends (it likewise filters only the policy carrier
     and sends all other properties unchanged).
  5. Calls the SAME `TablesApiHandler.updateTable` bean the native `/tables` PUT uses, so the
     reused pipeline runs `validateUpdateTable` → `validatePolicies` and persists via
     `TablePolicyManager.managePoliciesOnUpdateIfNeeded` into the reserved `policies` property.
  6. Returns a fresh `LoadTableResponse` (`CatalogHandlers.loadTable`).

Because the client's `SetProperties` only adds the (non-reserved-prefixed) `updated.openhouse.policy`
key and does not touch `policies` or `openhouse.*` directly, the reserved-property guard that the
native update path runs (`checkIfPreservedTblPropsModified`) sees the SAME reserved props before
and after (the carrier is stripped, the old `policies` string is carried through unchanged), so it
passes — while the actual policy change is applied separately from `tableDto.getPolicies()`. The
existing reserved-property protection for genuine `openhouse.*` / `policies` mutations is therefore
completely intact (verified by `testNonPolicyReservedPropStillRejected`).

### Server-side policy merge (`translatePolicyPatch`)

The stock RESTCatalog client cannot merge a partial policy onto the existing one (that was the
legacy client's `buildUpdatedPolicies` job). So the merge moves server-side. `translatePolicyPatch`
reproduces `buildUpdatedPolicies` field-by-field: for each sub-policy present in the patch
(`retention`, `sharingEnabled`, `columnTags`, `replication`, `history`, `lockState`) it overrides
the corresponding existing sub-policy; sub-policies absent from the patch are preserved. Parsing
reuses the shared `PoliciesSpecMapper` bean (`toPoliciesObject`) so the two lanes cannot drift.

One model nuance: the server `Policies.sharingEnabled` is a primitive `boolean` (it cannot carry a
tri-state null the way the legacy client's nullable `Boolean` did). So "is sharing present in this
patch?" is detected by inspecting the raw patch JSON for the `sharingEnabled` key (via Gson
`JsonObject.has`), exactly how the legacy client keyed off its nullable field. This keeps a
sharing update from clobbering an unrelated existing retention policy and vice-versa.

## Reused logic (NOT forked)

- `PoliciesSpecMapper.toPoliciesObject` — patch/existing JSON → `Policies` (parse).
- `TablesApiHandler.createTable` / `updateTable` — validation + service dispatch.
- `OpenHouseTablesApiValidator.validateCreateTable/validateUpdateTable` → `validatePolicies`
  → `RetentionPolicySpecValidator`, `ReplicationConfigValidator`, `HistoryPolicySpecValidator`.
- `TablesServiceImpl.putTable` → `OpenHouseInternalRepositoryImpl.save` update branch →
  `TablePolicyManager.managePoliciesOnUpdateIfNeeded` (persists into reserved `policies`).
- `PoliciesSpecMapper.toPoliciesJsonString` (via `computePropsForTableCreation`) on create.

The only genuinely new server code is: the property interception on create, the `isPolicyUpdate`
dispatch + `updatePolicy` on update, and the `translatePolicyPatch` merge (the server-side
reproduction of the legacy client's merge).

## Validation that applies

Identical to the native `/tables` path, because the same validators run. Notably the retention
validator requires a column pattern for a NON-timestamp-partitioned table
(`For non timestamp-partitioned table ..., column pattern in retention policy is mandatory`) — a
bare `{"retention":{"count":3,"granularity":"DAY"}}` on an unpartitioned table is a 400 on BOTH
lanes now. That parity is exactly the goal.

## Readback

A stock RESTCatalog load (`GET /iceberg/v1/namespaces/{ns}/tables/{table}` → `CatalogHandlers.loadTable`)
returns the table's Iceberg metadata whose `properties` include the reserved `policies` key
(OpenHouse persists the serialized `Policies` there). A REST client reads the policy as:

```java
table.properties().get("policies")   // serialized OpenHouse Policies JSON
```

or, in Spark SQL, `SHOW TBLPROPERTIES <t>` row with key `policies`. The `updated.openhouse.policy`
carrier is never present on readback.

## Verification

New test:
`integrations/spark/spark-4.0/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark/catalogtest/PolicyPropertyTestSpark4_0.java`
(extends `OpenHouseRestSparkITest`, drives the stock `RESTCatalog`).

| Test | Path exercised |
| --- | --- |
| `testSetRetentionPolicyOnUpdate` | UPDATE: retention (with column pattern) via `updateProperties()` → persisted + read back |
| `testSetSharingPolicyOnCreate` | CREATE: sharing carried in create props → persisted + read back |
| `testSetSharingPolicyOnUpdate` | UPDATE: sharing via `updateProperties()` → persisted + read back |
| `testSuccessivePolicyUpdatesMerge` | UPDATE ×2: retention then sharing → both survive (server-side merge) |
| `testNonPolicyReservedPropStillRejected` | UPDATE: `openhouse.tableType` change still rejected (guard intact) |

Targeted runs (JDK 17):

```
./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
  --tests "com.linkedin.openhouse.spark.catalogtest.PolicyPropertyTestSpark4_0"
# 5 tests, all PASSED

./gradlew :services:tables:test \
  --tests "*PoliciesSpecMapperTest" --tests "*TablePolicyManagerTest" \
  --tests "*RetentionPolicySpecValidatorTest" --tests "*HistoryPolicySpecValidatorTest"
# all PASSED (no server-side policy regression)
```

Also spot-checked no regression on the normal update/insert paths:
`CatalogOperationTestSpark4_0#{testCasingWithCTAS, testAlterTableSetSortOrder, testWriteOrderedByRoundTripsThroughInsert}` — GREEN.

## Follow-ups (out of scope here)

- The `CatalogOperationTestSpark4_0#testAlterTableUnsetReplicationPolicy` case remains `@Disabled`:
  it needs the custom `SET/UNSET POLICY` SQL DDL, which the stock Iceberg SQL extension cannot parse.
  A Spark-4.0 port of the OpenHouse SQL extension that lowers `SET POLICY` onto the
  `updated.openhouse.policy` property (this contract) would re-enable it. This change is the
  server-side foundation that port maps onto.
- `UNSET POLICY` (clearing a sub-policy) is not yet expressible: the contract here only overrides
  present sub-policies. A future carrier convention (e.g. an explicit null / tombstone) would extend
  it; the legacy `UnSetReplicationPolicyExec` used a separate code path.
