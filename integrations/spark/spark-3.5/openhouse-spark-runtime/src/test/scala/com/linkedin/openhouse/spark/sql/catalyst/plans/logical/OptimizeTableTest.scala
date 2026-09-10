package com.linkedin.openhouse.spark.sql.catalyst.plans.logical

import org.junit.jupiter.api.Assertions.{assertEquals, assertNotEquals, assertThrows, assertTrue}
import org.junit.jupiter.api.Test

import com.linkedin.openhouse.spark.sql.catalyst.plans.logical.OptimizeTable._

class OptimizeTableTest {

  @Test
  def parseClusterConfigResolvesDefaultsAndTypedValues(): Unit = {
    val empty = parseClusterConfig(Map.empty)
    assertTrue(empty.keys.isEmpty)
    assertEquals(DEFAULT_SORT_MODE, empty.sortMode)
    assertEquals(DEFAULT_MAX_COMMITS, empty.maxCommits)
    assertEquals(0L, empty.hwmSeq)
    assertTrue(empty.epochs.isEmpty)

    val cfg = parseClusterConfig(Map(
      KEYS_PROP -> " ts , uid ",
      SORT_MODE_PROP -> "SORT",
      MAX_COMMITS_PROP -> "3",
      HWM_SEQ_PROP -> "42",
      EPOCHS_PROP -> """[{"layout":1234,"lowerSeq":0,"upperSeq":42}]"""))
    assertEquals(Seq("ts", "uid"), cfg.keys)
    assertEquals("sort", cfg.sortMode)
    assertEquals(3L, cfg.maxCommits)
    assertEquals(42L, cfg.hwmSeq)
    assertEquals(Seq(Epoch(1234, 0L, 42L)), cfg.epochs)
    assertEquals(Layout(layoutId(Seq("ts", "uid"), "sort"), Seq("ts", "uid"), "sort"), cfg.layout)
  }

  @Test
  def parseClusterConfigRejectsUnknownSortMode(): Unit = {
    val e = assertThrows(classOf[IllegalArgumentException],
      () => parseClusterConfig(Map(KEYS_PROP -> "ts", SORT_MODE_PROP -> "hilbert")))
    assertTrue(e.getMessage.contains(SORT_MODE_PROP))
  }

  @Test
  def layoutIdStableAcrossWhitespaceChangesOnKeyOrMode(): Unit = {
    assertEquals(layoutId(Seq("ts", "uid"), "zorder"), layoutId(Seq(" ts ", " uid "), "ZORDER"))
    assertNotEquals(layoutId(Seq("ts", "uid"), "zorder"), layoutId(Seq("ts"), "zorder"))
    assertNotEquals(layoutId(Seq("ts"), "zorder"), layoutId(Seq("ts"), "sort"))
    assertNotEquals(layoutId(Seq("ts", "uid"), "zorder"), layoutId(Seq("uid", "ts"), "zorder"))
  }

  @Test
  def layoutIdNeverCollidesWithRegisteredSortOrderIds(): Unit = {
    Seq(Seq("a"), Seq("b"), Seq("a", "b"), Seq("ts"), Seq("uid", "ts")).foreach { keys =>
      Seq("zorder", "sort").foreach { mode =>
        assertTrue(layoutId(keys, mode) >= MIN_LAYOUT_ID, s"$keys/$mode")
      }
    }
  }

  @Test
  def layoutRoundTripsThroughJson(): Unit = {
    val layout = Layout(4321, Seq("ts", "uid"), "zorder")
    assertEquals(layout, layoutFromJson(layoutToJson(layout)))
  }

  @Test
  def parseEpochsRoundTrips(): Unit = {
    val epochs = Seq(Epoch(1, 0L, 20L), Epoch(2, 20L, 40L))
    assertEquals(epochs, parseEpochs(epochsToJson(epochs)))
  }

  @Test
  def parseEpochsEmptyOrNullIsNoHistory(): Unit = {
    assertEquals(Seq.empty[Epoch], parseEpochs(""))
    assertEquals(Seq.empty[Epoch], parseEpochs(null))
  }

  @Test
  def parseEpochsMalformedFailsLoudly(): Unit = {
    val e = assertThrows(classOf[IllegalStateException], () => parseEpochs("not json"))
    assertTrue(e.getMessage.contains(EPOCHS_PROP))
    assertTrue(e.getMessage.contains("UNSET TBLPROPERTIES"))
  }

  @Test
  def advanceEpochsFirstRunCreatesEpoch(): Unit = {
    assertEquals(
      Seq(Epoch(1, 5L, 10L)),
      advanceEpochs(Seq.empty, 1, 5L, 10L, full = false))
  }

  @Test
  def advanceEpochsSameLayoutExtendsUpperKeepsLower(): Unit = {
    val s0 = Seq(Epoch(1, 5L, 10L))
    assertEquals(Seq(Epoch(1, 5L, 20L)), advanceEpochs(s0, 1, 10L, 20L, full = false))
    // A run that consumed nothing new never moves the upper bound backwards.
    assertEquals(
      Seq(Epoch(1, 5L, 20L)),
      advanceEpochs(Seq(Epoch(1, 5L, 20L)), 1, 20L, 20L, full = false))
  }

  @Test
  def advanceEpochsFullStartsAtZero(): Unit = {
    val s0 = Seq(Epoch(1, 5L, 20L))
    assertEquals(Seq(Epoch(1, 0L, 30L)), advanceEpochs(s0, 1, 20L, 30L, full = true))
  }

  @Test
  def advanceEpochsLayoutChangeAppendsAndRetains(): Unit = {
    val s0 = Seq(Epoch(1, 0L, 20L))
    val s1 = advanceEpochs(s0, 2, 20L, 40L, full = false)
    assertEquals(Seq(Epoch(1, 0L, 20L), Epoch(2, 20L, 40L)), s1)
  }
}
