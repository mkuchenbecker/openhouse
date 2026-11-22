# Delta-harness through the REST-shaped path (rest-shim)

Bottom line: the full delta-harness runs through the in-process REST-shaped catalog path. With the real embedded house-table service, the counts match the legacy path. With the in-memory house-table stub, the REST-shaped path has one extra failure, a replace that is silently lost in 2 of 50 races. Everything here was run on 2026-11-22, except where a line says it was inferred from reading the code.

## Setup

The tree is `irc/t2b-harness-via-seam`. It equals `irc/c1-rest-shaped-seam` (74b3ce60) merged with `irc/t2-delta-harness` (614018f4), plus commit a25f476f, which adds the `HARNESS_CATALOG_PATH` setting to the harness. No production code changed.

The path is chosen with `HARNESS_CATALOG_PATH=legacy` or `HARNESS_CATALOG_PATH=rest-shim`. The rest-shim value changes only `spark.sql.catalog.openhouse.catalog-impl` to `com.linkedin.openhouse.spark.OpenHouseRestCatalog`, the same change `RestShimPathSparkSqlSmokeTest` makes. Every run printed the class it used.

Runs used JDK 17, `HARNESS_PARALLELISM=2`, and `HARNESS_JVM_ARGS=-Xmx3g` with a private `-Djava.io.tmpdir`. The real-service rest-shim run also set `-XX:MaxDirectMemorySize=512m` and `-XX:ReservedCodeCacheSize=160m`.

Draft pull request 86 in mkuchenbecker/openhouse, into `irc/c1-rest-shaped-seam`: https://github.com/mkuchenbecker/openhouse/pull/86

## Counts

In the Source column, "run here" means run on 2026-11-22 for this record. "Baseline" means the figure was copied from `BASELINE-2026-11-22.md` and was not run again.

| Mode | Path | Cases | Passed | Failed | Skipped | Wall | Source |
|---|---|---|---|---|---|---|---|
| Slice `delete parquet` (stub) | legacy | 238 | 237 | 0 | 1 | 291 s (including Gradle build) | run here |
| Slice `delete parquet` (stub) | rest-shim | 238 | 237 | 0 | 1 | 185 s | run here |
| Full, in-memory HTS stub | legacy | 2574 | 2560 | 3 | 11 | 1365 s | baseline |
| Full, in-memory HTS stub | rest-shim | 2574 | 2559 | **4** | 11 | 1422 s | run here |
| Full, real HTS (`HARNESS_REAL_HTS=1`) | legacy | 2792 | 2778 | 3 | 11 | 1683 s | baseline |
| Full, real HTS (`HARNESS_REAL_HTS=1`) | rest-shim | 2792 | 2778 | 3 | 11 | 1477 s | run here |

The non-passing cases on rest-shim are as follows.

- The same 3 failures as legacy, in both modes, with the same message (`OpenHouse: Cannot convert policies string to policies object`): `control.lock.enforcement`, `interact.rtas.onLockedTable`, and `hazard.lock.starvesMaintenance`.
- The same 11 skips, which come from `Plan.knownBugs` and were not run.
- No case that passes on legacy fails on rest-shim.
- One extra failure in stub mode only: `surface.conc.rtasVsAppend @ orc`, described as D1 below. It passed in the rest-shim real-service run and on parquet in both rest-shim runs.

## Differences

### D1. `surface.conc.rtasVsAppend @ orc` (stub mode only)

What the case asserts: on a 3-row table with `replace.enabled=true`, it runs `CREATE OR REPLACE TABLE t AS SELECT * FROM t WHERE foo_col_long <= 2` and `INSERT INTO t VALUES (30, ...)` on two threads. Each statement either succeeds or fails with a typed commit conflict. The table must end with 2 rows (the RTAS applied last) or 3 rows (the INSERT applied on top of the RTAS). The assertion is in `SurfaceScenarios.scala`, lines 340 to 354.

What happened on rest-shim: the table ended with 4 rows, and neither thread threw.

Measured (run here, with a diagnostic probe that repeated the race 25 times per format; the probe was not committed):

| Path and mode | Runs | 2 rows | 3 rows | 4 rows (RTAS lost) | Any thread threw |
|---|---|---|---|---|---|
| legacy, stub | 50 | 50 | 0 | 0 | none |
| rest-shim, stub | 50 | 47 | 1 | **2** (both orc) | none |
| rest-shim, real HTS | 50 | 49 | 1 | 0 | none |

In the 4-row runs, the snapshot history is `append(+3=3), append(+1=4)`. The RTAS snapshot does not exist at all, and the INSERT's snapshot is based on the seed. The RTAS statement returned normally, so this is a silent lost update.

Inferred from reading the code, consistent with the probe:

1. The server's conflict check is a check followed by a separate write. `OpenHouseInternalRepositoryImpl.versionCheck` (services/tables, lines 588 to 611) compares the request's base with the head. The only atomic guard after that is the House Table Service version check, reached through `houseTableRepository.save` in `OpenHouseInternalTableOperations.doCommit` (iceberg/openhouse/internalcatalog, from line 420). The stub's `HouseTablesH2Repository` is a plain JPA repository, and `HouseTable` has no `@Version` field. So in stub mode, two overlapping commits on the same base can both be accepted, and the later one overwrites the earlier one. With the real service, the same race gave 0 lost updates in 50 runs.
2. The seam changes when the commits happen, and that change exposes the gap. On rest-shim, the replace commit goes through `OpenHouseRestAdapter.updateTable` (lines 398 to 416). That method loads the table again (line 398), marks the commit as a replace (lines 403 to 405), rebuilds the metadata from the head it just loaded (lines 406 to 410), and commits (line 414). The INSERT goes through the same method in the same short window. On legacy, the RTAS commits through `BaseTransaction.commitReplaceTransaction` on the client. In 50 of 50 probe runs it landed after the INSERT. On rest-shim the two commits overlap. In 30 of 50 stub runs the INSERT committed first, and it is still in the history (see O1). In 2 of 50 stub runs the two commits hit the stub's gap.

Classification (inferred): this is a real Spark SQL behavior difference, because the data shows 4 rows where 2 or 3 are valid, and a successful RTAS result is lost. It appears only with the in-memory stub. The missing atomicity is in the test fixture, since the stub has no compare-and-set. The seam changes only the timing, and it does not happen with the real service. The harness assertion is correct. The fault is intermittent: the probe hit it in about 1 of 25 races on orc and in none of 25 on parquet. A single harness run hits it by chance.

### O1. A concurrent INSERT stays in the snapshot history on rest-shim (not asserted by any case)

This difference is recorded for completeness. It does not change any count.

Measured (run here): the final state has 2 rows on both paths. On legacy, the history is `append(+3), append(+2)` in 50 of 50 runs. The INSERT returned success, but its snapshot is gone from `t.snapshots`. On rest-shim, the history is `append(+3), append(+1=4), append(+2)` in 30 of 50 stub runs and in 41 of 50 real-service runs.

Inferred from reading the code: legacy commits the replacement metadata built from the transaction's starting base. The server removes snapshots that the client payload does not list (the "Remove snapshots that are no longer present in the client payload" step in `OpenHouseInternalTableOperations.doCommit`). The adapter applies the replace's updates to a freshly loaded head (`TableMetadata.buildFrom(base)`, `OpenHouseRestAdapter.java` lines 406 to 410), so the earlier snapshot is kept.

This is a metadata difference, visible in the `snapshots` and `history` tables and in time travel to that snapshot. It is not a data difference, because the current rows are the same. The cause is the seam. No harness case asserts it.

## Run notes

The first two rest-shim real-service attempts were killed by the out-of-memory killer (exit 137, about 4.2 to 4.4 GB resident). The memory cgroup was full while another process ran its Gradle test workers. Neither attempt reached the results printout. The third attempt, with the off-heap caps listed above, completed. The heap stayed at `-Xmx3g`.

Legacy full-run counts come from the baseline record. The base commit for that record is bb8929e1. The only non-test change on `irc/c1-rest-shaped-seam` is the addition of the seam classes and an `iceberg-data` exclusion in the itest `build.gradle`. The harness's `print-cp.init.gradle` already applies that exclusion. Legacy was run again only as the `delete parquet` slice and in the 50-run race probe.
