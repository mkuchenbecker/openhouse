# Work and dependencies

Bottom line: 81 items in two repos, counted across this file and `30-work-defaults.md`. Each names what it
needs and how it is proven. There is no order beyond the "Needs" column: an item can land whenever its needs are
in, and landing it leaves a valid state. All of it is Claude's judgement, built on `03-inferred.md`.

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
| I6e | oh | **Decided (D3): the 1.2 / Spark 3.1 line is removed from the integration branch's build when V9 lands.** | a decision | The decision is recorded in `13-decisions.md`. The 1.2 line is retired from the integration build. |
| I6f | oh | **Not started.** Full regression on the opt-in build: housetables, jobs, apps, Docker-backed suites, and Spark 3.5.2 versus 3.5.9 runtime. | I6b, I6c, X0 | The whole existing suite passes on the opt-in build, and every difference is listed. |
| I6g | oh | **Done 2026-11-22 (pull request 91).** Under the opt-in switch the Iceberg 1.2 / Spark 3.1 projects are excluded from the build (D15). | I6a | The opt-in build resolves and compiles every remaining project, and the default build is unchanged. |
| I6h | oh | **Done 2026-11-22 (pull request 91).** Tests whose expected result differs by Iceberg line state both expectations (D14): the RTAS partition-column case and the `CREATE NAMESPACE` message. | I6a | Those two tests pass on both builds. |

## Client seam

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| C1 | oh | A config value runs the plugin as `RESTCatalog` over the legacy client. Default unchanged. **Done 2026-11-22** on the Spark 3.5 line (pull request 84); differences listed in `09-seam-result.md`. | T1, T3 | T1 and T3 pass on both paths. Differences listed. |
| C2-1 | oh | **In progress on `irc/c2-capabilities-resolver`.** The `openhouse-catalog-capabilities` module (names, interfaces, `UnlockReason`, `Privileges`, `Principal`). The Java runtime's catalogs implement it through adapters. Old `javaclient.api` types are kept. | none | Unit tests. Spark 3.5 `statementTest` and `catalogTest` unchanged. |
| C2-2 | oh | **In progress on `irc/c2-capabilities-resolver`.** The resolver in the 3.5 extensions replaces `IcebergCatalogMapper` at all 5 call sites. Policy execs check `policies`. `HasIcebergCatalog` is used where present. | C1, C2-1 | Resolver unit tests (legacy, shim and fake-`RESTCatalog` properties). Smoke tests s16, s17 and s27 green on legacy and rest-shim. |
| C2-3 | oh | `OpenHouseRestCapabilityClient` (`HTTPClient`, `AuthManager`, `ResourcePaths`), used by the resolver. | C2-2, V5-3 | Spark 3.5 itest: GRANT, REVOKE, SHOW GRANTS and UNLOCK on `type=rest`. |

## Service

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| V1 | oh | **Done 2026-11-22 (pull request 89).** The commit path accepts a complete candidate metadata. Rules and persistence run without native decoding. No behavior change. Result in `23-v1-commit-core-result.md`. | T1, T4 | T1, T4 and the existing suite unchanged. |
| V2 | oh | IRC config, namespaces, table list, load, exists. **Done 2026-11-22 (pull request 88).** Result in `19-v2-irc-read-result.md`. | T4 | `RESTCatalog` reads tables that the legacy path wrote. |
| V3 | oh | **Done 2026-11-22 (pull request 94).** IRC table create, staged create, commit, drop, rename, with every existing rule holding. Result in `27-v3-irc-write-result.md`. | V1, V2 | T4's scenarios through IRC leave the same stored state. Rule violations are rejected. |
| V4 | oh | **Done 2026-11-22 (pull request 95).** IRC views, without rename (D27). Result in `31-v4-irc-views-result.md`. | V2 | View scenarios through IRC match the native API. |
| V5-0 | oh | **Done 2026-11-22 (pull request 96).** Red pins for the stray policy key, with the interim refusal of D25 (a 400, so the interim outcome is a refusal, not a policy). | V3 | An `IrcWriteRoutesTest` case, plus a Spark 3.5 `IcebergRestSparkSqlTest` case. |
| V5-gap | oh | **Done 2026-11-22 (pull request 96).** The native update validator now runs on IRC commits and replaces; the gaps are recorded in `27-v3-irc-write-result.md`. | V3 | A red MockMvc test. |
| V5-1 | oh | **In progress on `irc/v5-policies-over-irc`.** Server-side policy patch translation on commit, create and staged completion, with validators. The wire shape is a one-way door at release (D24). | V5-0 | An IRC patch and a native PUT with client-merged policies give a byte-equal `policies` property. Every rule is refused with the native status and message. Audit event. |
| V5-2 | oh | **In progress on `irc/v5-policies-over-irc`.** Config advertises `openhouse.capabilities=policies`, and the adapter's config says the same. One-way door at release (D24). | V5-1 | Config test. V5-0's policy pins flip to green. |
| V5-3 | oh | ACL and lock routes under `/iceberg`, advertising `grants,unlock`. One-way door at release (D24). | V5-2 | MockMvc equivalence with the native routes: status, message, authorization calls, audit and lock state after. |
| V3b | oh | The IRC config route's FileIO default follows the cluster's storage configuration. | V3 | A cluster configured for a non-Hadoop storage advertises the matching FileIO. |
| V3c | oh | Failed IRC requests appear in the service audit. | V3 | A refused IRC request produces the same audit event class as a refused native one. |
| V6 | oh | IRC metrics report. | V2 | A report arrives where today's reports go. |
| V7 | oh | IRC register table. | V3 | A registered table loads and commits. |
| V8 | oh | Multi-table commit. Investigate first (J24). | V3 | Two tables commit together or neither does, under injected failure. |
| V9 | oh | The service links the new Iceberg line. Service modules on JDK 17. Existing clients unchanged. | I3, I6, T1, T4 | Whole existing suite. T1 on legacy paths. T2 full run. |
| V10a | oh | The service's IRC config route advertises exactly the endpoints it serves, including view support, so Iceberg 1.12 clients use HEAD existence checks and views and do not assume routes that are not served. | V4 | A 1.12 `RESTCatalog` creates and loads a view and uses HEAD for existence against the service. |
| V10 | oh | IRC endpoint list advertised. View registration. Spec revision of the new line. | V9, V4 | A current `RESTCatalog` negotiates against it. |
| V11 | oh | IRC scan planning. | V9, V2 | Planned scans return the same files as client-side planning. |
| V12 | oh | IRC credentials and signing. Investigate first (J24). | V9, V2 | Defined by the investigation. |
| V13 | oh | Apache's REST compatibility kit passes against the service. | V9, V3, V4 | Kit green. Every exclusion listed with its reason. |

## Native path and platforms

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| N1 | oh | The same config value selects native REST over HTTP on Spark 3.5. | C2-3, V3, V4, V5-3 | T1 identical on all three paths. Differences priced. |
| N2 | oh | A Spark 3.5 runtime built on the new Iceberg line. | I4, I6, N1 | T1 and T2 pass on it. |
| N1-c | oh | `SparkSqlSmokeTest` gains a third path, `rest-http` (`SparkCatalog type=rest`). Policy, grant and unlock cases pass. Differences are listed. | C2-3 | s16, s17 and s27 plus a test per SET POLICY form, on all three paths. |
| N3 | oh | A Spark 4.2 runtime, IRC only, with capabilities and SQL extensions. | I5, I6, V3, V4, V5-1, V5-2, V5-3 | T1 ported to Spark 4.2 passes. |
| N3-x1 | oh | A Spark 4.2 and Scala 2.13 extensions module: ANTLR 4.13.1, the new parser methods, the resolver, `SQLConf.get`. It depends only on C2-1. | C2-2 | Ported parser and statement unit tests. |
| N3-x2 | oh | A Spark 4.2 itest: every OpenHouse statement over IRC, with no OpenHouse catalog class on the classpath. | N3-x1, V5-3 | A 4.2 itest beside the read itest, asserting stored state through the OpenHouse Java client. |
| N3b | oh | Spark 4.2 on Iceberg 1.12 writes OpenHouse tables over IRC. | V3, N3a, C5 | The Spark SQL write statements pass in the Spark 4.2 module under the opt-in build. |

## v3 functions

Each exists on the native path only (J3).

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| X1 | oh | Tables can be created at v3 or upgraded from v2, gated per table. The service returns v3 metadata exactly as committed. | V9, V3 | Round-trip test. v2 tables untouched. |
| X2 | oh | Deletion vectors written and read through Spark SQL. | X1, N2 or N3 | Delete, update and merge scenarios on a v3 table. |
| X3 | oh | Row lineage maintained across writes and compaction. | X1, N2 or N3 | Row ids stable across a rewrite. |
| X4 | oh | Native default values on v3 tables. The v2 read bridge is untouched. See W7. | X1, N2 or N3 | Add a defaulted column, read old files. |
| X5 | oh | New v3 types pass schema validation and column policies. | X1 | Create and evolve with each type. |
| X6 | oh | Maintenance jobs run on the new line and are correct for X2 to X5. | V9, I4, X2 | Each job on a v3 table loses no live data. |
| X7 | oh | Replication of v3 tables. Investigate first (J24). | X1 | Defined by the investigation. |

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

The scouting run confirmed X0 from the OpenHouse side: 8 OpenHouse tests fail on 1.12 because non-null column defaults are rejected on v2 tables.

## State

The current state is in `18-state.md`.
