package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.model.TableModelConstants.*;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.services.IcebergSnapshotsService;
import com.linkedin.openhouse.tables.services.TablesService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.util.Pair;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ContextConfiguration;

/**
 * Authorization tests for the replace-commit (RTAS phase 2) path of {@link
 * IcebergSnapshotsService}. The stage-replace path is covered in {@link TablesServiceTest}; this
 * class pins the same privilege gate on the snapshots endpoint, which would otherwise have no
 * authorization coverage.
 */
@SpringBootTest(classes = SpringH2Application.class)
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class IcebergSnapshotsServiceAuthorizationTest {

  @Autowired TablesService tablesService;

  @Autowired IcebergSnapshotsService icebergSnapshotsService;

  @MockBean AuthorizationHandler authorizationHandler;

  @BeforeEach
  public void setup() {
    Mockito.when(
            authorizationHandler.checkAccessDecision(
                Mockito.any(), (DatabaseDto) Mockito.any(), Mockito.any()))
        .thenReturn(true);
    Mockito.when(
            authorizationHandler.checkAccessDecision(
                Mockito.any(), (TableDto) Mockito.any(), Mockito.any()))
        .thenReturn(true);
  }

  /**
   * Replace-commit is denied when the acting principal lacks UPDATE_TABLE_METADATA — even for the
   * table's creator. This pins the permission-model change on the snapshots path: the creator has
   * no implicit replace right, so a regression back to creator-equality at this call site (which
   * would allow this call) fails this test.
   */
  @Test
  public void testReplaceCommitDeniedWithoutUpdatePrivilege() {
    TableDto tableDtoCopy = TABLE_DTO.toBuilder().build();
    Pair<TableDto, Boolean> createResult =
        tablesService.putTable(buildCreateUpdateTableRequestBody(tableDtoCopy), TEST_USER, true);
    try {
      Mockito.when(
              authorizationHandler.checkAccessDecision(
                  Mockito.any(),
                  Mockito.any(TableDto.class),
                  Mockito.eq(Privileges.UPDATE_TABLE_METADATA)))
          .thenReturn(false);
      IcebergSnapshotsRequestBody replaceCommitRequest =
          IcebergSnapshotsRequestBody.builder()
              .baseTableVersion(createResult.getFirst().getTableLocation())
              .createUpdateTableRequestBody(
                  buildCreateUpdateTableRequestBody(createResult.getFirst())
                      .toBuilder()
                      .replaceCommit(true)
                      .build())
              .build();
      Assertions.assertThrows(
          AccessDeniedException.class,
          () ->
              icebergSnapshotsService.putIcebergSnapshots(
                  tableDtoCopy.getDatabaseId(),
                  tableDtoCopy.getTableId(),
                  replaceCommitRequest,
                  TEST_USER));
    } finally {
      tablesService.deleteTable(tableDtoCopy.getDatabaseId(), tableDtoCopy.getTableId(), TEST_USER);
    }
  }
}
