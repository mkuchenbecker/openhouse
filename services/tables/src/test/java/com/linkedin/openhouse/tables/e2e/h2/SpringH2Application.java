package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.audit.DummyServiceAuditHandler;
import com.linkedin.openhouse.common.audit.model.ServiceAuditEvent;
import com.linkedin.openhouse.internal.catalog.repository.NamespaceStoreCompleteness;
import com.linkedin.openhouse.tables.audit.DummyTableAuditHandler;
import com.linkedin.openhouse.tables.audit.model.TableAuditEvent;
import java.util.Optional;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;

@SpringBootApplication
@ComponentScan(
    basePackages = {
      "com.linkedin.openhouse.tables.api",
      "com.linkedin.openhouse.tables.authorization",
      "com.linkedin.openhouse.tables.dto.mapper",
      "com.linkedin.openhouse.tables.controller",
      "com.linkedin.openhouse.tables.services",
      "com.linkedin.openhouse.tables.config",
      "com.linkedin.openhouse.internal.catalog.toggle",
      "com.linkedin.openhouse.internal.catalog",
      "com.linkedin.openhouse.cluster.configs",
      "com.linkedin.openhouse.cluster.storage",
      "com.linkedin.openhouse.tables.repository",
      "com.linkedin.openhouse.tables.utils",
      "com.linkedin.openhouse.common.exception.handler",
      "com.linkedin.openhouse.common.audit",
      "com.linkedin.openhouse.tables.audit",
      "com.linkedin.openhouse.tables.toggle"
    })
@EntityScan(
    basePackages = {
      "com.linkedin.openhouse.tables.model",
      "com.linkedin.openhouse.internal.catalog.model",
      "com.linkedin.openhouse.tables.toggle.model"
    })
@EnableAutoConfiguration(
    exclude = {SecurityAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class})
public class SpringH2Application {

  public static void main(String[] args) {
    SpringApplication.run(SpringH2Application.class, args);
  }

  /**
   * The stand-in for the backfill marker, alongside {@link NamespacesH2Repository}: there is no
   * House Tables here to hold it, and the real reader would be talking to a port nothing is
   * listening on.
   *
   * <p>Verified is the truth for these contexts rather than a convenience. The H2 namespace store
   * starts empty and every database in it is registered by the write that created it, so there is
   * no database it can be missing. {@code test.namespace-store.verified=false} reports an
   * unbackfilled store instead; set it through {@code @SpringBootTest(properties = ...)}, because
   * the gate latches once and a test of the refusal therefore needs a context of its own.
   */
  @Bean
  @Primary
  NamespaceStoreCompleteness provideNamespaceStoreCompleteness(
      @Value("${test.namespace-store.verified:true}") boolean verified) {
    return () -> verified ? Optional.of(1L) : Optional.empty();
  }

  @Bean
  public AuditHandler<ServiceAuditEvent> serviceAuditHandler() {
    return Mockito.mock(DummyServiceAuditHandler.class);
  }

  @Bean
  public AuditHandler<TableAuditEvent> tableAuditHandler() {
    return Mockito.mock(DummyTableAuditHandler.class);
  }
}
