package com.linkedin.openhouse.internal.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.linkedin.openhouse.housetables.client.api.DatabaseApi;
import com.linkedin.openhouse.housetables.client.api.ToggleStatusApi;
import com.linkedin.openhouse.housetables.client.api.UserTableApi;
import com.linkedin.openhouse.housetables.client.invoker.ApiClient;
import com.linkedin.openhouse.housetables.client.model.Database;
import com.linkedin.openhouse.housetables.client.model.EntityResponseBodyDatabase;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableCallerException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableNotFoundException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Fault-injection contract test for the namespace store's HTTP seam, mirroring {@link
 * HouseTableRepositoryImplTest}.
 *
 * <p>This is the only place the generated {@code DatabaseApi} calls, the {@code /hts/databases}
 * wire contract and the error translation below it are actually executed. Everything above
 * substitutes an H2 stand-in or a mock, so without this the translation table was asserted by
 * nothing: a 409 that no caller could map, an untranslated failure out of {@code findAll}, and a
 * {@code save} that answered a body-less 200 by returning null all lived here unobserved.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest
public class HouseNamespaceRepositoryImplTest {

  private static final String NAMESPACE_ID = "namespace_repo_db";

  @Autowired
  @Qualifier("namespaceRepoTest")
  HouseNamespaceRepository repository;

  private static MockWebServer mockHtsServer;

  @TestConfiguration
  public static class MockWebServerConfiguration {

    @Bean
    @Primary
    public DatabaseApi provideMockDatabaseApi() {
      ApiClient apiClient = new ApiClient();
      apiClient.setBasePath(String.format("http://localhost:%s", mockHtsServer.getPort()));
      return new DatabaseApi(apiClient);
    }

    /** Other components of this package autowire these; this test does not exercise them. */
    @Bean
    public UserTableApi provideUnusedUserTableApi() {
      return new UserTableApi(new ApiClient());
    }

    @Bean
    public ToggleStatusApi provideUnusedToggleStatusApi() {
      return new ToggleStatusApi(new ApiClient());
    }

    @Bean
    @Qualifier("namespaceRepoTest")
    public HouseNamespaceRepository provideRealNamespaceRepository() {
      return new HouseNamespaceRepositoryImpl();
    }
  }

  @BeforeAll
  static void setUp() throws IOException {
    mockHtsServer = new MockWebServer();
    mockHtsServer.start();
  }

  @AfterAll
  static void tearDown() throws IOException {
    mockHtsServer.shutdown();
  }

  /** The mock server records every request; each test asserts only on its own. */
  @BeforeEach
  void drainRecordedRequests() throws InterruptedException {
    while (mockHtsServer.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
      // discard
    }
  }

  @Test
  public void findByIdMapsTheStoredNamespace() {
    enqueueEntity(200, database(3L, "owner", "user"));

    HouseNamespace found = repository.findById(NAMESPACE_ID).get();

    assertThat(found.getNamespaceId()).isEqualTo(NAMESPACE_ID);
    assertThat(found.getVersion()).isEqualTo(3L);
    assertThat(found.getProperties()).containsEntry("owner", "user");
    assertThat(found.getCreationTime()).isEqualTo(1L);
    assertThat(found.getLastModifiedTime()).isEqualTo(2L);
  }

  /** Absence is not failure on a read: the repository answers with an empty Optional. */
  @Test
  public void findByIdAnswersEmptyOnNotFound() {
    enqueue(404, "");
    assertThat(repository.findById(NAMESPACE_ID)).isEqualTo(Optional.empty());
  }

  @Test
  public void findByIdTranslatesEveryOtherStatus() {
    enqueue(409, "");
    assertThatThrownBy(() -> repository.findById(NAMESPACE_ID))
        .isInstanceOf(HouseTableConcurrentUpdateException.class)
        .hasMessageContaining(NAMESPACE_ID);

    for (int status : new int[] {400, 401, 403, 429}) {
      enqueue(status, "");
      assertThatThrownBy(() -> repository.findById(NAMESPACE_ID))
          .isInstanceOf(HouseTableCallerException.class)
          .hasMessageContaining(NAMESPACE_ID);
    }

    for (int status : new int[] {500, 503}) {
      enqueue(status, "");
      assertThatThrownBy(() -> repository.findById(NAMESPACE_ID))
          .isInstanceOf(HouseTableRepositoryStateUnknownException.class)
          .hasMessageContaining(NAMESPACE_ID);
    }
  }

  /** B4: the version the caller read has to reach HTS, or the compare-and-set has nothing to do. */
  @Test
  public void saveCarriesTheVersionOnTheWire() throws InterruptedException {
    enqueueEntity(200, database(4L, "owner", "user"));

    HouseNamespace saved =
        repository.save(
            HouseNamespace.builder()
                .namespaceId(NAMESPACE_ID)
                .version(3L)
                .properties(Collections.singletonMap("owner", "user"))
                .creationTime(1L)
                .lastModifiedTime(2L)
                .build());

    RecordedRequest request = mockHtsServer.takeRequest();
    assertThat(request.getMethod()).isEqualTo("PUT");
    assertThat(request.getPath()).isEqualTo("/hts/databases");
    assertThat(request.getBody().readUtf8()).contains("\"version\":3");
    assertThat(saved.getVersion()).isEqualTo(4L);
  }

  @Test
  public void saveTranslatesEveryFailure() {
    enqueue(409, "");
    assertThatThrownBy(() -> repository.save(houseNamespace()))
        .isInstanceOf(HouseTableConcurrentUpdateException.class)
        .hasMessageContaining(NAMESPACE_ID);

    enqueue(404, "");
    assertThatThrownBy(() -> repository.save(houseNamespace()))
        .isInstanceOf(HouseTableNotFoundException.class)
        .hasMessageContaining(NAMESPACE_ID);

    for (int status : new int[] {400, 401, 403, 429}) {
      enqueue(status, "");
      assertThatThrownBy(() -> repository.save(houseNamespace()))
          .isInstanceOf(HouseTableCallerException.class)
          .hasMessageContaining(NAMESPACE_ID);
    }

    enqueue(500, "");
    assertThatThrownBy(() -> repository.save(houseNamespace()))
        .isInstanceOf(HouseTableRepositoryStateUnknownException.class)
        .hasMessageContaining(NAMESPACE_ID);
  }

  /**
   * S7: a 200 with no entity used to be answered with a null HouseNamespace, so the caller met a
   * NullPointerException instead of the fact that HTS did not say what it stored.
   */
  @Test
  public void saveWithAnEmptyBodyIsATypedFailureRatherThanNull() {
    enqueue(200, new Gson().toJson(new EntityResponseBodyDatabase()));
    assertThatThrownBy(() -> repository.save(houseNamespace()))
        .isInstanceOf(HouseTableRepositoryStateUnknownException.class)
        .hasMessageContaining(NAMESPACE_ID);
  }

  @Test
  public void findAllMapsEveryStoredNamespace() {
    // The generated listing model exposes no setter for results, so the wire form is built here.
    enqueue(200, "{\"results\":[" + new Gson().toJson(database(7L, "owner", "user")) + "]}");

    assertThat(repository.findAll())
        .singleElement()
        .satisfies(
            found -> {
              assertThat(found.getNamespaceId()).isEqualTo(NAMESPACE_ID);
              assertThat(found.getVersion()).isEqualTo(7L);
            });
  }

  /**
   * S1: findAll was the one method with no error translation at all, so an HTS failure arrived as
   * an untranslated vendor exception and the layer above could not tell a 400 from an outage.
   */
  @Test
  public void findAllTranslatesEveryFailure() {
    enqueue(404, "");
    assertThatThrownBy(() -> repository.findAll()).isInstanceOf(HouseTableNotFoundException.class);

    enqueue(409, "");
    assertThatThrownBy(() -> repository.findAll())
        .isInstanceOf(HouseTableConcurrentUpdateException.class);

    for (int status : new int[] {400, 401, 403, 429}) {
      enqueue(status, "");
      assertThatThrownBy(() -> repository.findAll()).isInstanceOf(HouseTableCallerException.class);
    }

    enqueue(500, "");
    assertThatThrownBy(() -> repository.findAll())
        .isInstanceOf(HouseTableRepositoryStateUnknownException.class);
  }

  @Test
  public void deleteSucceedsOnNoContent() throws InterruptedException {
    enqueue(204, "");
    repository.deleteById(NAMESPACE_ID);
    RecordedRequest request = mockHtsServer.takeRequest();
    assertThat(request.getMethod()).isEqualTo("DELETE");
    assertThat(request.getPath()).contains("databaseId=" + NAMESPACE_ID);
  }

  /** A delete of something absent is a typed failure, not the silent success a read gets. */
  @Test
  public void deleteTranslatesEveryFailure() {
    enqueue(404, "");
    assertThatThrownBy(() -> repository.deleteById(NAMESPACE_ID))
        .isInstanceOf(HouseTableNotFoundException.class)
        .hasMessageContaining(NAMESPACE_ID);

    enqueue(409, "");
    assertThatThrownBy(() -> repository.deleteById(NAMESPACE_ID))
        .isInstanceOf(HouseTableConcurrentUpdateException.class)
        .hasMessageContaining(NAMESPACE_ID);

    for (int status : new int[] {400, 401, 403, 429}) {
      enqueue(status, "");
      assertThatThrownBy(() -> repository.deleteById(NAMESPACE_ID))
          .isInstanceOf(HouseTableCallerException.class)
          .hasMessageContaining(NAMESPACE_ID);
    }

    enqueue(500, "");
    assertThatThrownBy(() -> repository.deleteById(NAMESPACE_ID))
        .isInstanceOf(HouseTableRepositoryStateUnknownException.class)
        .hasMessageContaining(NAMESPACE_ID);
  }

  private static HouseNamespace houseNamespace() {
    return HouseNamespace.builder()
        .namespaceId(NAMESPACE_ID)
        .properties(new LinkedHashMap<>())
        .creationTime(1L)
        .lastModifiedTime(2L)
        .build();
  }

  private static Database database(Long version, String key, String value) {
    return new Database()
        .databaseId(NAMESPACE_ID)
        .version(version)
        .properties(Collections.singletonMap(key, value))
        .creationTime(1L)
        .lastModifiedTime(2L);
  }

  private static void enqueueEntity(int status, Database database) {
    EntityResponseBodyDatabase response = new EntityResponseBodyDatabase();
    response.setEntity(database);
    enqueue(status, new Gson().toJson(response));
  }

  private static void enqueue(int status, String body) {
    mockHtsServer.enqueue(
        new MockResponse()
            .setResponseCode(status)
            .setBody(body)
            .addHeader("Content-Type", "application/json"));
  }
}
