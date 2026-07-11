package harness

import org.apache.spark.sql.{Row, SparkSession}
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
  def columnDefinitions: String =
    tableColumns.map(column => s"${column.columnName} ${column.sqlType}").mkString(", ")
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
  private def withTable(ctx: Ctx)(use: String => Unit): Unit = {
    val table = s"${ctx.namespace}.t_${TableTest.counter.incrementAndGet()}"
    ctx.spark.sql(s"DROP TABLE IF EXISTS $table") // ensure absent
    try use(table) finally ctx.spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  // Rows selected by the schema's columns, ordered by them for deterministic comparison.
  private def currentRows(spark: SparkSession, table: String): Seq[Row] = {
    val columns = schema.columnNames.mkString(", ")
    spark.sql(s"SELECT $columns FROM $table ORDER BY $columns").collect().toSeq
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
  // Partitioning references a real CoreTable column (identity on the date-partition string, the
  // common real-world pattern); unpartitioned is the empty spec. Format is a schema-independent
  // TBLPROPERTY, so it is varied blindly.
  final case class Layout(label: String, partitioning: CoreTable.type => String, tableProperties: Map[String, String])

  val layouts: List[Layout] =
    for {
      format <- List("parquet", "orc", "avro")
      (partitionLabel, partitioning) <- List[(String, CoreTable.type => String)](
        "unpartitioned" -> (_ => ""),
        "partitioned"   -> (core => core.datePartition.columnName))
    } yield Layout(s"$partitionLabel/$format", partitioning, Map("write.format.default" -> format))

  // Preparation: create under `layout` and seed `numberOfRows` deterministic rows. Interchangeable
  // with RTAS / drop+undrop preparations later — same resulting state.
  def createAndSeed(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    TableTest(Core).create(layout.partitioning, layout.tableProperties)().insert(numberOfRows)()

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

  // ── create (a preparation-only test: create under the layout, assert schema + emptiness) ─
  def createSchema(layout: Layout): TableTest[CoreTable.type] =
    TableTest(Core).create(layout.partitioning, layout.tableProperties) { view =>
      val actual = view.spark.table(view.table).schema.fields.toList.map(field => (field.name, field.dataType.simpleString))
      val expected = Core.tableColumns.toList.map(column => (column.columnName, column.sqlType))
      assert(actual == expected)
      assert(view.after.isEmpty)
    }

  /** The operations crossed with every layout, each a headless segment, in report order. */
  val operations: List[(String, TableTest[CoreTable.type])] = List(
    "read.projection"                -> readProjection,
    "read.filter"                    -> readFilter,
    "format.materialization"         -> formatMaterialization,
    "delete.byPredicate"             -> deleteByPredicate,
    "delete.whereFalse.noSnapshot"   -> deleteWhereFalseKeepsSnapshot,
    "delete.truncate"                -> truncate,
    "delete.atSnapshot.rejected"     -> deleteAtSnapshotRejected,
    "update.byPredicate"             -> updateByPredicate,
    "update.withoutCondition"        -> updateWithoutCondition,
    "update.noMatch"                 -> updateNoMatch,
    "merge.insertNotMatched"         -> mergeInsertNotMatched,
    "merge.updateMatched"            -> mergeUpdateMatched,
    "merge.deleteMatched"            -> mergeDeleteMatched,
    "merge.upsert"                   -> mergeUpsert,
    "merge.deleteNotMatchedBySource" -> mergeDeleteNotMatchedBySource,
    "insert.into"                    -> insertInto,
    "append.dataFrame"               -> appendDataFrame,
    "insert.overwrite"               -> insertOverwrite,
    "overwrite.dataFrame"            -> overwriteDataFrame
  )
}

/** Assembles the run: every operation x every layout, plus create.schema per layout. */
object Plan {
  final case class Case(id: String, run: Ctx => Unit)

  def cases: List[Case] = {
    val dml = for {
      layout        <- Scenarios.layouts
      (name, op)    <- Scenarios.operations
    } yield Case(s"$name @ ${layout.label}", Scenarios.createAndSeed(layout, 3).andThen(op).run)

    val creates = Scenarios.layouts.map { layout =>
      Case(s"create.schema @ ${layout.label}", Scenarios.createSchema(layout).run)
    }

    dml ++ creates
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
