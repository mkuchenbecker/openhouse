package com.linkedin.openhouse.tablestest;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The stand-in's compare-and-swap must read the stored location from the row, not from an entity a
 * persistence context already holds. Two commits built on the same base version: the second must be
 * rejected as the house tables service rejects a stale version, even when its persistence context
 * loaded the row before the first commit landed (the request-scoped context of a real commit).
 */
public class HouseTablesH2SaveVersionTest {

  private static final String DB = "casdb";
  private static final String TABLE = "t";

  private ConfigurableApplicationContext context;
  private HouseTablesH2Repository repository;
  private TransactionTemplate tx;

  @BeforeEach
  public void setUp() {
    try {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.register();
    } catch (Error e) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    }
    SpringApplication application = new SpringApplication(SpringH2TestApplication.class);
    application.setDefaultProperties(Collections.singletonMap("server.port", "0"));
    context = application.run();
    repository = context.getBean(HouseTablesH2Repository.class);
    tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    repository.deleteAll();
  }

  @AfterEach
  public void tearDown() {
    if (context != null) {
      context.close();
    }
  }

  private static HouseTable row(String version, String location) {
    return HouseTable.builder()
        .databaseId(DB)
        .tableId(TABLE)
        .tableLocation(location)
        .tableVersion(version)
        .storageType("local")
        .build();
  }

  @Test
  public void secondSaveOnTheSameBaseVersionIsRejectedEvenWithAStaleContext() {
    repository.save(row(INITIAL_TABLE_VERSION, "/m/00000.json"));
    HouseTablePrimaryKey key = HouseTablePrimaryKey.builder().databaseId(DB).tableId(TABLE).build();

    Assertions.assertThrows(
        HouseTableConcurrentUpdateException.class,
        () ->
            tx.execute(
                status -> {
                  // this commit's context loads the row at the base version
                  Assertions.assertEquals(
                      "/m/00000.json", repository.findById(key).get().getTableLocation());
                  // a concurrent commit on the same base lands and completes
                  CompletableFuture.runAsync(
                          () -> repository.save(row("/m/00000.json", "/m/00001.json")))
                      .join();
                  // same base version: must be rejected, the stored location is now 00001
                  return repository.save(row("/m/00000.json", "/m/00002.json"));
                }));

    Assertions.assertEquals("/m/00001.json", repository.findById(key).get().getTableLocation());
  }

  @Test
  public void sequentialSavesOnTheSameBaseVersionAreRejected() {
    repository.save(row(INITIAL_TABLE_VERSION, "/m/00000.json"));
    repository.save(row("/m/00000.json", "/m/00001.json"));
    Assertions.assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository.save(row("/m/00000.json", "/m/00002.json")));
  }
}
