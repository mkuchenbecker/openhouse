package com.linkedin.openhouse.tables.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.repository.impl.BasePreservedKeyChecker;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit coverage for the parts of the namespace service whose whole point is what they do NOT do:
 * scans they must not issue, configurations they must refuse, and writes they must not let past.
 */
public class NamespacesServiceImplTest {

  private static final String PRINCIPAL = "testuser";

  private StubNamespaceRepository repository;
  private OpenHouseInternalRepository tableRepository;
  private ClusterProperties clusterProperties;
  private NamespacesServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = new StubNamespaceRepository();
    tableRepository = Mockito.mock(OpenHouseInternalRepository.class);
    Mockito.when(tableRepository.searchTables(Mockito.anyString()))
        .thenReturn(Collections.emptyList());
    Mockito.when(tableRepository.findAllIds()).thenReturn(Collections.emptyList());
    clusterProperties = Mockito.mock(ClusterProperties.class);
    Mockito.when(clusterProperties.getClusterTablesNamespaceMaxDepth()).thenReturn(1);

    service = new NamespacesServiceImpl();
    service.houseNamespaceRepository = repository;
    service.openHouseInternalRepository = tableRepository;
    service.clusterProperties = clusterProperties;
    service.preservedKeyChecker = new BasePreservedKeyChecker();
    service.authorizationUtils = Mockito.mock(AuthorizationUtils.class);
  }

  /**
   * B8: raising cluster.tables.namespace.max-depth widens one validator and nothing below it, so a
   * cluster that asks for nesting has to be told at startup rather than on the first two-level
   * create, where it would have arrived as a 500 out of /hts/databases.
   */
  @Test
  void aMaxDepthAboveOneIsRefusedAtStartupWithTheSeamsNamed() {
    Mockito.when(clusterProperties.getClusterTablesNamespaceMaxDepth()).thenReturn(2);
    assertThatThrownBy(() -> service.rejectUnimplementedNamespaceDepth())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cluster.tables.namespace.max-depth is 2")
        .hasMessageContaining("/hts/databases")
        .hasMessageContaining("isTableNamespace")
        .hasMessageContaining("childrenOf");

    Mockito.when(clusterProperties.getClusterTablesNamespaceMaxDepth()).thenReturn(1);
    assertThatCode(() -> service.rejectUnimplementedNamespaceDepth()).doesNotThrowAnyException();
  }

  /**
   * B5: listNamespaces reads the whole store, and dropNamespace used to ask it for children. Past
   * the point where that unpaged read fails, no namespace could be dropped at all. At the shipped
   * depth the child scan is provably empty -- validate() forbids the namespaces it looks for -- so
   * it must not be issued.
   */
  @Test
  void droppingANamespaceDoesNotScanTheWholeStore() {
    repository.save(namespace("db"));
    repository.failOnFindAll = true;

    assertThat(service.listNamespaces(Namespace.of("db"), PRINCIPAL)).isEmpty();

    assertThatCode(() -> service.dropNamespace(Namespace.of("db"), PRINCIPAL))
        .doesNotThrowAnyException();
    assertThat(repository.findById("db")).isEmpty();
  }

  /** The top-level listing still reads the store; only the child scan short-circuits. */
  @Test
  void theTopLevelListingStillReadsTheStore() {
    repository.save(namespace("db"));
    assertThat(service.listNamespaces(Namespace.empty(), PRINCIPAL))
        .containsExactly(Namespace.of("db"));
  }

  /**
   * B4 at the service seam: two writers read the same namespace, both compose an update from it,
   * and the second one to arrive is based on state that no longer exists. It has to lose loudly.
   */
  @Test
  void aSecondUpdateFromTheSameReadFailsTheCommit() {
    repository.save(namespace("db"));

    // Both writers read version 0. Writer A commits, taking the row to version 1.
    service.updateProperties(
        Namespace.of("db"),
        Collections.emptySet(),
        Collections.singletonMap("owner", "a"),
        PRINCIPAL);

    // Writer B's read is now stale; the store rejects its write and the service says so as a
    // commit failure, which the REST layer renders as 409 rather than 500.
    repository.rejectNextSave = true;
    assertThatThrownBy(
            () ->
                service.updateProperties(
                    Namespace.of("db"),
                    Collections.emptySet(),
                    Collections.singletonMap("owner", "b"),
                    PRINCIPAL))
        .isInstanceOf(CommitFailedException.class)
        .hasMessageContaining("db");

    assertThat(repository.findById("db").get().getProperties()).containsEntry("owner", "a");
  }

  /** B4: an update carries the version it read, so the store can compare against it. */
  @Test
  void anUpdateCarriesTheVersionItRead() {
    repository.save(namespace("db"));
    service.updateProperties(
        Namespace.of("db"),
        Collections.emptySet(),
        Collections.singletonMap("owner", "a"),
        PRINCIPAL);
    assertThat(repository.lastSavedVersion).isEqualTo(0L);
  }

  /** B4: a create asserts that no row exists, which is a null version on the wire. */
  @Test
  void aCreateCarriesNoVersion() {
    service.createNamespace(Namespace.of("db"), Collections.emptyMap(), PRINCIPAL);
    assertThat(repository.lastSavedVersion).isNull();
  }

  /**
   * B6: the store folds case, listings do not. Answering with the requested spelling names a
   * namespace that appears in no listing and that DELETE would then destroy.
   */
  @Test
  void aLoadAnswersWithTheStoredSpellingNotTheRequestedOne() {
    repository.save(namespace("MyDb"));

    NamespaceMetadata loaded = service.loadNamespaceMetadata(Namespace.of("mydb"), PRINCIPAL);
    assertThat(loaded.getNamespace()).isEqualTo(Namespace.of("MyDb"));

    NamespaceMetadata created =
        service.createNamespace(Namespace.of("Other"), Collections.emptyMap(), PRINCIPAL);
    assertThat(created.getNamespace()).isEqualTo(Namespace.of("Other"));
  }

  /**
   * B1: a reserved key is a bad request about the property, not a claim that a namespace which
   * plainly exists does not. The service raises ValidationException and nothing above it may
   * rewrite that into a 404.
   */
  @Test
  void aReservedKeyIsAValidationFailureAndTheNamespaceStillExists() {
    repository.save(namespace("db"));
    assertThatThrownBy(
            () ->
                service.updateProperties(
                    Namespace.of("db"),
                    Collections.emptySet(),
                    Collections.singletonMap("openhouse.foo", "x"),
                    PRINCIPAL))
        .isInstanceOf(ValidationException.class)
        .isNotInstanceOf(NoSuchNamespaceException.class);

    // S5: policies is reserved by the same checker that reserves it for tables.
    assertThatThrownBy(
            () ->
                service.updateProperties(
                    Namespace.of("db"),
                    Collections.emptySet(),
                    Collections.singletonMap("policies", "x"),
                    PRINCIPAL))
        .isInstanceOf(ValidationException.class);
  }

  /** B7: the bounds hold on the merged result, not only on what the request carried. */
  @Test
  void thePropertyBoundsHoldOnTheMergedResult() {
    Map<String, String> existing = new LinkedHashMap<>();
    for (int i = 0; i < 100; i++) {
      existing.put("k" + i, "v");
    }
    repository.save(
        HouseNamespace.builder()
            .namespaceId("db")
            .properties(existing)
            .creationTime(1L)
            .lastModifiedTime(1L)
            .build());

    assertThatThrownBy(
            () ->
                service.updateProperties(
                    Namespace.of("db"),
                    Collections.emptySet(),
                    Collections.singletonMap("one_too_many", "v"),
                    PRINCIPAL))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("entries");
  }

  /** B3: a database known only from the table store is a namespace all the same. */
  @Test
  void aDatabaseKnownOnlyFromTheTableStoreExists() {
    Mockito.when(tableRepository.searchTables("prod"))
        .thenReturn(
            Collections.singletonList(TableDto.builder().databaseId("prod").tableId("t").build()));
    Mockito.when(tableRepository.findAllIds())
        .thenReturn(
            Collections.singletonList(
                TableDtoPrimaryKey.builder().databaseId("prod").tableId("t").build()));

    assertThat(service.namespaceExists(Namespace.of("prod"), PRINCIPAL)).isTrue();
    assertThat(service.loadNamespaceMetadata(Namespace.of("prod"), PRINCIPAL).getNamespace())
        .isEqualTo(Namespace.of("prod"));
    assertThat(service.listNamespaces(Namespace.empty(), PRINCIPAL))
        .containsExactly(Namespace.of("prod"));
    assertThat(repository.findById("prod")).isEmpty();
  }

  /** B3: registering is idempotent, and it is what a table write does. */
  @Test
  void ensureNamespaceIsIdempotent() {
    service.ensureNamespace("db");
    service.ensureNamespace("db");
    assertThat(repository.rows).hasSize(1);
    assertThat(service.namespaceExists(Namespace.of("db"), PRINCIPAL)).isTrue();
  }

  private static HouseNamespace namespace(String namespaceId) {
    return HouseNamespace.builder()
        .namespaceId(namespaceId)
        .properties(new LinkedHashMap<>())
        .creationTime(1L)
        .lastModifiedTime(1L)
        .build();
  }

  /**
   * A stand-in for the House Tables-backed store with the two behaviours that matter here: it folds
   * case the way the real store does, and it versions rows the way the real store does.
   */
  private static final class StubNamespaceRepository implements HouseNamespaceRepository {
    private final Map<String, HouseNamespace> rows = new LinkedHashMap<>();
    private boolean failOnFindAll;
    private boolean rejectNextSave;
    private Long lastSavedVersion;

    @Override
    @SuppressWarnings("unchecked")
    public <S extends HouseNamespace> S save(S entity) {
      lastSavedVersion = entity.getVersion();
      if (rejectNextSave) {
        rejectNextSave = false;
        throw new HouseTableConcurrentUpdateException("conflict", new RuntimeException());
      }
      long next = entity.getVersion() == null ? 0L : entity.getVersion() + 1;
      HouseNamespace stored = entity.toBuilder().version(next).build();
      rows.put(key(entity.getNamespaceId()), stored);
      return (S) stored;
    }

    @Override
    public Optional<HouseNamespace> findById(String namespaceId) {
      return Optional.ofNullable(rows.get(key(namespaceId)));
    }

    @Override
    public boolean existsById(String namespaceId) {
      return findById(namespaceId).isPresent();
    }

    @Override
    public Iterable<HouseNamespace> findAll() {
      if (failOnFindAll) {
        throw new AssertionError("findAll() must not be reached on this path");
      }
      return new ArrayList<>(rows.values());
    }

    @Override
    public void deleteById(String namespaceId) {
      rows.remove(key(namespaceId));
    }

    @Override
    public void delete(HouseNamespace entity) {
      deleteById(entity.getNamespaceId());
    }

    private static String key(String namespaceId) {
      return namespaceId.toLowerCase(Locale.ROOT);
    }

    @Override
    public <S extends HouseNamespace> Iterable<S> saveAll(Iterable<S> entities) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<HouseNamespace> findAllById(Iterable<String> namespaceIds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long count() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllById(Iterable<? extends String> namespaceIds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll(Iterable<? extends HouseNamespace> entities) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll() {
      throw new UnsupportedOperationException();
    }
  }

  @SuppressWarnings("unused")
  private static List<TableDto> noTables() {
    return Collections.emptyList();
  }
}
