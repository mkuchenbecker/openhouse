package com.linkedin.openhouse.housetables.e2e.database;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.housetables.model.DatabaseRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseHtsJdbcRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class DatabaseRepositoryTest {

  @Autowired DatabaseHtsJdbcRepository databaseRepository;

  @AfterEach
  public void tearDown() {
    databaseRepository.deleteAll();
  }

  private static DatabaseRow newRow(String databaseId, Map<String, String> properties) {
    return DatabaseRow.builder()
        .databaseUuid(UUID.randomUUID().toString())
        .databaseId(databaseId)
        .creationTime(123L)
        .properties(properties)
        .build();
  }

  @Test
  public void testSaveAssignsInitialVersionAndRoundTripsProperties() {
    Map<String, String> properties = new HashMap<>();
    properties.put("a", "1");
    properties.put("b", "2");
    DatabaseRow row = newRow("repo_db", properties);
    Assertions.assertNull(row.getVersion());

    DatabaseRow saved = databaseRepository.save(row);
    Assertions.assertEquals(0L, saved.getVersion());

    Optional<DatabaseRow> loaded = databaseRepository.findById(saved.getDatabaseUuid());
    Assertions.assertTrue(loaded.isPresent());
    Assertions.assertEquals("repo_db", loaded.get().getDatabaseId());
    Assertions.assertEquals(properties, loaded.get().getProperties());
    Assertions.assertEquals(123L, loaded.get().getCreationTime());
  }

  @Test
  public void testNullPropertiesReadBackAsEmptyMap() {
    DatabaseRow saved = databaseRepository.save(newRow("repo_db_null_props", null));
    Optional<DatabaseRow> loaded = databaseRepository.findById(saved.getDatabaseUuid());
    Assertions.assertTrue(loaded.isPresent());
    Assertions.assertNotNull(loaded.get().getProperties());
    Assertions.assertTrue(loaded.get().getProperties().isEmpty());
  }

  @Test
  public void testFindAndExistsByDatabaseIdIgnoreCase() {
    databaseRepository.save(newRow("Repo_Case_Db", new HashMap<>()));

    Assertions.assertTrue(databaseRepository.existsByDatabaseIdIgnoreCase("repo_case_db"));
    Assertions.assertTrue(databaseRepository.existsByDatabaseIdIgnoreCase("REPO_CASE_DB"));
    Assertions.assertFalse(databaseRepository.existsByDatabaseIdIgnoreCase("repo_case_db2"));

    Optional<DatabaseRow> found = databaseRepository.findByDatabaseIdIgnoreCase("REPO_CASE_DB");
    Assertions.assertTrue(found.isPresent());
    // The name is stored as written by the creator.
    Assertions.assertEquals("Repo_Case_Db", found.get().getDatabaseId());
    Assertions.assertFalse(databaseRepository.findByDatabaseIdIgnoreCase("nope").isPresent());
  }

  @Test
  public void testUniqueConstraintOnDatabaseId() {
    databaseRepository.save(newRow("repo_dup_db", new HashMap<>()));
    Assertions.assertThrows(
        DataIntegrityViolationException.class,
        () -> databaseRepository.save(newRow("repo_dup_db", new HashMap<>())));
  }

  @Test
  public void testStaleVersionSaveIsRejected() {
    DatabaseRow stale = databaseRepository.save(newRow("repo_stale_db", new HashMap<>()));
    Assertions.assertEquals(0L, stale.getVersion());

    // A fresh read-modify-write bumps the version to 1.
    DatabaseRow fresh = databaseRepository.findById(stale.getDatabaseUuid()).get();
    Map<String, String> updated = new HashMap<>();
    updated.put("k", "v");
    databaseRepository.save(fresh.toBuilder().properties(updated).build());
    Assertions.assertEquals(
        1L, databaseRepository.findById(stale.getDatabaseUuid()).get().getVersion());

    // Writing through the stale copy (version 0) must fail.
    Map<String, String> other = new HashMap<>();
    other.put("k", "stale");
    Assertions.assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> databaseRepository.save(stale.toBuilder().properties(other).build()));
    Assertions.assertEquals(
        updated, databaseRepository.findById(stale.getDatabaseUuid()).get().getProperties());
  }
}
