package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.model.DatabaseRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseHtsJdbcRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link DatabasesService} backed by the JDBC house table.
 *
 * <p>A write carries the version it is based on. Re-reading the stored version here and handing it
 * straight back to {@code save} would make the optimistic lock tautological — it would compare the
 * row against itself and never fail — so the supplied version is compared instead, and {@code null}
 * is read as the assertion "there was no row", which is what a create makes.
 */
@Component
public class DatabasesServiceImpl implements DatabasesService {

  private static final String ENTITY_TYPE = "Database";

  @Autowired DatabaseHtsJdbcRepository databaseRepository;

  @Override
  public Database getDatabase(String databaseId) {
    return databaseRepository
        .findById(databaseId)
        .map(DatabasesServiceImpl::toDatabase)
        .orElseThrow(() -> new NoSuchEntityException(ENTITY_TYPE, databaseId));
  }

  @Override
  public List<Database> getAllDatabases() {
    return StreamSupport.stream(databaseRepository.findAll().spliterator(), false)
        .map(DatabasesServiceImpl::toDatabase)
        .sorted(Comparator.comparing(Database::getDatabaseId))
        .collect(Collectors.toList());
  }

  @Override
  public List<Database> getChildDatabases(String parentDatabaseId) {
    Iterable<DatabaseRow> subtree =
        databaseRepository.findAllByDatabaseIdGreaterThanEqualAndDatabaseIdLessThan(
            NamespaceUtil.subtreeLowerBound(parentDatabaseId),
            NamespaceUtil.subtreeUpperBound(parentDatabaseId));
    return StreamSupport.stream(subtree.spliterator(), false)
        .map(DatabasesServiceImpl::toDatabase)
        // The range is the whole subtree; this is what makes it the children. Counting separators
        // in Java rather than adding a second SQL pattern keeps the depth rule collation-free and
        // out of reach of LIKE's treatment of the underscore that the charset admits.
        .filter(d -> NamespaceUtil.isDirectChild(parentDatabaseId, d.getDatabaseId()))
        .sorted(Comparator.comparing(Database::getDatabaseId))
        .collect(Collectors.toList());
  }

  @Override
  public Pair<Database, Boolean> putDatabase(Database database) {
    Optional<DatabaseRow> existing = databaseRepository.findById(database.getDatabaseId());
    Long expected = database.getVersion();
    if (!existing.isPresent() && expected != null) {
      throw new EntityConcurrentModificationException(
          database.getDatabaseId(),
          String.format(
              "databaseId : %s, version: %s %s",
              database.getDatabaseId(),
              expected,
              "The requested database has been deleted by other processes."),
          new RuntimeException());
    }
    if (existing.isPresent() && !Objects.equals(expected, existing.get().getVersion())) {
      throw new EntityConcurrentModificationException(
          database.getDatabaseId(),
          String.format(
              "databaseId : %s, version: %s %s",
              database.getDatabaseId(),
              expected,
              "The requested database has been modified/created by other processes."),
          new RuntimeException());
    }
    long now = System.currentTimeMillis();
    DatabaseRow row =
        DatabaseRow.builder()
            .databaseId(existing.map(DatabaseRow::getDatabaseId).orElse(database.getDatabaseId()))
            .version(expected)
            .properties(properties(database.getProperties()))
            .creationTime(existing.map(DatabaseRow::getCreationTime).orElse(now))
            .lastModifiedTime(now)
            .build();
    try {
      return Pair.of(toDatabase(databaseRepository.save(row)), existing.isPresent());
    } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
      // The window between the read above and this write: another writer got there first. The
      // database's own constraint is what closes it, and it closes it as a conflict, not a lost
      // update.
      throw new EntityConcurrentModificationException(
          database.getDatabaseId(),
          String.format(
              "databaseId : %s, version: %s %s",
              database.getDatabaseId(),
              expected,
              "The requested database has been modified/created by other processes."),
          e);
    }
  }

  @Override
  public void deleteDatabase(String databaseId, Long version) {
    DatabaseRow row =
        databaseRepository
            .findById(databaseId)
            .orElseThrow(() -> new NoSuchEntityException(ENTITY_TYPE, databaseId));
    if (version != null && !Objects.equals(version, row.getVersion())) {
      throw new EntityConcurrentModificationException(
          databaseId,
          String.format(
              "databaseId : %s, version: %s %s",
              databaseId,
              version,
              "The requested database has been modified/created by other processes."),
          new RuntimeException());
    }
    databaseRepository.delete(row);
  }

  private static Map<String, String> properties(Map<String, String> properties) {
    return properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
  }

  private static Database toDatabase(DatabaseRow row) {
    return Database.builder()
        .databaseId(row.getDatabaseId())
        .version(row.getVersion())
        .properties(properties(row.getProperties()))
        .creationTime(row.getCreationTime())
        .lastModifiedTime(row.getLastModifiedTime())
        .build();
  }
}
