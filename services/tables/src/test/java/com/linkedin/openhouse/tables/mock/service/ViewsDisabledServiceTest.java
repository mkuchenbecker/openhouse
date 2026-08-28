package com.linkedin.openhouse.tables.mock.service;

import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.model.IcebergRestViewFixtures;
import com.linkedin.openhouse.tables.model.ViewCreationRequest;
import com.linkedin.openhouse.tables.model.ViewPageRequest;
import com.linkedin.openhouse.tables.services.ViewsDisabledService;
import java.util.Collections;
import java.util.stream.Stream;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

/**
 * The stubbed default-off posture: every {@code ViewsService} operation reports {@code
 * VIEWS_DISABLED} with the fixed redacted message and a 404 status. Runs as a plain JUnit test: the
 * bean has no dependencies.
 */
public class ViewsDisabledServiceTest {

  private final ViewsDisabledService service = new ViewsDisabledService();

  private static final TableIdentifier IDENTIFIER =
      TableIdentifier.of(IcebergRestViewFixtures.DATABASE_ID, IcebergRestViewFixtures.VIEW_ID);

  private static Stream<Arguments> allOperations() {
    return Stream.of(
        Arguments.of("loadView", (Operation) service -> service.loadView(IDENTIFIER, "principal")),
        Arguments.of(
            "viewExists", (Operation) service -> service.viewExists(IDENTIFIER, "principal")),
        Arguments.of(
            "listViews",
            (Operation)
                service ->
                    service.listViews(
                        IcebergRestViewFixtures.DATABASE_ID,
                        ViewPageRequest.unpaged(),
                        "principal")),
        Arguments.of(
            "createView",
            (Operation)
                service ->
                    service.createView(
                        ViewCreationRequest.builder()
                            .identifier(IDENTIFIER)
                            .schema(IcebergRestViewFixtures.SCHEMA)
                            .requestedVersion(IcebergRestViewFixtures.viewVersion())
                            .properties(Collections.emptyMap())
                            .build(),
                        "principal")),
        Arguments.of(
            "replaceView",
            (Operation)
                service ->
                    service.replaceView(
                        IDENTIFIER, Collections.emptyList(), Collections.emptyList(), "principal")),
        Arguments.of("dropView", (Operation) service -> service.dropView(IDENTIFIER, "principal")));
  }

  /**
   * Declares {@code throws Exception} so the operations that now carry checked conflict exceptions
   * can be written as lambdas here. None of them can actually be thrown by this service, which
   * refuses before it could reach a conflict; the declaration exists for the compiler, not as a
   * claim about behavior.
   */
  @FunctionalInterface
  interface Operation {
    void run(ViewsDisabledService service) throws Exception;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allOperations")
  public void everyOperationReportsViewsDisabled(String operationName, Operation operation) {
    ViewApiException exception =
        Assertions.assertThrows(ViewApiException.class, () -> operation.run(service));

    Assertions.assertEquals(ViewErrorCode.VIEWS_DISABLED, exception.getErrorCode());
    Assertions.assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatus());
    Assertions.assertEquals(
        "Views are disabled",
        exception.getMessage(),
        "The message is fixed and redacted: it is copied into the error body and audit events.");
  }
}
