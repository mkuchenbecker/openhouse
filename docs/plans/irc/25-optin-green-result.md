# Opt-in build made green: result

> Correction, 2026-11-23: %s In CI the opt-in job failed on five post-merge runs in a row (6015e111 to bfdcf266) and
> first passed on run 45120792269 at 3222cf78. See `43-optin-ci-red-result.md`. The text below is unchanged.

Bottom line: the default build gives the same results as before, plus the new tests. The opt-in Iceberg 1.12 build
passes every listed suite except two seam view cases. Written by a delegated agent from branch `irc/i6-optin-green`
(openhouse pull request 91, a draft into `irc-integration`, on top of pull request 87). Counts are tests and
failures. Everything below was run unless marked read.

## Test table: base, default after, opt-in after

The base is cd47695f, the head of pull request 87. Before this work the base with the new pin did not compile its
Iceberg 1.5 Java runtime under the switch, so no 1.5-line Java or Spark itest could run on it.

| Task | Base (default) | Default after | Opt-in after |
| --- | --- | --- | --- |
| tables | 1268 / 0 | 1268 / 0 | 1268 / 0 |
| internalcatalog | 319 / 0 | 319 / 0 | 319 / 0 |
| htscatalog | 20 / 0 | 20 / 0 | 20 / 0 |
| spark-3.5 catalogTest | 208 / 0, 1 skipped | 213 / 0, 1 skipped | 213 / 2, 1 skipped |
| spark-3.5 statementTest | 137 / 0 | 137 / 0 | 137 / 0 |
| spark-3.5 test | 124 / 0 | 124 / 0 | 124 / 0 |
| java-iceberg-1.5 itest | 129 / 0 | 133 / 0 | 133 / 0 |

The count increases are only the new tests (5 in `ColumnDefaultsCapabilityByIcebergLineTest`, 3 in
`IcebergLineTest`, 1 overlay test). The classpath dump (59 projects) and source-set dump of the default build are
identical to the base, and `assemble testClasses spotlessCheck` passes.

## The two remaining failures

Both are in `RestShimPathSparkSqlSmokeTest`: `s18_views_createSelectDrop` and `s28_views_disabledByDefault`.

- Cause: 1.12's `RESTSessionCatalog` checks the endpoints the server advertised. The adapter's config response
  advertises none, so the client gets the default set, which has no views. The error is
  `UnsupportedOperationException: Server does not support endpoint: POST /v1/{prefix}/namespaces/{namespace}/views`.
  For s28 today's path gives `NoSuchNamespaceException [SCHEMA_NOT_FOUND]` instead.
- Not fixed. The adapter's config response would have to list its endpoints, including the view routes.
  `ConfigResponse.Builder.withEndpoints` and `Endpoint` exist on 1.12 but not on 1.5.2, so this needs
  version-specific code or reflection. This became work item C5.

Seam results on 1.12: the rest-shim contract suite passes 20 of 20 and the smoke suite 29 of 31.

## How the Iceberg line is detected, and why

`com.linkedin.openhouse.javaclient.IcebergLine` (enum `LEGACY` or `V3`) is in the shared Java runtime sources. It
asks the Iceberg on the classpath, once, to build format v3 table metadata in memory with a location that has no
file system. LinkedIn's 1.2.0.25 and 1.5.2.24 throw `IllegalArgumentException: Unsupported format version: v3`, so the
line is `LEGACY`. 1.12 builds the metadata, so the line is `V3`.

- It is the property the decision rests on (native defaults need v3, and 1.5.2 cannot build v3), uses only public
  API present on all three lines, and cannot be set by accident. It detects the Iceberg that runs, not the one the
  client was compiled against.
- `IcebergLineTest` checks it against an independent oracle, `IcebergBuild.version()`. It passes on the 1.2 itest,
  the default 1.5 itest and the fork 1.5 itest. All per-line tests use this class as their single helper.

## What changed (15 commits on top of cd47695f; each body says why)

1. Pin bump to 28742df3e538, published locally as `1.12.0-ohfork.28742df3e538` in 1m43s.
2. `IcebergLineTest`, red by itself, then `IcebergLine`.
3. Seam compile fixes for 1.12 in `OpenHouseRestAdapter`: `RESTUtil.NAMESPACE_SPLITTER` is gone,
   `RESTUtil.decodeNamespace(String)` now takes a separator, and `CatalogHandlers.loadTable` now takes a
   `SnapshotMode`. The adapter splits on U+001F and `%1F` itself and loads as 1.5.2 did. The adapter test builds its
   `AddSchema` update through `MetadataUpdateParser`.
4. I6g (D15). Under the switch `settings.gradle` leaves out `:apps:spark`, the 1.2 Java projects, the Spark 3.1
   projects and the 1.2 fixtures, and the 1.5 and Spark 3.5 projects name the reused directories directly.
   `libs:datalayout` is built on Spark 3.5 and the fork; its `DataCompactionConfig` now writes four bin-pack defaults
   as literals (0.75, 1.8, 5, `Integer.MAX_VALUE`) because 1.12 removed `BinPackStrategy`. That source change was
   outside the brief but required. `dockerPrereqs` does not work under the switch. Under the switch 51 projects
   resolve with 0 failures, and the only `com.linkedin.iceberg` versions are the fork's plus the unchanged
   `iceberg-aws:1.2.0.6`.
5. W3, tests first, then code. `OpenHouseTableOperations.loadMetadata` still decodes the read-bridge config, so a
   malformed entry still fails, but on V3 it does not overlay. The Spark 3.5 `OpenHouseCatalog.capabilities()`
   declares `column-defaults` only on `LEGACY`. Before the code the opt-in build had 11 W3 failures; after it all
   pass on both builds.
6. A fix of the agent's own: the new test class left a SparkSession open, which broke
   `DeleteFileReplicationTestSpark`. The class now closes its sessions.
7. Adapter: header-returning `get` and `post` overloads, without `@Override` so 1.5.2 compiles. Before this, 43 of
   the 51 rest-shim tests failed.
8. W4. `tableWithSchema` builds format v3 on V3 and v2 on `LEGACY`. The assertions are unchanged.
9. I6h (D14). RTAS on V3 now asserts success with only the `data` column, unpartitioned, 3 rows. The s19 and s24
   namespace messages are per line. The agent extended D14b to s24 (`DROP NAMESPACE`): same cause, same exception
   class. Claude accepted this extension as part of D14. It was not put to Mike.
10. Docs: `docs/development/iceberg-fork.md`.

## Spark SQL differences between the builds (observed)
1. Tables with `required-capability.column-defaults=true`: SELECT, INSERT, ALTER TABLE ADD COLUMN and CREATE TABLE
   with that property work on 1.5.2 and get 422 `CLIENT_CAPABILITY_MISSING` on 1.12 (D5, D11a).
2. REPLACE TABLE AS SELECT that drops a former partition source column fails on 1.5.2 and succeeds on 1.12 (D14a).
3. CREATE NAMESPACE and DROP NAMESPACE throw `UnsupportedOperationException` on both. The message is "Describing
   database is not supported" on 1.5.2 and "Checking if database exists is not supported" on 1.12 (D14b).
4. Views on the REST-shaped path: CREATE VIEW fails on the client on 1.12 with "Server does not support endpoint",
   whether views are enabled or not. On 1.5.2 the request reaches the catalog.
5. Observable by the service only: on 1.12 the Spark catalogs send no `X-OpenHouse-Client-Capabilities` header.

No difference was found in new-table property keys, DESCRIBE output, plain CREATE, INSERT and ALTER, the create
request body, or any other smoke and contract scenario. Not every Spark statement outside the suites was diffed.

## Other findings

- The jobs client (`apps/spark` `TablesClientFactory`) declares `column-defaults` unconditionally, on both lines, so
  on 1.12 the scheduler could schedule maintenance for bridged tables that the 1.12 catalog then refuses (read). This
  became work item W12.
- `DeleteFileReplicationTestSpark` needs no open SparkSession. The pre-push hook runs `spotlessCheck`, so a push
  during a running suite failed. Two Gradle daemons were killed by the shared memory limit and each task was re-run
  alone. Two orphaned test-worker JVMs holding about 2 GB could not be stopped, because the permission classifier
  refused.

## Not run

`libs:datalayout` and `apps:spark-3.5` tests under the switch (compile and classpath resolution only); housetables,
jobs and Docker tests on either build; the 1.2 and Spark 3.1 tests, which are excluded under the switch; Spark 4.2;
any CI action or merge.
