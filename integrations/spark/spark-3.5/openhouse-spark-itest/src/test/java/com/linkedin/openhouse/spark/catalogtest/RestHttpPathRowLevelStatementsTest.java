package com.linkedin.openhouse.spark.catalogtest;

/**
 * The row-level statements on format v3 tables on the REST_HTTP catalog path ({@link
 * RowLevelStatementsTest}).
 */
public class RestHttpPathRowLevelStatementsTest extends RowLevelStatementsTest {
  @Override
  protected String catalogPath() {
    return REST_HTTP;
  }
}
