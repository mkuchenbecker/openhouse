# N6: RENAME TO with a catalog-qualified target, and the `owner` property (record)

Branch `irc/n6-spark42-rename-target`, started from `origin/irc-integration` at c1303849 (merge of
pull request 117). The integration branch moved once (pull request 114, C9: workflows, a Dockerfile,
docs) and is merged in at 1fec3a0a. Draft pull request: 119 into `irc-integration` (https://github.com/mkuchenbecker/openhouse/pull/119). Head: `8ebbda0d4ea900717b51c91145cd50fa4f6793c6`.
Every fact is marked [ran] (run by me on JDK 17) or [read] (taken from code or earlier records).

## Bottom line

- Part 1, fixed. `ALTER TABLE openhouse.db.t RENAME TO openhouse.db.t2` works again on Spark 4.2
  over IRC, and a target that names another catalog is refused with the baseline's class and
  message. An analyzer rule of the OpenHouse SQL extensions (`ResolveRenameTableTarget`) reads the
  target as `OpenHouseCatalog.renameTable` read it. The smoke suite's declared differences drop
  from 16 to 11 (177 statements, 166 the baseline's). [ran]
- The same difference exists on Spark 3.5 with Iceberg's own `SparkCatalog type=rest` and the
  OpenHouse extensions [ran], and the same rule (the identical file) is ported to the Spark 3.5
  extensions; the Spark 3.5 suites are green on both builds [ran].
- Part 2, no difference. The baseline stores `owner` too, with the same kind of value. Nothing was
  changed; one test pins it on Spark 4.2. [ran]

## Part 1: before and after, per smoke statement [ran]

Baseline: Spark 3.5, OpenHouse catalog (the assertion `SparkSqlSmokeScenarios` makes). Before: head
c1303849 (N3b record). After: this branch, from `spark-4.2-irc-outcomes.tsv` of the final run.

| # | Statement | Baseline | Before | After |
| --- | --- | --- | --- | --- |
| s13b #3 | `ALTER TABLE openhouse.smoke_t1.s13b_rename RENAME TO openhouse.smoke_t1.s13b_renamed` | succeeds | `BadRequestException`: Malformed request: OpenHouse namespaces are databases and have exactly one level: openhouse.smoke_t1 | succeeds |
| s13b #4 | `SELECT * FROM ...s13b_rename` | `TABLE_OR_VIEW_NOT_FOUND` | rows [1] | `TABLE_OR_VIEW_NOT_FOUND` |
| s13b #5 | `SELECT * FROM ...s13b_renamed` | rows [1] | `TABLE_OR_VIEW_NOT_FOUND` | rows [1] |
| s13b #6 | `SHOW TABLES IN openhouse.smoke_t1 LIKE 's13b_*'` | [smoke_t1,s13b_renamed,false] | [smoke_t1,s13b_rename,false] | [smoke_t1,s13b_renamed,false] |
| s29 #3 | `ALTER TABLE openhouse.smoke_t1.s29_rename RENAME TO other_catalog.smoke_t1.s29_renamed` | `UnsupportedOperationException`: Cannot rename tables across catalogs: from=openhouse.smoke_t1.s29_rename, to=other_catalog.smoke_t1.s29_renamed | `BadRequestException`: ...exactly one level: other_catalog.smoke_t1 | the baseline's class and message |

The proof the brief asked for [ran]: with the rule and the old declarations, `smokeTest` failed in
exactly s13b ("4 unexpected outcome(s)") and s29 ("1 unexpected outcome(s)"), each line "a
difference is declared but the outcome is the baseline's". The five declarations were then removed.
Left: 11 = 5 of class (a), 2 of class (b), 4 of class (d): s19 #3, s24 #3, s24 #4, s27 #3, s29 #2
(a); s22 #11, s26 #2 (b); s24 #2, s24 #5, s25 #1, s25 #3 (d).

## The rule's exact scope

`integrations/spark/spark-4.2/openhouse-spark-runtime/src/main/scala/com/linkedin/openhouse/spark/sql/catalyst/analysis/ResolveRenameTableTarget.scala`
(and the identical file in the Spark 3.5 runtime), injected with `injectResolutionRule`.

It matches `RenameTable(ResolvedTable(catalog, from, ..), target, isView = false)` when the target
has more than two name parts (a namespace of two or more levels) and
`OpenHouseCapabilities.resolve(catalog).isOpenHouseRest`. Then, as
`OpenHouseCatalog.renameTable` / `CatalogAndDbNameFromNamespace` [read]:

| Target namespace | Outcome |
| --- | --- |
| more than two levels, whatever the first | `org.apache.iceberg.exceptions.ValidationException: Namespace has unexpected levels <levels>` |
| two levels, first equals `catalog.name()` (compared with equals, case-sensitive) | the first level is taken off; the plan carries `[db, table]` |
| two levels, first is anything else | `java.lang.UnsupportedOperationException: Cannot rename tables across catalogs: from=<catalog>.<db>.<table>, to=<target as written>` |
| one level (`RENAME TO db.t2`) | not matched: left as written |
| none (`RENAME TO t2`) | not matched: Spark's `RenameTableExec` gives it the table's namespace, on 3.5.2 as on 4.2.0 [ran: javap] |

Not matched, so not touched [ran, unit tests]: a REST catalog whose server advertises no OpenHouse
capability; a catalog class that implements the capability interfaces; any other Iceberg catalog
(Hadoop); Spark's own catalog; `ALTER VIEW ... RENAME TO` (on a table it keeps Spark's "Cannot
rename a table with ALTER VIEW"); a source table that does not exist (Spark's
`TABLE_OR_VIEW_NOT_FOUND` comes first, as on the baseline).

"An OpenHouse catalog" is the resolver's finding, not a second notion: new method
`CatalogCapabilities.isOpenHouseRest()` = path `ICEBERG_REST` and a non-empty advertised set
(`openhouse.capabilities` in the catalog's merged properties, where the server's override wins).

### Choice: the rule does not apply to OpenHouse catalog classes (path `OPENHOUSE_CATALOG`)

The brief says "a catalog the extensions consider an OpenHouse catalog". I narrowed that to
OpenHouse reached through Iceberg's own `RESTCatalog`, because an OpenHouse catalog class reads the
target itself, and on Spark 3.5 that class is the baseline: with the narrower scope the rule cannot
change anything on the legacy `OpenHouseCatalog` or the in-process `OpenHouseRestCatalog` path, not
even what EXPLAIN prints (below). On Spark 4.2 no such class exists in the engine, so the two
readings are the same there. My first, unpushed version applied to both paths; if you want that, it
is a one-line change of `isOpenHouseRest` and one unit test.

## The ambiguity: a database named like the catalog

`OpenHouseCatalog.renameTable` decides by the number of levels only [read]: one level is always the
database, two levels are always `<catalog>.<database>`. So:

| Statement (catalog `openhouse`) | Meaning | Baseline, Spark 3.5 + OpenHouse catalog [ran] | Rule [ran] |
| --- | --- | --- | --- |
| `ALTER TABLE openhouse.db.t RENAME TO openhouse.t2` | table `t2` of database `openhouse` | the service's refusal "Rename table across databases is not supported. Cannot rename table ... from db to openhouse" | not matched; the same refusal from the service |
| `ALTER TABLE openhouse.openhouse.t RENAME TO openhouse.t2` | the same database | works | not matched; works |
| `ALTER TABLE openhouse.openhouse.t RENAME TO openhouse.openhouse.t2` | `<catalog>.<database>` | works | one level taken off, once; works |

Pinned by `RenameTableTargetTest.aDatabaseNamedLikeTheCatalogIsADatabaseInATargetOfOneLevel` (plan
and what reaches the catalog), by `SparkIrcRenameTargetTest` of the same name (Spark 4.2 against
the service) and by the Spark 3.5 twin test. The rule returns a plan it no longer matches (one level
less), so the analyzer's fixed point cannot take a second level off.

## One thing that is not the baseline's: EXPLAIN

The catalog class refused when the statement ran; the rule refuses during analysis. `spark.sql` of
the statement fails the same way either way. `EXPLAIN ALTER TABLE ... RENAME TO other_catalog.db.t2`
differs: the baseline prints a physical plan [ran on Spark 3.5], with the rule Spark prints "Error
occurred during query planning:" and the refusal's message; and for an accepted target EXPLAIN shows
`db.t2` where the baseline shows `openhouse.db.t2`. Pinned in
`RenameTableTargetTest.explainOfARenameShowsThatTheTargetIsReadDuringAnalysis`. `ResolveUnlockTable`
refuses during analysis in the same way. Making it exact needs a planner strategy and an exec node
that refuses when run; say if you want it.

## Spark 3.5 over a plain REST catalog [ran]

Scratch probe in the Spark 3.5 itest JVM (default build, Spark 3.5.2, Iceberg 1.5.2, embedded
service, both extensions; not committed; its output is
`/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/n6-probe35-default.keep.txt`):

| Statement | `openhouse` (OpenHouse catalog) | `irc` (`SparkCatalog type=rest`), before the port |
| --- | --- | --- |
| `RENAME TO <catalog>.db.r1` | ok | `BadRequestException`: Malformed request: OpenHouse namespaces are databases and have exactly one level: irc.n6probe |
| `RENAME TO other_catalog.db.r2` | `UnsupportedOperationException`: Cannot rename tables across catalogs: from=openhouse.n6probe.r1, to=other_catalog.n6probe.r2 | `BadRequestException`: ...exactly one level: other_catalog.n6probe |
| `RENAME TO <catalog>.db.x.r2` | `ValidationException`: Namespace has unexpected levels openhouse.n6probe.x | `BadRequestException`: ...exactly one level: irc.n6probe.x |
| `RENAME TO other_catalog.db.x.r2` | `ValidationException`: Namespace has unexpected levels other_catalog.n6probe.x | not run |
| `RENAME TO db.r3`, `RENAME TO r4` | ok, ok | ok, ok |
| `RENAME TO OpenHouse.db.r5` | `UnsupportedOperationException`: Cannot rename tables across catalogs | not run |
| `RENAME TO openhouse.r5` (from database n6probe) | Java client exception, 400 "Rename table across databases is not supported. Cannot rename table r2 from n6probe to openhouse" | not run |
| `EXPLAIN ... RENAME TO other_catalog.db.r6` | prints `RenameTable ..., n6probe.r2, other_catalog.n6probe.r6, ...` | not run |
| `ALTER VIEW <table> RENAME TO other_catalog...` | `AnalysisException`: Cannot rename a table with ALTER VIEW. Please use ALTER TABLE instead. | not run |

So the difference is the catalog path's, not Spark 4's. Ported: the rule file (byte-identical to the
4.2 one), `isOpenHouseRest` (the same method), one resolver test (which also says the legacy
`OpenHouseCatalog` and the in-process `OpenHouseRestCatalog` are not `isOpenHouseRest`), and
`IcebergRestSparkSqlTest.renameToATargetThatNamesACatalogAnswersAsThroughTheOpenHouseCatalog`, which
runs the same statements through `openhouse` (reference) and `irc` and checks the service's tables.
Red before the port [ran]: the `openhouse` half passed, the `irc` half failed at its first statement
with the `BadRequestException` above. The plan-level unit tests (`RenameTableTargetTest`) exist only
in the Spark 4.2 runtime module: the Spark 3.5 runtime's unit-test classpath cannot raise Spark's own
errors (N3-x1 record [read]).

## Part 2: the `owner` property

| Line | Stores `owner`? | Evidence |
| --- | --- | --- |
| Baseline: Spark 3.5.2, OpenHouse catalog, Iceberg 1.5.2 | yes, `owner=root` for CREATE TABLE and for CREATE TABLE AS SELECT | [ran] the probe: stored properties read through the OpenHouse Java client (`getOpenHouseCatalog(spark).loadTable(..).properties()`) |
| Spark 3.5.2, `SparkCatalog type=rest` | yes, `owner=root`, both statements | [ran] the same probe |
| Spark 4.2.0, `SparkCatalog type=rest`, Iceberg 1.12 | yes, `owner=root`, both statements | [ran] `SparkIrcWriteStatementsTest.aTableSparkCreatesStoresTheUserSparkRunsAsAsItsOwner`, read through the Java client in the service JVM |

- Where the value comes from [ran: javap of both Spark jars]: `CatalogV2Util.withDefaultOwnership`,
  called from `CatalogV2Util.convertTableProperties(TableSpec)`, which all seven create and replace
  exec nodes call on both lines. Spark 3.5.2 puts `Utils.getCurrentUserName()`; Spark 4.2.0 puts
  `CurrentUserContext.getCurrentUser()`. In these runs both are the OS user (`root`), and the test
  compares with `SparkContext.sparkUser()`. A Spark 4 deployment that sets a per-session user would
  store that user; not run.
- Nothing strips it [read, and ran above]: Iceberg's `Spark3Util.rebuildCreateProperties` drops
  only `location` and `provider` (both lines [ran: javap]); the Java client's
  `OpenHouseTableOperations` drops only the policy patch key; the service's create keeps every key
  that is not preserved (`openhouse.` prefix, `policies`); the IRC create mapping passes properties
  on.
- Hidden and not settable, both lines [ran]: `SHOW TBLPROPERTIES t ('owner')` answers "Table ...
  does not have property: owner"; `ALTER TABLE t SET TBLPROPERTIES ('owner'='x')` is a
  `ParseException`.
- By the brief's rule (same property, same kind of value): no change. The pin also asserts that a
  later INSERT and SET TBLPROPERTIES keep the value.

## Counts [ran], JDK 17, head 8ebbda0d (the test tasks ran on 98f0d44f, its first parent; see the note under the table)

| Task | Result |
| --- | --- |
| `-PicebergFork` Spark 4.2 runtime `test` | 177, 0 failed, 0 skipped (163 before; +13 `RenameTableTargetTest`, +1 resolver) |
| `-PicebergFork` Spark 4.2 itest `test` | 40, 0 failed |
| `-PicebergFork` Spark 4.2 itest `extensionsTest` | 24, 0 failed |
| `-PicebergFork` Spark 4.2 itest `smokeTest` | 65, 0 failed (58 before; +6 `SparkIrcRenameTargetTest`, +1 owner) |
| default build, Spark 3.5 runtime `test` | 28, 0 failed (27 before; +1 resolver) |
| default build, Spark 3.5 itest `catalogTest` | 270, 1 skipped, 0 failed (269 before; +1) |
| default build, Spark 3.5 itest `statementTest` | 137, 0 failed |
| default build, Spark 3.5 itest `test` | 136, 0 failed |
| `-PicebergFork` Spark 3.5 runtime `test` | 28, 0 failed |
| `-PicebergFork` Spark 3.5 itest `catalogTest` | 270, 1 skipped, 0 failed |
| `-PicebergFork` Spark 3.5 itest `statementTest` | 137, 0 failed |
| `-PicebergFork` Spark 3.5 itest `test` | 136, 0 failed |
| `spotlessCheck` | default build: all projects (the commit and push hooks); `-PicebergFork`: the four touched projects |
| `scripts/iceberg-fork/publish-local.sh` | no-op ("already published") |

On the merge: every count above was run on 98f0d44f with the tasks forced to execute. The merge
commit 8ebbda0d adds eight files of pull request 114, none of them a source or Gradle file. On
8ebbda0d [ran]: under the switch the Spark 4.2 itest's `test`, `extensionsTest` and `smokeTest`
executed again (40, 24, 65, 0 failed) and Gradle found the Spark 4.2 runtime `test` and all four
Spark 3.5 test tasks UP-TO-DATE; on the default build the four Spark 3.5 test tasks came FROM-CACHE.
So no test input changed with the merge. The skipped test of `catalogTest` is
`ColumnDefaultSparkITest.parquetBackfillsColumnDefaultsViaReadBridge`, skipped before this work as
well [read: earlier records count 1 skipped].

Red first [ran]: `RenameTableTargetTest` without the rule, 13 tests, 6 failed (the six that need
it), 7 passed (the ones that pin what must not change). The Spark 3.5 twin test: red as above.
`SparkIrcRenameTargetTest` and the owner test were written after the rule and passed on their first
run. "Before" counts are the earlier records' figures [read], not re-run on c1303849.

## Not run

`:services:tables:test` and `:tables-test-fixtures:tables-test-fixtures_2.12:test` (no service or
fixture code changed). The full build. The other consumers of the Spark 3.5 runtime jar
(`:apps:spark-3.5`, `:libs:datalayout`, the delta harness): they use the OpenHouse catalog class,
which the rule does not match. The Spark 3.1 projects (not touched; they have their own runtime).
CI on the pull request: not waited for.

## Deviations from the brief

1. The rule's scope is OpenHouse over Iceberg's `RESTCatalog`, not every catalog the resolver
   calls OpenHouse (above, with the reason).
2. The rule also refuses a target namespace of more than two levels, with the baseline's
   `ValidationException`; the brief names only the first-level cases. It is what
   `OpenHouseCatalog.renameTable` did before it looked at the first level [ran on Spark 3.5].
3. The Spark 3.5 port has no plan-level unit test of its own (above).
4. Local history was rewritten once before the first push (the scope change); nothing was
   force-pushed. The branch was pushed before the integration branch's move was merged in, so the
   pull request's history has the merge commit on top.
5. The Javadoc of `Spark42IrcSparkSqlSmokeTest` names decision D37, which I have not read; the
   number is the brief's.

## Noticed

- The repository's commit and push hooks stash and pop on a stack all worktrees share, and they
  stash staged-only changes too. At 11:14 UTC another agent's hook popped this branch's entry; no
  content was lost (commit 7989f2cf held both files). Since then every commit and push here runs
  under `flock .../scratchpad/git-hook-stash.lock`, as the coordinator arranged for all agents.
- `git commit`'s hook prints "[[: not found" (it runs under sh); its exit code still works.

## Commits

- b300e997 test: a RENAME TO target that names the catalog, for a table of OpenHouse over REST
- 9e6b58da feat: the capability resolver says whether a catalog is OpenHouse over Iceberg's REST catalog
- 7989f2cf feat: the Spark 4.2 extensions read a RENAME TO target as the OpenHouse catalog did
- eae23853 test: the smoke suite's five RENAME TO differences are gone; the other targets end to end
- 05634d4f test: a table Spark 4.2 creates over IRC stores Spark's owner property, as on the baseline
- e0412afe test: on Spark 3.5 a RENAME TO target that names a catalog answers over REST as through the OpenHouse catalog
- 98f0d44f feat: the Spark 3.5 extensions read a RENAME TO target as the OpenHouse catalog does
- 8ebbda0d Merge origin/irc-integration (pull request 114) into N6
