package harness

import org.apache.spark.sql.{AnalysisException, Row, SparkSession}
import org.apache.iceberg.exceptions.BadRequestException
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
  val insertExplicitColumns: TableTest[CoreTable.type] =
    TableTest(Core).sql("insert.explicitColumns")(table =>
      s"INSERT INTO $table (${Core.long0.columnName}, ${Core.string0.columnName}) " +
        s"VALUES (CAST(4 AS BIGINT), 'd'), (CAST(5 AS BIGINT), 'e')") { view =>
      assert(keyed(view.after) == (view.before.map(_.get(Core.long0)) ++ Seq(4L, 5L)).sorted)
      val row4 = view.after.find(_.get(Core.long0) == 4L)
      assert(row4.map(_.get(Core.string0)).contains("d"))
      assert(row4.exists(_.isNullAt(1))) // col_int (position 1) was not supplied
    }

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
  private def coreTwoSnapshots: TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(table => s"CREATE TABLE $table ($columnDefinitions) USING iceberg TBLPROPERTIES ('write.format.default'='parquet')")()
      .insert(3)()
      .sql("insertMore")(table => s"INSERT INTO $table VALUES " +
        s"(CAST(4 AS BIGINT), 4, 'row-4', 4.5, true, '2024-01-04-03'), (CAST(5 AS BIGINT), 5, 'row-5', 5.5, false, '2024-01-05-04')")()

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

  val timeTravelVersionAsOf: TableTest[CoreTable.type] =
    coreTwoSnapshots.check("timeTravel.versionAsOf") { view =>
      val snaps = snapshotIds(view.spark, view.table)
      assert(view.spark.sql(s"SELECT count(*) FROM ${view.table} VERSION AS OF ${snaps(0)}").collect()(0).getLong(0) == 3)
      assert(view.spark.sql(s"SELECT count(*) FROM ${view.table} VERSION AS OF ${snaps(1)}").collect()(0).getLong(0) == 5)
    }

  val timeTravelTimestampAsOf: TableTest[CoreTable.type] =
    coreTwoSnapshots.check("timeTravel.timestampAsOf") { view =>
      val ts0 = view.spark.sql(s"SELECT committed_at FROM ${view.table}.snapshots ORDER BY committed_at LIMIT 1").collect()(0).getTimestamp(0)
      assert(view.spark.sql(s"SELECT count(*) FROM ${view.table} TIMESTAMP AS OF '$ts0'").collect()(0).getLong(0) == 3)
    }

  val timeTravelMetadataTables: TableTest[CoreTable.type] =
    coreTwoSnapshots.check("timeTravel.metadataTables") { view =>
      def count(meta: String): Long = view.spark.sql(s"SELECT count(*) FROM ${view.table}.$meta").collect()(0).getLong(0)
      assert(count("snapshots") == 2)
      assert(count("history") == 2)
      assert(count("files") >= 1 && count("manifests") >= 1)
    }

  val timeTravelIncrementalRead: TableTest[CoreTable.type] =
    coreTwoSnapshots.check("timeTravel.incrementalRead") { view =>
      val snaps = snapshotIds(view.spark, view.table)
      val added = view.spark.read.format("iceberg")
        .option("start-snapshot-id", snaps(0)).option("end-snapshot-id", snaps(1))
        .load(view.table).count()
      assert(added == 2) // only the rows added between snapshot A and B
    }

  val timeTravel: List[(String, TableTest[CoreTable.type])] = List(
    "timeTravel.versionAsOf"     -> timeTravelVersionAsOf,
    "timeTravel.timestampAsOf"   -> timeTravelTimestampAsOf,
    "timeTravel.metadataTables"  -> timeTravelMetadataTables,
    "timeTravel.incrementalRead" -> timeTravelIncrementalRead
  )

  // Restore/rollback via stored procedures (gated: OpenHouse may not expose CALL procedures).
  private def catalogRelative(table: String): String = table.stripPrefix("openhouse.")

  val restoreRollbackToSnapshot: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("restore.rollbackToSnapshot") { (spark, table) =>
      val first = snapshotIds(spark, table).head
      spark.sql(s"CALL openhouse.system.rollback_to_snapshot('${catalogRelative(table)}', $first)")
    } { view =>
      assert(view.after.size == 3) // rolled back to the 3-row snapshot
    }

  val restoreSetCurrentSnapshot: TableTest[CoreTable.type] =
    coreTwoSnapshots.step("restore.setCurrentSnapshot") { (spark, table) =>
      val first = snapshotIds(spark, table).head
      spark.sql(s"CALL openhouse.system.set_current_snapshot('${catalogRelative(table)}', $first)")
    } { view =>
      assert(view.after.size == 3)
    }

  val restoreRollback: List[(String, TableTest[CoreTable.type])] = List(
    "restore.rollbackToSnapshot"  -> restoreRollbackToSnapshot,
    "restore.setCurrentSnapshot"  -> restoreSetCurrentSnapshot
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
}

/** Assembles the run: every operation x every layout, plus create.schema per layout. */
object Plan {
  final case class Case(id: String, run: Ctx => Unit)

  // Known PRODUCT bugs: any case whose id contains the key is reported SKIP (bug: reason) instead
  // of failing the suite, and is tracked in BUGS.md. This is how we "tag a failing test and filter
  // it": a genuine bug is tagged here, deferred for follow-up, and never plowed past silently.
  val knownBugs: List[(String, String)] = List(
    "insert.explicitColumns" ->
      "partial-column INSERT rejected (CANNOT_FIND_DATA for omitted column); vanilla Iceberg null-fills optional columns — see BUGS.md",
    "nested.deleteByNestedField" ->
      "DELETE WHERE <nested struct field> crashes with an internal optimizer NPE (SELECT/UPDATE on the same field work) — see BUGS.md",
    "ddl.renameColumn" ->
      "RENAME COLUMN is a silent no-op — neither errors nor renames (the client drops the change before the server validates it); a silent failure worse than a clean rejection — see BUGS.md"
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
    val timeTravel      = Scenarios.timeTravel.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val restoreRollback = Scenarios.restoreRollback.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val negatives       = Scenarios.negatives.map { case (name, t) => Case(s"$name @ parquet", t.run) }
    val ddlNegatives    = Scenarios.ddlNegatives.map { case (name, t) => Case(s"$name @ parquet", t.run) }

    val creates = Scenarios.layouts.map { layout =>
      Case(s"create.schema @ ${layout.label}", Scenarios.createSchema(layout).run)
    }

    // DDL Phase 12: schema-evolution behaviors crossed with every layout.
    val ddlSchema = for {
      layout     <- Scenarios.layouts
      (name, op) <- Scenarios.ddlSchemaOperations
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeed(layout, 3).andThen(op).run)

    dml ++ partitioned ++ mor ++ morVerify ++ cowVerify ++ nested ++ types ++ partitionTransforms ++
      partitionEvolution ++ timeTravel ++ restoreRollback ++ negatives ++ creates ++ ddlSchema ++ ddlNegatives
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

    // Known-bug cases are tagged (Plan.knownBugs) and reported SKIP rather than run — deferred,
    // not passing. Everything else executes.
    val results = cases.map { c =>
      Plan.bugReason(c.id) match {
        case Some(reason) => (c.id, (Outcome.Skipped(reason): Outcome, 0))
        case None         => (c.id, Runner.execute(c, ctx))
      }
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
    // A run that validated nothing (0 cases, or everything skipped) is NOT success.
    System.exit(if (failed == 0 && passed > 0) 0 else 1)
  }
}
