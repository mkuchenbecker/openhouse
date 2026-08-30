package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The in-memory (H2) stand-in for {@link HouseNamespaceRepository} used by /tables e2e tests, so
 * the namespace store does not require a running House Tables Service. With {@link Primary} it is
 * the default injection, exactly as {@link HouseTablesH2Repository} is for tables.
 */
@Repository
@Primary
public interface NamespacesH2Repository extends HouseNamespaceRepository {
  /**
   * The real store folds case ({@code DatabaseHtsJdbcRepository.findByDatabaseIdIgnoreCase}). The
   * stand-in has to fold it too, or every test that runs against it is testing a store that does
   * not exist — which is how a namespace loadable as {@code mydb}, absent from every listing and
   * droppable by a name nobody created, got through in the first place.
   */
  Optional<HouseNamespace> findByNamespaceIdIgnoreCase(String namespaceId);

  boolean existsByNamespaceIdIgnoreCase(String namespaceId);

  @Transactional
  @Modifying
  void deleteByNamespaceIdIgnoreCase(String namespaceId);

  @Override
  default Optional<HouseNamespace> findById(String namespaceId) {
    return findByNamespaceIdIgnoreCase(namespaceId);
  }

  @Override
  default boolean existsById(String namespaceId) {
    return existsByNamespaceIdIgnoreCase(namespaceId);
  }

  @Override
  default void deleteById(String namespaceId) {
    deleteByNamespaceIdIgnoreCase(namespaceId);
  }
}
