package com.linkedin.openhouse.tables.mock.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.audit.CachingRequestBodyFilter;
import com.linkedin.openhouse.common.audit.ServiceAuditPayloadRedactor;
import com.linkedin.openhouse.common.audit.model.ServiceAuditEvent;
import com.linkedin.openhouse.common.exception.handler.OpenHouseExceptionHandler;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.tables.api.handler.ViewsApiHandler;
import com.linkedin.openhouse.tables.controller.IcebergRestViewsController;
import com.linkedin.openhouse.tables.controller.IcebergRestViewsExceptionHandler;
import com.linkedin.openhouse.tables.controller.V1RestUnresolvedPathExceptionHandler;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.IcebergRestViewFixtures;
import java.io.IOException;
import java.text.ParseException;
import java.util.stream.Stream;
import org.codehaus.jettison.json.JSONException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.AuthorizationServiceException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MockMvc coverage of the Iceberg REST views surface: {@code /v1/config}, the six view routes, the
 * views-disabled posture per route (the spec's per-route 404 vocabulary), the {@code
 * IcebergErrorResponse} envelope on every failure, the {@code /v1/**} unresolved-path contract, and
 * service-audit redaction.
 *
 * <p>The disabled-posture tests run against the real bean chain (controller, handler, validator,
 * {@code ViewsDisabledService}), so they pin the deployed behavior end to end. The error-mapping
 * matrix additionally drives a mocked handler so every {@code ViewErrorCode} and the uncoded
 * failure paths can be observed on demand.
 */
@SpringBootTest
@ContextConfiguration(initializers = AuthorizationPropertiesInitializer.class)
public class IcebergRestViewsControllerTest {

  private static final String CONFIG_PATH = "/v1/config";
  private static final String VIEWS_PATH =
      "/v1/namespaces/" + IcebergRestViewFixtures.DATABASE_ID + "/views";
  private static final String VIEW_PATH = VIEWS_PATH + "/" + IcebergRestViewFixtures.VIEW_ID;

  private static final String NO_SUCH_VIEW_TYPE = "NoSuchViewException";
  private static final String NO_SUCH_NAMESPACE_TYPE = "NoSuchNamespaceException";
  private static final String VIEWS_DISABLED_MESSAGE = "Views are disabled";

  private MockMvc mvc;

  /**
   * A second MockMvc that raises {@link org.springframework.web.servlet.NoHandlerFoundException}
   * for an unmapped path instead of letting the container answer a bare 404, matching the deployed
   * {@code spring.mvc.throw-exception-if-no-handler-found=true} configuration.
   */
  private MockMvc mvcThrowingOnUnmappedPath;

  /** A third MockMvc whose handler is a Mockito mock, for the on-demand error matrix. */
  private MockMvc mvcWithMockedHandler;

  private ViewsApiHandler mockedHandler;

  private String jwtAccessToken;

  @Autowired private IcebergRestViewsController viewsController;

  @Autowired private IcebergRestViewsExceptionHandler viewsExceptionHandler;

  @Autowired private V1RestUnresolvedPathExceptionHandler unresolvedPathExceptionHandler;

  @Autowired private OpenHouseExceptionHandler openHouseExceptionHandler;

  @MockBean private AuditHandler<ServiceAuditEvent> serviceAuditHandler;

  @Captor private ArgumentCaptor<ServiceAuditEvent> argCaptor;

  @BeforeEach
  public void setup() throws IOException, JSONException, ParseException {
    mvc =
        MockMvcBuilders.standaloneSetup(viewsController)
            .setControllerAdvice(
                viewsExceptionHandler, unresolvedPathExceptionHandler, openHouseExceptionHandler)
            .addInterceptors(new DummyTokenInterceptor())
            .addFilter(new CachingRequestBodyFilter())
            .build();

    mvcThrowingOnUnmappedPath =
        MockMvcBuilders.standaloneSetup(viewsController)
            .setControllerAdvice(
                viewsExceptionHandler, unresolvedPathExceptionHandler, openHouseExceptionHandler)
            .addInterceptors(new DummyTokenInterceptor())
            .addFilter(new CachingRequestBodyFilter())
            .addDispatcherServletCustomizer(
                dispatcherServlet -> dispatcherServlet.setThrowExceptionIfNoHandlerFound(true))
            .build();

    mockedHandler = Mockito.mock(ViewsApiHandler.class);
    IcebergRestViewsController controllerWithMockedHandler = new IcebergRestViewsController();
    ReflectionTestUtils.setField(controllerWithMockedHandler, "viewsApiHandler", mockedHandler);
    mvcWithMockedHandler =
        MockMvcBuilders.standaloneSetup(controllerWithMockedHandler)
            .setControllerAdvice(
                viewsExceptionHandler, unresolvedPathExceptionHandler, openHouseExceptionHandler)
            .addInterceptors(new DummyTokenInterceptor())
            .build();

    DummyTokenInterceptor.DummySecurityJWT dummySecurityJWT =
        new DummyTokenInterceptor.DummySecurityJWT("DUMMY_ANONYMOUS_USER");
    jwtAccessToken = dummySecurityJWT.buildNoopJWT();
  }

  // ---------------------------------------------------------------------------------------------
  // /v1/config
  // ---------------------------------------------------------------------------------------------

  @Test
  public void configIsServedWhileViewsAreDisabled() throws Exception {
    mvc.perform(authed(MockMvcRequestBuilders.get(CONFIG_PATH)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.defaults").isEmpty())
        .andExpect(jsonPath("$.overrides").isEmpty())
        .andExpect(jsonPath("$.endpoints", Matchers.hasSize(7)))
        .andExpect(
            jsonPath(
                "$.endpoints",
                Matchers.hasItem("GET /v1/{prefix}/namespaces/{namespace}/views/{view}")));
  }

  @Test
  public void configRequiresAuthentication() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get(CONFIG_PATH)).andExpect(status().isUnauthorized());
  }

  // ---------------------------------------------------------------------------------------------
  // Views-disabled posture, per route (F1): the deployed 404 vocabulary
  // ---------------------------------------------------------------------------------------------

  @Test
  public void disabledLoadViewIs404NoSuchViewException() throws Exception {
    mvc.perform(authed(MockMvcRequestBuilders.get(VIEW_PATH)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error.type", Matchers.is(NO_SUCH_VIEW_TYPE)))
        .andExpect(jsonPath("$.error.message", Matchers.is(VIEWS_DISABLED_MESSAGE)))
        .andExpect(jsonPath("$.error.code", Matchers.is(404)))
        .andExpect(jsonPath("$.error.stack").doesNotExist());
  }

  @Test
  public void disabledReplaceViewIs404NoSuchViewException() throws Exception {
    mvc.perform(
            authed(MockMvcRequestBuilders.post(VIEW_PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(IcebergRestViewFixtures.commitViewRequestJson()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type", Matchers.is(NO_SUCH_VIEW_TYPE)))
        .andExpect(jsonPath("$.error.message", Matchers.is(VIEWS_DISABLED_MESSAGE)));
  }

  @Test
  public void disabledDropViewIs404NoSuchViewException() throws Exception {
    mvc.perform(authed(MockMvcRequestBuilders.delete(VIEW_PATH)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type", Matchers.is(NO_SUCH_VIEW_TYPE)))
        .andExpect(jsonPath("$.error.message", Matchers.is(VIEWS_DISABLED_MESSAGE)));
  }

  /** The spec's HEAD carries no body on any status, including this 404. */
  @Test
  public void disabledViewExistsIs404WithEmptyBody() throws Exception {
    mvc.perform(authed(MockMvcRequestBuilders.head(VIEW_PATH)))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  public void disabledListViewsIs404NoSuchNamespaceException() throws Exception {
    mvc.perform(authed(MockMvcRequestBuilders.get(VIEWS_PATH)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type", Matchers.is(NO_SUCH_NAMESPACE_TYPE)))
        .andExpect(jsonPath("$.error.message", Matchers.is(VIEWS_DISABLED_MESSAGE)));
  }

  @Test
  public void disabledCreateViewIs404NoSuchNamespaceException() throws Exception {
    mvc.perform(
            authed(MockMvcRequestBuilders.post(VIEWS_PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(IcebergRestViewFixtures.createViewRequestJson()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type", Matchers.is(NO_SUCH_NAMESPACE_TYPE)))
        .andExpect(jsonPath("$.error.message", Matchers.is(VIEWS_DISABLED_MESSAGE)));
  }

  // ---------------------------------------------------------------------------------------------
  // Authentication: 401 stays a bare status on every route
  // ---------------------------------------------------------------------------------------------

  private static Stream<Arguments> allViewRoutes() {
    return Stream.of(
        Arguments.of("GET config", (RouteCall) () -> MockMvcRequestBuilders.get(CONFIG_PATH)),
        Arguments.of("GET views", (RouteCall) () -> MockMvcRequestBuilders.get(VIEWS_PATH)),
        Arguments.of(
            "POST views",
            (RouteCall)
                () ->
                    MockMvcRequestBuilders.post(VIEWS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IcebergRestViewFixtures.createViewRequestJson())),
        Arguments.of("GET view", (RouteCall) () -> MockMvcRequestBuilders.get(VIEW_PATH)),
        Arguments.of(
            "POST view",
            (RouteCall)
                () ->
                    MockMvcRequestBuilders.post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IcebergRestViewFixtures.commitViewRequestJson())),
        Arguments.of("DELETE view", (RouteCall) () -> MockMvcRequestBuilders.delete(VIEW_PATH)),
        Arguments.of("HEAD view", (RouteCall) () -> MockMvcRequestBuilders.head(VIEW_PATH)));
  }

  @FunctionalInterface
  interface RouteCall {
    MockHttpServletRequestBuilder request();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allViewRoutes")
  public void everyRouteRejectsAMissingBearerTokenWith401(String routeName, RouteCall route)
      throws Exception {
    mvc.perform(route.request()).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allViewRoutes")
  public void everyRouteRejectsAMalformedBearerTokenWith401(String routeName, RouteCall route)
      throws Exception {
    mvc.perform(route.request().header("Authorization", "Bearer not-a-real-jwt"))
        .andExpect(status().isUnauthorized());
  }

  // ---------------------------------------------------------------------------------------------
  // Malformed and missing bodies belong to the views error surface
  // ---------------------------------------------------------------------------------------------

  @Test
  public void malformedCreateBodyIsA400BadRequestEnvelope() throws Exception {
    mvc.perform(
            authed(MockMvcRequestBuilders.post(VIEWS_PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type", Matchers.is("BadRequestException")))
        .andExpect(jsonPath("$.error.code", Matchers.is(400)))
        // Fixed message: parser messages may echo the submitted document.
        .andExpect(jsonPath("$.error.message", Matchers.startsWith("Malformed CreateViewRequest")));
  }

  @Test
  public void missingCreateBodyIsA400BadRequestEnvelope() throws Exception {
    mvc.perform(
            authed(MockMvcRequestBuilders.post(VIEWS_PATH)).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type", Matchers.is("BadRequestException")));
  }

  @Test
  public void malformedCommitBodyIsA400BadRequestEnvelope() throws Exception {
    mvc.perform(
            authed(MockMvcRequestBuilders.post(VIEW_PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content("not json at all"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type", Matchers.is("BadRequestException")))
        .andExpect(jsonPath("$.error.message", Matchers.startsWith("Malformed CommitViewRequest")));
  }

  /** A structurally invalid identifier accumulates every violation into one 400 envelope. */
  @Test
  public void validationFailuresAccumulateIntoOne400Envelope() throws Exception {
    mvc.perform(authed(MockMvcRequestBuilders.get("/v1/namespaces/bad-db!/views/bad-view!")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type", Matchers.is("BadRequestException")))
        .andExpect(jsonPath("$.error.message", Matchers.containsString("namespace :")))
        .andExpect(jsonPath("$.error.message", Matchers.containsString("view :")))
        .andExpect(jsonPath("$.error.message", Matchers.containsString("; ")));
  }

  // ---------------------------------------------------------------------------------------------
  // Multi-level namespaces: 404, with the per-route type
  // ---------------------------------------------------------------------------------------------

  @Test
  public void multiLevelNamespaceOnListIs404NoSuchNamespaceException() throws Exception {
    mvc.perform(
            authed(
                MockMvcRequestBuilders.get(
                    java.net.URI.create("/v1/namespaces/parent%1Fchild/views"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type", Matchers.is(NO_SUCH_NAMESPACE_TYPE)));
  }

  @Test
  public void multiLevelNamespaceOnLoadIs404NoSuchViewException() throws Exception {
    mvc.perform(
            authed(
                MockMvcRequestBuilders.get(
                    java.net.URI.create("/v1/namespaces/parent%1Fchild/views/v"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type", Matchers.is(NO_SUCH_VIEW_TYPE)));
  }

  // ---------------------------------------------------------------------------------------------
  // /v1/** unresolved paths: Iceberg 404; everything else keeps the legacy contract
  // ---------------------------------------------------------------------------------------------

  @Test
  public void unknownV1PathIsAnIceberg404Envelope() throws Exception {
    mvcThrowingOnUnmappedPath
        .perform(authed(MockMvcRequestBuilders.post("/v1/views/rename")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type", Matchers.is("NotFoundException")))
        .andExpect(jsonPath("$.error.code", Matchers.is(404)));
  }

  @Test
  public void unknownV1TableRestPathIsAnIceberg404Envelope() throws Exception {
    mvcThrowingOnUnmappedPath
        .perform(authed(MockMvcRequestBuilders.get("/v1/namespaces/d/tables/t")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.type", Matchers.is("NotFoundException")));
  }

  /** The retired /v2 views surface no longer resolves, and keeps the legacy rendering. */
  @Test
  public void retiredV2ViewsPathKeepsTheLegacyUnresolvedContract() throws Exception {
    mvcThrowingOnUnmappedPath
        .perform(authed(MockMvcRequestBuilders.get("/v2/databases/d200/views/my_view")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", Matchers.containsString("cannot be resolved by server")))
        .andExpect(jsonPath("$.error").value("Bad Request"));
  }

  // ---------------------------------------------------------------------------------------------
  // Error matrix through a mocked handler
  // ---------------------------------------------------------------------------------------------

  /**
   * Every internal code renders its status and type on a per-view route; the two route-sensitive
   * 404 codes render as {@code NoSuchViewException} here.
   */
  @ParameterizedTest
  @EnumSource(ViewErrorCode.class)
  public void everyErrorCodeRendersItsStatusAndTypeOnAViewRoute(ViewErrorCode errorCode)
      throws Exception {
    Mockito.when(mockedHandler.loadView(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenThrow(new ViewApiException(errorCode, "fixed message"));

    String expectedType =
        errorCode.isRouteSensitive404() ? NO_SUCH_VIEW_TYPE : errorCode.getErrorType();
    mvcWithMockedHandler
        .perform(authed(MockMvcRequestBuilders.get(VIEW_PATH)))
        .andExpect(status().is(errorCode.getHttpStatus().value()))
        .andExpect(jsonPath("$.error.type", Matchers.is(expectedType)))
        .andExpect(jsonPath("$.error.message", Matchers.is("fixed message")))
        .andExpect(jsonPath("$.error.code", Matchers.is(errorCode.getHttpStatus().value())))
        // The envelope replaces the OpenHouse body: none of its fields may appear.
        .andExpect(jsonPath("$.status").doesNotExist())
        .andExpect(jsonPath("$.stacktrace").doesNotExist());
  }

  /** The same codes render their stored (namespace) type on the collection routes. */
  @ParameterizedTest
  @EnumSource(ViewErrorCode.class)
  public void everyErrorCodeRendersItsStoredTypeOnTheListRoute(ViewErrorCode errorCode)
      throws Exception {
    Mockito.when(
            mockedHandler.listViews(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
        .thenThrow(new ViewApiException(errorCode, "fixed message"));

    mvcWithMockedHandler
        .perform(authed(MockMvcRequestBuilders.get(VIEWS_PATH)))
        .andExpect(status().is(errorCode.getHttpStatus().value()))
        .andExpect(jsonPath("$.error.type", Matchers.is(errorCode.getErrorType())));
  }

  @Test
  public void accessDeniedRendersA403ForbiddenEnvelopeWithoutStacktrace() throws Exception {
    Mockito.when(mockedHandler.loadView(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenThrow(new AccessDeniedException("principal lacks SELECT"));

    mvcWithMockedHandler
        .perform(authed(MockMvcRequestBuilders.get(VIEW_PATH)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.type", Matchers.is("ForbiddenException")))
        .andExpect(jsonPath("$.error.message", Matchers.is("principal lacks SELECT")))
        .andExpect(jsonPath("$.error.stack").doesNotExist())
        .andExpect(jsonPath("$.stacktrace").doesNotExist());
  }

  @Test
  public void infrastructureFailureRendersA503ServiceUnavailableEnvelope() throws Exception {
    Mockito.when(mockedHandler.loadView(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenThrow(new AuthorizationServiceException("authorization backend unavailable"));

    mvcWithMockedHandler
        .perform(authed(MockMvcRequestBuilders.get(VIEW_PATH)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.type", Matchers.is("ServiceUnavailableException")))
        .andExpect(jsonPath("$.error.code", Matchers.is(503)));
  }

  /** An arbitrary server fault is a 500 envelope with a fixed message: no internals leak. */
  @Test
  public void unexpectedFailureRendersA500EnvelopeWithAFixedMessage() throws Exception {
    Mockito.when(mockedHandler.loadView(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenThrow(new IllegalStateException("secret internal detail"));

    mvcWithMockedHandler
        .perform(authed(MockMvcRequestBuilders.get(VIEW_PATH)))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.type", Matchers.is("InternalServerError")))
        .andExpect(jsonPath("$.error.message", Matchers.is("Internal Server Error")));
  }

  // ---------------------------------------------------------------------------------------------
  // Service audit redaction
  // ---------------------------------------------------------------------------------------------

  private static final String SECRET_SQL_MARKER = "secret_sql_marker_column";
  private static final String SECRET_SCHEMA_MARKER = "secret_schema_marker_column";

  private String createRequestCarryingSecrets() {
    org.apache.iceberg.Schema secretSchema =
        new org.apache.iceberg.Schema(
            org.apache.iceberg.types.Types.NestedField.required(
                1, SECRET_SCHEMA_MARKER, org.apache.iceberg.types.Types.StringType.get()));
    return IcebergRestViewFixtures.createViewRequestJson(
        org.apache.iceberg.rest.requests.ImmutableCreateViewRequest.builder()
            .from(IcebergRestViewFixtures.createViewRequest())
            .schema(secretSchema)
            .viewVersion(
                IcebergRestViewFixtures.viewVersionWithRepresentations(
                    IcebergRestViewFixtures.representation(
                        "spark", "SELECT " + SECRET_SQL_MARKER + " FROM t")))
            .build());
  }

  /**
   * The failure path is the important one: the audit event is emitted from the exception path after
   * the request body has been cached, so a rejected request writes its payload just as an accepted
   * one would — and with the stubbed service, rejection is the only path there is.
   */
  @Test
  public void serviceAuditOnViewCreateRedactsSchemaAndSql() throws Exception {
    mvc.perform(
        authed(MockMvcRequestBuilders.post(VIEWS_PATH))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequestCarryingSecrets()));

    ServiceAuditEvent event = capturedAuditEvent();
    Assertions.assertEquals(
        404, event.getStatusCode(), "Precondition: the disabled service rejects the create.");
    JsonElement payload = event.getRequestPayload();
    Assertions.assertNotNull(payload);
    JsonObject payloadObject = payload.getAsJsonObject();

    Assertions.assertEquals(
        ServiceAuditPayloadRedactor.REDACTED_VALUE,
        payloadObject.get("schema").getAsString(),
        "The schema document must not reach the audit event, but the key must survive.");
    JsonObject representation =
        payloadObject
            .getAsJsonObject("view-version")
            .getAsJsonArray("representations")
            .get(0)
            .getAsJsonObject();
    Assertions.assertEquals(
        ServiceAuditPayloadRedactor.REDACTED_VALUE, representation.get("sql").getAsString());
    Assertions.assertEquals("spark", representation.get("dialect").getAsString());

    String serialized = payload.toString();
    Assertions.assertFalse(serialized.contains(SECRET_SQL_MARKER));
    Assertions.assertFalse(serialized.contains(SECRET_SCHEMA_MARKER));
    // Identity and metadata stay auditable.
    Assertions.assertEquals(
        IcebergRestViewFixtures.VIEW_ID, payloadObject.get("name").getAsString());
  }

  @Test
  public void serviceAuditOnViewReplaceRedactsSchemaAndSql() throws Exception {
    org.apache.iceberg.rest.requests.UpdateTableRequest commit =
        org.apache.iceberg.rest.requests.UpdateTableRequest.create(
            null,
            java.util.Collections.singletonList(
                new org.apache.iceberg.UpdateRequirement.AssertViewUUID(
                    IcebergRestViewFixtures.VIEW_UUID)),
            java.util.Arrays.asList(
                new org.apache.iceberg.MetadataUpdate.AddViewVersion(
                    IcebergRestViewFixtures.viewVersionWithRepresentations(
                        IcebergRestViewFixtures.representation(
                            "spark", "SELECT " + SECRET_SQL_MARKER + " FROM t"))),
                new org.apache.iceberg.MetadataUpdate.AddSchema(
                    new org.apache.iceberg.Schema(
                        org.apache.iceberg.types.Types.NestedField.required(
                            1,
                            SECRET_SCHEMA_MARKER,
                            org.apache.iceberg.types.Types.StringType.get())),
                    1)));

    mvc.perform(
        authed(MockMvcRequestBuilders.post(VIEW_PATH))
            .contentType(MediaType.APPLICATION_JSON)
            .content(org.apache.iceberg.rest.requests.UpdateTableRequestParser.toJson(commit)));

    ServiceAuditEvent event = capturedAuditEvent();
    Assertions.assertEquals(
        404, event.getStatusCode(), "Precondition: the disabled service rejects the replace.");
    String serialized = event.getRequestPayload().toString();
    Assertions.assertFalse(
        serialized.contains(SECRET_SQL_MARKER),
        "No fragment of the submitted SQL may appear anywhere in the audited payload.");
    Assertions.assertFalse(
        serialized.contains(SECRET_SCHEMA_MARKER),
        "No fragment of the submitted schema may appear anywhere in the audited payload.");
  }

  private ServiceAuditEvent capturedAuditEvent() {
    Mockito.verify(serviceAuditHandler, Mockito.atLeastOnce()).audit(argCaptor.capture());
    return argCaptor.getValue();
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder
        .accept(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + jwtAccessToken);
  }
}
