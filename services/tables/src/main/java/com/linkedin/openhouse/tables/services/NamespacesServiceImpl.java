package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.utils.NamespacePropertiesValidator;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.tables.authorization.Privileges;
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
 * <p>Existence comes from the namespace store and nowhere else. It used to be composed with a
 * second source — a database with no row still existed if the table store held a table under it —
 * because the store could not yet be trusted to know about every database. It can now: every
 * table-creating path registers its database and fails the write if it cannot, and the backfill has
 * given every older database a row. Composing the two sources today would only reintroduce the
 * asymmetry it was covering for, where a database dropped from the namespace store reappears
 * because a table still names it.
 *
 * <p>The table store is still read for one thing, in {@link #dropNamespace}: whether a namespace
 * holds tables. That is occupancy, not existence — a namespace with tables in it is not empty — and
 * only the table store knows it.
 *
 * <p>Every read here is gated on {@link NamespaceStoreReadGate}, because a store that has not been
 * backfilled would answer all of them with a confident, well-formed "nothing".
 */
@Component
public class NamespacesServiceImpl implements NamespacesService {

  @Autowired HouseNamespaceRepository houseNamespaceRepository;

  @Autowired OpenHouseInternalRepository openHouseInternalRepository;

  @Autowired AuthorizationUtils authorizationUtils;

  @Autowired ClusterProperties clusterProperties;

  @Autowired PreservedKeyChecker preservedKeyChecker;

  @Autowired NamespaceStoreReadGate namespaceStoreReadGate;

  @Override
  public NamespaceMetadata createNamespace(
      Namespace namespace, Map<String, String> properties, String actingPrincipal) {
    validate(namespace);
    validateProperties(properties == null ? Collections.emptyMap() : properties);
    // Creating a namespace is authorized exactly the way creating the first table in a database is
    // today: CREATE_TABLE on the namespace being created (its parent, once nesting is enabled).
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(parentOrSelf(namespace)), actingPrincipal, Privileges.CREATE_TABLE);
    namespaceStoreReadGate.requireStoreIsAuthoritative();
    requireParentExists(namespace);

    if (findStored(namespace).isPresent()) {
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

  /**
   * The parent check is what keeps the namespace tree connected. Registering {@code a.b} while
   * {@code a} has no row would leave a child whose parent does not exist: no listing walk starts at
   * a root that is not there, so the database would be invisible to {@code listNamespaces}, and
   * undroppable through the namespace API — the same orphan that failing a registration silently
   * used to produce, arrived at through the table-create path instead.
   *
   * <p>It refuses rather than creating the missing ancestors. Iceberg's reference suite is the
   * specification here, and {@code CatalogTests#tableCreationWithoutNamespace} pins that a catalog
   * requiring namespace creation answers a table create into a namespace that does not exist with
   * {@link NoSuchNamespaceException} rather than conjuring the namespace; nothing in the suite
   * creates a nested namespace implicitly — {@code testListNestedNamespaces} and {@code
   * testDropNamespaceWithNestedNamespace} both create the parent explicitly first. Refusing is also
   * the only reversible answer: an implicitly created ancestor turns a typo into a permanent
   * namespace that nothing will ever clean up.
   *
   * <p>Unreachable at the shipped max-depth of 1, where every namespace is a root and this returns
   * before looking at anything. That also means the check cannot misfire on a cluster whose
   * namespace store was never backfilled: a nested namespace can only be reached through {@code
   * createNamespace}, which is gated on the store being authoritative and enforces the same rule,
   * so there is no unbackfilled cluster that has one.
   *
   * @throws NoSuchNamespaceException if the database's parent namespace does not exist
   */
  @Override
  public void ensureNamespace(String databaseId) {
    // decode(), not Namespace.of(): a nested databaseId arrives encoded, and Namespace.of("a.b")
    // would read it as a single level literally named "a.b" — an identifier with no parent to
    // check, which is exactly the check being added here.
    Namespace namespace = NamespaceUtil.decode(databaseId);
    String namespaceId = NamespaceUtil.encode(namespace);
    if (houseNamespaceRepository.findById(namespaceId).isPresent()) {
      return;
    }
    requireParentExists(namespace);
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
    namespaceStoreReadGate.requireStoreIsAuthoritative();
    return findStored(namespace)
        .map(NamespacesServiceImpl::metadata)
        .orElseThrow(() -> new NoSuchNamespaceException("Namespace does not exist: %s", namespace));
  }

  @Override
  public boolean namespaceExists(Namespace namespace, String actingPrincipal) {
    validate(namespace);
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(namespace), actingPrincipal, Privileges.GET_TABLE_METADATA);
    namespaceStoreReadGate.requireStoreIsAuthoritative();
    return findStored(namespace).isPresent();
  }

  @Override
  public List<Namespace> listNamespaces(Namespace parent, String actingPrincipal) {
    boolean hasParent = parent != null && !parent.isEmpty();
    if (hasParent) {
      validate(parent);
    }
    namespaceStoreReadGate.requireStoreIsAuthoritative();
    if (hasParent) {
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
    if (hasParent) {
      // The store's own range query, not findAll() filtered here: listing one namespace's children
      // must cost the children, not the catalog. It returns direct children only, so there is
      // nothing left to narrow.
      List<Namespace> children = new ArrayList<>();
      for (HouseNamespace child :
          houseNamespaceRepository.childrenOf(NamespaceUtil.encode(parent))) {
        children.add(NamespaceUtil.decode(child.getNamespaceId()));
      }
      return children;
    }
    // The roots have no parent to range over, so they are the one listing that still reads the
    // whole store. At every shipped depth that is the listing callers already pay for today.
    Set<String> encoded = new LinkedHashSet<>();
    for (HouseNamespace stored : houseNamespaceRepository.findAll()) {
      encoded.add(stored.getNamespaceId());
    }
    List<Namespace> roots = new ArrayList<>();
    for (String namespaceId : encoded) {
      Namespace candidate = NamespaceUtil.decode(namespaceId);
      if (candidate.levels().length == 1) {
        roots.add(candidate);
      }
    }
    return roots;
  }

  @Override
  public void dropNamespace(Namespace namespace, String actingPrincipal) {
    validate(namespace);
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(namespace), actingPrincipal, Privileges.DELETE_TABLE);
    namespaceStoreReadGate.requireStoreIsAuthoritative();
    HouseNamespace stored =
        findStored(namespace)
            .orElseThrow(
                () -> new NoSuchNamespaceException("Namespace does not exist: %s", namespace));
    // Existence is the namespace store's alone, but emptiness is not: a namespace holding tables is
    // not empty, and only the table store knows whether it holds any. Child namespaces come from
    // the namespace store. Either occupant is a 409.
    if (holdsTables(namespace) || !listNamespaces(namespace, actingPrincipal).isEmpty()) {
      throw new NamespaceNotEmptyException("Namespace %s is not empty", namespace);
    }
    houseNamespaceRepository.deleteById(stored.getNamespaceId());
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

    namespaceStoreReadGate.requireStoreIsAuthoritative();
    HouseNamespace stored =
        findStored(namespace)
            .orElseThrow(
                () -> new NoSuchNamespaceException("Namespace does not exist: %s", namespace));
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
   * @return whether the table store holds any table under this namespace. This is the one question
   *     the table store is still asked, and it is about occupancy rather than existence: a
   *     namespace that holds tables cannot be dropped, whether or not the namespace store agrees it
   *     is there.
   */
  private boolean holdsTables(Namespace namespace) {
    return !openHouseInternalRepository.searchTables(NamespaceUtil.encode(namespace)).isEmpty();
  }

  private void requireExists(Namespace namespace) {
    if (!findStored(namespace).isPresent()) {
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
