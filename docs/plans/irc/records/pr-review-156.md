# Review of pull request 156: restore LinkedIn main's native answers by default

Head `f2d7f6f604ce1375a2c7c08db1066375a6b08023`, base `irc-integration` `81b4cd4d`, worktree `/home/user/oh-restore-svc`.
Read only. Nothing built or run. Main is `bb8929e1`. Iceberg 1.5.2 means tag `v1.5.2.24` in `/home/user/iceberg`;
Iceberg 1.12 means `origin/openhouse-1.12.0`.

## 1. Verdict

**MERGE AFTER FIXES.** Close F1 first. F2 and F3 should be closed in the same pull request if they can be, but they
can follow.

The three things that matter most:
1. **F1 (MUST FIX).** FF-19 moved the format-version decision for a replace to after
   `replaceTransaction()`. Iceberg's `buildReplacement` therefore builds the replacement at the base's version (v2).
   Its `setCurrentSchema` runs `Schema.checkCompatibility(schema, 2)`. With the switch ON, a replace (RTAS, IRC
   replace) that upgrades a v2 table to v3 and whose schema has a v3-only type or an `initial-default` now fails
   with `Invalid schema for v2`, which is answered 500. On the base it succeeded. This breaks the author's own
   constraint that v3 behavior is unchanged, and no test covers it.
2. **The premise "the reference line is main" holds for the answers this pull request pins, but not in general.**
   I checked each pinned answer against main's code:
   - the create overwrites the asked version;
   - a PUT asking for 3, 4 or 5 gets the 500 `InvalidDataAccessApiUsageException` wrapping Iceberg 1.5.2's text,
     thrown inside `save` before anything is written;
   - a v3-only type gets the 400 `schema : Cannot parse type string to primitive: <t>`, plus the sort-order failure.

   All of these are main's. Several paths are not main's, and the author's behavior list misses some of them:
   - On the 1.12 line the refusal fires earlier in the update than on main, so it wins over other refusals main
     gave first.
   - The replace format-version changes on v1 tables and v1 clusters are not listed.
   - With the switch off, the validator check also hits existing v3 tables (F2).
   - The IRC commit path can turn a 400 into a 500 (F3).
3. **The FF-40 audit event is sent inside the aspect's `try` (F4).** If a strict downstream audit handler throws on
   the new enum constant, a committed, irreversible upgrade is answered as a failure and also audited FAILED. This
   is behind the switch, so it can wait, but it should be guarded.

## 2. Findings

### F1. MUST FIX BEFORE MERGE: with the switch on, a replace that upgrades a v2 table to v3 fails when its schema has a v3-only type or a column default

- **What goes wrong.** Take a native staged replace or replace commit (Spark RTAS through the OpenHouse client), or
  an IRC replace commit. It asks for `format-version=3` on a v2 table, on a cluster with
  `cluster.iceberg.format-v3.enabled=true`, on the 1.12 line. Its new schema has a column of type `timestamp_ns`,
  `timestamptz_ns`, `unknown`, `variant` (with variant enabled), `geometry` or `geography`, or a column with an
  `initial-default`.
  - Now it fails while the replacement is built: an `IllegalStateException` "Invalid schema for v2: ...", wrapped by
    the repository and answered 500 natively. On IRC it is 500 `ServiceFailureException`, which Iceberg REST clients
    read on a commit as "commit state unknown".
  - At the base it succeeded and the table became v3.
- **Where.** `OpenHouseInternalRepositoryImpl.java:209` and `:405-421`:
  ```java
  // The format version is decided on the replace's own base, in replaceTable.
  tableProps.remove(TableProperties.FORMAT_VERSION);
  ...
          .withProperties(tableProps)
          .withSortOrder(sortOrder)
          .replaceTransaction();
  int stored = txn.underlyingOps().current().formatVersion();
  int version = formatVersionOfReplace(tableIdentifier, stored, requested);
  if (version != stored) {
    txn.updateProperties().set(TableProperties.FORMAT_VERSION, Integer.toString(version)).commit();
  }
  ```
  In Iceberg 1.12, `TableMetadata.buildReplacement` (core `TableMetadata.java:744-757`) reads the format version
  from `updatedProperties`. It calls `.upgradeFormatVersion(newFormatVersion)` before
  `.setCurrentSchema(freshSchema, ...)`. `setCurrentSchema` reaches `addSchemaInternal`, which calls
  `Schema.checkCompatibility(schema, formatVersion)` (`:1621`). Without the property, the builder is still at the
  base's v2 when the schema is added.
  - At the base, `keepFormatVersionOnReplace` put `format-version=3` into `tableProps` before the build, so the
    upgrade came first.
  - The later `txn.updateProperties()` upgrade never runs, because `replaceTransaction()` has already thrown.
- **Tests.** No test catches it. `FormatV3NativeApiTest.aReplaceKeepsTheTablesFormatVersionAndUpgradesOnlyWhenItAsksFor3`
  and `IrcFormatV3RoutesTest.aReplaceKeepsAV3TableV3AndUpgradesAV2TableThatAsksFor3` both replace with the base's
  plain schema.
- **How sure.** Certain from reading the Iceberg 1.12 code path. The exact status is not run.
- **Smallest fix.** When the switch is on, Iceberg builds v3, and the request asks for 3, put that version into
  `tableProps` before `replaceTransaction()`, as the base did. `buildReplacement` then upgrades first. Keep the
  post-build decision on `txn.underlyingOps().current()` for the refusals (`versionOfReplace` on the real base) and
  for the switch-off cases.
- **Test.** Add a case to `FormatV3NativeApiTest`: replace a v2 table asking for 3, with an added `timestamp_ns` or
  `unknown` column, or a column with an initial default. Expect 2xx and v3. Add the IRC equivalent. Both fail at
  head.

### F2. SHOULD FIX: with the switch off, writes to an existing v3 table that has an `unknown` column are refused at request validation

- **What goes wrong.** The switch-off contract, which the base's tests pin, is "turning the switch off again stops
  new v3 tables, not the ones that exist". The new check refuses any schema with a v3-only type whenever the switch
  is off, whatever the table's version. `unknown` is a type a v3 table may take: `FormatV3Types` refuses only
  nanosecond timestamps and geo types on v3, and `unknown` is Spark's `void`.
  - The route that carries the full schema is affected: native PUT.
  - So is the snapshots PUT, which is how the OpenHouse Spark client appends.
  - So are IRC creates and commits (`IcebergRestWriteHandler.java:204, 390, 583` call `validateCreateTable` and
    `validateUpdateTable`).
  - Each answers 400 `schema : Cannot parse type string to primitive: unknown`, a parse error for a schema that
    parses.
  - At the base only `variant` was refused like this. The author mentions "a native PUT carrying an unknown column"
    but not the snapshots PUT, the Spark appends or the IRC commits that this blocks.
- **Where.** `OpenHouseTablesApiValidator.java:490-499` and `:575-578`.
- **How sure.** Certain from reading. No test covers it: `FormatV3NativeApiTest.aV3TableKeepsWorkingWhenTheSwitchIsTurnedOffAgain`
  uses plain schemas.
- **Why it can wait.** It needs a v3 table with an `unknown` column on a cluster whose switch was turned off again,
  and no such deployment exists yet.
- **Fix.** Make the refusal depend on the stored table. One way: keep the validator check for creates and for
  tables below v3. Another: move it to the repository next to `refuseVariantBelowV3`, which knows the stored
  version, keeping main's text for v2 tables. Pin it with a test of a switch-off snapshots PUT to a v3 table with an
  `unknown` column.

### F3. SHOULD FIX: an IRC commit carrying a `format-version` property above 2 with the switch off now answers 500 (commit state unknown) instead of 400

- **What goes wrong.** This is the path the author names as untested.
  1. A raw REST client sends `set-properties {"format-version":"3"}`. Iceberg's own Java client sends
     `upgrade-format-version` instead, which `CandidateViews` still refuses with 400.
  2. `MetadataUpdate.SetProperties.applyTo` calls `Builder.setProperties`, which keeps the key (1.12
     `TableMetadata.java:1474-1483`).
  3. `CandidateViews.toView` copies `candidate.properties()` into the `TableDto`.
  4. `repository.save` reaches `checkUpdate`, which now throws an `IllegalArgumentException`
     (`FormatVersionRule.java:198-211`).
  5. The repository's exception translation turns it into `InvalidDataAccessApiUsageException`.
     `IcebergRestErrors.toError` does not map that class, so it falls through to 500 `ServiceFailureException`
     (`IcebergRestErrors.java:158`).
  6. On a commit, Iceberg REST clients turn a 500 into `CommitStateUnknownException`.

  At the base this was 400 `FORMAT_VERSION_REFUSED`. There is no main baseline (no IRC on main), so this is new
  drift on the IRC surface, introduced by this pull request.
- **How sure.** Likely. I read every step except the inside of `commitOn`.
- **Fix.** Keep main's 500 for the native routes only. Two ways:
  - on the IRC commit path, refuse a candidate whose `format-version` property differs from its `formatVersion()`
    with the existing 400 (in `CandidateViews.refuseUnpersistedChanges` or the handler);
  - or have `IcebergRestErrors` map an `InvalidDataAccessApiUsageException` whose cause is an
    `IllegalArgumentException` to 400.

  Add the test the author says is missing.

### F4. SHOULD FIX: the upgrade audit event can fail a committed upgrade, and adds an enum constant consumers may not accept

- **What goes wrong.** `buildAndSendEvent` is called inside each advice's `try`, for example
  `TableAuditAspect.java:101-108`:
  ```java
  try { result = point.proceed(); buildAndSendEvent(event, SUCCESS, ...); }
  catch (Throwable t) { buildAndSendEvent(event, FAILED, null); throw t; }
  ```
  `auditFormatVersionUpgrade` runs inside `buildAndSendEvent` (`:819-821`, `:844-845`).
  - `OperationType` is serialized by `TableAuditEvent.toJson()` and handled by whatever `AuditHandler` the
    deployment wires (the repository only has `DummyTableAuditHandler`).
  - Suppose LinkedIn's handler maps `OperationType` strictly, for example to an Avro or Kafka enum without
    `UPGRADE_FORMAT_VERSION`. Then the second `audit` call throws. The aspect sends a FAILED event for a write that
    committed an irreversible upgrade and rethrows, and the client gets an error for a success.
  - The same holds if anything else in that call throws. The `log.warn` itself is harmless.
- **How sure.** Certain about the control flow. The downstream deserialization is not in this repository: cannot
  tell.
- **Why it can wait.** It is reachable only with the switch on.
- **Fix.** Wrap `auditFormatVersionUpgrade` in try/catch with a log line. Ask the owner whether a new
  `OperationType` constant is acceptable to the internal audit consumer. If it is not, carry the upgrade as a field
  of the UPDATE event instead.
- **Other gaps, MINOR.**
  - If a commit passes the check (recorded), fails with `CommitFailedException`, and the retry's refreshed base is
    already v3 (a concurrent upgrade), the stale record stays. The request then reports an upgrade it did not make.
  - A commit that ends in commit-state-unknown but landed is never reported.
  - Neither "never when the commit fails" nor exactly-once on retry is tested. The FAILED case in
    `FormatV3UpgradeAuditTest.aWriteThatUpgradesNothingHasNoUpgradeEvent` is refused before the check runs, so it
    cannot fail for the right reason.

### F5. SHOULD FIX (document): replace format-version changes on the 1.12 line that are missing from the author's behavior list

At the base on the 1.12 line, `keepFormatVersionOnReplace` kept the stored version for every replace, with the
switch on or off. With the switch off, head uses the cluster's version (`OpenHouseInternalRepositoryImpl.java:365-372`):
- **A v1 table on a v2 cluster.** A replace upgrades it to v2. This is toward main: main put `format-version` = the
  cluster's version in `computePropsForTableCreation`, and `buildReplacement` upgraded.
- **A v2 table on a v1 cluster** (for example a CTAS-created table, which is always v2: see D5).
  - Head: the replace now fails with `Cannot downgrade v2 table to v1`. The exception is thrown from
    `txn.updateProperties().commit()` inside `save` and answered 500.
  - Base: it succeeded.
  - Main: it failed the same way, from `replaceTransaction()`.

  This is toward main, but it turns a working replace into a failure. Neither change is in the author's "Behavior
  changes against the base". Certain from reading. Add both to the list, and add a v1-cluster replace case to
  `FormatVersionV1ClusterTest`.

### F6. MINOR: on the 1.12 line the upgrade refusal comes earlier in the update than on main

On main, Iceberg 1.5.2 threw the refusal at `updateProperties.set(COMMIT_KEY, ...).commit()`, after
`doUpdateSchemaIfNeeded`, `doUpdateUserPropsIfNeeded`, the policy update and the sort-order update. At head on 1.12,
`checkUpdate` throws at `OpenHouseInternalRepositoryImpl.java:231-235`, before all of them.

A PUT that asks for 3 and also carries an invalid schema evolution got main's `InvalidSchemaEvolutionException` (a
400). It now gets the 500. On the reference line the order is still main's. Likely, from reading. The fix is to
call the switch-off branch of `checkUpdate` just before the `COMMIT_KEY` commit, or to say so in the javadoc. The
javadoc's "before anything is changed" is true but not "where main refused".

### F7. MINOR: the hard-coded Iceberg 1.5.2 text

`FormatVersionRule.java:205-208` copies 1.5.2's `Preconditions` text. The trap is mitigated:
`FormatVersionSwitchOffTest.expectIcebergRefusal` asserts the exact text on both lines, and on the reference line
it is Iceberg's own text. A drift in LinkedIn's 1.5.2 would therefore fail that test.

A better placement: put the 1.12-only refusal in the 1.12 source set, behind the existing per-line shim
(`IcebergV3`), so the 1.5.2 build carries no copy of its own library's message. It could also be a named constant
that cites the source (`TableMetadata.Builder.upgradeFormatVersion`). Deliberately reproducing a 500 matches main,
and that choice is right under the owner's rule.

### F8. MINOR: the v3-type spelling differs from main for non-canonical spellings

`FormatV3Types.spelled` (`FormatV3Types.java:334-345`) returns 1.12's canonical `toString()`. Main echoed the
request's text (1.5.2 `Types.fromPrimitiveString`: `"Cannot parse type string to primitive: " + typeString`).
Examples:

| Request spells | Main answers with | Head answers with |
|---|---|---|
| `TIMESTAMP_NS` | `TIMESTAMP_NS` | `timestamp_ns` |
| `geometry(OGC:CRS84)` | `geometry(OGC:CRS84)` | `geometry` |
| `geography(OGC:CRS84)` | `geography(OGC:CRS84)` | `geography` |
| `geometry( EPSG:4326 )` | the request's text, spaces kept | normalized |

The author discloses this. The fix, if wanted, is to read the raw type string from the JSON. Everything else I
checked matches main, certain from reading:
- the type set (TypeIDs `TIMESTAMP_NANO`, `UNKNOWN`, `VARIANT`, `GEOMETRY`, `GEOGRAPHY`; 1.5.2's `TYPES` map has
  none of them);
- the order (struct fields in order, list element, map key then value, as `SchemaParser` reads them);
- nested positions;
- the sort-order second failure.

The check cannot fire for anything main accepted.

### F9. MINOR: stale comments say the default is the production widths

Three comments still say the default is the production store's widths, but `HouseTableRowLimits.DEFAULT` now
checks nothing:
- `OpenHouseInternalTableOperations.java:101`: "the production store's widths unless set";
- `ViewCommitEngineImpl.java:81`: "the production store's widths unless given";
- `OpenHouseInternalCatalog.java:75`: "the default widths".

### F10. MINOR: switch-off guards key on the switch alone, not on whether the line can build v3

`FormatVersionCommitPolicy.applies` (`rule.switchOn()`), `writesFormatV3` and `checkUpdate` key on the switch
alone. `OpenHouseTablesApiValidator.formatV3Enabled` keys on `v3Enabled()`, which also requires that Iceberg
builds v3.

On the reference line with the switch on, which is meaningless there, the write-default refusal and
`FORMAT_VERSION_REFUSED` still run. FF-13's wording was "only where the line can write v3". The default (switch off)
is fine. Use `v3Enabled()` in `applies` to make the reference line exact in every configuration.

### F11. MINOR: the start-up check's justification is overstated

`FormatVersionRule.requireClusterVersion` and the pull request say "on Iceberg 1.5.2 such a cluster could create no
table, so a start failure takes nothing main could do". Main with `cluster.iceberg.format-version=3` could not
create or replace, but it still served reads, updates and snapshot commits on existing tables. A start failure takes
those away too.

It is still defensible: no working deployment can have that setting on 1.5.2, and on this branch's 1.12 line it
silently made every table v3. Correct the sentence.

Other facts about the check:
- It covers only versions above 2; 1 and 2 start. Tested.
- It is called only in the `ApiConfig.formatVersionCommitPolicy` bean, which every tables-service context builds.
- It is not in the House Tables service.
- The repository's per-call `new FormatVersionRule(...)` skips it, which is harmless because the bean exists.
- No test fixture sets a version above 2 with the switch off. The only fixture with a version set is
  `FormatVersionV1ClusterTest` at 1.
- Tests that flip the switch at runtime by reflection bypass it, which is harmless.

### F12. MINOR: claimed closures that are partial

- **FF-18 includes RF-042** (a default on a `variant` or `unknown` column answers 500). It is left open with the
  switch on. The pull request's "deliberately left" says so, but the items table says FF-18 is closed.
- **FF-19 says "no second load".** `refuseVariantBelowV3` (`OpenHouseInternalRepositoryImpl.java:340-347`) still
  runs `catalog.loadTable` on every replace whose schema has a variant column.
- **FF-40's plan asked for "one counter"** and a GET field. The pull request has a WARN log and an audit event, no
  counter, no field. This is recorded as an orchestrator decision in the body, so it is fine if confirmed.
- **FF-13 asked for "one run of main"** to record BC-012's status. None was made; the answer is inferred from the
  reference line, and my reading of main's code agrees.
- **FF-12's mapper test** (five clustering columns with an unmappable type) was not added. I agree with the author
  that BC-010 is not a change: `toTransform` and `findColumnName` cannot throw, and `validatePartitionField` runs in
  the filter before the count on both main and head.

### F13. MINOR: FF-12 defaults throw `UnsupportedOperationException` on IRC routes only

`findWithMetadataById` and `TablesService.loadTable`, `createTable(UUID)` and `stageCreateTable` are called only
from the IRC routes and `TableCommitService.loadBase`, which only IRC uses. A main-era implementation therefore:
- compiles;
- serves every native route;
- fails every IRC load or commit with an unmapped `UnsupportedOperationException`, answered 500
  `ServiceFailureException`. On a commit, the client reads that as commit-state-unknown.

This is acceptable for an opt-in surface, and `stageCreate` already set the pattern. Consider mapping
`UnsupportedOperationException` to 501 in `IcebergRestErrors`.

### F14. MINOR: placement of the upgrade hand-off

`FormatVersionUpgrades` couples a catalog pre-commit check to the web request scope (`RequestContextHolder`) to
reach the audit aspect. Two consequences:
- a commit run off the request thread is silently not audited;
- the key is `TableIdentifier.toString()` matched against the aspect's `databaseName + "." + tableName`, so an
  event with null names (`TableAuditAspect.java:827`) is skipped silently.

It is named and placed sensibly otherwise. `NO_LIMIT` is fine. `HIGHEST_WITHOUT_V3` is package-private and fine.
No private ids appear in the added lines; I grepped for FF, RF, BC, work-item and decision ids.

## 3. Behavior changes (mine), against base `81b4cd4d`

| # | Path | Base | Head | Main | Direction | In author's list |
|---|---|---|---|---|---|---|
| 1 | Row-length checks: unset (default 0), or a negative value | refused above 255 | no check; the store decides | no check | toward | yes |
| 2 | One property set, the other unset | unset one = 255 | unset one unchecked | n/a | toward | implicit |
| 3 | Native create, staged create, replica create asking for a version other than the cluster's, switch off | 400 if above the cluster's | the cluster's version, property overwritten | same | toward | yes |
| 4 | Native PUT or snapshots PUT asking for >2, switch off | 400 `FORMAT_VERSION_REFUSED` | 500 `InvalidDataAccessApiUsageException: Cannot upgrade ...` | same text and status; thrown later in the update (F6) | toward (ordering differs on 1.12) | yes (not the ordering) |
| 5 | IRC commit with a `format-version` property >2, switch off | 400 | 500 ServiceFailure, read as commit-state-unknown (F3) | no IRC | away | named, untested |
| 6 | v2 write-default, switch off | 400 `COLUMN_DEFAULT_REQUIRES_V3` | stored | stored | toward | yes |
| 7 | Re-cased v2 field with defaults, switch off | kept defaults | loses them | loses them (identical rebuild) | toward | yes |
| 8 | Pre-commit `format-version` check or metric on a below-v3 commit, switch off | run and counted | skipped | none | toward | yes |
| 9 | v1 cluster: CTAS rebuilt from empty | 400 | v2 table | v2 | toward | yes |
| 10 | v3-only type with switch off: native or IRC create or update of a v2 table | 500 or 400 with other texts | 400 with main's text (canonical spelling, F8) | 400 with the raw spelling | toward | yes |
| 11 | v3-only type with switch off on an existing v3 table: native PUT, snapshots PUT, IRC commit | passed (only `variant` refused) | 400 parse error (F2) | n/a | away | partly (native PUT only) |
| 12 | Replace of a v1 table on a v2 cluster, 1.12 line, switch off | stays v1 | upgraded to v2 | v2 | toward | **no** (F5) |
| 13 | Replace of a v2 table on a v1 cluster, 1.12 line, switch off | succeeds, stays v2 | 500 `Cannot downgrade` | same 500 | toward (a regression vs base) | **no** (F5) |
| 14 | Replace asking for 3, switch off, 1.12 line | 400 | the cluster's version | the cluster's version | toward | yes |
| 15 | Replace asking for 3 on a v2 table with v3 types or defaults, switch on | upgraded | 500 `Invalid schema for v2` (F1) | n/a | away, regression | **no** |
| 16 | House Tables reads on a replace | 1 extra load | none (except the variant path) | none | toward | yes |
| 17 | Start-up with switch off and `format-version` >2 | starts | refuses to start | starts; creates and replaces fail | away | yes |
| 18 | Upgrade to v3, switch on | silent | WARN and second audit event; can fail the request if the handler throws (F4) | n/a | away | yes (not the failure mode) |
| 19 | Extension points | did not compile | compile; IRC routes 500 | n/a | toward | yes |
| 20 | Static `HouseTableRowLimits.requireTableIdFits` and the `DEFAULT_*` constants | public | removed (no callers; not on main) | absent | n/a | yes |

## 4. Tests

| Class | Can it fail for the right reason? |
|---|---|
| `HouseTableRowLimitsTest` | Yes. The 0 and -1 cases and independent widths are covered. |
| `OpenHouseInternalTableOperationsTest.testDoCommitChecksNoLengthByDefault` | Yes for the location. The rename half is only `assertDoesNotThrow`, with no check that the row was saved under the long name (the author relaxed it). |
| `HouseTableRowLimitsUnsetTest` | Yes for "a too-long value is accepted on a wider store". It does not pin what a too-long value does on a store that cannot hold it (main's late failure, its status, the file left behind): unpinned. |
| `MetadataLocationLimitTest`, `MetadataLocationLimitFormatV3Test`, `IrcRegisterViewRouteTest`, `TableIdConfiguredLimitTest` | They now state 255 or 128 explicitly. They keep their meaning; none lost an assertion. `TableIdConfiguredLimitTest` now asserts the location width is 0, which is correct. |
| `DefaultClusterPropertiesTest` | Fine. |
| `PreCommitCheckRunnerTest.aCheckThatDoesNotApplyIsNeitherRunNorCounted` | Yes, and it checks that the other check is still run and counted. |
| `FormatVersionCommitPolicyTest` | Existing cases moved to switch on, which keeps their meaning. The new case pins `appliesTo` both ways. |
| `FormatVersionRuleSwitchOffTest` | Yes. It pins the start-up message, the create overwrite, the 1.12 refusal text and `writesFormatV3`. |
| `FormatVersionSwitchOffTest` | The new expectations are main's: checked against main's `computePropsForTableCreation` (`:769-771` at `bb8929e1`), 1.5.2's `upgradeFormatVersion`, main's update path and the `@Repository` interface translation. The old 400s were the branch's. The exact message is asserted on both lines, which is good. The metric test is sound. |
| `FormatV3TypesSwitchOffTest` | Exact text on both lines, plus the sort-order case. Only the canonical spellings are tested. |
| `FormatV3NativeApiTest`, `FormatV3SparkTest` | Only switch-off expectations changed. They are correct as main's answers. No v3 (switch on) expectation was changed or weakened in any class. Neither covers F1 or F2. |
| `FormatVersionV1ClusterTest` | Yes for CTAS. No replace case (F5). |
| `NativeReplaceHouseTableReadsTest` | It counts keyed reads (10) on a staged replace with a plain schema. It does not assert the resulting version. It does not cover the variant path that still loads. |
| `FormatV3UpgradeAuditTest` | Native and IRC upgrades are each audited once. The "no event when the commit fails" claim is not exercised (F4). No replace upgrade. No snapshots-PUT upgrade. |
| `ExtensionPointDefaultsTest` | A real compile guard. All the abstract methods added since main now have defaults (checked by diffing both interfaces against `bb8929e1`). |

Claims with no test:
- F1, F2, F3 and F5;
- the store's late failure with no check;
- the upgrade event's failure path.

## 5. Checked and found sound

- Every row-limit call site goes through the two instance methods, which both test `> NO_LIMIT`: table commit and
  rename (`OpenHouseInternalTableOperations:725-727`), staged-create pre-check (`:735`), native view writes
  (`ViewCommitEngineImpl:173`), IRC register-view (`IcebergRestViewRegistration:170`).
- Negative values clamp to 0, and each property is independent.
- With no check, the commit path is the base's path, minus the check.
- Create asking for a lower or higher version, switch off: the cluster's version, as on main (main always overwrote
  the property).
- `writesFormatV3`, switch off: `readBridgeStripProtection.prepare` runs exactly where main ran it (TablesServiceImpl
  put and stage create, IcebergSnapshotsServiceImpl, TableCommitCore). `ReadBridgeStripProtection` is unchanged
  since main.
- The re-cased-field rebuild with `keepDefaults=false` is identical to main's visitor: the same rebuild conditions,
  a new struct each time, the doc and optionality kept, the defaults dropped.
- `appliesTo` does not change the other checks' order or metrics.
- With the switch off, `FormatVersionCommitPolicy` still applies to any commit leaving v3 or above, so a v2 to v3
  (or v4) commit by any path, IRC `upgrade-format-version` included, is still refused. The staged-create direct
  check is guarded inside `check()`.
- IRC create, staged create and register format-version refusals are unchanged (handler code not edited).
- The replace base is `txn.underlyingOps().current()`, which is the base `buildReplacement` used. A base that moves
  before commit is handled as before: the replace transaction commits `current` against the refreshed base, and the
  pre-commit invariant refuses a downgrade.
- An existing v3 table replaced with the switch off stays v3.
- `PreCommitCheck` exists on main; a default method is compatible.
- BC-010 is not a behavior change.
- `DEPLOY.md` and `iceberg-fork.md` are consistent with the code.
- No private ids in the added lines.

## 6. Not checked

- Nothing was run. Statuses for F1, F3 and F5 come from reading.
- Inside `IcebergRestWriteHandler.commitOn`: whether some step strips a `format-version` property before
  `toView` (F3).
- LinkedIn's internal `AuditHandler` and how it consumes `OperationType`.
- The House Tables service's exact answer for an over-long row with no check (the status and which file is left).
  The path is the base's minus the check, but I did not trace the HTS error mapping.
- The Spark 3.5 itest beyond the one changed method. CI results were not re-verified.

## Re-check at a0bb671d

Fix commits read: `be254bea` (F1, F2, F5), `04fa5681` (F3), `b2df562a` (F4), `3312162f` (F11, F7, F6, F9). Merges `a221398d` and `a0bb671d` were read only for `TablesServiceImpl`. Nothing was run.

**Verdict: NOT YET.** One new MUST FIX, R1, comes from the F2 fix. Everything else is closed or acceptable.

### R1. MUST FIX: the F2 fix reads the stored table before authorization on native routes

- **Where:** `OpenHouseTablesApiValidator.belowFormatV3` / `storedAtFormatV3` (added in `be254bea`). It calls `catalog.tableExists(identifier)` and then `catalog.loadTable(identifier)`, which reads House Tables and the metadata file.
- **When it runs:** on native `POST` and `PUT /tables` (`OpenHouseTablesApiHandler.createTable` and `updateTable` call the validator before `tableService.putTable`, where the privilege is checked), and on the snapshots PUT (`IcebergSnapshotsApiValidatorImpl` runs before the service). So the read happens before any authorization.
- **What it is not:** a hot-path cost for ordinary requests. It runs only when the switch is off and the schema has a v3-only type. `firstFormatV3Type(...).isPresent()` is evaluated first, `formatV3Enabled()` is checked before the read, and the result is memoized per request.
- **Why it is a defect:**
  - Any principal can trigger it, and it is unauthorized metadata I/O.
  - The answer depends on what was read. A v2 or missing table gives 400 `Cannot parse type string ...`. A v3 table passes validation and goes on to 403. An unauthorized caller can therefore learn whether a table exists at format v3.
  - This is the read-before-authorization pattern that pull request 152 removed from the IRC routes.
  - The `tableExists`-then-`loadTable` pair is also not atomic: a table dropped in between throws `NoSuchTableException` from the validator.
- **Certainty:** certain from reading.
- **Fix:** keep main's 400 in the validator only for creates; a create needs no read. For updates, apply the below-v3 refusal after authorization, where the stored table is already loaded: in `TablesServiceImpl.putTable` after the privilege check (it has `tableDto.getFormatVersion()`), or in the repository next to `refuseVariantBelowV3`.
- **Cost of the fix:** for a v2 table the answer then loses main's other validation failures in the same list, and its order changes. State that as an accepted difference.
- **On the IRC routes** the same read happens after `authorize`. That is not a security issue, but it is a redundant second load of a base the handler already holds. MINOR.

### Per finding

- **F1: closed.**
  - The fix: when `v3Enabled()` and the request asks for 3, `format-version=3` is put into `tableProps` before `replaceTransaction()`. The post-build comparison now uses `txn.currentMetadata().formatVersion()`.
  - `BaseTransaction.currentMetadata()` exists on 1.5.2.24.
  - Switch off: `asksForAllowedV3` is false, so the switch-off replace asking for 3 still gets the cluster's version, and v1-cluster replaces behave as in F5. Nothing the pull request closed is reopened.
  - The author is right about `timestamp_ns`, and my earlier F1 example was the mistake. At `81b4cd4d`, `FormatV3Types.REFUSALS` holds `TIMESTAMP_NANO`, `GEOMETRY` and `GEOGRAPHY`, and refuses them on v3 with a `CommitRejectedException` carrying `FORMAT_V3_TYPE_NOT_SUPPORTED` (400). So the base answered 400 for `timestamp_ns`, not 2xx. The real regressions were `unknown` and `initial-default`, and those are now pinned.
- **F2: the original defect is closed; R1 was introduced by the fix.** v3 tables keep v3's rules on every path.
- **F3: closed.**
  - The new check is `TableCommitService.commit` (5-arg) calling `refuseUpdate` inside the candidate supplier, skipped for a replace.
  - Only `IcebergRestWriteHandler` calls that method, so it affects only `/iceberg`.
  - It runs after the handler's `authorize(identifier, base, ...)`, the authorization pull request 152 moved to the front (`commitTable` at line 272, the transaction route at 322), and inside the core after its lock and privilege checks.
  - The native PUT keeps main's 500.
  - A `format-version` property below the table's on IRC with the switch off still reaches Iceberg's downgrade refusal and answers 500, the same as the base. MINOR.
- **F4: closed.**
  - The upgrade audit is wrapped in try/catch and logged, so the write's result and its primary event cannot change.
  - On FAILED the record is taken and discarded.
  - Each applicable check first calls `discard(table)`, so a retried commit is reported only as what it did.
  - A commit that ends commit-state-unknown but landed is still never reported (accepted).
  - The owner question about the new enum constant is written into the description.
- **F5: closed.** Both replace changes are pinned in `FormatVersionV1ClusterTest` and listed as toward main.
- **F6, F7, F9: closed.** F6 is documented in the javadoc. F7 is a named constant used only where `IcebergV3.supported()`. F9 fixes the three comments.
- **F11: closed, and closer to main than refusing to start.**
  - Reads, updates and snapshot commits work.
  - A create or replace at a cluster version above 2 with the switch off is refused on 1.12. The leading text is 1.5.2's (`Unsupported format version: vN` at `TableMetadata.java:299-301`; the upgrade text for a replace) plus a hint.
  - The appended hint makes the text differ from main's beyond the leading part. Disclosed; acceptable.
  - D2 cannot reopen: `checkCommitted` now caps the floor at 2 with the switch off, and `appliesTo` holds for any commit leaving v3 or above. Every path, the IRC create of a candidate at the cluster's version 3 included, is refused by the invariant even if a create path skipped `versionOfCreate`.
  - That IRC create answers a wrapped 500 rather than 400 (no main baseline, misconfiguration only). MINOR.
- **F8, F10, F12, F13, F14:** unchanged and accepted as listed.
- **Behavior-change list:** complete and correctly split into toward main, away from main and no main baseline, except for R1. The new pre-authorization read, and the answer that depends on it, is not listed.
- **New MINOR in the fix commits:** in `OpenHouseInternalRepositoryImpl` (around lines 363-376), `formatVersionOfReplace`'s javadoc now sits directly above `asksForAllowedV3`'s javadoc. One of the two dangles.
- **Merge resolution in `TablesServiceImpl`:** correct. It keeps both the `ClusterProperties` field and the namespace methods, the imports are consistent, and the `writesFormatV3(clusterProperties, ...)` calls are intact.
- **Switch-on v3 tests:** no expectation was removed or changed. The fix commits only add cases.

## Re-check at 25a64a26

Fix commit `ca0c8e94` read. Nothing run. **Verdict: MERGE.**

- **R1: closed.**
  - The validator no longer reads anything.
    - `storedAtFormatV3`, the `tableExists`/`loadTable` pair and the catalog injection are all removed.
    - `validateCreateTable` refuses from the request alone (`!formatV3Enabled()`), in main's place, combined with main's other failures. Replaces are exempt there.
    - `validateUpdateTable` never refuses.
  - The refusal now comes after the privilege check, on the stored table that check already uses. `FormatVersionRule.refuseFormatV3TypesBelowV3` reads only the in-memory `TableDto`, and is called in two places:
    - `TableCommitCore.commit`, after `checkTableWritePathPrivileges` and `checkSystemOnlyLockAccess`. This covers native PUT updates, snapshots PUT on an existing table (replace commits included), and the IRC commit, transaction and replace routes.
    - `TablesServiceImpl.putTable`, after the stage-replace privilege check or `CREATE_TABLE`. This covers staged replace and a PUT that creates.
  - The pre-existing `findById` before privilege is main's order; the refusal adds no read.
  - **No path left that leaks or reads early.** No remaining path gives a caller without the privilege an answer that depends on the stored version. Every pre-decision answer is from the request alone, and the creates' 400 is identical whatever is stored.
  - **Coverage for allowed callers:**
    - Covered: native POST, staged create, snapshots PUT on the initial version, IRC create and staged create (validator); every commit on a stored table (core); PUT-create and staged replace (`putTable`).
    - IRC register cannot carry a v3-only type into a v2 table: Iceberg refuses such v2 metadata.
    - Uncovered, MINOR: a snapshots PUT whose table is missing but whose base version is not the initial one, or with `replaceCommit=true` and the initial version. It skips both checks and reaches the create path. There Iceberg 1.12's `checkCompatibility` refuses the v2 schema with 500, where main gave 400.
    - So a v3-only type still cannot slip into a v2 table: Iceberg's own schema check stops it. Only the answer differs on that rare path, where the table was dropped between the client's load and its commit.
- **The test `FormatV3TypesAuthorizationTest`:** sound.
  - It denies every decision, table and database privileges alike, which is stronger than denying only update.
  - It covers v2, v3 and missing tables on the native PUT and the snapshots PUT. The missing table on the snapshots PUT is a create, answering 400 with nothing read.
  - It compares the House Tables reads before the first decision with the same request without the type.
  - It asserts 403 for each case, but compares status only, not the bodies. MINOR.
  - The allowed-caller case covers only the native PUT.
- **The declared consequence:** accurate, and listed in the pull request description as a change away from main.
  - Updates, replaces and PUTs that create answer 403 where main answered 400 to a caller without the privilege.
  - Other validation failures are now answered first and alone.
  - Creates keep main's order.
  - The reference line keeps main's order, because its parser fails in request validation.
  - This is acceptable as the price of not reading before authorization.
- **Rest of `ca0c8e94`:** nothing blocking. The dangling javadoc is fixed. `FormatV3Types.belowV3SchemaFailure` and `belowV3SortOrderFailure` keep main's texts. No switch-on expectation changed.
