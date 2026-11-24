package com.linkedin.openhouse;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SuppressWarnings({"PMD", "checkstyle:hideutilityclassconstructor"})
@SpringBootApplication(
    scanBasePackages = {
      "com.linkedin.openhouse.housetables",
      "com.linkedin.openhouse.common.audit",
      "com.linkedin.openhouse.common.exception",
      "com.linkedin.openhouse.cluster.configs",
      "com.linkedin.openhouse.cluster.storage"
    })
public class HouseTablesSpringApplication {

  public static void main(String[] args) {
    SpringApplication.run(HouseTablesSpringApplication.class, args);
  }

  /**
   * Writes the properties of the schemas springdoc makes from Spring Data's {@code Page} (named
   * {@code Page<content type>}) in alphabetical order, so that two builds of the same sources write
   * the same spec (work item C15). springdoc lists a schema's properties in the order Jackson finds
   * them, which for {@code Page}, an interface with getters and no fields, is the order of {@code
   * Class.getDeclaredMethods()} on {@code Page} and {@code Slice}: the JVM does not fix that order,
   * and it changed from one spec generation to the next. The schemas of the service's own classes
   * keep their field order, which is fixed. Only the order changes; the generated clients' models
   * of these schemas have the same fields.
   */
  @Bean
  OpenApiCustomiser pageSchemaPropertyOrderCustomizer() {
    return openApi -> {
      if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
        return;
      }
      openApi
          .getComponents()
          .getSchemas()
          .forEach(
              (name, schema) -> {
                Map<String, Schema> properties = schema.getProperties();
                if (name.startsWith("Page")
                    && properties != null
                    && properties.containsKey("pageable")
                    && properties.containsKey("totalPages")) {
                  schema.setProperties(new LinkedHashMap<>(new TreeMap<>(properties)));
                }
              });
    };
  }

  /** Need a separate bean to only surface this API document internally. */
  @Bean
  public OpenAPI houseTablesOpenAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("House Tables API")
                .description("API description for House Tables API")
                .termsOfService("http://swagger.io/terms")
                .version("v0.0.1")
                .license(new License().name("Apache 2.0").url("http://springdoc.org")));
  }
}
