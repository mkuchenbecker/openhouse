# X1 and W6: Iceberg format v3 tables in the tables service: design and record

Marks: **[ran]** = run in worktree `/home/user/oh-x1v3` (branch `irc/x1-format-v3`, base 440249d1). **[read]** =
read in code at the cited place. Written by a delegated agent, 2026-11-23. The design section was written before
any code; the result sections at the end are filled in as the work lands.

## 1. Bottom line of the design

Format v3 is a per-table choice behind one cluster switch that is off by default. A table is v3 only when its
create asks for `format-version` 3 or an explicit upgrade asks for it. One rule decides every format-version
question; it is evaluated at one choke point every table write passes (the catalog's pre-commit checks, inside the
commit core's persistence), and the two APIs only translate their request shape into the one thing the rule reads:
a requested format version. Everything v3 adds to a metadata file either survives the service's rebuild exactly,
or is refused with a message.

## 2. What exists today (measured before any change)

- [ran] Native create with `format-version` = 1, 2, 3, 4, 5 or `x` in `tableProperties`: 201, stored version 2.
- [ran] **Native table PUT with `format-version: 3` on a v2 table: 200 and the table becomes v3.** The property
  reaches Iceberg's `PropertiesUpdate`, whose `replaceProperties` upgrades (1.12 accepts v3 and v4). This is a hole
  that exists since the 1.12 line became the default (pull request 118): any native caller can make a v3 table
  today. `4` answers 500 only because v4 wants an absolute location; `1`, `5`, `x` answer 500 with Iceberg's
  message; `2` answers 200 and writes a new metadata version with no change.
- [read] The OpenHouse Java client never sends the format version. `constructMetadataRequestBody` sends
  `metadata.properties()`, and Iceberg keeps `format-version` out of the stored properties. So on Spark 3.5 with
  the OpenHouse catalog today: `CREATE TABLE ... ('format-version'='3')` creates a v2 table silently, and
  `ALTER TABLE ... SET TBLPROPERTIES ('format-version'='3')` sends nothing at all (`isMetadataUpdated` sees no
  difference) and reports success. A staged create (CTAS) does send the raw property, which the server overwrites.
- [read] On LinkedIn's 1.5.2 line both statements fail in the client's own Iceberg (`Unsupported format version:
  v3`, `Cannot upgrade table to unsupported format version: v3 (supported: v2)`).
- [read] Over IRC: create and staged create with another version than the cluster's: 400. A commit with
  `UpgradeFormatVersion`: 400 "changes what an OpenHouse table commit does not store: format version" (D19).
- [read] The service rebuilds metadata from a DTO on every write: schema JSON, time partitioning and clustering,
  properties, policies, sort order, snapshot JSON list and refs. Format version is not in the DTO.

## 3. Request shapes per client

| Statement | Client | What reaches the server | 
| --- | --- | --- |
| `CREATE TABLE ... TBLPROPERTIES ('format-version'='3')` | Spark 3.5, OpenHouse catalog (native) | `POST /v1/databases/{db}/tables` whose `tableProperties` carries `format-version: 3`. **Needs a client change**: the Java client adds the key when the metadata it commits is format 3 or higher and its base is absent or lower. Nothing else in the body changes, and a v2 create is byte-identical to today's. |
| same, as CTAS | Spark 3.5 native | Staged create `POST` with the raw property (already sent today), then the snapshots PUT on `INITIAL_VERSION` with the same key added by the same client rule. |
| `ALTER TABLE ... SET TBLPROPERTIES ('format-version'='3')` | Spark 3.5 native | `PUT /v1/databases/{db}/tables/{t}` with `format-version: 3` in `tableProperties` (same client rule; `isMetadataUpdated` also sees a format-version change). |
| `CREATE TABLE ... ('format-version'='3')` | Spark 4.2, Iceberg REST catalog | `POST /iceberg/v1/namespaces/{db}/tables` with `properties.format-version = 3`. |
| same, as CTAS | Spark 4.2 IRC | The same with `stage-create: true`; then a commit with `assert-create` whose updates start `assign-uuid`, `upgrade-format-version {format-version: 3}` (`RESTSessionCatalog.createChanges`). |
| `ALTER TABLE ... SET TBLPROPERTIES ('format-version'='3')` | Spark 4.2 IRC | A commit with requirement `assert-table-uuid` and the single update `upgrade-format-version {format-version: 3}`. Iceberg's `replaceProperties` drops the reserved property and records the upgrade; no requirement asserts the format version. |

All are mapped to: **requested format version = 3** on a create, a replace or an update.

## 4. The one rule (`FormatVersionRule`), as built

Changed from the first draft by the coordinator's answers (2026-11-23): a native create that asks for a version
above the cluster's is refused too, not created at v2 silently; a replace that names 3 is honoured; a replace of a
v3 table that names 2 is refused.

Inputs: the write's kind, the table's stored format version B (none on create), the requested version R (may be
absent or no number), the cluster's version C (`cluster.iceberg.format-version`, 2) and whether v3 is enabled.
"v3 is enabled" = the cluster switch is on and the service's Iceberg can build v3 metadata.

- Create: R absent, no number, or at most C gives C, exactly as before (a create asking for 1, 2 or `x` answers
  201 at v2). R = 3 and v3 enabled gives 3. Any other R above C is refused (400).
- Replace (RTAS): R = 3 and v3 enabled gives 3. R below a v3 table's version is refused. Otherwise B. (The
  replace used to force C; on a v3 table that is a downgrade Iceberg refuses, so it must keep B.)
- Update (table PUT, snapshots PUT, IRC commit): R absent or equal to B changes nothing. R = 3, B below 3 and v3
  enabled is the explicit upgrade. Any other R above B is refused (400). R below B is refused (400) on a v3
  table; on a v2 table it is left to Iceberg, which answers 500 as before.
- The invariant, checked on every commit path as a pre-commit check (create, staged create, replace, update,
  snapshots, rename, lock, replication): the version a commit leaves is at most max(B, C), or 3 where v3 is
  enabled. Anything else is rejected before anything is written.

Where it is called: (a) `computePropsForTableCreation` for create, staged create and replace (the version to
build); (b) the repository's replace branch (`keepFormatVersionOnReplace`); (c) the repository's update branch
(`checkUpdate`), which gives the clean 400; (d) `CandidateViews` for IRC commits, which turns an allowed upgrade
into the `format-version` property of the candidate view and otherwise keeps the D19 refusal text unchanged;
(e) the IRC create checks (`refuseOtherFormatVersion`, the completion of a staged create); (f) the pre-commit
check `FormatVersionCommitPolicy`. The upgrade itself is done by Iceberg (`replaceProperties` then
`upgradeFormatVersion`), not by new code.

## 5. Cluster switch

`cluster.iceberg.format-v3.enabled` (default `false`), next to `cluster.iceberg.format-version` in
`ClusterProperties`. Off: as today. On: v3 may be asked for per table. It never makes a table v3 by itself and
`cluster.iceberg.format-version` stays 2. Turning it off again later refuses new v3 creates and upgrades but
existing v3 tables keep working (the invariant allows B = 3 to stay 3).

## 6. What is stored, and the field-by-field survival table, as built and tested

The service rebuilds metadata from a DTO on every write. These are the things v3 adds to a metadata file and how
each fares. [ran] unless marked. "Create" covers the native create, the IRC create and the staged create with its
completion. Tests: N = `FormatV3NativeApiTest`, I = `IrcFormatV3RoutesTest`, G = `StoredMetadataV3GoldenTest`
(every file as written), S35 = `FormatV3SparkTest`, S42 = `SparkIrcFormatV3Test`.

| v3 addition | Create | Commit | Load | Rename | Drop and recreate |
| --- | --- | --- | --- | --- | --- |
| `format-version: 3` | Survives: from the requested property through `newTableMetadata` (N, I, G `gold_v3_create`, `gold_v3_ctas`). The rebuild-from-empty of a create (`client.table.schema`, set by no code in this repository) restarted at Iceberg's default version; it now carries the version over [read: no caller here to run it]. | Survives every commit kind (N, I, G). Upgrade through the property (N, I, G `gold_v3_upgrade`). | IRC answers the stored file (I: `$.metadata.format-version` 3). The native GET has no format field, unchanged. | Survives (N, I, G `gold_v3_append`). | A new table from the new request only: v2 unless asked (N, I). |
| `next-row-id` | 0; a create with data re-adds the snapshot and advances it (N, I, G). | Recomputed by Iceberg's `Builder.addSnapshot` when the server re-adds the new snapshots in the client's order; equal to what the client computed (I: answered = sent = stored). The first commit after an upgrade counts the rows written before it. A snapshot behind it is refused (N). | as stored | Survives | starts at 0 (N, I) |
| Snapshot `first-row-id`, `added-rows` | Carried in the snapshot JSON, both APIs; stored snapshot JSON equals the sent one (N, I). | same | as stored | Survives | none |
| Field `initial-default`, `write-default` | Stored as sent, any column (N, I, the kit's `testCreateTableWithDefaultColumnValue`). | Stored as sent; W6 rules (N, I, G `gold_v3_defaults`). The strip no longer runs on a v3 write; a re-cased field keeps its defaults (fix). | as stored (GET schema JSON carries them; no read-bridge config) | Survives (I) | none |
| `encryption-keys`, snapshot `key-id` | Refused, not lost (I). The native client cannot produce them. | Refused (I). | n/a | n/a | n/a |
| v3-only types | Refused (N, I, S42). | Refused. | n/a | n/a | n/a |
| Deletion vectors | Not in the metadata file (delete files in manifests, counted in the snapshot summary). The snapshot is stored as sent; Spark writes and reads them (S35, S42). | same | same | same | n/a |
| Row lineage in manifests and data files | Client-written; `_row_id` reads back (S35, S42). | same | same | same | n/a |
| Statistics files | Refused over IRC as before (D19). | | | | |

Not kept exactly, so refused: register of a v3 metadata file; replica and replicated tables at v3 (section 8).

What is not byte-identical between "as committed" and "as stored", by nature of the rebuild and the same as for v2
tables: the server stamps `openhouse.*` properties, `last-updated-ms` and the metadata log, and a create's field
ids and table UUID are assigned by the server (the differences listed in `27-v3-irc-write-result.md`).

## 7. W6 rules (column defaults on v3)

Evaluated in the same pre-commit check, over (base metadata, committed metadata):

1. v3: every `initial-default` and `write-default` persists unchanged; nothing is stripped.
2. v3: a field that exists in the base keeps its `initial-default` exactly (refusal `COLUMN_INITIAL_DEFAULT_IMMUTABLE`).
3. v3: only a field new in this commit (id above the base's last column id) may carry a non-null
   `initial-default`. On create every field is new. Applied to every schema the commit adds, intermediate ones
   included, and a new field must carry the same `initial-default` in each of them.
4. v3: `required-capability.column-defaults` = true is refused (`COLUMN_DEFAULTS_OPT_IN_ON_V3`): on create, on
   update and on the upgrade of a table that has it (W8's migration removes it in the upgrading commit).
5. Below v3: a commit that introduces or changes a non-null `write-default` is refused with
   `COLUMN_DEFAULT_REQUIRES_V3`. A field that already carries one (possible on the 1.12 line before this change)
   does not block unrelated commits.
6. v3: no read-bridge config (`openhouse.read-bridge.column-default.*`) is resolved or sent on load.
7. Below v3 nothing changes: `initial-default` is still stripped, the bridge still applies.

## 8. Refusals and messages, as built

| Case | Status | Message (stable part) | Test |
| --- | --- | --- | --- |
| Native create, staged create, replace or update asks for a version above the table's (create: the cluster's) that is not 3-where-enabled | 400 | `FORMAT_VERSION_REFUSED: <Create/Replace/Update> of table db.t asks for format version N: format version 3 is not enabled on this cluster (cluster.iceberg.format-v3.enabled is false), which keeps tables at format version 2` (for 4, 5: `OpenHouse tables are format version 2, or 3 where the cluster enables it`) | `FormatVersionSwitchOffTest` (all), `FormatV3NativeApiTest.aV3TableIsNeverLoweredAndNoOtherVersionCanBeAskedFor` |
| Update or replace asks for a version below a v3 table's | 400 | `FORMAT_VERSION_REFUSED: ... the table is format version 3, and a table's format version is never lowered` | same, and `aReplaceKeepsTheTablesFormatVersionAndUpgradesOnlyWhenItAsksFor3` |
| IRC commit lowers the version | 400 | Iceberg's `Cannot downgrade v3 table to v2` | `IrcFormatV3RoutesTest.theCommitAnAlterTableSends...` |
| IRC commit changes the format version, v3 not enabled | 400 | unchanged: `changes what an OpenHouse table commit does not store: format version` | `FormatVersionSwitchOffTest.noWritePath...` |
| IRC create or staged create asks for another version than 2 (or 3 where enabled) | 400 | unchanged: `sets the table property format-version to N: OpenHouse creates tables with format version 2` | existing `IrcWriteRoutesTest`, the sweep, `IrcFormatV3RoutesTest` |
| v3 asked for where the switch is on but Iceberg cannot build v3 (the reference line) | 400 | `FORMAT_VERSION_REFUSED: ... this tables service runs an Iceberg that cannot read or write format version 3; format version 3 needs the service on the Iceberg 1.12 line` | `FormatV3NativeApiTest.onAnIcebergThatCannotBuildV3...` (runs on the reference line only) |
| Any path would leave another version (backstop) | 400 | `FORMAT_VERSION_REFUSED: the commit would leave table db.t at format version N: ...` | reached only if a path bypassed the repository checks; the sweep shows none does |
| v3-only type on a v3 table (`timestamp_ns`, `timestamptz_ns`, `unknown`, geometry, geography) | 400 | `FORMAT_V3_TYPE_NOT_SUPPORTED: column c (type) of table db.t ... work item X5` | `aFormatV3TypeIsRefusedAndNamesItsWorkItem`, `IrcFormatV3RoutesTest.formatV3TypesAreRefusedOverTheRoutes` |
| `variant` column | 400 | `schema : Unsupported type: variant` (the request validation's schema walk, before any table rule; does not name X5) | same two tests, `SparkIrcFormatV3Test.variantColumnsAndSqlDefaultsAreRefused` |
| Replica or replicated table at v3 | 400 | `FORMAT_V3_REPLICATION_NOT_SUPPORTED: ... work item X7` | `aReplicaOrReplicatedTableAtV3IsRefusedAndNamesItsWorkItem` |
| `encryption.key-id` / `encryption.data-key-length` on a v3 table | 400 | `FORMAT_V3_ENCRYPTION_NOT_SUPPORTED: ... does not store encryption keys` | `tableEncryptionPropertiesAreRefusedOnV3`, `encryptionKeysAndEncryptedManifestListsAreRefused` |
| IRC commit carries `add-encryption-key`, or a snapshot with `key-id` | 400 | `changes what an OpenHouse table commit does not store: encryption keys` / `encrypted manifest lists (a snapshot's key-id)` | `encryptionKeysAndEncryptedManifestListsAreRefused` |
| Register of a v3 metadata file | 400 | unchanged: `Registration of table ... asks for format version 3` | `registerOfAFormatV3MetadataFileIsRefused`, the sweep |
| W6: `initial-default` of an existing column changed, removed or added | 400 | `COLUMN_INITIAL_DEFAULT_IMMUTABLE` | `anExistingColumnsInitialDefaultNeverChangesOnV3`, IRC `commitsOnAV3Table...` |
| W6: read-bridge opt-in on v3 (create, update, upgrade of a table that has it) | 400 | `COLUMN_DEFAULTS_OPT_IN_ON_V3 ... (work item W8)` | `theReadBridgeOptInIsRefusedOnV3` |
| W6: `write-default` introduced or changed below v3 | 400 | `COLUMN_DEFAULT_REQUIRES_V3: db.t is format v2; native column defaults need format v3, and upgrading is irreversible. ...` | `aWriteDefaultBelowV3IsRefused` (both lines) |

To lift the register refusal: register would have to keep the file's `next-row-id` instead of recomputing it
(a create-with-metadata entry in the commit path that takes the row-id high-water mark from the registered file
and checks it is at least every snapshot's `first-row-id + added-rows`), and keep each field's defaults; then the
"what a create cannot store" check can admit v3.

## 9. The reference line (`-PicebergLinkedIn`)

Iceberg 1.5.2 cannot build v3 metadata, so "v3 is enabled" is always false there and every v3 request gets the
refusal above (switch on) or today's behaviour (switch off). The service's code touches 1.12-only API
(`nextRowId`, `encryptionKeys`, `Snapshot.keyId`, `buildFromEmpty(int)`, the v3 type ids) only through one small
class that uses reflection and type-id names, the way `IcebergLine` probes instead of linking. Tests that need a
v3 table are skipped by assumption on that line; tests of the refusal run on both.

## 10. Questions put to the coordinator and the answers (2026-11-23)

1. Switch off, native PUT asking for v3: refuse 400 (accepted; closing the hole is required; the test is named
   for it).
2. Switch off, native create asking for v3: changed from my draft: refuse 400 as well, not silently v2.
3. RTAS with `format-version` 3 on a v2 table, switch on: honoured; a replace keeps the table's own version; a
   replace naming 2 on a v3 table is refused.
4. Refused rather than lost (register of v3, replica tables at v3, v3-only types, encryption): agreed.
5. `write-default` below v3 refused only when a commit introduces or changes it: agreed.
6. Keep defaults on re-cased fields: agreed, own commit and test.
7. Added requirement: a sweep of every write path with the switch off (`noWritePathLeavesATableAboveTheClustersFormatVersion`).

## 11. Result (filled in as the work landed)

Pull request 124 (draft) into `irc-integration`, branch `irc/x1-format-v3`, 14 commits and two merges of
`irc-integration` (pull requests 122 and 123).

### What a user writes and what happens [ran]

| Statement | Spark 3.5, OpenHouse catalog (native) | Spark 4.2, Iceberg REST catalog (IRC) |
| --- | --- | --- |
| `CREATE TABLE ... ('format-version'='3')`, switch on | v3 table (`FormatV3SparkTest`) | v3 table (`SparkIrcFormatV3Test`) |
| `ALTER TABLE ... SET TBLPROPERTIES ('format-version'='3')`, switch on | upgraded, rows kept | upgraded, rows kept |
| either, switch off, 1.12 line | fails: 400 `FORMAT_VERSION_REFUSED ... not enabled on this cluster` | fails: 400, the messages IRC had (`OpenHouse creates tables with format version 2`; `does not store: format version`) |
| either, LinkedIn 1.5.2 client | fails in the client's own Iceberg before any request: `Unsupported format version: v3` (create), `Cannot upgrade table to unsupported format version: v3` (alter) | not applicable (no Spark 4.2 there) |
| `ALTER TABLE ... ('format-version'='2')` on a v3 table | fails in the client's Iceberg: `Cannot downgrade v3 table to v2` | same |
| no `format-version` | v2, as before | v2, as before |

### Default writer behaviours on a v3 table

| Behaviour | Spark 3.5 native | Spark 4.2 IRC | Status |
| --- | --- | --- | --- |
| INSERT: row lineage on the snapshot, `next-row-id`, `_row_id` readable | ran | ran | works, tested |
| First commit after an upgrade gives the old rows row ids | ran | ran | works, tested |
| DELETE, UPDATE, MERGE, copy-on-write (Iceberg's default mode) | ran | ran | works, tested |
| DELETE, UPDATE, MERGE, merge-on-read (table property): deletion vectors (Puffin) written and applied on read | ran | ran | works, tested at the SQL level; X2 keeps the wider scenarios, X6 the maintenance jobs |
| INSERT OVERWRITE, ADD COLUMN | ran | ran | works, tested |
| CTAS at v3 | ran | ran | works, tested |
| RTAS of a v3 table | ran | HTTP level only | works, tested |
| Branch write | ran | not run | works, tested on 3.5; staged write-audit-publish snapshot at the HTTP level |
| SET POLICY, SET TAG | ran | not run | works, tested |
| VARIANT column (Spark 4) | no such type | ran | refused 400 `Unsupported type: variant` (X5; the message is the request validation's and does not name X5) |
| Other v3-only types | HTTP level | HTTP level | refused, naming X5 |
| Column DEFAULT in SQL | not run here (fork test) | ran | refused by Iceberg's Spark integration before any request (W9) |
| Defaults set through the Iceberg API | HTTP level, as the client sends them | HTTP level | stored as committed (W6); reading old files with them is W7 |
| Table encryption | HTTP level | HTTP level | refused |
| Replica tables, replication | HTTP level | n/a | refused, naming X7 |
| Row ids kept across compaction; expiry, orphan files, compaction on v3 | not run | not run | X3, X6 |

### Counts [ran], tests / failed / skipped

| Suite | Default (1.12) | `-PicebergLinkedIn` (1.5.2) | Base (V9e record) |
| --- | --- | --- | --- |
| `:services:tables:test` | 1634 / 0 / 1 | 1634 / 0 / 29 | 1595 / 0 / 0 both |
| `:iceberg:openhouse:internalcatalog:test` | 319 / 0 / 0 | 319 / 0 / 0 | 319 |
| fixtures 1.5 `test` | 41 / 0 / 0 | 41 / 0 / 0 | 41 |
| Java 1.5 itest `test` | 138 / 0 / 0 | 138 / 0 / 2 | 136 |
| Spark 3.5 `catalogTest` | 279 / 0 / 1 | 279 / 0 / 7 | 272 / 0 / 1 |
| Spark 3.5 `statementTest` | 137 / 0 / 0 | 137 / 0 / 0 | 137 |
| Spark 3.5 `test` | 137 / 0 / 0 | 137 / 0 / 0 | 137 |
| Spark 4.2 `test`, `extensionsTest`, `smokeTest` | 48, 24, 65 / 0 / 0 | not in that build | 41, 24, 65 |
| `restCompatKitTest` | 160: 41 passed, 96 failed, 23 skipped (matches the list) | not in that build | 39, 98, 23 |
| Delta-harness, full, stub mode | 2574: 2556 passed, 7 failed, 11 skipped; identical case by case to the record of head e8287715 | not run | same |
| `spotlessCheck` | pass | pass | |

The skipped tests on the reference line are the new ones that need a v3 table (29 in the service, 6 in the Spark
3.5 itest, 2 in the Java itest), plus the one that was skipped before.

## 12. Completion (second agent, 2026-11-23, after the first was cut off)

State found [ran]: head 5f7c4a44 pushed, clean tree, contains `irc-integration` 75d6e185; CI run 45230472645 on
5f7c4a44 green on both jobs (so the earlier reference-line failure on 213a61ff was the compile error fixed by
60628bf3). The counts in section 11 predate 2195514b, 806e582c and the merge of pull request 123, so every suite was
rerun on the final head with `--rerun` (each test task executed, none from cache).

Checklist of the eleven points [read]: each has code and a test (see section 8 and the test list in the pull
request), with one gap: point 6's "only when the commit introduces or changes it" was tested only for the refusal
half. Added `FormatVersionCommitPolicyTest` (commit 1ec7f240, 2 tests, passes on both lines): a v2 table whose field
already carries a write-default is not blocked by a property change, a column addition or the default's removal;
changing it or introducing one is refused with `COLUMN_DEFAULT_REQUIRES_V3`. No production code changed.

Final head 1ec7f2408a9f6c5905fe56864a099a1046cf2261. Counts [ran], tests / failed / skipped:

| Suite | Default (1.12) | `-PicebergLinkedIn` (1.5.2) |
| --- | --- | --- |
| `:services:tables:test` | 1636 / 0 / 1 | 1636 / 0 / 29 |
| fixtures 1.5 `test` | 41 / 0 / 0 | 41 / 0 / 0 |
| `:iceberg:openhouse:internalcatalog:test` | 319 / 0 / 0 | 319 / 0 / 0 |
| Java client itest `test` | 138 / 0 / 0 | 138 / 0 / 2 |
| Spark 3.5 `catalogTest` | 279 / 0 / 1 | 279 / 0 / 7 |
| Spark 3.5 `statementTest` | 137 / 0 / 0 | 137 / 0 / 0 |
| Spark 3.5 `test` | 137 / 0 / 0 | 137 / 0 / 0 |
| Spark 4.2 `test`, `extensionsTest`, `smokeTest` | 48, 24, 65 / 0 / 0 | not in that build |
| `restCompatKitTest` | 160: 41 passed, 96 failed, 23 skipped; matches `expected-outcomes.txt` | not in that build |
| `spotlessCheck` | pass | pass |

CI on 1ec7f240: run 45266134619, both jobs success. Pull request 121 was still open at the end; the merge of
`irc-integration` after it lands is left to the coordinator.
