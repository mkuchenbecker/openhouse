package com.linkedin.openhouse.tables.api.irc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.tables.api.handler.ViewsApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetViewResponseBody;
import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.audit.ViewAuditAspect;
import com.linkedin.openhouse.tables.model.ViewDto;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.services.ViewsServiceImpl;
import java.util.Collections;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.ImmutableSQLViewRepresentation;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * The answer of an IRC view commit whose native replace answers no metadata location. The native
 * replace answers the location House Tables answered for the row it saved (or, for a commit that
 * changes nothing, the stored row's, which the commit engine requires to be set), so this is a
 * House Tables answer that breaks its contract. Which version the commit stored cannot then be
 * told, and the view as stored now may be a later one: the commit is answered "state unknown",
 * never with a version that may not be the one it stored.
 */
@ExtendWith(MockitoExtension.class)
class IcebergRestViewHandlerCommitAnswerTest {

  private static final String DB = "irc_vnull";
  private static final String VIEW = "v";
  private static final String LOCATION = "file:/tmp/irc_vnull/v/00001-a.metadata.json";

  @Mock private ViewsApiHandler viewsApiHandler;
  @Mock private ViewsApiValidator viewsApiValidator;
  @Mock private ViewsServiceImpl viewsService;
  @Mock private IcebergRestApiHandler readHandler;
  @Mock private ViewsFeatureGate viewsFeatureGate;
  @Mock private ViewAuditAspect viewAudit;
  @InjectMocks private IcebergRestViewHandler handler;

  private static ViewMetadata metadata() {
    Schema schema = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    return ViewMetadata.builder()
        .assignUUID("0f6c2a5e-6a43-4c55-9d1e-2b8f1c4b7a10")
        .setLocation("file:/tmp/irc_vnull/v")
        .setCurrentVersion(
            ImmutableViewVersion.builder()
                .versionId(1)
                .timestampMillis(1L)
                .schemaId(schema.schemaId())
                .defaultNamespace(Namespace.of(DB))
                .addRepresentations(
                    ImmutableSQLViewRepresentation.builder()
                        .sql("SELECT 1")
                        .dialect("spark")
                        .build())
                .build(),
            schema)
        .build();
  }

  @Test
  void aCommitAnsweredWithoutItsMetadataLocationIsCommitStateUnknown() {
    ViewsServiceImpl.LoadedView stored =
        new ViewsServiceImpl.LoadedView(
            ViewDto.builder()
                .databaseId(DB)
                .viewId(VIEW)
                .metadataLocation(LOCATION)
                .viewVersion(LOCATION)
                .build(),
            metadata());
    when(viewsService.loadReadableView(DB, VIEW, "alice")).thenReturn(stored);
    when(viewsApiHandler.updateView(any(), anyString()))
        .thenReturn(
            ApiResponse.<GetViewResponseBody>builder()
                .httpStatus(HttpStatus.OK)
                .responseBody(GetViewResponseBody.builder().databaseId(DB).viewId(VIEW).build())
                .build());

    CommitStateUnknownException unknown =
        assertThrows(
            CommitStateUnknownException.class,
            () ->
                handler.commitView(
                    DB,
                    VIEW,
                    new UpdateTableRequest(Collections.emptyList(), Collections.emptyList()),
                    "alice"));
    assertTrue(
        unknown.getMessage().contains("answered no metadata location"), unknown.getMessage());
    // Nothing is read to guess the answer from.
    verify(viewsService, never()).readViewVersion(anyString(), anyString(), anyString());
  }
}
