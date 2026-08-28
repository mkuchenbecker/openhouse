package com.linkedin.openhouse.housetables.metrics;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ViewMetricsConstantTest {

  /**
   * External-contract pin. These literals are wire names that dashboards and alerts reference; they
   * survived the constants' move out of {@code services:common} unchanged, and this test is what
   * makes an accidental rename fail instead of quietly going dark downstream. Compare the literals,
   * never the constants against themselves.
   */
  @Test
  public void wireNamesAreAnExternalContract() {
    Assertions.assertEquals("hts_list_views_request", ViewMetricsConstant.HTS_LIST_VIEWS_REQUEST);
    Assertions.assertEquals("hts_list_views_time", ViewMetricsConstant.HTS_LIST_VIEWS_TIME);
    Assertions.assertEquals("hts_page_views_request", ViewMetricsConstant.HTS_PAGE_VIEWS_REQUEST);
    Assertions.assertEquals("hts_page_views_time", ViewMetricsConstant.HTS_PAGE_VIEWS_TIME);
  }
}
