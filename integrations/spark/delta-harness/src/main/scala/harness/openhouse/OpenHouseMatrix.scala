package harness

import org.apache.spark.sql.SparkSession
import scala.annotation.tailrec
import scala.util.control.NonFatal

// =====================================================================================
// Delta-test harness, against the real OpenHouse catalog. Structure:
//
//   starting states  x  state-agnostic tests        (every test runs on every state)
//   + standalone tests                               (state-transition tests, e.g. CREATE)
//
// A starting state prepares a seeded table in some physical shape (format, partitioning,
// and later RTAS'd / soft-dropped / feature-enabled). A test takes a prepared table, runs
// one or more modifications each followed by a verification, then the table is dropped.
//
// To add an "rtas" story later you write a couple of StartingState prep functions (which
// then run every existing test) and any rtas-specific TableTests (which then run on every
// existing table shape). Catalog wiring is copied from OpenHouseLocalServer +
// TestSparkSessionUtil (composed, not extended); no OpenHouse test is altered.
// =====================================================================================

final case class Ctx(spark: SparkSession, namespace: String)

sealed trait Outcome { def label: String }
object Outcome {
  case object Passed extends Outcome { val label = "PASS" }
  final case class Failed(cause: Throwable) extends Outcome {
    val label = "FAIL"
    def retryable: Boolean = Exceptions.isTransient(cause)
    def reason: String = s"${Exceptions.root(cause).getClass.getSimpleName}: ${cause.getMessage}"
  }
  final case class Skipped(reason: String) extends Outcome { val label = "SKIP" }
}

object Exceptions {
  def causeChain(t: Throwable): List[Throwable] = {
    val chain = scala.collection.mutable.ListBuffer[Throwable]()
    var current = t
    while (current != null && !chain.contains(current)) { chain += current; current = current.getCause }
    chain.toList
  }
  def root(t: Throwable): Throwable = causeChain(t).last
  /** Classification policy: an IOException anywhere in the chain is transient infrastructure. */
  def isTransient(t: Throwable): Boolean = causeChain(t).exists(_.isInstanceOf[java.io.IOException])
}

/** Plain assertions; each throws an AssertionError (a non-transient failure) on mismatch. */
object Check {
  def equal[A](actual: A, expected: A): Unit =
    if (actual != expected) throw new AssertionError(s"expected $expected but got $actual")

  def isTrue(condition: Boolean, message: String): Unit =
    if (!condition) throw new AssertionError(message)

  /** Run `op`, expecting it to throw; return the thrown error so the caller can assert on it. */
  def intercept(op: => Unit): Throwable = {
    val thrown = try { op; None } catch { case t: Throwable => Some(t) }
    thrown.getOrElse(throw new AssertionError("expected the operation to fail, but it succeeded"))
  }
}

sealed trait FileFormat { def id: String }
object FileFormat {
  case object Parquet extends FileFormat { val id = "parquet" }
  case object Orc extends FileFormat { val id = "orc" }
  case object Avro extends FileFormat { val id = "avro" }
}

/** Table helpers used by starting states and tests. */
object Tables {
  private val counter = new java.util.concurrent.atomic.AtomicInteger(0)

  val seed: List[(Long, String)] = List((1L, "a"), (2L, "b"), (3L, "c"))
  def seedValues: String = seed.map { case (id, data) => s"($id, '$data')" }.mkString(", ")

  def freshName(ctx: Ctx): String = s"${ctx.namespace}.t_${counter.incrementAndGet()}"
  def drop(ctx: Ctx, table: String): Unit = ctx.spark.sql(s"DROP TABLE IF EXISTS $table")

  def rows(ctx: Ctx, table: String): List[(Long, String)] =
    ctx.spark.sql(s"SELECT id, data FROM $table ORDER BY id").collect().toList.map(r => (r.getLong(0), r.getString(1)))
  def snapshotCount(ctx: Ctx, table: String): Long =
    ctx.spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0)
  def schema(ctx: Ctx, table: String): List[(String, String)] =
    ctx.spark.table(table).schema.fields.toList.map(f => (f.name, f.dataType.simpleString))
  def dataFilePaths(ctx: Ctx, table: String): List[String] =
    ctx.spark.sql(s"SELECT file_path FROM $table.files").collect().toList.map(_.getString(0))
  def declaredFormat(ctx: Ctx, table: String): String =
    ctx.spark.sql(s"SHOW TBLPROPERTIES $table ('write.format.default')").collect()(0).getString(1)
}

/** A named starting state: prepares a seeded table in some physical shape, returning its name. */
final case class StartingState(name: String, prepare: Ctx => String)

/** A state-agnostic test: it runs modifications + verifications against a prepared, seeded table. */
final case class TableTest(name: String, run: (Ctx, String) => Unit)

/** A self-contained test that needs a specific/absent state (e.g. CREATE); manages its own table. */
final case class StandaloneTest(name: String, run: Ctx => Unit)

object States {
  import Tables._

  def unpartitioned(fmt: FileFormat): StartingState =
    StartingState(s"unpartitioned/${fmt.id}", ctx => {
      val table = freshName(ctx)
      ctx.spark.sql(s"CREATE TABLE $table (id bigint, data string) TBLPROPERTIES ('write.format.default'='${fmt.id}')")
      ctx.spark.sql(s"INSERT INTO $table VALUES $seedValues")
      table
    })

  def partitioned(fmt: FileFormat): StartingState =
    StartingState(s"partitioned/${fmt.id}", ctx => {
      val table = freshName(ctx)
      ctx.spark.sql(s"CREATE TABLE $table (id bigint, data string) PARTITIONED BY (truncate(id, 2)) TBLPROPERTIES ('write.format.default'='${fmt.id}')")
      ctx.spark.sql(s"INSERT INTO $table VALUES $seedValues")
      table
    })

  // The states to run every state-agnostic test against.
  val all: List[StartingState] =
    for {
      fmt <- List(FileFormat.Parquet, FileFormat.Orc, FileFormat.Avro)
      shape <- List(unpartitioned _, partitioned _)
    } yield shape(fmt)
}

object Tests {
  import Check._
  import Tables._

  // ---- state-agnostic tests: same logical seed, so these hold on every table shape ----

  val readProjection: TableTest = TableTest("read.projection", (ctx, table) =>
    equal(ctx.spark.sql(s"SELECT data FROM $table ORDER BY id").collect().toList.map(_.getString(0)), List("a", "b", "c")))

  val readFilter: TableTest = TableTest("read.filter", (ctx, table) =>
    equal(ctx.spark.sql(s"SELECT id FROM $table WHERE id >= 2 ORDER BY id").collect().toList.map(_.getLong(0)), List(2L, 3L)))

  val formatMaterialization: TableTest = TableTest("format.materialization", (ctx, table) => {
    val fmt = declaredFormat(ctx, table)
    val paths = dataFilePaths(ctx, table)
    isTrue(paths.nonEmpty && paths.forall(_.toLowerCase.endsWith(s".$fmt")), s"data files are not all .$fmt: $paths")
  })

  val deleteByPredicate: TableTest = TableTest("delete.byPredicate", (ctx, table) => {
    ctx.spark.sql(s"DELETE FROM $table WHERE id < 2")
    equal(rows(ctx, table), List((2L, "b"), (3L, "c")))
  })

  val deleteWhereFalseKeepsSnapshot: TableTest = TableTest("delete.whereFalse.noSnapshot", (ctx, table) => {
    val snapshotsBefore = snapshotCount(ctx, table)
    ctx.spark.sql(s"DELETE FROM $table WHERE false")
    equal(rows(ctx, table), seed)
    isTrue(snapshotCount(ctx, table) == snapshotsBefore, "DELETE WHERE false must not create a new snapshot")
  })

  val truncate: TableTest = TableTest("delete.truncate", (ctx, table) => {
    ctx.spark.sql(s"TRUNCATE TABLE $table")
    equal(rows(ctx, table), Nil)
  })

  val deleteAtSnapshotRejected: TableTest = TableTest("delete.atSnapshot.rejected", (ctx, table) => {
    val before = rows(ctx, table)
    val snapshotId = ctx.spark
      .sql(s"SELECT snapshot_id FROM $table.snapshots ORDER BY committed_at DESC LIMIT 1")
      .collect()(0).getLong(0)
    val error = intercept(ctx.spark.sql(s"DELETE FROM $table.snapshot_id_$snapshotId WHERE id < 4"))
    isTrue(error.isInstanceOf[IllegalArgumentException], s"expected IllegalArgumentException, got ${error.getClass.getName}")
    equal(error.getMessage, s"Cannot delete from table at a specific snapshot: $snapshotId")
    equal(rows(ctx, table), before) // a rejected delete must leave the table unchanged
  })

  val stateAgnostic: List[TableTest] =
    List(readProjection, readFilter, formatMaterialization,
      deleteByPredicate, deleteWhereFalseKeepsSnapshot, truncate, deleteAtSnapshotRejected)

  // ---- standalone tests: incompatible with a pre-existing table ----

  val createTable: StandaloneTest = StandaloneTest("create.schema", ctx => {
    val table = freshName(ctx)
    try {
      ctx.spark.sql(s"CREATE TABLE $table (id bigint, data string)")
      equal(schema(ctx, table), List(("id", "bigint"), ("data", "string")))
      equal(rows(ctx, table), Nil)
    } finally drop(ctx, table)
  })

  val standalone: List[StandaloneTest] = List(createTable)
}

/** Assembles the run: (state x test) + standalone, applying the disable policy. */
object Plan {
  final case class Case(id: String, run: Ctx => Unit, skip: Option[String])

  // A known-blocked slice is a visible SKIP with a reason — a deliberate decision, not a swallow.
  private val disabled: List[(String, String)] = List(
    "avro" ->
      ("OpenHouse runtime shaded-Avro collides with Spark's unshaded Avro on the data path " +
        "(ClassCastException), likely from a recent Avro version bump diverging from Iceberg's " +
        "shaded Avro; packaging prerequisite before Avro is testable"))

  private def skipReason(id: String): Option[String] =
    disabled.collectFirst { case (pattern, reason) if id.contains(pattern) => reason }

  def cases: List[Case] = {
    val combined = for {
      state <- States.all
      test <- Tests.stateAgnostic
    } yield {
      val id = s"${test.name} @ ${state.name}"
      Case(id, ctx => {
        val table = state.prepare(ctx)
        try test.run(ctx, table) finally Tables.drop(ctx, table)
      }, skipReason(id))
    }

    val standalone = Tests.standalone.map(t => Case(t.name, t.run, skipReason(t.name)))

    combined ++ standalone
  }
}

/** Runs a case, retrying only a transient-infrastructure failure. */
object Runner {
  val MaxAttempts = 3

  def execute(c: Plan.Case, ctx: Ctx): (Outcome, Int) = c.skip match {
    case Some(reason) => (Outcome.Skipped(reason), 0)
    case None =>
      @tailrec def attempt(n: Int): (Outcome, Int) = {
        val outcome =
          try { c.run(ctx); Outcome.Passed }
          catch { case NonFatal(t) => Outcome.Failed(t) }
        outcome match {
          case f: Outcome.Failed if f.retryable && n + 1 < MaxAttempts => attempt(n + 1)
          case terminal                                                => (terminal, n + 1)
        }
      }
      attempt(0)
  }
}

/** Boots the embedded OpenHouse server and wires a SparkSession to the OpenHouse catalog. */
object OpenHouseEnv {
  import com.linkedin.openhouse.tablestest.OpenHouseLocalServer

  private def authToken(): String =
    Option(getClass.getClassLoader.getResourceAsStream("dummy.token"))
      .map(is => scala.io.Source.fromInputStream(is, "UTF-8").mkString.trim)
      .getOrElse("default-token")

  private def wireCatalog(builder: SparkSession.Builder, name: String, uri: String, token: String): SparkSession.Builder =
    builder
      .config(s"spark.sql.catalog.$name", "org.apache.iceberg.spark.SparkCatalog")
      .config(s"spark.sql.catalog.$name.catalog-impl", "com.linkedin.openhouse.spark.OpenHouseCatalog")
      .config(s"spark.sql.catalog.$name.uri", uri)
      .config(s"spark.sql.catalog.$name.cluster", "local-cluster")
      .config(s"spark.sql.catalog.$name.auth-token", token)

  def start(): (OpenHouseLocalServer, SparkSession) = {
    val server = new OpenHouseLocalServer()
    server.start()
    val uri = s"http://localhost:${server.getPort}"
    val token = authToken()

    val base = SparkSession.builder()
      .appName("delta-harness-openhouse")
      .master("local[2]")
      .config("spark.sql.extensions",
        "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions," +
          "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions")
      .config("spark.hadoop.fs.defaultFS", "file:///")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.sql.autoBroadcastJoinThreshold", "-1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.ui.enabled", "false")

    val wired = Seq("openhouse", "default_iceberg").foldLeft(base)(wireCatalog(_, _, uri, token))
    (server, wired.getOrCreate())
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val (server, spark) = OpenHouseEnv.start()
    spark.sparkContext.setLogLevel("ERROR")
    val ctx = Ctx(spark, "openhouse.dbMatrix")

    println("\n=== delta-harness :: state-agnostic tests x starting states @ OpenHouse catalog ===\n")

    val results = Plan.cases.map(c => (c.id, Runner.execute(c, ctx)))

    results.foreach { case (id, (outcome, attempts)) =>
      val note = outcome match {
        case f: Outcome.Failed       => s"  (${f.reason}${if (f.retryable) " [retryable]" else ""})"
        case Outcome.Skipped(reason) => s"  ($reason)"
        case Outcome.Passed          => ""
      }
      println(f"${outcome.label}%-4s ${id}%-52s try=$attempts$note")
    }

    val failed = results.count { case (_, (outcome, _)) => outcome.isInstanceOf[Outcome.Failed] }
    val skipped = results.count { case (_, (outcome, _)) => outcome.isInstanceOf[Outcome.Skipped] }
    val passed = results.size - failed - skipped
    println(f"\n$passed passed, $skipped skipped, $failed failed  (${results.size} cases)")

    try spark.stop() catch { case _: Throwable => () }
    try server.stop() catch { case _: Throwable => () }
    System.exit(if (failed == 0) 0 else 1)
  }
}
