package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.preparePutSnapshotsWithAppendRequest;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tablestest.HouseTablesH2Repository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Commit conflicts and the delete lifecycle, now that the house table stand-in behaves like the
 * real store: a save is a compare-and-swap on the metadata location, DELETE is a hard delete, and
 * soft-deleted rows carry a real deletion time. Every case pins the status, the stable leading part
 * of the message and what a follow-up read shows.
 *
 * <p>Which layer rejects a stale commit is visible in the message: the tables service compares the
 * base version with the table it just loaded ("Entity with key[...] is modified by another process
 * already"), the catalog remembers every base version that reached a commit ("is stale, please
 * consider retry"), and the store rejects a save whose version is not the stored location. Only a
 * true race gets past the first two.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class CommitPathConcurrencyTest {

  @Autowired private HouseTablesH2Repository houseTablesH2Store;
  private static final String MODIFIED =
      "Entity with key[local-cluster.%s.t] is modified by another process already";
  private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000;

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired HouseTableRepository houseTables;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
    houseTablesH2Store
        .softDeletedTables()
        .keySet()
        .removeIf(k -> k.getDatabaseId().startsWith("cc_"));
  }

  /** Creates {@code db.t} and returns what a GET shows for it. */
  private String make(String db) throws Exception {
    created.add(new String[] {db, "t"});
    post(mvc, newTable(db, "t")).andExpect(status().isCreated());
    return json(get(mvc, db, "t"));
  }

  private static CreateUpdateTableRequestBody withProp(String getJson, String key, String value) {
    CreateUpdateTableRequestBody base = bodyFromGet(getJson);
    Map<String, String> props = new HashMap<>(base.getTableProperties());
    props.put(key, value);
    return base.toBuilder().tableProperties(props).build();
  }

  private static String userProp(String getJson, String key) {
    Map<String, String> props = read(getJson, "$.tableProperties");
    return props.get(key);
  }

  /** An append request on top of the table's current state, built from {@code current}. */
  private IcebergSnapshotsRequestBody appendFrom(String db, String dataFile) throws Exception {
    MvcResult current = get(mvc, db, "t").andReturn();
    GetTableResponseBody body = buildGetTableResponseBody(current);
    return preparePutSnapshotsWithAppendRequest(
        current,
        body,
        body.getTableLocation(),
        catalog,
        Collections.singletonList(
            createDummyDataFile("/tmp/" + db + "_" + dataFile + ".orc", getPartitionSpec(body))));
  }

  private static long newSnapshotId(IcebergSnapshotsRequestBody request) {
    List<String> snapshots = request.getJsonSnapshots();
    return SnapshotParser.fromJson(snapshots.get(snapshots.size() - 1)).snapshotId();
  }

  private Set<Long> snapshotIds(String db) {
    Set<Long> ids = new HashSet<>();
    for (Snapshot s : catalog.loadTable(TableIdentifier.of(db, "t")).snapshots()) {
      ids.add(s.snapshotId());
    }
    return ids;
  }

  // ---- conflicts ----

  @Test
  void secondUpdateFromTheSameBaseIsAConflictAndGetShowsTheFirst() throws Exception {
    String db = "cc_two_updates";
    String v0 = make(db);

    put(mvc, withProp(v0, "user.v", "first")).andExpect(status().isOk());
    String afterFirst = json(get(mvc, db, "t"));

    expectError(put(mvc, withProp(v0, "user.v", "second")), 409, String.format(MODIFIED, db));

    expectUnchanged(mvc, afterFirst, db, "t");
    assertEquals("first", userProp(afterFirst, "user.v"));
    assertTrue(((String) read(afterFirst, "$.tableLocation")).contains("/00001-"));
  }

  @Test
  void secondSnapshotCommitFromTheSameBaseIsAConflictAndLeavesNoLoserSnapshot() throws Exception {
    String db = "cc_two_snapshots";
    make(db);
    IcebergSnapshotsRequestBody winner = appendFrom(db, "winner");
    IcebergSnapshotsRequestBody loser = appendFrom(db, "loser");
    assertEquals(winner.getBaseTableVersion(), loser.getBaseTableVersion());

    putSnapshots(mvc, db, "t", winner).andExpect(status().isOk());
    String afterWinner = json(get(mvc, db, "t"));

    expectError(putSnapshots(mvc, db, "t", loser), 409, String.format(MODIFIED, db));

    expectUnchanged(mvc, afterWinner, db, "t");
    Set<Long> ids = snapshotIds(db);
    assertTrue(ids.contains(newSnapshotId(winner)));
    assertFalse(ids.contains(newSnapshotId(loser)));
    assertEquals(1, ids.size());
  }

  /**
   * A snapshot commit built on a base that a property update has since replaced. The tables
   * service's own base comparison answers first; the catalog's stale-base divergence guard sits
   * behind it and is not reachable from a sequential request.
   */
  @Test
  void snapshotCommitOnABaseThatAnotherCommitReplacedIsAConflict() throws Exception {
    String db = "cc_stale_base";
    String v0 = make(db);
    IcebergSnapshotsRequestBody onV0 = appendFrom(db, "stale");
    put(mvc, withProp(v0, "user.v", "landed")).andExpect(status().isOk());
    String afterLanded = json(get(mvc, db, "t"));

    expectError(putSnapshots(mvc, db, "t", onV0), 409, String.format(MODIFIED, db));

    expectUnchanged(mvc, afterLanded, db, "t");
    assertEquals(0, snapshotIds(db).size());
  }

  @Test
  void replayingTheSameCommitRequestIsAConflictAndChangesNothing() throws Exception {
    String db = "cc_replay";
    String v0 = make(db);
    CreateUpdateTableRequestBody commit = withProp(v0, "user.v", "once");

    put(mvc, commit).andExpect(status().isOk());
    String afterFirst = json(get(mvc, db, "t"));

    expectError(put(mvc, commit), 409, String.format(MODIFIED, db));
    expectUnchanged(mvc, afterFirst, db, "t");

    // a different body on the same, already used, base is refused the same way
    expectError(put(mvc, withProp(v0, "user.v", "other")), 409, String.format(MODIFIED, db));
    expectUnchanged(mvc, afterFirst, db, "t");
  }

  @Test
  void concurrentIdenticalUpdatesYieldExactlyOneWinnerAndOneVersionStep() throws Exception {
    String db = "cc_race";
    int threads = 8;
    String v0 = make(db);
    CreateUpdateTableRequestBody commit = withProp(v0, "user.v", "raced");

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      results.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return CommitPathHttp.status(put(mvc, commit));
              }));
    }
    ready.await();
    go.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> f : results) {
      statuses.add(f.get(120, TimeUnit.SECONDS));
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

    assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses were " + statuses);
    assertEquals(
        threads - 1, statuses.stream().filter(s -> s == 409).count(), "statuses were " + statuses);
    String after = json(get(mvc, db, "t"));
    assertEquals("raced", userProp(after, "user.v"));
    assertTrue(((String) read(after, "$.tableLocation")).contains("/00001-"));
    assertEquals(
        str(v0, "$.tableLocation").replaceFirst("^file:", ""), str(after, "$.tableVersion"));
  }

  // ---- delete lifecycle ----

  private int softDeletedCount(String db, String table) throws Exception {
    List<Object> rows =
        read(json(softDeleted(mvc, db)), "$.pageResults.content[?(@.tableId=='" + table + "')]");
    return rows.size();
  }

  private void softDeleteInTheStore(String db, String table) {
    houseTables.deleteById(
        HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build(), false);
  }

  /** The tables service purges on DELETE, which the real client sends as a hard delete. */
  @Test
  void deleteIsAHardDeleteAndNothingAppearsInTheSoftDeletedListing() throws Exception {
    String db = "cc_delete";
    make(db);

    delete(mvc, db, "t").andExpect(status().isNoContent());

    get(mvc, db, "t").andExpect(status().isNotFound());
    assertEquals(0, softDeletedCount(db, "t"));
    delete(mvc, db, "t").andExpect(status().isNotFound());
  }

  @Test
  void softDeleteListsARealDeletionTimeAndRestoreBringsTheTableBack() throws Exception {
    String db = "cc_restore";
    String before = make(db);
    long from = System.currentTimeMillis();

    softDeleteInTheStore(db, "t");
    long to = System.currentTimeMillis();

    get(mvc, db, "t").andExpect(status().isNotFound());
    String listing = json(softDeleted(mvc, db));
    assertEquals(1, softDeletedCount(db, "t"));
    long deletedAtMs =
        ((Number) ((List<?>) read(listing, "$.pageResults.content[*].deletedAtMs")).get(0))
            .longValue();
    long purgeAfterMs =
        ((Number) ((List<?>) read(listing, "$.pageResults.content[*].purgeAfterMs")).get(0))
            .longValue();
    assertTrue(deletedAtMs >= from && deletedAtMs <= to, "deletedAtMs " + deletedAtMs);
    assertEquals(deletedAtMs + SEVEN_DAYS_MS, purgeAfterMs);

    restore(mvc, db, "t", deletedAtMs).andExpect(status().isNoContent());

    String after = json(get(mvc, db, "t"));
    assertEquals(str(before, "$.tableUUID"), str(after, "$.tableUUID"));
    assertEquals(str(before, "$.tableLocation"), str(after, "$.tableLocation"));
    assertEquals(0, softDeletedCount(db, "t"));

    // the restored table takes updates on top of its restored location
    put(mvc, withProp(after, "user.v", "restored")).andExpect(status().isOk());
    assertEquals("restored", userProp(json(get(mvc, db, "t")), "user.v"));
  }

  @Test
  void restoreOfAnUnknownDeletionTimeIsNotFoundAndKeepsTheSoftDeletedRow() throws Exception {
    String db = "cc_restore_unknown";
    make(db);
    softDeleteInTheStore(db, "t");
    String listing = json(softDeleted(mvc, db));
    long deletedAtMs =
        ((Number) ((List<?>) read(listing, "$.pageResults.content[*].deletedAtMs")).get(0))
            .longValue();

    assertEquals(404, CommitPathHttp.status(restore(mvc, db, "t", deletedAtMs + 1)));

    get(mvc, db, "t").andExpect(status().isNotFound());
    assertEquals(1, softDeletedCount(db, "t"));
    restore(mvc, db, "t", deletedAtMs).andExpect(status().isNoContent());
    // once restored the table is live, which a restore refuses before it looks for the row
    assertEquals(409, CommitPathHttp.status(restore(mvc, db, "t", deletedAtMs)));
    assertEquals(0, softDeletedCount(db, "t"));
  }

  @Test
  void purgeRemovesOnlyRowsWhosePurgeTimeIsBeforeTheGivenTime() throws Exception {
    String db = "cc_purge";
    make(db);
    softDeleteInTheStore(db, "t");
    String listing = json(softDeleted(mvc, db));
    long purgeAfterMs =
        ((Number) ((List<?>) read(listing, "$.pageResults.content[*].purgeAfterMs")).get(0))
            .longValue();

    // the row is not due yet: its purge time is a week away
    purge(mvc, db, "t", System.currentTimeMillis()).andExpect(status().isNoContent());
    assertEquals(1, softDeletedCount(db, "t"));
    purge(mvc, db, "t", purgeAfterMs).andExpect(status().isNoContent());
    assertEquals(1, softDeletedCount(db, "t"));

    purge(mvc, db, "t", purgeAfterMs + 1).andExpect(status().isNoContent());
    assertEquals(0, softDeletedCount(db, "t"));
    get(mvc, db, "t").andExpect(status().isNotFound());
  }

  @Test
  void softDeletedListingIsScopedToTheTableAndDatabase() throws Exception {
    String db = "cc_listing";
    make(db);
    softDeleteInTheStore(db, "t");

    assertEquals(1, softDeletedCount(db, "t"));
    assertEquals(0, softDeletedCount(db, "other"));
    assertEquals(0, softDeletedCount("cc_listing_elsewhere", "t"));
    assertEquals(
        Collections.singletonList("t"),
        ((List<?>) read(json(softDeleted(mvc, db)), "$.pageResults.content[*].tableId"))
            .stream().map(String::valueOf).collect(Collectors.toList()));
  }
}
