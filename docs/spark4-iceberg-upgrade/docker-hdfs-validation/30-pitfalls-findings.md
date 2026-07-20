# Pitfalls & findings — docker/HDFS validation

Technical facts only. Format: `Dn — symptom -> root cause -> fix/disposition`.

## Docker-setup fixes (compose/version drift)

- **D1 — Spark service built the wrong engine.**
  `common/spark-services.yml` pointed all three spark services at
  `spark/spark-base-hadoop2.8.dockerfile` (Spark **3.1.1** + Hadoop **2.7/2.8**,
  copying the old `openhouse-spark-runtime_2.12` jar). The 1.10-fork lane targets
  Spark 3.5. The branch had already added `spark/spark-3.5-base-hadoop3.2.dockerfile`
  (Spark **3.5.2**, Hadoop 3.2.1 base, copies `openhouse-spark-3.5-runtime_2.12-uber.jar`)
  but never wired it in. **Fix:** repointed all three services in `spark-services.yml`
  to the 3.5 dockerfile.

- **D2 — HDFS cluster was Hadoop 2.8, not 3.2.**
  `common/hdfs-services.yml` ran `bde2020/hadoop-{namenode,datanode}:1.2.0-hadoop2.8-java8`.
  The validation requires the 3.3.4 client vs a **Hadoop 3.2** HDFS (wire-compat leg of
  `F-HADOOP1`/`C5`), and the Spark 3.5 image's base FROM is the hadoop-3.2.1 namenode.
  **Fix:** bumped both images to `2.0.0-hadoop3.2.1-java8`.

- **D3 — empty `hadoop/hadoop.env`.**
  The recipe's `hadoop.env` was empty, so the single-datanode cluster inherited
  Hadoop's default `dfs.replication=3` (every file perpetually under-replicated) and
  had no explicit `fs.defaultFS`. **Fix:** set `HDFS_CONF_dfs_replication=1` (healthy
  single-datanode default; also makes the delete-file-replication contrast crisp) and
  `CORE_CONF_fs_defaultFS=hdfs://namenode:9000`.

## Build-environment fixes (host toolchain, not repo drift)

- **D4 — Gradle wrapper download blocked.** `gradlew` pins gradle 7.6.2 and downloads
  from `services.gradle.org` -> `github.com/gradle/gradle-distributions` which the
  session egress proxy denies (HTTP 403). The cached dist under
  `~/.gradle/wrapper/dists/gradle-7.6.2-bin/` was an empty/partial dir. **Workaround:**
  used the pre-installed system Gradle **8.14.3** (`/opt/gradle`) instead of the wrapper.
  Build configures cleanly (only gradle-9 deprecation warnings). No repo change.

- **D5 — `CopyGitHooksTask` fails in a git worktree.** `build.gradle` did
  `into file('.git/hooks/')`; in a linked worktree `.git` is a *file*
  (`gitdir: <path>`), so Gradle 8 fails Copy-task validation ("ancestor '.git' is not a
  directory"). **Fix (committed):** resolve the real gitdir — if `.git` is a file, parse
  the `gitdir:` pointer and target `<gitdir>/hooks`; else `<.git>/hooks`; guard with
  `onlyIf { gitPath.exists() }`. This is a genuine repo bug for anyone building inside a
  worktree, independent of this validation.

- **D6 — Lombok vs JDK 21.** Default `JAVA_HOME` is JDK **21**; project uses Lombok
  **1.18.20**, which throws `NoSuchFieldError JCTree$JCImport.qualid` on JDK 21 (field
  removed in 21). **Workaround:** build with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`
  (JDK 17, on which 1.18.20 works). No 11 JDK is installed; javac 17 emits the target-8
  bytecode the modules request. No repo change.

- **D7 — shadow plugin can't parse Java-21 multi-release classes (1.10 fallout).**
  `:apps:openhouse-spark-apps_2.12:shadowJar` failed with `Unsupported class file
  major version 65`. Root cause: the Iceberg 1.10 bump pulls `parquet-jackson 1.16` ->
  `jackson-core 2.19.2`, which ships `META-INF/versions/{19,20,21}/*.class` (Java 19–21,
  major 63–65) multi-release variants; the pinned shadow plugin `7.1.2` bundles ASM 9.2,
  which reads only up to major 62 (Java 18). The convention already excluded
  `META-INF/versions/19/`; jackson 2.19 added 20/21. **Fix (committed):** extended the
  exclude in `buildSrc/.../openhouse.apps-spark-common.gradle` to also drop
  `META-INF/versions/20/` and `/21/`. The base classes suffice at the Java 8/11 runtime.
  Remove once the shadow plugin (hence ASM) is upgraded. Only the apps shadowJar is
  affected (the spark-3.5-runtime shadowJar does not bundle parquet-jackson).

- **D8 — docker image build fails HTTPS behind the egress proxy.**
  The session's egress proxy terminates TLS at the network level; containers reach the
  internet transparently but do not trust the proxy CA, so the Spark image builder stage
  (curl Spark tgz, `git clone` Livy, Maven build of Livy) fails with "self-signed
  certificate in certificate chain". Containers cannot use the host proxy at
  `127.0.0.1:34695` (loopback is container-local). **Fix:** the builder stage now trusts
  any CA staged under `common/spark/extra-ca-certs/` (system store via
  `update-ca-certificates` + JDK `cacerts` via `keytool`, guarded so it is a no-op when
  no CA is present). The dir is committed with a README + `.gitignore` (`*.crt`); the
  actual `proxy-ca.crt` (copied from `/root/.ccr/ca-bundle.crt`) is staged per-environment
  and NOT committed. With CA trust, github + Maven Central + archive.apache.org are all
  reachable and policy-allowed from a container (verified). This is an
  environment-specific build accommodation, not repo drift.

## Findings (HDFS behavior)

(appended as evidence is gathered — see 20-progress.md)

- **D9 — Spark image ran Java 8; Iceberg 1.10 fork needs Java 11.**
  First `spark-sql` attempt threw `UnsupportedClassVersionError: org/apache/iceberg/spark/ExtendedParser
  ... class file version 55.0, this version of the Java Runtime only recognizes up to 52.0`.
  The Spark image's final stage was `FROM bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8`
  (Java 8 = class 52), but the fork jars are compiled to class 55 (Java 11). Debian-9/stretch
  (glibc 2.24) in that base cannot install JDK 11 (EOL apt repos; and a modern temurin JDK's
  glibc is too new to copy in). **Fix (committed):** rebase the final stage on
  `eclipse-temurin:11-jdk-jammy` (Spark 3.5 supports Java 11; Spark bundles its own Hadoop 3.3.4
  client, so the bde2020 Hadoop base is not needed — HDFS is reached via fully-qualified
  `hdfs://` URIs). HDFS namenode/datanode services still use the bde2020 hadoop-3.2.1 images.

## Findings (HDFS behavior)

- **F-DEFAULTFS — Spark needs `fs.defaultFS=hdfs://namenode:9000` or data writes go to local disk.**
  The OpenHouse catalog returns table locations as **scheme-less** paths (`/data/openhouse/...`).
  With Spark's default `fs.defaultFS=file:///`, the first INSERT failed:
  `Mkdirs failed to create /data/openhouse/.../data (exists=false, cwd=file:/opt/spark)` —
  Spark tried to write ORC data files to the container's LOCAL fs. Setting
  `--conf spark.hadoop.fs.defaultFS=hdfs://namenode:9000` routes the scheme-less path to HDFS.
  (The server-side metadata write is unaffected because the tables service's Hadoop conf points
  at HDFS.) This is exactly the leg the LocalFileSystem harness could not exercise
  (F-VACUITY-HADOOP): with `fs.defaultFS=file:///` in the harness the same scheme-less path
  silently resolves to local disk and "works", masking the need for HDFS defaultFS.

- **F-REPL — the delete-file replication custom behavior does NOT take effect through the
  OpenHouse Spark 3.5 + fork 1.10.0-openhouse stack on HDFS.**
  Evidence (Claim 2 table in 20-progress.md): MoR position-delete files always take the standard
  `dfs.replication`, regardless of `spark.sql.iceberg.delete-file-replication` (session conf via
  `SET`), the `delete-file-replication` write option, or the `write.delete-file-replication`
  table property (which round-trips through the catalog). Generic HDFS replication control works
  (data files honored `dfs.replication=2`), so the gap is specific to the delete-file knob.
  Jar-level analysis of `iceberg-spark-runtime-3.5_2.12-1.10.0-openhouse.jar` (the fork artifact
  under test) shows the plumbing classes are all PRESENT and individually correct:
  `SparkWriteConf.deleteFileReplication()` resolves session-conf/table-prop/write-option with
  default 3; `SparkPositionDeltaWrite$PositionDeltaWriteFactory` builds a delete-file
  `OutputFileFactory` with `.suffix(...).replicationFactor(...)`; `OutputFileFactory.newOutputFile()`
  passes `FILE_REPLICATION_FACTOR` via `FileIO.newOutputFile(String, Map)` when a factor is present;
  the default catalog `FileIO` is `HadoopFileIO`, whose `newOutputFile(String, Map)` overload calls
  `HadoopOutputFile.fromPath(path, conf, properties)` (which reads `FILE_REPLICATION_FACTOR` and
  passes it to `fs.create(..., replication, ...)`). Yet at runtime the delete file is created via
  the **no-map** path (`newOutputFile(String)` -> replication `-1` -> FS default), i.e. the
  `OutputFileFactory` actually used for the delete write has an EMPTY `replicationFactor`. The
  precise break between `PositionDeltaWriteFactory` (which sets it) and the writer instance
  actually used is not pinned down at bytecode level, but the end-to-end behavior is unambiguous
  and reproducible: **the replication-factor override is silently lost on the delete-file write
  path.** This is a genuine gap that ONLY real-HDFS validation can surface — the LocalFileSystem
  harness cannot: `RawLocalFileSystem` ignores replication entirely (always reports 1, `setReplication`
  is a no-op) and the harness never asserts replication. Disposition: re-port/verify the #219/#229
  delete-file-replication plumbing against the fork's 1.10 Spark write path; add an HDFS-level
  replication assertion to the validation suite so this cannot regress silently.

## Claim summary
1. Write + read Iceberg table on real HDFS via Spark 3.5 — **YES** (Claim 1).
2. Replication-factor custom behavior applies on HDFS — **NO** (F-REPL); plumbing present in the
   fork jar but the override never reaches the delete-file write; delete files follow `dfs.replication`.
3. Server metadata.json direct-write to HDFS with 3.3.4 client vs 3.2 HDFS — **YES** (Claim 3),
   and additionally proven on a Java 23 server JVM with no wire errors.
