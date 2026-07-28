# Rung 3 — v3 Deletion Vectors on Spark 4.0 + Iceberg 1.11

Goal: demonstrate DataSource-V2 deletion vectors (DVs) on Spark 4.0, bumping the custom Iceberg
fork from Apache 1.10 to 1.11. Diff the harness matrix against the rung-2 baseline
(1637 passed / 28 skipped / 32 failed).

| File | Covers |
|---|---|
| `00-index.md` (this) | Scope, stack, how to reproduce |
| `10-progress.md` | Built-vs-plan ledger: DV probe, fork bump, matrix, DV battery |
| `20-pitfalls.md` | Every blocker/config hit and its fix |

## How to reproduce

DV probe / battery (standalone, reuses the harness classpath + embedded server):
```
cd integrations/spark/delta-harness
JAVA17_HOME=/usr/lib/jvm/java-17-openjdk-amd64 FV=3 bash run-dvprobe.sh parquet   # v3 -> deletion vectors
JAVA17_HOME=/usr/lib/jvm/java-17-openjdk-amd64 FV=2 bash run-dvprobe.sh parquet   # v2 -> classic pos-deletes
```
`FV` sets the embedded server's `cluster.iceberg.format-version` (Spring `@Value`, JVM `-D`);
OpenHouse forces every table's `format-version` to the cluster value on create, so this is how
v3 tables are authored server-side.

Full matrix:
```
cd integrations/spark/delta-harness
JAVA17_HOME=/usr/lib/jvm/java-17-openjdk-amd64 GRADLE_BIN=gradle FORCE_CP=1 bash run-openhouse.sh
```

## Key up-front finding (corrects the rung premise)

Iceberg **1.10.0 already writes mature deletion vectors** at `format-version=3` merge-on-read — the
DV goal is reachable before the 1.11 bump. See `10-progress.md` step 1 for the evidence.
