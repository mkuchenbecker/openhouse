package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * The in-memory (H2) stand-in for {@link HouseNamespaceRepository} injected into tests that boot
 * the tables service without a real House Table Service. Guarded the same way {@link
 * HouseTablesH2Repository} is, so a consumer that wants the real HTS-backed repository opts out of
 * both together.
 */
@Repository
@Primary
@ConditionalOnProperty(
    name = "openhouse.htsStub.enabled",
    havingValue = "true",
    matchIfMissing = true)
public interface NamespacesH2Repository extends HouseNamespaceRepository {}
