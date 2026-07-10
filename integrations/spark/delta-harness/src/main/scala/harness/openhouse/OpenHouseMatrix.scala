package harness

import org.apache.spark.sql.SparkSession
import scala.annotation.tailrec
import scala.util.control.NonFatal

// =====================================================================================
// Delta-test harness — CREATE / READ / DELETE across a fileFormat axis, against the real
// OpenHouse catalog. Catalog wiring is copied from OpenHouseLocalServer +
// TestSparkSessionUtil (composed, not extended); no OpenHouse test is altered.
//
// A test is just a body that runs SQL and asserts. If it returns, it passed; if it throws,
// it failed, and the exception it threw is the reason. Whether that failure is transient
// infrastructure (worth retrying) is read off the exception type.
// =====================================================================================

final case class Ctx(spark: SparkSession, namespace: String)

/** A test: an id and a body that does its work and asserts, throwing on failure. */
final case class TestCase(id: String, body: Ctx => Unit)

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
  /** The classification policy: an IOException anywhere in the chain is transient infrastructure. */
  def isTransient(t: Throwable): Boolean = causeChain(t).exists(_.isInstanceOf[java.io.IOException])
}

/** Plain assertions. Each throws an AssertionError (a non-transient failure) on mismatch. */
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

// ---------- The fileFormat axis ----------
sealed trait FileFormat { def id: String }
object FileFormat {
  case object Parquet extends FileFormat { val id = "parquet" }
  case object Orc extends FileFormat { val id = "orc" }
  case object Avro extends FileFormat { val id = "avro" }
  val all: List[FileFormat] = List(Parquet, Orc, Avro)
}

/** Table helpers used by the test bodies: temp-table lifecycle and simple reads. */
object Tables {
  private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
  private val Seed = List((1L, "a"), (2L, "b"), (3L, "c"))

  def seed: List[(Long, String)] = Seed

  /** Run `use` against a fresh, uniquely-named table that is dropped afterward. */
  def withTable(ctx: Ctx)(use: String => Unit): Unit = {
    val table = s"${ctx.namespace}.t_${counter.incrementAndGet()}"
    ctx.spark.sql(s"DROP TABLE IF EXISTS $table")
    try use(table)
    finally ctx.spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  /** Same, but the table is created in `fmt` and seeded with the standard three rows. */
  def withSeededTable(ctx: Ctx, fmt: FileFormat)(use: String => Unit): Unit =
    withTable(ctx) { table =>
      ctx.spark.sql(s"CREATE TABLE $table (id bigint, data string) TBLPROPERTIES ('write.format.default'='${fmt.id}')")
      val values = Seed.map { case (id, data) => s"($id, '$data')" }.mkString(", ")
      ctx.spark.sql(s"INSERT INTO $table VALUES $values")
      use(table)
    }

  def rows(ctx: Ctx, table: String): List[(Long, String)] =
    ctx.spark.sql(s"SELECT id, data FROM $table ORDER BY id").collect().toList.map(r => (r.getLong(0), r.getString(1)))

  def snapshotCount(ctx: Ctx, table: String): Long =
    ctx.spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0)

  def schema(ctx: Ctx, table: String): List[(String, String)] =
    ctx.spark.table(table).schema.fields.toList.map(f => (f.name, f.dataType.simpleString))

  def dataFilePaths(ctx: Ctx, table: String): List[String] =
    ctx.spark.sql(s"SELECT file_path FROM $table.files").collect().toList.map(_.getString(0))
}

/** The test cases. Each reads top-to-bottom: set up, act, assert. */
object Tests {
  import Check._
  import Tables._

  private def test(id: String)(body: Ctx => Unit): TestCase = TestCase(id, body)

  def createSchema(fmt: FileFormat): TestCase =
    test(s"create.schema[fileFormat=${fmt.id}]") { ctx =>
      withTable(ctx) { table =>
        ctx.spark.sql(s"CREATE TABLE $table (id bigint, data string) TBLPROPERTIES ('write.format.default'='${fmt.id}')")
        equal(schema(ctx, table), List(("id", "bigint"), ("data", "string")))
        equal(rows(ctx, table), Nil)
      }
    }

  def readProjection(fmt: FileFormat): TestCase =
    test(s"read.projection[fileFormat=${fmt.id}]") { ctx =>
      withSeededTable(ctx, fmt) { table =>
        val data = ctx.spark.sql(s"SELECT data FROM $table ORDER BY id").collect().toList.map(_.getString(0))
        equal(data, List("a", "b", "c"))
      }
    }

  def readFilter(fmt: FileFormat): TestCase =
    test(s"read.filter[fileFormat=${fmt.id}]") { ctx =>
      withSeededTable(ctx, fmt) { table =>
        val ids = ctx.spark.sql(s"SELECT id FROM $table WHERE id >= 2 ORDER BY id").collect().toList.map(_.getLong(0))
        equal(ids, List(2L, 3L))
      }
    }

  def formatMaterialization(fmt: FileFormat): TestCase =
    test(s"format.materialization[fileFormat=${fmt.id}]") { ctx =>
      withSeededTable(ctx, fmt) { table =>
        val paths = dataFilePaths(ctx, table)
        isTrue(paths.nonEmpty && paths.forall(_.toLowerCase.endsWith(s".${fmt.id}")),
          s"data files are not all .${fmt.id}: $paths")
      }
    }

  def deleteByPredicate(fmt: FileFormat): TestCase =
    test(s"delete.byPredicate[fileFormat=${fmt.id}]") { ctx =>
      withSeededTable(ctx, fmt) { table =>
        ctx.spark.sql(s"DELETE FROM $table WHERE id < 2")
        equal(rows(ctx, table), List((2L, "b"), (3L, "c")))
      }
    }

  def deleteWhereFalseKeepsSnapshot(fmt: FileFormat): TestCase =
    test(s"delete.whereFalse.noSnapshot[fileFormat=${fmt.id}]") { ctx =>
      withSeededTable(ctx, fmt) { table =>
        val snapshotsBefore = snapshotCount(ctx, table)
        ctx.spark.sql(s"DELETE FROM $table WHERE false")
        equal(rows(ctx, table), seed)
        isTrue(snapshotCount(ctx, table) == snapshotsBefore, "DELETE WHERE false must not create a new snapshot")
      }
    }

  def truncate(fmt: FileFormat): TestCase =
    test(s"delete.truncate[fileFormat=${fmt.id}]") { ctx =>
      withSeededTable(ctx, fmt) { table =>
        ctx.spark.sql(s"TRUNCATE TABLE $table")
        equal(rows(ctx, table), Nil)
      }
    }

  def deleteAtSnapshotRejected(fmt: FileFormat): TestCase =
    test(s"delete.atSnapshot.rejected[fileFormat=${fmt.id}]") { ctx =>
      withSeededTable(ctx, fmt) { table =>
        val before = rows(ctx, table)
        val snapshotId = ctx.spark
          .sql(s"SELECT snapshot_id FROM $table.snapshots ORDER BY committed_at DESC LIMIT 1")
          .collect()(0).getLong(0)

        val error = intercept {
          ctx.spark.sql(s"DELETE FROM $table.snapshot_id_$snapshotId WHERE id < 4")
        }

        isTrue(error.isInstanceOf[IllegalArgumentException], s"expected IllegalArgumentException, got ${error.getClass.getName}")
        equal(error.getMessage, s"Cannot delete from table at a specific snapshot: $snapshotId")
        equal(rows(ctx, table), before) // a rejected delete must leave the table unchanged
      }
    }

  val perFormat: List[FileFormat => TestCase] =
    List(createSchema, readProjection, readFilter, formatMaterialization,
      deleteByPredicate, deleteWhereFalseKeepsSnapshot, truncate, deleteAtSnapshotRejected)

  // Two self-tests of the harness itself (doc 09): a transient IOException must heal via retry;
  // a wrong assertion must be reported as a failure.
  def firewallTransientHeals: TestCase = {
    val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
    test("firewall.transientHeals") { _ =>
      if (attempts.getAndIncrement() < 2) throw new java.io.IOException("injected transient storage fault")
    }
  }

  def firewallWrongExpectation: TestCase =
    test("firewall.wrongExpect") { ctx =>
      withSeededTable(ctx, FileFormat.Parquet) { table =>
        ctx.spark.sql(s"DELETE FROM $table WHERE false")
        equal(rows(ctx, table), Nil) // intentionally wrong: a no-op delete leaves the rows
      }
    }
}

/** Runs a case, retrying only a transient-infrastructure failure. */
object Runner {
  val MaxAttempts = 3

  def run(tc: TestCase, ctx: Ctx): (Outcome, Int) = {
    @tailrec def attempt(n: Int): (Outcome, Int) = {
      val outcome =
        try { tc.body(ctx); Outcome.Passed }
        catch { case NonFatal(t) => Outcome.Failed(t) }
      outcome match {
        case f: Outcome.Failed if f.retryable && n + 1 < MaxAttempts => attempt(n + 1)
        case terminal                                                => (terminal, n + 1)
      }
    }
    attempt(0)
  }
}

/** Generates the matrix (base tests x fileFormat) and applies the disable policy (doc 08). */
object Plan {
  // A known-blocked slice is a visible SKIP with a reason — a deliberate decision, not a swallow.
  private val disabled: List[(String, String)] = List(
    "fileFormat=avro" ->
      ("OpenHouse runtime shaded-Avro collides with Spark's unshaded Avro on the data path " +
        "(ClassCastException), likely from a recent Avro version bump diverging from Iceberg's " +
        "shaded Avro; packaging prerequisite before Avro is testable"))

  private def disabledReason(id: String): Option[String] =
    disabled.collectFirst { case (pattern, reason) if id.contains(pattern) => reason }

  final case class Entry(test: TestCase, expected: String, skip: Option[String])

  def entries: List[Entry] = {
    val matrix = for { fmt <- FileFormat.all; make <- Tests.perFormat } yield make(fmt)
    val firewall = List(Tests.firewallTransientHeals, Tests.firewallWrongExpectation)

    (matrix ++ firewall).map { tc =>
      disabledReason(tc.id) match {
        case Some(reason) => Entry(tc, "SKIP", Some(reason))
        case None if tc.id == "firewall.wrongExpect" => Entry(tc, "FAIL", None)
        case None => Entry(tc, "PASS", None)
      }
    }
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

    println("\n=== delta-harness :: CREATE/READ/DELETE x fileFormat @ OpenHouse catalog ===\n")

    val entries = Plan.entries
    val results = entries.map { entry =>
      val (outcome, attempts) = entry.skip match {
        case Some(reason) => (Outcome.Skipped(reason), 0)
        case None         => Runner.run(entry.test, ctx)
      }
      (entry, outcome, attempts)
    }

    results.foreach { case (entry, outcome, attempts) =>
      val ok = outcome.label == entry.expected
      val note = outcome match {
        case f: Outcome.Failed       => s"  (${f.reason}${if (f.retryable) " [retryable]" else ""})"
        case Outcome.Skipped(reason) => s"  ($reason)"
        case Outcome.Passed          => ""
      }
      println(f"${if (ok) "OK " else "XX "} ${entry.test.id}%-48s expect=${entry.expected}%-5s got=${outcome.label}%-5s try=$attempts$note")
    }

    val mismatches = results.count { case (entry, outcome, _) => outcome.label != entry.expected }
    println(f"\n${results.size - mismatches}%d/${results.size}%d verified" + (if (mismatches == 0) "  ALL GREEN" else s"  $mismatches MISMATCH"))
    try spark.stop() catch { case _: Throwable => () }
    try server.stop() catch { case _: Throwable => () }
    System.exit(if (mismatches == 0) 0 else 1)
  }
}
