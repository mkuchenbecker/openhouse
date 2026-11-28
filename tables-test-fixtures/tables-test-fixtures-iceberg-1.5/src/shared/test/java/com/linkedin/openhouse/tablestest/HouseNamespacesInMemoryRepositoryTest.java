package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;

/** The namespace stand-in against the repository contract it stands in for. */
public class HouseNamespacesInMemoryRepositoryTest extends HouseNamespaceRepositoryContract {

  private final HouseNamespacesInMemoryRepository repository =
      new HouseNamespacesInMemoryRepository();

  @Override
  protected HouseNamespaceRepository repository() {
    return repository;
  }
}
