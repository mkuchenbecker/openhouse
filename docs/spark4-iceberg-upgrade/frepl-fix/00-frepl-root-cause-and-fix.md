# F-REPL fix — delete-file replication now honored on HDFS (Spark 3.5 / Iceberg 1.10 fork)

Status: **FIXED + validated on real HDFS (PASS).**

Fork: `/home/user/apache-iceberg`, branch `openhouse-1.10-port`, commit `06e05ccfb`.
Republished to mavenLocal as `1.10.0-openhouse`.

## Symptom (from docker-hdfs-validation F-REPL)
MoR position-delete files always took the standard `dfs.replication`, ignoring the
`spark.sql.iceberg.delete-file-replication` session conf, the `delete-file-replication`
write option, and the `write.delete-file-replication` table property (#219/#229).

## Root cause (the part the earlier finding could not pin down)
The delete-file replication override IS correctly plumbed end-to-end on the Spark side —
verified live with instrumentation:

```
SparkPositionDeltaWrite ctor  deleteFileReplication = 1        (driver, resolved from session conf)
PositionDeltaWriteFactory     replicationFactor      = 1        (survives serialization to executor)
delete OutputFileFactory      newOutputFile suffix=deletes present=true  io=HadoopFileIO
OutputFileFactory.getProperties  {file-replication-factor=1}   (map path taken)
```

So the delete `OutputFileFactory` DOES carry the factor and DOES pass
`{file-replication-factor=1}` to `HadoopFileIO.newOutputFile(path, map)` →
`HadoopOutputFile` with `replication=1`. The earlier diagnosis ("delete OFF built without
the factor") was incorrect; the plumbing was already present and correct.

The real break is one layer deeper: **the actual Parquet and ORC file writers bypass
`HadoopOutputFile.create()`** — the only place the replication factor is passed to
`fs.create(...)`:

- **Parquet**: `ParquetIO.file(HadoopOutputFile[, conf])` returns parquet's *native*
  `org.apache.parquet.hadoop.util.HadoopOutputFile.fromPath(path, conf)`, which opens the
  file at the file-system default replication. (Iceberg's `ParquetWriter` uses the 2-arg
  `ParquetIO.file(output, conf)` overload.)
- **ORC**: `ORC.newFileWriter` calls `OrcFile.createWriter(path, options)`; ORC's
  `PhysicalFsWriter` opens the file with `fs.getDefaultReplication(path)`.

Neither ever consults the per-file replication on the iceberg `HadoopOutputFile`, so on
real HDFS the delete files silently followed `dfs.replication`. This is a **pre-existing
gap present in `openhouse-1.5.2` too** (identical `ParquetIO`/`ORC.newFileWriter`), not a
1.10 port regression. It only surfaces on real HDFS — the `LocalFileSystem` test harness
ignores replication entirely (`RawLocalFileSystem.setReplication` is a no-op).

## Fix (commit 06e05ccfb, 3 files, +43 lines, guarded by `replication > 0`)
- `core/.../hadoop/HadoopOutputFile.java`: expose `getReplication()`.
- `parquet/.../ParquetIO.java` (both `file(...)` overloads): when a custom replication
  factor is set, route through `ParquetOutputFile` so iceberg's `HadoopOutputFile.create()`
  (i.e. `fs.create(path, false, buf, replication, blockSize)`) is used.
- `orc/.../ORC.java` (`newFileWriter`): after creating the still-empty writer, apply the
  requested replication via `fs.setReplication(path, replication)` so the data blocks
  written next use it.

The guard (`replication > 0`) means zero behavioral change when the feature is not used.
Note: with the feature active, a delete write with no explicit override now applies the
configured default `write.delete-file-replication` (SparkWriteOptions default = 3) instead
of silently falling through to `dfs.replication`. This is the feature functioning as
designed, not a regression.

## Validation on real HDFS (oh-hadoop-spark, Hadoop 3.2.1, Spark 3.5.2, dfs.replication=3)
`hdfs dfs -stat "%r"` on the MoR position-delete (`*-deletes.*`) file:

| scenario | override via | format | delete %r before | delete %r after | data %r |
|---|---|---|---|---|---|
| session | `--conf spark.sql.iceberg.delete-file-replication=1` | ORC | 3 (FAIL) | **1** (PASS) | 3 |
| table property | `write.delete-file-replication=2` | ORC | 3 (FAIL) | **2** (PASS) | 3 |
| UPDATE (MoR) | session `=1` | ORC | 3 (FAIL) | **1** (PASS) | 3 |
| session | `=1` | Parquet | 3 (FAIL) | **1** (PASS) | 3 |
| baked-classpath | `=1`, iceberg from `$SPARK_HOME/jars` (no `--jars`) | ORC | — | **1** (PASS) | 3 |

Data files continue to follow `dfs.replication` (3) — no regression. **Overall: PASS.**

## Delivery into the docker Spark stack
No OpenHouse uber jar bundles `iceberg-spark-runtime` (it is an `implementation` dep, not
`fatJarPackagedDependencies`), so the fork artifact must be on the Spark classpath
separately. Validation loaded the republished `1.10.0-openhouse` fork jar exactly as the
stack loads it. The spark image Dockerfile
(`common/spark/spark-3.5-base-hadoop3.2.dockerfile`) was updated to COPY the fork jar into
`$SPARK_HOME/jars/` so future image builds bake the fix in (proven by the baked-classpath
row above). A plain image rebuild alone does NOT incorporate this fix — the openhouse uber
jars are unchanged by it; the fix rides on the fork jar.

## 1.11 replay
The fix touches the shared `core/`, `parquet/`, and `orc/` modules. The same patch should
be replayed onto the `openhouse-1.11-port` branch (the 1.11 `ParquetIO`/`ORC.newFileWriter`
have the identical bypass). Not done here per scope (1.10 / Spark-3.5 only).
