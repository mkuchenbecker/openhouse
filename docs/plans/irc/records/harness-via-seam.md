# delta-harness through the REST-shaped catalog path (rest-shim), 2026-11-22

Tree: `irc/t2b-harness-via-seam` = `irc/c1-rest-shaped-seam` (74b3ce60) + merge of `irc/t2-delta-harness` (614018f4)
+ a25f476f (harness `HARNESS_CATALOG_PATH`). Production code: unchanged.
JDK 17, `HARNESS_PARALLELISM=2`, `HARNESS_JVM_ARGS=-Xmx3g` (+ `-Djava.io.tmpdir=<private dir>`; the real-HTS
rest-shim run also had `-XX:MaxDirectMemorySize=512m -XX:ReservedCodeCacheSize=160m`, see "Run notes").

Path selection follows `RestShimPathSparkSqlSmokeTest` exactly: only `spark.sql.catalog.openhouse.catalog-impl`
changes to `com.linkedin.openhouse.spark.OpenHouseRestCatalog`; `default_iceberg` and all other properties are as on legacy.
Every run printed `>> catalog path: ...` naming the class used.

## Counts

| Mode | Path | Cases | Passed | Failed | Skipped | Wall | Source |
|---|---|---|---|---|---|---|---|
| Slice `delete parquet` (stub) | legacy | 238 | 237 | 0 | 1 | 291 s (incl. Gradle build) | run here |
| Slice `delete parquet` (stub) | rest-shim | 238 | 237 | 0 | 1 | 185 s | run here |
| Full, in-memory HTS stub | legacy | 2574 | 2560 | 3 | 11 | 1365 s | BASELINE-2026-11-22.md (not re-run) |
| Full, in-memory HTS stub | rest-shim | 2574 | 2559 | **4** | 11 | 1422 s | run here |
| Full, real HTS (`HARNESS_REAL_HTS=1`) | legacy | 2792 | 2778 | 3 | 11 | 1683 s | BASELINE-2026-11-22.md (not re-run) |
| Full, real HTS (`HARNESS_REAL_HTS=1`) | rest-shim | 2792 | 2778 | 3 | 11 | 1477 s | run here |

Non-passing on rest-shim:
- Same 3 failures as legacy, in both modes, with the same messages (`OpenHouse: Cannot convert policies string to
  policies object`): `control.lock.enforcement`, `interact.rtas.onLockedTable`, `hazard.lock.starvesMaintenance`.
- Same 11 skips (from `Plan.knownBugs`, not run).
- No legacy non-pass passes on rest-shim.
- One extra failure in stub mode only: `surface.conc.rtasVsAppend @ orc` (group D1 below). It passed in the rest-shim real-HTS run
  and on parquet in both rest-shim runs.

## Differences

### D1. `surface.conc.rtasVsAppend @ orc` (stub mode): RTAS racing an INSERT; the RTAS is lost and both statements report success

- **What the case asserts** (SurfaceScenarios.scala:340-354): on a 3-row table with `replace.enabled=true`, run
  `CREATE OR REPLACE TABLE t AS SELECT * FROM t WHERE foo_col_long <= 2` and `INSERT INTO t VALUES (30, ...)` on two threads.
  Each one either succeeds or fails with a typed commit conflict. The table must end with 2 rows (the RTAS was applied last)
  or 3 rows (the INSERT was applied on top of the RTAS).
- **What happened on rest-shim**: `got 4`, with no exception from either thread.
- **What I measured** (a diagnostic probe repeating the case's race 25x per format, scratchpad `probe/RacePr.scala`; not committed):

  | Path / mode | Runs | 2 rows | 3 rows | 4 rows (RTAS lost) | Any thread threw |
  |---|---|---|---|---|---|
  | legacy, stub | 50 | 50 | 0 | 0 | none |
  | rest-shim, stub | 50 | 47 | 1 | **2** (both orc) | none |
  | rest-shim, real HTS | 50 | 49 | 1 | 0 | none |

  In the 4-row runs, the snapshot history is `append(+3=3), append(+1=4)`: the RTAS snapshot does not exist at all, and the
  INSERT's snapshot is based on the seed. The RTAS statement returned normally. This is a silent lost update.
- **Diagnosis** (from reading the code, consistent with the probe):
  1. The server's conflict check is a check followed by a separate write. `OpenHouseInternalRepositoryImpl.versionCheck`
     (services/tables/.../OpenHouseInternalRepositoryImpl.java:588-611) compares the request's base with the head. The only
     atomic guard after that is the House Table Service version check, reached through `houseTableRepository.save` in
     `OpenHouseInternalTableOperations.doCommit` (iceberg/openhouse/internalcatalog/.../OpenHouseInternalTableOperations.java:420
     onward; it maps `HouseTableConcurrentUpdateException` to a conflict). The stub-mode `HouseTablesH2Repository`
     (tables-test-fixtures/.../HouseTablesH2Repository.java) is a plain JPA repository, and `HouseTable` has no `@Version`, so in
     stub mode two commits on the same base that overlap can both be accepted, and the later one overwrites the earlier one.
     With the real HTS, the same race gave 0/50 lost updates.
  2. The seam changes when the commits happen, and that change is what exposes the gap. On rest-shim, the replace commit goes
     through `OpenHouseRestAdapter.updateTable` (integrations/java/iceberg-1.5/openhouse-java-runtime/.../OpenHouseRestAdapter.java:398-416).
     That method loads the table again (`backing.loadTable`, :398), marks the commit as a replace (:403-405), rebuilds the
     metadata from the head it just loaded (:406-410), and commits (:414). The INSERT goes through the same method, inside the
     same short window. On legacy, the RTAS commits through `BaseTransaction.commitReplaceTransaction` on the client and, in 50/50
     probe runs, always landed after the INSERT. On rest-shim the two commits overlap: in 30/50 stub runs the INSERT committed
     first (it is still in the history, see O1), and in 2/50 the two commits hit the stub's gap.
- **Classification**: a real Spark SQL behavior difference (data: 4 rows instead of 2 or 3; a successful RTAS whose result is
  lost). It appears only with the in-memory HTS stub. The missing atomicity is in the test fixture (the stub has no
  compare-and-set). The seam only changes the timing, and it does not happen with the real HTS. The harness assertion is
  correct. Neither the seam nor the case expectation is wrong for production; the result is a fixture artifact that the
  seam's timing exposes. It is intermittent: about 1 in 25 races on orc and 0 in 25 on parquet in the probe; a single harness
  run hits it by chance.

### Observed but not asserted by any case (recorded for completeness; no count change)

**O1. A concurrently committed INSERT stays in the RTAS'd table's snapshot history on rest-shim; on legacy it is removed.**
Probe, final state 2 rows in both paths. Legacy history is `append(+3), append(+2)` in 50/50 runs: the INSERT returned
success, but its snapshot is gone from `t.snapshots`. Rest-shim history is `append(+3), append(+1=4), append(+2)` in 30/50
stub runs and 41/50 real-HTS runs. The cause, from reading: legacy commits the replacement metadata built at the transaction's
start base, and the server removes snapshots missing from the client payload (OpenHouseInternalTableOperations.java, the
"Remove snapshots that are no longer present in the client payload" step in `doCommit`). The adapter applies the replace's
updates to a freshly loaded head (`TableMetadata.buildFrom(base)`, OpenHouseRestAdapter.java:406-410), so the earlier
snapshot is kept. This is a real metadata difference (the `snapshots`/`history` tables and time travel to that snapshot), not
a data difference: the current rows are the same. Its source is the seam. No harness case asserts it.

## Run notes
- The first two rest-shim real-HTS attempts were OOM-killed (exit 137, about 4.2-4.4 GB RSS). The memory cgroup holding all
  agents' shell processes was full while another agent's Gradle test workers ran. Neither attempt reached the results
  printout, so no partial counts were lost. The third attempt, with the off-heap caps listed above, completed. Heap stayed at
  `-Xmx3g`.
- Legacy full-matrix counts were taken from BASELINE-2026-11-22.md (same base commit bb8929e1; the only non-test change on
  c1 is adding the seam classes plus an `iceberg-data` exclusion in the itest build.gradle, which the harness's
  print-cp.init.gradle already applies). Legacy was re-run here only as the `delete parquet` slice and the 50-run race probe.
- Logs (scratchpad `logs/`): `slice-legacy.log`, `slice-rest.log`, `full-rest-stub.log`, `full-rest-hts.log`,
  `probe-legacy.log`, `probe-rest.log`, `probe-rest-hts.log`.
PR: https://github.com/mkuchenbecker/openhouse/pull/86

## Re-probe with compare-and-set stand-in

Tree: scratch worktree `/home/user/oh-t2c` (branch `scratch/t2c-probe`, nothing committed except the merge commit a267c1cf, nothing pushed) = `origin/irc/t2b-harness-via-seam` + merge of `origin/irc/t4b-faithful-standin`. The merge had no conflicts. Same JDK 17 / HARNESS_PARALLELISM=2 / -Xmx3g / private tmpdir as above. All figures below were run, not inferred, unless marked.

**Which stand-in the harness uses.** Three files are named `HouseTablesH2Repository`. The harness classpath carries `tables-test-fixtures-iceberg-1.5_2.12.jar`, built from `tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/java/.../tablestest/HouseTablesH2Repository.java` (the jar's class contains the new `SAVE_LOCK`). t4b changed that file and a copy under `services/tables/src/test/.../e2e/h2/` (used only by the tables-service tests). The two copies differ in one line: the services copy reads the stored version with the scalar query `findStoredLocation(...)`; the fixtures copy declares `findStoredLocation` but `save()` reads it with `findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(...).map(getTableLocation)`, an entity read.

**Probe, rest-shim + stub, t4b stand-in as merged** (100 races per format, `probe/run-probe2.sh rest-shim 100`, log `logs/t2c-probe-rest.log`):
- orc: 100 x 2 rows. Lost RTAS 0/100.
- parquet: 85 x 2 rows, 2 x 3 rows, 13 x 4 rows. Lost RTAS 13/100. In those 13 the history is `append(+3=3),append(+1=4)` (no RTAS snapshot), both statements returned ok, no exception anywhere in the log.
- Before (t2b, 25 per format previously, 50 total): 2/50, both orc. So the lost update is not fixed; it moved to parquet and is more frequent (13/200 overall).
- Cause, observed with a temporary debug print in `save()` (removed): in a lost race two saves arrive with `ver` = `current` = the seed location; the second one read `current` as the seed location although the first save had already written a new location. That is the stale read from the request's persistence context that the t4b comment on `findStoredLocation` describes, so the compare passes and the later write overwrites the earlier one.

**Probe with the scalar read (experiment, not in t4b)**: in the scratch tree only, `save()` in the fixtures copy changed to use `findStoredLocation` as the services copy does (diff in `t2c-scalar-read-experiment.diff`; reverted afterwards). 100 races per format (`logs/t2c-probe-rest-scalar.log`): orc 89 x 2 rows + 11 x 3 rows; parquet 89 x 2 + 11 x 3. Lost RTAS 0/200. No exception reached either statement in any race (no `ERR`, no `CommitFailed`/`ConcurrentUpdate` in the log), and both statements reported ok. So the losing commit is rejected by the stand-in and the client retry commits it (inferred from the outcome: the history then holds both snapshots; I did not log the 409s). The RTAS snapshot is in the history in every race (2 rows: RTAS last; 3 rows: INSERT on top of RTAS).

**`surface.conc` (6 cases: appendAppend, updateUpdate, rtasVsAppend x parquet/orc)**, stub mode:
- As merged: rest-shim 6/6 pass (`logs/t2c-conc-rest.log`), legacy 6/6 pass (`logs/t2c-conc-legacy.log`). The rtasVsAppend pass on rest-shim is by chance given 13% parquet loss in the probe.
- With the scalar-read experiment: rest-shim 6/6, legacy 6/6 (`logs/t2c-conc-scalar-*.log`).

**Other cases (as merged, full matrix rest-shim + stub, `logs/t2c-full-rest-stub.log`)**: 2560 passed, 11 skipped, 3 failed of 2574. The 3 failures are the known lock-policy ones (`control.lock.enforcement`, `interact.rtas.onLockedTable`, `hazard.lock.starvesMaintenance`). The t4b merge broke no other case. Not run on this tree: full legacy matrix, real-HTS mode, full matrix with the scalar-read experiment.

## Stand-in fix and confirmation

**Fix** (commit 2969044d on `irc/t4b-faithful-standin`, pushed, no force; PR #83 untouched). Only two source files named `HouseTablesH2Repository` exist on the branch: `services/tables/src/test/.../e2e/h2/` (used by tables-service tests; `save()` already reads `findStoredLocation`) and `tables-test-fixtures/tables-test-fixtures-iceberg-1.2/.../tablestest/` (compiled into the `tables-test-fixtures_2.12` and `-iceberg-1.5_2.12` jars via srcDirs, so these are the "third copy"; used by Spark ITests and delta-harness). Only the fixtures copy had the flaw: `save()` read via `findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase` (entity, stale from persistence context). Changed to `findStoredLocation(...)`. Ran.

**Test** `HouseTablesH2SaveVersionTest` (fixtures module). Deterministic: a transaction loads the row (findById), another thread saves and commits on the same base, then the first transaction saves on the old base and must get `HouseTableConcurrentUpdateException`. Before the fix: FAILED ("Expected HouseTableConcurrentUpdateException to be thrown, but nothing was thrown"); after: PASSED. (A sequential same-base test passes both before and after, as expected.) Ran.

**Existing tests, after fix (ran)**: all `tables-test-fixtures_2.12` tests (view accessor, bean boundary, local server, tomcat), the 1.5 fixtures module tests, `HouseTablesH2RepositoryTest` (3), `CommitPathConcurrencyTest` (10): all pass. Spark smoke/contract suites not run.

**Probe** (`/home/user/oh-t2c`, merged origin 2969044d as 145b035a, local only), `run-probe2.sh rest-shim 100`: log `logs/t2d-probe-fixed.log`: orc 91x2 rows + 9x3; parquet 86x2 + 14x3; lost RTAS 0/200 (rows=4 never). Ran.

**Observation of rejections** (temporary event line in `save()`, reverted, not committed; log `logs/t2d-standin-events.log`, probe log `t2d-probe-fixed-instr.log`, again 0/200 lost): 1000 ACCEPT, 406 REJECT over 200 race tables; 102 of the 200 races had at least one stale-version rejection; every one of the 406 rejections was followed by an accepted save for the same table built on the winner's location (the client retried and committed). Observed, not inferred. Note several rejections per race (client retries more than once).

**Full matrix**, rest-shim + stub, clean jar: 2574 cases, 2560 passed, 3 failed (control.lock.enforcement, interact.rtas.onLockedTable, hazard.lock.starvesMaintenance), 11 skipped; surface.conc cases passed. Log `logs/t2d-full-rest-stub2.log`. A first attempt was OOM-killed (exit 137, `t2d-full-rest-stub.log`) and re-run after waiting. Not run: legacy matrix, real-HTS mode.
