package com.linkedin.openhouse.tables.mock.api;

import com.linkedin.openhouse.tables.api.handler.impl.OpenHouseViewsApiHandler;
import com.linkedin.openhouse.tables.api.validator.impl.OpenHouseViewsApiValidator;
import com.linkedin.openhouse.tables.config.ConditionalOnIcebergViewApi;
import com.linkedin.openhouse.tables.config.ViewsServiceConfiguration;
import com.linkedin.openhouse.tables.controller.IcebergRestViewsController;
import com.linkedin.openhouse.tables.controller.IcebergRestViewsExceptionHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * The views beans stay conditional on Iceberg having a view API.
 *
 * <h2>The failure this guards</h2>
 *
 * <p>The tables application is booted against Iceberg 1.2 by the iceberg-1.2 test fixture, which is
 * how every Spark 3.1 integration test gets a server. Iceberg 1.2 has no {@code
 * org.apache.iceberg.view} package. A views bean left unconditional there does not fail its own
 * routes — it fails <b>context startup</b>, and every test in that module then fails against a
 * server that never came up, reporting a missing class rather than anything about views.
 *
 * <h2>What is and is not checkable here</h2>
 *
 * <p>The authoritative proof is the Spark 3.1 integration suite, which really does start this
 * application without the view API. Two things break a bean there: naming a view type in a
 * supertype or method signature, which Spring resolves while parsing and creating it, and depending
 * on a bean that is itself conditional, which leaves the dependency unsatisfiable. The second is
 * not visible in a signature — the handler below takes only {@code String} and its own interfaces,
 * and needs the annotation solely because the service it consumes has it.
 *
 * <p>So this is two checks rather than one. The listed set is a golden list: those five classes are
 * the views surface today, and dropping an annotation from any of them fails here in seconds
 * instead of in a Spark run. The scan then catches a <i>new</i> bean that names the view API and
 * was never added to the list — the case a golden list alone cannot see.
 */
public class IcebergViewApiConditionTest {

  private static final String SCANNED_ROOT = "com.linkedin.openhouse.tables";

  /** The packages that do not exist before Iceberg 1.4. */
  private static final List<String> VIEW_API_PACKAGES =
      Arrays.asList("org.apache.iceberg.view.", "org.apache.iceberg.rest.");

  /** Every bean that makes up the views surface. */
  private static final List<Class<?>> VIEWS_SURFACE =
      Arrays.asList(
          OpenHouseViewsApiHandler.class,
          OpenHouseViewsApiValidator.class,
          IcebergRestViewsController.class,
          IcebergRestViewsExceptionHandler.class,
          ViewsServiceConfiguration.class);

  @Test
  public void everyBeanOfTheViewsSurfaceCarriesTheCondition() {
    Set<String> unguarded = new TreeSet<>();
    for (Class<?> beanClass : VIEWS_SURFACE) {
      if (beanClass.getAnnotation(ConditionalOnIcebergViewApi.class) == null) {
        unguarded.add(beanClass.getName());
      }
    }
    Assertions.assertTrue(
        unguarded.isEmpty(),
        "Without @ConditionalOnIcebergViewApi these beans are registered on an Iceberg-1.2"
            + " classpath, where the application then fails to start: "
            + unguarded);
  }

  @Test
  public void aBeanNamingTheViewApiIsEitherConditionalOrOnTheList() throws Exception {
    Set<String> unguarded = new TreeSet<>();
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(true);

    for (BeanDefinition definition : scanner.findCandidateComponents(SCANNED_ROOT)) {
      Class<?> beanClass = Class.forName(definition.getBeanClassName());
      if (namesTheViewApi(beanClass)
          && beanClass.getAnnotation(ConditionalOnIcebergViewApi.class) == null) {
        unguarded.add(beanClass.getName());
      }
    }

    Assertions.assertTrue(
        unguarded.isEmpty(),
        "These beans name Iceberg's view API in a supertype or a method signature, so Spring"
            + " resolves it during context startup. Annotate each with @ConditionalOnIcebergViewApi"
            + " and add it to VIEWS_SURFACE: "
            + unguarded);
  }

  /** True when a supertype, an interface, or any declared method signature names the view API. */
  private static boolean namesTheViewApi(Class<?> beanClass) {
    List<Class<?>> named = new ArrayList<>();
    for (Class<?> supertype = beanClass.getSuperclass();
        supertype != null;
        supertype = supertype.getSuperclass()) {
      named.add(supertype);
    }
    named.addAll(Arrays.asList(beanClass.getInterfaces()));
    for (Method method : beanClass.getDeclaredMethods()) {
      named.add(method.getReturnType());
      named.addAll(Arrays.asList(method.getParameterTypes()));
    }
    return named.stream().anyMatch(IcebergViewApiConditionTest::isViewApi);
  }

  private static boolean isViewApi(Class<?> type) {
    String name = type.getName();
    return VIEW_API_PACKAGES.stream().anyMatch(name::startsWith);
  }
}
