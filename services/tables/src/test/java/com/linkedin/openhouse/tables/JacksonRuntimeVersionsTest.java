package com.linkedin.openhouse.tables;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact Jackson the tables service runs with on each build line, so that a dependency that
 * moves a Jackson module fails this test instead of silently changing what the service answers.
 * Jackson's version shows in answers: jackson-core writes the location text of a parse error, and
 * jackson-databind writes every native answer body and the text of its binding errors, which a 400
 * message carries.
 *
 * <p>The build passes the Jackson modules of the service's runtime classpath, which its boot jar
 * ships, as {@code openhouse.jackson.runtime} ({@code group:name:version}, comma-separated), and
 * the line as {@code openhouse.iceberg.line}. The embedded service of the Spark suites is shaded
 * from the test fixtures' own resolution; {@code JacksonShadedVersionsTest} there pins the same
 * lists.
 *
 * <ul>
 *   <li>The reference line ({@code -PicebergLinkedIn}, LinkedIn's Iceberg 1.5.2): exactly what
 *       LinkedIn main resolves. Iceberg 1.5.2 asks for Jackson 2.14.3; the root build forces
 *       jackson-databind and the BOM back to 2.13.4.
 *   <li>The default line (the fork's Iceberg 1.12): the same, except jackson-core, which Iceberg
 *       1.12's metadata (jackson-bom, core and databind 2.22.2) pulls up to 2.22.2. databind stays
 *       at main's 2.13.4 on purpose, held by the same force. The mix is link-compatible: a check of
 *       every class, method and field that databind 2.13.4 and the other modules below reference
 *       found each one in jackson-core 2.22.2, and no Jackson module compares versions at run time.
 * </ul>
 *
 * <p>Changing any of these versions changes answers, so it is a behavior change that needs a
 * recorded comparison against LinkedIn main first; then change the list here with it.
 */
class JacksonRuntimeVersionsTest {

  /** What LinkedIn main resolves for the tables service, and the reference line with it. */
  static final List<String> REFERENCE_LINE =
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

  /** The default line: the reference line's list with Iceberg 1.12's jackson-core. */
  static final List<String> DEFAULT_LINE =
      REFERENCE_LINE.stream()
          .map(
              m ->
                  m.startsWith("com.fasterxml.jackson.core:jackson-core:")
                      ? "com.fasterxml.jackson.core:jackson-core:2.22.2"
                      : m)
          .collect(Collectors.toList());

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

  @Test
  void runtimeClasspathHasTheLinesJackson() {
    assertThat(runtimeJackson())
        .containsExactlyElementsOf(referenceLine() ? REFERENCE_LINE : DEFAULT_LINE);
  }

  @Test
  void testsRunOnTheSameCoreAndDatabind() {
    assertThat(com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString())
        .isEqualTo(referenceLine() ? "2.14.3" : "2.22.2");
    assertThat(com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString())
        .isEqualTo("2.13.4");
  }
}
