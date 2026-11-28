# Review: client seam, capabilities, Spark 3.5 REST paths, Spark 4.2 extensions (slug client-seam-spark)

Reviewer read-only at `81b4cd4d` (`/home/user/oh-review`), base `bb8929e1`. Assigned merges: #84 `4f15a3a3`,
#86 `b5b8ed87`, #100 `19605614`, #103 `c174e388`, #107 `152b08e7`, #101 `b07613f9`, #92 `8b9bfe83`, #117 `c1303849`,
#119 `92c5e117`, #128 `ca94d82e`. Paths read end to end: `integrations/java/iceberg-1.5/openhouse-java-runtime`,
`integrations/java/openhouse-catalog-capabilities`, `integrations/spark/spark-3.5/openhouse-spark-runtime`,
`integrations/spark/spark-4.2/openhouse-spark-runtime`, the smoke-suite declarations in both itests, `client/common`.
Iceberg sources checked: LinkedIn `v1.5.2.24` (the reference line's pinned version) and `origin/openhouse-1.12.0`.

## 1. Bottom line

The work in my assigned pull requests is mostly sound and mostly opt-in. #84 really edited no existing production
file (3 new files), so the seam is invisible unless `catalog-impl` names it. #100's refactor of the five SQL-extension
call sites is equivalent for Iceberg `SparkCatalog`/`SparkSessionCatalog` catalogs; I verified the accessor it swaps
in against Iceberg 1.5.2.24's source, and the error messages and `Principal` mapping are kept. The REST capability
client (#103) gets its connection, prefix, headers and authentication from the catalog's own merged properties, as it
claims. The Spark 4.2 extensions (#101) are a third copy of the extensions, not shared code.

What matters most:

1. **The default build is no longer LinkedIn's client, and the Spark SQL user sees it.** This comes from #91 (code)
   and #118 (the flip), not from my assignment, but it is the default path of my area. A plain build now ships the
   Spark 3.5 runtime against the fork's Iceberg 1.12. `IcebergLine.current()` is `V3` there. So the Spark
   `OpenHouseCatalog` declares no `column-defaults` capability and the client skips the read bridge. A table with
   `required-capability.column-defaults=true` that LinkedIn main reads gets 422 `CLIENT_CAPABILITY_MISSING` through
   the unchanged default configuration. Two namespace statements also change their messages on the legacy path. The
   #118 result note says "No Spark SQL difference was found", and its own suites disagree.
2. **#100 is not "unchanged" for every catalog (SHOULD FIX, default-on).** Every policy statement now calls
   `OpenHouseCapabilities.requirePolicies`. That call refuses any Spark `TableCatalog` that is not Iceberg's
   `SparkCatalog` or `SparkSessionCatalog` (path `NOT_ICEBERG`), even when its `loadTable` returns an OpenHouse
   `SparkTable`. Before, the statement committed. The unit test pins the new refusal as expected.
3. **The extensions now exist three times** (Spark 3.5, Spark 4.2, and the Java-client vs capability-module value
   types inside 3.5). 22 of 30 Spark 4.2 runtime files are byte-identical to 3.5. The 3.5 unlock path converts
   Java-client types to capability types and back. Its stated reason, tests shared with Spark 3.1, went away when #118
   deleted Spark 3.1.
4. **Some leftovers in the seam no longer do anything.** `OpenHouseRestAdapter.CONFIG_OVERRIDES` advertises
   `policies`, but nothing reads it: `OpenHouseRestCatalog.properties()` returns the raw properties, and the resolver
   recognizes the in-process catalog by type. The Java runtime takes a compile dependency on the capabilities module
   only for it. `IcebergCatalogMapper` and the Scala `Principal` object have no main-code callers.
5. **Packaging.** The capabilities module is not published, yet the published thin `openhouse-spark-3.5-runtime_2.12`
   jar now references its classes. The uber jars carry it unrelocated. No new third-party dependency was added by my
   pull requests.

## 2. Defects

### D-1 SHOULD FIX (behavior change, default-on): policy statements refused on non-`SparkCatalog` catalogs (#100)

- What goes wrong: a deployment whose Spark catalog is not Iceberg's `SparkCatalog`/`SparkSessionCatalog` (a delegating
  or wrapper `TableCatalog`) but returns an Iceberg `SparkTable` with `openhouse.tableId` could run
  `ALTER TABLE ... SET POLICY`/`SET TAG`/`UNSET POLICY` on main. Now each of the six policy execs fails before it
  commits, with `UnsupportedOperationException: Catalog '<name>' does not support OpenHouse policies: its server does
  not advertise 'policies' ...`. That message wrongly blames a server.
- Where:
  - `integrations/spark/spark-3.5/openhouse-spark-runtime/src/main/java/com/linkedin/openhouse/spark/capabilities/OpenHouseCapabilities.java:46-53`:
    `if (iceberg == null) { return new CatalogCapabilities(Path.NOT_ICEBERG, Collections.emptySet(), false, ...` (the
    `false` is "policies").
  - `:129-142` `requirePolicies`.
  - The call sites, for example `SetRetentionPolicyExec.scala` (`OpenHouseCapabilities.requirePolicies(catalog)` right
    before `updateProperties()`), and the same in `SetHistoryPolicyExec`, `SetSharingPolicyExec`,
    `SetReplicationPolicyExec`, `UnSetReplicationPolicyExec` and `SetColumnPolicyTagExec`.
  - On main the execs had no catalog check at all; only `case iceberg: SparkTable if ...containsKey("openhouse.tableId")`.
- Sureness: certain from reading. Real exposure depends on whether LinkedIn runs such catalogs (cannot tell).
- Pinned as intended: `OpenHouseCapabilitiesTest.aNonIcebergCatalogOffersNothing` asserts `offered().isEmpty()`.
- Fix (one line in each copy): offer `policies` on `NOT_ICEBERG` as on `OTHER_ICEBERG`. The exec's own
  `openhouse.tableId` check stays the gate, as before. Mirror the change in
  `spark-4.2/.../OpenHouseCapabilities.java:46-53`.

### D-2 MINOR (packaging, #100): the published thin Spark 3.5 runtime jar needs a module that is not published

- `integrations/spark/spark-3.5/openhouse-spark-runtime/build.gradle:58-60` makes the capabilities module
  `compileOnly` and puts it in `fatJarPackagedDependencies`. The thin jar's classes (`OpenHouseCapabilities`, the
  execs, `ResolveRenameTableTarget`) import `com.linkedin.openhouse.capabilities.*`.
- `openhouse.maven-publish` publishes the shadow publication plus `artifact jar`. The capabilities build says "Not
  published on its own" (`integrations/java/openhouse-catalog-capabilities/build.gradle:1-7`).
- On main a thin-jar consumer had to supply the Java runtime, which was published. Now they must also supply a class
  set that exists nowhere, or every OpenHouse statement fails with `NoClassDefFoundError`.
- The build comment "so the thin jar's consumers and the published POM do not change" is true of the POM and false of
  the consumers.
- The default build now refuses remote publishing anyway (#118), so this bites on `-PicebergLinkedIn` publishes.
- Fix: publish the module, or document the thin jar as uber-only. Sureness: certain from reading.

### D-3 MINOR (opt-in seam, #84): the in-process adapter ignores `prefix`

- `OpenHouseRestAdapter.Route` patterns have no prefix segment (`OpenHouseRestAdapter.java:115-137`).
  `Route.match` needs equal segment counts (`:160-163`).
- A user who sets `prefix` on the rest-shim catalog makes `RESTSessionCatalog` build `v1/<prefix>/...` paths. Every
  call then fails with `No route for request` (`:301-308`). `OpenHouseRestCatalog.initialize` builds its own `paths`
  with `ResourcePaths.forCatalogProperties(props)` (`OpenHouseRestCatalog.java:113`), so `HEAD` fails the same way.
- Fix: refuse `prefix` in `initialize`, or strip it in `execute`. Sureness: certain from reading.

### D-4 MINOR (opt-in seam, #84): the stale-base check covers loaded and created tables, not builder transactions

- `OpenHouseRestCatalog.CheckingTableBuilder.createTransaction/replaceTransaction/createOrReplaceTransaction`
  (`OpenHouseRestCatalog.java:357-370`) return the REST transaction unwrapped.
- A replace from a stale handle therefore goes through without today's `BaseMetastoreTableOperations` base check, and
  the adapter applies it to a freshly loaded head (`OpenHouseRestAdapter.java:551-568`).
- This is the RTAS-after-INSERT snapshot-history difference #86 measured (30 to 41 of 50 races) and D2 accepted. I
  list it because the class Javadoc (`:66-67`) says tables "reject a commit whose base is not the table's current
  metadata", without saying that transactions are excluded.
- Fix: wrap the transactions' table operations as well, or narrow the Javadoc. Sureness: certain from reading.

### D-5 MINOR (#103): a fresh authentication session per statement on Iceberg 1.12

- `capabilities/src/iceberg-1.12/.../RestTransport.java:230-245` builds a new `HTTPClient`, `AuthManager` and
  `catalogSession` for every GRANT/REVOKE/SHOW GRANTS/UNLOCK, then closes them.
- With an OAuth `credential` that is one extra token request per statement. #103 declares this.
- `catalogSession` is called without the `initSession` that `RESTSessionCatalog` 1.12 calls first. That is fine for
  the OAuth2 and none managers. For a custom `rest.auth.type` manager that keeps state from `initSession`, it can
  authenticate differently from the catalog. Not tested.
- Sureness: likely; cannot tell for custom managers without running.
- Fix: reuse the catalog's own `AuthSession` (not reachable through the public API), or keep one transport per
  catalog with an explicit close.

### D-6 MINOR (#100/#103): a client-side `openhouse.capabilities` value turns capabilities on

- `OpenHouseCapabilities.java:69-77` reads `rest.properties()`, which merges client and server properties.
- A user who sets `spark.sql.catalog.x.openhouse.capabilities=grants,unlock` against a server that sends no override
  gets HTTP calls to `/openhouse/acl-policies` instead of "does not support". Against a non-OpenHouse REST server,
  `policies` makes the policy statements write `updated.openhouse.policy` as an ordinary property.
- Declared in #100 and #103 ("This server always sends the override").
- Fix: read only what the config response sent; that needs the config response, which the public API does not
  expose, so keep the declaration. Sureness: certain from reading.

Lines of attack that found nothing:

- Error mapping in the adapter: backing exceptions are rethrown unchanged (`OpenHouseRestAdapter.java:61-63`). So a
  `CommitFailedException` still drives Iceberg's client retry, and no Java-client exception is re-mapped. Checked
  against `updateTable` (`:541-572`).
- State shared across tables or sessions: none. The adapter and backing catalog are per catalog instance;
  `IcebergLine` and `CapabilityErrorHandler` are static and stateless.
- Namespace encoding on both lines: the adapter's `%1F` split matches `RESTUtil.encodeNamespace` and
  `namespaceToQueryParam` defaults on 1.12.
- The REST client's 1.5.2 `post` with a null response type: `HTTPClient` 1.5.2 returns null for a success
  (`HTTPClient.java:304-305` at v1.5.2.24).

## 3. Behavior changes against LinkedIn main

Every pre-existing file my paths changed (`git diff --diff-filter=MDR bb8929e1..HEAD -- integrations/java
integrations/spark/spark-3.5 integrations/spark/spark-4.2 client`), grouped where the answer is the same. "Mine" marks
my assigned pull requests. The rest are recorded because they are on my area's default path; their own reviewers own
the detail.

| # | What existed (main) | Now | PR | Default-on? | Justification in code/notes | Follow-up documented | Upstream acceptable as is | Restore size |
|---|---|---|---|---|---|---|---|---|
| 1 | Six policy execs committed for any `TableCatalog` whose `loadTable` returns a `SparkTable` with `openhouse.tableId` (main `SetRetentionPolicyExec.scala` etc., no catalog check) | `requirePolicies` refuses `NOT_ICEBERG` catalogs (`OpenHouseCapabilities.java:46-53,129-142`; exec call before `updateProperties`) | #100 (mine) | Yes | "fail instead of storing the patch" on REST servers (exec comments); the non-Iceberg case is not discussed | No | No: an unexplained refusal of a working statement | SMALL (one line ×2) |
| 2 | Spark `OpenHouseCatalog.capabilities()` = `{column-defaults}` (main `spark-3.5/.../OpenHouseCatalog.java:28`); header `X-OpenHouse-Client-Capabilities: column-defaults` on every request (`OpenHouseCatalog.java:172`, `CatalogRequestHeaders.from`) | On the default (1.12) build it declares none (`spark-3.5/.../OpenHouseCatalog.java:35-38`) and no header is sent, so the service gate (`ClientCapabilityGate`, on main) answers 422 for tables that require it | #91 code, #118 default | Yes, since #118 | D5/D11a (`OpenHouseCatalog.java:26-32`; `docs/plans/irc/25-optin-green-result.md:88`); pinned by `ColumnDefaultsCapabilityByIcebergLineTest` | Pinned, not slated for change | No, unless upstream adopts 1.12 and drops the read bridge together | FOUNDATION (undoing means the 1.5.2 default build, which #118 and everything after it builds on) |
| 3 | `OpenHouseTableOperations.loadMetadata` always applied the read-bridge overlay (main 1.2 runtime `OpenHouseTableOperations.java` ~183-190) | Skipped on V3 (`shared/.../OpenHouseTableOperations.java:194-196`), i.e. on the default build | #91/#110, #118 | Yes | D11a comment at `:182-185` | Same as row 2 | Same as row 2 | FOUNDATION (with row 2) |
| 4 | Commit request never carried `format-version` (1.5.2 cannot build v3 metadata) | `asksForFormatV3` adds `format-version=3` to the request properties for v3 metadata (`OpenHouseTableOperations.java:254-272,315-321`) | #124 | Only reachable on the 1.12 default; v≤2 requests unchanged (checked `asksForFormatV3`) | Javadoc `:259-271` | n/a | Yes, it is additive | SMALL |
| 5 | `OpenHouseCatalog.loadFileIO` used `new HadoopFileIO(this.conf)`; `create()` built request metadata with a null location | No-arg `HadoopFileIO` when `conf` is null; `create()` sets `openhouse:/location-assigned-by-service` (`OpenHouseCatalog.java:219-229,962-986`) | #87 | Yes | Javadoc; #87 says the request body is byte-identical (`OpenHouseCatalogCreateTableTest`). I checked by reading that `doCommit` never sends `metadata.location()` (`OpenHouseTableOperations.java` only uses `metadataFileLocation`) | n/a | Yes | SMALL |
| 6 | `CREATE NAMESPACE`/`DROP NAMESPACE` on the OpenHouse catalog failed with "Describing database is not supported" | On the 1.12 default: "Checking if database exists is not supported" (Iceberg 1.12 `SparkCatalog` calls `namespaceExists`) | #118 (Iceberg change) | Yes | D14b, declared in `SparkSqlSmokeTest.namespaceExistsCheckOnIceberg112` | Accepted | Probably yes (Iceberg's own change) | FOUNDATION (tied to the 1.12 default) |
| 7 | Modules `integrations/java/iceberg-1.2/*` and `integrations/spark/spark-3.1/*` built and published (`openhouse-java-runtime`, `openhouse-spark-runtime_2.12` and their uber jars) | Deleted; shared sources moved to `iceberg-1.5/.../src/shared` (R100 moves verified: `CatalogRequestHeaders`, `ReadBridge`, builders, exceptions, `SupportsGrantRevoke`, `SupportsUnlock` unchanged) | #110, #118 | Yes | D3; `docs/plans/irc/54-v9e-flip-result.md` "What was deleted" | Recorded | Needs LinkedIn's agreement; deleting a published artifact is not reversible for consumers | SMALL to re-add in history terms, but it fights the 1.12 default |
| 8 | Spark 3.5 runtime jar built against `com.linkedin.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.2.24` | Default build uses the fork version `1.12.0-ohfork.<sha>`, hosted nowhere; remote publishing refused | #87, #118 | Yes | `build.gradle:37-45`; `54-v9e-flip-result.md` | "Publishing ... stays Mike's decision" | No | FOUNDATION |
| 9 | Spark 3.5 uber jar classes: extensions, Java runtime | Also `com.linkedin.openhouse.capabilities.**` (unrelocated, including the 1.12 `RestTransport`), `spark.capabilities.*`, `spark.OpenHouseRestCatalog`, `ResolveRenameTableTarget`; Java runtime adds `OpenHouseRestAdapter`, `OpenHouseRestCatalog`, `IcebergLine` | #84, #100, #103, #119 (mine) | Yes (classes ship), inert unless used | Build comments | n/a | Yes, apart from D-2 | SMALL |
| 10 | Grants/SHOW GRANTS/UNLOCK resolved the catalog by reflection (`IcebergCatalogMapper`, `DynFields` on `SparkSessionCatalog.icebergCatalog`, `SparkCatalog.icebergCatalog`/`cacheEnabled`, `CachingCatalog.catalog`) | `HasIcebergCatalog.icebergCatalog()`, then unwrap `CachingCatalog` by `instanceof` (`OpenHouseCapabilities.java:155-168`) | #100 (mine) | Yes | Javadoc | n/a | Yes | No change: same object. Checked v1.5.2.24 `SparkSessionCatalog.icebergCatalog()` (383-388) and `SparkCatalog.icebergCatalog()` (1011-1013); same messages "does not support Grant Revoke Statements"/"does not support UNLOCK" |
| 11 | `Principal` Scala object; `ShowGrants` used `Principal.unapply(..).get`; `GrantRevoke` used `Principal(principal)` | Java `capabilities.Principal.toAcl/fromAcl`; Scala object delegates | #100 (mine) | Yes | n/a | n/a | Yes | No change: same default-locale `toUpperCase`, same `"PUBLIC"`/`"*"` |
| 12 | `UnlockTableExec(catalog, javaclient.UnlockTableTarget, ReasonEnum)` called `SupportsUnlock.unlockTable` | Strategy converts to capability types; `JavaClientCapabilities.Unlock` converts back; the original target object is kept (`JavaClientCapabilities.java:60-80,126-128`) | #100 (mine) | Yes | n/a | n/a | Yes | No change: same call, same object, `UnlockReason` values equal the service's `LockReason {LEGACY, SYSTEM_ONLY}` |
| 13 | Session extensions injected parser, `ResolveUnlockTable`, strategy | Also `ResolveRenameTableTarget` (`OpenhouseSparkSessionExtensions.scala:12`) | #119 (mine) | Yes (rule runs) | Javadoc | n/a | Yes | No plan change on the default path: the rule matches only when `isOpenHouseRest` (Iceberg `RESTCatalog` with an advertised capability). The OpenHouse catalog class resolves to `OPENHOUSE_CATALOG`; checked `CatalogCapabilities.isOpenHouseRest` :251-253 |
| 14 | SQL grammar and parser of the Spark 3.5 extensions | Unmodified (`.g4` and `OpenhouseSparkSqlExtensionsParser.scala` not in the diff for spark-3.5) | n/a | n/a | n/a | n/a | n/a | No change |
| 15 | `IcebergCatalogMapper.toIcebergCatalog` | Delegates to the resolver; no main callers left | #100 (mine) | Yes | Comment | n/a | Cruft | No change |
| 16 | `client/common` downloaded the OpenAPI CLI with `jar_download.sh` | Gradle dependency `openapi-generator-cli:5.3.0` copied by `Sync` | #123 | Build only | Comment | n/a | Yes | Not checked whether the generated client is byte-identical (#123 claims it) |
| 17 | delta-harness always used `OpenHouseCatalog` | `HARNESS_CATALOG_PATH=rest-shim` switches `openhouse` only; default `legacy` (`Env.scala`) | #86 (mine) | No | n/a | n/a | Yes | No change by default |
| 18 | Tests: `CreateTable*TestSpark3_5` enqueue one fewer 404; `AlterTableTestSpark3_5`, `SparkMultiSchemaEvolutionTest`, `ColumnDefaultSparkITest`, `RTASTest`, `OpenHouseViewSparkITest` edited; spark-3.5 and java itest tests moved to `src/shared` | Test-only | #115, #87, #110, others | n/a | #115 says these 3.5 tests were not compiled on main | n/a | n/a | No product change. Not verified: whether the removed 404 hides a request-count change on the 1.12 line |

Opt-in only (not behavior changes, checked off): `OpenHouseRestCatalog` and `OpenHouseRestAdapter` (selected only by
`catalog-impl`); `RestCapabilityClient` (reached only for Iceberg's own `RESTCatalog` with advertised names); the
Spark 4.2 runtime (only in the default 1.12 build, never loaded unless configured).

## 4. Cruft

### Two (or three) implementations of one thing

- Spark 4.2 extensions copy the 3.5 ones. 22 files are byte-identical, including the `.g4`, all plan nodes, five
  execs, `GrantableResourceTypes` and `ResolveRenameTableTarget`. Eight differ by 4 to 20 lines.
  - Iceberg itself copies per Spark version, so upstream has precedent. Still, `OpenHouseCapabilities.java` and
    `CatalogCapabilities.java` differ only in the Java-client adapter lines and comments.
  - Do: one shared source directory compiled by both Spark modules, with per-line overlays for the parser and
    strategy.
- Java-client vs capability-module duplicates:
  - `javaclient/mapper/Privileges.java` vs `capabilities/Privileges.java` (identical but for Lombok).
  - `javaclient/api/UnlockTableTarget` vs `capabilities/UnlockTableTarget` + `ValidatedUnlockTableTarget`.
  - Two `UnlockValidationException` classes.
  - Scala `Principal` (spark-3.5 `constants/Principal.scala`) delegating to `capabilities.Principal`.
  - Do: keep the capability types and have the Java client use them (or the reverse); delete the Scala object.
- The Spark 3.5 unlock round trip (`OpenhouseDataSourceV2Strategy.scala:36-42`, `JavaClientCapabilities.java:47-80`,
  `JavaClientTarget`).
  - #100's known gaps give the reason: "tests shared with the Spark 3.1 line pin them". #118 deleted Spark 3.1.
  - Do: parse into capability types as Spark 4.2 does (`OpenhouseSqlExtensionsAstBuilder.scala` 4.2 :67-70).
- Two `RestTransport` copies (`capabilities/src/iceberg-1.5` and `src/iceberg-1.12`): a cost of the two-line build,
  still needed while `-PicebergLinkedIn` exists.

### Dead code, unused parameters, switches that guard nothing

- `OpenHouseRestAdapter.CONFIG_OVERRIDES` (`OpenHouseRestAdapter.java:92-101`) advertises `policies`.
  `OpenHouseRestCatalog.properties()` returns `backing.properties()` (`OpenHouseRestCatalog.java:136-139`), and the
  resolver matches the in-process catalog by interface first (`OpenHouseCapabilities.java:55-66`), so no reader
  exists.
  - The Java runtime's `compileOnly project(':integrations:java:openhouse-catalog-capabilities')`
    (`openhouse-java-runtime/build.gradle:53-55`) exists only for it.
  - Do: delete both.
- `IcebergCatalogMapper` (spark-3.5 `mapper/IcebergCatalogMapper.scala`): only `IcebergCatalogMapperTest` calls it.
  Delete with that test, or keep as a deprecated alias with a reason.
- `RestTransport.open(catalogName, ...)` on 1.5: `catalogName` is unused there; documented as such. A cost of the two
  lines.
- `OpenHouseCapabilities.resolve` builds a `RestCapabilityClient` for every `RESTCatalog` resolution, including when
  nothing is advertised (`:73`). It is also called on every `RENAME` with a three-part target. Cheap, but build it
  only when needed.

### Leftovers and comment hygiene

- Orphaned Javadoc at `OpenHouseRestAdapter.java:240`. `/** Does nothing: the backing catalog outlives ... */` sits
  directly above another Javadoc block and belongs to `close()` at `:278-279`, which has none. Do: move it.
- Comments cite private ids:
  - `OpenHouseTableOperations.java:184` (D11a), `IcebergLine.java:12-13` (D5, D11).
  - `OpenHouseRestCatalog`-side: `spark-3.5/.../OpenHouseCatalog.java:31` (D5, D11a).
  - `openhouse-catalog-capabilities/build.gradle:1,6,38` (D23, D24, C2-3), `RestCapabilityClient.java:26` (D23).
  - `spark-3.5/openhouse-spark-runtime/build.gradle:58` (D23), `spark-4.2/openhouse-spark-runtime/build.gradle:3,7,86`
    (N3-x1, D23).
  - Test files cite many more: "Work item C3", D14b, D20, D37 in `SparkSqlSmokeTest` and `RestHttpPathSparkSqlSmokeTest`.
  - Do: replace with the reason itself.
- `openhouse-java-runtime/build.gradle:28-38` keeps three source roots named after the deleted 1.2 module
  (`src/shared`, `src/iceberg-version-specific`) and says they "can be folded". Names follow history. Do: fold into
  `src/main` (C11 is already noted).
- `OpenhouseSparkSqlExtensionsParser.scala` (3.5) imports `IcebergSqlExtensionsPostProcessor` and does not use it
  (pre-existing on main).

### Straddling two Iceberg lines (reflection and line checks)

- `OpenHouseRestAdapter.V3Endpoints` (`:372-399`) reflects `Endpoint.create` and `ConfigResponse.Builder.withEndpoints`,
  with a string-relocation workaround. The un-annotated 1.12 `get`/`post` overloads (`:249-276`) are there for the same
  reason. Needed only while this source compiles on 1.5.2.
- `IcebergLine` (`shared/.../IcebergLine.java`) detects the line at run time by building v3 metadata. It drives
  column-defaults behavior, the adapter's endpoints and many test expectations. Needed while both lines ship; a
  single-line build removes it.
- Test-only line branches in main-adjacent tests: `RestHttpPathSparkSqlSmokeTest.namespaceRouteRefused` and
  `SparkSqlSmokeTest.differences()` switch on `IcebergLine`.

### Tests that duplicate each other

- `spark-4.2/openhouse-spark-itest/src/smoke/.../SmokeJvmClasspathTest.java` and
  `src/test/.../extensions/SparkJvmClasspathTest.java` are the same 133-line check with different Javadoc.
- The Spark 4.2 itest has five service fixtures: `IrcReadTestService` 368 lines, `IrcExtensionsTestService` 302,
  `IrcSmokeTestService` 261, `ExtensionsTestService` 240, `SmokeTestService` 175, plus `IrcReadTestServiceProcess`.
  They overlap in bootstrap and seeding. Do: one fixture.
- `IcebergRestOpenHouseStatementsTest` (3.5) and `SparkIrcOpenHouseStatementsTest` (4.2) pin the same statement
  outcomes on two lines; acceptable as cross-line checks, but they share no code.

## 5. Tests

- **Claim no test supports and the suites contradict (#118, my area's default path):** `54-v9e-flip-result.md` says
  "No Spark SQL difference was found". On the default 1.12 build:
  - `SparkSqlSmokeTest.differences()` declares two differences on the legacy path itself
    (`namespaceExistsCheckOnIceberg112`).
  - `ColumnDefaultsCapabilityByIcebergLineTest` pins 422 for column-default tables.
  - The same note reports the harness at 7 failures vs 3 on the 1.5.2 baseline.
- **#100 "Behavior on the two existing paths is unchanged":** true for `SparkCatalog`/`SparkSessionCatalog`. No test
  covers a policy statement on a non-Iceberg `TableCatalog` that returns an OpenHouse `SparkTable`.
  `OpenHouseCapabilitiesTest.aNonIcebergCatalogOffersNothing` pins `offered().isEmpty()`, i.e. it pins the new refusal
  (D-1) as expected. The pin is wrong for policies.
- **#100 "returns the same object as the old reflection":** no test compares the old and new extraction.
  `IcebergCatalogMapperTest` (moved by #110, pre-existing) has no `@Test` annotations and uses Java `assert`, which is
  off without `-ea`. Its five methods never run, and could not fail if they did. `sessionCatalogIsUnwrappedToTheCatalogItWraps`
  covers one case. I verified the claim by reading Iceberg 1.5.2.24.
- **#84 "All 21 original smoke scenarios ... pass on both paths with no expectation changed":** cannot tell now.
  #128 later rewrote `SparkSqlSmokeTest` (954 lines) into a shared list, and its result says equivalence "was read,
  not proven by a diff of outcomes".
- **#117 "none is a defect":** its own known gaps list RENAME with a catalog-qualified target as "To be fixed", and
  #119 then fixed it. The declaration text and the gap disagree.
- **#119:** the Spark 3.5 rule has no plan-level unit test (declared); only the twin itest covers it.
- **#103:** the stub-server tests cover token, OAuth credential exchange, prefix and error mapping
  (`RestCapabilityClientTest.java:167-306`). Nothing covers TLS settings or custom `rest.auth.type` managers (D-5).
- **#107:** no product code changed (verified: tables test, CI YAML and a Python script only). Its stated gap, the
  Spark 4.2 `extensionsTest` failing in CI with an unknown cause, is not resolved by anything in my assignment.
- **Declared smoke differences: I checked 12 stated reasons against the code.** Each holds:
  - s19 CREATE NAMESPACE, rest-http: 1.12 refuses an unlisted endpoint; 1.5.2 sends it and the service refuses it.
  - s24 ALTER NAMESPACE and DROP NAMESPACE, rest-http: same reason as s19.
  - s24 DESCRIBE NAMESPACE: the baseline throws "Describing database is not supported" (`OpenHouseCatalog.java:487`).
  - s24 SHOW NAMESPACES IN db: "Openhouse supports 2-lvl namespace" (`:476`).
  - s25 SHOW TABLES and SHOW TABLES IN openhouse: `RESTSessionCatalog.checkNamespaceIsValid` throws "Invalid namespace"
    on both 1.5.2.24 (line 276) and 1.12 (1517-1520).
  - s27 UNLOCK of a missing table: `CapabilityErrorHandler` maps 404 to `NoSuchTableException` with the native message.
  - s29 RENAME to the same name: a native 400 becomes Iceberg's `BadRequestException`.
  - Legacy on 1.12, s19 and s24: "Checking if database exists is not supported" exists at `OpenHouseCatalog.java:510`
    (and on main at 504).
  - Rest-shim C3 orphan data file: matches `OpenHouseRestAdapter.updateTable`'s replace handling at commit.
  - s22 (RTAS planned as an overwrite on Spark 4) and s26 (DESCRIBE EXTENDED rows): attributed to Spark 4 and backed
    by `Spark42WithoutOpenHouseTest` on Iceberg's Hadoop catalog. Not re-derived from Spark source.
- **Skipped or assumed away:** rest-http on 1.5.2 is skipped by assumption (`RestHttpPathLineTest` pins the
  assumption). #128 says "the 18 Spark 4.2 write-statement checks are not ported to rest-http".

## 6. Per pull request

| PR | "Leaves true" holds? | Why |
|---|---|---|
| #84 C1 | Partly | Yes: `catalog-impl` selects the path and no existing production file changed (stat: 3 production files added, 0 changed). Cannot tell now: the "no expectation changed" count. Javadoc overstates the stale-base check (D-4); `prefix` is unsupported (D-3) |
| #86 T2b | Yes | Harness-only `Env.scala`; default `legacy` unchanged. Counts cannot be verified by reading |
| #100 C2-1/2 | Partly | Equivalent for Iceberg Spark catalogs (verified accessor, messages, Principal, unlock identity). Not unchanged for non-Iceberg catalogs on policy statements (D-1). "Spark 3.1 keeps its own copy" was later moot (#118). "Shipped Java jar names no module class" holds: only compile-time constants are used |
| #103 C2-3 | Yes, with declared gaps | Uses the catalog's uri, prefix, `header.*` and auth properties; per-statement session (D-5); client-side advertised names (D-6) are declared |
| #107 | Yes | Test, CI and script only; no product code |
| #101 N3-x1 | Yes | Byte-identity claim verified by diff (22 identical, 8 with the listed adaptations); jar composition by build config |
| #92 N3a | Yes (client side) | Test module plus server IRC changes (the server side is not mine); default-build classpath claim not re-verified |
| #117 N3b | Partly | Test-only and the default build is unchanged. "None is a defect" contradicts its own RENAME gap |
| #119 N6 | Yes | Rule logic matches `OpenHouseCatalog.renameTable` (`:350-385`): same messages and same ordering of checks. Inert on the OpenHouse catalog class |
| #128 N1-c/N7 | Yes, partly unprovable | No product code; one shared list. "None differs in rows read or in what a write stores" cannot be told by reading |

## 7. For the clean re-implementation

What must survive:

1. A capability vocabulary in a plain-Java module: server capabilities `policies`, `grants`, `unlock`, advertised in
   the IRC config response as `openhouse.capabilities`; the client capability `column-defaults` in
   `X-OpenHouse-Client-Capabilities`. Names are short and stable; keep them.
2. One resolver per Spark line that maps a Spark catalog to its capabilities. It uses
   `HasIcebergCatalog.icebergCatalog()` (still present on Spark 4.2) plus one `CachingCatalog` unwrap.
3. The resolver's order: catalogs that implement OpenHouse interfaces first, then Iceberg `RESTCatalog` by advertised
   names, then other Iceberg catalogs. Non-Iceberg catalogs must keep their old policy behavior (D-1).
4. `GrantsCapability` and `UnlockCapability` interfaces with plain value types, used by the Java client too (no second
   `Privileges` or `UnlockTableTarget`).
5. A small REST capability client for grants and lock routes that reuses the catalog's properties, HTTP client and
   auth manager, and maps errors as #103 does (400 to `IllegalArgumentException`, 404 to `NoSuch*`, 409 to
   `IllegalStateException`, the rest to Iceberg's defaults).
6. Policy statements that refuse on a REST server not advertising `policies` before writing anything.
7. The rename-target rule for OpenHouse-over-REST catalogs, with the legacy catalog's messages.
8. Spark 4.2 parser additions: `parsePlanWithParameters` passes parameters on; `parseRoutineParam` delegates.
9. If an in-process seam is kept at all (it is test scaffolding for comparing paths), keep it out of the shipped
   runtime jar or behind a clearly test-named module.
10. Shared smoke scenarios with declared differences that fail the suite when undeclared or stale. This is the right
    test pattern.

Do not carry over:

- The third copy of the extensions; the Java-client/capability round trip; `IcebergCatalogMapper`; the Scala
  `Principal`; `CONFIG_OVERRIDES`; the Java runtime's dependency on the capabilities module.
- Comments citing D-numbers or work items; `src/shared` naming.
- Duplicate classpath tests; five test-service fixtures.

Proposed upstream commit sequence (behavior-preserving first):

1. Add the capabilities module (names, interfaces, value types) with tests; no callers.
2. Make the Java client implement the capability interfaces with its existing types re-pointed (no behavior change;
   pin request bodies and headers).
3. Spark 3.5: introduce the resolver and switch grants, SHOW GRANTS and unlock to it; delete `IcebergCatalogMapper`
   (pin messages).
4. Spark 3.5: policy statements consult the resolver, refusing only on `RESTCatalog` without `policies`, unchanged
   elsewhere.
5. Add the REST capability client (per-line transport only while two Iceberg lines exist) and offer it from the
   resolver.
6. Add the rename-target rule for OpenHouse-over-REST.
7. Extract the extension sources into a shared directory; add the Spark 4.2 module with only parser and strategy
   overlays.
8. Separately and last, any default-line change (Iceberg 1.12 default, column-defaults capability semantics), with
   its own justification and owner sign-off.

## 8. Not checked

- `OpenHouseViewOperations` and the view routes of the adapter in depth (views belong to other pull requests); I read
  only the dispatch.
- `openhouse-rest-compat-kit` (V13), `apps/spark-3.5` including `TablesClientFactory` (not in my assigned diffs; the
  capability header there is on main at `apps/spark/.../TablesClientFactory.java:54`).
- Server-side IRC handlers that answer these clients (#92's `IcebergRestApiHandler` changes, the grants and lock
  routes).
- Byte-identity of the generated client after #123 and #139.
- Jar sizes: no build was allowed, so the jar contents above are inferred from build configuration, not measured.
- The Spark 4 attributions of s22 and s26 against Spark source.
- TLS behavior of `HTTPClient.builder(properties)` per line.
- Whether `CreateTable*TestSpark3_5`'s removed 404 hides a request-count change on 1.12.
