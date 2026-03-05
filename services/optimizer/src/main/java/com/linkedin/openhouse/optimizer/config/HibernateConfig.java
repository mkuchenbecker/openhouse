package com.linkedin.openhouse.optimizer.config;

import org.hibernate.boot.model.TypeContributions;
import org.hibernate.boot.model.TypeContributor;
import org.hibernate.service.ServiceRegistry;

/**
 * Registers custom Hibernate types with the Hibernate type system.
 *
 * <p>This class is loaded automatically by Hibernate via the Java SPI mechanism: the file {@code
 * META-INF/services/org.hibernate.boot.model.TypeContributor} lists this class as an implementation
 * of {@link TypeContributor}. Hibernate discovers and invokes it during bootstrap, before any
 * entity mappings are resolved.
 *
 * <p>Currently registers {@link JsonType} under the alias {@code "json"}, which allows entity
 * fields to use {@code @Type(type = "json")} for JSON column mappings.
 */
public class HibernateConfig implements TypeContributor {

  @Override
  public void contribute(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
    typeContributions.contributeType(new JsonType(), new String[] {"json"});
  }
}
