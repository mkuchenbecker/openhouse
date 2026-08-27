package com.linkedin.openhouse.housetables.e2e.usertable;

import static com.linkedin.openhouse.housetables.model.TestHouseTableModelConstants.*;
import static org.assertj.core.api.Assertions.*;

import com.google.common.collect.Lists;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.housetables.model.TestHouseTableModelConstants;
import com.linkedin.openhouse.housetables.model.UserTableRow;
import com.linkedin.openhouse.housetables.model.UserTableRowPrimaryKey;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.UserTableHtsJdbcRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class HtsRepositoryTest {

  @Autowired UserTableHtsJdbcRepository htsRepository;

  @AfterEach
  public void tearDown() {
    htsRepository.deleteAll();
  }

  @Test
  public void testSaveFirstRecord() {
    UserTableRow testUserTableRow =
        new TestHouseTableModelConstants.TestTuple(0).get_userTableRow();
    // before insertion
    Assertions.assertEquals(null, testUserTableRow.getVersion());
    // after insertion
    Assertions.assertEquals(0, htsRepository.save(testUserTableRow).getVersion());
  }

  @Test
  public void testFindDistinctDatabases() {
    htsRepository.save(TEST_TUPLE_1_0.get_userTableRow());
    htsRepository.save(TEST_TUPLE_1_1.get_userTableRow());
    htsRepository.save(TEST_TUPLE_2_0.get_userTableRow());
    List<String> result = Lists.newArrayList(htsRepository.findAllDistinctDatabaseIds());
    Assertions.assertEquals(Lists.newArrayList("test_db0", "test_db1"), result);
  }

  @Test
  public void testFindAllByDatabaseId() {
    htsRepository.save(TEST_TUPLE_1_0.get_userTableRow());
    htsRepository.save(TEST_TUPLE_1_1.get_userTableRow());
    htsRepository.save(TEST_TUPLE_2_0.get_userTableRow());
    List<UserTableRow> result =
        Lists.newArrayList(htsRepository.findAllByDatabaseIdIgnoreCase("test_db0"));
    Assertions.assertEquals(
        Lists.newArrayList("test_table1", "test_table2"),
        result.stream().map(UserTableRow::getTableId).collect(Collectors.toList()));
  }

  @Test
  public void testFindAllByTableIdPattern() {
    htsRepository.save(TEST_TUPLE_1_0.get_userTableRow());
    htsRepository.save(TEST_TUPLE_1_1.get_userTableRow());
    htsRepository.save(TEST_TUPLE_2_0.get_userTableRow());
    List<UserTableRow> result =
        Lists.newArrayList(
            htsRepository.findAllByDatabaseIdAndTableIdLikeAllIgnoreCase(
                "test_db0", "test_table%"));
    Assertions.assertEquals(
        Lists.newArrayList("test_table1", "test_table2"),
        result.stream().map(UserTableRow::getTableId).collect(Collectors.toList()));
  }

  @Test
  public void testFindAllByTableId() {
    htsRepository.save(TEST_TUPLE_1_0.get_userTableRow());
    htsRepository.save(TEST_TUPLE_1_1.get_userTableRow());
    htsRepository.save(TEST_TUPLE_2_0.get_userTableRow());
    List<UserTableRow> result =
        Lists.newArrayList(
            htsRepository.findAllByDatabaseIdAndTableIdLikeAllIgnoreCase(
                "test_db0", "test_table1"));
    Assertions.assertEquals(
        Lists.newArrayList("test_table1"),
        result.stream().map(UserTableRow::getTableId).collect(Collectors.toList()));
  }

  @Test
  public void testHouseTable() {
    UserTableRow testUserTableRow =
        new TestHouseTableModelConstants.TestTuple(0).get_userTableRow();
    htsRepository.save(testUserTableRow);
    UserTableRow actual =
        htsRepository
            .findById(
                UserTableRowPrimaryKey.builder()
                    .databaseId(TEST_DB_ID)
                    .tableId(TEST_TABLE_ID)
                    .build())
            .orElse(UserTableRow.builder().build());

    Assertions.assertEquals(testUserTableRow, actual);
    htsRepository.delete(actual);
  }

  @Test
  public void testDeleteUserTable() {
    htsRepository.save(TEST_TUPLE_1_1.get_userTableRow());
    UserTableRowPrimaryKey key =
        UserTableRowPrimaryKey.builder()
            .tableId(TEST_TUPLE_1_1.getTableId())
            .databaseId(TEST_TUPLE_1_1.getDatabaseId())
            .build();
    // verify testTuple1_1 exist first.
    assertThat(htsRepository.existsById(key)).isTrue();
    // Delete testTuple1_1 from house table.
    htsRepository.deleteById(key);
    // verify testTuple1_1 doesn't exist any more.
    assertThat(htsRepository.existsById(key)).isFalse();
  }

  @Test
  public void testSaveUserTableWithConflict() {
    UserTableRow testUserTableRow =
        new TestHouseTableModelConstants.TestTuple(0).get_userTableRow();
    Long currentVersion = htsRepository.save(testUserTableRow).getVersion();
    // test create the table again
    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () -> htsRepository.save(testUserTableRow.toBuilder().version(null).build()));
    Assertions.assertTrue(exception instanceof DataIntegrityViolationException);

    // test update at wrong version
    exception =
        Assertions.assertThrows(
            Exception.class,
            () -> htsRepository.save(testUserTableRow.toBuilder().version(100L).build()));
    Assertions.assertTrue(
        exception instanceof ObjectOptimisticLockingFailureException
            | exception instanceof EntityConcurrentModificationException);

    // test update at correct version
    Assertions.assertNotEquals(
        htsRepository
            .save(
                testUserTableRow
                    .toBuilder()
                    .version(currentVersion)
                    .metadataLocation("file:/ml2")
                    .build())
            .getVersion(),
        currentVersion);

    // test update at older version
    exception =
        Assertions.assertThrows(Exception.class, () -> htsRepository.save(testUserTableRow));
    Assertions.assertTrue(
        exception instanceof ObjectOptimisticLockingFailureException
            | exception instanceof EntityConcurrentModificationException);

    htsRepository.deleteById(
        UserTableRowPrimaryKey.builder().databaseId(TEST_DB_ID).tableId(TEST_TABLE_ID).build());
  }

  @Test
  public void testRenameUserTable() {
    UserTableRow savedRow = htsRepository.save(TEST_TUPLE_1_1.get_userTableRow());
    UserTableRowPrimaryKey key =
        UserTableRowPrimaryKey.builder()
            .tableId(TEST_TUPLE_1_1.getTableId())
            .databaseId(TEST_TUPLE_1_1.getDatabaseId())
            .build();
    // verify testTuple1_1 exist first.
    assertThat(htsRepository.existsById(key)).isTrue();

    String newTableMetadata = TEST_TUPLE_1_1.getTableLoc() + "_v2";
    int updatedRows =
        htsRepository.renameTableId(
            TEST_TUPLE_1_1.getDatabaseId(),
            TEST_TUPLE_1_1.getTableId(),
            TEST_TUPLE_1_1.getDatabaseId(),
            TEST_TUPLE_1_1.getTableId() + "_renamed",
            newTableMetadata,
            savedRow.getVersion(),
            savedRow.getMetadataLocation());
    Assertions.assertEquals(1, updatedRows);

    UserTableRow result =
        htsRepository
            .findById(
                UserTableRowPrimaryKey.builder()
                    .databaseId(TEST_TUPLE_1_1.getDatabaseId())
                    .tableId(TEST_TUPLE_1_1.getTableId() + "_renamed")
                    .build())
            .orElse(UserTableRow.builder().build());
    assertThat(result.getMetadataLocation()).isEqualTo(newTableMetadata);
    // rename must bump the optimistic-lock @Version column so concurrent writers using the old
    // version conflict instead of silently overwriting the renamed row.
    assertThat(result.getVersion()).isEqualTo(savedRow.getVersion() + 1);

    // verify testTuple1_1 doesn't exist any more.
    assertThat(htsRepository.existsById(key)).isFalse();
  }

  @Test
  public void testRenameUserTableAtStaleVersionUpdatesNoRows() {
    UserTableRow savedRow = htsRepository.save(TEST_TUPLE_1_1.get_userTableRow());

    // A concurrent commit advances the row (bumping @Version) after the renamer read it.
    UserTableRow committedRow =
        htsRepository.save(
            savedRow.toBuilder().metadataLocation(TEST_TUPLE_1_1.getTableLoc() + "_v2").build());
    Assertions.assertNotEquals(savedRow.getVersion(), committedRow.getVersion());

    // The rename conditioned on the stale version must match 0 rows instead of clobbering the
    // concurrently committed metadataLocation.
    int updatedRows =
        htsRepository.renameTableId(
            TEST_TUPLE_1_1.getDatabaseId(),
            TEST_TUPLE_1_1.getTableId(),
            TEST_TUPLE_1_1.getDatabaseId(),
            TEST_TUPLE_1_1.getTableId() + "_renamed",
            TEST_TUPLE_1_1.getTableLoc() + "_renamed",
            savedRow.getVersion(),
            savedRow.getMetadataLocation());
    Assertions.assertEquals(0, updatedRows);

    // The winning commit's row is intact: same id, same metadataLocation, same version.
    UserTableRow result =
        htsRepository
            .findById(
                UserTableRowPrimaryKey.builder()
                    .databaseId(TEST_TUPLE_1_1.getDatabaseId())
                    .tableId(TEST_TUPLE_1_1.getTableId())
                    .build())
            .orElse(UserTableRow.builder().build());
    assertThat(result.getMetadataLocation()).isEqualTo(TEST_TUPLE_1_1.getTableLoc() + "_v2");
    assertThat(result.getVersion()).isEqualTo(committedRow.getVersion());
    assertThat(
            htsRepository.existsById(
                UserTableRowPrimaryKey.builder()
                    .databaseId(TEST_TUPLE_1_1.getDatabaseId())
                    .tableId(TEST_TUPLE_1_1.getTableId() + "_renamed")
                    .build()))
        .isFalse();
  }

  @Test
  public void testRenameUserTableAfterDropAndRecreateUpdatesNoRows() {
    // A version-only condition is vulnerable to ABA, because @Version is a per-row counter that
    // restarts at 0 when a row is deleted and reinserted. Here the renamer observes the row, the
    // table is then dropped and recreated at the same identity as a different table with its own
    // metadata, and the recreated row lands back on the observed version. Conditioning the update
    // on the observed metadataLocation as well is what keeps the rename from adopting the new
    // incarnation and overwriting its metadata with the previous incarnation's.
    UserTableRow observedRow = htsRepository.save(TEST_TUPLE_1_1.get_userTableRow());
    Long observedVersion = observedRow.getVersion();
    String observedLocation = observedRow.getMetadataLocation();

    UserTableRowPrimaryKey key =
        UserTableRowPrimaryKey.builder()
            .databaseId(TEST_TUPLE_1_1.getDatabaseId())
            .tableId(TEST_TUPLE_1_1.getTableId())
            .build();
    htsRepository.deleteById(key);
    String recreatedLocation = TEST_TUPLE_1_1.getTableLoc() + "_recreated";
    UserTableRow recreatedRow =
        htsRepository.save(
            TEST_TUPLE_1_1
                .get_userTableRow()
                .toBuilder()
                .version(null)
                .metadataLocation(recreatedLocation)
                .build());
    // The premise of the race: the recreated row is a different table, yet its version counter has
    // restarted at exactly the value the renamer observed on the previous incarnation.
    Assertions.assertEquals(observedVersion, recreatedRow.getVersion());
    Assertions.assertNotEquals(observedLocation, recreatedRow.getMetadataLocation());

    int updatedRows =
        htsRepository.renameTableId(
            TEST_TUPLE_1_1.getDatabaseId(),
            TEST_TUPLE_1_1.getTableId(),
            TEST_TUPLE_1_1.getDatabaseId(),
            TEST_TUPLE_1_1.getTableId() + "_renamed",
            observedLocation + "_renamed",
            observedVersion,
            observedLocation);
    Assertions.assertEquals(0, updatedRows);

    // The new incarnation is untouched: same identity, its own metadataLocation, its own version.
    UserTableRow result = htsRepository.findById(key).orElse(UserTableRow.builder().build());
    assertThat(result.getMetadataLocation()).isEqualTo(recreatedLocation);
    assertThat(result.getVersion()).isEqualTo(recreatedRow.getVersion());
    assertThat(
            htsRepository.existsById(
                UserTableRowPrimaryKey.builder()
                    .databaseId(TEST_TUPLE_1_1.getDatabaseId())
                    .tableId(TEST_TUPLE_1_1.getTableId() + "_renamed")
                    .build()))
        .isFalse();
  }

  @Test
  public void testRenameCaseSensitivity() {
    UserTableRow testUpperCaseRow =
        TEST_TUPLE_1_1
            .get_userTableRow()
            .toBuilder()
            .tableId(TEST_TUPLE_1_1.getTableId().toUpperCase())
            .databaseId(TEST_TUPLE_1_1.getDatabaseId())
            .build();
    UserTableRow savedRow = htsRepository.save(testUpperCaseRow);

    UserTableRowPrimaryKey key =
        UserTableRowPrimaryKey.builder()
            .tableId(TEST_TUPLE_1_1.getTableId().toUpperCase())
            .databaseId(TEST_TUPLE_1_1.getDatabaseId())
            .build();
    // verify fetch is case in-sensitive
    assertThat(htsRepository.existsById(key)).isTrue();

    String renamedUpperCaseTableId = TEST_TUPLE_1_1.getTableId() + "_RENAMED";

    // Condition the rename on the version the row actually carries rather than assuming a freshly
    // saved row starts at 0, and assert it landed: a 0-row result here means the optimistic-lock
    // guard rejected the update, which must not be mistaken for a case-sensitivity failure.
    int updatedRows =
        htsRepository.renameTableId(
            TEST_TUPLE_1_1.getDatabaseId(),
            TEST_TUPLE_1_1.getTableId(),
            TEST_TUPLE_1_1.getDatabaseId().toUpperCase(),
            renamedUpperCaseTableId,
            TEST_TUPLE_1_1.getTableLoc(),
            savedRow.getVersion(),
            savedRow.getMetadataLocation());
    Assertions.assertEquals(1, updatedRows);

    // Try fetching with lower case ID, should still work
    UserTableRow result =
        htsRepository
            .findById(
                UserTableRowPrimaryKey.builder()
                    .databaseId(TEST_TUPLE_1_1.getDatabaseId())
                    .tableId(renamedUpperCaseTableId.toLowerCase())
                    .build())
            .orElse(UserTableRow.builder().build());

    // Should preserve original case
    Assertions.assertEquals(result.getTableId(), renamedUpperCaseTableId);

    // verify testTuple1_1 doesn't exist any more.
    assertThat(htsRepository.existsById(key)).isFalse();
  }
}
