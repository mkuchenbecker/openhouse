package com.linkedin.openhouse.tables.mock.service;

import static com.linkedin.openhouse.tables.mock.RequestConstants.*;

import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
import com.linkedin.openhouse.common.metrics.MetricsConstant;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockState;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.dto.mapper.TablesMapper;
import com.linkedin.openhouse.tables.dto.mapper.TablesMapperImpl;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.services.IcebergSnapshotsService;
import com.linkedin.openhouse.tables.utils.TableUUIDGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.util.Pair;

@SpringBootTest
public class IcebergSnapshotsServiceTest {

  private static final String TEST_TABLE_CREATOR = "test_user";

  @Autowired private ApplicationContext applicationContext;

  @Autowired private IcebergSnapshotsService service;

  @Autowired private TablesMapper tablesMapper;

  @MockBean private TableUUIDGenerator tableUUIDGenerator;

  @Autowired private HouseNamespaceRepository houseNamespaceRepository;

  @Autowired private MeterRegistry meterRegistry;

  private OpenHouseInternalRepository mockRepository;

  /**
   * The rows the namespace store holds during a test. The store itself is a mock in this context
   * (see {@code MockTablesApplication}), so it is given the one behaviour these tests are about:
   * what is saved can be read back.
   */
  private Map<String, HouseNamespace> namespaceStore;

  @Captor ArgumentCaptor<TableDto> tableDtoArgumentCaptor;

  @BeforeEach
  void setup() {
    mockRepository = applicationContext.getBean(OpenHouseInternalRepository.class);
    namespaceStore = new LinkedHashMap<>();
    Mockito.when(houseNamespaceRepository.findById(Mockito.anyString()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(namespaceStore.get(invocation.<String>getArgument(0))));
    Mockito.when(houseNamespaceRepository.save(Mockito.any(HouseNamespace.class)))
        .thenAnswer(
            invocation -> {
              HouseNamespace saved = invocation.getArgument(0);
              namespaceStore.put(saved.getNamespaceId(), saved);
              return saved;
            });
  }

  /**
   * A snapshot commit against a table that does not exist creates the table, and so creates the
   * database it names. That database has to be registered in the namespace store, or it is a
   * database the namespace API can only see by deriving it from the tables it holds — the
   * derivation this work exists to delete.
   */
  @Test
  public void testTableCreatedRegistersItsNamespace() {
    final IcebergSnapshotsRequestBody requestBody =
        TEST_ICEBERG_SNAPSHOTS_INITIAL_VERSION_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto = TableDto.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.empty());
    Mockito.when(mockRepository.save(Mockito.any(TableDto.class))).thenReturn(tableDto);

    service.putIcebergSnapshots(dbId, tableId, requestBody, TEST_TABLE_CREATOR);

    Assertions.assertTrue(
        namespaceStore.containsKey(dbId),
        "Creating a table through a snapshot commit must register its database as a namespace");
  }

  /**
   * Registration runs before the table write. When the write then fails the leftover is a database
   * row nothing points at, which is inert; the opposite order leaves a table whose database the
   * namespace store has never heard of, and nothing the caller sees can repair that.
   */
  @Test
  public void testNamespaceIsRegisteredBeforeTheTableWrite() {
    final IcebergSnapshotsRequestBody requestBody =
        TEST_ICEBERG_SNAPSHOTS_INITIAL_VERSION_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.empty());
    Mockito.when(mockRepository.save(Mockito.any(TableDto.class)))
        .thenThrow(CommitFailedException.class);

    Assertions.assertThrows(
        EntityConcurrentModificationException.class,
        () -> service.putIcebergSnapshots(dbId, tableId, requestBody, TEST_TABLE_CREATOR));

    Assertions.assertTrue(
        namespaceStore.containsKey(dbId),
        "Registration must already have happened when the table write fails");
  }

  /**
   * The flip the fallback deletion brought with it. This asserted the opposite -- that a namespace
   * store which is down must not take table creation down with it -- and that was right while reads
   * derived a database's existence from its tables, because the missing row cost nothing a client
   * could see. It costs everything now: the commit would create a table in a database the catalog
   * denies exists, and no later request could tell that had happened.
   */
  @Test
  public void testNamespaceRegistrationFailureFailsTableCreation() {
    final IcebergSnapshotsRequestBody requestBody =
        TEST_ICEBERG_SNAPSHOTS_INITIAL_VERSION_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto = TableDto.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.empty());
    Mockito.when(mockRepository.save(Mockito.any(TableDto.class))).thenReturn(tableDto);
    Mockito.when(houseNamespaceRepository.save(Mockito.any(HouseNamespace.class)))
        .thenThrow(new RuntimeException("namespace store unavailable"));

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class,
            () -> service.putIcebergSnapshots(dbId, tableId, requestBody, TEST_TABLE_CREATOR));

    Assertions.assertEquals("namespace store unavailable", thrown.getMessage());
    Assertions.assertTrue(namespaceStore.isEmpty(), "Nothing was registered");
    Mockito.verify(mockRepository, Mockito.never()).save(Mockito.any(TableDto.class));
  }

  /**
   * The counter survives the flip. It used to be the only trace of a swallowed failure; it now
   * measures table writes lost to the namespace store, which is a different question with the same
   * answer and worth keeping separate from the client's error.
   */
  @Test
  public void testNamespaceRegistrationFailureIsCounted() {
    final IcebergSnapshotsRequestBody requestBody =
        TEST_ICEBERG_SNAPSHOTS_INITIAL_VERSION_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto = TableDto.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.empty());
    Mockito.when(mockRepository.save(Mockito.any(TableDto.class))).thenReturn(tableDto);
    Mockito.when(houseNamespaceRepository.save(Mockito.any(HouseNamespace.class)))
        .thenThrow(new RuntimeException("namespace store unavailable"));
    double before = registrationFailures();

    Assertions.assertThrows(
        RuntimeException.class,
        () -> service.putIcebergSnapshots(dbId, tableId, requestBody, TEST_TABLE_CREATOR));

    Assertions.assertEquals(
        before + 1,
        registrationFailures(),
        "a registration failure has to be countable, not only visible to the one caller");
  }

  /** A registration that succeeds must not look like drift. */
  @Test
  public void testSuccessfulNamespaceRegistrationIsNotCounted() {
    final IcebergSnapshotsRequestBody requestBody =
        TEST_ICEBERG_SNAPSHOTS_INITIAL_VERSION_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto = TableDto.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.empty());
    Mockito.when(mockRepository.save(Mockito.any(TableDto.class))).thenReturn(tableDto);
    double before = registrationFailures();

    service.putIcebergSnapshots(dbId, tableId, requestBody, TEST_TABLE_CREATOR);

    Assertions.assertTrue(namespaceStore.containsKey(dbId), "Precondition: registration succeeded");
    Assertions.assertEquals(before, registrationFailures());
  }

  private double registrationFailures() {
    Counter counter =
        meterRegistry.find(MetricsConstant.NAMESPACE_REGISTRATION_FAILED_CTR).counter();
    return counter == null ? 0d : counter.count();
  }

  /** Only the create branch registers: an update names a database that already exists. */
  @Test
  public void testTableUpdateDoesNotRegisterANamespace() {
    final IcebergSnapshotsRequestBody requestBody = TEST_ICEBERG_SNAPSHOTS_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto =
        tablesMapper.toTableDto(
            TableDto.builder()
                .clusterId(requestBody.getCreateUpdateTableRequestBody().getClusterId())
                .databaseId(dbId)
                .tableId(tableId)
                .tableLocation(requestBody.getBaseTableVersion())
                .tableCreator(TEST_TABLE_CREATOR)
                .build(),
            requestBody);
    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.of(tableDto));
    Mockito.when(mockRepository.save(Mockito.any(TableDto.class))).thenReturn(tableDto);

    service.putIcebergSnapshots(dbId, tableId, requestBody, null);

    Assertions.assertTrue(namespaceStore.isEmpty());
  }

  @Test
  public void testRepositoryMockWired() {
    Assertions.assertEquals(
        Mockito.mock(OpenHouseInternalRepository.class).getClass(),
        applicationContext.getBean(OpenHouseInternalRepository.class).getClass());
  }

  @Test
  public void testTablesMapperImplWired() {
    Assertions.assertEquals(
        TablesMapperImpl.class, applicationContext.getBean(TablesMapper.class).getClass());
  }

  @Test
  public void testTableCreated() {
    final IcebergSnapshotsRequestBody requestBody =
        TEST_ICEBERG_SNAPSHOTS_INITIAL_VERSION_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto = TableDto.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.empty());
    Mockito.when(mockRepository.save(tableDtoArgumentCaptor.capture())).thenReturn(tableDto);

    Pair<TableDto, Boolean> result =
        service.putIcebergSnapshots(dbId, tableId, requestBody, TEST_TABLE_CREATOR);
    Assertions.assertEquals(tableDto, result.getFirst(), "Returned DTO must be the mock value");
    Assertions.assertTrue(result.getSecond(), "Table must be created");

    verifyCalls(key, TEST_TABLE_CREATOR, requestBody.getCreateUpdateTableRequestBody());
  }

  @Test
  public void testPutTableExceptionHandling() {
    final IcebergSnapshotsRequestBody requestBody =
        TEST_ICEBERG_SNAPSHOTS_INITIAL_VERSION_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();

    // Mocking exception and ensure it is propogated to the right layer
    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenThrow(RequestValidationFailureException.class);

    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.empty());
    Assertions.assertThrows(
        RequestValidationFailureException.class,
        () -> service.putIcebergSnapshots(dbId, tableId, requestBody, TEST_TABLE_CREATOR));

    // Mocking Concurrency failure
    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.save(Mockito.any(TableDto.class)))
        .thenThrow(CommitFailedException.class);

    Assertions.assertThrows(
        EntityConcurrentModificationException.class,
        () -> service.putIcebergSnapshots(dbId, tableId, requestBody, TEST_TABLE_CREATOR));
  }

  @Test
  public void testPutTableSnapshotsValidationExceptionHandling() {
    final IcebergSnapshotsRequestBody requestBody =
        TEST_ICEBERG_SNAPSHOTS_INITIAL_VERSION_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.empty());
    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.save(Mockito.any(TableDto.class)))
        .thenThrow(BadRequestException.class);

    Assertions.assertThrows(
        RequestValidationFailureException.class,
        () -> service.putIcebergSnapshots(dbId, tableId, requestBody, TEST_TABLE_CREATOR));
  }

  @Test
  public void testTableUpdated() {
    final IcebergSnapshotsRequestBody requestBody = TEST_ICEBERG_SNAPSHOTS_REQUEST_BODY;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto =
        tablesMapper.toTableDto(
            TableDto.builder()
                .clusterId(requestBody.getCreateUpdateTableRequestBody().getClusterId())
                .databaseId(dbId)
                .tableId(tableId)
                .tableLocation(requestBody.getBaseTableVersion())
                .tableCreator(TEST_TABLE_CREATOR)
                .build(),
            requestBody);
    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.of(tableDto));
    Mockito.when(mockRepository.save(tableDtoArgumentCaptor.capture())).thenReturn(tableDto);

    Pair<TableDto, Boolean> result = service.putIcebergSnapshots(dbId, tableId, requestBody, null);
    Assertions.assertEquals(tableDto, result.getFirst(), "Returned DTO must be the mock value");
    Assertions.assertFalse(result.getSecond(), "Table must be found in repository");

    verifyCalls(key, TEST_TABLE_CREATOR, requestBody.getCreateUpdateTableRequestBody());
  }

  @Test
  public void testTableUpdatedForLockedTableThrowsException() {
    final IcebergSnapshotsRequestBody requestBody = TEST_ICEBERG_SNAPSHOTS_REQUEST_BODY_FOR_LOCKED;
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto =
        tablesMapper.toTableDto(
            TableDto.builder()
                .clusterId(requestBody.getCreateUpdateTableRequestBody().getClusterId())
                .databaseId(dbId)
                .tableId(tableId)
                .tableLocation(requestBody.getBaseTableVersion())
                .policies(
                    Policies.builder().lockState(LockState.builder().locked(true).build()).build())
                .tableCreator(TEST_TABLE_CREATOR)
                .build(),
            requestBody);
    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.of(tableDto));
    Mockito.when(mockRepository.save(tableDtoArgumentCaptor.capture())).thenReturn(tableDto);

    Assertions.assertThrows(
        UnsupportedClientOperationException.class,
        () -> service.putIcebergSnapshots(dbId, tableId, requestBody, null));
  }

  @Test
  public void testReplaceCommitOnLockedTableThrowsException() {
    // A CREATE OR REPLACE (RTAS) against a locked table must be rejected just like a normal write.
    // Regression guard for the gap where the replace-commit path bypassed the table lock and could
    // silently overwrite a locked table.
    final IcebergSnapshotsRequestBody base = TEST_ICEBERG_SNAPSHOTS_REQUEST_BODY_FOR_LOCKED;
    final IcebergSnapshotsRequestBody requestBody =
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(base.getBaseTableVersion())
            .jsonSnapshots(base.getJsonSnapshots())
            .snapshotRefs(base.getSnapshotRefs())
            .createUpdateTableRequestBody(
                base.getCreateUpdateTableRequestBody().toBuilder().replaceCommit(true).build())
            .build();
    final String dbId = requestBody.getCreateUpdateTableRequestBody().getDatabaseId();
    final String tableId = requestBody.getCreateUpdateTableRequestBody().getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto =
        tablesMapper.toTableDto(
            TableDto.builder()
                .clusterId(requestBody.getCreateUpdateTableRequestBody().getClusterId())
                .databaseId(dbId)
                .tableId(tableId)
                .tableLocation(requestBody.getBaseTableVersion())
                .policies(
                    Policies.builder().lockState(LockState.builder().locked(true).build()).build())
                .tableCreator(TEST_TABLE_CREATOR)
                .build(),
            requestBody);
    Mockito.when(tableUUIDGenerator.generateUUID(Mockito.any(IcebergSnapshotsRequestBody.class)))
        .thenReturn(UUID.randomUUID());
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.of(tableDto));
    Mockito.when(mockRepository.save(tableDtoArgumentCaptor.capture())).thenReturn(tableDto);

    Assertions.assertThrows(
        UnsupportedClientOperationException.class,
        () -> service.putIcebergSnapshots(dbId, tableId, requestBody, null));
  }

  private void verifyCalls(
      TableDtoPrimaryKey expectedKey,
      String expectedTableCreator,
      CreateUpdateTableRequestBody expectedRequestBody) {
    Mockito.verify(mockRepository, Mockito.times(1)).findById(Mockito.eq(expectedKey));
    Mockito.verify(mockRepository, Mockito.times(1)).save(tableDtoArgumentCaptor.capture());

    TableDto tableDto = tableDtoArgumentCaptor.getValue();
    Assertions.assertEquals(expectedRequestBody.getDatabaseId(), tableDto.getDatabaseId());
    Assertions.assertEquals(expectedRequestBody.getTableId(), tableDto.getTableId());
    Assertions.assertEquals(expectedRequestBody.getClusterId(), tableDto.getClusterId());
    Assertions.assertEquals(expectedTableCreator, tableDto.getTableCreator());
    Assertions.assertEquals(expectedRequestBody.getSchema(), tableDto.getSchema());
    Assertions.assertEquals(expectedRequestBody.getPolicies(), tableDto.getPolicies());
    Assertions.assertEquals(
        expectedRequestBody.getTableProperties(), tableDto.getTableProperties());
  }
}
