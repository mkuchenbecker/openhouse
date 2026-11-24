package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A second embedded tables service with the cluster switch {@code
 * cluster.iceberg.format-v3.enabled} on, so that a test can create format v3 tables. The service
 * every other apps test runs against keeps the switch off, as a cluster does by default. One per
 * test class, closed with it. The same as the Spark 3.5 itest's {@code FormatV3Server}.
 */
final class FormatV3TablesServer implements AutoCloseable {
  private final ConfigurableApplicationContext context;

  FormatV3TablesServer() {
    org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    context =
        new SpringApplication(SpringH2TestApplication.class)
            .run(
                "--server.port=0",
                "--cluster.iceberg.format-v3.enabled=true",
                // Variant columns too (off by default), for the tests of tables that have one.
                "--cluster.iceberg.format-v3.variant.enabled=true");
  }

  String uri() {
    return "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
  }

  @Override
  public void close() {
    context.close();
  }
}
