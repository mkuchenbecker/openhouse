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
- `gradle dockerBuild -Precipe=oh-hadoop-spark` (JDK17) — in progress.
