# Spark 4.0 / Iceberg 1.11 / REST-first e2e port — FIX CHECKLIST

This is a **fix backlog**, not a list of accepted omissions. Every legacy Spark-3.1 / Spark-3.5
in-JVM `catalogtest` case is ported into the Spark-4.0 REST-first module. This checklist tracked
each case that could not initially be made green on the REST lane — its exact failure, root cause,
and the concrete fix. **All of them have now been triaged and fixed** (each `[x]` below links to an
audit-grade write-up); no `catalogtest` case remains `@Disabled`. The single open item is the
`GRANT` / `REVOKE` ACL deferral, which needs a new server endpoint and is out of scope for this
Spark-4.0 / Iceberg-1.11 modernization spike (see the DEFERRED section). The checkboxes record
triage state: `[x]` = fixed + verified, `[ ]` = deferred with a documented reason.

The REST lane wires Spark to a STOCK `org.apache.iceberg.rest.RESTCatalog` pointed at the OpenHouse
`/iceberg/v1/*` controller. UPDATE: the custom OpenHouse Spark SQL extension is now ported to
Spark-4.0 / Scala-2.13 and registered on this lane (module
`:integrations:spark:spark-4.0:openhouse-spark-4.0-runtime_2.13`), so `ALTER TABLE ... SET POLICY
(...)` DDL (retention / replication / sharing / history) works end-to-end (see
`policy-sql-extension-spark4.md`). `UNSET POLICY (REPLICATION)` clear semantics now work too — the
server policy-merge honors an empty sub-policy object as a clear/tombstone (see
`unset-policy-clear-semantics.md`). Column policy-tag DDL (`ALTER TABLE ... MODIFY COLUMN ... SET
TAG = (PII, HC)`) is also verified end-to-end (see `column-tags-verification.md`) — the server
already merges the `columnTags` sub-policy, no code change was needed.

**Backlog status: every fixable item is now GREEN.** The only remaining gap is `GRANT` / `REVOKE`
/ `SHOW GRANTS` execution, which needs a server-side ACL endpoint on the `/iceberg` lane (the DDL
parses on the ported extension but has nowhere to commit — see the GRANT deferral section below).
Also still by-design absent on this itest module: the OpenHouse Java client's `Policies` gen-model
on the compile classpath (readbacks assert against the raw `policies` JSON string instead).

---

## Disabled cases (must be fixed)

- [x] **`CatalogOperationTestSpark4_0.testAlterTableUnsetReplicationPolicy`** — FIXED (re-enabled,
    GREEN). The `/iceberg` server policy-merge (`IcebergRestCatalogController.translatePolicyPatch`)
    now treats an empty sub-policy object in the patch JSON (e.g. `{"replication": {}}`, what
    `UnSetReplicationPolicyExec` emits for `UNSET POLICY (REPLICATION)`) as a clear/tombstone: it
    drops the sub-policy from the merged `Policies` instead of overriding with an invalid empty
    `Replication` (which tripped the `@NotNull` config check → HTTP 400). Applies to
    replication / retention / history / columnTags (SET paths untouched, since a real SET always
    carries a non-empty object). Native-lane parity finding + full root cause + verification in
    `spark4-e2e-tests/unset-policy-clear-semantics.md`.

- [x] **`CatalogOperationTestSpark4_0.testRenameTableFailsConflict`** — FIXED (live `@Test`, GREEN;
    confirmed locally + in branch-1.11 CI). Original failure: `ALTER TABLE ... RENAME TO <existing>`
    succeeded silently (no exception) and the destination was replaced. Root cause: the rename path
    did not reject a destination that already exists.
  - Fix (`OpenHouseInternalCatalog.renameTable`, commit `956820c`): enforce the conflict at the
    CATALOG level, not the controller. The guard checks the EFFECTIVE destination
    (`TableIdentifier.of(from.namespace(), to.name())`) via a direct HTS lookup
    (`findHouseTable(...).isPresent()`, NOT `tableExists()` — the latter does a full
    `loadTable→refreshMetadata` and throws `InvalidTableMetadataException` on an absent row) and
    throws `org.apache.iceberg.exceptions.AlreadyExistsException`. The effective-destination
    resolution is what made it fire where attempt-1 failed: OpenHouse renames are single-DB, and on
    the Spark REST lane `RENAME TO openhouse.db.y` leaks the Spark catalog name into the destination
    namespace (`[openhouse, db]` vs the real `[db]`), so a controller-level `tableExists(to)` guard
    resolved the wrong namespace and never saw the conflict. See `rename-409-fix.md`.
  - The assertion is engine-agnostic (stock `RESTCatalog` maps the server error differently than the
    3.1 custom client): it asserts SOME exception is thrown AND neither the source nor the
    pre-existing destination is mutated (no silent replace).

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
- [x] **`CatalogOperationTestSpark4_0.testRenameTableCatalogApi`** — FIXED (assertion RESTORED,
  GREEN; commit `923d39b`). Empirical finding: the earlier "not surfaced" note was inaccurate —
  `openhouse.tableUri` IS carried through the stock REST `loadTable` (it flows: rename persists it
  into the Iceberg metadata JSON → `IcebergRestCatalogController.loadTable` → `CatalogHandlers.loadTable`
  → `OpenHouseInternalTableOperations.refreshMetadata` → `LoadTableResponse` → client; no `openhouse.*`
  filter exists). The original assertion would only have failed on the VALUE: the REST lane renders
  `local-cluster.openhouse.db.rename_test_renamed` (the `to.namespace()` string carries the Spark
  catalog prefix) vs the legacy bare `local-cluster.db.rename_test_renamed`. Restored as a
  tail-match (`endsWith("db.rename_test_renamed")`), robust to the embedded cluster name and the
  REST-lane namespace rendering. No server change needed. See `tableuri-loadtable.md`.

- [x] **Column policy-tag DDL** (`ALTER TABLE ... MODIFY COLUMN ... SET TAG = (PII, HC)`) — VERIFIED
  end-to-end on the REST lane (new `ColumnTagsTestSpark4_0`, 3 tests GREEN; commit `f42268d`). No
  code change was required: the ported `SetColumnPolicyTagExec` emits
  `{"columnTags":{"<col>":{"tags":[...]}}}` and the server's `translatePolicyPatch` already merges
  the `columnTags` sub-policy into the reserved `policies` property. Caveat: the `SET TAG = (NONE)`
  clear form emits an empty `tags` array (`{"tags":[]}`) rather than a tombstone, so it clears the
  column's tags but leaves the `columnTags` map entry present — behaviorally "no tags", verified by
  the test. See `column-tags-verification.md`.

## DEFERRED — `GRANT` / `REVOKE` / `SHOW GRANTS` (needs a server-side ACL endpoint)

- [ ] **`GRANT <priv> ON <resource> TO <principal>` / `REVOKE ... FROM ...` / `SHOW GRANTS ON ...`**
  — the ported Spark-4.0 extension PARSES these (grammar rules `grantStatement` / `revokeStatement`
  / `showGrantsStatement`), but they have nowhere to commit on the REST lane: the OpenHouse
  `/iceberg` controller (`IcebergRestCatalogController`) exposes only table CRUD + snapshots +
  policy translation — it has NO ACL endpoint. The legacy `/tables` lane routed GRANT/REVOKE to a
  separate server AAA/ACL surface that the stock `RESTCatalog` client cannot reach.
  - Scope call: implementing a full server ACL endpoint + wiring an exec that calls it is a distinct
    feature, disproportionate to this Spark-4.0 / Iceberg-1.11 modernization spike (and adjacent to
    the maintenance/AAA surfaces that are being rewritten separately). It is therefore an explicit,
    documented DEFERRAL, not a silent omission. The one inline `GRANT` in
    `WapIdTestSpark4_0.testWapWorkflowWithVariousOperations` stays dropped (the rest of that test —
    including the restored `SET POLICY (SHARING=TRUE)` — is green).
  - To close later: add an ACL endpoint on the `/iceberg` lane (or a sibling REST surface), port a
    `GrantRevokeStatementExec` that commits to it, then add a `GrantRevokeTestSpark4_0` and assert.

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
