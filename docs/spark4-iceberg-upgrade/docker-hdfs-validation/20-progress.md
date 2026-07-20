# Progress log — docker/HDFS validation

Append-only. Newest at the bottom.

## Setup / recon
- Worktree base was stale (branch `worktree-agent-*` @ 99133b0, lacking the 1.10 work);
  reset to `origin/claude/iceberg-spark-upgrade-4h7pwb` @ d7dd5e8 (the branch carrying the
  fork wiring: `iceberg_1_10_version = "1.10.0-openhouse"`, `mavenLocal()`).
- Confirmed fork jars in mavenLocal: `org/apache/iceberg/iceberg-core/1.10.0-openhouse`,
  `iceberg-spark-runtime-3.5_2.12/1.10.0-openhouse`, etc.
- Replication patch property names (from fork source):
  - session conf `spark.sql.iceberg.delete-file-replication` (SparkSQLProperties, #229)
  - write option `delete-file-replication` (SparkWriteOptions)
  - table property `write.delete-file-replication` (TableProperties)
  - applies to DELETE files via `OutputFileFactory.FILE_REPLICATION_FACTOR` ->
    `HadoopOutputFile.create()` calling `fs.create(..., replication, ...)`.

## Docker-setup fixes applied (see 30-pitfalls-findings.md D1–D6)
- spark-services.yml -> spark-3.5-base-hadoop3.2.dockerfile
- hdfs-services.yml -> bde2020 hadoop-3.2.1 namenode+datanode
- hadoop.env -> dfs.replication=1 + fs.defaultFS
- build.gradle CopyGitHooksTask -> worktree-safe hooks dir
- host build: system Gradle 8.14.3 + JAVA_HOME=JDK17 (wrapper/JDK21 blocked)

## Build
- `gradle dockerBuild` (JDK17) built all prereq jars (incl. apps uber jar after the
  shadow fix). Ran `docker compose build` directly thereafter.
- Spark image builder needed the CA cert-chain fix (D8): first attempt imported only the
  first cert of the bundle into JDK cacerts -> Maven PKIX failure; fixed by splitting the
  PEM bundle (`csplit`) and importing every cert. Then Spark tgz + Livy Maven build fetch
  over HTTPS OK.

## Runtime prep (superseded by results below)
- The OpenHouse Spark 3.5 runtime uber jar
  (`openhouse-spark-3.5-runtime_2.12-uber.jar`, copied into the image as
  `openhouse-spark-runtime_2.12-latest-all.jar`) contains OpenHouseCatalog + extensions +
  javaclient but does NOT bundle `iceberg-spark-runtime` (no `org.apache.iceberg.spark.SparkCatalog`,
  no `IcebergSparkSessionExtensions`). Iceberg must be provided separately. Rather than
  `--packages` (which would pull stock iceberg from Maven Central, not the fork), inject the
  FORK jar `iceberg-spark-runtime-3.5_2.12-1.10.0-openhouse.jar` from mavenLocal via
  `docker cp` and add it to `--jars`. Verified that jar contains SparkCatalog, the
  extensions, `SparkSQLProperties` (with the `spark.sql.iceberg.delete-file-replication`
  string, #229), `SparkWrite`, and `HadoopOutputFile` — i.e. the replication patch under test.

## VALIDATION RESULTS (stack up on real HDFS, Hadoop 3.2.1, Spark 3.5.2/Java11)

Spark invocation (local[2] inside local.spark-master), catalog `openhouse` +
`--conf spark.hadoop.fs.defaultFS=hdfs://namenode:9000` + both jars (openhouse
runtime uber + fork iceberg-spark-runtime-3.5_2.12-1.10.0-openhouse).

### Claim 1 — write + read on real HDFS: PASS
`CREATE openhouse.db.hdfs_val (id int, msg string)`; two INSERTs (5 rows);
`SELECT count(*),sum(id)` -> `5   15`; `SELECT *` -> rows (1,a)(2,b)(3,c)(4,d)(5,e).
`hdfs dfs -ls -R` shows the table on HDFS (nothing on Spark local disk):
- 3 `*.metadata.json` (server-written, one per commit)
- `data/` with 4 `.orc` data files (Spark executors)
- `metadata/` with 2 manifest `-m0.avro` + 2 `snap-*.avro` (Spark)

### Claim 3 — server metadata.json direct-write to HDFS: PASS
The `metadata.json` files above were written by the OpenHouse **tables service**
(owner `openhouse`), not Spark, via `OpenHouseInternalTableOperations` ->
`TableMetadataParser.write` on `HadoopFileIO`. Client is hadoop-client **3.3.4**
(pinned in `hadoop-conventions.gradle`), HDFS cluster is Hadoop **3.2.1**, and the
tables JVM is **Java 23**. No `NoSuchMethodError` / wire-incompat / RemoteException
in `docker logs local.openhouse-tables`. Multiple successful commits (CREATE +
2 INSERTs => 00000/00001/00002.metadata.json) confirm the commit path round-trips.

### Claim 2 — delete-file replication custom behavior: NOT APPLIED (fail)
`hdfs dfs -stat "%r"` on MoR position-delete files:

| table | dfs.replication | delete-file-replication set via | data repl | DELETE-file repl |
|---|---|---|---|---|
| mor_rep | 3 | `--conf spark.sql.iceberg.delete-file-replication=1` | 3 | **3** (expected 1) |
| mor_upd | 1 | in-session `SET ...=3` (UPDATE + DELETE) | 1 | **1** (expected 3) |
| mor_def | 1 | (none; patch default is 3) | 1 | **1** |
| mor_rep2 | 3 | TBLPROPERTIES `write.delete-file-replication=2` (round-trips via SHOW TBLPROPERTIES) | 3 | **3** (expected 2) |
| rep_probe | 2 | (baseline: standard `spark.hadoop.dfs.replication=2`) | **2** | n/a |

The delete-file replication factor always equals the standard HDFS `dfs.replication`
and never tracks `spark.sql.iceberg.delete-file-replication` (session conf or write
option) nor `write.delete-file-replication` (table property). The `rep_probe` row
shows generic HDFS replication control DOES work through this client (data files
honored `dfs.replication=2`), so the failure is specific to the delete-file knob.
See finding F-REPL below for the jar-level analysis.
