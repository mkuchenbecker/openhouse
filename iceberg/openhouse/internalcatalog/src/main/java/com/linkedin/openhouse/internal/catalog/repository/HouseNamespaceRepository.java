package com.linkedin.openhouse.internal.catalog.repository;

import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Base interface for the repository backed by HouseTableService for storing and retrieving {@link
 * HouseNamespace} objects.
 *
 * <p>The key is the encoded namespace and the repository must not split, parse or interpret it.
 */
@Repository
public interface HouseNamespaceRepository extends CrudRepository<HouseNamespace, String> {

  /**
   * The direct children of {@code encodedParent} — stored namespaces exactly one level deeper —
   * ordered by encoded id.
   *
   * <p>Not {@code findAll()} filtered in the caller: listing a namespace's children must cost the
   * children, not the catalog. The store answers it as a bounded range over the encoded id, which
   * is contiguous precisely because the encoding dot-joins the levels.
   *
   * <p>Grandchildren are in the range but are not children, and the parent's own row sorts below
   * the range, so neither is returned. A parent with no children — including one that does not
   * exist — yields an empty list; existence is a separate question with a separate answer, and
   * folding it in here would give one call two failure modes for the same state.
   *
   * <p>At the shipped namespace depth of 1 nothing can be a child of anything, so this has no
   * production callers yet. It exists so that the slice which turns nesting on does not also have
   * to invent the store's listing primitive.
   */
  List<HouseNamespace> childrenOf(String encodedParent);
}
