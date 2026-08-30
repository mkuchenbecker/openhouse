package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.utils.NamespacePropertiesValidator;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.repository.PreservedKeyChecker;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.PostConstruct;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link NamespacesService}.
 *
 * <p>This layer owns authorization, identifier validation and the existence policy. The persisted
 * encoding is applied here through {@link NamespaceUtil#encode}, and the repository below receives
 * an opaque key it must not parse.
 *
 * <p>Existence is composed from two stores. The namespace store holds a row for every namespace
 * created through this API and for every database a table has been written into since the store
 * landed ({@link #ensureNamespace}). A database older than that has no row, and is derived from the
 * table store instead — the same derivation {@code GET /databases} has always used. Without that
 * second source this API would report that {@code prod} does not exist while {@code GET
 * /namespaces/prod/tables} listed its tables.
 */
@Component
public class NamespacesServiceImpl implements NamespacesService {

  @Autowired HouseNamespaceRepository houseNamespaceRepository;

  @Autowired OpenHouseInternalRepository openHouseInternalRepository;

  @Autowired AuthorizationUtils authorizationUtils;

  @Autowired ClusterProperties clusterProperties;

  @Autowired PreservedKeyChecker preservedKeyChecker;

  /**
   * Nesting is not implemented, and a cluster that asks for it must be told so at startup rather
   * than discover it as a 500 on the first two-level create. Raising the property today widens
   * {@link NamespaceUtil#validate} and nothing else: {@code /hts/databases} rejects the dot in the
   * encoded form, {@link NamespaceUtil#isTableNamespace} still means "exactly one level", the
   * design's metadata-table discriminator (§5.4) is absent, and {@code /v1} rejects nested paths.
   */
  @PostConstruct
  void rejectUnimplementedNamespaceDepth() {
    int maxDepth = clusterProperties.getClusterTablesNamespaceMaxDepth();
    if (maxDepth != NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH) {
      throw new IllegalStateException(
          String.format(
              "cluster.tables.namespace.max-depth is %s, but only %s is implemented. Nesting needs,"
                  + " at least: an encoded namespace the /hts/databases charset accepts (the '.'"
                  + " separator is rejected today), a depth-aware isTableNamespace, the"
                  + " metadata-table discriminator of design section 5.4, a childrenOf range query"
                  + " on the namespace store, and nested-path routing on /v1.",
              maxDepth, NamespaceUtil.DEFAULT_MAX_NAMESPACE_DEPTH));
    }
  }

  @Override
  public NamespaceMetadata createNamespace(
      Namespace namespace, Map<String, String> properties, String actingPrincipal) {
    validate(namespace);
    validateProperties(properties == null ? Collections.emptyMap() : properties);
    // Creating a namespace is authorized exactly the way creating the first table in a database is
    // today: CREATE_TABLE on the namespace being created (its parent, once nesting is enabled).
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(parentOrSelf(namespace)), actingPrincipal, Privileges.CREATE_TABLE);
    requireParentExists(namespace);

    if (findStored(namespace).isPresent() || findDerived(namespace).isPresent()) {
      throw new AlreadyExistsException("Namespace already exists: %s", namespace);
    }
    long now = System.currentTimeMillis();
    HouseNamespace saved =
        save(
            HouseNamespace.builder()
                .namespaceId(NamespaceUtil.encode(namespace))
                .properties(copy(properties))
                .creationTime(now)
                .lastModifiedTime(now)
                .build(),
            namespace);
    return metadata(saved);
  }

  @Override
  public void ensureNamespace(String databaseId) {
    String namespaceId = NamespaceUtil.encode(Namespace.of(databaseId));
    if (houseNamespaceRepository.findById(namespaceId).isPresent()) {
      return;
    }
    long now = System.currentTimeMillis();
    try {
      houseNamespaceRepository.save(
          HouseNamespace.builder()
              .namespaceId(namespaceId)
              .properties(new LinkedHashMap<>())
              .creationTime(now)
              .lastModifiedTime(now)
              .build());
    } catch (HouseTableConcurrentUpdateException
        | OptimisticLockingFailureException
        | DataIntegrityViolationException e) {
      // Another writer registered the same database first, which is the outcome this asked for.
    }
  }

  @Override
  public NamespaceMetadata loadNamespaceMetadata(Namespace namespace, String actingPrincipal) {
    validate(namespace);
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(namespace), actingPrincipal, Privileges.GET_TABLE_METADATA);
    Optional<HouseNamespace> stored = findStored(namespace);
    if (stored.isPresent()) {
      return metadata(stored.get());
    }
    return findDerived(namespace)
        .map(derived -> new NamespaceMetadata(NamespaceUtil.decode(derived), new LinkedHashMap<>()))
        .orElseThrow(() -> new NoSuchNamespaceException("Namespace does not exist: %s", namespace));
  }

  @Override
  public boolean namespaceExists(Namespace namespace, String actingPrincipal) {
    validate(namespace);
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(namespace), actingPrincipal, Privileges.GET_TABLE_METADATA);
    return findStored(namespace).isPresent() || findDerived(namespace).isPresent();
  }

  @Override
  public List<Namespace> listNamespaces(Namespace parent, String actingPrincipal) {
    boolean hasParent = parent != null && !parent.isEmpty();
    if (hasParent) {
      validate(parent);
      requireExists(parent);
    }
    int maxDepth = clusterProperties.getClusterTablesNamespaceMaxDepth();
    if (hasParent && parent.levels().length >= maxDepth) {
      // A child of a namespace already at the maximum depth would be one level deeper than
      // validate() permits, so the scan that would look for one is provably empty. At the shipped
      // max-depth of 1 that covers every non-empty parent, which is what keeps dropNamespace off
      // the unpaged findAll() below.
      return Collections.emptyList();
    }
    Set<String> encoded = new LinkedHashSet<>();
    for (HouseNamespace stored : houseNamespaceRepository.findAll()) {
      encoded.add(stored.getNamespaceId());
    }
    // Databases that predate the namespace store have no row; they are still namespaces.
    for (TableDtoPrimaryKey key : openHouseInternalRepository.findAllIds()) {
      encoded.add(key.getDatabaseId());
    }
    String prefix = hasParent ? NamespaceUtil.encode(parent) + "." : "";
    int depth = hasParent ? parent.levels().length : 0;
    List<Namespace> children = new ArrayList<>();
    for (String namespaceId : encoded) {
      if (!namespaceId.startsWith(prefix)) {
        continue;
      }
      Namespace candidate = NamespaceUtil.decode(namespaceId);
      // Immediate children only: the contiguous prefix range gives the subtree, this filters it
      // down to the level the spec asks for.
      if (candidate.levels().length == depth + 1) {
        children.add(candidate);
      }
    }
    return children;
  }

  @Override
  public void dropNamespace(Namespace namespace, String actingPrincipal) {
    validate(namespace);
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(namespace), actingPrincipal, Privileges.DELETE_TABLE);
    Optional<HouseNamespace> stored = findStored(namespace);
    Optional<String> derived = findDerived(namespace);
    if (!stored.isPresent() && !derived.isPresent()) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }
    // Emptiness spans two stores and is composed here: a derived name exists precisely because a
    // table names it, and child namespaces come from the namespace store. Either occupant is a 409.
    if (derived.isPresent() || !listNamespaces(namespace, actingPrincipal).isEmpty()) {
      throw new NamespaceNotEmptyException("Namespace %s is not empty", namespace);
    }
    houseNamespaceRepository.deleteById(stored.get().getNamespaceId());
  }

  @Override
  public NamespacePropertiesUpdateResult updateProperties(
      Namespace namespace,
      Set<String> removals,
      Map<String, String> updates,
      String actingPrincipal) {
    validate(namespace);
    Map<String, String> requestedUpdates = updates == null ? Collections.emptyMap() : updates;
    Set<String> requestedRemovals = removals == null ? Collections.emptySet() : removals;
    validateProperties(requestedUpdates);
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(namespace), actingPrincipal, Privileges.UPDATE_TABLE_METADATA);

    HouseNamespace stored = requireStoredOrRegisterDerived(namespace);
    Map<String, String> properties = copy(stored.getProperties());

    List<String> removed = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String key : requestedRemovals) {
      if (properties.remove(key) != null) {
        removed.add(key);
      } else {
        missing.add(key);
      }
    }
    properties.putAll(requestedUpdates);
    validateProperties(properties);

    save(
        stored
            .toBuilder()
            .properties(properties)
            .lastModifiedTime(System.currentTimeMillis())
            .build(),
        namespace);
    return new NamespacePropertiesUpdateResult(
        new ArrayList<>(requestedUpdates.keySet()), removed, missing);
  }

  /**
   * The namespace store is the only writable one; a database that only exists derivedly gets its
   * row written here, on the first write that needs somewhere to put a property.
   */
  private HouseNamespace requireStoredOrRegisterDerived(Namespace namespace) {
    Optional<HouseNamespace> stored = findStored(namespace);
    if (stored.isPresent()) {
      return stored.get();
    }
    String derived =
        findDerived(namespace)
            .orElseThrow(
                () -> new NoSuchNamespaceException("Namespace does not exist: %s", namespace));
    ensureNamespace(derived);
    return findStored(namespace)
        .orElseThrow(() -> new NoSuchNamespaceException("Namespace does not exist: %s", namespace));
  }

  private HouseNamespace save(HouseNamespace namespaceRow, Namespace namespace) {
    try {
      return houseNamespaceRepository.save(namespaceRow);
    } catch (HouseTableConcurrentUpdateException
        | OptimisticLockingFailureException
        | DataIntegrityViolationException e) {
      throw new CommitFailedException(
          e, "Namespace %s was modified concurrently; retry with the current state", namespace);
    }
  }

  private void validate(Namespace namespace) {
    NamespaceUtil.validate(namespace, clusterProperties.getClusterTablesNamespaceMaxDepth());
  }

  @SuppressWarnings("deprecation")
  private void validateProperties(Map<String, String> properties) {
    for (String key : properties.keySet()) {
      if (key != null && preservedKeyChecker.isKeyPreserved(key)) {
        throw new ValidationException(
            "Property key %s is reserved and cannot be set by a client", key);
      }
    }
    List<String> violations = NamespacePropertiesValidator.violations(properties);
    if (!violations.isEmpty()) {
      throw new ValidationException(String.join("; ", violations));
    }
  }

  private Optional<HouseNamespace> findStored(Namespace namespace) {
    return houseNamespaceRepository.findById(NamespaceUtil.encode(namespace));
  }

  /**
   * @return the stored spelling of the database a table names, when the table store holds one under
   *     this namespace. Case-insensitivity lives in the repository below, so the returned name is
   *     the one the table store holds rather than the one that was asked with.
   */
  private Optional<String> findDerived(Namespace namespace) {
    List<TableDto> tables =
        openHouseInternalRepository.searchTables(NamespaceUtil.encode(namespace));
    return tables.isEmpty() ? Optional.empty() : Optional.of(tables.get(0).getDatabaseId());
  }

  private void requireExists(Namespace namespace) {
    if (!findStored(namespace).isPresent() && !findDerived(namespace).isPresent()) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }
  }

  private void requireParentExists(Namespace namespace) {
    if (namespace.levels().length <= 1) {
      return;
    }
    requireExists(parentOrSelf(namespace));
  }

  private static NamespaceMetadata metadata(HouseNamespace stored) {
    return new NamespaceMetadata(
        NamespaceUtil.decode(stored.getNamespaceId()), copy(stored.getProperties()));
  }

  private static Namespace parentOrSelf(Namespace namespace) {
    String[] levels = namespace.levels();
    if (levels.length <= 1) {
      return namespace;
    }
    String[] parent = new String[levels.length - 1];
    System.arraycopy(levels, 0, parent, 0, parent.length);
    return Namespace.of(parent);
  }

  private static Map<String, String> copy(Map<String, String> properties) {
    return properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
  }
}
