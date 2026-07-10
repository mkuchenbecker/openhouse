package harness

import org.apache.spark.sql.SparkSession
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

// =====================================================================================
// Delta-test harness — CREATE / READ / DELETE across a fileFormat axis, against the
// REAL OpenHouse catalog. Catalog wiring COPIED from OpenHouseLocalServer +
// TestSparkSessionUtil (composed, not extended). No OpenHouse test altered.
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

// ---------- Axis (doc 07) ----------
sealed trait FileFormat { def id: String }
object FileFormat {
  case object Parquet extends FileFormat { val id = "parquet" }
  case object Orc     extends FileFormat { val id = "orc"     }
  case object Avro    extends FileFormat { val id = "avro"    }
  val all: List[FileFormat] = List(Parquet, Orc, Avro)
}

final case class Ctx(spark: SparkSession, table: String)
final case class State(rows: List[(Long, String)], snapshots: Long)
final case class Case(id: String, setup: () => Managed[Ctx], test: Ctx => Outcome)

object Observe {
  def state(ctx: Ctx): State = {
    val rows = ctx.spark.sql(s"SELECT id, data FROM ${ctx.table} ORDER BY id")
      .collect().toList.map(r => (r.getLong(0), r.getString(1)))
    val snaps = ctx.spark.sql(s"SELECT count(*) AS c FROM ${ctx.table}.snapshots")
      .collect()(0).getLong(0)
    State(rows, snaps)
  }
}

object Builders {
  // General assertion over a live Ctx: infra (IOException) -> Errored, else Left -> Failed. (docs 02/03)
  def assertion(check: Ctx => Either[String, Unit]): Ctx => Outcome =
    ctx => Try(check(ctx)) match {
      case Success(Right(_)) => Outcome.Passed
      case Success(Left(d))  => Outcome.Failed(d)
      case Failure(t) if Classify.isTransient(t) => Outcome.Errored(Classify.infra(t))
      case Failure(t)        => Outcome.Failed(s"threw ${Classify.unwrap(t).getClass.getSimpleName}: ${t.getMessage}")
    }

  // Delta test: observe -> operation -> observe -> expect(delta). (doc 06)
  def deltaTest(operation: Ctx => Unit)(expect: (State, State) => Either[String, Unit]): Ctx => Outcome =
    ctx => Try(Observe.state(ctx)) match {
      case Failure(t) => Outcome.Errored(Classify.infra(t))
      case Success(pre) =>
        Try(operation(ctx)) match {
          case Failure(t) if Classify.isTransient(t) => Outcome.Errored(Classify.infra(t))
          case Failure(t) => Outcome.Failed(s"operation threw ${Classify.unwrap(t).getClass.getSimpleName}: ${t.getMessage}")
          case Success(_) =>
            Try(Observe.state(ctx)) match {
              case Failure(t) => Outcome.Errored(Classify.infra(t))
              case Success(post) => expect(pre, post) match {
                case Left(d) => Outcome.Failed(d); case Right(_) => Outcome.Passed
              }
            }
        }
    }

  def rejectionTest(desc: String)(operation: Ctx => Unit)(matches: Throwable => Boolean): Ctx => Outcome =
    ctx => Try(operation(ctx)) match {
      case Failure(t) if Classify.chain(t).exists(matches) => Outcome.Passed
      case Failure(t) => Outcome.Failed(s"expected $desc, got ${t.getClass.getSimpleName}: ${t.getMessage}")
      case Success(_) => Outcome.Failed(s"expected $desc, but operation succeeded")
    }

  private def props(fmt: FileFormat) = s"TBLPROPERTIES ('write.format.default'='${fmt.id}')"

  // Fixture: create table with the axis fileFormat + seed rows. (doc 05)
  def seeded(spark: SparkSession, table: String, fmt: FileFormat, seed: Seq[(Long, String)]): () => Managed[Ctx] =
    () => Managed(
      acquire = () => {
        spark.sql(s"DROP TABLE IF EXISTS $table")
        spark.sql(s"CREATE TABLE $table (id bigint, data string) ${props(fmt)}")
        if (seed.nonEmpty)
          spark.sql(s"INSERT INTO $table VALUES ${seed.map { case (i, d) => s"($i, '$d')" }.mkString(", ")}")
        Ctx(spark, table)
      },
      release = _ => spark.sql(s"DROP TABLE IF EXISTS $table"))

  // Fixture for CREATE tests: table absent (drop only); the test does the CREATE.
  def emptyName(spark: SparkSession, table: String): () => Managed[Ctx] =
    () => Managed(
      acquire = () => { spark.sql(s"DROP TABLE IF EXISTS $table"); Ctx(spark, table) },
      release = _ => spark.sql(s"DROP TABLE IF EXISTS $table"))

  def noFixture(spark: SparkSession): () => Managed[Ctx] =
    () => Managed(acquire = () => Ctx(spark, "<none>"), release = _ => ())

  def propsOf(fmt: FileFormat): String = props(fmt)
}

object Slice {
  import Builders._
  private val seed = Seq((1L, "a"), (2L, "b"), (3L, "c"))

  // ---- format-varying base tests: (spark, ns, fmt) => (Case, expectedLabel) ----

  private def createSchema(spark: SparkSession, ns: String, fmt: FileFormat): (Case, String) = {
    val t = s"$ns.t_create_${fmt.id}"
    Case(s"create.schema[fileFormat=${fmt.id}]", emptyName(spark, t), assertion { ctx =>
      ctx.spark.sql(s"CREATE TABLE ${ctx.table} (id bigint, data string) ${propsOf(fmt)}")
      val schema = ctx.spark.table(ctx.table).schema.fields.toList.map(f => (f.name, f.dataType.simpleString))
      val cnt = ctx.spark.sql(s"SELECT count(*) FROM ${ctx.table}").collect()(0).getLong(0)
      if (schema == List(("id", "bigint"), ("data", "string")) && cnt == 0) Right(())
      else Left(s"schema=$schema count=$cnt")
    }) -> "PASS"
  }

  private def readProjection(spark: SparkSession, ns: String, fmt: FileFormat): (Case, String) = {
    val t = s"$ns.t_readproj_${fmt.id}"
    Case(s"read.projection[fileFormat=${fmt.id}]", seeded(spark, t, fmt, seed), assertion { ctx =>
      val got = ctx.spark.sql(s"SELECT data FROM ${ctx.table} ORDER BY id").collect().toList.map(_.getString(0))
      if (got == List("a", "b", "c")) Right(()) else Left(s"got=$got")
    }) -> "PASS"
  }

  private def readFilter(spark: SparkSession, ns: String, fmt: FileFormat): (Case, String) = {
    val t = s"$ns.t_readfilt_${fmt.id}"
    Case(s"read.filter[fileFormat=${fmt.id}]", seeded(spark, t, fmt, seed), assertion { ctx =>
      val got = ctx.spark.sql(s"SELECT id FROM ${ctx.table} WHERE id >= 2 ORDER BY id").collect().toList.map(_.getLong(0))
      if (got == List(2L, 3L)) Right(()) else Left(s"got=$got")
    }) -> "PASS"
  }

  private def formatMaterialization(spark: SparkSession, ns: String, fmt: FileFormat): (Case, String) = {
    val t = s"$ns.t_fmt_${fmt.id}"
    Case(s"format.materialization[fileFormat=${fmt.id}]", seeded(spark, t, fmt, seed), assertion { ctx =>
      val paths = ctx.spark.sql(s"SELECT file_path FROM ${ctx.table}.files").collect().toList.map(_.getString(0))
      if (paths.nonEmpty && paths.forall(_.toLowerCase.endsWith(s".${fmt.id}"))) Right(())
      else Left(s"data files not all .${fmt.id}: $paths")
    }) -> "PASS"
  }

  private def deletePredicate(spark: SparkSession, ns: String, fmt: FileFormat): (Case, String) = {
    val t = s"$ns.t_delpred_${fmt.id}"
    Case(s"delete.byPredicate[fileFormat=${fmt.id}]", seeded(spark, t, fmt, seed),
      deltaTest(c => c.spark.sql(s"DELETE FROM ${c.table} WHERE id < 2")) { (pre, post) =>
        val removed = pre.rows.filterNot(post.rows.contains)
        if (post.rows == List((2L, "b"), (3L, "c")) && removed == List((1L, "a"))) Right(())
        else Left(s"rows=${post.rows} removed=$removed")
      }) -> "PASS"
  }

  private def deleteWhereFalse(spark: SparkSession, ns: String, fmt: FileFormat): (Case, String) = {
    val t = s"$ns.t_delfalse_${fmt.id}"
    Case(s"delete.whereFalse.noSnapshot[fileFormat=${fmt.id}]", seeded(spark, t, fmt, seed),
      deltaTest(c => c.spark.sql(s"DELETE FROM ${c.table} WHERE false")) { (pre, post) =>
        if (post.rows == pre.rows && post.snapshots == pre.snapshots) Right(())
        else Left(s"rowsΔ=${pre.rows != post.rows} snapΔ=${post.snapshots - pre.snapshots}")
      }) -> "PASS"
  }

  private def truncate(spark: SparkSession, ns: String, fmt: FileFormat): (Case, String) = {
    val t = s"$ns.t_trunc_${fmt.id}"
    Case(s"delete.truncate[fileFormat=${fmt.id}]", seeded(spark, t, fmt, seed),
      deltaTest(c => c.spark.sql(s"TRUNCATE TABLE ${c.table}")) { (_, post) =>
        if (post.rows.isEmpty) Right(()) else Left(s"expected empty, got ${post.rows}")
      }) -> "PASS"
  }

  private def deleteAtSnapshot(spark: SparkSession, ns: String, fmt: FileFormat): (Case, String) = {
    val t = s"$ns.t_delsnap_${fmt.id}"
    Case(s"delete.atSnapshot.rejected[fileFormat=${fmt.id}]", seeded(spark, t, fmt, seed),
      rejectionTest("rejection of delete-at-snapshot")({ ctx =>
        val snap = ctx.spark.sql(s"SELECT snapshot_id FROM ${ctx.table}.snapshots ORDER BY committed_at DESC LIMIT 1")
          .collect()(0).getLong(0)
        ctx.spark.sql(s"DELETE FROM ${ctx.table}.snapshot_id_$snap WHERE id < 4")
      })(t => Option(t.getMessage).exists(_.toLowerCase.contains("snapshot")))) -> "PASS"
  }

  private val formatVarying =
    List(createSchema _, readProjection _, readFilter _, formatMaterialization _,
      deletePredicate _, deleteWhereFalse _, truncate _, deleteAtSnapshot _)

  // Disable policy (doc 08): a known-blocked axis slice becomes a visible Skipped row (with a
  // reason), NOT a silent drop and NOT a red ERROR. Here: the OpenHouse runtime ships a shaded
  // Avro that collides with Spark's unshaded Avro on the data path -> ClassCastException. That is
  // an OpenHouse packaging prerequisite, so we disable the slice rather than let it fail.
  private val disabled: List[(String, String)] = List(
    "fileFormat=avro" ->
      "OpenHouse runtime shaded-Avro collides with Spark unshaded Avro on the data path (ClassCastException); packaging prerequisite before Avro is testable"
  )
  private def disabledReason(id: String): Option[String] =
    disabled.collectFirst { case (k, r) if id.contains(k) => r }

  // Generator (doc 07): cross format-varying base tests x fileFormat; firewall tests stay format-free.
  def cases(spark: SparkSession, ns: String): List[(Case, String)] = {
    val matrix = for {
      fmt  <- FileFormat.all
      base <- formatVarying
    } yield base(spark, ns, fmt)

    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    val firewallTransient = Case("firewall.transientHeals", noFixture(spark), { _ =>
      if (counter.getAndIncrement() < 2) throw new java.io.IOException("injected transient storage fault")
      else Outcome.Passed
    }) -> "PASS"
    val firewallWrong = Case("firewall.wrongExpect", seeded(spark, s"$ns.t_wrong", FileFormat.Parquet, seed),
      deltaTest(c => c.spark.sql(s"DELETE FROM ${c.table} WHERE false")) { (_, post) =>
        if (post.rows.isEmpty) Right(()) else Left("intentional: asserted empty after a no-op delete")
      }) -> "FAIL"

    (matrix ++ List(firewallTransient, firewallWrong)).map { case (c, exp) =>
      disabledReason(c.id) match {
        case Some(r) => (Case(c.id, noFixture(spark), _ => Outcome.Skipped(r)), "SKIP")
        case None    => (c, exp)
      }
    }
  }
}

object OpenHouseEnv {
  import com.linkedin.openhouse.tablestest.OpenHouseLocalServer
  private def authToken(): String = {
    val is = getClass.getClassLoader.getResourceAsStream("dummy.token")
    if (is == null) "default-token" else scala.io.Source.fromInputStream(is, "UTF-8").mkString.trim
  }
  def start(): (OpenHouseLocalServer, SparkSession) = {
    val server = new OpenHouseLocalServer(); server.start()
    val uri = s"http://localhost:${server.getPort}"; val token = authToken()
    var b = SparkSession.builder()
      .appName("delta-harness-openhouse").master("local[2]")
      .config("spark.sql.extensions",
        "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions," +
          "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions")
      .config("spark.hadoop.fs.defaultFS", "file:///")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.sql.autoBroadcastJoinThreshold", "-1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.ui.enabled", "false")
    for (cat <- Seq("openhouse", "default_iceberg")) {
      b = b.config(s"spark.sql.catalog.$cat", "org.apache.iceberg.spark.SparkCatalog")
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
    val ns = "openhouse.dbMatrix"
    println("\n=== delta-harness :: CREATE/READ/DELETE x fileFormat @ OpenHouse catalog ===\n")
    val specs = Slice.cases(spark, ns)
    var mismatches = 0
    for ((c, expected) <- specs) {
      val (out, attempts) = Harness.runCase(c)
      val ok = out.label == expected
      if (!ok) mismatches += 1
      val detail = out match {
        case Outcome.Failed(d)   => s"  ($d)"
        case Outcome.Errored(e)  => s"  (${e.message})"
        case Outcome.Skipped(r)  => s"  ($r)"
        case _                   => ""
      }
      println(f"${if (ok) "OK " else "XX "} ${c.id}%-48s expect=$expected%-5s got=${out.label}%-5s try=$attempts$detail")
    }
    println(f"\n${specs.size - mismatches}%d/${specs.size}%d verified" + (if (mismatches == 0) "  ALL GREEN" else s"  $mismatches MISMATCH"))
    try spark.stop() catch { case _: Throwable => () }
    try server.stop() catch { case _: Throwable => () }
    System.exit(if (mismatches == 0) 0 else 1)
  }
}
