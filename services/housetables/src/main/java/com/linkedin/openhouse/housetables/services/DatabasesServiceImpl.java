package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.dto.mapper.DatabasesMapper;
import com.linkedin.openhouse.housetables.dto.model.DatabaseDto;
import com.linkedin.openhouse.housetables.dto.model.DatabasePropertiesUpdateDto;
import com.linkedin.openhouse.housetables.model.DatabaseRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseHtsJdbcRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Default {@link DatabasesService} over the JDBC repository. Writes rely on the {@code @Version}
 * column of {@link DatabaseRow}: the read and the write of a sparse update are two statements, and
 * the version check on the write turns an interleaved writer into a conflict instead of a lost
 * update. The write is deliberately not wrapped in a service-level transaction so the optimistic
 * lock failure surfaces from {@code save} and can be translated here, as {@link
 * UserTablesServiceImpl#putUserTable} does.
 */
@Component
@Slf4j
public class DatabasesServiceImpl implements DatabasesService {

  private static final String ENTITY_TYPE = "Database";

  @Autowired DatabaseHtsJdbcRepository databaseHtsJdbcRepository;

  @Autowired DatabasesMapper databasesMapper;

  @Override
  public DatabaseDto createDatabase(Database database) {
    String databaseId = database.getDatabaseId();
    if (databaseHtsJdbcRepository.existsByDatabaseIdIgnoreCase(databaseId)) {
      throw new AlreadyExistsException(ENTITY_TYPE, databaseId);
    }
    DatabaseRow row =
        DatabaseRow.builder()
            .databaseUuid(UUID.randomUUID().toString())
            .databaseId(databaseId)
            .creationTime(System.currentTimeMillis())
            .properties(
                database.getProperties() == null
                    ? new HashMap<>()
                    : new HashMap<>(database.getProperties()))
            .build();
    try {
      return databasesMapper.toDatabaseDto(databaseHtsJdbcRepository.save(row));
    } catch (DataIntegrityViolationException e) {
      // The unique constraint on database_id lost a race with another creator.
      throw new AlreadyExistsException(ENTITY_TYPE, databaseId, e);
    }
  }

  @Override
  public DatabaseDto getDatabase(String databaseId) {
    return databasesMapper.toDatabaseDto(findRowOrThrow(databaseId));
  }

  @Override
  public void deleteDatabase(String databaseId) {
    DatabaseRow row = findRowOrThrow(databaseId);
    databaseHtsJdbcRepository.deleteById(row.getDatabaseUuid());
  }

  @Override
  public DatabasePropertiesUpdateDto updateDatabaseProperties(
      String databaseId, Map<String, String> updates, List<String> removals) {
    Map<String, String> safeUpdates = updates == null ? Collections.emptyMap() : updates;
    List<String> safeRemovals = removals == null ? Collections.emptyList() : removals;
    for (String key : safeRemovals) {
      if (safeUpdates.containsKey(key)) {
        throw new UnprocessableEntityException(
            String.format("Property key [%s] cannot be present in both updates and removals", key));
      }
    }

    DatabaseRow existing = findRowOrThrow(databaseId);
    Map<String, String> properties =
        existing.getProperties() == null
            ? new HashMap<>()
            : new HashMap<>(existing.getProperties());

    List<String> updated = new ArrayList<>();
    for (Map.Entry<String, String> entry : safeUpdates.entrySet()) {
      properties.put(entry.getKey(), entry.getValue());
      updated.add(entry.getKey());
    }
    List<String> removed = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String key : safeRemovals) {
      if (properties.containsKey(key)) {
        properties.remove(key);
        removed.add(key);
      } else {
        missing.add(key);
      }
    }

    try {
      databaseHtsJdbcRepository.save(existing.toBuilder().properties(properties).build());
    } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
      throw new EntityConcurrentModificationException(
          databaseId,
          String.format(
              "databaseId : %s, version: %s %s",
              databaseId,
              existing.getVersion(),
              "The requested database has been modified by other processes."),
          e);
    }
    log.info(
        "Updated properties of database {}: updated={}, removed={}, missing={}",
        databaseId,
        updated,
        removed,
        missing);
    return DatabasePropertiesUpdateDto.builder()
        .updated(updated)
        .removed(removed)
        .missing(missing)
        .build();
  }

  private DatabaseRow findRowOrThrow(String databaseId) {
    return databaseHtsJdbcRepository
        .findByDatabaseIdIgnoreCase(databaseId)
        .orElseThrow(() -> new NoSuchEntityException(ENTITY_TYPE, databaseId));
  }
}
