package com.linkedin.openhouse.tables.mock.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.exception.OpenHouseCommitStateUnknownException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.housetables.client.api.DatabaseApi;
import com.linkedin.openhouse.housetables.client.model.CreateUpdateEntityRequestBodyDatabase;
import com.linkedin.openhouse.housetables.client.model.Database;
import com.linkedin.openhouse.housetables.client.model.EntityResponseBodyDatabase;
import com.linkedin.openhouse.housetables.client.model.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.housetables.client.model.UpdateDatabasePropertiesResponseBody;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.DatabasePropertiesUpdateDto;
import com.linkedin.openhouse.tables.repository.impl.DatabaseEntityRepositoryImpl;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Verifies the translation of HouseTables {@code DatabaseApi} responses and failures into the typed
 * exceptions the shared exception handler maps to Iceberg namespace status codes.
 */
public class DatabaseEntityRepositoryImplTest {

  private DatabaseApi databaseApi;

  private DatabaseEntityRepositoryImpl repository;

  @BeforeEach
  public void setup() {
    databaseApi = mock(DatabaseApi.class);
    repository = new DatabaseEntityRepositoryImpl();
    ReflectionTestUtils.setField(repository, "databaseApi", databaseApi);
  }

  private static WebClientResponseException httpError(int status, String message) {
    String body =
        message == null
            ? ""
            : String.format(
                "{\"status\":\"X\",\"error\":\"X\",\"message\":\"%s\",\"stacktrace\":null,\"cause\":\"Not Available\"}",
                message);
    return WebClientResponseException.create(
        status, "reason", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), null);
  }

  private static WebClientRequestException connectionFailure() {
    return new WebClientRequestException(
        new ConnectException("Connection refused"),
        HttpMethod.GET,
        URI.create("http://hts/hts/databases"),
        HttpHeaders.EMPTY);
  }

  private static EntityResponseBodyDatabase htsDatabase(String name, Map<String, String> props) {
    Database database = new Database();
    // read-only in the generated model: set the way Jackson does when deserializing HTS responses
    ReflectionTestUtils.setField(database, "databaseUuid", "uuid-" + name);
    database.setDatabaseId(name);
    ReflectionTestUtils.setField(database, "creationTime", 1L);
    database.setProperties(props);
    EntityResponseBodyDatabase body = new EntityResponseBodyDatabase();
    body.setEntity(database);
    return body;
  }

  @Test
  public void testFindMapsEntityAndTreatsNotFoundAsEmpty() {
    Map<String, String> props = new HashMap<>();
    props.put("a", "1");
    when(databaseApi.getDatabase("d1")).thenReturn(Mono.just(htsDatabase("d1", props)));
    Optional<DatabaseDto> found = repository.findByDatabaseIdIgnoreCase("d1");
    Assertions.assertTrue(found.isPresent());
    Assertions.assertEquals("d1", found.get().getDatabaseId());
    Assertions.assertEquals("uuid-d1", found.get().getDatabaseUUID());
    Assertions.assertEquals(props, found.get().getProperties());
    Assertions.assertTrue(repository.existsByDatabaseIdIgnoreCase("d1"));

    when(databaseApi.getDatabase("nope")).thenReturn(Mono.error(httpError(404, "not found")));
    Assertions.assertFalse(repository.findByDatabaseIdIgnoreCase("nope").isPresent());
    Assertions.assertFalse(repository.existsByDatabaseIdIgnoreCase("nope"));
  }

  @Test
  public void testFindTranslatesServerAndConnectionFailuresToStateUnknown() {
    when(databaseApi.getDatabase("d500")).thenReturn(Mono.error(httpError(500, "boom")));
    Assertions.assertThrows(
        OpenHouseCommitStateUnknownException.class,
        () -> repository.findByDatabaseIdIgnoreCase("d500"));

    when(databaseApi.getDatabase("dconn")).thenReturn(Mono.error(connectionFailure()));
    Assertions.assertThrows(
        OpenHouseCommitStateUnknownException.class,
        () -> repository.findByDatabaseIdIgnoreCase("dconn"));
  }

  @Test
  public void testCreateSendsNameAndPropertiesAndNeverAUuid() {
    Map<String, String> props = new HashMap<>();
    props.put("a", "1");
    when(databaseApi.createDatabase(any())).thenReturn(Mono.just(htsDatabase("d1", props)));

    DatabaseDto created =
        repository.create(
            DatabaseDto.builder()
                .databaseId("d1")
                .clusterId("cluster")
                .databaseUUID("client-supplied")
                .properties(props)
                .build());

    ArgumentCaptor<CreateUpdateEntityRequestBodyDatabase> captor =
        ArgumentCaptor.forClass(CreateUpdateEntityRequestBodyDatabase.class);
    Mockito.verify(databaseApi).createDatabase(captor.capture());
    Assertions.assertEquals("d1", captor.getValue().getEntity().getDatabaseId());
    Assertions.assertEquals(props, captor.getValue().getEntity().getProperties());
    Assertions.assertNull(captor.getValue().getEntity().getDatabaseUuid());

    Assertions.assertEquals("uuid-d1", created.getDatabaseUUID());
    Assertions.assertEquals("cluster", created.getClusterId());
    Assertions.assertEquals(props, created.getProperties());
  }

  @Test
  public void testCreateTranslatesConflictAndBadRequest() {
    when(databaseApi.createDatabase(any()))
        .thenReturn(Mono.error(httpError(409, "Database d1 already exists")));
    Assertions.assertThrows(
        AlreadyExistsException.class,
        () -> repository.create(DatabaseDto.builder().databaseId("d1").build()));

    when(databaseApi.createDatabase(any()))
        .thenReturn(Mono.error(httpError(400, "databaseId : bad")));
    RequestValidationFailureException bad =
        Assertions.assertThrows(
            RequestValidationFailureException.class,
            () -> repository.create(DatabaseDto.builder().databaseId("d1").build()));
    Assertions.assertEquals("databaseId : bad", bad.getMessage());
  }

  @Test
  public void testDeleteTranslatesNotFound() {
    when(databaseApi.deleteDatabase("d1")).thenReturn(Mono.empty());
    Assertions.assertDoesNotThrow(
        () -> repository.delete(DatabaseDto.builder().databaseId("d1").build()));

    when(databaseApi.deleteDatabase("nope")).thenReturn(Mono.error(httpError(404, "missing")));
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> repository.delete(DatabaseDto.builder().databaseId("nope").build()));

    when(databaseApi.deleteDatabase("d503")).thenReturn(Mono.error(httpError(503, "down")));
    Assertions.assertThrows(
        OpenHouseCommitStateUnknownException.class,
        () -> repository.delete(DatabaseDto.builder().databaseId("d503").build()));
  }

  @Test
  public void testUpdatePropertiesMapsResultAndTranslatesFailures() {
    UpdateDatabasePropertiesResponseBody response = new UpdateDatabasePropertiesResponseBody();
    ReflectionTestUtils.setField(response, "updated", Collections.singletonList("a"));
    ReflectionTestUtils.setField(response, "removed", Collections.singletonList("b"));
    ReflectionTestUtils.setField(response, "missing", Collections.singletonList("c"));
    when(databaseApi.updateDatabaseProperties(eq("d1"), any())).thenReturn(Mono.just(response));

    Map<String, String> updates = new HashMap<>();
    updates.put("a", "9");
    DatabasePropertiesUpdateDto result =
        repository.updateProperties("d1", updates, Arrays.asList("b", "c"));
    Assertions.assertEquals(Collections.singletonList("a"), result.getUpdated());
    Assertions.assertEquals(Collections.singletonList("b"), result.getRemoved());
    Assertions.assertEquals(Collections.singletonList("c"), result.getMissing());

    ArgumentCaptor<UpdateDatabasePropertiesRequestBody> captor =
        ArgumentCaptor.forClass(UpdateDatabasePropertiesRequestBody.class);
    Mockito.verify(databaseApi).updateDatabaseProperties(eq("d1"), captor.capture());
    Assertions.assertEquals(updates, captor.getValue().getUpdates());
    Assertions.assertEquals(Arrays.asList("b", "c"), captor.getValue().getRemovals());

    when(databaseApi.updateDatabaseProperties(eq("nope"), any()))
        .thenReturn(Mono.error(httpError(404, "missing")));
    Assertions.assertThrows(
        NoSuchEntityException.class,
        () -> repository.updateProperties("nope", updates, Collections.emptyList()));

    when(databaseApi.updateDatabaseProperties(eq("d409"), any()))
        .thenReturn(Mono.error(httpError(409, "modified")));
    Assertions.assertThrows(
        EntityConcurrentModificationException.class,
        () -> repository.updateProperties("d409", updates, Collections.emptyList()));

    when(databaseApi.updateDatabaseProperties(eq("d422"), any()))
        .thenReturn(Mono.error(httpError(422, "Property key [a] cannot be present in both")));
    UnprocessableEntityException unprocessable =
        Assertions.assertThrows(
            UnprocessableEntityException.class,
            () -> repository.updateProperties("d422", updates, Collections.singletonList("a")));
    Assertions.assertEquals(
        "Property key [a] cannot be present in both", unprocessable.getMessage());

    when(databaseApi.updateDatabaseProperties(eq("d502"), any()))
        .thenReturn(Mono.error(httpError(502, null)));
    Assertions.assertThrows(
        OpenHouseCommitStateUnknownException.class,
        () -> repository.updateProperties("d502", updates, Collections.emptyList()));
  }

  @Test
  public void testNullCollectionsAreSentAsEmpty() {
    UpdateDatabasePropertiesResponseBody response = new UpdateDatabasePropertiesResponseBody();
    when(databaseApi.updateDatabaseProperties(eq("d1"), any())).thenReturn(Mono.just(response));
    DatabasePropertiesUpdateDto result = repository.updateProperties("d1", null, null);
    Assertions.assertTrue(result.getUpdated().isEmpty());
    Assertions.assertTrue(result.getRemoved().isEmpty());
    Assertions.assertTrue(result.getMissing().isEmpty());

    ArgumentCaptor<UpdateDatabasePropertiesRequestBody> captor =
        ArgumentCaptor.forClass(UpdateDatabasePropertiesRequestBody.class);
    Mockito.verify(databaseApi).updateDatabaseProperties(eq("d1"), captor.capture());
    Assertions.assertTrue(captor.getValue().getUpdates().isEmpty());
    Assertions.assertTrue(captor.getValue().getRemovals().isEmpty());
  }

  @Test
  public void testUpsertStyleWritesAreUnsupported() {
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> repository.save(DatabaseDto.builder().databaseId("d1").build()));
    Assertions.assertThrows(UnsupportedOperationException.class, () -> repository.findAll());
    Assertions.assertThrows(UnsupportedOperationException.class, () -> repository.deleteAll());
  }
}
