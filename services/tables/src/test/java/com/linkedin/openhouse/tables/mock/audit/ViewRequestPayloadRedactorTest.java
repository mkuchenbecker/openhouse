package com.linkedin.openhouse.tables.mock.audit;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.linkedin.openhouse.common.audit.ServiceAuditPayloadRedactor;
import com.linkedin.openhouse.tables.audit.ViewRequestPayloadRedactor;
import com.linkedin.openhouse.tables.model.IcebergRestViewFixtures;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit coverage of {@link ViewRequestPayloadRedactor} on the Iceberg REST request shapes: route
 * scoping, schema/SQL removal at every nesting the create and commit envelopes use, and non-object
 * payload passthrough.
 */
public class ViewRequestPayloadRedactorTest {

  private final ViewRequestPayloadRedactor redactor = new ViewRequestPayloadRedactor();

  private static HttpServletRequest requestFor(String uri) {
    HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    Mockito.when(request.getRequestURI()).thenReturn(uri);
    return request;
  }

  @Test
  public void appliesExactlyToTheViewWriteRoutes() {
    Assertions.assertTrue(redactor.appliesTo(requestFor("/v1/namespaces/db/views")));
    Assertions.assertTrue(redactor.appliesTo(requestFor("/v1/namespaces/db/views/v")));
    Assertions.assertFalse(redactor.appliesTo(requestFor("/v1/config")));
    Assertions.assertFalse(redactor.appliesTo(requestFor("/v1/databases/db/tables")));
    Assertions.assertFalse(redactor.appliesTo(requestFor("/v2/databases/db/views")));
    Assertions.assertFalse(redactor.appliesTo(requestFor(null)));
  }

  @Test
  public void createRequestSchemaAndSqlAreRedactedAndEverythingElseSurvives() {
    JsonObject payload =
        JsonParser.parseString(IcebergRestViewFixtures.createViewRequestJson()).getAsJsonObject();

    JsonObject redacted = redactor.redact(payload).getAsJsonObject();

    Assertions.assertEquals(
        ServiceAuditPayloadRedactor.REDACTED_VALUE,
        redacted.get("schema").getAsString(),
        "The schema subtree collapses to the marker; the key survives.");
    JsonObject representation =
        redacted
            .getAsJsonObject("view-version")
            .getAsJsonArray("representations")
            .get(0)
            .getAsJsonObject();
    Assertions.assertEquals(
        ServiceAuditPayloadRedactor.REDACTED_VALUE, representation.get("sql").getAsString());
    Assertions.assertEquals("sql", representation.get("type").getAsString());
    Assertions.assertEquals(
        IcebergRestViewFixtures.SOURCE_DIALECT, representation.get("dialect").getAsString());
    Assertions.assertEquals(IcebergRestViewFixtures.VIEW_ID, redacted.get("name").getAsString());
    Assertions.assertEquals(
        "openhouse", redacted.getAsJsonObject("properties").get("owner").getAsString());
    Assertions.assertFalse(
        redacted.toString().contains(IcebergRestViewFixtures.VIEW_SQL),
        "No fragment of the SQL text survives anywhere in the payload.");

    // The input is not mutated: the redactor returns a copy.
    Assertions.assertTrue(payload.toString().contains(IcebergRestViewFixtures.SOURCE_DIALECT));
    Assertions.assertNotEquals(payload.toString(), redacted.toString());
  }

  @Test
  public void commitRequestSchemaAndSqlAreRedactedAtTheirNestedLocations() {
    JsonObject payload =
        JsonParser.parseString(IcebergRestViewFixtures.commitViewRequestJson()).getAsJsonObject();

    JsonObject redacted = redactor.redact(payload).getAsJsonObject();

    JsonObject addViewVersion = redacted.getAsJsonArray("updates").get(0).getAsJsonObject();
    Assertions.assertEquals("add-view-version", addViewVersion.get("action").getAsString());
    Assertions.assertEquals(
        ServiceAuditPayloadRedactor.REDACTED_VALUE,
        addViewVersion
            .getAsJsonObject("view-version")
            .getAsJsonArray("representations")
            .get(0)
            .getAsJsonObject()
            .get("sql")
            .getAsString());
    // Requirements are metadata, not definition: they stay auditable.
    Assertions.assertEquals(
        IcebergRestViewFixtures.VIEW_UUID,
        redacted.getAsJsonArray("requirements").get(0).getAsJsonObject().get("uuid").getAsString());
  }

  @Test
  public void nonObjectPayloadsPassThroughUntouched() {
    Assertions.assertEquals(JsonNull.INSTANCE, redactor.redact(JsonNull.INSTANCE));
    Assertions.assertNull(redactor.redact(null));
    JsonElement array = JsonParser.parseString("[1, 2, 3]");
    Assertions.assertEquals(array, redactor.redact(array));
  }
}
