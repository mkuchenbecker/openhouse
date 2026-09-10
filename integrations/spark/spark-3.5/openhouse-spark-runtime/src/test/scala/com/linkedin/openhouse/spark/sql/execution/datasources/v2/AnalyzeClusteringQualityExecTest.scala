package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

import com.linkedin.openhouse.spark.sql.execution.datasources.v2.AnalyzeClusteringQualityExec._

class AnalyzeClusteringQualityExecTest {

  @Test
  def metricExprAccessesPerFileMetricQuotingKey(): Unit = {
    assertEquals("readable_metrics.ts.lower_bound", metricExpr("ts", "lower_bound"))
    assertEquals("readable_metrics.`my-col`.upper_bound", metricExpr("my-col", "upper_bound"))
  }
}
