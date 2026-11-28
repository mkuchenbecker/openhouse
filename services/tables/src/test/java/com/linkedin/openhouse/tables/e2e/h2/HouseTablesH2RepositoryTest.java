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
import com.linkedin.openhouse.tablestest.HouseTablesH2Repository;
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

  @Autowired private HouseTablesH2Repository houseTablesH2Store;
  private static final String DB = "cc_store";

  @Autowired private HouseTableRepository store;

  private final List<HouseTablePrimaryKey> keys = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (HouseTablePrimaryKey key : keys) {
      // A missing row is a 404 on delete, as in the store; most tests leave none behind.
      if (store
          .findEntityById(key)
          .filter(row -> "TABLE".equals(row.getEntityType()))
          .isPresent()) {
        store.deleteById(key, true);
      }
    }
    keys.clear();
    houseTablesH2Store.softDeletedTables().keySet().removeIf(k -> k.getDatabaseId().equals(DB));
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

  /**
   * The tables of a database are found by any spelling of its name, as House Tables' table listing
   * finds them (it compares the upper-cased names).
   */
  @Test
  void theTablesOfADatabaseAreFoundByAnySpellingOfItsName() {
    key("cased");
    store.save(row("cased", INITIAL_TABLE_VERSION, "/m/00000.json"));
    String upper = DB.toUpperCase(java.util.Locale.ROOT);
    assertEquals(1, store.findAllByDatabaseId(upper).size());
    assertEquals(
        1,
        store
            .findAllByDatabaseId(upper, org.springframework.data.domain.PageRequest.of(0, 1))
            .getContent()
            .size());
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

  /**
   * Tables and views share the store's key (house tables' {@code user_table_row} has the primary
   * key {@code (database_id, table_id)} whatever the entity type), so renaming a table onto a key a
   * view holds violates it: the real store answers 409, which its client raises as {@link
   * HouseTableConcurrentUpdateException}, and both rows stay as they were. Before, this stand-in
   * overwrote the view with the renamed table.
   */
  @Test
  void renameOntoAKeyAViewHoldsIsRefusedAndChangesNeitherRow() {
    store.save(row("ren_src", INITIAL_TABLE_VERSION, "/m/src.json"));
    key("ren_src");
    store.saveView(row("ren_view", INITIAL_TABLE_VERSION, "/m/view.json"));
    try {
      assertThrows(
          HouseTableConcurrentUpdateException.class,
          () -> store.rename(DB, "ren_src", DB, "ren_view", "/m/renamed.json"));
      assertEquals("/m/src.json", store.findById(key("ren_src")).get().getTableLocation());
      HouseTable view = store.findViewById(key("ren_view")).get();
      assertEquals("/m/view.json", view.getTableLocation());
      assertEquals("VIEW", view.getEntityType());
    } finally {
      store.deleteViewById(
          HouseTablePrimaryKey.builder().databaseId(DB).tableId("ren_view").build());
    }
  }

  @Test
  void purgeDeleteIsHardAndNonPurgeDeleteMovesTheRowWithRealTimes() {
    store.save(row("hard", INITIAL_TABLE_VERSION, "/m/00000.json"));
    store.save(row("soft", INITIAL_TABLE_VERSION, "/m/00000.json"));
    key("hard");
    key("soft");

    store.deleteById(key("hard"), true);
    assertFalse(store.findEntityById(key("hard")).isPresent());
    assertTrue(
        houseTablesH2Store.softDeletedTables().keySet().stream()
            .noneMatch(k -> k.getTableId().equals("hard")));

    long from = System.currentTimeMillis();
    store.deleteById(key("soft"), false);
    long to = System.currentTimeMillis();
    assertFalse(store.findEntityById(key("soft")).isPresent());
    SoftDeletedTablePrimaryKey softKey =
        houseTablesH2Store.softDeletedTables().keySet().stream()
            .filter(k -> k.getTableId().equals("soft"))
            .findFirst()
            .get();
    assertTrue(softKey.getDeletedAtMs() >= from && softKey.getDeletedAtMs() <= to);
    HouseTable archived = houseTablesH2Store.softDeletedTables().get(softKey);
    assertEquals(softKey.getDeletedAtMs(), archived.getDeletedAtMs());
    assertEquals(softKey.getDeletedAtMs() + 7L * 24 * 60 * 60 * 1000, archived.getPurgeAfterMs());

    // restore is not a version-checked save: the row comes back as it was archived
    store.restoreTable(DB, "soft", softKey.getDeletedAtMs());
    assertEquals("/m/00000.json", store.findById(key("soft")).get().getTableLocation());
    assertEquals(0, store.findById(key("soft")).get().getDeletedAtMs());
  }
}
