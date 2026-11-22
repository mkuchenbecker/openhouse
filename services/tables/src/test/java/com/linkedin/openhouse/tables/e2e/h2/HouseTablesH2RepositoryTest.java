package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.model.SoftDeletedTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * The house table stand-in against the rules of the real store, which an HTTP caller can only reach
 * through a true race: a save is a compare-and-swap on the metadata location, and a delete is hard
 * unless it is not a purge.
 */
@SpringBootTest(classes = SpringH2Application.class)
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class HouseTablesH2RepositoryTest {
  private static final String DB = "cc_store";

  @Autowired private HouseTableRepository store;

  private final List<HouseTablePrimaryKey> keys = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (HouseTablePrimaryKey key : keys) {
      store.deleteById(key, true);
    }
    keys.clear();
    HouseTablesH2Repository.softDeletedTables.keySet().removeIf(k -> k.getDatabaseId().equals(DB));
  }

  private HouseTablePrimaryKey key(String table) {
    HouseTablePrimaryKey key = HouseTablePrimaryKey.builder().databaseId(DB).tableId(table).build();
    keys.add(key);
    return key;
  }

  private static HouseTable row(String table, String version, String location) {
    return HouseTable.builder()
        .databaseId(DB)
        .tableId(table)
        .tableUUID("uuid-" + table)
        .tableVersion(version)
        .tableLocation(location)
        .storageType("local")
        .build();
  }

  @Test
  void firstSaveNeedsTheInitialVersionAndLaterSavesNeedTheStoredLocation() {
    key("chain");

    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> store.save(row("chain", "/m/00000.json", "/m/00001.json")));
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> store.save(row("chain", null, "/m/00000.json")));
    assertFalse(store.findEntityById(key("chain")).isPresent());

    store.save(row("chain", INITIAL_TABLE_VERSION, "/m/00000.json"));
    // the store reports the location as the version
    assertEquals("/m/00000.json", store.findById(key("chain")).get().getTableVersion());

    // creating over an existing row is a stale write too
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> store.save(row("chain", INITIAL_TABLE_VERSION, "/m/00001.json")));
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> store.save(row("chain", "/m/elsewhere.json", "/m/00001.json")));
    assertEquals("/m/00000.json", store.findById(key("chain")).get().getTableLocation());

    store.save(row("chain", "/m/00000.json", "/m/00001.json"));
    assertEquals("/m/00001.json", store.findById(key("chain")).get().getTableLocation());
    // the loser of the step above now holds a stale version
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> store.save(row("chain", "/m/00000.json", "/m/00002.json")));
  }

  @Test
  void concurrentSavesFromTheSameVersionHaveOneWinner() throws Exception {
    int threads = 8;
    key("race");
    store.save(row("race", INITIAL_TABLE_VERSION, "/m/00000.json"));
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Boolean>> outcomes = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      String next = "/m/winner-" + i + ".json";
      outcomes.add(
          pool.submit(
              () -> {
                go.await();
                try {
                  store.save(row("race", "/m/00000.json", next));
                  return true;
                } catch (HouseTableConcurrentUpdateException e) {
                  return false;
                }
              }));
    }
    go.countDown();
    int winners = 0;
    for (Future<Boolean> f : outcomes) {
      if (f.get(60, TimeUnit.SECONDS)) {
        winners++;
      }
    }
    pool.shutdown();

    assertEquals(1, winners);
    assertTrue(store.findById(key("race")).get().getTableLocation().startsWith("/m/winner-"));
  }

  @Test
  void purgeDeleteIsHardAndNonPurgeDeleteMovesTheRowWithRealTimes() {
    store.save(row("hard", INITIAL_TABLE_VERSION, "/m/00000.json"));
    store.save(row("soft", INITIAL_TABLE_VERSION, "/m/00000.json"));
    key("hard");
    key("soft");

    store.deleteById(key("hard"), true);
    assertFalse(store.findById(key("hard")).isPresent());
    assertTrue(
        HouseTablesH2Repository.softDeletedTables.keySet().stream()
            .noneMatch(k -> k.getTableId().equals("hard")));

    long from = System.currentTimeMillis();
    store.deleteById(key("soft"), false);
    long to = System.currentTimeMillis();
    assertFalse(store.findById(key("soft")).isPresent());
    SoftDeletedTablePrimaryKey softKey =
        HouseTablesH2Repository.softDeletedTables.keySet().stream()
            .filter(k -> k.getTableId().equals("soft"))
            .findFirst()
            .get();
    assertTrue(softKey.getDeletedAtMs() >= from && softKey.getDeletedAtMs() <= to);
    HouseTable archived = HouseTablesH2Repository.softDeletedTables.get(softKey);
    assertEquals(softKey.getDeletedAtMs(), archived.getDeletedAtMs());
    assertEquals(softKey.getDeletedAtMs() + 7L * 24 * 60 * 60 * 1000, archived.getPurgeAfterMs());

    // restore is not a version-checked save: the row comes back as it was archived
    store.restoreTable(DB, "soft", softKey.getDeletedAtMs());
    assertEquals("/m/00000.json", store.findById(key("soft")).get().getTableLocation());
    assertEquals(0, store.findById(key("soft")).get().getDeletedAtMs());
  }
}
