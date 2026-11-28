package com.linkedin.openhouse.tablestest;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The House Tables stand-in answers as a remote store does, whatever context calls it: each
 * application context has a store of its own, a read never comes from the caller's persistence
 * context, and no property can turn on the H2 result reuse that serves rows from before a commit.
 */
public class HouseTablesH2DatabaseTest {

  private static final String DB = "storedb";

  private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

  @AfterEach
  public void tearDown() {
    contexts.forEach(ConfigurableApplicationContext::close);
    contexts.clear();
  }

  private ConfigurableApplicationContext start(String... args) {
    try {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.register();
    } catch (Error e) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    }
    SpringApplication application = new SpringApplication(SpringH2TestApplication.class);
    application.setDefaultProperties(Collections.singletonMap("server.port", "0"));
    ConfigurableApplicationContext context = application.run(args);
    contexts.add(context);
    return context;
  }

  private static HouseTablePrimaryKey key(String tableId) {
    return HouseTablePrimaryKey.builder().databaseId(DB).tableId(tableId).build();
  }

  private static HouseTable row(String tableId, String version, String location) {
    return HouseTable.builder()
        .databaseId(DB)
        .tableId(tableId)
        .tableVersion(version)
        .tableLocation(location)
        .build();
  }

  /**
   * A request's persistence context (the tables service binds one per request) has read the row; a
   * commit through another context lands; the next read in the request must see it, as a second
   * call to the House Tables service would.
   */
  @Test
  public void aReadInsideAnOpenPersistenceContextSeesALaterCommit() throws Exception {
    ConfigurableApplicationContext context = start();
    HouseTableRepository repository = context.getBean(HouseTableRepository.class);
    repository.save(row("t", INITIAL_TABLE_VERSION, "/t/0.metadata.json"));
    TransactionTemplate request =
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    request.setReadOnly(true);

    List<String> seen =
        request.execute(
            status -> {
              List<String> locations = new ArrayList<>();
              locations.add(repository.findById(key("t")).get().getTableLocation());
              locations.add(repository.findEntityById(key("t")).get().getTableLocation());
              try {
                CompletableFuture.runAsync(
                        () -> repository.save(row("t", "/t/0.metadata.json", "/t/1.metadata.json")))
                    .get(30, TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              locations.add(repository.findById(key("t")).get().getTableLocation());
              locations.add(repository.findEntityById(key("t")).get().getTableLocation());
              locations.add(repository.findAllByDatabaseId(DB).get(0).getTableLocation());
              return locations;
            });
    Assertions.assertEquals(
        java.util.Arrays.asList(
            "/t/0.metadata.json",
            "/t/0.metadata.json",
            "/t/1.metadata.json",
            "/t/1.metadata.json",
            "/t/1.metadata.json"),
        seen);
  }

  /** Two services in one JVM, as the Spark suites run them: neither sees the other's rows. */
  @Test
  public void twoApplicationContextsShareNoRowsAndNoSoftDeletedRows() throws Exception {
    HouseTableRepository first = start().getBean(HouseTableRepository.class);
    HouseTableRepository second = start().getBean(HouseTableRepository.class);

    first.save(row("t", INITIAL_TABLE_VERSION, "/first/t/0.metadata.json"));
    Assertions.assertEquals(Collections.emptyList(), second.findAllByDatabaseId(DB));
    second.save(row("t", INITIAL_TABLE_VERSION, "/second/t/0.metadata.json"));

    first.deleteById(key("t"), false);
    Assertions.assertEquals(
        1, first.searchSoftDeletedTables(DB, "t", PageRequest.of(0, 10)).getTotalElements());
    Assertions.assertEquals(
        0, second.searchSoftDeletedTables(DB, "t", PageRequest.of(0, 10)).getTotalElements());
    Assertions.assertEquals(
        "/second/t/0.metadata.json", second.findById(key("t")).get().getTableLocation());
  }

  /** A write held open in one context does not hold up a write in another. */
  @Test
  public void aWriteInOneContextDoesNotWaitForAnotherContext() throws Exception {
    ConfigurableApplicationContext firstContext = start();
    HouseTableRepository first = firstContext.getBean(HouseTableRepository.class);
    HouseTableRepository second = start().getBean(HouseTableRepository.class);
    TransactionTemplate open =
        new TransactionTemplate(firstContext.getBean(PlatformTransactionManager.class));
    open.execute(
        status -> {
          first.save(row("held", INITIAL_TABLE_VERSION, "/first/held/0.metadata.json"));
          try {
            CompletableFuture.runAsync(
                    () -> second.save(row("free", INITIAL_TABLE_VERSION, "/second/free/0.json")))
                .get(10, TimeUnit.SECONDS);
          } catch (Exception e) {
            throw new AssertionError("the other context's write waited for this one", e);
          }
          return null;
        });
    Assertions.assertTrue(second.findEntityById(key("free")).isPresent());
  }

  /**
   * Even with a datasource URL on the command line, the highest-precedence property source, the
   * stand-in's database keeps result reuse off and stays a database of its own. H2 lists no such
   * setting, so the connection's own URL is the evidence; {@code HouseTablesH2ReadAfterCommitTest}
   * shows what the setting does.
   */
  @Test
  public void noPropertyTurnsResultReuseBackOn() throws Exception {
    ConfigurableApplicationContext context =
        start(
            "--spring.datasource.url=jdbc:h2:mem:consumer_db;DB_CLOSE_DELAY=-1;"
                + "OPTIMIZE_REUSE_RESULTS=TRUE");
    DataSource dataSource = context.getBean(DataSource.class);
    try (Connection connection = dataSource.getConnection()) {
      // The database the connections reach (H2 reports the name without the settings).
      String connected = connection.getMetaData().getURL();
      Assertions.assertTrue(connected.startsWith("jdbc:h2:mem:house-tables-"), connected);
    }
    String configured = dataSource.unwrap(HikariDataSource.class).getJdbcUrl();
    Assertions.assertTrue(configured.startsWith("jdbc:h2:mem:house-tables-"), configured);
    Assertions.assertTrue(configured.contains(";OPTIMIZE_REUSE_RESULTS=FALSE"), configured);
  }
}
