package com.linkedin.openhouse.internal.catalog.mapper;

import static org.mockito.Mockito.*;

import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.local.LocalStorage;
import com.linkedin.openhouse.housetables.client.api.ToggleStatusApi;
import com.linkedin.openhouse.housetables.client.api.UserTableApi;
import com.linkedin.openhouse.housetables.client.invoker.ApiClient;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepositoryImpl;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

@SpringBootTest
public class HouseTableMapperTest {

  /**
   * Tests that doesn't care on HTS server should import this test configuration as
   *
   * @import(classes = MockConfiguration.class)
   */
  @TestConfiguration
  public static class MockConfiguration {
    @Bean
    public UserTableApi provideMockHtsApiInstance() {
      // Routing the client to access port from Mock server so that Mock server can respond with
      // stub response.
      ApiClient apiClient = new ApiClient();
      return new UserTableApi(apiClient);
    }

    @Bean
    public ToggleStatusApi provideMockHtsApiInstanceForToggle() {
      // Routing the client to access port from Mock server so that Mock server can respond with
      // stub response.
      ApiClient apiClient = new ApiClient();
      return new ToggleStatusApi(apiClient);
    }

    @Bean
    public HouseTableRepository provideRealHtsRepository() {
      return new HouseTableRepositoryImpl();
    }
  }

  @Autowired protected HouseTableMapper houseTableMapper;

  @Autowired FileIOManager fileIOManager;

  @Test
  public void simpleMapperTest() {
    HadoopFileIO fileIO = new HadoopFileIO(new Configuration());
    LocalStorage localStorage = mock(LocalStorage.class);
    when(fileIOManager.getStorage(fileIO)).thenReturn(localStorage);
    when(localStorage.getType()).thenReturn(StorageType.LOCAL);
    HouseTable houseTable =
        houseTableMapper.toHouseTable(
            ImmutableMap.of("databaseId", "database", "tableId", "table"), fileIO);
    Assertions.assertEquals("database", houseTable.getDatabaseId());
    Assertions.assertEquals("table", houseTable.getTableId());
    Assertions.assertEquals("local", houseTable.getStorageType());
  }

  /**
   * Values are copied, never rewritten.
   *
   * <p>This pins the fix for an accidental conversion: {@code stripOhNamespace} strips the {@code
   * openhouse.} prefix from map <i>keys</i>, but MapStruct had adopted it as an implicit
   * String-to-String converter and was applying it to every mapped <i>value</i> as well. This test
   * previously asserted that behavior — it passed {@code databaseId} as {@code
   * "openhouse.database"} and expected {@code "database"} back — which made a defect look
   * intentional. A value that happens to begin with the reserved prefix must survive the mapping
   * unchanged; only keys are namespaced.
   */
  @Test
  public void valuesBeginningWithTheReservedPrefixAreNotRewritten() {
    HadoopFileIO fileIO = new HadoopFileIO(new Configuration());
    LocalStorage localStorage = mock(LocalStorage.class);
    when(fileIOManager.getStorage(fileIO)).thenReturn(localStorage);
    when(localStorage.getType()).thenReturn(StorageType.LOCAL);
    HouseTable houseTable =
        houseTableMapper.toHouseTable(
            ImmutableMap.of("databaseId", "openhouse.database", "tableId", "openhouse.table"),
            fileIO);
    Assertions.assertEquals("openhouse.database", houseTable.getDatabaseId());
    Assertions.assertEquals("openhouse.table", houseTable.getTableId());
  }

  /**
   * The discriminator is not a table property, so it cannot be supplied as one.
   *
   * <p>{@code entityType} says whether a row is a table or a view, and that is decided by which
   * operations class performs the write. If it were a property-derived field, a caller who could
   * get {@code openhouse.entityType} into a table's properties would be choosing the discriminator
   * for their own row — writing a table that House Tables stores as a view, invisible to a typed
   * table read.
   */
  @Test
  public void entityTypeCannotBeSuppliedThroughTableProperties() {
    Assertions.assertFalse(
        HouseTableSerdeUtils.HTS_FIELD_NAMES.contains("entityType"),
        "entityType must stay out of the property-derived field set");
    Assertions.assertTrue(
        HouseTableSerdeUtils.NON_PROPERTY_FIELD_NAMES.contains("entityType"),
        "the exclusion is deliberate and belongs in NON_PROPERTY_FIELD_NAMES");
  }
}
