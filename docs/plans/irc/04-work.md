# Work and dependencies

Bottom line: 102 items in two repos, counted across this file, `30-work-defaults.md` and
`41-work-platforms-v3.md`; V9 is a pointer and is not counted. Each names what it needs and how it is proven. There
is no order beyond the "Needs" column: an item can land whenever its needs are in, and landing it leaves a valid
state. All of it is Claude's judgement, built on `03-inferred.md`.

Reading the tables: `oh` is the openhouse fork, `ib` is the iceberg fork. `J`, `Q` refer to
`03-inferred.md`. "Paths" means the catalog paths selectable by config: legacy, REST-shaped over legacy,
native REST.

## Rules for every item

- Tests come first. A change to a module starts with black-box tests of that module passing against the
  unchanged code.
- An item is done when its "Proven by" holds and everything that passed before still passes.
- A difference in Spark SQL behavior is decided by Claude when it is a two-way door and recorded in `13-decisions.md`; only one-way doors go to Mike (D10).

## Prerequisites

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| P1 | oh | Branch `irc-integration` equals LinkedIn main and is where all work lands (J26). **Proven 2026-11-22**: CI green on the fork. | none | Same commit id. CI green on it. |
| P2 | ib | Fork `openhouse-1.2.0` and `openhouse-1.5.2` equal LinkedIn's. **Done 2026-11-22** (`8d4afa5c`, `af77f055`). | none | Same commit ids. |

## Test foundation

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| T1 | oh | A small Spark SQL smoke suite runs on Spark 3.5 against the real embedded service, with the catalog path as a parameter. **Done 2026-11-22** (pull request 80). | P1 | Green in CI. A deliberately broken rule turns it red. |
| T2 | oh | The delta-harness is in the repo and runs on demand. **Done 2026-11-22** (pull request 85). Full runs recorded in `08-findings.md`. | P1 | One full recorded run. |
| T2b | oh | The delta-harness runs through the in-process REST-shaped path, selected by `HARNESS_CATALOG_PATH=rest-shim`. **Done 2026-11-22** (pull request 86). Counts and differences in `11-harness-via-seam.md`. | T2, C1 | The full harness runs in both modes on both paths, and the counts are recorded. |
| T3 | oh | Black-box tests pin the catalog plugin's behavior as it is. **Done 2026-11-22** (pull request 81). | P1 | Green on unchanged code. |
| T4 | oh | Black-box tests pin the service commit path over the native API, filling gaps only. **Done 2026-11-22** (pull request 82); conflict and soft-delete cases follow once the stand-in is faithful. | P1 | Green on unchanged code. |

## Iceberg line

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| I1 | ib | A clean branch at Apache 1.12.0 builds untouched. **Proven 2026-11-22** for the modules listed in F4a; full Spark suites not run. | none | Apache's own tests pass for api, core, orc, parquet, Spark 3.5, Spark 4.2. |
| I2 | ib | A ledger marks each of LinkedIn's 29 commits as already upstream, reapply, or build plumbing. **Done 2026-11-22**: 13 reapply, 11 upstream, 5 plumbing (`05-iceberg-patch-ledger.md`). | P2 | 7 rows re-checked by Claude. |
| I3 | ib | Core, API and ORC patches reapplied, one commit each. **Done 2026-11-22**: all core, API and ORC patches (iceberg pull requests 3 to 7). | I1, I2 | Each patch's test fails before and passes after. Apache tests still pass. |
| I4 | ib | Spark 3.5 patches reapplied, one commit each. **Done 2026-11-22** (same pull requests). | I3 | Same. |
| I5 | ib | The same Spark patches applied to the Spark 4.2 module where they apply. **Done 2026-11-22 (iceberg pull request 10): full Spark 4.2 suite 6199 tests and extensions 2958 tests, 0 failures at the tip.** | I4 | Same. |
| I6a | oh | Done 2026-11-22, merged (pull request 87). An opt-in build switch builds the newest OpenHouse variant against the fork's 1.12 line, and the default build is unchanged. The fork commit is pinned in the repo and published to a build-local Maven directory by a script. Iceberg side: pull request 9 (`irc/publish-plumbing`) merged into `openhouse-1.12.0` as merge commit 984904f02. | I8 | The default build's CI is unchanged. The opt-in build compiles against the fork's artifacts. |
| I6b | oh | Done 2026-11-22, merged (pull request 87). Source ports for removed Iceberg APIs (`addSchema(Schema,int)`, `GenericAppenderFactory`) and the Java 17 test import. | I6a | The opt-in build compiles all test sources on Java 17. |
| I6c | oh | Done 2026-11-22, merged (pull request 87). Client fixes for 1.12 runtime changes: null table location (D13), and `HadoopFileIO` with a null configuration. | I6a | The opt-in build passes the catalog and SmokeTest suites that these changes affect. |
| I6d | oh | **Not started.** Remote resolution of the fork's artifacts for CI and other developers. Options are recorded in `12-openhouse-on-1.12-scout.md`. | I6a | A clean checkout builds the opt-in variant with no local publish step. |
| I6e | oh | **Decided (D3): the 1.2 / Spark 3.1 line is removed from the integration branch's build when V9e lands.** | a decision | The decision is recorded in `13-decisions.md`. The 1.2 line is retired from the integration build. |
| I6f | oh | **Done 2026-11-23 (pull request 105).** Result in `40-i6f-full-regression-result.md`. Full regression on the opt-in build: housetables, jobs, apps, Docker-backed suites, and Spark 3.5.2 versus 3.5.9 runtime. | I6b, I6c, X0 | The whole existing suite passes on the opt-in build, and every difference is listed. |
| I6g | oh | **Done 2026-11-22 (pull request 91).** Under the opt-in switch the Iceberg 1.2 / Spark 3.1 projects are excluded from the build (D15). | I6a | The opt-in build resolves and compiles every remaining project, and the default build is unchanged. |
| I6h | oh | **Done 2026-11-22 (pull request 91).** Tests whose expected result differs by Iceberg line state both expectations (D14): the RTAS partition-column case and the `CREATE NAMESPACE` message. | I6a | Those two tests pass on both builds. |

## Client seam

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| C1 | oh | A config value runs the plugin as `RESTCatalog` over the legacy client. Default unchanged. **Done 2026-11-22** on the Spark 3.5 line (pull request 84); differences listed in `09-seam-result.md`. | T1, T3 | T1 and T3 pass on both paths. Differences listed. |
| C2-1 | oh | **Done 2026-11-22 (pull request 100).** Result in `34-c2-capabilities-resolver-result.md`. The `openhouse-catalog-capabilities` module (names, interfaces, `UnlockReason`, `Privileges`, `Principal`). The Java runtime's catalogs implement it through adapters. Old `javaclient.api` types are kept. | none | Unit tests. Spark 3.5 `statementTest` and `catalogTest` unchanged. |
| C2-2 | oh | **Done 2026-11-22 (pull request 100).** The resolver in the 3.5 extensions replaces `IcebergCatalogMapper` at all 5 call sites. Policy execs check `policies`. `HasIcebergCatalog` is used where present. | C1, C2-1 | Resolver unit tests (legacy, shim and fake-`RESTCatalog` properties). Smoke tests s16, s17 and s27 green on legacy and rest-shim. |
| C2-3 | oh | **Done 2026-11-23 (pull request 103).** Result in `38-c2-3-rest-capability-client-result.md`. `RestCapabilityClient` with a per-line transport over `HTTPClient` and `AuthManager`, used by the resolver. | C2-2, V5-3 | Spark 3.5 itest: GRANT, REVOKE, SHOW GRANTS and UNLOCK on `type=rest`. |

## Service

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| V1 | oh | **Done 2026-11-22 (pull request 89).** The commit path accepts a complete candidate metadata. Rules and persistence run without native decoding. No behavior change. Result in `23-v1-commit-core-result.md`. | T1, T4 | T1, T4 and the existing suite unchanged. |
| V2 | oh | IRC config, namespaces, table list, load, exists. **Done 2026-11-22 (pull request 88).** Result in `19-v2-irc-read-result.md`. | T4 | `RESTCatalog` reads tables that the legacy path wrote. |
| V3 | oh | **Done 2026-11-22 (pull request 94).** IRC table create, staged create, commit, drop, rename, with every existing rule holding. Result in `27-v3-irc-write-result.md`. | V1, V2 | T4's scenarios through IRC leave the same stored state. Rule violations are rejected. |
| V4 | oh | **Done 2026-11-22 (pull request 95).** IRC views, without rename (D27). Result in `31-v4-irc-views-result.md`. | V2 | View scenarios through IRC match the native API. |
| V5-0 | oh | **Done 2026-11-22 (pull request 96).** Red pins for the stray policy key, with the interim refusal of D25 (a 400, so the interim outcome is a refusal, not a policy). | V3 | An `IrcWriteRoutesTest` case, plus a Spark 3.5 `IcebergRestSparkSqlTest` case. |
| V5-gap | oh | **Done 2026-11-22 (pull request 96).** The native update validator now runs on IRC commits and replaces; the gaps are recorded in `27-v3-irc-write-result.md`. | V3 | A red MockMvc test. |
| V5-1 | oh | **Done 2026-11-22 (pull request 99).** Result in `33-v5-policies-over-irc-result.md`. Server-side policy patch translation on commit, create and staged completion, with validators. The wire shape is a one-way door at release (D24). | V5-0 | An IRC patch and a native PUT with client-merged policies give a byte-equal `policies` property. Every rule is refused with the native status and message. Audit event. |
| V5-2 | oh | **Done 2026-11-22 (pull request 99).** Config advertises `openhouse.capabilities=policies`, and the adapter's config says the same. One-way door at release (D24). | V5-1 | Config test. V5-0's policy pins flip to green. |
| V5-3 | oh | **Done 2026-11-22 (pull request 102).** Result in `36-v5-3-grants-unlock-endpoints-result.md`. ACL and lock routes under `/iceberg`, advertising `grants,unlock`. One-way door at release (D24). | V5-2 | MockMvc equivalence with the native routes: status, message, authorization calls, audit and lock state after. |
| V3b | oh | The IRC config route's FileIO default follows the cluster's storage configuration. | V3 | A cluster configured for a non-Hadoop storage advertises the matching FileIO. |
| V3c | oh | Failed IRC requests appear in the service audit. | V3 | A refused IRC request produces the same audit event class as a refused native one. |
| V6 | oh | **Done 2026-11-23 (pull request 112).** Result in `46-v6-v7-metrics-register-result.md`. IRC metrics report: counted and logged, not stored (D34). | V2 | A report arrives where today's reports go. |
| V7 | oh | **Done 2026-11-23 (pull request 112).** Result in `46-v6-v7-metrics-register-result.md`. IRC register table: a native create read from the file (D34). | V3 | A registered table loads and commits. |
| V8 | oh | Multi-table commit. Investigate first (J24). | V3 | Two tables commit together or neither does, under injected failure. |
| V9 | oh | Replaced by V9a to V9e, which make the 1.12 line the default build. The order is in `40-i6f-full-regression-result.md`. Other rows that need V9 need V9e. | none | See V9a to V9e. |
| V10a | oh | **Done 2026-11-22 (pull request 102).** The service's IRC config route advertises exactly the endpoints it serves, including view support, so Iceberg 1.12 clients use HEAD existence checks and views and do not assume routes that are not served. | V4 | A 1.12 `RESTCatalog` creates and loads a view and uses HEAD for existence against the service. |
| V10 | oh | IRC endpoint list advertised. View registration. Spec revision of the new line. | V9, V4 | A current `RESTCatalog` negotiates against it. |
| V11 | oh | IRC scan planning. | V9, V2 | Planned scans return the same files as client-side planning. |
| V12 | oh | IRC credentials and signing. Investigate first (J24). | V9, V2 | Defined by the investigation. |
| V13 | oh | Apache's REST compatibility kit passes against the service. What counts as passing is D32. The first measurement is V13a (`39-v13-compat-kit-result.md`). | V9, V3, V4 | Kit green. Every exclusion listed with its reason. |

## Native path, platforms and v3 functions

These rows are in `41-work-platforms-v3.md`. They are part of the total in the bottom line.

## Column defaults on the new line

Rows W1 to W13 are in `30-work-defaults.md`. They are part of the total in the bottom line.

## Added since the list was written

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| T5 | oh | The house table test stand-in checks versions and deletes like the real store. **Done 2026-11-22** (pull request 83). | T4 | Conflict and soft-delete tests pass at the HTTP boundary. |
| T6 | oh | A test server that stores grants, so grant behavior is observable through Spark SQL. | T1 | Smoke scenario 17 asserts a grant is listed and then gone. |
| I7 | ib | The Iceberg fork's CI survives dependency download limits. | none | One full CI run completes on the 1.12 branch. |
| I8 | ib | All reapplied patches hold together on one branch, with the full Spark 3.5 suites run. | I3, I4 | **Done 2026-11-22** (branch `irc/reapply-all` tip f180ae589, draft pull request 8 in mkuchenbecker/iceberg). The 8 test failures and 4 checkstyle failures are fixed on their source branches and merged forward. Verified by the touched classes plus compile and style on the combined branch; full suites not re-run after the fixes. |
| X0 | ib, oh | The new Iceberg line has a defined, tested behavior for existing v2 tables that carry OpenHouse column defaults, with native Iceberg defaults as the target (S47, D5). Design done (`14-native-defaults-design.md`, D11); the work is W1 to W8. | I3 | Defined by the design in progress. |
| I9 | ib | Recorded and deliberately unaddressed (S45, D4). | a decision | A merge-on-read delete succeeds on a non-Hadoop file IO. |
| C3 | oh | A gated RTAS on the REST-shaped path writes no data file before the server rejects it. | C1 | No orphan file after a rejected RTAS, on both paths. |
| C4 | oh | Property and policy commits from a stale handle fail on the REST-shaped path as they do on the legacy path. | C1 | `rawCommitWithStaleBaseFails` passes on both paths for property-only commits. |
| T7 | oh | Storage performance tiering replaces the replication-factor property. | nothing | Not designed (S45). |
| C5 | oh | **Done 2026-11-22 (pull request 97).** The in-process REST-shaped path advertises its endpoints, so view statements work on Iceberg 1.12. | C1, I6a | The two rest-shim view scenarios pass under the opt-in build. |
| I10 | oh | **Done 2026-11-22 (pull request 97).** A CI job builds and tests the opt-in Iceberg 1.12 build. | I6a | A run on `irc-integration` passes or lists its known failures. |
| N3a | oh | **Done 2026-11-22 (pull request 92).** Result in `26-n3a-spark42-read-result.md`. Spark 4.2 on the fork's Iceberg 1.12 reads OpenHouse tables through an unmodified `RESTCatalog`, under the opt-in build only. | V2, I5, I6a | A committed Spark 4.2 test runs the read statements against the service. |
| V13a | oh | **Done 2026-11-23 (pull request 104).** Result in `39-v13-compat-kit-result.md`. Apache's compatibility kit runs against the service under the opt-in build with a reviewed list of expected outcomes. | V3, V4, I6a | The kit task passes exactly when the outcome matches the list in both directions. |
| V13b | oh | **Done 2026-11-23 (pull request 106).** Result in `42-v13b-spec-deviations-result.md`. The IRC routes follow the specification where the kit found deviations: cross-database rename status, format version on staged create, rename onto a view's name; a requested location is overridden (D30). | V13a | The kit's list shrinks by the tests that now pass. Done: 38 of 160 pass, up from 31. |
| I11 | ib | **Done 2026-11-23 (iceberg pull request 12, merge commit `a88f556c`).** An explicit time-travel read works while a write-audit-publish branch is set, on Spark 3.5 (Spark 4.2 needed no change). The OpenHouse pin still names the older fork commit `28742df3e538`; the bump is part of V9a. | I8 | The harness case `interact.branch.ttBeforeBranchPoint` passes on the 1.12 line. Proven so far by new Spark 3.5 and 4.2 tests in the fork; the harness was not re-run. |
| V14 | oh | A create transaction through IRC keeps its schema, partition spec and sort order history, not only the final ones with id 0 (kit group CREATEHIST). Found by V13b. | V13b | The kit test `testCompleteCreateTransactionMultipleSchemas` passes and leaves the expected-outcomes list. |
| C6 | oh | **Done 2026-11-23 (pull request 111).** Result in `44-v9abc-flip-prep-result.md`. The opt-in Iceberg job runs on pull requests as well as post-merge, through one reusable workflow called from `post-merge-build.yml` and `pr-validations.yml` (D33). | V9a | A pull request shows both jobs, and a deliberately broken suite under the switch turns the pull request red. |
| V15 | oh | A create whose metadata location will not fit House Tables' 255 characters is refused before anything is written, on both Iceberg lines. Found by pull request 108. | none | A test: such a create is refused with a message naming the limit, and leaves no metadata file. |
| T8 | oh | The unexplained Spark 4.2 itest `test` failure (run 45099517406) is explained and fixed. Do it when it recurs; the job now prints failed tests. A local one-off failure of `SparkIrcEndpointsTest.existenceChecksAreHeadRequests` (empty access log, passed on two reruns) may be the same thing. Pull request 112 made that test wait for its last access-log line; T8 stays open until a CI failure names its test. | none | The failing test is named and passes repeatedly. |
| T9 | oh | **Done 2026-11-23 (pull request 111).** The compatibility kit's service keeps its warehouse in the short temporary directory the Spark 4.2 services use. | none | The kit's longest location no longer depends on the checkout path, and its outcomes list is unchanged. |
| V9a | oh | **Done 2026-11-23 (pull request 109).** The fork's published artifacts survive `clean` and the opt-in build passes `clean build`. | I6a | `./gradlew -PicebergFork clean build` passes. |
| V9b | oh | **Done 2026-11-23 (pull request 109).** `iceberg-aws` comes from the fork's line under the switch. | I6a | No LinkedIn 1.2 Iceberg artifact is on any classpath under the switch. |
| V9c | oh | **Done 2026-11-23 (pull request 110).** Sources the 1.5 projects reuse from the Iceberg 1.2 / Spark 3.1 projects live in the 1.5 projects. | I6g | The 1.2 projects can be removed from the build without touching the 1.5 projects. |
| V9d | oh | **Running (2026-11-23, branch `irc/v9d-deployables-1.12`).** Docker images and deployables use the Spark 3.5 apps jar and a Java 17 base. | V9c | The image builds and the Docker integration step pass under the switch. |
| V9e | oh | The 1.12 line is the default build and the Iceberg 1.2 / Spark 3.1 projects are removed (D3). The 11 blockers are in `44-v9abc-flip-prep-result.md`. | V9d, T10 (V9a to V9c, I11, V13b are done) | Default CI builds and tests the 1.12 line. |
| T10 | oh | The five Spark 3.5 end-to-end test classes `AlterTable*Spark3_5` and `CreateTable*Spark3_5` are compiled and run. Today the Spark 3.5 itest's excludes `**/AlterTable*.java` and `**/CreateTable*.java` also match them, so after V9e those statements would be tested nowhere. | V9c | The five classes compile and run on both builds, and every failure they show is fixed or recorded. V9e needs it. |
| T11 | oh | The 1.12 line's newer AWS SDK and `iceberg-aws` are tested against real S3, not only mocks: `S3FileIO` reads and writes, STS, KMS and the new default HTTP client. Needs an environment with S3. | V9b | A read, a write and a delete against real S3 on the opt-in build. |
| C7 | oh | The publish script's lock works without `flock`, which macOS lacks. Small. | V9a | The script publishes on macOS, and concurrent runs still serialize on Linux. |
| C8 | oh | `integrations/python/dataloader/CLAUDE.md` lines 36 and 49 name the new `dummy.token` path. Small; do with V9e. | V9c | A grep for the old path finds nothing. |
| V16 | oh | The IRC staged-create completion refuses a directory that a live or soft-deleted row uses, so a drop of the new table cannot purge a renamed table's files. Next item to start. Found by V7 (`21-findings-continued.md`). | V3, V7 | A test that fails first: complete a staged create naming a renamed-away table's directory, see it refused with nothing changed. |
| N5 | oh | The native create with `openhouse.tableUUID` accepts the directory of a live table that was renamed away. Per D9, pin it with a test that shows the behavior, and record the fix as a change that needs a decision, because it changes native behavior. | none | A pinning test shows the behavior. The fix is written up as a decision for Mike, not made. |
| T13 | oh | Tests for the four register refusals that have none: a partition-spec history, field ids a create would renumber, statistics files, a replica table type. Small. | V7 | Four tests, each failing first if its refusal is removed. |
| T12 | oh | `:integrations:java:openhouse-rest-compat-kit:test` no longer fails with "No tests found" (the kit's own task is `restCompatKitTest`; `test` is not in CI's list). Small. | none | `./gradlew -PicebergFork build` has no failing kit task. |

The scouting run confirmed X0 from the OpenHouse side: 8 OpenHouse tests fail on 1.12 because non-null column defaults are rejected on v2 tables.

## State

The current state is in `18-state.md`.
