# Docker / real-HDFS validation of the Iceberg 1.10 fork

This sub-tree validates the OpenHouse stack — rebuilt against the custom Iceberg
`1.10.0-openhouse` fork — end-to-end on **real HDFS** (namenode + datanode
containers), not LocalFileSystem. It closes the honesty caveat recorded as
**F-VACUITY-HADOOP** in `../20-risks-decisions-findings.md`: the delta-harness that
validated the fork runs on `LocalFileSystem` + an H2 HTS stub, so the Hadoop/HDFS
leg was only build- and local-FS-validated. Here the stack is exercised against the
`oh-hadoop-spark` docker recipe (HDFS + OpenHouse services + Spark 3.5).

## Documents
- [`10-goal.md`](10-goal.md) — intent, scope, and the exact claims being proven.
- [`20-progress.md`](20-progress.md) — append-only run log (commands + evidence).
- [`30-pitfalls-findings.md`](30-pitfalls-findings.md) — what was broken in the docker
  setup, the fixes applied, and technical findings.

## What is being proven
1. OpenHouse + the 1.10 fork can CREATE / INSERT / SELECT an Iceberg table through
   Spark 3.5 SQL against the OpenHouse catalog, with data + metadata landing on HDFS.
2. The custom delete-file **replication-factor** patch (`#219`/`#229`) actually applies
   on HDFS writes (factor changes as the `spark.sql.iceberg.delete-file-replication`
   conf changes).
3. The OpenHouse server writes `metadata.json` **directly** to HDFS with the Hadoop
   3.3.4 client against the Hadoop 3.2 HDFS cluster (no `NoSuchMethodError` /
   wire-incompatibility).
