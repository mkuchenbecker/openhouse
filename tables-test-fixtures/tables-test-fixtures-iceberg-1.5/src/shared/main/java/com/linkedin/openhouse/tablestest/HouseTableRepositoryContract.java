package com.linkedin.openhouse.tablestest;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.internal.catalog.CatalogConstants.ENTITY_TYPE_TABLE;
import static com.linkedin.openhouse.internal.catalog.CatalogConstants.ENTITY_TYPE_VIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableCallerException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableNotFoundException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * What the tables service relies on from a {@link HouseTableRepository}, run against the embedded
 * stand-in ({@link HouseTablesH2Repository}, in this module's tests and in the tables service's
 * tests) and against the real repository over a real House Tables service (in the tables service's
 * House Tables integration tests), so that the stand-in cannot drift from the store.
 *
 * <p>The real store defines every expectation here: a compare-and-set save on the metadata location
 * that refuses any other occupant of the key, keys that ignore letter case and keep the spelling
 * they were created with, the bootstrap schema's column widths, 404 for a missing row on every
 * point operation and 409 for an occupied one, only the fields House Tables keeps, a soft-deleted
 * store that is listed, purged and restored by key, and listings that page and sort in the store.
 *
 * <p>Every database carries a suffix of its own per test, so that the tests can share one store.
 */
public abstract class HouseTableRepositoryContract {

  /** The widths of the House Tables service's bootstrap schema ({@code schema.sql}). */
  public static final int BOOTSTRAP_ID_WIDTH = 128;

  public static final int BOOTSTRAP_METADATA_LOCATION_WIDTH = 512;

  /**
   * The widths of the production tables ({@code ddl/0000__baseline.sql}), which the stand-in has.
   */
  public static final int PRODUCTION_ID_WIDTH = 255;

  public static final int PRODUCTION_METADATA_LOCATION_WIDTH = 255;

  private static final AtomicInteger TESTS = new AtomicInteger();

  private String suffix;

  /** The repository under test. */
  protected abstract HouseTableRepository repository();

  /** The width of the store's name columns. */
  protected abstract int idWidth();

  /** The width of the store's metadata location column. */
  protected abstract int metadataLocationWidth();

  @BeforeEach
  public void nameThisTest() {
    suffix = "_t" + TESTS.incrementAndGet();
  }

  /** The database name, spelled as given, with this test's suffix. */
  protected String db(String name) {
    return name + suffix;
  }

  private static HouseTablePrimaryKey key(String databaseId, String tableId) {
    return HouseTablePrimaryKey.builder().databaseId(databaseId).tableId(tableId).build();
  }

  private static String location(String databaseId, String tableId, int n) {
    return "/data/openhouse/" + databaseId + "/" + tableId + "/" + n + "-metadata.json";
  }

  private static HouseTable row(String databaseId, String tableId, String version, String loc) {
    return HouseTable.builder()
        .databaseId(databaseId)
        .tableId(tableId)
        .tableVersion(version)
        .tableLocation(loc)
        .build();
  }

  private HouseTable createTable(String databaseId, String tableId) {
    return repository()
        .save(row(databaseId, tableId, INITIAL_TABLE_VERSION, location(databaseId, tableId, 0)));
  }

  private HouseTable createView(String databaseId, String viewId) {
    return repository()
        .saveView(row(databaseId, viewId, INITIAL_TABLE_VERSION, location(databaseId, viewId, 0)));
  }

  private List<String> tableIds(List<HouseTable> rows) {
    return rows.stream().map(HouseTable::getTableId).collect(Collectors.toList());
  }

  private Page<HouseTable> softDeleted(String databaseId, String tableId) {
    return repository().searchSoftDeletedTables(databaseId, tableId, PageRequest.of(0, 50));
  }

  private static String repeat(char c, int n) {
    char[] chars = new char[n];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  // save and the point reads

  @Test
  public void aCreateIsReadBackByAnySpellingWithTheSpellingItWasCreatedWith() {
    String db = db("Sales");
    HouseTable created = createTable(db, "Orders");
    assertEquals(db, created.getDatabaseId());
    assertEquals("Orders", created.getTableId());
    assertEquals(location(db, "Orders", 0), created.getTableLocation());
    // The store reports the metadata location as the version a writer builds on.
    assertEquals(location(db, "Orders", 0), created.getTableVersion());
    assertEquals(ENTITY_TYPE_TABLE, created.getEntityType());

    HouseTable read = repository().findById(key(db.toUpperCase(), "ORDERS")).get();
    assertEquals(db, read.getDatabaseId());
    assertEquals("Orders", read.getTableId());
    assertEquals(location(db, "Orders", 0), read.getTableLocation());
    assertEquals(location(db, "Orders", 0), read.getTableVersion());
    assertEquals(ENTITY_TYPE_TABLE, read.getEntityType());
    assertEquals(read, repository().findEntityById(key(db.toLowerCase(), "orders")).get());
  }

  @Test
  public void theStoreKeepsOnlyItsOwnFieldsAndDefaultsTheStorageType() {
    String db = db("db");
    HouseTable saved =
        repository()
            .save(
                row(db, "t", INITIAL_TABLE_VERSION, location(db, "t", 0))
                    .toBuilder()
                    .tableUUID("5f0f3c3e-0000-0000-0000-000000000001")
                    .clusterId("cluster")
                    .tableUri("cluster." + db + ".t")
                    .tableCreator("creator")
                    .lastModifiedTime(42L)
                    .creationTime(1651002318265L)
                    .build());
    for (HouseTable read : Arrays.asList(saved, repository().findById(key(db, "t")).get())) {
      assertNull(read.getTableUUID(), "the store has no UUID column");
      assertNull(read.getClusterId());
      assertNull(read.getTableUri());
      assertNull(read.getTableCreator());
      assertEquals(0L, read.getLastModifiedTime());
      assertEquals(1651002318265L, read.getCreationTime());
      assertEquals("hdfs", read.getStorageType());
    }

    repository()
        .save(
            row(db, "local", INITIAL_TABLE_VERSION, location(db, "local", 0))
                .toBuilder()
                .storageType("local")
                .build());
    assertEquals("local", repository().findById(key(db, "local")).get().getStorageType());
  }

  @Test
  public void aSaveIsConditionalOnTheStoredMetadataLocation() {
    String db = db("db");
    createTable(db, "t");
    HouseTable updated =
        repository().save(row(db, "t", location(db, "t", 0), location(db, "t", 1)));
    assertEquals(location(db, "t", 1), updated.getTableLocation());
    assertEquals(location(db, "t", 1), updated.getTableVersion());

    // The version read before the update is stale now.
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository().save(row(db, "t", location(db, "t", 0), location(db, "t", 2))));
    assertEquals(
        location(db, "t", 1), repository().findById(key(db, "t")).get().getTableLocation());
  }

  @Test
  public void aCreateOverAnOccupiedKeyOfAnySpellingIsAConflict() {
    String db = db("db");
    createTable(db, "t");
    assertThrows(HouseTableConcurrentUpdateException.class, () -> createTable(db, "t"));
    assertThrows(
        HouseTableConcurrentUpdateException.class, () -> createTable(db.toUpperCase(), "T"));
    assertEquals(Collections.singletonList("t"), tableIds(repository().findAllByDatabaseId(db)));
    assertEquals(
        location(db, "t", 0), repository().findById(key(db, "t")).get().getTableLocation());
  }

  @Test
  public void aSaveWithAVersionOnAnEmptyKeyIsAConflict() {
    String db = db("db");
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository().save(row(db, "t", location(db, "t", 0), location(db, "t", 1))));
    assertFalse(repository().findEntityById(key(db, "t")).isPresent());
  }

  @Test
  public void anUpdateThroughAnotherSpellingUpdatesTheOneRow() {
    String db = db("db");
    createTable(db, "t");
    repository().save(row(db.toUpperCase(), "T", location(db, "t", 0), location(db, "t", 1)));
    List<HouseTable> rows = repository().findAllByDatabaseId(db);
    assertEquals(1, rows.size());
    assertEquals(location(db, "t", 1), rows.get(0).getTableLocation());
    assertEquals(db, rows.get(0).getDatabaseId());
    assertEquals("t", rows.get(0).getTableId());
  }

  @Test
  public void aTableSaveOverAViewIsAConflictAndChangesNothing() {
    String db = db("db");
    createView(db, "v");
    assertThrows(HouseTableConcurrentUpdateException.class, () -> createTable(db, "v"));
    // Even with the view's own version: the store refuses a wrong-type occupant first.
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository().save(row(db, "v", location(db, "v", 0), location(db, "v", 1))));
    HouseTable view = repository().findViewById(key(db, "v")).get();
    assertEquals(ENTITY_TYPE_VIEW, view.getEntityType());
    assertEquals(location(db, "v", 0), view.getTableLocation());
  }

  @Test
  public void aViewSaveOverATableIsAConflictAndChangesNothing() {
    String db = db("db");
    createTable(db, "t");
    assertThrows(HouseTableConcurrentUpdateException.class, () -> createView(db, "t"));
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository().saveView(row(db, "t", location(db, "t", 0), location(db, "t", 1))));
    assertFalse(repository().findViewById(key(db, "t")).isPresent());
    assertEquals(
        location(db, "t", 0), repository().findById(key(db, "t")).get().getTableLocation());
  }

  @Test
  public void aViewIsSavedConditionallyAndReadOnlyThroughTheEntityAndViewReads() {
    String db = db("db");
    HouseTable view = createView(db, "v");
    assertEquals(ENTITY_TYPE_VIEW, view.getEntityType());
    assertEquals(location(db, "v", 0), view.getTableVersion());
    repository().saveView(row(db, "v", location(db, "v", 0), location(db, "v", 1)));
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository().saveView(row(db, "v", location(db, "v", 0), location(db, "v", 2))));
    assertEquals(
        location(db, "v", 1),
        repository().findViewById(key(db.toUpperCase(), "V")).get().getTableLocation());
    assertEquals(ENTITY_TYPE_VIEW, repository().findEntityById(key(db, "V")).get().getEntityType());
    assertThrows(HouseTableNotFoundException.class, () -> repository().findById(key(db, "v")));
  }

  @Test
  public void aMissingKeyIsNotFoundOnTheTableReadAndAbsentOnTheOthers() {
    String db = db("db");
    assertThrows(HouseTableNotFoundException.class, () -> repository().findById(key(db, "t")));
    assertEquals(Optional.empty(), repository().findEntityById(key(db, "t")));
    assertEquals(Optional.empty(), repository().findViewById(key(db, "t")));
    createTable(db, "t");
    assertEquals(Optional.empty(), repository().findViewById(key(db, "t")));
  }

  @Test
  public void theColumnWidthsBoundAndAWiderValueIsAConflict() {
    String db = db("db");
    String longest = "t" + repeat('x', idWidth() - 1);
    repository().save(row(db, longest, INITIAL_TABLE_VERSION, "/l"));
    assertTrue(repository().findById(key(db, longest)).isPresent());
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository().save(row(db, longest + "x", INITIAL_TABLE_VERSION, "/l")));
    assertFalse(repository().findEntityById(key(db, longest + "x")).isPresent());

    String widest = "/" + repeat('l', metadataLocationWidth() - 1);
    String wide = "/" + repeat('w', metadataLocationWidth() - 2);
    repository().save(row(db, "wide", INITIAL_TABLE_VERSION, wide));
    assertEquals(wide, repository().findById(key(db, "wide")).get().getTableLocation());
    repository().save(row(db, "wide", wide, widest));
    assertEquals(widest, repository().findById(key(db, "wide")).get().getTableLocation());
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository().save(row(db, "wide", widest, widest + "l")));
    assertEquals(widest, repository().findById(key(db, "wide")).get().getTableLocation());
  }

  @Test
  public void anInvalidNameOrAnEmptyLocationIsTheCallersError() {
    String db = db("db");
    assertThrows(HouseTableCallerException.class, () -> createTable(db, "a-b"));
    assertThrows(HouseTableCallerException.class, () -> createTable(db + "-x", "t"));
    assertThrows(
        HouseTableCallerException.class,
        () -> repository().save(row(db, "t", INITIAL_TABLE_VERSION, "")));
    assertEquals(Collections.emptyList(), repository().findAllByDatabaseId(db));
  }

  // delete

  @Test
  public void aPurgingDeleteRemovesTheRowByAnySpellingAndKeepsNoCopy() {
    String db = db("db");
    createTable(db, "t");
    repository().deleteById(key(db.toUpperCase(), "T"), true);
    assertFalse(repository().findEntityById(key(db, "t")).isPresent());
    assertEquals(0, softDeleted(db, null).getTotalElements());
  }

  @Test
  public void aDeleteOfAMissingRowOrAViewIsNotFoundAndChangesNothing() {
    String db = db("db");
    assertThrows(
        HouseTableNotFoundException.class, () -> repository().deleteById(key(db, "t"), true));
    assertThrows(
        HouseTableNotFoundException.class, () -> repository().deleteById(key(db, "t"), false));
    createView(db, "v");
    assertThrows(
        HouseTableNotFoundException.class, () -> repository().deleteById(key(db, "v"), true));
    assertThrows(
        HouseTableNotFoundException.class, () -> repository().deleteById(key(db, "v"), false));
    assertTrue(repository().findViewById(key(db, "v")).isPresent());
    assertEquals(0, softDeleted(db, null).getTotalElements());
  }

  @Test
  public void aSoftDeleteMovesTheRowToTheSoftDeletedStore() {
    String db = db("db");
    createTable(db, "t");
    long before = System.currentTimeMillis();
    repository().deleteById(key(db, "t"), false);
    assertFalse(repository().findEntityById(key(db, "t")).isPresent());

    Page<HouseTable> archived = softDeleted(db, "t");
    assertEquals(1, archived.getTotalElements());
    HouseTable row = archived.getContent().get(0);
    assertEquals(db, row.getDatabaseId());
    assertEquals("t", row.getTableId());
    assertEquals(location(db, "t", 0), row.getTableLocation());
    assertTrue(row.getDeletedAtMs() >= before);
    assertTrue(row.getPurgeAfterMs() > row.getDeletedAtMs());
  }

  @Test
  public void aViewDeleteRemovesOnlyAView() {
    String db = db("db");
    createView(db, "v");
    createTable(db, "t");
    assertTrue(repository().deleteViewById(key(db.toUpperCase(), "V")));
    assertFalse(repository().findEntityById(key(db, "v")).isPresent());
    assertFalse(repository().deleteViewById(key(db, "v")));
    assertFalse(repository().deleteViewById(key(db, "t")));
    assertTrue(repository().findById(key(db, "t")).isPresent());
  }

  // rename

  @Test
  public void aRenameMovesTheRowAndItsLocation() {
    String db = db("Db");
    createTable(db, "t");
    repository().rename(db.toLowerCase(), "T", db, "u", location(db, "u", 1));
    assertFalse(repository().findEntityById(key(db, "t")).isPresent());
    HouseTable renamed = repository().findById(key(db, "u")).get();
    assertEquals("u", renamed.getTableId());
    assertEquals(location(db, "u", 1), renamed.getTableLocation());
    assertEquals(location(db, "u", 1), renamed.getTableVersion());
    assertEquals(ENTITY_TYPE_TABLE, renamed.getEntityType());
    assertEquals(Collections.singletonList("u"), tableIds(repository().findAllByDatabaseId(db)));
  }

  @Test
  public void aRenameOfAMissingRowOrAViewIsNotFoundAndChangesNothing() {
    String db = db("db");
    assertThrows(
        HouseTableNotFoundException.class,
        () -> repository().rename(db, "t", db, "u", location(db, "u", 1)));
    createView(db, "v");
    assertThrows(
        HouseTableNotFoundException.class,
        () -> repository().rename(db, "v", db, "w", location(db, "w", 1)));
    assertTrue(repository().findViewById(key(db, "v")).isPresent());
    assertFalse(repository().findEntityById(key(db, "u")).isPresent());
    assertFalse(repository().findEntityById(key(db, "w")).isPresent());
  }

  @Test
  public void aRenameOntoAnOccupiedKeyOfAnySpellingIsAConflictAndChangesNothing() {
    String db = db("db");
    createTable(db, "t");
    createTable(db, "u");
    createView(db, "v");
    for (String target : Arrays.asList("u", "U", "v", "V")) {
      assertThrows(
          HouseTableConcurrentUpdateException.class,
          () -> repository().rename(db, "t", db, target, location(db, target, 1)),
          target);
    }
    assertEquals(
        location(db, "t", 0), repository().findById(key(db, "t")).get().getTableLocation());
    assertEquals(
        location(db, "u", 0), repository().findById(key(db, "u")).get().getTableLocation());
    assertEquals(
        location(db, "v", 0), repository().findViewById(key(db, "v")).get().getTableLocation());
  }

  @Test
  public void aRenameToAnotherDatabaseOrToItsOwnNameIsTheCallersErrorAndChangesNothing() {
    String from = db("from");
    String to = db("to");
    createTable(from, "t");
    assertThrows(
        HouseTableCallerException.class,
        () -> repository().rename(from, "t", to, "u", location(to, "u", 1)));
    assertThrows(
        HouseTableCallerException.class,
        () -> repository().rename(from, "t", from.toUpperCase(), "T", location(from, "T", 1)));
    assertThrows(
        HouseTableCallerException.class,
        () -> repository().rename(from, "t", from, "a-b", location(from, "a-b", 1)));
    assertEquals(
        location(from, "t", 0), repository().findById(key(from, "t")).get().getTableLocation());
    assertEquals("t", repository().findById(key(from, "t")).get().getTableId());
    assertFalse(repository().findEntityById(key(to, "u")).isPresent());
    assertFalse(repository().findEntityById(key(from, "u")).isPresent());
  }

  // restore and the soft-deleted store

  @Test
  public void aRestoreBringsTheSoftDeletedRowBack() {
    String db = db("db");
    repository()
        .save(
            row(db, "t", INITIAL_TABLE_VERSION, location(db, "t", 0))
                .toBuilder()
                .storageType("local")
                .creationTime(7L)
                .build());
    repository().deleteById(key(db, "t"), false);
    long deletedAtMs = softDeleted(db, "t").getContent().get(0).getDeletedAtMs();
    repository().restoreTable(db.toUpperCase(), "T", deletedAtMs);
    HouseTable restored = repository().findById(key(db, "t")).get();
    assertEquals(location(db, "t", 0), restored.getTableLocation());
    assertEquals(location(db, "t", 0), restored.getTableVersion());
    assertEquals("local", restored.getStorageType());
    assertEquals(7L, restored.getCreationTime());
    assertEquals(ENTITY_TYPE_TABLE, restored.getEntityType());
    assertEquals(0, softDeleted(db, "t").getTotalElements());
  }

  @Test
  public void aRestoreOverALiveTableIsAConflictAndChangesNothing() {
    String db = db("db");
    createTable(db, "t");
    repository().deleteById(key(db, "t"), false);
    long deletedAtMs = softDeleted(db, "t").getContent().get(0).getDeletedAtMs();
    repository().save(row(db, "T", INITIAL_TABLE_VERSION, location(db, "t", 5)));
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository().restoreTable(db, "t", deletedAtMs));
    assertEquals(
        location(db, "t", 5), repository().findById(key(db, "t")).get().getTableLocation());
    assertEquals(1, softDeleted(db, "t").getTotalElements());
  }

  @Test
  public void aRestoreOverAViewIsAConflict() {
    String db = db("db");
    createTable(db, "t");
    repository().deleteById(key(db, "t"), false);
    long deletedAtMs = softDeleted(db, "t").getContent().get(0).getDeletedAtMs();
    createView(db, "t");
    assertThrows(
        HouseTableConcurrentUpdateException.class,
        () -> repository().restoreTable(db, "t", deletedAtMs));
    assertTrue(repository().findViewById(key(db, "t")).isPresent());
    assertEquals(1, softDeleted(db, "t").getTotalElements());
  }

  @Test
  public void aRestoreOfAMissingSoftDeletedRowIsNotFound() {
    String db = db("db");
    createTable(db, "t");
    repository().deleteById(key(db, "t"), false);
    long deletedAtMs = softDeleted(db, "t").getContent().get(0).getDeletedAtMs();
    assertThrows(
        HouseTableNotFoundException.class,
        () -> repository().restoreTable(db, "t", deletedAtMs + 1));
    assertThrows(
        HouseTableNotFoundException.class,
        () -> repository().restoreTable(db, "other", deletedAtMs));
    assertFalse(repository().findEntityById(key(db, "t")).isPresent());
  }

  @Test
  public void theSoftDeletedListingIgnoresCaseSortsAsAskedAndPagesInTheStore() {
    String db = db("Db");
    for (String table : Arrays.asList("c", "a", "b")) {
      createTable(db, table);
      repository().deleteById(key(db, table), false);
    }
    createTable(db("other"), "a");
    repository().deleteById(key(db("other"), "a"), false);

    Sort byTableId = Sort.by(Sort.Direction.ASC, "tableId");
    Page<HouseTable> first =
        repository()
            .searchSoftDeletedTables(db.toUpperCase(), null, PageRequest.of(0, 2, byTableId));
    assertEquals(Arrays.asList("a", "b"), tableIds(first.getContent()));
    assertEquals(3, first.getTotalElements());
    assertEquals(2, first.getTotalPages());
    assertEquals(2, first.getSize());
    assertEquals(0, first.getNumber());
    Page<HouseTable> second =
        repository()
            .searchSoftDeletedTables(db.toLowerCase(), null, PageRequest.of(1, 2, byTableId));
    assertEquals(Collections.singletonList("c"), tableIds(second.getContent()));
    assertEquals(3, second.getTotalElements());
    assertEquals(2, second.getSize());
    assertEquals(1, second.getNumber());
    Page<HouseTable> descending =
        repository()
            .searchSoftDeletedTables(
                db, null, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "tableId")));
    // The repository sends House Tables the sort properties only, so a direction is dropped.
    assertEquals(Arrays.asList("a", "b", "c"), tableIds(descending.getContent()));

    // Unsorted, the store promises the page size and the totals, not an order.
    Page<HouseTable> unsorted =
        repository().searchSoftDeletedTables(db, null, PageRequest.of(0, 2));
    assertEquals(2, unsorted.getContent().size());
    assertEquals(3, unsorted.getTotalElements());
    assertEquals(2, unsorted.getTotalPages());
    assertEquals(2, unsorted.getSize());

    Page<HouseTable> one = repository().searchSoftDeletedTables(db, "B", PageRequest.of(0, 10));
    assertEquals(Collections.singletonList("b"), tableIds(one.getContent()));
  }

  @Test
  public void aPurgeRemovesTheSoftDeletedRowsDueBeforeTheTimeByAnySpelling() {
    String db = db("db");
    createTable(db, "t");
    repository().deleteById(key(db, "t"), false);
    HouseTable archived = softDeleted(db, "t").getContent().get(0);

    repository().purgeSoftDeletedTables(db, "t", archived.getPurgeAfterMs());
    assertEquals(1, softDeleted(db, "t").getTotalElements(), "not due before that time");
    repository().purgeSoftDeletedTables(db.toUpperCase(), "T", archived.getPurgeAfterMs() + 1);
    assertEquals(0, softDeleted(db, "t").getTotalElements());
    // Nothing to purge is not an error.
    repository().purgeSoftDeletedTables(db, "t", archived.getPurgeAfterMs() + 1);
  }

  // listings

  @Test
  public void theTableListingIgnoresCaseAndHoldsOnlyTables() {
    String db = db("Db");
    createTable(db, "b");
    createTable(db, "a");
    createView(db, "v");
    createTable(db("other"), "c");
    List<String> listed = tableIds(repository().findAllByDatabaseId(db.toUpperCase()));
    Collections.sort(listed);
    assertEquals(Arrays.asList("a", "b"), listed);
    repository()
        .findAllByDatabaseId(db.toLowerCase())
        .forEach(
            it -> {
              assertEquals(db, it.getDatabaseId());
              assertEquals(ENTITY_TYPE_TABLE, it.getEntityType());
            });
  }

  @Test
  public void theTablePagesSortInTheStoreAndCountOnlyTables() {
    String db = db("db");
    for (String table : Arrays.asList("d", "b", "a", "c")) {
      createTable(db, table);
    }
    createView(db, "aa");

    Page<HouseTable> first =
        repository().findAllByDatabaseId(db.toUpperCase(), PageRequest.of(0, 3));
    assertEquals(Arrays.asList("a", "b", "c"), tableIds(first.getContent()));
    assertEquals(4, first.getTotalElements());
    assertEquals(2, first.getTotalPages());
    assertEquals(3, first.getSize());
    Page<HouseTable> second = repository().findAllByDatabaseId(db, PageRequest.of(1, 3));
    assertEquals(Collections.singletonList("d"), tableIds(second.getContent()));

    Page<HouseTable> sorted =
        repository()
            .findAllByDatabaseId(db, PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "tableId")));
    assertEquals(Arrays.asList("a", "b"), tableIds(sorted.getContent()));
    assertEquals(4, sorted.getTotalElements());
    Page<HouseTable> descending =
        repository()
            .findAllByDatabaseId(db, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "tableId")));
    assertEquals(Arrays.asList("a", "b"), tableIds(descending.getContent()));
  }

  @Test
  public void theViewPagesIgnoreCaseAndHoldOnlyViews() {
    String db = db("Db");
    createView(db, "w");
    createView(db, "v");
    createTable(db, "t");
    Page<HouseTable> views =
        repository().findAllViewsByDatabaseId(db.toLowerCase(), PageRequest.of(0, 10));
    assertEquals(Arrays.asList("v", "w"), tableIds(views.getContent()));
    assertEquals(2, views.getTotalElements());
    views.forEach(it -> assertEquals(ENTITY_TYPE_VIEW, it.getEntityType()));
    Page<HouseTable> first = repository().findAllViewsByDatabaseId(db, PageRequest.of(0, 1));
    assertEquals(Collections.singletonList("v"), tableIds(first.getContent()));
    assertEquals(2, first.getTotalPages());
  }

  @Test
  public void theDatabaseListNamesEachDatabaseOnceWhateverItHolds() {
    String tables = db("tables");
    String views = db("views");
    createTable(tables, "a");
    createTable(tables, "b");
    createView(views, "v");
    List<HouseTable> databases =
        StreamSupport.stream(repository().findAll().spliterator(), false)
            .filter(it -> it.getDatabaseId().endsWith(suffix))
            .collect(Collectors.toList());
    List<String> names =
        databases.stream().map(HouseTable::getDatabaseId).sorted().collect(Collectors.toList());
    assertEquals(Arrays.asList(tables, views), names);
    databases.forEach(it -> assertNull(it.getTableId(), "a database row names no table"));
  }
}
