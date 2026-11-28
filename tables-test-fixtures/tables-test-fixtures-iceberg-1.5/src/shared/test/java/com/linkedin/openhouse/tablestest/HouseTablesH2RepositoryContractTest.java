package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import java.util.Collections;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The House Tables stand-in of this module's application ({@link SpringH2TestApplication}) against
 * the repository contract the real store passes.
 */
public class HouseTablesH2RepositoryContractTest extends HouseTableRepositoryContract {

  private static ConfigurableApplicationContext context;

  @BeforeAll
  public static void start() {
    try {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.register();
    } catch (Error e) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    }
    SpringApplication application = new SpringApplication(SpringH2TestApplication.class);
    application.setDefaultProperties(Collections.singletonMap("server.port", "0"));
    context = application.run();
  }

  @AfterAll
  public static void stop() {
    if (context != null) {
      context.close();
    }
  }

  @Override
  protected HouseTableRepository repository() {
    return context.getBean(HouseTablesH2Repository.class);
  }
}
