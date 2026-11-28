package com.linkedin.openhouse.tables.e2e.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.audit.model.ServiceAuditEvent;
import com.linkedin.openhouse.common.audit.model.ServiceName;
import com.linkedin.openhouse.common.exception.handler.OpenHouseExceptionHandler;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.mock.properties.CustomClusterPropertiesInitializer;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * The tables service with the Iceberg REST Catalog routes off, as every deployment has them unless
 * it sets {@code cluster.tables.irc.enabled}: here the property is unset. Every request under
 * {@code /iceberg} is then answered as on LinkedIn main, where nothing is mapped there: no handler,
 * so neither an interceptor nor any route runs, and the native advice answers the 400 of an
 * unmapped path, with or without credentials, audited under no service name. None of the routes'
 * beans is created, nothing is mapped under {@code /iceberg}, the token interceptor is registered
 * on every path as on main, and a native route's error is answered by the native advice. The class
 * uses only what LinkedIn main has, and passes on main's code as it does here.
 *
 * <p>Its context is the one of {@link ViewsDualStorageH2IntegrationTest} (same application,
 * initializers and mock): the suite's only cached context with the token interceptor configured and
 * without the shared set-up that turns the routes on, so this class adds no context.
 */
@SpringBootTest(classes = SpringH2Application.class)
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      CustomClusterPropertiesInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IcebergRestRoutesOffTest {

  /** The package of the Iceberg REST routes' classes. */
  private static final String ROUTES_PACKAGE = "com.linkedin.openhouse.tables.api.irc";

  /** The annotation every bean that exists only for those routes carries, on this branch. */
  private static final String SWITCH_ANNOTATION = ROUTES_PACKAGE + ".ConditionalOnIcebergRest";

  private static final List<String> PATHS =
      Arrays.asList(
          "/iceberg/v1/config",
          "/iceberg/v1/namespaces",
          "/iceberg/v1/openhouse/namespaces",
          "/iceberg/v1/namespaces/db1/tables/t1",
          "/iceberg/v1/openhouse/namespaces/db1/tables/t1",
          "/iceberg/x");

  private static final List<HttpMethod> METHODS =
      Arrays.asList(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.DELETE);

  /**
   * Sends every request with a token: this suite's {@code MockMvcBuilderConfig} gives the autowired
   * {@code MockMvc} a default {@code Authorization} header.
   */
  @Autowired private MockMvc mvc;

  @Autowired private WebApplicationContext webApplicationContext;

  /** Sends no {@code Authorization} header: built on the same context, without that default. */
  private MockMvc tokenless;

  @Autowired private ApplicationContext context;

  @Autowired
  @Qualifier("serviceAuditHandler")
  private AuditHandler<ServiceAuditEvent> serviceAuditHandler;

  @MockBean private ViewsFeatureGate viewsFeatureGate;

  private String token;

  @BeforeEach
  public void setup() throws Exception {
    token = new DummyTokenInterceptor.DummySecurityJWT("irc_off_user").buildNoopJWT();
    // As the service's own dispatcher is set up (spring.mvc.throw-exception-if-no-handler-found),
    // with the service's servlet filters.
    tokenless =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(context.getBeansOfType(Filter.class).values().toArray(new Filter[0]))
            .addDispatcherServletCustomizer(
                dispatcher -> dispatcher.setThrowExceptionIfNoHandlerFound(true))
            .build();
  }

  @AfterAll
  static void unsetSysProp() {
    System.clearProperty("OPENHOUSE_CLUSTER_CONFIG_PATH");
  }

  private MvcResult perform(HttpMethod method, String path, boolean credentials, String body)
      throws Exception {
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path);
    if (credentials) {
      request.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      request.contentType("application/json").content(body);
    }
    Mockito.clearInvocations(serviceAuditHandler);
    MvcResult result = (credentials ? mvc : tokenless).perform(request).andReturn();
    assertEquals(
        credentials ? "Bearer " + token : null,
        result.getRequest().getHeader("Authorization"),
        method + " " + path);
    return result;
  }

  private List<ServiceAuditEvent> audited() {
    ArgumentCaptor<ServiceAuditEvent> events = ArgumentCaptor.forClass(ServiceAuditEvent.class);
    Mockito.verify(serviceAuditHandler, Mockito.atLeast(0)).audit(events.capture());
    return events.getAllValues();
  }

  /**
   * The answer of {@link OpenHouseExceptionHandler} to a request no handler maps, as on LinkedIn
   * main for every path under {@code /iceberg}.
   */
  private void assertUnmapped(MvcResult result, HttpMethod method, String path, String what)
      throws Exception {
    assertEquals(400, result.getResponse().getStatus(), what);
    assertNull(result.getHandler(), what);
    assertTrue(result.getResolvedException() instanceof NoHandlerFoundException, what);
    assertEquals("application/json", result.getResponse().getContentType(), what);
    if (method == HttpMethod.HEAD) {
      assertEquals("", result.getResponse().getContentAsString(), what);
    } else {
      JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
      assertEquals(5, body.size(), what + ": " + body);
      assertEquals("BAD_REQUEST", body.path("status").asText(), what);
      assertEquals("Bad Request", body.path("error").asText(), what);
      assertEquals(
          String.format(
              "The combination of the method [%s] and Path [uri=%s] cannot be resolved by server. "
                  + "Please check and making sure you are using the right version of API.",
              method, path),
          body.path("message").asText(),
          what);
      assertTrue(body.path("stacktrace").isNull(), what);
      assertEquals("Not Available", body.path("cause").asText(), what);
    }
    List<ServiceAuditEvent> events = audited();
    assertEquals(1, events.size(), what);
    assertEquals(ServiceName.UNRECOGNIZED, events.get(0).getServiceName(), what);
    assertEquals(400, events.get(0).getStatusCode(), what);
  }

  @Test
  public void everyRequestUnderIcebergIsAnsweredAsAPathNothingMaps() throws Exception {
    for (String path : PATHS) {
      for (HttpMethod method : METHODS) {
        for (boolean credentials : new boolean[] {false, true}) {
          String body = method == HttpMethod.POST ? "{}" : null;
          String what = method + " " + path + (credentials ? " with" : " without") + " a token";
          assertUnmapped(perform(method, path, credentials, body), method, path, what);
        }
      }
    }
  }

  /** The control: a native path nothing maps is answered the same way. */
  @Test
  public void aNativePathNothingMapsIsAnsweredTheSameWay() throws Exception {
    assertUnmapped(
        perform(HttpMethod.GET, "/v1/nothing", true, null),
        HttpMethod.GET,
        "/v1/nothing",
        "GET /v1/nothing");
  }

  /**
   * The token interceptor is on in this context: a mapped native route without a token is refused
   * by it (401, no body, no handler call), so the 400 answered above to tokenless requests under
   * {@code /iceberg} is not for want of an interceptor. It runs only where a handler is found, and
   * under {@code /iceberg} none is.
   */
  @Test
  public void aMappedNativeRouteWithoutATokenIsRefusedByTheTokenInterceptor() throws Exception {
    MvcResult result = perform(HttpMethod.GET, "/v1/databases/d1/tables/t1", false, null);
    assertEquals(401, result.getResponse().getStatus());
    assertNotNull(result.getHandler());
    assertNull(result.getResolvedException());
    assertEquals("", result.getResponse().getContentAsString());
    assertEquals(new ArrayList<ServiceAuditEvent>(), audited());
  }

  /** A native route's error is answered by the native advice, in its error shape. */
  @Test
  public void aNativeRoutesErrorIsAnsweredByTheNativeAdvice() throws Exception {
    MvcResult result = perform(HttpMethod.POST, "/v1/databases/d1/tables/", true, "{");
    assertEquals(400, result.getResponse().getStatus());
    assertTrue(result.getResolvedException() instanceof HttpMessageNotReadableException);
    JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
    assertEquals("BAD_REQUEST", body.path("status").asText(), body.toString());
    assertTrue(body.path("message").asText().startsWith("Unacceptable JSON"), body.toString());
    assertFalse(body.path("error").isObject(), body.toString());
  }

  @Test
  public void noBeanOfTheIcebergRestRoutesIsCreated() throws IOException {
    Set<String> switched = classesCarryingTheSwitch();
    List<String> found = new ArrayList<>();
    for (String name : context.getBeanDefinitionNames()) {
      Class<?> type = context.getType(name);
      if (type == null) {
        continue;
      }
      String className = ClassUtils.getUserClass(type).getName();
      if (className.startsWith(ROUTES_PACKAGE + ".") || switched.contains(className)) {
        found.add(name + " (" + className + ")");
      }
    }
    assertEquals(new ArrayList<String>(), found);
  }

  /**
   * Every class of the service carrying the routes' switch, found by scanning its packages (none on
   * LinkedIn main, which has no such annotation): the beans outside the routes' package that exist
   * only for them are found here, not listed by hand.
   */
  private static Set<String> classesCarryingTheSwitch() throws IOException {
    // Read from the class files, not through a component scan, which would apply the switch's own
    // condition and find none.
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
    Set<String> found = new TreeSet<>();
    for (Resource resource :
        resolver.getResources("classpath*:com/linkedin/openhouse/tables/**/*.class")) {
      AnnotationMetadata metadata = readers.getMetadataReader(resource).getAnnotationMetadata();
      if (metadata.hasAnnotation(SWITCH_ANNOTATION)
          || metadata.hasMetaAnnotation(SWITCH_ANNOTATION)) {
        found.add(metadata.getClassName());
      }
    }
    if (ClassUtils.isPresent(SWITCH_ANNOTATION, null)) {
      // On this branch the scan must find them, the one outside the routes' package included.
      assertTrue(
          found.contains("com.linkedin.openhouse.tables.services.TableCommitService"),
          found.toString());
    }
    return found;
  }

  @Test
  public void nothingIsMappedUnderIceberg() {
    List<String> found = new ArrayList<>();
    int routes = 0;
    for (HandlerMapping mapping : context.getBeansOfType(HandlerMapping.class).values()) {
      if (mapping instanceof RequestMappingInfoHandlerMapping) {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
            ((RequestMappingInfoHandlerMapping) mapping).getHandlerMethods().entrySet()) {
          routes++;
          String handler = ClassUtils.getUserClass(entry.getValue().getBeanType()).getName();
          for (String pattern : entry.getKey().getPatternValues()) {
            if (pattern.startsWith("/iceberg") || handler.startsWith(ROUTES_PACKAGE + ".")) {
              found.add(entry.getKey() + " -> " + handler);
            }
          }
        }
      }
      if (mapping instanceof AbstractUrlHandlerMapping) {
        for (String pattern : ((AbstractUrlHandlerMapping) mapping).getHandlerMap().keySet()) {
          if (pattern.startsWith("/iceberg")) {
            found.add(pattern);
          }
        }
      }
    }
    assertTrue(routes > 0, "the native routes are mapped");
    assertEquals(new ArrayList<String>(), found);
  }

  /**
   * The token interceptor is registered on every path, {@code /iceberg} included, as on LinkedIn
   * main, and no interceptor of the Iceberg REST routes is registered.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void theTokenInterceptorIsRegisteredAsOnMain() {
    int tokenInterceptors = 0;
    for (HandlerMapping mapping : context.getBeansOfType(HandlerMapping.class).values()) {
      if (!(mapping instanceof AbstractHandlerMapping)) {
        continue;
      }
      Object[] interceptors =
          (Object[]) ReflectionTestUtils.invokeMethod(mapping, "getAdaptedInterceptors");
      for (Object interceptor : interceptors) {
        Object target =
            interceptor instanceof MappedInterceptor
                ? ((MappedInterceptor) interceptor).getInterceptor()
                : interceptor;
        assertFalse(
            target.getClass().getName().startsWith(ROUTES_PACKAGE + "."), target.toString());
        if (target instanceof DummyTokenInterceptor) {
          tokenInterceptors++;
          MappedInterceptor mapped = (MappedInterceptor) interceptor;
          assertTrue(mapped.matches("/iceberg/v1/config", new AntPathMatcher()));
          assertTrue(mapped.matches("/v1/databases", new AntPathMatcher()));
          assertFalse(mapped.matches("/actuator/health", new AntPathMatcher()));
        }
      }
    }
    assertTrue(tokenInterceptors > 0, "the token interceptor is registered");
  }
}
