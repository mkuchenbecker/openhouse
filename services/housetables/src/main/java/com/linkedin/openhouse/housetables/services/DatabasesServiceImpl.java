package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.model.DatabaseRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseHtsJdbcRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

/** Default implementation of {@link DatabasesService} backed by the JDBC house table. */
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
  public Pair<Database, Boolean> putDatabase(Database database) {
    Optional<DatabaseRow> existing = databaseRepository.findById(database.getDatabaseId());
    long now = System.currentTimeMillis();
    DatabaseRow row =
        DatabaseRow.builder()
            .databaseId(existing.map(DatabaseRow::getDatabaseId).orElse(database.getDatabaseId()))
            .version(existing.map(DatabaseRow::getVersion).orElse(null))
            .properties(properties(database.getProperties()))
            .creationTime(existing.map(DatabaseRow::getCreationTime).orElse(now))
            .lastModifiedTime(now)
            .build();
    return Pair.of(toDatabase(databaseRepository.save(row)), existing.isPresent());
  }

  @Override
  public void deleteDatabase(String databaseId) {
    DatabaseRow row =
        databaseRepository
            .findById(databaseId)
            .orElseThrow(() -> new NoSuchEntityException(ENTITY_TYPE, databaseId));
    databaseRepository.delete(row);
  }

  private static Map<String, String> properties(Map<String, String> properties) {
    return properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
  }

  private static Database toDatabase(DatabaseRow row) {
    return Database.builder()
        .databaseId(row.getDatabaseId())
        .properties(properties(row.getProperties()))
        .creationTime(row.getCreationTime())
        .lastModifiedTime(row.getLastModifiedTime())
        .build();
  }
}
