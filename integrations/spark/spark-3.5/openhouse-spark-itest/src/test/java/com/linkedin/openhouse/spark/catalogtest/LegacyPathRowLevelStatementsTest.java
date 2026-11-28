package com.linkedin.openhouse.spark.catalogtest;

/**
 * The row-level statements on format v3 tables on the LEGACY catalog path ({@link
 * RowLevelStatementsTest}).
 */
public class LegacyPathRowLevelStatementsTest extends RowLevelStatementsTest {
  @Override
  protected String catalogPath() {
    return LEGACY;
  }
}
