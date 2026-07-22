# Goal / intent — real-HDFS validation

## Why
`F-VACUITY-HADOOP` (see `../20-risks-decisions-findings.md`): the delta-harness that
gated the 1.10 fork runs on `spark.hadoop.fs.defaultFS=file:///`, `master=local[2]`,
and the H2 in-memory HTS stub. It therefore validated the Hadoop 2.10 -> 3.3.4 bump
only at (a) build/resolve/compile level and (b) the LocalFileSystem FileIO path. It
did NOT exercise `DistributedFileSystem`, the HDFS RPC wire protocol, or the server's
direct-to-HDFS metadata write. This work exercises those legs on a real HDFS cluster.

## Stack under test
`oh-hadoop-spark` docker recipe:
- Real HDFS: `bde2020` namenode + datanode, **Hadoop 3.2.1**.
- OpenHouse services (tables/housetables/jobs) built against `1.10.0-openhouse`,
  linking the Hadoop **3.3.4** client (per `F-HADOOP1`).
- Spark **3.5.2** (bundled Hadoop 3), OpenHouse Spark 3.5 runtime uber jar that
  bundles the fork's `iceberg-spark-runtime-3.5_2.12:1.10.0-openhouse`.

## Claims to prove (each needs real evidence, not "appears to work")
1. **Write+read on real HDFS.** Through Spark 3.5 SQL against catalog `openhouse`:
   `CREATE TABLE` -> `INSERT` -> `SELECT` returns correct rows; `hdfs dfs -ls -R`
   shows the table's data + metadata files under the warehouse path on HDFS.
2. **Replication-factor patch applies on HDFS.** With
   `spark.sql.iceberg.delete-file-replication=<N>` set and a DELETE/MERGE producing
   position-delete files, `hdfs dfs -stat "%r"` on the delete file(s) reports `<N>`,
   distinct from the data-file replication, and tracks the conf when changed.
3. **Server metadata direct-write to HDFS.** The OpenHouse server commit path
   (`OpenHouseInternalTableOperations` -> `TableMetadataParser.write`) writes
   `metadata.json` to HDFS successfully with the 3.3.4 client against 3.2 HDFS — no
   `NoSuchMethodError` / wire-incompat; the `metadata.json` object is present on HDFS
   and readable.

## Out of scope
Kerberos/auth, RBF routing, ADLS/S3, Spark 4 / Scala 2.13 (later rungs).
