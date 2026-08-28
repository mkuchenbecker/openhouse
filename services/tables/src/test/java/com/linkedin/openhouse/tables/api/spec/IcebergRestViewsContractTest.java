package com.linkedin.openhouse.tables.api.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.tables.api.icebergrest.IcebergRestWire;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.model.IcebergRestViewFixtures;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.CreateViewRequestParser;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.ErrorResponseParser;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.rest.responses.LoadViewResponseParser;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Freezes the Iceberg REST views wire surface: exact serialized key sets (kebab-case), the error
 * envelope shape, the {@code /v1/config} body, and round-trips through Iceberg's own parsers.
 *
 * <p>Runs as a plain JUnit 5 test: the wire shapes are properties of the Iceberg dependency and the
 * {@code IcebergRestWire} helper, not of any Spring wiring.
 */
public class IcebergRestViewsContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  // ---------------------------------------------------------------------------------------------
  // LoadViewResult
  // ---------------------------------------------------------------------------------------------

  @Test
  public void loadViewResultSerializesTheSpecKeySetInKebabCase() throws Exception {
    JsonNode root = JSON.readTree(IcebergRestWire.toLoadViewResultJson(fixtureMetadata()));

    Assertions.assertEquals(
        setOf("metadata-location", "metadata"),
        fieldNames(root),
        "LoadViewResult carries exactly metadata-location and metadata (config is optional and"
            + " absent).");
    Assertions.assertEquals(
        IcebergRestViewFixtures.METADATA_LOCATION, root.get("metadata-location").asText());

    JsonNode metadata = root.get("metadata");
    Assertions.assertEquals(
        setOf(
            "view-uuid",
            "format-version",
            "location",
            "current-version-id",
            "versions",
            "version-log",
            "schemas",
            "properties"),
        fieldNames(metadata),
        "ViewMetadata serializes the complete spec document.");
    Assertions.assertEquals(IcebergRestViewFixtures.VIEW_UUID, metadata.get("view-uuid").asText());
    Assertions.assertEquals(1, metadata.get("format-version").asInt());

    JsonNode version = metadata.get("versions").get(0);
    Assertions.assertEquals(
        setOf(
            "version-id",
            "timestamp-ms",
            "schema-id",
            "summary",
            "default-catalog",
            "default-namespace",
            "representations"),
        fieldNames(version),
        "ViewVersion fields are kebab-case per the spec.");
    JsonNode representation = version.get("representations").get(0);
    Assertions.assertEquals(setOf("type", "sql", "dialect"), fieldNames(representation));
    Assertions.assertEquals("sql", representation.get("type").asText());
  }

  @Test
  public void loadViewResultRoundTripsThroughTheIcebergParser() {
    String json = IcebergRestWire.toLoadViewResultJson(fixtureMetadata());
    LoadViewResponse parsed = LoadViewResponseParser.fromJson(json);

    Assertions.assertEquals(IcebergRestViewFixtures.METADATA_LOCATION, parsed.metadataLocation());
    Assertions.assertEquals(IcebergRestViewFixtures.VIEW_UUID, parsed.metadata().uuid());
    Assertions.assertEquals(
        IcebergRestViewFixtures.VIEW_SQL,
        ((SQLViewRepresentation) parsed.metadata().currentVersion().representations().get(0))
            .sql());
  }

  // ---------------------------------------------------------------------------------------------
  // CreateViewRequest
  // ---------------------------------------------------------------------------------------------

  @Test
  public void createViewRequestSerializesTheSpecKeySetInKebabCase() throws Exception {
    JsonNode root = JSON.readTree(IcebergRestViewFixtures.createViewRequestJson());

    Assertions.assertEquals(
        setOf("name", "schema", "view-version", "properties"),
        fieldNames(root),
        "CreateViewRequest carries the spec's required fields; location is optional and absent."
            + " Namespace identity comes from the path only: no databaseId, no clusterId.");
    Assertions.assertEquals(
        setOf(
            "version-id",
            "timestamp-ms",
            "schema-id",
            "summary",
            "default-catalog",
            "default-namespace",
            "representations"),
        fieldNames(root.get("view-version")));
  }

  @Test
  public void createViewRequestRoundTripsThroughTheIcebergParser() {
    CreateViewRequest parsed =
        CreateViewRequestParser.fromJson(IcebergRestViewFixtures.createViewRequestJson());

    Assertions.assertEquals(IcebergRestViewFixtures.VIEW_ID, parsed.name());
    Assertions.assertEquals(IcebergRestViewFixtures.SCHEMA.asStruct(), parsed.schema().asStruct());
    Assertions.assertEquals(1, parsed.viewVersion().representations().size());
    Assertions.assertEquals(
        IcebergRestViewFixtures.SOURCE_DIALECT,
        ((SQLViewRepresentation) parsed.viewVersion().representations().get(0)).dialect());
    Assertions.assertEquals("openhouse", parsed.properties().get("owner"));
  }

  /** A spec-example document (hand-written kebab-case JSON) parses with the Iceberg parser. */
  @Test
  public void specExampleCreateViewRequestParses() {
    String specExample =
        "{\n"
            + "  \"name\": \"my_view\",\n"
            + "  \"schema\": {\"type\": \"struct\", \"schema-id\": 0, \"fields\": [\n"
            + "    {\"id\": 1, \"name\": \"id\", \"required\": true, \"type\": \"string\"}]},\n"
            + "  \"view-version\": {\n"
            + "    \"version-id\": 1,\n"
            + "    \"timestamp-ms\": 1651002318265,\n"
            + "    \"schema-id\": 0,\n"
            + "    \"summary\": {},\n"
            + "    \"representations\": [\n"
            + "      {\"type\": \"sql\", \"sql\": \"SELECT 1\", \"dialect\": \"spark\"}],\n"
            + "    \"default-namespace\": [\"my_database\"]},\n"
            + "  \"properties\": {}\n"
            + "}";

    CreateViewRequest parsed = CreateViewRequestParser.fromJson(specExample);
    Assertions.assertEquals("my_view", parsed.name());
    Assertions.assertEquals(1, parsed.viewVersion().versionId());
  }

  // ---------------------------------------------------------------------------------------------
  // CommitViewRequest (UpdateTableRequest envelope)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void commitViewRequestRoundTripsRequirementAndUpdatePolymorphism() throws Exception {
    String json = IcebergRestViewFixtures.commitViewRequestJson();
    JsonNode root = JSON.readTree(json);
    Assertions.assertEquals(setOf("identifier", "requirements", "updates"), fieldNames(root));
    Assertions.assertEquals(
        "assert-view-uuid", root.get("requirements").get(0).get("type").asText());
    Assertions.assertEquals("add-view-version", root.get("updates").get(0).get("action").asText());
    Assertions.assertEquals(
        "set-current-view-version", root.get("updates").get(1).get("action").asText());

    UpdateTableRequest parsed = UpdateTableRequestParser.fromJson(json);
    Assertions.assertEquals(
        TableIdentifier.of(IcebergRestViewFixtures.DATABASE_ID, IcebergRestViewFixtures.VIEW_ID),
        parsed.identifier());
    Assertions.assertTrue(parsed.requirements().get(0) instanceof UpdateRequirement.AssertViewUUID);
    Assertions.assertEquals(
        IcebergRestViewFixtures.VIEW_UUID,
        ((UpdateRequirement.AssertViewUUID) parsed.requirements().get(0)).uuid());
    Assertions.assertTrue(parsed.updates().get(0) instanceof MetadataUpdate.AddViewVersion);
    Assertions.assertTrue(parsed.updates().get(1) instanceof MetadataUpdate.SetCurrentViewVersion);
  }

  // ---------------------------------------------------------------------------------------------
  // ListTablesResponse
  // ---------------------------------------------------------------------------------------------

  @Test
  public void listViewsDocumentMatchesListTablesResponseShape() throws Exception {
    String json =
        IcebergRestWire.toListViewsJson(
            Arrays.asList(
                TableIdentifier.of("my_database", "my_view"),
                TableIdentifier.of("my_database", "my_other_view")),
            "opaque-token");
    JsonNode root = JSON.readTree(json);

    Assertions.assertEquals(setOf("identifiers", "next-page-token"), fieldNames(root));
    JsonNode first = root.get("identifiers").get(0);
    Assertions.assertEquals(setOf("namespace", "name"), fieldNames(first));
    Assertions.assertEquals("my_database", first.get("namespace").get(0).asText());
    Assertions.assertEquals("my_view", first.get("name").asText());
    Assertions.assertEquals("opaque-token", root.get("next-page-token").asText());
  }

  @Test
  public void aCompleteListingOmitsTheNextPageToken() throws Exception {
    JsonNode root =
        JSON.readTree(
            IcebergRestWire.toListViewsJson(
                Collections.singletonList(TableIdentifier.of("db", "v")), null));
    Assertions.assertEquals(
        setOf("identifiers"),
        fieldNames(root),
        "Token absence is the spec's termination signal and must be an omitted field, not null.");
  }

  @Test
  public void anEmptyListingSerializesAnEmptyIdentifiersArray() throws Exception {
    JsonNode root = JSON.readTree(IcebergRestWire.toListViewsJson(Collections.emptyList(), null));
    Assertions.assertTrue(root.get("identifiers").isArray());
    Assertions.assertEquals(0, root.get("identifiers").size());
  }

  // ---------------------------------------------------------------------------------------------
  // Error envelope
  // ---------------------------------------------------------------------------------------------

  @Test
  public void errorEnvelopeMatchesIcebergErrorResponseShape() throws Exception {
    String json =
        IcebergRestWire.toErrorJson(
            HttpStatus.NOT_FOUND, "NoSuchViewException", "Views are disabled");
    JsonNode root = JSON.readTree(json);

    Assertions.assertEquals(setOf("error"), fieldNames(root));
    Assertions.assertEquals(
        setOf("message", "type", "code"),
        fieldNames(root.get("error")),
        "No stack is ever serialized.");
    Assertions.assertEquals(404, root.get("error").get("code").asInt());

    ErrorResponse parsed = ErrorResponseParser.fromJson(json);
    Assertions.assertEquals("NoSuchViewException", parsed.type());
    Assertions.assertEquals("Views are disabled", parsed.message());
    Assertions.assertEquals(404, parsed.code());
  }

  // ---------------------------------------------------------------------------------------------
  // /v1/config
  // ---------------------------------------------------------------------------------------------

  @Test
  public void configBodyDeclaresEmptyMapsAndTheSevenImplementedEndpoints() throws Exception {
    JsonNode root = JSON.readTree(IcebergRestWire.toCatalogConfigJson());

    Assertions.assertEquals(setOf("defaults", "overrides", "endpoints"), fieldNames(root));
    Assertions.assertEquals(0, root.get("defaults").size());
    Assertions.assertEquals(0, root.get("overrides").size());

    Set<String> endpoints = new LinkedHashSet<>();
    root.get("endpoints").forEach(node -> endpoints.add(node.asText()));
    Assertions.assertEquals(
        setOf(
            "GET /v1/config",
            "GET /v1/{prefix}/namespaces/{namespace}/views",
            "POST /v1/{prefix}/namespaces/{namespace}/views",
            "GET /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "POST /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}"),
        endpoints,
        "The endpoints list advertises exactly the implemented surface: no namespace or table"
            + " routes, no rename-view, no register-view, no OAuth endpoint.");
  }

  // ---------------------------------------------------------------------------------------------
  // Error taxonomy
  // ---------------------------------------------------------------------------------------------

  /** Every code's type string is spec vocabulary, and no code maps to 422 on this surface. */
  @Test
  public void everyViewErrorCodeCarriesSpecVocabularyAndNo422() {
    Set<String> specTypes =
        setOf(
            "NoSuchViewException",
            "NoSuchNamespaceException",
            "AlreadyExistsException",
            "CommitFailedException",
            "BadRequestException",
            "ValidationException",
            "ServiceUnavailableException");
    for (ViewErrorCode code : ViewErrorCode.values()) {
      Assertions.assertTrue(
          specTypes.contains(code.getErrorType()),
          code.name() + " carries a non-spec type: " + code.getErrorType());
      Assertions.assertNotEquals(
          HttpStatus.UNPROCESSABLE_ENTITY,
          code.getHttpStatus(),
          "422 is not Iceberg REST views vocabulary; " + code.name() + " must not use it.");
    }
  }

  /** The per-route 404 vocabulary applies to exactly the two namespace-scoped codes. */
  @Test
  public void exactlyTheTwoNamespaceScopedCodesAreRouteSensitive() {
    Set<String> routeSensitive = new TreeSet<>();
    for (ViewErrorCode code : ViewErrorCode.values()) {
      if (code.isRouteSensitive404()) {
        routeSensitive.add(code.name());
        Assertions.assertEquals(
            "NoSuchNamespaceException",
            code.getErrorType(),
            "A route-sensitive code stores its collection-route (namespace) type.");
        Assertions.assertEquals(HttpStatus.NOT_FOUND, code.getHttpStatus());
      }
    }
    Assertions.assertEquals(setOf("DATABASE_NOT_FOUND", "VIEWS_DISABLED"), routeSensitive);
  }

  /**
   * The golden taxonomy pin, transcribed by hand from the plan document's §3.1 error table —
   * deliberately not derived from the enum — so changing any code's (status, type) pairing is a
   * two-file edit: the enum and this literal table (and the plan doc that both mirror).
   */
  @Test
  public void taxonomyMatchesThePlanDocumentLiterally() {
    Map<String, Object[]> golden = new LinkedHashMap<>();
    golden.put("NO_SUCH_VIEW", pair(404, "NoSuchViewException"));
    golden.put("VIEW_ALREADY_EXISTS", pair(409, "AlreadyExistsException"));
    golden.put("NAME_ALREADY_EXISTS_AS_TABLE", pair(409, "AlreadyExistsException"));
    golden.put("CONCURRENT_VIEW_MODIFICATION", pair(409, "CommitFailedException"));
    golden.put("DATABASE_NOT_FOUND", pair(404, "NoSuchNamespaceException"));
    golden.put("VIEWS_DISABLED", pair(404, "NoSuchNamespaceException"));
    golden.put("INVALID_VIEW_DEFINITION", pair(400, "BadRequestException"));
    golden.put("UNSUPPORTED_VIEW_DIALECT", pair(400, "BadRequestException"));
    golden.put("UNSUPPORTED_VIEW_SCHEMA", pair(400, "BadRequestException"));
    golden.put("VIEW_ADMISSION_FAILED", pair(400, "ValidationException"));
    golden.put("REQUIRED_REPRESENTATION_MISSING", pair(400, "ValidationException"));
    golden.put("DEPENDENCY_CYCLE", pair(400, "ValidationException"));
    golden.put("MAX_VIEW_DEPTH_EXCEEDED", pair(400, "ValidationException"));
    golden.put("ADMISSION_SERVICE_UNAVAILABLE", pair(503, "ServiceUnavailableException"));

    Assertions.assertEquals(
        golden.keySet(),
        java.util.Arrays.stream(ViewErrorCode.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
        "The enum and the golden table must name exactly the same codes.");
    for (ViewErrorCode code : ViewErrorCode.values()) {
      Object[] expected = golden.get(code.name());
      Assertions.assertEquals(
          expected[0],
          code.getHttpStatus().value(),
          code.name() + " drifted from the plan document's status.");
      Assertions.assertEquals(
          expected[1],
          code.getErrorType(),
          code.name() + " drifted from the plan document's type.");
    }
  }

  private static Object[] pair(int status, String type) {
    return new Object[] {status, type};
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private static ViewMetadata fixtureMetadata() {
    return IcebergRestViewFixtures.viewMetadata();
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new LinkedHashSet<>();
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      names.add(it.next());
    }
    return names;
  }

  private static Set<String> setOf(String... values) {
    return new LinkedHashSet<>(Arrays.asList(values));
  }
}
