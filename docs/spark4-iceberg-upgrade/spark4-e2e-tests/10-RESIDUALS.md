# Spark 4.0 / Iceberg 1.11 / REST-first e2e port — FIX CHECKLIST

This is a **fix backlog**, not a list of accepted omissions. Every legacy Spark-3.1 / Spark-3.5
in-JVM `catalogtest` case is ported into the Spark-4.0 REST-first module. Cases that cannot be made
green on the REST lane today are still present in code but annotated
`@org.junit.jupiter.api.Disabled("<reason> — see spark4-e2e-tests/10-RESIDUALS.md")`, and each is
listed below with its exact failure, root cause, and the concrete fix that would make it pass. The
checkboxes are work items to triage and fix later.

The REST lane wires Spark to a STOCK `org.apache.iceberg.rest.RESTCatalog` pointed at the OpenHouse
`/iceberg/v1/*` controller. UPDATE: the custom OpenHouse Spark SQL extension is now ported to
Spark-4.0 / Scala-2.13 and registered on this lane (module
`:integrations:spark:spark-4.0:openhouse-spark-4.0-runtime_2.13`), so `ALTER TABLE ... SET POLICY
(...)` DDL (retention / replication / sharing / history) works end-to-end (see
`policy-sql-extension-spark4.md`). Still absent: `UNSET POLICY` clear semantics + `GRANT` / column-tag
execution (both need server endpoints); the OpenHouse Java client's `Policies` gen-model on the
compile classpath; and several OpenHouse-server-only semantics surfaced only through the custom
`/tables` client.

---

## Disabled cases (must be fixed)

- [ ] **`CatalogOperationTestSpark4_0.testAlterTableUnsetReplicationPolicy`** (PARTIALLY FIXED —
    `SET POLICY` now works; blocked on server support for `UNSET POLICY`)
  - UPDATE (SQL extension ported): the OpenHouse Spark SQL extension is now ported to
    Spark-4.0 / Scala-2.13 (module `:integrations:spark:spark-4.0:openhouse-spark-4.0-runtime_2.13`)
    and registered in `OpenHouseRestSparkITest.getBuilder`. `ALTER TABLE ... SET POLICY (...)` for
    retention / replication / sharing / history now parses and persists end-to-end on the REST lane —
    proven GREEN by `PolicySqlDdlTestSpark4_0` (4 tests). See
    `spark4-e2e-tests/policy-sql-extension-spark4.md`.
  - Remaining failure: this test additionally exercises `UNSET POLICY (REPLICATION)`.
    `UnSetReplicationPolicyExec` emits `updated.openhouse.policy = {"replication": {}}`, and the
    `/iceberg` server rejects it with HTTP 400
    `"...replication.config : Incorrect replication policy specified. Replication config cannot be
    null."` (empirically confirmed: the SET steps pass, the UNSET step throws).
  - Root cause: the server policy-merge (`translatePolicyPatch`) only OVERRIDES present sub-policies;
    it has no clear/tombstone convention for REMOVING one, so an empty replication object fails
    validation. This is the "UNSET POLICY not yet expressible" follow-up called out in
    `policy-rest-lane.md`.
  - Fix: the `/iceberg` server must accept a policy patch that CLEARS a sub-policy (explicit
    null/tombstone for `replication`); then remove `@Disabled` (the SET portions already pass). The
    readback stays against the raw `policies` string (no gen-model on this module's compile classpath).

- [ ] **`CatalogOperationTestSpark4_0.testRenameTableFailsConflict`**
  - Failure: `assertThrows(WebClientResponseWithMessageException)` — nothing was thrown; empirically
    `ALTER TABLE ... RENAME TO <existing>` succeeded and the source table was gone afterward (silent
    replace of the destination).
  - Root cause: the OpenHouse `/iceberg` REST rename endpoint does not reject a rename whose
    destination already exists (no 409 conflict); it upserts.
  - Fix: server-side, `/iceberg` `renameTable` must reject when the destination exists (HTTP 409) so
    the stock client raises `AlreadyExistsException`; then re-enable and assert that type.
  - INVESTIGATION NOTE (attempt 1): adding a `catalog.tableExists(request.destination())` guard in
    `IcebergRestCatalogController#renameTable` did NOT make the test throw — `spark.sql("ALTER TABLE
    … RENAME TO <existing>")` still succeeded silently. So the Spark-4.0 SQL rename path does not
    trip the `/tables/rename` controller guard as expected (either Spark resolves/renames without
    hitting that endpoint with the existing-destination identifier, or `tableExists(destination)`
    resolves to false there). Next attempt must first trace the actual REST request the Spark-4.0
    rename emits (log the endpoint + identifiers) before choosing where to enforce the 409.

## Inline omissions inside otherwise-green cases (custom SQL dropped, method kept green)

- [x] **`WapIdTestSpark4_0.testWapWorkflowWithVariousOperations`** — RESTORED the inline
  `ALTER TABLE ... SET POLICY (SHARING=TRUE)` (+ a `policies` readback assertion) now that the
  OpenHouse SQL extension is ported to this lane; test stays GREEN. The `GRANT SELECT ON TABLE ... TO
  lejiang` statement REMAINS dropped — GRANT has no server ACL endpoint on the REST lane (see the
  GRANT item in policy-sql-extension-spark4.md).
- [x] **`RTASTestSpark4_0.testRTAS`** — RESTORED the inline
  `ALTER TABLE ... SET POLICY (HISTORY MAX_AGE=24H)`. The `assertEquals("", ...policies)` assertion is
  NOT restored verbatim: BEHAVIORAL DELTA — the legacy custom-catalog lane cleared `policies` on RTAS,
  but the REST lane's `/iceberg` RTAS PRESERVES the pre-existing policy. Assertion updated to the
  verified REST-lane behavior (the HISTORY policy survives RTAS); test GREEN.
- [ ] **`CatalogOperationTestSpark4_0.testRenameTableCatalogApi`** — the
  `openhouse.tableUri == "local-cluster.db.rename_test_renamed"` assertion was removed (the
  `openhouse.*` server properties are not surfaced by the stock REST `loadTable`). Restore once the
  `/iceberg` REST `loadTable` response carries the `openhouse.tableUri` property.

## PENDING — results being confirmed empirically (updated below on the run)

<!-- INVALIDMETADATA -->
<!-- MULTISCHEMA -->

---

## CI performance (fix — makes every branch-1.11 run ~2x faster)

- [ ] **`InvalidMetadataTestSpark4_0.testCorruptSchemaIdSurfacesRealError` runs ~19 min** (single
  test), which alone pushes the branch-1.11 "Build with Gradle" step to ~33 min. Diagnosis: the test
  corrupts `current-schema-id` to a non-existent value, then INSERTs. Server-side `refreshMetadata`
  throws `InvalidTableMetadataException` (`OpenHouseInternalTableOperations` line ~163). The INSERT's
  Iceberg **commit-retry** loop treats it as retryable and retries with exponential backoff up to the
  default `commit.retry.total-timeout-ms` (~30 min), so the expected failure only surfaces after
  ~19 min. Fixes (either): (a) server-side — map invalid/corrupt-metadata to a NON-retryable 4xx so
  the client fails fast (correct: corrupt metadata is not a transient conflict); or (b) test-scoped —
  create the table with `commit.retry.num-retries=0` (or a short `commit.retry.total-timeout-ms`) so
  the INSERT fails fast. Prefer (a). The test itself asserts correct behavior and PASSES; this is
  purely a runtime cost.

## Behavioral / engine deltas (accepted — no fix needed, documented for traceability)

- **`WapIdTestSpark4_0.testExpireSnapshotsWithEmptyRefs`**: the 3.1 lane asserted that expiring the
  sole unpublished WAP snapshot (zero refs) "does nothing" (1 snapshot left). On stock Iceberg 1.11
  the unreferenced snapshot is correctly removed (0 left; still 0 rows). Assertion updated to 1.11
  behavior; test intent unchanged and green.
- **`PartitionTestSpark4_0.testCreateTablePartitionedWithNestedColumn`**: the `DESCRIBE TABLE`
  rendering of a nested identity transform is engine-specific — Spark-4.0 renders `bigint` /
  `truncate(10, header.time)` where Spark-3.1 rendered `header.time` / `truncate(header.time, 10)`.
  Expected strings updated to the 4.0 rendering (partition spec identical). Green.
- **`CatalogOperationTestSpark4_0`** Java-API create cases: the OpenHouse `/iceberg` controller NPEs
  on a null `PartitionSpec` (the legacy OpenHouseCatalog client defaulted it). Changed the passed
  spec from `null` to `PartitionSpec.unpartitioned()` — catalog-wiring adjustment only, table intent
  unchanged. Green. (Arguably a server robustness bug: `/iceberg` create should tolerate an absent
  spec; noted here but not blocking.)
- **`CTASNonNullTestSpark4_0`**: ported once (the 3.1 and 3.5 source classes were byte-identical).
  Spark-4.0 also leaves the CTAS target column nullable by default; assertion unchanged. Green.
</content>
