package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * The in-memory (H2) stand-in for {@link HouseNamespaceRepository} used by /tables e2e tests, so
 * the namespace store does not require a running House Tables Service. With {@link Primary} it is
 * the default injection, exactly as {@link HouseTablesH2Repository} is for tables.
 */
@Repository
@Primary
public interface NamespacesH2Repository extends HouseNamespaceRepository {}
