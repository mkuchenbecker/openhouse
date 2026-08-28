package com.linkedin.openhouse.housetables.services;

import static com.linkedin.openhouse.common.utils.PageableUtil.createPageable;

import com.linkedin.openhouse.cluster.metrics.micrometer.MetricsReporter;
import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.exception.NoSuchSoftDeletedUserTableException;
import com.linkedin.openhouse.common.exception.NoSuchUserTableException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.metrics.MetricsConstant;
import com.linkedin.openhouse.housetables.api.spec.model.UserTable;
import com.linkedin.openhouse.housetables.dto.mapper.SoftDeletedUserTablesMapper;
import com.linkedin.openhouse.housetables.dto.mapper.UserTablesMapper;
import com.linkedin.openhouse.housetables.dto.model.UserTableDto;
import com.linkedin.openhouse.housetables.model.EntityType;
import com.linkedin.openhouse.housetables.model.SoftDeletedUserTableRow;
import com.linkedin.openhouse.housetables.model.SoftDeletedUserTableRowPrimaryKey;
import com.linkedin.openhouse.housetables.model.UserTableRow;
import com.linkedin.openhouse.housetables.model.UserTableRowPrimaryKey;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.JdbcPersistenceFailures;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.SoftDeletedUserTableHtsJdbcRepository;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.UserTableHtsJdbcRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.util.Pair;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class UserTablesServiceImpl implements UserTablesService {

  @Autowired UserTableHtsJdbcRepository htsJdbcRepository;

  @Autowired UserTablesMapper userTablesMapper;

  @Autowired SoftDeletedUserTableHtsJdbcRepository softDeletedHtsJdbcRepository;

  @Autowired SoftDeletedUserTablesMapper softDeletedUserTablesMapper;

  private static final MetricsReporter METRICS_REPORTER =
      MetricsReporter.of(MetricsConstant.HOUSETABLES_SERVICE);
  @Autowired private SoftDeletedUserTableHtsJdbcRepository softDeletedUserTableHtsJdbcRepository;

  /**
   * Every method whose repository interaction hydrates rows runs under {@link
   * JdbcPersistenceFailures#surfacingCorruption}, so a corrupt {@code entity_type} leaves this
   * service as the module-owned {@link
   * com.linkedin.openhouse.common.exception.CorruptEntityTypeException} rather than as the ORM
   * wrapper JPA translation hands back.
   */
  @Override
  public UserTableDto getUserTable(String databaseId, String tableId) {
    return JdbcPersistenceFailures.surfacingCorruption(
        () -> {
          UserTableRow userTableRow;

          try {
            userTableRow =
                htsJdbcRepository
                    .findTableByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(databaseId, tableId)
                    .orElseThrow(NoSuchElementException::new);
          } catch (NoSuchElementException ne) {
            throw new NoSuchUserTableException(databaseId, tableId, ne);
          }

          return userTablesMapper.toUserTableDto(userTableRow);
        });
  }

  @Override
  public UserTableDto getNeutralEntity(String databaseId, String tableId) {
    // Only an empty Optional is absence; repository and hydration failures must escape, because
    // reporting a broken row as "free" is how an occupant gets overwritten.
    return JdbcPersistenceFailures.surfacingCorruption(
        () ->
            htsJdbcRepository
                .findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(databaseId, tableId)
                .map(userTablesMapper::toUserTableDto)
                .orElseThrow(
                    () -> new NoSuchEntityException("Entity", databaseId + "." + tableId)));
  }

  @Override
  public UserTableDto getUserView(String databaseId, String tableId) {
    return JdbcPersistenceFailures.surfacingCorruption(
        () ->
            htsJdbcRepository
                .findViewByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(databaseId, tableId)
                .map(userTablesMapper::toUserTableDto)
                .orElseThrow(() -> new NoSuchEntityException("View", databaseId + "." + tableId)));
  }

  @Override
  public List<UserTableDto> getAllUserTables(UserTable userTable) {
    return JdbcPersistenceFailures.surfacingCorruption(
        () -> {
          if (isListDatabases(userTable)) {
            return listDatabases();
          } else if (isListTables(userTable)) {
            return listTables(userTable);
          } else if (isListTablesWithPattern(userTable)) {
            return listTablesWithPattern(userTable);
          } else {
            return searchTables(userTable);
          }
        });
  }

  @Override
  public Page<UserTableDto> getAllUserTables(
      UserTable userTable, int page, int size, String sortBy) {
    return JdbcPersistenceFailures.surfacingCorruption(
        () -> {
          if (isListDatabases(userTable)) {
            return listDatabases(page, size, sortBy);
          } else if (isListTables(userTable)) {
            return listTables(userTable, page, size, sortBy);
          } else if (isListTablesWithPattern(userTable)) {
            return listTablesWithPattern(userTable, page, size, sortBy);
          } else {
            return searchTables(userTable, page, size, sortBy);
          }
        });
  }

  @Override
  public List<UserTableDto> getAllUserViews(UserViewQuery userViewQuery) {
    return JdbcPersistenceFailures.surfacingCorruption(
        () -> {
          if (userViewQuery.getTableIdPattern() == null) {
            return listViews(userViewQuery.getDatabaseId());
          }
          return listViewsWithPattern(requireDatabaseId(userViewQuery));
        });
  }

  @Override
  public Page<UserTableDto> getAllUserViews(
      UserViewQuery userViewQuery, int page, int size, String sortBy) {
    return JdbcPersistenceFailures.surfacingCorruption(
        () -> {
          if (userViewQuery.getTableIdPattern() == null) {
            return listViews(userViewQuery.getDatabaseId(), page, size, sortBy);
          }
          return listViewsWithPattern(requireDatabaseId(userViewQuery), page, size, sortBy);
        });
  }

  /** The API validator enforces the same rule at ingress; this repeats it for direct callers. */
  private static UserViewQuery requireDatabaseId(UserViewQuery userViewQuery) {
    if (userViewQuery.getDatabaseId() == null) {
      throw new IllegalArgumentException(
          "A view query with a tableIdPattern requires a databaseId");
    }
    return userViewQuery;
  }

  @Override
  public Pair<UserTableDto, Boolean> putUserTable(UserTable userTable) {
    return JdbcPersistenceFailures.surfacingCorruption(() -> put(userTable, EntityType.TABLE));
  }

  @Override
  public Pair<UserTableDto, Boolean> putUserView(UserTable userView) {
    return JdbcPersistenceFailures.surfacingCorruption(() -> put(userView, EntityType.VIEW));
  }

  /**
   * The one persistence primitive behind both typed entry points. The entry point supplies {@code
   * entityType}; the payload's wire field may agree with it or stay silent, and a contradiction is
   * rejected here so the typed methods establish the invariant their names promise even for callers
   * that bypass the controller's own mismatch check.
   */
  private Pair<UserTableDto, Boolean> put(UserTable userTable, EntityType entityType) {
    if (userTable.getEntityType() != null
        && userTablesMapper.toEntityType(userTable.getEntityType()) != entityType) {
      throw new RequestValidationFailureException(
          String.format(
              "entityType provided: %s, but this operation writes %s only",
              userTable.getEntityType(), entityType.name()));
    }
    UserTable stampedUserTable = userTable.toBuilder().entityType(entityType.name()).build();

    Optional<UserTableRow> existingUserTableRow =
        htsJdbcRepository.findById(
            UserTableRowPrimaryKey.builder()
                .databaseId(stampedUserTable.getDatabaseId())
                .tableId(stampedUserTable.getTableId())
                .build());

    // Compared before any version mapping runs: a wrong-type collision is not a stale write.
    if (existingUserTableRow.isPresent()
        && existingUserTableRow.get().getEntityType() != entityType) {
      throw new AlreadyExistsException(
          existingUserTableRow.get().getEntityType().name(),
          stampedUserTable.getDatabaseId() + "." + stampedUserTable.getTableId());
    }

    UserTableRow targetUserTableRow =
        userTablesMapper.toUserTableRow(stampedUserTable, existingUserTableRow);
    UserTableDto returnedDto;

    try {
      returnedDto = userTablesMapper.toUserTableDto(htsJdbcRepository.save(targetUserTableRow));
    } catch (DataIntegrityViolationException e) {
      // Only a duplicate key means another writer holds the row. Any other integrity violation
      // is a server-side failure, not a race, so mislabeling it a 409 would send the caller into
      // a futile retry loop; it propagates as the 500 it is.
      if (!JdbcPersistenceFailures.isDuplicateKey(e)) {
        throw e;
      }
      throw concurrentModification(targetUserTableRow, userTable, e);
    } catch (CommitFailedException | ObjectOptimisticLockingFailureException e) {
      throw concurrentModification(targetUserTableRow, userTable, e);
    }

    return Pair.of(returnedDto, existingUserTableRow.isPresent());
  }

  private EntityConcurrentModificationException concurrentModification(
      UserTableRow targetUserTableRow, UserTable userTable, Exception cause) {
    return new EntityConcurrentModificationException(
        String.format(
            "databaseId : %s, tableId : %s, version: %s %s",
            targetUserTableRow.getDatabaseId(),
            targetUserTableRow.getTableId(),
            targetUserTableRow.getVersion(),
            "The requested user table has been modified/created by other processes."),
        userTablesMapper.fromUserTableToRowKey(userTable).toString(),
        cause);
  }

  /**
   * Renames a user table within the same database.
   *
   * @param fromDatabaseId The databaseId of the row to rename.
   * @param fromTableId The tableId of the row to rename.
   * @param toDatabaseId Until rename support across databases is supported, this should be the same
   *     as fromDatabaseId
   * @param toTableId The new tableId of the renamed row.
   * @param metadataLocation The new metadata file of the table with updated table properties that
   *     match the new tableId
   */
  @Override
  public void renameUserTable(
      String fromDatabaseId,
      String fromTableId,
      String toDatabaseId,
      String toTableId,
      String metadataLocation) {
    // No source precheck: the conditional update is the check, which closes the TOCTOU window.
    try {
      log.info(
          "Renaming user table from {}.{} to {}.{}",
          fromDatabaseId,
          fromTableId,
          toTableId,
          toDatabaseId);
      // Use fromDatabaseId for destination db to preserve the original case of the database
      // TODO: Use toDataBaseId for destination instead of fromDatabaseId once rename across
      // databases is supported
      if (htsJdbcRepository.renameTableId(
              fromDatabaseId, fromTableId, fromDatabaseId, toTableId, metadataLocation)
          == 0) {
        throw new NoSuchUserTableException(fromDatabaseId, fromTableId);
      }
    } catch (DataIntegrityViolationException e) {
      // A duplicate key means the destination is occupied; anything else broke a different
      // constraint and must not masquerade as an occupied destination.
      if (!JdbcPersistenceFailures.isDuplicateKey(e)) {
        throw e;
      }
      throw new AlreadyExistsException("Table", toTableId, e);
    }
  }

  @Override
  @Transactional
  public void deleteUserTable(String databaseId, String tableId, boolean isSoftDeleted) {
    JdbcPersistenceFailures.surfacingCorruption(
        () -> {
          UserTableRowPrimaryKey key =
              UserTableRowPrimaryKey.builder().databaseId(databaseId).tableId(tableId).build();
          if (isSoftDeleted) {
            UserTableRow existingTable =
                htsJdbcRepository
                    .findTableByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(databaseId, tableId)
                    .orElseThrow(() -> new NoSuchUserTableException(databaseId, tableId));
            softDeletedHtsJdbcRepository.save(
                softDeletedUserTablesMapper.toSoftDeletedUserTableRow(existingTable));
          }
          // Throwing inside the transaction rolls the copy above back, so a row that loses the
          // race is not left behind in the soft-deleted store.
          if (htsJdbcRepository.deleteTableById(key) == 0) {
            throw new NoSuchUserTableException(databaseId, tableId);
          }
        });
  }

  @Override
  public void deleteUserView(String databaseId, String tableId) {
    if (htsJdbcRepository.deleteViewById(
            UserTableRowPrimaryKey.builder().databaseId(databaseId).tableId(tableId).build())
        == 0) {
      throw new NoSuchEntityException("View", databaseId + "." + tableId);
    }
  }

  /**
   * Moves a soft deleted user table back to the user tables repository.
   *
   * @param databaseId
   * @param tableId
   * @param deletedAt
   * @return
   */
  @Override
  @Transactional
  public UserTableDto restoreUserTable(String databaseId, String tableId, Long deletedAt) {
    Optional<UserTableRow> existingUserTable =
        JdbcPersistenceFailures.surfacingCorruption(
            () ->
                htsJdbcRepository.findById(
                    UserTableRowPrimaryKey.builder()
                        .databaseId(databaseId)
                        .tableId(tableId)
                        .build()));
    if (existingUserTable.isPresent()) {
      // If the table already exists, we throw an exception
      throw new AlreadyExistsException("Table", existingUserTable.get().getTableId());
    }
    SoftDeletedUserTableRowPrimaryKey softDeletedTableKey =
        SoftDeletedUserTableRowPrimaryKey.builder()
            .databaseId(databaseId)
            .tableId(tableId)
            .deletedAtMs(deletedAt)
            .build();
    SoftDeletedUserTableRow existingSoftDeletedTable =
        softDeletedHtsJdbcRepository
            .findById(softDeletedTableKey)
            .orElseThrow(
                () -> new NoSuchSoftDeletedUserTableException(databaseId, tableId, deletedAt));

    try {
      softDeletedHtsJdbcRepository.deleteById(softDeletedTableKey);
      return JdbcPersistenceFailures.surfacingCorruption(
          () ->
              userTablesMapper.toUserTableDto(
                  htsJdbcRepository.save(
                      userTablesMapper.toUserTableRow(existingSoftDeletedTable))));
    } catch (DataIntegrityViolationException e) {
      // Same discrimination as the put path: only a duplicate key is a lost restore race.
      if (!JdbcPersistenceFailures.isDuplicateKey(e)) {
        throw e;
      }
      throw new AlreadyExistsException("Table", existingSoftDeletedTable.getTableId(), e);
    }
  }

  /**
   * Deletes all soft deleted user tables for a given databaseId and tableId that have a
   * purgeAfterMs earlier than purgeAfterMs.
   *
   * @param databaseId The database ID of the soft deleted user table.
   * @param tableId The table ID of the soft deleted user table.
   * @param purgeAfterMs The timestamp in milliseconds after which all soft deleted user tables
   *     should be deleted. If null, all soft deleted user tables for the given databaseId and
   *     tableId will be deleted.
   */
  @Override
  public void purgeSoftDeletedUserTables(String databaseId, String tableId, Long purgeAfterMs) {
    if (purgeAfterMs == null) {
      softDeletedHtsJdbcRepository.deleteAllByDatabaseIdTableId(databaseId, tableId);
    } else {
      softDeletedHtsJdbcRepository.deleteByDatabaseIdTableIdPurgeAfterMs(
          databaseId, tableId, purgeAfterMs);
    }
  }

  @Override
  public Page<UserTableDto> getAllSoftDeletedTables(
      UserTable userTable, int page, int size, String sortBy) {
    METRICS_REPORTER.count(MetricsConstant.HTS_PAGE_SEARCH_TABLES_REQUEST);
    Pageable pageable = createPageable(page, size, sortBy, "tableId");
    return METRICS_REPORTER.executeWithStats(
        () ->
            softDeletedHtsJdbcRepository
                .findAllByFilters(
                    userTable.getDatabaseId(),
                    userTable.getTableId(),
                    userTable.getPurgeAfterMs(),
                    pageable)
                .map(
                    softDeletedUserTableRow ->
                        softDeletedUserTablesMapper.toUserTableDto(softDeletedUserTableRow)),
        MetricsConstant.HTS_PAGE_SEARCH_TABLES_TIME);
  }

  private List<UserTableDto> listDatabases() {
    METRICS_REPORTER.count(MetricsConstant.HTS_LIST_DATABASES_REQUEST);
    return METRICS_REPORTER.executeWithStats(
        () ->
            StreamSupport.stream(
                    htsJdbcRepository.findAllDistinctDatabaseIds().spliterator(), false)
                .map(databaseId -> UserTableDto.builder().databaseId(databaseId).build())
                .collect(Collectors.toList()),
        MetricsConstant.HTS_LIST_DATABASES_TIME);
  }

  private Page<UserTableDto> listDatabases(int page, int size, String sortBy) {
    METRICS_REPORTER.count(MetricsConstant.HTS_PAGE_DATABASES_REQUEST);
    Pageable pageable = createPageable(page, size, sortBy, "databaseId");
    return METRICS_REPORTER.executeWithStats(
        () ->
            htsJdbcRepository
                .findAllDistinctDatabaseIds(null, pageable)
                .map(databaseId -> UserTableDto.builder().databaseId(databaseId).build()),
        MetricsConstant.HTS_PAGE_DATABASES_TIME);
  }

  private List<UserTableDto> listTables(UserTable userTable) {
    METRICS_REPORTER.count(MetricsConstant.HTS_LIST_TABLES_REQUEST);
    return METRICS_REPORTER.executeWithStats(
        () ->
            StreamSupport.stream(
                    htsJdbcRepository
                        .findAllTablesByFilters(
                            userTable.getDatabaseId(), null, null, null, null, null)
                        .spliterator(),
                    false)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow))
                .collect(Collectors.toList()),
        MetricsConstant.HTS_LIST_TABLES_TIME);
  }

  private Page<UserTableDto> listTables(UserTable userTable, int page, int size, String sortBy) {
    METRICS_REPORTER.count(MetricsConstant.HTS_PAGE_TABLES_REQUEST);
    Pageable pageable = createPageable(page, size, sortBy, "tableId");
    return METRICS_REPORTER.executeWithStats(
        () ->
            htsJdbcRepository
                .findAllTablesByFilters(
                    userTable.getDatabaseId(), null, null, null, null, null, pageable)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow)),
        MetricsConstant.HTS_PAGE_TABLES_TIME);
  }

  private List<UserTableDto> listTablesWithPattern(UserTable userTable) {
    METRICS_REPORTER.count(MetricsConstant.HTS_LIST_TABLES_REQUEST);
    return METRICS_REPORTER.executeWithStats(
        () ->
            StreamSupport.stream(
                    htsJdbcRepository
                        .findAllTablesByDatabaseIdAndTableIdLikeAllIgnoreCase(
                            userTable.getDatabaseId(), userTable.getTableId())
                        .spliterator(),
                    false)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow))
                .collect(Collectors.toList()),
        MetricsConstant.HTS_LIST_TABLES_TIME);
  }

  private Page<UserTableDto> listTablesWithPattern(
      UserTable userTable, int page, int size, String sortBy) {
    METRICS_REPORTER.count(MetricsConstant.HTS_PAGE_TABLES_REQUEST);
    Pageable pageable = createPageable(page, size, sortBy, "tableId");
    return METRICS_REPORTER.executeWithStats(
        () ->
            htsJdbcRepository
                .findAllTablesByDatabaseIdAndTableIdLikeAllIgnoreCase(
                    userTable.getDatabaseId(), userTable.getTableId(), pageable)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow)),
        MetricsConstant.HTS_PAGE_TABLES_TIME);
  }

  private Page<UserTableDto> searchTables(UserTable userTable, int page, int size, String sortBy) {
    METRICS_REPORTER.count(MetricsConstant.HTS_PAGE_SEARCH_TABLES_REQUEST);
    Pageable pageable = createPageable(page, size, sortBy, "tableId");
    log.warn(
        "Reaching general search for user table which is not expected: {}", userTable.toJson());
    return METRICS_REPORTER.executeWithStats(
        () ->
            htsJdbcRepository
                .findAllTablesByFilters(
                    userTable.getDatabaseId(),
                    userTable.getTableId(),
                    userTable.getTableVersion(),
                    userTable.getMetadataLocation(),
                    userTable.getStorageType(),
                    userTable.getCreationTime(),
                    pageable)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow)),
        MetricsConstant.HTS_PAGE_SEARCH_TABLES_TIME);
  }

  private List<UserTableDto> searchTables(UserTable userTable) {
    METRICS_REPORTER.count(MetricsConstant.HTS_GENERAL_SEARCH_REQUEST);
    log.warn(
        "Reaching general search for user table which is not expected: {}", userTable.toJson());
    return METRICS_REPORTER.executeWithStats(
        () ->
            StreamSupport.stream(
                    htsJdbcRepository
                        .findAllTablesByFilters(
                            userTable.getDatabaseId(),
                            userTable.getTableId(),
                            userTable.getTableVersion(),
                            userTable.getMetadataLocation(),
                            userTable.getStorageType(),
                            userTable.getCreationTime())
                        .spliterator(),
                    false)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow))
                .collect(Collectors.toList()),
        MetricsConstant.HTS_SEARCH_TABLES_TIME);
  }

  private List<UserTableDto> listViews(String databaseId) {
    METRICS_REPORTER.count(MetricsConstant.HTS_LIST_VIEWS_REQUEST);
    return METRICS_REPORTER.executeWithStats(
        () ->
            StreamSupport.stream(
                    htsJdbcRepository
                        .findAllViewsByFilters(databaseId, null, null, null, null, null)
                        .spliterator(),
                    false)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow))
                .collect(Collectors.toList()),
        MetricsConstant.HTS_LIST_VIEWS_TIME);
  }

  private Page<UserTableDto> listViews(String databaseId, int page, int size, String sortBy) {
    METRICS_REPORTER.count(MetricsConstant.HTS_PAGE_VIEWS_REQUEST);
    Pageable pageable = createPageable(page, size, sortBy, "tableId");
    return METRICS_REPORTER.executeWithStats(
        () ->
            htsJdbcRepository
                .findAllViewsByFilters(databaseId, null, null, null, null, null, pageable)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow)),
        MetricsConstant.HTS_PAGE_VIEWS_TIME);
  }

  private List<UserTableDto> listViewsWithPattern(UserViewQuery userViewQuery) {
    METRICS_REPORTER.count(MetricsConstant.HTS_LIST_VIEWS_REQUEST);
    return METRICS_REPORTER.executeWithStats(
        () ->
            StreamSupport.stream(
                    htsJdbcRepository
                        .findAllViewsByDatabaseIdAndTableIdLikeAllIgnoreCase(
                            userViewQuery.getDatabaseId(), userViewQuery.getTableIdPattern())
                        .spliterator(),
                    false)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow))
                .collect(Collectors.toList()),
        MetricsConstant.HTS_LIST_VIEWS_TIME);
  }

  private Page<UserTableDto> listViewsWithPattern(
      UserViewQuery userViewQuery, int page, int size, String sortBy) {
    METRICS_REPORTER.count(MetricsConstant.HTS_PAGE_VIEWS_REQUEST);
    Pageable pageable = createPageable(page, size, sortBy, "tableId");
    return METRICS_REPORTER.executeWithStats(
        () ->
            htsJdbcRepository
                .findAllViewsByDatabaseIdAndTableIdLikeAllIgnoreCase(
                    userViewQuery.getDatabaseId(), userViewQuery.getTableIdPattern(), pageable)
                .map(userTableRow -> userTablesMapper.toUserTableDto(userTableRow)),
        MetricsConstant.HTS_PAGE_VIEWS_TIME);
  }

  private boolean isListDatabases(UserTable userTable) {
    return isNonKeyFieldsNullForUserTable(userTable)
        && userTable.getDatabaseId() == null
        && userTable.getTableId() == null;
  }

  private boolean isListTables(UserTable userTable) {
    return isNonKeyFieldsNullForUserTable(userTable)
        && userTable.getDatabaseId() != null
        && userTable.getTableId() == null;
  }

  private boolean isListTablesWithPattern(UserTable userTable) {
    return isNonKeyFieldsNullForUserTable(userTable)
        && userTable.getDatabaseId() != null
        && userTable.getTableId() != null;
  }

  private boolean isNonKeyFieldsNullForUserTable(UserTable userTable) {
    return userTable.getTableVersion() == null
        && userTable.getMetadataLocation() == null
        && userTable.getStorageType() == null
        && userTable.getCreationTime() == null;
  }
}
