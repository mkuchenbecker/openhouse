package com.linkedin.openhouse.housetables.e2e.usertable;

import static com.linkedin.openhouse.housetables.model.TestHouseTableModelConstants.*;
import static org.assertj.core.api.Assertions.*;

import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.housetables.api.handler.UserTableHtsApiHandler;
import com.linkedin.openhouse.housetables.api.spec.model.UserTable;
import com.linkedin.openhouse.housetables.e2e.SpringH2HtsApplication;
import com.linkedin.openhouse.housetables.model.UserTableRow;
import com.linkedin.openhouse.housetables.model.UserTableRowPrimaryKey;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.UserTableHtsJdbcRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins the rename seam's concurrency-token contract: {@link
 * UserTableHtsApiHandler#renameEntity(UserTable, UserTable, String)} takes the caller's expected
 * metadata location as its own parameter, and reads it from nowhere else. The {@code fromUserTable}
 * DTO is identity-only on this path, so populating its {@code metadataLocation} cannot influence
 * the check.
 */
@SpringBootTest(classes = SpringH2HtsApplication.class)
public class UserTableHtsApiHandlerTest {

  // Two beans of this type exist (ApiConfig#tableHtsApiHandler and the component-scanned
  // one); the field name selects the same bean the controller uses.
  @Autowired UserTableHtsApiHandler tableHtsApiHandler;

  @Autowired UserTableHtsJdbcRepository htsRepository;

  private static final String STALE_LOCATION = "/openhouse/stale/v0_metadata.json";

  @BeforeEach
  public void setup() {
    htsRepository.save(TEST_TUPLE_1_0.get_userTableRow());
  }

  @AfterEach
  public void tearDown() {
    htsRepository.deleteAll();
  }

  @Test
  public void testRenameEntityUsesTheDeclaredTokenNotTheSourceDto() {
    // The misuse the parameter exists to prevent: a caller populates fromUserTable's
    // metadataLocation "for completeness" with the row's real location while declaring a stale
    // token. The stale token must still win the check, i.e. the rename must conflict.
    UserTable fromUserTable =
        TEST_TUPLE_1_0
            .get_userTable()
            .toBuilder()
            .metadataLocation(TEST_TUPLE_1_0.getTableLoc())
            .build();
    UserTable toUserTable =
        TEST_TUPLE_1_0
            .get_userTable()
            .toBuilder()
            .tableId(TEST_TUPLE_1_0.getTableId() + "_renamed")
            .metadataLocation(TEST_TUPLE_1_0.getTableLoc() + "_renamed")
            .build();

    Assertions.assertThrows(
        EntityConcurrentModificationException.class,
        () -> tableHtsApiHandler.renameEntity(fromUserTable, toUserTable, STALE_LOCATION));

    // Nothing moved: the source row is intact and no target row was created.
    assertThat(
            htsRepository.existsById(
                UserTableRowPrimaryKey.builder()
                    .databaseId(TEST_TUPLE_1_0.getDatabaseId())
                    .tableId(TEST_TUPLE_1_0.getTableId())
                    .build()))
        .isTrue();
    assertThat(
            htsRepository.existsById(
                UserTableRowPrimaryKey.builder()
                    .databaseId(TEST_TUPLE_1_0.getDatabaseId())
                    .tableId(TEST_TUPLE_1_0.getTableId() + "_renamed")
                    .build()))
        .isFalse();
  }

  @Test
  public void testRenameEntityAcceptsTheDeclaredTokenDespiteAStaleSourceDto() {
    // The converse: a fresh token in the parameter must be honored even when the source DTO's
    // metadataLocation is stale or absent, proving the DTO field is not consulted at all.
    UserTable fromUserTable =
        TEST_TUPLE_1_0.get_userTable().toBuilder().metadataLocation(STALE_LOCATION).build();
    UserTable toUserTable =
        TEST_TUPLE_1_0
            .get_userTable()
            .toBuilder()
            .tableId(TEST_TUPLE_1_0.getTableId() + "_renamed")
            .metadataLocation(TEST_TUPLE_1_0.getTableLoc() + "_renamed")
            .build();

    tableHtsApiHandler.renameEntity(fromUserTable, toUserTable, TEST_TUPLE_1_0.getTableLoc());

    UserTableRow renamed =
        htsRepository
            .findById(
                UserTableRowPrimaryKey.builder()
                    .databaseId(TEST_TUPLE_1_0.getDatabaseId())
                    .tableId(TEST_TUPLE_1_0.getTableId() + "_renamed")
                    .build())
            .orElse(UserTableRow.builder().build());
    assertThat(renamed.getMetadataLocation()).isEqualTo(TEST_TUPLE_1_0.getTableLoc() + "_renamed");
  }
}
