package com.linkedin.openhouse.tablestest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact Jackson that the fixtures' uber jar shades with the embedded tables service, the
 * service the Spark suites run against. The uber jar is built from this project's own runtime
 * classpath, not from the tables service's, so the two could differ; this test holds it to the same
 * lists as the tables service's {@code JacksonRuntimeVersionsTest} (see there for why each version
 * is what it is). A change to either list is a behavior change and changes both tests.
 *
 * <p>The build passes the runtime classpath's Jackson modules as {@code openhouse.jackson.runtime}
 * and the line as {@code openhouse.iceberg.line}.
 */
class JacksonShadedVersionsTest {

  private static final List<String> REFERENCE_LINE =
      Arrays.asList(
          "com.fasterxml.jackson.core:jackson-annotations:2.14.2",
          "com.fasterxml.jackson.core:jackson-core:2.14.3",
          "com.fasterxml.jackson.core:jackson-databind:2.13.4",
          "com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.13.5",
          "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.14.2",
          "com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.13.5",
          "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.14.2",
          "com.fasterxml.jackson.jaxrs:jackson-jaxrs-base:2.13.4",
          "com.fasterxml.jackson.jaxrs:jackson-jaxrs-json-provider:2.13.4",
          "com.fasterxml.jackson.module:jackson-module-jaxb-annotations:2.13.4",
          "com.fasterxml.jackson.module:jackson-module-parameter-names:2.13.4",
          "com.fasterxml.jackson:jackson-bom:2.13.4");

  private static final List<String> DEFAULT_LINE =
      REFERENCE_LINE.stream()
          .map(
              m ->
                  m.startsWith("com.fasterxml.jackson.core:jackson-core:")
                      ? "com.fasterxml.jackson.core:jackson-core:2.22.2"
                      : m)
          .collect(Collectors.toList());

  @Test
  void shadedServiceHasTheLinesJackson() {
    String line = System.getProperty("openhouse.iceberg.line");
    assertThat(line).as("openhouse.iceberg.line, set by the build").isIn("default", "linkedin");
    String modules = System.getProperty("openhouse.jackson.runtime");
    assertThat(modules).as("openhouse.jackson.runtime, set by the build").isNotBlank();
    assertThat(Arrays.stream(modules.split(",")).sorted().collect(Collectors.toList()))
        .containsExactlyElementsOf("linkedin".equals(line) ? REFERENCE_LINE : DEFAULT_LINE);
  }
}
