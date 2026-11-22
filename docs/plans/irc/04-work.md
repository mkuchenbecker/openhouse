# Work and dependencies

Bottom line: 49 items in two repos. Each names what it needs and how it is proven. There is no order
beyond the "Needs" column: an item can land whenever its needs are in, and landing it leaves a valid
state. All of it is Claude's judgement, built on `03-inferred.md`.

Reading the tables: `oh` is the openhouse fork, `ib` is the iceberg fork. `J`, `Q` refer to
`03-inferred.md`. "Paths" means the catalog paths selectable by config: legacy, REST-shaped over legacy,
native REST.

## Rules for every item

- Tests come first. A change to a module starts with black-box tests of that module passing against the
  unchanged code.
- An item is done when its "Proven by" holds and everything that passed before still passes.
- A difference in Spark SQL behavior stops the item and goes to Mike as a priced decision (J18).

## Prerequisites

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| P1 | oh | Branch `irc-integration` equals LinkedIn main and is where all work lands (J26). **Proven 2026-11-22**: CI green on the fork. | none | Same commit id. CI green on it. |
| P2 | ib | Fork `openhouse-1.2.0` and `openhouse-1.5.2` equal LinkedIn's. **Done 2026-11-22** (`8d4afa5c`, `af77f055`). | none | Same commit ids. |

## Test foundation

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| T1 | oh | A small Spark SQL smoke suite runs on Spark 3.5 against the real embedded service, with the catalog path as a parameter. **Done 2026-11-22** (pull request 80). | P1 | Green in CI. A deliberately broken rule turns it red. |
| T2 | oh | The delta-harness is in the repo and runs on demand. **Done 2026-11-22** (draft pull request 85). Full runs recorded in `08-findings.md`. | P1 | One full recorded run. |
| T2b | oh | The delta-harness runs through the in-process REST-shaped path, selected by `HARNESS_CATALOG_PATH=rest-shim`. **Done 2026-11-22** (draft pull request 86, into `irc/c1-rest-shaped-seam`). Counts and differences in `11-harness-via-seam.md`. | T2, C1 | The full harness runs in both modes on both paths, and the counts are recorded. |
| T3 | oh | Black-box tests pin the catalog plugin's behavior as it is. **Done 2026-11-22** (pull request 81). | P1 | Green on unchanged code. |
| T4 | oh | Black-box tests pin the service commit path over the native API, filling gaps only. **Done 2026-11-22** (pull request 82); conflict and soft-delete cases follow once the stand-in is faithful. | P1 | Green on unchanged code. |

## Iceberg line

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| I1 | ib | A clean branch at Apache 1.12.0 builds untouched. **Proven 2026-11-22** for the modules listed in F4a; full Spark suites not run. | none | Apache's own tests pass for api, core, orc, parquet, Spark 3.5, Spark 4.2. |
| I2 | ib | A ledger marks each of LinkedIn's 29 commits as already upstream, reapply, or build plumbing. **Done 2026-11-22**: 13 reapply, 11 upstream, 5 plumbing (`05-iceberg-patch-ledger.md`). | P2 | 7 rows re-checked by Claude. |
| I3 | ib | Core, API and ORC patches reapplied, one commit each. **Done 2026-11-22**: all core, API and ORC patches (iceberg pull requests 3 to 7). | I1, I2 | Each patch's test fails before and passes after. Apache tests still pass. |
| I4 | ib | Spark 3.5 patches reapplied, one commit each. **Done 2026-11-22** (same pull requests). | I3 | Same. |
| I5 | ib | The same Spark patches applied to the Spark 4.2 module where they apply. | I4 | Same. |
| I6a | oh | **In progress** on branch `irc/i6-iceberg-1.12-optin`. An opt-in build switch builds the newest OpenHouse variant against the fork's 1.12 line, and the default build is unchanged. The fork commit is pinned in the repo and published to a build-local Maven directory by a script. | I8 | The default build's CI is unchanged. The opt-in build compiles against the fork's artifacts. |
| I6b | oh | **In progress** on the same branch. Source ports for removed Iceberg APIs (`addSchema(Schema,int)`, `GenericAppenderFactory`) and the Java 17 test import. | I6a | The opt-in build compiles all test sources on Java 17. |
| I6c | oh | **In progress** on the same branch. Client fixes for 1.12 runtime changes: null table location, and `HadoopFileIO` with a null configuration. | I6a | The opt-in build passes the catalog and SmokeTest suites that these changes affect. |
| I6d | oh | **Not started.** Remote resolution of the fork's artifacts for CI and other developers. Options are recorded in `12-openhouse-on-1.12-scout.md`. | I6a | A clean checkout builds the opt-in variant with no local publish step. |
| I6e | oh | **Waiting on Mike.** Fate of the Iceberg 1.2 / Spark 3.1 line once shared server modules need Java 17 and Iceberg 1.12. | a decision | The decision is recorded here. The 1.2 line is either kept with its own pinned copies or retired. |
| I6f | oh | **Not started.** Full regression on the opt-in build: housetables, jobs, apps, Docker-backed suites, and Spark 3.5.2 versus 3.5.9 runtime. | I6b, I6c, X0 | The whole existing suite passes on the opt-in build, and every difference is listed. |

## Client seam

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| C1 | oh | A config value runs the plugin as `RESTCatalog` over the legacy client. Default unchanged. **Done 2026-11-22** on the Spark 3.5 line (pull request 84); differences listed in `09-seam-result.md`. | T1, T3 | T1 and T3 pass on both paths. Differences listed. |
| C2 | oh | Client features are capabilities. SQL extensions find features by capability on every path. | C1 | Existing extension tests and T1 pass on both paths. |

## Service

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| V1 | oh | The commit path accepts a complete candidate metadata. Rules and persistence run without native decoding. No behavior change. | T1, T4 | T1, T4 and the existing suite unchanged. |
| V2 | oh | IRC config, namespaces, table list, load, exists. | T4 | `RESTCatalog` reads tables that the legacy path wrote. |
| V3 | oh | IRC table create, staged create, commit, drop, rename, with every existing rule holding. | V1, V2 | T4's scenarios through IRC leave the same stored state. Rule violations are rejected. |
| V4 | oh | IRC views. | V2 | View scenarios through IRC match the native API. |
| V5 | oh | OpenHouse capabilities are served and advertised over IRC. | V2, C2 | Grant, unlock, policy and column-default scenarios pass through IRC. |
| V6 | oh | IRC metrics report. | V2 | A report arrives where today's reports go. |
| V7 | oh | IRC register table. | V3 | A registered table loads and commits. |
| V8 | oh | Multi-table commit. Investigate first (J24). | V3 | Two tables commit together or neither does, under injected failure. |
| V9 | oh | The service links the new Iceberg line. Service modules on JDK 17. Existing clients unchanged. | I3, I6, T1, T4 | Whole existing suite. T1 on legacy paths. T2 full run. |
| V10 | oh | IRC endpoint list advertised. View registration. Spec revision of the new line. | V9, V4 | A current `RESTCatalog` negotiates against it. |
| V11 | oh | IRC scan planning. | V9, V2 | Planned scans return the same files as client-side planning. |
| V12 | oh | IRC credentials and signing. Investigate first (J24). | V9, V2 | Defined by the investigation. |
| V13 | oh | Apache's REST compatibility kit passes against the service. | V9, V3, V4 | Kit green. Every exclusion listed with its reason. |

## Native path and platforms

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| N1 | oh | The same config value selects native REST over HTTP on Spark 3.5. | C2, V3, V4, V5 | T1 identical on all three paths. Differences priced. |
| N2 | oh | A Spark 3.5 runtime built on the new Iceberg line. | I4, I6, N1 | T1 and T2 pass on it. |
| N3 | oh | A Spark 4.2 runtime, IRC only, with capabilities and SQL extensions. | I5, I6, V3, V4, V5 | T1 ported to Spark 4.2 passes. |

## v3 functions

Each exists on the native path only (J3).

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| X1 | oh | Tables can be created at v3 or upgraded from v2, gated per table. The service returns v3 metadata exactly as committed. | V9, V3 | Round-trip test. v2 tables untouched. |
| X2 | oh | Deletion vectors written and read through Spark SQL. | X1, N2 or N3 | Delete, update and merge scenarios on a v3 table. |
| X3 | oh | Row lineage maintained across writes and compaction. | X1, N2 or N3 | Row ids stable across a rewrite. |
| X4 | oh | Native default values on v3 tables. The v2 read bridge is untouched. | X1, N2 or N3 | Add a defaulted column, read old files. |
| X5 | oh | New v3 types pass schema validation and column policies. | X1 | Create and evolve with each type. |
| X6 | oh | Maintenance jobs run on the new line and are correct for X2 to X5. | V9, I4, X2 | Each job on a v3 table loses no live data. |
| X7 | oh | Replication of v3 tables. Investigate first (J24). | X1 | Defined by the investigation. |

## Who does the work

Claude orchestrates and reviews every item before it is called done. Delegation by difficulty:

- Smallest capable agent: P2, I2, T2, and all inventories and verification runs.
- Mid-tier agent: T1, T3, T4, I3 to I5, V2, V4, V6, V7, V10, X5.
- Most capable agent, or Claude directly: C1, C2, V1, V3, V5, V8, V9, V11, V12, N1 to N3, X1 to X4, X6, X7.

## Added since the list was written

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| T5 | oh | The house table test stand-in checks versions and deletes like the real store. **Done 2026-11-22** (pull request 83). | T4 | Conflict and soft-delete tests pass at the HTTP boundary. |
| T6 | oh | A test server that stores grants, so grant behavior is observable through Spark SQL. | T1 | Smoke scenario 17 asserts a grant is listed and then gone. |
| I7 | ib | The Iceberg fork's CI survives dependency download limits. | none | One full CI run completes on the 1.12 branch. |
| I8 | ib | All reapplied patches hold together on one branch, with the full Spark 3.5 suites run. | I3, I4 | **Done 2026-11-22** (branch `irc/reapply-all` tip f180ae589, draft pull request 8 in mkuchenbecker/iceberg). The 8 test failures and 4 checkstyle failures are fixed on their source branches and merged forward. Verified by the touched classes plus compile and style on the combined branch; full suites not re-run after the fixes. |
| X0 | ib, oh | Schema changes work on v2 tables that carry column defaults on the 1.12 line. | I3, a decision (see `10-v2-defaults.md`) | The second ALTER TABLE in the column-default Spark test passes on the new line. |
| I9 | ib | Delete-file replication is safe on non-HDFS storage. | a decision | A merge-on-read delete succeeds on a non-Hadoop file IO. |

The scouting run confirmed X0 from the OpenHouse side: 8 OpenHouse tests fail on 1.12 because non-null column defaults are rejected on v2 tables.

## State

Done: P1, P2, I1, I2, I3, I4, I8, T1, T2, T2b, T3, T4, T5, C1. T2b is draft pull request 86 in
mkuchenbecker/openhouse, into `irc/c1-rest-shaped-seam`, on branch `irc/t2b-harness-via-seam`. The harness
path is selected by `HARNESS_CATALOG_PATH=legacy|rest-shim`. Stand-in stale-read defect fixed on
`irc/t4b-faithful-standin` (commit 2969044d, pull request 83); lost RTAS 0 of 200 races afterwards. In
progress, delegated: I5, Spark patches applied to the Spark 4.2 modules, branch `irc/spark42-patches`; I6a,
I6b and I6c on branch `irc/i6-iceberg-1.12-optin`. I6 scouting is done, local only and nothing pushed (see
`12-openhouse-on-1.12-scout.md`). Waiting on a decision from Mike: X0, I9, I6e, RTAS after a dropped
partition source column behaves differently on 1.12 (one test), and review of the seam design before C2 and
the service items start. Not started: T6, I7, I6d, I6f and all service items. Draft pull requests are open on both forks and
none is merged.
