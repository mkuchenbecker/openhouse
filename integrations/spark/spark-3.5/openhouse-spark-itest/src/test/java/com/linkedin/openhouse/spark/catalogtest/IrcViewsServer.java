package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.housetables.client.model.ToggleStatus;
import com.linkedin.openhouse.tables.exception.ViewExceptionHandler;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.toggle.model.TableToggleStatus;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.rest.RESTCatalog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A second embedded tables service for the IRC view itests: views are off by default, so this one
 * opens the views feature gate for the databases a test names, as a {@code views} toggle rule does,
 * and authorizes only {@link #OWNER} (with {@link IcebergRestCatalogReadTest}'s authorization
 * fixture), so a denied principal can be shown too. One per test class, closed with it.
 */
final class IrcViewsServer implements AutoCloseable {
  static final String OWNER = "irc_owner";
  static final String STRANGER = "irc_stranger";

  private final ConfigurableApplicationContext context;

  IrcViewsServer(String... viewDatabases) {
    org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    context =
        new SpringApplication(
                SpringH2TestApplication.class,
                ViewExceptionHandler.class,
                IcebergRestCatalogReadTest.AuthorizationTestConfig.class)
            .run(
                "--server.port=0",
                "--cluster.security.token.interceptor.classname="
                    + DummyTokenInterceptor.class.getName());
    for (String db : viewDatabases) {
      context
          .getBean(ToggleStatusesRepository.class)
          .save(
              TableToggleStatus.builder()
                  .databaseId(db)
                  .tableId(ViewsFeatureGate.GATE_PROBE_TABLE_ID)
                  .featureId("views")
                  .toggleStatusEnum(ToggleStatus.StatusEnum.ACTIVE)
                  .build());
    }
  }

  String uri() {
    return "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
  }

  static String token(String principal) {
    try {
      return new DummyTokenInterceptor.DummySecurityJWT(principal).buildNoopJWT();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** An unmodified Iceberg {@link RESTCatalog}, loaded as an engine loads one. */
  RESTCatalog rest(String principal, Configuration conf) {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", uri() + "/iceberg");
    properties.put("token", token(principal));
    return (RESTCatalog)
        CatalogUtil.loadCatalog(RESTCatalog.class.getName(), "openhouse", properties, conf);
  }

  /** The OpenHouse Java client with its view support switched on. */
  Catalog openhouse(String principal, Configuration conf) {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", uri());
    properties.put("cluster", "local-cluster");
    properties.put("auth-token", token(principal));
    properties.put("iceberg-views-enabled", "true");
    return CatalogUtil.loadCatalog(
        "com.linkedin.openhouse.javaclient.OpenHouseCatalog", "openhouse", properties, conf);
  }

  @Override
  public void close() {
    context.close();
  }
}
