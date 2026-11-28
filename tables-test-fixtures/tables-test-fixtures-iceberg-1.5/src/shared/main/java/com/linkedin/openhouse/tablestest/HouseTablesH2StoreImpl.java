package com.linkedin.openhouse.tablestest;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.internal.catalog.CatalogConstants.ENTITY_TYPE_TABLE;
import static com.linkedin.openhouse.internal.catalog.CatalogConstants.ENTITY_TYPE_VIEW;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.model.SoftDeletedTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableCallerException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableNotFoundException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The House Tables stand-in: what the real {@code HouseTableRepositoryImpl} answers over a House
 * Tables service ({@code UserTablesServiceImpl}), over rows in this application context's H2
 * database. {@code HouseTableRepositoryContract} holds this and the real store to the same rules.
 *
 * <ul>
 *   <li>Keys ignore letter case and keep the spelling they were created with, as the production
 *       tables' collation does.
 *   <li>A save is a compare-and-set on the metadata location: with no row at the key the version
 *       must be {@code INITIAL_TABLE_VERSION}, with one it must be the row's location; a row of the
 *       other entity type is a conflict whatever the version. A conflict is {@link
 *       HouseTableConcurrentUpdateException} (409 from the store).
 *   <li>A missing row is {@link HouseTableNotFoundException} (404) on the table read, delete,
 *       rename and restore; a restore over any live row, a rename onto any occupied key, and a
 *       value wider than its column are conflicts.
 *   <li>A name that is not letters, digits and underscores, an empty location, and a rename to
 *       another database or to the same name are {@link HouseTableCallerException} (400).
 *   <li>Only what House Tables keeps comes back: the key, the location (also as the version), the
 *       storage type ({@code hdfs} unless given), the creation time and the entity type.
 *   <li>Listings page and sort in the store, by the sort's properties only (the real repository
 *       does not send a direction), by table name when none is given.
 * </ul>
 *
 * <p>Every read is a projection, never a managed entity: an entity a caller's persistence context
 * already holds (a request's, say) would otherwise be served in place of the row another request
 * has since written. The store answers as a remote service does, whatever context calls it.
 *
 * <p>Where it differs from the real store, and why:
 *
 * <ul>
 *   <li>One lock per application context serializes every write, where the store has a row version
 *       per key; the outcomes are the same, the store only lets writes to different keys overlap.
 *   <li>Nothing here produces the client's 400/401/403/429 for a refused caller, its 5xx or DNS
 *       failures ({@code HouseTableRepositoryStateUnknownException}), or its retries.
 *   <li>The column widths are the ones H2 gives the entity, 255 for every string: the production
 *       tables' widths for the names and the location ({@code ddl/0000__baseline.sql}). The House
 *       Tables service's bootstrap schema ({@code schema.sql}) has 128 for the names and 512 for
 *       the location, and the production soft-deleted table 128 for the table name, which nothing
 *       here reproduces. A test that needs another store's widths alters the columns, as {@code
 *       HouseTableRowLimitsUnsetTest} does.
 *   <li>{@link #existsById} and {@link #deleteAll} serve tests; the real repository refuses both.
 *       The other methods the real repository refuses ({@code saveAll}, {@code count}, {@code
 *       findAll(Sort)}, {@code findAllById}, {@code deleteAllById}, {@code deleteAll(entities)},
 *       {@code delete(entity)}) are refused here too.
 *   <li>Names are checked as House Tables checks them (letters, digits and underscores) on the
 *       writes, the keyed reads and deletes, and the listings, but not on purge and restore, which
 *       the tables service reaches only with names it has checked.
 *   <li>Sorting ignores letter case as Java does ({@code String.CASE_INSENSITIVE_ORDER}); the
 *       production collation ({@code utf8mb4_0900_ai_ci}) puts punctuation before digits, so names
 *       that differ in an underscore against a digit ({@code a_} and {@code a1}) sort the other way
 *       there, which moves a page boundary only.
 *   <li>The soft-deleted rows are held in memory, outside the database transaction: a soft delete
 *       records its row after the row is deleted and a restore drops it after the row is written,
 *       so a failure of either write leaves them as they were, but a failed commit of the
 *       transaction itself is not undone in memory, where the store rolls both back together.
 * </ul>
 */
public class HouseTablesH2StoreImpl implements HouseTablesH2Store {

  /** Soft-deleted rows are kept for 7 days, as in the house tables service. */
  static final long SOFT_DELETE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

  private static final String COLUMNS =
      "h.databaseId, h.tableId, h.tableLocation, h.storageType, h.creationTime, h.entityType";

  private static final String KEY =
      "upper(h.databaseId) = upper(:databaseId) and upper(h.tableId) = upper(:tableId)";

  private static final String IN_DATABASE = "upper(h.databaseId) = upper(:databaseId)";

  /** The null arm is load-bearing: a row without a type predates the discriminator; a table. */
  private static final String TABLE_ROW = "(h.entityType is null or upper(h.entityType) = 'TABLE')";

  private static final String VIEW_ROW = "upper(h.entityType) = 'VIEW'";

  private static final Map<String, Function<HouseTable, Comparable<?>>> SORTABLE =
      sortableProperties();

  @PersistenceContext private EntityManager entityManager;

  /** Makes each read-compare-write atomic, as a row version does in the store. */
  private final ReentrantLock lock = new ReentrantLock();

  private final Map<SoftDeletedTablePrimaryKey, HouseTable> softDeleted =
      Collections.synchronizedMap(new LinkedHashMap<>());

  // writes

  @Override
  @Transactional
  @SuppressWarnings("unchecked")
  public <S extends HouseTable> S save(S entity) {
    return (S) put(entity, ENTITY_TYPE_TABLE);
  }

  @Override
  @Transactional
  public HouseTable saveView(HouseTable houseTable) {
    return put(houseTable, ENTITY_TYPE_VIEW);
  }

  /** {@code UserTablesServiceImpl.persistTypedEntity}: the type comes from the route. */
  private HouseTable put(HouseTable entity, String entityType) {
    requireValidName(entity.getDatabaseId(), entity.getTableId());
    if (entity.getTableLocation() == null || entity.getTableLocation().isEmpty()) {
      throw callerError("metadataLocation cannot be empty");
    }
    lockUntilCompletion();
    Optional<HouseTable> existing = read(entity.getDatabaseId(), entity.getTableId());
    if (existing.isPresent() && !entityType.equals(existing.get().getEntityType())) {
      throw conflict(
          "%s.%s is a %s",
          entity.getDatabaseId(), entity.getTableId(), existing.get().getEntityType());
    }
    String current = existing.map(HouseTable::getTableLocation).orElse(INITIAL_TABLE_VERSION);
    if (!current.equals(entity.getTableVersion())) {
      throw conflict(
          "databaseId : %s, tableId : %s, version: %s The requested user table has been"
              + " modified/created by other processes.",
          entity.getDatabaseId(), entity.getTableId(), entity.getTableVersion());
    }
    String storageType = entity.getStorageType() == null ? "hdfs" : entity.getStorageType();
    if (existing.isPresent()) {
      fitting(
          () ->
              entityManager
                  .createQuery(
                      "update HouseTable h set h.tableLocation = :location, h.tableVersion = :location,"
                          + " h.storageType = :storageType, h.creationTime = :creationTime,"
                          + " h.entityType = :entityType where "
                          + KEY)
                  .setParameter("location", entity.getTableLocation())
                  .setParameter("storageType", storageType)
                  .setParameter("creationTime", entity.getCreationTime())
                  .setParameter("entityType", entityType)
                  .setParameter("databaseId", entity.getDatabaseId())
                  .setParameter("tableId", entity.getTableId())
                  .executeUpdate());
    } else {
      insert(
          HouseTable.builder()
              .databaseId(entity.getDatabaseId())
              .tableId(entity.getTableId())
              .tableLocation(entity.getTableLocation())
              .tableVersion(entity.getTableLocation())
              .storageType(storageType)
              .creationTime(entity.getCreationTime())
              .entityType(entityType)
              .build());
    }
    return read(entity.getDatabaseId(), entity.getTableId()).get();
  }

  @Override
  @Transactional
  public void deleteById(HouseTablePrimaryKey key) {
    deleteById(key, true);
  }

  /**
   * The real client sends {@code !purge} as the soft-delete flag, so {@code purge} is a hard
   * delete; either way only a table is deleted.
   */
  @Override
  @Transactional
  public void deleteById(HouseTablePrimaryKey key, boolean purge) {
    requireValidName(key.getDatabaseId(), key.getTableId());
    lockUntilCompletion();
    HouseTable table =
        read(key.getDatabaseId(), key.getTableId())
            .filter(HouseTablesH2StoreImpl::isTable)
            .orElseThrow(() -> notFound(key.getDatabaseId(), key.getTableId()));
    // The row first: a failure there leaves the soft-deleted store as it was.
    deleteRows(key.getDatabaseId(), key.getTableId(), TABLE_ROW);
    if (!purge) {
      long deletedAtMs = System.currentTimeMillis();
      softDeleted.put(
          SoftDeletedTablePrimaryKey.builder()
              .databaseId(table.getDatabaseId())
              .tableId(table.getTableId())
              .deletedAtMs(deletedAtMs)
              .build(),
          table
              .toBuilder()
              .entityType(null)
              .deletedAtMs(deletedAtMs)
              .purgeAfterMs(deletedAtMs + SOFT_DELETE_RETENTION_MS)
              .build());
    }
  }

  @Override
  public void delete(HouseTable entity) {
    throw new UnsupportedOperationException("Entity deletion is not supported.");
  }

  @Override
  @Transactional
  public void deleteAll() {
    lockUntilCompletion();
    entityManager.createQuery("delete from HouseTable h").executeUpdate();
    softDeleted.clear();
  }

  @Override
  @Transactional
  public boolean deleteViewById(HouseTablePrimaryKey key) {
    requireValidName(key.getDatabaseId(), key.getTableId());
    lockUntilCompletion();
    return deleteRows(key.getDatabaseId(), key.getTableId(), VIEW_ROW) != 0;
  }

  /**
   * {@code UserTablesServiceImpl.renameUserTable}: one conditional update of a table row, which
   * keeps the source's database (as the caller spelled it) and refuses an occupied key.
   */
  @Override
  @Transactional
  public void rename(
      String fromDatabaseId,
      String fromTableId,
      String toDatabaseId,
      String toTableId,
      String metadataLocation) {
    requireValidName(fromDatabaseId, fromTableId);
    requireValidName(toDatabaseId, toTableId);
    if (!fromDatabaseId.equalsIgnoreCase(toDatabaseId)) {
      throw callerError(
          String.format(
              "Cross database rename is not supported: %s.%s to %s.%s",
              fromDatabaseId, fromTableId, toDatabaseId, toTableId));
    }
    if (fromTableId.equalsIgnoreCase(toTableId)) {
      throw callerError(
          String.format(
              "Cannot rename a table to the same current db name and table name: %s.%s",
              fromDatabaseId, fromTableId));
    }
    lockUntilCompletion();
    if (!read(fromDatabaseId, fromTableId).filter(HouseTablesH2StoreImpl::isTable).isPresent()) {
      throw notFound(fromDatabaseId, fromTableId);
    }
    if (read(fromDatabaseId, toTableId).isPresent()) {
      throw conflict(
          "Cannot rename %s.%s to %s: the key is held by another entity",
          fromDatabaseId, fromTableId, toTableId);
    }
    fitting(
        () ->
            entityManager
                .createQuery(
                    "update HouseTable h set h.databaseId = :toDatabaseId, h.tableId = :toTableId,"
                        + " h.tableLocation = :location, h.tableVersion = :location,"
                        + " h.entityType = 'TABLE' where "
                        + KEY
                        + " and "
                        + TABLE_ROW)
                .setParameter("toDatabaseId", fromDatabaseId)
                .setParameter("toTableId", toTableId)
                .setParameter("location", metadataLocation)
                .setParameter("databaseId", fromDatabaseId)
                .setParameter("tableId", fromTableId)
                .executeUpdate());
  }

  /** {@code UserTablesServiceImpl.restoreUserTable}: refused over any live row at the key. */
  @Override
  @Transactional
  public void restoreTable(String databaseId, String tableId, long deletedAtMs) {
    lockUntilCompletion();
    if (read(databaseId, tableId).isPresent()) {
      throw conflict("Table %s.%s already exists", databaseId, tableId);
    }
    SoftDeletedTablePrimaryKey key =
        softDeletedKey(databaseId, tableId, deletedAtMs)
            .orElseThrow(() -> notFound(databaseId, tableId));
    HouseTable archived = softDeleted.get(key);
    // The row first: a failure there leaves the soft-deleted row in its store.
    insert(
        archived
            .toBuilder()
            .tableVersion(archived.getTableLocation())
            .entityType(ENTITY_TYPE_TABLE)
            .deletedAtMs(0)
            .purgeAfterMs(0)
            .build());
    softDeleted.remove(key);
  }

  @Override
  public void purgeSoftDeletedTables(String databaseId, String tableId, long purgeAfterMs) {
    synchronized (softDeleted) {
      softDeleted
          .entrySet()
          .removeIf(
              entry ->
                  entry.getKey().getDatabaseId().equalsIgnoreCase(databaseId)
                      && entry.getKey().getTableId().equalsIgnoreCase(tableId)
                      && entry.getValue().getPurgeAfterMs() < purgeAfterMs);
    }
  }

  // reads

  @Override
  public Optional<HouseTable> findById(HouseTablePrimaryKey key) {
    requireValidName(key.getDatabaseId(), key.getTableId());
    return Optional.of(
        read(key.getDatabaseId(), key.getTableId())
            .filter(HouseTablesH2StoreImpl::isTable)
            .orElseThrow(() -> notFound(key.getDatabaseId(), key.getTableId())));
  }

  @Override
  public Optional<HouseTable> findEntityById(HouseTablePrimaryKey key) {
    requireValidName(key.getDatabaseId(), key.getTableId());
    return read(key.getDatabaseId(), key.getTableId());
  }

  @Override
  public Optional<HouseTable> findViewById(HouseTablePrimaryKey key) {
    requireValidName(key.getDatabaseId(), key.getTableId());
    return read(key.getDatabaseId(), key.getTableId()).filter(HouseTablesH2StoreImpl::isView);
  }

  @Override
  public boolean existsById(HouseTablePrimaryKey key) {
    return read(key.getDatabaseId(), key.getTableId()).isPresent();
  }

  @Override
  public List<HouseTable> findAllByDatabaseId(String databaseId) {
    requireListableDatabase(databaseId);
    return sorted(inDatabase(databaseId, TABLE_ROW), Sort.unsorted(), "tableId");
  }

  @Override
  public Page<HouseTable> findAllByDatabaseId(String databaseId, Pageable pageable) {
    requireListableDatabase(databaseId);
    return page(sorted(inDatabase(databaseId, TABLE_ROW), pageable.getSort(), "tableId"), pageable);
  }

  @Override
  public Page<HouseTable> findAllViewsByDatabaseId(String databaseId, Pageable pageable) {
    if (databaseId != null && !databaseId.matches(ALPHA_NUM_UNDERSCORE_REGEX)) {
      throw callerError(databaseId + ": a name is letters, digits and underscores");
    }
    return page(sorted(inDatabase(databaseId, VIEW_ROW), pageable.getSort(), "tableId"), pageable);
  }

  @Override
  public Iterable<HouseTable> findAll() {
    return databases();
  }

  @Override
  public Page<HouseTable> findAll(Pageable pageable) {
    return page(sorted(databases(), pageable.getSort(), "databaseId"), pageable);
  }

  /**
   * As the House Tables service sorts it: by the properties asked for, else by database, which
   * leaves the order of one database's rows to the store.
   */
  @Override
  public Page<HouseTable> searchSoftDeletedTables(
      String databaseId, String tableId, Pageable pageable) {
    requireListableDatabase(databaseId);
    List<HouseTable> rows;
    synchronized (softDeleted) {
      rows =
          softDeleted.values().stream()
              .filter(row -> databaseId == null || row.getDatabaseId().equalsIgnoreCase(databaseId))
              .filter(row -> tableId == null || row.getTableId().equalsIgnoreCase(tableId))
              .collect(Collectors.toList());
    }
    return page(sorted(rows, pageable.getSort(), "databaseId"), pageable);
  }

  @Override
  public Map<SoftDeletedTablePrimaryKey, HouseTable> softDeletedTables() {
    return softDeleted;
  }

  // what the real repository refuses

  @Override
  public <S extends HouseTable> Iterable<S> saveAll(Iterable<S> entities) {
    throw new UnsupportedOperationException("saveAll is not supported.");
  }

  @Override
  public long count() {
    throw new UnsupportedOperationException("Count is not supported.");
  }

  @Override
  public Iterable<HouseTable> findAll(Sort sort) {
    throw new UnsupportedOperationException("FindAll by Sort is not supported.");
  }

  @Override
  public Iterable<HouseTable> findAllById(Iterable<HouseTablePrimaryKey> keys) {
    throw new UnsupportedOperationException("FindAllById is not supported.");
  }

  @Override
  public void deleteAllById(Iterable<? extends HouseTablePrimaryKey> keys) {
    throw new UnsupportedOperationException("deleteAllById is not supported.");
  }

  @Override
  public void deleteAll(Iterable<? extends HouseTable> entities) {
    throw new UnsupportedOperationException("Entity-deleteAll is not supported.");
  }

  // the rows

  /** The row at the key, read from the database, never from a persistence context. */
  private Optional<HouseTable> read(String databaseId, String tableId) {
    return entityManager
        .createQuery("select " + COLUMNS + " from HouseTable h where " + KEY, Object[].class)
        .setParameter("databaseId", databaseId).setParameter("tableId", tableId).getResultList()
        .stream()
        .map(HouseTablesH2StoreImpl::row)
        .findFirst();
  }

  private List<HouseTable> inDatabase(String databaseId, String typePredicate) {
    return entityManager
        .createQuery(
            "select "
                + COLUMNS
                + " from HouseTable h where "
                + IN_DATABASE
                + " and "
                + typePredicate,
            Object[].class)
        .setParameter("databaseId", databaseId).getResultList().stream()
        .map(HouseTablesH2StoreImpl::row)
        .collect(Collectors.toList());
  }

  /** One row per database, naming only the database, as the House Tables database list does. */
  private List<HouseTable> databases() {
    Map<String, HouseTable> databases = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (String databaseId :
        entityManager
            .createQuery("select distinct h.databaseId from HouseTable h", String.class)
            .getResultList()) {
      databases.putIfAbsent(databaseId, HouseTable.builder().databaseId(databaseId).build());
    }
    return new ArrayList<>(databases.values());
  }

  /** What House Tables keeps and answers; a row without a type is a table. */
  private static HouseTable row(Object[] columns) {
    String location = (String) columns[2];
    return HouseTable.builder()
        .databaseId((String) columns[0])
        .tableId((String) columns[1])
        .tableLocation(location)
        .tableVersion(location)
        .storageType((String) columns[3])
        .creationTime(columns[4] == null ? 0L : (Long) columns[4])
        .entityType(columns[5] == null ? ENTITY_TYPE_TABLE : ((String) columns[5]).toUpperCase())
        .build();
  }

  /** Flushed and detached, so no persistence context keeps it for a later read. */
  private void insert(HouseTable row) {
    fitting(
        () -> {
          entityManager.persist(row);
          entityManager.flush();
          entityManager.detach(row);
          return 1;
        });
  }

  /**
   * A value wider than its column, or a key the table holds already, is a data integrity violation,
   * which House Tables answers with a conflict (409).
   */
  private static int fitting(IntSupplier write) {
    try {
      return write.getAsInt();
    } catch (PersistenceException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        String state = cause instanceof SQLException ? ((SQLException) cause).getSQLState() : null;
        if (state != null && (state.startsWith("22") || state.startsWith("23"))) {
          throw conflict("The row does not fit the store: %s", cause.getMessage());
        }
      }
      throw e;
    }
  }

  private int deleteRows(String databaseId, String tableId, String typePredicate) {
    return entityManager
        .createQuery("delete from HouseTable h where " + KEY + " and " + typePredicate)
        .setParameter("databaseId", databaseId)
        .setParameter("tableId", tableId)
        .executeUpdate();
  }

  private Optional<SoftDeletedTablePrimaryKey> softDeletedKey(
      String databaseId, String tableId, long deletedAtMs) {
    synchronized (softDeleted) {
      return softDeleted.keySet().stream()
          .filter(
              key ->
                  key.getDatabaseId().equalsIgnoreCase(databaseId)
                      && key.getTableId().equalsIgnoreCase(tableId)
                      && key.getDeletedAtMs() == deletedAtMs)
          .findFirst();
    }
  }

  private static boolean isTable(HouseTable row) {
    return ENTITY_TYPE_TABLE.equals(row.getEntityType());
  }

  private static boolean isView(HouseTable row) {
    return ENTITY_TYPE_VIEW.equals(row.getEntityType());
  }

  /**
   * Held until the transaction completes: releasing it on return would let a second writer read the
   * row before the first one commits.
   */
  private void lockUntilCompletion() {
    lock.lock();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            lock.unlock();
          }
        });
  }

  // the store's answers

  /**
   * The table listings and the soft-deleted listing of the real repository do not translate the
   * House Tables service's 400 for a name it refuses: the caller gets the client's own exception.
   */
  private static void requireListableDatabase(String databaseId) {
    if (databaseId != null && !databaseId.matches(ALPHA_NUM_UNDERSCORE_REGEX)) {
      throw WebClientResponseException.create(
          400, "Bad Request", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }
  }

  private static void requireValidName(String databaseId, String tableId) {
    for (String name : new String[] {databaseId, tableId}) {
      if (name == null || !name.matches(ALPHA_NUM_UNDERSCORE_REGEX)) {
        throw callerError(
            String.format("%s.%s: a name is letters, digits and underscores", databaseId, tableId));
      }
    }
  }

  private static HouseTableCallerException callerError(String message) {
    return new HouseTableCallerException(
        "[Client side failure]Error status code for HTS:400 BAD_REQUEST " + message, null);
  }

  private static HouseTableConcurrentUpdateException conflict(String format, Object... args) {
    return new HouseTableConcurrentUpdateException(String.format(format, args), null);
  }

  private static HouseTableNotFoundException notFound(String databaseId, String tableId) {
    return new HouseTableNotFoundException(
        String.format("User table %s.%s cannot be found", databaseId, tableId), null);
  }

  // listings

  /**
   * Sorted by the sort's properties, ascending whatever its direction, as the real repository sends
   * only the names; by {@code defaultProperty} when it names none.
   */
  private static List<HouseTable> sorted(List<HouseTable> rows, Sort sort, String defaultProperty) {
    List<String> properties =
        sort.isSorted()
            ? sort.stream().map(Sort.Order::getProperty).collect(Collectors.toList())
            : Collections.singletonList(defaultProperty);
    Comparator<HouseTable> order = null;
    for (String property : properties) {
      Function<HouseTable, Comparable<?>> value = SORTABLE.get(property);
      if (value == null) {
        throw new IllegalArgumentException("No property " + property + " found for HouseTable");
      }
      Comparator<HouseTable> next = Comparator.comparing(value, HouseTablesH2StoreImpl::compare);
      order = order == null ? next : order.thenComparing(next);
    }
    List<HouseTable> copy = new ArrayList<>(rows);
    copy.sort(order);
    return copy;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compare(Comparable left, Comparable right) {
    if (left == null || right == null) {
      return left == null ? (right == null ? 0 : -1) : 1;
    }
    if (left instanceof String) {
      return String.CASE_INSENSITIVE_ORDER.compare((String) left, (String) right);
    }
    return left.compareTo(right);
  }

  private static Map<String, Function<HouseTable, Comparable<?>>> sortableProperties() {
    Map<String, Function<HouseTable, Comparable<?>>> properties = new LinkedHashMap<>();
    properties.put("databaseId", HouseTable::getDatabaseId);
    properties.put("tableId", HouseTable::getTableId);
    properties.put("tableLocation", HouseTable::getTableLocation);
    properties.put("storageType", HouseTable::getStorageType);
    properties.put("creationTime", HouseTable::getCreationTime);
    properties.put("deletedAtMs", HouseTable::getDeletedAtMs);
    properties.put("purgeAfterMs", HouseTable::getPurgeAfterMs);
    return properties;
  }

  /** The page the House Tables service answers: its own number and size, no sort. */
  private static Page<HouseTable> page(List<HouseTable> sortedRows, Pageable pageable) {
    int from = Math.min(pageable.getPageNumber() * pageable.getPageSize(), sortedRows.size());
    int to = Math.min(from + pageable.getPageSize(), sortedRows.size());
    return new PageImpl<>(
        new ArrayList<>(sortedRows.subList(from, to)),
        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()),
        sortedRows.size());
  }
}
