# Review: format v3 and native column defaults (slug `format-v3`)

Scope: pull requests 124 (`e2e02bfa`), 126 (`57db4015`), 129 (`70b35d7a`); the default-values work W1 to W6 they
build on (OpenHouse pull requests 91 and 97 for W3, W4, W12; Iceberg fork pull request 11 `28742df3e` for W1, W2);
the pre-existing OpenHouse read bridge (`services/tables/.../readbridge/`, present on LinkedIn main `bb8929e1`).
Read at head `81b4cd4d`. Nothing was built or run.

## 1. Bottom line

The v3 policy itself (`FormatVersionRule`, `FormatVersionCommitPolicy`, `FormatV3Types`) is coherent, is wired into
the one pre-commit hook every commit path passes, and with `cluster.iceberg.format-v3.enabled=false` it leaves v2
tables essentially as they were on the branch before it. The weak points are about what "as before" was measured
against, and about the defaults story as a whole:

1. **Existing read-bridge tables become unusable from the default build.** On the default (Iceberg 1.12) build the
   Spark 3.5 catalog and the maintenance jobs' client no longer declare `column-defaults`, so every table carrying
   `required-capability.column-defaults=true` answers 422 `CLIENT_CAPABILITY_MISSING` on load and write, and the
   migration that is meant to move those tables (W8) does not exist. On LinkedIn main the same Spark 3.5 runtime
   declared the capability and read them. This is a default-on behavior change of the defaults work (pull requests
   91 and 118), deliberate (D5, D11) and documented in `14-native-defaults-design.md`, but with no way across.
2. **"Answers exactly as before" in pull request 129 is pinned against the wrong baseline.** The "before" pins were
   taken on the 1.12 branch, after the Iceberg flip. Against LinkedIn main (Iceberg 1.5.2), a native create or
   update carrying `timestamp_ns`, `unknown`, `geometry` or `geography` answered 400 `schema : Cannot parse type
   string to primitive: ...`; on the default build with both switches off it answers **500** `Invalid schema for v2`,
   and `FormatV3TypesSwitchOffTest` pins the 500 as "as before". `variant` answers 400 with different text.
3. **The four native answer changes named by pull request 124 are real and default-on** (they apply with the switch
   off), but two of the four "before" answers in the merge message describe the branch after the Iceberg flip, not
   LinkedIn main. Against main: create asking for a version above the cluster's 201 then 400; update asking for 3
   refused by Iceberg then refused 400 `FORMAT_VERSION_REFUSED`; v2 write-default introduced: stored then 400. All
   are refusals of requests no shipped OpenHouse client sends; they are defensible but not documented as
   upstream-facing changes. A fifth, unlisted change: every commit now emits a `format-version` pre-commit metric.
4. **The switch is bypassable by configuration and the upgrade is silent.** `cluster.iceberg.format-version=3`
   makes every new table v3 with the switch off (the rule's floor is the cluster's version). An upgrade to v3, which
   is irreversible and cuts off every LinkedIn 1.5.2 reader, is a plain 200 with no log line, metric or audit field,
   and the native GET does not expose a table's format version at all.
5. **Cruft:** user-facing error messages cite private work-item ids ("work item X5", "X7", "W8"); the IRC write
   handler keeps its own parallel format-version checks next to "the one rule"; `FormatVersionRule` is
   re-instantiated with `new` in seven places; `IcebergV3.nextRowId` exists only for tests; the Iceberg-line probe is
   implemented twice.

Row lineage, deletion vectors and native defaults survive the native commit paths that were checked (snapshots
PUT, update, replace, rename, create-with-data), by reading and by the tests' assertions; nothing found that drops
them silently on a v3 table.

## 2. Defects

### D1. SHOULD FIX: v3-only types on a v2 table answer 500 on the default build, pinned as "as before"
- What goes wrong: a native create or PUT whose schema has a `timestamp_ns`, `timestamptz_ns`, `unknown`,
  `geometry` or `geography` column, on a cluster with the switch off (the default), answers 500 with Iceberg's
  `Invalid schema for v2: ...`. A client error reported as a server error; on LinkedIn main it was 400.
- Before (`bb8929e1`): `OpenHouseTablesApiValidator.java:484` parses the schema with 1.5.2's `SchemaParser`, which
  has none of these types (`Types.fromPrimitiveString`, li/openhouse-1.5.2 `Types.java:63-80`, throws `Cannot parse
  type string to primitive`); the catch-all at `:583-586` turns it into `schema : <message>` and 400.
- After: 1.12 parses them; nothing refuses them below v3 at request validation, so Iceberg's `Schema.checkCompatibility`
  refuses at metadata build and the generic handler answers 500. Pinned by
  `FormatV3TypesSwitchOffTest.java:73-79`: `status(answer) == 500 && message.contains("Invalid schema for v2:")`,
  with the comment "by Iceberg's schema check of a v2 table (500), as before".
- `68-x5-v3-types-result.md`/the merge message of 129 say "a 400 would change v2 answers, so both are put to the
  owner": the 400 is what LinkedIn main answered; the 500 is the change.
- Certain from reading (the 500 status itself is asserted by the test).
- Fix: extend `FormatV3Types.refuseVariantBelowV3` to every v3-only type below v3 (400, message naming the type), and
  flip the pin. SMALL.

### D2. SHOULD FIX: `cluster.iceberg.format-version=3` creates v3 tables with the switch off
- `FormatVersionRule.versionOfCreate` (`FormatVersionRule.java:93-100`): `if (!asked.isPresent() || asked.getAsInt()
  <= clusterVersion()) return clusterVersion();` and `checkCommitted` (`:186-188`): `int floor = stored == null ?
  clusterVersion() : Math.max(stored, clusterVersion()); if (committed <= floor || ...) return;`.
- With the cluster version set to 3 on the 1.12 build, every create is v3 and passes the invariant, whatever
  `cluster.iceberg.format-v3.enabled` says. The class javadoc ("Format version 3 is a per-table choice behind the
  cluster switch") and the switch's javadoc in `ClusterProperties` ("It never makes a table v3 by itself") are then
  false, and the one-way door opens for every table on the cluster.
- Certain from reading. Not a behavior change (it needs a configuration change), but the switch does not guard
  what it claims to.
- Fix: refuse to start, or clamp, when `cluster.iceberg.format-version > 2`; or make `checkCommitted` treat any
  version above 2 as needing `v3Enabled()`. One test. SMALL.

### D3. SHOULD FIX: an upgrade to v3 is silent and invisible on the native API
- The upgrade (native PUT with `format-version=3`, Spark `ALTER TABLE ... SET TBLPROPERTIES ('format-version'='3')`,
  IRC `upgrade-format-version`) is irreversible and makes the table unloadable for every LinkedIn 1.5.2/1.2 client
  and the 1.5.2 service. It answers 200 like any property change. No log line, counter or audit field marks it
  (grep of `services/tables/src/main` for upgrade logging finds nothing); `GetTableResponseBody` has no format version
  and Iceberg strips `format-version` from properties, so the native API cannot even tell a caller afterwards that
  the table is v3.
- Certain from reading.
- Fix: log and count upgrades in `OpenHouseInternalRepositoryImpl` update path; consider exposing the format version
  (new field, additive). SMALL.

### D4. MINOR: a default value on a `variant` or `unknown` column answers 500 on every table version
- Stated by the 129 merge message ("a default value on a variant or unknown column answers 500 on every table
  version") and not fixed or pinned. With the switch on it is reachable through the Iceberg API over IRC. Cannot
  tell the exact path without running; the statement is the author's.
- Fix: refuse with 400 in `FormatVersionCommitPolicy.keepInitialDefaults` / `FormatV3Types.checkOnV3`. SMALL.

### D5. MINOR: on a cluster at `format-version=1`, a create that rebuilds metadata from empty is now refused
- `OpenHouseInternalTableOperations.rebuildTblMetaWithSchemaBuilder` (`:414-424`) builds a new table with
  `TableMetadata.buildFromEmpty()`, which is format v2 on both Iceberg lines (`DEFAULT_TABLE_FORMAT_VERSION = 2`).
  On a cluster configured at v1 that has always produced a v2 table for creates carrying a client schema (CTAS,
  replica creates). `checkCommitted` now refuses it (committed 2 > floor 1) with 400 `FORMAT_VERSION_REFUSED`.
- Likely (reading; no test runs a v1 cluster). Only a v1 cluster is affected.
- Fix: rebuild at the version `newMetadata` already has (`buildFromEmpty(newMetadata.formatVersion())` on 1.12),
  which also removes the special case for v3 at `:415-419`. SMALL.

### D6. MINOR: replace reads the stored table a second time, outside the transaction
- `keepFormatVersionOnReplace` (`OpenHouseInternalRepositoryImpl.java:355-368`) calls `catalog.loadTable` on every
  native and IRC replace on the default build (`IcebergV3.supported()` is true), switch on or off: one more House
  Tables read and metadata fetch per replace, and the version it reads is not the transaction's base, so an upgrade
  racing the replace is decided on a stale read (the pre-commit invariant still stops a downgrade, so the worst case
  is a refused replace). Same for `refuseVariantBelowV3` (`:321-338`) when the schema has a variant column.
- Certain from reading (the extra load); the race is inferred.

### D7. MINOR: write-default on a re-cased field, v2
- `BaseIcebergSchemaValidator.normalizeCasing` now keeps defaults on a re-cased field (`:76-80` at head). On LinkedIn
  main a re-cased field lost its `write-default` silently; now it keeps it and `refuseNewWriteDefaults` refuses the
  commit if the base does not carry the same value. Only a request that carries a write-default on a v2 table and
  re-cases the column; no shipped client does. Folded into behavior change B3.

Lines of attack that found nothing:
- Switch-off path for an existing v3 table: create, update, snapshots, replace, rename all keep v3; no silent
  downgrade path found (replace forces the stored version, `checkUpdate` refuses lower, Iceberg refuses downgrade).
- Row lineage on the native path: snapshots carry `first-row-id`/`added-rows` through `SnapshotParser` on 1.12;
  `TableMetadata.Builder.addSnapshot` maintains `next-row-id`; the create-from-empty path upgrades before adding
  snapshots. Deletion vectors live in manifests, which the service never rewrites.
- Initial-default immutability and the widening fix (pull request 126) are correct for int/long, float/double,
  decimal precision.
- Register of v3 metadata is refused always (`IcebergRestWriteHandler.readRegistered` -> `requireTheClustersFormatVersion`).
- Iceberg pull request 11 (ORC throws instead of NULL for an unfillable default): on the 1.12 line no v2 table has an
  `initial-default` in metadata (no overlay on that line), so it affects only v3 tables with native defaults read by
  a non-opting reader or from name-mapped files: correct, and matches Apache. The 1.5.2 line is untouched.

## 3. Behavior changes against LinkedIn main

"Default" means: a deployment that changes no configuration, default build (Iceberg 1.12 since pull request 118).

| # | What existed (main) | Now | PR | Default on? | Justification in code/notes | Documented for follow-up | Upstream accept as is? | Restore/gate size |
|---|---|---|---|---|---|---|---|---|
| B1 | Native create with `tableProperties.format-version` above the cluster's (e.g. `3`, `4`): 201, table at the cluster's version, property overwritten (`OpenHouseInternalRepositoryImpl.java:769-771` at `bb8929e1`) | 400 `FORMAT_VERSION_REFUSED: Create of table ... asks for format version N: ...` (`FormatVersionRule.java:93-100,197-205`; `OpenHouseInternalRepositoryImpl.java:888-895` at head). Same for staged create and replace. Versions 1, 2, non-numbers unchanged | 124 | Yes | Merge message 124 "Known gaps"; `FormatVersionSwitchOffTest.java:194-210` ("Before, it was created at the cluster's version (201), silently") | Merge message only | Likely yes: refusing a silently ignored request is an improvement, but it needs to be called out as an API change | SMALL (gate behind the switch) |
| B2 | Native PUT asking `format-version=3` (or 4, 5) on a v2 table: on 1.5.2, Iceberg's own refusal (`Cannot upgrade table to unsupported format version`), status 400 or 500 (cannot tell without running; the 1.12-era pin says 500 for 4 and 5) | 400 `FORMAT_VERSION_REFUSED` with switch off (`FormatVersionRule.java:136-159`; called at `OpenHouseInternalRepositoryImpl.java:224-228`). The merge message's "answered 200 and upgraded" is the branch after the flip, not main | 124 | Yes | Merge message 124; `FormatVersionSwitchOffTest.java:173-191` | Merge message only | Yes: same refusal, clearer text and status | SMALL |
| B3 | v2 commit whose schema carries a non-null `write-default` (parsed by li 1.5.2 `SchemaParser.java:61,101`): stored; `initial-default` stripped only | 400 `COLUMN_DEFAULT_REQUIRES_V3` when the commit introduces or changes it (`FormatVersionCommitPolicy.java:237-260`); also on the 1.5.2 reference build. Re-cased fields now keep their write-default (D7) | 124 | Yes | `14-native-defaults-design.md` 3b ("The server must also refuse a non-null write-default") | Design doc, W5 | Probably: no shipped LinkedIn client sends a write-default; needs a sentence of rationale upstream | SMALL |
| B4 | Every commit ran the pre-commit checks of main only | A new check `format-version` runs on every commit of every table, emitting the pre-commit metric with `name=format-version` (`PreCommitCheckRunner.java:86-110`, bean `ApiConfig.java` `formatVersionCommitPolicy`), indexing every schema of the base (`FormatVersionCommitPolicy.java:262-283`) | 124 | Yes | None for the metric | No | Yes (new metric series); the per-commit cost should be noted | SMALL |
| B5 | Native create/update with `timestamp_ns`/`unknown`/`geometry`/`geography`: 400 `schema : Cannot parse type string to primitive: <t>` | 500 `Invalid schema for v2` (D1). `variant`: 400 `schema : Unsupported type: variant` (text changed). Introduced by the Iceberg flip; 129 pinned it as "before" | 118, kept by 129 | Yes | 129 merge message: "a 400 would change v2 answers" (wrong baseline) | Put "to the owner" in 129 merge message | No: a 500 for a client error | SMALL |
| B6 | Spark 3.5 runtime (`OpenHouseCatalog.capabilities()` at `bb8929e1`) always declared `column-defaults`; bridged tables (`required-capability.column-defaults=true`) loaded and wrote, ORC old rows filled through the overlay | Default build declares nothing (`spark-3.5 OpenHouseCatalog.java:35-38`, `IcebergLine.usesReadBridge()`); every load and write of a bridged table answers 422 `CLIENT_CAPABILITY_MISSING`; creating one with the property too | 91, made default by 118 | Yes | D5, D11, `14-native-defaults-design.md` 3a and "Behavior differences" 1-2 | W8 (migration) open, not started | No: makes existing tables unreadable from the default artifact with no migration | FOUNDATION: restoring needs the 1.12 client to overlay defaults onto v2 metadata, which Iceberg 1.12 refuses client-side (design 3a, rejected option), or W8 |
| B7 | Maintenance jobs' `TablesClientFactory` always sent `column-defaults` (`apps/spark/.../TablesClientFactory.java:27,54` at `bb8929e1`) | On the 1.12 line jobs send nothing (`apps/spark-3.5/src/shared/main/.../TablesClientFactory.java:65-67,82-110`); the scheduler jar cannot detect the line and still declares (warning), so it schedules bridged tables whose jobs then get 422 on every run | 97 (W12), made default by 118 | Yes | D11a | W13 "in flight" in `30-work-defaults.md` | No | Tied to B6: FOUNDATION |
| B8 | Read-bridge config resolved for every table | `ReadBridgeConfigResolver.resolve` returns empty for a table at format v3 (`:52-56`) | 124 | Only for v3 tables (none exist with the switch off) | W6 | n/a | Yes | n/a |
| B9 | Strip of `initial-default` on every write (`TablesServiceImpl`, `IcebergSnapshotsServiceImpl`, `TableCommitCore` call `readBridgeStripProtection.prepare`) | Skipped when the stored table is v3 or the request asks for `format-version>=3` (`FormatVersionRule.writesFormatV3`, `:108-114`). With the switch off a request asking for 3 is skipped and then refused (B1/B2), so nothing unstripped is stored | 124 | Inert with switch off (checked by reading the order: strip skip, then repository refusal before any commit) | W6 | n/a | Yes | n/a |

Accounting for every file at `bb8929e1` that the three pull requests changed:

| File (existed on main) | PR | Verdict |
|---|---|---|
| `cluster/configs/.../ClusterProperties.java` | 124, 129 | Two new properties, default false; no change to existing ones. No behavior change. |
| `iceberg/openhouse/internalcatalog/.../OpenHouseInternalTableOperations.java` | 124, 129 | `:414-419` upgrade only when the built metadata is already v3 (inert below v3); `io()` passed to the pre-commit runner. No change below v3 except D5 via the new check. |
| `.../internal/catalog/PendingCommit.java`, `PreCommitCheckRunner.java` | 129 | Adds a nullable `FileIO`; old constructors and overloads kept. Refactor, no behavior change. |
| `services/tables/.../api/ApiConfig.java` | 124 | New pre-commit bean: B4. |
| `services/tables/.../model/TableDto.java` | 124 | `@Transient @EqualsAndHashCode.Exclude long formatVersion`; no response mapping reads it (grep of `api/spec` finds no format version). No behavior change. |
| `services/tables/.../readbridge/ReadBridgeConfigResolver.java` | 124 | B8. |
| `services/tables/.../repository/impl/BaseIcebergSchemaValidator.java` | 124, 129 | Defaults kept on re-case (D7, B3); visitor replaced by an explicit walk, same output for struct/list/map (read both side by side). |
| `services/tables/.../repository/impl/InternalRepositoryUtils.java` | 124 | Adds `formatVersion(Table)` and sets it on the DTO. No behavior change. |
| `services/tables/.../repository/impl/OpenHouseInternalRepositoryImpl.java` | 124, 129 | B1, B2, D5, D6; variant-below-v3 answer (B5 text). |
| `services/tables/.../services/IcebergSnapshotsServiceImpl.java`, `TablesServiceImpl.java` | 124 | B9. |
| `services/tables/.../api/validator/impl/OpenHouseTablesApiValidator.java` | 129 | Explicit walk; variant refused here only when v3 is disabled; text `Unsupported type: variant` is the 1.12 visitor's, not main's (B5). Duplicate-column messages unchanged. |
| `services/tables/src/test/.../BaseIcebergSchemaValidatorTest.java` | 124 | Test only. |
| `apps/spark-3.5/build.gradle` | 129 | Test source sets per Iceberg line. Test only. |

## 4. Cruft

Private ids in code and in user-facing text:
- Error messages returned to users name work items: `FormatV3Types.java:119-120` "(work item X5)",
  `FormatVersionCommitPolicy.java:134` "(work item W8)", `:142` "(work item X7)". Comments: `FormatVersionRule.java:61`
  "(work item X5)", `FormatVersionCommitPolicy.java:33` "(work item W6)", `ReadBridgeConfigResolver.java:36` "(work item
  W6)", `TableCommitCore` "(work item W6)", `IcebergRestWriteHandler` "(V14)", "(D30)", "D19, D20",
  `apps/spark-3.5/build.gradle` "work item X5", `spark-3.5 OpenHouseCatalog.java` "decisions D5 and D11a". Remove ids;
  messages should say what is unsupported and nothing about the plan.
- Names that narrate the plan: `refuseWhatV3DoesNotHandleYet`, "for now" sections in tests.

Two implementations of one thing:
- IRC handler's own format-version checks (`IcebergRestWriteHandler.java:514-531` `requireTheClustersFormatVersion`,
  `createsAtFormatV3`; `:1066-1083` `refuseOtherFormatVersion`) beside `FormatVersionRule`, which calls itself "the one
  rule". Different wording, no `FORMAT_VERSION_REFUSED` token. Route IRC through the rule.
- The Iceberg-line probe: `IcebergV3.probe()` (service) and `IcebergLine.detect()` (client) are the same code.
- `FormatV3Types.hasRetention` parses the policies JSON with Gson instead of using `PoliciesSpecMapper`.
- The `if (!FormatVersionRule.writesFormatV3(...)) readBridgeStripProtection.prepare(...)` block is copied into four
  call sites (`TablesServiceImpl` twice, `IcebergSnapshotsServiceImpl`, `TableCommitCore`); belongs inside `prepare`.
- The literal 3 as four constants: `IcebergV3.VERSION`, `OpenHouseInternalTableOperations.FORMAT_V3`,
  `ReadBridgeConfigResolver.NATIVE_DEFAULTS_FORMAT_VERSION`, `OpenHouseTableOperations.FORMAT_V3` (client).

Accretion and wiring:
- `new FormatVersionRule(clusterProperties)` in seven places (`IcebergRestWriteHandler` three times,
  `TableCommitService`, `OpenHouseInternalRepositoryImpl.formatVersionRule()`, `OpenHouseTablesApiValidator`, `ApiConfig`)
  and `new FormatVersionCommitPolicy(...)` again in `stageCreate`: make both beans.
- `FormatVersionCommitPolicy.check` has three overloads, one passing `null` io; the 4-argument one exists for staged
  create only.
- `PendingCommit` hand-written constructors next to Lombok `@Value`.
- `OpenHouseTablesApiValidator`: `@Autowired(required = false) ClusterProperties` (exists for unit tests that build the
  validator without the bean).
- `FormatV3Types.refuseVariantBelowV3` exists to reproduce the 1.12 visitor's accidental text `schema : Unsupported
  type: variant`, which was itself not main's answer (B5). Replace with one below-v3 refusal for all v3-only types.

Test-only code in main sources:
- `IcebergV3.nextRowId` (`IcebergV3.java:44-48`) has no caller in `src/main`; only tests use it.

Two-line straddling (cost of the 1.5.2 reference build; needed only while the service builds on 1.5.2):
- `IcebergV3` reflection for `nextRowId`, `encryptionKeys`, `Snapshot.keyId`.
- Types recognised by `typeId().name()` strings (`FormatV3Types`).
- `TablesClientFactory.declaresColumnDefaults` loads `IcebergLine` reflectively with a class name joined from parts to
  dodge shadow relocation (`apps/spark-3.5/.../TablesClientFactory.java:31-38,82-110`).
- `apps/spark-3.5` per-line test source sets with same-named `VariantData` copies.

## 5. Tests

- `FormatV3TypesSwitchOffTest.everyFormatV3TypeIsRefusedAsBeforeWithTheSwitchOff` (`:73-79`) pins a 500 for a client
  error as "as before"; the "before" was the 1.12 branch, not main (D1, B5). The pin is wrong.
- `FormatVersionSwitchOffTest.aNativePutAskingForNoHigherVersionAnswersAsBefore` (`:230-247`) pins 500 for `Cannot
  downgrade v2 table to v1` and for a non-numeric version, labelled "as before" without a run on main.
- Pull request 129's first commit "pin today's answers before any change" pinned the post-flip branch. Its claim "v2
  tables, clusters with the v3 switch off, and the LinkedIn Iceberg 1.5.2 line answer exactly as before" holds against
  the parent commit, not against LinkedIn main.
- Pull request 124's "four native answers changed" list gives "before" answers from the branch (update to 3 "answered
  200 and upgraded"), not from main, where Iceberg 1.5.2 refused it. No test pins main's answer for B2.
- No test runs a cluster at `format-version=1` (D5) or `3` (D2).
- No test asserts that an upgrade is logged or visible (D3), because nothing is.
- No test of the metric emitted by the new check (B4).
- No test covers a bridged v2 table being read by the default-built Spark 3.5 catalog end to end; the 422 is covered by
  W3's test of the declared capability set.
- `assumeTrue(IcebergV3.supported())` skips 29 to 50 tests on the 1.5.2 line (merge messages); that is by design.
- The vectorized ORC cases of pull request 126 were not checked for columnar execution (stated); the claim "with the
  row reader and (Parquet, checked in the plan) the vectorized reader" is pinned only for Parquet.
- Toggling the switch by `ReflectionTestUtils.setField(clusterProperties, ...)` (`FormatV3NativeApiTest.java:424,441`)
  exercises the rule but not a fresh context with the property off; the separate switch-off test classes cover that.

## 6. Per pull request

| PR | "Leaves true" holds? | Why |
|---|---|---|
| 124 | Partly | Switch-off sweep, v3 create/upgrade on both APIs, replace keeping v3, never lowered, v3 fields surviving: hold by reading. "Requests that ask for no higher version answer as before": holds. The answer-change list is incomplete (B4) and two "before" answers are the branch's, not main's (B2). "With it off, no write path leaves a table above the cluster's format version": true, but the switch does not stop `cluster.iceberg.format-version=3` (D2). Upgrade is one-way and unannounced (D3). |
| 126 | Yes, as far as reading can tell | The widening fix (`sameInitialDefault`, `FormatVersionCommitPolicy.java:224-235`) runs only on v3; v2 and the read bridge are untouched. Read paths are Iceberg's; the Spark tests are the evidence and were not re-run here. ORC vectorized columnar not verified (stated). |
| 129 | Partly | Per-type decisions, variant switch, ORC/retention refusals: enforced in the pre-commit check, so on every commit path, and in request validation/repository for below-v3. "v2 tables ... answer exactly as before": no against LinkedIn main (B5, D1). Retention-with-variant check only looks at the `policies` property's `retention` key, which is where both APIs put it: holds. |

## 7. For the clean re-implementation

Keep:
1. One `FormatVersionRule` bean: create stores the cluster's version unless the request asks for 3 and v3 is enabled;
   an update may only raise to 3 when enabled; never lower; one invariant checked in the pre-commit hook.
2. The switch off by default, and it must also bound `cluster.iceberg.format-version` (D2).
3. The upgrade path is Iceberg's own (`format-version` property / `upgrade-format-version` update), translated to the
   same property on both APIs.
4. Replace keeps the stored version unless it explicitly asks for 3.
5. Rebuild-from-empty creates at the metadata's own format version.
6. On v3: native `initial-default`/`write-default` stored as committed; `initial-default` of an existing field
   immutable modulo type promotion; only new fields may carry one.
7. Below v3: refuse a new or changed `write-default`, with a message that says why.
8. Read-bridge opt-in refused on v3; read-bridge config not sent for v3.
9. Per-type decisions for v3-only types, in the pre-commit hook; below v3 one 400 for every v3-only type.
10. Variant behind its own switch, refused with ORC data files present, with ORC write format, with retention.
11. Replication and encryption refused on v3 until supported.
12. An upgrade is logged, counted and visible on GET.
13. The Java client sends `format-version` only when a commit raises the table to v3.

Do not carry over: private ids in messages and comments; the IRC handler's parallel format-version checks; ad hoc
`new FormatVersionRule`; the `refuseVariantBelowV3` reproduction of an accidental message; `IcebergV3.nextRowId` in
main; the duplicated line probe; the four copies of the strip-skip block; the pins of 500 answers as "before".

Commit sequence for upstream:
1. Refactor: `FormatVersionRule` bean, used by the existing create path (`computePropsForTableCreation`) with today's
   behavior exactly (cluster version always). No behavior change.
2. Refactor: pre-commit hook gets the table `FileIO` (`PendingCommit`).
3. Behavior: below-v3 refusal (400) for v3-only types at request validation, restoring main's 400 on the 1.12 line.
4. Behavior: refuse update/create asking for a version above the cluster's (B1, B2), with the rationale.
5. Behavior: refuse a new `write-default` below v3 (B3).
6. Feature: `cluster.iceberg.format-v3.enabled` (default off, bounded with the cluster version), create and upgrade,
   replace keeps version, rebuild-from-empty at the metadata's version, upgrade logged and exposed.
7. Feature: native defaults on v3 (no strip, immutability with promotion, read-bridge opt-in refused, no read-bridge
   config).
8. Feature: v3 types, one per commit (`unknown`; refusals of nanos/geo; variant behind its switch with ORC and
   retention rules).
9. Client: Java client sends `format-version` on the raise to v3.
10. Separately, and only with an owner decision: the read-bridge migration (W8) before the default build stops
    declaring `column-defaults` (B6, B7).

## 8. Not checked

- Nothing was built or run; statuses quoted from tests were read, not observed.
- The exact LinkedIn-main status for B2 (400 or 500 for Iceberg 1.5.2's upgrade refusal) needs a run.
- Spark 3.5/4.2 integration tests (`FormatV3SparkTest`, `SparkIrcFormatV3Test`, `NativeColumnDefaultsSparkTest`,
  `SparkIrcNativeDefaultsTest`, `*TypesSparkTest`) and the apps tests on variant tables were not read line by line;
  their claims are taken as the authors' evidence.
- `FormatV3RulesHoldTest` ("46 rules alike on v2 and v3") was not audited rule by rule.
- The stored-metadata golden files under `stored-metadata/v3/` were not diffed field by field.
- The rest-compat-kit expected outcomes, and the docs file `docs/development/iceberg-fork.md`.
- Multi-table transaction route: assumed to go through `TableCommitService` like a single commit (not traced).
- The Iceberg fork's Spark 3.5/4.2 `TestNativeColumnDefaults` were read only through the merge message.
