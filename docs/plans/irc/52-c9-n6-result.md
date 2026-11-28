# C9 and N6 result: Docker checks in CI, and the Spark 4.2 rename target

Bottom line: both pull requests are merged into `irc-integration`. N6 is done: renaming a table to a target that
names the catalog works over IRC on Spark 4.2 as it did on the baseline, and the Spark 4.2 smoke suite now declares
11 differences from the baseline instead of 16 (166 of 177 statements behave as on the baseline). C9 is done in part:
two of its four checks are green in CI on every pull request, and two never ran because the Spark image of the 1.12
line has never finished building on a CI runner. The rest is a new work item, C10, which starts after V9e. Written
2026-11-23 from the two agents' reports.

## C9: the Docker checks of the Iceberg 1.12 line in CI (pull request 114)

- Pull request 114 into `irc-integration`, merge commit `1fec3a0affab866b2c13425d135c9e565effb8a0`.
- Green and now run on every pull request: the tables service and scheduler images built from the 1.12 jars start,
  and the integration script passes against them in CI. They add about 3 minutes 20 seconds to the 1.12 job. They add
  no wall-clock time, because the default build job takes 30 to 34 minutes and runs beside the 1.12 job
  (15 to 18 minutes).
- Not green, run by hand only: the Spark image of the 1.12 line has never finished building on a CI runner. So the
  Hadoop recipe and the dataloader tests have never run on Spark 3.5. The job for them exists and starts only by
  hand (`dataloader-tests-iceberg-fork.yml`). No pull request and no merge runs it.
- The causes found are all download problems:
  - Apache's archive host is rate-limited. The Spark tarball (382 MB) had 102 MB after 39 minutes.
  - `apt-get` in the Spark image's builder stage took 42 minutes on one run. The final image copies out only two
    directories of that stage, so C9 replaced the stage with Maven's image and Apache's Docker image of Spark.
  - Livy's thriftserver module downloads a second Spark tarball (228 MB) from the archive host, for tests that are
    skipped. The build stopped there for 29 minutes before the agent cancelled it. Untried next step:
    `-Ddownload.skip=true` on the Maven line of the `livy-dockerhub` stage.
- Wrong in the merged change: three sentences say the archive host is no longer needed (the guide's "nothing comes
  from those two hosts", the env file's "stages that use only Docker Hub, GitHub and Maven Central", and the
  Dockerfile's comment that names only one module). The V9e agent has been asked to correct them.
- Side finding: the existing Spark image build cannot finish on a CI runner today on LinkedIn's line either. Its
  stage needs the archive host for Spark, Maven and, inside Livy's build, Spark 3.1.1 twice.
- Proof the default line is unchanged: compose configuration of the six recipes is byte-identical to the base;
  `build-run-tests.yml`, `pr-validations.yml`, `post-merge-build.yml`, every Gradle file and every Python file are
  untouched; the default build was green in CI on three heads.
- Decisions made by the agent, one line each to reverse: the 1.12 line's Spark image takes its build tools from
  Docker Hub through `OH_SPARK_BUILDER=dockerhub` in `iceberg-fork.env`; the Docker steps run after the suites even
  when those failed; the oh-only recipe waits on both services' health checks instead of sleeping 30 seconds.
- Not proven: that Livy's build finishes, that the pinned Livy runs sessions on Spark 3.5.2 and Java 17, that
  `/opt/spark` from Apache's image is enough for the recipe, and the dataloader tests.
- Side effect: a cache entry saved under the branch's scope could not be deleted (the proxy refuses the cache API).
  It counts toward the repository's cache quota until evicted.
- New work item C10, in `04-work.md`: finish the Spark image build and the dataloader tests on the 1.12 line in CI.
  It starts after V9e, because V9e touches the same files.

## N6: the rename target on Spark 4.2 (pull request 119)

- Pull request 119 into `irc-integration`, merge commit `92c5e117cb80345b46cb8edd2d690358d9be5bba`.
- `ALTER TABLE ... RENAME TO <catalog>.<db>.<t>` now works over IRC as with the OpenHouse catalog, on Spark 4.2 and
  on Spark 3.5 over a plain REST catalog. The Spark 3.5 case had the same difference and is fixed by the same rule
  in the SQL extensions, byte for byte the same file.
- The rule reads the target as the OpenHouse catalog did. More than two levels is refused with `Namespace has
  unexpected levels`. Two levels whose first is the catalog's name has that name taken off. Two levels whose first
  is another catalog is refused with `Cannot rename tables across catalogs`, the baseline's class and message. One
  level or none is left as written.
- The rule applies only to OpenHouse reached through Iceberg's own REST catalog. It does not touch the legacy
  OpenHouse catalog class on Spark 3.5, which is the baseline.
- Before and after, the five statements that differed: the rename itself now succeeds, the old name is not found,
  the new name returns the row, `SHOW TABLES` lists the new name, and the cross-catalog target gives the baseline's
  refusal. The proof is that the smoke suite failed on exactly those five when the rule was in and the old
  declarations were kept.
- Left after N6, 11 differences: 5 are an Iceberg exception type or the new client's wording for the same refusal,
  4 are the namespace and `SHOW TABLES` differences accepted in D37, and 2 are Spark 4 itself.
- The `owner` property question is closed with no difference. Every line stores `owner=<the user Spark runs as>` on
  `CREATE TABLE` and `CREATE TABLE AS SELECT`: the baseline, Spark 3.5 over REST and Spark 4.2 over REST all stored
  `root` in these runs. It comes from Spark's own create path. One test pins it on Spark 4.2. A Spark 4 deployment
  that sets a per-session user would store that user; this was not run.
- One accepted detail (D37 addendum): the rule refuses during analysis, so `EXPLAIN` of a refused rename prints the
  refusal where the baseline prints a plan. For an accepted target, `EXPLAIN` shows `db.t2` where the baseline shows
  `openhouse.db.t2`. Making it exact needs a planner strategy and an exec node.
- A database named like the catalog stays a database when the target has one level. This is pinned on both Spark
  lines.
- Counts: Spark 4.2 runtime tests 177 (was 163), Spark 4.2 smoke task 65 (was 58), Spark 3.5 `catalogTest` 270 with
  1 skipped (the skip existed before), all with 0 failures, on both builds.
- Not run: the full build, the service tests, the Spark 3.1 projects and CI on the pull request. Nothing known red.

## Where the two reports differ from the planning notes

- N6's agent narrowed the rule's scope to the REST path, as above; the earlier notes did not say.
- The C9 agent ran a bare `git stash` six times in its own worktree before the lock arrangement. Nothing was lost.
  This is part of the hazard recorded in `17-delegation.md`.
