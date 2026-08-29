package com.linkedin.openhouse.tables.config;

import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.internal.catalog.OpenHouseInternalCatalog;
import com.linkedin.openhouse.internal.catalog.OpenHouseInternalViewCatalog;
import com.linkedin.openhouse.tables.services.OpenHouseViewsService;
import com.linkedin.openhouse.tables.services.ViewsDisabledService;
import com.linkedin.openhouse.tables.services.ViewsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the fallback {@link ViewsService}.
 *
 * <p>The gate is expressed as a fallback rather than as a pair of mutually exclusive conditions on
 * one property. A deployment that stores views contributes its own {@link ViewsService} bean;
 * anything else — views switched off, or a deployment that has not been given a view store — lands
 * here and reports that views are disabled. Written the other way, as
 * {@code @ConditionalOnProperty(havingValue = "false")} on this bean and {@code "true"} on the real
 * one, a misconfiguration that satisfied neither condition would fail context startup with a
 * missing-bean error instead of degrading to the safe posture.
 *
 * <p>The safe posture is the disabled one: a stock Iceberg client reads the resulting 404s as "this
 * catalog has no views" and Spark falls through to {@code loadTable}, which is coherent. There is
 * no equivalently coherent behavior for a catalog that claims view support and cannot store one.
 */
@ConditionalOnIcebergViewApi
@Configuration
public class ViewsServiceConfiguration {

  /**
   * The view store, when this deployment is configured to have one.
   *
   * <p>Declared before the fallback below, which is not incidental:
   * {@code @ConditionalOnMissingBean} is evaluated in declaration order within a configuration
   * class, so the fallback must come second to see this bean and stand down.
   *
   * <p>The view catalog is built here rather than component-scanned, and that placement carries the
   * Iceberg-version constraint: it extends a class Iceberg did not have before 1.4, and Spring
   * reads a component's supertypes while parsing the context. Constructing it inside a bean method
   * that this condition can switch off means an Iceberg-1.2 classpath — which the iceberg-1.2 test
   * fixture uses to serve Spark 3.1 — never loads the type at all, because the condition is
   * evaluated from annotation metadata before any of these signatures are resolved.
   *
   * @param catalog the internal catalog, whose table path the view catalog delegates to
   * @param storageSelector picks the storage a view's location is allocated from
   * @return a service that stores views
   */
  @Bean
  @ConditionalOnProperty(name = "cluster.tables.views.enabled", havingValue = "true")
  public ViewsService openHouseViewsService(
      OpenHouseInternalCatalog catalog, StorageSelector storageSelector) {
    return new OpenHouseViewsService(new OpenHouseInternalViewCatalog(catalog), storageSelector);
  }

  /** @return the disabled-views service, used unless a view store contributed a real one */
  @Bean
  @ConditionalOnMissingBean(ViewsService.class)
  public ViewsService viewsDisabledService() {
    return new ViewsDisabledService();
  }
}
