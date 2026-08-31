package com.linkedin.openhouse.tables.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.exception.NamespaceStoreNotBackfilledException;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.internal.catalog.repository.NamespaceStoreCompleteness;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.tables.model.TableDto;
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
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
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
    clusterProperties = Mockito.mock(ClusterProperties.class);
    Mockito.when(clusterProperties.getClusterTablesNamespaceMaxDepth()).thenReturn(1);

    service = new NamespacesServiceImpl();
    service.houseNamespaceRepository = repository;
    service.openHouseInternalRepository = tableRepository;
    service.clusterProperties = clusterProperties;
    service.preservedKeyChecker = new BasePreservedKeyChecker();
    service.authorizationUtils = Mockito.mock(AuthorizationUtils.class);
    service.namespaceStoreReadGate = gateOver(() -> Optional.of(1L));
  }

  private static NamespaceStoreReadGate gateOver(NamespaceStoreCompleteness completeness) {
    NamespaceStoreReadGate gate = new NamespaceStoreReadGate();
    gate.namespaceStoreCompleteness = completeness;
    return gate;
  }

  /**
   * Replaces the startup guard this slice removes. The guard refused any max-depth but 1 and named
   * the five seams nesting needed; the seams now exist, so the same configuration has to be
   * accepted end to end instead of refused. Anything less than a create/list/load/drop round trip
   * would leave "accepted" meaning only "did not throw at startup".
   */
  @Test
  void aNestedNamespaceIsCreatedListedLoadedAndDropped() {
    Mockito.when(clusterProperties.getClusterTablesNamespaceMaxDepth()).thenReturn(2);
    service.createNamespace(Namespace.of("db"), Collections.emptyMap(), PRINCIPAL);

    NamespaceMetadata created =
        service.createNamespace(
            Namespace.of("db", "sub"), Collections.singletonMap("owner", "a"), PRINCIPAL);
    assertThat(created.getNamespace()).isEqualTo(Namespace.of("db", "sub"));
    assertThat(repository.findById("db.sub")).isPresent();

    assertThat(service.listNamespaces(Namespace.of("db"), PRINCIPAL))
        .containsExactly(Namespace.of("db", "sub"));
    assertThat(service.loadNamespaceMetadata(Namespace.of("db", "sub"), PRINCIPAL).getProperties())
        .containsEntry("owner", "a");
    assertThat(service.namespaceExists(Namespace.of("db", "sub"), PRINCIPAL)).isTrue();

    // The parent is occupied while the child is there, and free once it is gone.
    assertThatThrownBy(() -> service.dropNamespace(Namespace.of("db"), PRINCIPAL))
        .isInstanceOf(NamespaceNotEmptyException.class);
    service.dropNamespace(Namespace.of("db", "sub"), PRINCIPAL);
    assertThat(service.listNamespaces(Namespace.of("db"), PRINCIPAL)).isEmpty();
    assertThatCode(() -> service.dropNamespace(Namespace.of("db"), PRINCIPAL))
        .doesNotThrowAnyException();
  }

  /**
   * The nested listing has to come from the store's range query rather than a filtered findAll(),
   * and it has to stop at the direct children: a grandchild is in the subtree range but is not a
   * child of the parent that was asked about.
   *
   * <p>Calibration: filtering findAll() instead trips the stub's findAll() assertion; dropping the
   * direct-child filter from the stub's childrenOf turns the grandchild into a third element.
   */
  @Test
  void aNestedListingComesFromTheRangeQueryAndStopsAtDirectChildren() {
    Mockito.when(clusterProperties.getClusterTablesNamespaceMaxDepth()).thenReturn(3);
    repository.save(namespace("db"));
    repository.save(namespace("db.a"));
    repository.save(namespace("db.b"));
    repository.save(namespace("db.a.deep"));
    repository.save(namespace("dbx"));
    repository.failOnFindAll = true;

    assertThat(service.listNamespaces(Namespace.of("db"), PRINCIPAL))
        .containsExactlyInAnyOrder(Namespace.of("db", "a"), Namespace.of("db", "b"));
  }

  /**
   * The top-level listing is the one that still reads the store, and it must answer with the roots
   * rather than everything the store holds.
   */
  @Test
  void theTopLevelListingAnswersWithRootsOnly() {
    Mockito.when(clusterProperties.getClusterTablesNamespaceMaxDepth()).thenReturn(2);
    repository.save(namespace("db"));
    repository.save(namespace("db.sub"));

    assertThat(service.listNamespaces(Namespace.empty(), PRINCIPAL))
        .containsExactly(Namespace.of("db"));
  }

  /** The configured depth is a bound, not a suggestion: one level past it is a validation error. */
  @Test
  void aNamespaceDeeperThanTheConfiguredMaximumIsRejected() {
    Mockito.when(clusterProperties.getClusterTablesNamespaceMaxDepth()).thenReturn(2);
    service.createNamespace(Namespace.of("db"), Collections.emptyMap(), PRINCIPAL);
    service.createNamespace(Namespace.of("db", "sub"), Collections.emptyMap(), PRINCIPAL);

    assertThatThrownBy(
            () ->
                service.createNamespace(
                    Namespace.of("db", "sub", "deeper"), Collections.emptyMap(), PRINCIPAL))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("db.sub.deeper");
    assertThat(repository.findById("db.sub.deeper")).isEmpty();
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

  /**
   * The behaviour this slice changes, stated as plainly as it can be: a database with tables in it
   * and no namespace row does not exist. It used to, through a fallback that read the table store,
   * and every read path below went through that fallback. Nothing here may keep doing it -- if any
   * one of them still consults the table store for existence, a namespace dropped from the store
   * comes back to life because a table still names it.
   */
  @Test
  void aDatabaseWithTablesButNoRowIsNotFound() {
    givenTablesIn("prod");

    assertThat(service.namespaceExists(Namespace.of("prod"), PRINCIPAL)).isFalse();
    assertThatThrownBy(() -> service.loadNamespaceMetadata(Namespace.of("prod"), PRINCIPAL))
        .isInstanceOf(NoSuchNamespaceException.class);
    assertThat(service.listNamespaces(Namespace.empty(), PRINCIPAL)).isEmpty();
    assertThatThrownBy(() -> service.dropNamespace(Namespace.of("prod"), PRINCIPAL))
        .isInstanceOf(NoSuchNamespaceException.class);
    assertThatThrownBy(
            () ->
                service.updateProperties(
                    Namespace.of("prod"),
                    Collections.emptySet(),
                    Collections.singletonMap("owner", "a"),
                    PRINCIPAL))
        .isInstanceOf(NoSuchNamespaceException.class);
    // ...and the sixth path: the name is free to create, because nothing holds it.
    assertThatCode(
            () -> service.createNamespace(Namespace.of("prod"), Collections.emptyMap(), PRINCIPAL))
        .doesNotThrowAnyException();
  }

  /**
   * The distinction the drop path has to keep: existence is the namespace store's alone, but
   * occupancy is not. A namespace whose row exists and whose database holds tables is not empty,
   * and only the table store knows that.
   */
  @Test
  void aNamespaceHoldingTablesCannotBeDropped() {
    repository.save(namespace("prod"));
    givenTablesIn("prod");

    assertThatThrownBy(() -> service.dropNamespace(Namespace.of("prod"), PRINCIPAL))
        .isInstanceOf(NamespaceNotEmptyException.class);
    assertThat(repository.findById("prod")).isPresent();

    Mockito.when(tableRepository.searchTables("prod")).thenReturn(Collections.emptyList());
    assertThatCode(() -> service.dropNamespace(Namespace.of("prod"), PRINCIPAL))
        .doesNotThrowAnyException();
    assertThat(repository.findById("prod")).isEmpty();
  }

  /** A stored namespace holding nothing is the only thing a drop may remove. */
  @Test
  void everyReadPathAnswersFromTheStore() {
    repository.save(namespace("db"));

    assertThat(service.namespaceExists(Namespace.of("db"), PRINCIPAL)).isTrue();
    assertThat(service.loadNamespaceMetadata(Namespace.of("db"), PRINCIPAL).getNamespace())
        .isEqualTo(Namespace.of("db"));
    assertThat(service.listNamespaces(Namespace.empty(), PRINCIPAL))
        .containsExactly(Namespace.of("db"));
    assertThatThrownBy(
            () -> service.createNamespace(Namespace.of("db"), Collections.emptyMap(), PRINCIPAL))
        .isInstanceOf(AlreadyExistsException.class);
    assertThatCode(
            () ->
                service.updateProperties(
                    Namespace.of("db"),
                    Collections.emptySet(),
                    Collections.singletonMap("owner", "a"),
                    PRINCIPAL))
        .doesNotThrowAnyException();
    assertThatCode(() -> service.dropNamespace(Namespace.of("db"), PRINCIPAL))
        .doesNotThrowAnyException();
  }

  /**
   * Every read is gated, and the refusal has to carry the remedy: the caller who sees it is not the
   * operator who can fix it, and the only thing connecting them is this string.
   */
  @Test
  void anUnverifiedStoreRefusesEveryReadAndNamesTheBackfill() {
    service.namespaceStoreReadGate = gateOver(Optional::empty);
    repository.save(namespace("db"));

    for (org.junit.jupiter.api.function.Executable read : gatedReads()) {
      assertThatThrownBy(read::execute)
          .isInstanceOf(NamespaceStoreNotBackfilledException.class)
          .hasMessageContaining("/hts/databases/backfill")
          .hasMessageContaining("verify");
    }
  }

  /** The same reads, against the same store, once a verification pass has recorded completeness. */
  @Test
  void aVerifiedStoreServesTheSameReads() {
    repository.save(namespace("db"));
    for (org.junit.jupiter.api.function.Executable read : gatedReads()) {
      assertThatCode(read::execute).doesNotThrowAnyException();
    }
  }

  /** Every namespace path that reads the store, so the two tests above cover all of them. */
  private List<org.junit.jupiter.api.function.Executable> gatedReads() {
    List<org.junit.jupiter.api.function.Executable> reads = new ArrayList<>();
    reads.add(() -> service.namespaceExists(Namespace.of("db"), PRINCIPAL));
    reads.add(() -> service.loadNamespaceMetadata(Namespace.of("db"), PRINCIPAL));
    reads.add(() -> service.listNamespaces(Namespace.empty(), PRINCIPAL));
    reads.add(
        () -> service.createNamespace(Namespace.of("fresh"), Collections.emptyMap(), PRINCIPAL));
    reads.add(
        () ->
            service.updateProperties(
                Namespace.of("db"),
                Collections.emptySet(),
                Collections.singletonMap("owner", "a"),
                PRINCIPAL));
    reads.add(() -> service.dropNamespace(Namespace.of("db"), PRINCIPAL));
    return reads;
  }

  private void givenTablesIn(String databaseId) {
    Mockito.when(tableRepository.searchTables(databaseId))
        .thenReturn(
            Collections.singletonList(
                TableDto.builder().databaseId(databaseId).tableId("t").build()));
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
    public List<HouseNamespace> childrenOf(String encodedParent) {
      return rows.values().stream()
          .filter(
              namespace -> NamespaceUtil.isDirectChild(encodedParent, namespace.getNamespaceId()))
          .collect(Collectors.toList());
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
