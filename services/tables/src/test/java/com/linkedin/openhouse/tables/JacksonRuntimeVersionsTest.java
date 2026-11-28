package com.linkedin.openhouse.tables;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the Jackson the tables service runs with on each build line, so that a dependency that
 * raises a Jackson module fails this test instead of silently changing what the service answers
 * (jackson-core 2.22, for one, no longer quotes the request body in a parse error's location, which
 * a 500 message shows).
 *
 * <p>The build passes the Jackson modules of the service's runtime classpath, which its boot jar
 * ships, as {@code openhouse.jackson.runtime} ({@code group:name:version}, comma-separated), and
 * the line as {@code openhouse.iceberg.line}.
 *
 * <ul>
 *   <li>The default line (the fork's Iceberg 1.12, which needs jackson-core 2.22): every Jackson
 *       module on one minor, the fork's.
 *   <li>The reference line ({@code -PicebergLinkedIn}, LinkedIn's Iceberg 1.5.2): exactly what
 *       LinkedIn's own build resolves, mixed minors included, so that the line stays the behavior
 *       the default line is measured against.
 * </ul>
 */
class JacksonRuntimeVersionsTest {

  private static final String DEFAULT_LINE_MINOR = "2.22";

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

  private static boolean referenceLine() {
    String line = System.getProperty("openhouse.iceberg.line");
    assertThat(line).as("openhouse.iceberg.line, set by the build").isIn("default", "linkedin");
    return "linkedin".equals(line);
  }

  private static List<String> runtimeJackson() {
    String modules = System.getProperty("openhouse.jackson.runtime");
    assertThat(modules).as("openhouse.jackson.runtime, set by the build").isNotBlank();
    return Arrays.stream(modules.split(",")).sorted().collect(Collectors.toList());
  }

  private static String minor(String version) {
    String[] parts = version.split("\\.");
    return parts[0] + "." + parts[1];
  }

  @Test
  void runtimeClasspathHasTheLinesJackson() {
    List<String> modules = runtimeJackson();
    if (referenceLine()) {
      assertThat(modules).containsExactlyElementsOf(REFERENCE_LINE);
      return;
    }
    assertThat(modules)
        .anyMatch(m -> m.startsWith("com.fasterxml.jackson.core:jackson-core:"))
        .anyMatch(m -> m.startsWith("com.fasterxml.jackson.core:jackson-databind:"))
        .anyMatch(m -> m.startsWith("com.fasterxml.jackson.core:jackson-annotations:"));
    assertThat(modules)
        .as("every Jackson module of the runtime classpath on Jackson " + DEFAULT_LINE_MINOR)
        .allMatch(m -> minor(m.substring(m.lastIndexOf(':') + 1)).equals(DEFAULT_LINE_MINOR));
  }

  @Test
  void testsRunOnTheSameCoreAndDatabind() {
    String core = com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString();
    String databind = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString();
    if (referenceLine()) {
      assertThat(core).isEqualTo("2.14.3");
      assertThat(databind).isEqualTo("2.13.4");
    } else {
      assertThat(minor(core)).isEqualTo(DEFAULT_LINE_MINOR);
      assertThat(minor(databind)).isEqualTo(DEFAULT_LINE_MINOR);
    }
  }
}
