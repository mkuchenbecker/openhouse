# Spark-4.0 / Iceberg-1.11 modernization — CHANGE HISTORY (per repo, sequential)

A reviewer's walkthrough of everything this modernization changed, repo by repo, in commit order.
It exists so the diffs can be read as a narrative rather than 54 unlabeled commits.

## Repos in scope
| Repo | Role | Named branch (this work) | Pending PR |
|------|------|--------------------------|------------|
| **`mkuchenbecker/openhouse`** | OpenHouse services + Spark integrations (the product) | `1.11` | **#17** (draft, `1.11 → main`) |
| **`mkuchenbecker/iceberg`** | LinkedIn fork of Apache Iceberg (supplies `*:1.11.0-openhouse`) | `1.11.x` (release); `openhouse-1.11-port` (port dev) | none — consumed as **source**, built to `mavenLocal` by CI |

How they connect: `Branch 1.11 CI` checks out `mkuchenbecker/iceberg@1.11.x`, builds + publishes the
`org.apache.iceberg:*:1.11.0-openhouse` artifacts into the runner's `~/.m2`, then the OpenHouse `1.11`
build resolves them. The fork is not a PR — it is a source dependency.

---

# Repo 1 — `mkuchenbecker/openhouse` (branch `1.11`, PR #17 draft)

**Scope of the branch vs `main`: 132 files changed, +17,307 / −109.** 54 commits, grouped below into
the phases they were done in (oldest → newest).

## Phase 1 — Server core → Iceberg 1.11 + the REST-first cutover
The architectural heart: stop shipping a custom OpenHouse Spark runtime; instead point a **stock**
`RESTCatalog` at a new OpenHouse `/iceberg/v1/*` endpoint.
- `5bd5da1` point the server core at Apache Iceberg `1.11.0-openhouse` (the fork).
- `8f0c8b9` add the Iceberg REST Catalog endpoint (`IcebergRestCatalogController`) + CREATE TABLE.
- `0ee03ed` support CTAS + RTAS over the REST endpoint.
- `669e146` recover server-side update-validation parity on the REST commit path.

## Phase 2 — Build plumbing, delta-harness, design-of-record
- `cd64d60` docker HDFS validation recipe (Spark 3.5 + Hadoop 3.2, fork jar baked).
- `d369b63` design-of-record docs onto the branch.
- `fcccf0b` + `4a1d4ce` + `974ce91` delta-harness: Spark-4.0/Scala-2.13 classpath-only itest module,
  wire the modular harness to stock `RESTCatalog`, add the format-version=3 deletion-vector probe.
- `7336e2c` + `78c40c9` fix lock-endpoint 500 + migrate latent commons-lang 2.x → lang3 in validator.
- `acb02a8` + `0be35d1` + `82767cf` CI: provision the Iceberg 1.11 fork on `1.11`-branch pushes only;
  scope the JVM-17 resolution attribute to classpath configs (so Java-8 server jars still resolve).
- `b3880ca` + `5f2ee2a` consolidated status doc; keep the spark-3.5/iceberg-1.5 lane intact.

## Phase 3 — Green the server on Iceberg 1.11
- `f1f6eee` `:services:tables:test`; `d02688c` `:iceberg:openhouse:htscatalog:test`;
  `c2ebcab` spark-3.5-itest 1-arg `TableMetadata.Builder.addSchema` (1.11 API change);
  `558fe8a` spotless.

## Phase 4 — Spark-4.0 REST e2e harness + catalogtest port
- `13a2300` REST-first e2e JUnit harness (`OpenHouseRestSparkITest`) + first port.
- `7c80327` gate the legacy Spark-3.5/Iceberg-1.5 in-JVM e2e off the 1.11 branch (two-Iceberg-version
  collision is unavoidable in one JVM).
- `7188e2d` `2d56c5e` `26e8f9e` `20e45a1` port Partition/CTASNonNull/WapId/Branch(+Java)/RTAS/sort +
  Java-API cases to the Spark-4.0 REST lane.
- `e6c1598` + `2016e20` reframe legacy-gated tests as a fix checklist (not accepted residuals);
  finish the port (InvalidMetadata, MultiSchemaEvolution).
- `8fc1e7a` `95e959a` `d1e68da` spotless + green `testMultiSchemaEvolutionColumnOrderingOnCreate`.

## Phase 5 — Backlog triage + server-side fixes
- `278658f` `867a7c3` record CI green + the InvalidMetadata ~19-min commit-retry item.
- `b5db595` map corrupt-metadata to a **non-retryable 400** (was 500 → ~19-min retry) — ~⅓ CI-time cut.
- `e55bdb5` + `956820c` reject rename onto an existing table; the working guard is at the CATALOG
  level using `findHouseTable` (not `tableExists`), because Spark leaks the catalog name into the
  rename destination namespace.
- `7eb4afc` correct the harness comment; `da20ad4` translate the `updated.openhouse.policy`
  table-property into OpenHouse `Policies` on the REST lane (server side of the policy story).

## Phase 6 — OpenHouse SQL POLICY DDL extension → Spark-4.0
- `1f2d80a` port `OpenhouseSparkSessionExtensions` to Spark-4.0 / Scala-2.13 (SET POLICY works
  end-to-end on the REST lane).
- `d91cfdf` UNSET POLICY (REPLICATION) clear/tombstone semantics (server honors an empty sub-policy).
- `f42268d` verify column policy-tag DDL; `923d39b` restore the `openhouse.tableUri` assertion
  (it was surfaced all along; only the value rendering differed).
- `9cceeab` + `77eb4e9` consolidate residuals; mark the InvalidMetadata CI-perf item fixed.

## Phase 7 — The remaining-work ladder (worked sequentially, this session)
- `4a35709` add the `REMAINING-WORK.md` master checklist.
- `a87befe` **GRANT/REVOKE/SHOW GRANTS** execute on the REST lane via direct HTTP to the existing
  `/aclPolicies` endpoint (stock `RESTCatalog` can't use the legacy `SupportsGrantRevoke` hook).
- `69d83d6` **Rung 3 (the goal)** — prove Iceberg v3 DSv2 deletion vectors: an isolated
  `deletionVectorTest` fork (`-Dcluster.iceberg.format-version=3`) shows a MOR `DELETE` writing a
  puffin deletion vector; the default-v2 suite is untouched.
- `3f685e7` **Rung 7** — verify the server runtime is Java 17 while the metadata-writer stays Java-8
  bytecode (major 52), proven with `javap`.
- `2884455` **Rung 9** — move the server HDFS client `hadoop-client 2.10.0 → 3.3.6` (Java-17-capable,
  `openFile`, RBF wire-compat), migrating the transitive fallout to modern libs.
- `24dd99b` load the OpenHouse SQL extension in the delta-harness SparkSession (recovers ~18 `ddl.*`
  cases that previously failed to parse).
- `08b06e8` migrate the remaining commons-lang 2.x uses (apps-1.5 + datalayout) to commons-lang3 —
  the Hadoop-3.3.6 whole-repo build fallout; `./gradlew testClasses` green.
- `d5fd833` `c73eeed` tracker + **Rung 8** delta-harness full-matrix final validation (2542/2574).

**Per-item audit write-ups** (same directory): `grant-revoke-rest-lane.md`,
`rung3-v3-deletion-vectors.md`, `rung7-java17-runtime.md`, `rung8-final-validation.md`,
`rung9-hdfs-java17-v3-readcliff.md`; residual triage in `spark4-e2e-tests/10-RESIDUALS.md`; open items
in `KNOWN-GAPS.md`.

---

# Repo 2 — `mkuchenbecker/iceberg` (fork; branch `1.11.x`, no PR)

**The fork supplies `org.apache.iceberg:*:1.11.0-openhouse`.** It is the LinkedIn Iceberg fork's
LinkedIn-specific patches replayed onto Apache Iceberg 1.11.0. Branch layout:

| Branch | Purpose |
|--------|---------|
| `1.11.x` | release line the CI consumes (`1.11.0-openhouse`) |
| `1.10.x` | the rung-1 line (`1.10.0-openhouse`) |
| `openhouse-1.5.2` / `openhouse-1.2.0` | the pre-existing legacy fork lines |
| `openhouse-1.11-port` | port-development branch for the 1.11 replay |

**Scope of the 1.11 port vs Apache Iceberg 1.11.0: 12 files changed, +233 / −13** — two commits:
- `06f6e8f` **replay the LinkedIn core patches onto Apache Iceberg 1.11.0** (10 files, +190/−13):
  `build.gradle` (version stamp), `core` `TableProperties` (delete-file replication keys),
  `actions/RewriteFileGroup`, `hadoop/HadoopFileIO` + `HadoopOutputFile`, `io/OutputFileFactory`,
  `util/TableScanUtil` — the LinkedIn additions OpenHouse depends on (notably the delete-file
  replication-factor plumbing, #219/#229).
- `efa02dac` **F-REPL fix** (3 files, +43): honor the delete-file replication factor for ORC and
  Parquet writes — `hadoop/HadoopOutputFile`, `orc/ORC`, `parquet/ParquetIO`.

No open PR: the fork is a source dependency built to `mavenLocal` by the OpenHouse CI, not merged
via GitHub. (There is also a `claude/iceberg-spark-upgrade-4h7pwb` working branch carrying a
column-default API commit, `d1603c8` — the fork side of the `fork.colDefault` feature; it is not on
the `1.11.x` release line the spike consumes.)

---

# Reading order for review
1. This file (the map).
2. `KNOWN-GAPS.md` (what's left).
3. `REMAINING-WORK.md` (the rung ladder, all ticked).
4. The per-rung write-ups + `spark4-e2e-tests/10-RESIDUALS.md` for details.
5. OpenHouse PR **#17** diff; the fork `openhouse-1.11-port` two-commit diff.
