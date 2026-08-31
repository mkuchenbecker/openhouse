package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
public interface NamespacesH2Repository extends HouseNamespaceRepository {
  /**
   * The real store folds case ({@code DatabaseHtsJdbcRepository.findByDatabaseIdIgnoreCase}). The
   * stand-in has to fold it too, or every test that runs against it is testing a store that does
   * not exist -- which is how a namespace loadable as {@code mydb}, absent from every listing and
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

  /**
   * The subtree range the {@code childrenOf} contract is built on, expressed the same way the House
   * Tables store expresses it: a half-open range over the encoded id, which is contiguous because
   * the encoding dot-joins the levels. A {@code LIKE} prefix would not do — the identifier charset
   * admits {@code _}, which SQL {@code LIKE} reads as a single-character wildcard.
   */
  @Query(
      "SELECT n FROM HouseNamespace n WHERE n.namespaceId >= :lowerBound"
          + " AND n.namespaceId < :upperBound ORDER BY n.namespaceId")
  List<HouseNamespace> findAllInSubtree(
      @Param("lowerBound") String lowerBound, @Param("upperBound") String upperBound);

  /** The range gives the subtree; the separator count narrows it to the children. */
  @Override
  default List<HouseNamespace> childrenOf(String encodedParent) {
    return findAllInSubtree(
            NamespaceUtil.subtreeLowerBound(encodedParent),
            NamespaceUtil.subtreeUpperBound(encodedParent))
        .stream()
        .filter(namespace -> NamespaceUtil.isDirectChild(encodedParent, namespace.getNamespaceId()))
        .collect(Collectors.toList());
  }
}
