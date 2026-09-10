package com.linkedin.openhouse.spark.sql.catalyst.plans.logical

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32

import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, DefaultScalaModule}

import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.plans.logical.LeafCommand
import org.apache.spark.sql.types.StringType

/**
 * The logical plan of the OPTIMIZE command:
 * {{{
 *   OPTIMIZE multi_part_name [FULL] [REWRITE MANIFESTS]
 * }}}
 *
 * Behavior depends on whether clustering keys are configured via the `optimize.cluster.*` table
 * properties:
 *
 *  - '''No `optimize.cluster.keys`''': plain bin-pack compaction. `FULL` has no effect.
 *  - '''Clustering configured''': a sort / z-order rewrite of the configured keys. Every file the
 *    rewrite writes is stamped with the layout that produced it (its Iceberg `sort_order_id` and a
 *    footer entry), and its data sequence number is pinned to the newest input it replaced.
 *    Incremental (the default) rewrites files newer than the sequence watermark that do not carry
 *    the current layout; `FULL` rewrites every file that does not carry the current layout.
 *
 * `REWRITE MANIFESTS` is independent and runs after the data rewrite. Snapshot expiration is not
 * part of OPTIMIZE; that is the VACUUM command's job.
 */
case class OptimizeTable(tableName: Seq[String], full: Boolean, rewriteManifests: Boolean)
  extends LeafCommand {

  override lazy val output: Seq[Attribute] = Seq(
    AttributeReference("metric", StringType, nullable = false)(),
    AttributeReference("value", StringType, nullable = false)())

  override def simpleString(maxFields: Int): String = {
    s"OptimizeTable: ${tableName} full=${full} rewriteManifests=${rewriteManifests}"
  }
}

object OptimizeTable {

  // User configuration.
  val KEYS_PROP = "optimize.cluster.keys"
  val SORT_MODE_PROP = "optimize.cluster.sort-mode"
  val MAX_COMMITS_PROP = "optimize.cluster.max-commits"

  // State OPTIMIZE writes back.
  val LAYOUT_ID_PROP = "optimize.cluster.layout-id"
  val LAYOUT_PROP_PREFIX = "optimize.cluster.layout."
  val HWM_SEQ_PROP = "optimize.cluster.hwm-seq"
  val EPOCHS_PROP = "optimize.cluster.epochs"

  // State written by the snapshot-id watermark design this replaces; read once for migration and
  // then removed.
  val LEGACY_HWM_SNAPSHOT_PROP = "optimize.cluster.hwm-snapshot-id"
  val LEGACY_STATE_PROP = "optimize.cluster.state"
  val LEGACY_CONFIG_ID_PROP = "optimize.cluster.config-id"
  val LEGACY_MIN_SNAPSHOT_AGE_PROP = "optimize.cluster.min-snapshot-age-minutes"
  val LEGACY_PROPS: Seq[String] = Seq(
    LEGACY_HWM_SNAPSHOT_PROP,
    LEGACY_STATE_PROP,
    LEGACY_CONFIG_ID_PROP,
    LEGACY_MIN_SNAPSHOT_AGE_PROP)

  /** Footer metadata keys stamped into every file a clustering rewrite writes. */
  val FILE_LAYOUT_ID_KEY = "openhouse.cluster.layout-id"
  val FILE_LAYOUT_KEY = "openhouse.cluster.layout"

  val DEFAULT_SORT_MODE = "zorder"
  val DEFAULT_MAX_COMMITS = 10L

  val SORT_MODES: Set[String] = Set("zorder", "sort")

  /**
   * Layout ids start above this so they never collide with the ids of a table's registered Iceberg
   * sort orders, which are allocated from 1 upward.
   */
  val MIN_LAYOUT_ID = 1000

  /**
   * Clustering configuration resolved from the `optimize.cluster.*` table properties, with every
   * default applied and every value parsed to its type.
   */
  case class ClusterConfig(
      keys: Seq[String],
      sortMode: String,
      maxCommits: Long,
      hwmSeq: Long,
      epochs: Seq[Epoch]) {

    /** The layout this configuration produces. */
    def layout: Layout = Layout(layoutId(keys, sortMode), keys, sortMode)
  }

  /** A key selection and sort mode, identified by a stable id. */
  case class Layout(id: Int, keys: Seq[String], mode: String)

  /**
   * One run's worth of consumed data sequence numbers, `(lowerSeq, upperSeq]`, under a layout. A
   * FULL run starts at 0. Persisted in `optimize.cluster.epochs` as durable history of which
   * sequence ranges were clustered under which key selection; the per-file stamps remain the
   * source of truth for what is clustered now.
   */
  case class Epoch(layout: Int, lowerSeq: Long, upperSeq: Long)

  /** Parse the `optimize.cluster.*` table properties into a typed [[ClusterConfig]]. */
  def parseClusterConfig(props: Map[String, String]): ClusterConfig = {
    val sortMode =
      props.getOrElse(SORT_MODE_PROP, DEFAULT_SORT_MODE).trim.toLowerCase(Locale.ROOT)
    if (!SORT_MODES.contains(sortMode)) {
      throw new IllegalArgumentException(
        s"Unsupported '$SORT_MODE_PROP' value '$sortMode'; expected one of " +
          s"${SORT_MODES.toSeq.sorted.mkString(", ")}")
    }
    ClusterConfig(
      keys = parseKeys(props.getOrElse(KEYS_PROP, "")),
      sortMode = sortMode,
      maxCommits = props.get(MAX_COMMITS_PROP).map(_.trim.toLong).getOrElse(DEFAULT_MAX_COMMITS),
      hwmSeq = props.get(HWM_SEQ_PROP).map(_.trim.toLong).getOrElse(0L),
      epochs = parseEpochs(props.getOrElse(EPOCHS_PROP, "")))
  }

  def parseKeys(keys: String): Seq[String] =
    keys.split(",").map(_.trim).filter(_.nonEmpty).toSeq

  /**
   * Stable identity of a key selection and mode: only a keys/mode change produces a new id. A
   * positive int at or above [[MIN_LAYOUT_ID]], so it can be recorded as a data file's
   * `sort_order_id` without colliding with a registered sort order.
   */
  def layoutId(keys: Seq[String], sortMode: String): Int = {
    val normalized = keys.map(_.trim).mkString(",") + "|" + sortMode.trim.toLowerCase(Locale.ROOT)
    val crc = new CRC32()
    crc.update(normalized.getBytes(StandardCharsets.UTF_8))
    MIN_LAYOUT_ID + (crc.getValue % (Int.MaxValue - MIN_LAYOUT_ID)).toInt
  }

  val jsonMapper = {
    val mapper = new ObjectMapper() with ClassTagExtensions
    mapper.registerModule(DefaultScalaModule)
    mapper
  }

  def layoutToJson(layout: Layout): String = jsonMapper.writeValueAsString(layout)

  def layoutFromJson(json: String): Layout = jsonMapper.readValue[Layout](json)

  /**
   * Parse epoch history. Empty / absent input is no history (a fresh table). Non-empty but
   * unparseable input is a corrupted property; it fails loudly naming the property and how to
   * clear it rather than being silently read as "no history".
   */
  def parseEpochs(json: String): Seq[Epoch] = {
    if (json == null || json.trim.isEmpty) return Seq.empty
    try {
      jsonMapper.readValue[Seq[Epoch]](json)
    } catch {
      case NonFatal(e) =>
        throw new IllegalStateException(
          s"Malformed clustering history in table property '$EPOCHS_PROP'. Clear it and let " +
            s"the next OPTIMIZE rebuild it: ALTER TABLE <table> UNSET TBLPROPERTIES " +
            s"('$EPOCHS_PROP'). Value was: $json", e)
    }
  }

  def epochsToJson(epochs: Seq[Epoch]): String = jsonMapper.writeValueAsString(epochs)

  /**
   * Fold a completed run into the epoch history. An incremental run under a layout that already
   * has an epoch extends that epoch's upper bound; a run under a new layout appends an epoch whose
   * lower bound is the watermark it started from; FULL replaces the layout's epoch with one that
   * starts at 0. Epochs of other layouts are retained as history.
   */
  def advanceEpochs(
      existing: Seq[Epoch],
      layout: Int,
      lowerSeq: Long,
      upperSeq: Long,
      full: Boolean): Seq[Epoch] = {
    val others = existing.filterNot(_.layout == layout)
    (full, existing.find(_.layout == layout)) match {
      case (true, _) => others :+ Epoch(layout, 0L, upperSeq)
      case (false, Some(cur)) => others :+ cur.copy(upperSeq = math.max(cur.upperSeq, upperSeq))
      case (false, None) => existing :+ Epoch(layout, lowerSeq, upperSeq)
    }
  }
}
