# Small batch W13 / W14 / N9: record

Bottom line: PAUSED on the owner's instruction (2026-11-25) during W13 investigation. Nothing committed,
nothing pushed, no pull request. Worktree /home/user/oh-small2 is clean at origin/irc-integration 81b4cd4d
(branch irc/small-batch-w13-w14-n9 exists locally only). W14 and N9 not started.

## Done
- Worktree created; rows read (30-work-defaults.md W13/W14, 41-work-platforms-v3.md N9, audit 74 lines 72-75).
- W13 reading (read, not yet confirmed by a built jar):
  - TablesClientFactory (apps/spark-3.5/src/shared/main/.../jobs/client) is constructed only by
    JobsScheduler (main code). It loads com.linkedin.openhouse.javaclient.IcebergLine by name; on
    ClassNotFound/LinkageError it warns and declares column-defaults.
  - jobs-scheduler.Dockerfile runs `java -cp openhouse-spark-apps-1.5_2.12.jar ...JobsScheduler`, no Spark.
  - apps-spark-common shadowJar excludes every com.linkedin.iceberg dependency, and the OpenHouse Spark
    runtime (which carries the Java client and IcebergLine) is compileOnly in apps/spark-3.5. So the
    scheduler jar has neither IcebergLine nor Iceberg: the warning fires on every scheduler start, on
    both lines, and it declares column-defaults on both lines.
  - Service side (read): ClientCapabilityGate refuses (422 CLIENT_CAPABILITY_MISSING) a load/write of a
    table with required-capability.column-defaults=true by a client not declaring it;
    ReadBridgeStripProtection / ColumnDefaultCommitPolicy count a declaring client as default-aware.
  - Spark 3.5 OpenHouseCatalog declares column-defaults only on the legacy line (IcebergLine); so on the
    1.12 line the scheduler (declaring) sees tables its jobs' catalogs are refused: likely wrong on the
    wire on 1.12 = a finding to bring to the orchestrator before changing what is sent.
  - apps/spark-4.2 has no deployable jar (plain jar; IcebergLine only on its test classpath).

## Half done
- Build of apps uber jar + Spark 3.5 runtime + Java runtime jars (default line) was started and stopped
  before finishing for the pause; no jar inspected yet.

## Exact next step
1. free -g / df; build `:apps:openhouse-spark-apps-1.5_2.12:shadowJar` (default line, then -PicebergLinkedIn)
   and `unzip -l` for IcebergLine and org/apache/iceberg; same for spark-3.5 runtime and java runtime jars.
2. Report the 1.12-line declaration finding to the orchestrator; then candidate fix: build-time line
   marker resource in the apps jar as the fallback when IcebergLine is absent (tests first in TablesClientTest).
3. Then W14 (docs/development), N9 (SparkSqlSmokeScenarios ~line 1224).
