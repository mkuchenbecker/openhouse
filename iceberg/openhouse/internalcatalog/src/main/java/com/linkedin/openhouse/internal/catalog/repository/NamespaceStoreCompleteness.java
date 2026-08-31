package com.linkedin.openhouse.internal.catalog.repository;

import com.linkedin.openhouse.internal.catalog.repository.exception.NamespaceStoreCompletenessUnavailableException;
import java.util.Optional;

/**
 * Reads the one fact that says whether the namespace store may be treated as the source of truth
 * for which databases exist: whether a verification pass has read it back against the table store
 * and found nothing missing.
 *
 * <p>This is deliberately not part of {@link HouseNamespaceRepository}. That repository is about
 * namespace rows; this is about the state of a migration over all of them, and it is answered by a
 * different House Tables route.
 */
public interface NamespaceStoreCompleteness {

  /**
   * @return when a verification pass last found every database in the table store registered in the
   *     namespace store, or empty when no such pass has ever succeeded. Empty is also the honest
   *     answer for a store that has never been backfilled at all.
   * @throws NamespaceStoreCompletenessUnavailableException if the state cannot be read. An
   *     unreachable House Tables is not evidence of completeness, and it is not the same as an
   *     incomplete store either, so it is its own outcome rather than an empty answer.
   */
  Optional<Long> verifiedCompleteTimeMs();
}
