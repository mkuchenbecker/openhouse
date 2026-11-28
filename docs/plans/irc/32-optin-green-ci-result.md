# Opt-in build green under CI (C5, W12, I10): result

> Correction, 2026-11-23: %s In CI the opt-in job failed on five post-merge runs in a row (6015e111 to bfdcf266) and
> first passed on run 45120792269 at 3222cf78. See `43-optin-ci-red-result.md`. The text below is unchanged.

Bottom line: on the final merged head the opt-in Iceberg 1.12 build (`-PicebergFork`) passes every listed suite
and the Spark 4.2 module with 0 failures. The default build gives the base's counts plus the new tests, and its
resolved classpath for all 59 projects is identical to `irc-integration`. A CI job for the opt-in build now exists
but has not yet run on GitHub. Written by a delegated agent from branch `irc/c5-optin-ci` (pull request 97, merged
as e353a36d, head a0918147). "Ran" means run. "Read" means taken from code or earlier records only. Continues
`25-optin-green-result.md`.

## Counts (ran)

Opt-in build, replayed from a clean second worktree in the CI job's order. Each cell is run / failed.

| Task | Base 8b9bfe83 under the switch | Final head |
| --- | --- | --- |
| `services:tables` test | 1330 / 4 | 1390 / 0 |
| `internalcatalog` test | 319 / 0 | 319 / 0 |
| `htscatalog` test | 20 / 0 | 20 / 0 |
| 1.5 Java itest | 133 / 0 | 136 / 0 |
| Spark 3.5 `catalogTest` | 225 / 4, 1 skipped | 238 / 0, 1 skipped |
| Spark 3.5 `statementTest` | 137 / 0 | 137 / 0 |
| Spark 3.5 `test` | 124 / 0 | 124 / 0 |
| Spark 4.2 itest | 31 / 0 | 31 / 0 |

Default build against `irc-integration` at 14f23870: `services:tables` 1373 / 0 before and after; 1.5 Java itest
133 to 136; Spark 3.5 `catalogTest` 238 / 0 (1 skipped) before and after; `TablesClientTest` 22 to 24 in the apps.
The full apps suite on the base 8b9bfe83 was 296 / 0 (5 skipped), ran on the base only. `spotlessCheck` and
`actionlint` 1.7.12 pass. The increases beyond the three adapter tests come from pull requests 94 and 96.

## The 1.12 differences that were red, and what was done

The base was red beyond the two failures `25-optin-green-result.md` left: `StoredMetadataGoldenTest` four times and
`IcebergRestCatalogReadTest` twice. After pull request 94 merged in, `IcebergRestCatalogWriteTest` once and
`SparkIrcWriteTest` twice were also red. The last two pinned a 406 that pull request 94 made obsolete, so they were
red by design. All nine are fixed, and each test now states its expectation per Iceberg line (D14).

The report names the tests and the fix, not each line's expected value. Those values are in the tests themselves
and were not copied here (not verified in this note).

## How the adapter advertises endpoints (C5)

- The in-process REST-shaped adapter's config response lists the 21 routes it dispatches, all but config, derived
  from the `Route` enum. Path variables use the spec's `{table}` and `{view}`.
- The list is attached by reflection over `Endpoint.create(String, String)` and
  `ConfigResponse.Builder.withEndpoints(List)`, only when `IcebergLine.current()` is the new line. A missing method
  throws. On the legacy line the builder call is unchanged.
- The endpoint class name is built from `RESTClient`'s package, because the 1.5 runtime shadow jar relocated the
  string literal. The Java itest caught this.
- The exact list: GET and POST `/v1/{prefix}/namespaces`; HEAD, GET and DELETE `.../namespaces/{namespace}`; POST
  `.../namespaces/{namespace}/properties`; GET and POST `.../{namespace}/tables`; HEAD, GET, POST and DELETE
  `.../tables/{table}`; POST `/v1/{prefix}/tables/rename`; POST `.../{namespace}/register`; POST
  `.../tables/{table}/metrics`; GET and POST `.../{namespace}/views`; GET, POST and DELETE `.../views/{view}`; POST
  `/v1/{prefix}/views/rename`.
- Lesson for the service's own config route, which this item did not change: it advertises no list, so 1.12 clients
  assume the default endpoints. They never use HEAD existence checks and fall back to loads. They assume no views,
  and they assume write routes and `transactions/commit` that may not be served. It needs an explicit list of
  exactly what it serves (item V10a).

## The apps' capability declaration (W12)

- `TablesClientFactory` declares `column-defaults` if and only if `IcebergLine.usesReadBridge()`: yes on the legacy
  line, no on the new line. `IcebergLine` is loaded by name, because it is not on the apps compile classpath and
  adding it would change the classpath dump.
- The gap: the scheduler uber jar contains neither `IcebergLine` nor Iceberg core. A probe against the built jar
  logged a warning and declared the capability (ran). A scheduler image on the 1.12 line therefore still declares it
  (item W13).
- What a refusal does (422, read in code): `TablesClient.getTable` is not retried, since retry is only on 500.
  `executeWithRetry` logs an error and returns null, and the table is dropped from scheduling with a second error
  log. The run does not fail, so it is silent. Spark maintenance jobs load through the catalog and fail loudly.

## Spark 4.2 writes

The Spark 4.2 itest ran 31 tests, 0 failed, on the final head under the opt-in build. The two `SparkIrcWriteTest`
cases that had pinned the old 406 were fixed to match pull request 94's write routes and now pass. The report does
not list which write statements Spark 4.2 runs beyond creating tables and inserting rows. The full write item is N3b.

## The CI job (I10)

- Steps, in the order replayed locally: publish the fork's artifacts to the build-local Maven directory, assemble
  the test classes, then run the suites in the table above.
- Measured locally with two workers (ran). First replay: publish 36 s with fork tasks from the local build cache; a
  second publish 0 s; assemble 88 s; suites 980 s, stopped at a `catalogTest` failure that was then fixed. Later
  replays: compile 10 to 12 s and suites 727 s and 577 s, with only changed tasks re-running. The full base suite
  took 17.5 minutes plus 1.3 minutes.
- Expected on GitHub (estimate, not measured): warm 35 to 45 minutes, cold with the fork built from source 60 to 75.
- Only a real run confirms: the cold fork build time, cache save and restore and their keys, memory on the hosted
  runner at default parallelism, the cache write on `irc-integration`, and `workflow_dispatch`.

## Not run

Docker-backed and integration tests; the housetables and jobs services; the apps tests under the switch beyond
`TablesClientTest`; `libs:datalayout` tests; any GitHub Actions run; default `services:tables` after the pull
request 96 merge, which was not this change.
