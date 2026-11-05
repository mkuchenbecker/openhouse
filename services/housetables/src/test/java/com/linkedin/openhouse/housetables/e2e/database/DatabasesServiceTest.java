package com.linkedin.openhouse.housetables.e2e.database;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.dto.model.DatabaseDto;
import com.linkedin.openhouse.housetables.dto.model.DatabasePropertiesUpdateDto;
import com.linkedin.openhouse.housetables.e2e.SpringH2HtsApplication;
import com.linkedin.openhouse.housetables.model.DatabaseRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseHtsJdbcRepository;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.UserTableHtsJdbcRepository;
import com.linkedin.openhouse.housetables.services.DatabasesService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

@SpringBootTest(classes = SpringH2HtsApplication.class)
public class DatabasesServiceTest {

  @Autowired DatabasesService databasesService;

  @SpyBean DatabaseHtsJdbcRepository databaseRepository;

  @SpyBean UserTableHtsJdbcRepository userTableRepository;

  @AfterEach
  public void tearDown() {
    Mockito.reset(databaseRepository);
    Mockito.reset(userTableRepository);
    databaseRepository.deleteAll();
  }

  private static Map<String, String> props(String... kv) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put(kv[i], kv[i + 1]);
    }
    return map;
  }

  private static Database database(String databaseId, Map<String, String> properties) {
    return Database.builder().databaseId(databaseId).properties(properties).build();
  }

  @Test
  public void testCreateGeneratesUuidAndIgnoresClientUuid() {
    Database request =
        database("svc_db", props("a", "1")).toBuilder().databaseUuid("client-supplied").build();
    DatabaseDto created = databasesService.createDatabase(request);

    Assertions.assertNotNull(created.getDatabaseUuid());
    Assertions.assertNotEquals("client-supplied", created.getDatabaseUuid());
    Assertions.assertEquals("svc_db", created.getDatabaseId());
    Assertions.assertEquals(props("a", "1"), created.getProperties());
    Assertions.assertNotNull(created.getCreationTime());

    DatabaseDto fetched = databasesService.getDatabase("svc_db");
    Assertions.assertEquals(created, fetched);
  }

  @Test
  public void testCreateWithNullPropertiesStoresEmptyMap() {
    DatabaseDto created = databasesService.createDatabase(database("svc_db_noprops", null));
    Assertions.assertNotNull(created.getProperties());
    Assertions.assertTrue(created.getProperties().isEmpty());
  }

  @Test
  public void testCreateConflictsIgnoringCase() {
    databasesService.createDatabase(database("svc_dup", props()));
    Assertions.assertThrows(
        AlreadyExistsException.class,
        () -> databasesService.createDatabase(database("svc_dup", props())));
    Assertions.assertThrows(
        AlreadyExistsException.class,
        () -> databasesService.createDatabase(database("SVC_DUP", props())));
  }

  @Test
  public void testGetIgnoresCaseAndReturnsStoredName() {
    databasesService.createDatabase(database("Svc_Case", props("k", "v")));
    DatabaseDto fetched = databasesService.getDatabase("SVC_CASE");
    Assertions.assertEquals("Svc_Case", fetched.getDatabaseId());
    Assertions.assertEquals(props("k", "v"), fetched.getProperties());
  }

  @Test
  public void testGetAndDeleteMissingThrowNotFound() {
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.getDatabase("svc_missing"));
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.deleteDatabase("svc_missing"));
  }

  @Test
  public void testDeleteThenGetIsNotFound() {
    databasesService.createDatabase(database("svc_del", props()));
    databasesService.deleteDatabase("SVC_DEL");
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.getDatabase("svc_del"));
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.deleteDatabase("svc_del"));
  }

  @Test
  public void testDatabaseOperationsNeverTouchUserTableRepository() {
    databasesService.createDatabase(database("svc_isolated", props("a", "1")));
    databasesService.getDatabase("svc_isolated");
    databasesService.updateDatabaseProperties(
        "svc_isolated", props("b", "2"), Collections.singletonList("a"));
    databasesService.deleteDatabase("svc_isolated");
    Mockito.verifyNoInteractions(userTableRepository);
  }

  @Test
  public void testUpdatePropertiesAppliesSparseSemantics() {
    DatabaseDto created = databasesService.createDatabase(database("svc_upd", props()));
    long versionAfterCreate =
        databaseRepository.findById(created.getDatabaseUuid()).get().getVersion();

    DatabasePropertiesUpdateDto first =
        databasesService.updateDatabaseProperties(
            "svc_upd", props("a", "1", "b", "2"), Collections.emptyList());
    Assertions.assertEquals(
        new java.util.HashSet<>(Arrays.asList("a", "b")),
        new java.util.HashSet<>(first.getUpdated()));
    Assertions.assertTrue(first.getRemoved().isEmpty());
    Assertions.assertTrue(first.getMissing().isEmpty());
    Assertions.assertEquals(
        props("a", "1", "b", "2"), databasesService.getDatabase("svc_upd").getProperties());

    DatabasePropertiesUpdateDto second =
        databasesService.updateDatabaseProperties(
            "svc_upd", props("a", "9"), Arrays.asList("b", "c"));
    Assertions.assertEquals(Collections.singletonList("a"), second.getUpdated());
    Assertions.assertEquals(Collections.singletonList("b"), second.getRemoved());
    Assertions.assertEquals(Collections.singletonList("c"), second.getMissing());
    Assertions.assertEquals(
        props("a", "9"), databasesService.getDatabase("svc_upd").getProperties());

    // Exactly one version increment per successful update.
    Assertions.assertEquals(
        versionAfterCreate + 2,
        databaseRepository.findById(created.getDatabaseUuid()).get().getVersion());
  }

  @Test
  public void testUpdatePropertiesWithNullSetsIsNoOp() {
    DatabaseDto created =
        databasesService.createDatabase(database("svc_upd_null", props("a", "1")));
    DatabasePropertiesUpdateDto result =
        databasesService.updateDatabaseProperties("svc_upd_null", null, null);
    Assertions.assertTrue(result.getUpdated().isEmpty());
    Assertions.assertTrue(result.getRemoved().isEmpty());
    Assertions.assertTrue(result.getMissing().isEmpty());
    Assertions.assertEquals(
        props("a", "1"), databasesService.getDatabase("svc_upd_null").getProperties());
    Assertions.assertEquals(
        created.getDatabaseUuid(), databasesService.getDatabase("svc_upd_null").getDatabaseUuid());
  }

  @Test
  public void testUpdatePropertiesKeyInBothSetsIsRejectedBeforeAnyRead() {
    DatabaseDto created =
        databasesService.createDatabase(database("svc_upd_both", props("a", "1")));
    long versionBefore = databaseRepository.findById(created.getDatabaseUuid()).get().getVersion();
    Mockito.clearInvocations(databaseRepository);

    Assertions.assertThrows(
        UnprocessableEntityException.class,
        () ->
            databasesService.updateDatabaseProperties(
                "svc_upd_both", props("a", "2"), Collections.singletonList("a")));

    Mockito.verify(databaseRepository, Mockito.never()).findByDatabaseIdIgnoreCase(anyString());
    Assertions.assertEquals(
        props("a", "1"), databasesService.getDatabase("svc_upd_both").getProperties());
    Assertions.assertEquals(
        versionBefore, databaseRepository.findById(created.getDatabaseUuid()).get().getVersion());
  }

  @Test
  public void testUpdatePropertiesOnMissingDatabaseThrowsNotFound() {
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () ->
            databasesService.updateDatabaseProperties(
                "svc_missing", props("a", "1"), Collections.emptyList()));
  }

  @Test
  public void testConcurrentUpdateIsReportedAsConflict() {
    DatabaseDto created = databasesService.createDatabase(database("svc_conc", props("a", "1")));
    DatabaseRow stale = databaseRepository.findById(created.getDatabaseUuid()).get();

    // Another writer wins first.
    databasesService.updateDatabaseProperties("svc_conc", props("a", "2"), Collections.emptyList());

    // Make the service read the stale row so its write carries an outdated version.
    doReturn(Optional.of(stale)).when(databaseRepository).findByDatabaseIdIgnoreCase("svc_conc");
    Assertions.assertThrows(
        EntityConcurrentModificationException.class,
        () ->
            databasesService.updateDatabaseProperties(
                "svc_conc", props("a", "3"), Collections.emptyList()));

    Mockito.reset(databaseRepository);
    Assertions.assertEquals(
        props("a", "2"), databasesService.getDatabase("svc_conc").getProperties());
  }
}
