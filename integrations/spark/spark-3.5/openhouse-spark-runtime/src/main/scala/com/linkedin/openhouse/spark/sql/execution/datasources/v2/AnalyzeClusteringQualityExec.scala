package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import scala.collection.JavaConverters._
import scala.collection.mutable

import com.linkedin.openhouse.spark.sql.catalyst.plans.logical.OptimizeTable
import com.linkedin.openhouse.spark.sql.catalyst.plans.logical.OptimizeTable._
import org.apache.iceberg.spark.source.SparkTable
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, GenericInternalRow}
import org.apache.spark.sql.catalyst.util.quoteIfNeeded
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.datasources.v2.LeafV2CommandExec
import org.apache.spark.unsafe.types.UTF8String

/**
 * Read-only probe for `ANALYZE TABLE t COMPUTE CLUSTERING QUALITY`: reports how well a table is
 * clustered to its current key selection from what OPTIMIZE leaves in table metadata. Every file
 * is classified by two independent signals, the layout stamp it carries (`sort_order_id`) and its
 * data sequence number against the watermark (`optimize.cluster.hwm-seq`):
 *
 *  - '''covered''': stamped with the current layout;
 *  - '''new''': not covered and above the watermark, i.e. arrived since the last run;
 *  - '''stale''': stamped with an older layout, waiting for `OPTIMIZE ... FULL`;
 *  - '''damaged''': not covered, unstamped or foreign-stamped, at or below the watermark: a file
 *    something other than OPTIMIZE rewrote after it had been clustered.
 *
 * Depth per clustering key (how many files interleave at a point of the key range) comes from
 * manifest metrics. All metrics are computed with distributed SQL over the `entries` metadata
 * table, so the command is safe on tables with very large file counts. No commit and no property
 * write.
 */
case class AnalyzeClusteringQualityExec(
  output: Seq[Attribute],
  spark: SparkSession,
  catalog: TableCatalog,
  ident: Identifier) extends LeafV2CommandExec {

  import AnalyzeClusteringQualityExec._

  private def outRow(metric: String, dimension: String, value: String): InternalRow =
    new GenericInternalRow(Array[Any](
      UTF8String.fromString(metric),
      if (dimension == null) null else UTF8String.fromString(dimension),
      UTF8String.fromString(value)))

  override protected def run(): Seq[InternalRow] = {
    val props = catalog.loadTable(ident) match {
      case iceberg: SparkTable
        if iceberg.table().properties().containsKey(MaintenanceProperties.TABLE_ID_PROP) =>
        iceberg.table().properties().asScala.toMap
      case table =>
        throw new UnsupportedOperationException(
          s"Cannot compute clustering quality for non-Openhouse table: $table")
    }

    val qualifiedTableName =
      (Seq(catalog.name()) ++ ident.namespace() :+ ident.name()).map(quoteIfNeeded).mkString(".")
    val out = mutable.ArrayBuffer[InternalRow]()

    val config = OptimizeTable.parseClusterConfig(props)
    if (config.keys.isEmpty) {
      out += outRow("clustering_configured", null, "false")
      return out.toSeq
    }
    out += outRow("clustering_configured", null, "true")

    val layout = config.layout
    out += outRow("layout_id", null, layout.id.toString)
    out += outRow("keys", null, layout.keys.mkString(","))
    out += outRow("sort_mode", null, layout.mode)
    out += outRow("hwm_seq", null, config.hwmSeq.toString)

    // Live data files of the current snapshot, classified by stamp and sequence in one aggregate.
    val entries = s"$qualifiedTableName.entries"
    val live = s"status < 2 AND data_file.content = 0"
    val covered = s"data_file.sort_order_id = ${layout.id}"
    val a = spark.sql(
      s"""SELECT count(*) AS files_total,
         |  coalesce(sum(bytes), 0) AS bytes_total,
         |  coalesce(sum(CASE WHEN cov THEN 1 ELSE 0 END), 0) AS files_covered,
         |  coalesce(sum(CASE WHEN cov THEN bytes ELSE 0 END), 0) AS bytes_covered,
         |  coalesce(sum(CASE WHEN NOT cov AND seq > ${config.hwmSeq} THEN 1 ELSE 0 END), 0)
         |    AS files_new,
         |  coalesce(sum(CASE WHEN NOT cov AND seq > ${config.hwmSeq} THEN bytes ELSE 0 END), 0)
         |    AS bytes_new,
         |  coalesce(sum(CASE WHEN stale THEN 1 ELSE 0 END), 0) AS files_stale,
         |  coalesce(sum(CASE WHEN stale THEN bytes ELSE 0 END), 0) AS bytes_stale,
         |  coalesce(sum(CASE WHEN NOT cov AND NOT stale AND seq <= ${config.hwmSeq}
         |    THEN 1 ELSE 0 END), 0) AS files_damaged,
         |  coalesce(sum(CASE WHEN NOT cov AND NOT stale AND seq <= ${config.hwmSeq}
         |    THEN bytes ELSE 0 END), 0) AS bytes_damaged,
         |  min(CASE WHEN NOT cov THEN seq END) AS oldest_uncovered_seq,
         |  min(CASE WHEN NOT cov THEN snapshot_id END) AS oldest_uncovered_snapshot
         |FROM (SELECT data_file.file_size_in_bytes AS bytes,
         |        sequence_number AS seq,
         |        snapshot_id,
         |        ($covered) AS cov,
         |        (data_file.sort_order_id IS NOT NULL
         |          AND data_file.sort_order_id >= ${OptimizeTable.MIN_LAYOUT_ID}
         |          AND data_file.sort_order_id <> ${layout.id}) AS stale
         |      FROM $entries WHERE $live)""".stripMargin).collect().head
    val filesTotal = a.getLong(0)
    val bytesTotal = a.getLong(1)
    out += outRow("files_total", null, filesTotal.toString)
    out += outRow("bytes_total", null, bytesTotal.toString)
    out += outRow("files_covered", null, a.getLong(2).toString)
    out += outRow("bytes_covered", null, a.getLong(3).toString)
    out += outRow("coverage_files_pct", null, pct(a.getLong(2), filesTotal))
    out += outRow("coverage_bytes_pct", null, pct(a.getLong(3), bytesTotal))
    out += outRow("files_new", null, a.getLong(4).toString)
    out += outRow("bytes_new", null, a.getLong(5).toString)
    out += outRow("files_stale", null, a.getLong(6).toString)
    out += outRow("bytes_stale", null, a.getLong(7).toString)
    out += outRow("files_damaged", null, a.getLong(8).toString)
    out += outRow("bytes_damaged", null, a.getLong(9).toString)

    // Depth per clustering dimension: global and over the covered files only (the SLA input).
    layout.keys.foreach { k =>
      val kLo = metricExpr(k, "lower_bound")
      val kHi = metricExpr(k, "upper_bound")
      val g = depthStats(spark, entries, live, kLo, kHi, None)
      val c = depthStats(spark, entries, live, kLo, kHi, Some(covered))
      out += outRow("depth_avg", k, fmt(g.avg))
      out += outRow("depth_p90", k, fmt(g.p90))
      out += outRow("depth_max", k, g.max.toString)
      out += outRow("depth_avg_covered", k, fmt(c.avg))
      out += outRow("depth_p90_covered", k, fmt(c.p90))
    }

    // Tail: how old the oldest not-yet-clustered file is.
    val oldestSeq = if (a.isNullAt(10)) None else Some(a.getLong(10))
    val oldestSnapshot = if (a.isNullAt(11)) None else Some(a.getLong(11))
    out += outRow("oldest_uncovered_seq", null, oldestSeq.map(_.toString).getOrElse("none"))
    out += outRow("unclustered_tail_hours", null,
      tailHours(spark, qualifiedTableName, oldestSnapshot))

    out += outRow("epochs", null, props.getOrElse(EPOCHS_PROP, "[]"))
    out.toSeq
  }

  override def simpleString(maxFields: Int): String = {
    s"AnalyzeClusteringQualityExec: ${catalog} ${ident}"
  }
}

object AnalyzeClusteringQualityExec {

  final case class DepthStats(avg: Double, p90: Double, max: Long)

  /** SQL access to a per-file column metric, e.g. `readable_metrics.`ts`.lower_bound`. */
  def metricExpr(key: String, field: String): String =
    s"readable_metrics.${quoteIfNeeded(key)}.$field"

  private def pct(part: Long, total: Long): String =
    if (total == 0) "0.0" else fmt(100.0 * part / total)

  private def fmt(d: Double): String = f"$d%.2f"

  /**
   * Stabbing-depth stats over the `[lower, upper]` intervals of one dimension, optionally
   * restricted to files matching a filter. Computed with a windowed running-sum sweep in SQL
   * (`+1` at each lower bound, `-1` past each upper, sampled at start events) so nothing is
   * collected to the driver. Depth `1` means no overlap (perfectly clustered); higher means more
   * interleaving.
   */
  private def depthStats(
      spark: SparkSession,
      entries: String,
      live: String,
      loExpr: String,
      hiExpr: String,
      filter: Option[String]): DepthStats = {
    val extra = filter.map(f => s"AND ($f)").getOrElse("")
    val where = s"$live AND $loExpr IS NOT NULL AND $hiExpr IS NOT NULL $extra"
    val q =
      s"""WITH ev AS (
         |  SELECT $loExpr AS pt, 1 AS delta FROM $entries WHERE $where
         |  UNION ALL
         |  SELECT $hiExpr AS pt, -1 AS delta FROM $entries WHERE $where
         |),
         |running AS (SELECT delta, sum(delta) OVER (ORDER BY pt, delta DESC) AS depth FROM ev)
         |SELECT coalesce(avg(CASE WHEN delta = 1 THEN CAST(depth AS DOUBLE) END), 0.0),
         |  coalesce(percentile_approx(
         |    CASE WHEN delta = 1 THEN CAST(depth AS DOUBLE) END, 0.9), 0.0),
         |  coalesce(max(depth), 0L)
         |FROM running""".stripMargin
    val r = spark.sql(q).collect().head
    DepthStats(r.getDouble(0), r.getDouble(1), r.getLong(2))
  }

  /**
   * Age in hours of the oldest not-yet-clustered file, from the commit time of the snapshot that
   * added it. `0.0` when every file is clustered. When that snapshot has been expired the exact
   * age is unknown but is at least the age of the oldest live snapshot, which is reported as
   * `>=<hours>` so an SLA breach is never hidden.
   */
  private def tailHours(
      spark: SparkSession, qualifiedTableName: String, snapshotId: Option[Long]): String = {
    snapshotId match {
      case None => "0.0"
      case Some(id) =>
        val exact = spark.sql(
          s"""SELECT CAST((unix_timestamp(current_timestamp()) -
             |  unix_timestamp(committed_at)) / 3600.0 AS DOUBLE)
             |FROM $qualifiedTableName.snapshots WHERE snapshot_id = $id""".stripMargin).collect()
        if (exact.nonEmpty && !exact.head.isNullAt(0)) return fmt(exact.head.getDouble(0))

        val floor = spark.sql(
          s"""SELECT CAST((unix_timestamp(current_timestamp()) -
             |  unix_timestamp(min(committed_at))) / 3600.0 AS DOUBLE)
             |FROM $qualifiedTableName.snapshots""".stripMargin).collect()
        if (floor.isEmpty || floor.head.isNullAt(0)) {
          "unknown"
        } else {
          ">=" + fmt(floor.head.getDouble(0))
        }
    }
  }
}
