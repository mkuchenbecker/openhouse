# Review of pull request 154 (`irc/restore-jobs-and-client-behavior`, head 95cdb514)

Base `81b4cd4d` (merge base with `origin/irc-integration`), four commits. Read only; nothing built or run.

## 1. Verdict

MERGE AS IS. I found no MUST FIX. Two SHOULD FIX items are about bookkeeping and decisions, not code: FF-16 is only
partly closed, and one divergence from main is left on the REST path.

The three things that matter most:
1. **FF-10: the author's correction of the review is right.** I checked it against main's code. On main, a wrapper
   `TableCatalog` never reached a policy, GRANT, REVOKE or SHOW GRANTS exec: the planner gave "No plan for". UNLOCK was
   refused at analysis. All of this is the same today. The resolver change cannot be reached from SQL. The remaining
   SQL-visible difference from main is the `ICEBERG_REST` refusal, which the author flags. It contradicts the
   orchestrator's FF-10 decision as the PR body records it (finding 2).
2. **FF-14 is main's behavior.** For v1 and v2 tables the SQL is main's text, sent in main's order.
   `TablesClientFactory.java` and `TablesClientTest.java` are main's files byte for byte (R100 renames, empty diff).
   The 1.5.2 failure that the tests now pin is real for the `jobs.yaml` extension order and is labelled with its cause.
3. **FF-16 adds a new published artifact under LinkedIn's group id.** This is the only change away from main's
   metadata. The PR deletes the old comment's one-way-door warning and gives no replacement. RF-021 and the rest of the
   FF-16 row stay open (finding 1).

## 2. Findings

### F1 SHOULD FIX: FF-16 is reported closed, but its plan row is only partly done
- The FF-16 row (`86-review-findings-and-fix-forward.md:343`) also asks for these:
  - "relocate it in the uber jar";
  - "delete `CONFIG_OVERRIDES` and the Java runtime's compile dependency on the module" (RF-021);
  - an acceptance test: "a consumer of the thin jar plus the published Java runtime runs a GRANT without
    `NoClassDefFoundError` (a classpath test)".
- The PR does none of these.
  - Relocation was overruled by the recorded orchestrator decision ("No classes are folded or relocated"). That is
    fine.
  - RF-021 is deferred to FF-37 in "What is deliberately left". The pull request body still lists RF-021 under nothing
    and FF-16 as closed. The author's record says "FF-10, FF-14, FF-15 and FF-16 are closed".
  - No test pins the new POM dependency. A later edit that drops `runtimeOnly(...)` would fail nothing. The only
    evidence is a POM diff in the scratchpad (`rj/before|after-*-runtime-pom.xml`; I checked it: exactly one added
    `<dependency>`, `openhouse-catalog-capabilities`, runtime scope, on both lines).
- Also lost: the capabilities `build.gradle` header used to say "Not published on its own (its API is a one-way door,
  D24)". The new comment drops the one-way-door statement without saying that publishing now commits to the module's
  API (`CapabilityNames`, `GrantsCapability`, `UnlockCapability`, the value types, `RestCapabilityClient`). An upstream
  reviewer would see a new artifact (`com.linkedin.openhouse:openhouse-catalog-capabilities`, from a module main never
  had) published to `linkedin.jfrog.io` by `./gradlew publish` in `build-tag-publish.yml:56`, with no stated reason why
  it is safe to publish.
- How sure: certain from reading.
- Smallest fix:
  - Mark FF-16 as partial in the record and keep RF-021 open.
  - Say in the capabilities `build.gradle` comment that publishing makes the module's API public.
  - Optionally, add a build check that the generated POM of `openhouse-spark-3.5-runtime_2.12` names the module.
- Why it can wait: no runtime behavior is wrong.

### F2 SHOULD FIX (decision): a policy statement on an Iceberg `SparkCatalog` over `RESTCatalog` still fails where main committed
- The recorded orchestrator decision for FF-10 (PR body, "Decisions") says: "every OpenHouse SQL statement works again
  on any Spark catalog where it worked on LinkedIn main ... The refusal added with the capability resolver stays only
  where main also failed, with main's error."
- On main, `SetRetentionPolicyExec` (at `bb8929e1`) committed `updated.openhouse.policy` on any planned catalog whose
  table is a `SparkTable` carrying `openhouse.tableId`. That includes an Iceberg `SparkCatalog` with `type=rest`.
- At head, `OpenHouseCapabilities.java:83-97` (`ICEBERG_REST`, policies only if advertised) together with
  `requirePolicies` (`:134-148`) refuse that case with a message that is not main's.
- The author flags this under "deliberately left" as out of scope. The client-seam review's "what must survive", item 6
  (`records/review-client-seam-spark.md:321`), wants this refusal kept.
- So this is a conscious divergence, but it is not in the ledger as a BC row. The FF-10 decision text, read literally,
  is not met.
- Exposure is small: a non-OpenHouse REST server serving tables that carry `openhouse.tableId`. The OpenHouse IRC
  server advertises `policies`.
- How sure: certain from reading.
- Fix: the orchestrator records it as an intended divergence (a BC row with the justification "a REST server that does
  not advertise policies would store the patch as an ordinary property"), or narrows the decision text. No code change
  is needed in this PR.

### F3 MINOR: the inference "the uber jars may not be published at all" is likely wrong; the condition predates this PR
- `buildSrc/src/main/groovy/openhouse.maven-publish.gradle` (unchanged since `bb8929e1`) picks the `shadow` publication
  only if `tasks.findByName('shadowJar')` exists when the `publishing {}` block runs.
  - On Gradle 8.14.5 (wrapper, same on main), that block is not deferred. In the Spark 3.5 runtime and the Java
    runtime, `openhouse.maven-publish` is applied before `com.github.johnrengelman.shadow` (same order on main). So the
    `mavenJava` branch is taken. This part of the author's inference is right.
  - But `mavenJava` is `from components.java`. Shadow 7.1.2's `ShadowJavaPlugin` calls `addVariantsFromConfiguration`
    on the `shadowRuntimeElements` variant with `mapToOptional`. I checked the plugin jar in the Gradle cache. Maven
    publications include the artifacts of every variant of the component, so the `-uber` jar is most likely published
    as a classified artifact of `mavenJava`.
- How sure: likely; I cannot tell without `publishToMavenLocal`.
- Either way, the plugin order and the convention are the same on main, so this pull request changes nothing there.
- Fix: none in this PR. Correct the note ("inferred, not run") or settle it with a `publishToMavenLocal` listing.

### F4 MINOR: behavior changes the author did not list
- (a) **docker-compose recipe Spark jobs.**
  - On main, `jobs.yaml` ran the jobs from `openhouse-spark-apps_2.12-latest-all.jar`, built from the Spark 3.1 apps
    project (`spark-3.5-base-hadoop3.2.dockerfile:86` at `bb8929e1`). That jar carried
    `apps/spark/src/main/resources/log4j2.properties`.
  - Today the same file name is the Spark 3.5 apps jar (`spark-3.5-base-hadoop3.2.dockerfile:165`), which after this
    PR carries no logging configuration.
  - This is a recipe-only logging difference against main's recipe. The shipped 3.5 apps jar is now exactly main's
    (main's `apps/spark-3.5/build.gradle:63-72` borrowed only `java` srcDirs). Not wrong, but unlisted.
- (b) **Apps tests on both Spark lines.** The 3.5 and 4.2 apps tests lose the INFO console configuration they had from
  main resources. This is test-only. No test asserts log output: I grepped for appender and `LoggerContext` use in
  `apps/`.
- (c) **Spark 4.2 apps jar.** It also loses `log4j2.properties`. That project does not exist on main, so this change is
  neutral. Nothing consumes that jar as a scheduler.
- (d) **Scheduler on the 1.12 test classpath.** In tests where `IcebergLine` was loadable, the 1.12 line previously
  sent no `column-defaults` header and now sends it. In the scheduler image the wire is unchanged, because detection
  always failed there. The PR body says this only through the changed test expectation.

### F5 MINOR: the new CI gate passes silently if `unzip` fails or the jar is missing
- `.github/actions/default-build-deployables/action.yml:40-43`:
  `if unzip -Z1 build/.../openhouse-spark-apps-1.5_2.12-uber.jar | grep -x 'log4j2.properties'; then ... exit 1`.
- Under `pipefail`, a failing `unzip` makes the `if` condition false, so the step continues.
- The jar's absence would make the compose build fail next anyway, so the gap is small.
- The gate is in the fork's own composite action only (`.github/actions` does not exist at `bb8929e1`). Nothing is
  added to an upstream workflow.
- Fix: `unzip -Z1 "$jar" > entries.txt` first, then `grep -qx`.

### F6 MINOR: `StatementsOnAWrapperCatalogTest` leaves out several statement kinds
- The test is a real reproduction of main's behavior. It depends only on `OpenhouseDataSourceV2Strategy`'s
  `CatalogAndIdentifierExtractor`, which is identical to main's (`git diff bb8929e1` changes only the UNLOCK case's
  argument conversion).
- It would pass on main as well. It could not have failed before the resolver change, so it pins planning, not the fix.
  Only `theResolverDoesNotRefusePoliciesOnTheWrapper` and `OpenHouseCapabilitiesTest.aNonIcebergCatalogDoesNotRefusePolicies`
  pin the fix.
- Missing kinds: REVOKE, SET TAG (column policy tag), SET REPLICATION, UNSET REPLICATION, and GRANT or SHOW GRANTS on
  a DATABASE. All go through the same extractor, so I expect the same result, but they are not pinned.
- `DelegatingTableCatalog` is duplicated in two modules. The copies differ only in `createTable(..., StructType ...)`
  versus `createTable(..., Column[] ...)`, a Spark 3.5 versus 4.2 API difference, and there is no shared test source
  set between the 3.5 itest and the 4.2 runtime. Acceptable.

### F7 MINOR: `sourceTableOf` reads Spark's analyzed plan
- `SortStatsCollectionSparkApp.java:175-191` walks `queryExecution().analyzed().collectLeaves()` for a
  `DataSourceV2Relation` over a `SparkTable` whose table is a `BaseMetadataTable`, then calls `.table()`.
- What I checked:
  - No extra query, no extra analysis. `spark.sql` returns an already analyzed Dataset, and `analyzed` is a lazy val.
  - No reflection.
  - `BaseMetadataTable.table()` is `public BaseTable table()` on both lines (checked with javap on
    `iceberg-core-1.5.2.24.jar` and in the fork's `core/.../BaseMetadataTable.java:125`).
  - `operations().current()` does not refresh.
- For v1 and v2 tables, the result only chooses between the empty and the v3 `tblproperties` text. Main's statement is
  produced whenever the version is below 3 or the plan names no table.
- Risk: `QueryExecution` and `DataSourceV2Relation` are Spark developer and unstable API. An upstream reviewer may
  prefer `Spark3Util.loadIcebergTable(spark, "openhouse." + fqtn)`, which with Iceberg's default `cache-enabled` hits
  the `CachingCatalog` and sends no GET.
- Behavior is fine; this is a style and stability note only.

### F8 MINOR: private ids remain
- No added line in the diff contains an FF, RF, BC, D, X, W or N id (I grepped the `+` lines).
- Left in files this PR edits:
  - `integrations/java/openhouse-catalog-capabilities/build.gradle:1`: "(decision D23)". This is in the same comment
    block the PR rewrote: it removed D24 but kept D23.
  - `apps/spark-3.5/build.gradle:77-78`: "work item X5".
  - `FormatV3MaintenanceTest.java`: X6, X9, X10 and X12, in lines the PR did not touch.
- All four commit messages carry FF, RF and BC ids ("Closes FF-16, RF-034, BC-008.").
- The KEEP-FIX comments say "as before" ("Why not one per load, as before"). That is history narration, but it is what
  the orchestrator asked for (a justification against main), so it is acceptable.

### F9 MINOR: the capabilities artifact's contents depend on the Iceberg line
- `openhouse-catalog-capabilities/build.gradle` compiles `src/iceberg-1.5/java` or `src/iceberg-1.12/java` depending on
  `-PicebergLinkedIn`. It publishes under one coordinate, with no Iceberg dependency in its POM: Iceberg is
  compileOnly, and the evidence POM has no `<dependencies>`.
- The release workflow publishes the default (1.12) build, so a 1.5.2 consumer of the published artifact would get the
  1.12 transport.
- The thin Spark runtime has the same property: its POM names the fork's `iceberg-spark-runtime 1.12.0-ohfork`. So
  this is not a new kind of problem, but it should be said in the module's comment.

## 3. Behavior changes

| # | Change | Who sees it | Toward main? | Listed by author |
|---|---|---|---|---|
| 1 | `OpenHouseCapabilities.resolve` on a catalog that is not `SparkCatalog`/`SparkSessionCatalog`: `offered()` `{}` -> `{policies}`; `requirePolicies` no longer throws (3.5 and 4.2) | Direct Java callers only; unreachable from SQL (checked) | Neutral (main had no resolver); removes a wrong message | Yes |
| 2 | Scheduler: no Iceberg-line detection, no WARN, constant `column-defaults` | Image: wire unchanged, WARN gone. Test classpath on 1.12: header now sent (was null) | Toward main; exactly main's file | Yes (wire); the test-classpath change only via the test |
| 3 | Sort stats on 1.5.2 with OpenHouse's parser outermost (`jobs.yaml` order) and `spark_catalog` pointed at OpenHouse: success -> fails "Cannot parse order: parser is not an Iceberg ExtendedParser" | Reference line | Toward main (main's `ExtendedParser.parseSortOrder` checks only the outermost parser; main's `OpenhouseSparkSqlExtensionsParser` is not an `ExtendedParser`, checked) | Yes |
| 4 | Sort stats: no parse probe (1.12), no Java catalog build, no `ops.getTable` GET | Both lines | Toward main | Yes |
| 5 | Sort stats v3: version and write format now from the metadata query's plan instead of `ops.getTable`; if the plan names no table, the copy is v2 | v3 tables only (no main counterpart) | Neutral | Partly (null case mentioned as "gives main's statement") |
| 6 | `log4j2.properties` out of the 3.5 apps uber jar (both lines) and the 4.2 apps jar; the scheduler image gets `-Dlog4j2.configurationFile=$USER_HOME/log4j2.properties` | Shipped 3.5 jar: as on main. Scheduler: INFO as before | Toward main | Yes |
| 7 | Recipe Spark jobs and apps tests lose the INFO config (main's recipe ran jobs from the 3.1 jar, which carried it) | docker-compose recipe, tests | Away from main's recipe; toward main's 3.5 jar | No (F4) |
| 8 | Thin 3.5 runtime POM: one runtime dependency added; new published artifact `openhouse-catalog-capabilities` | Maven consumers | Away from main; justified in the build comments | Yes (one-way-door aspect not stated, F1) |
| 9 | CI: deployables action fails if the apps jar carries `log4j2.properties` | Fork CI | n/a | Yes |

No code change hides inside the KEEP-FIX comment edits. The diffs of `Operations.java`, `TableStatsCollectorUtil.java`
and `AppConstants.java` are comment lines only.

## 4. Tests

- `OpenHouseCapabilitiesTest.aNonIcebergCatalogDoesNotRefusePolicies` (3.5 and 4.2): fails on the old code (`offered()`
  was empty; `requirePolicies` threw). The new expectation is not main's, because main had no resolver, but it is
  consistent with main's exec, which had no catalog check.
- `StatementsOnAWrapperCatalogTest` (3.5 itest, 4.2 runtime; identical files):
  - The planning cases pass before and after the change and on main: they pin main's behavior through the unchanged
    extractor.
  - The UNLOCK case: main's `ResolveUnlockTable` gives the same message ("Catalog 'wrapper' does not support UNLOCK"),
    checked.
  - The resolver case fails on the old code.
  - Kinds left out: see F6.
- `TablesClientTest`: main's file byte for byte. It fails on the old code on 1.12 (the header was null).
- `SortStatsParserTest.theAppsRewriteSortsTheTableOnIceberg112AndFailsAsBeforeOn152`:
  - On 1.5.2 it would fail if the fallback were still present (it would not throw).
  - It checks the message, and that the table's data files and rows are unchanged.
  - The session's extension order is pinned equal to `jobs.yaml`'s by the existing test in the same class (lines
    88-97). So the pin uses the deployment configuration, and the "main failed there" claim holds for that order.
  - With OpenHouse's extensions listed first, main and head both succeed; this is not tested.
- `FormatV3MaintenanceTest` sort stats:
  - `catalogsBuilt() == 0` fails on the old code (it was 1). This is a proxy for "no extra GET": it counts catalogs,
    not requests. It is adequate because the Spark SQL path is unchanged.
  - On 1.5.2: throws, and no property is set.
- `SortStatsTempTableTest`:
  - `sourceTableOf` returns the same uuid and version as a catalog load, and null for `select 1`.
  - It does not test a metadata query whose plan has no `SparkTable` for a v3 table, which would give the silent v2
    copy.
- Unpinned claims:
  - The POM dependency (F1).
  - The jar has no `log4j2.properties`: pinned in CI only (F5), not in Gradle.
  - The scheduler logs at INFO from the image: CI only.

## 5. Checked and found sound

- Main's strategy (`bb8929e1:.../spark-3.5/.../OpenhouseDataSourceV2Strategy.scala`):
  - It matches `SparkCatalog` and `SparkSessionCatalog[_]` by type, so subclasses pass and wrappers do not.
  - Main's `IcebergCatalogMapper.toIcebergCatalog` returned null for anything that is not an instance of those two
    classes. There was no reflection on a field of an arbitrary class, so a wrapper holding an `icebergCatalog` field
    did not pass. For a subclass it read Iceberg's private fields through `DynFields.hiddenImpl(classOf[SparkCatalog], ...)`.
  - Head's `icebergCatalog` uses `instanceof` plus `HasIcebergCatalog`, so subclasses resolve the same way.
- Per statement kind on a wrapper:
  - Policies (six execs), GRANT, REVOKE, SHOW GRANTS: no plan, on main and at head.
  - UNLOCK: refused at analysis with the same message on both.
- Per statement kind on a `SparkCatalog` subclass: planned on both. Policies commit on both, unless the inner catalog
  is a `RESTCatalog` without advertised policies (F2).
- The 4.2 strategy's extractor is identical. The 4.2 resolver change mirrors 3.5.
- No other production caller branches on `offered()`, `offers()` or `NOT_ICEBERG`. `ResolveRenameTableTarget` uses
  `isOpenHouseRest`, which is unaffected.
- `TablesClientFactory`:
  - Its only production caller is `JobsScheduler.getTablesClientFactory`.
  - No service gate, job submission path or test outside `TablesClientTest` relies on a null header.
  - The 422 mismatch on the default line is the declared owner item BC-029; it is unchanged on the wire, since the
    image already declared the capability through the fallback.
- Sort stats SQL text and statement order versus main: identical for format below 3. `rewriteSorted` sends main's exact
  `call openhouse.system.rewrite_data_files(...)` text. `getRewriteOptions` builds the same map.
- KEEP-FIX defects on main are real:
  - `Operations.getTable` and `createTransaction` built a catalog per call through `CatalogUtil.loadCatalog`.
    `OpenHouseCatalog.initialize` mutates the `TablesApiClientFactory` singleton (`setStrategy`, `setSessionId`).
    `close()` only closed Spark.
  - `prepareBackupDataManifests` collected only `task.file()`.
  - `transformPartitionRowToColumnData` labelled the values by position with the current spec's names.
  - The written justifications are accurate.
- Jar listings (evidence directory), apps uber on both lines: the only entries removed are `log4j2.properties` and
  `TablesClientFactory$DeclaresColumnDefaults.class`. The runtime thin and uber listings are identical before and after.
- `jobs-scheduler.Dockerfile` against main:
  - Base image, user, chown, `find -name "*.jar"` rename and entrypoint are unchanged, apart from the `APP_NAME` rename
    (pre-existing) and the new `COPY` and `-D` flag.
  - The compose build context is the repo root (`oh-services.yml:23`), so the `COPY` path resolves.
  - The renaming `find` matches only `*.jar`.
- The old resources directory held only `log4j2.properties`. No other reference to it remains.
- The Spark 4.2 runtime does not apply `openhouse.maven-publish`, so it needs no POM change.
- The Java runtime uses only inlined constants of the capabilities module (comment at its `build.gradle:53-55`).

## 6. Not checked

- CI results, the smoke outcome files (`after-*-smoke`), and any build, test or publish. I ran none.
- Whether `-uber` artifacts are really published (F3).
- The thread safety of the shared per-run `Operations` catalog in the batched apps. This is a pre-existing KEEP-FIX,
  not changed here.
- Log4j2 resolution order inside a Spark driver with the app jar on a child classloader. This would decide whether
  F4(a) is visible at all.
