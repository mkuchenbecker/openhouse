package com.linkedin.openhouse.spark.catalogtest;

/**
 * The row-level statements on format v3 tables on the REST_SHIM catalog path ({@link
 * RowLevelStatementsTest}).
 */
public class RestShimPathRowLevelStatementsTest extends RowLevelStatementsTest {
  @Override
  protected String catalogPath() {
    return REST_SHIM;
  }
}
