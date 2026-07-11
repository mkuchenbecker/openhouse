package harness

import org.apache.spark.sql.{Row, SparkSession}
import scala.annotation.tailrec
import scala.util.control.NonFatal

// =====================================================================================
// Delta-test harness against the real OpenHouse catalog.
//
// A test is a TYPED PIPELINE: `TableTest[S <: Schema]`. The type parameter declares which
// table implementation the test depends on, and every step references that schema's columns
// through typed handles — so the compiler forbids mixing schemas or naming a column the
// schema doesn't declare.
//
// Preparations and operations are BOTH pipeline segments of the same schema, composed with
// `andThen`:
//   * a preparation prefix  (create+seed, and later RTAS / drop+undrop) yields a known state,
//   * an operation suffix   (delete / update / merge / insert ...) runs on that state.
// The test set is `preparations x operations`. RTAS wires into every DML test by joining the
// preparations list; no operation changes. (RTAS is not built yet — only the seam is.)
//
// Catalog wiring is copied from OpenHouseLocalServer + TestSparkSessionUtil (composed, not
// extended); no OpenHouse test is altered.
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

  /**
   * Retry ONLY errors we positively recognize as transient. A bare IOException is NOT assumed
   * transient — a FileNotFoundException, an EOFException on a corrupt file, or a permission error
   * is an IOException too, and those are real failures that must surface rather than be retried
   * away. When in doubt, an error is terminal.
   */
  def isTransient(t: Throwable): Boolean = causeChain(t).exists {
    case _: java.net.SocketTimeoutException => true
    case _: java.net.ConnectException       => true
    case e: java.net.SocketException        => Option(e.getMessage).exists(_.toLowerCase.contains("reset"))
    case _                                  => false
  }
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

// ── Schema: columns only. A column owns its deterministic value generator; no stored seed. ──
//
// `literalAt(rowIndex)` is a pure function of the row index, so generated data is reproducible.
// This is the ONLY logic a schema carries. Value generation lives on the column, which keeps
// RowGenerator a plain iteration with no knowledge of types.
final case class Column(columnName: String, sqlType: String, literalAt: Int => String)

sealed trait Schema {
  def tableColumns: Seq[Column]
  def columnDefinitions: String =
    tableColumns.map(column => s"${column.columnName} ${column.sqlType}").mkString(", ")
  def columnNames: Seq[String] = tableColumns.map(_.columnName)
}

object CoreTable extends Schema {
  val id:   Column = Column("id",   "bigint", rowIndex => rowIndex.toString)
  val data: Column = Column("data", "string", rowIndex => s"'row-$rowIndex'")
  def tableColumns: Seq[Column] = Seq(id, data)
}

object RowGenerator {
  /** VALUES clause for `numberOfRows` deterministic rows, one literal per column. */
  def valuesClause(schema: Schema, numberOfRows: Int): String =
    (1 to numberOfRows).map { rowIndex =>
      schema.tableColumns.map(column => column.literalAt(rowIndex)).mkString("(", ", ", ")")
    }.mkString("VALUES ", ", ", "")
}

/** What a step's validation thunk sees: the live table plus its rows before and after the step. */
final case class StepView[S <: Schema](
  spark:  SparkSession,
  table:  String,
  schema: S,
  before: Seq[Row],
  after:  Seq[Row]
)

/** One pipeline step: mutate the live table, then validate it against before/after. */
final case class Step[S <: Schema](
  label:    String,
  execute:  (SparkSession, String, S) => Unit,
  validate: StepView[S] => Unit
)

/**
 * An immutable, typed pipeline. Build a preparation prefix and an operation suffix, then
 * `run` executes the steps in order on one fresh, always-dropped table, validating each step.
 */
final class TableTest[S <: Schema] private (val schema: S, val steps: Vector[Step[S]]) {
  private def add(step: Step[S]): TableTest[S] = new TableTest(schema, steps :+ step)

  /** Append another same-schema pipeline (this is how prep prefixes join operation suffixes). */
  def andThen(next: TableTest[S]): TableTest[S] = new TableTest(schema, steps ++ next.steps)

  def create(
      partitioning:    S => String         = _ => "",
      tableProperties: Map[String, String] = Map.empty
  )(validate: StepView[S] => Unit = _ => ()): TableTest[S] =
    add(Step("create", (spark, table, schema) => {
      val partitionClause = Option(partitioning(schema)).filter(_.nonEmpty)
        .map(specification => s"PARTITIONED BY ($specification)").getOrElse("")
      val propertyClause =
        if (tableProperties.isEmpty) ""
        else tableProperties.map { case (key, value) => s"'$key'='$value'" }
                            .mkString("TBLPROPERTIES (", ", ", ")")
      spark.sql(s"CREATE TABLE $table (${schema.columnDefinitions}) USING iceberg $partitionClause $propertyClause")
    }, validate))

  def insert(numberOfRows: Int)(validate: StepView[S] => Unit = _ => ()): TableTest[S] =
    add(Step(s"insert($numberOfRows)", (spark, table, schema) =>
      spark.sql(s"INSERT INTO $table ${RowGenerator.valuesClause(schema, numberOfRows)}"), validate))

  def delete(predicate: S => String)(validate: StepView[S] => Unit = _ => ()): TableTest[S] =
    add(Step("delete", (spark, table, schema) =>
      spark.sql(s"DELETE FROM $table WHERE ${predicate(schema)}"), validate))

  /** Execute the pipeline on a fresh table, snapshotting rows around each step for validation. */
  def run(ctx: Ctx): Unit = Tables.withTable(ctx) { table =>
    steps.foreach { step =>
      val before = Tables.currentRows(ctx.spark, table, schema)
      step.execute(ctx.spark, table, schema)
      val after = Tables.currentRows(ctx.spark, table, schema)
      step.validate(StepView(ctx.spark, table, schema, before, after))
    }
  }
}

object TableTest {
  def apply[S <: Schema](schema: S): TableTest[S] = new TableTest(schema, Vector.empty)
}

/** Table lifecycle + generic (schema-driven) row snapshots. */
object Tables {
  private val counter = new java.util.concurrent.atomic.AtomicInteger(0)

  def freshName(ctx: Ctx): String = s"${ctx.namespace}.t_${counter.incrementAndGet()}"
  def drop(ctx: Ctx, table: String): Unit = ctx.spark.sql(s"DROP TABLE IF EXISTS $table")

  /** Hand `use` a fresh, empty table name and always drop it afterward. */
  def withTable(ctx: Ctx)(use: String => Unit): Unit = {
    val table = freshName(ctx)
    drop(ctx, table) // ensure absent
    try use(table) finally drop(ctx, table)
  }

  private def exists(spark: SparkSession, table: String): Boolean =
    try { spark.sql(s"DESCRIBE TABLE $table"); true } catch { case NonFatal(_) => false }

  /** All rows, selected by the schema's columns and ordered by them for deterministic compares. */
  def currentRows(spark: SparkSession, table: String, schema: Schema): Seq[Row] =
    if (!exists(spark, table)) Seq.empty
    else {
      val columns = schema.columnNames.mkString(", ")
      spark.sql(s"SELECT $columns FROM $table ORDER BY $columns").collect().toSeq
    }
}

/** The concrete tests: preparation prefixes and operation suffixes, on CoreTable. */
object Scenarios {
  // Preparation: create an unpartitioned table and seed `numberOfRows` deterministic rows.
  // Interchangeable with RTAS / drop+undrop preparations later — same resulting state.
  def createAndSeed(numberOfRows: Int): TableTest[CoreTable.type] =
    TableTest(CoreTable).create()().insert(numberOfRows)()

  // Operation: delete rows with id < 2, asserting the delta against the observed pre-state.
  val deleteByPredicate: TableTest[CoreTable.type] =
    createAndSeed(numberOfRows = 3)
      .delete(coreTable => s"${coreTable.id.columnName} < 2") { view =>
        Check.equal(view.after, view.before.filterNot(row => row.getLong(0) < 2))
      }
}

/** Assembles the run. Stage 1: a single operation on the create+seed preparation. */
object Plan {
  final case class Case(id: String, run: Ctx => Unit)

  def cases: List[Case] = List(
    Case("delete.byPredicate @ core", Scenarios.deleteByPredicate.run)
  )
}

/** Runs a case, retrying only a transient-infrastructure failure. */
object Runner {
  val MaxAttempts = 3

  def execute(c: Plan.Case, ctx: Ctx): (Outcome, Int) = {
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

    // Each command-line arg is an include-substring; a case runs only if its id contains ALL of
    // them (AND). No args = run everything.
    val filters = args.toList
    def selected(id: String): Boolean = filters.forall(id.contains)
    val cases = Plan.cases.filter(c => selected(c.id))

    val header = if (filters.isEmpty) "all cases" else s"filter ${filters.mkString(", ")} -> ${cases.size} cases"
    println(s"\n=== delta-harness :: typed pipelines @ OpenHouse catalog ($header) ===\n")

    val results = cases.map(c => (c.id, Runner.execute(c, ctx)))

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
