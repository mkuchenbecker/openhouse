# C5 / W12 / I10: opt-in build green and under CI — record

Bottom line: on the final merged head the opt-in build (`-PicebergFork`) passes every listed suite plus
the Spark 4.2 module, 0 failures [ran]. The default build gives the base's counts plus the new tests,
and the resolved-classpath dump of all 59 projects is identical to `irc-integration` [ran].
Draft pull request: https://github.com/mkuchenbecker/openhouse/pull/97, head
a09181475aa73e125095b6426714269c90a9385b, branch `irc/c5-optin-ci`, base `irc-integration`.

## Counts [ran]

Opt-in, final head a0918147, replayed from a clean second worktree in the CI job's order:

| Task | Base 8b9bfe83 under the switch | Final head |
| --- | --- | --- |
| services:tables test | 1330 / 4 | 1390 / 0 |
| internalcatalog test | 319 / 0 | 319 / 0 |
| htscatalog test | 20 / 0 | 20 / 0 |
| 1.5 Java itest test | 133 / 0 | 136 / 0 |
| Spark 3.5 itest catalogTest | 225 / 4, 1 skipped | 238 / 0, 1 skipped |
| Spark 3.5 itest statementTest | 137 / 0 | 137 / 0 |
| Spark 3.5 itest test | 124 / 0 | 124 / 0 |
| Spark 4.2 itest test | 31 / 0 | 31 / 0 |

Increases other than my 3 adapter tests come from pull requests 94 and 96 (merged in). Base red beyond
the two known: StoredMetadataGoldenTest x4, IcebergRestCatalogReadTest x2; after merging PR 94 also
IcebergRestCatalogWriteTest x1 and SparkIrcWriteTest x2 (by design). All fixed.

Default, against irc-integration 14f23870: tables 1373/0 -> 1373/0; 1.5 Java itest 133 -> 136;
Spark 3.5 catalogTest 238/0 (1 skipped) -> same; TablesClientTest 22 -> 24 in apps (1.2) and apps-1.5.
apps (1.2) full suite on 8b9bfe83: 296/0 (5 skipped) [ran, base only]. Classpath + source-set dump:
identical to 14f23870 and to 0010df15 (59 projects, 36,446 lines). spotlessCheck passes; actionlint
1.7.12 clean.

## C5
Config response on V3 lists the 21 routes dispatched (all but config), derived from the Route enum;
path variables renamed to the spec's `{table}`/`{view}`. Reflection over `Endpoint.create(String,String)`
and `ConfigResponse.Builder.withEndpoints(List)`, only when `IcebergLine.current()==V3`; a missing method
throws. Endpoint class name built from `RESTClient`'s package because the 1.5 runtime shadow jar
relocated the string literal (caught by the itest). Legacy: unchanged builder call.
List: GET/POST /v1/{prefix}/namespaces; HEAD/GET/DELETE /v1/{prefix}/namespaces/{namespace};
POST .../{namespace}/properties; GET/POST .../{namespace}/tables; HEAD/GET/POST/DELETE
.../tables/{table}; POST /v1/{prefix}/tables/rename; POST .../{namespace}/register; POST
.../tables/{table}/metrics; GET/POST .../{namespace}/views; GET/POST/DELETE .../views/{view};
POST /v1/{prefix}/views/rename.
Lesson for the service config route (not changed): it advertises none, so 1.12 clients assume
DEFAULT_ENDPOINTS: no HEAD exists routes (falls back to loads), no views, and it assumes write routes
and transactions/commit that may not be served. It needs an explicit list of exactly what it serves.

## W12
TablesClientFactory declares column-defaults iff IcebergLine.usesReadBridge(); IcebergLine loaded by
name (not on apps compile classpath; adding it would change the dump). Scheduler uber jar contains
neither IcebergLine nor Iceberg core (verified; probe against built jar: WARN + declares=true).
When refused (422): TablesClient.getTable is not retried (retry only on 500), executeWithRetry logs
ERROR and returns null, the table is dropped from scheduling with another ERROR log: silent for the
run (no failure). Spark maintenance jobs load through the catalog and fail loudly with the 422.

## I10 timings [ran locally, --max-workers=2]
Replay 1: publish 36 s (fork tasks from local Gradle build cache), publish again 0 s, assemble
testClasses 88 s, suites 980 s (stopped at catalogTest failure; fixed). Replay 2/3: compile 10-12 s,
suites 727 s / 577 s (only changed tasks re-ran). Base full opt-in suite run: 17.5 min + 1.3 min.
Expected on GitHub: warm ~35-45 min; cold (fork built from source, empty caches) ~60-75 min. Only a
real run confirms: cold fork build time, cache save/restore and keys, memory on the hosted runner with
default parallelism, setup-gradle cache-write on irc-integration, workflow_dispatch.

## Not run
Docker/integration tests, housetables/jobs services, apps tests under the switch beyond
TablesClientTest, libs:datalayout tests, any GitHub Actions run, default services:tables after the
PR 96 merge (not my change).
