package harness

import org.apache.spark.sql.{AnalysisException, Row, SparkSession}
import org.apache.iceberg.exceptions.BadRequestException
import org.apache.iceberg.exceptions.ValidationException
import com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.annotation.tailrec
import scala.reflect.{ClassTag, classTag}
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

final case class Ctx(spark: SparkSession, namespace: String, restUri: String = "", restToken: String = "")

// Minimal REST client to the embedded OpenHouse server (control-plane ops with no SQL surface:
// lock/unlock). Uses JDK 17's java.net.http; auth is the same Bearer token the Spark catalog uses.
object Rest {
  import java.net.http.{HttpClient, HttpRequest, HttpResponse}
  import java.net.URI
  private lazy val client = HttpClient.newHttpClient()
  private def base(ctx: Ctx, path: String): HttpRequest.Builder =
    HttpRequest.newBuilder(URI.create(ctx.restUri + path))
      .header("Authorization", s"Bearer ${ctx.restToken}")
      .header("Content-Type", "application/json")
  def post(ctx: Ctx, path: String, body: String): (Int, String) = {
    val r = client.send(base(ctx, path).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    (r.statusCode(), r.body())
  }
  def delete(ctx: Ctx, path: String): (Int, String) = {
    val r = client.send(base(ctx, path).DELETE().build(), HttpResponse.BodyHandlers.ofString())
    (r.statusCode(), r.body())
  }
  def put(ctx: Ctx, path: String, body: String): (Int, String) = {
    val r = client.send(base(ctx, path).PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    (r.statusCode(), r.body())
  }
  def get(ctx: Ctx, path: String): (Int, String) = {
    val r = client.send(base(ctx, path).GET().build(), HttpResponse.BodyHandlers.ofString())
    (r.statusCode(), r.body())
  }
}

// Drives the soft-delete / list / restore lifecycle for the UNDROP preparation axis (Phase 4).
// The customer DROP hard-codes purge=true (a hard delete), so soft-delete is unreachable via the
// Tables API — we trigger it directly on the EMBEDDED real HTS (only available under HARNESS_REAL_HTS=1),
// then restore via the customer-facing Tables API. Endpoints are process-global (one HTS, one tables
// server for the whole run) so they are held here and set once at startup; TableTest steps see only
// (spark, table) and reach the endpoints through this holder.
object HtsAdmin {
  import java.net.http.{HttpClient, HttpRequest, HttpResponse}
  import java.net.URI
  @volatile var htsUri: String = ""      // embedded HTS base (soft-delete + querySoftDeleted)
  @volatile var tablesUri: String = ""   // tables server base (restore, customer-facing)
  @volatile var token: String = ""       // Bearer token for the tables server
  def enabled: Boolean = htsUri.nonEmpty

  private lazy val client = HttpClient.newHttpClient()
  private def send(b: HttpRequest.Builder): (Int, String) = {
    val r = client.send(b.header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString())
    (r.statusCode(), r.body())
  }

  /** Soft-delete on the embedded HTS (V1 endpoint carries the isSoftDelete flag). No auth (HTS security excluded). */
  def softDelete(db: String, tbl: String): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(s"$htsUri/v1/hts/tables?databaseId=$db&tableId=$tbl&isSoftDelete=true")).DELETE())

  /** Recover the deletedAtMs of a soft-deleted table (needed to restore) from the HTS querySoftDeleted view. */
  def softDeletedAtMs(db: String, tbl: String): Option[Long] = {
    val (code, body) = send(HttpRequest.newBuilder(URI.create(s"$htsUri/hts/tables/querySoftDeleted?databaseId=$db&tableId=$tbl")).GET())
    if (code < 200 || code >= 300) None
    else "\"deletedAtMs\"\\s*:\\s*(\\d+)".r.findFirstMatchIn(body).map(_.group(1).toLong)
  }

  /** Restore via the customer-facing Tables API (PUT .../restore?deletedAtMs=). Requires the Bearer token. */
  def restore(db: String, tbl: String, deletedAtMs: Long): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(s"$tablesUri/v1/databases/$db/tables/$tbl/restore?deletedAtMs=$deletedAtMs"))
      .header("Authorization", s"Bearer $token")
      .PUT(HttpRequest.BodyPublishers.ofString("")))
}

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

// Tests assert with plain `assert`; a failed assertion throws AssertionError, which is NonFatal
// and so is caught at the Runner edge and reported as a (terminal) failure.
object Check {
  /**
   * Require `op` to throw exactly `E` — the ACTUAL thrown type is asserted, not merely that
   * *something* threw — and return it so the caller can assert on its message. NonFatal only; a
   * wrong type, or no throw at all, is itself an assertion failure.
   */
  def intercept[E <: Throwable: ClassTag](op: => Unit): E = {
    val expected = classTag[E].runtimeClass
    val caught: Option[Throwable] = try { op; None } catch { case NonFatal(t) => Some(t) }
    caught match {
      case Some(t) if expected.isInstance(t) => t.asInstanceOf[E]
      case Some(t) => throw new AssertionError(s"expected ${expected.getName} but got ${t.getClass.getName}: ${t.getMessage}", t)
      case None    => throw new AssertionError(s"expected ${expected.getName} to be thrown, but nothing was")
    }
  }
}

// ── Schema: columns only. A column owns its deterministic value generator; no stored seed. ──
//
// `Column[T]` carries a phantom type `T` — the Scala type the column reads back as — so typed
// row access (`row.get(CoreTable.long0): Long`) is compiler-checked. `literalAt(rowIndex)` is a
// pure function of the row index, so generated data is reproducible. Value generation lives on
// the column, which keeps RowGenerator a plain iteration with no knowledge of types.
final case class Column[T](columnName: String, sqlType: String, literalAt: Int => String)

sealed trait Schema {
  def tableColumns: Seq[Column[_]]
  def columnNames: Seq[String] = tableColumns.map(_.columnName)
}

/** Typed row access, keyed by the column's name: `row.get(CoreTable.long0)` returns a `Long`. */
object Rows {
  implicit class TypedRow(val row: Row) extends AnyVal {
    def get[T](column: Column[T]): T = row.getAs[T](column.columnName)
  }
}

// A representative "core" table: one column per common data type. Column NAMES are arbitrary
// literals (decoupled from the Scala handle) — tests reference columns through the handle, so a
// rename here propagates everywhere. Plus an explicit string date-partition field in the widely
// used YYYY-MM-DD-HH form. Columns only; each carries a deterministic generator.
object CoreTable extends Schema {
  val long0:         Column[Long]    = Column("foo_col_long",    "bigint",  rowIndex => rowIndex.toString)
  val int0:          Column[Int]     = Column("foo_col_int",     "int",     rowIndex => rowIndex.toString)
  val string0:       Column[String]  = Column("foo_col_string",  "string",  rowIndex => s"'row-$rowIndex'")
  val double0:       Column[Double]  = Column("foo_col_double",  "double",  rowIndex => s"$rowIndex.5")
  val boolean0:      Column[Boolean] = Column("foo_col_boolean", "boolean", rowIndex => if (rowIndex % 2 == 0) "true" else "false")
  val datePartition: Column[String]  = Column("datepartition",   "string",  rowIndex => s"'${CoreTable.datePartitionLiteral(rowIndex)}'")
  def tableColumns: Seq[Column[_]] = Seq(long0, int0, string0, double0, boolean0, datePartition)

  private val DatePartitionFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH")
  private val DatePartitionEpoch  = LocalDateTime.of(2024, 1, 1, 0, 0)

  /** Deterministic YYYY-MM-DD-HH partition value (one hour per row), formatted via java.time. */
  def datePartitionLiteral(rowIndex: Int): String =
    DatePartitionEpoch.plusHours((rowIndex - 1).toLong).format(DatePartitionFormat)
}

// A schema exercising complex/nested types: a struct, an array, a map, and a struct-in-struct.
// Struct/array read back as Row/Seq; map as a Map. `id` is first so it is the ordering key.
object NestedTable extends Schema {
  val id:     Column[Long]            = Column("id",     "bigint",                      rowIndex => rowIndex.toString)
  val s:      Column[Row]             = Column("s",      "struct<x:int,y:string>",      rowIndex => s"named_struct('x', $rowIndex, 'y', 'row-$rowIndex')")
  val arr:    Column[Seq[Int]]        = Column("arr",    "array<int>",                  rowIndex => s"array($rowIndex, ${rowIndex + 1})")
  val m:      Column[Map[String, Int]] = Column("m",     "map<string,int>",             rowIndex => s"map('k', $rowIndex)")
  val nested: Column[Row]             = Column("nested", "struct<inner:struct<z:int>>", rowIndex => s"named_struct('inner', named_struct('z', $rowIndex))")
  def tableColumns: Seq[Column[_]] = Seq(id, s, arr, m, nested)

  val columnDefinitions: String =
    "id bigint, s struct<x:int,y:string>, arr array<int>, m map<string,int>, nested struct<inner:struct<z:int>>"
}

// A schema for type-edge coverage: the common scalar types, exercised with nulls, special float
// values, boundary values, and unicode/empty strings.
object TypesTable extends Schema {
  val id:    Column[Long]   = Column("id",    "bigint",        rowIndex => rowIndex.toString)
  val n:     Column[Int]    = Column("n",     "int",           rowIndex => rowIndex.toString)
  val x:     Column[Double] = Column("x",     "double",        rowIndex => s"$rowIndex.5")
  val dec:   Column[java.math.BigDecimal] = Column("dec", "decimal(10,2)", rowIndex => s"CAST($rowIndex.50 AS decimal(10,2))")
  val str:   Column[String] = Column("str",   "string",        rowIndex => s"'row-$rowIndex'")
  val bin:   Column[Array[Byte]] = Column("bin", "binary",     rowIndex => s"CAST('bin-$rowIndex' AS binary)")
  val dt:    Column[java.sql.Date] = Column("dt", "date",      rowIndex => s"DATE '2024-01-0$rowIndex'")
  val ts:    Column[java.sql.Timestamp] = Column("ts", "timestamp", rowIndex => s"TIMESTAMP '2024-01-01 0$rowIndex:00:00'")
  val tsntz: Column[java.time.LocalDateTime] = Column("tsntz", "timestamp_ntz", rowIndex => s"TIMESTAMP_NTZ '2024-01-01 0$rowIndex:00:00'")
  def tableColumns: Seq[Column[_]] = Seq(id, n, x, dec, str, bin, dt, ts, tsntz)

  val columnDefinitions: String =
    "id bigint, n int, x double, dec decimal(10,2), str string, bin binary, dt date, ts timestamp, tsntz timestamp_ntz"
}

object RowGenerator {
  /** VALUES clause for `numberOfRows` deterministic rows, one literal per column. */
  def valuesClause(schema: Schema, numberOfRows: Int): String =
    (1 to numberOfRows).map { rowIndex =>
      schema.tableColumns.map(column => column.literalAt(rowIndex)).mkString("(", ", ", ")")
    }.mkString("VALUES ", ", ", "")
}

/**
 * What a step's validation thunk sees: the live table, its rows before and after the step, and
 * the table's snapshot (commit) count before and after — so a test can assert the delta in both
 * data and commits (e.g. "a no-match UPDATE still commits exactly one snapshot").
 */
final case class StepView[S <: Schema](
  spark:           SparkSession,
  table:           String,
  schema:          S,
  before:          Seq[Row],
  after:           Seq[Row],
  snapshotsBefore: Long,
  snapshotsAfter:  Long
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

  // The default validator asserts the seed actually appended `numberOfRows` rows. This defends the
  // relative-delta operation assertions from a vacuous pass on an empty/short baseline.
  def insert(numberOfRows: Int)(
      validate: StepView[S] => Unit = view => assert(
        view.after.size == view.before.size + numberOfRows,
        s"seed insert($numberOfRows) expected ${view.before.size + numberOfRows} rows, got ${view.after.size}")
  ): TableTest[S] =
    add(Step(s"insert($numberOfRows)", (spark, table, schema) =>
      spark.sql(s"INSERT INTO $table ${RowGenerator.valuesClause(schema, numberOfRows)}"), validate))

  def delete(predicate: S => String)(validate: StepView[S] => Unit = _ => ()): TableTest[S] =
    add(Step("delete", (spark, table, schema) =>
      spark.sql(s"DELETE FROM $table WHERE ${predicate(schema)}"), validate))

  /** General operation step: run an arbitrary mutation on the table, then validate the delta. */
  def step(label: String)(mutate: (SparkSession, String) => Unit)
          (validate: StepView[S] => Unit = _ => ()): TableTest[S] =
    add(Step(label, (spark, table, _) => mutate(spark, table), validate))

  /** Operation step whose mutation is a single SQL statement (the table name is supplied). */
  def sql(label: String)(statement: String => String)
         (validate: StepView[S] => Unit = _ => ()): TableTest[S] =
    step(label)((spark, table) => spark.sql(statement(table)))(validate)

  /** Read/assert-only step: no mutation, so before == after; used for the read paths. */
  def check(label: String)(validate: StepView[S] => Unit): TableTest[S] =
    step(label)((_, _) => ())(validate)

  // Execute the pipeline on a fresh, always-dropped table. Each step's `before` is the previous
  // step's `after` (an empty/zero baseline for the first step), so rows and commits are only ever
  // read AFTER a step has run — on a table a prior step created. There is no existence guard: a
  // query against a missing table loudly fails, which is the correct behavior.
  def run(ctx: Ctx): Unit = withTable(ctx) { table =>
    steps.foldLeft((Seq.empty[Row], 0L)) { case ((beforeRows, beforeSnapshots), step) =>
      step.execute(ctx.spark, table, schema)
      val afterRows = currentRows(ctx.spark, table)
      val afterSnapshots = snapshotCount(ctx.spark, table)
      step.validate(StepView(ctx.spark, table, schema, beforeRows, afterRows, beforeSnapshots, afterSnapshots))
      (afterRows, afterSnapshots)
    }
  }

  // The one table-lifecycle primitive: hand `use` a fresh table name and always drop it afterward.
  // The teardown drop is guarded so a drop failure can't mask the real failure from `use`.
  private def withTable(ctx: Ctx)(use: String => Unit): Unit = {
    val table = s"${ctx.namespace}.t_${TableTest.counter.incrementAndGet()}"
    ctx.spark.sql(s"DROP TABLE IF EXISTS $table") // ensure absent
    try use(table)
    finally try ctx.spark.sql(s"DROP TABLE IF EXISTS $table") catch { case NonFatal(_) => () }
  }

  // Rows selected by the schema's columns, ordered by the key (first) column for deterministic
  // comparison. Ordering by the key (not all columns) keeps this valid for schemas with columns
  // that aren't orderable, e.g. a map.
  private def currentRows(spark: SparkSession, table: String): Seq[Row] = {
    val columns = schema.columnNames.mkString(", ")
    spark.sql(s"SELECT $columns FROM $table ORDER BY ${schema.columnNames.head}").collect().toSeq
  }

  private def snapshotCount(spark: SparkSession, table: String): Long =
    spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0)
}

object TableTest {
  private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
  def apply[S <: Schema](schema: S): TableTest[S] = new TableTest(schema, Vector.empty)
}

/**
 * The concrete tests, all on CoreTable. An operation is a HEADLESS pipeline segment (no create);
 * the run crosses every operation with every `Layout` by composing `createAndSeed(layout)` before
 * it via `andThen`. Every operation asserts the DELTA against the observed pre-state (rows and/or
 * commit count), never an absolute row set — so a test holds under any layout. Operation sources
 * are written as EXPLICIT literals.
 */
object Scenarios {
  import Rows._

  private val Core = CoreTable            // brevity in the typed column references below
  private val cols = Core.columnNames.mkString(", ") // source column list, so renames propagate

  // Short typed views of the current rows, keyed by the long column, for incremental assertions.
  private def keyed(rows: Seq[Row]): Seq[Long] = rows.map(_.get(Core.long0)).sorted
  private def longToString(rows: Seq[Row]): Map[Long, String] =
    rows.map(row => row.get(Core.long0) -> row.get(Core.string0)).toMap

  // ── the layout axis: file format x partitioning, crossed with every operation ──────────
  // Each layout is a plain literal CREATE statement (no dynamic assembly): the column list is one
  // shared literal `columnDefinitions`, and format/partition are literal fragments. createSchema
  // cross-checks the literal against CoreTable's declared columns, so the two can't silently drift.
  private val columnDefinitions =
    "foo_col_long bigint, foo_col_int int, foo_col_string string, foo_col_double double, foo_col_boolean boolean, datepartition string"

  final case class Layout(label: String, create: String => String)

  private val partitionVariants = List("unpartitioned" -> "", "partitioned" -> "PARTITIONED BY (datepartition)")

  val layouts: List[Layout] =
    for {
      format                        <- List("parquet", "orc", "avro")
      (partitionLabel, partitionClause) <- partitionVariants
    } yield Layout(s"$partitionLabel/$format", table =>
      s"CREATE TABLE $table ($columnDefinitions) USING iceberg $partitionClause " +
        s"TBLPROPERTIES ('write.format.default'='$format')")

  // Merge-on-read layouts: same shapes, but DELETE/UPDATE/MERGE write position-delete files
  // (format v2) instead of rewriting data files. Crossed with the mutation operations only.
  val morLayouts: List[Layout] =
    for {
      format                        <- List("parquet", "orc", "avro")
      (partitionLabel, partitionClause) <- partitionVariants
    } yield Layout(s"mor-$partitionLabel/$format", table =>
      s"CREATE TABLE $table ($columnDefinitions) USING iceberg $partitionClause " +
        s"TBLPROPERTIES ('write.format.default'='$format', 'format-version'='2', " +
        s"'write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read', 'write.merge.mode'='merge-on-read')")

  // Dedicated layouts for the CoW/MoR *physical* discriminator (below). Both pin
  // `write.distribution-mode=none` and are unpartitioned so a single seed INSERT lands all rows in
  // ONE data file; deleting a strict subset is then necessarily a PARTIAL-file match, which Iceberg
  // cannot satisfy by whole-file elimination. That makes the physical outcome deterministic: MoR
  // must add a position-delete file, CoW must rewrite the data file and add none. (The general
  // `morLayouts` seed splits across files, so a boundary-aligned delete can legitimately drop a
  // whole file with no position delete — correct Iceberg behaviour, but not what we want to pin.)
  val morVerifyLayouts: List[Layout] =
    List("parquet", "orc", "avro").map(format => Layout(s"mor-verify/$format", table =>
      s"CREATE TABLE $table ($columnDefinitions) USING iceberg TBLPROPERTIES (" +
        s"'write.format.default'='$format', 'format-version'='2', 'write.distribution-mode'='none', " +
        s"'write.delete.mode'='merge-on-read')"))

  val cowVerifyLayouts: List[Layout] =
    List("parquet", "orc", "avro").map(format => Layout(s"cow-verify/$format", table =>
      s"CREATE TABLE $table ($columnDefinitions) USING iceberg TBLPROPERTIES (" +
        s"'write.format.default'='$format', 'format-version'='2', 'write.distribution-mode'='none', " +
        s"'write.delete.mode'='copy-on-write')"))

  // Preparation: create under `layout` and seed `numberOfRows` deterministic rows. Interchangeable
  // with RTAS / drop+undrop preparations later — same resulting state.
  def createAndSeed(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(layout.create)().insert(numberOfRows)()

  // Preparation for the physical CoW/MoR discriminator: seed all rows into ONE data file. A plain
  // seed INSERT fans the rows across a couple of files (writer-dependent), so a strict-subset delete
  // can land on a whole file and be satisfied by file elimination rather than a position delete. The
  // `COALESCE(1)` hint forces a single write task → a single data file, so deleting a strict subset
  // is deterministically a PARTIAL-file match: MoR must add a position-delete file, CoW must rewrite.
  def createAndSeedSingleFile(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(layout.create)()
      .sql(s"seed($numberOfRows, one-file)")(table =>
        s"INSERT INTO $table SELECT /*+ COALESCE(1) */ * FROM (${RowGenerator.valuesClause(Core, numberOfRows)}) AS seed")(
        view => assert(view.after.size == numberOfRows,
          s"single-file seed expected $numberOfRows rows, got ${view.after.size}"))

  // Phase 24 preparation multipliers: a DDL evolves the starting state, then a DML op runs on it.
  // Ordered prep (sort order) is arity-neutral → crosses ALL operations. Evolved prep adds a column
  // → INSERT arity changes, so it crosses only ops that don't re-insert all columns (delete/update/read).
  def createAndSeedOrdered(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    createAndSeed(layout, numberOfRows).sql("prep.ordered")(t => s"ALTER TABLE $t WRITE ORDERED BY ${CoreTable.long0.columnName}")()

  def createAndSeedEvolved(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    createAndSeed(layout, numberOfRows).sql("prep.evolved")(t => s"ALTER TABLE $t ADD COLUMN prep_extra int")()

  // Branch-routing prep (the T axis, wap-conf mechanism): seed on main, fork a branch, then set
  // spark.wap.branch so the ENTIRE downstream operation (writes AND reads) routes to the branch —
  // no per-op rewrite needed. The op's delta assertions are relative to view.before (also the
  // branch), so they hold unchanged. Each case runs in its own spark.newSession() (parallel runner),
  // so the conf never leaks across cases. This crosses the whole DML catalog onto a branch.
  def createAndSeedOnBranch(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    createAndSeed(layout, numberOfRows)
      .sql("prep.enableWap")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .step("prep.routeToBranch") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE BRANCH b")
        spark.conf.set("spark.wap.branch", "b")
      }()

  // RTAS prep prefix (the P axis, replace-lineage leg — SURFACE-APPRAISAL step 2): create + seed,
  // then CREATE OR REPLACE ... AS SELECT * re-specifying the SAME shape, so the table is
  // functionally identical but reached via the replace path (the path G9/G10 showed misbehaves).
  // Every downstream DML op then runs on a replace-lineage table. Crossed with ORC + Parquet (format
  // policy: test both, no single-format pruning). (label, partitionClause, format).
  val rtasPrepShapes: List[(String, String, String)] =
    for { (pl, pc) <- partitionVariants; fmt <- List("parquet", "orc") } yield (s"$pl/$fmt", pc, fmt)

  // MoR-read prep (closes the review's "reads on MoR with deletes is a distinct scan path" gap —
  // SURFACE-APPRAISAL step 1). The current MoR bucket runs mutation ops (each reads back once), but
  // never crosses the READ variants against a table carrying a LIVE position delete. Seed a single
  // data file (COALESCE(1)) on a MoR layout, delete a strict subset → a position-delete file the
  // reader must APPLY at scan time (not a whole-file elimination). Downstream read ops then assert
  // the deleted row is excluded under each read shape (projection, filter-pushdown, ...).
  def createAndSeedMorDeleted(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    createAndSeedSingleFile(layout, numberOfRows)
      .step("prep.morDelete") { (spark, table) =>
        spark.sql(s"DELETE FROM $table WHERE ${Core.long0.columnName} = 1")   // strict subset → position delete
      } { view =>
        assert(view.after.size == numberOfRows - 1, s"MoR prep delete failed: ${view.after.size}")
        val deleteFiles = view.spark.sql(s"SELECT count(*) FROM ${view.table}.all_delete_files").collect()(0).getLong(0)
        assert(deleteFiles == 1, s"MoR prep must leave a live position-delete file, got $deleteFiles")
      }

  // Undrop prep (the P axis, drop→undrop leg — SURFACE-APPRAISAL, requires embedded real HTS). Seed a
  // plain table, then take it through the FULL soft-delete → restore round-trip on the real HTS, and
  // hand the RESTORED table to the downstream op. The point is a modality audit: every feature's state
  // (rows, snapshot lineage, refs, spec, sort order, properties, MoR delete files, schema) must survive
  // the round-trip, so the whole DML/DDL catalog is crossed onto the restored table. Soft-delete is
  // driven directly on HTS (customer DROP hard-deletes); restore uses the customer Tables API.
  def createAndSeedUndropped(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    createAndSeed(layout, numberOfRows)
      .step("prep.undrop") { (spark, table) =>
        val Array(db, tbl) = table.stripPrefix("openhouse.").split("\\.", 2)
        val (sdCode, sdBody) = HtsAdmin.softDelete(db, tbl)
        assert(sdCode >= 200 && sdCode < 300, s"HTS soft-delete failed ($sdCode): $sdBody")
        val deletedAtMs = HtsAdmin.softDeletedAtMs(db, tbl)
          .getOrElse(throw new AssertionError(s"soft-deleted table $db.$tbl not found in querySoftDeleted"))
        val (rCode, rBody) = HtsAdmin.restore(db, tbl, deletedAtMs)
        assert(rCode >= 200 && rCode < 300, s"restore failed ($rCode): $rBody")
      } { view =>
        assert(view.after.size == numberOfRows,
          s"restored table must keep its $numberOfRows rows, got ${view.after.size}")
      }

  def createAndSeedRtas(partitionClause: String, numberOfRows: Int, format: String = "parquet"): TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg $partitionClause " +
        s"TBLPROPERTIES ('write.format.default'='$format', 'replace.enabled'='true')")()
      .insert(numberOfRows)()
      .sql("prep.rtas")(t => s"CREATE OR REPLACE TABLE $t USING iceberg $partitionClause " +
        s"TBLPROPERTIES ('write.format.default'='$format') AS SELECT * FROM $t")()

  // RTAS prep on a MERGE-ON-READ table (over-prune miss #1): the replace re-specifies the MoR delete/
  // update/merge modes, so downstream mutation ops exercise the MoR write path on a replace-lineage
  // table. Non-vacuous per the appraisal — replace + MoR is a distinct combination.
  private def morPropsFmt(format: String) = s"'write.format.default'='$format', 'format-version'='2', " +
    "'write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read', 'write.merge.mode'='merge-on-read'"
  private val morProps = morPropsFmt("parquet")

  def createAndSeedRtasMor(partitionClause: String, numberOfRows: Int, format: String = "parquet"): TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg $partitionClause " +
        s"TBLPROPERTIES (${morPropsFmt(format)}, 'replace.enabled'='true')")()
      .insert(numberOfRows)()
      .sql("prep.rtasMor")(t => s"CREATE OR REPLACE TABLE $t USING iceberg $partitionClause " +
        s"TBLPROPERTIES (${morPropsFmt(format)}) AS SELECT * FROM $t")()

  // ── MoR delete-file coexistence battery (BUILD-STATUS task #5, the NON-vacuous core) ─────────
  // The appraisal's "core DML → L×M=12" is ~90% vacuous: a read/insert on a DELETE-FREE MoR table
  // is byte-identical to CoW (no delete files to apply; append is mode-independent). The mutation
  // ops ARE crossed with MoR already (the `mor` bucket, 264). The genuinely-new MoR surface is
  // operating on a table that ALREADY carries a live position-delete file — data-file/delete-file
  // COEXISTENCE. `createAndSeedMorDeleted` leaves 2 rows (keys 2,3) with a live delete for key 1;
  // these ops then act on that state.
  val morCoexistOps: List[(String, TableTest[CoreTable.type])] = List(
    // A new data file must coexist with the existing delete file; the read applies the delete to
    // OLD data only, not the appended rows.
    "coexist.append" -> TableTest(Core).step("coexist.append") { (spark, table) =>
      spark.sql(s"INSERT INTO $table VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "append over live delete file wrong count")
      assert(spark.sql(s"SELECT count(*) FROM $table WHERE ${Core.long0.columnName} = 1").collect()(0).getLong(0) == 0, "deleted row resurrected by append")
    }(),
    // A second delete adds a second position-delete file over the same data file.
    "coexist.secondDelete" -> TableTest(Core).step("coexist.secondDelete") { (spark, table) =>
      spark.sql(s"DELETE FROM $table WHERE ${Core.long0.columnName} = 2")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 1, "second delete over existing delete file wrong count")
      assert(spark.sql(s"SELECT count(*) FROM $table.all_delete_files").collect()(0).getLong(0) >= 1, "delete files missing after second delete")
    }(),
    // Update a surviving row while a delete file is live.
    "coexist.update" -> TableTest(Core).step("coexist.update") { (spark, table) =>
      spark.sql(s"UPDATE $table SET ${Core.string0.columnName} = 'cx' WHERE ${Core.long0.columnName} = 3")
      assert(spark.sql(s"SELECT ${Core.string0.columnName} FROM $table WHERE ${Core.long0.columnName} = 3").collect()(0).getString(0) == "cx", "update over live delete failed")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "update over live delete changed count")
    }(),
    // A filtered read must apply the position delete (the deleted key must never appear).
    "coexist.readFilter" -> TableTest(Core).step("coexist.readFilter") { (spark, table) =>
      val keys = spark.sql(s"SELECT ${Core.long0.columnName} FROM $table WHERE ${Core.long0.columnName} <= 2 ORDER BY ${Core.long0.columnName}").collect().toSeq.map(_.getLong(0))
      assert(keys == Seq(2L), s"filter must apply the position delete (key 1 gone): $keys")
    }(),
    // Compacting the position deletes materializes them; the row set is unchanged.
    "coexist.compactDeletes" -> TableTest(Core).step("coexist.compactDeletes") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.rewrite_position_delete_files(table => '${catalogRelative(table)}', options => map('rewrite-all', 'true'))")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "compact position deletes changed row set")
    }(),
    // Merge onto a table with a live delete file.
    "coexist.merge" -> TableTest(Core).step("coexist.merge") { (spark, table) =>
      spark.sql(s"MERGE INTO $table t USING (SELECT CAST(3 AS BIGINT) k) s ON t.${Core.long0.columnName} = s.k " +
        s"WHEN MATCHED THEN UPDATE SET ${Core.string0.columnName} = 'mg'")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "merge over live delete changed count")
      assert(spark.sql(s"SELECT ${Core.string0.columnName} FROM $table WHERE ${Core.long0.columnName} = 3").collect()(0).getString(0) == "mg", "merge over live delete failed")
    }()
  )

  // ── Maintenance × MoR-with-live-delete (BUILD-STATUS block 8 deepening) ──────────────────────
  // The maintenance.* block runs on plain CoW; the genuinely-distinct surface is maintenance over a
  // table that carries a LIVE position-delete file. `createAndSeedMorDeleted` leaves keys 2,3 live
  // with a live delete for key 1. The hunt: does each maintenance procedure handle the delete file
  // correctly (fold / preserve / not resurrect the deleted row)?

  // rewrite_data_files over a live position delete: it applies the delete to the rewritten data
  // (key 1 physically gone, row set correct) — but it does NOT remove the now-dangling position
  // delete from the CURRENT snapshot. FINDING G14 (characterization): the compacted table still
  // carries a live delete-file reference that points at data already removed; it lingers until
  // rewrite_position_delete_files or expire_snapshots. Reads stay correct throughout. Crossed × 3 MoR
  // formats to confirm the behavior is format-consistent (the delete decode differs per format).
  val maintenanceMorFoldOps: List[(String, TableTest[CoreTable.type])] = List(
    "maint.mor.rewriteDataFilesDanglingDelete" -> TableTest(Core).step("maint.mor.rewriteDataFilesDanglingDelete") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.rewrite_data_files(table => '${catalogRelative(table)}', options => map('rewrite-all', 'true'))")
      // the delete IS applied logically — row set is correct
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "rewrite_data_files changed the live row set over a MoR delete")
      assert(spark.sql(s"SELECT count(*) FROM $table WHERE ${Core.long0.columnName} = 1").collect()(0).getLong(0) == 0, "rewrite_data_files RESURRECTED the deleted row")
      // G14 PIN: the position delete is NOT removed from the current snapshot — it dangles.
      val delFiles = spark.sql(s"SELECT count(*) FROM $table.delete_files").collect()(0).getLong(0)
      assert(delFiles == 1, s"characterized: rewrite_data_files leaves the position delete dangling in the current snapshot (expected 1), got $delFiles — if this is 0, the build now folds deletes and the pin should flip")
      // despite the dangling delete, reads remain correct (the removed row never reappears)
      val keys = spark.sql(s"SELECT ${Core.long0.columnName} FROM $table WHERE ${Core.long0.columnName} <= 2 ORDER BY ${Core.long0.columnName}").collect().toSeq.map(_.getLong(0))
      assert(keys == Seq(2L), s"read after rewrite_data_files must stay correct despite the dangling delete: $keys")
    }()
  )

  // Metadata-only maintenance over a live delete — format is vacuous (these never decode the delete
  // file), so × 1 MoR layout. Each must PRESERVE the delete (2 live rows, key 1 still gone).
  val maintenanceMorMetaOps: List[(String, TableTest[CoreTable.type])] = List(
    "maint.mor.expireSnapshots" -> TableTest(Core).step("maint.mor.expireSnapshots") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "expire_snapshots changed the live row set over a MoR delete")
      assert(spark.sql(s"SELECT count(*) FROM $table WHERE ${Core.long0.columnName} = 1").collect()(0).getLong(0) == 0, "expire_snapshots resurrected the deleted row")
    }(),
    "maint.mor.rewriteManifests" -> TableTest(Core).step("maint.mor.rewriteManifests") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.rewrite_manifests(table => '${catalogRelative(table)}', use_caching => false)")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "rewrite_manifests changed the live row set over a MoR delete")
    }(),
    "maint.mor.removeOrphanFiles" -> TableTest(Core).step("maint.mor.removeOrphanFiles") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.remove_orphan_files(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2020-01-01 00:00:00')")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "remove_orphan_files changed the live row set over a MoR delete")
    }(),
    // Modality: compact the position deletes, THEN expire the pre-compact snapshot — the folded
    // state must survive (the deleted row must not reappear via the retained/expired lineage).
    "maint.mor.compactThenExpire" -> TableTest(Core).step("maint.mor.compactThenExpire") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.rewrite_position_delete_files(table => '${catalogRelative(table)}', options => map('rewrite-all', 'true'))")
      spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "compact-then-expire changed the live row set")
      assert(spark.sql(s"SELECT count(*) FROM $table WHERE ${Core.long0.columnName} = 1").collect()(0).getLong(0) == 0, "compact-then-expire resurrected the deleted row")
    }()
  )

  // ── MoR delete-file modality hazards (BUILD-STATUS block 10 deepening) ───────────────────────
  // A live position delete is snapshot-scoped state. These hunt for it being mis-resolved across the
  // history/restore axes: a delete must NOT be retroactive (pre-delete snapshots still see the row),
  // rollback must UNDO it, and it must SURVIVE expiration of older snapshots. Time-travel/rollback
  // logic is format-vacuous (it resolves snapshots, not file bytes) → × 1 MoR layout.
  val morHazardOps: List[(String, TableTest[CoreTable.type])] = List(
    // The delete is snapshot-scoped: time-travel to the pre-delete snapshot still sees key 1.
    "hazard.mor.timeTravelBeforeDelete" -> TableTest(Core).step("hazard.mor.timeTravelBeforeDelete") { (spark, table) =>
      val seedSnap = spark.sql(s"SELECT snapshot_id FROM $table.snapshots ORDER BY committed_at LIMIT 1").collect()(0).getLong(0)
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "current MoR state should have the delete applied")
      assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF $seedSnap").collect()(0).getLong(0) == 3,
        "pre-delete snapshot must still see the deleted row (delete must not be retroactive)")
    }(),
    // Rollback to the pre-delete snapshot UNDOES the delete — the row returns and no delete is live.
    "hazard.mor.rollbackUndoesDelete" -> TableTest(Core).step("hazard.mor.rollbackUndoesDelete") { (spark, table) =>
      val seedSnap = spark.sql(s"SELECT snapshot_id FROM $table.snapshots ORDER BY committed_at LIMIT 1").collect()(0).getLong(0)
      spark.sql(s"CALL openhouse.system.rollback_to_snapshot(table => '${catalogRelative(table)}', snapshot_id => ${seedSnap}L)")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "rollback did not undo the MoR delete")
      assert(spark.sql(s"SELECT count(*) FROM $table WHERE ${Core.long0.columnName} = 1").collect()(0).getLong(0) == 1, "rolled-back row not restored")
    }(),
    // The delete must SURVIVE expiration of the older (pre-delete) snapshot — a filtered read still
    // excludes key 1 after expire.
    "hazard.mor.expireThenDeleteHolds" -> TableTest(Core).step("hazard.mor.expireThenDeleteHolds") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
      val keys = spark.sql(s"SELECT ${Core.long0.columnName} FROM $table WHERE ${Core.long0.columnName} <= 2 ORDER BY ${Core.long0.columnName}").collect().toSeq.map(_.getLong(0))
      assert(keys == Seq(2L), s"delete must survive expiration of the pre-delete snapshot (key 1 gone): $keys")
    }()
  )

  // ── MoR × branch MERGE (position deletes carried across fast_forward / cherry_pick / REPLACE BRANCH) ──
  // A DELETE/UPDATE on a branch of a MoR table writes position-delete files ON THE BRANCH; merging the
  // branch back to main must carry those deletes correctly. This is the known-fragile neighborhood of
  // G11 (branch × merge) and the "cherry-pick rejects row-delete snapshots" note — the merge is where
  // MoR-branch breakage hides. Base is a single-file MoR seed (COALESCE(1)) so a strict-subset DELETE
  // is a real position delete, not a file elimination. Merge is a ref/snapshot carry → format-vacuous
  // (× 1 MoR layout). Each hunts for: deletes lost/not-carried, deleted rows resurrecting on main,
  // cherry-pick rejecting row-delete snapshots.
  val morBranchMergeOps: List[(String, TableTest[CoreTable.type])] = List(
    // fast_forward must carry a branch position-delete into main: after merge the deleted row is gone.
    "mbranch.fastForwardDelete" -> TableTest(Core).step("mbranch.fastForwardDelete") { (spark, table) =>
      spark.sql(s"ALTER TABLE $table CREATE BRANCH mfb")
      spark.sql(s"DELETE FROM $table.branch_mfb WHERE ${Core.long0.columnName} = 1")   // position delete on branch
      assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "main advanced before merge")
      assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'mfb'") == "2", "branch delete not applied on the branch")
      spark.sql(s"CALL openhouse.system.fast_forward('${catalogRelative(table)}', 'main', 'mfb')")
      assert(countOf(spark, s"SELECT count(*) FROM $table") == "2", "fast_forward did not carry the branch position-delete to main")
      assert(countOf(spark, s"SELECT count(*) FROM $table WHERE ${Core.long0.columnName} = 1") == "0", "deleted row resurrected on main after fast_forward")
    }(),
    // fast_forward must carry a branch UPDATE (MoR update = position delete + new data file).
    "mbranch.fastForwardUpdate" -> TableTest(Core).step("mbranch.fastForwardUpdate") { (spark, table) =>
      spark.sql(s"ALTER TABLE $table CREATE BRANCH mub")
      spark.sql(s"UPDATE $table.branch_mub SET ${Core.string0.columnName} = 'br-upd' WHERE ${Core.long0.columnName} = 2")
      spark.sql(s"CALL openhouse.system.fast_forward('${catalogRelative(table)}', 'main', 'mub')")
      assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "fast_forward of a MoR update changed the row count on main")
      assert(spark.sql(s"SELECT ${Core.string0.columnName} FROM $table WHERE ${Core.long0.columnName} = 2").collect()(0).getString(0) == "br-upd",
        "MoR update not carried to main by fast_forward")
    }(),
    // Cherry-pick a branch ROW-DELETE snapshot onto main — CHARACTERIZE (the fragile path): it either
    // applies the delete (main → 2) or is rejected; pin the outcome and assert the row set matches it.
    "mbranch.cherrypickDelete" -> TableTest(Core).step("mbranch.cherrypickDelete") { (spark, table) =>
      spark.sql(s"ALTER TABLE $table CREATE BRANCH mcb")
      spark.sql(s"DELETE FROM $table.branch_mcb WHERE ${Core.long0.columnName} = 1")
      val delSnap = spark.sql(s"SELECT snapshot_id FROM $table.snapshots ORDER BY committed_at DESC LIMIT 1").collect()(0).getLong(0)
      val outcome =
        try { spark.sql(s"CALL openhouse.system.cherrypick_snapshot('${catalogRelative(table)}', ${delSnap}L)"); "ok" }
        catch { case NonFatal(e) => s"rejected:${Exceptions.root(e).getClass.getSimpleName}" }
      val mainCount = countOf(spark, s"SELECT count(*) FROM $table")
      println(s"DIAG mbranch.cherrypickDelete: $outcome, mainCount=$mainCount")
      if (outcome == "ok")
        assert(mainCount == "2", s"cherrypick reported ok but did not apply the branch delete to main (got $mainCount)")
      else
        assert(mainCount == "3", s"cherrypick was rejected but main changed anyway (got $mainCount)")
    }(),
    // REPLACE BRANCH retargets a MoR branch to a pre-delete snapshot — the delete must follow the target.
    "mbranch.replaceBranchDelete" -> TableTest(Core).step("mbranch.replaceBranchDelete") { (spark, table) =>
      val preSnap = spark.sql(s"SELECT snapshot_id FROM $table.snapshots ORDER BY committed_at DESC LIMIT 1").collect()(0).getLong(0) // seed (3 rows)
      spark.sql(s"ALTER TABLE $table CREATE BRANCH mrb")
      spark.sql(s"DELETE FROM $table.branch_mrb WHERE ${Core.long0.columnName} = 1")
      assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'mrb'") == "2", "branch delete not applied")
      spark.sql(s"ALTER TABLE $table REPLACE BRANCH mrb AS OF VERSION $preSnap")
      assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'mrb'") == "3",
        "REPLACE BRANCH to the pre-delete snapshot did not undo the branch position-delete")
    }()
  )

  // Encryption capability PIN (characterization). OpenHouse delegates table-data encryption to an
  // external KMS plugin (private repo); in OSS the catalog never wires a KeyManagementClient, so
  // customer tables use the default PlaintextEncryptionManager and data is written UNENCRYPTED.
  // Discriminator: a Parquet file's FOOTER magic is "PAR1" when unencrypted and "PARE" under modular
  // encryption — robust regardless of compression. This pins that OSS writes plaintext; it FLIPS to
  // "PARE" the moment table-data encryption is wired (then update BUGS.md and this pin). An off-the-
  // shelf KMS does NOT change this — nothing in the OpenHouse write path invokes the encryption hook.
  val encryptionPlaintextPin: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.pin.dataPlaintext") { (spark, table) =>
        val path = spark.sql(s"SELECT file_path FROM $table.data_files LIMIT 1").collect()(0).getString(0)
        val local = path.stripPrefix("file:")
        val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(local))
        assert(bytes.length >= 8, s"data file too small to inspect: ${bytes.length} bytes")
        val footerMagic = new String(bytes.takeRight(4), "US-ASCII")
        assert(footerMagic == "PAR1",
          s"expected UNENCRYPTED parquet footer magic PAR1 (OSS encryption is un-wired — capability gap, BUGS.md); " +
          s"got '$footerMagic' — if 'PARE', table-data encryption is now active and this pin should flip to assert ciphertext")
      }()

  // ── DDL × consumer battery (BUILD-STATUS task #3) ────────────────────────────────────────────
  // A DDL op is a STATE CHANGE; the battery asserts every consumer still works after it (the
  // modality thesis at the DDL level). DDL preps leave a distinct post-state; consumers are
  // arity-safe (they use SELECT * / metadata tables, never a fixed column list) so they compose
  // over ANY post-DDL schema. NOTE: this is the NON-VACUOUS core — the appraisal's 420 assumed
  // 35 DDL (incl. negatives/one-shots) × 6, but a rejected DDL or a rename has no post-state for a
  // consumer to exercise. State-changing DDL × real consumers is ~54, and that's what's built.
  val ddlPreps: List[(String, Layout => TableTest[CoreTable.type])] = List(
    "addColumn"  -> (l => createAndSeed(l, 3).sql("ddl")(t => s"ALTER TABLE $t ADD COLUMN cc int")()),
    "typeWiden"  -> (l => createAndSeed(l, 3).sql("ddl")(t => s"ALTER TABLE $t ALTER COLUMN ${Core.int0.columnName} TYPE bigint")()),
    "writeOrder" -> (l => createAndSeed(l, 3).sql("ddl")(t => s"ALTER TABLE $t WRITE ORDERED BY ${Core.long0.columnName}")()),
    "distMode"   -> (l => createAndSeed(l, 3).sql("ddl")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.distribution-mode'='range')")())
  )

  private def dupRow(key: Long) = s"SELECT * FROM %s WHERE ${Core.long0.columnName} = $key"  // arity-safe append source

  val ddlConsumers: List[(String, TableTest[CoreTable.type])] = List(
    // C1 the table stays WRITABLE (append) after the DDL — arity-safe self-select append.
    "dmlWrite" -> TableTest(Core).step("consume.dmlWrite") { (spark, table) =>
      spark.sql(s"INSERT INTO $table ${dupRow(1).format(table)}")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 4, "not writable post-DDL")
    }(),
    // C2 the MUTATION path still works after the DDL.
    "dmlMutate" -> TableTest(Core).step("consume.dmlMutate") { (spark, table) =>
      spark.sql(s"DELETE FROM $table WHERE ${Core.long0.columnName} = 2")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "mutation broken post-DDL")
    }(),
    // C3 TIME TRAVEL to the pre-DDL/seed snapshot still resolves.
    "timeTravel" -> TableTest(Core).step("consume.timeTravel") { (spark, table) =>
      val s0 = snapshotIds(spark, table).head
      assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF $s0").collect()(0).getLong(0) == 3,
        "pre-DDL snapshot not travelable")
    }(),
    // C4 RESTORE across the DDL: write post-DDL, then roll back to the seed snapshot.
    "restore" -> TableTest(Core).step("consume.restore") { (spark, table) =>
      val s0 = snapshotIds(spark, table).head
      spark.sql(s"INSERT INTO $table ${dupRow(1).format(table)}")
      spark.sql(s"CALL openhouse.system.rollback_to_snapshot('${catalogRelative(table)}', $s0)")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "restore across DDL failed")
    }(),
    // C5 EXPIRE after the DDL: history trims, current data survives and reads.
    "expire" -> TableTest(Core).step("consume.expire") { (spark, table) =>
      spark.sql(s"INSERT INTO $table ${dupRow(1).format(table)}")
      spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 4, "unreadable after expire post-DDL")
    }(),
    // C6 BRANCH after the DDL: branchable, write on branch, main isolated.
    "branch" -> TableTest(Core).step("consume.branch") { (spark, table) =>
      spark.sql(s"ALTER TABLE $table CREATE BRANCH cb")
      spark.sql(s"INSERT INTO $table.branch_cb ${dupRow(1).format(table)}")
      assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF 'cb'").collect()(0).getLong(0) == 4, "branch write failed post-DDL")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "branch leaked to main post-DDL")
    }(),
    // C7 COMPACTION after the DDL: a second data file, then rewrite_data_files preserves the rows.
    "compact" -> TableTest(Core).step("consume.compact") { (spark, table) =>
      spark.sql(s"INSERT INTO $table ${dupRow(1).format(table)}")   // second data file
      spark.sql(s"CALL openhouse.system.rewrite_data_files(table => '${catalogRelative(table)}', options => map('min-input-files', '2'))")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 4, "compaction changed rows post-DDL")
    }()
  )

  // Closing assertion for the branch axis: after the branch-routed op, MAIN must be untouched
  // (still the 3-row seed) — the isolation half of the branch contract. Uniform across all ops
  // because with spark.wap.branch set every write routes to the branch, never to main.
  val branchMainIsolation: TableTest[CoreTable.type] =
    TableTest(Core).step("branch.mainIsolated") { (spark, table) =>
      spark.conf.unset("spark.wap.branch")
      val mainCount = spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0)
      assert(mainCount == 3, s"branch op leaked to MAIN — expected 3 rows, got $mainCount (isolation broken)")
    }()

  // ── reads ────────────────────────────────────────────────────────────────────────────
  val readProjection: TableTest[CoreTable.type] =
    TableTest(Core).check("read.projection") { view =>
      val expected = view.before.sortBy(_.get(Core.long0)).map(_.get(Core.string0))
      val actual = view.spark
        .sql(s"SELECT ${Core.string0.columnName} FROM ${view.table} ORDER BY ${Core.long0.columnName}")
        .collect().toSeq.map(_.get(Core.string0))
      assert(actual == expected)
    }

  val readFilter: TableTest[CoreTable.type] =
    TableTest(Core).check("read.filter") { view =>
      val expected = view.before.map(_.get(Core.long0)).filter(_ >= 2).sorted
      val actual = view.spark
        .sql(s"SELECT ${Core.long0.columnName} FROM ${view.table} WHERE ${Core.long0.columnName} >= 2 ORDER BY ${Core.long0.columnName}")
        .collect().toSeq.map(_.get(Core.long0))
      assert(actual == expected)
    }

  // The declared write format actually materializes: every data file carries that extension.
  val formatMaterialization: TableTest[CoreTable.type] =
    TableTest(Core).check("format.materialization") { view =>
      val format = view.spark.sql(s"SHOW TBLPROPERTIES ${view.table} ('write.format.default')").collect()(0).getString(1)
      val paths = view.spark.sql(s"SELECT file_path FROM ${view.table}.files").collect().toSeq.map(_.getString(0))
      assert(paths.nonEmpty && paths.forall(_.toLowerCase.endsWith(s".$format")), s"data files are not all .$format: $paths")
    }

  // ── delete ───────────────────────────────────────────────────────────────────────────
  val deleteByPredicate: TableTest[CoreTable.type] =
    TableTest(Core).delete(core => s"${core.long0.columnName} < 2") { view =>
      assert(view.after == view.before.filterNot(_.get(Core.long0) < 2))
    }

  val deleteWhereFalseKeepsSnapshot: TableTest[CoreTable.type] =
    TableTest(Core).delete(_ => "false") { view =>
      assert(view.after == view.before)
      assert(view.snapshotsAfter == view.snapshotsBefore, "DELETE WHERE false must not commit a snapshot")
    }

  val truncate: TableTest[CoreTable.type] =
    TableTest(Core).sql("delete.truncate")(table => s"TRUNCATE TABLE $table") { view =>
      assert(view.after.isEmpty)
    }

  val deleteAtSnapshotRejected: TableTest[CoreTable.type] =
    TableTest(Core).step("delete.atSnapshot.rejected") { (spark, table) =>
      val snapshotId = spark
        .sql(s"SELECT snapshot_id FROM $table.snapshots ORDER BY committed_at DESC LIMIT 1")
        .collect()(0).getLong(0)
      val error = Check.intercept[IllegalArgumentException](
        spark.sql(s"DELETE FROM $table.snapshot_id_$snapshotId WHERE ${Core.long0.columnName} < 4"))
      assert(error.getMessage == s"Cannot delete from table at a specific snapshot: $snapshotId")
    } { view =>
      assert(view.after == view.before) // a rejected delete leaves the table unchanged
    }

  // Removes exactly the keys in the list.
  val deleteByInList: TableTest[CoreTable.type] =
    TableTest(Core).delete(core => s"${core.long0.columnName} IN (1, 3)") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filterNot(Set(1L, 3L)).sorted)
    }

  // Predicate is an IN-subquery over an explicit source.
  val deleteByInSubquery: TableTest[CoreTable.type] =
    TableTest(Core).delete(core =>
      s"${core.long0.columnName} IN (SELECT col1 FROM VALUES (CAST(2 AS BIGINT)) AS s(col1))") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filterNot(_ == 2L).sorted)
    }

  val deleteByNotInSubquery: TableTest[CoreTable.type] =
    TableTest(Core).delete(core =>
      s"${core.long0.columnName} NOT IN (SELECT col1 FROM VALUES (CAST(2 AS BIGINT)) AS s(col1))") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filter(_ == 2L).sorted)
    }

  val deleteByExistsSubquery: TableTest[CoreTable.type] =
    TableTest(Core).delete(core =>
      s"EXISTS (SELECT 1 FROM VALUES (CAST(2 AS BIGINT)) AS s(x) WHERE s.x = ${core.long0.columnName})") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filterNot(_ == 2L).sorted)
    }

  val deleteByNotExistsSubquery: TableTest[CoreTable.type] =
    TableTest(Core).delete(core =>
      s"NOT EXISTS (SELECT 1 FROM VALUES (CAST(2 AS BIGINT)) AS s(x) WHERE s.x = ${core.long0.columnName})") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filter(_ == 2L).sorted)
    }

  val deleteByScalarSubquery: TableTest[CoreTable.type] =
    TableTest(Core).delete(core =>
      s"${core.long0.columnName} = (SELECT max(col1) FROM VALUES (CAST(2 AS BIGINT)) AS s(col1))") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filterNot(_ == 2L).sorted)
    }

  // Seed a null-string row, then DELETE WHERE string IS NULL must remove exactly it (and nothing
  // else) — a real IS-NULL match, not a vacuous no-op.
  val deleteByNullCondition: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("delete.byNullCondition.seed")(table =>
        s"INSERT INTO $table VALUES (CAST(99 AS BIGINT), 99, NULL, 99.5, false, '2024-01-01-00')")()
      .delete(core => s"${core.string0.columnName} IS NULL") { view =>
        assert(view.before.exists(_.get(Core.string0) == null), "precondition: a null-string row was seeded")
        val expected = view.before.filterNot(_.get(Core.string0) == null).map(_.get(Core.long0)).sorted
        assert(keyed(view.after) == expected)                 // exactly the non-null rows remain
        assert(!keyed(view.after).contains(99L))              // the null-string row was removed
      }

  // DELETE with no WHERE clause empties the table.
  val deleteAll: TableTest[CoreTable.type] =
    TableTest(Core).sql("delete.all")(table => s"DELETE FROM $table") { view =>
      assert(view.after.isEmpty)
    }

  // A real predicate that matches nothing: rows unchanged, but one (empty) snapshot is still
  // committed — a scanned no-match, unlike the constant-folded `DELETE WHERE false` no-op above.
  val deleteNone: TableTest[CoreTable.type] =
    TableTest(Core).delete(core => s"${core.long0.columnName} = 999") { view =>
      assert(view.after == view.before)
      assert(view.snapshotsAfter == view.snapshotsBefore + 1, "no-match DELETE with a real predicate still commits one snapshot")
    }

  // A partition-column predicate (a metadata-only delete on a partitioned layout).
  val deleteByPartitionPredicate: TableTest[CoreTable.type] =
    TableTest(Core).delete(core => s"${core.datePartition.columnName} = '2024-01-01-00'") { view =>
      val expected = view.before.filterNot(_.get(Core.datePartition) == "2024-01-01-00").map(_.get(Core.long0)).sorted
      assert(keyed(view.after) == expected)
    }

  val deleteWithAlias: TableTest[CoreTable.type] =
    TableTest(Core).sql("delete.withAlias")(table =>
      s"DELETE FROM $table AS x WHERE x.${Core.long0.columnName} < 2") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filterNot(_ < 2L).sorted)
    }

  // ── update ───────────────────────────────────────────────────────────────────────────
  val updateByPredicate: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.byPredicate")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = 'X' WHERE ${Core.long0.columnName} = 2") { view =>
      val expected = longToString(view.before).map { case (id, s) => id -> (if (id == 2) "X" else s) }
      assert(longToString(view.after) == expected)
    }

  val updateWithoutCondition: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.withoutCondition")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = 'Z'") { view =>
      assert(longToString(view.after) == longToString(view.before).map { case (id, _) => id -> "Z" })
    }

  // A real predicate matching nothing still commits an (empty) snapshot — unlike the
  // constant-folded `DELETE WHERE false` no-op (confirmed vs OSS TestUpdate.testUpdateNonExistingRecords).
  val updateNoMatch: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.noMatch")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = 'Y' WHERE ${Core.long0.columnName} = 99") { view =>
      assert(longToString(view.after) == longToString(view.before))
      assert(view.snapshotsAfter == view.snapshotsBefore + 1, "no-match UPDATE still commits one snapshot")
    }

  private def stringUpdatedWhere(view: StepView[CoreTable.type], matches: Long => Boolean, to: String): Boolean =
    longToString(view.after) == longToString(view.before).map { case (id, s) => id -> (if (matches(id)) to else s) }

  val updateByInSubquery: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.byInSubquery")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = 'X' " +
        s"WHERE ${Core.long0.columnName} IN (SELECT col1 FROM VALUES (CAST(2 AS BIGINT)) AS s(col1))") { view =>
      assert(stringUpdatedWhere(view, _ == 2, "X"))
    }

  val updateByNotInSubquery: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.byNotInSubquery")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = 'X' " +
        s"WHERE ${Core.long0.columnName} NOT IN (SELECT col1 FROM VALUES (CAST(2 AS BIGINT)) AS s(col1))") { view =>
      assert(stringUpdatedWhere(view, _ != 2, "X"))
    }

  val updateByExistsSubquery: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.byExistsSubquery")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = 'X' " +
        s"WHERE EXISTS (SELECT 1 FROM VALUES (CAST(2 AS BIGINT)) AS s(x) WHERE s.x = ${Core.long0.columnName})") { view =>
      assert(stringUpdatedWhere(view, _ == 2, "X"))
    }

  val updateByNotExistsSubquery: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.byNotExistsSubquery")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = 'X' " +
        s"WHERE NOT EXISTS (SELECT 1 FROM VALUES (CAST(2 AS BIGINT)) AS s(x) WHERE s.x = ${Core.long0.columnName})") { view =>
      assert(stringUpdatedWhere(view, _ != 2, "X"))
    }

  val updateByScalarSubquery: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.byScalarSubquery")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = 'X' " +
        s"WHERE ${Core.long0.columnName} = (SELECT max(col1) FROM VALUES (CAST(2 AS BIGINT)) AS s(col1))") { view =>
      assert(stringUpdatedWhere(view, _ == 2, "X"))
    }

  val updateWithAlias: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.withAlias")(table =>
      s"UPDATE $table AS x SET x.${Core.string0.columnName} = 'X' WHERE x.${Core.long0.columnName} = 2") { view =>
      assert(stringUpdatedWhere(view, _ == 2, "X"))
    }

  // Sets two columns in one statement; assert both landed on the matched row.
  val updateMultipleColumns: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.multipleColumns")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = 'X', ${Core.int0.columnName} = 99 WHERE ${Core.long0.columnName} = 2") { view =>
      assert(stringUpdatedWhere(view, _ == 2, "X"))
      assert(view.after.find(_.get(Core.long0) == 2L).map(_.get(Core.int0)).contains(99))
    }

  // Assign a column by an expression over itself (updates the key column).
  val updateByExpression: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.byExpression")(table =>
      s"UPDATE $table SET ${Core.long0.columnName} = ${Core.long0.columnName} + 10 WHERE ${Core.long0.columnName} = 2") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).map(l => if (l == 2L) 12L else l).sorted)
    }

  // Update the partition column so the row moves partitions.
  val updateMovePartition: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.movePartition")(table =>
      s"UPDATE $table SET ${Core.datePartition.columnName} = '2099-12-31-23' WHERE ${Core.long0.columnName} = 2") { view =>
      val part = (rows: Seq[Row]) => rows.map(r => r.get(Core.long0) -> r.get(Core.datePartition)).toMap
      assert(part(view.after) == part(view.before).map { case (id, d) => id -> (if (id == 2) "2099-12-31-23" else d) })
    }

  val updateNullAssignment: TableTest[CoreTable.type] =
    TableTest(Core).sql("update.nullAssignment")(table =>
      s"UPDATE $table SET ${Core.string0.columnName} = NULL WHERE ${Core.long0.columnName} = 2") { view =>
      assert(longToString(view.after) == longToString(view.before).map { case (id, s) => id -> (if (id == 2) null else s) })
    }

  // ── merge ────────────────────────────────────────────────────────────────────────────
  // Source rows are written as EXPLICIT literals. The generator-sourced alternative for this
  // test would be:
  //   USING (${RowGenerator.valuesClause(Core, ...)} for indices 4,5) ... WHEN NOT MATCHED THEN INSERT *
  // i.e. name the row *indices* and let the column generators fill every column. We prefer the
  // explicit form so the source values are visible in the test.
  val mergeInsertNotMatched: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.insertNotMatched")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES
              (CAST(4 AS BIGINT), 4, 'row-4', 4.5, true,  '2024-01-04-03'),
              (CAST(5 AS BIGINT), 5, 'row-5', 5.5, false, '2024-01-05-04')
            AS s($cols)
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN NOT MATCHED THEN INSERT *""") { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) ++ Seq(4L, 5L)).sorted)
      // INSERT * must map the columns correctly, not just land the join key.
      assert(view.after.find(_.get(Core.long0) == 4L).map(_.get(Core.string0)).contains("row-4"))
      assert(view.after.find(_.get(Core.long0) == 5L).map(_.get(Core.string0)).contains("row-5"))
    }

  val mergeUpdateMatched: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.updateMatched")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES (CAST(2 AS BIGINT), 'M') AS s(${Core.long0.columnName}, ${Core.string0.columnName})
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN MATCHED THEN UPDATE SET t.${Core.string0.columnName} = s.${Core.string0.columnName}""") { view =>
      val expected = longToString(view.before).map { case (id, s) => id -> (if (id == 2) "M" else s) }
      assert(longToString(view.after) == expected)
    }

  val mergeDeleteMatched: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.deleteMatched")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES (CAST(1 AS BIGINT)), (CAST(3 AS BIGINT)) AS s(${Core.long0.columnName})
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN MATCHED THEN DELETE""") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filterNot(Set(1L, 3L)).sorted)
    }

  val mergeUpsert: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.upsert")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES
              (CAST(2 AS BIGINT), 2, 'U', 2.5, true,  '2024-01-02-01'),
              (CAST(7 AS BIGINT), 7, 'g', 7.5, false, '2024-01-07-06')
            AS s($cols)
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN MATCHED THEN UPDATE SET t.${Core.string0.columnName} = s.${Core.string0.columnName}
          WHEN NOT MATCHED THEN INSERT *""") { view =>
      val updated = longToString(view.before).map { case (id, s) => id -> (if (id == 2) "U" else s) }
      val withInsert = if (view.before.exists(_.get(Core.long0) == 7L)) updated else updated + (7L -> "g")
      assert(longToString(view.after) == withInsert)
    }

  // Keep only rows the source knows about: delete every row NOT matched by a source row.
  val mergeDeleteNotMatchedBySource: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.deleteNotMatchedBySource")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES (CAST(2 AS BIGINT)) AS s(${Core.long0.columnName})
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN NOT MATCHED BY SOURCE THEN DELETE""") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filter(_ == 2L).sorted)
    }

  // Both keys 2 and 3 match, but the per-clause condition only fires for key 2.
  val mergeConditionalUpdate: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.conditionalUpdate")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES (CAST(2 AS BIGINT), 'U2'), (CAST(3 AS BIGINT), 'U3')
            AS s(${Core.long0.columnName}, ${Core.string0.columnName})
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN MATCHED AND s.${Core.long0.columnName} = 2 THEN UPDATE SET t.${Core.string0.columnName} = s.${Core.string0.columnName}""") { view =>
      assert(longToString(view.after) == longToString(view.before).map { case (id, s) => id -> (if (id == 2) "U2" else s) })
    }

  // First matched clause wins: key 2 updates (conditional), key 3 falls through to DELETE.
  val mergeMultipleMatchedClauses: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.multipleMatchedClauses")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES (CAST(2 AS BIGINT), 'U'), (CAST(3 AS BIGINT), 'x')
            AS s(${Core.long0.columnName}, ${Core.string0.columnName})
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN MATCHED AND s.${Core.long0.columnName} = 2 THEN UPDATE SET t.${Core.string0.columnName} = s.${Core.string0.columnName}
          WHEN MATCHED THEN DELETE""") { view =>
      assert(keyed(view.after) == view.before.map(_.get(Core.long0)).filterNot(_ == 3L).sorted)
      assert(view.after.find(_.get(Core.long0) == 2L).map(_.get(Core.string0)).contains("U"))
    }

  // Conditional NOT MATCHED: source keys 4 and 5, but only 4 satisfies the insert condition.
  val mergeConditionalInsert: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.conditionalInsert")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES
              (CAST(4 AS BIGINT), 4, 'row-4', 4.5, true,  '2024-01-04-03'),
              (CAST(5 AS BIGINT), 5, 'row-5', 5.5, false, '2024-01-05-04')
            AS s($cols)
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN NOT MATCHED AND s.${Core.long0.columnName} = 4 THEN INSERT *""") { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) :+ 4L).sorted)
    }

  // All three clause kinds in one statement: update key 2, insert key 4, delete-by-source rows 1 & 3.
  val mergeAllClauses: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.allClauses")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES
              (CAST(2 AS BIGINT), 2, 'M2', 2.5, true,  '2024-01-02-01'),
              (CAST(4 AS BIGINT), 4, 'row-4', 4.5, false, '2024-01-04-03')
            AS s($cols)
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN MATCHED THEN UPDATE SET t.${Core.string0.columnName} = s.${Core.string0.columnName}
          WHEN NOT MATCHED THEN INSERT *
          WHEN NOT MATCHED BY SOURCE THEN DELETE""") { view =>
      assert(keyed(view.after) == Seq(2L, 4L))
      assert(view.after.find(_.get(Core.long0) == 2L).map(_.get(Core.string0)).contains("M2"))
    }

  // UPDATE SET * replaces every column of the matched row from the source.
  val mergeUpdateStar: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.updateStar")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES (CAST(2 AS BIGINT), 22, 'S2', 22.5, true, '2024-06-06-06') AS s($cols)
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN MATCHED THEN UPDATE SET *""") { view =>
      val row2 = view.after.find(_.get(Core.long0) == 2L)
      assert(row2.map(_.get(Core.string0)).contains("S2"))
      assert(row2.map(_.get(Core.int0)).contains(22))
    }

  // Explicit column-specification INSERT (other columns null-filled).
  val mergeInsertExplicitColumns: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.insertExplicitColumns")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES (CAST(7 AS BIGINT), 'g') AS s(${Core.long0.columnName}, ${Core.string0.columnName})
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN NOT MATCHED THEN INSERT (${Core.long0.columnName}, ${Core.string0.columnName}) VALUES (s.${Core.long0.columnName}, s.${Core.string0.columnName})""") { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) :+ 7L).sorted)
      assert(view.after.find(_.get(Core.long0) == 7L).map(_.get(Core.string0)).contains("g"))
    }

  // Source is a CTE.
  val mergeSourceCTE: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.sourceCTE")(table =>
      s"""MERGE INTO $table t USING (
            WITH src AS (SELECT CAST(8 AS BIGINT) AS ${Core.long0.columnName}) SELECT * FROM src
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN NOT MATCHED THEN INSERT (${Core.long0.columnName}) VALUES (s.${Core.long0.columnName})""") { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) :+ 8L).sorted)
    }

  // Source is a set operation (UNION ALL).
  val mergeSourceSetOp: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.sourceSetOp")(table =>
      s"""MERGE INTO $table t USING (
            SELECT CAST(8 AS BIGINT) AS ${Core.long0.columnName} UNION ALL SELECT CAST(9 AS BIGINT)
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN NOT MATCHED THEN INSERT (${Core.long0.columnName}) VALUES (s.${Core.long0.columnName})""") { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) ++ Seq(8L, 9L)).sorted)
    }

  // Merge into an empty target inserts all non-matching source rows (empties the seed first).
  val mergeIntoEmptyTarget: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("merge.intoEmptyTarget.empty")(table => s"DELETE FROM $table")()
      .sql("merge.intoEmptyTarget")(table =>
        s"""MERGE INTO $table t USING (
              SELECT * FROM VALUES
                (CAST(4 AS BIGINT), 4, 'row-4', 4.5, true,  '2024-01-04-03'),
                (CAST(5 AS BIGINT), 5, 'row-5', 5.5, false, '2024-01-05-04')
              AS s($cols)
            ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
            WHEN NOT MATCHED THEN INSERT *""") { view =>
        assert(view.before.isEmpty)
        assert(keyed(view.after) == Seq(4L, 5L))
      }

  // A null join key never matches, so it neither updates nor errors.
  val mergeNullJoinKey: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.nullJoinKey")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES (CAST(NULL AS BIGINT), 'n'), (CAST(2 AS BIGINT), 'M')
            AS s(${Core.long0.columnName}, ${Core.string0.columnName})
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN MATCHED THEN UPDATE SET t.${Core.string0.columnName} = s.${Core.string0.columnName}""") { view =>
      assert(keyed(view.after) == keyed(view.before))
      assert(longToString(view.after) == longToString(view.before).map { case (id, s) => id -> (if (id == 2) "M" else s) })
    }

  // INSERT * resolves columns by name even when the source lists them in a different order.
  val mergeResolveByName: TableTest[CoreTable.type] =
    TableTest(Core).sql("merge.resolveByName")(table =>
      s"""MERGE INTO $table t USING (
            SELECT * FROM VALUES ('g', CAST(7 AS BIGINT), 7, 7.5, false, '2024-07-07-07')
            AS s(${Core.string0.columnName}, ${Core.long0.columnName}, ${Core.int0.columnName}, ${Core.double0.columnName}, ${Core.boolean0.columnName}, datepartition)
          ) s ON t.${Core.long0.columnName} = s.${Core.long0.columnName}
          WHEN NOT MATCHED THEN INSERT *""") { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) :+ 7L).sorted)
      assert(view.after.find(_.get(Core.long0) == 7L).map(_.get(Core.string0)).contains("g"))
    }

  // ── insert / append / overwrite ────────────────────────────────────────────────────────
  val insertInto: TableTest[CoreTable.type] =
    TableTest(Core).sql("insert.into")(table =>
      s"""INSERT INTO $table VALUES
            (CAST(4 AS BIGINT), 4, 'row-4', 4.5, true,  '2024-01-04-03'),
            (CAST(5 AS BIGINT), 5, 'row-5', 5.5, false, '2024-01-05-04')""") { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) ++ Seq(4L, 5L)).sorted)
    }

  val appendDataFrame: TableTest[CoreTable.type] =
    TableTest(Core).step("append.dataFrame") { (spark, table) =>
      val frame = spark.sql(
        s"SELECT * FROM VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05') AS s($cols)")
      frame.writeTo(table).append()
    } { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) :+ 6L).sorted)
    }

  // INSERT OVERWRITE (static mode, the Spark default) replaces the whole table regardless of state.
  val insertOverwrite: TableTest[CoreTable.type] =
    TableTest(Core).sql("insert.overwrite")(table =>
      s"""INSERT OVERWRITE $table VALUES
            (CAST(1 AS BIGINT), 1, 'p', 1.5, false, '2024-01-01-00'),
            (CAST(2 AS BIGINT), 2, 'q', 2.5, true,  '2024-01-02-01')""") { view =>
      assert(keyed(view.after) == Seq(1L, 2L))
    }

  val overwriteDataFrame: TableTest[CoreTable.type] =
    TableTest(Core).step("overwrite.dataFrame") { (spark, table) =>
      val frame = spark.sql(
        s"SELECT * FROM VALUES (CAST(8 AS BIGINT), 8, 'h', 8.5, false, '2024-01-08-07') AS s($cols)")
      frame.writeTo(table).overwrite(org.apache.spark.sql.functions.lit(true))
    } { view =>
      assert(keyed(view.after) == Seq(8L))
    }

  // INSERT INTO with an explicit column list; the unlisted columns are null-filled.
  // NEGATIVE PIN (was SKIP-as-bug; reclassified after code-verified investigation). A partial/named-
  // column INSERT that omits other columns is REJECTED with INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_FIND_DATA.
  // This is an ENGINE limitation, not an OpenHouse policy: OpenHouse creates columns nullable-by-default
  // and the server round-trips the schema verbatim (verified) — but Iceberg 1.5's SparkTable does not
  // advertise column defaults (no SupportsColumnDefaultValue), so Spark's byName output resolution never
  // inserts the NULL-fill projection for the omitted (nullable) columns. Pin the rejection; it flips
  // only when the read+write APPLICATION of column defaults is wired (SparkTable implements
  // SupportsColumnDefaultValue + the reader injects initial-default for missing columns). NOTE (fork
  // audit): the com.linkedin.iceberg 1.5.2 fork #251 backported the NestedField initial/write-default
  // APIs + SchemaParser serialization ONLY — no SparkTable, no reader wiring — so the fork does NOT
  // satisfy the flip condition (and persists v3-style defaults on a v2 table with no gate). See
  // ICEBERG-FORK-AUDIT.md.
  val insertExplicitColumns: TableTest[CoreTable.type] =
    TableTest(Core).step("insert.explicitColumns") { (spark, table) =>
      val e = Check.intercept[Exception](
        spark.sql(s"INSERT INTO $table (${Core.long0.columnName}, ${Core.string0.columnName}) " +
          s"VALUES (CAST(4 AS BIGINT), 'd'), (CAST(5 AS BIGINT), 'e')"))
      val msg = Option(e.getMessage).getOrElse("").toUpperCase
      assert(msg.contains("CANNOT_FIND_DATA") || msg.contains("CANNOT FIND DATA") || msg.contains("INCOMPATIBLE_DATA"),
        s"expected a partial-INSERT rejection naming the omitted column (engine limitation), got: ${Option(e.getMessage).getOrElse("").take(200)}")
    }()

  // INSERT INTO … SELECT appends the selected rows.
  val insertIntoSelect: TableTest[CoreTable.type] =
    TableTest(Core).sql("insert.intoSelect")(table =>
      s"INSERT INTO $table SELECT * FROM VALUES " +
        s"(CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05') AS s($cols)") { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) :+ 6L).sorted)
    }

  // ── partitioned-only: selective-partition replacement (meaningful only when partitioned) ──
  // Seed rows 1/2/3 live in partitions '2024-01-01-00'/'01'/'02'. Writing one row into partition
  // '…-00' must replace only that partition, leaving rows 2 and 3.
  // Delta-sound: writing row 10 into partition '…-00' replaces ONLY that partition's rows (the
  // seeded row 1), leaving every other partition's rows and adding 10.
  private def onlyFirstPartitionReplaced(view: StepView[CoreTable.type]): Seq[Long] =
    (view.before.filterNot(_.get(Core.datePartition) == "2024-01-01-00").map(_.get(Core.long0)) :+ 10L).sorted

  val insertDynamicOverwrite: TableTest[CoreTable.type] =
    TableTest(Core).step("insert.dynamicOverwrite") { (spark, table) =>
      spark.conf.set("spark.sql.sources.partitionOverwriteMode", "dynamic")
      try spark.sql(s"INSERT OVERWRITE $table VALUES (CAST(10 AS BIGINT), 10, 'p', 10.5, true, '2024-01-01-00')")
      finally spark.conf.set("spark.sql.sources.partitionOverwriteMode", "static")
    } { view =>
      assert(keyed(view.after) == onlyFirstPartitionReplaced(view))
    }

  val overwritePartitions: TableTest[CoreTable.type] =
    TableTest(Core).step("overwrite.partitions") { (spark, table) =>
      val frame = spark.sql(
        s"SELECT * FROM VALUES (CAST(10 AS BIGINT), 10, 'p', 10.5, true, '2024-01-01-00') AS s($cols)")
      frame.writeTo(table).overwritePartitions()
    } { view =>
      assert(keyed(view.after) == onlyFirstPartitionReplaced(view))
    }

  // ── create (a preparation-only test: create under the layout, assert schema + emptiness) ─
  // Also the guard that the literal `columnDefinitions` matches CoreTable's declared columns.
  def createSchema(layout: Layout): TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(layout.create) { view =>
      val actual = view.spark.table(view.table).schema.fields.toList.map(field => (field.name, field.dataType.simpleString))
      val expected = Core.tableColumns.toList.map(column => (column.columnName, column.sqlType))
      assert(actual == expected)
      assert(view.after.isEmpty)
    }

  // ── DDL Phase 12: schema evolution — ADD COLUMN family (❓ probes settle B-vs-N) ───────────
  // The added column is not one of CoreTable's typed handles, so these assert on the LIVE schema
  // (name / type / comment / order) and raw SQL, not on typed row handles. Row snapshots
  // (view.before/after) still read only CoreTable's columns, so they stay valid across the ALTER.
  private def liveColumns(view: StepView[CoreTable.type]): Seq[(String, String)] =
    view.spark.table(view.table).schema.fields.toSeq.map(field => (field.name, field.dataType.simpleString))

  val ddlAddColumnSingle: TableTest[CoreTable.type] =
    TableTest(Core).sql("ddl.addColumn.single")(t => s"ALTER TABLE $t ADD COLUMN added_int int") { view =>
      assert(liveColumns(view).map(_._1).contains("added_int"), s"added_int missing: ${liveColumns(view).map(_._1)}")
      val nullCount = view.spark.sql(s"SELECT count(*) FROM ${view.table} WHERE added_int IS NULL").collect()(0).getLong(0)
      assert(nullCount == view.before.size, s"existing rows should read null for added_int: $nullCount != ${view.before.size}")
      assert(view.after.size == view.before.size)                                       // ADD COLUMN keeps rows
    }

  val ddlAddColumnMultiple: TableTest[CoreTable.type] =
    TableTest(Core).sql("ddl.addColumn.multiple")(t => s"ALTER TABLE $t ADD COLUMNS (added_a int, added_b string)") { view =>
      val names = liveColumns(view).map(_._1)
      assert(names.contains("added_a") && names.contains("added_b"), s"added columns missing: $names")
      assert(view.after.size == view.before.size)
    }

  val ddlAddColumnComment: TableTest[CoreTable.type] =
    TableTest(Core).sql("ddl.addColumn.comment")(t => s"ALTER TABLE $t ADD COLUMN added_c int COMMENT 'a note'") { view =>
      val field = view.spark.table(view.table).schema.fields.find(_.name == "added_c")
      assert(field.isDefined, "added_c missing")
      assert(field.get.getComment().contains("a note"), s"comment not stored: ${field.flatMap(_.getComment())}")
    }

  val ddlAddColumnPosition: TableTest[CoreTable.type] =
    TableTest(Core).sql("ddl.addColumn.position")(t => s"ALTER TABLE $t ADD COLUMN added_after int AFTER ${Core.long0.columnName}") { view =>
      val names = liveColumns(view).map(_._1)
      assert(names.indexOf("added_after") == names.indexOf(Core.long0.columnName) + 1, s"added_after not after long0: $names")
    }

  val ddlAlterColumnTypeWiden: TableTest[CoreTable.type] =
    TableTest(Core).sql("ddl.alterColumn.typeWiden")(t => s"ALTER TABLE $t ALTER COLUMN ${Core.int0.columnName} TYPE bigint") { view =>
      assert(liveColumns(view).toMap.get(Core.int0.columnName).contains("bigint"), s"int0 not widened: ${liveColumns(view).toMap.get(Core.int0.columnName)}")
      val vals = view.spark.sql(s"SELECT ${Core.int0.columnName} FROM ${view.table} ORDER BY ${Core.long0.columnName}").collect().toSeq.map(_.getLong(0))
      assert(vals == Seq(1L, 2L, 3L), s"values not preserved after widening: $vals")
    }

  // RENAME COLUMN is a SILENT NO-OP on OpenHouse (tagged bug): the statement neither errors nor renames
  // — verified via REFRESH TABLE + fresh DESCRIBE, the column keeps its old name. The recon predicted a
  // server rejection ("not found in newSchema"), but the client drops the rename before it reaches the
  // server, so nothing happens. This test asserts the CORRECT behavior (rename applies) and is tagged in
  // Plan.knownBugs, so it reports SKIP until fixed. A silent no-op is worse than a clean rejection.
  val ddlRenameColumn: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("ddl.renameColumn.seed")(t => s"ALTER TABLE $t ADD COLUMN to_rename int")()
      .sql("ddl.renameColumn")(t => s"ALTER TABLE $t RENAME COLUMN to_rename TO renamed_col") { view =>
        val names = liveColumns(view).map(_._1)
        assert(names.contains("renamed_col") && !names.contains("to_rename"), s"RENAME COLUMN silently no-oped: $names")
        assert(view.after.size == view.before.size)
      }

  /** Phase 12 DDL schema-evolution behaviors, crossed with every layout. */
  val ddlSchemaOperations: List[(String, TableTest[CoreTable.type])] = List(
    "ddl.addColumn.single"      -> ddlAddColumnSingle,
    "ddl.addColumn.multiple"    -> ddlAddColumnMultiple,
    "ddl.addColumn.comment"     -> ddlAddColumnComment,
    "ddl.addColumn.position"    -> ddlAddColumnPosition,
    "ddl.alterColumn.typeWiden" -> ddlAlterColumnTypeWiden,
    "ddl.renameColumn"          -> ddlRenameColumn
  )

  /** The operations crossed with every layout, each a headless segment, in report order. */
  val operations: List[(String, TableTest[CoreTable.type])] = List(
    "read.projection"                -> readProjection,
    "read.filter"                    -> readFilter,
    "format.materialization"         -> formatMaterialization,
    "delete.byPredicate"             -> deleteByPredicate,
    "delete.byInList"                -> deleteByInList,
    "delete.byInSubquery"            -> deleteByInSubquery,
    "delete.byNotInSubquery"         -> deleteByNotInSubquery,
    "delete.byExistsSubquery"        -> deleteByExistsSubquery,
    "delete.byNotExistsSubquery"     -> deleteByNotExistsSubquery,
    "delete.byScalarSubquery"        -> deleteByScalarSubquery,
    "delete.byNullCondition"         -> deleteByNullCondition,
    "delete.all"                     -> deleteAll,
    "delete.none"                    -> deleteNone,
    "delete.byPartitionPredicate"    -> deleteByPartitionPredicate,
    "delete.withAlias"               -> deleteWithAlias,
    "delete.whereFalse.noSnapshot"   -> deleteWhereFalseKeepsSnapshot,
    "delete.truncate"                -> truncate,
    "delete.atSnapshot.rejected"     -> deleteAtSnapshotRejected,
    "update.byPredicate"             -> updateByPredicate,
    "update.withoutCondition"        -> updateWithoutCondition,
    "update.noMatch"                 -> updateNoMatch,
    "update.byInSubquery"            -> updateByInSubquery,
    "update.byNotInSubquery"         -> updateByNotInSubquery,
    "update.byExistsSubquery"        -> updateByExistsSubquery,
    "update.byNotExistsSubquery"     -> updateByNotExistsSubquery,
    "update.byScalarSubquery"        -> updateByScalarSubquery,
    "update.withAlias"               -> updateWithAlias,
    "update.multipleColumns"         -> updateMultipleColumns,
    "update.byExpression"            -> updateByExpression,
    "update.movePartition"           -> updateMovePartition,
    "update.nullAssignment"          -> updateNullAssignment,
    "merge.insertNotMatched"         -> mergeInsertNotMatched,
    "merge.updateMatched"            -> mergeUpdateMatched,
    "merge.deleteMatched"            -> mergeDeleteMatched,
    "merge.upsert"                   -> mergeUpsert,
    "merge.deleteNotMatchedBySource" -> mergeDeleteNotMatchedBySource,
    "merge.conditionalUpdate"        -> mergeConditionalUpdate,
    "merge.multipleMatchedClauses"   -> mergeMultipleMatchedClauses,
    "merge.conditionalInsert"        -> mergeConditionalInsert,
    "merge.allClauses"               -> mergeAllClauses,
    "merge.updateStar"               -> mergeUpdateStar,
    "merge.insertExplicitColumns"    -> mergeInsertExplicitColumns,
    "merge.sourceCTE"                -> mergeSourceCTE,
    "merge.sourceSetOp"              -> mergeSourceSetOp,
    "merge.intoEmptyTarget"          -> mergeIntoEmptyTarget,
    "merge.nullJoinKey"              -> mergeNullJoinKey,
    "merge.resolveByName"            -> mergeResolveByName,
    "insert.into"                    -> insertInto,
    "insert.explicitColumns"         -> insertExplicitColumns,
    "insert.intoSelect"              -> insertIntoSelect,
    "append.dataFrame"               -> appendDataFrame,
    "insert.overwrite"               -> insertOverwrite,
    "overwrite.dataFrame"            -> overwriteDataFrame
  )

  /** Operations meaningful only on a partitioned table; crossed with the partitioned layouts only. */
  val partitionedOperations: List[(String, TableTest[CoreTable.type])] = List(
    "insert.dynamicOverwrite"        -> insertDynamicOverwrite,
    "overwrite.partitions"           -> overwritePartitions
  )

  /** The DELETE/UPDATE/MERGE subset — the operations affected by the CoW-vs-MoR mode. */
  val mutationOperations: List[(String, TableTest[CoreTable.type])] =
    operations.filter { case (name, _) =>
      name.startsWith("delete.") || name.startsWith("update.") || name.startsWith("merge.")
    }

  // ── MoR discriminator: prove merge-on-read actually wrote position-delete files ──────────
  // The rest of the MoR axis reuses CoW's row-delta assertions, which pass identically whether the
  // write was copy-on-write or merge-on-read. These two pin the PHYSICAL difference: a MoR delete
  // MUST add a position-delete file; a CoW delete must NOT. Both are prepared with
  // `createAndSeedSingleFile` and delete a strict subset (`long0 < 2` → 1 of 3 rows), so the write
  // cannot be satisfied by whole-file elimination — the outcome is deterministic across formats
  // (verified: parquet/orc/avro all add exactly one position delete under MoR, none under CoW).
  private def deleteFileCount(spark: SparkSession, table: String): Long =
    spark.sql(s"SELECT count(*) FROM $table.delete_files").collect()(0).getLong(0)

  val morWritesDeleteFiles: TableTest[CoreTable.type] =
    TableTest(Core).delete(core => s"${core.long0.columnName} < 2") { view =>
      assert(view.after == view.before.filterNot(_.get(Core.long0) < 2))                 // rows correct
      assert(deleteFileCount(view.spark, view.table) >= 1,
        "merge-on-read DELETE of a strict subset of a data file must write a position-delete file")
    }

  val cowWritesNoDeleteFiles: TableTest[CoreTable.type] =
    TableTest(Core).delete(core => s"${core.long0.columnName} < 2") { view =>
      assert(view.after == view.before.filterNot(_.get(Core.long0) < 2))
      assert(deleteFileCount(view.spark, view.table) == 0, "copy-on-write DELETE must not write delete files")
    }

  // ── nested / complex types (NestedTable) ───────────────────────────────────────────────
  val nestedLayouts: List[Layout] =
    List("parquet", "orc", "avro").map(format => Layout(s"nested-unpartitioned/$format", table =>
      s"CREATE TABLE $table (${NestedTable.columnDefinitions}) USING iceberg TBLPROPERTIES ('write.format.default'='$format')"))

  def createAndSeedNested(layout: Layout, numberOfRows: Int): TableTest[NestedTable.type] =
    TableTest(NestedTable).sql("create")(layout.create)().insert(numberOfRows)()

  // Read every nested column back and check the seeded values roundtrip.
  val nestedRoundtrip: TableTest[NestedTable.type] =
    TableTest(NestedTable).check("nested.roundtrip") { view =>
      val got = view.spark.sql(s"SELECT id, s.x, s.y, arr, m['k'], nested.inner.z FROM ${view.table} ORDER BY id").collect().toSeq
      val actual = got.map(r => (r.getLong(0), r.getInt(1), r.getString(2), r.getSeq[Int](3), r.getInt(4), r.getInt(5)))
      assert(actual == (1 to 3).map(i => (i.toLong, i, s"row-$i", Seq(i, i + 1), i, i)))
    }

  val nestedProjectField: TableTest[NestedTable.type] =
    TableTest(NestedTable).check("nested.projectField") { view =>
      val xs = view.spark.sql(s"SELECT s.x FROM ${view.table} ORDER BY id").collect().map(_.getInt(0)).toSeq
      assert(xs == Seq(1, 2, 3))
    }

  val nestedFilterField: TableTest[NestedTable.type] =
    TableTest(NestedTable).check("nested.filterNestedField") { view =>
      val ids = view.spark.sql(s"SELECT id FROM ${view.table} WHERE s.x = 2 ORDER BY id").collect().map(_.getLong(0)).toSeq
      assert(ids == Seq(2L))
    }

  // Update a nested struct field.
  val nestedUpdateStructField: TableTest[NestedTable.type] =
    TableTest(NestedTable).sql("nested.updateStructField")(table => s"UPDATE $table SET s.x = 99 WHERE id = 2") { view =>
      assert(view.spark.sql(s"SELECT s.x FROM ${view.table} WHERE id = 2").collect()(0).getInt(0) == 99)
      assert(view.spark.sql(s"SELECT s.x FROM ${view.table} WHERE id = 1").collect()(0).getInt(0) == 1)
    }

  val nestedMergeInsert: TableTest[NestedTable.type] =
    TableTest(NestedTable).sql("nested.mergeInsert")(table =>
      s"""MERGE INTO $table tgt USING (
            SELECT * FROM VALUES
              (CAST(4 AS BIGINT), named_struct('x', 4, 'y', 'row-4'), array(4, 5), map('k', 4), named_struct('inner', named_struct('z', 4)))
            AS v(id, s, arr, m, nested)
          ) src ON tgt.id = src.id
          WHEN NOT MATCHED THEN INSERT *""") { view =>
      val ids = view.spark.sql(s"SELECT id FROM ${view.table} ORDER BY id").collect().map(_.getLong(0)).toSeq
      assert(ids == Seq(1L, 2L, 3L, 4L))
      assert(view.spark.sql(s"SELECT s.x FROM ${view.table} WHERE id = 4").collect()(0).getInt(0) == 4)
    }

  val nestedDeleteByField: TableTest[NestedTable.type] =
    TableTest(NestedTable).sql("nested.deleteByNestedField")(table => s"DELETE FROM $table WHERE s.x = 2") { view =>
      val ids = view.spark.sql(s"SELECT id FROM ${view.table} ORDER BY id").collect().map(_.getLong(0)).toSeq
      assert(ids == Seq(1L, 3L))
    }

  // Insert a row with a null struct and empty array/map.
  val nestedNullValues: TableTest[NestedTable.type] =
    TableTest(NestedTable).sql("nested.nullValues")(table =>
      s"INSERT INTO $table VALUES (CAST(4 AS BIGINT), CAST(NULL AS struct<x:int,y:string>), " +
        s"CAST(array() AS array<int>), CAST(map() AS map<string,int>), CAST(NULL AS struct<inner:struct<z:int>>))") { view =>
      val row4 = view.spark.sql(s"SELECT id, s, arr FROM ${view.table} WHERE id = 4").collect()(0)
      assert(row4.isNullAt(1))                 // s is null
      assert(row4.getSeq[Int](2).isEmpty)      // arr is empty
    }

  val nestedOperations: List[(String, TableTest[NestedTable.type])] = List(
    "nested.roundtrip"          -> nestedRoundtrip,
    "nested.projectField"       -> nestedProjectField,
    "nested.filterNestedField"  -> nestedFilterField,
    "nested.updateStructField"  -> nestedUpdateStructField,
    "nested.mergeInsert"        -> nestedMergeInsert,
    "nested.deleteByNestedField" -> nestedDeleteByField,
    "nested.nullValues"         -> nestedNullValues
  )

  // ── type-edge coverage (TypesTable) ─────────────────────────────────────────────────────
  val typesLayouts: List[Layout] =
    List("parquet", "orc", "avro").map(format => Layout(s"types-unpartitioned/$format", table =>
      s"CREATE TABLE $table (${TypesTable.columnDefinitions}) USING iceberg TBLPROPERTIES ('write.format.default'='$format')"))

  def createAndSeedTypes(layout: Layout, numberOfRows: Int): TableTest[TypesTable.type] =
    TableTest(TypesTable).sql("create")(layout.create)().insert(numberOfRows)()

  // A full valued row for TypesTable with the given id; individual tests override specific columns.
  private def typesRow(id: Long, n: String, x: String, dec: String, str: String): String =
    s"(CAST($id AS BIGINT), $n, $x, $dec, $str, CAST('b' AS binary), DATE '2024-01-01', " +
      s"TIMESTAMP '2024-01-01 00:00:00', TIMESTAMP_NTZ '2024-01-01 00:00:00')"

  val typesRoundtrip: TableTest[TypesTable.type] =
    TableTest(TypesTable).check("types.roundtrip") { view =>
      val r = view.spark.sql(s"SELECT id, n, x, dec, str FROM ${view.table} WHERE id = 1").collect()(0)
      assert(r.getLong(0) == 1L && r.getInt(1) == 1 && r.getDouble(2) == 1.5)
      assert(r.getDecimal(3).compareTo(new java.math.BigDecimal("1.50")) == 0)
      assert(r.getString(4) == "row-1")
    }

  val typesNulls: TableTest[TypesTable.type] =
    TableTest(TypesTable).sql("types.nulls")(table =>
      s"INSERT INTO $table VALUES (CAST(10 AS BIGINT), NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)") { view =>
      val r = view.spark.sql(s"SELECT n, x, str, ts, tsntz FROM ${view.table} WHERE id = 10").collect()(0)
      assert((0 to 4).forall(r.isNullAt))
    }

  val typesSpecialFloats: TableTest[TypesTable.type] =
    TableTest(TypesTable).sql("types.specialFloats")(table =>
      s"INSERT INTO $table VALUES ${typesRow(11, "0", "double('NaN')", "CAST(0 AS decimal(10,2))", "'x'")}, " +
        s"${typesRow(12, "0", "double('Infinity')", "CAST(0 AS decimal(10,2))", "'y'")}") { view =>
      assert(view.spark.sql(s"SELECT x FROM ${view.table} WHERE id = 11").collect()(0).getDouble(0).isNaN)
      assert(view.spark.sql(s"SELECT x FROM ${view.table} WHERE id = 12").collect()(0).getDouble(0).isInfinite)
    }

  val typesBoundaries: TableTest[TypesTable.type] =
    TableTest(TypesTable).sql("types.boundaries")(table =>
      s"INSERT INTO $table VALUES " +
        s"${typesRow(9223372036854775807L, "2147483647", "0.0", "CAST(99999999.99 AS decimal(10,2))", "'max'")}") { view =>
      val r = view.spark.sql(s"SELECT id, n, dec FROM ${view.table} WHERE str = 'max'").collect()(0)
      assert(r.getLong(0) == Long.MaxValue && r.getInt(1) == Int.MaxValue)
      assert(r.getDecimal(2).compareTo(new java.math.BigDecimal("99999999.99")) == 0)
    }

  val typesUnicodeAndEmpty: TableTest[TypesTable.type] =
    TableTest(TypesTable).sql("types.unicodeAndEmpty")(table =>
      s"INSERT INTO $table VALUES ${typesRow(13, "0", "0.0", "CAST(0 AS decimal(10,2))", "'日本語 🎉'")}, " +
        s"${typesRow(14, "0", "0.0", "CAST(0 AS decimal(10,2))", "''")}") { view =>
      assert(view.spark.sql(s"SELECT str FROM ${view.table} WHERE id = 13").collect()(0).getString(0) == "日本語 🎉")
      assert(view.spark.sql(s"SELECT str FROM ${view.table} WHERE id = 14").collect()(0).getString(0) == "")
    }

  val typesOperations: List[(String, TableTest[TypesTable.type])] = List(
    "types.roundtrip"       -> typesRoundtrip,
    "types.nulls"           -> typesNulls,
    "types.specialFloats"   -> typesSpecialFloats,
    "types.boundaries"      -> typesBoundaries,
    "types.unicodeAndEmpty" -> typesUnicodeAndEmpty
  )

  // ── partition transforms + evolution ────────────────────────────────────────────────────
  // Each transform test is self-contained: create partitioned by the transform, seed, and verify
  // the rows roundtrip and a partition spec is registered.
  def partitionTransform(transform: String): TableTest[TypesTable.type] =
    TableTest(TypesTable)
      .sql("create")(table =>
        s"CREATE TABLE $table (${TypesTable.columnDefinitions}) USING iceberg PARTITIONED BY ($transform) " +
          s"TBLPROPERTIES ('write.format.default'='parquet')")()
      .insert(3)()
      .check("verify") { view =>
        assert(view.after.size == 3)
        assert(view.spark.sql(s"SELECT * FROM ${view.table}.partitions").collect().nonEmpty)
      }

  // A CREATE with an unsupported partition transform is rejected. Run it on a scratch name so the
  // pipeline's managed (valid) table still exists for snapshotting.
  private def partitionTransformRejected(label: String, transform: String, expectMessage: String): TableTest[TypesTable.type] =
    TableTest(TypesTable)
      .sql("create")(table => s"CREATE TABLE $table (${TypesTable.columnDefinitions}) USING iceberg TBLPROPERTIES ('write.format.default'='parquet')")()
      .step(label) { (spark, table) =>
        val scratch = table + "_x"
        val error = Check.intercept[RuntimeException](spark.sql(
          s"CREATE TABLE $scratch (${TypesTable.columnDefinitions}) USING iceberg PARTITIONED BY ($transform) TBLPROPERTIES ('write.format.default'='parquet')"))
        spark.sql(s"DROP TABLE IF EXISTS $scratch")
        assert(error.getMessage.contains(expectMessage))
      }()

  val partitionTransforms: List[(String, TableTest[TypesTable.type])] = List(
    "partition.identity"        -> partitionTransform("id"),
    "partition.bucket"          -> partitionTransform("bucket(4, id)"),
    "partition.truncate"        -> partitionTransform("truncate(2, str)"),
    "partition.years"           -> partitionTransform("years(ts)"),
    "partition.months"          -> partitionTransform("months(ts)"),
    "partition.days"            -> partitionTransform("days(ts)"),
    "partition.hours"           -> partitionTransform("hours(ts)"),
    // OpenHouse contract: these transforms are rejected (negative tests).
    "partition.void.rejected"   -> partitionTransformRejected("partition.void.rejected", "void(n)", "not supported"),
    "partition.dateDay.rejected" -> partitionTransformRejected("partition.dateDay.rejected", "days(dt)", "Unsupported column")
  )

  // OpenHouse contract: partition evolution is NOT supported — ALTER … ADD/DROP PARTITION FIELD is
  // rejected with a 400 telling you to recreate the table. Captured as negative tests.
  val partitionEvolutionAddRejected: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(table => s"CREATE TABLE $table ($columnDefinitions) USING iceberg TBLPROPERTIES ('write.format.default'='parquet')")()
      .insert(3)()
      .step("partition.evolutionAdd.rejected") { (spark, table) =>
        val error = Check.intercept[Exception](spark.sql(s"ALTER TABLE $table ADD PARTITION FIELD datepartition"))
        assert(error.getMessage.contains("Evolution of table partitioning"))
      }()

  val partitionEvolutionDropRejected: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(table => s"CREATE TABLE $table ($columnDefinitions) USING iceberg PARTITIONED BY (datepartition) TBLPROPERTIES ('write.format.default'='parquet')")()
      .insert(3)()
      .step("partition.evolutionDrop.rejected") { (spark, table) =>
        val error = Check.intercept[Exception](spark.sql(s"ALTER TABLE $table DROP PARTITION FIELD datepartition"))
        assert(error.getMessage.contains("Evolution of table partitioning"))
      }()

  val partitionEvolution: List[(String, TableTest[CoreTable.type])] = List(
    "partition.evolutionAdd.rejected"  -> partitionEvolutionAddRejected,
    "partition.evolutionDrop.rejected" -> partitionEvolutionDropRejected
  )

  // ── time travel + restore/rollback ──────────────────────────────────────────────────────
  // A two-snapshot base: seed 3 rows (snapshot A), then insert 2 more (snapshot B).
  // Format is a PARAMETER, not baked in — so any block built on this base can multiplex across formats.
  private def coreTwoSnapshots(fmt: String): TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(table => s"CREATE TABLE $table ($columnDefinitions) USING iceberg TBLPROPERTIES ('write.format.default'='$fmt')")()
      .insert(3)()
      .sql("insertMore")(table => s"INSERT INTO $table VALUES " +
        s"(CAST(4 AS BIGINT), 4, 'row-4', 4.5, true, '2024-01-04-03'), (CAST(5 AS BIGINT), 5, 'row-5', 5.5, false, '2024-01-05-04')")()
  // No-arg overload (parquet) keeps the many existing single-format call sites unchanged.
  private def coreTwoSnapshots: TableTest[CoreTable.type] = coreTwoSnapshots("parquet")

  // Snapshots in ancestry order (root first), following the parent_id chain — deterministic even
  // if two commits happen to share a committed_at millisecond (which `ORDER BY committed_at` is not).
  private def snapshotIds(spark: SparkSession, table: String): Seq[Long] = {
    val rows = spark.sql(s"SELECT snapshot_id, parent_id FROM $table.snapshots").collect().toSeq
    val ids = rows.map(_.getLong(0)).toSet
    val childByParent = rows.collect { case r if !r.isNullAt(1) => r.getLong(1) -> r.getLong(0) }.toMap
    val root = rows.collectFirst { case r if r.isNullAt(1) || !ids.contains(r.getLong(1)) => r.getLong(0) }.get
    val order = scala.collection.mutable.ListBuffer(root)
    var cur = root
    while (childByParent.contains(cur)) { cur = childByParent(cur); order += cur }
    order.toList
  }

  def timeTravelVersionAsOf(fmt: String): TableTest[CoreTable.type] =
    coreTwoSnapshots(fmt).check("timeTravel.versionAsOf") { view =>
      val snaps = snapshotIds(view.spark, view.table)
      assert(view.spark.sql(s"SELECT count(*) FROM ${view.table} VERSION AS OF ${snaps(0)}").collect()(0).getLong(0) == 3)
      assert(view.spark.sql(s"SELECT count(*) FROM ${view.table} VERSION AS OF ${snaps(1)}").collect()(0).getLong(0) == 5)
    }

  def timeTravelTimestampAsOf(fmt: String): TableTest[CoreTable.type] =
    coreTwoSnapshots(fmt).check("timeTravel.timestampAsOf") { view =>
      val ts0 = view.spark.sql(s"SELECT committed_at FROM ${view.table}.snapshots ORDER BY committed_at LIMIT 1").collect()(0).getTimestamp(0)
      assert(view.spark.sql(s"SELECT count(*) FROM ${view.table} TIMESTAMP AS OF '$ts0'").collect()(0).getLong(0) == 3)
    }

  def timeTravelMetadataTables(fmt: String): TableTest[CoreTable.type] =
    coreTwoSnapshots(fmt).check("timeTravel.metadataTables") { view =>
      def count(meta: String): Long = view.spark.sql(s"SELECT count(*) FROM ${view.table}.$meta").collect()(0).getLong(0)
      assert(count("snapshots") == 2)
      assert(count("history") == 2)
      assert(count("files") >= 1 && count("manifests") >= 1)
    }

  def timeTravelIncrementalRead(fmt: String): TableTest[CoreTable.type] =
    coreTwoSnapshots(fmt).check("timeTravel.incrementalRead") { view =>
      val snaps = snapshotIds(view.spark, view.table)
      val added = view.spark.read.format("iceberg")
        .option("start-snapshot-id", snaps(0)).option("end-snapshot-id", snaps(1))
        .load(view.table).count()
      assert(added == 2) // only the rows added between snapshot A and B
    }

  def timeTravelOps(fmt: String): List[(String, TableTest[CoreTable.type])] = List(
    "timeTravel.versionAsOf"     -> timeTravelVersionAsOf(fmt),
    "timeTravel.timestampAsOf"   -> timeTravelTimestampAsOf(fmt),
    "timeTravel.metadataTables"  -> timeTravelMetadataTables(fmt),
    "timeTravel.incrementalRead" -> timeTravelIncrementalRead(fmt)
  )

  // Restore/rollback via stored procedures (gated: OpenHouse may not expose CALL procedures).
  private def catalogRelative(table: String): String = table.stripPrefix("openhouse.")

  def restoreRollbackToSnapshot(fmt: String): TableTest[CoreTable.type] =
    coreTwoSnapshots(fmt).step("restore.rollbackToSnapshot") { (spark, table) =>
      val first = snapshotIds(spark, table).head
      spark.sql(s"CALL openhouse.system.rollback_to_snapshot('${catalogRelative(table)}', $first)")
    } { view =>
      assert(view.after.size == 3) // rolled back to the 3-row snapshot
    }

  def restoreSetCurrentSnapshot(fmt: String): TableTest[CoreTable.type] =
    coreTwoSnapshots(fmt).step("restore.setCurrentSnapshot") { (spark, table) =>
      val first = snapshotIds(spark, table).head
      spark.sql(s"CALL openhouse.system.set_current_snapshot('${catalogRelative(table)}', $first)")
    } { view =>
      assert(view.after.size == 3)
    }

  def restoreRollbackOps(fmt: String): List[(String, TableTest[CoreTable.type])] = List(
    "restore.rollbackToSnapshot"  -> restoreRollbackToSnapshot(fmt),
    "restore.setCurrentSnapshot"  -> restoreSetCurrentSnapshot(fmt)
  )

  // ── Maintenance OPERATIONS (Iceberg CALL procedures; jobs merely orchestrate these) ──────────
  // SE / OFD / compaction are stored procedures, reachable from Spark SQL like rollback/set_current.
  // Each mutates physical state; we assert the current DATA is preserved and observe the metadata delta.
  def maintenanceExpireSnapshots(fmt: String): TableTest[CoreTable.type] =
    coreTwoSnapshots(fmt).step("maintenance.expireSnapshots") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
    } { view =>
      assert(view.after.size == 5, "expire_snapshots changed the current data")
      assert(view.snapshotsAfter < view.snapshotsBefore, s"expire did not drop a snapshot: ${view.snapshotsBefore} -> ${view.snapshotsAfter}")
    }

  def maintenanceRewriteDataFiles(fmt: String): TableTest[CoreTable.type] =
    coreTwoSnapshots(fmt).step("maintenance.rewriteDataFiles") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.rewrite_data_files(table => '${catalogRelative(table)}')")
    } { view =>
      assert(view.after.size == 5, "compaction changed rows")                          // rows preserved
    }

  def maintenanceRemoveOrphanFiles(fmt: String): TableTest[CoreTable.type] =
    coreTwoSnapshots(fmt).step("maintenance.removeOrphanFiles") { (spark, table) =>
      // older_than must be ≥24h in the past (a safety guard); a far-past ts is a valid no-op that
      // still exercises the procedure end-to-end without corrupting live files.
      spark.sql(s"CALL openhouse.system.remove_orphan_files(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2020-01-01 00:00:00')")
    } { view =>
      assert(view.after.size == 5, "orphan removal changed rows")
    }

  def maintenanceOps(fmt: String): List[(String, TableTest[CoreTable.type])] = List(
    "maintenance.expireSnapshots"  -> maintenanceExpireSnapshots(fmt),
    "maintenance.rewriteDataFiles" -> maintenanceRewriteDataFiles(fmt),
    "maintenance.removeOrphanFiles" -> maintenanceRemoveOrphanFiles(fmt)
  )

  // ── Control-plane (REST) ops with no SQL surface — driven via the embedded server's HTTP API ──
  // Lock enforcement: POST /lock (a real public entry), then a Spark mutation is rejected server-side
  // (LOCKED_TABLE_OPERATION); DELETE /lock restores mutability. High-fidelity — the embedded server
  // runs the real TablesController/TablesServiceImpl (see REST-FIDELITY-EVAL.md).
  def controlLockEnforcement(ctx: Ctx): Unit = {
    val spark = ctx.spark
    val table = s"${ctx.namespace}.t_lock"
    val Array(db, tbl) = table.stripPrefix("openhouse.").split("\\.", 2)
    spark.sql(s"DROP TABLE IF EXISTS $table")
    spark.sql(coreCreateParquet(table))
    spark.sql(s"INSERT INTO $table ${RowGenerator.valuesClause(Core, 3)}")
    try {
      val (lockStatus, lockBody) = Rest.post(ctx, s"/v1/databases/$db/tables/$tbl/lock", """{"locked":true}""")
      assert(lockStatus >= 200 && lockStatus < 300, s"lock POST failed: $lockStatus $lockBody")
      val e = Check.intercept[Exception](spark.sql(
        s"UPDATE $table SET ${Core.string0.columnName} = 'locked-write' WHERE ${Core.long0.columnName} = 1"))
      assert(Exceptions.causeChain(e).exists(t => Option(t.getMessage).exists(_.toLowerCase.contains("locked"))),
        s"expected a locked-table rejection, got: ${e.getMessage.take(200)}")
      val (unlockStatus, unlockBody) = Rest.delete(ctx, s"/v1/databases/$db/tables/$tbl/lock")
      assert(unlockStatus >= 200 && unlockStatus < 300, s"unlock DELETE failed: $unlockStatus $unlockBody")
      spark.sql(s"UPDATE $table SET ${Core.string0.columnName} = 'unlocked-write' WHERE ${Core.long0.columnName} = 1")
      assert(spark.sql(s"SELECT count(*) FROM $table WHERE ${Core.string0.columnName} = 'unlocked-write'").collect()(0).getLong(0) == 1,
        "post-unlock update did not apply")
    } finally spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  // Undrop lifecycle — TAGGED SKIP (Plan.knownBugs). Not runnable at fidelity in the embedded harness:
  // (1) the embedded HouseTableRepository is a @Primary in-memory STUB (HouseTablesH2Repository) — a
  //     test here would exercise the shim's own reimplementation, not the real HTS soft-delete logic;
  // (2) the public Tables DELETE hard-codes purge=true, so drop→soft-delete is unreachable via the
  //     customer API in ANY environment (undrop is HTS-admin-only — a product finding).
  // Real fidelity needs an embedded HTS (SpringH2HtsApplication) + de-@Primary-ing the stub. The body
  // documents the intended list→restore flow for that future harness.
  def controlUndropLifecycle(ctx: Ctx): Unit = {
    val spark = ctx.spark
    val table = s"${ctx.namespace}.t_undrop"
    val Array(db, tbl) = table.stripPrefix("openhouse.").split("\\.", 2)
    spark.sql(s"DROP TABLE IF EXISTS $table")
    spark.sql(coreCreateParquet(table))
    spark.sql(s"INSERT INTO $table ${RowGenerator.valuesClause(Core, 3)}")
    // (intended, once a real HTS soft-deletes the table:)
    val (listStatus, listBody) = Rest.get(ctx, s"/v1/databases/$db/softDeletedTables")
    assert(listStatus == 200 && listBody.contains(tbl), "soft-deleted table should be listed")
    val (restoreStatus, _) = Rest.put(ctx, s"/v1/databases/$db/tables/$tbl/restore?deletedAtMs=0", "")
    assert(restoreStatus >= 200 && restoreStatus < 300, "restore should succeed")
    assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "restored table keeps its rows")
    spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  val controlPlane: List[(String, Ctx => Unit)] = List(
    "control.lock.enforcement"  -> controlLockEnforcement,
    "control.undrop.lifecycle"  -> controlUndropLifecycle
  )

  // ── Undrop admin-lifecycle block (Phase 5 — REAL HTS only, HtsAdmin.enabled) ─────────────────
  // With an embedded real HTS the full soft-delete → list → restore / purge lifecycle is exercisable
  // (the customer DROP still hard-deletes — soft-delete is driven directly on HTS). These are the
  // HTS-admin lifecycle cases that sit ALONGSIDE the surface-doubling undrop battery.
  private def undropSeed(ctx: Ctx, name: String): (String, String, String) = {
    val table = s"${ctx.namespace}.$name"
    val Array(db, tbl) = table.stripPrefix("openhouse.").split("\\.", 2)
    ctx.spark.sql(s"DROP TABLE IF EXISTS $table")
    ctx.spark.sql(coreCreateParquet(table))
    ctx.spark.sql(s"INSERT INTO $table ${RowGenerator.valuesClause(Core, 3)}")
    (table, db, tbl)
  }

  // Soft-delete → the customer softDeletedTables listing shows it → restore → rows intact.
  def undropAdminRestoreRoundTrip(ctx: Ctx): Unit = {
    val (table, db, tbl) = undropSeed(ctx, "t_undrop_rt")
    val (sd, sdb) = HtsAdmin.softDelete(db, tbl); assert(sd >= 200 && sd < 300, s"soft-delete failed ($sd): $sdb")
    val (ls, lb) = Rest.get(ctx, s"/v1/databases/$db/softDeletedTables")
    assert(ls == 200 && lb.contains(tbl), s"soft-deleted table not listed via Tables API ($ls): $lb")
    val ms = HtsAdmin.softDeletedAtMs(db, tbl).getOrElse(throw new AssertionError(s"no deletedAtMs for $db.$tbl"))
    val (rs, rb) = HtsAdmin.restore(db, tbl, ms); assert(rs >= 200 && rs < 300, s"restore failed ($rs): $rb")
    assert(ctx.spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "restored table lost rows")
    ctx.spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  // Two soft-deleted tables both appear in the listing (paging/enumeration works).
  def undropAdminListSoftDeleted(ctx: Ctx): Unit = {
    val (_, db, t1) = undropSeed(ctx, "t_undrop_l1")
    val (_, _,  t2) = undropSeed(ctx, "t_undrop_l2")
    assert(HtsAdmin.softDelete(db, t1)._1 / 100 == 2, "soft-delete t1 failed")
    assert(HtsAdmin.softDelete(db, t2)._1 / 100 == 2, "soft-delete t2 failed")
    val (ls, lb) = Rest.get(ctx, s"/v1/databases/$db/softDeletedTables")
    assert(ls == 200 && lb.contains(t1) && lb.contains(t2), s"both soft-deleted tables should list ($ls): $lb")
  }

  // Restore AFTER purge must be rejected — purge is permanent. Pin whatever the real HTS returns
  // (a 4xx; the point is that restore no longer succeeds once the row is purged).
  def undropAdminRestoreAfterPurgeRejected(ctx: Ctx): Unit = {
    val (_, db, tbl) = undropSeed(ctx, "t_undrop_purge")
    assert(HtsAdmin.softDelete(db, tbl)._1 / 100 == 2, "soft-delete failed")
    val ms = HtsAdmin.softDeletedAtMs(db, tbl).getOrElse(throw new AssertionError("no deletedAtMs"))
    // purge everything deleted before a far-future instant → removes this row permanently
    val (ps, _) = Rest.delete(ctx, s"/v1/databases/$db/tables/$tbl/purge?purgeAfterMs=${Long.MaxValue}")
    assert(ps / 100 == 2, s"purge should succeed ($ps)")
    val (rs, _) = HtsAdmin.restore(db, tbl, ms)
    assert(rs >= 400, s"restore after purge must be rejected, got $rs")
  }

  val undropAdminOps: List[(String, Ctx => Unit)] = List(
    "undropAdmin.restoreRoundTrip"        -> undropAdminRestoreRoundTrip,
    "undropAdmin.listSoftDeleted"         -> undropAdminListSoftDeleted,
    "undropAdmin.restoreAfterPurgeRejected" -> undropAdminRestoreAfterPurgeRejected
  )

  // ── Column-default (fork #251) — OSS Spark DDL path ──────────────────────────────────────────
  // Column defaults are TABLED (see ICEBERG-FORK-AUDIT.md). This test characterizes what the OSS Spark 3.5
  // DDL path does with `ALTER TABLE t ADD COLUMN c int DEFAULT 5`; the behavior is identical on the
  // published 1.5.2.15 and the branch build (#251 is api/core only, with no Spark write wiring). Measured:
  //   • accepted at Spark parse time (Spark 3.5 owns the DEFAULT grammar);
  //   • the default is not written into the Iceberg schema (DESCRIBE shows `c|int|null`, no default);
  //   • pre-existing rows read NULL;
  //   • an INSERT that omits the column is rejected INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_FIND_DATA
  //     (same root as bug1 — no column-default write wiring in the connector).
  // These are behavior pins: if a future build changes any of the above, the asserts flip and it is re-audited.
  private def forkColDefaultAddColumn(fmt: String)(ctx: Ctx): Unit = {
    val spark = ctx.spark
    val table = s"${ctx.namespace}.t_coldef_$fmt"
    spark.sql(s"DROP TABLE IF EXISTS $table")
    spark.sql(s"CREATE TABLE $table (id bigint, s string) USING iceberg TBLPROPERTIES ('write.format.default'='$fmt')")
    spark.sql(s"INSERT INTO $table VALUES (1, 'a'), (2, 'b')")

    // (1) The customer path is ACCEPTED at parse time (Spark owns the grammar) — pin no-throw.
    spark.sql(s"ALTER TABLE $table ADD COLUMN c int DEFAULT 5")

    // (2) The default is not written into the persisted schema — column c has no default metadata.
    val cDesc = spark.sql(s"DESCRIBE TABLE EXTENDED $table").collect()
                  .map(_.mkString("|")).filter(_.matches("(?i)^c\\|.*")).mkString(" ;; ")
    assert(!cDesc.toLowerCase.contains("default") && !cDesc.contains("5"),
      s"[$fmt] expected no default persisted for c, but DESCRIBE shows: $cDesc — a #251-containing build may now be wired; re-audit")

    // (3) The default is NOT backfilled on read — pre-existing rows read NULL, not 5.
    val nulls = spark.sql(s"SELECT count(*) FROM $table WHERE c IS NULL").collect()(0).getLong(0)
    assert(nulls == 2,
      s"[$fmt] expected the default NOT applied on read (2 NULLs), got $nulls — a #251-containing build may now apply defaults; re-audit")

    // (4) The default is NOT applied on write — an insert that omits c is rejected (no write wiring).
    val omit = Check.intercept[org.apache.spark.sql.AnalysisException] {
      spark.sql(s"INSERT INTO $table (id, s) VALUES (3, 'c')")
    }
    val omitMsg = Exceptions.causeChain(omit).flatMap(e => Option(e.getMessage)).mkString(" | ")
    assert(omitMsg.contains("CANNOT_FIND_DATA"),
      s"[$fmt] expected omit-insert rejected with CANNOT_FIND_DATA (no column-default write wiring), got: $omitMsg")

    println(s"DIAG fork.colDefault[$fmt]: accepted=yes persistedDefault=no readBackfill=no writeApply=no(CANNOT_FIND_DATA)")
    spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  // ── Column-default (fork #251) — SchemaParser serialization ──────────────────────────────────────
  // Characterizes the api/core surface of #251: NestedField carries `initial-default`/`write-default` and
  // SchemaParser serializes them into the schema JSON. `toJson` takes no format-version parameter, so the
  // key serializes regardless of the table's format version. Exercised directly via reflection so the SAME
  // source compiles and runs in BOTH artifacts:
  //   • published 1.5.2.15  → NestedField.builder() is absent → records "API unsupported";
  //   • branch HEAD (#251)  → builds a defaulted field, checks SchemaParser emits `initial-default` and
  //                           that it round-trips (fromJson→toJson).
  // Reflection (not direct calls) is required because the builder API does not exist in the release jar;
  // a direct reference would not COMPILE in default (release) mode.
  private def forkColDefaultApiSerialization(ctx: Ctx): Unit = {
    val nestedFieldCls = Class.forName("org.apache.iceberg.types.Types$NestedField")
    val builderM = scala.util.Try(nestedFieldCls.getMethod("builder"))
    if (builderM.isFailure) {
      // Published release: the #251 column-default API is absent. Pin that absence (feature not present).
      println("DIAG fork.colDefault.api: NestedField.builder ABSENT — #251 column-default API unsupported (published release artifact)")
      val ms = nestedFieldCls.getMethods.map(_.getName).toSet
      assert(!ms.contains("initialDefault") && !ms.contains("writeDefault"),
        "NestedField exposes initial/write-default accessors but no builder() — unexpected partial #251; re-audit")
      return
    }
    // Branch HEAD: #251 present. Build `optional int c` carrying initial-default=5 via the builder.
    val builder0 = builderM.get.invoke(null)
    def chain(b: AnyRef, m: String, argT: Class[_], arg: AnyRef): AnyRef =
      b.getClass.getMethod(m, argT).invoke(b, arg)
    def chain0(b: AnyRef, m: String): AnyRef = b.getClass.getMethod(m).invoke(b)
    val intType = Class.forName("org.apache.iceberg.types.Types$IntegerType")
      .getMethod("get").invoke(null)
    var b = chain(builder0, "withId", java.lang.Integer.TYPE, java.lang.Integer.valueOf(3))
    b = chain(b, "withName", classOf[String], "c")
    b = chain(b, "ofType", Class.forName("org.apache.iceberg.types.Type"), intType)
    b = chain0(b, "asOptional")
    b = chain(b, "withInitialDefault", classOf[Object], java.lang.Integer.valueOf(5))
    val field = b.getClass.getMethod("build").invoke(b)
      .asInstanceOf[org.apache.iceberg.types.Types.NestedField]

    // Assemble a schema [id, c(default=5)] and serialize it — no format version is even passed.
    val idField = org.apache.iceberg.types.Types.NestedField.required(
      1, "id", org.apache.iceberg.types.Types.LongType.get())
    val schema = new org.apache.iceberg.Schema(java.util.Arrays.asList(idField, field))
    val json = org.apache.iceberg.SchemaParser.toJson(schema)
    println(s"DIAG fork.colDefault.api: #251 PRESENT; serialized schema JSON = $json")

    // (a) The default is serialized into the schema JSON.
    assert(json.contains("initial-default"),
      s"expected #251 SchemaParser to serialize 'initial-default' into the schema JSON, got: $json")
    // (b) toJson takes no format-version argument — the key serializes the same regardless of format version.
    // (c) Round-trips through fromJson→toJson.
    val reparsed = org.apache.iceberg.SchemaParser.fromJson(json)
    val json2 = org.apache.iceberg.SchemaParser.toJson(reparsed)
    assert(json2.contains("initial-default"),
      s"expected 'initial-default' to survive fromJson->toJson round-trip, got: $json2")
    println("DIAG fork.colDefault.api: initial-default serialized (no format-version argument) + round-trips")
  }

  // Reflectively build an `optional int` NestedField carrying initial-default=`dflt` (the #251 builder).
  // Returns None when the API is absent (published release) so callers can pin that cleanly.
  private def buildDefaultedIntField(id: Int, name: String, dflt: Int): Option[org.apache.iceberg.types.Types.NestedField] = {
    val nfCls = Class.forName("org.apache.iceberg.types.Types$NestedField")
    val bm = scala.util.Try(nfCls.getMethod("builder"))
    if (bm.isFailure) return None
    def chain(b: AnyRef, m: String, at: Class[_], a: AnyRef): AnyRef = b.getClass.getMethod(m, at).invoke(b, a)
    def chain0(b: AnyRef, m: String): AnyRef = b.getClass.getMethod(m).invoke(b)
    val intType = Class.forName("org.apache.iceberg.types.Types$IntegerType").getMethod("get").invoke(null)
    var b = chain(bm.get.invoke(null), "withId", java.lang.Integer.TYPE, java.lang.Integer.valueOf(id))
    b = chain(b, "withName", classOf[String], name)
    b = chain(b, "ofType", Class.forName("org.apache.iceberg.types.Type"), intType)
    b = chain0(b, "asOptional")
    b = chain(b, "withInitialDefault", classOf[Object], java.lang.Integer.valueOf(dflt))
    Some(b.getClass.getMethod("build").invoke(b).asInstanceOf[org.apache.iceberg.types.Types.NestedField])
  }

  // ── Column-default (fork #251) — READ-APPLY characterization PROBE (TABLED / not a bug claim) ─────
  // TABLED per repo owner: "it is not fundamentally broken … if there is a gap, it's implemented somewhere."
  // This probe records, but does NOT assert a verdict on, what THIS harness config does — i.e. the OSS
  // Spark 3.5 read path over branch iceberg-core. It does NOT exercise LinkedIn's PRIVATE Spark fork, which
  // is the likely home of the missing-column read-application. So a NULL here is a property of this harness,
  // NOT proof the feature is broken. Left as a DIAG-only probe (asserts only the undisputed half: the
  // default persists into the committed schema). Revisit when default values are un-tabled AND the private
  // Spark reader is available to test against.
  private def forkColDefaultReadApplyProbe(ctx: Ctx): Unit = {
    val spark = ctx.spark
    val nfCls = Class.forName("org.apache.iceberg.types.Types$NestedField")
    val apiPresent = scala.util.Try(nfCls.getMethod("builder")).isSuccess
    if (!apiPresent) {
      // Published release: no way to set a default, so there is nothing to read back. Assert the API is
      // genuinely absent (so this is not a silent green) and return.
      println("DIAG fork.colDefault.readApplyProbe: #251 API absent (published release) — nothing to probe")
      assert(!nfCls.getMethods.map(_.getName).toSet.contains("initialDefault"),
        "NestedField exposes initialDefault but builder() is absent — unexpected partial #251; re-audit")
      return
    }
    val cat = "coldefroapply"
    val wh  = s"/tmp/coldef-readapply-${System.nanoTime()}"
    spark.conf.set(s"spark.sql.catalog.$cat", "org.apache.iceberg.spark.SparkCatalog")
    spark.conf.set(s"spark.sql.catalog.$cat.type", "hadoop")
    spark.conf.set(s"spark.sql.catalog.$cat.warehouse", wh)
    val t = s"$cat.d.t_readapply"
    spark.sql(s"DROP TABLE IF EXISTS $t")
    spark.sql(s"CREATE TABLE $t (id bigint) USING iceberg")
    spark.sql(s"INSERT INTO $t VALUES (1),(2)") // data files physically contain ONLY `id`

    // Set a column default the way a private engine would: evolve the schema to [id, c int DEFAULT 5] via
    // the low-level TableMetadata API (public UpdateSchema has no set-default op on the branch).
    val table = org.apache.iceberg.spark.Spark3Util.loadIcebergTable(spark, t)
    val cur   = table.schema()
    val nextId = cur.highestFieldId() + 1
    val cField = buildDefaultedIntField(nextId, "c", 5).getOrElse(
      throw new AssertionError("#251 builder present but field build failed"))
    val cols = new java.util.ArrayList[org.apache.iceberg.types.Types.NestedField](cur.columns())
    cols.add(cField)
    val s2 = new org.apache.iceberg.Schema(cols)
    val ops = table.asInstanceOf[org.apache.iceberg.HasTableOperations].operations()
    val base = ops.current()
    val updated = org.apache.iceberg.TableMetadata.buildFrom(base).setCurrentSchema(s2, s2.highestFieldId()).build()
    ops.commit(base, updated)

    // ASSERT only the undisputed half: the default persists into the committed schema (ungated).
    val persisted = org.apache.iceberg.SchemaParser.toJson(
      org.apache.iceberg.spark.Spark3Util.loadIcebergTable(spark, t).schema())
    assert(persisted.contains("initial-default"),
      s"expected initial-default to persist into the committed schema, got: $persisted")

    // DIAG only — record what the OSS-Spark read path returns here; NO verdict (read-apply may live in the
    // private Spark reader not exercised by this harness).
    spark.sql(s"REFRESH TABLE $t")
    val vals = spark.sql(s"SELECT c FROM $t ORDER BY id").collect()
                 .map(r => if (r.isNullAt(0)) "NULL" else r.getInt(0).toString)
    println(s"DIAG fork.colDefault.readApplyProbe: OSS-Spark read of defaulted col over old files = " +
            s"[${vals.mkString(",")}] (harness-config observation only; private Spark reader NOT tested; TABLED)")
    spark.sql(s"DROP TABLE IF EXISTS $t")
  }

  val forkColDefaultOps: List[(String, Ctx => Unit)] = List(
    "fork.colDefault.addColumnInert @ parquet"   -> forkColDefaultAddColumn("parquet"),
    "fork.colDefault.addColumnInert @ orc"       -> forkColDefaultAddColumn("orc"),
    "fork.colDefault.apiSerialization @ core"    -> forkColDefaultApiSerialization,
    "fork.colDefault.readApplyProbe @ core"      -> forkColDefaultReadApplyProbe
  )

  // ── #249 (d69c1fd91) — partitioned write distribution default ─────────────────────────────────────
  // The fork changes the DEFAULT write.distribution-mode for PARTITIONED writes from Apache's HASH to
  // NONE (Spark 3.5). With HASH, the writer shuffles rows so each partition is written by one task ->
  // ~(#partitions) data files. With NONE, no shuffle -> each input task writes every partition it holds
  // -> up to (#tasks × #partitions) files. This test appends the SAME multi-task DataFrame into a
  // 4-partition table twice — once with the default, once with an explicit HASH — and compares the data-
  // file counts. It pins that (a) explicit HASH clusters to ~#partitions, and (b) the default does not
  // cluster more than HASH. Run under both runtimes via ICEBERG_RUNTIME_JAR: the DIAG file counts show
  // the branch-vs-release difference (fork NONE default -> more files than a HASH-default build).
  private def forkPartitionDistDefault(fmt: String)(ctx: Ctx): Unit = {
    val spark = ctx.spark
    val nParts = 4
    val nTasks = 8
    def buildAndCountFiles(tbl: String, extraProps: String): Long = {
      spark.sql(s"DROP TABLE IF EXISTS $tbl")
      spark.sql(s"CREATE TABLE $tbl (id bigint, p int) USING iceberg PARTITIONED BY (p) " +
        s"TBLPROPERTIES ('format-version'='2', 'write.format.default'='$fmt'$extraProps)")
      // nTasks input partitions, each holding rows for all nParts table partitions.
      val df = spark.range(0, 400)
        .selectExpr("id", s"cast(id % $nParts as int) as p")
        .repartition(nTasks)
      df.writeTo(tbl).append()
      val n = spark.sql(s"SELECT count(*) FROM $tbl.data_files").collect()(0).getLong(0)
      spark.sql(s"DROP TABLE IF EXISTS $tbl")
      n
    }
    val nDefault = buildAndCountFiles(s"${ctx.namespace}.t_dist_def_$fmt", "")
    val nHash    = buildAndCountFiles(s"${ctx.namespace}.t_dist_hash_$fmt", ", 'write.distribution-mode'='hash'")
    println(s"DIAG fork.partitionDist[$fmt]: defaultFiles=$nDefault hashFiles=$nHash " +
            s"(parts=$nParts tasks=$nTasks; default==hash => HASH-default build, default>hash => NONE-default #249)")
    // (a) Explicit HASH clusters by partition -> roughly one file per partition (allow slack for spill).
    assert(nHash <= nParts * 2,
      s"[$fmt] write.distribution-mode=hash should cluster to ~$nParts files, got $nHash")
    // (b) The default never clusters MORE than HASH (fork default is NONE => >=; never <).
    assert(nDefault >= nHash,
      s"[$fmt] default partitioned distribution produced FEWER files than HASH (default=$nDefault hash=$nHash) — unexpected; re-audit #249")
  }

  val forkPartitionDistOps: List[(String, Ctx => Unit)] = List(
    "fork.partitionDist.default @ parquet" -> forkPartitionDistDefault("parquet"),
    "fork.partitionDist.default @ orc"     -> forkPartitionDistDefault("orc")
  )

  private def softDeleteRestore(ctx: Ctx, db: String, tbl: String): Unit = {
    assert(HtsAdmin.softDelete(db, tbl)._1 / 100 == 2, s"soft-delete $db.$tbl failed")
    val ms = HtsAdmin.softDeletedAtMs(db, tbl).getOrElse(throw new AssertionError(s"no deletedAtMs for $db.$tbl"))
    assert(HtsAdmin.restore(db, tbl, ms)._1 / 100 == 2, s"restore $db.$tbl failed")
  }

  // ── Undrop 3-way compositions (Block 9, real HTS only) — restore's state-preservation, per feature ──
  // The undrop:* battery proves the whole op catalog works post-restore. These are pointed 3-way
  // chains that set up a SPECIFIC feature's state (branch / snapshot history / evolved schema),
  // destroy via soft-delete→restore, then consume that exact feature — the direct modality check that
  // restore's destruction set does not intersect refs / lineage / schema.

  // A pre-existing branch must survive the drop→undrop round-trip.
  def interactUndropBranchSurvives(ctx: Ctx): Unit = {
    val (table, db, tbl) = undropSeed(ctx, "t_ud_branch")
    ctx.spark.sql(s"ALTER TABLE $table CREATE BRANCH b")
    ctx.spark.sql(s"INSERT INTO $table.branch_b ${RowGenerator.valuesClause(Core, 2)}")   // branch diverges: 3+2=5
    softDeleteRestore(ctx, db, tbl)
    assert(ctx.spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "main row set changed across undrop")
    assert(ctx.spark.sql(s"SELECT count(*) FROM $table VERSION AS OF 'b'").collect()(0).getLong(0) == 5, "branch 'b' did not survive undrop")
    ctx.spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  // Snapshot history (time travel) must survive restore.
  def interactUndropTimeTravelSurvives(ctx: Ctx): Unit = {
    val (table, db, tbl) = undropSeed(ctx, "t_ud_tt")
    val firstSnap = ctx.spark.sql(s"SELECT snapshot_id FROM $table.snapshots ORDER BY committed_at LIMIT 1").collect()(0).getLong(0)
    ctx.spark.sql(s"INSERT INTO $table ${RowGenerator.valuesClause(Core, 2)}")            // 2nd snapshot: 5 rows
    softDeleteRestore(ctx, db, tbl)
    assert(ctx.spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 5, "current state changed across undrop")
    assert(ctx.spark.sql(s"SELECT count(*) FROM $table VERSION AS OF $firstSnap").collect()(0).getLong(0) == 3,
      "pre-restore snapshot not time-travellable after undrop (lineage lost)")
    ctx.spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  // Evolved schema must survive restore, and the restored table must still accept the evolved shape.
  def interactUndropSchemaSurvives(ctx: Ctx): Unit = {
    val (table, db, tbl) = undropSeed(ctx, "t_ud_schema")
    ctx.spark.sql(s"ALTER TABLE $table ADD COLUMN extra int")
    ctx.spark.sql(s"INSERT INTO $table VALUES (CAST(9 AS BIGINT), 9, 'row-9', 9.5, false, '2024-01-09-08', 99)")
    softDeleteRestore(ctx, db, tbl)
    assert(ctx.spark.sql(s"SELECT extra FROM $table WHERE ${Core.long0.columnName} = 9").collect()(0).getInt(0) == 99,
      "evolved column value lost across undrop")
    ctx.spark.sql(s"INSERT INTO $table VALUES (CAST(10 AS BIGINT), 10, 'row-10', 10.5, true, '2024-01-10-09', 100)")
    assert(ctx.spark.sql(s"SELECT count(*) FROM $table WHERE extra IS NOT NULL").collect()(0).getLong(0) == 2,
      "restored table did not accept the evolved schema for new writes")
    ctx.spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  val undropInteractOps: List[(String, Ctx => Unit)] = List(
    "interact.undrop.branchSurvives"    -> interactUndropBranchSurvives,
    "interact.undrop.timeTravelSurvives" -> interactUndropTimeTravelSurvives,
    "interact.undrop.schemaSurvives"    -> interactUndropSchemaSurvives
  )

  // ── Branching / WAP (format-agnostic → parquet only; behavior-focused, not matrixed) ─────────
  // A CoreTable row literal for branch writes (long,int,string,double,boolean,datepartition).
  private def coreRow(long: Long, tag: String): String =
    s"(CAST($long AS BIGINT), ${long.toInt}, '$tag', ${long}.5, false, '2024-01-01-00')"

  // B1(a) direct branch ops (no WAP needed): write to t.branch_b, read it via VERSION AS OF 'b';
  // main stays isolated.
  val branchDirectIsolation: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("branch.direct.create")(t => s"ALTER TABLE $t CREATE BRANCH b")()
      .step("branch.direct.isolation") { (spark, table) =>
        spark.sql(s"INSERT INTO $table.branch_b VALUES ${coreRow(99, "branch")}")
        val onBranch = spark.sql(s"SELECT count(*) FROM $table VERSION AS OF 'b'").collect()(0).getLong(0)
        val onMain   = spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0)
        assert(onBranch == 4, s"branch b should have 4 rows, got $onBranch")
        assert(onMain == 3, s"main should be unchanged at 3, got $onMain")                // isolation
      }()

  // B1(b) spark.wap.branch conf: with write.wap.enabled, the conf routes BOTH reads and writes to the
  // branch transparently; unsetting reverts to main.
  val branchWapConfRouting: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("branch.wapconf.enable")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .sql("branch.wapconf.create")(t => s"ALTER TABLE $t CREATE BRANCH wapbr")()
      .step("branch.wapConf.routing") { (spark, table) =>
        spark.conf.set("spark.wap.branch", "wapbr")
        val onBranch =
          try {
            spark.sql(s"INSERT INTO $table VALUES ${coreRow(99, "wap")}")                 // routed to branch
            spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0)             // reads branch
          } finally spark.conf.unset("spark.wap.branch")
        assert(onBranch == 4, s"on-branch read should see 4, got $onBranch")
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "main leaked")
      }()

  // B2 WAP stage → publish: a staged write (spark.wap.id) does NOT advance main; cherrypick publishes it.
  val wapStagePublish: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("wap.enable")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .step("wap.stagePublish") { (spark, table) =>
        spark.conf.set("spark.wap.id", "w1")
        try spark.sql(s"INSERT INTO $table VALUES ${coreRow(99, "staged")}")
        finally spark.conf.unset("spark.wap.id")
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "staged write leaked to main")
        val stagedId = spark.sql(s"SELECT snapshot_id FROM $table.snapshots WHERE summary['wap.id'] = 'w1'").collect()(0).getLong(0)
        spark.sql(s"CALL openhouse.system.cherrypick_snapshot('${catalogRelative(table)}', $stagedId)")
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 4, "publish did not advance main")
      }()

  // B3 DDL-on-branch is NOT isolated — characterizes the leak (finding): schema/props/sortOrder are
  // table-global; ADD COLUMN while "on branch" mutates MAIN's schema, with no guard.
  val branchDdlLeakAddColumn: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("branch.leak.enable")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .sql("branch.leak.create")(t => s"ALTER TABLE $t CREATE BRANCH leakbr")()
      .step("branch.ddlLeak.addColumn") { (spark, table) =>
        spark.conf.set("spark.wap.branch", "leakbr")
        try spark.sql(s"ALTER TABLE $table ADD COLUMN leaked_col int")
        finally spark.conf.unset("spark.wap.branch")
        val mainCols = spark.table(table).schema.fields.map(_.name).toSeq
        assert(mainCols.contains("leaked_col"),
          s"characterizing the leak: ADD COLUMN on a branch mutated MAIN's schema — expected leaked_col in $mainCols")
      }()

  // B4 representative branch DML (update + delete on a branch), isolated from main.
  val branchDmlUpdateDelete: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("branch.dml.enable")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .sql("branch.dml.create")(t => s"ALTER TABLE $t CREATE BRANCH dmlbr")()
      .step("branch.dml.updateDelete") { (spark, table) =>
        spark.conf.set("spark.wap.branch", "dmlbr")
        try {
          spark.sql(s"UPDATE $table SET ${Core.string0.columnName} = 'br-upd' WHERE ${Core.long0.columnName} = 1")
          spark.sql(s"DELETE FROM $table WHERE ${Core.long0.columnName} = 2")
        } finally spark.conf.unset("spark.wap.branch")
        val onBranch = spark.sql(s"SELECT count(*) FROM $table VERSION AS OF 'dmlbr'").collect()(0).getLong(0)
        assert(onBranch == 2, s"branch should have 2 rows after delete, got $onBranch")
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "main unchanged by branch DML")
        val br1 = spark.sql(s"SELECT ${Core.string0.columnName} FROM $table VERSION AS OF 'dmlbr' WHERE ${Core.long0.columnName} = 1").collect()(0).getString(0)
        assert(br1 == "br-upd", s"branch update not applied: $br1")
      }()

  // B5 lifecycle (CREATE TAG / DROP BRANCH — both supported, verified) + WAP mixing negatives.
  val branchCreateTag: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("branch.lifecycle.tag") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE TAG mytag")
        assert(spark.sql(s"SELECT count(*) FROM $table.refs WHERE name = 'mytag' AND type = 'TAG'").collect()(0).getLong(0) == 1,
          "CREATE TAG did not create the tag ref")
      }()

  val branchDropBranch: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("branch.drop.create")(t => s"ALTER TABLE $t CREATE BRANCH tmpbr")()
      .step("branch.lifecycle.dropBranch") { (spark, table) =>
        assert(spark.sql(s"SELECT count(*) FROM $table.refs WHERE name = 'tmpbr'").collect()(0).getLong(0) == 1, "branch not created")
        spark.sql(s"ALTER TABLE $table DROP BRANCH tmpbr")
        assert(spark.sql(s"SELECT count(*) FROM $table.refs WHERE name = 'tmpbr'").collect()(0).getLong(0) == 0, "DROP BRANCH did not remove the ref")
      }()

  val branchNegWapIdAndBranch: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("branch.neg.enable")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .sql("branch.neg.create")(t => s"ALTER TABLE $t CREATE BRANCH nb")()
      .step("branch.neg.wapIdAndBranch") { (spark, table) =>
        spark.conf.set("spark.wap.id", "w1")
        spark.conf.set("spark.wap.branch", "nb")
        try {
          val e = Check.intercept[ValidationException](spark.sql(s"INSERT INTO $table VALUES ${coreRow(99, "x")}"))
          assert(e.getMessage.contains("Cannot set both WAP ID and branch"), s"msg: ${e.getMessage.take(140)}")
        } finally { spark.conf.unset("spark.wap.id"); spark.conf.unset("spark.wap.branch") }
      }()

  val branchNegInsertNonexistent: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("branch.neg.insertNonexistentBranch") { (spark, table) =>
        val e = Check.intercept[ValidationException](spark.sql(s"INSERT INTO $table.branch_nope VALUES ${coreRow(99, "x")}"))
        assert(e.getMessage.contains("does not exist"), s"msg: ${e.getMessage.take(140)}")
      }()

  val branching: List[(String, TableTest[CoreTable.type])] = List(
    "branch.direct.isolation" -> branchDirectIsolation,
    "branch.wapConf.routing"  -> branchWapConfRouting,
    "wap.stagePublish"        -> wapStagePublish,
    "branch.ddlLeak.addColumn" -> branchDdlLeakAddColumn,
    "branch.dml.updateDelete" -> branchDmlUpdateDelete,
    "branch.lifecycle.tag"    -> branchCreateTag,
    "branch.lifecycle.dropBranch" -> branchDropBranch,
    "branch.neg.wapIdAndBranch" -> branchNegWapIdAndBranch,
    "branch.neg.insertNonexistentBranch" -> branchNegInsertNonexistent
  )

  // ── negative / contract tests ───────────────────────────────────────────────────────────
  // Create + seed a valid CoreTable, then assert the bad operation is rejected.
  private def coreNegative(label: String)(bad: (SparkSession, String) => Unit): TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(table => s"CREATE TABLE $table ($columnDefinitions) USING iceberg TBLPROPERTIES ('write.format.default'='parquet')")()
      .insert(3)()
      .step(label)(bad)()

  private val L = CoreTable.long0.columnName
  private val S = CoreTable.string0.columnName

  // Each negative asserts BOTH the exception type and a message substring, so it verifies the
  // operation was rejected for the RIGHT reason (not merely that something threw).
  val negNonExistentColumn: TableTest[CoreTable.type] =
    coreNegative("negative.nonExistentColumn") { (spark, table) =>
      val e = Check.intercept[AnalysisException](spark.sql(s"DELETE FROM $table WHERE no_such_column = 1"))
      assert(e.getMessage.contains("no_such_column"))
    }

  val negNonDeterministicDelete: TableTest[CoreTable.type] =
    coreNegative("negative.nonDeterministicDelete") { (spark, table) =>
      val e = Check.intercept[AnalysisException](spark.sql(s"DELETE FROM $table WHERE rand() < 0.5"))
      assert(e.getMessage.toLowerCase.contains("deterministic"))
    }

  val negNonDeterministicUpdate: TableTest[CoreTable.type] =
    coreNegative("negative.nonDeterministicUpdate") { (spark, table) =>
      val e = Check.intercept[AnalysisException](spark.sql(s"UPDATE $table SET $S = 'x' WHERE rand() < 0.5"))
      assert(e.getMessage.toLowerCase.contains("deterministic"))
    }

  val negInsertArity: TableTest[CoreTable.type] =
    coreNegative("negative.insertArity") { (spark, table) =>
      val e = Check.intercept[AnalysisException](spark.sql(s"INSERT INTO $table VALUES (CAST(1 AS BIGINT), 1)")) // too few columns
      assert(e.getMessage.toLowerCase.contains("not enough data columns"))
    }

  // Two UPDATE assignments to the same column in one MERGE clause → analysis error.
  val negMergeConflictingUpdates: TableTest[CoreTable.type] =
    coreNegative("negative.mergeConflictingUpdates") { (spark, table) =>
      val e = Check.intercept[AnalysisException](spark.sql(
        s"""MERGE INTO $table t USING (SELECT * FROM VALUES (CAST(2 AS BIGINT)) AS s($L)) s
            ON t.$L = s.$L
            WHEN MATCHED THEN UPDATE SET t.$S = 'a', t.$S = 'b'"""))
      assert(e.getMessage.contains("Multiple assignments"))
    }

  // Source has two rows matching the same target row → cardinality violation at RUNTIME. The
  // concrete runtime exception class (SparkRuntimeException) is package-private, so we anchor on
  // the specific message across the cause chain (the error may be wrapped in a task failure).
  val negMergeCardinalityViolation: TableTest[CoreTable.type] =
    coreNegative("negative.mergeCardinalityViolation") { (spark, table) =>
      val e = Check.intercept[Exception](spark.sql(
        s"""MERGE INTO $table t USING (
              SELECT * FROM VALUES (CAST(2 AS BIGINT), 'a'), (CAST(2 AS BIGINT), 'b') AS s($L, $S)
            ) s ON t.$L = s.$L
            WHEN MATCHED THEN UPDATE SET t.$S = s.$S"""))
      assert(
        Exceptions.causeChain(e).exists(t => Option(t.getMessage).exists(_.contains("matched a single row from the target table"))),
        s"expected a MERGE cardinality-violation message, got: ${e.getMessage}")
    }

  // CREATE partitioned by a non-existent column (on a scratch name, valid managed table stays).
  val negPartitionByNonExistent: TableTest[CoreTable.type] =
    coreNegative("negative.partitionByNonExistent") { (spark, table) =>
      val scratch = table + "_x"
      val e = Check.intercept[AnalysisException](spark.sql(
        s"CREATE TABLE $scratch ($columnDefinitions) USING iceberg PARTITIONED BY (no_such_column) TBLPROPERTIES ('write.format.default'='parquet')"))
      spark.sql(s"DROP TABLE IF EXISTS $scratch")
      assert(e.getMessage.contains("no_such_column"))
    }

  val negatives: List[(String, TableTest[CoreTable.type])] = List(
    "negative.nonExistentColumn"        -> negNonExistentColumn,
    "negative.nonDeterministicDelete"   -> negNonDeterministicDelete,
    "negative.nonDeterministicUpdate"   -> negNonDeterministicUpdate,
    "negative.insertArity"              -> negInsertArity,
    "negative.mergeConflictingUpdates"  -> negMergeConflictingUpdates,
    "negative.mergeCardinalityViolation" -> negMergeCardinalityViolation,
    "negative.partitionByNonExistent"   -> negPartitionByNonExistent
  )

  // ── DDL Phase 13: schema-evolution negatives ────────────────────────────────────────────
  // DROP COLUMN fails at COMMIT (server 400 → Iceberg BadRequestException); the message carries the
  // full body incl. schema dump (AUDIT-FINDINGS B — a "dumb" message), so we anchor on the meaningful
  // "Some columns are dropped" reason. Narrowing / SET NOT NULL are caught earlier at Spark analysis
  // (ExtendedAnalysisException, a subtype of AnalysisException) with clean messages.
  // NOTE: RENAME COLUMN is NOT rejected — it is supported (see ddlRenameColumn in Phase 12).
  // DROP COLUMN rejects — but the message is `Column[foo_col_int] not found in newSchema` (buried in a
  // double schema dump); it never says "you cannot drop columns" (AUDIT-FINDINGS B, a readability gap).
  val ddlNegDropColumn: TableTest[CoreTable.type] =
    coreNegative("ddl.neg.dropColumn") { (spark, table) =>
      val e = Check.intercept[BadRequestException](spark.sql(s"ALTER TABLE $table DROP COLUMN ${Core.int0.columnName}"))
      assert(e.getMessage.contains("not found in newSchema"), s"unexpected message: ${e.getMessage.take(160)}")
      assert(e.getMessage.contains(Core.int0.columnName), s"message should name the dropped column: ${e.getMessage.take(160)}")
    }

  val ddlNegNarrowType: TableTest[CoreTable.type] =
    coreNegative("ddl.neg.narrowType") { (spark, table) =>
      val e = Check.intercept[AnalysisException](spark.sql(s"ALTER TABLE $table ALTER COLUMN ${Core.long0.columnName} TYPE int"))
      assert(e.getMessage.contains("NOT_SUPPORTED_CHANGE_COLUMN"), s"unexpected message: ${e.getMessage.take(160)}")
    }

  val ddlNegSetNotNull: TableTest[CoreTable.type] =
    coreNegative("ddl.neg.setNotNull") { (spark, table) =>
      val e = Check.intercept[AnalysisException](spark.sql(s"ALTER TABLE $table ALTER COLUMN ${Core.string0.columnName} SET NOT NULL"))
      assert(e.getMessage.contains("Cannot change nullable column to non-nullable"), s"unexpected message: ${e.getMessage.take(160)}")
    }

  val ddlNegatives: List[(String, TableTest[CoreTable.type])] = List(
    "ddl.neg.dropColumn" -> ddlNegDropColumn,
    "ddl.neg.narrowType" -> ddlNegNarrowType,
    "ddl.neg.setNotNull" -> ddlNegSetNotNull
  )

  // ── DDL Phase 14: table properties (user keys, reserved-key rejection, forced-override findings) ─
  // Self-contained pipelines (parquet) — property behavior is layout-invariant. `tableProps` reads
  // back via SHOW TBLPROPERTIES.
  private def tableProps(spark: SparkSession, table: String): Map[String, String] =
    spark.sql(s"SHOW TBLPROPERTIES $table").collect().toSeq.map(r => r.getString(0) -> r.getString(1)).toMap

  private def propsCreate(label: String, tblprops: String)(check: StepView[CoreTable.type] => Unit): TableTest[CoreTable.type] =
    TableTest(Core).sql(label)(table =>
      s"CREATE TABLE $table ($columnDefinitions) USING iceberg TBLPROPERTIES ($tblprops)")(check)

  // user key round-trips: SET then read back, UNSET removes it
  val ddlPropsUserRoundTrip: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("ddl.props.userRoundTrip.create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES ('write.format.default'='parquet')")()
      .sql("ddl.props.userRoundTrip.set")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('my_key'='my_val')") { view =>
        assert(tableProps(view.spark, view.table).get("my_key").contains("my_val"), "user prop not set")
      }
      .sql("ddl.props.userRoundTrip.unset")(t => s"ALTER TABLE $t UNSET TBLPROPERTIES ('my_key')") { view =>
        assert(!tableProps(view.spark, view.table).contains("my_key"), "user prop not removed")
      }

  // reserved-key rejection: an openhouse.* key hits the clean server guard (ALTER_RESERVED_TBLPROPS →
  // 400 → BadRequestException). NOTE: `policies` specifically is value-parsed on the CLIENT first, so
  // SET('policies'='x') throws a Gson JsonParseException before the guard — recorded in AUDIT-FINDINGS.
  val ddlPropsReservedOpenhouse: TableTest[CoreTable.type] =
    coreNegative("ddl.props.reservedOpenhouse") { (spark, table) =>
      val e = Check.intercept[BadRequestException](spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('openhouse.tableUUID'='deadbeef')"))
      assert(e.getMessage.toLowerCase.contains("restriction"), s"msg: ${e.getMessage.take(200)}")
    }

  // finding: format-version is forced to the cluster default (2) — a create with '1' still reads 2
  val ddlPropsFormatVersionForced: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES ('write.format.default'='parquet', 'format-version'='1')")()
      .insert(3)()
      .check("ddl.props.formatVersionForced") { view =>
        val fv = tableProps(view.spark, view.table).get("format-version")
        assert(fv.contains("2"), s"expected forced format-version=2, got $fv")
        assert(view.after.size == 3, "table not writable at the forced format-version")   // DML-after-DDL
      }

  // honored-if-set: previous-versions-max the user provides survives
  val ddlPropsPreviousVersionsHonored: TableTest[CoreTable.type] =
    propsCreate("ddl.props.previousVersionsHonored", "'write.format.default'='parquet', 'write.metadata.previous-versions-max'='7'") { view =>
      val v = tableProps(view.spark, view.table).get("write.metadata.previous-versions-max")
      assert(v.contains("7"), s"expected previous-versions-max=7, got $v")
    }

  val ddlPropsOperations: List[(String, TableTest[CoreTable.type])] = List(
    "ddl.props.userRoundTrip"          -> ddlPropsUserRoundTrip,
    "ddl.props.reservedOpenhouse"      -> ddlPropsReservedOpenhouse,
    "ddl.props.formatVersionForced"    -> ddlPropsFormatVersionForced,
    "ddl.props.previousVersionsHonored"-> ddlPropsPreviousVersionsHonored
  )

  private def coreCreateParquet(table: String): String =
    s"CREATE TABLE $table ($columnDefinitions) USING iceberg TBLPROPERTIES ('write.format.default'='parquet')"

  // ── DDL Phase 16: sort order / write distribution ───────────────────────────────────────
  // WRITE ORDERED BY sets the sort order; the observable side effect is write.distribution-mode=range
  // (the recon's CatalogOperationTest asserts this). WRITE UNORDERED clears the order.
  val ddlWriteOrderedBy: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("ddl.sortOrder.orderedBy")(t => s"ALTER TABLE $t WRITE ORDERED BY ${Core.long0.columnName}") { view =>
        assert(tableProps(view.spark, view.table).get("write.distribution-mode").contains("range"),
          s"distribution-mode not range: ${tableProps(view.spark, view.table).get("write.distribution-mode")}")
      }

  val ddlWriteOrderedByMulti: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("ddl.sortOrder.orderedByMulti")(t =>
        s"ALTER TABLE $t WRITE ORDERED BY ${Core.string0.columnName} DESC NULLS FIRST, ${Core.long0.columnName}") { view =>
        assert(tableProps(view.spark, view.table).get("write.distribution-mode").contains("range"), "multi-col ordered-by should set range")
      }
      .insert(2) { view => assert(view.after.size == 5, "multi-col ordered write path failed") }   // DML-after-DDL

  // ── DDL Phase 17: rename table (rename to scratch + back, so the harness's fixed table name resolves) ─
  val ddlRenameTable: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("ddl.renameTable") { (spark, table) =>
        val scratch = s"${table}_ren"
        spark.sql(s"ALTER TABLE $table RENAME TO $scratch")
        assert(spark.sql(s"SELECT count(*) FROM $scratch").collect()(0).getLong(0) == 3, "renamed table lost rows")
        Check.intercept[Exception](spark.sql(s"SELECT 1 FROM $table LIMIT 1"))          // old name is gone
        spark.sql(s"ALTER TABLE $scratch RENAME TO $table")                             // restore for teardown
      }()

  val ddlRenameTableConflict: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("ddl.renameTable.conflict") { (spark, table) =>
        val other = s"${table}_other"
        spark.sql(s"DROP TABLE IF EXISTS $other")
        spark.sql(coreCreateParquet(other))
        val e = Check.intercept[WebClientResponseWithMessageException](spark.sql(s"ALTER TABLE $table RENAME TO $other")) // target exists
        assert(e.getMessage.contains("already exists"), s"msg: ${e.getMessage.take(160)}")
        spark.sql(s"DROP TABLE IF EXISTS $other")
      }()

  // ── DDL Phase 19: namespace DDL negatives (OpenHouse rejects create/drop) ──────────────────
  // Both CREATE and DROP NAMESPACE surface `UnsupportedOperationException: "Describing database is not
  // supported"` — Spark calls loadNamespaceMetadata first, so the user gets a *describe* message for a
  // create/drop (a misleading message — AUDIT-FINDINGS B). We anchor on the stable "not supported".
  val ddlNegCreateNamespace: TableTest[CoreTable.type] =
    coreNegative("ddl.ns.createRejected") { (spark, _) =>
      val e = Check.intercept[UnsupportedOperationException](spark.sql("CREATE NAMESPACE openhouse.a_new_db"))
      assert(e.getMessage.contains("not supported"), s"msg: ${e.getMessage.take(160)}")
    }

  val ddlNegDropNamespace: TableTest[CoreTable.type] =
    coreNegative("ddl.ns.dropRejected") { (spark, _) =>
      val e = Check.intercept[UnsupportedOperationException](spark.sql("DROP NAMESPACE openhouse.dbMatrix"))
      assert(e.getMessage.contains("not supported"), s"msg: ${e.getMessage.take(160)}")
    }

  val ddlMiscOperations: List[(String, TableTest[CoreTable.type])] = List(
    "ddl.sortOrder.orderedBy"      -> ddlWriteOrderedBy,
    "ddl.sortOrder.orderedByMulti" -> ddlWriteOrderedByMulti,
    "ddl.renameTable"              -> ddlRenameTable,
    "ddl.renameTable.conflict"     -> ddlRenameTableConflict,
    "ddl.ns.createRejected"        -> ddlNegCreateNamespace,
    "ddl.ns.dropRejected"          -> ddlNegDropNamespace
  )

  // ── DDL Phase 20: policy DDL (OpenHouse SQL extension: ALTER TABLE … SET/UNSET POLICY) ──────
  private def policiesBlob(view: StepView[CoreTable.type]): String =
    tableProps(view.spark, view.table).getOrElse("policies", "")

  val ddlPolicySharing: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("ddl.policy.sharing")(t => s"ALTER TABLE $t SET POLICY (SHARING=TRUE)") { view =>
        assert(policiesBlob(view).toLowerCase.contains("true") || policiesBlob(view).toLowerCase.contains("sharing"),
          s"sharing policy not stored: ${policiesBlob(view)}")
        assert(view.after.size == 3, "table not queryable after SET POLICY (SHARING)")     // DML-after-DDL
      }

  val ddlPolicyHistory: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("ddl.policy.history")(t => s"ALTER TABLE $t SET POLICY (HISTORY MAX_AGE=2D VERSIONS=20)") { view =>
        assert(policiesBlob(view).contains("20") || policiesBlob(view).toLowerCase.contains("history"),
          s"history policy not stored: ${policiesBlob(view)}")
        assert(view.after.size == 3, "table not queryable after SET POLICY (HISTORY)")     // DML-after-DDL
      }

  val ddlPolicyReplicationRoundTrip: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("ddl.policy.replication.set")(t => s"ALTER TABLE $t SET POLICY (REPLICATION = ({destination:'clusterA'}))")()
      .sql("ddl.policy.replication.unset")(t => s"ALTER TABLE $t UNSET POLICY (REPLICATION)") { view =>
        assert(view.after.size == 3)                                                    // survives set+unset
      }

  val ddlPolicyNegHistoryMaxAge: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("ddl.policy.neg.historyMaxAge") { (spark, table) =>
        val e = Check.intercept[BadRequestException](spark.sql(s"ALTER TABLE $table SET POLICY (HISTORY MAX_AGE=5D)")) // > 3 days
        assert(e.getMessage.contains("max age must be between 1 to 3 days"), s"msg: ${e.getMessage.take(160)}")
      }()

  val ddlPolicyNegHistoryVersions: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("ddl.policy.neg.historyVersions") { (spark, table) =>
        val e = Check.intercept[BadRequestException](spark.sql(s"ALTER TABLE $table SET POLICY (HISTORY VERSIONS=200)")) // > 100
        assert(e.getMessage.contains("must be between 2 to 100 versions"), s"msg: ${e.getMessage.take(160)}")
      }()

  // Retention on a (string) time-partitioned column requires a column pattern (a valid DateTimeFormatter).
  val ddlPolicyRetention: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg PARTITIONED BY (datepartition) TBLPROPERTIES ('write.format.default'='parquet')")().insert(3)()
      .sql("ddl.policy.retention")(t => s"ALTER TABLE $t SET POLICY (RETENTION = 30d ON COLUMN datepartition WHERE pattern = 'yyyy-MM-dd-HH')") { view =>
        assert(policiesBlob(view).toLowerCase.contains("retention") || policiesBlob(view).contains("30"),
          s"retention policy not stored: ${policiesBlob(view)}")
        assert(view.after.size == 3, "table not queryable after SET POLICY (RETENTION)")   // DML-after-DDL
      }

  val ddlPolicyOperations: List[(String, TableTest[CoreTable.type])] = List(
    "ddl.policy.sharing"               -> ddlPolicySharing,
    "ddl.policy.history"               -> ddlPolicyHistory,
    "ddl.policy.replication"           -> ddlPolicyReplicationRoundTrip,
    "ddl.policy.retention"             -> ddlPolicyRetention,
    "ddl.policy.neg.historyMaxAge"     -> ddlPolicyNegHistoryMaxAge,
    "ddl.policy.neg.historyVersions"   -> ddlPolicyNegHistoryVersions
  )

  // ── DDL Phase 18: CTAS / RTAS ───────────────────────────────────────────────────────────
  val ddlCtas: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("ddl.ctas") { (spark, table) =>
        val tgt = s"${table}_ctas"
        spark.sql(s"DROP TABLE IF EXISTS $tgt")
        spark.sql(s"CREATE TABLE $tgt USING iceberg AS SELECT * FROM $table")
        assert(spark.sql(s"SELECT count(*) FROM $tgt").collect()(0).getLong(0) == 3, "CTAS lost rows")
        spark.sql(s"DROP TABLE IF EXISTS $tgt")
      }()

  val ddlRtasEnabled: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("ddl.rtas.enable")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('replace.enabled'='true')")()
      .step("ddl.rtas.enabled") { (spark, table) =>
        spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "RTAS did not replace")
      }()

  val ddlRtasDisabled: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("ddl.rtas.disabled") { (spark, table) =>
        val e = Check.intercept[BadRequestException](spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table"))
        assert(e.getMessage.contains("REPLACE TABLE AS SELECT is not enabled"), s"msg: ${e.getMessage.take(160)}")
      }()

  val ddlRtasReplicationConflict: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("ddl.rtas.repl.enable")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('replace.enabled'='true')")()
      .sql("ddl.rtas.repl.policy")(t => s"ALTER TABLE $t SET POLICY (REPLICATION = ({destination:'clusterA'}))")()
      .step("ddl.rtas.replicationConflict") { (spark, table) =>
        val e = Check.intercept[BadRequestException](spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table"))
        assert(e.getMessage.contains("while replication is enabled"), s"msg: ${e.getMessage.take(160)}")
      }()

  val ddlCtasRtasOperations: List[(String, TableTest[CoreTable.type])] = List(
    "ddl.ctas"                     -> ddlCtas,
    "ddl.rtas.enabled"             -> ddlRtasEnabled,
    "ddl.rtas.disabled"            -> ddlRtasDisabled,
    "ddl.rtas.replicationConflict" -> ddlRtasReplicationConflict
  )

  // ── DDL Phase 22: column tags + ACL (metadata/ACL-plane; tags do NOT mask query results) ────
  val ddlColumnTag: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("ddl.colTag")(t => s"ALTER TABLE $t MODIFY COLUMN ${Core.string0.columnName} SET TAG = (PII)") { view =>
        val vals = view.spark.sql(s"SELECT ${Core.string0.columnName} FROM ${view.table} ORDER BY ${Core.long0.columnName}").collect().toSeq.map(_.getString(0))
        assert(vals == Seq("row-1", "row-2", "row-3"), s"SET TAG changed query results (should not mask): $vals")
      }

  val ddlAclGrantUnshared: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("ddl.acl.grantUnshared") { (spark, table) =>
        val e = Check.intercept[IllegalArgumentException](spark.sql(s"GRANT SELECT ON TABLE $table TO test_user"))
        assert(e.getMessage.contains("is not a shared table"), s"msg: ${e.getMessage.take(160)}")
      }()

  // After SHARING=TRUE the grant is accepted (the embedded auth handler records it, no throw).
  val ddlAclGrantShared: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("ddl.acl.share")(t => s"ALTER TABLE $t SET POLICY (SHARING=TRUE)")()
      .sql("ddl.acl.grantShared")(t => s"GRANT SELECT ON TABLE $t TO test_user") { view =>
        assert(view.after.size == 3, "shared/granted table not queryable")               // DML-after-DDL
      }

  // ── DDL Phase 15: feature-flag property (write.distribution-mode governs the write path) ─
  val ddlFeatureDistributionMode: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES ('write.format.default'='parquet', 'write.distribution-mode'='none')")()
      .insert(3)()
      .check("ddl.featureFlag.distributionMode") { view =>
        assert(tableProps(view.spark, view.table).get("write.distribution-mode").contains("none"),
          s"distribution-mode not honored: ${tableProps(view.spark, view.table).get("write.distribution-mode")}")
        assert(view.after.size == 3, "table not writable under distribution-mode=none")   // DML-after-DDL
      }

  // ── DDL Phase 23: replication / table-type contract (SQL-reachable) ─────────────────────────
  val ddlReplTableTypeImmutable: TableTest[CoreTable.type] =
    coreNegative("ddl.repl.tableTypeImmutable") { (spark, table) =>
      val e = Check.intercept[BadRequestException](spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('openhouse.tableType'='REPLICA_TABLE')"))
      assert(e.getMessage.contains("restriction"), s"msg: ${e.getMessage.take(160)}")
    }

  val ddlTagAclFeatureOperations: List[(String, TableTest[CoreTable.type])] = List(
    "ddl.colTag"                       -> ddlColumnTag,
    "ddl.acl.grantUnshared"            -> ddlAclGrantUnshared,
    "ddl.acl.grantShared"              -> ddlAclGrantShared,
    "ddl.featureFlag.distributionMode" -> ddlFeatureDistributionMode,
    "ddl.repl.tableTypeImmutable"      -> ddlReplTableTypeImmutable
  )

  // ── DDL Phase 24b: encryption — asserts the INTENDED behavior, tagged SKIP in OSS ─────────────
  // The KMS plugin is external/private (a repo-wide search finds no EncryptionManager /
  // KeyManagementClient / crypto factory / interface / mock). This test asserts what SHOULD happen —
  // with encryption configured, the data file must NOT be readable as plaintext parquet. In OSS the
  // hook is un-wired so files are plaintext and this would fail; it is tagged in Plan.knownBugs and
  // reports SKIP until the private plugin is present (then unskip to validate encryption-ON).
  val ddlEncryptionActive: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES (" +
        s"'write.format.default'='parquet', 'encryption.key-id'='k1', 'write.metadata.encryption.gcm-key-id'='k1')")()
      .insert(3)()
      .check("ddl.encryption.active") { view =>
        val filePath = view.spark.sql(s"SELECT file_path FROM ${view.table}.files LIMIT 1").collect()(0).getString(0).stripPrefix("file:")
        val head = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)).take(4))
        assert(head != "PAR1", s"encryption not in force — data file is plaintext parquet (magic=$head); requires the private KMS plugin")
      }

  val ddlEncryptionOperations: List[(String, TableTest[CoreTable.type])] = List(
    "ddl.encryption.active" -> ddlEncryptionActive
  )

  // ═══ Feature-INTERACTION axis (INTERACTION-AUDIT.md) — behaviors, single layout ══════════════
  // Characterization stance: rejections are PINS of current behavior (tripwires), not contracts;
  // a pin that starts failing means the product changed — update the pin and activate the dormant
  // coverage it gates (see the pin inventory in INTERACTION-AUDIT.md §2b).

  private val extraColInsert9  = "(CAST(9 AS BIGINT), 9, 'row-9', 9.5, true, '2024-01-09-01', 42)"
  private val extraColInsert10 = "(CAST(10 AS BIGINT), 10, 'row-10', 10.5, true, '2024-01-10-01', 43)"

  // ── DDL × history ──────────────────────────────────────────────────────────────────────────
  val interactTtAfterAddColumn: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("interact.ddl.ttAfterAddColumn") { (spark, table) =>
        val s0 = snapshotIds(spark, table).last
        spark.sql(s"ALTER TABLE $table ADD COLUMN extra_col INT")
        spark.sql(s"INSERT INTO $table VALUES $extraColInsert9")
        val current = spark.sql(s"SELECT * FROM $table LIMIT 1").columns.toSeq
        val travel  = spark.sql(s"SELECT * FROM $table VERSION AS OF $s0 LIMIT 1").columns.toSeq
        assert(current.contains("extra_col"), s"current read missing evolved column: $current")
        assert(!travel.contains("extra_col") && travel.size == Core.tableColumns.size,
          s"time travel must read with the SNAPSHOT's schema (no extra_col): $travel")
        assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF $s0").collect()(0).getLong(0) == 3,
          "pre-DDL snapshot row count wrong")
      }()

  val interactRestoreAfterAddColumn: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("interact.ddl.restoreAfterAddColumn") { (spark, table) =>
        val s0 = snapshotIds(spark, table).last
        spark.sql(s"ALTER TABLE $table ADD COLUMN extra_col INT")
        spark.sql(s"INSERT INTO $table VALUES $extraColInsert9")
        spark.sql(s"CALL openhouse.system.rollback_to_snapshot('${catalogRelative(table)}', $s0)")
        val cols = spark.sql(s"SELECT * FROM $table LIMIT 1").columns.toSeq
        assert(cols.contains("extra_col"), s"rollback rolls back DATA only — schema keeps the evolved column: $cols")
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "data not rolled back")
        assert(spark.sql(s"SELECT count(*) FROM $table WHERE extra_col IS NOT NULL").collect()(0).getLong(0) == 0,
          "rolled-back rows must read the evolved column as null")
        spark.sql(s"INSERT INTO $table VALUES $extraColInsert10") // table stays writable at the evolved arity
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 4, "post-rollback insert failed")
      }()

  // E1: data in the evolved column, then the (currently pinned-rejected) DROP — table stays intact.
  // Gating pin: if DROP COLUMN support ever lands this fails → extend to full post-drop coverage.
  val interactDropColAfterData: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("interact.ddl.dropColAfterData") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table ADD COLUMN extra_col INT")
        spark.sql(s"INSERT INTO $table VALUES $extraColInsert9")
        val e = Check.intercept[BadRequestException](spark.sql(s"ALTER TABLE $table DROP COLUMN extra_col"))
        assert(e.getMessage.contains("not found in newSchema"), s"drop rejection message changed: ${e.getMessage.take(200)}")
        assert(spark.sql(s"SELECT count(*) FROM $table WHERE extra_col = 42").collect()(0).getLong(0) == 1,
          "rejected drop must leave the column's data readable")
        spark.sql(s"INSERT INTO $table VALUES $extraColInsert10")
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 5,
          "rejected drop must leave the table writable")
      }()

  // ── RTAS × history / lineage ───────────────────────────────────────────────────────────────
  private def rtasPrep: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("enableReplace")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('replace.enabled'='true')")()

  val interactRtasHistoryPreserved: TableTest[CoreTable.type] =
    rtasPrep.step("interact.rtas.historyPreserved") { (spark, table) =>
      val pre = snapshotIds(spark, table).last
      spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
      assert(spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0) == 2,
        "pre-RTAS snapshots must survive the replace")
      assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF $pre").collect()(0).getLong(0) == 3,
        "time travel to a pre-RTAS snapshot must work")
    }()

  val interactRtasRestoreRejected: TableTest[CoreTable.type] =
    rtasPrep.step("interact.rtas.restoreRejected") { (spark, table) =>
      val pre = snapshotIds(spark, table).last
      spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
      val e = Check.intercept[ValidationException](
        spark.sql(s"CALL openhouse.system.rollback_to_snapshot('${catalogRelative(table)}', $pre)"))
      assert(e.getMessage.contains("not an ancestor"),
        s"rollback across RTAS: expected the new-lineage/ancestry rejection, got: ${e.getMessage.take(200)}")
    }()

  // The recovery path rollback can't provide: set_current_snapshot has no ancestry requirement.
  val interactRtasSetCurrentRecovery: TableTest[CoreTable.type] =
    rtasPrep.step("interact.rtas.setCurrentRecovery") { (spark, table) =>
      val pre = snapshotIds(spark, table).last
      spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
      spark.sql(s"CALL openhouse.system.set_current_snapshot('${catalogRelative(table)}', $pre)")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3,
        "set_current_snapshot must recover the pre-RTAS state (no ancestry requirement)")
    }()

  val interactRtasWriteAfter: TableTest[CoreTable.type] =
    rtasPrep.step("interact.rtas.writeAfter") { (spark, table) =>
      spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
      spark.sql(s"INSERT INTO $table VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3,
        "replaced table must stay writable (DML-after-RTAS)")
    }()

  // G9 (partition half): the replace path skips checkPartitionSpecEvolution — RTAS CAN change the
  // spec where ALTER is pinned-rejected. Characterizes the bypass; if this ever fails, the guard
  // was extended to the replace path — update AUDIT-FINDINGS G9.
  val interactRtasPartitionSpecChange: TableTest[CoreTable.type] =
    rtasPrep.step("interact.rtas.partitionSpecChange") { (spark, table) =>
      spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg PARTITIONED BY (datepartition) AS SELECT * FROM $table")
      val desc = spark.sql(s"DESCRIBE TABLE $table").collect().toSeq
      // Confirmed live: the table gains a "# Partition Information" section (datepartition listed
      // both as a column and as a partition field) — the spec changed where ALTER is pinned-rejected.
      assert(desc.exists(_.getString(0) == "# Partition Information") &&
             desc.count(_.getString(0) == "datepartition") == 2,
        s"G9 appears FIXED — RTAS no longer changes the partition spec; update AUDIT-FINDINGS G9. DESCRIBE:\n" +
          desc.map(_.mkString(" | ")).mkString("\n"))
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "rows lost in re-spec RTAS")
    }()

  // G9 (schema half): column drop via RTAS projection, where ALTER DROP COLUMN is pinned-rejected.
  // Confirmed live (first run failed on the harness's own read-back because the column was GONE).
  // Runs on a side table so the pipeline's implicit full-schema read-back stays valid.
  val interactRtasDropsColumn: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("interact.rtas.dropsColumn") { (spark, table) =>
        val side = s"${table}_dropcol"
        spark.sql(s"DROP TABLE IF EXISTS $side")
        try {
          spark.sql(s"CREATE TABLE $side USING iceberg TBLPROPERTIES ('replace.enabled'='true') AS SELECT * FROM $table")
          spark.sql(s"CREATE OR REPLACE TABLE $side USING iceberg AS " +
            s"SELECT ${Core.long0.columnName}, ${Core.string0.columnName} FROM $side")
          val cols = spark.sql(s"SELECT * FROM $side LIMIT 1").columns.toSeq
          assert(cols == Seq(Core.long0.columnName, Core.string0.columnName),
            s"G9 appears FIXED — RTAS no longer drops columns (ALTER DROP stays rejected); update AUDIT-FINDINGS G9: $cols")
          assert(spark.sql(s"SELECT count(*) FROM $side").collect()(0).getLong(0) == 3, "rows lost in column-drop RTAS")
        } finally spark.sql(s"DROP TABLE IF EXISTS $side")
      }()

  // ── RTAS × table-property merge semantics (the THIRD property path beside CREATE and ALTER) ──
  val interactRtasPropsUserSurvival: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES (" +
        s"'write.format.default'='parquet', 'replace.enabled'='true', 'user.key'='v1')")()
      .insert(3)()
      .step("interact.rtas.props.userSurvival") { (spark, table) =>
        spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
        val p = tableProps(spark, table)
        assert(p.get("user.key").contains("v1"), s"user prop lost across RTAS: user.key=${p.get("user.key")}")
        assert(p.get("replace.enabled").contains("true"), s"replace.enabled lost across RTAS: ${p.get("replace.enabled")}")
      }()

  val interactRtasPropsStatementWins: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES (" +
        s"'write.format.default'='parquet', 'replace.enabled'='true', 'user.key'='v1')")()
      .insert(3)()
      .step("interact.rtas.props.statementWins") { (spark, table) =>
        spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg TBLPROPERTIES ('user.key'='v2') " +
          s"AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
        val p = tableProps(spark, table)
        assert(p.get("user.key").contains("v2"), s"statement TBLPROPERTIES must win over the old value: ${p.get("user.key")}")
        assert(p.get("replace.enabled").contains("true"),
          s"props NOT named in the statement must still survive (merge, not wholesale replace): ${p.get("replace.enabled")}")
      }()

  val interactRtasPropsCreateDefaulting: TableTest[CoreTable.type] =
    rtasPrep.step("interact.rtas.props.createDefaulting") { (spark, table) =>
      spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg TBLPROPERTIES ('write.format.default'='orc') " +
        s"AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
      val p = tableProps(spark, table)
      assert(p.get("write.format.default").contains("orc"),
        s"RTAS can change the storage format where ALTER can't rewrite: ${p.get("write.format.default")}")
      assert(p.get("format-version").forall(_ == "2"), s"forced format-version drifted: ${p.get("format-version")}")
      spark.sql(s"INSERT INTO $table VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "orc-format table not writable")
    }()

  val interactRtasPropsReservedPlane: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg PARTITIONED BY (datepartition) TBLPROPERTIES (" +
        s"'write.format.default'='parquet', 'replace.enabled'='true')")()
      .insert(3)()
      .sql("setRetention")(t => s"ALTER TABLE $t SET POLICY (RETENTION = 30d ON COLUMN datepartition WHERE pattern = 'yyyy-MM-dd-HH')")()
      .step("interact.rtas.props.reservedPlane") { (spark, table) =>
        val uuidBefore = tableProps(spark, table).getOrElse("openhouse.tableUUID", "<absent>")
        spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg PARTITIONED BY (datepartition) " +
          s"AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
        val p = tableProps(spark, table)
        assert(p.getOrElse("openhouse.tableUUID", "<absent>") == uuidBefore,
          s"tableUUID must be preserved across RTAS: $uuidBefore -> ${p.get("openhouse.tableUUID")}")
        // G10 (confirmed live): RTAS silently WIPES the policies plane — the retention policy set
        // before the replace is gone after it (while tableUUID survives). Characterizes the bug;
        // if this fails, G10 was fixed — flip to a survival assertion and update AUDIT-FINDINGS.
        val policiesAfter = p.get("policies")
        assert(policiesAfter.forall(b => !b.toLowerCase.contains("retention")),
          s"G10 appears FIXED — retention policy survived RTAS; update AUDIT-FINDINGS G10 and flip this test: $policiesAfter")
      }()

  // RTAS on a table with an existing branch: refs travel in the replace payload — branch survives,
  // still readable at its (old-lineage) head.
  val interactRtasWithBranch: TableTest[CoreTable.type] =
    rtasPrep.step("interact.rtas.withBranch") { (spark, table) =>
      spark.sql(s"ALTER TABLE $table CREATE BRANCH keepbr")
      spark.sql(s"INSERT INTO $table.branch_keepbr VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
      spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
      val refs = spark.sql(s"SELECT name FROM $table.refs").collect().toSeq.map(_.getString(0)).toSet
      assert(refs.contains("keepbr"), s"branch ref lost across RTAS: $refs")
      assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF 'keepbr'").collect()(0).getLong(0) == 4,
        "branch head (old lineage) unreadable after RTAS")
    }()

  // ── branch × history / maintenance ─────────────────────────────────────────────────────────
  val interactBranchTtBeforeBranchPoint: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("interact.branch.ttBeforeBranchPoint") { (spark, table) =>
      val snaps = snapshotIds(spark, table)
      val ts0 = spark.sql(s"SELECT committed_at FROM $table.snapshots ORDER BY committed_at LIMIT 1").collect()(0).getTimestamp(0)
      spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='true')")
      spark.sql(s"ALTER TABLE $table CREATE BRANCH tb")
      spark.sql(s"INSERT INTO $table.branch_tb VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
      assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF 'tb'").collect()(0).getLong(0) == 6, "branch head")
      assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF ${snaps.head}").collect()(0).getLong(0) == 3,
        "snapshot-id travel to a pre-branch-point ancestor must work")
      spark.conf.set("spark.wap.branch", "tb")
      try {
        assert(spark.sql(s"SELECT count(*) FROM $table TIMESTAMP AS OF '$ts0'").collect()(0).getLong(0) == 3,
          "explicit TIMESTAMP AS OF must override spark.wap.branch and resolve against main history")
        assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF ${snaps.head}").collect()(0).getLong(0) == 3,
          "explicit VERSION AS OF must override spark.wap.branch")
      } finally spark.conf.unset("spark.wap.branch")
    }()

  // E5 characterization (mirror of G8): DDL on MAIN hits branches immediately — schema is
  // table-global, and an old-arity branch writer is broken mid-flight.
  val interactBranchMainDdlImmediate: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("interact.branch.mainDdlImmediate") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='true')")
        spark.sql(s"ALTER TABLE $table CREATE BRANCH mb")
        spark.sql(s"INSERT INTO $table.branch_mb VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
        spark.sql(s"ALTER TABLE $table ADD COLUMN extra_col INT") // DDL on MAIN
        val branchCols = spark.sql(s"SELECT * FROM $table VERSION AS OF 'mb' LIMIT 1").columns.toSeq
        assert(branchCols.contains("extra_col"), s"main DDL is table-global — branch reads see it immediately: $branchCols")
        val e = Check.intercept[AnalysisException](
          spark.sql(s"INSERT INTO $table.branch_mb VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')"))
        assert(e.getMessage.toLowerCase.contains("not enough data columns"),
          s"old-arity branch writer must break after main DDL (characterizes the hazard): ${e.getMessage.take(200)}")
        spark.sql(s"INSERT INTO $table.branch_mb VALUES (CAST(8 AS BIGINT), 8, 'row-8', 8.5, true, '2024-01-08-07', 44)")
        assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF 'mb'").collect()(0).getLong(0) == 5,
          "new-arity branch write after main DDL")
      }()

  // E10: expiration is ref-aware — branch heads survive, shared ancestry prunes.
  val interactBranchExpireProtectsRefs: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("interact.branch.expireProtectsRefs") { (spark, table) =>
      spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='true')")
      spark.sql(s"ALTER TABLE $table CREATE BRANCH eb")
      spark.sql(s"INSERT INTO $table.branch_eb VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
      spark.sql(s"INSERT INTO $table VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')")
      assert(spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0) == 4, "expected 4 snapshots pre-expire")
      spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
      val refs = spark.sql(s"SELECT name FROM $table.refs").collect().toSeq.map(_.getString(0)).toSet
      assert(refs == Set("main", "eb"), s"branch/tag refs must survive expiration: $refs")
      assert(spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0) == 2,
        "shared ancestry prunes to the two ref heads")
      assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF 'eb'").collect()(0).getLong(0) == 6, "branch readable post-expire")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 6, "main readable post-expire")
    }()

  // C4: restore procedures target MAIN even while spark.wap.branch is set (procedures are not
  // branch-conf-routed) — the branch is untouched.
  val interactBranchRollbackWhileWapConf: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("interact.branch.rollbackWhileWapConf") { (spark, table) =>
      val s0 = snapshotIds(spark, table).head
      spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='true')")
      spark.sql(s"ALTER TABLE $table CREATE BRANCH rb")
      spark.sql(s"INSERT INTO $table.branch_rb VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
      spark.conf.set("spark.wap.branch", "rb")
      try spark.sql(s"CALL openhouse.system.rollback_to_snapshot('${catalogRelative(table)}', $s0)")
      finally spark.conf.unset("spark.wap.branch")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3,
        "rollback under wap.branch conf still targets MAIN (procedures are not branch-routed)")
      assert(spark.sql(s"SELECT count(*) FROM $table VERSION AS OF 'rb'").collect()(0).getLong(0) == 6,
        "branch untouched by the main rollback")
    }()

  // C1: rolled-past snapshots are unreferenced — expiration makes the rollback permanent.
  val interactRestoreExpireAfterRollback: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("interact.restore.expireAfterRollback") { (spark, table) =>
      val snaps = snapshotIds(spark, table)
      spark.sql(s"CALL openhouse.system.rollback_to_snapshot('${catalogRelative(table)}', ${snaps.head})")
      spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
      assert(spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0) == 1,
        "the rolled-past snapshot must be expired (unreferenced)")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 3, "current state intact")
      val e = Check.intercept[Exception](
        spark.sql(s"SELECT count(*) FROM $table VERSION AS OF ${snaps(1)}").collect())
      assert(Exceptions.causeChain(e).exists(t => Option(t.getMessage).exists(_.toLowerCase.contains("snapshot"))),
        s"travel to the expired snapshot must fail (rollback is now PERMANENT): ${e.getMessage.take(200)}")
    }()

  // ── THE COMPOSITE DEFECT: branch × expiration × merge (G11; INTERACTION-AUDIT §6) ───────────
  // Bytecode-confirmed mechanism: RemoveSnapshots retention is per-ref and head-anchored (no
  // protection for the ancestry BETWEEN live refs), and SnapshotUtil's ancestry walk SILENTLY
  // TRUNCATES at an expired hole and returns false. So policy-driven expiration between branch
  // work and the merge makes fast_forward spuriously reject with "not an ancestor" — even when
  // main never advanced — and, with no rebase in Iceberg, the branch is permanently stranded.
  // The pair test (branch × expire) PASSES because reads don't consume ancestry; only the merge does.
  val interactExpireMergeSpuriousReject: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("interact.branch.expireMerge.spuriousReject") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE BRANCH mb")
        spark.sql(s"INSERT INTO $table.branch_mb VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')") // B1
        spark.sql(s"INSERT INTO $table.branch_mb VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')") // B2 (head)
        assert(countOf(spark, s"SELECT count(*) FROM $table.snapshots") == "3", "expected P, B1, B2")
        // main NEVER advances. This merge is valid right now (branch.fastForward.merge is the
        // no-expiration control proving it). Interpose the destroyer:
        spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
        // P2 VIOLATED: retention is per-ref head-anchored — the intermediate branch commit B1
        // (merge connectivity) is expired even though both refs are alive.
        assert(countOf(spark, s"SELECT count(*) FROM $table.snapshots") == "2",
          "retention keeps only the two ref heads; the intermediate branch snapshot is expired")
        // The pair-test ILLUSION: refs alive, branch fully readable — nothing looks broken.
        val refs = spark.sql(s"SELECT name FROM $table.refs").collect().toSeq.map(_.getString(0)).toSet
        assert(refs == Set("main", "mb"), s"both refs alive: $refs")
        assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'mb'") == "5", "branch readable")
        // P1 VIOLATED: the merge is now spuriously rejected — the ancestry walk from B2 hits the
        // B1 hole, silently truncates, and concludes main's head "is not an ancestor" of the branch.
        val e = Check.intercept[Exception](
          spark.sql(s"CALL openhouse.system.fast_forward('${catalogRelative(table)}', 'main', 'mb')"))
        assert(Option(e.getMessage).exists(_.contains("not an ancestor")),
          s"G11 appears FIXED — fast_forward survived expiration (or failed differently); update AUDIT-FINDINGS G11: " +
            s"${e.getClass.getName} ${Option(e.getMessage).getOrElse("").take(180)}")
        // P6 VIOLATED: no recovery path merges the branch. Characterize the cherry-pick fallback:
        val b2 = spark.sql(s"SELECT snapshot_id FROM $table.refs WHERE name = 'mb'").collect()(0).getLong(0)
        val cherry = try {
          spark.sql(s"CALL openhouse.system.cherrypick_snapshot('${catalogRelative(table)}', ${b2}L)")
          s"SUCCEEDED — main now ${countOf(spark, s"SELECT count(*) FROM $table")} rows (B1's commit silently LOST in the 'merge')"
        } catch { case t: Throwable => s"REJECTED ${t.getClass.getName} :: ${Option(t.getMessage).getOrElse("").take(160)}" }
        println(s"DIAG expireMerge.cherrypickFallback: $cherry")
        val mainCount = countOf(spark, s"SELECT count(*) FROM $table").toLong
        assert(mainCount == 3 || mainCount == 4, s"main must stay consistent (3, or 4 if cherry-pick half-merged): $mainCount")
        // Copy-out is the ONLY full recovery (data files survive: expiration ran cleanExpiredFiles(false)).
        assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'mb'") == "5",
          "branch data must remain readable for copy-out recovery")
      }()

  // P3 VIOLATED: WAP-staged snapshots are UNREFERENCED, so age-based expiration silently deletes
  // them before publish; the loss only becomes loud at publish time ("Cannot find snapshot").
  // OpenHouse's scheduled expiration job (default 3-day TTL) makes this automatic, not hypothetical.
  val interactExpireMergeStagedWapLoss: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("enableWap")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .step("interact.branch.expireMerge.stagedWapLoss") { (spark, table) =>
        spark.conf.set("spark.wap.id", "w2")
        try spark.sql(s"INSERT INTO $table VALUES (CAST(9 AS BIGINT), 9, 'row-9', 9.5, true, '2024-01-09-01')")
        finally spark.conf.unset("spark.wap.id")
        assert(countOf(spark, s"SELECT count(*) FROM $table.snapshots WHERE summary['wap.id'] = 'w2'") == "1", "staged")
        spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
        // The SILENT loss: expiration reports nothing about the staged work it destroyed.
        assert(countOf(spark, s"SELECT count(*) FROM $table.snapshots WHERE summary['wap.id'] = 'w2'") == "0",
          "P3 appears FIXED — staged WAP snapshot survived expiration; update AUDIT-FINDINGS G11")
        // Loud only NOW, at publish — after the work is unrecoverable:
        val e = Check.intercept[Exception](
          spark.sql(s"CALL openhouse.system.publish_changes(table => '${catalogRelative(table)}', wap_id => 'w2')"))
        println(s"DIAG stagedWapLoss.publish: ${e.getClass.getName} :: ${Option(e.getMessage).getOrElse("").take(180)}")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "main unchanged; the staged write is gone")
      }()

  // ── flags at CREATE + ALTER-to-MoR + compaction over evolved schema ────────────────────────
  val interactFlagsWapReplaceAtCreate: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES (" +
        s"'write.format.default'='parquet', 'write.wap.enabled'='true', 'replace.enabled'='true')")()
      .insert(3)()
      .step("interact.flags.wapReplaceAtCreate") { (spark, table) =>
        val p = tableProps(spark, table)
        assert(p.get("write.wap.enabled").contains("true") && p.get("replace.enabled").contains("true"),
          s"flags set at CREATE must be honored: wap=${p.get("write.wap.enabled")} replace=${p.get("replace.enabled")}")
        spark.sql(s"ALTER TABLE $table CREATE BRANCH cb") // wap-at-create usable immediately
        val e = Check.intercept[BadRequestException](
          spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table"))
        assert(e.getMessage.contains("while WAP"),
          s"RTAS-while-WAP guard must fire from create-time flags too: ${e.getMessage.take(200)}")
      }()

  val interactMorAlterToMor: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)()
      .sql("seed(3, one-file)")(t =>
        s"INSERT INTO $t SELECT /*+ COALESCE(1) */ * FROM (${RowGenerator.valuesClause(Core, 3)}) AS seed")()
      .step("interact.mor.alterToMor") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.delete.mode'='merge-on-read')")
        spark.sql(s"DELETE FROM $table WHERE ${Core.long0.columnName} = 1")
        val deleteFiles = spark.sql(s"SELECT count(*) FROM $table.all_delete_files").collect()(0).getLong(0)
        assert(deleteFiles == 1,
          s"ALTER-to-MoR must govern subsequent deletes (expected 1 position-delete file, got $deleteFiles)")
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2, "row not deleted")
      }()

  val interactMaintCompactEvolved: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("interact.maint.compactEvolved") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table ADD COLUMN extra_col INT")
        spark.sql(s"INSERT INTO $table VALUES $extraColInsert9")
        spark.sql(s"INSERT INTO $table VALUES $extraColInsert10")
        spark.sql(s"CALL openhouse.system.rewrite_data_files(table => '${catalogRelative(table)}')")
        assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 5, "compaction changed row count")
        assert(spark.sql(s"SELECT count(*) FROM $table WHERE extra_col IN (42, 43)").collect()(0).getLong(0) == 2,
          "compaction over mixed-schema files must preserve evolved-column values")
        assert(spark.sql(s"SELECT count(*) FROM $table WHERE extra_col IS NULL").collect()(0).getLong(0) == 3,
          "pre-evolution rows must stay null in the evolved column")
      }()

  val interactions: List[(String, TableTest[CoreTable.type])] = List(
    "interact.ddl.ttAfterAddColumn"       -> interactTtAfterAddColumn,
    "interact.ddl.restoreAfterAddColumn"  -> interactRestoreAfterAddColumn,
    "interact.ddl.dropColAfterData"       -> interactDropColAfterData,
    "interact.rtas.historyPreserved"      -> interactRtasHistoryPreserved,
    "interact.rtas.restoreRejected"       -> interactRtasRestoreRejected,
    "interact.rtas.setCurrentRecovery"    -> interactRtasSetCurrentRecovery,
    "interact.rtas.writeAfter"            -> interactRtasWriteAfter,
    "interact.rtas.partitionSpecChange"   -> interactRtasPartitionSpecChange,
    "interact.rtas.dropsColumn"           -> interactRtasDropsColumn,
    "interact.rtas.props.userSurvival"    -> interactRtasPropsUserSurvival,
    "interact.rtas.props.statementWins"   -> interactRtasPropsStatementWins,
    "interact.rtas.props.createDefaulting" -> interactRtasPropsCreateDefaulting,
    "interact.rtas.props.reservedPlane"   -> interactRtasPropsReservedPlane,
    "interact.rtas.withBranch"            -> interactRtasWithBranch,
    "interact.branch.ttBeforeBranchPoint" -> interactBranchTtBeforeBranchPoint,
    "interact.branch.mainDdlImmediate"    -> interactBranchMainDdlImmediate,
    "interact.branch.expireProtectsRefs"  -> interactBranchExpireProtectsRefs,
    "interact.branch.rollbackWhileWapConf" -> interactBranchRollbackWhileWapConf,
    "interact.restore.expireAfterRollback" -> interactRestoreExpireAfterRollback,
    "interact.branch.expireMerge.spuriousReject" -> interactExpireMergeSpuriousReject,
    "interact.branch.expireMerge.stagedWapLoss"  -> interactExpireMergeStagedWapLoss,
    "interact.flags.wapReplaceAtCreate"   -> interactFlagsWapReplaceAtCreate,
    "interact.mor.alterToMor"             -> interactMorAlterToMor,
    "interact.maint.compactEvolved"       -> interactMaintCompactEvolved
  )

  // G2 characterization needs the REST lock (no SQL surface) → Ctx-based like controlPlane.
  // Sanity-checks the lock DOES block a normal write, then demonstrates RTAS sails through it.
  def interactRtasOnLockedTable(ctx: Ctx): Unit = {
    val spark = ctx.spark
    val table = s"${ctx.namespace}.t_lockrtas"
    val Array(db, tbl) = table.stripPrefix("openhouse.").split("\\.", 2)
    spark.sql(s"DROP TABLE IF EXISTS $table")
    spark.sql(coreCreateParquet(table))
    spark.sql(s"INSERT INTO $table ${RowGenerator.valuesClause(Core, 3)}")
    spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('replace.enabled'='true')")
    try {
      val (lockStatus, lockBody) = Rest.post(ctx, s"/v1/databases/$db/tables/$tbl/lock", """{"locked":true}""")
      assert(lockStatus >= 200 && lockStatus < 300, s"lock POST failed: $lockStatus $lockBody")
      val blocked = Check.intercept[Exception](spark.sql(
        s"UPDATE $table SET ${Core.string0.columnName} = 'x' WHERE ${Core.long0.columnName} = 1"))
      assert(Exceptions.causeChain(blocked).exists(t => Option(t.getMessage).exists(_.toLowerCase.contains("locked"))),
        s"lock not enforced on UPDATE: ${blocked.getMessage.take(160)}")
      // G2: the replace branches never reach the isTableLocked check — RTAS replaces a LOCKED table.
      spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
      assert(spark.sql(s"SELECT count(*) FROM $table").collect()(0).getLong(0) == 2,
        "G2 characterization: RTAS bypassed the lock (if a locked-table rejection landed here, G2 is FIXED — update AUDIT-FINDINGS)")
    } finally {
      Rest.delete(ctx, s"/v1/databases/$db/tables/$tbl/lock")
      spark.sql(s"DROP TABLE IF EXISTS $table")
    }
  }

  val interactionCtxOps: List[(String, Ctx => Unit)] = List(
    "interact.rtas.onLockedTable" -> interactRtasOnLockedTable
  )

  // ═══ Surface-completion axis: queued follow-ups + untested Iceberg surface ═══════════════════

  private def countOf(spark: SparkSession, sql: String): String =
    spark.sql(sql).collect()(0).getLong(0).toString

  // Audit-B regression guard: a rejection message shown to a SQL user must not be a raw stacktrace,
  // an [INTERNAL_ERROR], or a bare NPE. (It may still be MEH — jargony — that's tracked separately.)
  private def assertReadableMessage(context: String)(e: Throwable): Unit = {
    val m = Option(e.getMessage).getOrElse("")
    assert(m.nonEmpty, s"$context: empty error message (worst possible readability)")
    assert(!m.contains("[INTERNAL_ERROR]"), s"$context: internal error surfaced to the user: ${m.take(160)}")
    assert(!m.contains("\n\tat ") && !m.contains("\tat java."), s"$context: stacktrace frames in the user-facing message: ${m.take(160)}")
    assert(!m.startsWith("java.lang.NullPointerException"), s"$context: bare NPE surfaced: ${m.take(160)}")
  }

  val surfaceMsgReadabilityGuard: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.msg.readabilityGuard") { (spark, table) =>
        assertReadableMessage("dropColumn")(
          Check.intercept[Exception](spark.sql(s"ALTER TABLE $table DROP COLUMN ${Core.int0.columnName}")))
        assertReadableMessage("reservedProp")(
          Check.intercept[Exception](spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('openhouse.tableUUID'='x')")))
        assertReadableMessage("rtasDisabled")(
          Check.intercept[Exception](spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table")))
        assertReadableMessage("createNamespace")(
          Check.intercept[Exception](spark.sql("CREATE NAMESPACE openhouse.nope_ns")))
      }()

  // ── G8 legs: the other main-affecting DDLs leak from a branch to main ────────────────────────
  val surfaceBranchLeakSetProps: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("branch.leak.setProps") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='true')")
        spark.sql(s"ALTER TABLE $table CREATE BRANCH lb2")
        spark.conf.set("spark.wap.branch", "lb2")
        try spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('user.leaked'='yes')")
        finally spark.conf.unset("spark.wap.branch")
        assert(tableProps(spark, table).get("user.leaked").contains("yes"),
          "G8 appears FIXED for SET TBLPROPERTIES — props no longer leak from branch to main; update AUDIT-FINDINGS G8")
      }()

  val surfaceBranchLeakWriteOrdered: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("branch.leak.writeOrderedBy") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='true')")
        spark.sql(s"ALTER TABLE $table CREATE BRANCH lb3")
        spark.conf.set("spark.wap.branch", "lb3")
        try spark.sql(s"ALTER TABLE $table WRITE ORDERED BY ${Core.long0.columnName}")
        finally spark.conf.unset("spark.wap.branch")
        assert(tableProps(spark, table).get("write.distribution-mode").contains("range"),
          "G8 appears FIXED for WRITE ORDERED BY — sort order no longer leaks from branch to main; update AUDIT-FINDINGS G8")
      }()

  // ── G4 pin: toggling WAP off while staged snapshots exist is NOT guarded ─────────────────────
  val surfaceWapToggleNoGuard: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("enableWap")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .step("branch.wapToggle.noGuard") { (spark, table) =>
        spark.conf.set("spark.wap.id", "w9")
        try spark.sql(s"INSERT INTO $table VALUES (CAST(9 AS BIGINT), 9, 'row-9', 9.5, true, '2024-01-09-01')")
        finally spark.conf.unset("spark.wap.id")
        val staged = countOf(spark, s"SELECT count(*) FROM $table.snapshots WHERE summary['wap.id'] = 'w9'")
        assert(staged == "1", s"staging failed: $staged staged snapshots")
        // G4 pin: the toggle is ACCEPTED with a staged snapshot outstanding (no guard exists).
        spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='false')")
        val stagedAfter = countOf(spark, s"SELECT count(*) FROM $table.snapshots WHERE summary['wap.id'] = 'w9'")
        println(s"DIAG wapToggle: stagedAfterToggle=$stagedAfter")
      }()

  // ── WAP negatives (B2 follow-ups) ────────────────────────────────────────────────────────────
  val surfaceWapDoubleCherrypick: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("enableWap")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .step("wap.neg.doubleCherrypick") { (spark, table) =>
        spark.conf.set("spark.wap.id", "w1")
        try spark.sql(s"INSERT INTO $table VALUES (CAST(9 AS BIGINT), 9, 'row-9', 9.5, true, '2024-01-09-01')")
        finally spark.conf.unset("spark.wap.id")
        val sid = spark.sql(s"SELECT snapshot_id FROM $table.snapshots WHERE summary['wap.id'] = 'w1'").collect()(0).getLong(0)
        spark.sql(s"CALL openhouse.system.cherrypick_snapshot('${catalogRelative(table)}', ${sid}L)")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "4", "first publish failed")
        val e = Check.intercept[Exception](
          spark.sql(s"CALL openhouse.system.cherrypick_snapshot('${catalogRelative(table)}', ${sid}L)"))
        println(s"DIAG doubleCherrypick: ${e.getClass.getName} :: ${Option(e.getMessage).getOrElse("").take(180)}")
        assert(Option(e.getMessage).exists(m => m.toLowerCase.contains("duplicate") || m.toLowerCase.contains("already")),
          s"double cherry-pick should be rejected as a duplicate WAP commit: ${e.getMessage.take(180)}")
      }()

  val surfaceWapExpireRefTarget: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("wap.neg.expireRefTarget") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE BRANCH eb2")
        val headId = spark.sql(s"SELECT snapshot_id FROM $table.refs WHERE name = 'eb2'").collect()(0).getLong(0)
        val e = Check.intercept[Exception](spark.sql(
          s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', snapshot_ids => ARRAY(${headId}L))"))
        println(s"DIAG expireRefTarget: ${e.getClass.getName} :: ${Option(e.getMessage).getOrElse("").take(180)}")
      }()

  // ── Branch lifecycle tail: fast_forward IS the merge; replace branch ────────────────────────
  val surfaceBranchFastForwardMerge: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("branch.fastForward.merge") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE BRANCH fb")
        spark.sql(s"INSERT INTO $table.branch_fb VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
        spark.sql(s"INSERT INTO $table.branch_fb VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "main advanced unexpectedly")
        spark.sql(s"CALL openhouse.system.fast_forward('${catalogRelative(table)}', 'main', 'fb')")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "5",
          "fast_forward must merge the branch into main (main == branch head)")
      }()

  val surfaceBranchFastForwardDivergent: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("branch.fastForward.divergent") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE BRANCH db")
        spark.sql(s"INSERT INTO $table.branch_db VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
        spark.sql(s"INSERT INTO $table VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')") // diverge main
        val e = Check.intercept[Exception](
          spark.sql(s"CALL openhouse.system.fast_forward('${catalogRelative(table)}', 'main', 'db')"))
        println(s"DIAG ffDivergent: ${e.getClass.getName} :: ${Option(e.getMessage).getOrElse("").take(180)}")
        assert(Option(e.getMessage).exists(m => m.toLowerCase.contains("ancestor") || m.toLowerCase.contains("fast-forward")),
          s"divergent fast_forward should be rejected with an ancestry error: ${e.getMessage.take(180)}")
      }()

  val surfaceBranchReplaceBranch: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("branch.replaceBranch") { (spark, table) =>
      val snaps = snapshotIds(spark, table)
      spark.sql(s"ALTER TABLE $table CREATE BRANCH rb2")
      assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'rb2'") == "5", "branch at head")
      spark.sql(s"ALTER TABLE $table REPLACE BRANCH rb2 AS OF VERSION ${snaps.head}")
      assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'rb2'") == "3",
        "REPLACE BRANCH must retarget the ref to the older snapshot")
    }()

  // ── Streaming (structured streaming read + write) ────────────────────────────────────────────
  val surfaceStreamRead: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.stream.read") { (spark, table) =>
        val ckpt = java.nio.file.Files.createTempDirectory("ck-read").toString
        val sink = s"memsink_${System.nanoTime}"
        val q = spark.readStream.table(table)
          .writeStream.format("memory").queryName(sink)
          .trigger(org.apache.spark.sql.streaming.Trigger.AvailableNow())
          .option("checkpointLocation", ckpt)
          .start()
        assert(q.awaitTermination(120000), "streaming read did not finish in 120s")
        assert(countOf(spark, s"SELECT count(*) FROM $sink") == "3",
          "streaming read must deliver the seeded rows")
      }()

  val surfaceStreamWrite: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.stream.write") { (spark, table) =>
        import spark.implicits._
        implicit val sqlc: org.apache.spark.sql.SQLContext = spark.sqlContext
        val ms = org.apache.spark.sql.execution.streaming.MemoryStream[Long]
        ms.addData(100L, 101L)
        val df = ms.toDF().selectExpr(
          s"value AS ${Core.long0.columnName}",
          s"CAST(value AS INT) AS ${Core.int0.columnName}",
          s"concat('row-', value) AS ${Core.string0.columnName}",
          s"CAST(value AS DOUBLE) AS ${Core.double0.columnName}",
          s"true AS ${Core.boolean0.columnName}",
          s"'2024-01-01-00' AS ${Core.datePartition.columnName}")
        val ckpt = java.nio.file.Files.createTempDirectory("ck-write").toString
        val q = df.writeStream.format("iceberg").outputMode("append")
          .option("checkpointLocation", ckpt)
          .toTable(table)
        q.processAllAvailable()
        q.stop()
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "5",
          "streaming write must append the 2 streamed rows")
      }()

  // ── CDC: changelog view procedure ─────────────────────────────────────────────────────────────
  val surfaceCdcChangelogView: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("surface.cdc.changelogView") { (spark, table) =>
      val viewName = spark.sql(
        s"CALL openhouse.system.create_changelog_view(table => '${catalogRelative(table)}')").collect()(0).getString(0)
      val changes = spark.sql(s"SELECT count(*) FROM $viewName").collect()(0).getLong(0)
      assert(changes == 5, s"changelog must contain one INSERT change per seeded row: $changes")
      val types = spark.sql(s"SELECT DISTINCT _change_type FROM $viewName").collect().toSeq.map(_.getString(0)).toSet
      assert(types == Set("INSERT"), s"append-only history must yield INSERT changes only: $types")
    }()

  // ── Procedures not yet exercised ─────────────────────────────────────────────────────────────
  val surfaceProcRewriteManifests: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("surface.proc.rewriteManifests") { (spark, table) =>
      spark.sql(s"CALL openhouse.system.rewrite_manifests(table => '${catalogRelative(table)}', use_caching => false)")
      assert(countOf(spark, s"SELECT count(*) FROM $table") == "5", "rewrite_manifests changed data")
    }()

  val surfaceProcRewritePositionDeletes: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES (" +
        s"'write.format.default'='parquet', 'write.delete.mode'='merge-on-read')")()
      .sql("seed(3, one-file)")(t =>
        s"INSERT INTO $t SELECT /*+ COALESCE(1) */ * FROM (${RowGenerator.valuesClause(Core, 3)}) AS seed")()
      .step("surface.proc.rewritePositionDeletes") { (spark, table) =>
        spark.sql(s"DELETE FROM $table WHERE ${Core.long0.columnName} = 1")
        assert(countOf(spark, s"SELECT count(*) FROM $table.all_delete_files") == "1", "MoR delete file missing")
        spark.sql(s"CALL openhouse.system.rewrite_position_delete_files(table => '${catalogRelative(table)}', options => map('rewrite-all', 'true'))")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "2", "rewrite_position_delete_files changed data")
      }()

  val surfaceProcPublishChanges: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("enableWap")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .step("surface.proc.publishChanges") { (spark, table) =>
        spark.conf.set("spark.wap.id", "pw1")
        try spark.sql(s"INSERT INTO $table VALUES (CAST(9 AS BIGINT), 9, 'row-9', 9.5, true, '2024-01-09-01')")
        finally spark.conf.unset("spark.wap.id")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "staged write must not be visible")
        spark.sql(s"CALL openhouse.system.publish_changes(table => '${catalogRelative(table)}', wap_id => 'pw1')")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "4",
          "publish_changes (the wap_id publish path beside cherrypick) must publish the staged write")
      }()

  val surfaceProcAncestorsOf: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("surface.proc.ancestorsOf") { (spark, table) =>
      val n = spark.sql(s"CALL openhouse.system.ancestors_of(table => '${catalogRelative(table)}')").collect().length
      assert(n == 2, s"ancestors_of must list main's full ancestry (2 snapshots): $n")
    }()

  val surfaceProcRemoveOrphanReal: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.proc.removeOrphanReal") { (spark, table) =>
        val dataFile = spark.sql(s"SELECT file_path FROM $table.files LIMIT 1").collect()(0).getString(0).stripPrefix("file:")
        val orphan = java.nio.file.Paths.get(dataFile).getParent.resolve("zz_orphan_plant.parquet")
        java.nio.file.Files.write(orphan, "not-a-real-parquet".getBytes)
        java.nio.file.Files.setLastModifiedTime(orphan,
          java.nio.file.attribute.FileTime.fromMillis(1546300800000L)) // 2019-01-01
        spark.sql(s"CALL openhouse.system.remove_orphan_files(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2020-01-01 00:00:00')")
        assert(java.nio.file.Files.notExists(orphan), "planted orphan file must be removed")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "live data must survive orphan removal")
      }()

  // ── Metadata surface: hidden columns + full metadata-table sweep ─────────────────────────────
  val surfaceMetaHiddenColumns: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.meta.hiddenColumns") { (spark, table) =>
        val rows = spark.sql(s"SELECT _file, _pos, _spec_id, _partition FROM $table").collect().toSeq
        assert(rows.size == 3, s"hidden metadata columns must be selectable per row: ${rows.size}")
        assert(rows.forall(r => r.getString(0) != null && r.getString(0).nonEmpty), "_file must be populated")
        assert(rows.forall(r => r.getLong(1) >= 0), "_pos must be populated")
      }()

  val surfaceMetaTableSweep: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("surface.meta.tableSweep") { (spark, table) =>
      val metaTables = Seq("entries", "files", "manifests", "snapshots", "history", "refs", "partitions",
        "metadata_log_entries", "data_files", "all_data_files", "all_manifests", "all_entries", "all_files")
      metaTables.foreach { m =>
        val n = spark.sql(s"SELECT count(*) FROM $table.`$m`").collect()(0).getLong(0)
        assert(n >= 0, s"metadata table $m unreadable") // queryability is the assertion; count is a bonus
      }
      assert(countOf(spark, s"SELECT count(*) FROM $table.snapshots") == "2", "snapshots count sanity")
    }()

  val surfaceMetaPositionDeletes: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES (" +
        s"'write.format.default'='parquet', 'write.delete.mode'='merge-on-read')")()
      .sql("seed(3, one-file)")(t =>
        s"INSERT INTO $t SELECT /*+ COALESCE(1) */ * FROM (${RowGenerator.valuesClause(Core, 3)}) AS seed")()
      .step("surface.meta.positionDeletes") { (spark, table) =>
        spark.sql(s"DELETE FROM $table WHERE ${Core.long0.columnName} = 1")
        assert(countOf(spark, s"SELECT count(*) FROM $table.position_deletes") == "1",
          "position_deletes metadata table must expose the position delete")
      }()

  // ── Concurrency: invariant-based (no torn state; failures must be typed) ─────────────────────
  private def runConcurrently(fs: Seq[() => Unit]): Seq[Throwable] = {
    val errors = new java.util.concurrent.ConcurrentLinkedQueue[Throwable]()
    val threads = fs.map(f => new Thread(() => try f() catch { case t: Throwable => errors.add(t) }))
    threads.foreach(_.start())
    threads.foreach(_.join(180000))
    errors.toArray(Array.empty[Throwable]).toSeq
  }

  private def isTypedCommitConflict(t: Throwable): Boolean =
    Exceptions.causeChain(t).exists { c =>
      val n = c.getClass.getName
      n.contains("CommitFailed") || n.contains("CommitStateUnknown") || n.contains("Validation") ||
        n.contains("BadRequest") || n.contains("WebClientResponse")
    }

  val surfaceConcAppendAppend: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.conc.appendAppend") { (spark, table) =>
        val failures = new java.util.concurrent.atomic.AtomicInteger(0)
        def writer(base: Int): () => Unit = () => (0 until 3).foreach { i =>
          try spark.sql(s"INSERT INTO $table VALUES (CAST(${base + i} AS BIGINT), ${base + i}, 'row-c', 1.5, true, '2024-01-09-01')")
          catch { case t: Throwable =>
            assert(isTypedCommitConflict(t), s"concurrent append failed with an UNTYPED error: ${t.getClass.getName} ${Option(t.getMessage).getOrElse("").take(160)}")
            failures.incrementAndGet()
          }
        }
        val errs = runConcurrently(Seq(writer(100), writer(200)))
        assert(errs.isEmpty, s"writer thread died outside the insert loop: ${errs.headOption.map(_.toString)}")
        val expected = 3 + 6 - failures.get
        assert(countOf(spark, s"SELECT count(*) FROM $table") == expected.toString,
          s"row count must equal successful appends (3 seed + ${6 - failures.get} landed)")
        println(s"DIAG conc.appendAppend: ${failures.get}/6 inserts hit a typed commit conflict")
      }()

  val surfaceConcUpdateUpdate: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.conc.updateUpdate") { (spark, table) =>
        val col = Core.string0.columnName
        def updater(v: String): () => Unit = () =>
          try spark.sql(s"UPDATE $table SET $col = '$v' WHERE ${Core.long0.columnName} = 2")
          catch { case t: Throwable =>
            assert(isTypedCommitConflict(t), s"concurrent update failed with an UNTYPED error: ${t.getClass.getName} ${Option(t.getMessage).getOrElse("").take(160)}") }
        val errs = runConcurrently(Seq(updater("AAA"), updater("BBB")))
        assert(errs.isEmpty, s"updater thread died with a non-conflict error: ${errs.headOption.map(_.toString)}")
        val v = spark.sql(s"SELECT $col FROM $table WHERE ${Core.long0.columnName} = 2").collect()(0).getString(0)
        assert(v == "AAA" || v == "BBB" || v == "row-2", s"row must hold one writer's value or the original, not torn state: $v")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "row count must be unchanged")
      }()

  val surfaceConcRtasVsAppend: TableTest[CoreTable.type] =
    rtasPrep.step("surface.conc.rtasVsAppend") { (spark, table) =>
      def rtas(): Unit =
        try spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
        catch { case t: Throwable => assert(isTypedCommitConflict(t), s"RTAS race failed UNTYPED: ${t.getClass.getName}") }
      def append(): Unit =
        try spark.sql(s"INSERT INTO $table VALUES (CAST(30 AS BIGINT), 30, 'row-30', 30.5, true, '2024-01-09-01')")
        catch { case t: Throwable => assert(isTypedCommitConflict(t), s"append race failed UNTYPED: ${t.getClass.getName}") }
      val errs = runConcurrently(Seq(() => rtas(), () => append()))
      assert(errs.isEmpty, s"racing thread died with a non-conflict error: ${errs.headOption.map(_.toString)}")
      spark.sql(s"REFRESH TABLE $table")
      val n = countOf(spark, s"SELECT count(*) FROM $table").toLong
      assert(n == 2 || n == 3, s"RTAS-vs-append must settle to a consistent state (2 or 3 rows), got $n")
      println(s"DIAG conc.rtasVsAppend: settled at $n rows")
    }()

  // ── Schema-evolution edges ───────────────────────────────────────────────────────────────────
  val surfaceSchemaRelaxNotNull: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.schema.relaxNotNull") { (spark, table) =>
        val side = s"${table}_nn"
        spark.sql(s"DROP TABLE IF EXISTS $side")
        try {
          spark.sql(s"CREATE TABLE $side (id BIGINT, req INT NOT NULL) USING iceberg")
          spark.sql(s"ALTER TABLE $side ALTER COLUMN req DROP NOT NULL")
          spark.sql(s"INSERT INTO $side VALUES (CAST(1 AS BIGINT), NULL)")
          assert(spark.sql(s"SELECT count(*) FROM $side WHERE req IS NULL").collect()(0).getLong(0) == 1,
            "relaxing NOT NULL must allow null writes (the inverse of the pinned-rejected tighten)")
        } finally spark.sql(s"DROP TABLE IF EXISTS $side")
      }()

  val surfaceSchemaDecimalWiden: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.schema.decimalWiden") { (spark, table) =>
        val side = s"${table}_dec"
        spark.sql(s"DROP TABLE IF EXISTS $side")
        try {
          spark.sql(s"CREATE TABLE $side (id BIGINT, dec DECIMAL(10,2)) USING iceberg")
          spark.sql(s"INSERT INTO $side VALUES (CAST(1 AS BIGINT), CAST(12345678.99 AS DECIMAL(10,2)))")
          spark.sql(s"ALTER TABLE $side ALTER COLUMN dec TYPE DECIMAL(12,2)")
          spark.sql(s"INSERT INTO $side VALUES (CAST(2 AS BIGINT), CAST(1234567890.99 AS DECIMAL(12,2)))")
          assert(spark.sql(s"SELECT count(*) FROM $side").collect()(0).getLong(0) == 2,
            "decimal precision widen must keep old data readable and accept wider values")
        } finally spark.sql(s"DROP TABLE IF EXISTS $side")
      }()

  val surfaceSchemaNestedAddField: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.schema.nestedAddField") { (spark, table) =>
        val side = s"${table}_nst"
        spark.sql(s"DROP TABLE IF EXISTS $side")
        try {
          spark.sql(s"CREATE TABLE $side (id BIGINT, s STRUCT<x: INT, y: STRING>) USING iceberg")
          spark.sql(s"INSERT INTO $side VALUES (CAST(1 AS BIGINT), named_struct('x', 1, 'y', 'a'))")
          spark.sql(s"ALTER TABLE $side ADD COLUMN s.w INT")
          assert(spark.sql(s"SELECT count(*) FROM $side WHERE s.w IS NULL").collect()(0).getLong(0) == 1,
            "adding a nested struct field must null-fill existing rows")
          spark.sql(s"INSERT INTO $side VALUES (CAST(2 AS BIGINT), named_struct('x', 2, 'y', 'b', 'w', 9))")
          assert(spark.sql(s"SELECT count(*) FROM $side WHERE s.w = 9").collect()(0).getLong(0) == 1,
            "the new nested field must be writable")
        } finally spark.sql(s"DROP TABLE IF EXISTS $side")
      }()

  val surfaceSchemaNestedDropField: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.schema.nestedDropField") { (spark, table) =>
        val side = s"${table}_nsd"
        spark.sql(s"DROP TABLE IF EXISTS $side")
        try {
          spark.sql(s"CREATE TABLE $side (id BIGINT, s STRUCT<x: INT, y: STRING>) USING iceberg")
          spark.sql(s"INSERT INTO $side VALUES (CAST(1 AS BIGINT), named_struct('x', 1, 'y', 'a'))")
          val e = Check.intercept[Exception](spark.sql(s"ALTER TABLE $side DROP COLUMN s.x"))
          println(s"DIAG nestedDropField: ${e.getClass.getName} :: ${Option(e.getMessage).getOrElse("").take(180)}")
          assert(spark.sql(s"SELECT s.x FROM $side").collect()(0).getInt(0) == 1,
            "rejected nested drop must leave the field readable")
        } finally spark.sql(s"DROP TABLE IF EXISTS $side")
      }()

  val surfaceSchemaReorderExisting: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.schema.reorderExisting") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table ALTER COLUMN ${Core.string0.columnName} FIRST")
        val cols = spark.sql(s"SELECT * FROM $table LIMIT 1").columns.toSeq
        assert(cols.head == Core.string0.columnName, s"column reorder (FIRST) must change projection order: $cols")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "reorder must not affect data")
      }()

  // ── Write-path configs ───────────────────────────────────────────────────────────────────────
  val surfaceWriteDistributionHash: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg PARTITIONED BY (${Core.datePartition.columnName}) " +
        s"TBLPROPERTIES ('write.format.default'='parquet', 'write.distribution-mode'='hash')")()
      .insert(3)()
      .check("surface.write.distributionHash") { view =>
        assert(tableProps(view.spark, view.table).get("write.distribution-mode").contains("hash"), "hash mode not honored")
        assert(view.after.size == 3, "hash-distributed write failed")
      }

  val surfaceWriteTargetFileSize: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES (" +
        s"'write.format.default'='parquet', 'write.target-file-size-bytes'='1048576')")()
      .insert(3)()
      .check("surface.write.targetFileSize") { view =>
        assert(tableProps(view.spark, view.table).get("write.target-file-size-bytes").contains("1048576"), "target size not honored")
        assert(view.after.size == 3, "write under custom target file size failed")
      }

  val surfaceWriteDfToBranch: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.write.dfToBranch") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE BRANCH wb")
        val df = spark.sql(s"SELECT CAST(50 AS BIGINT) AS ${Core.long0.columnName}, 50 AS ${Core.int0.columnName}, " +
          s"'row-50' AS ${Core.string0.columnName}, 50.5 AS ${Core.double0.columnName}, " +
          s"true AS ${Core.boolean0.columnName}, '2024-01-09-01' AS ${Core.datePartition.columnName}")
        df.writeTo(s"$table.branch_wb").append()
        assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'wb'") == "4",
          "DataFrame-API write must land on the branch")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "main must be untouched by the branch DF write")
      }()

  // ── Pins: import/migration procedures, views, ANALYZE (expected-unsupported tripwires) ───────
  // The bogus-input probes showed these procedures fail on INPUT (NotFound/NoSuchTable), not on an
  // OpenHouse catalog block — so settle register_table with a REAL metadata file: is importing a
  // table into the managed catalog (bypassing normal creation) actually possible?
  val surfacePinImportProcs: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.pin.importProcs") { (spark, table) =>
        val metadataFile = spark.sql(
          s"SELECT file FROM $table.metadata_log_entries ORDER BY timestamp DESC LIMIT 1").collect()(0).getString(0)
        val regOutcome =
          try {
            spark.sql(s"CALL openhouse.system.register_table(table => 'dbMatrix.zz_reg', metadata_file => '$metadataFile')")
            val n = countOf(spark, "SELECT count(*) FROM openhouse.dbMatrix.zz_reg")
            spark.sql("DROP TABLE IF EXISTS openhouse.dbMatrix.zz_reg")
            s"REGISTERED (readable, $n rows) — import into the managed catalog is NOT blocked"
          } catch { case t: Throwable =>
            s"REJECTED ${t.getClass.getName} :: ${Option(t.getMessage).getOrElse("").take(160)}" }
        println(s"DIAG pin.register_table(real): $regOutcome")
        val snap = Check.intercept[Exception](spark.sql(
          s"CALL openhouse.system.snapshot(source_table => '${catalogRelative(table)}', table => 'dbMatrix.zz_snap')"))
        println(s"DIAG pin.snapshot: ${snap.getClass.getName} :: ${Option(snap.getMessage).getOrElse("").take(160)}")
        val add = Check.intercept[Exception](spark.sql(
          s"CALL openhouse.system.add_files(table => '${catalogRelative(table)}', source_table => '`parquet`.`/tmp/zz_nope_dir`')"))
        println(s"DIAG pin.add_files: ${add.getClass.getName} :: ${Option(add.getMessage).getOrElse("").take(160)}")
      }()

  val surfacePinViewsAnalyze: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("surface.pin.viewsAnalyze") { (spark, table) =>
        val view = Check.intercept[Exception](spark.sql(s"CREATE VIEW openhouse.dbMatrix.zz_v1 AS SELECT 1 AS one"))
        println(s"DIAG pin.createView: ${view.getClass.getName} :: ${Option(view.getMessage).getOrElse("").take(160)}")
        val analyze = Check.intercept[Exception](spark.sql(s"ANALYZE TABLE $table COMPUTE STATISTICS"))
        println(s"DIAG pin.analyze: ${analyze.getClass.getName} :: ${Option(analyze.getMessage).getOrElse("").take(160)}")
      }()

  // Compaction × branch: does rewrite_data_files touch/break branch state, and where does it land
  // when spark.wap.branch is set? (Untested cell flagged in the surface appraisal.)
  val surfaceMaintCompactWithBranch: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("surface.maint.compactWithBranch") { (spark, table) =>
      spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='true')")
      spark.sql(s"ALTER TABLE $table CREATE BRANCH cb")
      spark.sql(s"INSERT INTO $table.branch_cb VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
      spark.sql(s"INSERT INTO $table VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')")
      val r = spark.sql(s"CALL openhouse.system.rewrite_data_files(table => '${catalogRelative(table)}', options => map('min-input-files', '2'))").collect()(0)
      println(s"DIAG compactWithBranch: mainCompaction rewritten=${r.get(0)} added=${r.get(1)}")
      assert(countOf(spark, s"SELECT count(*) FROM $table") == "6", "main data preserved by compaction")
      assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'cb'") == "6",
        "branch data preserved and readable after main compaction")
      spark.conf.set("spark.wap.branch", "cb")
      val confOutcome = try {
        val rc = spark.sql(s"CALL openhouse.system.rewrite_data_files(table => '${catalogRelative(table)}')").collect()(0)
        s"RAN (rewritten=${rc.get(0)}, added=${rc.get(1)})"
      } catch { case t: Throwable => s"THREW ${t.getClass.getSimpleName} :: ${Option(t.getMessage).getOrElse("").take(140)}" }
      finally spark.conf.unset("spark.wap.branch")
      println(s"DIAG compactUnderWapConf: $confOutcome")
      spark.sql(s"REFRESH TABLE $table")
      assert(countOf(spark, s"SELECT count(*) FROM $table") == "6", "main intact after conf-routed compaction attempt")
      assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'cb'") == "6", "branch intact after conf-routed compaction attempt")
    }()

  val surfaceOps: List[(String, TableTest[CoreTable.type])] = List(
    "surface.maint.compactWithBranch"     -> surfaceMaintCompactWithBranch,
    "surface.msg.readabilityGuard"        -> surfaceMsgReadabilityGuard,
    "branch.leak.setProps"                -> surfaceBranchLeakSetProps,
    "branch.leak.writeOrderedBy"          -> surfaceBranchLeakWriteOrdered,
    "branch.wapToggle.noGuard"            -> surfaceWapToggleNoGuard,
    "wap.neg.doubleCherrypick"            -> surfaceWapDoubleCherrypick,
    "wap.neg.expireRefTarget"             -> surfaceWapExpireRefTarget,
    "branch.fastForward.merge"            -> surfaceBranchFastForwardMerge,
    "branch.fastForward.divergent"        -> surfaceBranchFastForwardDivergent,
    "branch.replaceBranch"                -> surfaceBranchReplaceBranch,
    "surface.stream.read"                 -> surfaceStreamRead,
    "surface.stream.write"                -> surfaceStreamWrite,
    "surface.cdc.changelogView"           -> surfaceCdcChangelogView,
    "surface.proc.rewriteManifests"       -> surfaceProcRewriteManifests,
    "surface.proc.rewritePositionDeletes" -> surfaceProcRewritePositionDeletes,
    "surface.proc.publishChanges"         -> surfaceProcPublishChanges,
    "surface.proc.ancestorsOf"            -> surfaceProcAncestorsOf,
    "surface.proc.removeOrphanReal"       -> surfaceProcRemoveOrphanReal,
    "surface.meta.hiddenColumns"          -> surfaceMetaHiddenColumns,
    "surface.meta.tableSweep"             -> surfaceMetaTableSweep,
    "surface.meta.positionDeletes"        -> surfaceMetaPositionDeletes,
    "surface.conc.appendAppend"           -> surfaceConcAppendAppend,
    "surface.conc.updateUpdate"           -> surfaceConcUpdateUpdate,
    "surface.conc.rtasVsAppend"           -> surfaceConcRtasVsAppend,
    "surface.schema.relaxNotNull"         -> surfaceSchemaRelaxNotNull,
    "surface.schema.decimalWiden"         -> surfaceSchemaDecimalWiden,
    "surface.schema.nestedAddField"       -> surfaceSchemaNestedAddField,
    "surface.schema.nestedDropField"      -> surfaceSchemaNestedDropField,
    "surface.schema.reorderExisting"      -> surfaceSchemaReorderExisting,
    "surface.write.distributionHash"      -> surfaceWriteDistributionHash,
    "surface.write.targetFileSize"        -> surfaceWriteTargetFileSize,
    "surface.write.dfToBranch"            -> surfaceWriteDfToBranch,
    "surface.pin.importProcs"             -> surfacePinImportProcs,
    "surface.pin.viewsAnalyze"            -> surfacePinViewsAnalyze
  )

  // ═══ Hazard demonstrations H1-H8 (MODALITY-RECON.md; gates cleared per FEATURE-ANALYSIS-PLAN) ══
  // Each was PREDICTED by the state-flow model, verified in code/bytecode, and is demonstrated
  // live here. Characterizations flip loudly if the product fixes the hazard.

  // H1 — streaming checkpoint × expiration (G11's streaming twin). Three acts:
  // (1) stream + checkpoint; (2) CONTROL: plain restart picks up new rows (restart mechanics fine);
  // (3) expire past the checkpointed offset → restart is BRICKED with the typed error.
  val hazardStreamExpiredCheckpoint: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("hazard.stream.expiredCheckpoint") { (spark, table) =>
        // memory sink cannot recover from a checkpoint — stream into a second Iceberg table.
        val dst = s"${table}_sink"
        spark.sql(s"DROP TABLE IF EXISTS $dst")
        spark.sql(coreCreateParquet(dst))
        val ckpt = java.nio.file.Files.createTempDirectory("ck-hazard").toString
        def runStream(): Unit = {
          val q = spark.readStream.table(table)
            .writeStream.format("iceberg").outputMode("append")
            .trigger(org.apache.spark.sql.streaming.Trigger.AvailableNow())
            .option("checkpointLocation", ckpt).toTable(dst)
          assert(q.awaitTermination(120000), "stream did not finish"); q.stop()
        }
        try {
          runStream()                                                              // act 1: offset -> s1
          assert(countOf(spark, s"SELECT count(*) FROM $dst") == "3", "initial stream delivered the seed")
          spark.sql(s"INSERT INTO $table VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')") // s2
          runStream()                                                              // act 2: CONTROL restart
          assert(countOf(spark, s"SELECT count(*) FROM $dst") == "4",
            "control restart must deliver exactly the incremental row (restart mechanics work)")
          spark.sql(s"INSERT INTO $table VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')") // s3
          spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
          // act 3: the checkpointed offset (s2) is expired -> restart bricked, typed.
          val e = Check.intercept[Exception](runStream())
          assert(Exceptions.causeChain(e).exists(t => Option(t.getMessage).exists(m =>
            m.contains("expired or removed") || m.contains("Cannot load current offset") || m.contains("Cannot find snapshot"))),
            s"H1 appears FIXED — stream restarted across the expired offset; update MODALITY-RECON H1: " +
              s"${e.getClass.getName} ${Option(e.getMessage).getOrElse("").take(200)}")
        } finally spark.sql(s"DROP TABLE IF EXISTS $dst")
      }()

  // H2 — CDC/changelog over expired lineage: expired explicit bound → hard typed error;
  // timestamp bound → SILENT under-report (the truth was 5 changes; the view shows fewer).
  val hazardCdcExpiredRange: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()                 // s1: 3 rows
      .step("hazard.cdc.expiredRange") { (spark, table) =>
        spark.sql(s"INSERT INTO $table VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')") // s2
        spark.sql(s"INSERT INTO $table VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')") // s3
        val snaps = snapshotIds(spark, table)
        val ts0 = spark.sql(s"SELECT committed_at FROM $table.snapshots ORDER BY committed_at LIMIT 1").collect()(0).getTimestamp(0)
        val tsMid = spark.sql(s"SELECT committed_at FROM $table.snapshots WHERE snapshot_id = ${snaps(1)}").collect()(0).getTimestamp(0)
        spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
        // Characterize each bound placement over the punctured lineage. FULL truth would mean fixed.
        def changelog(optKey: String, optVal: String, truth: Long): String = try {
          val v = spark.sql(
            s"CALL openhouse.system.create_changelog_view(table => '${catalogRelative(table)}', " +
              s"options => map('$optKey', '$optVal'))").collect()(0).getString(0)
          val n = spark.sql(s"SELECT count(*) FROM $v").collect()(0).getLong(0)
          if (n < truth) s"SILENT under-report: $n of $truth true changes" else s"FULL: $n of $truth"
        } catch { case t: Throwable =>
          s"TYPED: ${t.getClass.getSimpleName} :: ${Option(t.getMessage).getOrElse("").take(140)}" }
        val a  = changelog("start-snapshot-id", snaps.head.toString, 5)   // explicit expired bound
        val b1 = changelog("start-timestamp", (ts0.getTime - 1000).toString, 5)   // before all history
        val b2 = changelog("start-timestamp", (tsMid.getTime - 1).toString, 2)    // mid-history, expired region
        println(s"DIAG cdc.explicitExpiredId: $a")
        println(s"DIAG cdc.tsBeforeHistory:  $b1")
        println(s"DIAG cdc.tsMidExpired:     $b2")
        Seq("explicitId" -> a, "tsBeforeHistory" -> b1, "tsMidExpired" -> b2).foreach { case (k, o) =>
          assert(!o.startsWith("FULL"),
            s"H2 appears FIXED for $k — changelog reported the full truth over expired lineage; update MODALITY-RECON H2: $o")
          assert(!o.toLowerCase.contains("expir"),
            s"H2 error now NAMES expiration for $k (readability improved) — update MODALITY-RECON H2/Audit B: $o")
        }
      }()

  // H3 — RTAS wipes column tags (same policies plane as G10) and column comments (new schema from SELECT).
  val hazardRtasWipesColumnTags: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("enableReplace")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('replace.enabled'='true')")()
      .sql("tagPii")(t => s"ALTER TABLE $t MODIFY COLUMN ${Core.string0.columnName} SET TAG = (PII)")()
      .step("hazard.rtas.wipesColumnTags") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table ALTER COLUMN ${Core.string0.columnName} COMMENT 'contains-pii'")
        val before = tableProps(spark, table).getOrElse("policies", "")
        assert(before.toLowerCase.contains("pii") || before.toLowerCase.contains("columntags"),
          s"PII tag not stored in policies before replace: '$before'")
        spark.sql(s"CREATE OR REPLACE TABLE $table USING iceberg AS SELECT * FROM $table WHERE ${Core.long0.columnName} <= 2")
        val after = tableProps(spark, table).getOrElse("policies", "")
        assert(!(after.toLowerCase.contains("pii")),
          s"H3 appears FIXED — PII column tag survived RTAS; update MODALITY-RECON H3 / AUDIT-FINDINGS: '$after'")
        val comment = spark.sql(s"DESCRIBE TABLE $table").collect().toSeq
          .find(_.getString(0) == Core.string0.columnName).map(_.getString(2)).getOrElse("")
        println(s"DIAG rtas.columnComment after replace: '${comment}' (was 'contains-pii')")
      }()

  // H5 — retention × branches: the DEFENDED path (positive invariant): main-side TTL delete +
  // expiration + orphan removal leave a live branch fully readable.
  val hazardRetentionBranchDefended: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING iceberg PARTITIONED BY (${Core.datePartition.columnName}) TBLPROPERTIES ('write.format.default'='parquet')")()
      .insert(3)()
      .step("hazard.retentionBranch.defended") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE BRANCH rbb")
        spark.sql(s"DELETE FROM $table WHERE ${Core.long0.columnName} <= 2")     // retention-shaped main delete
        spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
        spark.sql(s"CALL openhouse.system.remove_orphan_files(table => '${catalogRelative(table)}', older_than => TIMESTAMP '2020-01-01 00:00:00')")
        assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'rbb'") == "3",
          "H5 invariant: branch must remain fully readable after retention-delete + expire + orphan removal")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "1", "main reflects the TTL delete")
      }()

  // H6 — rename × consumers: metadata continuity (branch refs, history, writability survive rename).
  val hazardRenameConsumers: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("hazard.rename.consumers") { (spark, table) =>
      val snaps = snapshotIds(spark, table)
      spark.sql(s"ALTER TABLE $table CREATE BRANCH rnb")
      spark.sql(s"INSERT INTO $table.branch_rnb VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
      val renamed = s"${table}_rn"
      spark.sql(s"ALTER TABLE $table RENAME TO $renamed")
      try {
        assert(countOf(spark, s"SELECT count(*) FROM $renamed VERSION AS OF 'rnb'") == "6",
          "branch ref must survive rename (metadata is continuous)")
        assert(countOf(spark, s"SELECT count(*) FROM $renamed VERSION AS OF ${snaps.head}") == "3",
          "time travel must survive rename (same snapshot log)")
        spark.sql(s"INSERT INTO $renamed VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')")
        assert(countOf(spark, s"SELECT count(*) FROM $renamed") == "6", "renamed table writable")
      } finally spark.sql(s"ALTER TABLE $renamed RENAME TO $table")               // restore for teardown
    }()

  // H7 — wap.enabled=false does NOT strand named branches (only staged wap.id snapshots — G4).
  val hazardWapToggleBranchesSurvive: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .sql("enableWap")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .step("hazard.wapToggle.branchesSurvive") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE BRANCH wtb")
        spark.sql(s"INSERT INTO $table.branch_wtb VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
        spark.sql(s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='false')")
        spark.sql(s"INSERT INTO $table.branch_wtb VALUES (CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')")
        assert(countOf(spark, s"SELECT count(*) FROM $table VERSION AS OF 'wtb'") == "5",
          "named branches must survive the WAP toggle (branch surface is not wap-gated)")
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "3", "main untouched")
      }()

  // H8 — ADD COLUMN breaks every existing explicit-column writer (composition with the
  // partial-INSERT rejection): schema evolution is NOT writer-backward-compatible here,
  // contrary to ANSI SQL (omitted columns default to NULL).
  val hazardAddColumnBreaksWriters: TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(coreCreateParquet)().insert(3)()
      .step("hazard.addColumn.breaksWriters") { (spark, table) =>
        val allCols = Core.tableColumns.map(_.columnName).mkString(", ")
        val writerStatement = s"INSERT INTO $table ($allCols) VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')"
        spark.sql(writerStatement)                                                // the fleet's writer: green today
        assert(countOf(spark, s"SELECT count(*) FROM $table") == "4", "writer works pre-evolution")
        spark.sql(s"ALTER TABLE $table ADD COLUMN extra_col INT")
        val e = Check.intercept[AnalysisException](spark.sql(writerStatement))    // IDENTICAL statement
        assert(e.getMessage.contains("extra_col") &&
               (e.getMessage.contains("CANNOT_FIND_DATA") || e.getMessage.toLowerCase.contains("cannot find data")),
          s"H8 appears FIXED — the pre-evolution writer survived ADD COLUMN (ANSI behavior!); update MODALITY-RECON H8 and BUGS.md: ${e.getMessage.take(200)}")
      }()

  // ── Reader × writer-class battery (BUILD-STATUS task #4) ─────────────────────────────────────
  // A reader (CDC changelog / incremental read / streaming) must correctly REPRESENT each writer
  // class (append / overwrite / delete / update / merge), and the physical mode (CoW vs MoR) must
  // not change what the reader reports. Bound each reader to the seed snapshot so only the writer's
  // change is under test. Non-vacuous core; the appraisal's 120 assumed every bound-shape crossed —
  // this builds the writer-class × reader core (~16), the part that actually varies by writer.
  // Format is a parameter (default parquet) so reader×writer blocks can multiplex across formats.
  private def cowCreate(t: String, fmt: String): String =
    s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES ('write.format.default'='$fmt')"
  private def cowCreate(t: String): String = cowCreate(t, "parquet")
  private def morCreate(t: String, fmt: String): String =
    s"CREATE TABLE $t ($columnDefinitions) USING iceberg TBLPROPERTIES (${morPropsFmt(fmt)})"
  private def morCreate(t: String): String = morCreate(t, "parquet")

  private val writerClasses: List[(String, String => String)] = List(
    "append"    -> (t => s"INSERT INTO $t VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')"),
    "overwrite" -> (t => s"INSERT OVERWRITE $t SELECT * FROM $t WHERE ${Core.long0.columnName} <= 2"),
    "delete"    -> (t => s"DELETE FROM $t WHERE ${Core.long0.columnName} = 1"),
    "update"    -> (t => s"UPDATE $t SET ${Core.string0.columnName} = 'upd' WHERE ${Core.long0.columnName} = 2"),
    "merge"     -> (t => s"MERGE INTO $t t USING (SELECT CAST(2 AS BIGINT) k UNION ALL SELECT CAST(9 AS BIGINT)) s " +
      s"ON t.${Core.long0.columnName} = s.k WHEN MATCHED THEN UPDATE SET ${Core.string0.columnName} = 'm' " +
      s"WHEN NOT MATCHED THEN INSERT (${Core.long0.columnName}, ${Core.int0.columnName}, ${Core.string0.columnName}, " +
      s"${Core.double0.columnName}, ${Core.boolean0.columnName}, ${Core.datePartition.columnName}) " +
      s"VALUES (s.k, 9, 'row-9', 9.5, true, '2024-01-09-01')")
  )

  // CDC changelog must represent each writer class; assert the defining change-type + print the map.
  private def changelogWriterTest(cls: String, mor: Boolean, fmt: String): TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => if (mor) morCreate(t, fmt) else cowCreate(t, fmt))().insert(3)()
      .step(s"readerWriter.changelog.$cls${if (mor) ".mor" else ""}") { (spark, table) =>
        val s0 = snapshotIds(spark, table).head
        spark.sql(writerClasses.toMap.apply(cls)(table))
        // FINDING (G13): a changelog scan REJECTS a MoR table whose update/merge wrote position-delete
        // files ("Delete files are currently not supported in changelog scans"). MoR delete-only and
        // all CoW writers work; MoR update/merge do NOT — CDC silently unavailable for that shape.
        val expectRejected = mor && (cls == "update" || cls == "merge")
        def buildView(): String = spark.sql(
          s"CALL openhouse.system.create_changelog_view(table => '${catalogRelative(table)}', " +
            s"options => map('start-snapshot-id', '$s0'))").collect()(0).getString(0)
        if (expectRejected) {
          val e = Check.intercept[Exception] { val v = buildView(); spark.sql(s"SELECT * FROM $v").collect() }
          assert(Exceptions.causeChain(e).exists(t => Option(t.getMessage).exists(_.contains("Delete files are currently not supported"))),
            s"G13 appears FIXED — changelog over MoR $cls no longer rejects delete files; update AUDIT-FINDINGS: ${e.getMessage.take(160)}")
          println(s"DIAG changelog.$cls.mor: REJECTED (G13 - delete files unsupported in changelog scans)")
        } else {
          val v = buildView()
          val types = spark.sql(s"SELECT _change_type, count(*) AS c FROM $v GROUP BY _change_type")
            .collect().toSeq.map(r => r.getString(0) -> r.getLong(1)).toMap
          println(s"DIAG changelog.$cls${if (mor) ".mor" else ""}: $types")
          cls match {
            case "append" => assert(types.getOrElse("INSERT", 0L) == 1 && !types.contains("DELETE"),
              s"append changelog must be a single INSERT, no DELETE: $types")
            case "delete" => assert(types.getOrElse("DELETE", 0L) == 1 && !types.contains("INSERT"),
              s"delete changelog must be a single DELETE, no INSERT: $types")
            case "update" => assert(types.getOrElse("DELETE", 0L) >= 1 && types.getOrElse("INSERT", 0L) >= 1,
              s"update changelog must decompose to DELETE(old)+INSERT(new): $types")
            case _        => assert(types.values.sum >= 1, s"$cls changelog must be non-empty: $types")
          }
        }
      }()

  // Incremental read (append scan) must reflect the writer: appends add rows; a delete/overwrite
  // changes the incremental row set. Bound start=seed.
  private def incrementalWriterTest(cls: String, fmt: String): TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => cowCreate(t, fmt))().insert(3)()
      .step(s"readerWriter.incremental.$cls") { (spark, table) =>
        val s0 = snapshotIds(spark, table).head
        spark.sql(writerClasses.toMap.apply(cls)(table))
        val s1 = snapshotIds(spark, table).last
        val added = spark.read.format("iceberg").option("start-snapshot-id", s0).option("end-snapshot-id", s1)
          .load(table).count()
        println(s"DIAG incremental.$cls: added=$added")
        cls match {
          case "append" => assert(added == 1, s"append incremental must scan the 1 appended row: $added")
          case _        => assert(added >= 0, s"$cls incremental read must not error: $added")
        }
      }()

  // Streaming read must represent the writer: an append is delivered; a delete/overwrite snapshot is
  // rejected by the stream unless streaming-skip-* is set (characterize the two paths).
  def readerWriterStreamAppend(fmt: String): TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => cowCreate(t, fmt))().insert(3)()
      .step("readerWriter.stream.append") { (spark, table) =>
        val dst = s"${table}_s"; spark.sql(s"DROP TABLE IF EXISTS $dst"); spark.sql(cowCreate(dst, fmt))
        val ckpt = java.nio.file.Files.createTempDirectory("ck-rw").toString
        def run(): Unit = { val q = spark.readStream.table(table).writeStream.format("iceberg")
          .outputMode("append").trigger(org.apache.spark.sql.streaming.Trigger.AvailableNow())
          .option("checkpointLocation", ckpt).toTable(dst); assert(q.awaitTermination(120000)); q.stop() }
        try {
          run(); assert(countOf(spark, s"SELECT count(*) FROM $dst") == "3", "seed not streamed")
          spark.sql(writerClasses.toMap.apply("append")(table))
          run(); assert(countOf(spark, s"SELECT count(*) FROM $dst") == "4", "append not streamed incrementally")
        } finally spark.sql(s"DROP TABLE IF EXISTS $dst")
      }()

  def readerWriterStreamDelete(fmt: String): TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(t => cowCreate(t, fmt))().insert(3)()
      .step("readerWriter.stream.deleteRejected") { (spark, table) =>
        val dst = s"${table}_sd"; spark.sql(s"DROP TABLE IF EXISTS $dst"); spark.sql(cowCreate(dst, fmt))
        val ckpt = java.nio.file.Files.createTempDirectory("ck-rwd").toString
        def run(): Unit = { val q = spark.readStream.table(table).writeStream.format("iceberg")
          .outputMode("append").trigger(org.apache.spark.sql.streaming.Trigger.AvailableNow())
          .option("checkpointLocation", ckpt).toTable(dst); assert(q.awaitTermination(120000)); q.stop() }
        try {
          run()                                                       // consume the seed
          spark.sql(writerClasses.toMap.apply("delete")(table))       // a delete snapshot
          val e = Check.intercept[Exception](run())
          println(s"DIAG stream.afterDelete: ${e.getClass.getSimpleName} :: ${Option(e.getMessage).getOrElse("").take(140)}")
          assert(Exceptions.causeChain(e).exists(t => Option(t.getMessage).exists(m =>
            m.toLowerCase.contains("delete") || m.toLowerCase.contains("overwrite"))),
            s"append-only stream must reject a delete snapshot (streaming-skip-* needed): ${e.getMessage.take(140)}")
        } finally spark.sql(s"DROP TABLE IF EXISTS $dst")
      }()

  def readerWriterOps(fmt: String): List[(String, TableTest[CoreTable.type])] = {
    val changelog = for {
      (cls, _) <- writerClasses
      mor      <- List(false, true)
    } yield (s"readerWriter.changelog.$cls${if (mor) ".mor" else ""}", changelogWriterTest(cls, mor, fmt))
    val incremental = List("append", "delete", "overwrite", "update").map(c =>
      (s"readerWriter.incremental.$c", incrementalWriterTest(c, fmt)))
    changelog ++ incremental ++ List(
      "readerWriter.stream.append"         -> readerWriterStreamAppend(fmt),
      "readerWriter.stream.deleteRejected" -> readerWriterStreamDelete(fmt))
  }

  val hazardOps: List[(String, TableTest[CoreTable.type])] = List(
    "hazard.stream.expiredCheckpoint"   -> hazardStreamExpiredCheckpoint,
    "hazard.cdc.expiredRange"           -> hazardCdcExpiredRange,
    "hazard.rtas.wipesColumnTags"       -> hazardRtasWipesColumnTags,
    "hazard.retentionBranch.defended"   -> hazardRetentionBranchDefended,
    "hazard.rename.consumers"           -> hazardRenameConsumers,
    "hazard.wapToggle.branchesSurvive"  -> hazardWapToggleBranchesSurvive,
    "hazard.addColumn.breaksWriters"    -> hazardAddColumnBreaksWriters
  )

  // H4 — lock starves maintenance (needs the REST lock → Ctx-based). The same gate G2 shows the
  // replace path SKIPS is hit by every maintenance commit: upkeep is blocked, replacement is not.
  def hazardLockStarvesMaintenance(ctx: Ctx): Unit = {
    val spark = ctx.spark
    val table = s"${ctx.namespace}.t_lockmaint"
    val Array(db, tbl) = table.stripPrefix("openhouse.").split("\\.", 2)
    spark.sql(s"DROP TABLE IF EXISTS $table")
    spark.sql(coreCreateParquet(table))
    spark.sql(s"INSERT INTO $table ${RowGenerator.valuesClause(Core, 3)}")
    spark.sql(s"INSERT INTO $table VALUES (CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
    try {
      val (lockStatus, lockBody) = Rest.post(ctx, s"/v1/databases/$db/tables/$tbl/lock", """{"locked":true}""")
      assert(lockStatus >= 200 && lockStatus < 300, s"lock POST failed: $lockStatus $lockBody")
      val snapsBefore = spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0)
      val e = Check.intercept[Exception](spark.sql(
        s"CALL openhouse.system.expire_snapshots(table => '${table.stripPrefix("openhouse.")}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)"))
      assert(Exceptions.causeChain(e).exists(t => Option(t.getMessage).exists(_.toLowerCase.contains("locked"))),
        s"expected LOCKED rejection for the maintenance commit: ${e.getClass.getName} ${Option(e.getMessage).getOrElse("").take(180)}")
      spark.sql(s"REFRESH TABLE $table")
      val snapsAfter = spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0)
      assert(snapsAfter == snapsBefore, "locked table must accumulate snapshots (maintenance starved)")
      val (unlockStatus, _) = Rest.delete(ctx, s"/v1/databases/$db/tables/$tbl/lock")
      assert(unlockStatus >= 200 && unlockStatus < 300, "unlock failed")
      spark.sql(s"CALL openhouse.system.expire_snapshots(table => '${table.stripPrefix("openhouse.")}', older_than => TIMESTAMP '2999-01-01 00:00:00', retain_last => 1)")
      spark.sql(s"REFRESH TABLE $table")
      assert(spark.sql(s"SELECT count(*) FROM $table.snapshots").collect()(0).getLong(0) < snapsBefore,
        "maintenance must proceed after unlock")
    } finally {
      Rest.delete(ctx, s"/v1/databases/$db/tables/$tbl/lock")
      spark.sql(s"DROP TABLE IF EXISTS $table")
    }
  }

  val hazardCtxOps: List[(String, Ctx => Unit)] = List(
    "hazard.lock.starvesMaintenance" -> hazardLockStarvesMaintenance
  )
}

/** Assembles the run: every operation x every layout, plus create.schema per layout. */
object Plan {
  final case class Case(id: String, run: Ctx => Unit)

  // Known PRODUCT bugs: any case whose id contains the key is reported SKIP (bug: reason) instead
  // of failing the suite, and is tracked in BUGS.md. This is how we "tag a failing test and filter
  // it": a genuine bug is tagged here, deferred for follow-up, and never plowed past silently.
  val knownBugs: List[(String, String)] = List(
    // insert.explicitColumns is NO LONGER a bug tag — reclassified to a negative PIN (engine limitation,
    // not OpenHouse; code-verified). See insertExplicitColumns above and BUGS.md.
    "nested.deleteByNestedField" ->
      "DELETE WHERE <nested struct field> crashes with an internal optimizer NPE (SELECT/UPDATE on the same field work). Code-verified UPSTREAM: OpenHouse contributes no code to the row-level DELETE rewrite (owned by IcebergSparkSessionExtensions + Spark optimizer); the NPE is in the nested-field DELETE-rewrite plan. Needs a full stack capture before filing — see BUGS.md",
    "ddl.renameColumn" ->
      "RENAME COLUMN is a silent no-op. Code-verified GENUINE OpenHouse regression from #558 (commit 0ad4914): server-side normalizeSchemaCasingToTable rewrites every field's name to the table's spelling BY FIELD ID (BaseIcebergSchemaValidator:60-73), reverting the rename, and it runs BEFORE the sameSchema gate so validateWriteSchema (which would reject loudly) never fires. Fix: guard the normalizer with equalsIgnoreCase. Silent failure worse than the pre-#558 clean rejection — see BUGS.md",
    "ddl.encryption" ->
      "encryption KMS plugin is external/private (no impl/interface/mock in-repo); OSS leaves the encryption() hook un-wired and writes plaintext, so the intended-behavior assertion is deferred until the plugin is present — see DDL-TEST-PLAN.md / AUDIT-FINDINGS.md",
    "control.undrop" ->
      "undrop is SKIP under the DEFAULT stub path (HouseTableRepository is a @Primary in-memory stub; the public Tables DELETE hard-codes purge=true). Under HARNESS_REAL_HTS=1 the real embedded HTS is booted and undrop runs for real as the undrop:* battery + undropAdmin.* lifecycle (NOT SKIP) — see HTS-EMBED-PLAN.md / HTS-EMBED-IMPL.md / REST-FIDELITY-EVAL.md"
  )

  def bugReason(id: String): Option[String] =
    knownBugs.collectFirst { case (key, reason) if id.contains(key) => s"bug: $reason" }

  def cases: List[Case] = {
    val dml = for {
      layout        <- Scenarios.layouts
      (name, op)    <- Scenarios.operations
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeed(layout, 3).andThen(op).run)

    val partitioned = for {
      layout        <- Scenarios.layouts.filter(_.label.startsWith("partitioned/"))
      (name, op)    <- Scenarios.partitionedOperations
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeed(layout, 3).andThen(op).run)

    // Merge-on-read: the same mutation operations, prepared on a MoR table.
    val mor = for {
      layout        <- Scenarios.morLayouts
      (name, op)    <- Scenarios.mutationOperations
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeed(layout, 3).andThen(op).run)

    // MoR discriminator: prove merge-on-read wrote delete files, and copy-on-write did not.
    val morVerify = Scenarios.morVerifyLayouts.map(layout =>
      Case(s"mor.writesDeleteFiles @ ${layout.label}", Scenarios.createAndSeedSingleFile(layout, 3).andThen(Scenarios.morWritesDeleteFiles).run))
    val cowVerify = Scenarios.cowVerifyLayouts.map(layout =>
      Case(s"cow.writesNoDeleteFiles @ ${layout.label}", Scenarios.createAndSeedSingleFile(layout, 3).andThen(Scenarios.cowWritesNoDeleteFiles).run))

    // Nested / complex types, on their own schema and layouts.
    val nested = for {
      layout        <- Scenarios.nestedLayouts
      (name, op)    <- Scenarios.nestedOperations
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeedNested(layout, 3).andThen(op).run)

    // Type-edge coverage, on TypesTable.
    val types = for {
      layout        <- Scenarios.typesLayouts
      (name, op)    <- Scenarios.typesOperations
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeedTypes(layout, 3).andThen(op).run)

    // Partition transforms + evolution (self-contained pipelines, parquet).
    val partitionTransforms = Scenarios.partitionTransforms.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val partitionEvolution  = Scenarios.partitionEvolution.map { case (name, t) => Case(s"$name @ parquet", t.run) }

    // Time travel + restore/rollback (self-contained pipelines, parquet).
    // Format-sensitive blocks multiplex across parquet+orc (the seed format is a parameter, not baked in).
    val dataFormats     = List("parquet", "orc")
    val timeTravel      = for { f <- dataFormats; (name, t) <- Scenarios.timeTravelOps(f) }     yield Case(s"$name @ $f", t.run)
    val restoreRollback = for { f <- dataFormats; (name, t) <- Scenarios.restoreRollbackOps(f) } yield Case(s"$name @ $f", t.run)
    val maintenance     = for { f <- dataFormats; (name, t) <- Scenarios.maintenanceOps(f) }     yield Case(s"$name @ $f", t.run)
    val control         = Scenarios.controlPlane.map { case (name, f) => Case(s"$name @ embedded", f) }
    val forkColDefault  = Scenarios.forkColDefaultOps.map { case (name, f) => Case(name, f) }
    val forkPartitionDist = Scenarios.forkPartitionDistOps.map { case (name, f) => Case(name, f) }
    val branching       = Scenarios.branching.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val interactions    = Scenarios.interactions.map { case (name, t) => Case(s"$name @ parquet", t.run) } ++
      Scenarios.interactionCtxOps.map { case (name, f) => Case(s"$name @ embedded", f) }
    val surface         = Scenarios.surfaceOps.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val hazards         = Scenarios.hazardOps.map { case (name, t) => Case(s"$name @ parquet", t.run) } ++
      Scenarios.hazardCtxOps.map { case (name, f) => Case(s"$name @ embedded", f) }
    val readerWriter    = for { f <- dataFormats; (name, t) <- Scenarios.readerWriterOps(f) } yield Case(s"$name @ $f", t.run)
    val negatives       = Scenarios.negatives.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val ddlNegatives    = Scenarios.ddlNegatives.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val ddlProps        = Scenarios.ddlPropsOperations.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val ddlMisc         = Scenarios.ddlMiscOperations.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val ddlPolicy       = Scenarios.ddlPolicyOperations.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val ddlCtasRtas     = Scenarios.ddlCtasRtasOperations.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val ddlTagAcl       = Scenarios.ddlTagAclFeatureOperations.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val ddlEncryption   = Scenarios.ddlEncryptionOperations.map { case (name, t) => Case(s"$name @ parquet", t.run) }

    // Phase 24 prep multipliers (full DML cross). Ordered prep × all operations; evolved prep ×
    // delete/update/read only (ADD COLUMN changes INSERT arity, breaking full-column inserts).
    val ddlPrepOrdered = for {
      layout     <- Scenarios.layouts
      (name, op) <- Scenarios.operations
    } yield Case(s"prep.ordered:$name @ ${layout.label}", Scenarios.createAndSeedOrdered(layout, 3).andThen(op).run)

    // delete/update/read only, and excluding ops that internally INSERT a full-column row
    // (delete.byNullCondition seeds a null row) — those hit the arity mismatch on the +1-column table.
    val ddlPrepEvolved = for {
      layout     <- Scenarios.layouts
      (name, op) <- Scenarios.operations.filter { case (n, _) =>
        (n.startsWith("delete.") || n.startsWith("update.") || n.startsWith("read.")) && !n.contains("byNullCondition") }
    } yield Case(s"prep.evolved:$name @ ${layout.label}", Scenarios.createAndSeedEvolved(layout, 3).andThen(op).run)

    // T axis — the whole DML catalog routed onto a BRANCH via spark.wap.branch (SURFACE-APPRAISAL
    // step 3). Format is vacuous for branches (refs never touch file encoding), so parquet only;
    // both partitionings kept (partitioning changes overwrite/dynamic-overwrite semantics on the
    // branch). Every op asserts its normal delta — now proving the op works branch-routed AND that
    // main is untouched (isolation). ~106 cases.
    // Format policy: ORC + Parquet (both), not parquet-only. Avro is intentionally NOT added to these
    // ref/metadata-routed blocks (branch/undrop/DDL-consumer) — the additive ask was ORC, and the
    // 3-format blocks keep Avro separately.
    val branchParquetLayouts = Scenarios.layouts.filter(l => l.label.endsWith("/parquet") || l.label.endsWith("/orc"))
    val branchWap = for {
      layout     <- branchParquetLayouts
      (name, op) <- Scenarios.operations
    } yield Case(s"branchWap:$name @ ${layout.label}",
      Scenarios.createAndSeedOnBranch(layout, 3).andThen(op).andThen(Scenarios.branchMainIsolation).run)

    // Over-prune miss #2: branch × MoR. The mutation ops routed onto a branch of a MoR table —
    // NOT vacuous (the MoR-branch merge story differs; cherry-pick rejects row-delete snapshots).
    val branchMorLayout = Scenarios.morLayouts.filter(l => l.label == "mor-unpartitioned/parquet" || l.label == "mor-unpartitioned/orc")
    val branchWapMor = for {
      layout     <- branchMorLayout
      (name, op) <- Scenarios.mutationOperations
    } yield Case(s"branchWap:$name @ ${layout.label}",
      Scenarios.createAndSeedOnBranch(layout, 3).andThen(op).andThen(Scenarios.branchMainIsolation).run)

    // P axis (replace-lineage leg) — the whole DML catalog on an RTAS'd table (SURFACE-APPRAISAL
    // step 2). ~106 cases. (The undrop leg is gated on the embedded-HTS restructure — see
    // REST-FIDELITY-EVAL.md — so only the RTAS leg is runnable now.)
    val prepRtas = for {
      (label, partitionClause, fmt) <- Scenarios.rtasPrepShapes
      (name, op)                    <- Scenarios.operations
    } yield Case(s"prep.rtas:$name @ $label", Scenarios.createAndSeedRtas(partitionClause, 3, fmt).andThen(op).run)

    // Over-prune miss #1: RTAS × MoR — mutation ops on a replace-lineage MoR table. ORC + Parquet.
    val prepRtasMor = for {
      fmt        <- List("parquet", "orc")
      (name, op) <- Scenarios.mutationOperations
    } yield Case(s"prep.rtasMor:$name @ mor-unpartitioned/$fmt",
      Scenarios.createAndSeedRtasMor("", 3, fmt).andThen(op).run)

    // P axis (drop→undrop leg) — the whole DML catalog on a table taken through a real HTS soft-delete
    // → restore round-trip (SURFACE-APPRAISAL). Requires the embedded real HTS (HARNESS_REAL_HTS=1);
    // empty otherwise. This is the surface-DOUBLING leg: every op re-verifies that the restored table
    // still behaves identically, i.e. that restore's destruction set does not intersect the feature's
    // state-dependency set. Undrop is metadata/ref reconstruction — file encoding is vacuous → parquet
    // layouts only (as with RTAS/branch).
    val undrop =
      if (HtsAdmin.enabled) for {
        layout     <- branchParquetLayouts
        (name, op) <- Scenarios.operations
      } yield Case(s"undrop:$name @ ${layout.label}",
        Scenarios.createAndSeedUndropped(layout, 3).andThen(op).run)
      else Nil

    // Undrop admin-lifecycle block (Phase 5) — soft-delete/list/restore/purge, real HTS only.
    val undropAdmin =
      if (HtsAdmin.enabled) Scenarios.undropAdminOps.map { case (name, run) => Case(name, run) }
      else Nil

    // Block 9 deepening: undrop 3-way compositions (branch/time-travel/schema survival), real HTS only.
    val undropInteract =
      if (HtsAdmin.enabled) Scenarios.undropInteractOps.map { case (name, run) => Case(name, run) }
      else Nil

    // DDL × consumer battery (task #3): each state-changing DDL, then each consumer must still work.
    // 4 DDL × 6 consumers × {unpartitioned, partitioned}/parquet = 48.
    val ddlConsumerBattery = for {
      layout          <- branchParquetLayouts
      (ddlName, prep) <- Scenarios.ddlPreps
      (conName, con)  <- Scenarios.ddlConsumers
    } yield Case(s"ddlConsume:$ddlName.$conName @ ${layout.label}", prep(layout).andThen(con).run)

    // MoR reads with a live position delete (closes the scan-path gap, step 1). Read/scan ops only —
    // they must apply the position delete at read time. Across formats (delete-file encoding differs).
    val morReadOps = Scenarios.operations.filter { case (n, _) => n.startsWith("read.") || n == "format.materialization" }
    val prepMorRead = for {
      layout     <- Scenarios.morVerifyLayouts   // single-file-friendly MoR layouts, per format
      (name, op) <- morReadOps
    } yield Case(s"prep.morRead:$name @ ${layout.label}", Scenarios.createAndSeedMorDeleted(layout, 3).andThen(op).run)

    // MoR delete-file COEXISTENCE (task #5 non-vacuous core): ops on a table that already carries a
    // live position delete. Format matters (delete-file encoding) → × 3 MoR formats.
    val morCoexist = for {
      layout     <- Scenarios.morVerifyLayouts
      (name, op) <- Scenarios.morCoexistOps
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeedMorDeleted(layout, 3).andThen(op).run)

    // Block 8 deepening: maintenance × MoR-with-live-delete. The delete-DECODE op (rewrite_data_files
    // fold) is format-relevant → × 3 MoR formats; metadata-only maintenance is format-vacuous → × 1.
    val maintenanceMorFold = for {
      layout     <- Scenarios.morVerifyLayouts
      (name, op) <- Scenarios.maintenanceMorFoldOps
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeedMorDeleted(layout, 3).andThen(op).run)
    val morParquetVerify = Scenarios.morVerifyLayouts.filter(l => l.label == "mor-verify/parquet" || l.label == "mor-verify/orc")
    val maintenanceMorMeta = for {
      layout     <- morParquetVerify
      (name, op) <- Scenarios.maintenanceMorMetaOps
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeedMorDeleted(layout, 3).andThen(op).run)

    // Block 10 deepening: MoR delete-file modality hazards (time-travel / rollback / expire). Snapshot
    // logic is format-vacuous → × 1 MoR layout.
    val morHazard = for {
      layout     <- morParquetVerify
      (name, op) <- Scenarios.morHazardOps
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeedMorDeleted(layout, 3).andThen(op).run)

    // MoR × branch MERGE: position deletes carried across fast_forward / cherry_pick / REPLACE BRANCH.
    // Single-file MoR seed so a branch DELETE is a real position delete; merge is format-vacuous → ×1.
    val morBranchMerge = for {
      layout     <- morParquetVerify
      (name, op) <- Scenarios.morBranchMergeOps
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeedSingleFile(layout, 3).andThen(op).run)

    // Encryption capability pin (characterization): OSS writes plaintext parquet (encryption un-wired).
    val encryptionPin = List(Case("surface.pin.dataPlaintext @ parquet", Scenarios.encryptionPlaintextPin.run))

    val creates = Scenarios.layouts.map { layout =>
      Case(s"create.schema @ ${layout.label}", Scenarios.createSchema(layout).run)
    }

    // DDL Phase 12: schema-evolution behaviors crossed with every layout.
    val ddlSchema = for {
      layout     <- Scenarios.layouts
      (name, op) <- Scenarios.ddlSchemaOperations
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeed(layout, 3).andThen(op).run)

    dml ++ partitioned ++ mor ++ morVerify ++ cowVerify ++ nested ++ types ++ partitionTransforms ++
      partitionEvolution ++ timeTravel ++ restoreRollback ++ negatives ++ creates ++ ddlSchema ++
      ddlNegatives ++ ddlProps ++ ddlMisc ++ ddlPolicy ++ ddlCtasRtas ++ ddlTagAcl ++ ddlEncryption ++
      maintenance ++ control ++ branching ++ interactions ++ surface ++ hazards ++ branchWap ++
      branchWapMor ++ prepRtas ++ prepRtasMor ++ prepMorRead ++ morCoexist ++ ddlConsumerBattery ++
      readerWriter ++ ddlPrepOrdered ++ ddlPrepEvolved ++ undrop ++ undropAdmin ++
      maintenanceMorFold ++ maintenanceMorMeta ++ undropInteract ++ morHazard ++ morBranchMerge ++
      encryptionPin ++ forkColDefault ++ forkPartitionDist
  }
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

// Boot app for the REAL House Table Service as a 2nd Spring context in-JVM (HTS-embed, Option A).
// Mirrors services/.../e2e/SpringH2HtsApplication's annotation set (test-scope, so replicated here).
// Security auto-config is excluded (spring-security-web is only partially present on the harness
// classpath, and the harness runs unauthenticated) — exactly as the tables boot does.
// internal.catalog.mapper is intentionally NOT scanned (a client-side concern needing FileIOManager;
// the HTS server does not use it). Proven by HtsBootProbe.
@org.springframework.boot.autoconfigure.SpringBootApplication(
  exclude = Array(
    classOf[org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration],
    classOf[org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration]))
@org.springframework.context.annotation.ComponentScan(basePackages = Array(
  "com.linkedin.openhouse.housetables.api",
  "com.linkedin.openhouse.housetables.dto.mapper",
  "com.linkedin.openhouse.housetables.controller",
  "com.linkedin.openhouse.housetables.services",
  "com.linkedin.openhouse.common.exception.handler",
  "com.linkedin.openhouse.common.audit",
  "com.linkedin.openhouse.housetables.repository",
  "com.linkedin.openhouse.housetables.properties",
  "com.linkedin.openhouse.housetables.config",
  "com.linkedin.openhouse.cluster.configs",
  "com.linkedin.openhouse.cluster.storage"))
@org.springframework.boot.autoconfigure.domain.EntityScan(
  basePackages = Array("com.linkedin.openhouse.housetables.model"))
class HtsBootApp

/** Boots the embedded real House Table Service (H2, MySQL-mode) as its own Spring context. */
object HtsEnv {
  import org.springframework.boot.builder.SpringApplicationBuilder
  import org.springframework.boot.web.context.WebServerApplicationContext
  import org.springframework.context.ConfigurableApplicationContext

  /** @return (context, base-uri) for the embedded HTS. */
  def start(): (ConfigurableApplicationContext, String) = {
    val root = System.getProperty("java.io.tmpdir") + "/hts-embed"
    val ctx = new SpringApplicationBuilder(classOf[HtsBootApp])
      .properties(
        "server.port=0",
        "cluster.storage.root-path=" + root,
        "cluster.tables.allowed-client-name-values=trino,spark")
      .run()
    val port = ctx.asInstanceOf[WebServerApplicationContext].getWebServer.getPort
    (ctx, s"http://localhost:$port")
  }
}

/** Boots the embedded OpenHouse server and wires a SparkSession to the OpenHouse catalog. */
object OpenHouseEnv {
  import com.linkedin.openhouse.tablestest.OpenHouseLocalServer
  import org.springframework.context.ConfigurableApplicationContext

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

  def start(): (OpenHouseLocalServer, SparkSession, String, String, Option[ConfigurableApplicationContext]) = {
    // HTS-embed (Option A): when HARNESS_REAL_HTS=1, boot the real House Table Service as a 2nd
    // Spring context, point the embedded tables server's HouseTableRepositoryImpl at it via
    // cluster.housetables.base-uri, and disable the @Primary in-memory stub (openhouse.htsStub.enabled
    // =false) so the real HTTP client is the sole HouseTableRepository. Default (flag unset) keeps the
    // stub — the existing green baseline is always reproducible.
    val realHts = sys.env.get("HARNESS_REAL_HTS").contains("1")
    val htsCtxOpt: Option[ConfigurableApplicationContext] =
      if (realHts) {
        // Boot the HTS context FIRST, while no spring.sql.init.mode System property is set, so it
        // uses its own application.properties (spring.sql.init.mode=always) and runs schema.sql +
        // data.sql on its MySQL-mode H2. The tables-context suppression props below are set AFTER
        // this returns (the HTS context is already fully refreshed), so they don't affect HTS.
        val (ctx, htsUri) = HtsEnv.start()
        HtsAdmin.htsUri = htsUri   // enables the undrop preparation axis (Phase 4)
        System.setProperty("cluster.housetables.base-uri", htsUri)
        System.setProperty("openhouse.htsStub.enabled", "false")
        println(s">> REAL HTS mode: embedded HTS at $htsUri (stub disabled)")
        Some(ctx)
      } else None

    // ALWAYS (both stub and real-HTS modes): housetables-lib.jar is on the harness classpath
    // unconditionally (print-cp.init.gradle pulls it in for the real-HTS path). Its root
    // data.sql/schema.sql are MySQL-dialect and would be auto-run by the TABLES context's H2
    // (non-MySQL mode) → INSERT IGNORE syntax error. The tables side ships no SQL scripts and relies
    // on Hibernate auto-DDL, so (i) never run classpath SQL init for it, and (ii) make auto-DDL
    // explicit (the stray schema.sql otherwise flips Spring Boot's embedded-H2 ddl-auto default to
    // `none`, leaving the tables server's own H2 tables — feature-toggle status/rules — missing).
    // In real-HTS mode this runs AFTER HtsEnv.start(), so the HTS schema (which needs init) is safe.
    System.setProperty("spring.sql.init.mode", "never")
    System.setProperty("spring.jpa.hibernate.ddl-auto", "create-drop")

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
    (server, wired.getOrCreate(), uri, token, htsCtxOpt)
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val (server, spark, restUri, restToken, htsCtxOpt) = OpenHouseEnv.start()
    spark.sparkContext.setLogLevel("ERROR")
    HtsAdmin.tablesUri = restUri; HtsAdmin.token = restToken   // undrop restore path (Phase 4)
    val ctx = Ctx(spark, "openhouse.dbMatrix", restUri, restToken)

    // Each command-line arg is an include-substring; a case runs only if its id contains ALL of
    // them (AND). No args = run everything.
    val filters = args.toList
    def selected(id: String): Boolean = filters.forall(id.contains)
    val cases = Plan.cases.filter(c => selected(c.id))

    val header = if (filters.isEmpty) "all cases" else s"filter ${filters.mkString(", ")} -> ${cases.size} cases"
    println(s"\n=== delta-harness :: typed pipelines @ OpenHouse catalog ($header) ===\n")

    // Known-bug cases are tagged (Plan.knownBugs) and reported SKIP rather than run — deferred,
    // not passing. Everything else executes.
    //
    // Cases are independent (each owns its table via the atomic counter), so they run on a worker
    // pool. Each worker task gets its OWN SparkSession (spark.newSession(): separate SQLConf —
    // isolating the session-global state some tests mutate, e.g. spark.wap.branch/wap.id and
    // changelog temp views — over the shared SparkContext). Results are collected and printed in
    // the original case order, so output is identical to a sequential run.
    // HARNESS_PARALLELISM overrides; <=1 falls back to the sequential path.
    val parallelism = sys.env.get("HARNESS_PARALLELISM").map(_.toInt)
      .getOrElse(math.max(1, Runtime.getRuntime.availableProcessors()))
    println(s"parallelism: $parallelism worker sessions\n")

    def runOne(c: Plan.Case): (String, (Outcome, Int)) =
      Plan.bugReason(c.id) match {
        case Some(reason) => (c.id, (Outcome.Skipped(reason): Outcome, 0))
        case None         => (c.id, Runner.execute(c, ctx.copy(spark = ctx.spark.newSession())))
      }

    val results =
      if (parallelism <= 1) cases.map(runOne)
      else {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(parallelism)
        try {
          val futures = cases.map(c => pool.submit(new java.util.concurrent.Callable[(String, (Outcome, Int))] {
            def call(): (String, (Outcome, Int)) = runOne(c)
          }))
          futures.map(_.get(60, java.util.concurrent.TimeUnit.MINUTES))
        } finally pool.shutdown()
      }

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
    if (passed == 0) println("WARNING: no case actually passed (empty selection or all skipped) — reporting failure")

    try spark.stop() catch { case _: Throwable => () }
    try server.stop() catch { case _: Throwable => () }
    htsCtxOpt.foreach(ctx => try ctx.close() catch { case _: Throwable => () })
    // A run that validated nothing (0 cases, or everything skipped) is NOT success.
    System.exit(if (failed == 0 && passed > 0) 0 else 1)
  }
}
