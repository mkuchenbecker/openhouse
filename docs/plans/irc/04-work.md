# Work and dependencies

Bottom line: 37 items in two repos. Each names what it needs and how it is proven. There is no order
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
| T1 | oh | A small Spark SQL smoke suite runs on Spark 3.5 against the real embedded service, with the catalog path as a parameter. | P1 | Green in CI. A deliberately broken rule turns it red. |
| T2 | oh | The delta-harness is in the repo and runs on demand. | P1 | One full recorded run. |
| T3 | oh | Black-box tests pin the catalog plugin's behavior as it is. | P1 | Green on unchanged code. |
| T4 | oh | Black-box tests pin the service commit path over the native API, filling gaps only. | P1 | Green on unchanged code. |

## Iceberg line

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| I1 | ib | A clean branch at Apache 1.12.0 builds untouched. **Proven 2026-11-22** for the modules listed in F4a; full Spark suites not run. | none | Apache's own tests pass for api, core, orc, parquet, Spark 3.5, Spark 4.2. |
| I2 | ib | A ledger marks each of LinkedIn's 29 commits as already upstream, reapply, or build plumbing. **Done 2026-11-22**: 13 reapply, 11 upstream, 5 plumbing (`05-iceberg-patch-ledger.md`). | P2 | 7 rows re-checked by Claude. |
| I3 | ib | Core, API and ORC patches reapplied, one commit each. | I1, I2 | Each patch's test fails before and passes after. Apache tests still pass. |
| I4 | ib | Spark 3.5 patches reapplied, one commit each. | I3 | Same. |
| I5 | ib | The same Spark patches applied to the Spark 4.2 module where they apply. | I4 | Same. |
| I6 | ib, oh | The openhouse fork build resolves the new line's artifacts from the mkuchenbecker Iceberg fork (S35, J25). | I1 | A throwaway module compiles against them in CI. |

## Client seam

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| C1 | oh | A config value runs the plugin as `RESTCatalog` over the legacy client. Default unchanged. | T1, T3 | T1 and T3 pass on both paths. Differences listed. |
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

## Ready now

P1, P2, I1 and I2 are done. In progress, delegated: T1 (smoke suite) and the first four patches of I3 and
I4. T2, T3 and T4 are ready and not started.
