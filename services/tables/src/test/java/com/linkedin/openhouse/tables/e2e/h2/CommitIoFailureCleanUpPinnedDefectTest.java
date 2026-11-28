package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.tables.model.TableModelConstants.TABLE_DTO;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.linkedin.openhouse.cluster.storage.local.LocalStorageClient;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * KNOWN DEFECT, pinned: when a commit's metadata write succeeds and the I/O step after the House
 * Tables save fails, the clean-up of {@code OpenHouseInternalTableOperations.doCommit} calls {@code
 * HouseTableRepository.delete(entity)}, which the real repository refuses with {@link
 * UnsupportedOperationException} (as the stand-in now does). That exception is not among those the
 * clean-up catches, so it replaces the {@code CommitFailedException} the path means to throw, and
 * nothing is cleaned up: the row the save wrote and the metadata file it points to both stay. The
 * code is LinkedIn main's.
 *
 * <p>The one checked {@code IOException} of that block today is the rewrite of the metadata file of
 * a replicated table's create ({@code updateMetadataFieldForTable}), so the test drives that create
 * with the local storage's file system failing to open the file. The failing file system is set
 * into the storage client of the shared test context for this test only, so the suite starts no
 * context of its own for it.
 */
@SpringBootTest
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class CommitIoFailureCleanUpPinnedDefectTest {

  private static final String TABLE_ID = "io_failure_clean_up";

  @Autowired OpenHouseInternalRepository openHouseInternalRepository;
  @Autowired HouseTableRepository houseTables;
  @Autowired LocalStorageClient localStorageClient;

  private HouseTablePrimaryKey key() {
    return HouseTablePrimaryKey.builder()
        .databaseId(TABLE_DTO.getDatabaseId())
        .tableId(TABLE_ID)
        .build();
  }

  @AfterEach
  void cleanUp() {
    if (houseTables.findEntityById(key()).isPresent()) {
      houseTables.deleteById(key());
    }
  }

  @Test
  void anIoFailureAfterTheSaveEscapesAsUnsupportedOperationAndLeavesTheRowAndTheFile()
      throws Exception {
    LocalStorageClient client = AopTestUtils.getUltimateTargetObject(localStorageClient);
    FileSystem original = client.getNativeClient();
    FileSystem failing = spy(original);
    doThrow(new IOException("the file system failed")).when(failing).open(any(Path.class));
    ReflectionTestUtils.setField(client, "fs", failing);
    try {
      anIoFailureAfterTheSave();
    } finally {
      ReflectionTestUtils.setField(client, "fs", original);
    }
  }

  private void anIoFailureAfterTheSave() {

    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogConstants.OPENHOUSE_IS_TABLE_REPLICATED_KEY, "true");
    properties.put(CatalogConstants.LAST_UPDATED_MS, "1700000000000");
    TableDto replicatedCreate =
        TABLE_DTO
            .toBuilder()
            .tableId(TABLE_ID)
            .tableVersion(INITIAL_TABLE_VERSION)
            .policies(null)
            .tableProperties(properties)
            .build();

    UnsupportedOperationException thrown =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> openHouseInternalRepository.save(replicatedCreate));
    Assertions.assertEquals("Entity deletion is not supported.", thrown.getMessage());

    // Nothing was cleaned up: the row the save wrote, and the metadata file it points to.
    Optional<HouseTable> row = houseTables.findEntityById(key());
    Assertions.assertTrue(row.isPresent(), "the row stays");
    String location = row.get().getTableLocation();
    String path = location.startsWith("file:") ? location.substring("file:".length()) : location;
    Assertions.assertTrue(new File(path).isFile(), "the metadata file stays: " + location);
  }
}
