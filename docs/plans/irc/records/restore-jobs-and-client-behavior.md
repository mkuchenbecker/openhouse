# Restore jobs and client behavior (FF-10, FF-14, FF-15, FF-16)

Bottom line: code changes written for all four items; tests running. Worktree `/home/user/oh-restore-jobs`, branch
`irc/restore-jobs-and-client-behavior`, base `origin/irc-integration` at `81b4cd4d`.

## Findings confirmed or corrected
- FF-10: the reviewer's premise is wrong in effect. `OpenhouseDataSourceV2Strategy.CatalogAndIdentifierExtractor`
  (unchanged since main, checked with `git diff bb8929e1`) plans the OpenHouse statements only for
  `SparkCatalog`/`SparkSessionCatalog` (and subclasses), the same classes `OpenHouseCapabilities.icebergCatalog`
  accepts. A wrapper catalog never reached the exec on main (planner: "No plan for ..."), and never reaches it now:
  `NOT_ICEBERG` is unreachable from the six policy execs. Fix kept anyway (resolver offers policies on NOT_ICEBERG,
  so the server-blaming message appears only on ICEBERG_REST); test pins main's planner failure on a wrapper.
- FF-14 scheduler: apps uber jar (default line, before) has no `IcebergLine` and only 13 `org/apache/iceberg/`
  entries (azure module + `io/DelegateFileIO`): detection always failed -> WARN + `column-defaults`. Same on ref line.
  Restored main's `TablesClientFactory` and main's `TablesClientTest` byte for byte.
- FF-14 sort stats: restored main's procedure call; kept `rewriteSorted` as a helper with main's exact SQL; format
  version read from the plan of the app's own `data_files` query (`BaseMetadataTable.table()`), no Java catalog.
- FF-15: before, `log4j2.properties` in the apps uber jar on both lines (listings below).
- FF-16: the thin runtime POM (before) lists mapstruct, servlet-api, iceberg-spark-runtime, antlr4-runtime only.
  The publication is `mavenJava` (the maven-publish plugin is applied before shadow, so the `shadow` branch never
  fires) — noted, not changed.

## Listings (scratchpad rj/)
- `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/rj/before-{default,ref}-*.list|.crc`,
  `before-{default,ref}-runtime-pom.xml`.

## Steps
- Before builds on both lines done.
- FF-10 tests before the fix (ref line): `aNonIcebergCatalogDoesNotRefusePolicies` FAILED (as intended). The wrapper
  SQL test could not run in the runtime module (jackson 2.13 forced there breaks Spark error classes), moved to the
  3.5 itest `statementtest` and the 4.2 runtime `statementtest`.

## Progress (latest)
- Commits: 8fa45a7e (FF-10), 90fc67ce (FF-14), c0d4f96f (FF-15), 95cdb514 (FF-16); pushed. Base unmoved (81b4cd4d).
- Draft pull request #154. PR body: scratchpad rj/pr-body.md (full verification, tests, listings summary).
- Before/after listings: rj/{before,after}-{default,ref}-*.list|.crc, *-runtime-pom.xml, after-*-capabilities-pom.xml,
  after-*-smoke/ (legacy outcomes: ref 300 same; default 298 same + 2 declared 1.12 differences).
- Waiting on CI for 95cdb514.

## Bottom line (final)
FF-10, FF-14, FF-15 and FF-16 are closed in draft pull request #154 (head 95cdb514faff777a444ad2fe4691e6f6df602eab).
All 16 CI checks finished: 15 success, and 1 skipped by design (the reference build's Docker job). No pollers are left running.
