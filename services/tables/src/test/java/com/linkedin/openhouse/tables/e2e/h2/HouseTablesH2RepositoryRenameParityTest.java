package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Pins the rename concurrency semantics of both in-memory House Tables fixtures — the one used by
 * the /tables e2e suite and its twin published from {@code tables-test-fixtures} — against the real
 * HTS contract stated on {@link HouseTableRepository#rename}.
 *
 * <p>Without this, the fixtures' conflict branch is behavior no test exercises: every e2e rename
 * takes the happy path, so a fixture that drifted from HTS (silently landing a stale-token rename)
 * would leave the suite green while deployments conflicted, or vice versa. The two fixtures are
 * separate source files, so they are checked against the same expectations here.
 */
public class HouseTablesH2RepositoryRenameParityTest {

  private static final String DATABASE_ID = "d1";
  private static final String TABLE_ID = "t1";
  private static final String BASE_LOCATION = "/tmp/d1/t1/v0.metadata.json";
  private static final String NEW_LOCATION = "/tmp/d1/t1_renamed/v1.metadata.json";
  private static final String RECREATED_LOCATION = "/tmp/d1/t1/v0-recreated.metadata.json";

  @Test
  public void testTablesE2eFixtureMatchesHtsRenameSemantics() {
    assertRenameSemantics(() -> newFixture(HouseTablesH2Repository.class));
  }

  @Test
  public void testPublishedTestFixtureMatchesHtsRenameSemantics() {
    assertRenameSemantics(
        () -> newFixture(com.linkedin.openhouse.tablestest.HouseTablesH2Repository.class));
  }

  @Test
  public void testTablesE2eFixtureRejectsRenameAcrossDropAndRecreate() {
    assertRenameRejectsDropAndRecreate(HouseTablesH2Repository.class);
  }

  @Test
  public void testPublishedTestFixtureRejectsRenameAcrossDropAndRecreate() {
    assertRenameRejectsDropAndRecreate(
        com.linkedin.openhouse.tablestest.HouseTablesH2Repository.class);
  }

  /**
   * The fourth mode, which HTS's version counter cannot distinguish on its own: the table is
   * dropped and recreated at the same identity after the rename read it, so the row the update
   * meets is a different table whose version has restarted at the value the rename observed. HTS
   * rejects this because its update is conditional on the observed metadata location, and the
   * fixtures must reject it for the same reason and leave the new incarnation alone.
   */
  private <T extends HouseTableRepository> void assertRenameRejectsDropAndRecreate(
      Class<T> fixtureType) {
    Map<HouseTablePrimaryKey, HouseTable> rows = new HashMap<>();
    T fixture = newFixture(fixtureType, rows);

    // The drop and recreate lands immediately after the rename's read, so the rename's declared
    // base still matches what it read while the row it is about to update is a different table.
    AtomicBoolean recreated = new AtomicBoolean(false);
    Mockito.doAnswer(
            invocation -> {
              Optional<HouseTable> current =
                  Optional.ofNullable(rows.get(invocation.getArgument(0)));
              if (recreated.compareAndSet(false, true)) {
                rows.put(
                    key(DATABASE_ID, TABLE_ID),
                    HouseTable.builder()
                        .databaseId(DATABASE_ID)
                        .tableId(TABLE_ID)
                        .tableLocation(RECREATED_LOCATION)
                        .build());
              }
              return current;
            })
        .when(fixture)
        .findById(Mockito.any(HouseTablePrimaryKey.class));

    Assertions.assertThrows(
        HouseTableConcurrentUpdateException.class,
        () ->
            fixture.rename(
                DATABASE_ID,
                TABLE_ID,
                DATABASE_ID,
                TABLE_ID + "_renamed",
                NEW_LOCATION,
                BASE_LOCATION));
    Assertions.assertEquals(
        RECREATED_LOCATION,
        rows.get(key(DATABASE_ID, TABLE_ID)).getTableLocation(),
        "the recreated table must keep its own metadata location");
    Assertions.assertFalse(
        rows.containsKey(key(DATABASE_ID, TABLE_ID + "_renamed")),
        "a conflicting rename must not create the target row");
  }

  /**
   * The three modes the real HTS rename distinguishes: a stale declared base conflicts, a matching
   * declared base lands, and an absent declared base lands (the old-client mode, where HTS's own
   * version CAS is the only guard).
   */
  private void assertRenameSemantics(Supplier<? extends HouseTableRepository> fixtureFactory) {
    HouseTableRepository staleTokenFixture = fixtureFactory.get();
    Assertions.assertThrows(
        HouseTableConcurrentUpdateException.class,
        () ->
            staleTokenFixture.rename(
                DATABASE_ID,
                TABLE_ID,
                DATABASE_ID,
                TABLE_ID + "_renamed",
                NEW_LOCATION,
                BASE_LOCATION + "_stale"));
    Assertions.assertEquals(
        BASE_LOCATION,
        staleTokenFixture.findById(key(DATABASE_ID, TABLE_ID)).get().getTableLocation(),
        "a conflicting rename must leave the source row untouched");
    Assertions.assertFalse(
        staleTokenFixture.findById(key(DATABASE_ID, TABLE_ID + "_renamed")).isPresent(),
        "a conflicting rename must not create the target row");

    HouseTableRepository matchingTokenFixture = fixtureFactory.get();
    matchingTokenFixture.rename(
        DATABASE_ID, TABLE_ID, DATABASE_ID, TABLE_ID + "_renamed", NEW_LOCATION, BASE_LOCATION);
    Assertions.assertEquals(
        NEW_LOCATION,
        matchingTokenFixture
            .findById(key(DATABASE_ID, TABLE_ID + "_renamed"))
            .get()
            .getTableLocation());
    Assertions.assertFalse(matchingTokenFixture.findById(key(DATABASE_ID, TABLE_ID)).isPresent());

    HouseTableRepository tokenlessFixture = fixtureFactory.get();
    tokenlessFixture.rename(
        DATABASE_ID, TABLE_ID, DATABASE_ID, TABLE_ID + "_renamed", NEW_LOCATION, null);
    Assertions.assertEquals(
        NEW_LOCATION,
        tokenlessFixture
            .findById(key(DATABASE_ID, TABLE_ID + "_renamed"))
            .get()
            .getTableLocation());
  }

  /**
   * Builds a fixture whose {@code rename} default method runs for real over an in-memory row map,
   * so the branch under test is the fixture's own code rather than a stub of it.
   */
  private <T extends HouseTableRepository> T newFixture(Class<T> fixtureType) {
    return newFixture(fixtureType, new HashMap<>());
  }

  /** As above, over a caller-supplied row map the test can inspect and mutate. */
  private <T extends HouseTableRepository> T newFixture(
      Class<T> fixtureType, Map<HouseTablePrimaryKey, HouseTable> rows) {
    rows.put(
        key(DATABASE_ID, TABLE_ID),
        HouseTable.builder()
            .databaseId(DATABASE_ID)
            .tableId(TABLE_ID)
            .tableLocation(BASE_LOCATION)
            .build());

    T fixture =
        Mockito.mock(fixtureType, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
    Mockito.doAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))))
        .when(fixture)
        .findById(Mockito.any(HouseTablePrimaryKey.class));
    Mockito.doAnswer(
            invocation -> {
              HouseTable houseTable = invocation.getArgument(0);
              rows.put(key(houseTable.getDatabaseId(), houseTable.getTableId()), houseTable);
              return houseTable;
            })
        .when(fixture)
        .save(Mockito.any());
    Mockito.doAnswer(
            invocation -> {
              HouseTable houseTable = invocation.getArgument(0);
              rows.remove(key(houseTable.getDatabaseId(), houseTable.getTableId()));
              return null;
            })
        .when(fixture)
        .delete(Mockito.any());
    return fixture;
  }

  private static HouseTablePrimaryKey key(String databaseId, String tableId) {
    return HouseTablePrimaryKey.builder().databaseId(databaseId).tableId(tableId).build();
  }
}
