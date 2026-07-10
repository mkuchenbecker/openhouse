package harness

import org.apache.spark.sql.SparkSession
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

// =====================================================================================
// Delta-test harness — DELETE slice against the REAL OpenHouse catalog.
// The catalog wiring is COPIED from OpenHouse's own OpenHouseLocalServer +
// TestSparkSessionUtil (read, not extended). No OpenHouse test class is subclassed and
// no existing test is altered — this harness stands alone and composes the embedded
// server as a component.
// =====================================================================================

// ---------- Outcome model (doc 02) ----------
sealed trait Outcome { def label: String }
object Outcome {
  case object Passed                        extends Outcome { val label = "PASS"  }
  final case class Failed(diff: String)     extends Outcome { val label = "FAIL"  }
  final case class Errored(err: InfraError) extends Outcome { val label = "ERROR" }
  final case class Skipped(reason: String)  extends Outcome { val label = "SKIP"  }
}

sealed trait InfraError { def retryable: Boolean; def message: String }
object InfraError {
  final case class StorageUnavailable(message: String) extends InfraError { val retryable = true  }
  final case class Timeout(message: String)            extends InfraError { val retryable = true  }
  final case class Unclassified(cause: Throwable) extends InfraError {
    val retryable = false
    val message   = s"unclassified ${cause.getClass.getName}: ${cause.getMessage}"
  }
}

object Classify {
  def chain(t: Throwable): List[Throwable] = {
    val b = scala.collection.mutable.ListBuffer[Throwable]()
    var c = t
    while (c != null && !b.contains(c)) { b += c; c = c.getCause }
    b.toList
  }
  def unwrap(t: Throwable): Throwable = chain(t).last
  def isTransient(t: Throwable): Boolean = chain(t).exists(_.isInstanceOf[java.io.IOException])
  def infra(t: Throwable): InfraError =
    if (isTransient(t)) InfraError.StorageUnavailable(unwrap(t).toString)
    else InfraError.Unclassified(t)
}

final case class Managed[R](acquire: () => R, release: R => Unit)

object Harness {
  val MaxAttempts = 3

  def bracket[A](m: Managed[Ctx])(use: Ctx => A): Either[InfraError, A] = {
    val r = try m.acquire() catch { case NonFatal(t) => return Left(Classify.infra(t)) }
    try Right(use(r))
    catch { case NonFatal(t) => Left(Classify.infra(t)) }
    finally { try m.release(r) catch { case NonFatal(_) => () } }
  }

  def runCase(c: Case): (Outcome, Int) = {
    @tailrec def loop(n: Int): (Outcome, Int) = {
      val out = bracket(c.setup())(c.test) match {
        case Left(infra)  => Outcome.Errored(infra)
        case Right(inner) => inner
      }
      out match {
        case Outcome.Errored(e) if e.retryable && n + 1 < MaxAttempts => loop(n + 1)
        case other                                                    => (other, n + 1)
      }
    }
    loop(0)
  }
}

final case class Ctx(spark: SparkSession, table: String)
final case class State(rows: List[(Long, String)], snapshots: Long)
final case class Case(id: String, setup: () => Managed[Ctx], test: Ctx => Outcome)

object Observe {
  def state(ctx: Ctx): State = {
    val rows = ctx.spark
      .sql(s"SELECT id, data FROM ${ctx.table} ORDER BY id")
      .collect().toList.map(r => (r.getLong(0), r.getString(1)))
    val snaps = ctx.spark
      .sql(s"SELECT count(*) AS c FROM ${ctx.table}.snapshots")
      .collect()(0).getLong(0)
    State(rows, snaps)
  }
}

object Builders {
  def deltaTest(operation: Ctx => Unit)(expect: (State, State) => Either[String, Unit]): Ctx => Outcome =
    ctx =>
      Try(Observe.state(ctx)) match {
        case Failure(t) => Outcome.Errored(Classify.infra(t))
        case Success(pre) =>
          Try(operation(ctx)) match {
            case Failure(t) if Classify.isTransient(t) => Outcome.Errored(Classify.infra(t))
            case Failure(t) => Outcome.Failed(s"operation threw ${Classify.unwrap(t).getClass.getSimpleName}: ${t.getMessage}")
            case Success(_) =>
              Try(Observe.state(ctx)) match {
                case Failure(t) => Outcome.Errored(Classify.infra(t))
                case Success(post) =>
                  expect(pre, post) match {
                    case Left(d)  => Outcome.Failed(d)
                    case Right(_) => Outcome.Passed
                  }
              }
          }
      }

  def rejectionTest(desc: String)(operation: Ctx => Unit)(matches: Throwable => Boolean): Ctx => Outcome =
    ctx =>
      Try(operation(ctx)) match {
        case Failure(t) if Classify.chain(t).exists(matches) => Outcome.Passed
        case Failure(t) => Outcome.Failed(s"expected $desc, got ${t.getClass.getSimpleName}: ${t.getMessage}")
        case Success(_) => Outcome.Failed(s"expected $desc, but operation succeeded")
      }

  def seeded(spark: SparkSession, table: String, ddl: String, seed: Seq[(Long, String)]): () => Managed[Ctx] =
    () =>
      Managed(
        acquire = () => {
          spark.sql(s"DROP TABLE IF EXISTS $table")
          spark.sql(ddl)
          if (seed.nonEmpty) {
            val values = seed.map { case (id, d) => s"($id, '$d')" }.mkString(", ")
            spark.sql(s"INSERT INTO $table VALUES $values")
          }
          Ctx(spark, table)
        },
        release = _ => spark.sql(s"DROP TABLE IF EXISTS $table")
      )

  def noFixture(spark: SparkSession): () => Managed[Ctx] =
    () => Managed(acquire = () => Ctx(spark, "<none>"), release = _ => ())
}

object DeleteSlice {
  import Builders._

  private val seed = Seq((1L, "a"), (2L, "b"), (3L, "c"))
  // OpenHouse: no `USING iceberg`, catalog is already Iceberg. (copied from WapIdTest style)
  private def ddl(t: String) = s"CREATE TABLE $t (id bigint, data string)"

  def cases(spark: SparkSession, ns: String): List[(Case, String)] = {
    val deletePredicate = Case(
      "delete.byPredicate",
      seeded(spark, s"$ns.t_pred", ddl(s"$ns.t_pred"), seed),
      deltaTest(ctx => ctx.spark.sql(s"DELETE FROM ${ctx.table} WHERE id < 2")) { (pre, post) =>
        val removed = pre.rows.filterNot(post.rows.contains)
        if (post.rows == List((2L, "b"), (3L, "c")) && removed == List((1L, "a"))) Right(())
        else Left(s"rows=${post.rows} removed=$removed")
      }
    )

    val deleteWhereFalse = Case(
      "delete.whereFalse.noSnapshot",
      seeded(spark, s"$ns.t_false", ddl(s"$ns.t_false"), seed),
      deltaTest(ctx => ctx.spark.sql(s"DELETE FROM ${ctx.table} WHERE false")) { (pre, post) =>
        if (post.rows == pre.rows && post.snapshots == pre.snapshots) Right(())
        else Left(s"rowsΔ=${pre.rows != post.rows} snapΔ=${post.snapshots - pre.snapshots}")
      }
    )

    val truncate = Case(
      "delete.truncate",
      seeded(spark, s"$ns.t_trunc", ddl(s"$ns.t_trunc"), seed),
      deltaTest(ctx => ctx.spark.sql(s"TRUNCATE TABLE ${ctx.table}")) { (_, post) =>
        if (post.rows.isEmpty) Right(()) else Left(s"expected empty, got ${post.rows}")
      }
    )

    val deleteAtSnapshot = Case(
      "delete.atSnapshot.rejected",
      seeded(spark, s"$ns.t_snap", ddl(s"$ns.t_snap"), seed),
      rejectionTest("rejection of delete-at-snapshot")({ ctx =>
        val snap = ctx.spark
          .sql(s"SELECT snapshot_id FROM ${ctx.table}.snapshots ORDER BY committed_at DESC LIMIT 1")
          .collect()(0).getLong(0)
        ctx.spark.sql(s"DELETE FROM ${ctx.table}.snapshot_id_$snap WHERE id < 4")
      })(t => Option(t.getMessage).exists(_.toLowerCase.contains("snapshot")))
    )

    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    val firewallTransient = Case(
      "firewall.transientHeals",
      noFixture(spark),
      { _ =>
        if (counter.getAndIncrement() < 2) throw new java.io.IOException("injected transient storage fault")
        else Outcome.Passed
      }
    )

    val firewallWrong = Case(
      "firewall.wrongExpect",
      seeded(spark, s"$ns.t_wrong", ddl(s"$ns.t_wrong"), seed),
      deltaTest(ctx => ctx.spark.sql(s"DELETE FROM ${ctx.table} WHERE false")) { (_, post) =>
        if (post.rows.isEmpty) Right(()) else Left("intentional: asserted empty after a no-op delete")
      }
    )

    List(
      deletePredicate   -> "PASS",
      deleteWhereFalse  -> "PASS",
      truncate          -> "PASS",
      deleteAtSnapshot  -> "PASS",
      firewallTransient -> "PASS",
      firewallWrong     -> "FAIL"
    )
  }
}

// ---------- OpenHouse catalog wiring (COPIED from OpenHouseLocalServer + TestSparkSessionUtil) ----------
object OpenHouseEnv {
  import com.linkedin.openhouse.tablestest.OpenHouseLocalServer

  private def authToken(): String = {
    val is = getClass.getClassLoader.getResourceAsStream("dummy.token")
    if (is == null) "default-token"
    else scala.io.Source.fromInputStream(is, "UTF-8").mkString.trim
  }

  /** Start embedded OpenHouse server + build a SparkSession wired to the OpenHouse catalog. */
  def start(): (OpenHouseLocalServer, SparkSession) = {
    val server = new OpenHouseLocalServer()
    server.start()
    val uri = s"http://localhost:${server.getPort}"
    val token = authToken()

    var b = SparkSession.builder()
      .appName("delta-harness-openhouse-delete-slice")
      .master("local[2]")
      .config("spark.sql.extensions",
        "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions," +
          "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions")
      .config("spark.hadoop.fs.defaultFS", "file:///")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.sql.autoBroadcastJoinThreshold", "-1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.ui.enabled", "false")

    for (cat <- Seq("openhouse", "default_iceberg")) {
      b = b
        .config(s"spark.sql.catalog.$cat", "org.apache.iceberg.spark.SparkCatalog")
        .config(s"spark.sql.catalog.$cat.catalog-impl", "com.linkedin.openhouse.spark.OpenHouseCatalog")
        .config(s"spark.sql.catalog.$cat.uri", uri)
        .config(s"spark.sql.catalog.$cat.cluster", "local-cluster")
        .config(s"spark.sql.catalog.$cat.auth-token", token)
    }
    (server, b.getOrCreate())
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val (server, spark) = OpenHouseEnv.start()
    spark.sparkContext.setLogLevel("ERROR")
    val ns = "openhouse.dbDelete"

    println("\n=== delta-harness :: DELETE slice @ OpenHouse catalog [copy-on-write, unpartitioned] ===\n")

    val specs = DeleteSlice.cases(spark, ns)
    var mismatches = 0
    for ((c, expected) <- specs) {
      val (out, attempts) = Harness.runCase(c)
      val got = out.label
      val ok  = got == expected
      if (!ok) mismatches += 1
      val detail = out match {
        case Outcome.Failed(d)  => s"  ($d)"
        case Outcome.Errored(e) => s"  (${e.message})"
        case _                  => ""
      }
      println(f"${if (ok) "OK " else "XX "} ${c.id}%-32s expect=$expected%-5s got=$got%-5s attempts=$attempts$detail")
    }

    println(f"\n${specs.size - mismatches}%d/${specs.size}%d harness checks verified" +
      (if (mismatches == 0) "  ALL GREEN" else s"  $mismatches MISMATCH"))
    try spark.stop() catch { case _: Throwable => () }
    try server.stop() catch { case _: Throwable => () }
    System.exit(if (mismatches == 0) 0 else 1)
  }
}
