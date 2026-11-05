package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.model.TableModelConstants.SHARED_TABLE_DTO;
import static com.linkedin.openhouse.tables.model.TableModelConstants.TEST_USER;

import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.DatabasePropertiesUpdateDto;
import com.linkedin.openhouse.tables.repository.DatabaseEntityRepository;
import com.linkedin.openhouse.tables.services.DatabasesService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(classes = SpringH2Application.class)
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class DatabasesServiceTest {

  @Autowired DatabasesService databasesService;

  @Test
  public void testUpdateAclPoliciesOnDatabase() {
    Assertions.assertDoesNotThrow(
        () ->
            databasesService.updateDatabaseAclPolicies(
                SHARED_TABLE_DTO.getDatabaseId(),
                UpdateAclPoliciesRequestBody.builder()
                    .role("AclEditor")
                    .principal("DUMMY_USER")
                    .operation(UpdateAclPoliciesRequestBody.Operation.GRANT)
                    .build(),
                TEST_USER));

    Assertions.assertDoesNotThrow(
        () ->
            databasesService.updateDatabaseAclPolicies(
                SHARED_TABLE_DTO.getDatabaseId(),
                UpdateAclPoliciesRequestBody.builder()
                    .role("AclEditor")
                    .principal("DUMMY_USER")
                    .operation(UpdateAclPoliciesRequestBody.Operation.REVOKE)
                    .build(),
                TEST_USER));
  }

  @Autowired DatabaseEntityRepository databaseEntityRepository;

  @AfterEach
  public void cleanUpDatabaseEntities() {
    databaseEntityRepository.deleteAll();
  }

  private static Map<String, String> props(String... kv) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put(kv[i], kv[i + 1]);
    }
    return map;
  }

  @Test
  public void testCreateGetDeleteDatabaseEntity() {
    DatabaseDto created = databasesService.createDatabase("svc_db", props("a", "1"), TEST_USER);
    Assertions.assertEquals("svc_db", created.getDatabaseId());
    Assertions.assertNotNull(created.getDatabaseUUID());
    Assertions.assertFalse(created.getDatabaseUUID().isEmpty());
    Assertions.assertEquals(props("a", "1"), created.getProperties());
    Assertions.assertEquals("local-cluster", created.getClusterId());

    DatabaseDto fetched = databasesService.getDatabase("SVC_DB", TEST_USER);
    Assertions.assertEquals(created.getDatabaseUUID(), fetched.getDatabaseUUID());
    Assertions.assertEquals("svc_db", fetched.getDatabaseId());
    Assertions.assertEquals(props("a", "1"), fetched.getProperties());

    Assertions.assertThrows(
        AlreadyExistsException.class,
        () -> databasesService.createDatabase("SVC_DB", props(), TEST_USER));

    databasesService.deleteDatabase("svc_db", TEST_USER);
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.getDatabase("svc_db", TEST_USER));
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.deleteDatabase("svc_db", TEST_USER));
  }

  @Test
  public void testCreateWithNullPropertiesStoresEmptyMap() {
    DatabaseDto created = databasesService.createDatabase("svc_db_null", null, TEST_USER);
    Assertions.assertNotNull(created.getProperties());
    Assertions.assertTrue(created.getProperties().isEmpty());
  }

  @Test
  public void testUpdateDatabasePropertiesSparseSemantics() {
    databasesService.createDatabase("svc_upd", props(), TEST_USER);
    DatabasePropertiesUpdateDto first =
        databasesService.updateDatabaseProperties(
            "svc_upd", props("a", "1", "b", "2"), Collections.emptyList(), TEST_USER);
    Assertions.assertEquals(
        new HashSet<>(Arrays.asList("a", "b")), new HashSet<>(first.getUpdated()));
    Assertions.assertTrue(first.getRemoved().isEmpty());
    Assertions.assertTrue(first.getMissing().isEmpty());

    DatabasePropertiesUpdateDto second =
        databasesService.updateDatabaseProperties(
            "SVC_UPD", props("a", "9"), Arrays.asList("b", "c"), TEST_USER);
    Assertions.assertEquals(Collections.singletonList("a"), second.getUpdated());
    Assertions.assertEquals(Collections.singletonList("b"), second.getRemoved());
    Assertions.assertEquals(Collections.singletonList("c"), second.getMissing());
    Assertions.assertEquals(
        props("a", "9"), databasesService.getDatabase("svc_upd", TEST_USER).getProperties());

    Assertions.assertThrows(
        UnprocessableEntityException.class,
        () ->
            databasesService.updateDatabaseProperties(
                "svc_upd", props("a", "2"), Collections.singletonList("a"), TEST_USER));
    Assertions.assertEquals(
        props("a", "9"), databasesService.getDatabase("svc_upd", TEST_USER).getProperties());

    Assertions.assertThrows(
        NoSuchEntityException.class,
        () ->
            databasesService.updateDatabaseProperties(
                "svc_missing", props("a", "1"), Collections.emptyList(), TEST_USER));
  }

  @Test
  public void testListingDatabasesIsUnaffectedByDatabaseEntities() {
    // The list endpoint derives databases from table metadata; a database entity with no tables
    // does not appear in it.
    databasesService.createDatabase("svc_entity_only", props(), TEST_USER);
    Assertions.assertTrue(
        databasesService.getAllDatabases().stream()
            .noneMatch(dto -> "svc_entity_only".equals(dto.getDatabaseId())));
  }
}
