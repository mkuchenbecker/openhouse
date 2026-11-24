# Native column defaults on OpenHouse's 1.12 line: design

Adopted by `13-decisions.md` D11 with the changes listed there. Written by a delegated agent from code reading and one Spark 3.5 experiment.
Corrected by running (iceberg pull request 11): Spark 4.2 ORC readers already opt in and fill defaults, like Spark 3.5; statements below that Spark 4.2 ORC returns NULL or would error are wrong. On Spark 3.5 a write does not apply write defaults (INSERT with DEFAULT writes NULL, and a column-list INSERT that omits the column fails); Spark 4.2 applies them.


Delegated agent, 2026-11-22. Marks: **[ran]** = run in my worktree `/home/user/iceberg-def` (detached at `irc/reapply-all` f180ae589, experiment test `spark/v3.5/spark/src/test/java/org/apache/iceberg/spark/source/TestNativeDefaultsExperiment.java`, uncommitted; output in scratchpad `natdef-run1.txt`, `natdef-run2.txt`). **[read]** = from code at the cited line. **[scout]** = from the i6 scout's logs. **[inferred]** = reasoning, not run. OpenHouse was not built. Nothing was pushed or committed.

## Recommended design (bottom line)

On the new line, column defaults are native Iceberg v3 `initial-default`/`write-default`, stored in table metadata as committed. OpenHouse's read bridge stays on the legacy line only, as decision D5 in `13-decisions.md` already says. The new-line client neither overlays `openhouse.read-bridge.column-default.*` nor declares `column-defaults`. A table with `required-capability.column-defaults=true` is therefore refused with the existing 422 `CLIENT_CAPABILITY_MISSING` on the new line. It stays a valid v2 table for every legacy client, because nothing writes to it from the new line. A bridged table moves to the new line only through an explicit, per-table, operator-run migration. The migration translates its stored defaults into `initial-default`, upgrades it to v3 and removes the opt-in, all in one commit. That is a one-way door that Mike approves per non-test table (D10). Setting a default is allowed only on v3 tables. It works through the Iceberg API today; Spark SQL DDL cannot set defaults on 3.5 or 4.2 without a fork patch. On v2 the server refuses it, including write-default-only changes that Iceberg 1.12 lets through. The server, once on 1.12, stops stripping `initial-default` on v3 tables and validates it instead. Parquet and Avro need no patches. Keep the three ORC patches, because the existing bridged tables are the ones that need ORC (only ORC filled defaults on LinkedIn's line). Change one thing in them: ORC readers that do not opt in must fail as upstream does, not return NULL.

## User-observable behavior

Q1, today (LinkedIn line: OpenHouse `irc-integration` bb8929e1 on Iceberg 1.5.2):

| Action | Today | Evidence |
|---|---|---|
| Set a default | No Spark SQL statement sets one. Defaults come from a deployment bean, `ColumnDefaultsSource.defaults(TableDto)` (keyed by field id). They apply only when the table property `required-capability.column-defaults=true` is set. OSS ships only a test bean that stamps `country`→US and `tier`→1 by name | [read] `ColumnDefaultsSource.java:12-19`, `ReadBridgeConfigResolver.java:105-108`, `SparkItestColumnDefaults.java:22-61` |
| `ALTER TABLE ADD COLUMN c int DEFAULT 1` (Spark 3.5) | Column added; the DEFAULT clause is silently dropped (1.5.2 `Spark3Util.apply(AddColumn)` never reads `defaultValue()`) | [read] li/openhouse-1.5.2 `Spark3Util.java:231-238` |
| `CREATE TABLE ... (c int DEFAULT 1)`, `ALTER COLUMN SET/DROP DEFAULT` (3.5) | CREATE: refused by Spark, since SparkCatalog declares no `SUPPORT_COLUMN_DEFAULT_VALUE`. ALTER COLUMN: `Cannot apply unknown table change` | [inferred] from the same code on 1.12 [ran] and the 1.5.2 dispatch at `Spark3Util.java:208` [read] |
| Stored / sent / overlaid | The server strips every `initial-default` from every incoming schema. It sends defaults per load as `config` entries. The client overlays `initial-default` only (never `write-default`) via toJson→patch→fromJson | [read] `ReadBridgeStripProtection.java:78,170,198`; `ReadBridgeConfigResolver.java:69-72`; client `ReadBridge.java:66-94` |
| Read old **Parquet** / Avro files | NULL | [read] `ColumnDefaultSparkITest.java:108-114` (@Disabled) |
| Read old **ORC** files (row or vectorized) | The default, from LinkedIn patches #267-269; a vectorized scan falls back to the row reader | [scout] 5 tests pass on 1.5.2 |
| INSERT omitting the column | Not possible by column list on Spark 3.5 ("Cannot find data for the output column"). `VALUES (..., DEFAULT)` gives NULL. No write-default exists | [ran] on 1.12/3.5, same Spark; [inferred] for 1.5.2 |
| Change or drop a default | No SQL. The deployment changes its source. Because the overlay is applied at read time, the new value retroactively rewrites every old ORC row. A commit that omits a stamped default is rejected with `COLUMN_DEFAULT_REMOVED`. The opt-in is immutable once true | [read] `ReadBridgeStripProtection.java:130-153`, `ColumnDefaultCommitPolicy.java:72-83` |
| Client without the capability | 422 on load and write; also rejected on snapshot commits (Type 2) | [read] `ClientCapabilityGate.java:174-190`, `TablesServiceImpl.java:86,172` |

Q2 plus the design (new line: Iceberg 1.12 fork, v3 table, native defaults):

| Action | Spark 3.5 | Spark 4.2 | Evidence |
|---|---|---|---|
| `ALTER TABLE ADD COLUMN c int DEFAULT 7` | `UnsupportedOperationException: Cannot add column c7 since setting default values in Spark is currently unsupported` | same | [ran] 3.5; [read] 4.2 `Spark3Util.java:234-238`, `TestSparkDefaultValues.java:158-173` |
| `CREATE TABLE ... DEFAULT 'q'` | `AnalysisException [UNSUPPORTED_FEATURE.TABLE_OPERATION] ... does not support column default value` | same | [ran] 3.5; [read] 4.2 test :143-156 |
| `ALTER COLUMN x SET DEFAULT` / `DROP DEFAULT` | `Cannot apply unknown table change: ...UpdateColumnDefaultValue` | same | [ran] 3.5; [read] 4.2 `Spark3Util.java:206` |
| API `addColumn(name, type, Literal)` | Sets both `initial-default` and `write-default` on v3; rejected on v2 with `Invalid schema for v2: - Invalid initial default ... not supported until v3` | n/a | [ran]; [read] `SchemaUpdate.java:181-182`, `Schema.java:619-626` |
| API `updateColumnDefault` | Changes `write-default` only (US→XX); `initial-default` stays US. **Also accepted on v2**, which persists `"write-default":"W"` in v2 metadata | n/a | [ran]; [read] `SchemaUpdate.java:322-339`; `checkCompatibility` checks only initial-default |
| ADD/RENAME/DROP COLUMN (no default) on a v3 table that has defaults | OK | OK | [ran] 3.5 |
| Read old Parquet files | Default, row and vectorized; `country='US'` returns the old row; `IS NULL` returns only explicit NULLs | same code | [ran] 3.5; [read] `ParquetValueReaders.java:272-287`, `VectorizedReaderBuilder.java:128-133` |
| Read old Avro files | Default | same | [read] `avro/ValueReaders.java:315-316` |
| Read old ORC files | Default with the three patches (vectorized falls back to the row reader); without them, `UnsupportedOperationException: ORC cannot read default value` | **NULL, silently** (patched; 4.2 does not opt in). Upstream: the same throw | [ran] 3.5; [read] `SparkFormatModels.java` 3.5:65 vs 4.2:69,78; upstream `ORCSchemaUtil.java:407-411` |
| Generic or Flink ORC readers | NULL, silently (patched) | | [ran] generic `[1,null]`; [read] `GenericFormatModels.java:61`, `FlinkFormatModels.java:54` |
| `INSERT ... VALUES (..., DEFAULT)` | NULL: Iceberg 3.5 does not expose write-default to Spark | write-default | [ran] 3.5 rows 5 and 7 are NULL; [read] 4.2 `TypeToSparkType.java:84-95`, test :50-141 |
| `SET TBLPROPERTIES('format-version'='3')` | Upgrades v2→v3; going back fails with `Cannot downgrade v3 table to v2`. Old v2 rows then read a later-added default | | [ran] |
| Bridged v2 table, any statement (design) | 422 `CLIENT_CAPABILITY_MISSING` | same | [read] gate; [inferred] for the new-line client after W3 |

## Decisions

**3a. Existing v2 tables with OpenHouse defaults.** *Chosen: no overlay and no capability on the new line, so the table is refused on load and write. The way across is the explicit migration (W8).*
- What the user observes: a clear 422 that names the table and the capability. The table is untouched and fully valid for legacy clients. No answer anywhere changes silently.
- Rejected, overlay but strip before schema changes. Reads would succeed, but the same Parquet or Avro table would return defaults on new clients and NULL on legacy ones. Spark 4.2 and Flink ORC would return NULL silently. Every schema path would need an overlay-stripping base (the 1.12 rejection comes from `TableMetadata.Builder.addSchemaInternal` on the client, as the e3 row of `10-v2-defaults.md` and the scout's failures at the second ALTER, ITest lines 42/86/133, show [scout]). It also keeps the mechanism D5 retires, with `initial-default` values that can still change.
- Rejected, new client declares the capability without overlaying. ORC old rows would read NULL, and the client would pass Type 2 and write to the table. That is silent and wrong.
- Rejected, upgrade to v3 automatically on first new-line touch. Legacy 1.5.2 and 1.2 clients and the 1.5.2 service cannot load v3 at all (`Unsupported format version: v3`, li/openhouse-1.5.2 `TableMetadata.java:54,298-301`; li/openhouse-1.2.0 :54,283 [read]). That is an irreversible cut-off made silently.

**3b. Setting a default.** *Chosen: v3 tables only, never an implicit upgrade.*
- The Java API works now. Spark SQL needs fork patch W9: ADD COLUMN DEFAULT sets initial and write defaults; SET/DROP DEFAULT changes write-default only; CREATE needs the catalog capability.
- On v2: Iceberg already refuses `initial-default` on the client [ran]. The server must also refuse a non-null `write-default`, which Iceberg 1.12 accepts on v2 [ran]. Suggested message: `COLUMN_DEFAULT_REQUIRES_V3: db.t is format v2; native column defaults need format v3, and upgrading is irreversible. Ask the OpenHouse team.`
- Nothing upgrades implicitly. Iceberg upgrades on a `format-version` property commit [ran]. Today the server forces the cluster version on create and replace (`OpenHouseInternalRepositoryImpl.java:155,188,769-771` [read]). Its update path is unknown, so W10 makes it refuse `format-version` changes outside the explicit upgrade.
- Rejected, upgrade a table to v3 when a default is first set. That is a one-way door hidden inside a DDL statement.

**3c. Server.** *Chosen: on the 1.12 service, v3 tables persist both defaults unchanged and send no read-bridge config.*
- It validates:
  - (i) an existing field id's `initial-default` never changes, since core does not check this on `setCurrentSchema`/`addSchema` (grep of `TableMetadata.java` finds no `initialDefault`) [read];
  - (ii) only a field new in this commit (id > base `lastColumnId`) may carry a non-null `initial-default`;
  - (iii) `required-capability.column-defaults` is refused on v3;
  - (iv) no non-null `write-default` below v3;
  - and it applies all of these to intermediate schemas too.
- `ReadBridgeStripProtection.prepare` strips every `initial-default` from every table today (`:78`, `:170-216`). On v3 it must not strip, or native defaults vanish and reads return NULL [inferred].
- The stored-defaults mechanism (`ColumnDefaultsSource`, config, Type 1/Type 2) stays for legacy v2 tables only. It is translated only by W8.
- Native defaults need the service on 1.12: a 1.5.2 service cannot load v3 metadata [read].
- Rejected, keep the server as the source of defaults and translate them at load time. That is the OpenHouse mechanism again, it breaks IRC clients that expect defaults in metadata, and the `initial-default` values could still change.

**3d. Capability.** *Chosen: `column-defaults` remains a legacy-line contract.*
- New-line clients do not declare it. The spark-3.5 `OpenHouseCatalog.java:28-29` declares it unconditionally today, so the opt-in build needs a version-specific override. The base 1.5 runtime declares none (`iceberg-1.5 OpenHouseCatalog.java:209-216`).
- Native defaults need no capability: format v3 is itself the gate, because old readers cannot load v3.
- W8 removes the opt-in property in the migration commit under the policy bypass, since the property is immutable (`ColumnDefaultCommitPolicy.java:72-83`).
- Optional: change the 422 remedy text for new-line clients to "migrate to native v3 defaults or use the legacy client".
- Rejected, a new `required-capability.native-defaults`. It adds nothing that v3 does not already enforce.

**3e. ORC patches.** *Chosen: keep all three, and change the non-opt-in path back to upstream's throw.*
- Parquet and Avro need none of them [ran/read].
- Without 1b8361f00 (reapplied as 294029a14), every ORC read of a file that lacks a defaulted column throws (upstream `ORCSchemaUtil.java:407-411`). That breaks migrated bridged tables, which are mostly ORC because only ORC filled defaults on LinkedIn's line [inferred].
- With the patch, Spark 3.5 fills the default [ran]. Readers that do not opt in (generic [ran], Spark 4.2 and Flink [read]) silently return NULL, because of `isOmittableDefault` (`ORCSchemaUtil.java:383-392`) and the null-synthesizing default branch at `:491-495`. Name-mapped files take the same NULL path even for Spark 3.5, via `hasAllIds` at `OrcIterable.java:94`.
- Amendment: when a field has a non-null `initial-default` and is not omitted, throw as upstream does.
- 4df974156 (c88162714) stays with 1b8361f00.
- 39df55d00 (61dbdbeff) is required with 1b8361f00. Its SARG guard keeps `country='US'` from pruning old rows ([ran] returns id 1). Its `PruneColumns` hunk is format-agnostic and harmless.
- Spark 4.2 ORC opt-in is deferred (Parquet first). Until then, with the amendment, 4.2 fails loudly.
- Rejected, drop the patches: that blocks the tables that have defaults today. Rejected, keep them as they are: that leaves silent NULLs.

**3f. The 8 tests** ([scout] failures; line numbers in the ITest file).

| Test | Asserts | Under the design |
|---|---|---|
| `ColumnDefaultSparkITest.readBridgeOverlaysInitialDefaultAfterSparkAddColumn` (:30-61; fails at the 2nd ALTER, :42) | Two ALTER ADD COLUMNs on a bridged table, then the overlaid `initial-default` US/1 appears in `table.schema()` | Legacy-only: exclude from the opt-in build. New-line counterpart asserts a deliberately different result: creating with the opt-in property → 422 `CLIENT_CAPABILITY_MISSING` |
| `orcFillsDefaultsInsideListsAndMaps` ×2 (:69-100; :86) | Nested list-element/map-value defaults filled by ORC, row and vectorized | Legacy-only. Port to native: v3 table, defaults through the API, Spark 3.5 ORC (needs the 1.12 service) |
| `orcBackfillsColumnDefaultsViaReadBridge` ×2 (:102-106,116-158; :133) | Old rows read US/1, new explicit values and NULLs kept, filters correct | Legacy-only. Port to native for **Parquet first** (replaces the @Disabled Parquet case :108-114), then ORC |
| `OpenHouseTableOperationsTest.testDoRefreshSkipReloadStillSendsStampedDefaults` (:629) | Bound config survives a config-less reload, and the PUT body keeps `initial-default` | Fails in fixture `tableWithSchema` (:885, `newTableMetadata` on v2). Port the fixture to v3 on the new line; assertions unchanged (the config is still captured even though it is not overlaid) |
| `constructMetadataRequestBody_sendsStampedIdsKeepsUnstampedColumnDefaults` (:839) | The PUT body sends schema defaults as given | Same fixture port; passes unchanged; this is native passthrough |
| `constructMetadataRequestBody_withoutConfigLeavesWriterDefaults` (:865) | Same, with no config | Same |

The fixture must be line-specific: 1.5.2 cannot build v3 metadata. That is the same shared-test-source problem the scout hit with `ReadBridgeTest.java`.

## One-way doors (each goes to Mike, per D10)

1. **Upgrading a table to v3**, whether by migration or by property. It is irreversible ([ran] `Cannot downgrade v3 table to v2`). Every legacy reader and writer of that table (Iceberg 1.5.2, 1.2, Spark 3.1, the 1.5.2 service) stops working with `Unsupported format version: v3` [read]. The upgrade also changes the answers for old Parquet and Avro rows from NULL to the default ([ran] `[1,US]` after the upgrade). Never automatic.
2. **Translating stored defaults into `initial-default`.** Once committed, the value is immutable in Iceberg's model. The deployment source can no longer change it, and W6's server rule enforces that.
3. **Writing native defaults into a table's metadata at all.** Removing a field's `initial-default` later is not a supported Iceberg operation.

The fork patches (ORC amendment, Spark DDL), the client capability change and the server rules are all two-way.

## Behavior differences from LinkedIn's line ("statement → old → new")

1. Any SELECT, INSERT or ALTER on a table with `required-capability.column-defaults=true`, from new-line Spark → old: works (ORC defaults filled, Parquet NULL) → new: 422 `CLIENT_CAPABILITY_MISSING`.
2. `CREATE TABLE ... TBLPROPERTIES('required-capability.column-defaults'='true')` → old: bridged table created → new: 422.
3. `ALTER TABLE ADD COLUMN c int DEFAULT 1` (3.5) → old: column added, DEFAULT silently ignored → new: `UnsupportedOperationException ... currently unsupported`. After W9: the default is applied on v3, and on v2 the statement is refused with the v3 message.
4. SELECT of old Parquet or Avro rows on a table with a default → old: NULL → new (v3 native): the default.
5. SELECT of old ORC rows on Spark 4.2 or Flink with a default → old: n/a → new: an error (after the ORC amendment) instead of NULL.
6. `INSERT ... VALUES (..., DEFAULT)` → old: NULL → new: NULL on 3.5, write-default on 4.2.
7. A default change by the deployment source → old: retroactively changes old ORC rows → new: impossible for `initial-default`. Changing `write-default` affects only future writes (API now, SQL after W9).
8. API `updateColumnDefault` on a v2 table → old (1.5.2): no such API → new: Iceberg accepts it, the server refuses it (W5).

## Work items (tests written first; ib = Iceberg fork, oh = OpenHouse)

| # | Repo | Change | Needs | Proof | When |
|---|---|---|---|---|---|
| W1 | ib | Amend reapplied 1b8361f00 and 4df974156: an ORC reader that does not opt in, or a name-mapped file, throws upstream's `ORC cannot read default value` for an absent field with a non-null `initial-default` | none | New test: generic ORC read of a v3 table → expects the throw (today `[1,null]` [ran]); ORC and Spark 3.5 ORC default suites still pass | **Now** |
| W2 | ib | Commit a proper Spark 3.5 test that pins native-default behavior: Parquet/Avro/ORC × row/vectorized fills, DEFAULT keyword NULL on 3.5, DDL refusals, v2 refusal, no downgrade (from `TestNativeDefaultsExperiment`) | none | Green on the fork; it documents the 3.5 surface | **Now** |
| W3 | oh | Opt-in 1.12 build: the new-line client neither overlays (`loadMetadata` skips `ReadBridge`) nor declares `column-defaults` (version-specific override of `capabilities()`); the legacy build is unchanged | I6a | Test first: on the opt-in build the catalog declares nothing and a stamped config does not reach `table.schema()`; ColumnDefaultSparkITest's 5 cases are marked legacy-only plus one new case for 422 on a bridged create; the legacy build still passes | **Now** (opt-in build) |
| W4 | oh | Port the 3 `OpenHouseTableOperationsTest` fixtures to v3 on the opt-in build (version-specific helper) | I6a | The 3 tests pass on the opt-in build and still pass on legacy | **Now** |
| W5 | oh | Server refuses a non-null `write-default` on v2 commits with `COLUMN_DEFAULT_REQUIRES_V3` | none | Unit test beside `ReadBridgeStripProtectionTest`: a v2 PUT with a write-default is refused; legacy overlay commits are unaffected | **Done in W6** (pull request 124) |
| W10 | oh | Server refuses a `format-version` change in an ordinary commit; upgrading needs an explicit operator path | none | Test: `ALTER TABLE SET TBLPROPERTIES('format-version'='3')` through OpenHouse is refused (current behavior unverified) | **Done in W6** (pull request 124) |
| W6 | oh | 1.12 service, fv≥3: no strip, validate rules (i)-(iv) of 3c, no read-bridge config | service on 1.12, X1 (v3 tables) | E2E: v3 create plus an API-added default round-trips byte-equal; a changed `initial-default` is refused; the opt-in on v3 is refused | After X1 |
| W7 | oh | X4: Spark 3.5 on OpenHouse, v3 table, API-added default, old Parquet files fill (port of the disabled Parquet ITest), then ORC and nested | W3, W6 | The ported ITests pass on the opt-in build | After X1 |
| W8 | oh | Migration: translate stored defaults → `initial-default` (write-default left unset, as legacy inserts NULL), upgrade to v3, remove the opt-in, in one bypassed commit; dry run lists the table's legacy readers | W6, W1 | On test tables: same ORC answers before and after; Parquet answers change as stated; legacy client gets `Unsupported format version` | After W6. **One-way per table** |
| W9 | ib | Optional: Spark 3.5 and 4.2 DDL for defaults (ADD COLUMN DEFAULT, ALTER COLUMN SET/DROP DEFAULT, `SUPPORT_COLUMN_DEFAULT_VALUE` for CREATE); on v2, a clear refusal. Diverges from Apache 1.12 | none | Flip `TestAlterTable.testAddColumnWithDefaultValuesUnsupported` and `TestSparkDefaultValues` :143-173 to expect success on v3 and refusal on v2 | **Now** (fork). Two-way |
| W11 | ib | Later, only if needed: Spark 4.2 ORC opt-in (port `OrcSchemaWithTypeVisitorSpark`) | W1 | 4.2 ORC default fill test | Deferred |

## Not verified

- No OpenHouse code was built or run. Every OpenHouse claim is [read] or [scout].
- Spark 4.2 was not run. Its behavior is read from the 4.2 sources and `TestSparkDefaultValues`.
- 1.5.2 Spark SQL results (DEFAULT dropped, CREATE refused, column-list INSERT) were not run on 1.5.2. They are inferred from code and from the same Spark 3.5 behavior on 1.12.
- Whether OpenHouse's update path lets a `format-version` property through today is unknown (W10 settles it).
- Whether a v3 field with a non-null `initial-default` and a null `write-default` (W8's translation) round-trips through Spark 3.5 reads and writes. Iceberg allows the two to be set independently (read); the round trip was not run.
- Dropping a write-default with `updateColumnDefault(name, null)`: my run hit a stale-table commit conflict, so I have no result.
- Whether `ColumnDefaultSparkITest` failures on the opt-in build have any cause besides the e3 rejection. The scout's stack points at the second ALTER only.
- The IRC (REST) path: whether the capability gate applies there, and REST clients reading native defaults through OpenHouse.
- That bridged tables in production are mostly ORC is an inference from the fact that only ORC filled defaults on LinkedIn's line.
