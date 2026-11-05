package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.DatabasePropertiesUpdateDto;
import com.linkedin.openhouse.tables.repository.DatabaseEntityRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * The {@link org.springframework.context.annotation.Bean} injected into /tables e2e tests when
 * communication to the HouseTables implementation of {@link DatabaseEntityRepository} is not
 * needed. With {@link Primary} annotation, this repository will be the default injection. The
 * name-based lookups are derived by Spring Data; the two HouseTables-side operations are emulated.
 */
@Repository
@Primary
public interface DatabaseEntityH2Repository extends DatabaseEntityRepository {

  @Override
  default DatabaseDto create(DatabaseDto databaseDto) {
    if (existsByDatabaseIdIgnoreCase(databaseDto.getDatabaseId())) {
      throw new AlreadyExistsException("Database", databaseDto.getDatabaseId());
    }
    return save(
        databaseDto
            .toBuilder()
            .databaseUUID(UUID.randomUUID().toString())
            .properties(
                databaseDto.getProperties() == null
                    ? new HashMap<>()
                    : new HashMap<>(databaseDto.getProperties()))
            .build());
  }

  @Override
  default DatabasePropertiesUpdateDto updateProperties(
      String databaseId, Map<String, String> updates, List<String> removals) {
    DatabaseDto existing =
        findByDatabaseIdIgnoreCase(databaseId)
            .orElseThrow(() -> new NoSuchEntityException("Database", databaseId));
    Map<String, String> properties =
        existing.getProperties() == null
            ? new HashMap<>()
            : new HashMap<>(existing.getProperties());
    List<String> updated = new ArrayList<>();
    if (updates != null) {
      for (Map.Entry<String, String> entry : updates.entrySet()) {
        properties.put(entry.getKey(), entry.getValue());
        updated.add(entry.getKey());
      }
    }
    List<String> removed = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    if (removals != null) {
      for (String key : removals) {
        if (properties.containsKey(key)) {
          properties.remove(key);
          removed.add(key);
        } else {
          missing.add(key);
        }
      }
    }
    save(existing.toBuilder().properties(properties).build());
    return DatabasePropertiesUpdateDto.builder()
        .updated(updated)
        .removed(removed)
        .missing(missing)
        .build();
  }
}
