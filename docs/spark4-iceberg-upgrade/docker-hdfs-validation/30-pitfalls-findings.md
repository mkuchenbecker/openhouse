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
