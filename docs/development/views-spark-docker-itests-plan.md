# Build the Spark 3.5 docker container first; the view DDL suite is the last of four stages

"Spark itests on docker using the DDL for views and querying" should not be planned or
staffed as a test milestone. Four things must be true before a single `CREATE VIEW`
can execute against a docker-deployed OpenHouse, and two of them are engineering
milestones that dwarf the suite itself. The recommendation is to split the work into
four stages, ship the first one now, and let the suite arrive last:

1. **Stage 1 — a Spark 3.5 / Iceberg 1.5 recipe that runs, asserting the views-disabled
   posture in docker.** Buildable today. This is the stage that has been mistaken for
   free.
2. **Stage 2 — House Tables view rows.** Built already, on two branches that are not
   ancestors of this one.
3. **Stage 3 — a real `ViewsService` backed by a `ViewOperations`.** Net-new. The
   largest single item in the plan.
4. **Stage 4 — the CREATE / SELECT / SHOW / DROP suite.** A few hundred lines of Python
   once stages 1–3 exist.

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

11. Won't extract a shared Livy harness across the dataloader suite and this one. The
    dataloader package is a published artifact; coupling its test module to a new one
    to save sixty lines trades a green CI path for tidiness. Stage 4 carries its own
    copy; de-duplication is a later, optional cleanup.
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
17. View-level authorization and OPA policy for views.
18. `rename-view`, which the server deliberately does not serve.

## 2. Why docker at all, when a Spark 3.5 suite already exists

Every existing Spark integration test bypasses the real House Tables Service.
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

The `oh-hadoop-spark` recipe is the only configuration that does. Its tables service
talks to `http://openhouse-housetables:8080`, which talks to
`jdbc:mysql://mysql:3306/oh_db` (`infra/recipes/docker-compose/oh-hadoop-spark/cluster.yaml`).
It also supplies three things the embedded suite cannot: real HDFS at
`hdfs://namenode:9000/data/openhouse` instead of a temp directory, a real standalone
Spark cluster running the shaded uber jar exactly as shipped, and a cross-process REST
bootstrap where the client's `GET /v1/config` crosses a network rather than a
`MockMvc` dispatch. For views specifically, the third one matters most: the whole
client plan rests on a stock `RESTCatalog` bootstrapping against a real socket.

## 3. What has to be true first

Four prerequisites, sized. The table is the document's core finding; the row that
matters is P1, because it is the one that is routinely assumed to be a compose-file
edit and is not.

| # | What is missing | Kind | Size | Without it |
|---|---|---|---|---|
| **P1** | A Spark 3.5 / Iceberg 1.5 container that actually boots | **milestone** — mostly plumbing, with one unbounded unknown (Livy) | days if Livy 0.8 hosts Spark 3.5.2; open-ended if it does not | View DDL cannot execute in docker at all, whatever the server does |
| **P2** | A server that can persist a view — House Tables rows *and* a `ViewsService` | **milestone** — the House Tables half is built but off-branch; the `ViewsService`/`ViewOperations` half is net-new | the largest item in the plan | `CREATE VIEW` returns 404 "Views are disabled" |
| P3 | A way to turn views on for Spark in docker | plumbing | hours | The client gate stays off; no view call crosses the wire |
| P4 | A docker assertion harness that fails on failure | plumbing | hours | Nothing that runs today can fail a build |

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

Options are staging strategies; columns are the requirements from §1. **Stage the work
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
| C. Skip docker; extend the embedded suite | **no** — H2 stub replaces House Tables | yes | yes | yes | n/a | n/a (it *is* the embedded suite) | partial |
| D. Wire the 3.5 container, assert nothing yet | yes | yes | **no** | yes | n/a | no | weak — an unasserted container rots |

Option C deserves the explicit rejection: it is the cheapest path to a green
`CREATE VIEW` test and it proves the least, because `@ConditionalOnProperty(name =
"openhouse.htsStub.enabled", matchIfMissing = true)` means the view row never reaches
MySQL. Option D is option B with stage 1's assertions deleted; the assertions are what
keep the container alive between stages.

## 5. Stage 1 in detail — a Spark 3.5 recipe, asserting the disabled posture

Stage 1's deliverable is a recipe that boots and a suite that fails if it stops booting.
It proves the container, the 3.5 uber jar, the catalog configuration, the client gate
and the Livy harness end to end against the surface that already works — the
views-disabled posture — before any of it can be blamed on the persistence milestone.

### 5.1 A new recipe, not a modified one

Add `infra/recipes/docker-compose/oh-hadoop-spark-3.5/` (`docker-compose.yml` +
`cluster.yaml`, the latter copied from `oh-hadoop-spark/cluster.yaml` unchanged — the
default `cluster.tables.views.supported-dialects` is already `spark`), plus
`common/spark-3.5-services.yml` defining the three Spark services against
`spark-3.5-base-hadoop3.2.dockerfile`, plus `common/hdfs-3.2-services.yml` running
`bde2020/hadoop-{name,data}node:2.0.0-hadoop3.2.1-java8` to match the 3.5 image's
client.

A separate recipe rather than an environment-variable toggle inside
`common/spark-services.yml`: M5 asks that the existing four recipes be unchanged, and a
literal no-diff on that file is the strongest available evidence. It also lets the HDFS
image version move with the Spark version, which a single interpolated `dockerfile:`
field cannot. The cost is roughly 130 duplicated lines of `extends:` stanzas, and one
constraint worth writing down: the two recipes bind the same host ports (8000, 8001,
9001–9003, 9870) and cannot run at once.

Also in Stage 1, fix the two defects in the never-built dockerfile: point `:86` at
`build/openhouse-spark-apps-1.5_2.12/libs/openhouse-spark-apps-1.5_2.12-uber.jar`, and
add `:apps:openhouse-spark-apps-1.5_2.12:shadowJar` to `dockerPrereqs`
beside `:apps:openhouse-spark-apps_2.12:shadowJar` at `build.gradle:219`, with the
comment block at `:198-201` updated to match.

### 5.2 Livy is the risk; try it first

Before any of the above is worth writing, build the image and open a Livy session on it.
The patch applied at `spark-3.5-base-hadoop3.2.dockerfile:37-46` compiles Livy against
Spark 3.1.1. Two outcomes, decided in this order:

1. **Re-pin the patch.** Fork `livy_spark3_hadoop3.patch` into
   `livy_spark3.5_hadoop3.patch` setting `spark.scala-2.12.version=3.5.2`,
   `hadoop.version=3.2.1`, and the matching `spark.bin.download.url` /
   `spark.bin.name`, and reference it from the 3.5 dockerfile only. Re-verify Livy's
   own Spark-version guard and its repl against 3.5.2.
2. **Fallback if Livy 0.8.0 cannot host Spark 3.5.2:** drive SQL with
   `docker exec local.spark-master /opt/spark/bin/spark-sql --master spark://spark-master:7077 ...`
   from the test container and parse stdout. This loses the row-shaped `query()` return
   that makes M4's assertions clean, so it is the fallback and not the plan. Pinning
   Spark down to the newest version Livy accepts is acceptable only if that version is
   ≥ 3.4 — below that the Iceberg 1.5 Spark runtime does not apply.

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
against. The artifact resolves from Maven Central, which is the only repository the
build declares (`settings.gradle:6-14`).

The gate goes in the session `conf` and nowhere else (M6). Stage 1's suite opens two
sessions, one with the gate and one without, and asserts the same observables from both.

### 5.4 What Stage 1 asserts

A new `integrations/python/spark-itest/` module — `tests/view_gate_tests.py`, a
`tests/Dockerfile`, and a `Makefile` with an `integration-tests` target modeled on
`integrations/python/dataloader/Makefile:44-58` with
`DOCKER_NETWORK ?= oh-hadoop-spark-3.5_default`. It carries its own `LivySession`
(per W11) with the raising `_run` and the row-returning `query`.

| Assertion | Statement or request | Expected | Why it matters |
|---|---|---|---|
| Table path still works | `CREATE TABLE openhouse.d_gate.t_base (id BIGINT, name STRING)`, `INSERT`, `SELECT ... ORDER BY id` | the inserted rows | Proves the 3.5 image, the uber jar and HDFS 3.2↔2.8 all work before views enter the picture |
| Fall-through over the wire | `SELECT id, name FROM openhouse.d_gate.t_base` in the **gate-on** session | same rows | `SparkCatalog` probes `loadView` first; the 404 must not break table reads |
| `SHOW VIEWS` | `SHOW VIEWS IN openhouse.d_gate` | empty list, no error | The plugin's list-route 404 catch, over a real socket |
| `CREATE VIEW` | `CREATE VIEW openhouse.d_gate.v_x AS SELECT id FROM openhouse.d_gate.t_base` | statement error whose `evalue` names an `AnalysisException` | The create-route 404 normalizing, not leaking |
| Bootstrap | `GET http://openhouse-tables:8080/v1/config` | 200, body lists the seven view routes | The one route without which a stock `RESTCatalog` cannot start |
| Disabled envelope | `GET http://openhouse-tables:8080/v1/namespaces/d_gate/views/v_x` | `404` `{"error":{"message":"Views are disabled","type":"NoSuchViewException","code":404}}` | Pins the server's fixed message, so stage 4's success case is provably a change |
| Gate-off control | all of the above in a session with no gate entry | identical observables | M6, and the posture the client plan §5.3 designed |

The row that matters is the second: it is the only one that proves a view call crossed
a network. It is also exactly what `OpenHouseViewGateOnTestSpark3_5` asserts against the
embedded service, which is the point — the same observable pinned in both places is how
a fixture/real-service divergence becomes visible (S9).

The two `curl` rows deliberately overlap with the black-box suite proposed in
views-docker-rest-tests-plan.md, and stay thin here on purpose: exhaustive envelope,
`HEAD`, and unresolved-`/v1`-path coverage belongs there, against `oh-only`. These two
exist only to localize a Stage 1 failure — if the Spark session cannot resolve a view
and these both pass, the fault is in the container or the catalog configuration, not in
the server.

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

## 7. Stage 4 in detail — the suite the owner asked for

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
`./gradlew dockerPrereqs`, brings up `oh-hadoop-spark-3.5`, and polls
`localhost:8000/v1/config` and `localhost:9003/sessions` for up to 600s — `/v1/config`
rather than `/v1/databases`, because it is the route the views client bootstraps on and
therefore the readiness signal that matters here.

## 8. Verification — running this from a clean tree

Stage 1, which is what a reviewer can check today once it is built:

```bash
git clone <repo> && cd openhouse
./gradlew dockerPrereqs                       # builds the 3.5 runtime and apps uber jars
docker compose -f infra/recipes/docker-compose/oh-hadoop-spark-3.5/docker-compose.yml \
  up -d --build
# wait for readiness (the CI poll, run by hand):
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8000/v1/config   # 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9003/sessions    # 200
cd integrations/python/spark-itest
make integration-tests \
  TOKEN_FILE=../../../tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/resources/dummy.token
docker compose -f ../../../infra/recipes/docker-compose/oh-hadoop-spark-3.5/docker-compose.yml down
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
concrete rather than aesthetic: P1's Livy question is answerable in an afternoon today,
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
thing that would notice the recipe breaking during the months stages 2 and 3 take. An
unasserted recipe is a recipe that has to be debugged twice.

**E. Flip `oh-hadoop-spark` to Spark 3.5 in place.** Considered and rejected inside
option B. It would break `SETUP.md:290-299`'s spark-shell instructions, the
`iceberg-spark-runtime-3.1_2.12:1.2.0` pin in
`integrations/python/dataloader/tests/integration_tests.py:41`, and
`dataloader-tests.yml`'s green run — a regression in a working path, traded for saving
about 130 lines of compose.

## Appendix B. Definitions

- **Disabled posture** — the designed default-off state: the client gate is off, or the
  server answers every view route with `404 "Views are disabled"`, and either way
  Spark's `ResolveViews` falls through to `loadTable` so table behavior is unchanged.
  Defined in views-client-plugin-plan.md §5.3.
- **The client gate** — `spark.sql.catalog.openhouse.iceberg-views-enabled`, default
  `false`, read once in `OpenHouseCatalog.initialize`.
- **`dockerPrereqs`** — `build.gradle:204-246`, the task that stages every jar the
  dockerfiles `COPY` into `build/<project-name>/libs/`.
