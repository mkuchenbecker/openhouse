package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link NamespacesService}.
 *
 * <p>This layer owns authorization, identifier validation and the existence policy. The persisted
 * encoding is applied here through {@link NamespaceUtil#encode}, and the repository below receives
 * an opaque key it must not parse.
 */
@Component
public class NamespacesServiceImpl implements NamespacesService {

  /** The server-owned property space that clients may not write. */
  static final String RESERVED_PROPERTY_PREFIX = "openhouse.";

  @Autowired HouseNamespaceRepository houseNamespaceRepository;

  @Autowired OpenHouseInternalRepository openHouseInternalRepository;

  @Autowired AuthorizationUtils authorizationUtils;

  @Autowired ClusterProperties clusterProperties;

  @Override
  public Map<String, String> createNamespace(
      Namespace namespace, Map<String, String> properties, String actingPrincipal) {
    validate(namespace);
    validateProperties(properties == null ? Collections.emptyMap() : properties);
    // Creating a namespace is authorized exactly the way creating the first table in a database is
    // today: CREATE_TABLE on the namespace being created (its parent, once nesting is enabled).
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(parentOrSelf(namespace)), actingPrincipal, Privileges.CREATE_TABLE);
    requireParentExists(namespace);

    String namespaceId = NamespaceUtil.encode(namespace);
    if (houseNamespaceRepository.findById(namespaceId).isPresent()) {
      throw new AlreadyExistsException("Namespace already exists: %s", namespace);
    }
    long now = System.currentTimeMillis();
    HouseNamespace saved =
        houseNamespaceRepository.save(
            HouseNamespace.builder()
                .namespaceId(namespaceId)
                .properties(copy(properties))
                .creationTime(now)
                .lastModifiedTime(now)
                .build());
    return copy(saved.getProperties());
  }

  @Override
  public Map<String, String> loadNamespaceMetadata(Namespace namespace, String actingPrincipal) {
    validate(namespace);
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(namespace), actingPrincipal, Privileges.GET_TABLE_METADATA);
    return copy(requireNamespace(namespace).getProperties());
  }

  @Override
  public boolean namespaceExists(Namespace namespace, String actingPrincipal) {
    validate(namespace);
    authorizationUtils.checkDatabasePrivilege(
        NamespaceUtil.encode(namespace), actingPrincipal, Privileges.GET_TABLE_METADATA);
    return houseNamespaceRepository.findById(NamespaceUtil.encode(namespace)).isPresent();
  }

  @Override
  public List<Namespace> listNamespaces(Namespace parent, String actingPrincipal) {
    if (parent != null && !parent.isEmpty()) {
      validate(parent);
      requireNamespace(parent);
    }
    String prefix = parent == null || parent.isEmpty() ? "" : NamespaceUtil.encode(parent) + ".";
    int depth = parent == null ? 0 : parent.levels().length;
    List<Namespace> children = new ArrayList<>();
    for (HouseNamespace stored : houseNamespaceRepository.findAll()) {
      String namespaceId = stored.getNamespaceId();
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
    HouseNamespace stored = requireNamespace(namespace);
    // Emptiness spans two stores and is composed here: tables from the table store, child
    // namespaces from the namespace store. Either kind of occupant is a 409.
    if (!openHouseInternalRepository.searchTables(stored.getNamespaceId()).isEmpty()
        || !listNamespaces(namespace, actingPrincipal).isEmpty()) {
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

    HouseNamespace stored = requireNamespace(namespace);
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

    houseNamespaceRepository.save(
        stored
            .toBuilder()
            .properties(properties)
            .lastModifiedTime(System.currentTimeMillis())
            .build());
    return new NamespacePropertiesUpdateResult(
        new ArrayList<>(requestedUpdates.keySet()), removed, missing);
  }

  private void validate(Namespace namespace) {
    NamespaceUtil.validate(namespace, clusterProperties.getClusterTablesNamespaceMaxDepth());
  }

  private static void validateProperties(Map<String, String> properties) {
    for (String key : properties.keySet()) {
      if (key != null && key.startsWith(RESERVED_PROPERTY_PREFIX)) {
        throw new ValidationException(
            "Property key %s is reserved and cannot be set by a client", key);
      }
    }
  }

  private HouseNamespace requireNamespace(Namespace namespace) {
    Optional<HouseNamespace> stored =
        houseNamespaceRepository.findById(NamespaceUtil.encode(namespace));
    return stored.orElseThrow(
        () -> new NoSuchNamespaceException("Namespace does not exist: %s", namespace));
  }

  private void requireParentExists(Namespace namespace) {
    if (namespace.levels().length <= 1) {
      return;
    }
    Namespace parent = parentOrSelf(namespace);
    if (!houseNamespaceRepository.findById(NamespaceUtil.encode(parent)).isPresent()) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", parent);
    }
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
