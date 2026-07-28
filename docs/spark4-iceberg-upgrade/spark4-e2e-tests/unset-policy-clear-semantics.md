# Fix: `UNSET POLICY (REPLICATION)` on the Spark-4.0 / Iceberg-1.11 REST lane

Status: FIXED. Test `CatalogOperationTestSpark4_0#testAlterTableUnsetReplicationPolicy` re-enabled
and GREEN.

## Symptom

On the Spark-4.0 / Iceberg-1.11 REST-first lane, `ALTER TABLE ... UNSET POLICY (REPLICATION)` was
rejected by the OpenHouse `/iceberg` server with HTTP 400:

```
CreateUpdateTableRequestBody.policies.replication.config : Incorrect replication policy specified.
Replication config cannot be null.
```

The `SET POLICY (...)` steps of the covering test already passed (proven by
`PolicySqlDdlTestSpark4_0`); only the `UNSET` step threw, so
`testAlterTableUnsetReplicationPolicy` was `@Disabled`.

## What the client emits

The Spark-4.0 extension exec
`integrations/spark/spark-4.0/openhouse-spark-runtime/.../v2/UnSetReplicationPolicyExec.scala`
sets the policy-carrier table property (byte-identical to the legacy 3.1/3.5 exec):

```
updated.openhouse.policy = {"replication": {}}
```

i.e. the sub-policy is present in the patch as an **empty object**.

## Root cause

Server-side, `IcebergRestCatalogController.translatePolicyPatch` (services/tables) reproduces the
legacy client's policy-merge: each sub-policy present in the patch OVERRIDES the corresponding
existing sub-policy. For replication it did:

```java
if (patch.getReplication() != null) { merged.replication(patch.getReplication()); }
```

`policiesSpecMapper.toPoliciesObject("{\"replication\":{}}")` deserializes (via the server
`Policies`/`Replication` model) to a **non-null** `Replication` whose `config` list is **null**
(Lombok field, no default). The merge therefore replaced the real replication with a null-config
`Replication`. The `@NotNull` constraint on `Replication.config`
(`.../api/spec/v0/request/components/Replication.java:24`, "Replication config cannot be null.")
is then evaluated by the javax bean-validator cascade in
`OpenHouseTablesApiValidator.validateUpdateTable` (`validator.validate(createUpdateTableRequestBody)`
→ `@Valid Policies.replication` → `@NotNull Replication.config`) and rejects the commit with 400.

The merge had an OVERRIDE convention but no CLEAR/tombstone convention for REMOVING a sub-policy, so
an empty replication object was indistinguishable from an invalid one.

## Native-lane parity finding

The legacy native `/tables` lane had the SAME merge structure in
`OpenHouseTableOperations.buildUpdatedPolicies`
(`integrations/java/iceberg-1.2/openhouse-java-runtime/...`):

```java
if (patchUpdatedPolicy.getReplication() != null) { policies.replication(...); }
```

But it did NOT 400. The reason is a difference in the **generated client** model:
`build/tableclient/generated/.../tables/client/model/Replication.java` initializes
`private List<ReplicationConfig> config = new ArrayList<>();`. Gson only overwrites fields present
in the JSON, so deserializing `{"replication":{}}` on the client leaves `config` as an **empty
list**, and the client silently sent `{"replication":{"config":[]}}`. That passes `@NotNull`
(empty list ≠ null), so the legacy `catalogtest` (`CatalogOperationTest#testAlterTableUnsetReplicationPolicy`)
reads back `updatedPolicy.getReplication().getConfig().size() == 0` — replication PRESENT with an
empty config list, not removed.

The server-side `Policies`/`Replication` model
(`services/tables/.../api/spec/v0/request/components/Replication.java`) has **no** such default,
so on the REST lane the same empty object deserializes to `config == null`. In other words: the
native lane never truly "cleared" replication — it stored an empty-but-valid config object, purely
as an accident of the client gen-model's list default.

## The fix

`IcebergRestCatalogController.translatePolicyPatch` now interprets an empty sub-policy object in the
raw patch JSON as a clear/tombstone: it drops the sub-policy from the merged `Policies` (sets it to
`null` on the builder) rather than overriding with an invalid empty object. Detection is on the raw
patch JSON, because the parsed server model cannot distinguish an absent sub-policy from an
empty-but-present one:

```java
private static boolean isClearedSubPolicy(JsonObject patchObj, String name) {
  return patchObj.has(name)
      && patchObj.get(name).isJsonObject()
      && patchObj.getAsJsonObject(name).size() == 0;
}
```

The rule is applied to the object sub-policies **replication, retention, history, columnTags**
(`sharingEnabled` is a primitive boolean, not an object — unchanged). A real `SET` always carries a
non-empty object, so its override branch is untouched; only replication has an `UNSET` DDL exec
today, the others get the same rule defensively for consistency. The merge now also builds from a
fresh `Policies.builder()` when the table has no existing policy, so an `UNSET` on a table that
never had the sub-policy (test case `tttest1`) also strips the empty object instead of persisting an
invalid one.

Because the cleared replication becomes `null`, the `@NotNull` config cascade and
`ReplicationConfigValidator` are never triggered on a clear.

Divergence vs. native lane (documented, intentional): the REST lane REMOVES the sub-policy outright
(stored `policies` no longer carries a `replication` key), whereas the native lane left
`{"replication":{"config":[]}}`. Both mean "no active replication"; the difference is not observable
through the behavioral test. The REST behavior is the cleaner of the two — the native empty-list
form was an artifact of the client gen-model default, not a deliberate design.

## Test

`testAlterTableUnsetReplicationPolicy` is re-enabled (`@Disabled` removed, unused import dropped).
Readback is against the raw `policies` table-property string (there is no `Policies` gen-model on
this itest module's compile classpath). The post-UNSET assertion now checks that the serialized
policy no longer carries the replication config (`!contains("WAR")`, `!contains("replication")`)
while retention is preserved (`contains("yyyy-MM-dd")`) — matching the actual REST-lane end-state
rather than the native lane's empty-list form.

## Verification (targeted, all GREEN)

Server guard (shared `Policies` model + validators):

```
./gradlew :services:tables:test --tests "com.linkedin.openhouse.tables.e2e.h2.TablesControllerTest"
  BUILD SUCCESSFUL   (incl. testUpdatePolicies, testUpdateSucceedsForReplicationConfig,
                      testUpdateSucceedsForReplicationAndRetention,
                      testUpdateSucceedsForMultipleReplicationConfig, testUpdateSucceedsForHistoryPolicy)
```

End-to-end (embedded OpenHouse server; the UNSET fix + no SET regression):

```
./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
  --tests "*.CatalogOperationTestSpark4_0.testAlterTableUnsetReplicationPolicy" \
  --tests "*.PolicySqlDdlTestSpark4_0" --tests "*.PolicyPropertyTestSpark4_0"

  CatalogOperationTestSpark4_0 > testAlterTableUnsetReplicationPolicy() PASSED
  PolicyPropertyTestSpark4_0 > testNonPolicyReservedPropStillRejected() PASSED
  PolicyPropertyTestSpark4_0 > testSetRetentionPolicyOnUpdate() PASSED
  PolicyPropertyTestSpark4_0 > testSuccessivePolicyUpdatesMerge() PASSED
  PolicyPropertyTestSpark4_0 > testSetSharingPolicyOnCreate() PASSED
  PolicyPropertyTestSpark4_0 > testSetSharingPolicyOnUpdate() PASSED
  PolicySqlDdlTestSpark4_0 > testSetReplicationPolicyViaSqlDdl() PASSED
  PolicySqlDdlTestSpark4_0 > testSetRetentionPolicyViaSqlDdl() PASSED
  PolicySqlDdlTestSpark4_0 > testSetHistoryPolicyViaSqlDdl() PASSED
  PolicySqlDdlTestSpark4_0 > testSetSharingPolicyViaSqlDdl() PASSED
  BUILD SUCCESSFUL
```

## Files changed

- `services/tables/.../controller/IcebergRestCatalogController.java` — clear/tombstone in
  `translatePolicyPatch` + `isClearedSubPolicy` helper.
- `integrations/spark/spark-4.0/openhouse-spark-itest/.../catalogtest/CatalogOperationTestSpark4_0.java`
  — `@Disabled` removed, post-UNSET assertions + javadoc updated.
- `docs/spark4-iceberg-upgrade/spark4-e2e-tests/10-RESIDUALS.md` — item moved to FIXED.
- `docs/spark4-iceberg-upgrade/spark4-e2e-tests/unset-policy-clear-semantics.md` — this doc.
</content>
</invoke>
