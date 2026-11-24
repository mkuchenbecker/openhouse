package com.linkedin.openhouse.tables;
// CI measurement only (pull request #133): shifts the line numbers so every test that runs the
// tables service misses the Gradle build cache. Reverted by the next commit.

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;

@SuppressWarnings({"PMD", "checkstyle:hideutilityclassconstructor"})
@SpringBootApplication(
    scanBasePackages = {
      "com.linkedin.openhouse.tables",
      "com.linkedin.openhouse.tables.toggle",
      "com.linkedin.openhouse.tables.utils",
      "com.linkedin.openhouse.internal.catalog.toggle",
      "com.linkedin.openhouse.cluster.configs",
      "com.linkedin.openhouse.cluster.storage",
      "com.linkedin.openhouse.common.audit",
      "com.linkedin.openhouse.common.exception",
      "com.linkedin.openhouse.internal.catalog"
    })
@EntityScan(
    basePackages = {
      "com.linkedin.openhouse.tables.model",
      "com.linkedin.openhouse.internal.catalog.model",
      "com.linkedin.openhouse.tables.toggle.model"
    })
@EnableAutoConfiguration(
    exclude = {
      SecurityAutoConfiguration.class,
      ManagementWebSecurityAutoConfiguration.class,
      DataSourceAutoConfiguration.class
    })
public class TablesSpringApplication {

  public static void main(String[] args) {
    SpringApplication.run(TablesSpringApplication.class, args);
  }
}
