# Build the Spark 3.5 docker container first; the view DDL suite is the last of five stages

A view DDL suite for Spark on docker is not a test milestone. Five things must be true
before a single `CREATE VIEW` can execute against a docker-deployed OpenHouse, and two
of them are engineering milestones that dwarf the suite itself. The recommendation is to
split the work into five stages, ship the first two now, and let the suite arrive last:

0. **Stage 0 — build the 3.5 image and open a Livy session on it.** No recipe, no
   workflow, no Python. An afternoon.
1. **Stage 1 — a Spark 3.5 / Iceberg 1.5 recipe that runs one ordinary table round
   trip.** Unblocked once Stage 0 answers.
2. **Stage 2 — House Tables view rows.**
3. **Stage 3 — a real `ViewsService` backed by a `ViewOperations`.**
4. **Stage 4 — the CREATE / SELECT / SHOW / DROP suite.**

§4.1 maps these onto the prerequisites in §3 — they are *not* a one-to-one
correspondence — and gives what each stage leaves behind.

Two decisions have to be settled now even though their work lands later. Both are in
the body and neither is visible from this list, so they are stated here:

- **View metadata goes to `<view-location>/%05d-<uuid>.metadata.json`** — the view's root
  directory, uncompressed, matching the OpenHouse table convention rather than Iceberg's
  `<location>/metadata/….json.gz` default. §6 has the argument. It is settled now because
  `OpenHouseViewOperations` cannot be written without it and because the layout under
  `/data/openhouse` is permanent once a view exists.
- **The dockerized MySQL must mount `services/housetables/ddl/`.** Without it the
  container's `entity_type` column takes MySQL 8's default collation, which is not the
  one the deployed schema pins — so the suite would certify a schema that differs from
  production in exactly the axis it exists to check. §3 P5.

The deciding argument is that the biggest blocker is invisible from the suite's
vantage point. The dockerized Spark is Spark 3.1.1 carrying the Iceberg-1.2 uber jar,
whose `OpenHouseCatalog` `extends BaseMetastoreCatalog` — it is not an Iceberg
`ViewCatalog` at all. No amount of test code makes `CREATE VIEW` reach the server from
that container. A Spark 3.5 image definition exists but has never been built, and its
Livy is patched to Spark 3.1.1. Discovering that while writing assertions, after the
persistence milestone has already been declared done, is the outcome staging exists to
prevent.

**Status:** nothing in this document is built. Stage 1 is unblocked and can start.
**Branch:** `claude/iceberg-rest-spec-compliance-l0s2ju`.
**Companions:** [views-iceberg-rest-compliance.md](views-iceberg-rest-compliance.md)
(the server wire surface these tests exercise) ·
[views-client-plugin-plan.md](views-client-plugin-plan.md) (the Spark plugin that
speaks it). Read the client plan's §5.3 first: it defines the disabled posture that
Stage 1 asserts. [views-docker-rest-tests-plan.md](views-docker-rest-tests-plan.md) is
the adjacent proposal: a black-box HTTP suite for the same views routes against the
`oh-only` recipe, with no Spark in the picture. The two are complementary and neither
blocks the other — that one proves the server's deployed surface, this one proves an
engine can drive it.

## 1. Requirements

Numbering is continuous. Later sections refer to these as M1–M6 (must), S7–S10
(should), W11–W14 (won't this milestone) and O15–O18 (out of scope).

**Must**

1. Every assertion runs against a deployed stack: the real House Tables Service over
   HTTP and JDBC/MySQL, real HDFS, a real standalone Spark cluster, and the shipped
   shaded uber jar — not an embedded service, not `file:///`, not a test classpath.
2. A failing SQL statement fails the test. No step prints an error and returns; no step
   waits on human input.
3. The result is reproducible from a clean tree with one documented command sequence,
   and runnable in GitHub Actions on `ubuntu-latest`.
4. The end state asserts view DDL through Spark SQL — `CREATE VIEW`, `SELECT` through
   the view, `SHOW VIEWS`, `DROP VIEW` — on returned rows, not on the absence of an
   exception.
5. No stage changes the behavior of the four existing Spark recipes (`oh-hadoop-spark`,
   `oh-s3-spark`, `oh-abs-spark`, `spark-only`) or of table operations in docker.
6. Views stay off by default. Enabling them is per-Livy-session configuration, never a
   compose-level or `cluster.yaml` default.

**Should**

7. Follow the assertion pattern already proven by
   `integrations/python/dataloader/tests/integration_tests.py`; build nothing on
   `scripts/python/livy_cli.py`.
8. One assertion proves the view definition survived the House Tables round trip and
   outlived the Spark session that created it.
9. The docker suite pins the same observables as the embedded-service suite
   (`OpenHouseViewGateOnTestSpark3_5`), so a divergence between the H2 test fixture and
   the real service becomes visible instead of silent.
10. Each stage is independently mergeable and leaves behind an assertion in CI that
    would catch a regression in what that stage built.

**Won't, this milestone**

11. Won't extract a shared Livy harness across the dataloader suite and this one.
    `LivySession` lives in that package's `tests/`, which does not ship — the published
    distribution is built from `src/`. The real cost of extracting it is that there is no
    shared installable package under `integrations/python/`, and `dataloader/Makefile:48-52`
    builds its itest image with `uv pip install --target … ".[dev]"`, so a second consumer
    needs its own packaging or a path hack. Stage 4 carries its own copy; de-duplication is
    a later, optional cleanup.
12. Won't run the docker Spark suite on every pull request. It is path-filtered, like
    `.github/workflows/dataloader-tests.yml`.
13. Won't add view support to spark-3.1 / iceberg-1.2. That runtime's Iceberg has no
    view API; the 3.1 recipes stay table-only.
14. Won't rewrite or delete `scripts/python/livy_cli.py`. It is a developer REPL, not a
    test harness, and it is fine at that job.

**Out of scope**

15. Trino and every other engine. Views reach engines through the REST catalog; proving
    one engine end to end is this milestone's claim.
16. The `oh-s3-spark`, `oh-abs-spark` and `spark-only` recipes. HDFS is the storage that
    makes the docker path worth more than the embedded path; S3 and ABS add credential
    plumbing without adding coverage of the House Tables round trip.
17. View-level authorization and OPA policy for views. `AuthorizationInterceptor` can
    deny, but the token interceptor answers 401 before dispatch for exactly the requests
    that would reach a denial, so no authorization outcome is observable over this
    deployment; a suite that "passes" by never reaching one would assert nothing.
18. `rename-view`, which the server deliberately does not serve.

## 2. Problem

No test anywhere proves that an engine can create and read an OpenHouse view against a
deployed OpenHouse, and the suite that looks like it could runs against a stub of the
service that would store one.

By default every Spark integration test bypasses the real House Tables Service.
`tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/java/com/linkedin/openhouse/tablestest/HouseTablesH2Repository.java:33-38`
is annotated `@Primary` and `@ConditionalOnProperty(name = "openhouse.htsStub.enabled",
havingValue = "true", matchIfMissing = true)`, and it **replaces** `HouseTableRepository`
wholesale with an in-memory H2 repository. The iceberg-1.5 fixtures module has no
sources of its own — `tables-test-fixtures/tables-test-fixtures-iceberg-1.5/build.gradle:15-23`
adds the 1.2 module's source directories — so the stub is equally in force for the
Spark 3.5 suite. Consequently `OpenHouseViewGateOnTestSpark3_5` and every sibling
`OpenHouseSparkITest` never execute
`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImpl.java`,
the generated WebClient, `UserHouseTablesController`, or any JDBC statement.

**But "by default" is doing work in that sentence, and it is the reason this document
must be careful about what docker buys.** The delta-harness already opts out:
`integrations/spark/delta-harness/src/main/scala/harness/openhouse/Env.scala:101-115`
reads `HARNESS_REAL_HTS=1`, sets `openhouse.htsStub.enabled=false`, and boots the real
House Table Service as a second in-JVM context — so `HouseTableRepositoryImpl`, the
generated WebClient, `UserHouseTablesController` and real JDBC statements all run today
with no container at all. `HouseTablesH2Repository`'s own javadoc names that harness as
the consumer of the opt-out.

**And the embedded suite is not a `MockMvc` suite.** `OpenHouseLocalServer:39,46` runs
`SpringApplication.run(SpringH2TestApplication.class)` and reads its port back from
`((WebServerApplicationContext) appContext).getWebServer().getPort()` — a real embedded
Tomcat on a real loopback socket. `OpenHouseViewGateOnTestSpark3_5` already crosses a
network in the only sense that matters to a `RESTCatalog`, and
`testGateOnProgrammaticViewCatalogAnswersOverTheWire` exists precisely to prove it, by
pinning the *server's* `"Views are disabled"` string against the gate-off control's
client-local message.

So the honest statement of docker's marginal value is narrower than "a real socket".
Against the embedded suite, `oh-hadoop-spark` supplies five things — a separate process,
MySQL 8 rather than H2-in-MySQL-mode, real HDFS at `hdfs://namenode:9000/data/openhouse`
rather than a temp directory, the shaded uber jar on a standalone cluster classpath, and
Livy. Its tables service talks to `http://openhouse-housetables:8080`, which talks to
`jdbc:mysql://mysql:3306/oh_db`
(`infra/recipes/docker-compose/oh-hadoop-spark/cluster.yaml`; `oh-s3-spark` and
`oh-abs-spark` do the same, and are scoped out separately).

Of those five, **Stage 1 can reach exactly two** — the uber jar and Livy — because MySQL
and HDFS view rows do not exist until Stage 3. That is what sizes Stage 1, and §4.1
scopes it accordingly.

## 3. What has to be true first

Five prerequisites, sized. P1 is the row that decides the sequencing, because it is the
only one that cannot be met by editing a compose file.

| # | What is missing | Kind | Size | Without it |
|---|---|---|---|---|
| **P1** | A Spark 3.5 / Iceberg 1.5 container that actually boots | milestone | days if Livy 0.8 hosts Spark 3.5.2; open-ended if it does not | View DDL cannot execute in docker at all, whatever the server does |
| **P2** | A server that can persist a view — House Tables rows *and* a `ViewsService` | milestone | unestimated; the largest body of net-new design in the plan | `CREATE VIEW` returns 404 "Views are disabled" |
| P3 | A way to turn views on for Spark in docker | plumbing | hours | The client gate stays off; no view call crosses the wire |
| P4 | A docker assertion harness that fails on failure | plumbing | hours | Nothing that runs today can fail a build |
| P5 | The pinned `entity_type` collation in the dockerized MySQL | plumbing | hours | Stage 4 passes against a schema that differs from the deployed one — see below |

P1's "mostly plumbing with one unbounded unknown" is the substance of §3.1, and P2's
built-but-off-branch / net-new split is the substance of §3.2; neither is repeated in
the table. "Largest" for P2 means net-new design decisions, not lines: the 697 branch
alone carries ~6,100 insertions in `services/housetables`, more code than Stage 3's
enumerated surface. Stage 3 is harder per line, not bigger.

**P5, which the first draft of this plan missed entirely.** `services/housetables`
sets `spring.jpa.hibernate.ddl-auto=none` with `spring.sql.init.mode=always`, so the
docker schema comes from `schema.sql` — which declares `entity_type VARCHAR(128)` with
**no collation**, deliberately, because H2 executes the same file in tests. The
collation is pinned separately in `services/housetables/ddl/0002__pin_entity_type_collation.sql`,
whose header states that deployment recipes are expected to mount that directory into
the MySQL image's init directory. `common/mysql-services.yml` is eleven lines and
declares no `volumes:`. Nothing mounts it.

Left alone, the container's column takes MySQL 8's default `utf8mb4_0900_ai_ci`, under
which an accented `TÁBLE` compares equal to `TABLE` — a typed predicate selects a row
whose `EntityType.fromName` hydration then fails, and a typed read becomes a 500. Stage
4 would run its `SELECT entity_type, metadata_location FROM user_table_row` and report
green against a schema that diverges from production in precisely the axis this suite
exists to check. The fix is four lines of compose (mount `services/housetables/ddl/` at
`/docker-entrypoint-initdb.d`) and one Stage 4 assertion on
`information_schema.columns.collation_name`.

### P1 — the dockerized Spark physically cannot run a view

`infra/recipes/docker-compose/common/spark-services.yml` hard-codes
`infra/recipes/docker-compose/common/spark/spark-base-hadoop2.8.dockerfile` for
`spark-master`, `spark-worker-a` and `spark-livy`, and all four Spark recipes extend
their Spark services from that one file (`oh-hadoop-spark/docker-compose.yml:118,124,132`;
`oh-s3-spark:80,90,98`; `oh-abs-spark:67,73,81`; and `spark-only:6,12`, which takes the
master and worker only). That dockerfile
sets `SPARK_VERSION=3.1.1` (`:14`) and bakes in the spark-3.1 runtime jar at `:85`,
copied from `build/openhouse-spark-runtime_2.12/libs/openhouse-spark-runtime_2.12-uber.jar`.
That module builds against `iceberg_1_2_version`
(`integrations/spark/spark-3.1/openhouse-spark-runtime/build.gradle:9`, resolving to
`1.2.0.20`), and the iceberg-1.2 `OpenHouseCatalog`
(`integrations/java/iceberg-1.2/.../javaclient/OpenHouseCatalog.java:72`) is
`extends BaseMetastoreCatalog`. Spark never routes a view call to it, because it is not
a `ViewCatalog`.

A Spark 3.5 image exists at
`infra/recipes/docker-compose/common/spark/spark-3.5-base-hadoop3.2.dockerfile`
(`SPARK_VERSION=3.5.2`, `HADOOP_VERSION=3`, copying the 3.5 uber jar at `:85`), and
`./gradlew dockerPrereqs` already builds that jar (`build.gradle:216`). But **no compose
file references the 3.5 dockerfile** — the only occurrence in the whole tree is the
comment at `build.gradle:198`. It has therefore never been built, and inspection turns
up two defects plus one unknown:

- **Its Livy is pinned to Spark 3.1.1.** The image applies
  `common/spark/livy_spark3_hadoop3.patch` verbatim (`:37-46`), and that patch sets
  `spark.scala-2.12.version` to `3.1.1` and `spark.bin.download.url` to
  `spark-3.1.1-bin-hadoop2.7.tgz` in both the root properties and the `spark-3.0`
  profile. So the image would install Spark 3.5.2 into `/opt/spark` and put in front of
  it a Livy compiled against Spark 3.1.1. Livy's repl and its
  `LivySparkUtils` version check both have to be re-verified against 3.5.2; this is
  the unbounded part of P1's estimate and the first thing Stage 1 should try.
- **It copies the wrong apps jar.** Line `:86` copies
  `build/openhouse-spark-apps_2.12/libs/openhouse-spark-apps_2.12-uber.jar` — the
  spark-3.1 / iceberg-1.2 apps jar. The 3.5 equivalent is
  `:apps:openhouse-spark-apps-1.5_2.12` (`settings.gradle:73`), whose shadow jar lands
  at `build/openhouse-spark-apps-1.5_2.12/libs/openhouse-spark-apps-1.5_2.12-uber.jar`
  and which `dockerPrereqs` does not build.
- **Its Hadoop client is 3.2.1 against a 2.8 namenode.** The 3.5 image is
  `FROM bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8` with `HADOOP_HOME=/opt/hadoop-3.2.1`,
  while `common/hdfs-services.yml` runs `bde2020/hadoop-{name,data}node:1.2.0-hadoop2.8-java8`.
  Cross-major HDFS RPC usually works; it is not something to assert without a write.

One thing does line up: both dockerfiles land the runtime jar at the same in-image
path, `$SPARK_HOME/openhouse-spark-runtime_2.12-latest-all.jar`, so every existing
`spark.jars=local:/opt/spark/openhouse-spark-runtime_2.12-latest-all.jar` config keeps
working unchanged on the 3.5 image.

### P2 — the server cannot persist a view, and the House Tables half is off-branch

`ViewsDisabledService`
(`services/tables/src/main/java/com/linkedin/openhouse/tables/services/ViewsDisabledService.java:30`)
is still the only `@Component` implementing `ViewsService`, and all six methods throw
`ViewApiException(VIEWS_DISABLED)`. Neither `origin/claude/port-696-entity-type-discriminator`
nor `origin/claude/port-697-hts-view-lifecycle` is an ancestor of this branch
(`git merge-base --is-ancestor` returns non-zero for both), so House Tables here is
table-only: no `EntityType`, no view query path.

**The House Tables half is built, elsewhere.** On the 697 branch, `UserTableRow` carries
`@Version Long version`, `metadataLocation`, `storageType`, `creationTime` and a
`@Convert`-ed `EntityType entityType` that resolves null to `TABLE` on read; views share
`user_table_row` with tables and are discriminated by `entity_type = 'VIEW'`, served
through the existing user-table routes with a service-owned `UserViewQuery`. No view SQL
is stored in the database — the metadata file is the definition. Bringing that onto this
branch is merge and review work, not design work.

**The `ViewsService` half is net-new.** What exists and is reusable:

- location allocation — `BaseStorage.allocateTableLocation(...)`
  (`cluster/storage/src/main/java/com/linkedin/openhouse/cluster/storage/BaseStorage.java:48-85`),
  producing `{endpoint}{rootPrefix}/{databaseId}/{tableId}-{uuid}`;
- `FileIO` resolution — `OpenHouseInternalCatalog.resolveFileIO`
  (`iceberg/openhouse/internalcatalog/.../OpenHouseInternalCatalog.java:302`);
- the metadata-write pattern —
  `OpenHouseInternalTableOperations.java:356-383`, which writes through
  `TableMetadataParser.write(metadata, io().newOutputFile(newMetadataLocation))` under a
  metrics-reported span and then maps to a `HouseTable`;
- the House Tables round trip — `HouseTableRepositoryImpl.save`/`findById`
  (`:152-179`) plus its `WebClientResponseException` → typed-exception translation
  (`:188-216`), which is where `Conflict` becomes `HouseTableConcurrentUpdateException`;
- everything Iceberg needs, verified present in `iceberg-core-1.5.2.17` with `javap`:
  `ViewMetadataParser.write(ViewMetadata, OutputFile)` / `.read(InputFile)`,
  `ViewMetadata.builder()` / `.buildFrom(...)`, `BaseViewOperations`,
  `BaseMetastoreViewCatalog`, `MetadataUpdate.applyTo(ViewMetadata$Builder)`,
  `UpdateRequirement.validate(ViewMetadata)`, `UpdateRequirement$AssertViewUUID`.

What does not exist: any `ViewOperations` implementation; `OpenHouseInternalCatalog`
is `extends BaseMetastoreCatalog` (`:53`) with no `newViewOps`; the internalcatalog
`HouseTable` entity
(`iceberg/openhouse/internalcatalog/.../model/HouseTable.java:21-53`) has no
`entityType` field; `HouseTableMapper` exposes only `toHouseTable(TableMetadata, FileIO)`
and the `UserTable` conversions, with no `ViewMetadata` overload; and
`OpenHouseInternalRepositoryImpl` has no view path. §6 settles the one design decision
that this work cannot start without.

### P3 — no way to turn views on for Spark in docker

`spark.sql.catalog.openhouse.iceberg-views-enabled` is a client-side catalog property,
read once at `OpenHouseCatalog.initialize` and defaulting to `false`
(`integrations/java/iceberg-1.5/.../OpenHouseCatalog.java:148,213`). On the docker path
it is expressible only inside a Livy session's `conf` map. Nothing in any compose file,
`SETUP.md`, or any `spark-defaults.conf` sets it — there is no `spark-defaults.conf`
anywhere in the tree, and `common/spark/start-spark.sh` writes only
`livy.spark.master` and `livy.server.port` into `livy.conf`.

Docker catalog configuration lives in exactly three places, all consistent, all using
`uri=http://openhouse-tables:8080`, `cluster=LocalHadoopCluster`, and
`spark.jars=local:/opt/spark/openhouse-spark-runtime_2.12-latest-all.jar`:
`scripts/python/livy_cli.py:16-33`,
`integrations/python/dataloader/tests/integration_tests.py:27-50`, and `SETUP.md:290-299`.
All three also pin `spark.jars.packages=org.apache.iceberg:iceberg-spark-runtime-3.1_2.12:1.2.0`,
which is the coordinate a Spark 3.5 session must change (§4).

### P4 — the assertion harness

`scripts/python/livy_cli.py` is not a test harness. `run_statement` (`:129-148`) prints
`ename`/`evalue` on failure and returns `None`, so a failed statement is
indistinguishable from a passing one; `run_table_test` (`:188-201`) blocks on `input()`
waiting for a human; nothing in `.github/workflows/` invokes it.

The real precedent is `integrations/python/dataloader/tests/integration_tests.py`, run
by `.github/workflows/dataloader-tests.yml`. Its `LivySession._run` (`:95-118`) raises
`RuntimeError` when `output["status"] == "error"`, `query()` (`:90-92`) returns rows,
and a genuine output assertion sits at `:321-325`. It runs from **inside a container
joined to the compose network** — `integrations/python/dataloader/Makefile:54-58` does
`docker run --network $(DOCKER_NETWORK)` with `DOCKER_NETWORK ?= oh-hadoop-spark_default`
— using `BASE_URL=http://openhouse-tables:8080` and `LIVY_URL=http://spark-livy:8998`.
Its workflow polls `localhost:8000/v1/databases` and `localhost:9003/sessions` every 5s
for up to 600s before running. Stage 4 copies that shape; it does not invent one.

## 4. Staging: options and recommendation

Options are staging strategies; the columns are the seven requirements from §1 that the
four options actually differ on. All four satisfy M2, M6 and S8 identically. **Stage the work
four ways (option B), because it is the only option that satisfies M5 and S10 together:
each stage merges on its own and leaves a CI assertion behind, so the Livy/Spark-3.5
unknown in P1 is discovered by the stage that owns it rather than by the stage that
depends on it.** The row that decides against option A is S10: a single milestone
carries P1's open-ended risk inside the same deliverable as the persistence work, where
it can only be discovered late.

| Option | M1 real stack | M3 clean tree + CI | M4 view DDL asserted | M5 recipes unchanged | S7 proven pattern | S9 mirrors embedded suite | S10 value per stage |
|---|---|---|---|---|---|---|---|
| A. One milestone after everything | yes | yes | yes | yes | yes | yes | **no** — nothing lands until P1–P3 all land |
| **B. Four stages, Stage 1 now (recommended)** | yes | yes | yes, at stage 4 | yes | yes | yes | **yes** — each stage is mergeable and asserted |
| C. Skip docker; extend the embedded suite, default profile | **no** — H2 stub replaces House Tables | yes | yes | yes | n/a | n/a (it *is* the embedded suite) | partial |
| D. Wire the 3.5 container, assert nothing yet | yes | yes | **no** | yes | n/a | no | weak — an unasserted container rots |
| F. Add view cases to the delta-harness's `HARNESS_REAL_HTS=1` mode | **partly** — real HTS, WebClient, controller and JDBC, but H2-in-MySQL-mode, no HDFS, no shaded jar on a cluster, no Livy | yes | yes | yes | yes | n/a | good, and it costs nothing |

Option C is the default-profile version of the embedded suite, and it does prove the
least: the `@Primary` H2 repository stands in for `HouseTableRepository`, so the view row
never reaches MySQL. Option D is option B with Stage 1's assertions deleted, and the
assertions are what keep the container alive between stages.

**Option F is the one the first draft of this document missed, and it reprices Stage 1.**
The delta-harness's `HARNESS_REAL_HTS=1` mode already buys real HTTP, the generated
WebClient, `UserHouseTablesController` and real JDBC — for zero container, zero image,
zero recipe, zero workflow and zero Python. It is not a substitute for B at Stage 4,
because it cannot reach MySQL-8 semantics, HDFS, the shaded jar on a standalone cluster,
or Livy. But it does mean the marginal value of Stage 1 is those four things and not
"the real service", and Stage 1 should be scoped and staffed on that basis (§4.1).

### 4.1 The stages against the prerequisites they clear

**The stages and the prerequisites are not a one-to-one correspondence, and the numbering
invites the assumption that they are.** Stage 1 clears three prerequisites; P2 splits
across two stages; Stage 4 clears none. Sizing lives in §3's table, so a stage's cost is
read from the prerequisites it clears rather than estimated twice.

| Stage | Clears | What it leaves in the tree | Blocked by |
|---|---|---|---|
| **0. Build the 3.5 image; open a Livy session** | answers P1's only open question | nothing committed — a spike | nothing |
| **1. Spark 3.5 recipe, one table round trip** | P1, P3, P4, P5 | `oh-hadoop-spark-35/`; the `:86` apps-jar and `build.gradle:219` fixes; `integrations/python/spark-itest/` | Stage 0's answer |
| 2. House Tables view rows | P2, first half | `EntityType` on `user_table_row`, `UserViewQuery`, views served through the existing user-table routes | merging `origin/claude/port-696-entity-type-discriminator` and `origin/claude/port-697-hts-view-lifecycle` |
| 3. `ViewsService` and `ViewOperations` | P2, second half | `OpenHouseViewOperations`; `newViewOps` on `OpenHouseInternalCatalog`; `entityType` on the internalcatalog `HouseTable`; a `ViewMetadata` overload on `HouseTableMapper`; a view path in `OpenHouseInternalRepositoryImpl` | Stage 2, and §6's layout decision |
| 4. CREATE / SELECT / SHOW / DROP | nothing; it consumes 1–3 | the §7 assertions, plus `.github/workflows/spark-view-itests.yml` | Stages 1–3 |

**Stage 0 is separated deliberately.** §5.2 already says "before any of the above is worth
writing, build the image and open a Livy session on it" — and then the plan commits to a
recipe, two common service files, an image, a workflow and a Python module anyway. The one
question that can invalidate all five should be asked before they are built. It costs an
afternoon and no committed artifact.

## 5. Stage 1 in detail — a Spark 3.5 recipe that runs one table round trip

Stage 1's deliverable is a recipe that boots and a suite that fails if it stops booting.

**Scope it against what only it can prove.** Per §2, docker's marginal value over the
embedded suite is five things, and Stage 1 reaches two: the shaded 3.5 uber jar loading on
a standalone cluster classpath, and Livy hosting Spark 3.5.2. Both are proved by a single
ordinary-table round trip — create, insert, select — plus a `spark-submit --version`
check. Every views-specific assertion the first draft put in Stage 1 is *already asserted*
by `OpenHouseViewGateOnTestSpark3_5` over a real Tomcat socket, including the disabled-
posture message, so duplicating them in Python buys no coverage and costs a maintained
suite. Views assertions belong in Stage 4, where there is a persisted view to assert
against.

### 5.1 A new recipe, not a modified one

Add `infra/recipes/docker-compose/oh-hadoop-spark-35/`, carrying `docker-compose.yml`,
`cluster.yaml` **and `jobs.yaml`**. The last is easy to miss and the recipe does not work
without it: `oh-hadoop-spark/docker-compose.yml` bind-mounts `./:/var/config/` into four
services (`:8-9, 31-32, 51-52, 67-68`), and `oh-hadoop-spark/jobs.yaml:20` supplies
`jar-path: local:/opt/spark/openhouse-spark-apps_2.12-latest-all.jar`. Without it the
jobs services start with no job configuration. `cluster.yaml` copies from
`oh-hadoop-spark/cluster.yaml` unchanged — the default
`cluster.tables.views.supported-dialects` is already `spark` — with the MySQL service
gaining the P5 `ddl/` mount.

**Build it by `extends` override rather than by duplicating the common files.** That is
already the repo's idiom: `oh-hadoop-spark/docker-compose.yml:115-135` overrides
`container_name` and `depends_on` on top of `extends`, and compose merges an override's
`build` map into the base's. So the new recipe can `extends` the **existing**
`common/spark-services.yml` and override only `build.dockerfile` (three lines × three
services), and `extends` the **existing** `common/hdfs-services.yml` overriding only
`image` (one line × two). No `common/spark-3.5-services.yml` and no
`common/hdfs-3.2-services.yml` need to exist.

That still satisfies M5 the way a separate recipe does — a literal no-diff on the shared
files, which §8 step 4 checks — and it answers the objection an earlier draft raised
against the alternative. "A single interpolated `dockerfile:` field cannot move the HDFS
image version with the Spark version" is true of interpolation *inside* the shared file;
it is not true of a recipe-level `extends` override, which moves both independently.

Point the HDFS override at `bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8` — the tag
the 3.5 dockerfile already builds `FROM` — with the matching `hadoop-datanode` tag if one
is published at that version. Matching the versions **retires** P1's cross-major RPC
concern rather than testing it; nothing in this plan runs a 3.2.1 client against the 2.8
namenode. (And per §5.2, `HADOOP_HOME` is not the client doing the writing anyway.)

One constraint worth writing down: the two recipes bind the same host ports (8000, 8001,
9001–9003, 9870) and use globally-unique `container_name` values (`local.namenode`,
`local.spark-master`, `local.mysql`), so they cannot run at once.

Also in Stage 1, fix the two defects in the never-built dockerfile: point `:86` at
`build/openhouse-spark-apps-1.5_2.12/libs/openhouse-spark-apps-1.5_2.12-uber.jar`, and
add `:apps:openhouse-spark-apps-1.5_2.12:shadowJar` to `dockerPrereqs`
beside `:apps:openhouse-spark-apps_2.12:shadowJar` at `build.gradle:219`, with the
comment block at `:198-201` updated to match.

### 5.2 Livy is the risk — and it is not the risk this plan first named

This is Stage 0. Build the image and open a Livy session before anything in §5.1 is
written.

**The version guard is a non-issue, and an earlier draft of this plan called it the
unbounded unknown.** At the SHA the dockerfile pins
(`4d8a912699683b973eee76d4e91447d769a0cb0d`), `LivySparkUtils.testSparkVersion` has
`MIN_VERSION = (2, 2)` and `MAX_VERSION = (3, 1)`, and above the maximum it **warns**:
`warn(s"Current Spark $v is not verified in Livy, please use it carefully")`. It does not
throw. `defaultSparkScalaVersion` likewise degrades to a warning, and is not reached at
all, because Spark 3.5.2's `spark-submit --version` emits a parseable
`Scala version 2.12.18`. That half of the question is answerable from a URL in thirty
seconds.

**The real risk is in the patch's other pins,** and re-pinning only the four properties
an earlier draft named would leave it in place. `livy_spark3_hadoop3.patch` sets
`json4s.spark-2.12.version=3.6.6` (`:94`), `netty.spark-2.12.version=4.1.47.Final`
(`:87`), `scala-2.12.version=2.12.14` (`:43`) and `hive.version=3.1.2` (`:18`). Spark
3.5.2 ships json4s-jackson **3.7.0-M11**, netty **4.1.96.Final**, Scala **2.12.18**,
hive **2.3.9** and hadoop **3.3.4**. json4s 3.6 → 3.7.0-M11 is binary-incompatible, and
Livy serializes statement results through json4s inside the Spark driver — which is
exactly the `query()`-returns-rows path that M4 and Stage 4 depend on. **Expect json4s to
fail first.**

Note also that `hadoop.version=3.2.1` would introduce a *third* Hadoop version. Spark's
HDFS client is the Hadoop 3.3.4 on `$SPARK_HOME/jars` from the `-bin-hadoop3`
distribution, not the `/opt/hadoop-3.2.1` that `HADOOP_HOME` points at. Any reasoning
about HDFS compatibility that goes through `HADOOP_HOME` is reasoning about the wrong
client.

Three branches, tried in this order:

1. **Livy 0.8.0 builds and runs against 3.5.2 — re-pin the patch.** Fork
   `livy_spark3_hadoop3.patch` into `livy_spark3.5_hadoop3.patch` setting
   `spark.scala-2.12.version=3.5.2`, the matching `spark.bin.download.url` /
   `spark.bin.name`, **and** `json4s.spark-2.12.version`, `netty.spark-2.12.version`,
   `scala-2.12.version` and `hive.version` to Spark 3.5.2's values. Reference it from the
   3.5 dockerfile only. This is the plan.
2. **Livy tops out at a Spark ≥ 3.5 — pin Spark to that version.** Iceberg's Spark
   runtimes are per-minor and not cross-compatible, and the client compiles against
   `iceberg-spark-runtime-3.5_2.12`, so the floor is **3.5**, not 3.4. Below it, branch 3.
3. **Livy cannot host any Spark 3.5.x — drive SQL without Livy.**
   `docker exec local.spark-master /opt/spark/bin/spark-sql --master spark://spark-master:7077 ...`
   from the test container, parsing stdout. This still satisfies M2, because a non-zero
   exit fails the step; it satisfies M4 only if stdout parsing yields rows the assertions
   can compare. It loses the row-shaped `query()` return that makes those assertions
   clean, which is why it is third.

### 5.3 Session configuration

The 3.5 Livy session differs from `integration_tests.py:39-50` in exactly three entries:

```python
"spark.jars.packages": "com.linkedin.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.2.17",
"spark.sql.catalog.openhouse.iceberg-views-enabled": "true",   # gate-on sessions only
# spark.jars, uri, cluster, catalog-impl, extensions: unchanged
```

The `com.linkedin.iceberg` coordinate rather than `org.apache.iceberg`: the 3.5 runtime
module compiles against `com.linkedin.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.2.17`
(`integrations/spark/spark-3.5/openhouse-spark-runtime/build.gradle:68,82`) and does not
bundle it — `fatJarPackagedDependencies` carries only the java-runtime shadow jar
(`:79-81`) — so the session must supply the same fork build the client was compiled
against. The build already compiles against that coordinate and declares Maven Central
among its project repositories (`build.gradle:71-79`; `settings.gradle:6-14` is
`pluginManagement`, a different block), so a Livy session resolving from Central gets the
same fork build. The coordinate resolves 200 from repo1.

The gate goes in the session `conf` and nowhere else (M6). Stage 1's suite opens two
sessions, one with the gate and one without, and asserts the same observables from both.

### 5.4 What Stage 1 asserts

A new `integrations/python/spark-itest/` module — `tests/view_gate_tests.py`, a
`tests/Dockerfile`, and a `Makefile` with an `integration-tests` target modeled on
`integrations/python/dataloader/Makefile:44-58`. It carries its own `LivySession`
(per W11) with the raising `_run` and the row-returning `query`.

**Name the recipe directory `oh-hadoop-spark-35`, not `oh-hadoop-spark-3.5`.** Compose
derives the project name from the directory and normalizes it by lowercasing and
stripping anything outside `[a-z0-9_-]` — the `.` is removed. A Makefile defaulting
`DOCKER_NETWORK ?= oh-hadoop-spark-3.5_default` would fail on the first
`make integration-tests` with `network not found`, in the harness whose whole purpose
(P4) is that failures are legible. Either drop the dot or declare an explicit top-level
`name:` in the compose file and reference it from the Makefile.

Stage 1 asserts two things, and only two:

| Assertion | Statement or request | Expected | Why it matters |
|---|---|---|---|
| Version | `spark-submit --version` on the master, and a Livy session that opens | Spark 3.5.2, session reaches `idle` | The Stage 0 answer, pinned so a regression is visible |
| Table round trip | `CREATE TABLE openhouse.d_gate.t_base (id BIGINT, name STRING)`, `INSERT`, `SELECT ... ORDER BY id` | the inserted rows | Proves the 3.5 image, the 3.5 uber jar on a standalone cluster classpath, and Spark 3.5 reading and writing the container's HDFS |

**Why this table is short.** An earlier draft of this plan put five more rows here — the
gate-on fall-through, `SHOW VIEWS`, `CREATE VIEW`, and two `curl` checks. Every one of
the Spark-side rows is *already asserted* by `OpenHouseViewGateOnTestSpark3_5` against a
real embedded Tomcat, including the one the draft called "the only one that proves a view
call crossed a network" — `testGateOnSelectFromRealTableFallsThroughToTable` proves
exactly that today, and `testGateOnProgrammaticViewCatalogAnswersOverTheWire` proves it
more strongly by pinning the server's message against the client-local one. Duplicating
them in Python adds no coverage and adds a suite to maintain.

The two `curl` rows were also simply wrong: they issued unauthenticated requests to
authenticated routes. `TablesMvcConfigurer` registers the token interceptor on `/**`
excluding only `/actuator/**`, api-docs, swagger-ui, favicon and `/error`. `/v1/config`
is not excluded, so both would have received `401` with an empty body — the envelope row
failing on absence, which is the least diagnosable failure shape there is. The sibling
plan records the correct answer for the identical command
(`views-docker-rest-tests-plan.md`: `GET /v1/config` with no header → `401`). Any such
check must send `Authorization: Bearer $(cat …/dummy.token)`, and belongs in the
black-box suite against `oh-only` rather than here.

(For the record, `IMPLEMENTED_ENDPOINTS` is seven *endpoints* — `GET /v1/config` plus six
view routes — not "seven view routes".)

Views assertions arrive in Stage 4, against a view that exists.

## 6. The view-metadata location convention — decide it before Stage 3

**Recommendation: write view metadata to `<view-location>/%05d-<uuid>.metadata.json` —
root directory, uncompressed — matching the OpenHouse table convention. Extend
`BaseViewOperations` and choose the path inside `doCommit`.**

Stock Iceberg writes view metadata to `<location>/metadata/00001-<uuid>.metadata.json.gz`.
OpenHouse tables deliberately do not: `OpenHouseInternalTableOperations.rootMetadataFileLocation`
(`:191-201`) formats `%s/%05d-%s%s` from `metadata.location()`, with the extension coming
from the `write.metadata.compression-codec` property, whose default is uncompressed. The
javadoc at `:175-190` states the layout as a decision: the root directory holds metadata
JSON, `./metadata` holds manifests, `./data` holds data files.

The mechanism is cheaper than it first looks. In `iceberg-core-1.5.2.17`,
`BaseViewOperations`' `newMetadataFilePath`, `metadataFileLocation`, `writeNewMetadata`
and `METADATA_FOLDER_NAME` are all **private** (`javap -p`), which is why the
`metadata/…json.gz` layout cannot be configured away. But `commit(ViewMetadata,
ViewMetadata)` does not write anything: disassembly shows it validating, calling
`doCommit(base, metadata)`, then `requestRefresh()`. The private path helper is reached
only from the **protected** `writeNewMetadataIfRequired`, which an implementation calls
from its own `doCommit`. So an `OpenHouseViewOperations extends BaseViewOperations` that
writes the file itself in `doCommit` — `ViewMetadataParser.write(metadata,
io().newOutputFile(rootMetadataFileLocation(...)))`, mirroring
`OpenHouseInternalTableOperations.doCommit` (`:253`, `:262`, `:356-370`) — never touches
the private helper and needs no `commit` override for this reason. It keeps
`current()`, `refresh()`, `refreshFromMetadataLocation(...)` and the refresh-suppression
flags for free.

Three reasons to match tables rather than accept Iceberg's default:

1. **One layout under `/data/openhouse`.** Operators, the retention and orphan-file
   jobs, and stage 4's own `hdfs dfs -ls` assertion all reason about one shape. A
   second, gzipped, subdirectory layout is a permanent second case for every such tool,
   bought for the saving of one small helper method.
2. **Nothing external reads these files by path.** The metadata location is always
   handed out explicitly — `metadata_location` in the House Tables row, `metadata-location`
   in `LoadViewResult` — exactly as for tables. The `metadata/` convention buys
   discoverability that nothing in this system uses.
3. **Iceberg's own parser cooperates.** `ViewMetadataParser.internalWrite` and `read`
   select gzip from the file name via `TableMetadataParser.Codec`, so a plain
   `.metadata.json` name round-trips through the reference implementation with no fork.
   And `BaseViewOperations.parseVersion` reads the leading `%05d-` prefix, which the
   OpenHouse table naming already uses — so version parsing keeps working. Only the
   folder and the compression differ, and both are ours to choose.

The competing option — call `writeNewMetadataIfRequired` from `doCommit` and accept
`<location>/metadata/…json.gz` — saves one small private method and is defensible if the
owner would rather have views match upstream Iceberg than match OpenHouse tables. Reject
it: the saving is a single `String.format`, and the layout divergence under
`/data/openhouse` is permanent. Note separately that `commit` will still likely need an
override for the staged-create refresh behavior that
`OpenHouseInternalTableOperations.commit` (`:212-222`) handles for tables — that is an
independent question from the path, and settling the path first keeps them independent.

## 7. Stage 4 in detail — the view DDL suite

Runs only once stages 1–3 have landed. One Livy session with the gate on, one control
session without it, and a direct MySQL query. Every SQL statement below is asserted on
its returned rows.

```sql
CREATE TABLE openhouse.d_views.t_base (id BIGINT, name STRING, score DOUBLE);
INSERT INTO openhouse.d_views.t_base VALUES (1,'alice',3.5),(2,'bob',1.5),(3,'carol',4.0);
CREATE VIEW openhouse.d_views.v_high AS
  SELECT id, name FROM openhouse.d_views.t_base WHERE score > 2.0;
SELECT id, name FROM openhouse.d_views.v_high ORDER BY id;   -- [[1,'alice'],[3,'carol']]
SHOW VIEWS IN openhouse.d_views;                             -- contains v_high
DESCRIBE openhouse.d_views.v_high;                           -- id BIGINT, name STRING
DROP VIEW openhouse.d_views.v_high;
SHOW VIEWS IN openhouse.d_views;                             -- does not contain v_high
```

Around that Spark flow sit the assertions that only the docker configuration can make.
The row that matters is the MySQL one: it is the single assertion that no
`OpenHouseSparkITest` can make, and therefore the whole reason this suite runs in
docker.

| Assertion | How | Expected |
|---|---|---|
| **The view row reached MySQL** | `docker exec local.mysql mysql -uoh_user -poh_password oh_db -e "SELECT entity_type, metadata_location FROM user_table_row WHERE database_id='d_views' AND table_id='v_high'"` (credentials from `common/mysql-services.yml`) | exactly one row, `entity_type = 'VIEW'` |
| The metadata file exists where §6 says | `docker exec local.namenode hdfs dfs -ls /data/openhouse/d_views/v_high-*/` | one `00001-<uuid>.metadata.json`, no `metadata/` subdirectory |
| The REST surface serves it | `GET http://openhouse-tables:8080/v1/namespaces/d_views/views/v_high` | 200 `LoadViewResult` whose `metadata-location` matches the file above and whose `versions[0].representations[0].sql` is the `SELECT` |
| It outlives the session (S8) | create in session A, close A, open session B, `SELECT ... FROM v_high` | the same two rows |
| Drop is real | after `DROP VIEW`, repeat the REST GET and the MySQL query | `404 NoSuchViewException`; zero rows |
| The gate still gates (M6) | `SELECT ... FROM openhouse.d_views.v_high` in the control session | statement error — a view is invisible to a gate-off session |

CI: a new `.github/workflows/spark-view-itests.yml` modeled on
`dataloader-tests.yml` (not on `build-run-tests.yml`, whose docker step brings up
`oh-only` — no Spark, no HDFS), path-filtered to `integrations/python/spark-itest/**`,
`integrations/spark/spark-3.5/**` and `infra/recipes/docker-compose/**`. It runs
`./gradlew dockerPrereqs`, brings up `oh-hadoop-spark-35`, and polls
`localhost:8000/actuator/health` for `"status":"UP"` and `localhost:9003/sessions` for
up to 600s.

`/actuator/health` rather than `/v1/config`: it is token-exempt, it is already the k8s
liveness and readiness probe, and it is what the sibling black-box plan settled on, so
the branch keeps one readiness convention rather than two. An earlier draft chose
`/v1/config` "because it is the route the views client bootstraps on" while copying
`dataloader-tests.yml`'s `[ "$status" != "000" ]` predicate — under which any response
counts as ready, including the `401` that `/v1/config` actually returns to an
unauthenticated poll. The chosen route carried no more information than the one it
replaced.

## 8. Verification — running this from a clean tree

Stage 1, which is what a reviewer can check today once it is built:

```bash
git clone <repo> && cd openhouse
./gradlew dockerPrereqs                       # builds the 3.5 runtime and apps uber jars
docker compose -f infra/recipes/docker-compose/oh-hadoop-spark-35/docker-compose.yml \
  up -d --build
# wait for readiness (the CI poll, run by hand):
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8000/v1/config   # 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9003/sessions    # 200
cd integrations/python/spark-itest
make integration-tests \
  TOKEN_FILE=../../../tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/resources/dummy.token
docker compose -f ../../../infra/recipes/docker-compose/oh-hadoop-spark-35/docker-compose.yml down
```

Independent checks a reviewer should make, in this order, because each one falsifies
the next cheaply:

1. `docker compose ... build spark-livy` succeeds and
   `docker exec local.spark-master /opt/spark/bin/spark-submit --version` reports 3.5.2.
   If this fails, §5.2 is where the work is.
2. `curl -s -XPOST http://localhost:9003/sessions -H 'Content-Type: application/json' -d '{"kind":"sql","conf":{...}}'`
   reaches state `idle`. This is the Livy-hosts-3.5.2 question, answered.
3. The suite fails when it should: temporarily point `spark.sql.catalog.openhouse.uri`
   at a dead port and confirm the run goes red rather than green-with-printed-errors.
   This is the check that `livy_cli.py` would not survive (P4), so it is the one that
   proves the harness is a harness.
4. `git diff --stat` shows no change to `infra/recipes/docker-compose/common/spark-services.yml`,
   `common/hdfs-services.yml`, or any of the four existing recipes (M5).
5. `./gradlew :integrations:spark:spark-3.5:openhouse-spark-3.5-itest:test` still passes,
   including `OpenHouseViewGateOnTestSpark3_5` — the embedded assertions the docker
   suite mirrors.

Stage 4 adds one more: run the suite twice in a row without tearing down the stack. The
second run must pass, which it only does if `DROP VIEW` truly removed the House Tables
row and the metadata file.

## Appendix A. Staging alternatives, developed

**A. One milestone after everything.** Wait for P1–P3, then build the suite once. It
satisfies every "must" and is the least total work. It fails S10, and the failure is
concrete rather than aesthetic: P1's Livy question is answerable today, against an image nothing else depends on yet,
and unanswerable-without-cost on the day the persistence milestone declares itself done
and its integration test will not run. The right time to learn that Livy 0.8.0 cannot
host Spark 3.5.2 is before three other stages are sequenced behind that assumption.

**C. Skip docker entirely.** Add view DDL cases to the existing
`integrations/spark/spark-3.5/openhouse-spark-itest` suite once persistence lands. This
is genuinely attractive — the suite exists, it runs Spark 3.5 and Iceberg 1.5 today,
`OpenHouseViewGateOnTestSpark3_5` already drives real SQL through it, and it needs no
container work at all. It fails M1 for one reason, and the reason is decisive: the
`@Primary` H2 repository replaces `HouseTableRepository` in that configuration, so
`CREATE VIEW` would write a view row into an in-memory map and the test would prove
nothing about the service that actually stores views. It is the right place for a
second copy of the assertions (S9), not the only place.

**D. Wire the container, assert nothing yet.** Build the 3.5 recipe, leave the suite for
stage 4. Cheaper than option B by exactly the assertions in §5.4 — which are the only
thing that would notice the recipe breaking while stages 2 and 3 are in flight. An
unasserted recipe is a recipe that has to be debugged twice.

**E. Flip `oh-hadoop-spark` to Spark 3.5 in place.** Considered and rejected inside
option B. It would break `SETUP.md:290-299`'s spark-shell instructions, the
`iceberg-spark-runtime-3.1_2.12:1.2.0` pin in
`integrations/python/dataloader/tests/integration_tests.py:41`, and
`dataloader-tests.yml`'s green run — a regression in a working path, traded for saving
about 220 lines of compose.

## Appendix B. Definitions

- **Disabled posture** — the designed default-off state: the client gate is off, or the
  server answers every view route with `404 "Views are disabled"`, and either way
  Spark's `ResolveViews` falls through to `loadTable` so table behavior is unchanged.
  Defined in views-client-plugin-plan.md §5.3.
- **The client gate** — `spark.sql.catalog.openhouse.iceberg-views-enabled`, default
  `false`, read once in `OpenHouseCatalog.initialize`.
- **`dockerPrereqs`** — `build.gradle:204-246`, the task that stages every jar the
  dockerfiles `COPY` into `build/<project-name>/libs/`.
