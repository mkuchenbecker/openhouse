package com.linkedin.openhouse.tables.mock.api;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.VIEW_SOURCE_DIALECT_SUMMARY_KEY;

import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.exception.ViewRequestValidationFailureException;
import com.linkedin.openhouse.tables.model.IcebergRestViewFixtures;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.ImmutableCreateViewRequest;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.ViewVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The multi-dialect deployment shape: with {@code spark,trino} configured, a request carrying one
 * representation per supported dialect is well formed, and the {@code openhouse.source-dialect}
 * summary entry becomes mandatory exactly when representations are plural (F7).
 */
@SpringBootTest(properties = "cluster.tables.views.supported-dialects=spark,trino")
public class IcebergRestViewsValidatorMultiDialectTest {

  @Autowired private ViewsApiValidator validator;

  private static final String DB = IcebergRestViewFixtures.DATABASE_ID;

  private static ViewVersion twoDialectVersion(Map<String, String> summary) {
    return ImmutableViewVersion.builder()
        .from(
            IcebergRestViewFixtures.viewVersionWithRepresentations(
                IcebergRestViewFixtures.representation("spark", "SELECT 1"),
                IcebergRestViewFixtures.representation("trino", "SELECT 1")))
        .summary(summary)
        .build();
  }

  private static CreateViewRequest request(ViewVersion viewVersion) {
    return ImmutableCreateViewRequest.builder()
        .from(IcebergRestViewFixtures.createViewRequest())
        .viewVersion(viewVersion)
        .build();
  }

  @Test
  public void twoRepresentationsWithASourceDialectSummaryPass() {
    Map<String, String> summary = new LinkedHashMap<>();
    summary.put(VIEW_SOURCE_DIALECT_SUMMARY_KEY, "trino");
    Assertions.assertDoesNotThrow(
        () -> validator.validateCreateView(DB, request(twoDialectVersion(summary))));
  }

  /** F7: with plural representations the summary entry is required. */
  @Test
  public void twoRepresentationsWithoutASourceDialectSummaryFail() {
    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () ->
                validator.validateCreateView(
                    DB, request(twoDialectVersion(new LinkedHashMap<>()))));
    Assertions.assertEquals(ViewErrorCode.UNSUPPORTED_VIEW_DIALECT, exception.getErrorCode());
    Assertions.assertTrue(
        exception.getMessage().contains("required when multiple representations"));
  }

  @Test
  public void aSourceDialectSummaryNamingAnAbsentRepresentationFails() {
    Map<String, String> summary = new LinkedHashMap<>();
    summary.put(VIEW_SOURCE_DIALECT_SUMMARY_KEY, "trino");
    CreateViewRequest sparkOnly =
        request(
            ImmutableViewVersion.builder()
                .from(
                    IcebergRestViewFixtures.viewVersionWithRepresentations(
                        IcebergRestViewFixtures.representation("spark", "SELECT 1")))
                .summary(summary)
                .build());

    ViewRequestValidationFailureException exception =
        Assertions.assertThrows(
            ViewRequestValidationFailureException.class,
            () -> validator.validateCreateView(DB, sparkOnly));
    Assertions.assertTrue(
        exception.getMessage().contains("does not name a supplied representation"));
  }

  @Test
  public void trinoAloneIsAcceptedInThisDeployment() {
    Assertions.assertDoesNotThrow(
        () ->
            validator.validateCreateView(
                DB,
                request(
                    ImmutableViewVersion.builder()
                        .from(
                            IcebergRestViewFixtures.viewVersionWithRepresentations(
                                IcebergRestViewFixtures.representation("trino", "SELECT 1")))
                        .build())));
  }
}
