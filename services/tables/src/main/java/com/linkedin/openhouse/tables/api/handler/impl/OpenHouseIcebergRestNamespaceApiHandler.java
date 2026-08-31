package com.linkedin.openhouse.tables.api.handler.impl;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.tables.api.handler.IcebergRestApiHandler;
import com.linkedin.openhouse.tables.api.handler.IcebergRestNamespaceApiHandler;
import com.linkedin.openhouse.tables.services.NamespaceMetadata;
import com.linkedin.openhouse.tables.services.NamespacePropertiesUpdateResult;
import com.linkedin.openhouse.tables.services.NamespacesService;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default Iceberg REST namespace adapter, backed by {@link NamespacesService}.
 *
 * <p>Owns the wire-to-domain translation only: {@link IcebergRestNamespaceWireForm} decodes the
 * {@code 0x1F}-separated wire form, the service owns authorization and the existence policy, and
 * the persisted (dot-joined) encoding is applied below both.
 */
@Component
@ConditionalOnProperty(value = "cluster.tables.iceberg-rest.enabled", havingValue = "true")
public class OpenHouseIcebergRestNamespaceApiHandler implements IcebergRestNamespaceApiHandler {

  private final NamespacesService namespacesService;

  private final ClusterProperties clusterProperties;

  public OpenHouseIcebergRestNamespaceApiHandler(
      NamespacesService namespacesService, ClusterProperties clusterProperties) {
    this.namespacesService = namespacesService;
    this.clusterProperties = clusterProperties;
  }

  @Override
  public ListNamespacesResponse listNamespaces(
      String prefix, String parent, String pageToken, Integer pageSize) {
    validatePrefix(prefix);
    validateSinglePageRequest(pageToken);
    // An empty parent is "no parent" per the spec's backward-compatibility note, and an empty
    // pageToken is what every Iceberg Java client since 1.6.0 sends: neither is an error.
    Namespace parentNamespace =
        parent == null || parent.isEmpty() ? Namespace.empty() : readNamespace(parent);
    List<Namespace> namespaces = namespacesService.listNamespaces(parentNamespace, principal());
    return ListNamespacesResponse.builder().addAll(namespaces).build();
  }

  @Override
  public CreateNamespaceResponse createNamespace(String prefix, CreateNamespaceRequest request) {
    validatePrefix(prefix);
    request.validate();
    NamespaceMetadata created =
        namespacesService.createNamespace(
            request.namespace(),
            request.properties() == null ? Collections.emptyMap() : request.properties(),
            principal());
    return CreateNamespaceResponse.builder()
        .withNamespace(created.getNamespace())
        .setProperties(created.getProperties())
        .build();
  }

  @Override
  public GetNamespaceResponse loadNamespaceMetadata(String prefix, String namespace) {
    validatePrefix(prefix);
    NamespaceMetadata loaded =
        namespacesService.loadNamespaceMetadata(readNamespace(namespace), principal());
    // The stored namespace, not the requested one: lookup is case-insensitive, so echoing the
    // request would name a namespace that no listing contains and that DELETE would then destroy.
    return GetNamespaceResponse.builder()
        .withNamespace(loaded.getNamespace())
        .setProperties(loaded.getProperties())
        .build();
  }

  @Override
  public void namespaceExists(String prefix, String namespace) {
    validatePrefix(prefix);
    Namespace decoded = readNamespace(namespace);
    if (!namespacesService.namespaceExists(decoded, principal())) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", decoded);
    }
  }

  @Override
  public void dropNamespace(String prefix, String namespace) {
    validatePrefix(prefix);
    namespacesService.dropNamespace(readNamespace(namespace), principal());
  }

  @Override
  public UpdateNamespacePropertiesResponse updateProperties(
      String prefix, String namespace, UpdateNamespacePropertiesRequest request) {
    validatePrefix(prefix);
    // Iceberg's own request validation owns the removals-vs-updates overlap and raises
    // org.apache.iceberg.exceptions.UnprocessableEntityException (422) for it; restating the check
    // here would only be dead code that never runs.
    request.validate();
    Namespace decoded = readNamespace(namespace);
    Set<String> removals =
        request.removals() == null
            ? Collections.emptySet()
            : new LinkedHashSet<>(request.removals());
    Map<String, String> updates =
        request.updates() == null ? Collections.emptyMap() : request.updates();
    NamespacePropertiesUpdateResult result =
        namespacesService.updateProperties(decoded, removals, updates, principal());
    return UpdateNamespacePropertiesResponse.builder()
        .addUpdated(result.getUpdated())
        .addRemoved(result.getRemoved())
        .addMissing(result.getMissing())
        .build();
  }

  /**
   * Decode and validate the namespace a route names.
   *
   * <p>A route that names an existing namespace never answers "you asked wrongly" — a syntactically
   * invalid namespace names a resource that cannot exist, so it is a 404 with the same message and
   * type an absent one gets. That mapping covers the identifier only: it deliberately does not span
   * the service call, where a {@link ValidationException} means something else entirely (a reserved
   * property key, an oversized property bag) and owes the client a 400 that says so.
   */
  private Namespace readNamespace(String namespace) {
    Namespace decoded = IcebergRestNamespaceWireForm.decode(namespace);
    try {
      NamespaceUtil.validate(decoded, clusterProperties.getClusterTablesNamespaceMaxDepth());
    } catch (ValidationException e) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", decoded);
    }
    return decoded;
  }

  private static String principal() {
    return extractAuthenticatedUserPrincipal();
  }

  private static void validatePrefix(String prefix) {
    if (!IcebergRestApiHandler.ICEBERG_REST_PREFIX.equals(prefix)) {
      throw new IllegalArgumentException("Unsupported Iceberg REST prefix");
    }
  }

  /**
   * This catalog returns the complete listing in one page and never issues a continuation token, so
   * the only page token it can honour is the empty one every client sends on the first request.
   */
  private static void validateSinglePageRequest(String pageToken) {
    if (pageToken != null && !pageToken.isEmpty()) {
      throw new IllegalArgumentException("Invalid Iceberg REST page token");
    }
  }
}
