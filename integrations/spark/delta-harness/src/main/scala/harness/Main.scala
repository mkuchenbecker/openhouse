package harness

import org.apache.spark.sql.SparkSession
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

// =====================================================================================
// Minimal delta-test harness — first slice (docs 01-07, 12).
// Runs the DELETE category at a single permutation against real Spark 3.5 + Iceberg,
// and self-verifies (each check declares its expected outcome; the run gates on match).
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

// ---------- Classification (doc 02: allowlist, fail-closed, unwrap the cause chain) ----------
object Classify {
  def chain(t: Throwable): List[Throwable] = {
    val b = scala.collection.mutable.ListBuffer[Throwable]()
    var c = t
    while (c != null && !b.contains(c)) { b += c; c = c.getCause }
    b.toList
  }
  def unwrap(t: Throwable): Throwable = chain(t).last
  // allowlist: only IOException-family (anywhere in the chain) is retryable infra
  def isTransient(t: Throwable): Boolean = chain(t).exists(_.isInstanceOf[java.io.IOException])
  def infra(t: Throwable): InfraError =
    if (isTransient(t)) InfraError.StorageUnavailable(unwrap(t).toString)
    else InfraError.Unclassified(t) // fail closed: unknown => terminal
}

// ---------- Resources as thunks + bracket (doc 04) ----------
final case class Managed[R](acquire: () => R, release: R => Unit)

object Harness {
  val MaxAttempts = 3

  // The single edge: acquire/use/release are boundaries; escapes re-type into InfraError.
  def bracket[A](m: Managed[Ctx])(use: Ctx => A): Either[InfraError, A] = {
    val r = try m.acquire() catch { case NonFatal(t) => return Left(Classify.infra(t)) }
    try Right(use(r))
    catch { case NonFatal(t) => Left(Classify.infra(t)) }
    finally { try m.release(r) catch { case NonFatal(_) => () } } // release never masks the verdict
  }

  // Retry only retryable Errored (doc 03). Returns (outcome, attempts).
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

// ---------- Case model (docs 05, 06) ----------
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
  // Delta test: observe -> operation -> observe -> expect(delta). (doc 06)
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

  // Rejection test: the operation is EXPECTED to throw a matching error (a verdict, not infra). (doc 12 #2)
  def rejectionTest(desc: String)(operation: Ctx => Unit)(matches: Throwable => Boolean): Ctx => Outcome =
    ctx =>
      Try(operation(ctx)) match {
        case Failure(t) if Classify.chain(t).exists(matches) => Outcome.Passed
        case Failure(t) => Outcome.Failed(s"expected $desc, got ${t.getClass.getSimpleName}: ${t.getMessage}")
        case Success(_) => Outcome.Failed(s"expected $desc, but operation succeeded")
      }

  // Fixture (doc 05): create table + seed rows; release drops it. Thunk-deferred, hermetic.
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

// ---------- The DELETE slice + firewall self-tests (doc 12) ----------
object DeleteSlice {
  import Builders._

  private val seed = Seq((1L, "a"), (2L, "b"), (3L, "c"))
  private def ddl(t: String) = s"CREATE TABLE $t (id bigint, data string) USING iceberg"

  def cases(spark: SparkSession): List[(Case, String)] = {
    val ns = "local.db"

    // #1 delete-by-predicate (unpartitioned): DELETE WHERE id < 2
    val deletePredicate = Case(
      "delete.byPredicate",
      seeded(spark, s"$ns.t_pred", ddl(s"$ns.t_pred"), seed),
      deltaTest(ctx => ctx.spark.sql(s"DELETE FROM ${ctx.table} WHERE id < 2")) { (pre, post) =>
        val removed = pre.rows.filterNot(post.rows.contains)
        if (post.rows == List((2L, "b"), (3L, "c")) && removed == List((1L, "a"))) Right(())
        else Left(s"rows=${post.rows} removed=$removed")
      }
    )

    // #3 DELETE WHERE false: no-op AND no new snapshot
    val deleteWhereFalse = Case(
      "delete.whereFalse.noSnapshot",
      seeded(spark, s"$ns.t_false", ddl(s"$ns.t_false"), seed),
      deltaTest(ctx => ctx.spark.sql(s"DELETE FROM ${ctx.table} WHERE false")) { (pre, post) =>
        if (post.rows == pre.rows && post.snapshots == pre.snapshots) Right(())
        else Left(s"rowsΔ=${pre.rows != post.rows} snapΔ=${post.snapshots - pre.snapshots}")
      }
    )

    // #4 TRUNCATE TABLE: all rows removed
    val truncate = Case(
      "delete.truncate",
      seeded(spark, s"$ns.t_trunc", ddl(s"$ns.t_trunc"), seed),
      deltaTest(ctx => ctx.spark.sql(s"TRUNCATE TABLE ${ctx.table}")) { (_, post) =>
        if (post.rows.isEmpty) Right(()) else Left(s"expected empty, got ${post.rows}")
      }
    )

    // #2 DELETE at a specific snapshot must be rejected (verdict-vs-infra firewall on a rejection)
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

    // Firewall A: injected transient IOException heals after retries -> Passed (doc 09 §2)
    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    val firewallTransient = Case(
      "firewall.transientHeals",
      noFixture(spark),
      { _ =>
        if (counter.getAndIncrement() < 2) throw new java.io.IOException("injected transient storage fault")
        else Outcome.Passed
      }
    )

    // Firewall B: a deliberately-wrong expectation -> Failed (gate must catch it) (doc 09 §2)
    val firewallWrong = Case(
      "firewall.wrongExpect",
      seeded(spark, s"$ns.t_wrong", ddl(s"$ns.t_wrong"), seed),
      deltaTest(ctx => ctx.spark.sql(s"DELETE FROM ${ctx.table} WHERE false")) { (_, post) =>
        if (post.rows.isEmpty) Right(()) else Left("intentional: asserted empty after a no-op delete")
      }
    )

    List(
      deletePredicate    -> "PASS",
      deleteWhereFalse   -> "PASS",
      truncate           -> "PASS",
      deleteAtSnapshot   -> "PASS",
      firewallTransient  -> "PASS",  // passes only if retry absorbed the injected faults
      firewallWrong      -> "FAIL"   // self-test: harness must report FAIL here
    )
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val warehouse = java.nio.file.Files.createTempDirectory("oh-harness-wh").toString
    val spark = SparkSession.builder()
      .appName("delta-harness-delete-slice")
      .master("local[2]")
      .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
      .config("spark.sql.catalog.local", "org.apache.iceberg.spark.SparkCatalog")
      .config("spark.sql.catalog.local.type", "hadoop")
      .config("spark.sql.catalog.local.warehouse", warehouse)
      .config("spark.sql.defaultCatalog", "local")
      .config("spark.ui.enabled", "false")
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")

    println("\n=== delta-harness :: DELETE slice @ [formatVersion=2, parquet, copy-on-write, unpartitioned] ===\n")

    var mismatches = 0
    for ((c, expected) <- DeleteSlice.cases(spark)) {
      val (out, attempts) = Harness.runCase(c)
      val got = out.label
      val ok  = got == expected
      if (!ok) mismatches += 1
      val detail = out match {
        case Outcome.Failed(d)  => s"  ($d)"
        case Outcome.Errored(e) => s"  (${e.message})"
        case _                  => ""
      }
      println(f"${if (ok) "✓" else "✗"}  ${c.id}%-32s expect=$expected%-5s got=$got%-5s attempts=$attempts$detail")
    }

    val total = DeleteSlice.cases(spark).size
    println(f"\n${total - mismatches}%d/$total harness checks verified" + (if (mismatches == 0) "  ✓ ALL GREEN" else s"  ✗ $mismatches MISMATCH"))
    spark.stop()
    System.exit(if (mismatches == 0) 0 else 1)
  }
}
