package com.linkedin.openhouse.tables.api.irc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * The tables service is also embedded, through {@code tables-test-fixtures_2.12}, with Iceberg 1.2
 * at runtime, where {@code org.apache.iceberg.UpdateRequirement} (Iceberg 1.4 and later) does not
 * exist. Spring introspects every declared method of a bean class while creating the bean, so a
 * bean whose declared methods (synthetic lambda methods included) or fields name that type stops
 * the whole embedded server from starting there. Code that needs it lives outside the beans, in
 * {@link IcebergRestRequirements}, and is reached only by an IRC write.
 */
class IcebergRestBeanSignaturesTest {

  private static final String ABSENT_FROM_ICEBERG_1_2 = "org.apache.iceberg.UpdateRequirement";

  @Test
  void noIrcBeanNamesAnIcebergTypeTheIceberg12RuntimeLacks() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
    List<String> offending = new ArrayList<>();
    int beans = 0;
    for (BeanDefinition definition :
        scanner.findCandidateComponents(getClass().getPackage().getName())) {
      Class<?> bean = Class.forName(definition.getBeanClassName());
      beans++;
      for (Method method : bean.getDeclaredMethods()) {
        List<Type> types = new ArrayList<>(List.of(method.getGenericParameterTypes()));
        types.add(method.getGenericReturnType());
        check(bean.getSimpleName() + "." + method.getName(), types, offending);
      }
      for (Constructor<?> constructor : bean.getDeclaredConstructors()) {
        check(
            bean.getSimpleName() + ".<init>",
            List.of(constructor.getGenericParameterTypes()),
            offending);
      }
      for (Field field : bean.getDeclaredFields()) {
        check(
            bean.getSimpleName() + "." + field.getName(),
            List.of(field.getGenericType()),
            offending);
      }
    }
    assertEquals(true, beans >= 3, "the scan found the IRC beans: " + beans);
    assertEquals(List.of(), offending);
  }

  private static void check(String member, List<Type> types, List<String> offending) {
    for (Type type : types) {
      if (type.getTypeName().contains(ABSENT_FROM_ICEBERG_1_2)) {
        offending.add(member + " names " + type.getTypeName());
      }
    }
  }
}
