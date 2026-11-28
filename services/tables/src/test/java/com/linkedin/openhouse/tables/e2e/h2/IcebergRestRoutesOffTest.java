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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.ClassUtils;
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

  /** The classes outside that package that exist only for those routes. */
  private static final List<String> ROUTES_ONLY_CLASSES =
      Arrays.asList("com.linkedin.openhouse.tables.services.TableCommitService");

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

  @Autowired private MockMvc mvc;

  @Autowired private ApplicationContext context;

  @Autowired
  @Qualifier("serviceAuditHandler")
  private AuditHandler<ServiceAuditEvent> serviceAuditHandler;

  @MockBean private ViewsFeatureGate viewsFeatureGate;

  private String token;

  @BeforeEach
  public void setup() throws Exception {
    token = new DummyTokenInterceptor.DummySecurityJWT("irc_off_user").buildNoopJWT();
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
    return mvc.perform(request).andReturn();
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
   * A mapped native route reached without a token is answered as on LinkedIn main in this context:
   * its handler runs and the native advice answers the missing table (main's own code, run with
   * this class, answers the same; why the token interceptor lets the request through in this
   * context was not established).
   */
  @Test
  public void aMappedNativeRouteWithoutATokenIsAnsweredAsOnMain() throws Exception {
    MvcResult result = perform(HttpMethod.GET, "/v1/databases/d1/tables/t1", false, null);
    assertEquals(404, result.getResponse().getStatus());
    assertNotNull(result.getHandler());
    JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
    assertEquals("NOT_FOUND", body.path("status").asText(), body.toString());
    assertEquals("User table d1.t1 cannot be found", body.path("message").asText());
    List<ServiceAuditEvent> events = audited();
    assertEquals(1, events.size());
    assertEquals(ServiceName.TABLES_SERVICE, events.get(0).getServiceName());
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
  public void noBeanOfTheIcebergRestRoutesIsCreated() {
    List<String> found = new ArrayList<>();
    ConfigurableListableBeanFactory beans =
        ((ConfigurableApplicationContext) context).getBeanFactory();
    for (String name : context.getBeanDefinitionNames()) {
      Class<?> type = context.getType(name);
      // A test's own configuration in that package (scanned on the default line), and the beans
      // it declares, are not the service's.
      String factory = beans.getBeanDefinition(name).getFactoryBeanName();
      if (type == null
          || isTestConfiguration(context.getType(name))
          || (factory != null && isTestConfiguration(context.getType(factory)))) {
        continue;
      }
      String className = ClassUtils.getUserClass(type).getName();
      if (className.startsWith(ROUTES_PACKAGE + ".") || ROUTES_ONLY_CLASSES.contains(className)) {
        found.add(name + " (" + className + ")");
      }
    }
    assertEquals(new ArrayList<String>(), found);
  }

  private static boolean isTestConfiguration(Class<?> type) {
    return type != null
        && AnnotatedElementUtils.hasAnnotation(
            ClassUtils.getUserClass(type), TestConfiguration.class);
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
