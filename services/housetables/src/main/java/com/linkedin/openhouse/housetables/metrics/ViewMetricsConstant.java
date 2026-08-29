package com.linkedin.openhouse.housetables.metrics;

/**
 * Metric names for the House Tables view operations, owned by the service that emits them: renaming
 * a view operation is a housetables change, not a release of the shared {@code services:common}
 * module. The table-operation names still live in {@code
 * com.linkedin.openhouse.common.metrics.MetricsConstant} with the rest of the historical
 * vocabulary; moving those wholesale is tracked separately.
 */
public final class ViewMetricsConstant {

  private ViewMetricsConstant() {}

  public static final String HTS_LIST_VIEWS_REQUEST = "hts_list_views_request";
  public static final String HTS_LIST_VIEWS_TIME = "hts_list_views_time";
  public static final String HTS_PAGE_VIEWS_REQUEST = "hts_page_views_request";
  public static final String HTS_PAGE_VIEWS_TIME = "hts_page_views_time";
}
