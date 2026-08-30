package com.linkedin.openhouse.internal.catalog.repository;

import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Base interface for the repository backed by HouseTableService for storing and retrieving {@link
 * HouseNamespace} objects.
 *
 * <p>The key is the encoded namespace and the repository must not split, parse or interpret it.
 */
@Repository
public interface HouseNamespaceRepository extends CrudRepository<HouseNamespace, String> {}
