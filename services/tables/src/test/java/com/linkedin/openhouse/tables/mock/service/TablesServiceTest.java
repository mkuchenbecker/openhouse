package com.linkedin.openhouse.tables.mock.service;

import static com.linkedin.openhouse.tables.mock.RequestConstants.*;

import com.linkedin.openhouse.common.metrics.MetricsConstant;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableCallerException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.services.TablesService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
public class TablesServiceTest {

  private static final String TEST_TABLE_CREATOR = "test_user";

  @Autowired private ApplicationContext applicationContext;

  @Autowired private TablesService service;

  @Autowired private HouseNamespaceRepository houseNamespaceRepository;

  @Autowired private MeterRegistry meterRegistry;

  private OpenHouseInternalRepository mockRepository;

  @BeforeEach
  void setup() {
    mockRepository = applicationContext.getBean(OpenHouseInternalRepository.class);
  }

  /**
   * Creating a table through {@code putTable} registers its database, and a failure to do so now
   * fails the write rather than being swallowed: nothing derives a database's existence from its
   * tables any more, so the table would land in a database the catalog denies exists. The counter
   * stays, because a client's 500 is not a signal an operator can watch. {@code
   * IcebergSnapshotsServiceTest} covers the snapshot-commit twin of this path.
   */
  @Test
  public void testNamespaceRegistrationFailureFailsTheTableWriteAndIsCounted() {
    final String dbId = TEST_CREATE_TABLE_REQUEST_BODY.getDatabaseId();
    final String tableId = TEST_CREATE_TABLE_REQUEST_BODY.getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto = TableDto.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.empty());
    Mockito.when(mockRepository.save(Mockito.any(TableDto.class))).thenReturn(tableDto);
    Mockito.when(houseNamespaceRepository.findById(Mockito.anyString()))
        .thenReturn(Optional.empty());
    Mockito.when(houseNamespaceRepository.save(Mockito.any(HouseNamespace.class)))
        .thenThrow(new RuntimeException("namespace store unavailable"));
    Counter counter =
        meterRegistry.find(MetricsConstant.NAMESPACE_REGISTRATION_FAILED_CTR).counter();
    double before = counter == null ? 0d : counter.count();

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class,
            () -> service.putTable(TEST_CREATE_TABLE_REQUEST_BODY, TEST_TABLE_CREATOR, false));

    Assertions.assertEquals("namespace store unavailable", thrown.getMessage());
    Mockito.verify(mockRepository, Mockito.never()).save(Mockito.any(TableDto.class));
    Assertions.assertEquals(
        before + 1,
        meterRegistry.counter(MetricsConstant.NAMESPACE_REGISTRATION_FAILED_CTR).count(),
        "a registration failure has to be countable, not only visible to the one caller");
  }

  @Test
  public void testRetrievingStagedTableThrowsIllegalStateException() {
    final String dbId = TEST_CREATE_TABLE_REQUEST_BODY.getDatabaseId();
    final String tableId = TEST_CREATE_TABLE_REQUEST_BODY.getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();
    final TableDto tableDto =
        TableDto.builder().databaseId(dbId).tableId(tableId).stageCreate(true).build();
    Mockito.when(mockRepository.findById(key)).thenReturn(Optional.of(tableDto));
    IllegalStateException illegalStateException =
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> service.putTable(TEST_CREATE_TABLE_REQUEST_BODY, TEST_TABLE_CREATOR, false));
    Assertions.assertTrue(
        illegalStateException
            .getMessage()
            .contains(String.format("Staged Table %s.%s was illegally persisted", dbId, tableId)));
  }

  @Test
  public void testHouseTableConcurrentUpdateException() {
    final String dbId = TEST_CREATE_TABLE_REQUEST_BODY.getDatabaseId();
    final String tableId = TEST_CREATE_TABLE_REQUEST_BODY.getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(mockRepository.findById(key)).thenThrow(HouseTableConcurrentUpdateException.class);
    Assertions.assertThrowsExactly(
        HouseTableConcurrentUpdateException.class, () -> service.getTable(dbId, tableId, ""));
  }

  @Test
  public void testHouseTableCallerException() {
    final String dbId = TEST_CREATE_TABLE_REQUEST_BODY.getDatabaseId();
    final String tableId = TEST_CREATE_TABLE_REQUEST_BODY.getTableId();
    final TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder().databaseId(dbId).tableId(tableId).build();

    Mockito.when(mockRepository.findById(key)).thenThrow(HouseTableCallerException.class);
    Assertions.assertThrowsExactly(
        HouseTableCallerException.class, () -> service.getTable(dbId, tableId, ""));
  }
}
